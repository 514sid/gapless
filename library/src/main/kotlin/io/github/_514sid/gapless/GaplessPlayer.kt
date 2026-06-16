package io.github._514sid.gapless

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import io.github._514sid.gapless.internal.ActiveContent
import io.github._514sid.gapless.internal.PlayerOrchestrator
import io.github._514sid.gapless.internal.RotatedContainer
import io.github._514sid.gapless.internal.image.ImagePlayer
import io.github._514sid.gapless.internal.web.WebPlayer

@Composable
fun GaplessPlayer(
    modifier: Modifier = Modifier,
    manager: GaplessController,
    rotation: GaplessRotation = GaplessRotation.Deg0,
    videoConfig: GaplessVideoConfig = GaplessVideoConfig(),
    webConfig: GaplessWebConfig = GaplessWebConfig(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val orchestrator = remember(manager) {
        PlayerOrchestrator(context, scope, manager.commands, videoConfig, webConfig).also { o ->
            o.onError = { message -> manager.onPlaybackError(message) }
            o.onPreloadError = { assetId, message -> manager.onPreloadError(assetId, message) }
            o.onPreloadMissed = { assetId, elapsedMs -> manager.onPreloadMissed(assetId, elapsedMs) }
            manager.isNextReadyProvider = { o.isNextReady }
        }
    }

    DisposableEffect(orchestrator) {
        onDispose {
            orchestrator.release()
            manager.stop()
        }
    }

    Box(
        modifier = modifier.onSizeChanged { size ->
            orchestrator.updateContainerSize(size.width, size.height)
        }
    ) {
        RotatedContainer(rotation = rotation.degrees) {
            val active = orchestrator.activeContent

            orchestrator.video.Content(modifier = Modifier.fillMaxSize())

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
    }
}
