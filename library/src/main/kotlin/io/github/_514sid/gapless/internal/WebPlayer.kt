package io.github._514sid.gapless.internal

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

internal data class WebPlayerState(
    val webViewA: WebView,
    val webViewB: WebView?,
    val activeSlot: Int = 0
)

/**
 * Renders web content using two A/B WebView slots for seamless transitions.
 *
 * WebViewB is nullable and created lazily by the caller - it should be
 * destroyed and set to null when a web-to-web transition is no longer needed.
 *
 * The [key] on slot B ensures the AndroidView factory is re-invoked whenever
 * the WebView instance is replaced.
 */
@Composable
internal fun WebPlayer(
    state: WebPlayerState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (state.activeSlot == 0) {
                AndroidView(factory = { state.webViewA }, modifier = Modifier.fillMaxSize())
            }
        }

        key(state.webViewB) {
            state.webViewB?.let { webViewB ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    if (state.activeSlot == 1) {
                        AndroidView(factory = { webViewB }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
