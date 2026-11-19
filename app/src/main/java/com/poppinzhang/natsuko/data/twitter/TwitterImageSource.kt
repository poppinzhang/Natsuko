package com.poppinzhang.natsuko.data.twitter

import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.model.LocalizedIllegalStateException
import com.poppinzhang.natsuko.model.UiText
import com.poppinzhang.natsuko.model.checkText
import com.poppinzhang.natsuko.model.text
import java.io.File
import java.net.URI
import java.util.Locale
import org.json.JSONObject

data class TwitterImage(
    val url: String?,
    val fallbackUrl: String? = null,
    val formatExtension: String = "jpg",
    val unavailableReason: UiText? = null,
    /** One-based position among all post attachments, including videos. */
    val mediaIndex: Int? = null,
)

interface TwitterImageSource {
    suspend fun resolveImages(link: TwitterStatusLink): List<TwitterImage>
    suspend fun downloadImage(image: TwitterImage, destination: File, onBytes: (Long, Long?) -> Unit)
}

/** Parses only photo attachments on this tweet; quoted tweets and non-photo media are excluded. */
object TwitterImageMetadata {
    fun parse(json: String, expectedId: String): List<TwitterImage> {
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
            document.optJSONArray("photos")?.let { array ->
                for (index in 0 until array.length()) {
                    val photo = array.optJSONObject(index) ?: continue
                    val raw = photo.optString("url")
                    media += IndexedValue(
                        index + 1,
                        JSONObject().put("type", "photo").put("media_url_https", raw),
                    )
                }
            }
        }
        if (media.isEmpty()) {
            val card = document.optJSONObject("card")?.optJSONObject("binding_values")
                ?.optJSONObject("unified_card")?.optString("string_value").orEmpty()
            if (card.isNotBlank()) {
                val entities = runCatching { JSONObject(card).optJSONObject("media_entities") }.getOrNull()
                entities?.keys()?.asSequence()?.sorted()?.forEachIndexed { index, key ->
                    entities.optJSONObject(key)?.let { media += IndexedValue(index + 1, it) }
                }
            }
        }
        val images = media.filter { it.value.optString("type") == "photo" }.map { (mediaIndex, entry) ->
            val variants = TwitterImageUrl.resolveVariants(entry.optString("media_url_https"))
            if (variants != null) {
                TwitterImage(
                    url = variants.primaryUrl,
                    fallbackUrl = variants.fallbackUrl,
                    formatExtension = variants.formatExtension,
                    mediaIndex = mediaIndex,
                )
            } else {
                TwitterImage(
                    url = null,
                    unavailableReason = text(R.string.backend_image_url_unavailable),
                    mediaIndex = mediaIndex,
                )
            }
        }
        checkText(images.isNotEmpty()) { text(R.string.backend_post_no_images) }
        return images
    }
}

internal object TwitterImageUrl {
    data class ResolvedImageUrl(
        val primaryUrl: String,
        val fallbackUrl: String?,
        val formatExtension: String,
    )

    private val legacySizeSuffix = Regex(":(?:thumb|small|medium|large|orig)$", RegexOption.IGNORE_CASE)
    private val imageExtension = Regex("\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)
    private val supportedFormats = setOf("jpg", "png", "webp", "gif")

    fun isAllowed(url: String): Boolean = runCatching {
        if (url.any { it.isWhitespace() || it.isISOControl() }) return@runCatching false
        val uri = URI(url)
        val path = uri.rawPath.orEmpty()
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("pbs.twimg.com", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443) &&
            uri.fragment == null &&
            path.startsWith("/media/") &&
            path.length > "/media/".length &&
            !path.contains("//") &&
            uri.normalize().rawPath == path
    }.getOrDefault(false)

    fun resolveVariants(rawUrl: String): ResolvedImageUrl? {
        if (!isAllowed(rawUrl)) return null
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val rawPath = uri.rawPath ?: return null
        val strippedPath = legacySizeSuffix.replace(rawPath, "")
        val extensionMatch = imageExtension.find(strippedPath)
        val pathStem = if (extensionMatch != null) strippedPath.substring(0, extensionMatch.range.first) else strippedPath
        val pathExtension = extensionMatch?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?.let { if (it == "jpeg") "jpg" else it }

        val queryPairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { part ->
            part.substringBefore('=') to part.substringAfter('=', "")
        }
        val queryFormat = queryPairs.firstOrNull { it.first.equals("format", ignoreCase = true) }
            ?.second?.lowercase(Locale.ROOT)?.let { if (it == "jpeg") "jpg" else it }
        val resolvedFormat = queryFormat?.takeIf { it in supportedFormats } ?: pathExtension
        val extraParams = queryPairs.filterNot { (key, _) ->
            key.equals("format", ignoreCase = true) || key.equals("name", ignoreCase = true)
        }
        val newParams = buildList {
            addAll(extraParams)
            if (resolvedFormat != null) add("format" to resolvedFormat)
            add("name" to "orig")
        }
        val query = newParams.joinToString("&") { (key, value) -> "$key=$value" }
        val primaryUrl = "https://$host$pathStem?$query"
        if (!isAllowed(primaryUrl)) return null

        val candidateFallback = if (strippedPath != rawPath) "https://$host$strippedPath" else rawUrl
        val fallbackUrl = candidateFallback.takeIf { it != primaryUrl && isAllowed(it) }
        return ResolvedImageUrl(
            primaryUrl = primaryUrl,
            fallbackUrl = fallbackUrl,
            formatExtension = resolvedFormat ?: "jpg",
        )
    }
}
