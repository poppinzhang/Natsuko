package com.poppinzhang.natsuko

import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.OutputStorageSettings
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropBlackBorderWorkflowTest {
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
            outputs.map { it.file }.distinct().forEach { it.delete() }
            fixtures.forEach { it.delete() }
            preferences.setDirectory(originalSettings.treeUri, originalSettings.directoryLabel)
            preferences.setEnabled(originalSettings.enabled)
        }
    }

    @Test
    fun selectingSingleImageAutomaticallyCropsAndReturnsHomeWithReviewableResult() = runBlocking {
        val viewModel = newViewModel()
        val input = createImage()

        viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, listOf(input))
        val state = awaitOutcome(viewModel)

        assertSuccessfulHome(state, expectedCount = 1)
        val output = state.outcome!!.outputs.single()
        assertCroppedImage(output)
        assertNull(output.userStorage)
        val picturesRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, Environment.DIRECTORY_PICTURES)
        assertTrue(output.file.canonicalPath.startsWith(picturesRoot.canonicalPath + File.separator))
        val original = context.contentResolver.openInputStream(input).use { BitmapFactory.decodeStream(it) }
        assertNotNull(original)
        try {
            assertEquals(20, original!!.width)
            assertEquals(16, original.height)
        } finally {
            original?.recycle()
        }

        viewModel.openAbout()
        assertEquals(AppScreen.ABOUT, viewModel.state.value.screen)
        viewModel.openOpenSourceProject("natsuko")
        viewModel.back()
        assertEquals(AppScreen.ABOUT, viewModel.state.value.screen)
        viewModel.back()
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        assertEquals(state.successMessage, viewModel.state.value.successMessage)
        assertEquals(state.outcome, viewModel.state.value.outcome)

        viewModel.openResults()

        assertEquals(AppScreen.RESULT, viewModel.state.value.screen)
        assertEquals(state.outcome, viewModel.state.value.outcome)
    }

    @Test
    fun homeShowsCropFeatureAndOpensCompletedResults() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        composeRule.onNodeWithText(context.getString(R.string.feature_crop_black_border)).assertIsDisplayed()

        viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, listOf(createImage()))
        val completed = awaitOutcome(viewModel)
        assertSuccessfulHome(completed, expectedCount = 1)
        composeRule.onNodeWithTag("home-crop-results").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("result-list").assertIsDisplayed()
        assertEquals(AppScreen.RESULT, viewModel.state.value.screen)
        assertEquals(completed.outcome, viewModel.state.value.outcome)
    }

    @Test
    fun screenshotSharedThroughStreamsOrClipDataIsProcessedWithoutConfiguration() = runBlocking {
        val screenshot = createImage()
        val intents = listOf(
            shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, screenshot),
            shareIntent(Intent.ACTION_SEND).apply {
                clipData = ClipData.newRawUri("Screenshot", screenshot)
            },
            shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, screenshot).apply {
                clipData = ClipData.newRawUri("Screenshot", screenshot)
            },
        )
        for (intent in intents) {
            val viewModel = newViewModel()
            viewModel.handleShareIntent(intent)
            val state = awaitOutcome(viewModel)

            assertSuccessfulHome(state, expectedCount = 1)
            assertEquals(Feature.CROP_BLACK_BORDER, state.feature)
            assertCroppedImage(state.outcome!!.outputs.single())
        }
    }

    @Test
    fun automaticCropSavesToUserStorageAndKeepsHomeReviewAvailable() = runBlocking {
        preferences.setDirectory(null)
        preferences.setEnabled(true)
        val viewModel = newViewModel()

        viewModel.handleShareIntent(shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, createImage()))
        val state = awaitState(viewModel) {
            !it.isProcessing && !it.isSavingToUserStorage &&
                it.outcome?.outputs?.singleOrNull()?.userStorage != null
        }
        outputs += state.outcome!!.outputs

        assertSuccessfulHome(state, expectedCount = 1)
        assertTrue(state.storageFailures.isEmpty())
        val output = state.outcome.outputs.single()
        assertCroppedImage(output)
        val saved = checkNotNull(output.userStorage)
        assertEquals("Download/Natsuko/CropBlackBorder", saved.directoryLabel)
        context.contentResolver.query(
            saved.uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Download/Natsuko/CropBlackBorder/", cursor.getString(0))
        }
        assertArrayEquals(
            output.file.readBytes(),
            context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() },
        )
        viewModel.openResults()
        assertEquals(AppScreen.RESULT, viewModel.state.value.screen)
    }

    @Test
    fun automaticSaveFailureShowsResultsAndCanRetryIntoDefaultDirectory() = runBlocking {
        preferences.setDirectory(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANatsuko-crop-no-grant"))
        preferences.setEnabled(true)
        val viewModel = newViewModel()

        viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, listOf(createImage()))
        val failed = awaitState(viewModel) { !it.isSavingToUserStorage && it.storageFailures.isNotEmpty() }
        outputs += failed.outcome!!.outputs

        assertEquals(AppScreen.RESULT, failed.screen)
        assertNull(failed.successMessage)
        assertCroppedImage(failed.outcome.outputs.single())
        assertNull(failed.outcome.outputs.single().userStorage)

        viewModel.restoreDefaultUserStorageDirectory()
        val retried = awaitState(viewModel) {
            !it.isSavingToUserStorage && it.outcome?.outputs?.singleOrNull()?.userStorage != null
        }
        outputs += retried.outcome!!.outputs
        assertTrue(retried.storageFailures.isEmpty())
        assertEquals(failed.outcome.outputs.single().file, retried.outcome.outputs.single().file)
    }

    @Test
    fun uncroppedStatusIsVisibleWithLongScreenshotName(): Unit = runBlocking {
        val viewModel = newViewModel()
        val file = File(context.filesDir, "Screenshot_20260914_123456789_long_name.png").also { fixtures += it }
        val bitmap = Bitmap.createBitmap(20, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, listOf(fixtureUri(file)))
        awaitOutcome(viewModel)
        composeRule.onNodeWithTag("home-crop-results").performClick()
        composeRule.onNodeWithText(context.getString(R.string.backend_border_unchanged)).assertIsDisplayed()
    }

    @Test
    fun selectingMultipleImagesProcessesEachDistinctInputAutomatically() = runBlocking {
        val viewModel = newViewModel()
        val first = createImage()
        val second = createImage()

        viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, listOf(first, second, first))
        val state = awaitOutcome(viewModel)

        assertSuccessfulHome(state, expectedCount = 2)
        state.outcome!!.outputs.forEach(::assertCroppedImage)
        assertEquals(2, state.outcome.outputs.map { it.file }.distinct().size)
        assertEquals(state.progress.total, state.progress.completed)
    }

    @Test
    fun batchShareCombinesStreamsAndClipDataWithoutDuplicateOutputs() = runBlocking {
        val viewModel = newViewModel()
        val first = createImage()
        val second = createImage()
        val intent = shareIntent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
            .apply { clipData = ClipData.newRawUri("Images", first) }

        viewModel.handleShareIntent(intent)
        val state = awaitOutcome(viewModel)

        assertSuccessfulHome(state, expectedCount = 2)
        state.outcome!!.outputs.forEach(::assertCroppedImage)
    }

    @Test
    fun mixedShareRetainsFormatAndDecodeFailuresAlongsideSuccessfulOutput() = runBlocking {
        val viewModel = newViewModel()
        val wrongFormat = createFile("txt", "This is not an image".toByteArray())
        val corrupted = createFile("png", "Broken PNG".toByteArray())
        val intent = shareIntent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(createImage(), fixtureUri(wrongFormat), fixtureUri(corrupted)),
            )

        viewModel.handleShareIntent(intent)
        val state = awaitOutcome(viewModel)

        assertEquals(AppScreen.RESULT, state.screen)
        assertNull(state.successMessage)
        val outcome = state.outcome!!
        assertEquals(1, outcome.outputs.size)
        assertCroppedImage(outcome.outputs.single())
        assertEquals(setOf(wrongFormat.name, corrupted.name), outcome.failures.map { it.source.resolve(context) }.toSet())
        assertTrue(outcome.failures.all { it.reason.resolve(context).isNotBlank() })
    }

    @Test
    fun corruptImagesProduceIndividualFailuresWithoutSuccessfulOutput() = runBlocking {
        val viewModel = newViewModel()
        val first = createFile("png", "Not a PNG".toByteArray())
        val second = createFile("jpg", byteArrayOf(0, 1, 2, 3))

        viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, listOf(fixtureUri(first), fixtureUri(second)))
        val state = awaitOutcome(viewModel)

        assertEquals(AppScreen.RESULT, state.screen)
        assertNull(state.successMessage)
        assertTrue(state.outcome!!.outputs.isEmpty())
        assertEquals(setOf(first.name, second.name), state.outcome.failures.map { it.source.resolve(context) }.toSet())
        assertTrue(state.outcome.failures.all { it.reason.resolve(context).isNotBlank() })
    }

    @Test
    fun invalidMimeTypeIsRejectedBeforeProcessing() = runBlocking {
        val viewModel = newViewModel()
        val input = fixtureUri(createFile("txt", "Not an image".toByteArray()))

        viewModel.acceptSelection(Feature.CROP_BLACK_BORDER, listOf(input))
        val state = awaitState(viewModel) { it.errorMessage != null }

        assertRejectedShare(state)
    }

    @Test
    fun missingStreamsUnsupportedActionsAndUnknownTargetsAreRejected() = runBlocking {
        val image = createImage()
        val invalidIntents = listOf(
            shareIntent(Intent.ACTION_SEND),
            shareIntent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "invalid stream payload"),
            shareIntent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, image),
            Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, image),
        )

        for (intent in invalidIntents) {
            val viewModel = newViewModel()
            viewModel.handleShareIntent(intent)

            assertRejectedShare(awaitState(viewModel) { it.errorMessage != null })
        }
    }

    @Test
    fun imageShareTargetIsExportedForSingleAndMultipleImagesOnly() {
        val manager = context.packageManager
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mimeType in listOf("image/png", "image/jpeg")) {
                val matches = manager.queryIntentActivities(
                    Intent(action).setType(mimeType).setPackage(context.packageName),
                    flags,
                )
                val alias = matches.singleOrNull { it.activityInfo.name == SHARE_COMPONENT }
                assertNotNull("Crop share target must resolve for $action and $mimeType", alias)
                assertTrue(alias!!.activityInfo.exported)
                assertEquals("com.poppinzhang.natsuko.MainActivity", alias.activityInfo.targetActivity)
                assertEquals(context.getString(R.string.share_crop_black_border), alias.loadLabel(manager).toString())
            }
            for (mimeType in listOf("application/pdf", "video/mp4", "text/plain")) {
                val matches = manager.queryIntentActivities(
                    Intent(action).setType(mimeType).setPackage(context.packageName),
                    flags,
                )
                assertFalse(matches.any { it.activityInfo.name == SHARE_COMPONENT })
            }
        }
    }

    private fun newViewModel(): NatsukoViewModel {
        lateinit var viewModel: NatsukoViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application),
            )["crop-workflow-${viewModels.size}", NatsukoViewModel::class.java]
        }
        viewModels += viewModel
        return viewModel
    }

    private suspend fun awaitOutcome(viewModel: NatsukoViewModel) = awaitState(viewModel) {
        it.outcome != null && !it.isProcessing && !it.isSavingToUserStorage
    }.also { outputs += it.outcome!!.outputs }

    private suspend fun awaitState(
        viewModel: NatsukoViewModel,
        predicate: (NatsukoUiState) -> Boolean,
    ) = withTimeout(15_000) { viewModel.state.first(predicate) }

    private fun assertSuccessfulHome(state: NatsukoUiState, expectedCount: Int) {
        assertEquals(AppScreen.HOME, state.screen)
        assertFalse(state.isProcessing)
        assertNull(state.errorMessage)
        assertFalse(state.successMessage?.resolve(context).isNullOrBlank())
        assertEquals(expectedCount, state.outcome!!.outputs.size)
        assertTrue(state.outcome.failures.isEmpty())
    }

    private fun assertRejectedShare(state: NatsukoUiState) {
        assertEquals(AppScreen.HOME, state.screen)
        assertFalse(state.isProcessing)
        assertNotNull(state.errorMessage)
        assertNull(state.outcome)
        assertNull(state.successMessage)
    }

    private fun assertCroppedImage(output: OutputArtifact) {
        assertTrue(output.file.isFile)
        assertEquals("image/png", output.mimeType)
        val bitmap = BitmapFactory.decodeFile(output.file.absolutePath)
        assertNotNull(bitmap)
        try {
            assertEquals(12, bitmap!!.width)
            assertEquals(8, bitmap.height)
            assertEquals(CONTENT_COLOR, bitmap.getPixel(0, 0))
            assertEquals(CONTENT_COLOR, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
        } finally {
            bitmap?.recycle()
        }
    }

    private fun shareIntent(action: String) = Intent(action)
        .setComponent(ComponentName(context, SHARE_COMPONENT))
        .setType("image/png")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun createImage(): Uri {
        val file = createFile("png", byteArrayOf())
        val bitmap = Bitmap.createBitmap(20, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            for (y in 4 until 12) {
                for (x in 4 until 16) setPixel(x, y, CONTENT_COLOR)
            }
        }
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return fixtureUri(file)
    }

    private fun createFile(extension: String, bytes: ByteArray) =
        File(context.filesDir, "crop-workflow-${UUID.randomUUID()}.$extension").also {
            fixtures += it
            it.writeBytes(bytes)
        }

    private fun fixtureUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    companion object {
        private const val SHARE_COMPONENT = "com.poppinzhang.natsuko.share.CropBlackBorderShare"
        private const val CONTENT_COLOR = 0xFFB84E62.toInt()
    }
}
