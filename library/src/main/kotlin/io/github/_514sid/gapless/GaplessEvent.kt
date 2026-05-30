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

    /** Assets exist but none currently satisfies its scheduling constraints. */
    data class Idle(override val timestamp: Long = System.currentTimeMillis()) : GaplessEvent()
}
