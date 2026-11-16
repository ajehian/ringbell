package app.ringbell.client

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import app.ringbell.common.Ntfy
import app.ringbell.common.RingConfig
import app.ringbell.common.RingEvent
import app.ringbell.common.Subscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.math.min

sealed interface Status {
    data object Idle : Status
    data object Sending : Status
    data object Ringing : Status
    data object Dismissed : Status
    data object Cancelled : Status
    data class Failed(val message: String, val whileCancelling: Boolean = false) : Status
}

class RingViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("client", Context.MODE_PRIVATE)
    private val ntfy = Ntfy(RingConfig(BuildConfig.RING_SERVER, BuildConfig.RING_TOPIC, BuildConfig.RING_TOKEN))

    // One thread, so a "cancel" can never overtake the "ring" it refers to.
    private val io = Executors.newSingleThreadExecutor()

    private val _name = MutableStateFlow(prefs.getString("name", null))
    val name: StateFlow<String?> = _name

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    @Volatile private var ringId: String? = null
    @Volatile private var sub: Subscription? = null

    fun saveName(raw: String) {
        val n = raw.trim()
        if (n.isEmpty()) return
        prefs.edit().putString("name", n).apply()
        _name.value = n
        RingWidget.refreshAll(getApplication<Application>())
    }

    /**
     * Called once per real app launch. The very first launch (no name yet) only
     * shows the setup screen; every later launch rings the host.
     */
    fun onFreshLaunch() {
        if (_name.value != null) ring()
    }

    fun ring() {
        val n = _name.value ?: return
        stopListening()
        val ev = RingEvent(type = RingEvent.RING, id = RingEvent.newId(), name = n)
        ringId = ev.id
        _status.value = Status.Sending
        io.execute {
            try {
                ntfy.publish(ev)
                if (ringId == ev.id && _status.value is Status.Sending) {
                    _status.value = Status.Ringing
                    listenForDismiss(ev)
                }
            } catch (e: Exception) {
                if (ringId == ev.id && _status.value is Status.Sending) {
                    _status.value = Status.Failed(e.message ?: "Network error")
                }
            }
        }
    }

    /** Withdraw the ring. Only possible while the host has not dismissed it. */
    fun cancel() {
        val id = ringId ?: return
        val s = _status.value
        val retry = s is Status.Failed && s.whileCancelling
        if (s !is Status.Sending && s !is Status.Ringing && !retry) return

        stopListening()
        _status.value = Status.Cancelled
        io.execute {
            try {
                ntfy.publish(RingEvent(type = RingEvent.CANCEL, id = id))
            } catch (e: Exception) {
                if (ringId == id) _status.value = Status.Failed(e.message ?: "Network error", whileCancelling = true)
            }
        }
    }

    private fun stopListening() {
        sub?.cancel()
        sub = null
    }

    /** Follows the topic until the host reports this ring as dismissed. */
    private fun listenForDismiss(ev: RingEvent) {
        val s = Subscription()
        sub = s
        Thread {
            var delayMs = 1000L
            while (!s.cancelled) {
                try {
                    // "10m" replay window is independent of clock differences between phone and server.
                    ntfy.stream("10m", s, onOpen = { delayMs = 1000L }) { inc ->
                        if (inc.event.type == RingEvent.DISMISSED && ev.id in inc.event.ids) {
                            val cur = _status.value
                            if (ringId == ev.id && (cur is Status.Ringing || cur is Status.Sending)) {
                                _status.value = Status.Dismissed
                            }
                            s.cancel()
                        }
                    }
                } catch (_: Exception) {
                    // fall through to retry
                }
                if (s.cancelled) break
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    break
                }
                delayMs = min(delayMs * 2, 15_000L)
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onCleared() {
        stopListening()
        io.shutdown() // lets an already queued publish finish
    }
}
