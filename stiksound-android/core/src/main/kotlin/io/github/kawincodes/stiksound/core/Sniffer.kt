package io.github.kawincodes.stiksound.core

enum class ImageFormat(val extension: String) { PNG("png"), JPEG("jpg"), WEBP("webp"), GIF("gif") }

enum class AudioFormat { WAV, OGG, MP3, M4A, AAC, FLAC }

data class ImageProbe(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val animated: Boolean,
    val frameCount: Int,
    val loopDurationMs: Int,
)

/**
 * Identifies files by their magic bytes and enforces the size limits before any
 * decoder sees the data. Ported from StikSound `sniffers.ts`.
 */
object Sniffer {
    private fun u8(b: ByteArray, o: Int): Int = if (o in b.indices) b[o].toInt() and 0xFF else 0
    private fun u16le(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u16be(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)
    private fun u32le(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24))
    private fun u32be(b: ByteArray, o: Int): Long =
        ((u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong())
    private fun ascii(b: ByteArray, start: Int, len: Int): String =
        if (start + len <= b.size) String(b, start, len, Charsets.ISO_8859_1) else ""

    private fun assertDimensions(width: Long, height: Long) {
        if (width <= 0 || height <= 0) fail(ErrorCode.UNSUPPORTED_IMAGE, "Image has zero dimensions.")
        if (width > Limits.MAX_IMAGE_DIMENSION || height > Limits.MAX_IMAGE_DIMENSION) {
            fail(ErrorCode.FILE_TOO_LARGE, "Image dimension ${width}x$height exceeds 8000 px.")
        }
        if (width * height > Limits.MAX_IMAGE_PIXELS) {
            fail(ErrorCode.FILE_TOO_LARGE, "Image exceeds 40 megapixels (${width}x$height).")
        }
    }

    fun probeImage(bytes: ByteArray): ImageProbe {
        if (bytes.size < 12) fail(ErrorCode.UNSUPPORTED_IMAGE, "Image buffer too small.")
        if (bytes.size > Limits.MAX_IMAGE_BYTES) fail(ErrorCode.FILE_TOO_LARGE, "Image exceeds 20 MB limit.")

        // PNG
        if (u8(bytes, 0) == 0x89 && ascii(bytes, 1, 3) == "PNG" && u8(bytes, 4) == 0x0D && u8(bytes, 5) == 0x0A) {
            if (bytes.size < 29 || ascii(bytes, 12, 4) != "IHDR") fail(ErrorCode.UNSUPPORTED_IMAGE, "Malformed PNG IHDR chunk.")
            val width = u32be(bytes, 16)
            val height = u32be(bytes, 20)
            assertDimensions(width, height)
            val colorType = u8(bytes, 25)
            var hasAlpha = colorType == 4 || colorType == 6
            var pos = 8
            while (pos + 12 <= bytes.size && pos < 65_536) {
                val len = u32be(bytes, pos)
                val tag = ascii(bytes, pos + 4, 4)
                if (tag == "tRNS") { hasAlpha = true; break }
                if (tag == "IDAT" || tag == "IEND") break
                if (len > Int.MAX_VALUE - 12L - pos) break
                pos += 12 + len.toInt()
            }
            return ImageProbe(ImageFormat.PNG, width.toInt(), height.toInt(), hasAlpha, false, 1, 0)
        }

        // JPEG
        if (u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF) {
            var offset = 2
            while (offset + 4 <= bytes.size) {
                if (u8(bytes, offset) != 0xFF) break
                val marker = u8(bytes, offset + 1)
                if (marker == 0xD9 || marker == 0xDA) break
                if (marker == 0x00 || marker == 0xFF || marker in 0xD0..0xD8) { offset += if (marker == 0xFF) 1 else 2; continue }
                val segLen = u16be(bytes, offset + 2)
                if (segLen < 2 || offset + 2 + segLen > bytes.size) break
                if (marker in 0xC0..0xC3 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF) {
                    val height = u16be(bytes, offset + 5).toLong()
                    val width = u16be(bytes, offset + 7).toLong()
                    assertDimensions(width, height)
                    return ImageProbe(ImageFormat.JPEG, width.toInt(), height.toInt(), false, false, 1, 0)
                }
                offset += 2 + segLen
            }
            fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated or invalid JPEG markers.")
        }

        // WebP
        if (ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP") {
            if (bytes.size < 20) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated WebP.")
            when (ascii(bytes, 12, 4)) {
                "VP8X" -> if (bytes.size >= 30) {
                    val flags = u8(bytes, 20)
                    val width = 1L + (u8(bytes, 24) or (u8(bytes, 25) shl 8) or (u8(bytes, 26) shl 16))
                    val height = 1L + (u8(bytes, 27) or (u8(bytes, 28) shl 8) or (u8(bytes, 29) shl 16))
                    assertDimensions(width, height)
                    val animated = flags and 0x02 != 0
                    return ImageProbe(ImageFormat.WEBP, width.toInt(), height.toInt(), flags and 0x10 != 0, animated, if (animated) 2 else 1, 0)
                }
                "VP8L" -> if (bytes.size >= 25 && u8(bytes, 20) == 0x2F) {
                    val bits = u32le(bytes, 21)
                    val width = (bits and 0x3FFF) + 1
                    val height = ((bits shr 14) and 0x3FFF) + 1
                    assertDimensions(width, height)
                    return ImageProbe(ImageFormat.WEBP, width.toInt(), height.toInt(), (bits shr 28) and 1L == 1L, false, 1, 0)
                }
                "VP8 " -> if (bytes.size >= 30) {
                    val width = (u16le(bytes, 26) and 0x3FFF).toLong()
                    val height = (u16le(bytes, 28) and 0x3FFF).toLong()
                    assertDimensions(width, height)
                    return ImageProbe(ImageFormat.WEBP, width.toInt(), height.toInt(), false, false, 1, 0)
                }
            }
            fail(ErrorCode.UNSUPPORTED_IMAGE, "Unsupported WebP chunk layout.")
        }

        // GIF
        if (GifDecoder.isGif(bytes)) {
            val info = GifDecoder.probe(bytes)
            return ImageProbe(
                ImageFormat.GIF,
                info.width,
                info.height,
                info.hasTransparency,
                info.animated,
                info.frameCount,
                info.loopDurationMs,
            )
        }

        fail(ErrorCode.UNSUPPORTED_IMAGE, "Unrecognized image format. Use PNG, JPG, WebP or GIF.")
    }

    /** Recognizes the audio containers Android can decode. Returns null when unknown. */
    fun sniffAudio(bytes: ByteArray): AudioFormat? {
        if (bytes.size < 12) return null
        if (Wav.isWav(bytes)) return AudioFormat.WAV
        if (ascii(bytes, 0, 4) == "OggS") return AudioFormat.OGG
        if (ascii(bytes, 0, 4) == "fLaC") return AudioFormat.FLAC
        if (ascii(bytes, 0, 3) == "ID3") return AudioFormat.MP3
        if (ascii(bytes, 4, 4) == "ftyp") return AudioFormat.M4A
        if (u8(bytes, 0) == 0xFF && (u8(bytes, 1) and 0xF6) == 0xF0) return AudioFormat.AAC
        if (u8(bytes, 0) == 0xFF && (u8(bytes, 1) and 0xE0) == 0xE0) return AudioFormat.MP3
        return null
    }

    /** True for MP4/MOV/WebM video containers (but not audio-only M4A). */
    fun isVideoContainer(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val box = ascii(bytes, 4, 4)
        if (box == "ftyp") {
            val brand = ascii(bytes, 8, 4)
            return brand != "M4A " && brand != "M4B " && brand != "M4P "
        }
        return u8(bytes, 0) == 0x1A && u8(bytes, 1) == 0x45 && u8(bytes, 2) == 0xDF && u8(bytes, 3) == 0xA3
    }
}
