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

object MergeLayout {
    fun calculate(
        sizes: List<ImageSize>,
        direction: MergeDirection,
        maxLongSide: Int = 8192,
        maxPixels: Long = 40_000_000,
    ): MergePlan {
        require(sizes.isNotEmpty())
        require(sizes.all { it.width > 0 && it.height > 0 })

        if (direction == MergeDirection.GRID) {
            return calculateGrid(sizes, maxLongSide, maxPixels)
        }

        val rawWidth = when (direction) {
            MergeDirection.HORIZONTAL -> sizes.sumOf { it.width.toLong() }
            MergeDirection.VERTICAL -> sizes.maxOf { it.width }.toLong()
            MergeDirection.GRID -> error("Grid layout is handled separately")
        }
        val rawHeight = when (direction) {
            MergeDirection.HORIZONTAL -> sizes.maxOf { it.height }.toLong()
            MergeDirection.VERTICAL -> sizes.sumOf { it.height.toLong() }
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
                val left = (rawCursor * scale).roundToInt()
                rawCursor += size.width
                val right = (rawCursor * scale).roundToInt().coerceAtMost(width)
                val scaledHeight = (size.height * scale).roundToInt().coerceIn(1, height)
                ImagePlacement(left, (height - scaledHeight) / 2, (right - left).coerceAtLeast(1), scaledHeight)
            } else {
                val top = (rawCursor * scale).roundToInt()
                rawCursor += size.height
                val bottom = (rawCursor * scale).roundToInt().coerceAtMost(height)
                val scaledWidth = (size.width * scale).roundToInt().coerceIn(1, width)
                ImagePlacement((width - scaledWidth) / 2, top, scaledWidth, (bottom - top).coerceAtLeast(1))
            }
        }
        return MergePlan(width, height, placements)
    }

    private fun calculateGrid(
        sizes: List<ImageSize>,
        maxLongSide: Int,
        maxPixels: Long,
    ): MergePlan {
        val columns = MergeGridLayout.columnCount(sizes.size)
        val rows = (sizes.size + columns - 1) / columns
        val rawCellSide = sizes.maxOf { maxOf(it.width, it.height) }.toLong()
        val rawWidth = rawCellSide * columns
        val rawHeight = rawCellSide * rows
        val edgeScale = maxLongSide.toDouble() / maxOf(rawWidth, rawHeight)
        val pixelScale = sqrt(maxPixels.toDouble() / (rawWidth * rawHeight).toDouble())
        val scale = minOf(1.0, edgeScale, pixelScale)
        val width = (rawWidth * scale).toInt().coerceIn(1, maxLongSide)
        val height = (rawHeight * scale).toInt().coerceIn(1, maxLongSide)

        val placements = sizes.mapIndexed { index, size ->
            val column = index % columns
            val row = index / columns
            val cellLeft = (column * rawCellSide * scale).roundToInt()
            val cellTop = (row * rawCellSide * scale).roundToInt()
            val cellRight = ((column + 1) * rawCellSide * scale).roundToInt().coerceAtMost(width)
            val cellBottom = ((row + 1) * rawCellSide * scale).roundToInt().coerceAtMost(height)
            val cellWidth = (cellRight - cellLeft).coerceAtLeast(1)
            val cellHeight = (cellBottom - cellTop).coerceAtLeast(1)
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
    ): ProcessingOutcome {
        requireText(inputs.isNotEmpty()) { text(R.string.backend_select_image) }
        val failures = mutableListOf<ProcessingFailure>()
        val loaded = inputs.mapIndexedNotNull { index, input ->
            onProgress(index, inputs.size + 1, text(R.string.backend_reading_image, input.displayName))
            runCatching { input to bitmapLoader.decode(input.uri) }
                .getOrElse { error ->
                    failures += ProcessingFailure(input.displayName, error.userMessage(R.string.backend_decode_image_failed))
                    null
                }
        }
        checkText(loaded.isNotEmpty()) { text(R.string.backend_images_unreadable) }

        try {
            val plan = MergeLayout.calculate(
                loaded.map { ImageSize(it.second.width, it.second.height) },
                options.direction,
            )
            onProgress(inputs.size, inputs.size + 1, text(R.string.backend_merging_images))
            val output = createBitmap(plan.width, plan.height)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(options.fillColor)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                loaded.zip(plan.placements).forEach { (entry, placement) ->
                    val bitmap = entry.second
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
                }
                val directory = outputRepository.createRunDirectory(Feature.IMAGE_MERGE)
                val file = directory.resolve("merged.png")
                FileOutputStream(file).use { stream ->
                    checkText(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) { text(R.string.backend_merge_write_failed) }
                }
                onProgress(inputs.size + 1, inputs.size + 1, text(R.string.backend_merge_complete))
                return ProcessingOutcome(
                    outputs = listOf(OutputArtifact(file, "image/png", text(R.string.backend_merged_image))),
                    failures = failures,
                )
            } finally {
                output.recycle()
            }
        } finally {
            loaded.forEach { (_, bitmap) -> bitmap.recycle() }
        }
    }
}
