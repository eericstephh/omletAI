package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.SubtitleStyle
import org.junit.Assert.*
import org.junit.Test

class Phase11TabIsolationTest {

    private fun baseTemplate() = SubtitleTemplate(
        name = "test",
        style = SubtitleStyle(
            fontFamily = "Vazirmatn",
            fontWeightValue = 400,
            fontSizeSp = 36f,
            textColorArgb = 0xFFFFFFFF.toInt(),
            strokeEnabled = true,
            strokeColorArgb = 0xFF000000.toInt(),
            strokeWidthSp = 2f,
            shadowEnabled = false,
            backgroundEnabled = true,
            backgroundColorArgb = 0xFF000000.toInt(),
            backgroundCornerRadiusDp = 8f,
            backgroundOpacity = 0.55f,
            positionX = 0.5f,
            positionY = 0.85f,
            verticalAlignment = SubtitleAlignmentV.BOTTOM,
            horizontalAlignment = SubtitleAlignmentH.CENTER
        ),
        activeWordColorArgb = 0xFFFFD60A.toInt(),
        activeWordGlowRadiusDp = 0f
    )

    // ---- 11A: withFontOverride ----

    @Test
    fun `font override changes fontFamily`() {
        val o = FontOverride(family = "Lalezar")
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertEquals("Lalezar", r.style.fontFamily)
    }

    @Test
    fun `font override changes weight to bold`() {
        val o = FontOverride(weight = "bold")
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertTrue(r.style.bold)
        assertEquals(700, r.style.fontWeightValue)
    }

    @Test
    fun `font override changes weight to regular`() {
        val o = FontOverride(weight = "regular")
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertFalse(r.style.bold)
        assertEquals(400, r.style.fontWeightValue)
    }

    @Test
    fun `font override sizeRatio scales fontSizeSp`() {
        val o = FontOverride(sizeRatio = 1.5f)
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertEquals(54f, r.style.fontSizeSp, 0.5f) // 36 * 1.5
    }

    @Test
    fun `font override does not touch textColorArgb`() {
        val t = baseTemplate()
        val o = FontOverride(family = "Lalezar")
        val r = with(JsonTemplateMapper) { t.withFontOverride(o) }
        assertEquals(t.style.textColorArgb, r.style.textColorArgb)
    }

    @Test
    fun `font override does not touch position`() {
        val t = baseTemplate()
        val o = FontOverride(family = "Lalezar")
        val r = with(JsonTemplateMapper) { t.withFontOverride(o) }
        assertEquals(t.style.positionX, r.style.positionX, 0.001f)
        assertEquals(t.style.positionY, r.style.positionY, 0.001f)
    }

    @Test
    fun `null font override returns unchanged`() {
        val t = baseTemplate()
        val r = with(JsonTemplateMapper) { t.withFontOverride(null) }
        assertEquals(t.style.fontFamily, r.style.fontFamily)
    }

    // ---- 11A: withColorOverride ----

    @Test
    fun `color override changes textColorArgb`() {
        val o = ColorOverride(base = "#FF0000")
        val r = with(JsonTemplateMapper) { baseTemplate().withColorOverride(o) }
        assertEquals(0xFFFF0000.toInt(), r.style.textColorArgb)
    }

    @Test
    fun `color override also changes activeWordColorArgb`() {
        val o = ColorOverride(base = "#00FF00")
        val r = with(JsonTemplateMapper) { baseTemplate().withColorOverride(o) }
        assertEquals(0xFF00FF00.toInt(), r.activeWordColorArgb)
    }

    @Test
    fun `color override does not touch fontFamily`() {
        val t = baseTemplate()
        val o = ColorOverride(base = "#FF0000")
        val r = with(JsonTemplateMapper) { t.withColorOverride(o) }
        assertEquals(t.style.fontFamily, r.style.fontFamily)
    }

    @Test
    fun `color override does not touch position`() {
        val t = baseTemplate()
        val o = ColorOverride(base = "#FF0000")
        val r = with(JsonTemplateMapper) { t.withColorOverride(o) }
        assertEquals(t.style.positionX, r.style.positionX, 0.001f)
    }

    @Test
    fun `null color override returns unchanged`() {
        val t = baseTemplate()
        val r = with(JsonTemplateMapper) { t.withColorOverride(null) }
        assertEquals(t.style.textColorArgb, r.style.textColorArgb)
    }

    @Test
    fun `color override null base returns unchanged`() {
        val t = baseTemplate()
        val r = with(JsonTemplateMapper) { t.withColorOverride(ColorOverride()) }
        assertEquals(t.style.textColorArgb, r.style.textColorArgb)
    }

    // ---- 11C: Font override isolation (all 8 axes independent) ----

    @Test
    fun `all eight axes can be applied independently`() {
        val t = baseTemplate()
        val r = with(JsonTemplateMapper) {
            t.withFontOverride(FontOverride(family = "Lalezar", weight = "bold"))
                .withColorOverride(ColorOverride(base = "#FF0000"))
                .withShadowOverride(ShadowOverride(type = "outline", width = 3f))
                .withHighlightOverride(HighlightOverride(type = "colorChange"))
                .withBackgroundOverride(BackgroundOverride(type = "solidbox", cornerRadius = 16f))
                .withMotionOverride(MotionOverride(entryType = "fadein"))
                .withPositionOverride(PositionOverride(positionX = 0.3f, positionY = 0.4f))
        }
        // Font axis
        assertEquals("Lalezar", r.style.fontFamily)
        assertTrue(r.style.bold)
        // Color axis — textColorArgb set by color override (highlight without inactiveColor preserves it)
        assertEquals(0xFFFF0000.toInt(), r.style.textColorArgb)
        // Shadow axis
        assertTrue(r.style.strokeEnabled)
        assertEquals(3f, r.style.strokeWidthSp, 0.01f)
        // Background axis
        assertEquals(16f, r.style.backgroundCornerRadiusDp, 0.01f)
        // Motion axis
        assertEquals(com.aicreatorstudio.app.model.TextOverlayAnimation.FADE_IN, r.animationIn)
        // Position axis
        assertEquals(0.3f, r.style.positionX, 0.01f)
        assertEquals(0.4f, r.style.positionY, 0.01f)
    }

    @Test
    fun `clearing one axis does not affect others`() {
        val t = baseTemplate()
        var overrides = TemplateOverrides(
            font = FontOverride(family = "Lalezar"),
            color = ColorOverride(base = "#FF0000"),
            shadow = ShadowOverride(type = "outline")
        )
        assertEquals(3, overrides.axes().size)
        overrides = overrides.cleared(TemplateAxis.FONT)
        assertEquals(2, overrides.axes().size)
        assertNull(overrides.font)
        assertNotNull(overrides.color)
        assertNotNull(overrides.shadow)
    }

    // ---- 11A: withFontOverride edge cases ----

    @Test
    fun `font override thin weight`() {
        val o = FontOverride(weight = "thin")
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertEquals(100, r.style.fontWeightValue)
        assertFalse(r.style.bold)
    }

    @Test
    fun `font override heavy weight`() {
        val o = FontOverride(weight = "heavy")
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertEquals(900, r.style.fontWeightValue)
        assertTrue(r.style.bold)
    }

    @Test
    fun `font override sizeRatio clamped to 0_5`() {
        val o = FontOverride(sizeRatio = 0.1f)
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertEquals(18f, r.style.fontSizeSp, 0.5f) // 36 * 0.5
    }

    @Test
    fun `font override sizeRatio clamped to 2_0`() {
        val o = FontOverride(sizeRatio = 3.0f)
        val r = with(JsonTemplateMapper) { baseTemplate().withFontOverride(o) }
        assertEquals(72f, r.style.fontSizeSp, 0.5f) // 36 * 2.0
    }
}
