package io.github.kawincodes.stiksound.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GifTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "missing $name" }.readBytes()

    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF
    private val white = 0xFFFFFF

    private fun pixel(rgba: ByteArray, width: Int, x: Int, y: Int): Int {
        val o = (y * width + x) * 4
        return ((rgba[o + 3].toInt() and 0xFF) shl 24) or ((rgba[o].toInt() and 0xFF) shl 16) or
            ((rgba[o + 1].toInt() and 0xFF) shl 8) or (rgba[o + 2].toInt() and 0xFF)
    }

    @Test
    fun decodesPillowAnimationExactly() {
        val gif = resource("gif/anim.gif")
        val expected = resource("gif/anim.rgba")
        val frames = GifDecoder.decodeAll(gif)
        assertEquals(3, frames.size)
        val frameBytes = 40 * 24 * 4
        frames.forEachIndexed { i, f ->
            assertEquals(40, f.width)
            assertEquals(24, f.height)
            assertArrayEquals("frame $i", expected.copyOfRange(i * frameBytes, (i + 1) * frameBytes), f.rgba)
        }
        assertEquals(listOf(100, 200, 50), frames.map { it.delayMs })
    }

    @Test
    fun probeMatchesDecode() {
        val info = GifDecoder.probe(resource("gif/anim.gif"))
        assertEquals(40, info.width)
        assertEquals(24, info.height)
        assertEquals(3, info.frameCount)
        assertEquals(350, info.loopDurationMs)
        assertTrue(info.animated)
    }

    @Test
    fun transparentIndexStaysClear() {
        val frames = GifDecoder.decodeAll(resource("gif/transparent.gif"))
        assertEquals(1, frames.size)
        assertArrayEquals(resource("gif/transparent.rgba"), frames[0].rgba)
        val probe = Sniffer.probeImage(resource("gif/transparent.gif"))
        assertEquals(ImageFormat.GIF, probe.format)
        assertTrue(probe.hasAlpha)
        assertFalse(probe.animated)
    }

    @Test
    fun interlacedFrameRowsLandInPlace() {
        val w = 3
        val h = 10
        // Row r uses palette index r % 4, so a deinterlacing bug shows up as a wrong row color.
        val indices = IntArray(w * h) { (it / w) % 4 }
        val gif = GifBuilder(w, h, listOf(red, green, blue, white))
            .frame(GifBuilder.Frame(indices, w = w, h = h, interlaced = true))
            .build()
        val frame = GifDecoder.decodeAll(gif).single()
        val colors = listOf(red, green, blue, white)
        for (y in 0 until h) {
            assertEquals("row $y", 0xFF000000.toInt() or colors[y % 4], pixel(frame.rgba, w, 1, y))
        }
    }

    @Test
    fun disposalMethodsComposite() {
        val w = 4
        val h = 4
        val full = IntArray(w * h) { 0 } // red background
        val gif = GifBuilder(w, h, listOf(red, green, blue, white))
            .frame(GifBuilder.Frame(full, w = w, h = h, disposal = 1))
            // 2x2 green patch, then restore to background (clear) afterwards.
            .frame(GifBuilder.Frame(IntArray(4) { 1 }, left = 1, top = 1, w = 2, h = 2, disposal = 2))
            // 1x1 blue patch at (0,0), restored to previous afterwards.
            .frame(GifBuilder.Frame(IntArray(1) { 2 }, left = 0, top = 0, w = 1, h = 1, disposal = 3))
            // Transparent 1x1 frame: index 3 is transparent, so nothing changes.
            .frame(GifBuilder.Frame(IntArray(1) { 3 }, left = 3, top = 3, w = 1, h = 1, transparentIndex = 3))
            .build()
        val frames = GifDecoder.decodeAll(gif)
        assertEquals(4, frames.size)
        val opaque = 0xFF000000.toInt()
        assertEquals(opaque or green, pixel(frames[1].rgba, w, 1, 1))
        assertEquals(opaque or red, pixel(frames[1].rgba, w, 0, 0))
        // Frame 2 sees the cleared (transparent) patch from frame 1's disposal.
        assertEquals(0, pixel(frames[2].rgba, w, 1, 1))
        assertEquals(opaque or blue, pixel(frames[2].rgba, w, 0, 0))
        // Frame 3: blue pixel restored to red (disposal 3), transparent pixel left as red.
        assertEquals(opaque or red, pixel(frames[3].rgba, w, 0, 0))
        assertEquals(opaque or red, pixel(frames[3].rgba, w, 3, 3))
    }

    @Test
    fun tinyDelaysPlayAt100ms() {
        assertEquals(100, gifFrameDelayMs(0))
        assertEquals(100, gifFrameDelayMs(10))
        assertEquals(20, gifFrameDelayMs(20))
        assertEquals(100, gifFrameDelayMs(null))
    }

    @Test
    fun rejectsTooManyFrames() {
        val builder = GifBuilder(1, 1, listOf(red, green))
        repeat(Limits.MAX_GIF_FRAMES + 1) { builder.frame(GifBuilder.Frame(intArrayOf(it % 2), w = 1, h = 1)) }
        try {
            GifDecoder.probe(builder.build())
            fail("expected FILE_TOO_LARGE")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.FILE_TOO_LARGE, e.code)
        }
    }

    @Test
    fun rejectsFrameOutsideCanvas() {
        val gif = GifBuilder(2, 2, listOf(red, green))
            .frame(GifBuilder.Frame(IntArray(4), left = 1, top = 1, w = 2, h = 2))
            .build()
        try {
            GifDecoder.decodeAll(gif)
            fail("expected UNSUPPORTED_IMAGE")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.UNSUPPORTED_IMAGE, e.code)
        }
    }

    @Test
    fun rejectsHugeCanvasBeforeAllocating() {
        val header = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
            0x40, 0x1F, 0x40, 0x1F, 0, 0, 0, 0x3B,
        ) // 8000 x 8000
        try {
            GifDecoder.decode(header) { _, _, _, _, _ -> }
            fail("expected FILE_TOO_LARGE")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.FILE_TOO_LARGE, e.code)
        }
    }
}
