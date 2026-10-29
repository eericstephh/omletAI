package com.aicreatorstudio.app.model

/**
 * How a clip should fill its canvas in the absence of user-applied
 * manual transforms.
 *
 * - [FIT]: keep the whole clip visible. The clip scales so the longest
 *   dimension fits the canvas, with letterbox bars where the other
 *   dimension falls short. The same as ExoPlayer's
 *   `AspectRatioFrameLayout.RESIZE_MODE_FIT`.
 * - [FILL]: scale the clip so the shortest dimension covers the canvas,
 *   cropping the rest. Eliminates letterbox bars but may crop the source.
 *   Equivalent to `RESIZE_MODE_ZOOM`.
 * - [MANUAL]: the user has applied a [ClipTransform] via the Transform
 *   panel (pinch-to-zoom, drag-to-pan). The render path reads
 *   `VideoClip.transform` directly.
 */
enum class FillMode { FIT, FILL, MANUAL }

/**
 * Per-clip geometric transform applied on top of the [FillMode] baseline.
 *
 * Stored on the [VideoClip] so the transform survives switching clips
 * and persists in the project. Defaults are identity.
 *
 * @param scale uniform scale multiplier; 1f = no change. Values < 1f
 *   shrink the clip inside the canvas; > 1f zooms in.
 * @param offsetX horizontal pan in *canvas fractions*; -0.5f..+0.5f
 *   covers the practical pan range. 0f = centered.
 * @param offsetY vertical pan in *canvas fractions*; -0.5f..+0.5f.
 *   0f = centered.
 * @param rotation additional rotation in degrees added on top of the
 *   clip's existing [VideoClip.rotation]. The preview applies the
 *   combined rotation; the export only honours multiples of 90.
 * @param flipH mirror across the vertical axis
 * @param flipV mirror across the horizontal axis
 */
data class ClipTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false
) {
    /** True if this transform is the default identity. */
    val isIdentity: Boolean
        get() = scale == 1f && offsetX == 0f && offsetY == 0f &&
            rotation == 0 && !flipH && !flipV
}

/**
 * Snap a rotation in degrees onto the quarter-turn grid the render
 * path can actually resolve.
 *
 * ## Why the grid exists
 *
 * A clip's rotation is applied by the export's
 * `ScaleAndRotateTransformation`, which grows the frame to the
 * **rotated bounding box** so no source pixel is cropped. That
 * bounding box is only exact for multiples of 90 — for an arbitrary
 * angle it is an irrational-ish rectangle, and the canvas plate
 * (`CanvasLetterbox.rotatedBounds`) and the frame geometry would have
 * to agree on a rounding that neither of them can make exact. The
 * export therefore resolves the rotation to a quarter turn
 * (`RenderSegment.rotationDegrees` is documented as always being
 * `0` / `90` / `180` / `270`).
 *
 * ## Why the snap is *down*, not to the nearest
 *
 * Snapping **down** keeps a user's 89° at 0° rather than silently
 * moving the frame to 90°, which is what round-to-nearest would do.
 *
 * ## Why it lives here
 *
 * The editor *writes* the rotation and the export *reads* it. When
 * they disagreed, a stored 45° rendered as 45° in the preview and as
 * 0° in the exported file — the exact "the preview and the export
 * disagree" class of bug this project tracks. One rule, one place.
 */
fun snapRotationToQuarterTurn(degrees: Int): Int {
    val mod = degrees % 360
    val positive = if (mod < 0) mod + 360 else mod
    return (positive / 90) * 90
}

/**
 * The rotation to store after a +90° step.
 *
 * The **clip's own** rotation wins over the global mirror: the clip is
 * what the export renders, so repeated taps must accumulate on it
 * (90 → 180 → 270 → 0). Using the mirror instead would restart the
 * count on every tap.
 *
 * @param clipRotation the clip's current rotation, or `null` when no
 *   clip is selected — then the global [current] value is the base.
 * @param current the global mirror's current value.
 */
fun nextQuarterTurnRotation(clipRotation: Int?, current: Int): Int =
    snapRotationToQuarterTurn((clipRotation ?: current) + 90)

/**
 * Callback for [ClipTransform] updates.
 *
 * Modelled as a SAM interface (not a `(Float?, Float?, …) -> Unit` lambda)
 * because Kotlin does not allow named arguments at function-type call
 * sites, and `fun interface` does not allow default values on abstract
 * methods. Callers that only want to change a single field read the
 * current value from the existing [ClipTransform] and pass it back
 * unchanged for the other parameters — the [VideoClip] copy is cheap.
 */
fun interface ClipTransformUpdater {
    fun update(
        scale: Float?,
        offsetX: Float?,
        offsetY: Float?,
        rotation: Int?,
        flipH: Boolean?,
        flipV: Boolean?
    )
}

/**
 * Same as [ClipTransformUpdater] but addressed at a specific clip by id.
 *
 * Used by the SubPanel→ViewModel dispatch because Kotlin does not allow
 * named arguments at function-type call sites.
 */
fun interface ClipIdTransformUpdater {
    fun update(
        clipId: String,
        scale: Float?,
        offsetX: Float?,
        offsetY: Float?,
        rotation: Int?,
        flipH: Boolean?,
        flipV: Boolean?
    )
}
