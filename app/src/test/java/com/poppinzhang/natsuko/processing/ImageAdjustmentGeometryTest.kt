package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.model.AdjustmentEdits
import com.poppinzhang.natsuko.model.CropAspect
import com.poppinzhang.natsuko.model.PixelRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAdjustmentGeometryTest {
    @Test
    fun uneditedImageKeepsItsPixelSize() {
        val resolved = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits())

        assertEquals(PixelRect(0, 0, 4000, 3000), resolved.crop)
        assertEquals(4000, resolved.outputWidth)
        assertEquals(3000, resolved.outputHeight)
        assertTrue(resolved.isPixelCopy)
    }

    @Test
    fun reductionKeepsTheRatioAndFollowsLaterCrops() {
        val half = AdjustmentEditing.setScale(AdjustmentEdits(), 0.5)
        val reduced = AdjustmentGeometry.resolve(4000, 3000, half)
        assertEquals(2000 to 1500, reduced.outputWidth to reduced.outputHeight)

        val cropped = AdjustmentGeometry.resolve(4000, 3000, half.copy(crop = PixelRect(500, 0, 3500, 3000)))
        assertEquals(3000 to 3000, cropped.geometryWidth to cropped.geometryHeight)
        assertEquals(1500 to 1500, cropped.outputWidth to cropped.outputHeight)

        val restored = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEditing.resetScale(half))
        assertEquals(4000 to 3000, restored.outputWidth to restored.outputHeight)
    }

    @Test
    fun reductionNeverEnlargesAndAlwaysKeepsAPixel() {
        val enlarged = AdjustmentEditing.setScale(AdjustmentEdits(), 2.0)
        assertEquals(1.0, enlarged.scale, 0.0)
        assertEquals(AdjustmentEdits(), AdjustmentEditing.setScale(AdjustmentEdits(), 0.0))
        assertEquals(ImageSize(1, 1), AdjustmentGeometry.scaledSize(4000, 3, 0.0001))
    }

    @Test
    fun targetWidthOrHeightBecomesOneProportionalScale() {
        val resolved = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits())

        val byWidth = AdjustmentEditing.scaleForWidth(resolved, 1000)
        val widthResult = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits(scale = byWidth))
        assertEquals(1000 to 750, widthResult.outputWidth to widthResult.outputHeight)

        val byHeight = AdjustmentEditing.scaleForHeight(resolved, 1000)
        val heightResult = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits(scale = byHeight))
        assertEquals(1333 to 1000, heightResult.outputWidth to heightResult.outputHeight)

        // Targets are limited to the edited size: no enlargement and no independent stretching.
        assertEquals(1.0, AdjustmentEditing.scaleForWidth(resolved, 9000), 0.0)
        assertEquals(1.0 / 4000, AdjustmentEditing.scaleForWidth(resolved, 0), 0.0)
    }

    @Test
    fun quarterTurnsSwapDimensionsAndHalfTurnsDoNot() {
        val clockwise = AdjustmentEditing.rotate(AdjustmentEdits(), 4000, 3000, clockwise = true)
        val turned = AdjustmentGeometry.resolve(4000, 3000, clockwise)
        assertEquals(3000 to 4000, turned.outputWidth to turned.outputHeight)

        val half = AdjustmentEditing.rotate(clockwise, 4000, 3000, clockwise = true)
        val halfTurned = AdjustmentGeometry.resolve(4000, 3000, half)
        assertEquals(4000 to 3000, halfTurned.outputWidth to halfTurned.outputHeight)
    }

    @Test
    fun orientationMapsCornersForEveryTurnAndMirror() {
        // Source top-left pixel center after each transform, for a 4×2 source.
        val expected = mapOf(
            (0 to false) to (0.5 to 0.5),
            (1 to false) to (1.5 to 0.5),
            (2 to false) to (3.5 to 1.5),
            (3 to false) to (0.5 to 3.5),
            (0 to true) to (3.5 to 0.5),
            (1 to true) to (1.5 to 3.5),
        )
        expected.forEach { (key, point) ->
            val transform = AdjustmentGeometry.orientation(4, 2, key.first, key.second)
            assertEquals("$key x", point.first, transform.mapX(0.5, 0.5), 1e-9)
            assertEquals("$key y", point.second, transform.mapY(0.5, 0.5), 1e-9)
        }
    }

    @Test
    fun cropTurnsWithThePictureAndRoundTripsExactly() {
        val crop = PixelRect(100, 200, 1100, 700)
        val edits = AdjustmentEdits(crop = crop, aspect = CropAspect.RATIO_16_9)
        val clockwise = AdjustmentEditing.rotate(edits, 4000, 3000, clockwise = true)

        assertEquals(PixelRect(2300, 100, 2800, 1100), clockwise.crop)
        assertTrue(clockwise.aspectSwapped)
        assertEquals(edits, AdjustmentEditing.rotate(clockwise, 4000, 3000, clockwise = false))

        var full = edits
        repeat(4) { full = AdjustmentEditing.rotate(full, 4000, 3000, clockwise = true) }
        assertEquals(edits, full)
    }

    @Test
    fun cropKeepsTheSameSourcePixelsAfterRotationAndMirroring() {
        val edits = AdjustmentEdits(crop = PixelRect(10, 20, 30, 50))
        val before = sourceRegion(40, 60, edits)
        var changed = AdjustmentEditing.rotate(edits, 40, 60, clockwise = true)
        assertEquals(before, sourceRegion(40, 60, changed))
        changed = AdjustmentEditing.flip(changed, 40, 60, horizontal = true)
        assertEquals(before, sourceRegion(40, 60, changed))
        changed = AdjustmentEditing.flip(changed, 40, 60, horizontal = false)
        assertEquals(before, sourceRegion(40, 60, changed))
    }

    @Test
    fun flipsUseTheVisibleOrientationAndCancelOut() {
        val turned = AdjustmentEditing.rotate(AdjustmentEdits(straightenTenths = 25), 40, 20, clockwise = true)
        val horizontal = AdjustmentEditing.flip(turned, 40, 20, horizontal = true)

        // The visible left and right swap: the frame point near the visible left edge holds what was at the right.
        val before = AdjustmentGeometry.frameTransform(40, 20, turned.quarterTurns, turned.mirrored, 0)
        val after = AdjustmentGeometry.frameTransform(40, 20, horizontal.quarterTurns, horizontal.mirrored, 0)
        assertEquals(20 - before.mapX(3.5, 7.5), after.mapX(3.5, 7.5), 1e-9)
        assertEquals(before.mapY(3.5, 7.5), after.mapY(3.5, 7.5), 1e-9)
        assertEquals(-25, horizontal.straightenTenths)

        assertEquals(turned, AdjustmentEditing.flip(horizontal, 40, 20, horizontal = true))
        val vertical = AdjustmentEditing.flip(turned, 40, 20, horizontal = false)
        assertEquals(turned, AdjustmentEditing.flip(vertical, 40, 20, horizontal = false))
    }

    @Test
    fun toolResetsOnlyUndoTheirOwnAdjustment() {
        var edits = AdjustmentEdits(crop = PixelRect(100, 100, 2100, 1600), straightenTenths = 30, scale = 0.5)
        edits = AdjustmentEditing.flip(edits, 4000, 3000, horizontal = true)
        edits = AdjustmentEditing.rotate(edits, 4000, 3000, clockwise = true)
        val content = sourceRegion(4000, 3000, edits.copy(straightenTenths = 0))

        val unrotated = AdjustmentEditing.resetRotation(edits, 4000, 3000)
        assertEquals(0, unrotated.quarterTurns)
        assertTrue(unrotated.mirrored)
        assertEquals(edits.scale, unrotated.scale, 0.0)
        assertEquals(content, sourceRegion(4000, 3000, unrotated.copy(straightenTenths = 0)))

        val unmirrored = AdjustmentEditing.resetMirror(edits, 4000, 3000)
        assertFalse(unmirrored.mirrored)
        assertEquals(edits.quarterTurns, unmirrored.quarterTurns)
        assertEquals(30, unmirrored.straightenTenths)

        val level = AdjustmentEditing.resetStraighten(edits)
        assertEquals(0, level.straightenTenths)
        assertEquals(edits.crop, level.crop)

        val uncropped = AdjustmentEditing.resetCrop(edits)
        assertNull(uncropped.crop)
        assertEquals(edits.quarterTurns, uncropped.quarterTurns)
        assertEquals(edits.scale, uncropped.scale, 0.0)
    }

    @Test
    fun straighteningTrimsUncoveredCornersAndKeepsTheRatio() {
        val resolved = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits(straightenTenths = 100))
        val crop = resolved.crop

        assertTrue(crop.width < 4000 && crop.height < 3000)
        assertEquals(4.0 / 3.0, crop.width.toDouble() / crop.height, 0.002)
        assertEquals(2000.0, (crop.left + crop.right) / 2.0, 1.0)
        assertEquals(1500.0, (crop.top + crop.bottom) / 2.0, 1.0)
        assertCovered(4000, 3000, 100, crop)
        // Largest centered 4:3 crop inside the rotated picture: no unnecessary loss.
        val angle = Math.toRadians(10.0)
        val expectedScale = minOf(2000 / (2000 * cos(angle) + 1500 * sin(angle)), 1500 / (2000 * sin(angle) + 1500 * cos(angle)))
        assertEquals(4000 * expectedScale, crop.width.toDouble(), 2.0)
        // The trimmed size is not scaled back up to the original dimensions.
        assertEquals(crop.width to crop.height, resolved.outputWidth to resolved.outputHeight)
    }

    @Test
    fun zeroDegreesNeverAddsCroppingAndRestoresTheUserCrop() {
        val userCrop = PixelRect(400, 300, 3600, 2700)
        val straightened = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits(crop = userCrop, straightenTenths = -450))
        assertTrue(straightened.crop.width < userCrop.width)
        assertCovered(4000, 3000, -450, straightened.crop)
        assertTrue(contains(userCrop, straightened.crop))

        val level = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits(crop = userCrop))
        assertEquals(userCrop, level.crop)
        assertEquals(PixelRect(0, 0, 4000, 3000), AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits()).crop)
    }

    @Test
    fun cropAlreadyInsideTheRotatedPictureIsUnchanged() {
        val small = PixelRect(1800, 1300, 2200, 1700)
        assertEquals(small, AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits(crop = small, straightenTenths = 200)).crop)
    }

    @Test
    fun cropInAnUncoveredCornerStillProducesAValidArea() {
        val corner = PixelRect(0, 0, 40, 30)
        val resolved = AdjustmentGeometry.resolve(1000, 1000, AdjustmentEdits(crop = corner, straightenTenths = 450))

        assertCovered(1000, 1000, 450, resolved.crop)
        assertEquals(40.0 / 30.0, resolved.crop.width.toDouble() / resolved.crop.height, 0.06)
        assertTrue(resolved.crop.width <= 40)
    }

    @Test
    fun everyAngleProducesACoveredCropForUnusualShapes() {
        for ((width, height) in listOf(4000 to 3000, 1000 to 100, 100 to 1000, 12 to 8, 1 to 1)) {
            for (tenths in listOf(-450, -333, -1, 1, 77, 450)) {
                val resolved = AdjustmentGeometry.resolve(width, height, AdjustmentEdits(straightenTenths = tenths))
                assertTrue(resolved.crop.width >= 1 && resolved.crop.height >= 1)
                if (width > 1) assertCovered(width, height, tenths, resolved.crop)
            }
        }
    }

    @Test
    fun outputTransformMapsTheCropOntoTheOutputBounds() {
        val edits = AdjustmentEdits(quarterTurns = 1, mirrored = true, straightenTenths = 55, crop = PixelRect(300, 400, 2700, 3600), scale = 0.5)
        val resolved = AdjustmentGeometry.resolve(4000, 3000, edits)
        val toOutput = AdjustmentGeometry.outputTransform(resolved)
        val toFrame = AdjustmentGeometry.frameTransform(4000, 3000, 1, true, 55)
        val back = toFrame.inverted()
        val sourceX = back.mapX(resolved.crop.right.toDouble(), resolved.crop.bottom.toDouble())
        val sourceY = back.mapY(resolved.crop.right.toDouble(), resolved.crop.bottom.toDouble())

        assertEquals(resolved.outputWidth.toDouble(), toOutput.mapX(sourceX, sourceY), 1e-6)
        assertEquals(resolved.outputHeight.toDouble(), toOutput.mapY(sourceX, sourceY), 1e-6)
        val bounds = AdjustmentGeometry.sourceBounds(resolved)
        assertTrue(bounds.left >= -1e-6 && bounds.top >= -1e-6 && bounds.right <= 4000 + 1e-6 && bounds.bottom <= 3000 + 1e-6)
        assertFalse(resolved.isPixelCopy)
    }

    @Test
    fun aspectSelectionUsesTheLargestCropAndOriginalFollowsOrientation() {
        val square = AdjustmentEditing.selectAspect(AdjustmentEdits(), 4000, 3000, CropAspect.SQUARE)
        assertEquals(PixelRect(500, 0, 3500, 3000), square.crop)

        val wide = AdjustmentEditing.selectAspect(AdjustmentEdits(), 4000, 3000, CropAspect.RATIO_16_9)
        assertEquals(PixelRect(0, 375, 4000, 2625), wide.crop)
        val tall = AdjustmentEditing.swapAspect(wide, 4000, 3000)
        assertTrue(tall.aspectSwapped)
        assertEquals(1688, tall.crop!!.width)
        assertEquals(3000, tall.crop!!.height)

        val original = AdjustmentEditing.selectAspect(AdjustmentEdits(crop = PixelRect(0, 0, 100, 100)), 4000, 3000, CropAspect.ORIGINAL)
        assertNull("The full frame already has the original ratio", original.crop)
        val turned = AdjustmentEditing.rotate(AdjustmentEdits(), 4000, 3000, clockwise = true)
        val turnedOriginal = AdjustmentEditing.selectAspect(turned, 4000, 3000, CropAspect.ORIGINAL)
        assertTrue(turnedOriginal.aspectSwapped)
        assertEquals(3.0 / 4.0, AdjustmentGeometry.aspectRatio(turnedOriginal, 4000, 3000)!!, 1e-9)
    }

    @Test
    fun freeDragStaysInsideTheFrameAndRespectsMinimumSize() {
        val start = CropBox(0.0, 0.0, 4000.0, 3000.0)
        val grown = AdjustmentGeometry.dragCrop(start, CropHandle.RIGHT, 500.0, 0.0, 4000, 3000, 0, null, 100.0)
        assertEquals(4000.0, grown.right, 1e-3)
        assertEquals(PixelRect(0, 0, 4000, 3000), AdjustmentGeometry.snapCrop(grown, 4000, 3000, 0))

        val shrunk = AdjustmentGeometry.dragCrop(start, CropHandle.BOTTOM_RIGHT, -5000.0, -500.0, 4000, 3000, 0, null, 100.0)
        assertEquals(100.0, shrunk.width, 1e-3)
        assertEquals(2500.0, shrunk.height, 1e-6)

        val moved = AdjustmentGeometry.dragCrop(CropBox(0.0, 0.0, 1000.0, 1000.0), CropHandle.MOVE, 5000.0, 200.0, 4000, 3000, 0, null, 100.0)
        assertEquals(CropBox(3000.0, 200.0, 4000.0, 1200.0).left, moved.left, 1e-3)
        assertEquals(200.0, moved.top, 1e-6)
    }

    @Test
    fun fixedRatioDragNeverStretches() {
        val start = CropBox(1000.0, 1000.0, 2600.0, 1900.0)
        val ratio = 16.0 / 9.0
        for (handle in CropHandle.entries.filter { it != CropHandle.MOVE }) {
            val result = AdjustmentGeometry.dragCrop(start, handle, 700.0, 450.0, 4000, 3000, 0, ratio, 50.0)
            assertEquals("$handle", ratio, result.width / result.height, 1e-6)
            assertTrue("$handle", AdjustmentGeometry.isValidCrop(result, 4000, 3000, 0))
        }
    }

    @Test
    fun dragsAreLimitedByTheRotatedPicture() {
        val resolved = AdjustmentGeometry.resolve(4000, 3000, AdjustmentEdits(straightenTenths = 150))
        val result = AdjustmentGeometry.dragCrop(resolved.crop.toCropBox(), CropHandle.TOP_LEFT, -800.0, -800.0, 4000, 3000, 150, null, 50.0)

        assertTrue(AdjustmentGeometry.isValidCrop(result, 4000, 3000, 150))
        val snapped = AdjustmentGeometry.snapCrop(result, 4000, 3000, 150)
        assertCovered(4000, 3000, 150, snapped)
    }

    @Test
    fun snappingIsDeterministicAndFullFrameCropIsStoredAsNoCrop() {
        assertEquals(PixelRect(10, 20, 31, 41), AdjustmentGeometry.snapCrop(CropBox(10.4, 19.6, 30.5, 40.6), 100, 100, 0))
        val full = AdjustmentEditing.setCrop(AdjustmentEdits(), 100, 50, PixelRect(0, 0, 100, 50))
        assertNull(full.crop)
    }

    @Test
    fun historyRecordsSingleStepsAndSupportsUndoRedo() {
        var history = com.poppinzhang.natsuko.model.AdjustmentHistory()
        val rotated = AdjustmentEdits(quarterTurns = 1)
        history = history.record(rotated).record(rotated)
        assertEquals(1, history.past.size)
        history = history.record(AdjustmentEdits())
        assertTrue(history.canUndo)

        history = history.undo()
        assertEquals(rotated, history.present)
        history = history.redo()
        assertEquals(AdjustmentEdits(), history.present)
        history = history.undo().undo()
        assertEquals(AdjustmentEdits(), history.present)
        assertFalse(history.canUndo)
        assertEquals(2, history.future.size)
        history = history.record(AdjustmentEdits(mirrored = true))
        assertFalse(history.canRedo)

        repeat(200) { history = history.record(AdjustmentEdits(straightenTenths = it + 1)) }
        assertEquals(com.poppinzhang.natsuko.model.AdjustmentHistory.LIMIT, history.past.size)
    }

    private fun sourceRegion(width: Int, height: Int, edits: AdjustmentEdits): List<Pair<Long, Long>> {
        val resolved = AdjustmentGeometry.resolve(width, height, edits)
        val back = AdjustmentGeometry.frameTransform(width, height, resolved.quarterTurns, resolved.mirrored, resolved.straightenTenths).inverted()
        val crop = resolved.crop
        return listOf(crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom)
            .map { (x, y) -> Math.round(back.mapX(x.toDouble(), y.toDouble())) to Math.round(back.mapY(x.toDouble(), y.toDouble())) }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun assertCovered(width: Int, height: Int, tenths: Int, crop: PixelRect) {
        val angle = AdjustmentGeometry.radians(tenths)
        listOf(crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom).forEach { (x, y) ->
            val dx = x - width / 2.0
            val dy = y - height / 2.0
            assertTrue("corner $x,$y at $tenths", abs(cos(angle) * dx + sin(angle) * dy) <= width / 2.0 + 1e-4)
            assertTrue("corner $x,$y at $tenths", abs(-sin(angle) * dx + cos(angle) * dy) <= height / 2.0 + 1e-4)
        }
        assertTrue(crop.left >= 0 && crop.top >= 0 && crop.right <= width && crop.bottom <= height)
    }

    private fun contains(outer: PixelRect, inner: PixelRect) =
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom
}
