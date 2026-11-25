package io.github._514sid.gapless.internal.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

/**
 * A [WebViewClient] that passes every callback on to [target], so an app's own client keeps working
 * behind the one the library needs. Subclasses override what they handle and call `super` to pass it
 * on. [onRenderProcessGone] is the exception: the library recovers the slot itself, and an app's
 * client answering it would let Android kill the app.
 *
 * The deprecated overloads aren't forwarded; the framework's newer callbacks call them on [target]
 * when it overrides them.
 */
internal open class ForwardingWebViewClient : WebViewClient() {

    /** The app's client. A plain [WebViewClient] when there is none, which behaves as the default. */
    var target: WebViewClient = WebViewClient()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        target.shouldOverrideUrlLoading(view, request)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) =
        target.onPageStarted(view, url, favicon)

    override fun onPageFinished(view: WebView, url: String?) = target.onPageFinished(view, url)

    override fun onLoadResource(view: WebView, url: String?) = target.onLoadResource(view, url)

    override fun onPageCommitVisible(view: WebView, url: String) = target.onPageCommitVisible(view, url)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        target.shouldInterceptRequest(view, request)

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
        target.onReceivedError(view, request, error)

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) =
        target.onReceivedHttpError(view, request, errorResponse)

    override fun onFormResubmission(view: WebView, dontResend: Message?, resend: Message?) =
        target.onFormResubmission(view, dontResend, resend)

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
        target.doUpdateVisitedHistory(view, url, isReload)

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) =
        target.onReceivedSslError(view, handler, error)

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) =
        target.onReceivedClientCertRequest(view, request)

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) =
        target.onReceivedHttpAuthRequest(view, handler, host, realm)

    override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent?): Boolean =
        target.shouldOverrideKeyEvent(view, event)

    override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent?) = target.onUnhandledKeyEvent(view, event)

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
        target.onScaleChanged(view, oldScale, newScale)

    override fun onReceivedLoginRequest(view: WebView, realm: String?, account: String?, args: String?) =
        target.onReceivedLoginRequest(view, realm, account, args)

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) =
        target.onSafeBrowsingHit(view, request, threatType, callback)

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = false
}
