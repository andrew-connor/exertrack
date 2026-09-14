package com.exertrack.app.engine

import com.exertrack.app.data.LogEntry

enum class ChimeKind { DONE, GO, TICK, COMPLETE }

/** Sounds, vibration and speech. Implemented by audio/AudioCues.kt; faked in engine tests. */
interface Cues {
    fun chime(kind: ChimeKind)
    /** Speak [text]; when [interrupt] is true anything queued or in progress is dropped first. */
    fun speak(text: String, interrupt: Boolean = false)
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
