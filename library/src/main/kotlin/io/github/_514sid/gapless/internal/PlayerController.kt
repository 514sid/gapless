package io.github._514sid.gapless.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal sealed interface PlayerCommand {
    data class Prepare(val item: PlaybackItem) : PlayerCommand
    data class Play(val item: PlaybackItem) : PlayerCommand
}

internal class PlayerController {
    private val channel = Channel<PlayerCommand>(Channel.UNLIMITED)
    val commands = channel.receiveAsFlow()

    fun prepare(item: PlaybackItem) = channel.trySend(PlayerCommand.Prepare(item))
    fun play(item: PlaybackItem) = channel.trySend(PlayerCommand.Play(item))
}
