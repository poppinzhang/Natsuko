package com.poppinzhang.natsuko.ui

import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.processing.ImagePlacement
import com.poppinzhang.natsuko.processing.ImageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergePreviewLayoutTest {
    private val sizes = listOf(ImageSize(120, 80), ImageSize(60, 160), ImageSize(30, 20))

    @Test
    fun verticalPreviewPreservesRelativeSizesAndCentersWithoutGaps() {
        val plan = mergePreviewLayout(sizes, MergeDirection.VERTICAL, 360, 520)
        assertEquals(240, plan.width)
        assertEquals(520, plan.height)
        assertEquals(listOf(
            ImagePlacement(0, 0, 240, 160),
            ImagePlacement(60, 160, 120, 320),
            ImagePlacement(90, 480, 60, 40),
        ), plan.placements)
    }

    @Test
    fun horizontalPreviewPreservesRelativeSizesAndCentersWithoutGaps() {
        val plan = mergePreviewLayout(sizes, MergeDirection.HORIZONTAL, 420, 360)
        assertEquals(420, plan.width)
        assertEquals(320, plan.height)
        assertEquals(listOf(
            ImagePlacement(0, 80, 240, 160),
            ImagePlacement(240, 0, 120, 320),
            ImagePlacement(360, 140, 60, 40),
        ), plan.placements)
    }

    @Test
    fun gridFitsWholeImagesInOutputCellsAndRetainsEmptyLastCell() {
        val plan = mergePreviewLayout(sizes, MergeDirection.GRID, 320, 400)
        assertEquals(320, plan.width)
        assertEquals(320, plan.height)
        assertEquals(listOf(
            ImagePlacement(0, 26, 160, 107),
            ImagePlacement(210, 0, 60, 160),
            ImagePlacement(0, 186, 160, 107),
        ), plan.placements)
    }

    @Test
    fun singleWideOrTallImageFitsEntirely() {
        assertEquals(ImagePlacement(0, 0, 320, 16),
            mergePreviewLayout(listOf(ImageSize(4000, 200)), MergeDirection.VERTICAL, 320, 360).placements.single())
        assertEquals(ImagePlacement(0, 0, 18, 360),
            mergePreviewLayout(listOf(ImageSize(200, 4000)), MergeDirection.HORIZONTAL, 320, 360).placements.single())
    }

    @Test
    fun largeBatchAndTinyImagesStayWithinViewportAfterRounding() {
        for (direction in MergeDirection.entries) {
            val plan = mergePreviewLayout(List(99) { ImageSize(4096, 4096) } + ImageSize(1, 1), direction, 320, 360)
            assertTrue(plan.width in 1..320 && plan.height in 1..360)
            assertEquals(100, plan.placements.size)
            assertTrue(plan.placements.all {
                it.width > 0 && it.height > 0 && it.left >= 0 && it.top >= 0 &&
                    it.left + it.width <= plan.width && it.top + it.height <= plan.height
            })
        }
    }
}
