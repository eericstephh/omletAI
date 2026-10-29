package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.ai.audio.Biquad
import kotlin.math.roundToInt

/**
 * Sample-rate / channel conversion helpers shared by the audio AI
 * tools (Voice Clean's RNNoise path needs 48 kHz mono float; the
 * export muxer needs int16 bytes at the source rate; whisper needs
 * 16 kHz mono float).
 *
 * Pure Kotlin, no Android dependencies — fully unit-testable on
 * the JVM.
 *
 * ## 2026-10-20 ANTI-ALIASING FIX
 *
 * Downsampling used to be bare linear interpolation. Linear
 * interpolation is a *poor* low-pass: its response is only
 * -13 dB at Nyquist of the SOURCE rate, so decimating 48 kHz →
 * 16 kHz folded everything between 8 kHz and 24 kHz straight back
 * into the speech band. On phone audio that band holds the mic's
 * hiss, sibilance and any music bed — all of it landing on top of
 * the 1–4 kHz region the recogniser needs to separate consonants.
 * The measured symptom was misheard Persian words on otherwise
 * clean speech.
 *
 * Every decimation path now runs a 4th-order Butterworth low-pass
 * (-24 dB/octave) at 45 % of the TARGET rate before interpolating,
 * applied streaming so no source-sized float copy is allocated:
 * a 10-minute 48 kHz track is 57 MB as int16 and would be 115 MB
 * as float, which is not affordable while the model is resident.
 */
object AudioResampler {

    /**
     * Anti-alias cutoff as a fraction of the target rate. 0.45
     * leaves a guard band below the target Nyquist (0.5) so the
     * filter's own transition band does not fold back.
     */
    const val ANTI_ALIAS_RATIO: Double = 0.45


    /**
     * int16 little-endian interleaved PCM -> mono float in
     * `[-1, 1]`, preserving the source sample rate. No resampling.
     */
    fun int16BytesToMonoFloat(pcm: ByteArray, channels: Int): FloatArray {
        val channelCount = channels.coerceAtLeast(1)
        if (pcm.isEmpty()) return FloatArray(0)
        val frameCount = pcm.size / 2 / channelCount
        if (frameCount <= 0) return FloatArray(0)
        val out = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channelCount) {
                val byteIndex = (f * channelCount + c) * 2
                if (byteIndex + 1 < pcm.size) {
                    val low = pcm[byteIndex].toInt() and 0xFF
                    val high = pcm[byteIndex + 1].toInt()
                    sum += (high shl 8 or low).toShort().toInt()
                }
            }
            out[f] = (sum.toFloat() / channelCount) / 32768f
        }
        return out
    }

    /**
     * Mono float -> int16 little-endian bytes with a soft limiter:
     * samples beyond `[-1, 1]` are compressed with `tanh`-style
     * soft clipping instead of hard-clipped, so denoised peaks
     * never wrap or crackle.
     */
    fun monoFloatToInt16Bytes(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val soft = softLimit(samples[i])
            val s = (soft * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Resample mono float from [fromRate] to [toRate] with linear
     * interpolation, preceded by an anti-alias low-pass when the
     * conversion is a decimation. Length is scaled by
     * `toRate / fromRate` (rounded); a 0-length input yields a
     * 0-length output.
     */
    fun resampleMonoFloat(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        if (samples.isEmpty()) return FloatArray(0)
        if (fromRate == toRate) return samples.copyOf()
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLen = (samples.size * ratio).roundToInt().coerceAtLeast(1)
        val filters = if (fromRate > toRate) {
            Biquad.cascade(fromRate.toDouble(), toRate * ANTI_ALIAS_RATIO, 4, lowPass = true)
        } else {
            null
        }
        return decimateFloat(samples, outLen, ratio, filters)
    }

    /**
     * int16 mono → float at [toRate], with the same anti-alias
     * guarantee as [resampleMonoFloat] but WITHOUT materialising a
     * source-rate float copy. This is the whisper path: a 10-minute
     * 48 kHz track would otherwise need a 115 MB intermediate.
     */
    fun resampleShortMonoToFloat(samples: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive" }
        if (samples.isEmpty()) return FloatArray(0)
        if (fromRate == toRate) return FloatArray(samples.size) { samples[it] / 32768f }
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outLen = (samples.size / (fromRate.toDouble() / toRate)).toInt().coerceAtLeast(1)
        val filters = if (fromRate > toRate) {
            Biquad.cascade(fromRate.toDouble(), toRate * ANTI_ALIAS_RATIO, 4, lowPass = true)
        } else {
            null
        }
        return decimateShort(samples, outLen, ratio, filters)
    }

    /**
     * Shared decimation loop for float sources.
     *
     * The filter runs while the source cursor advances, so each
     * source sample is filtered exactly once and only two filtered
     * values are kept — enough because the requested source index
     * is monotonically non-decreasing.
     */
    private fun decimateFloat(
        src: FloatArray,
        outLen: Int,
        ratio: Double,
        filters: Array<Biquad>?
    ): FloatArray {
        val out = FloatArray(outLen)
        var next = 0
        var i0 = -2
        var v0 = 0f
        var i1 = -1
        var v1 = 0f

        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idxA = srcPos.toInt().coerceIn(0, src.size - 1)
            val idxB = (idxA + 1).coerceIn(0, src.size - 1)
            val frac = (srcPos - idxA).coerceIn(0.0, 1.0).toFloat()

            while (next <= idxA) {
                var v = src[next]
                if (filters != null) for (f in filters) v = f.process(v)
                i0 = i1; v0 = v1
                i1 = next; v1 = v
                next++
            }
            val a = if (idxA == i1) v1 else if (idxA == i0) v0 else v1
            while (next <= idxB) {
                var v = src[next]
                if (filters != null) for (f in filters) v = f.process(v)
                i0 = i1; v0 = v1
                i1 = next; v1 = v
                next++
            }
            val b = if (idxB == i1) v1 else if (idxB == i0) v0 else v1
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /** int16 twin of [decimateFloat] — same algorithm, no float copy. */
    private fun decimateShort(
        src: ShortArray,
        outLen: Int,
        ratio: Double,
        filters: Array<Biquad>?
    ): FloatArray {
        val out = FloatArray(outLen)
        var next = 0
        var i0 = -2
        var v0 = 0f
        var i1 = -1
        var v1 = 0f

        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idxA = srcPos.toInt().coerceIn(0, src.size - 1)
            val idxB = (idxA + 1).coerceIn(0, src.size - 1)
            val frac = (srcPos - idxA).coerceIn(0.0, 1.0).toFloat()

            while (next <= idxA) {
                var v = src[next] / 32768f
                if (filters != null) for (f in filters) v = f.process(v)
                i0 = i1; v0 = v1
                i1 = next; v1 = v
                next++
            }
            val a = if (idxA == i1) v1 else if (idxA == i0) v0 else v1
            while (next <= idxB) {
                var v = src[next] / 32768f
                if (filters != null) for (f in filters) v = f.process(v)
                i0 = i1; v0 = v1
                i1 = next; v1 = v
                next++
            }
            val b = if (idxB == i1) v1 else if (idxB == i0) v0 else v1
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /**
     * Upsample a per-frame gain envelope (one value per
     * [frameSamples]-sample frame at [envelopeRate]) to a
     * per-sample envelope at [targetRate] with linear
     * interpolation between frame centers. Used to apply RNNoise's
     * 10 ms gain decisions to source-rate PCM without zipper noise.
     */
    fun upsampleGainEnvelope(
        gains: FloatArray,
        frameSamples: Int,
        envelopeRate: Int,
        targetSamples: Int,
        targetRate: Int
    ): FloatArray {
        if (gains.isEmpty() || targetSamples <= 0) return FloatArray(targetSamples.coerceAtLeast(0))
        val out = FloatArray(targetSamples)
        // Frame centers in envelope-rate samples, mapped to target.
        val rateRatio = targetRate.toDouble() / envelopeRate.toDouble()
        for (i in 0 until targetSamples) {
            val posFrames = (i / rateRatio) / frameSamples - 0.5
            val idxA = posFrames.toInt().coerceIn(0, gains.size - 1)
            val idxB = (idxA + 1).coerceIn(0, gains.size - 1)
            val frac = (posFrames - posFrames.toInt()).coerceIn(0.0, 1.0).toFloat()
            out[i] = gains[idxA] + (gains[idxB] - gains[idxA]) * frac
        }
        return out
    }

    /**
     * Apply a per-sample gain envelope to interleaved int16 PCM
     * (any channel count), returning new int16 bytes. Gains are
     * clamped to `[0, 1]`; output is soft-limited so boosted peaks
     * (gain can exceed... no — clamped) never clip.
     */
    fun applyGainEnvelopeToInt16(
        pcm: ByteArray,
        channels: Int,
        envelope: FloatArray
    ): ByteArray {
        val channelCount = channels.coerceAtLeast(1)
        val frameCount = if (pcm.isEmpty()) 0 else pcm.size / 2 / channelCount
        val out = ByteArray(frameCount * channelCount * 2)
        for (f in 0 until frameCount) {
            val g = envelope.getOrElse(f) { 1f }.coerceIn(0f, 1f)
            for (c in 0 until channelCount) {
                val byteIndex = (f * channelCount + c) * 2
                var s = 0
                if (byteIndex + 1 < pcm.size) {
                    val low = pcm[byteIndex].toInt() and 0xFF
                    val high = pcm[byteIndex + 1].toInt()
                    s = (high shl 8 or low).toShort().toInt()
                }
                val v = softLimit((s / 32768f) * g)
                val q = (v * 32767f).roundToInt().coerceIn(-32768, 32767)
                val o = (f * channelCount + c) * 2
                out[o] = (q and 0xFF).toByte()
                out[o + 1] = ((q shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * Peak-normalize float PCM to [peak] (default 0.98) when the
     * current peak exceeds it; quieter signals pass through
     * untouched. Prevents accidental silence-to-loud jumps after
     * denoising without compressing normal dynamics.
     */
    fun normalizePeak(samples: FloatArray, peak: Float = 0.98f): FloatArray {
        var maxAbs = 0f
        for (s in samples) {
            val a = kotlin.math.abs(s)
            if (a > maxAbs) maxAbs = a
        }
        if (maxAbs <= peak || maxAbs < 1e-6f) return samples
        val k = peak / maxAbs
        for (i in samples.indices) samples[i] *= k
        return samples
    }

    /**
     * RMS energy of a float buffer. Used by tests to assert that
     * denoising actually attenuates noise-only regions.
     */
    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        val end = to.coerceAtMost(samples.size)
        if (end <= from) return 0f
        var acc = 0.0
        for (i in from until end) acc += samples[i] * samples[i]
        return kotlin.math.sqrt(acc / (end - from)).toFloat()
    }

    private fun softLimit(x: Float): Float {
        // Hard knee, C0-continuous at ±1: identity inside [-1, 1],
        // clamped to ±1 outside. Prevents int16 wrap-around on
        // denoised peaks; the knee is hit only by transient
        // overshoots so no audible pumping.
        val a = kotlin.math.abs(x)
        if (a <= 1f) return x
        return if (x > 0f) 1f else -1f
    }
}
