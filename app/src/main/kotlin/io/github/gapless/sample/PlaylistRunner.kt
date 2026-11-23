package io.github.gapless.sample

import android.util.Log
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessController
import io.github._514sid.gapless.GaplessEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Loops [assets] on its own [controller]: each asset is staged as the previous one starts, and
 * cut to when its slot runs out. Failed assets are skipped after a short pause.
 *
 * @param name Prefixed to every log line, so the players in split-screen mode can be told apart.
 */
class PlaylistRunner(
    private val scope: CoroutineScope,
    private val name: String,
    private val assets: List<GaplessAsset>,
) {
    val controller = GaplessController(scope = scope, preloadMs = 3_000)

    private var index = 0
    private var timerJob: Job? = null

    // The asset handed to start() or play() that hasn't reported Started yet.
    private var awaiting: GaplessAsset? = null

    fun start() {
        if (assets.isEmpty()) return
        val first = nextAsset()
        awaiting = first
        controller.start(first)

        // Collected for the activity's whole life, not just while started: the schedule is driven
        // by these events, and missing a Started while stopped would end it.
        scope.launch { controller.events.collect(::onEvent) }
    }

    private fun onEvent(event: GaplessEvent) {
        when (event) {
            is GaplessEvent.Started -> {
                log(
                    "Started: ${event.asset.id} [${event.playbackId}], " +
                        "began ${System.currentTimeMillis() - event.timestamp}ms before this event",
                )
                awaiting = null
                timerJob?.cancel()
                controller.prepareNext(assets[index % assets.size])
                // Count from when it really started, not from when the event arrived.
                val elapsed = System.currentTimeMillis() - event.timestamp
                val duration = event.asset.durationMs ?: return
                timerJob = scope.launch {
                    delay(duration - elapsed)
                    playNext()
                }
            }
            is GaplessEvent.Ended -> log("Ended: ${event.asset.id} [${event.playbackId}]")
            // The schedule cuts on the slot's duration; this only shows a clip ended before that.
            is GaplessEvent.Completed -> log("Completed: ${event.asset.id} [${event.playbackId}]")
            is GaplessEvent.PlaybackError -> {
                // The player already retried; this asset is done. Show the next one.
                Log.e(TAG, "${prefix}Error on ${event.asset.id}: ${event.message}")
                skipAhead()
            }
            is GaplessEvent.PreloadError -> {
                Log.e(TAG, "${prefix}PreloadError on ${event.asset.id}: ${event.message}")
                // If the cut we asked for failed, nothing else will move the schedule on.
                // A failed staged asset needs nothing: play() loads it again on its turn.
                if (event.asset.id == awaiting?.id) skipAhead()
            }
            is GaplessEvent.Preloading -> log("Preloading: ${event.asset.id}")
            is GaplessEvent.PreloadMissed ->
                Log.w(TAG, "${prefix}PreloadMissed: ${event.asset.id} took ${event.elapsedMs}ms")
        }
    }

    private fun nextAsset(): GaplessAsset = assets[index++ % assets.size]

    private fun playNext() {
        val next = nextAsset()
        awaiting = next
        controller.play(next)
    }

    // Moves past a failed asset. The pause keeps a playlist where everything fails from spinning.
    private fun skipAhead() {
        timerJob?.cancel()
        timerJob = scope.launch {
            delay(SKIP_DELAY_MS)
            playNext()
        }
    }

    private val prefix = if (name.isEmpty()) "" else "[$name] "

    private fun log(message: String) = Log.d(TAG, prefix + message)

    private companion object {
        const val TAG = "GaplessPlayer"
        const val SKIP_DELAY_MS = 1_000L
    }
}
