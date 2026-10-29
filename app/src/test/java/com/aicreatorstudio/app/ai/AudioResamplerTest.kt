package com.aicreatorstudio.app.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [AudioResampler] — the sample-rate / channel math
 * shared by Voice Clean's RNNoise path. No Android dependencies;
 * exercises real numeric behavior (lengths, round-trips, clamps).
 */
class AudioResamplerTest {

    @Test
    fun `int16 mono bytes decode to unit-scale floats`() {
        // 0, +32767, -32768, little-endian mono.
        val pcm = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0, 0x80.toByte())
        val out = AudioResampler.int16BytesToMonoFloat(pcm, 1)
        assertEquals(3, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(32767f / 32768f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
    }

    @Test
    fun `stereo downmixes by averaging channels`() {
        // L=+16384, R=-16384 -> 0.
        val pcm = byteArrayOf(0, 0x40, 0, 0xC0.toByte())
        val out = AudioResampler.int16BytesToMonoFloat(pcm, 2)
        assertEquals(1, out.size)
        assertEquals(0f, out[0], 1e-6f)
    }

    @Test
    fun `same-rate resample is a copy`() {
        val src = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = AudioResampler.resampleMonoFloat(src, 44100, 44100)
        assertArrayEquals(src, out, 1e-6f)
    }

    @Test
    fun `upsample doubles length`() {
        val src = FloatArray(100) { 0.5f }
        val out = AudioResampler.resampleMonoFloat(src, 24000, 48000)
        assertEquals(200, out.size)
        assertTrue(out.all { it == 0.5f })
    }

    @Test
    fun `downsample halves length`() {
        val src = FloatArray(200) { -0.25f }
        val out = AudioResampler.resampleMonoFloat(src, 48000, 24000)
        assertEquals(100, out.size)
    }

    @Test
    fun `empty input resamples to empty`() {
        assertEquals(0, AudioResampler.resampleMonoFloat(FloatArray(0), 44100, 48000).size)
    }

    @Test
    fun `float to int16 round-trips through clamping`() {
        val src = floatArrayOf(0f, 0.5f, -0.5f, 2f, -2f)
        val bytes = AudioResampler.monoFloatToInt16Bytes(src)
        assertEquals(10, bytes.size)
        val back = AudioResampler.int16BytesToMonoFloat(bytes, 1)
        assertEquals(0f, back[0], 1e-3f)
        assertEquals(0.5f, back[1], 1e-3f)
        assertEquals(-0.5f, back[2], 1e-3f)
        // Overshoots saturate at ±1 instead of wrapping.
        assertEquals(1f, back[3], 1e-3f)
        assertEquals(-1f, back[4], 1e-3f)
    }

    @Test
    fun `gain envelope of ones is a pass-through within one lsb`() {
        // int16 -> float -> int16 is not bit-exact (32768 vs 32767
        // scale): unity gain may dither the LSB by 1. That is
        // inaudible and correct; the test pins the bound.
        val pcm = ByteArray(8) { 0x40 }
        val env = FloatArray(4) { 1f }
        val out = AudioResampler.applyGainEnvelopeToInt16(pcm, 1, env)
        assertEquals(pcm.size, out.size)
        for (i in pcm.indices) {
            val diff = kotlin.math.abs((pcm[i].toInt() and 0xFF) - (out[i].toInt() and 0xFF))
            assertTrue("byte $i differs by $diff", diff <= 1)
        }
    }

    @Test
    fun `gain envelope of zeros silences without garbage`() {
        val pcm = ByteArray(8) { 0x40 }
        val env = FloatArray(4) { 0f }
        val out = AudioResampler.applyGainEnvelopeToInt16(pcm, 1, env)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test
    fun `gain envelope longer than audio is truncated safely`() {
        val pcm = ByteArray(4) { 0x10 }
        val env = FloatArray(100) { 0.5f }
        val out = AudioResampler.applyGainEnvelopeToInt16(pcm, 1, env)
        assertEquals(4, out.size)
    }

    @Test
    fun `upsampled envelope interpolates linearly`() {
        // 2 frames of gain [0, 1] at 48 kHz/480 → 960 target
        // samples at 48 kHz: first samples ~0, last ~1.
        val env = AudioResampler.upsampleGainEnvelope(
            gains = floatArrayOf(0f, 1f),
            frameSamples = 480,
            envelopeRate = 48000,
            targetSamples = 960,
            targetRate = 48000
        )
        assertEquals(960, env.size)
        assertTrue("first was ${env.first()}", env.first() < 0.1f)
        assertTrue("last was ${env.last()}", env.last() > 0.9f)
        // Monotonic non-decreasing.
        for (i in 1 until env.size) {
            assertTrue("dip at $i", env[i] >= env[i - 1] - 1e-6f)
        }
    }

    @Test
    fun `rms of silence is zero and of full scale is near one`() {
        assertEquals(0f, AudioResampler.rms(FloatArray(100)), 1e-6f)
        val full = FloatArray(100) { 1f }
        assertEquals(1f, AudioResampler.rms(full), 1e-3f)
    }

    @Test
    fun `peak normalize leaves quiet audio alone`() {
        val src = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = AudioResampler.normalizePeak(src.copyOf())
        assertArrayEquals(src, out, 1e-6f)
    }

    @Test
    fun `peak normalize tames hot audio`() {
        val src = floatArrayOf(0f, 2f, -1f)
        val out = AudioResampler.normalizePeak(src.copyOf(), 0.98f)
        assertTrue(out.all { kotlin.math.abs(it) <= 0.98f + 1e-6f })
    }

    // ---- 2026-10-20 ANTI-ALIASING ----

    private fun tone(freqHz: Double, rate: Int, samples: Int, amp: Double = 0.5): FloatArray =
        FloatArray(samples) { i ->
            (amp * kotlin.math.sin(2.0 * Math.PI * freqHz * i / rate)).toFloat()
        }

    private fun rms(a: FloatArray, from: Int): Double {
        var acc = 0.0
        var n = 0
        for (i in from until a.size) {
            acc += a[i].toDouble() * a[i]
            n++
        }
        return if (n == 0) 0.0 else kotlin.math.sqrt(acc / n)
    }

    @Test
    fun `downsampling attenuates content above the target nyquist`() {
        // 48 kHz -> 16 kHz. A 1 kHz tone is in band and must
        // survive; a 20 kHz tone is above the 8 kHz target Nyquist
        // and would fold back to 4 kHz without a filter. Measure
        // after the filter settles.
        val inBand = AudioResampler.resampleMonoFloat(tone(1000.0, 48000, 48000), 48000, 16000)
        val outOfBand = AudioResampler.resampleMonoFloat(tone(20000.0, 48000, 48000), 48000, 16000)
        val inRms = rms(inBand, 4000)
        val outRms = rms(outOfBand, 4000)
        assertTrue("1 kHz must pass (rms=$inRms)", inRms > 0.3)
        assertTrue(
            "20 kHz must be crushed by the anti-alias filter (rms=$outRms vs $inRms)",
            outRms < inRms * 0.05
        )
    }

    @Test
    fun `int16 downsampling matches the float path length`() {
        val shorts = ShortArray(48_000) { (16000.0 * kotlin.math.sin(2.0 * Math.PI * 440.0 * it / 48000.0)).toInt().toShort() }
        val out = AudioResampler.resampleShortMonoToFloat(shorts, 48000, 16000)
        assertEquals(16_000, out.size)
        assertTrue("must produce a real signal", out.maxOf { kotlin.math.abs(it) } > 0.2f)
    }

    @Test
    fun `short same-rate conversion is a plain scale`() {
        val shorts = shortArrayOf(0, 16384, -32768)
        val out = AudioResampler.resampleShortMonoToFloat(shorts, 16000, 16000)
        assertEquals(3, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(-1f, out[2], 1e-6f)
    }

    @Test
    fun `upsampling does not filter`() {
        // No anti-alias filter is applied when the rate rises, so a
        // constant stays constant.
        val src = FloatArray(100) { 0.5f }
        val out = AudioResampler.resampleMonoFloat(src, 24000, 48000)
        assertTrue(out.all { kotlin.math.abs(it - 0.5f) < 1e-4f })
    }
}
