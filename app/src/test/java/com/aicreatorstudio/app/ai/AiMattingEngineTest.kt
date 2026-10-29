package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.ai.engines.MediaPipeMattingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Tests for [MediaPipeMattingEngine] on the centralized installer
 * path ([AiModelInstaller] + [AiModelCatalog.SELFIE_MULTICLASS]).
 *
 * The engine loads its MediaPipe segmenter lazily, so JVM tests
 * cover the *contract* (ids, status text, spec identity,
 * temporal-smoothing bounds) with a real [AiModelInstaller] over an
 * empty temp dir — which deterministically reports "not installed"
 * without touching the network. Real inference is covered by the
 * on-device instrumentation test.
 */
class AiMattingEngineTest {

    @Test
    fun `id is matting and displayName is Background Remove`() {
        val engine = newEngine()
        assertEquals("matting", engine.id)
        assertEquals("Background Remove", engine.displayName)
    }

    @Test
    fun `isAvailable is false when the model is not installed`() {
        val engine = newEngine()
        assertFalse(engine.isAvailable)
    }

    @Test
    fun `modelStatusText mentions not installed when the model is missing`() {
        val engine = newEngine()
        val text = engine.modelStatusText
        assertTrue("text was: $text", text.contains("not installed", ignoreCase = true))
    }

    @Test
    fun `matting spec is the centralized selfie multiclass entry`() {
        val spec = MediaPipeMattingEngine.MATTING_SPEC
        assertEquals(AiModelCatalog.SELFIE_MULTICLASS, spec)
        assertEquals("selfie_multiclass", spec.id)
        assertEquals("selfie_multiclass_256x256.tflite", spec.fileName)
        assertEquals("selfie_multiclass", spec.dirName)
    }

    @Test
    fun `matting spec requires a sane minimum size`() {
        // The real model is ~16 MB; the floor must reject truncated
        // downloads without rejecting the real artifact.
        val minSize = MediaPipeMattingEngine.MATTING_SPEC.minSizeBytes
        assertTrue("min size was $minSize", minSize in 1_000_000L..50_000_000L)
    }

    @Test
    fun `temporal filter scene-cut threshold is usable`() {
        // The fixed TEMPORAL_ALPHA = 0.65 was replaced on 2026-10-29 by
        // MattingTemporalFilter (median of three + scene-cut reset).
        // The cut threshold must sit strictly inside (0, 1]: at 0 every
        // frame would be a "cut" and nothing would ever be stabilised;
        // above 1 the reset is unreachable and cut ghosting is guaranteed.
        val t = MattingTemporalFilter.DEFAULT_SCENE_CUT_DIFF
        assertTrue("threshold was $t", t > 0f && t <= 1f)
    }

    @Test
    fun `frame cap keeps the sidecar bounded`() {
        val cap = MediaPipeMattingEngine.MAX_FRAMES
        assertTrue("cap was $cap", cap in 60..3600)
    }

    private fun newEngine(): MediaPipeMattingEngine {
        val tempDir = Files.createTempDirectory("matting-engine-test").toFile()
        val context = TestContext(tempDir)
        val installer = AiModelInstaller(context)
        return MediaPipeMattingEngine(context, installer)
    }
}
