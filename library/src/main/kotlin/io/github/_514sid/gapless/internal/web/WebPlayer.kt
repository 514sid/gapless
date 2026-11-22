package io.github._514sid.gapless.internal.web

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

/**
 * The two web slots. A slot is null until its WebView is first needed, and stays null if WebView
 * is unavailable on the device; a slot whose renderer crashed gets a fresh WebView.
 */
internal data class WebPlayerState(
    val webViewA: WebView? = null,
    val webViewB: WebView? = null,
    val activeSlot: Int = 0
)

@Composable
internal fun WebPlayer(
    state: WebPlayerState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        WebSlot(view = state.webViewA, isActive = state.activeSlot == 0)
        WebSlot(view = state.webViewB, isActive = state.activeSlot == 1)
    }
}

@Composable
private fun WebSlot(view: WebView?, isActive: Boolean) {
    if (view == null) return
    // Keyed on the instance: AndroidView only runs its factory once, so a replacement WebView
    // needs a new node to be attached at all.
    key(view) {
        AndroidView(
            factory = {
                view.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (isActive) 1f else 0f)
        )
    }
}
