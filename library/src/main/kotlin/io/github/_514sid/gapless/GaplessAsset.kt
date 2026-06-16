package io.github._514sid.gapless

/**
 * Represents a single piece of media to play.
 *
 * @property id Unique identifier for this asset.
 * @property uri Local file path, `content://` URI, or remote URL to the media.
 * @property mimeType MIME type (e.g., "video/mp4", "image/jpeg"). Determines the rendering engine.
 * @property refreshIntervalMs Reload interval in milliseconds for web assets. Null means no auto-refresh.
 * @property volume Playback volume for video assets, from 0.0 (silent) to 1.0 (full). Defaults to 0.0.
 * Non-video assets ignore this field.
 * @property durationMs Optional video-only clip length, in milliseconds. When set, the video is clipped
 * to this duration so ExoPlayer can begin buffering the next clip well before the transition instead of
 * only in the final [GaplessVideoConfig.maxBufferMs] window of a long clip. The host still drives the
 * transition with [GaplessController.play]; if the clip reaches this end before then, it holds on the
 * last frame (it never auto-advances). For the largest preload benefit, pair this with a
 * [GaplessVideoConfig.maxBufferMs] at least as large as the clip. VOD only; leave null for live streams.
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
    constructor(
        id: Int,
        uri: String,
        mimeType: String,
        width: Int? = null,
        height: Int? = null,
        refreshIntervalMs: Long? = null,
        volume: Float = 0f,
        durationMs: Long? = null,
    ) : this(
        id = id.toString(),
        uri = uri,
        mimeType = mimeType,
        width = width,
        height = height,
        refreshIntervalMs = refreshIntervalMs,
        volume = volume,
        durationMs = durationMs,
    )

    companion object {
        private const val MIME_PREFIX_VIDEO = "video/"
        private const val MIME_PREFIX_IMAGE = "image/"
        private val HLS_DASH_RTSP_MIMES = setOf(
            "application/x-mpegURL",
            "application/vnd.apple.mpegurl",
            "application/dash+xml",
            "application/vnd.ms-sstr+xml",
            "application/x-rtsp"
        )
    }

    /**
     * Determines if the asset is a video stream or file based on its [mimeType].
     */
    val isVideo: Boolean
        get() = mimeType.startsWith(MIME_PREFIX_VIDEO) || mimeType in HLS_DASH_RTSP_MIMES

    /**
     * Determines if the asset is an image based on its [mimeType].
     */
    val isImage: Boolean
        get() = mimeType.startsWith(MIME_PREFIX_IMAGE)

    /**
     * Determines if the asset should be rendered as a web page (fallback if not video or image).
     */
    val isWeb: Boolean
        get() = !isVideo && !isImage
}
