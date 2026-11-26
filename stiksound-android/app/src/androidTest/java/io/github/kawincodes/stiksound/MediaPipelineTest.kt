package io.github.kawincodes.stiksound

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.ImageFormat
import io.github.kawincodes.stiksound.core.MonoPcm
import io.github.kawincodes.stiksound.core.TrimRange
import io.github.kawincodes.stiksound.core.AudioTrim
import io.github.kawincodes.stiksound.media.AudioDecoder
import io.github.kawincodes.stiksound.media.Mp4Exporter
import io.github.kawincodes.stiksound.media.Outputs
import io.github.kawincodes.stiksound.media.StickerLoader
import io.github.kawincodes.stiksound.media.StickerSource
import io.github.kawincodes.stiksound.media.VideoRequest
import io.github.kawincodes.stiksound.media.WebpExporter
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Android codecs: MediaCodec decoders, the H.264/AAC encoders,
 * MediaMuxer and the WebP encoder. Needs a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class MediaPipelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(name: String): ByteArray = testAssets.open(name).use { it.readBytes() }

    private fun tone(ms: Int, rate: Int = 48_000) =
        MonoPcm(FloatArray(rate * ms / 1000) { i -> (0.5 * sin(2 * PI * 440 * i / rate)).toFloat() }, rate)

    private fun pngBytes(width: Int, height: Int, draw: (Bitmap) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(bitmap)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private class TrackInfo(val mime: String, val format: MediaFormat)

    private fun tracks(file: File): List<TrackInfo> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).map {
                val f = extractor.getTrackFormat(it)
                TrackInfo(f.getString(MediaFormat.KEY_MIME)!!, f)
            }
        } finally {
            extractor.release()
        }
    }

    private fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            retriever.release()
        }
    }

    private fun frameAt(file: File, timeUs: Long): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally {
            retriever.release()
        }
    }

    private fun assertColorNear(expected: Int, actual: Int, tolerance: Int = 40) {
        val msg = "expected #%06X got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF)
        assertTrue(msg, abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue(msg, abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue(msg, abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
    }

    /**
     * The fixtures are a 440 Hz sine at amplitude 0.5 on every channel (MP3 is stereo,
     * M4A and OGG mono), so the decoded mono signal has an RMS of 0.5/√2 ≈ 0.354.
     * A wrong sample format, channel count or gain would move it far off.
     */
    @Test
    fun decodesCompressedAudioFormats() = runBlocking {
        val failures = mutableListOf<String>()
        for (name in listOf("tone.mp3", "tone.m4a", "tone.ogg")) {
            val pcm = AudioDecoder.decode(asset(name))
            val info = "$name: ${pcm.samples.size} samples @ ${pcm.sampleRate} Hz = ${pcm.durationMs} ms"
            Log.i("MediaPipelineTest", info)
            if (pcm.durationMs !in 1400..1650) {
                failures += "$info (expected about 1500 ms)"
                continue
            }
            val peak = pcm.samples.maxOf { abs(it) }
            val mid = pcm.samples.copyOfRange((0.2 * pcm.sampleRate).toInt(), (1.3 * pcm.sampleRate).toInt())
            val rms = kotlin.math.sqrt(mid.sumOf { (it * it).toDouble() } / mid.size)
            if (peak !in 0.4f..0.75f) failures += "$info, peak $peak"
            if (rms !in 0.31..0.40) failures += "$info, rms $rms"
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun loadsStillImageAndKeepsTransparency() = runBlocking {
        val png = pngBytes(1200, 800) { b ->
            b.eraseColor(Color.TRANSPARENT)
            android.graphics.Canvas(b).drawRect(300f, 200f, 900f, 600f, android.graphics.Paint().apply { color = Color.RED })
        }
        val sticker = StickerLoader.load("test.png", png)
        assertEquals(ImageFormat.PNG, sticker.format)
        assertEquals(1200, sticker.originalWidth)
        assertEquals(512, sticker.height) // shorter side scaled to 512
        assertTrue(sticker.hasAlpha)
        assertEquals(512, sticker.exportSize)
    }

    @Test
    fun loadsAnimatedGifFrames() = runBlocking {
        val sticker = StickerLoader.load("anim.gif", asset("anim.gif"))
        assertEquals(3, sticker.frames.size)
        assertEquals(listOf(100, 200, 50), sticker.delaysMs)
        assertEquals(350, sticker.loopDurationMs)
        assertEquals(24, sticker.exportSize)
    }

    @Test
    fun rendersStaticStickerToMp4WithMatte() = runBlocking {
        val png = pngBytes(640, 480) { b ->
            b.eraseColor(Color.TRANSPARENT)
            android.graphics.Canvas(b).drawCircle(320f, 240f, 120f, android.graphics.Paint().apply { color = Color.BLUE })
        }
        val sticker = StickerLoader.load("circle.png", png)
        val out = Outputs.cacheFile(context, "test-static.mp4")
        val result = Mp4Exporter.render(
            VideoRequest(sticker, 0.5f, 0.5f, 0xFFFFFF00.toInt(), AudioDsp.fitToDuration(tone(2000), 2000), 2000),
            out,
        )
        assertTrue(out.length() > 1000)
        val t = tracks(out)
        val video = t.single { it.mime == MediaFormat.MIMETYPE_VIDEO_AVC }
        val audio = t.single { it.mime == MediaFormat.MIMETYPE_AUDIO_AAC }
        assertEquals(result.sizePx, video.format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(result.sizePx, video.format.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(48_000, audio.format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(1, audio.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        val duration = durationMs(out)
        assertTrue("duration $duration", duration in 1850..2200)

        val frame = frameAt(out, 500_000)
        // Corner: transparent area flattened onto the yellow matte. Center: the blue circle.
        assertColorNear(0xFFFFFF00.toInt(), frame.getPixel(4, 4))
        assertColorNear(Color.BLUE, frame.getPixel(frame.width / 2, frame.height / 2))
    }

    @Test
    fun rendersAnimatedGifToMp4() = runBlocking {
        val sticker = StickerLoader.load("anim.gif", asset("anim.gif"))
        val trim = TrimRange(0, 1000)
        val durationMs = AudioTrim.exportDurationMs(trim, 10_000, sticker.loopDurationMs)
        val out = Outputs.cacheFile(context, "test-gif.mp4")
        Mp4Exporter.render(
            VideoRequest(sticker, 0.5f, 0.5f, Color.WHITE, AudioDsp.fitToDuration(tone(1000), durationMs), durationMs),
            out,
        )
        val duration = durationMs(out)
        assertTrue("duration $duration", duration in 900..1200)
        assertNotNull(tracks(out).singleOrNull { it.mime == MediaFormat.MIMETYPE_VIDEO_AVC })
    }

    @Test
    fun exportsStaticWebpUnder100Kb() = runBlocking {
        val png = pngBytes(900, 900) { b ->
            // Noisy content to make compression work hard.
            val rnd = java.util.Random(42)
            for (y in 0 until 900 step 3) for (x in 0 until 900 step 3) b.setPixel(x, y, rnd.nextInt() or 0xFF000000.toInt())
        }
        val sticker = StickerLoader.load("noise.png", png)
        val out = WebpExporter.export(sticker, 0.5f, 0.5f, Outputs.cacheFile(context, "test.webp"))
        assertTrue("size ${out.length()}", out.length() <= 102_400)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, opts)
        assertEquals(512, opts.outWidth)
        assertEquals(512, opts.outHeight)
    }

    @Test
    fun appLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertNotNull(it.window.decorView) }
        }
    }
}
