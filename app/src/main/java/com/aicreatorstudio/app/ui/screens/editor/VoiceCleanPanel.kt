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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
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
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.AiToolStatus
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VoiceCleanSettings
import com.aicreatorstudio.app.ui.components.ZeroSnapSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSPurple

/**
 * Voice Clean dock sub-panel.
 *
 * The engine (RnnoiseVoiceCleanEngine) decodes the clip's audio,
 * runs the RNNoise recurrent neural network (48 kHz mono frames,
 * baked-in trained weights, fully offline), and applies the
 * resulting VAD-gated gain envelope back onto the original-rate
 * stereo/mono PCM. The cleaned WAV is written to
 * `cacheDir/voiceclean/<clipId>.wav` for the export pipeline to
 * splice in. Intensity: Low / Medium (default) / High via the
 * strength slider.
 */
@Composable
fun VoiceCleanPanel(
    selectedClip: VideoClip?,
    settings: VoiceCleanSettings,
    aiState: AiToolState,
    onRun: (AiToolOptions) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit
) {
    val clip = selectedClip
    var strength by remember(settings) { mutableFloatStateOf(settings.strength) }
    Column(modifier = Modifier.fillMaxWidth()) {

        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                iOSBlue.copy(alpha = 0.6f),
                                iOSPurple.copy(alpha = 0.6f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "VOICE CLEAN",
                style = MaterialTheme.typography.labelSmall,
                color = iOSBlueBright,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                    .clickable(enabled = clip != null) { onReset() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "بازنشانی",
                        tint = LabelSecondary,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "الگوریتم: شبکه عصبی RNNoise · ۴۸ کیلوهرتز · آفلاین",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(10.dp))

        if (clip == null) {
            EmptyClipHint(text = "برای پاکسازی صدا، ابتدا یک کلیپ صوتی یا تصویری انتخاب کنید.")
            return@Column
        }
        if (clip.isAudioOnly == false && clip.uri.isBlank()) {
            EmptyClipHint(text = "این کلیپ فایل صوتی/تصویری معتبری ندارد.")
            return@Column
        }

        // ---- Strength slider ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "قدرت حذف نویز",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(strength * 100).toInt()}%",
                    color = iOSBlueBright,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            ZeroSnapSlider(
                value = strength,
                onValueChange = { strength = it },
                valueRange = 0.1f..1.0f,
                enabled = aiState.status != AiToolStatus.Processing
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- Status + progress ----
        AiToolProgress(aiState)
        Spacer(Modifier.height(12.dp))

        // ---- Run / Cancel button ----
        AiToolRunButton(
            label = "پاکسازی صدا",
            isProcessing = aiState.status == AiToolStatus.Processing,
            enabled = aiState.status != AiToolStatus.Processing,
            onRun = {
                onRun(
                    AiToolOptions(
                        strength = strength,
                        commitToClip = true
                    )
                )
            },
            onCancel = onCancel
        )

        // 2026-10-19 — the cleaned audio is swapped in during export
        // (`AiExportHooks.maybeSwapCleanAudio` reads
        // `cacheDir/voiceclean/<clipId>.wav`), so playback in the editor
        // still carries the original track. Saying so avoids the "the AI
        // did nothing" reading that a silent preview produces.
        Spacer(Modifier.height(8.dp))
        Text(
            text = "صدای پاک‌شده در ویدیوی خروجی جایگزین می‌شود؛ پیش‌نمایش همان صدای اصلی است.",
            color = LabelPrimary.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
