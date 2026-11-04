package com.poppinzhang.natsuko

import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.OutputStorageSettings
import com.poppinzhang.natsuko.model.AdjustmentEdits
import com.poppinzhang.natsuko.model.AdjustmentExportSettings
import com.poppinzhang.natsuko.model.AdjustmentFormat
import com.poppinzhang.natsuko.model.AdjustmentTool
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.PixelRect
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.ui.AdjustmentEstimate
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.ImageAdjustmentState
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageAdjustmentWorkflowTest {
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
    fun homeShowsOneImageAdjustmentEntryInTheImagesCategory() {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }

        composeRule.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-image-adjustment"))
        composeRule.onNodeWithTag("home-image-adjustment").assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.feature_image_adjustment)).assertCountEquals(1)
        composeRule.onAllNodesWithTag("home-image-adjustment").assertCountEquals(1)
    }

    @Test
    fun selectingOneImageOpensTheEditorDirectly() = runBlocking {
        val viewModel = newViewModel()
        val adjustment = open(viewModel, fixtureUri(quadrantImage()))
        val state = viewModel.state.value

        assertEquals(AppScreen.ADJUST_EDIT, state.screen)
        assertEquals(Feature.IMAGE_ADJUSTMENT, state.feature)
        val source = checkNotNull(adjustment.source)
        assertEquals(40, source.width)
        assertEquals(20, source.height)
        assertEquals(AdjustmentFormat.PNG, adjustment.exportSettings!!.format)
        assertNull(state.outcome)
        assertNull(state.errorMessage)
    }

    @Test
    fun sharedScreenshotsOpenTheSameEditorWithoutAutomaticExport() = runBlocking {
        val screenshot = fixtureUri(quadrantImage())
        val intents = listOf(
            shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, screenshot),
            shareIntent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("Screenshot", screenshot) },
        )
        for (intent in intents) {
            val viewModel = newViewModel()
            onMain { viewModel.handleShareIntent(intent) }
            val loaded = awaitState(viewModel) { it.adjustment?.source != null }

            assertEquals(AppScreen.ADJUST_EDIT, loaded.screen)
            assertEquals(screenshot, loaded.adjustment!!.input.uri)
            val exported = withTimeoutOrNull(1_000) { viewModel.state.first { it.isProcessing || it.outcome != null } }
            assertNull("Sharing must not export automatically", exported)
        }
    }

    @Test
    fun severalSharedImagesAskForOneImageInsteadOfBatchProcessing() = runBlocking {
        val first = fixtureUri(quadrantImage())
        val second = fixtureUri(quadrantImage(width = 30, height = 60))
        val viewModel = newViewModel()
        onMain {
            viewModel.handleShareIntent(
                shareIntent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second)),
            )
        }
        val choosing = awaitState(viewModel) { it.screen == AppScreen.ADJUST_CHOOSE }

        assertEquals(listOf(first, second), choosing.adjustmentChoices.map { it.uri })
        assertNull(choosing.adjustment)
        onMain { viewModel.chooseAdjustmentImage(choosing.adjustmentChoices[1]) }
        val editing = awaitState(viewModel) { it.adjustment?.source != null }
        val chosen = checkNotNull(editing.adjustment)
        val source = checkNotNull(chosen.source)
        assertEquals(second, chosen.input.uri)
        assertEquals(30 to 60, source.width to source.height)
        assertTrue(editing.adjustmentChoices.isEmpty())
    }

    @Test
    fun invalidCorruptedAndAnimatedInputsReportClearErrors() = runBlocking {
        val textFile = createFile("txt", "not an image".toByteArray())
        val rejected = newViewModel()
        onMain { rejected.handleShareIntent(shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, fixtureUri(textFile))) }
        val error = awaitState(rejected) { it.errorMessage != null }
        assertEquals(AppScreen.HOME, error.screen)
        assertNull(error.adjustment)

        val empty = newViewModel()
        onMain { empty.handleShareIntent(shareIntent(Intent.ACTION_SEND)) }
        assertEquals(text(R.string.backend_share_no_readable_files), awaitState(empty) { it.errorMessage != null }.errorMessage)

        val corrupt = newViewModel()
        onMain { corrupt.acceptSelection(Feature.IMAGE_ADJUSTMENT, listOf(fixtureUri(createFile("png", Random(7).nextBytes(512))))) }
        val corrupted = awaitState(corrupt) { it.adjustment?.loadError != null }
        assertEquals(text(R.string.backend_image_invalid), corrupted.adjustment!!.loadError)
        assertEquals(AppScreen.ADJUST_EDIT, corrupted.screen)

        val gif = createFile("gif", instrumentation.context.assets.open("video/sample.gif").use { it.readBytes() })
        val animated = newViewModel()
        onMain { animated.acceptSelection(Feature.IMAGE_ADJUSTMENT, listOf(fixtureUri(gif))) }
        assertEquals(text(R.string.backend_adjust_animated), awaitState(animated) { it.adjustment?.loadError != null }.adjustment!!.loadError)
        // Leaving a failed import never asks to discard edits.
        onMain { animated.back() }
        assertEquals(AppScreen.HOME, animated.state.value.screen)
    }

    @Test
    fun uneditedPngExportsAsJpegWithUnchangedSizeAndOriginal() = runBlocking {
        val source = quadrantImage()
        val originalBytes = source.readBytes()
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(source))

        val output = export(viewModel) { it.copy(format = AdjustmentFormat.JPEG) }

        assertEquals("image/jpeg", output.mimeType)
        assertEquals("${source.nameWithoutExtension}_adjusted.jpg", output.file.name)
        assertEquals(40 to 20, decodedSize(output.file))
        assertArrayEquals(originalBytes, source.readBytes())
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, Environment.DIRECTORY_PICTURES)
        assertEquals(File(pictures, "Natsuko/ImageAdjustment").canonicalPath, output.file.parentFile!!.parentFile!!.canonicalPath)
        assertNull(output.userStorage)
    }

    @Test
    fun changingOnlyQualityKeepsGeometryAndMeasuresEachChoice() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(noiseImage(320, 240, Bitmap.CompressFormat.JPEG)))
        onMain { viewModel.openAdjustmentExport() }
        val initial = awaitReadyEstimate(viewModel)
        val initialSettings = checkNotNull(initial.exportSettings)
        assertEquals(AdjustmentFormat.JPEG, initialSettings.format)
        assertEquals(AdjustmentExportSettings.DEFAULT_QUALITY, initialSettings.quality)

        val sizes = AdjustmentExportSettings.QUALITIES.associateWith { quality ->
            onMain { viewModel.updateAdjustmentExportSettings(viewModel.state.value.adjustment!!.exportSettings!!.copy(quality = quality)) }
            val ready = awaitReadyEstimate(viewModel)
            assertEquals(320 to 240, ready.exportRequest!!.geometry.outputWidth to ready.exportRequest!!.geometry.outputHeight)
            (ready.estimate as AdjustmentEstimate.Ready).bytes
        }
        assertTrue("Higher JPEG quality should produce a larger noisy file: $sizes", sizes.getValue(100) > sizes.getValue(60))

        onMain { viewModel.saveAdjustment() }
        val output = awaitResult(viewModel)
        assertEquals(sizes.getValue(100), output.file.length())
        assertEquals(320 to 240, decodedSize(output.file))
    }

    @Test
    fun rotationMirroringAndCropMatchThePreviewGeometry() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(quadrantImage()))
        onMain {
            viewModel.rotateAdjustment(clockwise = true)
            viewModel.flipAdjustment(horizontal = true)
            viewModel.updateAdjustmentCrop(PixelRect(0, 0, 20, 20), commit = true)
        }
        val edited = viewModel.state.value.adjustment!!
        assertEquals(20 to 20, edited.resolved!!.outputWidth to edited.resolved!!.outputHeight)

        val bitmap = decode(export(viewModel))
        try {
            assertEquals(20 to 20, bitmap.width to bitmap.height)
            // Clockwise, the left column becomes the top row; the flip then swaps the visible left and right.
            assertEquals(RED, bitmap.getPixel(4, 10))
            assertEquals(BLUE, bitmap.getPixel(15, 10))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun straighteningTrimsUncoveredCornersAndZeroRestoresTheCrop() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(noiseImage(400, 300, Bitmap.CompressFormat.PNG)))
        onMain { viewModel.updateAdjustmentStraighten(100, commit = true) }
        val straightened = viewModel.state.value.adjustment!!.resolved!!
        assertTrue(straightened.outputWidth < 400 && straightened.outputHeight < 300)

        val bitmap = decode(export(viewModel))
        try {
            assertEquals(straightened.outputWidth to straightened.outputHeight, bitmap.width to bitmap.height)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Straightening must not leave transparent corners", pixels.all { it ushr 24 == 255 })
        } finally {
            bitmap.recycle()
        }

        onMain {
            viewModel.continueAdjustmentEditing()
            viewModel.selectAdjustmentTool(AdjustmentTool.STRAIGHTEN)
            viewModel.resetAdjustmentTool(AdjustmentTool.STRAIGHTEN)
        }
        val level = viewModel.state.value.adjustment!!.resolved!!
        assertEquals(400 to 300, level.outputWidth to level.outputHeight)
    }

    @Test
    fun reductionFollowsLaterCropsAndRestoringNeverUpscalesAResult() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(noiseImage(400, 300, Bitmap.CompressFormat.PNG)))
        fun output() = viewModel.state.value.adjustment!!.resolved!!.let { it.outputWidth to it.outputHeight }

        onMain { viewModel.setAdjustmentScale(0.5) }
        assertEquals(200 to 150, output())
        onMain { viewModel.updateAdjustmentCrop(PixelRect(50, 0, 350, 300), commit = true) }
        assertEquals(150 to 150, output())
        onMain { viewModel.setAdjustmentTargetSize(width = 100) }
        assertEquals(100 to 100, output())
        onMain { viewModel.setAdjustmentTargetSize(height = 5_000) }
        assertEquals("Targets above the edited size cannot enlarge it", 300 to 300, output())

        onMain { viewModel.setAdjustmentScale(0.25) }
        assertEquals(75 to 75, decodedSize(export(viewModel).file))
        onMain {
            viewModel.continueAdjustmentEditing()
            viewModel.resetAdjustmentTool(AdjustmentTool.RESIZE)
        }
        assertEquals(300 to 300, output())
    }

    @Test
    fun transparencyIsKeptOrCompositedOntoTheChosenJpegBackground() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(halfTransparentImage()))
        val adjustment = viewModel.state.value.adjustment!!
        assertTrue(adjustment.source!!.hasTransparency)
        assertEquals(AdjustmentFormat.PNG, adjustment.exportSettings!!.format)

        decode(export(viewModel)).let { png ->
            assertEquals(0, Color.alpha(png.getPixel(3, 10)))
            assertEquals(RED, png.getPixel(15, 10))
            png.recycle()
        }
        for ((background, expected) in listOf(AdjustmentExportSettings.BACKGROUND_BLACK to Color.BLACK, AdjustmentExportSettings.BACKGROUND_WHITE to Color.WHITE)) {
            onMain { viewModel.continueAdjustmentEditing() }
            val jpeg = decode(export(viewModel) { it.copy(format = AdjustmentFormat.JPEG, background = background) })
            assertNear(expected, jpeg.getPixel(3, 10))
            assertNear(RED, jpeg.getPixel(16, 10))
            jpeg.recycle()
        }
        for (lossless in listOf(true, false)) {
            onMain { viewModel.continueAdjustmentEditing() }
            val output = export(viewModel) { it.copy(format = AdjustmentFormat.WEBP, webpLossless = lossless) }
            assertEquals("image/webp", output.mimeType)
            assertTrue(output.file.name.endsWith("_adjusted.webp"))
            val webp = decode(output)
            assertTrue(Color.alpha(webp.getPixel(3, 10)) < 32)
            assertEquals(255, Color.alpha(webp.getPixel(16, 10)))
            webp.recycle()
        }
    }

    @Test
    fun exifOrientationIsAppliedOnceAndCaptureMetadataIsNotCopied() = runBlocking {
        val source = quadrantImage(format = Bitmap.CompressFormat.JPEG)
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKE, "NatsukoTestCamera")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:02 03:04:05")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "35/1,40/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "139/1,45/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
        val viewModel = newViewModel()
        val adjustment = open(viewModel, fixtureUri(source))
        val oriented = checkNotNull(adjustment.source)
        assertEquals(20 to 40, oriented.width to oriented.height)
        assertEquals(AdjustmentFormat.JPEG, adjustment.exportSettings!!.format)

        val output = export(viewModel)
        val exif = ExifInterface(output.file.absolutePath)
        assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) in
            setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        val bitmap = decode(output)
        try {
            assertEquals(20 to 40, bitmap.width to bitmap.height)
            assertNear(BLUE, bitmap.getPixel(4, 8))
            assertNear(RED, bitmap.getPixel(15, 8))
            assertNear(YELLOW, bitmap.getPixel(4, 32))
            assertNear(GREEN, bitmap.getPixel(15, 32))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun undoRedoRestoreAndToolResetsCoverEveryEdit() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(quadrantImage()))
        fun edits() = viewModel.state.value.adjustment!!.history.present

        onMain {
            viewModel.rotateAdjustment(clockwise = true)
            viewModel.flipAdjustment(horizontal = false)
            viewModel.updateAdjustmentStraighten(40, commit = false)
            viewModel.updateAdjustmentStraighten(55, commit = false)
            viewModel.commitAdjustmentGesture()
            viewModel.setAdjustmentScale(0.5)
        }
        val edited = edits()
        assertEquals(55, edited.straightenTenths)
        assertEquals(4, viewModel.state.value.adjustment!!.history.past.size)

        onMain { viewModel.undoAdjustment() }
        assertEquals(1.0, edits().scale, 0.0)
        onMain { viewModel.undoAdjustment() }
        assertEquals("A finished slider drag is one undo step", 0, edits().straightenTenths)
        onMain {
            viewModel.redoAdjustment()
            viewModel.redoAdjustment()
        }
        assertEquals(edited, edits())

        onMain { viewModel.resetAdjustmentTool(AdjustmentTool.MIRROR) }
        assertFalse(edits().mirrored)
        assertEquals(edited.scale, edits().scale, 0.0)
        onMain { viewModel.restoreAdjustmentOriginal() }
        assertEquals(AdjustmentEdits(), edits())
        onMain { viewModel.undoAdjustment() }
        assertFalse(edits().mirrored)
        assertEquals(0.5, edits().scale, 0.0)
    }

    @Test
    fun leavingWithUnexportedEditsAsksWhileExportReturnsDirectly() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(quadrantImage()))
        onMain { viewModel.back() }
        assertEquals("No edits: leave without a prompt", AppScreen.HOME, viewModel.state.value.screen)

        open(viewModel, fixtureUri(quadrantImage()))
        onMain { viewModel.rotateAdjustment(clockwise = false) }
        onMain { viewModel.openAdjustmentExport() }
        awaitState(viewModel) { it.screen == AppScreen.ADJUST_EXPORT }
        onMain { viewModel.updateAdjustmentExportSettings(viewModel.state.value.adjustment!!.exportSettings!!.copy(format = AdjustmentFormat.WEBP)) }
        onMain { viewModel.back() }
        val back = viewModel.state.value
        val returned = checkNotNull(back.adjustment)
        assertEquals(AppScreen.ADJUST_EDIT, back.screen)
        assertFalse(returned.confirmDiscard)
        assertEquals(3, returned.history.present.quarterTurns)

        onMain { viewModel.back() }
        assertTrue(viewModel.state.value.adjustment!!.confirmDiscard)
        onMain { viewModel.dismissDiscardAdjustment() }
        assertEquals(AppScreen.ADJUST_EDIT, viewModel.state.value.screen)
        onMain { viewModel.openAdjustmentExport() }
        val reopened = awaitState(viewModel) { it.screen == AppScreen.ADJUST_EXPORT }
        assertEquals("Export choices persist in the workflow", AdjustmentFormat.WEBP, reopened.adjustment!!.exportSettings!!.format)
        onMain {
            viewModel.back()
            viewModel.back()
            viewModel.confirmDiscardAdjustment()
        }
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        assertNull(viewModel.state.value.adjustment)
    }

    @Test
    fun shareDuringUnexportedEditsWaitsForConfirmation() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(quadrantImage()))
        onMain { viewModel.rotateAdjustment(clockwise = true) }
        val next = fixtureUri(quadrantImage(width = 16, height = 16))

        onMain { viewModel.handleShareIntent(shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, next)) }
        val asking = checkNotNull(viewModel.state.value.adjustment)
        assertTrue(asking.confirmDiscard)
        assertEquals(1, asking.history.present.quarterTurns)

        onMain { viewModel.confirmDiscardAdjustment() }
        val replaced = awaitState(viewModel) { it.adjustment?.input?.uri == next && it.adjustment?.source != null }
        assertEquals(AdjustmentEdits(), replaced.adjustment!!.history.present)
        assertEquals(AppScreen.ADJUST_EDIT, replaced.screen)
    }

    @Test
    fun rapidSettingChangesSaveOnlyTheLatestParameters() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(noiseImage(256, 192, Bitmap.CompressFormat.PNG)))
        onMain { viewModel.openAdjustmentExport() }
        val base = awaitState(viewModel) { it.screen == AppScreen.ADJUST_EXPORT }.adjustment!!.exportSettings!!
        onMain {
            viewModel.updateAdjustmentExportSettings(base.copy(format = AdjustmentFormat.JPEG, quality = 60))
            viewModel.updateAdjustmentExportSettings(base.copy(format = AdjustmentFormat.WEBP, webpLossless = false, quality = 75))
            viewModel.updateAdjustmentExportSettings(base.copy(format = AdjustmentFormat.JPEG, quality = 95))
            // The previous size is stale immediately, before any encode finishes.
            assertTrue(viewModel.state.value.adjustment!!.estimate is AdjustmentEstimate.Calculating)
            viewModel.saveAdjustment()
        }
        val output = awaitResult(viewModel)
        assertEquals("image/jpeg", output.mimeType)

        // Encoding is deterministic: the same parameters measure exactly the committed bytes.
        onMain {
            viewModel.continueAdjustmentEditing()
            viewModel.openAdjustmentExport()
        }
        val measured = awaitReadyEstimate(viewModel)
        assertEquals(95, measured.exportSettings!!.quality)
        assertEquals(output.file.length(), (measured.estimate as AdjustmentEstimate.Ready).bytes)
    }

    @Test
    fun cancellingAnExportKeepsTheEditsAndLeavesNoPartialFiles() = runBlocking {
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(noiseImage(2400, 1800, Bitmap.CompressFormat.JPEG)))
        onMain { viewModel.rotateAdjustment(clockwise = true) }
        val edits = viewModel.state.value.adjustment!!.history.present
        val runs = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Natsuko/ImageAdjustment")
        val before = runs.listFiles().orEmpty().toSet()
        onMain {
            viewModel.openAdjustmentExport()
            viewModel.updateAdjustmentExportSettings(viewModel.state.value.adjustment!!.exportSettings!!.copy(format = AdjustmentFormat.PNG))
            viewModel.saveAdjustment()
        }
        awaitState(viewModel) { it.isProcessing }
        onMain { viewModel.cancelProcessing() }
        val cancelled = awaitState(viewModel) { !it.isProcessing }
        assumeTrue("The export finished before it could be cancelled", cancelled.screen != AppScreen.RESULT)

        assertEquals(AppScreen.ADJUST_EXPORT, cancelled.screen)
        assertEquals(edits, cancelled.adjustment!!.history.present)
        assertNull(cancelled.outcome)
        assertEquals(before, runs.listFiles().orEmpty().toSet())
        onMain { viewModel.saveAdjustment() }
        assertEquals(1800 to 2400, decodedSize(awaitResult(viewModel).file))
    }

    @Test
    fun savedResultUsesTheUserFeatureDirectoryWithoutATimestampFolder() = runBlocking {
        preferences.setEnabled(true)
        val source = quadrantImage()
        val viewModel = newViewModel()
        open(viewModel, fixtureUri(source))
        onMain { viewModel.openAdjustmentExport() }
        awaitReadyEstimate(viewModel)
        onMain { viewModel.saveAdjustment() }
        val saved = awaitState(viewModel) {
            !it.isProcessing && !it.isSavingToUserStorage && it.outcome?.outputs?.singleOrNull()?.userStorage != null
        }
        val output = saved.outcome!!.outputs.single().also(outputs::add)
        val published = checkNotNull(output.userStorage)

        assertEquals("Download/Natsuko/ImageAdjustment", published.directoryLabel)
        context.contentResolver.query(
            published.uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Download/Natsuko/ImageAdjustment/", cursor.getString(0))
            assertEquals("${source.nameWithoutExtension}_adjusted.png", cursor.getString(1))
        }
        assertArrayEquals(output.file.readBytes(), context.contentResolver.openInputStream(published.uri)!!.use { it.readBytes() })
    }

    @Test
    fun editStateSurvivesActivityRecreationWithoutAnotherImport() = runBlocking {
        val uri = fixtureUri(quadrantImage())
        val intent = shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            var model: NatsukoViewModel? = null
            scenario.onActivity { model = ViewModelProvider(it)[NatsukoViewModel::class.java] }
            val viewModel = checkNotNull(model)
            awaitState(viewModel) { it.adjustment?.source != null }
            onMain {
                viewModel.rotateAdjustment(clockwise = true)
                viewModel.selectAdjustmentTool(AdjustmentTool.ROTATE)
            }
            val edited = viewModel.state.value

            scenario.recreate()

            scenario.onActivity { assertSame(viewModel, ViewModelProvider(it)[NatsukoViewModel::class.java]) }
            assertEquals(edited.adjustment!!.history, viewModel.state.value.adjustment!!.history)
            assertEquals(AppScreen.ADJUST_EDIT, viewModel.state.value.screen)
            assertEquals(AdjustmentTool.ROTATE, viewModel.state.value.adjustment!!.tool)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun editorAndExportScreensExposeToolsSizesAndAccessibleActions() = runBlocking<Unit> {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        open(viewModel, fixtureUri(quadrantImage()))
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("adjust-canvas").fetchSemanticsNodes().isNotEmpty() }

        listOf("crop", "straighten", "rotate", "mirror", "resize").forEach { composeRule.onNodeWithTag("adjust-tool-$it").assertIsDisplayed() }
        listOf(R.string.adjust_undo, R.string.adjust_redo, R.string.adjust_restore_original, R.string.main_back).forEach {
            composeRule.onNodeWithContentDescription(context.getString(it)).assertExists()
        }
        composeRule.onNodeWithTag("adjust-output-size").assertTextEquals(context.getString(R.string.adjust_output_size, 40, 20))

        composeRule.onNodeWithTag("adjust-tool-rotate").performClick()
        composeRule.onNodeWithTag("adjust-rotate-right").performClick()
        composeRule.onNodeWithTag("adjust-output-size").assertTextEquals(context.getString(R.string.adjust_output_size, 20, 40))
        composeRule.onNodeWithTag("adjust-tool-straighten").performClick()
        composeRule.onNodeWithTag("adjust-straighten-up").performClick()
        composeRule.onNodeWithTag("adjust-straighten-value").assertTextEquals(context.getString(R.string.adjust_angle_value, 0.1))
        composeRule.onNodeWithTag("adjust-undo").performClick()
        composeRule.onNodeWithTag("adjust-straighten-value").assertTextEquals(context.getString(R.string.adjust_angle_value, 0.0))

        composeRule.onNodeWithTag("adjust-export").performClick()
        val ready = awaitReadyEstimate(viewModel)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("adjust-final-dimensions").assertTextEquals(context.getString(R.string.adjust_dimensions, 20, 40))
        composeRule.onNodeWithTag("adjust-estimated-size")
            .assertTextEquals(Formatter.formatShortFileSize(context, (ready.estimate as AdjustmentEstimate.Ready).bytes))

        composeRule.onNodeWithTag("adjust-export-back").performClick()
        composeRule.onNodeWithTag("adjust-back").performClick()
        composeRule.onNodeWithTag("adjust-discard-keep").performClick()
        assertEquals(AppScreen.ADJUST_EDIT, viewModel.state.value.screen)
        composeRule.onNodeWithTag("adjust-back").performClick()
        composeRule.onNodeWithTag("adjust-discard-confirm").performClick()
        composeRule.onNodeWithTag("home-list").assertIsDisplayed()
    }

    private suspend fun open(viewModel: NatsukoViewModel, uri: Uri): ImageAdjustmentState {
        onMain { viewModel.acceptSelection(Feature.IMAGE_ADJUSTMENT, listOf(uri)) }
        return awaitState(viewModel) { it.adjustment?.input?.uri == uri && it.adjustment?.source != null }.adjustment!!
    }

    /** Applies settings, waits for the matching size, saves, and checks the committed bytes. */
    private suspend fun export(
        viewModel: NatsukoViewModel,
        settings: (AdjustmentExportSettings) -> AdjustmentExportSettings = { it },
    ): OutputArtifact {
        onMain { viewModel.openAdjustmentExport() }
        val current = awaitState(viewModel) { it.screen == AppScreen.ADJUST_EXPORT }.adjustment!!.exportSettings!!
        onMain { viewModel.updateAdjustmentExportSettings(settings(current)) }
        val bytes = (awaitReadyEstimate(viewModel).estimate as AdjustmentEstimate.Ready).bytes
        onMain { viewModel.saveAdjustment() }
        return awaitResult(viewModel).also { assertEquals(bytes, it.file.length()) }
    }

    private suspend fun awaitReadyEstimate(viewModel: NatsukoViewModel): ImageAdjustmentState = awaitState(viewModel) { state ->
        val adjustment = state.adjustment
        adjustment != null && adjustment.estimate is AdjustmentEstimate.Ready && adjustment.estimate.request == adjustment.exportRequest
    }.adjustment!!

    private suspend fun awaitResult(viewModel: NatsukoViewModel): OutputArtifact {
        val state = awaitState(viewModel) { it.screen == AppScreen.RESULT && !it.isProcessing && !it.isSavingToUserStorage }
        assertNull(state.errorMessage)
        val output = state.outcome!!.outputs.single()
        outputs += output
        assertTrue(output.file.isFile)
        return output
    }

    private suspend fun awaitState(viewModel: NatsukoViewModel, predicate: (NatsukoUiState) -> Boolean) =
        withTimeout(30_000) { viewModel.state.first(predicate) }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun newViewModel(): NatsukoViewModel {
        lateinit var viewModel: NatsukoViewModel
        onMain {
            viewModel = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application),
            )["image-adjustment-${viewModels.size}", NatsukoViewModel::class.java]
        }
        viewModels += viewModel
        return viewModel
    }

    private fun shareIntent(action: String) = Intent(action)
        .setComponent(ComponentName(context, SHARE_COMPONENT))
        .setType("image/png")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Red, green, blue, and yellow quadrants in reading order. */
    private fun quadrantImage(width: Int = 40, height: Int = 20, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                y < height / 2 -> if (x < width / 2) RED else GREEN
                else -> if (x < width / 2) BLUE else YELLOW
            }
        }
        return encode(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), format)
    }

    private fun noiseImage(width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val random = Random(width * 31 + height)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        return encode(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), format)
    }

    private fun halfTransparentImage(): File {
        val pixels = IntArray(20 * 20) { if (it % 20 < 10) Color.TRANSPARENT else RED }
        return encode(Bitmap.createBitmap(pixels, 20, 20, Bitmap.Config.ARGB_8888), Bitmap.CompressFormat.PNG)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat): File {
        val extension = if (format == Bitmap.CompressFormat.JPEG) "jpg" else "png"
        val file = createFile(extension, byteArrayOf())
        try {
            file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun createFile(extension: String, bytes: ByteArray) =
        File(context.filesDir, "adjust-${UUID.randomUUID()}.$extension").also {
            fixtures += it
            it.writeBytes(bytes)
        }

    private fun fixtureUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun decode(output: OutputArtifact): Bitmap = decode(output.file)

    private fun decode(file: File): Bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "Cannot decode $file" }

    private fun decodedSize(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 40) {
        val channels = listOf(Color::red, Color::green, Color::blue)
        assertTrue(
            "Expected #${Integer.toHexString(expected)}, got #${Integer.toHexString(actual)}",
            channels.all { abs(it(expected) - it(actual)) <= tolerance },
        )
    }

    companion object {
        private const val SHARE_COMPONENT = "com.poppinzhang.natsuko.share.ImageAdjustmentShare"
        private const val RED = 0xFFE53935.toInt()
        private const val GREEN = 0xFF43A047.toInt()
        private const val BLUE = 0xFF1E88E5.toInt()
        private const val YELLOW = 0xFFFDD835.toInt()
    }
}
