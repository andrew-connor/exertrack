package com.exertrack.app.io

import com.exertrack.app.data.Json
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.RowMedia
import com.exertrack.app.data.Settings
import com.exertrack.app.data.Workout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-app backup: a zip holding `backup.json` (workouts, log and settings in one JSON
 * document) plus `media/<file>` for every photo or video an exercise references. The plain
 * JSON document on its own is the backup format of earlier versions and still restores.
 *
 * Only java.io and java.util.zip are used here so the format can be unit-tested on the JVM.
 */
object Backup {
    const val JSON_ENTRY = "backup.json"
    const val MEDIA_PREFIX = "media/"

    data class Data(
        val workouts: List<Workout>,
        val log: List<LogEntry>,
        val settings: Settings,
        val exportedAt: Long?,
        /** True when the data came out of a zip, which may also carry media files to extract. */
        val zipped: Boolean = false,
    )

    fun toJson(workouts: List<Workout>, log: List<LogEntry>, settings: Settings, exportedAt: Long): String =
        JSONObject()
            .put("app", "Exertrack")
            .put("schemaVersion", Json.SCHEMA_VERSION)
            .put("exportedAt", exportedAt)
            .put("workouts", JSONArray().apply { workouts.forEach { put(Json.workout(it)) } })
            .put("log", JSONArray().apply { log.forEach { put(Json.logEntry(it)) } })
            .put("settings", Json.settings(settings))
            .toString(2)

    /** @throws IllegalArgumentException when the text is not an Exertrack backup. */
    fun parse(text: String): Data {
        val o = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Not a JSON file") }
        if (o.optString("app") != "Exertrack" || !o.has("workouts")) throw IllegalArgumentException("Not an Exertrack backup file")
        val workouts = o.getJSONArray("workouts").let { arr -> (0 until arr.length()).map { Json.workout(arr.getJSONObject(it)) } }
        val log = o.optJSONArray("log")?.let { arr -> (0 until arr.length()).map { Json.logEntry(arr.getJSONObject(it)) } } ?: emptyList()
        val settings = o.optJSONObject("settings")?.let { Json.settings(it) } ?: Settings()
        return Data(workouts, log, settings, Json.longOrNull(o, "exportedAt"))
    }

    // ---- zip ----

    /**
     * Write the zip backup to [out]: `backup.json` first, then every referenced media file and
     * its preview that exists under [mediaDir]. Media still pending a save is left out. The zip
     * is finished and flushed but [out] itself is left open for the caller to close.
     */
    fun writeZip(
        out: OutputStream,
        workouts: List<Workout>,
        log: List<LogEntry>,
        settings: Settings,
        exportedAt: Long,
        mediaDir: File,
    ) {
        val zip = ZipOutputStream(BufferedOutputStream(out))
        zip.putNextEntry(ZipEntry(JSON_ENTRY))
        zip.write(toJson(workouts, log, settings, exportedAt).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
        val written = HashSet<String>()
        for (media in referencedMedia(workouts)) {
            for (name in listOf(media.file, media.thumbFile)) {
                if (!isFlatName(name) || !written.add(name)) continue
                val source = File(mediaDir, name)
                if (!source.isFile) continue
                zip.putNextEntry(ZipEntry(MEDIA_PREFIX + name))
                FileInputStream(source).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        zip.finish()
        zip.flush()
    }

    /** True when [header] starts with the local-file signature every zip begins with. */
    fun isZip(header: ByteArray): Boolean =
        header.size >= 4 &&
            header[0] == 'P'.code.toByte() &&
            header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() &&
            header[3] == 4.toByte()

    /**
     * Read `backup.json` out of a zip backup. Entries are streamed in order and reading stops
     * at the JSON document, so media after it is never touched. [input] is left open.
     * @throws IllegalArgumentException when the stream is not a zip or has no `backup.json`.
     */
    fun readZipJson(input: InputStream): Data {
        val zip = ZipInputStream(input)
        var entry = nextEntry(zip)
        while (entry != null) {
            if (!entry.isDirectory && entry.name == JSON_ENTRY) {
                val text = zip.readBytes().toString(StandardCharsets.UTF_8)
                return parse(text).copy(zipped = true)
            }
            zip.closeEntry()
            entry = nextEntry(zip)
        }
        throw IllegalArgumentException("Not an Exertrack backup")
    }

    /**
     * Copy every `media/<name>` entry of a zip backup into [mediaDir], overwriting files of the
     * same name. Only flat names are accepted: an entry with a nested path or ".." in its name
     * is skipped, so a crafted zip cannot write outside [mediaDir]. [input] is left open.
     * @return how many files were written.
     */
    fun extractZipMedia(input: InputStream, mediaDir: File): Int {
        val zip = ZipInputStream(input)
        var count = 0
        var entry = nextEntry(zip)
        while (entry != null) {
            val name = mediaEntryName(entry)
            if (name != null) {
                mediaDir.mkdirs()
                val target = File(mediaDir, name)
                // Written beside the target first so an interrupted restore never leaves a truncated file in place.
                val partial = File(mediaDir, "$name.part")
                try {
                    FileOutputStream(partial).use { zip.copyTo(it) }
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    count++
                } finally {
                    partial.delete()
                }
            }
            zip.closeEntry()
            entry = nextEntry(zip)
        }
        return count
    }

    /**
     * Read a backup of either kind: the stream is peeked for the zip signature and read as a
     * zip backup when present, otherwise as the plain JSON document of earlier versions.
     * [open] is called once and the stream it returns is closed here.
     * @throws IllegalArgumentException when the content is not an Exertrack backup.
     */
    fun readAny(open: () -> InputStream): Data {
        BufferedInputStream(open()).use { input ->
            input.mark(8)
            val header = ByteArray(4)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            input.reset()
            return if (isZip(header.copyOf(read))) {
                readZipJson(input)
            } else {
                parse(input.readBytes().toString(StandardCharsets.UTF_8).removePrefix("﻿"))
            }
        }
    }

    // ---- internals ----

    /** Media every row references, once each and without picks that were never saved. */
    private fun referencedMedia(workouts: List<Workout>): List<RowMedia> =
        workouts.flatMap { w -> w.rows.mapNotNull { it.media } }.filterNot { it.isPending }.distinct()

    /** A name that stays inside the media directory: no separators, no parent references. */
    private fun isFlatName(name: String): Boolean =
        name.isNotEmpty() && name != "." && !name.contains("..") && !name.contains('/') && !name.contains('\\')

    /** The file name of a `media/<name>` entry, or null for any other or unsafe entry. */
    private fun mediaEntryName(entry: ZipEntry): String? {
        if (entry.isDirectory || !entry.name.startsWith(MEDIA_PREFIX)) return null
        val name = entry.name.removePrefix(MEDIA_PREFIX)
        return name.takeIf { isFlatName(it) }
    }

    private fun nextEntry(zip: ZipInputStream): ZipEntry? =
        try {
            zip.nextEntry
        } catch (e: ZipException) {
            throw IllegalArgumentException("Not an Exertrack backup")
        }
}
