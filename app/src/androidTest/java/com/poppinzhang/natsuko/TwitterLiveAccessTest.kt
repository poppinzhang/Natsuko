package com.poppinzhang.natsuko

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.twitter.SyndicationTwitterVideoSource
import com.poppinzhang.natsuko.data.twitter.TwitterAccessException
import com.poppinzhang.natsuko.data.twitter.TwitterAccessFailure
import com.poppinzhang.natsuko.data.twitter.TwitterStatusLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional network smoke check. Routine tests remain offline and do not use an X account. */
@RunWith(AndroidJUnit4::class)
class TwitterLiveAccessTest {
    @Test
    fun publicExampleIsExplicitlyLoginGated() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("twitterLiveAccessCheck") == "true")
        val link = TwitterStatusLink.parse("https://x.com/BeataTart/status/2120307161339244627/video/1")
        val failure = runCatching { SyndicationTwitterVideoSource().resolve(link) }.exceptionOrNull()
        assertEquals(TwitterAccessFailure.LOGIN_REQUIRED, (failure as? TwitterAccessException)?.reason)
    }
}
