package io.github.kawincodes.stiksound.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.ErrorCode
import io.github.kawincodes.stiksound.core.Limits
import io.github.kawincodes.stiksound.core.MonoPcm
import io.github.kawincodes.stiksound.core.StikSoundException
import io.github.kawincodes.stiksound.core.Wav
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Serves an in-memory file to [MediaExtractor] without a temp file. */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = data.size.toLong()
    override fun close() = Unit
}

/** Grows a float buffer without boxing. */
private class FloatAccumulator(initial: Int = 1 shl 16) {
    var data = FloatArray(initial)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Decodes any audio Android supports (MP3, AAC/M4A, OGG Vorbis/Opus, FLAC, WAV, or
 * the sound track of a video file) to mono float PCM at the source sample rate.
 */
object AudioDecoder {
    private const val TIMEOUT_US = 10_000L

    suspend fun decode(bytes: ByteArray): MonoPcm =
        decode(bytes) { extractor -> extractor.setDataSource(ByteArrayMediaDataSource(bytes)) }

    /**
     * [setSource] points the extractor at the data. Production code serves [bytes] from
     * memory; Robolectric tests point it at a fake source registered with ShadowMediaExtractor.
     */
    internal suspend fun decode(bytes: ByteArray, setSource: (MediaExtractor) -> Unit): MonoPcm {
        if (bytes.size > Limits.MAX_AUDIO_BYTES) {
            throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "Audio is larger than 25 MB.")
        }
        val pcm = if (Wav.isWav(bytes)) {
            runCatching { Wav.decodeToMono(bytes) }.getOrNull() ?: decodeWithMediaCodec(setSource)
        } else {
            decodeWithMediaCodec(setSource)
        }
        if (pcm.durationMs < Limits.MIN_AUDIO_DURATION_MS) {
            throw StikSoundException(ErrorCode.UNSUPPORTED_AUDIO, "The audio is shorter than 0.1 s or could not be read.")
        }
        if (pcm.durationMs > Limits.MAX_AUDIO_DURATION_MS + Limits.DURATION_TOLERANCE_MS) {
            throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "The audio is longer than 3 minutes.")
        }
        return pcm
    }

    private suspend fun decodeWithMediaCodec(setSource: (MediaExtractor) -> Unit): MonoPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                setSource(extractor)
            } catch (e: Exception) {
                throw StikSoundException(ErrorCode.UNSUPPORTED_AUDIO, "This audio format is not supported.", e)
            }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw StikSoundException(ErrorCode.UNSUPPORTED_AUDIO, "No audio track found in this file.")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)
                if (durationUs > (Limits.MAX_AUDIO_DURATION_MS + 1000L) * 1000L) {
                    throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "The audio is longer than 3 minutes.")
                }
            }

            codec = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(inputFormat, null, null, 0) }
            } catch (e: Exception) {
                throw StikSoundException(ErrorCode.UNSUPPORTED_AUDIO, "No decoder for $mime on this device.", e)
            }
            codec.start()

            var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var floatPcm = false
            val mono = FloatAccumulator()
            var maxFrames = Long.MAX_VALUE
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        floatPcm = f.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        if (maxFrames == Long.MAX_VALUE) {
                            maxFrames = (Limits.MAX_AUDIO_DURATION_MS + 1000L) * sampleRate / 1000
                        }
                        val out = codec.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val ordered = out.slice().order(ByteOrder.nativeOrder())
                            val ch = maxOf(1, channels)
                            if (floatPcm) {
                                val fb = ordered.asFloatBuffer()
                                val frames = fb.remaining() / ch
                                for (i in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until ch) sum += fb.get(i * ch + c)
                                    mono.add(sum / ch)
                                }
                            } else {
                                val sb = ordered.asShortBuffer()
                                val frames = sb.remaining() / ch
                                for (i in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until ch) sum += sb.get(i * ch + c) / 32768f
                                    mono.add(sum / ch)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (mono.size > maxFrames) {
                            throw StikSoundException(ErrorCode.FILE_TOO_LARGE, "The audio is longer than 3 minutes.")
                        }
                    }
                }
            }
            return MonoPcm(mono.toArray(), sampleRate)
        } catch (e: StikSoundException) {
            throw e
        } catch (e: IllegalStateException) {
            throw StikSoundException(ErrorCode.UNSUPPORTED_AUDIO, "This audio could not be decoded.", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Waveform peaks for the editor. */
    fun peaks(pcm: MonoPcm) = AudioDsp.computePeaks(pcm.samples)
}

internal fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
