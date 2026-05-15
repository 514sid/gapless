package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessPlayerConfig
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import kotlin.time.measureTime

class PlaylistManagerBenchmarkTest {
    // Builds a playlist of `inactiveCount` inactive assets (with full scheduling constraints)
    // followed by a single active asset at the end. Forces findNextActive() to scan every
    // inactive item before reaching the one it can preload.
    private fun buildWorstCasePlaylist(inactiveCount: Int): List<GaplessAsset> {
        val expiredMs = System.currentTimeMillis() - 86_400_000L

        val inactive = List(inactiveCount) { i ->
            GaplessAsset(
                id = "inactive_$i",
                uri = "test://uri",
                mimeType = "video/mp4",
                durationMs = 10_000L,
                endDate = expiredMs,
                playDays = setOf(DayOfWeek.entries[i % 7]),
                playTimeFrom = "%02d:00".format(i % 24),
                playTimeTo = "%02d:00".format((i + 4) % 24),
            )
        }

        val active = GaplessAsset(
            id = "active_last",
            uri = "test://uri",
            mimeType = "video/mp4",
            durationMs = 10_000L,
        )

        return inactive + active
    }

    private fun runTickBenchmark(inactiveCount: Int, tickCount: Int): Long {
        val manager = PlaylistManager()
        val config = GaplessPlayerConfig(
            tickIntervalMs = 1000L,
            preloadThresholdMs = 20_000L,
        )
        manager.update(buildWorstCasePlaylist(inactiveCount), shuffle = false, config = config)

        repeat(10) { manager.tick() } // warm up

        val elapsed = measureTime {
            repeat(tickCount) { manager.tick() }
        }

        return elapsed.inWholeMicroseconds / tickCount
    }

    @Test
    fun `benchmark tick with 100 inactive assets before active`() {
        val avgUs = runTickBenchmark(inactiveCount = 100, tickCount = 1000)
        println("100 inactive + 1 active — avg tick: ${avgUs}µs")
    }

    @Test
    fun `benchmark tick with 500 inactive assets before active`() {
        val avgUs = runTickBenchmark(inactiveCount = 500, tickCount = 1000)
        println("500 inactive + 1 active — avg tick: ${avgUs}µs")
    }

    @Test
    fun `benchmark tick with 1000 inactive assets before active`() {
        val avgUs = runTickBenchmark(inactiveCount = 1000, tickCount = 1000)
        println("1000 inactive + 1 active — avg tick: ${avgUs}µs")
    }

    @Test
    fun `benchmark tick with 5000 inactive assets before active`() {
        val avgUs = runTickBenchmark(inactiveCount = 5000, tickCount = 500)
        println("5000 inactive + 1 active — avg tick: ${avgUs}µs")
    }
}
