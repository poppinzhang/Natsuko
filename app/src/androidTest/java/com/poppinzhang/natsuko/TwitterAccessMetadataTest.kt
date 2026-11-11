package com.poppinzhang.natsuko

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poppinzhang.natsuko.data.twitter.TwitterAccessException
import com.poppinzhang.natsuko.data.twitter.TwitterAccessFailure
import com.poppinzhang.natsuko.data.twitter.TwitterWebMetadata
import com.poppinzhang.natsuko.data.twitter.SyndicationTwitterVideoSource
import com.poppinzhang.natsuko.data.twitter.TwitterHttpClient
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.data.twitter.TwitterSession
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwitterAccessMetadataTest {
    private val id = "2120307161339244627"
    // Scrubbed focal-result fixture from X's public SSR response; no account identifiers/cookies.
    private val loginResult = """{"__typename":"TweetTombstone","tombstone":{
        "__typename":"BlurredMediaTombstone","text":{"text":"To view this media, you’ll need to log in to X."}}}"""

    @Test
    fun actualExampleExplicitFocalPageNoticeRequiresLogin() {
        val payload = """{"data":{"tweet_result_by_rest_id":{"rest_id":"$id","result":$loginResult}}}"""
        val page = ssrPage(payload)
        assertFailure(TwitterAccessFailure.LOGIN_REQUIRED) { TwitterWebMetadata.parsePublicPage(page, id) }
        assertFailure(TwitterAccessFailure.UNKNOWN) { TwitterWebMetadata.parsePublicPage(page, "999") }
    }

    @Test
    fun genericPageLoginControlsAndUnrelatedPostsCannotTriggerLogin() {
        val pageFixtures = listOf(
            "<a href='/i/flow/login'>Log in</a><p>To view this media, you’ll need to log in to X.</p>",
            "value:${JSONObject.quote("""{"data":{"tweet_result_by_rest_id":{"rest_id":"999","result":$loginResult}}}""")}",
            "value:${JSONObject.quote("""{"data":{"quoted_status_result":{"rest_id":"$id","result":$loginResult}}}""")}",
            "value:\"unterminated\\",
            "value:window.location.href",
            ssrPage("""{"data":{"tweet_result_by_rest_id":{"rest_id":"$id","result":$loginResult}}}""")
                .replace("<script>", "<div>").replace("</script>", "</div>"),
            "<script>value:${JSONObject.quote("""{"data":{"tweet_result_by_rest_id":{"rest_id":"$id","result":$loginResult}}}""")}</script>",
        )
        for (page in pageFixtures) assertFailure(TwitterAccessFailure.UNKNOWN) { TwitterWebMetadata.parsePublicPage(page, id) }
    }

    @Test
    fun ambiguousErrorsNeverPretendContentWasDeletedOrNeedsLogin() {
        for (result in listOf(
            "{}", """{"__typename":"TweetTombstone"}""",
            """{"__typename":"TweetTombstone","tombstone":{}}""",
            """{"__typename":"TweetUnavailable","reason":"SomethingNew"}""",
            """{"__typename":"TweetTombstone","tombstone":{"__typename":"BlurredMediaTombstone"}}""",
        )) assertFailure(TwitterAccessFailure.UNKNOWN) { TwitterWebMetadata.parseGraphql(graph(result), id, false) }
        for (json in listOf("{}", "<html>Not found</html>", """{"errors":[{"message":"Not authorized"}]}""")) {
            assertFailure(TwitterAccessFailure.UNKNOWN) { TwitterWebMetadata.parseGraphql(json, id, false) }
        }
    }

    @Test
    fun explicitRestrictionsAndSessionExpiryRemainDistinct() {
        val cases = mapOf(
            "NsfwLoggedOut" to TwitterAccessFailure.LOGIN_REQUIRED,
            "Protected" to TwitterAccessFailure.PROTECTED,
            "Deleted" to TwitterAccessFailure.DELETED,
            "NotFound" to TwitterAccessFailure.DELETED,
            "NsfwViewerHasNoStatedAge" to TwitterAccessFailure.AGE_RESTRICTED,
            "NsfwViewerUnderage" to TwitterAccessFailure.AGE_RESTRICTED,
            "CountryWithheld" to TwitterAccessFailure.WITHHELD,
            "Suspended" to TwitterAccessFailure.UNAVAILABLE,
        )
        cases.forEach { (reason, expected) ->
            assertFailure(expected) { TwitterWebMetadata.parseGraphql(graph("""{"__typename":"TweetUnavailable","reason":"$reason"}"""), id, false) }
        }
        assertFailure(TwitterAccessFailure.SESSION_EXPIRED) { TwitterWebMetadata.parseGraphql(graph(loginResult), id, true) }
        assertFailure(TwitterAccessFailure.PROTECTED) { TwitterWebMetadata.parseGraphql("""{"errors":[{"code":179}]}""", id, false) }
        assertFailure(TwitterAccessFailure.RATE_LIMITED) { TwitterWebMetadata.parseGraphql("""{"errors":[{"code":88}]}""", id, false) }
        assertFailure(TwitterAccessFailure.SESSION_EXPIRED) { TwitterWebMetadata.parseGraphql("""{"errors":[{"code":89}]}""", id, true) }
        assertFailure(TwitterAccessFailure.UNKNOWN) { TwitterWebMetadata.parseGraphql("""{"errors":[{"code":89}]}""", id, false) }
    }

    @Test
    fun graphMediaUsesOnlyFocalPostAndPreservesAttachmentPositions() {
        val result = """{"__typename":"TweetWithVisibilityResults","tweet":{
          "__typename":"Tweet","rest_id":"$id","legacy":{"extended_entities":{"media":[
            {"type":"photo"},{"type":"video","video_info":{"variants":[
            {"content_type":"video/mp4","bitrate":1,"url":"https://video.twimg.com/small.mp4"},
            {"content_type":"video/mp4","bitrate":2,"url":"https://video.twimg.com/big.mp4"}]}},
            {"type":"animated_gif","video_info":{"variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/gif.mp4"}]}}
          ]}},"quoted_status_result":{"result":{"legacy":{"extended_entities":{"media":[{"type":"video"}]}}}}
        }}"""
        val videos = TwitterWebMetadata.parseGraphql(graph(result), id, true)
        assertEquals(2, videos.size)
        assertEquals(listOf(2, 3), videos.map { it.mediaIndex })
        assertEquals("https://video.twimg.com/big.mp4", videos[0].url)
        assertFalse(videos[0].animatedGif)
        assertTrue(videos[1].animatedGif)
        assertFailure(TwitterAccessFailure.UNKNOWN) { TwitterWebMetadata.parseGraphql(graph(result), "999", true) }
    }

    @Test
    fun partialPublicMediaDoesNotDependOnDiagnosticEndpoints() = runBlocking {
        val body = """{"id_str":"$id","mediaDetails":[
          {"type":"video","video_info":{"variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/good.mp4"}]}},
          {"type":"video","video_info":{"variants":[{"content_type":"application/x-mpegURL","url":"https://video.twimg.com/list.m3u8"}]}}
        ]}""".toByteArray()
        var requests = 0
        val http = TwitterHttpClient({ url ->
            requests++
            check(url.host == "cdn.syndication.twimg.com") { "Diagnostics must not replace a partial public batch" }
            object : HttpURLConnection(URL("https://cdn.syndication.twimg.com/tweet-result")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = body.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(body)
            }
        })
        val videos = SyndicationTwitterVideoSource(http).resolve(TwitterStatusLink.parse("https://x.com/i/status/$id"))
        assertEquals(1, requests)
        assertEquals(2, videos.size)
        assertEquals("https://video.twimg.com/good.mp4", videos[0].url)
        assertEquals(null, videos[1].url)
        assertEquals(listOf(1, 2), videos.map { it.mediaIndex })
    }

    @Test
    fun retainedSessionStillUsesCookieFreePublicUnifiedCard() = runBlocking {
        val card = """{"media_entities":{"one":{"type":"video","video_info":{"variants":[
          {"content_type":"video/mp4","url":"https://video.twimg.com/card.mp4"}]}}}}"""
        val body = JSONObject().put("id_str", id).put("card", JSONObject().put("binding_values",
            JSONObject().put("unified_card", JSONObject().put("string_value", card)))).toString().toByteArray()
        var requests = 0
        val http = TwitterHttpClient({ url ->
            requests++
            check(url.host == "cdn.syndication.twimg.com")
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    assertEquals(null, getRequestProperty("Cookie"))
                    assertEquals(null, getRequestProperty("Authorization"))
                    return 200
                }
                override fun getContentLengthLong() = body.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(body)
            }
        })
        val session = checkNotNull(TwitterSession.fromCookieHeader("auth_token=fixture; ct0=fixture-csrf"))
        val videos = SyndicationTwitterVideoSource(http, session = { session })
            .resolve(TwitterStatusLink.parse("https://x.com/i/status/$id"))
        assertEquals(1, requests)
        assertEquals("https://video.twimg.com/card.mp4", videos.single().url)
    }

    @Test
    fun genericAuthenticatedHttpFailuresDoNotExpireSavedSessions() = runBlocking {
        for (status in listOf(401, 403)) {
            for (body in listOf("{}", "<html>Not authorized</html>", """{"errors":[{"message":"Not authorized"}]}""")) {
                assertEquals(TwitterAccessFailure.UNKNOWN, authenticatedFailure(status, body))
            }
        }
    }

    @Test
    fun structuredAuthenticatedFailureStillExpiresSavedSessions() = runBlocking {
        assertEquals(TwitterAccessFailure.SESSION_EXPIRED, authenticatedFailure(401, """{"errors":[{"code":89}]}"""))
        assertEquals(TwitterAccessFailure.SESSION_EXPIRED, authenticatedFailure(200, graph(loginResult)))
    }

    private suspend fun authenticatedFailure(status: Int, response: String): TwitterAccessFailure? {
        val session = checkNotNull(TwitterSession.fromCookieHeader("auth_token=fixture; ct0=fixture-csrf"))
        var requests = 0
        val http = TwitterHttpClient({ url ->
            requests++
            val publicRequest = url.host == "cdn.syndication.twimg.com"
            check(publicRequest || url.host == "x.com" && url.path == TwitterWebMetadata.GRAPHQL_PATH)
            val bytes = (if (publicRequest) "{}" else response).toByteArray()
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode(): Int {
                    assertEquals(if (publicRequest) null else session.cookieHeader, getRequestProperty("Cookie"))
                    return if (publicRequest) 200 else status
                }
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(bytes)
                override fun getErrorStream() = ByteArrayInputStream(bytes)
            }
        })
        val failure = runCatching {
            SyndicationTwitterVideoSource(http, session = { session })
                .resolve(TwitterStatusLink.parse("https://x.com/i/status/$id"))
        }.exceptionOrNull()
        assertEquals(2, requests)
        return (failure as? TwitterAccessException)?.reason
    }

    private fun graph(result: String): String = """{"data":{"tweetResult":{"result":$result}}}"""
    private fun ssrPage(payload: String): String = "<script>result:${'$'}R[14]={kind:\"Result.Ok\",value:${JSONObject.quote(payload)}}</script>"

    private fun assertFailure(expected: TwitterAccessFailure, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertEquals(expected, (failure as? TwitterAccessException)?.reason)
    }
}
