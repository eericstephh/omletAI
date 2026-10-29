package com.aicreatorstudio.app.ai.engines

import com.aicreatorstudio.app.ai.AiModelInstaller
import com.aicreatorstudio.app.ai.TestContext
import com.aicreatorstudio.app.model.AutoFrameSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * JVM tests for the Auto Frame math: crop-window geometry, the
 * 1-Euro smoothing filter, and the centralized pose model spec.
 * Detection itself needs a device; geometry must be exactly right
 * here (a wrong window = stretched or out-of-bounds crops).
 */
class AutoFrameMathTest {

    private fun newEngine(): SaliencyTrackerEngine {
        val tempDir = Files.createTempDirectory("autoframe-test").toFile()
        val context = TestContext(tempDir)
        return SaliencyTrackerEngine(context, AiModelInstaller(context))
    }

    @Test
    fun `landscape to portrait keeps full height and clamps center`() {
        val engine = newEngine()
        // 1920x1080 -> 9:16: window must be 607x1080 (607.5 -> 607).
        val win = engine.cropWindowFor(1920, 1080, 9f, 16f, Pair(0.5f, 0.5f))
        assertEquals(1080, win.height)
        assertEquals((1080 * 9f / 16f).toInt(), win.width)
        assertTrue(win.left >= 0 && win.right <= 1920)
        // Extreme left request clamps inside the frame (no black bars).
        val clamped = engine.cropWindowFor(1920, 1080, 9f, 16f, Pair(0f, 0.5f))
        assertEquals(0, clamped.left)
        assertEquals(win.width, clamped.width)
    }

    @Test
    fun `portrait to landscape keeps full width`() {
        val engine = newEngine()
        // 1080x1920 -> 16:9: window must be 1080x607.
        val win = engine.cropWindowFor(1080, 1920, 16f, 9f, Pair(0.5f, 0.2f))
        assertEquals(1080, win.width)
        assertEquals((1080 * 9f / 16f).toInt(), win.height)
        assertTrue(win.top >= 0 && win.bottom <= 1920)
    }

    @Test
    fun `square crops are centered and bounded`() {
        val engine = newEngine()
        val win = engine.cropWindowFor(1920, 1080, 1f, 1f, Pair(0.9f, 0.1f))
        assertEquals(win.width, win.height)
        assertEquals(1080, win.width)
        assertTrue(win.left >= 0 && win.right <= 1920)
        assertTrue(win.top >= 0 && win.bottom <= 1080)
    }

    @Test
    fun `four-to-five portrait crop is bounded`() {
        val engine = newEngine()
        val win = engine.cropWindowFor(1920, 1080, 4f, 5f, Pair(0.5f, 0.5f))
        assertEquals(1080, win.height)
        assertEquals((1080 * 4f / 5f).toInt(), win.width)
        assertTrue(win.left >= 0 && win.right <= 1920)
    }

    @Test
    fun `same-aspect window is the full frame`() {
        val engine = newEngine()
        val win = engine.cropWindowFor(1280, 720, 16f, 9f, Pair(0.5f, 0.5f))
        assertEquals(0, win.left)
        assertEquals(0, win.top)
        assertEquals(1280, win.right)
        assertEquals(720, win.bottom)
    }

    @Test
    fun `one-euro filter smooths a step without overshoot`() {
        val filter = com.aicreatorstudio.app.model.util.OneEuroFilter(0.8f)
        val samples = List(20) { if (it < 10) Pair(0.2f, 0.5f) else Pair(0.8f, 0.5f) }
        val out = filter.smooth(samples)
        assertEquals(20, out.size)
        // First output equals first input (filter state init).
        assertEquals(0.2f, out[0].first, 1e-6f)
        // No overshoot past the step target, no undershoot below start.
        out.forEach { (x, y) ->
            assertTrue("x=$x out of [0.2, 0.8]", x in 0.2f..0.8f)
            assertEquals(0.5f, y, 1e-6f)
        }
        // Heavy smoothing lags the step (no violent jump).
        assertTrue("jumped too fast: ${out[10].first}", out[10].first < 0.8f)
    }

    @Test
    fun `one-euro filter is identity on constant input`() {
        val filter = com.aicreatorstudio.app.model.util.OneEuroFilter(0.2f)
        val samples = List(10) { Pair(0.4f, 0.6f) }
        val out = filter.smooth(samples)
        out.forEach {
            assertEquals(0.4f, it.first, 1e-4f)
            assertEquals(0.6f, it.second, 1e-4f)
        }
    }

    @Test
    fun `pose spec is the centralized landmarker entry`() {
        assertEquals("pose_landmarker_lite", SaliencyTrackerEngine.POSE_SPEC.id)
        assertEquals(
            "pose_landmarker_lite.task",
            SaliencyTrackerEngine.POSE_SPEC.fileName
        )
    }

    @Test
    fun `default algorithm tag is the pose backend`() {
        assertEquals(
            AutoFrameSettings.ALGORITHM_POSE_LANDMARKER_V1,
            AutoFrameSettings.Default.algorithm
        )
    }

    @Test
    fun `engine reports model missing on an empty install dir`() {
        val engine = newEngine()
        assertTrue(engine.modelStatusText.contains("not installed", ignoreCase = true))
    }
}
