package com.poppinzhang.natsuko.data.update

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedFailure
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateHttpClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packageUrl = "https://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/Natsuko-1.4.0.apk"
    private val storageUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=abc"
    private val target get() = File(temporary.root, "update.apk.part")

    @Test
    fun latestReleaseIsReadFromTheApiWithIdentifyingHeaders() = runBlocking {
        val connection = FakeConnection("""{"tag_name":"v1.4.0"}""".toByteArray()).apply { length = -1 }
        val requested = mutableListOf<URL>()

        val json = UpdateHttpClient({ requested += it; connection }).latestRelease()

        assertEquals("""{"tag_name":"v1.4.0"}""", json)
        assertEquals(listOf(URL("https://api.github.com/repos/poppinzhang/Natsuko/releases/latest")), requested)
        assertEquals("Natsuko", connection.getRequestProperty("User-Agent"))
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test
    fun apiFailuresExplainMissingReleasesRateLimitsAndServerErrors() = runBlocking {
        val expected = mapOf(
            404 to text(R.string.update_error_no_release),
            403 to text(R.string.update_error_rate_limited),
            429 to text(R.string.update_error_rate_limited),
            500 to text(R.string.update_error_http, 500),
            204 to text(R.string.update_error_http, 204),
        )
        for ((status, message) in expected) {
            val connection = FakeConnection(ByteArray(1)).apply { code = status }
            assertEquals("HTTP $status", message, failure { UpdateHttpClient({ connection }).latestRelease() })
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun releaseInformationFollowsRedirectsOnlyWithinTheApiHost() = runBlocking {
        val moved = FakeConnection(ByteArray(0)).apply {
            code = 301
            location = "/repositories/42/releases/latest"
        }
        val requested = mutableListOf<String>()
        val json = UpdateHttpClient({ url ->
            requested += url.toString()
            if (requested.size == 1) moved else FakeConnection("{}".toByteArray())
        }).latestRelease()
        assertEquals("{}", json)
        assertEquals("https://api.github.com/repositories/42/releases/latest", requested.last())

        for (destination in listOf("https://example.com/latest", packageUrl, "http://api.github.com/latest")) {
            var connections = 0
            val redirect = FakeConnection(ByteArray(0)).apply { code = 302; location = destination }
            assertEquals(
                destination,
                text(R.string.update_error_redirect),
                failure { UpdateHttpClient({ connections++; redirect }).latestRelease() },
            )
            assertEquals(1, connections)
        }
    }

    @Test
    fun oversizedReleaseInformationIsRejected() = runBlocking {
        val declared = FakeConnection(ByteArray(8)).apply { length = 2L * 1024 * 1024 }
        val undeclared = FakeConnection(ByteArray(1024 * 1024 + 1)).apply { length = -1 }
        for (connection in listOf(declared, undeclared)) {
            assertEquals(
                text(R.string.update_error_invalid_response),
                failure { UpdateHttpClient({ connection }).latestRelease() },
            )
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun downloadFollowsGitHubToItsReleaseStorageAndStreamsTheExactPackage() = runBlocking {
        val body = ByteArray(150_000) { (it % 251).toByte() }
        val redirect = FakeConnection(ByteArray(0)).apply { code = 302; location = storageUrl }
        val storage = FakeConnection(body)
        val requested = mutableListOf<String>()
        val progress = mutableListOf<Long>()

        UpdateHttpClient({ url ->
            requested += url.toString()
            if (requested.size == 1) redirect else storage
        }).download(packageUrl, body.size.toLong(), target) { progress += it }

        assertEquals(listOf(packageUrl, storageUrl), requested)
        assertTrue(body.contentEquals(target.readBytes()))
        assertTrue(progress.size > 1)
        assertEquals(progress.sorted(), progress)
        assertEquals(body.size.toLong(), progress.last())
        assertEquals("application/octet-stream", storage.getRequestProperty("Accept"))
        assertEquals("identity", storage.getRequestProperty("Accept-Encoding"))
        assertTrue(redirect.disconnected)
        assertTrue(storage.disconnected)
    }

    @Test
    fun downloadsOfAnyOtherLengthLeaveNoFile() = runBlocking {
        val fixtures = listOf(
            // The server announces a different file.
            FakeConnection(ByteArray(100)).apply { length = 120 } to 100L,
            // The stream ends early or carries extra bytes when no length was announced.
            FakeConnection(ByteArray(60)).apply { length = -1 } to 100L,
            FakeConnection(ByteArray(140)).apply { length = -1 } to 100L,
            // The announced length is right but the connection closes early.
            FakeConnection(ByteArray(60)).apply { length = 100 } to 100L,
        )
        for ((connection, size) in fixtures) {
            assertEquals(
                text(R.string.backend_download_incomplete),
                failure { UpdateHttpClient({ connection }).download(packageUrl, size, target) {} },
            )
            assertFalse(target.exists())
            assertTrue(connection.disconnected)
        }
        var connected = false
        val tooLarge = UpdateHttpClient.MAX_PACKAGE_BYTES + 1
        assertEquals(
            text(R.string.backend_download_size_limit, 512L),
            failure { UpdateHttpClient({ connected = true; FakeConnection(ByteArray(1)) }).download(packageUrl, tooLarge, target) {} },
        )
        assertFalse(connected)
    }

    @Test
    fun downloadFailuresReportTheStatusWithoutBlamingRateLimits() = runBlocking {
        val expected = mapOf(
            404 to text(R.string.update_error_no_release),
            403 to text(R.string.update_error_http, 403),
            503 to text(R.string.update_error_http, 503),
        )
        for ((status, message) in expected) {
            val connection = FakeConnection(ByteArray(1)).apply { code = status }
            assertEquals(message, failure { UpdateHttpClient({ connection }).download(packageUrl, 1, target) {} })
            assertFalse(target.exists())
        }
    }

    @Test
    fun onlyHttpsGitHubHostsAreContacted() = runBlocking {
        for (untrusted in listOf(
            "http://github.com/poppinzhang/Natsuko/releases/download/v1.4.0/Natsuko-1.4.0.apk",
            "https://github.com.evil.test/Natsuko-1.4.0.apk",
            "https://github.com@evil.test/Natsuko-1.4.0.apk",
            "https://github.com:8443/Natsuko-1.4.0.apk",
            "https://githubusercontent.com/Natsuko-1.4.0.apk",
            "https://release-assets.githubusercontent.com.evil.test/Natsuko-1.4.0.apk",
            "https://api.github.com/repos/poppinzhang/Natsuko/releases/latest",
            "file:///data/local/tmp/Natsuko-1.4.0.apk",
            "not a url",
        )) {
            var connected = false
            assertEquals(
                untrusted,
                text(R.string.update_error_redirect),
                failure { UpdateHttpClient({ connected = true; FakeConnection(ByteArray(1)) }).download(untrusted, 1, target) {} },
            )
            assertFalse(untrusted, connected)
        }

        var connections = 0
        val offDomain = FakeConnection(ByteArray(0)).apply { code = 302; location = "https://example.com/Natsuko.apk" }
        assertEquals(
            text(R.string.update_error_redirect),
            failure { UpdateHttpClient({ connections++; offDomain }).download(packageUrl, 1, target) {} },
        )
        assertEquals(1, connections)
        val missingLocation = FakeConnection(ByteArray(0)).apply { code = 302 }
        assertEquals(
            text(R.string.update_error_redirect),
            failure { UpdateHttpClient({ missingLocation }).download(packageUrl, 1, target) {} },
        )
    }

    @Test
    fun redirectLoopsStopAfterFiveRequests() = runBlocking {
        var connections = 0
        val failure = failure {
            UpdateHttpClient({
                connections++
                FakeConnection(ByteArray(0)).apply { code = 302; location = storageUrl }
            }).download(packageUrl, 1, target) {}
        }
        assertEquals(text(R.string.update_error_redirect), failure)
        assertEquals(5, connections)
    }

    @Test
    fun networkFailuresAreExplainedForTheOperationThatFailed() = runBlocking {
        val unreachable: (URL) -> HttpURLConnection = { throw UnknownHostException("api.github.com") }
        assertEquals(text(R.string.update_error_network), failure { UpdateHttpClient(unreachable).latestRelease() })
        assertEquals(
            text(R.string.backend_network_storage_failed),
            failure { UpdateHttpClient(unreachable).download(packageUrl, 1, target) {} },
        )

        val stalled = object : FakeConnection(ByteArray(1)) {
            override fun getResponseCode(): Int = throw SocketTimeoutException("Read timed out")
        }
        assertEquals(text(R.string.update_error_network), failure { UpdateHttpClient({ stalled }).latestRelease() })
        assertTrue(stalled.disconnected)
    }

    @Test
    fun exceedingTheTransferTimeLimitStopsTheDownload() = runBlocking {
        val connection = BlockedConnection()
        val message = withTimeout(5_000) {
            failure { UpdateHttpClient({ connection }, transferTimeoutMs = 200).download(packageUrl, 1, target) {} }
        }
        assertEquals(text(R.string.backend_download_timeout), message)
        assertTrue(connection.disconnected)
        assertFalse(target.exists())
    }

    @Test
    fun cancellationDisconnectsBlockedReadAndDeletesPartialFile() = runBlocking {
        val connection = BlockedConnection()
        withTimeout(5_000) {
            val transfer = async { UpdateHttpClient({ connection }).download(packageUrl, 1, target) {} }
            connection.started.await()
            transfer.cancelAndJoin()
            assertTrue(transfer.isCancelled)
        }
        assertTrue(connection.disconnected)
        assertFalse(target.exists())
    }

    private suspend fun failure(block: suspend () -> Unit): UiText? {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected a localized failure, got $error", error is LocalizedFailure)
        return (error as LocalizedFailure).userMessage
    }

    /** A read that only ends when the connection is disconnected. */
    private class BlockedConnection : FakeConnection(ByteArray(1)) {
        val started = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                started.complete(Unit)
                if (!released.await(10, TimeUnit.SECONDS)) throw SocketTimeoutException()
                throw IOException("Disconnected")
            }
        }

        override fun disconnect() {
            super.disconnect()
            released.countDown()
        }
    }

    private open class FakeConnection(body: ByteArray) : HttpURLConnection(URL("https://github.com/test")) {
        private val input = ByteArrayInputStream(body)
        var length = body.size.toLong()
        var code = 200
        var location: String? = null
        @Volatile var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getInputStream(): InputStream = input
        override fun getResponseCode() = code
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null
    }
}
