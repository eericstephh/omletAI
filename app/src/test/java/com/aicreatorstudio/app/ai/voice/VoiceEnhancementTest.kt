package com.aicreatorstudio.app.ai.voice

import com.aicreatorstudio.app.model.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PHASE 4 — JVM tests for local voice enhancement DSP + contract.
 *
 * Covers: conditioning (silent / quiet / loud), clipping prevention,
 * output normalize caps, waveform peaks, preset ordering, source-key
 * staleness, and duration-guard constants. The native RNNoise call
 * itself stays covered by the on-device self-test + existing
 * VoiceCleanGainTest (JNI is out of JVM scope by design).
 */
class VoiceEnhancementTest {

    private fun sine(frames: Int, peak: Float, freqHz: Float = 440f, rate: Int = 48_000): FloatArray {
        val out = FloatArray(frames)
        for (i in out.indices) {
            out[i] = (peak * kotlin.math.sin(2.0 * Math.PI * freqHz * i / rate)).toFloat()
        }
        return out
    }

    private fun floatToInt16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun clip(id: String = "c1", durationMs: Long = 60_000L) = VideoClip(
        id = id, uri = "file:///$id.mp4", fileName = "$id.mp4", durationMs = durationMs,
        width = 1920, height = 1080, fileSizeBytes = 1_000L, importedAtEpochMillis = 0L
    )

    // ---- conditioning ----

    @Test
    fun `digital silence passes through untouched`() {
        val silent = FloatArray(4800)
        val stats = VoiceNormalizer.conditionForDenoise(silent)
        assertEquals(1f, stats.appliedGain, 0.0001f)
        assertTrue(silent.all { it == 0f })
    }

    @Test
    fun `empty input is safe`() {
        val stats = VoiceNormalizer.conditionForDenoise(FloatArray(0))
        assertEquals(1f, stats.appliedGain, 0.0001f)
    }

    @Test
    fun `quiet speech is boosted toward target`() {
        val quiet = sine(4800, 0.05f) // ≈ −26 dBFS
        val before = VoiceNormalizer.peakOf(quiet)
        val stats = VoiceNormalizer.conditionForDenoise(quiet)
        assertTrue(stats.appliedGain > 1f)
        assertTrue(stats.appliedGain <= VoiceNormalizer.MAX_BOOST_GAIN + 0.001f)
        val after = VoiceNormalizer.peakOf(quiet)
        assertTrue(after > before)
        assertTrue(after <= 1f)
    }

    @Test
    fun `loud speech is left alone`() {
        val loud = sine(4800, 0.95f)
        val before = loud.copyOf()
        val stats = VoiceNormalizer.conditionForDenoise(loud)
        assertEquals(1f, stats.appliedGain, 0.0001f)
        assertTrue(loud.contentEquals(before))
    }

    @Test
    fun `boost never exceeds plus 12 dB`() {
        val tiny = sine(4800, 0.001f) // −60 dBFS would need +59 dB
        val stats = VoiceNormalizer.conditionForDenoise(tiny)
        assertTrue(stats.appliedGain <= VoiceNormalizer.MAX_BOOST_GAIN + 0.001f)
        assertTrue(VoiceNormalizer.peakOf(tiny) <= 1f)
    }

    @Test
    fun `conditioning is deterministic`() {
        val a = sine(4800, 0.07f)
        val b = a.copyOf()
        VoiceNormalizer.conditionForDenoise(a)
        VoiceNormalizer.conditionForDenoise(b)
        assertTrue(a.contentEquals(b))
    }

    // ---- output normalize ----

    @Test
    fun `output normalize lifts quiet mix without clipping`() {
        val bytes = floatToInt16(sine(4800, 0.1f))
        val gain = VoiceNormalizer.normalizeOutputInt16(bytes, 1)
        assertTrue(gain > 1f)
        assertTrue(gain <= VoiceNormalizer.MAX_BOOST_GAIN + 0.001f)
        // Re-scan: still valid int16, peak at/below target.
        val peakDb = VoiceNormalizer.peakOfInt16Db(bytes)
        assertTrue(peakDb.isFinite())
        assertTrue(peakDb <= VoiceNormalizer.peakToDb(VoiceNormalizer.OUTPUT_TARGET_PEAK) + 0.5f)
    }

    @Test
    fun `output normalize leaves hot mix untouched`() {
        val bytes = floatToInt16(sine(4800, 0.95f))
        val before = bytes.copyOf()
        assertEquals(1f, VoiceNormalizer.normalizeOutputInt16(bytes, 1), 0.0001f)
        assertTrue(bytes.contentEquals(before))
    }

    @Test
    fun `output normalize is length preserving`() {
        val bytes = floatToInt16(sine(4801, 0.05f))
        VoiceNormalizer.normalizeOutputInt16(bytes, 2)
        assertEquals(4801 * 2, bytes.size)
    }

    @Test
    fun `output normalize handles empty input`() {
        assertEquals(1f, VoiceNormalizer.normalizeOutputInt16(ByteArray(0), 1), 0.0001f)
        assertEquals(1f, VoiceNormalizer.normalizeOutputInt16(ByteArray(1), 1), 0.0001f)
    }

    // ---- helpers ----

    @Test
    fun `waveform peaks track energy per bucket`() {
        val samples = FloatArray(6400)
        // Loud first half, silent second half.
        for (i in 0 until 3200) samples[i] = 0.8f
        val peaks = VoiceNormalizer.waveformPeaks(samples, 64)
        assertEquals(64, peaks.size)
        assertTrue(peaks.take(32).all { it > 0.5f })
        assertTrue(peaks.drop(32).all { it == 0f })
    }

    @Test
    fun `suppression estimate is bounded and sane`() {
        assertEquals(6f, VoiceNormalizer.suppressionDb(-10f, -16f), 0.001f)
        assertEquals(0f, VoiceNormalizer.suppressionDb(-16f, -10f), 0.001f) // never negative
        assertEquals(24f, VoiceNormalizer.suppressionDb(0f, -60f), 0.001f) // clamped
        assertEquals(0f, VoiceNormalizer.suppressionDb(Float.NEGATIVE_INFINITY, -10f), 0.001f)
    }

    // ---- presets ----

    @Test
    fun `presets order by aggression and all normalize`() {
        val ordered = VoiceEnhancementPreset.entries.sortedBy { it.strength }
        assertEquals(
            listOf(
                VoiceEnhancementPreset.NATURAL,
                VoiceEnhancementPreset.CLEAR_VOICE,
                VoiceEnhancementPreset.STRONG_CLEANUP
            ),
            ordered
        )
        for (p in VoiceEnhancementPreset.entries) {
            assertTrue(p.strength in 0.1f..1f)
            assertTrue(p.persianLabel.isNotBlank())
            assertTrue(p.description.isNotBlank())
        }
        assertEquals(VoiceEnhancementPreset.CLEAR_VOICE, VoiceEnhancementPreset.Default)
    }

    // ---- source key ----

    @Test
    fun `source key detects clip changes`() {
        val base = clip()
        val key = voiceSourceKey(base)
        assertTrue(key.isNotBlank())
        assertEquals(key, voiceSourceKey(base.copy()))
        // Trim change.
        assertFalse(key == voiceSourceKey(base.copy(trimStartMs = 500L)))
        assertFalse(key == voiceSourceKey(base.copy(trimEndMs = 30_000L)))
        // Different source bytes.
        assertFalse(key == voiceSourceKey(base.copy(uri = "file:///other.mp4")))
        // Different clip / duration / timeline position.
        assertFalse(key == voiceSourceKey(base.copy(id = "c2")))
        assertFalse(key == voiceSourceKey(base.copy(durationMs = 30_000L)))
        assertFalse(key == voiceSourceKey(base.copy(timelineStartMs = 1000L)))
    }

    // ---- guards ----

    @Test
    fun `duration guard is fifteen minutes`() {
        assertEquals(15L * 60L * 1000L, MAX_ENHANCE_DURATION_MS)
    }

    @Test
    fun `stats carry algorithm and preset`() {
        val stats = VoiceEnhancementStats(
            inputPeakDb = -20f,
            outputPeakDb = -2f,
            appliedGainDb = 10f,
            noiseReductionDb = 8f,
            durationMs = 60_000L,
            sampleRate = 44_100,
            channels = 2,
            algorithm = "rnnoise-v1",
            preset = VoiceEnhancementPreset.STRONG_CLEANUP
        )
        assertEquals("rnnoise-v1", stats.algorithm)
        assertEquals(VoiceEnhancementPreset.STRONG_CLEANUP, stats.preset)
        assertTrue(abs(stats.appliedGainDb - 10f) < 0.001f)
    }
}
