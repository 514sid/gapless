package io.github._514sid.gapless.internal.video

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

/** Experimental single-[ExoPlayer] video engine. See [io.github._514sid.gapless.GaplessVideoStrategy]. */
@OptIn(UnstableApi::class)
internal class VideoStateMachine(context: Context, config: GaplessVideoConfig = GaplessVideoConfig()) : VideoEngine {

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

    var textureViewRef: TextureView? = null
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
    private var isPreloaded = false
    private var isPlaying = false

    private var pausedAtEnd = false

    override fun prepare(item: PlaybackItem.Video) {
        pendingItem = item
        isPreloaded = false

        if (isPlaying) {
            val nextIndex = exoPlayer.currentMediaItemIndex + 1
            while (exoPlayer.mediaItemCount > nextIndex) {
                exoPlayer.removeMediaItem(exoPlayer.mediaItemCount - 1)
            }
            exoPlayer.addMediaItem(buildMediaItem(item))
        } else {
            renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.playWhenReady = false
            exoPlayer.prepare()
        }
    }

    override fun play(item: PlaybackItem.Video) {
        if (pendingItem?.playbackId == item.playbackId) {
            if (isPlaying && exoPlayer.mediaItemCount > 1) {
                if (renderState.aspectRatio != item.targetAspectRatio) {
                    renderState = renderState.copy(snapshot = textureViewRef?.bitmap)
                }
                renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
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
            renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
            exoPlayer.volume = item.volume
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.prepare()
            exoPlayer.play()
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
        if (!isPlaying) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            renderState = renderState.copy(aspectRatio = null)
        }
    }

    override fun clear() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        isPlaying = false
        isPreloaded = false
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
