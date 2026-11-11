package com.poppinzhang.natsuko.data.update

import androidx.annotation.StringRes
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedIOException
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.errorText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Restricted HTTPS transport: release information from GitHub's API, the package from GitHub's download hosts. */
class UpdateHttpClient internal constructor(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val transferTimeoutMs: Long = 30 * 60 * 1000L,
) {
    suspend fun latestRelease(): String = timedRequest(60_000, R.string.update_error_network) {
        request(GitHubReleases.LATEST_URL, api = true) { connection ->
            checkText(connection.contentLengthLong <= MAX_RELEASE_BYTES) { text(R.string.update_error_invalid_response) }
            val result = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                copyBounded(input, result, MAX_RELEASE_BYTES, text(R.string.update_error_invalid_response)) {}
            }
            result.toString(Charsets.UTF_8.name())
        }
    }

    /** Streams exactly [size] bytes into [destination]; an incomplete or failed transfer leaves no file. */
    suspend fun download(url: String, size: Long, destination: File, onBytes: (Long) -> Unit) {
        var completed = false
        try {
            checkText(size <= MAX_PACKAGE_BYTES) { text(R.string.backend_download_size_limit, MAX_PACKAGE_BYTES / 1024 / 1024) }
            timedRequest(transferTimeoutMs, R.string.backend_network_storage_failed) {
                request(url, api = false) { connection ->
                    // The published size identifies the package; any other length is not that file.
                    val length = connection.contentLengthLong
                    checkText(length < 0 || length == size) { text(R.string.backend_download_incomplete) }
                    val received = connection.inputStream.use { input ->
                        destination.outputStream().use { output ->
                            copyBounded(input, output, size, text(R.string.backend_download_incomplete), onBytes)
                        }
                    }
                    checkText(received == size) { text(R.string.backend_download_incomplete) }
                }
            }
            currentCoroutineContext().ensureActive()
            completed = true
        } finally {
            if (!completed) destination.delete()
        }
    }

    private suspend fun <T> timedRequest(timeoutMs: Long, @StringRes fallback: Int, operation: suspend () -> T): T {
        val started = TimeSource.Monotonic.markNow()
        try {
            return withTimeout(timeoutMs) { operation() }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw LocalizedIOException(text(R.string.backend_download_timeout), timeout)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            // Disconnecting at the deadline interrupts a blocked read, which then fails as an I/O error.
            val timedOut = started.elapsedNow() >= timeoutMs.milliseconds
            throw LocalizedIOException(
                if (timedOut) text(R.string.backend_download_timeout) else error.userMessage(fallback),
                error,
            )
        }
    }

    private suspend fun <T> request(
        initialUrl: String,
        api: Boolean,
        read: suspend (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        var url = initialUrl
        repeat(MAX_REQUESTS) { requestIndex ->
            currentCoroutineContext().ensureActive()
            checkText(isAllowed(url, api)) { text(R.string.update_error_redirect) }
            val connection = openConnection(URL(url)).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                // GitHub rejects API requests that do not identify their client.
                setRequestProperty("User-Agent", "Natsuko")
                setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
            }
            // Cancelling the coroutine disconnects an active blocking read as well as the next copy iteration.
            val closeOnCancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { connection.disconnect() }
            }
            try {
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                if (status in REDIRECTS) {
                    checkText(requestIndex < MAX_REQUESTS - 1) { text(R.string.update_error_redirect) }
                    val location = connection.getHeaderField("Location") ?: errorText(text(R.string.update_error_redirect))
                    url = URL(URL(url), location).toString()
                } else {
                    checkText(status == HttpURLConnection.HTTP_OK) {
                        when {
                            status == 404 || status == 410 -> text(R.string.update_error_no_release)
                            api && (status == 403 || status == 429) -> text(R.string.update_error_rate_limited)
                            else -> text(R.string.update_error_http, status)
                        }
                    }
                    return@withContext read(connection)
                }
            } finally {
                closeOnCancellation.cancel()
                connection.disconnect()
            }
        }
        errorText(text(R.string.update_error_redirect))
    }

    /** Copies at most [limit] bytes and returns the amount; a longer stream fails with [tooLong]. */
    private suspend fun copyBounded(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        tooLong: UiText,
        onBytes: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val received = input.read(buffer)
            if (received < 0) break
            checkText(count + received <= limit) { tooLong }
            output.write(buffer, 0, received)
            count += received
            onBytes(count)
        }
        currentCoroutineContext().ensureActive()
        return count
    }

    internal companion object {
        const val MAX_PACKAGE_BYTES = 512 * 1024 * 1024L
        private const val MAX_RELEASE_BYTES = 1024 * 1024L

        // The first request plus the redirects GitHub uses to reach its release storage.
        private const val MAX_REQUESTS = 5
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** Release information stays on the API host; packages come from github.com and its storage hosts. */
        fun isAllowed(url: String, api: Boolean): Boolean = runCatching {
            val uri = URI(url)
            val host = uri.host.lowercase(Locale.ROOT)
            uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
                if (api) host == "api.github.com" else host == "github.com" || host.endsWith(".githubusercontent.com")
        }.getOrDefault(false)
    }
}
