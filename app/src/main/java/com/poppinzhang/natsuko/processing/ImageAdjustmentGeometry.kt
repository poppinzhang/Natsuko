package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.model.AdjustmentEdits
import com.poppinzhang.natsuko.model.CropAspect
import com.poppinzhang.natsuko.model.PixelRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** x' = scaleX·x + skewX·y + translateX; y' = skewY·x + scaleY·y + translateY (android.graphics.Matrix order). */
data class AffineTransform(
    val scaleX: Double = 1.0,
    val skewX: Double = 0.0,
    val translateX: Double = 0.0,
    val skewY: Double = 0.0,
    val scaleY: Double = 1.0,
    val translateY: Double = 0.0,
) {
    fun mapX(x: Double, y: Double): Double = scaleX * x + skewX * y + translateX
    fun mapY(x: Double, y: Double): Double = skewY * x + scaleY * y + translateY

    /** Applies this transform first, then [next]. */
    fun then(next: AffineTransform) = AffineTransform(
        scaleX = next.scaleX * scaleX + next.skewX * skewY,
        skewX = next.scaleX * skewX + next.skewX * scaleY,
        translateX = next.scaleX * translateX + next.skewX * translateY + next.translateX,
        skewY = next.skewY * scaleX + next.scaleY * skewY,
        scaleY = next.skewY * skewX + next.scaleY * scaleY,
        translateY = next.skewY * translateX + next.scaleY * translateY + next.translateY,
    )

    fun inverted(): AffineTransform {
        val determinant = scaleX * scaleY - skewX * skewY
        require(determinant != 0.0) { "Singular transform" }
        val a = scaleY / determinant
        val b = -skewX / determinant
        val c = -skewY / determinant
        val d = scaleX / determinant
        return AffineTransform(a, b, -(a * translateX + b * translateY), c, d, -(c * translateX + d * translateY))
    }

    /** The nine values expected by android.graphics.Matrix.setValues. */
    fun matrixValues(): FloatArray = floatArrayOf(
        scaleX.toFloat(), skewX.toFloat(), translateX.toFloat(),
        skewY.toFloat(), scaleY.toFloat(), translateY.toFloat(),
        0f, 0f, 1f,
    )

    companion object {
        val IDENTITY = AffineTransform()
        fun translate(x: Double, y: Double) = AffineTransform(translateX = x, translateY = y)
        fun scale(x: Double, y: Double) = AffineTransform(scaleX = x, scaleY = y)

        /** Positive angles turn clockwise in y-down screen coordinates. */
        fun rotate(radians: Double): AffineTransform {
            val cos = cos(radians)
            val sin = sin(radians)
            return AffineTransform(scaleX = cos, skewX = -sin, skewY = sin, scaleY = cos)
        }
    }
}

/** Continuous rectangle in rotated-frame pixels, used while a crop gesture is in progress. */
data class CropBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2

    fun offset(x: Double, y: Double) = CropBox(left + x, top + y, right + x, bottom + y)
}

fun PixelRect.toCropBox() = CropBox(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())

/**
 * The one geometry result shared by the preview bounds, size display, trial encoding, and export.
 * The frame is the oriented source after quarter turns and mirroring; straightening rotates the
 * picture inside that axis-aligned frame, and [crop] is the retained frame area.
 */
data class ResolvedAdjustment(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val quarterTurns: Int,
    val mirrored: Boolean,
    val straightenTenths: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val crop: PixelRect,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    /** Size after cropping, straightening, rotation, and mirroring, before any reduction. */
    val geometryWidth: Int get() = crop.width
    val geometryHeight: Int get() = crop.height

    /** Output pixels equal a source region without resampling. */
    val isPixelCopy: Boolean
        get() = quarterTurns == 0 && !mirrored && straightenTenths == 0 &&
            outputWidth == crop.width && outputHeight == crop.height
}

enum class CropHandle { LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

object AdjustmentGeometry {
    const val MAX_STRAIGHTEN_TENTHS = 450
    private const val TOLERANCE = 1e-6
    private const val SEARCH_STEPS = 30
    private const val TERNARY_STEPS = 80
    private const val PLACEMENT_PREFERENCE = 1e-6

    fun normalizedTurns(quarterTurns: Int): Int = Math.floorMod(quarterTurns, 4)

    fun frameSize(sourceWidth: Int, sourceHeight: Int, quarterTurns: Int): ImageSize =
        if (normalizedTurns(quarterTurns) % 2 == 0) ImageSize(sourceWidth, sourceHeight) else ImageSize(sourceHeight, sourceWidth)

    fun radians(tenths: Int): Double = Math.toRadians(tenths / 10.0)

    fun resolve(sourceWidth: Int, sourceHeight: Int, edits: AdjustmentEdits): ResolvedAdjustment {
        require(sourceWidth > 0 && sourceHeight > 0) { "Invalid source size" }
        val turns = normalizedTurns(edits.quarterTurns)
        val tenths = edits.straightenTenths.coerceIn(-MAX_STRAIGHTEN_TENTHS, MAX_STRAIGHTEN_TENTHS)
        val frame = frameSize(sourceWidth, sourceHeight, turns)
        val crop = effectiveCrop(frame.width, frame.height, edits.crop, tenths)
        val output = scaledSize(crop.width, crop.height, edits.scale)
        return ResolvedAdjustment(
            sourceWidth, sourceHeight, turns, edits.mirrored, tenths,
            frame.width, frame.height, crop, output.width, output.height,
        )
    }

    /** A reduction keeps the proportions; each side is rounded once and never enlarged. */
    fun scaledSize(width: Int, height: Int, scale: Double): ImageSize {
        val factor = if (scale > 0.0 && scale < 1.0) scale else 1.0
        return ImageSize(
            (width * factor).roundToInt().coerceIn(1, width),
            (height * factor).roundToInt().coerceIn(1, height),
        )
    }

    /** Mirroring happens first, then clockwise quarter turns, mapping oriented source pixels into the frame. */
    fun orientation(sourceWidth: Int, sourceHeight: Int, quarterTurns: Int, mirrored: Boolean): AffineTransform {
        val width = sourceWidth.toDouble()
        val height = sourceHeight.toDouble()
        val mirror = if (mirrored) AffineTransform(scaleX = -1.0, translateX = width) else AffineTransform.IDENTITY
        val turn = when (normalizedTurns(quarterTurns)) {
            0 -> AffineTransform.IDENTITY
            1 -> AffineTransform(scaleX = 0.0, skewX = -1.0, translateX = height, skewY = 1.0, scaleY = 0.0)
            2 -> AffineTransform(scaleX = -1.0, translateX = width, scaleY = -1.0, translateY = height)
            else -> AffineTransform(scaleX = 0.0, skewX = 1.0, skewY = -1.0, scaleY = 0.0, translateY = width)
        }
        return mirror.then(turn)
    }

    /** Rotates the picture about the frame center; the frame itself stays axis-aligned. */
    fun straighten(frameWidth: Int, frameHeight: Int, tenths: Int): AffineTransform {
        if (tenths == 0) return AffineTransform.IDENTITY
        val centerX = frameWidth / 2.0
        val centerY = frameHeight / 2.0
        return AffineTransform.translate(-centerX, -centerY)
            .then(AffineTransform.rotate(radians(tenths)))
            .then(AffineTransform.translate(centerX, centerY))
    }

    /** Oriented source pixels to straightened frame pixels. */
    fun frameTransform(sourceWidth: Int, sourceHeight: Int, quarterTurns: Int, mirrored: Boolean, straightenTenths: Int): AffineTransform {
        val frame = frameSize(sourceWidth, sourceHeight, quarterTurns)
        return orientation(sourceWidth, sourceHeight, quarterTurns, mirrored)
            .then(straighten(frame.width, frame.height, straightenTenths))
    }

    /** Oriented source pixels to output pixels: every geometric edit is merged into one resampling step. */
    fun outputTransform(resolved: ResolvedAdjustment): AffineTransform =
        frameTransform(resolved.sourceWidth, resolved.sourceHeight, resolved.quarterTurns, resolved.mirrored, resolved.straightenTenths)
            .then(AffineTransform.translate(-resolved.crop.left.toDouble(), -resolved.crop.top.toDouble()))
            .then(
                AffineTransform.scale(
                    resolved.outputWidth.toDouble() / resolved.crop.width,
                    resolved.outputHeight.toDouble() / resolved.crop.height,
                ),
            )

    /** Bounding box of the oriented source area that contributes to the output. */
    fun sourceBounds(resolved: ResolvedAdjustment): CropBox {
        val toSource = outputTransform(resolved).inverted()
        val corners = listOf(
            0.0 to 0.0,
            resolved.outputWidth.toDouble() to 0.0,
            0.0 to resolved.outputHeight.toDouble(),
            resolved.outputWidth.toDouble() to resolved.outputHeight.toDouble(),
        )
        val xs = corners.map { (x, y) -> toSource.mapX(x, y) }
        val ys = corners.map { (x, y) -> toSource.mapY(x, y) }
        return CropBox(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /**
     * The retained crop for a straighten angle. At 0° this is exactly the user's crop. Otherwise a
     * crop that reaches uncovered corners shrinks, keeping its proportions and as much picture as
     * possible, and snaps inward to whole pixels so export never trims more than the preview shows.
     */
    fun effectiveCrop(frameWidth: Int, frameHeight: Int, crop: PixelRect?, tenths: Int): PixelRect {
        val user = crop?.let { clampToFrame(it, frameWidth, frameHeight) } ?: PixelRect(0, 0, frameWidth, frameHeight)
        if (tenths == 0 || isValidCrop(user.toCropBox(), frameWidth, frameHeight, tenths)) return user
        return snapInward(fitInsideRotation(user.toCropBox(), frameWidth.toDouble(), frameHeight.toDouble(), radians(tenths)), frameWidth, frameHeight)
    }

    /** Inside the frame and, when straightened, entirely covered by the rotated picture. */
    fun isValidCrop(box: CropBox, frameWidth: Int, frameHeight: Int, tenths: Int, minSize: Double = 1.0): Boolean {
        if (!(box.width >= minSize - TOLERANCE && box.height >= minSize - TOLERANCE)) return false
        if (box.left < -TOLERANCE || box.top < -TOLERANCE ||
            box.right > frameWidth + TOLERANCE || box.bottom > frameHeight + TOLERANCE
        ) return false
        if (tenths == 0) return true
        val radians = radians(tenths)
        val cos = cos(radians)
        val sin = sin(radians)
        val halfWidth = frameWidth / 2.0
        val halfHeight = frameHeight / 2.0
        return listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)
            .all { (x, y) ->
                val dx = x - halfWidth
                val dy = y - halfHeight
                abs(cos * dx + sin * dy) <= halfWidth + TOLERANCE && abs(-sin * dx + cos * dy) <= halfHeight + TOLERANCE
            }
    }

    /** Width:height in the current frame orientation; null when the crop is free. */
    fun aspectRatio(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int): Double? {
        val base = when (edits.aspect) {
            CropAspect.FREE -> return null
            CropAspect.ORIGINAL -> sourceWidth.toDouble() / sourceHeight
            else -> edits.aspect.ratioWidth.toDouble() / edits.aspect.ratioHeight
        }
        return if (edits.aspectSwapped) 1 / base else base
    }

    /** The largest crop with [ratio] inside the frame, centered as close to the given point as possible. */
    fun largestCrop(frameWidth: Int, frameHeight: Int, ratio: Double, centerX: Double, centerY: Double): PixelRect {
        var width = frameWidth.toDouble()
        var height = width / ratio
        if (height > frameHeight) {
            height = frameHeight.toDouble()
            width = height * ratio
        }
        val roundedWidth = width.roundToInt().coerceIn(1, frameWidth)
        val roundedHeight = height.roundToInt().coerceIn(1, frameHeight)
        val left = (centerX - roundedWidth / 2.0).roundToInt().coerceIn(0, frameWidth - roundedWidth)
        val top = (centerY - roundedHeight / 2.0).roundToInt().coerceIn(0, frameHeight - roundedHeight)
        return PixelRect(left, top, left + roundedWidth, top + roundedHeight)
    }

    /**
     * Moves a handle by a pointer offset measured from the gesture start. A blocked axis stops at
     * the valid boundary while the other axis keeps following, so corners slide along edges.
     */
    fun dragCrop(
        start: CropBox,
        handle: CropHandle,
        dx: Double,
        dy: Double,
        frameWidth: Int,
        frameHeight: Int,
        tenths: Int,
        ratio: Double?,
        minSize: Double,
    ): CropBox {
        val minimum = minOf(minSize, start.width, start.height).coerceAtLeast(TOLERANCE)
        val valid = { box: CropBox -> isValidCrop(box, frameWidth, frameHeight, tenths, minimum) }
        if (!valid(start)) return start
        if (handle == CropHandle.MOVE) {
            val moved = farthest(start, valid) { t -> start.offset(dx * t, 0.0) }
            return farthest(moved, valid) { t -> moved.offset(0.0, dy * t) }
        }
        val left = handle == CropHandle.LEFT || handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT
        val right = handle == CropHandle.RIGHT || handle == CropHandle.TOP_RIGHT || handle == CropHandle.BOTTOM_RIGHT
        val top = handle == CropHandle.TOP || handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT
        val bottom = handle == CropHandle.BOTTOM || handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM_RIGHT
        if (ratio == null) {
            val horizontal = farthest(start, valid) { t ->
                start.copy(left = if (left) start.left + dx * t else start.left, right = if (right) start.right + dx * t else start.right)
            }
            return farthest(horizontal, valid) { t ->
                horizontal.copy(top = if (top) horizontal.top + dy * t else horizontal.top, bottom = if (bottom) horizontal.bottom + dy * t else horizontal.bottom)
            }
        }
        // A fixed ratio scales the box about the opposite corner or edge; corner movement is
        // projected onto the diagonal so the handle tracks the finger without distorting the ratio.
        val growth = when {
            (left || right) && (top || bottom) -> {
                val signX = if (right) 1.0 else -1.0
                val signY = if (bottom) 1.0 else -1.0
                (dx * signX * start.width + dy * signY * start.height) / (start.width * start.width + start.height * start.height)
            }
            right -> dx / start.width
            left -> -dx / start.width
            bottom -> dy / start.height
            else -> -dy / start.height
        }
        return farthest(start, valid) { t ->
            val width = start.width * (1 + growth * t)
            val height = start.height * (1 + growth * t)
            val boxLeft = when {
                left -> start.right - width
                right -> start.left
                else -> start.centerX - width / 2
            }
            val boxTop = when {
                top -> start.bottom - height
                bottom -> start.top
                else -> start.centerY - height / 2
            }
            CropBox(boxLeft, boxTop, boxLeft + width, boxTop + height)
        }
    }

    /** Whole-pixel crop for a finished gesture; rounding never reaches an uncovered corner. */
    fun snapCrop(box: CropBox, frameWidth: Int, frameHeight: Int, tenths: Int): PixelRect {
        val left = box.left.roundToInt().coerceIn(0, frameWidth - 1)
        val top = box.top.roundToInt().coerceIn(0, frameHeight - 1)
        val rounded = PixelRect(
            left,
            top,
            box.right.roundToInt().coerceIn(left + 1, frameWidth),
            box.bottom.roundToInt().coerceIn(top + 1, frameHeight),
        )
        return if (isValidCrop(rounded.toCropBox(), frameWidth, frameHeight, tenths)) rounded else snapInward(box, frameWidth, frameHeight)
    }

    private fun clampToFrame(rect: PixelRect, frameWidth: Int, frameHeight: Int): PixelRect {
        val left = rect.left.coerceIn(0, frameWidth - 1)
        val top = rect.top.coerceIn(0, frameHeight - 1)
        return PixelRect(left, top, rect.right.coerceIn(left + 1, frameWidth), rect.bottom.coerceIn(top + 1, frameHeight))
    }

    private fun snapInward(box: CropBox, frameWidth: Int, frameHeight: Int): PixelRect {
        fun span(start: Double, end: Double, center: Double, limit: Int): Pair<Int, Int> {
            val low = ceil(start - TOLERANCE).toInt()
            val high = floor(end + TOLERANCE).toInt()
            val (from, to) = if (high > low) low to high else floor(center).toInt().let { it to it + 1 }
            val clamped = from.coerceIn(0, limit - 1)
            return clamped to to.coerceIn(clamped + 1, limit)
        }
        val (left, right) = span(box.left, box.right, box.centerX, frameWidth)
        val (top, bottom) = span(box.top, box.bottom, box.centerY, frameHeight)
        return PixelRect(left, top, right, bottom)
    }

    private fun fitInsideRotation(user: CropBox, frameWidth: Double, frameHeight: Double, radians: Double): CropBox {
        val width = user.width
        val height = user.height
        val cos = cos(radians)
        val sin = sin(radians)
        // Half extents of the scaled crop along the picture's own axes, per unit of scale.
        val halfAlong = width / 2 * abs(cos) + height / 2 * abs(sin)
        val halfAcross = width / 2 * abs(sin) + height / 2 * abs(cos)
        val centerX = frameWidth / 2
        val centerY = frameHeight / 2
        val rotated = { x: Double, y: Double ->
            val dx = x - centerX
            val dy = y - centerY
            minOf(
                (frameWidth / 2 - abs(cos * dx + sin * dy)) / halfAlong,
                (frameHeight / 2 - abs(-sin * dx + cos * dy)) / halfAcross,
            )
        }
        // Prefer tightening inside the user's crop. A crop lying in an uncovered corner keeps its
        // proportions and maximum size but may move toward the picture.
        val inside = maximizeScale(user.left, user.right, user.top, user.bottom, user, frameWidth, frameHeight) { x, y ->
            minOf(1 - 2 * abs(x - user.centerX) / width, 1 - 2 * abs(y - user.centerY) / height, rotated(x, y))
        }
        val best = if (inside.scale * minOf(width, height) >= 1.0) inside else {
            maximizeScale(0.0, frameWidth, 0.0, frameHeight, user, frameWidth, frameHeight) { x, y ->
                minOf(1.0, (frameWidth - 2 * abs(x - centerX)) / width, (frameHeight - 2 * abs(y - centerY)) / height, rotated(x, y))
            }
        }
        val scale = best.scale.coerceIn(0.0, 1.0)
        return CropBox(best.x - width * scale / 2, best.y - height * scale / 2, best.x + width * scale / 2, best.y + height * scale / 2)
    }

    private class Placement(val x: Double, val y: Double, val scale: Double)

    /**
     * Every limit is concave in the crop center, so nested ternary searches find the largest scale.
     * A negligible distance penalty makes equally large placements deterministic and keeps them as
     * close as possible to the user's crop center.
     */
    private fun maximizeScale(
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        preferred: CropBox,
        frameWidth: Double,
        frameHeight: Double,
        limit: (Double, Double) -> Double,
    ): Placement {
        val score = { x: Double, y: Double ->
            limit(x, y) - PLACEMENT_PREFERENCE * (abs(x - preferred.centerX) / frameWidth + abs(y - preferred.centerY) / frameHeight)
        }
        val bestY = { x: Double -> ternary(minY, maxY) { y -> score(x, y) } }
        val x = ternary(minX, maxX) { x -> score(x, bestY(x)) }
        val y = bestY(x)
        return Placement(x, y, limit(x, y))
    }

    private fun ternary(low: Double, high: Double, value: (Double) -> Double): Double {
        var from = low
        var to = high
        repeat(TERNARY_STEPS) {
            val first = from + (to - from) / 3
            val second = to - (to - from) / 3
            if (value(first) < value(second)) from = first else to = second
        }
        return (from + to) / 2
    }

    private fun farthest(start: CropBox, valid: (CropBox) -> Boolean, candidate: (Double) -> CropBox): CropBox {
        val end = candidate(1.0)
        if (valid(end)) return end
        var low = 0.0
        var high = 1.0
        repeat(SEARCH_STEPS) {
            val middle = (low + high) / 2
            if (valid(candidate(middle))) low = middle else high = middle
        }
        return if (low == 0.0) start else candidate(low)
    }
}

/** Edit operations expressed in the orientation the user currently sees. */
object AdjustmentEditing {
    fun rotate(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int, clockwise: Boolean): AdjustmentEdits {
        val frame = AdjustmentGeometry.frameSize(sourceWidth, sourceHeight, edits.quarterTurns)
        val crop = edits.crop?.let { rect ->
            if (clockwise) {
                PixelRect(frame.height - rect.bottom, rect.left, frame.height - rect.top, rect.right)
            } else {
                PixelRect(rect.top, frame.width - rect.right, rect.bottom, frame.width - rect.left)
            }
        }
        return edits.copy(
            quarterTurns = AdjustmentGeometry.normalizedTurns(edits.quarterTurns + if (clockwise) 1 else -1),
            crop = crop,
            // The crop turns with the picture, so a fixed ratio swaps its width and height.
            aspectSwapped = if (isOrientable(edits.aspect)) !edits.aspectSwapped else edits.aspectSwapped,
        )
    }

    /** A flip of the visible picture; it also mirrors the straighten direction and the crop. */
    fun flip(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int, horizontal: Boolean): AdjustmentEdits {
        val frame = AdjustmentGeometry.frameSize(sourceWidth, sourceHeight, edits.quarterTurns)
        val crop = edits.crop?.let { rect ->
            if (horizontal) {
                PixelRect(frame.width - rect.right, rect.top, frame.width - rect.left, rect.bottom)
            } else {
                PixelRect(rect.left, frame.height - rect.bottom, rect.right, frame.height - rect.top)
            }
        }
        // Reflection reverses rotation: flip ∘ turn(k) = turn(-k) ∘ flip, and a vertical flip is a
        // horizontal one followed by a half turn.
        val turns = if (horizontal) -edits.quarterTurns else 2 - edits.quarterTurns
        return edits.copy(
            quarterTurns = AdjustmentGeometry.normalizedTurns(turns),
            mirrored = !edits.mirrored,
            straightenTenths = -edits.straightenTenths,
            crop = crop,
        )
    }

    fun resetCrop(edits: AdjustmentEdits) = edits.copy(crop = null, aspect = CropAspect.FREE, aspectSwapped = false)

    fun resetStraighten(edits: AdjustmentEdits) = edits.copy(straightenTenths = 0)

    /** Removes the quarter turns while keeping the mirror, angle, and crop on the same picture content. */
    fun resetRotation(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int): AdjustmentEdits {
        var result = edits
        repeat(AdjustmentGeometry.normalizedTurns(edits.quarterTurns)) {
            result = rotate(result, sourceWidth, sourceHeight, clockwise = false)
        }
        return result
    }

    /** Removes the mirror while keeping the current quarter turns. */
    fun resetMirror(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int): AdjustmentEdits =
        if (!edits.mirrored) edits else {
            flip(edits, sourceWidth, sourceHeight, horizontal = AdjustmentGeometry.normalizedTurns(edits.quarterTurns) % 2 == 0)
        }

    fun resetScale(edits: AdjustmentEdits) = edits.copy(scale = 1.0)

    fun setStraighten(edits: AdjustmentEdits, tenths: Int) = edits.copy(
        straightenTenths = tenths.coerceIn(-AdjustmentGeometry.MAX_STRAIGHTEN_TENTHS, AdjustmentGeometry.MAX_STRAIGHTEN_TENTHS),
    )

    /** Reductions only: values above 1 restore the edited size instead of enlarging it. */
    fun setScale(edits: AdjustmentEdits, scale: Double): AdjustmentEdits =
        if (scale > 0.0) edits.copy(scale = scale.coerceAtMost(1.0)) else edits

    /** The reduction that makes the edited width equal [width]; the height follows the same ratio. */
    fun scaleForWidth(resolved: ResolvedAdjustment, width: Int): Double =
        width.coerceIn(1, resolved.geometryWidth).toDouble() / resolved.geometryWidth

    fun scaleForHeight(resolved: ResolvedAdjustment, height: Int): Double =
        height.coerceIn(1, resolved.geometryHeight).toDouble() / resolved.geometryHeight

    fun setCrop(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int, crop: PixelRect): AdjustmentEdits {
        val frame = AdjustmentGeometry.frameSize(sourceWidth, sourceHeight, edits.quarterTurns)
        return edits.copy(crop = crop.takeUnless { it == PixelRect(0, 0, frame.width, frame.height) })
    }

    /** Selecting a ratio uses the largest crop of that ratio around the current crop center. */
    fun selectAspect(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int, aspect: CropAspect): AdjustmentEdits {
        if (aspect == CropAspect.FREE) return edits.copy(aspect = aspect, aspectSwapped = false)
        val current = AdjustmentGeometry.resolve(sourceWidth, sourceHeight, edits).crop
        val swapped = when (aspect) {
            CropAspect.SQUARE -> false
            // The original proportions as currently displayed: a quarter turn shows height:width.
            CropAspect.ORIGINAL -> AdjustmentGeometry.normalizedTurns(edits.quarterTurns) % 2 == 1
            else -> current.height > current.width
        }
        return withAspect(edits.copy(aspect = aspect, aspectSwapped = swapped), sourceWidth, sourceHeight, current)
    }

    fun swapAspect(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int): AdjustmentEdits {
        if (!isOrientable(edits.aspect)) return edits
        val current = AdjustmentGeometry.resolve(sourceWidth, sourceHeight, edits).crop
        return withAspect(edits.copy(aspectSwapped = !edits.aspectSwapped), sourceWidth, sourceHeight, current)
    }

    fun isOrientable(aspect: CropAspect) = aspect != CropAspect.FREE && aspect != CropAspect.SQUARE

    private fun withAspect(edits: AdjustmentEdits, sourceWidth: Int, sourceHeight: Int, around: PixelRect): AdjustmentEdits {
        val ratio = AdjustmentGeometry.aspectRatio(edits, sourceWidth, sourceHeight) ?: return edits
        val frame = AdjustmentGeometry.frameSize(sourceWidth, sourceHeight, edits.quarterTurns)
        val crop = AdjustmentGeometry.largestCrop(
            frame.width,
            frame.height,
            ratio,
            (around.left + around.right) / 2.0,
            (around.top + around.bottom) / 2.0,
        )
        return setCrop(edits, sourceWidth, sourceHeight, crop)
    }
}
