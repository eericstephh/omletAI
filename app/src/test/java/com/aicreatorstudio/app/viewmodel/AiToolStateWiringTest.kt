package com.aicreatorstudio.app.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — guards for the AI-tool state bugs that made the app
 * LOOK like it worked while doing nothing.
 *
 * ## The "Reset" pill only reset the progress bar
 *
 * `resetAiTool` reset the per-tool status entry and nothing else. The
 * export pipeline reads the **clip** state (`clip.matting.enabled`,
 * `clip.autoFrame.enabled` + `cropKeyframes`, `clip.motionTrack`,
 * `clip.voiceClean.enabled`), so "run Background Remove → Reset" left
 * the subject cut out in the exported video. The reset now clears the
 * committed effect too.
 *
 * ## Background Remove wiped the user's background choice
 *
 * `onAiToolSuccess` assigned the engine's fresh `MattingSettings`
 * wholesale. The engine does not set `backgroundMode` /
 * `backgroundColor`, so every run silently reverted the mode to
 * `ORIGINAL` — the user picked "عکس" + a background, ran the tool, saw
 * "تکمیل شد", and the export used the ORIGINAL background. The write
 * is now a merge.
 *
 * ## OCR applied to the wrong clip
 *
 * The staged OCR result carried no clip identity, so "Apply" resolved
 * its target from the CURRENT selection: run OCR on clip A, select
 * clip B, press Apply → the text landed on clip B.
 *
 * ## OCR wiped the user's edited text
 *
 * The panel's `LaunchedEffect` was keyed on the whole staged result,
 * and "Apply" mutates that object — so the effect re-fired and reset
 * the text field to the raw OCR output a moment after the user
 * corrected it.
 *
 * ## A failed run left the tool stuck in "Processing"
 *
 * `onAiToolSuccess` bailed out with a bare `?: return` when the
 * project was null, leaving the status at `Processing` forever — and
 * `AiToolUiPolicy.generateEnabled` is false while Processing, so the
 * Run button stayed disabled for the rest of the session.
 *
 * These are structural (source-level) assertions, matching the
 * project's existing wiring-test style: the ViewModel cannot be
 * instantiated in the JVM test stack.
 */
class AiToolStateWiringTest {

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

    private val viewModel: String by lazy {
        stripComments(
            source("app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt")
        )
    }

    /**
     * Drops whole-line `//` comments.
     *
     * The sources document the OLD buggy code in their KDoc (to explain
     * what changed), so a naive substring search would find the old
     * snippet inside a comment and pass/fail for the wrong reason.
     */
    private fun stripComments(src: String): String =
        src.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /**
     * Extracts the body of the declaration that starts at [marker].
     *
     * The marker must include the leading indentation so it cannot
     * match the same signature quoted inside a KDoc code block.
     */
    private fun bodyOf(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val end = src.indexOf("\n    fun ", start + marker.length)
            .let { if (it < 0) src.length else it }
        return src.substring(start, end)
    }

    @Test
    fun `reset clears the committed clip effect and not just the progress bar`() {
        val body = bodyOf(viewModel, "\n    fun resetAiTool(toolId: String)")

        assertTrue(
            "reset must clear the committed matting effect",
            body.contains("MattingSettings.Identity")
        )
        assertTrue(
            "reset must clear the committed auto-frame effect",
            body.contains("AutoFrameSettings.Identity")
        )
        assertTrue(
            "reset must clear the committed motion-track effect",
            body.contains("MotionTrackSettings.Identity")
        )
        assertTrue(
            "reset must clear the committed voice-clean effect",
            body.contains("VoiceCleanSettings.Identity")
        )
        assertTrue(
            "reset must persist the cleared state",
            body.contains("saveAiTools(")
        )
    }

    @Test
    fun `a successful matting run merges the settings instead of replacing them`() {
        val body = bodyOf(viewModel, "\n    private fun onAiToolSuccess(")

        assertTrue(
            "the matting write must MERGE — replacing the whole settings object " +
                "silently reverts the user's backgroundMode/backgroundColor",
            body.contains("c.matting.copy(")
        )
        assertTrue(
            "the whole-settings assignment destroys the background mode",
            !body.contains("matting = result.matting ?: c.matting")
        )
    }

    @Test
    fun `a fresh run commits subject-stays-background-removed as the default`() {
        // 2026-10-27 — the untouched ORIGINAL default was a mask-only
        // pass-through: the button visibly did nothing. A fresh run
        // must commit TRANSPARENT (subject over the canvas fill) unless
        // the user deliberately picked a mode.
        val body = bodyOf(viewModel, "\n    private fun onAiToolSuccess(")
        assertTrue(
            "the merge must route the mode through " +
                "runDefaultBackgroundMode(c.matting.backgroundMode)",
            body.contains("runDefaultBackgroundMode(c.matting.backgroundMode)")
        )
    }

    @Test
    fun `the staged ocr result carries the clip it was produced from`() {
        val body = bodyOf(viewModel, "\n    private fun onAiToolSuccess(")
        assertTrue(
            "the staged OCR result must record the clip id",
            body.contains("clipId = clipId")
        )

        val apply = bodyOf(viewModel, "\n    fun applyOcrResult(")
        assertTrue(
            "Apply must resolve the clip from the staged result, not the selection",
            apply.contains("staged?.clipId")
        )
    }

    @Test
    fun `a successful run can never leave the tool stuck in Processing`() {
        val body = bodyOf(viewModel, "\n    private fun onAiToolSuccess(")
        assertTrue(
            "the null-project early return must set a terminal state — a bare " +
                "`return` leaves the Run button disabled for the whole session",
            body.contains("AiToolStatus.Failed")
        )
    }

    @Test
    fun `the ocr panel does not clobber the user's edited text`() {
        val panel = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/OcrPanel.kt"
        )
        assertTrue(
            "the effect must be keyed on the FRAMES — keying on the whole " +
                "staged result re-fires after Apply and wipes the edit",
            panel.contains("LaunchedEffect(ocrResult?.frames)")
        )
        assertTrue(
            "an existing edit must not be overwritten by a new run",
            panel.contains("editedText.isBlank()")
        )
    }

    @Test
    fun `a cancelled ai run is visible to the user`() {
        val panel = source(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/AiMattingPanel.kt"
        )
        val body = bodyOf(panel, "internal fun AiToolProgress(state: AiToolState)")
        assertTrue(
            "the cancelled state must be rendered — returning early left the " +
                "user with no feedback at all after cancelling a long run",
            !body.contains("if (state.status == AiToolStatus.Cancelled) return")
        )
        assertTrue(
            "the cancelled state must have its own colour",
            body.contains("AiToolStatus.Cancelled -> iOSOrange")
        )
    }
}
