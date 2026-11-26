package io.github.kawincodes.stiksound

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.AudioTrim
import io.github.kawincodes.stiksound.core.ErrorCode
import io.github.kawincodes.stiksound.core.FrameScheduler
import io.github.kawincodes.stiksound.core.ImageFormat
import io.github.kawincodes.stiksound.core.MonoPcm
import io.github.kawincodes.stiksound.core.StikSoundException
import io.github.kawincodes.stiksound.core.TrimRange
import io.github.kawincodes.stiksound.core.Wav
import io.github.kawincodes.stiksound.media.AudioDecoder
import io.github.kawincodes.stiksound.media.Mp4Exporter
import io.github.kawincodes.stiksound.media.PreviewPlayer
import io.github.kawincodes.stiksound.media.StickerLoader
import io.github.kawincodes.stiksound.media.StickerSource
import io.github.kawincodes.stiksound.media.VideoRequest
import io.github.kawincodes.stiksound.media.WebpExporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource

/**
 * The media pipeline on the JVM. Bitmap decoding and WebP encoding are real (Robolectric
 * native graphics). MediaCodec, MediaExtractor, MediaMuxer and AudioTrack are Robolectric
 * fakes, so these tests check the app's codec loops, frame timing, cropping, color
 * conversion and audio handling, not the device's H.264/AAC output. That part is covered
 * by the instrumented tests in src/androidTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MediaPipelineJvmTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() = FakeCodecs.install()

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "missing $name" }.readBytes()

    private fun tone(ms: Int, rate: Int = 48_000, amp: Double = 0.5) =
        MonoPcm(FloatArray(rate * ms / 1000) { i -> (amp * sin(2 * PI * 440 * i / rate)).toFloat() }, rate)

    private fun png(width: Int, height: Int, draw: (Canvas) -> Unit): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun bt601Y(r: Int, g: Int, b: Int) = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
    private fun bt601U(r: Int, g: Int, b: Int) = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
    private fun bt601V(r: Int, g: Int, b: Int) = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

    private fun assertNear(message: String, expected: Int, actual: Int, tolerance: Int = 3) =
        assertTrue("$message: expected $expected±$tolerance, got $actual", abs(expected - actual) <= tolerance)

    private fun tempFile(name: String) = File(context.cacheDir, name).also { it.delete() }

    // ---- Sticker loading -----------------------------------------------------------------------

    @Test
    fun loadsWideTransparentPngAndScalesShortSideTo512() = runBlocking {
        val bytes = png(1200, 800) { it.drawRect(300f, 200f, 900f, 600f, Paint().apply { color = Color.RED }) }
        val sticker = StickerLoader.load("wide.png", bytes)
        assertEquals(ImageFormat.PNG, sticker.format)
        assertEquals(1200, sticker.originalWidth)
        assertEquals(800, sticker.originalHeight)
        assertEquals(768, sticker.width)
        assertEquals(512, sticker.height)
        assertTrue(sticker.hasAlpha)
        assertFalse(sticker.animated)
        assertEquals(512, sticker.exportSize)
    }

    @Test
    fun loadsOpaqueJpegWithoutAlpha() = runBlocking {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val sticker = StickerLoader.load("photo.jpg", jpeg)
        assertEquals(ImageFormat.JPEG, sticker.format)
        assertFalse(sticker.hasAlpha)
        assertEquals(300, sticker.exportSize)
    }

    @Test
    fun loadsAnimatedGifFramesWithDelays() = runBlocking {
        val sticker = StickerLoader.load("anim.gif", resource("anim.gif"))
        assertEquals(ImageFormat.GIF, sticker.format)
        assertEquals(3, sticker.frames.size)
        assertEquals(listOf(100, 200, 50), sticker.delaysMs)
        assertEquals(350, sticker.loopDurationMs)
        assertEquals(24, sticker.exportSize)
    }

    @Test
    fun rejectsVideosAndUnknownBytes() = runBlocking {
        for (bytes in listOf("\u0000\u0000\u0000\u0018ftypisom".toByteArray(Charsets.ISO_8859_1) + ByteArray(64), ByteArray(256) { 7 })) {
            try {
                StickerLoader.load("bad", bytes)
                fail("expected UNSUPPORTED_IMAGE")
            } catch (e: StikSoundException) {
                assertEquals(ErrorCode.UNSUPPORTED_IMAGE, e.code)
            }
        }
    }

    // ---- WebP ----------------------------------------------------------------------------------

    @Test
    fun webpIs512SquareUnder100KbAndKeepsTransparency() = runBlocking {
        val bytes = png(900, 600) { it.drawCircle(450f, 300f, 250f, Paint().apply { color = Color.GREEN }) }
        val sticker = StickerLoader.load("circle.png", bytes)
        val out = WebpExporter.export(sticker, 0.5f, 0.5f, tempFile("sticker.webp"))
        assertTrue("size ${out.length()}", out.length() in 1..102_400)
        val decoded = BitmapFactory.decodeFile(out.absolutePath)
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)
        assertEquals(0, Color.alpha(decoded.getPixel(2, 2)))
        assertEquals(Color.GREEN, decoded.getPixel(256, 256))
    }

    @Test
    fun noisyWebpStillFitsTheLimit() = runBlocking {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        val pixels = IntArray(512 * 512) { rnd.nextInt() or 0xFF000000.toInt() }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
        val sticker = StickerSource("noise", ImageFormat.PNG, 512, 512, listOf(bitmap), listOf(0), false)
        val out = WebpExporter.export(sticker, 0.5f, 0.5f, tempFile("noise.webp"))
        assertTrue("size ${out.length()}", out.length() <= 102_400)
    }

    // ---- MP4 -----------------------------------------------------------------------------------

    @Test
    fun staticStickerVideoHasExpectedFramesColorsAndAudio() = runBlocking {
        val bytes = png(640, 480) { it.drawCircle(320f, 240f, 120f, Paint().apply { color = Color.BLUE }) }
        val sticker = StickerLoader.load("circle.png", bytes)
        val yellow = 0xFFFFFF00.toInt()
        val audio = AudioDsp.fitToDuration(tone(2000), 2000)
        val request = VideoRequest(sticker, 0.5f, 0.5f, yellow, audio, 2000)

        // Encoder loops: one input per scheduled frame, at the scheduled timestamp.
        val timeline = FrameScheduler.forSticker(sticker.delaysMs, 2000)
        assertEquals(20, timeline.size)
        val size = Mp4Exporter.chooseVideoSize(sticker.exportSize)
        assertEquals(480, size)
        val video = Mp4Exporter.encodeVideo(request, timeline, size, 10) {}
        assertEquals(timeline.map { it.timestampUs }, video.samples.map { it.ptsUs })
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, video.format.getString(MediaFormat.KEY_MIME))
        val encodedAudio = Mp4Exporter.encodeAudio(audio)
        val audioPts = encodedAudio.samples.map { it.ptsUs }
        assertEquals(0L, audioPts.first())
        assertTrue("audio pts must increase", audioPts.zipWithNext().all { (a, b) -> b > a })

        // Full render through the (fake) muxer.
        val out = tempFile("static.mp4")
        val result = Mp4Exporter.render(request, out)
        assertEquals(480, result.sizePx)
        val records = FakeCodecs.parse(out.readBytes())
        val frames = records.filterIsInstance<FakeCodecs.VideoRecord>()
        val chunks = records.filterIsInstance<FakeCodecs.AudioRecord>()
        assertEquals(20, frames.size)
        frames.forEachIndexed { i, f ->
            assertEquals("frame $i input", 480 * 480 * 3 / 2, f.inputBytes)
            // Corner: transparent pixel flattened onto the yellow matte. Center: the blue circle.
            assertNear("frame $i corner Y", bt601Y(255, 255, 0), f.yCorner)
            assertNear("frame $i corner U", bt601U(255, 255, 0), f.uCorner)
            assertNear("frame $i corner V", bt601V(255, 255, 0), f.vCorner)
            assertNear("frame $i center Y", bt601Y(0, 0, 255), f.yCenter)
        }
        // 2 s of 48 kHz mono 16-bit PCM reached the audio encoder, nothing more, nothing less.
        assertEquals(2000 * 48 * 2, chunks.sumOf { it.pcmBytes })
        // The muxer received the two tracks interleaved by timestamp.
        val expectedOrder = mergeOrder(video.samples.map { it.ptsUs }, audioPts)
        assertEquals(expectedOrder, records.map { if (it is FakeCodecs.VideoRecord) 'V' else 'A' })
    }

    /** Same rule as Mp4Exporter.mux: video first when timestamps tie. */
    private fun mergeOrder(video: List<Long>, audio: List<Long>): List<Char> {
        val out = ArrayList<Char>()
        var v = 0
        var a = 0
        while (v < video.size || a < audio.size) {
            if (a >= audio.size || (v < video.size && video[v] <= audio[a])) { out += 'V'; v++ } else { out += 'A'; a++ }
        }
        return out
    }

    @Test
    fun animatedGifVideoFollowsTheGifTiming() = runBlocking {
        val sticker = StickerLoader.load("anim.gif", resource("anim.gif"))
        val durationMs = AudioTrim.exportDurationMs(TrimRange(0, 1000), 10_000, sticker.loopDurationMs)
        val out = tempFile("gif.mp4")
        val result = Mp4Exporter.render(
            VideoRequest(sticker, 0.5f, 0.5f, Color.WHITE, AudioDsp.fitToDuration(tone(1000), durationMs), durationMs),
            out,
        )
        assertEquals(24, result.sizePx)
        val frames = FakeCodecs.parse(out.readBytes()).filterIsInstance<FakeCodecs.VideoRecord>()
        val timeline = FrameScheduler.forSticker(sticker.delaysMs, durationMs)
        assertEquals(24, timeline.size) // 1 s at 24 fps
        assertEquals(timeline.size, frames.size)
        // The crop center is source pixel (20, 12): green in GIF frame 1, background in frames 0 and 2.
        val green = bt601Y(30, 200, 60)
        val background = bt601Y(250, 240, 200)
        timeline.zip(frames).forEachIndexed { i, (slot, frame) ->
            val expected = if (slot.frameIndex == 1) green else background
            assertNear("encoder frame $i (GIF frame ${slot.frameIndex})", expected, frame.yCenter, tolerance = 8)
        }
    }

    @Test
    fun missingEncoderIsReported() = runBlocking {
        org.robolectric.shadows.ShadowMediaCodecList.reset()
        val sticker = StickerLoader.load("anim.gif", resource("anim.gif"))
        try {
            Mp4Exporter.render(VideoRequest(sticker, 0.5f, 0.5f, Color.WHITE, tone(500), 500), tempFile("none.mp4"))
            fail("expected UNSUPPORTED_CODEC")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.UNSUPPORTED_CODEC, e.code)
        }
    }

    // ---- Audio decoding ------------------------------------------------------------------------

    @Test
    fun decodesThroughMediaCodecAndDownmixesStereo() = runBlocking {
        val rate = 44_100
        val frames = rate * 3 / 2
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val left = (0.6 * sin(2 * PI * 300 * i / rate) * 32767).toInt().toShort()
            pcm.putShort(left)
            pcm.putShort((-left / 3).toShort())
        }
        val format = MediaFormat.createAudioFormat("audio/mpeg", rate, 2).apply { setLong(MediaFormat.KEY_DURATION, 1_500_000) }
        ShadowMediaExtractor.addTrack(DataSource.toDataSource("robolectric://tone.mp3"), format, pcm.array())
        FakeCodecs.installPcmDecoder("audio/mpeg")

        val fakeMp3 = "ID3".toByteArray() + ByteArray(64)
        val decoded = AudioDecoder.decode(fakeMp3) { it.setDataSource("robolectric://tone.mp3") }
        assertEquals(rate, decoded.sampleRate)
        assertEquals(frames, decoded.samples.size)
        assertEquals(1500, decoded.durationMs)
        for (i in listOf(10, 1234, frames - 1)) {
            val left = pcm.getShort(i * 4) / 32768f
            val right = pcm.getShort(i * 4 + 2) / 32768f
            assertEquals("sample $i", (left + right) / 2, decoded.samples[i], 1e-6f)
        }
    }

    @Test
    fun rejectsAudioLongerThanThreeMinutes() = runBlocking {
        val format = MediaFormat.createAudioFormat("audio/mpeg", 44_100, 1).apply { setLong(MediaFormat.KEY_DURATION, 200_000_000) }
        ShadowMediaExtractor.addTrack(DataSource.toDataSource("robolectric://long.mp3"), format, ByteArray(4096))
        FakeCodecs.installPcmDecoder("audio/mpeg")
        try {
            AudioDecoder.decode("ID3".toByteArray() + ByteArray(64)) { it.setDataSource("robolectric://long.mp3") }
            fail("expected FILE_TOO_LARGE")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.FILE_TOO_LARGE, e.code)
        }
    }

    @Test
    fun rejectsFilesWithoutAnAudioTrack() = runBlocking {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 64, 64)
        ShadowMediaExtractor.addTrack(DataSource.toDataSource("robolectric://silent.mp4"), format, ByteArray(128))
        try {
            AudioDecoder.decode(ByteArray(64)) { it.setDataSource("robolectric://silent.mp4") }
            fail("expected UNSUPPORTED_AUDIO")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.UNSUPPORTED_AUDIO, e.code)
        }
    }

    @Test
    fun ignoresTheBogusEncoderDelayAndroid9ReportsForEditLists() {
        // What Android 9's MP4 extractor reports for an ffmpeg-made M4A with 1024 priming samples.
        val bogus = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1)
            .apply { setInteger(MediaFormat.KEY_ENCODER_DELAY, 49_152) }
        assertTrue(AudioDecoder.dropImplausibleEncoderDelay(bogus))
        assertEquals(0, bogus.getInteger(MediaFormat.KEY_ENCODER_DELAY))

        // Real priming delays are left alone.
        for (delay in listOf(1024, 2112, 3840)) {
            val real = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1)
                .apply { setInteger(MediaFormat.KEY_ENCODER_DELAY, delay) }
            assertFalse(AudioDecoder.dropImplausibleEncoderDelay(real))
            assertEquals(delay, real.getInteger(MediaFormat.KEY_ENCODER_DELAY))
        }
        assertFalse(AudioDecoder.dropImplausibleEncoderDelay(MediaFormat.createAudioFormat("audio/mpeg", 44_100, 2)))
    }

    @Test
    fun decodesWavWithoutMediaCodec() = runBlocking {
        val pcm = tone(800, rate = 22_050)
        val decoded = AudioDecoder.decode(Wav.encodeMono16(pcm))
        assertEquals(22_050, decoded.sampleRate)
        assertEquals(pcm.samples.size, decoded.samples.size)
        assertEquals(800, decoded.durationMs)
    }

    @Test
    fun rejectsTooShortAudio() = runBlocking {
        try {
            AudioDecoder.decode(Wav.encodeMono16(tone(50)))
            fail("expected UNSUPPORTED_AUDIO")
        } catch (e: StikSoundException) {
            assertEquals(ErrorCode.UNSUPPORTED_AUDIO, e.code)
        }
    }

    // ---- Preview playback ----------------------------------------------------------------------

    @Test
    fun previewPlaysExactlyTheSelection() = runBlocking {
        val written = ByteArrayOutputStream()
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.write(data) }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            val pcm = tone(1000, rate = 8000)
            val positions = mutableListOf<Int>()
            PreviewPlayer.play(pcm, 250, 750) { positions += it }
            val expected = AudioDsp.toPcm16(pcm.samples, 2000, 6000)
            val bytes = written.toByteArray()
            assertEquals(expected.size * 2, bytes.size)
            val played = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(played)
            assertArrayEquals(expected, played)
            assertTrue(positions.isNotEmpty())
            assertTrue(positions.all { it in 250..750 })
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }
}
