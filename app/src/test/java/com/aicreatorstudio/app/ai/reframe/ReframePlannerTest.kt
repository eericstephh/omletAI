package com.aicreatorstudio.app.ai.reframe

import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 5 — JVM tests for Smart Reframe planning math.
 *
 * Covers: crop-window aspect/bounds/margins, simplification
 * (redundant merge, tolerance, max cap, monotonicity, endpoints,
 * determinism), validation, source-key staleness, and stats.
 * Detector inference itself stays on-device (existing engine).
 */
class ReframePlannerTest {

    private fun key(t: Long, x: Float, y: Float = 0.5f) =
        CropKeyframe(timeMs = t, centerX = x, centerY = y, zoom = 1f)

    private fun clip(id: String = "c1") = VideoClip(
        id = id, uri = "file:///$id.mp4", fileName = "$id.mp4", durationMs = 60_000L,
        width = 1920, height = 1080, fileSizeBytes = 1_000L, importedAtEpochMillis = 0L
    )

    // ---- crop math ----

    @Test
    fun `landscape to portrait keeps full height`() {
        val win = ReframePlanner.cropWindow(1920, 1080, 9f, 16f, 0.5f, 0.5f, margin = 0f)
        assertEquals(1080, win.height)
        assertEquals((1080 * 9f / 16f).toInt(), win.width)
        assertTrue(validateWindow(win, 1920, 1080))
    }

    @Test
    fun `portrait to landscape keeps full width`() {
        val win = ReframePlanner.cropWindow(1080, 1920, 16f, 9f, 0.5f, 0.5f, margin = 0f)
        assertEquals(1080, win.width)
        assertEquals((1080 * 9f / 16f).toInt(), win.height)
        assertTrue(validateWindow(win, 1080, 1920))
    }

    @Test
    fun `same aspect returns full frame`() {
        val win = ReframePlanner.cropWindow(1920, 1080, 16f, 9f, 0.5f, 0.5f, margin = 0f)
        assertEquals(0, win.left)
        assertEquals(0, win.top)
        assertEquals(1920, win.width)
        assertEquals(1080, win.height)
    }

    @Test
    fun `edge subject clamps window inside source`() {
        // Subject glued to the left edge: window must not go negative.
        val left = ReframePlanner.cropWindow(1920, 1080, 9f, 16f, 0f, 0.5f, margin = 0f)
        assertEquals(0, left.left)
        assertTrue(validateWindow(left, 1920, 1080))
        // Subject glued to the right edge: window must not overflow.
        val right = ReframePlanner.cropWindow(1920, 1080, 9f, 16f, 1f, 0.5f, margin = 0f)
        assertEquals(1920 - right.width, right.left)
        assertTrue(validateWindow(right, 1920, 1080))
        // Top / bottom edges on a portrait source.
        val top = ReframePlanner.cropWindow(1080, 1920, 16f, 9f, 0.5f, 0f, margin = 0f)
        assertEquals(0, top.top)
        assertTrue(validateWindow(top, 1080, 1920))
    }

    @Test
    fun `margin shrinks window and never escapes source`() {
        val noMargin = ReframePlanner.cropWindow(1920, 1080, 9f, 16f, 0.5f, 0.5f, margin = 0f)
        val margin = ReframePlanner.cropWindow(1920, 1080, 9f, 16f, 0.5f, 0.5f, margin = 0.2f)
        assertTrue(margin.width < noMargin.width)
        assertTrue(margin.height < noMargin.height)
        assertTrue(validateWindow(margin, 1920, 1080))
    }

    @Test
    fun `out of range centers clamp`() {
        val win = ReframePlanner.cropWindow(1920, 1080, 1f, 1f, -2f, 5f, margin = 0f)
        assertTrue(validateWindow(win, 1920, 1080))
    }

    // ---- simplification ----

    @Test
    fun `static subject collapses to endpoints`() {
        val keys = (0..100).map { i -> key(i * 200L, 0.5f) }
        val out = ReframePlanner.simplify(keys)
        assertEquals(2, out.size)
        assertEquals(0L, out.first().timeMs)
        assertEquals(20_000L, out.last().timeMs)
    }

    @Test
    fun `real movement is preserved`() {
        // Pan 0.2 → 0.8 in visible steps.
        val keys = (0..10).map { i -> key(i * 200L, 0.2f + i * 0.06f) }
        val out = ReframePlanner.simplify(keys)
        assertTrue(out.size > 2)
        assertEquals(0L, out.first().timeMs)
        assertEquals(2000L, out.last().timeMs)
    }

    @Test
    fun `timestamps stay monotonic with endpoints pinned`() {
        val keys = (0..50).map { i ->
            key(i * 200L, (0.5f + 0.05f * (i % 3)).coerceIn(0f, 1f))
        }
        val out = ReframePlanner.simplify(keys, tolerance = 0.001f, maxKeys = 10)
        assertTrue(out.size <= 10)
        assertEquals(0L, out.first().timeMs)
        assertEquals(10_000L, out.last().timeMs)
        for (i in 1 until out.size) assertTrue(out[i].timeMs >= out[i - 1].timeMs)
        assertTrue(validateKeys(out, 10_000L))
    }

    @Test
    fun `simplify is deterministic and handles tiny inputs`() {
        val keys = (0..20).map { i -> key(i * 200L, 0.3f + (i % 5) * 0.02f) }
        assertEquals(ReframePlanner.simplify(keys), ReframePlanner.simplify(keys))
        assertTrue(ReframePlanner.simplify(emptyList()).isEmpty())
        assertEquals(1, ReframePlanner.simplify(listOf(key(0L, 0.5f))).size)
    }

    // ---- validation & keys ----

    @Test
    fun `validateKeys rejects bad paths`() {
        assertTrue(validateKeys(listOf(key(0L, 0.5f), key(200L, 0.6f)), 1000L))
        assertFalse(validateKeys(listOf(key(0L, 0.5f), key(2000L, 0.6f)), 1000L)) // beyond duration
        assertFalse(validateKeys(listOf(key(-10L, 0.5f)), 1000L)) // negative time
        assertFalse(validateKeys(listOf(key(400L, 0.5f), key(200L, 0.6f)), 1000L)) // non-monotonic
        assertFalse(validateKeys(listOf(key(0L, 1.5f)), 1000L)) // outside frame
    }

    // ---- source key ----

    @Test
    fun `source key covers geometry and aspect`() {
        val base = clip()
        val key = reframeSourceKey(base, 9f, 16f)
        assertEquals(key, reframeSourceKey(base.copy(), 9f, 16f))
        assertFalse(key == reframeSourceKey(base.copy(trimStartMs = 500L), 9f, 16f))
        assertFalse(key == reframeSourceKey(base.copy(uri = "file:///other.mp4"), 9f, 16f))
        assertFalse(key == reframeSourceKey(base.copy(width = 1280, height = 720), 9f, 16f))
        assertFalse(key == reframeSourceKey(base, 1f, 1f)) // aspect flip invalidates
        assertFalse(key == reframeSourceKey(base.copy(timelineStartMs = 1000L), 9f, 16f))
    }

    // ---- stats ----

    @Test
    fun `stats summarize found ratio and counts`() {
        val raw = listOf(
            CropKeyframe(0L, 0.5f, 0.5f, 1f, confidence = 0.9f, isFound = true),
            CropKeyframe(200L, 0.5f, 0.5f, 1f, confidence = 0.9f, isFound = true),
            CropKeyframe(400L, 0.5f, 0.5f, 1f, confidence = 0.1f, isFound = false),
            CropKeyframe(600L, 0.5f, 0.5f, 1f, confidence = 0.1f, isFound = false)
        )
        val stats = ReframePlanner.stats(raw, raw.take(2), 60_000L, "test")
        assertEquals(4, stats.rawKeyframes)
        assertEquals(2, stats.keyframes)
        assertEquals(50, stats.subjectFoundPct)
        assertEquals(60_000L, stats.durationMs)
        val empty = ReframePlanner.stats(emptyList(), emptyList(), 0L, "none")
        assertEquals(0, empty.subjectFoundPct)
    }
}
