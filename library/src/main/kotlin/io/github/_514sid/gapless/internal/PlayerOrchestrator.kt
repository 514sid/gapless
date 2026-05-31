package io.github._514sid.gapless.internal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class ActiveContent { NONE, VIDEO, IMAGE, WEB }

internal class PlayerOrchestrator(
    context: Context,
    scope: CoroutineScope,
    val controller: PlayerController = PlayerController()
) {

    val video = VideoStateMachine(context)
    val image = ImageStateMachine(context, scope)
    val web = WebStateMachine(context, scope)

    var activeContent by mutableStateOf(ActiveContent.NONE)
        private set

    var onError: ((String) -> Unit)? = null

    init {
        video.onError = { message -> onError?.invoke(message) }

        scope.launch {
            controller.commands.collect { command ->
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
            }
            is PlaybackItem.Image -> {
                video.cancelPrepare()
                web.cancelPrepare()
                image.prepare(item)
            }
            is PlaybackItem.Web -> {
                video.cancelPrepare()
                image.cancelPrepare()
                web.prepare(item)
            }
        }
    }

    private fun play(item: PlaybackItem) {
        when (item) {
            is PlaybackItem.Video -> {
                video.play(item)
                image.clear()
                web.clear()
                activeContent = ActiveContent.VIDEO
            }
            is PlaybackItem.Image -> {
                image.play(item)
                video.clear()
                web.clear()
                activeContent = ActiveContent.IMAGE
            }
            is PlaybackItem.Web -> {
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
