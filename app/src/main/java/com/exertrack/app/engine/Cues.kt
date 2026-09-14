package com.exertrack.app.engine

import com.exertrack.app.data.LogEntry

enum class ChimeKind { DONE, GO, TICK, COMPLETE }

/** Sounds, vibration and speech. Implemented by audio/AudioCues.kt; faked in engine tests. */
interface Cues {
    fun chime(kind: ChimeKind)
    /**
     * Speak [text]; when [interrupt] is true anything queued or in progress is dropped first.
     * [onDone] must be invoked exactly once, on the calling thread, when the utterance has
     * finished, failed, been interrupted, or could not be spoken at all (speech muted, engine
     * unavailable, blank text). The engine uses it to hold a countdown until its announcement ends.
     */
    fun speak(text: String, interrupt: Boolean = false, onDone: (() -> Unit)? = null)
    fun stopSpeaking()
    /** Called when a session starts so the speech engine can be warmed up. */
    fun prepare() {}
}

/** Persistence hooks the engine needs. Implemented by data/Repository.kt; faked in tests. */
interface SessionStore {
    /** Write (or clear with null) the resumable snapshot. */
    fun saveSnapshot(snapshot: SessionSnapshot?)
    /** Insert or update the log entry with the same id. */
    fun upsertLog(entry: LogEntry)
    fun deleteLog(entryId: String)
    fun findLog(entryId: String): LogEntry?
    /** Record that a workout was started. */
    fun touchWorkoutUsed(workoutId: String, at: Long)
}
