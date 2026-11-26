package io.github.kawincodes.stiksound.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.core.graphics.scale
import io.github.kawincodes.stiksound.core.ErrorCode
import io.github.kawincodes.stiksound.core.GifDecoder
import io.github.kawincodes.stiksound.core.ImageFormat
import io.github.kawincodes.stiksound.core.ImageOps
import io.github.kawincodes.stiksound.core.ImageProbe
import io.github.kawincodes.stiksound.core.Limits
import io.github.kawincodes.stiksound.core.Sniffer
import io.github.kawincodes.stiksound.core.StikSoundException
import io.github.kawincodes.stiksound.core.computeSquareCrop
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.ensureActive

/**
 * A decoded sticker. [frames] are software ARGB_8888 bitmaps, possibly downscaled
 * from the original so their shorter side is at most 512 px (or less for very long
 * GIFs, to bound memory). [originalWidth]/[originalHeight] decide the export size.
 */
class StickerSource(
    val name: String,
    val format: ImageFormat,
    val originalWidth: Int,
    val originalHeight: Int,
    val frames: List<Bitmap>,
    val delaysMs: List<Int>,
    val hasAlpha: Boolean,
) {
    val width: Int get() = frames.first().width
    val height: Int get() = frames.first().height
    val animated: Boolean get() = frames.size > 1

    /** One loop of the animation, or 0 for a still image. */
    val loopDurationMs: Int get() = if (animated) delaysMs.sum() else 0

    /** Side of the exported square: the original's shorter side, at most 512 and even. */
    val exportSize: Int get() = computeSquareCrop(originalWidth, originalHeight).outputSize

    fun recycle() = frames.forEach { if (!it.isRecycled) it.recycle() }
}

object StickerLoader {
    /** Upper bound for all decoded GIF frames kept in memory. */
    private const val FRAME_BUDGET_BYTES = 128L * 1024 * 1024

    suspend fun load(name: String, bytes: ByteArray): StickerSource {
        if (bytes.size > Limits.MAX_IMAGE_BYTES) {
            throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "Image is larger than 20 MB.")
        }
        if (Sniffer.isVideoContainer(bytes)) {
            throw StikSoundException(ErrorCode.UNSUPPORTED_IMAGE, "Video files can't be used as a sticker yet. Use PNG, JPG, WebP or GIF.")
        }
        val probe = probe(bytes)
        return try {
            if (probe.format == ImageFormat.GIF && probe.animated) loadGif(name, bytes, probe) else loadStill(name, bytes, probe)
        } catch (e: OutOfMemoryError) {
            throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "This image is too large to edit on this device.")
        }
    }

    private fun probe(bytes: ByteArray): ImageProbe = try {
        Sniffer.probeImage(bytes)
    } catch (e: StikSoundException) {
        if (e.code != ErrorCode.UNSUPPORTED_IMAGE) throw e
        // Formats the sniffer does not know (HEIC/AVIF from the photo picker): trust the platform
        // decoder for the header, but still enforce the size limits.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) throw e
        if (w > Limits.MAX_IMAGE_DIMENSION || h > Limits.MAX_IMAGE_DIMENSION || w.toLong() * h > Limits.MAX_IMAGE_PIXELS) {
            throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "Image exceeds 8000 px or 40 megapixels.")
        }
        ImageProbe(ImageFormat.JPEG, w, h, hasAlpha = true, animated = false, frameCount = 1, loopDurationMs = 0)
    }

    private fun loadStill(name: String, bytes: ByteArray, probe: ImageProbe): StickerSource {
        val minSide = min(probe.width, probe.height)
        // Largest power-of-two subsample that keeps the shorter side >= 512.
        var sample = 1
        while (minSide / (sample * 2) >= Limits.STICKER_DIMENSION_PX) sample *= 2

        val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            try {
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(sample)
                    decoder.isMutableRequired = false
                }
            } catch (e: Exception) {
                throw StikSoundException(ErrorCode.UNSUPPORTED_IMAGE, "This image could not be decoded.", e)
            }
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw StikSoundException(ErrorCode.UNSUPPORTED_IMAGE, "This image could not be decoded.")
        }
        val argbBitmap = ensureArgb8888(decoded)
        val bitmap = downscaleToMinSide(argbBitmap, Limits.STICKER_DIMENSION_PX)
        // ImageDecoder applies EXIF rotation, so the decoded size is the true orientation.
        val rotated = (bitmap.width > bitmap.height) != (probe.width > probe.height) && probe.width != probe.height
        val ow = if (rotated) probe.height else probe.width
        val oh = if (rotated) probe.width else probe.height
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return StickerSource(
            name = name,
            format = probe.format,
            originalWidth = ow,
            originalHeight = oh,
            frames = listOf(bitmap),
            delaysMs = listOf(0),
            hasAlpha = ImageOps.hasTransparency(pixels),
        )
    }

    private suspend fun loadGif(name: String, bytes: ByteArray, probe: ImageProbe): StickerSource {
        val w = probe.width
        val h = probe.height
        var scale = min(1.0, Limits.STICKER_DIMENSION_PX.toDouble() / min(w, h))
        val frameBytes = (w * scale) * (h * scale) * 4
        val total = frameBytes * probe.frameCount
        if (total > FRAME_BUDGET_BYTES) scale *= sqrt(FRAME_BUDGET_BYTES / total)
        val tw = max(1, (w * scale).roundToInt())
        val th = max(1, (h * scale).roundToInt())

        val frames = ArrayList<Bitmap>(probe.frameCount)
        val delays = ArrayList<Int>(probe.frameCount)
        val argb = IntArray(w * h)
        var transparent = false
        try {
            GifDecoder.decode(bytes) { _, fw, fh, delayMs, rgba ->
                ImageOps.rgbaToArgb(rgba, argb)
                if (!transparent) transparent = ImageOps.hasTransparency(argb)
                val full = Bitmap.createBitmap(argb, fw, fh, Bitmap.Config.ARGB_8888)
                frames += if (tw != fw || th != fh) {
                    full.scale(tw, th, filter = true).also { if (it !== full) full.recycle() }
                } else {
                    full
                }
                delays += delayMs
            }
            coroutineContext.ensureActive()
        } catch (t: Throwable) {
            frames.forEach { it.recycle() }
            throw t
        }
        return StickerSource(name, ImageFormat.GIF, w, h, frames, delays, transparent)
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw StikSoundException(ErrorCode.UNSUPPORTED_IMAGE, "This image could not be converted.")
        bitmap.recycle()
        return copy
    }

    private fun downscaleToMinSide(bitmap: Bitmap, target: Int): Bitmap {
        val minSide = min(bitmap.width, bitmap.height)
        if (minSide <= target) return bitmap
        val s = target.toDouble() / minSide
        val scaled = bitmap.scale(
            max(1, (bitmap.width * s).roundToInt()),
            max(1, (bitmap.height * s).roundToInt()),
            filter = true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * The square the user framed, as straight-alpha ARGB pixels of [size] x [size],
     * taken from frame [frameIndex].
     */
    fun croppedPixels(source: StickerSource, frameIndex: Int, cropX: Float, cropY: Float, size: Int): IntArray {
        val frame = source.frames[frameIndex]
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        return ImageOps.cropAndFitSquare(pixels, frame.width, frame.height, cropX, cropY, size).first
    }
}
