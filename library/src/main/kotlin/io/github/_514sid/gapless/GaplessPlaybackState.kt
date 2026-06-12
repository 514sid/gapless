package io.github._514sid.gapless

import java.util.UUID

/**
 * Snapshot of what is currently playing.
 *
 * @param asset     The asset that started playing.
 * @param playbackId Unique id for this specific playback instance, matching [GaplessEvent.Started.playbackId].
 * @param startedAt Epoch milliseconds when playback of this asset began.
 */
data class GaplessPlaybackState(
    val asset: GaplessAsset,
    val playbackId: UUID,
    val startedAt: Long,
)
