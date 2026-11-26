package io.github.kawincodes.stiksound.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import io.github.kawincodes.stiksound.core.AudioDsp
import io.github.kawincodes.stiksound.core.ErrorCode
import io.github.kawincodes.stiksound.core.FrameScheduler
import io.github.kawincodes.stiksound.core.ImageOps
import io.github.kawincodes.stiksound.core.Limits
import io.github.kawincodes.stiksound.core.MonoPcm
import io.github.kawincodes.stiksound.core.ScheduledFrame
import io.github.kawincodes.stiksound.core.StikSoundException
import io.github.kawincodes.stiksound.core.Yuv420
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** What to render: the framed sticker plus the selected, already trimmed audio. */
class VideoRequest(
    val sticker: StickerSource,
    val cropX: Float,
    val cropY: Float,
    val matteArgb: Int,
    /** 48 kHz mono, exactly [durationMs] long (padded or cut by the caller). */
    val audio: MonoPcm,
    val durationMs: Int,
)

class VideoResult(val file: File, val sizePx: Int, val durationMs: Int)

/**
 * Renders a square H.264 + AAC MP4 with MediaCodec and MediaMuxer, matching the
 * StikSound `video-mp4` profile: solid background, 48 kHz mono audio, at most 10 s.
 */
object Mp4Exporter {
    private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val VIDEO_BITRATE = 1_200_000
    private const val AUDIO_BITRATE = 96_000
    private const val TIMEOUT_US = 10_000L
    private const val YUV_CACHE_BYTES = 48L * 1024 * 1024

    internal class EncodedSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    internal class EncodedTrack(val format: MediaFormat, val samples: List<EncodedSample>)

    suspend fun render(request: VideoRequest, output: File, onProgress: (Float) -> Unit = {}): VideoResult {
        val timeline = FrameScheduler.forSticker(request.sticker.delaysMs, request.durationMs)
        val size = chooseVideoSize(request.sticker.exportSize)
        val fps = if (request.sticker.animated) Limits.GIF_VIDEO_FPS else Limits.STATIC_VIDEO_FPS
        val video = encodeVideo(request, timeline, size, fps) { onProgress(it * 0.85f) }
        val audio = encodeAudio(request.audio)
        onProgress(0.95f)
        mux(output, video, audio)
        onProgress(1f)
        return VideoResult(output, size, request.durationMs)
    }

    /** The export size, adjusted when the device's H.264 encoder can't take it as is. */
    fun chooseVideoSize(desired: Int): Int {
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(VIDEO_MIME, ignoreCase = true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(VIDEO_MIME).videoCapabilities }.getOrNull() }
        if (caps.isEmpty()) throw StikSoundException(ErrorCode.UNSUPPORTED_CODEC, "This device has no H.264 encoder.")
        val candidates = buildList {
            add(desired)
            add(((desired + 15) / 16 * 16).coerceAtMost(Limits.STICKER_DIMENSION_PX))
            var s = 128
            while (s <= Limits.STICKER_DIMENSION_PX) { if (s >= desired) add(s); s += 16 }
            add(Limits.STICKER_DIMENSION_PX)
        }.distinct()
        return candidates.firstOrNull { s -> caps.any { c -> runCatching { c.isSizeSupported(s, s) }.getOrDefault(false) } }
            ?: Limits.STICKER_DIMENSION_PX
    }

    internal suspend fun encodeVideo(
        request: VideoRequest,
        timeline: List<ScheduledFrame>,
        size: Int,
        fps: Int,
        onProgress: (Float) -> Unit,
    ): EncodedTrack {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, size, size).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw StikSoundException(ErrorCode.UNSUPPORTED_CODEC, "No H.264 encoder supports ${size}x$size.")
        val codec = MediaCodec.createByCodecName(codecName)
        val samples = ArrayList<EncodedSample>()
        var outputFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val yuvBytes = size.toLong() * size * 3 / 2
            val maxCached = maxOf(1, (YUV_CACHE_BYTES / yuvBytes).toInt())
            val cache = object : LinkedHashMap<Int, Yuv420>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Yuv420>?) = size > maxCached
            }
            fun frameYuv(index: Int): Yuv420 = cache.getOrPut(index) {
                val pixels = StickerLoader.croppedPixels(request.sticker, index, request.cropX, request.cropY, size)
                Yuv420.fromArgb(ImageOps.compositeOnMatte(pixels, request.matteArgb), size, size)
            }

            val info = MediaCodec.BufferInfo()
            var next = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (next < timeline.size) {
                            val frame = timeline[next]
                            if (frame.keyFrame && next > 0) {
                                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                            }
                            val yuv = frameYuv(frame.frameIndex)
                            val written = writeFrame(codec, inIndex, yuv)
                            codec.queueInputBuffer(inIndex, 0, written, frame.timestampUs, 0)
                            next++
                            onProgress(next.toFloat() / timeline.size)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, timeline.last().timestampUs + timeline.last().durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                outputDone = drain(codec, info, samples, if (inputDone) TIMEOUT_US else 0L) { outputFormat = it }
            }
        } catch (e: StikSoundException) {
            throw e
        } catch (e: IllegalStateException) {
            throw StikSoundException(ErrorCode.ENCODE_FAILED, "The video encoder failed: ${e.message}", e)
        } catch (e: MediaCodec.CodecException) {
            throw StikSoundException(ErrorCode.ENCODE_FAILED, "The video encoder failed: ${e.diagnosticInfo}", e)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        val fmt = outputFormat ?: throw StikSoundException(ErrorCode.ENCODE_FAILED, "The video encoder produced no output.")
        if (samples.isEmpty()) throw StikSoundException(ErrorCode.ENCODE_FAILED, "The video encoder produced no frames.")
        return EncodedTrack(fmt, samples)
    }

    /**
     * Copies a YUV 4:2:0 picture into the encoder's input. Uses the Image API, which
     * describes the device's real plane layout; falls back to NV12/I420 byte buffers.
     */
    @Suppress("DEPRECATION") // The legacy color constants are only used on the no-Image fallback path.
    private fun writeFrame(codec: MediaCodec, index: Int, yuv: Yuv420): Int {
        // Read the capacity first: asking for the Image invalidates earlier ByteBuffer views.
        val capacity = codec.getInputBuffer(index)?.capacity() ?: (yuv.y.size * 3 / 2)
        val image = runCatching { codec.getInputImage(index) }.getOrNull()
        if (image != null) {
            val planes = image.planes
            writePlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, yuv.y, yuv.width, yuv.height)
            writePlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, yuv.u, yuv.chromaWidth, yuv.chromaHeight)
            writePlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, yuv.v, yuv.chromaWidth, yuv.chromaHeight)
            return capacity
        }
        val buffer = codec.getInputBuffer(index)
            ?: throw StikSoundException(ErrorCode.ENCODE_FAILED, "The video encoder has no input buffer.")
        val colorFormat = codec.inputFormat.getIntegerOr(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        )
        buffer.clear()
        buffer.put(yuv.y)
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            buffer.put(yuv.u)
            buffer.put(yuv.v)
        } else {
            for (i in yuv.u.indices) { buffer.put(yuv.u[i]); buffer.put(yuv.v[i]) }
        }
        return buffer.position()
    }

    private fun writePlane(dst: ByteBuffer, rowStride: Int, pixelStride: Int, src: ByteArray, width: Int, height: Int) {
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (pixelStride == 1) {
                dst.position(rowStart)
                dst.put(src, row * width, width)
            } else {
                var p = rowStart
                val srcRow = row * width
                for (col in 0 until width) {
                    dst.put(p, src[srcRow + col])
                    p += pixelStride
                }
            }
        }
    }

    internal fun encodeAudio(pcm: MonoPcm): EncodedTrack {
        val format = MediaFormat.createAudioFormat(AUDIO_MIME, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw StikSoundException(ErrorCode.UNSUPPORTED_CODEC, "This device has no AAC encoder.")
        val codec = MediaCodec.createByCodecName(codecName)
        val samples = ArrayList<EncodedSample>()
        var outputFormat: MediaFormat? = null
        val pcm16 = AudioDsp.toPcm16(pcm.samples)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        buffer.clear()
                        val ptsUs = offset * 1_000_000L / pcm.sampleRate
                        if (offset < pcm16.size) {
                            val count = minOf(buffer.remaining() / 2, 2048, pcm16.size - offset)
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm16, offset, count)
                            offset += count
                            codec.queueInputBuffer(inIndex, 0, count * 2, ptsUs, 0)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                outputDone = drain(codec, info, samples, if (inputDone) TIMEOUT_US else 0L) { outputFormat = it }
            }
        } catch (e: IllegalStateException) {
            throw StikSoundException(ErrorCode.ENCODE_FAILED, "The audio encoder failed: ${e.message}", e)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        val fmt = outputFormat ?: throw StikSoundException(ErrorCode.ENCODE_FAILED, "The audio encoder produced no output.")
        return EncodedTrack(fmt, samples)
    }

    /** Pulls finished buffers off an encoder. Returns true once end of stream is reached. */
    private inline fun drain(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        sink: MutableList<EncodedSample>,
        timeoutUs: Long,
        onFormat: (MediaFormat) -> Unit,
    ): Boolean {
        while (true) {
            // Wait briefly once input is finished, instead of spinning while the encoder flushes.
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data, 0, info.size)
                        sink += EncodedSample(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun mux(output: File, video: EncodedTrack, audio: EncodedTrack) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val videoTrack = muxer.addTrack(video.format)
            val audioTrack = muxer.addTrack(audio.format)
            muxer.start()
            started = true
            // Interleave the two tracks by timestamp so players can stream the file, but never
            // reorder samples inside a track (B-frames arrive in decode order, not pts order).
            val info = MediaCodec.BufferInfo()
            var v = 0
            var a = 0
            while (v < video.samples.size || a < audio.samples.size) {
                val takeVideo = a >= audio.samples.size ||
                    (v < video.samples.size && video.samples[v].ptsUs <= audio.samples[a].ptsUs)
                val (track, sample) = if (takeVideo) videoTrack to video.samples[v++] else audioTrack to audio.samples[a++]
                info.set(0, sample.data.size, sample.ptsUs, sample.flags)
                muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
            }
        } catch (e: IllegalStateException) {
            throw StikSoundException(ErrorCode.ENCODE_FAILED, "Writing the MP4 failed: ${e.message}", e)
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
        if (!output.exists() || output.length() == 0L) {
            throw StikSoundException(ErrorCode.ENCODE_FAILED, "The MP4 file is empty.")
        }
    }
}
