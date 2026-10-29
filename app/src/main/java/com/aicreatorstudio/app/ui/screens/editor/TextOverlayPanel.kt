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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay

/** Default on-screen duration of a newly added text overlay. */
const val TEXT_OVERLAY_DURATION_MS = 2000L

/**
 * Text panel — two tabs:
 *
 *  1. **افزودن متن** — the add-text flow (unchanged): typed text
 *     lands at the playhead for 2 s and is immediately selected.
 *  2. **استایل متن** (2026-09-08) — a full style editor for the
 *     SELECTED text overlay: font, size, color, preset, stroke,
 *     shadow, background. Every change applies LIVE to the selected
 *     overlay; a second button applies the current look to EVERY
 *     text overlay on the timeline (single undo step).
 */
@Composable
fun TextOverlayPanel(
    currentPositionMs: Long,
    durationMs: Long,
    onAddText: (String, Long, Long, String) -> Unit,
    // 2026-09-06 — «اعمال» action. Same signature as [onAddText];
    // the host wires it to add-at-playhead + select-the-new-overlay.
    onApplyAtPlayhead: (String, Long, Long, String) -> Unit = onAddText,
    // 2026-09-08 — style tab. The selected overlay drives the draft;
    // every mutation calls [onApplyStyle] so the preview updates live.
    selectedOverlay: TextOverlay? = null,
    onUpdateFont: (String) -> Unit = {},
    onUpdateFontSize: (Float) -> Unit = {},
    onUpdateColor: (Int) -> Unit = {},
    onApplyStyle: (SubtitleStyle) -> Unit = {},
    onApplyStyleToAll: (SubtitleStyle) -> Unit = {},
    // 2026-10-12 — user-imported fonts, shared with Subtitle Studio.
    // Defaults keep existing callers compiling with no strip shown.
    customFonts: List<com.aicreatorstudio.app.model.CustomFont> = emptyList(),
    onImportCustomFont: (uri: String) -> Unit = {},
    onDeleteCustomFont: (fontId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(0) }

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
                    imageVector = Icons.Filled.TextFields,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "متن",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f)
                )
            }

            // ---- Tab switcher ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PanelTab("افزودن متن", tab == 0, Modifier.weight(1f)) { tab = 0 }
                PanelTab("استایل", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }

            when (tab) {
                0 -> AddTextTab(
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    onApplyAtPlayhead = onApplyAtPlayhead,
                    customFonts = customFonts,
                    onImportCustomFont = onImportCustomFont,
                    onDeleteCustomFont = onDeleteCustomFont
                )
                else -> TextStyleTab(
                    selectedOverlay = selectedOverlay,
                    onUpdateFont = onUpdateFont,
                    onUpdateFontSize = onUpdateFontSize,
                    onUpdateColor = onUpdateColor,
                    onApplyStyle = onApplyStyle,
                    onApplyStyleToAll = onApplyStyleToAll,
                    customFonts = customFonts,
                    onImportCustomFont = onImportCustomFont,
                    onDeleteCustomFont = onDeleteCustomFont
                )
            }
        }
    }
}

@Composable
private fun PanelTab(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --------------------------------------------------------------------
// Tab 1 — add text (legacy flow, unchanged behaviour)
// --------------------------------------------------------------------

@Composable
private fun AddTextTab(
    currentPositionMs: Long,
    durationMs: Long,
    onApplyAtPlayhead: (String, Long, Long, String) -> Unit,
    customFonts: List<com.aicreatorstudio.app.model.CustomFont>,
    onImportCustomFont: (uri: String) -> Unit,
    onDeleteCustomFont: (fontId: String) -> Unit
) {
    var newText by remember { mutableStateOf("") }
    var selectedFont by remember { mutableStateOf(PersianFont.VAZIR) }
    // 2026-10-12 — when a CUSTOM font is chosen it wins over the enum
    // selection; the enum stays as the remembered fallback so clicking
    // a bundled family afterwards simply clears this.
    var selectedCustomReference by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = newText,
            onValueChange = { newText = it },
            label = { Text("Text") },
            placeholder = { Text("Type your text…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = selectedCustomReference?.let {
                    resolveFontFamily(it) { ref ->
                        com.aicreatorstudio.app.data.font.CustomFontLocator.pathFor(ref)
                    }
                } ?: selectedFont.toFontFamily()
            )
        )

        // Persian font picker — preview each name in its own font
        Text(
            text = "Font",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CustomFontStrip(
            customFonts = customFonts,
            selectedFontFamily = selectedCustomReference,
            onPick = { reference -> selectedCustomReference = reference },
            onImport = onImportCustomFont,
            onDelete = onDeleteCustomFont
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            PersianFont.entries.forEach { font ->
                val isSelected = font == selectedFont && selectedCustomReference == null
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
                        .clickable {
                            selectedFont = font
                            selectedCustomReference = null
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Text(
            text = "Added at ${formatPanelTime(currentPositionMs)} for 2 seconds — drag it anywhere on the video",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                val trimmed = newText.trim()
                if (trimmed.isNotBlank()) {
                    val startMs = currentPositionMs.coerceIn(0L, durationMs)
                    onApplyAtPlayhead(
                        trimmed,
                        startMs,
                        startMs + TEXT_OVERLAY_DURATION_MS,
                        // A custom reference is what gets persisted when
                        // one is chosen; otherwise the enum name. The
                        // two namespaces cannot collide (see CustomFont).
                        selectedCustomReference ?: selectedFont.name
                    )
                    newText = ""
                }
            },
            enabled = newText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(modifier = Modifier.height(4.dp))
            Text("اعمال در محل پلی‌هد")
        }
    }
}

// --------------------------------------------------------------------
// Tab 2 — style editor for the selected overlay
// --------------------------------------------------------------------

private val StyleSwatchColors: List<Int> = listOf(
    0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFFFD54F.toInt(), 0xFF00E5FF.toInt(),
    0xFFFF5252.toInt(), 0xFF69F0AE.toInt(), 0xFF0A84FF.toInt(), 0xFFFF4081.toInt()
)

/** Working-draft backing store for the style tab. */
private data class StyleDraft(
    val fontName: String,
    val sizeSp: Float,
    val colorArgb: Int,
    val style: SubtitleStyle
)

@Composable
private fun TextStyleTab(
    selectedOverlay: TextOverlay?,
    onUpdateFont: (String) -> Unit,
    onUpdateFontSize: (Float) -> Unit,
    onUpdateColor: (Int) -> Unit,
    onApplyStyle: (SubtitleStyle) -> Unit,
    onApplyStyleToAll: (SubtitleStyle) -> Unit,
    customFonts: List<com.aicreatorstudio.app.model.CustomFont>,
    onImportCustomFont: (uri: String) -> Unit,
    onDeleteCustomFont: (fontId: String) -> Unit
) {
    val overlay = selectedOverlay
    if (overlay == null) {
        Text(
            text = "یک متن را از تایم‌لاین انتخاب کن تا استایل آن را اینجا تغییر دهی.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    // Draft seeded from the overlay; every mutation applies
    // immediately through onApplyStyle (live preview on the
    // selected overlay).
    var draft by remember(overlay.id) {
        mutableStateOf(
            StyleDraft(
                fontName = overlay.fontFamily,
                sizeSp = overlay.fontSizeSp,
                colorArgb = overlay.subtitleStyle?.textColorArgb
                    ?: android.graphics.Color.WHITE,
                style = overlay.subtitleStyle ?: SubtitleStyle.Default
            )
        )
    }

    fun mutate(transform: (SubtitleStyle) -> SubtitleStyle) {
        val newStyle = transform(draft.style)
        draft = draft.copy(style = newStyle)
        onApplyStyle(newStyle)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Font ----
        Text(
            text = "فونت",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 2026-10-12 — imported fonts, applied through the SAME
        // onUpdateFont path as a bundled family, so the live preview
        // and the canvas pick it up identically.
        CustomFontStrip(
            customFonts = customFonts,
            selectedFontFamily = draft.fontName,
            onPick = { reference ->
                draft = draft.copy(fontName = reference)
                onUpdateFont(reference)
            },
            onImport = onImportCustomFont,
            onDelete = onDeleteCustomFont
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            PersianFont.entries.forEach { font ->
                val isSelected = font.name == draft.fontName
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
                        .clickable {
                            draft = draft.copy(fontName = font.name)
                            onUpdateFont(font.name)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // ---- Size ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.FormatSize,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "اندازه: ${draft.sizeSp.toInt()}sp",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = draft.sizeSp,
            onValueChange = { size ->
                draft = draft.copy(sizeSp = size)
                onUpdateFontSize(size)
            },
            valueRange = 14f..72f
        )

        // ---- Color ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "رنگ متن",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            StyleSwatchColors.forEach { argb ->
                val isSelected = draft.colorArgb == argb
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable {
                            draft = draft.copy(colorArgb = argb)
                            onUpdateColor(argb)
                        }
                )
            }
        }

        // ---- Presets ----
        Text(
            text = "استایل‌های آماده",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            SubtitleStyle.AllPresets.forEach { (name, preset) ->
                val isSelected = draft.style == preset
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable {
                            draft = draft.copy(
                                style = preset,
                                sizeSp = preset.fontSizeSp,
                                colorArgb = preset.textColorArgb
                            )
                            onApplyStyle(preset)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // ---- Toggles: stroke / shadow / background ----
        StyleToggleRow(
            label = "استروک (خط دور)",
            enabled = draft.style.strokeEnabled
        ) { on ->
            mutate { it.copy(strokeEnabled = on) }
        }
        StyleToggleRow(
            label = "سایه",
            enabled = draft.style.shadowEnabled
        ) { on ->
            mutate { it.copy(shadowEnabled = on) }
        }
        StyleToggleRow(
            label = "باکس پس‌زمینه",
            enabled = draft.style.backgroundEnabled
        ) { on ->
            mutate { it.copy(backgroundEnabled = on) }
        }

        // ---- Apply targets ----
        Button(
            onClick = { onApplyStyle(draft.style) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("اعمال روی متن انتخاب‌شده")
        }
        OutlinedButton(
            onClick = { onApplyStyleToAll(draft.style) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("اعمال روی همه متن‌ها")
        }
    }
}

@Composable
private fun StyleToggleRow(
    label: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (enabled) "روشن" else "خاموش",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatPanelTime(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
