package com.aicreatorstudio.app.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin waveform peak computer (PART 16 of the brief).
 *
 * Takes a real PCM `FloatArray` (mono, [-1, 1]) and down-samples it
 * into `AudioWaveformPeak` buckets sized to [bucketMs]. The result is
 * what the timeline renders under a video clip; the values are
 * real per-bucket min/max from the source PCM — never random /
 * static data (PART 16 forbids synthetic waveforms).
 *
 * The downsampler is allocation-light: it walks the PCM once and
 * emits one peak per bucket. Empty inputs yield an empty list so a
 * silent / unreadable clip still produces a valid (empty) waveform
 * the UI can render.
 *
 * **Why a separate file from [AudioClip]?** The waveform is data,
 * not state. Splitting it out keeps the data class lean and lets
 * the audio panel swap to a streaming / cache-backed implementation
 * later without touching the model.
 */
object WaveformGenerator {

    /**
     * Down-sample [samples] (mono float, [-1, 1]) into peaks sized
     * to [bucketMs] milliseconds. The PCM is assumed to have been
     * captured at [sampleRateHz]; this is the only thing that maps
     * samples ↔ milliseconds. A 0 / negative sample rate or empty
     * sample buffer yields an empty list — the timeline renderer
     * can detect "no waveform yet" without a special case.
     *
     * Performance: a single pass over the PCM with O(buckets) memory.
     * For a 3-minute mono 44.1 kHz clip with 50 ms buckets that is
     * 3 600 buckets / 180 ms per bucket — fast enough to run on a
     * background dispatcher without blocking the UI.
     */
    fun compute(
        samples: FloatArray,
        sampleRateHz: Int,
        bucketMs: Long = DEFAULT_BUCKET_MS
    ): List<AudioWaveformPeak> {
        if (samples.isEmpty() || sampleRateHz <= 0 || bucketMs <= 0L) {
            return emptyList()
        }
        val samplesPerBucket = max(1L, (sampleRateHz.toLong() * bucketMs) / 1000L)
        val bucketCount = ((samples.size + samplesPerBucket - 1) / samplesPerBucket).toInt()
        if (bucketCount <= 0) return emptyList()

        val out = ArrayList<AudioWaveformPeak>(bucketCount)
        var index = 0
        var bucketIndex = 0
        while (index < samples.size) {
            val end = min(samples.size.toLong(), index + samplesPerBucket).toInt()
            var lo = 1f
            var hi = -1f
            var i = index
            while (i < end) {
                val s = samples[i]
                if (s < lo) lo = s
                if (s > hi) hi = s
                i++
            }
            // Always emit a peak, even when the bucket is silent:
            // the timeline renderer paints zero-width bars for those.
            if (lo > hi) { lo = 0f; hi = 0f }
            out.add(
                AudioWaveformPeak(
                    min = abs(lo),
                    max = abs(hi),
                    bucketStartMs = bucketIndex * bucketMs,
                    bucketEndMs = (bucketIndex + 1) * bucketMs
                )
            )
            index = end
            bucketIndex++
        }
        return out
    }

    /**
     * Aggregate a pre-computed peak list into a smaller render-set
     * for the timeline. The timeline's visible width is bounded so
     * rendering thousands of tiny buckets wastes GPU time.
     *
     * `targetBuckets` is the maximum number of buckets the timeline
     * will draw at once; this collapses the input by simple averaging
     * (min-of-mins / max-of-maxes is also fine and is what callers
     * should use for amplitude-correct rendering — we use max-of-
     * abs so the visible envelope matches the loudest sample).
     */
    fun resample(
        peaks: List<AudioWaveformPeak>,
        targetBuckets: Int
    ): List<AudioWaveformPeak> {
        if (peaks.isEmpty() || targetBuckets <= 0) return emptyList()
        if (peaks.size <= targetBuckets) return peaks
        val bucketSize = peaks.size.toDouble() / targetBuckets.toDouble()
        val out = ArrayList<AudioWaveformPeak>(targetBuckets)
        var i = 0
        for (b in 0 until targetBuckets) {
            val start = (b * bucketSize).toInt().coerceAtMost(peaks.size - 1)
            val end = ((b + 1) * bucketSize).toInt().coerceAtMost(peaks.size)
            var maxMax = 0f
            var minMin = 1f
            var j = start
            while (j < end) {
                val p = peaks[j]
                if (p.max > maxMax) maxMax = p.max
                if (p.min < minMin) minMin = p.min
                j++
            }
            out.add(
                AudioWaveformPeak(
                    min = minMin,
                    max = maxMax,
                    bucketStartMs = peaks[start].bucketStartMs,
                    bucketEndMs = peaks[(end - 1).coerceAtLeast(0)].bucketEndMs
                )
            )
            i = end
        }
        return out
    }

    /** Default bucket size in milliseconds. 50 ms = 20 buckets / second. */
    const val DEFAULT_BUCKET_MS: Long = 50L
}