package com.poppinzhang.natsuko

import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.OutputStorageSettings
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.SplitDirection
import com.poppinzhang.natsuko.model.SplitMode
import com.poppinzhang.natsuko.model.SplitOptions
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.ImageSplitState
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageSplitWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = OutputStoragePreferences(context)
    private val store = ViewModelStore()
    private val viewModels = mutableListOf<NatsukoViewModel>()
    private val fixtures = mutableListOf<File>()
    private val outputs = mutableListOf<OutputArtifact>()
    private lateinit var originalSettings: OutputStorageSettings

    @Before
    fun disablePublicCopies() {
        originalSettings = preferences.read()
        preferences.setDirectory(null)
        preferences.setEnabled(false)
    }

    @After
    fun cleanUp() = runBlocking {
        try {
            viewModels.forEach { viewModel ->
                awaitState(viewModel) { !it.isProcessing && !it.isSavingToUserStorage }
                outputs += viewModel.state.value.outcome?.outputs.orEmpty()
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            outputs.mapNotNull { it.userStorage?.uri }.distinct().forEach { context.contentResolver.delete(it, null, null) }
            outputs.map { it.file }.distinct().forEach {
                it.delete()
                it.parentFile?.delete()
            }
            fixtures.forEach { it.delete() }
            preferences.setDirectory(originalSettings.treeUri, originalSettings.directoryLabel)
            preferences.setEnabled(originalSettings.enabled)
        }
    }

    @Test
    fun homeShowsImageSplitEntry() {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }

        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-feature-IMAGE_SPLIT"))
        composeRule.onNodeWithTag("home-feature-IMAGE_SPLIT").assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.feature_image_split)).assertCountEquals(1)
        composeRule.onAllNodesWithTag("home-feature-IMAGE_SPLIT").assertCountEquals(1)
    }

    @Test
    fun selectingAndSharingSingleImageOpensSplitEditorWithoutAutoProcessing() = runBlocking {
        val image = fixtureUri(stripedImage(120, 60))
        val direct = newViewModel()
        val loaded = open(direct, image)
        assertEquals(AppScreen.SPLIT_EDIT, direct.state.value.screen)
        assertEquals(Feature.IMAGE_SPLIT, direct.state.value.feature)
        assertEquals(120 to 60, loaded.source!!.width to loaded.source!!.height)
        assertEquals(SplitDirection.HORIZONTAL, loaded.options.direction)
        assertEquals(2, loaded.options.parts)
        assertEquals(SplitMode.EQUAL, loaded.options.mode)

        val intents = listOf(
            shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, image),
            shareIntent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("Image", image) },
        )
        for (intent in intents) {
            val viewModel = newViewModel()
            onMain { viewModel.handleShareIntent(intent) }
            val state = awaitState(viewModel) { it.split?.source != null }
            assertEquals(AppScreen.SPLIT_EDIT, state.screen)
            assertEquals(image, state.split!!.input.uri)
            val autoProcessed = withTimeoutOrNull(800) { viewModel.state.first { it.isProcessing || it.outcome != null } }
            assertNull("Sharing to image split must not process automatically", autoProcessed)
        }
    }

    @Test
    fun severalSharedImagesPromptToChooseOneImage() = runBlocking {
        val first = fixtureUri(stripedImage(100, 50))
        val second = fixtureUri(stripedImage(80, 160))
        val viewModel = newViewModel()
        onMain {
            viewModel.handleShareIntent(
                shareIntent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second)),
            )
        }
        val choosing = awaitState(viewModel) { it.screen == AppScreen.SPLIT_CHOOSE }
        assertEquals(listOf(first, second), choosing.splitChoices.map { it.uri })
        assertNull(choosing.split)

        onMain { viewModel.chooseSplitImage(choosing.splitChoices[1]) }
        val editing = awaitState(viewModel) { it.split?.source != null }
        assertEquals(AppScreen.SPLIT_EDIT, editing.screen)
        assertEquals(second, editing.split!!.input.uri)
        assertEquals(80 to 160, editing.split!!.source!!.width to editing.split!!.source!!.height)
    }

    @Test
    fun invalidCorruptedAndAnimatedInputsReportClearErrors() = runBlocking {
        val textFile = createFile("txt", "not an image".toByteArray())
        val rejected = newViewModel()
        onMain { rejected.handleShareIntent(shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, fixtureUri(textFile))) }
        val error = awaitState(rejected) { it.errorMessage != null }
        assertEquals(AppScreen.HOME, error.screen)
        assertNull(error.split)

        val empty = newViewModel()
        onMain { empty.handleShareIntent(shareIntent(Intent.ACTION_SEND)) }
        assertEquals(text(R.string.backend_share_no_readable_files), awaitState(empty) { it.errorMessage != null }.errorMessage)

        val corrupt = newViewModel()
        onMain { corrupt.acceptSelection(Feature.IMAGE_SPLIT, listOf(fixtureUri(createFile("png", Random(9).nextBytes(512))))) }
        val corrupted = awaitState(corrupt) { it.split?.loadError != null }
        assertEquals(text(R.string.backend_image_invalid), corrupted.split!!.loadError)
        assertEquals(AppScreen.SPLIT_EDIT, corrupted.screen)

        val gif = createFile("gif", instrumentation.context.assets.open("video/sample.gif").use { it.readBytes() })
        val animated = newViewModel()
        onMain { animated.acceptSelection(Feature.IMAGE_SPLIT, listOf(fixtureUri(gif))) }
        assertEquals(text(R.string.backend_split_animated), awaitState(animated) { it.split?.loadError != null }.split!!.loadError)
    }

    @Test
    fun horizontalEqualSplitProducesExactReassemblableSlices() = runBlocking {
        val sourceFile = stripedImage(width = 100, height = 40)
        val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(sourceFile))
        onMain {
            viewModel.updateSplitDirection(SplitDirection.HORIZONTAL)
            viewModel.updateSplitParts(4)
            viewModel.process()
        }
        val done = awaitState(viewModel) { it.screen == AppScreen.RESULT && !it.isProcessing }
        val generated = done.outcome!!.outputs
        outputs += generated

        assertEquals(4, generated.size)
        val stem = sourceFile.nameWithoutExtension
        generated.forEachIndexed { index, artifact ->
            assertEquals("image/png", artifact.mimeType)
            assertEquals("${stem}_part_%02d.png".format(index + 1), artifact.file.name)
            val sliceBitmap = BitmapFactory.decodeFile(artifact.file.absolutePath)
            assertNotNull(sliceBitmap)
            assertEquals(25, sliceBitmap.width)
            assertEquals(40, sliceBitmap.height)
            val offsetX = index * 25
            for (y in 0 until 40 step 5) {
                for (x in 0 until 25 step 5) {
                    assertEquals(sourceBitmap.getPixel(offsetX + x, y), sliceBitmap.getPixel(x, y))
                }
            }
            sliceBitmap.recycle()
        }
        sourceBitmap.recycle()
    }

    @Test
    fun verticalEqualSplitSupportsUpToTwentyZeroPaddedSlices() = runBlocking {
        val sourceFile = stripedImage(width = 40, height = 200)
        val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(sourceFile))
        onMain {
            viewModel.updateSplitDirection(SplitDirection.VERTICAL)
            viewModel.updateSplitParts(SplitOptions.MAX_PARTS)
            viewModel.process()
        }
        val done = awaitState(viewModel) { it.screen == AppScreen.RESULT && !it.isProcessing }
        val generated = done.outcome!!.outputs
        outputs += generated

        assertEquals(20, generated.size)
        assertTrue(generated.first().file.name.endsWith("_part_01.png"))
        assertTrue(generated.last().file.name.endsWith("_part_20.png"))
        generated.forEachIndexed { index, artifact ->
            val sliceBitmap = BitmapFactory.decodeFile(artifact.file.absolutePath)
            assertEquals(40, sliceBitmap.width)
            assertEquals(10, sliceBitmap.height)
            assertEquals(sourceBitmap.getPixel(10, index * 10 + 3), sliceBitmap.getPixel(10, 3))
            sliceBitmap.recycle()
        }
        sourceBitmap.recycle()
    }

    @Test
    fun freeSplitCustomDividersExportExactCustomRegionsAndResetWorks() = runBlocking {
        val sourceFile = stripedImage(width = 200, height = 60)
        val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(sourceFile))
        onMain {
            viewModel.updateSplitParts(3)
            viewModel.updateSplitMode(SplitMode.FREE)
            viewModel.moveSplitDivider(0, 0.20) // 40 px
            viewModel.moveSplitDivider(1, 0.75) // 150 px
            viewModel.nudgeSplitDivider(0, 5)   // 45 px
        }
        val customPlan = checkNotNull(viewModel.state.value.split?.plan)
        assertEquals(listOf(45, 150), customPlan.dividersPx)
        assertTrue(viewModel.state.value.split!!.hasCustomDividers)

        onMain { viewModel.process() }
        val done = awaitState(viewModel) { it.screen == AppScreen.RESULT && !it.isProcessing }
        val generated = done.outcome!!.outputs
        outputs += generated

        assertEquals(3, generated.size)
        val expectedWidths = listOf(45, 105, 50)
        val expectedOffsets = listOf(0, 45, 150)
        generated.forEachIndexed { index, artifact ->
            val slice = BitmapFactory.decodeFile(artifact.file.absolutePath)
            assertEquals(expectedWidths[index], slice.width)
            assertEquals(60, slice.height)
            assertEquals(sourceBitmap.getPixel(expectedOffsets[index] + 5, 20), slice.getPixel(5, 20))
            slice.recycle()
        }
        sourceBitmap.recycle()

        // Continuing to edit preserves custom dividers, and reset returns them to equal.
        onMain { viewModel.continueSplitEditing() }
        assertEquals(AppScreen.SPLIT_EDIT, viewModel.state.value.screen)
        assertEquals(listOf(45, 150), viewModel.state.value.split!!.plan!!.dividersPx)
        onMain { viewModel.resetSplitDividers() }
        assertFalse(viewModel.state.value.split!!.hasCustomDividers)
        assertEquals(listOf(67, 133), viewModel.state.value.split!!.plan!!.dividersPx)
    }

    @Test
    fun dividerWidthExcludesPixelsFromExportAndUpdatesEditorAndPreview() {
        runBlocking {
            val sourceFile = stripedImage(width = 200, height = 60)
            val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
            val viewModel = newViewModel()
            composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
            open(viewModel, fixtureUri(sourceFile))
            composeRule.waitForIdle()

            // Default divider width is 0 px, so decrease is disabled.
            assertEquals(0, viewModel.state.value.split!!.options.dividerWidthPx)
            composeRule.onNodeWithTag("split-divider-width-decrease").performScrollTo().assertIsNotEnabled()

            // Select 3 parts and configure divider width via +, -, and input.
            composeRule.onNodeWithTag("split-parts-preset-3").performScrollTo().performClick()
            composeRule.onNodeWithTag("split-divider-width-increase").performScrollTo().performClick()
            assertEquals(1, viewModel.state.value.split!!.options.dividerWidthPx)
            composeRule.onNodeWithTag("split-divider-width-decrease").performScrollTo().performClick()
            assertEquals(0, viewModel.state.value.split!!.options.dividerWidthPx)
            composeRule.onNodeWithTag("split-divider-width-input").performScrollTo().performTextReplacement("20")
            composeRule.waitForIdle()
            assertEquals(20, viewModel.state.value.split!!.options.dividerWidthPx)
            composeRule.onNodeWithTag("split-summary").assertTextContains("20 px", substring = true)

            // Split preview displays and syncs the configured divider width alongside spacing.
            composeRule.onNodeWithTag("split-open-preview").performClick()
            composeRule.onNodeWithTag("split-preview-canvas").assertIsDisplayed()
            composeRule.onNodeWithTag("split-preview-summary").assertTextContains("20 px", substring = true)
            composeRule.onNodeWithTag("split-preview-divider-width-increase").performScrollTo().performClick()
            assertEquals(21, viewModel.state.value.split!!.options.dividerWidthPx)
            composeRule.onNodeWithTag("split-preview-divider-width-decrease").performScrollTo().performClick()
            assertEquals(20, viewModel.state.value.split!!.options.dividerWidthPx)

            // Export from preview and verify exact slices with 20 px strips excluded.
            // 200 - 2 * 20 = 160 kept -> slices [0, 53), [73, 127), [147, 200).
            composeRule.onNodeWithTag("split-preview-start").performClick()
            val done = awaitState(viewModel) { it.screen == AppScreen.RESULT && !it.isProcessing }
            val generated = done.outcome!!.outputs
            outputs += generated

            assertEquals(3, generated.size)
            val expectedSlices = listOf(0 to 53, 73 to 54, 147 to 53)
            generated.forEachIndexed { index, artifact ->
                val (expectedLeft, expectedWidth) = expectedSlices[index]
                val slice = BitmapFactory.decodeFile(artifact.file.absolutePath)
                assertEquals(expectedWidth, slice.width)
                assertEquals(60, slice.height)
                assertEquals(sourceBitmap.getPixel(expectedLeft, 20), slice.getPixel(0, 20))
                assertEquals(sourceBitmap.getPixel(expectedLeft + expectedWidth - 1, 20), slice.getPixel(expectedWidth - 1, 20))
                slice.recycle()
            }
            sourceBitmap.recycle()
        }
    }

    @Test
    fun editorUiDiagramControlsFullscreenAndPreviewWorkEndToEnd() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        open(viewModel, fixtureUri(stripedImage(240, 120)))
        composeRule.waitForIdle()

        // Split diagram sits in the upper region above the settings panel.
        val diagramBounds = composeRule.onNodeWithTag("split-diagram-canvas").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settingsBounds = composeRule.onNodeWithTag("split-settings-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Split diagram must be above settings in portrait", diagramBounds.top < settingsBounds.top)

        // Direction, parts, and mode controls update state.
        composeRule.onNodeWithTag("split-direction-horizontal").assertIsSelected()
        composeRule.onNodeWithTag("split-direction-vertical").performScrollTo().performClick()
        composeRule.onNodeWithTag("split-direction-vertical").assertIsSelected()
        assertEquals(SplitDirection.VERTICAL, viewModel.state.value.split!!.options.direction)

        composeRule.onNodeWithTag("split-parts-preset-4").performScrollTo().performClick()
        assertEquals(4, viewModel.state.value.split!!.options.parts)
        composeRule.onNodeWithTag("split-parts-increase").performScrollTo().performClick()
        assertEquals(5, viewModel.state.value.split!!.options.parts)
        composeRule.onNodeWithTag("split-parts-input").performScrollTo().performTextReplacement("12")
        composeRule.waitForIdle()
        assertEquals(12, viewModel.state.value.split!!.options.parts)

        composeRule.onNodeWithTag("split-mode-free").performScrollTo().performClick()
        assertEquals(SplitMode.FREE, viewModel.state.value.split!!.options.mode)

        // Fullscreen mode opens, toggles mode, and exits cleanly.
        composeRule.onNodeWithTag("split-open-fullscreen").performClick()
        composeRule.onNodeWithTag("split-fullscreen-canvas").assertIsDisplayed()
        assertEquals(AppScreen.SPLIT_FULLSCREEN, viewModel.state.value.screen)
        composeRule.onNodeWithTag("split-fullscreen-mode-equal").performClick()
        assertEquals(SplitMode.EQUAL, viewModel.state.value.split!!.options.mode)
        composeRule.onNodeWithTag("split-fullscreen-exit").performClick()
        assertEquals(AppScreen.SPLIT_EDIT, viewModel.state.value.screen)

        // Split preview opens, configures spacing and background color, and returns cleanly.
        composeRule.onNodeWithTag("split-open-preview").performClick()
        composeRule.onNodeWithTag("split-preview-canvas").assertIsDisplayed()
        assertEquals(AppScreen.SPLIT_PREVIEW, viewModel.state.value.screen)
        composeRule.onNodeWithTag("split-preview-spacing-preset-48").performScrollTo().performClick()
        assertEquals(48, viewModel.state.value.split!!.options.previewSpacingPx)
        composeRule.onNodeWithTag("split-preview-spacing-increase").performScrollTo().performClick()
        assertEquals(49, viewModel.state.value.split!!.options.previewSpacingPx)
        composeRule.onNodeWithTag("split-preview-bg-dim").performScrollTo().performClick()
        assertEquals(SplitOptions.BACKGROUND_DIM, viewModel.state.value.split!!.options.previewBackgroundColor)
        composeRule.onNodeWithTag("split-preview-bg-dim").assertIsSelected()
        composeRule.onNodeWithTag("split-preview-return-edit").performClick()
        assertEquals(AppScreen.SPLIT_EDIT, viewModel.state.value.screen)
        assertEquals(49, viewModel.state.value.split!!.options.previewSpacingPx)
        assertEquals(SplitOptions.BACKGROUND_DIM, viewModel.state.value.split!!.options.previewBackgroundColor)
    }

    @Test
    fun onePixelAxisShowsWarningAndDisablesStartUntilDirectionChanges() {
        runBlocking {
            val viewModel = newViewModel()
            composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
            open(viewModel, fixtureUri(stripedImage(1, 60)))
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("split-too-small-warning").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("split-start").assertIsNotEnabled()
            composeRule.onNodeWithTag("split-open-preview").assertIsNotEnabled()

            composeRule.onNodeWithTag("split-direction-vertical").performScrollTo().performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("split-start").assertIsEnabled()
            composeRule.onNodeWithTag("split-open-preview").assertIsEnabled()
        }
    }

    @Test
    fun fullscreenAdaptsActivityOrientationForHorizontalAndVerticalImages() = runBlocking {
        val landscapeUri = fixtureUri(stripedImage(240, 80))
        val portraitUri = fixtureUri(stripedImage(80, 240))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var viewModel: NatsukoViewModel
            scenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity)[NatsukoViewModel::class.java]
                viewModel.acceptSelection(Feature.IMAGE_SPLIT, listOf(landscapeUri))
            }
            awaitState(viewModel) { it.split?.source != null }

            scenario.onActivity { viewModel.openSplitFullscreen() }
            waitForOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
            assertEquals(AppScreen.SPLIT_FULLSCREEN, viewModel.state.value.screen)

            scenario.onActivity { viewModel.exitSplitFullscreen() }
            waitForOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            assertEquals(AppScreen.SPLIT_EDIT, viewModel.state.value.screen)

            scenario.onActivity { viewModel.acceptSelection(Feature.IMAGE_SPLIT, listOf(portraitUri)) }
            awaitState(viewModel) { it.split?.source?.width == 80 && it.split?.source?.height == 240 }

            scenario.onActivity { viewModel.openSplitFullscreen() }
            waitForOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)

            scenario.onActivity { viewModel.back() }
            waitForOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            assertEquals(AppScreen.SPLIT_EDIT, viewModel.state.value.screen)
        }
    }

    @Test
    fun userStorageExportsSplitOutputsIntoImageSplitDirectory() = runBlocking {
        preferences.setEnabled(true)
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(stripedImage(120, 60)))
        onMain {
            viewModel.updateSplitParts(3)
            viewModel.process()
        }
        val done = awaitState(viewModel) { state ->
            state.screen == AppScreen.RESULT &&
                !state.isProcessing &&
                !state.isSavingToUserStorage &&
                (state.outcome?.outputs?.all { it.userStorage != null } == true || state.storageFailures.isNotEmpty())
        }
        val generated = done.outcome!!.outputs
        outputs += generated

        assertEquals(3, generated.size)
        assertTrue(done.storageFailures.isEmpty())
        generated.forEach { artifact ->
            val saved = checkNotNull(artifact.userStorage)
            assertEquals("Download/Natsuko/ImageSplit", saved.directoryLabel)
        }
    }

    private fun waitForOrientation(
        scenario: ActivityScenario<MainActivity>,
        expectedRequested: Int,
        expectedConfigOrientation: Int? = null,
    ) {
        val deadline = System.currentTimeMillis() + 10_000L
        var actualRequested = Int.MIN_VALUE
        var actualConfig = Configuration.ORIENTATION_UNDEFINED
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                actualRequested = it.requestedOrientation
                actualConfig = it.resources.configuration.orientation
            }
            if (actualRequested == expectedRequested &&
                (expectedConfigOrientation == null || actualConfig == expectedConfigOrientation)
            ) {
                return
            }
            Thread.sleep(50)
        }
        assertEquals("Expected activity requestedOrientation $expectedRequested", expectedRequested, actualRequested)
        if (expectedConfigOrientation != null) {
            assertEquals("Expected config orientation $expectedConfigOrientation", expectedConfigOrientation, actualConfig)
        }
    }

    private suspend fun open(viewModel: NatsukoViewModel, uri: Uri): ImageSplitState {
        onMain { viewModel.acceptSelection(Feature.IMAGE_SPLIT, listOf(uri)) }
        return checkNotNull(awaitState(viewModel) { it.split?.source != null && it.split?.preview != null }.split)
    }

    private fun newViewModel(): NatsukoViewModel {
        lateinit var created: NatsukoViewModel
        onMain {
            created = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application),
            )["split-${UUID.randomUUID()}", NatsukoViewModel::class.java]
        }
        viewModels += created
        return created
    }

    private fun shareIntent(action: String) = Intent(action)
        .setComponent(ComponentName(context.packageName, "com.poppinzhang.natsuko.share.ImageSplitShare"))
        .setType("image/*")

    private fun stripedImage(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = ((x * 255) / maxOf(1, width - 1)).coerceIn(0, 255)
                val g = ((y * 255) / maxOf(1, height - 1)).coerceIn(0, 255)
                val b = (((x + y) * 127) / maxOf(1, width + height - 2)).coerceIn(0, 255)
                bitmap.setPixel(x, y, Color.argb(255, r, g, b))
            }
        }
        val file = fixtureFile("png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    private fun createFile(extension: String, bytes: ByteArray): File =
        fixtureFile(extension).apply { writeBytes(bytes) }

    private fun fixtureFile(extension: String): File {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Natsuko/SplitTestFixtures").apply { mkdirs() }
        return File(directory, "split-${UUID.randomUUID()}.$extension").also(fixtures::add)
    }

    private fun fixtureUri(file: File): Uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private suspend fun awaitState(viewModel: NatsukoViewModel, predicate: (NatsukoUiState) -> Boolean): NatsukoUiState =
        withTimeout(15_000) { viewModel.state.first(predicate) }
}
