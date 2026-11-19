package com.poppinzhang.natsuko.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.system.ErrnoException
import android.system.OsConstants
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.LocalizedIOException
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.model.SplitOptions
import com.poppinzhang.natsuko.model.SplitSource
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.text
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

class SplitLoad(
    val source: SplitSource,
    val preview: Bitmap,
)

class ImageSplitProcessor(
    private val context: Context,
    private val outputRepository: OutputRepository,
) {
    /** Reads the oriented image header and decodes a display-sized sRGB preview. */
    fun load(input: InputItem, checkpoint: () -> Unit): SplitLoad {
        var width = 0
        var height = 0
        var mimeType: String? = null
        val preview = decodeOrFail {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, input.uri)) { decoder, info, _ ->
                requireText(!info.isAnimated) { text(R.string.backend_split_animated) }
                width = info.size.width
                height = info.size.height
                requireText(width > 0 && height > 0) { text(R.string.backend_image_size_invalid) }
                mimeType = info.mimeType
                configure(decoder)
                val target = fitInside(width, height, PREVIEW_MAX_EDGE)
                if (target.width != width || target.height != height) {
                    decoder.setTargetSize(target.width, target.height)
                }
            }
        }
        try {
            checkpoint()
            if (preview.hasGainmap()) preview.gainmap = null
            return SplitLoad(
                source = SplitSource(width, height, mimeType, preview.hasAlpha()),
                preview = preview,
            )
        } catch (error: Throwable) {
            preview.recycle()
            throw error
        }
    }

    /** Slices the original image at full resolution into lossless PNG files matching [plan]. */
    fun process(
        input: InputItem,
        source: SplitSource,
        plan: SplitPlan,
        checkpoint: () -> Unit,
        onProgress: (Int, Int, UiText) -> Unit,
    ): ProcessingOutcome {
        requireText(plan.sourceWidth == source.width && plan.sourceHeight == source.height) {
            text(R.string.backend_split_source_changed)
        }
        requireText(plan.slices.size in SplitOptions.MIN_PARTS..SplitOptions.MAX_PARTS) {
            text(R.string.backend_split_invalid_parts)
        }
        plan.slices.forEach { slice ->
            val slicePixels = slice.width.toLong() * slice.height
            requireText(slicePixels <= MAX_SLICE_PIXELS) {
                text(R.string.backend_split_too_large, slicePixels / 1_000_000.0, MAX_SLICE_PIXELS / 1_000_000.0)
            }
        }

        val total = plan.slices.size
        val directory = outputRepository.createRunDirectory(Feature.IMAGE_SPLIT)
        val stem = outputStem(input.displayName)
        val createdFiles = mutableListOf<File>()
        val artifacts = ArrayList<OutputArtifact>(total)
        var complete = false
        try {
            checkpoint()
            val sourcePixels = source.width.toLong() * source.height
            if (sourcePixels <= FULL_DECODE_MAX_PIXELS) {
                onProgress(0, total, text(R.string.backend_reading_image, input.displayName))
                val fullBitmap = decodeFull(input, source)
                try {
                    if (fullBitmap.hasGainmap()) fullBitmap.gainmap = null
                    plan.slices.forEachIndexed { index, slice ->
                        checkpoint()
                        onProgress(index, total, text(R.string.backend_split_generating, index + 1, total))
                        val sliceBitmap = Bitmap.createBitmap(
                            fullBitmap,
                            slice.left,
                            slice.top,
                            slice.width,
                            slice.height,
                        )
                        try {
                            val file = sliceFile(directory, stem, index)
                            createdFiles += file
                            writePng(sliceBitmap, file, checkpoint)
                            artifacts += artifactFor(file, index, total, slice)
                        } finally {
                            if (sliceBitmap !== fullBitmap) sliceBitmap.recycle()
                        }
                    }
                } finally {
                    fullBitmap.recycle()
                }
            } else {
                // Very large images are decoded one cropped slice at a time so peak memory stays bounded.
                plan.slices.forEachIndexed { index, slice ->
                    checkpoint()
                    onProgress(index, total, text(R.string.backend_split_generating, index + 1, total))
                    val sliceBitmap = decodeRegion(input, source, slice)
                    try {
                        if (sliceBitmap.hasGainmap()) sliceBitmap.gainmap = null
                        val file = sliceFile(directory, stem, index)
                        createdFiles += file
                        writePng(sliceBitmap, file, checkpoint)
                        artifacts += artifactFor(file, index, total, slice)
                    } finally {
                        sliceBitmap.recycle()
                    }
                }
            }
            checkpoint()
            onProgress(total, total, text(R.string.backend_split_complete))
            complete = true
            return ProcessingOutcome(outputs = artifacts)
        } finally {
            if (!complete) {
                createdFiles.forEach { it.delete() }
                directory.delete()
            }
        }
    }

    private fun decodeFull(input: InputItem, source: SplitSource): Bitmap = decodeOrFail {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, input.uri)) { decoder, info, _ ->
            requireText(!info.isAnimated) { text(R.string.backend_split_animated) }
            checkText(info.size.width == source.width && info.size.height == source.height) {
                text(R.string.backend_split_source_changed)
            }
            configure(decoder)
        }
        checkText(decoded.width == source.width && decoded.height == source.height) {
            decoded.recycle()
            text(R.string.backend_decode_image_failed)
        }
        decoded
    }

    private fun decodeRegion(input: InputItem, source: SplitSource, slice: SplitSlice): Bitmap = decodeOrFail {
        val region = Rect(slice.left, slice.top, slice.right, slice.bottom)
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, input.uri)) { decoder, info, _ ->
            requireText(!info.isAnimated) { text(R.string.backend_split_animated) }
            checkText(info.size.width == source.width && info.size.height == source.height) {
                text(R.string.backend_split_source_changed)
            }
            configure(decoder)
            decoder.crop = region
        }
        checkText(decoded.width == slice.width && decoded.height == slice.height) {
            decoded.recycle()
            text(R.string.backend_decode_image_failed)
        }
        decoded
    }

    private fun writePng(bitmap: Bitmap, file: File, checkpoint: () -> Unit) {
        var writeFailure: IOException? = null
        val success = file.outputStream().use { stream ->
            val checked = object : FilterOutputStream(stream) {
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    checkpoint()
                    try {
                        out.write(buffer, offset, length)
                    } catch (error: IOException) {
                        writeFailure = error
                        throw error
                    }
                }

                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, checked)
        }
        checkpoint()
        writeFailure?.let { throw storageFailure(it) }
        checkText(success) { text(R.string.backend_write_image_failed) }
    }

    private fun sliceFile(directory: File, stem: String, index: Int): File =
        File(directory, "${stem}_part_${String.format(Locale.US, "%02d", index + 1)}.png")

    private fun artifactFor(file: File, index: Int, total: Int, slice: SplitSlice): OutputArtifact =
        OutputArtifact(
            file = file,
            mimeType = "image/png",
            label = UiText.Literal(file.name),
            detail = text(R.string.split_output_detail, index + 1, total, slice.width, slice.height),
        )

    private fun configure(decoder: ImageDecoder) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    private inline fun decodeOrFail(decode: () -> Bitmap): Bitmap = try {
        decode()
    } catch (error: Exception) {
        throw when (error) {
            is LocalizedFailure -> error
            is ImageDecoder.DecodeException -> LocalizedIllegalStateException(text(R.string.backend_image_invalid), error)
            is FileNotFoundException, is SecurityException, is IOException ->
                LocalizedIOException(text(R.string.backend_split_source_unreadable), error)
            else -> error
        }
    } catch (_: OutOfMemoryError) {
        throw LocalizedIllegalStateException(text(R.string.backend_split_out_of_memory))
    }

    private fun storageFailure(error: IOException): Exception {
        val full = generateSequence<Throwable>(error) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
        return LocalizedIOException(text(if (full) R.string.backend_storage_full else R.string.backend_write_image_failed), error)
    }

    private fun outputStem(displayName: String): String {
        val stem = OutputRepository.safeStem(displayName).take(70)
        return if (stem.last().isHighSurrogate()) stem.dropLast(1) else stem
    }

    companion object {
        const val PREVIEW_MAX_EDGE = 2560
        const val FULL_DECODE_MAX_PIXELS = 24_000_000L
        const val MAX_SLICE_PIXELS = 52_000_000L

        private fun fitInside(width: Int, height: Int, maxEdge: Int): ImageSize {
            val largest = maxOf(width, height)
            if (largest <= maxEdge) return ImageSize(width, height)
            val scale = maxEdge.toDouble() / largest
            return ImageSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
}
