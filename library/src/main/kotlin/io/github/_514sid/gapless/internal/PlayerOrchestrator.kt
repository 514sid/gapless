package io.github._514sid.gapless.internal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github._514sid.gapless.GaplessVideoConfig
import io.github._514sid.gapless.GaplessWebConfig
import io.github._514sid.gapless.internal.image.ImageStateMachine
import io.github._514sid.gapless.internal.video.VideoStateMachine
import io.github._514sid.gapless.internal.web.WebStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal sealed interface PlayerCommand {
    data class Prepare(val item: PlaybackItem) : PlayerCommand
    data class Play(val item: PlaybackItem) : PlayerCommand
}

internal enum class ActiveContent { NONE, VIDEO, IMAGE, WEB }

internal class PlayerOrchestrator(
    context: Context,
    scope: CoroutineScope,
    commands: Flow<PlayerCommand>,
    videoConfig: GaplessVideoConfig = GaplessVideoConfig(),
    webConfig: GaplessWebConfig = GaplessWebConfig(),
) {

    val video = VideoStateMachine(context, videoConfig)
    val image = ImageStateMachine(context, scope)
    val web = WebStateMachine(context, scope, webConfig)

    var activeContent by mutableStateOf(ActiveContent.NONE)
        private set

    private var lastPreparedContent: ActiveContent = ActiveContent.NONE

    val isNextReady: Boolean
        get() = when (lastPreparedContent) {
            ActiveContent.IMAGE -> image.isPrepareComplete
            ActiveContent.WEB   -> web.pageCommitted
            ActiveContent.VIDEO -> video.isNextReady
            ActiveContent.NONE  -> true
        }

    var onError: ((String) -> Unit)? = null
    var onPreloadError: ((assetId: String, message: String) -> Unit)? = null
    var onPreloadMissed: ((assetId: String, elapsedMs: Long) -> Unit)? = null

    init {
        video.onErrorCallback = { message -> onError?.invoke(message) }
        video.onPreloadErrorCallback = { assetId, message -> onPreloadError?.invoke(assetId, message) }
        web.onPreloadError = { assetId, message -> onPreloadError?.invoke(assetId, message) }
        image.onError = { assetId, message -> onPreloadError?.invoke(assetId, message) }

        scope.launch {
            commands.collect { command ->
                when (command) {
                    is PlayerCommand.Prepare -> prepare(command.item)
                    is PlayerCommand.Play    -> play(command.item)
                }
            }
        }
    }

    fun updateContainerSize(width: Int, height: Int) {
        image.containerWidth = width
        image.containerHeight = height
    }

    private fun prepare(item: PlaybackItem) {
        when (item) {
            is PlaybackItem.Video -> {
                image.cancelPrepare()
                web.cancelPrepare()
                video.prepare(item)
                lastPreparedContent = ActiveContent.VIDEO
            }
            is PlaybackItem.Image -> {
                video.cancelPrepare()
                web.cancelPrepare()
                image.prepare(item)
                lastPreparedContent = ActiveContent.IMAGE
            }
            is PlaybackItem.Web -> {
                video.cancelPrepare()
                image.cancelPrepare()
                web.prepare(item)
                lastPreparedContent = ActiveContent.WEB
            }
        }
    }

    private fun play(item: PlaybackItem) {
        when (item) {
            is PlaybackItem.Video -> {
                if (!video.isNextReady) onPreloadMissed?.invoke(item.assetId, System.currentTimeMillis() - item.preparedAt)
                video.play(item)
                image.clear()
                web.clear()
                activeContent = ActiveContent.VIDEO
            }
            is PlaybackItem.Image -> {
                if (!image.isPrepareComplete) onPreloadMissed?.invoke(item.assetId, System.currentTimeMillis() - item.preparedAt)
                image.play(item)
                video.clear()
                web.clear()
                activeContent = ActiveContent.IMAGE
            }
            is PlaybackItem.Web -> {
                if (!web.pageCommitted) onPreloadMissed?.invoke(item.assetId, System.currentTimeMillis() - item.preparedAt)
                web.play(item)
                video.clear()
                image.clear()
                activeContent = ActiveContent.WEB
            }
        }
    }

    fun release() {
        video.release()
        web.release()
    }
}
