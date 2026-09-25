package com.exertrack.app.io

import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.MediaKind
import com.exertrack.app.data.RowMedia
import com.exertrack.app.data.SessionStatus
import com.exertrack.app.data.Settings
import com.exertrack.app.data.VibrationMode
import com.exertrack.app.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val video = RowMedia("abc.mp4", MediaKind.VIDEO)
    private val photo = RowMedia("def.jpg", MediaKind.IMAGE)
    private val pending = RowMedia("pending/ghi.jpg", MediaKind.IMAGE)
    private val missing = RowMedia("nowhere.jpg", MediaKind.IMAGE)

    private val workouts = listOf(
        Workout(
            id = "w1",
            name = "Legs",
            notes = "Warm up first",
            rounds = 2,
            leadInSec = 5,
            createdAt = 10L,
            updatedAt = 20L,
            rows = listOf(
                ExerciseRow(id = "r1", name = "Squats", durationSec = 40, triggerSec = 15, notes = "Deep", media = video),
                ExerciseRow(id = "r2", name = "Plank", durationSec = null, triggerSec = null),
                ExerciseRow(id = "r3", name = "Lunges", durationSec = 30, media = photo),
                // The same file attached twice must be written once.
                ExerciseRow(id = "r4", name = "Lunges again", durationSec = 30, media = photo),
            ),
        ),
        Workout(
            id = "w2",
            name = "Core",
            createdAt = 30L,
            updatedAt = 40L,
            rows = listOf(
                ExerciseRow(id = "r5", name = "Crunches", durationSec = 45, media = pending),
                ExerciseRow(id = "r6", name = "Bridge", durationSec = 45, media = missing),
            ),
        ),
    )

    private val log = listOf(
        LogEntry(
            id = "s1",
            workoutId = "w1",
            workoutName = "Legs",
            startedAt = 1_000L,
            finishedAt = 2_000L,
            zoneId = "America/Chicago",
            elapsedSec = 600,
            pausedSec = 30,
            completedSteps = 8,
            totalSteps = 8,
            rounds = 2,
            rowCount = 4,
            status = SessionStatus.COMPLETED,
            notes = "Good one",
        ),
    )

    private val settings = Settings(
        speechEnabled = false,
        duckOtherAudio = false,
        vibrationMode = VibrationMode.ALWAYS,
        chimesMuted = true,
        keepScreenOn = false,
        warningTicks = false,
    )

    private val exportedAt = 123_456L

    private fun mediaDirWithFiles(): File {
        val dir = temp.newFolder("media")
        File(dir, "abc.mp4").writeBytes("video bytes".toByteArray())
        File(dir, "abc.thumb.jpg").writeBytes("video preview".toByteArray())
        File(dir, "def.jpg").writeBytes("photo bytes".toByteArray())
        File(dir, "def.thumb.jpg").writeBytes("photo preview".toByteArray())
        // Not referenced by any row: must never end up in a backup.
        File(dir, "orphan.jpg").writeBytes("orphan".toByteArray())
        File(dir, "pending").mkdirs()
        File(dir, "pending/ghi.jpg").writeBytes("pending".toByteArray())
        return dir
    }

    private fun zipBytes(mediaDir: File = mediaDirWithFiles()): ByteArray {
        val out = ByteArrayOutputStream()
        Backup.writeZip(out, workouts, log, settings, exportedAt, mediaDir)
        return out.toByteArray()
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertSameData(data: Backup.Data) {
        assertEquals(workouts.map { it.id }, data.workouts.map { it.id })
        assertEquals(workouts.map { it.name }, data.workouts.map { it.name })
        assertEquals(workouts.map { it.notes }, data.workouts.map { it.notes })
        assertEquals(workouts.flatMap { w -> w.rows.map { it.media } }, data.workouts.flatMap { w -> w.rows.map { it.media } })
        assertEquals(workouts.flatMap { w -> w.rows.map { it.id } }, data.workouts.flatMap { w -> w.rows.map { it.id } })
        assertEquals(log.map { it.id }, data.log.map { it.id })
        assertEquals(log[0].notes, data.log[0].notes)
        assertEquals(settings, data.settings)
        assertEquals(exportedAt, data.exportedAt)
    }

    // ---- writing ----

    @Test
    fun `writeZip holds the json plus every referenced media file once`() {
        val bytes = zipBytes()
        assertTrue(Backup.isZip(bytes))
        val entries = entries(bytes)
        assertEquals(
            listOf("backup.json", "media/abc.mp4", "media/abc.thumb.jpg", "media/def.jpg", "media/def.thumb.jpg"),
            entries.keys.toList(),
        )
        assertEquals("video bytes", String(entries.getValue("media/abc.mp4"), StandardCharsets.UTF_8))
        assertEquals("photo preview", String(entries.getValue("media/def.thumb.jpg"), StandardCharsets.UTF_8))
        val json = String(entries.getValue("backup.json"), StandardCharsets.UTF_8)
        assertEquals(Backup.toJson(workouts, log, settings, exportedAt), json)
    }

    @Test
    fun `writeZip leaves the caller's stream open`() {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }
        Backup.writeZip(out, workouts, log, settings, exportedAt, mediaDirWithFiles())
        assertFalse(closed)
        assertTrue(Backup.isZip(out.toByteArray()))
    }

    @Test
    fun `writeZip with no media directory still produces a readable backup`() {
        val bytes = zipBytes(File(temp.root, "does-not-exist"))
        assertEquals(listOf("backup.json"), entries(bytes).keys.toList())
        assertSameData(Backup.readZipJson(ByteArrayInputStream(bytes)))
    }

    // ---- reading ----

    @Test
    fun `readZipJson returns the same workouts log and settings`() {
        val data = Backup.readZipJson(ByteArrayInputStream(zipBytes()))
        assertSameData(data)
        assertTrue(data.zipped)
    }

    @Test
    fun `readZipJson finds the json after media entries`() {
        val json = Backup.toJson(workouts, log, settings, exportedAt).toByteArray(StandardCharsets.UTF_8)
        val bytes = buildZip("media/abc.mp4" to "video".toByteArray(), "backup.json" to json)
        assertSameData(Backup.readZipJson(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `zip without backup json is rejected`() {
        val bytes = buildZip("media/abc.mp4" to "video".toByteArray(), "readme.txt" to "hi".toByteArray())
        try {
            Backup.readZipJson(ByteArrayInputStream(bytes))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Not an Exertrack backup", e.message)
        }
    }

    @Test
    fun `isZip recognises only the zip signature`() {
        assertTrue(Backup.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertFalse(Backup.isZip("{\"app\":\"Exertrack\"}".toByteArray()))
        assertFalse(Backup.isZip(byteArrayOf(0x50, 0x4B)))
        assertFalse(Backup.isZip(ByteArray(0)))
    }

    @Test
    fun `readAny handles a zip and plain json`() {
        val zip = zipBytes()
        val fromZip = Backup.readAny { ByteArrayInputStream(zip) }
        assertSameData(fromZip)
        assertTrue(fromZip.zipped)

        val json = Backup.toJson(workouts, log, settings, exportedAt).toByteArray(StandardCharsets.UTF_8)
        val fromJson = Backup.readAny { ByteArrayInputStream(json) }
        assertSameData(fromJson)
        assertFalse(fromJson.zipped)
    }

    @Test
    fun `readAny closes the stream it opened`() {
        var closed = false
        val json = Backup.toJson(workouts, log, settings, exportedAt).toByteArray(StandardCharsets.UTF_8)
        Backup.readAny {
            object : ByteArrayInputStream(json) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
        }
        assertTrue(closed)
    }

    @Test
    fun `readAny rejects content that is neither`() {
        try {
            Backup.readAny { ByteArrayInputStream("hello".toByteArray()) }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---- extracting ----

    @Test
    fun `extractZipMedia writes exactly the referenced files`() {
        val bytes = zipBytes()
        val target = temp.newFolder("restored")
        val count = Backup.extractZipMedia(ByteArrayInputStream(bytes), target)
        assertEquals(4, count)
        assertEquals(
            setOf("abc.mp4", "abc.thumb.jpg", "def.jpg", "def.thumb.jpg"),
            target.listFiles()!!.map { it.name }.toSet(),
        )
        assertEquals("video bytes", File(target, "abc.mp4").readText())
        assertEquals("photo bytes", File(target, "def.jpg").readText())
        assertNull(File(target, "orphan.jpg").takeIf { it.exists() })
    }

    @Test
    fun `extractZipMedia overwrites an existing file and creates the directory`() {
        val target = File(temp.root, "fresh/media")
        val bytes = buildZip("media/abc.mp4" to "new".toByteArray())
        assertEquals(1, Backup.extractZipMedia(ByteArrayInputStream(bytes), target))
        assertEquals("new", File(target, "abc.mp4").readText())
        assertEquals(1, Backup.extractZipMedia(ByteArrayInputStream(buildZip("media/abc.mp4" to "newer".toByteArray())), target))
        assertEquals("newer", File(target, "abc.mp4").readText())
        assertEquals(listOf("abc.mp4"), target.listFiles()!!.map { it.name })
    }

    @Test
    fun `extractZipMedia rejects traversal and nested entries`() {
        val target = temp.newFolder("guarded")
        val bytes = buildZip(
            "media/../evil" to "bad".toByteArray(),
            "media/sub/nested.jpg" to "bad".toByteArray(),
            "media/..\\evil2" to "bad".toByteArray(),
            "other/file.jpg" to "bad".toByteArray(),
            "backup.json" to "{}".toByteArray(),
            "media/ok.jpg" to "good".toByteArray(),
        )
        val count = Backup.extractZipMedia(ByteArrayInputStream(bytes), target)
        assertEquals(1, count)
        assertEquals(listOf("ok.jpg"), target.listFiles()!!.map { it.name })
        assertFalse(File(temp.root, "evil").exists())
        assertFalse(File(target.parentFile, "evil").exists())
        assertFalse(File(target, "sub").exists())
    }
}
