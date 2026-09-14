package com.exertrack.app.engine

import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.Workout

enum class Phase { IDLE, LEAD_IN, EXERCISE, REST, WAITING, FINISHED }

/** Time source, abstracted so the engine can be unit-tested. */
interface Clock {
    /** Monotonic milliseconds (SystemClock.elapsedRealtime in production). */
    fun elapsedRealtime(): Long
    /** Epoch milliseconds (System.currentTimeMillis in production). */
    fun wallTime(): Long
}

/**
 * Everything the UI and the service need to render the current session. Immutable; the engine
 * publishes a new copy on every change and on every ticker tick while a clock runs.
 */
data class SessionState(
    val workout: Workout? = null,
    val sessionId: String? = null,
    val phase: Phase = Phase.IDLE,
    val paused: Boolean = false,
    /** True while a spoken announcement plays and the phase's clock is held at its full value. */
    val announcing: Boolean = false,
    /** Step that is running (EXERCISE) or about to run (LEAD_IN / REST / WAITING). */
    val stepIndex: Int = 0,
    /** Milliseconds left in the current countdown (LEAD_IN, timed EXERCISE, REST); null otherwise. Frozen while paused. */
    val remainingMs: Long? = null,
    /** Milliseconds spent in the current phase, excluding paused time. */
    val phaseElapsedMs: Long = 0,
    /** Steps whose exercise has been completed (clock reached zero, Done, or Skip). */
    val completedSteps: Set<Int> = emptySet(),
    /** Epoch ms when the session started; null when IDLE. */
    val startedAtWall: Long? = null,
    /** Wall-clock milliseconds since the session started, pauses included. */
    val totalElapsedMs: Long = 0,
    /** Milliseconds spent paused during this session. */
    val pausedMs: Long = 0,
    /** The log entry for the session that just ended (FINISHED only); the completion dialog edits its notes. */
    val finishedEntry: LogEntry? = null,
) {
    val rows: List<ExerciseRow> get() = workout?.rows ?: emptyList()
    val rounds: Int get() = workout?.rounds ?: 1
    val totalSteps: Int get() = rows.size * rounds
    val rowIndex: Int get() = if (rows.isEmpty()) 0 else stepIndex % rows.size
    /** 0-based round of [stepIndex]. */
    val round: Int get() = if (rows.isEmpty()) 0 else stepIndex / rows.size
    val currentRow: ExerciseRow? get() = rows.getOrNull(rowIndex)
    /** The row whose trigger produced the current REST (the step just completed). */
    val previousRow: ExerciseRow? get() = if (stepIndex <= 0 || rows.isEmpty()) null else rows[(stepIndex - 1) % rows.size]
    val isActive: Boolean get() = phase != Phase.IDLE && phase != Phase.FINISHED
    val hasCountdown: Boolean get() = remainingMs != null
    /** True when a clock is counting down right now (drives the wake lock and the ticker). */
    val isCountingDown: Boolean get() = isActive && hasCountdown && !paused
    val isUntimedExercise: Boolean get() = phase == Phase.EXERCISE && (currentRow?.isUntimed ?: false)
    /** Seconds to show on the big clock for a countdown: ceil(remaining / 1000). */
    val remainingSec: Int get() = remainingMs?.let { ((it + 999) / 1000).toInt() } ?: 0
    val completedCount: Int get() = completedSteps.size

    /** Sum of durations and automatic rests still ahead, using the current clock for the current phase. */
    fun estimatedRemainingSec(): Int {
        val w = workout ?: return 0
        if (rows.isEmpty()) return 0
        var total = 0L
        val startStep = when (phase) {
            Phase.IDLE -> 0
            Phase.FINISHED -> return 0
            else -> stepIndex
        }
        for (step in startStep until totalSteps) {
            val row = rows[step % rows.size]
            val isCurrent = step == startStep
            if (isCurrent && phase == Phase.EXERCISE) {
                total += remainingMs?.let { (it + 999) / 1000 } ?: 0L
            } else {
                total += (row.durationSec ?: 0)
            }
            if (step < totalSteps - 1) total += (row.triggerSec ?: 0)
        }
        if (phase == Phase.REST) total += remainingMs?.let { (it + 999) / 1000 } ?: 0L
        if (phase == Phase.LEAD_IN) total += remainingMs?.let { (it + 999) / 1000 } ?: 0L
        return total.toInt()
    }
}

/** What the engine persists so an interrupted session can be resumed (paused) after process death. */
data class SessionSnapshot(
    val sessionId: String,
    val workoutId: String,
    val phase: Phase,
    val stepIndex: Int,
    val remainingMs: Long?,
    val phaseElapsedMs: Long,
    val completedSteps: List<Int>,
    val startedAtWall: Long,
    val pausedMs: Long,
    /** Epoch ms when this snapshot was written. */
    val savedAtWall: Long,
)
