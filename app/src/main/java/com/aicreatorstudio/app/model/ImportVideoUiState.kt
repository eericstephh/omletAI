package com.aicreatorstudio.app.model

/**
 * UI state for the Import Video screen.
 *
 * Models every stage of the import flow explicitly so the screen can render
 * one state at a time instead of juggling loose booleans:
 * idle → (permission needed) → loading metadata → selected (ready to save) → saved.
 */
data class ImportVideoUiState(
    val isLoadingMetadata: Boolean = false,
    val selectedClip: VideoClip? = null,
    val permissionRequired: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val errorMessage: String? = null,
    val savedProjectId: String? = null
)
