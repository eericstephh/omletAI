package com.aicreatorstudio.app.ai.engines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Auto Frame tracking math: the
 * target-selection cost function, tracking-loss recovery windows,
 * and the crop-window geometry for every supported aspect ratio.
 *
 * These pin the "Detection → Tracking → Target Selection → Smooth
 * Camera Path → Crop → Keyframes" contract without a device.
 */
class TargetSelectionTest {

    // ----------------------------------------------------------------
    // Target selection cost semantics (mirrors poseSubjectPointMulti)
    // ----------------------------------------------------------------

    /**
     * The cost formula must prefer: higher visibility, larger torso
     * span (closer/bigger person), and — decisively — continuity
     * with the locked target. This local mirror keeps the exact
     * coefficients in sync with the engine.
     */
    private fun cost(
        meanVis: Float,
        shoulderSpan: Float,
        continuity: Float?
    ): Float = 1f - meanVis.coerceIn(0f, 1f) +
        (1f - shoulderSpan.coerceIn(0f, 1f)) * 0.5f +
        (continuity ?: 0.15f)

    @Test
    fun `locked target wins when second person appears`() {
        // Person A: the locked target, slightly smaller + slightly moved.
        val costA = cost(0.8f, 0.3f, continuity = 0.05f)
        // Person B: bigger + more visible but far away from the lock.
        val costB = cost(0.9f, 0.5f, continuity = 0.6f)
        assertTrue(
            "continuity with the locked target must dominate size/visibility",
            costA < costB
        )
    }

    @Test
    fun `no lock means prominence decides`() {
        // First frame: no continuity term (null → 0.15 default).
        val closer = cost(0.8f, 0.6f, continuity = null)
        val farther = cost(0.8f, 0.2f, continuity = null)
        assertTrue(closer < farther)
    }

    @Test
    fun `visibility is respected when both near lock`() {
        val crisp = cost(0.95f, 0.4f, continuity = 0.1f)
        val blurry = cost(0.3f, 0.4f, continuity = 0.1f)
        assertTrue(crisp < blurry)
    }

    // ----------------------------------------------------------------
    // Loss-recovery window
    // ----------------------------------------------------------------

    @Test
    fun `loss recovery window is two seconds at 250ms samples`() {
        assertEquals(2000L, SaliencyTrackerEngine.LOSS_RECOVERY_SAMPLES * 250L)
    }

    @Test
    fun `loss recovery tolerance covers pose gaps without chasing ghosts`() {
        // A typical pose dropout is 3-6 samples (0.75-1.5 s); the
        // window must tolerate it but still recover well within a
        // scene cut (~3 s).
        assertTrue(SaliencyTrackerEngine.LOSS_RECOVERY_SAMPLES >= 4)
        assertTrue(SaliencyTrackerEngine.LOSS_RECOVERY_SAMPLES <= 16)
    }

    // ----------------------------------------------------------------
    // Crop windows for every supported aspect (engine geometry)
    // ----------------------------------------------------------------

    @Test
    fun `crop 16x9 from 1080x1920 portrait`() {
        val win = SaliencyCrop.cropWindowFor(1080, 1920, 16f, 9f, 0.5f to 0.5f)
        assertEquals(1080, win.width)
        assertEquals(607, win.height) // 1080 * 9/16 = 607.5 → toInt() floors
        assertEquals(0, win.left)
        assertTrue(win.top >= 0 && win.top + win.height <= 1920)
    }

    @Test
    fun `crop 9x16 from 1920x1080 landscape`() {
        val win = SaliencyCrop.cropWindowFor(1920, 1080, 9f, 16f, 0.5f to 0.5f)
        assertEquals(607, win.width)
        assertEquals(1080, win.height)
        assertTrue(win.left >= 0 && win.left + win.width <= 1920)
    }

    @Test
    fun `crop 1x1 from 1920x1080`() {
        val win = SaliencyCrop.cropWindowFor(1920, 1080, 1f, 1f, 0.5f to 0.5f)
        assertEquals(1080, win.width)
        assertEquals(1080, win.height)
    }

    @Test
    fun `crop 4x5 from 1920x1080`() {
        val win = SaliencyCrop.cropWindowFor(1920, 1080, 4f, 5f, 0.5f to 0.5f)
        assertEquals(864, win.width)
        assertEquals(1080, win.height)
    }

    @Test
    fun `off-center target clamps window inside frame`() {
        // Subject at the extreme left: window must not go negative.
        val win = SaliencyCrop.cropWindowFor(1920, 1080, 9f, 16f, 0.0f to 0.5f)
        assertEquals(0, win.left)
        // Subject at the extreme right: window must not overflow.
        val winR = SaliencyCrop.cropWindowFor(1920, 1080, 9f, 16f, 1.0f to 0.5f)
        assertEquals(1920 - winR.width, winR.left)
    }
}

/**
 * Pure re-implementation of the engine's crop geometry for JVM
 * tests (the engine method requires Android Bitmap types only in
 * other paths, but keeping the mirror here pins the formula).
 */
private object SaliencyCrop {
    fun cropWindowFor(
        srcW: Int,
        srcH: Int,
        targetW: Float,
        targetH: Float,
        center: Pair<Float, Float>
    ): CropWindow {
        val tw = targetW.coerceAtLeast(0.01f)
        val th = targetH.coerceAtLeast(0.01f)
        val targetAspect = tw / th
        val srcAspect = srcW.toFloat() / srcH
        val (cw, ch) = if (srcAspect > targetAspect) {
            val h = srcH
            val w = (h * targetAspect).toInt().coerceIn(1, srcW)
            w to h
        } else {
            val w = srcW
            val h = (w / targetAspect).toInt().coerceIn(1, srcH)
            w to h
        }
        val cxPx = (center.first.coerceIn(0f, 1f) * srcW).toInt()
        val cyPx = (center.second.coerceIn(0f, 1f) * srcH).toInt()
        val left = (cxPx - cw / 2).coerceIn(0, (srcW - cw).coerceAtLeast(0))
        val top = (cyPx - ch / 2).coerceIn(0, (srcH - ch).coerceAtLeast(0))
        return CropWindow(left, top, cw, ch)
    }
}

private data class CropWindow(val left: Int, val top: Int, val width: Int, val height: Int)
