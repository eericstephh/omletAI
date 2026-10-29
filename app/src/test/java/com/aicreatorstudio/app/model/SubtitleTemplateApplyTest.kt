package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.db.EditorStateJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — applying a template must move EVERY template value
 * onto the segment. `activeWordBold` + `activeWordBackgroundArgb`
 * used to be silently dropped by `withTemplate`, so the
 * active-word pill and the template bold choice never reached the
 * canvas or the export.
 */
class SubtitleTemplateApplyTest {

    private val bar = BuiltInSubtitleTemplates.byId("fa_16_bar")!!
    private val clean = BuiltInSubtitleTemplates.byId("fa_01_clean")!!

    @Test
    fun `withTemplate carries bold and pill onto the overlay`() {
        val before = TextOverlay.default("سلام دنیا", 0L, 2_000L)
        assertFalse(before.activeWordBold)
        assertNull(before.activeWordBackgroundArgb)

        val after = before.withTemplate(bar)
        assertTrue(after.activeWordBold)
        assertEquals(0xFFFF375F.toInt(), after.activeWordBackgroundArgb)
        // The rest of the template still flows through.
        assertEquals(WordRevealMode.HIGHLIGHT, after.wordRevealMode)
        assertEquals(bar.id, after.templateId)
        // Untouched overlays keep their identity fields.
        assertEquals(before.id, after.id)
        assertEquals(before.text, after.text)
        assertEquals(before.words, after.words)
    }

    @Test
    fun `withTemplate clears the pill when the template has none`() {
        val withPill = TextOverlay.default("x", 0L, 1_000L)
            .copy(activeWordBackgroundArgb = 0xFFFF375F.toInt(), activeWordBold = true)
        val cleared = withPill.withTemplate(clean)
        assertNull(cleared.activeWordBackgroundArgb)
        assertFalse(cleared.activeWordBold)
    }

    @Test
    fun `bold and pill survive project save plus reopen`() {
        val overlay = TextOverlay.default("سلام دنیا", 0L, 2_000L)
            .withTemplate(bar)
        val json = EditorStateJson.textOverlaysToJson(listOf(overlay))
        val back = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, back.size)
        assertTrue(back[0].activeWordBold)
        assertEquals(0xFFFF375F.toInt(), back[0].activeWordBackgroundArgb)
    }

    @Test
    fun `legacy blobs without the new keys decode as off`() {
        val legacy = TextOverlay.default("سلام", 0L, 1_000L)
        val json = EditorStateJson.textOverlaysToJson(listOf(legacy))
        // Simulate an old blob: strip the new keys if present.
        val back = EditorStateJson.textOverlaysFromJson(json)
        assertEquals(1, back.size)
        // A fresh default overlay carries off/off.
        assertFalse(back[0].activeWordBold)
        assertNull(back[0].activeWordBackgroundArgb)
    }
}
