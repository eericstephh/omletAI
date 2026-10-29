package com.aicreatorstudio.app.ai.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Chooses where to cut the recording into whisper windows.
 *
 * ## Why not a fixed 30-second grid
 *
 * whisper.cpp decodes at most ~30 s per call, and the engine
 * calls it once per window so it can report progress and honour
 * cancel. A fixed grid therefore cuts at exactly 30.000 s — which
 * is a random point in the speech. A word straddling the cut is
 * split in two: the first half is decoded with no following
 * context, the second half with no preceding context, and BOTH
 * halves usually come out wrong. On a 10-minute video that is 19
 * damaged word boundaries, and the damage is invisible in the
 * settings — it just looks like "whisper mishears words".
 *
 * Cutting inside the quietest point near each nominal boundary
 * moves the cut into a pause, so every window starts and ends on
 * clean speech. The window length then varies by up to
 * ±[DEFAULT_SEARCH_MS] around the nominal value, which whisper
 * does not care about.
 *
 * Pure Kotlin: the tests feed synthetic speech-like signals with
 * a known pause and assert the cut lands inside it.
 */
object WindowPlanner {

    /** How far either side of the nominal boundary to look. */
    const val DEFAULT_SEARCH_MS: Int = 600

    /** A window is never shorter than this fraction of the nominal one. */
    const val MIN_FILL_RATIO: Float = 0.55f

    /** Energy frame used to score candidate cut points. */
    const val ENERGY_FRAME_MS: Int = 10

    /**
     * @return window start indices, always beginning with 0 and
     *   ending with `samples.size`. Size ≥ 2 for a non-empty input.
     *   The last window may be shorter than the nominal length.
     */
    fun boundaries(
        samples: FloatArray,
        sampleRate: Int,
        windowSamples: Int,
        searchMs: Int = DEFAULT_SEARCH_MS
    ): IntArray {
        require(windowSamples > 0) { "windowSamples must be positive" }
        if (samples.isEmpty()) return intArrayOf(0)
        val total = samples.size
        if (total <= windowSamples) return intArrayOf(0, total)

        val frame = max(1, sampleRate * ENERGY_FRAME_MS / 1000)
        val search = max(1, (sampleRate.toLong() * searchMs / 1000L).toInt())
        val minWindow = max(1, (windowSamples * MIN_FILL_RATIO).toInt())

        val out = ArrayList<Int>(total / windowSamples + 2)
        out.add(0)
        var cursor = 0
        while (cursor + windowSamples < total) {
            val nominal = cursor + windowSamples
            val lo = max(cursor + minWindow, nominal - search)
            val hi = min(total - 1, nominal + search)
            val cut = if (hi <= lo) {
                min(nominal, total - 1)
            } else {
                quietestIndex(samples, lo, hi, frame)
            }
            // Guarantee forward progress even if the search window
            // collapsed against the end of the buffer.
            val safeCut = max(cut, cursor + 1)
            out.add(safeCut)
            cursor = safeCut
        }
        if (out.last() != total) out.add(total)
        return out.toIntArray()
    }

    /**
     * Index of the lowest-energy frame inside `[lo, hi]`, stepped by
     * half a frame (5 ms at 16 kHz — finer than any phoneme onset).
     * Ties resolve to the candidate closest to the midpoint of the
     * search range so the windows stay near their nominal length.
     */
    fun quietestIndex(samples: FloatArray, lo: Int, hi: Int, frame: Int): Int {
        if (hi <= lo) return lo
        val mid = (lo + hi) / 2
        val step = max(1, frame / 2)
        var best = lo
        var bestEnergy = Double.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        var i = lo
        while (i <= hi) {
            val end = min(i + frame, samples.size)
            var acc = 0.0
            for (k in i until end) {
                val v = samples[k].toDouble()
                acc += v * v
            }
            val energy = if (end > i) acc / (end - i) else 0.0
            val distance = kotlin.math.abs(i - mid)
            if (energy < bestEnergy - 1e-12 ||
                (energy <= bestEnergy + 1e-12 && distance < bestDistance)
            ) {
                best = i
                bestEnergy = energy
                bestDistance = distance
            }
            i += step
        }
        return best
    }

    /** RMS of `[from, to)` — exposed for the tests. */
    internal fun rms(samples: FloatArray, from: Int, to: Int): Double {
        val end = min(to, samples.size)
        if (end <= from) return 0.0
        var acc = 0.0
        for (i in from until end) acc += samples[i].toDouble() * samples[i]
        return sqrt(acc / (end - from))
    }
}
