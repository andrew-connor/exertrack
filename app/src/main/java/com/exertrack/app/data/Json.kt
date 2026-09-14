package com.exertrack.app.data

import com.exertrack.app.engine.Phase
import com.exertrack.app.engine.SessionSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** JSON (de)serialisation for every persisted type, using the platform org.json. */
object Json {
    const val SCHEMA_VERSION = 1

    // ---- rows / workouts ----

    fun row(r: ExerciseRow): JSONObject = JSONObject()
        .put("id", r.id)
        .put("name", r.name)
        .put("durationSec", r.durationSec ?: JSONObject.NULL)
        .put("triggerSec", r.triggerSec ?: JSONObject.NULL)
        .put("notes", r.notes)

    fun row(o: JSONObject): ExerciseRow = ExerciseRow(
        id = o.optString("id").ifEmpty { newId() },
        name = o.optString("name"),
        durationSec = intOrNull(o, "durationSec"),
        triggerSec = intOrNull(o, "triggerSec"),
        notes = o.optString("notes"),
    ).normalized()

    fun workout(w: Workout): JSONObject = JSONObject()
        .put("id", w.id)
        .put("name", w.name)
        .put("rows", JSONArray().apply { w.rows.forEach { put(row(it)) } })
        .put("rounds", w.rounds)
        .put("leadInSec", w.leadInSec)
        .put("createdAt", w.createdAt)
        .put("updatedAt", w.updatedAt)
        .put("lastUsedAt", w.lastUsedAt ?: JSONObject.NULL)
        .put("sourceUri", w.sourceUri ?: JSONObject.NULL)
        .put("sourceSheet", w.sourceSheet ?: JSONObject.NULL)

    fun workout(o: JSONObject): Workout {
        val rows = o.optJSONArray("rows")?.let { arr -> (0 until arr.length()).map { row(arr.getJSONObject(it)) } } ?: emptyList()
        return Workout(
            id = o.optString("id").ifEmpty { newId() },
            name = o.optString("name").ifEmpty { "Untitled workout" },
            rows = rows,
            rounds = o.optInt("rounds", 1).coerceAtLeast(1),
            leadInSec = o.optInt("leadInSec", 3).coerceAtLeast(0),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            lastUsedAt = longOrNull(o, "lastUsedAt"),
            sourceUri = stringOrNull(o, "sourceUri"),
            sourceSheet = stringOrNull(o, "sourceSheet"),
        )
    }

    fun workoutsDocument(list: List<Workout>): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("workouts", JSONArray().apply { list.forEach { put(workout(it)) } })
        .toString(2)

    fun parseWorkoutsDocument(text: String): List<Workout> {
        val o = JSONObject(text)
        val arr = o.optJSONArray("workouts") ?: return emptyList()
        return (0 until arr.length()).map { workout(arr.getJSONObject(it)) }
    }

    // ---- log ----

    fun logEntry(e: LogEntry): JSONObject = JSONObject()
        .put("id", e.id)
        .put("workoutId", e.workoutId)
        .put("workoutName", e.workoutName)
        .put("startedAt", e.startedAt)
        .put("finishedAt", e.finishedAt ?: JSONObject.NULL)
        .put("zoneId", e.zoneId)
        .put("elapsedSec", e.elapsedSec)
        .put("pausedSec", e.pausedSec)
        .put("completedSteps", e.completedSteps)
        .put("totalSteps", e.totalSteps)
        .put("rounds", e.rounds)
        .put("rowCount", e.rowCount)
        .put("status", e.status.name)
        .put("notes", e.notes)

    fun logEntry(o: JSONObject): LogEntry = LogEntry(
        id = o.optString("id").ifEmpty { newId() },
        workoutId = o.optString("workoutId"),
        workoutName = o.optString("workoutName"),
        startedAt = o.optLong("startedAt", 0L),
        finishedAt = longOrNull(o, "finishedAt"),
        zoneId = o.optString("zoneId").ifEmpty { "UTC" },
        elapsedSec = o.optInt("elapsedSec", 0),
        pausedSec = o.optInt("pausedSec", 0),
        completedSteps = o.optInt("completedSteps", 0),
        totalSteps = o.optInt("totalSteps", 0),
        rounds = o.optInt("rounds", 1),
        rowCount = o.optInt("rowCount", 0),
        status = runCatching { SessionStatus.valueOf(o.optString("status")) }.getOrDefault(SessionStatus.INTERRUPTED),
        notes = o.optString("notes"),
    )

    fun logDocument(list: List<LogEntry>): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("entries", JSONArray().apply { list.forEach { put(logEntry(it)) } })
        .toString(2)

    fun parseLogDocument(text: String): List<LogEntry> {
        val arr = JSONObject(text).optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).map { logEntry(arr.getJSONObject(it)) }
    }

    // ---- settings ----

    fun settings(s: Settings): JSONObject = JSONObject()
        .put("speechEnabled", s.speechEnabled)
        .put("duckOtherAudio", s.duckOtherAudio)
        .put("vibrationMode", s.vibrationMode.name)
        .put("chimesMuted", s.chimesMuted)
        .put("keepScreenOn", s.keepScreenOn)
        .put("warningTicks", s.warningTicks)

    fun settings(o: JSONObject): Settings = Settings(
        speechEnabled = o.optBoolean("speechEnabled", true),
        duckOtherAudio = o.optBoolean("duckOtherAudio", true),
        vibrationMode = runCatching { VibrationMode.valueOf(o.optString("vibrationMode")) }.getOrDefault(VibrationMode.WHEN_SILENT),
        chimesMuted = o.optBoolean("chimesMuted", false),
        keepScreenOn = o.optBoolean("keepScreenOn", true),
        warningTicks = o.optBoolean("warningTicks", true),
    )

    fun settingsDocument(s: Settings): String =
        settings(s).put("schemaVersion", SCHEMA_VERSION).toString(2)

    fun parseSettingsDocument(text: String): Settings = settings(JSONObject(text))

    // ---- session snapshot ----

    fun snapshot(s: SessionSnapshot): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("sessionId", s.sessionId)
        .put("workoutId", s.workoutId)
        .put("phase", s.phase.name)
        .put("stepIndex", s.stepIndex)
        .put("remainingMs", s.remainingMs ?: JSONObject.NULL)
        .put("phaseElapsedMs", s.phaseElapsedMs)
        .put("completedSteps", JSONArray(s.completedSteps))
        .put("startedAtWall", s.startedAtWall)
        .put("pausedMs", s.pausedMs)
        .put("savedAtWall", s.savedAtWall)

    fun snapshot(o: JSONObject): SessionSnapshot {
        val steps = o.optJSONArray("completedSteps")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        return SessionSnapshot(
            sessionId = o.getString("sessionId"),
            workoutId = o.getString("workoutId"),
            phase = Phase.valueOf(o.getString("phase")),
            stepIndex = o.optInt("stepIndex", 0),
            remainingMs = longOrNull(o, "remainingMs"),
            phaseElapsedMs = o.optLong("phaseElapsedMs", 0L),
            completedSteps = steps,
            startedAtWall = o.optLong("startedAtWall", 0L),
            pausedMs = o.optLong("pausedMs", 0L),
            savedAtWall = o.optLong("savedAtWall", 0L),
        )
    }

    fun snapshotDocument(s: SessionSnapshot): String = snapshot(s).toString(2)
    fun parseSnapshotDocument(text: String): SessionSnapshot = snapshot(JSONObject(text))

    // ---- helpers ----

    fun intOrNull(o: JSONObject, key: String): Int? = if (!o.has(key) || o.isNull(key)) null else o.optInt(key)
    fun longOrNull(o: JSONObject, key: String): Long? = if (!o.has(key) || o.isNull(key)) null else o.optLong(key)
    fun stringOrNull(o: JSONObject, key: String): String? = if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
}
