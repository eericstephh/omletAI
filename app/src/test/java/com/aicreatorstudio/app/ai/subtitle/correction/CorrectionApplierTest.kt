package com.aicreatorstudio.app.ai.subtitle.correction

import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.model.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Applying accepted corrections.
 *
 * The invariants asserted here are the brief's hard requirements:
 * ids, timings and ordering survive; only `text` changes; a word-count
 * change re-times karaoke words INSIDE the segment's own measured
 * window (never outside it, never backwards); and text the user did
 * not accept is left byte-identical.
 */
class CorrectionApplierTest {

    private fun overlay(
        id: String,
        text: String,
        start: Long = 1_000L,
        end: Long = 3_000L,
        words: List<TranscribedWord> = emptyList(),
        source: String? = "ai"
    ) = TextOverlay(
        id = id,
        text = text,
        startTimeMs = start,
        endTimeMs = end,
        words = words,
        subtitleSource = source
    )

    private fun words(vararg texts: String): List<TranscribedWord> {
        // Four equal spans inside 1000..3000 for the given tokens.
        val step = 2_000L / texts.size
        return texts.mapIndexed { i, t ->
            TranscribedWord(
                id = "w$i",
                text = t,
                startTimeMs = 1_000L + step * i,
                endTimeMs = 1_000L + step * (i + 1),
                confidence = 0.9f
            )
        }
    }

    // ---- preservation ----

    @Test
    fun `ids timings and order are preserved`() {
        val list = listOf(
            overlay("a", "نرمفزار", 0L, 500L),
            overlay("b", "دوشوار", 500L, 900L),
            overlay("c", "کمکاربورد", 900L, 1_400L)
        )
        val result = CorrectionApplier.apply(
            list,
            mapOf(
                "a" to "نرم‌افزار",
                "b" to "دشوار",
                "c" to "کم‌کاربرد"
            )
        )

        assertEquals(3, result.changed)
        assertEquals(listOf("a", "b", "c"), result.overlays.map { it.id })
        assertEquals(listOf(0L, 500L, 900L), result.overlays.map { it.startTimeMs })
        assertEquals(listOf(500L, 900L, 1_400L), result.overlays.map { it.endTimeMs })
        assertEquals(
            listOf("نرم‌افزار", "دشوار", "کم‌کاربرد"),
            result.overlays.map { it.text }
        )
    }

    @Test
    fun `unaccepted overlays are untouched instances`() {
        val a = overlay("a", "تلفز")
        val b = overlay("b", "استنبات")
        val result = CorrectionApplier.apply(listOf(a, b), mapOf("a" to "تلفظ"))

        assertEquals(1, result.changed)
        // Same object, not a copy: nothing about `b` was rewritten.
        assertTrue(result.overlays[1] === b)
        assertEquals("استنبات", result.overlays[1].text)
    }

    @Test
    fun `identical correction is not counted as a change`() {
        val a = overlay("a", "خواهر")
        val result = CorrectionApplier.apply(listOf(a), mapOf("a" to "خواهر"))
        assertEquals(0, result.changed)
        assertTrue(result.overlays[0] === a)
    }

    @Test
    fun `unknown ids are ignored rather than resurrected`() {
        val a = overlay("a", "سلام")
        val result = CorrectionApplier.apply(listOf(a), mapOf("ghost" to "متن جدید"))
        assertEquals(0, result.changed)
        assertEquals(1, result.overlays.size)
        assertEquals("سلام", result.overlays[0].text)
    }

    @Test
    fun `blank correction is refused`() {
        val a = overlay("a", "سلام")
        val result = CorrectionApplier.apply(listOf(a), mapOf("a" to "   "))
        assertEquals(0, result.changed)
        assertEquals("سلام", result.overlays[0].text)
    }

    // ---- karaoke word timings ----

    @Test
    fun `same word count keeps every original word timing and id`() {
        val original = words("تلفز", "من", "درست", "است")
        val a = overlay("a", "تلفز من درست است", words = original)

        val result = CorrectionApplier.apply(listOf(a), mapOf("a" to "تلفظ من درست است"))
        val updated = result.overlays[0]

        assertEquals(0, result.retimed)
        assertEquals(original.map { it.id }, updated.words.map { it.id })
        assertEquals(original.map { it.startTimeMs }, updated.words.map { it.startTimeMs })
        assertEquals(original.map { it.endTimeMs }, updated.words.map { it.endTimeMs })
        assertEquals("تلفظ", updated.words[0].text)
        // The model's confidence described a real token and is kept.
        assertEquals(0.9f, updated.words[0].confidence)
    }

    @Test
    fun `word count change re-times inside the original window`() {
        // "کار آمد" (2 tokens) -> "کارآمد" (1 token).
        val original = words("کار", "آمد")
        val a = overlay("a", "کار آمد", start = 1_000L, end = 3_000L, words = original)

        val result = CorrectionApplier.apply(listOf(a), mapOf("a" to "کارآمد"))
        val updated = result.overlays[0]

        assertEquals(1, result.retimed)
        assertEquals("کارآمد", updated.text)
        assertEquals(1, updated.words.size)
        // Never outside the segment's own measured speech window.
        assertTrue(updated.words[0].startTimeMs >= 1_000L)
        assertTrue(updated.words[0].endTimeMs <= 3_000L)
        // The overlay's own timing is untouched.
        assertEquals(1_000L, updated.startTimeMs)
        assertEquals(3_000L, updated.endTimeMs)
    }

    @Test
    fun `re-timed words are monotonic and strictly non-empty`() {
        val original = words("یک", "دو", "سه", "چهار")
        val a = overlay("a", "یک دو سه چهار", start = 0L, end = 4_000L, words = original)

        val result = CorrectionApplier.apply(
            listOf(a),
            mapOf("a" to "یک‌دو سه چهار پنج شش هفت")
        )
        val retimed = result.overlays[0].words

        assertEquals(6, retimed.size)
        for (i in retimed.indices) {
            assertTrue(
                "word $i must have a non-zero span",
                retimed[i].endTimeMs > retimed[i].startTimeMs
            )
            if (i > 0) {
                assertTrue(
                    "word $i must not start before the previous one ends",
                    retimed[i].startTimeMs >= retimed[i - 1].endTimeMs
                )
            }
        }
        assertTrue(retimed.first().startTimeMs >= 0L)
        assertTrue(retimed.last().endTimeMs <= 4_000L)
    }

    @Test
    fun `re-timed words drop a confidence that no longer applies`() {
        val a = overlay("a", "کار آمد", words = words("کار", "آمد"))
        val result = CorrectionApplier.apply(listOf(a), mapOf("a" to "کارآمد"))
        assertNull(result.overlays[0].words[0].confidence)
    }

    @Test
    fun `overlay without words gains none`() {
        // SRT imports and hand-typed captions have no word timings.
        val a = overlay("a", "کار آمد", words = emptyList(), source = "srt")
        val result = CorrectionApplier.apply(listOf(a), mapOf("a" to "کارآمد"))
        assertEquals("کارآمد", result.overlays[0].text)
        assertTrue(result.overlays[0].words.isEmpty())
        assertEquals("srt", result.overlays[0].subtitleSource)
    }

    @Test
    fun `tokenize never splits on zwnj`() {
        assertEquals(
            listOf("کم‌کاربرد", "و", "نرم‌افزار"),
            CorrectionApplier.tokenize("کم‌کاربرد و نرم‌افزار")
        )
    }

    // ---- preview building ----

    @Test
    fun `preview lists only changed rows and counts the rest`() {
        val list = listOf(
            overlay("a", "نرمفزار"),
            overlay("b", "سلام"),
            overlay("c", "خاهر")
        )
        val preview = CorrectionApplier.buildPreview(
            overlays = list,
            corrected = listOf(
                CorrectedSegment("a", "نرم‌افزار"),
                CorrectedSegment("b", "سلام"),
                CorrectedSegment("c", "خواهر")
            )
        )

        assertEquals(2, preview.items.size)
        assertEquals(1, preview.unchangedCount)
        assertEquals(listOf("a", "c"), preview.items.map { it.overlayId })
        assertEquals("نرمفزار", preview.items[0].original)
        assertEquals("نرم‌افزار", preview.items[0].corrected)
        assertFalse(preview.items[0].retimesWords)
    }

    @Test
    fun `preview flags the rows that will re-time karaoke words`() {
        val list = listOf(
            overlay("a", "کار آمد", words = words("کار", "آمد")),
            overlay("b", "تلفز", words = words("تلفز"))
        )
        val preview = CorrectionApplier.buildPreview(
            overlays = list,
            corrected = listOf(
                CorrectedSegment("a", "کارآمد"),
                CorrectedSegment("b", "تلفظ")
            )
        )
        assertTrue(preview.items[0].retimesWords)
        assertFalse(preview.items[1].retimesWords)
        assertEquals(1, preview.retimedCount)
    }

    @Test
    fun `preview carries the failed batches so the user is told`() {
        val preview = CorrectionApplier.buildPreview(
            overlays = listOf(overlay("a", "x")),
            corrected = emptyList(),
            failedBatches = listOf(0, 1),
            failureMessage = "محدودیت نرخ"
        )
        assertTrue(preview.isEmpty)
        assertEquals(2, preview.failedBatches.size)
        assertEquals("محدودیت نرخ", preview.failureMessage)
    }
}
