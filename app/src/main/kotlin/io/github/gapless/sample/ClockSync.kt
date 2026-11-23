package io.github.gapless.sample

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * What the devices of a synced session play: [ids] in a loop, the first slot starting at
 * [startMs] on the shared clock.
 */
data class SyncSession(val startMs: Long, val ids: List<String>)

/**
 * A clock shared by the devices of a synced session, kept over UDP on the LAN.
 *
 * The leader's monotonic clock is the shared clock. Each follower asks the leader for its time
 * every second or so, NTP style: it notes when it sent the request and when the reply came back,
 * and takes the leader's time as having been read halfway between. Of the recent exchanges it
 * keeps the one with the shortest round trip, the least delayed by the network. Replies also
 * carry the leader's [SyncSession], so a follower plays whatever the leader was launched with.
 *
 * Monotonic clocks are used because the wall clock can jump when the device syncs its time.
 */
class ClockSync private constructor(
    private val leaderHost: String?,
    leaderSession: SyncSession?,
) {
    val isLeader: Boolean get() = leaderHost == null

    private val _session = MutableStateFlow(leaderSession)

    /** The session to play: set at once on the leader, after the first reply on a follower. */
    val session: StateFlow<SyncSession?> = _session.asStateFlow()

    // Shared clock minus local monotonic clock, in nanoseconds. Zero on the leader.
    @Volatile private var offsetNanos = 0L
    @Volatile private var synced = leaderHost == null

    /** The round trip of the exchange the offset came from, in milliseconds; 0 on the leader. */
    @Volatile var rttMs: Double = 0.0
        private set

    /** Whether the clock has been measured at least once (always true on the leader). */
    val isSynced: Boolean get() = synced

    /** The shared clock now, in milliseconds. */
    fun now(): Long = (SystemClock.elapsedRealtimeNanos() + offsetNanos) / 1_000_000

    /** The offset from this device's clock to the shared one, in milliseconds. */
    val offsetMs: Double get() = offsetNanos / 1e6

    /** Converts a wall-clock time from this device, such as an event timestamp, to the shared clock. */
    fun fromWallClock(wallMs: Long): Long =
        wallMs - System.currentTimeMillis() + SystemClock.elapsedRealtime() + offsetNanos / 1_000_000

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { if (isLeader) serve() else poll(leaderHost!!) }
    }

    private suspend fun serve() {
        val session = _session.value!!
        val tail = " ${session.startMs} ${session.ids.joinToString(",")}"
        DatagramSocket(PORT).use { socket ->
            Log.i(TAG, "Leader on UDP $PORT, session starts at ${session.startMs}: ${session.ids}")
            val buf = ByteArray(MAX_PACKET)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val request = String(packet.data, 0, packet.length).split(' ')
                if (request.size != 2 || request[0] != "T") continue
                // Read the clock as late as possible, right before replying.
                val reply = "R ${request[1]} ${SystemClock.elapsedRealtimeNanos()}$tail".toByteArray()
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }
    }

    private suspend fun poll(host: String) {
        val leader = InetAddress.getByName(host)
        val recent = ArrayDeque<Pair<Long, Long>>() // (round trip, offset), in nanoseconds
        var sent = 0
        DatagramSocket().use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MS
            val buf = ByteArray(MAX_PACKET)
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val request = "T $t0".toByteArray()
                socket.send(DatagramPacket(request, request.size, leader, PORT))
                sent++
                try {
                    while (true) {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val t3 = SystemClock.elapsedRealtimeNanos()
                        val parts = String(packet.data, 0, packet.length).split(' ')
                        // A late reply to an earlier request would give a wrong round trip.
                        if (parts.size < 4 || parts[0] != "R" || parts[1].toLong() != t0) continue
                        val t1 = parts[2].toLong()
                        recent.addLast((t3 - t0) to (t1 - (t0 + t3) / 2))
                        if (recent.size > SAMPLES) recent.removeFirst()
                        val best = recent.minBy { it.first }
                        offsetNanos = best.second
                        rttMs = best.first / 1e6
                        synced = true
                        val session = SyncSession(parts[3].toLong(), parts.getOrNull(4)?.split(',').orEmpty())
                        if (_session.value != session) {
                            Log.i(TAG, "Session from $host starts at ${session.startMs}: ${session.ids}")
                            _session.value = session
                        }
                        break
                    }
                } catch (_: SocketTimeoutException) {
                    Log.w(TAG, "No reply from leader $host")
                }
                // Measure quickly at first, then once a second to follow the clocks' drift.
                delay(if (sent < FAST_SAMPLES) 100 else 1_000)
            }
        }
    }

    companion object {
        private const val TAG = "GaplessSync"
        const val PORT = 47800
        private const val MAX_PACKET = 8 * 1024
        private const val REPLY_TIMEOUT_MS = 500
        private const val SAMPLES = 30
        private const val FAST_SAMPLES = 20

        /** Leads a session of [ids], starting [leadMs] from now. */
        fun leader(ids: List<String>, leadMs: Long = 5_000): ClockSync =
            ClockSync(null, SyncSession(SystemClock.elapsedRealtime() + leadMs, ids))

        /** Follows the leader at [host]. */
        fun follower(host: String): ClockSync = ClockSync(host, null)
    }
}
