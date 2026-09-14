package com.exertrack.app.io

import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.Workout
import com.exertrack.app.ui.Format
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Builds the bytes for every export the app offers. */
object Exporters {
    const val MIME_CSV = "text/csv"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_JSON = "application/json"

    /** Header row of the log exports, in column order; shared by [logCsv] and [logXlsx]. */
    val LOG_COLUMNS: List<String> = listOf(
        "date", "start_time", "end_time", "elapsed_min", "paused_min", "workout",
        "completed", "total", "rounds", "status", "notes", "session_id", "workout_id",
    )

    /** Header row of [workoutCsv], which is also the layout the importer expects. */
    val WORKOUT_COLUMNS: List<String> = listOf("Exercise", "Duration (s)", "Trigger (s)", "Notes")

    private const val CRLF = "\r\n"
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** Widths in characters for the log sheet, one per [LOG_COLUMNS] entry. */
    private val LOG_COLUMN_WIDTHS = listOf(12.0, 11.0, 11.0, 12.0, 11.0, 28.0, 11.0, 8.0, 8.0, 13.0, 48.0, 38.0, 38.0)

    /**
     * A workout in the import layout: header row `Exercise,Duration (s),Trigger (s),Notes`,
     * untimed = blank duration, manual = blank trigger, RFC 4180 quoting, CRLF line endings,
     * UTF-8 with BOM so Excel opens it correctly.
     */
    fun workoutCsv(workout: Workout): ByteArray {
        val sb = StringBuilder()
        appendCsvRecord(sb, WORKOUT_COLUMNS)
        for (row in workout.rows) {
            appendCsvRecord(sb, listOf(row.name, secondsOrBlank(row.durationSec), secondsOrBlank(row.triggerSec), row.notes))
        }
        return csvBytes(sb)
    }

    /**
     * Log as CSV (UTF-8 with BOM, CRLF). Columns, in order:
     * date, start_time, end_time, elapsed_min, paused_min, workout, completed, total, rounds,
     * status, notes, session_id, workout_id. Times are local to each entry's zoneId, formatted
     * yyyy-MM-dd and HH:mm:ss; elapsed_min and paused_min have one decimal; status is lower-case.
     */
    fun logCsv(entries: List<LogEntry>): ByteArray {
        val sb = StringBuilder()
        appendCsvRecord(sb, LOG_COLUMNS)
        for (entry in entries) {
            appendCsvRecord(
                sb,
                listOf(
                    Format.isoDate(entry.startedAt, entry.zoneId),
                    Format.isoTime(entry.startedAt, entry.zoneId),
                    entry.finishedAt?.let { Format.isoTime(it, entry.zoneId) } ?: "",
                    minutesText(entry.elapsedSec),
                    minutesText(entry.pausedSec),
                    entry.workoutName,
                    entry.completedSteps.toString(),
                    entry.totalSteps.toString(),
                    entry.rounds.toString(),
                    statusText(entry),
                    entry.notes,
                    entry.id,
                    entry.workoutId,
                ),
            )
        }
        return csvBytes(sb)
    }

    /** Same columns as [logCsv] as a formatted .xlsx (see XlsxWriter) with real date/time cells. */
    fun logXlsx(entries: List<LogEntry>): ByteArray = XlsxWriter.write(listOf(logSheet(entries)))

    /** The log as an [XSheet], so other exports (such as a multi-sheet workbook) can reuse it. */
    fun logSheet(entries: List<LogEntry>): XSheet = XSheet(
        name = "Log",
        header = LOG_COLUMNS,
        rows = entries.map { entry ->
            listOf(
                XCell.DateTime(entry.startedAt, entry.zoneId, DateStyle.DATE),
                XCell.DateTime(entry.startedAt, entry.zoneId, DateStyle.TIME),
                entry.finishedAt?.let { XCell.DateTime(it, entry.zoneId, DateStyle.TIME) } ?: XCell.Blank,
                XCell.Number(minutes(entry.elapsedSec)),
                XCell.Number(minutes(entry.pausedSec)),
                XCell.Text(entry.workoutName),
                XCell.Number(entry.completedSteps.toDouble()),
                XCell.Number(entry.totalSteps.toDouble()),
                XCell.Number(entry.rounds.toDouble()),
                XCell.Text(statusText(entry)),
                XCell.Text(entry.notes, wrap = true),
                XCell.Text(entry.id),
                XCell.Text(entry.workoutId),
            )
        },
        columnWidths = LOG_COLUMN_WIDTHS,
    )

    // ---- CSV helpers ----

    /**
     * One field per RFC 4180: quoted when it contains a comma, a double quote, CR or LF, with
     * embedded double quotes doubled. Anything else is written as-is.
     */
    fun csvField(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun appendCsvRecord(sb: StringBuilder, fields: List<String>) {
        fields.forEachIndexed { index, field ->
            if (index > 0) sb.append(',')
            sb.append(csvField(field))
        }
        sb.append(CRLF)
    }

    private fun csvBytes(sb: StringBuilder): ByteArray {
        val body = sb.toString().toByteArray(StandardCharsets.UTF_8)
        return UTF8_BOM + body
    }

    private fun secondsOrBlank(seconds: Int?): String = seconds?.takeIf { it > 0 }?.toString() ?: ""

    /** Minutes with one decimal, rounded half up, for the CSV. */
    private fun minutesText(seconds: Int): String = String.format(Locale.US, "%.1f", seconds / 60.0)

    /** Minutes rounded to one decimal, so the spreadsheet shows the same figures as the CSV. */
    private fun minutes(seconds: Int): Double = Math.round(seconds / 6.0) / 10.0

    private fun statusText(entry: LogEntry): String = entry.status.name.lowercase(Locale.US)
}
