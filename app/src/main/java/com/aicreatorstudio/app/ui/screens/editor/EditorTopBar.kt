package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.components.GlassChrome
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.theme.accentBright
import com.aicreatorstudio.app.ui.theme.accentDeep
import com.aicreatorstudio.app.ui.theme.accentPrimary
import com.aicreatorstudio.app.ui.theme.glowBrand
import com.aicreatorstudio.app.ui.theme.AccentDeep
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.ctaBrush

/**
 * Top navigation bar for the editor.
 *
 * Clean dark chrome: back + title on the left, export CTA on the right,
 * undo/redo centered. Minimal glass, no heavy borders.
 */
@Composable
fun EditorTopBar(
    title: String,
    canExport: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBackClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(EditorLayoutSpec.TopBarHeight)
            .padding(horizontal = EditorLayoutSpec.SpaceMd, vertical = 6.dp)
    ) {
        // Frosted floating bar — the single chrome layer of this screen.
        GlassChrome(
            shape = RoundedCornerShape(20.dp),
            material = GlassMaterial.Thin,
            modifier = Modifier.matchParentSize()
        ) {}
        // --- Left group: back + title -----------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp)
        ) {
            SquircleIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBackClick
            )
            Spacer(Modifier.width(EditorLayoutSpec.SpaceSm))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = LabelPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(end = EditorLayoutSpec.SpaceSm)
            )
        }

        // --- Center group: undo / redo ----------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.Center)
        ) {
            SquircleIconButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                enabled = canUndo,
                onClick = onUndo
            )
            Spacer(Modifier.width(6.dp))
            SquircleIconButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                enabled = canRedo,
                onClick = onRedo
            )
        }

        // --- Right group: export CTA -------------------------------
        ExportPillButton(
            enabled = canExport,
            onClick = onExportClick,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/** Circular glass squircle with optional press scale and disabled dim. */
@Composable
private fun SquircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        label = "icon-alpha"
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .semantics { if (!enabled) disabled() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(20.dp)
        )
    }
}

/** High-contrast accent pill for the primary Export CTA. */
@Composable
private fun ExportPillButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        label = "export-alpha"
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(ctaBrush())
            .border(1.dp, accentDeep().copy(alpha = 0.35f), RoundedCornerShape(percent = 50))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "Export video",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .semantics { if (!enabled) disabled() }
            // 44dp minimum touch height (Apple HIG) — padding 12/10 ≈ 44dp.
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.IosShare,
                contentDescription = null,
                tint = Color.White.copy(alpha = alpha),
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Export",
                color = Color.White.copy(alpha = alpha),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
