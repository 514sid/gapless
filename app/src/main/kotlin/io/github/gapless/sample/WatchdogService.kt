package io.github.gapless.sample

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

// Runs in the main process. Holds a binding to PlayerService (:player process).
// onServiceDisconnected fires when :player crashes → auto-restarts the player.
class WatchdogService : Service() {
    companion object {
        private const val TAG = "WatchdogService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "watchdog"
    }

    private val playerConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, ":player process connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, ":player process died — restarting")
            launchPlayer()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Player watchdog", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gapless Player")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        launchPlayer()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(playerConnection) }
    }

    private fun launchPlayer() {
        startService(Intent(this, PlayerService::class.java))
        bindService(Intent(this, PlayerService::class.java), playerConnection, BIND_AUTO_CREATE)
        // FLAG_ACTIVITY_NEW_TASK required from non-Activity context.
        // On Android 12+ the OS may suppress background activity starts; in that case
        // tapping the persistent notification brings the player back.
        startActivity(Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
