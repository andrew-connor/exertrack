package com.exertrack.app.io

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class CsvParserTest {

    private fun rows(text: String): List<List<String>> = CsvParser.parseText(text).rows

    @Test
    fun commaSeparated() {
        val table = CsvParser.parseText("Exercise,Duration,Trigger,Notes\nPush-ups,40,20,Slow\n", "Upper")
        assertEquals("Upper", table.name)
        assertEquals(
            listOf(
                listOf("Exercise", "Duration", "Trigger", "Notes"),
                listOf("Push-ups", "40", "20", "Slow"),
            ),
            table.rows,
        )
    }

    @Test
    fun semicolonSeparated() {
        assertEquals(';', CsvParser.detectDelimiter("Exercise;Duration;Trigger\nPush-ups;40;20\nPlank;1,5;10\n"))
        assertEquals(
            listOf(listOf("Push-ups", "40", "20"), listOf("Plank", "1,5", "10")),
            rows("Push-ups;40;20\nPlank;1,5;10"),
        )
    }

    @Test
    fun tabSeparated() {
        assertEquals('\t', CsvParser.detectDelimiter("Exercise\tDuration\tTrigger\nPush-ups\t40\t20\n"))
        assertEquals(
            listOf(listOf("Push-ups", "40", "20", "Keep core tight, breathe")),
            rows("Push-ups\t40\t20\tKeep core tight, breathe"),
        )
    }

    @Test
    fun quotedFieldKeepsDelimiter() {
        assertEquals(
            listOf(listOf("Push-ups", "40", "20", "Keep core tight, breathe")),
            rows("Push-ups,40,20,\"Keep core tight, breathe\""),
        )
    }

    @Test
    fun quotedFieldKeepsLineBreak() {
        val text = "Push-ups,40,20,\"Line one\r\nLine two\"\r\nPlank,60,,\r\n"
        assertEquals(
            listOf(
                listOf("Push-ups", "40", "20", "Line one\nLine two"),
                listOf("Plank", "60", "", ""),
            ),
            rows(text),
        )
    }

    @Test
    fun doubledQuotesBecomeOneQuote() {
        assertEquals(
            listOf(listOf("Farmer \"walk\"", "40", "", "Say \"go\"")),
            rows("\"Farmer \"\"walk\"\"\",40,,\"Say \"\"go\"\"\""),
        )
    }

    @Test
    fun bomIsStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Push-ups,40\n".toByteArray(Charsets.UTF_8)
        assertEquals(listOf(listOf("Push-ups", "40")), CsvParser.parse(bytes).rows)
        assertEquals(listOf(listOf("Push-ups", "40")), CsvParser.parseText("﻿Push-ups,40").rows)
    }

    @Test
    fun crlfLineEndings() {
        assertEquals(
            listOf(listOf("a", "1"), listOf("b", "2")),
            rows("a,1\r\nb,2\r\n"),
        )
    }

    @Test
    fun crOnlyLineEndings() {
        assertEquals(
            listOf(listOf("a", "1"), listOf("b", "2")),
            rows("a,1\rb,2\r"),
        )
    }

    @Test
    fun windows1252FallbackDecodesAccents() {
        val bytes = "Café,40\n".toByteArray(Charset.forName("windows-1252"))
        assertEquals(0xE9.toByte(), bytes[3])
        assertEquals(listOf(listOf("Café", "40")), CsvParser.parse(bytes).rows)
    }

    @Test
    fun validUtf8IsDecodedAsUtf8() {
        val bytes = "Café,40\nÜbung,1:30\n".toByteArray(Charsets.UTF_8)
        assertEquals(listOf(listOf("Café", "40"), listOf("Übung", "1:30")), CsvParser.parse(bytes).rows)
    }

    @Test
    fun utf16WithBomIsDecoded() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Push-ups\t40\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf(listOf("Push-ups", "40")), CsvParser.parse(bytes).rows)
    }

    @Test
    fun trailingEmptyLinesAreDropped() {
        assertEquals(listOf(listOf("a", "1"), listOf(""), listOf("b", "2")), rows("a,1\n\nb,2\n\n\n"))
    }

    @Test
    fun cellsAreNotTrimmed() {
        assertEquals(listOf(listOf(" a ", " 1")), rows(" a , 1"))
    }

    @Test
    fun emptyInputGivesNoRows() {
        assertEquals(emptyList<List<String>>(), rows(""))
        assertEquals(emptyList<List<String>>(), CsvParser.parse(ByteArray(0)).rows)
    }

    @Test
    fun delimiterDefaultsToCommaWhenUndecidable() {
        assertEquals(',', CsvParser.detectDelimiter("Push-ups\nPlank\n"))
        assertEquals(',', CsvParser.detectDelimiter(""))
    }

    @Test
    fun delimiterDetectionPrefersConsistentCounts() {
        // Commas only appear in some notes cells; semicolons separate every line.
        val text = "Exercise;Duration (s);Trigger (s);Notes\nPush-ups;40;20;Slow, controlled\nPlank;60;;\nSquats;45;15;\n"
        assertEquals(';', CsvParser.detectDelimiter(text))
        // Delimiters inside quotes do not count.
        assertEquals(',', CsvParser.detectDelimiter("a,\"x;y;z\"\nb,\"p;q;r\"\n"))
    }

    @Test
    fun lastLineWithoutNewlineIsKept() {
        assertEquals(listOf(listOf("a", "1"), listOf("b", "2")), rows("a,1\nb,2"))
    }
}
