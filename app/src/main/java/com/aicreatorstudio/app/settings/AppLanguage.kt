package com.aicreatorstudio.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * 2026-09-09 — in-app UI language override (no AppCompat needed).
 *
 * The chosen tag ("fa", "en", "ar", or "" = follow the system) is
 * persisted via [SharedPreferences] and applied by wrapping every
 * Activity's base context with a localized configuration
 * ([attach][applyToContext]). The host Activity calls [applyToContext]
 * from `attachBaseContext`; resources then resolve against the
 * overridden locale for the whole UI.
 *
 * Product default: PERSIAN. When the user has never picked a
 * language, the app runs in Persian regardless of the device
 * language; choosing «سیستم» in Settings removes the override.
 */
object AppLanguage {

    private const val PREFS = "app_language_prefs"
    private const val KEY_TAG = "app_language_tag"

    /** Product default: Persian UI. */
    const val DEFAULT_TAG: String = "fa"

    /**
     * Languages offered in Settings: (tag, native display name).
     * Native names so the option is findable in any UI language.
     * The empty tag means "follow the device language".
     */
    val SUPPORTED: List<Pair<String, String>> = listOf(
        "fa" to "فارسی",
        "en" to "English",
        "ar" to "العربية",
        "" to "سیستم (System)"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The persisted tag, or [DEFAULT_TAG] on first launch. */
    fun savedTag(context: Context): String =
        prefs(context).getString(KEY_TAG, DEFAULT_TAG) ?: DEFAULT_TAG

    /** Persist + remember. Call [applyToContext] on next Activity create. */
    fun saveTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_TAG, tag).apply()
    }

    /**
     * Wrap [base] with a configuration whose locale list starts with
     * the persisted app language (falling back to the system locales).
     * Call from `Activity.attachBaseContext`.
     */
    fun applyToContext(base: Context): Context {
        val tag = savedTag(base)
        if (tag.isBlank()) return base // follow the system
        val appLocale = Locale.forLanguageTag(tag)
        Locale.setDefault(appLocale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(appLocale)
        config.setLocales(android.os.LocaleList(appLocale))
        return base.createConfigurationContext(config)
    }
}
