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

/** A/B video engine backed by two [ExoPlayer]s, one per slot. The next clip is staged and rendered
 * on the hidden player, then [play] flips the visible slot for a seamless cut. */
@OptIn(UnstableApi::class)
internal class DualVideoStateMachine(
    context: Context,
    private val config: GaplessVideoConfig = GaplessVideoConfig(),
) : VideoEngine {

    private val playerA = buildPlayer(context, slot = 0)
    private val playerB = buildPlayer(context, slot = 1)

    private val textureViewA = TextureView(context).apply { isOpaque = true }
    private val textureViewB = TextureView(context).apply { isOpaque = true }

    init {
        playerA.setVideoTextureView(textureViewA)
        playerB.setVideoTextureView(textureViewB)
    }

    var renderState by mutableStateOf(
        DualVideoPlayerState(
            textureViewA = textureViewA,
            textureViewB = textureViewB,
            activeSlot = 0,
        )
    )
        private set

    override var onErrorCallback: ((String) -> Unit)? = null
    override var onPreloadErrorCallback: ((assetId: String, message: String) -> Unit)? = null

    private var pendingItem: PlaybackItem.Video? = null

    private var readyA = false
    private var readyB = false

    private val inactiveSlot: Int
        get() = if (renderState.activeSlot == 0) 1 else 0

    private fun player(slot: Int) = if (slot == 0) playerA else playerB

    private fun ready(slot: Int) = if (slot == 0) readyA else readyB

    private fun setReady(slot: Int, value: Boolean) {
        if (slot == 0) readyA = value else readyB = value
    }

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
        pendingItem = item

        val target = inactiveSlot
        val p = player(target)
        setReady(target, false)
        setAspect(target, item.targetAspectRatio)

        p.volume = 0f
        p.setMediaItem(buildMediaItem(item))
        p.playWhenReady = false
        p.prepare()
    }

    override fun play(item: PlaybackItem.Video) {
        val target = inactiveSlot
        val p = player(target)
        val old = renderState.activeSlot

        if (pendingItem?.playbackId != item.playbackId) {
            setReady(target, false)
            setAspect(target, item.targetAspectRatio)
            p.setMediaItem(buildMediaItem(item))
            p.prepare()
        }

        p.volume = item.volume
        p.playWhenReady = true
        renderState = renderState.copy(activeSlot = target)
        pendingItem = null

        clearSlot(old)
    }

    private fun clearSlot(slot: Int) {
        val p = player(slot)
        p.stop()
        p.clearMediaItems()
        p.playWhenReady = false
        setReady(slot, false)
        setAspect(slot, null)
    }

    private fun onError(slot: Int, error: PlaybackException) {
        val msg = error.message ?: error.toString()
        if (slot == renderState.activeSlot) {
            onErrorCallback?.invoke(msg)
        } else {
            onPreloadErrorCallback?.invoke(pendingItem?.assetId ?: "", msg)
        }
        clearSlot(slot)
    }

    override fun clear() {
        clearSlot(0)
        clearSlot(1)
        pendingItem = null
        renderState = renderState.copy(activeSlot = 0)
    }

    override fun release() {
        clear()
        playerA.setVideoTextureView(null)
        playerB.setVideoTextureView(null)
        playerA.release()
        playerB.release()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        DualVideoPlayer(state = renderState, modifier = modifier)
    }

    private fun buildPlayer(context: Context, slot: Int): ExoPlayer =
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
                    override fun onRenderedFirstFrame() = setReady(slot, true)
                    override fun onPlayerError(error: PlaybackException) = onError(slot, error)
                })
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
