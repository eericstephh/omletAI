package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ai.upscale.UpscaleProfile
import com.aicreatorstudio.app.ai.upscale.UpscaleProgress
import com.aicreatorstudio.app.model.EditorUiState
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
 * Modal bottom sheet shown when the user taps the "AI Upscale" dock
 * button. Mirrors the iOS-style bottom-sheet pattern used elsewhere
 * in the editor (see `EditorSubPanel` for the inline variant).
 *
 * Layout (top-to-bottom):
 *
 *  1. Header — sparkle icon + "AI Upscale" title + a Close button
 *     that calls [onDismiss].
 *  2. Subtitle — one-line description so the user knows what the
 *     button does before picking a profile.
 *  3. Three profile cards:
 *      - ⚡ **Fast Mode (2x)**     — recommended for fast export
 *      - ✨ **High Quality (4x)**   — ultra-sharp AI enhancement
 *      - 🪄 **Auto**                — picks based on device
 *     Each card shows the profile name, the subtitle, an icon, and
 *     a "selected" highlight when the user picks it. The High
 *     Quality card shows a "Requires powerful GPU" warning when
 *     the auto-detection says the device won't handle it well.
 *  4. **Start** button — enabled when a profile is picked and no
 *     run is in flight; disabled with a spinner when one is.
 *  5. **Progress** row (only when [progress] != null) — animated
 *     progress bar + stage label + "≈ Xm Ys remaining" + Cancel
 *     button. Mirrors the three-stage look of [UpscaleProgressDialog]
 *     so the UX is consistent across the two upscale paths.
 *  6. **Result** row — when [progress] is [UpscaleProgress.Succeeded]
 *     a "Done" button lands under the bar; on Failed/Cancelled the
 *     bar collapses and the message is shown.
 *
 * The sheet is fully controlled by its caller — it doesn't own any
 * state. That keeps the ViewModel the single source of truth and
 * makes the sheet trivial to drop into a `BottomSheetScaffold` or
 * a `Dialog` interchangeably.
 *
 * @param state full [EditorUiState] — the sheet reads
 *   `selectedUpscaleProfile`, `upscaleProgress`, and `upscaleEtaMs`.
 *   Optional callers (e.g. a story-style preview) can pass
 *   [EditorUiState] defaults for an inert sheet.
 * @param autoResolved the profile that "Auto" will resolve to on
 *   this device — used to label the High Quality card with a
 *   "May be slow on this device" hint when the device is low-end.
 * @param onProfileSelected forwards [UpscaleProfile] selection to
 *   the ViewModel.
 * @param onStartClicked forwards the Start button tap.
 * @param onCancelClicked forwards the Cancel button tap.
 * @param onDismiss called when the user taps the close (X) button.
 * @param onConsumeResult called when the user dismisses a terminal
 *   progress state (Done / Close).
 */
@Composable
fun UpscaleBottomSheet(
    state: EditorUiState,
    autoResolved: UpscaleProfile,
    onProfileSelected: (UpscaleProfile) -> Unit,
    onStartClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onDismiss: () -> Unit,
    onConsumeResult: () -> Unit
) {
    val selected = state.selectedUpscaleProfile
    val isRunning = state.upscaleProgress is UpscaleProgress.Running
    val isTerminal = state.upscaleProgress is UpscaleProgress.Succeeded ||
        state.upscaleProgress is UpscaleProgress.Failed ||
        state.upscaleProgress is UpscaleProgress.Cancelled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.02f)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
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
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI Upscale",
                    color = iOSBlueBright,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Enhance & upscale your video",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(enabled = !isRunning) { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = LabelSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pick a profile. Fast Mode uses less battery and runs " +
                "smoother on older phones; High Quality gives sharper detail " +
                "but needs a powerful GPU.",
            color = LabelSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(14.dp))

        // ---- Profile cards ----
        ProfileCard(
            profile = UpscaleProfile.Fast,
            isSelected = selected == UpscaleProfile.Fast,
            enabled = !isRunning,
            recommendedBadge = null,
            onClick = { onProfileSelected(UpscaleProfile.Fast) }
        )
        Spacer(Modifier.height(8.dp))
        ProfileCard(
            profile = UpscaleProfile.HighQuality,
            isSelected = selected == UpscaleProfile.HighQuality,
            enabled = !isRunning,
            recommendedBadge = when (autoResolved) {
                UpscaleProfile.HighQuality -> "Recommended for this device"
                else -> "May be slow on this device"
            },
            onClick = { onProfileSelected(UpscaleProfile.HighQuality) }
        )
        Spacer(Modifier.height(8.dp))
        ProfileCard(
            profile = UpscaleProfile.Auto,
            isSelected = selected == UpscaleProfile.Auto,
            enabled = !isRunning,
            recommendedBadge = "Picks ${autoResolved.displayName} for you",
            onClick = { onProfileSelected(UpscaleProfile.Auto) }
        )
        Spacer(Modifier.height(16.dp))

        // ---- Start / Progress / Result ----
        when (val p = state.upscaleProgress) {
            null -> StartButton(
                enabled = selected != null && !isRunning,
                onClick = onStartClicked
            )
            is UpscaleProgress.Running -> ProgressRow(
                progress = p,
                etaMs = state.upscaleEtaMs,
                onCancel = onCancelClicked
            )
            is UpscaleProgress.Succeeded -> ResultRow(
                success = true,
                message = "Saved as ${p.fileName} (${(p.fileSizeBytes / 1024)} KB)",
                onDone = onConsumeResult
            )
            is UpscaleProgress.Failed -> ResultRow(
                success = false,
                message = p.message,
                onDone = onConsumeResult
            )
            UpscaleProgress.Cancelled -> ResultRow(
                success = false,
                message = "Upscale was cancelled.",
                onDone = onConsumeResult
            )
        }
        // Suppress unused warning — `isTerminal` is referenced in
        // the start-button `enabled` predicate via the `null` arm
        // above. If a future refactor changes the "no result" check
        // we still want to know the type.
        @Suppress("UNUSED_EXPRESSION") isTerminal
    }
}

// ---- Profile card ----

/**
 * Single squircle card for one [UpscaleProfile]. Mirrors the
 * `PresetCard` style in `UpscalerPanel` so the two paths share a
 * visual identity.
 */
@Composable
private fun ProfileCard(
    profile: UpscaleProfile,
    isSelected: Boolean,
    enabled: Boolean,
    recommendedBadge: String?,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (profile) {
        UpscaleProfile.Fast -> Icons.Filled.Bolt
        UpscaleProfile.HighQuality -> Icons.Filled.HighQuality
        UpscaleProfile.Auto -> Icons.Filled.PhoneAndroid
    }
    val borderColor = if (isSelected) iOSBlueBright else HairlineSubtle
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val background = if (isSelected) {
        Brush.linearGradient(
            listOf(
                iOSBlue.copy(alpha = 0.30f),
                iOSPurple.copy(alpha = 0.20f)
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) iOSBlue.copy(alpha = 0.45f)
                        else Color.White.copy(alpha = 0.06f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) iOSBlueBright else LabelPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName,
                    color = titleColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = profile.subtitle,
                    color = LabelSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (recommendedBadge != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = null,
                            tint = iOSOrange,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = recommendedBadge,
                            color = iOSOrange,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (isSelected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = iOSBlueBright,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ---- Buttons ----

/**
 * Big primary "Start" button. Disabled (and shows a spinner) while
 * a run is in flight.
 */
@Composable
private fun StartButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (enabled) {
                    Brush.horizontalGradient(
                        listOf(iOSBlue, iOSBlueBright, iOSPurple.copy(alpha = 0.85f))
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    )
                }
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = if (enabled) Color.White else LabelSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Start Upscale",
                color = if (enabled) Color.White else LabelSecondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Live progress row: animated progress bar + stage label + ETA
 * countdown + Cancel button. Only shown while
 * `state.upscaleProgress` is [UpscaleProgress.Running].
 */
@Composable
private fun ProgressRow(
    progress: UpscaleProgress.Running,
    etaMs: Long,
    onCancel: () -> Unit
) {
    val target by animateFloatAsState(
        targetValue = progress.percent.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250),
        label = "upscale-sheet-progress"
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = iOSBlueBright,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = progress.stage,
                transitionSpec = {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(220)))
                },
                label = "stage"
            ) { stage ->
                Text(
                    text = stage,
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${(target * 100).toInt()}%",
                color = iOSBlueBright,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(target)
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val etaText = if (etaMs > 0) {
                val totalSec = etaMs / 1000
                val mins = totalSec / 60
                val secs = totalSec % 60
                when {
                    mins > 0 -> "≈ ${mins}m ${secs}s remaining"
                    else -> "≈ ${secs}s remaining"
                }
            } else "Estimating time…"
            Text(
                text = etaText,
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = null,
                        tint = iOSRed,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Cancel",
                        color = iOSRed,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Terminal-state row (Success / Failure / Cancelled). Shows a
 * status line + a "Done" / "Close" button that calls
 * [onConsumeResult] to clear the progress state.
 */
@Composable
private fun ResultRow(
    success: Boolean,
    message: String,
    onDone: () -> Unit
) {
    val accent = if (success) iOSGreen else iOSRed
    AnimatedVisibility(visible = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (success) Icons.Filled.CheckCircle
                                  else Icons.Filled.Close,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (success) "Done" else "Failed",
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
                        .clickable(onClick = onDone)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Close",
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
}
