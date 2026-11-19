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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.OutputStorageSettings
import com.poppinzhang.natsuko.data.twitter.TwitterAccessException
import com.poppinzhang.natsuko.data.twitter.TwitterAccessFailure
import com.poppinzhang.natsuko.data.twitter.TwitterSession
import com.poppinzhang.natsuko.data.twitter.TwitterSessionStore
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.TwitterLoginStep
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Network responses are deterministic; storage publishing and image merging are real. */
@RunWith(AndroidJUnit4::class)
class TwitterImageDownloadWorkflowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = OutputStoragePreferences(context)
    private val store = ViewModelStore()
    private val viewModels = mutableListOf<NatsukoViewModel>()
    private val outputs = ConcurrentLinkedQueue<OutputArtifact>()
    private val downloadCalls = AtomicInteger()
    private lateinit var originalSettings: OutputStorageSettings

    @Before
    fun disablePublicCopies() {
        originalSettings = preferences.read()
        preferences.setEnabled(false)
        preferences.setDirectory(null)
    }

    @After
    fun cleanUp() = runBlocking {
        try {
            viewModels.forEach { viewModel ->
                viewModel.cancelProcessing()
                val state = awaitState(viewModel) { !it.isProcessing && !it.isSavingToUserStorage }
                outputs.addAll(state.outcome?.outputs.orEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            outputs.mapNotNull { it.userStorage?.uri }.distinct().forEach { uri ->
                context.contentResolver.delete(uri, null, null)
            }
            outputs.map { it.file }.distinct().forEach { file ->
                file.delete()
                file.parentFile?.delete()
            }
            preferences.setDirectory(originalSettings.treeUri, originalSettings.directoryLabel)
            preferences.setEnabled(originalSettings.enabled)
        }
    }

    @Test
    fun homeOpensImageLinkInputAndInvalidLinksNeverReachDownloader() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }

        composeRule.onNodeWithTag("home-list")
            .performScrollToNode(hasTestTag("home-feature-DOWNLOAD_TWITTER_IMAGES"))
        composeRule.onNodeWithTag("home-feature-DOWNLOAD_TWITTER_IMAGES").performClick()

        assertEquals(AppScreen.TWITTER_DOWNLOAD, viewModel.state.value.screen)
        assertEquals(Feature.DOWNLOAD_TWITTER_IMAGES, viewModel.state.value.feature)
        composeRule.onNodeWithTag("twitter-link-input").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.twitter_merge_title)).performScrollTo().assertIsDisplayed()
        assertTrue(viewModel.state.value.inputs.isEmpty())

        for (link in listOf("", "https://example.com/user/status/123", "https://x.com/user", "not a link")) {
            viewModel.dismissError()
            viewModel.updateTwitterLink(link)
            viewModel.downloadTwitterImages()
            val rejected = awaitState(viewModel) { it.errorMessage != null }
            assertEquals(AppScreen.TWITTER_DOWNLOAD, rejected.screen)
            assertEquals(Feature.DOWNLOAD_TWITTER_IMAGES, rejected.feature)
            assertFalse(rejected.isProcessing)
            assertNull(rejected.outcome)
        }
        assertEquals(0, downloadCalls.get())
    }

    @Test
    fun textAndClipDataSharesPrefillLinkWithoutStartingDownload() {
        val text = "看看这些图片 $LINK/photo/2"
        val intents = listOf(
            shareIntent().putExtra(Intent.EXTRA_TEXT, text),
            shareIntent().apply { clipData = ClipData.newPlainText("Tweet", text) },
        )
        for (intent in intents) {
            val viewModel = newViewModel()
            viewModel.handleShareIntent(intent)

            val state = viewModel.state.value
            assertEquals(AppScreen.TWITTER_DOWNLOAD, state.screen)
            assertEquals(Feature.DOWNLOAD_TWITTER_IMAGES, state.feature)
            assertEquals(text, state.twitterLink)
            assertTrue(state.inputs.isEmpty())
            assertFalse(state.isProcessing)
            assertNull(state.outcome)
            assertNull(state.errorMessage)
        }
        assertEquals(0, downloadCalls.get())
    }

    @Test
    fun shareTargetIsExportedOnlyForSinglePlainTextShares() {
        val manager = context.packageManager
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mimeType in listOf("text/plain", "image/png", "video/mp4", "application/pdf")) {
                val matches = manager.queryIntentActivities(
                    Intent(action).setType(mimeType).setPackage(context.packageName),
                    flags,
                )
                val alias = matches.singleOrNull { it.activityInfo.name == SHARE_COMPONENT }
                if (action == Intent.ACTION_SEND && mimeType == "text/plain") {
                    assertNotNull(alias)
                    assertTrue(alias!!.activityInfo.exported)
                    assertEquals("com.poppinzhang.natsuko.MainActivity", alias.activityInfo.targetActivity)
                } else {
                    assertNull("Unexpected Twitter images target for $action and $mimeType", alias)
                }
            }
        }
    }

    @Test
    fun resultPublishesImagesToUserStorageAndChainsAllImagesInOrderToMerge() = runBlocking {
        preferences.setEnabled(true)
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val downloaded = download(viewModel, expectPublicCopy = true)
        val originalOutputs = downloaded.outcome!!.outputs
        assertEquals(3, originalOutputs.size)
        val firstBytes = originalOutputs[0].file.readBytes()

        // Deleting a public copy must not break chaining because merge uses the private FileProvider URI.
        context.contentResolver.delete(originalOutputs[0].userStorage!!.uri, null, null)

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("twitter-merge-images"))
        composeRule.onNodeWithTag("twitter-merge-images").performClick()

        val configured = viewModel.state.value
        assertEquals(AppScreen.CONFIGURE, configured.screen)
        assertEquals(Feature.IMAGE_MERGE, configured.feature)
        assertEquals(MergeDirection.HORIZONTAL, configured.mergeOptions.direction)
        assertEquals(originalOutputs.map { it.file.name }, configured.inputs.map { it.displayName })
        assertTrue(configured.inputs.all { it.uri.scheme == "content" && it.uri.authority == "${context.packageName}.files" })
        assertNull(configured.outcome)

        viewModel.process()
        val mergedState = awaitPublicCopy(viewModel)
        val mergedArtifact = mergedState.outcome!!.outputs.single()
        assertEquals("image/png", mergedArtifact.mimeType)
        assertPublicCopy(mergedArtifact)

        val mergedBitmap = BitmapFactory.decodeFile(mergedArtifact.file.path)
        assertEquals(30, mergedBitmap.width)
        assertEquals(10, mergedBitmap.height)
        assertEquals(Color.RED, mergedBitmap.getPixel(5, 5))
        assertEquals(Color.GREEN, mergedBitmap.getPixel(15, 5))
        assertEquals(Color.BLUE, mergedBitmap.getPixel(25, 5))
        mergedBitmap.recycle()

        assertArrayEquals(firstBytes, originalOutputs[0].file.readBytes())
        assertEquals(1, downloadCalls.get())

        viewModel.back()

        assertEquals(AppScreen.RESULT, viewModel.state.value.screen)
        assertEquals(Feature.DOWNLOAD_TWITTER_IMAGES, viewModel.state.value.feature)
        assertEquals(originalOutputs.map { it.file }, viewModel.state.value.outcome!!.outputs.map { it.file })
    }

    @Test
    fun chainedMergeDeleteCheckboxDeletesPrivateAndPublicImagesAndReturnsHome() = runBlocking {
        preferences.setEnabled(true)
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val downloaded = download(viewModel, expectPublicCopy = true)
        val originalOutputs = downloaded.outcome!!.outputs
        val runDirectory = checkNotNull(originalOutputs.first().file.parentFile)
        val publicUris = originalOutputs.map { checkNotNull(it.userStorage).uri }
        assertTrue(publicUris.all(::mediaStoreExists))

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("twitter-merge-images"))
        composeRule.onNodeWithTag("twitter-merge-images").performClick()

        composeRule.onNodeWithTag("configure-list")
            .performScrollToNode(hasTestTag("delete-original-images-checkbox"))
        composeRule.onNodeWithTag("delete-original-images-checkbox")
            .assertIsDisplayed()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        assertTrue(viewModel.state.value.deleteChainedOriginals)

        viewModel.updateMergeDirection(MergeDirection.HORIZONTAL)
        viewModel.process()
        val mergedState = awaitPublicCopy(viewModel)
        val mergedArtifact = mergedState.outcome!!.outputs.single()
        assertPublicCopy(mergedArtifact)
        assertEquals(3, mergedState.deletedOriginalsCount)
        assertTrue(mergedState.originalDeletionFailures.isEmpty())
        assertTrue(originalOutputs.none { it.file.exists() })
        assertFalse(runDirectory.exists())
        assertTrue(publicUris.none(::mediaStoreExists))

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("result-originals-deleted"))
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.main_original_images_deleted, 3, 3),
        ).assertIsDisplayed()

        // When all downloaded images were deleted, Back returns cleanly to Home instead of an empty result screen.
        viewModel.back()
        assertEquals(AppScreen.HOME, viewModel.state.value.screen)
        assertNull(viewModel.state.value.outcome)
    }

    @Test
    fun removingImageFromChainedMergeRetainsRemovedImageAndRestoresItOnBack() = runBlocking {
        preferences.setEnabled(true)
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val downloaded = download(viewModel, expectPublicCopy = true)
        val originalOutputs = downloaded.outcome!!.outputs
        val first = originalOutputs[0]
        val removed = originalOutputs[1]
        val third = originalOutputs[2]
        val runDirectory = checkNotNull(first.file.parentFile)

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("twitter-merge-images"))
        composeRule.onNodeWithTag("twitter-merge-images").performClick()

        composeRule.onNodeWithTag("configure-list")
            .performScrollToNode(hasTestTag("delete-original-images-checkbox"))
        composeRule.onNodeWithTag("delete-original-images-checkbox").performClick()

        // Remove the middle image so only the remaining 2 images are merged and deleted.
        viewModel.removeInput(1)
        assertTrue(viewModel.state.value.canDeleteChainedOriginals)
        viewModel.updateMergeDirection(MergeDirection.HORIZONTAL)
        viewModel.process()
        val mergedState = awaitPublicCopy(viewModel)
        val mergedArtifact = mergedState.outcome!!.outputs.single()

        val mergedBitmap = BitmapFactory.decodeFile(mergedArtifact.file.path)
        assertEquals(20, mergedBitmap.width)
        assertEquals(10, mergedBitmap.height)
        assertEquals(Color.RED, mergedBitmap.getPixel(5, 5))
        assertEquals(Color.BLUE, mergedBitmap.getPixel(15, 5))
        mergedBitmap.recycle()

        assertEquals(2, mergedState.deletedOriginalsCount)
        assertFalse(first.file.exists())
        assertFalse(mediaStoreExists(first.userStorage!!.uri))
        assertTrue(removed.file.isFile)
        assertTrue(mediaStoreExists(removed.userStorage!!.uri))
        assertTrue(runDirectory.isDirectory)
        assertFalse(third.file.exists())
        assertFalse(mediaStoreExists(third.userStorage!!.uri))

        viewModel.back()

        val restored = viewModel.state.value
        assertEquals(AppScreen.RESULT, restored.screen)
        assertEquals(Feature.DOWNLOAD_TWITTER_IMAGES, restored.feature)
        assertEquals(listOf(removed.file), restored.outcome!!.outputs.map { it.file })
    }

    @Test
    fun partialDecodeFailureRetainsFailedOriginalImageWhenDeleteChecked() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val downloaded = download(viewModel)
        val originalOutputs = downloaded.outcome!!.outputs
        val first = originalOutputs[0]
        val corrupted = originalOutputs[1]
        val third = originalOutputs[2]

        viewModel.mergeDownloadedImages()
        awaitState(viewModel) { it.screen == AppScreen.CONFIGURE && it.canDeleteChainedOriginals }
        viewModel.updateDeleteChainedOriginals(true)
        // Corrupt the second image after chaining so ImageMergeProcessor skips it as undecodable.
        corrupted.file.writeText("not an image")

        viewModel.updateMergeDirection(MergeDirection.HORIZONTAL)
        viewModel.process()
        val mergedState = awaitOutcome(viewModel)

        assertEquals(1, mergedState.outcome!!.failures.size)
        assertEquals(2, mergedState.deletedOriginalsCount)
        assertFalse(first.file.exists())
        assertTrue(corrupted.file.isFile)
        assertFalse(third.file.exists())

        viewModel.back()
        assertEquals(AppScreen.RESULT, viewModel.state.value.screen)
        assertEquals(listOf(corrupted.file), viewModel.state.value.outcome!!.outputs.map { it.file })
    }

    @Test
    fun failedMergeRetainsAllOriginalImagesEvenWhenDeleteChecked() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val downloaded = download(viewModel)
        val originalOutputs = downloaded.outcome!!.outputs
        viewModel.mergeDownloadedImages()
        awaitState(viewModel) { it.screen == AppScreen.CONFIGURE && it.canDeleteChainedOriginals }
        viewModel.updateDeleteChainedOriginals(true)
        originalOutputs.forEach { it.file.writeText("not an image") }

        viewModel.process()
        val failed = awaitState(viewModel) { !it.isProcessing && it.errorMessage != null }

        assertEquals(AppScreen.CONFIGURE, failed.screen)
        assertTrue(originalOutputs.all { it.file.isFile })
        viewModel.dismissError()
        viewModel.back()
        assertEquals(AppScreen.RESULT, viewModel.state.value.screen)
        assertEquals(originalOutputs.map { it.file }, viewModel.state.value.outcome!!.outputs.map { it.file })
    }

    @Test
    fun directImageMergeAndRepickHideDeleteCheckboxAndKeepOriginals() = runBlocking {
        val localFile = OutputRepository(context).createRunDirectory(Feature.IMAGE_MERGE).resolve("local.png")
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        localFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        outputs.add(OutputArtifact(localFile, "image/png", "local"))
        val localUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", localFile)

        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }

        // Direct Image Merge from Home never shows the Twitter delete-original checkbox.
        viewModel.acceptSelection(Feature.IMAGE_MERGE, listOf(localUri))
        awaitState(viewModel) { it.screen == AppScreen.CONFIGURE && it.feature == Feature.IMAGE_MERGE }
        assertFalse(viewModel.state.value.canDeleteChainedOriginals)
        composeRule.onNodeWithTag("delete-original-images-checkbox").assertDoesNotExist()

        // Chaining shows the checkbox, and re-picking local images hides it and preserves downloaded images.
        val downloaded = download(viewModel)
        val originalOutputs = downloaded.outcome!!.outputs
        viewModel.mergeDownloadedImages()
        awaitState(viewModel) { it.screen == AppScreen.CONFIGURE && it.canDeleteChainedOriginals }
        viewModel.updateDeleteChainedOriginals(true)
        assertTrue(viewModel.state.value.deleteChainedOriginals)

        viewModel.acceptSelection(Feature.IMAGE_MERGE, listOf(localUri))
        val repicked = awaitState(viewModel) {
            it.screen == AppScreen.CONFIGURE && it.inputs.singleOrNull()?.displayName == "local.png"
        }
        assertFalse(repicked.canDeleteChainedOriginals)
        assertFalse(repicked.deleteChainedOriginals)
        composeRule.onNodeWithTag("delete-original-images-checkbox").assertDoesNotExist()
        viewModel.process()
        awaitOutcome(viewModel)

        assertTrue(originalOutputs.all { it.file.isFile })
    }

    @Test
    fun resultSwitchSavesBatchAndDownloadAgainPreservesImageFeatureAndLink() = runBlocking {
        val viewModel = newViewModel { _, progress ->
            if (downloadCalls.get() == 1) {
                ProcessingOutcome(emptyList(), listOf(ProcessingFailure(LINK, "No images")))
            } else {
                successfulImagesDownload(progress)
            }
        }
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val failed = download(viewModel)
        assertTrue(failed.outcome!!.outputs.isEmpty())
        composeRule.onNodeWithTag("twitter-merge-images").assertDoesNotExist()

        composeRule.onNodeWithTag("twitter-download-again").performClick()
        assertEquals(AppScreen.TWITTER_DOWNLOAD, viewModel.state.value.screen)
        assertEquals(Feature.DOWNLOAD_TWITTER_IMAGES, viewModel.state.value.feature)
        assertEquals(LINK, viewModel.state.value.twitterLink)

        composeRule.onNodeWithTag("twitter-link-input").performTextReplacement(SECOND_LINK)
        composeRule.onNodeWithTag("twitter-download-start").performClick()
        val succeeded = awaitOutcome(viewModel)
        assertEquals(SECOND_LINK, succeeded.twitterLink)
        assertEquals(3, succeeded.outcome!!.outputs.size)

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("result-user-storage-switch"))
        composeRule.onNodeWithTag("result-user-storage-switch").assertIsOff().performClick()
        val saved = awaitPublicCopy(viewModel)
        assertTrue(saved.storageFailures.isEmpty())
        saved.outcome!!.outputs.forEach(::assertPublicCopy)
    }

    @Test
    fun loginGateShowsImageConsentAndResumesDownload() = runBlocking {
        val calls = AtomicInteger()
        val sessions = MemorySessions()
        lateinit var model: NatsukoViewModel
        instrumentation.runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NatsukoViewModel(
                        context.applicationContext as Application,
                        twitterSessions = sessions,
                        downloadTwitterImages = { _, progress ->
                            if (calls.incrementAndGet() == 1) throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED)
                            successfulImagesDownload(progress)
                        },
                    ) as T
            }
            model = ViewModelProvider(store, factory)["twitter-image-login", NatsukoViewModel::class.java]
        }
        viewModels += model
        composeRule.setContent { NatsukoTheme { NatsukoApp(model) } }

        model.openTwitterDownload(Feature.DOWNLOAD_TWITTER_IMAGES)
        model.updateTwitterLink(LINK)
        model.downloadTwitterImages()

        awaitState(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        composeRule.onNodeWithTag("twitter-login-consent").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.twitter_auth_consent_images_title)).assertIsDisplayed()

        model.acceptTwitterLogin()
        model.completeTwitterLogin(checkNotNull(TwitterSession.fromCookieHeader("auth_token=img-token; ct0=img-csrf")))
        val completed = awaitOutcome(model)
        assertEquals(2, calls.get())
        assertEquals(3, completed.outcome!!.outputs.size)
        assertTrue(completed.twitterSessionActive)
    }

    @Test
    fun cancellingDownloadCleansUpUnpublishedFiles() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val viewModel = newViewModel { _, progress ->
            progress(1, 3, UiText.Literal("Downloading image"))
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        viewModel.openTwitterDownload(Feature.DOWNLOAD_TWITTER_IMAGES)
        viewModel.updateTwitterLink(LINK)
        viewModel.downloadTwitterImages()
        withTimeout(10_000) { started.await() }

        viewModel.cancelProcessing()
        val stopped = awaitState(viewModel) { !it.isProcessing }
        assertTrue(cancelled.get())
        assertEquals(AppScreen.TWITTER_DOWNLOAD, stopped.screen)
        assertEquals(Feature.DOWNLOAD_TWITTER_IMAGES, stopped.feature)
        assertNull(stopped.outcome)
    }

    private fun newViewModel(
        download: suspend (String, (Int, Int, UiText) -> Unit) -> ProcessingOutcome =
            { _, progress -> successfulImagesDownload(progress) },
    ): NatsukoViewModel {
        lateinit var viewModel: NatsukoViewModel
        instrumentation.runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NatsukoViewModel(
                        context.applicationContext as Application,
                        downloadTwitterImages = { link, progress ->
                            downloadCalls.incrementAndGet()
                            download(link, progress)
                        },
                    ) as T
            }
            viewModel = ViewModelProvider(store, factory)["twitter-image-workflow-${viewModels.size}", NatsukoViewModel::class.java]
        }
        viewModels += viewModel
        return viewModel
    }

    private fun successfulImagesDownload(progress: (Int, Int, UiText) -> Unit): ProcessingOutcome {
        progress(0, 3, UiText.Literal("Downloading test images"))
        val directory = OutputRepository(context).createRunDirectory(Feature.DOWNLOAD_TWITTER_IMAGES)
        val artifacts = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { index, color ->
            val file = directory.resolve("twitter-${UUID.randomUUID()}-${index + 1}.png")
            val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            OutputArtifact(file, "image/png", "Image ${index + 1}")
        }
        outputs.addAll(artifacts)
        progress(3, 3, UiText.Literal("Test download completed"))
        return ProcessingOutcome(artifacts)
    }

    private suspend fun download(
        viewModel: NatsukoViewModel,
        expectPublicCopy: Boolean = false,
    ): NatsukoUiState {
        viewModel.openTwitterDownload(Feature.DOWNLOAD_TWITTER_IMAGES)
        viewModel.updateTwitterLink(LINK)
        viewModel.downloadTwitterImages()
        return if (expectPublicCopy) awaitPublicCopy(viewModel) else awaitOutcome(viewModel)
    }

    private suspend fun awaitOutcome(viewModel: NatsukoViewModel): NatsukoUiState = awaitState(viewModel) {
        !it.isProcessing && !it.isSavingToUserStorage && (it.outcome != null || it.errorMessage != null)
    }.also {
        assertNull(it.errorMessage)
        assertEquals(AppScreen.RESULT, it.screen)
        assertNotNull(it.outcome)
        outputs.addAll(it.outcome!!.outputs)
    }

    private suspend fun awaitPublicCopy(viewModel: NatsukoViewModel): NatsukoUiState = awaitState(viewModel) {
        !it.isProcessing && !it.isSavingToUserStorage &&
            (it.outcome?.outputs?.isNotEmpty() == true && it.outcome.outputs.all { out -> out.userStorage != null } ||
                it.errorMessage != null || it.storageFailures.isNotEmpty())
    }.also {
        assertNull(it.errorMessage)
        assertTrue(it.storageFailures.isEmpty())
        assertTrue(it.outcome!!.outputs.all { out -> out.userStorage != null })
        outputs.addAll(it.outcome.outputs)
    }

    private suspend fun awaitState(
        viewModel: NatsukoViewModel,
        predicate: (NatsukoUiState) -> Boolean,
    ) = withTimeout(30_000) { viewModel.state.first(predicate) }

    private fun assertPublicCopy(artifact: OutputArtifact) {
        val uri = checkNotNull(artifact.userStorage).uri
        assertEquals("content", uri.scheme)
        assertArrayEquals(
            artifact.file.readBytes(),
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
        )
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE),
            null,
            null,
            null,
        ).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertTrue(cursor.getString(0).startsWith("${Environment.DIRECTORY_DOWNLOADS}/Natsuko/"))
            assertEquals(0, cursor.getInt(1))
            assertEquals(artifact.mimeType, cursor.getString(2))
        }
    }

    private fun shareIntent() = Intent(Intent.ACTION_SEND)
        .setComponent(ComponentName(context, SHARE_COMPONENT))
        .setType("text/plain")

    private fun mediaStoreExists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
            it.moveToFirst()
        } == true
    }.getOrDefault(false)

    private class MemorySessions(
        @Volatile var value: TwitterSession? = null,
    ) : TwitterSessionStore {
        override fun read(): TwitterSession? = value
        override fun write(session: TwitterSession) { value = session }
        override fun clear() { value = null }
    }

    companion object {
        private const val SHARE_COMPONENT = "com.poppinzhang.natsuko.share.DownloadTwitterImagesShare"
        private const val LINK = "https://x.com/natsuko/status/1234567890123456789"
        private const val SECOND_LINK = "https://twitter.com/natsuko/status/1234567890123456790"
    }
}
