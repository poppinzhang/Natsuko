package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.model.SplitDirection
import com.poppinzhang.natsuko.model.SplitMode
import com.poppinzhang.natsuko.model.SplitOptions
import com.poppinzhang.natsuko.model.SplitOrientationHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSplitGeometryTest {
    @Test
    fun horizontalEqualSplitTilesSourceExactlyAcrossAllPartCounts() {
        for (parts in SplitOptions.MIN_PARTS..SplitOptions.MAX_PARTS) {
            val plan = checkNotNull(
                ImageSplitGeometry.resolve(
                    width = 1000,
                    height = 600,
                    options = SplitOptions(direction = SplitDirection.HORIZONTAL, parts = parts, mode = SplitMode.EQUAL),
                ),
            )
            assertEquals(parts, plan.parts)
            assertEquals(parts - 1, plan.dividersPx.size)
            assertEquals(0, plan.slices.first().left)
            assertEquals(1000, plan.slices.last().right)
            assertEquals(1000, plan.slices.sumOf { it.width })
            plan.slices.forEachIndexed { index, slice ->
                assertEquals(index, slice.index)
                assertEquals(0, slice.top)
                assertEquals(600, slice.height)
                assertTrue("Slice $index width must be positive", slice.width >= 1)
                if (index > 0) {
                    assertEquals(plan.slices[index - 1].right, slice.left)
                }
            }
            val widths = plan.slices.map { it.width }
            assertTrue("Equal split widths should differ by at most 1 px", widths.max() - widths.min() <= 1)
        }
    }

    @Test
    fun verticalEqualSplitTilesSourceExactlyAndSymmetrically() {
        val plan = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 800,
                height = 100,
                options = SplitOptions(direction = SplitDirection.VERTICAL, parts = 3, mode = SplitMode.EQUAL),
            ),
        )
        assertEquals(listOf(33, 67), plan.dividersPx)
        assertEquals(listOf(33, 34, 33), plan.slices.map { it.height })
        assertTrue(plan.slices.all { it.left == 0 && it.width == 800 })
        assertEquals(0, plan.slices.first().top)
        assertEquals(100, plan.slices.last().bottom)
    }

    @Test
    fun freeSplitHonorsCustomDividersAndEnforcesMonotonicBounds() {
        val options = SplitOptions(
            direction = SplitDirection.HORIZONTAL,
            parts = 4,
            mode = SplitMode.FREE,
            freeDividers = listOf(0.15, 0.4, 0.85),
        )
        val plan = checkNotNull(ImageSplitGeometry.resolve(1000, 500, options))
        assertEquals(listOf(150, 400, 850), plan.dividersPx)
        assertEquals(listOf(150, 250, 450, 150), plan.slices.map { it.width })
        assertTrue(ImageSplitGeometry.hasCustomDividers(1000, 500, options))

        // Out-of-order or collapsed fractions still yield strictly ordered 1 px+ slices.
        val collapsed = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 100,
                height = 50,
                options = SplitOptions(
                    direction = SplitDirection.HORIZONTAL,
                    parts = 4,
                    mode = SplitMode.FREE,
                    freeDividers = listOf(0.0, 0.0, 1.0),
                ),
            ),
        )
        assertEquals(listOf(1, 2, 99), collapsed.dividersPx)
        assertEquals(100, collapsed.slices.sumOf { it.width })
        assertTrue(collapsed.slices.all { it.width >= 1 })
    }

    @Test
    fun movingDividerNeverCrossesNeighborsOrImageEdges() {
        val base = SplitOptions(
            direction = SplitDirection.HORIZONTAL,
            parts = 3,
            mode = SplitMode.FREE,
            freeDividers = listOf(0.3, 0.7),
        )
        // Attempt to drag divider 0 past divider 1.
        val movedRight = ImageSplitGeometry.moveDivider(1000, 500, base, dividerIndex = 0, targetFraction = 0.95)
        val planRight = checkNotNull(ImageSplitGeometry.resolve(1000, 500, base.copy(freeDividers = movedRight)))
        assertEquals(700, planRight.dividersPx[1])
        assertTrue(planRight.dividersPx[0] < planRight.dividersPx[1])
        assertEquals(700 - ImageSplitGeometry.minInteractiveSlicePx(1000, 3), planRight.dividersPx[0])

        // Attempt to drag divider 0 past the left edge.
        val movedLeft = ImageSplitGeometry.moveDivider(1000, 500, base, dividerIndex = 0, targetFraction = -0.2)
        val planLeft = checkNotNull(ImageSplitGeometry.resolve(1000, 500, base.copy(freeDividers = movedLeft)))
        assertEquals(ImageSplitGeometry.minInteractiveSlicePx(1000, 3), planLeft.dividersPx[0])
    }

    @Test
    fun movingDividerRemainsStrictlyOrderedEvenWhenNeighborsWereNudgedClose() {
        // Neighbors at 10 px and 14 px (4 px apart, tighter than 2 * minInteractiveSlicePx).
        val tight = SplitOptions(
            direction = SplitDirection.HORIZONTAL,
            parts = 4,
            mode = SplitMode.FREE,
            freeDividers = listOf(0.010, 0.012, 0.014),
        )
        val moved = ImageSplitGeometry.moveDivider(1000, 500, tight, dividerIndex = 1, targetFraction = 0.5)
        val plan = checkNotNull(ImageSplitGeometry.resolve(1000, 500, tight.copy(freeDividers = moved)))
        assertEquals(listOf(10, 13, 14), plan.dividersPx)
    }

    @Test
    fun nudgeDividerMovesByExactPixelsWithinOnePixelMargins() {
        val base = SplitOptions(
            direction = SplitDirection.VERTICAL,
            parts = 3,
            mode = SplitMode.FREE,
            freeDividers = listOf(0.25, 0.75),
        )
        val nudged = ImageSplitGeometry.nudgeDivider(400, 200, base, dividerIndex = 0, deltaPx = 7)
        val plan = checkNotNull(ImageSplitGeometry.resolve(400, 200, base.copy(freeDividers = nudged)))
        assertEquals(listOf(57, 150), plan.dividersPx)

        val clamped = ImageSplitGeometry.nudgeDivider(400, 200, base, dividerIndex = 0, deltaPx = 500)
        val clampedPlan = checkNotNull(ImageSplitGeometry.resolve(400, 200, base.copy(freeDividers = clamped)))
        assertEquals(listOf(149, 150), clampedPlan.dividersPx)
    }

    @Test
    fun smallImageConstraintsAreHandledCleanly() {
        assertFalse(ImageSplitGeometry.canSplit(1, 200, SplitDirection.HORIZONTAL))
        assertNull(ImageSplitGeometry.resolve(1, 200, SplitOptions(direction = SplitDirection.HORIZONTAL, parts = 2)))
        assertTrue(ImageSplitGeometry.canSplit(1, 200, SplitDirection.VERTICAL))

        // Width = 5 caps horizontal split to 5 parts even when 20 is requested.
        assertEquals(5, ImageSplitGeometry.maxPartsFor(5, 100, SplitDirection.HORIZONTAL))
        val clamped = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 5,
                height = 100,
                options = SplitOptions(direction = SplitDirection.HORIZONTAL, parts = 20),
            ),
        )
        assertEquals(5, clamped.parts)
        assertEquals(listOf(1, 1, 1, 1, 1), clamped.slices.map { it.width })
    }

    @Test
    fun previewLayoutArrangesSlicesWithConfiguredSpacingAndFitsViewport() {
        val horizontalPlan = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 1000,
                height = 500,
                options = SplitOptions(direction = SplitDirection.HORIZONTAL, parts = 4),
            ),
        )
        val layout = ImageSplitGeometry.previewLayout(
            plan = horizontalPlan,
            spacingPx = 50,
            viewportWidth = 400f,
            viewportHeight = 300f,
            paddingPx = 20f,
        )
        assertEquals(4, layout.slices.size)
        // Virtual width = 1000 + 3 * 50 = 1150, available width = 360 -> scale = 360 / 1150.
        val expectedScale = 360f / 1150f
        assertEquals(expectedScale, layout.scale, 1e-4f)
        for (i in 0 until 3) {
            val gap = layout.slices[i + 1].left - layout.slices[i].right
            assertEquals(50f * expectedScale, gap, 1e-2f)
        }
        assertTrue(layout.canvasLeft >= 19.9f)
        assertTrue(layout.canvasTop >= 19.9f)
        assertTrue(layout.canvasLeft + layout.canvasWidth <= 380.1f)
        assertTrue(layout.canvasTop + layout.canvasHeight <= 280.1f)

        val verticalPlan = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 400,
                height = 800,
                options = SplitOptions(direction = SplitDirection.VERTICAL, parts = 3),
            ),
        )
        val verticalLayout = ImageSplitGeometry.previewLayout(
            plan = verticalPlan,
            spacingPx = 40,
            viewportWidth = 300f,
            viewportHeight = 500f,
            paddingPx = 10f,
        )
        assertEquals(3, verticalLayout.slices.size)
        for (i in 0 until 2) {
            val gap = verticalLayout.slices[i + 1].top - verticalLayout.slices[i].bottom
            assertEquals(40f * verticalLayout.scale, gap, 1e-2f)
        }
    }

    @Test
    fun fullscreenOrientationAdaptsToImageAspectRatio() {
        assertEquals(SplitOrientationHint.LANDSCAPE, ImageSplitGeometry.fullscreenOrientation(1920, 1080))
        assertEquals(SplitOrientationHint.PORTRAIT, ImageSplitGeometry.fullscreenOrientation(1080, 1920))
        assertEquals(SplitOrientationHint.UNSPECIFIED, ImageSplitGeometry.fullscreenOrientation(1080, 1080))
    }

    @Test
    fun hitTestSelectsClosestDividerWithinThreshold() {
        val plan = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 1000,
                height = 500,
                options = SplitOptions(direction = SplitDirection.HORIZONTAL, parts = 4),
            ),
        )
        // Dividers at 250, 500, 750 on imageRect [100, 500] -> screen X at 200, 300, 400.
        assertEquals(0, ImageSplitGeometry.hitTestDivider(208f, 100f, 400f, plan, maxDistance = 24f))
        assertEquals(1, ImageSplitGeometry.hitTestDivider(295f, 100f, 400f, plan, maxDistance = 24f))
        assertEquals(2, ImageSplitGeometry.hitTestDivider(410f, 100f, 400f, plan, maxDistance = 24f))
        assertNull(ImageSplitGeometry.hitTestDivider(150f, 100f, 400f, plan, maxDistance = 24f))
    }

    @Test
    fun equalDividersRejectsCustomFlag() {
        val equal = SplitOptions(
            direction = SplitDirection.HORIZONTAL,
            parts = 4,
            mode = SplitMode.FREE,
            freeDividers = ImageSplitGeometry.equalDividers(4),
        )
        assertNotNull(ImageSplitGeometry.resolve(1000, 500, equal))
        assertFalse(ImageSplitGeometry.hasCustomDividers(1000, 500, equal))
    }

    @Test
    fun equalSplitWithDividerWidthExcludesExactStripWidthAndKeepsSymmetricSlices() {
        val horizontal = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 1000,
                height = 500,
                options = SplitOptions(
                    direction = SplitDirection.HORIZONTAL,
                    parts = 4,
                    mode = SplitMode.EQUAL,
                    dividerWidthPx = 20,
                ),
            ),
        )
        assertEquals(20, horizontal.dividerWidthPx)
        assertEquals(listOf(235, 235, 235, 235), horizontal.slices.map { it.width })
        assertEquals(listOf(235 to 255, 490 to 510, 745 to 765), horizontal.dividers.map { it.start to it.end })
        assertEquals(1000, horizontal.slices.sumOf { it.width } + horizontal.dividers.sumOf { it.width })

        val verticalOdd = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 100,
                height = 400,
                options = SplitOptions(
                    direction = SplitDirection.VERTICAL,
                    parts = 3,
                    mode = SplitMode.EQUAL,
                    dividerWidthPx = 5,
                ),
            ),
        )
        assertEquals(5, verticalOdd.dividerWidthPx)
        assertEquals(listOf(130, 130, 130), verticalOdd.slices.map { it.height })
        assertEquals(listOf(130 to 135, 265 to 270), verticalOdd.dividers.map { it.start to it.end })
    }

    @Test
    fun freeSplitWithDividerWidthPreservesBandWidthWhenMovedOrNudged() {
        val equalFractions = ImageSplitGeometry.equalDividerFractions(axisLength = 1000, parts = 3, dividerWidthPx = 40)
        val free = SplitOptions(
            direction = SplitDirection.HORIZONTAL,
            parts = 3,
            mode = SplitMode.FREE,
            dividerWidthPx = 40,
            freeDividers = equalFractions,
        )
        val initial = checkNotNull(ImageSplitGeometry.resolve(1000, 500, free))
        assertFalse(ImageSplitGeometry.hasCustomDividers(1000, 500, free))
        assertEquals(listOf(307 to 347, 653 to 693), initial.dividers.map { it.start to it.end })

        val moved = ImageSplitGeometry.moveDivider(1000, 500, free, dividerIndex = 0, targetFraction = 0.95)
        val movedPlan = checkNotNull(ImageSplitGeometry.resolve(1000, 500, free.copy(freeDividers = moved)))
        assertEquals(40, movedPlan.dividers[0].width)
        assertEquals(40, movedPlan.dividers[1].width)
        assertTrue(movedPlan.dividers[0].end < movedPlan.dividers[1].start)
        assertTrue(movedPlan.slices.all { it.width >= 1 })

        val nudged = ImageSplitGeometry.nudgeDivider(1000, 500, free, dividerIndex = 0, deltaPx = 10)
        val nudgedPlan = checkNotNull(ImageSplitGeometry.resolve(1000, 500, free.copy(freeDividers = nudged)))
        assertEquals(317 to 357, nudgedPlan.dividers[0].start to nudgedPlan.dividers[0].end)
        assertEquals(40, nudgedPlan.dividers[0].width)
    }

    @Test
    fun dividerWidthClampsToLeaveAtLeastOnePixelPerSlice() {
        assertEquals(32, ImageSplitGeometry.maxDividerWidthFor(100, 50, SplitDirection.HORIZONTAL, parts = 4))
        val plan = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 100,
                height = 50,
                options = SplitOptions(
                    direction = SplitDirection.HORIZONTAL,
                    parts = 4,
                    mode = SplitMode.EQUAL,
                    dividerWidthPx = 200,
                ),
            ),
        )
        assertEquals(32, plan.dividerWidthPx)
        assertEquals(listOf(1, 1, 1, 1), plan.slices.map { it.width })
        assertEquals(100, plan.slices.sumOf { it.width } + plan.dividers.sumOf { it.width })
    }

    @Test
    fun previewLayoutUsesKeptSliceWidthWhenDividerWidthIsConfigured() {
        val plan = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 1000,
                height = 500,
                options = SplitOptions(
                    direction = SplitDirection.HORIZONTAL,
                    parts = 2,
                    dividerWidthPx = 40,
                ),
            ),
        )
        // Kept slice widths = 480 + 480 = 960; with spacingPx = 40, virtual width = 1000.
        val matchingGap = ImageSplitGeometry.previewLayout(
            plan = plan,
            spacingPx = 40,
            viewportWidth = 500f,
            viewportHeight = 300f,
            paddingPx = 0f,
        )
        assertEquals(0.5f, matchingGap.scale, 1e-4f)
        assertEquals(500f, matchingGap.canvasWidth, 1e-2f)
        assertEquals(20f, matchingGap.slices[1].left - matchingGap.slices[0].right, 1e-2f)

        // With spacingPx = 0, virtual width = 960 (no phantom space from excluded divider pixels).
        val zeroGap = ImageSplitGeometry.previewLayout(
            plan = plan,
            spacingPx = 0,
            viewportWidth = 480f,
            viewportHeight = 300f,
            paddingPx = 0f,
        )
        assertEquals(0.5f, zeroGap.scale, 1e-4f)
        assertEquals(480f, zeroGap.canvasWidth, 1e-2f)
        assertEquals(0f, zeroGap.slices[1].left - zeroGap.slices[0].right, 1e-2f)
    }

    @Test
    fun hitTestMatchesAnywhereInsideWideDividerBand() {
        val plan = checkNotNull(
            ImageSplitGeometry.resolve(
                width = 1000,
                height = 500,
                options = SplitOptions(
                    direction = SplitDirection.HORIZONTAL,
                    parts = 2,
                    dividerWidthPx = 200,
                ),
            ),
        )
        // Divider spans [400, 600] -> on imageRect [0, 500], band spans [200, 300].
        assertEquals(0, ImageSplitGeometry.hitTestDivider(205f, 0f, 500f, plan, maxDistance = 10f))
        assertEquals(0, ImageSplitGeometry.hitTestDivider(250f, 0f, 500f, plan, maxDistance = 10f))
        assertEquals(0, ImageSplitGeometry.hitTestDivider(295f, 0f, 500f, plan, maxDistance = 10f))
        assertNull(ImageSplitGeometry.hitTestDivider(150f, 0f, 500f, plan, maxDistance = 10f))
    }
}
