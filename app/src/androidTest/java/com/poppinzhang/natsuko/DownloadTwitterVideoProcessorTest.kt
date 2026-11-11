package com.poppinzhang.natsuko

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.data.twitter.TwitterDownloadScope
import com.poppinzhang.natsuko.data.twitter.TwitterVideo
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSelection
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSource
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.GifOptions
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.processing.DownloadTwitterVideoProcessor
import com.poppinzhang.natsuko.processing.VideoToGifProcessor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadTwitterVideoProcessorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val outputRepository = OutputRepository(context)
    private val root = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Natsuko")
    private val initialFiles = root.walkTopDown().toSet()
    private val url = "https://x.com/user/status/123"

    @After
    fun cleanup() {
        root.walkBottomUp().filterNot { it in initialFiles }.forEach { it.delete() }
    }

    @Test
    fun downloadsAndVerifiesSingleVideoThenConvertsToGifWithoutAnotherPicker() = runBlocking {
        val result = DownloadTwitterVideoProcessor(context, outputRepository, fakeSource(listOf("good")))
            .process(url) { _, _, _ -> }
        assertTrue(result.failures.isEmpty())
        val artifact = result.outputs.single()
        assertEquals("video/mp4", artifact.mimeType)
        assertEquals("twitter_123_1.mp4", artifact.file.name)
        assertTrue(artifact.file.path.contains("Natsuko/DownloadTwitterVideo/"))
        val input = InputItem(outputRepository.shareUri(artifact), artifact.file.name, artifact.mimeType)
        val gif = VideoToGifProcessor(context, outputRepository).process(input, GifOptions(maxWidth = 64, maxFrames = 5)) { _, _, _ -> }
        assertEquals("image/gif", gif.outputs.single().mimeType)
        assertTrue(gif.outputs.single().file.length() > 0)
        assertTrue(artifact.file.exists())
    }

    @Test
    fun partialFailuresContinueAndOnlyVerifiedMp4FilesRemain() = runBlocking {
        val progress = mutableListOf<Int>()
        val result = DownloadTwitterVideoProcessor(context, outputRepository, fakeSource(listOf("network", "good", "corrupt", "gif", "unsupported")))
            .process(url) { completed, total, _ -> assertEquals(1000, total); progress += completed }
        assertEquals(2, result.outputs.size)
        assertEquals(3, result.failures.size)
        assertEquals(listOf("twitter_123_2.mp4", "twitter_123_4.mp4"), result.outputs.map { it.file.name })
        assertTrue(result.outputs[1].detail!!.resolve(context).contains("MP4"))
        assertEquals(progress.sorted(), progress)
        assertEquals(1000, progress.last())
        assertEquals(2, result.outputs.first().file.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun allFailedVideosReturnFileDiagnosticsAndRemoveRunDirectory() = runBlocking {
        val result = DownloadTwitterVideoProcessor(context, outputRepository, fakeSource(listOf("network", "corrupt")))
            .process(url) { _, _, _ -> }
        assertTrue(result.outputs.isEmpty())
        assertEquals(2, result.failures.size)
        assertFalse(newFiles().any { it.isFile || it.parentFile?.name == "DownloadTwitterVideo" })
    }

    @Test
    fun cancellationAfterFirstDownloadRemovesEntireAbandonedBatch() = runBlocking {
        val failure = runCatching {
            DownloadTwitterVideoProcessor(context, outputRepository, fakeSource(listOf("good", "good")))
                .process(url) { _, _, message -> if (message == text(R.string.backend_videos_processed, 1, 2)) throw CancellationException("Cancelled") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(newFiles().any { it.isFile })
    }

    @Test
    fun currentVideoUsesAttachmentPositionAndPreservesOriginalVideoNumber() = runBlocking {
        val downloaded = mutableListOf<String?>()
        val prompts = mutableListOf<TwitterVideoSelection>()
        val source = fakeSource(listOf("network", "good"), listOf(2, 4)) { downloaded += it.url }
        val result = DownloadTwitterVideoProcessor(context, outputRepository, source).process(
            "$url/video/4",
            chooseScope = { selection ->
                prompts += selection
                assertTrue(downloaded.isEmpty())
                assertFalse(newFiles().any())
                TwitterDownloadScope.CURRENT
            },
        ) { _, _, _ -> }
        assertEquals(listOf(TwitterVideoSelection(4, 2)), prompts)
        assertEquals(listOf("good"), downloaded)
        assertTrue(result.failures.isEmpty())
        assertEquals("twitter_123_2.mp4", result.outputs.single().file.name)
    }

    @Test
    fun allVideosChoiceRetainsPartialFailuresAndOriginalNumbers() = runBlocking {
        val downloaded = mutableListOf<String?>()
        val result = DownloadTwitterVideoProcessor(
            context, outputRepository, fakeSource(listOf("network", "good")) { downloaded += it.url },
        ).process("$url/video/2", { TwitterDownloadScope.ALL }) { _, _, _ -> }
        assertEquals(listOf("network", "good"), downloaded)
        assertEquals(1, result.failures.size)
        assertEquals("twitter_123_2.mp4", result.outputs.single().file.name)
    }

    @Test
    fun unavailableCurrentVideoDoesNotDownloadOtherAttachments() = runBlocking {
        val downloaded = mutableListOf<String?>()
        val result = DownloadTwitterVideoProcessor(
            context, outputRepository, fakeSource(listOf("unsupported", "good")) { downloaded += it.url },
        ).process("$url/video/1", { TwitterDownloadScope.CURRENT }) { _, _, _ -> }
        assertTrue(downloaded.isEmpty())
        assertTrue(result.outputs.isEmpty())
        assertEquals(1, result.failures.size)
        assertFalse(newFiles().any { it.isFile })
    }

    @Test
    fun singleVideoAndWholePostLinksDoNotPrompt() = runBlocking {
        for ((link, types, indices) in listOf(
            Triple("$url/video/2", listOf("good"), listOf(2)),
            Triple(url, listOf("good", "gif"), listOf(1, 2)),
            Triple("$url/photo/1", listOf("good", "gif"), listOf(2, 3)),
        )) {
            val result = DownloadTwitterVideoProcessor(context, outputRepository, fakeSource(types, indices)).process(
                link, { error("Unexpected selection prompt") },
            ) { _, _, _ -> }
            assertEquals(types.size, result.outputs.size)
            assertTrue(result.failures.isEmpty())
        }
    }

    @Test
    fun invalidOrPhotoAttachmentPositionFailsBeforePromptAndDownload() = runBlocking {
        val downloaded = mutableListOf<String?>()
        for (mediaIndex in listOf(1, 3, 5)) {
            val failure = runCatching {
                DownloadTwitterVideoProcessor(
                    context, outputRepository, fakeSource(listOf("good", "gif"), listOf(2, 4)) { downloaded += it.url },
                ).process("$url/video/$mediaIndex", { error("Unexpected selection prompt") }) { _, _, _ -> }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
        assertTrue(downloaded.isEmpty())
        assertFalse(newFiles().any())
    }

    @Test
    fun cancellingPendingSelectionNeverDownloadsOrCreatesOutputDirectory() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val decision = CompletableDeferred<TwitterDownloadScope>()
        val downloaded = mutableListOf<String?>()
        val job = launch {
            DownloadTwitterVideoProcessor(
                context, outputRepository, fakeSource(listOf("good", "gif")) { downloaded += it.url },
            ).process("$url/video/2", {
                requested.complete(Unit)
                decision.await()
            }) { _, _, _ -> }
        }
        withTimeout(5_000) { requested.await() }
        assertTrue(downloaded.isEmpty())
        assertFalse(newFiles().any())
        job.cancelAndJoin()
        assertTrue(downloaded.isEmpty())
        assertFalse(newFiles().any())
    }

    private fun newFiles() = root.walkTopDown().filterNot { it in initialFiles }

    private fun fakeSource(
        types: List<String>,
        mediaIndices: List<Int?> = List(types.size) { null },
        onDownload: (TwitterVideo) -> Unit = {},
    ) = object : TwitterVideoSource {
        override suspend fun resolve(link: TwitterStatusLink): List<TwitterVideo> {
            assertEquals("123", link.id)
            return types.mapIndexed { index, type ->
                TwitterVideo(if (type == "unsupported") null else type, type == "gif", UiText.Literal("No MP4 test video"), mediaIndices[index])
            }
        }
        override suspend fun download(video: TwitterVideo, destination: File, onBytes: (Long, Long?) -> Unit) {
            onDownload(video)
            when (video.url) {
                "network" -> { destination.writeText("partial"); throw IOException("网络连接中断") }
                "corrupt" -> destination.writeText("Not a real MP4")
                else -> instrumentation.context.assets.open("video/h264.mp4").use { source ->
                    destination.outputStream().use { source.copyTo(it) }
                }
            }
            onBytes(destination.length(), destination.length())
        }
    }
}
