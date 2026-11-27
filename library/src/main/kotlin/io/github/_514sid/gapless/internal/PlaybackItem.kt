package io.github._514sid.gapless.internal

import java.util.UUID

internal sealed class PlaybackItem {
    val playbackId: UUID = UUID.randomUUID()
    var preparedAt: Long = 0L
    var startedAt: Long = 0L
    var assetId: String = ""

    /**
     * @param sizeHint The asset's declared size, used to lay out the slot before the decoder
     * reports the real video size. Null when the asset declared no size.
     */
    data class Video(
        val uri: String,
        /** Null lets ExoPlayer work the format out from the URI and the content. */
        val mimeType: String? = null,
        val sizeHint: ContentSize? = null,
        val volume: Float = 0f,
        val durationMs: Long? = null,
        val framing: Framing = Framing(),
        /** Sent with every request for the clip: the file, or a stream's manifest and segments. */
        val requestHeaders: Map<String, String> = emptyMap(),
    ) : PlaybackItem() {
        /** Set at play time when the host gave a start time; null plays unanchored. */
        @Volatile var timeline: VideoTimeline? = null
    }

    data class Image(
        val model: Any,
        val framing: Framing = Framing(),
        /** How many times an animated image plays; null loops it until it is cut away from. */
        val animationLoops: Int? = null,
        val requestHeaders: Map<String, String> = emptyMap(),
    ) : PlaybackItem()

    /**
     * @param javaScript Run at the start of every document, in [javaScriptOrigins].
     * @param waitForReady Not ready to show until the page calls `gapless.ready()`.
     * @param viewport The page's own size: it is laid out at this many CSS pixels and fitted to the
     * player as [framing] says. Null lays it out at the player's size.
     */
    data class Web(
        val url: String,
        val refreshIntervalMs: Long = 0L,
        val javaScript: String? = null,
        val javaScriptOrigins: Set<String> = emptySet(),
        val waitForReady: Boolean = false,
        /** The size in CSS pixels to lay the page out at, scaled to the player; null fills it. */
        val viewport: ContentSize? = null,
        val framing: Framing = Framing(),
        /** Sent with the request for the page itself, not the resources it loads. */
        val requestHeaders: Map<String, String> = emptyMap(),
    ) : PlaybackItem()
}
