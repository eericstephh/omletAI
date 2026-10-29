package com.aicreatorstudio.app.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 — guards for the diagnostics wiring that was missing.
 *
 * ## The defect
 *
 * The «گزارش‌های فنی» panel in Settings read its files faithfully, but
 * almost nothing ever wrote to them: `OmeletLog` was attached to exactly
 * two producers (`ExportDiagnostics`, `WhisperDiagnostics`), so a failed
 * AI tool, a player error, a model download failure, a relink failure
 * and an uncaught crash all told the user something was wrong and then
 * wrote nothing. The panel showed "هنوز رویدادی ثبت نشده است" while the
 * app was broken — the opposite of its job.
 *
 * Two structural gaps had to close:
 *
 * 1. **Producers.** `EditorViewModel` is where every one of those
 *    failures already passes, so it now takes `OmeletLog` and records
 *    them. The guards below pin the injection site and each call site.
 * 2. **Visibility.** The panel snapshot was taken once, at ViewModel
 *    construction, so even correctly-logged events were invisible
 *    without a process restart. It now re-reads on ON_RESUME, and the
 *    crash path uses a synchronous store write because a queued line
 *    never gets flushed once the process is dying.
 */
class DiagnosticsWiringTest {

    private fun lines(path: String): List<String> =
        File(path).readLines().map { it.trim() }

    private fun vm() =
        lines("src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt")

    private fun store() =
        lines("src/main/java/com/aicreatorstudio/app/logging/OmeletLogStore.kt")

    private fun facade() =
        lines("src/main/java/com/aicreatorstudio/app/logging/OmeletLog.kt")

    private fun crash() =
        lines("src/main/java/com/aicreatorstudio/app/CrashReporter.kt")

    private fun app() =
        lines("src/main/java/com/aicreatorstudio/app/AICreatorStudioApp.kt")

    private fun panel() =
        lines("src/main/java/com/aicreatorstudio/app/ui/screens/settings/DiagnosticsSection.kt")

    // ---- producers ------------------------------------------------------

    @Test
    fun `the ViewModel receives the logger and records the failure paths`() {
        val src = vm()
        assertTrue(
            "EditorViewModel must inject OmeletLog",
            src.any { it.contains("private val omeletLog: com.aicreatorstudio.app.logging.OmeletLog,") }
        )
        // Every path that already put a message in front of the user.
        val producers = listOf(
            "event = \"player_error\"" to "onPlayerError",
            "\"generation_failed\"" to "failGeneration",
            "\"generation_model_failed\"" to "failGeneration (model variant)",
            "event = \"run_failed\"" to "onAiToolFailure",
            "event = \"run_rejected_no_clip\"" to "runAiTool",
            "event = \"model_install_failed\"" to "model install",
            "event = \"model_ensure_failed\"" to "ensureAiToolModelReady",
            "event = \"relink_media_failed\"" to "relinkMissingMedia",
            "event = \"extract_failed\"" to "extractAudioFromVideo"
        )
        for ((needle, where) in producers) {
            assertTrue("no log call with $needle ($where)", src.any { it.contains(needle) })
        }
        // A hang is only diagnosable if the matching start exists.
        assertTrue(
            "AI tool runs must record a start event",
            src.any { it.contains("event = \"run_started\"") }
        )
    }

    // ---- crash path -----------------------------------------------------

    @Test
    fun `a crash reaches the log synchronously`() {
        // The queued writer may never run once the process is dying, so
        // the crash path needs its own write.
        assertTrue(
            "OmeletLogStore must expose a synchronous append",
            store().any { it.contains("fun appendNow(line: String)") }
        )
        assertTrue(
            "OmeletLog must expose the blocking error write",
            facade().any { it.contains("fun errorBlocking(") }
        )
        assertTrue(
            "CrashReporter must hold a sink",
            crash().any { it.contains("fun attachLog(") }
        )
        val handlerBody = crash().dropWhile { !it.contains("Thread.setDefaultUncaughtExceptionHandler") }
        assertTrue(
            "the crash handler must call the blocking write",
            handlerBody.any { it.contains("logSink?.errorBlocking(") }
        )
        // attach before install, or a crash during install is unrecorded.
        val appSrc = app()
        val attachIdx = appSrc.indexOfFirst { it.contains("CrashReporter.attachLog(omeletLog)") }
        val installIdx = appSrc.indexOfFirst { it.contains("CrashReporter.install(this)") }
        assertTrue("attachLog must be called", attachIdx >= 0)
        assertTrue("attachLog must precede install()", installIdx > attachIdx)    }

    // ---- visibility -----------------------------------------------------

    @Test
    fun `the panel re-reads on resume`() {
        val src = panel()
        assertTrue(
            "the panel must observe the lifecycle",
            src.any { it.contains("val lifecycleOwner = LocalLifecycleOwner.current") }
        )
        assertTrue(
            "and refresh on ON_RESUME",
            src.any { it.contains("Lifecycle.Event.ON_RESUME") && it.contains("viewModel.refresh()") }
        )
        assertTrue(
            "the observer must be removed on dispose",
            src.any { it.contains("lifecycleOwner.lifecycle.removeObserver(observer)") }
        )
    }
}
