package app.ringbell.common

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The only message format on the wire. Sent as the body of an ntfy message.
 *
 *  - RING       client -> host   "name" is ringing; [id] identifies this ring
 *  - CANCEL     client -> host   the ring with [id] is withdrawn
 *  - DISMISSED  host -> client   the rings listed in [ids] were dismissed
 *  - HEARTBEAT  host -> client   "a host is online"; sent every few minutes while a host is connected
 */
data class RingEvent(
    val type: String,
    val id: String = "",
    val name: String = "",
    val ts: Long = System.currentTimeMillis(),
    val ids: List<String> = emptyList(),
) {
    fun encode(): String = JSONObject().apply {
        put("t", type)
        put("id", id)
        put("name", name)
        put("ts", ts)
        if (ids.isNotEmpty()) put("ids", JSONArray(ids))
    }.toString()

    companion object {
        const val RING = "ring"
        const val CANCEL = "cancel"
        const val DISMISSED = "dismissed"
        const val HEARTBEAT = "hb"

        fun newId(): String = UUID.randomUUID().toString()

        /** Returns null for anything that is not one of our messages. */
        fun decode(s: String): RingEvent? = try {
            val o = JSONObject(s)
            val type = o.optString("t")
            if (type != RING && type != CANCEL && type != DISMISSED && type != HEARTBEAT) {
                null
            } else {
                val arr = o.optJSONArray("ids")
                val ids = if (arr == null) emptyList() else List(arr.length()) { arr.getString(it) }
                RingEvent(
                    type = type,
                    id = o.optString("id"),
                    name = o.optString("name"),
                    ts = o.optLong("ts"),
                    ids = ids,
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
