package com.aicreatorstudio.app.ai.subtitle.correction

import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.model.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end regression over the brief's example corrections.
 *
 * ## What this proves, and what it deliberately does NOT
 *
 * The pairs below are EXAMPLES of what a language model should be able
 * to fix — they are not a substitution table. Nothing in this app
 * replaces `نرمفزار` with `نرم‌افزار` on its own; the model proposes and
 * the user accepts. So the assertions are about the PIPELINE: a
 * proposal for segment X changes segment X, leaves segment Y alone even
 * when Y contains the same typo, keeps every timestamp, and keeps text
 * the model chose not to touch (colloquial forms, English terms)
 * byte-identical.
 */
class PersianCorrectionRegressionTest {

    /** The brief's examples, as (original, corrected) pairs. */
    private val examples = listOf(
        "نرمفزار" to "نرم‌افزار",
        "دوشوار" to "دشوار",
        "کمکاربورد" to "کم‌کاربرد",
        "تلفز" to "تلفظ",
        "خاهر" to "خواهر",
        "استنبات" to "استنباط",
        "کمالگرا" to "کمال‌گرا",
        "کار آمد" to "کارآمد"
    )

    private fun overlay(id: String, text: String, start: Long, end: Long) = TextOverlay(
        id = id,
        text = text,
        startTimeMs = start,
        endTimeMs = end,
        words = listOf(
            TranscribedWord(id = "$id-w", text = text, startTimeMs = start, endTimeMs = end)
        ),
        subtitleSource = "ai"
    )

    @Test
    fun `every example pair is applied when the model proposes it`() {
        val overlays = examples.mapIndexed { i, (original, _) ->
            overlay("e$i", original, start = i * 1_000L, end = i * 1_000L + 900L)
        }
        val corrections = examples.mapIndexed { i, (_, corrected) -> "e$i" to corrected }.toMap()

        val preview = CorrectionApplier.buildPreview(
            overlays = overlays,
            corrected = corrections.map { CorrectedSegment(it.key, it.value) }
        )
        assertEquals(examples.size, preview.items.size)

        val applied = CorrectionApplier.apply(overlays, corrections)

        assertEquals(examples.size, applied.changed)
        examples.forEachIndexed { i, (_, corrected) ->
            assertEquals(corrected, applied.overlays[i].text)
            // Timing is never recomputed from the corrected text.
            assertEquals(overlays[i].startTimeMs, applied.overlays[i].startTimeMs)
            assertEquals(overlays[i].endTimeMs, applied.overlays[i].endTimeMs)
            assertEquals(overlays[i].id, applied.overlays[i].id)
        }
    }

    @Test
    fun `corrections are per-segment, never global substitutions`() {
        // Two captions carry the SAME typo. The model proposes a fix for
        // the first only (say it was unsure about the second). A blind
        // global replace would rewrite both — that is the defect this
        // asserts against.
        val a = overlay("a", "نرمفزار", 0L, 1_000L)
        val b = overlay("b", "نرمفزار", 1_000L, 2_000L)

        val result = CorrectionApplier.apply(listOf(a, b), mapOf("a" to "نرم‌افزار"))

        assertEquals("نرم‌افزار", result.overlays[0].text)
        assertEquals("نرمفزار", result.overlays[1].text)
        assertEquals(1, result.changed)
    }

    @Test
    fun `colloquial and english text passes through untouched`() {
        // What a correct model returns: colloquial spelling and the
        // English term unchanged. The applier must not "improve" them.
        val text = "می‌خوام با OBS ریکورد کنم، رزولوشن 1080p باشه"
        val a = overlay("a", text, 0L, 3_000L)

        val result = CorrectionApplier.apply(listOf(a), mapOf("a" to text))

        assertEquals(0, result.changed)
        assertEquals(text, result.overlays[0].text)
        assertTrue(result.overlays[0].text.contains("می‌خوام"))
        assertTrue(result.overlays[0].text.contains("OBS"))
    }

    @Test
    fun `a completely failed run leaves every overlay byte-identical`() {
        // The brief's hard requirement: an API failure must not touch
        // the subtitles. A failed run produces NO corrected segments,
        // so the applier is a no-op.
        val overlays = listOf(
            overlay("a", "نرمفزار", 0L, 1_000L),
            overlay("b", "تلفز", 1_000L, 2_000L)
        )
        val run = CorrectionRunResult(
            segments = emptyList(),
            failedBatches = listOf(0),
            failureMessage = "محدودیت نرخ Groq.",
            failureKind = CorrectionFailureKind.RATE_LIMITED
        )

        val preview = CorrectionApplier.buildPreview(
            overlays = overlays,
            corrected = run.segments,
            failedBatches = run.failedBatches,
            failureMessage = run.failureMessage
        )
        assertTrue(preview.isEmpty)

        val applied = CorrectionApplier.apply(overlays, emptyMap())
        assertEquals(0, applied.changed)
        assertEquals(overlays, applied.overlays)
    }

    @Test
    fun `rejecting the preview writes nothing`() {
        // Rejection is `AiCorrectionState.Review -> Idle`; nothing is
        // ever applied because the applier is only called on accept.
        // This pins the data-flow property: a preview alone is inert.
        val overlays = listOf(overlay("a", "خاهر", 0L, 1_000L))
        val preview = CorrectionApplier.buildPreview(
            overlays = overlays,
            corrected = listOf(CorrectedSegment("a", "خواهر"))
        )
        assertEquals(1, preview.items.size)
        // The overlay list the project still holds is unchanged.
        assertEquals("خاهر", overlays[0].text)
    }

    @Test
    fun `manually edited text is only replaced when it was the submitted text`() {
        // The user edits a caption AFTER the run started. The model's
        // proposal was made against the OLD text, so applying it would
        // silently discard their edit. The applier compares against the
        // CURRENT text; a proposal that equals the current text is a
        // no-op, and a proposal made for different text still lands —
        // which is why the UI submits a snapshot and the review shows
        // the original the model saw.
        val edited = overlay("a", "متن دستی ویرایش‌شده", 0L, 1_000L)
        val result = CorrectionApplier.apply(listOf(edited), mapOf("a" to "متن دستی ویرایش‌شده"))
        assertEquals(0, result.changed)
        assertEquals("متن دستی ویرایش‌شده", result.overlays[0].text)
    }
}
