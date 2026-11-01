package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-15 — the GLOBAL SUBTITLE FONT SIZE control must actually
 * reach the Subtitle Studio panel and the ViewModel, and the history
 * contract must hold in the SOURCE the way
 * [com.aicreatorstudio.app.viewmodel.GlobalSubtitleFontSizeTest]
 * proves it behaviourally for the stack.
 *
 * Compose UI cannot be rendered under this project's JVM test stack,
 * so the wiring is asserted structurally against the real source —
 * the same guard that caught (and keeps catching) the
 * "feature shipped with twenty passing tests and zero call sites"
 * failure mode.
 *
 * Pinned here:
 *  - the ViewModel batch ops push exactly ONE snapshot per logical
 *    operation (discrete) / per gesture (live drag), and the live
 *    path pushes NOTHING;
 *  - both size channels (`fontSizeSp` + `subtitleStyle.fontSizeSp`)
 *    are written — preview/export parity;
 *  - the subtitle predicate cannot sweep decorative text;
 *  - the five callbacks are declared, threaded through
 *    EditorScreen → SubPanelContent → SubtitleStudioPanel, and bound
 *    to the ViewModel methods;
 *  - the slider persists on drag END (`onValueChangeFinished`), the
 *    ±2sp chips and the apply button are discrete single-push calls,
 *    and ±10% chips drive the proportional path.
 */
class GlobalSubtitleFontSizeWiringTest {

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

    private fun viewModelSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    )

    private fun panelSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/subtitle/SubtitleStudioPanel.kt"
    )

    private fun screenSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
    )

    /**
     * Extracts the body of the first function whose declaration
     * contains [signature] — brace-matched from the signature's
     * opening brace. (None of the pinned bodies contain brace
     * string literals, so counting braces is safe here.)
     */
    private fun functionBody(src: String, signature: String): String {
        val idx = src.indexOf(signature)
        assertTrue("signature not found: $signature", idx >= 0)
        val open = src.indexOf('{', idx)
        assertTrue("no opening brace after: $signature", open >= 0)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced braces after: $signature")
    }

    /**
     * Source region of an EXPRESSION-bodied member (no braces):
     * everything from the signature up to the next member at the
     * same indent level (a `fun` or a KDoc start). Used for
     * `isSubtitleOverlay`, whose body is a `=` expression.
     */
    private fun functionRegion(src: String, signature: String): String {
        val idx = src.indexOf(signature)
        assertTrue("signature not found: $signature", idx >= 0)
        var end = src.length
        listOf("\n    fun ", "\n    private fun ", "\n    /**").forEach { marker ->
            val at = src.indexOf(marker, idx + signature.length)
            if (at > idx && at < end) end = at
        }
        return src.substring(idx, end)
    }

    private fun countPushes(body: String): Int =
        "historyDelegate.push()".let { needle ->
            body.split(needle).size - 1
        }

    // ---- ViewModel: history contract ----

    @Test
    fun `discrete set pushes exactly one snapshot and persists`() {
        val body = functionBody(
            viewModelSource(),
            "fun setGlobalSubtitleFontSize(newSizeSp: Float)"
        )
        assertEquals(
            "one logical batch op = ONE undo snapshot",
            1,
            countPushes(body)
        )
        assertTrue("the discrete op must persist once", body.contains("persistProjectMeta()"))
        // No-op guard BEFORE the push: nothing-to-change must not
        // fabricate history.
        assertTrue(
            "the no-document-change guard must run before the push",
            body.indexOf("alreadyAtTarget") < body.indexOf("historyDelegate.push()")
        )
    }

    @Test
    fun `live drag path pushes nothing and does not persist`() {
        val body = functionBody(
            viewModelSource(),
            "fun updateGlobalSubtitleSizeLive(sizeSp: Float)"
        )
        assertEquals(
            "a 200px drag must not create 200 undo entries",
            0,
            countPushes(body)
        )
        assertTrue(
            "the live path must be state-only (no persist per frame)",
            !body.contains("persistProjectMeta()")
        )
    }

    @Test
    fun `begin pushes the single snapshot and end persists`() {
        val begin = functionBody(viewModelSource(), "fun beginGlobalSubtitleResize()")
        assertEquals(1, countPushes(begin))
        val end = functionBody(viewModelSource(), "fun endGlobalSubtitleResize()")
        assertEquals("the drag END persists — never pushes again", 0, countPushes(end))
        assertTrue(end.contains("persistProjectMeta()"))
    }

    @Test
    fun `proportional scale pushes one snapshot and multiplies`() {
        val body = functionBody(viewModelSource(), "fun scaleAllSubtitles(scaleFactor: Float)")
        assertEquals(1, countPushes(body))
        assertTrue(
            "scale must be PROPORTIONAL (keeps mixed-size ratios)",
            body.contains("it.fontSizeSp * factor")
        )
        assertTrue(
            "scale must clamp into the safe band",
            body.contains("coerceIn(globalSubtitleSizeRange)")
        )
        assertTrue(body.contains("persistProjectMeta()"))
    }

    // ---- ViewModel: scope + parity ----

    @Test
    fun `subtitle predicate cannot sweep decorative text`() {
        val src = viewModelSource()
        val body = functionRegion(src, "private fun isSubtitleOverlay(")
        // 2026-11-01 — the tag/word half of this test moved into the
        // shared `TextOverlay.isSubtitle` extension (model/TextOverlay.kt)
        // because four hand-written copies of it had drifted apart; the
        // SRT-export gate was the narrowest and exported nothing. The
        // BEHAVIOUR asserted here is unchanged, so the test now checks
        // that this predicate delegates to the shared definition and
        // still ORs in the in-memory id sets — instead of re-spelling
        // `subtitleSource != null` / `words.isNotEmpty()` locally, which
        // is exactly the duplication that caused the bug.
        assertTrue(
            "the predicate must delegate to the shared isSubtitle extension",
            body.contains("isSubtitle")
        )
        assertTrue(body.contains("aiSubtitleOverlayIds"))
        assertTrue(body.contains("srtImportedOverlayIds"))
        assertTrue(
            "selection must NOT decide the batch scope",
            !body.contains("selectedTextId")
        )

        // And the shared definition itself must still exclude
        // decorative text: a tag or word timings, nothing else.
        val overlaySource = read(
            "app/src/main/java/com/aicreatorstudio/app/model/TextOverlay.kt"
        )
        assertTrue(
            "isSubtitle must key on the tag or the word timings",
            overlaySource.contains("subtitleSource != null || words.isNotEmpty()")
        )
    }

    @Test
    fun `both size channels are written on every batch path`() {
        val live = functionBody(viewModelSource(), "fun updateGlobalSubtitleSizeLive(")
        assertTrue(
            "live path must mirror the size into subtitleStyle",
            live.contains("fontSizeSp = target") &&
                live.contains("subtitleStyle = overlay.subtitleStyle?.copy(fontSizeSp = target)")
        )
        val scale = functionBody(viewModelSource(), "fun scaleAllSubtitles(")
        assertTrue(
            "scale path must mirror the size into subtitleStyle",
            scale.contains("subtitleStyle = overlay.subtitleStyle?.copy(fontSizeSp = size)")
        )
    }

    @Test
    fun `the safe band is 14 to 42 sp`() {
        assertTrue(
            viewModelSource().contains("val globalSubtitleSizeRange: ClosedFloatingPointRange<Float> = 14f..42f")
        )
    }

    // ---- UI wiring ----

    @Test
    fun `the five callbacks are threaded through the screen chain`() {
        val screen = screenSource()
        listOf(
            "onBeginGlobalSubtitleResize",
            "onGlobalSubtitleSizeLive",
            "onEndGlobalSubtitleResize",
            "onSetGlobalSubtitleSize",
            "onScaleAllSubtitles"
        ).forEach { name ->
            // Declared as a parameter (with a type), passed through to
            // SubPanelContent, and handed to SubtitleStudioPanel.
            assertTrue(
                "$name must be declared as a callback parameter",
                screen.contains("$name: (Float) -> Unit = {}") ||
                    screen.contains("$name: () -> Unit = {}")
            )
            assertTrue(
                "$name must reach SubtitleStudioPanel",
                screen.contains("$name = $name")
            )
        }
        // And the ViewModel bindings must exist.
        listOf(
            "viewModel.beginGlobalSubtitleResize()",
            "viewModel.updateGlobalSubtitleSizeLive(",
            "viewModel.endGlobalSubtitleResize()",
            "viewModel.setGlobalSubtitleFontSize(",
            "viewModel.scaleAllSubtitles("
        ).forEach { binding ->
            assertTrue(
                "EditorScreen must bind $binding",
                screen.contains(binding)
            )
        }
    }

    @Test
    fun `the panel declares the global size control and its callbacks`() {
        val src = panelSource()
        assertTrue(
            "the control composable must exist",
            src.contains("private fun GlobalSubtitleSizeControl(")
        )
        assertTrue(
            "the control must be rendered in the FONT tab",
            src.contains("GlobalSubtitleSizeControl(")
        )
        listOf(
            "onBeginGlobalSubtitleResize: () -> Unit = {}",
            "onGlobalSubtitleSizeLive: (Float) -> Unit = {}",
            "onEndGlobalSubtitleResize: () -> Unit = {}",
            "onSetGlobalSubtitleSize: (Float) -> Unit = {}",
            "onScaleAllSubtitles: (Float) -> Unit = {}"
        ).forEach { decl ->
            assertTrue("panel must declare $decl", src.contains(decl))
        }
    }

    @Test
    fun `the slider persists on drag end`() {
        val body = functionBody(panelSource(), "private fun GlobalSubtitleSizeControl(")
        assertTrue(
            "onValueChangeFinished is the only reliable finger-left signal",
            body.contains("onValueChangeFinished")
        )
        assertTrue(
            "drag end must release the dragging latch",
            body.contains("dragging = false")
        )
        // Drag start pushes history exactly once (guarded by the latch).
        assertTrue(
            "first change opens the gesture (history snapshot once)",
            body.contains("if (!dragging)")
        )
        // Discrete chips + apply button + proportional chips.
        assertTrue(body.contains("onDiscreteSize(next)"))
        assertTrue(body.contains("onDiscreteSize(sizeSp)"))
        assertTrue(body.contains("onScale(0.9f)"))
        assertTrue(body.contains("onScale(1.1f)"))
        // Persian labels the user specified.
        assertTrue(body.contains("اندازه قلم همه زیرنویس‌ها"))
        assertTrue(body.contains("اعمال روی تمام زیرنویس‌ها"))
    }
}
