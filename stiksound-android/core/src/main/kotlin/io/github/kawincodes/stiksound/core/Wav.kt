package io.github.kawincodes.stiksound.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE PCM reader and writer (8/16/24/32-bit integer and 32-bit float). */
object Wav {
    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WAVE"

    fun decodeToMono(bytes: ByteArray): MonoPcm {
        if (!isWav(bytes)) fail(ErrorCode.UNSUPPORTED_AUDIO, "Not a RIFF/WAVE file.")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 1
        var channels = 1
        var sampleRate = 48_000
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val size = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            if (id == "fmt " && pos + 24 <= bytes.size) {
                format = buf.getShort(pos + 8).toInt() and 0xFFFF
                channels = buf.getShort(pos + 10).toInt() and 0xFFFF
                sampleRate = buf.getInt(pos + 12)
                bits = buf.getShort(pos + 22).toInt() and 0xFFFF
                if (format == 0xFFFE && size >= 26 && pos + 34 <= bytes.size) {
                    // WAVE_FORMAT_EXTENSIBLE: the real format is the first 2 bytes of the sub-format GUID.
                    format = buf.getShort(pos + 32).toInt() and 0xFFFF
                }
            } else if (id == "data") {
                dataOffset = pos + 8
                dataSize = minOf(size, (bytes.size - dataOffset).toLong()).toInt()
                break
            }
            pos += 8 + size.toInt() + (size % 2).toInt()
            if (pos < 0) break
        }
        if (dataOffset < 0) fail(ErrorCode.UNSUPPORTED_AUDIO, "WAV has no data chunk.")
        if (channels < 1 || sampleRate <= 0) fail(ErrorCode.UNSUPPORTED_AUDIO, "WAV header is invalid.")
        val isFloat = format == 3
        if (!(format == 1 || isFloat)) fail(ErrorCode.UNSUPPORTED_AUDIO, "Unsupported WAV encoding $format.")
        if (isFloat && bits != 32) fail(ErrorCode.UNSUPPORTED_AUDIO, "Unsupported float WAV bit depth $bits.")
        if (!isFloat && bits !in setOf(8, 16, 24, 32)) fail(ErrorCode.UNSUPPORTED_AUDIO, "Unsupported WAV bit depth $bits.")

        val bytesPerSample = bits / 8
        val frameCount = dataSize / (bytesPerSample * channels)
        val mono = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) {
                val o = dataOffset + (i * channels + c) * bytesPerSample
                sum += when {
                    isFloat -> buf.getFloat(o)
                    bits == 8 -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
                    bits == 16 -> buf.getShort(o) / 32768f
                    bits == 24 -> {
                        val v = (bytes[o].toInt() and 0xFF) or
                            ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                            (bytes[o + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    else -> buf.getInt(o) / 2147483648f
                }
            }
            mono[i] = sum / channels
        }
        return MonoPcm(mono, sampleRate)
    }

    fun encodeMono16(pcm: MonoPcm): ByteArray {
        val dataBytes = pcm.samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.ISO_8859_1))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.ISO_8859_1))
        buf.put("fmt ".toByteArray(Charsets.ISO_8859_1))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(pcm.sampleRate)
        buf.putInt(pcm.sampleRate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.ISO_8859_1))
        buf.putInt(dataBytes)
        for (s in AudioDsp.toPcm16(pcm.samples)) buf.putShort(s)
        return buf.array()
    }
}
