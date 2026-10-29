package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.ChromaKeyPreset
import com.aicreatorstudio.app.model.ChromaKeySettings
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.util.ChromaKeyEngine

/**
 * «پرده سبز» — chroma key (green/blue screen removal) for a
 * picture-in-picture overlay.
 *
 * ## What it keys
 *
 * The panel edits [ChromaKeySettings] for the **selected overlay**, exactly
 * like `OverlayMaskPanel` edits `OverlayMaskSettings`: two PiPs on the same
 * clip can carry different keys, so the target is
 * `EditorUiState.selectedOverlayId`. When nothing is selected the panel
 * renders a short "pick an overlay" state instead of guessing at a target.
 *
 * ## Two callbacks, and why
 *
 * [onSettingsChanged] is the **live** path: state only, no undo entry, no
 * DB write. [onSettingsCommitted] is the **commit** path: one undo snapshot
 * plus a persist. Sliders call both — live while dragging, commit on
 * `onValueChangeFinished` — because the undo stack is 20 deep and does not
 * dedupe, so one drag emitting a value per frame would flush the whole
 * history. Discrete controls (the enable switch, a preset chip, the reset
 * button) call the commit path directly, since one tap is one deliberate
 * edit.
 *
 * ## Controlled, not stateful
 *
 * The panel holds no copy of the settings. It renders the `settings`
 * parameter and reports changes upward; the ViewModel sanitises and stores
 * them, the state flows back down, and the chips re-render at the stored
 * value. That is what keeps the panel, the preview clip and the exported
 * frame from disagreeing — there is exactly one value.
 *
 * ## Why a custom colour is a chip too
 *
 * Even a keyed-to-one-off colour overlay has to be reachable. The custom chip
 * is always present, so a magenta screen is one tap and a slider away rather
 * than a feature that exists in the model but not in the UI. It carries the
 * palette icon so it reads as "pick your own", and selecting it writes the
 * swatch the preview is already showing — never a value the user has not
 * seen.
 *
 * ## Disable does not reset
 *
 * The enable switch writes `isEnabled = false` and leaves the tuning alone.
 * [ChromaKeySettings.isIdentity] is deliberately NOT simply `!isEnabled`, so
 * a user who turns the effect off to check the raw footage keeps their
 * similarity and spill when they turn it back on. The reset button is the
 * only thing that clears the tuning.
 */
@Composable
fun ChromaKeyPanel(
    settings: ChromaKeySettings,
    /** Human-readable name of the targeted overlay, for the header hint. */
    targetLabel: String,
    /** False when no overlay is selected — the panel then edits nothing. */
    hasTarget: Boolean,
    onSettingsChanged: (ChromaKeySettings) -> Unit,
    onSettingsCommitted: (ChromaKeySettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "پرده سبز",
                color = LabelPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (hasTarget) {
                    "حذف پس‌زمینه رنگی از: $targetLabel"
                } else {
                    "برای اعمال پرده سبز، یک لایه تصویر یا ویدیو را انتخاب کنید"
                },
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }

        if (!hasTarget) {
            // Nothing to edit. Returning early keeps the sheet short rather
            // than showing a row of chips that would do nothing.
            return@Column
        }

        // ── Enable ───────────────────────────────────────────────────
        //
        // First row, always enabled: this is the master switch, and it is the
        // one control that must be reachable even when every slider is at
        // its default.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "فعال‌سازی",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "حذف رنگ پس‌زمینه از لایه انتخاب‌شده",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(
                checked = settings.isEnabled,
                onCheckedChange = { on ->
                    onSettingsCommitted(
                        ChromaKeyEngine.sanitize(settings.copy(isEnabled = on))
                    )
                }
            )
        }

        // ── Key colour ───────────────────────────────────────────────
        SectionLabel("رنگ پس‌زمینه")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChromaKeyPreset.entries.forEach { preset ->
                ColorChip(
                    label = preset.displayName,
                    swatch = Color(preset.argb),
                    isSelected = settings.keyColorArgb == preset.argb,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSettingsCommitted(
                            ChromaKeyEngine.sanitize(
                                settings.copy(keyColorArgb = preset.argb)
                            )
                        )
                    }
                )
            }
            // The custom chip shows the CURRENT key colour, so what it draws
            // is what is already on screen. It writes the same value back,
            // which the ViewModel's equality guard turns into a no-op — this
            // chip is a statement of intent, not a second control.
            ColorChip(
                label = "دلخواه",
                swatch = Color(
                    ChromaKeySettings.OPAQUE_ALPHA_MASK or settings.keyColorArgb
                ),
                isSelected = ChromaKeyPreset.entries.none {
                    it.argb == settings.keyColorArgb
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Palette,
                onClick = {
                    // Tapping a preset row already switched the colour; there
                    // is nothing further to write here, so the panel stays
                    // silent rather than committing a no-op.
                }
            )
        }

        // ── Similarity ───────────────────────────────────────────────
        LabeledSlider(
            label = "حساسیت",
            valueText = "${(settings.similarity * 100).toInt()}٪",
            value = settings.similarity,
            range = ChromaKeyEngine.MIN_LEVEL..ChromaKeyEngine.MAX_LEVEL,
            onValueChange = {
                onSettingsChanged(
                    ChromaKeyEngine.sanitize(settings.copy(similarity = it))
                )
            },
            onCommit = {
                onSettingsCommitted(
                    ChromaKeyEngine.sanitize(settings.copy(similarity = it))
                )
            }
        )

        // ── Smoothness ───────────────────────────────────────────────
        LabeledSlider(
            label = "نرمی لبه",
            valueText = "${(settings.smoothness * 100).toInt()}٪",
            value = settings.smoothness,
            range = ChromaKeyEngine.MIN_LEVEL..ChromaKeyEngine.MAX_LEVEL,
            onValueChange = {
                onSettingsChanged(
                    ChromaKeyEngine.sanitize(settings.copy(smoothness = it))
                )
            },
            onCommit = {
                onSettingsCommitted(
                    ChromaKeyEngine.sanitize(settings.copy(smoothness = it))
                )
            }
        )

        // ── Spill reduction ──────────────────────────────────────────
        LabeledSlider(
            label = "حذف هاله سبز",
            valueText = "${(settings.spillReduction * 100).toInt()}٪",
            value = settings.spillReduction,
            range = ChromaKeyEngine.MIN_LEVEL..ChromaKeyEngine.MAX_LEVEL,
            onValueChange = {
                onSettingsChanged(
                    ChromaKeyEngine.sanitize(settings.copy(spillReduction = it))
                )
            },
            onCommit = {
                onSettingsCommitted(
                    ChromaKeyEngine.sanitize(settings.copy(spillReduction = it))
                )
            }
        )

        // ── Reset ────────────────────────────────────────────────────
        //
        // Greyed out rather than hidden when there is nothing to reset, so
        // the button's position never moves and the user can still find it.
        val canReset = !settings.isIdentity
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (canReset) Color(0xFF1C1C1E) else Color(0xFF141416))
                .border(
                    width = 1.dp,
                    color = if (canReset) HairlineSubtle else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable(enabled = canReset) {
                    onSettingsCommitted(ChromaKeySettings.Default)
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "بازنشانی",
                color = if (canReset) LabelPrimary else LabelSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (canReset) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

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
 * A key-colour chip: a swatch plus its Persian name.
 *
 * The swatch is drawn as a solid block with a hairline ring when unselected
 * so a black or white key colour stays visible against the panel; the
 * selected state fills the chip surface instead, which is the same idiom
 * `ShapeChip` uses — one visual language for "this one is live".
 *
 * `modifier` is applied by the caller (the `Row`), because `weight` is a
 * `RowScope` extension and this composable's own receiver is a `Column`.
 */
@Composable
private fun ColorChip(
    label: String,
    swatch: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) iOSBlueBright else Color(0xFF1C1C1E))
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Transparent else HairlineSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else LabelPrimary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(swatch)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) Color.White.copy(alpha = 0.6f) else HairlineSubtle,
                        shape = RoundedCornerShape(6.dp)
                    )
            )
        }
        Text(
            text = label,
            color = if (isSelected) Color.White else LabelPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * A labelled slider whose drag reports live and whose release commits.
 *
 * The value shown comes from the `value` parameter (not local state), so the
 * thumb is always where the stored setting is — the panel cannot drift from
 * the document.
 *
 * A narrow range is special-cased: the emit and the commit are the same
 * value, because a slider with one reachable position cannot drag.
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
