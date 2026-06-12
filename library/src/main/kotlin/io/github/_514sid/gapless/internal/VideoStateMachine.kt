package io.github._514sid.gapless.internal

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
import io.github._514sid.gapless.GaplessVideoRepeatMode

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
        .build()
        .apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            repeatMode = when (config.repeatMode) {
                GaplessVideoRepeatMode.LOOP   -> ExoPlayer.REPEAT_MODE_ONE
                GaplessVideoRepeatMode.FREEZE -> ExoPlayer.REPEAT_MODE_OFF
            }
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    onFirstFrameRendered()
                }
                override fun onPlayerError(error: PlaybackException) {
                    onErrorCallback?.invoke(error.message ?: error.toString())
                    handleErrorState()
                }
            })
        }

    val durationMs: Long?
        get() = exoPlayer.duration.takeIf { it != C.TIME_UNSET }

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

    private var pendingItem: PlaybackItem.Video? = null
    private var isPreloaded = false
    private var isPlaying = false

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
                exoPlayer.seekToNextMediaItem()
                exoPlayer.removeMediaItem(0)
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
            .build()
}