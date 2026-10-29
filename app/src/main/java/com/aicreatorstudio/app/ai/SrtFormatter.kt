package com.aicreatorstudio.app.ai

import java.util.Locale

/**
 * SRT (SubRip) subtitle formatter.
 *
 * Produces standard `.srt` files:
 *
 * ```
 * 1
 * 00:00:01,000 --> 00:00:02,000
 * Hello everyone,
 *
 * 2
 * 00:00:02,000 --> 00:00:03,500
 * Welcome to my channel.
 * ```
 *
 * Time format is the spec-mandated `HH:MM:SS,mmm` (comma decimal
 * separator, NOT a period) regardless of the device locale.
 *
 * The output is plain UTF-8 text; Persian, Arabic, and any other
 * Unicode text passes through unchanged. Segments are written in
 * the order given, no internal IDs or debug fields are emitted.
 */
object SrtFormatter {

    /**
     * Convert a list of [Subtitle]s to a standard SRT-formatted string.
     *
     * Empty / whitespace-only segments are skipped. Negative or
     * inverted timestamps are clamped to a minimum of 0ms and the
     * end is forced to be at least as large as the start.
     */
    fun format(subtitles: List<Subtitle>): String {
        val sb = StringBuilder()
        var index = 1
        for (sub in subtitles) {
            val text = sub.text.trim()
            if (text.isEmpty()) continue
            val start = sub.startTimeMs.coerceAtLeast(0L)
            val end = sub.endTimeMs.coerceAtLeast(start + 1L)
            sb.append(index).append('\n')
            sb.append(formatTimestamp(start)).append(" --> ").append(formatTimestamp(end)).append('\n')
            sb.append(text).append('\n')
            sb.append('\n')
            index++
        }
        return sb.toString()
    }

    /**
     * Format a millisecond duration as `HH:MM:SS,mmm`.
     *
     * Uses an explicit Locale.ROOT so the digit shape (ASCII vs
     * Eastern Arabic) and the decimal separator are stable across
     * devices — SRT consumers are strict about that.
     */
    fun formatTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
