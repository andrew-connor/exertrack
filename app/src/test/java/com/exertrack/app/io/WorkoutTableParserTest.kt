package com.exertrack.app.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkoutTableParserTest {

    private fun table(vararg rows: List<String>): SheetTable = SheetTable("Sheet", rows.toList())

    private fun seconds(text: String): Int? = when (val parsed = WorkoutTableParser.parseDuration(text)) {
        is DurationParse.Seconds -> parsed.seconds
        DurationParse.Untimed -> null
        is DurationParse.Invalid -> throw AssertionError("unexpected: $parsed")
    }

    // ---- header detection ------------------------------------------------------------------

    @Test
    fun headerDetectedByColumnBText() {
        val first = listOf("Push-ups", "Duration (s)", "Trigger (s)", "Notes")
        assertTrue(WorkoutTableParser.looksLikeHeader(first))
        val parsed = WorkoutTableParser.parse(table(first, listOf("Squats", "40", "20", "")))
        assertTrue(parsed.hasHeader)
        assertEquals(1, parsed.rows.size)
        assertEquals(2, parsed.rows[0].rowNumber)
        assertEquals("Squats", parsed.rows[0].row?.name)
    }

    @Test
    fun headerDetectedByExerciseInA1() {
        assertTrue(WorkoutTableParser.looksLikeHeader(listOf("Exercise", "40")))
        assertTrue(WorkoutTableParser.looksLikeHeader(listOf("  exercise name ", "")))
        assertTrue(WorkoutTableParser.looksLikeHeader(listOf("Name")))
        val parsed = WorkoutTableParser.parse(table(listOf("Exercise", "40"), listOf("Squats", "40")))
        assertTrue(parsed.hasHeader)
        assertEquals(listOf(2), parsed.rows.map { it.rowNumber })
    }

    @Test
    fun noHeaderWhenB1IsNumeric() {
        assertFalse(WorkoutTableParser.looksLikeHeader(listOf("Push-ups", "40", "20")))
        assertFalse(WorkoutTableParser.looksLikeHeader(listOf("Push-ups", "1:30")))
        assertFalse(WorkoutTableParser.looksLikeHeader(listOf("Push-ups", "")))
        assertFalse(WorkoutTableParser.looksLikeHeader(emptyList()))
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40", "20"), listOf("Squats", "30")))
        assertFalse(parsed.hasHeader)
        assertEquals(listOf(1, 2), parsed.rows.map { it.rowNumber })
        assertEquals(40, parsed.rows[0].row?.durationSec)
        assertEquals(20, parsed.rows[0].row?.triggerSec)
    }

    // ---- durations ---------------------------------------------------------------------------

    @Test
    fun untimedDurations() {
        for (text in listOf("", "  ", "0", "-", "untimed", "Untimed", "REPS", "0:00", "0.0")) {
            assertEquals(text, DurationParse.Untimed, WorkoutTableParser.parseDuration(text))
        }
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "reps", "", "Until failure")))
        val row = parsed.rows[0].row
        assertNotNull(row)
        assertNull(row!!.durationSec)
        assertTrue(row.isUntimed)
        assertNull(row.triggerSec)
        assertEquals("Until failure", row.notes)
    }

    @Test
    fun minuteSecondDurations() {
        assertEquals(90, seconds("1:30"))
        assertEquals(45, seconds("0:45"))
        assertEquals(45, seconds("00:45"))
        assertEquals(600, seconds("10:00"))
    }

    @Test
    fun hourDurationGetsWarning() {
        val parsed = WorkoutTableParser.parseDuration("1:00:00") as DurationParse.Seconds
        assertEquals(3600, parsed.seconds)
        assertEquals(WorkoutTableParser.LONG_DURATION, parsed.warning)
        assertEquals(5400, seconds("1:30:00"))
        assertNull((WorkoutTableParser.parseDuration("59:59") as DurationParse.Seconds).warning)
    }

    @Test
    fun integerAndDecimalDurations() {
        assertEquals(40, seconds("40"))
        assertEquals(40, seconds(" 40 "))
        assertEquals(41, seconds("40.5"))
        assertEquals(40, seconds("40.4"))
        assertEquals(2, seconds("1,5"))
        assertEquals(1, seconds("1"))
        assertNull((WorkoutTableParser.parseDuration("40") as DurationParse.Seconds).warning)
    }

    @Test
    fun durationsWithUnits() {
        assertEquals(40, seconds("40s"))
        assertEquals(40, seconds("40 sec"))
        assertEquals(90, seconds("1.5 min"))
        assertEquals(120, seconds("2m"))
        assertEquals(3600, seconds("1 hour"))
    }

    @Test
    fun excelFractionWarning() {
        val parsed = WorkoutTableParser.parseDuration("0.000463") as DurationParse.Seconds
        assertEquals(40, parsed.seconds)
        assertEquals(WorkoutTableParser.EXCEL_TIME_DURATION, parsed.warning)

        val table = WorkoutTableParser.parse(table(listOf("Plank", "0.0625", "", "")))
        val row = table.rows[0]
        assertNull(row.problem)
        assertEquals(5400, row.row?.durationSec)
        assertTrue(row.warning!!.contains(WorkoutTableParser.EXCEL_TIME_DURATION))
        assertTrue(row.warning!!.contains(WorkoutTableParser.LONG_DURATION))
        assertEquals(1, table.warningCount)
        assertTrue(table.isClean)
    }

    @Test
    fun invalidDurations() {
        for (text in listOf("abc", "1:75", "1:2:3:4", "-5", "40x", "1..5", "fast")) {
            assertTrue(text, WorkoutTableParser.parseDuration(text) is DurationParse.Invalid)
        }
        val invalid = WorkoutTableParser.parseDuration("abc") as DurationParse.Invalid
        assertTrue(invalid.reason.contains("abc"))
    }

    @Test
    fun invalidDurationIsAProblem() {
        // Row 1 must hold a real duration: "forty" in row 1 would make that row the header.
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40"), listOf("Squats", "forty", "20", "")))
        assertFalse(parsed.hasHeader)
        val row = parsed.rows[1]
        assertEquals(2, row.rowNumber)
        assertNull(row.row)
        assertNotNull(row.problem)
        assertTrue(row.problem!!.contains("forty"))
        assertEquals(1, parsed.problemCount)
        assertFalse(parsed.isClean)
        assertEquals(listOf("Push-ups"), parsed.exercises.map { it.name })
    }

    // ---- triggers ----------------------------------------------------------------------------

    @Test
    fun manualTriggers() {
        for (text in listOf("", "  ", "manual", "Manual", "m", "M", "-", "0", "0:00")) {
            assertEquals(text, TriggerParse.Manual, WorkoutTableParser.parseTrigger(text))
        }
    }

    @Test
    fun secondsTriggers() {
        assertEquals(TriggerParse.Seconds(20), WorkoutTableParser.parseTrigger("20"))
        assertEquals(TriggerParse.Seconds(20), WorkoutTableParser.parseTrigger(" 20 "))
        assertEquals(TriggerParse.Seconds(90), WorkoutTableParser.parseTrigger("1:30"))
        assertEquals(TriggerParse.Seconds(31), WorkoutTableParser.parseTrigger("30.5"))
    }

    @Test
    fun invalidTriggers() {
        assertTrue(WorkoutTableParser.parseTrigger("soon") is TriggerParse.Invalid)
        assertTrue(WorkoutTableParser.parseTrigger("-10") is TriggerParse.Invalid)
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40", "soon", "")))
        assertNull(parsed.rows[0].row)
        assertTrue(parsed.rows[0].problem!!.contains("soon"))
    }

    @Test
    fun triggerZeroIsManualInRow() {
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40", "0", "")))
        val row = parsed.rows[0].row!!
        assertNull(row.triggerSec)
        assertTrue(row.isManual)
    }

    @Test
    fun excelFractionTriggerWarnsButIsNotAProblem() {
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40", "0.000231", "")))
        val row = parsed.rows[0]
        assertNull(row.problem)
        assertEquals(20, row.row?.triggerSec)
        assertEquals(WorkoutTableParser.EXCEL_TIME_TRIGGER, row.warning)
    }

    // ---- rows and layout ---------------------------------------------------------------------

    @Test
    fun blankNameWithOtherContentIsAProblem() {
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40"), listOf("   ", "30", "", "")))
        assertEquals(2, parsed.rows.size)
        val bad = parsed.rows[1]
        assertEquals(2, bad.rowNumber)
        assertNull(bad.row)
        assertEquals(WorkoutTableParser.MISSING_NAME, bad.problem)
    }

    @Test
    fun multipleProblemsAreCombined() {
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40"), listOf("", "abc", "xyz", "notes")))
        val problem = parsed.rows[1].problem!!
        assertTrue(problem.contains(WorkoutTableParser.MISSING_NAME))
        assertTrue(problem.contains("abc"))
        assertTrue(problem.contains("xyz"))
    }

    @Test
    fun blankRowsAreSkippedAndRowNumbersFollowTheSheet() {
        val parsed = WorkoutTableParser.parse(
            table(
                listOf("Exercise", "Duration"),
                listOf("Push-ups", "40"),
                emptyList(),
                listOf("", "", "", ""),
                listOf("  ", "\t"),
                listOf("Squats", "30"),
            ),
        )
        assertTrue(parsed.hasHeader)
        assertEquals(listOf(2, 6), parsed.rows.map { it.rowNumber })
        assertEquals(listOf("Push-ups", "Squats"), parsed.exercises.map { it.name })
    }

    @Test
    fun leadingBlankRowsBeforeHeaderAreSkipped() {
        val parsed = WorkoutTableParser.parse(
            table(emptyList(), listOf("Exercise", "Duration"), listOf("Push-ups", "40")),
        )
        assertTrue(parsed.hasHeader)
        assertEquals(listOf(3), parsed.rows.map { it.rowNumber })
    }

    @Test
    fun extraColumnsAreIgnored() {
        val parsed = WorkoutTableParser.parse(
            table(listOf("Push-ups", "40", "20", "Slow", "ignored", "also ignored")),
        )
        val row = parsed.rows[0].row!!
        assertEquals("Push-ups", row.name)
        assertEquals(40, row.durationSec)
        assertEquals(20, row.triggerSec)
        assertEquals("Slow", row.notes)
        assertNull(parsed.rows[0].problem)
    }

    @Test
    fun twoColumnSheetIsAccepted() {
        val parsed = WorkoutTableParser.parse(table(listOf("Exercise", "Duration"), listOf("Push-ups", "40"), listOf("Plank", "1:00")))
        assertTrue(parsed.isClean)
        assertEquals(2, parsed.exercises.size)
        assertEquals(40, parsed.exercises[0].durationSec)
        assertNull(parsed.exercises[0].triggerSec)
        assertEquals("", parsed.exercises[0].notes)
        assertEquals(60, parsed.exercises[1].durationSec)
    }

    @Test
    fun oneColumnSheetGivesUntimedManualRows() {
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups"), listOf("Squats")))
        assertFalse(parsed.hasHeader)
        assertEquals(2, parsed.exercises.size)
        assertTrue(parsed.exercises.all { it.isUntimed && it.isManual })
    }

    @Test
    fun namesAndNotesAreTrimmedButNotesKeepLineBreaks() {
        val parsed = WorkoutTableParser.parse(table(listOf("  Push-ups  ", " 40 ", " 20 ", "\n  First line\r\nSecond line  \n")))
        val row = parsed.rows[0].row!!
        assertEquals("Push-ups", row.name)
        assertEquals("First line\nSecond line", row.notes)
        assertEquals(40, row.durationSec)
        assertEquals(20, row.triggerSec)
    }

    @Test
    fun rowIdsAreFresh() {
        val parsed = WorkoutTableParser.parse(table(listOf("Push-ups", "40"), listOf("Push-ups", "40")))
        val ids = parsed.exercises.map { it.id }
        assertEquals(2, ids.toSet().size)
        assertNotEquals("", ids[0])
    }

    @Test
    fun emptySheet() {
        val parsed = WorkoutTableParser.parse(SheetTable("Empty", emptyList()))
        assertFalse(parsed.hasHeader)
        assertTrue(parsed.isEmpty)
        assertFalse(parsed.isClean)
        assertEquals("Empty", parsed.sheetName)
    }

    // ---- SpreadsheetImport -------------------------------------------------------------------

    @Test
    fun csvIsNamedAfterTheFile() {
        val sheets = SpreadsheetImport.readAll("Exercise,Duration\nPush-ups,40\n".toByteArray(), "Monday workout.csv")
        assertEquals(1, sheets.size)
        assertEquals("Monday workout", sheets[0].name)
        assertEquals(listOf(listOf("Exercise", "Duration"), listOf("Push-ups", "40")), sheets[0].rows)
        assertEquals("notes", SpreadsheetImport.sheetNameFor("notes"))
        assertEquals("CSV", SpreadsheetImport.sheetNameFor(".csv"))
        assertEquals("CSV", SpreadsheetImport.sheetNameFor(""))
        assertEquals("a.b", SpreadsheetImport.sheetNameFor("a.b.txt"))
    }

    @Test
    fun xlsxBytesAreReadAsWorkbook() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("xl/workbook.xml", """<workbook xmlns="$ns" xmlns:r="$relNs"><sheets><sheet name="Core" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put(
                "xl/_rels/workbook.xml.rels",
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$relNs/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            )
            put(
                "xl/worksheets/sheet1.xml",
                """<worksheet xmlns="$ns"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Plank</t></is></c><c r="B1"><v>60</v></c></row></sheetData></worksheet>""",
            )
        }
        val sheets = SpreadsheetImport.readAll(out.toByteArray(), "core.xlsx")
        assertEquals(listOf("Core"), sheets.map { it.name })
        val parsed = WorkoutTableParser.parse(sheets[0])
        assertTrue(parsed.isClean)
        assertEquals(60, parsed.exercises[0].durationSec)
    }

    @Test(expected = IllegalArgumentException::class)
    fun binaryBytesAreRejected() {
        val bytes = ByteArray(512) { (it % 7).toByte() }
        SpreadsheetImport.readAll(bytes, "photo.jpg")
    }

    @Test
    fun textWithFewControlCharactersIsAccepted() {
        val text = "Push-ups,40\r\nSquats\t30\r\n"
        val sheets = SpreadsheetImport.readAll(text.toByteArray(), "plain.txt")
        assertEquals("plain", sheets[0].name)
        assertEquals(2, sheets[0].rows.size)
    }
}
