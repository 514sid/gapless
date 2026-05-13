package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessPlayerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Duration

/**
 * Represents the current playback session.
 * @property asset The actual media metadata.
 * @property generation A unique identifier for this specific playback instance.
 * Increasing this value signals the UI to reset players/progress, even if the [asset] is the same.
 */
data class PlaybackState(
    val asset: GaplessAsset,
    val generation: Long
)

internal class PlaylistManager {
    private val _currentAsset = MutableStateFlow<PlaybackState?>(null)
    val currentAsset: StateFlow<PlaybackState?> = _currentAsset.asStateFlow()

    private val _preloadAsset = MutableStateFlow<GaplessAsset?>(null)
    val preloadAsset: StateFlow<GaplessAsset?> = _preloadAsset.asStateFlow()

    private val _playlist = MutableStateFlow<List<GaplessAsset>>(emptyList())

    private var currentElapsedMs = 0L
    private var generation = 0L

    private var shuffleEnabled = false
    private var sourceAssets: List<GaplessAsset> = emptyList()
    private var config = GaplessPlayerConfig()

    fun update(assets: List<GaplessAsset>, shuffle: Boolean, config: GaplessPlayerConfig) {
        this.config = config
        this.shuffleEnabled = shuffle
        this.sourceAssets = assets
        refreshPlaylist()
    }

    fun tick() {
        if (_playlist.value.isEmpty()) return

        val current = _currentAsset.value?.asset
        if (current == null || !current.isActiveNow()) {
            advance()
            return
        }

        val newElapsed = currentElapsedMs + config.tickIntervalMs
        if (newElapsed >= current.durationMs) {
            advance()
        } else {
            currentElapsedMs = newElapsed
            schedulePreload(current)
        }
    }

    fun advance() {
        val list = _playlist.value
        if (list.isEmpty()) {
            resetState()
            return
        }

        val currentAsset = _currentAsset.value?.asset
        val next = findNextActive(list, currentAsset?.id)

        if (next != null) {
            val currentIndex = list.indexOfFirst { it.id == currentAsset?.id }.coerceAtLeast(0)
            val nextIndex = list.indexOfFirst { it.id == next.id }

            _currentAsset.value = PlaybackState(next, ++generation)
            currentElapsedMs = 0

            schedulePreload(next)

            if (nextIndex <= currentIndex && shuffleEnabled) {
                refreshPlaylist()
            }
        } else {
            resetState()
        }
    }

    private fun schedulePreload(current: GaplessAsset) {
        val remaining = current.durationMs - currentElapsedMs
        _preloadAsset.value = if (remaining < config.preloadThresholdMs) {
            findNextActive(_playlist.value, current.id)
        } else {
            null
        }
    }

    private fun refreshPlaylist() {
        val playingAsset = _currentAsset.value?.asset

        val newList = prepare(sourceAssets, shuffleEnabled, playingAsset?.id)
        _playlist.value = newList

        val currentStillValid = playingAsset != null &&
                newList.any { it.id == playingAsset.id } &&
                newList.first { it.id == playingAsset.id }.isActiveNow()

        if (!currentStillValid) {
            val next = newList.find { it.isActiveNow() }
            currentElapsedMs = 0

            if (next != null) {
                _currentAsset.value = PlaybackState(next, ++generation)
            } else {
                _preloadAsset.value = null
                _currentAsset.value = null
            }
        }
    }

    private fun resetState() {
        _currentAsset.value = null
        _preloadAsset.value = null
        currentElapsedMs = 0
        generation = 0
    }

    private fun prepare(source: List<GaplessAsset>, shuffle: Boolean, lastId: String?): List<GaplessAsset> {
        if (source.isEmpty()) return emptyList()
        if (!shuffle) return source

        val shuffled = source.shuffled()

        return if (lastId != null && shuffled.isNotEmpty() && shuffled.first().id == lastId) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }

    private fun findNextActive(playlist: List<GaplessAsset>, currentId: String?): GaplessAsset? {
        if (playlist.isEmpty()) return null

        val currentIndex = playlist.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        val shiftedClock = Clock.offset(
            Clock.systemDefaultZone(),
            Duration.ofMillis(config.preloadThresholdMs)
        )

        for (i in 1..playlist.size) {
            val candidate = playlist[(currentIndex + i) % playlist.size]
            if (candidate.isActiveAt(shiftedClock)) return candidate
        }

        return null
    }
}