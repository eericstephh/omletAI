package com.aicreatorstudio.app.ai.engines

import android.content.Context
import android.net.Uri
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolResult
import com.aicreatorstudio.app.ai.AudioExtractor
import com.aicreatorstudio.app.model.VoiceCleanSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * **Spectral gating DSP** — the pure-Kotlin denoising core.
 *
 * 2026-10-20 — this class used to implement [AiToolEngine] with the
 * id `"voiceclean"`, the SAME id as the registered
 * [RnnoiseVoiceCleanEngine], and wrote its result to a **`.pcm`** file
 * while the registered engine writes a **`.wav`**. It was never
 * registered with Hilt, so the duplicate engine could never run — but
 * the dead code was a trap: adding it to the DI list would have made
 * the export look for a `.wav` that the engine never wrote, or mux a
 * headerless PCM as if it were a WAV.
 *
 * The class is now what it actually is: a DSP helper that
 * [RnnoiseVoiceCleanEngine] uses as its fallback backend. It has one
 * public behaviour — [spectralGate] — and one cache contract, the
 * registered engine's.
 *
 * ## What the algorithm does
 *
 * Classic MMSE spectral subtraction with a gain floor:
 *
 * ```
 * gain(ω) = max(gMin, 1 − α · N(ω) / |Y(ω)|)
 * X̂(ω)    = gain(ω) · Y(ω)
 * ```
 *
 * `N(ω)` is the per-bin noise magnitude estimated from the first
 * ~500 ms of the clip, `α` is the over-subtraction factor driven by
 * the user's strength slider, and `gMin = 0.05` keeps a small floor
 * so a silent frame is not fully muted.
 *
 * ## Limitations
 *
 * - Steady-state noise (fan, traffic, hum) → very good.
 * - Transient noise (dog bark, door slam) → reduced but audible.
 * - Slight "musical noise" artefacts may appear at very high
 *   strengths; capped by the `gMin` floor.
 * - A clip that STARTS with speech has a poor noise profile (the
 *   "noise" measured is the speech). Trim a leading edge in the Trim
 *   panel first.
 */
@Singleton
class SpectralGateVoiceClean @Inject constructor() {

    // ----------------------------------------------------------------
    // Spectral gating core — pure-Kotlin, unit-testable
    // ----------------------------------------------------------------

    /**
     * Apply MMSE spectral gating to a mono float PCM buffer.
     *
     * 2026-10-20 — REWRITTEN for correctness. The previous version had
     * three defects that made the fallback backend behave differently
     * from the algorithm it documents:
     *
     *  1. **The rate was assumed, not declared.** The noise profile is
     *     estimated over `NOISE_PROFILE_MS` using
     *     `AudioExtractor.SAMPLE_RATE` (16 kHz) — but the engine fed
     *     this function **48 kHz** audio, so the "500 ms" profile
     *     actually covered ~165 ms and the 512-sample frame was 10.6 ms
     *     instead of 32 ms. The sample rate is now a parameter.
     *  2. **The tail was dropped.** The loop `break`s when a frame
     *     would run past the end, so the last partial window was never
     *     written — the tail of every clip came out silent. The input
     *     is now padded to a whole number of frames.
     *  3. **A clip shorter than one frame came out as pure silence and
     *     was still reported as a success.** The old code produced an
     *     all-zero buffer and returned it. The padding above fixes the
     *     length; a genuinely empty input now fails loudly.
     *
     * The function also returns the per-frame **speech probability**
     * so the caller can gate its blend on real voice activity instead
     * of a hard-coded constant.
     *
     * @param samples mono float PCM in `[-1, 1]`.
     * @param strength over-subtraction factor 0..1. Higher = more
     *   aggressive noise removal. Internally mapped to `α = 1..4`.
     * @param sampleRate the rate [samples] was captured at. The noise
     *   profile window and the frame duration are derived from it.
     * @param onFrame called every 32 frames with `(frameIdx, totalFrames)`
     *   so the caller can publish progress / cooperative cancel.
     * @return the gated signal and one speech probability per frame.
     */
    internal fun spectralGate(
        samples: FloatArray,
        strength: Float,
        sampleRate: Int = AudioExtractor.SAMPLE_RATE,
        onFrame: (Int, Int) -> Unit = { _, _ -> }
    ): GateResult {
        if (samples.isEmpty()) {
            throw IllegalArgumentException("spectral gate: empty input")
        }
        val frameSize = FRAME_SIZE
        val hopSize = HOP_SIZE
        val window = hannWindow(frameSize)
        val fftSize = frameSize
        val fft = FFT(fftSize)
        val alpha = 1f + 3f * strength // 1..4
        val gainFloor = 0.05f

        // 2026-10-20 — pad to a whole number of frames so the TAIL is
        // processed instead of being dropped, and so a clip shorter
        // than one frame still produces output.
        val totalFrames = ((samples.size + hopSize - 1) / hopSize).coerceAtLeast(1)
        val paddedLen = totalFrames * hopSize + frameSize
        val padded = samples.copyOf(paddedLen.coerceAtLeast(samples.size))

        val noiseProfile = estimateNoiseProfile(padded, frameSize, hopSize, fft, window, sampleRate)

        val out = FloatArray(paddedLen)
        val windowSum = FloatArray(paddedLen)
        val speechProb = FloatArray(totalFrames)

        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val mag = FloatArray(fftSize / 2 + 1)
        val noiseEnergy = FloatArray(totalFrames)

        var processed = 0
        for (fIdx in 0 until totalFrames) {
            val start = fIdx * hopSize
            if (start + frameSize > padded.size) break
            processed++

            // Window the input frame.
            for (i in 0 until frameSize) {
                re[i] = padded[start + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)

            // Compute magnitudes.
            var frameEnergy = 0f
            for (i in mag.indices) {
                mag[i] = kotlin.math.sqrt(re[i] * re[i] + im[i] * im[i])
                frameEnergy += mag[i] * mag[i]
            }
            frameEnergy /= mag.size
            noiseEnergy[fIdx] = frameEnergy

            // Apply gain in place. We mutate `re` / `im` directly so the
            // inverse FFT picks up the gated spectrum.
            for (i in mag.indices) {
                val n = noiseProfile[i]
                val gain = if (mag[i] < 1e-9f) gainFloor
                else max(gainFloor, 1f - alpha * n / mag[i])
                re[i] *= gain
                im[i] *= gain
                val mirror = fftSize - i
                if (mirror < fftSize && mirror != i) {
                    // Hermitian symmetry — apply same gain to the mirrored bin.
                    // The mirror bin's value is just `re[mirror]` so this
                    // is an out-of-order mutation, but the original mag[]
                    // is already captured above and we read gain from the
                    // unmirrored index.
                    re[mirror] *= gain
                    im[mirror] *= gain
                }
            }

            fft.inverse(re, im)

            // Overlap-add the inverse-windowed frame.
            for (i in 0 until fftSize) {
                val pos = start + i
                if (pos < out.size) {
                    out[pos] += re[i] * window[i]
                    windowSum[pos] += window[i] * window[i]
                }
            }

            if ((fIdx and 31) == 0) onFrame(fIdx, totalFrames)
        }
        onFrame(totalFrames, totalFrames)

        if (processed == 0) {
            // 2026-10-20 — fail loudly instead of returning silence.
            // The old path returned an all-zero buffer for a clip
            // shorter than one frame, and the engine reported
            // "پاک‌سازی صدا روی N ثانیه اعمال شد" for a clip that had
            // been reduced to pure silence.
            throw IllegalStateException("spectral gate: no frame could be processed")
        }

        // Normalize by window sum so the OLA sums to unity.
        for (i in out.indices) {
            val w = windowSum[i]
            if (w > 1e-9f) out[i] /= w
            // Soft clip to keep peak transients from blowing up.
            if (out[i] > 1f) out[i] = 1f
            else if (out[i] < -1f) out[i] = -1f
        }

        // 2026-10-20 — a real per-frame speech probability.
        //
        // The caller blends the original and the denoised signal with
        // this value: speech frames keep more of the original, noise
        // frames take more suppression. The previous fallback passed a
        // CONSTANT 0.5, which disabled the VAD gating entirely and made
        // the fallback behave differently from the neural backend.
        //
        // The estimate is the frame's energy relative to the loudest
        // frame in the clip — a simple, robust "is there signal here"
        // measure that needs no extra model.
        var peakEnergy = 0f
        for (e in noiseEnergy) if (e > peakEnergy) peakEnergy = e
        if (peakEnergy <= 1e-12f) {
            java.util.Arrays.fill(speechProb, 0f)
        } else {
            for (i in noiseEnergy.indices) {
                val rel = noiseEnergy[i] / peakEnergy
                // Map the energy ratio to a probability: quiet frames
                // score low, frames within ~10 dB of the peak score high.
                speechProb[i] = ((rel - VAD_FLOOR_REL) / (VAD_CEILING_REL - VAD_FLOOR_REL))
                    .coerceIn(0f, 1f)
            }
        }

        // Trim back to the original length (the padding must not leak
        // into the output).
        val trimmed = out.copyOf(samples.size.coerceAtMost(out.size))
        return GateResult(trimmed, speechProb)
    }

    /**
     * The gated signal plus one speech probability per analysed frame.
     */
    internal data class GateResult(
        val samples: FloatArray,
        /** Per-frame voice probability in `[0, 1]`. */
        val speechProbability: FloatArray
    )

    /**
     * Estimate the per-bin noise magnitude from the first ~500 ms
     * of audio. This is the simplest MMSE noise tracker — works well
     * when the leading edge of the clip is non-speech. For clips that
     * start with speech the user can simply trim a leading edge in
     * the Trim panel first.
     *
     * 2026-10-20 — the profile window is derived from the ACTUAL
     * sample rate. It used to be computed with a hard-coded 16 kHz
     * while the engine fed this function 48 kHz audio, so the "500 ms"
     * profile actually covered ~165 ms.
     */
    private fun estimateNoiseProfile(
        samples: FloatArray,
        frameSize: Int,
        hopSize: Int,
        fft: FFT,
        window: FloatArray,
        sampleRate: Int
    ): FloatArray {
        val rate = sampleRate.coerceAtLeast(1)
        val noiseFrames = max(1, NOISE_PROFILE_MS * rate / 1000 / hopSize)
        val re = FloatArray(frameSize)
        val im = FloatArray(frameSize)
        val acc = FloatArray(frameSize / 2 + 1)
        var counted = 0
        for (fIdx in 0 until noiseFrames) {
            val start = fIdx * hopSize
            if (start + frameSize > samples.size) break
            for (i in 0 until frameSize) {
                re[i] = samples[start + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            for (i in acc.indices) {
                acc[i] += kotlin.math.sqrt(re[i] * re[i] + im[i] * im[i])
            }
            counted++
        }
        if (counted == 0) return FloatArray(frameSize / 2 + 1) // silent fallback
        for (i in acc.indices) acc[i] /= counted.toFloat()
        return acc
    }

    /** Standard Hann window, symmetric. */
    private fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            // Symmetric form so overlap-add sums to 1.
            w[i] = (0.5f - 0.5f * cos(2.0 * PI * i / (n - 1)).toFloat())
        }
        return w
    }

    /**
     * Compact radix-2 Cooley-Tukey FFT. Operates in-place on the
     * supplied real + imaginary arrays. Size must be a power of two.
     *
     * Not the fastest possible implementation but plenty quick at
     * 512 samples for our use case (~4 ms per 100 frames on a
     * mid-range phone).
     */
    internal class FFT(private val n: Int) {
        init { require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two" } }

        private val bitReverse: IntArray = run {
            val bits = (ln(n.toDouble()) / ln(2.0)).toInt()
            val tbl = IntArray(n)
            for (i in 0 until n) {
                var x = i
                var r = 0
                for (b in 0 until bits) {
                    r = (r shl 1) or (x and 1)
                    x = x shr 1
                }
                tbl[i] = r
            }
            tbl
        }

        fun forward(re: FloatArray, im: FloatArray) {
            transform(re, im, inverse = false)
        }

        fun inverse(re: FloatArray, im: FloatArray) {
            transform(re, im, inverse = true)
            val scale = 1f / n
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }

        private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = this.n
            // Bit-reverse permutation in-place.
            for (i in 0 until n) {
                val j = bitReverse[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val phaseStep = if (inverse) (2.0 * PI / size) else (-2.0 * PI / size)
                val tablePhase = phaseStep
                var i = 0
                while (i < n) {
                    var phase = 0.0
                    for (k in 0 until half) {
                        val wr = cos(phase).toFloat()
                        val wi = sin(phase).toFloat()
                        val a = i + k
                        val b = i + k + half
                        val tr = wr * re[b] - wi * im[b]
                        val ti = wr * im[b] + wi * re[b]
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] = re[a] + tr
                        im[a] = im[a] + ti
                        phase += tablePhase
                    }
                    i += size
                }
                size = size shl 1
            }
        }
    }

    companion object {
        /**
         * 2026-10-20 — the rate the spectral gate is designed for.
         * 512 samples = 32 ms frames, a 500 ms noise profile, and a
         * 256-sample hop. The engine resamples to this rate before
         * gating and back afterwards.
         */
        const val SAMPLE_RATE: Int = 16_000

        /** STFT frame size — 32 ms @ 16 kHz. */
        const val FRAME_SIZE: Int = 512
        /** Hop size — 16 ms @ 16 kHz (50% overlap). */
        const val HOP_SIZE: Int = 256
        /** Length of audio assumed to be noise (no speech) for profile. */
        const val NOISE_PROFILE_MS: Int = 500

        /**
         * 2026-10-20 — the per-frame speech probability is derived from
         * the frame's energy relative to the loudest frame in the clip.
         * A frame below [VAD_FLOOR_REL] of the peak scores 0, a frame
         * at or above [VAD_CEILING_REL] scores 1, and the rest is
         * interpolated.
         */
        internal const val VAD_FLOOR_REL: Float = 0.02f
        internal const val VAD_CEILING_REL: Float = 0.20f

        /** Used by unit tests to verify noise-floor reduction. */
        internal const val MIN_DB_REDUCTION: Float = 6f
    }
}