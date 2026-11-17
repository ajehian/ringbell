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

    /** A ring that arrived too late to sound: tell the sender it is not waiting any more. */
    fun ackMissed(id: String) = publishDismissed(listOf(id))

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
