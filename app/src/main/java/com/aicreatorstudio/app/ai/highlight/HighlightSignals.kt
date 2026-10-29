package com.aicreatorstudio.app.ai.highlight

import kotlin.math.abs

/**
 * PHASE 7 — Local Smart Highlight Detection: pure signal planning.
 *
 * Pipeline (all signals pre-computed by [HighlightEngine], all local):
 *
 * ```
 * coarse windows (4 s / 50% overlap, clip-local ms)
 *   ↓ per-window raw signals (nullable = unavailable, never zero-filled)
 * normalize each signal to 0..1 (deterministic, outlier-clamped)
 *   ↓ contextual shaping (energy vs neighborhood; speech gating)
 * weighted score with renormalization over AVAILABLE signals only
 *   ↓ silence penalty, confidence from signal agreement
 * merge neighboring hot windows → coherent segments (5–30 s)
 *   ↓ temporal-diversity greedy ranking, top-N cap
 * HighlightCandidate list (no frames, no PCM — metadata only)
 * ```
 *
 * A highlight = "stronger evidence of useful, active, content-rich
 * material". Loudness alone never decides: every signal is bounded
 * and no single weight can dominate after renormalization.
 */
object HighlightPlanner {

    /**
     * Score + merge + rank raw windows into candidates. Pure and
     * deterministic (score → duration fit → start → clip).
     */
    fun plan(
        windows: List<SignalWindow>,
        clipId: String,
        config: HighlightConfig = HighlightConfig()
    ): List<HighlightCandidate> {
        if (windows.isEmpty()) return emptyList()
        val scored = windows.map { scoreWindow(it, config) }.filter { it.score >= 0f }
        if (scored.isEmpty()) return emptyList()

        // Merge runs of hot windows (score >= keepThreshold) into segments.
        val ordered = scored.sortedBy { it.window.startMs }
        val segments = mutableListOf<MutableList<ScoredWindow>>()
        for (s in ordered) {
            val tail = segments.lastOrNull()
            if (s.score >= config.keepThreshold &&
                tail != null &&
                tail.last().window.endMs >= s.window.startMs &&
                tail.all { it.score >= config.keepThreshold }
            ) {
                tail.add(s)
            } else if (s.score >= config.keepThreshold) {
                segments.add(mutableListOf(s))
            }
        }

        val merged = segments.mapNotNull { run ->
            val start = run.minOf { it.window.startMs }
            val end = run.maxOf { it.window.endMs }
            var curStart = start
            val parts = mutableListOf<HighlightCandidate>()
            // Split over-long runs at their coldest interior boundary.
            var remaining = run.sortedBy { it.window.startMs }
            while (remaining.isNotEmpty()) {
                val total = remaining.maxOf { it.window.endMs } - curStart
                if (total <= config.maxDurationMs) {
                    parts.add(
                        buildCandidate(
                            clipId, curStart,
                            remaining.maxOf { it.window.endMs },
                            remaining, config
                        )
                    )
                    break
                }
                // Cut before the coldest interior window.
                val interior = remaining.dropLast(1)
                val coldest = interior.minByOrNull { it.score } ?: interior.last()
                val cutEnd = coldest.window.startMs
                if (cutEnd <= curStart) {
                    // Degenerate: force a max-length chunk.
                    val cut = (curStart + config.maxDurationMs)
                        .coerceAtMost(remaining.maxOf { it.window.endMs })
                    parts.add(buildCandidate(clipId, curStart, cut, remaining, config))
                    remaining = remaining.filter { it.window.endMs > cut }
                    curStart = cut
                } else {
                    val head = remaining.filter { it.window.endMs <= cutEnd }
                    val tail = remaining.filter { it.window.endMs > cutEnd }
                    parts.add(buildCandidate(clipId, curStart, cutEnd, head, config))
                    remaining = tail
                    curStart = cutEnd
                }
            }
            parts
        }.flatten().filter { it.durationMs >= config.minDurationMs }

        if (merged.isEmpty()) return emptyList()

        // Diversity greedy: best first, suppress any overlap with accepted.
        val ranked = merged.sortedWith(
            compareByDescending<HighlightCandidate> { it.score }
                .thenBy { durationFitPenalty(it.durationMs, config) }
                .thenBy { it.startMs }
                .thenBy { it.clipId }
        )
        val accepted = mutableListOf<HighlightCandidate>()
        for (c in ranked) {
            if (accepted.size >= config.maxHighlights.coerceAtLeast(1)) break
            if (accepted.any { overlaps(it, c) }) continue
            accepted.add(c)
        }
        return accepted.sortedWith(
            compareByDescending<HighlightCandidate> { it.score }
                .thenBy { it.startMs }
        )
    }

    private fun buildCandidate(
        clipId: String,
        startMs: Long,
        endMs: Long,
        windows: List<ScoredWindow>,
        config: HighlightConfig
    ): HighlightCandidate {
        val inRange = windows.filter { it.window.endMs > startMs && it.window.startMs < endMs }
        val durWeighted = if (inRange.isEmpty()) 0f else {
            val total = inRange.sumOf { it.window.durationMs }.coerceAtLeast(1L).toFloat()
            inRange.sumOf { (it.score * it.window.durationMs).toDouble() }.toFloat() / total
        }
        // Evidence breadth: speech+energy+visual outranks energy alone.
        // A lone signal renormalizes high on its own (correct per-signal
        // behavior) but must not beat genuinely multi-signal windows.
        val kinds = mutableSetOf<HighlightSignal>()
        for (s in inRange) {
            val w = s.window
            if (w.speechRatio != null) kinds.add(HighlightSignal.SPEECH)
            if (w.rmsDb != null) kinds.add(HighlightSignal.ENERGY)
            if (w.beatDensity != null || w.beatIntensity != null) kinds.add(HighlightSignal.RHYTHM)
            if (w.visualActivity != null) kinds.add(HighlightSignal.VISUAL)
            if (w.wordsPerSecond != null) kinds.add(HighlightSignal.SUBTITLES)
        }
        val coverage = (kinds.size / 5f).coerceIn(0f, 1f)
        val finalScore = (0.7f * durWeighted + 0.3f * coverage).coerceIn(0f, 1f)
        val topSignals = topSignals(inRange)
        return HighlightCandidate(
            id = "$clipId:$startMs-$endMs",
            clipId = clipId,
            startMs = startMs,
            endMs = endMs,
            score = finalScore,
            confidence = agreement(inRange),
            reason = reasonFor(topSignals, finalScore),
            signalSummary = topSignals,
            selected = true
        )
    }

    private data class ScoredWindow(val window: SignalWindow, val score: Float)

    private fun scoreWindow(w: SignalWindow, config: HighlightConfig): ScoredWindow {
        val parts = mutableListOf<Pair<Float, Float>>() // (score, weight)
        w.speechRatio?.let { r ->
            parts.add(speechScore(r) to config.speechWeight)
        }
        w.rmsDb?.let { db ->
            parts.add(energyScore(db, w.neighborRmsDb, config) to config.energyWeight)
        }
        if (w.beatDensity != null || w.beatIntensity != null) {
            parts.add(rhythmScore(w.beatDensity, w.beatIntensity) to config.rhythmWeight)
        }
        w.visualActivity?.let { v ->
            parts.add(v.coerceIn(0f, 1f) to config.visualWeight)
        }
        w.wordsPerSecond?.let { wps ->
            parts.add(subtitleScore(wps) to config.subtitleWeight)
        }
        if (parts.isEmpty()) return ScoredWindow(w, -1f) // no signal: drop window
        val totalW = parts.sumOf { it.second.toDouble() }.toFloat()
        if (totalW <= 0f) return ScoredWindow(w, -1f)
        var score = parts.sumOf { (it.first * it.second).toDouble() }.toFloat() / totalW
        // Silence penalty: dead air is never a highlight, but the
        // window survives (speech may start right after).
        val speechRatio = w.speechRatio
        if (speechRatio != null && speechRatio < config.deadAirRatio) {
            score -= config.silencePenalty
        }
        return ScoredWindow(w, score.coerceIn(0f, 1f))
    }

    /** Speech ratio shaped: full-bore speech is good, not decisive. */
    internal fun speechScore(ratio: Float): Float =
        (ratio.coerceIn(0f, 1f) * 1.1f).coerceIn(0f, 1f)

    /**
     * Energy: absolute level (mapped [-60,-6] dB) blended with
     * contextual lift vs the neighborhood mean (local peaks beat
     * uniformly loud beds).
     */
    internal fun energyScore(rmsDb: Float, neighborRmsDb: Float?, config: HighlightConfig): Float {
        if (!rmsDb.isFinite()) return 0f
        val absolute = ((rmsDb + 60f) / 48f).coerceIn(0f, 1f)
        if (neighborRmsDb == null || !neighborRmsDb.isFinite()) return absolute
        val lift = (0.5f + (rmsDb - neighborRmsDb) / 12f).coerceIn(0f, 1f)
        return (config.energyAbsoluteMix * absolute +
            (1f - config.energyAbsoluteMix) * lift).coerceIn(0f, 1f)
    }

    /** Rhythm: dense + strong beats; absent data simply contributes nothing. */
    internal fun rhythmScore(density: Float?, meanIntensity: Float?): Float {
        val d = density?.coerceIn(0f, 1f) ?: return -1f
        val i = meanIntensity?.coerceIn(0f, 1f) ?: 0.5f
        return (0.5f * d + 0.5f * i).coerceIn(0f, 1f)
    }

    /** Subtitle density: ~3 words/s (fast speech) saturates. */
    internal fun subtitleScore(wordsPerSecond: Float): Float =
        (wordsPerSecond.coerceAtLeast(0f) / 3f).coerceIn(0f, 1f)

    /** Agreement across available signals → confidence 0..1. */
    private fun agreement(windows: List<ScoredWindow>): Float {
        if (windows.isEmpty()) return 0f
        var votes = 0
        var hot = 0
        for (s in windows) {
            val w = s.window
            val signals = listOfNotNull(
                w.speechRatio?.let { it > 0.4f },
                w.rmsDb?.let { it > -30f },
                if (w.beatDensity != null || w.beatIntensity != null)
                    (w.beatIntensity ?: 0.5f) > 0.5f else null,
                w.visualActivity?.let { it > 0.4f },
                w.wordsPerSecond?.let { it > 1f }
            )
            votes += signals.size
            hot += signals.count { it }
        }
        return if (votes == 0) 0f else (hot.toFloat() / votes).coerceIn(0f, 1f)
    }

    private fun topSignals(windows: List<ScoredWindow>): List<HighlightSignal> {
        if (windows.isEmpty()) return emptyList()
        data class Acc(var sum: Float = 0f, var n: Int = 0)
        val acc = mutableMapOf<HighlightSignal, Acc>()
        fun add(s: HighlightSignal, v: Float) {
            acc.getOrPut(s) { Acc() }.let { it.sum += v; it.n++ }
        }
        for (s in windows) {
            val w = s.window
            w.speechRatio?.let { add(HighlightSignal.SPEECH, it) }
            w.rmsDb?.let { if (it.isFinite()) add(HighlightSignal.ENERGY, ((it + 60f) / 48f).coerceIn(0f, 1f)) }
            if (w.beatDensity != null || w.beatIntensity != null) {
                add(HighlightSignal.RHYTHM, (w.beatIntensity ?: 0.5f).coerceIn(0f, 1f))
            }
            w.visualActivity?.let { add(HighlightSignal.VISUAL, it.coerceIn(0f, 1f)) }
            w.wordsPerSecond?.let { add(HighlightSignal.SUBTITLES, (it / 3f).coerceIn(0f, 1f)) }
        }
        return acc.entries.sortedByDescending { it.value.sum / it.value.n.coerceAtLeast(1) }
            .take(2).map { it.key }
    }

    private fun reasonFor(top: List<HighlightSignal>, score: Float): HighlightReason {
        if (score < 0.45f) return HighlightReason.MODERATE
        if (top.size >= 2) {
            val set = top.toSet()
            if (set.containsAll(listOf(HighlightSignal.SPEECH, HighlightSignal.ENERGY)) ||
                set.containsAll(listOf(HighlightSignal.SPEECH, HighlightSignal.RHYTHM))
            ) return HighlightReason.STRONG_MIX
        }
        return when (top.firstOrNull()) {
            HighlightSignal.SPEECH -> HighlightReason.SPEECH_ACTIVE
            HighlightSignal.ENERGY -> HighlightReason.ENERGY_PEAK
            HighlightSignal.RHYTHM -> HighlightReason.RHYTHM_ACTIVE
            HighlightSignal.VISUAL -> HighlightReason.VISUAL_CHANGE
            HighlightSignal.SUBTITLES -> HighlightReason.SPEECH_ACTIVE
            null -> HighlightReason.MODERATE
        }
    }

    private fun durationFitPenalty(durationMs: Long, config: HighlightConfig): Float {
        val ideal = (config.minDurationMs + config.maxDurationMs) / 2f
        return (abs(durationMs - ideal) / ideal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun overlaps(a: HighlightCandidate, b: HighlightCandidate): Boolean =
        a.clipId == b.clipId && a.startMs < b.endMs && b.startMs < a.endMs
}

/** One coarse analysis window. Clip-local ms. Null = signal unavailable. */
data class SignalWindow(
    val startMs: Long,
    val endMs: Long,
    val speechRatio: Float? = null,
    val rmsDb: Float? = null,
    val neighborRmsDb: Float? = null,
    val beatDensity: Float? = null,
    val beatIntensity: Float? = null,
    val visualActivity: Float? = null,
    val wordsPerSecond: Float? = null
) {
    val durationMs: Long get() = endMs - startMs
}

/** All weights/thresholds live here — no magic numbers in callers. */
data class HighlightConfig(
    val windowMs: Long = 4000L,
    val strideMs: Long = 2000L,
    val minDurationMs: Long = 5000L,
    val maxDurationMs: Long = 30_000L,
    val maxHighlights: Int = 5,
    /** Windows below this never seed/extend a segment. */
    val keepThreshold: Float = 0.45f,
    val speechWeight: Float = 0.30f,
    val energyWeight: Float = 0.25f,
    val rhythmWeight: Float = 0.15f,
    val visualWeight: Float = 0.15f,
    val subtitleWeight: Float = 0.15f,
    /** Absolute/contextual blend inside energyScore. */
    val energyAbsoluteMix: Float = 0.5f,
    /** Below this speech ratio the silence penalty applies. */
    val deadAirRatio: Float = 0.05f,
    val silencePenalty: Float = 0.35f
) {
    /** Fingerprint for the source key: config changes invalidate previews. */
    fun versionKey(): String =
        listOf(
            windowMs, strideMs, minDurationMs, maxDurationMs, maxHighlights,
            keepThreshold, speechWeight, energyWeight, rhythmWeight,
            visualWeight, subtitleWeight, deadAirRatio, silencePenalty
        ).joinToString(",")
}

enum class HighlightSignal { SPEECH, ENERGY, RHYTHM, VISUAL, SUBTITLES }

/** Human-readable cause. Raw scores stay internal. */
enum class HighlightReason {
    STRONG_MIX,
    SPEECH_ACTIVE,
    ENERGY_PEAK,
    RHYTHM_ACTIVE,
    VISUAL_CHANGE,
    MODERATE
}

/**
 * One recommended highlight. Clip-local ms (offset into the clip's
 * effective/post-trim span). Metadata only — no frames, no PCM.
 * Shaped for the future AutoEdit planner: stable ids, scores,
 * reasons and explicit selection.
 */
data class HighlightCandidate(
    val id: String,
    val clipId: String,
    val startMs: Long,
    val endMs: Long,
    val score: Float,
    val confidence: Float,
    val reason: HighlightReason,
    val signalSummary: List<HighlightSignal> = emptyList(),
    val selected: Boolean = true
) {
    val durationMs: Long get() = endMs - startMs
}
