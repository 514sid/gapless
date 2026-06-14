package io.github.gapless.sample

import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github._514sid.gapless.GaplessAsset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import io.github._514sid.gapless.GaplessEvent
import io.github._514sid.gapless.GaplessController
import io.github._514sid.gapless.GaplessPlayer
import io.github._514sid.gapless.GaplessRotation
import io.github._514sid.gapless.GaplessVideoConfig
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "GaplessPlayer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.allowContentAccess = true
            serviceWorkerWebSettings.allowFileAccess = true
            setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
            })
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isSustainedPerformanceModeSupported) {
            window.setSustainedPerformanceMode(true)
        }

        val slotMs = 10_000L
        val assets = listOf(
            GaplessAsset(
                id = "horse1",
                uri = "android.resource://$packageName/raw/horse1",
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                durationMs = slotMs,
            ) to slotMs,
            GaplessAsset(
                id = "horse2",
                uri = "android.resource://$packageName/raw/horse2",
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                durationMs = slotMs,
            ) to slotMs,
            GaplessAsset(
                id = "google",
                uri = "https://google.com",
                mimeType = "text/html",
            ) to slotMs,
        )
        val durations = assets.associate { (asset, duration) -> asset.id to duration }
        var index = 0
        var timerJob: Job? = null

        val manager = GaplessController(scope = lifecycleScope, preloadMs = 3_000)

        if (assets.isNotEmpty()) {
            manager.start(assets[index++].first)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.events.collect { event ->
                    when (event) {
                        is GaplessEvent.Started -> {
                            Log.d(TAG, "Started: ${event.asset.id} [${event.playbackId}]")
                            if (assets.isNotEmpty()) {
                                timerJob?.cancel()
                                val next = assets[index++ % assets.size].first
                                manager.prepareNext(next)
                                timerJob = launch {
                                    delay(durations[event.asset.id] ?: return@launch)
                                    manager.play(next)
                                }
                            }
                        }
                        is GaplessEvent.Ended ->
                            Log.d(TAG, "Ended: ${event.asset.id} [${event.playbackId}]")
                        is GaplessEvent.PlaybackError ->
                            Log.e(TAG, "Error on ${event.asset.id}: ${event.message}")
                        is GaplessEvent.PreloadError ->
                            Log.e(TAG, "PreloadError on ${event.asset.id}: ${event.message}")
                        is GaplessEvent.Preloading ->
                            Log.d(TAG, "Preloading: ${event.asset.id}")
                        is GaplessEvent.PreloadMissed ->
                            Log.w(TAG, "PreloadMissed: ${event.asset.id} took ${event.elapsedMs}ms")
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GaplessPlayer(
                        modifier = Modifier.fillMaxSize(),
                        manager = manager,
                        rotation = GaplessRotation.Deg0,
                        // maxBufferMs >= the clip length so ExoPlayer can preload the next clip
                        // across the whole slot instead of only its final few seconds.
                        videoConfig = GaplessVideoConfig(
                            minBufferMs = 2_000,
                            maxBufferMs = 12_000,
                        ),
                    )
                }
            }
        }
    }
}
