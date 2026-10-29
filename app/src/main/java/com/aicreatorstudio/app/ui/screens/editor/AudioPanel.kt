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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.components.iOSSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

@Composable
fun AudioPanel(
    selectedClip: VideoClip?,
    onVolumeChanged: (Float) -> Unit,
    onFadeInChanged: (Float) -> Unit,
    onFadeOutChanged: (Float) -> Unit,
    onPickMusic: () -> Unit,
    // 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو).
    onExtractAudio: () -> Unit,
    isExtractingAudio: Boolean = false,
    modifier: Modifier = Modifier
) {
    var volume by remember(selectedClip?.volume) { mutableFloatStateOf(selectedClip?.volume ?: 1f) }
    var fadeIn by remember(selectedClip?.id, selectedClip?.fadeInMs) {
        mutableFloatStateOf(selectedClip?.fadeInMs?.toFloat() ?: 0f)
    }
    var fadeOut by remember(selectedClip?.id, selectedClip?.fadeOutMs) {
        mutableFloatStateOf(selectedClip?.fadeOutMs?.toFloat() ?: 0f)
    }
    val fadeMaxMs = (selectedClip?.effectiveDurationMs ?: 5000L).toFloat().coerceAtLeast(1f)

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        iOSPanelHeader(title = "Audio", eyebrow = "Sound")

        // Per-clip volume slider
        if (selectedClip != null) {
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
                            text = "Volume",
                            color = LabelPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(volume * 100).toInt()}%",
                            color = iOSBlueBright,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    iOSSlider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            onVolumeChanged(it)
                        },
                        valueRange = 0f..2f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Per-clip fade in/out sliders
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Fade In
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Fade In",
                                color = LabelPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${fadeIn.toInt()} ms",
                                color = iOSBlueBright,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        iOSSlider(
                            value = fadeIn,
                            onValueChange = { fadeIn = it },
                            onValueChangeFinished = { onFadeInChanged(fadeIn) },
                            valueRange = 0f..fadeMaxMs,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // Fade Out
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Fade Out",
                                color = LabelPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${fadeOut.toInt()} ms",
                                color = iOSBlueBright,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        iOSSlider(
                            value = fadeOut,
                            onValueChange = { fadeOut = it },
                            onValueChangeFinished = { onFadeOutChanged(fadeOut) },
                            valueRange = 0f..fadeMaxMs,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Add music action row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(iOSBlue.copy(alpha = 0.18f), iOSBlue.copy(alpha = 0.10f))
                    )
                )
                .border(1.dp, iOSBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable(onClick = onPickMusic)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iOSBlue.copy(alpha = 0.20f))
                        .border(1.dp, iOSBlue.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        tint = iOSBlueBright,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add Music File",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Lands on the Music lane",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(iOSBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 2026-10-13 — استخراج صدا از ویدیو: pick any video, its audio
        // track is demuxed to an M4A and lands on the timeline. The
        // extraction itself is a 1-2 second disk operation, so while it
        // runs the row shows a spinner — NOT a blocking overlay: the rest
        // of the panel (and the editor) stays interactive, and the
        // ViewModel guards a second tap.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            iOSBlue.copy(alpha = 0.18f),
                            iOSBlue.copy(alpha = 0.10f)
                        )
                    )
                )
                .border(1.dp, iOSBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .clickable(enabled = !isExtractingAudio, onClick = onExtractAudio)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iOSBlue.copy(alpha = 0.20f))
                        .border(1.dp, iOSBlue.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isExtractingAudio) {
                        CircularProgressIndicator(
                            color = iOSBlueBright,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = null,
                            tint = iOSBlueBright,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "استخراج صدا از ویدیو",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isExtractingAudio) "در حال استخراج صدا…"
                        else "صدای ویدیوی انتخابی به تایم‌لاین اضافه می‌شود",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!isExtractingAudio) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(iOSBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Background Music picker
        Spacer(Modifier.height(8.dp))
        MusicPickerRow(onPickMusic = onPickMusic)
    }
}

@Composable
private fun MusicPickerRow(onPickMusic: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.04f),
                        Color.White.copy(alpha = 0.02f)
                    )
                )
            )
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
            .clickable { onPickMusic() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iOSBlue.copy(alpha = 0.20f))
                .border(1.dp, iOSBlue.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.LibraryMusic,
                contentDescription = null,
                tint = iOSBlueBright,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Add Music File",
                color = LabelPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Lands on the Music lane",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(iOSBlue),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
