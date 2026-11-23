package io.github._514sid.gapless

import java.util.UUID

public sealed class GaplessEvent {
    public abstract val timestamp: Long

    /**
     * The asset is on screen. For a video this is when its frames start moving, which with audio on
     * can be a few hundred milliseconds after the cut, while the audio output starts; [timestamp]
     * is the wall-clock time the clip's position was zero. For an image it is when the image has
     * decoded, and for a web page when the painted page is swapped in. If the player can't confirm
     * it within [GaplessController.readyTimeoutMs] of the cut, this is emitted anyway.
     *
     * Time a slot from [timestamp], not from when the event arrives or when [GaplessController.play]
     * was called, so a slow start doesn't cut the end off the clip.
     */
    public data class Started(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /**
     * The asset was replaced on screen. Emitted just before the [Started] of what replaced it. An
     * asset cut away from before it was ever shown gets neither event.
     */
    public data class Ended(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /**
     * A video played to its end: a clip reached its last frame, or a stream finished (video on
     * demand, or a live stream the server marked as ended). The last frame stays on screen until
     * the next [GaplessController.play], and [Ended] still follows when something replaces it.
     *
     * Use it to move on as soon as a clip or stream is done, when its length is unknown or shorter
     * than its slot, instead of holding the last frame. A live stream that just stops arriving
     * doesn't end: it stalls, is retried, and ends in [PlaybackError]. Not emitted for images and
     * web pages, which have no end, or for a clip cut away from before its end.
     */
    public data class Completed(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    /**
     * The asset on screen failed and the player gave up on it. Transient video failures (network,
     * decoder, audio output, stalls) are retried inside the player first, so this means retries ran
     * out or the media can't play on this device. A failed video stays frozen on its last frame.
     * The controller no longer has a current asset: call [GaplessController.play] with the next one.
     */
    public data class PlaybackError(val asset: GaplessAsset, val playbackId: UUID, val message: String, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    public data class Preloading(val asset: GaplessAsset, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /**
     * The asset started playing before its preload completed.
     * The transition fired on schedule to preserve sync, but content may not have been fully ready.
     * Consider increasing [GaplessController.preloadMs] if this fires frequently.
     */
    public data class PreloadMissed(val asset: GaplessAsset, val preloadMs: Long, val elapsedMs: Long, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /**
     * The asset failed to load and was never shown. A later [GaplessController.play] of it loads it
     * again. If a [GaplessController.play] was already waiting on this asset, this event ends it:
     * the previous asset stays on screen and no [Started] follows, so move on to another asset.
     */
    public data class PreloadError(val asset: GaplessAsset, val message: String, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
}
