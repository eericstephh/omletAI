package com.aicreatorstudio.app.ai.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Front-end conditioning for the audio that is about to be fed to
 * whisper.cpp.
 *
 * ## Why this exists
 *
 * Whisper's log-mel front end has no automatic gain control. Its
 * accuracy therefore depends on the recording arriving at roughly
 * the level the model was trained on (16 kHz speech, peaks near
 * full scale, no rumble). Phone footage is the opposite of that:
 * the on-camera mic sits at whatever distance it was held, clips
 * when the speaker gets close, and carries handling noise and
 * room rumble well below 100 Hz that the mel filters partially
 * integrate into the first bands.
 *
 * The observed symptom is not "the transcript is empty" — it is
 * *fluent but wrong* Persian: on a quiet or rumble-heavy window
 * the decoder's language model wins over the acoustics and emits
 * a plausible phrase that was never spoken. That failure mode is
 * exactly what a level + rumble fix reduces.
 *
 * ## What it does, in order
 *
 *  1. **Rumble / DC removal** — 4th-order Butterworth high-pass at
 *     [HIGHPASS_HZ]. 24 dB/octave, so the 50/60 Hz mains hum and
 *     the sub-80 Hz handling thump are gone while the fundamental
 *     of a male voice (~85 Hz) survives.
 *  2. **Active-level measurement** — frame RMS over
 *     [FRAME_MS] windows, keeping only frames within
 *     [ACTIVE_RANGE_DB] of the loudest frame. Silence and room
 *     tone therefore cannot drag the measured level down (which
 *     is what a plain whole-file RMS does on a clip with long
 *     pauses: it boosts the noise floor into the model).
 *  3. **Gain to [TARGET_RMS_DBFS]**, clamped to
 *     [MAX_BOOST_DB]..[MAX_CUT_DB].
 *  4. **Peak safety** — if the gain would push the peak past
 *     [PEAK_CEILING] the gain is reduced so the result never
 *     clips. Clipping is worse than being quiet: it adds
 *     broadband harmonics that the decoder hears as consonants.
 *
 * Everything is in place on the caller's buffer: a 30-minute
 * recording is ~115 MB of float PCM and a second copy is not
 * affordable on a phone that is already holding the model in RAM.
 */
object WhisperAudioConditioner {

    /** High-pass corner. Below the lowest human fundamental of interest. */
    const val HIGHPASS_HZ: Double = 80.0

    /** Target level for speech-active frames. */
    const val TARGET_RMS_DBFS: Double = -20.0

    /** Upper bound on the boost — more than this amplifies noise. */
    const val MAX_BOOST_DB: Double = 18.0

    /** Upper bound on the cut — never crush already-quiet speech. */
    const val MAX_CUT_DB: Double = -9.0

    /** Output never exceeds this absolute sample value. */
    const val PEAK_CEILING: Float = 0.97f

    /** Quantile used for the clipping guard (ignores isolated clicks). */
    const val PEAK_PERCENTILE: Double = 0.995

    /** Histogram resolution for [robustPeak]. */
    const val PEAK_BINS: Int = 512

    /** Analysis frame for the level measurement. */
    const val FRAME_MS: Int = 20

    /**
     * Frames quieter than `loudest - ACTIVE_RANGE_DB` are treated
     * as non-speech and excluded from the level measurement.
     */
    const val ACTIVE_RANGE_DB: Double = 40.0

    /** Below this the input is silence; normalising it is pointless. */
    private const val SILENCE_RMS: Double = 1e-5

    /**
     * Conditions [samples] **in place** and returns the same array.
     *
     * @param sampleRate must be the buffer's real rate (16000 for
     *   the whisper path).
     * @return the same instance as [samples], for call chaining.
     */
    fun conditionInPlace(samples: FloatArray, sampleRate: Int): FloatArray {
        if (samples.size < 2 || sampleRate <= 0) return samples

        removeRumble(samples, sampleRate)

        val activeRms = activeRms(samples, sampleRate)
        if (activeRms < SILENCE_RMS) return samples

        val measuredDb = 20.0 * log10(activeRms)
        val gainDb = (TARGET_RMS_DBFS - measuredDb)
            .coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        if (abs(gainDb) < 0.25) return samples

        var gain = 10.0.pow(gainDb / 20.0)
        // Robust peak, not the absolute max: one door slam in a
        // ten-minute take must not cancel the boost for the whole
        // recording. The handful of samples above the percentile
        // are clamped at the end instead.
        val peak = robustPeak(samples, PEAK_PERCENTILE)
        val peakAfter = peak * gain
        if (peakAfter > PEAK_CEILING && peak > 0f) {
            gain *= (PEAK_CEILING / peakAfter).toDouble()
        }
        val g = gain.toFloat()
        for (i in samples.indices) {
            samples[i] = (samples[i] * g).coerceIn(-PEAK_CEILING, PEAK_CEILING)
        }
        return samples
    }

    /**
     * Magnitude at the [percentile] quantile of `|samples|`.
     *
     * A 512-bin histogram, so it is O(n) with a fixed 2 KB of
     * scratch — no sorting, no allocation proportional to the
     * buffer. For tonal material this converges to the true peak;
     * for material with isolated transients it ignores them.
     */
    fun robustPeak(samples: FloatArray, percentile: Double = PEAK_PERCENTILE): Float {
        if (samples.isEmpty()) return 0f
        val bins = IntArray(PEAK_BINS)
        for (s in samples) {
            val a = abs(s)
            val idx = (a * (PEAK_BINS - 1)).toInt().coerceIn(0, PEAK_BINS - 1)
            bins[idx]++
        }
        val threshold = (samples.size * percentile).toInt().coerceAtLeast(1)
        var seen = 0
        for (i in PEAK_BINS - 1 downTo 0) {
            seen += bins[i]
            if (seen >= threshold) return i.toFloat() / (PEAK_BINS - 1)
        }
        return 0f
    }

    /** 4th-order high-pass, applied in place. */
    fun removeRumble(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val hp = Biquad.cascade(
            sampleRate = sampleRate.toDouble(),
            cutoffHz = HIGHPASS_HZ,
            order = 4,
            lowPass = false
        )
        for (i in samples.indices) {
            samples[i] = Biquad.processChain(hp, samples[i])
        }
    }

    /**
     * RMS of the speech-active frames only.
     *
     * Two passes, no allocation: pass 1 finds the loudest frame,
     * pass 2 accumulates the energy of every frame within
     * [ACTIVE_RANGE_DB] of it.
     */
    fun activeRms(samples: FloatArray, sampleRate: Int): Double {
        val frame = max(1, sampleRate * FRAME_MS / 1000)
        var loudest = 0.0
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + frame, samples.size)
            val rms = frameRms(samples, i, end)
            if (rms > loudest) loudest = rms
            i += frame
        }
        if (loudest <= 0.0) return 0.0

        val floor = loudest * 10.0.pow(-ACTIVE_RANGE_DB / 20.0)
        var acc = 0.0
        var counted = 0L
        i = 0
        while (i < samples.size) {
            val end = minOf(i + frame, samples.size)
            val rms = frameRms(samples, i, end)
            if (rms >= floor) {
                for (k in i until end) acc += samples[k].toDouble() * samples[k]
                counted += (end - i)
            }
            i += frame
        }
        if (counted == 0L) return 0.0
        return sqrt(acc / counted)
    }

    private fun frameRms(samples: FloatArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var acc = 0.0
        for (i in from until to) {
            val v = samples[i].toDouble()
            acc += v * v
        }
        return sqrt(acc / (to - from))
    }
}
