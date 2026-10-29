package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.ai.reframe.ReframePlanner
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.util.MotionTrackingGeometry
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Preview-side placement of the **Auto Frame crop** on the live video
 * canvas.
 *
 * ## Why this exists
 *
 * Before 2026-10-17 the animated crop existed only in the export
 * pre-render (`AiExportHooks.reframe`): the editor preview kept showing
 * the uncropped source, so a committed Auto Frame looked like a no-op
 * until the user exported. This helper applies the SAME per-frame crop
 * to the preview video layer, driven by the same trajectory the export
 * interpolates.
 *
 * ## Parity contract
 *
 * The crop window is computed by [ReframePlanner.cropWindowFor] — the
 * exact function the export hook now delegates to — sourced at the
 * clip-local playhead via `MotionTrackingGeometry.interpolateKeyframe`
 * (the shared keyframe sampler). The returned transform is a uniform
 * scale about the canvas centre plus a translation, equivalent to:
 * crop the letterboxed source, then blow the crop up to fill the
 * canvas. When the clip's manual transform (pan/zoom/rotate) is ALSO
 * active, the call site must apply this placement INNERMOST (a
 * dedicated `graphicsLayer` before the manual one) — which mirrors the
 * export order (reframe pre-pass runs **before** the Transformer's
 * stage-1 geometry).
 *
 * All math is plain floats on normalized coordinates — no
 * `android.graphics` — so the JVM test suite can pin it.
 *
 * Approximation note: the preview canvas is the target-ratio canvas
 * box. When the user chose a reframe aspect that differs from the
 * canvas preset, the export output takes the reframe aspect; the
 * preview keeps showing it inside the canvas box (equivalent geometry
 * only when the two ratios agree, which is the common case since the
 * reframe aspect is picked from the canvas aspect in the panel).
 *
 * @param keyframes the clip's committed crop trajectory (reframe
 *   entries have NO extent; Motion Tracking entries do and are mutually
 *   exclusive with an enabled Auto Frame).
 * @param settings the clip's [AutoFrameSettings] (`enabled`).
 * @param clipLocalMs playhead relative to the clip's start (same
 *   quantity the export subtracts from each frame's PTS).
 * @param sourceAspect rotation-corrected display aspect of the source
 *   (width / height), the same value the [com.aicreatorstudio.app.model.
 *   CanvasPreset] letterboxing already uses.
 * @return scale + translation in view px for the video layer, or `null`
 *   when there is nothing to preview (callers keep the untransformed
 *   video — the "no crop" state stays honest).
 */
data class AutoFramePreviewPlacement(
    /** Uniform scale, >= 1f, applied about the canvas centre. */
    val scale: Float,
    /** Layer translation in view px (applied after scaling). */
    val translationXPx: Float,
    val translationYPx: Float
)

fun autoFramePreviewPlacement(
    keyframes: List<CropKeyframe>,
    settings: AutoFrameSettings,
    clipLocalMs: Long,
    sourceAspect: Float,
    canvasWidthPx: Float,
    canvasHeightPx: Float
): AutoFramePreviewPlacement? {
    if (!settings.enabled || keyframes.isEmpty()) return null
    if (sourceAspect <= 0f ||
        canvasWidthPx <= 0f || canvasHeightPx <= 0f ||
        canvasWidthPx.isNaN() || canvasHeightPx.isNaN()
    ) return null
    if (settings.targetAspectWidth <= 0f || settings.targetAspectHeight <= 0f) return null

    val k = MotionTrackingGeometry.interpolateKeyframe(keyframes, clipLocalMs)

    // Synthetic source dims: any size with the display aspect produces
    // identical crop fractions (the math is ratio-invariant).
    val srcW = 1000
    val srcH = (1000f / sourceAspect).roundToInt()
    if (srcH <= 0) return null

    val win = try {
        ReframePlanner.cropWindowFor(
            srcW, srcH,
            settings.targetAspectWidth, settings.targetAspectHeight,
            k.centerX, k.centerY
        )
    } catch (_: IllegalArgumentException) {
        return null
    }
    val cwFrac = win.width.toFloat() / srcW
    val chFrac = win.height.toFloat() / srcH
    if (cwFrac <= 0f || chFrac <= 0f) return null

    // The letterboxed (FIT) source rect inside the canvas box.
    val sw = min(canvasWidthPx, canvasHeightPx * sourceAspect)
    val sh = sw / sourceAspect
    if (sw <= 0f || sh <= 0f) return null

    // Scale so the crop window covers the whole canvas ("min" keeps the
    // window honest when canvas ratio != reframe aspect: never crop
    // beyond the window into letterbox).
    val scale = min(
        canvasWidthPx / (cwFrac * sw),
        canvasHeightPx / (chFrac * sh)
    )
    if (scale.isNaN() || scale < 1f) return null

    // Crop-window centre in source-fraction space -> view px offset
    // from the canvas centre; negated so the crop centre lands on the
    // canvas centre after scaling.
    val ccx = (win.left + win.width / 2f) / srcW
    val ccy = (win.top + win.height / 2f) / srcH
    return AutoFramePreviewPlacement(
        scale = scale,
        translationXPx = -(ccx - 0.5f) * sw * scale,
        translationYPx = -(ccy - 0.5f) * sh * scale
    )
}
