package com.poppinzhang.natsuko

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportWorkflowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedScreenshotAutomaticallyEntersSelectedFeature() = runBlocking {
        val screenshot = createImage("shared-screenshot.png", 0xFFB84E62.toInt())
        val intent = Intent(Intent.ACTION_SEND)
            .setComponent(ComponentName(context, "com.poppinzhang.natsuko.share.ImageMergeShare"))
            .putExtra(Intent.EXTRA_STREAM, fixtureUri(screenshot))
        val viewModel = NatsukoViewModel(context.applicationContext as Application)

        viewModel.handleShareIntent(intent)
        val state = withTimeout(5_000) { viewModel.state.first { it.screen == AppScreen.CONFIGURE } }

        assertEquals(Feature.IMAGE_MERGE, state.feature)
        assertEquals(1, state.inputs.size)
    }

    @Test
    fun importsMultipleImagesInOneWorkflow() = runBlocking {
        val first = fixtureUri(createImage("batch-one.png", 0xFF112233.toInt()))
        val second = fixtureUri(createImage("batch-two.png", 0xFF445566.toInt()))
        val viewModel = NatsukoViewModel(context.applicationContext as Application)

        viewModel.acceptSelection(Feature.IMAGES_TO_PDF, listOf(first, second))
        val state = withTimeout(5_000) { viewModel.state.first { it.screen == AppScreen.CONFIGURE } }

        assertEquals(2, state.inputs.size)
        assertEquals(listOf("batch-one.png", "batch-two.png"), state.inputs.map { it.displayName })
    }

    @Test
    fun reorderedImagePreviewsUpdateOutputOrder() = runBlocking {
        val first = fixtureUri(createImage("order-one.png", 0xFF112233.toInt()))
        val second = fixtureUri(createImage("order-two.png", 0xFF445566.toInt()))
        val viewModel = NatsukoViewModel(context.applicationContext as Application)
        viewModel.acceptSelection(Feature.IMAGE_MERGE, listOf(first, second))
        withTimeout(5_000) { viewModel.state.first { it.inputs.size == 2 } }

        viewModel.reorderInput(0, 1)

        assertEquals(listOf("order-two.png", "order-one.png"), viewModel.state.value.inputs.map { it.displayName })
    }

    @Test
    fun importsSinglePdf() = runBlocking {
        val pdf = File(context.filesDir, "single.pdf").apply { writeBytes("%PDF fixture".toByteArray()) }
        val viewModel = NatsukoViewModel(context.applicationContext as Application)

        viewModel.acceptSelection(Feature.PDF_TO_IMAGES, listOf(fixtureUri(pdf)))
        val state = withTimeout(5_000) { viewModel.state.first { it.screen == AppScreen.CONFIGURE } }

        assertEquals(Feature.PDF_TO_IMAGES, state.feature)
        assertEquals(1, state.inputs.size)
    }

    @Test
    fun rejectsInvalidInputBeforeProcessing() = runBlocking {
        val text = File(context.filesDir, "invalid.txt").apply { writeText("not an image") }
        val viewModel = NatsukoViewModel(context.applicationContext as Application)

        viewModel.acceptSelection(Feature.IMAGE_MERGE, listOf(fixtureUri(text)))
        val state = withTimeout(5_000) { viewModel.state.first { it.errorMessage != null } }

        assertNotNull(state.errorMessage)
        assertEquals(AppScreen.HOME, state.screen)
    }

    private fun createImage(name: String, color: Int): File {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun fixtureUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
