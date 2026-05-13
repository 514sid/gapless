package io.github._514sid.gapless.ui

import io.github._514sid.gapless.GaplessAsset
import java.util.UUID

internal data class MediaSlotData(
    val asset: GaplessAsset?,
    val isActive: Boolean,
    val playbackId: UUID = UUID.randomUUID(),
)