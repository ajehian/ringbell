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

    /** Set by the activity when it stops for a configuration change, so the re-start does not ring. */
    @Volatile var skipNextStart = false

    /** True while the active ring is queued because no host is online right now. */
    private val _hostsOffline = MutableStateFlow(false)
    val hostsOffline: StateFlow<Boolean> = _hostsOffline

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
     * Called each time the app comes to the foreground. With no name yet it only shows
     * the setup screen; once a name is saved, every open rings the host, unless a ring
     * from this device (for example from the widget) is already active, in which case
     * the screen simply adopts it.
     */
    fun onFreshLaunch() {
        if (_name.value != null) ring()
    }

    fun ring() {
        val n = _name.value ?: return
        stopListening()
        _hostsOffline.value = false
        _status.value = Status.Sending
        io.execute {
            val r = RingSession.ringIfIdle(getApplication<Application>(), n)
            val id = when (r) {
                is RingSession.Result.Sent -> r.id
                is RingSession.Result.AlreadyRinging -> r.id
                is RingSession.Result.Failed -> null
            }
            when (r) {
                is RingSession.Result.Sent -> _hostsOffline.value = !r.hostsOnline
                is RingSession.Result.AlreadyRinging -> _hostsOffline.value = !r.hostsOnline
                is RingSession.Result.Failed -> {}
            }
            if (id != null) {
                ringId = id // set even if the user already pressed Cancel, so that cancel targets it
                if (_status.value is Status.Sending) {
                    _status.value = Status.Ringing
                    listenForDismiss(id)
                }
            } else if (_status.value is Status.Sending) {
                _status.value = Status.Failed((r as RingSession.Result.Failed).message)
            }
        }
    }

    /** Withdraw the ring. Only possible while the host has not dismissed it. */
    fun cancel() {
        val s = _status.value
        val retry = s is Status.Failed && s.whileCancelling
        if (s !is Status.Sending && s !is Status.Ringing && !retry) return

        stopListening()
        _hostsOffline.value = false
        _status.value = Status.Cancelled
        io.execute {
            // Runs after any in-flight ring() on this same thread, so ringId is up to date.
            val id = ringId ?: return@execute
            try {
                ntfy.publish(RingEvent(type = RingEvent.CANCEL, id = id))
                RingSession.clear(getApplication<Application>(), id)
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
    private fun listenForDismiss(id: String) {
        val s = Subscription()
        sub = s
        Thread {
            var delayMs = 1000L
            while (!s.cancelled) {
                try {
                    // The ring may be days old, so replay everything the server still has.
                    ntfy.stream("all", s, onOpen = { delayMs = 1000L }) { inc ->
                        if (inc.event.type == RingEvent.HEARTBEAT &&
                            System.currentTimeMillis() - inc.timeMs < RingSession.HOST_FRESH_MS
                        ) {
                            _hostsOffline.value = false
                            RingSession.hostSeen(getApplication<Application>())
                        }
                        if (inc.event.type == RingEvent.DISMISSED && id in inc.event.ids) {
                            val cur = _status.value
                            if (ringId == id && (cur is Status.Ringing || cur is Status.Sending)) {
                                _status.value = Status.Dismissed
                            }
                            RingSession.clear(getApplication<Application>(), id)
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
