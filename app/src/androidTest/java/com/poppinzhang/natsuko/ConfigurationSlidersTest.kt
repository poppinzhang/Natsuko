package com.poppinzhang.natsuko

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.ImageOutputFormat
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A stepped slider reports floating-point values such as 15.999999 for the step at 16. Every
 * step of the integer settings must remain selectable, by touch and through accessibility.
 */
@RunWith(AndroidJUnit4::class)
class ConfigurationSlidersTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureDirectory = File(context.filesDir, "configuration-sliders-test").apply { mkdirs() }
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    private lateinit var viewModel: NatsukoViewModel

    @Before
    fun setUp() {
        viewModel = NatsukoViewModel(context.applicationContext as Application)
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
    }

    @After
    fun cleanUp() {
        fixtureDirectory.deleteRecursively()
    }

    @Test
    fun everyGifFrameRateAndSamplingIntervalCanBeSelected() {
        open(Feature.VIDEO_TO_GIF, "clip.mp4")

        assertSelectsEveryStep(sliderIndex = 0, steps = (1L..30L).toList()) {
            viewModel.state.value.gifOptions.framesPerSecond.toLong()
        }
        assertSelectsEveryStep(sliderIndex = 1, steps = (50L..1000L step 50).toList()) {
            viewModel.state.value.gifOptions.sampleIntervalMs
        }
    }

    @Test
    fun everyPdfPageMarginCanBeSelected() {
        open(Feature.IMAGES_TO_PDF, "page.png")

        assertSelectsEveryStep(sliderIndex = 0, steps = (0L..72L step 6).toList()) {
            viewModel.state.value.imagesToPdfOptions.marginPoints.toLong()
        }
    }

    @Test
    fun everyJpegQualityCanBeSelected() {
        open(Feature.PDF_TO_IMAGES, "document.pdf") { viewModel.updateImageFormat(ImageOutputFormat.JPEG) }

        // The first slider on this page is the rendering scale.
        assertSelectsEveryStep(sliderIndex = 1, steps = (60L..100L step 5).toList()) {
            viewModel.state.value.pdfToImagesOptions.jpegQuality.toLong()
        }
    }

    private fun open(feature: Feature, fileName: String, configure: () -> Unit = {}) {
        val file = File(fixtureDirectory, fileName)
        if (fileName.endsWith(".png")) {
            val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF334455.toInt()) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } else {
            // Only the configuration page is exercised; the content is never processed.
            file.writeBytes(ByteArray(16))
        }
        viewModel.acceptSelection(feature, listOf(FileProvider.getUriForFile(context, "${context.packageName}.files", file)))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.let { it.screen == AppScreen.CONFIGURE && it.feature == feature && it.inputs.size == 1 }
        }
        composeRule.runOnIdle(configure)
        // The settings follow the input list, which contains a scrollable preview of its own.
        composeRule.onAllNodes(hasScrollToNodeAction() and hasTestTag("pdf-page-preview-list").not())
            .onFirst().performScrollToNode(slider)
        composeRule.waitForIdle()
    }

    private fun assertSelectsEveryStep(sliderIndex: Int, steps: List<Long>, selected: () -> Long) {
        // TalkBack and Switch Access request a value directly.
        for (step in steps) {
            composeRule.onAllNodes(slider)[sliderIndex]
                .performSemanticsAction(SemanticsActions.SetProgress) { it(step.toFloat()) }
            composeRule.waitForIdle()
            assertEquals("Value selected through accessibility", step, selected())
        }

        // A finger can land anywhere along the track: tap it end to end, several times per step.
        val width = composeRule.onAllNodes(slider)[sliderIndex].fetchSemanticsNode().size.width
        val spacing = (width / (steps.size - 1) / 6f).coerceAtLeast(2f)
        val reached = sortedSetOf<Long>()
        var x = 1f
        while (x < width) {
            val position = x
            composeRule.onAllNodes(slider)[sliderIndex].performTouchInput { click(Offset(position, centerY)) }
            composeRule.waitForIdle()
            reached += selected()
            x += spacing
        }
        assertEquals("Values selected by touch", steps, reached.toList())
    }
}
