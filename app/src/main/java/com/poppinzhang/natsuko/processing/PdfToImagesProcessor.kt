package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.checkNotNullText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.core.graphics.createBitmap
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.ImageOutputFormat
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.PdfToImagesOptions
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import java.io.FileOutputStream
import kotlin.math.roundToInt

class PdfToImagesProcessor(
    private val context: Context,
    private val outputRepository: OutputRepository,
) {
    fun process(
        inputs: List<InputItem>,
        options: PdfToImagesOptions,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome {
        requireText(inputs.isNotEmpty()) { text(R.string.backend_select_pdf) }
        val directory = outputRepository.createRunDirectory(Feature.PDF_TO_IMAGES)
        val outputs = mutableListOf<OutputArtifact>()
        val failures = mutableListOf<ProcessingFailure>()

        inputs.forEachIndexed { documentIndex, input ->
            runCatching {
                val descriptor = checkNotNullText(context.contentResolver.openFileDescriptor(input.uri, "r")) {
                    text(R.string.backend_file_open_failed)
                }
                descriptor.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        checkText(renderer.pageCount > 0) { text(R.string.backend_pdf_no_pages) }
                        for (pageIndex in 0 until renderer.pageCount) {
                            onProgress(documentIndex, inputs.size, text(R.string.backend_pdf_page_progress, input.displayName, pageIndex + 1, renderer.pageCount))
                            renderer.openPage(pageIndex).use { page ->
                                val requestedWidth = (page.width * options.scale).roundToInt().coerceAtLeast(1)
                                val requestedHeight = (page.height * options.scale).roundToInt().coerceAtLeast(1)
                                val edgeScale = minOf(1f, 4096f / maxOf(requestedWidth, requestedHeight))
                                val width = (requestedWidth * edgeScale).roundToInt().coerceAtLeast(1)
                                val height = (requestedHeight * edgeScale).roundToInt().coerceAtLeast(1)
                                val bitmap = createBitmap(width, height)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val stem = OutputRepository.safeStem(input.displayName)
                                    val file = directory.resolve("${documentIndex + 1}_${stem}_page_${pageIndex + 1}.${options.format.extension}")
                                    FileOutputStream(file).use { stream ->
                                        val success = when (options.format) {
                                            ImageOutputFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                            ImageOutputFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, options.jpegQuality, stream)
                                        }
                                        checkText(success) { text(R.string.backend_write_image_failed) }
                                    }
                                    outputs += OutputArtifact(
                                        file,
                                        options.format.mimeType,
                                        text(R.string.backend_pdf_page_label, input.displayName, pageIndex + 1),
                                    )
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                }
                onProgress(documentIndex + 1, inputs.size, text(R.string.backend_pdfs_processed, documentIndex + 1, inputs.size))
            }.onFailure { error ->
                failures += ProcessingFailure(input.displayName, error.userMessage(R.string.backend_pdf_invalid))
            }
        }
        onProgress(inputs.size, inputs.size, text(R.string.backend_pdf_to_images_complete))
        checkText(outputs.isNotEmpty()) { text(R.string.backend_pdfs_failed) }
        return ProcessingOutcome(outputs, failures)
    }
}
