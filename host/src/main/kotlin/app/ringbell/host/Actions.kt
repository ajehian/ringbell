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

    /** Answers a client's "is any host online?" ping. */
    fun pong(pingId: String) {
        io.execute {
            try {
                Ntfy(Config.ring).publish(RingEvent(type = RingEvent.PONG, id = pingId, name = "host"))
            } catch (_: Exception) {
            }
        }
    }

    @Volatile private var lastHello = 0L

    /**
     * Announces that this host just came online, so a client with a ring still pending can
     * make sure it is on the server. Rate-limited so a flaky connection can't flood the topic.
     */
    fun hello() {
        val now = System.currentTimeMillis()
        if (now - lastHello < HELLO_MIN_GAP_MS) return
        lastHello = now
        io.execute {
            try {
                Ntfy(Config.ring).publish(RingEvent(type = RingEvent.HELLO, name = "host"))
            } catch (_: Exception) {
                lastHello = 0L // retry on the next connect
            }
        }
    }

    private const val HELLO_MIN_GAP_MS = 10 * 60_000L

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
