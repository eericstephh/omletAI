package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ai.voice.VoiceEnhancementPreset
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VoiceEnhanceState
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * PHASE 4 — Local Voice Enhancement panel.
 *
 * Phased flow (mirrors the silence-cut UX): preset → Enhance
 * (decode → normalize → denoise → cache) → stats + waveform Preview
 * (timeline untouched) → Apply (one history entry) / Discard.
 * Fully offline; backed by the existing RNNoise engine + export swap.
 */
@Composable
fun VoiceEnhancePanel(
    state: VoiceEnhanceState,
    selectedClip: VideoClip?,
    committedEnabled: Boolean,
    onEnhance: (VoiceEnhancementPreset) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onClearCommitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var preset by remember { mutableStateOf(VoiceEnhancementPreset.Default) }

    val isProcessing = state is VoiceEnhanceState.Processing
    val isPreview = state is VoiceEnhanceState.Preview
    val progress by animateFloatAsState(
        targetValue = if (isProcessing) (state as VoiceEnhanceState.Processing).progress else 0f,
        label = "voice-enhance-progress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                iOSBlue.copy(alpha = 0.30f),
                                iOSBlue.copy(alpha = 0.15f)
                            )
                        )
                    )
                    .border(1.dp, iOSBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "بهبود صدا",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "نرمال‌سازی + حذف نویز عصبی · آفلاین",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (committedEnabled && !isProcessing && !isPreview) {
                OutlinedButton(
                    onClick = onClearCommitted,
                    shape = iOSShapes.capsule
                ) {
                    Text("حذف بهبود")
                }
            }
        }

        if (selectedClip == null || selectedClip.isAudioOnly) {
            Text(
                text = "برای بهبود صدا، یک کلیپ ویدیویی را انتخاب کنید.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        // Preset selector (settings phase only).
        if (!isProcessing && !isPreview) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "پیش‌تنظیم: ${selectedClip.fileName}",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceEnhancementPreset.entries.forEach { p ->
                        OutlinedButton(
                            onClick = { preset = p },
                            shape = iOSShapes.capsule,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (preset == p) iOSBlue.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                        ) {
                            Text(p.persianLabel)
                        }
                    }
                }
                Text(
                    text = preset.description,
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Progress.
        if (isProcessing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = iOSBlueBright,
                    trackColor = iOSBlue.copy(alpha = 0.2f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = iOSBlueBright
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = (state as VoiceEnhanceState.Processing).message,
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Preview: stats + waveform (timeline untouched).
        if (state is VoiceEnhanceState.Preview) {
            val stats = state.stats
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A2A3A))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = iOSBlueBright,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "پیش‌نمایش · ${state.preset.persianLabel}",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "صدا: ${formatDb(stats.inputPeakDb)} → ${formatDb(stats.outputPeakDb)}" +
                        (if (stats.appliedGainDb > 0.05f) " (تقویت ${"%.1f".format(stats.appliedGainDb)}dB)" else "") +
                        "\nکاهش نویز تخمینی: ${"%.1f".format(stats.noiseReductionDb)}dB · مدت: ${"%.1f".format(stats.durationMs / 1000.0)} ثانیه",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                WaveformBars(label = "قبل", peaks = stats.waveformBefore, color = Color.Gray)
                WaveformBars(label = "بعد", peaks = stats.waveformAfter, color = iOSBlueBright)
            }
        }

        // Result.
        if (state is VoiceEnhanceState.Completed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A3A1A))
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color(0xFF30D158),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "بهبود صدای «${state.fileName}» اعمال شد (کاهش نویز تخمینی ${"%.1f".format(state.noiseReductionDb)}dB)",
                    color = Color(0xFF30D158),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Error.
        if (state is VoiceEnhanceState.Error) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF3A1A1A))
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color(0xFFFF375F),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.message,
                    color = Color(0xFFFF375F),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Actions.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            when {
                isProcessing -> {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        shape = iOSShapes.capsule
                    ) {
                        Text("لغو")
                    }
                }
                isPreview -> {
                    Button(
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                        shape = iOSShapes.capsule
                    ) {
                        Text("اعمال")
                    }
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.weight(1f),
                        shape = iOSShapes.capsule
                    ) {
                        Text("انصراف")
                    }
                }
                else -> {
                    Button(
                        onClick = { onEnhance(preset) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                        shape = iOSShapes.capsule
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("بهبود صدا")
                    }
                }
            }
            if (state is VoiceEnhanceState.Completed || state is VoiceEnhanceState.Error) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    shape = iOSShapes.capsule
                ) {
                    Text("بستن")
                }
            }
        }

        if (committedEnabled && state !is VoiceEnhanceState.Preview) {
            Text(
                text = "این کلیپ هم‌اکنون صدای بهبودیافته دارد و در خروجی جایگزین می‌شود.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun WaveformBars(label: String, peaks: List<Float>, color: Color) {
    if (peaks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            peaks.forEach { peak ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((2 + peak.coerceIn(0f, 1f) * 22).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(color.copy(alpha = 0.85f))
                )
            }
        }
    }
}

private fun formatDb(db: Float): String =
    if (!db.isFinite()) "−∞dB" else "${"%.1f".format(db)}dB"
