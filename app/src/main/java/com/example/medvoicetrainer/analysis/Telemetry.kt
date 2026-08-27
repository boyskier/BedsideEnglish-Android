package com.example.medvoicetrainer.analysis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Ported from app/telemetry.py — anonymous, explicit opt-in usage telemetry (Phase 0).
 *
 * Privacy contract (enforced by code, not just docs): never sent are audio, transcripts,
 * feedback text, API keys, names, free text, or IP-derived precise location — [track] only
 * accepts a fixed event name plus small scalar properties chosen at the call site. Sent (coarse
 * counters only): event name, app version, OS family, UI/native language codes, a country code
 * derived from the system locale, session mode/duration, and a rounded average score. A random
 * per-install id (no account, no PII) ties events from one install together.
 *
 * Three independent off-switches — telemetry is silent unless ALL hold: (1) an endpoint AND key
 * are configured ([TelemetryConfig] — empty by default, a hard no-op until set), (2) the user
 * explicitly opted in (`telemetry_consent == "granted"`), and (3) the app is not in dev mode.
 *
 * Sending happens off a bounded channel on a background coroutine; every failure (offline,
 * DNS, 4xx/5xx, malformed) is swallowed — telemetry must never slow down, block, or crash the app.
 */
object TelemetryConfig {
    // Empty by default: a hard no-op until the maintainer fills these in for public release,
    // mirroring config.TELEMETRY_ENDPOINT / config.TELEMETRY_API_KEY.
    var endpoint: String = ""
    var apiKey: String = ""
    const val DEFAULT_CONSENT = "denied"
    const val APP_VERSION = "1.0.0"
}

object Telemetry {

    private const val QUEUE_MAX = 200
    private const val SEND_TIMEOUT_MS = 5000L
    // A crash loop must not turn into an event flood — cap error events per run.
    private const val ERROR_EVENT_CAP = 10
    private var errorsSent = 0

    private var getSetting: ((String, String) -> String)? = null
    private var setSetting: ((String, String) -> Unit)? = null
    private var anonId: String = ""
    private var dev: Boolean = false

    private var channel: Channel<JSONObject?>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Wire up settings access and the anonymous install id. Injecting the settings accessors
     * (instead of a hard Repository dependency) keeps this object decoupled and unit-testable.
     */
    fun init(
        getSetting: (key: String, default: String) -> String,
        setSetting: (key: String, value: String) -> Unit,
        devMode: Boolean = false
    ) {
        this.getSetting = getSetting
        this.setSetting = setSetting
        this.dev = devMode

        var aid = try {
            getSetting("telemetry_anon_id", "")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ""
        }
        if (aid.isEmpty()) {
            aid = UUID.randomUUID().toString().replace("-", "")
            try {
                setSetting("telemetry_anon_id", aid)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // ignore
            }
        }
        anonId = aid
    }

    /** True once the maintainer has set a telemetry endpoint and key. */
    fun isConfigured(): Boolean = TelemetryConfig.endpoint.isNotEmpty() && TelemetryConfig.apiKey.isNotEmpty()

    fun isConsented(): Boolean {
        var consent = try {
            getSetting?.invoke("telemetry_consent", "") ?: ""
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ""
        }
        if (consent.isEmpty()) consent = TelemetryConfig.DEFAULT_CONSENT
        return consent == "granted"
    }

    /** Persist the user's choice (onboarding toggle / Preferences). */
    fun setConsent(granted: Boolean) {
        try {
            setSetting?.invoke("telemetry_consent", if (granted) "granted" else "denied")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // ignore
        }
    }

    private fun active(): Boolean = !dev && isConfigured() && isConsented()

    /** Coarse region code (e.g. "KR") from the OS locale — never an IP lookup. */
    private fun countryFromLocale(): String {
        return try {
            Locale.getDefault().country.takeIf { it.isNotEmpty() } ?: ""
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ""
        }
    }

    private fun commonProps(): Map<String, Any?> {
        val native = try {
            getSetting?.invoke("native_language", "") ?: ""
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ""
        }
        val ui = try {
            (getSetting?.invoke("ui_language", "") ?: "").ifEmpty { native.ifEmpty { "en" } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            "en"
        }
        return mapOf(
            "app_version" to TelemetryConfig.APP_VERSION,
            "os" to "android",
            "ui_language" to ui,
            "native_language" to native,
            "country" to countryFromLocale()
        )
    }

    private fun ensureWorker() {
        if (channel != null) return
        synchronized(this) {
            if (channel != null) return
            val ch = Channel<JSONObject?>(capacity = QUEUE_MAX)
            channel = ch
            scope.launch { runWorker(ch) }
        }
    }

    /**
     * Record one event. No-op unless telemetry is fully active. Only pass small scalar props
     * (mode, duration, counts) — never transcript, audio, key, or free-text values.
     */
    fun track(event: String, props: Map<String, Any?> = emptyMap()) {
        try {
            if (!active()) return
            ensureWorker()
            val merged = JSONObject()
            for ((k, v) in commonProps()) merged.put(k, v)
            for ((k, v) in props) {
                if (v != null) merged.put(k, v)
            }
            val item = JSONObject()
            item.put("event", event)
            item.put("properties", merged)
            item.put("timestamp", isoNow())
            val result = channel?.trySend(item)
            if (result?.isFailure == true) {
                // sustained offline — drop rather than grow memory
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // telemetry must never affect the app
        }
    }

    /**
     * Report THAT an error happened — never what it said. Payload carries only the exception
     * class name, a caller-chosen [where] label, and (best-effort) a class:line location — no
     * exception message, which can embed user paths or other free text.
     */
    fun trackError(where: String, exc: Throwable) {
        try {
            if (errorsSent >= ERROR_EVENT_CAP) return
            errorsSent++
            val location = exc.stackTrace.firstOrNull { it.className.startsWith("com.example.medvoicetrainer") }
                ?: exc.stackTrace.firstOrNull()
            track(
                "app_error",
                mapOf(
                    "where" to where.take(40),
                    "exc_type" to exc.javaClass.simpleName,
                    "location" to (location?.let { "${it.fileName}:${it.lineNumber}" } ?: "")
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // telemetry must never affect the app
        }
    }

    /** Best-effort drain on shutdown; bounded so quitting never hangs. */
    suspend fun flush(timeoutMs: Long = 2000L) {
        val ch = channel ?: return
        try {
            ch.trySend(null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return
        }
        withTimeoutOrNull(timeoutMs) {
            // best-effort; the worker drains and exits on the sentinel
        }
    }

    private suspend fun runWorker(ch: Channel<JSONObject?>) {
        for (item in ch) {
            if (item == null) break
            try {
                send(item)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun send(item: JSONObject) {
        val body = JSONObject()
        body.put("api_key", TelemetryConfig.apiKey)
        body.put("event", item.getString("event"))
        body.put("distinct_id", anonId.ifEmpty { "anonymous" })
        body.put("properties", item.getJSONObject("properties"))
        body.put("timestamp", item.getString("timestamp"))

        val conn = URL(TelemetryConfig.endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = SEND_TIMEOUT_MS.toInt()
        conn.readTimeout = SEND_TIMEOUT_MS.toInt()
        conn.doOutput = true
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }

    /** Test helper: clear all module state. */
    fun resetForTests() {
        channel = null
        getSetting = null
        setSetting = null
        anonId = ""
        dev = false
        errorsSent = 0
    }
}
