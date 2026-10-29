package com.aicreatorstudio.app.model

/**
 * Rotation-aware resolution of a video's **display** dimensions.
 *
 * ## Why this is its own type
 *
 * `MediaMetadataRetriever` reports a container's coded frame
 * (`METADATA_KEY_VIDEO_WIDTH` / `_HEIGHT`) and its rotation
 * (`METADATA_KEY_VIDEO_ROTATION`) as two independent facts, and the
 * display size is the pair *after* the rotation is applied. Getting that
 * combination wrong is not a cosmetic bug: the display aspect ratio feeds
 * the preview canvas, the export frame, the letterbox geometry and the
 * motion-tracking frame rect, so one wrong swap moves every one of them.
 *
 * The swap used to live inline in
 * [com.aicreatorstudio.app.video.VideoMetadataExtractor] and was
 * untestable there (it needs a real `MediaMetadataRetriever`). It is a
 * pure function of three integers, so it lives here, where the JVM test
 * suite can pin it — this project has no Robolectric and no mocking
 * library, and pure functions are the only testable seam.
 *
 * ## The two failure modes this defends against
 *
 * 1. **Rotation is not normalised.** `METADATA_KEY_VIDEO_ROTATION` is
 *    documented as degrees, but containers and devices in the wild hand
 *    back `-90`, `270`, `450` and `0`. An equality test against `90`/`270`
 *    therefore silently drops a legitimate quarter turn and the clip
 *    renders sideways. [normalizeRotation] folds any value into `[0, 360)`.
 *
 * 2. **The retriever has already applied the rotation.** On a meaningful
 *    set of Android versions, devices and container layouts the reported
 *    width/height are the *display* dimensions, not the coded ones. Swapping
 *    them again then flips an axis that was never rotated — a landscape
 *    clip comes out portrait, or vice versa. Metadata cannot tell us which
 *    convention we are in, so [resolve] accepts an optional **probe**: the
 *    dimensions of one actually-decoded frame. `MediaMetadataRetriever
 *    .getFrameAtTime` returns a rotation-applied `Bitmap`, so its
 *    orientation is ground truth for what the user will see, and when it
 *    contradicts the metadata-derived orientation the probe wins.
 *
 * The probe is advisory only: it can decide *orientation*, never pixel
 * counts. [resolve] always returns the container's own magnitude, so a
 * probe can never invent a resolution.
 */
object VideoDimensions {

    /**
     * Folds [rotation] into `[0, 360)`.
     *
     * `-90 -> 270`, `270 -> 270`, `450 -> 90`, `0 -> 0`, `360 -> 0`.
     * Kept separate from [isQuarterTurn] because the normalised value is
     * also what a caller would persist or display.
     */
    fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    /**
     * Whether [rotation] is a quarter turn — the only rotations that
     * exchange the frame's axes, and therefore the only ones that swap
     * width and height. `180` deliberately returns `false`: a half turn
     * leaves the frame's shape untouched.
     */
    fun isQuarterTurn(rotation: Int): Boolean {
        val r = normalizeRotation(rotation)
        return r == 90 || r == 270
    }

    /**
     * The display dimensions for a container frame of [rawWidth] ×
     * [rawHeight] carrying [rotation] degrees of metadata.
     *
     * @param probeWidth width of one decoded frame, or `<= 0` when no probe
     *   was taken. `getFrameAtTime` applies rotation, so a probe tells us
     *   the truth about orientation.
     * @param probeHeight height of one decoded frame, or `<= 0` when absent.
     * @return `width to height`. The magnitudes always come from
     *   [rawWidth] / [rawHeight]; only the *order* is ever decided by the
     *   probe.
     *
     * Degenerate input (`rawWidth <= 0 || rawHeight <= 0`) is returned
     * unchanged rather than guessed at. Callers must treat a non-positive
     * dimension as "unknown" and fall back — inventing a plausible pair
     * here is what turns unreadable metadata into a wrong aspect ratio
     * that is indistinguishable from a correct one.
     */
    fun resolve(
        rawWidth: Int,
        rawHeight: Int,
        rotation: Int,
        probeWidth: Int = 0,
        probeHeight: Int = 0
    ): Pair<Int, Int> {
        if (rawWidth <= 0 || rawHeight <= 0) return rawWidth to rawHeight

        val metadataPair =
            if (isQuarterTurn(rotation)) rawHeight to rawWidth else rawWidth to rawHeight

        // A probe can only arbitrate between two *orientations*. A square
        // probe has no orientation to offer, and a missing one has no
        // opinion, so both fall through to the metadata reading.
        val probeUsable = probeWidth > 0 && probeHeight > 0 && probeWidth != probeHeight
        if (!probeUsable) return metadataPair

        val probeIsLandscape = probeWidth > probeHeight
        val metadataIsLandscape = metadataPair.first > metadataPair.second
        if (probeIsLandscape == metadataIsLandscape) return metadataPair

        // Disagreement: the container already carried the rotation, so the
        // swap was wrong. Keep the container's own pixel magnitudes and
        // take the orientation the decoded frame proves.
        val longSide = maxOf(rawWidth, rawHeight)
        val shortSide = minOf(rawWidth, rawHeight)
        return if (probeIsLandscape) longSide to shortSide else shortSide to longSide
    }

    /**
     * `width / height`, or `0f` when either dimension is unknown.
     *
     * `0f` is the "no opinion" sentinel every consumer already handles:
     * [CanvasPreset.effectiveAspectRatio] and the editor preview both treat
     * a non-positive ratio as "fall back", which is the correct reading of
     * unreadable metadata.
     */
    fun aspectRatio(width: Int, height: Int): Float =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f
}
