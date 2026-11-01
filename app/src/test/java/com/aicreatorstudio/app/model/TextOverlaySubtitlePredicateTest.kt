package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.ai.TranscribedWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [isSubtitle] — the single definition of "this TextOverlay is a
 * subtitle, not decorative text".
 *
 * ## Why this test exists
 *
 * "Export SRT" silently produced nothing while the Subs lane still
 * showed the captions, because the export had its own hand-written
 * test (`subtitleSource in {"ai","srt"}` OR an in-memory id set) that
 * was narrower than the one the timeline used. A caption carrying
 * word timings but no `subtitleSource` tag matched the lane and not
 * the export.
 *
 * Every consumer — the Subs lane, its hit-testing, the global
 * subtitle-style tools, the card's Export gate and
 * `EditorViewModel.onExportSrtClicked` — now ORs this predicate with
 * its own in-memory id sets, so the four can no longer disagree about
 * what is exportable.
 */
class TextOverlaySubtitlePredicateTest {

    private fun word(text: String = "سلام", start: Long = 0L, end: Long = 500L) =
        TranscribedWord(text = text, startTimeMs = start, endTimeMs = end)

    @Test
    fun `AI caption with word timings and no tag is a subtitle`() {
        // The regression case: a project authored before
        // `subtitleSource` existed. The words are the only evidence.
        val overlay = TextOverlay(
            text = "سلام دنیا",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            words = listOf(word())
        )
        assertTrue(overlay.subtitleSource == null)
        assertTrue("words-bearing caption must count as a subtitle", overlay.isSubtitle)
    }

    @Test
    fun `SRT import with the srt tag and no words is a subtitle`() {
        val overlay = TextOverlay(
            text = "زیرنویس وارد شده",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            subtitleSource = "srt"
        )
        assertTrue(overlay.words.isEmpty())
        assertTrue(overlay.isSubtitle)
    }

    @Test
    fun `tagged ai caption without words is a subtitle`() {
        val overlay = TextOverlay(
            text = "متن",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            subtitleSource = "ai"
        )
        assertTrue(overlay.isSubtitle)
    }

    @Test
    fun `decorative title is not a subtitle`() {
        // The case that used to leave "Export SRT" enabled with nothing
        // to write: a title/watermark has no tag and no word timings.
        val overlay = TextOverlay(
            text = "My Channel",
            startTimeMs = 0L,
            endTimeMs = 5_000L
        )
        assertTrue(overlay.words.isEmpty())
        assertTrue(overlay.subtitleSource == null)
        assertFalse("decorative text must never be exported as SRT", overlay.isSubtitle)
    }

    @Test
    fun `empty text overlay is still classified by its tag only`() {
        // Classification is about provenance, not content: SrtFormatter
        // already skips blank text at write time, so the predicate must
        // not double-guess it here.
        val blankSubtitle = TextOverlay(
            text = "   ",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            subtitleSource = "srt"
        )
        assertTrue(blankSubtitle.isSubtitle)

        val blankDecorative = TextOverlay(
            text = "   ",
            startTimeMs = 0L,
            endTimeMs = 1_000L
        )
        assertFalse(blankDecorative.isSubtitle)
    }
}
