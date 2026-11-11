package com.poppinzhang.natsuko.data.twitter

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run twice with twitterSessionProcessStep=seed, then verify, to cross a real process boundary. */
@RunWith(AndroidJUnit4::class)
class TwitterSessionProcessTest {
    @Test fun savedSessionSurvivesProcessExit() {
        val step = InstrumentationRegistry.getArguments().getString("twitterSessionProcessStep")
        assumeTrue(step == "seed" || step == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "twitter-process-test.enc")
        val pid = File(context.noBackupFilesDir, "twitter-process-test.pid")
        val store = EncryptedTwitterSessionStore(file, "natsuko.test.twitter-process")
        if (step == "seed") {
            store.clear()
            store.write(checkNotNull(TwitterSession.fromCookieHeader(COOKIES)))
            pid.writeText(Process.myPid().toString())
        } else {
            try {
                assertNotEquals("Verification must run in a new process", pid.readText().toInt(), Process.myPid())
                assertEquals(COOKIES, store.read()?.cookieHeader)
                store.clear()
                assertNull(EncryptedTwitterSessionStore(file, "natsuko.test.twitter-process").read())
            } finally {
                store.clear()
                pid.delete()
            }
        }
    }

    private companion object {
        const val COOKIES = "auth_token=synthetic-process-test; ct0=synthetic-process-csrf"
    }
}
