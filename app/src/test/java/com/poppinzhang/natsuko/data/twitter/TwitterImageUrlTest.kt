package com.poppinzhang.natsuko.data.twitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitterImageUrlTest {
    @Test
    fun acceptsOnlyOfficialHttpsMediaPaths() {
        listOf(
            "https://pbs.twimg.com/media/AbCdEfG.jpg",
            "https://PBS.TWIMG.COM/media/AbCdEfG.png",
            "https://pbs.twimg.com/media/AbCdEfG?format=webp&name=large",
            "https://pbs.twimg.com/media/AbCdEfG.jpg:large",
        ).forEach { assertTrue(it, TwitterImageUrl.isAllowed(it)) }

        listOf(
            "",
            "http://pbs.twimg.com/media/AbCdEfG.jpg",
            "https://pbs.twimg.com.evil.test/media/AbCdEfG.jpg",
            "https://pbs.twimg.com@evil.test/media/AbCdEfG.jpg",
            "https://pbs.twimg.com:8443/media/AbCdEfG.jpg",
            "https://pbs.twimg.com/media/",
            "https://pbs.twimg.com/profile_images/123/avatar.jpg",
            "https://pbs.twimg.com/ext_tw_video_thumb/123/pu/img/thumb.jpg",
            "https://pbs.twimg.com/media/../profile_images/123.jpg",
            "https://pbs.twimg.com/media/AbCdEfG.jpg#frag",
            "https://pbs.twimg.com/media/AbCdEfG.jpg\n",
            "file:///tmp/image.jpg",
        ).forEach { assertFalse(it, TwitterImageUrl.isAllowed(it)) }
    }

    @Test
    fun rewritesSupportedPhotoUrlsToOriginalResolutionWithSafeFallback() {
        val jpg = TwitterImageUrl.resolveVariants("https://pbs.twimg.com/media/AbCdEf1.jpg")
        assertNotNull(jpg)
        assertEquals("https://pbs.twimg.com/media/AbCdEf1?format=jpg&name=orig", jpg!!.primaryUrl)
        assertEquals("https://pbs.twimg.com/media/AbCdEf1.jpg", jpg.fallbackUrl)
        assertEquals("jpg", jpg.formatExtension)

        val pngQuery = TwitterImageUrl.resolveVariants("https://pbs.twimg.com/media/AbCdEf2?format=png&name=small")
        assertNotNull(pngQuery)
        assertEquals("https://pbs.twimg.com/media/AbCdEf2?format=png&name=orig", pngQuery!!.primaryUrl)
        assertEquals("https://pbs.twimg.com/media/AbCdEf2?format=png&name=small", pngQuery.fallbackUrl)
        assertEquals("png", pngQuery.formatExtension)

        val alreadyOrig = TwitterImageUrl.resolveVariants("https://pbs.twimg.com/media/AbCdEf3?format=webp&name=orig")
        assertNotNull(alreadyOrig)
        assertEquals("https://pbs.twimg.com/media/AbCdEf3?format=webp&name=orig", alreadyOrig!!.primaryUrl)
        assertNull(alreadyOrig.fallbackUrl)
        assertEquals("webp", alreadyOrig.formatExtension)

        val legacyJpeg = TwitterImageUrl.resolveVariants("https://pbs.twimg.com/media/AbCdEf4.jpeg:large")
        assertNotNull(legacyJpeg)
        assertEquals("https://pbs.twimg.com/media/AbCdEf4?format=jpg&name=orig", legacyJpeg!!.primaryUrl)
        assertEquals("https://pbs.twimg.com/media/AbCdEf4.jpeg", legacyJpeg.fallbackUrl)
        assertEquals("jpg", legacyJpeg.formatExtension)

        assertNull(TwitterImageUrl.resolveVariants("https://evil.test/media/AbCdEf5.jpg"))
    }
}
