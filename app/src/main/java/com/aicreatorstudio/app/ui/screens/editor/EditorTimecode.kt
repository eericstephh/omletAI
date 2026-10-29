package com.aicreatorstudio.app.ui.screens.editor

/**
 * High-precision timecode utilities for the editor.
 *
 *  - [mmSsMs]     `MM:SS.mmm`  — used in the playback strip and timecode HUD.
 *  - [mmSs]       `MM:SS`      — used in compact badges and clip labels.
 *  - [hhMmSsMs]   `HH:MM:SS.mmm` — used when the project is over an hour.
 *
 * Formatters are pure and allocation-free for hot paths; they're called on
 * every animation frame to update the timecode, so they avoid creating
 * intermediate objects.
 */
object EditorTimecode {

    /** Formats a duration as `MM:SS.mmm` (zero-padded, always shows ms). */
    fun mmSsMs(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = (safe / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = (safe % 1000L).toInt()
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    /** Formats a duration as `MM:SS` — for compact readouts. */
    fun mmSs(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = (safe / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    /** Formats a duration as `H:MM:SS.mmm` once we cross an hour. */
    fun hhMmSsMs(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = (safe / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = (safe % 1000L).toInt()
        return "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    /** Picker for the right formatter for a given duration. */
    fun smart(ms: Long): String =
        if (ms >= 3_600_000L) hhMmSsMs(ms) else mmSsMs(ms)
}
