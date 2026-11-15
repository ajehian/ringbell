package app.ringbell.common

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RingConfig(val server: String, val topic: String, val token: String = "") {
    val url: String get() = server.trimEnd('/') + "/" + topic
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** A handle so another thread can abort a blocking [Ntfy.stream]. */
class Subscription {
    @Volatile private var call: Call? = null

    @Volatile var cancelled: Boolean = false
        private set

    internal fun attach(c: Call) {
        call = c
        if (cancelled) c.cancel()
    }

    fun cancel() {
        cancelled = true
        call?.cancel()
    }
}

data class Incoming(val ntfyId: String, val timeMs: Long, val event: RingEvent)

/** Minimal ntfy client: publish a message, or follow a topic's JSON stream. */
class Ntfy(private val cfg: RingConfig) {

    private val publishClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    // ntfy sends a keepalive roughly every 45 s, so 90 s of silence means a dead link.
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun request(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (cfg.token.isNotBlank()) b.header("Authorization", "Bearer ${cfg.token}")
        return b
    }

    @Throws(IOException::class)
    fun publish(ev: RingEvent) {
        val body = ev.encode().toRequestBody("text/plain; charset=utf-8".toMediaType())
        val req = request(cfg.url).post(body).build()
        publishClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code)
        }
    }

    /**
     * Blocks, delivering every message on the topic until the stream ends,
     * fails (throws IOException) or [sub] is cancelled.
     *
     * [since]: null = only new messages; otherwise an ntfy message id,
     * unix timestamp or duration such as "10m".
     */
    @Throws(IOException::class)
    fun stream(
        since: String?,
        sub: Subscription,
        onOpen: () -> Unit,
        onMessage: (Incoming) -> Unit,
    ) {
        val url = cfg.url + "/json" + (if (since != null) "?since=$since" else "")
        val call = streamClient.newCall(request(url).build())
        sub.attach(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code)
            val source = (resp.body ?: throw IOException("Empty response")).source()
            onOpen()
            while (!sub.cancelled) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val o = try {
                    JSONObject(line)
                } catch (e: Exception) {
                    continue
                }
                if (o.optString("event") != "message") continue
                val ev = RingEvent.decode(o.optString("message")) ?: continue
                onMessage(Incoming(o.optString("id"), o.optLong("time") * 1000, ev))
            }
        }
    }
}
