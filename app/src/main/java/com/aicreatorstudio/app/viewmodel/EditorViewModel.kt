package com.aicreatorstudio.app.viewmodel

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicreatorstudio.app.ai.WhisperModelSpec
import com.aicreatorstudio.app.ai.AiModelInstaller
import com.aicreatorstudio.app.ai.AiModelStatus
import com.aicreatorstudio.app.ai.AiModels
import com.aicreatorstudio.app.ai.AiPhase
import com.aicreatorstudio.app.ai.AiPhaseListener
import com.aicreatorstudio.app.ai.InstallOutcome
import com.aicreatorstudio.app.ai.ModelRegistry
import com.aicreatorstudio.app.ai.SubtitleGenerationState
import com.aicreatorstudio.app.ai.SubtitleGenerator
import com.aicreatorstudio.app.ai.WhisperEngine
import com.aicreatorstudio.app.ai.UnifiedModelInstaller
import com.aicreatorstudio.app.ai.subtitle.SubtitlePostProcessor
import com.aicreatorstudio.app.ai.upscale.EsrganUpscalePipeline
import com.aicreatorstudio.app.ai.upscale.UpscaleEngineRegistry
import com.aicreatorstudio.app.ai.upscale.UpscaleJob
import com.aicreatorstudio.app.ai.upscale.UpscaleProfile
import com.aicreatorstudio.app.ai.upscale.UpscaleProgress
import com.aicreatorstudio.app.ai.media.AudioExtractionOutcome
import com.aicreatorstudio.app.ai.media.AudioExtractionService
import com.aicreatorstudio.app.export.ExportConfig
import com.aicreatorstudio.app.export.ExportQuality
import com.aicreatorstudio.app.export.ExportState
import com.aicreatorstudio.app.export.UpscalerExportPresets
import com.aicreatorstudio.app.export.UpscalerExportState
import com.aicreatorstudio.app.export.VideoExportEngine
import com.aicreatorstudio.app.data.font.CustomFontRepository
import com.aicreatorstudio.app.model.AudioExtractionPlanner
import com.aicreatorstudio.app.model.CustomFont
import com.aicreatorstudio.app.model.CustomFontMessages
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.PairedAudio
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleMessages
import com.aicreatorstudio.app.model.SubtitleEditOps
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.withTemplate
import com.aicreatorstudio.app.model.UpscalerPreset
import com.aicreatorstudio.app.model.UpscalerSettings
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.model.PlaybackSpeedRange
import com.aicreatorstudio.app.util.BeatCutPlanner
import com.aicreatorstudio.app.util.BeatDetector

import com.aicreatorstudio.app.video.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aicreatorstudio.app.video.MediaStorageManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 2026-10-20 — outcome of [EditorViewModel.deleteAudioClip].
 *
 * An enum rather than a boolean because the UI has to say *why* a delete was
 * refused, and "false" conflated two unrelated situations: a stale id (nothing
 * to tell the user) and the soundtrack of a video clip (which needs an
 * explanation, see [PairedAudio]).
 */
enum class AudioDeleteOutcome {
    /** The object was removed from `project.audioClips` and persisted. */
    Deleted,

    /** No object with that id — already gone, or a stale selection. */
    NotFound,

    /**
     * The object is the soundtrack of a video clip (the "Video Audio" lane).
     * It cannot be removed: `seedVideoAudioClipsIfNeeded` rebuilds a paired
     * row for every video clip that lacks one, and the exporter re-extracts
     * the soundtrack from the video source. Muting the clip is the supported
     * way to silence it.
     */
    PairedAudio
}

/**
 * ViewModel for the video editor screen.
 *
 * Manages playback state, timeline position, and project data.
 * The actual video playback is handled by ExoPlayer in the screen,
 * but this ViewModel tracks the logical state and handles seeking.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val subtitleGenerator: SubtitleGenerator,
    private val whisperEngineProvider: dagger.Lazy<com.aicreatorstudio.app.ai.WhisperEngine>,
    private val exportEngine: VideoExportEngine,
    private val upscalerExportEngine: com.aicreatorstudio.app.export.UpscalerExportEngine,
    private val mediaStorage: MediaStorageManager,
    private val metadataExtractor: com.aicreatorstudio.app.video.VideoMetadataExtractor,
    private val aiToolRegistry: com.aicreatorstudio.app.ai.AiToolRegistry,
    private val upscaleEngineRegistry: UpscaleEngineRegistry,
    private val unifiedModelInstaller: UnifiedModelInstaller,
    private val aiModelInstaller: AiModelInstaller,
    private val settingsRepository: com.aicreatorstudio.app.settings.SettingsRepository,
    private val subtitleTemplateRepository:
        com.aicreatorstudio.app.data.subtitle.SubtitleTemplateRepository,
    private val jsonTemplateCatalog:
        com.aicreatorstudio.app.data.subtitle.JsonTemplateCatalog,
    private val audioExtractor: com.aicreatorstudio.app.ai.AudioExtractor,
    private val audioExtractionService: AudioExtractionService,
    /**
     * 2026-10-13 — the centralized logger. The «گزارش‌های فنی» panel in
     * Settings was empty for everything except export and Whisper,
     * because those two were the only writers. A failed AI tool, a
     * player error and a missing-media relink all told the user
     * something was wrong and then wrote nothing down, which left no
     * evidence to diagnose from. This ViewModel is where every one of
     * those failures already passes, so it now records them.
     */
    private val omeletLog: com.aicreatorstudio.app.logging.OmeletLog,
    private val customFontRepository: CustomFontRepository,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /**
     * Active AI upscale run, if any. Stored as a field (rather than
     * a state-flow in `_uiState`) so the cancel path can reach the
     * engine without going through the state machine.
     */
    private var activeUpscaleJob: UpscaleJob? = null

    private val projectId: String = checkNotNull(savedStateHandle["projectId"])

    /**
     * 2026-10-20 — deep-link state for the home screen's AI
     * capability tiles. [pendingInitialTool] is the panel waiting for
     * the project to finish loading; [initialToolApplied] makes the
     * request one-shot (a rotation must not re-open a panel the user
     * deliberately closed).
     */
    private var pendingInitialTool: com.aicreatorstudio.app.model.ToolPanel? = null
    private var initialToolApplied: Boolean = false

    /** Ids of text overlays that were created by AI subtitle generation. */
    private val aiSubtitleOverlayIds = mutableSetOf<String>()

    /**
     * PHASE B4 � ids of text overlays that were created by an
     * SRT import. The SRT import path produces
     * [com.aicreatorstudio.app.model.TextOverlay]s with empty
     * `words` (SRT has no per-word timing), so the existing
     * "AI subtitle" discriminator (`aiSubtitleOverlayIds`) is
     * not the right marker for them. Instead we keep a
     * separate set so the SRT export pipeline can pull
     * subtitle overlays regardless of their origin.
     *
     * The two sets are not mutually exclusive in principle
     * (a future "re-align AI captions to imported SRT" feature
     * might want both), but in v1 they are independent:
     * generating AI captions never adds to this set, and
     * importing SRT never adds to the AI set.
     */
    private val srtImportedOverlayIds = mutableSetOf<String>()

    /**
     * PHASE B3 � the active AI subtitle generation coroutine, if
     * any. Stored as a field (rather than relying on
     * `viewModelScope.coroutineContext.job`) so
     * [cancelSubtitleGeneration] can interrupt the in-flight
     * pipeline cleanly. Cancellation propagates into
     * `WhisperEngine.generateTranscription` via the
     * `coroutineContext[Job]?.ensureActive()` checks between
     * 30-second whisper windows.
     */
    private var subtitleGenerationJob: Job? = null

    private companion object {
        const val TAG_SUBS = "EditorViewModel"

        /**
         * `AiToolEngine.id` analogue for beat detection. Beat detection is
         * not a registered [com.aicreatorstudio.app.ai.AiToolEngine] (it runs
         * on a plain FFT rather than a bundled model), but it reuses the
         * per-tool state channel so the audio panel gets the same progress
         * bar / cancel / error surface for free.
         */
        const val TOOL_ID_BEATS = "beats"

        /**
         * Snap radius for beat markers, in milliseconds.
         *
         * Much tighter than the 80 ms clip-boundary snap on purpose: the whole
         * value of snapping to a beat is precision, so a cut that lands 40 ms
         * off the kick is exactly the defect this feature removes. A window
         * this narrow still catches the user's intent at every zoom level
         * while never stealing a drag that was aimed at a clip edge.
         */
        const val BEAT_SNAP_MS = 18L

        /**
         * PHASE A (Persistence Integrity) — the smallest span a clip may
         * occupy after a trim, in milliseconds.
         *
         * 1 ms rather than 0 because `trimEndMs <= 0` is the model's
         * "untrimmed, play to the end of the source" sentinel: letting a
         * drag reach 0 made the clip silently snap back to full length.
         * A bare 1 ms floor is invisible to the user but keeps every
         * write representable.
         */
        const val MIN_TRIM_MS = 1L

        /**
         * 2026-10-13 — the family a style falls back to when the custom
         * font it referenced is deleted.
         *
         * This is [PersianFont.VAZIR], and it is deliberately expressed as
         * the enum name (the value persisted in `fontFamily`) rather than
         * by looking up the `fa_01_clean` template: the default template
         * already ships `fontFamily = PersianFont.VAZIR.name`, and going
         * through the template catalog here would make a *style* fallback
         * depend on a *template* lookup that can legitimately fail.
         */
        val DEFAULT_PERSIAN_FONT_FAMILY: String = PersianFont.VAZIR.name
    }

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /** Decoupled undo/redo manager. */
    private val historyDelegate = HistoryDelegate(_uiState)

    // -- Domain delegates -----------------------------------
    val timeline = TimelineDelegate(_uiState, historyDelegate)
    val adjustment = AdjustmentDelegate(_uiState, historyDelegate)
    val subtitle = SubtitleDelegate(_uiState, historyDelegate)
    val transition = TransitionDelegate(_uiState, historyDelegate)
    val export = ExportDelegate(_uiState, exportEngine, viewModelScope)

    /**
     * 2026-10-23 — CRASH FIX. These fields are read and written by
     * [restoreAiState], which the `init` block starts on
     * `Dispatchers.IO`. A Kotlin property initializer runs in
     * declaration order, so a field declared *after* the `init` block is
     * still `null` when that coroutine runs — and `Dispatchers.IO` really
     * does run it concurrently with the rest of the constructor. That
     * race killed the app on launch:
     *
     * ```
     * FATAL EXCEPTION: DefaultDispatcher-worker-3
     * java.lang.NullPointerException: Attempt to invoke virtual method
     *   'java.lang.Object java.util.concurrent.ConcurrentHashMap.put(...)'
     *   on a null object reference
     *     at EditorViewModel.probeAiToolModelState(EditorViewModel.kt:447)
     *     at EditorViewModel.restoreAiState(EditorViewModel.kt:402)
     * ```
     *
     * It is timing-dependent, which is why it survived until now: the UI
     * thread only has to lose the race once. Declaring the fields
     * **before** `init` removes the race instead of narrowing it.
     */
    private val aiToolAvailabilityCache =
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var cachedAutoUpscaleProfile: UpscaleProfile? = null

    private var esrganSpecCache: ModelRegistry.Spec? = null

    init {
        // Resolve the sticky Whisper selection + probe every AI
        // tool's model state off the main thread (the checks
        // hash model files, so they must not run on the UI
        // thread). Until the probe lands, the UI shows the
        // static defaults (Medium selected, tools Idle), which
        // the panels render as "Download".
        viewModelScope.launch(Dispatchers.IO) { restoreAiState() }
        loadProject()
        // 2026-09-19 PHASE 4 — load the JSON template catalog once
        // (IO-safe, cached, never throws). Until READY the studio
        // panel renders a stable skeleton; EMPTY keeps the picker
        // crash-free with no flicker.
        viewModelScope.launch { restoreJsonCatalog() }
        // 2026-10-13 — mirror the imported-font list into UI state so the
        // font pickers can render the user's own fonts. The repository
        // loads its index eagerly on the IO dispatcher and publishes to
        // [CustomFontLocator]; this collection only *observes*, so the
        // first emission is already correct (no empty-list flash that
        // would momentarily re-point a saved `custom:<id>` style at the
        // bundled fallback).
        viewModelScope.launch {
            customFontRepository.getCustomFonts().collect { fonts ->
                _uiState.update { it.copy(customFonts = fonts) }
            }
        }
    }

    /**
     * Restore persisted AI state on app start (IO dispatcher):
     *
     *  1. Whisper selection � the user's sticky manual choice
     *     wins (never silently replaced). Without a saved
     *     choice, keep an already-installed model; otherwise
     *     fall back to the device-aware default (Medium).
     *  2. Whisper per-model statuses � recomputed from disk so
     *     a downloaded model shows READY after restart without
     *     re-downloading.
     *  3. AI tool model states (matting / autoframe / upscale) �
     *     probed from the engines/installer so the panels show
     *     the real Download-vs-Generate state on first open.
     *
     * All updates go through thread-safe `_uiState.update`, so
     * this is safe to run in the background while [loadProject]
     * populates the project on the main thread.
     */
    private fun restoreAiState() {
        // 0a. 2026-09-09 � restore the subtitle accuracy mode +
        // custom dictionary into the engine so generation honours
        // the user's persisted quality / vocabulary choices.
        whisperEngineProvider.get().accuracyMode = WhisperEngine.AccuracyMode.fromKey(
            settingsRepository.savedSubtitleAccuracyMode()
        )
        val dictText = settingsRepository.savedCustomDictionary().orEmpty()
        whisperEngineProvider.get().customDictionary = dictText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        _uiState.update {
            it.copy(
                subtitleAccuracyMode = whisperEngineProvider.get().accuracyMode,
                customDictionaryText = dictText,
                // 2026-09-15 � restore the sticky words-per-caption
                // (Premiere-style) so the Subs picker survives
                // restarts. The value is read by the post-processor
                // at generation time via currentSubtitlePostProcessSettings().
                subtitleWordsPerCaption = settingsRepository.savedSubtitleWordsPerCaption()
            )
        }

        // 0b. 2026-09-07 � restore the sticky spoken-language
        // selection so the Subs panel picker survives app restarts.
        settingsRepository.savedSubtitleLanguageName()?.let { saved ->
            val restored = com.aicreatorstudio.app.ai.SubtitleLanguage.from(saved)
            _uiState.update { it.copy(selectedSubtitleLanguage = restored) }
            subtitleGenerator.setSelectedLanguage(restored)
        }

        // 1. Selection: sticky preference first.
        val savedName = settingsRepository.savedWhisperModelName()
        if (savedName != null) {
            val restored = WhisperModelSpec.migrateSavedId(savedName)
            android.util.Log.d(
                "EditorViewModel",
                "restoring saved whisper model: $savedName -> ${restored.name}"
            )
            _uiState.update { it.copy(selectedWhisperModel = restored) }
        } else {
            // Sticky installed-model rule: if a model is
            // INSTALLED, keep it; only promote when nothing is
            // installed yet.
            // 2026-09-13 CLOUD FIX � the cloud spec is never
            // "installed" on disk (there is no file); without
            // the isCloud guard its aiSpec() fallback (Small)
            // could hijack this sticky pick.
            val alreadyInstalled = WhisperModelSpec.entries.firstOrNull {
                !it.isCloud &&
                    AiModels.isVerifiedInstalled(appContext, it.aiSpec())
            }
            if (alreadyInstalled != null) {
                _uiState.update { it.copy(selectedWhisperModel = alreadyInstalled) }
            } else {
                val am = appContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager
                val freeMb = if (am != null) {
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    mi.availMem / (1024L * 1024L)
                } else {
                    Long.MAX_VALUE // can't probe -> trust the static default (Medium)
                }
                android.util.Log.d(
                    "EditorViewModel",
                    "device-aware default: freeRamMb=$freeMb " +
                        "picking=${WhisperModelSpec.defaultFor(freeMb).name}"
                )
                _uiState.update {
                    it.copy(selectedWhisperModel = WhisperModelSpec.defaultFor(freeMb))
                }
            }
        }

        // 2. Whisper statuses from disk.
        _uiState.update { it.copy(aiModelStatuses = refreshAiModelStatusesLocked()) }

        // 2b. 2026-10-10 — surface whether a Groq key is saved so the
        // Subs card shows real cloud readiness (not a hardcoded
        // "Cloud" ready pill).
        _uiState.update { it.copy(hasGroqApiKey = subtitleGenerator.hasUserGroqKey()) }

        // 3. AI tool model states from the live engines.
        //
        // 2026-10-20 — the OCR tool is probed too. It used to be
        // missing from this list, so `aiTools["ocr"]` stayed Idle
        // until the user opened the OCR panel and pressed Download —
        // the state map and the panel disagreed until then.
        probeAiToolModelState("matting")
        probeAiToolModelState("autoframe")
        probeAiToolModelState("ocr")
        val registry = ModelRegistry.fromAssets(appContext).getOrNull()
        esrganSpecCache = registry?.byId(EsrganUpscalePipeline.MODEL_ID)
        val esrganSpec = esrganSpecCache
        val esrganReady = esrganSpec != null &&
            unifiedModelInstaller.isVerifiedInstalled(esrganSpec)
        updateAiToolState("upscale") { cur ->
            cur.copy(
                status = if (esrganReady) {
                    com.aicreatorstudio.app.ai.AiToolStatus.Ready
                } else {
                    com.aicreatorstudio.app.ai.AiToolStatus.ModelMissing
                },
                progress = if (esrganReady) 1f else 0f,
                message = if (esrganReady) SubtitleMessages.AiTool.READY_FA else null,
                cancellable = false
            )
        }
    }

    /**
     * Probe one [AiToolEngine]'s live availability and mirror it
     * into `_uiState.aiTools` (Ready vs ModelMissing) without
     * touching an in-flight install or run.
     */
    private fun probeAiToolModelState(toolId: String) {
        val available = aiToolRegistry.byId(toolId)?.isAvailable == true
        // 2026-10-20 — prime the availability cache with the value we
        // just probed, so the first read from the UI is free.
        aiToolAvailabilityCache[toolId] = available
        updateAiToolState(toolId) { cur ->
            val inFlight = cur.status == com.aicreatorstudio.app.ai.AiToolStatus.Downloading ||
                cur.status == com.aicreatorstudio.app.ai.AiToolStatus.Verifying ||
                cur.status == com.aicreatorstudio.app.ai.AiToolStatus.Installing ||
                cur.status == com.aicreatorstudio.app.ai.AiToolStatus.Processing
            if (inFlight) {
                cur
            } else {
                cur.copy(
                    status = if (available) {
                        com.aicreatorstudio.app.ai.AiToolStatus.Ready
                    } else {
                        com.aicreatorstudio.app.ai.AiToolStatus.ModelMissing
                    },
                    progress = if (available) 1f else 0f,
                    message = if (available) SubtitleMessages.AiTool.READY_FA else null,
                    cancellable = false
                )
            }
        }
    }

    private fun loadProject() {
        viewModelScope.launch {
            val project = projectRepository.getProject(projectId)
            if (project != null) {
                val duration = project.clips.firstOrNull()?.durationMs ?: 0L
                // Restore the project's canvas settings onto the
                // UI-state so the Ratio and BG panels open to the
                // values the user last picked. Without this mirror the
                // panels would show the default (WIDE_16_9 / Black)
                // after a process restart even though the project file
                // carries the right values.
                val preset = project.canvasPreset
                val bg = project.canvasBackground
                val resolution = if (preset == com.aicreatorstudio.app.model.CanvasPreset.FREE) {
                    null
                } else {
                    "${preset.exportWidth} � ${preset.exportHeight}"
                }
                // Probe every clip's backing media off the main thread.
                // Legacy projects (and anything the user relinked away)
                // can carry dead picker content:// URIs � catch them here
                // so the UI can show the "Media Missing / Relink" state
                // instead of ExoPlayer failing silently with a black
                // preview.
                val missingClips = withContext(Dispatchers.IO) {
                    project.clips.filter { !mediaStorage.isMediaAccessible(it.uri) }
                }
                // Restore the saved playhead. Clamped to the project
                // duration so a stale position can't park the playhead
                // past the end of a trimmed timeline.
                val restorePosition = project.lastPositionMs.coerceIn(0L, duration)
                // Captions/AI subtitles + media overlays live in the
                // projects row as JSON blobs � restore them into the
                // editor state so captions and PiP blocks reappear.
                aiSubtitleOverlayIds.clear()
                aiSubtitleOverlayIds.addAll(
                    project.textOverlays.filter { it.words.isNotEmpty() }.map { it.id }
                )
                // PHASE B4 � restore the SRT-imported marker set
                // from the persistent `subtitleSource` tag so a
                // project that was saved after an SRT import
                // still exports those captions on reopen. The
                // legacy `words.isNotEmpty()` path on the line
                // above picks up AI captions; the "srt" tag here
                // picks up the B4 path. Both flows coexist.
                srtImportedOverlayIds.clear()
                srtImportedOverlayIds.addAll(
                    project.textOverlays
                        .filter { it.subtitleSource == "srt" }
                        .map { it.id }
                )
                _uiState.update {
                    it.copy(
                        project = project,
                        durationMs = duration,
                        canvasPreset = preset,
                        canvasBackground = bg,
                        // v8 — mirror the project's video progress bar into
                        // UI state so the preview and the panel open on the
                        // values the user last picked. A v7 row decodes to
                        // the disabled default, so nothing changes for a
                        // project that never used the feature.
                        progressBarSettings = project.progressBarSettings,
                        textOverlays = project.textOverlays,
                        overlays = project.mediaOverlays,
                        playbackPositionMs = restorePosition,
                        pendingRestoreSeekMs = restorePosition.takeIf { pos -> pos > 0L },
                        mediaMissingClips = missingClips,
                        resolutionLabel = resolution ?: it.resolutionLabel,
                        isLoading = false,
                        // PHASE B3 � recompute per-model install
                        // status from disk so the AI Subtitle card
                        // shows the correct Ready/Download pill
                        // when the editor opens (not a hard-coded
                        // "all models available").
                        aiModelStatuses = refreshAiModelStatusesLocked()
                    )
                }
                // 2026-09-06 � VIDEO AUDIO lane seeding. A project with
                // video clips but NO persisted audio objects gets one
                // AudioClip per video (mirroring the video's own
                // placement), so the paired audio shows up as a
                // selectable object on the "Video Audio" lane under the
                // video. Saved projects keep their audio objects; the
                // seeding is idempotent.
                seedVideoAudioClipsIfNeeded(project)
                // 2026-10-20 — a deep-linked AI tool opens as soon as
                // the project is on screen (the route argument usually
                // arrives first, so the panel waits here).
                pendingInitialTool?.let { panel ->
                    pendingInitialTool = null
                    openInitialToolPanel(panel)
                }
                // 2026-10-05 — kick off real-waveform computation for the
                // project's audio lane clips (the timeline renders peaks
                // from this cache instead of the procedural placeholder).
                ensureWaveformsFor(_uiState.value.project)
                ensureThumbnailsFor(_uiState.value.project)
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Project not found"
                    )
                }
            }
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
        // The playhead is part of the saved project state � snapshot it
        // whenever playback pauses. Skipped while the restore-seek is
        // still pending so the initial first-frame pause on open can't
        // overwrite the just-restored position with 0.
        if (!isPlaying && _uiState.value.pendingRestoreSeekMs == null) {
            persistProjectMeta()
        }
    }

    fun onPositionChanged(positionMs: Long) {
        _uiState.update {
            // While the user is actively scrubbing we don't want to
            // recompute the active subtitle id (the position is being
            // driven by drag deltas, not by the player clock).
            if (it.isScrubbing) {
                it.copy(playbackPositionMs = positionMs)
            } else {
                val active = activeSubtitleIdAt(positionMs, it.textOverlays)
                val activeWordId = if (active != null) {
                    activeWordIdAt(positionMs, it.textOverlays, active)
                } else null
                it.copy(
                    playbackPositionMs = positionMs,
                    activeSubtitleId = active,
                    activeWordId = activeWordId
                )
            }
        }
    }

    fun onDurationChanged(durationMs: Long) {
        _uiState.update { it.copy(durationMs = durationMs) }
    }

    fun onSeekTo(positionMs: Long) {
        val ms = positionMs.coerceAtLeast(0L)
        _uiState.update {
            val active = activeSubtitleIdAt(ms, it.textOverlays)
            it.copy(
                playbackPositionMs = ms,
                activeSubtitleId = active,
                activeWordId = if (active != null) activeWordIdAt(ms, it.textOverlays, active) else null
            )
        }
        persistProjectMeta()
    }

    /**
     * Live scrub seek. Called by the timeline on every drag frame so
     * the player can stay in lockstep with the finger. We publish a
     * [EditorUiState.pendingScrubSeekMs] value the screen consumes
     * and forwards to `ExoPlayer.seekTo(ms, SEEK_PARAM_CLOSEST_SYNC)`
     * � the optimized fast-seek path that snaps to the nearest sync
     * frame and avoids the latency of a precise seek.
     */
    fun onScrubSeek(positionMs: Long) {
        val ms = positionMs.coerceAtLeast(0L)
        _uiState.update {
            it.copy(
                playbackPositionMs = ms,
                pendingScrubSeekMs = ms
            )
        }
    }

    fun consumeScrubSeek() {
        _uiState.update { it.copy(pendingScrubSeekMs = null) }
    }

    /**
     * Marker set by the timeline while the user is drag-scrubbing. While
     * true the ViewModel will still record the seek position (so the
     * playhead line follows the finger) but it will skip the side-effects
     * that should only fire on real player-driven position changes
     * (currently: the "active subtitle id" recompute).
     *
     * The screen also reads [EditorUiState.isScrubbing] as the source
     * of truth for two behaviours that must be re-keyed on drag start /
     * end:
     *  - the 33 ms playback poller (EditorScreen DisposableEffect on
     *    exoPlayer) is gated so it does NOT overwrite the local
     *    `currentPositionMs` while the finger is the source of truth;
     *  - `SeekParameters` is switched to `EXACT` for the duration of
     *    the drag (so the final player position lands on the
     *    requested ms instead of snapping to the nearest sync frame
     *    behind it) and reverted to `CLOSEST_SYNC` on release.
     */
    fun onScrubStarted() {
        _uiState.update { it.copy(isScrubbing = true) }
    }

    fun onScrubEnded() {
        _uiState.update {
            val pos = it.playbackPositionMs
            val active = activeSubtitleIdAt(pos, it.textOverlays)
            it.copy(
                isScrubbing = false,
                activeSubtitleId = active,
                activeWordId = if (active != null) activeWordIdAt(pos, it.textOverlays, active) else null
            )
        }
        persistProjectMeta()
    }

    /**
     * Pixel offset of the timeline's vertical scroll. The horizontal
     * ruler and the playhead column stay pinned to the viewport; only
     * the stack of track rows scrolls.
     */
    fun onTimelineVerticalScroll(px: Float) {
        _uiState.update { it.copy(timelineVerticalScrollPx = px.coerceAtLeast(0f)) }
    }

    /**
     * Linear scan of the subtitle list. The list is small (a few hundred
     * entries at most) and we want exact inclusive [start, end] matching
     * for highlighting.
     */
    private fun activeSubtitleIdAt(
        positionMs: Long,
        overlays: List<com.aicreatorstudio.app.model.TextOverlay>
    ): String? {
        if (overlays.isEmpty()) return null
        // Linear scan; cheaper than a binary search here because the
        // list is short and we don't want to keep it sorted across
        // reorders.
        for (i in overlays.indices) {
            val o = overlays[i]
            if (positionMs >= o.startTimeMs && positionMs < o.endTimeMs) {
                return o.id
            }
        }
        return null
    }

    /**
     * Returns the id of the [com.aicreatorstudio.app.ai.TranscribedWord]
     * inside the active subtitle whose `[start, end)` range contains
     * [positionMs]. Linear scan is fine because each subtitle only
     * carries a handful of words.
     */
    private fun activeWordIdAt(
        positionMs: Long,
        overlays: List<com.aicreatorstudio.app.model.TextOverlay>,
        subtitleId: String
    ): String? {
        val subtitle = overlays.firstOrNull { it.id == subtitleId } ?: return null
        if (!subtitle.karaokeEnabled || subtitle.words.isEmpty()) return null
        return subtitle.activeWordAt(positionMs)?.id
    }

    /**
     * Returns the absolute, timeline-space millisecond position of the
     * playhead. Re-exported so the timeline and the player agree on
     * a single source of truth.
     */
    val playheadPositionMs: Long get() = _uiState.value.playbackPositionMs

    // ---- Refactor 2026-08-28 ----

    /**
     * Toggle the editor's fullscreen mode. In fullscreen the canvas
     * expands to fill the available area and the timeline / dock collapse
     * to a compact strip. The ExoPlayer surface stays bound to the
     * canvas so playback is uninterrupted.
     */
    fun onFullscreenToggle() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    /** Step the playhead by [deltaMs] (positive or negative). */
    fun onStepFrames(deltaMs: Long) {
        val current = _uiState.value
        val maxPos = current.durationMs.coerceAtLeast(0L)
        val newPos = (current.playbackPositionMs + deltaMs).coerceIn(0L, maxPos)
        _uiState.update { it.copy(playbackPositionMs = newPos, pendingSeekMs = newPos) }
    }

    /** Set the timeline zoom in pixels per millisecond. Clamped. */
    fun onTimelineZoomChange(pixelsPerMs: Float) {
        _uiState.update {
            it.copy(
                pixelsPerMs = pixelsPerMs.coerceIn(0.005f, 1.0f)
            )
        }
    }

    /**
     * Compute the snap-adjusted playhead position when the user drops
     * the scrubber near a clip boundary. [nearbyMs] are the boundaries
     * the scrubber is currently close to (in ms). Returns the snapped
     * position.
     */
    fun snapToNearest(positionMs: Long, nearbyMs: List<Long>, snapMs: Long = 80L): Long {
        val snapped = nearbyMs.minByOrNull { kotlin.math.abs(it - positionMs) }
        return if (snapped != null && kotlin.math.abs(snapped - positionMs) <= snapMs) {
            snapped
        } else positionMs
    }

    /** Set the open sub-panel, or null to close. */
    fun setToolPanel(panel: com.aicreatorstudio.app.model.ToolPanel?) {
        _uiState.update { it.copy(toolPanel = panel) }
        if (panel == com.aicreatorstudio.app.model.ToolPanel.SUBTITLES) {
            // 2026-10-10 — refresh cloud-key availability: the user
            // may have saved or removed a Groq key in Settings since
            // the last restore.
            _uiState.update { it.copy(hasGroqApiKey = subtitleGenerator.hasUserGroqKey()) }
        }
        if (panel != null) revealTracksForTool(panel)
    }

    /**
     * 2026-10-20 — open a deep-linked AI tool panel, once.
     *
     * The home screen's AI capability tiles open the editor with the
     * matching tool already open. [tool] is a
     * [com.aicreatorstudio.app.model.ToolPanel] enum name; an unknown
     * or missing value is ignored (the editor opens on the timeline).
     *
     * The panel is opened as soon as BOTH the request and the project
     * are available — the request usually arrives first (the route
     * argument is known before the project finishes loading), so the
     * panel is held until the project lands.
     */
    fun openInitialTool(tool: String?) {
        if (initialToolApplied) return
        val name = tool?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val panel = runCatching {
            com.aicreatorstudio.app.model.ToolPanel.valueOf(name)
        }.getOrNull() ?: return
        initialToolApplied = true
        if (_uiState.value.project != null) {
            openInitialToolPanel(panel)
        } else {
            pendingInitialTool = panel
        }
    }

    /**
     * 2026-10-22 — open a deep-linked tool panel **with a clip
     * selected**.
     *
     * The home screen's capability tiles deep-link straight into a
     * clip-scoped tool (Background Remove, Auto Frame, Motion
     * Tracking…). The editor opened with **nothing selected**, so the
     * panel greeted the user with "select a clip first" and its run
     * button stayed disabled — a dead end for the exact action the
     * user asked for on the home screen. CapCut just works.
     *
     * The selection is the project's first **video** clip (an audio
     * clip is never a valid target for these tools). Nothing is
     * selected when the project has no video clip, so the panel still
     * explains itself honestly.
     */
    private fun openInitialToolPanel(panel: com.aicreatorstudio.app.model.ToolPanel) {
        selectFirstVideoClipIfNone()
        setToolPanel(panel)
    }

    /**
     * Select the project's first video clip when nothing is selected.
     *
     * Never overrides an existing selection — the user may have
     * deliberately picked a different clip.
     */
    private fun selectFirstVideoClipIfNone() {
        val state = _uiState.value
        if (state.selectedClipId != null) return
        val first = state.project?.clips?.firstOrNull { !it.isAudioOnly } ?: return
        _uiState.update { it.copy(selectedClipId = first.id) }
    }

    /**
     * Show or hide a specific [com.aicreatorstudio.app.model.TrackKind] in
     * the timeline. VIDEO cannot be hidden � it's the primary lane.
     */
    fun setTrackVisibility(
        kind: com.aicreatorstudio.app.model.TrackKind,
        visible: Boolean
    ) {
        _uiState.update { state ->
            val next = state.visibleTrackKinds.toMutableSet().apply {
                if (visible) add(kind) else remove(kind)
            }
            // VIDEO is always visible.
            if (kind == com.aicreatorstudio.app.model.TrackKind.VIDEO) {
                next.add(com.aicreatorstudio.app.model.TrackKind.VIDEO)
            }
            state.copy(
                visibleTrackKinds = next,
                focusedTrackKind = if (visible) kind else state.focusedTrackKind
            )
        }
    }

    /** Toggle visibility of a [com.aicreatorstudio.app.model.TrackKind]. */
    fun toggleTrackVisibility(kind: com.aicreatorstudio.app.model.TrackKind) {
        val isVisible = _uiState.value.visibleTrackKinds.contains(kind)
        setTrackVisibility(kind, !isVisible)
    }

    /**
     * Convenience: when the user opens a sub-panel, the matching track
     * kind(s) should expand so they can see what they're editing. Closing
     * the panel does NOT collapse the track — once revealed, the user
     * can keep it visible.
     *
     * 2026-09-28 IA FIX — the mapping itself now lives in the pure
     * [com.aicreatorstudio.app.model.tracksForTool]. It previously existed
     * here *and* as an inline `when` in `EditorScreen`'s dock callback, and
     * the two copies disagreed (different lane sets, different focus). Both
     * ran, so the focused lane depended on write order. This is now the
     * single call site.
     */
    fun revealTracksForTool(panel: com.aicreatorstudio.app.model.ToolPanel) {
        val kinds = com.aicreatorstudio.app.model.tracksForTool(panel)
        if (kinds.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                visibleTrackKinds = state.visibleTrackKinds + kinds,
                // First entry is the lane that owns the tool.
                focusedTrackKind = kinds.first()
            )
        }
    }

    fun onVolumeChanged(volume: Float) {
        _uiState.update { it.copy(volume = volume, isMuted = volume == 0f) }
    }

    fun toggleMute() {
        _uiState.update { 
            it.copy(isMuted = !it.isMuted)
        }
    }

    // Video Effects
    fun selectVideoEffect(effect: com.aicreatorstudio.app.model.VideoEffect) {
        _uiState.update { it.copy(selectedEffect = effect) }
        // Also persist onto the selected clip so the effect survives
        // selection changes and is part of the saved project.
        val id = _uiState.value.selectedClipId
        if (id != null) {
            updateClipVideoEffect(id, effect)
        }
    }

    /**
     * Per-clip "famous effect" (ColorMatrix). Used by the Filters panel
     * so the chosen effect is bound to the clip rather than only to the
     * global state. Persisted to disk via [persistClips].
     */
    fun updateClipVideoEffect(
        clipId: String,
        effect: com.aicreatorstudio.app.model.VideoEffect
    ) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(videoEffect = effect) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    fun onBrightnessChanged(brightness: Float) {
        historyDelegate.push()
        val b = brightness.coerceIn(0f, 2f)
        _uiState.update { it.copy(brightness = b) }
        // Mirror to the active clip so the preview reads from a single
        // source of truth and the value is persisted.
        val id = _uiState.value.selectedClipId
        if (id != null) updateClipBrightness(id, b)
    }

    fun onContrastChanged(contrast: Float) {
        historyDelegate.push()
        val c = contrast.coerceIn(0f, 2f)
        _uiState.update { it.copy(contrast = c) }
        val id = _uiState.value.selectedClipId
        if (id != null) updateClipContrast(id, c)
    }

    fun onSaturationChanged(saturation: Float) {
        historyDelegate.push()
        val s = saturation.coerceIn(0f, 2f)
        _uiState.update { it.copy(saturation = s) }
        val id = _uiState.value.selectedClipId
        if (id != null) updateClipSaturation(id, s)
    }

    // ---- 2026-08-29 Lumetri color grading ----

    /**
     * Replace the entire ColorGrading state in one shot. Called by
     * the ColorGradingPanel on every slider tick. Mirrors to the
     * selected clip so the value is persisted.
     */
    fun onColorGradingChanged(grading: com.aicreatorstudio.app.model.ColorGrading) {
        historyDelegate.push()
        _uiState.update { it.copy(colorGrading = grading) }
        val id = _uiState.value.selectedClipId
        if (id != null) updateClipColorGrading(id, grading)
    }

    /**
     * Single-parameter setter used by the per-clip Effects / Adjust
     * dock chip "Reset" button. Resets the *global* grade; the per-clip
     * grade is reset by [resetColorGradingForSelectedClip].
     */
    fun resetColorGrading() {
        historyDelegate.push()
        _uiState.update { it.copy(colorGrading = com.aicreatorstudio.app.model.ColorGrading.Identity) }
        val id = _uiState.value.selectedClipId
        if (id != null) updateClipColorGrading(id, com.aicreatorstudio.app.model.ColorGrading.Identity)
    }

    /**
     * Reset the *per-clip* ColorGrading on the currently selected clip.
     * Used by the "Reset All" button when the user wants to keep the
     * global preset but clear the per-clip override.
     */
    fun resetColorGradingForSelectedClip() {
        val id = _uiState.value.selectedClipId ?: return
        historyDelegate.push()
        updateClipColorGrading(id, com.aicreatorstudio.app.model.ColorGrading.Identity)
    }

    private fun updateClipColorGrading(
        clipId: String,
        grading: com.aicreatorstudio.app.model.ColorGrading
    ) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(colorGrading = grading) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    private fun updateClipBrightness(clipId: String, brightness: Float) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(brightness = brightness) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    private fun updateClipContrast(clipId: String, contrast: Float) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(contrast = contrast) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    private fun updateClipSaturation(clipId: String, saturation: Float) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(saturation = saturation) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    fun onPlaybackSpeedChanged(speed: Float) {
        historyDelegate.push()
        _uiState.update { it.copy(playbackSpeed = PlaybackSpeedRange.clamp(speed)) }
    }

    /**
     * 2026-10-21 — write the SELECTED CLIP's base rotation.
     *
     * ## Why this changed
     *
     * The Adjust/Effects panel shows `clip.rotation`
     * (`EditorScreen` → `EffectsPanel(rotation = clip.rotation)`), and
     * the export reads exactly that field:
     * [com.aicreatorstudio.app.export.RenderGraphBuilder] computes
     * `normaliseRotation(rotation + transform.rotation)` from the
     * clip's own `rotation`.
     *
     * The old handler wrote `uiState.rotation` — a *global* mirror
     * that only the preview ever read. The consequences were:
     *
     *  - the panel displayed a value that was permanently `0`,
     *  - the rotate button moved a number the export never saw,
     *  - the exported video came out **unrotated** — the
     *    "`VideoClip.rotation` has no writer" gap.
     *
     * Now the selected clip is written, exactly like brightness /
     * contrast / saturation, and the global mirror is kept in sync so
     * the undo snapshot and any UI reading it stay coherent.
     */
    fun onRotationChanged(rotation: Int) {
        historyDelegate.push()
        // 2026-10-21 — snapped to the quarter-turn grid the render
        // path resolves (see `snapRotationToQuarterTurn`). Storing an
        // unsnapped value would render as e.g. 45° in the preview and
        // as 0° in the exported file.
        val normalised = com.aicreatorstudio.app.model.snapRotationToQuarterTurn(rotation)
        _uiState.update { it.copy(rotation = normalised) }
        val clipId = _uiState.value.selectedClipId
        if (clipId != null) updateClipRotation(clipId, normalised)
    }

    /**
     * Rotate the selected clip by +90°.
     *
     * The base is the **clip's own** rotation, so repeated taps
     * accumulate (90 → 180 → 270 → 0) instead of restarting from a
     * stale global mirror.
     */
    fun rotate90Degrees() {
        historyDelegate.push()
        val state = _uiState.value
        val clipId = state.selectedClipId
        val clip = state.project?.clips?.firstOrNull { it.id == clipId }
        val next = com.aicreatorstudio.app.model.nextQuarterTurnRotation(
            clipRotation = clip?.rotation,
            current = state.rotation
        )
        _uiState.update { it.copy(rotation = next) }
        if (clipId != null) updateClipRotation(clipId, next)
    }

    /** Write one clip's base rotation and persist the clip list. */
    private fun updateClipRotation(clipId: String, rotation: Int) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(rotation = rotation) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    // Trim
    fun onTrimStartChanged(trimStartMs: Long) {
        historyDelegate.push()
        _uiState.update { it.copy(trimStartMs = trimStartMs.coerceIn(0, it.trimEndMs - 1000)) }
    }

    fun onTrimEndChanged(trimEndMs: Long) {
        historyDelegate.push()
        _uiState.update { it.copy(trimEndMs = trimEndMs.coerceIn(it.trimStartMs + 1000, it.durationMs)) }
    }

    fun toggleTrimMode() {
        _uiState.update { 
            val newTrimMode = !it.isTrimMode
            it.copy(
                isTrimMode = newTrimMode,
                trimStartMs = if (newTrimMode && it.trimStartMs == 0L) 0L else it.trimStartMs,
                trimEndMs = if (newTrimMode && it.trimEndMs == 0L) it.durationMs else it.trimEndMs
            )
        }
    }

    /**
     * Reset the adjustment panel.
     *
     * 2026-10-21 — this now resets the **selected clip** as well. The
     * panel displays the clip's values (`clip.brightness`,
     * `clip.rotation`, …), so resetting only the global mirror left
     * the visible values untouched — the button looked like it did
     * nothing. [AdjustmentDelegate.resetEffects] already had the
     * correct semantics (global + clip); the live implementation did
     * not, and the delegate was never wired up.
     */
    fun resetEffects() {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                brightness = 1f,
                contrast = 1f,
                saturation = 1f,
                playbackSpeed = 1f,
                rotation = 0,
                colorGrading = com.aicreatorstudio.app.model.ColorGrading.Identity
            )
        }
        val id = _uiState.value.selectedClipId
        if (id != null) {
            updateClipColorGrading(id, com.aicreatorstudio.app.model.ColorGrading.Identity)
            updateClipBrightness(id, 1f)
            updateClipContrast(id, 1f)
            updateClipSaturation(id, 1f)
            updateClipRotation(id, 0)
        }
    }

    // Text Overlays
    fun addTextOverlay(
        text: String,
        startTimeMs: Long,
        endTimeMs: Long,
        fontFamilyName: String = com.aicreatorstudio.app.model.PersianFont.DEFAULT.name
    ) {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays + TextOverlay.default(
                    text = text,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs
                ).copy(fontFamily = fontFamilyName)
            )
        }
        persistProjectMeta()
    }

    /**
     * 2026-09-06 � Paragraph tool. Adds a multi-line text block
     * (maxLines = 8, wider bounds than a normal overlay) at the
     * requested normalized anchor position so the Position grid
     * in the paragraph panel can drop it straight onto
     * "??? ?????" / "????" / "?????" / corner presets.
     */
    fun addParagraphOverlay(
        text: String,
        startTimeMs: Long,
        endTimeMs: Long,
        anchorX: Float,
        anchorY: Float,
        fontFamilyName: String = com.aicreatorstudio.app.model.PersianFont.DEFAULT.name
    ) {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays + TextOverlay.default(
                    text = text,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs
                ).copy(
                    fontFamily = fontFamilyName,
                    position = androidx.compose.ui.geometry.Offset(
                        anchorX.coerceIn(0.05f, 0.95f),
                        anchorY.coerceIn(0.05f, 0.95f)
                    ),
                    fontSizeSp = 20f
                )
            )
        }
        persistProjectMeta()
    }

    /**
     * 2026-09-06 � Text tool �?????� button. Adds the text overlay
     * starting exactly at the current playhead and SELECTS it, so the
     * user can immediately drag it on the preview or move it on the
     * timeline.
     */
    fun applyTextAtPlayhead(
        text: String,
        startTimeMs: Long,
        endTimeMs: Long,
        fontFamilyName: String = com.aicreatorstudio.app.model.PersianFont.DEFAULT.name
    ) {
        addTextOverlay(text, startTimeMs, endTimeMs, fontFamilyName)
        // The new overlay is the last one in the list (addTextOverlay
        // appends); select it so the drag / style gestures are armed
        // right away.
        val newId = _uiState.value.textOverlays.lastOrNull()?.id
        if (newId != null) selectTextOverlay(newId)
    }

    fun updateTextOverlay(textId: String, newText: String) {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == textId) overlay.copy(text = newText) else overlay
                }
            )
        }
        persistProjectMeta()
    }

    // ------------------------------------------------------------------
    // 2026-10-13 — CapCut-style smart-subtitle EDITING workflow.
    //
    // The generator owns readability; these actions own user intent.
    // Every computation lives in `model/SubtitleEditOps` (pure, JVM
    // tested); the ViewModel's only job is to locate the target, apply
    // the op, and route the result through the standard
    // push/update/persist pattern so undo/redo comes for free.
    //
    // All three share one contract, and it matters: **an op that
    // returns null does not push history and does not persist.** A
    // rejected edit must leave no undo entry, or the user's undo stack
    // fills with steps that visibly do nothing.
    // ------------------------------------------------------------------

    /**
     * Split the AI/SRT subtitle [textId] in two at [wordIndex] (the
     * index of the first word of the second half).
     *
     * No-op when the overlay is unknown, has fewer than two timed
     * words, or [wordIndex] is not a real interior boundary. The left
     * half keeps the original id (so the current selection survives)
     * and the right half is inserted immediately after it, which keeps
     * `textOverlays` in timeline order.
     */
    fun splitSubtitleAt(textId: String, wordIndex: Int) {
        val state = _uiState.value
        val index = state.textOverlays.indexOfFirst { it.id == textId }
        if (index < 0) return
        val target = state.textOverlays[index]
        val halves = SubtitleEditOps.splitAt(target, wordIndex) ?: return
        val (left, right) = halves

        historyDelegate.push()
        // The right half joins the AI-subtitle bookkeeping set so the
        // "apply template to all" / SRT-export gates keep treating it
        // as a subtitle rather than decorative text.
        if (textId in aiSubtitleOverlayIds) aiSubtitleOverlayIds.add(right.id)
        _uiState.update { current ->
            val newList = current.textOverlays.toMutableList().also { list ->
                val pos = list.indexOfFirst { it.id == textId }
                if (pos < 0) return@also
                list[pos] = left
                list.add(pos + 1, right)
            }
            current.copy(textOverlays = newList)
        }
        persistProjectMeta()
    }

    /**
     * Merge the AI/SRT subtitle [textId] with the next subtitle that
     * starts after it — the adjacent fragment a user most often wants
     * joined back together after an over-eager auto-segmentation.
     *
     * "Next" is resolved by timeline order, and
     * [SubtitleEditOps.merge] independently refuses a pair that
     * overlaps, so a bad neighbour selection degrades to a no-op rather
     * than a corrupt caption. The surviving overlay keeps [textId]'s
     * identity, so selection is stable.
     */
    fun mergeSubtitleWithNext(textId: String) {
        val state = _uiState.value
        val index = state.textOverlays.indexOfFirst { it.id == textId }
        if (index < 0) return
        val first = state.textOverlays[index]
        val second = state.textOverlays
            .withIndex()
            .filter { (i, o) -> i != index && o.id != textId }
            .map { (_, o) -> o }
            .filter { it.words.isNotEmpty() && it.startTimeMs >= first.startTimeMs }
            .minByOrNull { it.startTimeMs }
            ?: return

        val merged = SubtitleEditOps.merge(first, second) ?: return

        historyDelegate.push()
        aiSubtitleOverlayIds.remove(second.id)
        aiSubtitleOverlayIds.add(merged.id)
        _uiState.update { current ->
            current.copy(
                textOverlays = current.textOverlays
                    .filterNot { it.id == second.id }
                    .map { if (it.id == textId) merged else it },
                selectedTextId = if (current.selectedTextId == second.id) merged.id else current.selectedTextId
            )
        }
        persistProjectMeta()
    }

    /**
     * Move the AI/SRT subtitle [textId]'s boundaries to
     * `[newStartMs, newEndMs)`.
     *
     * Unlike [updateSelectedTextDuration] — which rescales the word
     * timings proportionally and therefore rewrites them — this is the
     * **non-destructive** retime: the frame changes, the words do not.
     * That is what makes a boundary drag reversible, which is what the
     * API needs (see `SubtitleEditOps.retime`).
     */
    fun retimeSubtitle(textId: String, newStartMs: Long, newEndMs: Long) {
        val target = _uiState.value.textOverlays.firstOrNull { it.id == textId } ?: return
        val retimed = SubtitleEditOps.retime(target, newStartMs, newEndMs)
        if (retimed == target) return

        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o -> if (o.id == textId) retimed else o }
            )
        }
        persistProjectMeta()
    }

    /**
     * Nudge a single word's timing inside the AI/SRT subtitle
     * [textId] — the correction for the recognizer mis-timing a short
     * Persian clitic.
     *
     * No-op when the overlay or [wordIndex] is unknown. The parent
     * segment's frame may widen to keep the corrected word visible;
     * [SubtitleEditOps.retimeWord] decides that and keeps the word
     * order monotonic.
     */
    fun retimeSubtitleWord(textId: String, wordIndex: Int, newStartMs: Long, newEndMs: Long) {
        val target = _uiState.value.textOverlays.firstOrNull { it.id == textId } ?: return
        val updated = SubtitleEditOps.retimeWord(target, wordIndex, newStartMs, newEndMs) ?: return
        if (updated == target) return

        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o -> if (o.id == textId) updated else o }
            )
        }
        persistProjectMeta()
    }

    /**
     * Re-derive the subtitle [textId]'s `text` from its word list,
     * restoring INV-COHERENCE after an out-of-band text edit made the
     * two representations disagree.
     *
     * This is what the AI Subtitle card's "restore original text"
     * affordance should call: the manual editor deliberately writes
     * `text` alone so a user can fix a typo without losing timings, and
     * this is the inverse — the words win.
     */
    fun resyncSubtitleTextFromWords(textId: String) {
        val target = _uiState.value.textOverlays.firstOrNull { it.id == textId } ?: return
        val resynced = SubtitleEditOps.rebuildTextFromWords(target)
        if (resynced == target) return

        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o -> if (o.id == textId) resynced else o }
            )
        }
        persistProjectMeta()
    }

    // ------------------------------------------------------------------
    // 2026-10-13 — Custom Font Importer (.ttf / .otf).
    //
    // These two actions are the ONLY write path from the UI into
    // [CustomFontRepository]. All validation, sanitisation and on-disk
    // layout live behind the repository (and behind the pure helpers in
    // `model/CustomFontFiles`), so the ViewModel's job is narrow:
    //   1. call the repository,
    //   2. surface the outcome on a user-visible channel, and
    //   3. on delete, sweep every style/overlay that referenced the
    //      removed font down to the default Persian font so nothing
    //      renders a dangling `custom:<id>`.
    //
    // Note there is no Toast machinery in this app: the project's
    // user-visible message channel is the dismissible banner fed by
    // `uiState.errorMessage` / `uiState.editorNotice`. A successful import
    // is a *confirmation*, so it goes to the new `editorConfirmation`
    // field (the same banner renders either), never to `editorNotice`,
    // which is reserved for refusals.
    // ------------------------------------------------------------------

    /**
     * Import the font the user picked at [uri].
     *
     * Returns immediately; the copy + validation happens on the IO
     * dispatcher inside the repository. On success the new font appears
     * in `uiState.customFonts` (via the observing collection wired in
     * [init]) and the user sees «فونت با موفقیت اضافه شد». If the file is
     * missing, empty, has no recognised sfnt header, or cannot be turned
     * into a [android.graphics.Typeface], the repository returns null,
     * leaves nothing on disk, and the user sees «فایل فونت معتبر نیست».
     *
     * This action deliberately does NOT auto-select the font: the user
     * asked to *add* it to the picker, not to restyle their captions.
     */
    fun importCustomFont(uri: Uri) {
        viewModelScope.launch {
            val imported = customFontRepository.importFont(uri.toString())
            if (imported != null) {
                _uiState.update {
                    // The repository is the source of truth for ordering;
                    // the collector will overwrite this, but updating now
                    // keeps the confirmation and the chip in sync even if
                    // the flow emission is a frame late.
                    it.copy(
                        editorConfirmation = CustomFontMessages.IMPORT_SUCCESS,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update { it.copy(errorMessage = CustomFontMessages.IMPORT_FAILURE) }
            }
        }
    }

    /**
     * Delete the imported font [fontId] (a bare id, NOT the `custom:`
     * reference string — the strip already unwraps it).
     *
     * The safety requirement is the important half: if the font is the
     * active `fontFamily` of any text overlay or subtitle style, dropping
     * the record alone would leave a saved project pointing at a file
     * that no longer exists. Instead every matching reference is rewritten
     * to [PersianFont.VAZIR] — the family the default `fa_01_clean`
     * subtitle template uses — so the project keeps rendering (in
     * Vazirmatn) instead of silently falling back per-render or, worse,
     * failing to export.
     *
     * Deleting an unknown id is a no-op: the sweep still runs (harmless,
     * nothing matches) and no history entry is pushed, so a double-tap on
     * the trash icon cannot add an empty undo step.
     */
    fun deleteCustomFont(fontId: String) {
        viewModelScope.launch {
            val removed = customFontRepository.deleteCustomFont(fontId) ?: return@launch
            val reverted = fallbackAwayFromCustomFont(removed.selectionId)
            _uiState.update {
                it.copy(
                    editorConfirmation = if (reverted) {
                        CustomFontMessages.DELETE_FALLBACK_APPLIED
                    } else {
                        CustomFontMessages.DELETE_SUCCESS
                    }
                )
            }
        }
    }

    /**
     * Rewrite every reference to [selectionId] (a `"custom:<id>"` string)
     * across text overlays down to the default Persian font.
     *
     * Pushes history and persists only when something actually changed,
     * so a font that was imported but never applied costs zero undo
     * entries. The check is a plain `any {}` scan first — this is the
     * "no document change ⇒ no history" contract the subtitle editing
     * actions share.
     *
     * @return `true` when at least one caption was reverted.
     */
    private fun fallbackAwayFromCustomFont(selectionId: String): Boolean {
        val state = _uiState.value
        val affected = state.textOverlays.any {
            it.fontFamily == selectionId || it.subtitleStyle?.fontFamily == selectionId
        }
        if (!affected) return false

        historyDelegate.push()
        _uiState.update { current ->
            current.copy(
                textOverlays = current.textOverlays.map { overlay ->
                    // Both fields are swept, mirroring
                    // [updateSelectedTextFont], which keeps
                    // `fontFamily` and `subtitleStyle.fontFamily` in
                    // sync. Touching only one would leave the decorative
                    // renderer pointing at a deleted file while the plain
                    // renderer showed the fallback — a mismatch that only
                    // shows up in the exported video.
                    val plainMatches = overlay.fontFamily == selectionId
                    val styleMatches = overlay.subtitleStyle?.fontFamily == selectionId
                    if (!plainMatches && !styleMatches) return@map overlay
                    overlay.copy(
                        fontFamily = if (plainMatches) DEFAULT_PERSIAN_FONT_FAMILY else overlay.fontFamily,
                        subtitleStyle = if (styleMatches) {
                            overlay.subtitleStyle?.copy(fontFamily = DEFAULT_PERSIAN_FONT_FAMILY)
                        } else {
                            overlay.subtitleStyle
                        }
                    )
                }
            )
        }
        persistProjectMeta()
        return true
    }

    fun updateTextPosition(textId: String, x: Float, y: Float) {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == textId) overlay.copy(
                        position = androidx.compose.ui.geometry.Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
                    ) else overlay
                }
            )
        }
        persistProjectMeta()
    }

    // ------------------------------------------------------------------
    // 2026-09-06 text-drag fix � live transform pipeline.
    //
    // The canvas drag gesture used to call [updateTextPosition] on
    // EVERY frame, which (a) pushed a full undo-history snapshot per
    // frame, (b) queued a Room persist per frame, and (c) � the
    // visible bug � recomposed the gesture's `pointerInput(textOverlays,
    // ...)` keys, which CANCELLED the in-flight gesture mid-drag (the
    // text followed the finger for a moment, then froze).
    //
    // The drag pipeline is now split:
    //   [beginTextTransform]              � once, on the first moved
    //                                       frame (one history snapshot)
    //   [updateTextPositionLive] /
    //   [updateTextStyleLive]             � per frame, state-only
    //   [endTextTransform]                � once, on pointer up (persist)
    // ------------------------------------------------------------------

    /** One undo-history snapshot at the start of a text drag / pinch. */
    fun beginTextTransform() {
        historyDelegate.push()
    }

    /** Persists the final transform once, when the pointer is released. */
    fun endTextTransform() {
        persistProjectMeta()
    }

    // ------------------------------------------------------------------
    // 2026-09-06 � Paragraph-panel editing of the SELECTED text overlay.
    // When the user taps a text / subtitle tile on the timeline the
    // paragraph panel opens bound to that overlay: edit text, change
    // font / size / color, apply one of the style presets, snap to a
    // position-grid preset. All single-undo-step operations.
    // ------------------------------------------------------------------

    /** Update the text content of the selected overlay. */
    fun updateSelectedTextContent(newText: String) {
        val id = _uiState.value.selectedTextId ?: return
        if (newText.isBlank()) return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == id) overlay.copy(text = newText) else overlay
                }
            )
        }
        persistProjectMeta()
    }

    /** Update the selected overlay's font family. */
    fun updateSelectedTextFont(fontFamilyName: String) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == id) {
                        overlay.copy(
                            fontFamily = fontFamilyName,
                            // The decorative renderer reads the font from
                            // subtitleStyle.fontFamily � keep both in sync.
                            subtitleStyle = overlay.subtitleStyle?.copy(fontFamily = fontFamilyName)
                        )
                    } else overlay
                }
            )
        }
        persistProjectMeta()
    }

    /** Update the selected overlay's font size (sp). */
    fun updateSelectedTextFontSize(sizeSp: Float) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == id) {
                        overlay.copy(
                            fontSizeSp = sizeSp,
                            subtitleStyle = overlay.subtitleStyle?.copy(fontSizeSp = sizeSp)
                        )
                    } else overlay
                }
            )
        }
        persistProjectMeta()
    }

    /** Update the selected overlay's text color. */
    fun updateSelectedTextColor(colorArgb: Int) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == id) {
                        overlay.copy(
                            color = androidx.compose.ui.graphics.Color(colorArgb),
                            subtitleStyle = overlay.subtitleStyle?.copy(textColorArgb = colorArgb)
                        )
                    } else overlay
                }
            )
        }
        persistProjectMeta()
    }

    /** Shift the selected overlay's timeline placement (start/end). */
    fun moveSelectedTextToPosition(startMs: Long) {
        val id = _uiState.value.selectedTextId ?: return
        val overlay = _uiState.value.textOverlays.firstOrNull { it.id == id } ?: return
        val duration = overlay.durationMs.coerceAtLeast(1L)
        // 2026-09-07 KARAOKE SYNC � shifting the caption must shift
        // its per-word timestamps by the SAME delta, otherwise the
        // preview's word highlight desyncs from the moved caption.
        val delta = startMs.coerceAtLeast(0L) - overlay.startTimeMs
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o ->
                    if (o.id == id) o.copy(
                        startTimeMs = startMs.coerceAtLeast(0L),
                        endTimeMs = startMs.coerceAtLeast(0L) + duration,
                        words = if (delta == 0L) o.words else o.words.map { w ->
                            w.copy(
                                startTimeMs = w.startTimeMs + delta,
                                endTimeMs = w.endTimeMs + delta
                            )
                        }
                    ) else o
                }
            )
        }
        persistProjectMeta()
    }

    /**
     * 2026-09-06 � TEXT SYSTEM completion. Direct style setters for
     * the SELECTED overlay so every control in the brief's list has a
     * one-tap path: rotation, stroke, shadow, background, animation,
     * duration. All preserve text/timing/words (single-object
     * invariant Timeline ? Preview).
     */

    /** Rotate the selected overlay (degrees, snapped for export). */
    fun updateSelectedTextRotation(deg: Float) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o ->
                    if (o.id == id) o.copy(rotation = deg) else o
                }
            )
        }
        persistProjectMeta()
    }

    /** Toggle / tune the selected overlay's stroke. */
    fun updateSelectedTextStroke(enabled: Boolean, colorArgb: Int? = null, widthSp: Float? = null) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o ->
                    if (o.id == id) {
                        val style = o.subtitleStyle ?: com.aicreatorstudio.app.model.SubtitleStyle.Default
                        o.copy(
                            subtitleStyle = style.copy(
                                strokeEnabled = enabled,
                                strokeColorArgb = colorArgb ?: style.strokeColorArgb,
                                strokeWidthSp = widthSp ?: style.strokeWidthSp
                            )
                        )
                    } else o
                }
            )
        }
        persistProjectMeta()
    }

    /** Toggle / tune the selected overlay's drop shadow. */
    fun updateSelectedTextShadow(enabled: Boolean) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o ->
                    if (o.id == id) {
                        val style = o.subtitleStyle ?: com.aicreatorstudio.app.model.SubtitleStyle.Default
                        o.copy(
                            subtitleStyle = style.copy(
                                shadowEnabled = enabled,
                                shadowColorArgb = android.graphics.Color.BLACK
                            )
                        )
                    } else o
                }
            )
        }
        persistProjectMeta()
    }

    /** Toggle / tune the selected overlay's background pill. */
    fun updateSelectedTextBackground(enabled: Boolean) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o ->
                    if (o.id == id) {
                        val style = o.subtitleStyle ?: com.aicreatorstudio.app.model.SubtitleStyle.Default
                        o.copy(
                            subtitleStyle = style.copy(
                                backgroundEnabled = enabled,
                                backgroundColorArgb =
                                    if (enabled && !style.backgroundEnabled)
                                        android.graphics.Color.argb(255, 20, 20, 26)
                                    else style.backgroundColorArgb
                            )
                        )
                    } else o
                }
            )
        }
        persistProjectMeta()
    }

    /** Set the selected overlay's entrance/exit animation. */
    fun updateSelectedTextAnimation(
        animation: com.aicreatorstudio.app.model.TextOverlayAnimation
    ) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o ->
                    if (o.id == id) o.copy(textAnimation = animation) else o
                }
            )
        }
        persistProjectMeta()
    }

    /** Extend / shorten the selected overlay's on-screen duration. */
    fun updateSelectedTextDuration(durationMs: Long) {
        val id = _uiState.value.selectedTextId ?: return
        val overlay = _uiState.value.textOverlays.firstOrNull { it.id == id } ?: return
        val safe = durationMs.coerceIn(300L, 60_000L)
        // 2026-09-07 KARAOKE SYNC � rescale per-word timestamps into
        // the new [start, start+duration] window so the highlight
        // stays proportional when the caption is stretched. Without
        // this, a lengthened caption went blank (words ending early)
        // and a shortened one highlighted words outside its range.
        val oldDuration = (overlay.endTimeMs - overlay.startTimeMs).coerceAtLeast(1L)
        val newEnd = overlay.startTimeMs + safe
        val scale = safe.toDouble() / oldDuration
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { o ->
                    if (o.id == id) o.copy(
                        endTimeMs = newEnd,
                        words = if (o.words.isEmpty()) o.words else o.words.map { w ->
                            val rs = ((w.startTimeMs - o.startTimeMs) * scale).toLong()
                            val re = ((w.endTimeMs - o.startTimeMs) * scale).toLong()
                            w.copy(
                                startTimeMs = o.startTimeMs + rs,
                                endTimeMs = o.startTimeMs + maxOf(re, rs + 1L)
                            )
                        }
                    ) else o
                }
            )
        }
        persistProjectMeta()
    }

    /**
     * Per-frame position update during a finger drag. State-only �
     * no history push, no persistence (those belong to
     * [beginTextTransform] / [endTextTransform]).
     */
    fun updateTextPositionLive(textId: String, x: Float, y: Float) {
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == textId) overlay.copy(
                        position = androidx.compose.ui.geometry.Offset(
                            x.coerceIn(0f, 1f),
                            y.coerceIn(0f, 1f)
                        )
                    ) else overlay
                }
            )
        }
    }

    /**
     * Per-frame style update (pinch / rotate) during a gesture.
     * State-only, same contract as [updateTextPositionLive].
     */
    fun updateTextStyleLive(
        textId: String,
        fontSizeSp: Float? = null,
        rotation: Float? = null
    ) {
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == textId) overlay.copy(
                        fontSizeSp = fontSizeSp ?: overlay.fontSizeSp,
                        rotation = rotation ?: overlay.rotation
                    ) else overlay
                }
            )
        }
    }

    fun updateTextTimeRange(textId: String, startTimeMs: Long, endTimeMs: Long) {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == textId) overlay.copy(
                        startTimeMs = startTimeMs,
                        endTimeMs = endTimeMs
                    ) else overlay
                }
            )
        }
        persistProjectMeta()
    }

    fun updateTextStyle(
        textId: String,
        color: androidx.compose.ui.graphics.Color? = null,
        fontSizeSp: Float? = null,
        fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
        // PHASE B5.5 � the 2-finger pinch+rotate gesture writes
        // the rotation in the same call as fontSizeSp so a
        // single gesture yields a single history snapshot.
        rotation: Float? = null
    ) {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == textId) overlay.copy(
                        color = color ?: overlay.color,
                        fontSizeSp = fontSizeSp ?: overlay.fontSizeSp,
                        fontWeight = fontWeight ?: overlay.fontWeight,
                        rotation = rotation ?: overlay.rotation
                    ) else overlay
                }
            )
        }
        persistProjectMeta()
    }

    fun deleteTextOverlay(textId: String) {
        historyDelegate.push()
        aiSubtitleOverlayIds.remove(textId)
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.filter { overlay -> overlay.id != textId },
                selectedTextId = if (it.selectedTextId == textId) null else it.selectedTextId
            )
        }
        persistProjectMeta()
    }

    /**
     * PHASE A (Persistence Integrity) — used to write `selectedTextId`
     * alone, leaving `selectedClipId` / `selectedAudioClipId` set.
     * The invariant every real entry point depends on is "exactly one
     * selected object", enforced by `TimelineDelegate.selectClip` /
     * `selectAudioClip` and asserted by `SelectionExclusivityTest` —
     * but this method is the one the UI actually calls, so the
     * invariant silently did not hold on the live path.
     *
     * `resolveEditorContext` prioritises text, so the toolbar still
     * looked right; the stale sibling ids are what leaked (e.g. a clip
     * action firing on a text selection).
     */
    fun selectTextOverlay(textId: String?) {
        _uiState.update {
            it.copy(
                selectedTextId = textId,
                // Selecting text clears the other two selections.
                selectedClipId = null,
                selectedAudioClipId = null
            )
        }
    }

    /**
     * 2026-09-06 — Paragraph tool: snap the selected text overlay
     * onto one of the position-grid presets (center / top / bottom /
     * corners / thirds). No-op when nothing is selected.
     */
    fun snapSelectedTextPosition(anchorX: Float, anchorY: Float) {
        val id = _uiState.value.selectedTextId ?: return
        updateTextPosition(id, anchorX, anchorY)
    }

    /**
     * 2026-09-06 � Paragraph tool: nudge the selected overlay by a
     * normalized delta (one tap on a direction pad). Used by the
     * arrow-pad move controls so the user can fine-tune placement
     * without a precision drag.
     */
    fun nudgeSelectedTextPosition(dx: Float, dy: Float) {
        val id = _uiState.value.selectedTextId ?: return
        val current = _uiState.value.textOverlays.firstOrNull { it.id == id } ?: return
        updateTextPosition(
            id,
            (current.position.x + dx).coerceIn(0f, 1f),
            (current.position.y + dy).coerceIn(0f, 1f)
        )
    }

    /**
     * PHASE B5 � apply a [com.aicreatorstudio.app.model.SubtitleStyle]
     * to the overlay currently selected via [selectTextOverlay].
     * No-op if no overlay is selected. Only mutates the
     * `subtitleStyle` field on the matching overlay; text, timing,
     * words, and subtitleSource are preserved.
     */
    fun applySubtitleStyleToSelected(
        style: com.aicreatorstudio.app.model.SubtitleStyle
    ) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == id) {
                        overlay.copy(
                            subtitleStyle = style,
                            // 2026-09-06: sync the legacy fontSizeSp so
                            // the hit-test / selection chrome
                            // (boundsNormalized reads fontSizeSp) stays
                            // in lock-step with the style-driven render
                            // size for BOTH subtitle and decorative
                            // overlays.
                            fontSizeSp = style.fontSizeSp
                        )
                    } else overlay
                }
            )
        }
        persistProjectMeta()
    }

    /**
     * PHASE B5 � apply a [com.aicreatorstudio.app.model.SubtitleStyle]
     * to every subtitle (AI or SRT-imported) on the timeline.
     * Decorative text overlays (titles, watermarks) are
     * excluded. The filter matches the SRT export filter:
     * `aiSubtitleOverlayIds ? subtitleSource in {"ai", "srt"}`.
     */
    fun applySubtitleStyleToAllSubtitles(
        style: com.aicreatorstudio.app.model.SubtitleStyle
    ) {
        historyDelegate.push()
        val ids = srtImportedOverlayIds + aiSubtitleOverlayIds
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id in ids) {
                        overlay.copy(subtitleStyle = style)
                    } else overlay
                }
            )
        }
        persistProjectMeta()
    }

    // ------------------------------------------------------------------
    // 2026-10-15 — GLOBAL SUBTITLE FONT SIZE (batch resize of every
    // subtitle in one undo step).
    //
    // Two ways to drive it, deliberately split like the text-drag
    // pipeline (beginTextTransform / update…Live / endTextTransform):
    //
    //   discrete  — [+]/[-] buttons, the "apply to all" button:
    //               [setGlobalSubtitleFontSize] pushes ONE history
    //               snapshot, updates every subtitle, persists. One
    //               Undo click reverts the whole batch.
    //   live drag — slider: [beginGlobalSubtitleResize] pushes the
    //               single snapshot BEFORE the first change,
    //               [updateGlobalSubtitleSizeLive] is state-only (no
    //               push, no persist per frame), and
    //               [endGlobalSubtitleResize] persists once on
    //               release. A 200-pixel drag must not create 200
    //               undo entries.
    //
    // Scope — which overlays are "subtitles": the SRT-export filter
    // (`subtitleSource in {ai, srt}` and the in-memory id sets) OR
    // any overlay carrying word timings, OR the studio's own
    // `"manual"` tag. Decorative overlays (titles, watermarks,
    // `subtitleSource == null`) are never touched.
    //
    // Both size fields are written on every overlay — `fontSizeSp`
    // (legacy renderer + timeline hit-testing bounds) and
    // `subtitleStyle.fontSizeSp` (the decorative renderer) — so the
    // preview, the canvas chrome and the export can never disagree
    // about the size. This mirrors updateSelectedTextFontSize.
    // ------------------------------------------------------------------

    /** The sp band the global-size control may produce. */
    val globalSubtitleSizeRange: ClosedFloatingPointRange<Float> = 14f..42f

    /** True when [overlay] is a subtitle (never decorative text). */
    private fun isSubtitleOverlay(
        overlay: com.aicreatorstudio.app.model.TextOverlay
    ): Boolean =
        overlay.subtitleSource != null ||
            overlay.words.isNotEmpty() ||
            overlay.id in aiSubtitleOverlayIds ||
            overlay.id in srtImportedOverlayIds

    /**
     * The current font size of the subtitles, for seeding the
     * slider: the size of the first subtitle, or null when the
     * timeline has none.
     */
    fun currentGlobalSubtitleFontSize(): Float? =
        _uiState.value.textOverlays.firstOrNull { isSubtitleOverlay(it) }?.fontSizeSp

    /** One undo snapshot, before a live slider drag starts. */
    fun beginGlobalSubtitleResize() {
        historyDelegate.push()
    }

    /** Persist the final state once, when the drag is released. */
    fun endGlobalSubtitleResize() {
        persistProjectMeta()
    }

    /**
     * State-only size change for the live drag path — NO history
     * push, NO persist. The caller pairs it with
     * [beginGlobalSubtitleResize] / [endGlobalSubtitleResize].
     */
    fun updateGlobalSubtitleSizeLive(sizeSp: Float) {
        val target = sizeSp.coerceIn(globalSubtitleSizeRange)
        if (!hasSubtitles()) return
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map { overlay ->
                    if (isSubtitleOverlay(overlay)) {
                        overlay.copy(
                            fontSizeSp = target,
                            subtitleStyle = overlay.subtitleStyle?.copy(fontSizeSp = target)
                        )
                    } else overlay
                }
            )
        }
    }

    /**
     * Set every subtitle's font size to [newSizeSp], clamped to
     * [globalSubtitleSizeRange]. Single undo step, single persist.
     *
     * No-op (zero history entries, zero writes) when the timeline
     * has no subtitle or every subtitle already sits at the target —
     * the same "no document change ⇒ no history" contract the
     * subtitle editing actions share.
     */
    fun setGlobalSubtitleFontSize(newSizeSp: Float) {
        val target = newSizeSp.coerceIn(globalSubtitleSizeRange)
        if (!hasSubtitles()) return
        val alreadyAtTarget = _uiState.value.textOverlays
            .filter { isSubtitleOverlay(it) }
            .all { it.fontSizeSp == target && it.subtitleStyle?.fontSizeSp == target }
        if (alreadyAtTarget) return

        historyDelegate.push()
        updateGlobalSubtitleSizeLive(target)
        persistProjectMeta()
    }

    /**
     * Scale every subtitle's CURRENT font size by [scaleFactor]
     * (e.g. 1.1f = +10%, 0.9f = −10%), clamped to
     * [globalSubtitleSizeRange] so repeated taps cannot shrink the
     * text into oblivion or blow it past the safe area.
     *
     * Proportional on purpose: a timeline that already mixes sizes
     * (a 20sp caption next to a 34sp one) keeps its ratios instead
     * of being flattened to one absolute size.
     */
    fun scaleAllSubtitles(scaleFactor: Float) {
        if (!hasSubtitles()) return
        val factor = scaleFactor.takeIf { it > 0f && it.isFinite() } ?: return
        val next = _uiState.value.textOverlays
            .filter { isSubtitleOverlay(it) }
            .associate { it.id to (it.fontSizeSp * factor).coerceIn(globalSubtitleSizeRange) }
        if (next.isEmpty()) return
        val unchanged = next.all { (id, size) ->
            _uiState.value.textOverlays.first { it.id == id }.fontSizeSp == size
        }
        if (unchanged) return

        historyDelegate.push()
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map { overlay ->
                    next[overlay.id]?.let { size ->
                        overlay.copy(
                            fontSizeSp = size,
                            subtitleStyle = overlay.subtitleStyle?.copy(fontSizeSp = size)
                        )
                    } ?: overlay
                }
            )
        }
        persistProjectMeta()
    }

    private fun hasSubtitles(): Boolean =
        _uiState.value.textOverlays.any { isSubtitleOverlay(it) }

    // PHASE B5.5 � viral text style application.
    //
    // Distinct from `applySubtitleStyleToSelected` /
    // `applySubtitleStyleToAllSubtitles` in two ways:
    //  1. Scope � viral styles can be applied to ANY
    //     `TextOverlay` (subtitle OR decorative). The renderer
    //     in `EditorScreen.kt:1654` is `subtitleStyle`-driven
    //     and falls back to `SubtitleStyle.Default` when the
    //     field is null, so viral styles on decorative text
    //     render with the same chrome the AI subtitle path
    //     uses.
    //  2. "Apply to all" is a single undo step. The whole list
    //     update happens in one `_uiState.update` so the
    //     snapshot taken by `pushHistory` is the pre-change
    //     state for ALL overlays at once.
    //
    // Both methods use `overlay.copy(subtitleStyle = style)`,
    // preserving `text / startTimeMs / endTimeMs / words /
    // fontFamily / subtitleSource / position / rotation` �
    // the B3 `SubtitleTextEditingTest` invariant.

    /**
     * Apply a viral / caption style to the overlay currently
     * selected via [selectTextOverlay]. No-op if no overlay is
     * selected. Preserves every other field on the overlay
     * (text, timing, words, position, rotation, source tag).
     */
    fun applyViralStyleToSelected(
        style: com.aicreatorstudio.app.model.SubtitleStyle
    ) {
        val id = _uiState.value.selectedTextId ?: return
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    if (overlay.id == id) {
                        overlay.copy(
                            subtitleStyle = style,
                            fontSizeSp = style.fontSizeSp
                        )
                    } else overlay
                }
            )
        }
        persistProjectMeta()
    }

    /**
     * Apply a viral / caption style to EVERY `TextOverlay` on
     * the timeline � subtitle or decorative. Single undo step
     * via one `historyDelegate.push()` + one `_uiState.update`.
     */
    fun applyViralStyleToAll(
        style: com.aicreatorstudio.app.model.SubtitleStyle
    ) {
        historyDelegate.push()
        _uiState.update {
            it.copy(
                textOverlays = it.textOverlays.map { overlay ->
                    overlay.copy(subtitleStyle = style)
                }
            )
        }
        persistProjectMeta()
    }

    fun toggleTextEditMode() {
        _uiState.update { it.copy(isTextEditMode = !it.isTextEditMode) }
    }

    // Clip Selection
    fun selectClip(clipId: String?) {
        _uiState.update {
            it.copy(
                selectedClipId = clipId,
                // 2026-09-06 � selecting a video clip clears the audio
                // and text selections: exactly ONE selected object.
                selectedAudioClipId = null,
                selectedTextId = null
            )
        }
    }

    /**
     * 2026-09-06 � audio-clip SELECTION (Video Audio / Music / SFX
     * lanes). Selecting an audio object highlights its tile and opens
     * the Volume panel so mute / fades / volume apply to that audio
     * object. Deselect clears it. Selecting a video clip clears the
     * audio selection (and vice versa in the UI wiring) so there is
     * always exactly one selected object.
     */
    /**
     * 2026-09-28 IA (Q1) — the audio auto-open was REMOVED.
     *
     * Picking an audio object used to also set `toolPanel = VOLUME`, which
     * covered the video preview the moment the user tapped a tile. The
     * approved IA makes the dock a function of the selection instead: the
     * toolbar switches to the audio tool set (Beat Detection / Volume), so
     * the controls are one tap away without hijacking the preview.
     *
     * This is a real defect, not just a preference: it was the same
     * class of behaviour that made the old dock feel like it was
     * "deciding for you".
     */
    fun selectAudioClip(audioClipId: String?) {
        // 2026-09-07 DESELECT FIX — the doc promises "tapping the
        // same tile again deselects AND closes the panel", but the
        // code applied `selectedAudioClipId = audioClipId`
        // unconditionally, so the tile stayed selected forever and
        // every later Generate kept transcribing that (possibly
        // music/SFX) file. Now a second tap on the same tile clears
        // the selection.
        val isSameTile = audioClipId != null &&
            audioClipId == _uiState.value.selectedAudioClipId
        val effectiveId = if (isSameTile) null else audioClipId
        _uiState.update {
            it.copy(
                selectedAudioClipId = effectiveId,
                // Deselect the video clip when an audio object is
                // picked — one selected object at a time.
                selectedClipId = null,
                // Also clear the text selection so there is exactly
                // one selected object across the whole editor.
                selectedTextId = null,
                // 2026-09-28 — any panel that belonged to the previous
                // selection must close, since the toolbar now describes the
                // newly selected object.
                toolPanel = if (effectiveId == null) null else it.toolPanel
            )
        }
    }

    /**
     * 2026-09-06 � VIDEO AUDIO lane seeding (idempotent). For every
     * video clip that has no matching persisted AudioClip
     * (`originVideoClipId == clip.id`), create one spanning the
     * video's own timeline window. This makes the paired audio a
     * first-class, selectable object on the "Video Audio" lane under
     * the video. The audio objects are persisted via saveProjectMeta
     * so they survive reopen.
     */
    private fun seedVideoAudioClipsIfNeeded(project: com.aicreatorstudio.app.model.VideoProject) {
        val videos = project.clips.filter { !it.isAudioOnly }
        if (videos.isEmpty()) return
        val existingOrigins = project.audioClips.mapNotNull { it.originVideoClipId }.toSet()
        val missing = videos.filter { it.id !in existingOrigins }
        if (missing.isEmpty()) return

        val newAudio = missing.map { clip ->
            val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
            com.aicreatorstudio.app.model.AudioClip(
                id = UUID.randomUUID().toString(),
                sourceUri = clip.uri,
                fileName = clip.fileName,
                startTimeMs = start,
                durationMs = clip.effectiveDurationMs.coerceAtLeast(1L),
                volume = clip.volume,
                originVideoClipId = clip.id
            )
        }
        val merged = project.audioClips + newAudio
        _uiState.update { it.copy(project = project.copy(audioClips = merged)) }
        android.util.Log.i(
            "EditorViewModel",
            "seeded ${newAudio.size} video-audio clip(s) on the Video Audio lane"
        )
    }

    // Timeline placement � drag & drop support.
    //
    // `pushHistory` defaults to `true` for direct (non-continuous)
    // callers, but the drag-move path on the timeline sets it to
    // `false` after the first frame of a drag so the undo stack only
    // records one snapshot per drag instead of one per pointer frame
    // (which would exhaust the 30-entry undo stack in under a second
    // and silently clear the redo stack).
    fun updateClipPosition(
        clipId: String,
        newStartMs: Long,
        pushHistory: Boolean = true
    ) {
        if (pushHistory) historyDelegate.push()
        var movedAudio = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val target = project.clips.firstOrNull { it.id == clipId } ?: return@update state
            val oldStart = target.timelineStartMs.takeIf { it >= 0 } ?: 0L
            val start = newStartMs.coerceAtLeast(0L)
            val delta = start - oldStart
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(timelineStartMs = start) else clip
            }
            // 2026-10-19 — drag the soundtrack with the picture. The paired
            // AudioClip is a separate object, so moving only `clips` left the
            // "Video Audio" tile behind at the old position. The shift is the
            // same delta, clamped at 0 like the video's own start.
            val audioClips = if (delta == 0L) {
                project.audioClips
            } else {
                var shifted = false
                project.audioClips.map { audio ->
                    if (audio.originVideoClipId == clipId) {
                        shifted = true
                        audio.copy(startTimeMs = (audio.startTimeMs + delta).coerceAtLeast(0L))
                    } else {
                        audio
                    }
                }.also { movedAudio = shifted }
            }
            state.copy(project = project.copy(clips = clips, audioClips = audioClips))
        }
        persistClips()
        if (movedAudio) persistProjectMeta()
    }

    /** Imports an audio/music file as a new clip placed at the end of the Music lane. */
    fun addAudioClip(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                // Copy into app-owned storage so the audio track survives
                // app exit � same transient-grant fix as video import.
                val localUri = runCatching { mediaStorage.importToAppStorage(uri) }
                    .getOrDefault(uri)
                var fileName = localUri.lastPathSegment ?: "Audio track"
                var durationMs = 0L

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, localUri)
                    durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.let { fileName = it }
                } finally {
                    retriever.release()
                }

                if (durationMs <= 0L) return@launch

                historyDelegate.push()
                _uiState.update { state ->
                    val project = state.project ?: return@update state
                    // Place at the end of the existing Music lane (not all clips)
                    val musicLaneEnd = project.clips
                        .filter { it.isAudioOnly && it.audioCategory == "music" }
                        .maxOfOrNull { clip ->
                            val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                            start + clip.effectiveDurationMs
                        } ?: 0L
                    val audioClip = VideoClip(
                        id = UUID.randomUUID().toString(),
                        uri = localUri.toString(),
                        fileName = fileName,
                        durationMs = durationMs,
                        width = 0,
                        height = 0,
                        fileSizeBytes = 0L,
                        importedAtEpochMillis = System.currentTimeMillis(),
                        timelineStartMs = musicLaneEnd,
                        isAudioOnly = true,
                        audioCategory = "music"
                    )
                    state.copy(
                        project = project.copy(clips = project.clips + audioClip)
                    )
                }
                persistClips()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو).
     *
     * The user picks ANY video from device storage; the first audio track
     * is demuxed to a standalone `.m4a` in `filesDir/audio/` (fast
     * stream copy for AAC — no transcode, no quality loss) and lands on
     * the timeline as an independent audio clip at the playhead (or 0
     * when the project has no audio yet).
     *
     * Failure contract mirrors [addMediaError]: every expected failure is
     * named in `extractAudioError` (Persian) instead of a silent no-op,
     * and the dismissal goes through [consumeExtractAudioError]. The
     * whole run is cooperative — cancelling the ViewModel aborts the
     * extraction instantly through the service's `ensureActive` checks.
     *
     * The new object is selected (audio context, so the audio toolbar
     * opens) and pushed to the undo stack like every other mutation, so
     * one Undo removes both the clip and its history entry.
     */
    fun extractAudioFromVideo(uri: Uri) {
        if (_uiState.value.isExtractingAudio) return
        _uiState.update { it.copy(isExtractingAudio = true, extractAudioError = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { audioExtractionService.extract(uri) }
            }.getOrElse {
                com.aicreatorstudio.app.ai.media.AudioExtractionOutcome.Failure(
                    com.aicreatorstudio.app.model.AudioExtractionFailure.UNREADABLE_SOURCE
                )
            }
            when (outcome) {
                is com.aicreatorstudio.app.ai.media.AudioExtractionOutcome.Failure -> {
                    // 2026-10-13 — a demux or decode failure. The panel
                    // shows the Persian line; the log keeps the failure
                    // identity and the picked source's leaf name so a
                    // bad file can be identified without guessing.
                    omeletLog.warn(
                        component = "extract_audio",
                        event = "extract_failed",
                        message = outcome.failure.messageFa,
                        fields = mapOf("reason" to outcome.failure.name)
                    )
                    _uiState.update {
                        it.copy(
                            isExtractingAudio = false,
                            extractAudioError = outcome.failure.messageFa
                                .ifEmpty { null }
                        )
                    }
                }
                is com.aicreatorstudio.app.ai.media.AudioExtractionOutcome.Success -> {
                    val extracted = outcome.audio
                    // A zero duration would place an infinite-length object
                    // on the timeline — refuse it with the same message the
                    // service uses.
                    if (extracted.durationMs <= 0L) {
                        _uiState.update {
                            it.copy(
                                isExtractingAudio = false,
                                extractAudioError =
                                    com.aicreatorstudio.app.model
                                        .AudioExtractionFailure.MISSING_DURATION.messageFa
                            )
                        }
                        return@launch
                    }
                    val startMs = with(_uiState.value) {
                        com.aicreatorstudio.app.model.AudioExtractionPlanner.resolveStartMs(
                            playheadMs = playbackPositionMs,
                            hasExistingAudio = project?.clips?.any { it.isAudioOnly } == true
                        )
                    }
                    val clip = VideoClip(
                        id = UUID.randomUUID().toString(),
                        // The extracted file is app-owned storage (no
                        // transient picker grant to lose), so the clip
                        // references it directly.
                        uri = Uri.fromFile(java.io.File(extracted.filePath)).toString(),
                        fileName = com.aicreatorstudio.app.model.EXTRACTED_AUDIO_CLIP_NAME,
                        durationMs = extracted.durationMs,
                        width = 0,
                        height = 0,
                        fileSizeBytes = java.io.File(extracted.filePath).length(),
                        importedAtEpochMillis = System.currentTimeMillis(),
                        timelineStartMs = startMs,
                        isAudioOnly = true,
                        audioCategory = "music"
                    )
                    historyDelegate.push()
                    _uiState.update { state ->
                        val project = state.project ?: return@update state
                        state.copy(
                            project = project.copy(clips = project.clips + clip),
                            isExtractingAudio = false,
                            extractAudioError = null,
                            editorConfirmation =
                                com.aicreatorstudio.app.model.EXTRACT_AUDIO_SUCCESS_MESSAGE
                        )
                    }
                    // Select the new object in the AUDIO context (not
                    // selectedClipId — that would open the video-clip
                    // toolbar) so the audio tools open on the result.
                    timeline.selectAudioClip(clip.id)
                    ensureWaveformsFor(_uiState.value.project)
                    persistClips()
                }
            }
        }
    }

    /** Clears the extract-audio failure banner (the user dismissed it). */
    fun consumeExtractAudioError() {
        _uiState.update { it.copy(extractAudioError = null) }
    }

    /**
     * 2026-10-05 — adds a picked video to the OPEN project, CapCut-style.
     *
     * Until now `ProjectRepository.addClipToProject` had no UI caller: a
     * project could only ever hold the single clip it was created with, so
     * the whole multi-clip pipeline (playlist preview, multi-segment
     * export) was unreachable from the app. This is the missing entry
     * point behind the timeline "+" button.
     *
     * Flow mirrors the import screen exactly: copy into app-owned storage
     * (the picker grant is transient), extract metadata from the LOCAL
     * copy, append at the end of the timeline, seed the Video-Audio lane
     * object, persist via [persistClips] and select the new clip so the
     * preview playlist seeks to it (the selection-seek effect owns that).
     */
    fun addClipFromUri(uri: android.net.Uri) {
        if (_uiState.value.isAddingMedia) return
        _uiState.update { it.copy(isAddingMedia = true, addMediaError = null) }
        viewModelScope.launch {
            runCatching {
                // Copy FIRST (like the import screen): the extractor and the
                // editor both want a stable file:// uri that outlives the
                // process, and the metadata probe then runs on our own file.
                val localUri = mediaStorage.importToAppStorage(uri)
                metadataExtractor.extract(localUri)
                    .copy(
                        id = UUID.randomUUID().toString(),
                        uri = localUri.toString()
                    )
            }.onSuccess { newClip ->
                historyDelegate.push()
                _uiState.update { state ->
                    val project = state.project ?: return@update state
                    // Append at the end of the drawn timeline: the rightmost
                    // clip edge across all clips (gaps don't extend it).
                    val nextStart = project.clips.maxOfOrNull { clip ->
                        clip.timelineStartMs.coerceAtLeast(0L) + clip.effectiveDurationMs
                    } ?: 0L
                    val placed = newClip.copy(timelineStartMs = nextStart)
                    state.copy(
                        project = project.copy(clips = project.clips + placed),
                        selectedClipId = placed.id,
                        isAddingMedia = false
                    )
                }
                // The new video needs its Video-Audio lane object like every
                // clip the project loaded with.
                _uiState.value.project?.let { seedVideoAudioClipsIfNeeded(it) }
                ensureWaveformsFor(_uiState.value.project)
                ensureThumbnailsFor(_uiState.value.project)
                persistClips()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAddingMedia = false,
                        addMediaError =
                            "افزودن ویدیو ناموفق بود: ${error.message ?: "فایل قابل خواندن نبود"}"
                    )
                }
            }
        }
    }

    /** Clears the add-clip error banner (the user dismissed it). */
    fun consumeAddMediaError() {
        _uiState.update { it.copy(addMediaError = null) }
    }

    /** PHASE A — clears the one-shot core-edit notice. */
    fun consumeEditorNotice() {
        _uiState.update { it.copy(editorNotice = null) }
    }

    /**
     * 2026-10-13 — clears the one-shot positive confirmation (a font
     * import / delete that succeeded). Sibling of [consumeEditorNotice],
     * which carries refusals.
     */
    fun consumeEditorConfirmation() {
        _uiState.update { it.copy(editorConfirmation = null) }
    }

    /**
     * PHASE A — states why the delete was refused instead of dropping
     * the tap silently.
     */
    fun notifyLastClipDeleteRefused() {
        _uiState.update { it.copy(editorNotice = "حداقل یک کلیپ باید در پروژه باقی بماند") }
    }

    /**
     * 2026-10-19 — «برش» was tapped with the playhead on (or outside) an
     * edge of the selected object, so there is nothing to cut. Stated
     * instead of dropped silently: the tile looks untouched either way, and
     * without a line the button reads as broken.
     */
    fun notifySplitNotPossible() {
        _uiState.update {
            it.copy(editorNotice = "نقطه برش باید داخل محدوده کلیپ باشد")
        }
    }

    // ------------------------------------------------------------------
    // 2026-10-05 — REAL timeline waveforms.
    //
    // The model already carried `AudioClip.waveformCacheKey` ("wf-<id>",
    // persisted) and `WaveformGenerator.compute` existed with no caller,
    // while the timeline drew procedural sine-noise — exactly the
    // "synthetic waveform" the project brief forbids. This completes the
    // designed slot: decode each audio lane clip's real PCM once, compute
    // 50 ms peaks, cache them per key, and publish them for the timeline.
    //
    // Memory: peaks are ~20 floats/second — a 5-minute clip is ~6 000
    // small objects (~100 KB), not the PCM itself. Failures degrade to
    // the placeholder bars (documented as a loading state), never a
    // fabricated waveform presented as real.
    // ------------------------------------------------------------------
    private val _waveforms =
        MutableStateFlow<Map<String, List<com.aicreatorstudio.app.model.AudioWaveformPeak>>>(
            emptyMap()
        )
    val waveforms:
        StateFlow<Map<String, List<com.aicreatorstudio.app.model.AudioWaveformPeak>>> =
        _waveforms.asStateFlow()

    private val computingWaveformKeys = mutableSetOf<String>()

    // ------------------------------------------------------------------
    // 2026-10-05 — REAL filmstrip thumbnails for the video lane.
    //
    // The timeline's `showThumbnails` parameter existed but nothing ever
    // fed it. This decodes a handful of small sync frames per video clip
    // (MediaMetadataRetriever, IO dispatcher) and publishes them as
    // ImageBitmaps. Keyed by "<clipId>@<uri>" so a relinked clip
    // recomputes. ~6 frames at 96px wide ≈ 0.5 MB per clip — bounded.
    // Frames are OPTION_CLOSEST_SYNC and ignore trim drift (a trim only
    // shifts the window over the same source), which is plenty for a
    // filmstrip.
    // ------------------------------------------------------------------
    private val _clipThumbnails =
        MutableStateFlow<Map<String, List<androidx.compose.ui.graphics.ImageBitmap>>>(
            emptyMap()
        )
    val clipThumbnails:
        StateFlow<Map<String, List<androidx.compose.ui.graphics.ImageBitmap>>> =
        _clipThumbnails.asStateFlow()

    private val computingThumbnailKeys = mutableSetOf<String>()

    /** Kicks off background filmstrip decoding for every video clip in [project]. */
    fun ensureThumbnailsFor(project: com.aicreatorstudio.app.model.VideoProject?) {
        if (project == null) return
        project.clips
            .filter { !it.isAudioOnly && it.uri.isNotBlank() }
            .forEach { clip ->
                val key = "${clip.id}@${clip.uri}"
                if (key in computingThumbnailKeys || _clipThumbnails.value.containsKey(key)) {
                    return@forEach
                }
                computingThumbnailKeys.add(key)
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(appContext, Uri.parse(clip.uri))
                            val span = clip.effectiveDurationMs.coerceAtLeast(1L)
                            val count = 6
                            (0 until count).mapNotNull { i ->
                                val atUs =
                                    (clip.trimStartMs.coerceAtLeast(0L) + span * i / count) * 1000L
                                retriever.getFrameAtTime(
                                    atUs,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                                )?.let { frame ->
                                    val targetW = 96
                                    val scale = targetW.toFloat() / frame.width.coerceAtLeast(1)
                                    val targetH =
                                        (frame.height * scale).toInt().coerceAtLeast(1)
                                    android.graphics.Bitmap.createScaledBitmap(
                                        frame, targetW, targetH, true
                                    ).asImageBitmap()
                                }
                            }
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }.onSuccess { frames ->
                        if (frames.isNotEmpty()) {
                            _clipThumbnails.update { it + (key to frames) }
                        }
                    }.also {
                        computingThumbnailKeys.remove(key)
                    }
                }
            }
    }

    /**
     * Kicks off background peak computation for every audio-bearing lane
     * object in [project]: imported music/SFX (`VideoClip.isAudioOnly`,
     * keyed by the clip id) and paired video audio (`AudioClip`, keyed by
     * its persisted `waveformCacheKey`). Already-cached or in-flight keys
     * are skipped; failures release the key so a retry can run later.
     */
    fun ensureWaveformsFor(project: com.aicreatorstudio.app.model.VideoProject?) {
        if (project == null) return
        val jobs = buildList {
            project.clips
                .filter { it.isAudioOnly }
                .forEach { clip -> add(clip.id to clip.uri) }
            project.audioClips.forEach { clip ->
                clip.sourceUri?.takeIf { it.isNotBlank() }?.let { uri ->
                    add((clip.waveformCacheKey ?: clip.id) to uri)
                }
            }
        }
        jobs.forEach { (key, uri) ->
            if (key in computingWaveformKeys || _waveforms.value.containsKey(key)) return@forEach
            if (!uri.startsWith("file://") && !uri.startsWith("content://")) return@forEach
            computingWaveformKeys.add(key)
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val (samples, sampleRate, _) = audioExtractor.decodeToMonoFloat(Uri.parse(uri))
                    com.aicreatorstudio.app.model.WaveformGenerator.compute(
                        samples = samples,
                        sampleRateHz = sampleRate,
                        bucketMs = com.aicreatorstudio.app.model.WaveformGenerator.DEFAULT_BUCKET_MS
                    )
                }.onSuccess { peaks ->
                    _waveforms.update { it + (key to peaks) }
                }.also {
                    computingWaveformKeys.remove(key)
                }
            }
        }
    }

    /**
     * 2026-10-05 — persistClips used to launch on `viewModelScope`, which
     * [onCleared] cancels the moment the user leaves the editor: a clip
     * save in flight was cancelled mid-write and the edit was lost (seen
     * live when a quick process kill right after an add-clip dropped the
     * new clip). Clip and meta writes now share one process-lifetime
     * scope that is NEVER cancelled — writes are tiny single-row updates
     * and the scope dies with the process anyway.
     */
    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Persists the current clip list so edits survive leaving the editor.
     *
     * PHASE A (Persistence Integrity) — also writes the AI-tools blob in
     * the same call. `VideoClip` carries per-clip state that lives in
     * TWO stores: plain columns (trims, volume, speed, transitions, the
     * legacy B/C/S mirrors) and the blob (matting, autoFrame, ramps,
     * highlights, and now the look settings). Clipping only wrote the
     * columns, so any edit to a blob-only field was silently lost
     * unless the caller happened to also invoke `saveAiTools`.
     *
     * One write-through per clip mutation is the regression guard: a
     * field added to `VideoClip` + `ClipAiTools` in future is persisted
     * by every existing edit path without remembering to call twice.
     */
    private fun persistClips() {
        val project = _uiState.value.project ?: return
        persistScope.launch {
            runCatching { projectRepository.saveClips(project.id, project.clips) }
            runCatching {
                projectRepository.saveAiTools(
                    projectId = project.id,
                    tools = project.clips.toClipAiTools()
                )
            }
        }
    }

    /**
     * Persists the editor's project-level state — canvas preset and
     * background, captions/AI subtitles, media overlays and the
     * playhead position — so a saved project reopens exactly as the
     * user left it (SAVE -> EXIT -> REOPEN). Fire-and-forget: callers
     * are user-interaction paths and persistence failures must never
     * surface in the editing flow.
     */
    private fun persistProjectMeta() {
        val state = _uiState.value
        val project = state.project ?: return
        persistScope.launch {
            runCatching {
                projectRepository.saveProjectMeta(
                    projectId = project.id,
                    canvasPreset = state.canvasPreset,
                    canvasBackground = state.canvasBackground,
                    textOverlays = state.textOverlays,
                    mediaOverlays = state.overlays,
                    audioClips = project.audioClips,
                    lastPositionMs = state.playbackPositionMs.coerceAtLeast(0L),
                    progressBarSettings = state.progressBarSettings
                )
            }
        }
    }

    /** Consumed by the screen after it seeks the player to the saved position. */
    fun consumeRestoreSeekMs() {
        _uiState.update { it.copy(pendingRestoreSeekMs = null) }
    }

    /**
     * "Media Missing / Relink" action: copies the user-picked replacement
     * video into app-owned storage and rewrites the first missing clip's
     * URI. The preview re-arms on its own because the media URI in the
     * UI state changes (the screen's media-item effect is keyed on it).
     */
    fun relinkMissingMedia(uriString: String) {
        val target = _uiState.value.mediaMissingClips.firstOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mediaStorage.importToAppStorage(Uri.parse(uriString)) }
                .onSuccess { localUri ->
                    _uiState.update { state ->
                        val project = state.project ?: return@update state
                        state.copy(
                            project = project.copy(
                                clips = project.clips.map { clip ->
                                    if (clip.id == target.id) {
                                        clip.copy(uri = localUri.toString())
                                    } else clip
                                }
                            ),
                            mediaMissingClips = state.mediaMissingClips
                                .filterNot { it.id == target.id }
                        )
                    }
                    persistClips()
                }
                .onFailure { error ->
                    omeletLog.error(
                        component = "editor",
                        event = "relink_media_failed",
                        message = error.message ?: error::class.java.simpleName,
                        throwable = error
                    )
                    _uiState.update {
                        it.copy(
                            errorMessage =
                                "ذخیرهٔ فایل جایگزین ناموفق بود: ${error.message}"
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        // The playhead is part of the saved project state � snapshot it
        // one last time when the editor closes so reopening lands where
        // the user left off.
        persistProjectMeta()
        // Cancel any in-flight AI tools so they don't leak past the
        // ViewModel's lifetime. Engines themselves are singletons so
        // they keep their (lightweight) state for the next editor.
        aiToolJobs.values.toList().forEach { it.cancel() }
        // Cancel any in-flight AI upscale run for the same reason.
        activeUpscaleJob?.cancel()
        activeUpscaleJob = null
        // PHASE B3 � cancel any in-flight AI subtitle generation.
        // Mirrors the aiToolJobs / activeUpscaleJob pattern above.
        // The Whisper engine itself is a singleton and survives
        // ViewModel teardown; we only stop the coroutine that
        // is using it.
        subtitleGenerationJob?.cancel()
        subtitleGenerationJob = null
        // 2026-10-21 — release the voiceover recorder. A live
        // MediaRecorder holds the microphone, so leaving it running
        // past the ViewModel's lifetime would keep recording into a
        // file nobody can reach.
        stopVoiceoverMeter()
        voiceoverRecorder.cancel()
        super.onCleared()
    }

    // ====================================================================
    // Voiceover (2026-10-21) — «نریشن»
    //
    // CapCut's voiceover records a narration over the video and drops
    // it on the timeline as its own audio object. This project had
    // every piece of that EXCEPT the capture: an audio lane, audio
    // clips with volume and fades, an audio mixer, and an offline
    // voice changer — but no recorder and no `RECORD_AUDIO` grant.
    //
    // The take is written into the app's own storage and then added to
    // the project the same way an imported music file is, so nothing
    // downstream needs to know it was recorded rather than picked.
    // ====================================================================

    private val voiceoverRecorder = com.aicreatorstudio.app.audio.VoiceoverRecorder(appContext)
    private var voiceoverMeterJob: kotlinx.coroutines.Job? = null

    /**
     * Begin a voiceover take.
     *
     * The caller is responsible for the `RECORD_AUDIO` grant: the
     * panel requests it before calling this, and a denied grant is
     * reported through [VoiceoverState.Failed] rather than by
     * throwing.
     */
    fun startVoiceover() {
        if (voiceoverRecorder.isRecording) return
        val now = System.currentTimeMillis()
        val target = com.aicreatorstudio.app.audio.VoiceoverRecorder
            .outputFile(appContext, now)
        voiceoverRecorder.start(target)
            .onSuccess {
                _uiState.update {
                    it.copy(
                        voiceoverState = com.aicreatorstudio.app.model.VoiceoverState.Recording(
                            startedAtEpochMs = now,
                            level = 0f,
                            elapsedMs = 0L
                        )
                    )
                }
                startVoiceoverMeter()
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        voiceoverState = com.aicreatorstudio.app.model.VoiceoverState.Failed(
                            message = "ضبط صدا شروع نشد: ${error.message ?: "خطای نامشخص"}"
                        )
                    )
                }
            }
    }

    /**
     * Stop the take and keep it as a pending capture.
     *
     * The take is NOT on the timeline yet — the user confirms it, so a
     * mis-fired recording does not leave a stray clip behind.
     */
    fun stopVoiceover() {
        if (!voiceoverRecorder.isRecording) return
        stopVoiceoverMeter()
        val startedAt = voiceoverRecorder.startedAtEpochMs()
        voiceoverRecorder.stop()
            .onSuccess { file ->
                val durationMs = runCatching {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(appContext, android.net.Uri.fromFile(file))
                        retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                    } finally {
                        retriever.release()
                    }
                }.getOrDefault(0L)
                _uiState.update {
                    it.copy(
                        voiceoverState = com.aicreatorstudio.app.model.VoiceoverState.Captured(
                            uri = file.toURI().toString(),
                            durationMs = durationMs
                        )
                    )
                }
            }
            .onFailure {
                _uiState.update {
                    it.copy(
                        voiceoverState = com.aicreatorstudio.app.model.VoiceoverState.Failed(
                            message = "ضبط صدا ناموفق بود. دوباره تلاش کنید."
                        )
                    )
                }
            }
        // `startedAt` is read for the state's timestamp; the take's own
        // length comes from the container, not from the clock.
        @Suppress("UNUSED_EXPRESSION") startedAt
    }

    /** Throw the pending take away and return to idle. */
    fun cancelVoiceover() {
        stopVoiceoverMeter()
        voiceoverRecorder.cancel()
        _uiState.update {
            it.copy(
                voiceoverState = com.aicreatorstudio.app.model.VoiceoverState.Idle,
                pendingVoiceoverUri = null
            )
        }
    }

    /**
     * Put the captured take on the timeline at the playhead.
     *
     * It becomes an audio-only clip in the project's own clip list —
     * the same shape an imported music file has, so the mixer, the
     * waveform, the volume slider and the export all treat it exactly
     * like any other audio object. The category is `voice` so the
     * timeline can tell a narration from a music bed.
     */
    fun placeVoiceover() {
        val captured = _uiState.value.voiceoverState
            as? com.aicreatorstudio.app.model.VoiceoverState.Captured ?: return
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clip = VideoClip(
                id = java.util.UUID.randomUUID().toString(),
                uri = captured.uri,
                fileName = "نریشن",
                durationMs = captured.durationMs,
                width = 0,
                height = 0,
                fileSizeBytes = 0L,
                importedAtEpochMillis = System.currentTimeMillis(),
                timelineStartMs = state.playbackPositionMs.coerceAtLeast(0L),
                isAudioOnly = true,
                audioCategory = "voice"
            )
            state.copy(
                project = project.copy(clips = project.clips + clip),
                voiceoverState = com.aicreatorstudio.app.model.VoiceoverState.Idle,
                pendingVoiceoverUri = null
            )
        }
        persistClips()
    }

    /** Dismiss a failure message and return to idle. */
    fun consumeVoiceoverError() {
        _uiState.update {
            if (it.voiceoverState is com.aicreatorstudio.app.model.VoiceoverState.Failed) {
                it.copy(voiceoverState = com.aicreatorstudio.app.model.VoiceoverState.Idle)
            } else {
                it
            }
        }
    }

    /** Poll the recorder's level so the panel can draw a live meter. */
    private fun startVoiceoverMeter() {
        stopVoiceoverMeter()
        voiceoverMeterJob = viewModelScope.launch {
            while (voiceoverRecorder.isRecording) {
                val level = voiceoverRecorder.level()
                val elapsed = System.currentTimeMillis() -
                    voiceoverRecorder.startedAtEpochMs()
                _uiState.update { state ->
                    val recording = state.voiceoverState
                        as? com.aicreatorstudio.app.model.VoiceoverState.Recording
                        ?: return@update state
                    state.copy(
                        voiceoverState = recording.copy(
                            level = level,
                            elapsedMs = elapsed.coerceAtLeast(0L)
                        )
                    )
                }
                delay(80L)
            }
        }
    }

    private fun stopVoiceoverMeter() {
        voiceoverMeterJob?.cancel()
        voiceoverMeterJob = null
    }

    // Transitions
    fun setClipTransition(
        clipId: String,
        scope: com.aicreatorstudio.app.model.TransitionScope,
        config: com.aicreatorstudio.app.model.TransitionConfig?
    ) {
        historyDelegate.push()
        val encoded = config?.encode()
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id != clipId) clip else when (scope) {
                    com.aicreatorstudio.app.model.TransitionScope.IN ->
                        clip.copy(transitionIn = encoded)
                    com.aicreatorstudio.app.model.TransitionScope.OUT ->
                        clip.copy(transitionOut = encoded)
                    com.aicreatorstudio.app.model.TransitionScope.BOTH ->
                        clip.copy(transitionIn = encoded, transitionOut = encoded)
                }
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    // Clip Edit Actions

    /**
     * Splits the given clip at the supplied **global timeline position**
     * (the playhead). If [clipId] is blank/no clip matches, the video clip
     * currently under the playhead is split automatically.
     *
     * PHASE 6: [pushHistory]/[persist] allow batch callers (beat sync)
     * to push exactly ONE undo entry and persist once for many cuts —
     * same convention as `setClipTrim(..., pushHistory)`. Defaults keep
     * every existing single-cut caller unchanged.
     *
     * 2026-10-19 — the cut now reaches the AUDIO lane as well. A video
     * clip's soundtrack is a first-class [AudioClip] on the "Video Audio"
     * lane (see `seedVideoAudioClipsIfNeeded`); before this change the
     * split only reshaped `project.clips`, so the lane below kept one
     * unbroken tile spanning both halves. Splitting the paired object at
     * the same playhead position keeps the two lanes describing the same
     * edit, and makes the per-piece volume / mute / fades addressable.
     *
     * @return true when the cut was applied. `false` covers "no such
     *   clip" and "the playhead is on or outside an edge" — callers that
     *   cut from an audio selection use it to avoid reporting success.
     */
    fun splitClip(
        clipId: String,
        splitTimelineMs: Long,
        pushHistory: Boolean = true,
        persist: Boolean = true
    ): Boolean {
        if (pushHistory) historyDelegate.push()
        var applied = false
        var audioSplit = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clip = project.clips.firstOrNull { it.id == clipId }
                ?: project.clips.firstOrNull {
                    !it.isAudioOnly && splitTimelineMs >= it.timelineStartMs.coerceAtLeast(0L) &&
                        splitTimelineMs < it.timelineStartMs.coerceAtLeast(0L) + it.effectiveDurationMs
                }
                ?: return@update state

            val base = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
            val effectiveEnd = clip.trimEndMs.takeIf { it > 0 } ?: clip.durationMs
            // Convert global playhead time to clip-local trim coordinates
            val localPos = splitTimelineMs - base + clip.trimStartMs
            if (localPos <= clip.trimStartMs || localPos >= effectiveEnd) {
                return@update state
            }

            val clips = project.clips.toMutableList()
            val clipIndex = clips.indexOfFirst { it.id == clip.id }

            // 2026-09-29 — slice the speed ramp at the split point.
            //
            // Both halves used to inherit the *whole* curve, because
            // `copy(trimEndMs = …)` / `copy(trimStartMs = …)` leave
            // every other field alone. A ramp is keyed on the source
            // span, and a trim does not change `normalizedTime` — so
            // each half would have replayed the entire ramp compressed
            // into its own shorter span: cut a HERO ramp in the middle
            // and the first half would slow down, punch fast and land
            // slow all over again instead of playing only its own
            // portion. The user sees a split that changes the speed
            // curve, which is not what a cut should ever do.
            //
            // `SpeedCurveInterpolator.split` renormalises the two
            // windows onto their own 0..1 and pins both interior edges
            // by sampling the original, so the ramp is continuous
            // across the join. Null (the common case) passes straight
            // through.
            val splitFraction = ((localPos - clip.trimStartMs).toDouble() /
                (effectiveEnd - clip.trimStartMs).toDouble().coerceAtLeast(1.0))
                .toFloat()
            val (leftCurve, rightCurve) = com.aicreatorstudio.app.model.util
                .SpeedCurveInterpolator.split(clip.speedCurve, splitFraction)

            val firstClip = clip.copy(
                trimEndMs = localPos,
                speedCurve = leftCurve.takeIf { it.isActive }
            )
            val secondClip = clip.copy(
                id = java.util.UUID.randomUUID().toString(),
                trimStartMs = localPos,
                // Place the right part immediately after the left part
                timelineStartMs = base + (localPos - clip.trimStartMs),
                speedCurve = rightCurve.takeIf { it.isActive }
            )

            clips.removeAt(clipIndex)
            clips.add(clipIndex, firstClip)
            clips.add(clipIndex + 1, secondClip)

            // 2026-10-19 — carry the cut into the "Video Audio" lane. The
            // right audio half is retargeted at the NEW video clip so the
            // one-audio-object-per-video invariant (and therefore
            // `seedVideoAudioClipsIfNeeded`) still holds after a reload.
            val splitAudioClips = splitPairedAudioClips(
                audioClips = project.audioClips,
                videoClipId = clip.id,
                rightVideoClipId = secondClip.id,
                atTimelineMs = splitTimelineMs
            )
            audioSplit = splitAudioClips != null
            applied = true

            state.copy(
                project = project.copy(
                    clips = clips,
                    audioClips = splitAudioClips ?: project.audioClips
                ),
                selectedClipId = firstClip.id
            )
        }
        if (!persist) return applied
        persistClips()
        // The audio lane lives in a different table (`audioClipsJson`), so
        // a split that touched it needs the meta write as well.
        if (audioSplit) persistProjectMeta()
        // The two halves carry different ramps from here on, so the
        // AI-tools blob has to be rewritten as well as the clip table.
        // Read the state back rather than capturing it: `_uiState.update`
        // above runs in its own lambda whose parameter is out of scope
        // here.
        // PHASE 6: batch callers persist once after the loop instead.
        if (_uiState.value.project?.clips?.any { it.speedCurve != null } == true) {
            persistSpeedCurves()
        }
        return applied
    }

    /**
     * 2026-10-19 — split the audio object that belongs to [videoClipId] at
     * [atTimelineMs], retargeting the right half at [rightVideoClipId].
     *
     * Returns the new list, or `null` when there is nothing to split (no
     * paired audio, or the position falls on/outside the audio span). A
     * `null` is deliberately not an error: projects imported before the
     * Video Audio lane existed have video clips with no audio object, and
     * a cut on an edge is a no-op by definition.
     *
     * Only the FIRST object with this origin is split. `originVideoClipId`
     * is unique by construction (see `seedVideoAudioClipsIfNeeded`), so
     * "first" and "the" are the same thing here.
     */
    private fun splitPairedAudioClips(
        audioClips: List<com.aicreatorstudio.app.model.AudioClip>,
        videoClipId: String,
        rightVideoClipId: String,
        atTimelineMs: Long
    ): List<com.aicreatorstudio.app.model.AudioClip>? {
        val index = audioClips.indexOfFirst { it.originVideoClipId == videoClipId }
        if (index < 0) return null
        val halves = com.aicreatorstudio.app.model.AudioClipSplit.split(
            clip = audioClips[index],
            atTimelineMs = atTimelineMs,
            rightOriginVideoClipId = rightVideoClipId
        ) ?: return null
        return audioClips.toMutableList().apply {
            removeAt(index)
            add(index, halves.first)
            add(index + 1, halves.second)
        }
    }

    /**
     * 2026-10-19 — «برش» for an AUDIO object (Video Audio lane).
     *
     * ## Why a paired object is cut through [splitClip]
     *
     * An [com.aicreatorstudio.app.model.AudioClip] with a non-null
     * `originVideoClipId` IS its video clip's soundtrack. Splitting only
     * the audio object would leave the video lane showing one clip while
     * the audio lane shows two, and — worse — would leave two audio
     * objects pointing at the same `originVideoClipId`, which breaks the
     * one-object-per-video invariant that project loading relies on.
     * So a paired object delegates to [splitClip], which cuts both lanes
     * in one undo step; only standalone objects (no origin) are split
     * here.
     *
     * @return true when the cut was applied.
     */
    fun splitAudioClip(audioClipId: String, splitTimelineMs: Long): Boolean {
        val project = _uiState.value.project ?: return false
        val audio = project.audioClips.firstOrNull { it.id == audioClipId } ?: return false

        val parentVideoId = audio.originVideoClipId
        if (parentVideoId != null && project.clips.any { it.id == parentVideoId }) {
            val applied = splitClip(parentVideoId, splitTimelineMs)
            if (!applied) return false
            // The user cut from the AUDIO toolbar, so hand the selection
            // back to the left audio half. splitClip selects the left VIDEO
            // half, and leaving both ids set would violate the
            // one-selected-object invariant the toolbar is built on.
            _uiState.update { state ->
                val leftHalfId = state.project?.audioClips
                    ?.firstOrNull { it.originVideoClipId == parentVideoId }
                    ?.id
                state.copy(
                    selectedAudioClipId = leftHalfId,
                    selectedClipId = if (leftHalfId != null) null else state.selectedClipId
                )
            }
            return true
        }

        val halves = com.aicreatorstudio.app.model.AudioClipSplit.split(
            clip = audio,
            atTimelineMs = splitTimelineMs
        ) ?: return false

        historyDelegate.push()
        _uiState.update { state ->
            val p = state.project ?: return@update state
            val index = p.audioClips.indexOfFirst { it.id == audioClipId }
            if (index < 0) return@update state
            state.copy(
                project = p.copy(
                    audioClips = p.audioClips.toMutableList().apply {
                        removeAt(index)
                        add(index, halves.first)
                        add(index + 1, halves.second)
                    }
                ),
                selectedAudioClipId = halves.first.id
            )
        }
        persistProjectMeta()
        return true
    }

    /**
     * PHASE 6 — split one clip at many timeline positions with exactly
     * ONE history entry. [cutsTimelineMs] must be normalized (strictly
     * inside, ascending, deduped — see `BeatSyncPlanner.normalizeCuts`);
     * applied last-to-first so earlier positions stay valid. Returns
     * the number of cuts actually applied.
     */
    fun splitClipMultiple(clipId: String, cutsTimelineMs: List<Long>): Int {
        if (cutsTimelineMs.isEmpty()) return 0
        historyDelegate.push()
        var applied = 0
        val before = _uiState.value.project?.clips?.size ?: 0
        // 2026-10-19 — every cut also splits the paired audio object, so the
        // audio table has to be written once at the end alongside the clips.
        val audioBefore = _uiState.value.project?.audioClips?.size ?: 0
        for (cut in cutsTimelineMs.sortedDescending()) {
            splitClip(clipId, cut, pushHistory = false, persist = false)
            if ((_uiState.value.project?.clips?.size ?: 0) > before + applied) applied++
        }
        // Selection lands on the first piece (splitClip selects the
        // left remainder each time); keep it — deterministic.
        persistClips()
        if ((_uiState.value.project?.audioClips?.size ?: 0) > audioBefore) {
            persistProjectMeta()
        }
        if (_uiState.value.project?.clips?.any { it.speedCurve != null } == true) {
            persistSpeedCurves()
        }
        return applied
    }

    /**
     * PHASE A (Persistence Integrity) — deleting the very last clip is
     * refused. It used to succeed and leave an editor with no timeline,
     * where every panel shows an empty state and the preview is a bare
     * rectangle: from the user's side the app looks broken rather than
     * "empty by choice". Returning `false` lets the UI say so.
     *
     * 2026-10-19 — the paired AUDIO object is deleted with its video clip.
     * Deleting only `project.clips` left the "Video Audio" lane showing a
     * soundtrack tile for a clip that no longer existed — the mirror of the
     * split bug fixed earlier the same day. Both tables are written
     * (`persistClips` + `persistProjectMeta`), because a reload would
     * otherwise bring the orphan tile straight back from `audioClipsJson`.
     *
     * @return true when the clip was deleted, false when it was the
     *   last one and the operation was refused.
     */
    fun deleteClip(clipId: String): Boolean {
        val project = _uiState.value.project ?: return false
        if (project.clips.count { it.id != clipId } == 0) return false
        historyDelegate.push()
        var removedAudio = false
        _uiState.update { state ->
            val p = state.project ?: return@update state
            val clips = p.clips.filter { it.id != clipId }
            // The soundtrack is a separate object; it must not outlive the
            // clip it belongs to.
            val audioClips = p.audioClips.filter { it.originVideoClipId != clipId }
            removedAudio = audioClips.size != p.audioClips.size
            val removedAudioIds = p.audioClips
                .filter { it.originVideoClipId == clipId }
                .mapTo(HashSet()) { it.id }
            state.copy(
                project = p.copy(clips = clips, audioClips = audioClips),
                selectedClipId = if (state.selectedClipId == clipId) null else state.selectedClipId,
                // The audio selection cannot survive its own object.
                selectedAudioClipId = state.selectedAudioClipId
                    ?.takeUnless { it in removedAudioIds }
            )
        }
        persistClips()
        if (removedAudio) persistProjectMeta()
        return true
    }

    /**
     * 2026-10-20 — delete a selected **audio** object.
     *
     * Delete was video-only: `deleteClip` filters `project.clips` and
     * `ContextualToolbarSpec.trailingActionsFor(AUDIO_SELECTED)` returned an
     * empty list, so an imported music track (or an extracted soundtrack)
     * could be split but never removed. Because `selectAudioClip` clears
     * `selectedClipId`, the old `"delete"` handler also silently no-oped while
     * an audio object was selected — which is what the "delete only works on
     * the video timeline" report described.
     *
     * The two kinds of audio object behave differently, and the difference is
     * structural rather than cosmetic:
     *
     *  - **Standalone audio** (`originVideoClipId == null`: imported music,
     *    sfx, extracted tracks) — removing the row is the whole job, and it
     *    persists, because `seedVideoAudioClipsIfNeeded` only ever creates
     *    rows for *video* clips.
     *  - **Paired audio** (`originVideoClipId != null`: the "Video Audio"
     *    lane) — this row is a *view* of the owning clip's own soundtrack.
     *    Deleting it cannot stick: `seedVideoAudioClipsIfNeeded` rebuilds a
     *    paired row for every video clip that has none, so the tile would be
     *    back on the next project load, and the exporter re-extracts the
     *    soundtrack from the video source anyway. Detaching needs a new
     *    persisted "detached" flag on the clip (model + `EditorStateJson` +
     *    `RoomProjectRepository.toVideoProject` + `RenderSegment`), which is a
     *    schema change this button does not justify. So the request is
     *    **refused with a reason** — muting via the Volume panel is the
     *    supported way to silence a clip, and it is what the notice says.
     *
     * There is deliberately no "last object" refusal (unlike [deleteClip]):
     * an empty audio lane is a normal project state, whereas an empty video
     * timeline leaves the editor unusable.
     *
     * @return [AudioDeleteOutcome.Deleted] when the object was removed,
     *   [AudioDeleteOutcome.NotFound] for a stale id, or
     *   [AudioDeleteOutcome.PairedAudio] when the object belongs to a clip.
     */
    fun deleteAudioClip(audioClipId: String): AudioDeleteOutcome {
        val project = _uiState.value.project ?: return AudioDeleteOutcome.NotFound
        val target = project.audioClips.firstOrNull { it.id == audioClipId }
            ?: return AudioDeleteOutcome.NotFound
        // A paired row is the clip's own soundtrack; see the KDoc above.
        if (target.originVideoClipId != null) return AudioDeleteOutcome.PairedAudio
        historyDelegate.push()
        _uiState.update { state ->
            val p = state.project ?: return@update state
            val isSelected = state.selectedAudioClipId == audioClipId
            state.copy(
                project = p.copy(audioClips = p.audioClips.filter { it.id != audioClipId }),
                // The selection cannot survive its own object.
                selectedAudioClipId = state.selectedAudioClipId
                    ?.takeUnless { it == audioClipId },
                // The audio toolbar described this object; with it gone the
                // panel would describe nothing.
                toolPanel = if (isSelected) null else state.toolPanel
            )
        }
        // audioClips live in the project meta blob, not the clips table.
        persistProjectMeta()
        return AudioDeleteOutcome.Deleted
    }

    /**
     * 2026-10-20 — drag-to-move for an **audio** object on its lane.
     *
     * Video clips have been draggable since 2026-09-06 (`ClipBlock`'s body drag
     * → `onClipMoved` → [updateClipPosition]). The audio lane's tiles only ever
     * had `clickable`, so a soundtrack could be selected but never
     * repositioned — the one lane where the gesture silently did nothing.
     *
     * ## Why this takes a DELTA while the video path takes an absolute start
     *
     * The audio tile's gesture lives in a `pointerInput` keyed on the clip id,
     * so it is never restarted during a drag and everything it captures is
     * frozen — including the tile's own `startTimeMs`. An absolute position
     * computed inside that block would therefore be `frozenStart + oneFrame`,
     * i.e. the tile would jitter in place instead of following the finger.
     * Passing the per-frame delta and resolving it against the live state here
     * makes the gesture immune to that, and costs nothing: the ViewModel state
     * is already the authority for the position.
     *
     * A **paired** object is not an independent thing: it is the owning clip's
     * soundtrack, drawn on its own lane at the same `startTimeMs`. Moving the
     * tile alone would desync the two lanes, which is exactly the drift
     * `AudioLaneLifecycleWiringTest` exists to prevent. So a paired drag
     * delegates to [updateClipPosition] on the owning clip, which already
     * carries its audio along — the video tile moves with it, which is what the
     * gesture should mean when the two are one object.
     *
     * @param deltaMs movement since the previous frame (signed).
     * @param pushHistory false for every frame of a drag except the first, so
     *   one drag is one undo entry.
     */
    fun moveAudioClip(
        audioClipId: String,
        deltaMs: Long,
        pushHistory: Boolean = true
    ) {
        if (deltaMs == 0L) return
        val project = _uiState.value.project ?: return
        val audio = project.audioClips.firstOrNull { it.id == audioClipId } ?: return
        val ownerId = audio.originVideoClipId
        if (ownerId != null) {
            val clip = project.clips.firstOrNull { it.id == ownerId } ?: return
            val clipStart = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
            updateClipPosition(ownerId, (clipStart + deltaMs).coerceAtLeast(0L), pushHistory)
            return
        }
        if (pushHistory) historyDelegate.push()
        val start = (audio.startTimeMs + deltaMs).coerceAtLeast(0L)
        _uiState.update { state ->
            val p = state.project ?: return@update state
            val audioClips = p.audioClips.map { a ->
                if (a.id == audioClipId) a.copy(startTimeMs = start) else a
            }
            state.copy(project = p.copy(audioClips = audioClips))
        }
        // audioClips ride the project-meta blob, not the clips table.
        persistProjectMeta()
    }

    /**
     * 2026-10-20 — «حذف» was tapped on the soundtrack of a video clip, which
     * cannot be removed without detaching it. Stated instead of dropped: the
     * tile would look untouched and the button would read as broken (the same
     * rule as [notifyLastClipDeleteRefused] / [notifySplitNotPossible]).
     */
    fun notifyPairedAudioNotDeletable() {
        _uiState.update {
            it.copy(
                editorNotice = "این صدا به کلیپ ویدیو وابسته است؛ " +
                    "برای بی‌صدا کردن از «صدا» استفاده کنید"
            )
        }
    }

    /**
     * Trim a clip's in / out points. Both [newStartMs] and [newEndMs]
     * are timeline-space (i.e. they describe where the clip lives on the
     * timeline, not the source-media range). The clip's media-local
     * trimStartMs / trimEndMs are derived from the diff.
     */
    fun setClipTrim(clipId: String, newStartMs: Long, newEndMs: Long, pushHistory: Boolean = true) {
        // PHASE A (Persistence Integrity) — validate BEFORE pushing
        // history so an impossible trim is a no-op instead of an undo
        // entry that appears to do nothing.
        //
        // Two failure modes used to reach the model here:
        //  (1) the end edge dragged past the start edge produced
        //      `trimEndMs <= 0`, which `effectiveDurationMs` reads as
        //      "untrimmed" — the clip silently snapped back to full
        //      source length;
        //  (2) `trimEndMs` could exceed the source duration, letting a
        //      clip claim media that does not exist.
        val validation = validateTrim(clipId, newStartMs, newEndMs)
        if (validation != TrimValidation.Valid) return
        if (pushHistory) historyDelegate.push()
        var audioTouched = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            var trimmed: VideoClip? = null
            val clips = project.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                val oldStart = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                val oldEnd = oldStart + clip.effectiveDurationMs
                val leftDelta = (newStartMs - oldStart).coerceAtLeast(-clip.trimStartMs)
                val rightDelta = (newEndMs - oldEnd)
                val currentEnd = clip.trimEndMs.takeIf { it > 0 } ?: clip.durationMs
                val nextStart = (clip.trimStartMs + leftDelta).coerceAtLeast(0L)
                // Keep at least one ms of source between the edges and
                // never claim media past the end of the file.
                val nextEnd = (currentEnd + rightDelta)
                    .coerceAtLeast(nextStart + MIN_TRIM_MS)
                    .coerceAtMost(clip.durationMs)
                clip.copy(
                    timelineStartMs = newStartMs.coerceAtLeast(0L),
                    trimStartMs = nextStart,
                    trimEndMs = nextEnd
                ).also { trimmed = it }
            }
            // Paired-audio invariant (2026-10-30): the "Video Audio" tile
            // is a separate object and nothing moved it with the clip, so
            // trimming a clip slid the tile out from under it and left it
            // playing from the untrimmed head. One shared rule now —
            // `PairedAudio.mirrorGeometry` — so the next mutator added
            // cannot forget the other half.
            val next = trimmed ?: return@update state
            val audioClips = project.audioClips.map { audio ->
                if (audio.originVideoClipId != clipId) return@map audio
                audioTouched = true
                PairedAudio.mirrorGeometry(audio, next)
            }
            state.copy(project = project.copy(clips = clips, audioClips = audioClips))
        }
        persistClips()
        if (audioTouched) persistProjectMeta()
    }

    /**
     * PHASE A — pure trim validation, exposed so the UI can disable an
     * impossible trim gesture instead of silently dropping it.
     *
     * Rejects: unknown clip, negative or crossed edges, a span shorter
     * than one frame, and any request whose resulting SOURCE edge would
     * fall outside the file (start before 0 or end past `durationMs`).
     */
    fun validateTrim(clipId: String, newStartMs: Long, newEndMs: Long): TrimValidation {
        if (newStartMs < 0L) return TrimValidation.Negative
        if (newEndMs <= newStartMs) return TrimValidation.Inverted
        if (newEndMs - newStartMs < MIN_TRIM_MS) return TrimValidation.TooShort
        val clip = _uiState.value.project?.clips?.firstOrNull { it.id == clipId }
            ?: return TrimValidation.UnknownClip

        val oldStart = clip.timelineStartMs.coerceAtLeast(0L)
        val oldEnd = oldStart + clip.effectiveDurationMs
        val currentEnd = clip.trimEndMs.takeIf { it > 0 } ?: clip.durationMs
        val nextTrimStart = (clip.trimStartMs + (newStartMs - oldStart)).coerceAtLeast(0L)
        val nextTrimEnd = currentEnd + (newEndMs - oldEnd)
        if (nextTrimStart < 0L || nextTrimEnd > clip.durationMs) return TrimValidation.OutOfBounds
        if (nextTrimEnd - nextTrimStart < MIN_TRIM_MS) return TrimValidation.TooShort
        return TrimValidation.Valid
    }

    /** PHASE A — result of [validateTrim]. */
    enum class TrimValidation { Valid, UnknownClip, Negative, Inverted, TooShort, OutOfBounds }

    fun duplicateClip(clipId: String) {
        historyDelegate.push()
        var audioDuplicated = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.toMutableList()
            val clipIndex = clips.indexOfFirst { it.id == clipId }
            if (clipIndex == -1) return@update state
            
            val originalClip = clips[clipIndex]
            // Place the copy immediately AFTER the original on the
            // timeline. Copying in place (keeping the same
            // `timelineStartMs`) stacked the duplicate exactly on top
            // of its source, so the action looked like a no-op.
            val duplicateStartMs = originalClip.timelineStartMs
                .takeIf { it >= 0L } ?: 0L
            val duplicatedClip = originalClip.copy(
                id = java.util.UUID.randomUUID().toString(),
                importedAtEpochMillis = System.currentTimeMillis(),
                timelineStartMs = duplicateStartMs + originalClip.effectiveDurationMs
            )
            clips.add(clipIndex + 1, duplicatedClip)

            // 2026-10-19 — the soundtrack is duplicated too. Without this
            // the copy had no tile on the "Video Audio" lane until the
            // project was reloaded (`seedVideoAudioClipsIfNeeded` would
            // then invent one), so a duplicate looked like a video with no
            // audio. The clone is shifted by the same delta as the video
            // and retargeted at the NEW clip id, keeping the
            // one-audio-object-per-video invariant.
            val shift = duplicatedClip.timelineStartMs - duplicateStartMs
            val duplicatedAudio = project.audioClips
                .filter { it.originVideoClipId == clipId }
                .map { audio ->
                    audio.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        startTimeMs = (audio.startTimeMs + shift).coerceAtLeast(0L),
                        originVideoClipId = duplicatedClip.id
                    )
                }
            audioDuplicated = duplicatedAudio.isNotEmpty()

            state.copy(
                project = project.copy(
                    clips = clips,
                    audioClips = project.audioClips + duplicatedAudio
                )
            )
        }
        persistClips()
        if (audioDuplicated) persistProjectMeta()
    }

    // Per-clip properties
    fun updateClipVolume(clipId: String, volume: Float) {
        historyDelegate.push()
        var audioTouched = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val v = volume.coerceIn(0f, 2f)
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(volume = v) else clip
            }
            // 2026-10-30 — mirror into the "Video Audio" tile, so the
            // tile's own slider (which reads `AudioClip.volume`) shows the
            // level the preview and the export are actually using. The
            // reverse direction is `updateAudioClipVolume`.
            val audioClips = project.audioClips.map { audio ->
                if (audio.originVideoClipId != clipId) return@map audio
                audioTouched = true
                PairedAudio.withVideoVolume(audio, v)
            }
            state.copy(project = project.copy(clips = clips, audioClips = audioClips))
        }
        persistClips()
        if (audioTouched) persistProjectMeta()
    }

    /**
     * 2026-09-06 � set the volume of a selected AUDIO object (Video
     * Audio / Music / SFX lane). Operates on `project.audioClips` and
     * persists via saveProjectMeta so it survives reopen.
     */
    /**
     * Volume of a selected **audio object** (Video Audio / Music / SFX).
     *
     * 2026-10-30 — for the paired "Video Audio" object this used to write
     * only `project.audioClips`, i.e. the tile. The preview mixer
     * (`EditorScreen`'s `AudioMixerPlayer.update`) and the export
     * (`RenderSegment.volume`, baked by `VolumeScalingAudioProcessor`)
     * both read `VideoClip.volume`, so dragging that slider changed the
     * tile and **nothing else** — no preview change, no change in the
     * exported file. Silent in both directions, which is why it survived.
     *
     * The write-through is [PairedAudio.volumeForVideo]: paired objects
     * forward to their clip, standalone ones (music, sfx) do not.
     */
    fun updateAudioClipVolume(audioClipId: String, volume: Float) {
        historyDelegate.push()
        var videoTouched = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val audioClips = project.audioClips.map { clip ->
                if (clip.id == audioClipId) {
                    clip.copy(volume = volume.coerceIn(0f, 2f), muted = false)
                } else {
                    clip
                }
            }
            val target = audioClips.firstOrNull { it.id == audioClipId }
                ?: return@update state
            val writeThrough = PairedAudio.volumeForVideo(target)
            val clips = if (writeThrough == null) {
                project.clips
            } else {
                videoTouched = true
                project.clips.map { video ->
                    if (video.id == target.originVideoClipId) video.copy(volume = writeThrough)
                    else video
                }
            }
            state.copy(project = project.copy(clips = clips, audioClips = audioClips))
        }
        if (videoTouched) persistClips()
        persistProjectMeta()
    }

    /** Mute / unmute a selected AUDIO object. Paired objects forward to their clip. */
    fun updateAudioClipMute(audioClipId: String, muted: Boolean) {
        historyDelegate.push()
        var videoTouched = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val audioClips = project.audioClips.map { clip ->
                if (clip.id == audioClipId) clip.copy(muted = muted) else clip
            }
            val target = audioClips.firstOrNull { it.id == audioClipId }
                ?: return@update state
            // Unmuting restores the remembered level rather than unity —
            // the same reason `volumeForVideo` reads `muted` first.
            val writeThrough = PairedAudio.volumeForVideo(target)
            val clips = if (writeThrough == null) {
                project.clips
            } else {
                videoTouched = true
                project.clips.map { video ->
                    if (video.id == target.originVideoClipId) video.copy(volume = writeThrough)
                    else video
                }
            }
            state.copy(project = project.copy(clips = clips, audioClips = audioClips))
        }
        if (videoTouched) persistClips()
        persistProjectMeta()
    }

    // ------------------------------------------------------------------ //
    // Beat detection                                                     //
    // ------------------------------------------------------------------ //

    /**
     * In-flight beat analyses keyed by audio-clip id.
     *
     * Keyed by clip rather than by tool (unlike [aiToolJobs]) because the two
     * audio clips in a project are independent jobs: re-running Detect on the
     * music bed must not cancel an analysis the user started on a voice-over.
     */
    private val beatJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Analyse an audio clip's waveform and store the detected beat markers.
     *
     * Runs the decode + FFT sweep on [Dispatchers.IO] so the UI thread is
     * never blocked, and reports real progress through the same
     * `aiTools` state channel every other AI tool uses, so the panel gets the
     * established loading indicator for free.
     *
     * The heavy lifting is split so the DSP is testable: [AudioExtractor]
     * produces PCM, [BeatDetector] does the analysis. This method only wires
     * them together and persists the result.
     *
     * Cancellation is real: a new Detect on the same clip cancels the previous
     * job, and the extractor's `checkCancelled` hook aborts the codec loop
     * rather than letting it run to completion.
     */
    fun detectBeats(
        audioClipId: String,
        sensitivity: BeatDetector.BeatSensitivity = BeatDetector.BeatSensitivity.Default
    ) {
        val clip = _uiState.value.project?.audioClips?.firstOrNull { it.id == audioClipId }
        if (clip == null) {
            updateAiToolState(TOOL_ID_BEATS) {
                it.copy(
                    status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                    message = "کلیپ صوتی پیدا نشد",
                    cancellable = false
                )
            }
            return
        }

        beatJobs.remove(audioClipId)?.cancel()
        updateAiToolState(TOOL_ID_BEATS) {
            it.copy(
                status = com.aicreatorstudio.app.ai.AiToolStatus.Processing,
                progress = 0f,
                message = null,
                cancellable = true
            )
        }

        val job = viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val uri = android.net.Uri.parse(clip.sourceUri)
                    val (samples, rate, _) = audioExtractor.decodeForAnalysis(
                        videoUri = uri,
                        onProgress = { p -> publishAiToolProgress(TOOL_ID_BEATS, p * 0.85f) },
                        checkCancelled = { coroutineContext.ensureActive() }
                    )
                    publishAiToolProgress(TOOL_ID_BEATS, 0.9f)
                    BeatDetector.detect(samples, rate, sensitivity)
                }
            }
            result.fold(
                onSuccess = { beatData ->
                    publishAiToolProgress(TOOL_ID_BEATS, 1f)
                    applyBeatData(audioClipId, beatData)
                    updateAiToolState(TOOL_ID_BEATS) {
                        it.copy(
                            status = com.aicreatorstudio.app.ai.AiToolStatus.Completed,
                            progress = 1f,
                            message = beatResultSummary(beatData),
                            cancellable = false
                        )
                    }
                },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) {
                        updateAiToolState(TOOL_ID_BEATS) {
                            it.copy(
                                status = com.aicreatorstudio.app.ai.AiToolStatus.Cancelled,
                                cancellable = false
                            )
                        }
                    } else {
                        updateAiToolState(TOOL_ID_BEATS) {
                            it.copy(
                                status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                                message = e.message ?: "تشخیص ضرباهنگ ناموفق بود",
                                cancellable = false
                            )
                        }
                    }
                }
            )
        }
        beatJobs[audioClipId] = job
        job.invokeOnCompletion { beatJobs.remove(audioClipId, job) }
    }

    /** Remove detected beat markers from an audio clip. */
    fun clearBeats(audioClipId: String) {
        beatJobs.remove(audioClipId)?.cancel()
        historyDelegate.push()
        applyBeatData(audioClipId, null)
    }

    /** Cancel an in-flight analysis for [audioClipId], if any. */
    fun cancelBeatDetection(audioClipId: String) {
        beatJobs.remove(audioClipId)?.cancel()
        updateAiToolState(TOOL_ID_BEATS) {
            it.copy(status = com.aicreatorstudio.app.ai.AiToolStatus.Cancelled, cancellable = false)
        }
    }

    /**
     * Split [videoClipId] at every beat that falls inside it.
     *
     * This is the "auto-cut a montage to the music" action. It is deliberately
     * driven by [audioClipId]'s markers mapped onto the timeline rather than by
     * the video clip's own audio, so the user can cut a montage to an imported
     * music track that is not attached to the selected video at all.
     *
     * Reuses [splitClip] so undo granularity, id generation and the speed-ramp
     * slicing behaviour all stay consistent with a manual split. Two ordering
     * decisions matter and both are load-bearing:
     *
     * 1. Cuts are applied **last to first**. [splitClip] converts a global
     *    timeline position into clip-local trim coordinates using the clip's
     *    *current* `timelineStartMs`, and each split re-numbers the following
     *    clips. Working backwards means every position is still valid at the
     *    time it is used.
     * 2. History is pushed **once** before the batch rather than by each
     *    [splitClip] call, so a montage cut is a single undo step instead of
     *    one per beat.
     *
     * @return the number of split positions that were applied. Note that
     *   [splitClip] silently ignores a position that no longer falls strictly
     *   inside the clip (which cannot happen for cuts derived from the clip's
     *   own span, but is reported honestly rather than assumed).
     */
    fun autoSplitOnBeats(videoClipId: String, audioClipId: String): Int {
        val project = _uiState.value.project ?: return 0
        val video = project.clips.firstOrNull { it.id == videoClipId } ?: return 0
        val audio = project.audioClips.firstOrNull { it.id == audioClipId } ?: return 0

        val markers = BeatDetector.toTimelineMarkers(
            beatData = audio.beatData,
            clipStartMs = audio.startTimeMs,
            clipOffsetMs = audio.clipOffsetMs,
            clipDurationMs = audio.durationMs
        )
        if (markers.isEmpty()) return 0

        // Only cuts strictly inside the video clip are meaningful; the clip's
        // own boundaries already exist and splitting at them is a no-op.
        // Selection rules live in the pure, unit-tested [BeatCutPlanner].
        val start = video.timelineStartMs.coerceAtLeast(0L)
        val end = start + video.effectiveDurationMs
        val cuts = BeatCutPlanner.planCuts(
            markersTimelineMs = markers.map { it.timestampMs },
            clipStartMs = start,
            clipEndMs = end
        )
        if (cuts.isEmpty()) return 0

        historyDelegate.push()
        var applied = 0
        for (cut in cuts) {
            val before = _uiState.value.project?.clips?.size ?: 0
            splitClip(videoClipId, cut)
            // A successful split adds exactly one clip. Counting the delta is
            // how a Unit-returning action reports whether it took effect.
            val after = _uiState.value.project?.clips?.size ?: 0
            if (after > before) applied++
        }
        return applied
    }

    /**
     * Store (or clear) the beat markers for one audio clip and persist them.
     * Passing null clears the clip back to "never analysed".
     */
    private fun applyBeatData(
        audioClipId: String,
        beatData: BeatDetector.AudioBeatData?
    ) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            state.copy(
                project = project.copy(
                    audioClips = project.audioClips.map { clip ->
                        if (clip.id == audioClipId) clip.copy(beatData = beatData) else clip
                    }
                )
            )
        }
        persistProjectMeta()
    }

    /**
     * Beat markers for the currently selected audio clip, in **timeline**
     * time, or an empty list. Used by the timeline marker layer and by the
     * snapping path.
     */
    fun timelineBeatMarkers(audioClip: com.aicreatorstudio.app.model.AudioClip?): List<BeatDetector.BeatMarker> =
        BeatDetector.toTimelineMarkers(
            beatData = audioClip?.beatData,
            clipStartMs = audioClip?.startTimeMs ?: 0L,
            clipOffsetMs = audioClip?.clipOffsetMs ?: 0L,
            clipDurationMs = audioClip?.durationMs ?: 0L
        )

    /**
     * Every beat marker in the project, in timeline time, ascending.
     *
     * Deduplicated because two audio clips can legitimately carry markers at
     * the same instant (a music bed and a video's own audio), and the snap
     * search should not consider the same position twice.
     */
    fun allBeatMarkersOnTimeline(): List<Long> {
        val audioClips = _uiState.value.project?.audioClips ?: return emptyList()
        val out = LinkedHashSet<Long>()
        for (clip in audioClips) {
            for (marker in timelineBeatMarkers(clip)) out.add(marker.timestampMs)
        }
        return out.sorted()
    }

    /**
     * Snap [positionMs] to a nearby beat marker, falling back to the
     * clip-boundary snap when no beat is close enough.
     *
     * The two tolerances are deliberately different and the reason is
     * perceptual: [BEAT_SNAP_MS] is tight (18 ms) because the whole point of
     * snapping to a beat is precision — a cut that lands 40 ms off the kick is
     * exactly the defect the feature exists to remove. The clip-boundary
     * tolerance stays at [EditorLayoutSpec.TIMELINE_SNAP_MS] (80 ms) because
     * boundaries are further apart and a wider target is more forgiving.
     * Checking beats first means a beat that is nearly on a boundary wins,
     * which is what a user cutting to music wants.
     */
    fun snapToNearestWithBeats(
        positionMs: Long,
        nearbyMs: List<Long>,
        beatMarkers: List<Long>,
        snapMs: Long = com.aicreatorstudio.app.ui.screens.editor.EditorLayoutSpec.TIMELINE_SNAP_MS,
        beatSnapMs: Long = BEAT_SNAP_MS
    ): Long {
        val beat = BeatDetector.nearestMarker(
            beatData = beatMarkers.takeIf { it.isNotEmpty() }
                ?.let { BeatDetector.AudioBeatData(it.map { t -> BeatDetector.BeatMarker(t) }) },
            positionMs = positionMs,
            toleranceMs = beatSnapMs
        )
        if (beat != null) return beat.timestampMs
        return snapToNearest(positionMs, nearbyMs, snapMs)
    }

    /** Human-readable summary shown in the beats panel after an analysis. */
    private fun beatResultSummary(data: BeatDetector.AudioBeatData): String {
        if (data.markers.isEmpty()) return "ضرباهنگی پیدا نشد"
        val base = "تعداد ضرب: ${data.markers.size}"
        return if (data.bpm != null) "$base • ${data.bpm} BPM" else base
    }

    /**
     * Per-clip playback speed. The toolbar's "Speed" button uses this
     * to apply quick presets to the selected clip. Values are clamped to
     * [PlaybackSpeedRange] � the same bounds the preview player and the
     * Effects-panel slider use, so the UI can never offer a rate the
     * player would reject.
     */
    fun setClipSpeed(clipId: String, speed: Float) {
        historyDelegate.push()
        var audioTouched = false
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clipped = PlaybackSpeedRange.clamp(speed)
            var changed: VideoClip? = null
            val clips = project.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                clip.copy(speed = clipped).also { changed = it }
            }
            val next = changed ?: return@update state
            // Paired-audio invariant (2026-10-30): speed changes how long
            // the clip PLAYS, and the tile has to follow the same rule as
            // the clip it mirrors. `mirrorGeometry` is the single place
            // that decision lives.
            val audioClips = project.audioClips.map { audio ->
                if (audio.originVideoClipId != clipId) return@map audio
                audioTouched = true
                PairedAudio.mirrorGeometry(audio, next)
            }
            state.copy(project = project.copy(clips = clips, audioClips = audioClips))
        }
        persistClips()
        if (audioTouched) persistProjectMeta()
    }

    // ====================================================================
    // 2026-09-29 — Speed Ramping / Curves
    // ====================================================================

    /**
     * Replace the selected clip's speed ramp.
     *
     * **The constant [VideoClip.speed] is left alone.** That is the whole
     * backward-compatibility design: the ramp is an *additional* layer
     * that multiplies the constant rate, so clearing the ramp
     * ([clearClipSpeedCurve]) restores exactly the playback the user had
     * before they ever opened the curve editor. Treating the two as
     * alternatives to be overwritten would have made "undo my ramp" a
     * destructive operation.
     *
     * A curve that does not actually vary is normalised to `null` before
     * being stored: the editor opens with a flat `1f` ramp, and keeping
     * that as a non-null value would put the clip on the curve code path
     * for no reason and grow every saved project's blob.
     *
     * Persisted through [persistClips] AND the AI-tools blob, because
     * the curve lives in the latter — see
     * [com.aicreatorstudio.app.db.EditorStateJson.ClipAiTools.speedCurve].
     */
    fun setClipSpeedCurve(clipId: String, curve: com.aicreatorstudio.app.model.SpeedCurve?) {
        historyDelegate.push()
        val stored = curve?.takeIf { it.isActive }
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(speedCurve = stored) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
        persistSpeedCurves()
    }

    /** Drop the ramp, restoring the clip's constant speed. */
    fun clearClipSpeedCurve(clipId: String) {
        setClipSpeedCurve(clipId, null)
    }

    /**
     * Write the per-clip speed curves into the AI-tools blob.
     *
     * Its own call rather than a reuse of the OCR/matting save sites, so
     * editing a curve does not have to construct a whole
     * [com.aicreatorstudio.app.db.EditorStateJson.ClipAiTools] map for
     * every clip just to change one field.
     */
    private fun persistSpeedCurves() {
        val project = _uiState.value.project ?: return
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = project.clips.toClipAiTools()
        )
    }

    /**
     * Quick mute toggle for the currently-selected clip. Maps to a
     * per-clip volume of 0; restore by setting it back to 1f via
     * [updateClipVolume].
     */
    fun toggleMuteForSelectedClip() {
        val state = _uiState.value
        val clip = state.selectedClip ?: return
        val newVolume = if (clip.volume == 0f) 1f else 0f
        updateClipVolume(clip.id, newVolume)
    }

    fun updateClipAnimation(clipId: String, animation: com.aicreatorstudio.app.model.ClipAnimation) {
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(animation = animation) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    fun updateClipCrop(clipId: String, preset: com.aicreatorstudio.app.model.CropPreset, aspectRatio: Float) {
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(cropPreset = preset, cropAspectRatio = aspectRatio) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    /**
     * Per-clip audio fade-in (ms). Clamped to the clip's effective
     * duration so the fade can never exceed the clip itself. Backs
     * the Audio dock chip's Fade In slider.
     */
    fun setClipFadeInMs(clipId: String, ms: Long) {
        historyDelegate.push()
        val maxMs = (_uiState.value.project?.clips?.firstOrNull { it.id == clipId }
            ?.effectiveDurationMs ?: ms).coerceAtLeast(0L)
        val safe = ms.coerceIn(0L, maxMs)
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(fadeInMs = safe) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    /**
     * Per-clip audio fade-out (ms). See [setClipFadeInMs] for clamping.
     */
    fun setClipFadeOutMs(clipId: String, ms: Long) {
        historyDelegate.push()
        val maxMs = (_uiState.value.project?.clips?.firstOrNull { it.id == clipId }
            ?.effectiveDurationMs ?: ms).coerceAtLeast(0L)
        val safe = ms.coerceIn(0L, maxMs)
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(fadeOutMs = safe) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    /**
     * Per-clip opacity 0..1. Applied to the foreground `PlayerView`
     * in the preview via `Modifier.graphicsLayer { alpha = ... }` and
     * baked by the export pipeline. Backs the Opacity dock chip.
     */
    fun setClipOpacity(clipId: String, opacity: Float) {
        historyDelegate.push()
        val safe = opacity.coerceIn(0f, 1f)
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(opacity = safe) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    /**
     * Per-clip "famous effect" (ColorMatrix LUT). Distinct from
     * [selectVideoEffect] which mirrors to the active clip anyway �
     * this method is the authoritative per-clip setter, used by the
     * Filters panel when a clip is selected.
     */
    fun selectVideoEffectForClip(clipId: String, effect: com.aicreatorstudio.app.model.VideoEffect) {
        historyDelegate.push()
        updateClipVideoEffect(clipId, effect)
    }

    // Overlays
    fun addOverlay(clipId: String) {
        historyDelegate.push()
        _uiState.update { state ->
            val newOverlay = com.aicreatorstudio.app.model.VideoOverlay(
                clipId = clipId,
                startTimeMs = state.playbackPositionMs,
                endTimeMs = state.playbackPositionMs + 5000
            )
            state.copy(overlays = state.overlays + newOverlay)
        }
    }

    /**
     * Adds a picked image/video as an overlay block on the Overlay lane,
     * starting right at the playhead. Videos use their real duration;
     * images default to 3 seconds.
     */
    fun addMediaOverlay(uriString: String, isVideo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            // Copy into app-owned storage so the overlay media survives
            // app exit � same transient-grant fix as the import screen.
            val localUri = runCatching {
                mediaStorage.importToAppStorage(Uri.parse(uriString))
            }.getOrDefault(Uri.parse(uriString))
            var label = localUri.lastPathSegment ?: "Overlay"
            var durationMs = 3000L
            // 2026-10-13 — the PiP box's aspect ratio is probed here, once,
            // at import. Without it the box would be square and a landscape
            // overlay would be centre-cropped with no visible reason.
            var aspectRatio = com.aicreatorstudio.app.model.OverlayPipGeometry.DEFAULT_ASPECT_RATIO
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, localUri)
                    val probedW = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                    val probedH = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                    val probedRot = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    aspectRatio = com.aicreatorstudio.app.model.OverlayPipGeometry
                        .aspectRatioFor(probedW, probedH, probedRot)
                    if (isVideo) {
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMs = it }
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }?.let { label = it }
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) {
            }
            historyDelegate.push()
            _uiState.update { state ->
                val start = state.playbackPositionMs.coerceAtLeast(0L)
                val (x, y, scale) = com.aicreatorstudio.app.model.OverlayPipGeometry
                    .defaultPlacement()
                state.copy(
                    overlays = state.overlays + com.aicreatorstudio.app.model.VideoOverlay(
                        clipId = state.selectedClipId ?: "",
                        startTimeMs = start,
                        endTimeMs = start + durationMs,
                        // A fresh PiP lands inset from the top-left rather
                        // than at the model's 0.1/0.3 default, so it is
                        // fully on screen for any aspect ratio.
                        positionX = x,
                        positionY = y,
                        scale = scale,
                        uri = localUri.toString(),
                        label = label,
                        isVideo = isVideo,
                        aspectRatio = aspectRatio
                    )
                )
            }
            persistProjectMeta()
        }
    }

    fun removeOverlay(overlayId: String) {
        historyDelegate.push()
        _uiState.update { state ->
            state.copy(overlays = state.overlays.filter { it.id != overlayId })
        }
        persistProjectMeta()
    }

    // ============================================================
    // 2026-10-13 — Picture-in-Picture overlays (preview + export)
    // ============================================================
    //
    // Before this round the overlay list was write-only: `addOverlay` and
    // `addMediaOverlay` appended to `state.overlays`, the codec persisted
    // them, the timeline drew tiles — and nothing rendered them. The
    // actions below are the read side: selection, mask editing and the
    // live drag/pinch/rotate transform the preview's gesture layer drives.

    /**
     * Select (or clear) the PiP overlay the mask panel targets.
     *
     * **Deliberately does NOT clear [EditorUiState.selectedClipId].** The
     * mask chip lives in the *clip* toolbar, and `resolveEditorContext`
     * only shows that toolbar while a clip is selected — so clearing the
     * clip here would make the panel vanish the instant the user picked
     * the overlay it edits. Unlike the text/audio selection invariant in
     * `TimelineDelegate`, these two selections are meant to coexist.
     *
     * Selection is transient UI state: no undo entry, no DB write.
     */
    fun selectOverlay(overlayId: String?) {
        if (_uiState.value.selectedOverlayId == overlayId) return
        _uiState.update { it.copy(selectedOverlayId = overlayId) }
    }

    /**
     * 2026-10-13 — apply a **shape mask** to one PiP overlay.
     *
     * @param pushHistory `false` for the per-frame updates a slider drag
     *   produces, `true` (the default) for a discrete edit — a shape chip,
     *   the invert switch, the reset button. The undo stack is 20 deep and
     *   does not dedupe, so a single drag would otherwise flush it; the
     *   panel calls with `false` while dragging and with `true` on
     *   `onValueChangeFinished`, exactly the live/commit pairing
     *   [setProgressBarSettings] uses.
     *
     * Settings are sanitised before they are stored, so no caller can put a
     * NaN corner radius or a negative feather into the document that the
     * geometry would then have to defend against.
     *
     * Re-applying the value that is already set is a no-op and leaves no
     * undo entry — which is what makes the «حذف ماسک» button safe to tap
     * twice.
     */
    fun setOverlayMask(
        overlayId: String,
        settings: com.aicreatorstudio.app.model.OverlayMaskSettings,
        pushHistory: Boolean = true
    ) {
        val next = com.aicreatorstudio.app.model.OverlayMaskGeometry.sanitize(settings)
        val current = _uiState.value.overlays.firstOrNull { it.id == overlayId } ?: return
        if (current.maskSettings == next) return
        if (pushHistory) historyDelegate.push()
        _uiState.update { state ->
            state.copy(
                overlays = state.overlays.map { overlay ->
                    if (overlay.id == overlayId) overlay.copy(maskSettings = next) else overlay
                }
            )
        }
        if (pushHistory) persistProjectMeta()
    }

    /**
     * 2026-10-13 — apply a **chroma key** (green/blue screen removal) to one
     * PiP overlay.
     *
     * The twin of [setOverlayMask] and it follows the same contract for the
     * same reasons: the undo stack is 20 deep and does not dedupe, so
     * `pushHistory` is `false` for the per-frame updates a slider drag
     * produces and `true` for the discrete edit that ends it. Settings are
     * sanitised before they are stored, so no caller can put a NaN similarity
     * or a negative spill into the document.
     *
     * Re-applying the value that is already set is a no-op and leaves no undo
     * entry, which is what makes «بازنشانی» safe to tap twice.
     *
     * Mask and chroma key are deliberately independent writes: the panel is a
     * sibling of the mask panel, both are scoped to the selected overlay, and
     * they compose at render time (chroma key runs *before* the mask clip).
     * There is no combined action, so there is no ordering a caller can get
     * wrong.
     */
    fun setOverlayChromaKey(
        overlayId: String,
        settings: com.aicreatorstudio.app.model.ChromaKeySettings,
        pushHistory: Boolean = true
    ) {
        val next = com.aicreatorstudio.app.util.ChromaKeyEngine.sanitize(settings)
        val current = _uiState.value.overlays.firstOrNull { it.id == overlayId } ?: return
        if (current.chromaKeySettings == next) return
        if (pushHistory) historyDelegate.push()
        _uiState.update { state ->
            state.copy(
                overlays = state.overlays.map { overlay ->
                    if (overlay.id == overlayId) {
                        overlay.copy(chromaKeySettings = next)
                    } else {
                        overlay
                    }
                }
            )
        }
        if (pushHistory) persistProjectMeta()
    }

    /**
     * 2026-10-13 — the preview's drag / pinch / rotate gesture.
     *
     * One history snapshot per gesture, taken on the **first moved frame**
     * ([beginOverlayTransform]) and persisted once on release
     * ([endOverlayTransform]) — never per frame. This mirrors the text
     * gesture's `beginTextTransform` / `endTextTransform` pair exactly, and
     * for the same reason: the position changes ~60×/s while the finger is
     * down.
     *
     * Values are clamped through [com.aicreatorstudio.app.model.OverlayPipGeometry]
     * so a drag can never push a PiP's normalised position outside `0..1`
     * or its scale below the point of being grabbable.
     */
    fun updateOverlayTransform(
        overlayId: String,
        positionX: Float,
        positionY: Float,
        scale: Float,
        rotationDegrees: Float
    ) {
        val x = com.aicreatorstudio.app.model.OverlayPipGeometry.clampNorm(positionX)
        val y = com.aicreatorstudio.app.model.OverlayPipGeometry.clampNorm(positionY)
        val s = com.aicreatorstudio.app.model.OverlayPipGeometry.clampScale(scale)
        val r = if (rotationDegrees.isFinite()) rotationDegrees else 0f
        _uiState.update { state ->
            state.copy(
                overlays = state.overlays.map { overlay ->
                    if (overlay.id != overlayId) {
                        overlay
                    } else {
                        overlay.copy(
                            positionX = x,
                            positionY = y,
                            scale = s,
                            rotationDegrees = r
                        )
                    }
                }
            )
        }
    }

    /** One undo snapshot at the start of a PiP drag/pinch/rotate gesture. */
    fun beginOverlayTransform() {
        historyDelegate.push()
    }

    /** Persist the final transform once, when the pointer is released. */
    fun endOverlayTransform() {
        persistProjectMeta()
    }

    /**
     * 2026-10-13 — re-probe an overlay's media so its PiP box has the right
     * aspect ratio.
     *
     * `addMediaOverlay` already reads duration for the timeline tile; this
     * adds width/height/rotation for the box. Kept separate so the import
     * path does not have to grow a second metadata concern, and idempotent
     * so re-running it on a project that already has ratios is harmless.
     */
    fun probeOverlayAspect(overlayId: String) {
        val overlay = _uiState.value.overlays.firstOrNull { it.id == overlayId } ?: return
        if (overlay.uri.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val aspect = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(appContext, Uri.parse(overlay.uri))
                    val w = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                    val h = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
                    val rot = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    com.aicreatorstudio.app.model.OverlayPipGeometry
                        .aspectRatioFor(w, h, rot)
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull() ?: return@launch
            _uiState.update { state ->
                state.copy(
                    overlays = state.overlays.map { o ->
                        if (o.id == overlayId && o.aspectRatio != aspect) {
                            o.copy(aspectRatio = aspect)
                        } else {
                            o
                        }
                    }
                )
            }
            persistProjectMeta()
        }
    }

    // ============================================================
    // AI Subtitle Generation � clean 2026-09-08 rebuild
    // ============================================================
    //
    // One entry point (requestGenerateSubtitles), one pipeline
    // (runGeneration), one authoritative state (SubtitleGenerationState
    // in the uiState). Design invariants:
    //
    //  1. The generation job is the ONLY source of truth for
    //     "is a run active". A stale non-terminal state with a dead
    //     job is healed on the next request, never blocks the button.
    //  2. Every progress emission is a REAL fraction (installer,
    //     MediaCodec PTS, whisper window index). No synthetic ramps.
    //  3. `finally` always clears isLoading and heals a non-terminal
    //     state ? the button can never get stuck.
    //  4. Empty transcription (raw or post-filtered) is a clean
    //     Error with no commit, no persist, no crash.
    //  5. Commit replaces only AI-owned overlays (subtitleSource ==
    //     "ai"); user text and SRT overlays survive.
    //  6. Overlay placement: timeline = (sourceT - windowStart)/speed
    //     + anchor, where the anchor is the generating object's
    //     timeline start. `subtitleAnchorMs` records the anchor so
    //     SRT export can invert it exactly.
    // ============================================================

    /** Selected language for the next generation (persisted). */
    fun setSubtitleLanguage(language: com.aicreatorstudio.app.ai.SubtitleLanguage) {
        _uiState.update { it.copy(selectedSubtitleLanguage = language) }
        subtitleGenerator.setSelectedLanguage(language)
        settingsRepository.updateSubtitleLanguage(language.name)
    }

    /**
     * 2026-09-09 � switch the subtitle decoder accuracy mode
     * (Fast / Balanced / Accurate). Persists + updates the engine.
     */
    fun setSubtitleAccuracyMode(mode: WhisperEngine.AccuracyMode) {
        _uiState.update { it.copy(subtitleAccuracyMode = mode) }
        whisperEngineProvider.get().accuracyMode = mode
        settingsRepository.updateSubtitleAccuracyMode(mode.key)
    }

    /**
     * 2026-09-15 � set the Premiere-style "words per caption"
     * (0 = off, else 1..12). Persists via SettingsRepository; the
     * post-processor segmenter reads it on every future AI-subtitle
     * generation, so existing captions are untouched until the next
     * Generate run.
     */
    fun setSubtitleWordsPerCaption(count: Int) {
        val clamped = count.coerceIn(0, 12)
        settingsRepository.updateSubtitleWordsPerCaption(clamped)
        _uiState.update { it.copy(subtitleWordsPerCaption = clamped) }
    }

    /**
     * 2026-09-09 � replace the custom dictionary (one term per line)
     * and push it into the engine's initial-prompt builder.
     */
    fun setCustomDictionary(text: String) {
        settingsRepository.updateCustomDictionary(text)
        whisperEngineProvider.get().customDictionary = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        _uiState.update { it.copy(customDictionaryText = text) }
    }

    /** The persisted custom dictionary raw text (for the editor UI). */
    fun savedCustomDictionaryText(): String =
        settingsRepository.savedCustomDictionary().orEmpty()

    // ====================================================================
    // 2026-09-11 �??????? ?????� � template studio
    // ====================================================================

    /**
     * Apply a [SubtitleTemplate] to **ALL generated subtitles**
     * (every overlay with a subtitleSource tag � ai / srt / manual �
     * never decorative titles/watermarks), then remember it as the
     * default so future generations are styled the same way.
     */
    fun applySubtitleTemplateToAll(template: com.aicreatorstudio.app.model.SubtitleTemplate) {
        historyDelegate.push()
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.map { o ->
                    o.withTemplate(template)
                }
            )
        }
        persistStudioTemplate(template)
        persistProjectMeta()
    }

    /**
     * Remember [template] as the studio default (applies to all
     * FUTURE generated subtitles on commit) without touching the
     * existing overlays.
     *
     * 2026-09-16 BUGFIX � this used to persist ONLY the template
     * id, which silently dropped two things:
     *  1. Pack tweaks (font / position / motion / colors keep the
     *     built-in id, so future generations lost them), and
     *  2. "Save preset" custom templates (`custom_*` ids were never
     *     written to the repository, so they resolved to null and
     *     the preset vanished on reopen).
     * Now the full template JSON is snapshotted AND user templates
     * are written to the repository.
     */
    fun saveStudioTemplate(template: com.aicreatorstudio.app.model.SubtitleTemplate) {
        persistStudioTemplate(template)
    }

    /** Shared persistence for both studio paths (pick + apply-all). */
    private fun persistStudioTemplate(template: com.aicreatorstudio.app.model.SubtitleTemplate) {
        if (!template.builtIn) {
            subtitleTemplateRepository.saveUserTemplate(template)
        }
        settingsRepository.saveStudioTemplateId(template.id)
        settingsRepository.saveStudioTemplateJson(
            runCatching { template.toJson() }.getOrNull()
        )
    }

    /**
     * 2026-09-19 PHASE 4 — one-shot JSON catalog load into UI state.
     * Resolution order per template: full snapshot JSON (the user's
     * exact tweaks) → mapped JSON catalog (by id, incl. legacy
     * migration) → user repository. Old hand-built ids resolve
     * through the migration map so saved projects keep working.
     */
    private suspend fun restoreJsonCatalog() {
        val mapped = com.aicreatorstudio.app.model.template.JsonTemplateMapper.mapCatalogState(
            jsonTemplateCatalog.load()
        )
        _uiState.update {
            it.copy(
                jsonCatalogTemplates = mapped.templates,
                jsonCatalogStatus = mapped.status
            )
        }
    }
    /**
     * The persisted studio template, or null when unset.
     *
     * Resolution order (2026-09-19 PHASE 4): full JSON snapshot
     * (the user's exact tweaks) → hand-built catalog by id →
     * hand-built legacy migration → mapped JSON catalog (by id,
     * incl. legacy migration) → user repository. Old ids resolve
     * through the migration maps so saved projects keep working.
     */
    fun studioTemplate(): com.aicreatorstudio.app.model.SubtitleTemplate? {
        settingsRepository.studioTemplateJson()?.let { json ->
            com.aicreatorstudio.app.model.SubtitleTemplate.fromJson(json)?.let { return it }
        }
        return settingsRepository.studioTemplateId()?.let { id ->
            // 2026-09-19 — the id may reference one of the 20
            // shipped templates, a saved user preset, or a REMOVED
            // pre-20 template (migrated to its closest equivalent
            // so old projects keep their look).
            com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.byId(id)
                ?: com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.migrateLegacyId(id)
                ?: com.aicreatorstudio.app.model.template.JsonTemplateMapper.pickTemplate(
                    _uiState.value.jsonCatalogTemplates, id
                )
                ?: subtitleTemplateRepository.byId(id)
        }
    }

    /**
     * Add a manual template-styled subtitle. For user-authored text
     * (no whisper timings) the word timings are spread EVENLY across
     * the window so word-by-word/karaoke reveal works deterministically
     * � this is authored timing, not inference.
     */
    fun addTemplateSubtitle(
        text: String,
        template: com.aicreatorstudio.app.model.SubtitleTemplate,
        startMs: Long,
        endMs: Long
    ) {
        if (text.isBlank() || endMs <= startMs) return
        historyDelegate.push()
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val span = (endMs - startMs).coerceAtLeast(tokens.size.toLong())
        val per = span / tokens.size
        val words = tokens.mapIndexed { i, t ->
            com.aicreatorstudio.app.ai.TranscribedWord(
                text = t,
                startTimeMs = startMs + i * per,
                endTimeMs = startMs + (i + 1) * per
            )
        }
        val overlay = com.aicreatorstudio.app.model.TextOverlay(
            text = text.trim(),
            startTimeMs = startMs,
            endTimeMs = endMs,
            // 2026-09-15 POSITION FIX � the template's absolute
            // positionX/Y is converted through the SAME
            // anchor-delta helper `withTemplate` uses, so the
            // manual-add path and the apply-to-all path land the
            // caption in the identical spot (previously this
            // passed the raw 0..1 values as drag-deltas, double-
            // counting the anchor and pushing bottom captions off
            // the canvas).
            position = com.aicreatorstudio.app.model
                .templatePositionAsAnchorDelta(template.style),
            fontSizeSp = template.style.fontSizeSp,
            fontFamily = template.style.fontFamily,
            words = words,
            karaokeEnabled = template.wordRevealMode !=
                com.aicreatorstudio.app.model.WordRevealMode.ALL_AT_ONCE,
            subtitleSource = "manual",
            subtitleStyle = template.style,
            subtitleAnchorMs = 0L,
            textAnimation = template.animationIn,
            templateId = template.id,
            activeWordColorArgb = template.activeWordColorArgb,
            activeWordScale = template.activeWordScale,
            textAnimationOut = template.animationOut,
            wordRevealMode = template.wordRevealMode,
            // 2026-09-14 � viral motion + background layer.
            motionKind = template.motionKind,
            backgroundKind = template.backgroundKind,
            activeWordGlowRadiusDp = template.activeWordGlowRadiusDp,
            activeWordUnderline = template.activeWordUnderline,
            accentColorArgb = template.accentColorArgb,
            animationDurationMs = template.animationDurationMs
        )
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays + overlay,
                selectedTextId = overlay.id
            )
        }
        persistProjectMeta()
    }

    /**
     * 2026-09-11 � persist the typo-corrector decision log as JSON
     * under `filesDir/subtitle-corrections/` so every correction
     * (applied / flagged / kept) is auditable after the fact. Best
     * effort: a write failure never affects subtitle generation.
     */
    private fun persistCorrectionLog(
        clipId: String,
        before: com.aicreatorstudio.app.ai.Transcription,
        after: com.aicreatorstudio.app.ai.Transcription,
        records: List<com.aicreatorstudio.app.ai.subtitle.corrector.CorrectionRecord>
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(appContext.filesDir, "subtitle-corrections")
                    .apply { mkdirs() }
                val file = java.io.File(
                    dir,
                    "corrections_${clipId.take(8)}_${System.currentTimeMillis()}.json"
                )
                val json = com.aicreatorstudio.app.ai.subtitle.corrector
                    .CorrectionLogJson.encode(
                        originalText = before.segments.joinToString("\n") { it.text },
                        normalizedText = before.segments.joinToString("\n") { it.text },
                        finalText = after.segments.joinToString("\n") { it.text },
                        records = records
                    )
                file.writeText(json)
                android.util.Log.i(
                    TAG_SUBS,
                    "correction log: ${records.size} decisions ? ${file.name}"
                )
            }
        }
    }

    /** True while a generation coroutine is alive. */
    private val isGenerationActive: Boolean
        get() = subtitleGenerationJob?.isActive == true

    /**
     * The single UI entry point for the Generate button. Safe to call
     * from any state: a live job makes this a no-op, a stale
     * non-terminal state is healed first.
     */
    fun requestGenerateSubtitles(
        font: com.aicreatorstudio.app.model.PersianFont = com.aicreatorstudio.app.model.PersianFont.DEFAULT
    ) {
        if (isGenerationActive) {
            android.util.Log.w(TAG_SUBS, "requestGenerateSubtitles: run already active")
            return
        }
        if (!_uiState.value.subtitleGenerationState.isTerminal) {
            android.util.Log.w(
                TAG_SUBS,
                "healing stale state ${_uiState.value.subtitleGenerationState::class.simpleName}"
            )
            _uiState.update {
                it.copy(subtitleGenerationState = SubtitleGenerationState.Idle)
            }
        }
        runGeneration(font)
    }

    /**
     * 2026-10-05 — AUTO-CAPTION ONE-TAP.
     *
     * One call = pick a template + generate + style. Persists
     * [templateId] through the SAME studio-template path the Subtitle
     * Studio uses ([saveStudioTemplate]), then starts the standard gated
     * generation. `runGeneration` already styles every new overlay with
     * the persisted studio template, so a single tap produces styled
     * captions — no second visit to the Studio needed.
     *
     * Resolution order for [templateId]: built-in catalog by id → the
     * already-saved studio template → the shipped default
     * (`fa_01_clean`). A null [templateId] means "use whatever is saved"
     * (first-run default: CLEAN). If nothing resolves (corrupt prefs),
     * the run is refused rather than generating unstyled captions the
     * user did not ask for.
     */
    fun runAutoCaption(templateId: String?) {
        if (isGenerationActive) {
            android.util.Log.w(TAG_SUBS, "runAutoCaption: run already active")
            return
        }
        val template = templateId?.let {
            com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.byId(it)
        } ?: studioTemplate()
            ?: com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.byId("fa_01_clean")
        if (template == null) {
            android.util.Log.w(TAG_SUBS, "runAutoCaption: no template resolved — refused")
            return
        }
        saveStudioTemplate(template)
        requestGenerateSubtitles()
    }

    /**
     * Resolve WHAT we transcribe and WHERE the subtitles land.
     * Returns null (with an Error state committed) when the project
     * has nothing to transcribe.
     *
     * 2026-10-10 — the resolution rules moved to the pure
     * `com.aicreatorstudio.app.model.resolveSubtitleSourceWindow` so they
     * are JVM-unit-testable (same pattern as `resolveEditorContext` /
     * `tracksForTool`). The no-source error is now a real Persian string
     * (`SUBTITLE_NO_SOURCE_MESSAGE_FA`); the previous literal had been
     * corrupted into `"??? ???? …"` mojibake at write time.
     */
    private fun resolveGenerationTarget(): GenerationTarget? {
        val window = com.aicreatorstudio.app.model.resolveSubtitleSourceWindow(_uiState.value)
        if (window == null) {
            _uiState.update {
                it.copy(
                    subtitleGenerationState = SubtitleGenerationState.Error(
                        com.aicreatorstudio.app.model.SUBTITLE_NO_SOURCE_MESSAGE_FA
                    ),
                    errorMessage = com.aicreatorstudio.app.model.SUBTITLE_NO_SOURCE_MESSAGE_FA
                )
            }
            return null
        }
        return GenerationTarget(
            decodeUri = Uri.parse(window.sourceUriString),
            anchorMs = window.anchorMs,
            windowStartMs = window.windowStartMs,
            windowEndMs = window.windowEndMs,
            speed = window.speed
        )
    }

    /** Immutable description of one generation run's target. */
    private data class GenerationTarget(
        val decodeUri: Uri,
        val anchorMs: Long,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val speed: Float
    )

    private fun runGeneration(
        font: com.aicreatorstudio.app.model.PersianFont
    ) {
        val state = _uiState.value
        val target = resolveGenerationTarget() ?: return
        // 2026-09-11 � the clip the transcription belongs to (same
        // resolution order as resolveGenerationTarget) for the
        // correction-log filename.
        val generationClipId = (state.selectedClip
            ?: state.project?.clips?.firstOrNull { !it.isAudioOnly }
            ?: state.project?.clips?.firstOrNull())?.id ?: "unknown"
        whisperEngineProvider.get().accuracyMode = state.subtitleAccuracyMode
        val selectedModel = state.selectedWhisperModel

        historyDelegate.push()
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                subtitleGenerationState = SubtitleGenerationState.Preparing()
            )
        }

        subtitleGenerationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 2026-09-08 PIPELINE-GLOBAL PROGRESS � the card shows
                // ONE bar for the whole job, so every phase is mapped
                // onto a fixed slice of the same 0..100% scale. The
                // slices are weighted by real measured cost:
                //
                //   ?????????? (model install/load)   0%  ? 15%
                //   ??????? ??? (remux + decode)     15% ? 35%
                //   ??????? (whisper windows)        35% ? 100%
                //   ???? ??????? (pure data, no bar)
                //
                // Each phase callback receives a phase-LOCAL 0..1
                // fraction from the engine and is re-mapped here. This
                // makes the bar climb monotonically across phase
                // changes and every percentage meaningful against the
                // total job � the old per-phase scales made the bar
                // jump backwards (??????? ???% ? ??????? ??%) and read
                // as broken.
                val installToGlobal: (Float) -> Float = { p ->
                    0.15f * p.coerceIn(0f, 1f)
                }
                val extractionToGlobal: (Float) -> Float = { p ->
                    0.15f + 0.20f * p.coerceIn(0f, 1f)
                }
                val transcribeToGlobal: (Float) -> Float = { p ->
                    0.35f + 0.65f * p.coerceIn(0f, 1f)
                }

                // 2026-09-12 CLOUD PIPELINE-GLOBAL PROGRESS — the
                // cloud path reports its own weighted
                // fraction (extraction 0..0.2, upload 0.2..0.6,
                // whisper-wait at 0.6 boundary — see
                // UserGroqEngine), so the ViewModel can derive
                // the REAL phase from the fraction and drive the
                // distinct UI states:
                //
                //   ??????????        0%  ? 15%
                //   ??????? ???      15% ? 35%   (engine 0.00..0.20)
                //   ????? ???        35% ? 75%   (engine 0.20..0.60)
                //   ?????? Whisper   75% (indeterminate until reply)
                //   ???? ???????     (pure data, no bar)
                //
                // The mapping keeps the ONE continuous bar: engine
                // fraction f is re-mapped onto 15%..75% of the job,
                // and the exact 0.6 boundary flips the label to
                // "?? ??? ?????? ???? Whisper AI" (indeterminate �
                // the server is working, no fake percentage).
                val isCloudRun = selectedModel == WhisperModelSpec.CLOUD_API
                val cloudExtractionToGlobal: (Float) -> Float = { p ->
                    0.15f + 0.20f * p.coerceIn(0f, 1f)
                }
                val cloudUploadToGlobal: (Float) -> Float = { p ->
                    // engine upload fraction (0..0.4 band) ? global 35..75%
                    0.35f + 0.40f * (p.coerceIn(0f, 0.6f) / 0.6f)
                }
                val cloudPhaseFromEngine: (Float) -> SubtitleGenerationState = { p ->
                    when {
                        p < 0.2f -> SubtitleGenerationState.DecodingAudio(
                            cloudExtractionToGlobal(p / 0.2f)
                        )
                        p < 0.6f -> SubtitleGenerationState.UploadingAudio(
                            cloudUploadToGlobal(p)
                        )
                        else -> SubtitleGenerationState.ProcessingByWhisper
                    }
                }

                // ---- Phase 1: model ready (install + native load) ----
                val initResult = subtitleGenerator.initializeModelFor(selectedModel) { p ->
                    updateGenerationState { SubtitleGenerationState.Preparing(installToGlobal(p)) }
                }
                initResult.onFailure { error ->
                    android.util.Log.w(TAG_SUBS, "model init failed", error)
                    failGeneration(
                        SubtitleMessages.modelInitFailed(
                            error.message ?: error::class.java.simpleName
                        ),
                        markModelFailed = selectedModel
                    )
                    return@launch
                }

                // ---- Phase 2: decode + transcribe ----
                updateGenerationState {
                    SubtitleGenerationState.DecodingAudio(extractionToGlobal(0f))
                }
                _uiState.update {
                    it.copy(
                        aiModelStatuses =
                            it.aiModelStatuses + (selectedModel to AiModelStatus.INSTALLED)
                    )
                }

                val result = if (isCloudRun) {
                    subtitleGenerator.generateTranscription(
                        videoUri = target.decodeUri,
                        onProgress = { p ->
                            updateGenerationState { cloudPhaseFromEngine(p) }
                        },
                        onDecodeProgress = { p ->
                            updateGenerationState {
                                SubtitleGenerationState.DecodingAudio(
                                    cloudExtractionToGlobal(p)
                                )
                            }
                        }
                    )
                } else {
                    subtitleGenerator.generateTranscription(
                        videoUri = target.decodeUri,
                        onProgress = { p ->
                            updateGenerationState {
                                SubtitleGenerationState.Transcribing(transcribeToGlobal(p))
                            }
                        },
                        onDecodeProgress = { p ->
                            updateGenerationState {
                                SubtitleGenerationState.DecodingAudio(extractionToGlobal(p))
                            }
                        }
                    )
                }
                val rawTranscription = result.getOrElse { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    android.util.Log.w(TAG_SUBS, "transcription failed", error)
                    failGeneration(
                        SubtitleMessages.transcriptionFailed(
                            error.message ?: error::class.java.simpleName
                        )
                    )
                    return@launch
                }

                // ---- Phase 3: window / speed adaptation + post-process ----
                updateGenerationState { SubtitleGenerationState.CreatingSubtitles }
                val adapted = adaptToWindow(rawTranscription, target)
                // 2026-09-11 � the user's custom dictionary protects
                // brands/names/jargon from the typo corrector.
                val customWords = settingsRepository.savedCustomDictionary()
                    .orEmpty()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                val corrections =
                    mutableListOf<com.aicreatorstudio.app.ai.subtitle.corrector.CorrectionRecord>()
                val postProcessed = SubtitlePostProcessor.postProcess(
                    transcription = adapted,
                    settings = settingsRepository.currentSubtitlePostProcessSettings(),
                    customWords = customWords,
                    onCorrections = { corrections.addAll(it) }
                )
                if (corrections.isNotEmpty()) {
                    persistCorrectionLog(generationClipId, adapted, postProcessed, corrections)
                }
                if (postProcessed.segments.isEmpty()) {
                    failGeneration(SubtitleMessages.NO_OVERLAYS_PRODUCED_FA)
                    return@launch
                }

                // ---- Phase 4: map to overlays (offset + anchor) ----
                val newOverlays =
                    com.aicreatorstudio.app.ai.WhisperTranscriptionToTextOverlays
                        .toTextOverlays(
                            transcription = postProcessed,
                            clipOffsetMs = target.anchorMs,
                            font = font
                        )
                if (newOverlays.isEmpty()) {
                    failGeneration(SubtitleMessages.NO_OVERLAYS_PRODUCED_FA)
                    return@launch
                }

                // ---- Phase 4.5 (2026-09-11): studio template ----
                // When the user picked a template in �??????? ?????�,
                // EVERY generated subtitle is styled with it here, so
                // the choice survives future regenerations too.
                val studioTemplate = studioTemplate()
                val styledOverlays = if (studioTemplate != null) {
                    newOverlays.map { it.withTemplate(studioTemplate) }
                } else newOverlays

                // ---- Phase 5: commit + persist ----
                _uiState.update { current ->
                    current.copy(
                        textOverlays = current.textOverlays
                            .filterNot { it.subtitleSource == "ai" } + styledOverlays,
                        isLoading = false,
                        subtitleGenerationState = SubtitleGenerationState.Completed(
                            overlayCount = newOverlays.size
                        ),
                        activeTranscriptionEngine =
                            "${subtitleGenerator.activeEngineDisplayName} " +
                                "(${selectedModel.displayName})"
                    )
                }
                aiSubtitleOverlayIds.clear()
                aiSubtitleOverlayIds.addAll(styledOverlays.map { it.id })
                persistProjectMeta()
                _uiState.update {
                    it.copy(pendingSeekMs = styledOverlays.minOf { o -> o.startTimeMs })
                }
            } finally {
                // Single guaranteed wind-down: clear isLoading, heal any
                // non-terminal state (cancel / unexpected crash), and
                // only reset Preparing/Decoding/etc to Idle � never a
                // Completed/Error the run legitimately reached.
                _uiState.update {
                    val st = it.subtitleGenerationState
                    it.copy(
                        isLoading = false,
                        subtitleGenerationState =
                            if (st.isTerminal) st else SubtitleGenerationState.Idle
                    )
                }
            }
        }
    }

    /**
     * State updater that refuses to move a `Cancelling` state back
     * into a running one (a late progress tick after the user tapped
     * Cancel must not resurrect the run in the UI).
     */
    private inline fun updateGenerationState(
        crossinline produce: () -> SubtitleGenerationState
    ) {
        _uiState.update {
            if (it.subtitleGenerationState is SubtitleGenerationState.Cancelling) it
            else it.copy(subtitleGenerationState = produce())
        }
    }

    /** Commit a terminal Error state with the given Persian message. */
    private fun failGeneration(
        message: String,
        markModelFailed: WhisperModelSpec? = null
    ) {
        // 2026-10-13 — every AI subtitle failure funnels through here, so
        // this is the single place that makes the diagnostics panel see
        // them. Before this, a failed Whisper run left the user with a
        // banner and the log with nothing.
        omeletLog.error(
            component = "ai_subtitle",
            event = if (markModelFailed != null) "generation_model_failed"
            else "generation_failed",
            message = message,
            fields = buildMap {
                markModelFailed?.let { put("model", it.name) }
            }
        )
        _uiState.update {
            it.copy(
                isLoading = false,
                subtitleGenerationState = SubtitleGenerationState.Error(message),
                errorMessage = message,
                aiModelStatuses = if (markModelFailed != null) {
                    it.aiModelStatuses + (markModelFailed to AiModelStatus.FAILED)
                } else it.aiModelStatuses
            )
        }
    }

    /**
     * Map a raw (source-relative) transcription into the target's
     * playable window: drop words outside [windowStartMs, windowEndMs)
     * and convert the rest to clip-local ms
     * ((t - windowStart) / speed). Segments are re-grouped from the
     * surviving words so segment timing stays pinned to its words.
     */
    private fun adaptToWindow(
        transcription: com.aicreatorstudio.app.ai.Transcription,
        target: GenerationTarget
    ): com.aicreatorstudio.app.ai.Transcription {
        // 2026-09-13 CLOUD FIX � the old implementation filtered
        // ONLY `transcription.words`. The Cloud path can have
        // NO per-word timings (segment-level-only replies), so the
        // filter dropped EVERYTHING and the run ended with
        // "??? ?????? ?? ????? ??????? ???" even though the API
        // had transcribed real speech.
        //
        // Words path (offline whisper.cpp + cloud verbose replies):
        val inWindow = transcription.words.filter {
            it.startTimeMs >= target.windowStartMs &&
                it.startTimeMs < target.windowEndMs
        }
        if (inWindow.isNotEmpty()) {
            val mapped = inWindow.map { w ->
                val s = ((w.startTimeMs - target.windowStartMs) / target.speed).toLong()
                val e = ((w.endTimeMs - target.windowStartMs) / target.speed).toLong()
                w.copy(startTimeMs = s, endTimeMs = maxOf(e, s + 1L))
            }.sortedBy { it.startTimeMs }
            return com.aicreatorstudio.app.ai.Transcription(
                segments = com.aicreatorstudio.app.ai.groupWordsIntoSubtitlesForWhisper(mapped),
                words = mapped
            )
        }

        // 2026-09-13 SEGMENTS path (cloud replies with segments /
        // VTT cues but no words): filter + remap the SEGMENTS by
        // their own [start, end) window. A segment partially
        // overlapping the window is kept and clamped.
        val inWindowSegments = transcription.segments.mapNotNull { seg ->
            val overlapStart = maxOf(seg.startTimeMs, target.windowStartMs)
            val overlapEnd = minOf(
                seg.endTimeMs,
                // An open-ended final segment (end == 0 / == start)
                // still counts when its start is inside the window.
                if (seg.endTimeMs <= seg.startTimeMs) Long.MAX_VALUE else seg.windowEndBound(target)
            )
            if (overlapStart >= overlapEnd) null
            else if (seg.startTimeMs >= target.windowStartMs &&
                seg.startTimeMs < target.windowEndMs
            ) {
                // Start inside the window � keep with its own end
                // (clamped to the window).
                val s = ((seg.startTimeMs - target.windowStartMs) / target.speed).toLong()
                val e = ((minOf(
                    seg.endTimeMs.takeIf { it > seg.startTimeMs } ?: overlapEnd,
                    target.windowEndMs
                ) - target.windowStartMs) / target.speed).toLong()
                seg.copy(
                    startTimeMs = s,
                    endTimeMs = maxOf(e, s + 1L),
                    words = emptyList()
                )
            } else null
        }.sortedBy { it.startTimeMs }
        if (inWindowSegments.isNotEmpty()) {
            return com.aicreatorstudio.app.ai.Transcription(
                segments = inWindowSegments,
                words = emptyList()
            )
        }
        return com.aicreatorstudio.app.ai.Transcription(emptyList(), emptyList())
    }

    /** Segment end clamped to the target window (helper). */
    private fun com.aicreatorstudio.app.ai.Subtitle.windowEndBound(
        target: GenerationTarget
    ): Long = minOf(endTimeMs, target.windowEndMs)

    /** Cancel the active generation. No-op when nothing is running. */
    fun cancelSubtitleGeneration() {
        val job = subtitleGenerationJob ?: return
        _uiState.update {
            it.copy(subtitleGenerationState = SubtitleGenerationState.Cancelling)
        }
        job.cancel()
    }

    /**
     * Delete every AI-generated subtitle overlay (subtitleSource ==
     * "ai", plus the in-memory id set for pre-anchor projects).
     * User text and SRT overlays are untouched.
     */
    fun deleteAllAiSubtitles() {
        historyDelegate.push()
        _uiState.update { state ->
            state.copy(
                textOverlays = state.textOverlays.filterNot {
                    it.id in aiSubtitleOverlayIds || it.subtitleSource == "ai"
                },
                selectedTextId = if (state.selectedTextId in aiSubtitleOverlayIds)
                    null else state.selectedTextId
            )
        }
        aiSubtitleOverlayIds.clear()
        _uiState.update { state ->
            if (state.subtitleGenerationState is SubtitleGenerationState.Completed) {
                state.copy(subtitleGenerationState = SubtitleGenerationState.Idle)
            } else {
                state
            }
        }
        persistProjectMeta()
    }


    /**
     * PHASE B3 � recompute the per-model install status map
     * from disk. Called on project open and after the
     * AI Subtitle card's "refresh" path; safe to call any
     * time (no I/O off the main thread is done here � the
     * hash check is in-memory after the model file's bytes
     * are read).
     */
    fun refreshAiModelStatuses() {
        _uiState.update { it.copy(aiModelStatuses = refreshAiModelStatusesLocked()) }
    }

    /**
     * Read every [WhisperModelSpec]'s install status. Used
     * by both the initial load and the public refresh path.
     * Reads happen on the calling thread; callers off the
     * main thread is fine because [AiModels.isVerifiedInstalled]
     * does a single ~50 MB SHA-256 read at most.
     */
    private fun refreshAiModelStatusesLocked(): Map<WhisperModelSpec, AiModelStatus> {
        val out = LinkedHashMap<WhisperModelSpec, AiModelStatus>()
        for (spec in WhisperModelSpec.entries) {
            // If the user has a DOWNLOADING or FAILED status
            // already in the state, preserve that in-flight
            // signal so the card doesn't briefly flicker to
            // "Ready" while a download is running.
            val current = _uiState.value.aiModelStatuses[spec]
            out[spec] = if (current == AiModelStatus.DOWNLOADING ||
                current == AiModelStatus.FAILED
            ) {
                current
            } else {
                AiModelStatus.forSpec(appContext, spec)
            }
        }
        return out
    }

    /**
     * True if the [WhisperModelSpec] whose id is [textId] is an
     * AI-generated subtitle (i.e. its id is in
     * [aiSubtitleOverlayIds]). Used by the AI Subtitle card to
     * decide whether to show the inline text editor.
     */
    fun isAiSubtitle(textId: String?): Boolean =
        textId != null && textId in aiSubtitleOverlayIds

    /**
     * Switch which Whisper model the next generation will use.
     * Persists into [EditorUiState.selectedWhisperModel] so the
     * AI Subtitle card radio reflects the choice immediately and
     * survives configuration changes, AND into
     * [SettingsRepository] so the manual selection survives app
     * restarts (never silently replaced).
     */
    fun setWhisperModel(model: WhisperModelSpec) {
        _uiState.update { it.copy(selectedWhisperModel = model) }
        settingsRepository.updateSelectedWhisperModel(model.name)
    }

    // ---- SRT Export ----

    /**
     * Build an SRT body from the currently-loaded AI subtitle overlays
     * and push a one-shot [com.aicreatorstudio.app.model.SrtExportTrigger]
     * into the UI state. The screen reads the trigger and launches the
     * Android "Save as" file picker (ACTION_CREATE_DOCUMENT), receives
     * the user's chosen URI, and writes the SRT bytes to it on a
     * background dispatcher.
     *
     * Times are converted from the timeline-space coordinates stored
     * on the overlays back to media-local ms by subtracting the
     * source clip's `timelineStartMs`. The SRT consumer expects
     * media-local timestamps � Premiere, DaVinci Resolve, VLC, and
     * every other player treat SRT times as offsets from the start of
     * the media, not the editor timeline.
     */
    fun onExportSrtClicked() {
        // PHASE B4: include SRT-imported overlays in the export
        // in addition to the AI-generated ones. The persistent
        // `subtitleSource` tag is the source of truth so the
        // export keeps working after a project save/reopen (the
        // in-memory `srtImportedOverlayIds` set is otherwise
        // empty on a fresh launch).
        val overlays = _uiState.value.textOverlays.filter { overlay ->
            overlay.id in aiSubtitleOverlayIds ||
                overlay.subtitleSource == "ai" ||
                overlay.subtitleSource == "srt"
        }
        if (overlays.isEmpty()) {
            _uiState.update { it.copy(errorMessage = SubtitleMessages.NO_SPEECH_FOUND_FA) }
            return
        }
        // 2026-09-07 TIMEBASE FIX � every subtitle overlay now carries
        // its own `subtitleAnchorMs` (the timeline offset that was
        // ADDED when it was created). Subtracting the per-overlay
        // anchor converts each caption back to media-local times
        // exactly � the inverse of the generation mapping. This fixes
        // (a) subtitles generated from a selected audio clip, and
        // (b) multi-clip projects, where the old code subtracted the
        // FIRST video clip's offset from everything.
        val subtitles = overlays
            .sortedBy { it.startTimeMs }
            .map { overlay ->
                val anchorMs = overlay.subtitleAnchorMs.coerceAtLeast(0L)
                com.aicreatorstudio.app.ai.Subtitle(
                    id = overlay.id,
                    text = overlay.text,
                    startTimeMs = (overlay.startTimeMs - anchorMs).coerceAtLeast(0L),
                    endTimeMs = (overlay.endTimeMs - anchorMs).coerceAtLeast(0L),
                    words = overlay.words.map { w ->
                        w.copy(
                            startTimeMs = (w.startTimeMs - anchorMs).coerceAtLeast(0L),
                            endTimeMs = (w.endTimeMs - anchorMs).coerceAtLeast(0L)
                        )
                    },
                    confidence = null
                )
            }
        val body = com.aicreatorstudio.app.ai.SrtFormatter.format(subtitles)
        val baseName = _uiState.value.project?.name?.takeIf { it.isNotBlank() } ?: "subtitles"
        val safeName = baseName.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").trim().ifBlank { "subtitles" }
        _uiState.update {
            it.copy(
                srtExportTrigger = com.aicreatorstudio.app.model.SrtExportTrigger(
                    suggestedFileName = "$safeName.srt",
                    srtBody = body
                )
            )
        }
    }

    /** Called by the screen after the SRT trigger has been handled. */
    fun consumeSrtExportTrigger() {
        _uiState.update { it.copy(srtExportTrigger = null) }
    }

    // ---- SRT Import (PHASE B4) ----

    /**
     * Set a one-shot [com.aicreatorstudio.app.model.SrtImportTrigger]
     * in the UI state. The screen reads it and launches the Android
     * "Open" file picker (ACTION_OPEN_DOCUMENT) for SRT files. The
     * [requestId] is a monotonic counter so two rapid clicks don't
     * collapse to a single LaunchedEffect.
     *
     * Clears any previous error/import state so the new run starts
     * cleanly. The import is real (no fake progress): the picker
     * returns a content:// URI, the bytes are read on
     * [Dispatchers.IO], parsed by [com.aicreatorstudio.app.ai.SrtParser],
     * mapped to TextOverlays, deduped against the existing list,
     * and appended.
     */
    fun onImportSrtClicked() {
        val nextId = (_uiState.value.srtImportTrigger?.requestId ?: 0L) + 1L
        _uiState.update {
            it.copy(
                srtImportTrigger = com.aicreatorstudio.app.model.SrtImportTrigger(requestId = nextId),
                srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Idle
            )
        }
    }

    /** Called by the screen after the import trigger has been handled. */
    fun consumeSrtImportTrigger() {
        _uiState.update { it.copy(srtImportTrigger = null) }
    }

    /** Called by the screen after the import state has been displayed. */
    fun consumeSrtImportState() {
        _uiState.update { it.copy(srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Idle) }
    }

    /**
     * Read an SRT file from a content [uri] (returned by the SAF
     * "Open" picker), parse it, map the cues onto
     * [com.aicreatorstudio.app.model.TextOverlay]s, dedupe against
     * the existing overlays, and append the survivors to the
     * project state.
     *
     * The pipeline is observable through
     * [EditorUiState.srtImportState]:
     *   Idle ? Reading ? Parsing ? Validating ? Importing ? Completed | Error
     *
     * Reading, parsing, and mapping all happen off the main
     * thread on [Dispatchers.IO]. The append to
     * `_uiState.textOverlays` happens back on the main thread
     * via the [viewModelScope] coroutine so the Compose
     * recomposition is safe.
     */
    fun importSrtFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Reading())
                }
                // 1. Read bytes off the main thread. We pull the
                //    whole file into memory � a typical SRT is
                //    <100 KB and we need it as a String for the
                //    parser. If the file is enormous the parser
                //    still handles it (it's all String ops) but
                //    the read can OOM; we catch that below.
                val body: String = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: throw java.io.IOException("Could not open the chosen file")
                }

                _uiState.update {
                    it.copy(srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Parsing())
                }
                // 2. Parse. The parser never throws.
                // 2026-09-07 THREAD FIX � parsing + mapping used to
                // run on the MAIN dispatcher (viewModelScope default)
                // and janked the UI on large SRT files; only the byte
                // read was on IO. Parse + map are pure CPU String
                // work � moved to Dispatchers.Default.
                val label = uri.lastPathSegment ?: "imported.srt"
                val clip = _uiState.value.project?.clips?.firstOrNull()
                val clipOffsetMs = clip?.timelineStartMs?.takeIf { it >= 0 } ?: 0L
                val existing = _uiState.value.textOverlays
                // 2026-09-07 FONT FIX � respect the user's persisted
                // default text font instead of hardcoding DEFAULT.
                val importFont = runCatching {
                    com.aicreatorstudio.app.model.PersianFont.valueOf(
                        settingsRepository.settings.value.defaultTextFont
                    )
                }.getOrDefault(com.aicreatorstudio.app.model.PersianFont.DEFAULT)
                val parseResult = com.aicreatorstudio.app.ai.SrtParser.parse(body, sourceLabel = label)
                val cues = parseResult.cues
                val newOverlays = withContext(Dispatchers.Default) {
                    com.aicreatorstudio.app.ai.SrtImportMapper.toTextOverlays(
                        cues = cues,
                        clipOffsetMs = clipOffsetMs,
                        font = importFont,
                        existing = existing
                    )
                }
                // 2026-09-07 COUNT FIX � `cues.size - newOverlays.size`
                // also counted time-invalid cues (end <= start) as
                // "duplicates". Deduped = the fingerprints we skipped;
                // everything else is either imported or invalid.
                val importedStarts = newOverlays.mapTo(HashSet()) { it.startTimeMs }
                val skippedDuplicates = cues.count { cue ->
                    val s = clipOffsetMs + cue.startTimeMs
                    val e = clipOffsetMs + cue.endTimeMs
                    e > s && !importedStarts.contains(s)
                }

                _uiState.update {
                    it.copy(
                        srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Validating(
                            issues = parseResult.issues
                        )
                    )
                }

                _uiState.update {
                    it.copy(
                        srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Importing()
                    )
                }
                // 4. Append to the project's overlay list and
                //    track the new ids in the in-memory "is
                //    subtitle" set. The persistent
                //    `subtitleSource = "srt"` tag on each new
                //    overlay is the source of truth; the
                //    in-memory set just lets the rest of the
                //    editor reach them without re-scanning.
                val combinedIds = (srtImportedOverlayIds + newOverlays.map { it.id })
                srtImportedOverlayIds.clear()
                srtImportedOverlayIds.addAll(combinedIds)
                _uiState.update { state ->
                    state.copy(
                        textOverlays = state.textOverlays + newOverlays,
                        srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Completed(
                            overlayCount = newOverlays.size,
                            skippedDuplicates = skippedDuplicates,
                            issues = parseResult.issues
                        )
                    )
                }
                // 5. Persist immediately so a crash between the
                //    import and the next auto-save can't lose the
                //    imported subtitles.
                persistProjectMeta()
            } catch (oom: java.lang.OutOfMemoryError) {
                _uiState.update {
                    it.copy(
                        srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Error(
                            "The SRT file is too large to read into memory."
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        srtImportState = com.aicreatorstudio.app.ai.SrtImportState.Error(
                            e.message ?: "Unknown SRT import error"
                        )
                    )
                }
            }
        }
    }

    /**
     * Write the SRT body to a content [Uri] returned by the SAF
     * "Save as" picker. UTF-8 encoded. Runs on [Dispatchers.IO] so
     * the UI thread stays free.
     */
    fun writeSrtToUri(uri: Uri, body: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: run {
                    _uiState.update { it.copy(errorMessage = SubtitleMessages.NO_SPEECH_FOUND_FA) }
                    return@launch
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = SubtitleMessages.srtExportFailed(e.message ?: e::class.java.simpleName)) }
            }
        }
    }
    
    fun initializeSubtitleModel() {
        // Refresh the per-model install statuses off the main
        // thread so the AI Subtitle card shows READY for an
        // already-downloaded model without re-downloading.
        // Idempotent; safe to call on every screen entry.
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(aiModelStatuses = refreshAiModelStatusesLocked()) }
        }
    }

    /**
     * 2026-09-06 � explicit "Download model" action for the AI
     * Subtitle card's model rows. Runs the full install pipeline
     * (bundled-first, network repair) with REAL progress mapped into
     * the per-model status so the row can render a download bar.
     * A second tap while downloading is ignored.
     */
    fun downloadWhisperModel(spec: WhisperModelSpec) {
        // 2026-09-13 CLOUD FIX � the cloud model never downloads:
        // there is no file (it runs on Groq's cloud). Pre-fix,
        // tapping the cloud row's "??????" ran the installer for
        // aiSpec() == Whisper Small and actually fetched 488 MB.
        // Guard here too, not just in the UI, so no code path can
        // start a bogus cloud download.
        if (spec.isCloud) return
        if (_uiState.value.aiModelStatuses[spec] == AiModelStatus.DOWNLOADING) return
        _uiState.update {
            it.copy(
                aiModelStatuses = it.aiModelStatuses + (spec to AiModelStatus.DOWNLOADING)
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = aiModelInstaller.installWithManifest(
                spec.aiSpec(),
                onProgress = { p ->
                    _uiState.update {
                        it.copy(
                            aiModelStatuses = it.aiModelStatuses +
                                (spec to AiModelStatus.DOWNLOADING),
                            aiModelDownloadProgress = it.aiModelDownloadProgress +
                                (spec.aiSpec().id to p.coerceIn(0f, 1f))
                        )
                    }
                }
            )
            val newStatus = if (outcome.isReady) AiModelStatus.INSTALLED
            else AiModelStatus.FAILED
            _uiState.update {
                it.copy(
                    aiModelStatuses = it.aiModelStatuses + (spec to newStatus),
                    aiModelDownloadProgress = it.aiModelDownloadProgress - spec.aiSpec().id,
                    // 2026-09-07 MSG FIX � a successful download must
                    // not wipe an unrelated pending error banner; only
                    // a FAILED download writes a message.
                    errorMessage = if (outcome.isFailed) {
                        omeletLog.error(
                            component = "ai_model",
                            event = "model_install_failed",
                            message = outcome.detail ?: outcome::class.java.simpleName
                        )
                        "دانلود مدل ممکن نشد: ${outcome.detail ?: ""}"
                    } else it.errorMessage
                )
            }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Surfaces an ExoPlayer playback error to the existing error banner
     * so the user gets a visible diagnostic instead of an empty
     * preview. The screen forwards `onPlayerError` from
     * [androidx.media3.common.Player.Listener.onPlayerError] here; the
     * raw [Throwable.message] is preserved for the developer-facing log
     * while a short Persian message is shown in the UI.
     */
    /**
     * Surfaces an ExoPlayer playback error to the existing error banner
     * so the user gets a visible diagnostic instead of an empty
     * preview. The screen forwards `onPlayerError` from
     * [androidx.media3.common.Player.Listener.onPlayerError] here; the
     * screen also calls it from the transport when there is nothing to
     * play, which is why the message is NOT wrapped in
     * [SubtitleMessages.subtitleError] — a decode failure or an empty
     * timeline is not a subtitle problem, and the old prefix told the
     * user the wrong subsystem had failed.
     */
    fun onPlayerError(errorMessage: String) {
        omeletLog.warn(
            component = "editor",
            event = "player_error",
            message = errorMessage
        )
        _uiState.update { it.copy(errorMessage = errorMessage) }
    }

    fun consumePendingSeek() {
        _uiState.update { it.copy(pendingSeekMs = null) }
    }

    // ---- Export ----

    /**
     * Kick off an export of the current project. If a previous export is
     * in flight it is cancelled first. The function returns immediately;
     * progress is observed via [EditorUiState.exportState].
     *
     * @param quality target quality preset
     * @param baseName the user-visible project name; sanitised by the engine
     */
    fun onExportClicked(
        quality: ExportQuality = ExportQuality.HIGH_1080P,
        baseName: String = _uiState.value.project?.name.orEmpty()
    ) {
        // 2026-09-19 EXPORT ERROR TRANSPARENCY — every synchronous
        // failure below (bad config, engine construction, graph
        // build) surfaces as a Failed state with a message instead
        // of dying silently or crashing the tap handler. Async
        // failures are already mapped to Failed inside
        // Media3TransformerExportEngine.
        try {
            startExportLocked(quality, baseName)
        } catch (t: Throwable) {
            _uiState.update {
                it.copy(
                    exportState = ExportState.Failed(
                        com.aicreatorstudio.app.export.ExportErrorDetail.describe(t, "STARTUP")
                    )
                )
            }
        }
    }

    /** Synchronous start half of [onExportClicked] (throwing part). */
    private fun startExportLocked(
        quality: ExportQuality,
        baseName: String
    ) {
        val state = _uiState.value
        val project = state.project ?: run {
            _uiState.update {
                it.copy(exportState = ExportState.Failed("No project loaded."))
            }
            return
        }
        // 2026-10-22 — THE EXPORT RUNS IN A FOREGROUND SERVICE.
        //
        // It used to run here, in the ViewModel's scope, which meant
        // that leaving the app (or swiping it away) killed a render
        // that might have been minutes old. CapCut keeps exporting in
        // the background, with a notification — so the service owns the
        // export now, and this class only *observes* it.
        //
        // The request carries the project's id plus the one value
        // that is not on the project (the upscaler's global default);
        // the service loads the project and resolves everything with
        // the SAME rule the editor would have used
        // (`ExportRequestResolver`), so the file is identical.
        //
        // The editor's live subtitle set is NOT part of the request:
        // the service reads the project's own (persisted) subtitles,
        // which are the same set — the in-memory lag the old code
        // worked around is not visible to a service that reads the
        // project it was told to export.
        val request = com.aicreatorstudio.app.export.ExportRequest(
            projectId = project.id,
            quality = quality,
            baseName = baseName,
            globalUpscale = state.upscale
        )
        _uiState.update { it.copy(exportState = ExportState.Preparing) }
        // Clear the PREVIOUS run's terminal state before the new one is
        // announced: the shared stream replays its current value to a
        // new collector, so without this the editor would briefly show
        // the last export's "done".
        exportEngine.prepare()
        com.aicreatorstudio.app.export.ExportService.start(appContext, request)
        // Observe the engine's SHARED state — the service is the one
        // starting the export, and the engine exposes the same stream
        // to both sides.
        viewModelScope.launch {
            exportEngine.state.collect { newState ->
                _uiState.update { it.copy(exportState = newState) }
            }
        }
    }

    /** Cancel any in-flight export. */
    fun onExportCancelClicked() {
        viewModelScope.launch {
            exportEngine.cancel()
        }
    }

    // ---- Canvas (project aspect ratio) ----

    /**
     * Set the project canvas preset (9:16, 16:9, 1:1, 4:5, Free/Original).
     * The change pushes history so undo reverts the canvas change. The
     * top-bar resolution badge is recomputed from the preset's export
     * dimensions (or, for FREE, falls back to the existing label).
     */
    fun setCanvasPreset(preset: com.aicreatorstudio.app.model.CanvasPreset) {
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project
            val updatedProject = project?.copy(canvasPreset = preset)
            val label = if (preset == com.aicreatorstudio.app.model.CanvasPreset.FREE) {
                state.resolutionLabel
            } else {
                "${preset.exportWidth} � ${preset.exportHeight}"
            }
            state.copy(
                canvasPreset = preset,
                resolutionLabel = label,
                project = updatedProject
            )
        }
        // Persist the new canvas preset alongside the project so it
        // survives leaving the editor.
        val proj = _uiState.value.project ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { projectRepository.saveClips(proj.id, proj.clips) }
        }
        persistProjectMeta()
    }

    // ---- Canvas background (letterbox fill, preview only) ----

    /**
     * Set the preview letterbox background. This is a purely visual
     * preference and does not push history (the export pipeline doesn't
     * honour it in v1, so there's nothing destructive to undo).
     */
    fun setCanvasBackground(bg: com.aicreatorstudio.app.model.CanvasBackground) {
        // Mirror the canvas background onto the project so the render
        // graph builder can read it without going through EditorUiState.
        // Solid color is mirrored as-is; Blur is treated as a
        // preview-only v1 limitation (the engine can't easily blur a
        // letterbox without breaking the v1 export pipeline) � the
        // project still carries the Blur value so the preview keeps
        // rendering it.
        _uiState.update { state ->
            val project = state.project?.copy(canvasBackground = bg)
            state.copy(canvasBackground = bg, project = project)
        }
        persistProjectMeta()
    }

    // ---- Video progress bar (timeline indicator / story timer) ----

    /**
     * 2026-10-13 — update the project's video progress bar.
     *
     * Unlike [setCanvasBackground], this **does** push history: the bar is
     * honoured by the export (see
     * [com.aicreatorstudio.app.export.ProgressBarOverlay]), so it is a real
     * document edit and undo has to be able to revert it.
     *
     * @param pushHistory `false` for the per-frame updates a slider drag
     *   produces. The undo stack is only 20 deep and does not dedupe, so a
     *   single drag would otherwise flush it entirely; the panel calls with
     *   `false` while dragging and with the default `true` on
     *   `onValueChangeFinished`, exactly like the text-drag
     *   `beginTextTransform` / `endTextTransform` pair. A live update is
     *   state-only (the preview and the export both read state) and writes
     *   nothing to Room until the commit.
     *
     * The incoming settings are [ProgressBarSettings.clamped] before they
     * are stored, so no caller can put a value into the document that the
     * geometry would then have to defend against.
     */
    fun setProgressBarSettings(
        settings: com.aicreatorstudio.app.model.ProgressBarSettings,
        pushHistory: Boolean = true
    ) {
        val next = settings.clamped()
        // A slider re-emitting the value it already has must not create an
        // undo entry or a redundant DB write.
        if (_uiState.value.progressBarSettings == next) return
        if (pushHistory) historyDelegate.push()
        _uiState.update { state ->
            // Mirror onto the project as well: the export engine reads
            // `project.progressBarSettings`, not UI state.
            val project = state.project?.copy(progressBarSettings = next)
            state.copy(progressBarSettings = next, project = project)
        }
        if (pushHistory) persistProjectMeta()
    }

    // ---- Per-clip transform (Fit / Fill / Manual) ----

    /**
     * 2026-10-13 — apply a **voice changer** preset to one clip's audio.
     *
     * Works for both kinds of target the panel can address: a video clip
     * and a standalone audio object. Both are `VideoClip` rows in
     * `project.clips` (an audio-only clip is one with
     * `isAudioOnly = true`), so a single lookup by id covers both and the
     * effect cannot be applied to "the video but not its sound".
     *
     * One tap on a preset card is one deliberate edit, so this always
     * pushes an undo snapshot — unlike the progress-bar sliders, there is
     * no per-frame drag path that would need splitting into a live and a
     * commit variant. Re-selecting the effect that is already set is a
     * no-op and therefore leaves no undo entry, which is what makes the
     * «بازنشانی به حالت عادی» button safe to tap twice.
     *
     * The change rides the clip's `aiToolsJson` blob
     * ([com.aicreatorstudio.app.db.ClipAiTools]), so no Room migration is
     * needed and old projects decode with the field defaulting to
     * [VoiceEffect.NONE].
     */
    fun setClipVoiceEffect(clipId: String, effect: VoiceEffect) {
        val current = _uiState.value.project
            ?.clips
            ?.firstOrNull { it.id == clipId }
            ?.voiceEffect
            ?: return
        // Same effect → same document. Returning before `push()` keeps a
        // repeated tap from filling the 20-deep undo stack with duplicates.
        if (current == effect) return
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(voiceEffect = effect) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    /**
     * Set the [com.aicreatorstudio.app.model.FillMode] for a clip. Pushing
     * the history snapshot only when switching *into* MANUAL, because
     * FIT/FILL are deterministic given the canvas and we don't want them
     * clogging the undo stack.
     */
    fun setClipFillMode(clipId: String, mode: com.aicreatorstudio.app.model.FillMode) {
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(fillMode = mode) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    /**
     * Apply a freeform [com.aicreatorstudio.app.model.ClipTransform] to
     * the given clip. Called from the Transform panel's Manual mode and
     * from the preview's pinch/drag gesture. Only the fields the caller
     * supplies are written (clamp first to keep state bounded).
     */
    fun setClipTransform(
        clipId: String,
        scale: Float? = null,
        offsetX: Float? = null,
        offsetY: Float? = null,
        rotation: Int? = null,
        flipH: Boolean? = null,
        flipV: Boolean? = null
    ) {
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id != clipId) return@map clip
                val t = clip.transform
                clip.copy(
                    transform = t.copy(
                        scale = (scale ?: t.scale).coerceIn(0.25f, 6f),
                        offsetX = (offsetX ?: t.offsetX).coerceIn(-0.5f, 0.5f),
                        offsetY = (offsetY ?: t.offsetY).coerceIn(-0.5f, 0.5f),
                        // 2026-10-21 — a supplied rotation is snapped to
                        // the quarter-turn grid the render path
                        // resolves, so the preview and the export can
                        // never disagree about the angle. A `null`
                        // rotation means "leave it alone" and keeps
                        // whatever is already stored.
                        rotation = if (rotation == null) t.rotation
                        else com.aicreatorstudio.app.model.snapRotationToQuarterTurn(rotation),
                        flipH = flipH ?: t.flipH,
                        flipV = flipV ?: t.flipV
                    )
                )
            }
            state.copy(project = project.copy(clips = clips))
        }
        // Don't push history on every gesture frame � only the explicit
        // panel actions push. The persist is also deferred to the end
        // of the gesture; we persist on every frame for the in-memory
        // repository so the transform survives a tab switch.
        persistClips()
    }

    /** Reset a clip's transform to identity. Pushes history. */
    fun resetClipTransform(clipId: String) {
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project ?: return@update state
            val clips = project.clips.map { clip ->
                if (clip.id == clipId) clip.copy(
                    fillMode = com.aicreatorstudio.app.model.FillMode.FIT,
                    transform = com.aicreatorstudio.app.model.ClipTransform()
                ) else clip
            }
            state.copy(project = project.copy(clips = clips))
        }
        persistClips()
    }

    /**
     * Reset the export state to [ExportState.Idle] after the user has
     * seen the result (e.g. dismissed the success dialog). The result
     * details remain available to consumers until this is called.
     */
    fun consumeExportResult() {
        val current = _uiState.value.exportState
        if (current is ExportState.Succeeded ||
            current is ExportState.Failed ||
            current is ExportState.Cancelled
        ) {
            _uiState.update { it.copy(exportState = ExportState.Idle) }
        }
    }

    // --- Undo / Redo ---
    //
    // Bounded history of full EditorUiState snapshots. Editing operations
    // call `historyDelegate.push()` BEFORE they mutate state, so undo can
    // restore the previous snapshot.
    //

    fun undo() {
        historyDelegate.undo()
        persistAfterHistoryChange()
    }

    fun redo() {
        historyDelegate.redo()
        persistAfterHistoryChange()
    }

    fun canUndo(): Boolean = historyDelegate.canUndo()
    fun canRedo(): Boolean = historyDelegate.canRedo()

    /**
     * Persists the post-undo / post-redo document, mirroring what the
     * mutating edit functions do via [persistClips] / [persistProjectMeta].
     */
    private fun persistAfterHistoryChange() {
        persistClips()
        persistProjectMeta()
    }

    // ---- AI Video Upscaler ----

    /**
     * The effective upscaler settings for the preview. When a clip is
     * selected the per-clip value wins (mirroring how [colorGrading] is
     * resolved); otherwise we fall back to the global
     * `EditorUiState.upscale`.
     */
    val effectiveUpscale: UpscalerSettings
        get() {
            val state = _uiState.value
            val selected = state.selectedClip
            val perClip = selected?.upscale
            return if (perClip != null && perClip != UpscalerSettings.Identity) perClip
            else state.upscale
        }

    /**
     * Set the upscaler preset. When a clip is selected the new value
     * is written to that clip; otherwise it goes to the global state.
     * In both cases history is pushed so undo restores the previous
     * preset, and `persistClips()` saves the new state to disk.
     */
    fun setUpscalerPreset(preset: UpscalerPreset) {
        historyDelegate.push()
        _uiState.update { state ->
            val current = effectiveUpscale
            val next = current.copy(preset = preset)
            val project = state.project
            val updatedProject = if (state.selectedClipId != null && project != null) {
                val clipId = state.selectedClipId
                project.copy(clips = project.clips.map { c ->
                    if (c.id == clipId) c.copy(upscale = next) else c
                })
            } else project
            state.copy(upscale = next, project = updatedProject)
        }
        persistClips()
    }

    /**
     * Set the upscaler strength (0..1). Same per-clip-vs-global split
     * as [setUpscalerPreset]. Strength = 0 with a non-OFF preset is a
     * valid "kill switch" � the preset stays set, the preview and
     * export skip enhancement, and re-enabling is just a slider tick
     * up.
     */
    fun setUpscalerStrength(strength: Float) {
        val safe = strength.coerceIn(0f, 1f)
        _uiState.update { state ->
            val current = effectiveUpscale
            val next = current.copy(strength = safe)
            val project = state.project
            val updatedProject = if (state.selectedClipId != null && project != null) {
                val clipId = state.selectedClipId
                project.copy(clips = project.clips.map { c ->
                    if (c.id == clipId) c.copy(upscale = next) else c
                })
            } else project
            state.copy(upscale = next, project = updatedProject)
        }
        // Strength changes can fire many times per second while the
        // user drags the slider, so we skip the persist call here �
        // the next preset change will flush the latest state to disk.
    }

    /**
     * Persist the latest position of the before/after compare divider.
     * Not pushed to history; this is UI-only state and is stored on
     * the global `EditorUiState.upscale` only (not mirrored to a
     * per-clip field, because the compare divider is a preview-only
     * UI affordance).
     */
    fun setUpscaleSplitPosition(position: Float) {
        val safe = position.coerceIn(0f, 1f)
        _uiState.update { state ->
            state.copy(upscale = state.upscale.copy(splitPosition = safe))
        }
    }

    /**
     * Reset the upscaler settings to identity (preset = OFF,
     * strength = 0.5, splitPosition = 0.5). Mirrors the per-clip
     * pattern used by [setUpscalerPreset] so a reset on a selected
     * clip zeroes out that clip's `upscale` field.
     */
    fun resetUpscaler() {
        historyDelegate.push()
        _uiState.update { state ->
            val project = state.project
            val updatedProject = if (state.selectedClipId != null && project != null) {
                val clipId = state.selectedClipId
                project.copy(clips = project.clips.map { c ->
                    if (c.id == clipId) c.copy(upscale = UpscalerSettings.Identity) else c
                })
            } else project
            state.copy(
                upscale = UpscalerSettings.Identity,
                project = updatedProject
            )
        }
        persistClips()
    }

    /**
     * Begin an upscale export. The preset's [UpscalerPreset.targetScale]
     * is folded into the output resolution by the engine.
     */
    fun onUpscaleExportClicked() {
        val state = _uiState.value
        val project = state.project ?: run {
            _uiState.update {
                it.copy(upscaleExportState = UpscalerExportState.Failed("No project loaded."))
            }
            return
        }
        val settings = effectiveUpscale
        if (!settings.isActive) {
            _uiState.update {
                it.copy(
                    upscaleExportState = UpscalerExportState.Failed(
                        "Pick an upscaler preset and a non-zero strength first."
                    )
                )
            }
            return
        }
        // Resolve output dimensions from the canvas preset, falling
        // back to the engine-recommended base for the chosen preset.
        val canvasPreset = state.canvasPreset
        val baseConfig = UpscalerExportPresets.forPreset(settings.preset)
        val outW = if (canvasPreset.exportWidth > 0) canvasPreset.exportWidth
                   else baseConfig.effectiveWidthPx
        val outH = if (canvasPreset.exportHeight > 0) canvasPreset.exportHeight
                   else baseConfig.effectiveHeightPx
        val config = baseConfig.copy(
            outputWidthPx = outW,
            outputHeightPx = outH,
            outputBaseName = project.name.ifBlank { "upscale_export" }
        )
        _uiState.update { it.copy(upscaleExportState = UpscalerExportState.Preparing) }
        val flow = upscalerExportEngine.startUpscaleExport(project, settings, config)
        viewModelScope.launch {
            flow.collect { newState ->
                _uiState.update { it.copy(upscaleExportState = newState) }
            }
        }
    }

    /** Cancel any in-flight upscale export. */
    fun onUpscaleExportCancel() {
        viewModelScope.launch {
            upscalerExportEngine.cancel()
        }
    }

    /**
     * Dismiss the result of a finished upscale export (success / fail /
     * cancelled) so the progress modal closes. Mirrors the
     * `consumeExportResult()` pattern of the normal export.
     */
    fun consumeUpscaleExportResult() {
        _uiState.update { it.copy(upscaleExportState = UpscalerExportState.Idle) }
    }

    // ====================================================================
    // AI Video Upscaler (Fast / High Quality / Auto)
    // ====================================================================
    //
    // Newer engine-driven pipeline (separate from the v1
    // `Media3UpscalerExportEngine` enhancement export above). The bottom
    // sheet at `UpscaleBottomSheet` lets the user pick one of three
    // profiles; the ViewModel routes the selection through
    // [UpscaleEngineRegistry] to the matching engine and pushes
    // progress ticks into [EditorUiState.upscaleProgress] for the
    // progress dialog.

    /**
     * Record a "user picked this profile" event from the bottom
     * sheet. Updates [EditorUiState.selectedUpscaleProfile] so the
     * card flips to the selected state on the next recomposition.
     *
     * Does not start the engine � that happens via
     * [onUpscaleStartClicked]. The split lets the UI keep the
     * selection persistent across opens without re-launching a
     * long-running job every time the user reopens the sheet.
     */
    fun onUpscaleSelected(profile: UpscaleProfile) {
        _uiState.update { it.copy(selectedUpscaleProfile = profile, upscaleEtaMs = -1L) }
    }

    /**
     * Begin an upscale run with the currently-selected profile
     * (defaults to [UpscaleProfile.Auto] when the user has not yet
     * picked one). Cancels any in-flight run first so the user
     * can't accidentally start two jobs.
     */
    fun onUpscaleStartClicked() {
        val state = _uiState.value
        val project = state.project ?: run {
            _uiState.update {
                it.copy(upscaleProgress = UpscaleProgress.Failed("No project loaded."))
            }
            return
        }
        val profile = state.selectedUpscaleProfile ?: UpscaleProfile.Auto
        val engine = upscaleEngineRegistry.resolveForProfile(profile) ?: run {
            _uiState.update {
                it.copy(
                    upscaleProgress = UpscaleProgress.Failed(
                        "No upscale engine available for profile ${profile.displayName}."
                    )
                )
            }
            return
        }
        // Cancel any in-flight run before starting a new one.
        activeUpscaleJob?.cancel()
        // Build a sensible output config from the canvas preset.
        val canvasPreset = state.canvasPreset
        val quality = ExportQuality.HIGH_1080P
        val outW = if (canvasPreset.exportWidth > 0) canvasPreset.exportWidth else quality.widthPx
        val outH = if (canvasPreset.exportHeight > 0) canvasPreset.exportHeight else quality.heightPx
        val config = ExportConfig(
            quality = quality,
            outputBaseName = project.name.ifBlank { "upscale_export" },
            outputWidthPx = outW,
            outputHeightPx = outH,
            canvasPreset = canvasPreset
        )
        // Reset progress + ETA on the UI thread so the dialog
        // re-renders from a known state.
        _uiState.update {
            it.copy(
                upscaleProgress = UpscaleProgress.Running("Starting", 0f, 0L),
                upscaleEtaMs = -1L
            )
        }
        val job = engine.start(project, profile, config)
        activeUpscaleJob = job
        viewModelScope.launch {
            // Track per-tick elapsed for ETA estimation. A short
            // ring buffer (3 samples) is enough to smooth over
            // jitter without lag.
            val samples = ArrayDeque<Pair<Long, Float>>() // (wallClockMs, percent)
            job.progress.collect { tick ->
                val now = System.currentTimeMillis()
                when (tick) {
                    is UpscaleProgress.Running -> {
                        if (samples.size >= 3) samples.removeFirst()
                        samples.addLast(now to tick.percent)
                        // Estimate rate from the oldest sample to the
                        // newest; if < 0.5% progress the ETA is
                        // meaningless, so we report -1L.
                        val first = samples.first()
                        val last = samples.last()
                        val pctDelta = last.second - first.second
                        val msDelta = (now - first.first).coerceAtLeast(1L)
                        val eta = if (pctDelta > 0.005f) {
                            val remaining = ((1f - tick.percent) / pctDelta) * msDelta
                            remaining.toLong().coerceAtLeast(0L)
                        } else -1L
                        _uiState.update {
                            it.copy(
                                upscaleProgress = tick,
                                upscaleEtaMs = eta
                            )
                        }
                    }
                    is UpscaleProgress.Succeeded,
                    is UpscaleProgress.Failed,
                    UpscaleProgress.Cancelled -> {
                        _uiState.update {
                            it.copy(
                                upscaleProgress = tick,
                                upscaleEtaMs = if (tick is UpscaleProgress.Succeeded) 0L else -1L
                            )
                        }
                        activeUpscaleJob = null
                    }
                }
            }
        }
    }

    /**
     * Cancel the in-flight upscale run, if any. Idempotent � safe to
     * call when nothing is running.
     */
    fun onUpscaleCancel() {
        activeUpscaleJob?.cancel()
        activeUpscaleJob = null
    }

    /**
     * Drop the upscale progress / result state back to idle so the
     * progress dialog closes. Called by the dialog when the user
     * taps "Done" on a Succeeded/Failed/Cancelled result.
     */
    fun consumeUpscaleProgress() {
        _uiState.update {
            it.copy(
                upscaleProgress = null,
                upscaleEtaMs = -1L
            )
        }
    }

    /**
     * The profile Auto will resolve to on this device.
     *
     * 2026-10-20 — CACHED.
     *
     * The KDoc already said "the device capabilities don't change at
     * runtime", but the value was re-resolved on EVERY recomposition
     * of the editor body — which is ~30 times a second while the
     * playhead moves. `DeviceCapabilityDetector.probe()` asks
     * `PackageManager.systemAvailableFeatures` (an IPC) and
     * `ActivityManager.getMemoryInfo` on every call, so the editor was
     * doing a binder round trip per frame during playback.
     */
    fun autoResolvedUpscaleProfile(): UpscaleProfile =
        cachedAutoUpscaleProfile ?: upscaleEngineRegistry.autoResolvedProfile()
            .also { cachedAutoUpscaleProfile = it }

    // ====================================================================
    // AI tools (Background Remove / Auto Frame / Voice Clean)
    // ====================================================================
    //
    /**
     * Live model status text + availability for an AI tool, read
     * straight from the registered engine. Panels use these to
     * render NOT DOWNLOADED / DOWNLOAD / READY without polling.
     */
    fun aiToolModelStatus(toolId: String): String =
        aiToolRegistry.byId(toolId)?.modelStatusText ?: ""

    /**
     * 2026-10-20 — CACHED.
     *
     * `isAvailable` is a property getter on the engine, and for the
     * model-backed engines it checks the model file ON DISK — which
     * meant opening and JSON-parsing `assets/ai-models/manifest.json`
     * out of the APK on every call. The editor body called this for
     * three tools on every recomposition (~30 Hz during playback), so
     * the app was re-reading and re-parsing an asset 90 times a
     * second.
     *
     * The cache is invalidated by [invalidateAiToolProbes] whenever a
     * model is installed or removed, so the panel still flips to
     * "ready" the moment the download finishes.
     */
    fun aiToolIsAvailable(toolId: String): Boolean =
        aiToolAvailabilityCache.getOrPut(toolId) {
            aiToolRegistry.byId(toolId)?.isAvailable == true
        }

    /**
     * Drop the cached model-availability probes so the next read
     * hits the disk again. Called after any install / uninstall.
     */
    private fun invalidateAiToolProbes() {
        aiToolAvailabilityCache.clear()
        cachedAutoUpscaleProfile = null
    }

    /**
     * Generic model-install flow for any [AiToolEngine] that needs
     * weights (Background Remove, Auto Frame). Mirrors the
     * Downloading ? Verifying ? Installing states into
     * [com.aicreatorstudio.app.ai.AiToolState] so the panel shows
     * real progress instead of an idle spinner.
     */
    fun ensureAiToolModelReady(toolId: String) {
        val engine = aiToolRegistry.byId(toolId) ?: return
        // Cancel any prior install / run before starting a new one so
        // the user doesn't see overlapping status messages.
        aiToolJobs.remove(toolId)?.cancel()
        updateAiToolState(toolId) {
            it.copy(
                status = com.aicreatorstudio.app.ai.AiToolStatus.Downloading,
                progress = 0f,
                message = SubtitleMessages.AiTool.DOWNLOADING_FA,
                cancellable = false
            )
        }
        val job = viewModelScope.launch {
            val result = engine.ensureModelReady { p ->
                // Translate the manager's 0..0.8 / 0.8..0.95 / 0.95..1.0
                // ranges into Downloading / Verifying / Installing.
                val (status, message) = when {
                    p < 0.8f -> com.aicreatorstudio.app.ai.AiToolStatus.Downloading to
                        SubtitleMessages.AiTool.DOWNLOADING_FA
                    p < 0.95f -> com.aicreatorstudio.app.ai.AiToolStatus.Verifying to
                        SubtitleMessages.AiTool.VERIFYING_FA
                    else -> com.aicreatorstudio.app.ai.AiToolStatus.Installing to
                        SubtitleMessages.AiTool.INSTALLING_FA
                }
                updateAiToolState(toolId) { st ->
                    if (st.status == com.aicreatorstudio.app.ai.AiToolStatus.Downloading ||
                        st.status == com.aicreatorstudio.app.ai.AiToolStatus.Verifying ||
                        st.status == com.aicreatorstudio.app.ai.AiToolStatus.Installing
                    ) {
                        st.copy(status = status, progress = p, message = message)
                    } else st
                }
            }
            result.fold(
                onSuccess = {
                    // 2026-10-20 — the model just landed on disk, so
                    // the cached availability probe is stale.
                    invalidateAiToolProbes()
                    updateAiToolState(toolId) { st ->
                        st.copy(
                            status = com.aicreatorstudio.app.ai.AiToolStatus.Ready,
                            progress = 1f,
                            message = SubtitleMessages.AiTool.READY_FA,
                            cancellable = false
                        )
                    }
                },
                onFailure = { e ->
                    // 2026-10-13 — a failed weight download / install.
                    // The panel shows `e.message`, which for an IOException
                    // from the installer is often a bare path-less English
                    // string; the log keeps the tool id and the exception
                    // identity so the panel can stay user-facing.
                    omeletLog.error(
                        component = "ai_tool",
                        event = "model_ensure_failed",
                        message = e.message ?: e::class.java.simpleName,
                        fields = mapOf("tool" to toolId),
                        throwable = e
                    )
                    updateAiToolState(toolId) { st ->
                        st.copy(
                            status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                            progress = 0f,
                            message = e.message ?: SubtitleMessages.AiTool.FAILED_FA,
                            cancellable = false
                        )
                    }
                }
            )
        }
        aiToolJobs[toolId] = job
        job.invokeOnCompletion { aiToolJobs.remove(toolId, job) }
    }

    /** Auto Frame model install (delegates to the generic flow). */
    fun ensureAutoframeModelReady() = ensureAiToolModelReady("autoframe")

    // ------------------------------------------------------------------
    // 2026-09-28 Motion Tracking
    // ------------------------------------------------------------------

    /**
     * Which appearance-tracker backend this device resolved. Read
     * once and cached inside [RoiTrackerFactory], so calling this from
     * composition is free after the first call.
     *
     * There is deliberately **no** model-install path for Motion
     * Tracking: the engine ships no weights (the OpenCV tracker is
     * native code, and the fallback is pure Kotlin), so the panel shows
     * a backend line instead of a Download button.
     */
    fun motionTrackBackendText(): String =
        runCatching { com.aicreatorstudio.app.ai.tracking.RoiTrackerFactory.statusText() }
            .getOrDefault("")

    /**
     * Record the ROI the user dragged, normalized 0..1 against the
     * video frame. Called at gesture rate, so it must stay a single
     * `copy` — no recomputation, no history entry.
     */
    fun setPendingTrackingRoi(roi: android.graphics.RectF) {
        _uiState.update { it.copy(pendingTrackingRoi = roi) }
    }

    /**
     * Enter ROI-selection mode: pause playback and show
     * [com.aicreatorstudio.app.ui.screens.editor.RoiSelectorOverlay]
     * over the frozen frame.
     *
     * Pausing first is not cosmetic — the user must draw a box against
     * a *stable* frame, and the engine later initialises the tracker on
     * exactly the frame at the playhead. Drawing against a moving frame
     * would hand the tracker an initial box that no longer matches the
     * frame it initialises on.
     */
    fun enterTrackingSelection() {
        _uiState.update { it.copy(isPlaying = false, trackingSelectionActive = true) }
    }

    /** Leave selection mode, keeping the drawn ROI. */
    fun exitTrackingSelection() {
        _uiState.update { it.copy(trackingSelectionActive = false) }
    }

    /**
     * ESRGAN model install for the AI Upscale panel. Mirrors the
     * Downloading ? Verifying ? Installing states into
     * `aiTools["upscale"]` so the panel shows real progress
     * instead of an idle spinner. Uses [UnifiedModelInstaller]
     * + [ModelRegistry] (the same source of truth the ESRGAN
     * engines use), so the installed path is exactly the path
     * the Generate pipeline reads.
     *
     * Download eligibility is storage + network only � RAM
     * plays no role.
     */
    fun ensureUpscaleModelReady() {
        val toolId = "upscale"
        // Cancel any prior install before starting a new one so
        // the user doesn't see overlapping status messages.
        aiToolJobs.remove(toolId)?.cancel()
        updateAiToolState(toolId) {
            it.copy(
                status = com.aicreatorstudio.app.ai.AiToolStatus.Downloading,
                progress = 0f,
                message = SubtitleMessages.AiTool.DOWNLOADING_FA,
                cancellable = false
            )
        }
        val job = viewModelScope.launch {
            val spec = esrganSpecCache
                ?: ModelRegistry.fromAssets(appContext).getOrNull()
                    ?.byId(EsrganUpscalePipeline.MODEL_ID)
                    ?.also { esrganSpecCache = it }
            if (spec == null) {
                updateAiToolState(toolId) {
                    it.copy(
                        status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                        progress = 0f,
                        message = "Upscale model catalog entry missing.",
                        cancellable = false
                    )
                }
                return@launch
            }
            val outcome = unifiedModelInstaller.install(
                spec,
                phaseListener = AiPhaseListener { phase, _ ->
                    val mapped = when (phase) {
                        AiPhase.DOWNLOADING, AiPhase.NOT_INSTALLED ->
                            com.aicreatorstudio.app.ai.AiToolStatus.Downloading
                        AiPhase.VERIFYING ->
                            com.aicreatorstudio.app.ai.AiToolStatus.Verifying
                        AiPhase.INSTALLING, AiPhase.LOADING ->
                            com.aicreatorstudio.app.ai.AiToolStatus.Installing
                        AiPhase.READY ->
                            com.aicreatorstudio.app.ai.AiToolStatus.Ready
                    }
                    val msg = when (mapped) {
                        com.aicreatorstudio.app.ai.AiToolStatus.Downloading ->
                            SubtitleMessages.AiTool.DOWNLOADING_FA
                        com.aicreatorstudio.app.ai.AiToolStatus.Verifying ->
                            SubtitleMessages.AiTool.VERIFYING_FA
                        com.aicreatorstudio.app.ai.AiToolStatus.Installing ->
                            SubtitleMessages.AiTool.INSTALLING_FA
                        else -> SubtitleMessages.AiTool.READY_FA
                    }
                    updateAiToolState(toolId) { st ->
                        if (st.status == com.aicreatorstudio.app.ai.AiToolStatus.Downloading ||
                            st.status == com.aicreatorstudio.app.ai.AiToolStatus.Verifying ||
                            st.status == com.aicreatorstudio.app.ai.AiToolStatus.Installing
                        ) {
                            st.copy(status = mapped, message = msg)
                        } else st
                    }
                },
                onProgress = { p ->
                    updateAiToolState(toolId) { st ->
                        if (st.status == com.aicreatorstudio.app.ai.AiToolStatus.Downloading ||
                            st.status == com.aicreatorstudio.app.ai.AiToolStatus.Verifying ||
                            st.status == com.aicreatorstudio.app.ai.AiToolStatus.Installing
                        ) {
                            st.copy(progress = p.coerceIn(0f, 1f))
                        } else st
                    }
                }
            )
            when (outcome) {
                is InstallOutcome.Ready -> {
                    // 2026-10-20 — the model just landed; the cached
                    // availability probe is stale.
                    invalidateAiToolProbes()
                    updateAiToolState(toolId) { st ->
                        st.copy(
                            status = com.aicreatorstudio.app.ai.AiToolStatus.Ready,
                            progress = 1f,
                            message = SubtitleMessages.AiTool.READY_FA,
                            cancellable = false
                        )
                    }
                }
                is InstallOutcome.Failed -> updateAiToolState(toolId) { st ->
                    st.copy(
                        status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                        progress = 0f,
                        message = outcome.detail,
                        cancellable = false
                    )
                }
            }
        }
        aiToolJobs[toolId] = job
        job.invokeOnCompletion { aiToolJobs.remove(toolId, job) }
    }

    /**
     * Live ESRGAN availability for the AI Upscale panel. Reads
     * the verified-install state (sidecar check � main-safe) so
     * a downloaded model reports ready immediately after app
     * restart, without re-downloading.
     */
    fun upscaleEsrganReady(): Boolean {
        val spec = esrganSpecCache ?: return aiToolState("upscale").status ==
            com.aicreatorstudio.app.ai.AiToolStatus.Ready
        return unifiedModelInstaller.isVerifiedInstalled(spec)
    }

    /**
     * One-line ESRGAN status for the AI Upscale panel:
     * download progress while installing, "ready" when the
     * verified model is on disk, "not installed" otherwise.
     */
    fun upscaleEsrganStatusText(): String {
        val st = aiToolState("upscale")
        return when (st.status) {
            com.aicreatorstudio.app.ai.AiToolStatus.Downloading,
            com.aicreatorstudio.app.ai.AiToolStatus.Verifying,
            com.aicreatorstudio.app.ai.AiToolStatus.Installing ->
                st.message ?: SubtitleMessages.AiTool.DOWNLOADING_FA
            com.aicreatorstudio.app.ai.AiToolStatus.Failed ->
                st.message ?: "Model download failed"
            else ->
                if (upscaleEsrganReady()) "Model: ESRGAN � ready (offline)"
                else "Model: ESRGAN � not installed"
        }
    }

    // Each tool is backed by an [AiToolEngine] from the registry.
    // The ViewModel owns the per-tool [AiToolState] in
    // `_uiState.aiTools` so the dock sub-panels can render the
    // progress bar, status, and error message without subscribing to
    // any other source. On success the engine's [AiToolResult] is
    // merged onto the selected [VideoClip] and the project is
    // persisted immediately (matches the upscaler panel pattern).
    //
    // The job is tracked in a small map so the user can cancel a
    // long-running tool without restarting the editor.

    /** Per-tool job handles keyed by [AiToolEngine.id]. */
    private val aiToolJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Read the current per-tool state, defaulting to [AiToolState.Idle]
     * for tools the UI hasn't touched yet. Lets the dock read
     * `state.aiTools[toolId] ?: AiToolState.Idle` without worrying
     * about which tools have been registered.
     */
    fun aiToolState(toolId: String): com.aicreatorstudio.app.ai.AiToolState =
        _uiState.value.aiTools[toolId] ?: com.aicreatorstudio.app.ai.AiToolState(
            toolId = toolId
        )

    /**
     * Launch an AI tool run against the currently selected clip.
     * Cancels any prior run for the same tool id first so the user
     * doesn't get overlapping jobs.
     *
     * @param toolId one of [AiToolEngine.id] � `"matting"`,
     *   `"autoframe"`, `"voiceclean"`.
     * @param options collected from the dock sub-panel.
     */
    fun runAiTool(
        toolId: String,
        options: com.aicreatorstudio.app.ai.AiToolOptions
    ) {
        val engine = aiToolRegistry.byId(toolId)
        if (engine == null) {
            omeletLog.error(
                component = "ai_tool",
                event = "unknown_tool",
                message = "no engine registered for toolId=$toolId"
            )
            updateAiToolState(toolId) {
                it.copy(
                    status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                    message = "ابزار ناشناس: $toolId",
                    cancellable = false
                )
            }
            return
        }
        val clip = _uiState.value.selectedClip
        if (clip == null) {
            omeletLog.warn(
                component = "ai_tool",
                event = "run_rejected_no_clip",
                message = "toolId=$toolId with no selected clip",
                fields = mapOf("tool" to toolId)
            )
            updateAiToolState(toolId) {
                it.copy(
                    status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                    message = SubtitleMessages.AiTool.NO_CLIP_SELECTED_FA,
                    cancellable = false
                )
            }
            return
        }

        // Cancel any prior run for this tool so jobs don't overlap.
        aiToolJobs.remove(toolId)?.cancel()

        // 2026-10-20 — a NEW run must not leave the PREVIOUS run's
        // result on screen. The OCR panel renders its result card
        // from `ocrResult != null` independently of the tool status,
        // so a stale result survived both a new run and a FAILED run:
        // run #1 succeeded, run #2 failed, and the card still showed
        // run #1's text (the "no text found" branch was unreachable).
        if (toolId == "ocr") {
            _uiState.update { it.copy(ocrResult = null) }
        }

        updateAiToolState(toolId) {
            it.copy(
                status = com.aicreatorstudio.app.ai.AiToolStatus.Processing,
                progress = 0f,
                message = null,
                cancellable = true
            )
        }

        // 2026-10-13 — the start record is what makes a hang diagnosable:
        // a failure log with no matching start means the job never ran.
        omeletLog.info(
            component = "ai_tool",
            event = "run_started",
            message = "toolId=$toolId clipId=${clip.id}",
            fields = mapOf("tool" to toolId)
        )

        // 2026-10-17 — Background Remove streams its sidecar masks
        // directly from the engine while the analysis loop runs. The
        // engine needs the owning project id (only the host knows it),
        // so it is routed here rather than holding up to MAX_FRAMES
        // full-resolution bitmaps in the result and compressing them
        // after success was already shown.
        val routedOptions = if (toolId == "matting") {
            val projectId = _uiState.value.project?.id
            if (projectId == null) {
                omeletLog.error(
                    component = "ai_tool",
                    event = "matting_no_project",
                    message = "matting run requested with no open project"
                )
                updateAiToolState(toolId) {
                    it.copy(
                        status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                        message = SubtitleMessages.AiTool.NO_CLIP_SELECTED_FA,
                        cancellable = false
                    )
                }
                return
            }
            options.copy(mattingProjectId = projectId)
        } else options

        val job = viewModelScope.launch {
            val uri = android.net.Uri.parse(clip.uri)
            val result = engine.process(
                clipId = clip.id,
                videoUri = uri,
                options = routedOptions,
                onProgress = { p -> publishAiToolProgress(toolId, p) }
            )
            result.fold(
                onSuccess = { r -> onAiToolSuccess(toolId, clip.id, r) },
                onFailure = { e -> onAiToolFailure(toolId, e) }
            )
        }
        aiToolJobs[toolId] = job
        job.invokeOnCompletion {
            aiToolJobs.remove(toolId, job)
        }
    }

    /**
     * Cancel an in-flight AI tool run. Idempotent � safe to call
     * when no job is running.
     */
    fun cancelAiTool(toolId: String) {
        val job = aiToolJobs.remove(toolId) ?: return
        job.cancel()
        updateAiToolState(toolId) {
            it.copy(
                status = com.aicreatorstudio.app.ai.AiToolStatus.Cancelled,
                message = SubtitleMessages.AiTool.CANCELLED_FA,
                cancellable = false
            )
        }
    }

    /**
     * Drop a tool's state back to Idle **and undo the effect it
     * committed to the clip**.
     *
     * 2026-10-20 — CRITICAL UX FIX.
     *
     * The old implementation only reset the per-tool status entry:
     *
     * ```
     * fun resetAiTool(toolId: String) {
     *     aiToolJobs.remove(toolId)?.cancel()
     *     updateAiToolState(toolId) { AiToolState(toolId = toolId) }
     * }
     * ```
     *
     * but every panel's "Reset" pill calls it, and the export pipeline
     * reads the **clip** state (`clip.matting.enabled`,
     * `clip.autoFrame.enabled` + `cropKeyframes`, `clip.motionTrack`,
     * `clip.voiceClean.enabled`). So the user ran Background Remove,
     * tapped Reset, saw the card go blank — and the exported video
     * STILL had the subject cut out, because `matting.enabled` was
     * still true and the sidecar masks were still on disk.
     *
     * The reset now clears the committed effect as well (mirroring
     * [clearReframe] / [clearVoiceEnhancement], which already did this
     * correctly for the newer panels).
     */
    fun resetAiTool(toolId: String) {
        aiToolJobs.remove(toolId)?.cancel()
        updateAiToolState(toolId) {
            com.aicreatorstudio.app.ai.AiToolState(toolId = toolId)
        }

        // OCR has its own staged result + committed clip field.
        if (toolId == "ocr") {
            clearOcrCommitted()
            return
        }

        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip ?: return

        val updatedClips = project.clips.map { c ->
            if (c.id != clip.id) c
            else when (toolId) {
                "matting" -> c.copy(
                    matting = com.aicreatorstudio.app.model.MattingSettings.Identity
                )
                "autoframe" -> c.copy(
                    autoFrame = com.aicreatorstudio.app.model.AutoFrameSettings.Identity,
                    cropKeyframes = emptyList()
                )
                "motiontrack" -> c.copy(
                    motionTrack = com.aicreatorstudio.app.model.MotionTrackSettings.Identity,
                    cropKeyframes = emptyList()
                )
                "voiceclean" -> c.copy(
                    voiceClean = com.aicreatorstudio.app.model.VoiceCleanSettings.Identity
                )
                else -> c
            }
        }
        if (updatedClips == project.clips) return

        historyDelegate.push()
        _uiState.update { it.copy(project = project.copy(clips = updatedClips)) }
        persistClips()
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )

        // The matting masks live on disk next to the project; leaving
        // them behind means a later export could still find them.
        if (toolId == "matting") {
            runCatching {
                com.aicreatorstudio.app.ai.MattingSidecar.delete(
                    appContext, project.id, clip.id
                )
            }
        }
    }

    /**
     * Trigger the model download for the **Background Remove** tool.
     * Delegates to the generic [ensureAiToolModelReady] flow so the
     * panel shows a unified "Downloading -> Verifying -> Installing"
     * progress bar, including the "Model integrity check failed -
     * retry" line on a checksum mismatch.
     */
    fun ensureMattingModelReady() = ensureAiToolModelReady("matting")

    // ---- internals ----

    private fun publishAiToolProgress(toolId: String, progress: Float) {
        updateAiToolState(toolId) {
            // Only update progress while still Processing � once a
            // terminal state lands (success / fail / cancelled) the
            // tool shouldn't be overwriting it on a late tick.
            if (it.status == com.aicreatorstudio.app.ai.AiToolStatus.Processing) {
                it.copy(progress = progress.coerceIn(0f, 1f))
            } else it
        }
    }

    private fun onAiToolSuccess(
        toolId: String,
        clipId: String,
        result: com.aicreatorstudio.app.ai.AiToolResult
    ) {
        // Merge the result back onto the matching clip in the
        // current project, then persist.
        //
        // 2026-10-20 — the old code did a bare `?: return`, which left
        // the tool stuck in `Processing` with `cancellable = true` when
        // the project was null. `AiToolUiPolicy.generateEnabled` is
        // false while Processing, so the Run button stayed disabled
        // for the rest of the session.
        val project = _uiState.value.project ?: run {
            updateAiToolState(toolId) {
                it.copy(
                    status = com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                    message = SubtitleMessages.AiTool.NO_CLIP_SELECTED_FA,
                    cancellable = false
                )
            }
            return
        }
        val updatedClips = project.clips.map { c ->
            if (c.id != clipId) c
            else {
                // 2026-09-28 Motion Tracking. This tool shares the
                // `cropKeyframes` list with Auto Frame (see
                // `CropKeyframe`'s KDoc for why one type serves both),
                // so enabling a track must clear the Auto Frame crop.
                // Leaving both enabled would let one trajectory drive a
                // crop AND an overlay binding at once, which is never
                // what the user asked for.
                val tracking = result.motionTrack
                val trackingEnabled = tracking?.enabled == true
                c.copy(
                    // 2026-10-20 — MERGE, do not replace.
                    //
                    // The engine builds a fresh `MattingSettings` and
                    // the old code assigned it wholesale:
                    //
                    //     matting = result.matting ?: c.matting
                    //
                    // `MattingSettings` also carries `backgroundMode`
                    // and `backgroundColor`, which the ENGINE DOES NOT
                    // SET. Every run therefore reset the mode to the
                    // default `ORIGINAL` and the colour to
                    // `0xFF111111` — so the user picked "عکس" + a
                    // background, ran Background Remove, saw
                    // "تکمیل شد", and the exported video came out with
                    // the ORIGINAL background (the export reads
                    // `settings.backgroundMode` and bails out to the
                    // untouched frame when it is ORIGINAL).
                    //
                    // Only the fields the engine owns are written back;
                    // the user's background choice is preserved.
                    //
                    // 2026-10-25 — `feather` now joins this list only
                    // because a fresh run REPLACES the sidecar with raw
                    // masks (the engine re-warms it to
                    // `MaskPostProcess.DEFAULT_FEATHER`; a migration blob
                    // whose old feather is baked into its PNGs is not
                    // touched because no run happened). `expansion` and
                    // `reverse` are pure composition-time user knobs —
                    // the Engine never sees them and they are never
                    // overwritten here.
                    //
                    // 2026-10-27 — `backgroundMode` joins the write with
                    // one caveat (`MattingSettings.runDefaultBackground
                    // Mode`): a run whose mode is still the untouched
                    // ORIGINAL default now commits TRANSPARENT — the
                    // user's plain press must mean "subject stays,
                    // background gone", not a pass-through that visibly
                    // changes nothing. A deliberately-picked mode
                    // survives untouched.
                    matting = result.matting?.let { r ->
                        c.matting.copy(
                            enabled = r.enabled,
                            threshold = r.threshold,
                            feather = r.feather,
                            replacementBackgroundUri = r.replacementBackgroundUri,
                            algorithm = r.algorithm,
                            backgroundMode =
                                com.aicreatorstudio.app.model.MattingSettings
                                    .runDefaultBackgroundMode(c.matting.backgroundMode)
                        )
                    } ?: c.matting,
                    autoFrame = if (trackingEnabled) {
                        com.aicreatorstudio.app.model.AutoFrameSettings.Identity
                    } else {
                        result.autoFrame ?: c.autoFrame
                    },
                    cropKeyframes = result.cropKeyframes.ifEmpty { c.cropKeyframes },
                    motionTrack = tracking ?: c.motionTrack,
                    voiceClean = result.voiceClean ?: c.voiceClean,
                    // OCR output is staged in memory only (the user
                    // reviews/edits it in the panel and "Apply" commits
                    // it) - the clip's persisted ocrResult is untouched
                    // by the Generate step.
                    ocrResult = c.ocrResult
                )
            }
        }
        val updatedProject = project.copy(clips = updatedClips)
        _uiState.update {
            it.copy(project = updatedProject)
        }
        // Persist the per-clip AI tool settings as a JSON blob
        // (matches the v6 schema).
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
        // OCR: publish the staged result for the panel.
        if (toolId == "ocr") {
            val payload = result.ocr
            if (payload != null) {
                _uiState.update {
                    it.copy(
                        ocrResult = com.aicreatorstudio.app.model.OcrPanelResult(
                            // 2026-10-20 — carry the clip identity so
                            // "Apply" targets the clip the OCR actually
                            // ran on, not whatever is selected now.
                            clipId = clipId,
                            frames = payload.frames,
                            languages = payload.languages
                        )
                    )
                }
            }
        }
        // 2026-10-17 — the host no longer persists matting masks. The
        // engine streams each PNG into the project sidecar while the
        // analysis loop runs and finalizes the manifest BEFORE
        // reporting success, so reaching [onAiToolSuccess] means the
        // sidecar is already complete and verified-able. (The old
        // post-run launch held up to MAX_FRAMES full-resolution
        // bitmaps in the result and compressed them here — the main
        // cause of the slow Apply and the premature "Completed".)
        // The live preview shows a readiness chip only; preview
        // compositing is not part of v1 (EditorScreen's chip block).
        updateAiToolState(toolId) {
            it.copy(
                status = com.aicreatorstudio.app.ai.AiToolStatus.Completed,
                progress = 1f,
                message = result.summary.ifBlank { SubtitleMessages.AiTool.COMPLETED_FA },
                cancellable = false
            )
        }
    }

    private fun onAiToolFailure(
        toolId: String,
        error: Throwable
    ) {
        val isCancel = error is kotlinx.coroutines.CancellationException
        // 2026-10-13 — the AI panels render `error.message` straight into
        // the tool card. When a message is missing, or missing entirely
        // from the log, "the AI feature does not work" is all the user
        // can say. Record both the identity and the tool.
        if (isCancel) {
            omeletLog.info("ai_tool", "run_cancelled", "toolId=$toolId")
        } else {
            omeletLog.error(
                component = "ai_tool",
                event = "run_failed",
                message = error.message ?: error::class.java.simpleName,
                fields = mapOf("tool" to toolId),
                throwable = error
            )
        }
        updateAiToolState(toolId) {
            it.copy(
                status = if (isCancel) com.aicreatorstudio.app.ai.AiToolStatus.Cancelled
                else com.aicreatorstudio.app.ai.AiToolStatus.Failed,
                message = error.message ?: SubtitleMessages.AiTool.UNKNOWN_ERROR_FA,
                cancellable = false
            )
        }
        // 2026-10-20 — a failed OCR run must not leave the previous
        // run's text on screen (the panel renders the card from
        // `ocrResult != null`, independent of the tool status).
        if (toolId == "ocr") {
            _uiState.update { it.copy(ocrResult = null) }
        }
    }

    private inline fun updateAiToolState(
        toolId: String,
        transform: (com.aicreatorstudio.app.ai.AiToolState) -> com.aicreatorstudio.app.ai.AiToolState
    ) {
        _uiState.update { state ->
            val current = state.aiTools[toolId]
                ?: com.aicreatorstudio.app.ai.AiToolState(toolId = toolId)
            state.copy(aiTools = state.aiTools + (toolId to transform(current)))
        }
    }

    // ---- Auto Silence Cut (AI) ----

    private var autoCutSilenceJob: kotlinx.coroutines.Job? = null

    /**
     * Auto-detect and cut silence across all video clips using
     * Silero VAD. Runs asynchronously with progress reporting.
     * The user can cancel via [cancelAutoCutSilence].
     */
    fun autoCutSilence(
        speechThreshold: Float = 0.5f,
        speechPadMs: Float = 300f,
        minSilenceMs: Float = 500f
    ) {
        // Cancel any previous run
        autoCutSilenceJob?.cancel()

        val project = _uiState.value.project ?: return
        val clips = project.clips
        if (clips.none { !it.isAudioOnly }) {
            _uiState.update { it.copy(
                autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                    "هیچ کلیپ ویدیویی وجود ندارد"
                )
            ) }
            return
        }

        autoCutSilenceJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(
                autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Processing(
                    progress = 0f,
                    message = "در حال شناسایی و حذف سکوت‌ها..."
                )
            ) }

            try {
                val cutter = com.aicreatorstudio.app.ai.vad.SilenceCutter(appContext)
                val result = cutter.cutSilence(
                    clips = clips,
                    speechThreshold = speechThreshold,
                    speechPadMs = speechPadMs,
                    minSilenceMs = minSilenceMs,
                    onProgress = { progress ->
                        _uiState.update { state ->
                            val current = state.autoSilenceCutState
                            if (current is com.aicreatorstudio.app.model.AutoSilenceCutState.Processing) {
                                state.copy(autoSilenceCutState = current.copy(progress = progress))
                            } else state
                        }
                    },
                    checkCancelled = {
                        val job = autoCutSilenceJob
                        if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException()
                    }
                )
                cutter.release()

                // Apply result to timeline
                historyDelegate.push()
                _uiState.update { it.copy(
                    project = it.project?.copy(clips = result.newClips),
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Completed(
                        silencesRemoved = result.totalSilencesRemoved,
                        silenceMsRemoved = result.totalSilenceMsRemoved
                    )
                ) }
                persistClips()

            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Idle
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                        e.message ?: "خطا در پردازش"
                    )
                ) }
            }
        }
    }

    fun cancelAutoCutSilence() {
        autoCutSilenceJob?.cancel()
        autoCutSilenceJob = null
    }

    fun clearAutoSilenceCutResult() {
        silencePreviewAnalyses = emptyList()
        _uiState.update { it.copy(
            autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Idle
        ) }
    }

    // ---- PHASE 1 — Analyze → Preview → Apply (non-destructive) ----

    /** Last analysis backing the Preview state; source for [applySilencePreview]. */
    private var silencePreviewAnalyses: List<com.aicreatorstudio.app.ai.vad.SilenceCutter.ClipSilenceAnalysis> =
        emptyList()

    /**
     * PHASE 1 — analyze only. Decodes audio + runs on-device Silero VAD,
     * then parks the scored segments in [AutoSilenceCutState.Preview].
     * The timeline is NOT touched; the user reviews first.
     */
    fun analyzeSilence(
        speechThreshold: Float = 0.5f,
        padBeforeMs: Float = 300f,
        padAfterMs: Float = 300f,
        minSilenceMs: Float = 500f
    ) {
        autoCutSilenceJob?.cancel()

        val project = _uiState.value.project ?: return
        val clips = project.clips
        if (clips.none { !it.isAudioOnly }) {
            _uiState.update { it.copy(
                autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                    "هیچ کلیپ ویدیویی وجود ندارد"
                )
            ) }
            return
        }

        val config = com.aicreatorstudio.app.ai.vad.SilenceDetectionConfig(
            threshold = speechThreshold,
            minSilenceMs = minSilenceMs,
            padBeforeMs = padBeforeMs,
            padAfterMs = padAfterMs
        )

        autoCutSilenceJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(
                autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Processing(
                    progress = 0f,
                    message = "در حال شناسایی سکوت‌ها..."
                )
            ) }

            try {
                val cutter = com.aicreatorstudio.app.ai.vad.SilenceCutter(appContext)
                val analyses = cutter.analyzeClips(
                    clips = clips,
                    config = config,
                    onProgress = { progress ->
                        _uiState.update { state ->
                            val current = state.autoSilenceCutState
                            if (current is com.aicreatorstudio.app.model.AutoSilenceCutState.Processing) {
                                state.copy(autoSilenceCutState = current.copy(progress = progress))
                            } else state
                        }
                    },
                    checkCancelled = {
                        val job = autoCutSilenceJob
                        if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException()
                    }
                )
                cutter.release()
                // PHASE 2 — the timeline may have changed DURING analysis.
                // Never park a preview against a moved timeline.
                val currentClips = _uiState.value.project?.clips ?: clips
                val startedKey = com.aicreatorstudio.app.ai.vad.SilenceCutter.previewSourceKey(clips)
                val finishedKey = com.aicreatorstudio.app.ai.vad.SilenceCutter.previewSourceKey(currentClips)
                if (startedKey != finishedKey) {
                    silencePreviewAnalyses = emptyList()
                    _uiState.update { it.copy(
                        autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                            "تایم‌لاین حین شناسایی تغییر کرد — لطفاً دوباره شناسایی کنید"
                        )
                    ) }
                    return@launch
                }
                silencePreviewAnalyses = analyses

                val previewClips = analyses.map { a ->
                    val sorted = a.segments.sortedBy { it.startMs }
                    com.aicreatorstudio.app.model.SilencePreviewClip(
                        clipId = a.clipId,
                        fileName = a.fileName,
                        clipDurationMs = a.clipDurationMs,
                        segments = sorted.mapIndexed { index, seg ->
                            com.aicreatorstudio.app.model.SilencePreviewSegment(
                                id = com.aicreatorstudio.app.ai.vad.SilenceCutter.segmentId(a.clipId, index),
                                clipId = a.clipId,
                                startMs = seg.startMs,
                                endMs = seg.endMs,
                                confidence = seg.confidence,
                                selected = true
                            )
                        }
                    )
                }
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Preview(
                        clips = previewClips,
                        config = config,
                        totalSegments = previewClips.sumOf { c -> c.segments.size },
                        totalSilenceMs = previewClips.sumOf { c -> c.silenceMs },
                        sourceKey = com.aicreatorstudio.app.ai.vad.SilenceCutter.previewSourceKey(clips)
                    )
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Idle
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                        e.message ?: "خطا در پردازش"
                    )
                ) }
            }
        }
    }

    /**
     * PHASE 3 — Smart Jump Cut analyze. Same on-device VAD pipeline as
     * [analyzeSilence], then deterministic ranking: only high-quality
     * recommendations are pre-selected, borderline pauses stay visible
     * but unselected for manual review. Timeline untouched until Apply
     * ([applySilencePreview] is reused unchanged).
     */
    fun analyzeJumpCut(
        speechThreshold: Float = 0.5f,
        padBeforeMs: Float = 300f,
        padAfterMs: Float = 300f,
        minSilenceMs: Float = 500f
    ) {
        autoCutSilenceJob?.cancel()

        val project = _uiState.value.project ?: return
        val clips = project.clips
        if (clips.none { !it.isAudioOnly }) {
            _uiState.update { it.copy(
                autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                    "هیچ کلیپ ویدیویی وجود ندارد"
                )
            ) }
            return
        }

        val silence = com.aicreatorstudio.app.ai.vad.SilenceDetectionConfig(
            threshold = speechThreshold,
            minSilenceMs = minSilenceMs,
            padBeforeMs = padBeforeMs,
            padAfterMs = padAfterMs
        )
        val jump = com.aicreatorstudio.app.ai.vad.JumpCutConfig(
            minSilenceMs = minSilenceMs.toDouble()
        )

        autoCutSilenceJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(
                autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Processing(
                    progress = 0f,
                    message = "در حال تحلیل برای کات هوشمند..."
                )
            ) }

            try {
                val cutter = com.aicreatorstudio.app.ai.vad.SilenceCutter(appContext)
                val analyzer = com.aicreatorstudio.app.ai.vad.LocalJumpCutAnalyzer(cutter)
                val ranked = analyzer.analyze(
                    clips = clips,
                    silence = silence,
                    jump = jump,
                    onProgress = { progress ->
                        _uiState.update { state ->
                            val current = state.autoSilenceCutState
                            if (current is com.aicreatorstudio.app.model.AutoSilenceCutState.Processing) {
                                state.copy(autoSilenceCutState = current.copy(progress = progress))
                            } else state
                        }
                    },
                    checkCancelled = {
                        val job = autoCutSilenceJob
                        if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException()
                    }
                )
                cutter.release()
                // Same stale-timeline guard as analyzeSilence: never park
                // a preview against a moved timeline.
                val currentClips = _uiState.value.project?.clips ?: clips
                val startedKey = com.aicreatorstudio.app.ai.vad.SilenceCutter.previewSourceKey(clips)
                val finishedKey = com.aicreatorstudio.app.ai.vad.SilenceCutter.previewSourceKey(currentClips)
                if (startedKey != finishedKey) {
                    silencePreviewAnalyses = emptyList()
                    _uiState.update { it.copy(
                        autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                            "تایم‌لاین حین شناسایی تغییر کرد — لطفاً دوباره شناسایی کنید"
                        )
                    ) }
                    return@launch
                }
                // Park the RANKED (possibly capped) analyses so
                // filterSelected indices match preview segment ids.
                silencePreviewAnalyses = ranked.map { it.toAnalysis() }

                val previewClips = ranked.map { r ->
                    com.aicreatorstudio.app.model.SilencePreviewClip(
                        clipId = r.clipId,
                        fileName = r.fileName,
                        clipDurationMs = r.clipDurationMs,
                        segments = r.candidates.mapIndexed { index, cand ->
                            com.aicreatorstudio.app.model.SilencePreviewSegment(
                                id = com.aicreatorstudio.app.ai.vad.SilenceCutter.segmentId(r.clipId, index),
                                clipId = r.clipId,
                                startMs = cand.startMs,
                                endMs = cand.endMs,
                                confidence = cand.confidence,
                                selected = cand.recommended,
                                score = cand.score,
                                reason = cand.reason
                            )
                        }
                    )
                }
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Preview(
                        clips = previewClips,
                        config = silence,
                        totalSegments = previewClips.sumOf { c -> c.segments.size },
                        totalSilenceMs = previewClips.sumOf { c -> c.silenceMs },
                        sourceKey = com.aicreatorstudio.app.ai.vad.SilenceCutter.previewSourceKey(clips),
                        mode = com.aicreatorstudio.app.model.SilencePreviewMode.SMART_JUMP_CUT
                    )
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Idle
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                        e.message ?: "خطا در پردازش"
                    )
                ) }
            }
        }
    }

    /**
     * PHASE 2 — flip one proposed cut. Preview-state only: no VAD,
     * no decode, no timeline mutation. Refuses stale previews (timeline
     * moved since analysis) instead of toggling against dead data.
     */
    fun toggleSilenceSegment(segmentId: String) {
        val preview = _uiState.value.autoSilenceCutState
        if (preview !is com.aicreatorstudio.app.model.AutoSilenceCutState.Preview) return
        if (isSilencePreviewStale(preview)) {
            invalidateSilencePreview()
            return
        }
        _uiState.update { state ->
            val cur = state.autoSilenceCutState
            if (cur !is com.aicreatorstudio.app.model.AutoSilenceCutState.Preview) return@update state
            state.copy(
                autoSilenceCutState = cur.copy(
                    clips = cur.clips.map { clip ->
                        clip.copy(
                            segments = clip.segments.map { seg ->
                                if (seg.id == segmentId) seg.copy(selected = !seg.selected) else seg
                            }
                        )
                    }
                )
            )
        }
    }

    /**
     * PHASE 2 — select/deselect every proposed cut at once. Single
     * state update; no media work.
     */
    fun setAllSilenceSelected(selected: Boolean) {
        val preview = _uiState.value.autoSilenceCutState
        if (preview !is com.aicreatorstudio.app.model.AutoSilenceCutState.Preview) return
        if (isSilencePreviewStale(preview)) {
            invalidateSilencePreview()
            return
        }
        _uiState.update { state ->
            val cur = state.autoSilenceCutState
            if (cur !is com.aicreatorstudio.app.model.AutoSilenceCutState.Preview) return@update state
            state.copy(
                autoSilenceCutState = cur.copy(
                    clips = cur.clips.map { clip ->
                        clip.copy(segments = clip.segments.map { it.copy(selected = selected) })
                    }
                )
            )
        }
    }

    /** PHASE 2 — true when the timeline moved since this preview was taken. */
    private fun isSilencePreviewStale(
        preview: com.aicreatorstudio.app.model.AutoSilenceCutState.Preview
    ): Boolean {
        val clips = _uiState.value.project?.clips ?: return true
        return com.aicreatorstudio.app.ai.vad.SilenceCutter.previewSourceKey(clips) != preview.sourceKey
    }

    /** PHASE 2 — drop a stale preview with guidance instead of applying it. */
    private fun invalidateSilencePreview() {
        silencePreviewAnalyses = emptyList()
        _uiState.update { it.copy(
            autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Error(
                "تایم‌لاین تغییر کرد — لطفاً دوباره شناسایی کنید"
            )
        ) }
    }

    /**
     * PHASE 2 — apply ONLY the selected cuts through the existing
     * timeline mechanism (trim-based clip replacement, source files
     * never modified):
     * 1. reject stale previews and empty selections (no empty history),
     * 2. filter analyses to selected segments (pure, no re-inference),
     * 3. push exactly ONE undo entry,
     * 4. persist, exit preview, clear dead video selection, clamp playhead.
     */
    fun applySilencePreview() {
        val preview = _uiState.value.autoSilenceCutState
        if (preview !is com.aicreatorstudio.app.model.AutoSilenceCutState.Preview) return
        val project = _uiState.value.project ?: return
        if (silencePreviewAnalyses.isEmpty()) return
        if (isSilencePreviewStale(preview)) {
            invalidateSilencePreview()
            return
        }
        val selectedIds = preview.selectedSegments.map { it.id }.toSet()
        // Requirement 8: nothing selected → Apply stays disabled in UI;
        // double-guard here so no empty history entry is ever created.
        if (selectedIds.isEmpty()) return

        val cutter = com.aicreatorstudio.app.ai.vad.SilenceCutter(appContext)
        val filtered = com.aicreatorstudio.app.ai.vad.SilenceCutter.filterSelected(
            silencePreviewAnalyses,
            selectedIds
        )
        val result = cutter.buildCutClips(project.clips, filtered)
        cutter.release()

        // New clips carry fresh UUIDs, so a kept video selection id would
        // dangle — clear it (audio-only clips keep their ids and stay
        // selected). Undo restores the pre-apply selection via snapshot.
        val keepAudioSelection = _uiState.value.selectedAudioClipId
        val newTotalMs = result.newClips.sumOf { it.effectiveDurationMs }

        historyDelegate.push()
        silencePreviewAnalyses = emptyList()
        _uiState.update { state -> state.copy(
            project = state.project?.copy(clips = result.newClips),
            selectedClipId = null,
            selectedAudioClipId = keepAudioSelection,
            playbackPositionMs = state.playbackPositionMs.coerceAtMost(newTotalMs.coerceAtLeast(0L)),
            autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Completed(
                silencesRemoved = result.totalSilencesRemoved,
                silenceMsRemoved = result.totalSilenceMsRemoved
            )
        ) }
        persistClips()
    }

    /** Discard the preview without touching the timeline. */
    fun discardSilencePreview() {
        silencePreviewAnalyses = emptyList()
        _uiState.update { it.copy(
            autoSilenceCutState = com.aicreatorstudio.app.model.AutoSilenceCutState.Idle
        ) }
    }

    // ---- PHASE 4 — Local Voice Enhancement (preview → apply) ----

    private var voiceEnhanceJob: kotlinx.coroutines.Job? = null

    /**
     * PHASE 4 — run decode → normalize → denoise → cache on the
     * selected VIDEO clip and park the result in Preview. The timeline
     * is NOT touched; the WAV sits in cache until Apply.
     *
     * Restricted to video clips: the export swap only replaces audio
     * on single video-clip exports, so enhancing a standalone audio
     * clip could never reach export — fail loudly instead of
     * producing a placebo file.
     */
    fun previewVoiceEnhancement(
        preset: com.aicreatorstudio.app.ai.voice.VoiceEnhancementPreset =
            com.aicreatorstudio.app.ai.voice.VoiceEnhancementPreset.Default
    ) {
        voiceEnhanceJob?.cancel()

        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip
        if (clip == null || clip.isAudioOnly) {
            _uiState.update { it.copy(
                voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Error(
                    "برای بهبود صدا، یک کلیپ ویدیویی را انتخاب کنید"
                )
            ) }
            return
        }
        if (clip.effectiveDurationMs > com.aicreatorstudio.app.ai.voice.MAX_ENHANCE_DURATION_MS) {
            _uiState.update { it.copy(
                voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Error(
                    "کلیپ طولانی‌تر از ۱۵ دقیقه است — بخش کوتاه‌تری انتخاب کنید"
                )
            ) }
            return
        }
        val engine = aiToolRegistry.byId("voiceclean") ?: run {
            _uiState.update { it.copy(
                voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Error(
                    "موتور بهبود صدا در دسترس نیست"
                )
            ) }
            return
        }
        val sourceKey = com.aicreatorstudio.app.ai.voice.voiceSourceKey(clip)
        val clipId = clip.id
        val videoUri = android.net.Uri.parse(clip.uri)

        voiceEnhanceJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(
                voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Processing(
                    progress = 0f,
                    message = "در حال تحلیل صدا..."
                )
            ) }
            try {
                val result = engine.process(
                    clipId = clipId,
                    videoUri = videoUri,
                    options = com.aicreatorstudio.app.ai.AiToolOptions(
                        strength = preset.strength,
                        commitToClip = false,
                        normalizeOutput = true
                    ),
                    onProgress = { progress ->
                        _uiState.update { state ->
                            val current = state.voiceEnhanceState
                            if (current is com.aicreatorstudio.app.model.VoiceEnhanceState.Processing) {
                                val stage = when {
                                    progress < 0.3f -> "در حال استخراج صدا..."
                                    progress < 0.78f -> "در حال حذف نویز..."
                                    else -> "در حال آماده‌سازی پیش‌نمایش..."
                                }
                                state.copy(
                                    voiceEnhanceState = current.copy(
                                        progress = progress,
                                        message = stage
                                    )
                                )
                            } else state
                        }
                    }
                ).getOrThrow()
                // Timeline may have moved during processing — never park
                // a preview against it.
                val now = _uiState.value.project?.clips?.firstOrNull { it.id == clipId }
                if (now == null ||
                    com.aicreatorstudio.app.ai.voice.voiceSourceKey(now) != sourceKey
                ) {
                    deleteVoicePreviewCache(clipId, onlyWhenDisabled = true)
                    _uiState.update { it.copy(
                        voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Error(
                            "تایم‌لاین حین پردازش تغییر کرد — لطفاً دوباره تلاش کنید"
                        )
                    ) }
                    return@launch
                }
                val stats = (result.voiceStats?.copy(preset = preset))
                    ?: throw IllegalStateException("نتیجه پردازش ناقص است")
                _uiState.update { it.copy(
                    voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Preview(
                        clipId = clipId,
                        fileName = clip.fileName,
                        preset = preset,
                        stats = stats,
                        pendingSettings = com.aicreatorstudio.app.model.VoiceCleanSettings(
                            enabled = false,
                            strength = result.voiceClean?.strength ?: preset.strength,
                            algorithm = result.voiceClean?.algorithm
                                ?: com.aicreatorstudio.app.ai.engines.RnnoiseVoiceCleanEngine.ALGORITHM_RNNOISE_V1
                        ),
                        sourceKey = sourceKey
                    )
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                deleteVoicePreviewCache(clipId, onlyWhenDisabled = true)
                _uiState.update { it.copy(
                    voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Idle
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Error(
                        e.message ?: "خطا در پردازش صدا"
                    )
                ) }
            }
        }
    }

    /**
     * PHASE 4 — commit the previewed enhancement: exactly ONE history
     * entry, persist clips + AI-tools blob, durations unchanged (gain
     * processing never touches timing) so no playhead/selection fixup.
     */
    fun applyVoiceEnhancement() {
        val preview = _uiState.value.voiceEnhanceState
        if (preview !is com.aicreatorstudio.app.model.VoiceEnhanceState.Preview) return
        val project = _uiState.value.project ?: return
        val clip = project.clips.firstOrNull { it.id == preview.clipId } ?: return
        if (com.aicreatorstudio.app.ai.voice.voiceSourceKey(clip) != preview.sourceKey) {
            _uiState.update { it.copy(
                voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Error(
                    "تایم‌لاین تغییر کرد — لطفاً دوباره پردازش کنید"
                )
            ) }
            return
        }
        // The WAV must exist: it is written by the preview run above.
        val wav = java.io.File(appContext.cacheDir, "voiceclean/${preview.clipId}.wav")
        if (!wav.exists() || wav.length() <= 44) {
            _uiState.update { it.copy(
                voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Error(
                    "فایل پیش‌نمایش یافت نشد — لطفاً دوباره پردازش کنید"
                )
            ) }
            return
        }

        val committed = preview.pendingSettings.copy(enabled = true)
        val updatedClips = project.clips.map { c ->
            if (c.id == preview.clipId) c.copy(voiceClean = committed) else c
        }
        historyDelegate.push()
        _uiState.update { it.copy(
            project = it.project?.copy(clips = updatedClips),
            voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Completed(
                fileName = preview.fileName,
                noiseReductionDb = preview.stats.noiseReductionDb
            )
        ) }
        persistClips()
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    /** Discard the preview; timeline untouched. */
    fun discardVoiceEnhancement() {
        val preview = _uiState.value.voiceEnhanceState
        if (preview is com.aicreatorstudio.app.model.VoiceEnhanceState.Preview) {
            deleteVoicePreviewCache(preview.clipId, onlyWhenDisabled = true)
        }
        voiceEnhanceJob?.cancel()
        voiceEnhanceJob = null
        _uiState.update { it.copy(
            voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Idle
        ) }
    }

    /**
     * Remove a committed enhancement: ONE history entry, WAV deleted,
     * settings back to Identity, persisted. (Fills the reset gap the
     * legacy voice-clean tool leaves: its reset never cleared the clip
     * or the cache file.)
     */
    fun clearVoiceEnhancement() {
        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip ?: return
        if (clip.voiceClean == com.aicreatorstudio.app.model.VoiceCleanSettings.Identity) return
        val updatedClips = project.clips.map { c ->
            if (c.id == clip.id) {
                c.copy(voiceClean = com.aicreatorstudio.app.model.VoiceCleanSettings.Identity)
            } else c
        }
        historyDelegate.push()
        runCatching {
            java.io.File(appContext.cacheDir, "voiceclean/${clip.id}.wav").delete()
        }
        _uiState.update { it.copy(project = it.project?.copy(clips = updatedClips)) }
        persistClips()
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    // ---- PHASE 5 — Local Smart Reframe (analyze → preview → apply) ----

    private var reframeJob: kotlinx.coroutines.Job? = null

    /**
     * PHASE 5 — run the existing autoframe engine (no commit), simplify
     * the key path and park a Preview. Timeline untouched.
     *
     * Restricted to video clips with known dimensions (crop math needs
     * a source frame) and 15 min max (retriever sampling cost).
     */
    fun analyzeReframe(targetW: Float, targetH: Float, smoothing: Float = 0.5f) {
        reframeJob?.cancel()

        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip
        if (clip == null || clip.isAudioOnly || clip.width <= 0 || clip.height <= 0) {
            _uiState.update { it.copy(
                reframeState = com.aicreatorstudio.app.model.ReframeState.Error(
                    "برای ری‌فریم هوشمند، یک کلیپ ویدیویی را انتخاب کنید"
                )
            ) }
            return
        }
        if (clip.effectiveDurationMs > com.aicreatorstudio.app.ai.reframe.ReframeConfigDefaults.MAX_ANALYZE_DURATION_MS) {
            _uiState.update { it.copy(
                reframeState = com.aicreatorstudio.app.model.ReframeState.Error(
                    "کلیپ طولانی‌تر از ۱۵ دقیقه است — بخش کوتاه‌تری انتخاب کنید"
                )
            ) }
            return
        }
        val engine = aiToolRegistry.byId("autoframe") ?: run {
            _uiState.update { it.copy(
                reframeState = com.aicreatorstudio.app.model.ReframeState.Error(
                    "موتور ری‌فریم در دسترس نیست"
                )
            ) }
            return
        }
        val config = com.aicreatorstudio.app.ai.reframe.ReframeConfig(
            targetAspectWidth = targetW,
            targetAspectHeight = targetH,
            smoothing = smoothing.coerceIn(0f, 1f)
        )
        val sourceKey = com.aicreatorstudio.app.ai.reframe.reframeSourceKey(clip, targetW, targetH)
        val clipId = clip.id
        val videoUri = android.net.Uri.parse(clip.uri)
        val targetLabel = reframeAspectLabel(targetW, targetH)

        reframeJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(
                reframeState = com.aicreatorstudio.app.model.ReframeState.Processing(
                    progress = 0f,
                    message = "در حال شناسایی سوژه..."
                )
            ) }
            try {
                val analyzer = com.aicreatorstudio.app.ai.reframe.LocalSmartReframeAnalyzer(engine)
                val analysis = analyzer.analyze(
                    clip = clip,
                    sourceUri = videoUri,
                    config = config,
                    onProgress = { progress ->
                        _uiState.update { state ->
                            val current = state.reframeState
                            if (current is com.aicreatorstudio.app.model.ReframeState.Processing) {
                                val stage = when {
                                    progress < 0.7f -> "در حال شناسایی سوژه..."
                                    progress < 0.92f -> "در حال دنبال‌کردن حرکت..."
                                    else -> "در حال ساخت مسیر کراپ..."
                                }
                                state.copy(
                                    reframeState = current.copy(progress = progress, message = stage)
                                )
                            } else state
                        }
                    },
                    checkCancelled = {
                        val job = reframeJob
                        if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException()
                    }
                )
                // Timeline/geometry may have moved during analysis.
                val now = _uiState.value.project?.clips?.firstOrNull { it.id == clipId }
                if (now == null ||
                    com.aicreatorstudio.app.ai.reframe.reframeSourceKey(now, targetW, targetH) != sourceKey ||
                    !com.aicreatorstudio.app.ai.reframe.validateKeys(analysis.keys, analysis.clipDurationMs)
                ) {
                    _uiState.update { it.copy(
                        reframeState = com.aicreatorstudio.app.model.ReframeState.Error(
                            "تایم‌لاین حین تحلیل تغییر کرد — لطفاً دوباره تلاش کنید"
                        )
                    ) }
                    return@launch
                }
                if (analysis.keys.isEmpty()) {
                    _uiState.update { it.copy(
                        reframeState = com.aicreatorstudio.app.model.ReframeState.Error(
                            "مسیر کراپی ساخته نشد — ویدیوی دیگری امتحان کنید"
                        )
                    ) }
                    return@launch
                }
                _uiState.update { it.copy(
                    reframeState = com.aicreatorstudio.app.model.ReframeState.Preview(
                        clipId = clipId,
                        fileName = clip.fileName,
                        targetLabel = targetLabel,
                        targetAspectWidth = targetW,
                        targetAspectHeight = targetH,
                        keys = analysis.keys,
                        stats = analysis.stats,
                        pendingSettings = analysis.engineSettings.copy(enabled = false),
                        sourceKey = sourceKey
                    )
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(
                    reframeState = com.aicreatorstudio.app.model.ReframeState.Idle
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    reframeState = com.aicreatorstudio.app.model.ReframeState.Error(
                        e.message ?: "خطا در تحلیل ری‌فریم"
                    )
                ) }
            }
        }
    }

    /**
     * PHASE 5 — commit the previewed crop path through the existing
     * autoFrame/cropKeyframes mechanism (the export pre-render already
     * consumes it): exactly ONE history entry, persist clips + blob.
     * Audio, subtitles, effects and transitions are untouched (same
     * clip objects, only reframe fields change).
     */
    fun applyReframe() {
        val preview = _uiState.value.reframeState
        if (preview !is com.aicreatorstudio.app.model.ReframeState.Preview) return
        val project = _uiState.value.project ?: return
        val clip = project.clips.firstOrNull { it.id == preview.clipId } ?: return
        if (com.aicreatorstudio.app.ai.reframe.reframeSourceKey(
                clip, preview.targetAspectWidth, preview.targetAspectHeight
            ) != preview.sourceKey ||
            !com.aicreatorstudio.app.ai.reframe.validateKeys(preview.keys, clip.effectiveDurationMs)
        ) {
            _uiState.update { it.copy(
                reframeState = com.aicreatorstudio.app.model.ReframeState.Error(
                    "تایم‌لاین تغییر کرد — لطفاً دوباره تحلیل کنید"
                )
            ) }
            return
        }
        val committed = preview.pendingSettings.copy(enabled = true)
        val updatedClips = project.clips.map { c ->
            if (c.id == preview.clipId) {
                c.copy(
                    // Enabling reframe clears motion-track (shared
                    // cropKeyframes list — same rule as onAiToolSuccess).
                    motionTrack = com.aicreatorstudio.app.model.MotionTrackSettings.Identity,
                    autoFrame = committed,
                    cropKeyframes = preview.keys
                )
            } else c
        }
        historyDelegate.push()
        _uiState.update { it.copy(
            project = it.project?.copy(clips = updatedClips),
            reframeState = com.aicreatorstudio.app.model.ReframeState.Completed(
                fileName = preview.fileName,
                keyframes = preview.keys.size
            )
        ) }
        persistClips()
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    /** Discard the preview; timeline untouched. */
    fun discardReframe() {
        reframeJob?.cancel()
        reframeJob = null
        _uiState.update { it.copy(
            reframeState = com.aicreatorstudio.app.model.ReframeState.Idle
        ) }
    }

    fun cancelReframe() {
        reframeJob?.cancel()
        reframeJob = null
        _uiState.update { it.copy(
            reframeState = com.aicreatorstudio.app.model.ReframeState.Idle
        ) }
    }

    /**
     * Remove a committed reframe: ONE history entry, Identity + empty
     * path, persisted. Export falls back to the original framing.
     */
    fun clearReframe() {
        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip ?: return
        if (!clip.autoFrame.enabled && clip.cropKeyframes.isEmpty()) return
        val updatedClips = project.clips.map { c ->
            if (c.id == clip.id) {
                c.copy(
                    autoFrame = com.aicreatorstudio.app.model.AutoFrameSettings.Identity,
                    cropKeyframes = emptyList()
                )
            } else c
        }
        historyDelegate.push()
        _uiState.update { it.copy(project = it.project?.copy(clips = updatedClips)) }
        persistClips()
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    private fun reframeAspectLabel(w: Float, h: Float): String = when {
        w <= 0f || h <= 0f -> "آزاد"
        kotlin.math.abs(w / h - 9f / 16f) < 0.02f -> "9:16"
        kotlin.math.abs(w / h - 1f) < 0.02f -> "1:1"
        kotlin.math.abs(w / h - 16f / 9f) < 0.02f -> "16:9"
        kotlin.math.abs(w / h - 4f / 5f) < 0.02f -> "4:5"
        else -> "سفارشی"
    }

    fun cancelVoiceEnhance() {
        val preview = _uiState.value.voiceEnhanceState
        if (preview is com.aicreatorstudio.app.model.VoiceEnhanceState.Preview) {
            deleteVoicePreviewCache(preview.clipId, onlyWhenDisabled = true)
        }
        voiceEnhanceJob?.cancel()
        voiceEnhanceJob = null
        _uiState.update { it.copy(
            voiceEnhanceState = com.aicreatorstudio.app.model.VoiceEnhanceState.Idle
        ) }
    }

    /**
     * Delete a preview WAV — but never a committed one. A clip whose
     * voiceClean is enabled owns its cache file (export reads it).
     */
    private fun deleteVoicePreviewCache(clipId: String, onlyWhenDisabled: Boolean) {
        val clip = _uiState.value.project?.clips?.firstOrNull { it.id == clipId }
        if (onlyWhenDisabled && clip?.voiceClean?.enabled == true) return
        runCatching {
            java.io.File(appContext.cacheDir, "voiceclean/$clipId.wav").delete()
        }
    }

    // ---- PHASE 7 — Smart Highlight Detection (analyze → select → store) ----

    private var highlightJob: kotlinx.coroutines.Job? = null

    /**
     * PHASE 7 — analyze the selected video clip with existing local
     * signals (VAD, energy, persisted beats, subtitle timings, sparse
     * frame-diff) and park ranked highlight candidates. Timeline
     * untouched. Subtitle data is READ, never generated or modified.
     */
    fun analyzeHighlights(
        config: com.aicreatorstudio.app.ai.highlight.HighlightConfig =
            com.aicreatorstudio.app.ai.highlight.HighlightConfig()
    ) {
        highlightJob?.cancel()

        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip
        if (clip == null || clip.isAudioOnly) {
            _uiState.update { it.copy(
                highlightState = com.aicreatorstudio.app.model.HighlightState.Error(
                    "برای هایلایت هوشمند، یک کلیپ ویدیویی را انتخاب کنید"
                )
            ) }
            return
        }
        if (clip.effectiveDurationMs >
            com.aicreatorstudio.app.ai.highlight.HighlightEngine.MAX_ANALYZE_DURATION_MS
        ) {
            _uiState.update { it.copy(
                highlightState = com.aicreatorstudio.app.model.HighlightState.Error(
                    "کلیپ طولانی‌تر از ۱۵ دقیقه است — بخش کوتاه‌تری انتخاب کنید"
                )
            ) }
            return
        }
        val sourceKey = highlightSourceKey(project, clip, config)
        val clipId = clip.id
        val videoUri = android.net.Uri.parse(clip.uri)

        highlightJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(
                highlightState = com.aicreatorstudio.app.model.HighlightState.Processing(
                    progress = 0f,
                    message = "در حال تحلیل صدا..."
                )
            ) }
            try {
                // Snapshot subtitle words (timeline ms → clip-local) and
                // beat marks once; the engine only sees plain data.
                val live = _uiState.value.project ?: throw IllegalStateException("پروژه پیدا نشد")
                val words = highlightSubtitleWords(live, clip)
                val beats = highlightBeatMarks(live, clip)
                val engine = com.aicreatorstudio.app.ai.highlight.HighlightEngine(
                    appContext,
                    audioExtractor
                )
                val analysis = engine.analyze(
                    clipId = clipId,
                    fileName = clip.fileName,
                    clipUri = videoUri,
                    trimStartMs = clip.trimStartMs,
                    effectiveDurationMs = clip.effectiveDurationMs,
                    config = config,
                    subtitleWords = words,
                    beatMarks = beats,
                    onProgress = { progress ->
                        _uiState.update { state ->
                            val cur = state.highlightState
                            if (cur is com.aicreatorstudio.app.model.HighlightState.Processing) {
                                val stage = when {
                                    progress < 0.38f -> "در حال تحلیل صدا..."
                                    progress < 0.65f -> "در حال شناسایی گفتار..."
                                    progress < 0.92f -> "در حال بررسی تصویر..."
                                    else -> "در حال رتبه‌بندی هایلایت‌ها..."
                                }
                                state.copy(
                                    highlightState = cur.copy(progress = progress, message = stage)
                                )
                            } else state
                        }
                    },
                    checkCancelled = {
                        val job = highlightJob
                        if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException()
                    }
                )
                // Timeline/subtitles/beats may have moved mid-analysis.
                val now = _uiState.value.project
                val nowClip = now?.clips?.firstOrNull { it.id == clipId }
                if (now == null || nowClip == null ||
                    highlightSourceKey(now, nowClip, config) != sourceKey
                ) {
                    _uiState.update { it.copy(
                        highlightState = com.aicreatorstudio.app.model.HighlightState.Error(
                            "تایم‌لاین حین تحلیل تغییر کرد — لطفاً دوباره تلاش کنید"
                        )
                    ) }
                    return@launch
                }
                if (analysis.candidates.isEmpty()) {
                    _uiState.update { it.copy(
                        highlightState = com.aicreatorstudio.app.model.HighlightState.Error(
                            "هایلایت قابل‌توجهی پیدا نشد"
                        )
                    ) }
                    return@launch
                }
                _uiState.update { it.copy(
                    highlightState = com.aicreatorstudio.app.model.HighlightState.Preview(
                        clipId = clipId,
                        fileName = clip.fileName,
                        candidates = analysis.candidates,
                        signalsAvailable = analysis.signalsAvailable,
                        sourceKey = sourceKey
                    )
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(
                    highlightState = com.aicreatorstudio.app.model.HighlightState.Idle
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    highlightState = com.aicreatorstudio.app.model.HighlightState.Error(
                        e.message ?: "خطا در تحلیل هایلایت"
                    )
                ) }
            }
        }
    }

    /**
     * Subtitle word timings intersecting the clip, mapped clip-local.
     * Read-only.
     *
     * 2026-10-20 — TIMELINE TIME → SOURCE TIME.
     *
     * Overlay word times live on the project TIMELINE, so
     * `wordTime - clip.timelineStartMs` is an offset into the clip's
     * TIMELINE span. The highlight engine's window grid is the
     * clip's SOURCE span (`effectiveDurationMs` is the source length —
     * the timeline length is `rampedDurationMs`).
     *
     * For a clip with `speed != 1` those two spans have different
     * lengths, so the word times were compared against the wrong
     * part of the clip: at 2x speed, a word spoken at source second 1
     * was counted at source second 0.5. The offset is now scaled by
     * the clip's speed, which converts timeline time to source time.
     *
     * (A speed RAMP is not linear, so the conversion is exact only
     * for a constant speed — the overwhelmingly common case. The
     * engine's own analysis is on the source audio either way, so a
     * ramped clip is now merely approximate rather than simply
     * wrong.)
     */
    private fun highlightSubtitleWords(
        project: com.aicreatorstudio.app.model.VideoProject,
        clip: com.aicreatorstudio.app.model.VideoClip
    ): List<com.aicreatorstudio.app.ai.highlight.WordTiming> {
        val base = clip.timelineStartMs.coerceAtLeast(0L)
        val speed = clip.speed.coerceAtLeast(0.01f)
        val sourceSpan = clip.effectiveDurationMs
        // The clip's span on the timeline is the SOURCE span scaled by
        // the speed — the overlay filter must use that, not the source
        // length, or a sped-up clip would pull in words from the next
        // clip.
        val end = base + (sourceSpan / speed).toLong()
        val out = mutableListOf<com.aicreatorstudio.app.ai.highlight.WordTiming>()
        for (overlay in project.textOverlays) {
            if (overlay.endTimeMs <= base || overlay.startTimeMs >= end) continue
            for (w in overlay.words) {
                val s = timelineToSourceOffset(w.startTimeMs - base, speed)
                    .coerceIn(0L, sourceSpan)
                val e = timelineToSourceOffset(w.endTimeMs - base, speed)
                    .coerceIn(0L, sourceSpan)
                if (e > s) out.add(com.aicreatorstudio.app.ai.highlight.WordTiming(s, e))
            }
            // Word-less overlays still mark speech presence.
            if (overlay.words.isEmpty()) {
                val s = timelineToSourceOffset(overlay.startTimeMs - base, speed)
                    .coerceIn(0L, sourceSpan)
                val e = timelineToSourceOffset(overlay.endTimeMs - base, speed)
                    .coerceIn(0L, sourceSpan)
                if (e > s) out.add(com.aicreatorstudio.app.ai.highlight.WordTiming(s, e))
            }
        }
        return out
    }

    /**
     * Convert an offset into a clip's TIMELINE span into the matching
     * offset into its SOURCE span.
     *
     * The timeline plays the source `speed` times faster, so a
     * timeline offset of `d` covers `d * speed` of source time.
     */
    private fun timelineToSourceOffset(timelineOffsetMs: Long, speed: Float): Long =
        (timelineOffsetMs.toDouble() * speed.toDouble()).toLong()

    /**
     * Persisted beat marks intersecting the clip, mapped clip-local.
     * Read-only.
     *
     * 2026-10-20 — same timeline→source conversion as
     * [highlightSubtitleWords]; see the note there.
     */
    private fun highlightBeatMarks(
        project: com.aicreatorstudio.app.model.VideoProject,
        clip: com.aicreatorstudio.app.model.VideoClip
    ): List<com.aicreatorstudio.app.ai.highlight.BeatMark> {
        val base = clip.timelineStartMs.coerceAtLeast(0L)
        val speed = clip.speed.coerceAtLeast(0.01f)
        val sourceSpan = clip.effectiveDurationMs
        val end = base + (sourceSpan / speed).toLong()
        val out = mutableListOf<com.aicreatorstudio.app.ai.highlight.BeatMark>()
        for (audio in project.audioClips) {
            val markers = com.aicreatorstudio.app.util.BeatDetector.toTimelineMarkers(
                audio.beatData, audio.startTimeMs, audio.clipOffsetMs, audio.durationMs
            )
            for (m in markers) {
                if (m.timestampMs >= base && m.timestampMs < end) {
                    out.add(
                        com.aicreatorstudio.app.ai.highlight.BeatMark(
                            timelineToSourceOffset(m.timestampMs - base, speed)
                                .coerceIn(0L, sourceSpan),
                            m.intensity
                        )
                    )
                }
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun highlightSourceKey(
        project: com.aicreatorstudio.app.model.VideoProject,
        clip: com.aicreatorstudio.app.model.VideoClip,
        config: com.aicreatorstudio.app.ai.highlight.HighlightConfig
    ): String {
        val overlayFp = "${project.textOverlays.size}|" +
            "${project.textOverlays.sumOf { it.words.size }}"
        val beatFp = project.audioClips.joinToString(";") {
            "${it.id}:${it.beatData?.markers?.size ?: -1}:${it.beatData?.bpm}"
        }
        return "${clip.id}|${clip.uri}|${clip.trimStartMs}|${clip.trimEndMs}|" +
            "${clip.durationMs}|${clip.timelineStartMs}|$overlayFp|$beatFp|" +
            "${config.versionKey()}"
    }

    private fun isHighlightStale(
        preview: com.aicreatorstudio.app.model.HighlightState.Preview,
        config: com.aicreatorstudio.app.ai.highlight.HighlightConfig =
            com.aicreatorstudio.app.ai.highlight.HighlightConfig()
    ): Boolean {
        val project = _uiState.value.project ?: return true
        val clip = project.clips.firstOrNull { it.id == preview.clipId } ?: return true
        return highlightSourceKey(project, clip, config) != preview.sourceKey
    }

    private fun invalidateHighlights() {
        _uiState.update { it.copy(
            highlightState = com.aicreatorstudio.app.model.HighlightState.Error(
                "تایم‌لاین تغییر کرد — لطفاً دوباره تحلیل کنید"
            )
        ) }
    }

    /** PHASE 7 — flip one candidate (preview state only). */
    fun toggleHighlight(candidateId: String) {
        val preview = _uiState.value.highlightState
        if (preview !is com.aicreatorstudio.app.model.HighlightState.Preview) return
        if (isHighlightStale(preview)) {
            invalidateHighlights()
            return
        }
        _uiState.update { state ->
            val cur = state.highlightState
            if (cur !is com.aicreatorstudio.app.model.HighlightState.Preview) return@update state
            state.copy(
                highlightState = cur.copy(
                    candidates = cur.candidates.map { c ->
                        if (c.id == candidateId) c.copy(selected = !c.selected) else c
                    }
                )
            )
        }
    }

    /** PHASE 7 — select/deselect all candidates at once. */
    fun setAllHighlightsSelected(selected: Boolean) {
        val preview = _uiState.value.highlightState
        if (preview !is com.aicreatorstudio.app.model.HighlightState.Preview) return
        if (isHighlightStale(preview)) {
            invalidateHighlights()
            return
        }
        _uiState.update { state ->
            val cur = state.highlightState
            if (cur !is com.aicreatorstudio.app.model.HighlightState.Preview) return@update state
            state.copy(
                highlightState = cur.copy(
                    candidates = cur.candidates.map { it.copy(selected = selected) }
                )
            )
        }
    }

    /**
     * PHASE 7 — store ONLY the selected ranges on the clip as
     * non-destructive markers (future AutoEdit input): exactly ONE
     * history entry, persist clips + blob. The timeline is never
     * trimmed — the original edit is fully preserved.
     */
    fun applyHighlights() {
        val preview = _uiState.value.highlightState
        if (preview !is com.aicreatorstudio.app.model.HighlightState.Preview) return
        val project = _uiState.value.project ?: return
        val clip = project.clips.firstOrNull { it.id == preview.clipId } ?: return
        if (isHighlightStale(preview)) {
            invalidateHighlights()
            return
        }
        val selected = preview.selectedCandidates
        if (selected.isEmpty()) return // Apply disabled with empty selection; double-guard.
        val ranges = selected
            .sortedBy { it.startMs }
            .filter { it.endMs > it.startMs && it.startMs >= 0 }
            .map {
                com.aicreatorstudio.app.model.HighlightRange(
                    startMs = it.startMs,
                    endMs = it.endMs,
                    score = it.score,
                    reason = it.reason.name
                )
            }
        if (ranges.isEmpty()) return
        val updatedClips = project.clips.map { c ->
            if (c.id == preview.clipId) c.copy(highlightRanges = ranges) else c
        }
        historyDelegate.push()
        _uiState.update { it.copy(
            project = it.project?.copy(clips = updatedClips),
            highlightState = com.aicreatorstudio.app.model.HighlightState.Completed(
                fileName = preview.fileName,
                highlightsStored = ranges.size
            )
        ) }
        persistClips()
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    /** Remove committed ranges: ONE history entry, persisted. */
    fun clearHighlights() {
        val project = _uiState.value.project ?: return
        val clip = _uiState.value.selectedClip ?: return
        if (clip.highlightRanges.isEmpty()) return
        val updatedClips = project.clips.map { c ->
            if (c.id == clip.id) c.copy(highlightRanges = emptyList()) else c
        }
        historyDelegate.push()
        _uiState.update { it.copy(project = it.project?.copy(clips = updatedClips)) }
        persistClips()
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    /**
     * PHASE 7 — tap-to-preview: move the playhead to the highlight
     * start (timeline-global) without modifying anything. Reuses the
     * existing precise-seek trigger consumed by the player.
     */
    fun seekHighlight(candidateId: String) {
        val preview = _uiState.value.highlightState
        if (preview !is com.aicreatorstudio.app.model.HighlightState.Preview) return
        val clip = _uiState.value.project?.clips?.firstOrNull { it.id == preview.clipId }
            ?: return
        val cand = preview.candidates.firstOrNull { it.id == candidateId } ?: return
        val timelineMs = clip.timelineStartMs.coerceAtLeast(0L) + cand.startMs
        _uiState.update { it.copy(
            playbackPositionMs = timelineMs,
            pendingSeekMs = timelineMs
        ) }
    }

    /** Discard suggestions; timeline untouched. */
    fun discardHighlights() {
        highlightJob?.cancel()
        highlightJob = null
        _uiState.update { it.copy(
            highlightState = com.aicreatorstudio.app.model.HighlightState.Idle
        ) }
    }

    fun cancelHighlightAnalysis() {
        highlightJob?.cancel()
        highlightJob = null
        _uiState.update { it.copy(
            highlightState = com.aicreatorstudio.app.model.HighlightState.Idle
        ) }
    }

    // ---- PHASE 6 — Local Beat Sync (suggest → select → apply) ----

    private var beatSyncJob: kotlinx.coroutines.Job? = null

    /**
     * PHASE 6 — build ranked on-beat cut suggestions for the selected
     * video clip from the selected audio clip's analyzed beats. Parks
     * a Preview; the timeline is NOT touched.
     *
     * Music source is strictly the selected audio clip with existing
     * `beatData` (Beat panel analysis) — never a silent fallback to
     * unrelated audio.
     */
    fun analyzeBeatSync(mode: com.aicreatorstudio.app.util.BeatSyncMode = com.aicreatorstudio.app.util.BeatSyncMode.Default) {
        beatSyncJob?.cancel()

        val project = _uiState.value.project ?: return
        val video = _uiState.value.selectedClip
        if (video == null || video.isAudioOnly) {
            _uiState.update { it.copy(
                beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                    "برای سینک ضرب، یک کلیپ ویدیویی را انتخاب کنید"
                )
            ) }
            return
        }
        val audio = project.audioClips.firstOrNull { it.id == _uiState.value.selectedAudioClipId }
        if (audio == null) {
            _uiState.update { it.copy(
                beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                    "یک کلیپ صوتی/موزیک را از لاین صدا انتخاب کنید"
                )
            ) }
            return
        }
        val beatData = audio.beatData
        if (beatData == null || !beatData.hasBeats) {
            _uiState.update { it.copy(
                beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                    "ابتدا در پنل ضرباهنگ، «تشخیص ضرباهنگ» را اجرا کنید"
                )
            ) }
            return
        }

        val config = com.aicreatorstudio.app.util.BeatSyncConfig(mode = mode)
        val videoClipId = video.id
        val audioClipId = audio.id
        val sourceKey = com.aicreatorstudio.app.util.beatSyncSourceKey(video, audio, mode)

        beatSyncJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(
                beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Processing(
                    progress = 0f,
                    message = "در حال تطبیق کات با ضرب..."
                )
            ) }
            try {
                // Beats are already analyzed + persisted (Beat panel);
                // here we only map + rank — fast, cancellable, chunked.
                val liveAudio = _uiState.value.project?.audioClips
                    ?.firstOrNull { it.id == audioClipId }
                    ?: throw IllegalStateException("کلیپ صوتی پیدا نشد")
                val liveVideo = _uiState.value.project?.clips
                    ?.firstOrNull { it.id == videoClipId }
                    ?: throw IllegalStateException("کلیپ ویدیویی پیدا نشد")
                _uiState.update { state ->
                    val cur = state.beatSyncState
                    if (cur is com.aicreatorstudio.app.model.BeatSyncState.Processing) {
                        state.copy(beatSyncState = cur.copy(progress = 0.4f))
                    } else state
                }
                ensureActive()
                val markers = com.aicreatorstudio.app.util.BeatDetector.toTimelineMarkers(
                    liveAudio.beatData,
                    liveAudio.startTimeMs,
                    liveAudio.clipOffsetMs,
                    liveAudio.durationMs
                )
                ensureActive()
                val candidates = com.aicreatorstudio.app.util.BeatSyncPlanner.plan(
                    markers,
                    liveVideo,
                    config
                )
                ensureActive()
                // Timeline may have moved during planning.
                val nowVideo = _uiState.value.project?.clips?.firstOrNull { it.id == videoClipId }
                val nowAudio = _uiState.value.project?.audioClips?.firstOrNull { it.id == audioClipId }
                if (nowVideo == null || nowAudio == null ||
                    com.aicreatorstudio.app.util.beatSyncSourceKey(nowVideo, nowAudio, mode) != sourceKey
                ) {
                    _uiState.update { it.copy(
                        beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                            "تایم‌لاین حین تحلیل تغییر کرد — لطفاً دوباره تلاش کنید"
                        )
                    ) }
                    return@launch
                }
                if (candidates.isEmpty()) {
                    _uiState.update { it.copy(
                        beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                            "ضرب مناسبی برای برش در این کلیپ پیدا نشد"
                        )
                    ) }
                    return@launch
                }
                _uiState.update { it.copy(
                    beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Preview(
                        videoClipId = videoClipId,
                        videoFileName = liveVideo.fileName,
                        audioClipId = audioClipId,
                        audioFileName = liveAudio.fileName,
                        bpm = liveAudio.beatData?.bpm,
                        beatCount = markers.size,
                        mode = mode,
                        candidates = candidates,
                        sourceKey = sourceKey
                    )
                ) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(
                    beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Idle
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                        e.message ?: "خطا در تحلیل سینک ضرب"
                    )
                ) }
            }
        }
    }

    /** PHASE 6 — flip one suggested cut (preview state only). */
    fun toggleBeatCandidate(candidateId: String) {
        val preview = _uiState.value.beatSyncState
        if (preview !is com.aicreatorstudio.app.model.BeatSyncState.Preview) return
        if (isBeatSyncStale(preview)) {
            invalidateBeatSync()
            return
        }
        _uiState.update { state ->
            val cur = state.beatSyncState
            if (cur !is com.aicreatorstudio.app.model.BeatSyncState.Preview) return@update state
            state.copy(
                beatSyncState = cur.copy(
                    candidates = cur.candidates.map { c ->
                        if (c.id == candidateId) c.copy(selected = !c.selected) else c
                    }
                )
            )
        }
    }

    /** PHASE 6 — select/deselect all suggestions at once. */
    fun setAllBeatSelected(selected: Boolean) {
        val preview = _uiState.value.beatSyncState
        if (preview !is com.aicreatorstudio.app.model.BeatSyncState.Preview) return
        if (isBeatSyncStale(preview)) {
            invalidateBeatSync()
            return
        }
        _uiState.update { state ->
            val cur = state.beatSyncState
            if (cur !is com.aicreatorstudio.app.model.BeatSyncState.Preview) return@update state
            state.copy(
                beatSyncState = cur.copy(
                    candidates = cur.candidates.map { it.copy(selected = selected) }
                )
            )
        }
    }

    private fun isBeatSyncStale(
        preview: com.aicreatorstudio.app.model.BeatSyncState.Preview
    ): Boolean {
        val project = _uiState.value.project ?: return true
        val video = project.clips.firstOrNull { it.id == preview.videoClipId } ?: return true
        val audio = project.audioClips.firstOrNull { it.id == preview.audioClipId } ?: return true
        return com.aicreatorstudio.app.util.beatSyncSourceKey(video, audio, preview.mode) != preview.sourceKey
    }

    private fun invalidateBeatSync() {
        _uiState.update { it.copy(
            beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                "تایم‌لاین تغییر کرد — لطفاً دوباره تحلیل کنید"
            )
        ) }
    }

    /**
     * PHASE 6 — apply ONLY the selected on-beat cuts through the
     * existing split machinery: exactly ONE history entry (batch
     * split), persist, keep audio/subtitles/effects (same objects —
     * splits only duplicate transition poses per existing semantics,
     * never corrupt them; music bytes never touched).
     */
    fun applyBeatSync() {
        val preview = _uiState.value.beatSyncState
        if (preview !is com.aicreatorstudio.app.model.BeatSyncState.Preview) return
        val project = _uiState.value.project ?: return
        val video = project.clips.firstOrNull { it.id == preview.videoClipId } ?: return
        if (isBeatSyncStale(preview)) {
            invalidateBeatSync()
            return
        }
        val selected = preview.selectedCandidates.map { it.cutTimelineMs }
        if (selected.isEmpty()) return // Apply disabled with empty selection; double-guard.
        val clipStart = video.timelineStartMs.coerceAtLeast(0L)
        val clipEnd = clipStart + video.effectiveDurationMs
        val cuts = com.aicreatorstudio.app.util.BeatSyncPlanner.normalizeCuts(
            selected, clipStart, clipEnd
        )
        if (cuts.isEmpty()) {
            _uiState.update { it.copy(
                beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Error(
                    "نقطه برش معتبری باقی نماند — دوباره تحلیل کنید"
                )
            ) }
            return
        }
        val applied = splitClipMultiple(preview.videoClipId, cuts)
        _uiState.update { it.copy(
            beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Completed(
                fileName = preview.videoFileName,
                cutsApplied = applied
            )
        ) }
    }

    /** Discard suggestions; timeline untouched. */
    fun discardBeatSync() {
        beatSyncJob?.cancel()
        beatSyncJob = null
        _uiState.update { it.copy(
            beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Idle
        ) }
    }

    fun cancelBeatSync() {
        beatSyncJob?.cancel()
        beatSyncJob = null
        _uiState.update { it.copy(
            beatSyncState = com.aicreatorstudio.app.model.BeatSyncState.Idle
        ) }
    }

    // ---- Matting preview ----


    /**
     * Picked by the user from the system document picker. Stores the
     * chosen content URI on the selected clip's [MattingSettings] so
     * the export pipeline can find it later. The mode follows the
     * MIME type (image ? IMAGE, video ? VIDEO) so the export
     * composite reads the right source kind.
     */
    fun onBackgroundMediaPicked(uri: android.net.Uri) {
        val clip = _uiState.value.selectedClip ?: return
        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull() ?: ""
        val mode = when {
            mime.startsWith("video/") ->
                com.aicreatorstudio.app.model.MattingBackgroundMode.VIDEO
            mime.startsWith("image/") ->
                com.aicreatorstudio.app.model.MattingBackgroundMode.IMAGE
            uri.toString().contains("video", ignoreCase = true) ->
                com.aicreatorstudio.app.model.MattingBackgroundMode.VIDEO
            else -> com.aicreatorstudio.app.model.MattingBackgroundMode.IMAGE
        }
        val next = clip.matting.copy(
            enabled = true,
            replacementBackgroundUri = uri.toString(),
            backgroundMode = mode
        )
        persistMatting(clip.id, next)
    }

    /**
     * Background replacement mode picked in the Background Remove
     * panel (Original / Blur / Color / Image / Video). Persisted
     * with the clip so save/reopen + export honor it.
     */
    fun setMattingBackgroundMode(mode: com.aicreatorstudio.app.model.MattingBackgroundMode) {
        val clip = _uiState.value.selectedClip ?: return
        // Modes that need a picked URI keep the previous URI (the
        // export fails with a precise message when it is missing);
        // ORIGINAL/BLUR/COLOR clear nothing but ignore the URI.
        val next = clip.matting.copy(backgroundMode = mode)
        persistMatting(clip.id, next)
    }

    /**
     * 2026-10-25 — the three composition-time mask knobs
     * (expansion / feather / reverse) persist immediately, so the NEXT
     * export applies them without re-running the Background Remove
     * pass. They only take effect where a sidecar exists (`enabled`
     * clips); the panel states that.
     */
    fun setMattingExpansion(value: Float) {
        val clip = _uiState.value.selectedClip ?: return
        persistMatting(
            clip.id,
            clip.matting.copy(expansion = value.coerceIn(-1f, 1f))
        )
    }

    fun setMattingFeather(value: Float) {
        val clip = _uiState.value.selectedClip ?: return
        persistMatting(
            clip.id,
            clip.matting.copy(feather = value.coerceIn(0f, 1f))
        )
    }

    fun setMattingReverse(value: Boolean) {
        val clip = _uiState.value.selectedClip ?: return
        persistMatting(clip.id, clip.matting.copy(reverse = value))
    }

    private fun persistMatting(
        clipId: String,
        next: com.aicreatorstudio.app.model.MattingSettings
    ) {
        val project = _uiState.value.project ?: return
        val updatedClips = project.clips.map { c ->
            if (c.id != clipId) c else c.copy(matting = next)
        }
        _uiState.update { it.copy(project = project.copy(clips = updatedClips)) }
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    // ====================================================================
    // AI OCR — panel callbacks
    // ====================================================================

    /** Trigger the OCR language-pack download (fas + ara + eng). */
    fun ensureOcrModelReady() = ensureAiToolModelReady("ocr")

    /**
     * Commit an OCR result onto the selected clip: the (possibly
     * user-edited) text + the timestamp it was extracted from are
     * persisted on the clip's `ocrResult` field. This is the panel's
     * "Apply" button � a real project mutation, visible after
     * save/reopen.
     */
    fun applyOcrResult(text: String, timestampMs: Long, lineCount: Int, confidence: Float) {
        // 2026-10-20 — resolve the target clip from the STAGED
        // result's own clip id. The old code used the current
        // selection, so running OCR on clip A, selecting clip B and
        // pressing Apply committed the text to clip B.
        val staged = _uiState.value.ocrResult
        val selected = _uiState.value.selectedClip
        val targetId = staged?.clipId?.takeIf { it.isNotEmpty() } ?: selected?.id
        if (targetId == null) return
        val frame = staged?.frames?.firstOrNull { it.timestampMs == timestampMs }
        val committed = com.aicreatorstudio.app.ai.ocr.OcrClipResult(
            text = text,
            timestampMs = timestampMs,
            sourceWidth = frame?.sourceWidth ?: 0,
            sourceHeight = frame?.sourceHeight ?: 0,
            lineCount = lineCount,
            confidence = confidence
        )
        val project = _uiState.value.project ?: return
        val updatedClips = project.clips.map { c ->
            if (c.id != targetId) c
            else c.copy(ocrResult = com.aicreatorstudio.app.ai.ocr.OcrClipResultJson.encode(committed))
        }
        _uiState.update { it.copy(project = project.copy(clips = updatedClips)) }
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
        _uiState.update {
            it.copy(
                ocrResult = it.ocrResult?.copy(appliedText = text, appliedTimestampMs = timestampMs)
            )
        }
    }

    /**
     * Clear the staged OCR panel result and the committed clip field.
     *
     * 2026-10-20 — split out of [resetOcr] so the "Reset" pill and
     * the "Apply" flow can reuse it. The target clip is resolved from
     * the staged result's clip id, falling back to the selection.
     */
    private fun clearOcrCommitted() {
        val staged = _uiState.value.ocrResult
        val selected = _uiState.value.selectedClip
        val targetId = staged?.clipId?.takeIf { it.isNotEmpty() } ?: selected?.id
        _uiState.update { it.copy(ocrResult = null) }
        if (targetId == null) return
        val project = _uiState.value.project ?: return
        val updatedClips = project.clips.map { c ->
            if (c.id != targetId) c else c.copy(ocrResult = null)
        }
        if (updatedClips == project.clips) return
        historyDelegate.push()
        _uiState.update { it.copy(project = project.copy(clips = updatedClips)) }
        projectRepository.saveAiTools(
            projectId = project.id,
            tools = updatedClips.toClipAiTools()
        )
    }

    /** Clear the OCR staged result + the clip's committed result. */
    fun resetOcr() {
        // `resetAiTool("ocr")` already routes to `clearOcrCommitted()`.
        resetAiTool("ocr")
    }
}


/**
 * Map a clip list to the persisted `ClipAiTools` blob.
 *
 * **2026-09-29 — this exists because the four call sites that used to
 * build this map inline disagreed with each other.** `motionTrack` was
 * carried by one of them and silently dropped by the other three, so a
 * tracked overlay was lost on the next save (bug class 1: written but
 * never rendered). Adding `speedCurve` to four hand-written literals
 * would have repeated the mistake with a fifth field, so the mapping
 * lives here and every field is listed exactly once.
 *
 * A field added to [VideoClip] and to
 * [com.aicreatorstudio.app.db.EditorStateJson.ClipAiTools] but forgotten
 * *here* is still a silent data loss — that is the residual risk, and it
 * is why this is one function rather than four.
 */
private fun List<VideoClip>.toClipAiTools():
    Map<String, com.aicreatorstudio.app.db.EditorStateJson.ClipAiTools> =
    associate { clip ->
        clip.id to com.aicreatorstudio.app.db.EditorStateJson.ClipAiTools(
            matting = clip.matting,
            autoFrame = clip.autoFrame,
            cropKeyframes = clip.cropKeyframes,
            motionTrack = clip.motionTrack,
            voiceClean = clip.voiceClean,
            ocrResult = clip.ocrResult,
            speedCurve = clip.speedCurve,
            highlightRanges = clip.highlightRanges,
            // PHASE A (Persistence Integrity) — the per-clip look
            // settings used to be written to neither a column nor the
            // blob, so every filter / grade / transform / fade was
            // silently dropped on reopen. They ride the blob now.
            look = com.aicreatorstudio.app.model.ClipLookSettings(
                videoEffect = clip.videoEffect,
                colorGrading = clip.colorGrading,
                opacity = clip.opacity,
                fillMode = clip.fillMode,
                transform = clip.transform,
                fadeInMs = clip.fadeInMs,
                fadeOutMs = clip.fadeOutMs,
                // 2026-10-21 — the AI upscaler settings used to be
                // written to neither a column nor the blob, so the
                // user's choice was dropped on reopen and the export
                // never saw it. They ride the blob now.
                upscale = clip.upscale
            ),
            // 2026-10-13 Voice Changer. Written only when non-NONE by
            // the encoder, so this is a no-op for every project that
            // never touched the panel.
            voiceEffect = clip.voiceEffect
        )
    }
