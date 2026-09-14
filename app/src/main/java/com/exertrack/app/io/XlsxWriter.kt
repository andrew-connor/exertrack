package com.exertrack.app.io

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
 *
 * Every string is written as an inline string (`t="inlineStr"`), so no sharedStrings part is
 * needed. An empty sheet list still produces a valid workbook with one empty sheet, because a
 * workbook without worksheets does not open. A [DateStyle.DATE] cell stores the whole-day serial
 * (local midnight); the other two date styles keep the time of day in the fraction.
 */
object XlsxWriter {
    /** Index into `cellXfs` of styles.xml: the plain cell. */
    const val STYLE_DEFAULT = 0
    /** Bold text on a light solid fill; used for the header row. */
    const val STYLE_HEADER = 1
    /** Number format `yyyy-mm-dd`. */
    const val STYLE_DATE = 2
    /** Number format `hh:mm`. */
    const val STYLE_TIME = 3
    /** Number format `yyyy-mm-dd hh:mm`. */
    const val STYLE_DATE_TIME = 4
    /** Wrapped text, aligned to the top of the cell. */
    const val STYLE_WRAP = 5

    /** First id available for custom number formats; lower ids are Excel's built-ins. */
    private const val NUM_FMT_DATE = 164
    private const val NUM_FMT_TIME = 165
    private const val NUM_FMT_DATE_TIME = 166

    private const val MAX_SHEET_NAME_LENGTH = 31
    private const val FORBIDDEN_SHEET_NAME_CHARS = "[]:*?/\\"
    private const val SECONDS_PER_DAY = 86_400.0
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_DOC_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"

    fun write(sheets: List<XSheet>): ByteArray {
        val effective = sheets.ifEmpty { listOf(XSheet(name = "Sheet1", header = emptyList(), rows = emptyList())) }
        val names = uniqueSheetNames(effective.map { it.name })
        val extents = effective.map { Extent.of(it) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, StandardCharsets.UTF_8).use { zip ->
            zip.putPart("[Content_Types].xml", contentTypes(effective.size))
            zip.putPart("_rels/.rels", packageRels())
            zip.putPart("docProps/app.xml", appProperties())
            zip.putPart("docProps/core.xml", coreProperties())
            zip.putPart("xl/workbook.xml", workbook(names, extents))
            zip.putPart("xl/_rels/workbook.xml.rels", workbookRels(effective.size))
            zip.putPart("xl/styles.xml", styles())
            effective.forEachIndexed { index, sheet ->
                zip.putPart("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet, extents[index], selected = index == 0))
            }
        }
        return out.toByteArray()
    }

    /** Excel serial number (days since 1899-12-30) for a local date-time in [zoneId]. */
    fun excelSerial(epochMs: Long, zoneId: String): Double {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }
        val local = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
        val days = ChronoUnit.DAYS.between(EXCEL_EPOCH, local.toLocalDate())
        return days + local.toLocalTime().toSecondOfDay() / SECONDS_PER_DAY
    }

    // ---- package parts ----

    private fun ZipOutputStream.putPart(name: String, xml: String) {
        putNextEntry(ZipEntry(name))
        write(xml.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append(XML_DECLARATION)
        append("<Types xmlns=\"").append(NS_CONTENT_TYPES).append("\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..sheetCount) {
            append("<Override PartName=\"/xl/worksheets/sheet").append(i)
            append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        append("</Types>")
    }

    private fun packageRels(): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"").append(NS_PKG_RELS).append("\">")
        append("<Relationship Id=\"rId1\" Type=\"").append(NS_DOC_RELS).append("/officeDocument\" Target=\"xl/workbook.xml\"/>")
        append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>")
        append("<Relationship Id=\"rId3\" Type=\"").append(NS_DOC_RELS).append("/extended-properties\" Target=\"docProps/app.xml\"/>")
        append("</Relationships>")
    }

    private fun appProperties(): String = buildString {
        append(XML_DECLARATION)
        append("<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"")
        append(" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">")
        append("<Application>Exertrack</Application>")
        append("</Properties>")
    }

    private fun coreProperties(): String {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        return buildString {
            append(XML_DECLARATION)
            append("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\"")
            append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\"")
            append(" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
            append("<dc:creator>Exertrack</dc:creator>")
            append("<dcterms:created xsi:type=\"dcterms:W3CDTF\">").append(now).append("</dcterms:created>")
            append("<dcterms:modified xsi:type=\"dcterms:W3CDTF\">").append(now).append("</dcterms:modified>")
            append("</cp:coreProperties>")
        }
    }

    private fun workbook(names: List<String>, extents: List<Extent>): String = buildString {
        append(XML_DECLARATION)
        append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_DOC_RELS).append("\">")
        append("<bookViews><workbookView activeTab=\"0\"/></bookViews>")
        append("<sheets>")
        names.forEachIndexed { index, name ->
            append("<sheet name=\"").append(escapeXml(name, attribute = true)).append("\"")
            append(" sheetId=\"").append(index + 1).append("\" r:id=\"rId").append(index + 1).append("\"/>")
        }
        append("</sheets>")
        // Excel records each sheet's autofilter range as a hidden defined name; declaring it keeps
        // the filter buttons attached when the file is re-saved.
        val filtered = extents.indices.filter { extents[it].hasColumns }
        if (filtered.isNotEmpty()) {
            append("<definedNames>")
            for (index in filtered) {
                append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(index).append("\" hidden=\"1\">")
                append(escapeXml(quoteSheetName(names[index]) + "!" + extents[index].absoluteRef, attribute = false))
                append("</definedName>")
            }
            append("</definedNames>")
        }
        append("</workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"").append(NS_PKG_RELS).append("\">")
        for (i in 1..sheetCount) {
            append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(NS_DOC_RELS)
            append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>")
        }
        append("<Relationship Id=\"rId").append(sheetCount + 1).append("\" Type=\"").append(NS_DOC_RELS)
        append("/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun styles(): String = buildString {
        append(XML_DECLARATION)
        append("<styleSheet xmlns=\"").append(NS_MAIN).append("\">")
        append("<numFmts count=\"3\">")
        append("<numFmt numFmtId=\"").append(NUM_FMT_DATE).append("\" formatCode=\"yyyy-mm-dd\"/>")
        append("<numFmt numFmtId=\"").append(NUM_FMT_TIME).append("\" formatCode=\"hh:mm\"/>")
        append("<numFmt numFmtId=\"").append(NUM_FMT_DATE_TIME).append("\" formatCode=\"yyyy-mm-dd hh:mm\"/>")
        append("</numFmts>")
        append("<fonts count=\"2\">")
        append("<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
        append("<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
        append("</fonts>")
        append("<fills count=\"3\">")
        append("<fill><patternFill patternType=\"none\"/></fill>")
        append("<fill><patternFill patternType=\"gray125\"/></fill>")
        append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCE6F1\"/><bgColor indexed=\"64\"/></patternFill></fill>")
        append("</fills>")
        append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
        append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        append("<cellXfs count=\"6\">")
        // 0: default
        append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        // 1: bold header on a light fill
        append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>")
        // 2: date
        append("<xf numFmtId=\"").append(NUM_FMT_DATE).append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        // 3: time
        append("<xf numFmtId=\"").append(NUM_FMT_TIME).append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        // 4: date-time
        append("<xf numFmtId=\"").append(NUM_FMT_DATE_TIME).append("\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>")
        // 5: wrapped text
        append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\">")
        append("<alignment wrapText=\"1\" vertical=\"top\"/></xf>")
        append("</cellXfs>")
        append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        append("</styleSheet>")
    }

    private fun worksheet(sheet: XSheet, extent: Extent, selected: Boolean): String = buildString {
        append(XML_DECLARATION)
        append("<worksheet xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_DOC_RELS).append("\">")
        append("<dimension ref=\"").append(extent.ref).append("\"/>")
        append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (selected) append(" tabSelected=\"1\"")
        append(">")
        append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>")
        append("</sheetView></sheetViews>")
        append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        val widths = sheet.columnWidths
        if (!widths.isNullOrEmpty()) {
            append("<cols>")
            widths.forEachIndexed { index, width ->
                val safeWidth = width.takeIf { it.isFinite() }?.coerceIn(1.0, 255.0) ?: 10.0
                append("<col min=\"").append(index + 1).append("\" max=\"").append(index + 1)
                append("\" width=\"").append(numberText(safeWidth)).append("\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        append("<row r=\"1\">")
        sheet.header.forEachIndexed { column, text ->
            appendInlineString(this, cellRef(column, 1), text, STYLE_HEADER)
        }
        append("</row>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 2
            append("<row r=\"").append(rowNumber).append("\">")
            row.forEachIndexed { column, cell -> appendCell(this, cellRef(column, rowNumber), cell) }
            append("</row>")
        }
        append("</sheetData>")
        if (extent.hasColumns) append("<autoFilter ref=\"").append(extent.ref).append("\"/>")
        append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        append("</worksheet>")
    }

    // ---- cells ----

    private fun appendCell(sb: StringBuilder, ref: String, cell: XCell) {
        when (cell) {
            is XCell.Blank -> {}
            is XCell.Text -> {
                if (cell.value.isNotEmpty()) {
                    appendInlineString(sb, ref, cell.value, if (cell.wrap) STYLE_WRAP else STYLE_DEFAULT)
                }
            }
            is XCell.Number -> {
                val text = numberText(cell.value) ?: return
                sb.append("<c r=\"").append(ref).append("\"><v>").append(text).append("</v></c>")
            }
            is XCell.DateTime -> {
                val style = when (cell.style) {
                    DateStyle.DATE -> STYLE_DATE
                    DateStyle.TIME -> STYLE_TIME
                    DateStyle.DATE_TIME -> STYLE_DATE_TIME
                }
                val serial = excelSerial(cell.epochMs, cell.zoneId)
                // A date-only cell holds the whole-day serial so it compares equal to DATE() and
                // groups cleanly in filters; time and date-time cells keep the time of day.
                val value = if (cell.style == DateStyle.DATE) Math.floor(serial) else serial
                val text = numberText(value) ?: return
                sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>").append(text).append("</v></c>")
            }
        }
    }

    private fun appendInlineString(sb: StringBuilder, ref: String, text: String, style: Int) {
        sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"")
        if (style != STYLE_DEFAULT) sb.append(" s=\"").append(style).append("\"")
        sb.append("><is><t xml:space=\"preserve\">").append(escapeXml(text, attribute = false)).append("</t></is></c>")
    }

    /** "A1"-style reference for a 0-based column and a 1-based row. */
    private fun cellRef(column: Int, row: Int): String = columnName(column) + row

    /** 0 -> "A", 25 -> "Z", 26 -> "AA". */
    private fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            sb.append('A' + remainder)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Plain decimal text for a `<v>` element; null for values Excel cannot store. */
    private fun numberText(value: Double): String? {
        if (value.isNaN() || value.isInfinite()) return null
        if (value == 0.0) return "0"
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    /**
     * Escapes the XML special characters and drops control characters other than tab, LF and CR
     * (and the non-characters U+FFFE / U+FFFF), which are not allowed in XML 1.0 at all.
     */
    private fun escapeXml(text: String, attribute: Boolean): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' && attribute -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' || c == '￾' || c == '￿' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---- sheet names ----

    /** Sanitises every name to Excel's rules and appends " (2)", " (3)"... to case-insensitive duplicates. */
    private fun uniqueSheetNames(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.mapIndexed { index, raw ->
            val base = sanitizeSheetName(raw, fallback = "Sheet${index + 1}")
            var candidate = base
            var n = 2
            while (candidate.lowercase() in used) {
                val suffix = " ($n)"
                candidate = base.take(MAX_SHEET_NAME_LENGTH - suffix.length).trimEnd() + suffix
                n++
            }
            used.add(candidate.lowercase())
            candidate
        }
    }

    private fun sanitizeSheetName(raw: String, fallback: String): String {
        val cleaned = raw
            .map { c -> if (c in FORBIDDEN_SHEET_NAME_CHARS || c < ' ') ' ' else c }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('\'')
            .trim()
        val shortened = if (cleaned.length > MAX_SHEET_NAME_LENGTH) cleaned.take(MAX_SHEET_NAME_LENGTH).trim() else cleaned
        return if (shortened.isEmpty() || shortened.equals("History", ignoreCase = true)) fallback else shortened
    }

    /** `'My sheet'` with embedded apostrophes doubled, as used in formulas and defined names. */
    private fun quoteSheetName(name: String): String = "'" + name.replace("'", "''") + "'"

    /** The used range of a sheet: the header row plus every data row. */
    private class Extent(val columnCount: Int, val lastRow: Int) {
        val hasColumns: Boolean get() = columnCount > 0
        private val lastColumn: String get() = columnName(maxOf(columnCount, 1) - 1)
        /** "A1:D10" (or "A1" for a sheet without columns). */
        val ref: String get() = if (hasColumns) "A1:$lastColumn$lastRow" else "A1"
        /** "$A$1:$D$10" for the filter defined name. */
        val absoluteRef: String get() = "\$A\$1:\$$lastColumn\$$lastRow"

        companion object {
            fun of(sheet: XSheet): Extent = Extent(
                columnCount = maxOf(sheet.header.size, sheet.rows.maxOfOrNull { it.size } ?: 0),
                lastRow = sheet.rows.size + 1,
            )
        }
    }
}
