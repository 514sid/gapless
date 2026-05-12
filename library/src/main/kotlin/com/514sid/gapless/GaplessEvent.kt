package com.514sid.gapless

sealed class GaplessEvent {
    /** Fired whenever a new asset starts playing (deduplicated — won't fire again for the same asset). */
    data class NowPlaying(val asset: GaplessAsset) : GaplessEvent()

    /** Fired when an asset is queued for preload (typically ~5 s before the current one ends). */
    data class Preloading(val asset: GaplessAsset) : GaplessEvent()

    /** Fired when a video, image, or web asset fails to render. The asset is automatically
     *  removed from the playlist for the current session. */
    data class PlaybackError(val asset: GaplessAsset, val message: String) : GaplessEvent()

    /** Fired when the active playlist is empty (no assets, or all are outside their schedule/disabled). */
    data object PlaylistEmpty : GaplessEvent()
}
