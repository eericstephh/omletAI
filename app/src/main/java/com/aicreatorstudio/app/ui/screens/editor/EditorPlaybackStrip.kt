package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.components.iOSSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * Control & playback strip.
 *
 * Hierarchy (left → right):
 *   [timecode]    [frame back] [play/pause] [frame forward]    [split] [aspect] [fullscreen]
 *
 * Below the transport row: a millisecond-precise scrubber that drives the
 * playhead in real time. Scrubbing writes back to the ViewModel, which
 * pushes a `pendingSeekMs` that the canvas applies to ExoPlayer — the
 * two stay in lock-step on every drag.
 */
@Composable
fun EditorPlaybackStrip(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isFullscreen: Boolean,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onStepFrames: (Long) -> Unit,
    onSplitClick: () -> Unit,
    onAspectClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 2026-10-19 — legacy file (the live transport is
            // `EditorScreen.EditorPlaybackStripSection`). It referenced
            // `EditorLayoutSpec.PlaybackStripHeight`, removed by the
            // 2026-09-28 cleanup, which broke the build. Literal restored.
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        GlassSurface(
            shape = EditorSquircleLarge,
            material = GlassMaterial.Regular
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // Scrubber row
                ScrubberRow(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek
                )
                Spacer(Modifier.height(8.dp))
                // Transport row
                TransportRow(
                    isPlaying = isPlaying,
                    isFullscreen = isFullscreen,
                    onPlayPauseClick = onPlayPauseClick,
                    onStepFrames = onStepFrames,
                    onSplitClick = onSplitClick,
                    onAspectClick = onAspectClick,
                    onFullscreenClick = onFullscreenClick
                )
            }
        }
    }
}

@Composable
private fun ScrubberRow(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        TimecodeText(positionMs, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            iOSSlider(
                value = positionMs.toFloat().coerceAtLeast(0f),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                activeColor = iOSBlue,
                modifier = Modifier
                    .fillMaxWidth()
                    // 2026-10-19 — `SCRUB_SCRUBBER_HEIGHT` was another token
                    // removed by the 2026-09-28 cleanup. Literal restored.
                    .height(24.dp)
            )
        }
        TimecodeText(durationMs, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TimecodeText(ms: Long, fontWeight: FontWeight) {
    Text(
        text = EditorTimecode.mmSsMs(ms),
        style = MaterialTheme.typography.labelMedium,
        color = LabelPrimary.copy(alpha = if (fontWeight == FontWeight.SemiBold) 1f else 0.6f),
        fontWeight = fontWeight
    )
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    isFullscreen: Boolean,
    onPlayPauseClick: () -> Unit,
    onStepFrames: (Long) -> Unit,
    onSplitClick: () -> Unit,
    onAspectClick: () -> Unit,
    onFullscreenClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Step back
        TransportIconButton(
            icon = Icons.Filled.KeyboardDoubleArrowLeft,
            contentDescription = "Step back",
            onClick = { onStepFrames(-100L) }
        )
        Spacer(Modifier.width(8.dp))
        // Big play / pause
        BigPlayPauseButton(
            isPlaying = isPlaying,
            onClick = onPlayPauseClick
        )
        Spacer(Modifier.width(8.dp))
        // Step forward
        TransportIconButton(
            icon = Icons.Filled.ChevronRight,
            contentDescription = "Step forward",
            onClick = { onStepFrames(100L) }
        )

        Spacer(Modifier.weight(1f))

        // Right cluster: split, aspect, fullscreen
        TransportIconButton(
            icon = Icons.Filled.Splitscreen,
            contentDescription = "Split at playhead",
            onClick = onSplitClick
        )
        Spacer(Modifier.width(6.dp))
        TransportIconButton(
            icon = Icons.Filled.AspectRatio,
            contentDescription = "Aspect ratio",
            onClick = onAspectClick
        )
        Spacer(Modifier.width(6.dp))
        TransportIconButton(
            icon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
            onClick = onFullscreenClick
        )
    }
}

@Composable
private fun TransportIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LabelPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** Big circular play / pause button with a radial glow. */
@Composable
private fun BigPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = EditorLayoutSpec.SPRING_STIFFNESS
        ),
        label = "play-scale"
    )
    Box(
        modifier = Modifier
            .size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(percent = 50))
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        iOSBlueBright.copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.6f
                )
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .scale(scale)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            iOSBlueBright,
                            iOSBlue
                        )
                    )
                )
                .border(
                    1.dp,
                    iOSBlueBright.copy(alpha = 0.6f),
                    RoundedCornerShape(percent = 50)
                )
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Public helper for sub-panels that need a small "Tune" icon row.
 * Kept here to avoid duplicating the transport row's icon-button shape.
 */
@Composable
internal fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .clip(iOSShapes.capsule)
            .background(iOSBlue.copy(alpha = 0.18f))
            .border(1.dp, iOSBlue.copy(alpha = 0.4f), iOSShapes.capsule)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iOSBlueBright,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = iOSBlueBright,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

