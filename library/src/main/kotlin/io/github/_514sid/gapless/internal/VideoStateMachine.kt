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

@OptIn(UnstableApi::class)
internal class VideoStateMachine(context: Context) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(false))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 500, 1000)
                .build()
        )
        .build()
        .apply {
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    val aspectRatio = currentMediaItem?.localConfiguration?.tag as? Float
                    onFirstFrameRendered(aspectRatio)
                }
                override fun onPlayerError(error: PlaybackException) {
                    onError?.invoke(error.message ?: error.toString())
                    onError()
                }
            })
        }

    var renderState by mutableStateOf(VideoPlayerState())
        private set

    var textureViewRef: TextureView? = null
    var onError: ((String) -> Unit)? = null

    private var pendingItem: PlaybackItem.Video? = null
    private var isPreloaded = false

    // True while a video is actively playing, used to pick the right
    // ExoPlayer path (playlist vs cold start) when a new prepare arrives.
    private var isPlaying = false

    /**
     * Start buffering [item] in the background without changing the display.
     * Cancels any previous pending prepare.
     */
    fun prepare(item: PlaybackItem.Video) {
        pendingItem = item
        isPreloaded = false

        if (isPlaying) {
            // Queue as second playlist item for gapless video-to-video handoff.
            // Aspect ratio is intentionally NOT updated here - it changes in play()
            // after the snapshot is captured, so the current video never visibly jumps.
            if (exoPlayer.mediaItemCount > 1) exoPlayer.removeMediaItem(1)
            exoPlayer.addMediaItem(buildMediaItem(item))
        } else {
            // Cold prepare coming from image, web, or idle.
            // Safe to update aspect ratio now - another layer covers the VideoLayer.
            renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.playWhenReady = false
            exoPlayer.prepare()
        }
    }

    /**
     * Transition to [item].
     *
     * If [item] matches the pending prepare, the buffered content is used
     * directly. Otherwise, the item is prepared and played immediately.
     */
    fun play(item: PlaybackItem.Video) {
        if (pendingItem?.playbackId == item.playbackId) {
            if (isPlaying && exoPlayer.mediaItemCount > 1) {
                // Gapless playlist swap - capture snapshot if aspect ratio changes.
                if (renderState.aspectRatio != item.targetAspectRatio) {
                    renderState = renderState.copy(snapshot = textureViewRef?.bitmap)
                }
                renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
                exoPlayer.seekToNextMediaItem()
                exoPlayer.removeMediaItem(0)
            } else {
                exoPlayer.play()
            }
        } else {
            // Different item or nothing prepared - cold immediate play.
            pendingItem = item
            renderState = renderState.copy(aspectRatio = item.targetAspectRatio)
            exoPlayer.setMediaItem(buildMediaItem(item))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        isPlaying = true
    }

    /** Called by the ExoPlayer listener when the first frame of a new item renders. */
    fun onFirstFrameRendered(aspectRatio: Float?) {
        isPreloaded = true
        renderState = renderState.copy(
            aspectRatio = aspectRatio ?: renderState.aspectRatio,
            snapshot = null
        )
    }

    fun onError() {
        isPreloaded = false
        pendingItem = null
        renderState = renderState.copy(snapshot = null)
    }

    /** Drop a pending prepare without stopping current playback. */
    fun cancelPrepare() {
        pendingItem = null
        isPreloaded = false
        if (isPlaying) {
            // Drop the queued next item from the playlist, keep current video running.
            if (exoPlayer.mediaItemCount > 1) exoPlayer.removeMediaItem(1)
        } else {
            // Was cold-preparing with no video on screen - reset fully.
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
        exoPlayer.clearVideoSurface()
        exoPlayer.release()
    }

    private fun buildMediaItem(item: PlaybackItem.Video): MediaItem =
        MediaItem.Builder()
            .setUri(item.uri)
            .setTag(item.targetAspectRatio)
            .build()
}
