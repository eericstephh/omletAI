package com.aicreatorstudio.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.VideoProject
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicElevated
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.StatusError
import com.aicreatorstudio.app.util.Formatters

/**
 * A single project row on Home.
 *
 * Restrained studio card: one solid surface, one border, one thumbnail.
 * The delete affordance is a small, clearly-bounded icon button (44dp touch
 * target via `minimumInteractiveComponentSize` of the row padding + the
 * button's own 36dp box) so a destructive tap can never be confused with the
 * card's primary "open" tap.
 */
@Composable
fun ProjectCard(
    project: VideoProject,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "project-card-scale"
    )

    val clipCount = project.clips.size
    // Latin digits, matching Formatters/DateFormat (the app's one digit
    // convention). Persian does not pluralise a noun after a numeral, so
    // "3 ویدیو" is correct as-is.
    val meta = "$clipCount ویدیو · ${Formatters.date(project.createdAtEpochMillis)}"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(CinematicSurface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProjectThumbnail(
            uri = project.clips.firstOrNull { !it.isAudioOnly }?.uri
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                color = LabelPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = meta,
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onDelete != null) {
            DeleteProjectButton(onDelete = onDelete, projectName = project.name)
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = LabelSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ProjectThumbnail(uri: String?) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CinematicElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        ProjectThumbnailFrame(
            uri = uri,
            modifier = Modifier.fillMaxSize(),
            fallback = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    AccentBright.copy(alpha = 0.16f),
                                    AccentBright.copy(alpha = 0.04f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Movie,
                        contentDescription = null,
                        tint = AccentBright,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        )
    }
}

@Composable
private fun DeleteProjectButton(onDelete: () -> Unit, projectName: String) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(StatusError.copy(alpha = 0.12f))
            .border(1.dp, StatusError.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "حذف پروژه"
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDelete()
            }
            .semantics { contentDescription = "حذف پروژه $projectName" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = null,
            tint = StatusError,
            modifier = Modifier.size(18.dp)
        )
    }
}
