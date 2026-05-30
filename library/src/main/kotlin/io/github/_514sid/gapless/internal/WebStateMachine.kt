package io.github._514sid.gapless.internal

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
internal class WebStateMachine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // WebViewA is always present. WebViewB is created lazily for the second
    // slot and destroyed as soon as a web-to-web transition is no longer needed.
    val webViewA: WebView = createWebView()

    var renderState by mutableStateOf(
        WebPlayerState(webViewA = webViewA, webViewB = null, activeSlot = 0)
    )
        private set

    private var pendingItem: PlaybackItem.Web? = null

    /**
     * Start loading [item]'s URL into the inactive slot.
     * Cancels any previous pending prepare.
     */
    fun prepare(item: PlaybackItem.Web) {
        pendingItem = item
        val targetView = getOrCreateInactiveView()
        scope.launch { targetView.loadUrl(item.url) }
    }

    /**
     * Make [item] visible.
     *
     * If [item] matches the pending prepare the already-loaded slot is flipped.
     * Otherwise, the item is loaded inline and shown immediately.
     */
    fun play(item: PlaybackItem.Web) {
        val targetSlot = if (pendingItem?.playbackId == item.playbackId) {
            nextSlot()
        } else {
            val slot = nextSlot()
            val targetView = if (slot == 1) getOrCreateWebViewB() else webViewA
            scope.launch { targetView.loadUrl(item.url) }
            slot
        }

        renderState = renderState.copy(activeSlot = targetSlot)
        pendingItem = null

        // Prefer B: when B is active, A has no role → blank it.
        // When A is active (unavoidable during alternation), B is unused → kill it.
        // Either way only one WebView holds content until the next web-to-web prepare.
        if (targetSlot == 1) {
            webViewA.loadUrl("about:blank")
        } else {
            renderState.webViewB?.destroy()
            renderState = renderState.copy(webViewB = null)
        }
    }

    /** Cancel the pending URL load and destroy the inactive WebView without changing the active slot. */
    fun cancelPrepare() {
        pendingItem = null
        val inactiveSlot = nextSlot()
        if (inactiveSlot == 1) {
            renderState.webViewB?.let {
                it.destroy()
                renderState = renderState.copy(webViewB = null)
            }
        } else {
            webViewA.loadUrl("about:blank")
        }
    }

    /**
     * Hide web content and destroy WebViewB to free memory.
     * Stops web content and destroys WebViewB to free memory when a non-web asset takes over.
     */
    fun clear() {
        pendingItem = null
        renderState.webViewB?.destroy()
        renderState = renderState.copy(webViewB = null, activeSlot = 0)
    }

    fun release() {
        clear()
        webViewA.destroy()
    }

    private fun nextSlot() = if (renderState.activeSlot == 0) 1 else 0

    private fun getOrCreateInactiveView(): WebView {
        return if (nextSlot() == 1) getOrCreateWebViewB() else webViewA
    }

    private fun getOrCreateWebViewB(): WebView =
        renderState.webViewB ?: createWebView().also {
            renderState = renderState.copy(webViewB = it)
        }

    private fun createWebView() = WebView(context).apply {
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) view.loadUrl("about:blank")
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) view.loadUrl("about:blank")
            }
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        loadUrl("about:blank")
    }
}
