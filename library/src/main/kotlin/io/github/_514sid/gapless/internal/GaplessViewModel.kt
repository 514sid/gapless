package io.github._514sid.gapless.internal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessEvent
import io.github._514sid.gapless.GaplessPlayerConfig
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

    /**
     * Rebuilds the internal playlist and resolves the playback state.
     * 
     * This function is triggered whenever the source assets change (e.g., database updates)
     * or when the shuffle toggle is flipped. Its primary goal is to perform a "hot-swap": 
     * if the currently playing asset still exists in the new list and is still allowed to play, 
     * it will keep playing without interruption. Otherwise, it resets the player to the next 
     * available active asset (or transitions to the empty state).
     */
    private fun refreshPlaylist() {
        val playingId = _currentAsset.value?.id
    
        // 1. Prepare the new playlist, applying shuffle logic if enabled.
        // We pass the playingId so the manager can keep it in the correct relative position.
        val newList = PlaylistManager.prepare(sourceAssets, shuffleEnabled, playingId)
        _playlist.value = newList
    
        // 2. Determine if we can seamlessly continue playing the current asset.
        // It must exist in the new list AND its temporal schedule must still be active.
        val currentStillValid = playingId != null &&
                newList.any { it.id == playingId } &&
                newList.first { it.id == playingId }.isActiveNow()
    
        if (currentStillValid) {
            // Hot-swap the asset instance. We grab the new instance from the list because 
            // the database might have updated its URI or duration, but we intentionally do NOT 
            // reset _currentElapsedMs so the video/image doesn't restart visually.
            _currentAsset.value = newList.first { it.id == playingId }
            schedulePreload(_currentAsset.value!!)
        } else {
            // The current asset was removed, disabled, or its time window expired.
            // Find the very first asset in the new list that is allowed to play right now.
            _currentAsset.value = newList.find { it.isActiveNow() }
            _currentElapsedMs.value = 0 // Start fresh
            
            val current = _currentAsset.value
            if (current != null) {
                // We found a valid asset, start preloading the one that comes after it.
                schedulePreload(current)
            } else {
                // Prevent the "black screen" bug. If no valid asset exists in the entire 
                // new list, we must explicitly wipe the preload slot so the UI layer knows 
                // to render the emptyState() Composable.
                _preloadAsset.value = null
            }
        }
    
        // Notify the UI layer if there is absolutely nothing to play.
        if (newList.isEmpty()) _events.tryEmit(GaplessEvent.PlaylistEmpty)
    }

    private fun resetState() {
        _currentAsset.value = null
        _preloadAsset.value = null
        _currentElapsedMs.value = 0
        _events.tryEmit(GaplessEvent.PlaylistEmpty)
    }
}
