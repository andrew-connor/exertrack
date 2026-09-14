package com.exertrack.app.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    private val mainNs = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val packageRelNs = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val relType = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="$mainNs" xmlns:r="$relNs">
          <sheets>
            <sheet name="Upper body" sheetId="1" r:id="rId2"/>
            <sheet name="Legs" sheetId="2" r:id="rId1"/>
          </sheets>
        </workbook>"""

    /** rId1 uses an absolute target, rId2 a relative one; the order differs from the workbook order on purpose. */
    private val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="$packageRelNs">
          <Relationship Id="rId1" Type="$relType/worksheet" Target="/xl/worksheets/sheet2.xml"/>
          <Relationship Id="rId2" Type="$relType/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId3" Type="$relType/sharedStrings" Target="sharedStrings.xml"/>
        </Relationships>"""

    private val sharedStringsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sst xmlns="$mainNs" count="3" uniqueCount="3">
          <si><t>Exercise</t></si>
          <si><r><rPr><b/></rPr><t>Push</t></r><r><t xml:space="preserve">-ups</t></r></si>
          <si><t>Keep it tight</t><rPh sb="0" eb="1"><t>ignored</t></rPh></si>
        </sst>"""

    private val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="$mainNs" xmlns:r="$relNs">
          <dimension ref="A1:E4"/>
          <sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Duration (s)</t></is></c></row>
            <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>4.0E1</v></c><c r="C2"><v>20.0</v></c><c r="D2" t="s"><v>2</v></c></row>
            <row r="3"><c r="A3" t="inlineStr"><is><t>Plank</t></is></c><c r="B3"><v>0.0625</v></c><c r="E3" t="b"><v>1</v></c></row>
            <row r="4"><c r="A4" t="str"><f>CONCATENATE("Re","st")</f><v>Rest</v></c><c r="B4"><f>B2/2</f><v>20</v></c><c r="C4" s="1"/><c r="D4" t="b"><v>0</v></c><c r="E4" s="1"/></row>
          </sheetData>
        </worksheet>"""

    /** Row 2 is missing entirely and cells carry no references. */
    private val sheet2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="$mainNs">
          <sheetData>
            <row r="1"><c t="inlineStr"><is><t>Squats</t></is></c><c><v>45</v></c></row>
            <row r="3"><c t="inlineStr"><is><t>Lunges</t></is></c><c><v>1.5E-2</v></c></row>
          </sheetData>
        </worksheet>"""

    private fun workbookBytes(): ByteArray = zip(
        mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="$packageRelNs"><Relationship Id="rId1" Type="$relType/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to workbookXml,
            "xl/_rels/workbook.xml.rels" to workbookRels,
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml,
            "xl/worksheets/sheet2.xml" to sheet2Xml,
        ),
    )

    @Test
    fun isXlsxChecksZipSignature() {
        assertTrue(XlsxReader.isXlsx(workbookBytes()))
        assertFalse(XlsxReader.isXlsx("Push-ups,40\n".toByteArray()))
        assertFalse(XlsxReader.isXlsx(byteArrayOf(0x50, 0x4B)))
        assertFalse(XlsxReader.isXlsx(ByteArray(0)))
    }

    @Test
    fun sheetsComeInWorkbookOrderWithNames() {
        val sheets = XlsxReader.read(workbookBytes())
        assertEquals(listOf("Upper body", "Legs"), sheets.map { it.name })
    }

    @Test
    fun sharedInlineFormulaBooleanAndNumberCells() {
        val sheet = XlsxReader.read(workbookBytes())[0]
        assertEquals(listOf("Exercise", "Duration (s)"), sheet.rows[0])
        assertEquals(listOf("Push-ups", "40", "20", "Keep it tight"), sheet.rows[1])
        assertEquals(listOf("Rest", "20", "", "FALSE"), sheet.rows[3])
    }

    @Test
    fun sparseRowFillsMissingCells() {
        val sheet = XlsxReader.read(workbookBytes())[0]
        assertEquals(listOf("Plank", "0.0625", "", "", "TRUE"), sheet.rows[2])
    }

    @Test
    fun missingRowsBecomeEmptyAndUnreferencedCellsAreSequential() {
        val sheet = XlsxReader.read(workbookBytes())[1]
        assertEquals(3, sheet.rows.size)
        assertEquals(listOf("Squats", "45"), sheet.rows[0])
        assertEquals(emptyList<String>(), sheet.rows[1])
        assertEquals(listOf("Lunges", "0.015"), sheet.rows[2])
    }

    @Test
    fun worksheetsResolveWithoutPackageRels() {
        val bytes = zip(
            mapOf(
                "xl/workbook.xml" to workbookXml,
                "xl/_rels/workbook.xml.rels" to workbookRels,
                "xl/sharedStrings.xml" to sharedStringsXml,
                "xl/worksheets/sheet1.xml" to sheet1Xml,
                "xl/worksheets/sheet2.xml" to sheet2Xml,
            ),
        )
        val sheets = XlsxReader.read(bytes)
        assertEquals(listOf("Upper body", "Legs"), sheets.map { it.name })
        assertEquals("Push-ups", sheets[0].rows[1][0])
    }

    @Test
    fun missingSharedStringsGivesEmptyCells() {
        val bytes = zip(
            mapOf(
                "xl/workbook.xml" to workbookXml,
                "xl/_rels/workbook.xml.rels" to workbookRels,
                "xl/worksheets/sheet1.xml" to sheet1Xml,
                "xl/worksheets/sheet2.xml" to sheet2Xml,
            ),
        )
        val sheet = XlsxReader.read(bytes)[0]
        assertEquals(listOf("", "Duration (s)"), sheet.rows[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun zipWithoutWorkbookIsRejected() {
        XlsxReader.read(zip(mapOf("word/document.xml" to "<document/>")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun garbageIsRejected() {
        XlsxReader.read(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test
    fun numberNormalisation() {
        assertEquals("40", XlsxReader.normalizeNumber("4.0E1"))
        assertEquals("40", XlsxReader.normalizeNumber("40.0"))
        assertEquals("40", XlsxReader.normalizeNumber("40"))
        assertEquals("0.0625", XlsxReader.normalizeNumber("0.0625"))
        assertEquals("0.015", XlsxReader.normalizeNumber("1.5E-2"))
        assertEquals("0", XlsxReader.normalizeNumber("0.000"))
        assertEquals("-2.5", XlsxReader.normalizeNumber("-2.50"))
        assertEquals("1000000", XlsxReader.normalizeNumber("1E6"))
        assertEquals("#N/A", XlsxReader.normalizeNumber("#N/A"))
        assertEquals("", XlsxReader.normalizeNumber(""))
    }

    @Test
    fun columnIndexFromReference() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(2, XlsxReader.columnIndex("C7"))
        assertEquals(25, XlsxReader.columnIndex("Z10"))
        assertEquals(26, XlsxReader.columnIndex("AA1"))
        assertEquals(27, XlsxReader.columnIndex("ab3"))
        assertEquals(null, XlsxReader.columnIndex("12"))
    }
}
