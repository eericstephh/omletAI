package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.TextOverlayAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-14 — pins the VIRAL motion engine: per-word visual states
 * (bounce / typewriter / slide / fade / shake / karaoke fill /
 * rise) and the whole-caption entrance/exit transform. All pure
 * math — JVM only, no Android.
 */
class WordMotionEngineTest {

    private fun overlay(
        kind: WordMotionKind,
        words: List<TranscribedWord>,
        durationMs: Long = 3000L,
        animationDurationMs: Int = 200
    ) = TextOverlay(
        text = words.joinToString(" ") { it.text },
        startTimeMs = 10_000L,
        endTimeMs = 10_000L + durationMs,
        words = words,
        motionKind = kind,
        activeWordScale = 1.3f,
        animationDurationMs = animationDurationMs,
        textAnimation = TextOverlayAnimation.FADE_IN
    )

    /** Words in TIMELINE space (absolute), aligned to the overlay's
     *  10_000..13_000 window: word i spans 10_000+i*1000. */
    private fun words(vararg texts: String): List<TranscribedWord> {
        return texts.mapIndexed { i, t ->
            TranscribedWord(
                text = t,
                startTimeMs = 10_000L + i * 1000L,
                endTimeMs = 10_000L + (i + 1) * 1000L
            )
        }
    }

    /** Words with EXPLICIT raw timings (timeline space). */
    private fun rawWords(vararg windows: Pair<String, LongRange>): List<TranscribedWord> =
        windows.map { (t, w) ->
            TranscribedWord(text = t, startTimeMs = w.first, endTimeMs = w.last)
        }

    // ----------------------------------------------------------------
    // BOUNCE_POP
    // ----------------------------------------------------------------

    @Test
    fun `bounce pop - word before its start is hidden`() {
        val o = overlay(
            WordMotionKind.BOUNCE_POP,
            words("سلام", "دوستان", "امروز")
        )
        val state = WordMotionEngine.wordStateAt(o, o.words, 1, 10_500L)
        // Word 1 starts at 11_000; before that it is hidden.
        assertEquals(0f, state.alpha, 1e-6f)
    }

    @Test
    fun `bounce pop - active word scales beyond rest`() {
        val o = overlay(
            WordMotionKind.BOUNCE_POP,
            words("سلام", "دوستان", "امروز")
        )
        // 50ms into word 1's window (starts 11_000, effect 200ms).
        val state = WordMotionEngine.wordStateAt(o, o.words, 1, 11_050L)
        assertTrue("active bounce scale > 1", state.scale > 1f)
        assertTrue("bounce lifts the word", state.offsetYFraction < 0f)
    }

    @Test
    fun `bounce pop - finished word returns to rest`() {
        val o = overlay(
            WordMotionKind.BOUNCE_POP,
            words("سلام", "دوستان", "امروز")
        )
        // Word 0's window is 10_000..11_000; at 11_500 it is done.
        val state = WordMotionEngine.wordStateAt(o, o.words, 0, 11_500L)
        assertEquals(1f, state.scale, 1e-6f)
        assertEquals(0f, state.offsetYFraction, 1e-6f)
    }

    // ----------------------------------------------------------------
    // TYPEWRITER
    // ----------------------------------------------------------------

    @Test
    fun `typewriter - characters reveal across the word window`() {
        val o = overlay(
            WordMotionKind.TYPEWRITER,
            rawWords("امروز" to (10_000L..10_999L), "دوستان" to (11_000L..11_999L))
        )
        // Word 0 ("امروز" = 5 chars) at half its window → ~2-3 chars.
        val half = WordMotionEngine.wordStateAt(o, o.words, 0, 10_500L)
        assertTrue(half.visibleCharacters in 2..3)
        assertTrue(half.alpha > 0f)
        // At the word's end → all 5.
        val done = WordMotionEngine.wordStateAt(o, o.words, 0, 10_999L)
        assertEquals(5, done.visibleCharacters)
    }

    @Test
    fun `typewriter - future word is fully hidden`() {
        val o = overlay(
            WordMotionKind.TYPEWRITER,
            words("سلام", "دنیا")
        )
        val state = WordMotionEngine.wordStateAt(o, o.words, 1, 10_200L)
        assertEquals(0, state.visibleCharacters)
        assertEquals(0f, state.alpha, 1e-6f)
    }

    // ----------------------------------------------------------------
    // KARAOKE_FILL
    // ----------------------------------------------------------------

    @Test
    fun `karaoke fill - fill fraction grows through the word window`() {
        val o = overlay(
            WordMotionKind.KARAOKE_FILL,
            words("سلام", "دوستان")
        )
        val quarter = WordMotionEngine.wordStateAt(o, o.words, 0, 10_250L)
        val full = WordMotionEngine.wordStateAt(o, o.words, 0, 11_000L)
        assertTrue(quarter.fillFraction in 0.05f..0.5f)
        assertEquals(1f, full.fillFraction, 1e-6f)
    }

    // ----------------------------------------------------------------
    // SLIDE_UP_WORD / FADE_SCALE
    // ----------------------------------------------------------------

    @Test
    fun `slide up - word starts below and settles`() {
        val o = overlay(
            WordMotionKind.SLIDE_UP_WORD,
            words("بالا", "بالا")
        )
        val early = WordMotionEngine.wordStateAt(o, o.words, 0, 10_010L)
        val late = WordMotionEngine.wordStateAt(o, o.words, 0, 10_500L)
        assertTrue(early.offsetYFraction > late.offsetYFraction)
        assertTrue(late.alpha > early.alpha)
    }

    @Test
    fun `fade scale - alpha ramps and scale grows`() {
        val o = overlay(
            WordMotionKind.FADE_SCALE,
            words("پدیدن", "است")
        )
        val state = WordMotionEngine.wordStateAt(o, o.words, 0, 10_100L)
        assertTrue(state.alpha in 0.01f..1f)
        assertTrue(state.scale > 0.7f)
    }

    // ----------------------------------------------------------------
    // SHAKE_WIGGLE
    // ----------------------------------------------------------------

    @Test
    fun `shake - active word wiggles horizontally`() {
        val o = overlay(
            WordMotionKind.SHAKE_WIGGLE,
            words("لرزش", "است")
        )
        val state = WordMotionEngine.wordStateAt(o, o.words, 0, 10_100L)
        // The sine wiggle may be at zero crossing — sample several
        // times; at least one must offset.
        val offsets = (0 until 10).map { i ->
            WordMotionEngine.wordStateAt(o, o.words, 0, 10_100L + i * 20L).offsetXFraction
        }
        assertTrue("wiggle must move somewhere", offsets.any { it != 0f })
        // Inactive word is at rest.
        val rest = WordMotionEngine.wordStateAt(o, o.words, 1, 10_100L)
        assertEquals(0f, rest.offsetXFraction, 1e-6f)
    }

    // ----------------------------------------------------------------
    // NONE + whole-caption transform
    // ----------------------------------------------------------------

    @Test
    fun `none motion leaves every word at rest`() {
        val o = overlay(
            WordMotionKind.NONE,
            words("ساده", "متن")
        )
        val state = WordMotionEngine.wordStateAt(o, o.words, 0, 10_500L)
        assertEquals(1f, state.alpha, 1e-6f)
        assertEquals(1f, state.scale, 1e-6f)
        assertEquals(0f, state.fillFraction, 1e-6f)
    }

    @Test
    fun `caption fade-in ramps alpha over the effect window`() {
        val o = overlay(WordMotionKind.NONE, words("تست"))
        val early = WordMotionEngine.captionTransformAt(o, 10_010L)
        val settled = WordMotionEngine.captionTransformAt(o, 10_900L)
        assertTrue(early.alpha < 1f)
        assertEquals(1f, settled.alpha, 1e-6f)
    }

    @Test
    fun `caption fade-out fades near the end`() {
        val o = overlay(WordMotionKind.NONE, words("تست")).copy(
            textAnimationOut = TextOverlayAnimation.FADE_OUT
        )
        val nearEnd = WordMotionEngine.captionTransformAt(o, 12_990L)
        assertTrue(nearEnd.alpha < 1f)
    }

    // ----------------------------------------------------------------
    // RTL probe
    // ----------------------------------------------------------------

    @Test
    fun `rtl probe detects persian but not latin`() {
        assertTrue(WordMotionEngine.isRtlWord("سلام"))
        assertFalse(WordMotionEngine.isRtlWord("hello"))
    }
}
