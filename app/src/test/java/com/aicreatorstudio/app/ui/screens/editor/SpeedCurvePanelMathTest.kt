package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.ui.geometry.Offset
import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure geometry and formatting helpers behind
 * [SpeedCurvePanel].
 *
 * The composable itself is untestable here — `unitTests.isReturnDefaultValues
 * = true` makes every `android.*`/Compose call inert on the JVM, and
 * `detectDragGestures` needs a real pointer pipeline — so the maths that
 * the gestures *feed* is factored out into top-level functions and
 * pinned here instead. That is the same split `MotionTrackingGeometry`
 * uses for the ROI selector, for the same reason.
 *
 * `Offset` is a Compose type, but it is a plain immutable value class
 * with no framework calls, so it constructs and compares correctly under
 * the default-return stub.
 */
class SpeedCurvePanelMathTest {

    private fun pts(vararg pairs: Pair<Float, Float>) =
        pairs.map { SpeedPoint(it.first, it.second) }.sortedBy { it.normalizedTime }

    // ---- speedAt, the curve the graph draws ----

    @Test
    fun `speedAt holds the end knots outside their range`() {
        // 0.25 -> 2x and 0.75 -> 0.5x: outside the knots the value is
        // held, so t=0 reads the FIRST knot and t=1 the LAST one.
        val p = pts(0.25f to 2f, 0.75f to 0.5f)
        assertEquals("before the first knot the ramp holds its start", 2f, speedAt(p, 0f), 1e-6f)
        assertEquals(2f, speedAt(p, 0.25f), 1e-6f)
        assertEquals(0.5f, speedAt(p, 0.75f), 1e-6f)
        assertEquals("after the last knot the ramp holds its end", 0.5f, speedAt(p, 1f), 1e-6f)
    }

    @Test
    fun `speedAt interpolates linearly between knots`() {
        val p = pts(0f to 1f, 1f to 3f)
        assertEquals(2f, speedAt(p, 0.5f), 1e-6f)
    }

    @Test
    fun `speedAt on an empty list reads as normal`() {
        assertEquals(1f, speedAt(emptyList(), 0.5f), 1e-6f)
    }

    @Test
    fun `speedAt on a single knot returns that knot everywhere`() {
        val p = pts(0.5f to 4f)
        assertEquals(4f, speedAt(p, 0f), 1e-6f)
        assertEquals(4f, speedAt(p, 1f), 1e-6f)
    }

    /**
     * The panel's `speedAt` is a deliberate duplicate of
     * `SpeedCurve.speedAt` (see its KDoc). They must agree, or the drawn
     * curve would disagree with the duration readout beside it.
     */
    @Test
    fun `the panel's speedAt matches the model's for real presets`() {
        val presets = listOf(
            SpeedCurve.ofPreset(com.aicreatorstudio.app.model.SpeedCurvePreset.HERO),
            SpeedCurve.ofPreset(com.aicreatorstudio.app.model.SpeedCurvePreset.BULLET),
            SpeedCurve.ofPreset(com.aicreatorstudio.app.model.SpeedCurvePreset.MONTAGE),
            SpeedCurve.ofPreset(com.aicreatorstudio.app.model.SpeedCurvePreset.FLASH_IN),
            SpeedCurve.ofPreset(com.aicreatorstudio.app.model.SpeedCurvePreset.FLASH_OUT)
        )
        presets.forEach { curve ->
            val list = curve.sanitized().points
            var t = 0f
            while (t <= 1f) {
                assertEquals(
                    "panel/model disagreement at t=$t for ${curve.preset}",
                    curve.speedAt(t),
                    speedAt(list, t),
                    1e-5f
                )
                t += 0.01f
            }
        }
    }

    // ---- nearestKnot, the hit test behind both tap and drag ----

    @Test
    fun `nearestKnot finds the knot under the pointer`() {
        val p = pts(0f to 1f, 0.5f to 1f, 1f to 1f)
        // 0.5 of the width is the middle knot.
        val idx = nearestKnot(p, Offset(50f, speedToY(1f, 100f)), width = 100f, height = 100f)
        assertEquals(1, idx)
    }

    /**
     * Returning `-1` for empty space is load-bearing: the tap handler
     * uses this to decide whether a tap *adds* a knot or *grabs* one,
     * so an always-nearest implementation would make "tap to add" start
     * dragging whichever knot happened to be closest — and the user
     * could never add a point far from an existing one.
     */
    @Test
    fun `nearestKnot returns minus one when nothing is in reach`() {
        val p = pts(0f to 1f, 1f to 1f)
        val idx = nearestKnot(p, Offset(50f, speedToY(1f, 100f)), width = 100f, height = 100f)
        assertEquals(-1, idx)
    }

    @Test
    fun `nearestKnot is insensitive to a zero-size canvas`() {
        val p = pts(0f to 1f, 1f to 1f)
        assertEquals(-1, nearestKnot(p, Offset.Zero, width = 0f, height = 0f))
    }

    @Test
    fun `nearestKnot picks the closer of two candidates`() {
        val p = pts(0.2f to 1f, 0.8f to 1f)
        val y = speedToY(1f, 100f)
        assertEquals(0, nearestKnot(p, Offset(22f, y), 100f, 100f))
        assertEquals(1, nearestKnot(p, Offset(78f, y), 100f, 100f))
    }

    // ---- the y axis, which the drag maths depends on ----

    @Test
    fun `the y axis is centred so one-x sits in the middle`() {
        val y = speedToY(1f, 100f)
        // 0.25 and 8 are the axis ends; 1x is the geometric midpoint.
        assertEquals(50f, y, 0.5f)
    }

    @Test
    fun `faster speeds map higher on screen`() {
        assertTrue(speedToY(2f, 100f) < speedToY(1f, 100f))
        assertTrue(speedToY(0.5f, 100f) > speedToY(1f, 100f))
    }

    @Test
    fun `yToSpeed inverts speedToY`() {
        listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f).forEach { speed ->
            val y = speedToY(speed, 240f)
            assertEquals(speed, yToSpeed(y, 240f), 1e-3f)
        }
    }

    /**
     * Out-of-range rates are clamped to the axis rather than drawn off
     * the canvas: a stored 0.12x knot must still be *visible* (so the
     * user can drag it back) instead of silently painting above the top
     * edge where nothing can be grabbed.
     */
    @Test
    fun `speeds outside the axis clamp to its edges instead of leaving the canvas`() {
        val top = speedToY(100f, 100f)
        val bottom = speedToY(0.0001f, 100f)
        assertTrue("must stay on canvas", top >= 0f)
        assertTrue("must stay on canvas", bottom <= 100f)
    }

    @Test
    fun `yToSpeed never leaves the model's legal range`() {
        // Dragging far above/below the canvas must not produce a rate
        // the model would have to reject.
        listOf(-5000f, 0f, 50f, 100f, 5000f).forEach { y ->
            val v = yToSpeed(y, 100f)
            assertTrue("$v below MIN", v >= SpeedCurve.MIN_SPEED - 1e-6f)
            assertTrue("$v above MAX", v <= SpeedCurve.MAX_SPEED + 1e-6f)
        }
    }

    // ---- insertion helpers ----

    @Test
    fun `widestGapMidpoint splits the largest interval`() {
        val p = pts(0f to 1f, 0.2f to 1f, 1f to 1f)
        // The 0.2..1.0 gap is widest, so the new knot lands at 0.6.
        assertEquals(0.6f, widestGapMidpoint(p), 1e-4f)
    }

    @Test
    fun `widestGapMidpoint spreads repeated insertions out`() {
        // Adding the returned point then asking again must not put the
        // next knot in the same place, or the "+" button would stack
        // knots on top of each other.
        var p = pts(0f to 1f, 1f to 1f)
        val first = widestGapMidpoint(p)
        p = (p + SpeedPoint(first, 1f)).sortedBy { it.normalizedTime }
        val second = widestGapMidpoint(p)
        assertTrue("insertions must not coincide", kotlin.math.abs(first - second) > 0.1f)
    }

    @Test
    fun `widestGapMidpoint on a lone knot moves to the far side`() {
        assertEquals(1f, widestGapMidpoint(pts(0f to 1f)), 1e-6f)
        assertEquals(0f, widestGapMidpoint(pts(1f to 1f)), 1e-6f)
    }

    @Test
    fun `widestGapMidpoint on an empty list is the middle`() {
        assertEquals(0.5f, widestGapMidpoint(emptyList()), 1e-6f)
    }

    @Test
    fun `averageSpeedAt is the mean of the knots`() {
        assertEquals(2f, averageSpeedAt(pts(0f to 1f, 1f to 3f)), 1e-6f)
        assertEquals(1f, averageSpeedAt(emptyList()), 1e-6f)
    }

    @Test
    fun `averageSpeedAt respects the model bounds`() {
        val v = averageSpeedAt(pts(0f to SpeedCurve.MIN_SPEED, 1f to SpeedCurve.MAX_SPEED))
        assertTrue(v in SpeedCurve.MIN_SPEED..SpeedCurve.MAX_SPEED)
    }

    // ---- the readouts ----

    @Test
    fun `durationSummary reports a lengthening clip`() {
        val s = durationSummary(10_000L, 12_500L)
        assertTrue("expected a percentage in '$s'", s.contains("25"))
        assertTrue("expected 'longer' wording in '$s'", s.contains("طولانی"))
    }

    @Test
    fun `durationSummary reports a shortening clip`() {
        val s = durationSummary(10_000L, 8_000L)
        assertTrue("expected 'shorter' wording in '$s'", s.contains("کوتاه"))
    }

    @Test
    fun `durationSummary is neutral when the length is unchanged`() {
        val s = durationSummary(10_000L, 10_000L)
        assertTrue("expected a neutral message in '$s'", s.contains("تغییر نمی"))
    }

    @Test
    fun `durationSummary does not divide by a zero source`() {
        assertEquals("طول منبع نامشخص است.", durationSummary(0L, 5_000L))
    }

    @Test
    fun `previewRateFor is the reciprocal of the duration ratio`() {
        assertEquals(2f, previewRateFor(10_000L, 5_000L, SpeedCurve.ofPreset(
            com.aicreatorstudio.app.model.SpeedCurvePreset.HERO)), 1e-4f)
    }

    @Test
    fun `previewRateFor is one for an inactive curve`() {
        assertEquals(1f, previewRateFor(10_000L, 10_000L, SpeedCurve.Flat), 0f)
    }

    @Test
    fun `previewRateFor never divides by a zero output`() {
        assertEquals(1f, previewRateFor(10_000L, 0L, SpeedCurve.ofPreset(
            com.aicreatorstudio.app.model.SpeedCurvePreset.HERO)), 0f)
    }

    @Test
    fun `formatDuration renders minutes seconds and tenths`() {
        assertEquals("0:00.0", formatDuration(0L))
        assertEquals("0:01.5", formatDuration(1_500L))
        assertEquals("1:05.0", formatDuration(65_000L))
    }

    @Test
    fun `formatDuration clamps a negative value instead of printing one`() {
        assertEquals("0:00.0", formatDuration(-500L))
    }

    @Test
    fun `formatRate trims a trailing zero`() {
        assertEquals("2", formatRate(2f))
        assertEquals("1", formatRate(1f))
        assertEquals("0.5", formatRate(0.5f))
    }

    @Test
    fun `formatRate survives a non-finite rate`() {
        assertEquals("1", formatRate(Float.NaN))
        assertEquals("1", formatRate(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `the knot cap is large enough for the presets`() {
        // Every shipped preset must be loadable into the editor without
        // immediately hitting the cap, or selecting it would look broken.
        listOf(
            com.aicreatorstudio.app.model.SpeedCurvePreset.HERO,
            com.aicreatorstudio.app.model.SpeedCurvePreset.BULLET,
            com.aicreatorstudio.app.model.SpeedCurvePreset.MONTAGE,
            com.aicreatorstudio.app.model.SpeedCurvePreset.FLASH_IN,
            com.aicreatorstudio.app.model.SpeedCurvePreset.FLASH_OUT
        ).forEach { preset ->
            assertTrue(
                "$preset has ${preset.points!!.size} knots but the cap is $MAX_KNOTS",
                preset.points!!.size <= MAX_KNOTS
            )
        }
        assertTrue("the cap must leave room to edit", MAX_KNOTS >= 6)
    }

    @Test
    fun `the hit radius is big enough to be touchable`() {
        // A touch target under ~24dp is unreliable on a phone; the
        // value is in raw pixels, so the floor here is conservative.
        assertTrue("hit radius $HIT_RADIUS_PX is too small", HIT_RADIUS_PX >= 24f)
    }
}
