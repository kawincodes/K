package io.github.kawincodes.stiksound.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel helpers for packed ARGB ints (non-premultiplied, the layout Android's
 * `Bitmap.getPixels` returns). Ported from StikSound `image-utils.ts`.
 */
object ImageOps {
    /** "#RRGGBB" to an opaque ARGB int; anything else falls back to white. */
    fun parseHexColor(hex: String): Int {
        val clean = hex.trim().removePrefix("#")
        if (clean.length == 6 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return (0xFF shl 24) or clean.toInt(16)
        }
        return 0xFFFFFFFF.toInt()
    }

    fun toHexColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Converts RGBA bytes (GIF decoder output) to ARGB ints. */
    fun rgbaToArgb(rgba: ByteArray, out: IntArray = IntArray(rgba.size / 4)): IntArray {
        for (i in out.indices) {
            val o = i * 4
            out[i] = ((rgba[o + 3].toInt() and 0xFF) shl 24) or
                ((rgba[o].toInt() and 0xFF) shl 16) or
                ((rgba[o + 1].toInt() and 0xFF) shl 8) or
                (rgba[o + 2].toInt() and 0xFF)
        }
        return out
    }

    fun hasTransparency(argb: IntArray): Boolean = argb.any { (it ushr 24) != 0xFF }

    /**
     * Crops a 1:1 square anchored at ([cropX], [cropY]) and scales it to the crop's
     * output size (at most 512, never upscaled) with bilinear filtering in straight
     * alpha, so transparent edges do not pick up dark fringes.
     */
    fun cropAndFitSquare(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        cropX: Float = 0.5f,
        cropY: Float = 0.5f,
        outputSizeOverride: Int? = null,
    ): Pair<IntArray, Int> {
        val crop = computeSquareCrop(srcWidth, srcHeight, cropX, cropY)
        val size = outputSizeOverride ?: crop.outputSize
        val out = IntArray(size * size)
        val scale = crop.cropSide.toDouble() / size
        for (y in 0 until size) {
            val sy = crop.srcY + (y + 0.5) * scale - 0.5
            val y0 = floor(sy).toInt().coerceIn(0, srcHeight - 1)
            val y1 = min(srcHeight - 1, y0 + 1)
            val fy = max(0.0, sy - y0)
            for (x in 0 until size) {
                val sx = crop.srcX + (x + 0.5) * scale - 0.5
                val x0 = floor(sx).toInt().coerceIn(0, srcWidth - 1)
                val x1 = min(srcWidth - 1, x0 + 1)
                val fx = max(0.0, sx - x0)

                val p00 = src[y0 * srcWidth + x0]
                val p10 = src[y0 * srcWidth + x1]
                val p01 = src[y1 * srcWidth + x0]
                val p11 = src[y1 * srcWidth + x1]
                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy
                val a00 = (p00 ushr 24) * w00
                val a10 = (p10 ushr 24) * w10
                val a01 = (p01 ushr 24) * w01
                val a11 = (p11 ushr 24) * w11
                val alpha = a00 + a10 + a01 + a11
                if (alpha <= 0.5) {
                    out[y * size + x] = 0
                    continue
                }
                fun channel(shift: Int): Int {
                    val v = (((p00 shr shift) and 0xFF) * a00 +
                        ((p10 shr shift) and 0xFF) * a10 +
                        ((p01 shr shift) and 0xFF) * a01 +
                        ((p11 shr shift) and 0xFF) * a11) / alpha
                    return v.roundToInt().coerceIn(0, 255)
                }
                out[y * size + x] = (alpha.roundToInt().coerceIn(0, 255) shl 24) or
                    (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
        }
        return out to size
    }

    /** Flattens straight-alpha ARGB onto an opaque [matteArgb] background. */
    fun compositeOnMatte(argb: IntArray, matteArgb: Int): IntArray {
        val mr = (matteArgb shr 16) and 0xFF
        val mg = (matteArgb shr 8) and 0xFF
        val mb = matteArgb and 0xFF
        return IntArray(argb.size) { i ->
            val p = argb[i]
            val a = (p ushr 24) / 255.0
            val inv = 1 - a
            val r = (((p shr 16) and 0xFF) * a + mr * inv).roundToInt()
            val g = (((p shr 8) and 0xFF) * a + mg * inv).roundToInt()
            val b = ((p and 0xFF) * a + mb * inv).roundToInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

/** Planar YUV 4:2:0 picture (BT.601, limited range), the input H.264 encoders expect. */
class Yuv420(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
    val chromaWidth: Int get() = (width + 1) / 2
    val chromaHeight: Int get() = (height + 1) / 2

    companion object {
        fun fromArgb(argb: IntArray, width: Int, height: Int): Yuv420 {
            require(argb.size >= width * height) { "Pixel buffer too small." }
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            val yPlane = ByteArray(width * height)
            val uPlane = ByteArray(cw * ch)
            val vPlane = ByteArray(cw * ch)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val p = argb[row * width + col]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    yPlane[row * width + col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                }
            }
            for (cy in 0 until ch) {
                for (cx in 0 until cw) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (dy in 0..1) {
                        val row = cy * 2 + dy
                        if (row >= height) continue
                        for (dx in 0..1) {
                            val col = cx * 2 + dx
                            if (col >= width) continue
                            val p = argb[row * width + col]
                            r += (p shr 16) and 0xFF
                            g += (p shr 8) and 0xFF
                            b += p and 0xFF
                            n++
                        }
                    }
                    r /= n; g /= n; b /= n
                    uPlane[cy * cw + cx] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    vPlane[cy * cw + cx] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
            return Yuv420(width, height, yPlane, uPlane, vPlane)
        }
    }
}
