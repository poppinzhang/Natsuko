package com.poppinzhang.natsuko

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputRepository
import com.poppinzhang.natsuko.data.twitter.SyndicationTwitterVideoSource
import com.poppinzhang.natsuko.data.twitter.TwitterHttpClient
import com.poppinzhang.natsuko.data.twitter.TwitterImage
import com.poppinzhang.natsuko.data.twitter.TwitterImageSource
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.model.InputItem
import com.poppinzhang.natsuko.model.MergeDirection
import com.poppinzhang.natsuko.model.MergeOptions
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.processing.DownloadTwitterImagesProcessor
import com.poppinzhang.natsuko.processing.ImageMergeProcessor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadTwitterImagesProcessorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputRepository = OutputRepository(context)
    private val root = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Natsuko")
    private val initialFiles = root.walkTopDown().toSet()
    private val url = "https://x.com/user/status/123/photo/2"

    @After
    fun cleanup() {
        root.walkBottomUp().filterNot { it in initialFiles }.forEach { it.delete() }
    }

    @Test
    fun downloadsAllImagesInOrderAndChainsDirectlyToImageMerge() = runBlocking {
        val result = DownloadTwitterImagesProcessor(
            context,
            outputRepository,
            fakeSource(listOf("png-red", "jpeg-green", "webp-blue")),
        ).process(url) { _, _, _ -> }

        assertTrue(result.failures.isEmpty())
        assertEquals(3, result.outputs.size)
        assertEquals(
            listOf("twitter_123_1.png", "twitter_123_2.jpg", "twitter_123_3.webp"),
            result.outputs.map { it.file.name },
        )
        assertEquals(listOf("image/png", "image/jpeg", "image/webp"), result.outputs.map { it.mimeType })
        assertTrue(result.outputs.all { it.file.path.contains("Natsuko/DownloadTwitterImages/") })

        val inputs = result.outputs.map { artifact ->
            InputItem(outputRepository.shareUri(artifact), artifact.file.name, artifact.mimeType)
        }
        val merged = ImageMergeProcessor(context, outputRepository).process(
            inputs,
            MergeOptions(direction = MergeDirection.HORIZONTAL),
        ) { _, _, _ -> }

        assertTrue(merged.failures.isEmpty())
        val mergedFile = merged.outputs.single().file
        val bitmap = BitmapFactory.decodeFile(mergedFile.path)
        assertEquals(30, bitmap.width)
        assertEquals(10, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(5, 5))
        val jpegPixel = bitmap.getPixel(15, 5)
        assertTrue(Color.red(jpegPixel) < 15 && Color.green(jpegPixel) > 240 && Color.blue(jpegPixel) < 15)
        assertEquals(Color.BLUE, bitmap.getPixel(25, 5))
        bitmap.recycle()
        assertTrue(result.outputs.all { it.file.exists() })
    }

    @Test
    fun partialFailuresContinueAndKeepOriginalImageNumbers() = runBlocking {
        val progress = mutableListOf<Int>()
        val result = DownloadTwitterImagesProcessor(
            context,
            outputRepository,
            fakeSource(listOf("network", "png-red", "corrupt", "jpeg-green", "unsupported")),
        ).process(url) { completed, total, _ ->
            assertEquals(1000, total)
            progress += completed
        }

        assertEquals(2, result.outputs.size)
        assertEquals(3, result.failures.size)
        assertEquals(listOf("twitter_123_2.png", "twitter_123_4.jpg"), result.outputs.map { it.file.name })
        assertEquals(progress.sorted(), progress)
        assertEquals(1000, progress.last())
        assertEquals(2, result.outputs.first().file.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun allFailedImagesReturnDiagnosticsAndRemoveRunDirectory() = runBlocking {
        val result = DownloadTwitterImagesProcessor(
            context,
            outputRepository,
            fakeSource(listOf("network", "corrupt")),
        ).process(url) { _, _, _ -> }

        assertTrue(result.outputs.isEmpty())
        assertEquals(2, result.failures.size)
        assertFalse(newFiles().any { it.isFile || it.parentFile?.name == "DownloadTwitterImages" })
    }

    @Test
    fun cancellationAfterFirstImageRemovesEntireAbandonedBatch() = runBlocking {
        val failure = runCatching {
            DownloadTwitterImagesProcessor(
                context,
                outputRepository,
                fakeSource(listOf("png-red", "jpeg-green")),
            ).process(url) { _, _, message ->
                if (message == UiText.Resource(R.string.backend_images_processed, listOf(1, 2))) {
                    throw CancellationException("Cancelled")
                }
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertFalse(newFiles().any { it.isFile })
    }

    @Test
    fun syndicationSourceFallsBackToOriginalUrlWhenOrigVariantReturns404() = runBlocking {
        val pngBytes = encodeBitmap(Color.CYAN, Bitmap.CompressFormat.PNG)
        val requestedUrls = mutableListOf<String>()
        val http = TwitterHttpClient({ url ->
            requestedUrls += url.toString()
            val missing = url.query?.contains("name=orig") == true
            val body = if (missing) ByteArray(0) else pngBytes
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = if (missing) 404 else 200
                override fun getContentLengthLong() = body.size.toLong()
                override fun getContentType() = if (missing) "text/plain" else "image/png"
                override fun getInputStream() = ByteArrayInputStream(body)
                override fun getErrorStream() = ByteArrayInputStream(body)
            }
        })
        val source = SyndicationTwitterVideoSource(http)
        val destination = File(context.cacheDir, "fallback-test.part").apply { delete() }
        try {
            source.downloadImage(
                TwitterImage(
                    url = "https://pbs.twimg.com/media/Test?format=png&name=orig",
                    fallbackUrl = "https://pbs.twimg.com/media/Test.png",
                    formatExtension = "png",
                ),
                destination,
            ) { _, _ -> }
            assertEquals(2, requestedUrls.size)
            assertTrue(requestedUrls[0].contains("name=orig"))
            assertEquals("https://pbs.twimg.com/media/Test.png", requestedUrls[1])
            assertTrue(pngBytes.contentEquals(destination.readBytes()))
        } finally {
            destination.delete()
        }
    }

    private fun newFiles() = root.walkTopDown().filterNot { it in initialFiles }

    private fun fakeSource(kinds: List<String>) = object : TwitterImageSource {
        override suspend fun resolveImages(link: TwitterStatusLink): List<TwitterImage> {
            assertEquals("123", link.id)
            return kinds.mapIndexed { index, kind ->
                TwitterImage(
                    url = if (kind == "unsupported") null else kind,
                    formatExtension = if (kind.startsWith("png")) "png" else "jpg",
                    unavailableReason = UiText.Literal("No image URL"),
                    mediaIndex = index + 1,
                )
            }
        }

        override suspend fun downloadImage(image: TwitterImage, destination: File, onBytes: (Long, Long?) -> Unit) {
            when (image.url) {
                "network" -> {
                    destination.writeText("partial")
                    throw IOException("Network dropped")
                }
                "corrupt" -> destination.writeText("Not an image")
                "png-red" -> destination.writeBytes(encodeBitmap(Color.RED, Bitmap.CompressFormat.PNG))
                "jpeg-green" -> destination.writeBytes(encodeBitmap(Color.GREEN, Bitmap.CompressFormat.JPEG))
                "webp-blue" -> destination.writeBytes(encodeBitmap(Color.BLUE, Bitmap.CompressFormat.WEBP_LOSSLESS))
            }
            onBytes(destination.length(), destination.length())
        }
    }

    private fun encodeBitmap(color: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val output = ByteArrayOutputStream()
        bitmap.compress(format, 100, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
