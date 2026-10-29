package com.aicreatorstudio.app.ai.voice

import com.aicreatorstudio.app.model.VideoClip
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * PHASE 4 — Local Voice Enhancement contract.
 *
 * Pipeline (all on-device, no network):
 *
 * ```
 * decode (AudioExtractor, source rate/channels)
 *   ↓
 * conditionForDenoise (peak-normalize 48 kHz mono copy; output-neutral:
 *   the gain-envelope design applies RATIOS, so conditioning only moves
 *   the neural net into its trained operating point)
 *   ↓
 * RNNoise GRU denoise (existing RnnoiseNative session, chunked) or the
 * existing spectral-gate fallback when native is unavailable
 *   ↓
 * gain envelope → original PCM (existing engine path, gains ≤ 1)
 *   ↓
 * output loudness normalize (optional, user-facing: quiet speech gets
 * louder; capped so noise floor and clipping stay safe)
 *   ↓
 * WAV cache (voiceclean/<clipId>.wav — the exact file export swaps in)
 *   ↓
 * Preview (stats + waveform, timeline untouched) → Apply → Undo
 * ```
 *
 * Voice clarity EQ was deliberately SKIPPED: no safe existing DSP
 * implementation exists in the repo, and RNNoise + normalization meets
 * the Phase 4 bar without inventing a mastering engine.
 */
object VoiceNormalizer {

    /** Peak the denoiser input is conditioned to (-1 dBFS headroom). */
    const val CONDITION_PEAK: Float = 0.89f

    /** Default output loudness target (-1 dBFS, never clips). */
    const val OUTPUT_TARGET_PEAK: Float = 0.89f

    /** Maximum output make-up gain (+12 dB): quiet speech gets louder,
     *  near-silent noise floors are never blasted. */
    const val MAX_BOOST_GAIN: Float = 3.9810718f // 10^(12/20)

    /** Below this peak the input counts as digital silence: pass through. */
    const val SILENCE_FLOOR: Float = 1e-6f

    /**
     * In-place peak conditioning for the denoiser input. Boosts quiet
     * recordings toward [CONDITION_PEAK] so RNNoise/VAD see speech at
     * the level they were trained on; leaves loud audio untouched.
     * Returns what was applied (for preview display). Never throws.
     */
    fun conditionForDenoise(samples: FloatArray): ConditionStats {
        if (samples.isEmpty()) return ConditionStats(1f, Float.NEGATIVE_INFINITY)
        var peak = 0f
        for (v in samples) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        if (peak < SILENCE_FLOOR || peak >= CONDITION_PEAK) {
            return ConditionStats(1f, peakToDb(peak))
        }
        val gain = (CONDITION_PEAK / peak).coerceAtMost(MAX_BOOST_GAIN)
        for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        return ConditionStats(gain, peakToDb(peak))
    }

    /**
     * In-place loudness normalize of int16 LE PCM bytes (the final
     * cleaned mix): peak toward [targetPeak], make-up capped at
     * [MAX_BOOST_GAIN], hard-clamped to int16 range (cannot clip).
     * Length-preserving. Returns applied gain (1 = untouched).
     */
    fun normalizeOutputInt16(
        pcmBytes: ByteArray,
        channels: Int,
        targetPeak: Float = OUTPUT_TARGET_PEAK
    ): Float {
        if (pcmBytes.size < 2) return 1f
        val ch = channels.coerceAtLeast(1)
        val frames = pcmBytes.size / 2 / ch
        if (frames <= 0) return 1f
        var peak = 0
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            var s = (high shl 8) or low
            if (s > 32767) s -= 65536
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
            i += 2
        }
        val target = (targetPeak.coerceIn(0.01f, 0.99f) * 32767f).toInt().coerceAtLeast(1)
        if (peak <= 0 || peak >= target) return 1f
        val gain = (target.toFloat() / peak).coerceAtMost(MAX_BOOST_GAIN)
        if (gain <= 1f) return 1f
        var j = 0
        while (j + 1 < pcmBytes.size) {
            val low = pcmBytes[j].toInt() and 0xFF
            val high = pcmBytes[j + 1].toInt()
            var s = (high shl 8) or low
            if (s > 32767) s -= 65536
            var v = (s * gain).toInt().coerceIn(-32768, 32767)
            pcmBytes[j] = (v and 0xFF).toByte()
            pcmBytes[j + 1] = ((v shr 8) and 0xFF).toByte()
            j += 2
        }
        return gain
    }

    /** Max-abs waveform buckets for preview bars. Pure, single pass. */
    fun waveformPeaks(samples: FloatArray, buckets: Int = 64): List<Float> {
        if (samples.isEmpty() || buckets <= 0) return emptyList()
        val out = FloatArray(buckets)
        val perBucket = samples.size.toDouble() / buckets
        for (b in 0 until buckets) {
            val from = (b * perBucket).toInt().coerceIn(0, samples.size)
            val to = ((b + 1) * perBucket).toInt().coerceIn(from + 1, samples.size)
            var peak = 0f
            for (k in from until to) {
                val a = kotlin.math.abs(samples[k])
                if (a > peak) peak = a
            }
            out[b] = peak.coerceIn(0f, 1f)
        }
        return out.toList()
    }

    fun peakToDb(peak: Float): Float =
        if (peak <= 0f) Float.NEGATIVE_INFINITY else (20f * log10(peak))

    /** Max abs sample of a float buffer. */
    fun peakOf(samples: FloatArray): Float {
        var peak = 0f
        for (v in samples) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        return peak
    }

    /** Peak dB of int16 LE PCM bytes (actual file-level loudness). */
    fun peakOfInt16Db(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 2) return Float.NEGATIVE_INFINITY
        var peak = 0
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            var s = (high shl 8) or low
            if (s > 32767) s -= 65536
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
            i += 2
        }
        return peakToDb(peak / 32768f)
    }

    fun rmsDb(samples: FloatArray): Float {
        if (samples.isEmpty()) return Float.NEGATIVE_INFINITY
        var sum = 0.0
        for (v in samples) sum += v * v
        val rms = sqrt(sum / samples.size).toFloat()
        return if (rms <= 0f) Float.NEGATIVE_INFINITY else (20f * log10(rms))
    }

    /** Estimated suppression in dB from pre/post RMS. Clamped, labelled estimate. */
    fun suppressionDb(beforeRmsDb: Float, afterRmsDb: Float): Float {
        if (!beforeRmsDb.isFinite() || !afterRmsDb.isFinite()) return 0f
        return (beforeRmsDb - afterRmsDb).coerceIn(0f, 24f)
    }
}

data class ConditionStats(val appliedGain: Float, val inputPeakDb: Float)

/**
 * PHASE 4 presets. All presets normalize output (that is the point of
 * the phase); they differ only in denoise aggression. Technical DSP
 * numbers stay here — the panel shows Persian labels only.
 */
enum class VoiceEnhancementPreset(
    val strength: Float,
    val persianLabel: String,
    val description: String
) {
    NATURAL(
        strength = 0.45f,
        persianLabel = "طبیعی",
        description = "نرمال‌سازی ملایم + حذف نویز سبک"
    ),
    CLEAR_VOICE(
        strength = 0.65f,
        persianLabel = "صدای شفاف",
        description = "نرمال‌سازی + حذف نویز متوسط"
    ),
    STRONG_CLEANUP(
        strength = 0.85f,
        persianLabel = "پاک‌سازی قوی",
        description = "نرمال‌سازی کنترل‌شده + حذف نویز قوی"
    );

    companion object {
        val Default = CLEAR_VOICE
    }
}

/** Structured preview/apply payload. Volatile — never persisted. */
data class VoiceEnhancementStats(
    val inputPeakDb: Float,
    val outputPeakDb: Float,
    val appliedGainDb: Float,
    /** Estimated noise-floor reduction (labelled تخمینی in UI). */
    val noiseReductionDb: Float,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val algorithm: String,
    val preset: VoiceEnhancementPreset,
    val waveformBefore: List<Float> = emptyList(),
    val waveformAfter: List<Float> = emptyList()
)

/**
 * Identity of the audio a preview was generated from. Rejects stale
 * previews (changed clip/trim/source/timeline) before Apply — same
 * principle as Phase 2's previewSourceKey, scoped to one clip and
 * including the URI (voice cares about the exact source bytes).
 */
fun voiceSourceKey(clip: VideoClip): String =
    "${clip.id}|${clip.uri}|${clip.trimStartMs}|${clip.trimEndMs}|" +
        "${clip.durationMs}|${clip.timelineStartMs}"

/** Memory guard: refuse enhancement beyond this (decode needs ~2 full buffers). */
const val MAX_ENHANCE_DURATION_MS: Long = 15L * 60L * 1000L
