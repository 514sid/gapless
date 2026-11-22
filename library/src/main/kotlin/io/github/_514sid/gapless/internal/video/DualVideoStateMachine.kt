package io.github._514sid.gapless.internal.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.github._514sid.gapless.GaplessVideoConfig
import io.github._514sid.gapless.internal.PlaybackItem
import java.util.UUID

/**
 * A/B video engine backed by two [ExoPlayer]s, one per slot. The next clip is staged and rendered
 * on the hidden player, then [play] flips the visible slot for a seamless cut.
 *
 * Failures are handled per slot before anything is reported:
 * - Transient errors (network, decoder reclaimed or failing to open, audio output) are retried
 *   with a growing delay; the last retry rebuilds the slot's ExoPlayer in case the old one is
 *   wedged. Only when retries run out is the error reported.
 * - A live stream that fell behind its window rejoins the live edge.
 * - Video with no decoder on the device is reported at once instead of playing with no picture.
 * - Some devices can't open a second hardware decoder while one is in use. When that happens the
 *   engine switches to hard cuts: the incoming clip opens its decoder only after the outgoing one
 *   releases it, and the outgoing clip's last frame stays up until the new one draws. The switch is
 *   provisional until the parked clip draws at its cut; if its decoder still fails with the other
 *   slot empty, the clip was at fault, so it is reported and seamless cuts resume.
 * - A playing clip that stops advancing for [GaplessVideoConfig.stallTimeoutMs] is restarted.
 */
@OptIn(UnstableApi::class)
internal class DualVideoStateMachine(
    private val context: Context,
    private val config: GaplessVideoConfig = GaplessVideoConfig(),
) : VideoEngine {

    private val handler = Handler(Looper.getMainLooper())

    private val textureViews = arrayOf(
        TextureView(context).apply { isOpaque = true },
        TextureView(context).apply { isOpaque = true },
    )

    private val players = arrayOf(buildPlayer(0), buildPlayer(1))

    var renderState by mutableStateOf(
        DualVideoPlayerState(
            textureViewA = textureViews[0],
            textureViewB = textureViews[1],
            activeSlot = 0,
        )
    )
        private set

    override var onErrorCallback: ((String) -> Unit)? = null
    override var onPreloadErrorCallback: ((assetId: String, message: String) -> Unit)? = null

    /** The item loaded in each slot, or null when the slot is empty. */
    private val items = arrayOfNulls<PlaybackItem.Video>(2)

    /** Bumped whenever a slot's item changes, so delayed retries for the old item do nothing. */
    private val slotGeneration = LongArray(2)

    private val retryCount = IntArray(2)
    private val lastRetryAt = LongArray(2)

    /** Slots holding an item whose decoder is opened only at the cut (hard-cut mode). */
    private val deferred = BooleanArray(2)

    // Written on the main thread, read by the controller's readiness check.
    @Volatile private var readyA = false
    @Volatile private var readyB = false

    /** The slot the controller treats as playing. Normally also the visible one. */
    private var currentSlot = 0

    /** During a hard cut, the slot to show once it draws its first frame; -1 otherwise. */
    private var pendingFlip = -1

    /** Set once the device fails to run two decoders at once; from then on cuts are hard cuts. */
    private var singleDecoderMode = false

    /**
     * The parked clip whose decoder error turned on [singleDecoderMode]. The switch stays
     * provisional until this clip draws at its cut; null once it has, or when no switch is on trial.
     */
    private var trialPlaybackId: UUID? = null

    /** Whether the incoming clip of the current hard cut has had its quick decoder retry. */
    private var cutRetryUsed = false

    private var released = false

    // Stall watchdog state for the current slot.
    private var watchedSlot = -1
    private var watchedGeneration = -1L
    private var lastPosition = C.TIME_UNSET
    private var lastProgressAt = 0L

    private var watchdogRunning = false

    /** Tracks the current clip's progress: refills its retry budget, and catches stalls. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (released || !watchdogRunning) return
            checkForStall()
            // checkForStall() can fail the clip and stop the watchdog; don't revive it then. The
            // removeCallbacks keeps one chain at most, whatever ran in between.
            if (!watchdogRunning) return
            handler.removeCallbacks(this)
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    init {
        players.forEachIndexed { slot, p -> p.setVideoTextureView(textureViews[slot]) }
    }

    private fun startWatchdog() {
        if (watchdogRunning || released) return
        watchdogRunning = true
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        handler.removeCallbacks(watchdog)
    }

    private val inactiveSlot: Int
        get() = 1 - currentSlot

    /**
     * True while a hard cut keeps [slot] on screen, frozen on the outgoing clip's last frame. The
     * incoming slot never counts, even when a newer clip reuses the slot on screen.
     */
    private fun isFrozen(slot: Int) =
        pendingFlip != -1 && slot != pendingFlip && slot == renderState.activeSlot

    private fun ready(slot: Int) = if (slot == 0) readyA else readyB

    private fun setReady(slot: Int, value: Boolean) {
        if (slot == 0) readyA = value else readyB = value
    }

    private fun aspect(slot: Int) = if (slot == 0) renderState.aspectRatioA else renderState.aspectRatioB

    private fun setAspect(slot: Int, value: Float?) {
        renderState = if (slot == 0) {
            renderState.copy(aspectRatioA = value)
        } else {
            renderState.copy(aspectRatioB = value)
        }
    }

    override val isNextReady: Boolean
        get() = ready(inactiveSlot)

    override fun prepare(item: PlaybackItem.Video) {
        load(inactiveSlot, item)
    }

    /** Loads [item] into [slot], paused. In hard-cut mode the decoder is not opened yet. */
    private fun load(slot: Int, item: PlaybackItem.Video) {
        val replaced = items[slot]?.playbackId
        if (replaced != null && replaced == trialPlaybackId && replaced != item.playbackId) {
            endTrial("the parked clip was replaced before its cut")
        }
        slotGeneration[slot]++
        retryCount[slot] = 0
        items[slot] = item
        deferred[slot] = false
        setReady(slot, false)
        // During a hard cut the inactive slot is still the one on screen; leave its layout alone.
        if (!isFrozen(slot)) setAspect(slot, item.aspectRatioHint)

        val p = players[slot]
        p.volume = 0f
        p.setAudioEnabled(item.volume > 0f)
        p.setMediaItem(buildMediaItem(item))
        p.playWhenReady = false

        if (singleDecoderMode && slot != currentSlot && items[currentSlot] != null) {
            // Opening a second decoder fails on this device. Report ready so the cut is not
            // held up; play() opens the decoder once the outgoing clip has let go of its own.
            deferred[slot] = true
            setReady(slot, true)
            return
        }
        p.prepare()
    }

    override fun play(item: PlaybackItem.Video) {
        val target = inactiveSlot
        val old = currentSlot

        if (items[target]?.playbackId != item.playbackId) load(target, item)

        val p = players[target]
        p.volume = item.volume
        p.playWhenReady = true
        currentSlot = target
        resetWatchdog()
        startWatchdog()

        if (deferred[target]) {
            // Hard cut: free the outgoing clip's decoder, then open the incoming one. The outgoing
            // slot stays on screen, frozen on its last frame, until the new clip draws.
            deferred[target] = false
            setReady(target, false)
            clearSlot(old, keepFrame = true)
            if (aspect(target) == null) setAspect(target, item.aspectRatioHint)
            pendingFlip = target
            cutRetryUsed = false
            p.prepare()
            return
        }

        pendingFlip = -1
        renderState = renderState.copy(activeSlot = target)
        clearSlot(old, keepFrame = false)
    }

    private fun onFirstFrame(slot: Int) {
        setReady(slot, true)
        if (slot != pendingFlip) return
        pendingFlip = -1
        if (trialPlaybackId != null && items[slot]?.playbackId == trialPlaybackId) {
            // The parked clip opened its decoder once the other was released: the device really
            // can't run two at once.
            Log.w(TAG, "Confirmed: device can't run two video decoders at once; keeping hard cuts")
            trialPlaybackId = null
        }
        val other = 1 - slot
        renderState = renderState.copy(activeSlot = slot)
        // The slot just hidden was frozen on the old clip's frame; it may since have been given
        // the next item, whose own declared ratio applies.
        setAspect(other, items[other]?.aspectRatioHint)
    }

    /**
     * Stops [slot] and forgets its item. With [keepFrame] the slot keeps its layout so the last
     * decoded frame, which the TextureView holds on to, stays correctly shaped on screen.
     */
    private fun clearSlot(slot: Int, keepFrame: Boolean) {
        slotGeneration[slot]++
        items[slot] = null
        deferred[slot] = false
        retryCount[slot] = 0
        if (pendingFlip == slot) pendingFlip = -1
        val p = players[slot]
        p.stop()
        p.clearMediaItems()
        p.playWhenReady = false
        setReady(slot, false)
        if (!keepFrame) setAspect(slot, null)
    }

    private fun onPlayerError(slot: Int, error: PlaybackException) {
        val item = items[slot] ?: return
        val message = error.message ?: error.errorCodeName
        Log.w(TAG, "Slot $slot (${item.assetId}): ${error.errorCodeName}: $message")
        val code = error.errorCode

        when (classifyError(error)) {
            ErrorAction.REJOIN_LIVE -> if (retryCount[slot] < MAX_RETRIES) {
                retryCount[slot]++
                lastRetryAt[slot] = SystemClock.elapsedRealtime()
                players[slot].seekToDefaultPosition()
                players[slot].prepare()
                return
            }
            ErrorAction.RETRY -> {
                if (isDecoderResourceError(code)) {
                    if (slot == pendingFlip) {
                        if (!cutRetryUsed) {
                            // stop() on the outgoing player runs on its own playback thread, so
                            // this one can reach codec init before the old codec is freed. Try
                            // again shortly, without counting it against the clip.
                            cutRetryUsed = true
                            retryAfter(slot, CUT_RETRY_DELAY_MS)
                            return
                        }
                        if (item.playbackId == trialPlaybackId) {
                            // The other slot is empty and the decoder still won't open, so the
                            // clip was at fault, not the device.
                            fail(slot, message)
                            return
                        }
                    } else if (switchToSingleDecoder(slot)) {
                        return
                    }
                }
                val limit = retryLimit(code)
                if (retryCount[slot] < limit) {
                    scheduleRetry(slot, limit)
                    return
                }
            }
            ErrorAction.FATAL -> Unit
        }
        fail(slot, message)
    }

    /**
     * Handles running out of decoders when both slots hold one. Returns true if that was the cause
     * and it has been dealt with, so the error is not reported.
     */
    private fun switchToSingleDecoder(failedSlot: Int): Boolean {
        val other = 1 - failedSlot
        if (items[other] == null || deferred[other]) return false
        val staged = if (failedSlot == currentSlot) other else failedSlot
        if (staged == failedSlot) {
            // Only a staged clip failing to open next to a playing one says the device can't run
            // two decoders. Its own cut will tell whether the device or the clip was at fault.
            if (!singleDecoderMode) {
                Log.w(TAG, "Second video decoder failed to open; trying hard cuts")
                singleDecoderMode = true
                trialPlaybackId = items[staged]?.playbackId
            }
        } else {
            // The playing clip lost its decoder (reclaimed by another app, say). That proves
            // nothing about running two, so park the staged clip for this one cut only.
            Log.w(TAG, "Playing clip lost its decoder; next cut is a hard cut")
        }
        // Park the staged clip without a decoder; play() opens it at the cut. Its player is idle
        // after the error (or is stopped here), and keeps its media item for that later prepare().
        // Bumping the generation drops any retry still queued for it, which would reopen a decoder.
        players[staged].stop()
        slotGeneration[staged]++
        retryCount[staged] = 0
        deferred[staged] = true
        setReady(staged, true)
        // If the clip on screen was the one that lost out, it can have the decoder back now.
        if (failedSlot == currentSlot) scheduleRetry(failedSlot)
        return true
    }

    /** Uses up one of [limit] retries for [slot]; the last one rebuilds the player. */
    private fun scheduleRetry(slot: Int, limit: Int = MAX_RETRIES) {
        retryCount[slot]++
        lastRetryAt[slot] = SystemClock.elapsedRealtime()
        val attempt = retryCount[slot]
        retryAfter(slot, RETRY_DELAY_MS * attempt, rebuild = attempt >= limit)
    }

    private fun retryAfter(slot: Int, delayMs: Long, rebuild: Boolean = false) {
        val generation = slotGeneration[slot]
        handler.postDelayed({
            if (!released && slotGeneration[slot] == generation) retry(slot, rebuild)
        }, delayMs)
    }

    /** Drops a provisional switch to hard cuts, so the next clip is staged with its own decoder. */
    private fun endTrial(reason: String) {
        Log.w(TAG, "Back to seamless cuts: $reason")
        singleDecoderMode = false
        trialPlaybackId = null
    }

    private fun retry(slot: Int, rebuild: Boolean) {
        val item = items[slot] ?: return
        if (deferred[slot]) return
        setReady(slot, false)
        if (!rebuild) {
            players[slot].prepare()
            return
        }
        // Last attempt: a codec or renderer can be left in a state only a new player clears.
        val old = players[slot]
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        val volume = old.volume
        old.release()
        val p = buildPlayer(slot)
        players[slot] = p
        p.setVideoTextureView(textureViews[slot])
        p.volume = volume
        p.setAudioEnabled(item.volume > 0f)
        p.setMediaItem(buildMediaItem(item), position)
        p.playWhenReady = playWhenReady
        p.prepare()
    }

    /** Gives up on [slot]'s item and reports it. A failed current clip stays frozen on screen. */
    private fun fail(slot: Int, message: String) {
        val item = items[slot] ?: return
        val isCurrent = slot == currentSlot
        val visible = renderState.activeSlot
        if (item.playbackId == trialPlaybackId) endTrial("the parked clip failed on its own")
        clearSlot(slot, keepFrame = isCurrent && slot == visible)
        if (isCurrent && slot != visible) {
            // A hard cut to this slot never drew, so the outgoing slot is still on screen with its
            // last frame. Make that slot current again so the frozen frame stays up until the next
            // cut. A clip already staged there (parked, so its decoder is closed) moves to the
            // hidden slot, where the next play() expects it.
            val staged = items[visible]
            if (staged != null) clearSlot(visible, keepFrame = true)
            currentSlot = visible
            resetWatchdog()
            if (staged != null) load(inactiveSlot, staged)
        }
        if (isCurrent) {
            stopWatchdog()
            onErrorCallback?.invoke(message)
        } else {
            onPreloadErrorCallback?.invoke(item.assetId, message)
        }
    }

    private fun onTracksChanged(slot: Int, tracks: Tracks) {
        val message = tracks.unsupportedVideoMessage() ?: return
        if (items[slot] == null) return
        Log.w(TAG, "Slot $slot: $message")
        fail(slot, message)
    }

    private fun resetWatchdog() {
        watchedSlot = -1
    }

    private fun checkForStall() {
        val slot = currentSlot
        val p = players[slot]
        val now = SystemClock.elapsedRealtime()
        val active = items[slot] != null && !deferred[slot] && p.playWhenReady &&
            (p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_READY)
        if (!active || watchedSlot != slot || watchedGeneration != slotGeneration[slot]) {
            watchedSlot = slot
            watchedGeneration = slotGeneration[slot]
            lastPosition = p.currentPosition
            lastProgressAt = now
            return
        }
        val position = p.currentPosition
        if (position != lastPosition) {
            lastPosition = position
            lastProgressAt = now
            // Playing again for a while: earlier failures no longer count against this item.
            if (retryCount[slot] > 0 && now - lastRetryAt[slot] > RETRY_FORGET_MS) retryCount[slot] = 0
            return
        }
        if (config.stallTimeoutMs <= 0 || now - lastProgressAt < config.stallTimeoutMs) return

        lastProgressAt = now
        val message = "Playback stalled for ${config.stallTimeoutMs / 1000}s"
        Log.w(TAG, "Slot $slot (${items[slot]?.assetId}): $message")
        if (retryCount[slot] < MAX_RETRIES) {
            p.stop() // leaves the media item and position for the retry's prepare()
            scheduleRetry(slot)
        } else {
            fail(slot, message)
        }
    }

    override fun clear() {
        clearSlot(0, keepFrame = false)
        clearSlot(1, keepFrame = false)
        currentSlot = 0
        pendingFlip = -1
        if (trialPlaybackId != null) endTrial("playback was cleared")
        stopWatchdog()
        resetWatchdog()
        renderState = renderState.copy(activeSlot = 0)
    }

    override fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        clear()
        players.forEach {
            it.setVideoTextureView(null)
            it.release()
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        DualVideoPlayer(state = renderState, modifier = modifier)
    }

    private fun ExoPlayer.setAudioEnabled(enabled: Boolean) {
        // A muted clip needs no audio decoder or AudioTrack, and an audio output failure (no
        // device, HDMI switching) can't stop the picture.
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !enabled)
            .build()
    }

    private fun buildPlayer(slot: Int): ExoPlayer =
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context).setEnableDecoderFallback(config.enableDecoderFallback)
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        config.minBufferMs,
                        config.maxBufferMs,
                        config.bufferForPlaybackMs,
                        config.bufferForPlaybackAfterRebufferMs,
                    )
                    .build()
            )
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = onFirstFrame(slot)
                    // TextureView stretches frames to its bounds, so the slot has to carry the real ratio.
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (isFrozen(slot)) return
                        videoSize.displayAspectRatio()?.let { setAspect(slot, it) }
                    }
                    override fun onTracksChanged(tracks: Tracks) = onTracksChanged(slot, tracks)
                    override fun onPlayerError(error: PlaybackException) = onPlayerError(slot, error)
                })
            }

    private companion object {
        const val TAG = "GaplessVideo"
        const val RETRY_DELAY_MS = 1_000L
        const val CUT_RETRY_DELAY_MS = 150L
        const val RETRY_FORGET_MS = 30_000L
        const val WATCHDOG_INTERVAL_MS = 1_000L
    }
}
