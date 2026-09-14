package com.exertrack.app.data

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** One exercise in a workout table. */
data class ExerciseRow(
    val id: String = newId(),
    val name: String,
    /** Seconds; null = untimed (the exercise ends when the user taps Done). */
    val durationSec: Int? = null,
    /** Seconds of automatic rest before the next exercise; null = manual trigger (wait for a tap). Never 0. */
    val triggerSec: Int? = null,
    val notes: String = "",
) {
    val isUntimed: Boolean get() = durationSec == null || durationSec <= 0
    val isManual: Boolean get() = triggerSec == null || triggerSec <= 0

    /** Normalises 0 / negative values to null so the rest of the app can rely on null == untimed/manual. */
    fun normalized(): ExerciseRow = copy(
        name = name.trim(),
        durationSec = durationSec?.takeIf { it > 0 },
        triggerSec = triggerSec?.takeIf { it > 0 },
    )
}

data class Workout(
    val id: String = newId(),
    val name: String,
    val rows: List<ExerciseRow>,
    /** How many times the whole row list is performed. Always >= 1. */
    val rounds: Int = 1,
    /** "Get ready" countdown after Start and after each manual advance. 0 disables. */
    val leadInSec: Int = 3,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long? = null,
    /** content:// URI of the spreadsheet this workout was imported from, if any. */
    val sourceUri: String? = null,
    /** Sheet name inside that spreadsheet (null for CSV). */
    val sourceSheet: String? = null,
) {
    val totalSteps: Int get() = rows.size * rounds

    /**
     * Sum of timed durations and automatic rests over all rounds. Manual waits and untimed
     * rows contribute nothing. Used for the "~24 min" estimate on the home screen.
     */
    fun estimatedSeconds(): Int {
        if (rows.isEmpty()) return 0
        var perRound = 0
        rows.forEachIndexed { i, row ->
            perRound += row.durationSec ?: 0
            // the last row's trigger only applies between rounds, never after the final step
            perRound += row.triggerSec ?: 0
        }
        val total = perRound * rounds - (rows.last().triggerSec ?: 0)
        return maxOf(0, total)
    }
}

enum class SessionStatus { IN_PROGRESS, COMPLETED, STOPPED, INTERRUPTED }

/** One row of the workout log: a single session of a workout. */
data class LogEntry(
    val id: String = newId(),
    val workoutId: String,
    /** Snapshot of the workout name when the session ran. */
    val workoutName: String,
    /** Epoch milliseconds. */
    val startedAt: Long,
    /** Epoch milliseconds; null while IN_PROGRESS. */
    val finishedAt: Long? = null,
    /** IANA zone id the session was recorded in, e.g. "America/Chicago". */
    val zoneId: String,
    /** Wall-clock seconds from start to finish, including pauses. */
    val elapsedSec: Int = 0,
    val pausedSec: Int = 0,
    val completedSteps: Int = 0,
    val totalSteps: Int,
    val rounds: Int,
    val rowCount: Int,
    val status: SessionStatus = SessionStatus.IN_PROGRESS,
    val notes: String = "",
)

enum class VibrationMode { ALWAYS, WHEN_SILENT, NEVER }

data class Settings(
    val speechEnabled: Boolean = true,
    val duckOtherAudio: Boolean = true,
    val vibrationMode: VibrationMode = VibrationMode.WHEN_SILENT,
    val chimesMuted: Boolean = false,
    val keepScreenOn: Boolean = true,
    val warningTicks: Boolean = true,
)
