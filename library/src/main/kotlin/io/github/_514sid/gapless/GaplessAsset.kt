package io.github._514sid.gapless

/**
 * A single piece of media to play: a [Video], an [Image] or a [Web] page. The type decides how it
 * is rendered. A host that gets a MIME type from its content source can pick the type with
 * [kindOf].
 *
 * Every asset has:
 * - [id]: a unique identifier.
 * - [uri]: a local file path, `content://` URI, or remote URL.
 * - [fit]: how content whose shape differs from the player's is fitted to it.
 * - [backgroundColor]: the colour of the bars around content that doesn't fill the player.
 * - [requestHeaders]: HTTP headers sent with the asset's requests.
 */
public sealed interface GaplessAsset {
    /** Unique identifier for this asset. */
    public val id: String

    /** Local file path, `content://` URI, or remote URL to the media. */
    public val uri: String

    /**
     * How content whose shape differs from the player's is fitted to it: scaled to fit inside, to
     * cover, stretched, or at its own size. Defaults to [GaplessFit.Contain], which shows all of
     * it with bars around it.
     */
    public val fit: GaplessFit

    /**
     * The colour of the bars around content that doesn't fill the player, as ARGB, such as
     * `0xFF202020.toInt()` or `Color.White.toArgb()`. Defaults to opaque black. A transparent
     * colour shows black, the player's own background.
     */
    public val backgroundColor: Int

    /**
     * HTTP headers sent with the asset's requests, such as `Authorization` or a cookie. Each type
     * says which requests carry them. RTSP streams and local files ignore them. Names can't be
     * blank or contain spaces or colons, and values can't contain line breaks.
     */
    public val requestHeaders: Map<String, String>

    /**
     * A video file or stream, played by ExoPlayer.
     *
     * @property mimeType The video's MIME type, such as `video/mp4` or `application/x-mpegURL`.
     * Only needed for a stream whose URL doesn't show what it is: ExoPlayer recognises `.m3u8`,
     * `.mpd` and `.ism` URLs and `rtsp://` on its own, and works out a file's format from its
     * content. When given, it must be a `video/` type or an HLS, DASH, SmoothStreaming or RTSP
     * stream type; matching ignores case and parameters such as `; codecs=...`.
     * @property width Width of the video in pixels, if known. With [height], it sizes the video
     * slot until the decoder reports the real frame size; leaving both null is fine.
     * @property height Height of the video in pixels, if known. See [width].
     * @property volume Playback volume, from 0.0 (silent) to 1.0 (full). Defaults to 0.0.
     * @property durationMs Optional clip length, in milliseconds. When set, the video is clipped to
     * this duration so it ends at a fixed point instead of playing its full natural length. The
     * host still drives the transition with [GaplessController.play]; if the clip reaches this end
     * before then, it holds on the last frame (it never auto-advances). VOD only; leave null for
     * live streams.
     * @property requestHeaders Sent with every request for the video: the file, or a stream's
     * manifest, segments and keys.
     */
    public data class Video(
        override val id: String,
        override val uri: String,
        val mimeType: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val volume: Float = 0f,
        val durationMs: Long? = null,
        override val fit: GaplessFit = GaplessFit.Contain,
        override val backgroundColor: Int = BLACK,
        override val requestHeaders: Map<String, String> = emptyMap(),
    ) : GaplessAsset

    /**
     * A still or animated image, decoded by Coil: JPEG, PNG, WebP, GIF, SVG and anything else the
     * platform decodes. It is decoded at the player's size, or at its own with [GaplessFit.Original].
     *
     * @property animationLoops Animated images only (GIF, and animated WebP on Android 9 and up):
     * how many times the animation plays. Null, the default, loops it until the next
     * [GaplessController.play]. With a count it plays that many times from its first frame, stops
     * on its last, and [GaplessEvent.Completed] is emitted, as for a video that reaches its end.
     * Still images ignore it.
     * @property requestHeaders Sent with the image's download.
     */
    public data class Image(
        override val id: String,
        override val uri: String,
        val animationLoops: Int? = null,
        override val fit: GaplessFit = GaplessFit.Contain,
        override val backgroundColor: Int = BLACK,
        override val requestHeaders: Map<String, String> = emptyMap(),
    ) : GaplessAsset

    /**
     * A web page, shown in a WebView.
     *
     * @property viewportWidth The width in CSS pixels the page is laid out at, whatever the
     * player's size and the screen's density: a page made for 1920x1080 is given 1920 x 1080 and
     * scaled to fit the player as [fit] says. Leave it and [viewportHeight] null to lay the page
     * out at the player's own size, as a browser would; [fit] then has no effect.
     * @property viewportHeight The height in CSS pixels the page is laid out at. See [viewportWidth].
     * @property refreshIntervalMs Reload interval in milliseconds. Null means no auto-refresh.
     * @property javaScript A script run in every document the page loads, before the page's own
     * scripts and before anything is drawn, so styles it injects apply from the first paint. Use
     * it to hide cookie banners or scrollbars, fit a page to the screen, or sign in. It runs again
     * on every navigation and refresh. To act on elements that appear later, use a
     * MutationObserver. On WebView versions too old to run scripts that early (before WebView 87),
     * it runs once the page first paints instead.
     * @property javaScriptOrigins The origins [javaScript] may run in, such as
     * `https://example.com`, so a redirect to a sign-in page elsewhere doesn't get it. `*` allows
     * every origin. Null, the default, allows the page's own origin for http and https pages and
     * every origin otherwise.
     * @property waitForPageReady The page isn't ready to be shown when it first paints but when it
     * calls `gapless.ready()`, for example once a dashboard has loaded its data or a sign-in has
     * finished. The page itself or [javaScript] can make the call. A page that never calls it is
     * shown anyway when [GaplessController.play] times out waiting, as an unpainted page is. Any
     * page in the WebView can call `gapless.ready()`, but it only ever decides when to show the page.
     * @property requestHeaders Sent with the request for the page itself, and again on each
     * refresh, not with the scripts, images or frames it loads; those use the page's cookies.
     */
    public data class Web(
        override val id: String,
        override val uri: String,
        val viewportWidth: Int? = null,
        val viewportHeight: Int? = null,
        val refreshIntervalMs: Long? = null,
        val javaScript: String? = null,
        val javaScriptOrigins: Set<String>? = null,
        val waitForPageReady: Boolean = false,
        override val fit: GaplessFit = GaplessFit.Contain,
        override val backgroundColor: Int = BLACK,
        override val requestHeaders: Map<String, String> = emptyMap(),
    ) : GaplessAsset

    /** Which asset type plays a MIME type. */
    public enum class Kind { Video, Image, Web }

    public companion object {
        private const val BLACK = 0xFF000000.toInt()

        private val STREAMING_MIMES = setOf(
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
            "application/dash+xml",
            "application/vnd.ms-sstr+xml",
            "application/x-rtsp",
        )
        private val WEB_MIMES = setOf("text/html", "application/xhtml+xml")

        /**
         * The asset type that plays [mimeType], or null if none does: [Kind.Video] for `video/`
         * types and HLS, DASH, SmoothStreaming and RTSP streams, [Kind.Image] for `image/` types,
         * and [Kind.Web] for `text/html` and `application/xhtml+xml`. Matching ignores case and
         * parameters such as `; charset=utf-8`.
         */
        public fun kindOf(mimeType: String): Kind? {
            val base = mimeType.substringBefore(';').trim().lowercase()
            return when {
                base.startsWith("video/") || base in STREAMING_MIMES -> Kind.Video
                base.startsWith("image/") -> Kind.Image
                base in WEB_MIMES -> Kind.Web
                else -> null
            }
        }
    }
}
