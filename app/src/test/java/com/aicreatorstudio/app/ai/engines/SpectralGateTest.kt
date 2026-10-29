package com.aicreatorstudio.app.ai.engines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 2026-10-20 — guards the spectral-gate fallback backend.
 *
 * The fallback used to have three defects that made it behave
 * differently from the algorithm it documents:
 *
 *  1. **The sample rate was assumed, not declared.** The noise
 *     profile window is `NOISE_PROFILE_MS` long and was always
 *     computed with a hard-coded 16 kHz — but the engine fed the
 *     gate **48 kHz** audio, so the "500 ms" profile actually covered
 *     ~165 ms and each 512-sample frame was 10.6 ms instead of 32 ms.
 *  2. **The tail was dropped.** The frame loop broke as soon as a
 *     frame would run past the end of the buffer, so the last
 *     partial window was never written — the tail of every clip came
 *     out silent.
 *  3. **A clip shorter than one frame came out as pure silence and
 *     was still reported as a success.** The buffer stayed all-zero
 *     and the engine announced "پاک‌سازی صدا روی N ثانیه اعمال شد".
 *
 * These tests pin all three, plus the per-frame speech probability
 * that replaced the hard-coded 0.5 (a constant VAD disabled the
 * speech/noise blend entirely).
 */
class SpectralGateTest {

    private fun noise(n: Int, amp: Float, seed: Int = 12345): FloatArray {
        val out = FloatArray(n)
        var s = seed
        for (i in out.indices) {
            s = (s * 1103515245 + 12345) and 0x7fffffff
            out[i] = ((s / 1073741824f) - 1f) * amp
        }
        return out
    }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Float {
        if (to <= from) return 0f
        var acc = 0.0
        for (i in from until to) acc += a[i].toDouble() * a[i]
        return sqrt(acc / (to - from)).toFloat()
    }

    @Test
    fun `a clip shorter than one frame is not reduced to silence`() {
        val gate = SpectralGateVoiceClean()
        // 100 samples — far less than the 512-sample frame.
        val input = noise(100, 0.5f)
        val result = gate.spectralGate(input, strength = 0.6f, sampleRate = 16_000)
        assertEquals("the output must keep the input length", input.size, result.samples.size)
        val outRms = rms(result.samples)
        assertTrue(
            "a short clip must not be reduced to silence (rms=$outRms)",
            outRms > 0.01f
        )
    }

    @Test
    fun `an empty input fails loudly instead of returning silence`() {
        val gate = SpectralGateVoiceClean()
        val failed = runCatching {
            gate.spectralGate(FloatArray(0), strength = 0.6f, sampleRate = 16_000)
        }
        assertTrue(
            "an empty buffer must fail, not silently produce silence",
            failed.isFailure
        )
    }

    @Test
    fun `the tail of the clip is processed, not dropped`() {
        val gate = SpectralGateVoiceClean()
        // 2 s of noise at 16 kHz with a loud burst in the LAST
        // 200 ms — the region the old implementation dropped.
        val n = 16_000 * 2
        val input = noise(n, 0.05f)
        for (i in (n - 3_200) until n) input[i] = 0.9f
        val result = gate.spectralGate(input, strength = 0.6f, sampleRate = 16_000)
        val tailRms = rms(result.samples, n - 3_200, n)
        assertTrue(
            "the tail must survive the gate (rms=$tailRms)",
            tailRms > 0.1f
        )
    }

    @Test
    fun `the noise floor is reduced`() {
        val gate = SpectralGateVoiceClean()
        // Stationary noise only: the profile IS the signal, so the
        // gate must pull the level down.
        val input = noise(16_000, 0.2f)
        val result = gate.spectralGate(input, strength = 0.9f, sampleRate = 16_000)
        val before = rms(input)
        val after = rms(result.samples)
        assertTrue(
            "the noise floor should drop (before=$before after=$after)",
            after < before
        )
    }

    @Test
    fun `the per-frame speech probability separates loud frames from quiet ones`() {
        val gate = SpectralGateVoiceClean()
        val frame = SpectralGateVoiceClean.FRAME_SIZE
        val hop = SpectralGateVoiceClean.HOP_SIZE
        val n = 16_000
        val input = noise(n, 0.005f)
        // One loud burst in the middle third.
        for (i in (n / 3) until (n / 3 + 6_000)) input[i] = 0.8f

        val result = gate.spectralGate(input, strength = 0.6f, sampleRate = 16_000)
        val vad = result.speechProbability
        assertTrue("the gate must report a probability per frame", vad.isNotEmpty())
        assertTrue("probabilities must be within [0,1]", vad.all { it in 0f..1f })

        val quietFrame = (n / 6) / hop
        val loudFrame = (n / 2) / hop
        val quiet = vad.getOrElse(quietFrame) { 0f }
        val loud = vad.getOrElse(loudFrame) { 0f }
        assertTrue(
            "a loud frame must score higher than a quiet one (quiet=$quiet loud=$loud)",
            loud > quiet
        )
    }

    @Test
    fun `the gate is rate aware - the same signal at 48 kHz keeps its length`() {
        val gate = SpectralGateVoiceClean()
        val input = noise(48_000, 0.2f)
        val result = gate.spectralGate(input, strength = 0.6f, sampleRate = 48_000)
        assertEquals("the output must keep the input length", input.size, result.samples.size)
        assertTrue("the output must not be silent", rms(result.samples) > 0.001f)
    }

    @Test
    fun `the output never exceeds the input range`() {
        val gate = SpectralGateVoiceClean()
        val input = noise(16_000, 0.9f)
        val result = gate.spectralGate(input, strength = 0.9f, sampleRate = 16_000)
        assertTrue(
            "the gated signal must stay inside [-1, 1]",
            result.samples.all { abs(it) <= 1.0001f }
        )
    }
}
