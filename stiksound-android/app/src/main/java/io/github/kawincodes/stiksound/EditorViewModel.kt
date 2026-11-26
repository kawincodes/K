package io.github.kawincodes.stiksound

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.AudioTrim
import io.github.kawincodes.stiksound.core.ErrorCode
import io.github.kawincodes.stiksound.core.ImageOps
import io.github.kawincodes.stiksound.core.Limits
import io.github.kawincodes.stiksound.core.MonoPcm
import io.github.kawincodes.stiksound.core.MyInstantsProvider
import io.github.kawincodes.stiksound.core.PeakBuckets
import io.github.kawincodes.stiksound.core.StikSoundException
import io.github.kawincodes.stiksound.core.TrimRange
import io.github.kawincodes.stiksound.core.UrlPolicy
import io.github.kawincodes.stiksound.media.AudioDecoder
import io.github.kawincodes.stiksound.media.InputReader
import io.github.kawincodes.stiksound.media.Mp4Exporter
import io.github.kawincodes.stiksound.media.Outputs
import io.github.kawincodes.stiksound.media.PreviewPlayer
import io.github.kawincodes.stiksound.media.StickerLoader
import io.github.kawincodes.stiksound.media.StickerSource
import io.github.kawincodes.stiksound.media.VideoRequest
import io.github.kawincodes.stiksound.media.WebpExporter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Matte { WHITE, BLACK, CUSTOM }

enum class Busy { STICKER, AUDIO, DOWNLOAD, EXPORT }

class AudioClip(
    val name: String,
    val pcm: MonoPcm,
    val peaks: PeakBuckets,
    val fromMyInstants: Boolean,
) {
    val durationMs: Int get() = pcm.durationMs
}

sealed interface ExportResult {
    val file: File
    val kind: Outputs.Kind

    class Video(override val file: File, val sizePx: Int, val durationMs: Int) : ExportResult {
        override val kind get() = Outputs.Kind.VIDEO
    }

    class Webp(override val file: File) : ExportResult {
        override val kind get() = Outputs.Kind.WEBP
    }
}

class UiMessage(@param:StringRes val resId: Int, vararg val args: Any) {
    val id: Long = System.nanoTime()
}

data class EditorState(
    val sticker: StickerSource? = null,
    val cropX: Float = 0.5f,
    val cropY: Float = 0.5f,
    val audio: AudioClip? = null,
    val trim: TrimRange? = null,
    val maxDurationMs: Int = Limits.DEFAULT_VIDEO_MAX_AUDIO_MS,
    val matte: Matte = Matte.WHITE,
    val customColor: Int = 0xFF25D366.toInt(),
    val busy: Busy? = null,
    val progress: Float? = null,
    val playheadMs: Int? = null,
    val result: ExportResult? = null,
    val savedToGallery: Boolean = false,
    val message: UiMessage? = null,
) {
    val playing: Boolean get() = playheadMs != null

    val matteArgb: Int
        get() = when (matte) {
            Matte.WHITE -> 0xFFFFFFFF.toInt()
            Matte.BLACK -> 0xFF000000.toInt()
            Matte.CUSTOM -> customColor or 0xFF000000.toInt()
        }

    /** Shortest selection: one loop of an animated GIF, at least 0.1 s. */
    val minTrimMs: Int get() = maxOf(Limits.MIN_TRIM_DURATION_MS, sticker?.loopDurationMs ?: 0)

    /** Why the MP4 export is disabled, or null when it can run. */
    @get:StringRes
    val videoBlocker: Int?
        get() = when {
            sticker == null -> R.string.export_needs_sticker
            audio == null || trim == null -> R.string.export_needs_audio
            busy != null -> R.string.export_busy
            else -> null
        }
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var workJob: Job? = null
    private var playJob: Job? = null
    private val provider = MyInstantsProvider()

    private val resolver get() = getApplication<Application>().contentResolver

    // ---- Inputs -------------------------------------------------------------------------------

    fun loadSticker(uri: Uri) = runWork(Busy.STICKER) {
        val file = withContext(Dispatchers.IO) { InputReader.read(resolver, uri, Limits.MAX_IMAGE_BYTES, "Image") }
        val sticker = withContext(Dispatchers.Default) { StickerLoader.load(file.name, file.bytes) }
        _state.update { s ->
            val next = s.copy(sticker = sticker, cropX = 0.5f, cropY = 0.5f, result = null)
            next.copy(trim = normalizedTrim(next))
        }
    }

    fun loadAudio(uri: Uri) = runWork(Busy.AUDIO) {
        val file = withContext(Dispatchers.IO) { InputReader.read(resolver, uri, Limits.MAX_AUDIO_BYTES, "Audio") }
        setAudio(file.name, file.bytes, fromMyInstants = false)
    }

    fun fetchMyInstants(rawUrl: String) {
        val url = extractUrl(rawUrl)
        if (url == null || !provider.canResolve(url)) {
            showMessage(UiMessage(R.string.error_url_rejected))
            return
        }
        runWork(Busy.DOWNLOAD) {
            val sound = withContext(Dispatchers.IO) { provider.resolve(url) }
            val name = sound.title ?: sound.mediaUrl.substringAfterLast('/')
            setAudio(name, sound.bytes, fromMyInstants = true)
        }
    }

    private suspend fun setAudio(name: String, bytes: ByteArray, fromMyInstants: Boolean) {
        stopPreview()
        val pcm = withContext(Dispatchers.Default) { AudioDecoder.decode(bytes) }
        val peaks = withContext(Dispatchers.Default) { AudioDecoder.peaks(pcm) }
        val clip = AudioClip(name.substringBeforeLast('.').ifBlank { name }, pcm, peaks, fromMyInstants)
        _state.update { s ->
            val trim = AudioTrim.defaultTrim(clip.durationMs, s.maxDurationMs)
            val next = s.copy(audio = clip, trim = trim, result = null)
            next.copy(trim = normalizedTrim(next))
        }
    }

    /** Handles ACTION_SEND from other apps: images become the sticker, audio/video the sound, text a MyInstants link. */
    fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        when {
            type.startsWith("image/") && stream != null -> loadSticker(stream)
            (type.startsWith("audio/") || type.startsWith("video/")) && stream != null -> loadAudio(stream)
            type.startsWith("text/") -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { fetchMyInstants(it) }
        }
    }

    // ---- Editing ------------------------------------------------------------------------------

    fun setCrop(x: Float, y: Float) = _state.update { it.copy(cropX = x.coerceIn(0f, 1f), cropY = y.coerceIn(0f, 1f)) }

    fun setMaxDuration(ms: Int) {
        if (ms !in Limits.AUDIO_DURATION_PRESETS) return
        _state.update { s -> s.copy(maxDurationMs = ms).let { it.copy(trim = normalizedTrim(it)) } }
    }

    fun setTrim(range: TrimRange) {
        _state.update { s -> s.copy(trim = range).let { it.copy(trim = normalizedTrim(it)) } }
    }

    fun setMatte(matte: Matte) = _state.update { it.copy(matte = matte) }

    fun setCustomColor(argb: Int) = _state.update { it.copy(matte = Matte.CUSTOM, customColor = argb or 0xFF000000.toInt()) }

    fun setCustomColorHex(hex: String): Boolean {
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6 || clean.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return false
        setCustomColor(ImageOps.parseHexColor(clean))
        return true
    }

    private fun normalizedTrim(s: EditorState): TrimRange? {
        val audio = s.audio ?: return null
        val current = s.trim ?: AudioTrim.defaultTrim(audio.durationMs, s.maxDurationMs)
        return AudioTrim.normalize(current, audio.durationMs, s.maxDurationMs, s.minTrimMs)
    }

    // ---- Preview playback ---------------------------------------------------------------------

    fun togglePreview() {
        if (playJob?.isActive == true) {
            stopPreview()
            return
        }
        val s = _state.value
        val audio = s.audio ?: return
        val trim = s.trim ?: return
        playJob = viewModelScope.launch {
            try {
                _state.update { it.copy(playheadMs = trim.startMs) }
                PreviewPlayer.play(audio.pcm, trim.startMs, trim.endMs) { pos ->
                    _state.update { it.copy(playheadMs = pos.coerceAtMost(trim.endMs)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(UiMessage(R.string.error_playback))
            } finally {
                _state.update { it.copy(playheadMs = null) }
            }
        }
    }

    fun stopPreview() {
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(playheadMs = null) }
    }

    // ---- Export -------------------------------------------------------------------------------

    fun exportVideo() {
        val s = _state.value
        val sticker = s.sticker ?: return
        val audio = s.audio ?: return
        val trim = s.trim ?: return
        stopPreview()
        runWork(Busy.EXPORT) {
            AudioTrim.validate(trim, audio.durationMs, s.maxDurationMs, s.minTrimMs)
            val durationMs = AudioTrim.exportDurationMs(trim, s.maxDurationMs, sticker.loopDurationMs)
            val result = withContext(Dispatchers.Default) {
                val clip = AudioDsp.trim(audio.pcm, trim.startMs, trim.endMs, 1f, s.maxDurationMs)
                val fitted = AudioDsp.fitToDuration(clip, durationMs)
                val name = "${Outputs.safeName(audio.name.ifBlank { sticker.name })}-${s.maxDurationMs / 1000}s.mp4"
                val output = Outputs.cacheFile(getApplication(), name)
                Mp4Exporter.render(
                    VideoRequest(sticker, s.cropX, s.cropY, s.matteArgb, fitted, durationMs),
                    output,
                ) { p -> _state.update { it.copy(progress = p) } }
            }
            _state.update { it.copy(result = ExportResult.Video(result.file, result.sizePx, result.durationMs), savedToGallery = false) }
        }
    }

    fun exportWebp() {
        val s = _state.value
        val sticker = s.sticker ?: return
        stopPreview()
        runWork(Busy.EXPORT) {
            val file = withContext(Dispatchers.Default) {
                val output = Outputs.cacheFile(getApplication(), "${Outputs.safeName(sticker.name)}-sticker.webp")
                WebpExporter.export(sticker, s.cropX, s.cropY, output)
            }
            _state.update { it.copy(result = ExportResult.Webp(file), savedToGallery = false) }
        }
    }

    fun saveResult() {
        val result = _state.value.result ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Outputs.saveToGallery(getApplication(), result.file, result.kind) }
                _state.update { it.copy(savedToGallery = true) }
                showMessage(
                    UiMessage(if (result.kind == Outputs.Kind.VIDEO) R.string.saved_video else R.string.saved_webp),
                )
            } catch (e: Exception) {
                showMessage(UiMessage(R.string.error_save))
            }
        }
    }

    fun closeResult() = _state.update { it.copy(result = null) }

    fun reset() {
        stopPreview()
        workJob?.cancel()
        _state.value = EditorState(
            maxDurationMs = _state.value.maxDurationMs,
            matte = _state.value.matte,
            customColor = _state.value.customColor,
        )
    }

    fun consumeMessage(id: Long) = _state.update { if (it.message?.id == id) it.copy(message = null) else it }

    fun showMessage(message: UiMessage) = _state.update { it.copy(message = message) }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun runWork(kind: Busy, block: suspend () -> Unit) {
        if (_state.value.busy == Busy.EXPORT) return
        workJob?.cancel()
        workJob = viewModelScope.launch {
            _state.update { it.copy(busy = kind, progress = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: StikSoundException) {
                showMessage(messageFor(e))
            } catch (e: OutOfMemoryError) {
                showMessage(UiMessage(R.string.error_file_too_large))
            } catch (e: Exception) {
                showMessage(UiMessage(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            } finally {
                _state.update { if (it.busy == kind) it.copy(busy = null, progress = null) else it }
            }
        }
    }

    private fun messageFor(e: StikSoundException): UiMessage = when (e.code) {
        ErrorCode.UNSUPPORTED_IMAGE -> UiMessage(R.string.error_unsupported_image)
        ErrorCode.UNSUPPORTED_AUDIO -> UiMessage(R.string.error_unsupported_audio)
        ErrorCode.FILE_TOO_LARGE -> UiMessage(R.string.error_file_too_large)
        ErrorCode.TRIM_OUT_OF_RANGE -> UiMessage(R.string.error_trim)
        ErrorCode.URL_REJECTED -> UiMessage(R.string.error_url_rejected)
        ErrorCode.REMOTE_UNAVAILABLE -> UiMessage(R.string.error_remote)
        ErrorCode.UNSUPPORTED_CODEC -> UiMessage(R.string.error_codec)
        ErrorCode.ENCODE_FAILED -> UiMessage(R.string.error_encode, e.message ?: "")
    }

    override fun onCleared() {
        playJob?.cancel()
        workJob?.cancel()
    }

    companion object {
        private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

        /** Finds the first http(s) link in shared or pasted text and strips trailing punctuation. */
        fun extractUrl(text: String): String? =
            URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '"', '\'', '>') }
                .firstOrNull { UrlPolicy.isAllowed(it) }
                ?: URL.find(text)?.value
    }
}
