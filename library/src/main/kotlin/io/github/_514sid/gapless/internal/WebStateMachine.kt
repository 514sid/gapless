package io.github._514sid.gapless.internal

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@MainThread
@SuppressLint("SetJavaScriptEnabled")
internal class WebStateMachine(
    private val context: Context,
    private val scope: CoroutineScope,
    enableChromeDebugging: Boolean = true,
    var onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "WebStateMachine"
        private const val BLANK_PAGE =
            "<html><head><style>body { background-color: black; margin: 0; padding: 0; }</style></head><body></body></html>"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }

    init {
        if (enableChromeDebugging) {
            WebView.setWebContentsDebuggingEnabled(true)
            Log.d(TAG, "WebView remote debugging enabled via Chrome.")
        }
    }

    val webViewA: WebView = createWebView("Slot A")
    val webViewB: WebView = createWebView("Slot B")

    var renderState by mutableStateOf(
        WebPlayerState(webViewA = webViewA, webViewB = webViewB, activeSlot = 0)
    )
        private set

    private var pendingItem: PlaybackItem.Web? = null
    private var transitionJob: Job? = null
    private var refreshJob: Job? = null
    private var commitDeferred: CompletableDeferred<Unit>? = null

    private val inactiveSlot: Int
        get() = if (renderState.activeSlot == 0) 1 else 0

    private val inactiveWebView: WebView
        get() = if (renderState.activeSlot == 0) webViewB else webViewA

    private fun WebView.slotName(): String = if (this === webViewA) "Slot A" else "Slot B"

    fun prepare(item: PlaybackItem.Web) {
        transitionJob?.cancel()
        refreshJob?.cancel()

        commitDeferred = CompletableDeferred()
        pendingItem = item

        val targetView = inactiveWebView
        Log.d(TAG, "prepare(): Preloading URL into ${targetView.slotName()} -> ${item.url}")
        targetView.post { targetView.loadUrl(item.url) }
    }

    fun play(item: PlaybackItem.Web) {
        transitionJob?.cancel()
        refreshJob?.cancel()

        val target = inactiveSlot

        if (pendingItem?.playbackId != item.playbackId) {
            Log.w(TAG, "play(): Item was NOT pending! Forcing immediate load.")
            commitDeferred = CompletableDeferred()
            val targetView = inactiveWebView
            targetView.post { targetView.loadUrl(item.url) }
        }

        pendingItem = null

        transitionJob = scope.launch {
            Log.d(TAG, "play(): Waiting for page to visually commit before swapping...")

            try {
                withTimeout(5000) { commitDeferred?.await() }
                Log.d(TAG, "play(): Pixels painted! Swapping instantly to slot $target.")
            } catch (e: Exception) {
                Log.w(TAG, "play(): Force swapping! Wait failed or timed out: ${e.message}")
            }

            renderState = renderState.copy(activeSlot = target)

            delay(150)
            if (renderState.activeSlot == target) {
                inactiveWebView.loadBlank()
            }

            if (item.refreshIntervalMs > 0L) {
                startSeamlessRefreshLoop(item)
            }
        }
    }

    private fun startSeamlessRefreshLoop(item: PlaybackItem.Web) {
        Log.d(TAG, "Refresh loop started. Interval: ${item.refreshIntervalMs}ms")

        refreshJob = scope.launch {
            while (true) {
                delay(item.refreshIntervalMs)

                if (pendingItem != null) break

                val target = inactiveSlot
                val targetView = inactiveWebView

                Log.d(TAG, "Refresh triggered: Preloading ${item.url} into ${targetView.slotName()}")
                commitDeferred = CompletableDeferred()
                targetView.post { targetView.loadUrl(item.url) }

                try {
                    withTimeout(5000) { commitDeferred?.await() }

                    if (pendingItem != null) break

                    Log.d(TAG, "Refresh ready! Swapping instantly to slot $target.")
                    renderState = renderState.copy(activeSlot = target)

                    delay(150)
                    if (renderState.activeSlot == target) {
                        inactiveWebView.loadBlank()
                    }
                } catch (_: Exception) {
                    Log.w(TAG, "Refresh failed or timed out. Keeping current page visible.")
                    inactiveWebView.loadBlank()
                }
            }
        }
    }

    fun cancelPrepare() {
        Log.d(TAG, "cancelPrepare(): Aborting pending load.")
        transitionJob?.cancel()
        refreshJob?.cancel()
        commitDeferred?.cancel()
        pendingItem = null
        inactiveWebView.loadBlank()
    }

    fun clear() {
        Log.d(TAG, "clear(): Blanking both slots and resetting state.")
        transitionJob?.cancel()
        refreshJob?.cancel()
        commitDeferred?.cancel()
        pendingItem = null
        webViewA.loadBlank()
        webViewB.loadBlank()
        renderState = renderState.copy(activeSlot = 0)
    }

    fun release() {
        Log.d(TAG, "release(): Destroying WebViews to free memory.")
        transitionJob?.cancel()
        refreshJob?.cancel()
        commitDeferred?.cancel()
        clear()
        webViewA.destroy()
        webViewB.destroy()
    }

    private fun createWebView(slotIdentifier: String) = WebView(context).apply {
        Log.d(TAG, "createWebView(): Instantiating $slotIdentifier")
        setBackgroundColor(android.graphics.Color.BLACK)

        setInitialScale(100)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                Log.d(TAG, "${view.slotName()} redirecting to: ${request.url}")
                return false
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                if (!url.startsWith("data:text/html")) {
                    Log.i(TAG, "onPageCommitVisible(): ${view.slotName()} has successfully painted pixels! -> $url")
                    if (view === inactiveWebView) {
                        commitDeferred?.complete(Unit)
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val message = "Web Error on ${view.slotName()}: ${error.description}"
                    Log.e(TAG, message)
                    commitDeferred?.completeExceptionally(Exception(message))
                    onError?.invoke(message)
                    view.loadBlank()
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    val message = "HTTP Error on ${view.slotName()}: ${errorResponse.statusCode}"
                    Log.e(TAG, message)
                    commitDeferred?.completeExceptionally(Exception(message))
                    onError?.invoke(message)
                    view.loadBlank()
                }
            }
        }

        webChromeClient = WebChromeClient()

        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = DESKTOP_USER_AGENT
        }

        loadBlank()
    }

    private fun WebView.loadBlank() {
        this.loadData(BLANK_PAGE, "text/html", "UTF-8")
    }
}