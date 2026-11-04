package com.poppinzhang.natsuko.data.twitter

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.userMessage
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.errorText
import com.poppinzhang.natsuko.model.LocalizedIOException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
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

/** Restricted HTTPS transport: tweet metadata from X's embed service, media from its video CDN. */
class TwitterHttpClient internal constructor(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val maxVideoBytes: Long = MAX_VIDEO_BYTES,
    private val transferTimeoutMs: Long = 10 * 60 * 1000L,
) {
    suspend fun metadata(link: TwitterStatusLink): String = timedRequest(60_000) {
        val url = "https://cdn.syndication.twimg.com/tweet-result?id=${link.id}&lang=en&token=${TwitterSyndicationToken.create(link.id)}"
        request(url, media = false) { connection ->
            val length = connection.contentLengthLong
            checkText(length <= MAX_METADATA_BYTES) { text(R.string.backend_metadata_too_large) }
            val result = ByteArrayOutputStream()
            connection.inputStream.use { copyBounded(it, result, MAX_METADATA_BYTES, length.takeIf { size -> size >= 0 }) { _, _ -> } }
            result.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun download(url: String, destination: File, onBytes: (Long, Long?) -> Unit) {
        var completed = false
        try {
            timedRequest(transferTimeoutMs) {
                request(url, media = true) { connection ->
                    val length = connection.contentLengthLong
                    checkText(length <= maxVideoBytes) { text(R.string.backend_video_download_size_limit, maxVideoBytes / 1024 / 1024) }
                    val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                    checkText(type in setOf("", "video/mp4", "application/octet-stream", "binary/octet-stream")) {
                        text(R.string.backend_download_not_mp4)
                    }
                    connection.inputStream.use { input ->
                        destination.outputStream().use { output ->
                            copyBounded(input, output, maxVideoBytes, length.takeIf { it >= 0 }, onBytes)
                        }
                    }
                    checkText(destination.length() > 0) { text(R.string.backend_download_video_empty) }
                }
            }
            currentCoroutineContext().ensureActive()
            completed = true
        } finally {
            if (!completed) destination.delete()
        }
    }

    private suspend fun <T> timedRequest(timeoutMs: Long, operation: suspend () -> T): T = try {
        withTimeout(timeoutMs) { operation() }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw LocalizedIOException(text(R.string.backend_download_timeout), timeout)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (timeout: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        throw LocalizedIOException(text(R.string.backend_network_timeout), timeout)
    } catch (unresolved: UnknownHostException) {
        currentCoroutineContext().ensureActive()
        throw LocalizedIOException(text(R.string.backend_network_x_failed), unresolved)
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw LocalizedIOException(error.userMessage(R.string.backend_network_storage_failed), error)
    }

    private suspend fun <T> request(
        initialUrl: String,
        media: Boolean,
        read: suspend (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        var url = initialUrl
        repeat(4) { redirectIndex ->
            currentCoroutineContext().ensureActive()
            val allowed = if (media) TwitterMediaUrl.isAllowed(url) else runCatching {
                val uri = URI(url)
                uri.scheme == "https" && uri.host == "cdn.syndication.twimg.com" &&
                    uri.port in listOf(-1, 443) && uri.userInfo == null
            }.getOrDefault(false)
            checkText(allowed) { text(R.string.backend_download_url_unsupported) }
            val connection = openConnection(URL(url)).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Googlebot")
                setRequestProperty("Accept", if (media) "video/mp4,application/octet-stream" else "application/json")
                setRequestProperty("Accept-Encoding", "identity")
            }
            // Cancelling the coroutine disconnects an active blocking read as well as the next copy iteration.
            val closeOnCancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { connection.disconnect() }
            }
            try {
                val status = connection.responseCode
                currentCoroutineContext().ensureActive()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    checkText(redirectIndex < 3) { text(R.string.backend_download_redirect_limit) }
                    val location = connection.getHeaderField("Location") ?: errorText(text(R.string.backend_download_redirect_invalid))
                    url = URL(URL(url), location).toString()
                } else {
                    checkText(status == HttpURLConnection.HTTP_OK) {
                        when (status) {
                            401, 403 -> text(R.string.backend_x_access_denied)
                            404, 410 -> text(R.string.backend_x_media_missing)
                            429 -> text(R.string.backend_x_rate_limited)
                            in 500..599 -> text(R.string.backend_x_server_error, status)
                            else -> text(R.string.backend_x_http_error, status)
                        }
                    }
                    return@withContext read(connection)
                }
            } finally {
                closeOnCancellation.cancel()
                connection.disconnect()
            }
        }
        errorText(text(R.string.backend_download_redirect_limit))
    }

    private suspend fun copyBounded(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        expectedLength: Long?,
        onBytes: (Long, Long?) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val received = input.read(buffer)
            if (received < 0) break
            checkText(count + received <= limit) { text(R.string.backend_download_size_limit, limit / 1024 / 1024) }
            output.write(buffer, 0, received)
            count += received
            onBytes(count, expectedLength)
        }
        currentCoroutineContext().ensureActive()
        checkText(expectedLength == null || count == expectedLength) { text(R.string.backend_download_incomplete) }
    }

    companion object {
        const val MAX_VIDEO_BYTES = 512 * 1024 * 1024L
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024L
    }
}
