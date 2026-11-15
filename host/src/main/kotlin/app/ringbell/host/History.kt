package app.ringbell.host

import android.content.Context
import app.ringbell.common.RingConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Config {
    val ring = RingConfig(BuildConfig.RING_SERVER, BuildConfig.RING_TOPIC, BuildConfig.RING_TOKEN)
}

enum class RingState { RINGING, DISMISSED, CANCELLED, MISSED }

data class Entry(
    val id: String,
    val name: String,
    val at: Long,
    val state: RingState,
    val resolvedAt: Long? = null,
)

/** Connection status shown in the UI. */
data class ConnState(val connected: Boolean, val text: String)

object Conn {
    private val _state = MutableStateFlow(ConnState(false, "Starting…"))
    val state: StateFlow<ConnState> = _state
    fun set(connected: Boolean, text: String) {
        _state.value = ConnState(connected, text)
    }
}

/** Ring history, newest first, persisted to a small JSON file. */
object History {
    private const val MAX = 500
    private var file: File? = null
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "history.json")
        file = f
        _entries.value = load(f)
    }

    /** Adds a ring; returns false if this id is already known (duplicate delivery). */
    @Synchronized
    fun addRing(id: String, name: String, at: Long, state: RingState): Boolean {
        if (_entries.value.any { it.id == id }) return false
        val resolvedAt = if (state == RingState.MISSED) System.currentTimeMillis() else null
        val list = (listOf(Entry(id, name, at, state, resolvedAt)) + _entries.value)
            .sortedByDescending { it.at }
            .take(MAX)
        publish(list)
        return true
    }

    /** Moves currently-ringing entries (all, or only [ids]) to [to]. */
    @Synchronized
    fun resolve(ids: Collection<String>?, to: RingState) {
        val now = System.currentTimeMillis()
        var changed = false
        val list = _entries.value.map {
            if (it.state == RingState.RINGING && (ids == null || it.id in ids)) {
                changed = true
                it.copy(state = to, resolvedAt = now)
            } else {
                it
            }
        }
        if (changed) publish(list)
    }

    @Synchronized
    fun clearResolved() {
        publish(_entries.value.filter { it.state == RingState.RINGING })
    }

    private fun publish(list: List<Entry>) {
        _entries.value = list
        save(list)
    }

    private fun save(list: List<Entry>) {
        val f = file ?: return
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("at", it.at)
                    put("state", it.state.name)
                    if (it.resolvedAt != null) put("resolvedAt", it.resolvedAt)
                })
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) f.writeText(arr.toString())
        } catch (_: Exception) {
            // history is best-effort
        }
    }

    private fun load(f: File): List<Entry> = try {
        if (!f.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    at = o.getLong("at"),
                    state = RingState.valueOf(o.getString("state")),
                    resolvedAt = if (o.has("resolvedAt")) o.getLong("resolvedAt") else null,
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
