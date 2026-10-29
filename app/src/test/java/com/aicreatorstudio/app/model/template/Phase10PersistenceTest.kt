package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.SubtitleStyle
import org.junit.Assert.*
import org.junit.Test

class Phase10PersistenceTest {

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
            shadowColorArgb = 0x80000000.toInt(),
            shadowOffsetYDp = 2f,
            backgroundEnabled = true,
            backgroundColorArgb = 0xFF000000.toInt(),
            backgroundCornerRadiusDp = 8f,
            backgroundOpacity = 0.55f,
            positionX = 0.5f,
            positionY = 0.85f,
            verticalAlignment = SubtitleAlignmentV.BOTTOM,
            horizontalAlignment = SubtitleAlignmentH.CENTER
        )
    )

    // ---- 10A: easingIn / easingOut round-trip ----

    @Test
    fun `easingIn and easingOut survive round trip`() {
        val t = baseTemplate().copy(easingIn = "easeOutBack", easingOut = "easeInCubic")
        val json = t.toJson()
        val r = SubtitleTemplate.fromJson(json)!!
        assertEquals("easeOutBack", r.easingIn)
        assertEquals("easeInCubic", r.easingOut)
    }

    @Test
    fun `null easing defaults preserved in round trip`() {
        val json = baseTemplate().toJson()
        val r = SubtitleTemplate.fromJson(json)!!
        assertNull(r.easingIn)
        assertNull(r.easingOut)
    }

    // ---- 10E: animationDurationMsOut ----

    @Test
    fun `animationDurationMsOut survives round trip`() {
        val t = baseTemplate().copy(animationDurationMs = 300, animationDurationMsOut = 500)
        val r = SubtitleTemplate.fromJson(t.toJson())!!
        assertEquals(300, r.animationDurationMs)
        assertEquals(500, r.animationDurationMsOut)
    }

    @Test
    fun `null animationDurationMsOut defaults to null`() {
        val r = SubtitleTemplate.fromJson(baseTemplate().toJson())!!
        assertNull(r.animationDurationMsOut)
    }

    @Test
    fun `withMotionOverride applies exitDuration`() {
        val t = baseTemplate()
        val o = MotionOverride(entryDuration = 0.3, exitDuration = 0.8)
        val r = with(JsonTemplateMapper) { t.withMotionOverride(o) }
        assertEquals(300, r.animationDurationMs)
        assertEquals(800, r.animationDurationMsOut)
    }

    @Test
    fun `withMotionOverride null exitDuration preserves existing`() {
        val t = baseTemplate().copy(animationDurationMsOut = 450)
        val o = MotionOverride(entryDuration = 0.2)
        val r = with(JsonTemplateMapper) { t.withMotionOverride(o) }
        assertEquals(450, r.animationDurationMsOut)
    }

    @Test
    fun `withMotionOverride exitDuration clamped`() {
        val t = baseTemplate()
        val r1 = with(JsonTemplateMapper) { t.withMotionOverride(MotionOverride(exitDuration = 0.01)) }
        assertEquals(60, r1.animationDurationMsOut)
        val r2 = with(JsonTemplateMapper) { t.withMotionOverride(MotionOverride(exitDuration = 5.0)) }
        assertEquals(1500, r2.animationDurationMsOut)
    }

    // ---- 10B: withShadowOverride ----

    @Test
    fun `shadow override outline enables stroke disables shadow`() {
        val t = baseTemplate().copy(style = baseTemplate().style.copy(strokeEnabled = false, shadowEnabled = true))
        val o = ShadowOverride(type = "outline", color = "#FF0000", width = 3f)
        val r = with(JsonTemplateMapper) { t.withShadowOverride(o) }
        assertTrue(r.style.strokeEnabled)
        assertFalse(r.style.shadowEnabled)
        assertEquals(0xFFFF0000.toInt(), r.style.strokeColorArgb)
        assertEquals(3f, r.style.strokeWidthSp, 0.01f)
    }

    @Test
    fun `shadow override dropshadow enables shadow disables stroke`() {
        val o = ShadowOverride(type = "dropshadow", color = "#00FF00")
        val r = with(JsonTemplateMapper) { baseTemplate().withShadowOverride(o) }
        assertFalse(r.style.strokeEnabled)
        assertTrue(r.style.shadowEnabled)
        assertEquals(0xFF00FF00.toInt(), r.style.shadowColorArgb)
    }

    @Test
    fun `shadow override glow sets glow radius`() {
        val o = ShadowOverride(type = "glow", color = "#0000FF", radius = 8f)
        val r = with(JsonTemplateMapper) { baseTemplate().withShadowOverride(o) }
        assertEquals(8f, r.activeWordGlowRadiusDp, 0.01f)
        assertEquals(0xFF0000FF.toInt(), r.accentColorArgb)
    }

    @Test
    fun `shadow override none disables everything`() {
        val t = baseTemplate().copy(
            style = baseTemplate().style.copy(strokeEnabled = true, shadowEnabled = true),
            activeWordGlowRadiusDp = 5f
        )
        val r = with(JsonTemplateMapper) { t.withShadowOverride(ShadowOverride(type = "none")) }
        assertFalse(r.style.strokeEnabled)
        assertFalse(r.style.shadowEnabled)
        assertEquals(0f, r.activeWordGlowRadiusDp, 0.001f)
    }

    @Test
    fun `null shadow override returns unchanged`() {
        val t = baseTemplate()
        val r = with(JsonTemplateMapper) { t.withShadowOverride(null) }
        assertEquals(t.style.strokeEnabled, r.style.strokeEnabled)
    }

    @Test
    fun `shadow override preserves font`() {
        val o = ShadowOverride(type = "outline")
        val r = with(JsonTemplateMapper) { baseTemplate().withShadowOverride(o) }
        assertEquals("Vazirmatn", r.style.fontFamily)
        assertEquals(36f, r.style.fontSizeSp, 0.01f)
    }

    @Test
    fun `shadow width clamped to 8`() {
        val o = ShadowOverride(type = "outline", width = 15f)
        val r = with(JsonTemplateMapper) { baseTemplate().withShadowOverride(o) }
        assertEquals(8f, r.style.strokeWidthSp, 0.01f)
    }

    // ---- 10C: highlight inactiveColor ----

    @Test
    fun `highlight inactiveColor changes textColorArgb`() {
        val o = HighlightOverride(type = "colorChange", inactiveColor = "#AAAAAA")
        val r = with(JsonTemplateMapper) { baseTemplate().withHighlightOverride(o) }
        assertEquals(0xFFAAAAAA.toInt(), r.style.textColorArgb)
    }

    @Test
    fun `highlight without inactiveColor preserves textColorArgb`() {
        val t = baseTemplate()
        val r = with(JsonTemplateMapper) { t.withHighlightOverride(HighlightOverride(type = "colorChange")) }
        assertEquals(t.style.textColorArgb, r.style.textColorArgb)
    }

    @Test
    fun `highlight barColor and trackColor applied`() {
        val o = HighlightOverride(type = "progressbar", barColor = "#FF0000", trackColor = "#333333")
        val r = with(JsonTemplateMapper) { baseTemplate().withHighlightOverride(o) }
        assertNotNull(r.progressBar)
        assertEquals(0xFFFF0000.toInt(), r.progressBar!!.colorArgb)
        assertEquals(0xFF333333.toInt(), r.progressBar!!.trackArgb)
    }

    @Test
    fun `highlight inactiveColor with box type`() {
        val o = HighlightOverride(type = "box", inactiveColor = "#555555")
        val r = with(JsonTemplateMapper) { baseTemplate().withHighlightOverride(o) }
        assertEquals(0xFF555555.toInt(), r.style.textColorArgb)
    }

    // ---- 10D: background cornerRadius + overlayOpacity ----

    @Test
    fun `background cornerRadius maps to style`() {
        val o = BackgroundOverride(type = "solidbox", cornerRadius = 20f)
        val r = with(JsonTemplateMapper) { baseTemplate().withBackgroundOverride(o) }
        assertEquals(20f, r.style.backgroundCornerRadiusDp, 0.01f)
    }

    @Test
    fun `background overlayOpacity maps to backgroundOpacity`() {
        val o = BackgroundOverride(type = "solidbox", overlayOpacity = 0.8f)
        val r = with(JsonTemplateMapper) { baseTemplate().withBackgroundOverride(o) }
        assertEquals(0.8f, r.style.backgroundOpacity, 0.01f)
    }

    @Test
    fun `background cornerRadius clamped to 40`() {
        val o = BackgroundOverride(type = "solidbox", cornerRadius = 100f)
        val r = with(JsonTemplateMapper) { baseTemplate().withBackgroundOverride(o) }
        assertEquals(40f, r.style.backgroundCornerRadiusDp, 0.01f)
    }

    @Test
    fun `background opacity clamped to 1`() {
        val o = BackgroundOverride(type = "solidbox", overlayOpacity = 2.0f)
        val r = with(JsonTemplateMapper) { baseTemplate().withBackgroundOverride(o) }
        assertEquals(1.0f, r.style.backgroundOpacity, 0.01f)
    }

    @Test
    fun `background null override returns unchanged`() {
        val t = baseTemplate()
        val r = with(JsonTemplateMapper) { t.withBackgroundOverride(null) }
        assertEquals(t.style.backgroundColorArgb, r.style.backgroundColorArgb)
    }

    @Test
    fun `background preserves font and position`() {
        val o = BackgroundOverride(type = "solidbox", color = "#FF0000", cornerRadius = 16f)
        val r = with(JsonTemplateMapper) { baseTemplate().withBackgroundOverride(o) }
        assertEquals("Vazirmatn", r.style.fontFamily)
        assertEquals(0.5f, r.style.positionX, 0.01f)
        assertEquals(0.85f, r.style.positionY, 0.01f)
    }

    // ---- 10A: all 7 axes independent round-trip ----

    @Test
    fun `all seven overrides applied independently then serialized`() {
        val t = baseTemplate()
        val result = with(JsonTemplateMapper) {
            t.withShadowOverride(ShadowOverride(type = "outline", width = 3f))
                .withHighlightOverride(HighlightOverride(type = "colorChange", inactiveColor = "#CCCCCC"))
                .withBackgroundOverride(BackgroundOverride(type = "solidbox", cornerRadius = 16f, overlayOpacity = 0.7f))
                .withMotionOverride(MotionOverride(entryType = "fadein", exitType = "fadeout", entryDuration = 0.4, exitDuration = 0.6, entryEasing = "easeOutBack", exitEasing = "easeInCubic"))
                .withPositionOverride(PositionOverride(positionX = 0.3f, positionY = 0.4f, scaleRatio = 1.5f))
        }
        val json = result.toJson()
        val restored = SubtitleTemplate.fromJson(json)!!
        assertTrue(restored.style.strokeEnabled)
        assertEquals(0xFFCCCCCC.toInt(), restored.style.textColorArgb)
        assertEquals(16f, restored.style.backgroundCornerRadiusDp, 0.01f)
        assertEquals(0.7f, restored.style.backgroundOpacity, 0.01f)
        assertEquals("easeOutBack", restored.easingIn)
        assertEquals("easeInCubic", restored.easingOut)
        assertEquals(400, restored.animationDurationMs)
        assertEquals(600, restored.animationDurationMsOut)
        assertEquals(0.3f, restored.style.positionX, 0.01f)
        assertEquals(0.4f, restored.style.positionY, 0.01f)
    }
}
