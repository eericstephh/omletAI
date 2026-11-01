package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.accentBright
import com.aicreatorstudio.app.ui.theme.accentDeep
import com.aicreatorstudio.app.ui.theme.accentPrimary
import com.aicreatorstudio.app.ui.theme.cardEdgeBrush
import com.aicreatorstudio.app.ui.theme.ctaBrush

/**
 * 2026-11-01 — modern transport bar (replaces the M3-Slider strip).
 *
 * Layout (single 56dp band — unchanged height contract with the
 * timeline section below):
 *
 *   [▶ gradient circle]  00:12.4  ──●────────────────  01:30.0
 *
 * Design language — "Dark Studio 2":
 *  - Play/pause: 40dp accent-gradient circle ([ctaBrush]) with a
 *    top-lit hairline ([cardEdgeBrush]), an accent-tinted drop shadow
 *    and a spring press-scale. This mirrors the home CTAs.
 *  - Scrubber: hand-drawn Canvas track — a slim 3dp capsule, played
 *    span filled with the accent gradient, and a glowing thumb that
 *    scales up + brightens while scrubbing. Material3's default fat
 *    slider trough is gone.
 *  - Timecodes: position in [accentBright], duration in
 *    [LabelSecondary] — quiet chrome around the live element.
 *
 * Scrub contract is byte-compatible with the old slider: [onScrubStart]
 * fires on the FIRST pointer contact, [onScrubSeek] on every movement
 * (and on a tap's landing position), [onScrubEnd] when every pointer
 * is released. The screen keeps the pause-during-drag / CLOSEST_SYNC /
 * EXACT-on-release pipeline, so nothing upstream changes.
 *
 * ⚠ Stale-closure guards: gesture handlers run for the lifetime of the
 * composition, so [durationMs] and the three callbacks are read through
 * [rememberUpdatedState] — a duration change mid-session (project edit)
 * must not bake the old value into the drag mapping.
 */
@Composable
fun EditorTransportBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubSeek: (Long) -> Unit,
    onScrubEnd: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(PlaybackStripHeight)
            .padding(horizontal = 14.dp)
    ) {
        TransportPlayButton(isPlaying = isPlaying, onClick = onPlayPause)
        Spacer(Modifier.width(12.dp))
        Text(
            text = EditorTimecode.mmSsMs(positionMs),
            color = accentBright(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(10.dp))
        TransportScrubber(
            positionMs = positionMs,
            durationMs = durationMs,
            onScrubStart = onScrubStart,
            onScrubSeek = onScrubSeek,
            onScrubEnd = onScrubEnd,
            modifier = Modifier
                .weight(1f)
                .height(30.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = EditorTimecode.mmSsMs(durationMs),
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Accent-gradient play/pause circle with press-scale and a tinted shadow. */
@Composable
private fun TransportPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "transport-press"
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .scale(scale)
            .shadow(
                elevation = 10.dp,
                shape = CircleShape,
                ambientColor = Color.Black,
                spotColor = accentDeep()
            )
            .clip(CircleShape)
            .background(ctaBrush())
            .border(1.dp, cardEdgeBrush(), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "توقف" else "پخش",
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Canvas scrubber: slim capsule track, accent-gradient played span and a
 * glowing thumb that grows while the finger is down. Tap = seek; drag =
 * the shared scrub pipeline (start / seek per move / end on release).
 */
@Composable
private fun TransportScrubber(
    positionMs: Long,
    durationMs: Long,
    onScrubStart: () -> Unit,
    onScrubSeek: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scrubbing by remember { mutableStateOf(false) }
    // Gesture closures outlive recompositions — read live values.
    val durationNow by rememberUpdatedState(durationMs)
    val startNow by rememberUpdatedState(onScrubStart)
    val seekNow by rememberUpdatedState(onScrubSeek)
    val endNow by rememberUpdatedState(onScrubEnd)

    val thumbScale by animateFloatAsState(
        targetValue = if (scrubbing) 1.5f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "scrub-thumb"
    )
    val fillStart = accentPrimary()
    val fillEnd = accentBright()
    val thumb = accentBright()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scrubbing = true
                    startNow()
                    seekNow(msAt(down.position.x, size.width, durationNow))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            seekNow(msAt(change.position.x, size.width, durationNow))
                            change.consume()
                        }
                    }
                    scrubbing = false
                    endNow()
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val trackHeight = 3.dp.toPx()
        val trackRadius = trackHeight / 2f
        val inset = 8.dp.toPx() // keep the thumb's glow inside the bounds
        val usable = (w - inset * 2).coerceAtLeast(1f)
        val fraction = if (durationNow <= 0) 0f
        else (positionMs.toFloat() / durationNow).coerceIn(0f, 1f)
        val playX = inset + usable * fraction

        // Track (unplayed span)
        drawRoundRect(
            color = BorderSubtle,
            topLeft = Offset(inset, cy - trackRadius),
            size = androidx.compose.ui.geometry.Size(usable, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadius)
        )
        // Played span — accent gradient, left → right
        if (fraction > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(fillStart, fillEnd),
                    startX = inset,
                    endX = playX
                ),
                topLeft = Offset(inset, cy - trackRadius),
                size = androidx.compose.ui.geometry.Size(playX - inset, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadius)
            )
        }
        // Thumb glow — brightens only while scrubbing
        drawCircle(
            color = thumb.copy(alpha = if (scrubbing) 0.35f else 0.15f),
            radius = 11.dp.toPx() * if (scrubbing) thumbScale else 1f,
            center = Offset(playX, cy)
        )
        // Thumb core — white hairline keeps it crisp on any accent
        drawCircle(color = thumb, radius = 5.dp.toPx() * thumbScale, center = Offset(playX, cy))
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            radius = 5.dp.toPx() * thumbScale,
            center = Offset(playX, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
    }
}

private fun msAt(x: Float, width: Int, durationMs: Long): Long {
    if (width <= 0) return 0L
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * durationMs).toLong().coerceAtLeast(0L)
}
