package app.ringbell.client

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.RemoteViews
import android.widget.Toast

/** Home-screen widget: one tap rings the host with the saved name, without opening the app. */
class RingWidget : AppWidgetProvider() {

    /** Temporary look shown on the widget right after a tap. */
    enum class Feedback(val label: String, val background: Int) {
        SENDING("Ringing…", R.drawable.widget_bg),
        SENT("Sent ✓", R.drawable.widget_bg_ok),
        ACTIVE("Ringing", R.drawable.widget_bg_warn),
        FAILED("Failed", R.drawable.widget_bg_err),
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context, null)) }
    }

    companion object {
        fun savedName(ctx: Context): String? =
            ctx.getSharedPreferences("client", Context.MODE_PRIVATE).getString("name", null)

        private fun buildViews(ctx: Context, feedback: Feedback?): RemoteViews {
            val name = savedName(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_ring)
            views.setTextViewText(R.id.widget_sub, feedback?.label ?: (name ?: "Set name"))
            views.setInt(R.id.widget_root, "setBackgroundResource", feedback?.background ?: R.drawable.widget_bg)

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val tap = if (name != null) {
                PendingIntent.getBroadcast(ctx, 0, Intent(ctx, RingActionReceiver::class.java), flags)
            } else {
                // No name yet: open the app so it can be set up.
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
            }
            views.setOnClickPendingIntent(R.id.widget_root, tap)
            return views
        }

        /** Re-render every placed widget; [feedback] null = normal look. */
        fun refreshAll(ctx: Context, feedback: Feedback? = null) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RingWidget::class.java))
            ids.forEach { mgr.updateAppWidget(it, buildViews(ctx, feedback)) }
        }
    }
}

class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val name = RingWidget.savedName(app) ?: return
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                // Instant feedback that the tap registered (works even if toasts are blocked).
                buzz(app)
                RingWidget.refreshAll(app, RingWidget.Feedback.SENDING)

                val (fb, msg) = try {
                    when (val r = RingSession.ringIfIdle(app, name)) {
                        is RingSession.Result.Sent ->
                            RingWidget.Feedback.SENT to "Ringing the host as $name"
                        is RingSession.Result.AlreadyRinging ->
                            RingWidget.Feedback.ACTIVE to "Already ringing. Open the app to cancel."
                        is RingSession.Result.Failed ->
                            RingWidget.Feedback.FAILED to "Couldn't ring: ${r.message}"
                    }
                } catch (e: Exception) {
                    RingWidget.Feedback.FAILED to "Couldn't ring: ${e.message ?: "error"}"
                }

                RingWidget.refreshAll(app, fb)
                main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() } // extra, may be suppressed by the OS
                if (fb == RingWidget.Feedback.FAILED) buzz(app)

                Thread.sleep(2500)
                RingWidget.refreshAll(app, null)
            } catch (_: Exception) {
                RingWidget.refreshAll(app, null)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun buzz(ctx: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}
