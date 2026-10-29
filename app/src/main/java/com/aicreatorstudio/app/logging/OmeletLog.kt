package com.aicreatorstudio.app.logging

import android.util.Log
import com.aicreatorstudio.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-10-12 — the single entry point the rest of the app logs through.
 *
 * ## Relationship to existing infrastructure
 *
 * The project already had three overlapping channels: raw
 * `android.util.Log` calls, an `ExportDiagnostics` object, and a
 * `WhisperDiagnostics` object. Rather than adding yet another logging
 * *framework*, this is a thin **facade** in front of them:
 *
 * - Logcat output is preserved (developers keep their existing habit),
 *   but now filtered by [minLevel] so release builds are quiet.
 * - The same call additionally persists a **structured, sanitized**
 *   record to [OmeletLogStore] so the diagnostics screen and the shared
 *   report have real data instead of empty files.
 * - `ExportDiagnostics` / `WhisperDiagnostics` keep their public shape
 *   and simply forward here; their export/transcription behaviour is
 *   untouched.
 *
 * ## Release policy
 *
 * [minLevel] is `DEBUG` in debug builds and `WARN` in release builds,
 * so verbose logging is off for users unless enabled through the safe
 * internal flag [setVerboseForTesting]. Warnings and errors are always
 * available for user-triggered diagnostics.
 *
 * ## Thread safety
 *
 * Every method is safe to call from any thread, including native
 * callbacks. Sanitization is pure and synchronous (cheap); the disk
 * write is delegated to [OmeletLogStore]'s writer coroutine.
 */
@Singleton
class OmeletLog @Inject constructor(
    private val store: OmeletLogStore
) {

    /**
     * Minimum severity that gets recorded. Debug builds keep DEBUG;
     * release builds keep only WARN and above.
     */
    @Volatile
    var minLevel: LogLevel = LogLevel.defaultMinimum(BuildConfig.DEBUG)
        private set

    /** Sensitive literals the caller has registered (never persisted). */
    @Volatile
    private var extraSecrets: List<String> = emptyList()

    /**
     * Registers literal strings that must never reach disk (e.g. a key
     * read into memory). Kept in memory only.
     */
    fun registerSecret(secret: String?) {
        if (secret.isNullOrBlank()) return
        val current = extraSecrets
        if (current.any { it == secret }) return
        extraSecrets = current + secret
    }

    /**
     * 2026-10-12 — internal override used by tests and by a future
     * "verbose diagnostics" developer toggle. Kept deliberate and
     * explicit: nothing in the shipping UI calls this.
     */
    fun setVerboseForTesting(verbose: Boolean) {
        minLevel = if (verbose) LogLevel.DEBUG else LogLevel.defaultMinimum(BuildConfig.DEBUG)
    }

    // ---- Public logging API ------------------------------------------------------

    fun debug(component: String, event: String, message: String = "", correlationId: String? = null, fields: Map<String, String> = emptyMap()) =
        log(LogLevel.DEBUG, component, event, message, correlationId, fields, null)

    fun info(component: String, event: String, message: String = "", correlationId: String? = null, fields: Map<String, String> = emptyMap()) =
        log(LogLevel.INFO, component, event, message, correlationId, fields, null)

    fun warn(component: String, event: String, message: String = "", correlationId: String? = null, fields: Map<String, String> = emptyMap(), throwable: Throwable? = null) =
        log(LogLevel.WARN, component, event, message, correlationId, fields, throwable)

    fun error(component: String, event: String, message: String = "", correlationId: String? = null, fields: Map<String, String> = emptyMap(), throwable: Throwable? = null) =
        log(LogLevel.ERROR, component, event, message, correlationId, fields, throwable)

    /**
     * 2026-10-13 — the uncaught-crash write, called while the process is
     * dying. Everything the normal path does, except the store write is
     * synchronous ([OmeletLogStore.appendNow]) instead of queued, so the
     * record survives the teardown that follows it.
     *
     * Best-effort by contract: the logger must never turn a crash into a
     * second crash, so every failure inside is swallowed.
     */
    fun errorBlocking(
        component: String,
        event: String,
        message: String = "",
        throwable: Throwable? = null,
        /**
         * 2026-10-20 — the character budget for [message].
         *
         * The crash handler raises it: a stack trace is the only thing
         * that says WHERE a crash happened, and the default 500-char
         * budget truncated the frames away, leaving a report that said
         * "NullPointerException" and nothing more.
         */
        maxMessageChars: Int = LogSanitizer.MAX_FIELD_CHARS
    ) {
        try {
            val record = LogRecord(
                timestampMillis = System.currentTimeMillis(),
                level = LogLevel.ERROR,
                component = component,
                event = event,
                correlationId = null,
                message = LogSanitizer.sanitize(
                    message,
                    extraSecrets,
                    maxChars = maxMessageChars
                ),
                fields = emptyMap(),
                throwable = LogSanitizer.describeThrowable(throwable)
            )
            echoToLogcat(record)
            store.appendNow(LogFormat.toLine(record))
        } catch (_: Throwable) {
            // Logging must never take the app down.
        }
    }

    /**
     * Core write path. Declared `internal` so tests can drive it with a
     * fixed clock/dispatcher-free store fake.
     */
    internal fun log(
        level: LogLevel,
        component: String,
        event: String,
        message: String,
        correlationId: String?,
        fields: Map<String, String>,
        throwable: Throwable?
    ) {
        if (!LogLevel.isEnabled(level, minLevel)) return
        try {
            val record = LogRecord(
                timestampMillis = System.currentTimeMillis(),
                level = level,
                component = component,
                event = event,
                correlationId = correlationId,
                message = LogSanitizer.sanitize(message, extraSecrets),
                fields = LogSanitizer.sanitizeFields(fields),
                throwable = LogSanitizer.describeThrowable(throwable)
            )
            echoToLogcat(record)
            store.append(LogFormat.toLine(record))
        } catch (_: Throwable) {
            // Logging must never take the app down. Do not recursively
            // log here.
        }
    }

    /** Mirrors the record to logcat at the matching priority. */
    private fun echoToLogcat(record: LogRecord) {
        val tag = "Omelet/${record.component}"
        val text = buildString {
            append(record.event)
            if (record.correlationId != null) append(" [").append(record.correlationId).append(']')
            if (record.message.isNotBlank()) append(": ").append(record.message)
            if (record.throwable != null) append(" | ").append(record.throwable)
        }
        when (record.level) {
            LogLevel.DEBUG -> Log.d(tag, text)
            LogLevel.INFO -> Log.i(tag, text)
            LogLevel.WARN -> Log.w(tag, text)
            LogLevel.ERROR -> Log.e(tag, text)
        }
    }
}
