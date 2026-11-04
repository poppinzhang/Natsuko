package com.poppinzhang.natsuko

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poppinzhang.natsuko.data.twitter.TwitterVideoMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwitterVideoMetadataTest {
    @Test
    fun selectsHighestBitrateMp4ForEachVideoAndPreservesAnimatedGif() {
        val result = TwitterVideoMetadata.parse("""
            {"id_str":"123","mediaDetails":[
              {"type":"photo"},
              {"type":"video","video_info":{"variants":[
                {"content_type":"video/mp4","bitrate":256000,"url":"https://video.twimg.com/vid/320x180/small.mp4"},
                {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/pl/master.m3u8"},
                {"content_type":"video/mp4","bitrate":2176000,"url":"https://video.twimg.com/vid/1280x720/big.mp4?tag=12"},
                {"content_type":"video/mp4","bitrate":9999999,"url":"https://evil.test/video.mp4"}]}},
              {"type":"animated_gif","video_info":{"variants":[
                {"content_type":"video/mp4","bitrate":0,"url":"https://video.twimg.com/tweet_video/gif.mp4"}]}}
            ]}
        """.trimIndent(), "123")
        assertEquals(2, result.size)
        assertEquals("https://video.twimg.com/vid/1280x720/big.mp4?tag=12", result[0].url)
        assertEquals("https://video.twimg.com/tweet_video/gif.mp4", result[1].url)
        assertTrue(result[1].animatedGif)
    }

    @Test
    fun unsupportedVideoRemainsAnIndividualFailureCandidate() {
        val result = TwitterVideoMetadata.parse("""{"id_str":"123","mediaDetails":[
          {"type":"video","video_info":{"variants":[{"content_type":"application/x-mpegURL","url":"https://video.twimg.com/playlist.m3u8"}]}},
          {"type":"video","video_info":{"variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/good.mp4"}]}}
        ]}""", "123")
        assertEquals(2, result.size)
        assertNull(result[0].url)
        assertNotNull(result[0].unavailableReason)
        assertNotNull(result[1].url)
    }

    @Test
    fun supportsLegacyPlayerAndUnifiedCardVideo() {
        val legacy = TwitterVideoMetadata.parse("""{"id_str":"123","video":{"isGif":true,"variants":[
          {"type":"video/mp4","src":"https://video.twimg.com/tweet_video/legacy.mp4"}
        ]}}""", "123")
        assertTrue(legacy.single().animatedGif)
        assertTrue(legacy.single().url!!.endsWith("legacy.mp4"))
        val card = """{"media_entities":{"media":{"type":"video","video_info":{"variants":[
          {"content_type":"video/mp4","url":"https://video.twimg.com/card.mp4"}
        ]}}}}"""
        val metadata = JSONObject().put("id_str", "123").put("card", JSONObject()
            .put("binding_values", JSONObject().put("unified_card", JSONObject().put("string_value", card))))
        assertEquals("https://video.twimg.com/card.mp4", TwitterVideoMetadata.parse(metadata.toString(), "123").single().url)
    }

    @Test
    fun rejectsEmptyMalformedWrongTweetAndPhotoOnlyResponses() {
        for (json in listOf("{}", "<html>unavailable</html>", """{"id_str":"456"}""", """{"id_str":"123","mediaDetails":[{"type":"photo"}]}""")) {
            val failure = runCatching { TwitterVideoMetadata.parse(json, "123") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.isNotBlank())
        }
    }
}
