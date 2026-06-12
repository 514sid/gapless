package io.github._514sid.gapless

import java.util.UUID

sealed class GaplessEvent {
    abstract val timestamp: Long

    data class Started(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    data class Ended(val asset: GaplessAsset, val playbackId: UUID, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    data class PlaybackError(val asset: GaplessAsset, val playbackId: UUID, val message: String, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
    data class Preloading(val asset: GaplessAsset, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /** No assets were passed to the manager. */
    data class Empty(override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /** Every asset in the playlist has been played at least once this pass. */
    data class CycleCompleted(override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()

    /**
     * The asset started playing before its preload completed.
     * The transition fired on schedule to preserve sync, but content may not have been fully ready.
     * Consider increasing [GaplessPlaylistManager.preloadMs] if this fires frequently.
     */
    data class PreloadMissed(val asset: GaplessAsset, val preloadMs: Long, val elapsedMs: Long, override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
}
