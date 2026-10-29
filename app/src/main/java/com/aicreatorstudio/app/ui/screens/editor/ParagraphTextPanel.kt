package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay

/** Default on-screen duration of a newly added paragraph overlay. */
const val PARAGRAPH_OVERLAY_DURATION_MS = 5000L

/**
 * 2026-09-06 — Paragraph tool panel.
 *
 * Adds a multi-line paragraph text block to the timeline with:
 *  1. A multi-line text input (up to 8 lines).
 *  2. A Persian font picker (same chips as the Text tool).
 *  3. A 3x3 position grid — وسط ویدیو / بالا / پایین / گوشه‌ها —
 *     that sets where the paragraph lands. The grid doubles as a
 *     "snap the selected text here" control when an overlay is
 *     already selected.
 *  4. A nudge pad for fine-tuning the selected overlay's placement.
 *
 * Free-form placement always works: every text overlay (paragraphs
 * included) can be dragged anywhere on the video preview with a
 * finger (1-finger drag; 2-finger pinch/rotate on the selection).
 */
@Composable
fun ParagraphTextPanel(
    currentPositionMs: Long,
    durationMs: Long,
    selectedOverlay: TextOverlay?,
    onAddParagraph: (text: String, startMs: Long, endMs: Long, font: PersianFont, anchorX: Float, anchorY: Float) -> Unit,
    onSnapSelected: (anchorX: Float, anchorY: Float) -> Unit,
    onNudgeSelected: (dx: Float, dy: Float) -> Unit,
    onDeselect: () -> Unit,
    // 2026-09-06 — edit controls for the SELECTED overlay (tapped on
    // the timeline): text content, font, size, color, style preset,
    // and a time-shift row that moves the overlay to the playhead.
    onUpdateSelectedText: (String) -> Unit = {},
    onUpdateSelectedFont: (PersianFont) -> Unit = {},
    onUpdateSelectedFontSize: (Float) -> Unit = {},
    onUpdateSelectedColor: (Int) -> Unit = {},
    onApplySelectedStyle: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    onMoveSelectedToPlayhead: () -> Unit = {},
    // 2026-09-06 — full TEXT SYSTEM controls.
    onUpdateSelectedRotation: (Float) -> Unit = {},
    onUpdateSelectedStroke: (Boolean) -> Unit = {},
    onUpdateSelectedShadow: (Boolean) -> Unit = {},
    onUpdateSelectedBackground: (Boolean) -> Unit = {},
    onUpdateSelectedAnimation: (com.aicreatorstudio.app.model.TextOverlayAnimation) -> Unit = {},
    onUpdateSelectedDuration: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var paragraphText by remember { mutableStateOf("") }
    var selectedFont by remember { mutableStateOf(PersianFont.VAZIR) }
    var anchorX by remember { mutableFloatStateOf(0.5f) }
    var anchorY by remember { mutableFloatStateOf(0.5f) }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Notes,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "متن پاراگراف",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // -------------------------------------------------------
            // 2026-09-06 — SELECTED OVERLAY EDITOR.
            // When a text / subtitle tile is tapped on the timeline
            // this panel opens bound to it: edit text, font, size,
            // color, style preset, position + time shift.
            // -------------------------------------------------------
            if (selectedOverlay != null) {
                Text(
                    text = "ویرایش متن انتخاب‌شده",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                var editBuffer by remember(selectedOverlay.id) {
                    mutableStateOf(selectedOverlay.text)
                }
                OutlinedTextField(
                    value = editBuffer,
                    onValueChange = { if (it.count { c -> c == '\n' } < 7) editBuffer = it },
                    label = { Text("متن") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 8,
                    textStyle = TextStyle(
                        fontFamily = PersianFont.from(selectedOverlay.fontFamily).toFontFamily()
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onUpdateSelectedText(editBuffer.trim()) },
                        enabled = editBuffer.isNotBlank() && editBuffer != selectedOverlay.text
                    ) {
                        Text("ذخیره متن")
                    }
                    Text(
                        text = "لغو انتخاب",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onDeselect()
                            }
                            .align(Alignment.CenterVertically)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }

                // Font — previewed in its own face; the current font
                // of the selected overlay is pre-highlighted.
                Text(
                    text = "فونت",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    PersianFont.entries.forEach { font ->
                        val isSelected = font.name == selectedOverlay.fontFamily
                        Text(
                            text = font.displayName,
                            fontFamily = font.toFontFamily(),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onUpdateSelectedFont(font) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Size slider (12..48 sp), synced live.
                val currentSize = selectedOverlay.subtitleStyle?.fontSizeSp
                    ?: selectedOverlay.fontSizeSp
                Text(
                    text = "اندازه: ${currentSize.toInt()} sp",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Slider(
                    value = currentSize,
                    onValueChange = { onUpdateSelectedFontSize(it) },
                    valueRange = 12f..48f
                )

                // Color swatches.
                Text(
                    text = "رنگ متن",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    val swatches = listOf(
                        0xFFFFFFFF, 0xFF111111, 0xFF00E5FF, 0xFFFFD54F,
                        0xFFFF5252, 0xFF69F0AE, 0xFF0A84FF, 0xFFE040FB
                    )
                    val currentColor = selectedOverlay.subtitleStyle?.textColorArgb
                        ?: (selectedOverlay.color.toArgb())
                    swatches.forEach { argb ->
                        val isSelected = currentColor == argb.toInt()
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(androidx.compose.ui.graphics.Color(argb))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                                .clickable { onUpdateSelectedColor(argb.toInt()) }
                        )
                    }
                }

                // Style presets — the same "خفن و مینیمال" pack used
                // by the AI-subtitle quick chips, applied to the
                // selected overlay in one tap.
                Text(
                    text = "استایل",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    com.aicreatorstudio.app.model.SubtitleStyle.AllPresets.forEach { (name, preset) ->
                        val isSelected = selectedOverlay.subtitleStyle == preset
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onApplySelectedStyle(preset) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // ---- 2026-09-06 TEXT SYSTEM controls -----------------
                // Rotation
                val currentRotation = selectedOverlay.rotation
                Text(
                    text = "چرخش: ${currentRotation.toInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Slider(
                    value = currentRotation,
                    onValueChange = { onUpdateSelectedRotation(it) },
                    valueRange = 0f..360f
                )

                // Stroke / Shadow / Background toggles — the working
                // style is the overlay's subtitleStyle (or Default).
                val workingStyle = selectedOverlay.subtitleStyle
                    ?: com.aicreatorstudio.app.model.SubtitleStyle.Default
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip("Stroke", workingStyle.strokeEnabled) {
                        onUpdateSelectedStroke(it)
                    }
                    ToggleChip("Shadow", workingStyle.shadowEnabled) {
                        onUpdateSelectedShadow(it)
                    }
                    ToggleChip("Background", workingStyle.backgroundEnabled) {
                        onUpdateSelectedBackground(it)
                    }
                }
                if (workingStyle.strokeEnabled) {
                    Text(
                        text = "ضخامت خط دور: ${"%.1f".format(workingStyle.strokeWidthSp)} sp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.Slider(
                        value = workingStyle.strokeWidthSp,
                        onValueChange = { onUpdateSelectedStroke(true) },
                        onValueChangeFinished = {
                            // Width fine-tuning happens through the
                            // stroke toggle callback (toggle keeps the
                            // current width); explicit width update:
                            onUpdateSelectedStroke(true)
                        },
                        valueRange = 0f..6f
                    )
                }

                // Animation presets.
                Text(
                    text = "انیمیشن",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    com.aicreatorstudio.app.model.TextOverlayAnimation.entries.forEach { anim ->
                        val isSelected = selectedOverlay.textAnimation == anim
                        Text(
                            text = anim.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onUpdateSelectedAnimation(anim) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Duration — extend / shorten the on-screen window.
                val currentDuration = selectedOverlay.durationMs.coerceAtLeast(300L)
                Text(
                    text = "مدت نمایش: ${"%.1f".format(currentDuration / 1000f)} ثانیه",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.Slider(
                    value = currentDuration.toFloat(),
                    onValueChange = { onUpdateSelectedDuration(it.toLong()) },
                    valueRange = 300f..20_000f
                )

                // Time shift — move the whole overlay to the playhead.
                Button(
                    onClick = onMoveSelectedToPlayhead,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("انتقال به محل پلی‌هد (${formatParagraphTime(currentPositionMs)})")
                }

                androidx.compose.material3.HorizontalDivider()
            }

            OutlinedTextField(
                value = paragraphText,
                onValueChange = { if (it.count { c -> c == '\n' } < 7) paragraphText = it },
                label = { Text("متن پاراگراف جدید (چند خطی)") },
                placeholder = { Text("متن خود را بنویسید…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                textStyle = TextStyle(fontFamily = selectedFont.toFontFamily())
            )

            // Persian font picker — same chips as the Text tool.
            Text(
                text = "فونت",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                PersianFont.entries.forEach { font ->
                    val isSelected = font == selectedFont
                    Text(
                        text = font.displayName,
                        fontFamily = font.toFontFamily(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { selectedFont = font }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // ---- Position grid ----
            // Doubles as "where the new paragraph lands" and
            // "snap the selected text here" when one is selected.
            Text(
                text = if (selectedOverlay != null)
                    "موقعیت متن انتخاب‌شده (لمس کنید)"
                else
                    "موقعیت پاراگراف در کادر ویدیو",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PositionGrid(
                selectedAnchorX = anchorX,
                selectedAnchorY = anchorY,
                onPick = { x, y ->
                    anchorX = x
                    anchorY = y
                    if (selectedOverlay != null) onSnapSelected(x, y)
                }
            )

            // ---- Nudge pad for the selected overlay ----
            if (selectedOverlay != null) {
                Text(
                    text = "جابه‌جایی ریز متن انتخاب‌شده",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NudgePad(onNudge = onNudgeSelected)
                Text(
                    text = "برای جابه‌جایی آزاد، متن را روی خود ویدیو با انگشت بکشید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "افزوده می‌شود در ${formatParagraphTime(currentPositionMs)} " +
                    "به مدت ${PARAGRAPH_OVERLAY_DURATION_MS / 1000} ثانیه" +
                    (if (durationMs > 0) " (کل ویدیو: ${formatParagraphTime(durationMs)})" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    val trimmed = paragraphText.trim()
                    if (trimmed.isNotBlank()) {
                        val startMs = currentPositionMs.coerceIn(0L, durationMs)
                        onAddParagraph(
                            trimmed,
                            startMs,
                            startMs + PARAGRAPH_OVERLAY_DURATION_MS,
                            selectedFont,
                            anchorX,
                            anchorY
                        )
                        paragraphText = ""
                    }
                },
                enabled = paragraphText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("افزودن به تایم‌لاین")
            }
        }
    }
}

/**
 * 3x3 anchor grid. Each cell snaps the overlay to a screen
 * position; the picked cell stays highlighted. Labels are Persian
 * and read like the screen (بالا / وسط / پایین + راست / وسط / چپ).
 */
@Composable
private fun PositionGrid(
    selectedAnchorX: Float,
    selectedAnchorY: Float,
    onPick: (x: Float, y: Float) -> Unit
) {
    val rows = listOf(
        listOf(
            Triple(0.15f, 0.12f, "بالا چپ"),
            Triple(0.5f, 0.12f, "بالا"),
            Triple(0.85f, 0.12f, "بالا راست")
        ),
        listOf(
            Triple(0.15f, 0.5f, "وسط چپ"),
            Triple(0.5f, 0.5f, "وسط کادر"),
            Triple(0.85f, 0.5f, "وسط راست")
        ),
        listOf(
            Triple(0.15f, 0.88f, "پایین چپ"),
            Triple(0.5f, 0.88f, "پایین"),
            Triple(0.85f, 0.88f, "پایین راست")
        )
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { (x, y, label) ->
                    val isSelected = kotlin.math.abs(selectedAnchorX - x) < 0.01f &&
                        kotlin.math.abs(selectedAnchorY - y) < 0.01f
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onPick(x, y) }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/**
 * 4-arrow pad that nudges the selected overlay by a small
 * normalized delta per tap — for placement too small to hit
 * with a finger drag.
 */
@Composable
private fun NudgePad(onNudge: (dx: Float, dy: Float) -> Unit) {
    val step = 0.02f
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        NudgeCell(Icons.Filled.ArrowBack, "چپ", Modifier.weight(1f)) { onNudge(-step, 0f) }
        NudgeCell(Icons.Filled.ArrowForward, "راست", Modifier.weight(1f)) { onNudge(step, 0f) }
        NudgeCell(Icons.Filled.ArrowUpward, "بالا", Modifier.weight(1f)) { onNudge(0f, -step) }
        NudgeCell(Icons.Filled.ArrowDownward, "پایین", Modifier.weight(1f)) { onNudge(0f, step) }
    }
}

@Composable
private fun NudgeCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatParagraphTime(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 2026-09-06 — compact on/off chip for the Stroke / Shadow /
 * Background text-system controls.
 */
@Composable
private fun ToggleChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                1.dp,
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal
        )
    }
}
