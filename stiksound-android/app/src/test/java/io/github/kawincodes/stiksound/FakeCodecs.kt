package io.github.kawincodes.stiksound

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaCodecList

/**
 * Deterministic stand-ins for the device codecs, registered with Robolectric's
 * ShadowMediaCodec / ShadowMediaCodecList. They let the real MediaCodec loops in the
 * app run on the JVM. Instead of H.264/AAC they emit small records that tests can
 * read back from the (raw) file ShadowMediaMuxer writes:
 *
 * - video: 'V', Y at top-left, U and V at top-left, Y at center, input length (Int)
 * - audio: 'A', PCM byte count (Int), first sample (Short)
 */
object FakeCodecs {
    const val VIDEO_ENCODER = "fake.h264.encoder"
    const val AUDIO_ENCODER = "fake.aac.encoder"
    const val VIDEO_TAG = 'V'.code.toByte()
    const val AUDIO_TAG = 'A'.code.toByte()
    const val VIDEO_RECORD_BYTES = 9
    const val AUDIO_RECORD_BYTES = 7

    private const val VIDEO_INPUT_BYTES = 512 * 512 * 3 / 2 + 1024

    fun install(maxVideoSize: Int = 1920) {
        ShadowMediaCodecList.reset()
        ShadowMediaCodec.clearCodecs()

        val avc = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC)
            setInteger("max-width", maxVideoSize)
            setInteger("max-height", maxVideoSize)
        }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName(VIDEO_ENCODER)
                .setIsEncoder(true)
                .setCapabilities(
                    MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(avc)
                        .setIsEncoder(true)
                        .setProfileLevels(
                            arrayOf(
                                CodecProfileLevel().apply {
                                    profile = CodecProfileLevel.AVCProfileBaseline
                                    level = CodecProfileLevel.AVCLevel41
                                },
                            ),
                        )
                        .setColorFormats(
                            intArrayOf(
                                CodecCapabilities.COLOR_FormatYUV420Flexible,
                                @Suppress("DEPRECATION") CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                            ),
                        )
                        .build(),
                )
                .build(),
        )

        val aac = MediaFormat().apply { setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC) }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName(AUDIO_ENCODER)
                .setIsEncoder(true)
                .setCapabilities(
                    MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(aac)
                        .setIsEncoder(true)
                        .setProfileLevels(arrayOf(CodecProfileLevel().apply { profile = CodecProfileLevel.AACObjectLC }))
                        .build(),
                )
                .build(),
        )

        val video = ShadowMediaCodec.CodecConfig(VIDEO_INPUT_BYTES, 64) { input, output ->
            val len = input.remaining()
            if (len == 0) return@CodecConfig // end-of-stream buffer
            val side = sqrt(len / 1.5).roundToInt()
            val base = input.position()
            val center = base + (side / 2) * side + side / 2
            output.put(VIDEO_TAG)
            output.put(input.get(base))
            output.put(input.get(base + side * side)) // first chroma byte (U for NV12 and I420)
            output.put(input.get(base + side * side + 1)) // second chroma byte (V for NV12)
            output.put(input.get(center))
            output.order(ByteOrder.LITTLE_ENDIAN).putInt(len)
        }
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, video)
        ShadowMediaCodec.addEncoder(VIDEO_ENCODER, video)

        val audio = ShadowMediaCodec.CodecConfig(16 * 1024, 64) { input, output ->
            val len = input.remaining()
            if (len == 0) return@CodecConfig
            val first = input.duplicate().order(ByteOrder.LITTLE_ENDIAN).getShort(input.position())
            output.put(AUDIO_TAG)
            output.order(ByteOrder.LITTLE_ENDIAN).putInt(len)
            output.putShort(first)
        }
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC, audio)
        ShadowMediaCodec.addEncoder(AUDIO_ENCODER, audio)
    }

    /** Registers a pass-through "decoder": the extractor's samples are already PCM 16-bit. */
    fun installPcmDecoder(mime: String) {
        ShadowMediaCodec.addDecoder(mime, ShadowMediaCodec.CodecConfig(4096, 4096) { input, output -> output.put(input) })
    }

    class VideoRecord(val yCorner: Int, val uCorner: Int, val vCorner: Int, val yCenter: Int, val inputBytes: Int)

    class AudioRecord(val pcmBytes: Int, val firstSample: Short)

    /** Parses the raw file written by ShadowMediaMuxer into the tagged records, in write order. */
    fun parse(bytes: ByteArray): List<Any> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Any>()
        while (buf.hasRemaining()) {
            when (buf.get()) {
                VIDEO_TAG -> out += VideoRecord(
                    buf.get().toInt() and 0xFF,
                    buf.get().toInt() and 0xFF,
                    buf.get().toInt() and 0xFF,
                    buf.get().toInt() and 0xFF,
                    buf.getInt(),
                )
                AUDIO_TAG -> out += AudioRecord(buf.getInt(), buf.getShort())
                else -> error("Unexpected record at ${buf.position() - 1}")
            }
        }
        return out
    }
}
