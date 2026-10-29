package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PHASE 7 — isolation tests for [SubtitleTemplate.withBackgroundOverride]:
 * changing the background axis must leave font, color, shadow,
 * highlight, and motion byte-identical.
 */
class BackgroundOverrideIsolationTest {

    private fun baseTemplate(): SubtitleTemplate = SubtitleTemplate(
        name = "Test BG",
        id = "test_bg",
        backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        activeWordColorArgb = 0xFFFFD600.toInt(),
        accentColorArgb = 0xFF00E5FF.toInt(),
        gradientAngleDeg = 45f,
        style = SubtitleStyle(
            backgroundEnabled = true,
            backgroundColorArgb = 0xFF1C1C1E.toInt(),
            textColorArgb = 0xFFFFFFFF.toInt()
        )
    )

    @Test
    fun changingKind_onlyTouchesBackgroundAxis() {
        val base = baseTemplate()
        val changed = base.withBackgroundOverride(
            BackgroundOverride(type = "letterboxbars")
        )
        assertEquals(CaptionBackgroundKind.LETTERBOX_BARS, changed.backgroundKind)
        assertEquals(base.style.textColorArgb, changed.style.textColorArgb)
        assertEquals(base.motionKind, changed.motionKind)
        assertEquals(base.activeWordColorArgb, changed.activeWordColorArgb)
        assertEquals(base.progressBar, changed.progressBar)
        assertEquals(base.gradientAngleDeg, changed.gradientAngleDeg)
    }

    @Test
    fun changingColor_onlyTouchesBackgroundColor() {
        val base = baseTemplate()
        val changed = base.withBackgroundOverride(
            BackgroundOverride(color = "#FF0000")
        )
        assertNotEquals(base.style.backgroundColorArgb, changed.style.backgroundColorArgb)
        assertEquals(base.id, changed.id)
        assertEquals(base.motionKind, changed.motionKind)
        assertEquals(base.activeWordColorArgb, changed.activeWordColorArgb)
        assertEquals(base.gradientAngleDeg, changed.gradientAngleDeg)
        assertEquals(base.backgroundKind, changed.backgroundKind)
    }

    @Test
    fun changingAngle_onlyTouchesGradientAngle() {
        val base = baseTemplate()
        val changed = base.withBackgroundOverride(
            BackgroundOverride(angle = 180f)
        )
        assertEquals(180f, changed.gradientAngleDeg!!)
        assertEquals(base.backgroundKind, changed.backgroundKind)
        assertEquals(base.style.backgroundColorArgb, changed.style.backgroundColorArgb)
        assertEquals(base.motionKind, changed.motionKind)
        assertEquals(base.activeWordColorArgb, changed.activeWordColorArgb)
    }

    @Test
    fun nullOverride_returnsUnchanged() {
        val base = baseTemplate()
        val same = base.withBackgroundOverride(null)
        assertEquals(base, same)
    }

    @Test
    fun switchToNone_disablesBackgroundEnabled() {
        val base = baseTemplate().copy(
            backgroundKind = CaptionBackgroundKind.GRADIENT,
            style = baseTemplate().style.copy(backgroundEnabled = true)
        )
        val changed = base.withBackgroundOverride(
            BackgroundOverride(type = "none")
        )
        assertEquals(CaptionBackgroundKind.NONE, changed.backgroundKind)
        assertEquals(false, changed.style.backgroundEnabled)
    }

    @Test
    fun switchFromNone_toSolid_enablesBackground() {
        val base = baseTemplate().copy(
            backgroundKind = CaptionBackgroundKind.NONE,
            style = baseTemplate().style.copy(backgroundEnabled = false)
        )
        val changed = base.withBackgroundOverride(
            BackgroundOverride(type = "solidbox")
        )
        assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, changed.backgroundKind)
        assertEquals(true, changed.style.backgroundEnabled)
    }

    @Test
    fun gradientAngle_null_keepsExistingAngle() {
        val base = baseTemplate().copy(gradientAngleDeg = 120f)
        val changed = base.withBackgroundOverride(
            BackgroundOverride(type = "gradientbox")
        )
        assertEquals(120f, changed.gradientAngleDeg!!)
    }

    @Test
    fun multipleFields_changedTogether() {
        val base = baseTemplate()
        val changed = base.withBackgroundOverride(
            BackgroundOverride(
                type = "gradientbox",
                color = "#6C3DF4",
                angle = 270f
            )
        )
        assertEquals(CaptionBackgroundKind.GRADIENT, changed.backgroundKind)
        assertEquals(270f, changed.gradientAngleDeg!!)
        assertNotEquals(base.style.backgroundColorArgb, changed.style.backgroundColorArgb)
        assertEquals(base.motionKind, changed.motionKind)
        assertEquals(base.activeWordColorArgb, changed.activeWordColorArgb)
        assertEquals(base.progressBar, changed.progressBar)
    }

    @Test
    fun idempotent_doubleApply_sameResult() {
        val base = baseTemplate()
        val o = BackgroundOverride(type = "blurvideo")
        val first = base.withBackgroundOverride(o)
        val second = first.withBackgroundOverride(o)
        assertEquals(first.backgroundKind, second.backgroundKind)
        assertEquals(first.style.backgroundColorArgb, second.style.backgroundColorArgb)
        assertEquals(first.gradientAngleDeg, second.gradientAngleDeg!!)
    }
}
