package io.github._514sid.gapless

import java.util.UUID

sealed class GaplessEvent {
    /** Fired whenever a new asset starts playing. */
    data class Started(val asset: GaplessAsset, val playbackId: UUID) : GaplessEvent()

    /** Fired whenever an asset finishes playing (reaches its duration). */
    data class Finished(val asset: GaplessAsset, val playbackId: UUID) : GaplessEvent()

    /** Fired when an asset is queued for preload (typically ~5 s before the current one ends). */
    data class Preloading(val asset: GaplessAsset) : GaplessEvent()

    /** Fired when a video, image, or web asset fails to render. The asset is automatically
     *  removed from the playlist for the current session. */
    data class PlaybackError(val asset: GaplessAsset, val message: String) : GaplessEvent()
}
