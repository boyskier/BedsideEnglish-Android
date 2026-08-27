package com.example.medvoicetrainer.analysis

import com.example.medvoicetrainer.api.ApiRequestException
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Ported from app/ui/error_dialog.py — friendly handling for unexpected UI errors, so a failure
 * inside a user action shows something reassuring instead of silently doing nothing (the "silent
 * funnel leak" LAUNCH_PLAN.md warns about) and goes untelemetered.
 *
 * Android's process model doesn't support Python/Tkinter's full trick of "catch any callback
 * exception, log it, and keep the whole app running" — a genuinely uncaught exception on Android
 * tears the process down; there is no safe way to resume from an arbitrary point after that (see
 * MainActivity's Thread.setDefaultUncaughtExceptionHandler, which covers that case: log +
 * telemetry + delegate to the system default handler, since the process is dying regardless).
 * What IS portable is the actual bug class the Python docstring describes — a UI action's code
 * throws and the tap appears to do nothing. MainViewModel.startSession() (the single highest-
 * traffic "tap something, expect a session to start" entry point) wraps its body and routes
 * failures here instead of crashing or silently no-opping.
 */
object ErrorDialog {

    // Matches config.py's GITHUB_REPO default — no Kotlin equivalent of that env-overridable
    // constant exists yet, so the default is hardcoded here.
    const val GITHUB_REPO = "boyskier/BedsideEnglish-Android"

    // §13 "Invalid / expired API key": which provider's key was implicated, so the dialog's
    // "Open Preferences" route can land on (and focus) that exact field instead of the screen top.
    data class Content(val headline: String, val body: String, val detail: String, val provider: String? = null)

    private data class ShownKey(val excType: String, val location: String)

    // Unique (exception class, location) pairs already shown this run — a repeating error in a
    // polling/retry loop must not stack an infinite pile of dialogs.
    private val shown = mutableSetOf<ShownKey>()

    // Fully-qualified marker classes for each provider's request path (API services + Live/
    // Realtime voice clients) — whichever one appears in the throwable's stack (walking causes
    // too, since a network/parsing failure often surfaces wrapped) tells us who threw it.
    private val PROVIDER_MARKERS = listOf(
        "gemini" to listOf("com.example.medvoicetrainer.api.GeminiService", "com.example.medvoicetrainer.voice.GeminiLiveClient"),
        "openai" to listOf("com.example.medvoicetrainer.api.OpenAIService", "com.example.medvoicetrainer.voice.OpenAIRealtimeClient"),
        "claude" to listOf("com.example.medvoicetrainer.api.ClaudeService")
    )

    private fun inferProvider(throwable: Throwable): String? {
        var current: Throwable? = throwable
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            val classNames = current.stackTrace.map { it.className }
            for ((provider, markers) in PROVIDER_MARKERS) {
                if (markers.any { marker -> classNames.any { it == marker } }) return provider
            }
            current = current.cause
        }
        return null
    }

    const val REDACTED = "[redacted]"

    /**
     * Supplies the credentials currently configured on this install, so [redactSecrets] can strip
     * them out of a stack trace. A provider (rather than a snapshot) keeps this in step with keys
     * the learner edits mid-run, and keeps ErrorDialog free of a Repository dependency — see
     * MainViewModel's init. Left null in unit tests, where pattern redaction still applies.
     */
    private var secretProvider: (() -> Collection<String>)? = null

    fun installSecretProvider(provider: () -> Collection<String>) {
        secretProvider = provider
    }

    // Recognizable credential shapes, as a backstop for secrets the provider doesn't know about
    // (a key held only in a local variable, or a provider added later without touching this file).
    private val SECRET_PATTERNS = listOf(
        Regex("""AIza[0-9A-Za-z_\-]{10,}"""),          // Google API keys
        Regex("""sk-ant-[0-9A-Za-z_\-]{10,}"""),        // Anthropic
        Regex("""sk-(?:proj-)?[0-9A-Za-z_\-]{20,}"""),  // OpenAI
        Regex("""(?i)(key|api[_-]?key|token|secret)=[^&\s"']+""")
    )

    /**
     * Strip credentials out of text that is about to be shown — and, per [handle]'s body copy,
     * pasted into a public issue tracker. The exact configured keys go first (longest first, so a
     * key that contains another as a substring can't be half-redacted), then the shape patterns.
     */
    internal fun redactSecrets(text: String): String {
        var out = text
        val secrets = try {
            secretProvider?.invoke() ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            emptyList()
        }
        for (secret in secrets.filter { it.length >= 8 }.sortedByDescending { it.length }) {
            out = out.replace(secret, REDACTED)
        }
        for (pattern in SECRET_PATTERNS) {
            out = pattern.replace(out) { match ->
                // Keep the `key=` style prefix so the reader can still see *which* parameter it was.
                val eq = match.value.indexOf('=')
                if (eq >= 0) match.value.substring(0, eq + 1) + REDACTED else REDACTED
            }
        }
        return out
    }

    /** Log + count (via the caller) + build dialog content — but only once per unique
     * (exception class, location) pair this run. Returns null for a repeat. */
    fun handle(source: String, throwable: Throwable): Content? {
        val location = throwable.stackTrace
            .firstOrNull { it.className.startsWith("com.example.medvoicetrainer") }
            ?: throwable.stackTrace.firstOrNull()
        val key = ShownKey(throwable.javaClass.simpleName, location?.let { "${it.fileName}:${it.lineNumber}" } ?: "")
        if (!shown.add(key)) return null

        // Provider failures are expected operational conditions, not app crashes. In particular,
        // never put a provider response/JSON payload in the copyable diagnostics section.
        if (throwable is ApiRequestException) {
            return Content(
                headline = "AI service couldn't complete the request",
                body = throwable.message.orEmpty(),
                detail = "Provider: ${throwable.provider}\nStatus: ${throwable.statusCode ?: "unknown"}\nCategory: ${throwable.kind}",
                provider = throwable.provider.lowercase().substringBefore(' ')
            )
        }

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return Content(
            headline = "Something went wrong — your work is safe",
            body = "Bedside English hit an unexpected error while $source. Session transcripts are " +
                "saved as they arrive, so nothing is lost and you can keep using the app.\n\n" +
                "If this keeps happening, please copy the details below and report it — that's how it gets fixed.",
            // An exception message can embed the request URL or a credential; this dialog's detail
            // is copyable and its body invites posting it publicly, so redact before it is shown.
            detail = redactSecrets(sw.toString().trim()),
            provider = inferProvider(throwable)
        )
    }

    fun resetForTests() {
        shown.clear()
        secretProvider = null
    }
}
