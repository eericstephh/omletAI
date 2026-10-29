package com.aicreatorstudio.app.model

/**
 * A timbre stage a [VoiceEffect] switches on.
 *
 * Modelled as an explicit set rather than three booleans so a profile can
 * be compared, logged and asserted on as a whole, and so adding a stage
 * later cannot be forgotten in one of the three places a boolean would
 * have to be threaded through.
 */
enum class VoiceFilter {
    /** Amplitude modulation of the signal by a low-frequency oscillator. */
    RING_MODULATION,

    /** High-pass + low-pass pair that keeps only a speech band. */
    BANDPASS,

    /** A delayed, attenuated copy of the signal mixed back in. */
    ECHO
}

/**
 * 2026-10-13 — **Voice Changer**: offline voice transformation for a
 * video clip's audio track or a standalone audio clip.
 *
 * ## What each effect actually does
 *
 * | Effect      | Pitch | Timbre stages                                  |
 * |-------------|-------|------------------------------------------------|
 * | `NONE`      | 1.00× | — (bit-exact pass-through)                     |
 * | `CHIPMUNK`  | 1.60× | —                                              |
 * | `DEEP`      | 0.65× | —                                              |
 * | `ROBOT`     | 1.00× | ring modulation + band-pass                    |
 * | `TELEPHONE` | 1.00× | band-pass                                      |
 * | `ECHO`      | 1.00× | delay line                                     |
 *
 * Only two of the six are a pure pitch shift. That is deliberate and it is
 * why this feature is not implemented as "set a pitch factor": a robot is
 * a *ring modulation*, a telephone is a *band-pass*, and an echo is a
 * *delay line*. Each of those is a time-domain stage that the pitch
 * shifter in Media3 cannot express. See [VoiceEffectDsp] for the
 * implementations and [com.aicreatorstudio.app.export.VoiceEffectAudioProcessor]
 * for where they run.
 *
 * ## [playbackRate] is 1.0 for every effect, on purpose
 *
 * A naive "chipmunk" is just *playing the audio faster*, which raises the
 * pitch by shortening the sound. This app cannot do that: the audio track
 * has to stay locked to the video track, and the clip's own speed (and its
 * `SpeedCurve` ramp) already owns the one playback rate the timeline has.
 * A second rate would desync picture from sound and would multiply against
 * the ramp — exactly the "break the speed curve" failure the brief warns
 * about.
 *
 * So every effect is **duration-neutral**: pitch and timbre change, the
 * length of the clip does not. The property is still declared (and
 * asserted by `VoiceEffectTest`) because it is a real invariant worth
 * pinning — a future effect that set anything other than 1.0 here would
 * break A/V sync, and the test fails if one ever does.
 *
 * @param displayName the Persian label shown on the preset card.
 * @param pitchFactor how much to shift pitch, preserving duration.
 *   1.0 = unchanged. Clamped into
 *   `[VoiceEffects.MIN_PITCH, VoiceEffects.MAX_PITCH]` by
 *   [VoiceEffects.profileFor].
 * @param playbackRate how much the effect changes the clip's *length*.
 *   Always 1.0 — see the class KDoc.
 * @param filters the timbre stages this effect enables.
 * @param ringModHz low-frequency oscillator rate for
 *   [VoiceFilter.RING_MODULATION]. Ignored when the filter is off.
 * @param bandpassLowHz lower edge of the speech band, for
 *   [VoiceFilter.BANDPASS]. Ignored when the filter is off.
 * @param bandpassHighHz upper edge of the speech band. Always coerced
 *   above [bandpassLowHz] and below Nyquist by the DSP.
 * @param echoDelayMs delay between the dry signal and its first repeat.
 * @param echoFeedback how much of each repeat is fed back into the next,
 *   `0f..0.9f`. Above ~0.9 a delay line can run away, so it is clamped.
 * @param echoMix how loud the repeats are relative to the dry signal.
 * @param outputGain makeup gain applied after the stages, so a band-pass
 *   (which removes most of the signal's energy) can be brought back to a
 *   comparable loudness.
 */
enum class VoiceEffect(
    val displayName: String,
    val pitchFactor: Float,
    val playbackRate: Float,
    val filters: Set<VoiceFilter>,
    val ringModHz: Float = 0f,
    val bandpassLowHz: Float = 0f,
    val bandpassHighHz: Float = 0f,
    val echoDelayMs: Float = 0f,
    val echoFeedback: Float = 0f,
    val echoMix: Float = 0f,
    val outputGain: Float = 1f
) {
    /** «عادی» — no processing at all. The default for every clip. */
    NONE(
        displayName = "عادی",
        pitchFactor = 1f,
        playbackRate = 1f,
        filters = emptySet()
    ),

    /** «سنجاب / کارتونی» — a high, cartoon pitch. */
    CHIPMUNK(
        displayName = "سنجاب / کارتونی",
        pitchFactor = 1.6f,
        playbackRate = 1f,
        filters = emptySet(),
        // Shifting a voice up this far concentrates energy in the
        // harmonics, which reads as harsh. A little makeup *down* keeps
        // it at the same perceived loudness as the original.
        outputGain = 0.85f
    ),

    /** «غول / بم» — a low, giant pitch. */
    DEEP(
        displayName = "غول / بم",
        pitchFactor = 0.65f,
        playbackRate = 1f,
        filters = emptySet(),
        outputGain = 1.05f
    ),

    /**
     * «رباتیک» — ring modulation plus a speech band-pass.
     *
     * The 42 Hz oscillator is deliberately below the speech range: it
     * chops the waveform at a rate the ear hears as a metallic buzz
     * rather than as a separate tone.
     */
    ROBOT(
        displayName = "رباتیک",
        pitchFactor = 1f,
        playbackRate = 1f,
        filters = setOf(VoiceFilter.RING_MODULATION, VoiceFilter.BANDPASS),
        ringModHz = 42f,
        bandpassLowHz = 300f,
        bandpassHighHz = 3400f,
        outputGain = 1.25f
    ),

    /**
     * «تلفنی» — the classic 300–3400 Hz telephone band.
     *
     * The band-pass throws away most of the signal's energy, so the
     * makeup gain is large; without it the effect reads as "quiet and
     * broken" rather than "on the phone".
     */
    TELEPHONE(
        displayName = "تلفنی",
        pitchFactor = 1f,
        playbackRate = 1f,
        filters = setOf(VoiceFilter.BANDPASS),
        bandpassLowHz = 300f,
        bandpassHighHz = 3400f,
        outputGain = 1.8f
    ),

    /**
     * «اکو» — one audible repeat trail.
     *
     * Feedback stays well under 1.0 so the tail decays instead of
     * building up; the DSP clamps it again for a profile that came from
     * a hand-edited project file.
     */
    ECHO(
        displayName = "اکو",
        pitchFactor = 1f,
        playbackRate = 1f,
        filters = setOf(VoiceFilter.ECHO),
        echoDelayMs = 220f,
        echoFeedback = 0.34f,
        echoMix = 0.45f
    );

    /**
     * True for [NONE] only. The single predicate the pipeline branches on
     * to decide whether to insert a processor at all, so "no effect" can
     * never be half-applied.
     */
    val isIdentity: Boolean get() = this == NONE

    /**
     * Whether this effect needs a pitch stage in addition to the timbre
     * stages. Lets the export skip constructing a `SonicAudioProcessor`
     * for the four effects that are pitch-neutral.
     */
    val needsPitchShift: Boolean get() = pitchFactor != 1f
}

/**
 * A [VoiceEffect] resolved into the exact numbers the DSP is allowed to
 * use: every value inside its legal band, every pair of related values
 * (band edges, echo feedback) made self-consistent.
 *
 * The enum carries the *authored* values; this carries the *validated*
 * ones. Keeping them separate means the renderers never have to re-check
 * anything, and a profile that came from a hand-edited project file (or a
 * future build with a wider range) still cannot produce a `NaN` filter
 * coefficient or a delay line that grows without bound — both of which
 * would produce silence or a crash rather than a merely wrong sound.
 *
 * @see VoiceEffects.profileFor
 */
data class VoiceEffectProfile(
    val effect: VoiceEffect,
    val pitchFactor: Float,
    val playbackRate: Float,
    val filters: Set<VoiceFilter>,
    val ringModHz: Float,
    val bandpassLowHz: Float,
    val bandpassHighHz: Float,
    val echoDelayMs: Float,
    val echoFeedback: Float,
    val echoMix: Float,
    val outputGain: Float
) {
    /** True when this profile would leave the signal untouched. */
    val isIdentity: Boolean
        get() = effect.isIdentity &&
            pitchFactor == 1f &&
            filters.isEmpty() &&
            outputGain == 1f
}

/**
 * Pure resolution of a [VoiceEffect] into a clamped [VoiceEffectProfile],
 * plus the bands everything is clamped into.
 *
 * Lives in `model/` — not in the audio processor — because that is the
 * only place it can be unit-tested: the processor needs Media3's
 * `AudioProcessor` machinery and a real audio format, while this is plain
 * arithmetic.
 */
object VoiceEffects {

    /**
     * Pitch band. A voice shifted beyond these stops being intelligible
     * speech, and a pitch factor of 0 or a negative one would make
     * `SonicAudioProcessor` throw.
     */
    const val MIN_PITCH: Float = 0.5f
    const val MAX_PITCH: Float = 2f

    /** Legal delay-line feedback. 1.0 or above never decays. */
    const val MAX_ECHO_FEEDBACK: Float = 0.9f

    /** Longest echo the panel can produce. */
    const val MAX_ECHO_DELAY_MS: Float = 1000f

    /**
     * Lowest usable band edge. Below this a "band-pass" is really just a
     * low-pass, and the high-pass stage would remove the entire signal.
     */
    const val MIN_FILTER_HZ: Float = 20f

    /**
     * Highest band edge as a fraction of Nyquist. A biquad's coefficients
     * blow up as the cut-off approaches Nyquist, so the edge is pulled
     * back rather than allowed to reach it.
     */
    const val MAX_FILTER_NYQUIST_FRACTION: Float = 0.45f

    /** [playbackRate] for every effect. See [VoiceEffect]'s KDoc. */
    const val NEUTRAL_PLAYBACK_RATE: Float = 1f

    /**
     * The highest band edge a biquad may use at [sampleRateHz].
     *
     * `0.45 × sampleRate / 2` — i.e. 45% of Nyquist. At 48 kHz that is
     * 10.8 kHz, comfortably above the 3.4 kHz a telephone band needs, and
     * far enough below Nyquist that the bilinear-transform warp does not
     * produce a self-oscillating filter.
     */
    fun maxFilterHz(sampleRateHz: Int): Float {
        if (sampleRateHz <= 0) return MIN_FILTER_HZ
        val nyquist = sampleRateHz / 2f
        return (nyquist * MAX_FILTER_NYQUIST_FRACTION).coerceAtLeast(MIN_FILTER_HZ)
    }

    /** `value` clamped into `[min, max]`, or `fallback` when it is not a usable number. */
    fun clampOr(value: Float, min: Float, max: Float, fallback: Float): Float {
        if (!value.isFinite()) return fallback
        return value.coerceIn(min, max)
    }

    /** Pitch factor pulled into [MIN_PITCH]..[MAX_PITCH]; non-finite → 1.0. */
    fun clampPitch(pitchFactor: Float): Float =
        clampOr(pitchFactor, MIN_PITCH, MAX_PITCH, 1f)

    /**
     * Resolves [effect] into the numbers the DSP will actually use.
     *
     * @param sampleRateHz the stream's sample rate, needed to clamp the
     *   band edges below Nyquist. Passing a non-positive rate falls back
     *   to [MIN_FILTER_HZ] as the ceiling, which disables the band-pass
     *   rather than producing an unstable filter.
     */
    fun profileFor(effect: VoiceEffect, sampleRateHz: Int = 48_000): VoiceEffectProfile {
        val ceiling = maxFilterHz(sampleRateHz)
        val wantsBandpass = VoiceFilter.BANDPASS in effect.filters

        // A band-pass needs low < high. Authored values already satisfy
        // that, but a profile must stay valid for any future effect, so
        // the ordering is enforced here instead of assumed.
        val rawLow = clampOr(effect.bandpassLowHz, MIN_FILTER_HZ, ceiling, MIN_FILTER_HZ)
        val rawHigh = clampOr(effect.bandpassHighHz, MIN_FILTER_HZ, ceiling, ceiling)
        val low = minOf(rawLow, rawHigh)
        val high = maxOf(rawLow, rawHigh)

        return VoiceEffectProfile(
            effect = effect,
            pitchFactor = clampPitch(effect.pitchFactor),
            playbackRate = NEUTRAL_PLAYBACK_RATE,
            filters = effect.filters,
            ringModHz = clampOr(
                effect.ringModHz,
                MIN_FILTER_HZ,
                ceiling,
                MIN_FILTER_HZ
            ),
            // A band-pass with a zero-width band is silence, so the two
            // edges are only reported when the filter is actually on.
            bandpassLowHz = if (wantsBandpass) low else 0f,
            bandpassHighHz = if (wantsBandpass) high else 0f,
            echoDelayMs = if (VoiceFilter.ECHO in effect.filters) {
                clampOr(effect.echoDelayMs, 1f, MAX_ECHO_DELAY_MS, 0f)
            } else {
                0f
            },
            echoFeedback = if (VoiceFilter.ECHO in effect.filters) {
                clampOr(effect.echoFeedback, 0f, MAX_ECHO_FEEDBACK, 0f)
            } else {
                0f
            },
            echoMix = if (VoiceFilter.ECHO in effect.filters) {
                clampOr(effect.echoMix, 0f, 1f, 0f)
            } else {
                0f
            },
            outputGain = clampOr(effect.outputGain, 0f, 4f, 1f)
        )
    }

    /**
     * Whether the effect needs a *pitch* stage in addition to its timbre
     * stages. The export uses this to avoid constructing a
     * `SonicAudioProcessor` it would only pass through.
     */
    fun needsPitchStage(effect: VoiceEffect): Boolean =
        clampPitch(effect.pitchFactor) != 1f

    /**
     * Every effect, in the order the panel shows them. `NONE` is first
     * because it is the default and the reset target.
     */
    val panelOrder: List<VoiceEffect> = listOf(
        VoiceEffect.NONE,
        VoiceEffect.CHIPMUNK,
        VoiceEffect.DEEP,
        VoiceEffect.ROBOT,
        VoiceEffect.TELEPHONE,
        VoiceEffect.ECHO
    )
}
