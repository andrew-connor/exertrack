package com.exertrack.app.io

import com.exertrack.app.data.ExerciseRow

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
    fun parse(sheet: SheetTable): ParsedTable = TODO("implemented by the parsing module")

    /** True when [firstRow] looks like a header row (see DESIGN.md). */
    fun looksLikeHeader(firstRow: List<String>): Boolean = TODO("implemented by the parsing module")

    fun parseDuration(text: String): DurationParse = TODO("implemented by the parsing module")

    fun parseTrigger(text: String): TriggerParse = TODO("implemented by the parsing module")
}

/** Entry point used by the import screen: sniff the format and return every sheet. */
object SpreadsheetImport {
    /**
     * @param fileName display name of the picked file; used as the sheet name for CSV (extension stripped).
     * @throws IllegalArgumentException when the bytes are neither a spreadsheet nor text.
     */
    fun readAll(bytes: ByteArray, fileName: String): List<SheetTable> = TODO("implemented by the parsing module")
}
