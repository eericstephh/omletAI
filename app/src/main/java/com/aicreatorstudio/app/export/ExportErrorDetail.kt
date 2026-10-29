package com.aicreatorstudio.app.export

import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException

/**
 * 2026-09-19 — surgical export-failure reporting.
 *
 * A bare `ExportException.message` (e.g. "Muxer error") never says
 * WHICH track or stage failed. This helper renders the full
 * diagnostic chain so one run pinpoints the culprit:
 *
 * ```
 * Export failed (code 4001) at ENCODING: Muxer error: ...
 *   caused by [1] MuxerException: Failed to write sample ...
 *   caused by [2] IllegalStateException: timestampUs -500 < lastTimestampUs 0
 * ```
 *
 * Pure Kotlin (no I/O, no threads) so it is JVM unit-tested; the
 * engine only formats strings with it.
 */
@UnstableApi
object ExportErrorDetail {

    /** One-line diagnosis for a finished-with-error export. */
    fun describe(
        error: Throwable,
        stage: String = "UNKNOWN"
    ): String {
        val sb = StringBuilder()
        sb.append("Export failed")
        val code = (error as? ExportException)?.errorCode
        if (code != null) sb.append(" (code ").append(code).append(')')
        sb.append(" at ").append(stage).append(": ")
        sb.append(error.message ?: error::class.java.simpleName)
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 5) {
            depth++
            sb.append(" | caused by [").append(depth).append("] ")
                .append(cause::class.java.simpleName).append(": ")
                .append(cause.message ?: "(no message)")
            cause = cause.cause
        }
        return sb.toString()
    }

    /**
     * Short track hint for muxer-family failures: Media3 reports
     * per-track write errors through the cause chain, so surfacing
     * the deepest message usually names the track ("audio",
     * "video", "timestampUs ...").
     */
    fun deepestMessage(error: Throwable): String {
        var current = error
        var depth = 0
        while (current.cause != null && depth < 8) {
            current = current.cause!!
            depth++
        }
        return current.message ?: current::class.java.simpleName
    }
}
