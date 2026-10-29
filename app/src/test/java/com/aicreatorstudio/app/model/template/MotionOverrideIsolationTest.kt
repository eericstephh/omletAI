package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PHASE 8 — isolation tests for [SubtitleTemplate.withMotionOverride]:
 * changing the motion axis must leave font, color, shadow, highlight,
 * and background byte-identical.
 */
class MotionOverrideIsolationTest {

    private fun baseTemplate(): SubtitleTemplate = SubtitleTemplate(
        name = "Test Motion",
        id = "test_motion",
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 300,
        easingIn = null,
        easingOut = null,
        motionKind = WordMotionKind.BOUNCE_POP,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        activeWordColorArgb = 0xFFFFD600.toInt(),
        backgroundKind = CaptionBackgroundKind.GRADIENT,
        gradientAngleDeg = 90f,
        style = SubtitleStyle(
            backgroundColorArgb = 0xFF1C1C1E.toInt(),
            textColorArgb = 0xFFFFFFFF.toInt()
        )
    )

    @Test
    fun changingEntryAnim_onlyTouchesMotionAxis() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(
            MotionOverride(entryType = "popin")
        )
        assertEquals(TextOverlayAnimation.POP, changed.animationIn)
        // Other motion fields unchanged.
        assertEquals(base.animationOut, changed.animationOut)
        assertEquals(base.animationDurationMs, changed.animationDurationMs)
        assertEquals(base.easingIn, changed.easingIn)
        assertEquals(base.easingOut, changed.easingOut)
        // Background untouched.
        assertEquals(base.backgroundKind, changed.backgroundKind)
        assertEquals(base.gradientAngleDeg, changed.gradientAngleDeg)
        assertEquals(base.style.backgroundColorArgb, changed.style.backgroundColorArgb)
        // Color untouched.
        assertEquals(base.activeWordColorArgb, changed.activeWordColorArgb)
        // Highlight untouched.
        assertEquals(base.motionKind, changed.motionKind)
    }

    @Test
    fun changingExitAnim_onlyTouchesMotionAxis() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(
            MotionOverride(exitType = "glitchout")
        )
        assertEquals(TextOverlayAnimation.GLITCH_OUT, changed.animationOut)
        assertEquals(base.animationIn, changed.animationIn)
        assertEquals(base.backgroundKind, changed.backgroundKind)
    }

    @Test
    fun changingDuration_onlyTouchesMotionAxis() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(
            MotionOverride(entryDuration = 0.5)
        )
        assertEquals(500, changed.animationDurationMs)
        assertEquals(base.animationIn, changed.animationIn)
        assertEquals(base.animationOut, changed.animationOut)
        assertEquals(base.easingIn, changed.easingIn)
        assertEquals(base.backgroundKind, changed.backgroundKind)
    }

    @Test
    fun changingEasingIn_onlyTouchesMotionAxis() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(
            MotionOverride(entryEasing = "EASE_OUT_BACK")
        )
        assertEquals("EASE_OUT_BACK", changed.easingIn)
        assertEquals(base.animationIn, changed.animationIn)
        assertEquals(base.animationDurationMs, changed.animationDurationMs)
        assertEquals(base.backgroundKind, changed.backgroundKind)
    }

    @Test
    fun changingEasingOut_onlyTouchesMotionAxis() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(
            MotionOverride(exitEasing = "EASE_OUT_BOUNCE")
        )
        assertEquals("EASE_OUT_BOUNCE", changed.easingOut)
        assertEquals(base.animationOut, changed.animationOut)
        assertEquals(base.backgroundKind, changed.backgroundKind)
    }

    @Test
    fun nullOverride_returnsUnchanged() {
        val base = baseTemplate()
        val same = base.withMotionOverride(null)
        assertEquals(base, same)
    }

    @Test
    fun multipleFields_changedTogether() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(
            MotionOverride(
                entryType = "glitchin",
                exitType = "glitchout",
                entryDuration = 0.8,
                entryEasing = "EASE_OUT_ELASTIC",
                exitEasing = "EASE_IN_OUT_CUBIC"
            )
        )
        assertEquals(TextOverlayAnimation.GLITCH_IN, changed.animationIn)
        assertEquals(TextOverlayAnimation.GLITCH_OUT, changed.animationOut)
        assertEquals(800, changed.animationDurationMs)
        assertEquals("EASE_OUT_ELASTIC", changed.easingIn)
        assertEquals("EASE_IN_OUT_CUBIC", changed.easingOut)
        // Other axes untouched.
        assertEquals(base.backgroundKind, changed.backgroundKind)
        assertEquals(base.activeWordColorArgb, changed.activeWordColorArgb)
    }

    @Test
    fun idempotent_doubleApply_sameResult() {
        val base = baseTemplate()
        val o = MotionOverride(entryType = "popin", entryEasing = "EASE_OUT_BACK")
        val first = base.withMotionOverride(o)
        val second = first.withMotionOverride(o)
        assertEquals(first.animationIn, second.animationIn)
        assertEquals(first.easingIn, second.easingIn)
        assertEquals(first.animationDurationMs, second.animationDurationMs)
    }

    @Test
    fun entryDuration_clampedToValidRange() {
        val base = baseTemplate()
        // Too short: should clamp to 60ms.
        val tooShort = base.withMotionOverride(MotionOverride(entryDuration = 0.01))
        assertEquals(60, tooShort.animationDurationMs)
        // Too long: should clamp to 1500ms.
        val tooLong = base.withMotionOverride(MotionOverride(entryDuration = 5.0))
        assertEquals(1500, tooLong.animationDurationMs)
    }

    @Test
    fun glitchExit_mapsToCorrectAnimation() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(MotionOverride(exitType = "glitchout"))
        assertEquals(TextOverlayAnimation.GLITCH_OUT, changed.animationOut)
    }

    @Test
    fun fadein_mapsToCorrectAnimation() {
        val base = baseTemplate()
        val changed = base.withMotionOverride(MotionOverride(entryType = "fadein"))
        assertEquals(TextOverlayAnimation.FADE_IN, changed.animationIn)
    }
}
