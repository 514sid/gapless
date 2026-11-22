package io.github._514sid.gapless.internal.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github._514sid.gapless.GaplessWebConfig
import io.github._514sid.gapless.internal.PlaybackItem
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
    private val config: GaplessWebConfig = GaplessWebConfig(),
) {
    var onError: ((String) -> Unit)? = null
    var onPreloadError: ((assetId: String, message: String) -> Unit)? = null
    companion object {
        private const val TAG = "WebStateMachine"
        private const val BLANK_PAGE =
            "<html><head><style>body { background-color: black; margin: 0; padding: 0; }</style></head><body></body></html>"
    }

    init {
        if (config.enableChromeDebugging) {
            WebView.setWebContentsDebuggingEnabled(true)
            logDebug("WebView remote debugging enabled via Chrome.")
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

    /** True while the refresh loop is reloading the current page into the hidden slot. */
    private var isRefreshing = false

    var pageCommitted: Boolean = false
        private set

    private val inactiveSlot: Int
        get() = if (renderState.activeSlot == 0) 1 else 0

    private val inactiveWebView: WebView
        get() = if (renderState.activeSlot == 0) webViewB else webViewA

    /**
     * Debug logging, off unless enabled with `adb shell setprop log.tag.WebStateMachine DEBUG`.
     * These lines include page URLs, which may carry tokens, so they stay out of release logs.
     */
    private fun logDebug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    private fun WebView.slotName(): String = if (this === webViewA) "Slot A" else "Slot B"

    fun prepare(item: PlaybackItem.Web) {
        transitionJob?.cancel()
        refreshJob?.cancel()

        commitDeferred = CompletableDeferred()
        pageCommitted = false
        pendingItem = item

        val targetView = inactiveWebView
        logDebug("prepare(): Preloading URL into ${targetView.slotName()} -> ${item.url}")
        targetView.post { targetView.loadUrl(item.url) }
    }

    fun play(item: PlaybackItem.Web) {
        transitionJob?.cancel()
        refreshJob?.cancel()

        val target = inactiveSlot
        val alreadyCommitted = pendingItem?.playbackId == item.playbackId && pageCommitted

        if (pendingItem?.playbackId != item.playbackId) {
            Log.w(TAG, "play(): Item was NOT pending! Forcing immediate load.")
            commitDeferred = CompletableDeferred()
            val targetView = inactiveWebView
            targetView.post { targetView.loadUrl(item.url) }
        }

        pendingItem = null

        if (alreadyCommitted) {
            // The preloaded page has already painted, so swap synchronously. Deferring to a
            // coroutine here would let a following prepare() of a non-web asset clear the web
            // slots before the swap applies, leaving a blank slot on screen.
            logDebug("play(): Already committed, swapping immediately to slot $target.")
            renderState = renderState.copy(activeSlot = target)
            transitionJob = scope.launch {
                delay(150)
                if (renderState.activeSlot == target) {
                    inactiveWebView.loadBlank()
                }
                if (item.refreshIntervalMs > 0L) {
                    startSeamlessRefreshLoop(item)
                }
            }
            return
        }

        transitionJob = scope.launch {
            logDebug("play(): Waiting for page to visually commit before swapping...")

            try {
                withTimeout(5000) { commitDeferred?.await() }
                logDebug("play(): Pixels painted! Swapping instantly to slot $target.")
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
        logDebug("Refresh loop started. Interval: ${item.refreshIntervalMs}ms")

        refreshJob = scope.launch {
            while (true) {
                delay(item.refreshIntervalMs)

                if (pendingItem != null) break

                val target = inactiveSlot
                val targetView = inactiveWebView

                logDebug("Refresh triggered: Preloading ${item.url} into ${targetView.slotName()}")
                commitDeferred = CompletableDeferred()
                isRefreshing = true
                targetView.post { targetView.loadUrl(item.url) }

                try {
                    withTimeout(5000) { commitDeferred?.await() }

                    if (pendingItem != null) break

                    logDebug("Refresh ready! Swapping instantly to slot $target.")
                    renderState = renderState.copy(activeSlot = target)

                    delay(150)
                    if (renderState.activeSlot == target) {
                        inactiveWebView.loadBlank()
                    }
                } catch (_: Exception) {
                    Log.w(TAG, "Refresh failed or timed out. Keeping current page visible.")
                    inactiveWebView.loadBlank()
                } finally {
                    isRefreshing = false
                }
            }
        }
    }

    fun clear() {
        logDebug("clear(): Blanking both slots and resetting state.")
        transitionJob?.cancel()
        refreshJob?.cancel()
        commitDeferred?.cancel()
        pendingItem = null
        webViewA.loadBlank()
        webViewB.loadBlank()
        renderState = renderState.copy(activeSlot = 0)
    }

    fun release() {
        logDebug("release(): Destroying WebViews to free memory.")
        transitionJob?.cancel()
        refreshJob?.cancel()
        commitDeferred?.cancel()
        clear()
        webViewA.destroy()
        webViewB.destroy()
    }

    private fun createWebView(slotIdentifier: String) = WebView(context).apply {
        logDebug("createWebView(): Instantiating $slotIdentifier")
        setBackgroundColor(android.graphics.Color.BLACK)

        setInitialScale(100)

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                logDebug("${view.slotName()} redirecting to: ${request.url}")
                return false
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                if (!url.startsWith("data:text/html")) {
                    logDebug("onPageCommitVisible(): ${view.slotName()} has successfully painted pixels! -> $url")
                    if (view === inactiveWebView) {
                        pageCommitted = true
                        commitDeferred?.complete(Unit)
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val message = "Web Error on ${view.slotName()}: ${error.description}"
                    Log.e(TAG, message)
                    reportMainFrameError(view, message)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    val message = "HTTP Error on ${view.slotName()}: ${errorResponse.statusCode}"
                    Log.e(TAG, message)
                    reportMainFrameError(view, message)
                }
            }
        }

        webChromeClient = WebChromeClient()

        CookieManager.getInstance().setAcceptThirdPartyCookies(this, config.allowThirdPartyCookies)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            mixedContentMode = config.mixedContentMode
            useWideViewPort = true
            loadWithOverviewMode = true
            minimumFontSize = 1
            minimumLogicalFontSize = 1
            if (config.userAgent != null) userAgentString = config.userAgent
        }

        loadBlank()
    }

    private fun reportMainFrameError(view: WebView, message: String) {
        commitDeferred?.completeExceptionally(Exception(message))
        val pending = pendingItem
        when {
            pending != null -> onPreloadError?.invoke(pending.assetId, message)
            // A failed refresh leaves the current page on screen; nothing for the host to act on.
            isRefreshing && view === inactiveWebView -> Unit
            else -> onError?.invoke(message)
        }
        view.loadBlank()
    }

    private fun WebView.loadBlank() {
        this.loadData(BLANK_PAGE, "text/html", "UTF-8")
    }
}