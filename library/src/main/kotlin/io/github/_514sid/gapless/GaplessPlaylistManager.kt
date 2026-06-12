package io.github._514sid.gapless

import io.github._514sid.gapless.internal.PlaybackItem
import io.github._514sid.gapless.internal.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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

    private val nextAssetChannel = Channel<GaplessAsset>(Channel.CONFLATED)

    private var currentAsset: GaplessAsset? = null
    private var currentPlaybackItem: PlaybackItem? = null
    private var pendingAsset: GaplessAsset? = null
    private var pendingPlaybackItem: PlaybackItem? = null

    private var startJob: Job? = null
    private var preloadJob: Job? = null

    internal var naturalDurationProvider: (() -> Long?)? = null
    internal var isNextReadyProvider: (() -> Boolean)? = null

    internal fun onPreloadMissed(assetId: String, elapsedMs: Long) {
        val asset = pendingAsset?.takeIf { it.id == assetId } ?: return
        _events.tryEmit(GaplessEvent.PreloadMissed(asset, preloadMs, elapsedMs))
    }

    internal fun onPlaybackError(message: String) {
        val item = currentPlaybackItem ?: return
        val asset = currentAsset ?: return
        _events.tryEmit(GaplessEvent.PlaybackError(asset, item.playbackId, message))
        startJob?.cancel()
        preloadJob?.cancel()
        startJob = null
        preloadJob = null
        _currentState.value = null
    }

    private fun validateAsset(asset: GaplessAsset) {
        require(asset.id.isNotBlank()) { "Asset with uri \"${asset.uri}\" has a blank id" }
        require(asset.uri.isNotBlank()) { "Asset \"${asset.id}\" has a blank uri" }
        if (asset.durationMs != null) {
            require(asset.durationMs > 0) { "Asset \"${asset.id}\" has durationMs=${asset.durationMs}, must be > 0" }
        } else {
            require(asset.isVideo) { "Asset \"${asset.id}\" has durationMs=null but is not a video; natural duration is only supported for video assets" }
        }
    }

    fun start(asset: GaplessAsset) {
        validateAsset(asset)
        nextAssetChannel.tryReceive()

        startJob?.cancel()
        preloadJob?.cancel()

        startJob = scope.launch {
            val item = toPlaybackItem(asset)
            pendingAsset = asset
            pendingPlaybackItem = item
            currentAsset = asset
            currentPlaybackItem = item

            item.preparedAt = System.currentTimeMillis()
            controller.prepare(item)
            _events.tryEmit(GaplessEvent.Preloading(asset))
            delay(preloadMs)

            if (!currentCoroutineContext().isActive) return@launch

            controller.play(item)
            pendingAsset = null
            pendingPlaybackItem = null
            item.startedAt = System.currentTimeMillis()
            emitStarted(asset, item.playbackId, item.startedAt)
            launchPreloadJob()
        }
    }

    fun prepareNext(asset: GaplessAsset) {
        validateAsset(asset)
        nextAssetChannel.trySend(asset)
    }

    fun play(asset: GaplessAsset) {
        validateAsset(asset)

        val alreadyPrepared = pendingAsset?.id == asset.id && pendingPlaybackItem != null
        val existingItem = if (alreadyPrepared) pendingPlaybackItem else null

        startJob?.cancel()
        preloadJob?.cancel()

        startJob = scope.launch {
            val nextItem = existingItem ?: toPlaybackItem(asset)
            val prevItem = currentPlaybackItem
            val prevAsset = currentAsset

            if (!alreadyPrepared) {
                nextItem.preparedAt = System.currentTimeMillis()
                pendingAsset = asset
                pendingPlaybackItem = nextItem
                controller.prepare(nextItem)
                _events.tryEmit(GaplessEvent.Preloading(asset))

                while (currentCoroutineContext().isActive) {
                    if (isNextReadyProvider?.invoke() == true) break
                    delay(50)
                }
            }

            if (!currentCoroutineContext().isActive) return@launch

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
            launchPreloadJob()
        }
    }

    fun stop() {
        startJob?.cancel()
        preloadJob?.cancel()
        startJob = null
        preloadJob = null
        nextAssetChannel.tryReceive()
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

    private fun launchPreloadJob() {
        preloadJob?.cancel()
        preloadJob = scope.launch { runPreloadLoop() }
    }

    private suspend fun effectiveDurationMs(asset: GaplessAsset): Long {
        asset.durationMs?.let { return it }
        while (currentCoroutineContext().isActive) {
            val dur = naturalDurationProvider?.invoke()
            if (dur != null) return dur
            delay(50)
        }
        return -1L
    }

    private suspend fun runPreloadLoop() {
        while (currentCoroutineContext().isActive) {
            val currentItem = currentPlaybackItem ?: break
            val current = currentAsset ?: break

            val next = nextAssetChannel.receive()
            if (!currentCoroutineContext().isActive) break

            validateAsset(next)
            val nextItem = toPlaybackItem(next)

            nextItem.preparedAt = System.currentTimeMillis()
            pendingAsset = next
            pendingPlaybackItem = nextItem
            controller.prepare(nextItem)
            _events.tryEmit(GaplessEvent.Preloading(next))

            val durationMs = effectiveDurationMs(current)
            if (durationMs < 0) break

            val elapsed = System.currentTimeMillis() - currentItem.startedAt
            val remaining = durationMs - elapsed
            if (remaining > 0) delay(remaining)
            if (!currentCoroutineContext().isActive) break

            controller.play(nextItem)

            _events.tryEmit(GaplessEvent.Ended(current, currentItem.playbackId))
            nextItem.startedAt = System.currentTimeMillis()
            currentAsset = next
            pendingAsset = null
            pendingPlaybackItem = null
            currentPlaybackItem = nextItem
            emitStarted(next, nextItem.playbackId, nextItem.startedAt)
        }
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
