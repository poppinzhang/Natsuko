package com.poppinzhang.natsuko

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poppinzhang.natsuko.data.twitter.SyndicationTwitterVideoSource
import com.poppinzhang.natsuko.data.twitter.TwitterAccessException
import com.poppinzhang.natsuko.data.twitter.TwitterAccessFailure
import com.poppinzhang.natsuko.data.twitter.TwitterHttpClient
import com.poppinzhang.natsuko.data.twitter.TwitterImageMetadata
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import com.poppinzhang.natsuko.data.twitter.TwitterWebMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwitterImageMetadataTest {
    private val id = "2120307161339244627"

    @Test
    fun extractsAllPhotosInOrderWithOriginalUrlsAndSkipsVideos() {
        val result = TwitterImageMetadata.parse(
            """
            {"id_str":"$id","mediaDetails":[
              {"type":"photo","media_url_https":"https://pbs.twimg.com/media/FirstImg.jpg"},
              {"type":"video","video_info":{"variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/v.mp4"}]}},
              {"type":"photo","media_url_https":"https://pbs.twimg.com/media/SecondImg.png"},
              {"type":"animated_gif","video_info":{"variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/g.mp4"}]}},
              {"type":"photo","media_url_https":"https://pbs.twimg.com/media/ThirdImg?format=webp&name=large"}
            ]}
            """.trimIndent(),
            id,
        )
        assertEquals(3, result.size)
        assertEquals(
            listOf(
                "https://pbs.twimg.com/media/FirstImg?format=jpg&name=orig",
                "https://pbs.twimg.com/media/SecondImg?format=png&name=orig",
                "https://pbs.twimg.com/media/ThirdImg?format=webp&name=orig",
            ),
            result.map { it.url },
        )
        assertEquals(
            listOf(
                "https://pbs.twimg.com/media/FirstImg.jpg",
                "https://pbs.twimg.com/media/SecondImg.png",
                "https://pbs.twimg.com/media/ThirdImg?format=webp&name=large",
            ),
            result.map { it.fallbackUrl },
        )
        assertEquals(listOf("jpg", "png", "webp"), result.map { it.formatExtension })
        assertEquals(listOf(1, 3, 5), result.map { it.mediaIndex })
    }

    @Test
    fun supportsPhotosFallbackUnifiedCardAndIndividualUntrustedItems() {
        val photosOnly = TwitterImageMetadata.parse(
            """{"id_str":"$id","photos":[
              {"url":"https://pbs.twimg.com/media/One.jpg"},
              {"url":"https://evil.test/media/Bad.jpg"},
              {"url":"https://pbs.twimg.com/media/Two.png"}
            ]}""",
            id,
        )
        assertEquals(3, photosOnly.size)
        assertEquals("https://pbs.twimg.com/media/One?format=jpg&name=orig", photosOnly[0].url)
        assertNull(photosOnly[1].url)
        assertNotNull(photosOnly[1].unavailableReason)
        assertEquals("https://pbs.twimg.com/media/Two?format=png&name=orig", photosOnly[2].url)

        val card = """{"media_entities":{
          "b":{"type":"photo","media_url_https":"https://pbs.twimg.com/media/CardB.jpg"},
          "a":{"type":"photo","media_url_https":"https://pbs.twimg.com/media/CardA.png"}
        }}"""
        val metadata = JSONObject().put("id_str", id).put(
            "card",
            JSONObject().put("binding_values", JSONObject().put("unified_card", JSONObject().put("string_value", card))),
        )
        val cardPhotos = TwitterImageMetadata.parse(metadata.toString(), id)
        assertEquals(
            listOf(
                "https://pbs.twimg.com/media/CardA?format=png&name=orig",
                "https://pbs.twimg.com/media/CardB?format=jpg&name=orig",
            ),
            cardPhotos.map { it.url },
        )
    }

    @Test
    fun rejectsMalformedWrongTweetAndVideoOnlyResponses() {
        for (json in listOf(
            "{}",
            "<html>unavailable</html>",
            """{"id_str":"456","mediaDetails":[{"type":"photo","media_url_https":"https://pbs.twimg.com/media/A.jpg"}]}""",
            """{"id_str":"$id","mediaDetails":[{"type":"video"}]}""",
        )) {
            val failure = runCatching { TwitterImageMetadata.parse(json, id) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.isNotBlank())
        }
    }

    @Test
    fun graphqlAndPublicPageParseFocalPhotosAndDetectAccessGates() = runBlocking {
        val result = """{"__typename":"TweetWithVisibilityResults","tweet":{
          "__typename":"Tweet","rest_id":"$id","legacy":{"extended_entities":{"media":[
            {"type":"photo","media_url_https":"https://pbs.twimg.com/media/G1.jpg"},
            {"type":"photo","media_url_https":"https://pbs.twimg.com/media/G2.png"}
          ]}},"quoted_status_result":{"result":{"legacy":{"extended_entities":{"media":[
            {"type":"photo","media_url_https":"https://pbs.twimg.com/media/Quoted.jpg"}
          ]}}}}
        }}"""
        val graphImages = TwitterWebMetadata.parseImagesGraphql("""{"data":{"tweetResult":{"result":$result}}}""", id, false)
        assertEquals(
            listOf(
                "https://pbs.twimg.com/media/G1?format=jpg&name=orig",
                "https://pbs.twimg.com/media/G2?format=png&name=orig",
            ),
            graphImages.map { it.url },
        )

        val loginPayload = """{"data":{"tweet_result_by_rest_id":{"rest_id":"$id","result":{"__typename":"TweetTombstone","tombstone":{
            "__typename":"BlurredMediaTombstone","text":{"text":"To view this media, you’ll need to log in to X."}}}}}}"""
        val loginPage = "<script>result:${'$'}R[14]={kind:\"Result.Ok\",value:${JSONObject.quote(loginPayload)}}</script>"
        val loginError = runCatching { TwitterWebMetadata.parseImagesPublicPage(loginPage, id) }.exceptionOrNull()
        assertEquals(TwitterAccessFailure.LOGIN_REQUIRED, (loginError as? TwitterAccessException)?.reason)

        // Fallback from incomplete syndication to SSR page login gate works for images too.
        val http = TwitterHttpClient({ url ->
            val body = when (url.host) {
                "cdn.syndication.twimg.com" -> "{}"
                "api.x.com" -> "{}"
                else -> loginPage
            }.toByteArray()
            object : HttpURLConnection(URL("https://x.com/i/status/$id")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = body.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(body)
                override fun getOutputStream() = ByteArrayOutputStream()
            }
        })
        val resolvedFailure = runCatching {
            SyndicationTwitterVideoSource(http).resolveImages(TwitterStatusLink.parse("https://x.com/i/status/$id"))
        }.exceptionOrNull()
        assertEquals(TwitterAccessFailure.LOGIN_REQUIRED, (resolvedFailure as? TwitterAccessException)?.reason)
    }
}
