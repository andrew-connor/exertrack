package com.exertrack.app.io

import com.exertrack.app.data.ExerciseRow
import kotlin.math.roundToLong

/** One parsed spreadsheet row. [row] is null when the row has a blocking [problem]. */
data class ParsedRow(
    /** 1-based row number in the sheet (header row counted). */
    val rowNumber: Int,
    val row: ExerciseRow?,
    val problem: String? = null,
    val warning: String? = null,
)

data class ParsedTable(
    val sheetName: String,
    val hasHeader: Boolean,
    /** Data rows only (header and all-blank rows excluded). */
    val rows: List<ParsedRow>,
) {
    val exercises: List<ExerciseRow> get() = rows.mapNotNull { it.row }
    val problemCount: Int get() = rows.count { it.problem != null }
    val warningCount: Int get() = rows.count { it.warning != null }
    val isEmpty: Boolean get() = rows.isEmpty()
    val isClean: Boolean get() = rows.isNotEmpty() && problemCount == 0
}

sealed class DurationParse {
    object Untimed : DurationParse()
    data class Seconds(val seconds: Int, val warning: String? = null) : DurationParse()
    data class Invalid(val reason: String) : DurationParse()
}

sealed class TriggerParse {
    object Manual : TriggerParse()
    data class Seconds(val seconds: Int) : TriggerParse()
    data class Invalid(val reason: String) : TriggerParse()
}

/**
 * Turns a raw [SheetTable] into exercise rows following the rules in docs/DESIGN.md (columns by
 * position A-D, header detection, duration / trigger grammar, blocking problems vs warnings).
 */
object WorkoutTableParser {
    const val MISSING_NAME = "Missing exercise name"
    const val EXCEL_TIME_DURATION = "Looks like an Excel time value; check the duration"
    const val EXCEL_TIME_TRIGGER = "Looks like an Excel time value; check the trigger"
    const val LONG_DURATION = "Longer than an hour; check the duration"
    const val LONG_TRIGGER = "Rest longer than an hour; check the trigger"

    /** Durations at or above this many seconds are flagged, so "1:00:00" is questioned too. */
    private const val LONG_SECONDS = 3600
    private const val SECONDS_PER_DAY = 86400.0

    private val HEADER_NAMES = setOf("exercise", "exercise name", "name")
    private val UNTIMED_WORDS = setOf("", "0", "-", "untimed", "reps")
    private val MANUAL_WORDS = setOf("", "0", "-", "manual", "m")

    private const val DECIMAL = """[+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+)"""
    private val NUMBER = Regex("^($DECIMAL)$")
    private val NUMBER_WITH_UNIT = Regex(
        "^($DECIMAL)\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours)$",
        RegexOption.IGNORE_CASE,
    )
    private val MINUTES_SECONDS = Regex("""^(\d+):(\d{1,2})$""")
    private val HOURS_MINUTES_SECONDS = Regex("""^(\d+):(\d{1,2}):(\d{1,2})$""")

    fun parse(sheet: SheetTable): ParsedTable {
        val rows = sheet.rows
        val firstContentIndex = rows.indexOfFirst { row -> !isBlankRow(row) }
        val hasHeader = firstContentIndex >= 0 && looksLikeHeader(rows[firstContentIndex])
        val parsed = ArrayList<ParsedRow>()
        rows.forEachIndexed { index, cells ->
            if (hasHeader && index == firstContentIndex) return@forEachIndexed
            if (isBlankRow(cells)) return@forEachIndexed
            parsed.add(parseRow(index + 1, cells))
        }
        return ParsedTable(sheet.name, hasHeader, parsed)
    }

    /** True when [firstRow] looks like a header row (see DESIGN.md). */
    fun looksLikeHeader(firstRow: List<String>): Boolean {
        val nameCell = cell(firstRow, 0).trim().lowercase()
        if (nameCell in HEADER_NAMES) return true
        val durationCell = cell(firstRow, 1).trim()
        return durationCell.isNotEmpty() && parseDuration(durationCell) is DurationParse.Invalid
    }

    fun parseDuration(text: String): DurationParse {
        val trimmed = text.trim()
        if (trimmed.lowercase() in UNTIMED_WORDS) return DurationParse.Untimed
        return when (val amount = parseAmount(trimmed)) {
            Amount.Zero -> DurationParse.Untimed
            is Amount.Invalid -> DurationParse.Invalid(reason("duration", trimmed, amount.detail))
            is Amount.Seconds -> {
                val warnings = ArrayList<String>(2)
                if (amount.excelFraction) warnings.add(EXCEL_TIME_DURATION)
                if (amount.seconds >= LONG_SECONDS) warnings.add(LONG_DURATION)
                DurationParse.Seconds(amount.seconds, warnings.joinToString("; ").ifEmpty { null })
            }
        }
    }

    fun parseTrigger(text: String): TriggerParse = analyseTrigger(text).result

    // ---- rows ----------------------------------------------------------------------------

    private fun parseRow(rowNumber: Int, cells: List<String>): ParsedRow {
        val name = cell(cells, 0).trim()
        val durationText = cell(cells, 1).trim()
        val triggerText = cell(cells, 2).trim()
        val notes = normalizeNotes(cell(cells, 3))

        val problems = ArrayList<String>(3)
        val warnings = ArrayList<String>(2)
        if (name.isEmpty()) problems.add(MISSING_NAME)

        var durationSec: Int? = null
        when (val duration = parseDuration(durationText)) {
            DurationParse.Untimed -> Unit
            is DurationParse.Seconds -> {
                durationSec = duration.seconds
                duration.warning?.let { warnings.add(it) }
            }
            is DurationParse.Invalid -> problems.add(duration.reason)
        }

        var triggerSec: Int? = null
        val trigger = analyseTrigger(triggerText)
        when (val result = trigger.result) {
            TriggerParse.Manual -> Unit
            is TriggerParse.Seconds -> triggerSec = result.seconds
            is TriggerParse.Invalid -> problems.add(result.reason)
        }
        trigger.warning?.let { warnings.add(it) }

        val problem = problems.joinToString("; ").ifEmpty { null }
        val warning = warnings.joinToString("; ").ifEmpty { null }
        val row = if (problem == null) {
            ExerciseRow(name = name, durationSec = durationSec, triggerSec = triggerSec, notes = notes).normalized()
        } else {
            null
        }
        return ParsedRow(rowNumber = rowNumber, row = row, problem = problem, warning = warning)
    }

    private fun cell(cells: List<String>, index: Int): String = cells.getOrNull(index) ?: ""

    private fun isBlankRow(cells: List<String>): Boolean = cells.all { it.isBlank() }

    /** Trims the ends and normalises line endings; internal line breaks are kept. */
    private fun normalizeNotes(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()

    // ---- triggers ------------------------------------------------------------------------

    private class TriggerAnalysis(val result: TriggerParse, val warning: String?)

    private fun analyseTrigger(text: String): TriggerAnalysis {
        val trimmed = text.trim()
        if (trimmed.lowercase() in MANUAL_WORDS) return TriggerAnalysis(TriggerParse.Manual, null)
        return when (val amount = parseAmount(trimmed)) {
            Amount.Zero -> TriggerAnalysis(TriggerParse.Manual, null)
            is Amount.Invalid -> TriggerAnalysis(TriggerParse.Invalid(reason("trigger", trimmed, amount.detail)), null)
            is Amount.Seconds -> {
                val warnings = ArrayList<String>(2)
                if (amount.excelFraction) warnings.add(EXCEL_TIME_TRIGGER)
                if (amount.seconds >= LONG_SECONDS) warnings.add(LONG_TRIGGER)
                TriggerAnalysis(TriggerParse.Seconds(amount.seconds), warnings.joinToString("; ").ifEmpty { null })
            }
        }
    }

    // ---- shared grammar ------------------------------------------------------------------

    private sealed class Amount {
        object Zero : Amount()
        class Seconds(val seconds: Int, val excelFraction: Boolean) : Amount()
        class Invalid(val detail: String?) : Amount()
    }

    /**
     * The grammar shared by durations and triggers: integer or decimal seconds (decimals rounded,
     * a value strictly between 0 and 1 read as an Excel time fraction), "m:ss", "h:mm:ss", or a
     * number followed by a seconds / minutes / hours unit.
     */
    private fun parseAmount(text: String): Amount {
        HOURS_MINUTES_SECONDS.matchEntire(text)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: return Amount.Invalid("too large")
            val minutes = match.groupValues[2].toInt()
            val seconds = match.groupValues[3].toInt()
            if (minutes > 59 || seconds > 59) return Amount.Invalid("minutes and seconds must be 00-59")
            return secondsAmount(hours * 3600 + minutes * 60 + seconds, excelFraction = false)
        }
        MINUTES_SECONDS.matchEntire(text)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return Amount.Invalid("too large")
            val seconds = match.groupValues[2].toInt()
            if (seconds > 59) return Amount.Invalid("seconds must be 00-59")
            return secondsAmount(minutes * 60 + seconds, excelFraction = false)
        }
        NUMBER.matchEntire(text)?.let { match ->
            val value = toDouble(match.groupValues[1]) ?: return Amount.Invalid(null)
            if (value < 0) return Amount.Invalid("must not be negative")
            if (value > 0 && value < 1) {
                return secondsAmount((value * SECONDS_PER_DAY).roundToLong(), excelFraction = true)
            }
            return secondsAmount(value.roundToLong(), excelFraction = false)
        }
        NUMBER_WITH_UNIT.matchEntire(text)?.let { match ->
            val value = toDouble(match.groupValues[1]) ?: return Amount.Invalid(null)
            if (value < 0) return Amount.Invalid("must not be negative")
            val multiplier = when (match.groupValues[2].lowercase()) {
                "s", "sec", "secs", "second", "seconds" -> 1.0
                "m", "min", "mins", "minute", "minutes" -> 60.0
                else -> 3600.0
            }
            return secondsAmount((value * multiplier).roundToLong(), excelFraction = false)
        }
        return Amount.Invalid(null)
    }

    private fun secondsAmount(seconds: Long, excelFraction: Boolean): Amount = when {
        seconds <= 0L -> Amount.Zero
        seconds > Int.MAX_VALUE -> Amount.Invalid("too large")
        else -> Amount.Seconds(seconds.toInt(), excelFraction)
    }

    private fun toDouble(text: String): Double? {
        val value = text.replace(',', '.').toDoubleOrNull() ?: return null
        return if (value.isNaN() || value.isInfinite()) null else value
    }

    private fun reason(kind: String, text: String, detail: String?): String =
        if (detail == null) "Invalid $kind \"$text\"" else "Invalid $kind \"$text\": $detail"
}

/** Entry point used by the import screen: sniff the format and return every sheet. */
object SpreadsheetImport {
    private const val SNIFF_BYTES = 4096
    private const val MAX_CONTROL_FRACTION = 0.05

    /**
     * @param fileName display name of the picked file; used as the sheet name for CSV (extension stripped).
     * @throws IllegalArgumentException when the bytes are neither a spreadsheet nor text.
     */
    fun readAll(bytes: ByteArray, fileName: String): List<SheetTable> {
        if (XlsxReader.isXlsx(bytes)) return XlsxReader.read(bytes)
        require(!looksBinary(bytes)) { "This file is not a spreadsheet or a text file" }
        return listOf(CsvParser.parse(bytes, sheetNameFor(fileName)))
    }

    /** The file name without its extension, or "CSV" when nothing usable is left. */
    fun sheetNameFor(fileName: String): String {
        val base = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        val name = if (dot >= 0) base.substring(0, dot) else base
        return name.trim().ifEmpty { "CSV" }
    }

    /**
     * True when more than 5% of the first 4 KB are control characters other than tab, CR and LF.
     * UTF-16 text (which is full of zero bytes) is recognised by its byte-order mark first.
     */
    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.size >= 2 && (
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                    (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                )
        ) return false
        val length = minOf(bytes.size, SNIFF_BYTES)
        var control = 0
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            val isControl = (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) || b == 0x7F
            if (isControl) control++
        }
        return control > length * MAX_CONTROL_FRACTION
    }
}
