package com.aicreatorstudio.app.video

import com.aicreatorstudio.app.db.EditorStateJson
import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.ProgressBarSettings
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoOverlay
import com.aicreatorstudio.app.model.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Single source of truth for video editing projects.
 *
 * This is the "project state" that imported videos get saved into, and
 * that the future timeline/editor and Home screen both read from. The
 * interface is intentionally storage-agnostic — [InMemoryProjectRepository]
 * is a placeholder implementation; swapping in a persistent one (Room,
 * DataStore, etc.) later is a one-file change since nothing else depends
 * on the storage mechanism.
 */
interface ProjectRepository {

    /** All projects, newest first. */
    val projects: StateFlow<List<VideoProject>>

    /**
     * Creates a new project containing a single imported clip.
     * Suspending: implementations do DB I/O, so callers must invoke this
     * from a coroutine — the project row is guaranteed to be persisted
     * when this returns.
     */
    suspend fun createProjectFromClip(clip: VideoClip, name: String? = null): VideoProject

    /** Appends an imported clip to an existing project. */
    fun addClipToProject(projectId: String, clip: VideoClip)

    /**
     * Looks up a single project by id, or null if it doesn't exist.
     * Suspending: implementations do DB I/O.
     */
    suspend fun getProject(projectId: String): VideoProject?

    /** Replaces all clips of a project (used for timeline edits). */
    fun saveClips(projectId: String, clips: List<VideoClip>)

    /**
     * Persists the editor's project-level state (canvas preset/background,
     * captions/AI subtitles, media overlays, audio clips, playhead
     * position, progress-bar settings) so a saved project reopens exactly
     * as it was left. Fire-and-forget from the caller's perspective;
     * implementations must be safe to call often.
     *
     * [progressBarSettings] has **no default** on purpose: the six-knob
     * blob is easy to forget at a call site, and a silent default would
     * make the bar reset to "off" on the next save from whichever caller
     * omitted it. Making it required turns that into a compile error.
     */
    fun saveProjectMeta(
        projectId: String,
        canvasPreset: CanvasPreset,
        canvasBackground: CanvasBackground,
        textOverlays: List<TextOverlay>,
        mediaOverlays: List<VideoOverlay>,
        audioClips: List<AudioClip>,
        lastPositionMs: Long,
        progressBarSettings: ProgressBarSettings
    )

    /**
     * Persists the per-clip AI tool settings (matting / crop keyframes /
     * voice clean). Stored on the project row as a single JSON blob —
     * matches the pattern of [saveProjectMeta] for the rest of the
     * editor state. Fire-and-forget; safe to call after every AI tool
     * run completes so the work survives process death.
     */
    fun saveAiTools(projectId: String, tools: Map<String, EditorStateJson.ClipAiTools>)

    /** Permanently deletes a project and all its clips. */
    fun deleteProject(projectId: String)
}

/**
 * In-memory placeholder implementation. Project data does not survive
 * process death yet — that's expected at this stage; persistence is a
 * later, separate concern behind the same [ProjectRepository] interface.
 */
class InMemoryProjectRepository @Inject constructor() : ProjectRepository {

    private val _projects = MutableStateFlow<List<VideoProject>>(emptyList())
    override val projects: StateFlow<List<VideoProject>> = _projects

    override suspend fun createProjectFromClip(clip: VideoClip, name: String?): VideoProject {
        val project = VideoProject(
            id = UUID.randomUUID().toString(),
            name = name ?: defaultProjectName(),
            createdAtEpochMillis = System.currentTimeMillis(),
            clips = listOf(clip),
            // 2026-09-30 — the canvas ADOPTS the imported clip's own
            // orientation. `forSourceDimensions` was written for exactly
            // this (its KDoc says it "replaced the previous hard-coded
            // WIDE_16_9 project default") and had 20 unit tests but no
            // production call site, so the first-import canvas was never
            // actually derived from the source.
            //
            // A canonical ratio (16:9 / 9:16 / 1:1 / 4:5) snaps to its
            // preset, which also pins the EXPORT frame to that preset's
            // `exportWidth` × `exportHeight` — see
            // [com.aicreatorstudio.app.export.ExportFrame]. Anything
            // non-canonical (cinematic 2.39:1, unusual sensors) or unknown
            // returns FREE, which tracks the source 1:1.
            canvasPreset = CanvasPreset.forSourceDimensions(clip.width, clip.height)
        )
        _projects.value = listOf(project) + _projects.value
        return project
    }

    override fun addClipToProject(projectId: String, clip: VideoClip) {
        _projects.value = _projects.value.map { project ->
            if (project.id == projectId) project.copy(clips = project.clips + clip) else project
        }
    }

    override suspend fun getProject(projectId: String): VideoProject? =
        _projects.value.firstOrNull { it.id == projectId }

    override fun saveClips(projectId: String, clips: List<VideoClip>) {
        _projects.value = _projects.value.map { project ->
            if (project.id == projectId) project.copy(clips = clips) else project
        }
    }

    override fun saveProjectMeta(
        projectId: String,
        canvasPreset: CanvasPreset,
        canvasBackground: CanvasBackground,
        textOverlays: List<TextOverlay>,
        mediaOverlays: List<VideoOverlay>,
        audioClips: List<AudioClip>,
        lastPositionMs: Long,
        progressBarSettings: ProgressBarSettings
    ) {
        _projects.value = _projects.value.map { project ->
            if (project.id == projectId) {
                project.copy(
                    canvasPreset = canvasPreset,
                    canvasBackground = canvasBackground,
                    textOverlays = textOverlays,
                    mediaOverlays = mediaOverlays,
                    audioClips = audioClips,
                    lastPositionMs = lastPositionMs,
                    progressBarSettings = progressBarSettings
                )
            } else project
        }
    }

    /**
     * In-memory save: merges the AI tool settings onto each affected
     * clip in the project. The Room implementation persists them as a
     * JSON blob instead — both paths keep the in-memory [VideoProject]
     * consistent so callers don't have to special-case storage.
     *
     * 2026-10-20 — EVERY FIELD IS NOW CARRIED.
     *
     * The merge used to copy only five of the eleven fields in
     * [EditorStateJson.ClipAiTools]: `motionTrack`, `ocrResult`,
     * `speedCurve`, `look` and `voiceEffect` were silently dropped.
     * The KDoc claimed "both paths keep the in-memory VideoProject
     * consistent", which was not true — and the difference only shows
     * up in the JVM tests and any future switch to the in-memory
     * repository, where a motion track or an OCR result would
     * disappear on the next save.
     */
    override fun saveAiTools(
        projectId: String,
        tools: Map<String, EditorStateJson.ClipAiTools>
    ) {
        _projects.value = _projects.value.map { project ->
            if (project.id != projectId) return@map project
            val newClips = project.clips.map { clip ->
                val t = tools[clip.id] ?: return@map clip
                clip.copy(
                    matting = t.matting,
                    autoFrame = t.autoFrame,
                    cropKeyframes = t.cropKeyframes,
                    motionTrack = t.motionTrack,
                    voiceClean = t.voiceClean,
                    ocrResult = t.ocrResult,
                    speedCurve = t.speedCurve,
                    // The look block is flattened onto the clip (the
                    // same mapping `toClipAiTools` uses in the
                    // opposite direction).
                    videoEffect = t.look.videoEffect,
                    colorGrading = t.look.colorGrading,
                    opacity = t.look.opacity,
                    fillMode = t.look.fillMode,
                    transform = t.look.transform,
                    fadeInMs = t.look.fadeInMs,
                    fadeOutMs = t.look.fadeOutMs,
                    highlightRanges = t.highlightRanges,
                    voiceEffect = t.voiceEffect
                )
            }
            project.copy(clips = newClips)
        }
    }

    override fun deleteProject(projectId: String) {
        _projects.value = _projects.value.filter { it.id != projectId }
    }

    private fun defaultProjectName(): String {
        val formatted = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
        return "Project — $formatted"
    }
}
