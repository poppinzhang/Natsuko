package com.poppinzhang.natsuko

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.PdfOrientation
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagesToPdfPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun previewHasFixedScrollableViewportAndCenteredPages() {
        val viewModel = NatsukoViewModel(context.applicationContext as Application)
        composeRule.setContent {
            NatsukoTheme { NatsukoApp(viewModel) }
        }
        viewModel.acceptSelection(
            Feature.IMAGES_TO_PDF,
            List(5) { index -> fixtureUri(createImage("pdf-preview-$index.png", index)) },
        )
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.inputs.size == 5 }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                val page = bounds("pdf-page-preview-page-0")
                page.width > page.height
            }.getOrDefault(false)
        }

        val viewport = bounds("pdf-page-preview")
        val firstPage = bounds("pdf-page-preview-page-0")
        val expectedHeight = 360f * context.resources.displayMetrics.density
        assertEquals(expectedHeight, viewport.height, 2f)
        assertEquals(viewport.center.x, firstPage.center.x, 1f)
        assertTrue(firstPage.width > firstPage.height)
        composeRule.onNodeWithTag("pdf-page-preview-list").assert(hasScrollAction())

        composeRule.onNodeWithTag("pdf-page-preview-list").performScrollToIndex(4)
        composeRule.onNodeWithTag("pdf-page-preview-page-4").assertIsDisplayed()

        composeRule.runOnIdle { viewModel.updatePdfOrientation(PdfOrientation.PORTRAIT) }
        composeRule.waitForIdle()
        val portraitPage = bounds("pdf-page-preview-page-4")
        // Changing every page's height can leave the last page partly scrolled out
        // of view. Check its measured size, rather than its clipped visible bounds.
        val portraitSize = composeRule.onNodeWithTag("pdf-page-preview-page-4", useUnmergedTree = true)
            .fetchSemanticsNode().size
        assertTrue(portraitSize.height > portraitSize.width)
        assertEquals(viewport.center.x, portraitPage.center.x, 1f)
    }

    private fun bounds(tag: String) = composeRule
        .onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    private fun createImage(name: String, index: Int): File {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF334455.toInt() + index)
        }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun fixtureUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
