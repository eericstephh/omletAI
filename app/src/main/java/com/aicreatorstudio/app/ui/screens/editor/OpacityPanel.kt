package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.components.iOSSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlueBright

/**
 * Per-clip opacity panel — backs the Opacity dock chip.
 *
 * Slider 0..1 with a percentage readout. The callback only fires on
 * `onValueChangeFinished` (drag release) to avoid blowing the undo
 * history with one entry per drag tick. Continuous preview updates
 * use the local `opacity` state.
 */
@Composable
fun OpacityPanel(
    selectedClip: VideoClip?,
    onOpacityChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var opacity by remember(selectedClip?.id, selectedClip?.opacity) {
        mutableFloatStateOf(selectedClip?.opacity ?: 1f)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        iOSPanelHeader(title = "Opacity", eyebrow = "Visibility")

        if (selectedClip == null) {
            Text(
                text = "Select a clip to adjust its opacity.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Clip Opacity",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(opacity * 100).toInt()}%",
                        color = iOSBlueBright,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                iOSSlider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { onOpacityChanged(opacity) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
