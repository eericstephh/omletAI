package com.aicreatorstudio.app.logging

import android.content.Context
import android.os.Build
import com.aicreatorstudio.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-10-12 — Android-side owner of the bounded, rotating log files.
 *
 * ## Guarantees
 *
 * - **Never blocks a caller.** [append] sanitizes on the calling thread
 *   (cheap, pure) and hands the line to an unbounded-buffer channel;
 *   the actual disk write happens on a single dedicated IO coroutine.
 * - **Never crashes the app.** Every filesystem operation is wrapped;
 *   a failure flips [writesDisabled] and the app continues silently.
 *   The logger never logs its own failure (that would be recursive).
 * - **No interleaving.** Exactly one writer coroutine, so two threads
 *   logging concurrently cannot interleave bytes inside a line.
 * - **Bounded.** Files rotate past [LogFormat.DEFAULT_MAX_FILE_BYTES]
 *   and the whole set is pruned to
 *   [LogFormat.DEFAULT_MAX_TOTAL_BYTES] / [LogFormat.DEFAULT_MAX_FILES].
 * - **User data is untouched.** Only files matching [LOG_FILE_PREFIX]
 *   inside the dedicated `logs/` directory are ever deleted. Projects
 *   and exported videos live elsewhere and are never named here.
 *
 * Storage lives under `context.filesDir/logs/` (app-private internal
 * storage → no storage permission on any API level).
 */
@Singleton
class OmeletLogStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dir: File = File(context.filesDir, DIR_NAME)
    private val activeFile: File = File(dir, ACTIVE_FILE_NAME)

    /** Single writer + single scope; [Dispatchers.IO] because this is disk. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Buffer of pending lines. Capacity 1024 protects against a runaway
     * producer in release; overflow drops the OLDEST entry rather than
     * suspending the caller (dropping old diagnostics beats stalling an
     * export).
     */
    private val queue = Channel<String>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var writesDisabled: Boolean = false

    /** Set once the writer has re-read the on-disk state (crash recovery). */
    private val initialized = AtomicBoolean(false)

    private val lock = Any()

    init {
        scope.launch { drainLoop() }
    }

    /** True when a previous write failed and the store is read-only. */
    val isWriteDisabled: Boolean get() = writesDisabled

    /** Enqueue one already-formatted JSONL line. Non-blocking. */
    fun append(line: String) {
        if (writesDisabled) return
        queue.trySend(line)
    }

    /**
     * 2026-10-13 — the crash path's write. [append] only *enqueues*; the
     * process is about to die when `CrashReporter` calls this, so the
     * writer coroutine may never run and the record would be lost.
     *
     * Takes the same lock as the writer and writes inline, so a crash
     * that lands during a normal write still interleaves at line
     * granularity. Never throws, never flips [writesDisabled] into a
     * retry storm — the existing crash file remains the authoritative
     * record; this only makes the event visible in the diagnostics panel.
     */
    fun appendNow(line: String) {
        if (writesDisabled) return
        runCatching {
            synchronized(lock) {
                if (!dir.exists() && !dir.mkdirs()) return@runCatching
                writeLineSafely(line)
            }
        }
    }

    /**
     * Reads every retained record, oldest first. Safe to call while
     * writes are in flight: it reads the files that exist at call time
     * and skips any partially written trailing line.
     */
    fun readAll(): List<LogRecord> {
        val files = listLogFiles()
        if (files.isEmpty()) return emptyList()
        // Oldest → newest so a report reads chronologically.
        val ordered = files.sortedWith(
            compareByDescending<File> { generationOf(it) == 0 }
                .thenByDescending { generationOf(it) }
        )
        val out = ArrayList<LogRecord>(256)
        for (file in ordered) {
            val body = runCatching { file.readText() }.getOrNull() ?: continue
            out += LogFormat.parseAll(body)
        }
        return out.sortedBy { it.timestampMillis }
    }

    /** Total bytes across all retained log files. */
    fun retainedBytes(): Long = listLogFiles().sumOf { it.length() }

    /** Deletes every log file. Only ever touches files this store owns. */
    fun clearAll(): Boolean {
        val ok = runCatching {
            listLogFiles().forEach { it.delete() }
            true
        }.getOrDefault(false)
        return ok
    }

    /**
     * Minimal, non-identifying device summary for the shared report.
     * Public build constants only — no identifiers, no account, no
     * advertising id, no serial.
     */
    fun deviceSummary(): String = buildString {
        append("app=").append(BuildConfig.VERSION_NAME)
        append(" (").append(BuildConfig.VERSION_CODE).append(')')
        append(" android=").append(Build.VERSION.SDK_INT)
        append(" abi=").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
        append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
    }

    /**
     * 2026-10-20 — the device's video ENCODER capabilities.
     *
     * The AI pipeline's encoder choice is a load-bearing, device-
     * specific decision (which codec, which color format, which
     * resolution). When the app misbehaves on one phone but not
     * another, this line is the evidence: it says exactly what the
     * device can encode, so a report says WHY the output was clamped
     * or why the run failed — instead of "the AI feature does not
     * work" with no actionable content.
     *
     * Public capability data only; no identifiers.
     */
    fun mediaSummary(): String {
        val cached = mediaSummaryCache
        if (cached != null) return cached
        val summary = buildString {
            append("encoders=")
            val options = runCatching {
                com.aicreatorstudio.app.ai.media.VideoEncoderCapability
                    .listEncoders("video/avc")
            }.getOrDefault(emptyList())
            if (options.isEmpty()) {
                append("none")
            } else {
                options.forEachIndexed { index, option ->
                    if (index > 0) append(';')
                    append(option.codecName)
                    append("[")
                    append(option.widthRange.first).append('-').append(option.widthRange.last)
                    append('x')
                    append(option.heightRange.first).append('-').append(option.heightRange.last)
                    append(" fmt=")
                    append(
                        option.colorFormats
                            .map { fmt ->
                                when (fmt) {
                                    com.aicreatorstudio.app.ai.media.VideoEncoderCapability.COLOR_PLANAR -> "I420"
                                    com.aicreatorstudio.app.ai.media.VideoEncoderCapability.COLOR_SEMI_PLANAR -> "NV12"
                                    com.aicreatorstudio.app.ai.media.VideoEncoderCapability.COLOR_FLEXIBLE -> "flex"
                                    else -> fmt.toString()
                                }
                            }
                            .sorted()
                            .joinToString("+")
                    )
                    append(']')
                }
            }
        }
        mediaSummaryCache = summary
        return summary
    }

    @Volatile
    private var mediaSummaryCache: String? = null

    // ---- Writer -----------------------------------------------------------------

    private suspend fun drainLoop() {
        // Crash recovery: make sure the directory exists and any
        // leftover partial state is tolerated (we always append, never
        // assume a clean last line).
        ensureInitialized()
        for (line in queue) {
            if (writesDisabled) continue
            writeLineSafely(line)
        }
    }

    private fun ensureInitialized() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                writesDisabled = true
                return
            }
            prune()
        } catch (_: Throwable) {
            writesDisabled = true
        }
    }

    private fun writeLineSafely(line: String) {
        try {
            synchronized(lock) {
                if (!dir.exists() && !dir.mkdirs()) {
                    writesDisabled = true
                    return
                }
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (LogFormat.shouldRotate(activeFile.length(), bytes.size)) {
                    rotate()
                }
                // Append — a partial line from a previous crash stays in
                // place and is skipped by the parser, never repaired.
                activeFile.appendBytes(bytes)
                prune()
            }
        } catch (_: Throwable) {
            // Disk full, permission revoked, unmounted storage, …
            // Stop writing rather than retry-storming; the app must not
            // crash because logging failed.
            writesDisabled = true
        }
    }

    /**
     * Cascade `.N-1 → .N` (dropping the last), then move the active
     * file to `.1`. Bounded to [LogFormat.DEFAULT_MAX_FILES] slots.
     */
    private fun rotate() {
        val max = LogFormat.DEFAULT_MAX_FILES
        // Delete the oldest slot, then shift upward.
        File(dir, "$LOG_FILE_PREFIX.$max").takeIf { it.exists() }?.delete()
        for (gen in max - 1 downTo 1) {
            val from = File(dir, "$LOG_FILE_PREFIX.$gen")
            if (from.exists()) {
                runCatching { from.renameTo(File(dir, "$LOG_FILE_PREFIX.${gen + 1}")) }
            }
        }
        runCatching { activeFile.renameTo(File(dir, "$LOG_FILE_PREFIX.1")) }
    }

    /** Deletes files beyond the retention limits. Only log files. */
    private fun prune() {
        val files = listLogFiles()
        if (files.isEmpty()) return
        val described = files.map {
            LogFile(generation = generationOf(it), sizeBytes = it.length())
        }
        val doomed = LogFormat.filesToPrune(
            files = described,
            maxFiles = LogFormat.DEFAULT_MAX_FILES,
            maxTotalBytes = LogFormat.DEFAULT_MAX_TOTAL_BYTES
        )
        if (doomed.isEmpty()) return
        val doomedGenerations = doomed.map { it.generation }.toSet()
        files.forEach { f ->
            if (generationOf(f) in doomedGenerations) {
                runCatching { f.delete() }
            }
        }
    }

    /** Every file this store owns. Nothing outside `logs/` is listed. */
    private fun listLogFiles(): List<File> {
        val all = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        return all.filter { it.isFile && it.name.startsWith(LOG_FILE_PREFIX) }
    }

    /** 0 = active, N = the `.N` rotated file. */
    private fun generationOf(file: File): Int {
        if (file.name == ACTIVE_FILE_NAME) return 0
        val suffix = file.name.substringAfter("$LOG_FILE_PREFIX.", "")
        return suffix.toIntOrNull() ?: 0
    }

    companion object {
        const val DIR_NAME: String = "logs"
        const val LOG_FILE_PREFIX: String = "omelet.log"
        const val ACTIVE_FILE_NAME: String = "omelet.log"
    }
}
