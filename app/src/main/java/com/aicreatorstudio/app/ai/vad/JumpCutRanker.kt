package com.aicreatorstudio.app.ai.vad

import com.aicreatorstudio.app.model.VideoClip

/**
 * PHASE 3 — Local Smart Jump Cut ranking layer.
 *
 * Sits strictly between silence analysis and the Phase 2 preview/apply
 * system — no new cut engine, no media work, no network:
 *
 * ```
 * SilenceCutter.analyzeClips (VAD, on-device)
 *   ↓
 * JumpCutRanker.rank (pure scoring, this file)
 *   ↓
 * Phase 2 Preview (per-cut accept/reject, existing)
 *   ↓
 * SilenceCutter.filterSelected + buildCutClips (existing apply)
 * ```
 *
 * Only signals already present in [SilenceSegment] are used
 * (duration + VAD confidence + position in clip). No semantic model,
 * no subtitle dependency, no frame decoding.
 */
object JumpCutRanker {

    /**
     * Score + rank silence analyses into jump-cut candidates.
     *
     * Deterministic: same input always yields the same order
     * (score desc, duration desc, clipId, startMs). Pure — safe for
     * hundreds of candidates; only the top [JumpCutConfig.maxCandidates]
     * survive, the rest are dropped before preview.
     */
    fun rank(
        analyses: List<SilenceCutter.ClipSilenceAnalysis>,
        config: JumpCutConfig = JumpCutConfig()
    ): List<RankedClip> {
        if (analyses.isEmpty()) return emptyList()

        val all = mutableListOf<Triple<SilenceCutter.ClipSilenceAnalysis, SilenceSegment, Scored>>()
        for (a in analyses) {
            // Normalize first (clamp + merge), then enforce the minimum:
            // the VAD mapper usually guarantees it, but rank() must stay
            // safe for direct callers too. Never cut micro-pauses.
            val normalized = SilenceSegmentMapper.normalize(a.segments, a.clipDurationMs)
                .filter { it.durationMs >= config.minSilenceMs }
            for (seg in normalized) {
                all.add(Triple(a, seg, score(seg, a.clipDurationMs, config)))
            }
        }
        // Global top-N cap BEFORE per-clip regrouping, so a 30-minute
        // compilation with hundreds of pauses never floods the preview.
        val kept = all.sortedWith(
            compareByDescending<Triple<SilenceCutter.ClipSilenceAnalysis, SilenceSegment, Scored>> { it.third.score }
                .thenByDescending { it.second.durationMs }
                .thenBy { it.first.clipId }
                .thenBy { it.second.startMs }
        ).take(config.maxCandidates.coerceAtLeast(1))

        return analyses.mapNotNull { a ->
            val cands = kept.filter { it.first.clipId == a.clipId }
                .map { (_, seg, scored) ->
                    JumpCutCandidate(
                        segment = seg,
                        score = scored.score,
                        reason = scored.reason,
                        recommended = scored.score >= config.recommendThreshold
                    )
                }
                .sortedBy { it.segment.startMs } // clip-local order: stable segment ids
            // Preserve clips even when empty so multi-clip previews stay complete.
            RankedClip(
                clipId = a.clipId,
                fileName = a.fileName,
                clipDurationMs = a.clipDurationMs,
                candidates = cands,
                analyzedDurationMs = a.analyzedDurationMs
            )
        }
    }

    internal data class Scored(val score: Float, val reason: JumpCutReason)

    /**
     * Normalized 0..1 score from duration + confidence, with guardrails:
     * - touching clip start/end → [JumpCutConfig.boundaryPenalty] (protect intros/outros)
     * - shorter than [JumpCutConfig.shortPauseMs] → capped (preserve natural rhythm)
     * - confidence below [JumpCutConfig.lowConfidenceThreshold] → capped (don't cut uncertain audio)
     */
    internal fun score(
        seg: SilenceSegment,
        clipDurationMs: Long,
        config: JumpCutConfig
    ): Scored {
        val duration = seg.durationMs.toDouble()
        val saturation = maxOf(
            config.preferredSilenceMs * config.saturationMultiplier,
            config.minSilenceMs + 1.0
        ).toDouble()
        val durationNorm = ((duration - config.minSilenceMs) / (saturation - config.minSilenceMs))
            .toFloat().coerceIn(0f, 1f)
        val confidence = seg.confidence.coerceIn(0f, 1f)
        var value = config.durationWeight * durationNorm + config.confidenceWeight * confidence

        val touchesBoundary = seg.startMs <= 0L || seg.endMs >= clipDurationMs
        val reason: JumpCutReason
        when {
            touchesBoundary -> {
                value *= config.boundaryPenalty
                reason = JumpCutReason.BOUNDARY
            }
            duration < config.shortPauseMs -> {
                value = minOf(value, config.shortPauseCap)
                reason = JumpCutReason.SHORT_PAUSE
            }
            confidence < config.lowConfidenceThreshold -> {
                value = minOf(value, config.lowConfidenceCap)
                reason = JumpCutReason.LOW_CONFIDENCE
            }
            duration >= config.preferredSilenceMs * 2 -> reason = JumpCutReason.EXTENDED_SILENCE
            duration >= config.preferredSilenceMs -> reason = JumpCutReason.LONG_PAUSE
            else -> reason = JumpCutReason.MEDIUM_PAUSE
        }
        return Scored(value.coerceIn(0f, 1f), reason)
    }
}

/**
 * PHASE 3 — tuning knobs for jump-cut recommendations. No magic numbers
 * in callers; every weight/cap is explicit with a documented default.
 */
data class JumpCutConfig(
    /** Segments shorter than this never reach ranking (mapper minimum). */
    val minSilenceMs: Double = 500.0,
    /** Duration at/above which a pause reads as a confident cut. */
    val preferredSilenceMs: Double = 1500.0,
    /** Score saturates at preferred × this multiplier. */
    val saturationMultiplier: Double = 1.5,
    val durationWeight: Float = 0.65f,
    val confidenceWeight: Float = 0.35f,
    /** Score at/above which a candidate is pre-selected. */
    val recommendThreshold: Float = 0.6f,
    /** Global cap on suggested cuts (protects long compilations). */
    val maxCandidates: Int = 200,
    /** Below this the pause is kept for natural rhythm (capped score). */
    val shortPauseMs: Double = 700.0,
    val shortPauseCap: Float = 0.4f,
    val lowConfidenceThreshold: Float = 0.5f,
    val lowConfidenceCap: Float = 0.55f,
    /** Multiplier for silences touching clip start/end. */
    val boundaryPenalty: Float = 0.5f
)

/** Human-readable cause for a recommendation. Never expose raw scores in UI. */
enum class JumpCutReason {
    EXTENDED_SILENCE,
    LONG_PAUSE,
    MEDIUM_PAUSE,
    SHORT_PAUSE,
    BOUNDARY,
    LOW_CONFIDENCE
}

/** One ranked suggestion. Coordinates are clip-local (matches Phase 2). */
data class JumpCutCandidate(
    val segment: SilenceSegment,
    val score: Float,
    val reason: JumpCutReason,
    val recommended: Boolean
) {
    val startMs: Long get() = segment.startMs
    val endMs: Long get() = segment.endMs
    val durationMs: Long get() = segment.durationMs
    val confidence: Float get() = segment.confidence
}

/** Per-clip ranked result. Clips analyzed independently, never mixed. */
data class RankedClip(
    val clipId: String,
    val fileName: String,
    val clipDurationMs: Long,
    val candidates: List<JumpCutCandidate>,
    val analyzedDurationMs: Long = 0L
) {
    val recommendedCount: Int get() = candidates.count { it.recommended }
    val recommendedMs: Long get() = candidates.filter { it.recommended }.sumOf { it.durationMs }

    /**
     * Rebuild the storable analysis from the (possibly capped) ranked
     * output. The ViewModel parks THIS — not the raw VAD analysis — so
     * [SilenceCutter.filterSelected]'s sorted-index ids always resolve
     * against exactly the segments shown in preview.
     */
    fun toAnalysis(): SilenceCutter.ClipSilenceAnalysis =
        SilenceCutter.ClipSilenceAnalysis(
            clipId = clipId,
            fileName = fileName,
            clipDurationMs = clipDurationMs,
            segments = candidates.map { it.segment },
            analyzedDurationMs = analyzedDurationMs
        )
}

/**
 * PHASE 3 — seam between VAD analysis and ranking. The ViewModel drives
 * this; tests target [JumpCutRanker.rank] directly (pure).
 */
interface JumpCutAnalyzer {
    suspend fun analyze(
        clips: List<VideoClip>,
        silence: SilenceDetectionConfig = SilenceDetectionConfig(),
        jump: JumpCutConfig = JumpCutConfig(),
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): List<RankedClip>
}

/** On-device implementation: VAD first, rank second. No network, ever. */
class LocalJumpCutAnalyzer(
    private val cutter: SilenceCutter
) : JumpCutAnalyzer {
    override suspend fun analyze(
        clips: List<VideoClip>,
        silence: SilenceDetectionConfig,
        jump: JumpCutConfig,
        onProgress: (Float) -> Unit,
        checkCancelled: (() -> Unit)?
    ): List<RankedClip> {
        // Reserve the tail of the progress bar for ranking (cheap, but visible).
        val analyses = cutter.analyzeClips(
            clips = clips,
            config = silence,
            onProgress = { onProgress(it * 0.9f) },
            checkCancelled = checkCancelled
        )
        checkCancelled?.invoke()
        val ranked = JumpCutRanker.rank(analyses, jump)
        onProgress(1f)
        return ranked
    }
}
