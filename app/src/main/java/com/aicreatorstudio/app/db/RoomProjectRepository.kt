package com.aicreatorstudio.app.db

import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.ClipTransform
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.CropPreset
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.MattingSettings
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VideoProject
import com.aicreatorstudio.app.model.VoiceCleanSettings
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.video.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomProjectRepository @Inject constructor(
    private val database: ProjectDatabase,
    // 2026-10-05 — orphaned-media cleanup on project deletion (B5).
    private val mediaStorage: com.aicreatorstudio.app.video.MediaStorageManager
) : ProjectRepository {

    private val projectDao = database.projectDao()

    // One app-lifetime scope for all fire-and-forget writes. This class is
    // a process-lifetime @Singleton, so the scope is never cancelled — the
    // point is that every write shares it instead of each call spawning an
    // untracked `CoroutineScope(Dispatchers.IO)` that nothing owns.
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _projects = MutableStateFlow<List<VideoProject>>(emptyList())
    override val projects: StateFlow<List<VideoProject>> = _projects

    init {
        loadProjects()
    }

    private fun loadProjects() {
        repoScope.launch {
            projectDao.getAllProjects().collect { projectEntities ->
                val projectsWithClips = mutableListOf<VideoProject>()
                for (entity in projectEntities) {
                    val clips = projectDao.getClipsByProjectId(entity.id)
                    projectsWithClips.add(toVideoProject(entity, clips))
                }
                _projects.value = projectsWithClips
            }
        }
    }

    override fun deleteProject(projectId: String) {
        repoScope.launch {
            // 2026-10-05 — orphaned-media cleanup (B5). Snapshot the doomed
            // project's clip URIs BEFORE the rows go, delete the rows, then
            // hand the URIs to the janitor together with every URI the
            // surviving projects still reference. Files inside the app's
            // media dir that nobody references any more are removed —
            // previously deleted projects left their imported copies on
            // disk forever, so storage grew unboundedly. The cleanup is
            // best-effort and never fails the deletion itself.
            val doomedUris = runCatching {
                projectDao.getClipsByProjectId(projectId).map { it.uri }
            }.getOrDefault(emptyList())

            projectDao.deleteProjectWithClips(projectId)
            _projects.value = _projects.value.filter { it.id != projectId }

            if (doomedUris.isNotEmpty()) {
                val stillReferenced = _projects.value
                    .flatMap { it.clips }
                    .mapTo(mutableSetOf()) { it.uri }
                mediaStorage.deleteUnreferencedProjectMedia(doomedUris, stillReferenced)
            }
        }
    }

    private fun toVideoProject(entity: VideoProjectEntity, clips: List<VideoClipEntity>): VideoProject {
        // v6: hydrate per-clip AI tool settings from the JSON blob
        // and merge them onto each [VideoClip] so the rest of the
        // editor (and the export pipeline) sees the same shape
        // regardless of how the data got persisted.
        val aiTools = EditorStateJson.aiToolsFromJson(entity.aiToolsJson)
        return VideoProject(
            id = entity.id,
            name = entity.name,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            clips = clips.map { toVideoClip(it, aiTools[it.id]) },
            canvasPreset = decodeCanvasPreset(entity.canvasPreset),
            canvasBackground = decodeCanvasBackground(entity.canvasBackground),
            textOverlays = EditorStateJson.textOverlaysFromJson(entity.textOverlaysJson),
            mediaOverlays = EditorStateJson.mediaOverlaysFromJson(entity.mediaOverlaysJson),
            audioClips = EditorStateJson.audioClipsFromJson(entity.audioClipsJson),
            lastPositionMs = entity.lastPositionMs,
            // v8 — the video progress bar. A v7 row carries `"{}"`, which
            // decodes to the disabled default, so an unmigrated project
            // behaves exactly as it did before the column existed.
            progressBarSettings = EditorStateJson.progressBarSettingsFromJson(
                entity.progressBarSettingsJson
            )
        )
    }

    /**
     * Lenient preset decode.
     *
     * 2026-09-30 — an unreadable name now falls back to
     * [com.aicreatorstudio.app.model.CanvasPreset.FREE], not `WIDE_16_9`.
     * A decode failure means we do not know the project's canvas, and
     * "unknown" has to resolve to "track the source". Falling back to a
     * fixed 16:9 asserted a landscape canvas on a project that may well be
     * portrait, which then letterboxed every clip in it — a wrong answer
     * that looked exactly like a right one. `FREE` is the only preset that
     * cannot be wrong about a source it has not seen.
     */
    private fun decodeCanvasPreset(name: String): com.aicreatorstudio.app.model.CanvasPreset {
        return runCatching {
            com.aicreatorstudio.app.model.CanvasPreset.valueOf(name)
        }.getOrDefault(com.aicreatorstudio.app.model.CanvasPreset.FREE)
    }

    /** Decodes "BLACK" / "BLUR" / "COLOR:<argb>" into a `model.CanvasBackground`. */
    private fun decodeCanvasBackground(encoded: String): com.aicreatorstudio.app.model.CanvasBackground {
        return when {
            encoded.equals("BLUR", ignoreCase = true) ->
                com.aicreatorstudio.app.model.CanvasBackground.Blur
            encoded.startsWith("COLOR:", ignoreCase = true) ->
                com.aicreatorstudio.app.model.CanvasBackground.SolidColor(
                    encoded.substringAfter(':').toLongOrNull()
                        ?: 0xFF000000L
                )
            else -> com.aicreatorstudio.app.model.CanvasBackground.Black
        }
    }

    private fun toVideoClip(
        entity: VideoClipEntity,
        aiTools: EditorStateJson.ClipAiTools? = null
    ): VideoClip {
        return VideoClip(
            id = entity.id,
            uri = entity.uri,
            fileName = entity.fileName,
            durationMs = entity.durationMs,
            width = entity.width,
            height = entity.height,
            fileSizeBytes = entity.fileSizeBytes,
            importedAtEpochMillis = entity.importedAtEpochMillis,
            volume = entity.volume,
            speed = entity.speed,
            trimStartMs = entity.trimStartMs,
            trimEndMs = if (entity.trimEndMs > 0) entity.trimEndMs else entity.durationMs,
            timelineStartMs = entity.timelineStartMs,
            isAudioOnly = entity.isAudioOnly,
            audioCategory = entity.audioCategory,
            transitionIn = entity.transitionIn,
            transitionOut = entity.transitionOut,
            animation = ClipAnimation.valueOf(entity.animation),
            cropPreset = CropPreset.valueOf(entity.cropPreset),
            cropAspectRatio = entity.cropAspectRatio,
            brightness = entity.brightness,
            contrast = entity.contrast,
            saturation = entity.saturation,
            rotation = entity.rotation,
            matting = aiTools?.matting ?: MattingSettings.Identity,
            autoFrame = aiTools?.autoFrame ?: AutoFrameSettings.Identity,
            cropKeyframes = aiTools?.cropKeyframes ?: CropKeyframe.Empty,
            // 2026-09-29 BUG FIX — `motionTrack` was decoded from the
            // blob, carried in `ClipAiTools`, and then dropped here.
            // The trajectory (`cropKeyframes`) survived, but the
            // settings that say "bind an overlay/mask to it" did not,
            // so a tracked overlay reverted to a plain Auto Frame crop
            // on reopen — bug class 1, "written but never rendered".
            motionTrack = aiTools?.motionTrack ?: MotionTrackSettings.Identity,
            voiceClean = aiTools?.voiceClean ?: VoiceCleanSettings.Identity,
            ocrResult = aiTools?.ocrResult,
            // PHASE A (Persistence Integrity) — the per-clip look
            // settings ride the blob. Absent in legacy blobs → each
            // falls back to the model default, which is exactly what
            // the clip had before this block existed. The legacy
            // brightness/contrast/saturation columns above are still
            // the authority for those three fields; ColorGrading
            // carries its own -1..+1 tone controls and is a separate
            // axis, so nothing is duplicated.
            opacity = aiTools?.look?.opacity ?: 1f,
            fadeInMs = aiTools?.look?.fadeInMs ?: 0L,
            fadeOutMs = aiTools?.look?.fadeOutMs ?: 0L,
            videoEffect = aiTools?.look?.videoEffect ?: VideoEffect.NONE,
            colorGrading = aiTools?.look?.colorGrading ?: ColorGrading.Identity,
            fillMode = aiTools?.look?.fillMode ?: FillMode.FIT,
            transform = aiTools?.look?.transform ?: ClipTransform(),
            // 2026-10-21 — the AI upscaler settings ride the same blob.
            // Absent in legacy blobs → Identity → exactly what those
            // clips looked like before the field existed.
            upscale = aiTools?.look?.upscale
                ?: com.aicreatorstudio.app.model.UpscalerSettings.Identity,
            // PHASE 7 — highlight ranges ride the same blob; absent →
            // empty (no highlights), exactly like every legacy row.
            highlightRanges = aiTools?.highlightRanges ?: emptyList(),
            // 2026-09-29 Speed Ramping. Absent in legacy blobs → null →
            // the constant `speed` column above still drives playback.
            speedCurve = aiTools?.speedCurve,
            // 2026-10-13 Voice Changer. Absent in legacy blobs → NONE,
            // which is exactly how those clips already sounded. Without
            // this line the effect would be decoded from the blob and
            // then dropped on the floor — bug class 1, "written but
            // never rendered", which is how `motionTrack` was lost
            // before it (see the note above).
            voiceEffect = aiTools?.voiceEffect ?: VoiceEffect.NONE
        )
    }

    private fun toProjectEntity(project: VideoProject): VideoProjectEntity {
        return VideoProjectEntity(
            id = project.id,
            name = project.name,
            createdAtEpochMillis = project.createdAtEpochMillis,
            updatedAtEpochMillis = System.currentTimeMillis()
        )
    }

    private fun toClipEntities(projectId: String, clips: List<VideoClip>): List<VideoClipEntity> {
        return clips.map { clip ->
            VideoClipEntity(
                id = clip.id,
                projectId = projectId,
                uri = clip.uri,
                fileName = clip.fileName,
                durationMs = clip.durationMs,
                width = clip.width,
                height = clip.height,
                fileSizeBytes = clip.fileSizeBytes,
                importedAtEpochMillis = clip.importedAtEpochMillis,
                volume = clip.volume,
                speed = clip.speed,
                trimStartMs = clip.trimStartMs,
                trimEndMs = clip.trimEndMs,
                timelineStartMs = clip.timelineStartMs,
                isAudioOnly = clip.isAudioOnly,
                audioCategory = clip.audioCategory,
                transitionIn = clip.transitionIn,
                transitionOut = clip.transitionOut,
                animation = clip.animation.name,
                cropPreset = clip.cropPreset.name,
                cropAspectRatio = clip.cropAspectRatio,
                brightness = clip.brightness,
                contrast = clip.contrast,
                saturation = clip.saturation,
                rotation = clip.rotation
            )
        }
    }

    override suspend fun createProjectFromClip(clip: VideoClip, name: String?): VideoProject {
        val projectId = UUID.randomUUID().toString()
        val project = VideoProject(
            id = projectId,
            name = name ?: defaultProjectName(),
            createdAtEpochMillis = System.currentTimeMillis(),
            clips = listOf(clip.copy(id = UUID.randomUUID().toString())),
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
            canvasPreset = com.aicreatorstudio.app.model.CanvasPreset
                .forSourceDimensions(clip.width, clip.height)
        )
        
        // Suspend write: the project row is guaranteed to exist when this
        // returns (callers invoke this from a coroutine before navigating).
        projectDao.insertProjectWithClips(
            toProjectEntity(project),
            toClipEntities(projectId, project.clips)
        )

        return project
    }

    override fun addClipToProject(projectId: String, clip: VideoClip) {
        repoScope.launch {
            val projectEntity = projectDao.getProjectById(projectId)
                ?: throw IllegalArgumentException("Project not found: $projectId")
            
            val newClip = clip.copy(id = UUID.randomUUID().toString())
            val clipEntities = toClipEntities(projectId, listOf(newClip))
            projectDao.insertClips(clipEntities)
            
            // Update project updated timestamp
            projectDao.updateProject(projectEntity.copy(updatedAtEpochMillis = System.currentTimeMillis()))
        }
    }

    override suspend fun getProject(projectId: String): VideoProject? {
        return try {
            val entity = projectDao.getProjectById(projectId)
            val clips = projectDao.getClipsByProjectId(projectId)
            entity?.let { toVideoProject(it, clips) }
        } catch (e: Exception) {
            null
        }
    }

    override fun saveClips(projectId: String, clips: List<VideoClip>) {
        repoScope.launch {
            projectDao.replaceClipsForProject(projectId, toClipEntities(projectId, clips))
        }
    }

    override fun saveProjectMeta(
        projectId: String,
        canvasPreset: com.aicreatorstudio.app.model.CanvasPreset,
        canvasBackground: com.aicreatorstudio.app.model.CanvasBackground,
        textOverlays: List<com.aicreatorstudio.app.model.TextOverlay>,
        mediaOverlays: List<com.aicreatorstudio.app.model.VideoOverlay>,
        audioClips: List<com.aicreatorstudio.app.model.AudioClip>,
        lastPositionMs: Long,
        progressBarSettings: com.aicreatorstudio.app.model.ProgressBarSettings
    ) {
        repoScope.launch {
            runCatching {
                val existing = projectDao.getProjectById(projectId) ?: return@launch
                val encodedBackground = when (canvasBackground) {
                    is com.aicreatorstudio.app.model.CanvasBackground.Black -> "BLACK"
                    is com.aicreatorstudio.app.model.CanvasBackground.Blur -> "BLUR"
                    is com.aicreatorstudio.app.model.CanvasBackground.SolidColor ->
                        "COLOR:${canvasBackground.colorValue}"
                }
                projectDao.updateProject(
                    existing.copy(
                        canvasPreset = canvasPreset.name,
                        canvasBackground = encodedBackground,
                        textOverlaysJson = EditorStateJson.textOverlaysToJson(textOverlays),
                        mediaOverlaysJson = EditorStateJson.mediaOverlaysToJson(mediaOverlays),
                        audioClipsJson = EditorStateJson.audioClipsToJson(audioClips),
                        lastPositionMs = lastPositionMs.coerceAtLeast(0L),
                        progressBarSettingsJson =
                            EditorStateJson.progressBarSettingsToJson(progressBarSettings),
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    override fun saveAiTools(
        projectId: String,
        tools: Map<String, EditorStateJson.ClipAiTools>
    ) {
        repoScope.launch {
            runCatching {
                val existing = projectDao.getProjectById(projectId) ?: return@launch
                projectDao.updateProject(
                    existing.copy(
                        aiToolsJson = EditorStateJson.aiToolsToJson(tools),
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun defaultProjectName(): String {
        val formatted = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        return "Project — $formatted"
    }
}