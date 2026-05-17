package io.github.gapless.sample

import io.github._514sid.gapless.GaplessPlayerConfig

object SampleConfig {
    val assets    = SampleAssets.all
    val shuffle   = false
    val rotation  = 0           // 0 | 90 | 180 | 270
    val config    = GaplessPlayerConfig(
        tickIntervalMs = 100L,
        preloadThresholdMs  = 3_000L,
    )
}
