package io.github._514sid.gapless.ui

import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import io.github._514sid.gapless.GaplessAsset

@Composable
internal fun VideoSlot(
    asset: GaplessAsset,
    generation: Long,
    isActive: Boolean,
    onError: (GaplessAsset, String) -> Unit,
) {
    val context = LocalContext.current
    var firstFrameReady by remember { mutableStateOf(false) }
    var videoRatio by remember { mutableStateOf<Float?>(null) }
    val textureView = remember { TextureView(context) }

    val exoPlayer = remember(asset.id) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
        }
    }

    DisposableEffect(asset.id) {
        exoPlayer.setVideoTextureView(textureView)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameReady = true }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0) videoRatio = (v.width.toFloat() * v.pixelWidthHeightRatio) / v.height
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                onError(asset, e.localizedMessage ?: "Video error")
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(asset.id) {
        firstFrameReady = false
        val uri = if (asset.uri.startsWith("/")) "file://${asset.uri}" else asset.uri
        exoPlayer.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType(asset.mimeType).build())
        exoPlayer.prepare()
    }

    LaunchedEffect(isActive, generation) {
        if (isActive) {
            exoPlayer.seekTo(0)
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    val videoModifier = (videoRatio?.let { Modifier.then(Modifier) } ?: Modifier.fillMaxSize())
        .graphicsLayer { alpha = if (firstFrameReady) 1f else 0f }

    AndroidView(factory = { textureView }, modifier = videoModifier)
}