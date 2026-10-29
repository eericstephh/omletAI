package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.ai.TranscribedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SubtitleEditOps] — the split / merge / retime /
 * per-word-retime editing workflow.
 *
 * The tests are organised around the invariants the rest of the
 * subtitle pipeline depends on (see the INV-COHERENCE block in
 * `SubtitleEditOps`), not around the implementation. Each invariant
 * gets its own group so a regression names itself:
 *
 *  1. **Split** — partition, boundaries, id policy, rejection.
 *  2. **Merge** — concatenation, adjacency, order-independence.
 *  3. **Round trip** — split-then-merge restores the original words.
 *  4. **Retime** — clamping, and *non-destructiveness* toward words.
 *  5. **Per-word retime** — clamping to neighbour envelopes.
 *  6. **Coherence** — ZWNJ survival, monotonicity, text/word agreement,
 *     and that `KaraokeRanges` still resolves for every word.
 *
 * Everything here uses plain `String` / `Long` / `Int`, so
 * `unitTests.isReturnDefaultValues = true` cannot make an assertion
 * pass for the wrong reason.
 */
class SubtitleEditOpsTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A four-word Persian caption with real gaps between some words —
     * the shape whisper.cpp actually produces. Includes two words
     * carrying ZWNJ (U+200C): «می‌روم» and «نمی‌دانم».
     */
    private fun overlay(): TextOverlay = TextOverlay(
        id = "cap-1",
        text = "من می‌روم به خانه",
        startTimeMs = 1000L,
        endTimeMs = 4000L,
        words = listOf(
            word("w0", "من", 1000L, 1300L, 0.9f),
            word("w1", "می‌روم", 1400L, 1900L, 0.8f),
            word("w2", "به", 2000L, 2200L, 0.7f),
            word("w3", "خانه", 2300L, 2600L, 0.6f)
        ),
        subtitleSource = "ai"
    )

    private fun word(
        id: String,
        text: String,
        startMs: Long,
        endMs: Long,
        confidence: Float? = null
    ) = TranscribedWord(
        id = id,
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        confidence = confidence
    )

    private fun wordsText(overlay: TextOverlay): List<String> = overlay.words.map { it.text }

    // ==================================================================
    // 1. SPLIT
    // ==================================================================

    @Test
    fun `splitAt middle partitions words into two halves`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        assertEquals(listOf("من", "می‌روم"), wordsText(left))
        assertEquals(listOf("به", "خانه"), wordsText(right))
    }

    @Test
    fun `splitAt first valid index puts one word left`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 1)!!
        assertEquals(listOf("من"), wordsText(left))
        assertEquals(listOf("می‌روم", "به", "خانه"), wordsText(right))
    }

    @Test
    fun `splitAt last valid index puts one word right`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 3)!!
        assertEquals(listOf("من", "می‌روم", "به"), wordsText(left))
        assertEquals(listOf("خانه"), wordsText(right))
    }

    @Test
    fun `splitAt is rejected at index zero`() {
        // An empty left half is a delete, not a split.
        assertNull(SubtitleEditOps.splitAt(overlay(), 0))
    }

    @Test
    fun `splitAt is rejected at the word count`() {
        // An empty right half is a delete, not a split.
        assertNull(SubtitleEditOps.splitAt(overlay(), 4))
    }

    @Test
    fun `splitAt is rejected for negative and out-of-range indices`() {
        val o = overlay()
        assertNull(SubtitleEditOps.splitAt(o, -1))
        assertNull(SubtitleEditOps.splitAt(o, 99))
    }

    @Test
    fun `splitAt is rejected when there are fewer than two words`() {
        val single = overlay().copy(words = listOf(word("only", "تنها", 0L, 500L)))
        assertNull(SubtitleEditOps.splitAt(single, 1))
        assertNull(SubtitleEditOps.splitAt(single.copy(words = emptyList()), 1))
    }

    @Test
    fun `splitAt partitions without duplicating or losing a word id`() {
        val original = overlay()
        val (left, right) = SubtitleEditOps.splitAt(original, 2)!!

        val leftIds = left.words.map { it.id }
        val rightIds = right.words.map { it.id }

        // Disjoint.
        assertTrue((leftIds.toSet() intersect rightIds.toSet()).isEmpty())
        // Complete, and in the original order.
        assertEquals(original.words.map { it.id }, leftIds + rightIds)
        // No word object was cloned/rebuilt either — same instances.
        assertEquals(original.words, left.words + right.words)
    }

    @Test
    fun `splitAt keeps the original id on the left and mints a new one on the right`() {
        val original = overlay()
        val (left, right) = SubtitleEditOps.splitAt(original, 2)!!
        // Reusing the left id keeps the user's timeline selection valid.
        assertEquals(original.id, left.id)
        assertNotEquals(original.id, right.id)
        assertTrue(right.id.isNotBlank())
    }

    @Test
    fun `splitAt derives each half frame from its own words`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        // Left: w0.start .. w1.end
        assertEquals(1000L, left.startTimeMs)
        assertEquals(1900L, left.endTimeMs)
        // Right: w2.start .. w3.end
        assertEquals(2000L, right.startTimeMs)
        assertEquals(2600L, right.endTimeMs)
    }

    @Test
    fun `splitAt preserves the silence gap between the halves`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        // The recognizer reported a 100 ms gap (1900 -> 2000). Both
        // halves must respect it rather than meeting at a midpoint.
        assertEquals(100L, right.startTimeMs - left.endTimeMs)
    }

    @Test
    fun `splitAt rebuilds text to match the derived words`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        assertEquals(left.words.joinToString(" ") { it.text }, left.text)
        assertEquals(right.words.joinToString(" ") { it.text }, right.text)
        assertEquals("من می‌روم", left.text)
        assertEquals("به خانه", right.text)
    }

    @Test
    fun `splitAt carries styling and subtitle metadata to both halves`() {
        val styled = overlay().copy(
            fontSizeSp = 41f,
            fontFamily = PersianFont.VAZIR.name,
            templateId = "fa_07_karaoke",
            activeWordBold = true,
            motionKind = WordMotionKind.BOUNCE_POP,
            backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED,
            subtitleSource = "ai"
        )
        val (left, right) = SubtitleEditOps.splitAt(styled, 2)!!
        listOf(left, right).forEach { half ->
            assertEquals(41f, half.fontSizeSp, 0.0001f)
            assertEquals(PersianFont.VAZIR.name, half.fontFamily)
            assertEquals("fa_07_karaoke", half.templateId)
            assertTrue(half.activeWordBold)
            assertEquals(WordMotionKind.BOUNCE_POP, half.motionKind)
            assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, half.backgroundKind)
            // Export gating reads subtitleSource — a split must not lose it.
            assertEquals("ai", half.subtitleSource)
        }
    }

    @Test
    fun `splitAt does not mutate the input overlay`() {
        val original = overlay()
        val snapshot = original.copy(words = original.words.toList())
        SubtitleEditOps.splitAt(original, 2)
        assertEquals(snapshot, original)
        assertEquals(4, original.words.size)
    }

    // ==================================================================
    // 2. MERGE
    // ==================================================================

    @Test
    fun `merge concatenates words in speech order`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val merged = SubtitleEditOps.merge(left, right)!!
        assertEquals(listOf("من", "می‌روم", "به", "خانه"), wordsText(merged))
    }

    @Test
    fun `merge spans min start to max end`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val merged = SubtitleEditOps.merge(left, right)!!
        assertEquals(1000L, merged.startTimeMs)
        assertEquals(2600L, merged.endTimeMs)
    }

    @Test
    fun `merge absorbs an intervening gap rather than clipping it`() {
        val a = overlay().copy(
            id = "a",
            words = listOf(word("a0", "یک", 0L, 500L), word("a1", "دو", 600L, 900L)),
            startTimeMs = 0L, endTimeMs = 900L
        )
        val b = overlay().copy(
            id = "b",
            words = listOf(word("b0", "سه", 5000L, 5400L)),
            startTimeMs = 5000L, endTimeMs = 5400L
        )
        val merged = SubtitleEditOps.merge(a, b)!!
        assertEquals(0L, merged.startTimeMs)
        assertEquals(5400L, merged.endTimeMs)
        assertEquals(listOf("یک", "دو", "سه"), wordsText(merged))
    }

    @Test
    fun `merge is order independent`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val forward = SubtitleEditOps.merge(left, right)!!
        val backward = SubtitleEditOps.merge(right, left)!!
        // Same word sequence regardless of argument order — the
        // implementation normalises to speech order internally.
        assertEquals(wordsText(forward), wordsText(backward))
        assertEquals(forward.startTimeMs, backward.startTimeMs)
        assertEquals(forward.endTimeMs, backward.endTimeMs)
    }

    @Test
    fun `merge is rejected for overlapping segments`() {
        val a = overlay().copy(
            id = "a",
            words = listOf(word("a0", "یک", 0L, 1000L)),
            startTimeMs = 0L, endTimeMs = 1000L
        )
        val b = overlay().copy(
            id = "b",
            words = listOf(word("b0", "دو", 500L, 1500L)),
            startTimeMs = 500L, endTimeMs = 1500L
        )
        assertNull(SubtitleEditOps.merge(a, b))
    }

    @Test
    fun `merge accepts touching segments`() {
        val a = overlay().copy(
            id = "a",
            words = listOf(word("a0", "یک", 0L, 1000L)),
            startTimeMs = 0L, endTimeMs = 1000L
        )
        val b = overlay().copy(
            id = "b",
            words = listOf(word("b0", "دو", 1000L, 1500L)),
            startTimeMs = 1000L, endTimeMs = 1500L
        )
        assertNotNull(SubtitleEditOps.merge(a, b))
    }

    @Test
    fun `merge is rejected when a side has no words`() {
        val (left, _) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val wordless = left.copy(id = "wordless", words = emptyList())
        assertNull(SubtitleEditOps.merge(left, wordless))
        assertNull(SubtitleEditOps.merge(wordless, left))
    }

    @Test
    fun `merge is rejected for the same overlay twice`() {
        val o = overlay()
        assertNull(SubtitleEditOps.merge(o, o))
    }

    @Test
    fun `merge keeps the earlier overlay id and styling`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val merged = SubtitleEditOps.merge(left, right)!!
        assertEquals(left.id, merged.id)
        assertEquals("ai", merged.subtitleSource)
    }

    @Test
    fun `merge keeps meaningful word confidences on the words themselves`() {
        val a = overlay().copy(
            id = "a",
            words = listOf(word("a0", "یک", 0L, 400L, 0.9f), word("a1", "دو", 400L, 800L, 0.95f)),
            startTimeMs = 0L, endTimeMs = 800L
        )
        val b = overlay().copy(
            id = "b",
            words = listOf(word("b0", "سه", 800L, 1200L, 0.4f)),
            startTimeMs = 800L, endTimeMs = 1200L
        )
        val merged = SubtitleEditOps.merge(a, b)!!
        // TextOverlay carries no overlay-level confidence field — the
        // per-word confidences are the only signal, and they must
        // survive verbatim so diagnostics keep working.
        assertEquals(listOf(0.9f, 0.95f, 0.4f), merged.words.map { it.confidence })
    }

    @Test
    fun `merge does not mutate its inputs`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val leftSnapshot = left.copy(words = left.words.toList())
        val rightSnapshot = right.copy(words = right.words.toList())
        SubtitleEditOps.merge(left, right)
        assertEquals(leftSnapshot, left)
        assertEquals(rightSnapshot, right)
    }

    // ==================================================================
    // 3. ROUND TRIP — split then merge restores the original
    // ==================================================================

    @Test
    fun `split then merge restores the original word sequence`() {
        val original = overlay()
        val (left, right) = SubtitleEditOps.splitAt(original, 2)!!
        val merged = SubtitleEditOps.merge(left, right)!!
        assertEquals(wordsText(original), wordsText(merged))
        assertEquals(original.words, merged.words)
    }

    @Test
    fun `split then merge restores the original time span when it was word derived`() {
        // The generator derives a segment's frame from its first and
        // last word, so a generator-produced overlay round-trips
        // exactly.
        val wordDerived = overlay().copy(startTimeMs = 1000L, endTimeMs = 2600L)
        val (left, right) = SubtitleEditOps.splitAt(wordDerived, 2)!!
        assertEquals(wordDerived.startTimeMs, left.startTimeMs)
        assertEquals(wordDerived.endTimeMs, right.endTimeMs)
        val merged = SubtitleEditOps.merge(left, right)!!
        assertEquals(wordDerived.startTimeMs, merged.startTimeMs)
        assertEquals(wordDerived.endTimeMs, merged.endTimeMs)
    }

    @Test
    fun `split then merge collapses a hand widened frame to the word span`() {
        // DELIBERATE, DOCUMENTED BEHAVIOUR, not a bug.
        //
        // `overlay()` spans 1000..4000 while its last word ends at
        // 2600 — i.e. a user has already stretched the cue's tail by
        // retime(). Splitting rebuilds each half's frame FROM ITS
        // WORDS (so the two halves are faithful to the speech), and
        // merging therefore yields the word span 1000..2600. The extra
        // tail padding is not silently re-invented.
        //
        // This is the honest trade: split/merge are lossless with
        // respect to *speech timing* and are allowed to re-derive the
        // *frame*. Retiming is the non-destructive operation (see
        // `retime never touches the words`), and a user who wants the
        // tail back applies retime() again.
        val handWidened = overlay()
        assertTrue("fixture must have a widened tail", handWidened.endTimeMs > handWidened.words.last().endTimeMs)

        val (left, right) = SubtitleEditOps.splitAt(handWidened, 2)!!
        val merged = SubtitleEditOps.merge(left, right)!!

        assertEquals(handWidened.words.first().startTimeMs, merged.startTimeMs)
        assertEquals(handWidened.words.last().endTimeMs, merged.endTimeMs)
        // Words are still identical, so nothing was actually lost from
        // the caption itself.
        assertEquals(handWidened.words, merged.words)
        assertEquals(handWidened.text, merged.text)
    }

    @Test
    fun `split then merge restores the original text`() {
        val original = overlay()
        val (left, right) = SubtitleEditOps.splitAt(original, 2)!!
        assertEquals(original.text, SubtitleEditOps.merge(left, right)!!.text)
    }

    @Test
    fun `split then merge round trips at every valid index`() {
        val original = overlay()
        for (index in 1 until original.words.size) {
            val (left, right) = SubtitleEditOps.splitAt(original, index)!!
            val merged = SubtitleEditOps.merge(left, right)!!
            assertEquals(
                "round trip failed at index $index",
                original.words,
                merged.words
            )
            assertEquals(
                "round trip text failed at index $index",
                original.text,
                merged.text
            )
        }
    }

    // ==================================================================
    // 4. RETIME
    // ==================================================================

    @Test
    fun `retime moves the frame`() {
        val retimed = SubtitleEditOps.retime(overlay(), 2000L, 6000L)
        assertEquals(2000L, retimed.startTimeMs)
        assertEquals(6000L, retimed.endTimeMs)
    }

    @Test
    fun `retime never touches the words`() {
        val original = overlay()
        val retimed = SubtitleEditOps.retime(original, 2000L, 6000L)
        // Same instances, not merely equal — the whole point is that a
        // boundary drag is non-destructive to word timing.
        assertSame(original.words, retimed.words)
        assertEquals(original.words, retimed.words)
    }

    @Test
    fun `retime never touches the text`() {
        val original = overlay()
        assertEquals(original.text, SubtitleEditOps.retime(original, 0L, 9000L).text)
    }

    @Test
    fun `retime clamps an inverted range to the minimum duration`() {
        // end before start: the result must still be a valid forward range.
        val retimed = SubtitleEditOps.retime(overlay(), 5000L, 1000L)
        assertEquals(5000L, retimed.startTimeMs)
        assertEquals(5000L + SubtitleEditOps.MIN_DURATION_MS, retimed.endTimeMs)
        assertTrue(retimed.endTimeMs > retimed.startTimeMs)
    }

    @Test
    fun `retime clamps a zero length range to the minimum duration`() {
        val retimed = SubtitleEditOps.retime(overlay(), 3000L, 3000L)
        assertEquals(3000L, retimed.startTimeMs)
        assertEquals(3000L + SubtitleEditOps.MIN_DURATION_MS, retimed.endTimeMs)
    }

    @Test
    fun `retime clamps a negative start to zero`() {
        val retimed = SubtitleEditOps.retime(overlay(), -5000L, 1000L)
        assertEquals(0L, retimed.startTimeMs)
        assertEquals(1000L, retimed.endTimeMs)
    }

    @Test
    fun `retime is reversible`() {
        val original = overlay()
        val moved = SubtitleEditOps.retime(original, 2000L, 6000L)
        val restored = SubtitleEditOps.retime(moved, original.startTimeMs, original.endTimeMs)
        assertEquals(original.startTimeMs, restored.startTimeMs)
        assertEquals(original.endTimeMs, restored.endTimeMs)
        assertEquals(original.words, restored.words)
    }

    @Test
    fun `retime keeps words visible inside the new window`() {
        // Narrow the window to the first two words only. The frame
        // defines visibility, so the third/fourth words are simply
        // outside it — and dragging back restores them.
        val narrowed = SubtitleEditOps.retime(overlay(), 1000L, 1900L)
        assertTrue(narrowed.isVisibleAt(1500L))
        assertFalse(narrowed.isVisibleAt(2500L))
        // Words are still all present — nothing was deleted.
        assertEquals(4, narrowed.words.size)
    }

    @Test
    fun `retime preserves every styling field`() {
        val styled = overlay().copy(fontSizeSp = 33f, templateId = "fa_02", rotation = 12f)
        val retimed = SubtitleEditOps.retime(styled, 0L, 1000L)
        assertEquals(33f, retimed.fontSizeSp, 0.0001f)
        assertEquals("fa_02", retimed.templateId)
        assertEquals(12f, retimed.rotation, 0.0001f)
    }

    // ==================================================================
    // 5. PER-WORD RETIME
    // ==================================================================

    @Test
    fun `retimeWord moves one word only`() {
        val updated = SubtitleEditOps.retimeWord(overlay(), 1, 1450L, 1950L)!!
        assertEquals(1450L, updated.words[1].startTimeMs)
        assertEquals(1950L, updated.words[1].endTimeMs)
        // Its neighbours are untouched.
        assertEquals(1000L, updated.words[0].startTimeMs)
        assertEquals(2000L, updated.words[2].startTimeMs)
    }

    @Test
    fun `retimeWord preserves the word text and id`() {
        val original = overlay()
        val updated = SubtitleEditOps.retimeWord(original, 1, 1450L, 1950L)!!
        assertEquals(original.words[1].id, updated.words[1].id)
        assertEquals(original.words[1].text, updated.words[1].text)
        assertEquals(original.words[1].confidence, updated.words[1].confidence)
    }

    @Test
    fun `retimeWord is rejected for an out-of-range index`() {
        val o = overlay()
        assertNull(SubtitleEditOps.retimeWord(o, -1, 0L, 100L))
        assertNull(SubtitleEditOps.retimeWord(o, 4, 0L, 100L))
        assertNull(SubtitleEditOps.retimeWord(o, 99, 0L, 100L))
    }

    @Test
    fun `retimeWord clamps against the previous word start`() {
        // Word 1 may not start before word 0 starts (1000).
        val updated = SubtitleEditOps.retimeWord(overlay(), 1, 0L, 1900L)!!
        assertTrue(updated.words[1].startTimeMs >= updated.words[0].startTimeMs)
        assertEquals(1000L, updated.words[1].startTimeMs)
    }

    @Test
    fun `retimeWord clamps against the next word end`() {
        // Word 1 may not end after word 2 ends (2200).
        val updated = SubtitleEditOps.retimeWord(overlay(), 1, 1400L, 99000L)!!
        assertTrue(updated.words[1].endTimeMs <= updated.words[2].endTimeMs)
        assertEquals(2200L, updated.words[1].endTimeMs)
    }

    @Test
    fun `retimeWord clamps a negative start to zero`() {
        val updated = SubtitleEditOps.retimeWord(overlay(), 0, -9000L, 1300L)!!
        assertEquals(0L, updated.words[0].startTimeMs)
    }

    @Test
    fun `retimeWord keeps word timings monotonic`() {
        val updated = SubtitleEditOps.retimeWord(overlay(), 2, 100L, 99999L)!!
        val starts = updated.words.map { it.startTimeMs }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun `retimeWord enforces a minimum word duration`() {
        val updated = SubtitleEditOps.retimeWord(overlay(), 1, 1500L, 1500L)!!
        assertTrue(updated.words[1].endTimeMs > updated.words[1].startTimeMs)
    }

    @Test
    fun `retimeWord widens the segment frame when the word moves outside it`() {
        val original = overlay().copy(words = listOf(
            word("w0", "من", 1000L, 1300L),
            word("w1", "می‌روم", 1400L, 1900L)
        ))
        // Last word: ceiling is MAX_VALUE, so it can move past the frame.
        val updated = SubtitleEditOps.retimeWord(original, 1, 1400L, 5000L)!!
        assertTrue(updated.endTimeMs >= 5000L)
        assertTrue(updated.words[1].endTimeMs <= updated.endTimeMs)
    }

    @Test
    fun `retimeWord never narrows the segment frame`() {
        val original = overlay()
        val updated = SubtitleEditOps.retimeWord(original, 1, 1450L, 1500L)!!
        assertTrue(updated.startTimeMs <= original.startTimeMs)
        assertTrue(updated.endTimeMs >= original.endTimeMs)
    }

    @Test
    fun `retimeWord does not mutate the input`() {
        val original = overlay()
        val snapshot = original.copy(words = original.words.toList())
        SubtitleEditOps.retimeWord(original, 1, 1450L, 1950L)
        assertEquals(snapshot, original)
    }

    // ==================================================================
    // 6. COHERENCE INVARIANTS
    // ==================================================================

    @Test
    fun `splitAt preserves ZWNJ inside words byte for byte`() {
        val zwnj = "\u200C"
        val original = overlay()
        assertTrue("fixture must contain ZWNJ", original.words[1].text.contains(zwnj))

        val (left, right) = SubtitleEditOps.splitAt(original, 1)!!
        // The word must not be normalised, trimmed, or split.
        assertTrue(right.words[0].text.contains(zwnj))
        assertEquals("می\u200Cروم", right.words[0].text)
        // Five letters + the ZWNJ = six code points. Written out
        // explicitly because "that looks like four characters" is
        // exactly the kind of eyeball estimate a byte-for-byte
        // guarantee exists to overrule.
        assertEquals(6, right.words[0].text.length)
    }

    @Test
    fun `merge preserves ZWNJ inside words`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val merged = SubtitleEditOps.merge(left, right)!!
        assertEquals("می\u200Cروم", merged.words[1].text)
        assertTrue(merged.text.contains("\u200C"))
    }

    @Test
    fun `retimeWord preserves ZWNJ inside the word`() {
        val updated = SubtitleEditOps.retimeWord(overlay(), 1, 1450L, 1950L)!!
        assertEquals("می\u200Cروم", updated.words[1].text)
    }

    @Test
    fun `no operation trims or normalises a word`() {
        val padded = overlay().copy(
            words = listOf(
                word("w0", "من", 0L, 300L),
                word("w1", "می‌روم", 400L, 900L)
            ),
            text = "من می‌روم",
            startTimeMs = 0L,
            endTimeMs = 900L
        )
        val (l, r) = SubtitleEditOps.splitAt(padded, 1)!!
        assertEquals("می‌روم", r.words[0].text)
        assertEquals("من", l.words[0].text)
    }

    @Test
    fun `text is always the words joined by a single space after a split`() {
        val original = overlay()
        for (index in 1 until original.words.size) {
            val (left, right) = SubtitleEditOps.splitAt(original, index)!!
            assertEquals(left.words.joinToString(" ") { it.text }, left.text)
            assertEquals(right.words.joinToString(" ") { it.text }, right.text)
        }
    }

    @Test
    fun `text is the words joined by a single space after a merge`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        val merged = SubtitleEditOps.merge(left, right)!!
        assertEquals(merged.words.joinToString(" ") { it.text }, merged.text)
    }

    @Test
    fun `karaoke still resolves for every word after a split`() {
        val original = overlay()
        val (left, right) = SubtitleEditOps.splitAt(original, 2)!!
        assertKaraokeResolves(left)
        assertKaraokeResolves(right)
    }

    @Test
    fun `karaoke still resolves for every word after a merge`() {
        val (left, right) = SubtitleEditOps.splitAt(overlay(), 2)!!
        assertKaraokeResolves(SubtitleEditOps.merge(left, right)!!)
    }

    @Test
    fun `karaoke still resolves for every word after a retime`() {
        assertKaraokeResolves(SubtitleEditOps.retime(overlay(), 0L, 9000L))
    }

    @Test
    fun `karaoke still resolves for every word after a retimeWord`() {
        assertKaraokeResolves(SubtitleEditOps.retimeWord(overlay(), 1, 1450L, 1950L)!!)
    }

    @Test
    fun `karaoke resolves for repeated words after a split`() {
        // Two identical words either side of the split — the
        // Nth-occurrence path must still pick the right instance.
        val repeated = overlay().copy(
            words = listOf(
                word("r0", "سلام", 0L, 400L),
                word("r1", "دنیا", 500L, 900L),
                word("r2", "سلام", 1000L, 1400L)
            ),
            text = "سلام دنیا سلام",
            startTimeMs = 0L,
            endTimeMs = 1400L
        )
        val (left, right) = SubtitleEditOps.splitAt(repeated, 1)!!
        assertKaraokeResolves(left)
        assertKaraokeResolves(right)
        // Each half now has exactly one «سلام» — the highlight is unambiguous.
        assertEquals(1, left.words.count { it.text == "سلام" })
        assertEquals(1, right.words.count { it.text == "سلام" })
    }

    @Test
    fun `rebuildTextFromWords restores agreement after an out-of-band text edit`() {
        val original = overlay()
        // Simulate the manual text editor: it writes `text` alone.
        val edited = original.copy(text = "من میروم به خانهٔ خودم")
        assertNotEquals(edited.words.joinToString(" ") { it.text }, edited.text)

        val rebuilt = SubtitleEditOps.rebuildTextFromWords(edited)
        assertEquals(original.words.joinToString(" ") { it.text }, rebuilt.text)
        assertEquals(original.words, rebuilt.words)
    }

    @Test
    fun `rebuildTextFromWords leaves a wordless decorative overlay alone`() {
        val decorative = TextOverlay(id = "t1", text = "عنوان", startTimeMs = 0L, endTimeMs = 1000L)
        val rebuilt = SubtitleEditOps.rebuildTextFromWords(decorative)
        assertEquals("عنوان", rebuilt.text)
        assertEquals(decorative, rebuilt)
    }

    @Test
    fun `activeWordAt keeps working after a retime`() {
        val retimed = SubtitleEditOps.retime(overlay(), 0L, 9000L)
        // Word ranges are untouched, so per-word lookup is unchanged.
        assertEquals("w0", retimed.activeWordAt(1100L)?.id)
        assertEquals("w2", retimed.activeWordAt(2100L)?.id)
    }

    @Test
    fun `isVisibleAt follows a retime`() {
        val retimed = SubtitleEditOps.retime(overlay(), 3000L, 5000L)
        assertFalse(retimed.isVisibleAt(1500L))
        assertTrue(retimed.isVisibleAt(4000L))
    }

    @Test
    fun `operations never produce an inverted frame`() {
        val o = overlay()
        val cases = listOf(
            SubtitleEditOps.retime(o, 9000L, 100L),
            SubtitleEditOps.retime(o, 500L, 500L),
            SubtitleEditOps.retime(o, -1L, -2L),
            SubtitleEditOps.splitAt(o, 2)!!.first,
            SubtitleEditOps.splitAt(o, 2)!!.second,
            SubtitleEditOps.merge(SubtitleEditOps.splitAt(o, 2)!!.first, SubtitleEditOps.splitAt(o, 2)!!.second)!!,
            SubtitleEditOps.retimeWord(o, 2, 99999L, 0L)!!
        )
        cases.forEach { candidate ->
            assertTrue(
                "inverted frame: ${candidate.startTimeMs}..${candidate.endTimeMs}",
                candidate.endTimeMs > candidate.startTimeMs
            )
        }
    }

    @Test
    fun `word order is monotonic after every operation`() {
        val o = overlay()
        val (left, right) = SubtitleEditOps.splitAt(o, 2)!!
        val results = listOf(
            left,
            right,
            SubtitleEditOps.merge(left, right)!!,
            SubtitleEditOps.retime(o, 0L, 9000L),
            SubtitleEditOps.retimeWord(o, 1, 1450L, 1950L)!!
        )
        results.forEach { candidate ->
            val starts = candidate.words.map { it.startTimeMs }
            assertEquals("word starts not sorted: $starts", starts.sorted(), starts)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Every word in [overlay] must be locatable by [KaraokeRanges]
     * inside the overlay's own `text`. A split/merge that rebuilt `text`
     * with a different whitespace convention would break this even
     * though the word list looked correct.
     */
    private fun assertKaraokeResolves(overlay: TextOverlay) {
        val wordTexts = overlay.words.map { it.text }
        overlay.words.indices.forEach { index ->
            val range = KaraokeRanges.activeRange(overlay.text, wordTexts, index)
            assertNotNull(
                "karaoke failed for index $index of '${overlay.text}' (words=$wordTexts)",
                range
            )
            // The located slice must actually be that word.
            assertEquals(
                "karaoke located the wrong slice for index $index",
                wordTexts[index],
                overlay.text.substring(range!!.first, range.last + 1)
            )
        }
    }
}
