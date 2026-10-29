package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-23 — the launch crash caused by declaration order.
 *
 * `EditorViewModel.init` starts `restoreAiState()` on
 * `Dispatchers.IO`. That coroutine really does run concurrently with
 * the rest of the constructor, and a Kotlin property initializer runs
 * in **declaration order** — so a field declared *after* the `init`
 * block is still `null` when the coroutine touches it:
 *
 * ```
 * FATAL EXCEPTION: DefaultDispatcher-worker-3
 * java.lang.NullPointerException: Attempt to invoke virtual method
 *   'java.lang.Object java.util.concurrent.ConcurrentHashMap.put(...)'
 *   on a null object reference
 *     at EditorViewModel.probeAiToolModelState(EditorViewModel.kt:447)
 *     at EditorViewModel.restoreAiState(EditorViewModel.kt:402)
 * ```
 *
 * The race is timing-dependent — it only crashes when the IO thread
 * wins — which is exactly why it must be pinned by a test rather than
 * by a comment: the next field added to `restoreAiState` can silently
 * reintroduce it, and no JVM test can reproduce the interleaving.
 *
 * The invariant this test enforces: every field that the IO coroutine
 * reads or writes is declared **before** the `init` block.
 */
class EditorViewModelInitOrderTest {

    private fun repoRoot(): File {
        var dir: File = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: throw IllegalStateException("settings.gradle.kts not found")
        }
    }

    private fun source(): String = File(
        repoRoot(),
        "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    ).readText()

    @Test
    fun `the init block still starts restoreAiState on the IO dispatcher`() {
        // If this ever changes, the guard below stops describing the
        // real hazard — so fail loudly instead of passing vacuously.
        val src = source()
        assertTrue(
            "`init` must start restoreAiState() off the main thread; the " +
                "declaration-order guard below exists because of that call",
            src.contains("viewModelScope.launch(Dispatchers.IO) { restoreAiState() }")
        )
    }

    @Test
    fun `fields touched by the IO init coroutine are declared before init`() {
        val src = source()
        val initIndex = src.indexOf("\n    init {")
        assertTrue("`init {` not found in EditorViewModel", initIndex > 0)

        // Every mutable field restoreAiState/probeAiToolModelState uses.
        // A field declared after `init` is still null when the IO
        // coroutine runs — that is the crash.
        val fields = listOf(
            "aiToolAvailabilityCache",
            "cachedAutoUpscaleProfile",
            "esrganSpecCache"
        )
        for (field in fields) {
            val decl = Regex("""\n    (?:@\w+\s+)?private (?:val|var) $field\b""")
                .find(src)
                ?: throw AssertionError(
                    "no `private val/var $field` declaration found — rename the " +
                        "field in this test if that was deliberate"
                )
            assertTrue(
                "`$field` is used by restoreAiState(), which `init` starts on " +
                    "Dispatchers.IO. It must be declared BEFORE the `init` block " +
                    "(declaration order = initialization order), otherwise the " +
                    "coroutine can read it as null and kill the app on launch.",
                decl.range.first < initIndex
            )
        }
    }

    @Test
    fun `restoreAiState still probes the AI tool models`() {
        // Guards against the fix being "achieved" by deleting the probe
        // that the crash happened in.
        val src = source()
        assertTrue(
            "restoreAiState must still probe the tools the panels read",
            src.contains("probeAiToolModelState(\"matting\")")
        )
    }
}
