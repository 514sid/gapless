package io.github.gapless.sample

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessPlayer
import io.github._514sid.gapless.GaplessRotation
import io.github._514sid.gapless.GaplessVideoConfig

class PlayerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "GaplessPlayer"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_CLOCK = "clock"
        const val EXTRA_UHD = "uhd"
        const val EXTRA_IDS = "ids"
        const val EXTRA_PLAYERS = "players"
        const val EXTRA_FALLBACK = "fallback"
        const val EXTRA_PLAYLIST2 = "playlist2"
        const val EXTRA_IDS2 = "ids2"
        const val EXTRA_VOLUME2 = "volume2"
        private const val DEFAULT_PLAYLIST = "mixed"
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

        // Which generated playlist to loop, and at what volume. Passed through from MainActivity:
        // adb shell am start -S -n io.github.gapless.sample/.MainActivity --es playlist codecs
        val manifest = SampleManifest.load(this)
        val playlistName = intent.getStringExtra(EXTRA_PLAYLIST) ?: DEFAULT_PLAYLIST
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f)
        val showClock = intent.getBooleanExtra(EXTRA_CLOCK, false)
        // Videos above Full HD: on if asked, otherwise only if the display itself goes above it.
        val allowUhd =
            if (intent.hasExtra(EXTRA_UHD)) intent.getBooleanExtra(EXTRA_UHD, true) else displayIsAboveFullHd()
        // One player, or two side by side, each with its own controller and schedule.
        val players = intent.getIntExtra(EXTRA_PLAYERS, 1).coerceIn(1, 2)
        val first = resolvePlaylist(manifest, EXTRA_IDS, EXTRA_PLAYLIST, DEFAULT_PLAYLIST, volume, allowUhd)
        val runners = buildList {
            add(PlaylistRunner(lifecycleScope, if (players == 2) "left" else "", first))
            if (players == 2) {
                // The second player stays muted unless asked, so the two soundtracks don't mix.
                val volume2 = intent.getFloatExtra(EXTRA_VOLUME2, 0f)
                // Without its own list, it plays the first one shifted by one, so the two differ.
                val second = if (intent.hasExtra(EXTRA_IDS2) || intent.hasExtra(EXTRA_PLAYLIST2)) {
                    resolvePlaylist(manifest, EXTRA_IDS2, EXTRA_PLAYLIST2, DEFAULT_PLAYLIST, volume2, allowUhd)
                } else {
                    (first.drop(1) + first.take(1)).map { it.copy(volume = volume2) }
                }
                add(PlaylistRunner(lifecycleScope, "right", second))
            }
        }
        Log.d(
            TAG,
            "Players: $players (uhd=$allowUhd, " +
                "fallback=${intent.getBooleanExtra(EXTRA_FALLBACK, true)})",
        )
        runners.forEach { it.start() }

        val videoConfig = GaplessVideoConfig(
            // false: a decoder that can't open fails instead of falling back to a software one.
            enableDecoderFallback = intent.getBooleanExtra(EXTRA_FALLBACK, true),
            // maxBufferMs >= the clip length so ExoPlayer can preload the next clip across the
            // whole slot instead of only its final few seconds.
            minBufferMs = 2_000,
            maxBufferMs = 12_000,
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Row(Modifier.fillMaxSize()) {
                        runners.forEach { runner ->
                            GaplessPlayer(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                controller = runner.controller,
                                rotation = GaplessRotation.Deg0,
                                videoConfig = videoConfig,
                            )
                        }
                    }
                    if (showClock) DeviceClock()
                }
            }
        }
    }

    /**
     * The assets named by the [idsExtra] (comma-separated ids) or [playlistExtra] launch extra,
     * falling back to [default] when neither names anything known.
     */
    private fun resolvePlaylist(
        manifest: SampleManifest,
        idsExtra: String,
        playlistExtra: String,
        default: String,
        volume: Float,
        allowUhd: Boolean,
    ): List<GaplessAsset> {
        val ids = intent.getStringExtra(idsExtra)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (ids != null) {
            manifest.assets(ids, volume, allowUhd)?.let {
                Log.d(TAG, "Playlist $idsExtra: ${it.map { a -> a.id }}")
                return it
            }
            Log.e(TAG, "Unknown ids in $ids")
        }
        val name = intent.getStringExtra(playlistExtra) ?: default
        val list = manifest.playlist(name, volume, allowUhd) ?: run {
            Log.e(TAG, "No playlist \"$name\"; have ${manifest.playlists.keys}")
            manifest.playlist(default, volume, allowUhd)!!
        }
        Log.d(TAG, "Playlist $name: ${list.map { it.id }}")
        return list
    }

    /** Whether any mode of the main display is larger than 1920x1080. */
    private fun displayIsAboveFullHd(): Boolean {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return true
        return display.supportedModes.any {
            maxOf(it.physicalWidth, it.physicalHeight) > 1920 || minOf(it.physicalWidth, it.physicalHeight) > 1080
        }
    }
}

/**
 * The device's own clock, drawn over the player, so a camera recording shows real time next to
 * the clip's timer and a stall or gap can be measured.
 */
@Composable
private fun DeviceClock() {
    var ms by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { ms = SystemClock.uptimeMillis() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Text(
            text = "device %d:%02d.%03d".format(ms / 60_000 % 60, ms / 1000 % 60, ms % 1000),
            color = Color.White,
            fontSize = 28.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
