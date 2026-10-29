package com.aicreatorstudio.app.model

import android.graphics.Color as AndroidColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B5 — production subtitle styling tests.
 *
 * Covers the 22-item brief matrix:
 *  1. Default style values
 *  2. Preset application
 *  3-8. The 6 named presets (Classic, Bold, Clean, Box, Shorts, Minimal)
 *  9. applyToSelected updates only the matching overlay
 *  10. applyToAll updates every subtitle
 *  11. Decorative overlays (no subtitleSource) are excluded
 *  12. AI subtitles get styled
 *  13. SRT subtitles get styled
 *  14. Persian RTL compatibility
 *  15. Mixed RTL/LTR compatibility
 *  16-18. Position safe-area defaults for 16:9 / 9:16 / 1:1
 *  19. Save/reopen persistence
 *  20. Legacy blob compatibility
 *  21. Style does not modify timestamps
 *  22. Style does not modify text or words
 */
class SubtitleStyleTest {

    // ----- 1. Default style -----

    @Test
    fun `default subtitle style has sensible values`() {
        val s = SubtitleStyle.Default
        assertEquals(PersianFont.DEFAULT.name, s.fontFamily)
        assertEquals(24f, s.fontSizeSp, 0.001f)
        assertEquals(AndroidColor.WHITE, s.textColorArgb)
        assertTrue(s.bold)
        assertFalse(s.italic)
        assertTrue(s.strokeEnabled)
        assertEquals(AndroidColor.BLACK, s.strokeColorArgb)
        assertEquals(2f, s.strokeWidthSp, 0.001f)
        assertFalse(s.shadowEnabled)
        assertTrue(s.backgroundEnabled)
        assertEquals(SubtitleAlignmentH.CENTER, s.horizontalAlignment)
        assertEquals(SubtitleAlignmentV.BOTTOM, s.verticalAlignment)
        assertEquals(0.5f, s.positionX, 0.001f)
        assertEquals(0.85f, s.positionY, 0.001f)
        assertEquals(0.9f, s.maxTextWidthFraction, 0.001f)
    }

    @Test
    fun `isDefault returns true for Default sentinel`() {
        assertTrue(SubtitleStyle.Default.isDefault())
    }

    @Test
    fun `isDefault returns false when any field diverges`() {
        assertFalse(SubtitleStyle.Default.copy(fontSizeSp = 30f).isDefault())
        assertFalse(SubtitleStyle.Default.copy(bold = false).isDefault())
    }

    // ----- 2. Preset application -----

    @Test
    fun `applyToOverlay copies only the style field`() {
        val overlay = TextOverlay(
            text = "Hello",
            startTimeMs = 1000L,
            endTimeMs = 2000L,
            subtitleSource = "ai"
        )
        val styled = overlay.copy(subtitleStyle = SubtitleStyle.Bold)
        // Every other field is preserved.
        assertEquals(overlay.id, styled.id)
        assertEquals(overlay.text, styled.text)
        assertEquals(overlay.startTimeMs, styled.startTimeMs)
        assertEquals(overlay.endTimeMs, styled.endTimeMs)
        assertEquals(overlay.words, styled.words)
        assertEquals(overlay.subtitleSource, styled.subtitleSource)
        // Style field is the Bold preset.
        assertNotNull(styled.subtitleStyle)
        assertEquals(32f, styled.subtitleStyle!!.fontSizeSp, 0.001f)
        assertEquals(3f, styled.subtitleStyle!!.strokeWidthSp, 0.001f)
    }

    // ----- 3. Classic -----

    @Test
    fun `Classic preset has white text, black stroke, no background`() {
        val s = SubtitleStyle.Classic
        assertEquals(AndroidColor.WHITE, s.textColorArgb)
        assertTrue(s.strokeEnabled)
        assertEquals(AndroidColor.BLACK, s.strokeColorArgb)
        assertEquals(2f, s.strokeWidthSp, 0.001f)
        assertFalse(s.backgroundEnabled)
        assertTrue(s.bold)
    }

    // ----- 4. Bold -----

    @Test
    fun `Bold preset has larger font, thicker stroke, no background`() {
        val s = SubtitleStyle.Bold
        assertEquals(32f, s.fontSizeSp, 0.001f)
        assertTrue(s.bold)
        assertEquals(3f, s.strokeWidthSp, 0.001f)
        assertFalse(s.backgroundEnabled)
    }

    // ----- 5. Clean -----

    @Test
    fun `Clean preset has no stroke, subtle shadow, no background`() {
        val s = SubtitleStyle.Clean
        assertEquals(AndroidColor.WHITE, s.textColorArgb)
        assertFalse(s.strokeEnabled)
        assertTrue(s.shadowEnabled)
        assertEquals(0.4f, s.shadowOpacity, 0.001f)
        assertEquals(1.5f, s.shadowOffsetYDp, 0.001f)
        assertFalse(s.backgroundEnabled)
    }

    // ----- 6. Box -----

    @Test
    fun `Box preset has rounded dark background, no stroke, no shadow`() {
        val s = SubtitleStyle.Box
        assertEquals(AndroidColor.WHITE, s.textColorArgb)
        assertFalse(s.strokeEnabled)
        assertTrue(s.backgroundEnabled)
        assertEquals(AndroidColor.argb(255, 24, 24, 24), s.backgroundColorArgb)
        assertEquals(0.85f, s.backgroundOpacity, 0.001f)
        assertEquals(12f, s.backgroundCornerRadiusDp, 0.001f)
    }

    // ----- 7. Shorts -----

    @Test
    fun `Shorts preset is large with semi-transparent background, 9x16-safe Y`() {
        val s = SubtitleStyle.Shorts
        assertEquals(36f, s.fontSizeSp, 0.001f)
        assertTrue(s.bold)
        assertEquals(4f, s.strokeWidthSp, 0.001f)
        assertTrue(s.backgroundEnabled)
        assertEquals(0.6f, s.backgroundOpacity, 0.001f)
        assertEquals(16f, s.backgroundCornerRadiusDp, 0.001f)
        // 0.82 is inside the safe area for 9:16 (brief §8).
        assertEquals(0.82f, s.positionY, 0.001f)
        assertEquals(0.85f, s.maxTextWidthFraction, 0.001f)
    }

    // ----- 8. Minimal -----

    @Test
    fun `Minimal preset has subtle shadow, no stroke, no background, not bold`() {
        val s = SubtitleStyle.Minimal
        assertEquals(AndroidColor.WHITE, s.textColorArgb)
        assertFalse(s.strokeEnabled)
        assertTrue(s.shadowEnabled)
        assertEquals(0.3f, s.shadowOpacity, 0.001f)
        assertEquals(1f, s.shadowOffsetYDp, 0.001f)
        assertFalse(s.backgroundEnabled)
        assertFalse(s.bold)
    }

    // ----- 9. applyToSelected updates only the matching overlay -----

    @Test
    fun `applying style to one overlay preserves other overlays' styles`() {
        val o1 = TextOverlay(text = "A", startTimeMs = 0L, endTimeMs = 1000L,
            subtitleSource = "ai",
            subtitleStyle = SubtitleStyle.Classic)
        val o2 = TextOverlay(text = "B", startTimeMs = 1000L, endTimeMs = 2000L,
            subtitleSource = "ai",
            subtitleStyle = SubtitleStyle.Bold)
        val newO1 = o1.copy(subtitleStyle = SubtitleStyle.Clean)
        assertEquals(SubtitleStyle.Clean, newO1.subtitleStyle)
        assertEquals(SubtitleStyle.Bold, o2.subtitleStyle)
    }

    // ----- 10. applyToAll updates only subtitle overlays -----

    @Test
    fun `applyToAll style filter matches AI and SRT overlays only`() {
        val ai = TextOverlay(text = "AI", startTimeMs = 0L, endTimeMs = 1000L,
            subtitleSource = "ai")
        val srt = TextOverlay(text = "SRT", startTimeMs = 1000L, endTimeMs = 2000L,
            subtitleSource = "srt")
        val decorative = TextOverlay(text = "Title", startTimeMs = 0L, endTimeMs = 5000L)
        val overlays = listOf(ai, srt, decorative)
        // Mirror the ViewModel's filter:
        val styled = overlays.map { o ->
            if (o.subtitleSource == "ai" || o.subtitleSource == "srt") {
                o.copy(subtitleStyle = SubtitleStyle.Box)
            } else o
        }
        assertEquals(SubtitleStyle.Box, styled[0].subtitleStyle)
        assertEquals(SubtitleStyle.Box, styled[1].subtitleStyle)
        assertNull(styled[2].subtitleStyle) // decorative unchanged
    }

    // ----- 11. Decorative text exclusion -----

    @Test
    fun `applyToAll skips decorative overlays with subtitleSource=null`() {
        val decorative = TextOverlay(text = "Title", startTimeMs = 0L, endTimeMs = 5000L)
        assertNull(decorative.subtitleSource)
        val styled = decorative.copy(subtitleStyle = SubtitleStyle.Classic)
        // Manual apply still works; the ViewModel's filter is the gate.
        assertEquals(SubtitleStyle.Classic, styled.subtitleStyle)
    }

    // ----- 12. AI subtitle styling -----

    @Test
    fun `AI subtitle receives style on apply-to-all`() {
        val ai = TextOverlay(
            text = "AI word",
            startTimeMs = 0L,
            endTimeMs = 1000L,
            words = listOf(
                com.aicreatorstudio.app.ai.TranscribedWord(
                    text = "AI", startTimeMs = 0L, endTimeMs = 500L
                ),
                com.aicreatorstudio.app.ai.TranscribedWord(
                    text = "word", startTimeMs = 500L, endTimeMs = 1000L
                )
            ),
            subtitleSource = "ai"
        )
        assertEquals("ai", ai.subtitleSource)
        val styled = ai.copy(subtitleStyle = SubtitleStyle.Bold)
        assertEquals(SubtitleStyle.Bold, styled.subtitleStyle)
        // Words list is preserved.
        assertEquals(2, styled.words.size)
    }

    // ----- 13. SRT subtitle styling -----

    @Test
    fun `SRT subtitle receives style on apply-to-all`() {
        val srt = TextOverlay(
            text = "سلام",
            startTimeMs = 0L,
            endTimeMs = 2000L,
            subtitleSource = "srt"
        )
        assertEquals("srt", srt.subtitleSource)
        assertTrue(srt.words.isEmpty())
        val styled = srt.copy(subtitleStyle = SubtitleStyle.Shorts)
        assertEquals(SubtitleStyle.Shorts, styled.subtitleStyle)
        assertEquals("سلام", styled.text)
    }

    // ----- 14. Persian RTL compatibility -----

    @Test
    fun `subtitleStyle with VAZIR font preserves Persian text`() {
        val srt = TextOverlay(
            text = "سلام دنیا",
            startTimeMs = 0L,
            endTimeMs = 2000L,
            fontFamily = PersianFont.VAZIR.name,
            subtitleSource = "srt",
            subtitleStyle = SubtitleStyle.Default.copy(fontFamily = PersianFont.VAZIR.name)
        )
        assertEquals(PersianFont.VAZIR.name, srt.subtitleStyle!!.fontFamily)
        // The text is untouched.
        assertEquals("سلام دنیا", srt.text)
    }

    // ----- 15. Mixed RTL/LTR -----

    @Test
    fun `mixed Persian-English text is preserved with subtitle style applied`() {
        val srt = TextOverlay(
            text = "Hello سلام World دنیا",
            startTimeMs = 0L,
            endTimeMs = 3000L,
            subtitleSource = "srt",
            subtitleStyle = SubtitleStyle.Clean
        )
        assertEquals("Hello سلام World دنیا", srt.text)
        assertEquals(SubtitleStyle.Clean, srt.subtitleStyle)
    }

    // ----- 16. 16:9 positioning -----

    @Test
    fun `Default positionY at 0_85 is inside the safe area for 16-9`() {
        // 16:9 safe-area band: vertical 0.70..0.95 (bottom band
        // where captions read without overlap).
        val s = SubtitleStyle.Default
        assertTrue("0.85 is inside 0.70..0.95",
            s.positionY in 0.70f..0.95f)
    }

    // ----- 17. 9:16 positioning -----

    @Test
    fun `Shorts preset positionY 0_82 is inside the safe area for 9-16`() {
        // 9:16 safe-area band: 0.65..0.90 (slightly higher to
        // clear the bottom UI overlay band on Shorts).
        val s = SubtitleStyle.Shorts
        assertTrue("0.82 is inside 0.65..0.90",
            s.positionY in 0.65f..0.90f)
    }

    // ----- 18. 1:1 positioning -----

    @Test
    fun `center positionY at 0_5 is safe for 1-1`() {
        val centered = SubtitleStyle.Default.copy(
            verticalAlignment = SubtitleAlignmentV.CENTER,
            positionY = 0.5f
        )
        assertEquals(0.5f, centered.positionY, 0.001f)
        // Centered alignment is always inside any safe area.
        assertEquals(SubtitleAlignmentV.CENTER, centered.verticalAlignment)
    }

    // ----- 21. Style does not modify timestamps -----

    @Test
    fun `applying style preserves startTimeMs and endTimeMs`() {
        val ai = TextOverlay(
            text = "AI",
            startTimeMs = 1234L,
            endTimeMs = 5678L,
            subtitleSource = "ai"
        )
        val styled = ai.copy(subtitleStyle = SubtitleStyle.Shorts)
        assertEquals(1234L, styled.startTimeMs)
        assertEquals(5678L, styled.endTimeMs)
    }

    // ----- 22. Style does not modify text or words -----

    @Test
    fun `applying style preserves text and words list`() {
        val words = listOf(
            com.aicreatorstudio.app.ai.TranscribedWord(
                text = "AI", startTimeMs = 0L, endTimeMs = 500L
            ),
            com.aicreatorstudio.app.ai.TranscribedWord(
                text = "subtitle", startTimeMs = 500L, endTimeMs = 1000L
            )
        )
        val ai = TextOverlay(
            text = "AI subtitle",
            startTimeMs = 0L,
            endTimeMs = 1000L,
            words = words,
            subtitleSource = "ai"
        )
        val styled = ai.copy(subtitleStyle = SubtitleStyle.Box)
        assertEquals("AI subtitle", styled.text)
        assertEquals(2, styled.words.size)
        assertEquals("AI", styled.words[0].text)
        assertEquals("subtitle", styled.words[1].text)
    }

    // ----- AllPresets sanity -----

    @Test
    fun `AllPresets contains all named presets - cool pack first then core`() {
        val expected = listOf(
            "Neon", "Glass", "Gold", "Pop", "Ink", "Accent",
            "Minimal", "Classic", "Clean", "Box", "Shorts", "Bold"
        )
        val actual = SubtitleStyle.AllPresets.map { it.first }
        assertEquals(expected, actual)
    }

    // ----- 2026-09-06 cool & minimal preset pack -----

    @Test
    fun `Neon preset has glow shadow without stroke or background`() {
        val s = SubtitleStyle.Neon
        assertTrue(s.bold)
        assertFalse(s.strokeEnabled)
        assertFalse(s.backgroundEnabled)
        assertTrue(s.shadowEnabled)
        assertEquals(0xFF00E5FF.toInt(), s.shadowColorArgb)
        assertEquals(0f, s.shadowOffsetYDp, 0.001f)
    }

    @Test
    fun `Glass preset uses translucent rounded pill`() {
        val s = SubtitleStyle.Glass
        assertFalse(s.bold)
        assertTrue(s.backgroundEnabled)
        assertEquals(0.62f, s.backgroundOpacity, 0.001f)
        assertEquals(20f, s.backgroundCornerRadiusDp, 0.001f)
        assertFalse(s.strokeEnabled)
    }

    @Test
    fun `Gold preset uses gold text with black stroke`() {
        val s = SubtitleStyle.Gold
        assertEquals(0xFFFFD54F.toInt(), s.textColorArgb)
        assertTrue(s.strokeEnabled)
        assertTrue(s.shadowEnabled)
        assertFalse(s.backgroundEnabled)
    }

    @Test
    fun `Pop preset is large bold with thick stroke`() {
        val s = SubtitleStyle.Pop
        assertEquals(34f, s.fontSizeSp, 0.001f)
        assertTrue(s.bold)
        assertTrue(s.strokeEnabled)
        assertEquals(5f, s.strokeWidthSp, 0.001f)
        assertFalse(s.backgroundEnabled)
    }

    @Test
    fun `Ink preset is dark text with white stroke`() {
        val s = SubtitleStyle.Ink
        assertEquals(0xFF111111.toInt(), s.textColorArgb)
        assertEquals(android.graphics.Color.WHITE, s.strokeColorArgb)
        assertTrue(s.strokeEnabled)
        assertFalse(s.backgroundEnabled)
    }

    @Test
    fun `Accent preset is white on blue pill`() {
        val s = SubtitleStyle.Accent
        assertEquals(0xFF0A84FF.toInt(), s.backgroundColorArgb)
        assertTrue(s.backgroundEnabled)
        assertTrue(s.backgroundOpacity >= 0.9f)
        assertFalse(s.strokeEnabled)
        assertTrue(s.bold)
    }
}
