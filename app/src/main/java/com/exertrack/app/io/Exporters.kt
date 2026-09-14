package com.exertrack.app.io

import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.Workout

/** Builds the bytes for every export the app offers. */
object Exporters {
    const val MIME_CSV = "text/csv"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_JSON = "application/json"

    /**
     * A workout in the import layout: header row `Exercise,Duration (s),Trigger (s),Notes`,
     * untimed = blank duration, manual = blank trigger, RFC 4180 quoting, CRLF line endings,
     * UTF-8 with BOM so Excel opens it correctly.
     */
    fun workoutCsv(workout: Workout): ByteArray = TODO("implemented by the writing module")

    /**
     * Log as CSV (UTF-8 with BOM, CRLF). Columns, in order:
     * date, start_time, end_time, elapsed_min, paused_min, workout, completed, total, rounds,
     * status, notes, session_id, workout_id. Times are local to each entry's zoneId, formatted
     * yyyy-MM-dd and HH:mm:ss; elapsed_min and paused_min have one decimal; status is lower-case.
     */
    fun logCsv(entries: List<LogEntry>): ByteArray = TODO("implemented by the writing module")

    /** Same columns as [logCsv] as a formatted .xlsx (see XlsxWriter) with real date/time cells. */
    fun logXlsx(entries: List<LogEntry>): ByteArray = TODO("implemented by the writing module")
}
