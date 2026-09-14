package com.exertrack.app.io

/** Cell model for [XlsxWriter]. */
sealed class XCell {
    data class Text(val value: String, val wrap: Boolean = false) : XCell()
    data class Number(val value: Double) : XCell()
    /** Rendered as a real Excel date/time serial with the matching number format. */
    data class DateTime(val epochMs: Long, val zoneId: String, val style: DateStyle) : XCell()
    object Blank : XCell()
}

enum class DateStyle { DATE, TIME, DATE_TIME }

data class XSheet(
    val name: String,
    val header: List<String>,
    val rows: List<List<XCell>>,
    /** Column widths in characters, one per column; null = default. */
    val columnWidths: List<Double>? = null,
)

/**
 * Minimal .xlsx writer (no third-party libraries): writes [Content_Types].xml, _rels/.rels,
 * docProps, xl/workbook.xml, xl/_rels/workbook.xml.rels, xl/styles.xml, xl/sharedStrings.xml
 * (or inline strings) and one worksheet per [XSheet]. Formatting: bold header row with a light
 * fill, frozen top row, autofilter over the used range, column widths, number formats
 * yyyy-mm-dd / hh:mm / yyyy-mm-dd hh:mm for [XCell.DateTime], wrapped text for Text(wrap = true).
 * Sheet names are sanitised to Excel's rules (31 chars, no []:*?/\ ) and made unique.
 */
object XlsxWriter {
    fun write(sheets: List<XSheet>): ByteArray = TODO("implemented by the writing module")

    /** Excel serial number (days since 1899-12-30) for a local date-time in [zoneId]. */
    fun excelSerial(epochMs: Long, zoneId: String): Double = TODO("implemented by the writing module")
}
