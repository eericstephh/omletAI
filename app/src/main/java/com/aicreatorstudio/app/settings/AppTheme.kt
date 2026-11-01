package com.aicreatorstudio.app.settings

/**
 * The app's three selectable themes.
 *
 * ## Why an enum of three and not the old DARK / LIGHT / SYSTEM switch
 *
 * The user asked for three selectable themes. All three are dark-authored
 * identities of the same cinematic studio — the screens hardcode the dark
 * base (`CinematicBlack`), so a LIGHT mode was never actually rendered as
 * light; the switch only re-skinned Material widgets. The honest replacement
 * is three accent identities, and the visual definition of each lives in
 * `ui/theme/Accents.kt` (this file owns the *choice*, that file owns the
 * *look*).
 *
 * ## Why the enum lives here
 *
 * It is a persisted user preference, like `AppLanguage`. The repository
 * stores its [key]; the theme layer maps it to colours. Keeping the enum out
 * of `ui/` means the settings layer never depends on Compose.
 *
 * ## Migration
 *
 * The legacy `ThemeMode` ordinal (0 = DARK, 1 = LIGHT, 2 = SYSTEM) maps to
 * [VIOLET_NIGHT]: every legacy value was observed on a dark-authored app,
 * and LIGHT has no successor. See `SettingsRepository.loadSettings`.
 */
enum class AppTheme(val key: String) {
    VIOLET_NIGHT("violet"),
    OCEAN("ocean"),
    ROSE("rose");

    companion object {
        /** Resolve a persisted key; anything unknown (or null) falls back to the brand default. */
        fun fromKey(key: String?): AppTheme =
            entries.firstOrNull { it.key == key } ?: VIOLET_NIGHT

        /**
         * Legacy `ThemeMode` ordinal (0 = DARK, 1 = LIGHT, 2 = SYSTEM).
         * Every legacy choice maps to the brand dark theme — LIGHT has no
         * successor, and there was never a real light rendering to keep.
         */
        fun fromLegacyThemeModeOrdinal(ordinal: Int): AppTheme = VIOLET_NIGHT
    }
}
