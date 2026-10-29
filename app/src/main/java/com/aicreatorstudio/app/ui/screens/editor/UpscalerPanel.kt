package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ai.AiModelCatalog
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.AiToolStatus
import com.aicreatorstudio.app.ai.AiToolUiPolicy
import com.aicreatorstudio.app.ai.upscale.UpscaleProgress
import com.aicreatorstudio.app.model.UpscalerPreset
import com.aicreatorstudio.app.model.UpscalerSettings
import com.aicreatorstudio.app.ui.components.ZeroSnapSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.iOSRed

/**
 * Apple-style "AI Upscale / Enhance" sub-panel.
 *
 * Layout (top-to-bottom):
 *  1. Header — sparkle icon + title + active indicator + reset pill
 *  2. Preset cards — horizontal scroll of three squircle cards
 *     (Smart HD / Ultra 4K / Face & Clarity). Tapping selects.
 *  3. Strength slider — ZeroSnapSlider, 0..1, with iOSBlueBright
 *     readout on the right.
 *  4. (Collapsible) Description card — explains the active preset.
 *  5. Model status hint — shown when a TFLite model is / isn't
 *     downloaded. The current build uses Lanczos resampling so this is
 *     informational only, but the slot is wired up for the future
 *     ESRGAN drop-in.
 *
 * Mirrors the visual style of [ColorGradingPanel]:
 *  - dark glass background (Color.White 5% alpha + hairline border)
 *  - RoundedCornerShape(14.dp) for cards
 *  - iOSBlue / iOSBlueBright for accent
 *
 * @param settings current upscaler settings
 * @param onPresetSelected fires when the user picks one of the three
 *   presets (not fired for the OFF chip — that lives in the reset)
 * @param onStrengthChanged fires on every slider tick
 * @param onReset resets settings to identity
 * @param onExportClicked fires when the user taps the "Export" button;
 *   the engine kicks off the upscale export and the progress dialog
 *   opens. Suppressed when no preset is active.
 * @param isProcessing true when an upscale export is in flight; the
 *   preset cards and slider are disabled in that state
 * @param modelStatus optional one-line text shown under the slider,
 *   e.g. "ESRGAN model: 12.4 MB · ready" or "TFLite model: not downloaded —
 *   using Lanczos fallback"
 * @param upscaleModelState live ESRGAN model-install state from the
 *   ViewModel's `aiTools["upscale"]`. When non-null the panel renders
 *   the model block below: Download (not installed), real download
 *   progress (downloading/verifying/installing), and the ready line.
 * @param upscaleModelReady true when the verified ESRGAN model is on
 *   disk. Gates the Generate button (see [AiToolUiPolicy]).
 * @param upscaleModelStatusText one-line status, e.g.
 *   "Model: ESRGAN · ready (offline)".
 * @param onDownloadModelClicked fires when the user taps Download;
 *   the ViewModel runs the real ESRGAN install pipeline.
 * @param upscaleProgress live ESRGAN run state. Running shows real
 *   progress + Cancel; Succeeded/Failed show the result + Dismiss.
 * @param onGenerateClicked fires when the user taps Generate; the
 *   ViewModel starts the real ESRGAN pipeline (Fast / High Quality
 *   / Auto). Suppressed unless the model is READY.
 * @param onCancelUpscaleRun cancels the in-flight ESRGAN run.
 * @param onDismissUpscaleResult clears a terminal ESRGAN result.
 * @param effectiveProfileLabel e.g. "Auto (Fast Mode)" — shown above
 *   Generate so the user knows which pipeline will run.
 */
@Composable
fun UpscalerPanel(
    settings: UpscalerSettings,
    onPresetSelected: (UpscalerPreset) -> Unit,
    onStrengthChanged: (Float) -> Unit,
    onReset: () -> Unit,
    onExportClicked: () -> Unit = {},
    isProcessing: Boolean = false,
    modelStatus: String? = null,
    upscaleModelState: AiToolState? = null,
    upscaleModelReady: Boolean = false,
    upscaleModelStatusText: String = "",
    onDownloadModelClicked: () -> Unit = {},
    upscaleProgress: UpscaleProgress? = null,
    onGenerateClicked: () -> Unit = {},
    onCancelUpscaleRun: () -> Unit = {},
    onDismissUpscaleResult: () -> Unit = {},
    effectiveProfileLabel: String? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // ---- Header ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
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
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "AI ENHANCE",
                style = MaterialTheme.typography.labelSmall,
                color = iOSBlueBright,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Reset pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                    .clickable(enabled = !isProcessing) { onReset() }
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
        Spacer(Modifier.height(10.dp))

        // ---- Preset cards ----
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(UpscalerPreset.entries.filter { it != UpscalerPreset.OFF }) { preset ->
                PresetCard(
                    preset = preset,
                    isSelected = settings.preset == preset,
                    enabled = !isProcessing,
                    onClick = { onPresetSelected(preset) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

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
                    text = "Strength",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(settings.strength * 100).toInt()}%",
                    color = if (settings.preset != UpscalerPreset.OFF && settings.strength > 0f)
                        iOSBlueBright else LabelSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            ZeroSnapSlider(
                value = settings.strength,
                onValueChange = onStrengthChanged,
                valueRange = 0f..1f,
                enabled = !isProcessing && settings.preset != UpscalerPreset.OFF
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- Description / status (collapsible) ----
        AnimatedVisibility(
            visible = settings.preset != UpscalerPreset.OFF,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                iOSBlue.copy(alpha = 0.10f),
                                iOSPurple.copy(alpha = 0.06f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        iOSBlue.copy(alpha = 0.35f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = settings.preset.displayName,
                    color = iOSBlueBright,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = settings.preset.description,
                    color = LabelPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (settings.preset == UpscalerPreset.ULTRA_4K) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Exports true 4K (2× upscale) via Lanczos resampling. " +
                            "A real ESRGAN TFLite model can be dropped into " +
                            "assets/upscaler.tflite to replace the fallback.",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (settings.preset == UpscalerPreset.FACE_CLARITY_BOOST) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Tuned for facial detail: stronger mid-tone sharpening " +
                            "and a small contrast lift to recover soft features.",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (modelStatus != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = modelStatus,
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        if (isProcessing) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Exporting… tap the cancel button in the progress dialog.",
                color = iOSGreen,
                style = MaterialTheme.typography.labelSmall
            )
        }

        // ---- AI model (ESRGAN) block: Download / progress / Generate / Cancel ----
        if (upscaleModelState != null) {
            Spacer(Modifier.height(12.dp))
            val modelStatusValue = upscaleModelState.status
            val isDownloading = AiToolUiPolicy.isDownloading(modelStatusValue)
            val isRunning = upscaleProgress is UpscaleProgress.Running

            // Status line: real install progress or the ready/not-installed line.
            if (isDownloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = iOSBlueBright,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = upscaleModelState.message ?: "Downloading model",
                            color = LabelPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(upscaleModelState.progress * 100).toInt()}%",
                            color = iOSBlueBright,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.06f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(upscaleModelState.progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(iOSBlue, iOSBlueBright, iOSPurple)
                                    )
                                )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else if (upscaleModelStatusText.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (upscaleModelReady) iOSGreen.copy(alpha = 0.08f)
                            else iOSBlue.copy(alpha = 0.10f)
                        )
                        .border(
                            1.dp,
                            if (upscaleModelReady) iOSGreen.copy(alpha = 0.4f)
                            else iOSBlue.copy(alpha = 0.5f),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = upscaleModelStatusText,
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Download button when the model is not installed.
            // 2026-09-06: the ESRGAN model ships inside the APK
            // (5 MB, sha256 verified) so the install runs offline;
            // when the bundled asset is missing the same button
            // downloads the model in-app.
            if (AiToolUiPolicy.showDownload(
                    modelStatusValue, upscaleModelReady
                ) &&
                upscaleProgress !is UpscaleProgress.Running
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(iOSBlue.copy(alpha = 0.18f))
                        .border(1.dp, iOSBlue.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
                        .clickable { onDownloadModelClicked() }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Download AI Model (~5 MB · ESRGAN)",
                        color = iOSBlueBright,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Run progress + Cancel while processing.
            if (isRunning) {
                val running = upscaleProgress as UpscaleProgress.Running
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = iOSBlueBright,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = running.stage,
                            color = LabelPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(running.percent * 100).toInt()}%",
                            color = iOSBlueBright,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.06f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(running.percent.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(iOSBlue, iOSBlueBright, iOSPurple)
                                    )
                                )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                        .clickable { onCancelUpscaleRun() }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Cancel",
                        color = iOSRed,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // Terminal result (success / failure) + Dismiss.
            when (upscaleProgress) {
                is UpscaleProgress.Succeeded -> {
                    val done = upscaleProgress as UpscaleProgress.Succeeded
                    UpscaleResultRow(
                        success = true,
                        message = "Saved as ${done.fileName}",
                        onDismiss = onDismissUpscaleResult
                    )
                    Spacer(Modifier.height(8.dp))
                }
                is UpscaleProgress.Failed -> {
                    val failed = upscaleProgress as UpscaleProgress.Failed
                    UpscaleResultRow(
                        success = false,
                        message = failed.message,
                        onDismiss = onDismissUpscaleResult
                    )
                    Spacer(Modifier.height(8.dp))
                }
                else -> Unit
            }

            // Generate button when the model is READY.
            if (AiToolUiPolicy.upscaleGenerateEnabled(
                    modelStatusValue, upscaleModelReady, upscaleProgress
                )
            ) {
                if (effectiveProfileLabel != null) {
                    Text(
                        text = "Profile: $effectiveProfileLabel",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    iOSBlue,
                                    iOSBlueBright,
                                    iOSPurple.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .clickable { onGenerateClicked() }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Generate Upscale",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ---- Export button ----
        if (settings.preset != UpscalerPreset.OFF && settings.strength > 0f && !isProcessing) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                iOSBlue,
                                iOSBlueBright,
                                iOSPurple.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .clickable(enabled = !isProcessing) { onExportClicked() }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.IosShare,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Export " + when (settings.preset) {
                            UpscalerPreset.ULTRA_4K -> "4K Video"
                            UpscalerPreset.SMART_HD_1080P -> "1080p HD"
                            UpscalerPreset.FACE_CLARITY_BOOST -> "Enhanced Video"
                            UpscalerPreset.OFF -> "Video"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Terminal ESRGAN result row (success / failure) with a Dismiss
 * button that clears the progress state via
 * [onDismissUpscaleResult].
 */
@Composable
private fun UpscaleResultRow(
    success: Boolean,
    message: String,
    onDismiss: () -> Unit
) {
    val accent = if (success) iOSGreen else iOSRed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (success) "انجام شد" else "ناموفق",
                color = accent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accent.copy(alpha = 0.18f))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(percent = 50))
                    .clickable(onClick = onDismiss)
                    // 2026-10-16 (Phase 2) — 6dp vertical padding made this
                    // ~30dp tall; this is the only way to clear the
                    // upscale result banner, so it gets a comfortable body.
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "بستن",
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            color = LabelPrimary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Single squircle card for a preset (Smart HD / Ultra 4K / Face & Clarity).
 * Selected state is filled with an iOSBlue/iOSPurple gradient + hairline
 * highlight; unselected state is a flat dark glass.
 */
@Composable
private fun PresetCard(
    preset: UpscalerPreset,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (preset) {
        UpscalerPreset.SMART_HD_1080P -> Icons.Filled.Hd
        UpscalerPreset.ULTRA_4K -> Icons.Filled.HighQuality
        UpscalerPreset.FACE_CLARITY_BOOST -> Icons.Filled.FaceRetouchingNatural
        UpscalerPreset.OFF -> Icons.Filled.AutoAwesome
    }
    val borderColor = if (isSelected) iOSBlueBright else HairlineSubtle
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val background = if (isSelected) {
        Brush.linearGradient(
            listOf(
                iOSBlue.copy(alpha = 0.35f),
                iOSPurple.copy(alpha = 0.25f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.02f)
            )
        )
    }
    val titleColor = if (isSelected) iOSBlueBright else LabelPrimary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) iOSBlue.copy(alpha = 0.40f)
                    else Color.White.copy(alpha = 0.06f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) iOSBlueBright else LabelPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = preset.displayName,
            color = titleColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        // Single-line, very short subtitle (e.g. "1080p · Fast", "2× · Detail", "Faces").
        Text(
            text = when (preset) {
                UpscalerPreset.SMART_HD_1080P -> "1080p · Fast"
                UpscalerPreset.ULTRA_4K -> "2× · Detail"
                UpscalerPreset.FACE_CLARITY_BOOST -> "Faces"
                UpscalerPreset.OFF -> ""
            },
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}
