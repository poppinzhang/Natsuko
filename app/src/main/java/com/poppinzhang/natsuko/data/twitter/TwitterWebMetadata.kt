package com.poppinzhang.natsuko.data.twitter

import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** X web responses are not a stable API. Unknown responses must never imply that login will help. */
internal object TwitterWebMetadata {
    const val GRAPHQL_PATH = "/i/api/graphql/2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId"
    // Public identifier used by X's web client; this is not a user credential or an application secret.
    const val WEB_BEARER = "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

    fun queryUrl(id: String): String {
        val variables = JSONObject().put("tweetId", id).put("withCommunity", false)
            .put("includePromotedContent", false).put("withVoice", false)
        val features = JSONObject()
        listOf(
            "creator_subscriptions_tweet_preview_api_enabled", "tweetypie_unmention_optimization_enabled",
            "responsive_web_edit_tweet_api_enabled", "graphql_is_translatable_rweb_tweet_is_translatable_enabled",
            "view_counts_everywhere_api_enabled", "longform_notetweets_consumption_enabled",
            "freedom_of_speech_not_reach_fetch_enabled", "standardized_nudges_misinfo",
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled",
            "longform_notetweets_rich_text_read_enabled", "longform_notetweets_inline_media_enabled",
            "responsive_web_graphql_exclude_directive_enabled", "responsive_web_graphql_timeline_navigation_enabled",
        ).forEach { features.put(it, true) }
        listOf(
            "responsive_web_twitter_article_tweet_consumption_enabled", "tweet_awards_web_tipping_enabled",
            "verified_phone_label_enabled", "responsive_web_media_download_video_enabled",
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled", "responsive_web_enhance_cards_enabled",
        ).forEach { features.put(it, false) }
        return "https://x.com$GRAPHQL_PATH?" + listOf(
            "variables" to variables,
            "features" to features,
            "fieldToggles" to JSONObject().put("withArticleRichContentState", false),
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value.toString(), "UTF-8")}" }
    }

    fun parseGraphql(json: String, expectedId: String, authenticated: Boolean): List<TwitterVideo> {
        val document = runCatching { JSONObject(json) }.getOrNull() ?: fail(TwitterAccessFailure.UNKNOWN)
        val data = document.optJSONObject("data")
        val wrapper = data?.optJSONObject("tweetResult") ?: data?.optJSONObject("tweet_result_by_rest_id")
        if (wrapper != null && wrapper.has("rest_id") && wrapper.optString("rest_id") != expectedId) {
            fail(TwitterAccessFailure.UNKNOWN)
        }
        val result = wrapper?.optJSONObject("result")
        if (result != null) return parseResult(result, expectedId, authenticated)
        val errors = document.optJSONArray("errors")
        for (index in 0 until (errors?.length() ?: 0)) {
            val error = errors?.optJSONObject(index) ?: continue
            when (error.optInt("code", -1)) {
                144 -> fail(TwitterAccessFailure.DELETED)
                179 -> fail(TwitterAccessFailure.PROTECTED)
                63, 64, 326 -> fail(TwitterAccessFailure.UNAVAILABLE)
                88 -> fail(TwitterAccessFailure.RATE_LIMITED)
                32, 89, 135, 215, 353 -> if (authenticated) fail(TwitterAccessFailure.SESSION_EXPIRED)
            }
        }
        fail(TwitterAccessFailure.UNKNOWN)
    }

    /** Read only JSON string values in the server-rendered payload; never evaluate page JavaScript. */
    fun parsePublicPage(html: String, expectedId: String): List<TwitterVideo> {
        var pageOffset = 0
        while (pageOffset < html.length) {
            val scriptStart = html.indexOf("<script", pageOffset)
            if (scriptStart < 0) break
            val tagEnd = html.indexOf('>', scriptStart + 7)
            if (tagEnd < 0) break
            val scriptEnd = html.indexOf("</script>", tagEnd + 1)
            if (scriptEnd < 0) break
            pageOffset = scriptEnd + 9
            if (html[scriptStart + 7] != '>' && !html[scriptStart + 7].isWhitespace()) continue
            var offset = tagEnd + 1
            while (offset < scriptEnd) {
                // Pin to X's actual server-rendered GraphQL Result.Ok envelope. Plain text,
                // attributes and arbitrary JSON-looking values elsewhere in the page are ignored.
                val marker = SSR_RESULT.find(html, offset) ?: break
                if (marker.range.last >= scriptEnd) break
                val start = marker.range.last + 1
                offset = start + 1
                if (start >= scriptEnd || html[start] != '"') continue
                var end = start + 1
                while (end < scriptEnd) {
                    if (html[end] == '\\') { end += 2; continue }
                    if (html[end] == '"') break
                    end++
                }
                offset = end + 1
                if (end >= scriptEnd) break
                val decoded = runCatching { JSONTokener(html.substring(start, end + 1)).nextValue() as? String }.getOrNull() ?: continue
                val json = runCatching { JSONObject(decoded) }.getOrNull() ?: continue
                // Replies and quote posts are not evidence about this post.
                val wrapper = json.optJSONObject("data")?.optJSONObject("tweet_result_by_rest_id") ?: continue
                if (wrapper.optString("rest_id") != expectedId) continue
                val result = wrapper.optJSONObject("result") ?: continue
                return parseResult(result, expectedId, authenticated = false)
            }
        }
        fail(TwitterAccessFailure.UNKNOWN)
    }

    private val SSR_RESULT = Regex("""result:\${'$'}R\[[0-9]+\]=\{kind:"Result\.Ok",value:""")

    private fun parseResult(raw: JSONObject, expectedId: String, authenticated: Boolean): List<TwitterVideo> {
        if (raw.has("rest_id") && raw.optString("rest_id") != expectedId) fail(TwitterAccessFailure.UNKNOWN)
        val type = raw.optString("__typename")
        val reason = raw.optString("reason")
        if (type == "TweetUnavailable") {
            when (reason) {
                "NsfwLoggedOut" -> fail(if (authenticated) TwitterAccessFailure.SESSION_EXPIRED else TwitterAccessFailure.LOGIN_REQUIRED)
                "NsfwViewerHasNoStatedAge", "NsfwViewerUnderage", "NsfwAgeRestricted" -> fail(TwitterAccessFailure.AGE_RESTRICTED)
                "Protected" -> fail(TwitterAccessFailure.PROTECTED)
                "Deleted", "NotFound" -> fail(TwitterAccessFailure.DELETED)
                "Suspended", "Deactivated", "AuthorSuspended", "AuthorDeactivated" -> fail(TwitterAccessFailure.UNAVAILABLE)
                "Withheld", "CountryWithheld", "LegalWithheld" -> fail(TwitterAccessFailure.WITHHELD)
                else -> fail(TwitterAccessFailure.UNKNOWN)
            }
        }
        raw.optJSONObject("tombstone")?.let { tombstone ->
            val notice = tombstone.optJSONObject("text")?.optString("text").orEmpty()
                .lowercase(Locale.ROOT).replace('’', '\'').trim()
            // BlurredMediaTombstone alone can also mean age/account restrictions, so require its explicit login notice.
            if (tombstone.optString("__typename") == "BlurredMediaTombstone" &&
                (notice.contains("to view this media, you'll need to log in to x") ||
                    notice.contains("to view this media, you'll need to log in to twitter"))) {
                fail(if (authenticated) TwitterAccessFailure.SESSION_EXPIRED else TwitterAccessFailure.LOGIN_REQUIRED)
            }
            when {
                notice.startsWith("this post was deleted") || notice.startsWith("this tweet was deleted") -> fail(TwitterAccessFailure.DELETED)
                notice.startsWith("these posts are protected") || notice.startsWith("these tweets are protected") -> fail(TwitterAccessFailure.PROTECTED)
                notice.startsWith("this post has been withheld") || notice.startsWith("this tweet has been withheld") -> fail(TwitterAccessFailure.WITHHELD)
                notice.startsWith("this post is from a suspended account") || notice.startsWith("this tweet is from a suspended account") -> fail(TwitterAccessFailure.UNAVAILABLE)
                else -> fail(TwitterAccessFailure.UNKNOWN)
            }
        }
        val result = if (type == "TweetWithVisibilityResults") raw.optJSONObject("tweet") ?: fail(TwitterAccessFailure.UNKNOWN) else raw
        if (result.optString("__typename") != "Tweet") fail(TwitterAccessFailure.UNKNOWN)
        val legacy = result.optJSONObject("legacy") ?: fail(TwitterAccessFailure.UNKNOWN)
        val actualId = result.optString("rest_id").ifBlank { legacy.optString("id_str") }
        if (actualId != expectedId) fail(TwitterAccessFailure.UNKNOWN)
        val media = legacy.optJSONObject("extended_entities")?.optJSONArray("media")
            ?: legacy.optJSONObject("entities")?.optJSONArray("media") ?: JSONArray()
        // Normalize only this post's attached media. Do not traverse quoted/retweeted content or external cards.
        val normalized = JSONObject().put("id_str", actualId).put("mediaDetails", media)
        return TwitterVideoMetadata.parse(normalized.toString(), expectedId)
    }

    private fun fail(reason: TwitterAccessFailure): Nothing = throw TwitterAccessException(reason)
}
