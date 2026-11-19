package app.ringbell.host

import app.ringbell.common.Ntfy
import app.ringbell.common.RingEvent
import java.util.concurrent.Executors

/** User-driven operations on active rings. Safe to call from any thread. */
object Actions {
    private val io = Executors.newSingleThreadExecutor()

    fun dismiss(id: String) = dismiss(listOf(id))

    fun dismissAll() {
        dismiss(History.entries.value.filter { it.state == RingState.RINGING }.map { it.id })
    }

    private fun dismiss(ids: List<String>) {
        if (ids.isEmpty()) return
        // Local state first: the alarm stops immediately, even when offline.
        History.resolve(ids, RingState.DISMISSED)
        // Then tell the senders so they can no longer "cancel".
        publishDismissed(ids)
    }

    @Volatile private var lastBeat = 0L

    /**
     * Tells clients "a host is online". Called on connect and on every server keepalive,
     * but only publishes once per [HEARTBEAT_EVERY_MS] (or at once when [force]d).
     */
    fun heartbeat(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastBeat < HEARTBEAT_EVERY_MS) return
        lastBeat = now
        io.execute {
            try {
                Ntfy(Config.ring).publish(RingEvent(type = RingEvent.HEARTBEAT, name = "host"))
            } catch (_: Exception) {
                lastBeat = 0L // retry on the next keepalive
            }
        }
    }

    private const val HEARTBEAT_EVERY_MS = 4 * 60_000L

    private fun publishDismissed(ids: List<String>) {
        io.execute {
            val ev = RingEvent(type = RingEvent.DISMISSED, name = "host", ids = ids)
            repeat(3) { attempt ->
                try {
                    Ntfy(Config.ring).publish(ev)
                    return@execute
                } catch (_: Exception) {
                    try {
                        Thread.sleep(2000L * (attempt + 1))
                    } catch (_: InterruptedException) {
                        return@execute
                    }
                }
            }
        }
    }
}
