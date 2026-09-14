package com.exertrack.app.io

import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * Minimal .xlsx reader (no third-party libraries): unzips the package, reads
 * xl/workbook.xml + xl/_rels/workbook.xml.rels for sheet names and order, xl/sharedStrings.xml,
 * and each worksheet's sheetData. Supports shared strings (t="s"), inline strings (t="inlineStr"),
 * plain numbers, booleans (t="b"), and the cached value of formula cells. Numbers are returned as
 * the text stored in the file (e.g. "40", "0.0625", "1.5E-2" normalised to plain decimal).
 * Sparse rows/cells are filled with "" so every row has cells up to its last populated column.
 */
object XlsxReader {
    private const val DEFAULT_WORKBOOK = "xl/workbook.xml"
    private const val PACKAGE_RELS = "_rels/.rels"

    /** True when the bytes start with the zip signature "PK". */
    fun isXlsx(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    /** All sheets in workbook order. Throws IllegalArgumentException for a file that is not a spreadsheet. */
    fun read(bytes: ByteArray): List<SheetTable> {
        val entries = readEntries(bytes)
        val workbookPath = locateWorkbook(entries)
        val workbookXml = entries.lookup(workbookPath)
            ?: throw IllegalArgumentException("Not a spreadsheet: workbook.xml is missing")
        val workbookDir = workbookPath.substringBeforeLast('/', "")
        val workbookFile = workbookPath.substringAfterLast('/')
        val relationships = entries.lookup(joinPath(workbookDir, "_rels/$workbookFile.rels"))
            ?.let { parseRelationships(it) }
            ?: emptyMap()

        val sharedStringsPath = relationships.values
            .firstOrNull { it.type.endsWith("/sharedStrings") }
            ?.let { resolvePath(workbookDir, it.target) }
            ?: joinPath(workbookDir, "sharedStrings.xml")
        val sharedStrings = entries.lookup(sharedStringsPath)?.let { parseSharedStrings(it) } ?: emptyList()

        val sheets = parseWorkbook(workbookXml)
        return sheets.mapIndexed { index, sheet ->
            val target = relationships[sheet.relId]?.let { resolvePath(workbookDir, it.target) }
                ?: joinPath(workbookDir, "worksheets/sheet${index + 1}.xml")
            val worksheetXml = entries.lookup(target)
            val rows = if (worksheetXml == null) emptyList() else parseWorksheet(worksheetXml, sharedStrings)
            SheetTable(sheet.name, rows)
        }
    }

    // ---- package -------------------------------------------------------------------------

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        if (isPartOfInterest(name)) entries[name] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: ZipException) {
            throw IllegalArgumentException("Not a spreadsheet: damaged zip package", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("Not a spreadsheet: unreadable zip package", e)
        }
        if (entries.isEmpty()) throw IllegalArgumentException("Not a spreadsheet: workbook.xml is missing")
        return entries
    }

    private fun isPartOfInterest(name: String): Boolean =
        name.endsWith(".xml", ignoreCase = true) || name.endsWith(".rels", ignoreCase = true)

    /** Exact-name lookup with a case-insensitive fallback, since some writers vary the case of part names. */
    private fun Map<String, ByteArray>.lookup(path: String): ByteArray? {
        this[path]?.let { return it }
        val key = keys.firstOrNull { it.equals(path, ignoreCase = true) } ?: return null
        return this[key]
    }

    /** The workbook part named by the package relationships, or the conventional xl/workbook.xml. */
    private fun locateWorkbook(entries: Map<String, ByteArray>): String {
        val packageRels = entries.lookup(PACKAGE_RELS)?.let { parseRelationships(it) }
        val fromRels = packageRels?.values
            ?.firstOrNull { it.type.endsWith("/officeDocument") }
            ?.let { resolvePath("", it.target) }
        if (fromRels != null && entries.lookup(fromRels) != null) return fromRels
        return DEFAULT_WORKBOOK
    }

    private fun joinPath(dir: String, file: String): String = if (dir.isEmpty()) file else "$dir/$file"

    /**
     * Resolves a relationship target against the directory of the part that owns the
     * relationships: absolute targets ("/xl/worksheets/sheet1.xml") are taken from the package
     * root, relative ones ("worksheets/sheet1.xml", "../foo.xml") from [baseDir].
     */
    private fun resolvePath(baseDir: String, target: String): String {
        val cleaned = target.replace('\\', '/')
        val segments = ArrayList<String>()
        if (!cleaned.startsWith("/") && baseDir.isNotEmpty()) {
            segments.addAll(baseDir.split('/').filter { it.isNotEmpty() })
        }
        for (segment in cleaned.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    // ---- XML helpers ---------------------------------------------------------------------

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        trySetFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        try {
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
        } catch (e: SAXException) {
            throw IllegalArgumentException("Not a spreadsheet: malformed XML part", e)
        } catch (e: ParserConfigurationException) {
            throw IllegalArgumentException("XML parser unavailable", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("Not a spreadsheet: unreadable XML part", e)
        }
    }

    private fun trySetFeature(factory: SAXParserFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (e: Exception) {
            // The feature is not known to this parser; nothing else to do.
        }
    }

    /** Local element name whether or not the parser reported namespaces. */
    private fun local(localName: String, qName: String): String =
        if (localName.isNotEmpty()) localName else qName.substringAfter(':')

    /** Attribute value by local name, ignoring any namespace prefix such as r:id. */
    private fun attr(attributes: Attributes, name: String): String? {
        for (i in 0 until attributes.length) {
            val localName = attributes.getLocalName(i)
            val qName = attributes.getQName(i)
            if (localName == name || qName == name || qName.endsWith(":$name")) return attributes.getValue(i)
        }
        return null
    }

    // ---- relationships -------------------------------------------------------------------

    private class Relationship(val type: String, val target: String)

    private fun parseRelationships(bytes: ByteArray): Map<String, Relationship> {
        val result = LinkedHashMap<String, Relationship>()
        parseXml(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (local(localName, qName) != "Relationship") return
                val id = attr(attributes, "Id") ?: return
                val target = attr(attributes, "Target") ?: return
                result[id] = Relationship(attr(attributes, "Type") ?: "", target)
            }
        })
        return result
    }

    // ---- workbook ------------------------------------------------------------------------

    private class SheetRef(val name: String, val relId: String?)

    private fun parseWorkbook(bytes: ByteArray): List<SheetRef> {
        val sheets = ArrayList<SheetRef>()
        parseXml(bytes, object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (local(localName, qName) != "sheet") return
                val name = attr(attributes, "name") ?: "Sheet${sheets.size + 1}"
                sheets.add(SheetRef(name, attr(attributes, "id")))
            }
        })
        return sheets
    }

    // ---- shared strings ------------------------------------------------------------------

    /** Every `<si>` as one string: plain `<t>` and rich-text `<r><t>` runs concatenated; phonetic runs skipped. */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val strings = ArrayList<String>()
        parseXml(bytes, object : DefaultHandler() {
            private val current = StringBuilder()
            private var inItem = false
            private var inText = false
            private var phoneticDepth = 0

            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                when (local(localName, qName)) {
                    "si" -> {
                        inItem = true
                        current.setLength(0)
                    }
                    "rPh" -> phoneticDepth++
                    "t" -> if (inItem && phoneticDepth == 0) inText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) current.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when (local(localName, qName)) {
                    "t" -> inText = false
                    "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                    "si" -> {
                        strings.add(current.toString())
                        inItem = false
                    }
                }
            }
        })
        return strings
    }

    // ---- worksheet -----------------------------------------------------------------------

    private fun parseWorksheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        parseXml(bytes, object : DefaultHandler() {
            private var inSheetData = false
            private var cells: ArrayList<String>? = null
            private var nextColumn = 0
            private var cellColumn = 0
            private var cellType = ""
            private val value = StringBuilder()
            private val inlineText = StringBuilder()
            private var inValue = false
            private var inInline = false
            private var inInlineText = false
            private var phoneticDepth = 0

            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                when (local(localName, qName)) {
                    "sheetData" -> inSheetData = true
                    "row" -> if (inSheetData) startRow(attributes)
                    "c" -> if (cells != null) startCell(attributes)
                    "v" -> if (cells != null) {
                        inValue = true
                        value.setLength(0)
                    }
                    "is" -> if (cells != null) inInline = true
                    "rPh" -> phoneticDepth++
                    "t" -> if (inInline && phoneticDepth == 0) inInlineText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue) value.appendRange(ch, start, start + length)
                else if (inInlineText) inlineText.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String, localName: String, qName: String) {
                when (local(localName, qName)) {
                    "sheetData" -> inSheetData = false
                    "row" -> endRow()
                    "c" -> endCell()
                    "v" -> inValue = false
                    "is" -> inInline = false
                    "t" -> inInlineText = false
                    "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                }
            }

            private fun startRow(attributes: Attributes) {
                val declared = attr(attributes, "r")?.toIntOrNull()
                val rowIndex = if (declared != null && declared >= 1) declared - 1 else rows.size
                while (rows.size < rowIndex) rows.add(emptyList())
                cells = ArrayList()
                nextColumn = 0
            }

            private fun endRow() {
                val row = cells ?: return
                cells = null
                var end = row.size
                while (end > 0 && row[end - 1].isEmpty()) end--
                rows.add(if (end == row.size) row else ArrayList(row.subList(0, end)))
            }

            private fun startCell(attributes: Attributes) {
                cellColumn = attr(attributes, "r")?.let { columnIndex(it) } ?: nextColumn
                cellType = attr(attributes, "t") ?: ""
                value.setLength(0)
                inlineText.setLength(0)
                inValue = false
                inInline = false
                inInlineText = false
            }

            private fun endCell() {
                val row = cells ?: return
                val text = cellText()
                while (row.size < cellColumn) row.add("")
                if (row.size == cellColumn) row.add(text) else row[cellColumn] = text
                nextColumn = cellColumn + 1
            }

            private fun cellText(): String = when (cellType) {
                "s" -> value.toString().trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                "inlineStr" -> inlineText.toString()
                "b" -> if (value.toString().trim() == "1") "TRUE" else "FALSE"
                "str", "e", "d" -> value.toString()
                else -> normalizeNumber(value.toString())
            }
        })
        return rows
    }

    /** 0-based column index of a cell reference such as "C7" (2) or "AA1" (26); null when it has no letters. */
    internal fun columnIndex(reference: String): Int? {
        var index = 0
        var letters = 0
        for (ch in reference) {
            val upper = ch.uppercaseChar()
            if (upper !in 'A'..'Z') break
            index = index * 26 + (upper - 'A' + 1)
            letters++
        }
        return if (letters == 0) null else index - 1
    }

    /** "4.0E1" and "40.0" become "40"; "0.0625" stays as it is; anything that is not a number is returned unchanged. */
    internal fun normalizeNumber(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        return try {
            val number = BigDecimal(trimmed).stripTrailingZeros()
            if (number.signum() == 0) "0" else number.toPlainString()
        } catch (e: NumberFormatException) {
            trimmed
        }
    }
}
