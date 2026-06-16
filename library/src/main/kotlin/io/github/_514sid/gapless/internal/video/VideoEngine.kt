package io.github._514sid.gapless.internal.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github._514sid.gapless.internal.PlaybackItem

/** Video playback strategy. Implemented by [DualVideoStateMachine] and [VideoStateMachine]. */
internal interface VideoEngine {
    val isNextReady: Boolean

    var onErrorCallback: ((String) -> Unit)?
    var onPreloadErrorCallback: ((assetId: String, message: String) -> Unit)?

    fun prepare(item: PlaybackItem.Video)

    fun play(item: PlaybackItem.Video)

    fun clear()

    fun release()

    @Composable
    fun Content(modifier: Modifier)
}
