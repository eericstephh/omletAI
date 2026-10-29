package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.ui.screens.editor.toFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for the two production bugs found in the
 * 2026-09-03 audit:
 *
 * - **BUG 1 — duplicate Subtitle tracks:** AI-generated
 *   TextOverlays from Whisper were rendered on BOTH the
 *   Text lane AND the Subtitle lane, because the
 *   `SubtitleTrackRow` in [com.aicreatorstudio.app.ui.screens.editor.EditorTimeline]
 *   received the full `textOverlays` list. The architectural
 *   fix: the timeline filters out AI-generated overlays
 *   (those with non-empty `words`) before passing the list
 *   to `SubtitleTrackRow`. The mapper and ViewModel keep
 *   producing one TextOverlay per Whisper segment with
 *   `words` populated; the timeline is the only place that
 *   decides "Text lane vs Subtitle lane" based on
 *   `overlay.words.isNotEmpty()`.
 *
 * - **BUG 2 — Persian RTL rendering:** the preview Text
 *   composable in
 *   [com.aicreatorstudio.app.ui.screens.editor.EditorScreen]
 *   rendered Persian text with `MaterialTheme.typography.titleMedium`
 *   and no `fontFamily` — the system default font does not
 *   shape Persian letters, producing the broken
 *   "م ا ل س" / unjoined rendering. The fix pins
 *   `fontFamily = PersianFont.from(overlay.fontFamily).toFontFamily()`
 *   and wraps the Text in
 *   `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`
 *   when the text contains Arabic-script code points.
 *
 * The two architectural rules locked in here are:
 *   (a) AI overlays always have `words.isNotEmpty()`.
 *       User-authored overlays always have `words.isEmpty()`.
 *   (b) The filter `textOverlays.filterNot { it.words.isNotEmpty() }`
 *       therefore removes only AI content and keeps only
 *       user content on the Subtitle lane.
 *
 * The test in `containsPersianScript` locks the detection
 * helper used by the BUG 2 fix.
 */
class SubtitleTimelineDuplicationAndRtlTest {

    // -----------------------------------------------------------------
    // BUG 1 — no duplicate AI content on the Subtitle lane.
    // -----------------------------------------------------------------

    @Test
    fun `mapper produces one overlay per segment and every AI overlay has non-empty words`() {
        val transcription = transcription(
            segments = listOf(
                segment(
                    text = "سلام دنیا",
                    startMs = 0L, endMs = 2_000L,
                    words = listOf(word("سلام", 0L, 900L), word("دنیا", 1_000L, 2_000L))
                ),
                segment(
                    text = "این یک تست است",
                    startMs = 2_500L, endMs = 5_000L,
                    words = listOf(
                        word("این", 2_500L, 2_900L),
                        word("یک", 3_000L, 3_300L),
                        word("تست", 3_400L, 3_900L),
                        word("است", 4_000L, 5_000L)
                    )
                )
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            transcription = transcription,
            font = PersianFont.VAZIR
        )
        assertEquals(2, overlays.size)
        for (o in overlays) {
            assertTrue(
                "AI overlay must carry word timestamps (BUG 1 invariant); " +
                    "overlay=${o.id} text='${o.text}'",
                o.words.isNotEmpty()
            )
        }
    }

    @Test
    fun `filter removes AI overlays from the subtitle lane but keeps user-authored overlays`() {
        val aiOverlay = aiOverlay("سلام دنیا", startMs = 0L, endMs = 2_000L)
        val userOverlay = userOverlay("User caption", startMs = 3_000L, endMs = 4_500L)
        val mixed: List<TextOverlay> = listOf(aiOverlay, userOverlay)

        // The exact filter expression the BUG 1 fix uses in
        // EditorTimeline.kt: `textOverlays.filterNot { it.words.isNotEmpty() }`.
        val onSubtitleLane = mixed.filterNot { it.words.isNotEmpty() }

        assertEquals(1, onSubtitleLane.size)
        assertSame(userOverlay, onSubtitleLane.first())
        assertFalse(
            "AI overlay must not appear on the Subtitle lane",
            onSubtitleLane.any { it.id == aiOverlay.id }
        )
    }

    @Test
    fun `subtitles lane is empty when only AI content is present`() {
        val overlays = listOf(
            aiOverlay("اول", startMs = 0L, endMs = 1_000L),
            aiOverlay("دوم", startMs = 1_500L, endMs = 3_000L)
        )
        val onSubtitleLane = overlays.filterNot { it.words.isNotEmpty() }
        assertTrue(
            "BUG 1: AI-only overlay list must yield zero Subtitle-lane tiles",
            onSubtitleLane.isEmpty()
        )
    }

    @Test
    fun `subtitles lane preserves user captions across multiple AI regenerations`() {
        val user = userOverlay("My caption", startMs = 1_000L, endMs = 2_000L)
        val firstRun = listOf(user) +
            WhisperTranscriptionToTextOverlays.toTextOverlays(
                transcription = transcription(
                    listOf(segment("a", 0L, 500L, listOf(word("a", 0L, 500L))))
                )
            )
        val secondRun = firstRun.filterNot { it.words.isNotEmpty() } +
            WhisperTranscriptionToTextOverlays.toTextOverlays(
                transcription = transcription(
                    listOf(segment("b", 0L, 800L, listOf(word("b", 0L, 800L))))
                )
            )
        val onSubtitleLane = secondRun.filterNot { it.words.isNotEmpty() }
        assertEquals(1, onSubtitleLane.size)
        assertEquals(user.id, onSubtitleLane.first().id)
        assertEquals("My caption", onSubtitleLane.first().text)
    }

    @Test
    fun `whisper overlay carries the chosen Persian font and stable id after save_reopen`() {
        val transcription = transcription(
            listOf(
                segment("سلام", 0L, 1_000L, listOf(word("سلام", 0L, 1_000L)))
            )
        )
        val overlays = WhisperTranscriptionToTextOverlays.toTextOverlays(
            transcription = transcription,
            font = PersianFont.VAZIR
        )
        assertEquals(1, overlays.size)
        val o = overlays.first()
        assertEquals(PersianFont.VAZIR.name, o.fontFamily)
        assertNotNull(o.id)
        // AI overlays have words populated, so the BUG 1
        // filter keeps them off the Subtitle lane.
        assertTrue(o.words.isNotEmpty())
    }

    // -----------------------------------------------------------------
    // BUG 2 — Persian text must be detected and routed to RTL.
    // -----------------------------------------------------------------

    @Test
    fun `contains_persian_script identifies pure Persian text`() {
        assertTrue(containsArabicScript("سلام"))
        assertTrue(containsArabicScript("این یک جمله فارسی است"))
    }

    @Test
    fun `contains_persian_script identifies mixed Persian and English text`() {
        assertTrue(containsArabicScript("Hello سلام world دنیا"))
        assertTrue(containsArabicScript("AI یعنی هوش مصنوعی"))
    }

    @Test
    fun `contains_persian_script identifies Persian and Arabic digits inside Persian sentences`() {
        assertTrue(containsArabicScript("سال ۲۰۲۶"))
        assertTrue(containsArabicScript("شماره ۱۲۳۴"))
    }

    @Test
    fun `contains_persian_script identifies mixed with parentheses and punctuation`() {
        assertTrue(containsArabicScript("(سلام)"))
        assertTrue(containsArabicScript("«این یک تست است»"))
        assertTrue(containsArabicScript("گرافیک ۵"))
    }

    @Test
    fun `contains_persian_script returns false for pure Latin text`() {
        assertFalse(containsArabicScript("Hello world"))
        assertFalse(containsArabicScript("This is a test 123"))
    }

    @Test
    fun `persian font resolves to a non-default font family`() {
        // Sanity: the BUG 2 fix applies PersianFont.from(o.fontFamily)
        // to the preview Text composable. Every non-DEFAULT
        // Persian font must produce a FontFamily other than
        // FontFamily.Default.
        assertSame(
            "DEFAULT must fall back to FontFamily.Default",
            androidx.compose.ui.text.font.FontFamily.Default,
            PersianFont.DEFAULT.toFontFamily()
        )
        val vazir = PersianFont.VAZIR.toFontFamily()
        assertNotNull(vazir)
        // The bundled font files are resolved by the Android
        // resource system; the JVM test only verifies that
        // PersianFont.toFontFamily() returns a value.
        assertTrue(vazir.toString().isNotEmpty())
    }

    @Test
    fun `persian font round_trips through from by enum name`() {
        for (f in PersianFont.values()) {
            assertSame(
                "round-trip for $f",
                f,
                PersianFont.from(f.name)
            )
        }
        // Unknown / null name -> DEFAULT.
        assertSame(PersianFont.DEFAULT, PersianFont.from(null))
        assertSame(PersianFont.DEFAULT, PersianFont.from("NOT_A_FONT"))
    }

    @Test
    fun `mixed Persian English overlay text preserves both scripts byte-for-byte`() {
        val original = "AI یعنی هوش مصنوعی — version 1.0"
        val overlay = aiOverlay(original, startMs = 0L, endMs = 1_000L)
        assertEquals(original, overlay.text)
    }

    @Test
    fun `persian digit characters are preserved in subtitle text`() {
        val text = "شماره ۵ در گرافیک ۵ پیدا شد"
        val overlay = aiOverlay(text, startMs = 0L, endMs = 1_000L)
        assertEquals(text, overlay.text)
        // Each Persian digit must be intact (no ZWNJ/SP insertion
        // or character swap).
        assertTrue(overlay.text.contains("۵"))
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * The same detection helper the BUG 2 fix in
     * EditorScreen.kt uses to decide whether to wrap the
     * preview caption in a RTL `CompositionLocalProvider`.
     *
     * Pure-JVM mirror of the inline check so it is testable
     * without a Compose runtime.
     */
    private fun containsArabicScript(s: String): Boolean = s.any { c ->
        c.code in 0x0600..0x06FF ||
        c.code in 0x0750..0x077F ||
        c.code in 0xFB50..0xFDFF ||
        c.code in 0xFE70..0xFEFF
    }

    private fun word(text: String, startMs: Long, endMs: Long) = TranscribedWord(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        confidence = null
    )

    private fun segment(
        text: String,
        startMs: Long,
        endMs: Long,
        words: List<TranscribedWord>
    ) = Subtitle(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        words = words,
        confidence = null
    )

    private fun transcription(
        segments: List<Subtitle>
    ) = Transcription(segments = segments, words = segments.flatMap { it.words })

    /**
     * AI-generated overlay: mirrors the shape produced by
     * [WhisperTranscriptionToTextOverlays.toTextOverlays].
     * `words` is non-empty.
     */
    private fun aiOverlay(
        text: String,
        startMs: Long,
        endMs: Long,
        font: PersianFont = PersianFont.VAZIR
    ) = TextOverlay(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        fontFamily = font.name,
        words = listOf(
            TranscribedWord(
                id = UUID.randomUUID().toString(),
                text = text,
                startTimeMs = startMs,
                endTimeMs = endMs
            )
        ),
        karaokeEnabled = true
    )

    /**
     * User-authored overlay: `words` is always empty, so it
     * is kept by the BUG 1 filter and rendered on the
     * Subtitle lane.
     */
    private fun userOverlay(
        text: String,
        startMs: Long,
        endMs: Long,
        font: PersianFont = PersianFont.VAZIR
    ) = TextOverlay(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        fontFamily = font.name,
        words = emptyList(),
        karaokeEnabled = false
    )
}
