package io.github._514sid.gapless

import java.util.UUID

public sealed class GaplessEvent {
    public abstract val timestamp: Long

    public data class Started(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    public data class Ended(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
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
