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

    private val playlistManager = PlaylistManager()

    val currentAsset: StateFlow<PlaybackState?> = playlistManager.currentAsset
    val preloadAsset: StateFlow<GaplessAsset?> = playlistManager.preloadAsset

    private var currentConfig = GaplessPlayerConfig()
    private var job: Job? = null

    init {
        currentAsset
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { state ->
                _events.tryEmit(GaplessEvent.NowPlaying(state.asset))
            }
            .launchIn(viewModelScope)

        preloadAsset
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { _events.tryEmit(GaplessEvent.Preloading(it)) }
            .launchIn(viewModelScope)
    }

    fun update(assets: List<GaplessAsset>, shuffle: Boolean, config: GaplessPlayerConfig) {
        val oldConfig = currentConfig
        currentConfig = config
        playlistManager.update(assets, shuffle, config)
        _isInitialized.value = true

        if (oldConfig.tickIntervalMs != config.tickIntervalMs || job == null) {
            startPlaybackLoop()
        }
    }

    fun handlePlaybackError(asset: GaplessAsset, message: String) {
        val isRelevant = asset.id == currentAsset.value?.asset?.id ||
                asset.id == preloadAsset.value?.id

        if (!isRelevant) return

        _events.tryEmit(GaplessEvent.PlaybackError(asset, message))

        if (currentAsset.value?.asset?.id == asset.id) {
            playlistManager.advance()
        }
    }

    private fun startPlaybackLoop() {
        job?.cancel()
        job = viewModelScope.launch {
            while (isActive) {
                delay(currentConfig.tickIntervalMs)
                playlistManager.tick()
            }
        }
    }
}