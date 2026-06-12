package io.github._514sid.gapless

import io.github._514sid.gapless.internal.PlaybackItem
import io.github._514sid.gapless.internal.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.core.net.toUri

/**
 * Playlist manager - drives [GaplessPlayer] via explicit prepare/play commands.
 *
 * @param scope             Coroutine scope that owns the playback loop.
 * @param preloadMs         How many milliseconds before each transition to start buffering.
 * @param skipFailedAssets  true: advance immediately on error. false: let remaining time expire.
 */
class GaplessPlaylistManager(
    private val scope: CoroutineScope,
    val preloadMs: Long = 2_000L,
    val skipFailedAssets: Boolean = true
) {
    internal val controller = PlayerController()

    // replay = 1 so subscribers that join after an event is emitted (e.g. the Compose
    // LaunchedEffect that starts after manager.start() is called) still receive the
    // most recent state event such as Empty or Idle.
    private val _events = MutableSharedFlow<GaplessEvent>(replay = 1, extraBufferCapacity = 16)
    val events: SharedFlow<GaplessEvent> = _events.asSharedFlow()

    private var assets: List<GaplessAsset> = emptyList()

    private var currentPlaybackItem: PlaybackItem? = null
    private var currentAssetFailed = false

    private var startJob: Job? = null
    private var preloadJob: Job? = null

    private fun requireValidAssets(assets: List<GaplessAsset>) {
        assets.forEach { asset ->
            require(asset.id.isNotBlank()) { "Asset with uri \"${asset.uri}\" has a blank id" }
            require(asset.uri.isNotBlank()) { "Asset \"${asset.id}\" has a blank uri" }
            if (asset.durationMs != null) {
                require(asset.durationMs > 0) { "Asset \"${asset.id}\" has durationMs=${asset.durationMs}, must be > 0" }
            } else {
                require(asset.isVideo) { "Asset \"${asset.id}\" has durationMs=null but is not a video; natural duration is only supported for video assets" }
            }
        }
        val seen = mutableSetOf<String>()
        assets.forEach { asset ->
            require(seen.add(asset.id)) { "Duplicate asset id \"${asset.id}\"" }
        }
    }

    fun start(assets: List<GaplessAsset>) {
        requireValidAssets(assets)
        this.assets = assets
        startLoop()
    }

    fun update(assets: List<GaplessAsset>) {
        requireValidAssets(assets)
        this.assets = assets
        if (assets.isEmpty()) {
            stop()
            _events.tryEmit(GaplessEvent.Empty())
            return
        }
        val currentStillPresent = currentPlaybackItem != null &&
                assets.any { it.id == currentPlaybackItem!!.assetId }
        if (currentStillPresent) reschedulePreload() else transitionToNext()
    }

    fun stop() {
        startJob?.cancel()
        preloadJob?.cancel()
        startJob = null
        preloadJob = null
    }

    internal var naturalDurationProvider: (() -> Long?)? = null

    internal fun onPlaybackError(message: String) {
        val item = currentPlaybackItem ?: return
        val asset = assets.firstOrNull { it.id == item.assetId } ?: return
        _events.tryEmit(GaplessEvent.PlaybackError(asset, item.playbackId, message))
        if (skipFailedAssets) {
            currentPlaybackItem = findNext()?.let { toPlaybackItem(it) }
            startLoop()
        } else {
            currentAssetFailed = true
        }
    }

    private fun findFromCurrent(): GaplessAsset? {
        val from = currentPlaybackItem
            ?.let { item -> assets.indexOfFirst { it.id == item.assetId }.coerceAtLeast(0) }
            ?: 0
        return assets.subList(from, assets.size).firstOrNull()
    }

    private fun findNext(): GaplessAsset? {
        val after = currentPlaybackItem
            ?.let { item -> assets.indexOfFirst { it.id == item.assetId } }
            ?: -1
        val from = (after + 1).coerceAtLeast(0)
        return assets.subList(from, assets.size).firstOrNull()
    }

    private fun awaitActive(): GaplessAsset = findFromCurrent() ?: assets.first()

    private fun awaitNextCycle(): GaplessAsset = assets.first()

    private fun startLoop() {
        startJob?.cancel()
        preloadJob?.cancel()
        if (assets.isEmpty()) { _events.tryEmit(GaplessEvent.Empty()); return }

        currentAssetFailed = false
        startJob = scope.launch {
            val first = awaitActive()
            val item = toPlaybackItem(first)
            currentPlaybackItem = item

            controller.prepare(item)
            _events.tryEmit(GaplessEvent.Preloading(first))
            delay(preloadMs)

            controller.play(item)
            item.startedAt = System.currentTimeMillis()
            _events.tryEmit(GaplessEvent.Started(first, item.playbackId))
            launchPreloadJob()
        }
    }

    private fun reschedulePreload() {
        startJob?.cancel()
        preloadJob?.cancel()
        launchPreloadJob()
    }

    private fun transitionToNext() {
        startJob?.cancel()
        preloadJob?.cancel()
        currentAssetFailed = false

        val prevItem = currentPlaybackItem
        val prevAsset = prevItem?.let { p -> assets.firstOrNull { it.id == p.assetId } }

        startJob = scope.launch {
            val next = awaitActive()
            val nextItem = toPlaybackItem(next)

            controller.prepare(nextItem)
            _events.tryEmit(GaplessEvent.Preloading(next))

            delay(preloadMs)
            if (!currentCoroutineContext().isActive) return@launch

            controller.play(nextItem)
            if (prevItem != null && prevAsset != null) {
                _events.tryEmit(GaplessEvent.Ended(prevAsset, prevItem.playbackId))
            }
            nextItem.startedAt = System.currentTimeMillis()
            currentPlaybackItem = nextItem
            _events.tryEmit(GaplessEvent.Started(next, nextItem.playbackId))

            launchPreloadJob()
        }
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
            val current = assets.firstOrNull { it.id == currentItem.assetId } ?: break

            val durationMs = effectiveDurationMs(current)
            if (durationMs < 0) break

            val elapsed = System.currentTimeMillis() - currentItem.startedAt
            val remaining = (durationMs - preloadMs) - elapsed
            if (remaining > 0) delay(remaining)
            if (!currentCoroutineContext().isActive) break

            val failed = currentAssetFailed
            currentAssetFailed = false

            val next = findNext() ?: run {
                _events.tryEmit(GaplessEvent.CycleCompleted())
                awaitNextCycle()
            }
            val nextItem = toPlaybackItem(next)

            controller.prepare(nextItem)
            _events.tryEmit(GaplessEvent.Preloading(next))

            delay(preloadMs)
            if (!currentCoroutineContext().isActive) break

            controller.play(nextItem)

            if (!failed) _events.tryEmit(GaplessEvent.Ended(current, currentItem.playbackId))
            nextItem.startedAt = System.currentTimeMillis()
            currentPlaybackItem = nextItem
            _events.tryEmit(GaplessEvent.Started(next, nextItem.playbackId))
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

    /**
     * Forces the player to transition to the asset with [assetId] in exactly [delayMs] milliseconds.
     */
    fun syncPlayIn(assetId: String, delayMs: Long) {
        val targetAsset = assets.firstOrNull { it.id == assetId } ?: return

        startJob?.cancel()
        preloadJob?.cancel()
        currentAssetFailed = false

        startJob = scope.launch {
            val nextItem = toPlaybackItem(targetAsset)

            val timeUntilPreload = delayMs - preloadMs

            if (timeUntilPreload > 0) {
                delay(timeUntilPreload)
                controller.prepare(nextItem)
                _events.tryEmit(GaplessEvent.Preloading(targetAsset))

                delay(preloadMs)
            } else {
                controller.prepare(nextItem)
                _events.tryEmit(GaplessEvent.Preloading(targetAsset))

                if (delayMs > 0) delay(delayMs)
            }

            if (!currentCoroutineContext().isActive) return@launch

            controller.play(nextItem)

            val prevItem = currentPlaybackItem
            val prevAsset = prevItem?.let { p -> assets.firstOrNull { it.id == p.assetId } }
            if (prevItem != null && prevAsset != null) {
                _events.tryEmit(GaplessEvent.Ended(prevAsset, prevItem.playbackId))
            }

            nextItem.startedAt = System.currentTimeMillis()
            currentPlaybackItem = nextItem
            _events.tryEmit(GaplessEvent.Started(targetAsset, nextItem.playbackId))

            launchPreloadJob()
        }
    }
}
