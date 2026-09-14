package com.exertrack.app.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxWriterTest {

    private val zone = "America/Chicago"

    private val requiredParts = listOf(
        "[Content_Types].xml",
        "_rels/.rels",
        "docProps/app.xml",
        "docProps/core.xml",
        "xl/workbook.xml",
        "xl/_rels/workbook.xml.rels",
        "xl/styles.xml",
        "xl/worksheets/sheet1.xml",
    )

    // ---- helpers ----

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, zoneId: String = zone): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.of(zoneId)).toInstant().toEpochMilli()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return parts
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun Document.elements(localName: String): List<Element> {
        val nodes = getElementsByTagName(localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.children(localName: String): List<Element> {
        val nodes = getElementsByTagName(localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Attribute value, or null when the attribute is absent. */
    private fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

    private fun Element.cells(): List<Element> = children("c")

    private fun Element.cell(ref: String): Element? = cells().firstOrNull { it.getAttribute("r") == ref }

    private fun Element.inlineText(): String = children("t").single().textContent

    private fun Element.value(): String = children("v").single().textContent

    private fun sheetRow(sheet: Document, number: Int): Element =
        sheet.elements("row").single { it.getAttribute("r") == number.toString() }

    private fun sampleSheet(): XSheet = XSheet(
        name = "Log",
        header = listOf("date", "start", "count", "notes"),
        rows = listOf(
            listOf(
                // 06:00 local: a DATE cell must still hold the whole-day serial
                XCell.DateTime(epochMs(2026, 9, 14, 6, 0), zone, DateStyle.DATE),
                XCell.DateTime(epochMs(2026, 9, 14, 6, 0), zone, DateStyle.TIME),
                XCell.Number(3.0),
                XCell.Text("a < b & \"c\"\nline2", wrap = true),
            ),
            listOf(
                XCell.DateTime(epochMs(2026, 9, 14, 18, 0), zone, DateStyle.DATE_TIME),
                XCell.Blank,
                XCell.Number(1.5),
                XCell.Text("plain"),
            ),
        ),
        columnWidths = listOf(12.0, 10.0, 8.0, 40.0),
    )

    // ---- tests ----

    @Test
    fun requiredPartsExistAndEveryXmlPartIsWellFormed() {
        val parts = unzip(XlsxWriter.write(listOf(sampleSheet())))
        for (name in requiredParts) assertTrue("missing part $name", parts.containsKey(name))
        for ((name, bytes) in parts) {
            if (name.endsWith(".xml") || name.endsWith(".rels")) {
                val doc = parse(bytes)
                assertNotNull("no root element in $name", doc.documentElement)
            }
        }
        assertFalse("inline strings make sharedStrings unnecessary", parts.containsKey("xl/sharedStrings.xml"))
    }

    @Test
    fun contentTypesAndRelationshipsCoverEverySheet() {
        val sheets = listOf(sampleSheet(), sampleSheet().copy(name = "Second"))
        val parts = unzip(XlsxWriter.write(sheets))
        assertTrue(parts.containsKey("xl/worksheets/sheet2.xml"))

        val types = parse(parts.getValue("[Content_Types].xml"))
        val overrides = types.elements("Override").map { it.getAttribute("PartName") }
        assertTrue(overrides.contains("/xl/worksheets/sheet1.xml"))
        assertTrue(overrides.contains("/xl/worksheets/sheet2.xml"))
        assertTrue(overrides.contains("/xl/styles.xml"))
        assertTrue(overrides.contains("/xl/workbook.xml"))

        val rels = parse(parts.getValue("xl/_rels/workbook.xml.rels"))
        val targets = rels.elements("Relationship").map { it.getAttribute("Target") }
        assertTrue(targets.contains("worksheets/sheet1.xml"))
        assertTrue(targets.contains("worksheets/sheet2.xml"))
        assertTrue(targets.contains("styles.xml"))

        val workbook = parse(parts.getValue("xl/workbook.xml"))
        val sheetElements = workbook.elements("sheet")
        assertEquals(listOf("Log", "Second"), sheetElements.map { it.getAttribute("name") })
        assertEquals(listOf("rId1", "rId2"), sheetElements.map { it.getAttributeNS(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") })
    }

    @Test
    fun headerRowUsesTheBoldFilledStyleWithInlineStrings() {
        val parts = unzip(XlsxWriter.write(listOf(sampleSheet())))
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))
        val header = sheetRow(sheet, 1).cells()
        assertEquals(listOf("A1", "B1", "C1", "D1"), header.map { it.getAttribute("r") })
        assertEquals(listOf("date", "start", "count", "notes"), header.map { it.inlineText() })
        for (cell in header) {
            assertEquals("inlineStr", cell.getAttribute("t"))
            assertEquals(XlsxWriter.STYLE_HEADER.toString(), cell.getAttribute("s"))
        }

        val styles = parse(parts.getValue("xl/styles.xml"))
        val xfs = styles.elements("cellXfs").single().children("xf")
        assertEquals(6, xfs.size)
        val headerXf = xfs[XlsxWriter.STYLE_HEADER]
        assertEquals("1", headerXf.getAttribute("fontId"))
        assertEquals("2", headerXf.getAttribute("fillId"))
        val fonts = styles.elements("fonts").single().children("font")
        assertEquals(2, fonts.size)
        assertTrue("second font must be bold", fonts[1].children("b").isNotEmpty())
        val fills = styles.elements("fills").single().children("fill")
        assertEquals(3, fills.size)
        assertEquals("solid", fills[2].children("patternFill").single().getAttribute("patternType"))
        assertEquals(1, styles.elements("borders").single().children("border").size)
    }

    @Test
    fun stylesDefineDateTimeAndWrapFormats() {
        val parts = unzip(XlsxWriter.write(listOf(sampleSheet())))
        val styles = parse(parts.getValue("xl/styles.xml"))
        val numFmts = styles.elements("numFmt")
        assertEquals(3, numFmts.size)
        for (fmt in numFmts) assertTrue(fmt.getAttribute("numFmtId").toInt() >= 164)
        val codes = numFmts.associate { it.getAttribute("numFmtId") to it.getAttribute("formatCode") }

        val xfs = styles.elements("cellXfs").single().children("xf")
        assertEquals("yyyy-mm-dd", codes[xfs[XlsxWriter.STYLE_DATE].getAttribute("numFmtId")])
        assertEquals("hh:mm", codes[xfs[XlsxWriter.STYLE_TIME].getAttribute("numFmtId")])
        assertEquals("yyyy-mm-dd hh:mm", codes[xfs[XlsxWriter.STYLE_DATE_TIME].getAttribute("numFmtId")])
        val wrapAlignment = xfs[XlsxWriter.STYLE_WRAP].children("alignment").single()
        assertEquals("1", wrapAlignment.getAttribute("wrapText"))
        assertEquals("top", wrapAlignment.getAttribute("vertical"))
    }

    @Test
    fun topRowIsFrozenAndFilteredWithColumnWidths() {
        val parts = unzip(XlsxWriter.write(listOf(sampleSheet())))
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))

        val pane = sheet.elements("pane").single()
        assertEquals("1", pane.getAttribute("ySplit"))
        assertEquals("A2", pane.getAttribute("topLeftCell"))
        assertEquals("bottomLeft", pane.getAttribute("activePane"))
        assertEquals("frozen", pane.getAttribute("state"))

        assertEquals("A1:D3", sheet.elements("dimension").single().getAttribute("ref"))
        assertEquals("A1:D3", sheet.elements("autoFilter").single().getAttribute("ref"))

        val cols = sheet.elements("col")
        assertEquals(4, cols.size)
        assertEquals(listOf("12", "10", "8", "40"), cols.map { it.getAttribute("width") })
        for (col in cols) assertEquals("1", col.getAttribute("customWidth"))
        assertEquals(listOf("1", "2", "3", "4"), cols.map { it.getAttribute("min") })

        // autoFilter must follow sheetData, and both must precede pageMargins
        val root = sheet.documentElement
        val order = (0 until root.childNodes.length).map { root.childNodes.item(it).localName }
        assertTrue(order.indexOf("sheetData") < order.indexOf("autoFilter"))
        assertTrue(order.indexOf("autoFilter") < order.indexOf("pageMargins"))
        assertTrue(order.indexOf("cols") < order.indexOf("sheetData"))
        assertTrue(order.indexOf("sheetViews") < order.indexOf("cols"))

        val workbook = parse(parts.getValue("xl/workbook.xml"))
        val filterName = workbook.elements("definedName").single()
        assertEquals("_xlnm._FilterDatabase", filterName.getAttribute("name"))
        assertEquals("0", filterName.getAttribute("localSheetId"))
        assertEquals("'Log'!\$A\$1:\$D\$3", filterName.textContent)
    }

    @Test
    fun cellsCarryDateStylesSerialsNumbersAndEscapedText() {
        val parts = unzip(XlsxWriter.write(listOf(sampleSheet())))
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))

        val row2 = sheetRow(sheet, 2)
        assertEquals(4, row2.cells().size)
        val date = row2.cell("A2")!!
        assertEquals(XlsxWriter.STYLE_DATE.toString(), date.getAttribute("s"))
        assertEquals("46279", date.value())
        assertNull(date.attr("t"))
        val time = row2.cell("B2")!!
        assertEquals(XlsxWriter.STYLE_TIME.toString(), time.getAttribute("s"))
        assertEquals(46279.25, time.value().toDouble(), 1e-9)
        val count = row2.cell("C2")!!
        assertNull("numbers use the default style", count.attr("s"))
        assertEquals("3", count.value())
        val notes = row2.cell("D2")!!
        assertEquals("inlineStr", notes.getAttribute("t"))
        assertEquals(XlsxWriter.STYLE_WRAP.toString(), notes.getAttribute("s"))
        assertEquals("control characters are stripped, markup is escaped", "a < b & \"c\"\nline2", notes.inlineText())

        val row3 = sheetRow(sheet, 3)
        assertEquals("blank cells are omitted", listOf("A3", "C3", "D3"), row3.cells().map { it.getAttribute("r") })
        assertEquals(XlsxWriter.STYLE_DATE_TIME.toString(), row3.cell("A3")!!.getAttribute("s"))
        assertEquals(46279.75, row3.cell("A3")!!.value().toDouble(), 1e-9)
        assertEquals("1.5", row3.cell("C3")!!.value())
        assertNull(row3.cell("D3")!!.attr("s"))
        assertEquals("plain", row3.cell("D3")!!.inlineText())

        // the raw XML must not contain unescaped markup from cell text
        val raw = String(parts.getValue("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8)
        assertTrue(raw.contains("a &lt; b &amp; \"c\""))
        assertFalse(raw.contains(""))
    }

    @Test
    fun excelSerialMatchesExcel() {
        assertEquals(46279.0, XlsxWriter.excelSerial(epochMs(2026, 9, 14, 0, 0), zone), 0.0)
        assertEquals(61.0, XlsxWriter.excelSerial(epochMs(1900, 3, 1, 0, 0, "UTC"), "UTC"), 0.0)
        assertEquals(46279.5, XlsxWriter.excelSerial(epochMs(2026, 9, 14, 12, 0), zone), 1e-9)
        // the same instant is 14 hours later in Tokyo than in Chicago (CDT)
        val chicagoMidnight = epochMs(2026, 9, 14, 0, 0)
        assertEquals(46279.0 + 14.0 / 24.0, XlsxWriter.excelSerial(chicagoMidnight, "Asia/Tokyo"), 1e-9)
        // seconds contribute to the fraction
        val withSeconds = LocalDateTime.of(2026, 9, 14, 0, 0, 30).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        assertEquals(46279.0 + 30.0 / 86400.0, XlsxWriter.excelSerial(withSeconds, zone), 1e-9)
    }

    @Test
    fun sheetNamesAreSanitisedTruncatedAndUnique() {
        val names = listOf("My: log/2026?", "My  log 2026", "x".repeat(40), "x".repeat(40), "", "[Bracketed]\\Name")
        val sheets = names.map { XSheet(name = it, header = listOf("a"), rows = emptyList()) }
        val parts = unzip(XlsxWriter.write(sheets))
        val workbook = parse(parts.getValue("xl/workbook.xml"))
        val actual = workbook.elements("sheet").map { it.getAttribute("name") }
        assertEquals(
            listOf("My log 2026", "My log 2026 (2)", "x".repeat(31), "x".repeat(27) + " (2)", "Sheet5", "Bracketed Name"),
            actual,
        )
        for (name in actual) assertTrue(name.length <= 31)
        assertEquals(6, workbook.elements("definedName").size)
        for (i in 1..6) assertTrue(parts.containsKey("xl/worksheets/sheet$i.xml"))
    }

    @Test
    fun emptyInputStillProducesOneValidSheet() {
        val parts = unzip(XlsxWriter.write(emptyList()))
        for (name in requiredParts) assertTrue("missing part $name", parts.containsKey(name))
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))
        assertEquals("A1", sheet.elements("dimension").single().getAttribute("ref"))
        assertTrue(sheet.elements("autoFilter").isEmpty())
        assertEquals(1, sheet.elements("pane").size)
        val workbook = parse(parts.getValue("xl/workbook.xml"))
        assertEquals("Sheet1", workbook.elements("sheet").single().getAttribute("name"))
        assertTrue(workbook.elements("definedName").isEmpty())
    }

    @Test
    fun wideSheetsUseTwoLetterColumns() {
        val header = (1..28).map { "c$it" }
        val parts = unzip(XlsxWriter.write(listOf(XSheet("Wide", header, listOf(header.map { XCell.Text(it) })))))
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))
        assertEquals("A1:AB2", sheet.elements("autoFilter").single().getAttribute("ref"))
        val refs = sheetRow(sheet, 2).cells().map { it.getAttribute("r") }
        assertEquals("Z2", refs[25])
        assertEquals("AA2", refs[26])
        assertEquals("AB2", refs[27])
    }
}
