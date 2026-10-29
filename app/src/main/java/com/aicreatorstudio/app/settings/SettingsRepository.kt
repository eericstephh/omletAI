package com.aicreatorstudio.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.aicreatorstudio.app.ai.subtitle.SubtitlePostProcessSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaultExportQuality: String = "1080p",
    val autoSaveEnabled: Boolean = true,
    val snapToGrid: Boolean = true,
    val defaultTransitionDurationMs: Long = 500L,
    val hardwareAcceleration: Boolean = true,
    val hapticFeedback: Boolean = true,
    val previewFps: Int = 30,
    val defaultTextFont: String = "Vazirmatn",
    val showFrameCount: Boolean = false,
    val gridSnapSize: Int = 10,
    val maxUndoSteps: Int = 30,
    /**
     * PHASE B7 — master toggle for the Persian subtitle
     * post-processing pipeline (normalisation, repetition
     * collapse, silence filter, readability segmenter). When
     * `false`, the post-processor returns the input
     * transcription bit-for-bit (Identity). The per-step flags
     * and numeric limits live inside
     * [SubtitlePostProcessSettings.Default] and are not
     * surfaced to the settings UI in v1 — the master toggle
     * is the v1 control surface.
     */
    val subtitlePostProcessEnabled: Boolean = true
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            themeMode = ThemeMode.entries.getOrElse(prefs.getInt(KEY_THEME_MODE, 2)) { ThemeMode.DARK },
            defaultExportQuality = prefs.getString(KEY_EXPORT_QUALITY, "1080p") ?: "1080p",
            autoSaveEnabled = prefs.getBoolean(KEY_AUTO_SAVE, true),
            snapToGrid = prefs.getBoolean(KEY_SNAP_TO_GRID, true),
            defaultTransitionDurationMs = prefs.getLong(KEY_TRANSITION_DURATION, 500L),
            hardwareAcceleration = prefs.getBoolean(KEY_HW_ACCEL, true),
            hapticFeedback = prefs.getBoolean(KEY_HAPTIC, true),
            previewFps = prefs.getInt(KEY_PREVIEW_FPS, 30),
            defaultTextFont = prefs.getString(KEY_TEXT_FONT, "Vazirmatn") ?: "Vazirmatn",
            showFrameCount = prefs.getBoolean(KEY_FRAME_COUNT, false),
            gridSnapSize = prefs.getInt(KEY_GRID_SNAP, 10),
            maxUndoSteps = prefs.getInt(KEY_UNDO_STEPS, 30),
            subtitlePostProcessEnabled = prefs.getBoolean(KEY_SUBTITLE_POSTPROCESS_ENABLED, true)
        )
    }

    fun updateThemeMode(mode: ThemeMode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode.ordinal).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun updateExportQuality(quality: String) {
        prefs.edit().putString(KEY_EXPORT_QUALITY, quality).apply()
        _settings.value = _settings.value.copy(defaultExportQuality = quality)
    }

    fun updateAutoSave(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SAVE, enabled).apply()
        _settings.value = _settings.value.copy(autoSaveEnabled = enabled)
    }

    fun updateSnapToGrid(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SNAP_TO_GRID, enabled).apply()
        _settings.value = _settings.value.copy(snapToGrid = enabled)
    }

    fun updateTransitionDuration(durationMs: Long) {
        prefs.edit().putLong(KEY_TRANSITION_DURATION, durationMs).apply()
        _settings.value = _settings.value.copy(defaultTransitionDurationMs = durationMs)
    }

    fun updateHardwareAcceleration(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HW_ACCEL, enabled).apply()
        _settings.value = _settings.value.copy(hardwareAcceleration = enabled)
    }

    fun updateHapticFeedback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply()
        _settings.value = _settings.value.copy(hapticFeedback = enabled)
    }

    fun updatePreviewFps(fps: Int) {
        prefs.edit().putInt(KEY_PREVIEW_FPS, fps).apply()
        _settings.value = _settings.value.copy(previewFps = fps)
    }

    fun updateTextFont(font: String) {
        prefs.edit().putString(KEY_TEXT_FONT, font).apply()
        _settings.value = _settings.value.copy(defaultTextFont = font)
    }

    fun updateShowFrameCount(show: Boolean) {
        prefs.edit().putBoolean(KEY_FRAME_COUNT, show).apply()
        _settings.value = _settings.value.copy(showFrameCount = show)
    }

    fun updateGridSnapSize(size: Int) {
        prefs.edit().putInt(KEY_GRID_SNAP, size).apply()
        _settings.value = _settings.value.copy(gridSnapSize = size)
    }

    fun updateMaxUndoSteps(steps: Int) {
        prefs.edit().putInt(KEY_UNDO_STEPS, steps).apply()
        _settings.value = _settings.value.copy(maxUndoSteps = steps)
    }

    /**
     * Persist the user's manual Whisper model selection (Tiny /
     * Small / Medium / Large-v3) so it survives app restarts. The
     * stored value is the [WhisperModelSpec.name]; reads go
     * through [WhisperModelSpec.migrateSavedId] so an unknown or
     * legacy id can never corrupt the picker — it falls back to
     * the default instead.
     */
    fun updateSelectedWhisperModel(modelName: String) {
        prefs.edit().putString(KEY_SELECTED_WHISPER_MODEL, modelName).apply()
    }

    /**
     * The persisted manual selection, or null when the user has
     * never picked a model. Null means "no sticky preference" —
     * the caller falls back to the installed-model / default
     * policy.
     */
    fun savedWhisperModelName(): String? =
        prefs.getString(KEY_SELECTED_WHISPER_MODEL, null)

    /**
     * 2026-09-07 — persist the subtitle SPOKEN-LANGUAGE selection
     * (the Subs panel picker). The value is the
     * [com.aicreatorstudio.app.ai.SubtitleLanguage.name]. Previously
     * this reset to Persian on every app restart while the user
     * believed it was sticky.
     */
    fun updateSubtitleLanguage(languageName: String) {
        prefs.edit().putString(KEY_SUBTITLE_LANGUAGE, languageName).apply()
    }

    /** The persisted spoken-language name, or null when never picked. */
    fun savedSubtitleLanguageName(): String? =
        prefs.getString(KEY_SUBTITLE_LANGUAGE, null)

    /**
     * 2026-09-09 — persist the in-app UI language tag ("fa", "en",
     * "ar", or "" = follow the system). Read by
     * [com.aicreatorstudio.app.AICreatorStudioApp] at process start
     * so the locale applies before any Activity inflates resources.
     */
    fun updateAppLanguageTag(tag: String) {
        prefs.edit().putString(KEY_APP_LANGUAGE, tag).apply()
    }

    /** The persisted UI-language tag, or null when never changed. */
    fun savedAppLanguageTag(): String? =
        prefs.getString(KEY_APP_LANGUAGE, null)

    /**
     * 2026-09-09 — persist the subtitle ACCURACY MODE ("fast" /
     * "balanced" / "accurate"). Read by WhisperEngine at generation
     * time; maps onto real whisper.cpp decoder knobs (beam size +
     * temperature fallback width).
     */
    fun updateSubtitleAccuracyMode(modeKey: String) {
        prefs.edit().putString(KEY_SUBTITLE_ACCURACY_MODE, modeKey).apply()
    }

    /** The persisted accuracy-mode key, or null (→ engine default). */
    fun savedSubtitleAccuracyMode(): String? =
        prefs.getString(KEY_SUBTITLE_ACCURACY_MODE, null)

    /**
     * 2026-09-09 — persist the user's CUSTOM DICTIONARY (brand names,
     * people, jargon — one term per line). Injected into the decoder's
     * initial prompt so whisper.cpp preferentially emits the correct
     * spelling.
     */
    fun updateCustomDictionary(text: String) {
        prefs.edit().putString(KEY_CUSTOM_DICTIONARY, text).apply()
    }

    /** The raw dictionary text (lines), or null when never set. */
    fun savedCustomDictionary(): String? =
        prefs.getString(KEY_CUSTOM_DICTIONARY, null)

    // ---- 2026-09-11 «زیرنویس فارسی» studio template ----

    /**
     * Persist the template selected in the Subtitle Studio. When set,
     * EVERY newly generated AI subtitle is styled with it on commit.
     * Null = no auto-apply (raw generator style).
     */
    fun saveStudioTemplateId(id: String?) {
        if (id.isNullOrBlank()) prefs.edit().remove(KEY_STUDIO_TEMPLATE_ID).apply()
        else prefs.edit().putString(KEY_STUDIO_TEMPLATE_ID, id).apply()
    }

    /** The persisted studio template id, or null when unset. */
    fun studioTemplateId(): String? =
        prefs.getString(KEY_STUDIO_TEMPLATE_ID, null)?.takeIf { it.isNotBlank() }

    /**
     * 2026-09-16 BUGFIX — persist the FULL customized studio
     * template (JSON), not just its id. The Studio panel lets the
     * user tweak a pack (font / position / motion / colors); those
     * tweaks keep the built-in id, so id-only persistence silently
     * dropped them (and custom `custom_*` presets resolved to
     * nothing at all). The JSON snapshot preserves the exact look.
     */
    fun saveStudioTemplateJson(json: String?) {
        if (json.isNullOrBlank()) prefs.edit().remove(KEY_STUDIO_TEMPLATE_JSON).apply()
        else prefs.edit().putString(KEY_STUDIO_TEMPLATE_JSON, json).apply()
    }

    /** The persisted full studio template JSON, or null when unset. */
    fun studioTemplateJson(): String? =
        prefs.getString(KEY_STUDIO_TEMPLATE_JSON, null)?.takeIf { it.isNotBlank() }

    /**
     * PHASE B7 — flip the master toggle for the Persian
     * subtitle post-processing pipeline. When `false` the
     * pipeline runs as [SubtitlePostProcessSettings.Identity]
     * (no-op, returns the input bit-for-bit).
     */
    fun updateSubtitlePostProcessEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUBTITLE_POSTPROCESS_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(subtitlePostProcessEnabled = enabled)
    }

    /**
     * PHASE B7 — resolve the current
     * [SubtitlePostProcessSettings] from the master toggle.
     * The per-step flags and numeric limits always come from
     * [SubtitlePostProcessSettings.Default] in v1 — the
     * master toggle is the v1 control surface. A future v2
     * settings UI can split this into per-step switches.
     *
     * 2026-09-15 WORD-COUNT — the user's sticky Premiere-style
     * "words per caption" (0 = off) overrides the Default's 0 on
     * top of the resolved base, so the cap applies to BOTH the
     * enabled and (harmlessly) the Identity preset path is never
     * affected (Identity keeps every step off, so the segmenter
     * never runs).
     */
    fun currentSubtitlePostProcessSettings(): SubtitlePostProcessSettings {
        val base = if (_settings.value.subtitlePostProcessEnabled) {
            SubtitlePostProcessSettings.Default
        } else {
            SubtitlePostProcessSettings.Identity
        }
        val wordsPerCaption = savedSubtitleWordsPerCaption()
        // Words-per-caption is independent of the resegment flag:
        // even when post-processing is disabled (Identity), the
        // user's word count choice must be honoured. The segmenter
        // respects maxWordsPerSegment on its own; it does not
        // require other post-process steps to be enabled.
        if (wordsPerCaption > 0) {
            return base.copy(maxWordsPerSegment = wordsPerCaption)
        }
        return base
    }

    /**
     * 2026-09-15 WORD-COUNT — persist the user's Premiere-style
     * "words per caption" (0 = off). Applied by the segmenter on
     * every future AI-subtitle generation; survives restarts.
     */
    fun updateSubtitleWordsPerCaption(count: Int) {
        val clamped = count.coerceIn(0, 12)
        prefs.edit().putInt(KEY_SUBTITLE_WORDS_PER_CAPTION, clamped).apply()
    }

    /** The persisted words-per-caption (0 = off), never null. */
    fun savedSubtitleWordsPerCaption(): Int =
        prefs.getInt(KEY_SUBTITLE_WORDS_PER_CAPTION, 0)
        .coerceIn(0, 12)

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_EXPORT_QUALITY = "export_quality"
        private const val KEY_AUTO_SAVE = "auto_save"
        private const val KEY_SNAP_TO_GRID = "snap_to_grid"
        private const val KEY_TRANSITION_DURATION = "transition_duration"
        private const val KEY_HW_ACCEL = "hardware_acceleration"
        private const val KEY_HAPTIC = "haptic_feedback"
        private const val KEY_PREVIEW_FPS = "preview_fps"
        private const val KEY_TEXT_FONT = "text_font"
        private const val KEY_FRAME_COUNT = "show_frame_count"
        private const val KEY_GRID_SNAP = "grid_snap_size"
        private const val KEY_UNDO_STEPS = "max_undo_steps"
        private const val KEY_SUBTITLE_POSTPROCESS_ENABLED = "subtitle_postprocess_enabled"
        /**
         * Stable SharedPreferences key for the sticky Whisper
         * model selection. The value is a [WhisperModelSpec.name].
         * Pinned as a const so a rename fails loudly instead of
         * orphaning the user's saved choice.
         */
        const val KEY_SELECTED_WHISPER_MODEL = "selected_whisper_model"
        /** 2026-09-07 — sticky Subs-panel spoken-language selection. */
        const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
        /** 2026-09-09 — in-app UI language tag ("" = system default). */
        const val KEY_APP_LANGUAGE = "app_language"
        /** 2026-09-09 — subtitle decoder accuracy mode key. */
        const val KEY_SUBTITLE_ACCURACY_MODE = "subtitle_accuracy_mode"
        /** 2026-09-09 — user custom dictionary (newline-separated). */
        private const val KEY_CUSTOM_DICTIONARY = "custom_dictionary"
        /** 2026-09-11 — «زیرنویس فارسی» studio template id (null = off). */
        private const val KEY_STUDIO_TEMPLATE_ID = "subtitle_studio_template_id"
        /** 2026-09-16 — full customized studio template JSON (see above). */
        private const val KEY_STUDIO_TEMPLATE_JSON = "subtitle_studio_template_json"
        /** 2026-09-15 — Premiere-style words-per-caption (0 = off). */
        private const val KEY_SUBTITLE_WORDS_PER_CAPTION = "subtitle_words_per_caption"
        // 2026-09-13 GROQ REBUILD — the Cloudflare Worker URL/token
        // keys were removed with the Worker itself; the Groq API key
        // lives encrypted in GroqApiKeyStore (Android Keystore).
    }
}
