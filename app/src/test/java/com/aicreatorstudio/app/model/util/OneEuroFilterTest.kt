package com.aicreatorstudio.app.model.util

import com.aicreatorstudio.app.model.CropKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the extracted [OneEuroFilter] and
 * [MotionTrajectorySmoothing].
 *
 * These are the two highest-value pure modules in Motion Tracking: the
 * filter decides whether the overlay looks locked or shaky, and the
 * smoothing module decides whether the box holds its aspect and whether
 * a re-acquisition is hidden. Both were previously un-testable (the
 * filter was a nested class inside a Hilt `@Singleton`), which is why
 * the extraction was worth doing even though it changed no behaviour.
 */
class OneEuroFilterTest {

    @Test
    fun `is identity on the first sample`() {
        // Filter state initialises to the first sample, so there is no
        // ramp-in artefact on frame 0.
        val out = OneEuroFilter(0.8f).smooth(listOf(0.2f to 0.7f))
        assertEquals(0.2f, out[0].first, 1e-6f)
        assertEquals(0.7f, out[0].second, 1e-6f)
    }

    @Test
    fun `is identity on constant input`() {
        val out = OneEuroFilter(0.2f).smooth(List(10) { 0.4f to 0.6f })
        out.forEach {
            assertEquals(0.4f, it.first, 1e-4f)
            assertEquals(0.6f, it.second, 1e-4f)
        }
    }

    @Test
    fun `preserves the sample count`() {
        val out = OneEuroFilter(0.5f).smooth(List(7) { 0.5f to 0.5f })
        assertEquals(7, out.size)
    }

    @Test
    fun `heavy smoothing lags a step and never overshoots`() {
        // The defining property that made this filter the right choice
        // over EMA: it lags a step (no violent jump) but stays inside
        // the input envelope.
        val samples = List(20) { if (it < 10) 0.2f to 0.5f else 0.8f to 0.5f }
        val out = OneEuroFilter(0.8f).smooth(samples)
        assertTrue("jumped too fast: ${out[10].first}", out[10].first < 0.8f)
        out.forEach { assertTrue("overshoot: ${it.first}", it.first in 0.2f..0.8f) }
    }

    @Test
    fun `adapts to a fast move rather than lagging behind it`() {
        // The adaptive-cutoff property: a sustained fast motion must
        // track closely (unlike a fixed-cutoff EMA, which would leave
        // the overlay visibly behind its subject).
        val fast = List(30) { (0.05f * it).coerceAtMost(1f) to 0.5f }
        val out = OneEuroFilter(0.5f).smooth(fast)
        assertTrue(
            "lagged a sustained move: ${out.last().first}",
            out.last().first > 0.9f
        )
    }

    @Test
    fun `smoothScalar preserves count and tracks a constant`() {
        val out = OneEuroFilter(0.4f).smoothScalar(List(5) { 0.25f })
        assertEquals(5, out.size)
        out.forEach { assertEquals(0.25f, it, 1e-4f) }
    }
}

class MotionTrajectorySmoothingTest {

    private val s = MotionTrajectorySmoothing

    /** A jittery straight-line trajectory at 30 fps. */
    private fun jitteryTrace(n: Int): List<CropKeyframe> = List(n) { i ->
        val base = 0.2f + 0.02f * i
        // +-0.004 of alternating jitter.
        val wobble = if (i % 2 == 0) 0.004f else -0.004f
        CropKeyframe(
            timeMs = i * 33L,
            centerX = base + wobble,
            centerY = 0.5f,
            width = 0.10f,
            height = 0.10f
        )
    }

    @Test
    fun `returns the input unchanged when too short to filter`() {
        // Fewer than three samples cannot exhibit jitter and the
        // derivative estimate is meaningless.
        val two = jitteryTrace(2)
        assertEquals(two, s.smooth(two, 0.5f, 0.7f))
    }

    @Test
    fun `reduces centre jitter`() {
        val raw = jitteryTrace(40)
        val smoothed = s.smooth(raw, positionSmoothing = 0.9f, scaleSmoothing = 0.9f)
        assertEquals(raw.size, smoothed.size)

        fun jitter(keys: List<CropKeyframe>): Float {
            var sum = 0f
            for (i in 1 until keys.size) {
                sum += kotlin.math.abs(keys[i].centerX - keys[i - 1].centerX - 0.02f)
            }
            return sum
        }
        assertTrue(
            "smoothing did not reduce jitter",
            jitter(smoothed) < jitter(raw)
        )
    }

    @Test
    fun `preserves the sample count and isFound flags`() {
        val raw = List(10) { i ->
            CropKeyframe(i * 33L, 0.2f, 0.5f, width = 0.1f, height = 0.1f, isFound = i != 5)
        }
        val smoothed = s.smooth(raw, 0.5f, 0.7f)
        assertEquals(10, smoothed.size)
        assertEquals(false, smoothed[5].isFound)
        assertEquals(true, smoothed[6].isFound)
    }

    @Test
    fun `holds the aspect ratio of the box`() {
        // Filtering width and height independently lets the aspect
        // drift, so the user's box would slowly stretch.
        val raw = List(20) { i ->
            CropKeyframe(i * 33L, 0.5f, 0.5f, width = 0.16f, height = 0.08f)
        }
        val smoothed = s.smooth(raw, 0.3f, 0.3f)
        smoothed.forEach { k ->
            assertEquals(2f, k.width!! / k.height!!, 0.05f)
        }
    }

    @Test
    fun `leaves extent null for a trajectory without one`() {
        // Auto Frame entries carry no extent; the smoother must not
        // fabricate one, or the two producers would interfere.
        val raw = List(10) { i -> CropKeyframe(i * 33L, 0.4f, 0.5f) }
        val smoothed = s.smooth(raw, 0.5f, 0.5f)
        smoothed.forEach {
            assertEquals(null, it.width)
            assertEquals(null, it.height)
        }
    }

    @Test
    fun `decimate lands on the requested spacing and includes the last sample`() {
        val smoothed = List(100) { i ->
            CropKeyframe(i * 33L, 0.2f + 0.005f * i, 0.5f)
        }
        val out = s.decimate(smoothed, intervalMs = 100L, durationMs = 3267L)
        assertTrue("expected several keyframes, got ${out.size}", out.size >= 30)
        // Time-ordered and strictly increasing.
        for (i in 1 until out.size) assertTrue(out[i].timeMs >= out[i - 1].timeMs)
        // A trajectory stopping short of the clip end makes the overlay
        // freeze then jump.
        assertTrue(out.last().timeMs >= smoothed.last().timeMs - 1L)
    }

    @Test
    fun `decimate on an empty trajectory is a no-op`() {
        assertEquals(emptyList<CropKeyframe>(), s.decimate(emptyList(), 100L, 1000L))
    }

    @Test
    fun `reanchorAlpha ramps down at a discontinuity`() {
        // A 0.2 -> 0.9 jump in 33 ms is a re-acquisition, not motion.
        val keys = listOf(
            CropKeyframe(0L, 0.2f, 0.5f),
            CropKeyframe(33L, 0.9f, 0.5f),
            CropKeyframe(66L, 0.9f, 0.5f)
        )
        // At the jump itself the overlay must be invisible.
        val atJump = s.reanchorAlpha(keys, 33L, rampMs = 100L)
        assertTrue("expected ~0 at the jump, got $atJump", atJump < 0.1f)
    }

    @Test
    fun `reanchorAlpha is 1 far from any discontinuity`() {
        val keys = listOf(
            CropKeyframe(0L, 0.2f, 0.5f),
            CropKeyframe(1000L, 0.21f, 0.5f)
        )
        assertEquals(1f, s.reanchorAlpha(keys, 500L, rampMs = 100L), 1e-6f)
    }

    @Test
    fun `reanchorAlpha is 1 for a smooth trajectory at any time`() {
        val keys = List(10) { i -> CropKeyframe(i * 33L, 0.2f + 0.001f * i, 0.5f) }
        keys.indices.forEach { i ->
            assertEquals(1f, s.reanchorAlpha(keys, keys[i].timeMs, rampMs = 100L), 1e-6f)
        }
    }
}
