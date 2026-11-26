package io.github.kawincodes.stiksound.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageTest {
    @Test
    fun parsesHexColors() {
        assertEquals(0xFF112233.toInt(), ImageOps.parseHexColor("#112233"))
        assertEquals(0xFFAABBCC.toInt(), ImageOps.parseHexColor("aabbcc"))
        assertEquals(0xFFFFFFFF.toInt(), ImageOps.parseHexColor("nope"))
        assertEquals("#00FF7F", ImageOps.toHexColor(0xFF00FF7F.toInt()))
    }

    @Test
    fun cropOfSquareImageAtNativeSizeIsIdentity() {
        val src = IntArray(16) { 0xFF000000.toInt() or (it * 0x0F0F0F) }
        val (out, size) = ImageOps.cropAndFitSquare(src, 4, 4)
        assertEquals(4, size)
        assertArrayEquals(src, out)
    }

    @Test
    fun cropPicksTheAnchoredSquare() {
        // 4x2 image: left half red, right half blue.
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val src = IntArray(8) { if (it % 4 < 2) red else blue }
        assertArrayEquals(intArrayOf(red, red, red, red), ImageOps.cropAndFitSquare(src, 4, 2, 0f, 0.5f).first)
        assertArrayEquals(intArrayOf(blue, blue, blue, blue), ImageOps.cropAndFitSquare(src, 4, 2, 1f, 0.5f).first)
    }

    @Test
    fun downscaleDoesNotDarkenTransparentEdges() {
        // 2x2 image: one opaque white pixel, three fully transparent black ones. Scaled to 1x1 via override.
        val src = intArrayOf(0xFFFFFFFF.toInt(), 0, 0, 0)
        val (out, size) = ImageOps.cropAndFitSquare(src, 2, 2, outputSizeOverride = 1)
        assertEquals(1, size)
        // Color stays white (straight alpha), alpha becomes ~25%.
        assertEquals(0xFFFFFF, out[0] and 0xFFFFFF)
        assertEquals(64, out[0] ushr 24)
    }

    @Test
    fun compositeOnMatteFlattensAlpha() {
        val out = ImageOps.compositeOnMatte(intArrayOf(0x00FF0000, 0xFFFF0000.toInt(), 0x80FFFFFF.toInt()), 0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), out[0])
        assertEquals(0xFFFF0000.toInt(), out[1])
        assertEquals(0xFF808080.toInt(), out[2])
    }

    @Test
    fun rgbaBytesConvertToArgb() {
        val argb = ImageOps.rgbaToArgb(byteArrayOf(1, 2, 3, 4, 255.toByte(), 0, 0, 255.toByte()))
        assertArrayEquals(intArrayOf(0x04010203, 0xFFFF0000.toInt()), argb)
        assertTrue(ImageOps.hasTransparency(argb))
        assertFalse(ImageOps.hasTransparency(intArrayOf(0xFF000000.toInt())))
    }

    @Test
    fun yuvUsesBt601LimitedRange() {
        val white = Yuv420.fromArgb(IntArray(4) { 0xFFFFFFFF.toInt() }, 2, 2)
        assertEquals(235, white.y[0].toInt() and 0xFF)
        assertEquals(128, white.u[0].toInt() and 0xFF)
        assertEquals(128, white.v[0].toInt() and 0xFF)
        val black = Yuv420.fromArgb(IntArray(4) { 0xFF000000.toInt() }, 2, 2)
        assertEquals(16, black.y[0].toInt() and 0xFF)
        val red = Yuv420.fromArgb(IntArray(4) { 0xFFFF0000.toInt() }, 2, 2)
        assertEquals(82, red.y[0].toInt() and 0xFF)
        assertEquals(90, red.u[0].toInt() and 0xFF)
        assertEquals(240, red.v[0].toInt() and 0xFF)
    }

    @Test
    fun yuvHandlesOddSizes() {
        val yuv = Yuv420.fromArgb(IntArray(9) { 0xFF808080.toInt() }, 3, 3)
        assertEquals(2, yuv.chromaWidth)
        assertEquals(4, yuv.u.size)
        assertEquals(9, yuv.y.size)
    }

    private fun pngHeader(w: Int, h: Int, colorType: Int): ByteArray {
        val b = ByteArray(33)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b)
        b[11] = 13
        "IHDR".toByteArray().copyInto(b, 12)
        b[16] = (w ushr 24).toByte(); b[17] = (w ushr 16).toByte(); b[18] = (w ushr 8).toByte(); b[19] = w.toByte()
        b[20] = (h ushr 24).toByte(); b[21] = (h ushr 16).toByte(); b[22] = (h ushr 8).toByte(); b[23] = h.toByte()
        b[24] = 8; b[25] = colorType.toByte()
        return b
    }

    @Test
    fun sniffsPngSizeAndAlpha() {
        val probe = Sniffer.probeImage(pngHeader(640, 480, 6))
        assertEquals(ImageFormat.PNG, probe.format)
        assertEquals(640, probe.width)
        assertEquals(480, probe.height)
        assertTrue(probe.hasAlpha)
        assertFalse(Sniffer.probeImage(pngHeader(10, 10, 2)).hasAlpha)
    }

    @Test
    fun rejectsDecompressionBombs() {
        for (header in listOf(pngHeader(9000, 10, 6), pngHeader(7000, 7000, 6))) {
            try {
                Sniffer.probeImage(header)
                fail("expected FILE_TOO_LARGE")
            } catch (e: StikSoundException) {
                assertEquals(ErrorCode.FILE_TOO_LARGE, e.code)
            }
        }
    }

    @Test
    fun sniffsJpegAndWebp() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0x01, 0x2C, 0x01, 0x90.toByte(), 3, 0, 0, 0,
        )
        val j = Sniffer.probeImage(jpeg)
        assertEquals(ImageFormat.JPEG, j.format)
        assertEquals(400, j.width)
        assertEquals(300, j.height)

        val webp = ByteArray(30)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBPVP8X".toByteArray().copyInto(webp, 8)
        webp[20] = 0x10 // alpha
        webp[24] = (512 - 1).toByte(); webp[25] = ((512 - 1) shr 8).toByte()
        webp[27] = (256 - 1).toByte()
        val w = Sniffer.probeImage(webp)
        assertEquals(ImageFormat.WEBP, w.format)
        assertEquals(512, w.width)
        assertEquals(256, w.height)
        assertTrue(w.hasAlpha)
    }

    @Test
    fun rejectsUnknownImages() {
        try {
            Sniffer.probeImage(ByteArray(64) { 7 })
            fail("expected UNSUPPORTED_IMAGE")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.UNSUPPORTED_IMAGE, e.code)
        }
    }

    @Test
    fun sniffsAudioContainers() {
        fun bytes(prefix: String) = (prefix.toByteArray(Charsets.ISO_8859_1) + ByteArray(16))
        assertEquals(AudioFormat.MP3, Sniffer.sniffAudio(bytes("ID3")))
        assertEquals(AudioFormat.OGG, Sniffer.sniffAudio(bytes("OggS")))
        assertEquals(AudioFormat.FLAC, Sniffer.sniffAudio(bytes("fLaC")))
        assertEquals(AudioFormat.M4A, Sniffer.sniffAudio(bytes("\u0000\u0000\u0000 ftypM4A ")))
        assertEquals(AudioFormat.MP3, Sniffer.sniffAudio(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(16)))
        assertEquals(AudioFormat.AAC, Sniffer.sniffAudio(byteArrayOf(0xFF.toByte(), 0xF1.toByte()) + ByteArray(16)))
        assertEquals(null, Sniffer.sniffAudio(ByteArray(16)))
        assertFalse(Sniffer.isVideoContainer(bytes("\u0000\u0000\u0000 ftypM4A ")))
        assertTrue(Sniffer.isVideoContainer(bytes("\u0000\u0000\u0000 ftypisom")))
    }
}
