package app.ringbell.client

import android.content.Context
import app.ringbell.common.Ntfy
import app.ringbell.common.RingConfig
import app.ringbell.common.RingEvent

/**
 * Process-wide record of "the ring this device currently has active", shared by the
 * app screen and the home-screen widget so they never double-ring the host.
 */
object RingSession {
    private const val KEY_ID = "active_id"
    private const val KEY_AT = "active_at"

    // After this long without a dismissal we assume the ring was missed (host offline) and allow a fresh one.
    private const val EXPIRY_MS = 10 * 60_000L

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

    /**
     * Rings the host unless this device already has a live, undismissed ring.
     * Blocking (network). Synchronized so rapid taps are processed one at a time.
     */
    @Synchronized
    fun ringIfIdle(ctx: Context, name: String): Result {
        val p = prefs(ctx)
        val oldId = p.getString(KEY_ID, null)
        if (oldId != null) {
            val age = System.currentTimeMillis() - p.getLong(KEY_AT, 0L)
            if (age <= EXPIRY_MS) {
                val finished = try {
                    isFinished(oldId)
                } catch (e: Exception) {
                    return Result.Failed(e.message ?: "Network error")
                }
                if (!finished) return Result.AlreadyRinging(oldId)
            } else {
                try {
                    ntfy.publish(RingEvent(type = RingEvent.CANCEL, id = oldId))
                } catch (_: Exception) {
                }
            }
            p.edit().remove(KEY_ID).remove(KEY_AT).apply()
        }

        val ev = RingEvent(type = RingEvent.RING, id = RingEvent.newId(), name = name)
        return try {
            ntfy.publish(ev)
            p.edit().putString(KEY_ID, ev.id).putLong(KEY_AT, System.currentTimeMillis()).apply()
            Result.Sent(ev.id)
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Network error")
        }
    }

    /** Forget the active ring (dismissed by the host, or cancelled by us). */
    fun clear(ctx: Context, id: String) {
        val p = prefs(ctx)
        if (p.getString(KEY_ID, null) == id) p.edit().remove(KEY_ID).remove(KEY_AT).apply()
    }

    /** True if the host dismissed this ring, or it was cancelled. Throws if the check can't be made. */
    private fun isFinished(id: String): Boolean =
        ntfy.poll("15m").any { inc ->
            val e = inc.event
            (e.type == RingEvent.DISMISSED && id in e.ids) || (e.type == RingEvent.CANCEL && e.id == id)
        }
}
