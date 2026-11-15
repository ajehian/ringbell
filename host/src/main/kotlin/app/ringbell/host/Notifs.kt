package app.ringbell.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifs {
    private const val CH_LISTEN = "listen"
    private const val CH_RING = "ring"
    const val ID_LISTEN = 1
    private const val ID_RING = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "Listening for rings", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Persistent notification that keeps Ring Host connected"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RING, "Incoming rings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while someone is ringing"
                setSound(null, null) // the alarm is played by AlarmPlayer, not by the channel
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun listening(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("Ring Host is listening")
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun showRing(ctx: Context, active: List<Entry>) {
        val names = active.joinToString(", ") { it.name }
        val dismiss = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, DismissReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = openApp(ctx)
        val n = NotificationCompat.Builder(ctx, CH_RING)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("$names is ringing")
            .setContentText(if (active.size > 1) "${active.size} rings waiting" else "Tap to open")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Dismiss", dismiss)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_RING, n)
        } catch (_: SecurityException) {
            // notification permission missing; the alarm still sounds
        }
    }

    fun cancelRing(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_RING)
    }
}
