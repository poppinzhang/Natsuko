package com.poppinzhang.natsuko

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.ImagesToPdfOptions
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.model.MergeOptions
import com.poppinzhang.natsuko.model.PdfToImagesOptions
import com.poppinzhang.natsuko.processing.ImagesToPdfProcessor
import com.poppinzhang.natsuko.processing.ImageMergeProcessor
import com.poppinzhang.natsuko.processing.PdfToImagesProcessor
import java.io.File
import java.io.FileOutputStream
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
