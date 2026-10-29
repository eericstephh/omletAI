package com.aicreatorstudio.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.aicreatorstudio.app.settings.LocalizedStrings

/**
 * 2026-09-09 — localized string for Compose: resolves [key] against
 * the current in-app language ONCE per composition (keys are the
 * original Persian literals; the fallback is the key itself so an
 * untranslated entry always renders the original Persian text).
 *
 * Usage: `Text(text = t("تنظیمات"))`
 */
@Composable
fun t(key: String): String {
    // remember on the tag so a language change recomposes everything
    // that read strings through this helper.
    val tag = LocalizedStrings.currentTag
    return remember(tag, key) { LocalizedStrings.t(key) }
}
