package io.github.gapless.sample

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

// Minimal keepalive service in the :player process.
// Gives WatchdogService (main process) a Binder to hold so the :player process
// stays alive, and onServiceDisconnected fires when the process dies.
class PlayerService : Service() {
    override fun onBind(intent: Intent): IBinder = Binder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
