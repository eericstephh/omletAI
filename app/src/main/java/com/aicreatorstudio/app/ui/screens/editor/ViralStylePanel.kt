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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.ViralStylePresets

/**
 * PHASE B5.5 — viral text style picker.
 *
 * Renders a 3-column grid of the 15 viral presets from
 * [ViralStylePresets.All]. Tapping a chip selects it locally
 * and immediately applies it to the currently-selected
 * `TextOverlay` via [onApplyToSelected]. An "Apply to all text"
 * button broadcasts the same style to every overlay on the
 * timeline.
 *
 * Distinct from [SubtitleStylePanel] in three ways:
 *  1. **No customize section.** Viral presets are
 *     pick-and-apply only; if the user wants to tweak a
 *     preset, they go through the existing
 *     `SubtitleStylePanel` Customize section or the bare
 *     `TextOverlay` color / size editor.
 *  2. **Three-column grid vs horizontal scroll row.** 15
 *     presets don't fit in a row, so the grid shows all
 *     presets at once.
 *  3. **Scope is "all text", not "all subtitles".** The
 *     "Apply to all" button is wired to
 *     `applyViralStyleToAll`, which targets every overlay
 *     regardless of `subtitleSource`.
 *
 * Selection state is local (`selectedName`) and not pushed
 * into the ViewModel — viral style application is an
 * idempotent "set the field" operation, so two consecutive
 * taps of the same chip are harmless. The view-model still
 * holds the canonical "last applied style" via the
 * `TextOverlay.subtitleStyle` field on the matching overlay.
 */
@Composable
fun ViralStylePanel(
    initialStyle: SubtitleStyle?,
    onApplyToSelected: (SubtitleStyle) -> Unit,
    onApplyToAll: (SubtitleStyle) -> Unit,
    showApplyToAll: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Local selection — seeded from the overlay's current
    // style by name match, otherwise the first preset
    // ("Bold Shorts") is the initial highlight.
    val initialName = remember(initialStyle) {
        ViralStylePresets.All.firstOrNull { it.second == initialStyle }?.first
            ?: ViralStylePresets.All.first().first
    }
    var selectedName by remember { mutableStateOf(initialName) }
    val selected = ViralStylePresets.All.firstOrNull { it.first == selectedName }
        ?: ViralStylePresets.All.first()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        // ----- Title -----
        Text(
            text = "Viral styles",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Pick-and-apply captions. Tap a chip to apply to the selected text; use the button to apply to all.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
        )

        Spacer(Modifier.height(8.dp))

        // ----- 3-column grid of all 15 viral presets -----
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // Cap the height so the panel doesn't take the
            // whole screen when 15 entries are listed. Five
            // rows of ~56dp chip height = 280dp + spacing.
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
        ) {
            items(ViralStylePresets.All, key = { it.first }) { (name, style) ->
                ViralPresetChip(
                    name = name,
                    style = style,
                    isSelected = name == selectedName,
                    onClick = {
                        selectedName = name
                        onApplyToSelected(style)
                    }
                )
            }
        }

        if (showApplyToAll) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onApplyToAll(selected.second) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply to all text")
            }
        }
    }
}

/**
 * One chip in the 3-column grid. Renders the preset name
 * styled as a small caption preview: same colors, stroke,
 * shadow, and background as the real preset, so the user can
 * see what they're picking.
 */
@Composable
private fun ViralPresetChip(
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
            .clip(RoundedCornerShape(10.dp))
            .background(chipBackground)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp)
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = Color(style.textColorArgb),
            fontSize = 11.sp,
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
