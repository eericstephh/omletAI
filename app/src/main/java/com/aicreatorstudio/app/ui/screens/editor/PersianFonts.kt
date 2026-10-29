package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.aicreatorstudio.app.R
import com.aicreatorstudio.app.model.CustomFont
import com.aicreatorstudio.app.model.PersianFont

/**
 * Maps a [PersianFont] to its bundled Compose [FontFamily].
 *
 * 2026-09-14 — the five multi-weight Persian families ship as
 * Android font-family XML resources (res/font, ending _family) so a
 * FontWeight selection resolves to the REAL TTF weight (Light /
 * Regular / Medium / Bold / Black) instead of synthetic bolding.
 * Single-file fonts resolve directly to their TTF.
 *
 * 2026-09-19 PHASE 5 — [weight] (CSS 100..1000, default 400)
 * resolves multi-file families to explicit per-weight entries, so
 * the requested weight is honored with real TTFs (minSdk-safe,
 * no API-28 `Typeface.create` needed). The [weight] argument
 * selects WHICH files are registered; Compose then matches the
 * TextStyle's FontWeight against them. Single-file families
 * ignore it (fake-bold downstream).
 */
fun PersianFont.toFontFamily(weight: Int = FontWeight.Normal.weight): FontFamily = when (this) {
        PersianFont.DEFAULT -> FontFamily.Default
        PersianFont.VAZIR -> FontFamily(
            Font(R.font.vazirmatn_light, FontWeight.ExtraLight),
            Font(R.font.vazirmatn, FontWeight.Normal),
            Font(R.font.vazirmatn_medium, FontWeight.Medium),
            Font(R.font.vazirmatn_bold, FontWeight.Bold),
            Font(R.font.vazirmatn_black, FontWeight.Black)
        )
        PersianFont.SAHEL -> FontFamily(
            Font(R.font.sahel_light, FontWeight.ExtraLight),
            Font(R.font.sahel, FontWeight.Normal),
            Font(R.font.sahel_bold, FontWeight.Bold),
            Font(R.font.sahel_black, FontWeight.Black)
        )
        PersianFont.SHABNAM -> FontFamily(
            Font(R.font.shabnam_light, FontWeight.ExtraLight),
            Font(R.font.shabnam, FontWeight.Normal),
            Font(R.font.shabnam_medium, FontWeight.Medium),
            Font(R.font.shabnam_bold, FontWeight.Bold),
            Font(R.font.shabnam_black, FontWeight.Black)
        )
        PersianFont.SAMIM -> FontFamily(
            Font(R.font.samim, FontWeight.Normal),
            Font(R.font.samim_medium, FontWeight.Medium),
            Font(R.font.samim_bold, FontWeight.Bold)
        )
        PersianFont.ESTEDAD -> FontFamily(
            Font(R.font.estedad, FontWeight.Normal),
            Font(R.font.estedad_medium, FontWeight.Medium),
            Font(R.font.estedad_bold, FontWeight.Bold),
            Font(R.font.estedad_black, FontWeight.Black)
        )
        PersianFont.PARASTOO -> FontFamily(
            Font(R.font.parastoo, FontWeight.Normal),
            Font(R.font.parastoo_bold, FontWeight.Bold)
        )
        // Single-file families ignore [w] (fake-bold downstream).
        PersianFont.VAZIR_CODE -> FontFamily(Font(R.font.vazir_code))
        PersianFont.GANDOM -> FontFamily(Font(R.font.gandom))
        PersianFont.NAHID -> FontFamily(Font(R.font.nahid))
        PersianFont.TANHA -> FontFamily(Font(R.font.tanha))
        PersianFont.LALEZAR -> FontFamily(Font(R.font.lalezar))
        // English / Latin (2026-09-06)
        PersianFont.ROBOTO -> FontFamily(Font(R.font.roboto))
        PersianFont.OPEN_SANS -> FontFamily(Font(R.font.open_sans))
        PersianFont.MONTSERRAT -> FontFamily(Font(R.font.montserrat))
        PersianFont.POPPINS -> FontFamily(Font(R.font.poppins))
        PersianFont.INTER -> FontFamily(Font(R.font.inter))
        PersianFont.OSWALD -> FontFamily(Font(R.font.oswald))
        PersianFont.BEBAS_NEUE -> FontFamily(Font(R.font.bebas_neue))
        PersianFont.ANTON -> FontFamily(Font(R.font.anton))
        PersianFont.LOBSTER -> FontFamily(Font(R.font.lobster))
        PersianFont.PLAYFAIR_DISPLAY -> FontFamily(Font(R.font.playfair_display))
    }

/**
 * Resolve **any** persisted `fontFamily` string — bundled or
 * user-imported — to a Compose [FontFamily].
 *
 * This is the Compose counterpart of
 * `CaptionRenderer.resolveTypeface`, and the two must agree: the canvas
 * preview is drawn by `CaptionRenderer` (not by Compose text), so a
 * mismatch here would show up as the *studio cards and picker labels*
 * disagreeing with the canvas and the export. Both sides therefore
 * resolve an imported font from the same file path, and both fall back
 * to [PersianFont.VAZIR] when the file is gone.
 *
 * @param fontFamily the persisted reference: a [PersianFont] enum name
 *   (e.g. `"VAZIR"`) or a custom reference (`"custom:<uuid>"`).
 * @param customPathFor maps a `custom:<id>` reference to a file path,
 *   or null when it is unknown/unreadable. Supplied by the caller
 *   (typically `CustomFontLocator::pathFor`) because this is a
 *   `@Composable`-adjacent helper and should not reach into global
 *   state implicitly at every call.
 */
fun resolveFontFamily(
    fontFamily: String?,
    customPathFor: (String) -> String? = { null }
): FontFamily {
    if (CustomFont.isCustomFontId(fontFamily)) {
        val path = customPathFor(fontFamily!!)
        if (!path.isNullOrBlank()) {
            // `Font(File)` is the Compose entry point that loads a font
            // from the filesystem. Wrapped defensively: a file that
            // vanished between the check and here must degrade to the
            // default family rather than throw during composition.
            val family = runCatching {
                FontFamily(Font(java.io.File(path)))
            }.getOrNull()
            if (family != null) return family
        }
        // Unknown / deleted / unreadable import → the same default the
        // renderer uses for a Persian caption.
        return PersianFont.VAZIR.toFontFamily()
    }
    return PersianFont.from(fontFamily).toFontFamily()
}
