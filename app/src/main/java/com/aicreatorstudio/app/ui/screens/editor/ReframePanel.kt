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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.ReframeState
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

private data class AspectChoice(val label: String, val w: Float, val h: Float)

private val ASPECT_CHOICES = listOf(
    AspectChoice("9:16", 9f, 16f),
    AspectChoice("1:1", 1f, 1f),
    AspectChoice("16:9", 16f, 9f)
)

/**
 * PHASE 5 — Local Smart Reframe panel.
 *
 * Aspect → Analyze (existing on-device pose/face/saliency engine,
 * then keyframe simplification) → stats + crop-path strip Preview
 * (timeline untouched) → Apply (one history entry) / Cancel.
 * The animated crop itself is baked by the existing export
 * pre-render; preview here shows WHERE the crop will travel.
 */
@Composable
fun ReframePanel(
    state: ReframeState,
    selectedClip: VideoClip?,
    committedEnabled: Boolean,
    committedKeyframes: Int,
    onAnalyze: (Float, Float) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onClearCommitted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var aspect by remember { mutableStateOf(ASPECT_CHOICES[0]) }

    val isProcessing = state is ReframeState.Processing
    val isPreview = state is ReframeState.Preview
    val progress by animateFloatAsState(
        targetValue = if (isProcessing) (state as ReframeState.Processing).progress else 0f,
        label = "reframe-progress"
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
                    imageVector = Icons.Filled.CropFree,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ری‌فریم هوشمند",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "دنبال‌کردن سوژه + کراپ خودکار · آفلاین",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (committedEnabled && !isProcessing && !isPreview) {
                OutlinedButton(
                    onClick = onClearCommitted,
                    shape = iOSShapes.capsule
                ) {
                    Text("حذف ری‌فریم")
                }
            }
        }

        if (selectedClip == null || selectedClip.isAudioOnly) {
            Text(
                text = "برای ری‌فریم هوشمند، یک کلیپ ویدیویی را انتخاب کنید.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            return@Column
        }

        // Aspect selector (settings phase only).
        if (!isProcessing && !isPreview) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "نسبت تصویر مقصد",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ASPECT_CHOICES.forEach { choice ->
                        OutlinedButton(
                            onClick = { aspect = choice },
                            shape = iOSShapes.capsule,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (aspect == choice) iOSBlue.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                        ) {
                            Text(choice.label)
                        }
                    }
                }
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
                        text = (state as ReframeState.Processing).message,
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Preview: stats + crop-path strip (timeline untouched).
        if (state is ReframeState.Preview) {
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
                        text = "مسیر کراپ آماده · ${state.targetLabel}",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "سوژه پیدا شد: ${state.stats.subjectFoundPct}٪ · ${state.stats.keyframes} نقطه کلیدی · " +
                        "${"%.1f".format(state.stats.durationMs / 1000.0)} ثانیه\n${state.stats.trackingSummary}",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                CropPathStrip(
                    keys = state.keys,
                    targetAspect = state.targetAspectWidth / state.targetAspectHeight.coerceAtLeast(0.01f)
                )
            }
        }

        // Result.
        if (state is ReframeState.Completed) {
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
                    text = "ری‌فریم «${state.fileName}» اعمال شد (${state.keyframes} نقطه کلیدی)",
                    color = Color(0xFF30D158),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Error.
        if (state is ReframeState.Error) {
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
                        onClick = { onAnalyze(aspect.w, aspect.h) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = iOSBlueBright),
                        shape = iOSShapes.capsule
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CropFree,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("تحلیل ری‌فریم")
                    }
                }
            }
            if (state is ReframeState.Completed || state is ReframeState.Error) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    shape = iOSShapes.capsule
                ) {
                    Text("بستن")
                }
            }
        }

        if (committedEnabled && state !is ReframeState.Preview) {
            Text(
                text = "این کلیپ مسیر کراپ دارد ($committedKeyframes نقطه) و در خروجی ری‌فریم می‌شود.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Miniature crop-travel map: source frame outline with the crop
 * center trail. Shows WHERE the crop will travel without a second
 * video renderer.
 */
@Composable
private fun CropPathStrip(keys: List<CropKeyframe>, targetAspect: Float) {
    if (keys.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "مسیر حرکت کراپ",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.35f))
        ) {
            val w = size.width
            val h = size.height
            // Source outline.
            drawRect(color = Color.Gray.copy(alpha = 0.5f), style = Stroke(width = 2f))
            // Trail.
            val pts = keys.map { k ->
                Offset(k.centerX.coerceIn(0f, 1f) * w, k.centerY.coerceIn(0f, 1f) * h)
            }
            for (i in 1 until pts.size) {
                drawLine(
                    color = Color(0xFF30B0FF),
                    start = pts[i - 1],
                    end = pts[i],
                    strokeWidth = 3f
                )
            }
            // Start (green) / end (amber) markers + a target-aspect box
            // at the path midpoint for scale intuition.
            pts.firstOrNull()?.let { drawCircle(Color(0xFF30D158), 7f, it) }
            pts.lastOrNull()?.let { drawCircle(Color(0xFFFFB340), 7f, it) }
            val mid = pts[pts.size / 2]
            val boxH = h * 0.55f
            val boxW = (boxH * targetAspect.coerceIn(0.2f, 5f)).coerceAtMost(w * 0.95f)
            drawRect(
                color = Color.White.copy(alpha = 0.65f),
                topLeft = Offset(
                    (mid.x - boxW / 2).coerceIn(0f, (w - boxW).coerceAtLeast(0f)),
                    (mid.y - boxH / 2).coerceIn(0f, (h - boxH).coerceAtLeast(0f))
                ),
                size = androidx.compose.ui.geometry.Size(boxW, boxH),
                style = Stroke(width = 2f)
            )
        }
    }
}
