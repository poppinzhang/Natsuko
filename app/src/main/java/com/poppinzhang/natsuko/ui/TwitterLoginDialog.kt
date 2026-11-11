@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.poppinzhang.natsuko.ui

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
) {
    val context = LocalContext.current
    val currentAuthenticated by rememberUpdatedState(onAuthenticated)
    val generation = remember { TwitterLoginBrowserData.nextGeneration() }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var origin by remember { mutableStateOf("https://x.com") }
    var submitting by remember { mutableStateOf(false) }

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
            val webView = WebView(context)
            // Assign ownership immediately, so partial initialization failures are also destroyed.
            browser = webView
            webView.apply {
                isSaveEnabled = false
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
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
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val blocked = if (request.isForMainFrame) !TwitterLoginUrl.isAllowed(request.url.toString())
                            else request.url.scheme !in setOf("https", "about", "data", "blob")
                        if (blocked) error = R.string.twitter_login_navigation_blocked
                        return blocked
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // This also covers form POSTs, for which shouldOverrideUrlLoading is not called.
                        if ((request.isForMainFrame && !TwitterLoginUrl.isAllowed(request.url.toString())) ||
                            request.url.scheme !in setOf("https", "about", "data", "blob")) {
                            view.post { error = R.string.twitter_login_navigation_blocked }
                            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        val pageOrigin = TwitterLoginUrl.origin(url)
                        if (pageOrigin == null) {
                            view.stopLoading()
                            error = R.string.twitter_login_navigation_blocked
                        } else {
                            origin = pageOrigin
                            error = null
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                        if (request.isForMainFrame) error = R.string.twitter_login_load_failed
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) error = R.string.twitter_login_load_failed
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                        handler.cancel()
                        error = R.string.twitter_login_secure_connection_failed
                    }
                }
                // No Javascript interface, script injection, WebView state saving, or external intents.
                loadUrl(TwitterLoginUrl.START)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            error = R.string.twitter_login_unavailable
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag("twitter-login-dialog"),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.twitter_login_title))
                            Text(origin, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    actions = {
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("twitter-login-cancel")) {
                            Text(stringResource(R.string.main_cancel))
                        }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = browser != null && !submitting,
                        onClick = {
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
                        modifier = Modifier.fillMaxWidth().testTag("twitter-login-continue"),
                    ) { Text(stringResource(R.string.twitter_login_continue)) }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    stringResource(R.string.twitter_login_help),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                val activeBrowser = browser
                if (activeBrowser != null) {
                    AndroidView(factory = { activeBrowser }, modifier = Modifier.fillMaxSize())
                } else if (error == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.twitter_login_loading))
                        }
                    }
                }
            }
        }
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
