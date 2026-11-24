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
        val mimeType: String,
        val sizeHint: ContentSize? = null,
        val volume: Float = 0f,
        val durationMs: Long? = null,
        val framing: Framing = Framing(),
    ) : PlaybackItem() {
        /** Set at play time when the host gave a start time; null plays unanchored. */
        @Volatile var timeline: VideoTimeline? = null
    }

    data class Image(
        val model: Any,
        val width: Int,
        val height: Int,
        val framing: Framing = Framing(),
        /** How many times an animated image plays; null loops it until it is cut away from. */
        val animationLoops: Int? = null,
    ) : PlaybackItem()

    /**
     * @param javaScript Run at the start of every document, in [javaScriptOrigins].
     * @param waitForReady Not ready to show until the page calls `gapless.ready()`.
     */
    data class Web(
        val url: String,
        val refreshIntervalMs: Long = 0L,
        val javaScript: String? = null,
        val javaScriptOrigins: Set<String> = emptySet(),
        val waitForReady: Boolean = false,
    ) : PlaybackItem()
}
