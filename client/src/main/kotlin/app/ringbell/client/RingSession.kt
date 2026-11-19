package app.ringbell.client

import android.content.Context
import app.ringbell.common.Ntfy
import app.ringbell.common.RingConfig
import app.ringbell.common.RingEvent

/**
 * Process-wide record of "the ring this device currently has active", shared by the
 * app screen, the home-screen widget and [RingWatchService] so they never double-ring
 * the host and always agree on the state.
 *
 * A ring stays active until a host dismisses it or this device cancels it, however long
 * that takes (hosts may all be offline for hours or days).
 */
object RingSession {
    private const val KEY_ID = "active_id"
    private const val KEY_AT = "active_at"
    private const val KEY_PUB = "active_published_at"
    private const val KEY_OFFLINE = "active_hosts_offline"

    /** A heartbeat younger than this means a host is online (hosts beat every ~4 min). */
    const val HOST_FRESH_MS = 10 * 60_000L

    // The ntfy server only caches messages for a limited time (12 h on ntfy.sh). While a ring is
    // still pending we re-publish it now and then, whenever a host is seen, so it never expires.
    private const val RESEND_AFTER_MS = 60 * 60_000L

    sealed interface Result {
        data class Sent(val id: String, val hostsOnline: Boolean) : Result
        data class AlreadyRinging(val id: String, val hostsOnline: Boolean) : Result
        data class Failed(val message: String) : Result
    }

    private val ntfy by lazy {
        Ntfy(RingConfig(BuildConfig.RING_SERVER, BuildConfig.RING_TOPIC, BuildConfig.RING_TOKEN))
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("client", Context.MODE_PRIVATE)

    /** Id of this device's live ring, or null if none. */
    fun activeId(ctx: Context): String? = prefs(ctx).getString(KEY_ID, null)

    fun isActive(ctx: Context): Boolean = activeId(ctx) != null

    /** True while the active ring is waiting because no host was online when it was sent. */
    fun isQueuedOffline(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getString(KEY_ID, null) != null && p.getBoolean(KEY_OFFLINE, false)
    }

    /**
     * Rings the host unless this device already has an undismissed ring.
     * Blocking (network). Synchronized so rapid taps are processed one at a time.
     */
    @Synchronized
    fun ringIfIdle(ctx: Context, name: String): Result {
        val app = ctx.applicationContext
        val p = prefs(app)
        val oldId = p.getString(KEY_ID, null)
        if (oldId != null) {
            val finished = try {
                isFinished(oldId)
            } catch (e: Exception) {
                return Result.Failed(e.message ?: "Network error")
            }
            if (!finished) {
                RingWatchService.start(app) // make sure something is watching for the dismissal
                val online = checkHostsOnline(app)
                RingWidget.refreshAll(app)
                return Result.AlreadyRinging(oldId, online)
            }
            forget(app)
        }

        val ev = RingEvent(type = RingEvent.RING, id = RingEvent.newId(), name = name)
        return try {
            ntfy.publish(ev)
            val now = System.currentTimeMillis()
            p.edit().putString(KEY_ID, ev.id).putLong(KEY_AT, now).putLong(KEY_PUB, now)
                .putBoolean(KEY_OFFLINE, false).apply()
            val online = checkHostsOnline(app)
            RingWatchService.start(app)
            RingWidget.refreshAll(app)
            Result.Sent(ev.id, online)
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
        prefs(app).edit().remove(KEY_ID).remove(KEY_AT).remove(KEY_PUB).remove(KEY_OFFLINE).apply()
        RingWatchService.stop(app)
        RingWidget.refreshAll(app)
    }

    /** A host has just been seen online: the active ring is no longer waiting on an offline host. */
    fun hostSeen(ctx: Context) {
        val app = ctx.applicationContext
        val p = prefs(app)
        if (p.getBoolean(KEY_OFFLINE, false)) {
            p.edit().putBoolean(KEY_OFFLINE, false).apply()
            RingWidget.refreshAll(app)
        }
    }

    /** Re-publishes the pending ring [id] (same id, so hosts ignore duplicates) if it is getting old. */
    @Synchronized
    fun resendIfDue(ctx: Context, id: String) {
        val p = prefs(ctx)
        if (p.getString(KEY_ID, null) != id) return
        val name = p.getString("name", null) ?: return
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_PUB, 0L)
        if (now - last < RESEND_AFTER_MS) return
        try {
            ntfy.publish(RingEvent(type = RingEvent.RING, id = id, name = name))
            p.edit().putLong(KEY_PUB, now).apply()
        } catch (_: Exception) {
            // try again on the next heartbeat
        }
    }

    /** Looks for a recent host heartbeat and records the result. A failed check counts as "online". */
    private fun checkHostsOnline(app: Context): Boolean {
        val online = try {
            ntfy.poll("10m").any { it.event.type == RingEvent.HEARTBEAT }
        } catch (_: Exception) {
            true
        }
        prefs(app).edit().putBoolean(KEY_OFFLINE, !online).apply()
        return online
    }

    /** True if the host dismissed this ring, or it was cancelled. Throws if the check can't be made. */
    private fun isFinished(id: String): Boolean =
        ntfy.poll("all").any { inc ->
            val e = inc.event
            (e.type == RingEvent.DISMISSED && id in e.ids) || (e.type == RingEvent.CANCEL && e.id == id)
        }
}
