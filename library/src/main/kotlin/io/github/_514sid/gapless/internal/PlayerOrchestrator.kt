package io.github._514sid.gapless.internal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github._514sid.gapless.GaplessVideoConfig
import io.github._514sid.gapless.GaplessVideoStrategy
import io.github._514sid.gapless.GaplessWebConfig
import io.github._514sid.gapless.internal.image.ImageStateMachine
import io.github._514sid.gapless.internal.video.DualVideoStateMachine
import io.github._514sid.gapless.internal.video.VideoEngine
import io.github._514sid.gapless.internal.video.VideoStateMachine
import io.github._514sid.gapless.internal.web.WebStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

internal sealed interface PlayerCommand {
    data class Prepare(val item: PlaybackItem) : PlayerCommand
    data class Play(val item: PlaybackItem) : PlayerCommand
}

internal enum class ActiveContent { NONE, VIDEO, IMAGE, WEB }

/**
 * Routes [PlayerCommand]s to the per-media state machines.
 *
 * Commands are consumed from a single [Flow] on one coroutine, so prepare/play are processed
 * strictly in order and never overlap. The [VideoEngine] is chosen from
 * [GaplessVideoConfig.strategy] and lives for the whole lifetime; switching to image or web playback
 * clears its media so the decoder is released, but the engine is reused for the next video.
 */
internal class PlayerOrchestrator(
    context: Context,
    scope: CoroutineScope,
    commands: Flow<PlayerCommand>,
    videoConfig: GaplessVideoConfig = GaplessVideoConfig(),
    webConfig: GaplessWebConfig = GaplessWebConfig(),
) {

    val video: VideoEngine = when (videoConfig.strategy) {
        GaplessVideoStrategy.DUAL_INSTANCE -> DualVideoStateMachine(context, videoConfig)
        GaplessVideoStrategy.SINGLE_INSTANCE_EXPERIMENTAL -> VideoStateMachine(context, videoConfig)
    }
    val image = ImageStateMachine(context, scope)
    val web = WebStateMachine(context, scope, webConfig)

    var activeContent by mutableStateOf(ActiveContent.NONE)
        private set

    private var lastPreparedContent: ActiveContent = ActiveContent.NONE

    private var preparedPlaybackId: UUID? = null

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
        if (preparedPlaybackId == item.playbackId) return
        preparedPlaybackId = item.playbackId

        when (item) {
            is PlaybackItem.Video -> {
                if (activeContent != ActiveContent.IMAGE) image.clear()
                if (activeContent != ActiveContent.WEB) web.clear()
                video.prepare(item)
                lastPreparedContent = ActiveContent.VIDEO
            }
            is PlaybackItem.Image -> {
                if (activeContent != ActiveContent.WEB) web.clear()
                if (activeContent != ActiveContent.VIDEO) video.clear()
                image.prepare(item)
                lastPreparedContent = ActiveContent.IMAGE
            }
            is PlaybackItem.Web -> {
                if (activeContent != ActiveContent.IMAGE) image.clear()
                if (activeContent != ActiveContent.VIDEO) video.clear()
                web.prepare(item)
                lastPreparedContent = ActiveContent.WEB
            }
        }
    }

    private fun play(item: PlaybackItem) {
        if (preparedPlaybackId == item.playbackId) preparedPlaybackId = null

        val previous = activeContent

        when (item) {
            is PlaybackItem.Video -> {
                if (!video.isNextReady) reportMissed(item)
                video.play(item)
                activeContent = ActiveContent.VIDEO
            }
            is PlaybackItem.Image -> {
                if (!image.isPrepareComplete) reportMissed(item)
                image.play(item)
                activeContent = ActiveContent.IMAGE
            }
            is PlaybackItem.Web -> {
                if (!web.pageCommitted) reportMissed(item)
                web.play(item)
                activeContent = ActiveContent.WEB
            }
        }

        if (previous != activeContent) cleanup(previous)
    }

    private fun cleanup(content: ActiveContent) {
        when (content) {
            ActiveContent.VIDEO -> video.clear()
            ActiveContent.IMAGE -> image.clear()
            ActiveContent.WEB   -> web.clear()
            ActiveContent.NONE  -> Unit
        }
    }

    private fun reportMissed(item: PlaybackItem) {
        onPreloadMissed?.invoke(item.assetId, System.currentTimeMillis() - item.preparedAt)
    }

    fun release() {
        video.release()
        web.release()
        image.clear()
    }
}