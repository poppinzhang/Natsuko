package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.checkText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import java.util.concurrent.CancellationException

class CropBlackBorderProcessor(
    private val context: Context,
    private val outputRepository: OutputRepository,
) {
    fun process(
        inputs: List<InputItem>,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome {
        requireText(inputs.isNotEmpty()) { text(R.string.backend_select_image) }
        val directory = outputRepository.createRunDirectory(Feature.CROP_BLACK_BORDER)
        val outputs = mutableListOf<OutputArtifact>()
        val failures = mutableListOf<ProcessingFailure>()

        inputs.forEachIndexed { index, input ->
            val output = directory.resolve("${index + 1}_${outputStem(input.displayName)}_cropped.png")
            var source: Bitmap? = null
            var cropped: Bitmap? = null
            var saved = false

            // The caller's progress callback also checks its coroutine context.
            // It is invoked during scans and on each stream write so cancellation
            // cannot be silently converted into an individual file failure.
            fun checkpoint(message: UiText = text(R.string.backend_cropping_image, input.displayName)) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("Processing cancelled")
                onProgress(index, inputs.size, message)
            }

            try {
                checkpoint()
                val bitmap = decode(input.uri)
                source = bitmap
                val bounds = BlackBorderCropper.findBounds(
                    bitmap.width,
                    bitmap.height,
                    readRow = { y, row -> bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) },
                    checkCancellation = { checkpoint() },
                )
                val changed = bounds.width != bitmap.width || bounds.height != bitmap.height
                checkpoint(text(R.string.backend_saving_image, input.displayName))
                val result = if (changed) {
                    Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height).also {
                        cropped = it
                        if (it !== bitmap) {
                            bitmap.recycle()
                            source = null
                        }
                    }
                } else {
                    bitmap
                }
                output.outputStream().use { stream ->
                    val checkedStream = object : java.io.FilterOutputStream(stream) {
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            checkpoint(text(R.string.backend_saving_image, input.displayName))
                            out.write(buffer, offset, length)
                        }

                        override fun write(value: Int) {
                            checkpoint(text(R.string.backend_saving_image, input.displayName))
                            out.write(value)
                        }
                    }
                    checkText(result.compress(Bitmap.CompressFormat.PNG, 100, checkedStream)) { text(R.string.backend_write_image_failed) }
                }
                checkpoint(text(R.string.backend_saved_image, input.displayName))
                outputs += OutputArtifact(
                    output,
                    "image/png",
                    UiText.Literal(input.displayName),
                    detail = if (changed) text(R.string.backend_border_removed) else text(R.string.backend_border_unchanged),
                )
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += ProcessingFailure(input.displayName, error.userMessage(R.string.backend_image_invalid))
            } finally {
                if (!saved) output.delete()
                cropped?.takeUnless { it.isRecycled }?.recycle()
                source?.takeUnless { it.isRecycled }?.recycle()
            }
            onProgress(index + 1, inputs.size, text(R.string.backend_images_processed, index + 1, inputs.size))
        }
        // Keep per-file diagnostics even when every input fails.
        return ProcessingOutcome(outputs, failures)
    }

    private fun outputStem(displayName: String): String {
        // At most 210 UTF-8 bytes leaves room under the 255-byte filename limit
        // for any Int batch index and "_cropped.png". Do not split surrogate pairs.
        val stem = OutputRepository.safeStem(displayName).take(70)
        return if (stem.last().isHighSurrogate()) stem.dropLast(1) else stem
    }

    private fun decode(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(context.contentResolver, uri),
    ) { decoder, info, _ ->
        // Retain original oriented dimensions: downsampling could blur a thin
        // border into real content. Source + crop require at most ~128 MB in
        // ARGB_8888; larger images fail individually instead of risking an OOM.
        checkText(info.size.width <= 8192 && info.size.height <= 8192 &&
            info.size.width.toLong() * info.size.height <= 16_000_000L) {
            text(R.string.backend_crop_image_too_large)
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        // LOW_RAM may decode RGB565 and erase near-black detail before detection.
        // ImageDecoder applies EXIF orientation and rejects corrupt input by default.
    }
}
