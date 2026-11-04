package com.poppinzhang.natsuko

import android.app.Application
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
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
import com.poppinzhang.natsuko.model.Feature
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.OutputArtifact
import com.poppinzhang.natsuko.model.ProcessingFailure
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
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

/** Network responses are deterministic; storage publishing and FFmpeg conversion are real. */
@RunWith(AndroidJUnit4::class)
class TwitterDownloadWorkflowTest {
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
    fun homeOpensLinkInputAndInvalidLinksNeverReachDownloader() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }

        composeRule.onNodeWithTag("home-download-twitter").performScrollTo().performClick()

        assertEquals(AppScreen.TWITTER_DOWNLOAD, viewModel.state.value.screen)
        composeRule.onNodeWithTag("twitter-link-input").assertIsDisplayed()
        assertTrue(viewModel.state.value.inputs.isEmpty())
        for (link in listOf("", "https://example.com/user/status/123", "https://x.com/user", "not a link")) {
            viewModel.dismissError()
            viewModel.updateTwitterLink(link)
            viewModel.downloadTwitterVideo()
            val rejected = awaitState(viewModel) { it.errorMessage != null }
            assertEquals(AppScreen.TWITTER_DOWNLOAD, rejected.screen)
            assertFalse(rejected.isProcessing)
            assertNull(rejected.outcome)
        }
        assertEquals(0, downloadCalls.get())
    }

    @Test
    fun textAndClipDataSharesPrefillLinkWithoutStartingDownload() {
        val text = "看看这个视频 $LINK"
        val intents = listOf(
            shareIntent().putExtra(Intent.EXTRA_TEXT, text),
            shareIntent().apply { clipData = ClipData.newPlainText("Tweet", text) },
            shareIntent().putExtra(Intent.EXTRA_TEXT, text).apply {
                clipData = ClipData.newPlainText("Tweet", text)
            },
        )
        for (intent in intents) {
            val viewModel = newViewModel()

            viewModel.handleShareIntent(intent)

            val state = viewModel.state.value
            assertEquals(AppScreen.TWITTER_DOWNLOAD, state.screen)
            assertEquals(Feature.DOWNLOAD_TWITTER_VIDEO, state.feature)
            assertEquals(text, state.twitterLink)
            assertTrue(state.inputs.isEmpty())
            assertFalse(state.isProcessing)
            assertNull(state.outcome)
            assertNull(state.errorMessage)
        }
        assertEquals(0, downloadCalls.get())
    }

    @Test
    fun unsupportedAndMalformedTextSharesFailWithoutImportingOrDownloading() {
        val intents = listOf(
            shareIntent(),
            shareIntent().putExtra(Intent.EXTRA_TEXT, 42),
            shareIntent().putExtra(Intent.EXTRA_TEXT, "https://example.com/video.mp4"),
            shareIntent().putExtra(Intent.EXTRA_TEXT, "x".repeat(8193)),
            shareIntent().setType("video/mp4").putExtra(Intent.EXTRA_TEXT, LINK),
            shareIntent().setAction(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_TEXT, LINK),
            shareIntent().setAction(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, LINK),
            shareIntent().apply { clipData = ClipData.newRawUri("Tweet", Uri.parse(LINK)) },
        )
        for (intent in intents) {
            val viewModel = newViewModel()

            viewModel.handleShareIntent(intent)

            val state = viewModel.state.value
            assertEquals(AppScreen.HOME, state.screen)
            assertFalse(state.errorMessage?.resolve(context).isNullOrBlank())
            assertFalse(state.isProcessing)
            assertTrue(state.inputs.isEmpty())
            assertNull(state.outcome)
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
                    assertNull("Unexpected Twitter target for $action and $mimeType", alias)
                }
            }
        }
    }

    @Test
    fun resultSwitchPublishesPrivateVideoAndAutomaticallySavesNextDownload() = runBlocking {
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val first = download(viewModel).outcome!!.outputs.single()
        val picturesRoot = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, Environment.DIRECTORY_PICTURES)
        assertTrue(first.file.canonicalPath.startsWith(picturesRoot.canonicalPath + File.separator))
        assertTrue(first.file.isFile)
        assertNull(first.userStorage)

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("result-user-storage-switch"))
        composeRule.onNodeWithTag("result-user-storage-switch").assertIsOff().performClick()
        val saved = awaitPublicCopy(viewModel)

        assertTrue(preferences.read().enabled)
        assertTrue(saved.storageFailures.isEmpty())
        assertPublicCopy(saved.outcome!!.outputs.single())
        val second = download(viewModel, expectPublicCopy = true)
        assertPublicCopy(second.outcome!!.outputs.single())
        assertTrue(first.file != second.outcome.outputs.single().file)
        assertEquals(2, downloadCalls.get())
    }

    @Test
    fun expiredStoragePermissionPreservesVideoAndCanRetryWithoutDownloadingAgain() = runBlocking {
        preferences.setDirectory(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANatsuko-twitter-no-grant"))
        preferences.setEnabled(true)
        val viewModel = newViewModel()
        viewModel.openTwitterDownload()
        viewModel.updateTwitterLink(LINK)
        viewModel.downloadTwitterVideo()
        val failed = awaitState(viewModel) { !it.isSavingToUserStorage && it.storageFailures.isNotEmpty() }
        outputs.addAll(failed.outcome!!.outputs)
        val original = failed.outcome.outputs.single()

        assertEquals(AppScreen.RESULT, failed.screen)
        assertTrue(original.file.isFile)
        assertNull(original.userStorage)
        viewModel.retryUserStorageSave()
        assertTrue(viewModel.state.value.directoryPickerRequested)
        viewModel.onDirectoryPickerLaunched()
        viewModel.onUserStorageDirectorySelected(null)
        assertTrue(original.file.isFile)

        viewModel.restoreDefaultUserStorageDirectory()
        val retried = awaitPublicCopy(viewModel)

        assertTrue(retried.storageFailures.isEmpty())
        assertEquals(original.file, retried.outcome!!.outputs.single().file)
        assertPublicCopy(retried.outcome.outputs.single())
        assertEquals(1, downloadCalls.get())
    }

    @Test
    fun resultConvertsPrivateVideoWithFfmpegAndRetainsOriginalAndDownloadResults() = runBlocking {
        preferences.setEnabled(true)
        val viewModel = newViewModel()
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val downloaded = download(viewModel, expectPublicCopy = true)
        val original = downloaded.outcome!!.outputs.single()
        val originalBytes = original.file.readBytes()
        // A public copy may be deleted or lose its grant while the private source remains usable.
        context.contentResolver.delete(original.userStorage!!.uri, null, null)

        composeRule.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("twitter-convert-gif-0"))
        composeRule.onNodeWithTag("twitter-convert-gif-0").performClick()

        val configured = viewModel.state.value
        assertEquals(AppScreen.CONFIGURE, configured.screen)
        assertEquals(Feature.VIDEO_TO_GIF, configured.feature)
        assertEquals(1, configured.inputs.size)
        assertEquals("content", configured.inputs.single().uri.scheme)
        assertEquals("${context.packageName}.files", configured.inputs.single().uri.authority)
        assertEquals(original.file.name, configured.inputs.single().displayName)
        assertNull(configured.outcome)
        composeRule.onNodeWithText(context.getString(R.string.main_gif_settings)).performScrollTo().assertIsDisplayed()
        viewModel.updateGifWidth(240)
        viewModel.updateGifInterval(200)
        viewModel.process()
        val converted = awaitPublicCopy(viewModel)
        val gif = converted.outcome!!.outputs.single()

        assertEquals("image/gif", gif.mimeType)
        assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(gif.file)) is AnimatedImageDrawable)
        assertPublicCopy(gif)
        assertArrayEquals(originalBytes, original.file.readBytes())
        assertEquals(1, downloadCalls.get())

        viewModel.back()

        assertEquals(AppScreen.RESULT, viewModel.state.value.screen)
        assertEquals(Feature.DOWNLOAD_TWITTER_VIDEO, viewModel.state.value.feature)
        assertEquals(original.file, viewModel.state.value.outcome!!.outputs.single().file)
    }

    @Test
    fun failedDownloadCanReturnToInputAndRetryWithEditedLink() = runBlocking {
        val viewModel = newViewModel { _, progress ->
            if (downloadCalls.get() == 1) {
                ProcessingOutcome(emptyList(), listOf(ProcessingFailure(LINK, "推文中没有可下载的视频")))
            } else {
                successfulDownload(progress)
            }
        }
        composeRule.setContent { NatsukoTheme { NatsukoApp(viewModel) } }
        val failed = download(viewModel)
        assertTrue(failed.outcome!!.outputs.isEmpty())
        assertEquals(1, failed.outcome.failures.size)

        composeRule.onNodeWithTag("twitter-download-again").performClick()
        assertEquals(LINK, viewModel.state.value.twitterLink)
        composeRule.onNodeWithTag("twitter-link-input").performTextReplacement(SECOND_LINK)
        composeRule.onNodeWithTag("twitter-download-start").performClick()
        val retried = awaitOutcome(viewModel)

        assertEquals(SECOND_LINK, retried.twitterLink)
        assertTrue(retried.outcome!!.failures.isEmpty())
        assertTrue(retried.outcome.outputs.single().file.isFile)
        assertEquals(2, downloadCalls.get())
    }

    @Test
    fun activeDownloadBlocksReplacementAndCanBeCancelled() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val viewModel = newViewModel { _, progress ->
            progress(1, 10, UiText.Literal("Downloading test video"))
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        viewModel.openTwitterDownload()
        viewModel.updateTwitterLink(LINK)
        viewModel.downloadTwitterVideo()
        withTimeout(10_000) { started.await() }

        viewModel.downloadTwitterVideo()
        viewModel.openSettings()
        viewModel.back()
        viewModel.openTwitterDownload()
        viewModel.updateTwitterLink(SECOND_LINK)
        viewModel.handleShareIntent(shareIntent().putExtra(Intent.EXTRA_TEXT, SECOND_LINK))

        val busy = viewModel.state.value
        assertTrue(busy.isProcessing)
        assertEquals(AppScreen.TWITTER_DOWNLOAD, busy.screen)
        assertEquals(LINK, busy.twitterLink)
        assertEquals(1, downloadCalls.get())
        assertEquals(1, busy.progress.completed)
        viewModel.cancelProcessing()
        val stopped = awaitState(viewModel) { !it.isProcessing }

        assertTrue(cancelled.get())
        assertEquals(LINK, stopped.twitterLink)
        assertNull(stopped.outcome)
    }

    @Test
    fun cancellingCompletedDownloadBeforeResultPublicationRemovesUnpublishedFiles() = runBlocking {
        val cancelledOutput = CompletableDeferred<OutputArtifact>()
        lateinit var viewModel: NatsukoViewModel
        viewModel = newViewModel { _, progress ->
            val outcome = successfulDownload(progress)
            val artifact = outcome.outputs.single()
            assertTrue(artifact.file.isFile)
            // Cancel after the file is complete but before withContext can deliver its result.
            // Do not suspend or report progress after cancellation: return the completed outcome.
            viewModel.cancelProcessing()
            cancelledOutput.complete(artifact)
            outcome
        }
        viewModel.openTwitterDownload()
        viewModel.updateTwitterLink(LINK)
        viewModel.downloadTwitterVideo()
        val artifact = withTimeout(10_000) { cancelledOutput.await() }

        val stopped = awaitState(viewModel) { !it.isProcessing }

        assertFalse(artifact.file.exists())
        assertFalse(checkNotNull(artifact.file.parentFile).exists())
        assertNull(stopped.outcome)
        assertEquals(AppScreen.TWITTER_DOWNLOAD, stopped.screen)
        assertEquals(1, downloadCalls.get())
    }

    private fun newViewModel(
        download: suspend (String, (Int, Int, UiText) -> Unit) -> ProcessingOutcome =
            { _, progress -> successfulDownload(progress) },
    ): NatsukoViewModel {
        lateinit var viewModel: NatsukoViewModel
        instrumentation.runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NatsukoViewModel(context.applicationContext as Application) { link, progress ->
                        downloadCalls.incrementAndGet()
                        download(link, progress)
                    } as T
            }
            viewModel = ViewModelProvider(store, factory)["twitter-workflow-${viewModels.size}", NatsukoViewModel::class.java]
        }
        viewModels += viewModel
        return viewModel
    }

    private fun successfulDownload(progress: (Int, Int, UiText) -> Unit): ProcessingOutcome {
        progress(0, 1, UiText.Literal("Downloading test video"))
        val directory = OutputRepository(context).createRunDirectory(Feature.DOWNLOAD_TWITTER_VIDEO)
        val file = directory.resolve("twitter-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("video/h264.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val artifact = OutputArtifact(file, "video/mp4", "推文视频")
        outputs.add(artifact)
        progress(1, 1, UiText.Literal("Test download completed"))
        return ProcessingOutcome(listOf(artifact))
    }

    private suspend fun download(
        viewModel: NatsukoViewModel,
        expectPublicCopy: Boolean = false,
    ): NatsukoUiState {
        viewModel.openTwitterDownload()
        viewModel.updateTwitterLink(LINK)
        viewModel.downloadTwitterVideo()
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
            (it.outcome?.outputs?.singleOrNull()?.userStorage != null || it.errorMessage != null || it.storageFailures.isNotEmpty())
    }.also {
        assertNull(it.errorMessage)
        assertTrue(it.storageFailures.isEmpty())
        assertNotNull(it.outcome?.outputs?.singleOrNull()?.userStorage)
        outputs.addAll(it.outcome!!.outputs)
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

    companion object {
        private const val SHARE_COMPONENT = "com.poppinzhang.natsuko.share.DownloadTwitterVideoShare"
        private const val LINK = "https://x.com/natsuko/status/1234567890123456789"
        private const val SECOND_LINK = "https://twitter.com/natsuko/status/1234567890123456790"
    }
}
