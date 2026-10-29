package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.model.ProgressBarGeometry
import com.aicreatorstudio.app.model.ProgressBarPosition
import com.aicreatorstudio.app.model.ProgressBarSettings
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlueBright

/**
 * «نوار پیشرفت» — the project's **video progress bar** (timeline indicator
 * / story timer).
 *
 * A thin full-width bar drawn inside the canvas that fills 0 → 100% as the
 * playhead advances. The preview above the sheet renders it live, so every
 * control here has an immediate, visible effect — there is no "Apply".
 *
 * ## Not the same thing as the subtitle `progressBar` style
 *
 * `SubtitleStudioPanel` also contains a chip labelled «نوار پیشرفت», but
 * that is a **per-word underline** drawn inside a caption box
 * (`model.ProgressBarSpec`). This panel is a canvas-wide, project-level
 * timeline indicator. They are unrelated settings that happen to share a
 * name in Persian.
 *
 * ## Two callbacks, and why
 *
 * [onSettingsChanged] is the **live** path: state-only, no undo entry, no
 * DB write. [onSettingsCommitted] is the **commit** path: it pushes one
 * undo snapshot and persists.
 *
 * The split exists because the undo stack is 20 deep and does not dedupe,
 * so a single slider drag (which emits a value per frame) would flush the
 * whole history. Sliders therefore call the live path while dragging and
 * the commit path on `onValueChangeFinished`; every discrete control (the
 * toggle, the edge chips, a swatch) calls the commit path directly, since
 * one tap is one deliberate edit.
 *
 * This is the same live/commit pairing the text-drag gesture uses
 * (`beginTextTransform` / `endTextTransform`), and the same shape as
 * `SubtitleStylePanel.LabeledSlider`'s `onValueChange` /
 * `onValueChangeFinished` pair.
 *
 * ## Controlled, not stateful
 *
 * The panel holds no copy of the settings — it renders the `settings`
 * parameter and reports changes upward. The ViewModel clamps and stores
 * them, the state flows back down, and the slider re-renders at the stored
 * value. That is what keeps the panel, the preview and the export from
 * disagreeing: there is exactly one value.
 */
@Composable
fun ProgressBarPanel(
    settings: ProgressBarSettings,
    onSettingsChanged: (ProgressBarSettings) -> Unit,
    onSettingsCommitted: (ProgressBarSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Enable ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "نوار پیشرفت",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "میزان پیشرفت ویدیو روی کادر نمایش داده می‌شود",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(
                checked = settings.isEnabled,
                onCheckedChange = { on ->
                    // Discrete: one tap, one undo entry.
                    onSettingsCommitted(settings.copy(isEnabled = on))
                }
            )
        }

        if (!settings.isEnabled) {
            // Nothing to configure while the bar is off. Keeping the sheet
            // this short is deliberate — the panel opens from the root row,
            // where the user is usually still deciding whether they want it.
            return@Column
        }

        // ── Edge ─────────────────────────────────────────────────────
        SectionLabel("موقعیت")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdgeChip(
                label = "بالا",
                isSelected = settings.position == ProgressBarPosition.TOP,
                onClick = {
                    onSettingsCommitted(settings.copy(position = ProgressBarPosition.TOP))
                }
            )
            EdgeChip(
                label = "پایین",
                isSelected = settings.position == ProgressBarPosition.BOTTOM,
                onClick = {
                    onSettingsCommitted(settings.copy(position = ProgressBarPosition.BOTTOM))
                }
            )
        }

        // ── Thickness ────────────────────────────────────────────────
        LabeledSlider(
            label = "ضخامت",
            valueText = "%.1f".format(settings.heightDp),
            value = settings.heightDp,
            range = ProgressBarGeometry.MIN_HEIGHT_DP..ProgressBarGeometry.MAX_HEIGHT_DP,
            onValueChange = { onSettingsChanged(settings.copy(heightDp = it)) },
            onCommit = { onSettingsCommitted(settings.copy(heightDp = it)) }
        )

        // ── Corner radius (flat → pill) ──────────────────────────────
        LabeledSlider(
            label = "گردی گوشه",
            valueText = if (settings.cornerRadiusDp <= 0.05f) {
                "صاف"
            } else {
                "%.1f".format(settings.cornerRadiusDp)
            },
            value = settings.cornerRadiusDp,
            range = 0f..ProgressBarGeometry.MAX_CORNER_RADIUS_DP,
            onValueChange = { onSettingsChanged(settings.copy(cornerRadiusDp = it)) },
            onCommit = { onSettingsCommitted(settings.copy(cornerRadiusDp = it)) }
        )

        // ── Margin ───────────────────────────────────────────────────
        LabeledSlider(
            label = "حاشیه",
            valueText = "%.1f".format(settings.marginDp),
            value = settings.marginDp,
            range = 0f..ProgressBarGeometry.MAX_MARGIN_DP,
            onValueChange = { onSettingsChanged(settings.copy(marginDp = it)) },
            onCommit = { onSettingsCommitted(settings.copy(marginDp = it)) }
        )

        // ── Fill colour ──────────────────────────────────────────────
        SectionLabel("رنگ نوار")
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            BAR_COLOR_PRESETS.forEach { preset ->
                ColorDot(
                    argb = preset.argb,
                    label = preset.label,
                    isSelected = settings.colorArgb == preset.argb,
                    onClick = { onSettingsCommitted(settings.copy(colorArgb = preset.argb)) }
                )
            }
            // Custom hex. Highlighted when the current colour is not one of
            // the presets, so the user can always see which entry is live.
            CustomHexDot(
                isSelected = BAR_COLOR_PRESETS.none { it.argb == settings.colorArgb },
                onPick = { argb -> onSettingsCommitted(settings.copy(colorArgb = argb)) }
            )
        }

        // ── Track (the un-travelled part) ────────────────────────────
        //
        // The model's `backgroundColorArgb` needs a real consumer or it is
        // write-only state (bug class 1). Rather than a second full colour
        // picker — which would double the panel's height for a value most
        // users never touch — this is a four-step opacity ramp over white,
        // which is the only thing a track colour ever usefully is.
        SectionLabel("پس‌زمینه نوار")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TRACK_PRESETS.forEach { preset ->
                EdgeChip(
                    label = preset.label,
                    isSelected = settings.backgroundColorArgb == preset.argb,
                    onClick = {
                        onSettingsCommitted(settings.copy(backgroundColorArgb = preset.argb))
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

private data class BarColorPreset(val argb: Int, val label: String)

/**
 * Fill-colour presets. Neon Red is the model default, and the other three
 * are the ones that stay legible on dark *and* light footage — which is
 * the only real requirement for a bar that sits on top of video.
 */
private val BAR_COLOR_PRESETS = listOf(
    BarColorPreset(0xFFFF0055.toInt(), "نئون"),
    BarColorPreset(0xFFFFE600.toInt(), "زرد"),
    BarColorPreset(0xFF00E5FF.toInt(), "فیروزه"),
    BarColorPreset(0xFFFFFFFF.toInt(), "سفید")
)

private data class TrackPreset(val argb: Int, val label: String)

/**
 * Track presets: a four-step opacity ramp over white. `0x00` is a fully
 * transparent track, i.e. "no track at all".
 */
private val TRACK_PRESETS = listOf(
    TrackPreset(0x00FFFFFF.toInt(), "خاموش"),
    TrackPreset(0x33FFFFFF.toInt(), "کم"),
    TrackPreset(0x66FFFFFF.toInt(), "متوسط"),
    TrackPreset(0xB3FFFFFF.toInt(), "پررنگ")
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = LabelSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold
    )
}

/**
 * A labelled slider whose drag reports live and whose release commits.
 *
 * The value shown comes from the `value` parameter (not local state), so
 * the thumb is always where the stored setting is — the panel cannot drift
 * from the document.
 */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onCommit: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            onValueChangeFinished = { onCommit(value) }
        )
    }
}

@Composable
private fun EdgeChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) iOSBlueBright else Color(0xFF1C1C1E))
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Transparent else HairlineSubtle,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ColorDot(
    argb: Int,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = when {
                    isSelected -> iOSBlueBright
                    argb == 0xFFFFFFFF.toInt() -> HairlineSubtle
                    else -> Color.Transparent
                },
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .semanticsLabel(label),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Text(
                text = "✓",
                color = if (argb == 0xFFFFFFFF.toInt()) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * A circular hex-entry field that commits a colour as soon as the typed
 * text parses as a 6- or 8-digit hex value.
 *
 * Committing on parse (rather than on an Enter key) is deliberate: the
 * field is 34dp, there is no visible "confirm" affordance, and a soft
 * keyboard's Done key is not guaranteed. A partially-typed value simply
 * does not parse and therefore changes nothing.
 */
@Composable
private fun CustomHexDot(
    isSelected: Boolean,
    onPick: (Int) -> Unit
) {
    var text by remember { mutableStateOf("") }

    LaunchedEffect(text) {
        parseProgressBarHex(text)?.let(onPick)
    }

    Box(
        modifier = Modifier
            .width(58.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(Color(0xFF1C1C1E))
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) iOSBlueBright else HairlineSubtle,
                shape = RoundedCornerShape(17.dp)
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (text.isEmpty()) {
            Text(
                text = "#",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(8) },
            singleLine = true,
            textStyle = TextStyle(color = LabelPrimary, fontSize = 11.sp),
            cursorBrush = SolidColor(iOSBlueBright),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
        )
    }
}

/**
 * Parses `"RRGGBB"` / `"#RRGGBB"` / `"AARRGGBB"` into an ARGB `Int`, or
 * `null` when the text is not (yet) a complete colour.
 *
 * Accepts an optional leading `#` and is case-insensitive. A 6-digit value
 * is treated as fully opaque, which is what a user typing `ff0055` means.
 */
internal fun parseProgressBarHex(raw: String): Int? {
    val trimmed = raw.trim().removePrefix("#")
    if (trimmed.length != 6 && trimmed.length != 8) return null
    if (trimmed.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val parsed = trimmed.toLongOrNull(16) ?: return null
    return if (trimmed.length == 6) {
        (0xFF000000L or parsed).toInt()
    } else {
        parsed.toInt()
    }
}

/**
 * `Modifier`-level content description so the colour dots are reachable by
 * a screen reader. Kept as a tiny local helper rather than pulling in
 * `androidx.compose.ui.semantics` at every call site.
 */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }
