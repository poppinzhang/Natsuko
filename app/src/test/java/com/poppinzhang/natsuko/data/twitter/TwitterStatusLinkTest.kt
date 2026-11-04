package com.poppinzhang.natsuko.data.twitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TwitterStatusLinkTest {
    @Test
    fun acceptsOfficialHostsAndStatusPathsWithShareTextAndTrackingParameters() {
        for (host in listOf("x.com", "twitter.com", "www.x.com", "mobile.twitter.com", "m.twitter.com")) {
            for (path in listOf("user/status/123456", "i/status/123456", "i/web/status/123456", "statuses/123456", "user/status/123456/video/2")) {
                val parsed = TwitterStatusLink.parse("推荐视频（https://$host/$path?s=20&t=abc）。")
                assertEquals("123456", parsed.id)
                assertEquals("https://x.com/i/status/123456", parsed.canonicalUrl)
            }
        }
    }

    @Test
    fun duplicateLinksAreAllowedButDifferentTweetsAreRejected() {
        assertEquals("123", TwitterStatusLink.parse("https://x.com/i/status/123 https://twitter.com/user/status/123").id)
        assertThrows(IllegalArgumentException::class.java) {
            TwitterStatusLink.parse("https://x.com/i/status/123 https://twitter.com/user/status/456")
        }
    }

    @Test
    fun rejectsUntrustedHostsMalformedPathsAndNonTweets() {
        listOf(
            "", "https://x.com/user", "https://x.com.evil.test/user/status/123",
            "https://x.com@evil.test/user/status/123", "https://evil.test@x.com/user/status/123",
            "https://x.com:8080/user/status/123", "https://x.com/user/status/0",
            "https://x.com/user/status/123oops", "https://x.com/user/status/123/anything",
            "https://x.com/user/status/123%2Fvideo%2F1", "file:///user/status/123",
            "https://x.com/user/status/" + "1".repeat(21), "x".repeat(8193),
        ).forEach { input -> assertThrows(input, IllegalArgumentException::class.java) { TwitterStatusLink.parse(input) } }
    }

    @Test
    fun tokenMatchesJavascriptNumberConversionIncludingLargeIdsAndFractionalCarry() {
        mapOf(
            "1" to "bhi2ay3f28n", "20" to "6dq1a2xwd93",
            "1349794411333394432" to "39si5g9b29u",
            "665052190608723968" to "1m1bmpg2m2t",
            "1577992159732609025" to "3tpepijitpa",
            "18446744073709551615" to "18ps5lqos4lc",
        ).forEach { (id, expected) -> assertEquals(id, expected, TwitterSyndicationToken.create(id)) }
    }
}
