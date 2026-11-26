package io.github.kawincodes.stiksound.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.kawincodes.stiksound.core.ErrorCode
import io.github.kawincodes.stiksound.core.StikSoundException
import java.io.ByteArrayOutputStream

/** Bytes of a picked or shared file, plus the name to show for it. */
class InputFile(val name: String, val bytes: ByteArray, val mimeType: String?)

object InputReader {
    /** Reads [uri] fully, refusing anything over [maxBytes] without loading it all. */
    fun read(resolver: ContentResolver, uri: Uri, maxBytes: Int, label: String): InputFile {
        var name: String? = null
        var declaredSize: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) declaredSize = c.getLong(sizeIdx)
                }
            }
        }
        val limitMb = maxBytes / (1024 * 1024)
        declaredSize?.let {
            if (it > maxBytes) throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "$label is larger than $limitMb MB.")
        }
        val input = resolver.openInputStream(uri)
            ?: throw StikSoundException(ErrorCode.UNSUPPORTED_IMAGE, "Could not open the selected file.")
        val out = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) {
                    throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "$label is larger than $limitMb MB.")
                }
            }
        }
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: label
        return InputFile(name ?: fallbackName, out.toByteArray(), resolver.getType(uri))
    }
}
