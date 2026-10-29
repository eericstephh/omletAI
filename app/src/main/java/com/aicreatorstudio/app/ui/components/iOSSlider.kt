package com.aicreatorstudio.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright

/**
 * iOS-style slider. Thick, rounded thumb with a soft outer glow, a pill-shaped
 * track, and gradient-filled active portion. Drag with finger or tap anywhere
 * on the track to jump — both gestures feel the same as UISlider on iOS.
 *
 * @param value current value in [valueRange]
 * @param onValueChange called on every drag/tap with the new value
 * @param onValueChangeFinished called once when the user releases
 */
@Composable
fun iOSSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = iOSBlue,
    trackColor: Color = Hairline,
    thumbGlow: Color = iOSBlueBright
) {
    val density = LocalDensity.current
    val thumbRadiusDp = 13.dp
    val trackHeightDp = 4.dp
    val thumbRadiusPx = with(density) { thumbRadiusDp.toPx() }
    val trackHeightPx = with(density) { trackHeightDp.toPx() }
    val touchTargetSizeDp = 28.dp

    var widthPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val dragScale by animateFloatAsState(if (isDragging) 1.25f else 1f, label = "thumb-scale")

    // Outer ring grows while dragging
    val ringAlpha by animateFloatAsState(if (isDragging) 0.5f else 0f, label = "ring-alpha")

    fun posToValue(pos: Float): Float {
        val w = (widthPx - 2 * thumbRadiusPx).coerceAtLeast(1f)
        val range = valueRange.endInclusive - valueRange.start
        if (range == 0f) return valueRange.start
        val raw = ((pos - thumbRadiusPx) / w).coerceIn(0f, 1f)
        return valueRange.start + raw * range
    }

    fun valueToX(v: Float): Float {
        val w = (widthPx - 2 * thumbRadiusPx).coerceAtLeast(1f)
        val range = valueRange.endInclusive - valueRange.start
        if (range == 0f) return thumbRadiusPx
        val frac = ((v - valueRange.start) / range).coerceIn(0f, 1f)
        return thumbRadiusPx + frac * w
    }

    val thumbX = valueToX(value)

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(touchTargetSizeDp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(valueRange) {
                // Two detectors cooperate via a single pointerInput: the
                // tap detector runs first and handles "tap to jump" without
                // any movement, while the drag detector handles dragging.
                // Both are guarded by `widthPx > 0` so they do nothing on
                // the first frame, before the slider has been measured.
                detectTapGestures(
                    onTap = { offset ->
                        if (widthPx > 0f) {
                            onValueChange(posToValue(offset.x))
                        }
                    }
                )
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (widthPx > 0f) {
                            isDragging = true
                            onValueChange(posToValue(it.x))
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    }
                ) { change, _ ->
                    change.consume()
                    val upper = (widthPx - thumbRadiusPx).coerceAtLeast(thumbRadiusPx)
                    val newX = change.position.x.coerceIn(thumbRadiusPx, upper)
                    onValueChange(posToValue(newX))
                }
            }
    ) {
        // Track + thumb drawn manually so we can use a gradient active fill
        // and a soft glow around the thumb.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp + 4.dp)
        ) {
            val centerY = size.height / 2f
            val trackTop = centerY - trackHeightPx / 2f

            // Inactive track (rounded pill)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2f)
            )

            // Active track (gradient)
            if (thumbX > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(activeColor.copy(alpha = 0.85f), activeColor),
                        startX = 0f,
                        endX = thumbX
                    ),
                    topLeft = Offset(0f, trackTop),
                    size = Size(thumbX, trackHeightPx),
                    cornerRadius = CornerRadius(trackHeightPx / 2f)
                )
            }

            // Thumb outer glow
            if (ringAlpha > 0f) {
                drawCircle(
                    color = thumbGlow.copy(alpha = ringAlpha * 0.6f),
                    radius = thumbRadiusPx * dragScale * 1.6f,
                    center = Offset(thumbX, centerY)
                )
            }
            // Thumb shadow (soft black)
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = thumbRadiusPx * dragScale + 1.5f,
                center = Offset(thumbX, centerY + 1f)
            )
            // Thumb body (white with subtle gradient)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color(0xFFF2F2F7)),
                    center = Offset(thumbX - thumbRadiusPx * 0.3f, centerY - thumbRadiusPx * 0.3f),
                    radius = thumbRadiusPx
                ),
                radius = thumbRadiusPx * dragScale,
                center = Offset(thumbX, centerY)
            )
        }
    }
}
