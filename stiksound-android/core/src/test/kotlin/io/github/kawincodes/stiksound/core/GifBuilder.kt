package io.github.kawincodes.stiksound.core

import java.io.ByteArrayOutputStream

/**
 * Test helper that writes GIF89a files with uncompressed LZW data, so tests can
 * control offsets, interlacing, transparency and disposal exactly.
 */
class GifBuilder(private val width: Int, private val height: Int, private val palette: List<Int>) {
    data class Frame(
        val indices: IntArray,
        val left: Int = 0,
        val top: Int = 0,
        val w: Int,
        val h: Int,
        val delayCs: Int = 10,
        val disposal: Int = 0,
        val transparentIndex: Int? = null,
        val interlaced: Boolean = false,
    )

    private val frames = mutableListOf<Frame>()

    fun frame(f: Frame) = apply { frames += f }

    fun build(): ByteArray {
        var bits = 1
        while ((1 shl bits) < palette.size) bits++
        val tableSize = 1 shl bits
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        out.le16(width); out.le16(height)
        out.write(0x80 or (bits - 1))
        out.write(0); out.write(0)
        for (i in 0 until tableSize) {
            val c = palette.getOrElse(i) { 0 }
            out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
        }
        for (f in frames) {
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write((f.disposal shl 2) or if (f.transparentIndex != null) 1 else 0)
            out.le16(f.delayCs)
            out.write(f.transparentIndex ?: 0)
            out.write(0)

            out.write(0x2C)
            out.le16(f.left); out.le16(f.top); out.le16(f.w); out.le16(f.h)
            out.write(if (f.interlaced) 0x40 else 0)
            val minCodeSize = maxOf(2, bits)
            out.write(minCodeSize)
            val rows = if (f.interlaced) interlaceOrder(f.h) else (0 until f.h).toList()
            val ordered = IntArray(f.w * f.h)
            rows.forEachIndexed { i, row -> System.arraycopy(f.indices, row * f.w, ordered, i * f.w, f.w) }
            val data = lzwUncompressed(ordered, minCodeSize)
            var p = 0
            while (p < data.size) {
                val n = minOf(255, data.size - p)
                out.write(n); out.write(data, p, n); p += n
            }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun interlaceOrder(h: Int): List<Int> =
        listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2).flatMap { (start, step) -> (start until h step step).toList() }

    private fun lzwUncompressed(indices: IntArray, minCodeSize: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val codeSize = minCodeSize + 1
        val perBlock = (1 shl minCodeSize) - 2
        val bytes = ByteArrayOutputStream()
        var buf = 0
        var count = 0
        fun emit(code: Int) {
            buf = buf or (code shl count)
            count += codeSize
            while (count >= 8) { bytes.write(buf and 0xFF); buf = buf ushr 8; count -= 8 }
        }
        var sinceClear = perBlock
        for (idx in indices) {
            if (sinceClear == perBlock) { emit(clear); sinceClear = 0 }
            emit(idx); sinceClear++
        }
        emit(eoi)
        if (count > 0) bytes.write(buf and 0xFF)
        return bytes.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
}
