package io.github._514sid.gapless

import android.webkit.WebSettings

/**
 * Configuration for WebView instances created by the library.
 *
 * @param enableChromeDebugging Enables remote debugging via Chrome DevTools. Off by default; enable during development.
 * @param userAgent User-agent string sent with every request. Null uses the WebView system default.
 * Defaults to a desktop Chrome UA so sites serve their full layout rather than a mobile version.
 * @param allowThirdPartyCookies Whether to accept third-party cookies. Off by default.
 * @param mixedContentMode How to handle HTTPS pages loading HTTP sub-resources.
 * Defaults to [WebSettings.MIXED_CONTENT_NEVER_ALLOW].
 */
data class GaplessWebConfig(
    val enableChromeDebugging: Boolean = false,
    val userAgent: String? = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
    val allowThirdPartyCookies: Boolean = false,
    val mixedContentMode: Int = WebSettings.MIXED_CONTENT_NEVER_ALLOW,
)
