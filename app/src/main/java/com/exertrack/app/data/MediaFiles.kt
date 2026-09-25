package com.exertrack.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * Photos and videos attached to exercises live as plain files under `filesDir/media/`, copied
 * from whatever the user picked so the app never depends on another app's permission grant.
 * Each media file has a small JPEG preview next to it ([RowMedia.thumbFile]).
 *
 * A pick is first copied into `media/pending/`; saving the row calls [commit], which moves it
 * into place. Files nobody references any more are removed by [Repository.sweepMedia].
 * Everything here does blocking file work: call it off the main thread.
 */
object MediaFiles {
    private const val TAG = "MediaFiles"
    const val PENDING_DIR = "pending"
    /** Longest side of the generated preview, in pixels. */
    const val THUMB_MAX = 640

    fun mediaDir(context: Context): File = File(context.filesDir, "media")

    fun file(context: Context, media: RowMedia): File = File(mediaDir(context), media.file)

    fun thumbFile(context: Context, media: RowMedia): File = File(mediaDir(context), media.thumbFile)

    /**
     * Copy the picked photo or video into the pending area and build its preview.
     * @throws IllegalArgumentException when the content cannot be read or is neither an image nor a video.
     */
    fun importPending(context: Context, uri: Uri): RowMedia {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        val kind = when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("image/") -> MediaKind.IMAGE
            else -> throw IllegalArgumentException("Only photos and videos can be attached")
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (kind == MediaKind.VIDEO) "mp4" else "jpg"
        val pendingDir = File(mediaDir(context), PENDING_DIR).apply { mkdirs() }
        val name = "${newId()}.$ext"
        val target = File(pendingDir, name)
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("Could not open the selected file")
        } catch (e: Exception) {
            target.delete()
            throw IllegalArgumentException(e.message ?: "Could not copy the selected file")
        }
        if (target.length() == 0L) {
            target.delete()
            throw IllegalArgumentException("The selected file is empty")
        }
        val media = RowMedia(file = "$PENDING_DIR/$name", kind = kind)
        writeThumbnail(context, media)
        return media
    }

    /** Move pending media into permanent storage. Media that is already permanent is returned unchanged. */
    fun commit(context: Context, media: RowMedia): RowMedia {
        if (!media.isPending) return media
        val dir = mediaDir(context)
        val finalName = media.file.removePrefix("$PENDING_DIR/")
        val committed = RowMedia(finalName, media.kind)
        move(File(dir, media.file), File(dir, committed.file))
        move(File(dir, media.thumbFile), File(dir, committed.thumbFile))
        return committed
    }

    /** Delete the media file and its preview (pending or permanent). */
    fun discard(context: Context, media: RowMedia) {
        file(context, media).delete()
        thumbFile(context, media).delete()
    }

    /** Copy permanent media under a new name (used when a workout is duplicated). */
    fun copy(context: Context, media: RowMedia): RowMedia? {
        val source = file(context, media)
        if (!source.isFile) return null
        val copied = RowMedia("${newId()}.${source.extension}", media.kind)
        return try {
            source.copyTo(file(context, copied), overwrite = true)
            val thumb = thumbFile(context, media)
            if (thumb.isFile) thumb.copyTo(thumbFile(context, copied), overwrite = true)
            copied
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy media", e)
            discard(context, copied)
            null
        }
    }

    /** The preview bitmap, or null when it is missing or unreadable. */
    fun loadThumbnail(context: Context, media: RowMedia): Bitmap? {
        val thumb = thumbFile(context, media)
        if (!thumb.isFile) {
            // A preview lost to an interrupted import can be rebuilt from the media itself.
            runCatching { writeThumbnail(context, media) }
        }
        return runCatching { BitmapFactory.decodeFile(thumb.path) }.getOrNull()
    }

    /** The full image scaled so its longest side is at most [maxDim] pixels, rotated per EXIF. */
    fun loadImage(context: Context, media: RowMedia, maxDim: Int = 2048): Bitmap? {
        val source = file(context, media)
        if (!source.isFile) return null
        return runCatching { decodeScaled(source, maxDim) }.getOrNull()
    }

    /** Remove media dir entries no row references. [referenced] holds media file names and thumb names. */
    fun sweep(mediaDir: File, referenced: Set<String>) {
        val files = mediaDir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) continue
            if (f.name !in referenced) f.delete()
        }
    }

    /** Drop every pending pick (on startup nothing can still be waiting for a save). */
    fun clearPending(mediaDir: File) {
        File(mediaDir, PENDING_DIR).listFiles()?.forEach { it.delete() }
    }

    // ---- internals ----

    private fun move(from: File, to: File) {
        if (!from.isFile) return
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun writeThumbnail(context: Context, media: RowMedia) {
        val source = file(context, media)
        val bitmap = when (media.kind) {
            MediaKind.IMAGE -> decodeScaled(source, THUMB_MAX)
            MediaKind.VIDEO -> videoFrame(source)
        } ?: return
        val target = thumbFile(context, media)
        target.parentFile?.mkdirs()
        try {
            FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write preview", e)
            target.delete()
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeScaled(source: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: return null
        val scaled = scaleDown(decoded, maxDim)
        return applyExifRotation(source, scaled)
    }

    private fun videoFrame(source: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.path)
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()
            frame?.let { scaleDown(it, THUMB_MAX) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a video frame", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
