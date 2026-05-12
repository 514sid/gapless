package com.514sid.gapless.internal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.514sid.gapless.GaplessAsset
import com.514sid.gapless.GaplessEvent
import com.514sid.gapless.GaplessPlayerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class GaplessViewModel(app: Application) : AndroidViewModel(app) {

    // ── Public surface ────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<GaplessEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GaplessEvent> = _events.asSharedFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────────────

    private val _currentAsset = MutableStateFlow<GaplessAsset?>(null)
    val currentAsset: StateFlow<GaplessAsset?> = _currentAsset.asStateFlow()

    private val _preloadAsset = MutableStateFlow<GaplessAsset?>(null)
    val preloadAsset: StateFlow<GaplessAsset?> = _preloadAsset.asStateFlow()
    private val _currentElapsedMs = MutableStateFlow(0L)
    private val _playlist = MutableStateFlow<List<GaplessAsset>>(emptyList())

    private var shuffleEnabled = false
    private var sourceAssets: List<GaplessAsset> = emptyList()
    private var config = GaplessPlayerConfig()

    // ── Initialization ────────────────────────────────────────────────────────

    init {
        _currentAsset
            .filterNotNull()
            .distinctUntilChanged { a, b -> a.normalizedId == b.normalizedId }
            .onEach { _events.tryEmit(GaplessEvent.NowPlaying(it)) }
            .launchIn(viewModelScope)

        _preloadAsset
            .filterNotNull()
            .distinctUntilChanged { a, b -> a.normalizedId == b.normalizedId }
            .onEach { _events.tryEmit(GaplessEvent.Preloading(it)) }
            .launchIn(viewModelScope)

        startPlaybackLoop()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by the composable whenever [assets] or [shuffle] changes.
     * Rebuilds the playlist while preserving the currently-playing position when possible.
     */
    fun update(assets: List<GaplessAsset>, shuffle: Boolean, config: GaplessPlayerConfig) {
        this.config = config
        shuffleEnabled = shuffle
        sourceAssets = assets
        refreshPlaylist()
        _isInitialized.value = true
    }

    /** Called by the UI layer when a video, image, or web asset fails to render. */
    fun handlePlaybackError(asset: GaplessAsset, message: String) {
        val isRelevant = asset.id == _currentAsset.value?.id ||
                asset.id == _preloadAsset.value?.id
        if (!isRelevant) return

        _events.tryEmit(GaplessEvent.PlaybackError(asset, message))

        if (_currentAsset.value?.id == asset.id) advance()
    }

    // ── Playback loop ─────────────────────────────────────────────────────────

    private fun startPlaybackLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(config.tickIntervalMs)
                tick()
            }
        }
    }

    private fun tick() {
        if (_playlist.value.isEmpty()) return

        val current = _currentAsset.value
        if (current == null || !current.isActiveNow()) {
            advance()
            return
        }

        val newElapsed = _currentElapsedMs.value + config.tickIntervalMs
        if (newElapsed >= current.durationMs) {
            advance()
        } else {
            _currentElapsedMs.value = newElapsed
            schedulePreload(current)
        }
    }

    private fun schedulePreload(current: GaplessAsset) {
        val remaining = current.durationMs - _currentElapsedMs.value
        _preloadAsset.value = if (remaining < config.preloadThresholdMs) {
            PlaylistManager.findNextActive(_playlist.value, current.id)
        } else {
            null
        }
    }

    private fun advance() {
        val list = _playlist.value
        if (list.isEmpty()) { resetState(); return }

        val currentId = _currentAsset.value?.id
        val currentIndex = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val next = PlaylistManager.findNextActive(list, currentId)

        if (next != null) {
            val nextIndex = list.indexOfFirst { it.id == next.id }
            // In shuffle mode, wrap-around triggers a fresh shuffle.
            if (nextIndex <= currentIndex && shuffleEnabled) {
                refreshPlaylist()
                return
            }
            _currentAsset.value = next
            _currentElapsedMs.value = 0
        } else {
            resetState()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun refreshPlaylist() {
        val playingId = _currentAsset.value?.id

        val newList = PlaylistManager.prepare(sourceAssets, shuffleEnabled, playingId)
        _playlist.value = newList

        val currentStillValid = playingId != null &&
                newList.any { it.id == playingId } &&
                newList.first { it.id == playingId }.isActiveNow()

        if (currentStillValid) {
            _currentAsset.value = newList.first { it.id == playingId }
            // Keep elapsed time so the current asset doesn't restart.
            schedulePreload(_currentAsset.value!!)
        } else {
            _currentAsset.value = newList.find { it.isActiveNow() }
            _currentElapsedMs.value = 0
            _currentAsset.value?.let { schedulePreload(it) }
        }

        if (newList.isEmpty()) _events.tryEmit(GaplessEvent.PlaylistEmpty)
    }

    private fun resetState() {
        _currentAsset.value = null
        _preloadAsset.value = null
        _currentElapsedMs.value = 0
        _events.tryEmit(GaplessEvent.PlaylistEmpty)
    }
}
