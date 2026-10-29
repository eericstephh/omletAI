package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.OnVideoScrimStrong
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSPurple

/**
 * Interactive Before/After divider that floats over the video preview.
 *
 * Behaviour:
 *  - The whole overlay sits on top of the preview canvas (callers
 *    position it with `Modifier.matchParentSize()`).
 *  - A vertical bar marks the current split position; to the LEFT of
 *    the bar the preview is the "before" (no upscaler effect applied);
 *    to the RIGHT it is the "after" (CAS applied). The actual masking
 *    of the preview is the caller's responsibility — this composable
 *    just exposes [position] via [onPositionChange] so the preview
 *    can mask the two halves.
 *  - The user drags the central thumb (a circular iOS-style grab pill)
 *    to move the divider.
 *  - Tapping the overlay anywhere jumps the divider to that x. This is
 *    the iOS Photos / Instagram compare gesture.
 *
 * Rendering of the labels ("BEFORE" / "AFTER") is delegated to
 * [BeforeAfterLabels], which is drawn under the divider.
 *
 * @param position initial split position (0..1, where 0 = full after,
 *   1 = full before, 0.5 = centre)
 * @param onPositionChange fires continuously while the user drags
 * @param modifier modifier on the outer overlay (typically
 *   `Modifier.matchParentSize()` from the host preview Box)
 * @param enabled when false the divider is hidden and the overlay is
 *   transparent to pointer events
 */
@Composable
fun BeforeAfterSplitSlider(
    position: Float,
    onPositionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (!enabled) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        var sizePx by remember { mutableStateOf(IntSize.Zero) }
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val totalHeightPx = with(density) { maxHeight.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sizePx = it }
                // Tap-to-jump: tap anywhere to set the split to that x.
                .pointerInput(totalWidthPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (sizePx.width > 0) {
                                onPositionChange(
                                    (offset.x / sizePx.width).coerceIn(0f, 1f)
                                )
                            }
                        }
                    )
                }
                // Drag the central pill to move the divider.
                .pointerInput(sizePx) {
                    detectDragGestures { change, _ ->
                        if (sizePx.width > 0) {
                            onPositionChange(
                                (change.position.x / sizePx.width).coerceIn(0f, 1f)
                            )
                        }
                        change.consume()
                    }
                }
        ) {
            // Compute the divider's x in pixels.
            val xPx = (position.coerceIn(0f, 1f) * totalWidthPx).coerceAtLeast(0f)

            // BEFORE / AFTER chips, drawn just inside each side of the divider.
            BeforeAfterLabels(
                xPx = xPx,
                totalWidthPx = totalWidthPx,
                totalHeightPx = totalHeightPx
            )

            // The vertical bar + circular thumb.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val barColor = Color.White
                val barWidthPx = 2.5f * density.density

                // Subtle dark glow above and below the bar so it
                // reads against bright video frames.
                // 2026-10-16 (Phase 2) — hoisted to the shared on-video
                // scrim token: this is the same problem the preview's
                // play glyph and status pills have (the backdrop is
                // arbitrary footage).
                val glowColor = OnVideoScrimStrong
                drawLine(
                    color = glowColor,
                    start = Offset(xPx, 0f),
                    end = Offset(xPx, size.height),
                    strokeWidth = barWidthPx + 4f
                )
                drawLine(
                    color = barColor,
                    start = Offset(xPx, 0f),
                    end = Offset(xPx, size.height),
                    strokeWidth = barWidthPx
                )
            }

            // Central grab pill — sits at (xPx, midY) and renders a
            // vertical iOS-style compare icon.
            val pillSize = 32.dp
            val pillOffsetX = with(density) { xPx.toDp() } - pillSize / 2
            val pillOffsetY = (with(density) { totalHeightPx.toDp() } / 2) - pillSize / 2
            Box(
                modifier = Modifier
                    .offset(x = pillOffsetX, y = pillOffsetY)
                    .size(pillSize)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.95f),
                                Color.White.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        iOSBlueBright.copy(alpha = 0.7f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Compare,
                    contentDescription = "Drag to compare before / after",
                    tint = iOSBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Reset to the centre on first composition so the user has a
        // visible starting point if the persisted value is off-screen.
        LaunchedEffect(Unit) {
            if (position <= 0f || position >= 1f) onPositionChange(0.5f)
        }
    }
}

/**
 * Tiny "BEFORE" / "AFTER" chip pair drawn inside the overlay. Each
 * chip sits on its respective side of the divider, near the top of
 * the canvas.
 */
@Composable
private fun BeforeAfterLabels(
    xPx: Float,
    totalWidthPx: Float,
    totalHeightPx: Float
) {
    val density = LocalDensity.current
    val chipOffsetY = with(density) { 12.dp.toPx() }
    Box(modifier = Modifier.fillMaxSize()) {
        // "BEFORE" chip — left side
        val beforeVisible = xPx > 56f * density.density
        if (beforeVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = with(density) { (xPx - 12f).coerceAtLeast(8f).toDp() },
                        y = with(density) { chipOffsetY.toDp() }
                    )
                    .clip(RoundedCornerShape(percent = 50))
                    .background(OnVideoScrimStrong)
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "BEFORE",
                    color = LabelPrimary,
                    style = MaterialTheme3.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // "AFTER" chip — right side
        val afterVisible = (totalWidthPx - xPx) > 56f * density.density
        if (afterVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 0.dp)
                    .offset(
                        x = with(density) { (xPx + 8f).coerceAtMost(totalWidthPx - 64f).toDp() },
                        y = with(density) { chipOffsetY.toDp() }
                    )
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                iOSBlue.copy(alpha = 0.6f),
                                iOSPurple.copy(alpha = 0.6f)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "AFTER",
                    color = Color.White,
                    style = MaterialTheme3.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// Tiny helper so the file doesn't have to import
// `androidx.compose.material3.MaterialTheme.typography` directly twice.
private object MaterialTheme3 {
    val labelSmall: androidx.compose.ui.text.TextStyle
        @Composable
        get() = androidx.compose.material3.MaterialTheme.typography.labelSmall
}
