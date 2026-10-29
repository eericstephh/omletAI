package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.aicreatorstudio.app.ui.theme.iOSRed
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * Stable identifier for each toolbar item. [ToolbarItem.Panel] entries
 * (with a `panelKey` like `"audio"`, `"text"`, `"effects"`,
 * `"transitions"`, `"trim"`, `"crop"`, `"animations"`, `"overlay"`)
 * are toggled by the outer [EditorScreen] via the shared
 * `selectedPanel` state. [ToolbarItem.Action] entries (with an `id`
 * like `"split"`, `"delete"`, …) execute their callback immediately.
 */
sealed interface ToolbarItem {
    val label: String
    val icon: ImageVector

    data class Action(
        override val label: String,
        override val icon: ImageVector,
        val id: String,
        val destructive: Boolean = false
    ) : ToolbarItem

    data class Panel(
        override val label: String,
        override val icon: ImageVector,
        val panelKey: String
    ) : ToolbarItem
}

/**
 * The ordered list of items in the editor bottom toolbar. Kept at the top
 * level so callers can customise (e.g. add an "Export" pill on the right)
 * without diving into the composable.
 */
val BottomToolbarItems: List<ToolbarItem> = listOf(
    // --- Direct actions (fire immediately) ---
    ToolbarItem.Action("Rotate",    Icons.Filled.Rotate90DegreesCw, "rotate"),
    ToolbarItem.Action("Delete",    Icons.Filled.Delete,            "delete",     destructive = true),
    // --- Sub-panel toggles (open/close) ---
    ToolbarItem.Panel("Trim",        Icons.Filled.ContentCut,        "trim"),
    ToolbarItem.Panel("Filter",      Icons.Filled.MovieFilter,       "filter"),
    ToolbarItem.Panel("Volume",      Icons.AutoMirrored.Filled.VolumeUp, "volume"),
    ToolbarItem.Panel("Animate",     Icons.Filled.Animation,         "animations"),
    ToolbarItem.Panel("Overlay",     Icons.Filled.Layers,            "overlay"),
    ToolbarItem.Panel("Audio",       Icons.Filled.AudioFile,         "audio"),
    ToolbarItem.Panel("Text",        Icons.Filled.TextFields,        "text"),
    ToolbarItem.Panel("Transitions", Icons.Filled.SwapHoriz,         "transitions")
)

/**
 * Floating, frosted-glass bottom toolbar (iOS-style).
 *
 * This is a legacy shim that delegates to [EditorBottomDock] for the
 * canonical implementation. The old behaviour is preserved so other
 * callers (and old screenshots) keep working. New code should use
 * [EditorBottomDock] directly.
 */
@Composable
fun BottomToolbar(
    selectedPanel: String?,
    onPanelSelected: (String?) -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    EditorBottomDock(
        selectedPanel = selectedPanel,
        onPanelSelected = onPanelSelected,
        onAction = onAction,
        modifier = modifier
    )
}

/**
 * Small sheet that slides up above the toolbar with a single volume
 * slider. Bound to the currently selected clip via [onVolumeChanged].
 * If [clipVolume] is `null` the sheet shows a "No clip selected" hint
 * — the action is a no-op for that case.
 */
@Composable
fun VolumeSheet(
    clipLabel: String,
    clipVolume: Float?,
    onVolumeChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Volume",
                style = MaterialTheme.typography.titleMedium,
                color = LabelPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (clipLabel.isNotBlank()) {
                Text(
                    text = clipLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = LabelSecondary
                )
                Spacer(Modifier.width(8.dp))
            }
            // Pill close button
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .clickable { onDismiss() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelMedium,
                    color = LabelPrimary
                )
            }
        }
        if (clipVolume == null) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Select a clip on the timeline to adjust its volume.",
                style = MaterialTheme.typography.bodySmall,
                color = LabelSecondary
            )
        } else {
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("0%", style = MaterialTheme.typography.bodySmall, color = LabelSecondary)
                Box(modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                ) {
                    iOSSlider(
                        value = clipVolume,
                        onValueChange = onVolumeChanged,
                        valueRange = 0f..2f
                    )
                }
                Text("200%", style = MaterialTheme.typography.bodySmall, color = LabelSecondary)
            }
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(iOSShapes.capsule)
                    .background(iOSBlue.copy(alpha = 0.18f))
                    .border(1.dp, iOSBlue.copy(alpha = 0.4f), iOSShapes.capsule)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${(clipVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = iOSBlueBright,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
