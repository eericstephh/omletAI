package com.aicreatorstudio.app.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.model.VoiceEffects
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-14 — the chain-lane contract of [VoiceEffectAudioProcessor].
 *
 * This test exists because the voice-changer preview was shipped with a
 * processor that declared `PCM_FLOAT` output inside a chain whose next
 * stage (`SonicAudioProcessor`, the pitch shift) **only accepts
 * `PCM_16BIT`** and throws `UnhandledAudioFormatException` on anything
 * else. Every play attempt died in `DefaultAudioSink.configure` with an
 * `AudioSink.ConfigurationException` — visible in the app as
 * `MediaCodecAudioRenderer error, index=1, format=audio/raw,
 * format_supported=YES` and a Play button that did nothing. The DSP tests
 * could not catch it: the maths is pure and correct; what broke was the
 * Media3 wiring around it.
 *
 * The rules pinned here were read out of the Media3 1.5.0 bytecode
 * (`DefaultAudioSink.configure`, `SonicAudioProcessor.configure`,
 * `ToFloatPcmAudioProcessor` / `ToInt16PcmAudioProcessor.queueInput`):
 *
 *  1. whatever encoding arrives, the processor must declare
 *     `ENCODING_PCM_16BIT` out — Sonic's only accepted input;
 *  2. a 16-bit stream with no effect must pass through **bit-exact**;
 *  3. a float input is converted with Media3's own constants
 *     (`/ 32768` in, `clamp(-1, 1) * 32767` out);
 *  4. the output buffer stays in the 16-bit lane (2 bytes/sample) and
 *     the DSP actually runs when an effect is selected, so the stereo
 *     frame count is preserved.
 */
class VoiceEffectAudioProcessorTest {

    private fun format(encoding: Int) =
        AudioProcessor.AudioFormat(48_000, 2, encoding)

    /** Builds a little-endian 16-bit input buffer of [values]. */
    private fun shorts16(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it.toShort()) }
            flip()
        }

    /** Drains the processor's output as a little-endian 16-bit array. */
    private fun outputShorts(p: VoiceEffectAudioProcessor): ShortArray {
        val out = p.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        val count = out.remaining() / 2
        val result = ShortArray(count)
        out.asShortBuffer().get(result)
        return result
    }

    @Test
    fun sixteenBitInputDeclaresSixteenBitOutput() {
        val p = VoiceEffectAudioProcessor { VoiceEffects.profileFor(VoiceEffect.NONE) }
        val out = p.configure(format(C.ENCODING_PCM_16BIT))
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
        assertEquals(48_000, out.sampleRate)
        assertEquals(2, out.channelCount)
    }

    @Test
    fun floatInputAlsoDeclaresSixteenBitOutput() {
        val p = VoiceEffectAudioProcessor { VoiceEffects.profileFor(VoiceEffect.NONE) }
        val out = p.configure(format(C.ENCODING_PCM_FLOAT))
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
    }

    @Test
    fun identityProfileCopiesSixteenBitStreamVerbatim() {
        val p = VoiceEffectAudioProcessor { VoiceEffects.profileFor(VoiceEffect.NONE) }
        p.configure(format(C.ENCODING_PCM_16BIT))
        val input = shorts16(0, 1000, -1000, 32767, -32768, 17, 12345, -9999)
        p.queueInput(input)
        val out = outputShorts(p)
        assertEquals(
            "a NONE profile must not touch a single sample",
            listOf<Short>(0, 1000, -1000, 32767, -32768, 17, 12345, -9999),
            out.toList()
        )
    }

    @Test
    fun floatInputConvertsWithMedia3Constants() {
        // NONE keeps the DSP idle but still converts the lane, which is
        // exactly what ToInt16PcmAudioProcessor would do downstream.
        val p = VoiceEffectAudioProcessor { VoiceEffects.profileFor(VoiceEffect.NONE) }
        p.configure(format(C.ENCODING_PCM_FLOAT))
        val input = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        input.putFloat(1f); input.putFloat(-0.5f); input.putFloat(0f); input.putFloat(2f)
        input.flip()
        p.queueInput(input)
        val out = outputShorts(p)
        assertEquals(4, out.size)
        assertEquals(32767, out[0].toInt()) // 1.0 * 32767
        assertEquals(-16383, out[1].toInt()) // -0.5 * 32767 = -16383.5, toInt truncates
        assertEquals(0, out[2].toInt())
        assertEquals(32767, out[3].toInt()) // 2.0 clamps to 1.0 first
    }

    @Test
    fun activeEffectKeepsTheSixteenBitLaneAndFrameCount() {
        val p = VoiceEffectAudioProcessor { VoiceEffects.profileFor(VoiceEffect.ROBOT) }
        p.configure(format(C.ENCODING_PCM_16BIT))
        // Loud 220 Hz-ish stereo at full scale: the robot chain must
        // survive in-range samples and a full buffer.
        val values = IntArray(256) { i -> if (i % 2 == 0) 30000 else -29000 }
        val input = shorts16(*values)
        p.queueInput(input)
        val out = outputShorts(p)
        assertEquals(values.size, out.size)
        assertTrue("the DSP must actually transform the signal", out.any { it.toInt() != 0 })
        assertTrue(
            "every output sample must stay inside int16",
            out.all { it.toInt() in -32768..32767 }
        )
        // The ring modulator / band-pass must not blow the signal up:
        // a loud in-range input may come out loud but never clipped to
        // a constant, which is what an unstable filter would produce.
        assertTrue("the chain must not collapse to a constant", out.distinct().size > 8)
    }

    @Test
    fun floatInputSurvivesAnActiveEffect() {
        val p = VoiceEffectAudioProcessor { VoiceEffects.profileFor(VoiceEffect.ROBOT) }
        p.configure(format(C.ENCODING_PCM_FLOAT))
        val input = ByteBuffer.allocate(32 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(16) { input.putFloat(0.5f); input.putFloat(-0.5f) }
        input.flip()
        p.queueInput(input)
        val out = outputShorts(p)
        assertEquals(32, out.size)
        assertTrue(out.all { it.toInt() in -32768..32767 })
    }

    @Test
    fun profileSwitchIsVisibleOnTheNextBuffer() {
        var effect = VoiceEffect.NONE
        val p = VoiceEffectAudioProcessor { VoiceEffects.profileFor(effect) }
        p.configure(format(C.ENCODING_PCM_16BIT))
        val cutoff = shorts16(1000, 1000, 1000, 1000)
        p.queueInput(cutoff)
        assertEquals(
            "no effect selected must still be a verbatim copy",
            listOf<Short>(1000, 1000, 1000, 1000),
            outputShorts(p).toList()
        )
        // The user taps a preset: the same processor instance must now
        // transform, with no player rebuild and no stale filter state.
        effect = VoiceEffect.TELEPHONE
        val loud = shorts16(20000, 20000, 20000, 20000)
        p.queueInput(loud)
        val transformed = outputShorts(p)
        assertEquals(4, transformed.size)
        assertTrue(
            "selecting an effect must change the samples on the next buffer",
            transformed.toSet() != setOf<Short>(20000, 20000, 20000, 20000)
        )
    }
}
