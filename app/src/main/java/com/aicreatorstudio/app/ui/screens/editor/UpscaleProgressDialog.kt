package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicreatorstudio.app.export.UpscalerExportState
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.iOSRed

// 2026-10-17 PHASE 3 — Persian stage labels.
//
// `UpscalerExportState.stageLabels` (the export model) is authored in
// English. The model also feeds the unreferenced `UpscaleBottomSheet`, and
// the export engine has no business carrying UI copy, so the labels are
// mapped here at the presentation layer instead of mutating the model.
// Order matches `UpscalerExportState.stageLabels` exactly.
private val UpscaleStageLabels = listOf(
    "در حال بهبود فریم‌ها…",
    "بازسازی جزئیات با کیفیت بالا…",
    "نهایی‌سازی ویدئو…"
)

/**
 * Animated progress modal for the AI upscale / enhance export.
 *
 * Renders one of three presentations depending on [state]:
 *  - [UpscalerExportState.Idle] → not displayed
 *  - Preparing / EnhancingFrames / ReconstructingDetails / Finalizing4K
 *    → animated progress dialog with three stage chips that light up
 *    as the export advances, plus an indeterminate CircularProgressIndicator
 *    and a 6dp iOS-style progress bar.
 *  - [UpscalerExportState.Succeeded] → "Done" presentation with file
 *    name + size + duration.
 *  - [UpscalerExportState.Failed] / [UpscalerExportState.Cancelled]
 *    → terminal presentation with the relevant message.
 *
 * Mirrors the visual style of `ExportDialog.ProgressDialog` (centered
 * Dialog, `usePlatformDefaultWidth = false`, GlassSurface card) but
 * with a custom three-stage indicator tailored to the AI upscale flow.
 */
@Composable
fun UpscaleProgressDialog(
    state: UpscalerExportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state is UpscalerExportState.Idle) return

    Dialog(
        onDismissRequest = { /* block dismiss by tap-outside */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        GlassSurface(
            shape = RoundedCornerShape(22.dp),
            material = GlassMaterial.Regular,
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 22.dp)
            ) {
                when (state) {
                    is UpscalerExportState.Succeeded -> SuccessBody(
                        state = state,
                        onDismiss = onDismiss
                    )
                    is UpscalerExportState.Failed -> FailureBody(
                        state = state,
                        onDismiss = onDismiss
                    )
                    is UpscalerExportState.Cancelled -> CancelledBody(onDismiss = onDismiss)
                    else -> InProgressBody(
                        state = state,
                        onCancel = onCancel
                    )
                }
            }
        }
    }
}

// ---- In-progress ----

@Composable
private fun InProgressBody(
    state: UpscalerExportState,
    onCancel: () -> Unit
) {
    val activeStageIndex = when (state) {
        is UpscalerExportState.Preparing -> 0
        is UpscalerExportState.EnhancingFrames -> 0
        is UpscalerExportState.ReconstructingDetails -> 1
        is UpscalerExportState.Finalizing4K -> 2
        else -> 0
    }
    val stageProgress = when (state) {
        is UpscalerExportState.EnhancingFrames -> state.progress
        is UpscalerExportState.ReconstructingDetails -> state.progress
        is UpscalerExportState.Finalizing4K -> state.progress
        else -> 0f
    }
    val overall = when (activeStageIndex) {
        0 -> stageProgress * 0.33f
        1 -> 0.33f + stageProgress * 0.33f
        2 -> 0.66f + stageProgress * 0.34f
        else -> 0f
    }
    val animatedOverall by animateFloatAsState(
        targetValue = overall,
        animationSpec = tween(durationMillis = 350),
        label = "upscale-overall"
    )

    // ---- Header ----
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            iOSBlue.copy(alpha = 0.7f),
                            iOSPurple.copy(alpha = 0.7f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "بزرگ‌نمایی هوشمند",
                color = iOSBlueBright,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            AnimatedContent(
                targetState = UpscaleStageLabels[activeStageIndex],
                transitionSpec = {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(220)))
                },
                label = "stage-label"
            ) { label ->
                Text(
                    text = label,
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = iOSBlueBright,
            modifier = Modifier.size(22.dp)
        )
    }
    Spacer(Modifier.height(14.dp))

    // ---- Three stage chips ----
    Column(modifier = Modifier.fillMaxWidth()) {
        UpscaleStageLabels.forEachIndexed { index, label ->
            val isActive = index == activeStageIndex
            val isDone = index < activeStageIndex
            StageChip(
                label = label,
                isActive = isActive,
                isDone = isDone
            )
            if (index != UpscaleStageLabels.lastIndex) {
                Spacer(Modifier.height(6.dp))
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    // ---- Overall progress bar ----
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedOverall.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    Brush.horizontalGradient(
                        listOf(iOSBlue, iOSBlueBright, iOSPurple)
                    )
                )
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "${(animatedOverall * 100).toInt()}% · " +
            UpscaleStageLabels[activeStageIndex].removeSuffix("…"),
        color = LabelSecondary,
        style = MaterialTheme.typography.labelSmall
    )
    Spacer(Modifier.height(16.dp))

    // ---- Cancel button ----
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                .clickable(onClick = onCancel)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = "انصراف",
                color = LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StageChip(
    label: String,
    isActive: Boolean,
    isDone: Boolean
) {
    val iconBg = when {
        isActive -> Brush.horizontalGradient(listOf(iOSBlue, iOSPurple))
        isDone -> Brush.horizontalGradient(listOf(iOSGreen, iOSGreen))
        else -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.06f)))
    }
    val textColor = when {
        isActive -> iOSBlueBright
        isDone -> iOSGreen
        else -> LabelSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            when {
                isDone -> Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                isActive -> CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    color = Color.White,
                    modifier = Modifier.size(11.dp)
                )
                else -> Text(
                    text = "·",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label.removeSuffix("…"),
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isActive || isDone) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---- Terminal states ----

@Composable
private fun SuccessBody(
    state: UpscalerExportState.Succeeded,
    onDismiss: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iOSGreen.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "خروجی آماده شد",
                color = iOSGreen,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "ویدئوی بهبودیافته با هوش مصنوعی آماده است.",
                color = LabelPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        text = state.fileName,
        color = LabelPrimary,
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "${(state.fileSizeBytes / 1024 / 1024)} MB · " +
            "${state.durationMs / 1000}s",
        color = LabelSecondary,
        style = MaterialTheme.typography.labelSmall
    )
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TerminalButton(text = "تمام", color = iOSBlueBright, onClick = onDismiss)
    }
}

@Composable
private fun FailureBody(
    state: UpscalerExportState.Failed,
    onDismiss: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iOSRed.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "خروجی ناموفق بود",
                color = iOSRed,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = state.message,
                color = LabelPrimary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TerminalButton(text = "بستن", color = iOSBlueBright, onClick = onDismiss)
    }
}

@Composable
private fun CancelledBody(onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(LabelSecondary.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "خروجی لغو شد",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "خروجی بزرگ‌نمایی لغو شد.",
                color = LabelPrimary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TerminalButton(text = "بستن", color = iOSBlueBright, onClick = onDismiss)
    }
}

@Composable
private fun TerminalButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
