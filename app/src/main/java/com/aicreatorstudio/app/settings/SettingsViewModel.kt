package com.aicreatorstudio.app.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    fun updateThemeMode(mode: ThemeMode) = settingsRepository.updateThemeMode(mode)
    fun updateExportQuality(quality: String) = settingsRepository.updateExportQuality(quality)
    fun updateAutoSave(enabled: Boolean) = settingsRepository.updateAutoSave(enabled)
    fun updateSnapToGrid(enabled: Boolean) = settingsRepository.updateSnapToGrid(enabled)
    fun updateTransitionDuration(durationMs: Long) = settingsRepository.updateTransitionDuration(durationMs)
    fun updateHardwareAcceleration(enabled: Boolean) = settingsRepository.updateHardwareAcceleration(enabled)
    fun updateHapticFeedback(enabled: Boolean) = settingsRepository.updateHapticFeedback(enabled)
    fun updatePreviewFps(fps: Int) = settingsRepository.updatePreviewFps(fps)
    fun updateTextFont(font: String) = settingsRepository.updateTextFont(font)
    fun updateShowFrameCount(show: Boolean) = settingsRepository.updateShowFrameCount(show)
    fun updateGridSnapSize(size: Int) = settingsRepository.updateGridSnapSize(size)
    fun updateMaxUndoSteps(steps: Int) = settingsRepository.updateMaxUndoSteps(steps)
}
