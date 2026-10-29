package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ai.highlight.HighlightReason
import com.aicreatorstudio.app.model.HighlightState
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * PHASE 7 — Smart Highlight Detection panel.
 *
 * Analyze (existing local signals) → ranked cards with score bars
 * and reasons → tap-to-seek preview (no timeline change) →
 * per-card select → Apply stores non-destructive ranges (future
 * AutoEdit input) / Cancel. Fully offline.
 */
@Composable
fun HighlightPanel(
    state: HighlightState,
    selectedClip: VideoClip?,
    committedCount: Int,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onClearCommitted: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onSeek: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isProcessing = state is HighlightState.Processing
    val isPreview = state is HighlightState.Preview
    val progress by animateFloatAsState(
        targetValue = if (isProcessing) (state as HighlightState.Processing).progress else 0f,
        label = "highlight-progress"
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
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "هایلایت هوشمند",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "بهترین لحظات ویدیو · آفلاین",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (committedCount > 0 && !isProcessing && !isPreview) {
                OutlinedButton(
                    onClick = onClearCommitted,
                    shape = iOSShapes.capsule
                ) {
                    Text("حذف هایلایت‌ها")
                }
            }
        }

        if (selectedClip == null || selectedClip.isAudioOnly) {
            Text(
                text = "برای هایلایت هوشمند، یک کلیپ ویدیویی را انتخاب کنید.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
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
                        text = (state as HighlightState.Processing).message,
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Preview cards.
        if (state is HighlightState.Preview) {
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
                        text = "${state.candidates.size} هایلایت · ${state.selectedCount} انتخاب",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (state.candidates.isEmpty()) {
                    Text(
                        text = "هایلایت قابل‌توجهی پیدا نشد.",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSelectAll(true) },
                            shape = iOSShapes.capsule
                        ) {
                            Text("انتخاب همه")
                        }
                        OutlinedButton(
                            onClick = { onSelectAll(false) },
                            shape = iOSShapes.capsule
                        ) {
                            Text("لغو انتخاب")
                        }
                    }
                }
                state.candidates.take(10).forEachIndexed { index, cand ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .padding(8.dp)
                    ) {
                        Checkbox(
                            checked = cand.selected,
                            onCheckedChange = { onToggle(cand.id) },
                            colors = CheckboxDefaults.colors(checkedColor = iOSBlueBright)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "#${index + 1} ${formatRange(cand.startMs, cand.endMs)} · " +
                                    "${"%.1f".format(cand.durationMs / 1000.0)}s",
                                color = if (cand.selected) LabelPrimary else LabelSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            // Score bar (visual indicator, not a raw number dump).
                            LinearProgressIndicator(
                                progress = { cand.score.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = iOSBlueBright,
                                trackColor = Color.White.copy(alpha = 0.12f)
                            )
                            Text(
                                text = highlightReasonLabel(cand.reason),
                                color = LabelSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        // Tap-to-seek: existing playhead, no timeline change.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(iOSBlue.copy(alpha = 0.2f))
                                .clickable { onSeek(cand.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "پیش‌نمایش",
                                tint = iOSBlueBright,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                if (state.candidates.size > 10) {
                    Text(
                        text = "… و ${state.candidates.size - 10} هایلایت دیگر",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Result.
        if (state is HighlightState.Completed) {
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
                    text = "${state.highlightsStored} هایلایت در «${state.fileName}» ذخیره شد",
                    color = Color(0xFF30D158),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Error.
        if (state is HighlightState.Error) {
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
                    val selectedCount = (state as HighlightState.Preview).selectedCount
                    Button(
                        onClick = onApply,
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                        shape = iOSShapes.capsule
                    ) {
                        Text("ذخیره ($selectedCount)")
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
                        onClick = onAnalyze,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                        shape = iOSShapes.capsule
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("تحلیل هایلایت")
                    }
                }
            }
            if (state is HighlightState.Completed || state is HighlightState.Error) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    shape = iOSShapes.capsule
                ) {
                    Text("بستن")
                }
            }
        }

        if (committedCount > 0 && state !is HighlightState.Preview) {
            Text(
                text = "این کلیپ $committedCount هایلایت ذخیره‌شده دارد.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatRange(startMs: Long, endMs: Long): String {
    fun fmt(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%05.2f".format(totalSec / 60, (ms % 60_000) / 1000f)
    }
    return "${fmt(startMs)} → ${fmt(endMs)}"
}

private fun highlightReasonLabel(reason: HighlightReason): String = when (reason) {
    HighlightReason.STRONG_MIX -> "ترکیب چند سیگنال قوی"
    HighlightReason.SPEECH_ACTIVE -> "فعالیت گفتاری بالا"
    HighlightReason.ENERGY_PEAK -> "اوج انرژی صدا"
    HighlightReason.RHYTHM_ACTIVE -> "فعالیت ریتمیک بالا"
    HighlightReason.VISUAL_CHANGE -> "تغییر بصری قابل توجه"
    HighlightReason.MODERATE -> "امتیاز متوسط"
}
