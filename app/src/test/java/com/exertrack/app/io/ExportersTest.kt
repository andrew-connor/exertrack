package com.exertrack.app.io

import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.SessionStatus
import com.exertrack.app.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

class ExportersTest {

    private val zone = "America/Chicago"

    private val expectedLogHeader =
        "date,start_time,end_time,elapsed_min,paused_min,workout,completed,total,rounds,status,notes,session_id,workout_id"

    // ---- fixtures ----

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun completedEntry(notes: String = "Solid session"): LogEntry {
        val started = epochMs(2026, 9, 14, 6, 30)
        return LogEntry(
            id = "sess-1",
            workoutId = "wk-1",
            workoutName = "Morning circuit",
            startedAt = started,
            finishedAt = started + 1470 * 1000L,
            zoneId = zone,
            elapsedSec = 1470,
            pausedSec = 90,
            completedSteps = 14,
            totalSteps = 14,
            rounds = 2,
            rowCount = 7,
            status = SessionStatus.COMPLETED,
            notes = notes,
        )
    }

    private fun inProgressEntry(): LogEntry = LogEntry(
        id = "sess-2",
        workoutId = "wk-1",
        workoutName = "Morning circuit",
        startedAt = epochMs(2026, 9, 14, 8, 0),
        finishedAt = null,
        zoneId = zone,
        elapsedSec = 0,
        pausedSec = 0,
        completedSteps = 3,
        totalSteps = 14,
        rounds = 2,
        rowCount = 7,
        status = SessionStatus.IN_PROGRESS,
        notes = "",
    )

    private fun sampleWorkout(): Workout = Workout(
        id = "wk-1",
        name = "Morning circuit",
        rows = listOf(
            ExerciseRow(name = "Jumping jacks", durationSec = 40, triggerSec = 15, notes = "Warm-up pace"),
            ExerciseRow(name = "Push-ups", durationSec = null, triggerSec = null, notes = "10 reps"),
            ExerciseRow(name = "Plank, side", durationSec = 30, triggerSec = null, notes = "Say \"hold\"\nthen switch"),
        ),
        rounds = 2,
        leadInSec = 3,
        createdAt = 0L,
        updatedAt = 0L,
    )

    // ---- helpers ----

    /** Asserts the UTF-8 BOM and returns the text after it. */
    private fun textAfterBom(bytes: ByteArray): String {
        assertTrue(bytes.size >= 3)
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])
        return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
    }

    private fun records(text: String): List<String> = text.split("\r\n").dropLast(1)

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

    private fun Element.children(localName: String): List<Element> {
        val nodes = getElementsByTagName(localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Document.row(number: Int): Element {
        val nodes = getElementsByTagName("row")
        return (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("r") == number.toString() }
    }

    private fun Element.cell(ref: String): Element? = children("c").firstOrNull { it.getAttribute("r") == ref }

    // ---- log CSV ----

    @Test
    fun logCsvStartsWithBomAndUsesCrlfLineEndings() {
        val text = textAfterBom(Exporters.logCsv(listOf(completedEntry(), inProgressEntry())))
        assertTrue(text.endsWith("\r\n"))
        assertEquals(3, text.split("\r\n").size - 1)
        assertFalse("no bare line feeds", text.replace("\r\n", "").contains("\n"))
        assertFalse("no bare carriage returns", text.replace("\r\n", "").contains("\r"))
    }

    @Test
    fun logCsvHeaderListsColumnsInOrder() {
        val text = textAfterBom(Exporters.logCsv(emptyList()))
        assertEquals(expectedLogHeader + "\r\n", text)
        assertEquals(expectedLogHeader.split(","), Exporters.LOG_COLUMNS)
    }

    @Test
    fun logCsvFormatsACompletedEntry() {
        val lines = records(textAfterBom(Exporters.logCsv(listOf(completedEntry()))))
        assertEquals(2, lines.size)
        assertEquals(expectedLogHeader, lines[0])
        assertEquals("2026-09-14,06:30:00,06:54:30,24.5,1.5,Morning circuit,14,14,2,completed,Solid session,sess-1,wk-1", lines[1])
    }

    @Test
    fun logCsvLeavesEndTimeBlankForAnInProgressEntry() {
        val lines = records(textAfterBom(Exporters.logCsv(listOf(inProgressEntry()))))
        assertEquals("2026-09-14,08:00:00,,0.0,0.0,Morning circuit,3,14,2,in_progress,,sess-2,wk-1", lines[1])
    }

    @Test
    fun logCsvQuotesNotesContainingCommaQuoteAndNewline() {
        val note = "Felt good, \"strong\"\nsecond line"
        val text = textAfterBom(Exporters.logCsv(listOf(completedEntry(notes = note))))
        val expectedField = "\"Felt good, \"\"strong\"\"\nsecond line\""
        assertTrue(text.contains(",completed,$expectedField,sess-1,wk-1\r\n"))
        // the only bare line feed is the one inside the quoted field
        assertEquals(1, text.replace("\r\n", "").count { it == '\n' })
    }

    @Test
    fun logCsvUsesEachEntrysOwnZone() {
        val started = epochMs(2026, 9, 14, 6, 30)
        val tokyo = completedEntry().copy(zoneId = "Asia/Tokyo", startedAt = started, finishedAt = started + 60_000L)
        val lines = records(textAfterBom(Exporters.logCsv(listOf(tokyo))))
        assertTrue(lines[1].startsWith("2026-09-14,20:30:00,20:31:00,"))
    }

    // ---- workout CSV ----

    @Test
    fun workoutCsvFirstTwoLinesMatchTheImportLayout() {
        val lines = records(textAfterBom(Exporters.workoutCsv(sampleWorkout())))
        assertEquals("Exercise,Duration (s),Trigger (s),Notes", lines[0])
        assertEquals("Jumping jacks,40,15,Warm-up pace", lines[1])
    }

    @Test
    fun workoutCsvLeavesUntimedAndManualBlankAndQuotesSpecialFields() {
        val lines = records(textAfterBom(Exporters.workoutCsv(sampleWorkout())))
        assertEquals(4, lines.size)
        assertEquals("Push-ups,,,10 reps", lines[2])
        assertEquals("\"Plank, side\",30,,\"Say \"\"hold\"\"\nthen switch\"", lines[3])
    }

    @Test
    fun workoutCsvOfAnEmptyWorkoutIsJustTheHeader() {
        val text = textAfterBom(Exporters.workoutCsv(sampleWorkout().copy(rows = emptyList())))
        assertEquals("Exercise,Duration (s),Trigger (s),Notes\r\n", text)
    }

    // ---- CSV quoting ----

    @Test
    fun csvFieldQuotesOnlyWhenNeeded() {
        assertEquals("plain text", Exporters.csvField("plain text"))
        assertEquals("", Exporters.csvField(""))
        assertEquals("\"a,b\"", Exporters.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Exporters.csvField("say \"hi\""))
        assertEquals("\"line1\r\nline2\"", Exporters.csvField("line1\r\nline2"))
        assertEquals("\"line1\nline2\"", Exporters.csvField("line1\nline2"))
    }

    // ---- log XLSX ----

    @Test
    fun logXlsxHasTheSameColumnsWithRealDateAndTimeCells() {
        val parts = unzip(Exporters.logXlsx(listOf(completedEntry(), inProgressEntry())))
        val workbook = parse(parts.getValue("xl/workbook.xml"))
        assertEquals("Log", (workbook.getElementsByTagName("sheet").item(0) as Element).getAttribute("name"))

        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))
        val header = sheet.row(1).children("c")
        assertEquals(Exporters.LOG_COLUMNS, header.map { it.children("t").single().textContent })
        for (cell in header) assertEquals(XlsxWriter.STYLE_HEADER.toString(), cell.getAttribute("s"))

        val completed = sheet.row(2)
        val date = completed.cell("A2")!!
        assertEquals(XlsxWriter.STYLE_DATE.toString(), date.getAttribute("s"))
        assertEquals("46279", date.children("v").single().textContent)
        val start = completed.cell("B2")!!
        assertEquals(XlsxWriter.STYLE_TIME.toString(), start.getAttribute("s"))
        assertEquals(XlsxWriter.excelSerial(completedEntry().startedAt, zone), start.children("v").single().textContent.toDouble(), 1e-9)
        assertEquals(XlsxWriter.STYLE_TIME.toString(), completed.cell("C2")!!.getAttribute("s"))
        assertEquals("24.5", completed.cell("D2")!!.children("v").single().textContent)
        assertEquals("1.5", completed.cell("E2")!!.children("v").single().textContent)
        assertEquals("Morning circuit", completed.cell("F2")!!.children("t").single().textContent)
        assertEquals("14", completed.cell("G2")!!.children("v").single().textContent)
        assertEquals("14", completed.cell("H2")!!.children("v").single().textContent)
        assertEquals("2", completed.cell("I2")!!.children("v").single().textContent)
        assertEquals("completed", completed.cell("J2")!!.children("t").single().textContent)
        val notes = completed.cell("K2")!!
        assertEquals(XlsxWriter.STYLE_WRAP.toString(), notes.getAttribute("s"))
        assertEquals("Solid session", notes.children("t").single().textContent)
        assertEquals("sess-1", completed.cell("L2")!!.children("t").single().textContent)
        assertEquals("wk-1", completed.cell("M2")!!.children("t").single().textContent)

        val inProgress = sheet.row(3)
        assertNull("end_time is omitted while in progress", inProgress.cell("C3"))
        assertNull("empty notes are omitted", inProgress.cell("K3"))
        assertEquals("in_progress", inProgress.cell("J3")!!.children("t").single().textContent)

        assertEquals("A1:M3", (sheet.getElementsByTagName("autoFilter").item(0) as Element).getAttribute("ref"))
        assertEquals(13, sheet.getElementsByTagName("col").length)
    }
}
