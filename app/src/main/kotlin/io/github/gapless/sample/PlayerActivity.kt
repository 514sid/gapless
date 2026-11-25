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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
        const val EXTRA_ROTATION = "rotation"
        const val EXTRA_PLAYLIST2 = "playlist2"
        const val EXTRA_IDS2 = "ids2"
        const val EXTRA_VOLUME2 = "volume2"
        const val EXTRA_SYNC = "sync"
        const val EXTRA_ANCHOR = "anchor"
        const val EXTRA_LATE_MS = "lateMs"
        const val EXTRA_SCRIPTS = "scripts"
        const val EXTRA_FIT = "fit"
        const val EXTRA_BACKGROUND = "background"
        const val EXTRA_SERVER = "server"
        const val EXTRA_HEADER = "header"
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
        // --ez scripts false drops the page scripts and readiness waits, to compare without them.
        // --es fit cover and --es background 202020 frame every video and image that way.
        // --es server http://host:8000/ fetches the samples from an HTTP server serving the samples
        // folder, and --es header "Authorization: Bearer x" sends that header with every request.
        val manifest = SampleManifest.load(this).let {
            if (intent.getBooleanExtra(EXTRA_SCRIPTS, true)) it else it.withoutScripts()
        }.framed(
            fit = intent.getStringExtra(EXTRA_FIT)?.let(SampleManifest::parseFit),
            backgroundColor = intent.getStringExtra(EXTRA_BACKGROUND)?.let(SampleManifest::parseColor),
        ).served(intent.getStringExtra(EXTRA_SERVER), intent.getStringExtra(EXTRA_HEADER))
        val playlistName = intent.getStringExtra(EXTRA_PLAYLIST) ?: DEFAULT_PLAYLIST
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 1f)
        val showClock = intent.getBooleanExtra(EXTRA_CLOCK, false)
        // Videos above Full HD: on if asked, otherwise only if the display itself goes above it.
        val allowUhd =
            if (intent.hasExtra(EXTRA_UHD)) intent.getBooleanExtra(EXTRA_UHD, true) else displayIsAboveFullHd()
        // Synced playback across devices: "leader" on one, the leader's address on the others.
        intent.getStringExtra(EXTRA_SYNC)?.let { sync ->
            startSynced(manifest, sync, volume, allowUhd, showClock)
            return
        }

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
            "Players: $players (uhd=$allowUhd, rotation=${intent.getIntExtra(EXTRA_ROTATION, 0)}, " +
                "fallback=${intent.getBooleanExtra(EXTRA_FALLBACK, false)})",
        )
        runners.forEach { it.start() }

        // Clockwise, for a screen mounted on its side: 0, 90, 180 or 270.
        val rotation = when (intent.getIntExtra(EXTRA_ROTATION, 0)) {
            90 -> GaplessRotation.Deg90
            180 -> GaplessRotation.Deg180
            270 -> GaplessRotation.Deg270
            else -> GaplessRotation.Deg0
        }
        val sideways = rotation == GaplessRotation.Deg90 || rotation == GaplessRotation.Deg270

        val videoConfig = GaplessVideoConfig(
            // true: a decoder that can't open falls back to a software one instead of failing.
            enableDecoderFallback = intent.getBooleanExtra(EXTRA_FALLBACK, false),
            // maxBufferMs >= the clip length so ExoPlayer can preload the next clip across the
            // whole slot instead of only its final few seconds.
            minBufferMs = 2_000,
            maxBufferMs = 12_000,
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    // On a sideways screen, players stacked top to bottom here read as side by
                    // side to the viewer.
                    val player: @Composable (PlaylistRunner, Modifier) -> Unit = { runner, modifier ->
                        GaplessPlayer(
                            modifier = modifier,
                            controller = runner.controller,
                            rotation = rotation,
                            videoConfig = videoConfig,
                        )
                    }
                    if (sideways) {
                        Column(Modifier.fillMaxSize()) {
                            runners.forEach { player(it, Modifier.weight(1f).fillMaxWidth()) }
                        }
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            runners.forEach { player(it, Modifier.weight(1f).fillMaxHeight()) }
                        }
                    }
                    if (showClock) DeviceClock()
                }
            }
        }
    }

    /**
     * Plays one player in step with other devices: [role] is "leader", which also picks the
     * playlist for everyone, or the leader's address. See [ClockSync] and [SyncedRunner].
     */
    private fun startSynced(
        manifest: SampleManifest,
        role: String,
        volume: Float,
        allowUhd: Boolean,
        showClock: Boolean,
    ) {
        val sync = if (role == "leader") {
            ClockSync.leader(resolvePlaylist(manifest, EXTRA_IDS, EXTRA_PLAYLIST, DEFAULT_PLAYLIST, volume, allowUhd).map { it.id })
        } else {
            ClockSync.follower(role)
        }
        // The leader's list as is: filtering it here could leave this device on a different schedule.
        val runner = SyncedRunner(
            lifecycleScope,
            sync,
            anchor = intent.getBooleanExtra(EXTRA_ANCHOR, true),
            lateMs = intent.getIntExtra(EXTRA_LATE_MS, 0).toLong(),
        ) { ids -> manifest.assets(ids, volume, allowUhd = true) }
        Log.d(TAG, "Synced playback as ${if (sync.isLeader) "leader" else "follower of $role"}")
        runner.start()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    GaplessPlayer(modifier = Modifier.fillMaxSize(), controller = runner.controller)
                    if (showClock) SharedClock(sync, runner)
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
 * The shared clock and the sync status, drawn over the player. With the devices side by side, a
 * photo shows whether their clocks and clips agree.
 */
@Composable
private fun SharedClock(sync: ClockSync, runner: SyncedRunner) {
    var ms by remember { mutableLongStateOf(sync.now()) }
    val status by runner.status.collectAsState()
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { ms = sync.now() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Text(
            text = "shared %d:%02d.%03d\n%s".format(ms / 60_000 % 60, ms / 1000 % 60, ms % 1000, status),
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
