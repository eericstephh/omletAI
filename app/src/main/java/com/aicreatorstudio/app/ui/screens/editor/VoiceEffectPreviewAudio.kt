package com.aicreatorstudio.app.ui.screens.editor

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.aicreatorstudio.app.export.VoiceEffectAudioProcessor
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.model.VoiceEffectProfile
import com.aicreatorstudio.app.model.VoiceEffects

/**
 * 2026-10-13 — the live preview's half of the **Voice Changer**.
 *
 * ## The problem this solves
 *
 * `ExoPlayer` has no public "give me your audio effects" hook, and
 * `DefaultRenderersFactory` has no `setAudioProcessors`. The only seam is
 * [DefaultRenderersFactory.buildAudioSink], which the player calls once to
 * create its sink — so that is where the voice processors are attached.
 *
 * ## Parity is structural here too
 *
 * The processors in this array are the **same two objects** the export
 * builds, in the **same order**:
 *
 * ```
 *   VoiceEffectAudioProcessor  ->  SonicAudioProcessor(pitch)
 * ```
 *
 * and Media3 appends the speed stage after them (`DefaultAudioProcessorChain`
 * is built as `[custom..., silenceSkip, sonic]`). The export's chain is
 * `volume -> voice -> pitch -> speed -> fade`. Both therefore run
 * **timbre, then pitch, then rate**, on source-rate audio. That ordering is
 * the whole reason the exported file and the preview agree about what an
 * effect sounds like on a speed-ramped clip.
 *
 * The pitch is deliberately *not* routed through
 * `PlaybackParameters.pitch`: doing that would have put the pitch stage
 * **after** the rate change in the preview while the export applies it
 * **before**, and the two would diverge on exactly the clips where it is
 * hardest to spot. Leaving `PlaybackParameters` untouched also means the
 * existing speed-ramp preview behaviour is not disturbed at all.
 *
 * ## The chain lane is 16-bit PCM
 *
 * [VoiceEffectAudioProcessor] declares `ENCODING_PCM_16BIT` output and
 * [SonicAudioProcessor] **only accepts** 16-bit input — its `configure`
 * throws `UnhandledAudioFormatException` on anything else (read out of
 * the Media3 1.5.0 bytecode, 2026-10-14). `DefaultAudioSink` puts its
 * `ToInt16`/`ToFloat` converters at the *head* of the pipeline, never
 * between processors handed to `setAudioProcessors`, so the array below
 * must be 16-bit from end to end. Changing either processor's declared
 * encoding kills every play attempt with a renderer error — which is
 * exactly how this feature's first build behaved on the user's device.
 *
 * ## Why the processors are always attached
 *
 * Media3 drops a processor that reports itself inactive, once, at
 * `configure()` time — so a processor that declined while the effect was
 * `NONE` would be gone for the rest of the session and turning an effect
 * on afterwards would silently do nothing. Both processors therefore stay
 * in the chain permanently and pass audio through unchanged while the
 * effect is off (see [VoiceEffectAudioProcessor]'s KDoc). The cost is one
 * buffer copy; the alternative is a feature that only works if the user
 * picks an effect before pressing play.
 */
@UnstableApi
internal class VoiceEffectPreviewBridge {

    /** What the DSP processor reads, on the audio thread. */
    @Volatile
    private var profile: VoiceEffectProfile = VoiceEffects.profileFor(VoiceEffect.NONE)

    /**
     * The timbre stages. Reads [profile] on every buffer, so a preset tap
     * is audible on the next one — no player rebuild, no seek.
     */
    private val voiceProcessor: AudioProcessor = VoiceEffectAudioProcessor { profile }

    /**
     * The pitch stage. `setPitch` is a live setter — Media3 itself calls
     * it from the app thread whenever `PlaybackParameters` changes — so
     * retargeting it here is the supported way to change pitch at runtime.
     */
    private val pitchProcessor = SonicAudioProcessor().apply { setPitch(1f) }

    /** Handed to [DefaultAudioSink.Builder.setAudioProcessors]. */
    val processors: Array<AudioProcessor> = arrayOf(voiceProcessor, pitchProcessor)

    /** The effect currently being previewed. Read by the screen's effects. */
    val current: VoiceEffectProfile get() = profile

    /**
     * Retargets both stages at [effect].
     *
     * Called from a `LaunchedEffect` keyed on the playing clip's effect,
     * i.e. on the main thread, which is where Media3 expects processor
     * parameters to be set.
     */
    fun set(effect: VoiceEffect) {
        profile = VoiceEffects.profileFor(effect)
        pitchProcessor.setPitch(VoiceEffects.clampPitch(effect.pitchFactor))
    }
}

/**
 * Builds preview players whose audio runs through the voice changer.
 *
 * [buildAudioSink] is overridden to **mirror the platform default exactly**
 * and then add the processors. The default in Media3 1.5.0 is
 * `DefaultAudioSink.Builder(context).setEnableFloatOutput(..).setEnableAudioTrackPlaybackParams(..).build()`
 * — no more, no less (verified against the shipped bytecode). Passing the
 * same two flags straight through is what keeps the rest of the preview's
 * audio behaviour, including its speed handling, identical to before this
 * feature existed.
 */
@UnstableApi
internal class VoiceEffectRenderersFactory(
    context: Context,
    private val bridge: VoiceEffectPreviewBridge
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(bridge.processors)
        .build()
}
