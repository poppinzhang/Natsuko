package com.poppinzhang.natsuko

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.ImagesToPdfOptions
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.model.MergeOptions
import com.poppinzhang.natsuko.model.PdfPagePreset
import com.poppinzhang.natsuko.model.PdfToImagesOptions
import com.poppinzhang.natsuko.processing.ImagesToPdfProcessor
import com.poppinzhang.natsuko.processing.ImageMergeProcessor
import com.poppinzhang.natsuko.processing.PdfToImagesProcessor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentProcessorsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputs = OutputRepository(context)

    @Test
    fun convertsSingleAndMultipleImagesToPdfPages() {
        val images = listOf(
            imageInput("processor-one.png", Color.RED),
            imageInput("processor-two.png", Color.BLUE),
        )
        val result = ImagesToPdfProcessor(context, outputs).process(images, ImagesToPdfOptions()) { _, _, _ -> }

        assertEquals(1, result.outputs.size)
        ParcelFileDescriptor.open(result.outputs.single().file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> assertEquals(2, renderer.pageCount) }
        }
    }

    @Test
    fun convertsPdfPagesToImages() {
        val pdf = createPdf("processor-input.pdf", pages = 2)
        val input = InputItem(fixtureUri(pdf), pdf.name, "application/pdf")
        val result = PdfToImagesProcessor(context, outputs).process(listOf(input), PdfToImagesOptions()) { _, _, _ -> }

        assertEquals(2, result.outputs.size)
        assertTrue(result.outputs.all { it.file.length() > 0 && BitmapFactoryCompat.decodes(it.file) })
    }

    @Test
    fun mergesFourImagesIntoOrderedTwoByTwoGrid() {
        val inputs = listOf(
            imageInput("grid-red.png", Color.RED),
            imageInput("grid-blue.png", Color.BLUE),
            imageInput("grid-green.png", Color.GREEN),
            imageInput("grid-yellow.png", Color.YELLOW),
        )
        val result = ImageMergeProcessor(context, outputs).process(
            inputs,
            MergeOptions(direction = MergeDirection.GRID),
        ) { _, _, _ -> }

        val bitmap = BitmapFactory.decodeFile(result.outputs.single().file.absolutePath)
        try {
            assertEquals(32, bitmap.width)
            assertEquals(32, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(8, 8))
            assertEquals(Color.BLUE, bitmap.getPixel(24, 8))
            assertEquals(Color.GREEN, bitmap.getPixel(8, 24))
            assertEquals(Color.YELLOW, bitmap.getPixel(24, 24))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun mergedImageKeepsEveryShadeOfOpaqueInputs() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            val gradient = gradientInput("merge-gradient-$format", format, height = 64)
            val result = ImageMergeProcessor(context, outputs).process(listOf(gradient, gradient), MergeOptions()) { _, _, _ -> }

            val merged = BitmapFactory.decodeFile(result.outputs.single().file.absolutePath)
            try {
                // A 16-bit decode would leave at most 64 of the 256 grey levels.
                val shades = shades(merged, row = 8)
                assertTrue("$format input kept $shades shades", shades >= 250)
            } finally {
                merged.recycle()
            }
        }
    }

    @Test
    fun pdfPageKeepsEveryShadeOfAnOpaqueImage() {
        val gradient = gradientInput("pdf-gradient", Bitmap.CompressFormat.PNG, height = 128)
        // One point per pixel and no margin: the page shows the image at its own resolution.
        val options = ImagesToPdfOptions(pagePreset = PdfPagePreset.FIT_IMAGE, marginPoints = 0)
        val result = ImagesToPdfProcessor(context, outputs).process(listOf(gradient), options) { _, _, _ -> }

        ParcelFileDescriptor.open(result.outputs.single().file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    assertEquals(1024, page.width)
                    assertEquals(128, page.height)
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        rendered.eraseColor(Color.WHITE)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val shades = shades(rendered, row = 64)
                        assertTrue("The PDF page kept $shades shades", shades >= 200)
                    } finally {
                        rendered.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun pagesOfACombinedPdfShareOnePixelBudget() {
        val large = largeImage("pdf-large.png")
        val inputs = List(8) { InputItem(large, "page-$it.png", "image/png") }
        val options = ImagesToPdfOptions(pagePreset = PdfPagePreset.FIT_IMAGE, marginPoints = 0)

        var pdf: File? = null
        val growth = peakNativeHeapGrowth {
            // Half a megapixel per page instead of the four megapixels of each input.
            pdf = ImagesToPdfProcessor(context, outputs, documentPixelBudget = 4_000_000)
                .process(inputs, options) { _, _, _ -> }.outputs.single().file
        }

        // The document keeps every page's pixels until it is written: eight full-size pages
        // would hold 128 MiB, their shares of the budget about 16 MiB.
        assertTrue("Writing the PDF grew the native heap by ${growth shr 20} MiB", growth < 64L shl 20)
        ParcelFileDescriptor.open(checkNotNull(pdf), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(8, renderer.pageCount)
                // Pages are laid out from the image's own size; only its pixels were reduced.
                renderer.openPage(0).use { page ->
                    assertEquals(1440, page.width)
                    assertEquals(1440, page.height)
                }
            }
        }
    }

    @Test
    fun mergingManyLargeImagesHoldsOneDecodedInputAtATime() {
        val large = largeImage("merge-large.png")
        val inputs = List(30) { InputItem(large, "large-$it.png", "image/png") }

        var merged: File? = null
        val growth = peakNativeHeapGrowth {
            merged = ImageMergeProcessor(context, outputs).process(inputs, MergeOptions()) { _, _, _ -> }.outputs.single().file
        }

        // Thirty 4-megapixel inputs decoded together need well over 200 MiB; the 8192 px output
        // strip and a single input need a small fraction of that.
        assertTrue("Merging grew the native heap by ${growth shr 20} MiB", growth < 96L shl 20)
        val bitmap = BitmapFactory.decodeFile(checkNotNull(merged).absolutePath)
        try {
            assertTrue(bitmap.height in 8191..8192 && bitmap.width in 272..274)
            assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height - 1))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun mergeLeavesOutAnImageWhosePixelsCannotBeDecoded() {
        // The header of this file is intact, so its size is known before decoding fails.
        val truncated = File(context.filesDir, "merge-truncated.png")
        val noise = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val random = Random(1)
        for (y in 0 until noise.height) {
            for (x in 0 until noise.width) noise.setPixel(x, y, 0xFF000000.toInt() or random.nextInt(0x1000000))
        }
        val encoded = ByteArrayOutputStream().also { noise.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        noise.recycle()
        truncated.writeBytes(encoded.copyOf(encoded.size / 2))
        val inputs = listOf(
            imageInput("merge-top.png", Color.RED),
            InputItem(fixtureUri(truncated), truncated.name, "image/png"),
            imageInput("merge-bottom.png", Color.BLUE),
        )

        val result = ImageMergeProcessor(context, outputs).process(
            inputs,
            MergeOptions(direction = MergeDirection.VERTICAL),
        ) { _, _, _ -> }

        assertEquals(listOf(truncated.name), result.failures.map { it.source.resolve(context) })
        val bitmap = BitmapFactory.decodeFile(result.outputs.single().file.absolutePath)
        try {
            // Only the two readable images take part in the layout; no gap is left for the third.
            assertEquals(16, bitmap.width)
            assertEquals(24, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(8, 6))
            assertEquals(Color.BLUE, bitmap.getPixel(8, 18))
        } finally {
            bitmap.recycle()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun corruptedPdfFailsClearlyWhenNoOtherOutputExists() {
        val broken = File(context.filesDir, "corrupted.pdf").apply { writeText("not a pdf") }
        val input = InputItem(fixtureUri(broken), broken.name, "application/pdf")

        PdfToImagesProcessor(context, outputs).process(listOf(input), PdfToImagesOptions()) { _, _, _ -> }
    }

    private fun imageInput(name: String, color: Int): InputItem {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return InputItem(fixtureUri(file), name, "image/png")
    }

    /** 256 opaque grey levels from left to right, four pixels each. */
    private fun gradientInput(name: String, format: Bitmap.CompressFormat, height: Int): InputItem {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(1024, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(1024) { x -> Color.rgb(x / 4, x / 4, x / 4) }
        for (y in 0 until height) bitmap.setPixels(row, 0, 1024, 0, y, 1024, 1)
        bitmap.setHasAlpha(false)
        FileOutputStream(file).use { bitmap.compress(format, 100, it) }
        bitmap.recycle()
        val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        return InputItem(fixtureUri(file), name, mimeType)
    }

    /** An opaque 2048 × 2048 image: 16 MiB once decoded in full colour. */
    private fun largeImage(name: String): android.net.Uri {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            setHasAlpha(false)
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return fixtureUri(file)
    }

    private fun shades(bitmap: Bitmap, row: Int): Int {
        val pixels = IntArray(bitmap.width)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, row, bitmap.width, 1)
        return pixels.toSet().size
    }

    /** Largest growth of the native heap, where bitmap pixels live, while [work] runs. */
    private fun peakNativeHeapGrowth(work: () -> Unit): Long {
        Runtime.getRuntime().gc()
        val baseline = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(baseline)
        val running = AtomicBoolean(true)
        val sampler = thread {
            while (running.get()) {
                peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { previous, current -> maxOf(previous, current) }
                Thread.sleep(2)
            }
        }
        try {
            work()
        } finally {
            running.set(false)
            sampler.join()
        }
        return peak.get() - baseline
    }

    private fun createPdf(name: String, pages: Int): File {
        val file = File(context.filesDir, name)
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(120, 160, index + 1).create())
                page.canvas.drawColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun fixtureUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

private object BitmapFactoryCompat {
    fun decodes(file: File): Boolean = android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let {
        it.recycle()
        true
    } ?: false
}
