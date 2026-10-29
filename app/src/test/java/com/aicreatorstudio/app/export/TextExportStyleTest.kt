package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.TextOverlayAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE A (Persistence Integrity) — the export style bridge for plain
 * (decorative) text overlays.
 *
 * The old export path skipped every overlay whose `subtitleSource` was
 * null, so a user's Add-Text / paragraph overlay previewed on screen and
 * then silently vanished from the MP4. The gate is now "has blank
 * text", and the burn resolves the style through [TextOverlay.exportStyle].
 *
 * The bridge exists because decorative overlays keep their look on the
 * overlay itself (colour / size / font / position) rather than in a
 * `subtitleStyle`. Falling back to `SubtitleStyle.Default` would burn
 * white 24sp bottom-centred text — not what the user set.
 */
class TextExportStyleTest {

    private fun overlay(
        text: String = "My Title",
        style: com.aicreatorstudio.app.model.SubtitleStyle? = null,
        sizeSp: Float = 20f,
        backgroundKind: CaptionBackgroundKind = CaptionBackgroundKind.NONE,
        subtitleSource: String? = null
    ) = TextOverlay(
        text = text,
        startTimeMs = 0L,
        endTimeMs = 2_000L,
        subtitleStyle = style,
        fontSizeSp = sizeSp,
        backgroundKind = backgroundKind,
        subtitleSource = subtitleSource
    )

    @Test
    fun `a plain overlay keeps its own size colour and position`() {
        val base = overlay(sizeSp = 20f).copy(
            position = androidx.compose.ui.geometry.Offset(0.2f, 0.1f)
        )
        val s = base.exportStyle()
        assertEquals(20f, s.fontSizeSp, 0.001f)
        assertEquals(0.2f, s.positionX, 0.001f)
        assertEquals(0.1f, s.positionY, 0.001f)
        // The overlay's own white text is preserved, not Default's.
        assertEquals(0xFFFFFFFF.toInt(), s.textColorArgb)
    }

    @Test
    fun `a real subtitle style always wins over the overlay fields`() {
        val style = com.aicreatorstudio.app.model.SubtitleStyle(fontSizeSp = 44f)
        val s = overlay(sizeSp = 20f, style = style).exportStyle()
        assertEquals(44f, s.fontSizeSp, 0.001f)
    }

    @Test
    fun `a plain overlay burns without a pill it did not ask for`() {
        val s = overlay(backgroundKind = CaptionBackgroundKind.NONE).exportStyle()
        assertFalse(s.backgroundEnabled)
        assertFalse(s.strokeEnabled)
        assertFalse(s.shadowEnabled)
    }

    @Test
    fun `a solid box overlay requests a background`() {
        val s = overlay(backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED).exportStyle()
        assertTrue(s.backgroundEnabled)
        assertEquals(12f, s.backgroundCornerRadiusDp, 0.001f)
    }

    @Test
    fun `a blank overlay is not exportable at all`() {
        val overlays = listOf(
            overlay("Live"),
            overlay("   "),
            overlay("")
        )
        assertEquals(1, overlays.count { it.text.isNotBlank() })
    }

    @Test
    fun `an overlay with animation still resolves a style`() {
        val styled = overlay().copy(
            textAnimation = TextOverlayAnimation.FADE_IN,
            textAnimationOut = TextOverlayAnimation.FADE_OUT
        )
        assertTrue(styled.textAnimation != TextOverlayAnimation.NONE)
        assertEquals(20f, styled.exportStyle().fontSizeSp, 0.001f)
    }

    // ------------------------------------------------------------------
    // 2026-10-16 QA PARITY FIX — subtitle kind vs decorative kind
    //
    // AI transcriptions and SRT imports are created with
    // `subtitleStyle == null`, but the canvas preview draws them with
    // `subtitleStyle ?: SubtitleStyle.Default`. The burn used to apply
    // the DECORATIVE derivation instead, so a freshly imported caption
    // previewed as a bold, stroked, bottom-anchored black pill and
    // exported as plain non-bold centred text. These tests pin the two
    // branches so the two renderers cannot drift apart again.
    // ------------------------------------------------------------------

    @Test
    fun `a subtitle overlay with no style burns with the preview's Default`() {
        listOf("ai", "srt").forEach { source ->
            val s = overlay(subtitleSource = source).exportStyle()
            assertEquals(
                "subtitleSource=$source burned a different style than the preview draws",
                com.aicreatorstudio.app.model.SubtitleStyle.Default,
                s
            )
        }
    }

    @Test
    fun `a decorative overlay still derives its own style instead of Default`() {
        val s = overlay(
            sizeSp = 20f,
            backgroundKind = CaptionBackgroundKind.NONE
        ).exportStyle()
        assertEquals(20f, s.fontSizeSp, 0.001f)
        assertFalse("a decorative overlay must not pick up the caption pill", s.backgroundEnabled)
        assertFalse(s.strokeEnabled)
        assertTrue(
            "a decorative overlay must not silently become SubtitleStyle.Default",
            s != com.aicreatorstudio.app.model.SubtitleStyle.Default
        )
    }

    @Test
    fun `a real style still wins for a subtitle overlay`() {
        val style = com.aicreatorstudio.app.model.SubtitleStyle(fontSizeSp = 44f)
        val s = overlay(style = style, subtitleSource = "srt").exportStyle()
        assertEquals(style, s)
    }

    @Test
    fun `a re-imported subtitle keeps Default after a project round trip`() {
        // The parity branch must survive persistence: a subtitle overlay
        // with no style must still resolve to Default after the JSON
        // codec has dropped the field.
        val stored = listOf(overlay(subtitleSource = "srt"))
        val decoded = com.aicreatorstudio.app.db.EditorStateJson.textOverlaysFromJson(
            com.aicreatorstudio.app.db.EditorStateJson.textOverlaysToJson(stored)
        )
        assertEquals(1, decoded.size)
        assertEquals("srt", decoded[0].subtitleSource)
        assertEquals(
            com.aicreatorstudio.app.model.SubtitleStyle.Default,
            decoded[0].exportStyle()
        )
    }
}
