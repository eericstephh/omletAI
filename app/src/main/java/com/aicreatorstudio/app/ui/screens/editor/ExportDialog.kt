package com.aicreatorstudio.app.ui.screens.editor

import android.content.Context
import android.content.Intent
import android.net.Uri

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicreatorstudio.app.export.ExportEstimator
import com.aicreatorstudio.app.export.ExportQuality
import com.aicreatorstudio.app.export.ExportStage
import com.aicreatorstudio.app.export.ExportState
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.theme.accentBright
import com.aicreatorstudio.app.ui.theme.accentDeep
import com.aicreatorstudio.app.ui.theme.accentPrimary
import com.aicreatorstudio.app.ui.theme.glowBrand
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicElevated
import com.aicreatorstudio.app.ui.theme.CinematicHighest
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.ctaBrush
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSError
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSShapes
import java.util.Locale

/**
 * 2026-10-11 — Persian label for an export stage.
 *
 * The dialog is Persian elsewhere ("در حال آماده‌سازی…"), but the
 * exporting branch used to print `ExportStage.displayName` raw, which is
 * English. That was survivable while every stage was a synonym for
 * "encoding"; it stops being survivable now that FINALIZING is a
 * distinct, user-visible state — the whole point of the stall fix is
 * that the user can tell "still encoding" from "flushing the file", so
 * the distinction has to be legible.
 *
 * Kept as a local `when` rather than new string resources because this
 * dialog already hardcodes its Persian copy, and adding a resource
 * plumbing path for five labels would be a larger change than the bug
 * warrants.
 */
private fun stageLabel(stage: ExportStage): String = when (stage) {
    ExportStage.PREPARING -> "در حال آماده‌سازی…"
    ExportStage.DECODING -> "در حال رمزگشایی…"
    ExportStage.APPLYING_EFFECTS -> "در حال اعمال افکت‌ها…"
    ExportStage.ENCODING -> "در حال انکود ویدئو…"
    ExportStage.FINALIZING -> "در حال نهایی‌سازی فایل…"
}

/**
 * Modal dialog for the export flow — redesigned with an iOS-style sheet look.
 *
 * The dialog owns the "pick quality → start" sub-flow, then observes
 * [ExportState] for progress and the final result. It does not know
 * about Media3 or Hilt — all it sees is [ExportState] and the three
 * callbacks.
 */
@Composable
fun ExportDialog(
    state: ExportState,
    // 2026-10-05 — real project duration for the estimator panel.
    durationMs: Long = 0L,
    onDismiss: () -> Unit,
    onStart: (ExportQuality) -> Unit,
    onCancel: () -> Unit
) {
    when (state) {
        is ExportState.Idle -> QualityPickerDialog(
            durationMs = durationMs,
            onDismiss = onDismiss,
            onStart = onStart
        )
        // 2026-09-10 FIX — Preparing previously fell through to the
        // quality picker again, so after tapping "Start Export" the
        // dialog appeared frozen. Show real preparing feedback now.
        is ExportState.Preparing -> ProgressDialog(
            progress = 0f,
            stage = "در حال آماده‌سازی…",
            onCancel = onCancel
        )
        is ExportState.Exporting -> ProgressDialog(
            progress = state.progress.progress,
            stage = stageLabel(state.progress.currentStage),
            onCancel = onCancel
        )
        is ExportState.Succeeded -> SuccessDialog(
            state = state,
            onDismiss = onDismiss
        )
        is ExportState.Failed -> FailureDialog(
            state = state,
            onDismiss = onDismiss
        )
        is ExportState.Cancelled -> CancelledDialog(onDismiss = onDismiss)
    }
}

/**
 * iOS-style modal sheet container — a custom Dialog that wraps content in
 * a rounded GlassSurface, dimming the background like a UIAlertController.
 */
@Composable
private fun iOSDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            GlassSurface(
                shape = RoundedCornerShape(22.dp),
                material = GlassMaterial.Regular
            ) {
                content()
            }
        }
    }
}

@Composable
private fun QualityPickerDialog(
    durationMs: Long,
    onDismiss: () -> Unit,
    onStart: (ExportQuality) -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(ExportQuality.HIGH_1080P) }
    iOSDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Text(
                text = "خروجی ویدئو",
                style = MaterialTheme.typography.labelSmall,
                color = accentBright(),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "کیفیت خروجی را انتخاب کنید",
                style = MaterialTheme.typography.titleLarge,
                color = LabelPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "رزولوشن بالاتر زمان بیشتری برای انکود می‌گیرد و فایل بزرگ‌تری می‌سازد.",
                style = MaterialTheme.typography.bodySmall,
                color = LabelSecondary
            )
            Spacer(Modifier.height(12.dp))
            Column(modifier = Modifier.selectableGroup()) {
                ExportQuality.entries.forEach { quality ->
                    QualityRow(
                        quality = quality,
                        selected = quality == selected,
                        onSelected = { selected = quality }
                    )
                    Spacer(Modifier.height(5.dp))
                }
            }
            // 2026-10-05 — REAL export facts instead of a vague hint. The
            // size estimate is derived from the quality tier's bitrate,
            // which the encoder actually applies, so it is honest within
            // container/encoder overhead. Labelled with "≈" on purpose.
            if (durationMs > 0L) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ExportFactCard(
                        label = "مدت",
                        value = ExportEstimator.formatDuration(durationMs),
                        modifier = Modifier.weight(1f)
                    )
                    ExportFactCard(
                        label = "حجم تقریبی",
                        value = "≈ " + ExportEstimator.formatSize(
                            ExportEstimator.estimatedFileSizeBytes(durationMs, selected)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                iOSDialogButton(
                    label = "انصراف",
                    onClick = onDismiss,
                    style = iOSDialogButtonStyle.Gray,
                    modifier = Modifier.weight(1f)
                )
                iOSDialogButton(
                    label = "شروع خروجی",
                    onClick = { onStart(selected) },
                    style = iOSDialogButtonStyle.Filled,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One fact tile in the quality picker's info panel (duration / estimated
 * size). A compact iOS-style card: muted label over a bright value.
 */
@Composable
private fun ExportFactCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HairlineSubtle)
            .border(1.dp, Hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LabelSecondary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = LabelPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun QualityRow(
    quality: ExportQuality,
    selected: Boolean,
    onSelected: () -> Unit
) {    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) accentPrimary().copy(alpha = 0.18f)
                else HairlineSubtle
            )
            .border(
                1.dp,
                if (selected) accentPrimary().copy(alpha = 0.45f) else Hairline,
                RoundedCornerShape(10.dp)
            )
            .selectable(
                selected = selected,
                onClick = onSelected,
                role = Role.RadioButton
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // iOS-style radio button
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(percent = 50))
                .border(
                    width = 1.5.dp,
                    color = if (selected) accentPrimary() else BorderSubtle,
                    shape = RoundedCornerShape(percent = 50)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(accentPrimary())
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                quality.displayName,
                // 2026-10-16 (Phase 2) — the selected row was distinguished
                // only by an 18% accent wash and the radio dot; the label
                // stayed identical to the unselected rows. Accent-tinting
                // the selected label matches how the subtitle template
                // picker marks its selection, so "selected" reads the same
                // everywhere in the app.
                color = if (selected) accentBright() else LabelPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${quality.widthPx} × ${quality.heightPx} • ${quality.frameRate} fps",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // (B1) quality rows keep their radio layout; facts live in
        // [ExportFactCard] below the list.
    }
}

@Composable
private fun ProgressDialog(
    progress: Float,
    stage: String,
    onCancel: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "export-progress"
    )
        iOSDialog(onDismissRequest = { /* not dismissable while running */ }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 2026-09-06 — "omelet cooking" export animation:
                // a frying pan cooking a fried-omelet shaped like a
                // vintage film reel. Fully vector (PNG-like crispness,
                // transparent background) drawn on one Canvas.
                com.aicreatorstudio.app.ui.components.OmeletCookingAnimation(
                    modifier = Modifier.size(150.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "در حال خروجی گرفتن",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentBright(),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stage,
                    style = MaterialTheme.typography.titleLarge,
                    color = LabelPrimary,
                    fontWeight = FontWeight.Bold
                )
            Spacer(Modifier.height(16.dp))
            // iOS-style progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(BorderSubtle)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(accentPrimary(), accentBright())
                            )
                        )
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(20.dp))
            iOSDialogButton(
                label = "انصراف",
                onClick = onCancel,
                style = iOSDialogButtonStyle.Gray,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SuccessDialog(
    state: ExportState.Succeeded,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sizeMb = (state.fileSizeBytes / 1_000_000.0)
    val durationSec = state.durationMs / 1000.0
    iOSDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(iOSGreen.copy(alpha = 0.18f))
                    .border(1.dp, iOSGreen.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(iOSGreen.copy(alpha = 0.3f), Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                                radius = size.maxDimension * 0.8f
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = iOSGreen,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "خروجی آماده شد",
                style = MaterialTheme.typography.titleLarge,
                color = LabelPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                state.fileName,
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            // Stats grid
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatPill(label = "حجم", value = String.format(Locale.US, "%.1f MB", sizeMb), modifier = Modifier.weight(1f))
                StatPill(label = "مدت", value = String.format(Locale.US, "%.1fs", durationSec), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            val canOpen = state.outputUri != null && state.outputUri.scheme != "file"
            iOSDialogButton(
                label = if (canOpen) "پخش ویدئو" else "تمام",
                onClick = {
                    if (canOpen) launchOpenIntent(context, state.outputUri, onDismiss)
                    else onDismiss()
                },
                style = iOSDialogButtonStyle.Filled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HairlineSubtle)
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LabelSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = LabelPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FailureDialog(
    state: ExportState.Failed,
    onDismiss: () -> Unit
) {
    iOSDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(iOSError.copy(alpha = 0.18f))
                    .border(1.dp, iOSError.copy(alpha = 0.5f), RoundedCornerShape(percent = 50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = iOSError,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "خروجی ناموفق بود",
                style = MaterialTheme.typography.titleLarge,
                color = LabelPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                state.message,
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            state.cause?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it::class.simpleName ?: "خطای ناشناخته",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(14.dp))
            iOSDialogButton(
                label = "باشه",
                onClick = onDismiss,
                style = iOSDialogButtonStyle.Filled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CancelledDialog(onDismiss: () -> Unit) {
    iOSDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "خروجی لغو شد",
                style = MaterialTheme.typography.titleLarge,
                color = LabelPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "هیچ فایلی ذخیره نشد.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(14.dp))
            iOSDialogButton(
                label = "باشه",
                onClick = onDismiss,
                style = iOSDialogButtonStyle.Filled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private enum class iOSDialogButtonStyle { Filled, Gray }

@Composable
private fun iOSDialogButton(
    label: String,
    onClick: () -> Unit,
    style: iOSDialogButtonStyle,
    modifier: Modifier = Modifier
) {
    val brush = when (style) {
        // 2026-10-16 (Phase 2) — was a horizontal accentPrimary() → accentBright()
        // gradient under WHITE text. Measured: white on #8B5CF6 is 4.0:1 and
        // white on #A78BFA is 2.7:1, so the primary button of EVERY export
        // dialog failed WCAG AA. [ctaBrush] is the project's sanctioned
        // white-text gradient (accentDeep() → AccentCtaBottom; white measures
        // 5.7:1 → 7.1:1) and is already the toolbar Export pill's fill, so
        // the dialog CTA now matches the control that opened it.
        iOSDialogButtonStyle.Filled -> ctaBrush()
        // 2026-10-16 — `0xFF3A3A3C` / `0xFF2C2C2E` were iOS system grays from
        // outside the palette. Mapped onto the neutral elevation ramp;
        // [LabelPrimary] on [CinematicHighest] is 13:1.
        iOSDialogButtonStyle.Gray -> Brush.verticalGradient(
            listOf(CinematicHighest, CinematicElevated)
        )
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(iOSShapes.capsule)
            .background(brush)
            .border(1.dp, HairlineSubtle, iOSShapes.capsule)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = LabelPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun launchOpenIntent(context: Context, uri: Uri?, onDismiss: () -> Unit) {
    if (uri == null || uri.scheme == "file") {
        onDismiss()
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
    onDismiss()
}
