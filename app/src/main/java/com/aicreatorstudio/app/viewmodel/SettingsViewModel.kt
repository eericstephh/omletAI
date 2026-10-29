package com.aicreatorstudio.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicreatorstudio.app.ai.AiModelCatalog
import com.aicreatorstudio.app.ai.AiModelInstaller
import com.aicreatorstudio.app.ai.AiModels
import com.aicreatorstudio.app.settings.AppSettings
import com.aicreatorstudio.app.settings.SettingsRepository
import com.aicreatorstudio.app.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // ------------------------------------------------------------------
    // 2026-09-06 — AI model downloads. Every model the app can use is
    // listed here with its real install state + download progress, and
    // a one-tap download action. This is the single download surface
    // for the whole app (the editor panels reuse the same installer).
    // ------------------------------------------------------------------

    /** One row in the "AI Models / Downloads" list. */
    data class ModelDownloadRow(
        val id: String,
        val displayName: String,
        val description: String,
        val sizeLabel: String,
        val installed: Boolean,
        val downloading: Boolean,
        val progress: Float
    )

    private val _modelRows = MutableStateFlow<List<ModelDownloadRow>>(emptyList())
    val modelRows: StateFlow<List<ModelDownloadRow>> = _modelRows.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadProgress = mutableMapOf<String, Float>()
    private val installer by lazy { AiModelInstaller(appContext) }

    init {
        refreshModelRows()
    }

    /** Re-read install state for every catalog model. Cheap SHA checks. */
    fun refreshModelRows() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val rows = AiModelCatalog.all.map { spec ->
                val progress = downloadProgress[spec.id]
                ModelDownloadRow(
                    id = spec.id,
                    displayName = spec.displayName,
                    description = spec.subtitle ?: "AI model",
                    sizeLabel = formatSize(spec.minSizeBytes),
                    installed = AiModels.isVerifiedInstalled(appContext, spec),
                    downloading = progress != null,
                    progress = progress ?: 0f
                )
            }
            _modelRows.value = rows
        }
    }

    /** One-tap download for a model row. A second tap is ignored. */
    fun downloadModel(rowId: String) {
        if (downloadJobs.containsKey(rowId)) return
        val spec = AiModelCatalog.byId(rowId) ?: return
        downloadProgress[rowId] = 0f
        refreshModelRows()
        downloadJobs[rowId] = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val outcome = installer.installWithManifest(spec) { p ->
                downloadProgress[rowId] = p.coerceIn(0f, 1f)
                refreshModelRows()
            }
            if (outcome.isFailed) {
                downloadProgress.remove(rowId)
            }
            downloadJobs.remove(rowId)
            refreshModelRows()
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "${mb.toInt()} MB"
    }

    fun updateThemeMode(mode: ThemeMode) {
        settingsRepository.updateThemeMode(mode)
        _settings.update { it.copy(themeMode = mode) }
    }

    fun updateSnapToGrid(enabled: Boolean) {
        settingsRepository.updateSnapToGrid(enabled)
        _settings.update { it.copy(snapToGrid = enabled) }
    }

    fun updateGridSnapSize(size: Int) {
        settingsRepository.updateGridSnapSize(size)
        _settings.update { it.copy(gridSnapSize = size) }
    }

    fun updateTransitionDuration(durationMs: Long) {
        settingsRepository.updateTransitionDuration(durationMs)
        _settings.update { it.copy(defaultTransitionDurationMs = durationMs) }
    }

    fun updateMaxUndoSteps(steps: Int) {
        settingsRepository.updateMaxUndoSteps(steps)
        _settings.update { it.copy(maxUndoSteps = steps) }
    }

    fun updateExportQuality(quality: String) {
        settingsRepository.updateExportQuality(quality)
        _settings.update { it.copy(defaultExportQuality = quality) }
    }

    fun updateHardwareAcceleration(enabled: Boolean) {
        settingsRepository.updateHardwareAcceleration(enabled)
        _settings.update { it.copy(hardwareAcceleration = enabled) }
    }

    fun updatePreviewFps(fps: Int) {
        settingsRepository.updatePreviewFps(fps)
        _settings.update { it.copy(previewFps = fps) }
    }

    fun updateShowFrameCount(show: Boolean) {
        settingsRepository.updateShowFrameCount(show)
        _settings.update { it.copy(showFrameCount = show) }
    }

    fun updateAutoSave(enabled: Boolean) {
        settingsRepository.updateAutoSave(enabled)
        _settings.update { it.copy(autoSaveEnabled = enabled) }
    }

    fun updateHapticFeedback(enabled: Boolean) {
        settingsRepository.updateHapticFeedback(enabled)
        _settings.update { it.copy(hapticFeedback = enabled) }
    }

    fun updateTextFont(font: String) {
        settingsRepository.updateTextFont(font)
        _settings.update { it.copy(defaultTextFont = font) }
    }

    /**
     * 2026-09-09 — change the in-app UI language. Persists the tag;
     * the caller (Settings screen) then recreates the Activity so
     * [com.aicreatorstudio.app.settings.AppLanguage.applyToContext]
     * re-resolves every resource with the new locale.
     *
     * @param tag BCP-47 tag: "fa", "en", "ar" — or "" to follow the
     *   system. NOTE: the product default (first launch, nothing
     *   saved) is PERSIAN via [AppLanguage.DEFAULT_TAG].
     */
    fun updateAppLanguage(tag: String) {
        settingsRepository.updateAppLanguageTag(tag)
    }

    /** The persisted UI-language tag (never null — defaults to "fa"). */
    fun savedAppLanguageTag(): String =
        settingsRepository.savedAppLanguageTag() ?: com.aicreatorstudio.app.settings.AppLanguage.DEFAULT_TAG
}
