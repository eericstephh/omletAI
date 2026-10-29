package com.aicreatorstudio.app.viewmodel

import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.SubtitleEditOps
import com.aicreatorstudio.app.ai.TranscribedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 — the CapCut-style subtitle EDITING workflow, end to end.
 *
 * Two halves, deliberately:
 *
 *  **A. Behaviour (real, executed).** The editing operations are pure
 *  functions in `model/SubtitleEditOps`, so they can be driven here for
 *  real on the *exact* [TextOverlay] shape the ViewModel persists. The
 *  sequences below are the ones a user actually performs through the
 *  Subtitle Studio controls: split a too-long cue, merge two fragments,
 *  retime a boundary, nudge one word.
 *
 *  **B. Wiring (source-shape).** `EditorViewModel` is a Hilt singleton
 *  and cannot be constructed in a JVM test under this project's stack
 *  (JUnit4 + coroutines-test + org.json only — no Mockito/Robolectric),
 *  so its four new actions are asserted structurally: each must call
 *  `SubtitleEditOps`, must route through `historyDelegate.push()`, must
 *  `persistProjectMeta()`, and must return early when the op yields
 *  `null` so a rejected edit leaves no undo entry.
 *
 * The split is not a compromise — it is the honest shape of what is
 * testable here, and part B exists specifically to stop the action
 * bodies from silently drifting away from the behaviour proved in part
 * A.
 */
class SubtitleEditingWorkflowTest {

    // ==================================================================
    // A. Behaviour — real execution over the persisted type
    // ==================================================================

    private fun word(id: String, text: String, s: Long, e: Long) =
        TranscribedWord(id = id, text = text, startTimeMs = s, endTimeMs = e)

    /**
     * A phrase the segmenter split too aggressively: «امروز صبح» + «رفتم
     * به بازار». Both halves carry real word timings, as the generator
     * produces.
     */
    private fun firstFragment(): TextOverlay = TextOverlay(
        id = "sub-a",
        text = "امروز صبح",
        startTimeMs = 0L,
        endTimeMs = 1200L,
        words = listOf(
            word("w0", "امروز", 0L, 700L),
            word("w1", "صبح", 800L, 1200L)
        ),
        subtitleSource = "ai"
    )

    private fun secondFragment(): TextOverlay = TextOverlay(
        id = "sub-b",
        text = "رفتم به بازار",
        startTimeMs = 1300L,
        endTimeMs = 2600L,
        words = listOf(
            word("w2", "رفتم", 1300L, 1700L),
            word("w3", "به", 1800L, 1950L),
            word("w4", "بازار", 2000L, 2600L)
        ),
        subtitleSource = "ai"
    )

    /** The user's real merge: two fragments back into one caption. */
    @Test
    fun `merge rejoins two over-segmented fragments`() {
        val merged = SubtitleEditOps.merge(firstFragment(), secondFragment())!!
        assertEquals("امروز صبح رفتم به بازار", merged.text)
        assertEquals(5, merged.words.size)
        assertEquals(0L, merged.startTimeMs)
        assertEquals(2600L, merged.endTimeMs)
        // Order is the speech order, so karaoke walks the sentence.
        assertEquals(
            listOf("امروز", "صبح", "رفتم", "به", "بازار"),
            merged.words.map { it.text }
        )
    }

    @Test
    fun `merge then split restores the two fragments' words`() {
        val a = firstFragment()
        val b = secondFragment()
        val merged = SubtitleEditOps.merge(a, b)!!
        // Split the joined caption at the boundary that separates the
        // original fragments (2 words in) and compare word content.
        val (left, right) = SubtitleEditOps.splitAt(merged, 2)!!
        assertEquals(a.words, left.words)
        assertEquals(b.words, right.words)
    }

    @Test
    fun `the user's real edit sequence is reversible through history snapshots`() {
        // Mirrors HistoryDelegate: snapshot the whole document before
        // each mutation, then restore it to undo.
        var document = listOf(firstFragment(), secondFragment())
        val history = ArrayDeque<List<TextOverlay>>()

        // --- merge ---
        history.addLast(document)
        val merged = SubtitleEditOps.merge(document[0], document[1])!!
        document = listOf(merged)
        assertEquals(1, document.size)

        // --- split ---
        history.addLast(document)
        val (l, r) = SubtitleEditOps.splitAt(document[0], 2)!!
        document = listOf(l, r)
        assertEquals(2, document.size)

        // --- split words are still coherent ---
        assertEquals(merged.words, document[0].words + document[1].words)

        // --- undo the split ---
        document = history.removeLast()
        assertEquals(1, document.size)

        // --- undo the merge: the original two fragments are back ---
        document = history.removeLast()
        assertEquals(2, document.size)
        assertEquals(firstFragment().words, document[0].words)
        assertEquals(secondFragment().words, document[1].words)
        // And the frames came back exactly — merge/split are word
        // derived, and these fixtures are word derived too.
        assertEquals(0L, document[0].startTimeMs)
        assertEquals(1200L, document[0].endTimeMs)
        assertEquals(1300L, document[1].startTimeMs)
        assertEquals(2600L, document[1].endTimeMs)
    }

    @Test
    fun `retiming a boundary does not disturb the words a user wants back`() {
        val original = firstFragment()
        // Stretch the tail to cover a pause, then pull it back.
        val stretched = SubtitleEditOps.retime(original, 0L, 2000L)
        assertEquals(2000L, stretched.endTimeMs)
        assertEquals(original.words, stretched.words)

        val restored = SubtitleEditOps.retime(stretched, original.startTimeMs, original.endTimeMs)
        assertEquals(original, restored)
    }

    @Test
    fun `nudging one word fixes a mis-timed clitic without touching the others`() {
        val original = firstFragment()
        // «صبح» was reported 100 ms late; pull it back.
        val fixed = SubtitleEditOps.retimeWord(original, 1, 700L, 1100L)!!
        assertEquals(700L, fixed.words[1].startTimeMs)
        assertEquals(1100L, fixed.words[1].endTimeMs)
        // The first word is untouched, and its text survived.
        assertEquals(original.words[0], fixed.words[0])
        assertEquals("صبح", fixed.words[1].text)
    }

    @Test
    fun `a rejected edit produces no document change`() {
        // Out-of-range split and merge-with-overlap are both refused.
        val a = firstFragment()
        assertNull(SubtitleEditOps.splitAt(a, 0))
        assertNull(SubtitleEditOps.splitAt(a, 99))
        assertNull(SubtitleEditOps.merge(a, a))
        // The ViewModel contract is "null ⇒ no push, no persist", so a
        // refusal cannot pollute the undo stack.
    }

    // ==================================================================
    // B. Wiring — source-shape guards on the ViewModel actions
    // ==================================================================

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun viewModelSource(): String {
        val f = File(
            repoRoot(),
            "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
        )
        assertTrue("EditorViewModel.kt missing at $f", f.isFile)
        return f.readText()
    }

    /**
     * Strips `//` and block comments so a KDoc that merely *mentions* a
     * call cannot satisfy a guard. This project has already been bitten
     * once by a comment quoting code that had been removed.
     */
    private fun codeLinesOnly(source: String): String {
        val blockEnd = "*" + "/"
        val out = StringBuilder()
        var inBlock = false
        source.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (inBlock) {
                if (trimmed.contains(blockEnd)) inBlock = false
                return@forEach
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains(blockEnd) || trimmed.indexOf(blockEnd) == trimmed.lastIndexOf(blockEnd)) {
                    inBlock = true
                }
                if (trimmed.contains(blockEnd)) inBlock = false
                return@forEach
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEach
            out.appendLine(line)
        }
        return out.toString()
    }

    /** Extract one function body by brace matching from its signature. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("no opening brace after: $signature", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        throw IllegalStateException("unbalanced braces after: $signature")
    }

    @Test
    fun `the split action delegates to SubtitleEditOps`() {
        val code = codeLinesOnly(viewModelSource())
        val body = bodyOf(code, "fun splitSubtitleAt(")
        assertTrue("splitSubtitleAt must use SubtitleEditOps", body.contains("SubtitleEditOps.splitAt("))
    }

    @Test
    fun `the merge action delegates to SubtitleEditOps`() {
        val code = codeLinesOnly(viewModelSource())
        val body = bodyOf(code, "fun mergeSubtitleWithNext(")
        assertTrue("mergeSubtitleWithNext must use SubtitleEditOps", body.contains("SubtitleEditOps.merge("))
    }

    @Test
    fun `the retime action delegates to SubtitleEditOps`() {
        val code = codeLinesOnly(viewModelSource())
        val body = bodyOf(code, "fun retimeSubtitle(")
        assertTrue("retimeSubtitle must use SubtitleEditOps", body.contains("SubtitleEditOps.retime("))
    }

    @Test
    fun `the per-word retime action delegates to SubtitleEditOps`() {
        val code = codeLinesOnly(viewModelSource())
        val body = bodyOf(code, "fun retimeSubtitleWord(")
        assertTrue("retimeSubtitleWord must use SubtitleEditOps", body.contains("SubtitleEditOps.retimeWord("))
    }

    @Test
    fun `every new editing action pushes history`() {
        val code = codeLinesOnly(viewModelSource())
        listOf(
            "fun splitSubtitleAt(",
            "fun mergeSubtitleWithNext(",
            "fun retimeSubtitle(",
            "fun retimeSubtitleWord(",
            "fun resyncSubtitleTextFromWords("
        ).forEach { signature ->
            val body = bodyOf(code, signature)
            assertTrue(
                "$signature must call historyDelegate.push() so the edit is undoable",
                body.contains("historyDelegate.push()")
            )
        }
    }

    @Test
    fun `every new editing action persists the project`() {
        val code = codeLinesOnly(viewModelSource())
        listOf(
            "fun splitSubtitleAt(",
            "fun mergeSubtitleWithNext(",
            "fun retimeSubtitle(",
            "fun retimeSubtitleWord(",
            "fun resyncSubtitleTextFromWords("
        ).forEach { signature ->
            val body = bodyOf(code, signature)
            assertTrue(
                "$signature must call persistProjectMeta() or the edit is lost on reopen",
                body.contains("persistProjectMeta()")
            )
        }
    }

    @Test
    fun `a rejected operation returns before pushing history`() {
        val code = codeLinesOnly(viewModelSource())
        // Each op ends with `?: return` (or an explicit equality guard)
        // BEFORE the push — otherwise a refused edit would still add an
        // undo entry that visibly does nothing.
        listOf(
            "fun splitSubtitleAt(",
            "fun mergeSubtitleWithNext(",
            "fun retimeSubtitleWord("
        ).forEach { signature ->
            val body = bodyOf(code, signature)
            val pushAt = body.indexOf("historyDelegate.push()")
            assertTrue("$signature must push", pushAt >= 0)
            val guardBeforePush = body.substring(0, pushAt)
            assertTrue(
                "$signature must return early on a rejected op (before push); body was:\n$guardBeforePush",
                guardBeforePush.contains("return")
            )
        }
    }

    @Test
    fun `split keeps the right half registered as an AI subtitle`() {
        val code = codeLinesOnly(viewModelSource())
        val body = bodyOf(code, "fun splitSubtitleAt(")
        // The new right-hand overlay must join the subtitle id set, or
        // "apply to all" and SRT export would skip it.
        assertTrue(
            "splitSubtitleAt must register the new overlay in aiSubtitleOverlayIds",
            body.contains("aiSubtitleOverlayIds.add(")
        )
    }

    @Test
    fun `merge retires the absorbed overlay from the AI subtitle set`() {
        val code = codeLinesOnly(viewModelSource())
        val body = bodyOf(code, "fun mergeSubtitleWithNext(")
        assertTrue(
            "mergeSubtitleWithNext must drop the absorbed id from aiSubtitleOverlayIds",
            body.contains("aiSubtitleOverlayIds.remove(")
        )
    }

    @Test
    fun `the editing actions are declared on the ViewModel`() {
        val code = codeLinesOnly(viewModelSource())
        // Guards against a future refactor deleting or renaming a public
        // action the Subtitle Studio panel calls.
        listOf(
            "fun splitSubtitleAt(",
            "fun mergeSubtitleWithNext(",
            "fun retimeSubtitle(",
            "fun retimeSubtitleWord(",
            "fun resyncSubtitleTextFromWords("
        ).forEach { signature ->
            assertTrue("missing public action: $signature", code.contains(signature))
        }
    }

    @Test
    fun `SubtitleEditOps is imported by the ViewModel`() {
        val code = codeLinesOnly(viewModelSource())
        assertNotNull(
            "EditorViewModel must import SubtitleEditOps",
            Regex("import com\\.aicreatorstudio\\.app\\.model\\.SubtitleEditOps")
                .find(code)
        )
    }
}
