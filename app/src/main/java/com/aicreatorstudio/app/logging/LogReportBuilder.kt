package com.aicreatorstudio.app.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2026-10-12 — assembles the user-shareable diagnostic report.
 *
 * Pure Kotlin: takes the retained records + a device line and produces
 * the text body. Testable without Android, which matters because the
 * report is the one artefact that leaves the device and it must be
 * provably free of secrets and paths.
 *
 * ## What the report contains
 *
 * 1. A short header (generated timestamp, app version, device summary).
 * 2. A count summary by severity.
 * 3. The most recent error/warning, called out explicitly.
 * 4. The retained events, oldest → newest, as one line each.
 *
 * ## What it never contains
 *
 * No full media filenames, no subtitle text, no API keys, no absolute
 * paths — those are stripped by [LogSanitizer] *before* the record is
 * ever persisted, so this builder only ever sees sanitized data. The
 * builder additionally re-runs [LogSanitizer] as defence in depth.
 */
object LogReportBuilder {

    /** How many events a report carries (newest kept). */
    const val DEFAULT_MAX_EVENTS: Int = 800

    private val TIME_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"

    /**
     * Build the report body.
     *
     * @param records retained diagnostic events (any order).
     * @param deviceSummary one non-identifying line, e.g.
     *   `app=1.4.0 (4) android=35 abi=arm64-v8a device=…`.
     * @param generatedAtMillis wall clock for the header.
     */
    fun build(
        records: List<LogRecord>,
        deviceSummary: String,
        generatedAtMillis: Long,
        maxEvents: Int = DEFAULT_MAX_EVENTS,
        mediaSummary: String? = null
    ): String {
        val fmt = SimpleDateFormat(TIME_FORMAT, Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        val ordered = records.sortedBy { it.timestampMillis }
        val trimmed = if (ordered.size > maxEvents) ordered.takeLast(maxEvents) else ordered

        val errors = ordered.count { it.level == LogLevel.ERROR }
        val warnings = ordered.count { it.level == LogLevel.WARN }
        val latestProblem = ordered.lastOrNull { it.isProblem }

        return buildString {
            appendLine("Omelet — گزارش تشخیصی / Diagnostic report")
            appendLine("generated: ${fmt.format(Date(generatedAtMillis))}")
            appendLine("device: ${LogSanitizer.sanitize(deviceSummary)}")
            // 2026-10-20 — the device's encoder capabilities. The AI
            // pipeline picks its encoder, color format and output
            // size from these, so a report that says "the AI feature
            // does not work on this phone" is only actionable when it
            // also says what the phone can encode.
            if (!mediaSummary.isNullOrBlank()) {
                appendLine("media: ${LogSanitizer.sanitize(mediaSummary)}")
            }
            appendLine(
                "events: total=${ordered.size} error=$errors warn=$warnings " +
                    "retainedInReport=${trimmed.size}"
            )
            appendLine()
            if (latestProblem != null) {
                appendLine("آخرین خطا / Latest problem:")
                appendLine(
                    "  ${fmt.format(Date(latestProblem.timestampMillis))} " +
                        "[${latestProblem.level}] ${latestProblem.component}/" +
                        "${latestProblem.event}: ${LogSanitizer.sanitize(latestProblem.message)}" +
                        (latestProblem.throwable?.let { " | ${LogSanitizer.sanitize(it)}" } ?: "")
                )
                appendLine()
            }
            appendLine("---- events (oldest → newest) ----")
            for (r in trimmed) {
                appendLine(formatEvent(r, fmt))
            }
            appendLine("---- end ----")
        }
    }

    /** One compact line per event. Every field re-sanitized. */
    fun formatEvent(record: LogRecord, fmt: SimpleDateFormat = SimpleDateFormat(TIME_FORMAT, Locale.US)): String {
        val sb = StringBuilder(96)
        sb.append(fmt.format(Date(record.timestampMillis)))
            .append(' ')
            .append(record.level.name.padEnd(5))
            .append(' ')
            .append(LogSanitizer.sanitize(record.component))
            .append('/')
            .append(LogSanitizer.sanitize(record.event))
        record.correlationId?.let { sb.append(" [").append(LogSanitizer.sanitize(it)).append(']') }
        val msg = LogSanitizer.sanitize(record.message)
        if (msg.isNotBlank()) sb.append(": ").append(msg)
        if (record.fields.isNotEmpty()) {
            record.fields.toSortedMap().forEach { (k, v) ->
                sb.append(" | ").append(LogSanitizer.sanitize(k)).append('=')
                    .append(LogSanitizer.sanitize(v))
            }
        }
        record.throwable?.let { sb.append(" !! ").append(LogSanitizer.sanitize(it)) }
        return sb.toString()
    }
}
