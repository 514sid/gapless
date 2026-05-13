package io.github._514sid.gapless

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github._514sid.gapless.internal.GaplessViewModel
import io.github._514sid.gapless.ui.MediaSlotManager
import io.github._514sid.gapless.ui.RotatedScreenContainer

/**
 * A self-contained gapless media player composable.
 *
 * Maintains two render slots internally so the next asset is always preloaded and buffered
 * before the current one finishes, producing seamless black-frame-free transitions.
 *
 * @param assets     Ordered list of assets to play. Passing a new list hot-swaps the playlist
 * while preserving the currently-playing asset when possible.
 * @param rotation   Screen rotation in degrees — `0`, `90`, `180`, or `270`. Content is rotated
 * inside its bounds; the composable's own layout size is unaffected.
 * @param shuffle    When `true` the playlist is randomized each pass, ensuring the last-played
 * asset does not appear first in the reshuffled order.
 * @param config     Timing configuration — tick interval and preload threshold. See [GaplessPlayerConfig].
 * @param onEvent    Invoked on the main thread for each [GaplessEvent]:
 * [GaplessEvent.NowPlaying], [GaplessEvent.Preloading], [GaplessEvent.PlaybackError].
 * @param emptyState Composable to show when the [assets] list is completely empty.
 * @param idleState  Composable to show when [assets] is not empty, but NO assets currently
 * meet their temporal scheduling criteria (i.e., waiting for a time window).
 * Defaults to calling [emptyState] if not explicitly provided.
 */
@Composable
fun GaplessPlayer(
    assets: List<GaplessAsset>,
    rotation: Int = 0,
    shuffle: Boolean = false,
    config: GaplessPlayerConfig = GaplessPlayerConfig(),
    onEvent: (GaplessEvent) -> Unit = {},
    emptyState: @Composable () -> Unit = { Box(modifier = Modifier.fillMaxSize().background(Color.Black)) },
    idleState: @Composable () -> Unit = emptyState
) {
    val viewModel: GaplessViewModel = viewModel()

    LaunchedEffect(assets, shuffle, config) {
        viewModel.update(assets, shuffle, config)
    }

    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { currentOnEvent(it) }
    }

    val currentSlot by viewModel.currentSlot.collectAsState()
    val preloadSlot by viewModel.preloadSlot.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()

    if (!isInitialized) return

    RotatedScreenContainer(rotation) {
        when {
            assets.isEmpty() -> emptyState()
            currentSlot == null && preloadSlot == null -> idleState()
            else -> {
                MediaSlotManager(
                    currentSlot = currentSlot,
                    preloadSlot = preloadSlot,
                    onPlaybackError = { asset, msg -> viewModel.handlePlaybackError(asset, msg) }
                )
            }
        }
    }
}