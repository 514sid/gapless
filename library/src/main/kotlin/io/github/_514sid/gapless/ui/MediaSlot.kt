package io.github._514sid.gapless.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import io.github._514sid.gapless.GaplessAsset

@Composable
internal fun MediaSlot(
    slotData: MediaSlotData,
    onError: (GaplessAsset, String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (slotData.isActive) 1f else 0f
            }
            .zIndex(if (slotData.isActive) 1f else 0f),
        contentAlignment = Alignment.Center,
    ) {
        slotData.asset?.let {
            when {
                it.isVideo -> VideoSlot(it, slotData.isActive, onError)
                it.isWeb -> WebSlot(it, slotData.isActive, onError)
                it.isImage -> ImageSlot(it, onError)
            }
        }
    }
}