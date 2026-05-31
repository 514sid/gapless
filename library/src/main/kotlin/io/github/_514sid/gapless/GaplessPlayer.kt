package io.github._514sid.gapless

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import io.github._514sid.gapless.internal.ActiveContent
import io.github._514sid.gapless.internal.ImagePlayer
import io.github._514sid.gapless.internal.PlayerOrchestrator
import io.github._514sid.gapless.internal.RotatedContainer
import io.github._514sid.gapless.internal.VideoPlayer
import io.github._514sid.gapless.internal.WebPlayer

private enum class DisplayState { LOADING, EMPTY, IDLE, PLAYING }

@Composable
fun GaplessPlayer(
    modifier: Modifier = Modifier,
    manager: GaplessPlaylistManager,
    rotation: GaplessRotation = GaplessRotation.Deg0,
    emptyState: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Black)) },
    idleState: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Black)) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val orchestrator = remember(manager) {
        PlayerOrchestrator(context, scope, manager.controller).also { o ->
            o.onError = { message -> manager.onPlaybackError(message) }
        }
    }

    val textureView = remember {
        TextureView(context).apply { isOpaque = true }
    }

    DisposableEffect(textureView, orchestrator) {
        orchestrator.video.exoPlayer.setVideoTextureView(textureView)
        orchestrator.video.textureViewRef = textureView
        manager.onPlayerReady()

        onDispose {
            orchestrator.video.exoPlayer.clearVideoSurface()
            orchestrator.video.textureViewRef = null
        }
    }

    DisposableEffect(orchestrator) {
        onDispose {
            orchestrator.release()
            manager.stop()
        }
    }

    var displayState by remember { mutableStateOf(DisplayState.LOADING) }

    LaunchedEffect(manager) {
        manager.events.collect { event ->
            displayState = when (event) {
                is GaplessEvent.Empty   -> DisplayState.EMPTY
                is GaplessEvent.Idle    -> DisplayState.IDLE
                is GaplessEvent.Started -> DisplayState.PLAYING
                else                    -> displayState
            }
        }
    }

    Box(
        modifier = modifier.onSizeChanged { size ->
            orchestrator.updateContainerSize(size.width, size.height)
        }
    ) {
        RotatedContainer(rotation = rotation.degrees) {
            val active = orchestrator.activeContent

            VideoPlayer(
                textureView = textureView,
                state = orchestrator.video.renderState,
                modifier = Modifier.fillMaxSize()
            )

            ImagePlayer(
                state = orchestrator.image.renderState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (active == ActiveContent.IMAGE) 1f else 0f }
            )

            WebPlayer(
                state = orchestrator.web.renderState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (active == ActiveContent.WEB) 1f else 0f }
            )
        }

        when (displayState) {
            DisplayState.LOADING -> Unit
            DisplayState.EMPTY   -> emptyState()
            DisplayState.IDLE    -> idleState()
            DisplayState.PLAYING -> Unit
        }
    }
}
