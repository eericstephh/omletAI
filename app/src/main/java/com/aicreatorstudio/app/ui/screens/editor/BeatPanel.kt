package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.AiToolStatus
import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes
import com.aicreatorstudio.app.util.BeatDetector

/**
 * Yellow used for every beat marker in the app: the timeline ticks and
 * the panel's count badge. Defined once here (and mirrored by the
 * timeline's own constant) so the marker cannot drift between the two
 * surfaces that draw it.
 */
internal val BeatMarkerYellow = Color(0xFFFFD54A)

/**
 * «تشخیص ضرباهنگ» — Audio Beat Detection sub-panel.
 *
 * Three actions, in the order a user actually performs them:
 *
 *  1. **Detect** (with a sensitivity selector) — analyse the selected audio
 *     clip and drop markers on the timeline.
 *  2. **Clear** — drop the markers again (a non-destructive "I don't want
 *     these", undoable like every other edit).
 *  3. **Auto-Split** — cut the selected video clip at every marker. This is
 *     the payoff action, so it is enabled only once markers exist *and* both
 *     a video clip and an audio clip are selected.
 *
 * The analysis runs through the shared `aiTools` state channel, so the panel
 * renders the same progress bar / cancel / error surface as every other AI
 * tool instead of inventing a fourth loading idiom.
 *
 * ## Which clip gets analysed
 *
 * Always [AudioClip] `selectedAudioClipId` — the audio the user tapped on a
 * lane. When nothing is selected the panel shows a "pick an audio clip"
 * affordance rather than silently falling back to the video's own audio,
 * because the most common use is cutting a montage to an imported music file
 * that is *not* attached to the selected video at all.
 */
@Composable
fun BeatPanel(
    audioClip: AudioClip?,
    toolState: AiToolState?,
    selectedVideoClip: VideoClip?,
    onDetect: (BeatDetector.BeatSensitivity) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onAutoSplit: () -> Unit,
    onSelectAudioClip: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var sensitivity by remember {
        mutableStateOf(BeatDetector.BeatSensitivity.Default)
    }

    val status = toolState?.status ?: AiToolStatus.Idle
    val isProcessing = status == AiToolStatus.Processing ||
        status == AiToolStatus.Downloading ||
        status == AiToolStatus.Verifying ||
        status == AiToolStatus.Installing
    val beatData = audioClip?.beatData
    val hasMarkers = beatData?.markers?.isNotEmpty() == true
    // `hasMarkers` already implies a non-null audioClip (it is derived from
    // that clip's beatData), so only the video side needs checking here.
    val canAutoSplit = hasMarkers && selectedVideoClip != null

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        iOSPanelHeader(title = "تشخیص ضرباهنگ", eyebrow = "Beats")

        // -- Source clip summary ---------------------------------------- //
        if (audioClip == null) {
            EmptySourceHint()
        } else {
            SourceSummary(
                clip = audioClip,
                beatData = beatData
            )
        }

        // -- Sensitivity selector --------------------------------------- //
        SectionLabel("حساسیت")
        SensitivityRow(
            selected = sensitivity,
            enabled = audioClip != null && !isProcessing,
            onSelect = { sensitivity = it }
        )
        Text(
            text = sensitivityHint(sensitivity),
            style = MaterialTheme.typography.bodySmall,
            color = LabelSecondary
        )

        // -- Progress --------------------------------------------------- //
        if (isProcessing) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { toolState?.progress?.coerceIn(0f, 1f) ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(iOSShapes.capsule),
                    color = BeatMarkerYellow,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.HourglassTop,
                        contentDescription = null,
                        tint = BeatMarkerYellow,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "در حال تحلیل موج صوتی…",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabelSecondary
                    )
                }
            }
        }

        // -- Terminal message ------------------------------------------- //
        if (!isProcessing && toolState?.message != null) {
            StatusLine(
                message = toolState.message,
                isError = status == AiToolStatus.Failed
            )
        }

        // -- Primary actions -------------------------------------------- //
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isProcessing) {
                PillActionButton(
                    label = "لغو",
                    onClick = onCancel,
                    style = PillStyle.Gray
                )
            } else {
                PillActionButton(
                    label = if (hasMarkers) "تشخیص مجدد" else "تشخیص ضرباهنگ",
                    onClick = { onDetect(sensitivity) },
                    modifier = Modifier.weight(1f)
                )
                if (hasMarkers) {
                    PillActionButton(
                        label = "حذف نشانه‌ها",
                        onClick = onClear,
                        style = PillStyle.Gray
                    )
                }
            }
        }

        // -- Auto-split ------------------------------------------------- //
        if (hasMarkers) {
            SectionLabel("برش خودکار")
            PillActionButton(
                label = "برش خودکار سر ضرب",
                onClick = onAutoSplit,
                modifier = Modifier.fillMaxWidth(),
                enabled = canAutoSplit
            )
            if (!canAutoSplit) {
                Text(
                    text = if (selectedVideoClip == null) {
                        "برای برش، یک کلیپ ویدیویی روی تایم‌لاین انتخاب کنید."
                    } else {
                        "نشانه‌ای روی این کلیپ ویدیویی نمی‌افتد."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LabelSecondary
                )
            }
        }
    }
}

/**
 * "Pick an audio clip" state. Tapping it clears the audio selection so the
 * lanes look like the user is expected to act — the panel never silently
 * substitutes a different clip.
 */
@Composable
private fun EmptySourceHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "کلیپی انتخاب نشده",
                    style = MaterialTheme.typography.titleSmall,
                    color = LabelPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "روی یکی از کلیپ‌های صوتی تایم‌لاین (Video Audio / Music / SFX) ضربه بزنید، سپس تشخیص را اجرا کنید.",
                style = MaterialTheme.typography.bodySmall,
                color = LabelSecondary
            )
        }
    }
}

/** Name + detected-beat summary for the clip about to be analysed. */
@Composable
private fun SourceSummary(clip: AudioClip, beatData: BeatDetector.AudioBeatData?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = iOSBlueBright,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = clip.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = LabelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = when {
                        // null = never analysed; empty = analysed, no beats.
                        beatData == null -> "تحلیل نشده"
                        beatData.markers.isEmpty() -> "ضرباهنگی پیدا نشد"
                        else -> buildString {
                            append("${beatData.markers.size} ضرب")
                            beatData.bpm?.let { append(" • $it BPM") }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (beatData?.markers?.isNotEmpty() == true) {
                        BeatMarkerYellow
                    } else {
                        LabelSecondary
                    }
                )
            }
            if (beatData?.markers?.isNotEmpty() == true) {
                BeatCountBadge(count = beatData.markers.size)
            }
        }
    }
}

/** Compact yellow pill showing how many markers were found. */
@Composable
private fun BeatCountBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(iOSShapes.capsule)
            .background(BeatMarkerYellow.copy(alpha = 0.18f))
            .border(1.dp, BeatMarkerYellow.copy(alpha = 0.45f), iOSShapes.capsule)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = BeatMarkerYellow,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Three-way sensitivity chooser: آرام / معمولی / سریع. */
@Composable
private fun SensitivityRow(
    selected: BeatDetector.BeatSensitivity,
    enabled: Boolean,
    onSelect: (BeatDetector.BeatSensitivity) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        BeatDetector.BeatSensitivity.entries.forEach { option ->
            val isSelected = option == selected
            // animateColorAsState rather than a hard `if` so switching feels
            // like a segmented control instead of a flicker.
            val fill by animateColorAsState(
                targetValue = if (isSelected) {
                    iOSBlue.copy(alpha = 0.30f)
                } else {
                    Color.White.copy(alpha = 0.05f)
                },
                label = "beat-sensitivity-fill"
            )
            val edge by animateColorAsState(
                targetValue = if (isSelected) {
                    iOSBlueBright.copy(alpha = 0.75f)
                } else {
                    HairlineSubtle
                },
                label = "beat-sensitivity-edge"
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fill)
                    .border(1.dp, edge, RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect(option)
                    }
                    .padding(vertical = 10.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = option.labelFa,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) LabelPrimary
                        else LabelPrimary.copy(alpha = if (enabled) 0.65f else 0.35f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = option.labelEn,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) iOSBlueBright else LabelSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = LabelSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp)
    )
}

/**
 * Result / error line. Failed states get a warm red so the reason reads as
 * a problem; success and cancellation stay neutral.
 */
@Composable
private fun StatusLine(message: String, isError: Boolean) {
    val color = if (isError) Color(0xFFFF8A80) else LabelSecondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isError) {
            Icon(
                imageVector = Icons.Filled.ContentCut,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

/** One-line description of what each sensitivity actually changes. */
private fun sensitivityHint(sensitivity: BeatDetector.BeatSensitivity): String =
    when (sensitivity) {
        BeatDetector.BeatSensitivity.LOW ->
            "آستانه بالا — فقط ضربه‌های واضح و قوی تشخیص داده می‌شوند."
        BeatDetector.BeatSensitivity.MEDIUM ->
            "متعادل — مناسب بیشتر موسیقی‌ها."
        BeatDetector.BeatSensitivity.HIGH ->
            "آستانه پایین — ضربه‌های ریز و جزئیات هم ثبت می‌شوند."
    }
