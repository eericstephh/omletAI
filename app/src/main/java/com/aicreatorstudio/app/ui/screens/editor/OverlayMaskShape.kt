package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.aicreatorstudio.app.model.OverlayMaskGeometry
import com.aicreatorstudio.app.model.OverlayMaskSettings
import com.aicreatorstudio.app.model.OverlayMaskType

/**
 * 2026-10-13 — the Compose half of the PiP shape-mask parity contract.
 *
 * Builds a [Shape] from [OverlayMaskGeometry.outline] — the **same
 * normalised polygon** `export/OverlayMaskRenderer.buildPath` builds its
 * `android.graphics.Path` from. There is no shape definition here, only
 * the normalised → size mapping (`point.x * size.width`) and the fill-type
 * flip for [OverlayMaskSettings.invert]. One outline, two renderers.
 *
 * ## Why not `CircleShape` / `RoundedCornerShape`
 *
 * Those would be a second, independent definition of the same geometry:
 * Compose's circle is a true ellipse outline, the export's is a 96-gon, and
 * a rounded rect's radius would be interpreted by Compose's own
 * `CornerSize` logic rather than by the 720-unit canvas convention the
 * export uses. Using the shared polygon makes the two edges identical
 * *including* their sampling, so a mask can never look subtly different in
 * the exported file — the failure mode this project keeps having to fix.
 */
private class OverlayMaskShapeImpl(
    private val settings: OverlayMaskSettings,
    /**
     * The **canvas** height in pixels, not the box's. The corner radius is
     * a dp length and dp→px goes through
     * [OverlayMaskGeometry.CANVAS_UNIT_PX], so it needs the canvas it is
     * laid out on; using the box height would make a small PiP's corners
     * proportionally rounder than a large one's.
     */
    private val canvasHeightPx: Int
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val s = OverlayMaskGeometry.sanitize(settings)
        val radiusFraction = if (s.type == OverlayMaskType.ROUNDED_RECT) {
            OverlayMaskGeometry.cornerRadiusFraction(
                cornerRadiusDp = s.cornerRadiusDp,
                boxWidthPx = size.width.toInt(),
                boxHeightPx = size.height.toInt(),
                canvasHeightPx = canvasHeightPx
            )
        } else {
            0f
        }
        val points = OverlayMaskGeometry.outline(s.type, radiusFraction)
        val path = Path()
        points.forEachIndexed { index, p ->
            val x = p.x * size.width
            val y = p.y * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        // NOTE: Compose's `PathFillType` has only `NonZero` and `EvenOdd` —
        // there is no inverse variant (unlike `android.graphics.Path`).
        // The preview therefore expresses inversion as a `DstOut` knockout
        // in `OverlayPipLayer`, which is the same "keep the outside"
        // operation the export gets from `INVERSE_EVEN_ODD`. See that
        // layer's `clipModifier`.
        return Outline.Generic(path)
    }
}

/**
 * The clip shape for [settings], or `null` when the mask clips nothing —
 * which the caller must read as "draw the overlay unclipped", never as
 * "draw nothing".
 *
 * @param canvasHeightPx see [OverlayMaskShapeImpl].
 */
internal fun overlayMaskShape(
    settings: OverlayMaskSettings,
    canvasHeightPx: Int
): Shape? {
    val s = OverlayMaskGeometry.sanitize(settings)
    if (!s.hasShape) return null
    return OverlayMaskShapeImpl(s, canvasHeightPx)
}

/**
 * The mask's own outline as a Compose [Path] in the box's pixel space,
 * for the preview's selection chrome (the dashed outline drawn around a
 * masked PiP). Same polygon as the clip, so the chrome cannot disagree
 * with the clip it decorates.
 */
internal fun overlayMaskOutlinePath(
    settings: OverlayMaskSettings,
    boxWidthPx: Float,
    boxHeightPx: Float,
    canvasHeightPx: Int
): Path? {
    val s = OverlayMaskGeometry.sanitize(settings)
    if (!s.hasShape) return null
    val radiusFraction = if (s.type == OverlayMaskType.ROUNDED_RECT) {
        OverlayMaskGeometry.cornerRadiusFraction(
            cornerRadiusDp = s.cornerRadiusDp,
            boxWidthPx = boxWidthPx.toInt(),
            boxHeightPx = boxHeightPx.toInt(),
            canvasHeightPx = canvasHeightPx
        )
    } else {
        0f
    }
    val points = OverlayMaskGeometry.outline(s.type, radiusFraction)
    if (points.size < 3) return null
    val path = Path()
    points.forEachIndexed { index, p ->
        val x = p.x * boxWidthPx
        val y = p.y * boxHeightPx
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
