package com.poppinzhang.natsuko

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.processing.CropBlackBorderProcessor
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropBlackBorderProcessorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val processor = CropBlackBorderProcessor(context, OutputRepository(context))
    private val fixtureDirectory = File(context.filesDir, "crop-black-border-test").apply { mkdirs() }
    private val outputRoot = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, Environment.DIRECTORY_PICTURES),
        "Natsuko/CropBlackBorder",
    )
    private val initialDirectories = outputRoot.listFiles().orEmpty().toSet()

    @After
    fun cleanUp() {
        fixtureDirectory.deleteRecursively()
        outputRoot.listFiles().orEmpty().filterNot { it in initialDirectories }.forEach { it.deleteRecursively() }
    }

    @Test
    fun cropsSingleImageAtOriginalResolutionAndPreservesExactDarkColors() {
        val color = Color.rgb(9, 9, 9)
        val input = image("精确 颜色.png", 16, 12) { x, y ->
            if (x in 2..12 && y in 1..8) color else Color.BLACK
        }
        val result = processor.process(listOf(input)) { _, _, _ -> }

        assertTrue(result.failures.isEmpty())
        assertEquals("1_精确_颜色_cropped.png", result.outputs.single().file.name)
        assertEquals("image/png", result.outputs.single().mimeType)
        assertEquals(input.displayName, result.outputs.single().label.resolve(context))
        assertEquals(context.getString(R.string.backend_border_removed), result.outputs.single().detail?.resolve(context))
        assertBitmap(result.outputs.single(), 11, 8) { bitmap ->
            assertEquals(color, bitmap.getPixel(0, 0))
            assertEquals(color, bitmap.getPixel(10, 7))
        }
        // The imported image is never modified.
        BitmapFactory.decodeFile(File(fixtureDirectory, "精确 颜色.png").path).let {
            assertEquals(16, it.width)
            assertEquals(12, it.height)
            it.recycle()
        }
    }

    @Test
    fun processesMultipleImagesSequentiallyAndPreservesNoBorderAndBlackImages() {
        val inputs = listOf(
            image("vertical.png", 16, 12) { x, _ -> if (x in 3..11) Color.RED else Color.BLACK },
            image("horizontal.png", 16, 12) { _, y -> if (y in 2..8) Color.BLUE else Color.BLACK },
            image("no-border.png", 16, 12) { _, _ -> Color.GREEN },
            image("black.png", 16, 12) { _, _ -> Color.BLACK },
            image("transparent.png", 16, 12) { x, y -> if (x in 3..11 && y in 2..8) Color.RED else Color.TRANSPARENT },
        )
        val completed = mutableListOf<Int>()
        val result = processor.process(inputs) { count, total, _ ->
            assertEquals(5, total)
            completed += count
        }

        assertEquals(5, result.outputs.size)
        assertTrue(result.failures.isEmpty())
        assertEquals(completed.sorted(), completed)
        assertEquals(5, completed.last())
        assertBitmap(result.outputs[0], 9, 12)
        assertBitmap(result.outputs[1], 16, 7)
        result.outputs.drop(2).forEach {
            assertBitmap(it, 16, 12)
            assertEquals(context.getString(R.string.backend_border_unchanged), it.detail?.resolve(context))
        }
        assertBitmap(result.outputs.last(), 16, 12) { assertEquals(0, Color.alpha(it.getPixel(0, 0))) }
    }

    @Test
    fun corruptAndMissingFilesDoNotPreventLaterValidFilesFromProcessing() {
        val corrupt = File(fixtureDirectory, "broken.png").apply { writeText("not an image") }
        val missing = File(fixtureDirectory, "missing.png")
        val inputs = listOf(
            input(corrupt),
            image("valid.png", 5, 5) { x, y -> if (x in 1..3 && y in 1..3) Color.WHITE else Color.BLACK },
            input(missing),
        )
        val result = processor.process(inputs) { _, _, _ -> }

        assertEquals(1, result.outputs.size)
        assertEquals("2_valid_cropped.png", result.outputs.single().file.name)
        assertBitmap(result.outputs.single(), 3, 3)
        assertEquals(listOf("broken.png", "missing.png"), result.failures.map { it.source.resolve(context) })
        assertTrue(result.failures.all { it.reason.resolve(context).isNotBlank() })
        assertEquals(1, result.outputs.single().file.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun allInvalidFilesStillReturnIndividualDiagnostics() {
        val bad = File(fixtureDirectory, "invalid.png").apply { writeText("invalid") }
        val result = processor.process(listOf(input(bad))) { _, _, _ -> }

        assertTrue(result.outputs.isEmpty())
        assertEquals("invalid.png", result.failures.single().source.resolve(context))
        assertTrue(result.failures.single().reason.resolve(context).isNotBlank())
    }

    @Test
    fun filenamesAreSafeAndDuplicateNamesKeepBatchOrder() {
        val image = image("original.png", 5, 5) { _, _ -> Color.RED }
        val result = processor.process(List(2) { image.copy(displayName = "../相同 图片.png") }) { _, _, _ -> }

        assertEquals(listOf("1_相同_图片_cropped.png", "2_相同_图片_cropped.png"), result.outputs.map { it.file.name })
        assertTrue(result.outputs.all { it.file.canonicalFile.parentFile == it.file.parentFile!!.canonicalFile })
    }

    @Test
    fun longChineseFilenamesFitFilesystemByteLimitAtThreeDigitBatchIndices() {
        val fixture = image("long-name.png", 1, 1) { _, _ -> Color.RED }
        val longName = "界".repeat(80) + ".png"
        val supplementaryName = "界".repeat(69) + String(Character.toChars(0x20000)) + ".png"
        val inputs = List(100) { fixture.copy(displayName = longName) } + fixture.copy(displayName = supplementaryName)
        val result = processor.process(inputs) { _, _, _ -> }

        assertTrue(result.failures.isEmpty())
        assertEquals(101, result.outputs.size)
        assertEquals("100_${"界".repeat(70)}_cropped.png", result.outputs[99].file.name)
        assertEquals(longName, result.outputs[99].label.resolve(context))
        assertEquals("101_${"界".repeat(69)}_cropped.png", result.outputs[100].file.name)
        assertTrue(result.outputs.all { it.file.name.toByteArray(Charsets.UTF_8).size <= 255 && it.file.isFile })
    }

    @Test
    fun cancellationPropagatesAndRemovesAFileWrittenBeforeItWasCommitted() {
        val cancellation = CancellationException("cancel after writing")
        val first = image("cancel-first.png", 16, 12) { x, y -> if (x in 1..14 && y in 1..10) Color.RED else Color.BLACK }
        val second = image("cancel-second.png", 16, 12) { _, _ -> Color.BLUE }
        var cancelledOutput: File? = null
        val thrown = runCatching {
            processor.process(listOf(first, second)) { _, _, message ->
                if (message == text(R.string.backend_saved_image, "cancel-first.png")) {
                    cancelledOutput = outputRoot.walkTopDown().first { it.name == "1_cancel-first_cropped.png" }
                    assertTrue(cancelledOutput!!.length() > 0)
                    throw cancellation
                }
            }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertFalse(checkNotNull(cancelledOutput).exists())
        assertFalse(outputRoot.walkTopDown().any { it.name == "2_cancel-second_cropped.png" })
    }

    @Test
    fun oversizeImagesFailIndividuallyWithoutDownsampling() {
        // A narrow image exercises the edge limit without allocating a huge fixture.
        val large = image("oversized.png", 8193, 1) { _, _ -> Color.WHITE }
        val valid = image("normal.png", 5, 5) { _, _ -> Color.RED }
        val result = processor.process(listOf(large, valid)) { _, _, _ -> }

        assertEquals(1, result.outputs.size)
        assertEquals("oversized.png", result.failures.single().source.resolve(context))
        assertTrue(result.failures.single().reason.resolve(context).contains("8192"))
        assertBitmap(result.outputs.single(), 5, 5)
    }

    @Test
    fun appliesExifOrientationBeforeCropping() {
        val source = image("rotated.jpg", 40, 24, Bitmap.CompressFormat.JPEG) { x, _ ->
            if (x in 8..31) Color.WHITE else Color.BLACK
        }
        ExifInterface(File(fixtureDirectory, "rotated.jpg").path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val result = processor.process(listOf(source)) { _, _, _ -> }

        assertTrue(result.failures.isEmpty())
        assertBitmap(result.outputs.single(), 24, 24)
    }

    private fun image(
        name: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        pixel: (Int, Int) -> Int,
    ): InputItem {
        val file = File(fixtureDirectory, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            repeat(height) { y ->
                for (x in row.indices) row[x] = pixel(x, y)
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            file.outputStream().use { check(bitmap.compress(format, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return input(file)
    }

    private fun input(file: File) = InputItem(
        FileProvider.getUriForFile(context, "${context.packageName}.files", file),
        file.name,
        if (file.extension == "jpg") "image/jpeg" else "image/png",
    )

    private fun assertBitmap(
        artifact: OutputArtifact,
        width: Int,
        height: Int,
        checkPixels: (Bitmap) -> Unit = {},
    ) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(artifact.file.path))
        try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
            checkPixels(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
