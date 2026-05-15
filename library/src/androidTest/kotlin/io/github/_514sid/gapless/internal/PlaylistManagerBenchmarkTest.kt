package io.github._514sid.gapless.internal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessPlayerConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

@RunWith(AndroidJUnit4::class)
class PlaylistManagerBenchmarkTest {

    // X inactive (expired + full scheduling constraints) followed by 1 active at the end.
    // Forces findNextActive() to walk every inactive item before finding the preload candidate.
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
        // preloadThreshold > duration so schedulePreload always calls findNextActive
        val config = GaplessPlayerConfig(
            tickIntervalMs = 1000L,
            preloadThresholdMs = 20_000L,
        )
        manager.update(buildWorstCasePlaylist(inactiveCount), shuffle = false, config = config)

        repeat(10) { manager.tick() } // warm up

        val start = System.nanoTime()
        repeat(tickCount) { manager.tick() }
        val elapsedNs = System.nanoTime() - start

        return elapsedNs / tickCount / 1_000L // return microseconds per tick
    }

    @Test
    fun benchmarkTick100() {
        val avgUs = runTickBenchmark(inactiveCount = 100, tickCount = 1000)
        Log.i("GaplessBenchmark", "100 inactive + 1 active — avg tick: ${avgUs}µs")
    }

    @Test
    fun benchmarkTick500() {
        val avgUs = runTickBenchmark(inactiveCount = 500, tickCount = 1000)
        Log.i("GaplessBenchmark", "500 inactive + 1 active — avg tick: ${avgUs}µs")
    }

    @Test
    fun benchmarkTick1000() {
        val avgUs = runTickBenchmark(inactiveCount = 1000, tickCount = 1000)
        Log.i("GaplessBenchmark", "1000 inactive + 1 active — avg tick: ${avgUs}µs")
    }

    @Test
    fun benchmarkTick5000() {
        val avgUs = runTickBenchmark(inactiveCount = 5000, tickCount = 500)
        Log.i("GaplessBenchmark", "5000 inactive + 1 active — avg tick: ${avgUs}µs")
    }
}
