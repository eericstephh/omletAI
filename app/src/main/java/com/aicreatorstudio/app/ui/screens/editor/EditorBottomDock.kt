package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.components.iOSToolbarItem
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSRed

/**
 * Bottom tool dock — fixed at the bottom of the editor, horizontally
 * scrollable. Hosts both quick-action items (split, delete, rotate) and
 * panel-toggling items (text, audio, effects, …).
 *
 * Order matches the brief:
 *   [Split | Speed | Volume | Text & AI Subtitles | Audio | Filters | Adjust
 *    | Effects | Crop | Background | Opacity | Delete]
 */
sealed interface DockItem {
    val label: String
    val icon: ImageVector

    data class Action(
        override val label: String,
        override val icon: ImageVector,
        val id: String,
        val destructive: Boolean = false
    ) : DockItem

    data class Panel(
        override val label: String,
        override val icon: ImageVector,
        val panelKey: String
    ) : DockItem
}

val EditorDockItems: List<DockItem> = listOf(
    DockItem.Panel("Volume", Icons.AutoMirrored.Filled.VolumeUp, "volume"),
    DockItem.Panel("Text", Icons.Filled.TextFields, "text"),
    DockItem.Panel("AI Subs", Icons.Filled.AutoAwesome, "subtitles"),
    DockItem.Panel("Audio", Icons.Filled.AudioFile, "audio"),
    DockItem.Panel("Filters", Icons.Filled.MovieFilter, "filter"),
    DockItem.Action("Delete", Icons.Filled.Delete, "delete", destructive = true)
)

@Composable
fun EditorBottomDock(
    selectedPanel: String?,
    onPanelSelected: (String?) -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 2026-10-19 — this file is legacy (nothing references it; the
            // live dock is `EditorScreen.EditorBottomDockSection`). It broke
            // the build by referencing `EditorLayoutSpec.BottomDockHeight`,
            // a token the 2026-09-28 IA cleanup removed. Literal restored so
            // the module compiles; delete the file when it is safe to.
            .height(80.dp)
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp, top = 4.dp)
    ) {
        GlassSurface(
            shape = RoundedCornerShape(EditorLayoutSpec.SquircleExtraLarge),
            material = GlassMaterial.Regular
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 6.dp
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(EditorDockItems, key = { it.identity() }) { item ->
                    when (item) {
                        is DockItem.Action -> DockAction(
                            icon = item.icon,
                            label = item.label,
                            isDestructive = item.destructive,
                            onClick = { onAction(item.id) }
                        )
                        is DockItem.Panel -> {
                            val isSelected = selectedPanel == item.panelKey
                            DockAction(
                                icon = item.icon,
                                label = item.label,
                                isSelected = isSelected,
                                onClick = {
                                    onPanelSelected(if (isSelected) null else item.panelKey)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DockItem.identity(): String = when (this) {
    is DockItem.Action -> "action:$id"
    is DockItem.Panel -> "panel:$panelKey"
}

/** Single dock item — squircle icon with a small caption.
 *
 * Now a thin shim over the shared [iOSToolbarItem] primitive so the
 * active editor dock and this legacy dock share the same premium dark
 * glass + animated neon glow design. */
@Composable
private fun DockAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean = false,
    isDestructive: Boolean = false
) {
    iOSToolbarItem(
        icon = icon,
        label = label,
        onClick = onClick,
        selected = isSelected,
        accentColor = if (isDestructive) iOSRed else iOSBlue
    )
}
