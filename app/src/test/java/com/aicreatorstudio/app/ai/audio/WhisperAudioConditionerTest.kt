package com.aicreatorstudio.app.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JVM tests for the whisper front-end conditioner. Pure Kotlin, no
 * Android: every assertion is a real numeric property of the DSP,
 * measured on synthesised signals.
 */
class WhisperAudioConditionerTest {

    private val sr = 16_000

    private fun sine(freqHz: Double, amplitude: Double, samples: Int, rate: Int = 16_000): FloatArray =
        FloatArray(samples) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / rate)).toFloat()
        }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var acc = 0.0
        for (i in from until to) acc += a[i].toDouble() * a[i]
        return sqrt(acc / (to - from).coerceAtLeast(1))
    }

    private fun dbfs(x: Double): Double = 20.0 * log10(x.coerceAtLeast(1e-12))

    @Test
    fun `silence is left untouched`() {
        val buf = FloatArray(8000)
        WhisperAudioConditioner.conditionInPlace(buf, sr)
        assertTrue(buf.all { it == 0f })
    }

    @Test
    fun `empty and one-sample buffers are safe`() {
        assertEquals(0, WhisperAudioConditioner.conditionInPlace(FloatArray(0), sr).size)
        val one = floatArrayOf(0.5f)
        WhisperAudioConditioner.conditionInPlace(one, sr)
        assertEquals(1, one.size)
    }

    @Test
    fun `quiet speech is boosted toward the target level`() {
        // 1 kHz at 0.02 peak ≈ -37 dBFS RMS. Target is -20 dBFS,
        // so the conditioner should lift it by ~17 dB.
        val buf = sine(1000.0, 0.02, sr * 2)
        val before = rms(buf)
        WhisperAudioConditioner.conditionInPlace(buf, sr)
        val after = rms(buf)
        assertTrue("must get louder (before ${dbfs(before)} dB)", after > before * 3.0)
        assertEquals(
            "should land near -20 dBFS, got ${dbfs(after)}",
            -20.0, dbfs(after), 3.0
        )
    }

    @Test
    fun `hot audio is attenuated but never clipped`() {
        val buf = sine(1000.0, 0.95, sr * 2)
        val before = rms(buf)
        WhisperAudioConditioner.conditionInPlace(buf, sr)
        val after = rms(buf)
        assertTrue("must get quieter", after < before)
        val peak = buf.maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak must stay under the ceiling", peak <= WhisperAudioConditioner.PEAK_CEILING + 1e-6f)
    }

    @Test
    fun `already correct level is left alone`() {
        // Sine peak 0.1414 → RMS 0.1 = exactly -20 dBFS.
        val buf = sine(1000.0, 0.14142136, sr)
        val before = rms(buf)
        WhisperAudioConditioner.conditionInPlace(buf, sr)
        assertEquals(before, rms(buf), before * 0.05)
    }

    @Test
    fun `dc offset is removed`() {
        val buf = FloatArray(sr) { 0.3f }
        WhisperAudioConditioner.removeRumble(buf, sr)
        val tail = rms(buf, sr - 2000, sr)
        assertTrue("residual DC $tail must be negligible", tail < 1e-3)
    }

    @Test
    fun `sub-bass rumble is attenuated while speech band passes`() {
        val low = sine(30.0, 0.5, sr)
        val mid = sine(1000.0, 0.5, sr)
        val lowIn = rms(low)
        val midIn = rms(mid)
        WhisperAudioConditioner.removeRumble(low, sr)
        WhisperAudioConditioner.removeRumble(mid, sr)
        val lowRatio = rms(low) / lowIn
        val midRatio = rms(mid) / midIn
        assertTrue("30 Hz must be crushed, got ratio $lowRatio", lowRatio < 0.05)
        assertTrue("1 kHz must pass, got ratio $midRatio", midRatio > 0.95)
    }

    @Test
    fun `level measurement ignores silence so pauses do not drag it down`() {
        // 1 s of speech-level tone + 9 s of silence: the measured
        // active RMS must reflect the tone, not the average.
        val buf = FloatArray(sr * 10)
        val tone = sine(1000.0, 0.2, sr)
        tone.copyInto(buf, 0)
        val active = WhisperAudioConditioner.activeRms(buf, sr)
        val whole = rms(buf)
        assertTrue(
            "active ($active) must be far above whole-file ($whole)",
            active > whole * 2.5
        )
        assertEquals(0.2 / sqrt(2.0), active, 0.02)
    }

    @Test
    fun `conditioning preserves duration`() {
        val buf = sine(440.0, 0.3, 12345)
        WhisperAudioConditioner.conditionInPlace(buf, sr)
        assertEquals(12345, buf.size)
    }

    @Test
    fun `a single transient does not cancel the boost`() {
        // Quiet take (about -43 dBFS) with one loud click. The
        // click must not force the gain down for the whole file,
        // and it must not survive as a clipped sample either.
        val buf = sine(1000.0, 0.01, sr * 2)
        for (i in 0 until 50) buf[sr + i] = 0.95f
        val quietBefore = rms(buf, 0, sr / 2)
        WhisperAudioConditioner.conditionInPlace(buf, sr)
        val quietAfter = rms(buf, 0, sr / 2)
        assertTrue(
            "quiet region must still be boosted ($quietBefore -> $quietAfter)",
            quietAfter > quietBefore * 2
        )
        val peak = buf.maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak must be clamped", peak <= WhisperAudioConditioner.PEAK_CEILING + 1e-6f)
    }

    @Test
    fun `robust peak ignores a sparse transient`() {
        val buf = FloatArray(10_000) { 0.05f }
        for (i in 0 until 10) buf[i] = 0.99f
        val p = WhisperAudioConditioner.robustPeak(buf, 0.995)
        assertTrue("percentile peak $p must be near 0.05", p < 0.1f)
    }
}
