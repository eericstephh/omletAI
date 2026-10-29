package com.aicreatorstudio.app.ai.engines

import com.aicreatorstudio.app.ai.AudioExtractor
import com.aicreatorstudio.app.ai.TestContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * JVM tests for the Voice Clean gain math
 * ([RnnoiseVoiceCleanEngine.blendedFrameGains]).
 *
 * The neural inference itself needs the NDK library + a device, so
 * these tests pin the *decision* layer: given fixed original /
 * denoised / VAD streams, the gains must suppress noise, preserve
 * speech, never exceed 1 (no clipping), never NaN on silence, and
 * scale monotonically with strength.
 */
class VoiceCleanGainTest {

    private fun newEngine(): RnnoiseVoiceCleanEngine {
        val tempDir = Files.createTempDirectory("voiceclean-test").toFile()
        val context = TestContext(tempDir)
        val extractor = AudioExtractor(context)
        // 2026-10-20 — the spectral gate is a pure DSP helper now (it
        // no longer carries a duplicate `AiToolEngine` implementation
        // with a conflicting `.pcm` cache contract).
        val spectral = SpectralGateVoiceClean()
        return RnnoiseVoiceCleanEngine(context, extractor, spectral)
    }

    private fun noiseFrames(frames: Int, frameSamples: Int, amp: Float): FloatArray {
        // Deterministic pseudo-noise (no Random: stable across runs).
        val out = FloatArray(frames * frameSamples)
        var s = 12345
        for (i in out.indices) {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            out[i] = ((s / 1073741824f) - 1f) * amp
        }
        return out
    }

    @Test
    fun `gains stay within floor and unity`() {
        val engine = newEngine()
        val orig = noiseFrames(20, 480, 0.5f)
        val den = FloatArray(orig.size) { orig[it] * 0.1f }
        val vad = FloatArray(20) { 0.1f }
        val gains = engine.blendedFrameGains(orig, den, vad, 480, 0.6f)
        assertEquals(20, gains.size)
        gains.forEachIndexed { i, g ->
            assertTrue("gain[$i]=$g not finite", g.isFinite())
            assertTrue(
                "gain[$i]=$g outside [${RnnoiseVoiceCleanEngine.MIN_GAIN}, 1]",
                g in RnnoiseVoiceCleanEngine.MIN_GAIN..1f
            )
        }
    }

    @Test
    fun `strong suppression on noise frames, gentle on speech frames`() {
        val engine = newEngine()
        val orig = noiseFrames(10, 480, 0.5f)
        val den = FloatArray(orig.size) { orig[it] * 0.05f }
        val noiseVad = FloatArray(10) { 0.0f }
        val speechVad = FloatArray(10) { 1.0f }
        val gNoise = engine.blendedFrameGains(orig, den, noiseVad, 480, 0.9f).average()
        val gSpeech = engine.blendedFrameGains(orig, den, speechVad, 480, 0.9f).average()
        assertTrue("noise gain $gNoise should be < speech gain $gSpeech", gNoise < gSpeech)
        assertTrue("speech gain $gSpeech should preserve most energy", gSpeech > 0.4)
    }

    @Test
    fun `digital silence maps to unity gain, never NaN`() {
        val engine = newEngine()
        val silent = FloatArray(5 * 480)
        val gains = engine.blendedFrameGains(silent, silent, FloatArray(5) { 0f }, 480, 0.9f)
        gains.forEach { g ->
            assertTrue("gain $g", g.isFinite())
            assertEquals(1f, g, 1e-6f)
        }
    }

    @Test
    fun `higher strength suppresses more on noise`() {
        val engine = newEngine()
        val orig = noiseFrames(10, 480, 0.5f)
        val den = FloatArray(orig.size) { orig[it] * 0.05f }
        val vad = FloatArray(10) { 0f }
        val low = engine.blendedFrameGains(orig, den, vad, 480, 0.2f).average()
        val high = engine.blendedFrameGains(orig, den, vad, 480, 1.0f).average()
        assertTrue("low=$low should exceed high=$high", low > high)
    }

    @Test
    fun `identical input and denoised yields near-unity gains`() {
        val engine = newEngine()
        val orig = noiseFrames(8, 480, 0.4f)
        val gains = engine.blendedFrameGains(orig, orig.copyOf(), FloatArray(8) { 0.5f }, 480, 0.6f)
        gains.forEach { g ->
            assertTrue("gain $g should be ~1", g > 0.9f)
        }
    }

    @Test
    fun `engine id and backend constants are stable`() {
        val engine = newEngine()
        assertEquals("voiceclean", engine.id)
        assertEquals("Voice Clean", engine.displayName)
        assertEquals("rnnoise-v1", RnnoiseVoiceCleanEngine.ALGORITHM_RNNOISE_V1)
        assertTrue(engine.isAvailable)
    }

    @Test
    fun `rms sanity of test noise`() {
        val n = noiseFrames(4, 480, 0.5f)
        val rms = sqrt(n.map { it * it }.average()).toFloat()
        assertTrue("rms $rms should be in a sane band", rms > 0.1f && rms < 0.5f)
    }
}
