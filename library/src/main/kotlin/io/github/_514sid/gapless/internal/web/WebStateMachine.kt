package io.github._514sid.gapless.internal.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * A/B web slots. The WebViews are created on the first web asset, not up front: constructing one
 * throws when the device has no usable WebView provider (common on TV boxes while WebView updates),
 * and that must fail only the web asset, not every playlist. A slot whose renderer process dies is
 * destroyed and replaced, since an unhandled renderer death kills the app.
 */
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

    private val views = arrayOfNulls<WebView>(2)

    /** The URL each slot was last asked to load, or null while it shows the blank page. */
    private val loadedUrls = arrayOfNulls<String>(2)

    private var debuggingConfigured = false

    var renderState by mutableStateOf(WebPlayerState())
        private set

    private var pendingItem: PlaybackItem.Web? = null
    private var transitionJob: Job? = null
    private var refreshJob: Job? = null
    private var commitDeferred: CompletableDeferred<Unit>? = null

    /** True while the refresh loop is reloading the current page into the hidden slot. */
    private var isRefreshing = false

    /** True while play() waits for the hidden slot to paint before swapping to it. */
    private var awaitingSwap = false

    /** The page passed to [play] has been swapped in, painted (or after the paint timeout). */
    var onShown: ((playbackId: UUID) -> Unit)? = null

    var pageCommitted: Boolean = false
        private set

    private val inactiveSlot: Int
        get() = if (renderState.activeSlot == 0) 1 else 0

    private val inactiveWebView: WebView?
        get() = views[inactiveSlot]

    /**
     * Debug logging, off unless enabled with `adb shell setprop log.tag.WebStateMachine DEBUG`.
     * These lines include page URLs, which may carry tokens, so they stay out of release logs.
     */
    private fun logDebug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    private fun WebView.slotName(): String = if (this === views[0]) "Slot A" else "Slot B"

    private fun slotOf(view: WebView): Int = views.indexOfFirst { it === view }

    /** Creates any missing WebView. Returns an error message when WebView can't be created. */
    private fun ensureViews(): String? {
        if (views[0] != null && views[1] != null) return null
        try {
            if (!debuggingConfigured && config.enableChromeDebugging) {
                WebView.setWebContentsDebuggingEnabled(true)
                logDebug("WebView remote debugging enabled via Chrome.")
            }
            debuggingConfigured = true
            for (slot in views.indices) {
                if (views[slot] == null) views[slot] = createWebView(slot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebView is unavailable", e)
            return "WebView is unavailable: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            publishViews()
        }
        return null
    }

    private fun publishViews() {
        renderState = renderState.copy(webViewA = views[0], webViewB = views[1])
    }

    private fun WebView.loadPage(url: String) {
        val slot = slotOf(this)
        if (slot >= 0) loadedUrls[slot] = url
        post { loadUrl(url) }
    }

    fun prepare(item: PlaybackItem.Web) {
        transitionJob?.cancel()
        refreshJob?.cancel()

        ensureViews()?.let { message ->
            pendingItem = null
            pageCommitted = false
            onPreloadError?.invoke(item.assetId, message)
            return
        }

        commitDeferred = CompletableDeferred()
        pageCommitted = false
        pendingItem = item

        val targetView = inactiveWebView ?: return
        logDebug("prepare(): Preloading URL into ${targetView.slotName()} -> ${item.url}")
        targetView.loadPage(item.url)
    }

    fun play(item: PlaybackItem.Web) {
        transitionJob?.cancel()
        refreshJob?.cancel()

        ensureViews()?.let { message ->
            pendingItem = null
            onError?.invoke(message)
            return
        }

        val target = inactiveSlot
        val alreadyCommitted = pendingItem?.playbackId == item.playbackId && pageCommitted

        if (pendingItem?.playbackId != item.playbackId) {
            Log.w(TAG, "play(): Item was NOT pending! Forcing immediate load.")
            commitDeferred = CompletableDeferred()
            inactiveWebView?.loadPage(item.url)
        }

        pendingItem = null

        if (alreadyCommitted) {
            // The preloaded page has already painted, so swap synchronously. Deferring to a
            // coroutine here would let a following prepare() of a non-web asset clear the web
            // slots before the swap applies, leaving a blank slot on screen.
            logDebug("play(): Already committed, swapping immediately to slot $target.")
            renderState = renderState.copy(activeSlot = target)
            onShown?.invoke(item.playbackId)
            transitionJob = scope.launch {
                delay(150)
                if (renderState.activeSlot == target) {
                    inactiveWebView?.loadBlank()
                }
                if (item.refreshIntervalMs > 0L) {
                    startSeamlessRefreshLoop(item)
                }
            }
            return
        }

        transitionJob = scope.launch {
            logDebug("play(): Waiting for page to visually commit before swapping...")

            awaitingSwap = true
            val swap = try {
                withTimeout(5000) { commitDeferred?.await() }
                logDebug("play(): Pixels painted! Swapping instantly to slot $target.")
                true
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "play(): Page did not paint in time; swapping anyway.")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The page failed or its renderer died, and that was already reported. Swapping
                // would put a blank slot on screen and blank the page still showing.
                Log.w(TAG, "play(): Page failed before painting; keeping the current page: ${e.message}")
                false
            } finally {
                awaitingSwap = false
            }
            if (!swap) return@launch

            renderState = renderState.copy(activeSlot = target)
            onShown?.invoke(item.playbackId)

            delay(150)
            if (renderState.activeSlot == target) {
                inactiveWebView?.loadBlank()
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
                val targetView = inactiveWebView ?: continue

                logDebug("Refresh triggered: Preloading ${item.url} into ${targetView.slotName()}")
                commitDeferred = CompletableDeferred()
                isRefreshing = true
                targetView.loadPage(item.url)

                try {
                    withTimeout(5000) { commitDeferred?.await() }

                    if (pendingItem != null) break

                    logDebug("Refresh ready! Swapping instantly to slot $target.")
                    renderState = renderState.copy(activeSlot = target)

                    delay(150)
                    if (renderState.activeSlot == target) {
                        inactiveWebView?.loadBlank()
                    }
                } catch (_: Exception) {
                    Log.w(TAG, "Refresh failed or timed out. Keeping current page visible.")
                    inactiveWebView?.loadBlank()
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
        views.forEach { it?.loadBlank() }
        renderState = renderState.copy(activeSlot = 0)
    }

    fun release() {
        logDebug("release(): Destroying WebViews to free memory.")
        transitionJob?.cancel()
        refreshJob?.cancel()
        commitDeferred?.cancel()
        clear()
        for (slot in views.indices) {
            views[slot]?.destroy()
            views[slot] = null
        }
        publishViews()
    }

    /**
     * The renderer behind [view] crashed or was killed to reclaim memory. Returning false from
     * [WebViewClient.onRenderProcessGone] makes Android kill the app, so replace the WebView and
     * report the loss against whatever asset that slot was showing or loading.
     */
    private fun onRendererGone(view: WebView) {
        val slot = slotOf(view)
        if (slot < 0) return
        val hadPage = loadedUrls[slot] != null
        val wasActive = slot == renderState.activeSlot
        // Read before the deferred completes below, which lets play() resume and clear the flag.
        val swapPending = awaitingSwap
        val message = "WebView renderer gone on ${view.slotName()}"
        Log.e(TAG, message)

        // A dead WebView must leave the hierarchy before it is destroyed.
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        views[slot] = null
        loadedUrls[slot] = null
        try {
            views[slot] = createWebView(slot)
        } catch (e: Exception) {
            Log.e(TAG, "Could not replace WebView on slot $slot", e)
        }
        publishViews()

        if (!hadPage) return
        if (!wasActive) commitDeferred?.completeExceptionally(Exception(message))
        val pending = pendingItem
        when {
            wasActive -> {
                refreshJob?.cancel()
                onError?.invoke(message)
            }
            pending != null -> {
                pendingItem = null
                onPreloadError?.invoke(pending.assetId, message)
            }
            swapPending -> onError?.invoke(message)
            // A refresh or a page already swapped out: the visible page is unaffected.
            else -> Unit
        }
    }

    private fun createWebView(slot: Int) = WebView(context).apply {
        logDebug("createWebView(): Instantiating slot $slot")
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

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRendererGone(view)
                return true
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
        val slot = slotOf(this)
        if (slot >= 0) loadedUrls[slot] = null
        this.loadData(BLANK_PAGE, "text/html", "UTF-8")
    }
}
