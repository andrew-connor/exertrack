package com.exertrack.app.io

import com.exertrack.app.data.Json
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.Settings
import com.exertrack.app.data.Workout
import org.json.JSONArray
import org.json.JSONObject

/** Whole-app backup file: workouts, log and settings in one JSON document. */
object Backup {
    data class Data(
        val workouts: List<Workout>,
        val log: List<LogEntry>,
        val settings: Settings,
        val exportedAt: Long?,
    )

    fun toJson(workouts: List<Workout>, log: List<LogEntry>, settings: Settings, exportedAt: Long): String =
        JSONObject()
            .put("app", "Exertrack")
            .put("schemaVersion", Json.SCHEMA_VERSION)
            .put("exportedAt", exportedAt)
            .put("workouts", JSONArray().apply { workouts.forEach { put(Json.workout(it)) } })
            .put("log", JSONArray().apply { log.forEach { put(Json.logEntry(it)) } })
            .put("settings", Json.settings(settings))
            .toString(2)

    /** @throws IllegalArgumentException when the text is not an Exertrack backup. */
    fun parse(text: String): Data {
        val o = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Not a JSON file") }
        if (o.optString("app") != "Exertrack" || !o.has("workouts")) throw IllegalArgumentException("Not an Exertrack backup file")
        val workouts = o.getJSONArray("workouts").let { arr -> (0 until arr.length()).map { Json.workout(arr.getJSONObject(it)) } }
        val log = o.optJSONArray("log")?.let { arr -> (0 until arr.length()).map { Json.logEntry(arr.getJSONObject(it)) } } ?: emptyList()
        val settings = o.optJSONObject("settings")?.let { Json.settings(it) } ?: Settings()
        return Data(workouts, log, settings, Json.longOrNull(o, "exportedAt"))
    }
}
