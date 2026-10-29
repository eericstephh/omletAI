package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.PlaybackSpeedRange
import com.aicreatorstudio.app.ui.components.iOSSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * Minimal `HH:MM:SS.ms` formatter. Duplicated locally so this file
 * doesn't depend on the private `formatTime` in EditorScreen.kt.
 */
private fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = (ms % 1000).toInt() / 10
    return "%02d:%02d.%02d".format(minutes, seconds, millis)
}

/**
 * iOS-style section header used across all sub-panels.
 * Compact, left-aligned title with a small uppercase eyebrow.
 */
@Composable
internal fun iOSPanelHeader(title: String, eyebrow: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = iOSBlueBright,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = LabelPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * iOS-style glass row used inside panels (e.g. overlay picker items).
 * Tinted with a soft blue when selected, scales down on press.
 */
@Composable
internal fun iOSListRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: ImageVector? = null,
    selected: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "row-scale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) iOSBlue.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(
                1.dp,
                if (selected) iOSBlue.copy(alpha = 0.35f) else HairlineSubtle,
                RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Icon container
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(iOSBlue.copy(alpha = 0.20f))
                .border(1.dp, iOSBlue.copy(alpha = 0.35f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iOSBlueBright,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = LabelPrimary,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabelSecondary
                )
            }
        }
        if (trailing != null) {
            Icon(
                imageVector = trailing,
                contentDescription = null,
                tint = if (selected) iOSGreen else iOSBlueBright,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Trim controls: dual-slider start/end range with a toggle to enter/exit
 * the trim mode.
 */
@Composable
fun TrimPanel(
    trimStartMs: Long,
    trimEndMs: Long,
    durationMs: Long,
    isTrimMode: Boolean,
    onTrimStartChanged: (Long) -> Unit,
    onTrimEndChanged: (Long) -> Unit,
    onToggleTrimMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        iOSPanelHeader(title = "Trim", eyebrow = "Range")
        Spacer(Modifier.height(14.dp))

        if (isTrimMode) {
            // Time readout — two iOS-style pill badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TimePill(label = "Start", value = formatMmSs(trimStartMs), modifier = Modifier.weight(1f))
                TimePill(label = "End",   value = formatMmSs(trimEndMs),   modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Start",
                style = MaterialTheme.typography.labelMedium,
                color = LabelSecondary
            )
            iOSSlider(
                value = trimStartMs.toFloat(),
                onValueChange = { onTrimStartChanged(it.toLong()) },
                valueRange = 0f..trimEndMs.toFloat().coerceAtLeast(1f),
                activeColor = iOSBlue,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "End",
                style = MaterialTheme.typography.labelMedium,
                color = LabelSecondary
            )
            iOSSlider(
                value = trimEndMs.toFloat(),
                onValueChange = { onTrimEndChanged(it.toLong()) },
                valueRange = trimStartMs.toFloat().coerceAtLeast(0f)..durationMs.toFloat().coerceAtLeast(1f),
                activeColor = iOSBlue,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
        }

        // iOS-style pill toggle
        PillActionButton(
            label = if (isTrimMode) "Done Trimming" else "Enable Trim Mode",
            onClick = onToggleTrimMode,
            style = if (isTrimMode) PillStyle.Filled else PillStyle.Gray,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TimePill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LabelSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = LabelPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * iOS-style pill button (used in panel footers).
 *
 * @param enabled when false the pill is dimmed and taps are swallowed. The
 *   click handler is short-circuited rather than the clickable modifier being
 *   dropped, so a disabled pill keeps its exact layout size and the footer
 *   does not reflow when a button becomes available.
 */
@Composable
internal fun PillActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Filled,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pill-scale"
    )

    val brush = when (style) {
        PillStyle.Filled -> Brush.horizontalGradient(listOf(iOSBlue, iOSBlueBright))
        PillStyle.Gray -> Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF2C2C2E)))
    }
    val textColor = Color.White

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .scale(scale)
            .clip(iOSShapes.capsule)
            .background(brush)
            .border(1.dp, HairlineSubtle, iOSShapes.capsule)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!enabled) return@clickable
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

enum class PillStyle { Filled, Gray }

/**
 * Quick-apply playback speed presets. Tapping a chip applies the
 * preset to the currently-selected clip via [onSpeedSelected]. The
 * currently-active preset is highlighted with a glowing blue pill.
 */
@Composable
fun SpeedPanel(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // 0.25×..2× is the core range; 4× is kept as an extra quick preset.
    // Sourced from [PlaybackSpeedRange] so the presets, the ViewModel
    // clamp and the Effects-panel slider can never disagree.
    val presets = PlaybackSpeedRange.PRESETS
    Column(modifier = modifier.fillMaxWidth()) {
        iOSPanelHeader(title = "Playback Speed", eyebrow = "Preset")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Current: ${currentSpeed}×",
            style = MaterialTheme.typography.bodySmall,
            color = LabelSecondary
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { speed ->
                val isSelected = speed == currentSpeed
                SpeedChip(
                    label = "${speed}×",
                    isSelected = isSelected,
                    onClick = { onSpeedSelected(speed) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SpeedChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "chip-scale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) iOSBlue.copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(
                1.dp,
                if (isSelected) iOSBlue.copy(alpha = 0.55f) else HairlineSubtle,
                RoundedCornerShape(12.dp)
            )
            .drawBehind {
                if (isSelected) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                iOSBlue.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) iOSBlueBright else LabelPrimary,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * Famous-effects grid (one-tap filters). Lifted from [EffectsPanel]
 * so the toolbar's "Filter" button can open this grid on its own.
 */
@Composable
fun FilterPanel(
    selectedEffect: VideoEffect,
    onEffectSelected: (VideoEffect) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iOSBlue.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MovieFilter,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            iOSPanelHeader(title = "Filters", eyebrow = "Style")
        }
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(240.dp)
        ) {
            items(VideoEffect.entries) { effect ->
                EffectThumbnail(
                    effect = effect,
                    isSelected = selectedEffect == effect,
                    onClick = { onEffectSelected(effect) }
                )
            }
        }
    }
}

/** What kind of media the user is picking to overlay on the timeline. */
enum class OverlayKind(val label: String, val icon: ImageVector) {
    IMAGE_VIDEO("Image or video", Icons.Filled.Image)
}

/**
 * One-tap overlay picker.
 */
@Composable
fun OverlayPicker(
    onPick: (OverlayKind) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        iOSPanelHeader(title = "Add Overlay", eyebrow = "Media")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Place an image or short video on top of the timeline. " +
                    "It will appear at the current playhead.",
            style = MaterialTheme.typography.bodySmall,
            color = LabelSecondary
        )
        Spacer(Modifier.height(14.dp))
        OverlayKind.entries.forEach { kind ->
            iOSListRow(
                icon = kind.icon,
                title = kind.label,
                subtitle = "Tap to pick from your library",
                onClick = { onPick(kind) },
                trailing = Icons.Filled.Check
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
