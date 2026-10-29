package com.aicreatorstudio.app.logging

/**
 * 2026-10-12 — the pure, framework-free half of the Omelet logging
 * system.
 *
 * Everything in this file is plain Kotlin with **no Android imports**,
 * so the whole severity/policy/redaction/formatting layer is testable
 * on the JVM. The Android-facing writer lives in [OmeletLogStore]; the
 * user-facing facade is [OmeletLog].
 *
 * ## Why a separate pure layer
 *
 * The project's test stack is JUnit4 + kotlinx-coroutines-test + json
 * only (no Mockito / Robolectric), and
 * `unitTests.isReturnDefaultValues = true` makes `android.*` inert in
 * unit tests — an `android.util.Log` call would silently return 0 and a
 * test could pass for the wrong reason. Keeping the decisions here
 * (what to log, what to redact, which files to prune, when to rotate)
 * means those decisions get **behavioral** tests instead of source-text
 * assertions.
 */

/** Severity of a single diagnostic event. Order is meaningful. */
enum class LogLevel(val rank: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3);

    companion object {
        /** Parses a persisted level name; unknown → [INFO]. */
        fun fromName(name: String?): LogLevel =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: INFO

        /**
         * The minimum severity a build should record.
         *
         * Debug builds keep everything; release builds keep only WARN
         * and above, so verbose DEBUG logging is off for users unless a
         * safe internal flag later lifts it. This is pure so the release
         * policy is directly testable.
         */
        fun defaultMinimum(isDebugBuild: Boolean): LogLevel =
            if (isDebugBuild) DEBUG else WARN

        /** True when [level] passes the [minimum] threshold. */
        fun isEnabled(level: LogLevel, minimum: LogLevel): Boolean =
            level.rank >= minimum.rank
    }
}

/**
 * One structured diagnostic event.
 *
 * Deliberately includes a [correlationId] so an export run, a Whisper
 * initialization and its inference windows can be threaded together
 * even though they execute on different dispatchers.
 */
data class LogRecord(
    val timestampMillis: Long,
    val level: LogLevel,
    /** Subsystem that produced the event, e.g. `export` or `whisper`. */
    val component: String,
    /** Stable machine-readable event name, e.g. `export_started`. */
    val event: String,
    /** Optional run/operation id linking related events. */
    val correlationId: String? = null,
    /** Short, already-sanitized human-readable detail. */
    val message: String = "",
    /** Extra already-sanitized key/value context. Never raw user data. */
    val fields: Map<String, String> = emptyMap(),
    /** Sanitized exception summary — class name + sanitized message. */
    val throwable: String? = null
) {
    /** True when the record is at or above [WARN]. */
    val isProblem: Boolean get() = level.rank >= LogLevel.WARN.rank
}
