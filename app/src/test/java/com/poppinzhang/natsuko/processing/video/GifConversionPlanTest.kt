package com.poppinzhang.natsuko.processing.video

import com.poppinzhang.natsuko.model.GifOptions
import com.poppinzhang.natsuko.processing.VideoFrameSchedule
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GifConversionPlanTest {
    @Test
    fun playbackSpeedDoesNotChangeWhichSourceFramesAreSampled() {
        val slow = GifConversionPlan.create(1000, GifOptions(framesPerSecond = 5, sampleIntervalMs = 200))
        val fast = GifConversionPlan.create(1000, GifOptions(framesPerSecond = 20, sampleIntervalMs = 200))
        assertEquals(5, slow.frameCount)
        assertEquals(slow.frameCount, fast.frameCount)
        assertEquals(slow.intervalMs, fast.intervalMs)
        assertTrue(slow.filter.endsWith("setpts=N/(5*TB)"))
        assertTrue(fast.filter.endsWith("setpts=N/(20*TB)"))
    }

    @Test
    fun longVideosWidenSamplingToCoverTheWholeClipWithinFrameLimit() {
        val plan = GifConversionPlan.create(100_000, GifOptions(sampleIntervalMs = 50))
        assertEquals(334L, plan.intervalMs)
        assertEquals(300, plan.frameCount)
        assertTrue((plan.frameCount - 1) * plan.intervalMs > 99_000)
    }

    @Test
    fun paletteAnalysisHasItsOwnBoundedSamplingPlanWithoutReducingOutputFrames() {
        val options = GifOptions(sampleIntervalMs = 50, maxWidth = 1080)
        val palette = GifConversionPlan.forPalette(100_000, options)
        val output = GifConversionPlan.create(100_000, options)
        assertEquals(32, palette.frameCount)
        assertEquals(300, output.frameCount)
        assertTrue((palette.frameCount - 1) * palette.intervalMs > 96_000)
        assertTrue(palette.filter.contains("min(160,round("))
        assertTrue(output.filter.contains("min(4096,round("))
    }

    @Test
    fun shortVideoStillHasOneFrameAndVeryLongDurationDoesNotOverflow() {
        assertEquals(1, GifConversionPlan.create(1, GifOptions()).frameCount)
        val timestamps = VideoFrameSchedule.timestamps(Long.MAX_VALUE, 100, 300)
        assertEquals(300, timestamps.size)
        assertTrue(timestamps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun loopChoicesRepresentTotalPlays() {
        assertEquals(0, GifConversionPlan.create(1000, GifOptions(loopCount = 0)).repeatCount)
        assertEquals(-1, GifConversionPlan.create(1000, GifOptions(loopCount = 1)).repeatCount)
        assertEquals(2, GifConversionPlan.create(1000, GifOptions(loopCount = 3)).repeatCount)
    }

    @Test
    fun filtersUseLocaleIndependentNumbers() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertTrue(GifConversionPlan.create(1000, GifOptions(sampleIntervalMs = 150)).filter.contains("fps=1000/150:"))
        } finally { Locale.setDefault(previous) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun excessiveFrameCountIsRejected() {
        GifConversionPlan.create(1000, GifOptions(maxFrames = 301))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSamplingIntervalIsRejected() {
        GifConversionPlan.create(1000, GifOptions(sampleIntervalMs = 0))
    }
}
