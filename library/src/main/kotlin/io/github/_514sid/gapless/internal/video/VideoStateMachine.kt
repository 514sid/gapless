package io.github._514sid.gapless.internal.video

import android.content.Context
import android.graphics.Bitmap
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

/** Experimental single-[ExoPlayer] video engine. See [io.github._514sid.gapless.GaplessVideoStrategy]. */
@OptIn(UnstableApi::class)
internal class VideoStateMachine(context: Context, config: GaplessVideoConfig = GaplessVideoConfig()) : VideoEngine {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(config.enableDecoderFallback))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    config.minBufferMs,
                    config.maxBufferMs,
                    config.bufferForPlaybackMs,
                    config.bufferForPlaybackAfterRebufferMs,
                )
                .apply { if (config.targetBufferBytes > 0) setTargetBufferBytes(config.targetBufferBytes) }
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
                // TextureView stretches frames to its bounds, so the layout has to carry the real ratio.
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoSize.displayAspectRatio()?.let { renderState = renderState.copy(aspectRatio = it) }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                        pausedAtEnd = true
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    fail(error.message ?: error.errorCodeName)
                }
                override fun onTracksChanged(tracks: Tracks) {
                    tracks.unsupportedVideoMessage()?.let { fail(it) }
                }
            })
        }

    private val bufferForPlaybackMs: Long = config.bufferForPlaybackMs.toLong()

    override val isNextReady: Boolean
        get() {
            if (!isPlaying) return exoPlayer.playbackState == Player.STATE_READY
            if (exoPlayer.mediaItemCount <= 1) return false
            val duration = exoPlayer.duration
            if (duration == C.TIME_UNSET || duration <= 0L) return true
            val remainingInCurrent = duration - exoPlayer.currentPosition
            val bufferedIntoNext = exoPlayer.totalBufferedDuration - remainingInCurrent
            return bufferedIntoNext >= bufferForPlaybackMs
        }

    var renderState by mutableStateOf(VideoPlayerState())
        private set

    private var textureViewRef: TextureView? = null
        set(value) {
            val changed = value != field
            field = value
            when {
                value == null -> exoPlayer.clearVideoSurface()
                changed -> exoPlayer.setVideoTextureView(value)
            }
        }

    override var onErrorCallback: ((String) -> Unit)? = null
    override var onPreloadErrorCallback: ((assetId: String, message: String) -> Unit)? = null

    private var pendingItem: PlaybackItem.Video? = null
    private var isPlaying = false

    private var pausedAtEnd = false

    override fun prepare(item: PlaybackItem.Video) {
        pendingItem = item

        if (isPlaying) {
            val nextIndex = exoPlayer.currentMediaItemIndex + 1
            while (exoPlayer.mediaItemCount > nextIndex) {
                exoPlayer.removeMediaItem(exoPlayer.mediaItemCount - 1)
            }
            exoPlayer.addMediaItem(buildMediaItem(item))
        } else {
            renderState = renderState.copy(aspectRatio = item.aspectRatioHint)
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.playWhenReady = false
            exoPlayer.prepare()
        }
    }

    override fun play(item: PlaybackItem.Video) {
        if (pendingItem?.playbackId == item.playbackId) {
            if (isPlaying && exoPlayer.mediaItemCount > 1) {
                // The next clip's real ratio is only known once it decodes; cover the cut with a
                // snapshot whenever it may differ from what is on screen.
                val hint = item.aspectRatioHint
                if (hint == null || renderState.aspectRatio != hint) {
                    renderState = renderState.copy(snapshot = captureFrame())
                }
                if (hint != null) renderState = renderState.copy(aspectRatio = hint)
                exoPlayer.volume = item.volume

                if (!pausedAtEnd) {
                    exoPlayer.seekToNextMediaItem()
                }
                if (exoPlayer.mediaItemCount > 1 && exoPlayer.currentMediaItemIndex > 0) {
                    exoPlayer.removeMediaItem(0)
                }
                exoPlayer.play()
                pausedAtEnd = false
            } else {
                exoPlayer.volume = item.volume
                pausedAtEnd = false
                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                    exoPlayer.seekToDefaultPosition()
                }
                exoPlayer.play()
            }
        } else {
            pendingItem = item
            pausedAtEnd = false
            renderState = renderState.copy(aspectRatio = item.aspectRatioHint)
            exoPlayer.volume = item.volume
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.prepare()
            exoPlayer.play()
        }
        isPlaying = true
    }

    private fun onFirstFrameRendered() {
        renderState = renderState.copy(snapshot = null)
    }

    /**
     * Reports an error and resets the engine. The player is left idle after an error, so carrying
     * on in the "playing" state would queue later clips onto a player that never starts again.
     */
    private fun fail(message: String) {
        val wasPlaying = isPlaying
        val assetId = pendingItem?.assetId
        clear()
        if (wasPlaying) {
            onErrorCallback?.invoke(message)
        } else if (assetId != null) {
            onPreloadErrorCallback?.invoke(assetId, message)
        }
    }

    /**
     * A copy of the frame on screen to cover the cut, or null if there's no memory for one. It is
     * only up for a few frames, so it is taken at half size: a full-size copy of a 4K view is a
     * 33 MB heap allocation on every cut.
     */
    private fun captureFrame(): Bitmap? {
        val view = textureViewRef ?: return null
        val width = view.width / SNAPSHOT_DIVISOR
        val height = view.height / SNAPSHOT_DIVISOR
        if (width <= 0 || height <= 0) return null
        return try {
            view.getBitmap(width, height)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun clear() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        isPlaying = false
        pausedAtEnd = false
        pendingItem = null
        renderState = VideoPlayerState()
    }

    override fun release() {
        clear()
        textureViewRef = null
        exoPlayer.release()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        VideoPlayer(
            state = renderState,
            onTextureViewCreated = { view ->
                view.isOpaque = true
                textureViewRef = view
            },
            modifier = modifier,
        )
    }

    private companion object {
        const val SNAPSHOT_DIVISOR = 2
    }
}
