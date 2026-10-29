package com.aicreatorstudio.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicreatorstudio.app.audio.AudioPairingService
import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.ImportVideoUiState
import com.aicreatorstudio.app.video.MediaStorageManager
import com.aicreatorstudio.app.video.ProjectRepository
import com.aicreatorstudio.app.video.VideoMetadataExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Import Video screen.
 *
 * Owns the whole import flow end to end: reacting to a picked [Uri],
 * extracting its metadata off the main thread, surfacing permission and
 * error states, and saving the result into [ProjectRepository] once the
 * user confirms. It does not launch pickers or request permissions itself —
 * those need an Activity/Context, so the screen handles them and reports
 * the outcome back here, keeping this class Android-UI-free and testable.
 */
@HiltViewModel
class ImportVideoViewModel @Inject constructor(
    private val metadataExtractor: VideoMetadataExtractor,
    private val projectRepository: ProjectRepository,
    private val mediaStorage: MediaStorageManager,
    private val audioPairing: AudioPairingService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportVideoUiState())
    val uiState: StateFlow<ImportVideoUiState> = _uiState.asStateFlow()

    companion object {
        const val MAX_IMPORT_DURATION_MS = 5 * 60 * 1000L
    }

    /** Called once the system/legacy picker returns a video URI (or null if the user backed out). */
    fun onVideoPicked(uri: Uri?) {
        if (uri == null) return
        onVideosPicked(listOf(uri))
    }

    /**
     * 2026-10-05 — multi-select import. The first valid video CREATES the
     * project (the pre-existing single-pick behaviour); every subsequent
     * one is APPENDED at the end of the timeline — the editor's own
     * "+"-button path, so a Reels batch lands as one multi-clip draft in a
     * single pick. Invalid or over-length files are skipped (counted into
     * the error message) rather than failing the whole batch; a batch in
     * which nothing is valid leaves the old error state.
     */
    fun onVideosPicked(uris: List<Uri>) {
        val candidates = uris.filterNotNull().distinct()
        if (candidates.isEmpty()) return

        _uiState.update {
            it.copy(
                isLoadingMetadata = true,
                selectedClip = null,
                errorMessage = null,
                savedProjectId = null,
                permissionRequired = false
            )
        }

        viewModelScope.launch {
            var project: com.aicreatorstudio.app.model.VideoProject? = null
            val pairedAudio = mutableListOf<AudioClip>()
            val failed = mutableListOf<Uri>()

            for (uri in candidates) {
                runCatching { metadataExtractor.extract(uri) }
                    .mapCatching { clip ->
                        if (clip.durationMs > MAX_IMPORT_DURATION_MS) {
                            error("ویدیو بیش از ۵ دقیقه است — حداکثر طول مجاز ۵ دقیقه")
                        }
                        // Copy into app-owned storage BEFORE saving: the
                        // picker's content:// grant is transient and dies
                        // with the task, which made saved projects
                        // unplayable on reopen (see the single-pick path).
                        val localUri = mediaStorage.importToAppStorage(uri)
                        clip.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            uri = localUri.toString()
                        )
                    }
                    .onSuccess { clip ->
                        val current = project
                        if (current == null) {
                            project = projectRepository.createProjectFromClip(clip)
                        } else {
                            val nextStart = current.clips.maxOfOrNull { c ->
                                c.timelineStartMs.coerceAtLeast(0L) + c.effectiveDurationMs
                            } ?: 0L
                            val placed = clip.copy(timelineStartMs = nextStart)
                            val updated = current.copy(clips = current.clips + placed)
                            projectRepository.saveClips(current.id, updated.clips)
                            project = updated
                        }
                        // Same pairing probe the single-pick path runs: a
                        // silent clip gets NO fabricated placeholder (the
                        // editor's loader re-seeds missing ones on open).
                        val local = runCatching { Uri.parse(clip.uri) }.getOrNull()
                        if (local != null) {
                            audioPairing.pairForVideo(
                                videoUri = local,
                                videoClipId = clip.id,
                                startTimeMs = clip.timelineStartMs.coerceAtLeast(0L),
                                durationMs = clip.effectiveDurationMs.coerceAtLeast(0L),
                                fileName = clip.fileName
                            )?.let { pairedAudio.add(it) }
                        }
                    }
                    .onFailure { failed.add(uri) }
            }

            val finished = project
            if (finished == null) {
                _uiState.update {
                    it.copy(
                        isLoadingMetadata = false,
                        errorMessage = if (failed.isNotEmpty())
                            "هیچ ویدیویی ذخیره نشد — فایل‌ها قابل خواندن نبودند"
                        else
                            "ذخیره ویدیو ناموفق بود"
                    )
                }
                return@launch
            }

            if (pairedAudio.isNotEmpty()) {
                projectRepository.saveProjectMeta(
                    projectId = finished.id,
                    canvasPreset = finished.canvasPreset,
                    canvasBackground = finished.canvasBackground,
                    textOverlays = finished.textOverlays,
                    mediaOverlays = finished.mediaOverlays,
                    audioClips = finished.audioClips + pairedAudio,
                    lastPositionMs = finished.lastPositionMs,
                    // A brand-new project has never opened the editor, so
                    // the bar is at its disabled default — carried through
                    // explicitly rather than relying on a parameter
                    // default, which would silently reset the setting on
                    // any future call site that forgets it.
                    progressBarSettings = finished.progressBarSettings
                )
            }

            _uiState.update {
                it.copy(
                    isLoadingMetadata = false,
                    selectedClip = finished.clips.firstOrNull(),
                    savedProjectId = finished.id,
                    errorMessage = if (failed.isNotEmpty())
                        "${failed.size} فایل نادیده گرفته شد (خواندن ناموفق یا بیش از ۵ دقیقه)"
                    else
                        null
                )
            }
        }
    }

    /** Called by the screen when a storage permission it requested was denied. */
    fun onPermissionDenied(permanentlyDenied: Boolean) {
        _uiState.update {
            it.copy(permissionRequired = true, permissionPermanentlyDenied = permanentlyDenied)
        }
    }

    /** Called by the screen once a previously-required permission is granted. */
    fun onPermissionGranted() {
        _uiState.update { it.copy(permissionRequired = false, permissionPermanentlyDenied = false) }
    }

    /** Saves the currently selected clip as a new project. */
    fun saveToProject() {
        val clip = _uiState.value.selectedClip ?: return
        viewModelScope.launch {
            // 2026-10-04 — the DB write is suspending now, so navigation
            // (driven by `savedProjectId`) fires only after the project
            // row actually exists; previously this runBlocking'd the DB
            // on the main thread.
            val project = projectRepository.createProjectFromClip(clip)
            _uiState.update { it.copy(savedProjectId = project.id) }
            // PART 14: after the project shell is in place, probe the
            // imported video for an audio track. If one exists, attach
            // a paired AudioClip at the same startTimeMs / durationMs
            // and persist it via saveProjectMeta. If no audio exists
            // (silent clip, image import, raw music file) we do NOT
            // fabricate a silent placeholder — that would be the
            // "fake functionality" the brief explicitly forbids.
            val localUri = runCatching { Uri.parse(clip.uri) }.getOrNull()
                ?: return@launch
            val paired: AudioClip? = audioPairing.pairForVideo(
                videoUri = localUri,
                videoClipId = clip.id,
                startTimeMs = clip.timelineStartMs.coerceAtLeast(0L),
                durationMs = clip.effectiveDurationMs.coerceAtLeast(0L),
                fileName = clip.fileName
            )
            if (paired != null) {
                val current = projectRepository.getProject(project.id) ?: return@launch
                projectRepository.saveProjectMeta(
                    projectId = project.id,
                    canvasPreset = current.canvasPreset,
                    canvasBackground = current.canvasBackground,
                    textOverlays = current.textOverlays,
                    mediaOverlays = current.mediaOverlays,
                    audioClips = listOf(paired),
                    lastPositionMs = current.lastPositionMs,
                    progressBarSettings = current.progressBarSettings
                )
            }
        }
    }

    /** Discards the current selection so the user can pick a different video. */
    fun clearSelection() {
        _uiState.update {
            it.copy(selectedClip = null, errorMessage = null, savedProjectId = null)
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
