package com.aicreatorstudio.app.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * PHASE B5.5 — pure-function extensions on [TextOverlay] used by
 * the preview hit-test / drag / pinch / rotate pipeline.
 *
 * Kept out of the data class itself so the model stays a value
 * type and the extension surface can grow without churning
 * `TextOverlay`'s `equals` / `hashCode` / `copy`.
 *
 * Conventions (must match the renderer at `EditorScreen.kt:1654`
 * — the active-subtitle path is the only path that draws
 * subtitles today; the hit-test rect must match what the user
 * sees):
 *  - `position.x` and `position.y` are normalized 0..1 against
 *    the canvas. `position` is the **anchor** of the caption:
 *    the center for `SubtitleAlignmentV.CENTER` captions, the
 *    bottom band for `SubtitleAlignmentV.BOTTOM` captions, etc.
 *  - The rect width/height are derived from `fontSizeSp` (in
 *    sp) translated to a fraction of the canvas short side.
 *    The translation factor (0.6 * sp / 320) matches the
 *    pre-B5.5 renderer's visual size on a 320 dp-short-side
 *    canvas (a typical 9:16 phone preview in the editor).
 *  - The result is a [Rect] in the SAME normalized coordinate
 *    system as `position`, so [Rect.contains] works with the
 *    same `Offset` the renderer uses to place the subtitle.
 */

/** Public form of the existing private `isVisibleAt` member. */
fun TextOverlay.isVisibleAtTime(positionMs: Long): Boolean =
    positionMs in startTimeMs..endTimeMs

/**
 * The visual rect of this overlay in normalized canvas
 * coordinates (0..1). The anchor is [TextOverlay.position]
 * interpreted as the **center** of the rect; this is the
 * simplest invariant and matches `SubtitleAlignmentV.CENTER`
 * directly. For BOTTOM / TOP captions the user can still
 * select via the timeline — the hit-test still works because
 * `position` is the explicit anchor the user picked.
 *
 * [shortSidePx] is the canvas short-side in **pixels** (used
 * to convert the sp-based font size to a fraction of the
 * canvas short side). If 0, the function falls back to the
 * 320-dp default (a typical phone preview).
 */
fun TextOverlay.boundsNormalized(shortSidePx: Int = 0): Rect {
    val density = if (shortSidePx > 0) shortSidePx.toFloat() / 320f else 1f
    // Match the renderer's effective caption width: roughly
    // 0.6 sp of width per sp of font size, with the
    // `maxTextWidthFraction` cap from the active style (or
    // 0.9 if no style is set — the legacy default).
    val effectiveWidth = (fontSizeSp * 0.6f / density)
        .coerceAtMost(0.9f)
    val effectiveHeight = (fontSizeSp * 1.4f / density)
        .coerceAtMost(0.4f)
    val left = (position.x - effectiveWidth / 2f).coerceIn(0f, 1f)
    val top = (position.y - effectiveHeight / 2f).coerceIn(0f, 1f)
    val right = (position.x + effectiveWidth / 2f).coerceIn(0f, 1f)
    val bottom = (position.y + effectiveHeight / 2f).coerceIn(0f, 1f)
    return Rect(left, top, right, bottom)
}

/**
 * `true` if the normalized point (in canvas 0..1 coords) falls
 * inside this overlay's visual rect AND the overlay is
 * currently visible at [positionMs]. Combines bounds + time
 * so callers don't have to check both.
 */
fun TextOverlay.hitTest(pointNorm: Offset, positionMs: Long, shortSidePx: Int = 0): Boolean =
    isVisibleAtTime(positionMs) && boundsNormalized(shortSidePx).contains(pointNorm)
