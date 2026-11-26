package io.github.kawincodes.stiksound.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {
    @Test
    fun loopsGifFramesAndClampsTheLastOne() {
        val schedule = FrameScheduler.scheduleGif(listOf(100, 100), 250)
        assertEquals(
            listOf(
                ScheduledFrame(0, 0, 100_000),
                ScheduledFrame(1, 100_000, 100_000),
                ScheduledFrame(0, 200_000, 50_000),
            ),
            schedule,
        )
    }

    @Test
    fun capsScheduleAtTenSeconds() {
        val schedule = FrameScheduler.scheduleGif(listOf(1000), 60_000)
        assertEquals(10_000_000L, schedule.sumOf { it.durationUs })
    }

    @Test
    fun resampledTimelineIsSteadyAndCoversDuration() {
        val schedule = FrameScheduler.scheduleGif(listOf(100, 200, 50), 1000)
        val frames = FrameScheduler.resampleForEncoder(schedule, 1000, 24)
        assertEquals(24, frames.size)
        assertEquals(0L, frames.first().timestampUs)
        assertEquals(1_000_000L, frames.last().timestampUs + frames.last().durationUs)
        frames.zipWithNext().forEach { (a, b) -> assertEquals(a.timestampUs + a.durationUs, b.timestampUs) }
        assertTrue(frames.first().keyFrame)
        // A new GIF frame starts at 100 ms; the first encoder frame at or after it shows frame 1 and is a key frame.
        val firstOfFrame1 = frames.first { it.frameIndex == 1 }
        assertTrue(firstOfFrame1.keyFrame)
        assertEquals(125_000L, firstOfFrame1.timestampUs)
    }

    @Test
    fun staticImageUsesTenFps() {
        val frames = FrameScheduler.forSticker(listOf(100), 1050)
        assertEquals(11, frames.size)
        assertEquals(50_000L, frames.last().durationUs)
        assertTrue(frames.all { it.frameIndex == 0 })
        assertTrue(frames[0].keyFrame && frames[10].keyFrame)
    }
}
