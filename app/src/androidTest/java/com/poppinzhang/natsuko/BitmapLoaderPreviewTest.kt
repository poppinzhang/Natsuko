package com.poppinzhang.natsuko

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.processing.BitmapLoader
import com.poppinzhang.natsuko.processing.ImageSize
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapLoaderPreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loader = BitmapLoader(context)
    private val fixtureDirectory = File(context.filesDir, "bitmap-loader-preview-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        fixtureDirectory.deleteRecursively()
    }

    @Test
    fun thumbnailsRetainDifferentOriginalSizesForMergeLayout() {
        val large = loader.decodePreview(fixtureUri(createImage("large.png", 1600, 800)))
        val small = loader.decodePreview(fixtureUri(createImage("small.png", 800, 400)))
        try {
            assertEquals(ImageSize(512, 256), large.bitmap.size())
            assertEquals(large.bitmap.size(), small.bitmap.size())
            assertEquals(ImageSize(1600, 800), large.mergeSize)
            assertEquals(ImageSize(800, 400), small.mergeSize)
        } finally {
            large.bitmap.recycle()
            small.bitmap.recycle()
        }
    }

    @Test
    fun portraitPreviewFitsLongestEdgeAndSmallImagesAreNotUpscaled() {
        val portrait = loader.decodePreview(fixtureUri(createImage("portrait.png", 400, 1600)))
        val small = loader.decodePreview(fixtureUri(createImage("tiny.png", 80, 40)))
        try {
            assertEquals(ImageSize(128, 512), portrait.bitmap.size())
            assertEquals(ImageSize(400, 1600), portrait.mergeSize)
            assertEquals(ImageSize(80, 40), small.bitmap.size())
            assertEquals(small.bitmap.size(), small.mergeSize)
        } finally {
            portrait.bitmap.recycle()
            small.bitmap.recycle()
        }
    }

    @Test
    fun oversizedImageUsesTheSameMergeDimensionsAsProcessing() {
        val uri = fixtureUri(createImage("oversized.png", 8000, 1200))
        val preview = loader.decodePreview(uri)
        val processed = loader.decode(uri)
        try {
            assertEquals(ImageSize(512, 77), preview.bitmap.size())
            assertEquals(ImageSize(4096, 614), preview.mergeSize)
            assertEquals(processed.size(), preview.mergeSize)
        } finally {
            preview.bitmap.recycle()
            processed.recycle()
        }
    }

    @Test
    fun previewKeepsAllFourImageCorners() {
        val file = createImage("corners.png", 1024, 512) { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint()
            listOf(
                Triple(0f, 0f, Color.RED),
                Triple(512f, 0f, Color.GREEN),
                Triple(0f, 256f, Color.BLUE),
                Triple(512f, 256f, Color.YELLOW),
            ).forEach { (left, top, color) ->
                paint.color = color
                canvas.drawRect(left, top, left + 512f, top + 256f, paint)
            }
        }
        val preview = loader.decodePreview(fixtureUri(file))
        try {
            assertEquals(ImageSize(512, 256), preview.bitmap.size())
            assertEquals(Color.RED, preview.bitmap.getPixel(0, 0))
            assertEquals(Color.GREEN, preview.bitmap.getPixel(511, 0))
            assertEquals(Color.BLUE, preview.bitmap.getPixel(0, 255))
            assertEquals(Color.YELLOW, preview.bitmap.getPixel(511, 255))
        } finally {
            preview.bitmap.recycle()
        }
    }

    @Test
    fun exifRotationMatchesPreviewAndProcessingDimensions() {
        val file = createImage("rotated.jpg", 1000, 400, Bitmap.CompressFormat.JPEG)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val uri = fixtureUri(file)
        val preview = loader.decodePreview(uri)
        val processed = loader.decode(uri)
        try {
            assertEquals(ImageSize(205, 512), preview.bitmap.size())
            assertEquals(ImageSize(400, 1000), preview.mergeSize)
            assertEquals(processed.size(), preview.mergeSize)
        } finally {
            preview.bitmap.recycle()
            processed.recycle()
        }
    }

    @Test
    fun invalidImageFailsInsteadOfProducingAPreview() {
        val file = File(fixtureDirectory, "invalid.png").apply { writeText("not an image") }
        assertTrue(runCatching { loader.decodePreview(fixtureUri(file)) }.isFailure)
    }

    private fun createImage(
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        draw: (Bitmap) -> Unit = { it.eraseColor(Color.RED) },
    ): File {
        val file = File(fixtureDirectory, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            draw(bitmap)
            file.outputStream().use { stream ->
                check(bitmap.compress(format, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun fixtureUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun Bitmap.size() = ImageSize(width, height)
}
