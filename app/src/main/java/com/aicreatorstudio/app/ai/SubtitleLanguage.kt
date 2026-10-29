package com.aicreatorstudio.app.ai

/**
 * 2026-09-06 — subtitle language selection.
 *
 * The two production Whisper checkpoints (Medium / Large-v3) are
 * multilingual (99 languages); whisper.cpp receives the language hint
 * through `whisper_full_params.language` (already wired: lockfile
 * `language` → [CatalogEntry] → JNI). This enum lets the USER pick
 * the spoken language of the clip so the decoder does not waste time
 * auto-detecting and produces far more stable word timings.
 *
 * The catalog entry's `language` (currently "fa") stays as the
 * FALLBACK for API compatibility; the engine overrides it with the
 * user's selection when one is set ([Selection.code]).
 *
 * Only high-value languages ship in v1 — adding a language is a
 * one-line edit here (the code must be a whisper.cpp language id,
 * lowercase, e.g. "en", "ar", "tr", "ur").
 */
enum class SubtitleLanguage(
    val code: String,
    val displayName: String,
    /** Right-to-left script — used by the renderer for BiDi hints. */
    val rtl: Boolean = false
) {
    PERSIAN("fa", "فارسی", rtl = true),
    ENGLISH("en", "English"),
    ARABIC("ar", "العربية", rtl = true),
    TURKISH("tr", "Türkçe"),
    GERMAN("de", "Deutsch"),
    FRENCH("fr", "Français"),
    SPANISH("es", "Español"),
    RUSSIAN("ru", "Русский"),
    HINDI("hi", "हिन्दी"),
    URDU("ur", "اردو", rtl = true),
    CHINESE("zh", "中文"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português");

    companion object {
        /** Resolve a persisted code back to the enum; unknown → PERSIAN. */
        fun from(code: String?): SubtitleLanguage =
            entries.firstOrNull { it.code == code }
                ?: entries.firstOrNull { it.name == code }
                ?: PERSIAN
    }
}
