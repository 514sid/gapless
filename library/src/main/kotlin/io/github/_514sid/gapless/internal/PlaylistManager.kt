package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessPlayerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlaylistManager(
    private val onPlaylistEmpty: () -> Unit
) {
    private val _currentAsset = MutableStateFlow<GaplessAsset?>(null)
    val currentAsset: StateFlow<GaplessAsset?> = _currentAsset.asStateFlow()

    private val _preloadAsset = MutableStateFlow<GaplessAsset?>(null)
    val preloadAsset: StateFlow<GaplessAsset?> = _preloadAsset.asStateFlow()

    private val _playlist = MutableStateFlow<List<GaplessAsset>>(emptyList())

    private var currentElapsedMs = 0L

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

        val current = _currentAsset.value
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
        if (list.isEmpty()) { resetState(); return }

        val currentId = _currentAsset.value?.id
        val currentIndex = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val next = findNextActive(list, currentId)

        if (next != null) {
            val nextIndex = list.indexOfFirst { it.id == next.id }
            if (nextIndex <= currentIndex && shuffleEnabled) {
                refreshPlaylist()
                return
            }
            _currentAsset.value = next
            currentElapsedMs = 0
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
        val playingId = _currentAsset.value?.id
        val newList = prepare(sourceAssets, shuffleEnabled, playingId)
        _playlist.value = newList

        val currentStillValid = playingId != null &&
                newList.any { it.id == playingId } &&
                newList.first { it.id == playingId }.isActiveNow()

        if (currentStillValid) {
            _currentAsset.value = newList.first { it.id == playingId }
            schedulePreload(_currentAsset.value!!)
        } else {
            _currentAsset.value = newList.find { it.isActiveNow() }
            currentElapsedMs = 0

            val current = _currentAsset.value
            if (current != null) {
                schedulePreload(current)
            } else {
                _preloadAsset.value = null
            }
        }

        if (newList.isEmpty()) onPlaylistEmpty()
    }

    private fun resetState() {
        _currentAsset.value = null
        _preloadAsset.value = null
        currentElapsedMs = 0
        onPlaylistEmpty()
    }

    private fun prepare(source: List<GaplessAsset>, shuffle: Boolean, lastId: String?): List<GaplessAsset> {
        if (source.isEmpty()) return emptyList()
        if (source.size == 1) {
            val only = source.first()
            return listOf(only, only.clone())
        }
        if (!shuffle) return source

        val shuffled = source.shuffled()
        val lastNormalized = lastId?.removeSuffix(GaplessAsset.CLONE_SUFFIX)

        return if (lastNormalized != null && shuffled.first().normalizedId == lastNormalized) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }

    private fun findNextActive(playlist: List<GaplessAsset>, currentId: String?): GaplessAsset? {
        if (playlist.isEmpty()) return null
        val currentIndex = playlist.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        for (i in 1..playlist.size) {
            val candidate = playlist[(currentIndex + i) % playlist.size]
            if (candidate.isActiveNow()) return candidate
        }
        return null
    }
}