package io.github.kawincodes.stiksound.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Where exports go, and how they reach the gallery and other apps. */
object Outputs {
    private const val ALBUM = "StikSound"

    enum class Kind(val mimeType: String) { VIDEO("video/mp4"), WEBP("image/webp") }

    /** Scratch file in the app cache, shared through [FileProvider]. */
    fun cacheFile(context: Context, fileName: String): File =
        File(File(context.cacheDir, "exports").apply { mkdirs() }, fileName)

    /** Saving needs a runtime permission only on Android 9 and older. */
    val needsLegacyStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Copies [file] into Movies/StikSound or Pictures/StikSound and returns its gallery Uri. */
    fun saveToGallery(context: Context, file: File, kind: Kind): Uri {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, dir) = when (kind) {
                Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
                Kind.WEBP -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$ALBUM")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("The gallery refused the new file.")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("Could not write to the gallery.")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(
            if (kind == Kind.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        val target = uniqueFile(File(base, ALBUM).apply { mkdirs() }, file.name)
        file.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(kind.mimeType), null)
        return Uri.fromFile(target)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 1
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (candidate.exists()) candidate = File(dir, "$stem-${n++}.$ext")
        return candidate
    }

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun shareIntent(context: Context, file: File, kind: Kind, chooserTitle: String): Intent {
        val uri = contentUri(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = kind.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /** `<safe-name>-10s.mp4`, as StikSound names exports. */
    fun safeName(raw: String): String {
        val cleaned = raw.substringBeforeLast('.')
            .lowercase()
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""[^a-z0-9._-]"""), "")
            .trim('-', '.')
            .take(40)
        return cleaned.ifEmpty { "stiksound" }
    }
}
