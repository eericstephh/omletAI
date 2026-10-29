package com.aicreatorstudio.app.ui.screens.editor

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * Effects control panel — redesigned with iOS-style aesthetics.
 *
 * Provides controls for:
 * - Color filters (Brightness, Contrast, Saturation)
 * - Playback speed
 * - Rotation
 * - Trim controls
 */
@Composable
fun EffectsPanel(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    playbackSpeed: Float,
    rotation: Int,
    selectedEffect: VideoEffect = VideoEffect.NONE,
    onEffectSelected: (VideoEffect) -> Unit = {},
    onBrightnessChanged: (Float) -> Unit = {},
    onContrastChanged: (Float) -> Unit = {},
    onSaturationChanged: (Float) -> Unit = {},
    onPlaybackSpeedChanged: (Float) -> Unit = {},
    onRotationChanged: (Int) -> Unit = {},
    onRotate90Degrees: () -> Unit = {},
    // PHASE A (Persistence Integrity) — replaces the old `isTrimMode` /
    // `onToggleTrimMode` pair. The trim sliders live in `TrimPanel`;
    // the pill used to flip a boolean that nothing read, so it looked
    // like a working control while doing nothing.
    onOpenTrimPanel: () -> Unit = {},
    onResetEffects: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Effects",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            // Reset pill
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .clickable { onResetEffects() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = LabelSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Famous one-tap effects
        Text(
            text = "FILTERS",
            color = iOSBlueBright,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
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

        // Color Adjustments
        Text(
            text = "COLOR",
            color = iOSBlueBright,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        EffectSlider(
            label = "Brightness",
            value = brightness,
            valueRange = 0f..2f,
            onValueChange = onBrightnessChanged,
            valueText = "${(brightness * 100).toInt()}%"
        )
        EffectSlider(
            label = "Contrast",
            value = contrast,
            valueRange = 0f..2f,
            onValueChange = onContrastChanged,
            valueText = "${(contrast * 100).toInt()}%"
        )
        EffectSlider(
            label = "Saturation",
            value = saturation,
            valueRange = 0f..2f,
            onValueChange = onSaturationChanged,
            valueText = "${(saturation * 100).toInt()}%"
        )

        // Speed
        Text(
            text = "SPEED",
            color = iOSBlueBright,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        EffectSlider(
            label = "Playback Speed",
            value = playbackSpeed,
            valueRange = PlaybackSpeedRange.MIN..PlaybackSpeedRange.MAX,
            onValueChange = onPlaybackSpeedChanged,
            valueText = "${playbackSpeed}×"
        )

        // Rotation
        Text(
            text = "ROTATION",
            color = iOSBlueBright,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iOSBlue.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.RotateRight,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Current",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${rotation}°",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Rotate 90° pill button
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(iOSBlue)
                    .clickable { onRotate90Degrees() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Rotate 90°",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Trim sliders live in TrimPanel. The old `isTrimMode` pill
        // flipped a boolean nothing read, so it looked like a control
        // while doing nothing; it now opens the real Trim panel.
        Text(
            text = "TRIM",
            color = iOSBlueBright,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        PillActionButton(
            label = "Trim clip",
            onClick = onOpenTrimPanel,
            style = PillStyle.Filled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun EffectSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = LabelPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = iOSBlueBright,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        iOSSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            activeColor = iOSBlue,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
