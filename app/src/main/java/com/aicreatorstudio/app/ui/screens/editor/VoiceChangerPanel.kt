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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.model.VoiceEffects
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlueBright

/**
 * «تغییر صدا» — the offline **voice changer**.
 *
 * Six preset cards; tapping one applies immediately. There is no "Apply"
 * button and no draft state, for the same reason the other editor panels
 * have none: the preview above the sheet plays the effect as soon as the
 * card is tapped, so a two-step confirm would only add a way for the
 * panel and the audio to disagree.
 *
 * ## Why the panel is a grid of cards and not a slider sheet
 *
 * The effects are *discrete named things* a user recognises ("the squirrel
 * one"), not values they want to tune. So the panel offers exactly the six
 * presets in [VoiceEffects.panelOrder] and no parameters — the pitch
 * factor, band edges and delay times are properties of the preset, owned
 * by [VoiceEffect]. That also means the panel cannot produce a combination
 * the DSP has not been tested against.
 *
 * ## Controlled, not stateful
 *
 * The panel holds no copy of the current effect. It renders the [selected]
 * parameter and reports taps upward; the ViewModel stores them and the
 * value flows back down. That is what keeps the panel, the preview and the
 * export from disagreeing — there is exactly one value, and it lives on
 * the clip.
 *
 * ## Reset
 *
 * «بازنشانی به حالت عادی» is a distinct button rather than "tap عادی
 * again" because the عادی card is also a *selection* — a user who has
 * scrolled the row looking for a better effect should be able to get back
 * to the default in one unambiguous tap. It is disabled while the effect
 * already *is* the default, so it never writes a no-op undo entry.
 *
 * @param selected the effect currently stored on the target clip.
 * @param hasTarget whether there is a clip to apply an effect to. When
 *   false the cards are still drawn (so the panel shows what exists) but
 *   are not tappable, and a hint explains why.
 * @param onSelect invoked with the tapped effect. The caller is
 *   responsible for pushing the undo snapshot and persisting.
 */
@Composable
fun VoiceChangerPanel(
    selected: VoiceEffect,
    hasTarget: Boolean,
    onSelect: (VoiceEffect) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "تغییر صدا",
                color = LabelPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "صدا را بدون تغییر سرعت و مدت کلیپ، تغییر بده",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }

        if (!hasTarget) {
            // Explains the disabled state instead of leaving six dead
            // cards. The text names the action the user has to take.
            Text(
                text = "برای اعمال افکت، یک کلیپ یا فایل صوتی را انتخاب کن",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1C1C1E))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }

        // ── Presets ──────────────────────────────────────────────────
        //
        // Laid out as rows of three rather than a LazyVerticalGrid: the
        // list is six fixed entries, and a lazy grid inside a
        // height-constrained sheet would need an explicit height for no
        // benefit.
        val presets = VoiceEffects.panelOrder
        presets.chunked(3).forEach { rowEffects ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowEffects.forEach { effect ->
                    VoicePresetCard(
                        effect = effect,
                        isSelected = effect == selected,
                        enabled = hasTarget,
                        onClick = { onSelect(effect) },
                        // weight on the CARD, which fills its share; the
                        // icon and the labels are children *inside* the
                        // card, so they can never overflow into the
                        // neighbouring slot.
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Reset ────────────────────────────────────────────────────
        ResetToNormalButton(
            enabled = hasTarget && selected != VoiceEffect.NONE,
            onClick = { onSelect(VoiceEffect.NONE) }
        )
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

/**
 * One preset card: icon, short label, one-line description.
 *
 * The card face uses a *short* label ([VoicePreset.shortLabel]) while the
 * accessibility description and the tooltip-ish hint use the effect's full
 * [VoiceEffect.displayName]. «سنجاب / کارتونی» is the real name of the
 * preset, but a third of the panel is about 104dp and that string does not
 * fit there at any legible size.
 */
@Composable
private fun VoicePresetCard(
    effect: VoiceEffect,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val preset = VoicePreset.forEffect(effect)
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    isSelected -> iOSBlueBright.copy(alpha = 0.18f)
                    else -> Color(0xFF1C1C1E)
                }
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) iOSBlueBright else HairlineSubtle,
                shape = shape
            )
            // `enabled = false` on a clickable keeps the card visible and
            // announced but not tappable — the alternative (hiding the
            // grid) would make the panel look broken.
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp)
            .semantics { contentDescription = effect.displayName },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = preset.icon,
            // The card already carries the description; repeating it on the
            // icon would make a screen reader say the name twice.
            contentDescription = null,
            tint = when {
                isSelected -> iOSBlueBright
                enabled -> LabelPrimary
                else -> LabelSecondary
            },
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = preset.shortLabel,
            color = if (enabled) LabelPrimary else LabelSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = preset.hint,
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * «بازنشانی به حالت عادی» — a full-width outlined button.
 *
 * Rendered as a bordered box rather than a Material `Button` so it matches
 * the chips and cards around it, which are all flat bordered surfaces in
 * this sheet.
 */
@Composable
private fun ResetToNormalButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (enabled) Color(0xFF1C1C1E) else Color(0xFF161618))
            .border(
                width = 1.dp,
                color = if (enabled) HairlineSubtle else HairlineSubtle.copy(alpha = 0.4f),
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "بازنشانی به حالت عادی",
            color = if (enabled) LabelPrimary else LabelSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * The display-only facts about a preset: its icon, the label that fits on
 * a card, and the one-line hint under it.
 *
 * Kept here rather than on [VoiceEffect] because icons are a Compose/UI
 * concern and `model/VoiceEffect.kt` must stay free of `androidx.compose`
 * so it can be unit-tested in the JVM test source set. The *numbers* stay
 * on the enum; only the presentation lives here.
 */
private data class VoicePreset(
    val shortLabel: String,
    val hint: String,
    val icon: ImageVector
) {
    companion object {
        fun forEffect(effect: VoiceEffect): VoicePreset = when (effect) {
            VoiceEffect.NONE -> VoicePreset(
                shortLabel = "عادی",
                hint = "بدون تغییر",
                icon = Icons.Filled.RecordVoiceOver
            )
            VoiceEffect.CHIPMUNK -> VoicePreset(
                shortLabel = "سنجاب",
                hint = "زیر و کارتونی",
                icon = Icons.Filled.Pets
            )
            VoiceEffect.DEEP -> VoicePreset(
                shortLabel = "بم",
                hint = "سنگین و غول‌آسا",
                icon = Icons.Filled.GraphicEq
            )
            VoiceEffect.ROBOT -> VoicePreset(
                shortLabel = "ربات",
                hint = "فلزی و ماشینی",
                icon = Icons.Filled.SmartToy
            )
            VoiceEffect.TELEPHONE -> VoicePreset(
                shortLabel = "تلفنی",
                hint = "باند باریک",
                icon = Icons.Filled.PhoneAndroid
            )
            VoiceEffect.ECHO -> VoicePreset(
                shortLabel = "اکو",
                hint = "پژواک و تکرار",
                icon = Icons.Filled.Waves
            )
        }
    }
}
