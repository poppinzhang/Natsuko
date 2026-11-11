package com.poppinzhang.natsuko

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.OutputStoragePreferences
import com.poppinzhang.natsuko.data.twitter.TwitterAccessException
import com.poppinzhang.natsuko.data.twitter.TwitterAccessFailure
import com.poppinzhang.natsuko.data.twitter.TwitterDownloadScope
import com.poppinzhang.natsuko.data.twitter.TwitterSession
import com.poppinzhang.natsuko.data.twitter.TwitterSessionStore
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSelection
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.TwitterLoginStep
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No real account/network: the native login completion is driven with a synthetic session. */
@RunWith(AndroidJUnit4::class)
class TwitterLoginWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val store = ViewModelStore()
    private val preferences = OutputStoragePreferences(application)
    private var wasSaving = false
    private var number = 0

    @Before fun setup() { wasSaving = preferences.read().enabled; preferences.setEnabled(false) }
    @After fun cleanup() {
        instrumentation.runOnMainSync { store.clear() }
        preferences.setEnabled(wasSaving)
    }

    @Test fun explicitLoginGateWaitsThenResumesOriginalScopeSelection() = runBlocking {
        val calls = AtomicInteger()
        val model = model { link, choose ->
            assertEquals(LINK, link)
            if (calls.incrementAndGet() == 1) throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED)
            assertEquals(TwitterDownloadScope.CURRENT, choose(TwitterVideoSelection(2, 2)))
            ProcessingOutcome(emptyList(), emptyList())
        }
        start(model)
        await(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        assertEquals(1, calls.get())
        // Imports, editing and account disconnect cannot replace a suspended download.
        model.updateTwitterLink("https://x.com/other/status/987")
        model.handleShareIntent(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            component = ComponentName(application, "${application.packageName}.share.DownloadTwitterVideoShare")
            putExtra(Intent.EXTRA_TEXT, "https://x.com/other/status/987")
        })
        assertEquals(LINK, model.state.value.twitterLink)
        authenticate(model)
        await(model) { it.twitterVideoSelection != null }
        assertTrue(model.state.value.twitterSessionActive)
        model.chooseTwitterDownloadScope(TwitterDownloadScope.CURRENT)
        val result = await(model) { !it.isProcessing && it.outcome != null }
        assertEquals(2, calls.get())
        assertNull(result.twitterLoginStep)
        assertNull(result.twitterVideoSelection)
        model.openTwitterDownload()
        model.forgetTwitterSession()
        await(model) { !it.isProcessing && !it.twitterSessionActive }
        assertFalse(model.state.value.twitterSessionActive)
        assertEquals(LINK, model.state.value.twitterLink)
    }

    @Test fun cancellingConsentKeepsLinkAndPermitsRetry() = runBlocking {
        val calls = AtomicInteger()
        val model = model { _, _ ->
            calls.incrementAndGet()
            throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED)
        }
        compose.setContent { NatsukoTheme { NatsukoApp(model) } }
        start(model)
        await(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        compose.onNodeWithTag("twitter-login-consent").assertIsDisplayed()
        compose.onNodeWithTag("twitter-login-decline").performClick()
        val cancelled = await(model) { !it.isProcessing }
        assertEquals(AppScreen.TWITTER_DOWNLOAD, cancelled.screen)
        assertEquals(LINK, cancelled.twitterLink)
        assertNull(cancelled.twitterLoginStep)
        assertNull(cancelled.outcome)
        assertFalse(cancelled.twitterSessionActive)
        model.downloadTwitterVideo()
        await(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        assertEquals(2, calls.get())
        model.cancelProcessing()
    }

    @Test fun deletedProtectedUnknownAndOtherFailuresNeverPromptForLogin() = runBlocking {
        for (reason in TwitterAccessFailure.entries.filter { it != TwitterAccessFailure.LOGIN_REQUIRED }) {
            val model = model { _, _ -> throw TwitterAccessException(reason) }
            start(model)
            val failed = await(model) { !it.isProcessing && it.errorMessage != null }
            assertNull("$reason", failed.twitterLoginStep)
            assertFalse(failed.twitterSessionActive)
            assertEquals(TwitterAccessException(reason).userMessage, failed.errorMessage)
        }
    }

    @Test fun consentSurvivesCompositionRecreationWithoutAnotherRequest() = runBlocking {
        val calls = AtomicInteger()
        val model = model { _, _ ->
            calls.incrementAndGet()
            throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED)
        }
        val generation = mutableIntStateOf(0)
        compose.setContent { key(generation.intValue) { NatsukoTheme { NatsukoApp(model) } } }
        start(model)
        await(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        compose.runOnIdle { generation.intValue++ }
        compose.onNodeWithTag("twitter-login-consent").assertIsDisplayed()
        assertEquals(1, calls.get())
        model.cancelProcessing()
        await(model) { !it.isProcessing }
        Unit
    }

    @Test fun cancelDuringLoginIgnoresLateCompletion() = runBlocking {
        val calls = AtomicInteger()
        val model = model { _, _ ->
            calls.incrementAndGet()
            throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED)
        }
        start(model)
        await(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        model.acceptTwitterLogin()
        assertEquals(TwitterLoginStep.SIGN_IN, model.state.value.twitterLoginStep)
        model.cancelProcessing()
        await(model) { !it.isProcessing }
        model.completeTwitterLogin(session())
        assertFalse(model.state.value.twitterSessionActive)
        assertNull(model.state.value.twitterLoginStep)
        assertEquals(1, calls.get())
    }

    @Test fun expiredExistingSessionIsRediagnosedBeforeAnyNewPrompt() = runBlocking {
        val calls = AtomicInteger()
        val model = model { _, _ ->
            when (calls.incrementAndGet()) {
                1 -> throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED)
                2 -> ProcessingOutcome(emptyList(), emptyList())
                3 -> throw TwitterAccessException(TwitterAccessFailure.SESSION_EXPIRED)
                else -> throw TwitterAccessException(TwitterAccessFailure.PROTECTED)
            }
        }
        start(model)
        await(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        authenticate(model)
        await(model) { !it.isProcessing && it.outcome != null }
        model.openTwitterDownload()
        model.downloadTwitterVideo()
        val failed = await(model) { !it.isProcessing && it.errorMessage != null }
        assertEquals(4, calls.get())
        assertFalse(failed.twitterSessionActive)
        assertNull(failed.twitterLoginStep)
        assertEquals(TwitterAccessException(TwitterAccessFailure.PROTECTED).userMessage, failed.errorMessage)
    }

    @Test fun failedAuthenticatedRetryDoesNotLoopLogin() = runBlocking {
        val calls = AtomicInteger()
        val model = model { _, _ ->
            throw TwitterAccessException(if (calls.incrementAndGet() == 1) TwitterAccessFailure.LOGIN_REQUIRED else TwitterAccessFailure.SESSION_EXPIRED)
        }
        start(model)
        await(model) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        authenticate(model)
        val failed = await(model) { !it.isProcessing && it.errorMessage != null }
        assertEquals(2, calls.get())
        assertFalse(failed.twitterSessionActive)
        assertNull(failed.twitterLoginStep)
    }

    @Test fun manualLoginDoesNotDownloadUntilUserStartsAndCanBeDisconnected() = runBlocking {
        val calls = AtomicInteger()
        val model = model { _, _ -> calls.incrementAndGet(); ProcessingOutcome(emptyList(), emptyList()) }
        model.openTwitterDownload()
        model.updateTwitterLink(LINK)
        model.signInToTwitter()
        await(model) { it.twitterLoginStep == TwitterLoginStep.SIGN_IN }
        model.completeTwitterLogin(session())
        await(model) { !it.isProcessing && it.twitterSessionActive }
        assertEquals(0, calls.get())
        assertEquals(LINK, model.state.value.twitterLink)
        model.downloadTwitterVideo()
        await(model) { !it.isProcessing && it.outcome != null }
        assertEquals(1, calls.get())
        model.openTwitterDownload()
        model.forgetTwitterSession()
        await(model) { !it.isProcessing && !it.twitterSessionActive }
        assertFalse(model.state.value.twitterSessionActive)
    }

    @Test fun completedLoginSurvivesViewModelReplacementAndDisconnectPersists() = runBlocking {
        val sessions = MemorySessions()
        val first = model(sessions) { _, _ -> ProcessingOutcome(emptyList(), emptyList()) }
        first.openTwitterDownload()
        first.signInToTwitter()
        await(first) { it.twitterLoginStep == TwitterLoginStep.SIGN_IN }
        first.completeTwitterLogin(session())
        await(first) { !it.isProcessing && it.twitterSessionActive }
        instrumentation.runOnMainSync { store.clear() }

        val calls = AtomicInteger()
        val restored = model(sessions) { _, _ -> calls.incrementAndGet(); ProcessingOutcome(emptyList(), emptyList()) }
        start(restored)
        await(restored) { !it.isProcessing && it.outcome != null }
        assertTrue(restored.state.value.twitterSessionActive)
        assertEquals(1, calls.get())
        assertNull(restored.state.value.twitterLoginStep)
        restored.openTwitterDownload()
        restored.forgetTwitterSession()
        await(restored) { !it.isProcessing && !it.twitterSessionActive }
        assertNull(sessions.value)
        val disconnected = model(sessions) { _, _ -> throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED) }
        start(disconnected)
        await(disconnected) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        assertFalse(disconnected.state.value.twitterSessionActive)
        disconnected.cancelProcessing()
    }

    @Test fun disconnectInAnotherLiveViewModelIsObservedBeforeDownload(): Unit = runBlocking {
        val sessions = MemorySessions(session())
        val first = model(sessions) { _, _ -> ProcessingOutcome(emptyList(), emptyList()) }
        val calls = AtomicInteger()
        val second = model(sessions) { _, _ ->
            calls.incrementAndGet()
            throw TwitterAccessException(TwitterAccessFailure.LOGIN_REQUIRED)
        }
        await(first) { it.twitterSessionActive }
        await(second) { it.twitterSessionActive }

        first.forgetTwitterSession()
        await(first) { !it.isProcessing && !it.twitterSessionActive }
        assertNull(sessions.value)
        start(second)
        val refreshed = await(second) { it.twitterLoginStep == TwitterLoginStep.CONSENT }
        assertFalse(refreshed.twitterSessionActive)
        assertEquals(1, calls.get())
        second.cancelProcessing()
        await(second) { !it.isProcessing }
    }

    @Test fun loginSavedByAnotherLiveViewModelIsObservedBeforeDownload() = runBlocking {
        val sessions = MemorySessions()
        val first = model(sessions) { _, _ -> ProcessingOutcome(emptyList(), emptyList()) }
        var activeDuringDownload = false
        lateinit var second: NatsukoViewModel
        second = model(sessions) { _, _ ->
            activeDuringDownload = second.state.value.twitterSessionActive
            ProcessingOutcome(emptyList(), emptyList())
        }
        // Complete an anonymous action so this instance has already restored its empty store.
        start(second)
        await(second) { !it.isProcessing && it.outcome != null }
        assertFalse(activeDuringDownload)

        first.openTwitterDownload()
        first.signInToTwitter()
        await(first) { it.twitterLoginStep == TwitterLoginStep.SIGN_IN }
        first.completeTwitterLogin(session())
        await(first) { !it.isProcessing && it.twitterSessionActive }
        start(second)
        await(second) { !it.isProcessing && it.outcome != null }
        assertTrue(activeDuringDownload)
        assertTrue(second.state.value.twitterSessionActive)
        assertNull(second.state.value.twitterLoginStep)
    }

    @Test fun failedRefreshDoesNotUsePreviouslyRestoredCredentials() = runBlocking {
        val reads = AtomicInteger()
        val sessions = MemorySessions(session(), beforeRead = {
            if (reads.incrementAndGet() == 2) throw IOException("Synthetic refresh failure")
        })
        val calls = AtomicInteger()
        val model = model(sessions) { _, _ ->
            calls.incrementAndGet()
            ProcessingOutcome(emptyList(), emptyList())
        }
        await(model) { it.twitterSessionActive }
        start(model)
        val failed = await(model) { !it.isProcessing && it.errorMessage != null }
        assertEquals(com.poppinzhang.natsuko.model.text(R.string.twitter_auth_session_load_failed), failed.errorMessage)
        assertEquals(0, calls.get())
        assertTrue(failed.twitterSessionActive)
        assertNotNull(sessions.value)
        model.downloadTwitterVideo()
        await(model) { !it.isProcessing && it.outcome != null }
        assertEquals(1, calls.get())
    }

    @Test fun downloadWaitsForRestoreAndNonAuthenticationFailuresKeepSavedSession() = runBlocking {
        val readStarted = CountDownLatch(1)
        val readAllowed = CountDownLatch(1)
        val sessions = MemorySessions(session(), beforeRead = {
            readStarted.countDown()
            check(readAllowed.await(5, TimeUnit.SECONDS))
        })
        val calls = AtomicInteger()
        val model = model(sessions) { _, _ -> calls.incrementAndGet(); throw TwitterAccessException(TwitterAccessFailure.UNKNOWN) }
        start(model)
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        assertEquals(0, calls.get())
        readAllowed.countDown()
        await(model) { !it.isProcessing && it.errorMessage != null }
        assertTrue(model.state.value.twitterSessionActive)
        assertNotNull(sessions.value)

        for (failure in listOf(TwitterAccessFailure.PROTECTED, TwitterAccessFailure.RATE_LIMITED)) {
            val next = model(sessions) { _, _ -> throw TwitterAccessException(failure) }
            start(next)
            await(next) { !it.isProcessing && it.errorMessage != null }
            assertTrue(next.state.value.twitterSessionActive)
            assertNotNull(sessions.value)
        }
        val offline = model(sessions) { _, _ -> throw IOException("Synthetic offline failure") }
        start(offline)
        await(offline) { !it.isProcessing && it.errorMessage != null }
        assertTrue(offline.state.value.twitterSessionActive)
        assertNotNull(sessions.value)
    }

    @Test fun expiredRestoredSessionIsDeletedBeforeAnonymousRetry() = runBlocking {
        val sessions = MemorySessions(session())
        val calls = AtomicInteger()
        val model = model(sessions) { _, _ ->
            if (calls.incrementAndGet() == 1) throw TwitterAccessException(TwitterAccessFailure.SESSION_EXPIRED)
            assertNull(sessions.value)
            throw TwitterAccessException(TwitterAccessFailure.PROTECTED)
        }
        start(model)
        await(model) { !it.isProcessing && it.errorMessage != null }
        assertEquals(2, calls.get())
        assertFalse(model.state.value.twitterSessionActive)
        assertNull(sessions.value)
    }

    @Test fun disconnectWaitsForPendingRestoreSoOldCredentialsCannotReappear() = runBlocking {
        val readStarted = CountDownLatch(1)
        val readAllowed = CountDownLatch(1)
        val sessions = MemorySessions(session(), beforeRead = {
            readStarted.countDown()
            check(readAllowed.await(5, TimeUnit.SECONDS))
        })
        val model = model(sessions) { _, _ -> ProcessingOutcome(emptyList(), emptyList()) }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        model.forgetTwitterSession()
        await(model) { it.isProcessing }
        readAllowed.countDown()
        await(model) { !it.isProcessing && !it.twitterSessionActive }
        assertNull(sessions.value)
    }

    @Test fun saveFailureIsReportedWithoutClaimingAnActiveSession() = runBlocking {
        val sessions = MemorySessions(beforeWrite = { throw IOException("Synthetic disk failure") })
        val model = model(sessions) { _, _ -> fail("Manual login must not download"); ProcessingOutcome(emptyList(), emptyList()) }
        model.openTwitterDownload()
        model.signInToTwitter()
        await(model) { it.twitterLoginStep == TwitterLoginStep.SIGN_IN }
        model.completeTwitterLogin(session())
        val failed = await(model) { !it.isProcessing && it.errorMessage != null }
        assertEquals(com.poppinzhang.natsuko.model.text(R.string.twitter_auth_session_save_failed), failed.errorMessage)
        assertFalse(failed.twitterSessionActive)
        assertNull(sessions.value)
    }

    @Test fun transientRestoreFailureRetriesBeforeAnyDownloadAndKeepsOriginalSession() = runBlocking {
        val reads = AtomicInteger()
        val sessions = MemorySessions(session(), beforeRead = {
            if (reads.incrementAndGet() <= 2) throw IOException("Synthetic temporarily unavailable key")
        })
        val calls = AtomicInteger()
        val model = model(sessions) { _, _ -> calls.incrementAndGet(); ProcessingOutcome(emptyList(), emptyList()) }
        await(model) { it.errorMessage != null }
        start(model)
        await(model) { !it.isProcessing && it.errorMessage != null && reads.get() == 2 }
        assertEquals(0, calls.get())
        assertNotNull(sessions.value)
        assertNull(model.state.value.twitterLoginStep)
        model.downloadTwitterVideo()
        await(model) { !it.isProcessing && it.outcome != null }
        assertTrue(model.state.value.twitterSessionActive)
        assertEquals(1, calls.get())
        assertEquals(3, reads.get())
    }

    @Test fun failedDisconnectKeepsClearActionAvailableForRetry() = runBlocking {
        val clears = AtomicInteger()
        val sessions = MemorySessions(session(), beforeClear = {
            if (clears.incrementAndGet() == 1) throw IOException("Synthetic temporarily unavailable key")
        })
        val model = model(sessions) { _, _ -> ProcessingOutcome(emptyList(), emptyList()) }
        await(model) { it.twitterSessionActive }
        model.forgetTwitterSession()
        val failed = await(model) { !it.isProcessing && it.errorMessage != null }
        assertEquals(com.poppinzhang.natsuko.model.text(R.string.twitter_auth_session_clear_failed), failed.errorMessage)
        assertTrue(failed.twitterSessionActive)
        assertNotNull(sessions.value)
        model.forgetTwitterSession()
        await(model) { !it.isProcessing && !it.twitterSessionActive }
        assertNull(sessions.value)
        assertEquals(2, clears.get())
    }

    @Test fun cancellingAfterLoginCompletionDoesNotInterruptDurableSave() = runBlocking {
        val writeStarted = CountDownLatch(1)
        val writeAllowed = CountDownLatch(1)
        val sessions = MemorySessions(beforeWrite = {
            writeStarted.countDown()
            check(writeAllowed.await(5, TimeUnit.SECONDS))
        })
        val model = model(sessions) { _, _ -> ProcessingOutcome(emptyList(), emptyList()) }
        model.openTwitterDownload()
        model.signInToTwitter()
        await(model) { it.twitterLoginStep == TwitterLoginStep.SIGN_IN }
        model.completeTwitterLogin(session())
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
        model.cancelProcessing()
        writeAllowed.countDown()
        await(model) { !it.isProcessing && it.twitterSessionActive }
        assertNotNull(sessions.value)
    }

    private fun session() = checkNotNull(TwitterSession.fromCookieHeader("auth_token=synthetic-test-only; ct0=synthetic-csrf"))
    private fun authenticate(model: NatsukoViewModel) {
        model.acceptTwitterLogin()
        model.completeTwitterLogin(session())
    }
    private fun start(model: NatsukoViewModel) {
        model.openTwitterDownload(); model.updateTwitterLink(LINK); model.downloadTwitterVideo()
    }
    private suspend fun await(model: NatsukoViewModel, predicate: (NatsukoUiState) -> Boolean) =
        withTimeout(10_000) { model.state.first(predicate) }
    private fun model(
        sessions: TwitterSessionStore = MemorySessions(),
        work: suspend (String, suspend (TwitterVideoSelection) -> TwitterDownloadScope) -> ProcessingOutcome,
    ): NatsukoViewModel {
        lateinit var model: NatsukoViewModel
        instrumentation.runOnMainSync {
            model = NatsukoViewModel(application, twitterSessions = sessions, downloadTwitter = { link, choose, _ -> work(link, choose) })
            store.put("login-${number++}", model)
        }
        return model
    }

    private class MemorySessions(
        @Volatile var value: TwitterSession? = null,
        val beforeRead: () -> Unit = {},
        val beforeWrite: () -> Unit = {},
        val beforeClear: () -> Unit = {},
    ) : TwitterSessionStore {
        override fun read(): TwitterSession? { beforeRead(); return value }
        override fun write(session: TwitterSession) { beforeWrite(); value = session }
        override fun clear() { beforeClear(); value = null }
    }
    companion object { private const val LINK = "https://x.com/user/status/1234567890123456789/video/2" }
}
