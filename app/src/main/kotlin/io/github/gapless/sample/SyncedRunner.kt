package io.github.gapless.sample

import android.util.Log
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.ExperimentalGaplessApi
import io.github._514sid.gapless.GaplessClock
import io.github._514sid.gapless.GaplessController
import io.github._514sid.gapless.GaplessEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Plays a [SyncSession] on a schedule fixed by the shared clock, so every device in the session
 * shows the same asset at the same moment without messages per cut.
 *
 * Slot n of the loop starts at the session's start plus the durations of the slots before it.
 * Each device works the schedule out on its own; one that joins late starts at the next slot.
 * The next asset is staged once the current one is on screen, and cut to when its slot starts.
 * A failed asset is not skipped: the schedule moves on at the next slot boundary, as it does on
 * the other devices.
 *
 * Videos are played with their slot's start time on the shared clock, so the library keeps each
 * one on the schedule while it plays: a late start catches up, and drift between the devices'
 * clocks is corrected. [anchor] false plays them unanchored, to compare.
 *
 * Every slot logs how late the asset appeared against the shared schedule, under GaplessSync;
 * samples/sync/compare.py lines two devices' logs up.
 *
 * @param lateMs Cuts this much after each slot starts, to test how a late device catches up.
 */
@OptIn(ExperimentalGaplessApi::class)
class SyncedRunner(
    private val scope: CoroutineScope,
    private val sync: ClockSync,
    private val anchor: Boolean = true,
    private val lateMs: Long = 0,
    private val resolve: (List<String>) -> List<GaplessAsset>?,
) {
    val controller = GaplessController(scope = scope, preloadMs = 3_000, clock = GaplessClock { sync.now() })

    private val _status = MutableStateFlow("waiting for the leader")

    /** One line about the sync, for the on-screen overlay. */
    val status: StateFlow<String> = _status.asStateFlow()

    private class Slot(val n: Long, val asset: GaplessAsset, val startMs: Long)

    // The slot handed to play() that hasn't started or failed yet.
    @Volatile private var pending: Slot? = null
    private val outcomes = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        sync.start(scope)
        scope.launch { controller.events.collect(::onEvent) }
        scope.launch {
            sync.session.filterNotNull().collectLatest { session ->
                while (!sync.isSynced) delay(50)
                val assets = resolve(session.ids)
                if (assets.isNullOrEmpty() || assets.any { (it.durationMs ?: 0) <= 0 }) {
                    Log.e(TAG, "Can't play session ${session.ids}: unknown ids or no durations")
                    _status.value = "can't play the leader's playlist"
                    return@collectLatest
                }
                play(session, assets)
            }
        }
    }

    private suspend fun play(session: SyncSession, assets: List<GaplessAsset>) {
        val durations = assets.map { it.durationMs!! }
        val loopMs = durations.sum()
        val offsets = durations.runningFold(0L) { acc, d -> acc + d } // start of each slot in a loop

        fun slot(n: Long): Slot {
            val i = (n % assets.size).toInt()
            return Slot(n, assets[i], session.startMs + (n / assets.size) * loopMs + offsets[i])
        }

        // The first slot far enough ahead to load in time.
        var n = 0L
        val earliest = sync.now() + JOIN_LEAD_MS
        if (earliest > session.startMs) {
            n = (earliest - session.startMs) / loopMs * assets.size
            while (slot(n).startMs < earliest) n++
        }
        Log.i(TAG, "${if (sync.isLeader) "Leader" else "Follower"} joins at slot $n " +
            "(${slot(n).asset.id}), loop ${loopMs}ms of ${assets.size} assets")

        var next = slot(n)
        controller.prepareNext(next.asset)
        while (true) {
            waitUntil(next.startMs + lateMs)
            pending = next
            if (anchor) controller.play(next.asset, startAtMs = next.startMs) else controller.play(next.asset)
            // Stage the following asset once this one is on screen or failed, but early enough
            // to load before its own slot.
            val following = slot(next.n + 1)
            withTimeoutOrNull((following.startMs - sync.now() - STAGE_LEAD_MS).coerceAtLeast(0)) {
                outcomes.receive()
            }
            next = following
            controller.prepareNext(next.asset)
        }
    }

    /** Waits until [sharedMs] on the shared clock, which may be corrected during the wait. */
    private suspend fun waitUntil(sharedMs: Long) {
        while (true) {
            val remaining = sharedMs - sync.now()
            if (remaining <= 0) return
            delay(minOf(remaining, 20))
        }
    }

    private fun onEvent(event: GaplessEvent) {
        val slot = pending
        when (event) {
            is GaplessEvent.Started -> if (slot != null && event.asset.id == slot.asset.id) {
                pending = null
                val late = sync.fromWallClock(event.timestamp) - slot.startMs
                Log.i(TAG, "slot=${slot.n} asset=${slot.asset.id} late=${late}ms " +
                    "offset=${"%.1f".format(Locale.ROOT, sync.offsetMs)}ms rtt=${"%.1f".format(Locale.ROOT, sync.rttMs)}ms")
                _status.value = "${if (sync.isLeader) "leader" else "follower"}  slot ${slot.n}  " +
                    "late ${late} ms  rtt ${"%.1f".format(Locale.ROOT, sync.rttMs)} ms"
                outcomes.trySend(Unit)
            }
            is GaplessEvent.PlaybackError -> {
                Log.e(TAG, "slot=${slot?.n} ${event.asset.id} failed: ${event.message}")
                pending = null
                outcomes.trySend(Unit)
            }
            is GaplessEvent.PreloadError -> {
                Log.e(TAG, "${event.asset.id} failed to load: ${event.message}")
                // Only the asset being cut to matters now; a failed staged one loads again on play().
                if (slot != null && event.asset.id == slot.asset.id) {
                    pending = null
                    outcomes.trySend(Unit)
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "GaplessSync"
        const val JOIN_LEAD_MS = 3_000L
        const val STAGE_LEAD_MS = 1_500L
    }
}
