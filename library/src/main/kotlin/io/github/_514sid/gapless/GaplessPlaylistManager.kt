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
 * @param shuffle           When true, the list is reshuffled after every full pass.
 * @param skipFailedAssets  true: advance immediately on error. false: let remaining time expire.
 */
class GaplessPlaylistManager(
    private val scope: CoroutineScope,
    val preloadMs: Long = 2_000L,
    val shuffle: Boolean = false,
    val skipFailedAssets: Boolean = true
) {
    internal val controller = PlayerController()

    // replay = 1 so subscribers that join after an event is emitted (e.g. the Compose
    // LaunchedEffect that starts after manager.start() is called) still receive the
    // most recent state event such as Empty or Idle.
    private val _events = MutableSharedFlow<GaplessEvent>(replay = 1, extraBufferCapacity = 16)
    val events: SharedFlow<GaplessEvent> = _events.asSharedFlow()

    private var assets: List<GaplessAsset> = emptyList()
    private var playlist: List<GaplessAsset> = emptyList()

    private var currentPlaybackItem: PlaybackItem? = null
    private var currentAssetFailed = false

    private var startJob: Job? = null
    private var preloadJob: Job? = null

    fun start(assets: List<GaplessAsset>) {
        this.assets = assets
        startLoop()
    }

    fun update(assets: List<GaplessAsset>) {
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

    internal fun onPlaybackError(message: String) {
        val item = currentPlaybackItem ?: return
        val asset = playlist.firstOrNull { it.id == item.assetId } ?: return
        _events.tryEmit(GaplessEvent.PlaybackError(asset, item.playbackId, message))
        if (skipFailedAssets) {
            currentPlaybackItem = findNext()?.let { toPlaybackItem(it) }
            startLoop()
        } else {
            currentAssetFailed = true
        }
    }

    private fun rebuildPlaylist(lastPlayedId: String? = null) {
        playlist = if (shuffle && assets.isNotEmpty()) {
            val shuffled = assets.shuffled()
            if (lastPlayedId != null && shuffled.first().id == lastPlayedId)
                shuffled.drop(1) + shuffled.first()
            else shuffled
        } else {
            assets.toList()
        }
    }

    private fun findFromCurrent(): GaplessAsset? {
        val from = currentPlaybackItem
            ?.let { item -> playlist.indexOfFirst { it.id == item.assetId }.coerceAtLeast(0) }
            ?: 0
        return playlist.subList(from, playlist.size).firstOrNull { it.isActiveNow() }
    }

    private fun findNext(): GaplessAsset? {
        val after = currentPlaybackItem
            ?.let { item -> playlist.indexOfFirst { it.id == item.assetId } }
            ?: -1
        val from = (after + 1).coerceAtLeast(0)
        return playlist.subList(from, playlist.size).firstOrNull { it.isActiveNow() }
    }

    private suspend fun awaitActive(lastPlayedAssetId: String? = null): GaplessAsset {
        rebuildPlaylist(lastPlayedId = lastPlayedAssetId)
        var idleEmitted = false
        while (true) {
            val found = findFromCurrent()
            if (found != null) return found
            if (!idleEmitted) { _events.tryEmit(GaplessEvent.Idle()); idleEmitted = true }
            delay(100)
        }
    }

    private suspend fun awaitNextCycle(lastPlayedId: String): GaplessAsset {
        rebuildPlaylist(lastPlayedId = lastPlayedId)
        var idleEmitted = false
        while (true) {
            val found = playlist.firstOrNull { it.isActiveNow() }
            if (found != null) return found
            if (!idleEmitted) { _events.tryEmit(GaplessEvent.Idle()); idleEmitted = true }
            delay(100)
        }
    }

    private fun startLoop() {
        startJob?.cancel()
        preloadJob?.cancel()
        if (assets.isEmpty()) { _events.tryEmit(GaplessEvent.Empty()); return }

        currentAssetFailed = false
        startJob = scope.launch {
            val first = awaitActive(lastPlayedAssetId = currentPlaybackItem?.assetId)
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
        val prevAsset = prevItem?.let { p -> playlist.firstOrNull { it.id == p.assetId } }

        startJob = scope.launch {
            val next = awaitActive(lastPlayedAssetId = prevItem?.assetId)
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

    private suspend fun runPreloadLoop() {
        while (currentCoroutineContext().isActive) {
            val currentItem = currentPlaybackItem ?: break
            val current = playlist.firstOrNull { it.id == currentItem.assetId } ?: break

            val elapsed = System.currentTimeMillis() - currentItem.startedAt
            val remaining = (current.durationMs - preloadMs) - elapsed
            if (remaining > 0) delay(remaining)
            if (!currentCoroutineContext().isActive) break

            val failed = currentAssetFailed
            currentAssetFailed = false

            val next = findNext() ?: awaitNextCycle(lastPlayedId = currentItem.assetId)
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
     * Forces the player to transition to the asset at the original [index] in exactly [delayMs] milliseconds.
     * This references the unmodified `assets` list so multiple devices can sync
     * by the original source order, regardless of local shuffle state.
     */
    fun syncPlayIndexIn(index: Int, delayMs: Long) {
        val targetAsset = assets.getOrNull(index)?.takeIf { it.isActiveNow() } ?: return

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

            rebuildPlaylist(lastPlayedId = nextItem.assetId)
            launchPreloadJob()
        }
    }
}
