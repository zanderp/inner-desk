package dev.zanderp.innerdesk

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anonymous install heartbeat + crash/error upload to the InnerDesk Cloudflare Worker.
 * Random UUID only. Opt-out: About → Privacy.
 */
object AnonymousTelemetry {
    private const val PREFS = "innerdesk_telemetry"
    private const val KEY_UUID = "anon_uuid"
    private const val KEY_LAST_PING_MS = "last_ping_ms"
    private const val KEY_CRASH_SENT = "crash_fingerprint_sent"
    private const val QUEUE_FILE = "telemetry_queue.json"
    private const val MAX_QUEUE = 24
    private const val MAX_PAYLOAD = 40_000
    private const val PING_INTERVAL_MS = 20L * 60L * 60L * 1000L
    private const val ERROR_COOLDOWN_MS = 30L * 60L * 1000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "innerdesk-telemetry").apply { isDaemon = true }
    }
    private val flushRunning = AtomicBoolean(false)
    @Volatile private var lastErrorEnqueueMs = 0L

    fun onAppStart(ctx: Context) {
        if (!PrivacyPrefs.telemetryEnabled(ctx)) return
        io.execute {
            try {
                ensureUuid(ctx)
                enqueuePendingCrash(ctx)
                maybeEnqueuePing(ctx)
                flush(ctx)
            } catch (_: Exception) {
            }
        }
    }

    fun reportError(ctx: Context, detail: String) {
        if (!PrivacyPrefs.telemetryEnabled(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastErrorEnqueueMs < ERROR_COOLDOWN_MS) return
        lastErrorEnqueueMs = now
        io.execute {
            try {
                enqueue(ctx, "error", redact(detail).take(2_000))
                flush(ctx)
            } catch (_: Exception) {
            }
        }
    }

    fun onDisabled(ctx: Context) {
        io.execute {
            try {
                queueFile(ctx).delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureUuid(ctx: Context): String {
        val p = prefs(ctx)
        val existing = p.getString(KEY_UUID, null)
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_UUID, id).apply()
        return id
    }

    private fun endpoint(): String {
        val base = BuildConfig.TELEMETRY_URL.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return "$base/v1/ping"
    }

    private fun maybeEnqueuePing(ctx: Context) {
        val last = prefs(ctx).getLong(KEY_LAST_PING_MS, 0L)
        if (System.currentTimeMillis() - last < PING_INTERVAL_MS) return
        enqueue(ctx, "ping", null)
    }

    private fun enqueuePendingCrash(ctx: Context) {
        val text = CrashGuard.pendingCrashText(ctx) ?: return
        val fp = fingerprint(text)
        if (prefs(ctx).getString(KEY_CRASH_SENT, null) == fp) return
        enqueue(ctx, "crash", redact(text))
        prefs(ctx).edit().putString(KEY_CRASH_SENT, fp).apply()
    }

    private fun fingerprint(text: String): String {
        val line = text.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?: text.take(120)
        return "${line.hashCode()}:${text.length}"
    }

    private fun queueFile(ctx: Context) = File(ctx.applicationContext.filesDir, QUEUE_FILE)

    @Synchronized
    private fun enqueue(ctx: Context, type: String, payload: String?) {
        val arr = readQueue(ctx)
        while (arr.length() >= MAX_QUEUE) arr.remove(0)
        val o = JSONObject()
        o.put("type", type)
        o.put("ts", System.currentTimeMillis())
        if (!payload.isNullOrBlank()) o.put("payload", payload.take(MAX_PAYLOAD))
        arr.put(o)
        writeQueue(ctx, arr)
    }

    private fun readQueue(ctx: Context): JSONArray {
        val f = queueFile(ctx)
        if (!f.exists() || f.length() == 0L) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeQueue(ctx: Context, arr: JSONArray) {
        try {
            queueFile(ctx).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun flush(ctx: Context) {
        if (!flushRunning.compareAndSet(false, true)) return
        try {
            if (!PrivacyPrefs.telemetryEnabled(ctx)) return
            val url = endpoint()
            if (url.isEmpty()) return
            val uuid = ensureUuid(ctx)
            val arr = readQueue(ctx)
            if (arr.length() == 0) return
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val ok = postOne(ctx, url, uuid, item)
                if (!ok) {
                    kept.put(item)
                    for (j in i + 1 until arr.length()) {
                        arr.optJSONObject(j)?.let { kept.put(it) }
                    }
                    break
                } else if (item.optString("type") == "ping") {
                    prefs(ctx).edit().putLong(KEY_LAST_PING_MS, System.currentTimeMillis()).apply()
                }
            }
            writeQueue(ctx, kept)
        } finally {
            flushRunning.set(false)
        }
    }

    private fun postOne(ctx: Context, url: String, uuid: String, item: JSONObject): Boolean {
        return try {
            val body = JSONObject()
            body.put("uuid", uuid)
            body.put("type", item.optString("type", "ping"))
            body.put("version", BuildConfig.VERSION_NAME)
            body.put("versionCode", BuildConfig.VERSION_CODE)
            body.put("androidSdk", Build.VERSION.SDK_INT)
            body.put("locale", Locale.getDefault().toLanguageTag())
            val payload = item.optString("payload", "")
            if (payload.isNotBlank()) body.put("payload", payload)
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 12_000
            conn.readTimeout = 20_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("User-Agent", "InnerDesk/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun redact(text: String): String {
        var s = text
        s = s.replace(Regex("""(?i)(pwd|password|passphrase|pin)\s*[=:]\s*\S+"""), "$1=***")
        s = s.replace(Regex("""\b\d{6}\b"""), "******")
        return s.take(MAX_PAYLOAD)
    }
}
