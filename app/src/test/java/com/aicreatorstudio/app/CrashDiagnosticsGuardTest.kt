package com.aicreatorstudio.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — guards the diagnosability fixes from a real device
 * report (Xiaomi 2107113SG, Android 14).
 *
 * ## The crash report had no stack trace
 *
 * The report said:
 *
 * ```
 * crash/uncaught_exception: DefaultDispatcher-worker-8: NullPointerException
 *   !! NullPointerException: Attempt to invoke virtual method
 *   'java.lang.Object java.util.concurrent.ConcurrentHashMap.put(...)' on a null object reference
 * ```
 *
 * …and **nowhere said where**. The log record only carried the
 * exception class and the sanitizer renders a throwable as
 * `ClassName: message` with no frames. The frames are the only thing
 * that identifies a crash site, so the crash handler now puts them in
 * the event's message, with a larger character budget so they are not
 * truncated away.
 *
 * ## The process-wide caches were R8-fragile
 *
 * Three process-wide caches were plain `private val`s (a top-level
 * field, or a field on an `object`) — i.e. STATIC fields initialised
 * in a `<clinit>`. R8's class merging can reorder static
 * initialisers, and a field observed before its initialiser ran is
 * null. The Home screen's thumbnail cache is written from the IO
 * worker that decodes the frames, so a null field there is exactly
 * the reported `ConcurrentHashMap.put` on a null receiver.
 *
 * A `by lazy` holder is initialised on FIRST ACCESS under a lock, so
 * its value can never be observed as null.
 */
class CrashDiagnosticsGuardTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `the crash event carries the stack frames`() {
        val src = source("app/src/main/java/com/aicreatorstudio/app/CrashReporter.kt")
        assertTrue(
            "the crash event must include the stack frames — they are the only " +
                "thing that identifies WHERE the crash happened",
            src.contains("stackSummary(throwable)")
        )
        assertTrue(
            "the frames must be rendered",
            src.contains("\" | at \"")
        )
        assertTrue(
            "the crash message needs a bigger budget than the default 500 chars, " +
                "or the frames are truncated away",
            src.contains("maxMessageChars = STACK_MESSAGE_CHARS")
        )
    }

    @Test
    fun `the home thumbnail cache is behind a lazy holder`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/components/ProjectThumbnailFrame.kt"
        )
        assertTrue(
            "the cache must not be a bare top-level val — R8 can reorder static " +
                "initialisers and the field is then observed as null",
            !src.contains("private val projectThumbnailCache = ConcurrentHashMap")
        )
        assertTrue(
            "the cache must live behind a holder object",
            src.contains("object ProjectThumbnailCache")
        )
    }

    @Test
    fun `the string cache is behind a lazy holder`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/settings/LocalizedStrings.kt"
        )
        assertTrue(
            "the string cache must live behind a holder object",
            src.contains("object StringCache")
        )
        assertTrue(
            "the lookup must be atomic — computeIfAbsent, not getOrPut",
            src.contains("computeIfAbsent")
        )
    }

    @Test
    fun `the ai job registries are behind a lazy holder`() {
        val src = source("app/src/main/java/com/aicreatorstudio/app/ai/AiJobEngine.kt")
        assertTrue(
            "the job registries must live behind a holder object",
            src.contains("object Registries")
        )
        assertTrue(
            "the lookups must be atomic — computeIfAbsent, not getOrPut",
            src.contains("Registries.perTool.computeIfAbsent")
        )
    }
}
