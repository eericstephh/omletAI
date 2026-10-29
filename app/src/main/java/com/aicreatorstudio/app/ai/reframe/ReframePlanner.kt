package com.aicreatorstudio.app.ai.reframe

import android.net.Uri
import com.aicreatorstudio.app.ai.AiToolEngine
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.VideoClip
import kotlin.math.abs

/**
 * PHASE 5 — Local Smart Reframe planner + analyzer.
 *
 * Deliberately NOT a new detector or cut engine. Subject detection,
 * smoothing and keyframe emission come from the existing production
 * [AiToolEngine] registered as `"autoframe"`
 * (`SaliencyTrackerEngine`: pose → face → saliency, 250 ms sampling,
 * OneEuro smoothing, 200 ms keys). This layer adds what that engine
 * lacks:
 *
 * 1. keyframe simplification (it emits 5 keys/s unbounded),
 * 2. structured analysis stats for a preview-before-apply UX,
 * 3. stale-source protection and single-entry apply via the host.
 *
 * Animated crop reaches export through the EXISTING baked pre-render
 * (`AiExportHooks.reframe` interpolates `cropKeyframes` per frame) —
 * no renderer or exporter changes.
 */
object ReframePlanner {

    /**
     * Greedy keyframe simplification. Walks time-ordered keys, keeping
     * a key only when the crop center moved more than [tolerance]
     * (normalized units) from the last kept key; first and last always
     * kept (monotonic timestamps preserved). If still over [maxKeys],
     * stride-samples evenly (endpoints pinned). Deterministic.
     */
    fun simplify(
        keys: List<CropKeyframe>,
        tolerance: Float = ReframeConfigDefaults.SIMPLIFY_TOLERANCE,
        maxKeys: Int = ReframeConfigDefaults.MAX_KEYS
    ): List<CropKeyframe> {
        if (keys.size <= 2) return keys.sortedBy { it.timeMs }
        val ordered = keys.sortedBy { it.timeMs }
        val kept = ArrayList<CropKeyframe>(ordered.size)
        kept.add(ordered[0])
        var last = ordered[0]
        for (k in ordered.drop(1).dropLast(1)) {
            val dx = k.centerX - last.centerX
            val dy = k.centerY - last.centerY
            if (dx * dx + dy * dy >= tolerance * tolerance) {
                kept.add(k)
                last = k
            }
        }
        kept.add(ordered.last())
        if (kept.size <= maxKeys.coerceAtLeast(2)) return kept
        // Even stride sampling with pinned endpoints.
        val cap = maxKeys.coerceAtLeast(2)
        val out = ArrayList<CropKeyframe>(cap)
        for (i in 0 until cap) {
            val pos = i * (kept.size - 1).toDouble() / (cap - 1)
            out.add(kept[pos.toInt().coerceIn(0, kept.size - 1)])
        }
        return out.distinct().sortedBy { it.timeMs }
    }

    /**
     * Largest-fit crop window for [srcW]×[srcH] at the target aspect,
     * centered on ([centerX],[centerY]) with a [margin] safe border
     * (fraction of window size), clamped fully inside the source.
     * Pure mirror of the export-time math for validation/preview —
     * export itself keeps using its own implementation.
     */
    fun cropWindow(
        srcW: Int,
        srcH: Int,
        targetW: Float,
        targetH: Float,
        centerX: Float,
        centerY: Float,
        margin: Float = ReframeConfigDefaults.SAFE_MARGIN
    ): CropWindow {
        require(srcW > 0 && srcH > 0) { "empty source" }
        val targetAspect = (targetW / targetH.coerceAtLeast(0.01f)).coerceAtLeast(0.01f)
        val srcAspect = srcW.toFloat() / srcH
        val (winW, winH) = if (srcAspect > targetAspect) {
            // Source wider: full height, window width by aspect.
            val h = srcH
            val w = (h * targetAspect).toInt().coerceIn(1, srcW)
            w to h
        } else {
            val w = srcW
            val h = (w / targetAspect).toInt().coerceIn(1, srcH)
            w to h
        }
        // Shrink by margin so faces never touch the crop edge.
        val m = margin.coerceIn(0f, 0.4f)
        val shrunkW = (winW * (1f - m)).toInt().coerceIn(1, srcW)
        val shrunkH = (winH * (1f - m)).toInt().coerceIn(1, srcH)
        val cx = (centerX.coerceIn(0f, 1f) * srcW).toInt()
        val cy = (centerY.coerceIn(0f, 1f) * srcH).toInt()
        val left = (cx - shrunkW / 2).coerceIn(0, (srcW - shrunkW).coerceAtLeast(0))
        val top = (cy - shrunkH / 2).coerceIn(0, (srcH - shrunkH).coerceAtLeast(0))
        return CropWindow(left, top, shrunkW, shrunkH)
    }

    /**
     * The **export** crop math, exactly as `AiExportHooks.cropWindowFor`
     * (which now delegates here) applies it per decoded frame: largest
     * fit for the target aspect, centered on the (normalized) subject
     * point, NO safety margin, clamped inside the source.
     *
     * This function is the single source of truth shared by the export
     * hook and the Compose Auto-Frame preview, so preview and output can
     * never drift. For the analysis-planner's margin-shrunk variant
     * (validation only), see [cropWindow].
     */
    fun cropWindowFor(
        srcW: Int,
        srcH: Int,
        targetW: Float,
        targetH: Float,
        centerX: Float,
        centerY: Float
    ): CropWindow {
        require(srcW > 0 && srcH > 0) { "empty source" }
        val aspect = targetW.coerceAtLeast(0.01f) / targetH.coerceAtLeast(0.01f)
        val srcAspect = srcW.toFloat() / srcH
        val (cw, ch) = if (srcAspect > aspect) {
            val h = srcH
            val w = (h * aspect).toInt().coerceIn(1, srcW)
            w to h
        } else {
            val w = srcW
            val h = (w / aspect).toInt().coerceIn(1, srcH)
            w to h
        }
        val cxPx = (centerX.coerceIn(0f, 1f) * srcW).toInt()
        val cyPx = (centerY.coerceIn(0f, 1f) * srcH).toInt()
        val left = (cxPx - cw / 2).coerceIn(0, (srcW - cw).coerceAtLeast(0))
        val top = (cyPx - ch / 2).coerceIn(0, (srcH - ch).coerceAtLeast(0))
        return CropWindow(left, top, cw, ch)
    }

    /** Analysis summary for the preview panel. */
    fun stats(
        rawKeys: List<CropKeyframe>,
        simplifiedKeys: List<CropKeyframe>,
        durationMs: Long,
        trackingSummary: String
    ): ReframeStats {
        val found = rawKeys.count { it.isFound }
        return ReframeStats(
            rawKeyframes = rawKeys.size,
            keyframes = simplifiedKeys.size,
            durationMs = durationMs,
            subjectFoundPct = if (rawKeys.isEmpty()) 0 else (found * 100 / rawKeys.size),
            trackingSummary = trackingSummary
        )
    }
}

data class CropWindow(val left: Int, val top: Int, val width: Int, val height: Int)

data class ReframeStats(
    val rawKeyframes: Int,
    val keyframes: Int,
    val durationMs: Long,
    /** Share of raw keys with subject found (0..100). */
    val subjectFoundPct: Int,
    /** Engine's own detection-status line (pose/face/fallback %). */
    val trackingSummary: String
)

object ReframeConfigDefaults {
    const val SIMPLIFY_TOLERANCE: Float = 0.008f
    const val MAX_KEYS: Int = 400
    const val SAFE_MARGIN: Float = 0.06f
    const val MAX_ANALYZE_DURATION_MS: Long = 15L * 60L * 1000L
}

/** Tuning for one reframe analysis. No magic numbers in callers. */
data class ReframeConfig(
    val targetAspectWidth: Float = 9f,
    val targetAspectHeight: Float = 16f,
    /** 0..1 engine smoothing (strength channel). */
    val smoothing: Float = 0.5f,
    val simplifyTolerance: Float = ReframeConfigDefaults.SIMPLIFY_TOLERANCE,
    val maxKeys: Int = ReframeConfigDefaults.MAX_KEYS
)

/** One analyzed clip: simplified path + settings to commit on Apply. */
data class ReframeAnalysis(
    val clipId: String,
    val fileName: String,
    val clipDurationMs: Long,
    val srcWidth: Int,
    val srcHeight: Int,
    val config: ReframeConfig,
    /** Simplified, time-ordered, bounded. Clip-local timestamps. */
    val keys: List<CropKeyframe>,
    val stats: ReframeStats,
    /** Settings as the engine returned them (enabled=false until Apply). */
    val engineSettings: AutoFrameSettings
)

/**
 * PHASE 5 seam: run the existing autoframe engine (no commit), then
 * simplify. The host (ViewModel) owns progress/cancel/stale/apply.
 */
interface SmartReframeAnalyzer {
    suspend fun analyze(
        clip: VideoClip,
        sourceUri: Uri,
        config: ReframeConfig,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): ReframeAnalysis
}

class LocalSmartReframeAnalyzer(
    private val engine: AiToolEngine
) : SmartReframeAnalyzer {
    override suspend fun analyze(
        clip: VideoClip,
        sourceUri: Uri,
        config: ReframeConfig,
        onProgress: (Float) -> Unit,
        checkCancelled: (() -> Unit)?
    ): ReframeAnalysis {
        val result = engine.process(
            clipId = clip.id,
            videoUri = sourceUri,
            options = AiToolOptions(
                strength = config.smoothing,
                targetAspectWidth = config.targetAspectWidth,
                targetAspectHeight = config.targetAspectHeight,
                commitToClip = false
            ),
            onProgress = { onProgress(it * 0.92f) }
        ).getOrThrow()
        checkCancelled?.invoke()
        // AiToolResult carries cropKeyframes next to autoFrame — keep
        // the pairing; a future engine change must not silently drop it.
        val raw = result.cropKeyframes
        val simplified = ReframePlanner.simplify(raw, config.simplifyTolerance, config.maxKeys)
        checkCancelled?.invoke()
        onProgress(1f)
        return ReframeAnalysis(
            clipId = clip.id,
            fileName = clip.fileName,
            clipDurationMs = clip.effectiveDurationMs,
            srcWidth = clip.width,
            srcHeight = clip.height,
            config = config,
            keys = simplified,
            stats = ReframePlanner.stats(
                rawKeys = raw,
                simplifiedKeys = simplified,
                durationMs = clip.effectiveDurationMs,
                trackingSummary = result.summary
            ),
            engineSettings = result.autoFrame ?: AutoFrameSettings(
                enabled = false,
                targetAspectWidth = config.targetAspectWidth,
                targetAspectHeight = config.targetAspectHeight,
                smoothing = config.smoothing
            )
        )
    }
}

/** Bounds check for a crop window against its source. */
fun validateWindow(win: CropWindow, srcW: Int, srcH: Int): Boolean =
    win.left >= 0 && win.top >= 0 && win.width > 0 && win.height > 0 &&
        win.left + win.width <= srcW && win.top + win.height <= srcH

/**
 * Source identity for stale protection: clip bytes + geometry +
 * target aspect. Timeline moves, trims, re-imports and aspect flips
 * all invalidate a parked preview.
 */
fun reframeSourceKey(clip: VideoClip, targetW: Float, targetH: Float): String =
    "${clip.id}|${clip.uri}|${clip.trimStartMs}|${clip.trimEndMs}|" +
        "${clip.durationMs}|${clip.timelineStartMs}|${clip.width}x${clip.height}|" +
        "${targetW}x$targetH"

/** True when every key lies inside [0, duration] in non-decreasing time. */
fun validateKeys(keys: List<CropKeyframe>, durationMs: Long): Boolean {
    var prev = -1L
    for (k in keys) {
        if (k.timeMs < 0 || k.timeMs > durationMs || k.timeMs < prev) return false
        if (k.centerX < 0f || k.centerX > 1f || k.centerY < 0f || k.centerY > 1f) return false
        prev = k.timeMs
    }
    return true
}
