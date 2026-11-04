package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.model.MergeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeLayoutTest {
    @Test
    fun horizontalCentersShorterImages() {
        val plan = MergeLayout.calculate(
            listOf(ImageSize(100, 50), ImageSize(80, 100)),
            MergeDirection.HORIZONTAL,
        )

        assertEquals(180, plan.width)
        assertEquals(100, plan.height)
        assertEquals(ImagePlacement(0, 25, 100, 50), plan.placements[0])
        assertEquals(ImagePlacement(100, 0, 80, 100), plan.placements[1])
    }

    @Test
    fun verticalCentersNarrowerImages() {
        val plan = MergeLayout.calculate(
            listOf(ImageSize(50, 100), ImageSize(100, 80)),
            MergeDirection.VERTICAL,
        )

        assertEquals(100, plan.width)
        assertEquals(180, plan.height)
        assertEquals(25, plan.placements.first().left)
        assertEquals(100, plan.placements.last().top)
    }

    @Test
    fun hugeResultIsBounded() {
        val plan = MergeLayout.calculate(
            List(100) { ImageSize(4096, 4096) },
            MergeDirection.VERTICAL,
        )

        assertTrue(maxOf(plan.width, plan.height) <= 8192)
        assertTrue(plan.width.toLong() * plan.height <= 40_000_000)
    }

    @Test
    fun fourImagesFormTwoByTwoSquareGrid() {
        val plan = MergeLayout.calculate(
            List(4) { ImageSize(100, 100) },
            MergeDirection.GRID,
        )

        assertEquals(200, plan.width)
        assertEquals(200, plan.height)
        assertEquals(ImagePlacement(0, 0, 100, 100), plan.placements[0])
        assertEquals(ImagePlacement(100, 0, 100, 100), plan.placements[1])
        assertEquals(ImagePlacement(0, 100, 100, 100), plan.placements[2])
        assertEquals(ImagePlacement(100, 100, 100, 100), plan.placements[3])
    }

    @Test
    fun nineImagesFormThreeByThreeGrid() {
        val plan = MergeLayout.calculate(
            List(9) { ImageSize(80, 80) },
            MergeDirection.GRID,
        )

        assertEquals(240, plan.width)
        assertEquals(240, plan.height)
        assertEquals(ImagePlacement(160, 160, 80, 80), plan.placements.last())
    }

    @Test
    fun gridCentersImagesWithoutChangingAspectRatio() {
        val plan = MergeLayout.calculate(
            listOf(ImageSize(200, 100), ImageSize(100, 200), ImageSize(200, 200), ImageSize(50, 100)),
            MergeDirection.GRID,
        )

        assertEquals(ImagePlacement(0, 50, 200, 100), plan.placements[0])
        assertEquals(ImagePlacement(250, 0, 100, 200), plan.placements[1])
        assertEquals(ImagePlacement(250, 200, 100, 200), plan.placements[3])
    }

    @Test
    fun hugeGridResultIsBounded() {
        val plan = MergeLayout.calculate(
            List(100) { ImageSize(4096, 4096) },
            MergeDirection.GRID,
        )

        assertTrue(maxOf(plan.width, plan.height) <= 8192)
        assertTrue(plan.width.toLong() * plan.height <= 40_000_000)
    }
}
