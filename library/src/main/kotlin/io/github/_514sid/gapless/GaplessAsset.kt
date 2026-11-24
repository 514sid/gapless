package io.github._514sid.gapless

/**
 * Represents a single piece of media to play.
 *
 * @property id Unique identifier for this asset.
 * @property uri Local file path, `content://` URI, or remote URL to the media.
 * @property mimeType MIME type (e.g., "video/mp4", "image/jpeg", "text/html"). Determines the
 * rendering engine; see [isVideo], [isImage] and [isWeb]. Matching ignores case and parameters
 * such as `; charset=utf-8`. Any other type is rejected by [GaplessController].
 * @property width Width of the media in pixels, if known. For video, [width] and [height] together
 * size the video slot until the decoder reports the real frame size; leaving them null is fine.
 * For a web page, [width] and [height] are the size in CSS pixels it is laid out at, whatever the
 * player's size and the screen's density: a page made for 1920x1080 is given 1920 x 1080 and
 * scaled to fit the player as [fit] says. Leave them null to lay the page out at the player's own
 * size, as a browser would.
 * @property height Height of the media in pixels, if known. See [width].
 * @property refreshIntervalMs Reload interval in milliseconds for web assets. Null means no auto-refresh.
 * @property volume Playback volume for video assets, from 0.0 (silent) to 1.0 (full). Defaults to 0.0.
 * Non-video assets ignore this field.
 * @property durationMs Optional video-only clip length, in milliseconds. When set, the video is clipped
 * to this duration so it ends at a fixed point instead of playing its full natural length. The host still
 * drives the transition with [GaplessController.play]; if the clip reaches this end before then, it holds
 * on the last frame (it never auto-advances). VOD only; leave null for live streams.
 * @property javaScript Web assets only: a script run in every document the page loads, before the
 * page's own scripts and before anything is drawn, so styles it injects apply from the first paint.
 * Use it to hide cookie banners or scrollbars, fit a page to the screen, or sign in. It runs again
 * on every navigation and refresh. To act on elements that appear later, use a MutationObserver.
 * On WebView versions too old to run scripts that early (before WebView 87), it runs once the page
 * first paints instead.
 * @property javaScriptOrigins The origins [javaScript] may run in, such as `https://example.com`,
 * so a redirect to a sign-in page elsewhere doesn't get it. `*` allows every origin. Null, the
 * default, allows the page's own origin for http and https pages and every origin otherwise.
 * @property waitForPageReady Web assets only: the page isn't ready to be shown when it first paints
 * but when it calls `gapless.ready()`, for example once a dashboard has loaded its data or a sign-in
 * has finished. The page itself or [javaScript] can make the call. A page that never calls it is
 * shown anyway when [GaplessController.play] times out waiting, as an unpainted page is. Any page
 * in the WebView can call `gapless.ready()`, but it only ever decides when to show the page.
 * @property fit How content whose shape differs from the player's is fitted to it: scaled to fit
 * inside, to cover, stretched, or at its own size. Defaults to [GaplessFit.Contain], which shows
 * all of it with bars around it. Web pages follow it only when they have a [width] and [height];
 * otherwise they lay themselves out at the player's size.
 * @property backgroundColor The colour of the bars around content that
 * doesn't fill the player, as ARGB, such as `0xFF202020.toInt()` or `Color.White.toArgb()`.
 * Defaults to opaque black. A transparent colour shows black, the player's own background.
 * @property animationLoops Animated images only (GIF, and animated WebP on Android 9 and up): how
 * many times the animation plays. Null, the default, loops it until the next
 * [GaplessController.play]. With a count it plays that many times from its first frame, stops on
 * its last, and [GaplessEvent.Completed] is emitted, as for a video that reaches its end. Ignored
 * for still images, videos and web pages.
 */
public data class GaplessAsset(
    val id: String,
    val uri: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val refreshIntervalMs: Long? = null,
    val volume: Float = 0f,
    val durationMs: Long? = null,
    val javaScript: String? = null,
    val javaScriptOrigins: Set<String>? = null,
    val waitForPageReady: Boolean = false,
    val fit: GaplessFit = GaplessFit.Contain,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val animationLoops: Int? = null,
) {
    private companion object {
        const val MIME_PREFIX_VIDEO = "video/"
        const val MIME_PREFIX_IMAGE = "image/"
        val STREAMING_MIMES = setOf(
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "application/dash+xml",
            "application/vnd.ms-sstr+xml",
            "application/x-rtsp",
        )
        val WEB_MIMES = setOf(
            "text/html",
            "application/xhtml+xml",
        )
    }

    /** [mimeType] lowercased, without parameters. */
    private val baseMimeType: String
        get() = mimeType.substringBefore(';').trim().lowercase()

    /** True for `video/` types and HLS, DASH, SmoothStreaming and RTSP streams. */
    public val isVideo: Boolean
        get() = baseMimeType.let { it.startsWith(MIME_PREFIX_VIDEO) || it in STREAMING_MIMES }

    /** True for `image/` types. */
    public val isImage: Boolean
        get() = baseMimeType.startsWith(MIME_PREFIX_IMAGE)

    /** True for `text/html` and `application/xhtml+xml`, rendered in a WebView. */
    public val isWeb: Boolean
        get() = baseMimeType in WEB_MIMES
}
