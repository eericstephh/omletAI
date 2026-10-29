package com.aicreatorstudio.app.util

import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.VideoClip

/**
 * PHASE 6 — Local Beat Sync planner (pure, JVM-tested).
 *
 * Concept: cuts land EXACTLY on strong beats (offset is always 0 —
 * for music sync, on-beat is the goal); ranking decides WHICH beats
 * become cuts. Signals: beat intensity (0..1 from [BeatDetector]),
 * spacing from already-accepted cuts (no over-cutting), and distance
 * from clip edges (no slivers). Deterministic: score desc, time asc.
 *
 * Coordinates are timeline-global milliseconds (what
 * `EditorViewModel.splitClip` consumes). Multi-beat application goes
 * last-to-first so earlier positions stay valid.
 */
object BeatSyncPlanner {

    /**
     * Build ranked cut candidates for one video clip from timeline
     * beat markers. Pure: no media, no state.
     */
    fun plan(
        beatsTimelineMs: List<BeatDetector.BeatMarker>,
        videoClip: VideoClip,
        config: BeatSyncConfig = BeatSyncConfig()
    ): List<BeatSyncCandidate> {
        val clipStart = videoClip.timelineStartMs.coerceAtLeast(0L)
        val clipEnd = clipStart + videoClip.effectiveDurationMs
        if (clipEnd <= clipStart) return emptyList()

        // Validate: strictly inside (with edge margin), ascending,
        // deduped. Beats closer than MIN_BEAT_SEPARATION_MS collapse.
        val valid = beatsTimelineMs
            .filter { it.timestampMs > clipStart + config.edgeMarginMs }
            .filter { it.timestampMs < clipEnd - config.edgeMarginMs }
            .sortedBy { it.timestampMs }
            .fold(mutableListOf<BeatDetector.BeatMarker>()) { acc, m ->
                if (acc.isEmpty() ||
                    m.timestampMs - acc.last().timestampMs >= MIN_BEAT_SEPARATION_MS
                ) acc.add(m)
                acc
            }
        if (valid.isEmpty()) return emptyList()

        // Score: intensity dominates, edge distance breaks toward
        // mid-clip cuts (safer pieces on both sides).
        data class Scored(val marker: BeatDetector.BeatMarker, val score: Float)
        val scored = valid.map { m ->
            val edgeDist = minOf(
                m.timestampMs - clipStart,
                clipEnd - m.timestampMs
            ).toFloat()
            val edgeScore = (edgeDist / EDGE_FULL_SCORE_MS).coerceIn(0f, 1f)
            val intensity = m.intensity.coerceIn(0f, 1f)
            Scored(m, config.intensityWeight * intensity + config.edgeWeight * edgeScore)
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.marker.timestampMs })

        // Greedy accept by score with minimum spacing + cap.
        val accepted = mutableListOf<Long>()
        val selectedFlags = mutableMapOf<Long, BeatSyncReason>()
        for (s in scored) {
            if (accepted.size >= config.maxCuts) break
            if (s.score < config.recommendThreshold) {
                selectedFlags[s.marker.timestampMs] = BeatSyncReason.WEAK_BEAT
                continue
            }
            val tooClose = accepted.any { kotlin.math.abs(it - s.marker.timestampMs) < config.minGapMs }
            if (tooClose) {
                selectedFlags[s.marker.timestampMs] = BeatSyncReason.TOO_CLOSE
                continue
            }
            accepted.add(s.marker.timestampMs)
            selectedFlags[s.marker.timestampMs] = when {
                s.marker.intensity >= STRONG_BEAT_INTENSITY -> BeatSyncReason.STRONG_BEAT
                else -> BeatSyncReason.GOOD_SPACING
            }
        }

        return valid.map { m ->
            val score = scored.first { it.marker.timestampMs == m.timestampMs }.score
            val reason = selectedFlags[m.timestampMs] ?: BeatSyncReason.WEAK_BEAT
            BeatSyncCandidate(
                id = candidateId(videoClip.id, m.timestampMs),
                clipId = videoClip.id,
                cutTimelineMs = m.timestampMs,
                beatTimelineMs = m.timestampMs,
                offsetMs = 0L,
                score = score.coerceIn(0f, 1f),
                beatStrength = m.intensity.coerceIn(0f, 1f),
                reason = reason,
                selected = reason == BeatSyncReason.STRONG_BEAT ||
                    reason == BeatSyncReason.GOOD_SPACING
            )
        }.sortedBy { it.cutTimelineMs }
    }

    /**
     * Defensive cut normalization before apply: strictly inside,
     * ascending, deduped, minimum spacing. The planner output already
     * satisfies this; apply re-checks against live clip bounds.
     */
    fun normalizeCuts(
        cuts: List<Long>,
        clipStartMs: Long,
        clipEndMs: Long,
        edgeMarginMs: Long = BeatSyncConfigDefaults.EDGE_MARGIN_MS,
        minGapMs: Long = BeatSyncConfigDefaults.MIN_GAP_BALANCED_MS
    ): List<Long> {
        if (clipEndMs <= clipStartMs) return emptyList()
        val valid = cuts
            .filter { it > clipStartMs + edgeMarginMs && it < clipEndMs - edgeMarginMs }
            .distinct()
            .sorted()
        if (valid.isEmpty()) return emptyList()
        val out = mutableListOf<Long>()
        for (c in valid) {
            if (out.isEmpty() || c - out.last() >= minGapMs.coerceAtLeast(1L)) out.add(c)
        }
        return out
    }

    /** Deterministic candidate id: clip + timeline position. */
    fun candidateId(clipId: String, cutTimelineMs: Long): String = "$clipId:$cutTimelineMs"

    internal const val MIN_BEAT_SEPARATION_MS: Long = 100L
    internal const val EDGE_FULL_SCORE_MS: Float = 2000f
    internal const val STRONG_BEAT_INTENSITY: Float = 0.7f
}

/** Cut-density presets. One high-quality default (BALANCED) leads. */
enum class BeatSyncMode(
    val minGapMs: Long,
    val recommendThreshold: Float,
    val maxCuts: Int,
    val persianLabel: String,
    val description: String
) {
    MINIMAL(
        minGapMs = 4000L,
        recommendThreshold = 0.7f,
        maxCuts = 8,
        persianLabel = "حداقلی",
        description = "فقط قوی‌ترین ضرب‌ها"
    ),
    BALANCED(
        minGapMs = 2000L,
        recommendThreshold = 0.5f,
        maxCuts = 24,
        persianLabel = "متعادل",
        description = "تقریباً هر دو ضرب"
    ),
    DYNAMIC(
        minGapMs = 1000L,
        recommendThreshold = 0.35f,
        maxCuts = 48,
        persianLabel = "پویا",
        description = "کات‌های بیشتر روی ضرب‌های قوی"
    );

    companion object {
        val Default = BALANCED
    }
}

object BeatSyncConfigDefaults {
    const val EDGE_MARGIN_MS: Long = 500L
    const val MIN_GAP_BALANCED_MS: Long = 2000L
    const val INTENSITY_WEIGHT: Float = 0.7f
    const val EDGE_WEIGHT: Float = 0.3f
}

/** All weights/thresholds live here — no magic numbers in callers. */
data class BeatSyncConfig(
    val mode: BeatSyncMode = BeatSyncMode.Default,
    val minGapMs: Long = mode.minGapMs,
    val recommendThreshold: Float = mode.recommendThreshold,
    val maxCuts: Int = mode.maxCuts,
    val edgeMarginMs: Long = BeatSyncConfigDefaults.EDGE_MARGIN_MS,
    val intensityWeight: Float = BeatSyncConfigDefaults.INTENSITY_WEIGHT,
    val edgeWeight: Float = BeatSyncConfigDefaults.EDGE_WEIGHT
)

/** Human-readable cause. Raw scores stay internal. */
enum class BeatSyncReason {
    STRONG_BEAT,
    GOOD_SPACING,
    WEAK_BEAT,
    TOO_CLOSE
}

/** One suggested on-beat cut. Timeline-global coordinates. */
data class BeatSyncCandidate(
    val id: String,
    val clipId: String,
    val cutTimelineMs: Long,
    val beatTimelineMs: Long,
    val offsetMs: Long = 0L,
    val score: Float,
    val beatStrength: Float,
    val reason: BeatSyncReason,
    val selected: Boolean
)

/**
 * Source identity for stale protection: video geometry + audio
 * identity + beat content + mode. Timeline moves, re-analysis and
 * mode flips all invalidate a parked preview. Music bytes themselves
 * never change, so beats are content-addressed by count + bpm.
 */
fun beatSyncSourceKey(
    videoClip: VideoClip,
    audioClip: AudioClip,
    mode: BeatSyncMode
): String {
    val markers = audioClip.beatData?.markers ?: emptyList()
    return "${videoClip.id}|${videoClip.trimStartMs}|${videoClip.trimEndMs}|" +
        "${videoClip.durationMs}|${videoClip.timelineStartMs}|" +
        "${audioClip.id}|${audioClip.sourceUri}|${audioClip.startTimeMs}|" +
        "${audioClip.durationMs}|${markers.size}|${audioClip.beatData?.bpm}|${mode.name}"
}
