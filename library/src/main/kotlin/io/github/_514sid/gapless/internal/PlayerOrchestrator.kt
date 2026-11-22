package io.github._514sid.gapless.internal

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github._514sid.gapless.GaplessVideoConfig
import io.github._514sid.gapless.GaplessWebConfig
import io.github._514sid.gapless.internal.image.ImageStateMachine
import io.github._514sid.gapless.internal.video.DualVideoStateMachine
import io.github._514sid.gapless.internal.web.WebStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * strictly in order and never overlap. The video engine lives for the whole lifetime; switching to image or web playback
 * clears its media so the decoder is released, but the engine is reused for the next video.
 */
internal class PlayerOrchestrator(
    context: Context,
    scope: CoroutineScope,
    commands: Flow<PlayerCommand>,
    videoConfig: GaplessVideoConfig = GaplessVideoConfig(),
    webConfig: GaplessWebConfig = GaplessWebConfig(),
) {

    val video = DualVideoStateMachine(context, videoConfig)
    val image = ImageStateMachine(context, scope)
    val web = WebStateMachine(context, scope, webConfig)

    var activeContent by mutableStateOf(ActiveContent.NONE)
        private set

    private var lastPreparedContent: ActiveContent = ActiveContent.NONE

    private var preparedPlaybackId: UUID? = null

    /**
     * Whether the item with [playbackId] has been staged and can render. Returns false until the
     * matching Prepare command has been processed, so a caller polling right after sending Prepare
     * never sees the previous item's readiness. Main thread only.
     */
    fun isReady(playbackId: UUID): Boolean {
        if (preparedPlaybackId != playbackId) return false
        return when (lastPreparedContent) {
            ActiveContent.IMAGE -> image.isPrepareComplete
            ActiveContent.WEB   -> web.pageCommitted
            ActiveContent.VIDEO -> video.isNextReady
            ActiveContent.NONE  -> false
        }
    }

    private val commandJob: Job

    var onError: ((String) -> Unit)? = null
    var onPreloadError: ((assetId: String, message: String) -> Unit)? = null
    var onPreloadMissed: ((assetId: String, elapsedMs: Long) -> Unit)? = null

    /**
     * An item is on screen and its timeline is running. startedAt is the wall-clock time its
     * position was zero, or null for now.
     */
    var onShown: ((playbackId: UUID, startedAt: Long?) -> Unit)? = null

    init {
        video.onErrorCallback = { message -> onError?.invoke(message) }
        video.onPreloadErrorCallback = { assetId, message -> onPreloadError?.invoke(assetId, message) }
        web.onError = { message -> onError?.invoke(message) }
        web.onPreloadError = { assetId, message -> onPreloadError?.invoke(assetId, message) }
        image.onError = { assetId, message -> onPreloadError?.invoke(assetId, message) }
        image.onPlaybackError = { message -> onError?.invoke(message) }
        video.onStartedCallback = { playbackId, startedAt -> onShown?.invoke(playbackId, startedAt) }
        image.onShown = { playbackId -> onShown?.invoke(playbackId, null) }
        web.onShown = { playbackId -> onShown?.invoke(playbackId, null) }

        commandJob = scope.launch {
            commands.collect { command -> dispatch(command) }
        }
    }

    /**
     * Runs one command. A failure here would otherwise escape into the composition's scope and
     * crash the app, or end the collector and leave the player deaf to every later command, so it
     * is reported as an error for the item instead.
     */
    private fun dispatch(command: PlayerCommand) {
        try {
            when (command) {
                is PlayerCommand.Prepare -> prepare(command.item)
                is PlayerCommand.Play    -> play(command.item)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure(command, e)
        } catch (e: OutOfMemoryError) {
            reportFailure(command, e)
        }
    }

    private fun reportFailure(command: PlayerCommand, error: Throwable) {
        val message = "Player failed: ${error.message ?: error.javaClass.simpleName}"
        Log.e(TAG, message, error)
        when (command) {
            is PlayerCommand.Prepare -> {
                if (preparedPlaybackId == command.item.playbackId) preparedPlaybackId = null
                onPreloadError?.invoke(command.item.assetId, message)
            }
            is PlayerCommand.Play -> onError?.invoke(message)
        }
    }

    /** The size content is shown at, after rotation. */
    fun updateContainerSize(width: Int, height: Int) {
        image.updateContainerSize(width, height)
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
        // Stop consuming the controller's commands; a replacement orchestrator takes over.
        commandJob.cancel()
        video.release()
        web.release()
        image.clear()
    }

    private companion object {
        const val TAG = "GaplessPlayer"
    }
}