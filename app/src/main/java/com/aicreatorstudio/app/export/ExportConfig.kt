package com.aicreatorstudio.app.export

import android.net.Uri
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset

/**
 * Configuration for a single video export.
 *
 * Kept framework-light (no Media3 types) so the data layer can be unit
 * tested on the JVM. The engine implementation in
 * [Media3TransformerExportEngine] is the only place that translates these
 * values into Media3 API calls.
 */
data class ExportConfig(
    val quality: ExportQuality = ExportQuality.HIGH_1080P,
    /**
     * Display name of the output file, without the `.mp4` extension.
     * The engine is responsible for sanitising this and prepending a
     * directory/prefix as needed.
     */
    val outputBaseName: String = "export",
    /**
     * Output width in pixels, derived from the project's canvas
     * preset. Defaults to 0 meaning "use [quality].widthPx" — used by
     * the engine to size the Media3 `Transformer` output.
     */
    val outputWidthPx: Int = 0,
    /**
     * Output height in pixels, derived from the project's canvas
     * preset. 0 means "use [quality].heightPx".
     */
    val outputHeightPx: Int = 0,
    /**
     * The canvas (project) aspect-ratio preset. The engine sizes the
     * output frame to this preset's `exportWidth` × `exportHeight` when
     * the preset is not [CanvasPreset.FREE]. Mirrors
     * [com.aicreatorstudio.app.model.VideoProject.canvasPreset] but is
     * passed explicitly so the engine doesn't need a project reference.
     */
    val canvasPreset: CanvasPreset = CanvasPreset.FREE,
    /**
     * The canvas background (letterbox color or blur). The engine
     * uses the solid color when filling the output letterbox; blur
     * backgrounds are v1 preview-only.
     */
    val canvasBackground: CanvasBackground = CanvasBackground.Black
) {
    /** Resolved output width: the explicit value if set, else the quality default. */
    val effectiveWidthPx: Int
        get() = if (outputWidthPx > 0) outputWidthPx else quality.widthPx
    /** Resolved output height: the explicit value if set, else the quality default. */
    val effectiveHeightPx: Int
        get() = if (outputHeightPx > 0) outputHeightPx else quality.heightPx
}

/**
 * Quality presets for v1 export. The values were chosen to match what the
 * device's encoder can comfortably produce on a mid-range phone: H.264 +
 * AAC everywhere, with a sensible bitrate per resolution.
 */
enum class ExportQuality(
    val displayName: String,
    val widthPx: Int,
    val heightPx: Int,
    val frameRate: Int,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int
) {
    STANDARD_720P(
        displayName = "720p Standard",
        widthPx = 1280,
        heightPx = 720,
        frameRate = 30,
        // ~4 Mbps for 720p30 — the common "good enough" YouTube/Snapchat tier.
        videoBitrateBps = 4_000_000,
        audioBitrateBps = 128_000
    ),
    HIGH_1080P(
        displayName = "1080p High",
        widthPx = 1920,
        heightPx = 1080,
        frameRate = 30,
        // ~6 Mbps for 1080p30 — Instagram / TikTok default.
        videoBitrateBps = 6_000_000,
        audioBitrateBps = 192_000
    ),
    ULTRA_4K(
        displayName = "4K Ultra",
        widthPx = 3840,
        heightPx = 2160,
        frameRate = 30,
        // ~20 Mbps for 4K30. Heavier on battery and time.
        videoBitrateBps = 20_000_000,
        audioBitrateBps = 192_000
    )
}

/** High-level stage of an in-flight export. */
enum class ExportStage(val displayName: String) {
    PREPARING("Preparing"),
    DECODING("Decoding"),
    APPLYING_EFFECTS("Applying effects"),
    ENCODING("Encoding"),
    FINALIZING("Finalizing")
}

/**
 * 2026-10-11 — how long the export may sit at a given progress value
 * before the watchdog declares it stalled.
 *
 * ## Why this is a *stall* timeout and not a total-duration timeout
 *
 * Media3's `Transformer.getProgress` is derived from the input
 * player's `position / duration` (see `ExoPlayerAssetLoader.getProgress`,
 * which returns `min(position * 100 / duration, 99)`) and is only
 * recomputed from `TransformerInternal.drainExportersInternal`, i.e.
 * **only while samples are still being drained**. Two consequences:
 *
 * 1. The reported value can never exceed 99%, and with audio sequences
 *    mixed into the composition it plateaus lower still, because
 *    `TransformerInternal.updateProgressInternal` averages over *all*
 *    sequences and `SequenceAssetLoader.getProgress` divides by the
 *    item count. A two-sequence (video + audio) project therefore
 *    flattens in the high 80s — which is exactly where users see the
 *    UI stop.
 * 2. During muxer finalization (`endInternal` →
 *    `MuxerWrapper.finishWritingAndMaybeRelease`) the library RESETS
 *    progress to 0 and stops updating it. So the last value the UI ever
 *    receives is that plateau, and the remaining work is invisible.
 *
 * A total-duration budget would therefore be wrong twice over: it would
 * fire on legitimate large exports (a 4K 10-minute render is slow but
 * healthy) and it would say nothing about whether work is still
 * happening. What actually distinguishes "slow" from "stuck" is whether
 * progress is still *advancing*, so the watchdog measures silence.
 *
 * [BASE] covers normal encode chunks; [FINALIZE] is larger because the
 * finalization phase legitimately reports no progress at all while the
 * muxer flushes and the file is closed.
 */
object ExportStallPolicy {
    /** Max silence while the encoder is still producing samples. */
    const val BASE_TIMEOUT_MS: Long = 120_000L

    /**
     * Max silence once progress has stopped advancing near the ceiling.
     * The muxer flush + file close for a large 4K file can take a while
     * on slow storage, so this is far more generous than [BASE_TIMEOUT_MS].
     */
    const val FINALIZE_TIMEOUT_MS: Long = 300_000L

    /**
     * Progress at or above which we assume Media3 has hit its own
     * ceiling and is finalizing rather than encoding — the point where
     * the library stops emitting meaningful updates.
     *
     * Chosen just under the 99% hard clamp so a genuine 98% still gets
     * the generous budget.
     */
    const val FINALIZE_AT_OR_ABOVE: Float = 0.85f

    /** The silence budget that applies at [progress]. */
    fun timeoutForMs(progress: Float): Long =
        if (progress >= FINALIZE_AT_OR_ABOVE) FINALIZE_TIMEOUT_MS else BASE_TIMEOUT_MS
}

/**
 * 2026-10-11 — pure stall detector for the export watchdog.
 *
 * Extracted from the engine so the decision is unit-testable without an
 * Android `Transformer`: "given the last observed progress, when it was
 * observed, and the current time, should we give up?".
 *
 * Returning a [Stall] rather than throwing keeps the caller in control
 * of *how* it fails, and keeps this class free of side effects.
 */
object ExportStallDetector {

    /** Outcome of a watchdog check. */
    data class Stall(
        /** Seconds of silence, for the human-readable message. */
        val stalledSeconds: Long,
        /** The progress value we were stuck at, 0f..1f. */
        val progress: Float,
        /** Which budget was exceeded, for logging/diagnostics. */
        val limitMs: Long,
        val inFinalizePhase: Boolean
    )

    /**
     * @param lastProgress last reported progress, 0f..1f
     * @param lastChangeMs wall-clock time of the last *change* in progress
     * @param nowMs current wall-clock time
     * @return non-null when the stall budget has been exceeded
     */
    fun check(
        lastProgress: Float,
        lastChangeMs: Long,
        nowMs: Long
    ): Stall? {
        val limit = ExportStallPolicy.timeoutForMs(lastProgress)
        val silentMs = (nowMs - lastChangeMs).coerceAtLeast(0L)
        if (silentMs < limit) return null
        return Stall(
            stalledSeconds = silentMs / 1000L,
            progress = lastProgress,
            limitMs = limit,
            inFinalizePhase = lastProgress >= ExportStallPolicy.FINALIZE_AT_OR_ABOVE
        )
    }

    /**
     * Human-readable, actionable failure message. Mentions what was
     * happening (encoding vs finalizing) so the user can tell a genuine
     * encode stall from a slow muxer flush.
     */
    fun messageFor(stall: Stall): String {
        val pct = (stall.progress * 100f).toInt()
        return if (stall.inFinalizePhase) {
            "Export stalled while finalizing the video at $pct% " +
                "(no progress for ${stall.stalledSeconds}s). The file may be on " +
                "slow storage, or it may be too large. Try again, or reduce the " +
                "export quality."
        } else {
            "Export stalled while encoding at $pct% " +
                "(no progress for ${stall.stalledSeconds}s). Try again, or " +
                "reduce the export quality."
        }
    }
}

/**
 * Snapshot of progress during the encoding stage.
 *
 * @param progress 0f..1f
 * @param currentStage which stage of the export pipeline we're in
 */
data class ExportProgress(
    val progress: Float,
    val currentStage: ExportStage
) {
    init {
        require(progress in 0f..1f) { "progress must be in 0f..1f, was $progress" }
    }
}

/**
 * Sealed state of a single export job, exposed by [VideoExportEngine] as a
 * flow. The UI binds to this directly.
 */
sealed interface ExportState {
    /** No export is running and none has finished. */
    data object Idle : ExportState

    /** Engine is preparing inputs (e.g. resolving MediaStore URIs). */
    data object Preparing : ExportState

    /** Export is in progress. */
    data class Exporting(val progress: ExportProgress) : ExportState

    /**
     * Export completed successfully.
     *
     * [outputUri] is `null` only in unit tests that exercise this
     * data class without an Android framework; the engine always
     * supplies a real `content://` or `file://` URI.
     */
    data class Succeeded(
        val outputUri: Uri?,
        val fileName: String,
        val fileSizeBytes: Long,
        val durationMs: Long
    ) : ExportState

    /** Export failed. [message] is user-facing; [cause] is the underlying throwable, if any. */
    data class Failed(val message: String, val cause: Throwable? = null) : ExportState

    /** User cancelled the export. */
    data object Cancelled : ExportState
}
