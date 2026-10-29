package com.aicreatorstudio.app.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Small, dependency-free formatting helpers shared by any screen that
 * displays video metadata (import screen today, project/timeline UI later).
 */
object Formatters {

    /** Formats a duration in milliseconds as `m:ss` or `h:mm:ss`. */
    fun duration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /** Formats a byte count as a human-readable size, e.g. `24.3 MB`. */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.lastIndex) {
            size /= 1024
            unitIndex++
        }
        val rounded = (size * 10).roundToInt() / 10.0
        return if (unitIndex == 0) "${rounded.toInt()} ${units[unitIndex]}" else "$rounded ${units[unitIndex]}"
    }

    /** Formats a pixel width/height pair, e.g. `1920 × 1080`. */
    fun resolution(width: Int, height: Int): String = "$width × $height"

    /** Formats an epoch millisecond timestamp as a short local date, e.g. `Aug 24, 2026`. */
    fun date(epochMillis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(epochMillis))
}
