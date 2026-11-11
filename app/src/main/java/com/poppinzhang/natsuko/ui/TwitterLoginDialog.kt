package com.poppinzhang.natsuko.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.poppinzhang.natsuko.R
import com.poppinzhang.natsuko.data.twitter.TwitterLoginUrl
import com.poppinzhang.natsuko.data.twitter.TwitterSession
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** X owns the login form; the app reads session cookies only after the native Continue action. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TwitterLoginDialog(
    onAuthenticated: (TwitterSession) -> Unit,
    onCancel: () -> Unit,
    createBrowser: (Context) -> WebView = ::WebView,
) {
    val context = LocalContext.current
    val currentAuthenticated by rememberUpdatedState(onAuthenticated)
    val generation = remember { TwitterLoginBrowserData.nextGeneration() }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var origin by remember { mutableStateOf("https://x.com") }
    var submitting by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(generation) {
        onDispose {
            browser?.let { webView ->
                // Stop network work before clearing cookies; never retain an Activity in the cleaner.
                webView.stopLoading()
                webView.clearHistory()
                webView.clearCache(true)
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.removeAllViews()
                webView.destroy()
            }
            TwitterLoginBrowserData.clearWhenClosed(generation)
        }
    }
    LaunchedEffect(generation) {
        try {
            if (!TwitterLoginBrowserData.prepare(generation)) return@LaunchedEffect
            currentCoroutineContext().ensureActive()
            val webView = createBrowser(context)
            // Assign ownership immediately, so partial initialization failures are also destroyed.
            browser = webView
            webView.apply {
                // AndroidView otherwise gives its child WRAP_CONTENT LayoutParams. Chromium
                // forces the CSS layout viewport height to zero in that mode, even when Compose
                // measures a nonzero native view. X uses dvh/percentage heights for its form.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isSaveEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    safeBrowsingEnabled = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(webView, false)
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    // The default onCreateWindow and file chooser handlers reject these requests.
                }
                webViewClient = TwitterLoginWebViewClient(
                    onOrigin = { origin = it },
                    onError = { error = it },
                    onLoading = { loading = it },
                )
                // No Javascript interface, script injection, WebView state saving, or external intents.
                loadUrl(TwitterLoginUrl.START)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            loading = false
            error = R.string.twitter_login_unavailable
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Surface(Modifier.fillMaxSize()) {
            TwitterLoginContent(
                origin = origin,
                error = error,
                canContinue = browser != null && !submitting,
                loading = loading,
                onContinue = {
                    val session = runCatching {
                        if (!TwitterLoginUrl.isAllowed(browser?.url.orEmpty())) null
                        else TwitterSession.fromCookieHeader(CookieManager.getInstance().getCookie("https://x.com/"))
                    }.getOrNull()
                    if (session == null) error = R.string.twitter_login_incomplete
                    else {
                        submitting = true
                        currentAuthenticated(session)
                    }
                },
                onCancel = onCancel,
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            ) {
                val activeBrowser = browser
                if (activeBrowser != null) {
                    AndroidView(factory = { activeBrowser }, modifier = Modifier.fillMaxSize().clipToBounds())
                }
            }
        }
    }
}

/** Native chrome and the browser always occupy separate measured, clipped regions. */
@Composable
internal fun TwitterLoginContent(
    origin: String,
    error: Int?,
    canContinue: Boolean,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    browserContent: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.testTag("twitter-login-dialog")) {
        // Keep long errors and enlarged text scrollable without consuming the whole page.
        val errorHeight = minOf(80.dp, maxHeight * 0.15f)
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                    .testTag("twitter-login-header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.twitter_login_title),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(origin, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onCancel, modifier = Modifier.testTag("twitter-login-cancel")) {
                    Text(stringResource(R.string.main_cancel), maxLines = 1)
                }
            }
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("twitter-login-loading"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.twitter_login_loading),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // AndroidView does not clip its drawing by default. Explicitly keep the WebView inside
            // its weighted viewport, including when the keyboard reduces the available height.
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("twitter-login-browser")) {
                browserContent()
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("twitter-login-footer"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                error?.let {
                    Text(
                        stringResource(it),
                        modifier = Modifier.fillMaxWidth().heightIn(max = errorHeight)
                            .verticalScroll(rememberScrollState()).testTag("twitter-login-error"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    enabled = canContinue,
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().testTag("twitter-login-continue"),
                ) { Text(stringResource(R.string.twitter_login_continue)) }
            }
        }
    }
}

/** Loading describes the main document, not completion of X's asynchronous sign-in workflow. */
internal open class TwitterLoginWebViewClient(
    private val onOrigin: (String) -> Unit,
    private val onError: (Int?) -> Unit,
    private val onLoading: (Boolean) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val blocked = if (request.isForMainFrame) !TwitterLoginUrl.isAllowed(request.url.toString())
            else request.url.scheme !in setOf("https", "about", "data", "blob")
        if (blocked) {
            if (request.isForMainFrame) onLoading(false)
            onError(R.string.twitter_login_navigation_blocked)
        }
        return blocked
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // This also covers form POSTs, for which shouldOverrideUrlLoading is not called.
        if ((request.isForMainFrame && !TwitterLoginUrl.isAllowed(request.url.toString())) ||
            request.url.scheme !in setOf("https", "about", "data", "blob")) {
            view.post {
                if (request.isForMainFrame) onLoading(false)
                onError(R.string.twitter_login_navigation_blocked)
            }
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val pageOrigin = TwitterLoginUrl.origin(url)
        if (pageOrigin == null) {
            view.stopLoading()
            onLoading(false)
            onError(R.string.twitter_login_navigation_blocked)
        } else {
            onLoading(true)
            onOrigin(pageOrigin)
            onError(null)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        // Ignore a stale finish callback if a newer main-frame navigation has already started.
        if (url == view.url) onLoading(false)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
        if (request.isForMainFrame) {
            onLoading(false)
            onError(R.string.twitter_login_load_failed)
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) {
            onLoading(false)
            onError(R.string.twitter_login_load_failed)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
        handler.cancel()
        onLoading(false)
        onError(R.string.twitter_login_secure_connection_failed)
    }
}

/** Serializes asynchronous cookie removal across close/reopen and configuration recreation. */
private object TwitterLoginBrowserData {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clearing = Mutex()
    private var generation = 0L

    // All entry points run on Main. A stale dialog may not erase a newer dialog's login cookies.
    fun nextGeneration(): Long = ++generation

    suspend fun prepare(owner: Long): Boolean = withContext(NonCancellable) {
        clearing.withLock {
            if (owner != generation) return@withLock false
            clear()
            owner == generation
        }
    }

    fun clearWhenClosed(owner: Long) {
        scope.launch {
            runCatching {
                clearing.withLock {
                    if (owner == generation) clear()
                }
            }
        }
    }

    private suspend fun clear() {
        suspendCoroutine { continuation ->
            CookieManager.getInstance().removeAllCookies { continuation.resume(Unit) }
        }
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}
