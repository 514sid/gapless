package io.github._514sid.gapless

import android.annotation.SuppressLint
import android.util.Log
import android.view.TextureView
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github._514sid.gapless.internal.GaplessViewModel
import java.io.File

private const val TAG = "GaplessPlayer"

/**
 * A self-contained gapless media player composable.
 *
 * Maintains two render slots internally so the next asset is always preloaded and buffered
 * before the current one finishes, producing seamless black-frame-free transitions.
 *
 * // ... (keep existing docs for supported content, scheduling, errors, scope) ...
 *
 * @param assets     Ordered list of assets to play. Passing a new list hot-swaps the playlist
 * while preserving the currently-playing asset when possible.
 * @param rotation   Screen rotation in degrees — `0`, `90`, `180`, or `270`. Content is rotated
 * inside its bounds; the composable's own layout size is unaffected.
 * @param shuffle    When `true` the playlist is randomized each pass, ensuring the last-played
 * asset does not appear first in the reshuffled order.
 * @param config     Timing configuration — tick interval and preload threshold. See [GaplessPlayerConfig].
 * @param onEvent    Invoked on the main thread for each [GaplessEvent]:
 * [GaplessEvent.NowPlaying], [GaplessEvent.Preloading],
 * [GaplessEvent.PlaybackError], [GaplessEvent.PlaylistEmpty].
 * @param emptyState Composable to show when the [assets] list is completely empty.
 * @param idleState  Composable to show when [assets] is not empty, but NO assets currently
 * meet their temporal scheduling criteria (i.e., waiting for a time window).
 * Defaults to calling [emptyState] if not explicitly provided.
 */
@Composable
fun GaplessPlayer(
    assets: List<GaplessAsset>,
    rotation: Int = 0,
    shuffle: Boolean = false,
    config: GaplessPlayerConfig = GaplessPlayerConfig(),
    onEvent: (GaplessEvent) -> Unit = {},
    emptyState: @Composable () -> Unit = { Box(modifier = Modifier.fillMaxSize().background(Color.Black)) },
    idleState: @Composable () -> Unit = emptyState 
) {
    val viewModel: GaplessViewModel = viewModel()

    LaunchedEffect(assets, shuffle, config) {
        viewModel.update(assets, shuffle, config)
    }

    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { currentOnEvent(it) }
    }

    val currentAsset by viewModel.currentAsset.collectAsState()
    val preloadAsset by viewModel.preloadAsset.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()

    val slots = remember { mutableStateListOf<GaplessAsset?>(null, null) }

    LaunchedEffect(currentAsset, preloadAsset) {
        val liveIds = setOfNotNull(currentAsset?.id, preloadAsset?.id)

        for (i in 0..1) {
            if (slots[i]?.id !in liveIds) slots[i] = null
        }

        listOfNotNull(currentAsset, preloadAsset).forEach { asset ->
            val existing = slots.indexOfFirst { it?.id == asset.id }
            when {
                existing != -1 -> slots[existing] = asset
                else -> {
                    val empty = slots.indexOfFirst { it == null }
                    if (empty != -1) slots[empty] = asset
                    else Log.w(TAG, "Render slot overflow — more nodes than slots")
                }
            }
        }
    }

    if (!isInitialized) return

    RotatedScreenContainer(rotation) {
        if (assets.isEmpty()) {
            // 1. The list is completely empty. Show empty state immediately.
            emptyState()
        } else if (currentAsset == null && preloadAsset == null) {
            // 2. We have assets, but none are scheduled to play right now. 
            idleState()
        } else {
            // 3. We have assets and at least one is active. Render the slots.
            for (i in 0..1) {
                MediaSlot(
                    asset = slots[i],
                    isActive = slots[i]?.id == currentAsset?.id,
                    onError = { asset, msg -> viewModel.handlePlaybackError(asset, msg) },
                )
            }
        }
    }
}

// ── Internal composables ──────────────────────────────────────────────────────

@Composable
private fun RotatedScreenContainer(rotation: Int, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { rotationZ = rotation.toFloat() },
    ) { measurables, constraints ->
        val isRotated = rotation == 90 || rotation == 270
        val adjusted = if (isRotated) {
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )
        } else constraints

        val placeables = measurables.map { it.measure(adjusted) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { p ->
                p.placeRelative(
                    x = (constraints.maxWidth - p.width) / 2,
                    y = (constraints.maxHeight - p.height) / 2,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MediaSlot(
    asset: GaplessAsset?,
    isActive: Boolean,
    onError: (GaplessAsset, String) -> Unit,
) {
    val context = LocalContext.current

    val isVideo = asset?.isVideo == true
    val isImage = asset?.isImage == true
    val isWeb = asset?.isWeb == true

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f
        }
    }
    val textureView = remember { TextureView(context) }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
        }
    }

    var firstFrameReady by remember { mutableStateOf(false) }
    var videoRatio by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) {
        exoPlayer.setVideoTextureView(textureView)
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
            webView.destroy()
        }
    }

    // Load new asset into the appropriate renderer when the slot ID changes.
    LaunchedEffect(asset?.id) {
        if (asset == null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            webView.loadUrl("about:blank")
            return@LaunchedEffect
        }
        when {
            isVideo -> {
                firstFrameReady = false
                val uri = if (asset.uri.startsWith("/")) "file://${asset.uri}" else asset.uri
                exoPlayer.setMediaItem(
                    MediaItem.Builder().setUri(uri).setMimeType(asset.mimeType).build()
                )
                exoPlayer.prepare()
            }
            isWeb -> {
                if (webView.url != asset.uri) webView.loadUrl(asset.uri)
            }
            else -> exoPlayer.stop()
        }
    }

    // Play / pause in response to the active-slot flag.
    LaunchedEffect(isActive, asset?.id) {
        if (isActive) {
            if (isVideo) { exoPlayer.seekTo(0); exoPlayer.play() }
            if (isWeb) webView.onResume()
        } else {
            exoPlayer.pause()
            webView.onPause()
        }
    }

    // Wire player / web listeners; tear them down when the asset changes.
    DisposableEffect(asset?.id) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameReady = true }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0) videoRatio = (v.width.toFloat() * v.pixelWidthHeightRatio) / v.height
            }
            override fun onPlayerError(e: PlaybackException) {
                asset?.let { onError(it, e.localizedMessage ?: "Video error") }
            }
        }
        exoPlayer.addListener(listener)

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    asset?.let { onError(it, error?.description?.toString() ?: "Web error") }
                }
            }
        }

        onDispose {
            exoPlayer.removeListener(listener)
            webView.webViewClient = WebViewClient()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (isActive) 1f else 0f }
            .zIndex(if (isActive) 1f else 0f),
        contentAlignment = Alignment.Center,
    ) {
        val videoModifier = (videoRatio?.let { Modifier.then(Modifier) } ?: Modifier.fillMaxSize())
            .graphicsLayer { alpha = if (isVideo && firstFrameReady) 1f else 0f }
        AndroidView(factory = { textureView }, modifier = videoModifier)

        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (isWeb) 1f else 0f },
        )

        if (isImage) {
            val model = remember(asset.uri) {
                ImageRequest.Builder(context)
                    .data(if (asset.uri.startsWith("/")) File(asset.uri) else asset.uri)
                    .listener(onError = { _, r ->
                        onError(asset, r.throwable.message ?: "Image error")
                    })
                    .build()
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
