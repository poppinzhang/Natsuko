package com.poppinzhang.natsuko

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.webkit.WebView
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poppinzhang.natsuko.data.twitter.TwitterLoginUrl
import com.poppinzhang.natsuko.ui.TwitterLoginDialog
import com.poppinzhang.natsuko.ui.theme.NatsukoTheme
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production dialog and settings; all documents, geometry scripts and input are test fixtures. */
@RunWith(AndroidJUnit4::class)
class TwitterLoginViewportTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val visible = mutableStateOf(true)
    @Volatile private var browser: WebView? = null

    @After fun closeDialog() {
        compose.runOnIdle {
            browser?.windowInsetsController?.hide(WindowInsets.Type.ime())
            visible.value = false
        }
    }

    @Test fun deviceWidthViewportFitsActualDialogBrowser() {
        showLogin("width=device-width, initial-scale=1")
        val page = metrics()
        val native = nativeMetrics()
        assertEquals(native.width / native.density, page.layoutWidth, 2.0)
        assertEquals(native.width / native.density, page.visualWidth, 2.0)
        assertEquals(native.height / native.density, page.visualHeight, 2.0)
        assertEquals(page.visualHeight, page.frame.height, 2.0)
        assertTrue("Fixture must not overflow horizontally", page.scrollWidth <= page.layoutWidth + 1)
        assertVisibleInBrowser(page.input, page)
        assertNativeControlsVisible()
    }

    @Test fun declaredViewportWidthIsHonoredByProductionBrowser() {
        // A device-width page alone also passes when WebView ignores viewport metadata entirely.
        showLogin("width=640")
        val page = metrics()
        assertEquals("The page's explicit viewport width must be honored", 640.0, page.layoutWidth, 2.0)
        assertTrue("The fixture has no content forcing a wider layout", page.scrollWidth <= 641.0)
        assertNativeControlsVisible()
    }

    @Test fun realKeyboardResizesDialogAndFormRemainsReachable() {
        showLogin("width=device-width, initial-scale=1")
        val initialNative = nativeMetrics()
        val initialPage = metrics()
        tap(initialPage.input.centerX, initialPage.input.centerY, initialPage)
        // Request the real system IME even on emulators configured with a hardware keyboard.
        instrumentation.runOnMainSync {
            checkNotNull(browser).windowInsetsController?.show(WindowInsets.Type.ime())
        }
        waitForKeyboardViewport(15_000, "IME shown and fixture input focused", initialNative, initialPage) {
            nativeMetrics().imeVisible && metrics().activeElement == "username"
        }
        waitForKeyboardViewport(10_000, "Native and CSS viewport resized", initialNative, initialPage) {
            val native = nativeMetrics()
            val page = metrics()
            native.height < initialNative.height &&
                page.visualHeight < initialPage.visualHeight &&
                kotlin.math.abs(native.height / native.density - page.visualHeight) <= 2.0
        }
        val focused = metrics()
        assertVisibleInBrowser(focused.input, focused)
        assertNativeControlsVisible()

        // Exercise real touch scrolling of the inner form, rather than scrolling it with JavaScript.
        var scrolled = metrics()
        repeat(12) {
            if (!isVisibleInBrowser(scrolled.submit, scrolled)) {
                swipeFormUp(scrolled)
                scrolled = metrics()
            }
        }
        assertTrue("The keyboard must remain open while reaching the submit button", nativeMetrics().imeVisible)
        assertVisibleInBrowser(scrolled.submit, scrolled)
        tap(scrolled.submit.centerX, scrolled.submit.centerY, scrolled)
        compose.waitUntil(5_000) { metrics().submitted }
        assertNativeControlsVisible()
        instrumentation.runOnMainSync {
            checkNotNull(browser).windowInsetsController?.hide(WindowInsets.Type.ime())
        }
        waitForKeyboardViewport(10_000, "Viewport restored after hiding IME", initialNative, initialPage) {
            val native = nativeMetrics()
            val page = metrics()
            !native.imeVisible && kotlin.math.abs(native.height - initialNative.height) <= 1 &&
                kotlin.math.abs(page.visualHeight - initialPage.visualHeight) <= 2.0
        }
        val restored = metrics()
        assertEquals("Closing the keyboard must restore the CSS viewport without residual padding",
            initialPage.visualHeight, restored.frame.height, 2.0)
        assertNativeControlsVisible()
    }

    private fun waitForKeyboardViewport(
        timeoutMillis: Long,
        step: String,
        beforeNative: NativeMetrics,
        beforePage: PageMetrics,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (failure: ComposeTimeoutException) {
            // Fixture geometry and focus ID only; never capture input values or account content.
            throw AssertionError(
                "$step timed out. Before native=$beforeNative, page=$beforePage; " +
                    "after native=${nativeMetrics()}, page=${metrics()}; ${fixtureDiagnostics()}",
                failure,
            )
        }
    }

    private fun showLogin(viewport: String) {
        compose.setContent {
            NatsukoTheme {
                if (visible.value) {
                    TwitterLoginDialog(
                        onAuthenticated = { error("Offline fixture must never authenticate") },
                        onCancel = { visible.value = false },
                        createBrowser = { context ->
                            OfflineLoginWebView(context, fixture(viewport)).also { browser = it }
                        },
                    )
                }
            }
        }
        compose.waitUntil(10_000) { browser != null }
        try {
            compose.waitUntil(10_000) {
                evaluate("document.readyState === 'complete' && !!document.getElementById('username')") == "true"
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Offline document did not become ready: ${fixtureDiagnostics()}", failure)
        }
        val native = nativeMetrics()
        assertTrue("Browser must have a nonempty viewport: ${fixtureDiagnostics()}", native.width > 0 && native.height > 0)
        compose.onNodeWithTag("twitter-login-loading").assertDoesNotExist()
    }

    /** This browser can only contain the fixture, and diagnostics deliberately omit document text. */
    private fun fixtureDiagnostics(): String {
        var native = ""
        instrumentation.runOnMainSync {
            val view = checkNotNull(browser)
            fun describe(candidate: View): String =
                "${candidate.javaClass.simpleName}(size=${candidate.width}x${candidate.height}, " +
                    "measured=${candidate.measuredWidth}x${candidate.measuredHeight}, " +
                    "params=${candidate.layoutParams?.width}x${candidate.layoutParams?.height})"
            val location = when (view.url) {
                null -> "null"
                TwitterLoginUrl.START -> "fixture-base"
                "about:blank" -> "about:blank"
                else -> "unexpected-url"
            }
            native = "${describe(view)}, attached=${view.isAttachedToWindow}, " +
                "parent=${(view.parent as? View)?.let(::describe)}, url=$location, " +
                "fixtureLoads=${(view as OfflineLoginWebView).fixtureLoads}"
        }
        val document = evaluate("""
            JSON.stringify({ready:document.readyState,
                ids:['frame','scroller','username','submit'].filter(id => !!document.getElementById(id)),
                innerWidth:window.innerWidth, innerHeight:window.innerHeight,
                clientWidth:document.documentElement.clientWidth, clientHeight:document.documentElement.clientHeight,
                frameHeight:document.getElementById('frame')?.getBoundingClientRect().height ?? null})
        """.trimIndent())
        return "$native, document=$document"
    }

    private fun assertNativeControlsVisible() {
        compose.onNodeWithTag("twitter-login-cancel").assertIsDisplayed()
        compose.onNodeWithTag("twitter-login-continue").assertIsDisplayed()
        val header = compose.onNodeWithTag("twitter-login-header").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("twitter-login-browser").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("twitter-login-footer").fetchSemanticsNode().boundsInRoot
        assertTrue(header.bottom <= page.top)
        assertTrue(page.bottom <= footer.top)
        assertTrue(page.height > 0)
    }

    private fun assertVisibleInBrowser(rect: CssRect, page: PageMetrics) {
        assertTrue("Fixture control is clipped: $rect, viewport=${page.visualWidth}x${page.visualHeight}",
            isVisibleInBrowser(rect, page))
    }

    private fun isVisibleInBrowser(rect: CssRect, page: PageMetrics): Boolean =
        rect.left >= page.visualLeft - 1 && rect.right <= page.visualLeft + page.visualWidth + 1 &&
            rect.top >= maxOf(page.visualTop, page.scroller.top) - 1 &&
            rect.bottom <= minOf(page.visualTop + page.visualHeight, page.scroller.bottom) + 1

    private fun tap(cssX: Double, cssY: Double, page: PageMetrics) {
        val native = nativeMetrics()
        val pixelsPerCss = native.width / page.visualWidth
        val x = (native.left + (cssX - page.visualLeft) * pixelsPerCss).toFloat()
        val y = (native.top + (cssY - page.visualTop) * pixelsPerCss).toFloat()
        val start = SystemClock.uptimeMillis()
        pointer(start, MotionEvent.ACTION_DOWN, x, y)
        pointer(start, MotionEvent.ACTION_UP, x, y)
        instrumentation.waitForIdleSync()
    }

    private fun swipeFormUp(page: PageMetrics) {
        val native = nativeMetrics()
        val pixelsPerCss = native.width / page.visualWidth
        val top = maxOf(page.scroller.top, page.visualTop)
        val bottom = minOf(page.scroller.bottom, page.visualTop + page.visualHeight)
        val x = (native.left + native.width * 0.5).toFloat()
        val fromY = (native.top + (top + (bottom - top) * 0.8 - page.visualTop) * pixelsPerCss).toFloat()
        val toY = (native.top + (top + (bottom - top) * 0.2 - page.visualTop) * pixelsPerCss).toFloat()
        val start = SystemClock.uptimeMillis()
        pointer(start, MotionEvent.ACTION_DOWN, x, fromY)
        for (step in 1..10) {
            SystemClock.sleep(16)
            pointer(start, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * step / 10)
        }
        pointer(start, MotionEvent.ACTION_UP, x, toY)
        instrumentation.waitForIdleSync()
        // Finish any inertial scrolling before using element coordinates for the next gesture.
        var previous = metrics().submit.top
        var unchanged = 0
        compose.waitUntil(5_000) {
            val current = metrics().submit.top
            unchanged = if (kotlin.math.abs(previous - current) < 0.5) unchanged + 1 else 0
            previous = current
            unchanged >= 3
        }
    }

    private fun pointer(start: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun nativeMetrics(): NativeMetrics {
        lateinit var result: NativeMetrics
        instrumentation.runOnMainSync {
            val view = checkNotNull(browser)
            val location = IntArray(2).also(view::getLocationOnScreen)
            val insets = view.rootWindowInsets
            result = NativeMetrics(
                location[0], location[1], view.width, view.height,
                view.resources.displayMetrics.density.toDouble(),
                insets?.isVisible(WindowInsets.Type.ime()) == true,
                insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0,
                insets?.getInsets(WindowInsets.Type.systemBars())?.bottom ?: 0,
                view.rootView.height,
            )
        }
        return result
    }

    private fun evaluate(script: String): String {
        val finished = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            checkNotNull(browser).evaluateJavascript(script) { value -> result = value; finished.countDown() }
        }
        check(finished.await(5, TimeUnit.SECONDS)) { "Fixture JavaScript callback timed out" }
        return checkNotNull(result)
    }

    private fun metrics(): PageMetrics {
        val result = evaluate("""
            (() => {
                const viewport = window.visualViewport;
                const rect = id => {
                    const r = document.getElementById(id).getBoundingClientRect();
                    return {left:r.left, top:r.top, right:r.right, bottom:r.bottom};
                };
                return JSON.stringify({
                    layoutWidth:document.documentElement.clientWidth,
                    layoutHeight:document.documentElement.clientHeight,
                    scrollWidth:document.documentElement.scrollWidth,
                    visualWidth:viewport.width, visualHeight:viewport.height,
                    visualLeft:viewport.offsetLeft, visualTop:viewport.offsetTop,
                    visualScale:viewport.scale, devicePixelRatio:window.devicePixelRatio,
                    frame:rect('frame'), scroller:rect('scroller'),
                    input:rect('username'), submit:rect('submit'),
                    activeElement:document.activeElement.id, submitted:window.fixtureSubmitted === true
                });
            })()
        """.trimIndent())
        val json = JSONObject(JSONTokener(result).nextValue() as String)
        return PageMetrics(
            json.getDouble("layoutWidth"), json.getDouble("layoutHeight"), json.getDouble("scrollWidth"),
            json.getDouble("visualWidth"), json.getDouble("visualHeight"),
            json.getDouble("visualLeft"), json.getDouble("visualTop"),
            json.getDouble("visualScale"), json.getDouble("devicePixelRatio"),
            json.rect("frame"), json.rect("scroller"), json.rect("input"), json.rect("submit"),
            json.getString("activeElement"), json.getBoolean("submitted"),
        )
    }

    private fun JSONObject.rect(name: String): CssRect = getJSONObject(name).let {
        CssRect(it.getDouble("left"), it.getDouble("top"), it.getDouble("right"), it.getDouble("bottom"))
    }

    private data class NativeMetrics(
        val left: Int, val top: Int, val width: Int, val height: Int, val density: Double, val imeVisible: Boolean,
        val imeBottom: Int, val systemBarsBottom: Int, val rootHeight: Int,
    )

    private data class CssRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    private data class PageMetrics(
        val layoutWidth: Double, val layoutHeight: Double, val scrollWidth: Double,
        val visualWidth: Double, val visualHeight: Double, val visualLeft: Double, val visualTop: Double,
        val visualScale: Double, val devicePixelRatio: Double,
        val frame: CssRect, val scroller: CssRect, val input: CssRect, val submit: CssRect,
        val activeElement: String, val submitted: Boolean,
    )

    private class OfflineLoginWebView(context: Context, private val html: String) : WebView(context) {
        var fixtureLoads = 0
            private set

        override fun loadUrl(url: String) {
            check(url == TwitterLoginUrl.START) { "Unexpected fixture navigation" }
            fixtureLoads++
            super.loadUrl(url)
        }

        override fun setWebViewClient(client: WebViewClient) {
            // Keep production navigation/security/loading callbacks. Only replace network transport:
            // loadDataWithBaseURL can emit about:blank, correctly rejected by the production client.
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    client.shouldInterceptRequest(view, request)?.let { return it }
                    val fixture = request.isForMainFrame && request.url.toString() == TwitterLoginUrl.START
                    return WebResourceResponse(
                        if (fixture) "text/html" else "text/plain", "UTF-8",
                        if (fixture) 200 else 403, if (fixture) "OK" else "Blocked",
                        emptyMap(), ByteArrayInputStream(if (fixture) html.toByteArray() else byteArrayOf()),
                    )
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    client.shouldOverrideUrlLoading(view, request)

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) =
                    client.onPageStarted(view, url, favicon)

                override fun onPageFinished(view: WebView, url: String) = client.onPageFinished(view, url)

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
                    client.onReceivedError(view, request, error)

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) =
                    client.onReceivedHttpError(view, request, response)

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) =
                    client.onReceivedSslError(view, handler, error)
            })
        }
    }

    private fun fixture(viewport: String) = """
        <!doctype html><html><head>
        <meta name="viewport" content="$viewport">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
        <style>
            * { box-sizing: border-box; }
            html, body { margin: 0; width: 100%; height: 100%; overflow: hidden; }
            body { font: 16px sans-serif; }
            #frame { position: fixed; inset: 0; height: 100dvh; display: flex; flex-direction: column; }
            header { flex: none; padding: 12px 16px; }
            #scroller { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 16px 16px; }
            label { display: block; margin-bottom: 8px; }
            input, button { display: block; width: 100%; min-height: 48px; font: inherit; }
            .space { height: 650px; }
        </style></head><body>
        <main id="frame"><header>Offline sign-in fixture</header><div id="scroller">
            <label for="username">Fixture input</label>
            <input id="username" type="text" autocomplete="off">
            <div class="space" aria-hidden="true"></div>
            <button id="submit" type="button" onclick="window.fixtureSubmitted = true">Fixture submit</button>
        </div></main></body></html>
    """.trimIndent()
}
