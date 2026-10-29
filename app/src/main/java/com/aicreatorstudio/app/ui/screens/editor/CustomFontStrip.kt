package com.aicreatorstudio.app.ui.screens.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.CustomFont

/**
 * 2026-10-12 — the shared **custom font** strip: an `[+ افزودن فونت]`
 * card followed by every font the user has imported, each with a
 * delete affordance.
 *
 * ## Why one composable for both panels
 *
 * `SubtitleStudioPanel` and `TextOverlayPanel` both need this. They are
 * different enough (different tab structures, different state models)
 * that sharing the whole font section would be wrong, but the *import
 * launcher contract* must be identical in both — same MIME types, same
 * result handling, same delete semantics. Duplicating it would mean two
 * places to keep in sync every time the picker contract changes, and
 * the two would drift. So the strip is shared and embedded.
 *
 * ## The picker contract
 *
 * `ActivityResultContracts.OpenDocument()` with three MIME types:
 *
 *  * `font/ttf` — the correct type, but Android's `MimeTypeMap` does not
 *    know it on every API level, so some providers return `null` for a
 *    `.ttf`.
 *  * `font/otf` — same caveat.
 *  * `application/octet-stream` — what many file managers (and every
 *    cloud provider that guesses from an unknown extension) actually
 *    report for a font file.
 *
 * All three are requested because the alternative — asking for
 * `*` and filtering afterwards — would let the user pick an
 * arbitrary file from anywhere. This is the narrowest filter that still
 * lets a real `.ttf`/`.otf` through on real devices. The *authoritative*
 * validation is the byte sniff in `DefaultCustomFontRepository`, so a
 * wrong pick that slips past the filter is still rejected cleanly.
 *
 * `OpenDocument` (not `GetContent`) is deliberate: it grants a
 * persistable, re-readable URI across process death, which matters
 * because the copy happens slightly after the picker returns.
 *
 * @param customFonts the user's imported fonts, newest first.
 * @param selectedFontFamily the persisted reference of the current
 *   selection (`"custom:<id>"` or a `PersianFont` name), used to ring
 *   the active chip.
 * @param onPick called with the family reference when a custom font is
 *   chosen. The caller persists it, exactly as it would for a bundled
 *   family.
 * @param onImport called with the picked document URI.
 * @param onDelete called with the font id to remove.
 */
@Composable
fun CustomFontStrip(
    customFonts: List<CustomFont>,
    selectedFontFamily: String?,
    onPick: (String) -> Unit,
    onImport: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // The launcher is created unconditionally (Compose requires a stable
    // call order); registering it costs nothing until launched.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        // A cancelled picker delivers null — not an error, just a no-op.
        uri?.let { onImport(it.toString()) }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.horizontalScroll(rememberScrollState())
    ) {
        // ---- Add-font card ----
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, FontPickerColors.SubtleBorder, RoundedCornerShape(10.dp))
                .clickable {
                    launcher.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "application/octet-stream"
                        )
                    )
                }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+ افزودن فونت",
                color = FontPickerColors.Primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        // ---- Imported fonts ----
        customFonts.forEach { font ->
            val reference = font.selectionId
            val isSelected = reference == selectedFontFamily
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) FontPickerColors.Primary.copy(alpha = 0.22f)
                        else Color.White.copy(alpha = 0.05f)
                    )
                    .border(
                        1.dp,
                        if (isSelected) FontPickerColors.PrimaryBright
                        else FontPickerColors.SubtleBorder,
                        RoundedCornerShape(10.dp)
                    )
            ) {
                Text(
                    text = font.name,
                    // Previewed in its OWN font: the whole point of an
                    // imported font is seeing it, and a label in the
                    // default face tells the user nothing.
                    fontFamily = resolveFontFamily(reference) {
                        com.aicreatorstudio.app.data.font.CustomFontLocator.pathFor(it)
                    },
                    color = if (isSelected) FontPickerColors.PrimaryBright
                    else FontPickerColors.Label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { onPick(reference) }
                        .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp)
                )
                // Delete affordance. Sized explicitly and NOT placed in
                // a weighted slot — this project has been bitten by a
                // fixed-size child in a `weight(1f, fill = true)` slot
                // overflowing into its neighbour.
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onDelete(font.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "حذف فونت",
                        tint = FontPickerColors.Label.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                Box(modifier = Modifier.size(4.dp))
            }
        }
    }
}

/**
 * Colours for the shared font pickers.
 *
 * Held here rather than reached from a theme because both panels render
 * on the same dark editor surface and these values are also used by the
 * bundled-family chips — defining them once keeps the custom strip from
 * looking like a different control than the family chips beside it.
 */
internal object FontPickerColors {
    val Primary = Color(0xFF0A84FF)
    val PrimaryBright = Color(0xFF4FA8FF)
    val Label = Color(0xFFEDEDF2)
    val SubtleBorder = Color(0x33FFFFFF)
}
