package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.CropKeyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the AI export helpers ([AiExportHooks] pure
 * geometry): keyframe interpolation, reframe sizing, and crop
 * windows. Frame compositing itself needs bitmaps + codecs, so it
 * is covered on-device; the geometry must be exactly right here.
 */
class AiExportGeometryTest {

    private fun hooks(): AiExportHooks {
        // AiExportHooks only needs a Context for disk paths; the
        // geometry helpers below never touch it. A null-free stub
        // is impossible without Robolectric, so these tests use a
        // real instance over a temp dir via the test Context in the
        // ai package... which this package cannot see (internal).
        // Instead we exercise identical math through tiny local
        // mirrors? No — that would test copies, not code.
        //
        // Resolution: AiExportHooks' constructor takes a Context.
        // android.content.Context is an abstract class; subclassing
        // it directly in JVM tests requires stubbing dozens of
        // methods. ContextWrapper(null) (like the ai package's
        // TestContext) gives working defaults under
        // unitTests.isReturnDefaultValues. getCacheDir() returns null
        // by default — but geometry never calls it.
        return AiExportHooks(TestExportContext())
    }

    @Test
    fun `keyframe interpolation lerps between brackets`() {
        val hooks = hooks()
        val keys = listOf(
            CropKeyframe(0, 0.2f, 0.5f),
            CropKeyframe(1000, 0.8f, 0.5f)
        )
        val mid = hooks.interpolateKeys(keys, 500)
        assertEquals(0.5f, mid.first, 1e-5f)
        assertEquals(0.5f, mid.second, 1e-5f)
        // Clamp outside the range.
        assertEquals(0.2f, hooks.interpolateKeys(keys, -100).first, 1e-5f)
        assertEquals(0.8f, hooks.interpolateKeys(keys, 5000).first, 1e-5f)
    }

    @Test
    fun `empty keyframes center`() {
        val hooks = hooks()
        assertEquals(Pair(0.5f, 0.5f), hooks.interpolateKeys(emptyList(), 123))
    }

    @Test
    fun `reframe size landscape to portrait uses full height`() {
        val hooks = hooks()
        val (w, h) = hooks.reframeOutputSize(1920, 1080, 9f, 16f)
        assertEquals(1080, h)
        assertEquals((1080 * 9f / 16f).toInt() and 1.inv(), w)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun `reframe size portrait to landscape uses full width`() {
        val hooks = hooks()
        val (w, h) = hooks.reframeOutputSize(1080, 1920, 16f, 9f)
        assertEquals(1080, w)
        assertEquals((1080 * 9f / 16f).toInt() and 1.inv(), h)
    }

    @Test
    fun `reframe size to square is bounded by the short side`() {
        val hooks = hooks()
        val (w, h) = hooks.reframeOutputSize(1920, 1080, 1f, 1f)
        assertEquals(w, h)
        assertEquals(1080, h)
    }

    @Test
    fun `crop window never leaves the frame`() {
        val hooks = hooks()
        val cases = listOf(
            Triple(1920, 1080, Pair(9f, 16f)),
            Triple(1920, 1080, Pair(1f, 1f)),
            Triple(1080, 1920, Pair(16f, 9f)),
            Triple(1080, 1920, Pair(4f, 5f))
        )
        val centers = listOf(Pair(0f, 0f), Pair(0.5f, 0.5f), Pair(1f, 1f), Pair(0.9f, 0.1f))
        for ((sw, sh, aspect) in cases) {
            for (c in centers) {
                val win = hooks.cropWindowFor(sw, sh, aspect.first, aspect.second, c)
                assertTrue("left ${win.left}", win.left >= 0)
                assertTrue("top ${win.top}", win.top >= 0)
                assertTrue("right ${win.right} > $sw", win.right <= sw)
                assertTrue("bottom ${win.bottom} > $sh", win.bottom <= sh)
                assertTrue("empty window", win.width > 0 && win.height > 0)
            }
        }
    }

    /** Minimal Context: only construction is needed for geometry tests. */
    private class TestExportContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
    }
}
