package io.github.kawincodes.stiksound.media

import android.graphics.Bitmap
import android.os.Build
import io.github.kawincodes.stiksound.core.ErrorCode
import io.github.kawincodes.stiksound.core.Limits
import io.github.kawincodes.stiksound.core.StikSoundException
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Static WhatsApp sticker: 512 x 512 WebP, transparency kept, at most 100 KB
 * (StikSound `standard-webp` profile). Tries lossless first, then lowers lossy
 * quality until the file fits.
 */
object WebpExporter {
    fun export(sticker: StickerSource, cropX: Float, cropY: Float, output: File): File {
        val size = Limits.STICKER_DIMENSION_PX
        val pixels = StickerLoader.croppedPixels(sticker, 0, cropX, cropY, size)
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        try {
            val bytes = encodeWithinLimit(bitmap)
            output.parentFile?.mkdirs()
            output.writeBytes(bytes)
            return output
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun encodeWithinLimit(bitmap: Bitmap): ByteArray {
        val attempts: List<Pair<Bitmap.CompressFormat, Int>> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            listOf(Bitmap.CompressFormat.WEBP_LOSSLESS to 100) +
                (95 downTo 5 step 10).map { Bitmap.CompressFormat.WEBP_LOSSY to it }
        } else {
            // Before Android 11, quality 100 means lossless (on Android 10) and anything lower is lossy.
            listOf(Bitmap.CompressFormat.WEBP to 100) + (95 downTo 5 step 10).map { Bitmap.CompressFormat.WEBP to it }
        }
        for ((format, quality) in attempts) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(format, quality, out)) continue
            if (out.size() <= Limits.MAX_STATIC_WEBP_BYTES) return out.toByteArray()
        }
        throw StikSoundException(ErrorCode.ENCODE_FAILED, "Could not fit the WebP sticker under 100 KB.")
    }
}
