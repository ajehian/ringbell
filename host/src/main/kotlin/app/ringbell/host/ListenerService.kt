package app.ringbell.host

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.ringbell.common.HttpStatusException
import app.ringbell.common.Incoming
import app.ringbell.common.Ntfy
import app.ringbell.common.RingEvent
import app.ringbell.common.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Foreground service that
 *  1. keeps a streaming connection to the ntfy topic and records incoming rings, and
 *  2. plays the alarm for as long as at least one ring is un-dismissed.
 */
class ListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var alarm: AlarmPlayer

    @Volatile private var running = false
    @Volatile private var sub: Subscription? = null
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        History.init(this)
        Notifs.createChannels(this)
        alarm = AlarmPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifs.listening(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifs.ID_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifs.ID_LISTEN, n)
        }

        if (!running) {
            running = true
            observeRings()
            thread = Thread({ listenLoop() }, "ring-listener").apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    /** Alarm + notification follow the history: ringing iff any entry is RINGING. */
    private fun observeRings() {
        scope.launch {
            History.entries
                .map { list -> list.filter { it.state == RingState.RINGING } }
                .distinctUntilChanged()
                .collect { active ->
                    if (active.isEmpty()) {
                        alarm.stop()
                        Notifs.cancelRing(this@ListenerService)
                    } else {
                        alarm.start()
                        Notifs.showRing(this@ListenerService, active)
                    }
                }
        }
    }

    private fun listenLoop() {
        val ntfy = Ntfy(Config.ring)
        val prefs = getSharedPreferences("host", Context.MODE_PRIVATE)
        var backoff = 2_000L

        while (running) {
            val s = Subscription()
            sub = s
            try {
                Conn.set(false, "Connecting…")
                ntfy.stream(
                    since = prefs.getString("last_id", null),
                    sub = s,
                    onOpen = {
                        backoff = 2_000L
                        Conn.set(true, "Listening")
                    },
                ) { inc ->
                    handle(inc)
                    if (inc.ntfyId.isNotEmpty()) prefs.edit().putString("last_id", inc.ntfyId).apply()
                }
                if (running) Conn.set(false, "Connection closed, reconnecting…")
            } catch (e: Exception) {
                if (!running) break
                if (e is HttpStatusException && e.code == 400) {
                    // The remembered message id is no longer valid on the server.
                    prefs.edit().remove("last_id").apply()
                }
                Conn.set(false, "Offline: ${e.message ?: e.javaClass.simpleName}")
            }
            if (!running) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = min(backoff * 2, 30_000L)
        }
    }

    private fun handle(inc: Incoming) {
        val ev = inc.event
        when (ev.type) {
            RingEvent.RING -> {
                // Replays after being offline must not wake the house with a stale ring.
                val stale = System.currentTimeMillis() - inc.timeMs > STALE_MS
                val added = History.addRing(
                    id = ev.id,
                    name = ev.name.ifBlank { "Unknown" },
                    at = inc.timeMs,
                    state = if (stale) RingState.MISSED else RingState.RINGING,
                )
                // Without this the sender's widget would show "ringing" for a ring nobody will ever dismiss.
                if (added && stale) Actions.ackMissed(ev.id)
            }
            RingEvent.CANCEL -> History.resolve(listOf(ev.id), RingState.CANCELLED)
            // DISMISSED events are our own echoes; nothing to do.
        }
    }

    override fun onDestroy() {
        running = false
        sub?.cancel()
        thread?.interrupt()
        scope.cancel()
        alarm.stop()
        Notifs.cancelRing(this)
        Conn.set(false, "Stopped")
        super.onDestroy()
    }

    companion object {
        private const val STALE_MS = 2 * 60_000L

        fun start(ctx: Context) {
            androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ListenerService::class.java))
        }
    }
}
