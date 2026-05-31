package io.github._514sid.gapless.internal

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex

internal data class WebPlayerState(
    val webViewA: WebView,
    val webViewB: WebView,
    val activeSlot: Int = 0
)

@Composable
internal fun WebPlayer(
    state: WebPlayerState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = {
                state.webViewA.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (state.activeSlot == 0) 1f else 0f)
        )

        AndroidView(
            factory = {
                state.webViewB.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (state.activeSlot == 1) 1f else 0f)
        )
    }
}