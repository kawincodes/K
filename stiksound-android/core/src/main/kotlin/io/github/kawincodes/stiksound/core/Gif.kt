package io.github.kawincodes.stiksound.core

import kotlin.math.max
import kotlin.math.roundToInt

/** Summary of a GIF read without decoding pixels. */
data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    /** Length of one loop, using the same delay normalization as playback. */
    val loopDurationMs: Int,
    val hasTransparency: Boolean,
) {
    val animated: Boolean get() = frameCount > 1
}

class DecodedGifFrame(val width: Int, val height: Int, val delayMs: Int, val rgba: ByteArray)

/**
 * Normalizes a GIF frame delay the way browsers play it: a missing delay or one
 * under 20 ms (0 or 1 centisecond) plays at 100 ms.
 */
fun gifFrameDelayMs(rawDelayMs: Int?): Int =
    if (rawDelayMs == null || rawDelayMs < 20) 100 else rawDelayMs

/** Memory guards, ported from `gif-limits.ts`. */
object GifLimits {
    private const val RGBA = 4L

    /** Returns the canvas size in RGBA bytes, or throws when the canvas is too large. */
    fun assertCanvas(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) fail(ErrorCode.UNSUPPORTED_IMAGE, "GIF has zero dimensions.")
        if (width > Limits.MAX_IMAGE_DIMENSION || height > Limits.MAX_IMAGE_DIMENSION) {
            fail(ErrorCode.FILE_TOO_LARGE, "GIF dimension ${width}x$height exceeds 8000 px.")
        }
        val pixels = width.toLong() * height
        if (pixels > Limits.MAX_IMAGE_PIXELS) fail(ErrorCode.FILE_TOO_LARGE, "GIF exceeds 40 megapixels.")
        if (pixels > Limits.MAX_GIF_FRAME_PIXELS) {
            fail(ErrorCode.FILE_TOO_LARGE, "GIF canvas ${width}x$height exceeds the GIF pixel limit.")
        }
        val bytes = pixels * RGBA
        if (bytes > Limits.MAX_GIF_CANVAS_BYTES) {
            fail(ErrorCode.FILE_TOO_LARGE, "GIF canvas allocation of $bytes bytes exceeds the GIF limit.")
        }
        return bytes
    }

    /** Returns the frame's pixel count, or throws when the frame is invalid or too large. */
    fun assertFrameDescriptor(
        canvasWidth: Int,
        canvasHeight: Int,
        left: Int,
        top: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Int {
        if (frameWidth <= 0 || frameHeight <= 0) fail(ErrorCode.UNSUPPORTED_IMAGE, "GIF frame has zero dimensions.")
        if (frameWidth > Limits.MAX_IMAGE_DIMENSION || frameHeight > Limits.MAX_IMAGE_DIMENSION) {
            fail(ErrorCode.FILE_TOO_LARGE, "GIF frame dimension ${frameWidth}x$frameHeight exceeds 8000 px.")
        }
        val pixels = frameWidth.toLong() * frameHeight
        if (pixels > Limits.MAX_GIF_FRAME_PIXELS) {
            fail(ErrorCode.FILE_TOO_LARGE, "GIF frame ${frameWidth}x$frameHeight exceeds the GIF pixel limit.")
        }
        if (left + frameWidth > canvasWidth || top + frameHeight > canvasHeight) {
            fail(
                ErrorCode.UNSUPPORTED_IMAGE,
                "GIF frame bounds ($left,$top ${frameWidth}x$frameHeight) exceed the ${canvasWidth}x$canvasHeight canvas.",
            )
        }
        return pixels.toInt()
    }

    /** Transient bytes held while one frame decodes (canvas, LZW data, indices, disposal copy). */
    fun frameWorkingBytes(
        canvasBytes: Long,
        lzwDataBytes: Int,
        framePixels: Int,
        interlaced: Boolean,
        disposalMethod: Int,
    ): Long {
        val indexBytes = framePixels.toLong() * if (interlaced) 2 else 1
        val previousBytes = if (disposalMethod == 3) framePixels * RGBA else 0L
        return canvasBytes + lzwDataBytes + indexBytes + previousBytes
    }

    fun assertDecodedMemory(retainedBytes: Long, nextRetainedBytes: Long, workingBytes: Long) {
        if (retainedBytes + nextRetainedBytes + workingBytes > Limits.MAX_GIF_DECODED_BYTES) {
            fail(ErrorCode.FILE_TOO_LARGE, "GIF decoded RGBA memory exceeds the ${Limits.MAX_GIF_DECODED_BYTES} byte limit.")
        }
    }
}

/**
 * Pure Kotlin GIF87a/GIF89a decoder, ported from StikSound `gif-decoder.ts`.
 * It composites each frame onto a full RGBA canvas and honours frame offsets,
 * transparency, interlacing and the four disposal methods.
 */
object GifDecoder {
    private fun u8(b: ByteArray, o: Int): Int = if (o < b.size) b[o].toInt() and 0xFF else 0
    private fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

    fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        val sig = String(bytes, 0, 6, Charsets.ISO_8859_1)
        return sig == "GIF87a" || sig == "GIF89a"
    }

    private fun checkHeader(bytes: ByteArray) {
        if (bytes.size < 14) fail(ErrorCode.UNSUPPORTED_IMAGE, "GIF buffer too small.")
        if (bytes.size > Limits.MAX_IMAGE_BYTES) fail(ErrorCode.FILE_TOO_LARGE, "GIF exceeds 20 MB limit.")
        if (!isGif(bytes)) fail(ErrorCode.UNSUPPORTED_IMAGE, "Not a GIF87a/GIF89a stream.")
    }

    /** Walks a sub-block chain; returns the position after it and the payload size. */
    private fun measureSubBlocks(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var pos = start
        var length = 0
        while (pos < bytes.size) {
            val subLen = u8(bytes, pos++)
            if (subLen == 0) return pos to length
            if (subLen > bytes.size - pos) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF data sub-block.")
            pos += subLen
            length += subLen
        }
        fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF sub-block chain.")
    }

    /** Reads frame count, loop length and transparency without decoding pixels. */
    fun probe(bytes: ByteArray): GifInfo {
        checkHeader(bytes)
        val width = u16(bytes, 6)
        val height = u16(bytes, 8)
        val canvasBytes = GifLimits.assertCanvas(width, height)
        val packed = u8(bytes, 10)
        val hasGlobalPalette = packed and 0x80 != 0
        var pos = 13
        if (hasGlobalPalette) {
            val size = 3 * (1 shl ((packed and 0x07) + 1))
            if (pos + size > bytes.size) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF global color table.")
            pos += size
        }

        var frameCount = 0
        var durationMs = 0
        var hasAlpha = false
        var pendingDelayMs = 100
        var pendingDisposal = 0
        var retained = 0L

        while (pos < bytes.size) {
            when (u8(bytes, pos++)) {
                0x3B -> break
                0x21 -> {
                    if (pos >= bytes.size) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF extension.")
                    val label = u8(bytes, pos++)
                    if (label == 0xF9) {
                        val blockSize = u8(bytes, pos)
                        if (blockSize < 4 || pos + 1 + blockSize > bytes.size) {
                            fail(ErrorCode.UNSUPPORTED_IMAGE, "Malformed GIF graphic control extension.")
                        }
                        val gce = u8(bytes, pos + 1)
                        if (gce and 0x01 != 0) hasAlpha = true
                        pendingDisposal = (gce shr 2) and 0x07
                        pendingDelayMs = gifFrameDelayMs(u16(bytes, pos + 2) * 10)
                        pos += 1 + blockSize
                    }
                    pos = measureSubBlocks(bytes, pos).first
                }
                0x2C -> {
                    if (pos + 9 > bytes.size) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF image descriptor.")
                    val left = u16(bytes, pos)
                    val top = u16(bytes, pos + 2)
                    val fw = u16(bytes, pos + 4)
                    val fh = u16(bytes, pos + 6)
                    val imgPacked = u8(bytes, pos + 8)
                    pos += 9
                    val framePixels = GifLimits.assertFrameDescriptor(width, height, left, top, fw, fh)
                    val hasLocalPalette = imgPacked and 0x80 != 0
                    val interlaced = imgPacked and 0x40 != 0
                    if (hasLocalPalette) {
                        val size = 3 * (1 shl ((imgPacked and 0x07) + 1))
                        if (pos + size > bytes.size) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF local color table.")
                        pos += size
                    }
                    if (pos >= bytes.size) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF image data.")
                    pos++ // LZW minimum code size
                    val (end, dataLength) = measureSubBlocks(bytes, pos)
                    pos = end
                    frameCount++
                    if (frameCount > Limits.MAX_GIF_FRAMES) {
                        fail(ErrorCode.FILE_TOO_LARGE, "GIF exceeds the ${Limits.MAX_GIF_FRAMES} frame limit.")
                    }
                    if (hasGlobalPalette || hasLocalPalette) {
                        val working = GifLimits.frameWorkingBytes(canvasBytes, dataLength, framePixels, interlaced, pendingDisposal)
                        GifLimits.assertDecodedMemory(0, canvasBytes, working)
                        retained += canvasBytes
                        durationMs += pendingDelayMs
                    }
                    pendingDelayMs = 100
                    pendingDisposal = 0
                }
                else -> break
            }
        }
        if (frameCount == 0) fail(ErrorCode.UNSUPPORTED_IMAGE, "GIF contains no frames.")
        return GifInfo(width, height, frameCount, if (frameCount > 1) durationMs else 0, hasAlpha)
    }

    /**
     * Decodes every frame and hands the composited canvas to [onFrame].
     * The `rgba` array is reused for the next frame: copy it if you keep it.
     * Returns the number of frames delivered.
     */
    fun decode(
        bytes: ByteArray,
        onFrame: (index: Int, width: Int, height: Int, delayMs: Int, rgba: ByteArray) -> Unit,
    ): Int {
        checkHeader(bytes)
        val width = u16(bytes, 6)
        val height = u16(bytes, 8)
        val canvasBytes = GifLimits.assertCanvas(width, height)

        val packed = u8(bytes, 10)
        var pos = 13
        var gct: ByteArray? = null
        if (packed and 0x80 != 0) {
            val size = 3 * (1 shl ((packed and 0x07) + 1))
            if (pos + size > bytes.size) fail(ErrorCode.UNSUPPORTED_IMAGE, "Truncated GIF global color table.")
            gct = bytes.copyOfRange(pos, pos + size)
            pos += size
        }

        val canvas = ByteArray(canvasBytes.toInt())
        var delivered = 0
        var descriptors = 0
        var disposal = 0
        var transparentFlag = false
        var transparentIndex = 0
        var delayMs = 100

        loop@ while (pos < bytes.size) {
            when (u8(bytes, pos++)) {
                0x3B -> break@loop
                0x21 -> {
                    if (pos >= bytes.size) break@loop
                    val label = u8(bytes, pos++)
                    if (label == 0xF9 && pos < bytes.size) {
                        val blockSize = u8(bytes, pos)
                        if (blockSize < 4 || pos + 1 + blockSize > bytes.size) break@loop
                        val gce = u8(bytes, pos + 1)
                        disposal = (gce shr 2) and 0x07
                        transparentFlag = gce and 0x01 != 0
                        delayMs = gifFrameDelayMs(u16(bytes, pos + 2) * 10)
                        transparentIndex = u8(bytes, pos + 4)
                        pos += 1 + blockSize
                    }
                    pos = skipSubBlocksLenient(bytes, pos)
                }
                0x2C -> {
                    if (descriptors >= Limits.MAX_GIF_FRAMES) {
                        fail(ErrorCode.FILE_TOO_LARGE, "GIF exceeds the ${Limits.MAX_GIF_FRAMES} frame limit.")
                    }
                    descriptors++
                    if (pos + 9 > bytes.size) break@loop
                    val left = u16(bytes, pos)
                    val top = u16(bytes, pos + 2)
                    val fw = u16(bytes, pos + 4)
                    val fh = u16(bytes, pos + 6)
                    val imgPacked = u8(bytes, pos + 8)
                    pos += 9
                    val framePixels = GifLimits.assertFrameDescriptor(width, height, left, top, fw, fh)

                    val interlaced = imgPacked and 0x40 != 0
                    var palette = gct
                    if (imgPacked and 0x80 != 0) {
                        val size = 3 * (1 shl ((imgPacked and 0x07) + 1))
                        if (pos + size > bytes.size) break@loop
                        palette = bytes.copyOfRange(pos, pos + size)
                        pos += size
                    }
                    if (pos >= bytes.size) break@loop
                    val minCodeSize = u8(bytes, pos++)

                    // Collect the LZW sub-blocks.
                    val dataStart = pos
                    var dataLen = 0
                    var scan = pos
                    while (scan < bytes.size) {
                        val subLen = u8(bytes, scan++)
                        if (subLen == 0) break
                        val end = minOf(bytes.size, scan + subLen)
                        dataLen += end - scan
                        scan = end
                    }
                    pos = scan

                    if (palette == null) {
                        disposal = 0; transparentFlag = false; transparentIndex = 0; delayMs = 100
                        continue@loop
                    }

                    GifLimits.assertDecodedMemory(
                        0,
                        0,
                        GifLimits.frameWorkingBytes(canvasBytes, dataLen, framePixels, interlaced, disposal),
                    )
                    val lzwData = ByteArray(dataLen)
                    var off = 0
                    var p = dataStart
                    while (p < bytes.size && off < dataLen) {
                        val subLen = u8(bytes, p++)
                        if (subLen == 0) break
                        val n = minOf(subLen, bytes.size - p, dataLen - off)
                        System.arraycopy(bytes, p, lzwData, off, n)
                        off += n
                        p += subLen
                    }

                    var indices = lzwDecode(minCodeSize, lzwData, framePixels)
                    if (interlaced) indices = deinterlace(indices, fw, fh)

                    var previous: ByteArray? = null
                    if (disposal == 3) {
                        previous = ByteArray(framePixels * 4)
                        for (y in 0 until fh) {
                            System.arraycopy(canvas, ((top + y) * width + left) * 4, previous, y * fw * 4, fw * 4)
                        }
                    }

                    for (y in 0 until fh) {
                        val cy = top + y
                        for (x in 0 until fw) {
                            val idx = indices[y * fw + x].toInt() and 0xFF
                            if (transparentFlag && idx == transparentIndex) continue
                            val pal = idx * 3
                            if (pal + 2 >= palette.size) continue
                            val dst = (cy * width + left + x) * 4
                            canvas[dst] = palette[pal]
                            canvas[dst + 1] = palette[pal + 1]
                            canvas[dst + 2] = palette[pal + 2]
                            canvas[dst + 3] = 0xFF.toByte()
                        }
                    }

                    onFrame(delivered, width, height, delayMs, canvas)
                    delivered++

                    if (disposal == 2) {
                        for (y in 0 until fh) {
                            val rowStart = ((top + y) * width + left) * 4
                            canvas.fill(0, rowStart, rowStart + fw * 4)
                        }
                    } else if (disposal == 3 && previous != null) {
                        for (y in 0 until fh) {
                            System.arraycopy(previous, y * fw * 4, canvas, ((top + y) * width + left) * 4, fw * 4)
                        }
                    }

                    disposal = 0; transparentFlag = false; transparentIndex = 0; delayMs = 100
                }
                else -> break@loop
            }
        }

        if (delivered == 0) fail(ErrorCode.UNSUPPORTED_IMAGE, "GIF contained zero decodable frames.")
        return delivered
    }

    /** Decodes every frame into its own RGBA buffer, with the upstream memory accounting. */
    fun decodeAll(bytes: ByteArray): List<DecodedGifFrame> {
        val frames = ArrayList<DecodedGifFrame>()
        var retained = 0L
        decode(bytes) { _, w, h, delay, rgba ->
            GifLimits.assertDecodedMemory(retained, rgba.size.toLong(), 0)
            retained += rgba.size
            frames += DecodedGifFrame(w, h, delay, rgba.copyOf())
        }
        return frames
    }

    private fun skipSubBlocksLenient(bytes: ByteArray, start: Int): Int {
        var pos = start
        while (pos < bytes.size) {
            val subLen = u8(bytes, pos++)
            if (subLen == 0) break
            if (subLen > bytes.size - pos) return bytes.size
            pos += subLen
        }
        return pos
    }

    private fun lzwDecode(minCodeSize: Int, data: ByteArray, pixelCount: Int): ByteArray {
        if (minCodeSize < 1 || minCodeSize > 11) {
            fail(ErrorCode.UNSUPPORTED_IMAGE, "Invalid GIF LZW minimum code size: $minCodeSize.")
        }
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        val out = ByteArray(pixelCount)
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)
        for (i in 0 until clearCode) suffix[i] = i.toByte()

        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        var nextCode = eoiCode + 1
        var oldCode = -1
        var firstChar = 0
        var bitBuf = 0
        var bitCount = 0
        var bytePos = 0
        var outPos = 0

        while (outPos < pixelCount) {
            while (bitCount < codeSize && bytePos < data.size) {
                bitBuf = bitBuf or ((data[bytePos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            if (bitCount < codeSize) break
            val code = bitBuf and codeMask
            bitBuf = bitBuf ushr codeSize
            bitCount -= codeSize

            if (code == clearCode) {
                codeSize = minCodeSize + 1
                codeMask = (1 shl codeSize) - 1
                nextCode = eoiCode + 1
                oldCode = -1
                continue
            }
            if (code == eoiCode) break

            if (oldCode == -1) {
                if (code >= clearCode) break
                out[outPos++] = code.toByte()
                oldCode = code
                firstChar = code
                continue
            }

            val inCode = code
            var cur = code
            var top = 0
            if (cur >= nextCode) {
                if (cur > nextCode) break
                stack[top++] = firstChar.toByte()
                cur = oldCode
            }
            while (cur >= clearCode && top < stack.size) {
                stack[top++] = suffix[cur]
                cur = prefix[cur]
            }
            if (cur < 0 || cur >= clearCode) break

            firstChar = cur and 0xFF
            if (top >= stack.size) break
            stack[top++] = firstChar.toByte()

            if (nextCode < 4096) {
                prefix[nextCode] = oldCode
                suffix[nextCode] = firstChar.toByte()
                nextCode++
                if (nextCode and codeMask == 0 && nextCode < 4096) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            while (top > 0 && outPos < pixelCount) {
                out[outPos++] = stack[--top]
            }
            oldCode = inCode
        }
        return out
    }

    private fun deinterlace(indices: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(indices.size)
        val starts = intArrayOf(0, 4, 2, 1)
        val steps = intArrayOf(8, 8, 4, 2)
        var srcRow = 0
        for (pass in 0 until 4) {
            var y = starts[pass]
            while (y < height) {
                if (srcRow < height) {
                    System.arraycopy(indices, srcRow * width, out, y * width, width)
                    srcRow++
                }
                y += steps[pass]
            }
        }
        return out
    }
}

/** One video frame to encode: which source frame to show, and when. */
data class ScheduledFrame(
    val frameIndex: Int,
    val timestampUs: Long,
    val durationUs: Long,
    val keyFrame: Boolean = false,
)

/** Frame timing for the exported video, ported from `gif-scheduler.ts`. */
object FrameScheduler {
    /** Loops GIF frames (given their delays) to cover [targetDurationMs]. */
    fun scheduleGif(delaysMs: List<Int>, targetDurationMs: Int): List<ScheduledFrame> {
        if (delaysMs.isEmpty()) fail(ErrorCode.UNSUPPORTED_IMAGE, "Cannot schedule zero GIF frames.")
        val cappedMs = targetDurationMs.coerceIn(Limits.MIN_TRIM_DURATION_MS, Limits.DEFAULT_VIDEO_MAX_AUDIO_MS)
        val targetUs = cappedMs * 1000L
        val out = ArrayList<ScheduledFrame>()
        var cursor = 0L
        var i = 0
        while (cursor < targetUs) {
            val index = i % delaysMs.size
            val frameUs = max(10_000L, delaysMs[index] * 1000L)
            val dur = minOf(frameUs, targetUs - cursor)
            out += ScheduledFrame(index, cursor, dur)
            cursor += dur
            i++
        }
        return out
    }

    /**
     * Resamples a GIF schedule onto a steady frame rate so hardware encoders advance
     * cleanly. Every frame where a new GIF frame starts is marked as a key frame.
     */
    fun resampleForEncoder(scheduled: List<ScheduledFrame>, durationMs: Int, fps: Int = Limits.GIF_VIDEO_FPS): List<ScheduledFrame> {
        if (scheduled.isEmpty()) fail(ErrorCode.UNSUPPORTED_IMAGE, "Cannot resample zero GIF frames.")
        val totalUs = max((1_000_000.0 / fps).roundToInt().toLong(), durationMs * 1000L)
        val out = ArrayList<ScheduledFrame>()
        var schedIdx = 0
        var frame = 0
        while (true) {
            val tUs = Math.round(frame * 1_000_000.0 / fps)
            if (tUs >= totalUs) break
            while (schedIdx < scheduled.size - 1 &&
                tUs >= scheduled[schedIdx].timestampUs + scheduled[schedIdx].durationUs
            ) {
                schedIdx++
            }
            val nextUs = minOf(totalUs, Math.round((frame + 1) * 1_000_000.0 / fps))
            val frameIndex = scheduled[schedIdx].frameIndex
            val previous = out.lastOrNull()
            out += ScheduledFrame(
                frameIndex = frameIndex,
                timestampUs = tUs,
                durationUs = max(1L, nextUs - tUs),
                keyFrame = previous == null || previous.frameIndex != frameIndex || frame % fps == 0,
            )
            frame++
        }
        return out
    }

    /** A still image shown for [durationMs] at a steady [fps] (upstream: 10 fps). */
    fun staticFrames(durationMs: Int, fps: Int = Limits.STATIC_VIDEO_FPS): List<ScheduledFrame> {
        val stepUs = 1_000_000L / fps
        val totalUs = durationMs * 1000L
        val out = ArrayList<ScheduledFrame>()
        var t = 0L
        while (t < totalUs) {
            out += ScheduledFrame(0, t, minOf(stepUs, totalUs - t), keyFrame = out.size % fps == 0)
            t += stepUs
        }
        if (out.isEmpty()) out += ScheduledFrame(0, 0, stepUs, keyFrame = true)
        return out
    }

    /** Full timeline for the exported video. */
    fun forSticker(delaysMs: List<Int>, durationMs: Int): List<ScheduledFrame> =
        if (delaysMs.size > 1) {
            resampleForEncoder(scheduleGif(delaysMs, durationMs), durationMs)
        } else {
            staticFrames(durationMs)
        }
}
