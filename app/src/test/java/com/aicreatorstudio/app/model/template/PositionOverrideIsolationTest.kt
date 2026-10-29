package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.SubtitleStyle
import org.junit.Assert.*
import org.junit.Test

class PositionOverrideIsolationTest {

    private fun baseTemplate() = SubtitleTemplate(
        name = "تست",
        style = SubtitleStyle(
            fontFamily = "Vazirmatn",
            fontWeightValue = 400,
            fontSizeSp = 36f,
            textColorArgb = 0xFFFFFFFF.toInt(),
            strokeColorArgb = 0xFF000000.toInt(),
            strokeWidthSp = 2f,
            shadowColorArgb = 0x80000000.toInt(),
            shadowOffsetYDp = 4f,
            positionX = 0.5f,
            positionY = 0.85f,
            verticalAlignment = SubtitleAlignmentV.BOTTOM,
            horizontalAlignment = SubtitleAlignmentH.CENTER
        )
    )

    @Test
    fun `null override returns template unchanged`() {
        val t = baseTemplate()
        val result = with(JsonTemplateMapper) { t.withPositionOverride(null) }
        assertEquals(t.style.positionX, result.style.positionX, 0.001f)
        assertEquals(t.style.positionY, result.style.positionY, 0.001f)
    }

    @Test
    fun `position override changes positionX and positionY`() {
        val t = baseTemplate()
        val o = PositionOverride(positionX = 0.2f, positionY = 0.3f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(0.2f, result.style.positionX, 0.01f)
        assertEquals(0.3f, result.style.positionY, 0.01f)
    }

    @Test
    fun `position override does not touch font family`() {
        val t = baseTemplate()
        val o = PositionOverride(positionX = 0.1f, positionY = 0.1f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(t.style.fontFamily, result.style.fontFamily)
    }

    @Test
    fun `position override does not touch text color`() {
        val t = baseTemplate()
        val o = PositionOverride(positionX = 0.9f, positionY = 0.9f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(t.style.textColorArgb, result.style.textColorArgb)
    }

    @Test
    fun `position override does not touch stroke and shadow`() {
        val t = baseTemplate()
        val o = PositionOverride(positionX = 0.5f, positionY = 0.5f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(t.style.strokeWidthSp, result.style.strokeWidthSp, 0.01f)
        assertEquals(t.style.shadowOffsetYDp, result.style.shadowOffsetYDp, 0.01f)
    }

    @Test
    fun `position override clamps X to safe margins`() {
        val t = baseTemplate()
        val o = PositionOverride(positionX = 0.0f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(PositionSafeMargins.HORIZONTAL, result.style.positionX, 0.01f)
    }

    @Test
    fun `position override clamps Y to safe margins`() {
        val t = baseTemplate()
        val o = PositionOverride(positionY = 1.0f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(1f - PositionSafeMargins.BOTTOM, result.style.positionY, 0.01f)
    }

    @Test
    fun `scaleRatio multiplies fontSizeSp`() {
        val t = baseTemplate()
        val o = PositionOverride(scaleRatio = 1.5f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(54f, result.style.fontSizeSp, 0.5f)
    }

    @Test
    fun `scaleRatio clamped to 0_5 minimum`() {
        val t = baseTemplate()
        val o = PositionOverride(scaleRatio = 0.1f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(18f, result.style.fontSizeSp, 0.5f)
    }

    @Test
    fun `scaleRatio clamped to 2_0 maximum`() {
        val t = baseTemplate()
        val o = PositionOverride(scaleRatio = 3.0f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(72f, result.style.fontSizeSp, 0.5f)
    }

    @Test
    fun `position override derives alignment bands`() {
        val t = baseTemplate()
        val oLeft = PositionOverride(positionX = 0.15f, positionY = 0.12f)
        val r1 = with(JsonTemplateMapper) { t.withPositionOverride(oLeft) }
        assertEquals(SubtitleAlignmentH.LEFT, r1.style.horizontalAlignment)
        assertEquals(SubtitleAlignmentV.TOP, r1.style.verticalAlignment)

        val oRight = PositionOverride(positionX = 0.85f, positionY = 0.82f)
        val r2 = with(JsonTemplateMapper) { t.withPositionOverride(oRight) }
        assertEquals(SubtitleAlignmentH.RIGHT, r2.style.horizontalAlignment)
        assertEquals(SubtitleAlignmentV.BOTTOM, r2.style.verticalAlignment)

        val oCenter = PositionOverride(positionX = 0.5f, positionY = 0.5f)
        val r3 = with(JsonTemplateMapper) { t.withPositionOverride(oCenter) }
        assertEquals(SubtitleAlignmentH.CENTER, r3.style.horizontalAlignment)
        assertEquals(SubtitleAlignmentV.CENTER, r3.style.verticalAlignment)
    }

    @Test
    fun `override with only positionX preserves positionY`() {
        val t = baseTemplate()
        val o = PositionOverride(positionX = 0.3f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(0.3f, result.style.positionX, 0.01f)
        assertEquals(0.85f, result.style.positionY, 0.01f)
    }

    @Test
    fun `override with only scaleRatio preserves position`() {
        val t = baseTemplate()
        val o = PositionOverride(scaleRatio = 2.0f)
        val result = with(JsonTemplateMapper) { t.withPositionOverride(o) }
        assertEquals(0.5f, result.style.positionX, 0.01f)
        assertEquals(0.85f, result.style.positionY, 0.01f)
    }
}
