package io.github._514sid.gapless.internal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessEvent
import io.github._514sid.gapless.GaplessPlayerConfig
import io.github._514sid.gapless.ui.MediaSlotData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class GaplessViewModel(app: Application) : AndroidViewModel(app) {
    private val _events = MutableSharedFlow<GaplessEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GaplessEvent> = _events.asSharedFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val playlistManager = PlaylistManager()

    val currentSlot: StateFlow<MediaSlotData?> = playlistManager.currentSlot
    val preloadSlot: StateFlow<MediaSlotData?> = playlistManager.preloadSlot

    private var currentConfig = GaplessPlayerConfig()
    private var job: Job? = null

    init {
        var lastSlot: MediaSlotData? = null

        currentSlot
            .onEach { state ->
                val prev = lastSlot

                if (state?.playbackId != prev?.playbackId) {
                    prev?.asset?.let { asset ->
                        _events.tryEmit(
                            GaplessEvent.Finished(asset, prev.playbackId)
                        )
                    }

                    if (state?.asset != null) {
                        _events.tryEmit(GaplessEvent.Started(state.asset, state.playbackId))
                    } else {
                        _events.tryEmit(GaplessEvent.Idle)
                    }
                }

                lastSlot = state
            }
            .launchIn(viewModelScope)

        preloadSlot
            .distinctUntilChanged { old, new -> old?.asset?.id == new?.asset?.id && old?.playbackId == new?.playbackId }
            .onEach { state ->
                _events.tryEmit(GaplessEvent.Preloading(state?.asset))
            }
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
        val isRelevant = asset.id == currentSlot.value?.asset?.id ||
                asset.id == preloadSlot.value?.asset?.id

        if (!isRelevant) return

        _events.tryEmit(GaplessEvent.PlaybackError(asset, message))

        if (currentSlot.value?.asset?.id == asset.id) {
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