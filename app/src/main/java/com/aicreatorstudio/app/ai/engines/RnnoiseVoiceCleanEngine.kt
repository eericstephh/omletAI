package com.aicreatorstudio.app.ai.engines

import android.content.Context
import android.net.Uri
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolResult
import com.aicreatorstudio.app.ai.AudioExtractor
import com.aicreatorstudio.app.ai.AudioResampler
import com.aicreatorstudio.app.ai.RnnoiseNative
import com.aicreatorstudio.app.ai.RnnoiseSession
import com.aicreatorstudio.app.model.VoiceCleanSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * **Voice Clean** — real neural speech enhancement.
 *
 * Primary backend is **RNNoise** (Xiph, BSD): a recurrent neural
 * network (GRU) that estimates per-band suppression gains from
 * pitch + spectral features. The pre-trained 8-bit weights are
 * baked into `librnnoise.so`, so inference is fully offline with
 * no model download. Frames are 10 ms @ 48 kHz mono; the GRU
 * hidden state streams across the whole clip in one session.
 *
 * Fallback backend is the in-house MMSE spectral gate
 * ([SpectralGateVoiceClean]), used only when the native library
 * cannot load (e.g. a stripped ABI build). Both backends share
 * the gain-envelope output path below, so the cache contract and
 * the export muxer never care which backend ran.
 *
 * ## Gain-envelope design (why stereo/duration are preserved)
 *
 * RNNoise wants 48 kHz mono, but the clip may be 44.1 kHz stereo.
 * Instead of replacing the audio with resampled mono, the engine:
 *
 * 1. Decodes the original int16 PCM at the source rate/channels.
 * 2. Resamples a mono copy to 48 kHz and runs the neural net.
 * 3. Blends original/denoised 48 kHz by intensity + VAD into a
 *    per-frame scalar gain `rms(mix)/rms(orig)` in `[0, 1]`.
 * 4. Resamples the *gain envelope* (not audio) to the source rate
 *    and multiplies it onto the original stereo PCM.
 *
 * The result: same sample count, same rate, same channels, same
 * duration — only the noise floor moves. Gains never exceed 1, so
 * denoising cannot clip; speech frames keep a floor of the
 * original (VAD-gated blend), so denoising cannot mute speech.
 *
 * ## Intensity
 *
 * `strength` 0..1 (default 0.6 = Medium). Low ≈ 0.35, High ≈ 0.9.
 * The blend is VAD-gated: speech frames keep more original than
 * noise frames at every setting, which is what prevents the
 * "robotic/muffled" failure mode of naive over-subtraction.
 *
 * ## Output
 *
 * A 16-bit WAV at the source rate/channels under
 * `cacheDir/voiceclean/<clipId>.wav` (self-describing, so the
 * export muxer needs no sidecar). The original media is never
 * touched; Reset deletes the WAV and restores Identity settings.
 */
@Singleton
class RnnoiseVoiceCleanEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioExtractor: AudioExtractor,
    private val spectralFallback: SpectralGateVoiceClean
) : AiToolEngine {

    override val id: String = "voiceclean"

    override val displayName: String = "Voice Clean"

    override val modelStatusText: String
        get() = if (RnnoiseNative.isAvailable) {
            "Model: RNNoise GRU (baked-in) · 48 kHz · offline"
        } else {
            "Model: spectral-gate fallback · offline (native RNNoise unavailable)"
        }

    /**
     * Always available: either the neural backend or the spectral
     * fallback can run. No download is ever required.
     */
    override val isAvailable: Boolean = true

    override suspend fun ensureModelReady(onProgress: (Float) -> Unit): Result<Unit> {
        onProgress(1f)
        return Result.success(Unit)
    }

    override suspend fun process(
        clipId: String,
        videoUri: Uri,
        options: AiToolOptions,
        onProgress: (Float) -> Unit
    ): Result<AiToolResult> = withContext(Dispatchers.IO) {
        try {
            onProgress(0f)
            val (pcmBytes, sampleRate, channels) = audioExtractor.decodeFirstAudioTrack(videoUri)
            if (pcmBytes.isEmpty()) {
                return@withContext Result.failure(
                    Exception("هیچ صدایی برای پاک‌سازی در ویدیو پیدا نشد")
                )
            }
            coroutineContext[Job]?.ensureActive()
            onProgress(0.2f)

            val strength = options.strength.coerceIn(0f, 1f).takeIf { it > 0f } ?: 0.6f
            val useNeural = RnnoiseNative.isAvailable
            val algorithm = if (useNeural) ALGORITHM_RNNOISE_V1 else ALGORITHM_SPECTRAL_FALLBACK

            // Mono copy at 48 kHz for the neural net; the original
            // bytes are kept for the gain-application step.
            val monoSrc = AudioResampler.int16BytesToMonoFloat(pcmBytes, channels)
            val mono48 = AudioResampler.resampleMonoFloat(monoSrc, sampleRate, RnnoiseNative.SAMPLE_RATE)
            // PHASE 4 — input conditioning (output-neutral): the gain
            // envelope below applies RATIOS, so boosting the 48 kHz
            // working copy only moves the GRU/VAD into its trained
            // operating point. Final loudness is set by the output
            // stage, never by this step.
            val inputPeakDb = com.aicreatorstudio.app.ai.voice.VoiceNormalizer.peakToDb(
                com.aicreatorstudio.app.ai.voice.VoiceNormalizer.peakOf(mono48)
            )
            val peaksBefore =
                com.aicreatorstudio.app.ai.voice.VoiceNormalizer.waveformPeaks(mono48)
            com.aicreatorstudio.app.ai.voice.VoiceNormalizer.conditionForDenoise(mono48)
            // Conditioned working level: the honest "before" reference
            // for the suppression estimate below.
            val rmsCondDb = com.aicreatorstudio.app.ai.voice.VoiceNormalizer.rmsDb(mono48)
            coroutineContext[Job]?.ensureActive()
            onProgress(0.3f)

            // Run backend in ~2 s chunks for real progress +
            // cooperative cancellation. The RNNoise session persists
            // across chunks so GRU state streams continuously.
            val frameSamples = RnnoiseNative.FRAME_SAMPLES
            val paddedLen = ((mono48.size + frameSamples - 1) / frameSamples) * frameSamples
            val padded48 = mono48.copyOf(paddedLen)
            val denoised48: FloatArray
            val vadPerFrame: FloatArray
            if (useNeural) {
                val out = runNeural(padded48, frameSamples) { done, total ->
                    onProgress(0.3f + 0.45f * (done.toFloat() / total))
                } ?: return@withContext Result.failure(
                    Exception("خطای موتور عصبی حذف نویز؛ دوباره تلاش کنید")
                )
                denoised48 = out.first
                vadPerFrame = out.second
            } else {
                // 2026-10-20 — THE FALLBACK NOW RUNS AT ITS OWN RATE.
                //
                // The spectral gate is designed for 16 kHz (512 samples
                // = 32 ms frames, a 500 ms noise profile). The engine
                // used to hand it 48 kHz audio, so the "500 ms" profile
                // actually covered ~165 ms and each frame was 10.6 ms
                // instead of 32 ms — the fallback did not behave like
                // the algorithm it documents.
                //
                // The gate now runs on a 16 kHz copy and the result is
                // resampled back to 48 kHz, so the gain-envelope
                // machinery below is unchanged.
                val gated16 = AudioResampler.resampleMonoFloat(
                    padded48, RnnoiseNative.SAMPLE_RATE, SpectralGateVoiceClean.SAMPLE_RATE
                )
                val gateResult = withContext(Dispatchers.Default) {
                    spectralFallback.spectralGate(
                        samples = gated16,
                        strength = strength,
                        sampleRate = SpectralGateVoiceClean.SAMPLE_RATE
                    ) { done, total ->
                        // spectralGate progress runs on the calling
                        // thread; post to the outer callback rarely.
                        if ((done and 63) == 0) onProgress(0.3f + 0.45f * (done.toFloat() / total))
                    }
                }
                denoised48 = AudioResampler.resampleMonoFloat(
                    gateResult.samples, SpectralGateVoiceClean.SAMPLE_RATE, RnnoiseNative.SAMPLE_RATE
                )
                // 2026-10-20 — a REAL VAD instead of a constant 0.5.
                //
                // The blend is VAD-gated: speech frames keep more of
                // the original, noise frames take more suppression. A
                // constant 0.5 disabled that entirely, so the fallback
                // behaved differently from the neural backend (whose
                // VAD comes from the GRU).
                //
                // The gate's per-frame speech probability is computed
                // at 16 kHz; the blend runs at 48 kHz, so the
                // probability is resampled to the 48 kHz frame grid.
                val vad16 = gateResult.speechProbability
                val frames48 = paddedLen / frameSamples
                vadPerFrame = FloatArray(frames48) { i ->
                    val idx = (i.toFloat() * vad16.size / frames48.coerceAtLeast(1)).toInt()
                    vad16.getOrElse(idx) { 0.5f }.coerceIn(0f, 1f)
                }
            }
            coroutineContext[Job]?.ensureActive()
            onProgress(0.78f)

            // VAD-gated blend -> per-frame scalar gains.
            val gains48 = withContext(Dispatchers.Default) {
                blendedFrameGains(
                    original = padded48,
                    denoised = denoised48,
                    vad = vadPerFrame,
                    frameSamples = frameSamples,
                    strength = strength
                )
            }
            coroutineContext[Job]?.ensureActive()

            // Resample the gain envelope (not audio) to the source
            // rate and apply to the original stereo/mono PCM.
            val frameCountSrc = pcmBytes.size / 2 / channels.coerceAtLeast(1)
            val gainsSrc = AudioResampler.upsampleGainEnvelope(
                gains = gains48,
                frameSamples = frameSamples,
                envelopeRate = RnnoiseNative.SAMPLE_RATE,
                targetSamples = frameCountSrc,
                targetRate = sampleRate
            )
            val cleanedBytes = AudioResampler.applyGainEnvelopeToInt16(pcmBytes, channels, gainsSrc)
            onProgress(0.9f)

            // PHASE 4 — optional user-facing loudness normalize (quiet
            // speech gets louder). Off by default: legacy callers keep
            // byte-identical behavior. Make-up is capped (+12 dB) and
            // hard-clamped, so it cannot clip and never blasts silence.
            val outputGain = if (options.normalizeOutput) {
                com.aicreatorstudio.app.ai.voice.VoiceNormalizer.normalizeOutputInt16(
                    cleanedBytes,
                    channels,
                    options.normalizeTargetPeak
                )
            } else 1f

            // Preview stats (single passes over buffers we already own).
            //
            // 2026-10-20 — the "after" waveform and the noise-reduction
            // figure are now computed from the signal the user actually
            // HEARS, i.e. the final `cleanedBytes` (post-blend, post-
            // gain, source rate, source channel count).
            //
            // They used to be computed from `denoised48` — the
            // pre-blend, pre-gain, 48 kHz MONO backend output. The
            // panel therefore drew a "before/after" pair that did not
            // correspond to the exported audio, and the reported
            // suppression was overstated (the output keeps part of the
            // original by design).
            val outputSamples = AudioResampler.int16BytesToMonoFloat(cleanedBytes, channels)
            val outputRmsDb = com.aicreatorstudio.app.ai.voice.VoiceNormalizer.rmsDb(outputSamples)
            val peaksAfter =
                com.aicreatorstudio.app.ai.voice.VoiceNormalizer.waveformPeaks(outputSamples)
            val outputPeakDb =
                com.aicreatorstudio.app.ai.voice.VoiceNormalizer.peakOfInt16Db(cleanedBytes)
            val noiseReductionDb =
                com.aicreatorstudio.app.ai.voice.VoiceNormalizer.suppressionDb(
                    rmsCondDb,
                    outputRmsDb
                )
            // 2026-10-20 — the applied gain can be NEGATIVE: the
            // normalizer attenuates an over-hot signal by up to 12 dB.
            // `coerceAtLeast(1f)` mapped every attenuation to 1, so the
            // panel always reported "0 dB applied" even when the
            // output had been pulled down by 12 dB.
            val appliedGainDb = 20f * kotlin.math.log10(outputGain.coerceAtLeast(1e-6f))

            // Write self-describing WAV (rate/channels in header).
            val cacheFile = java.io.File(context.cacheDir, "$CACHE_DIR/$clipId.wav")
            cacheFile.parentFile?.mkdirs()
            writeWav16(cacheFile, cleanedBytes, sampleRate, channels.coerceAtLeast(1))
            coroutineContext[Job]?.ensureActive()
            onProgress(1f)

            val seconds = frameCountSrc / sampleRate.coerceAtLeast(1)
            Result.success(
                AiToolResult(
                    voiceClean = VoiceCleanSettings(
                        enabled = options.commitToClip,
                        strength = strength,
                        algorithm = algorithm
                    ),
                    summary = "پاک‌سازی صدا روی $seconds ثانیه اعمال شد",
                    // PHASE 4 — preview payload (volatile, never persisted).
                    // Computed for every run; legacy callers ignore it.
                    voiceStats = com.aicreatorstudio.app.ai.voice.VoiceEnhancementStats(
                        inputPeakDb = inputPeakDb,
                        outputPeakDb = outputPeakDb,
                        appliedGainDb = appliedGainDb,
                        noiseReductionDb = noiseReductionDb,
                        durationMs = frameCountSrc * 1000L / sampleRate.coerceAtLeast(1),
                        sampleRate = sampleRate,
                        channels = channels.coerceAtLeast(1),
                        algorithm = algorithm,
                        preset = com.aicreatorstudio.app.ai.voice.VoiceEnhancementPreset.Default,
                        waveformBefore = peaksBefore,
                        waveformAfter = peaksAfter
                    )
                )
            )
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun release() {
        // Sessions are per-job and closed by runNeural; nothing cached.
    }

    // ----------------------------------------------------------------
    // Neural backend
    // ----------------------------------------------------------------

    /**
     * Stream [padded48] (length a multiple of 480) through one
     * RNNoise session in chunks. Returns denoised audio (same
     * length) + per-frame VAD, or null when the native call fails.
     */
    private suspend fun runNeural(
        padded48: FloatArray,
        frameSamples: Int,
        onChunk: (doneFrames: Int, totalFrames: Int) -> Unit
    ): Pair<FloatArray, FloatArray>? = withContext(Dispatchers.Default) {
        RnnoiseSession.open()?.use { session ->
            val totalFrames = padded48.size / frameSamples
            val out = FloatArray(padded48.size)
            val vadAll = FloatArray(totalFrames)
            var doneFrames = 0
            var offset = 0
            while (offset < padded48.size) {
                val chunkEnd = min(offset + CHUNK_SAMPLES, padded48.size)
                val chunk = padded48.copyOfRange(offset, chunkEnd)
                val vad = RnnoiseNative.denoiseInPlace(session.handle, chunk)
                    ?: return@withContext null
                chunk.copyInto(out, offset)
                vad.copyInto(vadAll, doneFrames)
                doneFrames += vad.size
                offset = chunkEnd
                onChunk(doneFrames, totalFrames)
                coroutineContext[Job]?.ensureActive()
            }
            out to vadAll
        }
    }

    /**
     * Per-frame scalar suppression gains in `[MIN_GAIN, 1]`.
     * Pure function of the 48 kHz streams — unit-tested on JVM.
     *
     * @param original original 48 kHz mono.
     * @param denoised backend output, same length.
     * @param vad per-frame voice probability in `[0, 1]`.
     */
    internal fun blendedFrameGains(
        original: FloatArray,
        denoised: FloatArray,
        vad: FloatArray,
        frameSamples: Int,
        strength: Float
    ): FloatArray {
        val frames = (original.size / frameSamples).coerceAtLeast(1)
        val gains = FloatArray(frames)
        // Smoothed blend with fast attack / slow release so gains
        // never pump between adjacent frames.
        var smoothBlend = strength * 0.6f
        for (f in 0 until frames) {
            val start = f * frameSamples
            val end = min(start + frameSamples, original.size)
            if (start >= original.size) {
                gains[f] = 1f
                continue
            }
            val v = vad.getOrElse(f) { 0.5f }.coerceIn(0f, 1f)
            // Speech frames keep more original; noise frames take
            // more suppression. strength scales both ends.
            val target = strength * (BLEND_SPEECH + (BLEND_NOISE - BLEND_SPEECH) * (1f - v))
                .coerceIn(0f, 1f)
            val k = if (target > smoothBlend) ATTACK else RELEASE
            smoothBlend += (target - smoothBlend) * k

            var eOrig = 0.0
            var eMix = 0.0
            val n = (end - start).coerceAtLeast(1)
            for (i in start until end) {
                val o = original[i].toDouble()
                val d = denoised[i].toDouble()
                val m = o + (d - o) * smoothBlend
                eOrig += o * o
                eMix += m * m
            }
            eOrig /= n
            eMix /= n
            gains[f] = if (eOrig < 1e-12) {
                1f // digital silence in -> silence out; never NaN.
            } else {
                kotlin.math.sqrt(eMix / eOrig).toFloat().coerceIn(MIN_GAIN, 1f)
            }
        }
        return gains
    }

    private fun writeWav16(file: java.io.File, pcmBytes: ByteArray, sampleRate: Int, channels: Int) {
        val dataLen = pcmBytes.size
        java.io.DataOutputStream(java.io.FileOutputStream(file)).use { out ->
            fun writeIntLE(v: Int) {
                out.writeByte(v and 0xFF)
                out.writeByte((v shr 8) and 0xFF)
                out.writeByte((v shr 16) and 0xFF)
                out.writeByte((v shr 24) and 0xFF)
            }
            fun writeShortLE(v: Int) {
                out.writeByte(v and 0xFF)
                out.writeByte((v shr 8) and 0xFF)
            }
            out.writeBytes("RIFF")
            writeIntLE(36 + dataLen)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeIntLE(16)
            writeShortLE(1) // PCM
            writeShortLE(channels)
            writeIntLE(sampleRate)
            writeIntLE(sampleRate * channels * 2)
            writeShortLE(channels * 2)
            writeShortLE(16)
            out.writeBytes("data")
            writeIntLE(dataLen)
            out.write(pcmBytes)
        }
    }

    companion object {
        const val ALGORITHM_RNNOISE_V1: String = "rnnoise-v1"
        const val ALGORITHM_SPECTRAL_FALLBACK: String = "spectral-gate-mmse-v1"
        /** Subdir under [android.content.Context.getCacheDir]. */
        const val CACHE_DIR: String = "voiceclean"
        /** Samples per native chunk (~2 s @ 48 kHz). */
        const val CHUNK_SAMPLES: Int = 96_000
        /** Blend target on confident speech (fraction of denoised). */
        const val BLEND_SPEECH: Float = 0.6f
        /** Blend target on confident noise (fraction of denoised). */
        const val BLEND_NOISE: Float = 1.0f
        /** Floor so suppression never fully mutes a frame. */
        const val MIN_GAIN: Float = 0.05f
        /** One-pole smoothing: fast attack, slow release. */
        const val ATTACK: Float = 0.35f
        const val RELEASE: Float = 0.06f
    }
}
