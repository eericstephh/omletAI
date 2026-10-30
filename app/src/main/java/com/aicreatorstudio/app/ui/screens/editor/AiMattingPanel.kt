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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
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
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.AiToolStatus
import com.aicreatorstudio.app.model.MattingBackgroundMode
import com.aicreatorstudio.app.model.MattingSettings
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.components.ZeroSnapSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSOrange
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.iOSRed

/**
 * Background Remove dock sub-panel.
 *
 * Mirrors the visual style of [UpscalerPanel] / [AudioPanel]:
 *  - dark glass background (Color.White 5% alpha + hairline border)
 *  - RoundedCornerShape(14.dp) for cards
 *  - iOSBlue / iOSBlueBright for accent
 *
 * Header → model status pill → threshold slider → background-picker
 * pill → Run / Cancel / Apply / Reset row.
 *
 * The engine (MediaPipeMattingEngine) produces per-frame alpha
 * masks persisted as a sidecar on disk; the export pipeline
 * composites them with the chosen background image/video.
 *
 * @param engineModelStatusText the live status text from
 *   [com.aicreatorstudio.app.ai.AiToolEngine.modelStatusText].
 *   Drives the state-derived pill block below the header.
 * @param engineIsAvailable mirrors [AiToolEngine.isAvailable]; the
 *   "Download Model" pill is shown when false.
 * @param onEnsureModelReady user tapped the "Download Model" /
 *   "Retry" pill. The ViewModel routes the request through
 *   [com.aicreatorstudio.app.ai.AiToolEngine.ensureModelReady].
 */
@Composable
fun AiMattingPanel(
    selectedClip: VideoClip?,
    settings: MattingSettings,
    aiState: AiToolState,
    engineModelStatusText: String,
    engineIsAvailable: Boolean,
    onRun: (AiToolOptions) -> Unit,
    onCancel: () -> Unit,
    onPickBackground: () -> Unit,
    onBackgroundModeChanged: (MattingBackgroundMode) -> Unit = {},
    onExpansionChanged: (Float) -> Unit = {},
    onFeatherChanged: (Float) -> Unit = {},
    onReverseChanged: (Boolean) -> Unit = {},
    /**
     * 2026-10-30 — fired when a knob drag ends. The two sliders report
     * every frame, so the undo stack needs one boundary per gesture
     * instead of one snapshot per frame (a 30-entry stack empties in
     * under a second of dragging — the reason `setClipTrim` takes a
     * `pushHistory` flag at all).
     */
    onKnobDragFinished: () -> Unit = {},
    onReset: () -> Unit,
    onEnsureModelReady: () -> Unit
) {
    val clip = selectedClip
    var strength by remember(settings) { mutableFloatStateOf(settings.threshold) }
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
                    imageVector = Icons.Filled.AutoFixHigh,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "BACKGROUND REMOVE",
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

        // ---- Model state pill (download / verifying / ready / retry) ----
        ModelStatusPill(
            aiState = aiState,
            engineModelStatusText = engineModelStatusText,
            engineIsAvailable = engineIsAvailable,
            onEnsureModelReady = onEnsureModelReady
        )
        Spacer(Modifier.height(10.dp))

        if (clip == null) {
            EmptyClipHint(text = "برای حذف پس‌زمینه، ابتدا یک کلیپ را انتخاب کنید.")
            return@Column
        }

        // ---- Threshold slider ----
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
                    text = "حساسیت تشخیص",
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
                valueRange = 0.05f..0.6f,
                enabled = aiState.status != AiToolStatus.Processing
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- 2026-10-25 — composition-time mask knobs ----
        //
        // Expansion / feather / reverse persist immediately and are
        // applied by the EXPORT pipeline over the raw sidecar masks
        // (`MaskPostProcess`), so moving them never re-runs the model —
        // the parity property CapCut gets from storing the mask as an
        // algorithm layer. They act wherever the clip already has a
        // mask (settings.enabled).
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
                    text = "گسترش سوژه",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(settings.expansion * 100).toInt()}%",
                    color = iOSBlueBright,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            ZeroSnapSlider(
                value = settings.expansion.coerceIn(-1f, 1f),
                onValueChange = onExpansionChanged,
                onValueChangeFinished = onKnobDragFinished,
                valueRange = -1f..1f,
                enabled = aiState.status != AiToolStatus.Processing
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "نرمی لبه",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(settings.feather.coerceIn(0f, 1f) * 100).toInt()}%",
                    color = iOSBlueBright,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            ZeroSnapSlider(
                value = settings.feather.coerceIn(0f, 1f),
                onValueChange = onFeatherChanged,
                onValueChangeFinished = onKnobDragFinished,
                valueRange = 0f..1f,
                // No zero tick: feather = 0 is a *state*, not the
                // snap target — forcing the thumb to the middle of a
                // 0..1 track would misread as "50%".
                showZeroTick = false,
                enabled = aiState.status != AiToolStatus.Processing
            )
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = aiState.status != AiToolStatus.Processing) {
                        onReverseChanged(!settings.reverse)
                    }
                    .padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (settings.reverse) Icons.Filled.CheckCircle
                    else Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = if (settings.reverse) iOSGreen else LabelSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "معکوس (نگه‌داشتن پس‌زمینه)",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (settings.reverse) {
                        Text(
                            text = "سوژه حذف و پس‌زمینه حفظ می‌شود",
                            color = LabelSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Background mode selector ----
        // 2026-10-27 — «حذف» (TRANSPARENT) now leads the row and is what
        // a fresh run commits by default: the subject stays, the
        // removed area shows the project's canvas fill on export. See
        // `MattingBackgroundMode.TRANSPARENT` / `runDefaultBackgroundMode`.
        Text(
            text = "پس‌زمینه",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BgModeChip(
                label = "حذف",
                selected = settings.backgroundMode == MattingBackgroundMode.TRANSPARENT,
                onClick = { onBackgroundModeChanged(MattingBackgroundMode.TRANSPARENT) }
            )
            BgModeChip(
                label = "اصلی",
                selected = settings.backgroundMode == MattingBackgroundMode.ORIGINAL,
                onClick = { onBackgroundModeChanged(MattingBackgroundMode.ORIGINAL) }
            )
            BgModeChip(
                label = "تار",
                selected = settings.backgroundMode == MattingBackgroundMode.BLUR,
                onClick = { onBackgroundModeChanged(MattingBackgroundMode.BLUR) }
            )
            BgModeChip(
                label = "رنگ",
                selected = settings.backgroundMode == MattingBackgroundMode.COLOR,
                onClick = { onBackgroundModeChanged(MattingBackgroundMode.COLOR) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BgModeChip(
                label = "عکس",
                selected = settings.backgroundMode == MattingBackgroundMode.IMAGE,
                onClick = { onBackgroundModeChanged(MattingBackgroundMode.IMAGE) }
            )
            BgModeChip(
                label = "ویدیو",
                selected = settings.backgroundMode == MattingBackgroundMode.VIDEO,
                onClick = { onBackgroundModeChanged(MattingBackgroundMode.VIDEO) }
            )
        }
        if (settings.backgroundMode == MattingBackgroundMode.TRANSPARENT) {
            Spacer(Modifier.height(6.dp))
            Text(
                // 2026-10-30 — the preview composites the cutout now, so
                // the old blanket "the preview does not show this" would
                // be a lie. What is still true: only the solid-fill modes
                // are previewed, and the file is authoritative.
                text = "پس‌زمینه حذف می‌شود؛ سوژه روی رنگ بوم پروژه می‌ماند. " +
                    "پیش‌نمایش همین را نشان می‌دهد و خروجی مرجع نهایی است.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- Background picker pill (only for IMAGE / VIDEO) ----
        if (settings.backgroundMode == MattingBackgroundMode.IMAGE ||
            settings.backgroundMode == MattingBackgroundMode.VIDEO
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                .clickable(enabled = aiState.status != AiToolStatus.Processing) {
                    onPickBackground()
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = LabelSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "پس‌زمینه جایگزین",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = settings.replacementBackgroundUri?.let { "انتخاب شد" }
                        ?: "انتخاب نشده",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        }

        // ---- Status + progress ----
        AiToolProgress(aiState)
        Spacer(Modifier.height(12.dp))

        // ---- Run / Cancel button ----
        // Generate is enabled only when the model is READY
        // (see AiToolUiPolicy): not installed → Download,
        // downloading/verifying → progress, processing → Cancel.
        AiToolRunButton(
            label = "حذف پس‌زمینه",
            isProcessing = aiState.status == AiToolStatus.Processing,
            enabled = com.aicreatorstudio.app.ai.AiToolUiPolicy.generateEnabled(
                aiState.status, engineIsAvailable
            ),
            onRun = {
                onRun(
                    AiToolOptions(
                        strength = strength,
                        replacementBackgroundUri = settings.replacementBackgroundUri,
                        commitToClip = true
                    )
                )
            },
            onCancel = onCancel
        )

        // 2026-10-19 — expectation setting. Originally the live preview
        // could not sample the player's surface at all, so a finished run
        // only added a "ready" chip and the cutout appeared in the file
        // only. Without this line the tool read as "nothing happened".
        //
        // 2026-10-30 — the preview composites the cutout for the
        // solid-fill modes (see `MattingPreviewOverlay`), so the note is
        // narrowed to what is still export-only instead of repeating the
        // blanket claim.
        Spacer(Modifier.height(8.dp))
        Text(
            text = "حذف/رنگ در پیش‌نمایش دیده می‌شود. پس‌زمینه‌ی تار، عکس و ویدیو " +
                "فقط در خروجی اعمال می‌شوند.",
            color = LabelPrimary.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Renders one of: "Download Model" pill, "Verifying…" progress bar,
 * "Model: … ready" success line, or "Model integrity failed —
 * retry download" warning pill. Mirrors the lifecycle contract the
 * [com.aicreatorstudio.app.ai.ModelManager] exposes through the
 * ViewModel's [com.aicreatorstudio.app.ai.AiToolState].
 */
@Composable
private fun ModelStatusPill(
    aiState: AiToolState,
    engineModelStatusText: String,
    engineIsAvailable: Boolean,
    onEnsureModelReady: () -> Unit
) {
    // While a download is in flight the status text alone isn't
    // enough - the user wants a visible progress bar.
    val isDownloading = aiState.status == AiToolStatus.Downloading ||
        aiState.status == AiToolStatus.Verifying ||
        aiState.status == AiToolStatus.Installing
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
                    text = aiState.message ?: "در حال دانلود مدل",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(aiState.progress * 100).toInt()}%",
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
                        .fillMaxWidth(aiState.progress.coerceIn(0f, 1f))
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
        return
    }
    // Integrity failure: a red pill that lets the user retry.
    val integrityFailed = engineModelStatusText.contains("integrity", ignoreCase = true)
    if (integrityFailed) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(iOSRed.copy(alpha = 0.10f))
                .border(1.dp, iOSRed.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable { onEnsureModelReady() }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = iOSRed,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "بررسی صحت مدل ناموفق بود — برای تلاش دوباره بزنید",
                color = iOSRed,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        return
    }
    if (!engineIsAvailable) {
        // 2026-09-06: the model is not installed. The pill offers
        // the in-app install: the installer runs offline from the
        // bundled asset when the APK carries it and falls back to
        // a real download when it doesn't.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(iOSBlue.copy(alpha = 0.10f))
                .border(1.dp, iOSBlue.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable { onEnsureModelReady() }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AutoFixHigh,
                contentDescription = null,
                tint = iOSBlueBright,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "دانلود مدل",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "~16 MB · MediaPipe Selfie Multiclass",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        return
    }
    // Model is ready - subtle inline line.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(iOSGreen.copy(alpha = 0.08f))
            .border(1.dp, iOSGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = iOSGreen,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = engineModelStatusText,
            color = LabelPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---- Shared helpers used by all three AI sub-panels ----

@Composable
internal fun AiToolProgress(state: AiToolState) {
    // 2026-10-20 — the Cancelled state used to return early, which
    // rendered NOTHING. `cancelAiTool` sets the status to Cancelled
    // AND a Persian message, but the message was never displayed: the
    // card simply vanished and the button flipped back to "Generate".
    // A user who cancelled a 10-minute matting pass got zero feedback.
    // The cancelled state is now rendered like any other terminal
    // state ("لغو شد" + the message).
    val color = when (state.status) {
        AiToolStatus.Failed -> iOSRed
        AiToolStatus.Completed -> iOSGreen
        AiToolStatus.Cancelled -> iOSOrange
        AiToolStatus.Idle, AiToolStatus.Ready -> iOSBlue
        else -> iOSBlueBright
    }
    val icon = when (state.status) {
        AiToolStatus.Failed -> Icons.Filled.Error
        AiToolStatus.Completed -> Icons.Filled.CheckCircle
        AiToolStatus.Cancelled -> Icons.Filled.Cancel
        else -> Icons.Filled.AutoFixHigh
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when (state.status) {
                    AiToolStatus.Failed -> iOSRed.copy(alpha = 0.08f)
                    AiToolStatus.Completed -> iOSGreen.copy(alpha = 0.08f)
                    AiToolStatus.Cancelled -> iOSOrange.copy(alpha = 0.08f)
                    AiToolStatus.Idle, AiToolStatus.Ready -> iOSBlue.copy(alpha = 0.06f)
                    else -> Color.White.copy(alpha = 0.04f)
                }
            )
            .border(
                1.dp,
                when (state.status) {
                    AiToolStatus.Failed -> iOSRed.copy(alpha = 0.4f)
                    AiToolStatus.Completed -> iOSGreen.copy(alpha = 0.4f)
                    AiToolStatus.Cancelled -> iOSOrange.copy(alpha = 0.4f)
                    AiToolStatus.Idle, AiToolStatus.Ready -> iOSBlue.copy(alpha = 0.3f)
                    else -> HairlineSubtle
                },
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.status.label(),
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        if (state.status == AiToolStatus.Processing) {
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
                        .fillMaxWidth(state.progress.coerceIn(0f, 1f))
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
        state.message?.takeIf { it.isNotBlank() }?.let { msg ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = msg,
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
internal fun AiToolRunButton(
    label: String,
    isProcessing: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (isProcessing) Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.05f))
                )
                else Brush.horizontalGradient(
                    listOf(iOSBlue, iOSBlueBright, iOSPurple.copy(alpha = 0.85f))
                )
            )
            .clickable(enabled = enabled) {
                if (isProcessing) onCancel() else onRun()
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White,
                    strokeWidth = 1.5.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "لغو",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.AutoFixHigh,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Small selectable chip for the background-mode row
 * (Original / Blur / Color / Image / Video).
 */
@Composable
private fun BgModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) iOSBlueBright else HairlineSubtle
    val bg = if (selected) iOSBlue.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) iOSBlueBright else LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
internal fun EmptyClipHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Text(
            text = text,
            color = LabelSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

internal fun AiToolStatus.label(): String = when (this) {    AiToolStatus.Idle -> "آماده"
    AiToolStatus.Ready -> "آماده"
    AiToolStatus.ModelMissing -> "مدل نصب نیست"
    AiToolStatus.Downloading -> "در حال دانلود"
    AiToolStatus.Verifying -> "در حال بررسی"
    AiToolStatus.Installing -> "در حال نصب"
    AiToolStatus.Processing -> "در حال پردازش"
    AiToolStatus.Completed -> "تکمیل شد"
    AiToolStatus.Cancelled -> "لغو شد"
    AiToolStatus.Failed -> "خطا"
}
