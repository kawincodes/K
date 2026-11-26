package io.github.kawincodes.stiksound.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioTest {
    private fun sine(rate: Int, ms: Int, freq: Double = 440.0, amp: Float = 0.5f) =
        FloatArray(rate * ms / 1000) { i -> (amp * sin(2 * PI * freq * i / rate)).toFloat() }

    @Test
    fun peaksCaptureMinAndMaxPerBucket() {
        val samples = floatArrayOf(0f, 1f, -1f, 0.5f, 0.25f, -0.25f, 0f, 0f)
        val peaks = AudioDsp.computePeaks(samples, 4)
        assertArrayEquals(floatArrayOf(0f, -1f, -0.25f, 0f), peaks.mins, 0f)
        assertArrayEquals(floatArrayOf(1f, 0.5f, 0.25f, 0f), peaks.maxs, 0f)
    }

    @Test
    fun peaksOfEmptyAudioAreZero() {
        val peaks = AudioDsp.computePeaks(FloatArray(0), 16)
        assertEquals(16, peaks.size)
        assertTrue(peaks.maxs.all { it == 0f })
    }

    @Test
    fun peaksHandleFewerSamplesThanBuckets() {
        val peaks = AudioDsp.computePeaks(floatArrayOf(0.5f, -0.5f), 8)
        assertEquals(8, peaks.size)
        assertEquals(0.5f, peaks.maxs[0])
    }

    @Test
    fun resampleKeepsDuration() {
        val src = sine(44_100, 1000)
        val out = AudioDsp.resampleLinear(src, 44_100, 48_000)
        assertEquals(48_000, out.size)
        assertEquals(src.size, AudioDsp.resampleLinear(src, 44_100, 44_100).size)
    }

    @Test
    fun trimCutsAppliesVolumeAndResamples() {
        val pcm = MonoPcm(FloatArray(44_100 * 3) { 0.8f }, 44_100)
        val out = AudioDsp.trim(pcm, 500, 1500, volume = 0.5f)
        assertEquals(48_000, out.sampleRate)
        assertEquals(1000, out.durationMs)
        assertTrue(out.samples.all { abs(it - 0.4f) < 1e-5 })
    }

    @Test
    fun trimRejectsBadRanges() {
        val pcm = MonoPcm(FloatArray(48_000), 48_000)
        for ((s, e) in listOf(0 to 50, 500 to 400, 0 to 2000)) {
            try {
                AudioDsp.trim(pcm, s, e)
                fail("expected rejection for $s..$e")
            } catch (ex: StikSoundException) {
                assertEquals(ErrorCode.TRIM_OUT_OF_RANGE, ex.code)
            }
        }
    }

    @Test
    fun padAndFitMatchTargetLength() {
        val pcm = MonoPcm(FloatArray(48_000) { 0.1f }, 48_000)
        val padded = AudioDsp.padToDuration(pcm, 1500)
        assertEquals(72_000, padded.samples.size)
        assertEquals(0f, padded.samples.last())
        assertEquals(24_000, AudioDsp.fitToDuration(pcm, 500).samples.size)
        assertEquals(pcm, AudioDsp.padToDuration(pcm, 500))
    }

    @Test
    fun downmixAveragesChannels() {
        assertArrayEquals(floatArrayOf(0.5f, 0f), AudioDsp.downmix(floatArrayOf(1f, 0f, 0.5f, -0.5f), 2), 0f)
    }

    @Test
    fun pcm16ClampsAndScales() {
        assertArrayEquals(shortArrayOf(32767, -32767, 0, 16384), AudioDsp.toPcm16(floatArrayOf(2f, -1f, 0f, 0.5f)))
    }

    @Test
    fun wavRoundTrip() {
        val pcm = MonoPcm(sine(48_000, 250), 48_000)
        val decoded = Wav.decodeToMono(Wav.encodeMono16(pcm))
        assertEquals(48_000, decoded.sampleRate)
        assertEquals(pcm.samples.size, decoded.samples.size)
        for (i in pcm.samples.indices) assertEquals(pcm.samples[i], decoded.samples[i], 1e-4f)
        assertEquals(AudioFormat.WAV, Sniffer.sniffAudio(Wav.encodeMono16(pcm)))
    }

    @Test
    fun wavDecodesStereo8Bit() {
        val header = Wav.encodeMono16(MonoPcm(FloatArray(0), 8000)).copyOf(44)
        // Patch the fmt chunk: 2 channels, 8 bits, 4 data bytes.
        header[22] = 2; header[34] = 8; header[40] = 4
        val bytes = header + byteArrayOf(255.toByte(), 128.toByte(), 0, 128.toByte())
        val decoded = Wav.decodeToMono(bytes)
        assertEquals(2, decoded.samples.size)
        assertEquals(127f / 256f, decoded.samples[0], 1e-6f)
        assertEquals(-0.5f, decoded.samples[1], 1e-6f)
    }
}
