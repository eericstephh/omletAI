package com.aicreatorstudio.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signal-level tests for [VoiceEffectDsp].
 *
 * The DSP is deliberately pure arithmetic — an array of floats in, an
 * array of floats out — which is the whole reason it lives in `model/`
 * instead of inside the Media3 `AudioProcessor`. These tests drive it with
 * generated signals and measure what comes out, so they cover the actual
 * filter maths rather than asserting that some code was called.
 *
 * The three properties worth this much effort are the ones a wrong
 * implementation would still *run* with:
 *
 *  - the **band-pass** really attenuates out-of-band content (a filter with
 *    a bad coefficient table still passes audio through, just wrong);
 *  - the **echo** really lands at the configured delay, per channel, with
 *    the feedback decaying (a delay line off by one buffer produces a
 *    chorus instead of an echo, and only the timing assertion catches it);
 *  - the **ring modulator** keeps a constant dry component (the 0.85 blend
 *    is what keeps speech intelligible, and a plain multiply — the obvious
 *    implementation — is what makes it sound like a Dalek).
 */
class VoiceEffectDspTest {

    private val rate = 48_000

    // ---- helpers ----

    private fun sine(freqHz: Float, samples: Int, amplitude: Float = 1f): FloatArray =
        FloatArray(samples) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / rate)).toFloat()
        }

    private fun rms(samples: FloatArray, from: Int, until: Int): Float {
        var sum = 0.0
        for (i in from until until) sum += samples[i].toDouble() * samples[i].toDouble()
        return sqrt(sum / (until - from)).toFloat()
    }

    private fun dspFor(effect: VoiceEffect, channels: Int = 1): VoiceEffectDsp =
        VoiceEffectDsp(
            profile = VoiceEffects.profileFor(effect, rate),
            sampleRateHz = rate,
            channelCount = channels
        )

    /** A hand-built profile, for exercising one stage at a time. */
    private fun profileOf(
        filters: Set<VoiceFilter>,
        ringModHz: Float = 0f,
        bandpassLowHz: Float = 0f,
        bandpassHighHz: Float = 0f,
        echoDelayMs: Float = 0f,
        echoFeedback: Float = 0f,
        echoMix: Float = 0f,
        outputGain: Float = 1f
    ) = VoiceEffectProfile(
        effect = VoiceEffect.ROBOT,
        pitchFactor = 1f,
        playbackRate = 1f,
        filters = filters,
        ringModHz = ringModHz,
        bandpassLowHz = bandpassLowHz,
        bandpassHighHz = bandpassHighHz,
        echoDelayMs = echoDelayMs,
        echoFeedback = echoFeedback,
        echoMix = echoMix,
        outputGain = outputGain
    )

    // ---- construction ----

    @Test
    fun `sample rate and channel count are coerced away from zero`() {
        // Both are divisors: `rate` in every filter coefficient, `channels`
        // in the frame loop. A zero would be a divide-by-zero, so the
        // class must not accept one.
        val dsp = VoiceEffectDsp(VoiceEffects.profileFor(VoiceEffect.ROBOT), 0, 0)
        assertEquals(1, dsp.rate)
        assertEquals(1, dsp.channels)
    }

    @Test
    fun `the identity profile reports itself inactive`() {
        // The processors still have to push the buffer through — an
        // AudioProcessor cannot decline a buffer it has been handed — but
        // they branch on this to skip every stage and keep an untouched
        // project bit-exact.
        assertTrue(VoiceEffectDsp(VoiceEffects.profileFor(VoiceEffect.NONE), rate, 2).isActive.not())
        VoiceEffect.entries.filter { it != VoiceEffect.NONE }.forEach { effect ->
            assertTrue(
                "$effect should report the DSP as active",
                dspFor(effect).isActive
            )
        }
    }

    // ---- pass-through ----

    @Test
    fun `an identity profile leaves the buffer bit for bit unchanged`() {
        val dsp = dspFor(VoiceEffect.NONE)
        val original = sine(440f, 480)
        val processed = original.copyOf()
        dsp.processInPlace(processed, processed.size)
        assertTrue(
            "NONE must be an exact pass-through",
            original.contentEquals(processed)
        )
    }

    @Test
    fun `an inactive dsp does not touch samples beyond the count`() {
        val dsp = dspFor(VoiceEffect.NONE)
        val buffer = FloatArray(64) { 7f }
        dsp.processInPlace(buffer, 0)
        assertTrue(buffer.all { it == 7f })
    }

    @Test
    fun `a trailing partial frame is left alone`() {
        // Stereo, 5 interleaved samples = 2 frames + 1 orphan. Processing
        // the orphan would read a channel that is not there.
        val dsp = VoiceEffectDsp(profileOf(setOf(VoiceFilter.RING_MODULATION), ringModHz = 100f), rate, 2)
        val buffer = FloatArray(5) { 1f }
        dsp.processInPlace(buffer, 5)
        assertEquals(
            "the orphaned sample must be untouched",
            1f,
            buffer[4],
            0f
        )
    }

    // ---- band-pass ----

    @Test
    fun `the telephone band-pass keeps speech and rejects the extremes`() {
        val dsp = dspFor(VoiceEffect.TELEPHONE)
        val total = rate * 2

        fun gainAt(freqHz: Float): Float {
            val buffer = sine(freqHz, total)
            val input = rms(buffer, rate, total)
            dsp.reset()
            dsp.processInPlace(buffer, buffer.size)
            val output = rms(buffer, rate, total)
            return output / input
        }

        val low = gainAt(50f)      // far below the 300 Hz high-pass corner
        val mid = gainAt(1_000f)   // comfortably inside the passband
        val high = gainAt(12_000f) // far above the 3400 Hz low-pass corner

        assertTrue("a 50 Hz tone must be crushed, got $low", low < 0.15f)
        assertTrue("a 1 kHz tone must pass, got $mid", mid > 0.5f)
        // A 2nd-order Butterworth low-pass at 3.4 kHz puts a 12 kHz tone at
        // about -22 dB (|H| ~ 0.08); with the preset's 1.8x makeup gain that
        // lands near 0.14. The threshold is deliberately looser than that so
        // the test measures "rejected", not the exact coefficient table.
        assertTrue("a 12 kHz tone must be rejected, got $high", high < 0.25f)
        // The passband must be at least an order of magnitude above the
        // stopband — that is the difference between "telephone" and "muffled".
        assertTrue("passband $mid is not clearly above the 50 Hz stopband $low", mid > low * 10f)
    }

    @Test
    fun `the band-pass does not ring into a resonant peak`() {
        // Butterworth sections (Q = 1/sqrt(2)) are maximally flat: nothing
        // inside the passband may be amplified much beyond unity before the
        // makeup gain. A resonant filter would make the effect sound like a
        // sweep and could clip.
        val dsp = VoiceEffectDsp(
            profileOf(setOf(VoiceFilter.BANDPASS), bandpassLowHz = 300f, bandpassHighHz = 3400f),
            rate,
            1
        )
        val total = rate * 2
        val buffer = sine(1_000f, total)
        val input = rms(buffer, rate, total)
        dsp.processInPlace(buffer, buffer.size)
        val gain = rms(buffer, rate, total) / input
        assertTrue("unity gain expected, got $gain", abs(gain - 1f) < 0.1f)
    }

    // ---- ring modulation ----

    @Test
    fun `the ring modulator keeps a constant dry component instead of a pure multiply`() {
        // THE reason RING_MOD_BLEND exists, stated precisely.
        //
        // The multiplier is `(1 - blend) + blend * sin(theta)`, which is
        // NOT the same thing as "the signal is never nulled" — an amplitude
        // modulation always passes through zero. What the blend actually
        // buys is a shallower modulation with a non-zero MEAN: a pure
        // `sample * lfo` has zero mean, i.e. no dry signal at all, which is
        // the Dalek sound. Asserting the mean, the peak and the trough pins
        // all three numbers the constant controls.
        val dsp = VoiceEffectDsp(
            profileOf(setOf(VoiceFilter.RING_MODULATION), ringModHz = 1_000f),
            rate,
            1
        )
        // DC in, so the output IS the modulator's multiplier.
        // 4800 samples at 1 kHz / 48 kHz = exactly 100 LFO cycles, so the
        // average is not skewed by a partial cycle.
        val buffer = FloatArray(4_800) { 1f }
        dsp.processInPlace(buffer, buffer.size)

        val blend = VoiceEffectDsp.RING_MOD_BLEND

        // peak = 1: the modulation never boosts the signal above unity.
        assertEquals("peak", 1f, buffer.max(), 2e-3f)
        // trough = 1 - 2*blend: the constant component lifts the trough off
        // -1, i.e. the modulation is 85% deep rather than 100%.
        assertEquals("trough", 1f - 2f * blend, buffer.min(), 2e-3f)
        // mean = 1 - blend: the dry signal that survives on average. This is
        // the intelligibility number, and it must be positive.
        assertEquals("mean", 1f - blend, buffer.average().toFloat(), 5e-3f)
        assertTrue("the surviving dry component must be positive", 1f - blend > 0f)
        assertTrue(
            "the modulator must actually oscillate, range was ${buffer.max() - buffer.min()}",
            buffer.max() - buffer.min() > 0.5f
        )
    }

    @Test
    fun `the ring modulator advances once per frame so stereo stays phase locked`() {
        // Identical left and right input must produce identical left and
        // right output. If the LFO advanced once per *sample* instead of
        // once per frame, the right channel would use the next LFO value
        // and the two channels would diverge — which sounds like a
        // wandering stereo image.
        val dsp = VoiceEffectDsp(
            profileOf(setOf(VoiceFilter.RING_MODULATION), ringModHz = 500f),
            rate,
            2
        )
        val frames = 480
        val buffer = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val value = sin(2.0 * PI * 440.0 * frame / rate).toFloat()
            buffer[frame * 2] = value
            buffer[frame * 2 + 1] = value
        }
        dsp.processInPlace(buffer, buffer.size)

        for (frame in 0 until frames) {
            assertEquals(
                "channels diverged at frame $frame",
                buffer[frame * 2],
                buffer[frame * 2 + 1],
                0f
            )
        }
    }

    // ---- echo ----

    @Test
    fun `the echo repeats at exactly the configured delay and decays`() {
        val profile = VoiceEffects.profileFor(VoiceEffect.ECHO, rate)
        val dsp = VoiceEffectDsp(profile, rate, 1)

        // The DSP derives its line length the same way the class KDoc says;
        // computing it here from the profile keeps the test honest if the
        // preset's delay ever changes.
        val delayFrames = ((profile.echoDelayMs / 1000f) * rate).roundToInt()
        assertTrue("sanity: the echo preset must have a real delay", delayFrames > 1)

        val total = delayFrames * 3 + 16
        val buffer = FloatArray(total)
        buffer[0] = 1f // a single impulse
        dsp.processInPlace(buffer, total)

        // Dry signal at t = 0, unattenuated (ECHO's outputGain is 1).
        assertEquals("dry impulse", 1f, buffer[0], 1e-5f)
        // Nothing in between: a delay line that advanced once per buffer
        // instead of once per frame would smear the repeat across here.
        for (i in 1 until delayFrames) {
            assertEquals(
                "unexpected energy at sample $i before the first repeat",
                0f,
                buffer[i],
                1e-6f
            )
        }
        // First repeat: mix * delayed, where the delayed sample is the
        // stored dry impulse.
        assertEquals(
            "first repeat must land one delay later",
            profile.echoMix,
            buffer[delayFrames],
            1e-5f
        )
        // Second repeat: mix * (feedback * dry) — i.e. it decays.
        assertEquals(
            "second repeat must be quieter than the first",
            profile.echoMix * profile.echoFeedback,
            buffer[delayFrames * 2],
            1e-4f
        )
        assertTrue(
            "the echo must decay, not build up",
            abs(buffer[delayFrames * 2]) < abs(buffer[delayFrames])
        )
    }

    @Test
    fun `the echo is per channel and does not leak across the stereo image`() {
        val profile = VoiceEffects.profileFor(VoiceEffect.ECHO, rate)
        val dsp = VoiceEffectDsp(profile, rate, 2)
        val delayFrames = ((profile.echoDelayMs / 1000f) * rate).roundToInt()
        val frames = delayFrames * 2 + 16

        val buffer = FloatArray(frames * 2)
        buffer[0] = 1f // left only; right stays silent
        dsp.processInPlace(buffer, buffer.size)

        for (frame in 0 until frames) {
            assertEquals(
                "right channel must stay silent at frame $frame",
                0f,
                buffer[frame * 2 + 1],
                0f
            )
        }
        // ...while the left channel does get its repeat.
        assertTrue(
            "left channel lost its echo",
            abs(buffer[delayFrames * 2]) > 0f
        )
    }

    @Test
    fun `reset clears the echo tail`() {
        // Without this a seek would replay the tail of the audio the user
        // just jumped away from, and a new clip would inherit the previous
        // one's repeats.
        val profile = VoiceEffects.profileFor(VoiceEffect.ECHO, rate)
        val dsp = VoiceEffectDsp(profile, rate, 1)
        val delayFrames = ((profile.echoDelayMs / 1000f) * rate).roundToInt()

        val first = FloatArray(delayFrames * 2 + 8)
        first[0] = 1f
        dsp.processInPlace(first, first.size)
        assertTrue("setup: the echo should have been produced", abs(first[delayFrames]) > 0f)

        dsp.reset()

        val silence = FloatArray(delayFrames * 2 + 8)
        dsp.processInPlace(silence, silence.size)
        assertTrue(
            "reset must clear the delay line, found ${silence.maxOf { abs(it) }}",
            silence.all { it == 0f }
        )
    }

    // ---- output range ----

    @Test
    fun `output is clamped into the range the encoder expects`() {
        // Everything downstream (the Media3 encoder, the audio sink) assumes
        // [-1, 1]. A makeup gain above unity on a full-scale input would
        // otherwise wrap or clip unpredictably.
        val dsp = VoiceEffectDsp(
            profileOf(emptySet(), outputGain = 4f),
            rate,
            1
        )
        val buffer = sine(440f, 4_800, amplitude = 1f)
        dsp.processInPlace(buffer, buffer.size)
        assertTrue(
            "output escaped [-1, 1]: ${buffer.min()} .. ${buffer.max()}",
            buffer.all { it in -1f..1f }
        )
        assertTrue("the signal must still be there", rms(buffer, 0, buffer.size) > 0.5f)
    }

    // ---- realism ----

    @Test
    fun `every real preset produces sound and changes it`() {
        // A blunt but valuable guard: a stage that divides by zero, reads a
        // NaN coefficient or indexes off the end would show up as silence.
        val input = sine(440f, 24_000)
        VoiceEffect.entries.filter { it != VoiceEffect.NONE }.forEach { effect ->
            val dsp = dspFor(effect)
            val buffer = input.copyOf()
            dsp.processInPlace(buffer, buffer.size)
            val energy = rms(buffer, 0, buffer.size)
            assertTrue("$effect produced silence", energy > 0.01f)
            assertTrue("$effect produced NaN", buffer.all { it.isFinite() })
            assertTrue(
                "$effect must actually change the signal",
                !buffer.contentEquals(input)
            )
        }
    }
}
