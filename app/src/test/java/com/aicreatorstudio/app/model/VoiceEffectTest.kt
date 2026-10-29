package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [VoiceEffect] / [VoiceEffects] — the data and resolution
 * layer of the 2026-10-13 Voice Changer.
 *
 * Everything here is plain arithmetic on plain floats, so it runs in the
 * JVM test source set with no Android, no Media3 and no audio hardware.
 * The signal chain itself is covered separately by `VoiceEffectDspTest`.
 *
 * The invariants pinned here are the ones a future edit could plausibly
 * break *silently* — a wrong pitch factor still produces sound, and an
 * effect that quietly changed the clip's length would still "work" until
 * someone noticed the audio drifting out of sync with the picture.
 */
class VoiceEffectTest {

    // ---- shape ----

    @Test
    fun `the panel shows all six presets with NONE first`() {
        // NONE first because it is both the default and the reset target,
        // so «عادی» is the leftmost card in both readings of the panel.
        assertEquals(
            listOf(
                VoiceEffect.NONE,
                VoiceEffect.CHIPMUNK,
                VoiceEffect.DEEP,
                VoiceEffect.ROBOT,
                VoiceEffect.TELEPHONE,
                VoiceEffect.ECHO
            ),
            VoiceEffects.panelOrder
        )
        assertEquals(
            "panelOrder must list every effect exactly once",
            VoiceEffect.entries.toSet(),
            VoiceEffects.panelOrder.toSet()
        )
    }

    @Test
    fun `every preset has a non-blank Persian display name`() {
        VoiceEffect.entries.forEach { effect ->
            assertTrue(
                "$effect has a blank displayName",
                effect.displayName.isNotBlank()
            )
        }
    }

    // ---- the duration-neutrality invariant ----

    @Test
    fun `every effect is duration neutral`() {
        // THE headline invariant of this feature. A naive chipmunk is just
        // "play it faster", which would desync picture from sound and would
        // multiply against the clip's own SpeedCurve ramp. Every effect
        // here changes pitch and/or timbre and leaves the length alone, so
        // this must stay 1.0 for all six — and it must stay 1.0 in the
        // RESOLVED profile too, not just on the enum.
        VoiceEffect.entries.forEach { effect ->
            assertEquals(
                "$effect must not change the clip's duration",
                VoiceEffects.NEUTRAL_PLAYBACK_RATE,
                effect.playbackRate,
                0f
            )
            assertEquals(
                "$effect's resolved profile must not change the duration either",
                VoiceEffects.NEUTRAL_PLAYBACK_RATE,
                VoiceEffects.profileFor(effect).playbackRate,
                0f
            )
        }
    }

    // ---- pitch ----

    @Test
    fun `chipmunk and deep carry the documented pitch factors`() {
        assertEquals(1.6f, VoiceEffect.CHIPMUNK.pitchFactor, 0f)
        assertEquals(0.65f, VoiceEffect.DEEP.pitchFactor, 0f)
    }

    @Test
    fun `only the two pitch presets need a pitch stage`() {
        assertTrue(VoiceEffects.needsPitchStage(VoiceEffect.CHIPMUNK))
        assertTrue(VoiceEffects.needsPitchStage(VoiceEffect.DEEP))
        listOf(
            VoiceEffect.NONE,
            VoiceEffect.ROBOT,
            VoiceEffect.TELEPHONE,
            VoiceEffect.ECHO
        ).forEach { effect ->
            assertFalse(
                "$effect is pitch-neutral and must not build a Sonic stage",
                VoiceEffects.needsPitchStage(effect)
            )
            assertEquals(1f, effect.pitchFactor, 0f)
        }
    }

    @Test
    fun `pitch is clamped into the legal band`() {
        assertEquals(VoiceEffects.MIN_PITCH, VoiceEffects.clampPitch(0.1f), 0f)
        assertEquals(VoiceEffects.MAX_PITCH, VoiceEffects.clampPitch(9f), 0f)
        assertEquals(VoiceEffects.MIN_PITCH, VoiceEffects.clampPitch(-1f), 0f)
        // A value already inside the band is untouched.
        assertEquals(1.37f, VoiceEffects.clampPitch(1.37f), 0f)
    }

    @Test
    fun `a non-finite pitch falls back to unity rather than to a bound`() {
        // NaN coerced into a range is NaN, and a NaN pitch makes
        // SonicAudioProcessor produce silence or throw. The fallback is
        // 1.0 (unchanged) rather than MIN/MAX: an unreadable value should
        // leave the voice alone, not pick an arbitrary extreme.
        assertEquals(1f, VoiceEffects.clampPitch(Float.NaN), 0f)
        assertEquals(1f, VoiceEffects.clampPitch(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, VoiceEffects.clampPitch(Float.NEGATIVE_INFINITY), 0f)
    }

    // ---- filters ----

    @Test
    fun `each preset enables exactly the stages it needs`() {
        assertEquals(emptySet<VoiceFilter>(), VoiceEffect.NONE.filters)
        assertEquals(emptySet<VoiceFilter>(), VoiceEffect.CHIPMUNK.filters)
        assertEquals(emptySet<VoiceFilter>(), VoiceEffect.DEEP.filters)
        assertEquals(
            setOf(VoiceFilter.RING_MODULATION, VoiceFilter.BANDPASS),
            VoiceEffect.ROBOT.filters
        )
        assertEquals(setOf(VoiceFilter.BANDPASS), VoiceEffect.TELEPHONE.filters)
        assertEquals(setOf(VoiceFilter.ECHO), VoiceEffect.ECHO.filters)
    }

    @Test
    fun `the telephone band is the classic 300 to 3400 hertz band`() {
        assertEquals(300f, VoiceEffect.TELEPHONE.bandpassLowHz, 0f)
        assertEquals(3400f, VoiceEffect.TELEPHONE.bandpassHighHz, 0f)
    }

    // ---- the band-edge clamp (the NaN guard) ----

    @Test
    fun `the filter ceiling is 45 percent of Nyquist`() {
        // 48 kHz -> Nyquist 24 kHz -> ceiling 10.8 kHz.
        assertEquals(10_800f, VoiceEffects.maxFilterHz(48_000), 0.01f)
        assertEquals(5_400f, VoiceEffects.maxFilterHz(24_000), 0.01f)
    }

    @Test
    fun `a non-positive sample rate yields the floor, not a crash or a zero`() {
        // The DSP must never be handed a ceiling of 0: that would clamp
        // every band edge to 0 and make `sin(w0)` collapse, which produces
        // NaN coefficients and silences the track. The safe answer is the
        // floor, which disables the band-pass instead.
        assertEquals(VoiceEffects.MIN_FILTER_HZ, VoiceEffects.maxFilterHz(0), 0f)
        assertEquals(VoiceEffects.MIN_FILTER_HZ, VoiceEffects.maxFilterHz(-48_000), 0f)
    }

    @Test
    fun `band edges are pulled below the ceiling at low sample rates`() {
        // At 8 kHz the ceiling is 1800 Hz, so the authored 3400 Hz edge
        // must come down. Without this the low-pass would sit above Nyquist
        // and the bilinear warp would make the biquad self-oscillate.
        val profile = VoiceEffects.profileFor(VoiceEffect.TELEPHONE, sampleRateHz = 8_000)
        val ceiling = VoiceEffects.maxFilterHz(8_000)
        assertEquals(1_800f, ceiling, 0.01f)
        assertTrue(
            "high edge ${profile.bandpassHighHz} must not exceed $ceiling",
            profile.bandpassHighHz <= ceiling
        )
        assertTrue(
            "low edge must stay below the high edge",
            profile.bandpassLowHz < profile.bandpassHighHz
        )
        assertTrue(profile.bandpassLowHz >= VoiceEffects.MIN_FILTER_HZ)
    }

    @Test
    fun `band edges are always ordered low to high`() {
        VoiceEffect.entries.forEach { effect ->
            val profile = VoiceEffects.profileFor(effect)
            if (VoiceFilter.BANDPASS in effect.filters) {
                assertTrue(
                    "$effect: ${profile.bandpassLowHz} !< ${profile.bandpassHighHz}",
                    profile.bandpassLowHz < profile.bandpassHighHz
                )
            }
        }
    }

    @Test
    fun `stages that are switched off report zeroed parameters`() {
        // A band-pass that is off must not report band edges, an echo that
        // is off must not report a delay. Otherwise a caller could not tell
        // "this effect has no echo" from "this effect has a 0 ms echo", and
        // the DSP's enable checks would be the only thing standing between
        // a stray value and a filter that is silently doing something.
        val chipmunk = VoiceEffects.profileFor(VoiceEffect.CHIPMUNK)
        assertEquals(0f, chipmunk.bandpassLowHz, 0f)
        assertEquals(0f, chipmunk.bandpassHighHz, 0f)
        assertEquals(0f, chipmunk.echoDelayMs, 0f)
        assertEquals(0f, chipmunk.echoFeedback, 0f)
        assertEquals(0f, chipmunk.echoMix, 0f)

        val robot = VoiceEffects.profileFor(VoiceEffect.ROBOT)
        assertEquals(0f, robot.echoDelayMs, 0f)
        assertTrue(robot.bandpassHighHz > 0f)
    }

    @Test
    fun `echo feedback can never reach unity`() {
        // Feedback of 1.0 or more is a delay line that never decays, i.e.
        // a runaway that ends in clipping or a full buffer of noise.
        val profile = VoiceEffects.profileFor(VoiceEffect.ECHO)
        assertTrue(
            "echo feedback ${profile.echoFeedback} must stay below 1",
            profile.echoFeedback < 1f
        )
        assertTrue(profile.echoFeedback <= VoiceEffects.MAX_ECHO_FEEDBACK)
        assertTrue(profile.echoDelayMs > 0f)
        assertTrue(profile.echoMix > 0f)
    }

    @Test
    fun `a band-passed preset carries makeup gain so it does not read as broken`() {
        // Removing everything outside 300–3400 Hz also removes most of the
        // signal's energy. Without makeup gain the effect sounds like a
        // fault rather than like a telephone.
        assertTrue(VoiceEffect.TELEPHONE.outputGain > 1f)
        assertTrue(VoiceEffect.ROBOT.outputGain > 1f)
        // The chipmunk shifts energy into the harmonics, which reads as
        // harsh, so it trims a little instead of boosting.
        assertTrue(
            "the chipmunk should trim rather than boost, got ${VoiceEffect.CHIPMUNK.outputGain}",
            VoiceEffect.CHIPMUNK.outputGain < 1f
        )
        // The deep voice has no filtering either; its 1.05 is a hair of
        // makeup for the low end that the pitch shift thins out.
        assertEquals(1.05f, VoiceEffect.DEEP.outputGain, 0f)
    }

    // ---- identity ----

    @Test
    fun `only NONE is the identity`() {
        assertTrue(VoiceEffect.NONE.isIdentity)
        assertTrue(VoiceEffects.profileFor(VoiceEffect.NONE).isIdentity)
        VoiceEffect.entries.filter { it != VoiceEffect.NONE }.forEach { effect ->
            assertFalse("$effect must not be the identity", effect.isIdentity)
            assertFalse(
                "$effect's resolved profile must not be the identity",
                VoiceEffects.profileFor(effect).isIdentity
            )
        }
    }

    @Test
    fun `an identity profile resolves to exactly the input parameters`() {
        // The pass-through has to be *exact*: an untouched project must
        // render byte-identically, which means no gain change, no pitch
        // change, no filters.
        val none = VoiceEffects.profileFor(VoiceEffect.NONE)
        assertEquals(1f, none.pitchFactor, 0f)
        assertEquals(1f, none.playbackRate, 0f)
        assertEquals(1f, none.outputGain, 0f)
        assertEquals(emptySet<VoiceFilter>(), none.filters)
    }

    @Test
    fun `the resolved profile keeps the effect it came from`() {
        // The processors compare profiles to decide whether to rebuild the
        // DSP; a profile that forgot its effect would compare equal across
        // a preset switch and the audio would never change.
        VoiceEffect.entries.forEach { effect ->
            assertEquals(effect, VoiceEffects.profileFor(effect).effect)
        }
    }

    @Test
    fun `profile resolution is independent of the default sample rate`() {
        // The enum's authored values are the source of truth for the four
        // effects whose bands sit well inside every supported rate, so
        // resolving at 44.1 kHz and at 48 kHz must agree.
        listOf(VoiceEffect.NONE, VoiceEffect.CHIPMUNK, VoiceEffect.DEEP, VoiceEffect.ECHO)
            .forEach { effect ->
                assertEquals(
                    "$effect resolved differently at 44.1 kHz",
                    VoiceEffects.profileFor(effect, 48_000),
                    VoiceEffects.profileFor(effect, 44_100)
                )
            }
    }

    // ---- clamping helper ----

    @Test
    fun `clampOr substitutes the fallback only for unusable numbers`() {
        assertEquals(5f, VoiceEffects.clampOr(5f, 0f, 10f, 99f), 0f)
        assertEquals(0f, VoiceEffects.clampOr(-5f, 0f, 10f, 99f), 0f)
        assertEquals(10f, VoiceEffects.clampOr(50f, 0f, 10f, 99f), 0f)
        assertEquals(99f, VoiceEffects.clampOr(Float.NaN, 0f, 10f, 99f), 0f)
        assertEquals(99f, VoiceEffects.clampOr(Float.POSITIVE_INFINITY, 0f, 10f, 99f), 0f)
    }
}
