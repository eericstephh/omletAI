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
import androidx.compose.material.icons.filled.CenterFocusStrong
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
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.components.ZeroSnapSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSRed
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSPurple

/**
 * Auto Frame dock sub-panel.
 *
 * The engine (SaliencyTrackerEngine) tracks the subject with
 * MediaPipe Pose Landmarker per 250 ms sample (face + torso
 * blend), falls back to Sobel saliency on detection loss, smooths
 * with a 1-Euro filter, and emits 5 fps [CropKeyframe] entries.
 * The export pipeline renders the keyframes into a reframed file
 * at the target aspect (16:9, 9:16, 1:1, 4:5).
 */
@Composable
fun AutoFramePanel(
    selectedClip: VideoClip?,
    settings: AutoFrameSettings,
    keyframes: List<CropKeyframe>,
    aiState: AiToolState,
    onRun: (AiToolOptions) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    modelStatusText: String = "",
    modelAvailable: Boolean = true,
    onEnsureModelReady: () -> Unit = {}
) {
    val clip = selectedClip
    var smoothing by remember(settings) { mutableFloatStateOf(settings.smoothing) }
    var aspectChip by remember(settings) {
        mutableStateOf(
            when {
                settings.targetAspectWidth == 9f && settings.targetAspectHeight == 16f -> ASPECT_9_16
                settings.targetAspectWidth == 1f && settings.targetAspectHeight == 1f -> ASPECT_1_1
                settings.targetAspectWidth == 16f && settings.targetAspectHeight == 9f -> ASPECT_16_9
                settings.targetAspectWidth == 4f && settings.targetAspectHeight == 5f -> ASPECT_4_5
                else -> ASPECT_9_16
            }
        )
    }

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
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "AUTO FRAME",
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
            text = "الگوریتم: ردیابی بدن Pose Landmarker + فیلتر ۱-Euro · آفلاین",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(10.dp))

        if (clip == null) {
            EmptyClipHint(text = "برای قاب‌بندی خودکار، ابتدا یک کلیپ را انتخاب کنید.")
            return@Column
        }

        // ---- Model state ----
        // 2026-09-06: Auto Frame is backed by MediaPipe Pose
        // Landmarker Lite (5.6 MB) which ships inside the APK. The
        // install runs offline from the bundled asset; when the
        // asset is missing the same button downloads the model.
        if (!modelAvailable) {
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
                        text = "این مدل هوش مصنوعی نصب نیست.",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (modelStatusText.isNotBlank()) {
                        Text(
                            text = modelStatusText,
                            color = LabelSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(iOSBlue)
                        .clickable { onEnsureModelReady() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "دانلود",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- Target aspect selector ----
        Text(
            text = "نسبت تصویر مقصد",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AspectChip(label = "9:16", sub = "عمودی", selected = aspectChip == ASPECT_9_16) {
                aspectChip = ASPECT_9_16
            }
            AspectChip(label = "1:1", sub = "مربع", selected = aspectChip == ASPECT_1_1) {
                aspectChip = ASPECT_1_1
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AspectChip(label = "16:9", sub = "افقی", selected = aspectChip == ASPECT_16_9) {
                aspectChip = ASPECT_16_9
            }
            AspectChip(label = "4:5", sub = "پرتره", selected = aspectChip == ASPECT_4_5) {
                aspectChip = ASPECT_4_5
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Smoothing slider ----
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
                    text = "${(smoothing * 100).toInt()}%",
                    color = iOSBlueBright,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(4.dp))
            ZeroSnapSlider(
                value = smoothing,
                onValueChange = { smoothing = it },
                valueRange = 0f..1f,
                enabled = aiState.status != AiToolStatus.Processing
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- Keyframe count pill ----
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
                    text = "کی‌فریم‌های تولید‌شده",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${keyframes.size} نقطه",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        AiToolProgress(aiState)
        Spacer(Modifier.height(12.dp))

        AiToolRunButton(
            label = "قاب‌بندی خودکار",
            isProcessing = aiState.status == AiToolStatus.Processing,
            // Generate is enabled only when the model is READY:
            // not installed → Download; downloading/verifying →
            // progress; processing → Cancel. Centralized in
            // AiToolUiPolicy so unit tests pin the exact
            // predicate the button binds to.
            enabled = com.aicreatorstudio.app.ai.AiToolUiPolicy.generateEnabled(
                aiState.status, modelAvailable
            ),
            onRun = {
                val (w, h) = when (aspectChip) {
                    ASPECT_9_16 -> 9f to 16f
                    ASPECT_1_1 -> 1f to 1f
                    ASPECT_16_9 -> 16f to 9f
                    ASPECT_4_5 -> 4f to 5f
                    else -> 9f to 16f
                }
                onRun(
                    AiToolOptions(
                        strength = smoothing,
                        targetAspectWidth = w,
                        targetAspectHeight = h,
                        commitToClip = true
                    )
                )
            },
            onCancel = onCancel
        )
    }
}

private const val ASPECT_9_16 = 0
private const val ASPECT_1_1 = 1
private const val ASPECT_16_9 = 2
private const val ASPECT_4_5 = 3

@Composable
private fun AspectChip(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val border = if (selected) iOSBlueBright else HairlineSubtle
    val bg = if (selected) iOSBlue.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f)
    Column(
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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = if (selected) iOSBlueBright else LabelPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = sub,
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
