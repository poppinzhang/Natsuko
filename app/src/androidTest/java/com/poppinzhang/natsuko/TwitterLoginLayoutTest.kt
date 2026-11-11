package com.poppinzhang.natsuko

import android.net.Uri
import android.view.View
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.poppinzhang.natsuko.data.twitter.TwitterLoginUrl
import com.poppinzhang.natsuko.ui.TwitterLoginContent
import com.poppinzhang.natsuko.ui.TwitterLoginWebViewClient
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises a real WebView with local HTML, without X requests or account data. */
@RunWith(AndroidJUnit4::class)
class TwitterLoginLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val availableHeight = mutableStateOf(640.dp)
    private var browser: WebView? = null
    private lateinit var host: View
    private var continued = false
    private var cancelled = false
    private val loading = mutableStateOf(false)
    private val pageError = mutableStateOf<Int?>(null)

    @Test fun loginHasNoHelpBannerOrToggleAndControlsRemainUsable() {
        showLogin()
        compose.onNodeWithTag("twitter-login-help-toggle").assertDoesNotExist()
        compose.onNodeWithTag("twitter-login-help").assertDoesNotExist()
        assertSeparateRegions()
        compose.onNodeWithTag("twitter-login-continue").performClick()
        compose.onNodeWithTag("twitter-login-cancel").performClick()
        assertTrue(continued)
        assertTrue(cancelled)
    }

    @Test fun enlargedTextAndErrorsDoNotCoverBrowserInShortWindow() {
        availableHeight.value = 360.dp
        showLogin(fontScale = 2f, error = R.string.twitter_login_incomplete)
        assertSeparateRegions()
        val page = bounds("twitter-login-dialog")
        assertTrue(bounds("twitter-login-error").height <= page.height * 0.15f + 1f)
        assertTrue("Browser keeps a scrollable viewport", bounds("twitter-login-browser").height > 0f)
        compose.onNodeWithTag("twitter-login-continue").performClick()
        assertTrue(continued)
    }

    @Test fun browserResizesBetweenHeaderAndFooterWhenAvailableHeightShrinks() {
        loading.value = true
        showLogin(error = R.string.twitter_login_load_failed)
        val initialHeight = bounds("twitter-login-browser").height
        // Models the reduced content constraints delivered when the IME or split-screen opens.
        compose.runOnIdle { availableHeight.value = 320.dp }
        assertSeparateRegions()
        assertTrue(bounds("twitter-login-browser").height < initialHeight)
        compose.onNodeWithTag("twitter-login-continue").performClick()
        assertTrue(continued)
    }

    @Test fun loadingRemainsVisibleAfterBrowserCreationUntilDocumentFinishes() {
        val resourceRequested = CountDownLatch(1)
        val releaseResource = CountDownLatch(1)
        val mainPaths = CopyOnWriteArrayList<String>()
        loading.value = true
        try {
            showLogin(onLoad = { view ->
                view.webViewClient = fixtureClient(resourceRequested, releaseResource, mainPaths = mainPaths)
                view.loadUrl(TwitterLoginUrl.START)
            })
            compose.waitUntil(10_000) { resourceRequested.count == 0L }
            compose.onNodeWithTag("twitter-login-loading").assertIsDisplayed()
            assertSeparateRegions()
            assertNull(pageError.value)
            assertEquals(listOf("/"), mainPaths)
            releaseResource.countDown()
            compose.waitUntil(10_000) { !loading.value }
            compose.onNodeWithTag("twitter-login-loading").assertDoesNotExist()
            assertSeparateRegions()
        } finally {
            releaseResource.countDown()
        }
    }

    @Test fun mainFrameErrorEndsLoadingButSubresourceFailureDoesNot() {
        lateinit var client: TwitterLoginWebViewClient
        loading.value = true
        showLogin(onLoad = { view ->
            client = fixtureClient()
            view.webViewClient = client
            view.loadUrl(TwitterLoginUrl.START)
        })
        compose.waitUntil(10_000) { !loading.value }
        assertNull(pageError.value)
        val failure = WebResourceResponse(
            "text/html", "UTF-8", 503, "Unavailable", emptyMap(), ByteArrayInputStream(byteArrayOf()),
        )
        // WebView does not consistently deliver HTTP callbacks for intercepted responses.
        // Simulate the platform callback against the production client and real rendered WebView.
        compose.runOnIdle {
            client.onPageStarted(checkNotNull(browser), TwitterLoginUrl.START, null)
            client.onReceivedHttpError(checkNotNull(browser), fixtureRequest(mainFrame = false), failure)
        }
        compose.onNodeWithTag("twitter-login-loading").assertIsDisplayed()
        assertNull(pageError.value)
        compose.runOnIdle {
            client.onReceivedHttpError(checkNotNull(browser), fixtureRequest(mainFrame = true), failure)
        }
        assertEquals(R.string.twitter_login_load_failed, pageError.value)
        compose.onNodeWithTag("twitter-login-loading").assertDoesNotExist()
        compose.onNodeWithTag("twitter-login-error").assertIsDisplayed()
        assertSeparateRegions()
    }

    private fun fixtureRequest(mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(TwitterLoginUrl.START)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    /** Actual production callbacks, with offline responses and a deliberately slow subresource. */
    private fun fixtureClient(
        resourceRequested: CountDownLatch = CountDownLatch(0),
        releaseResource: CountDownLatch = CountDownLatch(0),
        mainPaths: MutableList<String> = CopyOnWriteArrayList(),
    ) = object : TwitterLoginWebViewClient(
        onOrigin = {},
        onError = { pageError.value = it },
        onLoading = { loading.value = it },
    ) {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            super.shouldInterceptRequest(view, request)?.let { return it }
            if (request.isForMainFrame) mainPaths += request.url.path.orEmpty()
            val image = request.url.path == "/slow.svg"
            if (image) {
                resourceRequested.countDown()
                check(releaseResource.await(15, TimeUnit.SECONDS))
            }
            val body = if (image) "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'/>"
                else "<html><body><a href='/login-fixture'>Sign in</a><img src='/slow.svg'></body></html>"
            return WebResourceResponse(
                if (image) "image/svg+xml" else "text/html", "UTF-8",
                200, "OK",
                emptyMap(), ByteArrayInputStream(body.toByteArray()),
            )
        }
    }

    private fun showLogin(
        fontScale: Float = 1f,
        error: Int? = null,
        onLoad: ((WebView) -> Unit)? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            host = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NatsukoTheme {
                    Box(Modifier.fillMaxSize()) {
                        TwitterLoginContent(
                            origin = "https://x.com",
                            error = error ?: pageError.value,
                            loading = loading.value,
                            canContinue = true,
                            onContinue = { continued = true },
                            onCancel = { cancelled = true },
                            modifier = Modifier.width(360.dp).height(availableHeight.value),
                        ) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).also {
                                        browser = it
                                        if (onLoad != null) onLoad(it) else it.loadData(
                                            "<html><body style='margin:0;min-height:1600px'>" +
                                                "<input placeholder='Offline sign-in form'></body></html>",
                                            "text/html",
                                            "UTF-8",
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize().clipToBounds(),
                                onRelease = { it.destroy(); browser = null },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertSeparateRegions() {
        val header = bounds("twitter-login-header")
        val viewport = bounds("twitter-login-browser")
        val footer = bounds("twitter-login-footer")
        val page = bounds("twitter-login-dialog")
        assertTrue(header.bottom <= viewport.top)
        compose.onNodeWithTag("twitter-login-help").assertDoesNotExist()
        if (loading.value) {
            val progress = bounds("twitter-login-loading")
            assertTrue(header.bottom <= progress.top)
            assertTrue(progress.bottom <= viewport.top)
        }
        assertTrue(viewport.bottom <= footer.top)
        assertTrue(footer.bottom <= page.bottom)
        assertTrue(viewport.height > 0f)
        compose.onNodeWithTag("twitter-login-cancel").assertIsDisplayed()
        compose.onNodeWithTag("twitter-login-continue").assertIsDisplayed()

        // Check actual Android View bounds, not only the Compose wrapper's semantics.
        compose.runOnIdle {
            val webView = checkNotNull(browser)
            val nativePosition = IntArray(2).also(webView::getLocationInWindow)
            val hostPosition = IntArray(2).also(host::getLocationInWindow)
            val left = (nativePosition[0] - hostPosition[0]).toFloat()
            val top = (nativePosition[1] - hostPosition[1]).toFloat()
            assertEquals(viewport.left, left, 1f)
            assertEquals(viewport.top, top, 1f)
            assertEquals(viewport.right, left + webView.width, 1f)
            assertEquals(viewport.bottom, top + webView.height, 1f)
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot
}
