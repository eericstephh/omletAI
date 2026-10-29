package com.aicreatorstudio.app.logging

import org.json.JSONObject

/**
 * 2026-10-12 — JSON Lines codec + file rotation/pruning policy.
 *
 * Pure Kotlin (plus `org.json`, which the JVM test classpath provides
 * at the same version Android ships) so the persisted format and the
 * retention rules are covered by behavioral tests.
 *
 * ## Format
 *
 * One compact JSON object per line. JSON Lines survives a partially
 * written trailing line (a kill mid-write leaves a truncated last
 * line, and every complete line before it is still parseable) — which
 * is exactly the "process restarts and partial log files" requirement.
 *
 * ## Keys
 *
 * Short keys keep the files small: `ts`, `lvl`, `c` (component),
 * `e` (event), `cid` (correlation id), `msg`, `x` (extra fields),
 * `t` (throwable).
 */
object LogFormat {

    /** Byte cap for a single log file before rotation. */
    const val DEFAULT_MAX_FILE_BYTES: Long = 512L * 1024L

    /** Number of rotated files kept (plus the active one). */
    const val DEFAULT_MAX_FILES: Int = 4

    /** Total byte budget across all log files before oldest are pruned. */
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 2L * 1024L * 1024L

    /** Serializes one record to a single compact JSON line (no newline). */
    fun toLine(record: LogRecord): String {
        val obj = JSONObject()
        obj.put("ts", record.timestampMillis)
        obj.put("lvl", record.level.name)
        obj.put("c", record.component)
        obj.put("e", record.event)
        record.correlationId?.let { obj.put("cid", it) }
        if (record.message.isNotBlank()) obj.put("msg", record.message)
        if (record.fields.isNotEmpty()) {
            val x = JSONObject()
            // Sorted for stable output — makes tests deterministic and
            // diffing two reports trivial.
            record.fields.toSortedMap().forEach { (k, v) -> x.put(k, v) }
            obj.put("x", x)
        }
        record.throwable?.let { obj.put("t", it) }
        return obj.toString()
    }

    /**
     * Parses one JSONL line back into a [LogRecord].
     *
     * Returns `null` for a blank line or a truncated/corrupt line
     * instead of throwing — a half-written trailing line after a crash
     * must never break the whole read.
     */
    fun fromLine(line: String): LogRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.first() != '{') return null
        return runCatching {
            val obj = JSONObject(trimmed)
            val fields = LinkedHashMap<String, String>()
            obj.optJSONObject("x")?.let { x ->
                for (key in x.keys()) fields[key] = x.optString(key)
            }
            LogRecord(
                timestampMillis = obj.optLong("ts", 0L),
                level = LogLevel.fromName(obj.optString("lvl")),
                component = obj.optString("c"),
                event = obj.optString("e"),
                correlationId = obj.optString("cid").takeIf { it.isNotBlank() },
                message = obj.optString("msg"),
                fields = fields,
                throwable = obj.optString("t").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    /** Parses a whole file body, skipping corrupt lines. */
    fun parseAll(body: String): List<LogRecord> =
        body.lineSequence().mapNotNull { fromLine(it) }.toList()

    // ---- Rotation policy (pure) -----------------------------------

    /**
     * Decides whether the active file must rotate before appending
     * [incomingBytes]. Rotation happens on the **next** append, not
     * retroactively, so a single oversized record still lands (bounded
     * by [LogSanitizer.MAX_FIELD_CHARS]).
     */
    fun shouldRotate(
        currentBytes: Long,
        incomingBytes: Int,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
    ): Boolean = currentBytes > 0L && currentBytes + incomingBytes > maxFileBytes

    /**
     * Chooses which files to delete, oldest first, to stay inside
     * [maxFiles] and [maxTotalBytes].
     *
     * @param files every log file with its size, in **any** order —
     *   this function sorts internally by generation so it does not
     *   depend on the caller's listing order.
     * @return the files to delete.
     */
    fun filesToPrune(
        files: List<LogFile>,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
    ): List<LogFile> {
        if (files.isEmpty()) return emptyList()
        // Newest first. Generation semantics: 0 = active file (newest of
        // all); `.1` is the most recent rotation, `.2` the one before it,
        // and so on — so a LOWER generation is NEWER.
        val newestFirst = files.sortedBy { it.generation }
        val toDelete = mutableListOf<LogFile>()
        var kept = 0
        var totalBytes = 0L
        for (file in newestFirst) {
            val withinCount = kept + 1 <= maxFiles
            val withinBytes = totalBytes + file.sizeBytes <= maxTotalBytes
            if (withinCount && withinBytes) {
                kept++
                totalBytes += file.sizeBytes
            } else {
                toDelete += file
            }
        }
        return toDelete
    }
}

/** Size + generation of a log file, used by [LogFormat.filesToPrune]. */
data class LogFile(
    /** Generation index: 0 = active file, N = `.N` rotated file. */
    val generation: Int,
    val sizeBytes: Long
)

/**
 * 2026-10-12 — pure view-model for the diagnostics screen.
 *
 * Kept as a top-level `data class` with a pure factory ([summarize]) so
 * the filter and summary logic is JVM-testable without constructing a
 * Hilt ViewModel (which the project's test stack cannot do). The
 * ViewModel simply holds this and exposes it as state.
 */
data class DiagnosticsSummary(
    val records: List<LogRecord> = emptyList(),
    val problemsOnly: Boolean = false,
    val latestProblem: LogRecord? = null,
    val totalCount: Int = 0,
    val errorCount: Int = 0,
    val warnCount: Int = 0
) {
    /** What the list should render under the current filter. */
    val visibleRecords: List<LogRecord>
        get() = if (problemsOnly) records.filter { it.isProblem } else records

    companion object {
        /** Builds a summary from retained records, oldest-first. */
        fun summarize(records: List<LogRecord>, problemsOnly: Boolean): DiagnosticsSummary {
            val ordered = records.sortedBy { it.timestampMillis }
            return DiagnosticsSummary(
                records = ordered,
                problemsOnly = problemsOnly,
                latestProblem = ordered.lastOrNull { it.isProblem },
                totalCount = ordered.size,
                errorCount = ordered.count { it.level == LogLevel.ERROR },
                warnCount = ordered.count { it.level == LogLevel.WARN }
            )
        }
    }
}
