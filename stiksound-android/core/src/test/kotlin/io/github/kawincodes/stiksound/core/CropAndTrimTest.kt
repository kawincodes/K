package io.github.kawincodes.stiksound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CropAndTrimTest {
    @Test
    fun centersCropOnWideImage() {
        assertEquals(SquareCropRect(250, 0, 500, 500), computeSquareCrop(1000, 500))
    }

    @Test
    fun capsOutputAt512AndKeepsItEven() {
        assertEquals(512, computeSquareCrop(1200, 900).outputSize)
        assertEquals(300, computeSquareCrop(301, 400).outputSize)
        assertEquals(2, computeSquareCrop(3, 3).outputSize)
        assertEquals(1, computeSquareCrop(1, 1).outputSize)
    }

    @Test
    fun cropAnchorIsClamped() {
        assertEquals(0, computeSquareCrop(1000, 500, 0f, 0.5f).srcX)
        assertEquals(500, computeSquareCrop(1000, 500, 1f, 0.5f).srcX)
        assertEquals(500, computeSquareCrop(1000, 500, 7f, 0.5f).srcX)
        assertEquals(250, computeSquareCrop(1000, 500, Float.NaN, 0.5f).srcX)
        assertEquals(150, computeSquareCrop(400, 700, 0.5f, 0.5f).srcY)
    }

    @Test
    fun defaultTrimCoversClipUpToPreset() {
        assertEquals(TrimRange(0, 10_000), AudioTrim.defaultTrim(42_000, 10_000))
        assertEquals(TrimRange(0, 5_000), AudioTrim.defaultTrim(42_000, 5_000))
        assertEquals(TrimRange(0, 3_200), AudioTrim.defaultTrim(3_200, 10_000))
    }

    @Test
    fun slidingWindowStaysInsideClip() {
        assertEquals(TrimRange(5_000, 10_000), AudioTrim.slideWindow(9_000, 5_000, 10_000, 10_000))
        assertEquals(TrimRange(0, 5_000), AudioTrim.slideWindow(-300, 5_000, 10_000, 10_000))
        assertEquals(TrimRange(1_000, 6_000), AudioTrim.slideWindow(1_000, 8_000, 30_000, 5_000))
    }

    @Test
    fun validateRejectsTooLongOrTooShort() {
        AudioTrim.validate(TrimRange(0, 10_000), 20_000, 10_000)
        for (bad in listOf(TrimRange(0, 10_001), TrimRange(0, 50), TrimRange(-1, 500), TrimRange(19_000, 20_001))) {
            try {
                AudioTrim.validate(bad, 20_000, 10_000)
                fail("expected rejection for $bad")
            } catch (e: StikSoundException) {
                assertEquals(ErrorCode.TRIM_OUT_OF_RANGE, e.code)
            }
        }
    }

    @Test
    fun gifLoopSetsMinimumSelection() {
        assertEquals(1_500, AudioTrim.effectiveMinMs(20_000, 10_000, 1_500))
        // Loop longer than the clip: the whole clip is the minimum.
        assertEquals(800, AudioTrim.effectiveMinMs(800, 10_000, 1_500))
        assertEquals(TrimRange(2_000, 3_500), AudioTrim.normalize(TrimRange(2_000, 2_500), 20_000, 10_000, 1_500))
    }

    @Test
    fun switchingPresetShrinksSelection() {
        assertEquals(TrimRange(1_000, 6_000), AudioTrim.normalize(TrimRange(1_000, 11_000), 30_000, 5_000))
    }

    @Test
    fun exportDurationStretchesToGifLoop() {
        assertEquals(2_000, AudioTrim.exportDurationMs(TrimRange(0, 2_000), 10_000, 0))
        assertEquals(3_000, AudioTrim.exportDurationMs(TrimRange(0, 2_000), 10_000, 3_000))
        assertEquals(5_000, AudioTrim.exportDurationMs(TrimRange(0, 2_000), 5_000, 12_000))
    }
}
