package com.poppinzhang.natsuko.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.roundToInt

private const val PROCESSING_MAX_EDGE = 4096

data class ImagePreview(val bitmap: Bitmap, val mergeSize: ImageSize)

class BitmapLoader(private val context: Context) {
    /** Full-colour pixels for an output file; a longer edge is reduced to [maxEdge]. */
    fun decode(uri: Uri, maxEdge: Int = PROCESSING_MAX_EDGE): Bitmap =
        decodeScaled(uri, fullColor = true) { scaledSize(it, maxEdge) }

    /**
     * Full-colour pixels at exactly [target], for an image that was laid out from its
     * [processingSize] and is shown smaller: memory and time follow the pixels actually used.
     */
    fun decode(uri: Uri, target: ImageSize): Bitmap = decodeScaled(uri, fullColor = true) { target }

    /** The size [decode] produces, read from the header without decoding any pixels. */
    fun processingSize(uri: Uri): ImageSize {
        var size: ImageSize? = null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { _, info, _ ->
                size = scaledSize(ImageSize(info.size.width, info.size.height), PROCESSING_MAX_EDGE)
                // Leaving the header callback this way stops ImageDecoder before the first pixel.
                throw HeaderOnly()
            }.recycle()
        } catch (_: HeaderOnly) {
            // The header has been read; that is all this call needs.
        }
        return checkNotNull(size) { "Image header was not decoded" }
    }

    fun decodePreview(uri: Uri, maxEdge: Int = 512): ImagePreview {
        lateinit var mergeSize: ImageSize
        val bitmap = decodeScaled(uri, fullColor = false) { sourceSize ->
            // Retain the dimensions used by processing: thumbnail dimensions alone
            // would make differently sized originals appear equally large.
            mergeSize = scaledSize(sourceSize, PROCESSING_MAX_EDGE)
            scaledSize(sourceSize, maxEdge)
        }
        return ImagePreview(bitmap, mergeSize)
    }

    private fun decodeScaled(uri: Uri, fullColor: Boolean, targetSize: (ImageSize) -> ImageSize): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // LOW_RAM decodes opaque images as RGB_565, which bands gradients. That is acceptable
            // for a thumbnail, never for pixels that reach a merged image or a PDF page.
            decoder.memorySizePolicy =
                if (fullColor) ImageDecoder.MEMORY_POLICY_DEFAULT else ImageDecoder.MEMORY_POLICY_LOW_RAM
            // ImageDecoder reports the oriented dimensions and applies the same
            // orientation to the decoded pixels, including EXIF-rotated images.
            val sourceSize = ImageSize(info.size.width, info.size.height)
            val target = targetSize(sourceSize)
            if (target != sourceSize) {
                decoder.setTargetSize(target.width, target.height)
            }
        }
    }

    private fun scaledSize(size: ImageSize, maxEdge: Int): ImageSize {
        val largest = maxOf(size.width, size.height)
        if (largest <= maxEdge) return size
        val scale = maxEdge.toFloat() / largest
        return ImageSize(
            (size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** Control flow only: no stack trace is recorded. */
    private class HeaderOnly : RuntimeException(null, null, false, false)
}
