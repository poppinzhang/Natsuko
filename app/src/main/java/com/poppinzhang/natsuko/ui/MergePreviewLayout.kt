package com.poppinzhang.natsuko.ui

import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.processing.ImagePlacement
import com.poppinzhang.natsuko.processing.ImageSize
import com.poppinzhang.natsuko.processing.MergeLayout
import com.poppinzhang.natsuko.processing.MergePlan
import kotlin.math.roundToInt

/** Fit the actual output plan into the viewport using one scale for every image. */
internal fun mergePreviewLayout(
    sizes: List<ImageSize>,
    direction: MergeDirection,
    maxWidth: Int,
    maxHeight: Int,
): MergePlan {
    require(maxWidth > 0 && maxHeight > 0)
    val output = MergeLayout.calculate(sizes, direction)
    val scale = minOf(maxWidth.toDouble() / output.width, maxHeight.toDouble() / output.height)
    val width = (output.width * scale).roundToInt().coerceIn(1, maxWidth)
    val height = (output.height * scale).roundToInt().coerceIn(1, maxHeight)
    val placements = output.placements.map { image ->
        val left = (image.left * scale).roundToInt().coerceIn(0, width - 1)
        val top = (image.top * scale).roundToInt().coerceIn(0, height - 1)
        val right = ((image.left + image.width) * scale).roundToInt().coerceIn(left + 1, width)
        val bottom = ((image.top + image.height) * scale).roundToInt().coerceIn(top + 1, height)
        ImagePlacement(left, top, right - left, bottom - top)
    }
    return MergePlan(width, height, placements)
}
