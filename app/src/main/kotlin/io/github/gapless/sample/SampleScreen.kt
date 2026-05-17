package io.github.gapless.sample

import android.util.Log
import androidx.compose.runtime.Composable
import io.github._514sid.gapless.GaplessEvent
import io.github._514sid.gapless.GaplessPlayer

private const val TAG = "GaplessLog"

@Composable
fun SampleScreen() {
    GaplessPlayer(
        assets   = SampleConfig.assets,
        rotation = SampleConfig.rotation,
        shuffle  = SampleConfig.shuffle,
        config   = SampleConfig.config,
        onEvent  = { event ->
            when (event) {
                is GaplessEvent.Started       -> Log.i(TAG, "Started      id=${event.asset.id}  playbackId=${event.playbackId}")
                is GaplessEvent.Finished      -> Log.i(TAG, "Finished     id=${event.asset.id}  playbackId=${event.playbackId}")
                is GaplessEvent.Preloading    -> Log.d(TAG, "Preloading   id=${event.asset?.id ?: "null"}")
                is GaplessEvent.PlaybackError -> Log.e(TAG, "Error        id=${event.asset.id}  msg=${event.message}")
                GaplessEvent.Idle             -> Log.w(TAG, "Idle")
            }
        },
    )
}
