package io.github._514sid.gapless

import java.util.UUID

public sealed class GaplessEvent {
    public abstract val timestamp: Long

    public data class Started(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    public data class Ended(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    public data class PlaybackError(val asset: GaplessAsset, val playbackId: UUID, val message: String, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    public data class Preloading(val asset: GaplessAsset, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /**
     * The asset started playing before its preload completed.
     * The transition fired on schedule to preserve sync, but content may not have been fully ready.
     * Consider increasing [GaplessController.preloadMs] if this fires frequently.
     */
    public data class PreloadMissed(val asset: GaplessAsset, val preloadMs: Long, val elapsedMs: Long, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /**
     * The asset failed to preload before it was scheduled to play.
     * The asset has never been presented; the host should decide whether to skip it or retry.
     */
    public data class PreloadError(val asset: GaplessAsset, val message: String, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
}
