package com.aicreatorstudio.app.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 2026-10-13 — the **voice changer's signal chain**, as pure arithmetic.
 *
 * ## Why this is not inside the audio processor
 *
 * The export runs this through a Media3 `AudioProcessor` and the live
 * preview runs it through the player's audio sink — but the *maths* is
 * neither. Keeping it here, in `model/`, is what makes it testable at all:
 * a `BaseAudioProcessor` needs a real `AudioFormat` and Media3's buffer
 * machinery, while this class needs an array of floats and a sample rate.
 * `VoiceEffectDspTest` drives it directly with generated sine waves and
 * measures what comes out.
 *
 * It also means there is exactly **one** implementation of every stage.
 * Preview and export cannot drift, because they are the same object
 * operating on the same numbers.
 *
 * ## The chain
 *
 * ```
 * input -> [high-pass] -> [low-pass] -> [ring mod] -> [echo] -> [gain] -> clamp
 * ```
 *
 * - The **band-pass is two biquads**, not one. A biquad "band-pass" has a
 *   fixed bandwidth in octaves, which is not what a telephone is: the
 *   telephone band is a high-pass at 300 Hz *and* a low-pass at 3400 Hz,
 *   independently chosen. Two Butterworth (Q = 0.707) sections give that
 *   exactly and are trivial to reason about.
 * - The band-pass runs **first, before the ring modulator**. Band-limiting
 *   ahead of a non-linear stage is the standard order: modulating first
 *   would fold content that is about to be discarded back down into the
 *   audible range as aliasing.
 * - The **ring modulator's LFO advances once per frame**, not once per
 *   sample, so a stereo signal is chopped identically in both channels and
 *   the effect does not run at double rate on the right channel.
 * - The **echo is per channel** and reads before it writes, so a mono
 *   buffer cannot leak one channel's tail into the other's.
 * - Everything is clamped to `[-1, 1]` on the way out, because the next
 *   stage in every path is an encoder that assumes that range.
 *
 * ## Statefulness
 *
 * Filters, the LFO phase and the delay line all carry state across
 * buffers, so one instance is bound to one continuous stream. [reset]
 * clears that state; the processors call it whenever the stream restarts
 * (a seek, a flush, a new item), which is what stops a stale delay line
 * from spilling the previous clip's tail into the next one.
 *
 * @param profile the validated numbers to run. Produced by
 *   [VoiceEffects.profileFor] so no value here can be out of band.
 * @param sampleRateHz the stream's sample rate. Decides every filter
 *   coefficient and the delay-line length, so it must be the real rate of
 *   the audio being processed.
 * @param channelCount interleaved channel count (1 = mono, 2 = stereo).
 *   Coerced to at least 1.
 */
class VoiceEffectDsp(
    val profile: VoiceEffectProfile,
    val sampleRateHz: Int,
    val channelCount: Int
) {

    /** Interleaved channel count actually in use. Never zero. */
    val channels: Int = channelCount.coerceAtLeast(1)

    /** Sample rate actually in use. Never zero, so no coefficient can divide by it. */
    val rate: Int = sampleRateHz.coerceAtLeast(1)

    /**
     * True when this DSP would leave the signal untouched.
     *
     * The processors still have to run the buffer through (an
     * `AudioProcessor` cannot decline a buffer it has already been handed)
     * but they can skip every stage, which keeps an untouched project's
     * audio bit-exact.
     */
    val isActive: Boolean = !profile.isIdentity

    private val bandpassEnabled =
        VoiceFilter.BANDPASS in profile.filters &&
            profile.bandpassHighHz > profile.bandpassLowHz

    private val ringModEnabled =
        VoiceFilter.RING_MODULATION in profile.filters && profile.ringModHz > 0f

    private val echoEnabled =
        VoiceFilter.ECHO in profile.filters &&
            profile.echoMix > 0f &&
            profile.echoDelayMs > 0f

    // ---- band-pass ----------------------------------------------------

    private val highPass = Biquad.highPass(profile.bandpassLowHz, rate)
    private val lowPass = Biquad.lowPass(profile.bandpassHighHz, rate)

    // ---- ring modulation ----------------------------------------------

    /** LFO phase in turns, `0f..1f`. */
    private var lfoPhase = 0f

    private val lfoStep: Float =
        if (ringModEnabled) (profile.ringModHz / rate.toFloat()) else 0f

    // ---- echo ---------------------------------------------------------

    /** Delay-line length in frames; at least one so the read never aliases the write. */
    private val delayFrames: Int = if (echoEnabled) {
        ((profile.echoDelayMs / 1000f) * rate).roundToInt().coerceIn(1, rate)
    } else {
        1
    }

    private val delayLines: Array<FloatArray> =
        Array(channels) { FloatArray(delayFrames) }

    private var writeIndex = 0

    // ---- processing ---------------------------------------------------

    /**
     * Processes interleaved float PCM in place.
     *
     * @param samples interleaved samples. Only the first [sampleCount]
     *   entries are read and written; the rest are left alone so a caller
     *   can reuse an oversized scratch array.
     * @param sampleCount number of **interleaved samples** (frames ×
     *   channels), not frames. A trailing partial frame is ignored rather
     *   than processed with a missing channel.
     */
    fun processInPlace(samples: FloatArray, sampleCount: Int) {
        if (!isActive) return
        val usable = minOf(sampleCount, samples.size)
        if (usable <= 0) return
        val frames = usable / channels
        var index = 0
        for (frame in 0 until frames) {
            // One LFO value per FRAME — see the class KDoc.
            val lfo = if (ringModEnabled) nextLfo() else 0f
            for (channel in 0 until channels) {
                samples[index] = processOne(samples[index], channel, lfo)
                index++
            }
            // One write slot per frame, shared by every channel, so the
            // channels stay phase-locked to each other. Advancing this
            // once per *buffer* instead would make the delay depend on
            // the buffer size Media3 happens to hand over.
            if (echoEnabled) {
                writeIndex = (writeIndex + 1) % delayFrames
            }
        }
    }

    /**
     * One sample through the whole chain.
     *
     * @param lfo the ring modulator's value for this frame, already
     *   advanced. Unused when the filter is off.
     */
    private fun processOne(input: Float, channel: Int, lfo: Float): Float {
        val channelIndex = channel.coerceIn(0, channels - 1)
        var sample = input

        if (bandpassEnabled) {
            sample = highPass.process(sample, channelIndex)
            sample = lowPass.process(sample, channelIndex)
        }

        if (ringModEnabled) {
            // Blended, not multiplied outright. The multiplier is
            // `(1 - blend) + blend * lfo`, so a constant `1 - blend` of the
            // dry signal survives on average even though the instantaneous
            // value still swings negative. What that buys is a *shallower*
            // modulation: the trough sits at `1 - 2 * blend` (-0.7) rather
            // than at -1, and the mean sits at `1 - blend` (+0.15) rather
            // than at 0. A pure `sample * lfo` has zero mean, i.e. no dry
            // signal at all, and sounds like a Dalek; keeping a constant
            // component leaves a recognisable voice inside the metallic
            // buzz.
            //
            // Note the modulation still passes through zero twice per LFO
            // cycle — that is inherent to amplitude modulation, not a bug.
            // `RING_MOD_BLEND` controls the depth, not the nulling.
            sample *= (1f - RING_MOD_BLEND) + RING_MOD_BLEND * lfo
        }

        if (echoEnabled) {
            val line = delayLines[channelIndex]
            val delayed = line[writeIndex]
            // Read-then-write, so the feedback path cannot see the sample
            // it is about to store.
            line[writeIndex] = (sample + profile.echoFeedback * delayed)
                .coerceIn(-1f, 1f)
            sample += profile.echoMix * delayed
        }

        sample *= profile.outputGain
        return sample.coerceIn(-1f, 1f)
    }

    /** Advances and returns the next ring-modulator value. */
    private fun nextLfo(): Float {
        val value = sin(TWO_PI * lfoPhase)
        lfoPhase += lfoStep
        if (lfoPhase >= 1f) lfoPhase -= 1f
        return value
    }

    /**
     * Clears every stateful element: filter memories, LFO phase and the
     * delay line.
     *
     * Called when a stream (re)starts. Without it, a seek would replay the
     * tail of the audio the user just jumped away from, and a new clip
     * would inherit the previous one's filter transient.
     */
    fun reset() {
        highPass.reset()
        lowPass.reset()
        lfoPhase = 0f
        writeIndex = 0
        delayLines.forEach { it.fill(0f) }
    }

    /**
     * A transposed-direct-form-II biquad.
     *
     * Transposed DF-II rather than DF-I because it needs only two state
     * variables per channel instead of four, and it degrades more
     * gracefully when a coefficient is near the edge of stability —
     * which is exactly the case the band edges are clamped against.
     *
     * Coefficients are the standard RBJ audio-EQ-cookbook set at
     * `Q = 1/sqrt(2)` (Butterworth, i.e. maximally flat passband), which
     * is the right choice for a band *edge*: it has no resonant peak, so
     * a telephone band sounds like a telephone band rather than like a
     * filter sweep.
     */
    private class Biquad private constructor(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float
    ) {
        private val z1 = FloatArray(MAX_CHANNELS)
        private val z2 = FloatArray(MAX_CHANNELS)

        fun process(input: Float, channel: Int): Float {
            val index = channel.coerceIn(0, MAX_CHANNELS - 1)
            val output = b0 * input + z1[index]
            z1[index] = b1 * input - a1 * output + z2[index]
            z2[index] = b2 * input - a2 * output
            return output
        }

        fun reset() {
            z1.fill(0f)
            z2.fill(0f)
        }

        companion object {
            /**
             * State slots. Comfortably above any channel count an MP4
             * carries (5.1 is 6), so an unusual layout degrades to
             * sharing the last slot instead of throwing.
             */
            private const val MAX_CHANNELS = 8

            fun lowPass(cutoffHz: Float, sampleRateHz: Int): Biquad =
                build(cutoffHz, sampleRateHz, highPass = false)

            fun highPass(cutoffHz: Float, sampleRateHz: Int): Biquad =
                build(cutoffHz, sampleRateHz, highPass = true)

            private fun build(cutoffHz: Float, sampleRateHz: Int, highPass: Boolean): Biquad {
                val rate = sampleRateHz.coerceAtLeast(1).toFloat()
                // Clamp the cut-off away from both 0 and Nyquist: at either
                // extreme `sin(w0)` collapses and the normalised
                // coefficients become NaN, which would silence the track.
                val ceiling = rate * 0.45f
                val cutoff = cutoffHz.coerceIn(1f, ceiling.coerceAtLeast(1f))

                val w0 = 2.0 * PI * (cutoff / rate)
                val cosW0 = cos(w0).toFloat()
                val alpha = (sin(w0) / (2.0 * BUTTERWORTH_Q)).toFloat()
                val a0 = 1f + alpha

                val rawB0: Float
                val rawB1: Float
                val rawB2: Float
                if (highPass) {
                    rawB0 = (1f + cosW0) / 2f
                    rawB1 = -(1f + cosW0)
                    rawB2 = (1f + cosW0) / 2f
                } else {
                    rawB0 = (1f - cosW0) / 2f
                    rawB1 = 1f - cosW0
                    rawB2 = (1f - cosW0) / 2f
                }

                return Biquad(
                    b0 = rawB0 / a0,
                    b1 = rawB1 / a0,
                    b2 = rawB2 / a0,
                    a1 = (-2f * cosW0) / a0,
                    a2 = (1f - alpha) / a0
                )
            }

            /** `1 / sqrt(2)` — Butterworth, no passband ripple. */
            private val BUTTERWORTH_Q = 1.0 / kotlin.math.sqrt(2.0)
        }
    }

    companion object {
        private const val TWO_PI = (2.0 * PI).toFloat()

        /**
         * How deep the ring modulation goes, `0f..1f`.
         *
         * The multiplier is `(1 - blend) + blend * sin(theta)`, so this
         * value controls two things at once and it is worth being precise
         * about both:
         *
         * | blend | peak | trough | mean |
         * |-------|------|--------|------|
         * | 1.0   | 1    | -1     | 0    |
         * | 0.85  | 1    | -0.7   | +0.15|
         *
         * The **mean** is the useful number: it is the fraction of the dry
         * signal that survives the modulation. At 1.0 there is none, which
         * is textbook ring modulation and sounds like a Dalek; at 0.85 a
         * constant 15% rides along, which is the difference between
         * "robot" and "unintelligible buzz".
         *
         * It does **not** remove the modulation's zero crossings — the
         * multiplier still passes through zero twice per LFO cycle, as any
         * amplitude modulation must. A value below 0.5 would keep the
         * multiplier positive throughout, but that is no longer ring
         * modulation; it is a tremolo, and it does not sound like a robot.
         */
        const val RING_MOD_BLEND: Float = 0.85f
    }
}
