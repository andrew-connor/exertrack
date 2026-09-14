package com.exertrack.app.engine

import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.SessionStatus
import com.exertrack.app.data.Settings
import com.exertrack.app.data.Workout
import com.exertrack.app.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The workout state machine. See docs/DESIGN.md for the phase table. All methods must be called
 * from a single thread (the main thread in the app; the test thread in unit tests).
 */
class WorkoutEngine(
    private val clock: Clock,
    private val cues: Cues,
    private val store: SessionStore,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val zoneId: () -> String = { TimeZone.getDefault().id },
) {
    companion object {
        const val TICK_MS = 100L
        const val DEBOUNCE_MS = 600L
        const val SNAPSHOT_EVERY_MS = 5_000L
        /** A held countdown starts anyway if the speech engine never reports the announcement done. */
        const val ANNOUNCE_TIMEOUT_MS = 8_000L
        /** A rest longer than this announces the exercise again when it ends. */
        const val REANNOUNCE_AFTER_REST_SEC = 15
    }

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    // ---- internal session bookkeeping ----
    private var workout: Workout? = null
    private var sessionId: String? = null
    private var phase: Phase = Phase.IDLE
    private var stepIndex = 0
    private var paused = false
    /** Length of the current countdown; null for count-up phases (WAITING, untimed EXERCISE). */
    private var phaseDurationMs: Long? = null
    private var phaseElapsedBeforeSegment = 0L
    private var segmentStartedAt = 0L
    private var pauseStartedAt: Long? = null
    private var pausedMsAccum = 0L
    private var startedAtWall: Long? = null
    private val completed = LinkedHashSet<Int>()
    private var lastAutoTransitionAt = Long.MIN_VALUE / 2
    private var lastTickSecond = -1
    private var lastSnapshotAt = 0L
    private var logEntry: LogEntry? = null
    private var finishedEntry: LogEntry? = null
    private var tickerJob: Job? = null

    // ---- announcement hold: a countdown waits for its spoken announcement to finish ----
    private var holding = false
    private var holdToken = 0
    private var holdStartedAt = 0L
    /** The GO chime that marks the start of a held exercise, played when the hold is released. */
    private var pendingGoChime = false

    private val rows: List<ExerciseRow> get() = workout?.rows ?: emptyList()
    private val totalSteps: Int get() = rows.size * (workout?.rounds ?: 1)
    private fun rowAt(step: Int): ExerciseRow? = if (rows.isEmpty()) null else rows[step % rows.size]
    private val isActive: Boolean get() = phase != Phase.IDLE && phase != Phase.FINISHED
    private val running: Boolean get() = isActive && !paused && !holding

    // ---- public API ----

    /** Show [workout] in IDLE. Ignored while a session for another workout is active. */
    fun load(workout: Workout) {
        if (isActive && this.workout?.id != workout.id) return
        if (isActive) { updateWorkout(workout); return }
        if (phase == Phase.FINISHED && this.workout?.id == workout.id) {
            // Same workout re-opened after it finished: keep the completion dialog until Save.
            this.workout = workout
            publish()
            return
        }
        // IDLE, or FINISHED for a different workout: that session is already closed in the log,
        // so drop it and show the requested workout in IDLE.
        this.workout = workout
        resetInternals()
        publish()
    }

    /** Swap in an edited copy of the current workout, adjusting a running clock if its row changed. */
    fun updateWorkout(workout: Workout) {
        val old = this.workout
        this.workout = workout
        if (old == null || !isActive) { publish(); return }
        if (rows.isEmpty()) { endSession(keepLog = false); return }
        if (stepIndex >= totalSteps) stepIndex = totalSteps - 1
        val now = clock.elapsedRealtime()
        when (phase) {
            Phase.EXERCISE -> {
                val row = rowAt(stepIndex)!!
                val newDuration = row.durationSec?.takeIf { it > 0 }?.let { it * 1000L }
                if (newDuration != phaseDurationMs) {
                    phaseDurationMs = newDuration
                    if (newDuration != null && newDuration - phaseElapsedMs(now) <= 0) {
                        completeStep()
                        return
                    }
                    if (newDuration == null && paused) resume()
                }
            }
            Phase.REST -> {
                val prev = rowAt(stepIndex - 1)
                val newRest = prev?.triggerSec?.takeIf { it > 0 }?.let { it * 1000L }
                if (newRest == null) { enterWaiting(stepIndex); return }
                if (newRest != phaseDurationMs) {
                    phaseDurationMs = newRest
                    if (newRest - phaseElapsedMs(now) <= 0) { enterExercise(stepIndex, announce = true, auto = false); return }
                }
            }
            Phase.LEAD_IN -> {
                val newLead = workout.leadInSec.takeIf { it > 0 }?.let { it * 1000L }
                if (newLead == null) { enterExercise(stepIndex, announce = false, auto = false); return }
                phaseDurationMs = newLead
                if (newLead - phaseElapsedMs(now) <= 0) { enterExercise(stepIndex, announce = false, auto = false); return }
            }
            else -> {}
        }
        ensureTicker()
        publish()
    }

    /** The big button. */
    fun primaryTap() {
        val now = clock.elapsedRealtime()
        if (now - lastAutoTransitionAt < DEBOUNCE_MS) return
        if (holding) {
            // Tapping during the announcement skips the rest of it and starts the clock now.
            cues.stopSpeaking()
            releaseHold(holdToken)
            return
        }
        when (phase) {
            Phase.IDLE -> startSession(0)
            Phase.LEAD_IN, Phase.REST -> togglePause()
            Phase.EXERCISE -> if (phaseDurationMs == null) completeStep() else togglePause()
            Phase.WAITING -> enterLeadIn(stepIndex)
            Phase.FINISHED -> {}
        }
    }

    /** End the current exercise or rest now. */
    fun skip() {
        when (phase) {
            Phase.LEAD_IN -> enterExercise(stepIndex, announce = false, auto = false)
            Phase.EXERCISE -> completeStep()
            Phase.REST -> enterExercise(stepIndex, announce = true, auto = false)
            Phase.WAITING -> enterLeadIn(stepIndex)
            else -> {}
        }
    }

    /** Go to the previous step's lead-in (or restart step 0). */
    fun back() {
        if (!isActive) return
        val target = (stepIndex - 1).coerceAtLeast(0)
        enterLeadIn(target)
    }

    /** Jump to any step's lead-in, starting a session first if needed. */
    fun jumpTo(step: Int) {
        if (workout == null || rows.isEmpty()) return
        val target = step.coerceIn(0, totalSteps - 1)
        if (phase == Phase.FINISHED) return
        if (phase == Phase.IDLE) startSession(target) else enterLeadIn(target)
    }

    fun pause() {
        if (!isActive || paused || phaseDurationMs == null) return
        val now = clock.elapsedRealtime()
        if (holding) {
            // The clock never started; keep the pending GO chime for the resume.
            cancelHold()
        } else {
            phaseElapsedBeforeSegment += now - segmentStartedAt
        }
        paused = true
        pauseStartedAt = now
        cues.stopSpeaking()
        updateLog()
        writeSnapshot(force = true)
        publish()
    }

    fun resume() {
        if (!isActive || !paused) return
        val now = clock.elapsedRealtime()
        pausedMsAccum += now - (pauseStartedAt ?: now)
        pauseStartedAt = null
        paused = false
        segmentStartedAt = now
        // Do not replay the warning tick for the second already on screen; a fresh countdown
        // (setPhase) still starts from -1.
        lastTickSecond = phaseDurationMs?.let { ((it - phaseElapsedBeforeSegment + 999) / 1000).toInt() } ?: -1
        if (pendingGoChime) {
            pendingGoChime = false
            cues.chime(ChimeKind.GO)
        }
        ensureTicker()
        writeSnapshot(force = true)
        publish()
    }

    fun togglePause() { if (paused) resume() else pause() }

    /**
     * End the session early. With [keepLog] the log entry is closed as STOPPED and the engine
     * moves to FINISHED (the completion dialog collects notes); otherwise the entry is deleted
     * and the engine returns to IDLE.
     */
    fun endSession(keepLog: Boolean) {
        if (!isActive) return
        if (keepLog) {
            finish(SessionStatus.STOPPED)
        } else {
            cues.stopSpeaking()
            logEntry?.let { store.deleteLog(it.id) }
            stopTicker()
            store.saveSnapshot(null)
            resetInternals()
            publish()
        }
    }

    /** Dismiss the FINISHED state and return to IDLE with the same workout loaded. */
    fun acknowledgeFinished() {
        if (phase != Phase.FINISHED) return
        resetInternals()
        publish()
    }

    /** Restore an interrupted session, paused, from its snapshot. */
    fun restore(snapshot: SessionSnapshot, workout: Workout) {
        this.workout = workout
        if (workout.rows.isEmpty() || snapshot.phase == Phase.IDLE || snapshot.phase == Phase.FINISHED) {
            discardSnapshot(snapshot)
            return
        }
        resetInternals()
        val now = clock.elapsedRealtime()
        val wallNow = clock.wallTime()
        sessionId = snapshot.sessionId
        startedAtWall = snapshot.startedAtWall
        completed.addAll(snapshot.completedSteps.filter { it in 0 until totalSteps })
        stepIndex = snapshot.stepIndex.coerceIn(0, totalSteps - 1)
        phase = snapshot.phase
        phaseDurationMs = when (phase) {
            Phase.LEAD_IN -> workout.leadInSec.takeIf { it > 0 }?.let { it * 1000L }
            Phase.EXERCISE -> rowAt(stepIndex)?.durationSec?.takeIf { it > 0 }?.let { it * 1000L }
            Phase.REST -> rowAt(stepIndex - 1)?.triggerSec?.takeIf { it > 0 }?.let { it * 1000L }
            else -> null
        }
        if (phase == Phase.REST && phaseDurationMs == null) phase = Phase.WAITING
        if (phase == Phase.LEAD_IN && phaseDurationMs == null) { phase = Phase.EXERCISE; phaseDurationMs = rowAt(stepIndex)?.durationSec?.takeIf { it > 0 }?.let { it * 1000L } }
        phaseElapsedBeforeSegment = snapshot.phaseElapsedMs.coerceAtLeast(0)
        // time since the snapshot was written counts as paused time
        pausedMsAccum = snapshot.pausedMs + (wallNow - snapshot.savedAtWall).coerceAtLeast(0)
        logEntry = store.findLog(snapshot.sessionId) ?: LogEntry(
            id = snapshot.sessionId,
            workoutId = workout.id,
            workoutName = workout.name,
            startedAt = snapshot.startedAtWall,
            zoneId = zoneId(),
            totalSteps = totalSteps,
            rounds = workout.rounds,
            rowCount = workout.rows.size,
        )
        if (phaseDurationMs != null) {
            paused = true
            pauseStartedAt = now
            segmentStartedAt = now
        } else {
            paused = false
            segmentStartedAt = now
        }
        cues.prepare()
        updateLog()
        writeSnapshot(force = true)
        ensureTicker()
        publish()
    }

    /** The user chose not to resume: close the log entry as INTERRUPTED and clear the snapshot. */
    fun discardSnapshot(snapshot: SessionSnapshot) {
        val entry = store.findLog(snapshot.sessionId)
        if (entry != null && entry.status == SessionStatus.IN_PROGRESS) {
            store.upsertLog(
                entry.copy(
                    status = SessionStatus.INTERRUPTED,
                    finishedAt = snapshot.savedAtWall,
                    elapsedSec = ((snapshot.savedAtWall - entry.startedAt) / 1000).toInt().coerceAtLeast(0),
                    pausedSec = (snapshot.pausedMs / 1000).toInt(),
                    completedSteps = snapshot.completedSteps.size,
                )
            )
        }
        store.saveSnapshot(null)
        if (!isActive) { resetInternals(); publish() }
    }

    /**
     * Advance clocks and republish. Called by the internal ticker ten times a second whenever a
     * session is active (also while paused or counting up, so elapsed times stay live); public so
     * tests can drive it.
     */
    fun tick() {
        if (!isActive) return
        val now = clock.elapsedRealtime()
        if (holding && now - holdStartedAt >= ANNOUNCE_TIMEOUT_MS) {
            cues.stopSpeaking()
            releaseHold(holdToken)
            return
        }
        val duration = phaseDurationMs
        if (running && duration != null) {
            val remaining = duration - phaseElapsedMs(now)
            if (remaining <= 0) {
                onCountdownEnded()
                return
            }
            val sec = ((remaining + 999) / 1000).toInt()
            if (sec in 1..3 && sec != lastTickSecond && settings().warningTicks) {
                val elapsed = phaseElapsedMs(now)
                if (phase == Phase.LEAD_IN || elapsed >= 300) cues.chime(ChimeKind.TICK)
            }
            lastTickSecond = sec
        }
        if (running) writeSnapshot(force = false)
        publish()
    }

    // ---- transitions ----

    private fun startSession(step: Int) {
        val w = workout ?: return
        if (w.rows.isEmpty()) return
        resetInternals()
        val wallNow = clock.wallTime()
        sessionId = newId()
        startedAtWall = wallNow
        store.touchWorkoutUsed(w.id, wallNow)
        logEntry = LogEntry(
            id = sessionId!!,
            workoutId = w.id,
            workoutName = w.name,
            startedAt = wallNow,
            zoneId = zoneId(),
            totalSteps = totalSteps,
            rounds = w.rounds,
            rowCount = w.rows.size,
        ).also { store.upsertLog(it) }
        cues.prepare()
        enterLeadIn(step)
    }

    private fun enterLeadIn(step: Int) {
        val w = workout ?: return
        val leadMs = w.leadInSec.takeIf { it > 0 }?.let { it * 1000L }
        if (leadMs == null) { enterExercise(step, announce = true, auto = false); return }
        setPhase(Phase.LEAD_IN, step, leadMs)
        rowAt(step)?.let { announce("${it.name}, ${spokenDuration(it)}", goChimeOnRelease = false) }
        afterTransition()
    }

    private fun enterExercise(step: Int, announce: Boolean, auto: Boolean) {
        val row = rowAt(step) ?: return
        setPhase(Phase.EXERCISE, step, row.durationSec?.takeIf { it > 0 }?.let { it * 1000L })
        if (announce && settings().speechEnabled) {
            // Announce first; the GO chime and the clock follow once the announcement is done.
            announce("${row.name}, ${spokenDuration(row)}", goChimeOnRelease = true)
        } else {
            cues.chime(ChimeKind.GO)
        }
        if (auto) lastAutoTransitionAt = clock.elapsedRealtime()
        afterTransition()
    }

    private fun enterRest(step: Int, restSec: Int) {
        setPhase(Phase.REST, step, restSec * 1000L)
        rowAt(step)?.let { announce("Rest, ${spokenSeconds(restSec)}. Next: ${it.name}, ${spokenDuration(it)}.", goChimeOnRelease = false) }
        afterTransition()
    }

    private fun enterWaiting(step: Int) {
        setPhase(Phase.WAITING, step, null)
        rowAt(step)?.let { speakIfEnabled("Next: ${it.name}, ${spokenDuration(it)}. Tap when ready.", interrupt = true) }
        afterTransition()
    }

    private fun completeStep(auto: Boolean = false) {
        val row = rowAt(stepIndex) ?: return
        completed.add(stepIndex)
        cues.chime(ChimeKind.DONE)
        if (auto) lastAutoTransitionAt = clock.elapsedRealtime()
        updateLog()
        if (stepIndex >= totalSteps - 1) {
            finish(SessionStatus.COMPLETED)
            return
        }
        val next = stepIndex + 1
        val rest = row.triggerSec?.takeIf { it > 0 }
        if (rest != null) enterRest(next, rest) else enterWaiting(next)
        if (auto) lastAutoTransitionAt = clock.elapsedRealtime()
    }

    private fun onCountdownEnded() {
        when (phase) {
            Phase.LEAD_IN -> enterExercise(stepIndex, announce = false, auto = true)
            Phase.EXERCISE -> completeStep(auto = true)
            // A short rest's opening announcement is recent enough; after a long rest the exercise
            // is announced again (clock held) before the GO chime.
            Phase.REST -> {
                val longRest = (phaseDurationMs ?: 0L) > REANNOUNCE_AFTER_REST_SEC * 1000L
                enterExercise(stepIndex, announce = longRest, auto = true)
            }
            else -> {}
        }
    }

    private fun finish(status: SessionStatus) {
        val now = clock.elapsedRealtime()
        cancelHold()
        pendingGoChime = false
        if (paused) {
            pausedMsAccum += now - (pauseStartedAt ?: now)
            pauseStartedAt = null
            paused = false
        }
        stopTicker()
        phase = Phase.FINISHED
        phaseDurationMs = null
        val wallNow = clock.wallTime()
        val entry = (currentLogEntry() ?: return).copy(
            status = status,
            finishedAt = wallNow,
            elapsedSec = totalElapsedSec(wallNow),
            pausedSec = (pausedMsAccum / 1000).toInt(),
            completedSteps = completed.size,
            totalSteps = totalSteps,
        )
        store.upsertLog(entry)
        finishedEntry = entry
        logEntry = null
        store.saveSnapshot(null)
        if (status == SessionStatus.COMPLETED) {
            cues.chime(ChimeKind.COMPLETE)
            speakIfEnabled("Workout complete", interrupt = true)
        } else {
            cues.stopSpeaking()
        }
        publish()
    }

    private fun setPhase(newPhase: Phase, step: Int, durationMs: Long?) {
        val now = clock.elapsedRealtime()
        cancelHold()
        pendingGoChime = false
        if (paused) {
            pausedMsAccum += now - (pauseStartedAt ?: now)
            pauseStartedAt = null
            paused = false
        }
        phase = newPhase
        stepIndex = step
        phaseDurationMs = durationMs
        phaseElapsedBeforeSegment = 0L
        segmentStartedAt = now
        lastTickSecond = -1
    }

    private fun afterTransition() {
        updateLog()
        writeSnapshot(force = true)
        ensureTicker()
        publish()
    }

    private fun resetInternals() {
        stopTicker()
        sessionId = null
        phase = Phase.IDLE
        stepIndex = 0
        paused = false
        phaseDurationMs = null
        phaseElapsedBeforeSegment = 0L
        segmentStartedAt = 0L
        pauseStartedAt = null
        pausedMsAccum = 0L
        startedAtWall = null
        completed.clear()
        lastTickSecond = -1
        lastSnapshotAt = 0L
        logEntry = null
        finishedEntry = null
        holding = false
        holdToken++
        pendingGoChime = false
    }

    // ---- announcement hold ----

    /**
     * Speak [text] and hold the current phase's clock until the utterance is done (or the
     * timeout in [tick] fires). With speech disabled nothing is held: the GO chime, if requested,
     * plays immediately.
     */
    private fun announce(text: String, goChimeOnRelease: Boolean) {
        if (!settings().speechEnabled) {
            if (goChimeOnRelease) cues.chime(ChimeKind.GO)
            return
        }
        holding = true
        holdStartedAt = clock.elapsedRealtime()
        pendingGoChime = goChimeOnRelease
        val token = ++holdToken
        cues.speak(text, interrupt = true) { releaseHold(token) }
    }

    /** Start the held clock. Ignored for a hold that was already released or superseded. */
    private fun releaseHold(token: Int) {
        if (!holding || token != holdToken) return
        holding = false
        segmentStartedAt = clock.elapsedRealtime()
        lastTickSecond = -1
        if (pendingGoChime) {
            pendingGoChime = false
            cues.chime(ChimeKind.GO)
        }
        ensureTicker()
        publish()
    }

    /** Drop the current hold without starting the clock (a new phase, a pause, or the end). */
    private fun cancelHold() {
        if (!holding) return
        holding = false
        holdToken++
        cues.stopSpeaking()
    }

    // ---- helpers ----

    private fun phaseElapsedMs(now: Long): Long =
        phaseElapsedBeforeSegment + if (running) (now - segmentStartedAt) else 0L

    private fun totalElapsedSec(wallNow: Long): Int =
        startedAtWall?.let { ((wallNow - it) / 1000).toInt().coerceAtLeast(0) } ?: 0

    private fun currentPausedMs(now: Long): Long =
        pausedMsAccum + (pauseStartedAt?.let { now - it } ?: 0L)

    /** The session's log entry as the store currently has it (the user may have edited its notes on the Log screen). */
    private fun currentLogEntry(): LogEntry? = logEntry?.let { store.findLog(it.id) ?: it }

    private fun updateLog() {
        val entry = currentLogEntry() ?: return
        val wallNow = clock.wallTime()
        val now = clock.elapsedRealtime()
        logEntry = entry.copy(
            elapsedSec = totalElapsedSec(wallNow),
            pausedSec = (currentPausedMs(now) / 1000).toInt(),
            completedSteps = completed.size,
            totalSteps = totalSteps,
        ).also { store.upsertLog(it) }
    }

    private fun writeSnapshot(force: Boolean) {
        val now = clock.elapsedRealtime()
        if (!force && now - lastSnapshotAt < SNAPSHOT_EVERY_MS) return
        lastSnapshotAt = now
        val w = workout
        val id = sessionId
        if (!isActive || w == null || id == null) return
        val duration = phaseDurationMs
        val elapsed = phaseElapsedMs(now)
        store.saveSnapshot(
            SessionSnapshot(
                sessionId = id,
                workoutId = w.id,
                phase = phase,
                stepIndex = stepIndex,
                remainingMs = duration?.let { (it - elapsed).coerceAtLeast(0) },
                phaseElapsedMs = elapsed,
                completedSteps = completed.toList(),
                startedAtWall = startedAtWall ?: clock.wallTime(),
                pausedMs = currentPausedMs(now),
                savedAtWall = clock.wallTime(),
            )
        )
    }

    private fun ensureTicker() {
        if (!isActive) { stopTicker(); return }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun speakIfEnabled(text: String, interrupt: Boolean) {
        if (settings().speechEnabled) cues.speak(text, interrupt)
    }

    private fun publish() {
        val now = clock.elapsedRealtime()
        val wallNow = clock.wallTime()
        val duration = phaseDurationMs
        val elapsed = if (isActive) phaseElapsedMs(now) else 0L
        _state.value = SessionState(
            workout = workout,
            sessionId = sessionId,
            phase = phase,
            paused = paused,
            stepIndex = stepIndex,
            remainingMs = if (isActive) duration?.let { (it - elapsed).coerceAtLeast(0) } else null,
            phaseElapsedMs = elapsed,
            completedSteps = completed.toSet(),
            startedAtWall = startedAtWall,
            totalElapsedMs = startedAtWall?.let { (wallNow - it).coerceAtLeast(0) } ?: finishedEntry?.let { it.elapsedSec * 1000L } ?: 0L,
            pausedMs = currentPausedMs(now),
            finishedEntry = finishedEntry,
            announcing = holding,
        )
    }

    private fun spokenDuration(row: ExerciseRow): String {
        val sec = row.durationSec?.takeIf { it > 0 } ?: return "until done"
        return spokenSeconds(sec)
    }

    private fun spokenSeconds(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        val minutes = when (m) { 0 -> ""; 1 -> "1 minute"; else -> "$m minutes" }
        val seconds = when (s) { 0 -> ""; 1 -> "1 second"; else -> "$s seconds" }
        return listOf(minutes, seconds).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
