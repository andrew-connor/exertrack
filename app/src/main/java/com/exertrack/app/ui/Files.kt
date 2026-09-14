package com.exertrack.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Reading picked files and handing exports to the system (Save-as target URIs and the share sheet). */
object Files {
    const val AUTHORITY = "com.exertrack.app.fileprovider"

    fun readBytes(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not open file")

    /** Display name of a content URI (falls back to the last path segment). */
    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { return it }
                }
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    /** Write [bytes] to a URI returned by ACTION_CREATE_DOCUMENT. */
    fun writeTo(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalArgumentException("Could not write file")
    }

    /** Put [bytes] in the cache and open the system share sheet for them. */
    fun share(context: Context, bytes: ByteArray, fileName: String, mime: String) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share $fileName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
