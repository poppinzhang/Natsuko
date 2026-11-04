package com.poppinzhang.natsuko

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.data.twitter.TwitterVideo
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
import kotlinx.coroutines.runBlocking
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

    private fun newFiles() = root.walkTopDown().filterNot { it in initialFiles }

    private fun fakeSource(types: List<String>) = object : TwitterVideoSource {
        override suspend fun resolve(link: TwitterStatusLink): List<TwitterVideo> {
            assertEquals("123", link.id)
            return types.map { TwitterVideo(if (it == "unsupported") null else it, it == "gif", UiText.Literal("No MP4 test video")) }
        }
        override suspend fun download(video: TwitterVideo, destination: File, onBytes: (Long, Long?) -> Unit) {
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
