package io.github._514sid.gapless.internal.video

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.github._514sid.gapless.GaplessVideoConfig
import io.github._514sid.gapless.internal.PlaybackItem

@OptIn(UnstableApi::class)
internal class VideoStateMachine(context: Context, config: GaplessVideoConfig = GaplessVideoConfig()) {
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(config.enableDecoderFallback))
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
        .setPauseAtEndOfMediaItems(true)
        .build()
        .apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    onFirstFrameRendered()
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                        pausedAtEnd = true
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    val msg = error.message ?: error.toString()
                    if (!isPlaying) {
                        onPreloadErrorCallback?.invoke(pendingItem?.assetId ?: "", msg)
                    } else {
                        onErrorCallback?.invoke(msg)
                    }
                    handleErrorState()
                }
            })
        }

    val durationMs: Long?
        get() = exoPlayer.duration.takeIf { it != C.TIME_UNSET }

    private val bufferForPlaybackMs: Long = config.bufferForPlaybackMs.toLong()

    /**
     * Whether the next video is buffered enough to transition without a rebuffer.
     *
     * While a clip is playing, the queued item lives at index 1 and ExoPlayer only buffers into it
     * once the remaining content of the current item fits inside the buffer window, so this usually
     * becomes true as the transition point approaches. While idle (no clip playing yet) it reports
     * whether the first/only item can render.
     */
    val isNextReady: Boolean
        get() {
            if (!isPlaying) return exoPlayer.playbackState == Player.STATE_READY
            if (exoPlayer.mediaItemCount <= 1) return false
            val duration = exoPlayer.duration
            // Live or not-yet-known current item: we cannot compute how much is buffered ahead.
            if (duration == C.TIME_UNSET || duration <= 0L) return true
            val remainingInCurrent = duration - exoPlayer.currentPosition
            val bufferedIntoNext = exoPlayer.totalBufferedDuration - remainingInCurrent
            return bufferedIntoNext >= bufferForPlaybackMs
        }

    var renderState by mutableStateOf(VideoPlayerState())
        private set

    var textureViewRef: TextureView? = null
        set(value) {
            field = value
            if (value != null) {
                exoPlayer.setVideoTextureView(value)
            } else {
                exoPlayer.clearVideoSurface()
            }
        }

    var onErrorCallback: ((String) -> Unit)? = null
    var onPreloadErrorCallback: ((assetId: String, message: String) -> Unit)? = null

    private var pendingItem: PlaybackItem.Video? = null
    private var isPreloaded = false
    private var isPlaying = false

    // Set by pauseAtEndOfMediaItems when a clip reaches its end and the player parks on the last
    // frame. Cleared when the next play()/prepare() acts on the player.
    private var pausedAtEnd = false

    fun prepare(item: PlaybackItem.Video) {
        pendingItem = item
        isPreloaded = false

        if (isPlaying) {
            if (exoPlayer.mediaItemCount > 1) exoPlayer.removeMediaItem(1)
            exoPlayer.addMediaItem(buildMediaItem(item))
        } else {
            renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.playWhenReady = false
            exoPlayer.prepare()
        }
    }

    fun play(item: PlaybackItem.Video) {
        if (pendingItem?.playbackId == item.playbackId) {
            if (isPlaying && exoPlayer.mediaItemCount > 1) {
                if (renderState.aspectRatio != item.targetAspectRatio) {
                    renderState = renderState.copy(snapshot = textureViewRef?.bitmap)
                }
                renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
                exoPlayer.volume = item.volume
                if (pausedAtEnd) {
                    // The previous clip reached its end and pauseAtEndOfMediaItems parked the player
                    // on its last frame. Removing the finished item advances to the queued clip at
                    // its start; seekToNextMediaItem from this state leaves the renderer frozen on
                    // the first frame.
                    exoPlayer.removeMediaItem(0)
                } else {
                    exoPlayer.seekToNextMediaItem()
                    exoPlayer.removeMediaItem(0)
                }
                pausedAtEnd = false
                exoPlayer.play()
            } else {
                exoPlayer.volume = item.volume
                exoPlayer.play()
            }
        } else {
            pendingItem = item
            renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
            exoPlayer.volume = item.volume
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        isPlaying = true
    }

    fun onFirstFrameRendered() {
        isPreloaded = true
        renderState = renderState.copy(snapshot = null)
    }

    fun handleErrorState() {
        isPreloaded = false
        pendingItem = null
        renderState = renderState.copy(snapshot = null)
    }

    fun cancelPrepare() {
        pendingItem = null
        isPreloaded = false
        if (isPlaying) {
            if (exoPlayer.mediaItemCount > 1) exoPlayer.removeMediaItem(1)
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            renderState = renderState.copy(aspectRatio = null)
        }
    }

    fun clear() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        isPlaying = false
        isPreloaded = false
        pausedAtEnd = false
        pendingItem = null
        renderState = VideoPlayerState()
    }

    fun release() {
        clear()
        textureViewRef = null
        exoPlayer.release()
    }

    private fun buildMediaItem(item: PlaybackItem.Video): MediaItem =
        MediaItem.Builder()
            .setUri(item.uri)
            .setTag(item.targetAspectRatio)
            .apply {
                val clipMs = item.durationMs
                if (clipMs != null && clipMs > 0L) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(clipMs)
                            .build()
                    )
                }
            }
            .build()
}