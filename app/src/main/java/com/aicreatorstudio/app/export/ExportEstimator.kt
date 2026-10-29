package com.aicreatorstudio.app.export

import java.util.Locale

/**
 * Pure export estimates for the quality picker (2026-10-05).
 *
 * The export dialog used to show only "higher resolutions take longer" —
 * no numbers. Since the quality tier's bitrate is actually applied to the
 * encoder (see Media3TransformerExportEngine's DefaultEncoderFactory wiring),
 * the file size is now predictable from real constants instead of guessed:
 *
 * ```
 * bytes ≈ (videoBitrateBps + audioBitrateBps) × durationSec / 8
 * ```
 *
 * This is an ESTIMATE: container overhead, keyframe spacing and the
 * encoder's rate control add a few percent. The UI must label it as
 * approximate ("≈"), never as an exact size.
 */
object ExportEstimator {

    /** Estimated output file size in bytes for [durationMs] at [quality]. */
    fun estimatedFileSizeBytes(durationMs: Long, quality: ExportQuality): Long {
        if (durationMs <= 0L) return 0L
        val totalBps = quality.videoBitrateBps.toLong() + quality.audioBitrateBps
        return totalBps * durationMs / 1000L / 8L
    }

    /** Human-readable size: "845 KB", "12.3 MB", "1.25 GB". */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes > 0L -> String.format(Locale.US, "%d KB", bytes / 1_024)
        else -> "—"
    }

    /** Human-readable duration: "0:08", "1:23", "12:05". */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "—"
        val totalSeconds = durationMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
