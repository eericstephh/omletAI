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
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.aicreatorstudio.app.model.AutoSilenceCutState
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * Panel for the AI Auto Silence Cut feature.
 *
 * PHASE 1 flow: tune settings → Analyze (preview what WOULD be cut)
 * → Apply or Discard. Shows controls for speech threshold, separate
 * pre/post speech padding, and minimum silence duration. Displays
 * progress while running and the segment preview before anything is
 * cut. The legacy one-shot [onRun] path is kept for compat.
 */
@Composable
fun AutoSilenceCutPanel(
    state: AutoSilenceCutState,
    onRun: (Float, Float, Float) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onAnalyze: (Float, Float, Float, Float) -> Unit = { threshold, pre, post, min ->
        onRun(threshold, (pre + post) / 2f, min)
    },
    onApplyPreview: () -> Unit = {},
    onDiscardPreview: () -> Unit = {},
    onToggleSegment: (String) -> Unit = {},
    onSelectAllSegments: (Boolean) -> Unit = {},
    onAnalyzeJumpCut: (Float, Float, Float, Float) -> Unit = { threshold, pre, post, min ->
        onAnalyze(threshold, pre, post, min)
    },
    modifier: Modifier = Modifier
) {
    var speechThreshold by remember { mutableFloatStateOf(0.5f) }
    var padBeforeMs by remember { mutableFloatStateOf(300f) }
    var padAfterMs by remember { mutableFloatStateOf(300f) }
    var minSilenceMs by remember { mutableFloatStateOf(500f) }
    // PHASE 3 — local mode switch. Jump-cut mode runs the same VAD
    // then ranks candidates (only strong ones pre-selected).
    var jumpMode by remember { mutableStateOf(false) }

    val isProcessing = state is AutoSilenceCutState.Processing
    val isPreview = state is AutoSilenceCutState.Preview
    val progress by animateFloatAsState(
        targetValue = if (isProcessing) (state as AutoSilenceCutState.Processing).progress else 0f,
        label = "silence-cut-progress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    imageVector = Icons.Filled.ContentCut,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "برش خودکار سکوت",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "شناسایی و حذف بخش‌های ساکت ویدیو",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // PHASE 3 — mode selector (settings phase only; preview locks it).
        if (!isProcessing && !isPreview) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { jumpMode = false },
                    shape = iOSShapes.capsule,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (!jumpMode) iOSBlue.copy(alpha = 0.2f) else Color.Transparent
                    )
                ) {
                    Text("حذف سکوت")
                }
                OutlinedButton(
                    onClick = { jumpMode = true },
                    shape = iOSShapes.capsule,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (jumpMode) iOSBlue.copy(alpha = 0.2f) else Color.Transparent
                    )
                ) {
                    Text("کات هوشمند")
                }
            }
            if (jumpMode) {
                Text(
                    text = "فقط مکث‌های باکیفیت پیشنهاد می‌شود؛ بقیه برای بازبینی دستی می‌ماند.",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Settings (disabled while processing)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Speech threshold
            Text(
                text = "آستانه تشخیص گفتار: ${String.format("%.0f%%", speechThreshold * 100)}",
                color = LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = speechThreshold,
                onValueChange = { speechThreshold = it },
                valueRange = 0.2f..0.9f,
                enabled = !isProcessing && !isPreview,
                colors = SliderDefaults.colors(
                    thumbColor = iOSBlueBright,
                    activeTrackColor = iOSBlueBright
                )
            )

            // Speech padding — before speech (protects word onsets)
            Text(
                text = "حاشیه قبل گفتار: ${String.format("%.0f", padBeforeMs)} میلی‌ثانیه",
                color = LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = padBeforeMs,
                onValueChange = { padBeforeMs = it },
                valueRange = 0f..1000f,
                steps = 9,
                enabled = !isProcessing && !isPreview,
                colors = SliderDefaults.colors(
                    thumbColor = iOSBlueBright,
                    activeTrackColor = iOSBlueBright
                )
            )

            // Speech padding — after speech (protects word tails)
            Text(
                text = "حاشیه بعد گفتار: ${String.format("%.0f", padAfterMs)} میلی‌ثانیه",
                color = LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = padAfterMs,
                onValueChange = { padAfterMs = it },
                valueRange = 0f..1000f,
                steps = 9,
                enabled = !isProcessing && !isPreview,
                colors = SliderDefaults.colors(
                    thumbColor = iOSBlueBright,
                    activeTrackColor = iOSBlueBright
                )
            )

            // Min silence duration
            Text(
                text = "حداقل مدت سکوت: ${String.format("%.0f", minSilenceMs)} میلی‌ثانیه",
                color = LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = minSilenceMs,
                onValueChange = { minSilenceMs = it },
                valueRange = 200f..3000f,
                steps = 13,
                enabled = !isProcessing && !isPreview,
                colors = SliderDefaults.colors(
                    thumbColor = iOSBlueBright,
                    activeTrackColor = iOSBlueBright
                )
            )
        }

        // Progress indicator
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = iOSBlueBright
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = (state as AutoSilenceCutState.Processing).message,
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // PHASE 2 preview — per-cut selection. Timeline untouched;
        // toggling only flips preview state (no VAD, no decode).
        if (state is AutoSilenceCutState.Preview) {
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
                        text = if (state.mode == com.aicreatorstudio.app.model.SilencePreviewMode.SMART_JUMP_CUT)
                            "${state.totalSegments} کاندید · ${state.selectedCount} پیشنهادی (کاهش تخمینی ${String.format("%.1f", state.selectedMs / 1000.0)} ثانیه)"
                        else
                            "${state.totalSegments} سکوت پیدا شد · ${state.selectedCount} انتخاب (${String.format("%.1f", state.selectedMs / 1000.0)} ثانیه)",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (state.totalSegments == 0) {
                    Text(
                        text = "سکوت قابل برشی پیدا نشد — تنظیمات را تغییر دهید یا انصراف بزنید.",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSelectAllSegments(true) },
                            shape = iOSShapes.capsule
                        ) {
                            Text("انتخاب همه")
                        }
                        OutlinedButton(
                            onClick = { onSelectAllSegments(false) },
                            shape = iOSShapes.capsule
                        ) {
                            Text("لغو انتخاب")
                        }
                    }
                }
                state.clips.filter { it.segments.isNotEmpty() }.take(4).forEach { clip ->
                    Text(
                        text = "• ${clip.fileName}",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    clip.segments.take(8).forEach { seg ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = seg.selected,
                                onCheckedChange = { onToggleSegment(seg.id) },
                                colors = CheckboxDefaults.colors(checkedColor = iOSBlueBright)
                            )
                            Text(
                                text = "${String.format("%.2f", seg.startMs / 1000.0)} → ${String.format("%.2f", seg.endMs / 1000.0)} · " +
                                    "${String.format("%.2f", seg.durationMs / 1000.0)}s · ${(seg.confidence * 100).toInt()}٪" +
                                    jumpCutReasonLabel(seg.reason),
                                color = if (seg.selected) LabelPrimary else LabelSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (clip.segments.size > 8) {
                        Text(
                            text = "… و ${clip.segments.size - 8} بخش دیگر",
                            color = LabelSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.clips.count { it.segments.isNotEmpty() } > 4) {
                    Text(
                        text = "… و ${state.clips.count { it.segments.isNotEmpty() } - 4} کلیپ دیگر",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Result
        if (state is AutoSilenceCutState.Completed) {
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
                Column {
                    Text(
                        text = "برش سکوت با موفقیت انجام شد",
                        color = Color(0xFF30D158),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${state.silencesRemoved} بخش سکوت حذف شد (${String.format("%.1f", state.silenceMsRemoved / 1000.0)} ثانیه)",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Error
        if (state is AutoSilenceCutState.Error) {
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

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isProcessing) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = iOSShapes.capsule
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("لغو")
                }
            } else if (isPreview) {
                // Requirement 8: empty selection → Apply disabled, so no
                // empty history operation can ever be created from UI.
                val selectedCount = (state as AutoSilenceCutState.Preview).selectedCount
                Button(
                    onClick = onApplyPreview,
                    enabled = selectedCount > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                    shape = iOSShapes.capsule
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCut,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("اعمال برش")
                }
                OutlinedButton(
                    onClick = onDiscardPreview,
                    modifier = Modifier.weight(1f),
                    shape = iOSShapes.capsule
                ) {
                    Text("انصراف")
                }
            } else {
                Button(
                    onClick = {
                        if (jumpMode) onAnalyzeJumpCut(speechThreshold, padBeforeMs, padAfterMs, minSilenceMs)
                        else onAnalyze(speechThreshold, padBeforeMs, padAfterMs, minSilenceMs)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                    shape = iOSShapes.capsule
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCut,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (jumpMode) "کات هوشمند" else "شناسایی سکوت")
                }
            }

            if (state is AutoSilenceCutState.Completed || state is AutoSilenceCutState.Error) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = iOSShapes.capsule
                ) {
                    Text("بستن")
                }
            }
        }

        // Info text
        Text(
            text = "این ابزار با استفاده از هوش مصنوعی بخش‌های ساکت ویدیو را شناسایی و حذف می‌کند. تنظیمات بالا حساسیت تشخیص را کنترل می‌کنند.",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * PHASE 3 — human-readable reason suffix for a ranked candidate.
 * Raw scores stay internal; users see causes. Null (classic mode)
 * renders nothing.
 */
private fun jumpCutReasonLabel(reason: com.aicreatorstudio.app.ai.vad.JumpCutReason?): String =
    when (reason) {
        com.aicreatorstudio.app.ai.vad.JumpCutReason.EXTENDED_SILENCE -> " · سکوت کشیده"
        com.aicreatorstudio.app.ai.vad.JumpCutReason.LONG_PAUSE -> " · مکث طولانی"
        com.aicreatorstudio.app.ai.vad.JumpCutReason.MEDIUM_PAUSE -> " · مکث متوسط"
        com.aicreatorstudio.app.ai.vad.JumpCutReason.SHORT_PAUSE -> " · مکث کوتاه"
        com.aicreatorstudio.app.ai.vad.JumpCutReason.BOUNDARY -> " · ابتدا/انتهای کلیپ"
        com.aicreatorstudio.app.ai.vad.JumpCutReason.LOW_CONFIDENCE -> " · اطمینان پایین"
        null -> ""
    }
