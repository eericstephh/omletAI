package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 — the CapCut-style subtitle editing controls must actually
 * reach the Subtitle Studio panel and the ViewModel.
 *
 * Compose UI cannot be rendered under this project's JVM test stack
 * (`androidTest` is broken; nothing Compose-related runs off-device),
 * so the wiring is asserted structurally against the real source. This
 * is deliberately narrow: it does not try to prove the panel *looks*
 * right — it proves the editing affordances exist, are reachable, and
 * are connected to the actions that were behaviourally tested in
 * `SubtitleEditingWorkflowTest` / `SubtitleEditOpsTest`.
 *
 * The failure this guards against is the quiet one: a refactor that
 * leaves `SubtitleEditOps` fully tested while the UI stops calling it,
 * so the feature silently disappears with a green test suite. That
 * exact pattern has bitten this project before (a documented
 * capability with twenty passing tests and zero call sites).
 */
class SubtitleEditingWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun read(relativePath: String): String {
        val f = File(repoRoot(), relativePath)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private fun panelSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/subtitle/SubtitleStudioPanel.kt"
    )

    private fun screenSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
    )

    @Test
    fun `the panel declares every editing callback`() {
        val src = panelSource()
        listOf(
            "onSplitSubtitle",
            "onMergeSubtitleWithNext",
            "onRetimeSubtitle",
            "onRetimeSubtitleWord",
            "onResyncSubtitleText"
        ).forEach { name ->
            assertTrue(
                "SubtitleStudioPanel must declare the $name callback",
                src.contains(name)
            )
        }
    }

    @Test
    fun `the panel renders the editing section`() {
        val src = panelSource()
        assertTrue(
            "SubtitleStudioPanel must render SubtitleEditingSection",
            src.contains("SubtitleEditingSection(")
        )
        assertTrue(
            "the editing section must be gated on a selected subtitle",
            src.contains("if (selectedTextOverlay != null)")
        )
    }

    @Test
    fun `the panel offers split merge and retime controls`() {
        val src = panelSource()
        assertTrue("split control label missing", src.contains("برش در"))
        assertTrue("merge control label missing", src.contains("ادغام با بعدی"))
        assertTrue("retime control missing", src.contains("شروع −۱۰۰ms"))
        assertTrue("per-word editor missing", src.contains("زمان‌بندی کلمه‌به‌کلمه"))
    }

    @Test
    fun `the editing section is disabled when an operation cannot succeed`() {
        val src = panelSource()
        // A one-word cue must not offer a split.
        assertTrue(
            "split must be gated on >= 2 words",
            src.contains("val canSplit = words.size >= 2")
        )
        assertTrue(
            "the split chip must consume the canSplit gate",
            src.contains("enabled = canSplit")
        )
    }

    @Test
    fun `EditorScreen forwards every editing callback to the ViewModel actions`() {
        val src = screenSource()
        // Each callback must be wired to its ViewModel action, not to
        // an empty lambda.
        assertTrue(src.contains("viewModel.splitSubtitleAt("))
        assertTrue(src.contains("viewModel.mergeSubtitleWithNext("))
        assertTrue(src.contains("viewModel.retimeSubtitle("))
        assertTrue(src.contains("viewModel.retimeSubtitleWord("))
        assertTrue(src.contains("viewModel.resyncSubtitleTextFromWords("))
    }

    @Test
    fun `EditorScreen passes the callbacks into SubtitleStudioPanel`() {
        val src = screenSource()
        // Isolate the SubtitleStudioPanel call so a callback wired
        // somewhere unrelated cannot satisfy this.
        val callStart = src.indexOf("SubtitleStudioPanel(")
        assertTrue("SubtitleStudioPanel call site missing", callStart >= 0)
        val call = src.substring(callStart, minOf(src.length, callStart + 3000))
        listOf(
            "onSplitSubtitle = onSplitSubtitle",
            "onMergeSubtitleWithNext = onMergeSubtitleWithNext",
            "onRetimeSubtitle = onRetimeSubtitle",
            "onRetimeSubtitleWord = onRetimeSubtitleWord",
            "onResyncSubtitleText = onResyncSubtitleText"
        ).forEach { wire ->
            assertTrue("SubtitleStudioPanel must receive $wire", call.contains(wire))
        }
    }

    @Test
    fun `the 20 built-in Persian templates are untouched`() {
        // The user's explicit constraint for this phase: do NOT
        // recreate or rename the built-in templates. Their ids are
        // persisted in saved projects, so a rename is a migration.
        // 2026-10-15 — the six `fa_pro_*` templates are ADDITIONS
        // (asserted below); the numbered set stays exactly 20.
        val src = read(
            "app/src/main/java/com/aicreatorstudio/app/model/BuiltInSubtitleTemplates.kt"
        )
        val ids = Regex("""fa_(\d{2})_[a-z_]+""").findAll(src).map { it.value }.toSet()
        assertTrue(
            "expected 20 fa_NN_* template ids, found ${ids.size}: $ids",
            ids.size == 20
        )
        assertTrue("fa_01_clean missing", "fa_01_clean" in ids)
        assertTrue("fa_20_signature missing", "fa_20_signature" in ids)
        listOf(
            "fa_pro_hormozi_viral", "fa_pro_neon_cyberpunk",
            "fa_pro_glass_minimal", "fa_pro_comic_pop",
            "fa_pro_golden_lux", "fa_pro_red_punch"
        ).forEach { proId ->
            assertTrue("$proId missing", proId in src)
        }
    }

    @Test
    fun `the subtitle editing phase did not add network access`() {
        // Constraint 1/2: everything here is offline. The new ops and
        // UI must not introduce any networking type.
        val ops = read(
            "app/src/main/java/com/aicreatorstudio/app/model/SubtitleEditOps.kt"
        )
        listOf("OkHttp", "HttpURLConnection", "Retrofit", "java.net.", "okhttp3", "Socket")
            .forEach { forbidden ->
                assertTrue(
                    "SubtitleEditOps must stay offline; found $forbidden",
                    !ops.contains(forbidden)
                )
            }
    }
}
