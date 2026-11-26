package io.github.kawincodes.stiksound.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Mono float PCM in [-1, 1]. */
class MonoPcm(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Int get() = if (sampleRate <= 0) 0 else ((samples.size.toDouble() / sampleRate) * 1000).roundToInt()
}

/** Waveform summary: per-bucket min and max sample. */
class PeakBuckets(val mins: FloatArray, val maxs: FloatArray) {
    val size: Int get() = mins.size
}

/** Audio math ported from StikSound `audio-utils.ts`. */
object AudioDsp {
    fun computePeaks(samples: FloatArray, buckets: Int = Limits.WAVEFORM_BUCKETS): PeakBuckets {
        val mins = FloatArray(max(0, buckets))
        val maxs = FloatArray(max(0, buckets))
        if (samples.isEmpty() || buckets <= 0) return PeakBuckets(mins, maxs)
        val total = samples.size.toLong()
        for (b in 0 until buckets) {
            val start = ((b * total) / buckets).toInt()
            val end = min(total, max(start + 1L, ((b + 1) * total) / buckets)).toInt()
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (i in start until end) {
                val v = samples[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            mins[b] = if (lo.isFinite()) lo else 0f
            maxs[b] = if (hi.isFinite()) hi else 0f
        }
        return PeakBuckets(mins, maxs)
    }

    fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples.copyOf()
        val outLen = ((samples.size.toLong() * toRate) / fromRate.toDouble()).roundToLong().toInt()
        val out = FloatArray(outLen)
        val ratio = fromRate.toDouble() / toRate
        val last = samples.size - 1
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = min(last, floor(pos).toInt())
            val next = min(last, idx + 1)
            val frac = (pos - idx).toFloat()
            out[i] = samples[idx] * (1 - frac) + samples[next] * frac
        }
        return out
    }

    /** Averages interleaved channels into mono. */
    fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            val base = f * channels
            for (c in 0 until channels) sum += interleaved[base + c]
            out[f] = sum / channels
        }
        return out
    }

    /**
     * Cuts [startMs, endMs) out of [source], applies [volume], clamps to [-1, 1]
     * and resamples to 48 kHz mono.
     */
    fun trim(
        source: MonoPcm,
        startMs: Int,
        endMs: Int,
        volume: Float = 1f,
        maxAllowedMs: Int = Limits.DEFAULT_VIDEO_MAX_AUDIO_MS,
    ): MonoPcm {
        val totalMs = source.durationMs
        if (endMs <= startMs || startMs < 0 || endMs > totalMs + Limits.DURATION_TOLERANCE_MS) {
            fail(ErrorCode.TRIM_OUT_OF_RANGE, "Trim bounds outside audio duration.")
        }
        val selected = endMs - startMs
        if (selected < Limits.MIN_TRIM_DURATION_MS || selected > maxAllowedMs) {
            fail(ErrorCode.TRIM_OUT_OF_RANGE, "Selection must be between ${Limits.MIN_TRIM_DURATION_MS} ms and $maxAllowedMs ms.")
        }
        val rate = source.sampleRate
        val startSample = max(0, floor(startMs / 1000.0 * rate).toInt())
        val endSample = min(source.samples.size, floor(endMs / 1000.0 * rate).toInt())
        val gain = volume.coerceIn(0f, 1f)
        val sliced = FloatArray(max(0, endSample - startSample)) { i ->
            (source.samples[startSample + i] * gain).coerceIn(-1f, 1f)
        }
        return MonoPcm(resampleLinear(sliced, rate, Limits.OUTPUT_SAMPLE_RATE), Limits.OUTPUT_SAMPLE_RATE)
    }

    /** Appends silence so the clip lasts [targetMs] (used when a GIF loop is longer than the sound). */
    fun padToDuration(pcm: MonoPcm, targetMs: Int): MonoPcm {
        val targetSamples = ((targetMs / 1000.0) * pcm.sampleRate).roundToInt()
        if (targetSamples <= pcm.samples.size) return pcm
        return MonoPcm(pcm.samples.copyOf(targetSamples), pcm.sampleRate)
    }

    /** Cuts the clip to exactly [targetMs] (or pads it), so audio and video lengths match. */
    fun fitToDuration(pcm: MonoPcm, targetMs: Int): MonoPcm {
        val targetSamples = ((targetMs / 1000.0) * pcm.sampleRate).roundToInt()
        if (targetSamples == pcm.samples.size) return pcm
        return MonoPcm(pcm.samples.copyOf(targetSamples), pcm.sampleRate)
    }

    /** Float [-1, 1] to signed 16-bit little-endian PCM. */
    fun toPcm16(samples: FloatArray, from: Int = 0, to: Int = samples.size): ShortArray =
        ShortArray(to - from) { i -> (samples[from + i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }
}
