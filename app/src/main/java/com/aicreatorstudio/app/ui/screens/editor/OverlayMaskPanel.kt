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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Star
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
import com.aicreatorstudio.app.model.OverlayMaskGeometry
import com.aicreatorstudio.app.model.OverlayMaskSettings
import com.aicreatorstudio.app.model.OverlayMaskType
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlueBright

/**
 * «ماسک» — shape masks for a picture-in-picture overlay.
 *
 * ## What it shapes
 *
 * The panel edits [OverlayMaskSettings] for the **selected overlay**, not
 * for the selected clip: two PiPs on the same clip can carry different
 * shapes, so the target is `EditorUiState.selectedOverlayId`. When nothing
 * is selected the panel renders a short "pick an overlay" state instead of
 * guessing at a target — a panel that silently edits the wrong object is
 * worse than one that says it has nothing to edit.
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
 * the commit path on `onValueChangeFinished`; every discrete control (a
 * shape chip, the invert switch, the reset button) calls the commit path
 * directly, since one tap is one deliberate edit. This is the same
 * live/commit pairing `ProgressBarPanel` uses.
 *
 * ## Controlled, not stateful
 *
 * The panel holds no copy of the settings — it renders the `settings`
 * parameter and reports changes upward. The ViewModel clamps and stores
 * them, the state flows back down, and the chips re-render at the stored
 * value. That is what keeps the panel, the preview clip and the exported
 * frame from disagreeing: there is exactly one value.
 *
 * ## Feather has a consumer, deliberately
 *
 * `featherDp` is not decoration. `OverlayMaskRenderer.rasterizeAlphaMask`
 * and the preview's `BlendMode.DstIn` layer both read it, so the slider
 * changes the exported file. A model field with no renderer would be
 * exactly the write-only-state bug this project keeps hitting.
 */
@Composable
fun OverlayMaskPanel(
    settings: OverlayMaskSettings,
    /** Human-readable name of the targeted overlay, for the header hint. */
    targetLabel: String,
    /** False when no overlay is selected — the panel then edits nothing. */
    hasTarget: Boolean,
    onSettingsChanged: (OverlayMaskSettings) -> Unit,
    onSettingsCommitted: (OverlayMaskSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "ماسک لایه",
                color = LabelPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (hasTarget) {
                    "شکل برش لایه: $targetLabel"
                } else {
                    "برای اعمال ماسک، یک لایه تصویر یا ویدیو را انتخاب کنید"
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

        // ── Shape ────────────────────────────────────────────────────
        SectionLabel("شکل ماسک")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OverlayMaskGeometry.panelOrder.forEach { type ->
                ShapeChip(
                    type = type,
                    isSelected = settings.type == type,
                    // `weight` is a RowScope extension, so it has to be
                    // applied HERE rather than inside `ShapeChip` — whose
                    // own receiver is a Column.
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Switching to NONE also clears `invert`: an inverted
                        // "no shape" is not a state the renderers can honour
                        // (there is no outline to complement), and leaving it
                        // set would make the reset button lie.
                        val next = if (type == OverlayMaskType.NONE) {
                            settings.copy(type = type, invert = false)
                        } else {
                            settings.copy(type = type)
                        }
                        onSettingsCommitted(OverlayMaskGeometry.sanitize(next))
                    }
                )
            }
        }

        // ── Corner radius — ROUNDED_RECT only ────────────────────────
        //
        // The slider is HIDDEN rather than disabled for the other shapes,
        // because the value genuinely has no meaning there; a greyed-out
        // slider implies the setting is stored and merely locked.
        if (settings.type == OverlayMaskType.ROUNDED_RECT) {
            LabeledSlider(
                label = "گردی گوشه",
                valueText = if (settings.cornerRadiusDp <= 0.05f) {
                    "صاف"
                } else {
                    "%.0f".format(settings.cornerRadiusDp)
                },
                value = settings.cornerRadiusDp,
                range = OverlayMaskGeometry.MIN_CORNER_RADIUS_DP..
                    OverlayMaskGeometry.MAX_CORNER_RADIUS_DP,
                onValueChange = {
                    onSettingsChanged(OverlayMaskGeometry.sanitize(
                        settings.copy(cornerRadiusDp = it)
                    ))
                },
                onCommit = {
                    onSettingsCommitted(OverlayMaskGeometry.sanitize(
                        settings.copy(cornerRadiusDp = it)
                    ))
                }
            )
        }

        // ── Feather ──────────────────────────────────────────────────
        if (settings.type != OverlayMaskType.NONE) {
            LabeledSlider(
                label = "نرمی لبه",
                valueText = if (settings.featherDp <= 0.05f) {
                    "تیز"
                } else {
                    "%.0f".format(settings.featherDp)
                },
                value = settings.featherDp,
                range = OverlayMaskGeometry.MIN_FEATHER_DP..OverlayMaskGeometry.MAX_FEATHER_DP,
                onValueChange = {
                    onSettingsChanged(OverlayMaskGeometry.sanitize(
                        settings.copy(featherDp = it)
                    ))
                },
                onCommit = {
                    onSettingsCommitted(OverlayMaskGeometry.sanitize(
                        settings.copy(featherDp = it)
                    ))
                }
            )
        }

        // ── Invert ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "معکوس کردن ماسک",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "بیرون شکل نمایش داده می‌شود، نه داخل آن",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Switch(
                checked = settings.invert,
                enabled = settings.type != OverlayMaskType.NONE,
                onCheckedChange = { on ->
                    onSettingsCommitted(
                        OverlayMaskGeometry.sanitize(settings.copy(invert = on))
                    )
                }
            )
        }

        // ── Reset ────────────────────────────────────────────────────
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
                    onSettingsCommitted(OverlayMaskSettings.Default)
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "حذف ماسک",
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

private data class ShapePreset(
    val type: OverlayMaskType,
    val icon: ImageVector
)

/**
 * The five shapes, in [OverlayMaskGeometry.panelOrder] order — the same
 * list the renderers and the persistence layer key off, so a chip can
 * never be added here without a matching enum entry.
 *
 * `Favorite` and `Star` come from material-icons-**core**; `Block`,
 * `Circle` and `RoundedCorner` are from **extended**. Both are on the
 * classpath, but note that a name that exists in neither produces a lone
 * "Unresolved reference" that looks like the icon does not exist.
 */
private val SHAPE_PRESETS = listOf(
    ShapePreset(OverlayMaskType.NONE, Icons.Filled.Block),
    ShapePreset(OverlayMaskType.CIRCLE, Icons.Filled.Circle),
    ShapePreset(OverlayMaskType.ROUNDED_RECT, Icons.Filled.RoundedCorner),
    ShapePreset(OverlayMaskType.HEART, Icons.Filled.Favorite),
    ShapePreset(OverlayMaskType.STAR, Icons.Filled.Star)
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

@Composable
private fun ShapeChip(
    type: OverlayMaskType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preset = SHAPE_PRESETS.firstOrNull { it.type == type }
        ?: ShapePreset(type, Icons.Filled.Block)
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
            .semanticsLabel(type.displayName),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = preset.icon,
            contentDescription = null,
            tint = if (isSelected) Color.White else LabelPrimary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = type.displayName,
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
 * The value shown comes from the `value` parameter (not local state), so
 * the thumb is always where the stored setting is — the panel cannot
 * drift from the document.
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

/** `Modifier`-level content description so the chips reach a screen reader. */
private fun Modifier.semanticsLabel(label: String): Modifier =
    this.semantics { contentDescription = label }
