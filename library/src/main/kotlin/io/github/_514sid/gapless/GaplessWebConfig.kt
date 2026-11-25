package io.github._514sid.gapless

import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Configuration for WebView instances created by the library.
 *
 * @param enableChromeDebugging Enables remote debugging via Chrome DevTools. Off by default; enable during development.
 * @param userAgent User-agent string sent with every request. Null uses the WebView system default.
 * Defaults to a desktop Chrome UA so sites serve their full layout rather than a mobile version.
 * @param allowThirdPartyCookies Whether to accept third-party cookies. Off by default.
 * @param mixedContentMode How to handle HTTPS pages loading HTTP sub-resources.
 * Defaults to [WebSettings.MIXED_CONTENT_NEVER_ALLOW].
 * @param configureWebView Called with each WebView once the library has set it up and before it
 * loads anything, including the fresh WebView that replaces one whose renderer crashed. Use it for
 * settings the library doesn't cover: file access, cookies, a [android.webkit.WebChromeClient] for
 * console messages or permission prompts, or a JavaScript interface of the app's own. A
 * [android.webkit.WebViewClient] set here works too: the library keeps its own client on the
 * WebView and passes every callback on to the app's, except `onRenderProcessGone`, since the
 * library replaces a crashed WebView itself. The library also needs JavaScript and the `gapless`
 * JavaScript interface, so it turns those back on if the function removes them, with a warning in
 * the log. If the function throws, the error is logged and the WebView is used anyway.
 *
 * The player is rebuilt whenever its config changes, and a function is only equal to itself. Create
 * the config once, for example with `remember`, rather than on every recomposition.
 */
public data class GaplessWebConfig(
    val enableChromeDebugging: Boolean = false,
    val userAgent: String? = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
    val allowThirdPartyCookies: Boolean = false,
    val mixedContentMode: Int = WebSettings.MIXED_CONTENT_NEVER_ALLOW,
    val configureWebView: ((WebView) -> Unit)? = null,
)
