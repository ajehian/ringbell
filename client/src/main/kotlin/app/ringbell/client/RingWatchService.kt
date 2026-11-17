package app.ringbell.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.ringbell.common.Ntfy
import app.ringbell.common.RingConfig
import app.ringbell.common.RingEvent
import app.ringbell.common.Subscription
import kotlin.math.min

/**
 * Runs only while a ring this device sent is still active. It listens for the host's
 * "dismissed" message so the widget can go back to normal the moment the host dismisses,
 * even if the app is closed. Stops itself as soon as the ring is finished.
 */
class RingWatchService : Service() {

    @Volatile private var running = false
    @Volatile private var sub: Subscription? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    // Safety net: if the ring is never dismissed, stop waiting when it expires.
    private val expire = Runnable {
        RingWidget.refreshAll(this)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Waiting for host", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Ringing the host")
            .setContentText("Waiting for the host to dismiss it")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }

        val activeId = RingSession.activeId(this)
        if (activeId == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val remaining = RingSession.EXPIRY_MS - (System.currentTimeMillis() - RingSession.activeAt(this))
        main.removeCallbacks(expire)
        main.postDelayed(expire, remaining.coerceAtLeast(0L))

        if (!running) {
            running = true
            thread = Thread({ watch() }, "ring-watch").apply { isDaemon = true; start() }
        }
        return START_NOT_STICKY
    }

    private fun watch() {
        val ntfy = Ntfy(RingConfig(BuildConfig.RING_SERVER, BuildConfig.RING_TOPIC, BuildConfig.RING_TOKEN))
        var backoff = 2_000L
        while (running) {
            val id = RingSession.activeId(this) ?: break
            val s = Subscription()
            sub = s
            try {
                ntfy.stream("all", s, onOpen = { backoff = 2_000L }) { inc ->
                    val e = inc.event
                    if ((e.type == RingEvent.DISMISSED && id in e.ids) || (e.type == RingEvent.CANCEL && e.id == id)) {
                        RingSession.clear(this, id) // also stops this service and refreshes the widget
                        s.cancel()
                    }
                }
            } catch (_: Exception) {
                // reconnect below
            }
            if (!running || s.cancelled) break
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = min(backoff * 2, 30_000L)
        }
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        sub?.cancel()
        thread?.interrupt()
        main.removeCallbacks(expire)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "watch"
        private const val ID = 10

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RingWatchService::class.java))
            } catch (_: Exception) {
                // Not allowed to start right now; the widget re-checks on its next tap / app open.
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, RingWatchService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
