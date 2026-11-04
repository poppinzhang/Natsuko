package com.poppinzhang.natsuko.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameScheduleTest {
    @Test
    fun requestedSamplingIntervalIsUsedForShortVideo() {
        assertEquals(listOf(0L, 100L, 200L, 300L, 400L), VideoFrameSchedule.timestamps(500, 100, 300))
    }

    @Test
    fun longVideoIsCappedAndStillCoveredEvenly() {
        val timestamps = VideoFrameSchedule.timestamps(100_000, 50, 300)

        assertTrue(timestamps.size <= 300)
        assertTrue(timestamps.zipWithNext().all { (first, second) -> second > first })
        assertTrue(timestamps.last() > 99_000)
    }
}
