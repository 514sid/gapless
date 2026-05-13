package io.github._514sid.gapless.ui

import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import io.github._514sid.gapless.GaplessAsset

@Composable
internal fun VideoSlot(
    slotData: MediaSlotData,
    onError: (GaplessAsset, String) -> Unit,
) {
    val context = LocalContext.current
    var firstFrameReady by remember { mutableStateOf(false) }
    var videoRatio by remember { mutableStateOf<Float?>(null) }
    val textureView = remember { TextureView(context) }
    val listenerRef = remember { object { var value: Player.Listener? = null } }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
        }
    }

    DisposableEffect(Unit) {
        exoPlayer.setVideoTextureView(textureView)
        onDispose {
            listenerRef.value?.let { exoPlayer.removeListener(it) }
            exoPlayer.release()
        }
    }

    LaunchedEffect(slotData.playbackId) {
        val asset = slotData.asset ?: return@LaunchedEffect
        firstFrameReady = false
        videoRatio = null
        listenerRef.value?.let { exoPlayer.removeListener(it) }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameReady = true }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0) videoRatio = (v.width.toFloat() * v.pixelWidthHeightRatio) / v.height
            }
            override fun onPlayerError(e: PlaybackException) {
                onError(asset, e.localizedMessage ?: "Video error")
            }
        }
        exoPlayer.addListener(listener)
        listenerRef.value = listener
        val uri = if (asset.uri.startsWith("/")) "file://${asset.uri}" else asset.uri
        exoPlayer.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(asset.mimeType).build())
        exoPlayer.prepare()
        exoPlayer.seekTo(0)
        if (slotData.isActive) exoPlayer.play() else exoPlayer.pause()
    }

    LaunchedEffect(slotData.isActive) {
        exoPlayer.seekTo(0)
        if (slotData.isActive) exoPlayer.play() else exoPlayer.pause()
    }

    val knownRatio = slotData.asset?.run {
        if (width != null && height != null && height > 0) width.toFloat() / height else null
    }
    val ratio = knownRatio ?: videoRatio
    val videoModifier = (if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize())
        .graphicsLayer { alpha = if (firstFrameReady) 1f else 0f }

    AndroidView(factory = { textureView }, modifier = videoModifier)
}
