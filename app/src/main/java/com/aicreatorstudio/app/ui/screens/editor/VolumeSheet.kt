package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import com.aicreatorstudio.app.ui.components.iOSSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSRed
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * Small sheet that slides up above the toolbar with a single volume
 * slider. Bound to the currently selected clip via [onVolumeChanged].
 * If [clipVolume] is `null` the sheet shows a "No clip selected" hint
 * — the action is a no-op for that case.
 *
 * 2026-09-28 — moved here verbatim from `BottomToolbar.kt`. That file was
 * dead code (its `BottomToolbar` / `BottomToolbarItems` were referenced by
 * nothing) but this composable was live, so it was extracted before the file
 * was deleted rather than deleted along with it.
 *
 * Serves both selection kinds: [ToolPanel.VOLUME] renders it for the selected
 * audio object, and the clip toolbar's Volume chip renders it for the selected
 * video clip.
 */
@Composable
fun VolumeSheet(
    clipLabel: String,
    clipVolume: Float?,
    // 2026-09-06 — per-clip MUTE toggle (Audio Editing list).
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
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
            // 2026-09-06 — per-clip MUTE row (Audio Editing list).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isMuted) iOSRed.copy(alpha = 0.12f)
                        else Color.White.copy(alpha = 0.04f)
                    )
                    .border(
                        1.dp,
                        if (isMuted) iOSRed.copy(alpha = 0.45f) else HairlineSubtle,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onToggleMute() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = if (isMuted) iOSRed else iOSBlueBright,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (isMuted) "Muted — tap to unmute"
                    else "Mute this clip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isMuted) iOSRed else LabelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
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
