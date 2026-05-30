package io.github.gapless.sample

import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github._514sid.gapless.GaplessAsset
import io.github._514sid.gapless.GaplessEvent
import io.github._514sid.gapless.GaplessPlaylistManager
import io.github._514sid.gapless.GaplessPlayer
import io.github._514sid.gapless.GaplessRotation
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "GaplessPlayer"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isSustainedPerformanceModeSupported) {
            window.setSustainedPerformanceMode(true)
        }

        val assets = emptyList<GaplessAsset>()

        val manager = GaplessPlaylistManager(scope = lifecycleScope, preloadMs = 3_000)
        manager.start(assets)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.events.collect { event ->
                    when (event) {
                        is GaplessEvent.Started ->
                            Log.d(TAG, "Started: ${event.asset.id} [${event.playbackId}]")
                        is GaplessEvent.Ended ->
                            Log.d(TAG, "Ended: ${event.asset.id} [${event.playbackId}]")
                        is GaplessEvent.PlaybackError ->
                            Log.e(TAG, "Error on ${event.asset.id}: ${event.message}")
                        is GaplessEvent.Empty ->
                            Log.d(TAG, "Empty: no assets configured")
                        is GaplessEvent.Preloading ->
                            Log.d(TAG, "Preloading: ${event.asset.id}")
                        is GaplessEvent.Idle ->
                            Log.d(TAG, "Idle: no assets match current schedule")
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
                        emptyState = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No content",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        },
                        idleState = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Outside scheduled hours",
                                    color = Color.Gray,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
