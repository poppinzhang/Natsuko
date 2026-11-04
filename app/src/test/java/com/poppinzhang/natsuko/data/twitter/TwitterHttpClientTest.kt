package com.poppinzhang.natsuko.data.twitter

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.text
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TwitterHttpClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://video.twimg.com/ext_tw_video/123/vid/640x360/sample.mp4?tag=12"

    @Test
    fun streamsBytesAndReportsProgressWithoutLoadingWholeVideo() = runBlocking {
        val body = ByteArray(150_000) { (it % 251).toByte() }
        val connection = FakeConnection(body)
        val target = File(temporary.root, "video.part")
        val progress = mutableListOf<Long>()
        TwitterHttpClient({ connection }).download(url, target) { count, total ->
            assertEquals(body.size.toLong(), total)
            progress += count
        }
        assertTrue(body.contentEquals(target.readBytes()))
        assertTrue(progress.size > 1)
        assertEquals(progress.sorted(), progress)
        assertEquals(body.size.toLong(), progress.last())
        assertTrue(connection.disconnected)
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test
    fun oversizedTruncatedEmptyAndWrongContentTypeDownloadsAreRemoved() = runBlocking {
        val fixtures = listOf(
            FakeConnection(ByteArray(10)).apply { length = 200 },
            FakeConnection(ByteArray(150)).apply { length = -1 },
            FakeConnection(ByteArray(10)).apply { length = 20 },
            FakeConnection(ByteArray(0)),
            FakeConnection(ByteArray(10)).apply { mime = "text/html" },
        )
        for (connection in fixtures) {
            val target = File(temporary.root, "bad.part")
            assertNotNull(runCatching { TwitterHttpClient({ connection }, maxVideoBytes = 100).download(url, target) { _, _ -> } }.exceptionOrNull())
            assertFalse(target.exists())
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun reportsHttpErrorsAndRefusesOffDomainRedirectsBeforeConnecting() = runBlocking {
        for (status in listOf(403, 404, 429, 503)) {
            val connection = FakeConnection(ByteArray(1)).apply { code = status }
            val failure = runCatching { TwitterHttpClient({ connection }).download(url, File(temporary.root, "bad.part")) { _, _ -> } }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(connection.disconnected)
        }
        var connections = 0
        val redirect = FakeConnection(ByteArray(1)).apply { code = 302; location = "https://example.com/video.mp4" }
        val failure = runCatching {
            TwitterHttpClient({ connections++; redirect }).download(url, File(temporary.root, "bad.part")) { _, _ -> }
        }.exceptionOrNull()
        assertEquals(text(R.string.backend_download_url_unsupported), (failure as? LocalizedFailure)?.userMessage)
        assertEquals(1, connections)
    }

    @Test
    fun rejectsNonHttpsAndDisguisedMediaUrls() = runBlocking {
        for (untrusted in listOf(
            "http://video.twimg.com/v.mp4", "https://video.twimg.com.evil.test/v.mp4",
            "https://video.twimg.com@localhost/v.mp4", "https://video.twimg.com:1234/v.mp4",
            "https://video.twimg.com/v.m3u8", "file:///tmp/video.mp4",
        )) {
            var connected = false
            val failure = runCatching {
                TwitterHttpClient({ connected = true; FakeConnection(ByteArray(1)) })
                    .download(untrusted, File(temporary.root, "bad.part")) { _, _ -> }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(connected)
        }
    }

    @Test
    fun cancellationDisconnectsBlockedReadAndDeletesPartialFile() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val connection = object : FakeConnection(ByteArray(1)) {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    started.complete(Unit)
                    if (!released.await(3, TimeUnit.SECONDS)) throw SocketTimeoutException()
                    throw IOException("Disconnected")
                }
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val target = File(temporary.root, "cancel.part")
        withTimeout(2_000) {
            val transfer = async { TwitterHttpClient({ connection }).download(url, target) { _, _ -> } }
            started.await()
            transfer.cancelAndJoin()
            assertTrue(transfer.isCancelled)
        }
        assertTrue(connection.disconnected)
        assertFalse(target.exists())
    }

    private open class FakeConnection(body: ByteArray) : HttpURLConnection(URL("https://video.twimg.com/test.mp4")) {
        private val input = ByteArrayInputStream(body)
        var length = body.size.toLong()
        var code = 200
        var mime = "video/mp4"
        var location: String? = null
        @Volatile var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getInputStream(): InputStream = input
        override fun getResponseCode() = code
        override fun getContentLengthLong() = length
        override fun getContentType() = mime
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
    }
}
