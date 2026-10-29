package com.aicreatorstudio.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {

    private const val FILE_NAME = "crash_log.txt"
    private const val HISTORY_FILE_NAME = "crash_history.txt"

    /**
     * 2026-10-20 — the character budget for the crash event's message.
     *
     * The default log budget (500) truncates a stack trace down to
     * nothing useful; 2 000 fits a full cause chain with frames.
     */
    private const val STACK_MESSAGE_CHARS = 2_000

    /**
     * 2026-10-13 — the diagnostics sink. Set from
     * [com.aicreatorstudio.app.AICreatorStudioApp] right after the
     * injected [com.aicreatorstudio.app.logging.OmeletLog] is available,
     * BEFORE [install], so a crash during install is still recorded.
     *
     * The two crash files below remain the authoritative record (they are
     * what the MainActivity dialog reads); this only makes the crash
     * visible in the «گزارش‌های فنی» panel, which previously showed
     * nothing for crashes because nothing bridged the two systems.
     */
    @Volatile
    private var logSink: com.aicreatorstudio.app.logging.OmeletLog? = null

    fun attachLog(sink: com.aicreatorstudio.app.logging.OmeletLog?) {
        logSink = sink
    }

    fun install(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Latest crash — the "share the last crash" dialog in
                // MainActivity reads exactly this file (contract unchanged).
                File(context.filesDir, FILE_NAME).writeText(stackTraceOf(throwable))
                // 2026-10-05 — rolling history. Previously a second crash
                // destroyed all evidence of the first; now every crash is
                // appended (capped) with a timestamped header so patterns
                // across crashes are visible even without a remote
                // telemetry backend.
                CrashLog.appendCapped(
                    historyFile = File(context.filesDir, HISTORY_FILE_NAME),
                    entry = CrashLog.buildEntry(
                        timestampMillis = System.currentTimeMillis(),
                        appVersion = appVersion(context),
                        device = "${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}",
                        threadName = thread.name,
                        throwable = throwable
                    )
                )
                // 2026-10-13 — same event into the diagnostics log.
                // Synchronous: the queued writer may never run.
                //
                // 2026-10-20 — THE STACK TRACE IS NOW PART OF THE
                // EVENT. The log record only carried
                // `ThreadName: ExceptionClass` and the throwable field
                // carried `ClassName: message` (see
                // [com.aicreatorstudio.app.logging.LogSanitizer.describeThrowable]),
                // so a report said "NullPointerException: Attempt to
                // invoke virtual method ...ConcurrentHashMap.put... on a
                // null object reference" and **nowhere said where**.
                // The frames are the only thing that identifies the
                // call site, and they are what turns "the app crashed"
                // into "this line crashed".
                logSink?.errorBlocking(
                    component = "crash",
                    event = "uncaught_exception",
                    message = "${thread.name}: ${stackSummary(throwable)}",
                    throwable = throwable,
                    // 2026-10-20 — a larger budget so the frames
                    // survive: they are the crash's identity.
                    maxMessageChars = STACK_MESSAGE_CHARS
                )
            } catch (_: Exception) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun takePendingCrash(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val content = runCatching { file.readText() }.getOrNull()
        file.delete()
        return content
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    /**
     * 2026-10-20 — a compact `Class: message | at frame | at frame` line.
     *
     * The frames are the crash's identity: the message alone says
     * WHAT went wrong, the frames say WHERE. The cause chain is
     * included (up to three levels) because the interesting frame is
     * often in the cause, not the wrapper.
     *
     * Kept short on purpose — the log record's message field is
     * sanitized and length-capped, and the most useful frames are the
     * top ones.
     */
    private fun stackSummary(throwable: Throwable, maxFrames: Int = 8): String {
        val sb = StringBuilder()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 3) {
            val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
            if (depth == 0) {
                sb.append(name)
                current.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
            } else {
                sb.append(" <- ").append(name)
                current.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
            }
            current.stackTrace.take(maxFrames).forEach { frame ->
                sb.append(" | at ").append(frame.toString())
            }
            current = current.cause
            depth++
        }
        return sb.toString()
    }
}
