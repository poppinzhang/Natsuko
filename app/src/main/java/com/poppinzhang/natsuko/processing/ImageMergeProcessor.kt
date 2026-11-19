package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.checkText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.model.MergeGridLayout
import com.poppinzhang.natsuko.model.MergeOptions
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ImageSize(val width: Int, val height: Int)
data class ImagePlacement(val left: Int, val top: Int, val width: Int, val height: Int)
data class MergePlan(val width: Int, val height: Int, val placements: List<ImagePlacement>)
data class ImageMergeResult(val outcome: ProcessingOutcome, val mergedInputs: List<InputItem>)

object MergeLayout {
    fun calculate(
        sizes: List<ImageSize>,
        direction: MergeDirection,
        spacingPx: Int = 0,
        maxLongSide: Int = 8192,
        maxPixels: Long = 40_000_000,
    ): MergePlan {
        require(sizes.isNotEmpty())
        require(sizes.all { it.width > 0 && it.height > 0 })
        require(spacingPx >= 0)
        val rawSpacing = spacingPx.coerceAtMost(MergeOptions.MAX_SPACING_PX).toLong()

        if (direction == MergeDirection.GRID) {
            return calculateGrid(sizes, rawSpacing, maxLongSide, maxPixels)
        }

        val rawWidth = when (direction) {
            MergeDirection.HORIZONTAL -> sizes.sumOf { it.width.toLong() } + rawSpacing * (sizes.size - 1)
            MergeDirection.VERTICAL -> sizes.maxOf { it.width }.toLong()
            MergeDirection.GRID -> error("Grid layout is handled separately")
        }
        val rawHeight = when (direction) {
            MergeDirection.HORIZONTAL -> sizes.maxOf { it.height }.toLong()
            MergeDirection.VERTICAL -> sizes.sumOf { it.height.toLong() } + rawSpacing * (sizes.size - 1)
            MergeDirection.GRID -> error("Grid layout is handled separately")
        }
        val edgeScale = maxLongSide.toDouble() / maxOf(rawWidth, rawHeight)
        val pixelScale = sqrt(maxPixels.toDouble() / (rawWidth * rawHeight).toDouble())
        val scale = minOf(1.0, edgeScale, pixelScale)
        val width = (rawWidth * scale).toInt().coerceIn(1, maxLongSide)
        val height = (rawHeight * scale).toInt().coerceIn(1, maxLongSide)
        var rawCursor = 0L
        val placements = sizes.map { size ->
            if (direction == MergeDirection.HORIZONTAL) {
                val left = (rawCursor * scale).roundToInt().coerceIn(0, width - 1)
                val imageEnd = rawCursor + size.width
                rawCursor = imageEnd + rawSpacing
                val right = (imageEnd * scale).roundToInt().coerceIn(left + 1, width)
                val scaledHeight = (size.height * scale).roundToInt().coerceIn(1, height)
                ImagePlacement(left, (height - scaledHeight) / 2, right - left, scaledHeight)
            } else {
                val top = (rawCursor * scale).roundToInt().coerceIn(0, height - 1)
                val imageEnd = rawCursor + size.height
                rawCursor = imageEnd + rawSpacing
                val bottom = (imageEnd * scale).roundToInt().coerceIn(top + 1, height)
                val scaledWidth = (size.width * scale).roundToInt().coerceIn(1, width)
                ImagePlacement((width - scaledWidth) / 2, top, scaledWidth, bottom - top)
            }
        }
        return MergePlan(width, height, placements)
    }

    private fun calculateGrid(
        sizes: List<ImageSize>,
        rawSpacing: Long,
        maxLongSide: Int,
        maxPixels: Long,
    ): MergePlan {
        val columns = MergeGridLayout.columnCount(sizes.size)
        val rows = (sizes.size + columns - 1) / columns
        val rawCellSide = sizes.maxOf { maxOf(it.width, it.height) }.toLong()
        val rawWidth = rawCellSide * columns + rawSpacing * (columns - 1)
        val rawHeight = rawCellSide * rows + rawSpacing * (rows - 1)
        val edgeScale = maxLongSide.toDouble() / maxOf(rawWidth, rawHeight)
        val pixelScale = sqrt(maxPixels.toDouble() / (rawWidth * rawHeight).toDouble())
        val scale = minOf(1.0, edgeScale, pixelScale)
        val width = (rawWidth * scale).toInt().coerceIn(1, maxLongSide)
        val height = (rawHeight * scale).toInt().coerceIn(1, maxLongSide)

        val placements = sizes.mapIndexed { index, size ->
            val column = index % columns
            val row = index / columns
            val rawCellLeft = column * (rawCellSide + rawSpacing)
            val rawCellTop = row * (rawCellSide + rawSpacing)
            val cellLeft = (rawCellLeft * scale).roundToInt().coerceIn(0, width - 1)
            val cellTop = (rawCellTop * scale).roundToInt().coerceIn(0, height - 1)
            val cellRight = ((rawCellLeft + rawCellSide) * scale).roundToInt().coerceIn(cellLeft + 1, width)
            val cellBottom = ((rawCellTop + rawCellSide) * scale).roundToInt().coerceIn(cellTop + 1, height)
            val cellWidth = cellRight - cellLeft
            val cellHeight = cellBottom - cellTop
            val fitScale = minOf(
                cellWidth.toDouble() / size.width,
                cellHeight.toDouble() / size.height,
            )
            val imageWidth = (size.width * fitScale).roundToInt().coerceIn(1, cellWidth)
            val imageHeight = (size.height * fitScale).roundToInt().coerceIn(1, cellHeight)
            ImagePlacement(
                left = cellLeft + (cellWidth - imageWidth) / 2,
                top = cellTop + (cellHeight - imageHeight) / 2,
                width = imageWidth,
                height = imageHeight,
            )
        }
        return MergePlan(width, height, placements)
    }
}

class ImageMergeProcessor(
    context: Context,
    private val outputRepository: OutputRepository,
) {
    private val bitmapLoader = BitmapLoader(context)

    fun process(
        inputs: List<InputItem>,
        options: MergeOptions,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome = processWithMergedInputs(inputs, options, onProgress).outcome

    fun processWithMergedInputs(
        inputs: List<InputItem>,
        options: MergeOptions,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ImageMergeResult {
        requireText(inputs.isNotEmpty()) { text(R.string.backend_select_image) }
        val failures = mutableListOf<ProcessingFailure>()
        val steps = inputs.size + 1
        // Only headers are read here. Pixels are decoded one image at a time while drawing, so
        // memory is the output canvas plus a single input, however many images are merged.
        var remaining = inputs.mapNotNull { input ->
            onProgress(0, steps, text(R.string.backend_reading_image, input.displayName))
            runCatching { input to bitmapLoader.processingSize(input.uri) }
                .getOrElse { error ->
                    failures += ProcessingFailure(input.displayName, error.userMessage(R.string.backend_decode_image_failed))
                    null
                }
        }

        val spacingPx = options.spacingPx.coerceIn(MergeOptions.MIN_SPACING_PX, MergeOptions.MAX_SPACING_PX)
        while (true) {
            checkText(remaining.isNotEmpty()) { text(R.string.backend_images_unreadable) }
            val plan = MergeLayout.calculate(remaining.map { it.second }, options.direction, spacingPx = spacingPx)
            val output = createBitmap(plan.width, plan.height)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(options.fillColor)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val undecodable = mutableSetOf<Int>()
                remaining.zip(plan.placements).forEachIndexed { index, (entry, placement) ->
                    val (input, size) = entry
                    onProgress(index, steps, text(R.string.backend_reading_image, input.displayName))
                    // Decode directly at the size drawn. A grid may enlarge a small image, which
                    // stays the canvas's job: decoding never produces more pixels than the source.
                    val drawn = ImageSize(placement.width, placement.height)
                    val target = if (drawn.width <= size.width && drawn.height <= size.height) drawn else size
                    val bitmap = runCatching { bitmapLoader.decode(input.uri, target) }
                        .getOrElse { error ->
                            failures += ProcessingFailure(input.displayName, error.userMessage(R.string.backend_decode_image_failed))
                            undecodable += index
                            return@forEachIndexed
                        }
                    try {
                        canvas.drawBitmap(
                            bitmap,
                            null,
                            Rect(
                                placement.left,
                                placement.top,
                                placement.left + placement.width,
                                placement.top + placement.height,
                            ),
                            paint,
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (undecodable.isEmpty()) {
                    onProgress(inputs.size, steps, text(R.string.backend_merging_images))
                    val directory = outputRepository.createRunDirectory(Feature.IMAGE_MERGE)
                    val file = directory.resolve("merged.png")
                    FileOutputStream(file).use { stream ->
                        checkText(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) { text(R.string.backend_merge_write_failed) }
                    }
                    onProgress(steps, steps, text(R.string.backend_merge_complete))
                    return ImageMergeResult(
                        outcome = ProcessingOutcome(
                            outputs = listOf(OutputArtifact(file, "image/png", text(R.string.backend_merged_image))),
                            failures = failures,
                        ),
                        mergedInputs = remaining.map { it.first },
                    )
                }
                // A header can be intact while the pixel data is not. Such an image leaves the
                // layout, as an unreadable one always has, and the rest are laid out again.
                remaining = remaining.filterIndexed { index, _ -> index !in undecodable }
            } finally {
                output.recycle()
            }
        }
    }
}
