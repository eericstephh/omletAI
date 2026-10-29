package com.aicreatorstudio.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure (JVM-testable) crash-log logic behind [CrashReporter].
 *
 * 2026-10-05 — the old reporter overwrote a single file per crash, so a
 * second crash destroyed all evidence of the first. This adds a capped
 * rolling history: every crash is APPENDED to `crash_history.txt` with a
 * timestamped header; when the file grows past [MAX_HISTORY_BYTES] the
 * OLDEST half is dropped, so the newest crashes always survive and the
 * file stays bounded.
 */
object CrashLog {

    /** 256 KB — roughly 100+ full stack traces; small enough to share. */
    const val MAX_HISTORY_BYTES: Int = 256 * 1024

    const val ENTRY_SEPARATOR: String =
        "================================================================\n"

    /**
     * Builds one history entry. Pure string shaping — no I/O — so tests
     * can assert the header fields without touching a filesystem.
     */
    fun buildEntry(
        timestampMillis: Long,
        appVersion: String,
        device: String,
        threadName: String?,
        throwable: Throwable,
        dateFormat: SimpleDateFormat = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS", Locale.US
        )
    ): String {
        val writer = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(writer))
        return buildString {
            append(ENTRY_SEPARATOR)
            append("CRASH @ ").append(dateFormat.format(Date(timestampMillis))).append('\n')
            append("app: ").append(appVersion).append('\n')
            append("device: ").append(device).append('\n')
            append("thread: ").append(threadName ?: "?").append('\n')
            append(writer.toString())
            if (!endsWith("\n")) append('\n')
        }
    }

    /**
     * Appends [entry] to [historyFile], keeping the file under
     * [maxBytes]. The NEWEST entry is always preserved in full — even
     * when it alone exceeds the cap — because the newest crash is the
     * one the user will be asked to share. Older content is trimmed
     * from the front, cutting at the next [ENTRY_SEPARATOR] so a stack
     * trace is never sliced mid-entry.
     */
    fun appendCapped(historyFile: File, entry: String, maxBytes: Long = MAX_HISTORY_BYTES.toLong()) {
        val existing = runCatching { historyFile.readText() }.getOrDefault("")
        var combined = existing + entry
        if (combined.length > maxBytes) {
            combined = if (entry.length >= maxBytes) {
                // The newest crash alone overflows the cap: keep ONLY it.
                entry
            } else {
                val keepFrom = (combined.length - maxBytes).toInt().coerceAtLeast(0)
                val nextSep = combined.indexOf(ENTRY_SEPARATOR, keepFrom)
                if (nextSep >= 0) combined.substring(nextSep) else combined.substring(keepFrom)
            }
        }
        historyFile.writeText(combined)
    }
}
