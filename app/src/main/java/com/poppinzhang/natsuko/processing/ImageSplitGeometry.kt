package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.model.SplitDirection
import com.poppinzhang.natsuko.model.SplitMode
import com.poppinzhang.natsuko.model.SplitOptions
import com.poppinzhang.natsuko.model.SplitOrientationHint
import kotlin.math.abs
import kotlin.math.roundToInt

data class SplitDivider(
    val index: Int,
    /** Inclusive start pixel along the split axis of the excluded strip. */
    val start: Int,
    /** Exclusive end pixel along the split axis of the excluded strip. */
    val end: Int,
) {
    val width: Int get() = end - start
    val center: Double get() = (start + end) / 2.0
}

data class SplitSlice(
    val index: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

data class SplitPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val direction: SplitDirection,
    /** Exact excluded divider strips along the split axis; width is 0 when no pixels are excluded. */
    val dividers: List<SplitDivider>,
    /** Slices extracted from the source image, separated by [dividers]. */
    val slices: List<SplitSlice>,
) {
    val axisLength: Int get() = if (direction == SplitDirection.HORIZONTAL) sourceWidth else sourceHeight
    val parts: Int get() = slices.size
    val dividerWidthPx: Int get() = dividers.firstOrNull()?.width ?: 0
    /** Rounded center coordinate of each divider along the split axis. */
    val dividersPx: List<Int> get() = dividers.map { it.center.roundToInt() }
}

data class SplitPreviewRect(
    val slice: SplitSlice,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

data class SplitPreviewPlan(
    val canvasLeft: Float,
    val canvasTop: Float,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val scale: Float,
    val slices: List<SplitPreviewRect>,
)

/**
 * Pure, deterministic split geometry shared by the interactive diagram, fullscreen view,
 * arranged preview, and full-resolution PNG export.
 */
object ImageSplitGeometry {
    fun axisLength(width: Int, height: Int, direction: SplitDirection): Int =
        if (direction == SplitDirection.HORIZONTAL) width else height

    fun maxPartsFor(width: Int, height: Int, direction: SplitDirection): Int =
        axisLength(width, height, direction).coerceIn(0, SplitOptions.MAX_PARTS)

    fun canSplit(width: Int, height: Int, direction: SplitDirection): Boolean =
        width > 0 && height > 0 && maxPartsFor(width, height, direction) >= SplitOptions.MIN_PARTS

    /** Maximum pixel width per divider line that still leaves at least 1 px in every slice. */
    fun maxDividerWidthFor(width: Int, height: Int, direction: SplitDirection, parts: Int): Int {
        if (!canSplit(width, height, direction)) return 0
        val axis = axisLength(width, height, direction)
        val effectiveParts = parts.coerceIn(SplitOptions.MIN_PARTS, maxPartsFor(width, height, direction))
        val dividerCount = effectiveParts - 1
        return ((axis - effectiveParts) / dividerCount).coerceIn(
            SplitOptions.MIN_DIVIDER_WIDTH_PX,
            SplitOptions.MAX_DIVIDER_WIDTH_PX,
        )
    }

    fun equalDividers(parts: Int): List<Double> {
        val count = parts.coerceIn(SplitOptions.MIN_PARTS, SplitOptions.MAX_PARTS)
        return List(count - 1) { index -> (index + 1).toDouble() / count }
    }

    /** Normalized center fractions of equal-split dividers for a given axis and divider width. */
    fun equalDividerFractions(axisLength: Int, parts: Int, dividerWidthPx: Int = 0): List<Double> {
        val count = parts.coerceIn(SplitOptions.MIN_PARTS, SplitOptions.MAX_PARTS)
        if (axisLength < count) return equalDividers(count)
        val maxWidth = ((axisLength - count) / (count - 1)).coerceIn(0, SplitOptions.MAX_DIVIDER_WIDTH_PX)
        val width = dividerWidthPx.coerceIn(0, maxWidth)
        return equalDividerStrips(axisLength, count, width).map { it.center / axisLength }
    }

    fun equalDividerStrips(axisLength: Int, parts: Int, dividerWidthPx: Int = 0): List<SplitDivider> {
        require(parts in SplitOptions.MIN_PARTS..SplitOptions.MAX_PARTS) { "Invalid part count: $parts" }
        require(dividerWidthPx >= 0) { "Divider width cannot be negative: $dividerWidthPx" }
        val dividerCount = parts - 1
        val keptTotal = axisLength - dividerCount * dividerWidthPx
        require(keptTotal >= parts) {
            "Axis length $axisLength cannot hold $parts slices with $dividerWidthPx px dividers"
        }
        return List(dividerCount) { index ->
            val keptBefore = ((index + 1) * keptTotal.toDouble() / parts).roundToInt()
            val start = keptBefore + index * dividerWidthPx
            SplitDivider(index = index, start = start, end = start + dividerWidthPx)
        }
    }

    fun equalDividersPx(axisLength: Int, parts: Int): List<Int> =
        equalDividerStrips(axisLength, parts, dividerWidthPx = 0).map { it.start }

    fun resolve(width: Int, height: Int, options: SplitOptions): SplitPlan? {
        if (!canSplit(width, height, options.direction)) return null
        val axis = axisLength(width, height, options.direction)
        val maxParts = maxPartsFor(width, height, options.direction)
        val parts = options.parts.coerceIn(SplitOptions.MIN_PARTS, maxParts)
        val maxDividerWidth = maxDividerWidthFor(width, height, options.direction, parts)
        val dividerWidth = options.dividerWidthPx.coerceIn(SplitOptions.MIN_DIVIDER_WIDTH_PX, maxDividerWidth)

        val dividers = when (options.mode) {
            SplitMode.EQUAL -> equalDividerStrips(axis, parts, dividerWidth)
            SplitMode.FREE -> resolveFreeDividerStrips(axis, parts, dividerWidth, options.freeDividers)
        }
        val slices = List(parts) { index ->
            val start = if (index == 0) 0 else dividers[index - 1].end
            val end = if (index == parts - 1) axis else dividers[index].start
            if (options.direction == SplitDirection.HORIZONTAL) {
                SplitSlice(index = index, left = start, top = 0, width = end - start, height = height)
            } else {
                SplitSlice(index = index, left = 0, top = start, width = width, height = end - start)
            }
        }
        return SplitPlan(
            sourceWidth = width,
            sourceHeight = height,
            direction = options.direction,
            dividers = dividers,
            slices = slices,
        )
    }

    fun moveDivider(
        width: Int,
        height: Int,
        options: SplitOptions,
        dividerIndex: Int,
        targetFraction: Double,
    ): List<Double> {
        val plan = resolve(width, height, options.copy(mode = SplitMode.FREE)) ?: return options.freeDividers
        if (dividerIndex !in plan.dividers.indices) return options.freeDividers
        val axis = plan.axisLength
        val d = plan.dividerWidthPx
        val keptAxis = axis - plan.dividers.size * d
        val minSlice = minInteractiveSlicePx(keptAxis, plan.parts)
        val prevEnd = if (dividerIndex == 0) 0 else plan.dividers[dividerIndex - 1].end
        val nextStart = if (dividerIndex == plan.dividers.lastIndex) axis else plan.dividers[dividerIndex + 1].start

        val hardMinStart = prevEnd + 1
        val hardMaxStart = nextStart - d - 1
        val softMinStart = prevEnd + minSlice
        val softMaxStart = nextStart - d - minSlice
        val safeMinStart = if (softMinStart <= softMaxStart) softMinStart.coerceIn(hardMinStart, hardMaxStart) else hardMinStart
        val safeMaxStart = if (softMinStart <= softMaxStart) softMaxStart.coerceIn(safeMinStart, hardMaxStart) else hardMaxStart

        val rawStart = (targetFraction.coerceIn(0.0, 1.0) * axis - d / 2.0).roundToInt()
        val clampedStart = rawStart.coerceIn(safeMinStart, safeMaxStart)
        return plan.dividers.mapIndexed { index, strip ->
            val start = if (index == dividerIndex) clampedStart else strip.start
            (start + d / 2.0) / axis
        }
    }

    fun nudgeDivider(
        width: Int,
        height: Int,
        options: SplitOptions,
        dividerIndex: Int,
        deltaPx: Int,
    ): List<Double> {
        val plan = resolve(width, height, options.copy(mode = SplitMode.FREE)) ?: return options.freeDividers
        if (dividerIndex !in plan.dividers.indices) return options.freeDividers
        val axis = plan.axisLength
        val d = plan.dividerWidthPx
        val prevEnd = if (dividerIndex == 0) 0 else plan.dividers[dividerIndex - 1].end
        val nextStart = if (dividerIndex == plan.dividers.lastIndex) axis else plan.dividers[dividerIndex + 1].start
        val minStart = prevEnd + 1
        val maxStart = nextStart - d - 1
        val clampedStart = (plan.dividers[dividerIndex].start + deltaPx).coerceIn(minStart, maxStart)
        return plan.dividers.mapIndexed { index, strip ->
            val start = if (index == dividerIndex) clampedStart else strip.start
            (start + d / 2.0) / axis
        }
    }

    fun hasCustomDividers(width: Int, height: Int, options: SplitOptions): Boolean {
        if (options.mode != SplitMode.FREE) return false
        val plan = resolve(width, height, options) ?: return false
        val equal = equalDividerStrips(plan.axisLength, plan.parts, plan.dividerWidthPx)
        return plan.dividers != equal
    }

    /**
     * Lays out all split slices in order separated by [spacingPx] (in source image pixels)
     * and fits the combined composition inside the viewport with uniform scaling.
     * Excluded divider pixels are omitted from the slices, so the layout reflects the true exported slices.
     */
    fun previewLayout(
        plan: SplitPlan,
        spacingPx: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        paddingPx: Float = 0f,
    ): SplitPreviewPlan {
        require(viewportWidth > 0f && viewportHeight > 0f)
        val gap = spacingPx.coerceIn(SplitOptions.MIN_PREVIEW_SPACING_PX, SplitOptions.MAX_PREVIEW_SPACING_PX).toDouble()
        val gapCount = (plan.slices.size - 1).coerceAtLeast(0)
        val keptWidth = when (plan.direction) {
            SplitDirection.HORIZONTAL -> plan.slices.sumOf { it.width }.toDouble()
            SplitDirection.VERTICAL -> plan.sourceWidth.toDouble()
        }
        val keptHeight = when (plan.direction) {
            SplitDirection.HORIZONTAL -> plan.sourceHeight.toDouble()
            SplitDirection.VERTICAL -> plan.slices.sumOf { it.height }.toDouble()
        }
        val virtualWidth = when (plan.direction) {
            SplitDirection.HORIZONTAL -> keptWidth + gap * gapCount
            SplitDirection.VERTICAL -> keptWidth
        }
        val virtualHeight = when (plan.direction) {
            SplitDirection.HORIZONTAL -> keptHeight
            SplitDirection.VERTICAL -> keptHeight + gap * gapCount
        }
        val availableWidth = (viewportWidth - paddingPx * 2f).coerceAtLeast(1f)
        val availableHeight = (viewportHeight - paddingPx * 2f).coerceAtLeast(1f)
        val scale = minOf(availableWidth / virtualWidth, availableHeight / virtualHeight).toFloat()
        val canvasWidth = (virtualWidth * scale).toFloat()
        val canvasHeight = (virtualHeight * scale).toFloat()
        val canvasLeft = (viewportWidth - canvasWidth) / 2f
        val canvasTop = (viewportHeight - canvasHeight) / 2f
        val scaledGap = (gap * scale).toFloat()

        var cursor = 0f
        val rects = plan.slices.mapIndexed { index, slice ->
            if (plan.direction == SplitDirection.HORIZONTAL) {
                val sliceWidth = slice.width * scale
                val rect = SplitPreviewRect(
                    slice = slice,
                    left = canvasLeft + cursor,
                    top = canvasTop,
                    width = sliceWidth,
                    height = canvasHeight,
                )
                cursor += sliceWidth + if (index < plan.slices.lastIndex) scaledGap else 0f
                rect
            } else {
                val sliceHeight = slice.height * scale
                val rect = SplitPreviewRect(
                    slice = slice,
                    left = canvasLeft,
                    top = canvasTop + cursor,
                    width = canvasWidth,
                    height = sliceHeight,
                )
                cursor += sliceHeight + if (index < plan.slices.lastIndex) scaledGap else 0f
                rect
            }
        }
        return SplitPreviewPlan(
            canvasLeft = canvasLeft,
            canvasTop = canvasTop,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            scale = scale,
            slices = rects,
        )
    }

    /** Landscape images adapt to phone landscape; portrait images adapt to phone portrait. */
    fun fullscreenOrientation(width: Int, height: Int): SplitOrientationHint = when {
        width <= 0 || height <= 0 -> SplitOrientationHint.UNSPECIFIED
        width > height -> SplitOrientationHint.LANDSCAPE
        height > width -> SplitOrientationHint.PORTRAIT
        else -> SplitOrientationHint.UNSPECIFIED
    }

    internal fun minInteractiveSlicePx(keptAxisLength: Int, parts: Int): Int {
        return maxOf(1, minOf(8, keptAxisLength / (parts * 2)))
    }

    private fun resolveFreeDividerStrips(
        axisLength: Int,
        parts: Int,
        dividerWidthPx: Int,
        fractions: List<Double>,
    ): List<SplitDivider> {
        val count = parts - 1
        if (fractions.size != count || fractions.any { it.isNaN() || it.isInfinite() }) {
            return equalDividerStrips(axisLength, parts, dividerWidthPx)
        }
        val sorted = fractions.sorted()
        val result = ArrayList<SplitDivider>(count)
        for (i in 0 until count) {
            val minStart = if (i == 0) 1 else result[i - 1].end + 1
            val maxStart = axisLength - (count - i) * (dividerWidthPx + 1)
            val candidateStart = (sorted[i].coerceIn(0.0, 1.0) * axisLength - dividerWidthPx / 2.0).roundToInt()
            val start = candidateStart.coerceIn(minStart, maxStart)
            result += SplitDivider(index = i, start = start, end = start + dividerWidthPx)
        }
        return result
    }

    /**
     * Returns the index of the closest divider strip within [maxDistance], or null if none is near.
     * Points directly inside a wider divider band have distance 0 and tie-break by proximity to center.
     */
    fun hitTestDivider(
        positionAlongAxis: Float,
        imageStart: Float,
        imageSpan: Float,
        plan: SplitPlan,
        maxDistance: Float,
    ): Int? {
        if (imageSpan <= 0f || plan.dividers.isEmpty()) return null
        val axis = plan.axisLength.toFloat()
        var bestIndex: Int? = null
        var bestEdgeDistance = Float.MAX_VALUE
        var bestCenterDistance = Float.MAX_VALUE
        for (i in plan.dividers.indices) {
            val strip = plan.dividers[i]
            val bandStart = imageStart + imageSpan * (strip.start / axis)
            val bandEnd = imageStart + imageSpan * (strip.end / axis)
            val edgeDistance = when {
                positionAlongAxis < bandStart -> bandStart - positionAlongAxis
                positionAlongAxis > bandEnd -> positionAlongAxis - bandEnd
                else -> 0f
            }
            if (edgeDistance <= maxDistance) {
                val centerDistance = abs(positionAlongAxis - (bandStart + bandEnd) / 2f)
                if (edgeDistance < bestEdgeDistance ||
                    (edgeDistance == bestEdgeDistance && centerDistance < bestCenterDistance)
                ) {
                    bestEdgeDistance = edgeDistance
                    bestCenterDistance = centerDistance
                    bestIndex = i
                }
            }
        }
        return bestIndex
    }
}
