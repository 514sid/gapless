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
import java.util.UUID

/**
 * Renders whatever [controller] is playing.
 *
 * The players behind it (ExoPlayer, WebView, image slots) live as long as this composable. When it
 * leaves the composition, or when [videoConfig] or [webConfig] change and the players are rebuilt,
 * it calls [GaplessController.stop]; call [GaplessController.start] again to resume.
 *
 * Use one [GaplessPlayer] per controller. Two players on the same controller would split its
 * commands between them.
 */
@Composable
public fun GaplessPlayer(
    controller: GaplessController,
    modifier: Modifier = Modifier,
    rotation: GaplessRotation = GaplessRotation.Deg0,
    videoConfig: GaplessVideoConfig = GaplessVideoConfig(),
    webConfig: GaplessWebConfig = GaplessWebConfig(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val orchestrator = remember(controller, context, videoConfig, webConfig) {
        PlayerOrchestrator(context, scope, controller.commands, videoConfig, webConfig).also { o ->
            o.onError = { message -> controller.onPlaybackError(message) }
            o.onPreloadError = { assetId, message -> controller.onPreloadError(assetId, message) }
            o.onPreloadMissed = { assetId, elapsedMs -> controller.onPreloadMissed(assetId, elapsedMs) }
        }
    }

    DisposableEffect(orchestrator) {
        val isReady: (UUID) -> Boolean = orchestrator::isReady
        controller.isReadyProvider = isReady
        onDispose {
            // The controller can outlive this composable (e.g. in a ViewModel); don't let it keep
            // the released orchestrator, and through it the Context and WebViews, reachable.
            if (controller.isReadyProvider === isReady) controller.isReadyProvider = null
            orchestrator.release()
            controller.stop()
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
