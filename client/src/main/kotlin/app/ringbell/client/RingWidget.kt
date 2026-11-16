package app.ringbell.client

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import app.ringbell.common.Ntfy
import app.ringbell.common.RingConfig
import app.ringbell.common.RingEvent

/** Home-screen widget: one tap rings the host with the saved name, without opening the app. */
class RingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    companion object {
        fun savedName(ctx: Context): String? =
            ctx.getSharedPreferences("client", Context.MODE_PRIVATE).getString("name", null)

        private fun buildViews(ctx: Context): RemoteViews {
            val name = savedName(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_ring)
            views.setTextViewText(R.id.widget_sub, if (name != null) "as $name" else "Tap to set name")

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

        /** Re-render every placed widget (e.g. after the name changes). */
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RingWidget::class.java))
            ids.forEach { mgr.updateAppWidget(it, buildViews(ctx)) }
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
            val msg = try {
                Ntfy(RingConfig(BuildConfig.RING_SERVER, BuildConfig.RING_TOPIC, BuildConfig.RING_TOKEN))
                    .publish(RingEvent(type = RingEvent.RING, id = RingEvent.newId(), name = name))
                "Rang the host as $name"
            } catch (e: Exception) {
                "Couldn't ring: ${e.message ?: "network error"}"
            }
            main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
            pending.finish()
        }.start()
    }
}
