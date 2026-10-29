package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Coverage for [WaveformGenerator] (PART 16 of the brief).
 *
 * These tests are pure JVM — they exercise the down-sampler and
 * the resampler without touching Android APIs. The audio panel
 * relies on the generator's behaviour for waveform fidelity (a
 * 1 kHz sine must produce peaks close to its amplitude at the
 * source rate, never random / static values).
 */
class WaveformGeneratorTest {

    @Test
    fun emptyInputProducesEmptyList() {
        val out = WaveformGenerator.compute(FloatArray(0), 44_100)
        assertTrue(out.isEmpty())
    }

    @Test
    fun invalidSampleRateProducesEmptyList() {
        val samples = FloatArray(100) { 0.5f }
        assertTrue(WaveformGenerator.compute(samples, 0).isEmpty())
        assertTrue(WaveformGenerator.compute(samples, -1).isEmpty())
    }

    @Test
    fun invalidBucketSizeProducesEmptyList() {
        val samples = FloatArray(100) { 0.5f }
        assertTrue(WaveformGenerator.compute(samples, 44_100, 0L).isEmpty())
        assertTrue(WaveformGenerator.compute(samples, 44_100, -1L).isEmpty())
    }

    @Test
    fun silenceProducesZeroAmplitudeBuckets() {
        val samples = FloatArray(44_100) { 0f } // 1s of silence at 44.1k
        val out = WaveformGenerator.compute(samples, 44_100, bucketMs = 100L)
        assertEquals(10, out.size)
        out.forEach { p ->
            assertEquals(0f, p.max, 1e-6f)
            assertEquals(0f, p.min, 1e-6f)
        }
    }

    @Test
    fun constantToneProducesConstantPeaks() {
        // 0.5 amplitude DC offset for 1 second @ 44.1 kHz.
        val samples = FloatArray(44_100) { 0.5f }
        val out = WaveformGenerator.compute(samples, 44_100, bucketMs = 50L)
        // 1 second / 50ms buckets = 20 buckets.
        assertEquals(20, out.size)
        out.forEach { p ->
            // |0.5| == 0.5; min and max should both be 0.5.
            assertEquals(0.5f, p.max, 1e-6f)
            assertEquals(0.5f, p.min, 1e-6f)
        }
    }

    @Test
    fun sineWaveProducesPeaksAtAmplitude() {
        // 1 kHz sine at 0.8 amplitude, 0.1s @ 44.1kHz = 4410 samples.
        val sr = 44_100
        val samples = FloatArray(sr / 10) { i ->
            (0.8 * sin(2.0 * PI * 1000.0 * i / sr)).toFloat()
        }
        val out = WaveformGenerator.compute(samples, sr, bucketMs = 10L)
        assertTrue("expected many buckets", out.size >= 5)
        // The loudest sample of any 10ms window of a 1 kHz sine is the
        // global peak (0.8). Allow 1e-3 tolerance for linear-interp
        // precision.
        val maxAmp = out.maxOf { it.max }
        assertTrue("max amp should be ~0.8, got $maxAmp", maxAmp in 0.79f..0.81f)
    }

    @Test
    fun bucketStartAndEndTimesAreContiguous() {
        val samples = FloatArray(44_100) { 0.3f }
        val out = WaveformGenerator.compute(samples, 44_100, bucketMs = 50L)
        out.forEachIndexed { i, p ->
            assertEquals(i * 50L, p.bucketStartMs)
            assertEquals((i + 1) * 50L, p.bucketEndMs)
        }
    }

    @Test
    fun resampleCollapsesToTargetBucketCount() {
        val peaks = (0 until 1000).map { i ->
            AudioWaveformPeak(
                min = 0.1f * (i % 10),
                max = 0.9f * ((i + 5) % 10) / 10f,
                bucketStartMs = i * 10L,
                bucketEndMs = (i + 1) * 10L
            )
        }
        val out = WaveformGenerator.resample(peaks, targetBuckets = 100)
        assertEquals(100, out.size)
    }

    @Test
    fun resampleReturnsInputWhenAlreadySmall() {
        val peaks = listOf(
            AudioWaveformPeak(0.1f, 0.2f, 0L, 50L),
            AudioWaveformPeak(0.3f, 0.4f, 50L, 100L)
        )
        val out = WaveformGenerator.resample(peaks, targetBuckets = 10)
        assertEquals(peaks, out)
    }

    @Test
    fun resampleEmptyInputReturnsEmpty() {
        assertTrue(WaveformGenerator.resample(emptyList(), targetBuckets = 10).isEmpty())
        val peaks = listOf(
            AudioWaveformPeak(0.1f, 0.2f, 0L, 50L)
        )
        assertTrue(WaveformGenerator.resample(peaks, targetBuckets = 0).isEmpty())
    }

    @Test
    fun resamplePreservesLoudestSampleInWindow() {
        // 4 buckets, loud sample (0.9) in the middle. Resample to 1
        // bucket; the output should reflect the loudest amplitude.
        val peaks = listOf(
            AudioWaveformPeak(0.1f, 0.2f, 0L, 50L),
            AudioWaveformPeak(0.3f, 0.9f, 50L, 100L),
            AudioWaveformPeak(0.4f, 0.5f, 100L, 150L),
            AudioWaveformPeak(0.1f, 0.2f, 150L, 200L)
        )
        val out = WaveformGenerator.resample(peaks, targetBuckets = 1)
        assertEquals(1, out.size)
        val p = out.first()
        assertNotNull(p)
        assertEquals(0.9f, p.max, 1e-6f)
        assertEquals(0.1f, p.min, 1e-6f)
    }
}