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
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.AiToolStatus
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.components.ZeroSnapSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSOrange
import com.aicreatorstudio.app.ui.theme.iOSPurple

/**
 * Motion Tracking dock sub-panel.
 *
 * ## What this feature is, in one line
 *
 * Auto Frame moves the **canvas** to follow a subject; Motion Tracking
 * moves an **overlay** to follow whatever box the user drew. That
 * distinction is why the two panels look similar but share no state:
 * a clip can only sensibly have one Auto Frame trajectory, but it can
 * carry a Motion Tracking trajectory alongside its own crop.
 *
 * ## The three-step user flow this panel drives
 *
 *  1. **انتخاب هدف** — press the selection button, the editor goes to
 *     the current playhead frame and overlays [RoiSelectorOverlay];
 *     the user drags a box around the target. The box is stored
 *     normalized, so it survives any later resolution change.
 *  2. **Tune** — pick the overlay to attach (text / sticker), whether
 *     to scale with the box, and the smoothing.
 *  3. **Generate** — the engine walks the clip through
 *     `VideoFramePipeline`, runs the appearance tracker per frame, and
 *     commits a `List<CropKeyframe>` marked `isFound`.
 *
 * ## Why the export button carries the warning
 *
 * A tracked **blur / mosaic** cannot be previewed: the video
 * composites through a `TextureView`, outside the View raster, so a
 * View-level effect acts on a transparent hole (bug class 2 in
 * `MEMORY.md`). Blur and mosaic are therefore export-only, and the
 * panel says so, exactly as `sharpness` / `vignette` / `grain` do.
 */
@Composable
fun MotionTrackPanel(
    selectedClip: VideoClip?,
    settings: MotionTrackSettings,
    keyframes: List<CropKeyframe>,
    overlays: List<TextOverlay>,
    aiState: AiToolState,
    pendingRoi: android.graphics.RectF?,
    backendStatusText: String,
    onRoiChanged: (android.graphics.RectF) -> Unit,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onRun: (AiToolOptions) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit
) {
    val clip = selectedClip
    var positionSmoothing by remember(settings) {
        mutableFloatStateOf(settings.positionSmoothing)
    }
    var scaleWithBox by remember(settings) { mutableStateOf(settings.scaleWithBox) }
    var maskMode by remember(settings) { mutableStateOf(settings.maskMode) }
    var overlayId by remember(settings) { mutableStateOf(settings.overlayId) }

    val running = aiState.status == AiToolStatus.Processing
    val hasRoi = pendingRoi != null
    val trackedCount = keyframes.count { it.hasExtent }

    Column(modifier = Modifier.fillMaxWidth()) {

        // ---- Header ----------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                iOSOrange.copy(alpha = 0.6f),
                                iOSPurple.copy(alpha = 0.6f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "MOTION TRACKING",
                style = MaterialTheme.typography.labelSmall,
                color = iOSOrange,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                    .clickable(enabled = clip != null && !running) { onReset() }
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
            text = "موتور ردیابی: قالب‌مبنا + قیچی پیوسته · ۱۰۰٪ آفلاین",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = backendStatusText.ifBlank { "موتور ردیابی: در دسترس" },
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(10.dp))

        if (clip == null) {
            EmptyClipHint(text = "برای ردیابی حرکت، ابتدا یک کلیپ را انتخاب کنید.")
            return@Column
        }

        // ---- Step 1: ROI selection -------------------------------------
        Text(
            text = "۱. انتخاب هدف",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (hasRoi) iOSBlue.copy(alpha = 0.18f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .border(
                        width = if (hasRoi) 1.5.dp else 1.dp,
                        color = if (hasRoi) iOSBlueBright else HairlineSubtle,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable(enabled = !running) {
                        // 2026-10-20 — the two arms of this `if` were
                        // IDENTICAL (`if (hasRoi) onEnterSelection() else
                        // onEnterSelection()`), so the branch said
                        // "drawing a new ROI" and "editing the existing
                        // one" were different actions when they were
                        // the same call. Re-entering selection always
                        // starts a fresh box; the previous ROI is
                        // replaced, not edited in place.
                        onEnterSelection()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CropFree,
                        contentDescription = null,
                        tint = if (hasRoi) iOSBlueBright else LabelPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (hasRoi) "ویرایش کادر هدف" else "کشیدن کادر هدف",
                            color = if (hasRoi) iOSBlueBright else LabelPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (hasRoi) "کادر روی فریم فعلی تنظیم شد"
                            else "روی فریم متوقف‌شده، هدف را قاب بگیرید",
                            color = LabelSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            if (hasRoi) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                        .clickable(enabled = !running) { onExitSelection() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "تأیید",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Step 2: attachment ----------------------------------------
        Text(
            text = "۲. اتصال به هدف",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))

        // Overlay picker. Only text overlays/stickers are offered: they
        // are the elements the export `FrameOp` can stamp at an
        // arbitrary coordinate.
        if (overlays.isEmpty()) {
            Text(
                text = "اورلی یا متن روی کلیپ وجود ندارد. ابتدا یک متن یا استیکر اضافه کنید.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                overlays.take(6).forEach { ov ->
                    val selected = overlayId == ov.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) iOSBlue.copy(alpha = 0.16f)
                                else Color.White.copy(alpha = 0.04f)
                            )
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) iOSBlueBright else HairlineSubtle,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(enabled = !running) { overlayId = ov.id }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = ov.text.take(28).ifBlank { "(استیکر)" },
                            color = if (selected) iOSBlueBright else LabelPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text(
                                text = "متصل",
                                color = iOSBlueBright,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // Scale-with-box toggle.
        ToggleRow(
            label = "تغییر اندازه همراه با کادر",
            sub = "وقتی هدف دور یا نزدیک می‌شود، اورلی هم بزرگ/کوچک شود",
            checked = scaleWithBox,
            enabled = !running
        ) { scaleWithBox = it }
        Spacer(Modifier.height(10.dp))

        // Mask mode (export-only).
        Text(
            text = "پوشاندن هدف (فقط در خروجی)",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MaskChip(
                label = "هیچ",
                selected = maskMode == MotionTrackSettings.MaskMode.NONE,
                enabled = !running
            ) { maskMode = MotionTrackSettings.MaskMode.NONE }
            MaskChip(
                label = "محو",
                selected = maskMode == MotionTrackSettings.MaskMode.BLUR,
                enabled = !running
            ) { maskMode = MotionTrackSettings.MaskMode.BLUR }
            MaskChip(
                label = "پیکسلی",
                selected = maskMode == MotionTrackSettings.MaskMode.MOSAIC,
                enabled = !running
            ) { maskMode = MotionTrackSettings.MaskMode.MOSAIC }
        }
        if (maskMode != MotionTrackSettings.MaskMode.NONE) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.BlurOn,
                    contentDescription = null,
                    tint = LabelSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "محو/پیکسلی فقط در خروجی اعمال می‌شود، نه در پیش‌نمایش.",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Step 3: smoothing + run -----------------------------------
        Text(
            text = "۳. تولید مسیر",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
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
                    text = "نرمی حرکت",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${(positionSmoothing * 100).toInt()}%",
                    color = iOSOrange,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            ZeroSnapSlider(
                value = positionSmoothing,
                onValueChange = { positionSmoothing = it },
                valueRange = 0f..1f,
                enabled = !running
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "کی‌فریم‌های مسیر",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (trackedCount > 0) "$trackedCount نقطه‌ی ردیابی‌شده"
                    else "هنوز مسیری تولید نشده",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        AiToolProgress(aiState)
        Spacer(Modifier.height(12.dp))

        AiToolRunButton(
            label = "ردیابی حرکت",
            isProcessing = running,
            enabled = !running && hasRoi && clip != null,
            onRun = {
                onRun(
                    AiToolOptions(
                        strength = positionSmoothing,
                        commitToClip = true,
                        trackingRoi = pendingRoi,
                        trackingOverlayId = overlayId,
                        trackingMaskMode = maskMode,
                        trackingMaskStrength = settings.maskStrength,
                        trackingAnchorU = settings.anchorU,
                        trackingAnchorV = settings.anchorV,
                        trackingScaleWithBox = scaleWithBox,
                        positionSmoothing = positionSmoothing,
                        scaleSmoothing = settings.scaleSmoothing,
                        minConfidence = settings.minConfidence
                    )
                )
            },
            onCancel = onCancel
        )

        if (!hasRoi) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "برای فعال شدن دکمه‌ی ردیابی، نخست کادر هدف را بکشید.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    sub: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (checked) iOSBlue.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = if (checked) 1.5.dp else 1.dp,
                color = if (checked) iOSBlueBright else HairlineSubtle,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (checked) iOSBlueBright else LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = sub,
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) iOSBlue else Color.White.copy(alpha = 0.08f))
                .border(1.dp, if (checked) iOSBlueBright else HairlineSubtle, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MaskChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) iOSBlue.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) iOSBlueBright else HairlineSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) iOSBlueBright else LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
