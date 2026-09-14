package com.exertrack.app.engine

import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.SessionStatus
import com.exertrack.app.data.Settings
import com.exertrack.app.data.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEngineTest {

    private class FakeClock(private val scheduler: TestCoroutineScheduler) : Clock {
        val wallBase = 1_800_000_000_000L
        override fun elapsedRealtime(): Long = scheduler.currentTime
        override fun wallTime(): Long = wallBase + scheduler.currentTime
    }

    private class FakeCues : Cues {
        val chimes = mutableListOf<ChimeKind>()
        val spoken = mutableListOf<String>()
        var prepared = 0
        var stops = 0
        /** When false, an utterance only completes when [finishSpeech] is called (or speech is stopped). */
        var autoComplete = true
        private val pendingDone = ArrayDeque<() -> Unit>()
        override fun chime(kind: ChimeKind) { chimes += kind }
        override fun speak(text: String, interrupt: Boolean, onDone: (() -> Unit)?) {
            spoken += text
            if (onDone == null) return
            if (autoComplete) onDone() else pendingDone.addLast(onDone)
        }
        /** Complete every utterance still "playing", like the real speaker does at the end of speech or on stop. */
        fun finishSpeech() { while (pendingDone.isNotEmpty()) pendingDone.removeFirst().invoke() }
        override fun stopSpeaking() { stops++; finishSpeech() }
        override fun prepare() { prepared++ }
    }

    private class FakeStore : SessionStore {
        var snapshot: SessionSnapshot? = null
        var snapshotWrites = 0
        val log = LinkedHashMap<String, LogEntry>()
        val touched = mutableListOf<String>()
        override fun saveSnapshot(snapshot: SessionSnapshot?) { this.snapshot = snapshot; snapshotWrites++ }
        override fun upsertLog(entry: LogEntry) { log[entry.id] = entry }
        override fun deleteLog(entryId: String) { log.remove(entryId) }
        override fun findLog(entryId: String): LogEntry? = log[entryId]
        override fun touchWorkoutUsed(workoutId: String, at: Long) { touched += workoutId }
    }

    private class Harness(scope: TestScope, settings: Settings = Settings()) {
        val clock = FakeClock(scope.testScheduler)
        val cues = FakeCues()
        val store = FakeStore()
        var settings = settings
        val engine = WorkoutEngine(clock, cues, store, { this.settings }, scope, { "UTC" })
        val state get() = engine.state.value
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun workout(rounds: Int = 1, leadIn: Int = 3, rows: List<ExerciseRow> = defaultRows) = Workout(
        id = "w1", name = "Test", rows = rows, rounds = rounds, leadInSec = leadIn, createdAt = 0, updatedAt = 0,
    )

    private val defaultRows = listOf(
        ExerciseRow(id = "a", name = "Push-ups", durationSec = 40, triggerSec = 15),
        ExerciseRow(id = "b", name = "Plank", durationSec = null, triggerSec = null, notes = "10 reps"),
        ExerciseRow(id = "c", name = "Squats", durationSec = 10, triggerSec = null),
    )

    @Test
    fun `idle shows the workout and start enters the lead-in`() = runTest {
        val h = Harness(this)
        h.engine.load(workout())
        assertEquals(Phase.IDLE, h.state.phase)
        assertEquals("Test", h.state.workout?.name)

        h.engine.primaryTap()
        assertEquals(Phase.LEAD_IN, h.state.phase)
        assertEquals(0, h.state.stepIndex)
        assertEquals(3000L, h.state.remainingMs)
        assertEquals(listOf("Push-ups, 40 seconds"), h.cues.spoken)
        assertEquals(1, h.cues.prepared)
        assertEquals(listOf("w1"), h.store.touched)
        assertEquals(1, h.store.log.size)
        assertEquals(SessionStatus.IN_PROGRESS, h.store.log.values.first().status)
        assertNotNull(h.store.snapshot)
        h.engine.stopForTest()
    }

    @Test
    fun `lead-in ticks 3-2-1 then go chime starts the exercise`() = runTest {
        val h = Harness(this)
        h.engine.load(workout())
        h.engine.primaryTap()
        advance(3000)
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertEquals(40_000L, h.state.remainingMs)
        assertEquals(listOf(ChimeKind.TICK, ChimeKind.TICK, ChimeKind.TICK, ChimeKind.GO), h.cues.chimes)
        // no extra speech at go after a lead-in that already announced the exercise
        assertEquals(listOf("Push-ups, 40 seconds"), h.cues.spoken)
        h.engine.stopForTest()
    }

    @Test
    fun `timed exercise completes into an automatic rest then the next exercise`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        assertEquals(Phase.EXERCISE, h.state.phase)
        h.cues.chimes.clear(); h.cues.spoken.clear()

        advance(40_000)
        assertEquals(Phase.REST, h.state.phase)
        assertEquals(1, h.state.stepIndex)
        assertEquals(15_000L, h.state.remainingMs)
        assertEquals(setOf(0), h.state.completedSteps)
        assertTrue(h.cues.chimes.contains(ChimeKind.DONE))
        assertEquals("Rest, 15 seconds. Next: Plank, until done.", h.cues.spoken.last())
        assertEquals(1, h.store.log.values.first().completedSteps)

        h.cues.chimes.clear()
        val spokenBefore = h.cues.spoken.size
        advance(15_000)
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertEquals(1, h.state.stepIndex)
        assertNull(h.state.remainingMs) // untimed
        assertEquals(ChimeKind.GO, h.cues.chimes.last())
        // the rest announcement already named the exercise, so nothing more is said at go
        assertEquals(spokenBefore, h.cues.spoken.size)
        h.engine.stopForTest()
    }

    @Test
    fun `a countdown waits for its announcement before it starts`() = runTest {
        val h = Harness(this)
        h.cues.autoComplete = false
        h.engine.load(workout())
        h.engine.primaryTap()
        assertEquals(Phase.LEAD_IN, h.state.phase)
        assertTrue(h.state.announcing)
        assertEquals(listOf("Push-ups, 40 seconds"), h.cues.spoken)

        advance(2_000)
        assertEquals(3000L, h.state.remainingMs) // held at its full value while speaking
        assertTrue(h.cues.chimes.isEmpty())

        h.cues.finishSpeech()
        assertFalse(h.state.announcing)
        advance(3_000)
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertEquals(listOf(ChimeKind.TICK, ChimeKind.TICK, ChimeKind.TICK, ChimeKind.GO), h.cues.chimes)
        h.engine.stopForTest()
    }

    @Test
    fun `an announced exercise plays its go chime only after the announcement`() = runTest {
        val h = Harness(this)
        h.cues.autoComplete = false
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap() // no lead-in: the exercise itself is announced first
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertTrue(h.state.announcing)
        assertTrue(h.cues.chimes.isEmpty())
        advance(1_500)
        assertEquals(40_000L, h.state.remainingMs)

        h.cues.finishSpeech()
        assertEquals(listOf(ChimeKind.GO), h.cues.chimes)
        assertFalse(h.state.announcing)
        advance(40_000)
        assertEquals(Phase.REST, h.state.phase)
        assertTrue(h.state.announcing) // the rest waits for its own announcement
        assertEquals(15_000L, h.state.remainingMs)
        advance(4_000)
        assertEquals(15_000L, h.state.remainingMs)
        h.cues.finishSpeech()
        advance(15_000)
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertEquals(1, h.state.stepIndex)
        h.engine.stopForTest()
    }

    @Test
    fun `a rest longer than 15 seconds announces the exercise again when it ends`() = runTest {
        val rows = listOf(
            ExerciseRow(id = "a", name = "Row", durationSec = 10, triggerSec = 20),
            ExerciseRow(id = "b", name = "Squats", durationSec = 30, triggerSec = null),
        )
        val h = Harness(this)
        h.cues.autoComplete = false
        h.engine.load(workout(leadIn = 0, rows = rows))
        h.engine.primaryTap()
        h.cues.finishSpeech() // "Row, 10 seconds"
        advance(10_000) // -> REST 20 s, announced
        assertEquals(Phase.REST, h.state.phase)
        assertEquals("Rest, 20 seconds. Next: Squats, 30 seconds.", h.cues.spoken.last())
        h.cues.finishSpeech()
        h.cues.chimes.clear()
        advance(20_000) // rest over: the exercise is announced again and its clock waits
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertTrue(h.state.announcing)
        assertEquals("Squats, 30 seconds", h.cues.spoken.last())
        // only the rest's 3-2-1 warning ticks so far; the GO chime waits for the announcement
        assertFalse(h.cues.chimes.contains(ChimeKind.GO))
        advance(1_000)
        assertEquals(30_000L, h.state.remainingMs)
        h.cues.finishSpeech()
        assertEquals(ChimeKind.GO, h.cues.chimes.last())
        advance(1_000)
        assertEquals(29_000L, h.state.remainingMs)
        h.engine.stopForTest()
    }

    @Test
    fun `a tap during the announcement starts the clock right away`() = runTest {
        val h = Harness(this)
        h.cues.autoComplete = false
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        assertTrue(h.state.announcing)
        advance(1_000) // past the post-transition debounce
        h.engine.primaryTap()
        assertFalse(h.state.announcing)
        assertEquals(listOf(ChimeKind.GO), h.cues.chimes)
        assertEquals(1, h.cues.stops)
        advance(1_000)
        assertEquals(39_000L, h.state.remainingMs)
        h.engine.stopForTest()
    }

    @Test
    fun `a hold releases on its own if the speech engine never reports back`() = runTest {
        val h = Harness(this)
        h.cues.autoComplete = false
        h.engine.load(workout())
        h.engine.primaryTap()
        advance(WorkoutEngine.ANNOUNCE_TIMEOUT_MS + 100)
        assertFalse(h.state.announcing)
        advance(2_900)
        assertEquals(Phase.EXERCISE, h.state.phase)
        h.engine.stopForTest()
    }

    @Test
    fun `pausing during the announcement keeps the go chime for the resume`() = runTest {
        val h = Harness(this)
        h.cues.autoComplete = false
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        h.engine.pause()
        assertTrue(h.state.paused)
        assertFalse(h.state.announcing)
        assertTrue(h.cues.chimes.isEmpty())
        advance(3_000)
        assertEquals(40_000L, h.state.remainingMs)
        h.engine.resume()
        assertEquals(listOf(ChimeKind.GO), h.cues.chimes)
        advance(1_000)
        assertEquals(39_000L, h.state.remainingMs)
        h.engine.stopForTest()
    }

    @Test
    fun `untimed exercise counts up and Done advances to a manual wait`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.jumpTo(1)
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertTrue(h.state.isUntimedExercise)
        advance(5_000)
        assertEquals(5_000L, h.state.phaseElapsedMs)

        h.engine.primaryTap() // Done
        assertEquals(Phase.WAITING, h.state.phase)
        assertEquals(2, h.state.stepIndex)
        assertEquals(setOf(1), h.state.completedSteps)
        assertEquals("Next: Squats, 10 seconds. Tap when ready.", h.cues.spoken.last())

        advance(7_000)
        assertEquals(7_000L, h.state.phaseElapsedMs)
        h.engine.primaryTap() // start next (no lead-in configured, so straight into the exercise)
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertEquals(2, h.state.stepIndex)
        assertEquals(10_000L, h.state.remainingMs)
        h.engine.stopForTest()
    }

    @Test
    fun `pause freezes the countdown and resume continues it`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        advance(10_000)
        assertEquals(30_000L, h.state.remainingMs)

        h.engine.primaryTap() // pause
        assertTrue(h.state.paused)
        advance(20_000)
        assertEquals(30_000L, h.state.remainingMs)
        assertEquals(20_000L, h.state.pausedMs)
        assertEquals(Phase.EXERCISE, h.state.phase)

        h.engine.primaryTap() // resume
        assertFalse(h.state.paused)
        advance(30_000)
        assertEquals(Phase.REST, h.state.phase)
        h.engine.stopForTest()
    }

    @Test
    fun `skip ends an exercise early and counts it as completed`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        advance(5_000)
        h.engine.skip()
        assertEquals(Phase.REST, h.state.phase)
        assertEquals(setOf(0), h.state.completedSteps)

        h.cues.chimes.clear()
        h.engine.skip() // cut the rest short
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertEquals(1, h.state.stepIndex)
        assertEquals(listOf(ChimeKind.GO), h.cues.chimes)
        h.engine.stopForTest()
    }

    @Test
    fun `back returns to the previous step's lead-in`() = runTest {
        val h = Harness(this)
        h.engine.load(workout())
        h.engine.primaryTap()
        advance(3000)
        advance(40_000) // -> REST for step 1
        assertEquals(Phase.REST, h.state.phase)
        h.engine.back()
        assertEquals(Phase.LEAD_IN, h.state.phase)
        assertEquals(0, h.state.stepIndex)
        assertEquals(3000L, h.state.remainingMs)
        // step 0 stays counted as completed once
        assertEquals(setOf(0), h.state.completedSteps)

        h.engine.back() // from step 0 restarts step 0
        assertEquals(0, h.state.stepIndex)
        assertEquals(Phase.LEAD_IN, h.state.phase)
        h.engine.stopForTest()
    }

    @Test
    fun `finishing the last step completes the session and closes the log`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.jumpTo(2)
        assertEquals(Phase.EXERCISE, h.state.phase)
        advance(10_000)
        assertEquals(Phase.FINISHED, h.state.phase)
        assertEquals(ChimeKind.COMPLETE, h.cues.chimes.last())
        assertEquals("Workout complete", h.cues.spoken.last())
        val entry = h.state.finishedEntry
        assertNotNull(entry)
        assertEquals(SessionStatus.COMPLETED, entry!!.status)
        assertEquals(1, entry.completedSteps)
        assertEquals(3, entry.totalSteps)
        assertEquals(10, entry.elapsedSec)
        assertNotNull(entry.finishedAt)
        assertNull(h.store.snapshot)
        assertEquals(entry, h.store.log[entry.id])

        h.engine.acknowledgeFinished()
        assertEquals(Phase.IDLE, h.state.phase)
        assertEquals("Test", h.state.workout?.name)
    }

    @Test
    fun `rounds repeat the row list and use the last row's trigger between rounds`() = runTest {
        val rows = listOf(
            ExerciseRow(id = "a", name = "A", durationSec = 5, triggerSec = null),
            ExerciseRow(id = "b", name = "B", durationSec = 5, triggerSec = 4),
        )
        val h = Harness(this)
        h.engine.load(workout(rounds = 2, leadIn = 0, rows = rows))
        assertEquals(4, h.state.totalSteps)
        h.engine.primaryTap()
        advance(5_000) // A done -> WAITING for B
        assertEquals(Phase.WAITING, h.state.phase)
        advance(1_000) // clear the post-transition debounce window
        h.engine.primaryTap()
        assertEquals(Phase.EXERCISE, h.state.phase)
        advance(5_000) // B done -> REST 4 s -> A of round 2
        assertEquals(Phase.REST, h.state.phase)
        assertEquals(2, h.state.stepIndex)
        assertEquals(0, h.state.rowIndex)
        assertEquals(1, h.state.round)
        advance(4_000)
        assertEquals(Phase.EXERCISE, h.state.phase)
        assertEquals("A", h.state.currentRow?.name)
        advance(5_000)
        assertEquals(Phase.WAITING, h.state.phase)
        advance(1_000)
        h.engine.primaryTap()
        advance(5_000) // last step: B of round 2 -> FINISHED, its trigger ignored
        assertEquals(Phase.FINISHED, h.state.phase)
        assertEquals(4, h.state.finishedEntry?.completedSteps)
    }

    @Test
    fun `a tap right after an automatic transition is ignored`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        advance(40_000) // auto -> REST
        assertEquals(Phase.REST, h.state.phase)
        h.engine.primaryTap() // within 600 ms: ignored
        assertFalse(h.state.paused)
        advance(700)
        h.engine.primaryTap()
        assertTrue(h.state.paused)
        h.engine.stopForTest()
    }

    @Test
    fun `editing the running exercise's duration adjusts the clock`() = runTest {
        val h = Harness(this)
        val w = workout(leadIn = 0)
        h.engine.load(w)
        h.engine.primaryTap()
        advance(10_000)
        assertEquals(30_000L, h.state.remainingMs)

        val longer = w.copy(rows = w.rows.map { if (it.id == "a") it.copy(durationSec = 60) else it })
        h.engine.updateWorkout(longer)
        assertEquals(50_000L, h.state.remainingMs)
        assertEquals(Phase.EXERCISE, h.state.phase)

        val shorter = w.copy(rows = w.rows.map { if (it.id == "a") it.copy(durationSec = 5) else it })
        h.engine.updateWorkout(shorter)
        assertEquals(Phase.REST, h.state.phase) // completed immediately
        assertEquals(setOf(0), h.state.completedSteps)

        // editing the trigger that produced the current rest to manual switches to waiting
        val manual = shorter.copy(rows = shorter.rows.map { if (it.id == "a") it.copy(triggerSec = null) else it })
        h.engine.updateWorkout(manual)
        assertEquals(Phase.WAITING, h.state.phase)
        h.engine.stopForTest()
    }

    @Test
    fun `end and log stops the session and keeps the entry, discard deletes it`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        advance(3_000)
        val id = h.state.sessionId!!
        h.engine.endSession(keepLog = true)
        assertEquals(Phase.FINISHED, h.state.phase)
        assertEquals(SessionStatus.STOPPED, h.state.finishedEntry?.status)
        assertEquals(SessionStatus.STOPPED, h.store.log[id]?.status)
        assertNull(h.store.snapshot)
        h.engine.acknowledgeFinished()

        h.engine.primaryTap()
        val id2 = h.state.sessionId!!
        val stopsBefore = h.cues.stops
        h.engine.endSession(keepLog = false)
        assertEquals(Phase.IDLE, h.state.phase)
        assertNull(h.store.log[id2])
        assertNull(h.store.snapshot)
        assertEquals(stopsBefore + 1, h.cues.stops) // discarding also silences speech in progress
    }

    @Test
    fun `loading a different workout after a finish returns to IDLE with that workout`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.jumpTo(2)
        advance(10_000)
        assertEquals(Phase.FINISHED, h.state.phase)
        // re-opening the same workout keeps the completion dialog
        h.engine.load(workout(leadIn = 0))
        assertEquals(Phase.FINISHED, h.state.phase)
        assertNotNull(h.state.finishedEntry)

        val other = workout(leadIn = 0).copy(id = "other", name = "Other")
        h.engine.load(other)
        assertEquals(Phase.IDLE, h.state.phase)
        assertEquals("other", h.state.workout?.id)
        assertNull(h.state.finishedEntry)
        assertTrue(h.state.completedSteps.isEmpty())
    }

    @Test
    fun `notes edited on the log entry mid-session survive the engine's updates`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        advance(5_000)
        val id = h.state.sessionId!!
        h.store.log[id] = h.store.log[id]!!.copy(notes = "felt strong")
        advance(35_000) // step completes -> updateLog
        assertEquals("felt strong", h.store.log[id]?.notes)
        h.engine.endSession(keepLog = true)
        assertEquals("felt strong", h.store.log[id]?.notes)
        assertEquals("felt strong", h.state.finishedEntry?.notes)
    }

    @Test
    fun `resuming does not replay the warning tick for the second on screen`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        advance(37_600) // 2.4 s left: the "3" tick has played
        val ticksBefore = h.cues.chimes.count { it == ChimeKind.TICK }
        h.engine.pause()
        advance(5_000)
        h.engine.resume()
        advance(300)
        assertEquals(ticksBefore, h.cues.chimes.count { it == ChimeKind.TICK })
        advance(200) // crosses into the "2" second
        assertEquals(ticksBefore + 1, h.cues.chimes.count { it == ChimeKind.TICK })
        h.engine.stopForTest()
    }

    @Test
    fun `snapshot restores paused with the saved remaining time`() = runTest {
        val h = Harness(this)
        val w = workout(leadIn = 0)
        h.engine.load(w)
        h.engine.primaryTap()
        advance(12_000)
        val snap = h.store.snapshot!!
        assertEquals(Phase.EXERCISE, snap.phase)
        assertTrue(snap.remainingMs!! <= 30_000L && snap.remainingMs!! >= 25_000L)

        // simulate process death: a fresh engine
        val h2 = Harness(this)
        h2.store.log.putAll(h.store.log)
        h2.engine.restore(snap, w)
        assertEquals(Phase.EXERCISE, h2.state.phase)
        assertTrue(h2.state.paused)
        assertEquals(snap.remainingMs, h2.state.remainingMs)
        assertEquals(snap.sessionId, h2.state.sessionId)
        h2.engine.resume()
        advance(snap.remainingMs!!)
        assertEquals(Phase.REST, h2.state.phase)
        h.engine.stopForTest()
        h2.engine.stopForTest()
    }

    @Test
    fun `discarding a snapshot marks the log entry interrupted`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        h.engine.primaryTap()
        advance(6_000)
        val snap = h.store.snapshot!!
        val logAtDeath = h.store.log.toMap()
        h.engine.stopForTest()

        val h2 = Harness(this)
        h2.store.log.putAll(logAtDeath)
        h2.engine.discardSnapshot(snap)
        val entry = h2.store.log[snap.sessionId]!!
        assertEquals(SessionStatus.INTERRUPTED, entry.status)
        assertEquals(snap.savedAtWall, entry.finishedAt)
        assertNull(h2.store.snapshot)
        assertEquals(Phase.IDLE, h2.state.phase)
    }

    @Test
    fun `speech is silent when disabled and ticks are silent when disabled`() = runTest {
        val h = Harness(this, Settings(speechEnabled = false, warningTicks = false))
        h.engine.load(workout())
        h.engine.primaryTap()
        advance(3000)
        assertTrue(h.cues.spoken.isEmpty())
        assertEquals(listOf(ChimeKind.GO), h.cues.chimes)
        h.engine.stopForTest()
    }

    @Test
    fun `estimated remaining counts durations and automatic rests ahead`() = runTest {
        val h = Harness(this)
        h.engine.load(workout(leadIn = 0))
        // 40 + 15 rest + 0 (untimed) + 10 = 65
        assertEquals(65, h.state.estimatedRemainingSec())
        h.engine.primaryTap()
        advance(10_000)
        assertEquals(55, h.state.estimatedRemainingSec())
        h.engine.stopForTest()
    }

    /** Cancel the ticker so runTest does not wait on an active coroutine. */
    private fun WorkoutEngine.stopForTest() {
        if (state.value.isActive) endSession(keepLog = false)
    }
}
