package io.github._514sid.gapless

import io.github._514sid.gapless.internal.PlaybackItem
import io.github._514sid.gapless.internal.PlayerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Drives playback: the host prepares the next asset, then asks to play it on its own schedule.
 *
 * Every public call ([start], [prepareNext], [play], [stop]) is turned into an internal intent and
 * processed by a single coroutine, so the controller's state and the [PlayerCommand]s it emits are
 * always handled in the exact order the calls arrived — even when, for example, a `play` and a
 * `prepareNext` for the following asset are issued back-to-back. Timing (the initial preload window
 * and any wait-for-ready) runs in a cancellable child job tagged with a generation, so a newer
 * intent always supersedes an older, in-flight transition instead of racing it.
 *
 * The public methods are safe to call from any thread. Readiness checks against the player always
 * run on the main thread, whatever dispatcher [scope] uses.
 *
 * @param preloadMs How long [start] buffers the first asset before showing it.
 * @param readyTimeoutMs Upper bound on how long [play] waits for an asset that was not staged with
 * [prepareNext] to become ready. When it runs out the cut happens anyway and
 * [GaplessEvent.PreloadMissed] is emitted.
 */
public class GaplessController(
    private val scope: CoroutineScope,
    public val preloadMs: Long = 2_000L,
    public val readyTimeoutMs: Long = 10_000L,
) {
    private val commandChannel = Channel<PlayerCommand>(Channel.UNLIMITED)
    internal val commands = commandChannel.receiveAsFlow()

    private val _events = MutableSharedFlow<GaplessEvent>(
        replay = 1,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Playback events, for logging and error handling. Emission never blocks playback: if a
     * collector falls more than 64 events behind, the oldest undelivered events are
     * dropped. The last event is replayed to new collectors. Use [currentState] to track what is
     * on screen.
     */
    public val events: SharedFlow<GaplessEvent> = _events.asSharedFlow()

    private val _currentState = MutableStateFlow<GaplessPlaybackState?>(null)
    public val currentState: StateFlow<GaplessPlaybackState?> = _currentState.asStateFlow()

    /** Set by [GaplessPlayer]: whether the item with the given playback id can render. Main thread only. */
    internal var isReadyProvider: ((UUID) -> Boolean)? = null

    private sealed interface Intent {
        data class Start(val asset: GaplessAsset) : Intent
        data class PrepareNext(val asset: GaplessAsset) : Intent
        data class Play(val asset: GaplessAsset) : Intent
        data class ReadyToPlay(val asset: GaplessAsset, val item: PlaybackItem, val generation: Long) : Intent
        data object Stop : Intent
        data class PreloadMissed(val assetId: String, val elapsedMs: Long) : Intent
        data class PreloadError(val assetId: String, val message: String) : Intent
        data class PlaybackError(val message: String) : Intent
    }

    private val intents = Channel<Intent>(Channel.UNLIMITED)

    // The fields below are owned exclusively by the intent-processing coroutine. Because that
    // coroutine handles one intent at a time, they need no further synchronization.
    private var currentAsset: GaplessAsset? = null
    private var currentItem: PlaybackItem? = null
    private var preparedAsset: GaplessAsset? = null
    private var preparedItem: PlaybackItem? = null

    private var transitionJob: Job? = null
    private var generation: Long = 0L

    init {
        scope.launch {
            for (intent in intents) handle(intent)
        }
    }

    /**
     * Begins playback with [asset]: buffers it for [preloadMs], then shows it.
     *
     * @throws IllegalArgumentException if [asset] fails validation (blank id or uri, unsupported
     * mimeType, volume outside 0..1, non-positive durationMs or refreshIntervalMs).
     */
    public fun start(asset: GaplessAsset) {
        validateAsset(asset)
        intents.trySend(Intent.Start(asset))
    }

    /**
     * Starts buffering [asset] so a later [play] of it can cut immediately.
     *
     * @throws IllegalArgumentException if [asset] fails validation (blank id or uri, unsupported
     * mimeType, volume outside 0..1, non-positive durationMs or refreshIntervalMs).
     */
    public fun prepareNext(asset: GaplessAsset) {
        validateAsset(asset)
        intents.trySend(Intent.PrepareNext(asset))
    }

    /**
     * Cuts to [asset]. If it was staged with [prepareNext] the cut is immediate; otherwise it is
     * prepared first and shown once ready, or after [readyTimeoutMs].
     *
     * Unless a later call supersedes it, this ends in [GaplessEvent.Started] for [asset], or in
     * [GaplessEvent.PreloadError] if it fails to load, in which case nothing changes on screen.
     *
     * @throws IllegalArgumentException if [asset] fails validation (blank id or uri, unsupported
     * mimeType, volume outside 0..1, non-positive durationMs or refreshIntervalMs).
     */
    public fun play(asset: GaplessAsset) {
        validateAsset(asset)
        intents.trySend(Intent.Play(asset))
    }

    /** Stops playback and forgets the current and staged assets. [start] resumes. */
    public fun stop() {
        intents.trySend(Intent.Stop)
    }

    /**
     * Stops playback and shuts the controller down for good. Calls made afterwards are ignored.
     * Cancelling [scope] has the same effect; use this when the controller should end before its
     * scope does.
     */
    public fun close() {
        intents.trySend(Intent.Stop)
        intents.close()
        commandChannel.close()
    }

    internal fun onPreloadMissed(assetId: String, elapsedMs: Long) {
        intents.trySend(Intent.PreloadMissed(assetId, elapsedMs))
    }

    internal fun onPreloadError(assetId: String, message: String) {
        intents.trySend(Intent.PreloadError(assetId, message))
    }

    internal fun onPlaybackError(message: String) {
        intents.trySend(Intent.PlaybackError(message))
    }

    private suspend fun handle(intent: Intent) {
        when (intent) {
            is Intent.Start -> handleStart(intent.asset)
            is Intent.PrepareNext -> stagePrepare(intent.asset)
            is Intent.Play -> handlePlay(intent.asset)
            is Intent.ReadyToPlay -> {
                if (intent.generation == generation) commitPlay(intent.asset, intent.item)
            }
            is Intent.Stop -> handleStop()
            is Intent.PreloadMissed -> {
                assetForId(intent.assetId)?.let {
                    _events.tryEmit(GaplessEvent.PreloadMissed(it, preloadMs, intent.elapsedMs))
                }
            }
            is Intent.PreloadError -> handlePreloadError(intent.assetId, intent.message)
            is Intent.PlaybackError -> handlePlaybackError(intent.message)
        }
    }

    private fun handleStart(asset: GaplessAsset) {
        generation++
        transitionJob?.cancel()

        val item = toPlaybackItem(asset).also { it.preparedAt = System.currentTimeMillis() }
        preparedAsset = asset
        preparedItem = item
        // Seed "current" so the very first transition does not emit a spurious Ended.
        currentAsset = asset
        currentItem = item

        commandChannel.trySend(PlayerCommand.Prepare(item))
        _events.tryEmit(GaplessEvent.Preloading(asset))

        val gen = generation
        transitionJob = scope.launch {
            delay(preloadMs)
            if (isActive) intents.trySend(Intent.ReadyToPlay(asset, item, gen)) // no-op once closed
        }
    }

    private fun stagePrepare(asset: GaplessAsset) {
        val item = toPlaybackItem(asset).also { it.preparedAt = System.currentTimeMillis() }
        preparedAsset = asset
        preparedItem = item

        commandChannel.trySend(PlayerCommand.Prepare(item))
        _events.tryEmit(GaplessEvent.Preloading(asset))
    }

    private fun handlePlay(asset: GaplessAsset) {
        generation++
        transitionJob?.cancel()

        val prepared = preparedItem?.takeIf { preparedAsset?.id == asset.id }
        if (prepared != null) {
            // Already staged: swap synchronously so the Play command is enqueued before anything
            // the resulting Started event causes the host to issue (e.g. prepareNext of the next asset).
            commitPlay(asset, prepared)
            return
        }

        // Not staged yet: prepare it now and wait until it can render before swapping, so we never
        // cut to a black or unbuffered surface.
        val item = toPlaybackItem(asset).also { it.preparedAt = System.currentTimeMillis() }
        preparedAsset = asset
        preparedItem = item
        commandChannel.trySend(PlayerCommand.Prepare(item))
        _events.tryEmit(GaplessEvent.Preloading(asset))

        val gen = generation
        transitionJob = scope.launch {
            // Times out into a forced cut; the player reports PreloadMissed for it.
            withTimeoutOrNull(readyTimeoutMs) {
                while (!isReady(item)) delay(READY_POLL_MS)
            }
            if (isActive) intents.trySend(Intent.ReadyToPlay(asset, item, gen)) // no-op once closed
        }
    }

    private suspend fun isReady(item: PlaybackItem): Boolean {
        val provider = isReadyProvider ?: return false
        return withContext(Dispatchers.Main.immediate) { provider(item.playbackId) }
    }

    private fun handlePreloadError(assetId: String, message: String) {
        val asset = assetForId(assetId) ?: return
        _events.tryEmit(GaplessEvent.PreloadError(asset, message))

        if (preparedAsset?.id != assetId) return
        val failedItem = preparedItem
        // Drop the staged item so a later play() of this asset loads it again instead of cutting
        // to a slot that failed.
        preparedAsset = null
        preparedItem = null
        // A transition waiting on this asset can never complete; keep the current asset on screen.
        if (transitionJob?.isActive == true) {
            generation++
            transitionJob?.cancel()
            transitionJob = null
        }
        // start() seeds "current" with the first asset, which never made it on screen.
        if (currentItem === failedItem) {
            currentAsset = null
            currentItem = null
        }
    }

    private fun handleStop() {
        generation++
        transitionJob?.cancel()
        transitionJob = null
        currentAsset = null
        currentItem = null
        preparedAsset = null
        preparedItem = null
        _currentState.value = null
    }

    private fun handlePlaybackError(message: String) {
        val asset = currentAsset ?: return
        val item = currentItem ?: return
        generation++
        transitionJob?.cancel()
        transitionJob = null
        _events.tryEmit(GaplessEvent.PlaybackError(asset, item.playbackId, message))
        currentAsset = null
        currentItem = null
        _currentState.value = null
    }

    /** Emits the Play command and advances bookkeeping/events. Always runs on the intent coroutine. */
    private fun commitPlay(asset: GaplessAsset, item: PlaybackItem) {
        commandChannel.trySend(PlayerCommand.Play(item))

        val prevAsset = currentAsset
        val prevItem = currentItem
        if (prevAsset != null && prevItem != null && prevItem.playbackId != item.playbackId) {
            _events.tryEmit(GaplessEvent.Ended(prevAsset, prevItem.playbackId))
        }

        item.startedAt = System.currentTimeMillis()
        currentAsset = asset
        currentItem = item
        preparedAsset = null
        preparedItem = null

        _currentState.value = GaplessPlaybackState(asset, item.playbackId, item.startedAt)
        _events.tryEmit(GaplessEvent.Started(asset, item.playbackId))
    }

    private fun assetForId(assetId: String): GaplessAsset? = when (assetId) {
        preparedAsset?.id -> preparedAsset
        currentAsset?.id -> currentAsset
        else -> null
    }

    private fun validateAsset(asset: GaplessAsset) {
        require(asset.id.isNotBlank()) { "Asset with uri \"${asset.uri}\" has a blank id" }
        require(asset.uri.isNotBlank()) { "Asset \"${asset.id}\" has a blank uri" }
        require(asset.isVideo || asset.isImage || asset.isWeb) {
            "Asset \"${asset.id}\" has unsupported mimeType \"${asset.mimeType}\""
        }
        require(asset.volume in 0f..1f) { "Asset \"${asset.id}\" volume ${asset.volume} is outside 0.0..1.0" }
        asset.durationMs?.let { require(it > 0) { "Asset \"${asset.id}\" durationMs must be positive" } }
        asset.refreshIntervalMs?.let { require(it > 0) { "Asset \"${asset.id}\" refreshIntervalMs must be positive" } }
    }

    private fun toPlaybackItem(g: GaplessAsset): PlaybackItem {
        val item = when {
            g.isVideo -> PlaybackItem.Video(
                uri = g.uri,
                mimeType = g.mimeType,
                aspectRatioHint = aspectRatioOf(g.width, g.height),
                volume = g.volume,
                durationMs = g.durationMs,
            )
            g.isImage -> PlaybackItem.Image(
                model = g.uri,
                width = g.width ?: 1920,
                height = g.height ?: 1080
            )
            else -> PlaybackItem.Web(url = g.uri, refreshIntervalMs = g.refreshIntervalMs ?: 0L)
        }
        item.assetId = g.id
        return item
    }

    private fun aspectRatioOf(width: Int?, height: Int?): Float? =
        if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else null

    private companion object {
        const val READY_POLL_MS = 50L
        const val EVENT_BUFFER = 64
    }
}
