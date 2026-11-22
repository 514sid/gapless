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
 * Ignored for web assets.
 * @property height Height of the media in pixels, if known. See [width].
 * @property refreshIntervalMs Reload interval in milliseconds for web assets. Null means no auto-refresh.
 * @property volume Playback volume for video assets, from 0.0 (silent) to 1.0 (full). Defaults to 0.0.
 * Non-video assets ignore this field.
 * @property durationMs Optional video-only clip length, in milliseconds. When set, the video is clipped
 * to this duration so it ends at a fixed point instead of playing its full natural length. The host still
 * drives the transition with [GaplessController.play]; if the clip reaches this end before then, it holds
 * on the last frame (it never auto-advances). VOD only; leave null for live streams.
 */
data class GaplessAsset(
    val id: String,
    val uri: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val refreshIntervalMs: Long? = null,
    val volume: Float = 0f,
    val durationMs: Long? = null,
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
    val isVideo: Boolean
        get() = baseMimeType.let { it.startsWith(MIME_PREFIX_VIDEO) || it in STREAMING_MIMES }

    /** True for `image/` types. */
    val isImage: Boolean
        get() = baseMimeType.startsWith(MIME_PREFIX_IMAGE)

    /** True for `text/html` and `application/xhtml+xml`, rendered in a WebView. */
    val isWeb: Boolean
        get() = baseMimeType in WEB_MIMES
}
