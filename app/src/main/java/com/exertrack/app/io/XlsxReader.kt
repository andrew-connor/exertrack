package com.exertrack.app.io

/**
 * Minimal .xlsx reader (no third-party libraries): unzips the package, reads
 * xl/workbook.xml + xl/_rels/workbook.xml.rels for sheet names and order, xl/sharedStrings.xml,
 * and each worksheet's sheetData. Supports shared strings (t="s"), inline strings (t="inlineStr"),
 * plain numbers, booleans (t="b"), and the cached value of formula cells. Numbers are returned as
 * the text stored in the file (e.g. "40", "0.0625", "1.5E-2" normalised to plain decimal).
 * Sparse rows/cells are filled with "" so every row has cells up to its last populated column.
 */
object XlsxReader {
    /** True when the bytes start with the zip signature "PK". */
    fun isXlsx(bytes: ByteArray): Boolean = TODO("implemented by the parsing module")

    /** All sheets in workbook order. Throws IllegalArgumentException for a file that is not a spreadsheet. */
    fun read(bytes: ByteArray): List<SheetTable> = TODO("implemented by the parsing module")
}
