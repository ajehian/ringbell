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
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast

/**
 * Home-screen widget: one tap rings the host without opening the app.
 *  - blue, icon only  : nothing active
 *  - yellow "Ringing" : this device's ring is active; stays until the host dismisses it
 *  - red "Failed"     : the tap could not reach the host (shown briefly)
 */
class RingWidget : AppWidgetProvider() {

    enum class Feedback(val label: String, val background: Int, val foreground: Int) {
        RINGING("Ringing", R.drawable.widget_bg_warn, 0xFF212121.toInt()),
        FAILED("Failed", R.drawable.widget_bg_err, 0xFFFFFFFF.toInt()),
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context, current(context))) }
    }

    companion object {
        fun savedName(ctx: Context): String? =
            ctx.getSharedPreferences("client", Context.MODE_PRIVATE).getString("name", null)

        private fun current(ctx: Context): Feedback? =
            if (RingSession.isActive(ctx)) Feedback.RINGING else null

        private fun buildViews(ctx: Context, fb: Feedback?): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_ring)

            if (fb == null) {
                views.setViewVisibility(R.id.widget_sub, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_sub, View.VISIBLE)
                views.setTextViewText(R.id.widget_sub, fb.label)
                views.setTextColor(R.id.widget_sub, fb.foreground)
            }
            views.setInt(R.id.widget_root, "setBackgroundResource", fb?.background ?: R.drawable.widget_bg)
            views.setInt(R.id.widget_icon, "setColorFilter", fb?.foreground ?: 0xFFFFFFFF.toInt())

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val tap = if (savedName(ctx) != null) {
                PendingIntent.getBroadcast(ctx, 0, Intent(ctx, RingActionReceiver::class.java), flags)
            } else {
                // No name yet: open the app so it can be set up.
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
            }
            views.setOnClickPendingIntent(R.id.widget_root, tap)
            return views
        }

        /** Re-render every placed widget from the shared ring state, or from [override]. */
        fun refreshAll(ctx: Context, override: Feedback? = null) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, RingWidget::class.java))
            if (ids.isEmpty()) return
            val fb = override ?: current(app)
            ids.forEach { mgr.updateAppWidget(it, buildViews(app, fb)) }
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
                buzz(app) // instant confirmation that the tap registered
                RingWidget.refreshAll(app, RingWidget.Feedback.RINGING) // optimistic yellow

                val msg = try {
                    when (val r = RingSession.ringIfIdle(app, name)) {
                        is RingSession.Result.Sent -> "Ringing the host as $name"
                        is RingSession.Result.AlreadyRinging -> "Already ringing. Open the app to cancel."
                        is RingSession.Result.Failed -> {
                            failed(app)
                            "Couldn't ring: ${r.message}"
                        }
                    }
                } catch (e: Exception) {
                    failed(app)
                    "Couldn't ring: ${e.message ?: "error"}"
                }
                main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() } // extra; the OS may suppress it
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Red flash, then back to whatever the real state is (blue, or yellow if a ring is still active). */
    private fun failed(app: Context) {
        buzz(app)
        RingWidget.refreshAll(app, RingWidget.Feedback.FAILED)
        Thread.sleep(2500)
        RingWidget.refreshAll(app)
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
