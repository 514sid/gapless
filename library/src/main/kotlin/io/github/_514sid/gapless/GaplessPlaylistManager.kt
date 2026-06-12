package io.github._514sid.gapless

import io.github._514sid.gapless.internal.PlaybackItem
import io.github._514sid.gapless.internal.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import java.util.UUID

class GaplessPlaylistManager(
    private val scope: CoroutineScope,
    val preloadMs: Long = 2_000L,
) {
    internal val controller = PlayerController()

    private val _events = MutableSharedFlow<GaplessEvent>(replay = 1, extraBufferCapacity = 16)
    val events: SharedFlow<GaplessEvent> = _events.asSharedFlow()

    private val _currentState = MutableStateFlow<GaplessPlaybackState?>(null)
    val currentState: StateFlow<GaplessPlaybackState?> = _currentState.asStateFlow()

    private var currentAsset: GaplessAsset? = null
    private var currentPlaybackItem: PlaybackItem? = null
    private var pendingAsset: GaplessAsset? = null
    private var pendingPlaybackItem: PlaybackItem? = null

    private var activeJob: Job? = null

    internal var isNextReadyProvider: (() -> Boolean)? = null

    internal fun onPreloadMissed(assetId: String, elapsedMs: Long) {
        val asset = pendingAsset?.takeIf { it.id == assetId } ?: return
        _events.tryEmit(GaplessEvent.PreloadMissed(asset, preloadMs, elapsedMs))
    }

    internal fun onPlaybackError(message: String) {
        val item = currentPlaybackItem ?: return
        val asset = currentAsset ?: return
        _events.tryEmit(GaplessEvent.PlaybackError(asset, item.playbackId, message))
        activeJob?.cancel()
        activeJob = null
        _currentState.value = null
    }

    private fun validateAsset(asset: GaplessAsset) {
        require(asset.id.isNotBlank()) { "Asset with uri \"${asset.uri}\" has a blank id" }
        require(asset.uri.isNotBlank()) { "Asset \"${asset.id}\" has a blank uri" }
    }

    fun start(asset: GaplessAsset) {
        validateAsset(asset)
        activeJob?.cancel()

        val item = toPlaybackItem(asset)
        pendingAsset = asset
        pendingPlaybackItem = item
        currentAsset = asset
        currentPlaybackItem = item

        item.preparedAt = System.currentTimeMillis()
        controller.prepare(item)
        _events.tryEmit(GaplessEvent.Preloading(asset))

        activeJob = scope.launch {
            delay(preloadMs)
            if (!isActive) return@launch
            controller.play(item)
            pendingAsset = null
            pendingPlaybackItem = null
            item.startedAt = System.currentTimeMillis()
            emitStarted(asset, item.playbackId, item.startedAt)
        }
    }

    fun prepareNext(asset: GaplessAsset) {
        validateAsset(asset)

        val item = toPlaybackItem(asset)
        item.preparedAt = System.currentTimeMillis()
        pendingAsset = asset
        pendingPlaybackItem = item

        controller.prepare(item)
        _events.tryEmit(GaplessEvent.Preloading(asset))
    }

    fun play(asset: GaplessAsset) {
        validateAsset(asset)

        val alreadyPrepared = pendingAsset?.id == asset.id && pendingPlaybackItem != null
        val existingItem = if (alreadyPrepared) pendingPlaybackItem else null

        activeJob?.cancel()

        activeJob = scope.launch {
            val nextItem = existingItem ?: run {
                val item = toPlaybackItem(asset)
                item.preparedAt = System.currentTimeMillis()
                pendingAsset = asset
                pendingPlaybackItem = item
                controller.prepare(item)
                _events.tryEmit(GaplessEvent.Preloading(asset))
                item
            }

            if (!alreadyPrepared) {
                while (currentCoroutineContext().isActive) {
                    if (isNextReadyProvider?.invoke() == true) break
                    delay(50)
                }
            }

            if (!currentCoroutineContext().isActive) return@launch

            val prevItem = currentPlaybackItem
            val prevAsset = currentAsset

            controller.play(nextItem)

            if (prevItem != null && prevAsset != null) {
                _events.tryEmit(GaplessEvent.Ended(prevAsset, prevItem.playbackId))
            }

            nextItem.startedAt = System.currentTimeMillis()
            currentAsset = asset
            pendingAsset = null
            pendingPlaybackItem = null
            currentPlaybackItem = nextItem
            emitStarted(asset, nextItem.playbackId, nextItem.startedAt)
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        currentAsset = null
        currentPlaybackItem = null
        pendingAsset = null
        pendingPlaybackItem = null
        _currentState.value = null
    }

    private fun emitStarted(asset: GaplessAsset, playbackId: UUID, startedAt: Long) {
        _currentState.value = GaplessPlaybackState(asset, playbackId, startedAt)
        _events.tryEmit(GaplessEvent.Started(asset, playbackId))
    }

    private fun toPlaybackItem(g: GaplessAsset): PlaybackItem {
        val item = when {
            g.isVideo -> PlaybackItem.Video(
                uri = g.uri.toUri(),
                width = g.width ?: 1920,
                height = g.height ?: 1080,
                volume = g.volume,
            )
            g.isImage -> PlaybackItem.Image(
                model = g.uri,
                width = g.width ?: 1920,
                height = g.height ?: 1080
            )
            else -> PlaybackItem.Web(url = g.uri)
        }
        item.assetId = g.id
        return item
    }
}
