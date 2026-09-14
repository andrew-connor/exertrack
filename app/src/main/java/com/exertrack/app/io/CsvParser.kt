package com.exertrack.app.io

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** A rectangular grid of cell strings from one sheet or one CSV file. Cells are raw (untrimmed) text. */
data class SheetTable(val name: String, val rows: List<List<String>>)

/**
 * CSV reader: strips a UTF-8 BOM, decodes UTF-8 (falling back to windows-1252 when the bytes are
 * not valid UTF-8), auto-detects the delimiter among comma / semicolon / tab from the first
 * non-empty lines, handles RFC 4180 quoting (quoted fields may contain delimiters, doubled
 * quotes and line breaks) and any of CRLF / LF / CR line endings.
 */
object CsvParser {
    private const val BOM = '﻿'
    private const val SAMPLE_LINES = 20

    /** Candidate delimiters in tie-break order: the earliest wins when scores are equal. */
    private val CANDIDATES = charArrayOf(',', ';', '\t')

    private val fallbackCharset: Charset by lazy {
        runCatching { Charset.forName("windows-1252") }.getOrElse { StandardCharsets.ISO_8859_1 }
    }

    fun parse(bytes: ByteArray, name: String = "CSV"): SheetTable = parseText(decode(bytes), name)

    fun parseText(text: String, name: String = "CSV"): SheetTable {
        val body = text.removePrefix(BOM.toString())
        val delimiter = detectDelimiter(body)
        val rows = splitRecords(body, delimiter)
        return SheetTable(name, dropTrailingBlankRows(rows))
    }

    /** The delimiter character used by [text], or ',' when undecidable. */
    fun detectDelimiter(text: String): Char {
        val samples = sampleDelimiterCounts(text.removePrefix(BOM.toString()))
        if (samples.isEmpty()) return CANDIDATES[0]
        var best = CANDIDATES[0]
        var bestScore = Score(0, 0)
        CANDIDATES.forEachIndexed { index, candidate ->
            val score = scoreCandidate(samples.map { it[index] })
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /**
     * Decodes CSV bytes to text: a UTF-8 BOM is honoured and stripped, a UTF-16 BOM selects that
     * encoding, otherwise the bytes are decoded as strict UTF-8 with windows-1252 as the fallback
     * for input that is not valid UTF-8 (typical of files saved by older Windows tools).
     */
    fun decode(bytes: ByteArray): String {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return decodeUtf8OrFallback(bytes, 3)
        if (startsWith(bytes, 0xFF, 0xFE)) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        if (startsWith(bytes, 0xFE, 0xFF)) return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        return decodeUtf8OrFallback(bytes, 0)
    }

    // ---- decoding -------------------------------------------------------------------------

    private fun decodeUtf8OrFallback(bytes: ByteArray, offset: Int): String {
        val length = bytes.size - offset
        if (length <= 0) return ""
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, offset, length, fallbackCharset)
        }
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i].toByte()) return false
        }
        return true
    }

    // ---- delimiter detection --------------------------------------------------------------

    /** (number of sampled lines agreeing on the modal count, that modal count). */
    private data class Score(val consistency: Int, val mode: Int) : Comparable<Score> {
        override fun compareTo(other: Score): Int =
            if (consistency != other.consistency) consistency.compareTo(other.consistency)
            else mode.compareTo(other.mode)
    }

    private fun scoreCandidate(counts: List<Int>): Score {
        val nonZero = counts.filter { it > 0 }
        if (nonZero.isEmpty()) return Score(0, 0)
        val mode = nonZero.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy({ it.value }, { it.key }))!!.key
        return Score(counts.count { it == mode }, mode)
    }

    /**
     * For each of the first [SAMPLE_LINES] non-empty records, how many times each candidate
     * delimiter occurs outside quotes. Records are split at unquoted line breaks so a quoted
     * field spanning several lines counts as one record.
     */
    private fun sampleDelimiterCounts(text: String): List<IntArray> {
        val result = ArrayList<IntArray>()
        var counts = IntArray(CANDIDATES.size)
        var hasContent = false
        var inQuotes = false
        var atFieldStart = true
        var i = 0
        val n = text.length
        while (i < n && result.size < SAMPLE_LINES) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text[i + 1] == '"') i++ else inQuotes = false
                }
            } else {
                when {
                    ch == '\n' || ch == '\r' -> {
                        if (ch == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                        if (hasContent) result.add(counts)
                        counts = IntArray(CANDIDATES.size)
                        hasContent = false
                        atFieldStart = true
                    }
                    ch == '"' && atFieldStart -> {
                        inQuotes = true
                        hasContent = true
                        atFieldStart = false
                    }
                    else -> {
                        val index = CANDIDATES.indexOf(ch)
                        if (index >= 0) counts[index]++
                        if (ch != ' ') hasContent = true
                        atFieldStart = index >= 0
                    }
                }
            }
            i++
        }
        if (hasContent && result.size < SAMPLE_LINES) result.add(counts)
        return result
    }

    // ---- record splitting -----------------------------------------------------------------

    /**
     * RFC 4180 tokenizer. A field that starts with a quote runs until the next lone quote;
     * inside it, doubled quotes are literal quotes and line breaks are kept (normalised to
     * "\n"). Text after the closing quote up to the delimiter is kept literally. Records end at
     * an unquoted CRLF, LF or CR. Cells are not trimmed.
     */
    private fun splitRecords(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var atFieldStart = true
        var recordStarted = false
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            if (inQuotes) {
                when (ch) {
                    '"' -> if (i + 1 < n && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                    '\r' -> {
                        field.append('\n')
                        if (i + 1 < n && text[i + 1] == '\n') i++
                    }
                    else -> field.append(ch)
                }
            } else {
                when {
                    ch == delimiter -> {
                        record.add(field.toString())
                        field.setLength(0)
                        atFieldStart = true
                        recordStarted = true
                    }
                    ch == '\n' || ch == '\r' -> {
                        if (ch == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                        record.add(field.toString())
                        field.setLength(0)
                        rows.add(record)
                        record = ArrayList()
                        atFieldStart = true
                        recordStarted = false
                    }
                    ch == '"' && atFieldStart -> {
                        inQuotes = true
                        atFieldStart = false
                        recordStarted = true
                    }
                    else -> {
                        field.append(ch)
                        atFieldStart = false
                        recordStarted = true
                    }
                }
            }
            i++
        }
        if (recordStarted || inQuotes) {
            record.add(field.toString())
            rows.add(record)
        }
        return rows
    }

    private fun dropTrailingBlankRows(rows: List<List<String>>): List<List<String>> {
        var end = rows.size
        while (end > 0 && rows[end - 1].all { it.isBlank() }) end--
        return if (end == rows.size) rows else rows.subList(0, end)
    }
}
