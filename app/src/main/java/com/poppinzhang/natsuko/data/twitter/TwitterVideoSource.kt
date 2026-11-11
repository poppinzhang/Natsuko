package com.poppinzhang.natsuko.data.twitter

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.text
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.checkNotNullText
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.LocalizedIOException
import com.poppinzhang.natsuko.model.LocalizedFailure
import java.io.File
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

data class TwitterVideo(
    val url: String?,
    val animatedGif: Boolean,
    val unavailableReason: UiText? = null,
    /** One-based position among all post attachments, including photos. */
    val mediaIndex: Int? = null,
)

interface TwitterVideoSource {
    suspend fun resolve(link: TwitterStatusLink): List<TwitterVideo>
    suspend fun download(video: TwitterVideo, destination: File, onBytes: (Long, Long?) -> Unit)
}

class SyndicationTwitterVideoSource(
    private val http: TwitterHttpClient = TwitterHttpClient(),
    private val session: () -> TwitterSession? = { null },
) : TwitterVideoSource {
    override suspend fun resolve(link: TwitterStatusLink): List<TwitterVideo> = try {
        // Includes every diagnostic request, rather than starting a new deadline for each fallback.
        withTimeout(60_000) { resolveWithinDeadline(link) }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw LocalizedIOException(text(R.string.backend_download_timeout), timeout)
    }

    private suspend fun resolveWithinDeadline(link: TwitterStatusLink): List<TwitterVideo> {
        val publicMedia = try {
            TwitterVideoMetadata.parse(http.metadata(link), link.id)
        } catch (failure: Exception) {
            if (!isIncompleteMetadata(failure)) throw failure
            null
        }
        // Keep existing partial-batch behavior: a missing MP4 for one attachment must not
        // discard other downloadable videos if a diagnostic endpoint is unavailable.
        if (publicMedia != null && publicMedia.any { it.url != null }) return publicMedia
        session()?.let { return resolveGraphql(link, it) }
        try {
            return resolveGraphql(link, null)
        } catch (failure: TwitterAccessException) {
            if (failure.reason != TwitterAccessFailure.UNKNOWN) throw failure
        }
        // The guest API sometimes removes the reason from a tombstone. The official post page
        // can still include its focal post's structured explanation (including the explicit login gate).
        val page = http.webMetadata("https://x.com/i/status/${link.id}", pagePostId = link.id)
        try {
            return TwitterWebMetadata.parsePublicPage(page.body, link.id)
        } catch (failure: TwitterAccessException) {
            if (failure.reason == TwitterAccessFailure.UNKNOWN && publicMedia != null) return publicMedia
            throw failure
        }
    }

    private suspend fun resolveGraphql(link: TwitterStatusLink, authenticated: TwitterSession?): List<TwitterVideo> {
        val guestToken = if (authenticated == null) {
            val response = http.webMetadata("https://api.x.com/1.1/guest/activate.json", activateGuest = true)
            val token = runCatching { JSONObject(response.body).optString("guest_token") }.getOrNull()
            if (response.status != 200 || token == null || !Regex("[0-9]{1,32}").matches(token)) {
                throw TwitterAccessException(TwitterAccessFailure.UNKNOWN)
            }
            token
        } else null
        val response = http.webMetadata(TwitterWebMetadata.queryUrl(link.id), session = authenticated, guestToken = guestToken)
        // A generic HTTP failure does not prove that saved account credentials are invalid.
        // Only an explicit authentication failure in X's structured response expires a session.
        return TwitterWebMetadata.parseGraphql(response.body, link.id, authenticated != null)
    }

    private fun isIncompleteMetadata(failure: Exception): Boolean =
        failure is TwitterAccessException && failure.reason == TwitterAccessFailure.UNKNOWN ||
            (failure as? LocalizedFailure)?.userMessage in setOf(
                text(R.string.backend_post_unavailable), text(R.string.backend_post_invalid_response),
                text(R.string.backend_post_no_media),
            )

    override suspend fun download(video: TwitterVideo, destination: File, onBytes: (Long, Long?) -> Unit) {
        http.download(checkNotNullText(video.url) { video.unavailableReason ?: text(R.string.backend_no_downloadable_mp4) }, destination, onBytes)
    }
}

/** Parses only media attached to this tweet; external player cards and quoted tweets are not followed. */
object TwitterVideoMetadata {
    fun parse(json: String, expectedId: String): List<TwitterVideo> {
        val document = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw LocalizedIllegalStateException(text(R.string.backend_post_invalid_response), error)
        }
        checkText(document.optString("id_str") == expectedId) {
            text(R.string.backend_post_unavailable)
        }
        val media = mutableListOf<IndexedValue<JSONObject>>()
        document.optJSONArray("mediaDetails")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { media += IndexedValue(index + 1, it) }
            }
        }
        if (media.isEmpty()) {
            // Older player responses contain a single video with type/src variants.
            document.optJSONObject("video")?.let { video ->
                media += IndexedValue(1, JSONObject().put("type", if (video.optBoolean("isGif")) "animated_gif" else "video")
                    .put("video_info", video))
            }
        }
        if (media.isEmpty()) {
            val card = document.optJSONObject("card")?.optJSONObject("binding_values")
                ?.optJSONObject("unified_card")?.optString("string_value").orEmpty()
            if (card.isNotBlank()) {
                val entities = runCatching { JSONObject(card).optJSONObject("media_entities") }.getOrNull()
                // JSONObject key iteration is unspecified; sorting keeps attachment order deterministic.
                entities?.keys()?.asSequence()?.sorted()?.forEachIndexed { index, key ->
                    entities.optJSONObject(key)?.let { media += IndexedValue(index + 1, it) }
                }
            }
        }
        val videos = media.filter { it.value.optString("type") in setOf("video", "animated_gif") }.map { (mediaIndex, entry) ->
            val selected = bestMp4(entry.optJSONObject("video_info")?.optJSONArray("variants"))
            TwitterVideo(
                url = selected,
                animatedGif = entry.optString("type") == "animated_gif",
                unavailableReason = if (selected == null) text(R.string.backend_video_mp4_unavailable) else null,
                mediaIndex = mediaIndex,
            )
        }
        checkText(videos.isNotEmpty()) { text(R.string.backend_post_no_media) }
        return videos
    }

    private fun bestMp4(variants: JSONArray?): String? {
        if (variants == null) return null
        data class Variant(val url: String, val bitrate: Long, val pixels: Long)
        return (0 until variants.length()).mapNotNull { index ->
            val item = variants.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").ifBlank { item.optString("src") }
            val type = item.optString("content_type").ifBlank { item.optString("type") }
            if (type.substringBefore(';').trim().lowercase() != "video/mp4" || !TwitterMediaUrl.isAllowed(url)) {
                return@mapNotNull null
            }
            val dimensions = Regex("/([0-9]{1,5})x([0-9]{1,5})/").find(URI(url).path)
            val pixels = dimensions?.let { it.groupValues[1].toLong() * it.groupValues[2].toLong() } ?: 0
            Variant(url, maxOf(0, item.optLong("bitrate", item.optLong("bit_rate", 0))), pixels)
        }.maxWithOrNull(compareBy<Variant> { it.bitrate }.thenBy { it.pixels }.thenBy { it.url })?.url
    }
}

internal object TwitterMediaUrl {
    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals("video.twimg.com", true) &&
            uri.userInfo == null && uri.port in listOf(-1, 443) && uri.fragment == null &&
            uri.path.lowercase().endsWith(".mp4")
    }.getOrDefault(false)
}
