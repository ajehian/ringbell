package app.ringbell.client

import android.content.Context
import app.ringbell.common.Ntfy
import app.ringbell.common.RingConfig
import app.ringbell.common.RingEvent

/**
 * Process-wide record of "the ring this device currently has active", shared by the
 * app screen, the home-screen widget and [RingWatchService] so they never double-ring
 * the host and always agree on the state.
 */
object RingSession {
    private const val KEY_ID = "active_id"
    private const val KEY_AT = "active_at"

    // A ring that is still not dismissed after this long is assumed lost (host offline / phone dead).
    const val EXPIRY_MS = 60 * 60_000L

    sealed interface Result {
        data class Sent(val id: String) : Result
        data class AlreadyRinging(val id: String) : Result
        data class Failed(val message: String) : Result
    }

    private val ntfy by lazy {
        Ntfy(RingConfig(BuildConfig.RING_SERVER, BuildConfig.RING_TOPIC, BuildConfig.RING_TOKEN))
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("client", Context.MODE_PRIVATE)

    /** Id of this device's live ring, or null if none (or it has expired). */
    fun activeId(ctx: Context): String? {
        val p = prefs(ctx)
        val id = p.getString(KEY_ID, null) ?: return null
        return if (System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > EXPIRY_MS) null else id
    }

    fun activeAt(ctx: Context): Long = prefs(ctx).getLong(KEY_AT, 0L)

    fun isActive(ctx: Context): Boolean = activeId(ctx) != null

    /**
     * Rings the host unless this device already has a live, undismissed ring.
     * Blocking (network). Synchronized so rapid taps are processed one at a time.
     */
    @Synchronized
    fun ringIfIdle(ctx: Context, name: String): Result {
        val app = ctx.applicationContext
        val p = prefs(app)
        val oldId = p.getString(KEY_ID, null)
        if (oldId != null) {
            val age = System.currentTimeMillis() - p.getLong(KEY_AT, 0L)
            if (age <= EXPIRY_MS) {
                val finished = try {
                    isFinished(oldId)
                } catch (e: Exception) {
                    return Result.Failed(e.message ?: "Network error")
                }
                if (!finished) {
                    RingWatchService.start(app) // make sure something is watching for the dismissal
                    RingWidget.refreshAll(app)
                    return Result.AlreadyRinging(oldId)
                }
            } else {
                try {
                    ntfy.publish(RingEvent(type = RingEvent.CANCEL, id = oldId))
                } catch (_: Exception) {
                }
            }
            forget(app)
        }

        val ev = RingEvent(type = RingEvent.RING, id = RingEvent.newId(), name = name)
        return try {
            ntfy.publish(ev)
            p.edit().putString(KEY_ID, ev.id).putLong(KEY_AT, System.currentTimeMillis()).apply()
            RingWatchService.start(app)
            RingWidget.refreshAll(app)
            Result.Sent(ev.id)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Network error")
        }
    }

    /** Forget the active ring if it is [id] (dismissed by the host, or cancelled by us). */
    fun clear(ctx: Context, id: String) {
        val app = ctx.applicationContext
        if (prefs(app).getString(KEY_ID, null) == id) forget(app)
    }

    private fun forget(app: Context) {
        prefs(app).edit().remove(KEY_ID).remove(KEY_AT).apply()
        RingWatchService.stop(app)
        RingWidget.refreshAll(app)
    }

    /** True if the host dismissed this ring, or it was cancelled. Throws if the check can't be made. */
    private fun isFinished(id: String): Boolean =
        ntfy.poll("2h").any { inc ->
            val e = inc.event
            (e.type == RingEvent.DISMISSED && id in e.ids) || (e.type == RingEvent.CANCEL && e.id == id)
        }
}
