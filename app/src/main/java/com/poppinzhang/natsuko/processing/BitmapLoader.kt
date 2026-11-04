package com.poppinzhang.natsuko.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.roundToInt

private const val PROCESSING_MAX_EDGE = 4096

data class ImagePreview(val bitmap: Bitmap, val mergeSize: ImageSize)

class BitmapLoader(private val context: Context) {
    fun decode(uri: Uri, maxEdge: Int = PROCESSING_MAX_EDGE): Bitmap =
        decodeScaled(uri, maxEdge) {}

    fun decodePreview(uri: Uri, maxEdge: Int = 512): ImagePreview {
        lateinit var mergeSize: ImageSize
        val bitmap = decodeScaled(uri, maxEdge) { sourceSize ->
            // Retain the dimensions used by processing: thumbnail dimensions alone
            // would make differently sized originals appear equally large.
            mergeSize = scaledSize(sourceSize, PROCESSING_MAX_EDGE)
        }
        return ImagePreview(bitmap, mergeSize)
    }

    private fun decodeScaled(uri: Uri, maxEdge: Int, onSourceSize: (ImageSize) -> Unit): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            // ImageDecoder reports the oriented dimensions and applies the same
            // orientation to the decoded pixels, including EXIF-rotated images.
            val sourceSize = ImageSize(info.size.width, info.size.height)
            onSourceSize(sourceSize)
            val targetSize = scaledSize(sourceSize, maxEdge)
            if (targetSize != sourceSize) {
                decoder.setTargetSize(targetSize.width, targetSize.height)
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
}
