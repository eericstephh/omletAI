package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.util.MotionTrackingGeometry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draggable, resizable ROI (region of interest) selector drawn over a
 * paused video frame — the user's entry point into Motion Tracking.
 *
 * ## Coordinate discipline
 *
 * All state is **normalized 0..1** against the *video frame rect*, never
 * against the containing composable. That distinction is the single
 * source of coordinate bugs in a feature like this: the video is
 * letterboxed inside the editor canvas, so normalizing against the
 * container makes the box drift whenever the user changes the canvas
 * aspect. [videoFrameRect] computes the true rect and every gesture is
 * mapped through it.
 *
 * Normalized storage also means the ROI stays valid across the three
 * resolutions in play at once — the UI container, the 360p analysis
 * frame, and the export frame — which is why the tracker accepts it
 * without a conversion contract.
 *
 * ## Gesture notes
 *
 * - `detectDragGestures`, **not** `detectTransformGestures`: pinch-zoom
 *   on this canvas would fight the editor's existing pan/zoom.
 * - `change.consume()` on every drag event, or the parent timeline
 *   scrubber steals the gesture. This is the most common bug in a
 *   composable like this.
 * - The whole overlay is inert while [enabled] is false, so the user
 *   cannot re-draw a box the tracker is already consuming.
 *
 * The visual language follows the existing editor chrome
 * (`FreePositionTextOverlay` in `EditorScreen`): a white 2dp outline
 * with a dark inner stroke so the box reads on both light and dark
 * footage, plus corner handles.
 */
@Composable
fun RoiSelectorOverlay(
    /** The displayed video frame's rect, in container pixels. */
    frameRect: Rect,
    /** Current ROI, normalized 0..1. */
    roi: androidx.compose.ui.geometry.Rect?,
    /** The container's measured size — needed to derive the frame rect. */
    containerSize: IntSize,
    /** Rotation-corrected source display size. */
    displayWidthPx: Int,
    displayHeightPx: Int,
    enabled: Boolean = true,
    onRoiChanged: (android.graphics.RectF) -> Unit
) {
    if (containerSize.width <= 0 || containerSize.height <= 0) return
    if (frameRect.width <= 1f || frameRect.height <= 1f) return

    val density = LocalDensity.current
    val handlePx = with(density) { HANDLE_DP.dp.toPx() }
    val hitPx = with(density) { (HANDLE_DP * 1.8f).dp.toPx() }

    var container by remember { mutableStateOf(IntSize.Zero) }
    var mode by remember { mutableStateOf(RoiDragMode.MOVE) }

    // The live box, in container pixels. Kept local so a drag does not
    // round-trip through the ViewModel at gesture rate (which would
    // recompose the whole editor on every touch move).
    var boxPx by remember(frameRect, containerSize) {
        mutableStateOf(roiToContainerOrDefault(roi, frameRect))
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(0, 0) }
            .size(
                width = with(density) { (frameRect.width).toDp() },
                height = with(density) { (frameRect.height).toDp() }
            )
            .offset {
                IntOffset(frameRect.left.roundToInt(), frameRect.top.roundToInt())
            }
            .onSizeChanged { container = it }
            .pointerInput(enabled, frameRect) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { start ->
                        mode = hitTest(start, boxPx, hitPx)
                    },
                    onDrag = { change, drag ->
                        // Consume unconditionally: an unconsumed move
                        // event propagates to the scrubber behind us.
                        change.consume()
                        boxPx = applyDrag(boxPx, drag, mode, frameRect, handlePx, hitPx)
                        onRoiChanged(normalize(boxPx, frameRect))
                    },
                    onDragEnd = {
                        onRoiChanged(normalize(boxPx, frameRect))
                    },
                    onDragCancel = { }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = boxPx
            val topLeft = Offset(r.left, r.top)
            val sz = Size(r.width, r.height)

            // Dark halo underneath: keeps the box legible over bright
            // footage without changing the white outline's weight.
            drawRect(
                color = Color.Black.copy(alpha = 0.45f),
                topLeft = topLeft,
                size = sz,
                style = Stroke(width = OUTLINE_HALO_DP.dp.toPx())
            )
            drawRect(
                color = Color.White,
                topLeft = topLeft,
                size = sz,
                style = Stroke(width = OUTLINE_DP.dp.toPx())
            )

            // Corner handles.
            val corners = listOf(
                r.topLeft, r.topRight, r.bottomLeft, r.bottomRight
            )
            corners.forEach { p ->
                drawCircle(Color.White, radius = handlePx / 2f, center = p)
                drawCircle(
                    Color.Black.copy(alpha = 0.7f),
                    radius = handlePx / 2f,
                    center = p,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

/** Which part of the ROI a drag is manipulating. */
private enum class RoiDragMode { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Hit-test the drag origin against the four corner handles, falling
 * back to a whole-box move. Corner detection wins over move so a touch
 * near a corner resizes rather than nudging the box.
 */
private fun hitTest(
    p: Offset,
    box: Rect,
    hitRadius: Float
): RoiDragMode {
    val d = hitRadius
    val tl = box.topLeft
    val tr = box.topRight
    val bl = box.bottomLeft
    val br = box.bottomRight
    return when {
        (p - tl).getDistance() <= d -> RoiDragMode.TOP_LEFT
        (p - tr).getDistance() <= d -> RoiDragMode.TOP_RIGHT
        (p - bl).getDistance() <= d -> RoiDragMode.BOTTOM_LEFT
        (p - br).getDistance() <= d -> RoiDragMode.BOTTOM_RIGHT
        else -> RoiDragMode.MOVE
    }
}

/**
 * Apply a drag delta to [box] according to [mode], in container pixels,
 * clamped so the box can neither leave the frame nor invert.
 *
 * The minimum size floor is expressed in **frame fraction**, not pixels,
 * so the constraint is resolution-independent — a box that is legal on a
 * tablet must not be illegal on a phone.
 */
private fun applyDrag(
    box: Rect,
    drag: Offset,
    mode: RoiDragMode,
    frame: Rect,
    handlePx: Float,
    hitPx: Float
): Rect {
    val minSide = max(frame.width, frame.height) * MotionTrackingGeometry.MIN_ROI_SIDE
    val minSidePx = max(minSide, handlePx * 2f)

    var l = box.left
    var t = box.top
    var r = box.right
    var b = box.bottom

    when (mode) {
        RoiDragMode.MOVE -> {
            val w = r - l
            val h = b - t
            l = (l + drag.x).coerceIn(frame.left, frame.right - w)
            t = (t + drag.y).coerceIn(frame.top, frame.bottom - h)
            r = l + w
            b = t + h
        }
        RoiDragMode.TOP_LEFT -> {
            l = (l + drag.x).coerceIn(frame.left, r - minSidePx)
            t = (t + drag.y).coerceIn(frame.top, b - minSidePx)
        }
        RoiDragMode.TOP_RIGHT -> {
            r = (r + drag.x).coerceIn(l + minSidePx, frame.right)
            t = (t + drag.y).coerceIn(frame.top, b - minSidePx)
        }
        RoiDragMode.BOTTOM_LEFT -> {
            l = (l + drag.x).coerceIn(frame.left, r - minSidePx)
            b = (b + drag.y).coerceIn(t + minSidePx, frame.bottom)
        }
        RoiDragMode.BOTTOM_RIGHT -> {
            r = (r + drag.x).coerceIn(l + minSidePx, frame.right)
            b = (b + drag.y).coerceIn(t + minSidePx, frame.bottom)
        }
    }
    return Rect(min(l, r), min(t, b), max(l, r), max(t, b))
}

/** Container pixels -> normalized frame coordinates. */
private fun normalize(box: Rect, frame: Rect): android.graphics.RectF {
    if (frame.width <= 0f || frame.height <= 0f) {
        return android.graphics.RectF(
            MotionTrackingGeometry.DEFAULT_ROI
        )
    }
    return MotionTrackingGeometry.rectToNormalized(
        android.graphics.RectF(box.left, box.top, box.right, box.bottom),
        frame
    )
}

/** Normalized ROI -> container pixels, or a sensible default box. */
private fun roiToContainerOrDefault(
    roi: androidx.compose.ui.geometry.Rect?,
    frame: Rect
): Rect {
    if (roi == null) {
        // Default: the middle 30% of the frame, so there is always
        // something draggable on screen rather than an empty canvas.
        val d = MotionTrackingGeometry.DEFAULT_ROI
        val c = MotionTrackingGeometry.rectToContainer(d, frame)
        return Rect(c.left, c.top, c.right, c.bottom)
    }
    val c = MotionTrackingGeometry.rectToContainer(
        android.graphics.RectF(roi.left, roi.top, roi.right, roi.bottom),
        frame
    )
    return Rect(c.left, c.top, c.right, c.bottom)
}

private const val OUTLINE_DP = 1.5f
private const val OUTLINE_HALO_DP = 3.5f
private const val HANDLE_DP = 11f
