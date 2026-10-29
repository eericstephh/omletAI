package com.aicreatorstudio.app.model.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2 — Base + Overrides: editing one axis never touches the
 * other five, and every change is reset-friendly.
 */
class TemplateOverridesTest {

    private fun karaokeClassic(): JsonTemplate {
        val file = java.io.File("src/main/assets/subtitle_templates.json")
        return JsonTemplateParser.parse(file.readText()).catalog
            .templates.first { it.id == "karaoke_classic" }
    }

    @Test
    fun `empty overrides resolve to the base verbatim`() {
        val r = ResolvedTemplate.resolve(karaokeClassic(), TemplateOverrides.Empty)
        assertEquals("karaoke_classic", r.id)
        assertEquals("کارائوکه کلاسیک", r.nameFa)
        assertEquals("Vazirmatn", r.fontFa)
        assertEquals("Montserrat", r.fontEn)
        assertEquals("rtl", r.directionFa)
        assertEquals("ltr", r.directionEn)
        assertEquals("bold", r.font.weight)
        assertEquals("#FFFFFF", r.color.base)
        assertEquals("outline", r.shadow.type)
        assertEquals("colorChange", r.wordHighlight.type)
        assertEquals("#FFD700", r.wordHighlight.activeColor)
        assertEquals("none", r.motion.entry.type)
        assertEquals("none", r.background.type)
        assertTrue(TemplateOverrides.Empty.isEmpty())
        assertTrue(TemplateOverrides.Empty.axes().isEmpty())
    }

    @Test
    fun `changing only color keeps the other five axes`() {
        // The spec's core scenario: Karaoke Classic + Color = Red.
        val o = TemplateOverrides(color = ColorOverride(base = "#FF0000"))
        assertEquals(setOf(TemplateAxis.COLOR), o.axes())
        val r = ResolvedTemplate.resolve(karaokeClassic(), o)
        assertEquals("#FF0000", r.color.base)
        // Everything else falls through to the base.
        assertEquals("Vazirmatn", r.fontFa)
        assertEquals("bold", r.font.weight)
        assertEquals("outline", r.shadow.type)
        assertEquals("colorChange", r.wordHighlight.type)
        assertEquals("#FFD700", r.wordHighlight.activeColor)
        assertEquals("none", r.motion.entry.type)
        assertEquals("none", r.background.type)
    }

    @Test
    fun `font family override replaces both script maps`() {
        val o = TemplateOverrides(font = FontOverride(family = "Lalezar"))
        val r = ResolvedTemplate.resolve(karaokeClassic(), o)
        assertEquals("Lalezar", r.fontFa)
        assertEquals("Lalezar", r.fontEn)
        // Weight still comes from the base.
        assertEquals("bold", r.font.weight)
    }

    @Test
    fun `cleared drops one axis and clearedAll resets everything`() {
        val o = TemplateOverrides(
            color = ColorOverride(base = "#FF0000"),
            font = FontOverride(weight = "black")
        )
        assertEquals(
            setOf(TemplateAxis.COLOR, TemplateAxis.FONT),
            o.axes()
        )
        val backToFont = o.cleared(TemplateAxis.COLOR)
        assertEquals(setOf(TemplateAxis.FONT), backToFont.axes())
        val r = ResolvedTemplate.resolve(karaokeClassic(), backToFont)
        assertEquals("#FFFFFF", r.color.base)
        assertEquals("black", r.font.weight)

        val reset = o.clearedAll()
        assertTrue(reset.isEmpty())
        val r2 = ResolvedTemplate.resolve(karaokeClassic(), reset)
        assertEquals("#FFFFFF", r2.color.base)
        assertEquals("bold", r2.font.weight)
    }

    @Test
    fun `motion and background overrides stay independent`() {
        val o = TemplateOverrides(
            motion = MotionOverride(entryType = "popIn", entryDuration = 0.22),
            background = BackgroundOverride(type = "solidBox", color = "#000000")
        )
        val r = ResolvedTemplate.resolve(karaokeClassic(), o)
        assertEquals("popIn", r.motion.entry.type)
        assertEquals(0.22, r.motion.entry.duration ?: -1.0, 0.0001)
        assertEquals("none", r.motion.exit.type) // base exit untouched
        assertEquals("solidBox", r.background.type)
        assertEquals("#000000", r.background.color)
        // Highlight axis untouched by motion/background edits.
        assertEquals("colorChange", r.wordHighlight.type)
    }

    @Test
    fun `blank directions fall back to a safe rtl-ltr pair`() {
        val base = karaokeClassic().copy(
            language = karaokeClassic().language.copy(directionFa = "", directionEn = "")
        )
        val r = ResolvedTemplate.resolve(base)
        assertEquals("rtl", r.directionFa)
        assertEquals("ltr", r.directionEn)
    }

    // ---- PHASE 9: POSITION axis ----

    @Test
    fun `position override registers as POSITION axis`() {
        val o = TemplateOverrides(position = PositionOverride(positionX = 0.3f))
        assertEquals(setOf(TemplateAxis.POSITION), o.axes())
    }

    @Test
    fun `position override is cleared independently`() {
        val o = TemplateOverrides(
            color = ColorOverride(base = "#FF0000"),
            position = PositionOverride(positionX = 0.3f, positionY = 0.4f)
        )
        assertEquals(2, o.axes().size)
        val cleared = o.cleared(TemplateAxis.POSITION)
        assertEquals(setOf(TemplateAxis.COLOR), cleared.axes())
        assertEquals(null, cleared.position)
    }

    @Test
    fun `seven axes can all be active`() {
        val o = TemplateOverrides(
            font = FontOverride(family = "Lalezar"),
            color = ColorOverride(base = "#FF0000"),
            shadow = ShadowOverride(type = "glow"),
            wordHighlight = HighlightOverride(type = "wordScale"),
            background = BackgroundOverride(type = "gradient"),
            motion = MotionOverride(entryType = "popIn"),
            position = PositionOverride(positionX = 0.5f)
        )
        assertEquals(7, o.axes().size)
        assertTrue(o.axes().containsAll(TemplateAxis.entries.toSet()))
    }

    @Test
    fun `clearedAll resets position too`() {
        val o = TemplateOverrides(position = PositionOverride(positionX = 0.1f))
        assertFalse(o.isEmpty())
        val reset = o.clearedAll()
        assertTrue(reset.isEmpty())
        assertEquals(null, reset.position)
    }

    @Test
    fun `FontOverride scaleRatio field defaults to null`() {
        val f = FontOverride()
        assertEquals(null, f.scaleRatio)
        val f2 = FontOverride(scaleRatio = 1.5f)
        assertEquals(1.5f, f2.scaleRatio!!, 0.001f)
    }
}
