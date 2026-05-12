package com._514sid.gapless

/**
 * Runtime configuration for [GaplessPlayer].
 *
 * @param tickIntervalMs      How often the playback engine checks elapsed time and schedule
 *                            validity, in milliseconds. Lower values are more precise but burn
 *                            more CPU. Default: 1 000 ms.
 * @param preloadThresholdMs  How many milliseconds before the current asset ends the next one
 *                            starts buffering. Increase if you see gaps on slow storage/network.
 *                            Default: 5 000 ms.
 */
data class GaplessPlayerConfig(
    val tickIntervalMs: Long = 1_000L,
    val preloadThresholdMs: Long = 5_000L,
)
