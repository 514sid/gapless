package io.github._514sid.gapless.ui

import androidx.compose.runtime.*
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.internal.PlaybackState

@Composable
internal fun MediaSlotManager(
    currentAsset: PlaybackState?,
    preloadAsset: GaplessAsset?,
    onPlaybackError: (GaplessAsset, String) -> Unit
) {
    val slots = remember { mutableStateListOf<PlaybackState?>(null, null) }

    LaunchedEffect(currentAsset, preloadAsset) {
        val liveIds = setOfNotNull(currentAsset?.asset?.id, preloadAsset?.id)

        for (i in 0..1) {
            if (slots[i]?.asset?.id !in liveIds) slots[i] = null
        }

        currentAsset?.let { state ->
            val existing = slots.indexOfFirst { it?.asset?.id == state.asset.id }
            if (existing != -1) slots[existing] = state
            else {
                val empty = slots.indexOfFirst { it == null }
                if (empty != -1) slots[empty] = state
            }
        }

        preloadAsset?.let { asset ->
            val existing = slots.indexOfFirst { it?.asset?.id == asset.id }
            if (existing == -1) {
                val empty = slots.indexOfFirst { it == null }
                if (empty != -1) slots[empty] = PlaybackState(asset, -1L)
            }
        }
    }

    for (i in 0..1) {
        val slotState = slots[i]
        MediaSlot(
            asset = slotState?.asset,
            generation = slotState?.generation ?: 0L,
            isActive = slotState?.asset?.id == currentAsset?.asset?.id,
            onError = onPlaybackError
        )
    }
}