package io.github.kawincodes.stiksound

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.content.IntentCompat
import io.github.kawincodes.stiksound.core.MonoPcm
import io.github.kawincodes.stiksound.core.Wav
import io.github.kawincodes.stiksound.ui.StikSoundTheme
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real screens and ViewModel on the JVM: files come in through content
 * Uris, the user drags the crop and the trim window, and exports through the buttons.
 * Codecs are the Robolectric fakes from [FakeCodecs].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class EditorFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: EditorViewModel

    @Before
    fun setUp() {
        FakeCodecs.install()
        vm = EditorViewModel(compose.activity.application)
        compose.setContent { StikSoundTheme { StikSoundApp(vm) } }
    }

    private val state get() = vm.state.value

    /**
     * Waits for work the ViewModel runs on background threads. The Compose test rule makes
     * Dispatchers.Main a test dispatcher, so the main clock has to be advanced for results
     * to land back on "main"; the real main looper is drained too.
     */
    private fun waitFor(timeoutMs: Long = 15_000, what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!what()) {
            if (System.currentTimeMillis() > deadline) {
                timedOut()
            }
            compose.mainClock.advanceTimeBy(32)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(5)
        }
    }

    private fun timedOut(): Nothing {
        val s = state
        val message = s.message?.let { compose.activity.getString(it.resId, *it.args) }
        val workers = Thread.getAllStackTraces()
            .filterKeys { it.name.startsWith("DefaultDispatcher") }
            .filterValues { stack -> stack.any { it.className.startsWith("io.github.kawincodes") } }
            .entries.joinToString("\n") { (t, stack) -> "${t.name}:\n  " + stack.take(25).joinToString("\n  ") }
        throw AssertionError(
            "Timed out. busy=${s.busy} sticker=${s.sticker != null} audio=${s.audio != null} " +
                "result=${s.result} message=$message\n$workers",
        )
    }

    private fun stickerFile(): Uri {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        Canvas(bitmap).drawCircle(320f, 240f, 200f, Paint().apply { color = Color.rgb(255, 196, 0) })
        val file = File(compose.activity.cacheDir, "smile.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun audioFile(seconds: Int): Uri {
        val rate = 16_000
        val pcm = MonoPcm(FloatArray(rate * seconds) { i -> (0.5 * sin(2 * PI * 330 * i / rate)).toFloat() }, rate)
        val file = File(compose.activity.cacheDir, "tone.wav")
        file.writeBytes(Wav.encodeMono16(pcm))
        return Uri.fromFile(file)
    }

    private fun loadBoth(audioSeconds: Int = 3) {
        vm.loadSticker(stickerFile())
        waitFor { state.sticker != null && state.busy == null }
        vm.loadAudio(audioFile(audioSeconds))
        waitFor { state.audio != null && state.busy == null }
    }

    @Test
    fun exportButtonExplainsWhatIsMissing() {
        compose.onNodeWithText("Create 10s MP4 video").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Add a sticker first.").assertExists()
        vm.loadSticker(stickerFile())
        waitFor { state.sticker != null && state.busy == null }
        compose.onNodeWithText("Add an audio file or a MyInstants link.").assertExists()
        vm.loadAudio(audioFile(3))
        waitFor { state.audio != null && state.busy == null }
        compose.onNodeWithText("Create 10s MP4 video").performScrollTo().assertIsEnabled()
    }

    @Test
    fun createsAVideoAndSharesIt() {
        loadBoth()
        assertEquals(3000, state.trim?.endMs)
        compose.onNodeWithText("Create 10s MP4 video").performScrollTo().performClick()
        waitFor { state.result != null }

        val result = state.result as ExportResult.Video
        assertTrue(result.file.length() > 0)
        assertEquals(3000, result.durationMs)
        assertEquals(480, result.sizePx)
        val frames = FakeCodecs.parse(result.file.readBytes()).filterIsInstance<FakeCodecs.VideoRecord>()
        assertEquals(30, frames.size) // 3 s at 10 fps

        compose.onNodeWithText("Your video").assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().performClick()
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        assertEquals("video/mp4", send!!.type)
        val stream = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals("content", stream?.scheme)
        assertEquals("io.github.kawincodes.stiksound.files", stream?.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Create 10s MP4 video").assertExists()
    }

    @Test
    fun createsAStaticWebpSticker() {
        vm.loadSticker(stickerFile())
        waitFor { state.sticker != null && state.busy == null }
        compose.onNodeWithText("Static WebP sticker (512×512, no sound)").performScrollTo().performClick()
        waitFor { state.result != null }
        val result = state.result as ExportResult.Webp
        assertTrue(result.file.length() in 1..102_400)
        compose.onNodeWithText("Your sticker").assertIsDisplayed()
    }

    @Test
    fun draggingTheStickerMovesTheCrop() {
        vm.loadSticker(stickerFile())
        waitFor { state.sticker != null && state.busy == null }
        assertEquals(0.5f, state.cropX)
        compose.onNode(hasContentDescription("Sticker preview", substring = true)).performTouchInput {
            swipe(center, center - Offset(width * 0.3f, 0f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertTrue("cropX ${state.cropX}", state.cropX > 0.5f)
        assertEquals(0.5f, state.cropY)
    }

    @Test
    fun draggingTheWaveformMovesAndResizesTheSelection() {
        loadBoth(audioSeconds = 20)
        assertEquals(0, state.trim?.startMs)
        assertEquals(10_000, state.trim?.endMs)

        val bar = compose.onNode(hasContentDescription("Audio selection", substring = true))
        bar.performScrollTo()
        // Drag the middle of the window to the right: same length, later start.
        bar.performTouchInput { swipe(Offset(width * 0.25f, centerY), Offset(width * 0.45f, centerY), 300) }
        compose.waitForIdle()
        val moved = state.trim!!
        assertTrue("moved start ${moved.startMs}", moved.startMs > 2_000)
        assertEquals(10_000, moved.durationMs)

        // Switch to 5 s: the window shrinks to the new cap.
        compose.onNodeWithText("5s").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(5_000, state.trim!!.durationMs)
        assertEquals(moved.startMs, state.trim!!.startMs)

        // Drag the left edge to the right: shorter window, same end.
        val before = state.trim!!
        val startX = before.startMs / 20_000f
        bar.performTouchInput { swipe(Offset(width * startX + 2f, centerY), Offset(width * (startX + 0.08f), centerY), 300) }
        compose.waitForIdle()
        val resized = state.trim!!
        assertEquals(before.endMs, resized.endMs)
        assertTrue("resized ${resized.durationMs}", resized.durationMs < before.durationMs)
        compose.onNodeWithText("Create 5s MP4 video").assertExists()
    }

    @Test
    fun rejectsLinksThatAreNotMyInstants() {
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("https://example.com/sound.mp3")
        compose.onNode(hasSetTextAction()).assert(hasText("https://example.com/sound.mp3"))
        compose.onNodeWithText("Get").assertIsEnabled().performClick()
        assertEquals(R.string.error_url_rejected, state.message?.resId)
        waitFor(5_000) {
            compose.onAllNodes(hasText("Only MyInstants links are supported", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(null, state.audio)
        // Once the snackbar times out the message is cleared, so it isn't shown again.
        waitFor(15_000) { state.message == null }
    }

    @Test
    fun sharedImageBecomesTheSticker() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, stickerFile())
        }
        vm.handleShare(share)
        waitFor { state.sticker != null && state.busy == null }
        assertEquals(640, state.sticker!!.originalWidth)
    }
}
