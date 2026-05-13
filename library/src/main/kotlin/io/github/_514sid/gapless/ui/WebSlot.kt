package io.github._514sid.gapless.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github._514sid.gapless.GaplessAsset

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebSlot(
    asset: GaplessAsset,
    isActive: Boolean,
    onError: (GaplessAsset, String) -> Unit,
) {
    val context = LocalContext.current

    val webView = remember(asset.id) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }
    }

    DisposableEffect(asset.id) {
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onError(asset, error?.description?.toString() ?: "Web error")
                }
            }
        }
        onDispose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }

    LaunchedEffect(asset.id) {
        if (webView.url != asset.uri) {
            webView.loadUrl(asset.uri)
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            webView.onResume()
            webView.resumeTimers()
        } else {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )
}