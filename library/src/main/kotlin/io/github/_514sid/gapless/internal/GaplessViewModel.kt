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
    private val _events = MutableSharedFlow<GaplessEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GaplessEvent> = _events.asSharedFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val playlistManager = PlaylistManager(
        onPlaylistEmpty = { _events.tryEmit(GaplessEvent.PlaylistEmpty) }
    )

    val currentAsset: StateFlow<GaplessAsset?> = playlistManager.currentAsset
    val preloadAsset: StateFlow<GaplessAsset?> = playlistManager.preloadAsset

    private var currentConfig = GaplessPlayerConfig()

    init {
        currentAsset
            .filterNotNull()
            .distinctUntilChanged { a, b -> a.normalizedId == b.normalizedId }
            .onEach { _events.tryEmit(GaplessEvent.NowPlaying(it)) }
            .launchIn(viewModelScope)

        preloadAsset
            .filterNotNull()
            .distinctUntilChanged { a, b -> a.normalizedId == b.normalizedId }
            .onEach { _events.tryEmit(GaplessEvent.Preloading(it)) }
            .launchIn(viewModelScope)

        startPlaybackLoop()
    }

    fun update(assets: List<GaplessAsset>, shuffle: Boolean, config: GaplessPlayerConfig) {
        currentConfig = config
        playlistManager.update(assets, shuffle, config)
        _isInitialized.value = true
    }

    fun handlePlaybackError(asset: GaplessAsset, message: String) {
        val isRelevant = asset.id == currentAsset.value?.id ||
                asset.id == preloadAsset.value?.id
        if (!isRelevant) return

        _events.tryEmit(GaplessEvent.PlaybackError(asset, message))

        if (currentAsset.value?.id == asset.id) {
            playlistManager.advance()
        }
    }

    private fun startPlaybackLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(currentConfig.tickIntervalMs)
                playlistManager.tick()
            }
        }
    }
}