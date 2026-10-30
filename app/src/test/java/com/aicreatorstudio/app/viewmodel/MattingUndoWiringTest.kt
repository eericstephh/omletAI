package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-30 — undo for the Background Remove panel.
 *
 * ## What was broken
 *
 * `persistMatting` mutated the project and saved it, and pushed **no
 * history entry**. So every change made in that panel was outside undo:
 * pick a different background, drag feather, toggle reverse, and Undo
 * either did nothing at all or jumped back past the entire engine run
 * (the only entry that existed). "Apply mask + new background" was one
 * user decision with zero or two undo steps, depending on where you
 * looked.
 *
 * ## The shape of the fix, and why it needs a gesture boundary
 *
 * The mode chips and the reverse toggle are discrete taps: one entry
 * each. The two sliders report on **every drag frame**, so pushing inside
 * them would exhaust the 30-entry stack in well under a second — the same
 * reason `setClipTrim` takes a `pushHistory` flag. One entry is opened on
 * the first frame of a gesture and closed by
 * `endMattingKnobGesture()`, wired from the slider's
 * `onValueChangeFinished`.
 *
 * `EditorViewModel` needs Hilt + Android and cannot be constructed here,
 * so this is a source-shape test. Every assertion is positive, so no
 * comment stripper is needed.
 */
class MattingUndoWiringTest {

    private fun source(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile ?: error("cannot locate the repository root")
        }
        // Normalise line endings: the checkout is CRLF on Windows, and every
        // assertion below slices on "\n". (Cost one red run to learn.)
        return File(dir, relative).readText().replace("\r\n", "\n")
    }

    private val viewModel =
        "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    private val panel = "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/AiMattingPanel.kt"
    private val section =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorMattingPanelSection.kt"

    @Test
    fun `persistMatting can push history and actually does when asked`() {
        val src = source(viewModel)
        assertTrue(
            "persistMatting must take a pushHistory flag",
            src.contains("pushHistory: Boolean\n    )")
        )
        val body = src.substringAfter("private fun persistMatting(")
            .substringBefore("\n    private fun ")
            .substringBefore("\n    fun ")
        assertTrue(
            "the push must be inside persistMatting, not left to the callers",
            body.contains("if (pushHistory) historyDelegate.push()")
        )
    }

    @Test
    fun `the discrete setters open one entry each`() {
        val src = source(viewModel)
        // Mode chips, the reverse toggle, and picking a background file are
        // all single actions. Counted as a bare token rather than with a
        // `persistMatting\\([^)]*\\)` regex, because the reverse call nests
        // `clip.matting.copy(reverse = value)` and `[^)]*` stops at that
        // inner paren.
        assertEquals(
            "setMattingBackgroundMode, setMattingReverse and the picked-URI path must all " +
                "pass pushHistory = true",
            3,
            Regex(Regex.escape("pushHistory = true")).findAll(src).count()
        )
    }

    @Test
    fun `the continuous knobs coalesce into one entry per gesture`() {
        val src = source(viewModel)
        val expansion = src.substringAfter("fun setMattingExpansion(")
            .substringBefore("fun setMattingFeather(")
        val feather = src.substringAfter("fun setMattingFeather(")
            .substringBefore("fun setMattingReverse(")
        for ((name, body) in listOf("expansion" to expansion, "feather" to feather)) {
            assertTrue(
                "$name must open the gesture entry once",
                body.contains("pushMattingKnobHistoryOnce()")
            )
            assertTrue(
                "$name must not push per frame",
                body.contains("pushHistory = false")
            )
        }
        assertTrue(
            "the gesture must be closable",
            src.contains("fun endMattingKnobGesture()") &&
                src.contains("mattingKnobGestureOpen = false")
        )
    }

    @Test
    fun `the sliders close the gesture`() {
        val panelSrc = source(panel)
        assertEquals(
            "both knobs must report onValueChangeFinished",
            2,
            Regex(Regex.escape("onValueChangeFinished = onKnobDragFinished"))
                .findAll(panelSrc).count()
        )
        assertTrue(
            "the section must forward the callback from the CompositionLocal",
            source(section).contains("onKnobDragFinished = knobActions.onKnobDragFinished")
        )
    }
}
