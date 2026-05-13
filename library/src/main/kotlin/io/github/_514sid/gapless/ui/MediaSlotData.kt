package io.github._514sid.gapless.ui

import io.github._514sid.gapless.GaplessAsset

internal data class MediaSlotData(
    val asset: GaplessAsset?,
    val id: Long,
    val isActive: Boolean
)