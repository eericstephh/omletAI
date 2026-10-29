package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.PersianFont

/**
 * PHASE 5 — bundled-font fallback registry.
 *
 * The JSON catalog names fonts that may not ship in the APK
 * (Archivo, IRANSansMono, JetBrainsMono, Orbitron, Caveat today).
 * A missing font must NEVER crash, NEVER fall back to the system
 * default for Persian (broken joining on some devices), and NEVER
 * change the caption size (size lives in the style, not the
 * family — swapping families keeps metrics stable enough that no
 * clipping appears).
 *
 * Rules (single source of truth — the mapper, the preview and the
 * export renderer all delegate here):
 *  1. exact bundled match (display name or enum name, case- and
 *     space-insensitive) always wins;
 *  2. otherwise Persian text pins bundled Vazirmatn (full Persian
 *     glyphs, weight-aware);
 *  3. otherwise Latin pins bundled Roboto (neutral grotesque,
 *     closest to Inter/system sans);
 *  4. blank/garbage input follows rules 2–3 (never null).
 *
 * Pure Kotlin — JVM unit-tested.
 */
object FontFallback {

    /** Bundled match or null (no guessing here). */
    fun matchBundled(name: String): PersianFont? {
        val key = name.trim().lowercase().replace(" ", "")
        if (key.isEmpty()) return null
        return PersianFont.entries.firstOrNull { font ->
            font.displayName.lowercase() == name.trim().lowercase() ||
                font.displayName.lowercase().replace(" ", "") == key ||
                font.name.lowercase() == key
        }
    }

    /**
     * Resolve one requested family for a script. [isPersian] selects
     * the safe default on a miss. Never returns DEFAULT (which would
     * mean "system font" downstream).
     */
    fun familyFor(requested: String, isPersian: Boolean): PersianFont =
        matchBundled(requested) ?: if (isPersian) PersianFont.VAZIR else PersianFont.ROBOTO

    /** Template-level resolution: fa map → en map → Vazirmatn. */
    fun templateFamily(fontMapFa: String, fontMapEn: String): PersianFont =
        matchBundled(fontMapFa)
            ?: matchBundled(fontMapEn)
            ?: PersianFont.VAZIR

    /** JSON weight name → CSS weight value (unknown → 400). */
    fun weightValue(weight: String): Int = when (weight.trim().lowercase()) {
        "medium" -> 500
        "semibold" -> 600
        "bold" -> 700
        "extrabold" -> 800
        "black" -> 900
        else -> 400 // regular + anything unknown
    }

    /**
     * minSdk-safe weight file resolution: real bundled TTF resource
     * names (no `Typeface.create(family, weight)` — that needs API
     * 28+ and minSdk is 24). Unknown combinations fall back to the
     * family's base file; families with a single file always return
     * their base (emphasis comes from fake-bold downstream).
     */
    fun weightResName(font: PersianFont, weight: Int): String = when (font) {
        PersianFont.VAZIR -> when {
            weight <= 200 -> "vazirmatn_light"
            weight <= 500 -> if (weight == 500) "vazirmatn_medium" else "vazirmatn"
            weight <= 700 -> if (weight >= 600) "vazirmatn_bold" else "vazirmatn_medium"
            weight <= 800 -> "vazirmatn_black"
            else -> "vazirmatn_black"
        }
        PersianFont.SAHEL -> when {
            weight <= 200 -> "sahel_light"
            weight >= 900 -> "sahel_black"
            weight >= 600 -> "sahel_bold"
            else -> "sahel"
        }
        PersianFont.SHABNAM -> when {
            weight <= 200 -> "shabnam_light"
            weight <= 500 -> if (weight == 500) "shabnam_medium" else "shabnam"
            weight >= 900 -> "shabnam_black"
            weight >= 600 -> "shabnam_bold"
            else -> "shabnam"
        }
        PersianFont.SAMIM -> when {
            weight >= 600 -> "samim_bold"
            weight == 500 -> "samim_medium"
            else -> "samim"
        }
        PersianFont.ESTEDAD -> when {
            weight >= 900 -> "estedad_black"
            weight >= 600 -> "estedad_bold"
            weight == 500 -> "estedad_medium"
            else -> "estedad"
        }
        PersianFont.PARASTOO -> if (weight >= 600) "parastoo_bold" else "parastoo"
        else -> font.resName
    }
}
