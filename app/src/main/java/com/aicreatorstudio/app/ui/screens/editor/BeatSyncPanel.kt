package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.MusicNote
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
import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.BeatSyncState
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes
import com.aicreatorstudio.app.util.BeatSyncMode
import com.aicreatorstudio.app.util.BeatSyncReason

/**
 * PHASE 6 — Local Beat Sync panel.
 *
 * Mode → Analyze (existing on-device beat markers, ranked) →
 * per-cut accept/reject Preview (timeline untouched) → Apply
 * (one history entry via batch split) / Cancel. Music bytes are
 * never modified — only video cut points move to strong beats.
 */
@Composable
fun BeatSyncPanel(
    state: BeatSyncState,
    videoClip: VideoClip?,
    audioClip: AudioClip?,
    audioAnalyzed: Boolean,
    onAnalyze: (BeatSyncMode) -> Unit,
    onEnsureBeats: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onToggleCandidate: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(BeatSyncMode.Default) }

    val isProcessing = state is BeatSyncState.Processing
    val isPreview = state is BeatSyncState.Preview
    val progress by animateFloatAsState(
        targetValue = if (isProcessing) (state as BeatSyncState.Processing).progress else 0f,
        label = "beat-sync-progress"
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
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "سینک ضرب",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "کات ویدیو روی ضرب‌های موزیک · آفلاین",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (videoClip == null || videoClip.isAudioOnly) {
            Text(
                text = "برای سینک ضرب، یک کلیپ ویدیویی را انتخاب کنید.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }
        if (audioClip == null) {
            Text(
                text = "یک کلیپ صوتی/موزیک را از لاین صدا انتخاب کنید.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        // Source summary.
        Text(
            text = "🎬 ${videoClip.fileName} · 🎵 ${audioClip.fileName}",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )

        // Beats not analyzed yet → delegate to the existing detector.
        if (!audioAnalyzed && !isProcessing && !isPreview) {
            Text(
                text = "این موزیک هنوز تحلیل ضرب نشده است.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = onEnsureBeats,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                shape = iOSShapes.capsule
            ) {
                Text("تشخیص ضرباهنگ")
            }
            return@Column
        }

        // Density mode (settings phase only).
        if (!isProcessing && !isPreview && audioAnalyzed) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "تراکم کات",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BeatSyncMode.entries.forEach { m ->
                        OutlinedButton(
                            onClick = { mode = m },
                            shape = iOSShapes.capsule,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (mode == m) iOSBlue.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                        ) {
                            Text(m.persianLabel)
                        }
                    }
                }
                Text(
                    text = mode.description,
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
                        text = (state as BeatSyncState.Processing).message,
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Preview: BPM + ranked candidates + beat strip.
        if (state is BeatSyncState.Preview) {
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
                        text = (state.bpm?.let { "$it BPM · " } ?: "BPM نامشخص · ") +
                            "${state.beatCount} ضرب · ${state.selectedCount} کات انتخابی",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (state.candidates.isEmpty()) {
                    Text(
                        text = "کاندیدی برای برش پیدا نشد.",
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
                    BeatStrip(
                        candidates = state.candidates,
                        clipEndMs = (state.candidates.maxOfOrNull { it.cutTimelineMs } ?: 0L) + 1000L,
                        clipOriginMs = state.candidates.minOfOrNull { it.cutTimelineMs }?.minus(1000L) ?: 0L
                    )
                    state.candidates.take(12).forEach { cand ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = cand.selected,
                                onCheckedChange = { onToggleCandidate(cand.id) },
                                colors = CheckboxDefaults.colors(checkedColor = iOSBlueBright)
                            )
                            Text(
                                text = "${formatTime(cand.cutTimelineMs)} · ${beatSyncReasonLabel(cand.reason)}",
                                color = if (cand.selected) LabelPrimary else LabelSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (state.candidates.size > 12) {
                        Text(
                            text = "… و ${state.candidates.size - 12} کاندید دیگر",
                            color = LabelSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Result.
        if (state is BeatSyncState.Completed) {
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
                    text = "${state.cutsApplied} کات روی ضرب در «${state.fileName}» اعمال شد",
                    color = Color(0xFF30D158),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Error.
        if (state is BeatSyncState.Error) {
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
                    val selectedCount = (state as BeatSyncState.Preview).selectedCount
                    Button(
                        onClick = onApply,
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                        shape = iOSShapes.capsule
                    ) {
                        Text("اعمال ($selectedCount)")
                    }
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.weight(1f),
                        shape = iOSShapes.capsule
                    ) {
                        Text("انصراف")
                    }
                }
                audioAnalyzed -> {
                    Button(
                        onClick = { onAnalyze(mode) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                        shape = iOSShapes.capsule
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("ساخت پیشنهاد")
                    }
                }
            }
            if (state is BeatSyncState.Completed || state is BeatSyncState.Error) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    shape = iOSShapes.capsule
                ) {
                    Text("بستن")
                }
            }
        }
    }
}

/**
 * Lightweight beat strip: strong beats as tall ticks, selected cuts
 * as markers. Positional context only — not a second timeline.
 */
@Composable
private fun BeatStrip(
    candidates: List<com.aicreatorstudio.app.util.BeatSyncCandidate>,
    clipEndMs: Long,
    clipOriginMs: Long
) {
    if (candidates.isEmpty()) return
    val span = (clipEndMs - clipOriginMs).coerceAtLeast(1L)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        val w = size.width
        val h = size.height
        for (cand in candidates) {
            val x = ((cand.cutTimelineMs - clipOriginMs).toFloat() / span * w)
                .coerceIn(0f, w)
            val tickH = h * (0.35f + 0.65f * cand.beatStrength.coerceIn(0f, 1f))
            drawLine(
                color = Color.Gray.copy(alpha = 0.7f),
                start = androidx.compose.ui.geometry.Offset(x, h),
                end = androidx.compose.ui.geometry.Offset(x, h - tickH),
                strokeWidth = 2f
            )
            if (cand.selected) {
                drawCircle(
                    color = Color(0xFFFFD54A),
                    radius = 6f,
                    center = androidx.compose.ui.geometry.Offset(x, 8f)
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%02d:%05.2f".format(totalSec / 60, (ms % 60_000) / 1000f)
}

private fun beatSyncReasonLabel(reason: BeatSyncReason): String = when (reason) {
    BeatSyncReason.STRONG_BEAT -> "هم‌زمانی دقیق با ضرب"
    BeatSyncReason.GOOD_SPACING -> "فاصله مناسب برای کات"
    BeatSyncReason.WEAK_BEAT -> "ضرب ضعیف"
    BeatSyncReason.TOO_CLOSE -> "کات بیش از حد نزدیک"
}
