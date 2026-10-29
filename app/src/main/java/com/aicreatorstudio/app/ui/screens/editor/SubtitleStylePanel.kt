package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleStyle

/**
 * PHASE B5 — production subtitle styling UI.
 *
 * Renders an inline panel:
 *  1. Preset row: 6 chips (Classic / Bold / Clean / Box / Shorts / Minimal).
 *  2. Customize section (collapsible).
 *  3. "Apply to all subtitles" button.
 *
 * The panel is read-only for overlays; all mutations are
 * delegated up through the `onApply*` callbacks. Local state
 * holds the working `SubtitleStyle` so the user can tweak the
 * Customize sliders before applying.
 */
@Composable
fun SubtitleStylePanel(
    initialStyle: SubtitleStyle?,
    onApplyToSelected: (SubtitleStyle) -> Unit,
    onApplyToAll: (SubtitleStyle) -> Unit,
    showApplyToAll: Boolean,
    modifier: Modifier = Modifier
) {
    // 2026-09-07 CRASH FIX — `rememberSaveable` cannot persist a
    // plain data class (SubtitleStyle is not @Parcelize/Serializable);
    // Compose threw IllegalArgumentException when the panel composed
    // with instance state or on configuration changes. A plain
    // `remember` is sufficient here: the working style is transient
    // tweak state and is always re-seeded from the overlay.
    var working by remember(initialStyle) {
        // If overlay has no style, seed with Default; otherwise
        // mirror the overlay's current style.
        mutableStateOf(initialStyle ?: SubtitleStyle.Default)
    }
    var customizeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // ----- Preset row -----
        Text(
            text = "Preset",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            SubtitleStyle.AllPresets.forEach { (name, preset) ->
                PresetChip(
                    name = name,
                    style = preset,
                    isSelected = working == preset,
                    onClick = {
                        working = preset
                        onApplyToSelected(preset)
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ----- Customize toggle -----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { customizeExpanded = !customizeExpanded }
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = "Customize",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.size(4.dp))
            androidx.compose.material3.Icon(
                imageVector = if (customizeExpanded) Icons.Filled.ExpandLess
                else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        if (customizeExpanded) {
            Spacer(Modifier.height(6.dp))
            CustomizeBody(
                working = working,
                onWorkingChange = { working = it },
                onApplyToSelected = onApplyToSelected
            )
        }

        if (showApplyToAll) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onApplyToAll(working) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply to all subtitles")
            }
        }
    }
}

/**
 * One chip in the preset row. The chip is a small preview of
 * how the preset will look (white text on a dark background,
 * with stroke / background applied).
 */
@Composable
private fun PresetChip(
    name: String,
    style: SubtitleStyle,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline
    val chipBackground = if (style.backgroundEnabled) {
        Color(style.backgroundColorArgb).copy(alpha = style.backgroundOpacity)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBackground)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = name,
            color = Color(style.textColorArgb),
            fontSize = 12.sp,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun CustomizeBody(
    working: SubtitleStyle,
    onWorkingChange: (SubtitleStyle) -> Unit,
    onApplyToSelected: (SubtitleStyle) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ----- Font size -----
        LabeledSlider(
            label = "Size",
            value = working.fontSizeSp,
            onValueChange = { onWorkingChange(working.copy(fontSizeSp = it)) },
            valueRange = 12f..48f,
            onChangeEnd = { onApplyToSelected(working) }
        )
        // ----- Stroke width -----
        if (working.strokeEnabled) {
            LabeledSlider(
                label = "Stroke width",
                value = working.strokeWidthSp,
                onValueChange = { onWorkingChange(working.copy(strokeWidthSp = it)) },
                valueRange = 0f..6f,
                onChangeEnd = { onApplyToSelected(working) }
            )
        }
        // ----- Background opacity -----
        if (working.backgroundEnabled) {
            LabeledSlider(
                label = "Background opacity",
                value = working.backgroundOpacity,
                onValueChange = { onWorkingChange(working.copy(backgroundOpacity = it)) },
                valueRange = 0f..1f,
                onChangeEnd = { onApplyToSelected(working) }
            )
        }
        // ----- Shadow opacity -----
        if (working.shadowEnabled) {
            LabeledSlider(
                label = "Shadow opacity",
                value = working.shadowOpacity,
                onValueChange = { onWorkingChange(working.copy(shadowOpacity = it)) },
                valueRange = 0f..1f,
                onChangeEnd = { onApplyToSelected(working) }
            )
        }
        // ----- Position X / Y -----
        LabeledSlider(
            label = "Position X",
            value = working.positionX,
            onValueChange = { onWorkingChange(working.copy(positionX = it)) },
            valueRange = 0f..1f,
            onChangeEnd = { onApplyToSelected(working) }
        )
        LabeledSlider(
            label = "Position Y",
            value = working.positionY,
            onValueChange = { onWorkingChange(working.copy(positionY = it)) },
            valueRange = 0f..1f,
            onChangeEnd = { onApplyToSelected(working) }
        )

        // ----- Bold / Italic / Stroke / Shadow / Background toggles -----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Bold", working.bold) {
                onWorkingChange(working.copy(bold = it))
                onApplyToSelected(working.copy(bold = it))
            }
            ToggleChip("Italic", working.italic) {
                onWorkingChange(working.copy(italic = it))
                onApplyToSelected(working.copy(italic = it))
            }
            ToggleChip("Stroke", working.strokeEnabled) {
                onWorkingChange(working.copy(strokeEnabled = it))
                onApplyToSelected(working.copy(strokeEnabled = it))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Shadow", working.shadowEnabled) {
                onWorkingChange(working.copy(shadowEnabled = it))
                onApplyToSelected(working.copy(shadowEnabled = it))
            }
            ToggleChip("Background", working.backgroundEnabled) {
                onWorkingChange(working.copy(backgroundEnabled = it))
                onApplyToSelected(working.copy(backgroundEnabled = it))
            }
        }

        // ----- Vertical alignment -----
        Text(
            text = "Vertical position",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubtitleAlignmentV.entries.forEach { v ->
                AlignmentChip(
                    label = v.name,
                    isSelected = working.verticalAlignment == v,
                    onClick = {
                        onWorkingChange(working.copy(verticalAlignment = v))
                        onApplyToSelected(working.copy(verticalAlignment = v))
                    }
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onChangeEnd: (Float) -> Unit
) {
    Column {
        Text(
            text = "$label: ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = { onChangeEnd(value) }
        )
    }
}

@Composable
private fun ToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (checked) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.size(4.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun AlignmentChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
