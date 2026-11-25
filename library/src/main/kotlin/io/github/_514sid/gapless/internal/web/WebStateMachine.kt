package io.github._514sid.gapless.internal.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github._514sid.gapless.GaplessWebConfig
import io.github._514sid.gapless.internal.ContentSize
import io.github._514sid.gapless.internal.Framing
import io.github._514sid.gapless.internal.PlaybackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A/B web slots. The WebViews are created on the first web asset, not up front: constructing one
 * throws when the device has no usable WebView provider (common on TV boxes while WebView updates),
 * and that must fail only the web asset, not every playlist. A slot whose renderer process dies is
 * destroyed and replaced, since an unhandled renderer death kills the app. A renderer that stops
 * responding (it no longer paints pages or runs scripts, while its memory keeps growing) is killed
 * and replaced the same way; see [onPaintTimeout].
 *
 * @param respondsToScript Whether the renderer behind a WebView still runs scripts. Replaced in tests.
 */
@MainThread
@SuppressLint("SetJavaScriptEnabled")
internal class WebStateMachine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: GaplessWebConfig = GaplessWebConfig(),
    private val respondsToScript: suspend (WebView) -> Boolean = ::runsScript,
) {
    var onError: ((String) -> Unit)? = null
    var onPreloadError: ((assetId: String, message: String) -> Unit)? = null
    companion object {
        private const val TAG = "WebStateMachine"
        private const val PAINT_TIMEOUT_MS = 5_000L
        // Kept short so the paint timeout and this check end before the controller's default
        // readyTimeoutMs of 10 seconds, which would report the hung page as started.
        private const val SCRIPT_TIMEOUT_MS = 3_000L
        internal const val RENDERER_HUNG = "WebView renderer stopped responding"

        /** Runs a trivial script in [view]'s page; false when the renderer doesn't answer in time. */
        private suspend fun runsScript(view: WebView): Boolean {
            val answered = CompletableDeferred<Unit>()
            view.evaluateJavascript("1") { answered.complete(Unit) }
            return withTimeoutOrNull(SCRIPT_TIMEOUT_MS) { answered.await() } != null
        }
        private const val BLANK_PAGE =
            "<html><head><style>body { background-color: black; margin: 0; padding: 0; }</style></head><body></body></html>"
    }

    private val views = arrayOfNulls<WebView>(2)

    /** The URL each slot was last asked to load, or null while it shows the blank page. */
    private val loadedUrls = arrayOfNulls<String>(2)

    /** The page each slot is loading or showing, for its script and readiness; null when blank. */
    private val slotItems = arrayOfNulls<PlaybackItem.Web>(2)

    /** Per slot: the document-start scripts registered for its page, removed when the page goes. */
    private val scriptHandlers = arrayOf(mutableListOf<ScriptHandler>(), mutableListOf<ScriptHandler>())

    /** Per slot: the page has painted. */
    private val painted = BooleanArray(2)

    /** Per slot: the page has called `gapless.ready()` since it started loading. */
    private val pageReady = BooleanArray(2)

    private val documentStartScripts: Boolean by lazy {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
    }

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

    private fun WebView.loadPage(item: PlaybackItem.Web) {
        val slot = slotOf(this)
        if (slot >= 0) {
            loadedUrls[slot] = item.url
            setSlotItem(slot, item)
        }
        post { if (item.requestHeaders.isEmpty()) loadUrl(item.url) else loadUrl(item.url, item.requestHeaders) }
    }

    /**
     * Gives [slot] the script and readiness rules of [item], or none for a blank page. A document
     * start script registered for the slot's previous page is removed first, so it can't run in
     * the next one.
     */
    private fun setSlotItem(slot: Int, item: PlaybackItem.Web?) {
        scriptHandlers[slot].forEach { it.remove() }
        scriptHandlers[slot].clear()
        slotItems[slot] = item
        painted[slot] = false
        pageReady[slot] = false
        setLayout(slot, WebLayout(item?.viewport, item?.framing ?: Framing()))
        val view = views[slot] ?: return
        val scripts = listOfNotNull(
            item?.viewport?.let { viewportScript(it) to setOf("*") },
            item?.javaScript?.let { it to item.javaScriptOrigins },
        )
        if (scripts.isEmpty()) return
        if (documentStartScripts) {
            for ((script, origins) in scripts) {
                try {
                    scriptHandlers[slot] += WebViewCompat.addDocumentStartJavaScript(view, script, origins)
                } catch (e: IllegalArgumentException) {
                    // A malformed origin rule. The page plays without its script rather than not at all.
                    Log.e(TAG, "Can't add the script for ${item?.assetId}: ${e.message}")
                }
            }
        } else if (!scriptFallbackWarned) {
            scriptFallbackWarned = true
            Log.w(TAG, "This WebView can't run scripts at document start; they run once the page paints")
        }
    }

    private var scriptFallbackWarned = false

    private fun setLayout(slot: Int, layout: WebLayout) {
        renderState = if (slot == 0) renderState.copy(layoutA = layout) else renderState.copy(layoutB = layout)
    }

    /** Whether [url] is in one of [origins], as the document start script rules match it. */
    private fun originAllowed(url: String, origins: Set<String>): Boolean {
        if ("*" in origins) return true
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port" in origins
    }

    /**
     * [slot]'s page may be shown once it has painted and, if it asked to be waited for, called
     * `gapless.ready()`. Only the hidden slot being prepared counts.
     */
    private fun maybeCommit(slot: Int) {
        if (slot != inactiveSlot || !painted[slot]) return
        if (slotItems[slot]?.waitForReady == true && !pageReady[slot]) return
        pageCommitted = true
        commitDeferred?.complete(Unit)
    }

    /** `gapless.ready()`, called by the page. Main thread. */
    private fun onPageReady(view: WebView) {
        val slot = slotOf(view)
        if (slot < 0 || slotItems[slot] == null || pageReady[slot]) return
        logDebug("${view.slotName()} page called gapless.ready()")
        pageReady[slot] = true
        maybeCommit(slot)
    }

    /** The `gapless` object pages see. Its calls arrive on a WebView thread. */
    private inner class PageBridge(private val view: WebView) {
        @JavascriptInterface
        fun ready() {
            // Not view.post(): that waits until the view is attached to a window.
            mainHandler.post { onPageReady(view) }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
        targetView.loadPage(item)
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
            inactiveWebView?.loadPage(item)
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
                withTimeout(PAINT_TIMEOUT_MS) { commitDeferred?.await() }
                logDebug("play(): Pixels painted! Swapping instantly to slot $target.")
                true
            } catch (_: TimeoutCancellationException) {
                if (onPaintTimeout(target)) {
                    Log.w(TAG, "play(): Page did not paint in time; swapping anyway.")
                    true
                } else {
                    onError?.invoke(RENDERER_HUNG)
                    false
                }
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
                targetView.loadPage(item)

                try {
                    withTimeout(PAINT_TIMEOUT_MS) { commitDeferred?.await() }

                    if (pendingItem != null) break

                    logDebug("Refresh ready! Swapping instantly to slot $target.")
                    renderState = renderState.copy(activeSlot = target)

                    delay(150)
                    if (renderState.activeSlot == target) {
                        inactiveWebView?.loadBlank()
                    }
                } catch (_: TimeoutCancellationException) {
                    if (!onPaintTimeout(target)) {
                        // The page on screen ran in the same renderer, so it is gone too.
                        onError?.invoke(RENDERER_HUNG)
                        break
                    }
                    Log.w(TAG, "Refresh timed out. Keeping current page visible.")
                    inactiveWebView?.loadBlank()
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

        replaceView(slot)
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

    private fun replaceView(slot: Int) {
        val view = views[slot] ?: return
        // A dead WebView must leave the hierarchy before it is destroyed.
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        views[slot] = null
        loadedUrls[slot] = null
        scriptHandlers[slot].clear() // went with the destroyed WebView
        setLayout(slot, WebLayout())
        slotItems[slot] = null
        painted[slot] = false
        pageReady[slot] = false
        try {
            views[slot] = createWebView(slot)
        } catch (e: Exception) {
            Log.e(TAG, "Could not replace WebView on slot $slot", e)
        }
    }

    /**
     * A page in [slot] did not paint within [PAINT_TIMEOUT_MS]. A slow page still runs scripts, so
     * the caller goes on as before. A renderer that doesn't is hung: on a TV in a soak test one
     * stopped painting every page for hours while it grew past 500 MB, until the system killed it.
     * Kill it now and start both slots on a fresh one. Returns whether the renderer is responsive.
     */
    private suspend fun onPaintTimeout(slot: Int): Boolean {
        val view = views[slot] ?: return true
        if (respondsToScript(view)) return true
        // Checked again: the renderer may have died and been replaced while the script was pending.
        if (views[slot] !== view) return false
        Log.e(TAG, "$RENDERER_HUNG; restarting it")
        // Both slots share one renderer. Kill it, or the new WebViews could attach to it again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.webViewRenderProcess?.terminate()
        commitDeferred?.cancel()
        for (s in views.indices) replaceView(s)
        publishViews()
        return false
    }

    private fun createWebView(slot: Int) = WebView(context).apply {
        logDebug("createWebView(): Instantiating slot $slot")
        setBackgroundColor(android.graphics.Color.BLACK)

        // Passes what it doesn't need, and what it only watches, on to the app's own client.
        val client = object : ForwardingWebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                logDebug("${view.slotName()} redirecting to: ${request.url}")
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                super.onPageCommitVisible(view, url)
                if (!url.startsWith("data:text/html")) {
                    logDebug("onPageCommitVisible(): ${view.slotName()} has successfully painted pixels! -> $url")
                    val slot = slotOf(view)
                    if (slot < 0) return
                    val item = slotItems[slot]
                    // Without document start scripts, this is the earliest the script can run.
                    if (item != null && !documentStartScripts) {
                        item.viewport?.let { view.evaluateJavascript(viewportScript(it), null) }
                        val script = item.javaScript
                        if (script != null && originAllowed(url, item.javaScriptOrigins)) {
                            view.evaluateJavascript(script, null)
                        }
                    }
                    painted[slot] = true
                    maybeCommit(slot)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val message = "Web Error on ${view.slotName()}: ${error.description}"
                    Log.e(TAG, message)
                    reportMainFrameError(view, message)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)
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
        webViewClient = client

        webChromeClient = WebChromeClient()

        // gapless.ready(), for pages shown only once they say so (waitForPageReady).
        val bridge = PageBridge(this)
        addJavascriptInterface(bridge, "gapless")

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

        config.configureWebView?.let { configure ->
            try {
                configure(this)
            } catch (e: Exception) {
                Log.e(TAG, "configureWebView failed; using the WebView as the library set it up", e)
            }
            // Paint detection, page scripts, the viewport, readiness and hang recovery all run
            // through these, so the app's function can't take them away. A client of the app's own
            // goes behind the library's, which passes its callbacks on.
            val appClient = webViewClient
            if (appClient !== client) {
                client.target = appClient
                webViewClient = client
            }
            if (!settings.javaScriptEnabled) {
                Log.w(TAG, "configureWebView turned JavaScript off; the library needs it and turned it back on")
                settings.javaScriptEnabled = true
            }
            addJavascriptInterface(bridge, "gapless")
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
        if (slot >= 0) {
            loadedUrls[slot] = null
            setSlotItem(slot, null)
        }
        this.loadData(BLANK_PAGE, "text/html", "UTF-8")
    }
}

/**
 * Lays the page out [viewport] CSS pixels wide, whatever its own viewport tag says: sets
 * every viewport tag to that width, adds one if there is none, and keeps them so if the page
 * changes them. With wide viewport and overview mode on, WebView then zooms the page to the
 * view's width. The view has the viewport's shape, so the height comes out right too.
 */
internal fun viewportScript(viewport: ContentSize): String {
    val content = "width=${viewport.width.toInt()}"
    return """
        (function () {
          if (window !== window.top) return;
          var content = '$content';
          function apply() {
            var tags = document.querySelectorAll('meta[name="viewport"]');
            if (!tags.length) {
              var tag = document.createElement('meta');
              tag.name = 'viewport';
              (document.head || document.documentElement).appendChild(tag);
              tags = [tag];
            }
            for (var i = 0; i < tags.length; i++) if (tags[i].content !== content) tags[i].content = content;
          }
          apply();
          new MutationObserver(apply).observe(document.documentElement,
              { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
        })();
    """.trimIndent()
}
