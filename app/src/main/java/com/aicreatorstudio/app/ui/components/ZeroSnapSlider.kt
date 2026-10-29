package com.aicreatorstudio.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import kotlin.math.abs
import kotlin.math.sign

/**
 * iOS-style slider that *snaps to zero* with a gentle haptic tick the
 * moment the dragged value crosses `0`. The visual cue is a centred
 * tick mark on the track.
 *
 *  - **Snap tolerance** is 4% of the value range, so the user can still
 *    land a precise value near zero without an accidental snap.
 *  - **Haptic tick** fires only on the *crossing* event (not while
 *    holding at zero), so the user doesn't get repeated buzzes.
 *  - **Visual cue**: a thin vertical pip on the track at the zero
 *    position so the user can see the snap target before they let go.
 *  - **Track fill** is split into a positive (blue gradient) and a
 *    negative (warm grey) half so the user can read the sign at a
 *    glance — like the iOS Accessibility slider.
 *
 * @param value current value, expected in [valueRange] (typically
 *   symmetric around 0 for tone/HSL controls)
 * @param onValueChange called every drag/tap frame
 * @param onValueChangeFinished called once when the user releases
 * @param activeColor positive-side fill colour
 * @param negativeColor negative-side fill colour
 * @param showZeroTick whether to draw the zero-position pip on the track
 * @param enabled when false, the slider ignores taps/drags and renders
 *   at reduced alpha. Defaults to true.
 */
@Composable
fun ZeroSnapSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = iOSBlue,
    negativeColor: Color = iOSBlueBright,
    showZeroTick: Boolean = true,
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    val thumbRadiusDp = 13.dp
    val trackHeightDp = 4.dp
    val thumbRadiusPx = with(density) { thumbRadiusDp.toPx() }
    val trackHeightPx = with(density) { trackHeightDp.toPx() }
    val touchTargetSizeDp = 28.dp

    var widthPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // The sign of the value the *previous* frame — used to detect the
    // zero-crossing and fire the haptic tick exactly once.
    var prevSign by remember { mutableFloatStateOf(sign(value)) }
    // Local "snapped" flag so we can suppress repeated haptics while
    // the user holds at zero.
    var hasSnappedThisGesture by remember { mutableStateOf(false) }

    val dragScale by animateFloatAsState(if (isDragging) 1.25f else 1f, label = "thumb-scale")
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

    fun valueToZeroX(): Float = valueToX(0f)

    val thumbX = valueToX(value)
    val zeroX = valueToZeroX()

    fun maybeSnapAndHaptic(raw: Float): Float {
        // Snap zone is 4% of range. If the value lands inside it,
        // quantise to 0 and fire a single tick.
        val range = valueRange.endInclusive - valueRange.start
        val tolerance = range * 0.04f
        return if (abs(raw) <= tolerance) {
            if (!hasSnappedThisGesture) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                hasSnappedThisGesture = true
            }
            0f
        } else raw
    }

    // If the value prop changes from outside (e.g. Reset All), make
    // sure the snap flag resets so the next drag is fresh.
    LaunchedEffect(value) {
        if (!isDragging) {
            hasSnappedThisGesture = false
            prevSign = sign(value)
        }
    }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(touchTargetSizeDp)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(valueRange, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { offset ->
                        if (widthPx > 0f) {
                            val raw = posToValue(offset.x)
                            onValueChange(maybeSnapAndHaptic(raw))
                        }
                    }
                )
            }
            .pointerInput(valueRange, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        if (widthPx > 0f) {
                            isDragging = true
                            hasSnappedThisGesture = false
                            prevSign = sign(value)
                            val raw = posToValue(it.x)
                            onValueChange(maybeSnapAndHaptic(raw))
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        hasSnappedThisGesture = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                        hasSnappedThisGesture = false
                        onValueChangeFinished?.invoke()
                    }
                ) { change, _ ->
                    change.consume()
                    val upper = (widthPx - thumbRadiusPx).coerceAtLeast(thumbRadiusPx)
                    val newX = change.position.x.coerceIn(thumbRadiusPx, upper)
                    val raw = posToValue(newX)
                    val newVal = maybeSnapAndHaptic(raw)
                    val newSign = sign(newVal)
                    // Fire a softer tick for ordinary value changes too,
                    // so the slider feels alive without being noisy.
                    if (newSign != prevSign && newVal != 0f) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    prevSign = newSign
                    onValueChange(newVal)
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp + 6.dp)
        ) {
            val centerY = size.height / 2f
            val trackTop = centerY - trackHeightPx / 2f

            // 1) Inactive base track
            drawRoundRect(
                color = Hairline,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2f)
            )

            // 2) Negative-side fill (thumb < 0)
            if (value < 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(negativeColor.copy(alpha = 0.7f), negativeColor),
                        startX = zeroX,
                        endX = thumbX
                    ),
                    topLeft = Offset(thumbX, trackTop),
                    size = Size(zeroX - thumbX, trackHeightPx),
                    cornerRadius = CornerRadius(trackHeightPx / 2f)
                )
            }

            // 3) Positive-side fill (thumb > 0)
            if (value > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(activeColor, activeColor.copy(alpha = 0.85f)),
                        startX = thumbX,
                        endX = zeroX
                    ),
                    topLeft = Offset(zeroX, trackTop),
                    size = Size(thumbX - zeroX, trackHeightPx),
                    cornerRadius = CornerRadius(trackHeightPx / 2f)
                )
            }

            // 4) Zero-pip on the track — the snap target
            if (showZeroTick) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 2.5f,
                    center = Offset(zeroX, centerY)
                )
            }

            // 5) Thumb outer glow
            if (ringAlpha > 0f) {
                drawCircle(
                    color = iOSBlueBright.copy(alpha = ringAlpha * 0.6f),
                    radius = thumbRadiusPx * dragScale * 1.6f,
                    center = Offset(thumbX, centerY)
                )
            }
            // 6) Thumb shadow
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = thumbRadiusPx * dragScale + 1.5f,
                center = Offset(thumbX, centerY + 1f)
            )
            // 7) Thumb body (white with subtle gradient)
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
