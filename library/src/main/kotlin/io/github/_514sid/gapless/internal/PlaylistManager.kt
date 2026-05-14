package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessPlayerConfig
import io.github._514sid.gapless.ui.MediaSlotData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Duration

internal class PlaylistManager {

    private val _currentSlot = MutableStateFlow<MediaSlotData?>(null)
    val currentSlot: StateFlow<MediaSlotData?> = _currentSlot.asStateFlow()

    private val _preloadSlot = MutableStateFlow<MediaSlotData?>(null)
    val preloadSlot: StateFlow<MediaSlotData?> = _preloadSlot.asStateFlow()

    private val _playlist = MutableStateFlow<List<GaplessAsset>>(emptyList())

    private var currentElapsedMs = 0L

    private var shuffleEnabled = false
    private var sourceAssets: List<GaplessAsset> = emptyList()
    private var config = GaplessPlayerConfig()

    fun update(
        assets: List<GaplessAsset>,
        shuffle: Boolean,
        config: GaplessPlayerConfig
    ) {
        this.config = config
        this.shuffleEnabled = shuffle
        this.sourceAssets = assets
        refreshPlaylist()
    }

    fun tick() {
        if (_playlist.value.isEmpty()) return

        val current = _currentSlot.value?.asset
        if (current == null || !current.isActiveNow()) {
            advance()
            return
        }

        val newElapsed = currentElapsedMs + config.tickIntervalMs

        if (newElapsed >= current.snappedDuration(config.tickIntervalMs)) {
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

        val currentAsset = _currentSlot.value?.asset
        val next = findNextActive(list, currentAsset?.id)

        if (next != null) {
            val currentIndex = list.indexOfFirst { it.id == currentAsset?.id }.coerceAtLeast(0)
            val nextIndex = list.indexOfFirst { it.id == next.id }

            val preload = _preloadSlot.value
            if (preload != null && preload.asset?.id == next.id) {
                _currentSlot.value = preload.copy(isActive = true)
                _preloadSlot.value = null
            } else {
                _currentSlot.value = newSlot(next, isActive = true)
            }

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
        val remaining = current.snappedDuration(config.tickIntervalMs) - currentElapsedMs

        val next = if (remaining < config.preloadThresholdMs) {
            findNextActive(_playlist.value, current.id)
        } else null

        if (next != null) {
            if (_preloadSlot.value?.asset?.id != next.id) {
                _preloadSlot.value = newSlot(next, isActive = false)
            }
        } else {
            _preloadSlot.value = null
        }
    }

    private fun refreshPlaylist() {
        val playingAsset = _currentSlot.value?.asset

        val newList = prepare(
            sourceAssets,
            shuffleEnabled,
            playingAsset?.id
        )

        _playlist.value = newList

        val currentStillValid =
            playingAsset != null &&
                    newList.any { it.id == playingAsset.id } &&
                    newList.first { it.id == playingAsset.id }.isActiveNow()

        if (!currentStillValid) {

            val next = newList.find { it.isActiveNow() }

            currentElapsedMs = 0

            if (next != null) {
                if (_currentSlot.value?.asset?.id != next.id) {
                    _currentSlot.value = newSlot(next, isActive = true)
                }
            } else {
                _preloadSlot.value = null
                _currentSlot.value = null
            }
        }
    }

    private fun resetState() {
        _currentSlot.value = null
        _preloadSlot.value = null
        currentElapsedMs = 0
    }

    private fun newSlot(
        asset: GaplessAsset?,
        isActive: Boolean
    ): MediaSlotData {
        return MediaSlotData(
            asset = asset,
            isActive = isActive
        )
    }

    private fun prepare(
        source: List<GaplessAsset>,
        shuffle: Boolean,
        lastId: String?
    ): List<GaplessAsset> {
        if (source.isEmpty()) return emptyList()
        if (!shuffle) return source

        val shuffled = source.shuffled()

        return if (
            lastId != null &&
            shuffled.isNotEmpty() &&
            shuffled.first().id == lastId
        ) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }

    private fun GaplessAsset.snappedDuration(tickMs: Long): Long {
        val snapped = (durationMs / tickMs) * tickMs
        return maxOf(snapped, tickMs)
    }

    private fun findNextActive(
        playlist: List<GaplessAsset>,
        currentId: String?
    ): GaplessAsset? {
        if (playlist.isEmpty()) return null

        val currentIndex = playlist.indexOfFirst { it.id == currentId }
            .coerceAtLeast(0)

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
