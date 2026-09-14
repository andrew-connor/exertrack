package com.exertrack.app.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.exertrack.app.io.Exporters
import java.io.File
import java.io.FileNotFoundException

/** Reading picked files and handing exports to the system (Save-as target URIs and the share sheet). */
object Files {
    const val AUTHORITY = "com.exertrack.app.fileprovider"

    /** Shown when a native Google Docs document was picked and no spreadsheet stream is available. */
    const val GOOGLE_SHEET_HINT =
        "This is a native Google Sheet, which Drive cannot hand over as a spreadsheet file. " +
            "In the Google Sheets app open the sheet, tap the three-dot menu, choose Share & export, " +
            "then Send a copy, pick Excel (.xlsx), and share it to Exertrack. " +
            "Or download it as .xlsx or .csv and pick that file."

    /** Streams a provider may offer for a virtual document, in order of preference. */
    private val SPREADSHEET_STREAM_TYPES = listOf(
        Exporters.MIME_XLSX,
        "text/csv",
        "text/comma-separated-values",
        "text/tab-separated-values",
    )

    /**
     * Read a picked file. Native Google Docs documents are *virtual*: they have no bytes of
     * their own, so the provider is asked for a converted spreadsheet stream instead, and a
     * clear hint is raised when it offers none.
     */
    fun readBytes(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver
        if (isVirtualDocument(context, uri)) return readVirtualDocument(resolver, uri)
        return try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Could not open file")
        } catch (e: FileNotFoundException) {
            if (isGoogleDocsType(resolver.getType(uri))) throw IllegalArgumentException(GOOGLE_SHEET_HINT)
            throw IllegalArgumentException("Could not open file: ${e.message ?: "not found"}")
        }
    }

    /** Display name of a content URI (falls back to the last path segment). */
    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
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

    // ---- virtual documents (native Google Docs / Sheets / Slides) ----

    private fun isVirtualDocument(context: Context, uri: Uri): Boolean {
        if (!DocumentsContract.isDocumentUri(context, uri)) return false
        return runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0
            } ?: false
        }.getOrDefault(false)
    }

    private fun readVirtualDocument(resolver: ContentResolver, uri: Uri): ByteArray {
        val offered = runCatching { resolver.getStreamTypes(uri, "*/*") }.getOrNull().orEmpty()
        for (mime in SPREADSHEET_STREAM_TYPES) {
            if (offered.none { it.equals(mime, ignoreCase = true) }) continue
            val bytes = runCatching {
                resolver.openTypedAssetFileDescriptor(uri, mime, null)?.use { afd ->
                    afd.createInputStream().use { it.readBytes() }
                }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        throw IllegalArgumentException(GOOGLE_SHEET_HINT)
    }

    private fun isGoogleDocsType(mime: String?): Boolean =
        mime != null && mime.startsWith("application/vnd.google-apps", ignoreCase = true)
}
