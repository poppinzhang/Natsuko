package com.poppinzhang.natsuko.processing

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.requireText
import com.poppinzhang.natsuko.model.text

/** Pixel bounds; right and bottom are exclusive. */
data class BlackBorderBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Conservative rectangular edge trimming, without object/background inference.
 * A border pixel must be fully opaque and have every 8-bit sRGB channel <= 8.
 * Every pixel in a removed outer row/column must qualify: even one brighter or
 * transparent pixel stops that edge. Entirely near-black images remain intact.
 * Uniform near-black content touching an edge is inherently indistinguishable
 * from a border; the low threshold deliberately favors retaining dark detail.
 *
 * The row reader fills the provided reusable buffer; additional memory is O(width).
 * checkCancellation runs before each row read so callers can interrupt large scans.
 */
object BlackBorderCropper {
    private const val MAX_BLACK_CHANNEL = 8

    fun findBounds(
        width: Int,
        height: Int,
        readRow: (y: Int, destination: IntArray) -> Unit,
        checkCancellation: () -> Unit = {},
    ): BlackBorderBounds {
        requireText(width > 0 && height > 0) { text(R.string.backend_image_size_invalid) }
        val full = BlackBorderBounds(0, 0, width, height)
        val row = IntArray(width)

        fun read(y: Int) {
            checkCancellation()
            readRow(y, row)
        }

        var top = 0
        while (top < height) {
            read(top)
            if (row.any { !isBorderPixel(it) }) break
            top++
        }
        if (top == height) return full

        var bottom = height
        while (bottom > top + 1) {
            read(bottom - 1)
            if (row.any { !isBorderPixel(it) }) break
            bottom--
        }

        // Keep the outermost content pixel from every remaining row. Interior
        // black areas cannot affect the result or split it into separate regions.
        var left = width
        var right = 0
        for (y in top until bottom) {
            read(y)
            var x = 0
            while (x < left && isBorderPixel(row[x])) x++
            left = x
            x = width
            while (x > right && isBorderPixel(row[x - 1])) x--
            right = x
            if (left == 0 && right == width) break
        }
        return BlackBorderBounds(left, top, right, bottom)
    }

    private fun isBorderPixel(argb: Int): Boolean =
        argb ushr 24 == 255 &&
            (argb ushr 16 and 255) <= MAX_BLACK_CHANNEL &&
            (argb ushr 8 and 255) <= MAX_BLACK_CHANNEL &&
            (argb and 255) <= MAX_BLACK_CHANNEL
}
