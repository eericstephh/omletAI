package com.aicreatorstudio.app.ui.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.TutorialStep
import com.aicreatorstudio.app.model.isLastTutorialIndex
import com.aicreatorstudio.app.model.toPersianDigits
import com.aicreatorstudio.app.ui.components.iOSButtonStyle
import com.aicreatorstudio.app.ui.components.iOSPillButton
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.theme.accentBright
import com.aicreatorstudio.app.ui.theme.accentDeep
import com.aicreatorstudio.app.ui.theme.accentPrimary
import com.aicreatorstudio.app.ui.theme.glowBrand
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.GlassThick
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.LabelTertiary

/**
 * The Home screen's guided tour: a dimmed scrim with a real hole punched
 * over the element the current step is about, plus a card explaining it.
 *
 * ## How the hole is drawn
 *
 * One `Path` containing the full-screen rectangle AND the target's
 * rounded rectangle, filled with [PathFillType.EvenOdd]. Even-odd means
 * the overlap is *not* filled — so the target area stays bright while
 * everything else is dimmed, with no second layer and no blend mode.
 *
 * (Compose has no `INVERSE_EVEN_ODD` equivalent to
 * `android.graphics.Path`'s, and `BlendMode.DstOut` would need an
 * offscreen layer for what is a single-path fill. Even-odd is the cheap
 * correct answer.)
 *
 * ## Why the card does not float next to the target
 *
 * Pointing a bubble at an element requires knowing the bubble's height
 * before laying it out, which needs either a `SubcomposeLayout` or a
 * measure-then-jump two-pass layout. Instead the card sits at the
 * **opposite end** of the screen from the hole: if the highlighted
 * element is in the top half, the card is at the bottom, and vice versa.
 * It can never overlap the thing it is describing, at any font scale, on
 * any screen — and the ring around the hole already says "this one".
 *
 * ## Off-screen targets
 *
 * A `LazyColumn` item can be scrolled out of view. If the recorded rect
 * does not intersect the visible area the hole is dropped (the scrim
 * goes uniform) rather than drawn at a stale position — a ring around
 * nothing is worse than no ring. `HomeScreen` scrolls the list to the
 * step's item before this is composed, so in practice the hole is there.
 *
 * ## Why the card is not a `GlassSurface`
 *
 * `GlassSurface` puts its content in a `Box(Modifier.fillMaxSize())`,
 * which inside a `Column` expands to all the remaining height — the card
 * would stretch to the full screen. This card sizes itself, so it draws
 * the same material directly (tint + hairline), which is exactly what
 * `GlassSurface` does minus the `fillMaxSize`.
 */
@Composable
fun TutorialSpotlight(
    step: TutorialStep,
    index: Int,
    total: Int,
    targetRect: Rect?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Hardware/gesture back is the most likely accidental dismissal, so
    // it means "previous step" rather than "close": on the first step
    // there is nothing to go back to, so it dismisses.
    BackHandler {
        if (index > 0) onPrevious() else onDismiss()
    }

    val density = LocalDensity.current
    val insetPx = with(density) { HOLE_INSET.toPx() }
    val cornerPx = with(density) { HOLE_CORNER.toPx() }
    val ringPx = with(density) { RING_WIDTH.toPx() }
    // Read in composition — the Canvas onDraw block is a DrawScope, not a
    // composable context, so the themed accent must be resolved out here.
    val ringColor = accentBright()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()

        val hole = targetRect
            ?.takeIf { it.width > 0f && it.height > 0f }
            ?.inflate(insetPx)
            ?.takeIf { rect ->
                // Keep the hole only while the element is on screen.
                rect.bottom > 0f && rect.top < screenHeight &&
                    rect.right > 0f && rect.left < screenWidth
            }
            ?.clampTo(0f, 0f, screenWidth, screenHeight)

        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                hole?.let {
                    addRoundRect(
                        RoundRect(
                            left = it.left,
                            top = it.top,
                            right = it.right,
                            bottom = it.bottom,
                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                        )
                    )
                }
            }
            drawPath(path, SCRIM)
            hole?.let {
                drawRoundRect(
                    color = ringColor.copy(alpha = 0.85f),
                    topLeft = it.topLeft,
                    size = it.size,
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = ringPx)
                )
            }
        }

        // The scrim swallows every touch so a stray tap cannot navigate
        // out of the tour mid-way. The card is composed AFTER this and
        // therefore still receives its own events.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                }
        )

        // Opposite-end placement — see the KDoc.
        val targetInTopHalf = hole == null || hole.center.y < screenHeight / 2f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = if (targetInTopHalf) Arrangement.Bottom else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TutorialCard(
                step = step,
                index = index,
                total = total,
                isLast = isLastTutorialIndex(index, total),
                onNext = onNext,
                onPrevious = onPrevious,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun TutorialCard(
    step: TutorialStep,
    index: Int,
    total: Int,
    isLast: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(GlassThick)
            .border(1.dp, Hairline, shape)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = t("${toPersianDigits(index + 1)} از ${toPersianDigits(total)}"),
                style = MaterialTheme.typography.labelMedium,
                color = accentBright(),
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onDismiss) {
                Text(
                    text = t("رد کردن"),
                    style = MaterialTheme.typography.labelMedium,
                    color = LabelTertiary
                )
            }
        }

        Text(
            text = t(step.title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = LabelPrimary,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = t(step.body),
            style = MaterialTheme.typography.bodyMedium,
            color = LabelSecondary,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (index > 0) {
                iOSPillButton(
                    label = t("قبلی"),
                    onClick = onPrevious,
                    modifier = Modifier.weight(1f),
                    style = iOSButtonStyle.Gray
                )
            }
            iOSPillButton(
                label = if (isLast) t("تمام") else t("بعدی"),
                onClick = onNext,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Clamp a rect into `[left, top] .. [right, bottom]`, keeping it non-empty. */
private fun Rect.clampTo(
    leftBound: Float,
    topBound: Float,
    rightBound: Float,
    bottomBound: Float
): Rect {
    val l = left.coerceIn(leftBound, rightBound)
    val t = top.coerceIn(topBound, bottomBound)
    val r = right.coerceIn(leftBound, rightBound)
    val b = bottom.coerceIn(topBound, bottomBound)
    return Rect(l, t, r.coerceAtLeast(l + 1f), b.coerceAtLeast(t + 1f))
}

private val HOLE_INSET = 6.dp
private val HOLE_CORNER = 18.dp
private val RING_WIDTH = 2.dp

/** Near-opaque app black, so the untouched UI reads as "paused", not "dimmed". */
private val SCRIM = Color(0xE60B0D12)
