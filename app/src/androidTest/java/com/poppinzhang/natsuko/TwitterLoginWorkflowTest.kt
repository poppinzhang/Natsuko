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
import com.poppinzhang.natsuko.data.twitter.TwitterVideoSelection
import com.poppinzhang.natsuko.model.ProcessingOutcome
import com.poppinzhang.natsuko.ui.AppScreen
import com.poppinzhang.natsuko.ui.NatsukoApp
import com.poppinzhang.natsuko.ui.NatsukoUiState
import com.poppinzhang.natsuko.ui.NatsukoViewModel
import com.poppinzhang.natsuko.ui.TwitterLoginStep
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.util.concurrent.atomic.AtomicInteger
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
        assertFalse(model.state.value.twitterSessionActive)
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
    private fun model(work: suspend (String, suspend (TwitterVideoSelection) -> TwitterDownloadScope) -> ProcessingOutcome): NatsukoViewModel {
        lateinit var model: NatsukoViewModel
        instrumentation.runOnMainSync {
            model = NatsukoViewModel(application, downloadTwitter = { link, choose, _ -> work(link, choose) })
            store.put("login-${number++}", model)
        }
        return model
    }
    companion object { private const val LINK = "https://x.com/user/status/1234567890123456789/video/2" }
}
