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
        val liveIds = setOfNotNull(currentSlot?.id, preloadSlot?.id)

        for (i in 0..1) {
            val slot = slots[i]
            if (slot != null && slot.id !in liveIds) {
                slots[i] = null
            }
        }

        currentSlot?.let { data ->
            val existingIndex = slots.indexOfFirst { it?.id == data.id }
            if (existingIndex != -1) {
                slots[existingIndex] = data
            } else {
                val emptyIndex = slots.indexOfFirst { it == null }
                if (emptyIndex != -1) {
                    slots[emptyIndex] = data
                }
            }
        }

        preloadSlot?.let { data ->
            val existingIndex = slots.indexOfFirst { it?.id == data.id }
            if (existingIndex != -1) {
                slots[existingIndex] = data
            } else {
                val emptyIndex = slots.indexOfFirst { it == null }
                if (emptyIndex != -1) {
                    slots[emptyIndex] = data
                }
            }
        }
    }

    for (i in 0..1) {
        val slotData = slots[i]
        if (slotData != null) {
            MediaSlot(
                slotData = slotData,
                onError = onPlaybackError
            )
        }
    }
}