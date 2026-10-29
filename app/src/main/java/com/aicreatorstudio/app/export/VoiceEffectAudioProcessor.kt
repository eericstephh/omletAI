package com.aicreatorstudio.app.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.aicreatorstudio.app.model.VoiceEffectDsp
import com.aicreatorstudio.app.model.VoiceEffectProfile
import com.aicreatorstudio.app.model.VoiceEffects
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 2026-10-13 — runs the **voice changer** DSP inside a Media3 audio chain.
 *
 * One class serves both renderers. The export constructs it with a
 * constant profile; the live preview constructs it with a reader of the
 * currently-playing clip's effect, so selecting a preset is audible on the
 * very next buffer. Neither has its own copy of the signal chain — the
 * arithmetic is [VoiceEffectDsp]'s, and there is exactly one of those.
 *
 * ## Why it never reports itself inactive
 *
 * Media3 asks `isActive()` once, right after `configure()`, and **drops
 * any processor that says no**. `BaseAudioProcessor` answers `true` as
 * soon as an output format is set, which is what makes live retargeting
 * possible at all: a processor that reported `false` while the effect was
 * `NONE` would be removed from the preview's chain permanently, and
 * turning an effect on afterwards would silently do nothing.
 *
 * The cost is one buffer copy per buffer when no effect is selected —
 * about 380 KB/s of `memcpy`, which is not measurable. The benefit is
 * that "no effect" is a genuine pass-through rather than an absent
 * processor, so the preview needs no player rebuild to switch one on.
 *
 * ## Live retargeting
 *
 * [queueInput] re-reads the profile every buffer. When it differs from the
 * one the current [VoiceEffectDsp] was built for, the DSP is rebuilt —
 * filters, LFO and delay line start clean, so switching from «اکو» to
 * «رباتیک» cannot leave an echo tail hanging over the new effect. A
 * rebuild costs three small allocations, once per user tap.
 *
 * ## The chain lane: 16-bit PCM, verified against the shipped bytecode
 *
 * Every processor in this chain runs in **PCM_16BIT**, and that is not
 * a choice — it is what Media3 1.5.0 actually composes (disassembled
 * from `DefaultAudioSink.configure` / `SonicAudioProcessor.configure`,
 * verified 2026-10-14):
 *
 *  * `DefaultAudioSink.configure` pipelines a **16-bit decoder output**
 *    as `[ChannelMapping, Trimming, ToInt16] ++ [custom chain...]` —
 *    `shouldUseFloatOutput` only turns on for *high-resolution* PCM input
 *    (24-bit int / 32-bit float), which an AAC or PCM decoder never
 *    produces, so a `ToFloatPcm` stage ahead of the custom processors
 *    does not happen for ordinary media.
 *  * `SonicAudioProcessor.configure` **throws
 *    `AudioProcessor.UnhandledAudioFormatException` unless its input
 *    encoding is `ENCODING_PCM_16BIT`** (constant 2; every other value
 *    throws).
 *  * Media3 inserts **no converters between custom processors** — its
 *    `ToFloat`/`ToInt16` stages sit at the head of the pipeline, never
 *    in the middle of a user array.
 *
 * The first shipping version of this processor declared `PCM_FLOAT`
 * output on the assumption that "Media3 inserts its own converters
 * ahead of us for a 16-bit source". It does not, and the pitch
 * `SonicAudioProcessor` the preview appends right behind us rejected
 * the float buffers: every play attempt failed at configure with
 * `AudioSink.ConfigurationException`, surfacing to the user as
 * `MediaCodecAudioRenderer error, index=1, format=audio/raw,
 * format_supported=YES` and a dead Play button.
 *
 * The processor therefore accepts **either** input encoding (16-bit or
 * float), converts to float internally for the DSP, and writes
 * **PCM_16BIT** out — the one encoding every following stage in both
 * the preview chain and the export chain is guaranteed to accept. The
 * scaling matches Media3's own converters exactly: `/ 32768f` going
 * in (that is `ToFloatPcmAudioProcessor`'s constant) and
 * `(clamp(x, -1, 1) * 32767f).toShort()` coming out (that is
 * `ToInt16PcmAudioProcessor`'s).
 *
 * When the effect is `NONE` the pass-through is **bit-exact** for a
 * 16-bit stream: [queueInput] copies the bytes verbatim rather than
 * round-tripping them through float, so an untouched project's preview
 * audio is sample-for-sample what the decoder emitted. (A float input
 * still has to be converted to the declared 16-bit output, with the
 * one-LSB asymmetry of Media3's own converters.)
 *
 * @param profileProvider the effect to apply. Called on the audio thread,
 *   so it must be cheap and thread-safe — the preview's implementation
 *   reads a `@Volatile` field.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VoiceEffectAudioProcessor(
    private val profileProvider: () -> VoiceEffectProfile
) : BaseAudioProcessor() {

    private var dsp: VoiceEffectDsp? = null

    /** The profile [dsp] was built for, so a change can be detected. */
    private var builtProfile: VoiceEffectProfile? = null

    private var sampleRate = 0
    private var channelCount = 0

    /**
     * True when the buffers handed to [queueInput] hold floats. Set in
     * [onConfigure] from the previous stage's declared encoding; the
     * output is PCM_16BIT either way.
     */
    private var inputIsFloat = false

    /**
     * Reused scratch buffer for the float view of the input.
     *
     * Grown, never shrunk: Media3 hands over buffers of a fairly stable
     * size, so after the first few callbacks this allocates nothing.
     */
    private var scratch = FloatArray(0)

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        // The lane every following stage in both chains accepts is
        // 16-bit: the preview's pitch `SonicAudioProcessor` throws on
        // anything else (verified against the 1.5.0 bytecode), and the
        // export's speed/fade stages are 16-bit too. A float input —
        // which only a high-resolution decoder would produce — is read
        // and converted here, never passed on.
        inputIsFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        outputAudioFormat = AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            C.ENCODING_PCM_16BIT
        )
        // A new format means the coefficients are stale.
        dsp = null
        builtProfile = null
        return outputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        // Bit-exact fast path: a 16-bit stream with no effect selected
        // is copied verbatim, so the preview never touches the samples
        // of a project that never opened the voice panel. (The export
        // never even constructs this processor for `NONE`.)
        if (!inputIsFloat && profileProvider().isIdentity) {
            val out = replaceOutputBuffer(remaining)
            out.order(ByteOrder.LITTLE_ENDIAN)
                .put(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
            out.position(0)
            out.limit(remaining)
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val bytesPerSample = if (inputIsFloat) 4 else 2
        val sampleCount = remaining / bytesPerSample
        if (sampleCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
        val samples = scratch

        if (inputIsFloat) {
            inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(samples, 0, sampleCount)
        } else {
            // Media3's own short -> float constant, so the DSP sees
            // exactly the samples a float chain would have given it.
            val shorts = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            for (i in 0 until sampleCount) samples[i] = shorts.get(i) / 32768f
        }

        // Rebuild first: the profile may have changed since the last
        // buffer (the user tapped a preset), and the new effect must not
        // inherit the previous one's filter or delay state.
        dspFor(profileProvider()).processInPlace(samples, sampleCount)

        val outBuffer = replaceOutputBuffer(sampleCount * 2)
        outBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            // Media3's own float -> short constant, so the following
            // stages see exactly what a 16-bit source would have produced.
            val clamped = samples[i].coerceIn(-1f, 1f)
            outBuffer.putShort((clamped * 32767f).toInt().toShort())
        }
        outBuffer.position(0)
        outBuffer.limit(sampleCount * 2)

        // The entire input has been consumed.
        inputBuffer.position(inputBuffer.limit())
    }

    /** Returns the DSP for [profile], rebuilding it only when it changed. */
    private fun dspFor(profile: VoiceEffectProfile): VoiceEffectDsp {
        val current = dsp
        if (current != null && builtProfile == profile) return current
        val fresh = VoiceEffectDsp(
            profile = profile,
            sampleRateHz = sampleRate,
            channelCount = channelCount
        )
        dsp = fresh
        builtProfile = profile
        return fresh
    }

    /**
     * A flush is a stream restart (a seek, a new item, a loop), so the
     * filters and the delay line must not carry over. Without this, an
     * echo tail would spill across a cut.
     */
    override fun onFlush() {
        dsp?.reset()
    }

    override fun onReset() {
        dsp = null
        builtProfile = null
        scratch = FloatArray(0)
    }

    companion object {
        /**
         * The processor to append for [effect], or `null` when there is
         * nothing to do.
         *
         * Returning `null` for [com.aicreatorstudio.app.model.VoiceEffect.NONE]
         * is what keeps an untouched project's export **byte-identical**
         * to the file this feature shipped against: no processor, no
         * buffer copy, no rounding.
         *
         * @param sampleRateHz only used to clamp the band edges, so a
         *   wrong value degrades the sound rather than breaking it.
         */
        fun build(effect: com.aicreatorstudio.app.model.VoiceEffect, sampleRateHz: Int = 48_000):
            AudioProcessor? {
            if (effect.isIdentity) return null
            val profile = VoiceEffects.profileFor(effect, sampleRateHz)
            if (profile.isIdentity) return null
            return VoiceEffectAudioProcessor { profile }
        }
    }
}
