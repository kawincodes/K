package io.github.kawincodes.stiksound.core

import kotlin.math.max
import kotlin.math.min

/** Selected window of the audio source, in integer milliseconds. */
data class TrimRange(val startMs: Int, val endMs: Int) {
    val durationMs: Int get() = endMs - startMs
}

object AudioTrim {
    /** Initial selection after loading audio: from 0 to the active cap (or the whole clip). */
    fun defaultTrim(totalDurationMs: Int, maxAudioMs: Int): TrimRange {
        val clamped = max(Limits.MIN_TRIM_DURATION_MS, totalDurationMs)
        return TrimRange(0, min(clamped, maxAudioMs))
    }

    /** Moves a window of fixed length so that it starts as close to [desiredStartMs] as fits. */
    fun slideWindow(
        desiredStartMs: Int,
        windowDurationMs: Int,
        totalDurationMs: Int,
        maxAudioMs: Int,
    ): TrimRange {
        val safeWindow = max(
            Limits.MIN_TRIM_DURATION_MS,
            minOf(windowDurationMs, maxAudioMs, totalDurationMs),
        )
        val maxStart = max(0, totalDurationMs - safeWindow)
        val start = desiredStartMs.coerceIn(0, maxStart)
        return TrimRange(start, start + safeWindow)
    }

    /**
     * Shortest selection allowed. For animated GIFs this is one loop of the animation
     * (capped by the preset), so the exported video always shows the whole animation.
     */
    fun effectiveMinMs(totalDurationMs: Int, maxAudioMs: Int, minTrimMs: Int): Int =
        max(Limits.MIN_TRIM_DURATION_MS, minOf(totalDurationMs, minTrimMs, maxAudioMs))

    /** Throws [StikSoundException] with [ErrorCode.TRIM_OUT_OF_RANGE] when the selection is invalid. */
    fun validate(
        trim: TrimRange,
        totalDurationMs: Int,
        maxAudioMs: Int,
        minTrimMs: Int = Limits.MIN_TRIM_DURATION_MS,
    ) {
        if (trim.startMs < 0 || trim.endMs > totalDurationMs) {
            fail(ErrorCode.TRIM_OUT_OF_RANGE, "Trim bounds exceed audio duration.")
        }
        val minMs = effectiveMinMs(totalDurationMs, maxAudioMs, minTrimMs)
        val selected = trim.durationMs
        if (selected < minMs || selected > maxAudioMs) {
            fail(ErrorCode.TRIM_OUT_OF_RANGE, "Selected duration $selected ms must be within $minMs..$maxAudioMs ms.")
        }
    }

    /**
     * Fixes up a selection after the source, preset or GIF minimum changed:
     * keeps the start where possible, then grows or shrinks the window to fit.
     */
    fun normalize(
        trim: TrimRange,
        totalDurationMs: Int,
        maxAudioMs: Int,
        minTrimMs: Int = Limits.MIN_TRIM_DURATION_MS,
    ): TrimRange {
        val minMs = effectiveMinMs(totalDurationMs, maxAudioMs, minTrimMs)
        val upper = max(minMs, min(maxAudioMs, totalDurationMs))
        val length = trim.durationMs.coerceIn(minMs, upper)
        return slideWindow(trim.startMs, length, totalDurationMs, maxAudioMs)
    }

    /**
     * Duration of the exported video: the selection, stretched to one full GIF loop
     * when the sticker animates for longer, and never above the preset.
     */
    fun exportDurationMs(trim: TrimRange, maxAudioMs: Int, gifLoopMs: Int): Int =
        min(maxAudioMs, max(trim.durationMs, gifLoopMs))
}
