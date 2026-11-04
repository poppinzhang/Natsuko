package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.quantityText
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.checkText
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.ImagesToPdfOptions
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.PdfOrientation
import com.poppinzhang.natsuko.model.PdfOutputMode
import com.poppinzhang.natsuko.model.PdfPagePreset
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object PdfPageLayout {
    private val a4Portrait = ImageSize(595, 842)

    fun pageSize(image: ImageSize, options: ImagesToPdfOptions): ImageSize {
        val natural = if (options.pagePreset == PdfPagePreset.A4) {
            a4Portrait
        } else {
            val scale = minOf(1f, 1440f / maxOf(image.width, image.height))
            ImageSize(
                (image.width * scale).roundToInt().coerceAtLeast(72),
                (image.height * scale).roundToInt().coerceAtLeast(72),
            )
        }
        val landscape = when (options.orientation) {
            PdfOrientation.AUTO -> image.width > image.height
            PdfOrientation.PORTRAIT -> false
            PdfOrientation.LANDSCAPE -> true
        }
        val short = minOf(natural.width, natural.height)
        val long = maxOf(natural.width, natural.height)
        return if (landscape) ImageSize(long, short) else ImageSize(short, long)
    }

    fun contentRect(page: ImageSize, image: ImageSize, margin: Int): ImagePlacement {
        val safeMargin = margin.coerceIn(0, minOf(page.width, page.height) / 3)
        val availableWidth = (page.width - safeMargin * 2).coerceAtLeast(1)
        val availableHeight = (page.height - safeMargin * 2).coerceAtLeast(1)
        val scale = minOf(
            availableWidth.toFloat() / image.width,
            availableHeight.toFloat() / image.height,
        )
        val width = (image.width * scale).roundToInt().coerceAtLeast(1)
        val height = (image.height * scale).roundToInt().coerceAtLeast(1)
        val left = (page.width - width) / 2
        val top = (page.height - height) / 2
        return ImagePlacement(left, top, width, height)
    }
}

class ImagesToPdfProcessor(
    context: Context,
    private val outputRepository: OutputRepository,
) {
    private val bitmapLoader = BitmapLoader(context)

    fun process(
        inputs: List<InputItem>,
        options: ImagesToPdfOptions,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome {
        requireText(inputs.isNotEmpty()) { text(R.string.backend_select_image) }
        val directory = outputRepository.createRunDirectory(Feature.IMAGES_TO_PDF, documentOutput = true)
        val failures = mutableListOf<ProcessingFailure>()
        val outputs = mutableListOf<OutputArtifact>()

        if (options.outputMode == PdfOutputMode.COMBINED) {
            val output = directory.resolve("images.pdf")
            val pageCount = writePdf(inputs, output, options, failures, onProgress)
            if (pageCount > 0) outputs += OutputArtifact(output, "application/pdf", quantityText(R.plurals.backend_combined_pdf_label, pageCount, pageCount))
        } else {
            inputs.forEachIndexed { index, input ->
                val output = directory.resolve("${index + 1}_${OutputRepository.safeStem(input.displayName)}.pdf")
                val pageCount = writePdf(
                    listOf(input),
                    output,
                    options,
                    failures,
                ) { _, _, message -> onProgress(index, inputs.size, message) }
                if (pageCount > 0) outputs += OutputArtifact(output, "application/pdf", text(R.string.backend_single_pdf_label, input.displayName))
                onProgress(index + 1, inputs.size, text(R.string.backend_items_processed, index + 1, inputs.size))
            }
        }
        checkText(outputs.isNotEmpty()) { text(R.string.backend_images_conversion_failed) }
        return ProcessingOutcome(outputs, failures)
    }

    private fun writePdf(
        inputs: List<InputItem>,
        output: File,
        options: ImagesToPdfOptions,
        failures: MutableList<ProcessingFailure>,
        onProgress: (Int, Int, UiText) -> Unit,
    ): Int {
        val document = PdfDocument()
        var pageNumber = 0
        try {
            inputs.forEachIndexed { index, input ->
                onProgress(index, inputs.size, text(R.string.backend_converting_image, input.displayName))
                val bitmap = runCatching { bitmapLoader.decode(input.uri) }
                    .getOrElse { error ->
                        failures += ProcessingFailure(input.displayName, error.userMessage(R.string.backend_decode_image_failed))
                        return@forEachIndexed
                    }
                try {
                    val imageSize = ImageSize(bitmap.width, bitmap.height)
                    val pageSize = PdfPageLayout.pageSize(imageSize, options)
                    pageNumber += 1
                    val pageInfo = PdfDocument.PageInfo.Builder(pageSize.width, pageSize.height, pageNumber).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    val placement = PdfPageLayout.contentRect(pageSize, imageSize, options.marginPoints)
                    page.canvas.drawBitmap(
                        bitmap,
                        null,
                        android.graphics.Rect(
                            placement.left,
                            placement.top,
                            placement.left + placement.width,
                            placement.top + placement.height,
                        ),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                    document.finishPage(page)
                } catch (error: Throwable) {
                    failures += ProcessingFailure(input.displayName, error.userMessage(R.string.backend_pdf_page_failed))
                } finally {
                    bitmap.recycle()
                }
                onProgress(index + 1, inputs.size, text(R.string.backend_items_processed, index + 1, inputs.size))
            }
            if (pageNumber > 0) FileOutputStream(output).use(document::writeTo)
        } finally {
            document.close()
        }
        return pageNumber
    }
}
