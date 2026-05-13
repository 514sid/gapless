package io.github._514sid.gapless.ui

import androidx.compose.runtime.*
import io.github._514sid.gapless.GaplessAsset

@Composable
internal fun MediaSlotManager(
    currentSlot: MediaSlotData?,
    preloadSlot: MediaSlotData?,
    onPlaybackError: (GaplessAsset, String) -> Unit
) {
    val slots = remember { mutableStateListOf<MediaSlotData?>(null, null) }

    LaunchedEffect(currentSlot, preloadSlot) {
        val liveIds = setOfNotNull(currentSlot?.playbackId, preloadSlot?.playbackId)

        // Mark stale slots inactive but keep them alive
        for (i in 0..1) {
            val slot = slots[i]
            if (slot != null && slot.playbackId !in liveIds && slot.isActive) {
                slots[i] = slot.copy(isActive = false)
            }
        }

        // Update in-place for already-assigned slot IDs
        for (i in 0..1) {
            slots[i] = when (slots[i]?.playbackId) {
                currentSlot?.playbackId -> currentSlot
                preloadSlot?.playbackId -> preloadSlot
                else -> slots[i]
            }
        }

        // Assign any unplaced live slots into stale/empty positions
        listOfNotNull(currentSlot, preloadSlot).forEach { data ->
            if (slots.none { it?.playbackId == data.playbackId }) {
                val targetIdx = slots.indexOfFirst { it == null || it.playbackId !in liveIds }
                if (targetIdx != -1) slots[targetIdx] = data
            }
        }
    }

    for (i in 0..1) {
        MediaSlot(
            slotData = slots[i],
            onError = onPlaybackError
        )
    }
}
