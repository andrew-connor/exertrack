package com.exertrack.app.io

/** A rectangular grid of cell strings from one sheet or one CSV file. Cells are raw (untrimmed) text. */
data class SheetTable(val name: String, val rows: List<List<String>>)

/**
 * CSV reader: strips a UTF-8 BOM, decodes UTF-8 (falling back to windows-1252 when the bytes are
 * not valid UTF-8), auto-detects the delimiter among comma / semicolon / tab from the first
 * non-empty lines, handles RFC 4180 quoting (quoted fields may contain delimiters, doubled
 * quotes and line breaks) and any of CRLF / LF / CR line endings.
 */
object CsvParser {
    fun parse(bytes: ByteArray, name: String = "CSV"): SheetTable = TODO("implemented by the parsing module")

    fun parseText(text: String, name: String = "CSV"): SheetTable = TODO("implemented by the parsing module")

    /** The delimiter character used by [text], or ',' when undecidable. */
    fun detectDelimiter(text: String): Char = TODO("implemented by the parsing module")
}
