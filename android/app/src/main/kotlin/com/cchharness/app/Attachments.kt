package com.cchharness.app

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.cchharness.core.Attachment
import com.cchharness.core.UploadSource

/** Where a composer attachment stands. */
sealed interface UploadState {
    data object Uploading : UploadState
    data class Done(val attachment: Attachment) : UploadState
    data class Failed(val message: String) : UploadState
}

/** A file the user picked for the next message; [localId] identifies it before the server assigns an id. */
data class DraftAttachment(
    val localId: Long,
    val name: String,
    val size: Long,
    val image: Boolean,
    val state: UploadState,
)

/** Turns a picker result (a content:// URI) into something uploadable. */
fun interface AttachmentResolver {
    /** Null when the file cannot be read. */
    fun resolve(uri: String): UploadSource?
}

class ContentResolverAttachments(private val resolver: ContentResolver) : AttachmentResolver {
    override fun resolve(uri: String): UploadSource? {
        val u = Uri.parse(uri)
        var name = ""
        var size = -1L
        try {
            resolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it).orEmpty() }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        } catch (e: Exception) {
            // some providers refuse the query; fall back to the URI's last segment below
        }
        if (name.isBlank()) name = u.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "file" }
        if (size < 0) {
            size = try {
                resolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }
        // Fail early if the provider cannot open it at all.
        try {
            resolver.openInputStream(u)?.close() ?: return null
        } catch (e: Exception) {
            return null
        }
        val mime = resolver.getType(u).orEmpty()
        return UploadSource(name, mime, size) { resolver.openInputStream(u) ?: throw java.io.IOException("cannot open $name") }
    }
}
