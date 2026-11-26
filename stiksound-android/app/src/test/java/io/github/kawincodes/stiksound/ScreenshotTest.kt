package io.github.kawincodes.stiksound

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.ImageFormat
import io.github.kawincodes.stiksound.core.MonoPcm
import io.github.kawincodes.stiksound.core.TrimRange
import io.github.kawincodes.stiksound.media.Outputs
import io.github.kawincodes.stiksound.media.StickerSource
import io.github.kawincodes.stiksound.ui.EditorScreen
import io.github.kawincodes.stiksound.ui.ResultScreen
import io.github.kawincodes.stiksound.ui.StikSoundTheme
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main screens to app/build/screenshots so the layout can be reviewed without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val vm by lazy { EditorViewModel(ApplicationProvider.getApplicationContext()) }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/screenshots/$name.png")

    private fun stickerBitmap(): Bitmap {
        val b = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.TRANSPARENT)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(255, 196, 0)
        c.drawCircle(320f, 240f, 200f, p)
        p.color = Color.rgb(40, 30, 20)
        c.drawCircle(250f, 190f, 26f, p)
        c.drawCircle(390f, 190f, 26f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 22f
        p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(200f, 160f, 440f, 360f), 20f, 140f, false, p)
        return b
    }

    private fun audioClip(): AudioClip {
        val rate = 8000
        val samples = FloatArray(rate * 7) { i ->
            val t = i.toFloat() / rate
            val envelope = exp(-((t % 1.4f) * 2.2f))
            (envelope * sin(2 * PI * 220 * t) * (0.4 + 0.6 * sin(t * 1.3))).toFloat()
        }
        val pcm = MonoPcm(samples, rate)
        return AudioClip("Airhorn Classic", pcm, AudioDsp.computePeaks(pcm.samples), fromMyInstants = true)
    }

    private fun editor(state: EditorState) {
        compose.setContent {
            StikSoundTheme { EditorScreen(state, vm, SnackbarHostState(), onAbout = {}) }
        }
    }

    private fun filledState() = EditorState(
        sticker = StickerSource("smile.png", ImageFormat.PNG, 640, 480, listOf(stickerBitmap()), listOf(0), hasAlpha = true),
        cropX = 0.35f,
        audio = audioClip(),
        trim = TrimRange(1200, 6200),
        maxDurationMs = 5000,
        matte = Matte.CUSTOM,
        customColor = 0xFF34B7F1.toInt(),
        playheadMs = 3100,
    )

    @Test
    fun editorEmpty() {
        editor(EditorState())
        shot("editor-empty")
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    fun editorFilled() {
        editor(filledState())
        shot("editor-filled")
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-night-xxhdpi")
    fun editorFilledDark() {
        editor(filledState())
        shot("editor-filled-dark")
    }

    @Test
    @Config(qualifiers = "es-w411dp-h1900dp-xxhdpi")
    fun editorSpanish() {
        editor(filledState().copy(busy = Busy.EXPORT, progress = 0.4f))
        shot("editor-exporting-es")
    }

    @Test
    fun resultWebp() {
        val file = File.createTempFile("sticker", ".webp")
        file.outputStream().use { stickerBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.setContent {
            StikSoundTheme {
                ResultScreen(ExportResult.Webp(file), saved = false, SnackbarHostState(), {}, {}, {})
            }
        }
        shot("result-webp")
    }

    @Test
    fun safeNamesAndLinks() {
        org.junit.Assert.assertEquals("airhorn-classic", Outputs.safeName("Airhorn Classic.mp3"))
        org.junit.Assert.assertEquals("stiksound", Outputs.safeName("¿¿??.png"))
        org.junit.Assert.assertEquals(
            "https://www.myinstants.com/en/instant/bruh-12345/",
            EditorViewModel.extractUrl("Check this out: https://www.myinstants.com/en/instant/bruh-12345/."),
        )
        org.junit.Assert.assertEquals(null, EditorViewModel.extractUrl("no link here"))
    }
}
