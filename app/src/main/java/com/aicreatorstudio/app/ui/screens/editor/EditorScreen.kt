@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aicreatorstudio.app.ui.screens.editor

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aicreatorstudio.app.export.ExportState
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.ProgressBarGeometry
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.ClipIdTransformUpdater
import com.aicreatorstudio.app.model.CropPreset
import com.aicreatorstudio.app.model.boundsNormalized
import com.aicreatorstudio.app.model.isVisibleAtTime
import com.aicreatorstudio.app.model.EditorContext
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.editorContext
import com.aicreatorstudio.app.model.hasAdjacentClip
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.ui.screens.editor.toFontFamily
import com.aicreatorstudio.app.model.SubPanelHeight
import com.aicreatorstudio.app.model.SubtitleMessages
import com.aicreatorstudio.app.model.ToolPanel
import com.aicreatorstudio.app.model.TrackKind
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.ads.TapsellAdManager
import com.aicreatorstudio.app.ads.TapsellAdsEntryPoint
import com.aicreatorstudio.app.ai.WhisperModelSpec
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.upscale.UpscaleProfile
import com.aicreatorstudio.app.ai.upscale.UpscaleProgress
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.SurfaceInset
import com.aicreatorstudio.app.ui.theme.SurfaceSunken
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.AccentPrimary
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.OnVideoScrim
import com.aicreatorstudio.app.ui.theme.OnVideoScrimStrong
import com.aicreatorstudio.app.ui.theme.StateDisabledAlpha
import com.aicreatorstudio.app.ui.theme.StatusError
import com.aicreatorstudio.app.ui.theme.StatusErrorContainer
import com.aicreatorstudio.app.ui.theme.StatusInfo
import com.aicreatorstudio.app.ui.theme.StatusInfoContainer
import com.aicreatorstudio.app.ui.theme.StatusWarning
import com.aicreatorstudio.app.ui.theme.StatusWarningContainer
import com.aicreatorstudio.app.ui.theme.AccentDeep
import com.aicreatorstudio.app.ui.theme.ctaBrush
import com.aicreatorstudio.app.ui.components.GlassChrome
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.iOSRed
import com.aicreatorstudio.app.viewmodel.AudioDeleteOutcome
import com.aicreatorstudio.app.viewmodel.EditorViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * `aiTools` key for Beat Detection. Mirrors `EditorViewModel.TOOL_ID_BEATS`
 * (the ViewModel's constant is private), so the panel reads the same slot the
 * ViewModel writes. The two must stay equal or the panel would watch a key
 * nothing ever updates.
 */
private const val BEAT_TOOL_ID = "beats"

/**
 * 2026-10-21 — the grain preview's noise tiles.
 *
 * The export's grain is per-pixel white noise regenerated every frame.
 * A preview cannot do that cheaply, so it tiles a small noise texture
 * and cycles through a handful of them — one per rendered frame — which
 * reads as moving grain rather than as a fixed pattern.
 *
 * 160 px is a compromise: large enough that the noise does not read as
 * blocks on a 1080p canvas, small enough that six tiles cost well under
 * a megabyte.
 */
private const val GRAIN_TILE_SIZE = 160
private const val GRAIN_TILE_COUNT = 6
private const val GRAIN_TILE_SEED = 20261021L

/** Milliseconds of playback per grain tile — about one 30 fps frame. */
private const val GRAIN_FRAME_MS = 33L

// =================== Section height tokens ===================
private val TopBarHeight = 56.dp
private val PlaybackStripHeight = 56.dp
// 2026-09-28 — the bottom dock is now the contextual single-row toolbar
// (`ContextualToolbar`), which sizes to its content. The old two-row
// category layout and its fixed height token are gone, along with
// `EditorBottomDock.kt` / `BottomToolbar.kt`.
private val TimelineMaxHeight = 280.dp

/**
 * Top-level video editor screen.
 *
 * Strict top-to-bottom hierarchy, all visible on any screen size:
 *
 *   1. [EditorTopBarSection]         — back, title/FPS, undo/redo, export
 *   2. [EditorVideoPreviewSection]   — `PlayerView` in a `Box(AspectRatio 16:9, max height)`
 *   3. [EditorPlaybackStripSection]  — play/pause, scrubber, timecodes
 *   4. [EditorTimelineSection]       — multi-track timeline with fixed center playhead
 *   5. [EditorBottomDockSection]     — the contextual tool row (`ContextualToolbar`)
 *
 * The video preview takes all remaining space via `weight(1f)` but is capped
 * at `VideoPreviewMaxHeight` to leave room for the timeline on small screens.
 * Status-bar and navigation-bar insets are applied on the root so no chrome
 * overlaps system UI.
 *
 * ExoPlayer is created once per screen visit, paused on `ON_STOP`, and
 * released on dispose. A 30fps position-poller drives the playhead so it
 * stays in lock-step with the actual frame.
 */
// Token used with Handler.removeCallbacksAndMessages so only the
// settle-timeout callback is cancelled (never other queued work).
private val SETTLE_TIMEOUT_TOKEN = Any()

@Composable
fun EditorScreen(
    onBackClick: () -> Unit,
    /**
     * 2026-10-20 — optional deep-link to an AI tool panel.
     *
     * The home screen's capability tiles open the editor with the
     * matching tool already open. The value is a [ToolPanel] enum
     * name; an unknown value is ignored (the editor just opens on the
     * timeline) rather than crashing.
     */
    initialTool: String? = null,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var exportDialogVisible by rememberSaveable { mutableStateOf(false) }
    var lastShownExportKind by rememberSaveable { mutableStateOf<String?>(null) }

    // 2026-09-29 — Tapsell export interstitial. Resolved from the application
    // context through a Hilt entry point, mirroring `HomeScreen`, because a
    // composable cannot constructor-inject.
    val context = LocalContext.current
    val tapsellAds: TapsellAdManager = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TapsellAdsEntryPoint::class.java
        ).tapsellAdManager()
    }

    LaunchedEffect(uiState.exportState) {
        val state = uiState.exportState
        val isResultLike = state is ExportState.Succeeded ||
            state is ExportState.Failed ||
            state is ExportState.Cancelled
        val kind = state::class.simpleName
        if (isResultLike && kind != lastShownExportKind) {
            exportDialogVisible = true
            lastShownExportKind = kind
            // 2026-09-29 — Tapsell export interstitial, fired once the render
            // has finished and the file is saved. Only on SUCCESS: a failed or
            // cancelled export produced nothing to show for, and serving an ad
            // on top of our own error is worse than serving none.
            //
            // Deliberately here rather than inside `ExportDialog`, so that
            // dialog stays a pure function of its state. The branch is already
            // de-duplicated by `lastShownExportKind`, which is what stops the
            // ad firing on every recomposition while the success dialog is up
            // (and across rotation, since that guard is `rememberSaveable`).
            if (state is ExportState.Succeeded) {
                (context as? Activity)
                    ?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?.let { tapsellAds.showExportInterstitial(it) }
            }
        } else if (state is ExportState.Idle) {
            exportDialogVisible = false
            // 2026-09-10 — clear the "already shown" guard on Idle so
            // the NEXT run's terminal state (even the same class) can
            // raise the dialog again.
            lastShownExportKind = null
        }
    }

    LaunchedEffect(Unit) { viewModel.initializeSubtitleModel() }

    // 2026-10-20 — deep-link from the home screen's AI capability
    // tiles: open the requested tool panel. The ViewModel makes this
    // one-shot, so a rotation does not re-open a panel the user closed.
    LaunchedEffect(initialTool) { viewModel.openInitialTool(initialTool) }

    BackHandler(enabled = uiState.toolPanel != null) {
        viewModel.setToolPanel(null)
    }

    if (exportDialogVisible) {
        ExportDialog(
            state = uiState.exportState,
            // Same inline duration as the safety-net branch below — the
            // quality picker's duration / size panel needs it on the
            // NORMAL open path too.
            durationMs = uiState.project?.clips?.maxOfOrNull { clip ->
                clip.timelineStartMs.coerceAtLeast(0L) + clip.effectiveDurationMs
            } ?: 0L,
            onDismiss = {
                exportDialogVisible = false
                viewModel.consumeExportResult()
            },
            onStart = { q -> viewModel.onExportClicked(quality = q) },
            onCancel = { viewModel.onExportCancelClicked() }
        )
    } else if (uiState.exportState !is ExportState.Idle) {
        // 2026-09-19 EXPORT VISIBILITY SAFETY NET — the progress /
        // result dialog is DERIVED from the export state machine,
        // not just the local tap flag: if an export is Preparing /
        // Exporting / finished while the flag was lost (rotation,
        // process restore, a stuck earlier run), the dialog MUST
        // still show. There is no longer any state in which an
        // export runs (or failed) invisibly.
        ExportDialog(
            state = uiState.exportState,
            // 2026-10-05 — real project duration feeds the quality
            // picker's duration / estimated-size panel. Computed inline
            // (same arithmetic as PlaylistPosition.totalMs) because the
            // `projectDuration` val is declared further down this
            // composable and is not in scope here yet.
            durationMs = uiState.project?.clips?.maxOfOrNull { clip ->
                clip.timelineStartMs.coerceAtLeast(0L) + clip.effectiveDurationMs
            } ?: 0L,
            onDismiss = {
                viewModel.consumeExportResult()
            },
            onStart = { q -> viewModel.onExportClicked(quality = q) },
            onCancel = { viewModel.onExportCancelClicked() }
        )
    }

    // AI Upscaler progress modal. Shown whenever an upscale export is
    // in any non-Idle state (Preparing / Enhancing / Reconstructing /
    // Finalizing / terminal). Uses the same composition-time check as
    // ExportDialog above — the dialog self-hides on Idle.
    UpscaleProgressDialog(
        state = uiState.upscaleExportState,
        onCancel = { viewModel.onUpscaleExportCancel() },
        onDismiss = { viewModel.consumeUpscaleExportResult() }
    )

    // 2026-10-21 — «نریشن» (voiceover). The actions are provided
    // through a composition local rather than a parameter: the
    // sub-panel call sits inside an `AnimatedVisibility` content lambda
    // that is already at the JVM's 64 KB method-size limit, and one
    // more argument there made the whole screen fail to compile with
    // `MethodTooLargeException`. The provider's own content is a single
    // call, so it costs that lambda nothing.
    //
    // 2026-10-25 — same story with the Background Remove mask knobs
    // (expansion / feather / reverse): three more arguments in that
    // `AnimatedVisibility` lambda pushed it past the limit a second
    // time, so they travel through `LocalMattingPanelActions` too.
    CompositionLocalProvider(
        LocalVoiceoverActions provides remember {
            VoiceoverPanelActions(
                onStart = viewModel::startVoiceover,
                onStop = viewModel::stopVoiceover,
                onPlace = viewModel::placeVoiceover,
                onDiscard = viewModel::cancelVoiceover
            )
        },
        LocalMattingPanelActions provides remember {
            MattingPanelActions(
                onExpansionChanged = viewModel::setMattingExpansion,
                onFeatherChanged = viewModel::setMattingFeather,
                onReverseChanged = viewModel::setMattingReverse
            )
        }
    ) {
        EditorScreenBody(
            uiState = uiState,
            viewModel = viewModel,
            onBackClick = onBackClick,
            onShowExportDialog = { exportDialogVisible = true }
        )
    }
}

// ====================================================================
// Root body
// ====================================================================

@Composable
private fun EditorScreenBody(
    uiState: EditorUiState,
    viewModel: EditorViewModel,
    onBackClick: () -> Unit,
    onShowExportDialog: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addAudioClip(it.toString()) } }

    // 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو).
    // ACTION_OPEN_DOCUMENT with a video/* filter — the system picker
    // shows only videos, and the ViewModel's extraction reads the picked
    // stream directly (no copy of the whole video into app storage).
    val extractAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.extractAudioFromVideo(it) } }

    // Tracks which clip is currently being dragged so we push undo
    // history exactly once per drag (on the first frame), not on every
    // pointer frame.
    var lastDraggedClipId by remember { mutableStateOf<String?>(null) }
    // 2026-10-04 — same pattern for TRIM drags: `EditorViewModel.setClipTrim`
    // used to push an undo snapshot on every pointer frame, so a single
    // 3-second trim gesture evicted the whole 20-slot undo stack with
    // intermediate states. The first frame pushes; the rest are free.
    // `lastTrimmedClipId` is cleared when the gesture ends
    // ([onClipTrimEnd]) so the NEXT gesture on the same clip pushes again.
    var lastTrimmedClipId by remember { mutableStateOf<String?>(null) }
    // 2026-10-20 — same coalescing latch for AUDIO-tile drags. Kept separate
    // from `lastDraggedClipId` on purpose: a paired soundtrack delegates to
    // `updateClipPosition`, so sharing one latch would let an audio drag and a
    // clip drag on the same object swallow each other's undo entry. Both
    // latches are cleared on release (`onDragEnd`) — without that reset a drag
    // performed after an undo silently skipped `historyDelegate.push()`.
    var lastDraggedAudioId by remember { mutableStateOf<String?>(null) }

    // 2026-10-05 — "+" beside the timeline: pick a video, append it to the
    // open project. ACTION_OPEN_DOCUMENT needs no runtime permission and
    // the ViewModel copies the file into app-owned storage inside the
    // callback, so the transient grant is not a problem.
    val addClipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.addClipFromUri(uri)
        }
    }

    // 2026-10-05 — real timeline waveforms (keyed by waveformCacheKey/id).
    val audioWaveforms by viewModel.waveforms.collectAsState()
    // Real filmstrip frames for video clips (keyed "id@uri").
    val clipThumbnails by viewModel.clipThumbnails.collectAsState()

    val overlayPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            viewModel.addMediaOverlay(uri.toString(), mime.startsWith("video"))
        }
    }

    // 2026-09-01 Background Remove — picker for the replacement
    // background image / video. Returns the chosen URI; the
    // ViewModel writes it onto the selected clip's
    // MattingSettings.replacementBackgroundUri and persists.
    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onBackgroundMediaPicked(uri)
        }
    }

    // "Media Missing / Relink" — fires when a restored project carries a
    // clip whose backing media is gone (legacy dead photo-picker grant,
    // deleted file). The user picks a replacement video; the ViewModel
    // copies it into app-owned storage and rewires the affected clip, so
    // the project becomes self-contained again.
    val relinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.relinkMissingMedia(it.toString()) } }

    // Android Storage Access Framework: "Save as" file picker for SRT
    // export. Triggered by [EditorViewModel.srtExportTrigger]; we
    // hand the system the pre-rendered SRT body and a suggested file
    // name, and write to the user-chosen URI on IO. The ViewModel
    // is the single source of truth for the trigger so the picker
    // never pops up unintentionally.
    val srtSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri ->
        if (uri != null) {
            val body = uiState.srtExportTrigger?.srtBody
            if (body != null) {
                viewModel.writeSrtToUri(uri, body)
            }
        }
        viewModel.consumeSrtExportTrigger()
    }

    // PHASE B4 — Android Storage Access Framework: "Open" file
    // picker for SRT import. Triggered by
    // [EditorViewModel.srtImportTrigger]; we ask the system for
    // any SubRip-style file (MIME application/x-subrip with a
    // text/plain fallback) and the result Uri is handed to
    // [EditorViewModel.importSrtFromUri] which reads + parses +
    // appends TextOverlays. The ViewModel is the single source
    // of truth for the trigger so the picker never pops up
    // unintentionally.
    val srtOpenLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importSrtFromUri(uri)
        }
        viewModel.consumeSrtImportTrigger()
    }

    // ----- ExoPlayer: created once, paused on background, released on dispose -----
    //
    // 2026-10-13 — Voice Changer. The player's audio sink is created by
    // `VoiceEffectRenderersFactory`, which mirrors Media3's own default
    // sink and adds the two voice processors (timbre, then pitch). The
    // bridge is the live handle the LaunchedEffect below writes and the
    // audio thread reads; it is created outside the `remember` block so
    // the factory can close over it.
    val voicePreviewBridge = remember { VoiceEffectPreviewBridge() }
    val exoPlayer = remember {
        ExoPlayer.Builder(
            context.applicationContext,
            VoiceEffectRenderersFactory(context.applicationContext, voicePreviewBridge)
        ).build().apply {
            playWhenReady = false
            // 2026-10-04 — the preview is now a PLAYLIST of every video
            // clip (see the setMediaItems effect below), so playback must
            // advance clip → clip instead of looping one item. The
            // previous REPEAT_MODE_ONE belonged to the single-clip era:
            // it kept the active clip looping and made sequential
            // multi-clip preview impossible.
            repeatMode = Player.REPEAT_MODE_OFF
            // 2026-09-19 — REVERTED. Do NOT call `setVideoEffects` here.
            //
            // A previous round called `setVideoEffects(emptyList())` at this
            // exact point, to force `MediaCodecVideoRenderer.onEnabled` to
            // build its GL pipeline (`PlaybackVideoGraphWrapper`, i.e. the
            // `videoSink`). The sink gate it was chasing is real — but
            // satisfying it *here* is the wrong moment, and it broke playback
            // outright: the video never started and Play/Pause did nothing.
            //
            // Why it broke, from the 1.5.0 sources:
            //   * `onEnabled` runs during `prepare()`, and it only tells the
            //     new sink where to draw when a display surface already exists
            //     at that instant — `MediaCodecVideoRenderer:752` is guarded by
            //     `displaySurface != null && outputResolution != UNKNOWN`.
            //   * This screen inflates its `PlayerView` inside the Compose
            //     `AndroidView` factory, so the surface attaches *after* the
            //     player is prepared. At `onEnabled` time it is still null.
            //   * `setOutput()` — where the surface later arrives — forwards it
            //     only when `videoSink == null` (that is where
            //     `videoFrameReleaseControl.setOutputSurface` lives). For the
            //     sink case it merely calls `videoSink.clearOutputSurfaceInfo()`
            //     when the surface is *removed*. The only two
            //     `videoSink.setOutputSurfaceInfo` calls in the whole renderer
            //     are line 752 and the `MSG_SET_VIDEO_OUTPUT_RESOLUTION`
            //     handler at line 963 — neither of which runs for a surface
            //     that appears late.
            //   * So the sink is created **blind**: the decoder renders into
            //     `videoSink.getInputSurface()` and the GL graph has no output
            //     surface to composite onto. No frame is ever produced,
            //     `onFirstFrameRendered` never fires, and the preview looks
            //     dead. The sliders still do nothing — because there is no
            //     frame to colour.
            //
            // Leaving `videoEffects` null keeps the decoder rendering straight
            // to the display surface, which is what this screen did before and
            // what plays correctly. The preview's colour is now applied by
            // Compose, outside the video engine entirely — see the
            // `ColorMatrixColorFilter` layer on the foreground `PlayerView`
            // below. Do not reintroduce a `setVideoEffects` call here.
        }
    }

    // 2026-09-19 — make a dead video pipeline VISIBLE.
    //
    // The entire colour / sharpness / vignette / grain chain lives inside Media3's
    // GL pipeline, and when that pipeline dies the app shows *nothing at all*:
    // `MediaCodecVideoRenderer` parks the failure as a pending PlaybackException,
    // the picture simply freezes on its last frame, and the editor looks exactly
    // like "the slider is not connected to the video". That is precisely what made
    // two earlier rounds of this fix inconclusive — the renderer was reporting a
    // hard error (for example `DefaultShaderProgram`'s
    // "A 4x4 transformation matrix must have 16 elements" assertion) and nothing
    // in the app was listening for it.
    //
    // One listener, zero behaviour change: it only logs. If the preview ever stops
    // responding again, `adb logcat -s EditorScreen` now says so outright instead
    // of leaving a silent freeze to be guessed at.
    DisposableEffect(exoPlayer) {
        val errorListener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e(
                    "EditorScreen",
                    "playback error — the preview effect pipeline may be dead: " +
                        "${error.errorCodeName}: ${error.message}",
                    error
                )
            }
        }
        exoPlayer.addListener(errorListener)
        onDispose { exoPlayer.removeListener(errorListener) }
    }

    // 2026-09-13 — parallel audio playback for AUDIO-ONLY timeline
    // clips (imported .wav/.mp3/.m4a + built-in SFX). The main
    // ExoPlayer above stays bound to the VIDEO clip; this mixer owns
    // one small player per audio-only clip and slaves them to the
    // same shared clock (position poller + scrub handlers below).
    // Without it, imported audio files were visible on the Music /
    // SFX lanes but never actually played — the "no sound" bug.
    val audioMixer = remember { AudioMixerPlayer(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { audioMixer.release() }
    }
    // Reconcile stem players whenever the audio-only clip list or
    // any of their playback-relevant fields change (volume, mute,
    // trim, position). Cheap no-op when nothing changed.
    // NOTE: when the project has NO video clip at all, the main
    // ExoPlayer falls back to playing the FIRST audio-only clip
    // (see activeClip below) — that clip is excluded from the mixer
    // so it is never played twice.
    val audioOnlyClips = uiState.project?.clips
        ?.filter { it.isAudioOnly }
        ?.let { all ->
            val projectHasVideo = uiState.project?.clips
                ?.any { !it.isAudioOnly } == true
            if (projectHasVideo) all else all.drop(1)
        }
        ?: emptyList()
    LaunchedEffect(
        // 2026-09-19: fadeInMs / fadeOutMs belong IN THIS KEY. They are read
        // by AudioMixerPlayer's `gainAt`, so a fade change has to re-run
        // `update()`; without them the fade only appeared after some
        // unrelated edit happened to change another keyed field.
        // 2026-10-13: `voiceEffect` belongs IN THIS KEY too. It is read by
        // `StemClip` below and retargets the stem's own voice bridge in
        // `AudioMixerPlayer.update()`. Omitting it would mean selecting a
        // preset changed the panel and the exported file while the stem
        // preview kept playing the old effect — the exact "declared but
        // never forwarded" failure the fade fields already fell into.
        audioOnlyClips.map { listOf(it.id, it.uri, it.timelineStartMs, it.trimStartMs, it.effectiveDurationMs, it.volume, it.isAudioOnly, it.fadeInMs, it.fadeOutMs, it.voiceEffect) },
        audioOnlyClips.size
    ) {
        audioMixer.update(
            audioOnlyClips.map { clip ->
                AudioMixerPlayer.StemClip(
                    id = clip.id,
                    uri = clip.uri,
                    startMs = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L,
                    durationMs = clip.effectiveDurationMs,
                    trimStartMs = clip.trimStartMs,
                    volume = clip.volume,
                    muted = clip.volume <= 0f,
                    // 2026-09-19: these two were omitted here — the ONLY
                    // construction site of `StemClip` — so the fields stayed
                    // at their `0L` defaults and `gainAt` was the identity
                    // for every audio clip. The Fade In / Fade Out sliders
                    // moved the panel and changed the exported file while
                    // the preview played at full level. This is the
                    // project's own recurring "declared but never forwarded"
                    // failure mode: when a parameter exists only to be
                    // forwarded, grep its call sites.
                    fadeInMs = clip.fadeInMs,
                    fadeOutMs = clip.fadeOutMs,
                    // 2026-10-13 — voice changer. `AudioMixerPlayer` gives each
                    // stem its own `VoiceEffectPreviewBridge` and applies this
                    // in `update()`; the stem processor chain is
                    // [voice, sonic(pitch)] + Media3's [silenceSkip, sonic(rate)],
                    // the same stage order the export uses.
                    voiceEffect = clip.voiceEffect
                )
            }
        )
        audioMixer.setSpeed(uiState.playbackSpeed.coerceIn(0.25f, 4f))
    }

    // ---------------------------------------------------------------
    // 2026-10-13 — Picture-in-Picture overlays.
    //
    // One small ExoPlayer per VIDEO overlay, slaved to the same clock the
    // audio stems use. Until this round `VideoOverlay` had no renderer at
    // all: `positionX` / `positionY` / `scale` / `uri` reached the codec
    // and the timeline lane and nothing else, and the only two
    // `ExoPlayer.Builder` sites were the main preview and the stems.
    // ---------------------------------------------------------------
    val pipPlayer = remember { OverlayPipPlayer(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { pipPlayer.release() }
    }
    // Only VIDEO overlays get a player; a still overlay is decoded to a
    // bitmap by the preview layer itself.
    val pipVideoOverlays = uiState.overlays.filter { it.isVideo && it.uri.isNotBlank() }
    LaunchedEffect(
        // Every field `OverlayPipPlayer.PipClip` reads belongs in this key.
        // The window start/end drive the seek target, and the uri decides
        // the media item — omitting any of them is the "declared but never
        // forwarded" failure this project keeps hitting.
        pipVideoOverlays.map { listOf(it.id, it.uri, it.startTimeMs, it.endTimeMs) },
        pipVideoOverlays.size
    ) {
        pipPlayer.update(
            pipVideoOverlays.map { overlay ->
                OverlayPipPlayer.PipClip(
                    id = overlay.id,
                    uri = overlay.uri,
                    startMs = overlay.startTimeMs,
                    endMs = overlay.endTimeMs
                )
            }
        )
    }

    var isPlaying by remember { mutableStateOf(false) }
    // 2026-10-13 — the user asked for playback while this playlist was
    // still preparing. The STATE_READY handler below force-pauses on the
    // first ready event (that is how the preview shows a still first
    // frame instead of auto-playing on import); without this latch a tap
    // that lands before READY is swallowed and the Play button looks
    // dead. Read through the `by` delegate, not a plain variable, because
    // the listener that consumes it is installed in an effect that
    // outlives the tap — and never `rememberUpdatedState`, whose `value`
    // is read-only (`rememberUpdatedState` returns `State`, not
    // `MutableState`).
    var userPlayRequested by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var playerDurationMs by remember { mutableLongStateOf(0L) }

    // ------------------------------------------------------------------
    // 2026-10-04 multi-clip preview — the player owns a PLAYLIST of every
    // video clip (ordered by timelineStartMs). `currentPositionMs` is the
    // EDITOR-TIMELINE playhead (clip start + window position), the domain
    // every downstream consumer already assumes: subtitle highlighting,
    // `EditorViewModel.splitClip`'s global position, `AudioMixerPlayer`
    // stem starts, and the persisted `lastPositionMs`.
    //
    // Trim preview: each playlist item carries a `ClippingConfiguration`
    // built from the clip's trim window, so trimmed-off frames never
    // reach the preview. Trim values are baked into the playlist at
    // [playlistRevision] bumps (drag end) instead of per drag frame —
    // rebuilding a playlist mid-drag would stutter the decoder.
    // ------------------------------------------------------------------
    var playlistRevision by remember { mutableStateOf(0) }
    // Index of the item the player is currently on; written by the
    // onMediaItemTransition listener and after playlist (re)builds. The
    // per-clip speed/volume effects re-run off this so a transition into
    // a 2× clip actually plays at 2×.
    var playingIndex by remember { mutableStateOf(0) }

    // Playback-ordered preview playlist. Audio-only clips never enter it
    // (they are mixed by AudioMixerPlayer stems); when the project has no
    // video clips at all, the first audio-only clip falls back into the
    // playlist so the main player still has something to show/play.
    val playlistClips = uiState.project?.clips
        ?.filter { !it.isAudioOnly }
        ?.sortedBy { it.timelineStartMs.coerceAtLeast(0L) }
        ?.ifEmpty {
            listOfNotNull(uiState.project?.clips?.firstOrNull { it.isAudioOnly })
        }
        ?: emptyList()
    val playlistClipsRef = rememberUpdatedState(playlistClips)
    val isMutedRef = rememberUpdatedState(uiState.isMuted)
    val globalVolumeRef = rememberUpdatedState(uiState.volume)
    val isPlayingRef = rememberUpdatedState(isPlaying)
    val playingClip = playlistClips.getOrNull(playingIndex)
        ?: playlistClips.firstOrNull()
    // 2026-08-29: set to true while the user is holding the Color
    // Grading panel's "Compare" button. The preview applies an
    // identity ColorMatrix while this is true so the user can A/B
    // before/after.
    var isComparing by remember { mutableStateOf(false) }

    // 2026-08-31: live snapshot of the scrubbing flag for the 33 ms
    // playback poller. The poller lives inside a DisposableEffect
    // keyed only on `exoPlayer`, so its Runnable captures `uiState`
    // by closure at composition time. That captured value would go
    // stale the moment the user started scrubbing (because
    // `uiState` is just a composable parameter — recomposing
    // produces a new `uiState`, but the previously-installed
    // runnable still closes over the old one). The result was a
    // feedback loop: every 33 ms tick read
    // `uiState.isScrubbing == false` and overwrote the local
    // `currentPositionMs` with `exoPlayer.currentPosition`, which
    // lags behind every in-flight `seekTo`. The playhead visibly
    // jumped backward at the start of every drag and trailed the
    // finger by one frame on release. `rememberUpdatedState` is the
    // Compose primitive that guarantees the inner lambda always sees
    // the freshest value at the moment it runs, even though the
    // surrounding DisposableEffect never restarts.
    val scrubbingRef = rememberUpdatedState(uiState.isScrubbing)

    // ------------------------------------------------------------------
    // 2026-09-06 playhead overhaul — shared, throttled scrub pipeline.
    //
    // The old pipeline had four compounding bugs:
    //   1. A seek-parameters FIGHT: `LaunchedEffect(isScrubbing)` set
    //      EXACT at drag start, but the per-frame pendingScrubSeekMs
    //      effect reset it to CLOSEST_SYNC on the very next drag frame,
    //      so the release snap-to-keyframe bug it was meant to fix
    //      still happened.
    //   2. A seek STORM: one seekTo per drag frame (~60 Hz) — each an
    //      EXACT decode — starved the decoder and made dragging stutter.
    //   3. Scrubbing while PLAYING fought the player: every seek
    //      restarted playback from the old position.
    //   4. The 33 ms poller raced the final seek on release: the first
    //      tick after `onScrubEnded` adopted the player's stale
    //      pre-seek position and the playhead jumped backwards.
    //
    // The new pipeline (all routed through these three handlers so the
    // timeline drag AND the playback-strip slider behave identically):
    //   - Drag frame: update the local `currentPositionMs` (the visual
    //     source of truth) synchronously; throttle player seeks to
    //     SCRUB_SEEK_INTERVAL_MS (CLOSEST_SYNC = cheap, smooth); pause
    //     playback for the duration of the drag.
    //   - Release: one EXACT seek to the final ms, one synchronous VM
    //     push, then block the poller until the seek settles (seek
    //     discontinuity event or SCRUB_SETTLE_TIMEOUT_MS fallback).
    // ------------------------------------------------------------------
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }
    var awaitingSeekSettle by remember { mutableStateOf(false) }
    // Last position the user requested (updated every drag frame).
    // Read by the Player.Listener below to decide when the
    // post-release EXACT seek has settled.
    var lastRequestedSeekMs by remember { mutableLongStateOf(0L) }
    var lastScrubTargetMs by remember { mutableLongStateOf(-1L) }
    var lastScrubSeekAtMs by remember { mutableLongStateOf(0L) }
    var lastVmPushTargetMs by remember { mutableLongStateOf(-1L) }
    var lastVmPushAtMs by remember { mutableLongStateOf(0L) }
    val mainHandler = remember {
        android.os.Handler(android.os.Looper.getMainLooper())
    }

    val handleScrubStart: () -> Unit = {
        viewModel.onScrubStarted()
        // Pause during the drag so the player stops fighting the
        // finger; resumed on release if it was playing.
        wasPlayingBeforeScrub = runCatching { exoPlayer.isPlaying }.getOrDefault(false)
        if (wasPlayingBeforeScrub) runCatching { exoPlayer.pause() }
        // 2026-09-13 — audio stems pause with the master during the
        // drag (they still SEEK along the finger via the poller's
        // syncTo with currentPositionMs).
        audioMixer.setPlaying(false)
        // Drag seeks use CLOSEST_SYNC — fast, decode-cheap. The single
        // EXACT seek happens once, on release (see handleScrubEnd).
        runCatching { exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC) }
        lastScrubTargetMs = -1L
        lastScrubSeekAtMs = 0L
        lastVmPushTargetMs = -1L
        lastVmPushAtMs = 0L
    }
    val handleScrubSeek: (Long) -> Unit = { ms ->
        val target = ms.coerceAtLeast(0L)
        // The finger is the visual source of truth — every drag frame
        // updates the local playhead synchronously (no recomposition
        // round-trip through the ViewModel). The value is EDITOR-timeline
        // time; the player seek resolves it to (item, window position).
        currentPositionMs = target
        val (itemIndex, windowPos) =
            PlaylistPosition.resolve(playlistClipsRef.value, target)
        lastRequestedSeekMs = windowPos
        val now = android.os.SystemClock.uptimeMillis()
        if (PlayheadScrubThrottle.shouldSeek(
                nowMs = now,
                lastSeekAtMs = lastScrubSeekAtMs,
                targetMs = target,
                lastTargetMs = lastScrubTargetMs,
                minIntervalMs = EditorLayoutSpec.SCRUB_SEEK_INTERVAL_MS
            )
        ) {
            lastScrubTargetMs = target
            lastScrubSeekAtMs = now
            runCatching { exoPlayer.seekTo(itemIndex, windowPos) }
        }
        if (PlayheadScrubThrottle.shouldPushViewModel(
                nowMs = now,
                lastPushAtMs = lastVmPushAtMs,
                targetMs = target,
                lastPushedMs = lastVmPushTargetMs,
                minIntervalMs = EditorLayoutSpec.SCRUB_VM_PUSH_INTERVAL_MS
            )
        ) {
            lastVmPushTargetMs = target
            lastVmPushAtMs = now
            // Mirrors playbackPositionMs (persistence + status reads).
            // Deliberately NOT the old pendingScrubSeekMs round-trip —
            // that produced two full recompositions per drag frame.
            viewModel.onScrubSeek(target)
        }
    }
    val handleScrubEnd: () -> Unit = {
        val finalMs = currentPositionMs.coerceAtLeast(0L)
        // 1. One EXACT seek so the player lands on the exact ms the
        //    finger left (no keyframe snap-back).
        val (finalIndex, finalWindowPos) =
            PlaylistPosition.resolve(playlistClipsRef.value, finalMs)
        runCatching {
            exoPlayer.setSeekParameters(SeekParameters.EXACT)
            exoPlayer.seekTo(finalIndex, finalWindowPos)
        }
        // 2026-09-13 — park the audio stems on the final position so
        // they resume in sync with the master (the poller's syncTo
        // keeps them honest afterwards).
        audioMixer.syncTo(finalMs)
        audioMixer.setPlaying(wasPlayingBeforeScrub)
        // Block the 33 ms poller until the seek settles, so it never
        // adopts a stale pre-seek player position (the jump-back bug).
        awaitingSeekSettle = true
        mainHandler.removeCallbacksAndMessages(SETTLE_TIMEOUT_TOKEN)
        mainHandler.postDelayed({
            awaitingSeekSettle = false
        }, SETTLE_TIMEOUT_TOKEN, EditorLayoutSpec.SCRUB_SETTLE_TIMEOUT_MS)
        // 2. Synchronous final VM push + end bookkeeping (persists the
        //    position, recomputes the active subtitle).
        viewModel.onScrubSeek(finalMs)
        viewModel.onScrubEnded()
        // 3. Resume playback if the drag started while playing.
        if (wasPlayingBeforeScrub) {
            runCatching {
                exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                exoPlayer.play()
            }
        } else {
            runCatching { exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC) }
        }
        lastScrubTargetMs = -1L
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }

            // 2026-10-04 — the playlist moved to the next clip. Bumping
            // `playingIndex` re-runs the per-clip speed / volume effects
            // below (a transition into a 2× clip must play at 2×), and
            // the 33 ms poller's conversion picks the new item up on its
            // next tick.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playingIndex = exoPlayer.currentMediaItemIndex
            }

            // 2026-09-06: seek-settle signal. When the post-release
            // EXACT seek completes, the player reports a position
            // discontinuity with a seek reason — that is our cue to
            // unblock the 33 ms poller (see the settle gate below).
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    awaitingSeekSettle = false
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 2026-08-29: surface silent player failures to the
                // existing error banner instead of leaving the user
                // staring at a black preview. The raw error is logged
                // at WARN for development; the banner shows a short
                // user-facing message.
                android.util.Log.w(
                    "EditorScreen",
                    "ExoPlayer error: code=${error.errorCode} " +
                        "name=${error.errorCodeName} msg=${error.message}",
                    error
                )
                // 2026-10-14 — the forwarded message keeps the error CODE
                // NAME, not just the derived text. Without it the
                // diagnostics panel said "MediaCodecAudioRenderer error,
                // index=1, format=..." — which names the renderer but not
                // the stage (decoder vs. audio sink vs. configuration
                // failure), so a device report could not tell a missing
                // codec from a failed AudioSink configure. The code name
                // separates them in one look.
                viewModel.onPlayerError(
                    "${error.errorCodeName}: ${error.message ?: error.errorCodeName}"
                )
            }
        }
        exoPlayer.addListener(listener)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastPolledPos = -1L
        var lastPolledDur = -1L
        val runnable = object : Runnable {
            override fun run() {
                try {
                    // 2026-10-04 — the player position is WINDOW-relative
                    // (inside the current item's trim window); the playhead
                    // and every downstream consumer live in EDITOR-timeline
                    // time. Convert: clip start + window position. This is
                    // also what makes the playhead advance past a clip's
                    // end into the next one — the transition itself is
                    // ExoPlayer's, this conversion only keeps the drawn
                    // playhead honest about WHERE on the timeline that is.
                    val clips = playlistClipsRef.value
                    val itemIndex = exoPlayer.currentMediaItemIndex
                    val pos = exoPlayer.currentPosition
                    val editorPos = PlaylistPosition.editorMs(clips, itemIndex, pos)
                    val total = PlaylistPosition.totalMs(clips)
                    // While the user is actively scrubbing, the visual
                    // playhead is the finger — not the player clock.
                    // The local currentPositionMs (the input to BOTH
                    // the CenteredPlayheadOverlay visual x and the
                    // Material3 Slider thumb) must NOT be overwritten
                    // by the player's last-known position here, or the
                    // playhead will jump backwards to a stale /
                    // sync-frame-snapped value.
                    //
                    // 2026-09-06: TWO gates now guard the tick:
                    //   1. isScrubbing — the finger is the source of
                    //      truth for the whole drag (unchanged).
                    //   2. awaitingSeekSettle — after the user releases,
                    //      the final EXACT seek takes 100-500 ms on long
                    //      GOPs. Adopting the player's pre-seek position
                    //      during that window was the "playhead jumps
                    //      backwards right after release" bug. The gate
                    //      clears on the seek discontinuity event (see
                    //      onPositionDiscontinuity above) or via the
                    //      SCRUB_SETTLE_TIMEOUT_MS fallback in
                    //      handleScrubEnd.
                    // The poller also only writes state when a value
                    // actually CHANGED — the old code wrote 30
                    // recompositions/sec forever while paused.
                    if (PlayheadScrubThrottle.shouldAdoptPlayerPosition(
                            isScrubbing = scrubbingRef.value,
                            awaitingSettle = awaitingSeekSettle,
                            playerPositionMs = pos,
                            lastRequestedMs = lastRequestedSeekMs,
                            settleToleranceMs = 250L
                        )
                    ) {
                        if (editorPos != lastPolledPos || total != lastPolledDur) {
                            lastPolledPos = editorPos
                            lastPolledDur = total
                            currentPositionMs = editorPos
                            playerDurationMs = total
                            viewModel.onPositionChanged(editorPos)
                        }
                    }
                    // 2026-09-13 — drive the audio-only stem players
                    // with the SAME clock the visual playhead uses.
                    // While scrubbing the finger is the source of
                    // truth (`currentPositionMs`), so the stems follow
                    // the drag too (paused — the scrub pipeline pauses
                    // the master player, and syncTo(paused=false) keeps
                    // the stems paused as well).
                    // 2026-10-04 — the stem clock is EDITOR-timeline time
                    // in both branches now; `AudioMixerPlayer` stem starts
                    // are `timelineStartMs`, so the previous clip-local
                    // player position only ever worked for a clip at 0.
                    val clockForStems = if (scrubbingRef.value) currentPositionMs else editorPos
                    audioMixer.syncTo(clockForStems)
                    // 2026-10-13 — `isPlayingRef`, NOT the captured
                    // `isPlaying`. This DisposableEffect is keyed on
                    // `exoPlayer` alone, so it never restarts when
                    // playback starts; the plain `isPlaying` read below is
                    // the value from the composition where the effect was
                    // installed — permanently `false`. The poller was
                    // therefore hard-pausing every audio stem and every
                    // PiP player 33 ms after the user tapped Play, which
                    // read as "the play button does nothing".
                    audioMixer.setPlaying(isPlayingRef.value && !scrubbingRef.value)
                    // 2026-10-13 — PiP overlay players ride the same clock.
                    // `OverlayPipPlayer.syncTo` takes EDITOR-timeline ms
                    // (an overlay's `startTimeMs` is an editor-timeline
                    // position), which is exactly what `clockForStems` is.
                    pipPlayer.syncTo(clockForStems)
                    // Same stale-closure fix as the stems above: through
                    // `isPlayingRef`, or the PiP overlay video freezes on
                    // the first frame the moment the master starts.
                    pipPlayer.setPlaying(isPlayingRef.value && !scrubbingRef.value)
                } catch (_: Exception) { }
                handler.postDelayed(this, 33L)
            }
        }
        handler.post(runnable)
        onDispose {
            handler.removeCallbacks(runnable)
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    runCatching { exoPlayer.pause() }
                    // 2026-09-13 — hard-pause the audio stems too so
                    // imported audio keeps playing in background.
                    audioMixer.pauseAll()
                    // 2026-10-13 — and the PiP overlay players, for the
                    // same reason: a backgrounded editor must not keep
                    // decoding overlay video.
                    pipPlayer.setPlaying(false)
                }
                Lifecycle.Event.ON_DESTROY -> {
                    runCatching { exoPlayer.setVideoSurface(null) }
                    runCatching { exoPlayer.release() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.playbackSpeed) {
        exoPlayer.setPlaybackSpeed(uiState.playbackSpeed.coerceIn(0.25f, 4f))
        // 2026-09-13 — global speed applies to the audio stems too.
        audioMixer.setSpeed(uiState.playbackSpeed.coerceIn(0.25f, 4f))
    }
    LaunchedEffect(uiState.pendingSeekMs) {
        uiState.pendingSeekMs?.let { target ->
            // Programmatic seek (e.g. "jump to first subtitle"): precise
            // seek so we land exactly on the target frame. The target is
            // EDITOR-timeline time (subtitle stamps are timeline stamps);
            // resolve it onto the playlist.
            val (index, windowPos) = PlaylistPosition.resolve(
                playlistClipsRef.value, target.coerceAtLeast(0L)
            )
            runCatching { exoPlayer.seekTo(index, windowPos) }
            viewModel.consumePendingSeek()
        }
    }
    // SRT export — the ViewModel flips srtExportTrigger when the user
    // taps "Export SRT". We open the system "Save as" file picker
    // (ACTION_CREATE_DOCUMENT) so the user picks the destination;
    // the launcher callback then writes the SRT bytes and clears
    // the trigger.
    LaunchedEffect(uiState.srtExportTrigger) {
        val trigger = uiState.srtExportTrigger ?: return@LaunchedEffect
        srtSaveLauncher.launch(trigger.suggestedFileName)
    }
    // PHASE B4 — SRT import mirror of the export block above. The
    // ViewModel flips srtImportTrigger; we open the system "Open"
    // file picker (ACTION_OPEN_DOCUMENT) restricted to SRT MIME
    // types so the user can't accidentally pick a video or a
    // PDF. The launcher callback hands the chosen Uri to the
    // ViewModel, which reads + parses + appends the cues. We
    // key the effect on the trigger's requestId so a second tap
    // re-launches the picker even when the user previously
    // dismissed it.
    LaunchedEffect(uiState.srtImportTrigger?.requestId) {
        val trigger = uiState.srtImportTrigger ?: return@LaunchedEffect
        // SubRip first, plain text as a safety net for tools
        // that serve `.srt` as text/plain. We intentionally do
        // NOT include audio/* / video/* — SRT is text only and
        // the brief is explicit that we use SAF, not the broad
        // storage permission.
        srtOpenLauncher.launch(arrayOf("application/x-subrip", "text/plain"))
        // The trigger value is intentionally read but not used
        // beyond the LaunchedEffect key — the launcher fires
        // immediately and the callback clears the trigger.
        @Suppress("UNUSED_EXPRESSION") trigger
    }
    // 2026-09-06: the two scrub LaunchedEffects that lived here (the
    // pendingScrubSeekMs round-trip and the isScrubbing
    // SeekParameters toggle) were REMOVED:
    //
    //  1. The pendingScrubSeekMs round-trip pushed every drag frame
    //     into the ViewModel StateFlow and consumed it — two full
    //     editor recompositions per drag frame. Seeks are now issued
    //     directly from the shared throttled handlers above
    //     (handleScrubSeek / handleScrubEnd).
    //  2. The isScrubbing EXACT toggle raced the round-trip effect,
    //     which reset CLOSEST_SYNC on every drag frame — so the
    //     release position still snapped to a keyframe. Seek
    //     parameters are now owned exclusively by the handlers:
    //     CLOSEST_SYNC during the drag, one EXACT seek on release,
    //     CLOSEST_SYNC restored after.

    // The clip the EDITING surface (transforms, panels, colour grade)
    // targets: the user's selection, else the clip the player is on.
    // Playback itself follows the playlist and is NOT re-routed by
    // selection — tapping a clip seeks the player to that item (effect
    // below), which is how the old single-clip "selection loads the
    // preview" behaviour is preserved.
    val activeClip = uiState.selectedClip ?: playingClip

    // Per-clip volume: the clip currently PLAYING drives the player's
    // effective volume (0..2 with 1f = unity). The global `uiState.volume`
    // is a fallback for the no-clip case and is also what the legacy
    // "Volume" slider edits via the global state. The `isMuted` flag is a
    // hard override — Mute must always silence the player regardless of
    // the per-clip volume. Keyed on the PLAYING clip (was: the selected
    // one) so a playlist transition into a quieter clip is honoured.
    LaunchedEffect(
        uiState.isMuted, uiState.volume, playingClip?.id, playingClip?.volume
    ) {
        val perClipVolume = playingClip?.volume ?: uiState.volume
        exoPlayer.volume = if (uiState.isMuted) 0f
                           else perClipVolume.coerceIn(0f, 1f)
    }

    // 2026-10-13 — per-clip Voice Changer effect. Keyed on the PLAYING
    // clip, exactly like the volume above, so a playlist transition into a
    // clip with a different effect retargets the audio sink instead of
    // leaving the previous clip's robot voice running.
    //
    // The bridge is a `@Volatile` holder rather than a player property:
    // `setPlaybackParameters` is already owned by the effect below, and
    // giving the voice effect a second writer on the same player property
    // is the ownership violation this codebase has been bitten by. The
    // processors read the holder on every buffer instead, so the change is
    // audible on the next one — and `PlaybackParameters` is left alone, so
    // the speed-ramp preview behaviour is untouched.
    //
    // `?: VoiceEffect.NONE` covers the no-clip / not-playing case: with
    // nothing playing there is no clip whose voice to impersonate.
    LaunchedEffect(playingClip?.voiceEffect) {
        voicePreviewBridge.set(playingClip?.voiceEffect ?: VoiceEffect.NONE)
    }

    // Per-clip speed: the clip currently PLAYING drives the player's
    // playback rate; re-applied on every playlist transition and on any
    // speed change so a Speed → 2x → Speed → 1x toggle round-trips
    // correctly. The clamp matches the ViewModel's
    // [EditorViewModel.setClipSpeed] range.
    //
    // 2026-09-29 — a speed ramp cannot be previewed. `PlaybackParameters`
    // is a single constant rate applied by the player's clock, and the
    // only way to vary it in real time would be to drive
    // `setPlaybackParameters` from the position poller — which the
    // ownership invariant in MEMORY.md forbids (one writer per player
    // property, and `volume` already owns that poller). Rather than fake
    // it, the preview plays the ramp's **effective average** rate,
    // `source / output`, taken from the same interpolator the export
    // uses and therefore identical to the file's overall speed for the
    // same clip. The panel states this explicitly so it is never a
    // silent mismatch. `rampedPreviewSpeed` owns that computation.
    LaunchedEffect(
        playingClip?.id,
        playingClip?.speed,
        playingClip?.speedCurve,
        playingClip?.effectiveDurationMs
    ) {
        val s = rampedPreviewSpeed(playingClip).coerceIn(0.25f, 4f)
        exoPlayer.setPlaybackParameters(PlaybackParameters(s))
    }

    // ---- The preview playlist itself ----
    //
    // 2026-10-04 — replaces the old single-item `LaunchedEffect(mediaUri)`
    // that loaded only the selected clip. Every video clip becomes one
    // playlist item whose `ClippingConfiguration` IS the clip's trim
    // window — this is what finally makes TRIM visible in the preview
    // (previously trims existed only as timeline visuals + export).
    //
    // Rebuild policy: the effect re-runs when the clip ORDER/IDENTITY or
    // URIs change, or when `playlistRevision` is bumped by a trim-gesture
    // end (per-frame rebuilds during a trim drag would stutter the
    // decoder). On rebuild the playhead — editor-timeline time — is
    // resolved onto the new playlist so a trim or reorder never yanks
    // the user to position 0.
    LaunchedEffect(
        playlistClips.map { "${it.id}:${it.uri}" },
        playlistRevision
    ) {
        if (playlistClips.isEmpty()) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            return@LaunchedEffect
        }
        val desiredEditorPos = currentPositionMs
        val items = playlistClips.map { clip ->
            val trimEnd = clip.trimEndMs.takeIf { it > 0 }
            MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs.coerceAtLeast(0L))
                        .apply {
                            if (trimEnd != null && trimEnd > clip.trimStartMs) {
                                setEndPositionMs(trimEnd)
                            }
                        }
                        .build()
                )
                .build()
        }
        exoPlayer.setMediaItems(items)
        // Land on the same story the user was watching. The
        // first-frame priming (playWhenReady toggle + pause on READY)
        // below still applies — STATE_READY fires once for the new
        // playlist.
        val (index, windowPos) = PlaylistPosition.resolve(playlistClips, desiredEditorPos)
        playingIndex = index
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
        exoPlayer.seekTo(index, windowPos)
    }

    // Selecting a clip keeps the old "selection loads the preview"
    // behaviour: seek the playlist to that clip's start. A no-op when the
    // clip is already the one playing (e.g. the user tapped the clip that
    // just auto-transitioned in), so selection during playback does not
    // restart it.
    LaunchedEffect(uiState.selectedClipId, playlistClips) {
        val selectedId = uiState.selectedClipId ?: return@LaunchedEffect
        val index = playlistClips.indexOfFirst { it.id == selectedId }
        if (index >= 0 && exoPlayer.currentMediaItemIndex != index) {
            playingIndex = index
            exoPlayer.seekTo(index, 0L)
            val editorPos = PlaylistPosition.editorMs(playlistClips, index, 0L)
            currentPositionMs = editorPos
            viewModel.onPositionChanged(editorPos)
        }
    }

    // 2026-08-29: pause the player as soon as it reports STATE_READY
    // after a fresh media item is loaded. Combined with
    // `playWhenReady = true` + `seekTo(0)` above, this guarantees a
    // visible first frame before the user taps Play. Without this
    // effect the player would continue playing from position 0 the
    // moment a clip is imported, which is unexpected behaviour for
    // an editor.
    //
    // Project restoration: when a saved project provides a playhead
    // position ([EditorUiState.pendingRestoreSeekMs]), the first
    // STATE_READY seeks there instead of rewinding to 0. The value is
    // consumed exactly once. Read through `rememberUpdatedState` —
    // this DisposableEffect installs its listeners once per mediaUri,
    // so the captured `uiState` parameter would be stale by the time
    // READY fires (same staleness pattern as the poller above).
    val pendingRestoreSeekRef = rememberUpdatedState(uiState.pendingRestoreSeekMs)
    //
    // 2026-08-30 patch: we also listen for `onRenderedFirstFrame`
    // and `onSurfaceSizeChanged` so the first frame is re-pushed
    // exactly when the PlayerView's underlying SurfaceView produces
    // a non-zero-sized surface. This is the *real* fix for the
    // "preview is black until Blur is enabled" symptom: the
    // Compose-level `onSizeChanged` modifier on the AndroidView
    // fires when the AndroidView itself is measured, which happens
    // BEFORE the underlying SurfaceView has produced a usable
    // surface. Calling `seekTo(0L)` at that moment is a no-op
    // because there is nowhere for the decoded frame to go.
    // `onSurfaceSizeChanged(width, height)` with width>0 is the
    // earliest safe moment — the surface exists by then.
    // `onRenderedFirstFrame` is the *authoritative* signal that
    // a frame actually reached the surface. If we never see it,
    // the bug is in the surface path; if we see it but the screen
    // is still black, the bug is in the Compose draw layer
    // (typically an opaque overlay covering the surface).
    val hasPushedFirstFrameState = remember { mutableStateOf(false) }
    val onFirstFrameRendered = rememberUpdatedState {
        // Reached when ExoPlayer's renderer has actually output a
        // frame to the surface. Mark the gate so we stop listening
        // for this event on the same mediaUri.
        hasPushedFirstFrameState.value = true
    }
    // Re-keyed on the playlist signature (was: the single `mediaUri`) so
    // the READY/first-frame listener re-arms for every playlist rebuild.
    DisposableEffect(
        playlistClips.map { "${it.id}:${it.uri}" }, playlistRevision
    ) {
        // Reset the per-clip gate when the media changes.
        hasPushedFirstFrameState.value = false
        // The position the first-frame re-push should land on: the
        // restored playhead when a project reopen provides one,
        // 0 otherwise (fresh import / normal first frame). EDITOR-domain
        // time — resolved onto the playlist at seek time.
        var firstFrameTargetEditorMs = 0L
        val readyListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    if (userPlayRequested) {
                        // 2026-10-13 — honour the tap that arrived before
                        // READY instead of pausing it away. The seek back
                        // to the first frame / restored playhead is skipped
                        // too: the playlist-rebuild effect already seeked to
                        // the editor position this tap came from, and
                        // yanking back to 0 would restart the clip.
                        userPlayRequested = false
                    } else {
                        exoPlayer.playWhenReady = false
                        exoPlayer.pause()
                        // Project restoration: land the playhead on the
                        // position saved with the project instead of
                        // rewinding to 0. Consumed exactly once.
                        val restoreMs = pendingRestoreSeekRef.value
                        firstFrameTargetEditorMs = restoreMs ?: 0L
                        val (index, windowPos) = PlaylistPosition.resolve(
                            playlistClipsRef.value, firstFrameTargetEditorMs
                        )
                        exoPlayer.seekTo(index, windowPos)
                        if (restoreMs != null) viewModel.consumeRestoreSeekMs()
                    }
                    exoPlayer.removeListener(this)
                }
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                // The PlayerView's SurfaceView just produced a
                // non-zero-sized surface. This is the earliest safe
                // moment to re-push the cached first frame. Use the
                // same target as the restore seek so a late-arriving
                // surface can't rewind a restored playhead to 0.
                if (width > 0 && height > 0 && !hasPushedFirstFrameState.value) {
                    val (index, windowPos) = PlaylistPosition.resolve(
                        playlistClipsRef.value, firstFrameTargetEditorMs
                    )
                    exoPlayer.seekTo(index, windowPos)
                }
            }

            override fun onRenderedFirstFrame() {
                // Authoritative signal: a frame actually reached the
                // surface. Latch the gate so subsequent re-pushes
                // (e.g. on Blur toggle, which re-creates the
                // blur-layer PlayerView and triggers a fresh
                // surface) are no-ops.
                onFirstFrameRendered.value.invoke()
            }
        }
        exoPlayer.addListener(readyListener)
        onDispose { exoPlayer.removeListener(readyListener) }
    }

    val projectDuration = uiState.durationMs.coerceAtLeast(playerDurationMs)
    val selectedClip = uiState.selectedClip

    // ----- Root layout -----
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CinematicBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        EditorTopBarSection(
            title = uiState.project?.name ?: "Untitled",
            // 2026-09-19 EXPORT-GATE FIX — availability used to hinge
            // on the GLOBAL isLoading flag, which belongs to subtitle
            // generation: any in-flight (or natively-stalled)
            // transcription run dimmed Export to a dead button with
            // zero explanation. Export has its own state machine, so
            // gate on THAT: enabled when no export is running. (While
            // an export runs, use Cancel in the progress dialog.)
            canExport = uiState.project != null &&
                uiState.exportState is com.aicreatorstudio.app.export.ExportState.Idle,
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            onBackClick = onBackClick,
            onUndo = { viewModel.undo() },
            onRedo = { viewModel.redo() },
            onExportClick = onShowExportDialog
        )

        // Project-restore safety net: when the saved media is
        // unreachable, tell the user plainly and offer a relink
        // instead of a silent black preview.
        if (uiState.mediaMissingClips.isNotEmpty()) {
            MediaMissingBanner(
                missingNames = uiState.mediaMissingClips.map { it.fileName },
                onRelinkClick = {
                    runCatching { relinkLauncher.launch(arrayOf("video/*")) }
                }
            )
        }

        // 2026-10-05 — add-clip failure surface. The toast-less, honest
        // pattern the rest of the editor uses: a dismissible banner instead
        // of a silent no-op when a picked file can't be read/copied.
        if (uiState.addMediaError != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3A2E10))
                    .border(1.dp, Color(0xFF6B541A), RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFC94D),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = uiState.addMediaError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFE0A0),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { viewModel.consumeAddMediaError() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "بستن",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFE0A0)
                    )
                }
            }
        }

        // PHASE A (Persistence Integrity) — refused core edits. Same
        // honest banner pattern as addMediaError above: a guard that
        // says why, never a silent no-op.
        if (uiState.editorNotice != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3A2E10))
                    .border(1.dp, Color(0xFF6B541A), RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFC94D),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = uiState.editorNotice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFE0A0),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { viewModel.consumeEditorNotice() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "بستن",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFE0A0)
                    )
                }
            }
        }

        // 2026-10-13 — Extract Audio from Video failed. Same honest amber
        // banner pattern as addMediaError / editorNotice: the extraction
        // names its reason («این ویدیو ترک صوتی ندارد» …) instead of
        // leaving the row's spinner gone with no explanation.
        if (uiState.extractAudioError != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3A2E10))
                    .border(1.dp, Color(0xFF6B541A), RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFC94D),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = uiState.extractAudioError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFE0A0),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { viewModel.consumeExtractAudioError() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "بستن",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFE0A0)
                    )
                }
            }
        }

        // 2026-10-13 — positive confirmations (font import / delete).
        // Deliberately a *separate* banner from `editorNotice` and with a
        // success palette: this app has no Toast machinery, so the
        // dismissible banner is the only user-visible channel, and a
        // "فونت با موفقیت اضافه شد" rendered in the amber *warning* colour
        // would read as a problem. Same layout, same dismissal contract.
        if (uiState.editorConfirmation != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF102A17))
                    .border(1.dp, Color(0xFF1E5A32), RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF5BD98A),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = uiState.editorConfirmation.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB7F5CE),
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { viewModel.consumeEditorConfirmation() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "بستن",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFB7F5CE)
                    )
                }
            }
        }

        EditorVideoPreviewSection(
            player = exoPlayer,
            isPlaying = isPlaying,
            hasClip = activeClip != null,
            // 2026-10-21 — the preview's rotation must come from the
            // SAME field the export reads, or the preview and the file
            // disagree.
            //
            // The export builds `rotationDegrees` from
            // `clip.rotation + clip.transform.rotation`
            // (`RenderGraphBuilder.toRenderSegment`). The preview used
            // to add the *global* `uiState.rotation` mirror instead of
            // the clip's own rotation — so rotating a clip in the panel
            // moved a number the export never saw, and a clip rotated
            // elsewhere rendered differently on screen than in the file.
            rotation = activeClip?.rotation ?: 0,
            canvasPreset = uiState.canvasPreset,
            canvasBackground = uiState.canvasBackground,
            sourceClip = activeClip,
            sourceAspect = if (activeClip != null && activeClip.width > 0 && activeClip.height > 0)
                activeClip.width.toFloat() / activeClip.height.toFloat() else 16f / 9f,
            activeSubtitle = uiState.textOverlays.firstOrNull { it.id == uiState.activeSubtitleId },
            // 2026-10-30 — the `activeWord` argument is gone. The ViewModel
            // computed `activeWordId` on every position change, this call
            // site turned it into a TranscribedWord, and the composable
            // never read the parameter: the shared caption renderer derives
            // the active word from `frameTimeMs` itself. So the whole chain
            // was per-frame work feeding a dead parameter.
            // Per-clip visual state. The Filters panel sets the global
            // selectedEffect but the per-clip `videoEffect` wins when a
            // clip is selected, so the chosen effect stays attached to
            // the clip itself across selection changes.
            selectedEffect = uiState.selectedEffect,
            perClipEffect = activeClip?.videoEffect,
            brightness = activeClip?.brightness ?: uiState.brightness,
            contrast = activeClip?.contrast ?: uiState.contrast,
            saturation = activeClip?.saturation ?: uiState.saturation,
            opacity = activeClip?.opacity ?: 1f,
            animation = activeClip?.animation,
            // 2026-08-29: full Lumetri color grading state. Per-clip
            // override wins when a clip is selected so the grade is
            // persisted with the clip.
            colorGrading = activeClip?.colorGrading ?: uiState.colorGrading,
            // 2026-08-29: when the Adjust / Color Grading panel is
            // open, the floating ColorScope overlay is shown.
            isAdjustPanelOpen = uiState.toolPanel == ToolPanel.ADJUST,
            // 2026-08-29: when the user holds the "Compare" button
            // the preview reverts to the original (no grade applied)
            // so they can A/B the before/after.
            isComparing = isComparing,
            currentPositionMs = currentPositionMs,
            // 2026-10-13 — the video progress bar's denominator. Same value
            // the timeline and the sub-panel sheet use, so the bar, the
            // ruler and the sheet agree on how long the project is.
            totalDurationMs = projectDuration,
            onClipTransformChanged = { scale, offsetX, offsetY, rotation, flipH, flipV ->
                activeClip?.id?.let {
                    viewModel.setClipTransform(
                        it, scale = scale, offsetX = offsetX, offsetY = offsetY,
                        rotation = rotation, flipH = flipH, flipV = flipV
                    )
                }
            },
            // AI Upscaler state for the live preview. Per-clip
            // settings win when a clip is selected so the user's
            // chosen preset/strength is bound to that clip.
            upscalerSettings = if (activeClip != null &&
                activeClip.upscale != com.aicreatorstudio.app.model.UpscalerSettings.Identity
            ) activeClip.upscale else uiState.upscale,
            isUpscalerPanelOpen = uiState.toolPanel == ToolPanel.UPSCALER,
            onUpscaleSplitChanged = viewModel::setUpscaleSplitPosition,
            // 2026-09-01: pass uiState so the preview can surface
            // the "BG Remove ready" chip when the matting engine
            // has finished at least one run for the selected clip.
            uiState = uiState,
            // PHASE B5.5 — direct text manipulation. The preview
            // surface draws a sibling selection chrome and
            // accepts tap / drag / pinch / rotate gestures that
            // route back through the ViewModel.
            textOverlays = uiState.textOverlays,
            selectedTextId = uiState.selectedTextId,
            onSelectTextOverlay = { id -> viewModel.selectTextOverlay(id) },
            // 2026-09-06 live-drag: per-frame updates are state-only
            // (no history push / Room write), so the gesture never
            // restarts and the text follows the finger 1:1.
            onUpdateTextPosition = { id, x, y ->
                viewModel.updateTextPositionLive(id, x, y)
            },
            onUpdateTextStyle = { id, fontSizeSp, rotation ->
                viewModel.updateTextStyleLive(
                    textId = id,
                    fontSizeSp = fontSizeSp,
                    rotation = rotation
                )
            },
            onTextDragStarted = { viewModel.beginTextTransform() },
            onTextDragEnded = { viewModel.endTextTransform() },
            // 2026-09-06 — empty-canvas tap selects the video clip.
            onEmptyCanvasTap = {
                viewModel.selectClip(activeClip?.id)
                // 2026-10-13 — and drops the PiP selection, so tapping the
                // picture is a reliable way out of the mask panel's target.
                viewModel.selectOverlay(null)
            },
            onTrackingRoiChanged = { viewModel.setPendingTrackingRoi(it) },
            // 2026-10-13 — PiP overlays. The same `uiState.overlays` list
            // the export reads through `RenderSegment.mediaOverlays`, so
            // the preview and the file cannot be looking at different
            // overlays.
            pipOverlays = uiState.overlays,
            selectedOverlayId = uiState.selectedOverlayId,
            pipPlayer = pipPlayer,
            onSelectOverlay = { id -> viewModel.selectOverlay(id) },
            onOverlayTransformStarted = { viewModel.beginOverlayTransform() },
            onOverlayTransform = { id, x, y, scale, rotation ->
                // Per-frame: state-only, no undo push and no Room write.
                // The gesture brackets the whole drag with
                // `beginOverlayTransform` / `endOverlayTransform`.
                viewModel.updateOverlayTransform(id, x, y, scale, rotation)
            },
            onOverlayTransformEnded = { viewModel.endOverlayTransform() },
            modifier = Modifier
                .fillMaxWidth()
                // 2026-09-13 — the preview keeps a healthy MINIMUM
                // height (180dp) even while the sub-panel sheet is
                // open at half the screen. With the panel closed the
                // weight(1f) still gives the preview all the space
                // the timeline/dock leave behind, exactly as before.
                .heightIn(min = 180.dp)
                .weight(1f, fill = true)
        )

        EditorPlaybackStripSection(
            isPlaying = isPlaying,
            positionMs = currentPositionMs,
            durationMs = projectDuration,
            onPlayPause = {
                if (isPlaying) {
                    exoPlayer.pause()
                    // 2026-09-13 — the audio stems pause with the master.
                    audioMixer.setPlaying(false)
                    // 2026-10-13 — so do the PiP overlay players.
                    pipPlayer.setPlaying(false)
                } else {
                    // 2026-10-13 — nothing in the timeline means `play()`
                    // is a silent no-op on an IDLE player: no frame, no
                    // sound, no error. Say so instead of letting the user
                    // conclude the button is broken.
                    if (playlistClips.isEmpty()) {
                        viewModel.onPlayerError(
                            SubtitleMessages.Playback.NOTHING_TO_PLAY_FA
                        )
                        return@EditorPlaybackStripSection
                    }
                    // Arm the latch BEFORE play(): if the media item is
                    // still preparing, the STATE_READY handler below reads
                    // this and declines to auto-pause.
                    userPlayRequested = true
                    exoPlayer.play()
                    // 2026-09-13 — audio stems resume from wherever
                    // syncTo parked them (the playhead position).
                    audioMixer.syncTo(currentPositionMs)
                    audioMixer.setPlaying(true)
                    pipPlayer.syncTo(currentPositionMs)
                    pipPlayer.setPlaying(true)
                }
            },
            // 2026-09-06: shared throttled scrub pipeline (same
            // handlers the timeline playhead uses) — pause during
            // the drag, ~15 Hz CLOSEST_SYNC seeks, one EXACT seek
            // + settle gate on release.
            onScrubStart = handleScrubStart,
            onScrubSeek = handleScrubSeek,
            onScrubEnd = handleScrubEnd,
            onSeek = { ms -> viewModel.onSeekTo(ms) }
        )

        uiState.errorMessage?.let { msg ->
            ErrorBanner(
                message = msg,
                onDismiss = { viewModel.consumeError() }
            )
        }

        // Hide the timeline when a tool panel is active so the video
        // preview expands to fill the freed space and the tool controls
        // have room above the dock.  The timeline state (playhead,
        // zoom, track visibility, clip selection) lives in the
        // ViewModel so nothing is lost when the composable leaves
        // composition — it simply re-composes from saved state when
        // the user closes the tool panel.
        // 2026-09-30 — the timeline now ANIMATES out instead of vanishing.
        // Removing it in a single frame while the preview was still
        // re-measuring read as a layout jump; collapsing it towards the top
        // (the edge it is anchored to) lets the preview above it absorb the
        // space smoothly.
        AnimatedVisibility(
            visible = uiState.toolPanel == null,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            EditorTimelineSection(
                uiState = uiState,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                durationMs = projectDuration,
                onClipSelected = { viewModel.selectClip(it) },
                // 2026-09-06 — Video Audio lane selection.
                onAudioClipSelected = { id ->
                    viewModel.selectAudioClip(id)
                },
                // 2026-10-20 — drag-to-move on the audio lane. Same undo
                // coalescing as a clip drag: the first frame of a gesture
                // pushes one history entry, the rest are free. `onDragEnd`
                // clears both latches on release, so a second gesture — in
                // particular one after an undo — pushes again.
                onAudioClipMoved = { id, ms ->
                    val pushHistory = lastDraggedAudioId != id
                    lastDraggedAudioId = id
                    viewModel.moveAudioClip(id, ms, pushHistory)
                },
                onDragEnd = {
                    lastDraggedAudioId = null
                    lastDraggedClipId = null
                },
                onClipMoved = { id, ms ->
                    val pushHistory = lastDraggedClipId != id
                    lastDraggedClipId = id
                    viewModel.updateClipPosition(id, ms, pushHistory)
                },
                onClipTrimmed = { id, start, end ->
                    val pushHistory = lastTrimmedClipId != id
                    lastTrimmedClipId = id
                    viewModel.setClipTrim(id, start, end, pushHistory)
                },
                // Trim gesture ended: reset the undo coalescing latch and
                // rebuild the preview playlist so the new trim window is
                // baked into the player's ClippingConfiguration.
                onClipTrimEnd = {
                    lastTrimmedClipId = null
                    playlistRevision++
                },
                onAddClip = { addClipLauncher.launch(arrayOf("video/*")) },
                isAddingClip = uiState.isAddingMedia,
                audioWaveforms = audioWaveforms,
                clipThumbnails = clipThumbnails,
                onSeek = { ms -> viewModel.onSeekTo(ms) },
                // 2026-09-06: shared throttled scrub pipeline (same
                // handlers the playback-strip slider uses).
                onScrubStart = handleScrubStart,
                onScrubSeek = handleScrubSeek,
                onScrubEnd = handleScrubEnd,
                onZoomChange = { viewModel.onTimelineZoomChange(it) },
                onToggleTrack = { viewModel.toggleTrackVisibility(it) },
                // PHASE B3 — subtitle lane tap now routes here
                // instead of onClipSelected. The previous wiring
                // (which routed subtitle taps to the video-clip
                // selector) was a silent bug: clicking a
                // subtitle would either do nothing (if the
                // id didn't match a video clip) or select the
                // wrong clip.
                //
                // 2026-09-28 IA (Q2) — tapping a text/subtitle tile now
                // only SELECTS it. It deliberately no longer auto-opens a
                // tool panel: that hid the video preview and pre-empted the
                // user's next move. The contextual toolbar switches to the
                // text tool set instead (Fonts & Typography / Animation &
                // Styles), so the same controls are one tap away without
                // covering the canvas.
                onTextOverlaySelected = { id ->
                    viewModel.selectTextOverlay(id)
                },
                // 2026-09-29 — beat onsets for the drag/trim snap. Recomputed
                // from state rather than cached in a local: it is a cheap
                // fold over the audio clips and a stale list would let a drag
                // snap to markers that were cleared a moment earlier.
                beatMarkers = viewModel.allBeatMarkersOnTimeline(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = TimelineMaxHeight)
            )
        }

        // ----- Sub-panel slides up over the dock when a tool is selected -----
        // 2026-09-30 — `AnimatedVisibility` finally makes the "slides up"
        // comment above true. `expandVertically(expandFrom = Bottom)` grows
        // the sheet out of the dock edge and `shrinkVertically(shrinkTowards =
        // Bottom)` collapses it back, so the weighted video preview above
        // resizes smoothly instead of snapping.
        //
        // This is deliberately NOT `if (uiState.toolPanel != null) { … }` any
        // more: a plain `if` removes the sheet from composition in the frame
        // the panel closes, so no exit transition could ever play. The
        // content survives the animation thanks to the `lastPanelState` latch
        // inside `SubPanelRow`.
        AnimatedVisibility(
            visible = uiState.toolPanel != null,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
        ) {
            SubPanelRow(
                uiState = uiState,
                currentPositionMs = currentPositionMs,
                projectDuration = projectDuration,
                onClose = { viewModel.setToolPanel(null) },
                onVolumeChanged = { id, v -> viewModel.updateClipVolume(id, v) },
                onBrightnessChanged = viewModel::onBrightnessChanged,
                onContrastChanged = viewModel::onContrastChanged,
                onSaturationChanged = viewModel::onSaturationChanged,
                onPlaybackSpeedChanged = viewModel::onPlaybackSpeedChanged,
                onRotationChanged = viewModel::onRotationChanged,
                onRotate90 = viewModel::rotate90Degrees,
                onTrimStartChanged = viewModel::onTrimStartChanged,
                onTrimEndChanged = viewModel::onTrimEndChanged,
                onClipTrimChanged = { id, start, end -> viewModel.setClipTrim(id, start, end) },
                // 2026-10-05 — AUTO-CAPTION one-tap entry (SUBTITLES panel).
                onAutoCaption = { templateId -> viewModel.runAutoCaption(templateId) },
                onToggleTrimMode = viewModel::toggleTrimMode,
                onResetEffects = viewModel::resetEffects,
                onAddText = { text, start, end, font ->
                    viewModel.addTextOverlay(text, start, end, font.name)
                },
                // 2026-09-06 — Text tool «اعمال در محل پلی‌هد»: add at
                // the playhead and select the new overlay.
                onApplyText = { text, start, end, fontName ->
                    viewModel.applyTextAtPlayhead(text, start, end, fontName)
                },
                // 2026-09-06 — Paragraph-panel editing of the selected
                // text / subtitle overlay.
                onUpdateSelectedFont = { fontName ->
                    viewModel.updateSelectedTextFont(fontName)
                },
                onUpdateSelectedFontSize = { size ->
                    viewModel.updateSelectedTextFontSize(size)
                },
                onUpdateSelectedColor = { argb ->
                    viewModel.updateSelectedTextColor(argb)
                },
                onApplySelectedStyle = { style ->
                    viewModel.applyViralStyleToSelected(style)
                },
                // 2026-09-08 — Text panel style tab «اعمال روی همه متن‌ها».
                onApplyStyleToAllTexts = { style ->
                    viewModel.applyViralStyleToAll(style)
                },
                onUpdateSelectedRotation = { deg ->
                    viewModel.updateSelectedTextRotation(deg)
                },
                onUpdateSelectedStroke = { enabled ->
                    viewModel.updateSelectedTextStroke(enabled)
                },
                onUpdateSelectedShadow = { enabled ->
                    viewModel.updateSelectedTextShadow(enabled)
                },
                onUpdateSelectedBackground = { enabled ->
                    viewModel.updateSelectedTextBackground(enabled)
                },
                onUpdateSelectedAnimation = { anim ->
                    viewModel.updateSelectedTextAnimation(anim)
                },
                onUpdateSelectedDuration = { ms ->
                    viewModel.updateSelectedTextDuration(ms)
                },
                onMoveSelectedTextToPlayhead = {
                    viewModel.moveSelectedTextToPosition(currentPositionMs)
                },
                // 2026-09-06 — Paragraph tool wiring: position-grid
                // anchor lands the new block; snap/nudge move the
                // selected overlay.
                onAddParagraph = { text, start, end, font, x, y ->
                    viewModel.addParagraphOverlay(text, start, end, x, y, font.name)
                },
                onSnapSelectedText = { x, y -> viewModel.snapSelectedTextPosition(x, y) },
                onNudgeSelectedText = { dx, dy -> viewModel.nudgeSelectedTextPosition(dx, dy) },
                onEffectSelected = viewModel::selectVideoEffect,
                onSetTransition = { scope, cfg ->
                    uiState.selectedClipId?.let { id ->
                        viewModel.setClipTransition(id, scope, cfg)
                    }
                },
                onPickMusic = { audioPickerLauncher.launch("audio/*") },
                // 2026-10-13 — Extract Audio from Video. Lives in BOTH the
                // root Audio panel and the audio toolbar, because the
                // toolbar is only reachable once an audio object is
                // selected — a project with no audio yet needs the panel
                // row.
                onExtractAudioFromVideo = {
                    extractAudioLauncher.launch(arrayOf("video/*"))
                },
                onClipAnimationChanged = { id, anim ->
                    viewModel.updateClipAnimation(id, anim)
                },
                onClipCropChanged = { id, preset ->
                    viewModel.updateClipCrop(id, preset, preset.aspectRatio)
                },
                onSetClipSpeed = { id, speed ->
                    viewModel.setClipSpeed(id, speed)
                },
                onGenerateSubtitles = { font ->
                    // PHASE B3 — the public entry point is now
                    // requestGenerateSubtitles, which is gated by
                    // the state machine. The internal
                    // `generateSubtitles` is reserved for the
                    // instrumentation test and for direct calls
                    // from the dialog flow.
                    viewModel.requestGenerateSubtitles(font)
                },
                onSelectWhisperModel = { model ->
                    viewModel.setWhisperModel(model)
                },
                // 2026-09-06 — model download for the Subs card rows.
                onDownloadWhisperModel = { spec ->
                    viewModel.downloadWhisperModel(spec)
                },
                // 2026-09-06 — per-clip mute toggle (Volume panel).
                onToggleMuteSelected = {
                    viewModel.toggleMuteForSelectedClip()
                },
                // 2026-09-06 — per-audio-object setters (Video Audio /
                // Music / SFX lanes) for the Volume panel.
                onUpdateAudioClipVolume = { id, v ->
                    viewModel.updateAudioClipVolume(id, v)
                },
                onUpdateAudioClipMute = { id, muted ->
                    viewModel.updateAudioClipMute(id, muted)
                },
                // 2026-09-06 — subtitle language picker: store in
                // uiState + push onto the Whisper engine.
                onSubtitleLanguageSelected = { lang ->
                    viewModel.setSubtitleLanguage(lang)
                },
                // 2026-09-15 — words-per-caption (Premiere-style):
                // persist + expose for the Subs card chips.
                onSelectWordsPerCaption = { count ->
                    viewModel.setSubtitleWordsPerCaption(count)
                },
                onExportSrt = {
                    viewModel.onExportSrtClicked()
                },
                // PHASE B4 — SRT import. Same shape as
                // onExportSrt: the picker is a one-shot
                // trigger; the ViewModel does the heavy
                // lifting on IO.
                onImportSrt = {
                    viewModel.onImportSrtClicked()
                },
                onConsumeSrtImportState = {
                    viewModel.consumeSrtImportState()
                },
                // PHASE B3 — AI Subtitle card handlers.
                onCancelSubtitleGeneration = { viewModel.cancelSubtitleGeneration() },
                onUpdateTextOverlay = { id, newText ->
                    viewModel.updateTextOverlay(id, newText)
                },
                onDeleteTextOverlay = { id -> viewModel.deleteTextOverlay(id) },
                onDeleteAllAiSubtitles = { viewModel.deleteAllAiSubtitles() },
                onClearTextSelection = { viewModel.selectTextOverlay(null) },
                // 2026-10-13 — CapCut-style subtitle editing workflow.
                // Each relays straight to the matching ViewModel
                // action, which routes through SubtitleEditOps and the
                // existing undo/redo history.
                onSplitSubtitle = { id, wordIndex ->
                    viewModel.splitSubtitleAt(id, wordIndex)
                },
                onMergeSubtitleWithNext = { id -> viewModel.mergeSubtitleWithNext(id) },
                onRetimeSubtitle = { id, startMs, endMs ->
                    viewModel.retimeSubtitle(id, startMs, endMs)
                },
                onRetimeSubtitleWord = { id, index, startMs, endMs ->
                    viewModel.retimeSubtitleWord(id, index, startMs, endMs)
                },
                onResyncSubtitleText = { id -> viewModel.resyncSubtitleTextFromWords(id) },
                // 2026-10-15 — global subtitle font size. The slider
                // drag is one history snapshot (begin) + state-only
                // live updates + one persist (end); the chips and the
                // apply button are discrete single-push calls.
                onBeginGlobalSubtitleResize = { viewModel.beginGlobalSubtitleResize() },
                onGlobalSubtitleSizeLive = { sizeSp -> viewModel.updateGlobalSubtitleSizeLive(sizeSp) },
                onEndGlobalSubtitleResize = { viewModel.endGlobalSubtitleResize() },
                onSetGlobalSubtitleSize = { sizeSp -> viewModel.setGlobalSubtitleFontSize(sizeSp) },
                onScaleAllSubtitles = { factor -> viewModel.scaleAllSubtitles(factor) },
                // 2026-10-13 — Custom Font Importer. The strip raises a
                // content-URI string (the ActivityResultContract has
                // already converted the picked document); the ViewModel
                // re-parses it. Delete passes the BARE font id, which is
                // what the repository keys on.
                customFonts = uiState.customFonts,
                onImportCustomFont = { uri -> viewModel.importCustomFont(android.net.Uri.parse(uri)) },
                onDeleteCustomFont = { fontId -> viewModel.deleteCustomFont(fontId) },
                // PHASE B5 — production subtitle styling wiring.
                // The ViewModel reads `selectedTextId` from
                // UI state to find which overlay to mutate,
                // so the id is ignored here.
                onApplySubtitleStyle = { _, style ->
                    viewModel.applySubtitleStyleToSelected(style)
                },
                onApplySubtitleStyleToAll = { style ->
                    viewModel.applySubtitleStyleToAllSubtitles(style)
                },
                onPickOverlay = { overlayPickerLauncher.launch(arrayOf("image/*", "video/*")) },
                onCanvasPresetChanged = viewModel::setCanvasPreset,
                onCanvasBackgroundChanged = viewModel::setCanvasBackground,
                // 2026-10-13 — video progress bar (timeline indicator /
                // story timer). `push` is false for the live drag updates
                // and true for the single commit on release, so one slider
                // drag costs one undo entry instead of one per frame.
                onProgressBarSettingsChanged = { settings, push ->
                    viewModel.setProgressBarSettings(settings, pushHistory = push)
                },
                // 2026-10-13 — voice changer. One tap on a preset card is
                // one deliberate edit, so the ViewModel pushes an undo
                // snapshot and persists on every call; there is no live
                // drag path to split out here.
                onVoiceEffectChanged = { clipId, effect ->
                    viewModel.setClipVoiceEffect(clipId, effect)
                },
                // 2026-10-13 — PiP shape mask. `push` is false for the live
                // slider drag and true for the single commit on release, so
                // one drag costs one undo entry instead of one per frame.
                onOverlayMaskChanged = { overlayId, settings, push ->
                    viewModel.setOverlayMask(overlayId, settings, pushHistory = push)
                },
                // 2026-10-13 — chroma key. Same live/commit split as the
                // mask above: `push` is false while a slider is dragged and
                // true for the single commit on release, so one drag costs
                // one undo entry instead of one per frame.
                onOverlayChromaChanged = { overlayId, settings, push ->
                    viewModel.setOverlayChromaKey(overlayId, settings, pushHistory = push)
                },
                onClipFillModeChanged = { id, mode ->
                    viewModel.setClipFillMode(id, mode)
                },
                onClipTransformChanged = { id, scale, offsetX, offsetY, rotation, flipH, flipV ->
                    viewModel.setClipTransform(
                        clipId = id,
                        scale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        rotation = rotation,
                        flipH = flipH,
                        flipV = flipV
                    )
                },
                onResetClipTransform = viewModel::resetClipTransform,
                onClipFadeInChanged = { id, ms -> viewModel.setClipFadeInMs(id, ms.toLong()) },
                onClipFadeOutChanged = { id, ms -> viewModel.setClipFadeOutMs(id, ms.toLong()) },
                onClipOpacityChanged = { id, o -> viewModel.setClipOpacity(id, o) },
                onColorGradingChanged = { viewModel.onColorGradingChanged(it) },
                onResetColorGrading = { viewModel.resetColorGrading() },
                onBeforeAfterChange = { comparing -> isComparing = comparing },
                // AI Upscaler hooks
                onUpscalerPresetChanged = viewModel::setUpscalerPreset,
                onUpscalerStrengthChanged = viewModel::setUpscalerStrength,
                onResetUpscaler = viewModel::resetUpscaler,
                onUpscaleExportClicked = viewModel::onUpscaleExportClicked,
                // 2026-09-01 AI tools — Background Remove / Auto Frame / Voice Clean.
                // Each AI panel collects its own options and calls
                // back through here; the ViewModel owns the
                // coroutine + state machine.
                onRunAiTool = { id, opts -> viewModel.runAiTool(id, opts) },
                onCancelAiTool = { id -> viewModel.cancelAiTool(id) },
                onResetAiTool = { id -> viewModel.resetAiTool(id) },
                onEnsureMattingModelReady = { viewModel.ensureMattingModelReady() },
                onMattingBackgroundModeChanged = { viewModel.setMattingBackgroundMode(it) },
                onEnsureAutoframeModelReady = { viewModel.ensureAutoframeModelReady() },
                autoframeModelStatusText = viewModel.aiToolModelStatus("autoframe"),
                autoframeModelAvailable = viewModel.aiToolIsAvailable("autoframe"),
                // Motion Tracking: no model to install — the engine is
                // pure Kotlin + an optional OpenCV native backend, so
                // instead of a model-status line the panel shows which
                // tracker backend this device resolved.
                pendingTrackingRoi = uiState.pendingTrackingRoi,
                motionTrackBackendText = viewModel.motionTrackBackendText(),
                onTrackingRoiChanged = { viewModel.setPendingTrackingRoi(it) },
                onEnterTrackingSelection = { viewModel.enterTrackingSelection() },
                onExitTrackingSelection = { viewModel.exitTrackingSelection() },
                mattingModelAvailable = viewModel.aiToolIsAvailable("matting"),
                // AI Upscale (ESRGAN): model state + Generate wiring.
                upscaleModelState = viewModel.aiToolState("upscale"),
                upscaleModelReady = viewModel.upscaleEsrganReady(),
                upscaleModelStatusText = viewModel.upscaleEsrganStatusText(),
                onEnsureUpscaleModelReady = { viewModel.ensureUpscaleModelReady() },
                upscaleProgress = uiState.upscaleProgress,
                effectiveUpscaleProfileLabel = run {
                    val selected =
                        uiState.selectedUpscaleProfile ?: UpscaleProfile.Auto
                    if (selected is UpscaleProfile.Auto) {
                        "Auto (${viewModel.autoResolvedUpscaleProfile().displayName})"
                    } else {
                        selected.displayName
                    }
                },
                onUpscaleGenerateClicked = { viewModel.onUpscaleStartClicked() },
                onUpscaleCancelClicked = { viewModel.onUpscaleCancel() },
                onDismissUpscaleResult = { viewModel.consumeUpscaleProgress() },
                onPickBackgroundMedia = {
                    backgroundPickerLauncher.launch(arrayOf("image/*", "video/*"))
                },
                // 2026-09-10 AI OCR wiring.
                onEnsureOcrModelReady = { viewModel.ensureOcrModelReady() },
                ocrModelStatusText = viewModel.aiToolModelStatus("ocr"),
                ocrModelAvailable = viewModel.aiToolIsAvailable("ocr"),
                onApplyOcr = { text, ts, lines, conf ->
                    viewModel.applyOcrResult(text, ts, lines, conf)
                },
                // 2026-09-11 «زیرنویس فارسی» studio wiring.
                onApplySubtitleTemplate = { template ->
                    viewModel.applySubtitleTemplateToAll(template)
                },
                onPickStudioTemplate = { template ->
                    viewModel.saveStudioTemplate(template)
                },
                onAddTemplateSubtitle = { text, template, startMs, endMs ->
                    viewModel.addTemplateSubtitle(text, template, startMs, endMs)
                },
                onOpenAutoSubtitles = { viewModel.setToolPanel(ToolPanel.SUBTITLES) },
                studioTemplateId = viewModel.studioTemplate()?.id,
                // 2026-09-21 — AI Auto Silence Cut wiring.
                onAutoCutSilence = { threshold, pad, minSil ->
                    viewModel.autoCutSilence(threshold, pad, minSil)
                },
                // PHASE 1 — analyze → preview → apply (non-destructive).
                onAnalyzeSilence = { threshold, pre, post, minSil ->
                    viewModel.analyzeSilence(threshold, pre, post, minSil)
                },
                onApplySilencePreview = { viewModel.applySilencePreview() },
                onDiscardSilencePreview = { viewModel.discardSilencePreview() },
                onToggleSilenceSegment = { id -> viewModel.toggleSilenceSegment(id) },
                onSelectAllSilence = { all -> viewModel.setAllSilenceSelected(all) },
                onAnalyzeJumpCut = { threshold, pre, post, minSil ->
                    viewModel.analyzeJumpCut(threshold, pre, post, minSil)
                },
                // PHASE 4 — voice enhancement preview flow.
                onPreviewVoiceEnhancement = { preset -> viewModel.previewVoiceEnhancement(preset) },
                onApplyVoiceEnhancement = { viewModel.applyVoiceEnhancement() },
                onDiscardVoiceEnhancement = { viewModel.discardVoiceEnhancement() },
                onClearVoiceEnhancement = { viewModel.clearVoiceEnhancement() },
                onCancelVoiceEnhance = { viewModel.cancelVoiceEnhance() },
                // PHASE 5 — smart reframe preview flow.
                onAnalyzeReframe = { w, h -> viewModel.analyzeReframe(w, h) },
                onApplyReframe = { viewModel.applyReframe() },
                onDiscardReframe = { viewModel.discardReframe() },
                onClearReframe = { viewModel.clearReframe() },
                onCancelReframe = { viewModel.cancelReframe() },
                onCancelAutoCutSilence = { viewModel.cancelAutoCutSilence() },
                onClearAutoCutSilenceResult = { viewModel.clearAutoSilenceCutResult() },
                // 2026-09-29 — Speed Ramping. Both funnel through the
                // ViewModel so the ramp is persisted with the clip and
                // pushed onto the undo stack like every other edit.
                onSpeedCurveChanged = { id, curve -> viewModel.setClipSpeedCurve(id, curve) },
                onClearSpeedCurve = { id -> viewModel.clearClipSpeedCurve(id) },
                // 2026-09-29 — Beat Detection & timeline markers.
                onDetectBeats = { sensitivity ->
                    uiState.selectedAudioClipId?.let { id ->
                        viewModel.detectBeats(id, sensitivity)
                    }
                },
                onCancelBeats = {
                    uiState.selectedAudioClipId?.let { viewModel.cancelBeatDetection(it) }
                },
                onClearBeats = {
                    uiState.selectedAudioClipId?.let { viewModel.clearBeats(it) }
                },
                onAutoSplitOnBeats = {
                    val audioId = uiState.selectedAudioClipId
                    val videoId = uiState.selectedClipId
                    if (audioId != null && videoId != null) {
                        viewModel.autoSplitOnBeats(videoId, audioId)
                    }
                },
                // PHASE 6 — beat sync suggestion flow.
                onAnalyzeBeatSync = { mode -> viewModel.analyzeBeatSync(mode) },
                onEnsureBeatSyncBeats = {
                    uiState.selectedAudioClipId?.let { id ->
                        viewModel.detectBeats(id, com.aicreatorstudio.app.util.BeatDetector.BeatSensitivity.Default)
                    }
                },
                onCancelBeatSync = { viewModel.cancelBeatSync() },
                onApplyBeatSync = { viewModel.applyBeatSync() },
                onDiscardBeatSync = { viewModel.discardBeatSync() },
                onToggleBeatCandidate = { id -> viewModel.toggleBeatCandidate(id) },
                onSelectAllBeats = { all -> viewModel.setAllBeatSelected(all) },
                // PHASE 7 — smart highlights.
                onAnalyzeHighlights = { viewModel.analyzeHighlights() },
                onCancelHighlightAnalysis = { viewModel.cancelHighlightAnalysis() },
                onApplyHighlights = { viewModel.applyHighlights() },
                onDiscardHighlights = { viewModel.discardHighlights() },
                onClearHighlights = { viewModel.clearHighlights() },
                onToggleHighlight = { id -> viewModel.toggleHighlight(id) },
                onSelectAllHighlights = { all -> viewModel.setAllHighlightsSelected(all) },
                onSeekHighlight = { id -> viewModel.seekHighlight(id) },
                onAudioClipSelected = { id -> viewModel.selectAudioClip(id) }
            )
        }

        // 2026-09-20 — sliding bottom-sheet subtitle editor.
        // Appears when the user taps a subtitle on the timeline.
        com.aicreatorstudio.app.ui.screens.editor.SubtitleEditBottomSheet(
            selectedTextOverlay = uiState.textOverlays
                .firstOrNull { it.id == uiState.selectedTextId },
            onUpdateTextOverlay = { id, newText ->
                viewModel.updateTextOverlay(id, newText)
            },
            onDeleteTextOverlay = { id ->
                viewModel.deleteTextOverlay(id)
            },
            onDismiss = { viewModel.selectTextOverlay(null) }
        )

        // Safe gap above the dock so it doesn't crowd the gesture bar.
        // 14dp balances reachability on tall phones against screen
        // efficiency on compact ones, and stacks with the
        // navigationBars insets already applied on the root Column.
        Spacer(Modifier.height(14.dp))

        // 2026-09-24 — the SELECTED model's network precondition, read
        // directly above the tool dock: cloud transcription cannot
        // reach Groq from an Iranian IP, so the user must enable their
        // sanctions-bypass route first. Driven by the selection, so it
        // appears the moment the model row is tapped and clears when
        // an on-device model is chosen.
        uiState.selectedWhisperModel.networkNoticeFa?.let { notice ->
            AiModelNetworkBanner(message = notice)
        }

        EditorBottomDockSection(
            // 2026-09-28 IA — the dock is now a pure function of the
            // selection. `editorContext` derives ROOT / CLIP / AUDIO / TEXT
            // from the three mutually-exclusive selection ids.
            context = uiState.editorContext,
            selectedPanel = uiState.toolPanel,
            // Q7 — a transition needs two clips to join, so the Transitions
            // chip is withheld when the selected clip has no neighbour. Uses
            // the same tolerance as the timeline's transition handle.
            hasAdjacentClip = hasAdjacentClip(
                clips = uiState.project?.clips.orEmpty(),
                clipId = uiState.selectedClipId
            ),
            onPanelSelected = { enum ->
                // 2026-09-28 IA FIX — hierarchical track expansion used to be
                // duplicated right here *and* in
                // EditorViewModel.revealTracksForTool, and the two copies
                // disagreed on both the revealed lanes and the focused lane.
                // setToolPanel now owns it via the pure model.tracksForTool
                // mapping, so there is exactly one implementation.
                viewModel.setToolPanel(enum)
            },
            onAction = { actionId ->
                when (actionId) {
                    "split" -> {
                        // 2026-10-19 — «برش» is now a real action for an
                        // AUDIO object too. The audio context always has
                        // `selectedClipId == null` (one selection at a time),
                        // so the old code silently did nothing there. A
                        // paired audio object is cut together with its video
                        // clip, which is what keeps the two lanes aligned.
                        val audioId = uiState.selectedAudioClipId
                        if (audioId != null) {
                            if (!viewModel.splitAudioClip(audioId, currentPositionMs)) {
                                viewModel.notifySplitNotPossible()
                            }
                        } else {
                            val id = uiState.selectedClipId ?: activeClip?.id
                            if (id != null && !viewModel.splitClip(id, currentPositionMs)) {
                                viewModel.notifySplitNotPossible()
                            }
                        }
                    }
                    "delete" -> {
                        // 2026-10-20 — «حذف» now works for an AUDIO object.
                        // `selectAudioClip` clears `selectedClipId`, so the old
                        // body (`selectedClipId?.let { deleteClip(it) }`) was a
                        // guaranteed no-op while audio was selected: the button
                        // only ever acted on the video timeline. The audio id
                        // is checked first because it is the selection that
                        // owns the audio toolbar.
                        val audioId = uiState.selectedAudioClipId
                        if (audioId != null) {
                            // A paired soundtrack cannot be removed (it would
                            // be re-seeded on the next load); say so instead
                            // of leaving the tile untouched with no feedback.
                            if (viewModel.deleteAudioClip(audioId) ==
                                AudioDeleteOutcome.PairedAudio
                            ) {
                                viewModel.notifyPairedAudioNotDeletable()
                            }
                        } else {
                            val deleted = uiState.selectedClipId?.let { viewModel.deleteClip(it) }
                            // Deleted is null (nothing selected) or false
                            // (the last clip, which deleteClip refuses). A
                            // silent no-op here is what made the editor look
                            // broken, so the refusal is stated.
                            if (deleted == false) viewModel.notifyLastClipDeleteRefused()
                        }
                    }
                    "duplicate" -> uiState.selectedClipId?.let { viewModel.duplicateClip(it) }
                    // 2026-10-13 — «استخراج»: the audio toolbar's extract
                    // action opens the system video picker.
                    "extract_audio" ->
                        extractAudioLauncher.launch(arrayOf("video/*"))
                    // 2026-09-28 IA — the text context's trailing delete. The
                    // old dock had no per-text delete entry point outside the
                    // subtitle panel itself.
                    "delete_text" -> uiState.selectedTextId?.let { viewModel.deleteTextOverlay(it) }
                    "mute" -> {
                        // Per-clip mute if something is selected, otherwise
                        // toggle the global player mute.
                        val id = uiState.selectedClipId
                        if (id != null) viewModel.toggleMuteForSelectedClip()
                        else viewModel.toggleMute()
                    }
                }
            }
        )
    }
}

// ====================================================================
// Section 1: Top Bar
// ====================================================================

@Composable
private fun EditorTopBarSection(
    title: String,
    canExport: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBackClick: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExportClick: () -> Unit
) {
    // 2026-09-06 — the resolution/FPS badge was removed and the
    // undo/redo pair moved to the EXACT center. Absolute centering is
    // done with a Box + per-group alignment (CenterStart / Center /
    // CenterEnd) instead of weighted spacers, so the pair never
    // drifts regardless of how wide the other groups are.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .padding(horizontal = 12.dp)
    ) {
        // Frosted floating bar — the single chrome layer of this screen.
        GlassChrome(
            shape = RoundedCornerShape(20.dp),
            material = GlassMaterial.Thin,
            modifier = Modifier.matchParentSize()
        ) {}

        // 2026-10-16 (Phase 2) — three-slot Row instead of three
        // independently aligned Box children. Absolutely positioning
        // `CenterStart` / `Center` / `CenterEnd` inside a 56dp Box lets the
        // slots overlap: the old left slot was capped at 160dp and the
        // centred undo/redo pair is ~86dp wide, so on a 360dp phone the
        // title's tail ran under the undo button. Equal `weight(1f)` side
        // slots keep undo/redo *exactly* centred (the weights match, so the
        // free space either side is equal) while making an overlap
        // geometrically impossible — the title now ellipsises inside its
        // own slot instead of colliding.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 6.dp)
        ) {
            // --- Left: back + project title -----------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                SquircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "بازگشت",
                    onClick = onBackClick
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        // 2026-10-16 — was the English eyebrow "EDITING"
                        // in cyan. This is the screen's own identity
                        // label, not an informational signal, so it reads
                        // in Persian like the rest of the workspace chrome
                        // and drops the accent colour to keep the project
                        // title the only high-emphasis element in the bar.
                        text = "در حال ویرایش",
                        style = MaterialTheme.typography.labelSmall,
                        color = LabelSecondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LabelPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // --- Center: undo / redo ------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                SquircleIconButton(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "واگرد",
                    enabled = canUndo,
                    onClick = onUndo
                )
                Spacer(Modifier.width(6.dp))
                SquircleIconButton(
                    icon = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "ازنو",
                    enabled = canRedo,
                    onClick = onRedo
                )
            }

            // --- Right: export CTA ----------------------------------
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd
            ) {
                ExportPill(enabled = canExport, onClick = onExportClick)
            }
        }
    }
}

@Composable
private fun SquircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    // 2026-10-16 (Phase 2) — 40dp → 44dp. Every control in this bar is a
    // primary navigation affordance (back / undo / redo); 44dp is the
    // minimum comfortable touch target, and 40dp sat under it. Disabled
    // alpha now uses the shared [StateDisabledAlpha] floor instead of a
    // local 0.35f so it cannot drift from the rest of the app.
    val alpha = if (enabled) 1f else StateDisabledAlpha
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(HairlineSubtle)
            .border(1.dp, Hairline, RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LabelPrimary.copy(alpha = alpha),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ExportPill(enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else StateDisabledAlpha
    // 2026-10-16 (Phase 2) — the pill was ~34dp tall (8dp padding around a
    // 18dp label). It is the screen's primary action, so it now carries an
    // explicit 44dp minimum height. The icon's own contentDescription was
    // dropped: `clickable` already merges descendants, so the visible
    // Persian label is the accessible name (before, TalkBack read the
    // duplicate "خروجی Export"). The white label stays, which the token
    // contract allows only on [ctaBrush] — and this is that gradient.
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(ctaBrush())
            .border(1.dp, AccentDeep.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.IosShare,
                contentDescription = null,
                tint = LabelPrimary.copy(alpha = alpha),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "خروجی",
                color = LabelPrimary.copy(alpha = alpha),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ====================================================================
// Section 2: Video preview (PlayerView)
// ====================================================================

@Composable
private fun EditorVideoPreviewSection(
    player: ExoPlayer,
    isPlaying: Boolean,
    hasClip: Boolean,
    rotation: Int,
    canvasPreset: CanvasPreset,
    canvasBackground: CanvasBackground,
    sourceClip: com.aicreatorstudio.app.model.VideoClip?,
    sourceAspect: Float,
    activeSubtitle: com.aicreatorstudio.app.model.TextOverlay?,
    // 2026-10-30 — the `activeWord` parameter was removed. It was passed
    // by the only live call site and never read in the body: the shared
    // renderer derives the active word from `frameTimeMs`. Keeping a
    // parameter that exists only to be forwarded is this project's
    // documented bug class ("a forwarded-only parameter eventually stops
    // being forwarded"), so the dead end was cut instead of documented.
    // Per-clip visual state. When `sourceClip` is non-null the per-clip
    // values win; otherwise we fall back to the global editor state.
    selectedEffect: VideoEffect,
    perClipEffect: VideoEffect?,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    opacity: Float,
    animation: ClipAnimation?,
    // 2026-08-29 Lumetri color grading
    colorGrading: com.aicreatorstudio.app.model.ColorGrading,
    isAdjustPanelOpen: Boolean,
    isComparing: Boolean,
    // Current playhead in ms — drives the per-frame animation pose.
    currentPositionMs: Long,
    /**
     * 2026-10-13 — total project duration (ms), used only by the video
     * progress bar to turn [currentPositionMs] into a 0..1 fraction.
     *
     * Defaults to `0`, which
     * [com.aicreatorstudio.app.model.ProgressBarGeometry.progressFraction]
     * reads as "no duration known" → an empty bar. That is the honest
     * fallback for a caller that does not track duration (a project
     * thumbnail), and it can never render a *full* bar for a video that
     * has not started.
     */
    totalDurationMs: Long = 0L,
    onClipTransformChanged: (scale: Float?, offsetX: Float?, offsetY: Float?,
                              rotation: Int?, flipH: Boolean?, flipV: Boolean?) -> Unit,
    // AI Upscaler preview state. Per-clip settings already resolved
    // by the caller so this composable just consumes them.
    upscalerSettings: com.aicreatorstudio.app.model.UpscalerSettings =
        com.aicreatorstudio.app.model.UpscalerSettings.Identity,
    isUpscalerPanelOpen: Boolean = false,
    onUpscaleSplitChanged: (Float) -> Unit = {},
    // 2026-09-01: editor UI state, used to surface the
    // "BG Remove ready" chip when the matting engine has finished
    // at least one run for the selected clip. v1 preview-only
    // composite (see [VideoExportEngine] KDoc).
    uiState: EditorUiState = EditorUiState(),
    // PHASE B5.5 — direct text manipulation. The preview
    // renders a sibling selection chrome and accepts preview
    // tap / drag / pinch / rotate gestures that route back
    // through the ViewModel. The four new params carry the
    // minimum state needed to render the chrome and dispatch
    // the gestures; defaults preserve the pre-B5.5 call site
    // (the live preview composable is referenced from
    // non-editor surfaces too, e.g. project thumbnails).
    textOverlays: List<com.aicreatorstudio.app.model.TextOverlay> = emptyList(),
    selectedTextId: String? = null,
    onSelectTextOverlay: (String?) -> Unit = {},
    onUpdateTextPosition: (String, Float, Float) -> Unit = { _, _, _ -> },
    onUpdateTextStyle: (String, Float?, Float?) -> Unit = { _, _, _ -> },
    // 2026-09-06 live-drag lifecycle: the gesture calls these ONCE
    // per gesture (first moved frame / pointer up) so the host can
    // open one undo-history snapshot and persist once — the per-frame
    // updates themselves are state-only.
    onTextDragStarted: () -> Unit = {},
    onTextDragEnded: () -> Unit = {},
    // 2026-09-06 — tap on the empty canvas (no text picked): the host
    // selects the video clip so tapping the video in the preview
    // selects it.
    onEmptyCanvasTap: () -> Unit = {},
    // 2026-09-28 — Motion Tracking ROI selection. Fired at gesture
    // rate while the user drags the box over a paused frame; the
    // normalized ROI travels back to the ViewModel.
    onTrackingRoiChanged: (android.graphics.RectF) -> Unit = {},
    // 2026-10-13 — Picture-in-Picture overlays with shape masks. The
    // preview is the ONLY place an overlay is turned into pixels in the
    // editor; before this the list was timeline-only metadata. Defaults
    // keep the non-editor call sites (project thumbnails) compiling
    // unchanged, and an empty list short-circuits the layer entirely.
    pipOverlays: List<com.aicreatorstudio.app.model.VideoOverlay> = emptyList(),
    selectedOverlayId: String? = null,
    /**
     * The overlay player pool. Nullable so a caller that renders a static
     * thumbnail does not have to build one — a null pool simply means
     * video overlays show their placeholder instead of live frames.
     */
    pipPlayer: OverlayPipPlayer? = null,
    onSelectOverlay: (String?) -> Unit = {},
    onOverlayTransformStarted: () -> Unit = {},
    onOverlayTransform: (String, Float, Float, Float, Float) -> Unit =
        { _, _, _, _, _ -> },
    onOverlayTransformEnded: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Effective aspect ratio of the canvas box. FREE follows the
    // source; the rest use their preset's fixed ratio.
    val targetRatio = canvasPreset.effectiveAspectRatio(sourceAspect)

    // Effective per-clip transform for the preview.
    val fillMode = sourceClip?.fillMode ?: FillMode.FIT
    val transform = sourceClip?.transform
        ?: com.aicreatorstudio.app.model.ClipTransform()

    // The "famous effect" ColorMatrix LUT that drives the on-screen
    // tint. Per-clip effect wins when a clip is selected so the chosen
    // filter is bound to the clip itself; the global `selectedEffect`
    // is otherwise a reasonable preview baseline.
    val effectiveEffect = perClipEffect?.takeIf { it != VideoEffect.NONE }
        ?: selectedEffect
    // PHASE A (Persistence Integrity) — `anyBcs` used to only gate the
    // label below; the preview always built the matrix either way, so
    // the flag is the honest summary of "is any control active" that
    // the label reads. The matrix helper is always called because the
    // grading chain composes on top of it.
    val anyBcs = brightness != 1f || contrast != 1f || saturation != 1f
    // 2026-08-29: full Lumetri color grading matrix. Composed AFTER
    // the legacy BCS + famous effect chain so the new panel's tweaks
    // (exposure, highlights/shadows, temperature, etc.) win, matching
    // Premiere's "Filter > Lumetri" stack order. While the user holds
    // the Compare button we pass an identity grade so they A/B the
    // original footage.
    val effectiveGrading = if (isComparing) com.aicreatorstudio.app.model.ColorGrading.Identity
                           else colorGrading
    val colorMatrix = remember(
        effectiveEffect, brightness, contrast, saturation, effectiveGrading,
        upscalerSettings, isComparing
    ) {
        // 2026-10-21 — the preview now builds its colour matrix with
        // the SAME function the export bakes into the file
        // ([ColorGradingMath.fullPreviewExportMatrix]), so the two
        // cannot drift apart.
        //
        // Two things changed:
        //
        //  1. The AI upscaler's CAS pass was chained here by hand; it
        //     is now part of the shared chain — and it is the same
        //     matrix the export bakes, so the enhancement the user
        //     sees on screen is the enhancement that lands in the
        //     file. (Before this the export had no upscale field at
        //     all and the file came out unsharpened.)
        //
        //  2. The grade is concatenated with the same pure 4×5 math
        //     the export uses, instead of `android.graphics`' own
        //     `postConcat`. The two agree, but sharing one
        //     implementation makes that a fact rather than a claim.
        //
        // When the user holds the before/after compare gesture
        // (isComparing = true) the upscaler pass is skipped so the
        // preview shows the original footage.
        val upscaleStrength = if (isComparing || !upscalerSettings.isActive) {
            0f
        } else {
            upscalerSettings.effectiveSharpening
        }
        androidx.compose.ui.graphics.ColorMatrix(
            com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath
                .fullPreviewExportMatrix(
                    grading = effectiveGrading,
                    effect = effectiveEffect,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    upscaleStrength = upscaleStrength
                )
        )
    }

    // Rebuilt only when the grade actually changes, so the
    // `SideEffect` can hand the SAME `Paint` instance to the platform
    // on every recomposition: `TextureView.setLayerPaint` compares by
    // identity and no-ops when it is unchanged, which keeps the
    // ~30 Hz position-poller recompositions free.
    //
    // 2026-10-21 — hoisted ABOVE the canvas box so the blurred
    // background layer can use it too. The export grades the whole
    // composited frame, so the preview's blurred background has to be
    // graded as well; leaving it ungraded meant a graded project
    // showed a warm/graded foreground over a raw, cold background.
    val previewColourPaint = remember(colorMatrix) {
        if (ColorGradingMath.isIdentityMatrix(colorMatrix.values)) {
            null
        } else {
            android.graphics.Paint().apply {
                // NB: `android.graphics.Paint`, not the Compose
                // `Paint` that is imported for the draw APIs.
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    colorMatrix.values
                )
            }
        }
    }

    // ── Preview colour: applied by Compose, NOT by Media3 ──────
    //
    // 2026-09-19 — the whole `ExoPlayer.setVideoEffects` pipeline that used to
    // live here (the grade / sharpness / vignette / grain instances, the
    // `effectSignature` membership bits, the `setVideoEffects` call and the
    // paused-state seek round-trip) has been REMOVED. It could not work in
    // this screen, and it broke playback.
    //
    // Two independent reasons, both read out of the Media3 1.5.0 sources:
    //
    //  1. It could not work. `MediaCodecVideoRenderer` builds its GL sink
    //     inside `onEnabled`, and only when `videoEffects != null` at that
    //     instant. `onEnabled` runs during `prepare()` — before this screen's
    //     `PlayerView` surface exists — so the sink is created with no output
    //     surface and never learns of one (full trace in the note on the
    //     player builder above). Priming it stopped the video rendering
    //     altogether, which is exactly what the user saw: Play/Pause dead, no
    //     picture, and the sliders still doing nothing because there were no
    //     frames left to colour.
    //
    //  2. It is not needed. The foreground `PlayerView` is inflated from
    //     `R.layout.player_view_texture`, i.e. it is a **TextureView** — its
    //     content is rasterised into the View hierarchy, so a Compose layer can
    //     filter it directly. That is now the preview's colour path: the
    //     `ColorMatrixColorFilter` layer on the foreground `AndroidView` below.
    //     It runs on the UI thread, needs no GL pipeline, no seek and no player
    //     state, and therefore cannot lock playback.
    //
    // Sharpness / vignette / grain stay export-only. They never reached the
    // screen through the old code either (the sink was never created), so
    // removing them changes nothing the user could see — and a colour matrix
    // cannot express them anyway (a convolution and a spatial vignette are not
    // per-pixel colour transforms). Do not reintroduce `setVideoEffects` here
    // without first solving the surface-timing problem above.

    // Animation pose for the current playhead. Recomputed on every
    // tick (33ms); harmless cost — it's a small enum-driven mapping.
    val clipDurationMs = sourceClip?.effectiveDurationMs?.takeIf { it > 0 } ?: 1L
    val animT = ((currentPositionMs % clipDurationMs).toFloat() / clipDurationMs)
        .coerceIn(0f, 1f)
    val animPose = if (animation != null && animation != ClipAnimation.NONE) {
        com.aicreatorstudio.app.model.ClipMotion.poseAt(animation, animT)
    } else null

    // -----------------------------------------------------------------
    // PHASE A — clip transitions are finally drawn in the preview.
    //
    // Before this the preview ignored `transitionIn` / `transitionOut`
    // entirely: the only renderer that would have drawn them
    // (`EditorVideoCanvas`) was orphaned with no call sites, so the
    // Transition panel wrote a choice the user could not see while the
    // exported file animated. The export path is `ClipMotionEffects`,
    // which composes transition + motion preset + transform + opacity
    // through `ClipMotion.compose`; this calls the same function, so
    // the preview and the file agree by construction rather than by
    // two formulas that happen to look similar.
    //
    // Blur is deliberately NOT previewed. `Modifier.blur()` on the
    // video layer is the exact shape that broke the Play button twice
    // (see the Layer 2.5 history below), so transitions that blur
    // (LENS_ZOOM, WHIP_PAN, GLITCH…) preview their geometry + wash
    // only. Everything else — scale, pan, rotate, alpha, fade, black
    // flash, white flash — is drawn.
    // -----------------------------------------------------------------
    val transitionInCfg = remember(sourceClip?.transitionIn) {
        com.aicreatorstudio.app.model.TransitionConfig.decode(sourceClip?.transitionIn)
    }
    val transitionOutCfg = remember(sourceClip?.transitionOut) {
        com.aicreatorstudio.app.model.TransitionConfig.decode(sourceClip?.transitionOut)
    }
    val clipStartMs = sourceClip?.timelineStartMs?.coerceAtLeast(0L) ?: 0L
    val hasTransition = transitionInCfg != null || transitionOutCfg != null
    val transitionPose = if (hasTransition) {
        com.aicreatorstudio.app.model.TransitionEngine.poseForClipLocal(
            clipLocalMs = (currentPositionMs - clipStartMs)
                .coerceIn(0L, clipDurationMs),
            clipDurationMs = clipDurationMs,
            transitionIn = transitionInCfg,
            transitionOut = transitionOutCfg
        )
    } else {
        com.aicreatorstudio.app.model.TransitionPose()
    }
    // Composed once for the geometry AND read by the wash passes, so
    // the alpha the clip fades to and the wash that fades in are the
    // same pose — the export's own ordering guarantee.
    val motionPose = if (hasTransition) {
        com.aicreatorstudio.app.model.ClipMotion.compose(
            transition = transitionPose,
            motion = animPose ?: com.aicreatorstudio.app.model.AnimationPose(),
            clipOpacity = opacity,
            transformScale = transform.scale,
            panXFraction = transform.offsetX,
            panYFraction = transform.offsetY
        )
    } else {
        null
    }
    val hasWash = hasTransition && motionPose != null && (
        com.aicreatorstudio.app.model.TransitionGeometry.fadeAlpha(motionPose) > 0f ||
            com.aicreatorstudio.app.model.TransitionGeometry.flashBlackAlpha(motionPose) > 0f ||
            com.aicreatorstudio.app.model.TransitionGeometry.whiteoutAlpha(motionPose) > 0f
        )

    // Crop preset -> scale factor that re-fits the source to the
    // chosen aspect. The PlayerView itself is RESIZE_MODE_FIT, so the
    // outer scale is what makes the crop visual.
    val cropScale = sourceClip?.cropAspectRatio?.takeIf { it > 0f }?.let { cropAr ->
        if (cropAr > 0f && sourceAspect > 0f) {
            // Re-fit source (srcAr) into crop (cropAr) while keeping
            // the canvas target aspect: scale = (targetRatio / srcAr) /
            // (targetRatio / cropAr) = cropAr / srcAr
            cropAr / sourceAspect
        } else null
    }

    // Background fill colour for the letterbox region. The blur case
    // is handled by overlaying a second PlayerView (below) and uses
    // black as the base layer behind it.
    val letterboxColor: Color = when (canvasBackground) {
        is CanvasBackground.Black -> Color.Black
        is CanvasBackground.SolidColor -> Color(canvasBackground.colorValue)
        is CanvasBackground.Blur -> Color.Black
    }

    // PlayerView.resizeMode the foreground player should use. FIT
    // letterboxes the source inside the canvas; FILL crops the source
    // to cover the canvas; MANUAL is the same as FIT for the player
    // and lets the outer graphicsLayer apply the user's pan/zoom.
    val playerResizeMode = when (fillMode) {
        FillMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        FillMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        FillMode.MANUAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    // Indicate to the user when an effect is active. A small chip
    // pinned to the top-end of the canvas. Stays visible regardless
    // of canvas blur / background so the user always knows their
    // filter is applied.
    val showFilterChip = effectiveEffect != VideoEffect.NONE || anyBcs ||
        !colorGrading.isIdentity

    // 2026-09-01: matting "BG Remove ready" chip. Shows when the
    // selected clip has [MattingSettings.enabled] == true AND the
    // matting engine has finished at least one run for it (so a
    // sidecar exists on disk).
    //
    // v1 limitation: the per-frame alpha composite is preview-only —
    // this chip is the live preview's only matting affordance. See
    // [VideoExportEngine] KDoc and the matting KDoc on the model
    // file. The export pipeline IS the working matting path; this
    // chip just lets the user see at a glance that the sidecar
    // exists.
    val mattingState = uiState.aiTools["matting"]
    val showMattingChip = sourceClip?.matting?.enabled == true &&
        (mattingState?.status == com.aicreatorstudio.app.ai.AiToolStatus.Completed ||
            mattingState?.status == com.aicreatorstudio.app.ai.AiToolStatus.Ready)

    // 2026-08-29: live canvas size in pixels — used by the floating
    // ColorScope overlay for its drag-clamp math. Updated via
    // onSizeChanged on the inner canvas Box below.
    var canvasSizePx by remember { mutableStateOf(Size.Zero) }

    // 2026-08-30: per-clip "have we re-pushed the first frame yet"
    // flag. Reset whenever the active clip changes, set to `true`
    // the first time the foreground PlayerView's SurfaceView is
    // laid out with a non-zero size. Used by the AndroidView's
    // `onSizeChanged` callback below to re-trigger
    // `exoPlayer.seekTo(0L)` exactly once per clip — which is the
    // root-cause fix for the "preview stays black until Blur is
    // enabled" bug. The bug existed because ExoPlayer's
    // `prepare()` was triggered by `LaunchedEffect(mediaUri)`
    // before the foreground PlayerView's underlying SurfaceView
    // was attached, so the first frame decoded into the void. By
    // the time the surface existed, no re-push was triggered.
    // Toggling Background Blur accidentally fixed it because
    // adding a second `AndroidView { PlayerView }` re-ran the
    // surface-attachment path and pushed the cached first frame
    // to the new (visible) surface. We now do the re-push
    // explicitly, gated to once per clip, so the preview shows
    // the first frame without depending on Blur.
    var hasPushedFirstFrame by remember(sourceClip?.id) {
        mutableStateOf(false)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(CinematicBlack)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Canvas box — sized by target aspect ratio, centred.
        Box(
            modifier = Modifier
                // 2026-09-03 portrait-canvas fix: the canvas box must keep
                // its target ratio for ANY orientation. The previous
                // `fillMaxWidth().aspectRatio(ratio)` forced the box to
                // the full available width first; for portrait ratios
                // (0.5625) the width-derived height exceeded the weighted
                // preview area and got clamped, so the box itself stopped
                // matching the target ratio and the vertical video
                // rendered as a thin strip inside a wrong-shape frame.
                // `heightIn(max = maxHeight)` (the bounded height of the
                // weighted preview area) + bare `aspectRatio` lets Compose
                // fit the largest ratio-correct box: width-driven for
                // landscape/square, height-driven for portrait. Landscape
                // (16:9) canvases resolve to the identical size as before
                // because the width-derived height still fits.
                .heightIn(max = maxHeight)
                .aspectRatio(targetRatio)
                .clip(RoundedCornerShape(22.dp))
                .background(letterboxColor)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(22.dp)
                )
                .onSizeChanged { canvasSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
        ) {
            // Layer 1: blurred background PlayerView (only when the user
            // picked CanvasBackground.Blur). Renders a ZOOM-resized,
            // heavily-blurred copy of the same source.
            //
            // 2026-10-21 — the blurred background is now **graded** with
            // the same paint as the foreground. The export grades the
            // whole composited frame, so an ungraded background meant a
            // graded project showed a warm, graded foreground over a
            // raw, cold background — the preview and the file visibly
            // disagreed. (Known limitation: the export grades the
            // blurred layer *after* the blur, and so does the preview,
            // because the paint sits on the blurred TextureView's own
            // layer.)
            if (hasClip && canvasBackground is CanvasBackground.Blur) {
                val backgroundPlayerView = remember { arrayOfNulls<PlayerView>(1) }
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        // 2026-08-30: re-trigger the first frame as soon
                        // as the PlayerView's underlying SurfaceView is
                        // laid out with a non-zero size. Media3 1.5.0
                        // drops the cached first frame if `prepare()`
                        // ran before the SurfaceView was attached (which
                        // is the common case on first composition, since
                        // the `LaunchedEffect(mediaUri)` and the
                        // `AndroidView` factory are both triggered by
                        // the same `mediaUri` state change but the
                        // SurfaceView's surface is created
                        // asynchronously after layout). The
                        // `onSizeChanged` callback fires after the view
                        // has been measured, which is the earliest safe
                        // moment to re-push the first frame. Guarded by
                        // `hasPushedFirstFrame` so it fires exactly once
                        // per clip — subsequent size changes (rotation,
                        // panel toggles) don't rewind the playhead to 0.
                        .onSizeChanged { newSize ->
                            if (!hasPushedFirstFrame &&
                                newSize.width > 0 && newSize.height > 0
                            ) {
                                player.seekTo(0L)
                                hasPushedFirstFrame = true
                            }
                        }
                        .blur(36.dp),
                    factory = { ctx ->
                        // 2026-09-19: this must be the TEXTURE-view layout,
                        // not `PlayerView(ctx)`. A SurfaceView's video is
                        // composited by the system OUTSIDE the View raster,
                        // so `Modifier.blur(36.dp)` above has nothing to act
                        // on and the "blurred" background comes out a sharp
                        // ZOOM-cropped copy of the video. Layer 2 below
                        // inflates the same layout for exactly this reason,
                        // and this layer was switched to it on 2026-09-17
                        // before regressing. Keep the two in step.
                        val inflated = android.view.LayoutInflater
                            .from(ctx)
                            .inflate(
                                com.aicreatorstudio.app.R.layout.player_view_texture,
                                null,
                                false
                            ) as PlayerView
                        inflated.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        inflated.useController = false
                        inflated.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        inflated.setShutterBackgroundColor(android.graphics.Color.BLACK)
                        inflated.player = player
                        backgroundPlayerView[0] = inflated
                        inflated
                    }
                    // Intentionally no `update` block: the ExoPlayer
                    // instance is `remember { ... }` for the lifetime of
                    // the screen, so re-binding the player on every
                    // recomposition (which happens ~30×/s while the
                    // 33 ms position-poller ticks) would detach and
                    // re-attach the PlayerView's underlying Surface,
                    // dropping the first frame before it is presented.
                )

                // Applies the SAME preview colour grade to the blurred
                // background's TextureView, so the whole composited
                // frame is graded — which is what the export does.
                //
                // `setLayerPaint` no-ops on an identical `Paint`
                // instance, so this costs one field comparison per
                // recomposition, not a repaint.
                SideEffect {
                    val textureView =
                        backgroundPlayerView[0]?.getVideoSurfaceView()
                            as? android.view.TextureView
                    if (textureView != null) {
                        textureView.setLayerType(
                            android.view.View.LAYER_TYPE_HARDWARE,
                            previewColourPaint
                        )
                    }
                }
            }

            if (hasClip) {
                // Layer 2: foreground PlayerView. The outer
                // `graphicsLayer` composes the per-clip transform
                // (pan/zoom/rotate/flip), the crop preset, the
                // animation pose, and the per-clip opacity.
                //
                // 2026-08-30, superseded — this comment used to describe a
                // `drawWithCache` saveLayer that was rejected because the player
                // was a SurfaceView back then. The player is a TextureView now,
                // and the saveLayer was tried again on 2026-09-19: it broke the
                // Play button. See the note on the modifier below. The colour
                // grade goes on the TextureView's own layer paint instead.
                //
                // -----------------------------------------------------------------
                // Preview colour grade (2026-09-19) — the native path.
                // -----------------------------------------------------------------
                // The grade reaches the video by setting a colour-filter `Paint`
                // on the TextureView's own layer. That is a first-class platform
                // mechanism, not a workaround; from `android/view/TextureView.java`
                // (API 37 platform source):
                //
                //   /** The layer type of a TextureView is ignored since a
                //    *  TextureView is always considered to act as a hardware
                //    *  layer. The optional paint supplied to this method will
                //    *  however be taken into account when rendering the content
                //    *  of this TextureView. */
                //   @Override public void setLayerType(int layerType, Paint paint) {
                //       setLayerPaint(paint);
                //   }
                //   // and in draw():
                //   mLayer.setLayerPaint(mLayerPaint);
                //   recordingCanvas.drawTextureLayer(layer);
                //
                // So: no extra offscreen layer (the layer type is ignored), no
                // Compose involvement, no re-entrant `drawContent()`. It is a
                // field write plus an `invalidate()`, and it cannot touch the
                // player, the Surface, or the touch/event loop. That is exactly
                // why it is the right mechanism here: the previous Compose
                // `saveLayer` made the Play button stop responding the instant
                // the matrix left the identity.
                //
                // `foregroundPlayerView` is a plain array holder, deliberately
                // NOT Compose state — publishing a View reference must not trigger
                // a recomposition, and the factory must run exactly once (running
                // it again would re-attach the Surface and drop the player). The
                // factory below writes it.
                val foregroundPlayerView = remember { arrayOfNulls<PlayerView>(1) }

                // 2026-10-17 — Auto Frame crop preview. Applies the same
                // per-frame crop the export pre-render applies (shared
                // ReframePlanner.cropWindowFor), driven by the clip-local
                // playhead. Null → no crop on this frame. Mutually
                // exclusive with Motion Tracking by the ViewModel (one
                // clears the other on commit).
                val afPlacement = sourceClip?.autoFrame?.takeIf { it.enabled }?.let { af ->
                    sourceClip.cropKeyframes.takeIf { it.isNotEmpty() }?.let { keys ->
                        autoFramePreviewPlacement(
                            keyframes = keys,
                            settings = af,
                            clipLocalMs = (currentPositionMs - clipStartMs)
                                .coerceIn(0L, clipDurationMs),
                            sourceAspect = sourceAspect,
                            canvasWidthPx = canvasSizePx.width,
                            canvasHeightPx = canvasSizePx.height
                        )
                    }
                }
                // 2026-08-30: the previous
                // `Modifier.onSizeChanged { seekTo(0L) }` here was
                // a red herring. `onSizeChanged` on the AndroidView's
                // outer Modifier fires when the AndroidView is
                // measured, but the underlying SurfaceView's
                // surface is created asynchronously after the View
                // is attached to the window — so a `seekTo(0L)` at
                // measurement time is a no-op (no surface to push
                // to). The *real* hook is
                // `Player.Listener.onSurfaceSizeChanged` / `onRenderedFirstFrame`
                // which we now listen for in the
                // `DisposableEffect(mediaUri)` block above. We no
                // longer wire an `onSizeChanged` here.
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        // 2026-09-19 — the preview colour is NOT applied here.
                        //
                        // It used to be a Compose `saveLayer` on this very
                        // modifier (`Paint.colorFilter` + `drawContent()` inside
                        // `drawIntoCanvas`). **That broke the Play button.** The
                        // user's report pinned it exactly: playback and Play were
                        // fine until a slider left its default, and died the
                        // instant the `saveLayer` branch activated. So this
                        // view's draw path is back to byte-for-byte what it was,
                        // and **nothing on this modifier may depend on the colour
                        // grade** — a draw modifier that changes when the grade
                        // changes is precisely the shape that broke it.
                        //
                        // The grade is applied natively to the TextureView's own
                        // layer paint instead, from the `SideEffect` after this
                        // `AndroidView` — see "Preview colour grade" below.
                        // `TextureView.setLayerType(type, paint)` ignores the
                        // layer type (a TextureView is always a hardware layer)
                        // and applies the paint when the content is rendered, so
                        // there is no offscreen layer, no re-entrant
                        // `drawContent()`, and no way for it to reach the player,
                        // the surface, or the touch/event loop.
                        .graphicsLayer {
                            val flipXSign = if (transform.flipH) -1f else 1f
                            val flipYSign = if (transform.flipV) -1f else 1f
                            // Crop preset re-fits the source aspect.
                            val cropSx = cropScale ?: 1f
                            // PHASE A — a transition composes transition +
                            // animation + transform + opacity through the
                            // export's own ClipMotion.compose, so the pan
                            // travels in reference-dp for those clips (the
                            // export's unit). Without a transition the
                            // original composition is kept byte-for-byte:
                            // no regression for the 99% case.
                            if (motionPose != null) {
                                scaleX = flipXSign * motionPose.scaleX * cropSx
                                scaleY = flipYSign * motionPose.scaleY * cropSx
                                translationX = motionPose.translationXDp.dp.toPx()
                                translationY = motionPose.translationYDp.dp.toPx()
                                rotationZ = (rotation + transform.rotation).toFloat() +
                                    motionPose.rotationZDeg
                                alpha = motionPose.alpha * opacity.coerceIn(0f, 1f)
                            } else {
                                val transformScaleX = flipXSign * transform.scale
                                val transformScaleY = flipYSign * transform.scale
                                // Animation pose contributes on top of the
                                // manual transform; both compose.
                                val animSx = animPose?.scaleX ?: 1f
                                val animSy = animPose?.scaleY ?: 1f
                                scaleX = transformScaleX * animSx * cropSx
                                scaleY = transformScaleY * animSy * cropSx
                                translationX = (transform.offsetX * size.width) +
                                    (animPose?.translationXDp?.dp?.toPx() ?: 0f)
                                translationY = (transform.offsetY * size.height) +
                                    (animPose?.translationYDp?.dp?.toPx() ?: 0f)
                                rotationZ = (rotation + transform.rotation).toFloat() +
                                    (animPose?.rotationZ ?: 0f)
                                alpha = (animPose?.alpha ?: 1f) * opacity.coerceIn(0f, 1f)
                            }
                        }
                        // 2026-10-17 — Auto Frame crop preview, placed
                        // AFTER the manual-transform graphicsLayer above so
                        // it is the INNERMOST layer: the crop is applied
                        // to the decoded content first, then the manual
                        ///transition transform wraps it — the exact order
                        // export uses (reframe pre-pass → Transformer
                        // stage-1 geometry). Identity when there is no
                        // committed Auto Frame.
                        .graphicsLayer {
                            afPlacement?.let { p ->
                                scaleX = p.scale
                                scaleY = p.scale
                                translationX = p.translationXPx
                                translationY = p.translationYPx
                            }
                        },
                    factory = { ctx ->
                        // 2026-09-01: inflate from a small XML layout
                        // that sets `app:surface_type="texture_view"`.
                        // The default SurfaceView renders in its own
                        // compositor layer and is NOT captured by
                        // `drawContent()` / `saveLayer`, which blocks
                        // the matting composite path. The
                        // `surface_type` attribute is read at
                        // inflation time and there is no programmatic
                        // setter on `PlayerView` in Media3 1.5.0
                        // (`setUseTextureView` does not exist), so
                        // inflation is the only way to switch.
                        // See [R.layout.player_view_texture].
                        val inflated = android.view.LayoutInflater
                            .from(ctx)
                            .inflate(
                                com.aicreatorstudio.app.R.layout.player_view_texture,
                                null,
                                false
                            ) as PlayerView
                        inflated.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        inflated.resizeMode = playerResizeMode
                        // 2026-08-30: use TRANSPARENT so the
                        // SurfaceView never paints a black shutter
                        // before the first frame arrives. The
                        // black-shutter default was contributing to
                        // the "preview is black" symptom on slower
                        // devices where the surface takes a few
                        // hundred ms to attach. Harmless on
                        // TextureView; kept for safety.
                        inflated.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        inflated.player = player
                        // Publish the inflated PlayerView for the colour-grade
                        // `SideEffect` after this `AndroidView`. Its TextureView
                        // already exists at this point: `PlayerView` builds the
                        // surface view in its CONSTRUCTOR, and
                        // `surface_type="texture_view"` makes it
                        // `new TextureView(context)` (`media3-ui` PlayerView.java
                        // ~line 452), so this is safe immediately after inflation.
                        //
                        // This is the ONLY thing this factory publishes. It must
                        // never touch `player`, and it must never run a second
                        // time — the factory runs once per AndroidView instance,
                        // and another run would swap in a new PlayerView,
                        // detaching and re-attaching the Surface and dropping the
                        // player.
                        foregroundPlayerView[0] = inflated
                        // Apply the initial grade HERE as well as from the
                        // `SideEffect` below. Which of the two runs first is an
                        // internal Compose ordering detail — `AndroidView`'s
                        // factory is executed by an `InsertNodeFixup`, a
                        // `SideEffect` by the change list, and both run in the
                        // apply phase with no documented order between them —
                        // so relying on either alone would make "a saved
                        // project opens with its grade already applied" depend
                        // on an implementation detail. Doing it in both places
                        // costs nothing: `TextureView.setLayerPaint` no-ops when
                        // the `Paint` instance is unchanged, so the second
                        // application is one field comparison.
                        //
                        // This does NOT make the factory run twice, and does not
                        // violate the "must never run a second time" rule above:
                        // the factory runs once per node insertion, and a
                        // *changed* `previewColourPaint` is not an insertion.
                        (inflated.getVideoSurfaceView() as? android.view.TextureView)
                            ?.setLayerType(
                                android.view.View.LAYER_TYPE_HARDWARE,
                                previewColourPaint,
                            )
                        inflated
                    }
                    // Intentionally no `update` block: see the matching
                    // note on the blurred-background PlayerView above.
                    // Re-binding `player` on every recomposition (which
                    // happens ~30×/s while the 33 ms position-poller
                    // ticks) detaches and re-attaches the underlying
                    // Surface and drops the cached first frame before it
                    // is presented. The ExoPlayer is `remember { ... }`
                    // for the lifetime of the screen, so the one-time
                    // factory assignment is sufficient.
                )

                // Applies the preview colour grade to the TextureView's own
                // layer paint. Runs after every successful composition, but the
                // `Paint` instance is rebuilt only when the grade changes, and
                // `TextureView.setLayerPaint` no-ops on an identical instance —
                // so this costs one field comparison per recomposition, not a
                // repaint.
                //
                // `as? TextureView` is a guard, not a formality: on a
                // SurfaceView, `setLayerType(LAYER_TYPE_HARDWARE, paint)` would
                // allocate a real offscreen hardware layer rather than just
                // setting a paint — expensive and pointless.
                SideEffect {
                    val textureView =
                        foregroundPlayerView[0]?.getVideoSurfaceView()
                            as? android.view.TextureView
                    if (textureView != null) {
                        textureView.setLayerType(
                            android.view.View.LAYER_TYPE_HARDWARE,
                            previewColourPaint
                        )
                    }
                }

                // Layer 2.5 — deliberately empty. The preview's colour is the
                // TextureView layer paint set by the `SideEffect` above — not a
                // Compose layer, and not an overlay here.
                //
                // History, because this exact spot has produced two bugs:
                //
                //  * 2026-08-30 — a `Modifier.drawWithCache` `saveLayer` with a
                //    `Paint.colorFilter` was wrapped around the foreground
                //    `AndroidView`. That was the **root cause** of the "preview
                //    is black while audio plays" bug: the player was a
                //    **SurfaceView**, whose frame lives in its own compositor
                //    layer and is NOT captured by `drawContent()` inside
                //    `saveLayer`, so the layer composited an empty offscreen
                //    buffer back over the video and rendered black.
                //
                //  * The replacement was a `RenderEffect` on a *separate, empty*
                //    overlay `Box`, on the theory that it would filter "the
                //    composited layer content". **That theory was wrong**: a
                //    Compose `renderEffect` filters its own node's layer content,
                //    and the `Box` had no children — so it filtered nothing and
                //    cost an offscreen layer per frame for nothing.
                //
                //  * 2026-09-19 — the `saveLayer` was tried once more, on the
                //    correct theory that a **TextureView** (the player since
                //    2026-09-01, changed for the matting path) would be captured.
                //    It was: the colours did not regress. But **it broke the Play
                //    button** — playback was fine until a slider left its default
                //    and then Play/Pause stopped responding, i.e. the failure
                //    appeared exactly when the `saveLayer` branch activated. That
                //    is what drove the move to the native layer paint.
                //
                // Do not add a colour filter here, and do not put one on the
                // `AndroidView`'s modifier chain either. The whole design is one
                // paint, on the TextureView, set from the `SideEffect` above.

                // Layer 2.55 — 2026-10-30, the Background Remove composite.
                //
                // Until now the matting mask was applied at export only, so a
                // user who ran the pass and pressed play saw the untouched
                // clip. The panel said so in a hint, but the honest reading of
                // that screen is "the feature does nothing".
                //
                // This is NOT the forbidden pattern above: it does not try to
                // capture or filter the TextureView's layer. It draws its own
                // bitmap — a flat fill whose alpha is the inverse of the mask —
                // as a sibling node on top. No `saveLayer`, no `RenderEffect`,
                // nothing that has to read back the video surface, so the two
                // bugs documented at Layer 2.5 cannot be re-opened here.
                //
                // It sits BELOW the transition washes (2.6) on purpose: a
                // flash/fade covers the whole composited frame, the plate
                // included.
                if (sourceClip != null) {
                    MattingPreviewOverlay(
                        projectId = uiState.project?.id,
                        clip = sourceClip,
                        projectPositionMs = currentPositionMs,
                        canvasFillArgb = letterboxColor.toArgb()
                    )
                }

                // Layer 2.6 — PHASE A transition washes (fade / flash).
                //
                // The export paints these as three TextureOverlays in
                // exactly this order — fade-to-canvas, then black flash,
                // then white flash — and states that their opacities
                // COMPOSE rather than compete (a GLITCH sets both a fade
                // and a black spike). The preview draws the same three
                // passes in the same order, on top of the video whose
                // own alpha the graphicsLayer above already reduced.
                //
                // They are plain Boxes with no draw modifier: the
                // Play-button history above is a warning about
                // *filters* on the video layer, not about a sibling
                // fill. Each Box is only emitted while its alpha is
                // non-zero, so a clip with no transition adds nothing.
                if (hasWash && motionPose != null) {
                    val fadeColour = letterboxColor
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                fadeColour.copy(
                                    alpha = com.aicreatorstudio.app.model.TransitionGeometry
                                        .fadeAlpha(motionPose)
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Color.Black.copy(
                                    alpha = com.aicreatorstudio.app.model.TransitionGeometry
                                        .flashBlackAlpha(motionPose)
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Color.White.copy(
                                    alpha = com.aicreatorstudio.app.model.TransitionGeometry
                                        .whiteoutAlpha(motionPose)
                                )
                            )
                    )
                }

                // ---- Detail > Vignette / Grain (2026-10-21) ----
                //
                // Both used to be **export-only**: the Color Grading
                // panel wrote `vignetteAmount` / `vignetteFeather` /
                // `vignetteRoundness` and `grain`, the DB persisted
                // them, and only the export's `VignetteOverlay` /
                // `GrainEffect` ever read them back — so the sliders
                // moved and the screen did not change. CapCut previews
                // both, and so does this now.
                //
                // The video's rect comes from `CanvasLetterbox.videoRect`
                // — the export's own function — so both passes land on
                // the video and not on the letterbox bars, exactly as
                // the export's pre-`Presentation` passes do.
                //
                // They are sibling overlays (a plain `Image` and a
                // `Canvas`), NOT filters on the video layer — the
                // Play-button history above is a warning about filters,
                // not about siblings.
                val detailSourceW = sourceClip?.width ?: 0
                val detailSourceH = sourceClip?.height ?: 0
                // The export applies both passes to the frame AFTER the
                // geometry step, so they are sized for the ROTATED
                // frame.
                val detailRotation = (rotation + transform.rotation).toFloat()
                val detailBounds = if (detailSourceW > 0 && detailSourceH > 0) {
                    com.aicreatorstudio.app.model.CanvasLetterbox
                        .rotatedBounds(detailSourceW, detailSourceH, detailRotation)
                } else {
                    null
                }
                val detailVideoRect = com.aicreatorstudio.app.model.CanvasLetterbox.videoRect(
                    canvasWidth = canvasSizePx.width.toInt(),
                    canvasHeight = canvasSizePx.height.toInt(),
                    sourceWidth = detailSourceW,
                    sourceHeight = detailSourceH,
                    rotationDegrees = detailRotation,
                    fillMode = fillMode
                )

                // ---- Vignette ----
                //
                // The plate is built by the **same** pure function the
                // export uses (`VignettePlate.pixels`), at the same 1/4
                // resolution, and it is stretched over the video's rect
                // with the same deliberate over-cover — so the falloff,
                // the alpha and the edge behaviour are the export's, not
                // a lookalike.
                val vignetteAmount = colorGrading.vignetteAmount
                if (com.aicreatorstudio.app.model.VignettePlate.isActive(vignetteAmount) &&
                    detailVideoRect != null && detailBounds != null
                ) {
                    val vignetteFrameW = detailBounds.first.roundToInt()
                    val vignetteFrameH = detailBounds.second.roundToInt()
                    val vignettePlate: ImageBitmap =
                        remember(vignetteAmount, colorGrading.vignetteFeather,
                            colorGrading.vignetteRoundness, vignetteFrameW, vignetteFrameH) {
                            val (plateW, plateH) =
                                com.aicreatorstudio.app.model.VignettePlate
                                    .plateSize(vignetteFrameW, vignetteFrameH)
                            val pixels = com.aicreatorstudio.app.model.VignettePlate.pixels(
                                frameWidth = vignetteFrameW,
                                frameHeight = vignetteFrameH,
                                amount = vignetteAmount,
                                feather = colorGrading.vignetteFeather,
                                roundness = colorGrading.vignetteRoundness
                            )
                            android.graphics.Bitmap
                                .createBitmap(pixels, plateW, plateH, android.graphics.Bitmap.Config.ARGB_8888)
                                .asImageBitmap()
                        }
                    val (vignetteScaleX, vignetteScaleY) =
                        com.aicreatorstudio.app.model.VignettePlate
                            .overlayScale(vignetteFrameW, vignetteFrameH)
                    val density = LocalDensity.current
                    Image(
                        bitmap = vignettePlate,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    detailVideoRect.left.roundToInt(),
                                    detailVideoRect.top.roundToInt()
                                )
                            }
                            .size(
                                width = with(density) { detailVideoRect.width.toDp() },
                                height = with(density) { detailVideoRect.height.toDp() }
                            )
                            .graphicsLayer {
                                scaleX = vignetteScaleX
                                scaleY = vignetteScaleY
                            }
                    )
                }

                // ---- Grain ----
                //
                // The export's grain is a fragment shader: per-pixel
                // white noise, scaled by a mid-tone parabola so a
                // crushed black stays crushed. A preview cannot run a
                // shader, so this is an **approximation**, and it is
                // documented as such:
                //
                //  - a small tileable noise field is drawn repeatedly
                //    over the video, cycling through a few tiles so the
                //    grain moves instead of sitting still like dirt on
                //    the lens;
                //  - each speckle is white or black with an alpha that
                //    follows the noise's magnitude, so the overlay
                //    both lightens and darkens;
                //  - the draw uses PLAIN ALPHA and no blend mode,
                //    because the preview's video is a `TextureView`
                //    inside an `AndroidView`: its pixels are not in the
                //    Compose canvas, so a Skia blend mode would have
                //    nothing to blend against and would composite as a
                //    flat, opaque sheet instead of as grain;
                //  - the shader's mid-tone weighting cannot be
                //    reproduced this way, so the preview's grain is
                //    slightly stronger in the blacks and whites than
                //    the file's.
                val grainAmount = colorGrading.grain
                if (com.aicreatorstudio.app.model.GrainSignal.isActive(grainAmount) &&
                    detailVideoRect != null
                ) {
                    val grainTiles = remember {
                        List(GRAIN_TILE_COUNT) { index ->
                            val pixels = com.aicreatorstudio.app.model.GrainSignal
                                .noiseTile(GRAIN_TILE_SIZE, GRAIN_TILE_SEED + index)
                            android.graphics.Bitmap.createBitmap(
                                pixels, GRAIN_TILE_SIZE, GRAIN_TILE_SIZE,
                                android.graphics.Bitmap.Config.ARGB_8888
                            ).asImageBitmap()
                        }
                    }
                    // One tile per rendered frame, so the grain crawls.
                    val grainIndex = ((currentPositionMs / GRAIN_FRAME_MS).toInt())
                        .mod(GRAIN_TILE_COUNT)
                    // The export's peak perturbation is `MAX_AMPLITUDE`;
                    // the tile already carries the noise's own
                    // magnitude, so this is the whole budget.
                    val grainAlpha = com.aicreatorstudio.app.model.GrainSignal
                        .amplitude(grainAmount)
                    val density = LocalDensity.current
                    Canvas(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    detailVideoRect.left.roundToInt(),
                                    detailVideoRect.top.roundToInt()
                                )
                            }
                            .size(
                                width = with(density) { detailVideoRect.width.toDp() },
                                height = with(density) { detailVideoRect.height.toDp() }
                            )
                    ) {
                        drawRect(
                            brush = androidx.compose.ui.graphics.ShaderBrush(
                                androidx.compose.ui.graphics.ImageShader(
                                    grainTiles[grainIndex],
                                    androidx.compose.ui.graphics.TileMode.Repeated,
                                    androidx.compose.ui.graphics.TileMode.Repeated
                                )
                            ),
                            alpha = grainAlpha
                        )
                    }
                }

                // Layer 3: gesture surface for manual pan & zoom.
                // Only active when the clip is in MANUAL mode — otherwise
                // the user's pinch would fight the Fit/Fill baseline.
                if (fillMode == FillMode.MANUAL) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(sourceClip?.id) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    onClipTransformChanged(
                                        (transform.scale * zoom)
                                            .coerceIn(0.25f, 6f),
                                        (transform.offsetX + pan.x / size.width)
                                            .coerceIn(-0.5f, 0.5f),
                                        (transform.offsetY + pan.y / size.height)
                                            .coerceIn(-0.5f, 0.5f),
                                        transform.rotation,
                                        transform.flipH,
                                        transform.flipV
                                    )
                                }
                            }
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ویدئویی بارگذاری نشده",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            // ---- Video progress bar (timeline indicator / story timer) ----
            // 2026-10-13. Drawn INSIDE the canvas Box, which is what makes
            // it match the exported file: the export's [ProgressBarOverlay]
            // paints into a canvas-sized bitmap, and both renderers consume
            // the SAME `ProgressBarGeometry.layout` numbers. There is no
            // second copy of the arithmetic to drift.
            //
            // The dp → px conversion is the shared 720-unit canvas
            // convention, NOT Compose's `Dp.toPx()`. Screen density has
            // nothing to do with frame size; using it would put the
            // on-screen bar at a different fraction of the frame than the
            // exported one — a mismatch visible only in the exported file.
            //
            // Placed BEFORE the subtitle block on purpose: the export
            // appends the bar's effect before the subtitle burn-in, so a
            // caption stays on top of the bar. Mirroring that order here
            // keeps the two renderings identical.
            val progressBarSettings = uiState.progressBarSettings
            if (progressBarSettings.isEnabled &&
                canvasSizePx.width > 0f && canvasSizePx.height > 0f
            ) {
                val barLayout = ProgressBarGeometry.layout(
                    settings = progressBarSettings,
                    canvasWidthPx = canvasSizePx.width,
                    canvasHeightPx = canvasSizePx.height,
                    progress = ProgressBarGeometry.progressFraction(
                        positionMs = currentPositionMs,
                        durationMs = totalDurationMs
                    )
                )
                if (!barLayout.isEmpty) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        drawRoundRect(
                            color = Color(barLayout.trackColorArgb),
                            topLeft = Offset(barLayout.trackLeft, barLayout.trackTop),
                            size = Size(barLayout.trackWidth, barLayout.trackHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                barLayout.cornerRadiusPx
                            )
                        )
                        if (barLayout.isFillVisible) {
                            drawRoundRect(
                                color = Color(barLayout.fillColorArgb),
                                topLeft = Offset(barLayout.fillLeft, barLayout.fillTop),
                                size = Size(
                                    barLayout.fillRight - barLayout.fillLeft,
                                    barLayout.fillBottom - barLayout.fillTop
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    barLayout.cornerRadiusPx
                                )
                            )
                        }
                    }
                }
            }

            // Subtitle word highlight — drawn on top of the video so
            // the active word always reads. The [activeSubtitle] is
            // recomputed by the ViewModel on every playhead tick
            // (33ms) so the word tracks the audio in real time at
            // millisecond accuracy. When [activeWord] is non-null we
            // render the segment as an AnnotatedString with the
            // active word highlighted in a different colour
            // (karaoke-style).
            if (activeSubtitle != null && activeSubtitle.text.isNotBlank()) {
                if (activeSubtitle.subtitleSource == null) {
                    // 2026-09-06 — Paragraph / decorative text: render
                    // at the overlay's own normalized anchor so the
                    // drag-anywhere + position-grid gestures in the
                    // Paragraph panel move the visible text (the
                    // subtitle pill path below is style-driven and
                    // stays put). Current playhead drives the
                    // entrance/exit animation.
                    //
                    // 2026-10-17 — Motion Tracking preview parity: when
                    // this overlay is the one bound by the active clip's
                    // MotionTrackSettings (overlayId) and a real
                    // tracked trajectory exists, drive its placement
                    // with the clip-local playhead exactly like the
                    // export's TrackedOverlayRenderer does.
                    val mt = sourceClip?.motionTrack?.takeIf { it.enabled }
                    val isTrackedOverlay = mt != null &&
                        activeSubtitle.id == mt.overlayId &&
                        (sourceClip?.cropKeyframes?.any { it.hasExtent } == true)
                    FreePositionTextOverlay(
                        overlay = activeSubtitle,
                        currentTimeMs = currentPositionMs,
                        trackedSettings = if (isTrackedOverlay) mt else null,
                        trackedKeyframes = if (isTrackedOverlay) {
                            sourceClip?.cropKeyframes ?: emptyList()
                        } else emptyList(),
                        trackedClipLocalMs = (currentPositionMs - clipStartMs)
                            .coerceIn(0L, clipDurationMs)
                    )
                } else {
                // 2026-09-19 SHARED RENDERER (preview == export) —
                // the caption is a bitmap drawn by CaptionRenderer
                // (the EXACT code that burns the export MP4) and shown
                // 1:1 over the canvas. No separate Compose text
                // implementation remains to drift: fonts, spans, pill,
                // glow, stroke, shadow, backgrounds, placement and
                // entrance/exit are single-defined. Overlay `position`
                // (drag), template changes and the playhead all flow
                // through the bitmap key, so edits reflect instantly.
                // PHASE B5 — production subtitle styling. The
                // overlay's `subtitleStyle` is the source of truth.
                // When null we fall back to SubtitleStyle.Default.
                val style = activeSubtitle.subtitleStyle
                    ?: com.aicreatorstudio.app.model.SubtitleStyle.Default
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val wPx = with(density) { maxWidth.roundToPx() }
                    val hPx = with(density) { maxHeight.roundToPx() }
                    val image = rememberCaptionImageBitmap(
                        overlay = activeSubtitle,
                        style = style,
                        canvasWidthPx = wPx,
                        canvasHeightPx = hPx,
                        frameTimeMs = currentPositionMs
                    )
                    if (image != null) {
                        androidx.compose.foundation.Image(
                            bitmap = image,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                }
            }

            // ---------------------------------------------------------
            // 2026-10-13 — Picture-in-Picture overlay layer.
            //
            // Sits ABOVE the video and subtitle layers (a facecam must draw
            // over the picture) and BELOW the text gesture surface, which
            // is added further down. That order is what keeps text
            // draggable: this layer hit-tests per PiP box rather than
            // claiming the whole canvas, so a touch that misses every
            // overlay still reaches the layers underneath.
            //
            // The layer is also mask-aware: `OverlayPipLayer` asks
            // `OverlayMaskGeometry.contains` before it starts a gesture, so
            // a circular facecam does not swallow taps in its own
            // transparent corners.
            //
            // It reads `VideoOverlay`, never `selectedOverlayId`, for what
            // to DRAW — the selection only decides which one gets chrome —
            // so the same list drives the export through
            // `RenderSegment.mediaOverlays`. Preview and file cannot drift.
            // ---------------------------------------------------------
            if (pipOverlays.isNotEmpty()) {
                OverlayPipLayer(
                    overlays = pipOverlays,
                    selectedOverlayId = selectedOverlayId,
                    playheadMs = currentPositionMs,
                    canvasSizePx = canvasSizePx,
                    pipPlayer = pipPlayer,
                    onSelect = onSelectOverlay,
                    onTransformStarted = onOverlayTransformStarted,
                    onTransform = onOverlayTransform,
                    onTransformEnded = onOverlayTransformEnded
                )
            }

            // Subtle pause / play hint
            if (!isPlaying && hasClip) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(OnVideoScrim)
                        .border(1.dp, Hairline, RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Filter / adjust indicator chip — always visible when an
            // effect is applied, so the user can see at a glance that
            // their colour grade / famous effect is live. Pinned to
            // the top-end so it never collides with the subtitle.
            if (showFilterChip) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(OnVideoScrimStrong)
                        .border(
                            1.dp,
                            iOSBlueBright.copy(alpha = 0.55f),
                            RoundedCornerShape(percent = 50)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MovieFilter,
                        contentDescription = null,
                        tint = iOSBlueBright,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = buildFilterChipLabel(
                            effectiveEffect, brightness, contrast, saturation
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            // 2026-09-01: matting (BG Remove) indicator chip. Stacked
            // below the filter chip when both are visible, so the
            // user can see at a glance that the selected clip has a
            // matting sidecar ready for export. v1 preview-only
            // composite (see VideoExportEngine KDoc).
            if (showMattingChip) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = if (showFilterChip) 38.dp else 10.dp,
                            end = 10.dp
                        )
                        .clip(RoundedCornerShape(percent = 50))
                        .background(OnVideoScrimStrong)
                        .background(iOSPurple.copy(alpha = 0.32f))
                        .border(
                            1.dp,
                            iOSPurple.copy(alpha = 0.55f),
                            RoundedCornerShape(percent = 50)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoFixHigh,
                        contentDescription = null,
                        tint = iOSPurple,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "حذف پس‌زمینه آماده است",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }

            // 2026-08-29: floating ColorScope overlay. Only visible
            // when the Adjust / Color Grading panel is open. The scope
            // samples the live ColorGrading matrix on a synthetic
            // preview, so the histogram bars / waveform / vectorscope
            // update instantly as the user drags any slider. The card
            // is draggable to any position inside the canvas and
            // collapsible to a header-only strip with a single tap.
            // 2026-09-19 BUILD FIX — ColorScope was never added, so
            // this call broke compilation of the whole module. Kept
            // disabled until the scopes composable lands (re-add the
            // call below when it exists).
            // if (isAdjustPanelOpen) {
            //     ColorScope(
            //         visible = isAdjustPanelOpen,
            //         grading = effectiveGrading,
            //         parentWidthPx = canvasSizePx.width,
            //         parentHeightPx = canvasSizePx.height,
            //         modifier = Modifier.matchParentSize()
            //     )
            // }

            // 2026-08-29: AI Upscaler before/after split overlay.
            // Only visible when the upscaler panel is open AND an
            // upscale preset is active. Tapping the bar jumps the
            // divider; dragging the central pill moves it smoothly.
            // The composable itself is a no-op when `enabled = false`
            // so we don't pay any pointer-input cost in the common case.
            BeforeAfterSplitSlider(
                position = upscalerSettings.splitPosition,
                onPositionChange = onUpscaleSplitChanged,
                enabled = isUpscalerPanelOpen && upscalerSettings.isActive,
                modifier = Modifier.matchParentSize()
            )

            // 2026-09-28 — Motion Tracking ROI selector.
            //
            // Only composed while the panel has put the editor into
            // selection mode, so the overlay costs nothing in the
            // common case. It is placed ABOVE the text-gesture surface
            // on purpose: while the user is drawing a box, that box's
            // gestures must win over dragging an existing overlay.
            //
            // The frame rect is derived from the measured canvas plus
            // the *rotation-corrected* source aspect, because the video
            // is letterboxed inside the canvas — normalizing against
            // the container instead of the frame rect is exactly the
            // bug the MotionTrackingGeometry KDoc warns about.
            if (uiState.trackingSelectionActive && sourceClip != null) {
                var roiCanvasSize by remember {
                    mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .onSizeChanged { roiCanvasSize = it }
                ) {
                    // The frame rect only needs the source *aspect*, and
                    // `VideoClip.width/height` are the rotation-corrected
                    // display dimensions the rest of the editor already
                    // normalizes against (see `sourceAspect` computed at
                    // the call site). Using the raw media dimensions here
                    // would put the box 90deg off for rotated footage.
                    val dispW = if (sourceClip.width > 0) sourceClip.width else 1
                    val dispH = if (sourceClip.height > 0) sourceClip.height else 1
                    val frameRect = com.aicreatorstudio.app.model.util
                        .MotionTrackingGeometry.videoFrameComposeRect(
                            container = roiCanvasSize,
                            displayWidthPx = dispW,
                            displayHeightPx = dispH
                        )
                    // The stored ROI is an android RectF (the tracker's
                    // currency); the selector draws in Compose Rect.
                    // Convert once here so neither side has to know
                    // about the other's type.
                    val storedRoi = uiState.pendingTrackingRoi
                    val composeRoi = storedRoi?.let {
                        androidx.compose.ui.geometry.Rect(
                            it.left, it.top, it.right, it.bottom
                        )
                    }
                    RoiSelectorOverlay(
                        frameRect = frameRect,
                        roi = composeRoi,
                        containerSize = roiCanvasSize,
                        displayWidthPx = dispW,
                        displayHeightPx = dispH,
                        enabled = true,
                        onRoiChanged = { onTrackingRoiChanged(it) }
                    )
                }
            }

            // PHASE B5.5 — text-manipulation gesture surface.
            //
            // A single sibling `Box` that handles:
            //   1. Tap        → `pickOverlayAt` then `onSelectTextOverlay`.
            //   2. 1-finger drag (selected overlay, not in MANUAL) →
            //      `onUpdateTextPosition` on `onPointerUp`.
            //   3. 2-finger pinch + rotate (selected overlay, not in
            //      MANUAL) → `onUpdateTextStyle` on `onPointerUp`.
            //
            // Gated on `fillMode != FillMode.MANUAL` so the
            // existing manual-clip-pan gesture at line ~1611 keeps
            // owning its own area of the screen. The active
            // subtitle renderer at line ~1654 is BELOW this Box
            // in z-order so the gesture is always reachable.
            //
            // Uses a single `awaitEachGesture` block so all
            // three interactions share one pointer-input
            // lifecycle (no double-dispatch with the parent
            // `pointerInput`s).
            if (fillMode != FillMode.MANUAL && textOverlays.isNotEmpty()) {
                val targetId = activeOverlayIdFor(
                    playheadMs = currentPositionMs,
                    overlays = textOverlays,
                    selectedId = selectedTextId
                )
                val canvasShortSidePx = canvasSizePx.height.toInt()

                // ------------------------------------------------------
                // 2026-09-06 live-drag fix.
                //
                // The old pointerInput was keyed on
                // `textOverlays, canvasSizePx, currentPositionMs` —
                // all three change DURING the gesture:
                //   - `textOverlays` changes every drag frame (the
                //     position update itself),
                //   - `currentPositionMs` changes every 33 ms during
                //     playback.
                // Any key change RESTARTS pointerInput, which cancels
                // the in-flight awaitEachGesture — that is exactly the
                // "text follows the finger for a moment, then freezes"
                // bug. The gesture now reads its live values through
                // rememberUpdatedState refs (closure-fresh at every
                // pointer event) and is keyed on `Unit` so it never
                // restarts mid-drag.
                // ------------------------------------------------------
                val textOverlaysRef = rememberUpdatedState(textOverlays)
                val selectedTextIdRef = rememberUpdatedState(selectedTextId)
                val playheadRef = rememberUpdatedState(currentPositionMs)
                val targetIdRef = rememberUpdatedState(targetId)

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                // Convert the raw pixel-space down
                                // position to normalized canvas coords.
                                val firstNorm = Offset(
                                    x = (first.position.x / size.width.toFloat()).coerceIn(0f, 1f),
                                    y = (first.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                                )
                                val overlaysNow = textOverlaysRef.value
                                val picked = pickOverlayAt(
                                    pointNorm = firstNorm,
                                    overlays = overlaysNow,
                                    playheadMs = playheadRef.value,
                                    activeId = targetIdRef.value,
                                    shortSidePx = canvasShortSidePx
                                )
                                if (picked == null) {
                                    // Tap on empty video → deselect text
                                    // AND select the VIDEO clip (2026-09-06
                                    // TEXT SYSTEM: tapping the video in the
                                    // preview must select it — previously
                                    // this only cleared the text selection
                                    // and the user could never select a
                                    // clip from the preview).
                                    onSelectTextOverlay(null)
                                    onEmptyCanvasTap()
                                    first.consume()
                                    return@awaitEachGesture
                                }
                                first.consume()
                                if (picked.id != selectedTextIdRef.value) {
                                    onSelectTextOverlay(picked.id)
                                }
                                // Track the picked overlay's start
                                // position so a drag is a delta, not
                                // an absolute jump.
                                val startPos = picked.position
                                var lastPos = startPos
                                val startSp = picked.fontSizeSp
                                val startRot = picked.rotation
                                // The pre-gesture distance and angle
                                // for pinch / rotate (2-finger only).
                                var initialSpan: Float? = null
                                var initialAngle: Float? = null
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val activePointers = event.changes.filter { it.pressed }
                                    when {
                                        activePointers.isEmpty() -> {
                                            if (moved) onTextDragEnded()
                                            break
                                        }
                                        activePointers.size == 1 -> {
                                            val change = activePointers[0]
                                            val dx = (change.position.x -
                                                change.previousPosition.x) / size.width.toFloat()
                                            val dy = (change.position.y -
                                                change.previousPosition.y) / size.height.toFloat()
                                            if (kotlin.math.abs(dx) > 0.0005f ||
                                                kotlin.math.abs(dy) > 0.0005f
                                            ) {
                                                if (!moved) {
                                                    moved = true
                                                    // One history snapshot per
                                                    // drag, on the first moved
                                                    // frame — not per frame.
                                                    onTextDragStarted()
                                                }
                                            }
                                            if (moved) {
                                                val newPos = Offset(
                                                    x = (lastPos.x + dx).coerceIn(0f, 1f),
                                                    y = (lastPos.y + dy).coerceIn(0f, 1f)
                                                )
                                                // Live update — the host wires
                                                // this to the state-only
                                                // ViewModel method (no history,
                                                // no Room write per frame).
                                                onUpdateTextPosition(picked.id, newPos.x, newPos.y)
                                                lastPos = newPos
                                            }
                                            change.consume()
                                        }
                                        activePointers.size >= 2 -> {
                                            val p0 = activePointers[0]
                                            val p1 = activePointers[1]
                                            val currentSpan = hypot(
                                                (p1.position.x - p0.position.x).toDouble(),
                                                (p1.position.y - p0.position.y).toDouble()
                                            ).toFloat()
                                            val currentAngle = Math.toDegrees(
                                                atan2(
                                                    (p1.position.y - p0.position.y).toDouble(),
                                                    (p1.position.x - p0.position.x).toDouble()
                                                )
                                            ).toFloat()
                                            if (initialSpan == null) {
                                                initialSpan = currentSpan
                                                initialAngle = currentAngle
                                                if (!moved) {
                                                    moved = true
                                                    onTextDragStarted()
                                                }
                                            }
                                            val spanInit = initialSpan ?: currentSpan
                                            val angleInit = initialAngle ?: currentAngle
                                            if (spanInit > 1f) {
                                                val scale = (currentSpan / spanInit)
                                                    .coerceIn(0.3f, 4f)
                                                val newSp = (startSp * scale)
                                                    .coerceIn(8f, 96f)
                                                val rotDelta = currentAngle - angleInit
                                                val newRot = (startRot + rotDelta) % 360f
                                                onUpdateTextStyle(picked.id, newSp, newRot)
                                            }
                                            p0.consume()
                                            p1.consume()
                                        }
                                    }
                                }
                            }
                        }
                )
            }

            // PHASE B5.5 — selection chrome. Drawn as a sibling
            // overlay so it never enters the export path
            // (`RenderSegment` / `RenderGraphBuilder` /
            // `VideoExportEngine` do not iterate `textOverlays`
            // or read `selectedTextId`). Visible only when an
            // overlay is selected AND currently visible at the
            // playhead.
            if (selectedTextId != null) {
                val activeId = activeOverlayIdFor(
                    playheadMs = currentPositionMs,
                    overlays = textOverlays,
                    selectedId = selectedTextId
                )
                val activeOverlay = textOverlays.firstOrNull { it.id == activeId }
                if (activeOverlay != null) {
                    TextOverlaySelectionChrome(
                        activeOverlay = activeOverlay,
                        canvasSizePx = canvasSizePx,
                        modifier = Modifier
                    )
                }
            }
        }
    }
}

// ====================================================================
// Section 3: Playback strip
// ====================================================================

// ====================================================================
// PHASE B5.5 — direct text manipulation helpers
// ====================================================================
//
// Pure functions and a sibling-`Box` chrome composable used by the
// preview's tap / drag / pinch+rotate gesture pipeline. The
// selection chrome is drawn as a SIBLING inside the same canvas
// `Box` (not a wrapper) so it never enters the export path — the
// `RenderSegment` / `RenderGraphBuilder` / `VideoExportEngine`
// pipeline is structurally unaware of `selectedTextId`. See
// `EditorViewModel` for the corresponding state mutations.

/**
 * Which `TextOverlay` should be the "active" one for hit-testing
 * and chrome rendering at the current playhead.
 *
 *  - If the caller has already selected an overlay and that
 *    overlay is visible at [playheadMs], it wins — the user can
 *    keep manipulating it even as the playhead moves out of its
 *    window (preview selection survives a brief visibility gap).
 *  - Otherwise we fall back to the top-most visible overlay
 *    (last index in [overlays] wins, mirroring Compose's z-order
 *    convention) so a freshly-tapped preview frame still has
 *    something to highlight.
 *  - Returns null when no overlay is visible at [playheadMs].
 */
private fun activeOverlayIdFor(
    playheadMs: Long,
    overlays: List<com.aicreatorstudio.app.model.TextOverlay>,
    selectedId: String?
): String? {
    if (selectedId != null) {
        val sel = overlays.firstOrNull { it.id == selectedId }
        if (sel != null) return sel.id
    }
    // Walk in REVERSE so the top-most (last in list) wins.
    for (i in overlays.indices.reversed()) {
        if (overlays[i].isVisibleAtTime(playheadMs)) return overlays[i].id
    }
    return null
}

/**
 * The constant rate the live preview should run at for [clip].
 *
 * **2026-09-29 — the ramp's preview story, in one function.** A speed
 * ramp is honoured in full by the export (a curve-backed Media3
 * `SpeedProvider`) but cannot be honoured by the preview player, whose
 * `PlaybackParameters` is one constant rate. The preview therefore runs
 * at the ramp's *effective average*, `source / output`, so the clip takes
 * exactly as long to play on screen as it will in the file. Individual
 * frames are wrong (the ramp's shape is not reproduced) while the overall
 * timing is right, and the panel says so rather than pretending
 * otherwise.
 *
 * The average is taken from [SpeedCurveInterpolator], the same engine
 * that predicts the export duration, so this cannot drift from
 * `RenderSegment.outputDurationMs`.
 *
 * Returns the plain constant `speed` (clamped to a sane range) for a
 * clip with no ramp, which is the pre-existing behaviour exactly — a
 * clip that never had a curve is unaffected by this function's
 * existence.
 */
internal fun rampedPreviewSpeed(clip: com.aicreatorstudio.app.model.VideoClip?): Float {
    if (clip == null) return 1f
    val constant = if (clip.speed.isFinite() && clip.speed > 0f) clip.speed else 1f
    val curve = clip.speedCurve
    if (curve == null || !curve.isActive) return constant
    val sourceMs = clip.effectiveDurationMs
    if (sourceMs <= 0L) return constant
    // Pre-multiply by the constant rate so the average already includes
    // it — the two layers compose, they do not replace each other.
    val scaled = curve.copy(
        points = curve.points.map { it.copy(speed = it.speed * constant) }
    )
    return com.aicreatorstudio.app.model.util.SpeedCurveInterpolator
        .averageSpeed(sourceMs * 1_000L, scaled)
        .let { if (it.isFinite() && it > 0f) it else constant }
}

/**
 * Pick the overlay under a normalized canvas-space point. Used
 * by the preview tap gesture. Falls back to [activeId] if it's
 * still visible, otherwise picks the top-most visible overlay
 * whose `boundsNormalized` rect contains the point.
 */
private fun pickOverlayAt(
    pointNorm: Offset,
    overlays: List<com.aicreatorstudio.app.model.TextOverlay>,
    playheadMs: Long,
    activeId: String?,
    shortSidePx: Int
): com.aicreatorstudio.app.model.TextOverlay? {
    val active = overlays.firstOrNull { it.id == activeId }
    if (active != null && active.isVisibleAtTime(playheadMs) &&
        active.boundsNormalized(shortSidePx).contains(pointNorm)
    ) {
        return active
    }
    for (i in overlays.indices.reversed()) {
        val o = overlays[i]
        if (o.isVisibleAtTime(playheadMs) &&
            o.boundsNormalized(shortSidePx).contains(pointNorm)
        ) {
            return o
        }
    }
    return null
}

/**
 * 2026-09-06 — free-position renderer for decorative text overlays
 * (Paragraph tool, Add Text tool: `subtitleSource == null`).
 *
 * Draws the overlay so its **center** sits at the overlay's
 * normalized `position` (0..1) — the exact same anchor contract
 * `TextOverlay.boundsNormalized` uses for hit-testing, so the
 * selection chrome, the drag gesture, and the visible text stay
 * in perfect lock-step while the user drags the text anywhere on
 * the video.
 */
@Composable
private fun FreePositionTextOverlay(
    overlay: com.aicreatorstudio.app.model.TextOverlay,
    currentTimeMs: Long = 0L,
    // 2026-10-17 — Motion Tracking preview parity. Non-null when this
    // overlay is the one bound by the active clip's MotionTrackSettings
    // and a real trajectory exists on the clip; then the overlay's
    // content centre follows the tracked anchor exactly the way the
    // export renderer (TrackedOverlayRenderer.drawStamp) places it.
    // The blur/mosaic MASK remains export-only (see MotionTrackPanel).
    trackedSettings: com.aicreatorstudio.app.model.MotionTrackSettings? = null,
    trackedKeyframes: List<com.aicreatorstudio.app.model.CropKeyframe> = emptyList(),
    trackedClipLocalMs: Long = 0L
) {
    var parentPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // 2026-09-06: honor the applied SubtitleStyle (style presets /
    // viral styles now work on decorative text too). Style fields win
    // when present; the legacy overlay fields are the fallback.
    val style = overlay.subtitleStyle
    val font = remember(overlay.fontFamily, style?.fontFamily) {
        com.aicreatorstudio.app.model.PersianFont.from(
            style?.fontFamily ?: overlay.fontFamily
        ).toFontFamily()
    }
    val textColor = Color(style?.textColorArgb ?: overlay.color.toArgb())
    val fontSizeSp = style?.fontSizeSp ?: overlay.fontSizeSp
    val fontWeight = when {
        style == null -> overlay.fontWeight
        style.bold -> FontWeight.Bold
        else -> FontWeight.Normal
    }
    val shadow = when {
        style?.shadowEnabled == true -> androidx.compose.ui.graphics.Shadow(
            color = Color(style.shadowColorArgb).copy(alpha = style.shadowOpacity),
            offset = Offset(0f, style.shadowOffsetYDp),
            blurRadius = (style.shadowOffsetYDp * 3f).coerceAtLeast(2f)
        )
        style != null -> null  // style present but shadow disabled
        else -> androidx.compose.ui.graphics.Shadow(
            color = Color.Black.copy(alpha = 0.55f),
            offset = Offset(0f, 1.5f),
            blurRadius = 4f
        )
    }
    val rtl = overlay.text.any { c ->
        c.code in 0x0600..0x06FF ||
            c.code in 0x0750..0x077F ||
            c.code in 0xFB50..0xFDFF ||
            c.code in 0xFE70..0xFEFF
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { parentPx = it }
    ) {
        // 2026-09-06 — text-animation timing. Progress through the
        // overlay's own lifetime drives the entrance/exit preset
        // (fade / pop / slide). Deterministic, time-based.
        val now = currentTimeMs
        val anim = overlay.textAnimation
        val animT = if (anim == com.aicreatorstudio.app.model.TextOverlayAnimation.NONE) {
            1f
        } else {
            val inMs = 320f
            val outMs = 320f
            val sinceStart = (now - overlay.startTimeMs).toFloat()
            val untilEnd = (overlay.endTimeMs - now).toFloat()
            var t = 1f
            if (sinceStart < inMs) t = (sinceStart / inMs).coerceIn(0f, 1f)
            if (untilEnd < outMs) t = minOf(t, (untilEnd / outMs).coerceIn(0f, 1f))
            t
        }
        val animAlpha = when (anim) {
            com.aicreatorstudio.app.model.TextOverlayAnimation.NONE -> 1f
            else -> animT.coerceIn(0f, 1f)
        }
        val animScale = when (anim) {
            com.aicreatorstudio.app.model.TextOverlayAnimation.POP ->
                0.6f + 0.4f * animT
            else -> 1f
        }
        val animOffsetYPx = if (anim == com.aicreatorstudio.app.model.TextOverlayAnimation.SLIDE_UP) {
            (1f - animT) * 0.06f * parentPx.height
        } else 0f

        // Tracked placement (same math as export). Computed against the
        // measured canvas box; `null` keeps the authored spot.
        val trackPlacement = trackedSettings?.let {
            trackedPreviewPlacement(
                settings = it,
                keyframes = trackedKeyframes,
                clipLocalMs = trackedClipLocalMs,
                parentWidthPx = parentPx.width.toFloat(),
                parentHeightPx = parentPx.height.toFloat(),
                authoredCenterXf = overlay.position.x,
                authoredCenterYf = overlay.position.y
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = (overlay.position.x - 0.5f) * parentPx.width +
                        (trackPlacement?.deltaXPx ?: 0f)
                    translationY =
                        (overlay.position.y - 0.5f) * parentPx.height +
                            animOffsetYPx + (trackPlacement?.deltaYPx ?: 0f)
                    rotationZ = overlay.rotation
                    scaleX = animScale * (trackPlacement?.scale ?: 1f)
                    scaleY = animScale * (trackPlacement?.scale ?: 1f)
                    alpha = animAlpha * (trackPlacement?.alpha ?: 1f)
                }
        ) {
            val textComposable: @Composable () -> Unit = {
                Text(
                    text = overlay.text,
                    color = textColor,
                    fontSize = fontSizeSp.sp,
                    fontFamily = font,
                    fontWeight = fontWeight,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = androidx.compose.ui.text.TextStyle(shadow = shadow)
                )
            }
            val content: @Composable () -> Unit = {
                if (style?.backgroundEnabled == true) {
                    // Rounded background pill behind the text (Glass /
                    // Accent / Box presets on decorative text).
                    Box(
                        modifier = Modifier
                            .clip(
                                RoundedCornerShape(style.backgroundCornerRadiusDp.dp)
                            )
                            .background(
                                Color(style.backgroundColorArgb)
                                    .copy(alpha = style.backgroundOpacity)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (style.strokeEnabled) {
                            androidx.compose.foundation.layout.Column {
                                // Stroke preview: duplicate text in the
                                // stroke color underneath the fill text.
                                Text(
                                    text = overlay.text,
                                    color = Color(style.strokeColorArgb),
                                    fontSize = (fontSizeSp + style.strokeWidthSp * 2f).sp,
                                    fontFamily = font,
                                    fontWeight = fontWeight,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = androidx.compose.ui.text.TextStyle(shadow = null)
                                )
                                Text(
                                    text = overlay.text,
                                    color = textColor,
                                    fontSize = fontSizeSp.sp,
                                    fontFamily = font,
                                    fontWeight = fontWeight,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = androidx.compose.ui.text.TextStyle(shadow = shadow)
                                )
                            }
                        } else {
                            textComposable()
                        }
                    }
                } else if (style?.strokeEnabled == true) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = overlay.text,
                            color = Color(style.strokeColorArgb),
                            fontSize = (fontSizeSp + style.strokeWidthSp * 2f).sp,
                            fontFamily = font,
                            fontWeight = fontWeight,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        textComposable()
                    }
                } else {
                    textComposable()
                }
            }
            if (rtl) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection
                        provides androidx.compose.ui.unit.LayoutDirection.Rtl
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * Sibling `Box` that draws the selection chrome around the
 * currently-active text overlay. Yellow 2 dp dashed border + 4
 * small corner squares. Renders inside the canvas's inner `Box`
 * so it lines up with the underlying video frame.
 *
 * The chrome is purely UI — it never reaches the export path
 * because `RenderSegment` / `RenderGraphBuilder` /
 * `VideoExportEngine` only iterate `project.clips`, not
 * `project.textOverlays` and never reference `selectedTextId`.
 */
@Composable
private fun TextOverlaySelectionChrome(
    activeOverlay: com.aicreatorstudio.app.model.TextOverlay?,
    canvasSizePx: Size,
    modifier: Modifier = Modifier
) {
    if (activeOverlay == null) return
    if (canvasSizePx.width <= 0f || canvasSizePx.height <= 0f) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rect = activeOverlay.boundsNormalized(canvasSizePx.height.toInt())
    val wPx = (rect.width * canvasSizePx.width).coerceAtLeast(0f)
    val hPx = (rect.height * canvasSizePx.height).coerceAtLeast(0f)
    val xPx = rect.left * canvasSizePx.width
    val yPx = rect.top * canvasSizePx.height
    Box(
        modifier = modifier
            .offset { androidx.compose.ui.unit.IntOffset(xPx.toInt(), yPx.toInt()) }
            .size(
                width = with(density) { wPx.toDp() },
                height = with(density) { hPx.toDp() }
            )
            .border(
                width = 2.dp,
                color = iOSBlueBright,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
            )
    ) {
        // Corner handles (top-left, top-right, bottom-left,
        // bottom-right) — small white squares on a black
        // background. They are NOT interactive in v1; the
        // pinch / drag gestures on the parent `Box` are the
        // sole transform input. The handles exist purely as a
        // visual affordance to signal "this is selected".
        val corners = listOf(
            Alignment.TopStart,
            Alignment.TopEnd,
            Alignment.BottomStart,
            Alignment.BottomEnd
        )
        corners.forEach { corner ->
            Box(
                modifier = Modifier
                    .align(corner)
                    .size(10.dp)
                    .background(Color.White)
                    .border(1.dp, Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun EditorPlaybackStripSection(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onScrubStart: () -> Unit,
    onScrubSeek: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onSeek: (Long) -> Unit
) {
    // 2026-09-28 IA (§5.4) — the transport row no longer carries a Split
    // button. It duplicated the CLIP context's `clip_split` chip with
    // byte-identical logic (`selectedClipId ?: activeClip` → `splitClip`),
    // putting the same action on screen twice whenever a clip was selected.
    // Split now belongs to the clip context, where it has a target by
    // definition; the row keeps ⏪ ▶ ⏩, the scrubber and the timecodes.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(PlaybackStripHeight)
            .background(CinematicSurface)
            .padding(horizontal = 12.dp)
    ) {
        // 2026-10-16 (Phase 2) — 40dp → 44dp: the transport's primary
        // control sits under the comfortable-touch-target floor. Labels are
        // Persian like the rest of the workspace chrome.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    ctaBrush()
                )
                .clickable(role = Role.Button) { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "توقف" else "پخش",
                tint = LabelPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = EditorTimecode.mmSsMs(positionMs),
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        // Live scrub: as the user drags the thumb we call
        // [onScrubStart] once, [onScrubSeek] on every value change
        // (millisecond-accurate) and [onScrubEnd] on release. The
        // ViewModel mirrors the position into `pendingScrubSeekMs`
        // which the screen forwards to ExoPlayer via
        // `seekTo(ms, SEEK_PARAM_CLOSEST_SYNC)` for snappy scrubbing
        // without stutter.
        // Track whether the user is currently dragging so we can
        // fire [onScrubStart] on the first value change and
        // [onScrubEnd] in onValueChangeFinished. This Material3 Slider
        // overload doesn't expose onValueChangeStarted, so we detect
        // the gesture start inside the value lambda with a remember flag.
        var scrubbing by remember { mutableStateOf(false) }
        Slider(
            value = positionMs.toFloat().coerceAtLeast(0f),
            onValueChange = { v ->
                if (!scrubbing) {
                    scrubbing = true
                    onScrubStart()
                }
                onScrubSeek(v.toLong())
            },
            onValueChangeFinished = {
                if (scrubbing) {
                    scrubbing = false
                    onScrubEnd()
                }
            },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = AccentBright,
                activeTrackColor = AccentPrimary,
                // 2026-10-16 — was `Color.White.copy(0.10f)`, a raw literal
                // that rendered the scrubber trough almost invisibly on the
                // transport band. [BorderSubtle] is the token for a visible
                // inactive track on a dark surface.
                inactiveTrackColor = BorderSubtle
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = EditorTimecode.mmSsMs(durationMs),
            color = LabelSecondary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// ====================================================================
// Section 4: Timeline
// ====================================================================

@Composable
private fun EditorTimelineSection(
    uiState: EditorUiState,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onClipSelected: (String?) -> Unit,
    // 2026-09-06 — Video Audio / Music / SFX lane selection.
    onAudioClipSelected: (String?) -> Unit = {},
    // 2026-10-20 — drag-to-move for an audio tile (per-frame delta).
    onAudioClipMoved: (String, Long) -> Unit = { _, _ -> },
    // 2026-10-20 — fired when a clip OR audio-tile drag releases. Resets the
    // undo coalescing latches so the next gesture on the same object pushes
    // its own history entry (a drag after an undo used to be unundoable).
    onDragEnd: () -> Unit = {},
    onClipMoved: (String, Long) -> Unit,
    onClipTrimmed: (String, Long, Long) -> Unit,
    // 2026-10-04 — fired once when a trim DRAG ends (not per frame). The
    // screen uses it to reset the undo coalescing latch and rebuild the
    // preview playlist with the new trim window.
    onClipTrimEnd: () -> Unit = {},
    // 2026-10-05 — "+" header button (add media to the open project).
    onAddClip: () -> Unit = {},
    isAddingClip: Boolean = false,
    // Real per-clip PCM peaks for the audio lanes.
    audioWaveforms: Map<String, List<com.aicreatorstudio.app.model.AudioWaveformPeak>> = emptyMap(),
    // Real filmstrip frames for the video lane.
    clipThumbnails: Map<String, List<androidx.compose.ui.graphics.ImageBitmap>> = emptyMap(),
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubSeek: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onToggleTrack: (TrackKind) -> Unit,
    // PHASE B3 — subtitle-lane taps land here, not on
    // onClipSelected. Defaults to a no-op so the function
    // still compiles for any future test/preview callsite.
    onTextOverlaySelected: (String) -> Unit = {},
    // 2026-09-29 — detected beat onsets (timeline time, ascending) for the
    // clip drag/trim snap. Empty when nothing has been analysed.
    beatMarkers: List<Long> = emptyList(),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(CinematicSurface)
            // 2026-10-16 (Phase 2) — the transport band above shares
            // [CinematicSurface], so the two used to merge into one slab and
            // the timeline's top boundary was unreadable. A hairline frame
            // separates "scrub the whole project" from "edit the lanes"
            // without changing layout (border draws inside the bounds).
            .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
            .padding(vertical = 6.dp)
    ) {
        EditorTimeline(
            clips = uiState.project?.clips ?: emptyList(),
            overlays = uiState.overlays,
            textOverlays = uiState.textOverlays,
            // 2026-09-06 — Video Audio lane: paired audio objects,
            // selectable with a dedicated selection state.
            audioClips = uiState.project?.audioClips ?: emptyList(),
            selectedAudioClipId = uiState.selectedAudioClipId,
            onAudioClipSelected = onAudioClipSelected,
            onAudioClipMoved = onAudioClipMoved,
            onDragEnd = onDragEnd,
            activeSubtitleId = uiState.activeSubtitleId,
            selectedClipId = uiState.selectedClipId,
            selectedTextId = uiState.selectedTextId,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            pixelsPerMs = uiState.pixelsPerMs,
            isPlaying = isPlaying,
            // 2026-09-06: the auto-scroll gate inside the playhead
            // overlay needs the live scrub flag so the lane follows
            // the finger during a drag instead of the playhead
            // scrolling out of view on long timelines.
            isScrubbing = uiState.isScrubbing,
            visibleTrackKinds = uiState.visibleTrackKinds,
            focusedTrackKind = uiState.focusedTrackKind,
            onClipSelected = onClipSelected,
            onClipMoved = onClipMoved,
            onClipTrimmed = onClipTrimmed,
            onClipTrimEnd = { onClipTrimEnd() },
            onAddClip = onAddClip,
            isAddingClip = isAddingClip,
            audioWaveforms = audioWaveforms,
            clipThumbnails = clipThumbnails,
            onSeek = onSeek,
            onScrubStart = onScrubStart,
            onScrubSeek = onScrubSeek,
            onScrubEnd = onScrubEnd,
            onZoomChange = onZoomChange,
            onToggleTrack = onToggleTrack,
            // PHASE B3 — pass the dedicated subtitle-selector
            // callback through to the timeline. The default
            // (no-op) keeps any unrelated test/preview caller
            // working.
            onTextOverlaySelected = onTextOverlaySelected,
            // 2026-09-29 — detected beat onsets for the drag/trim snap.
            beatMarkers = beatMarkers,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ====================================================================
// Section 5: Bottom dock (contextual tool row)
// ====================================================================

/**
 * The editor's tool dock.
 *
 * 2026-09-28 IA — replaced the flat category dock (5 always-visible category
 * tabs + 6 extras in one scrolling row, plus a second reveal row per category)
 * with a **contextual** toolbar: one non-scrolling row whose chips are a pure
 * function of [context]. The old layout rendered the same 12 chips whether
 * nothing, a clip, an audio object or a text overlay was selected, and put
 * Split / Duplicate / Delete last — permanently off-screen on a phone.
 *
 * The composition now lives in [ContextualToolbarSpec] (pure data, unit
 * tested) and is rendered by [ContextualToolbar].
 */
@Composable
private fun EditorBottomDockSection(
    context: EditorContext,
    selectedPanel: ToolPanel?,
    hasAdjacentClip: Boolean,
    onPanelSelected: (ToolPanel?) -> Unit,
    onAction: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CinematicSurface)
    ) {
        ContextualToolbar(
            context = context,
            selectedPanel = selectedPanel,
            hasAdjacentClip = hasAdjacentClip,
            onPanelSelected = onPanelSelected,
            onAction = onAction
        )
    }
}


// ====================================================================
// Sub-panel — secondary row above the dock
// ====================================================================

@Composable
private fun SubPanelRow(
    uiState: EditorUiState,
    currentPositionMs: Long,
    projectDuration: Long,
    onClose: () -> Unit,
    onVolumeChanged: (String, Float) -> Unit,
    onBrightnessChanged: (Float) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onPlaybackSpeedChanged: (Float) -> Unit,
    onRotationChanged: (Int) -> Unit,
    onRotate90: () -> Unit,
    onTrimStartChanged: (Long) -> Unit,
    onTrimEndChanged: (Long) -> Unit,
    onClipTrimChanged: (String, Long, Long) -> Unit,
    // PHASE A (Persistence Integrity) — lets a panel hand the user on
    // to a sibling panel (the Effects panel's "Trim clip" pill) instead
    // of pretending to do the work itself.
    onPanelSelected: (ToolPanel?) -> Unit = {},
    // 2026-10-05 — AUTO-CAPTION one-tap (SUBTITLES panel) / passthrough.
    onAutoCaption: (String?) -> Unit = {},
    onToggleTrimMode: () -> Unit,
    onResetEffects: () -> Unit,
    onAddText: (String, Long, Long, PersianFont) -> Unit,
    // 2026-09-06 — Text tool «اعمال» pass-through: add at playhead +
    // select the new overlay.
    onApplyText: (String, Long, Long, String) -> Unit = { _, _, _, _ -> },
    // 2026-09-06 — Paragraph-panel editing of the SELECTED overlay.
    onUpdateSelectedFont: (String) -> Unit = {},
    onUpdateSelectedFontSize: (Float) -> Unit = {},
    onUpdateSelectedColor: (Int) -> Unit = {},
    onApplySelectedStyle: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    // 2026-09-08 - Text panel style tab, apply-to-every-text button.
    onApplyStyleToAllTexts: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    onMoveSelectedTextToPlayhead: () -> Unit = {},
    // 2026-09-06 — full TEXT SYSTEM setters.
    onUpdateSelectedRotation: (Float) -> Unit = {},
    onUpdateSelectedStroke: (Boolean) -> Unit = {},
    onUpdateSelectedShadow: (Boolean) -> Unit = {},
    onUpdateSelectedBackground: (Boolean) -> Unit = {},
    onUpdateSelectedAnimation: (com.aicreatorstudio.app.model.TextOverlayAnimation) -> Unit = {},
    onUpdateSelectedDuration: (Long) -> Unit = {},
    // 2026-09-06 — Paragraph tool pass-throughs.
    onAddParagraph: (String, Long, Long, PersianFont, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onSnapSelectedText: (Float, Float) -> Unit = { _, _ -> },
    onNudgeSelectedText: (Float, Float) -> Unit = { _, _ -> },
    onEffectSelected: (VideoEffect) -> Unit,
    onSetTransition: (com.aicreatorstudio.app.model.TransitionScope,
                      com.aicreatorstudio.app.model.TransitionConfig?) -> Unit,

    onPickMusic: () -> Unit,
    // 2026-10-13 — Extract Audio from Video: opens the system video picker
    // via the host's launcher. Default keeps every existing call site.
    onExtractAudioFromVideo: () -> Unit = {},
    onClipAnimationChanged: (String, ClipAnimation) -> Unit,
    onClipCropChanged: (String, CropPreset) -> Unit,
    onSetClipSpeed: (String, Float) -> Unit,
    onGenerateSubtitles: (PersianFont) -> Unit,
    onSelectWhisperModel: (WhisperModelSpec) -> Unit,
    // 2026-09-06 — subtitle language picker pass-through.
    onSubtitleLanguageSelected: (com.aicreatorstudio.app.ai.SubtitleLanguage) -> Unit = {},
    onSelectAccuracyMode: (com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode) -> Unit = {},
    onSaveCustomDictionary: (String) -> Unit = {},
    // 2026-09-15 — words-per-caption (Premiere-style) pass-through.
    onSelectWordsPerCaption: (Int) -> Unit = {},
    // 2026-09-06 — model download pass-through for the Subs card.
    onDownloadWhisperModel: (WhisperModelSpec) -> Unit = {},
    // 2026-09-06 — per-clip mute toggle for the Volume panel.
    onToggleMuteSelected: () -> Unit = {},
    // 2026-09-06 — per-audio-object setters for the Volume panel.
    onUpdateAudioClipVolume: (String, Float) -> Unit = { _, _ -> },
    onUpdateAudioClipMute: (String, Boolean) -> Unit = { _, _ -> },
    onExportSrt: () -> Unit,
    // PHASE B4 — pass-through for the SRT import path. The
    // caller (EditorScreenBody) wires this to
    // viewModel.onImportSrtClicked(); the wrapper just
    // forwards.
    onImportSrt: () -> Unit = {},
    onConsumeSrtImportState: () -> Unit = {},
    onPickOverlay: () -> Unit,
    // Canvas / transform / background handlers (added in 2026-08-28 refactor)
    onCanvasPresetChanged: (CanvasPreset) -> Unit,
    onCanvasBackgroundChanged: (CanvasBackground) -> Unit,
    onClipFillModeChanged: (String, FillMode) -> Unit,
    onClipTransformChanged: ClipIdTransformUpdater,
    onResetClipTransform: (String) -> Unit,
    // Per-clip fade / opacity handlers (added in toolbar audit).
    onClipFadeInChanged: (String, Float) -> Unit,
    onClipFadeOutChanged: (String, Float) -> Unit,
    onClipOpacityChanged: (String, Float) -> Unit,
    // 2026-08-29 Lumetri color grading handlers. The before/after
    // callback toggles a local `isComparing` flag in the parent so the
    // preview can re-render with the identity grade while the user
    // holds the button.
    onColorGradingChanged: (com.aicreatorstudio.app.model.ColorGrading) -> Unit = {},
    onResetColorGrading: () -> Unit = {},
    onBeforeAfterChange: (Boolean) -> Unit = {},
    // AI Upscaler handlers.
    onUpscalerPresetChanged: (com.aicreatorstudio.app.model.UpscalerPreset) -> Unit = {},
    onUpscalerStrengthChanged: (Float) -> Unit = {},
    onResetUpscaler: () -> Unit = {},
    onUpscaleExportClicked: () -> Unit = {},
    // PHASE B3 — AI Subtitle card gets a richer handler
    // surface: cancel, edit/delete a single overlay, delete
    // all AI subtitles, and text-selection plumbing for the
    // timeline.
    onCancelSubtitleGeneration: () -> Unit = {},
    onUpdateTextOverlay: (String, String) -> Unit = { _, _ -> },
    onDeleteTextOverlay: (String) -> Unit = {},
    onDeleteAllAiSubtitles: () -> Unit = {},
    onClearTextSelection: () -> Unit = {},
    // 2026-10-13 — CapCut-style subtitle editing workflow. Defaults
    // keep every existing caller compiling and inert.
    onSplitSubtitle: (String, Int) -> Unit = { _, _ -> },
    onMergeSubtitleWithNext: (String) -> Unit = {},
    onRetimeSubtitle: (String, Long, Long) -> Unit = { _, _, _ -> },
    onRetimeSubtitleWord: (String, Int, Long, Long) -> Unit = { _, _, _, _ -> },
    onResyncSubtitleText: (String) -> Unit = {},
    // 2026-10-15 — global subtitle font size (batch resize of every
    // subtitle in one undo step). Inert defaults keep every existing
    // caller compiling; see EditorViewModel.beginGlobalSubtitleResize.
    onBeginGlobalSubtitleResize: () -> Unit = {},
    onGlobalSubtitleSizeLive: (Float) -> Unit = {},
    onEndGlobalSubtitleResize: () -> Unit = {},
    onSetGlobalSubtitleSize: (Float) -> Unit = {},
    onScaleAllSubtitles: (Float) -> Unit = {},
    // 2026-10-13 — Custom Font Importer (.ttf / .otf). The list is pure
    // state (read-only); the three callbacks are the write path into
    // CustomFontRepository via the ViewModel. Defaulting to inert keeps
    // every preview / test caller compiling.
    customFonts: List<com.aicreatorstudio.app.model.CustomFont> = emptyList(),
    onImportCustomFont: (String) -> Unit = {},
    onDeleteCustomFont: (String) -> Unit = {},
    // 2026-10-13 — Video progress bar (timeline indicator / story timer).
    // The second argument is the ViewModel's `pushHistory` flag: `false`
    // for the per-frame updates a slider drag produces (state only), `true`
    // for the one commit on release (undo snapshot + Room write). The value
    // itself is read from `uiState.progressBarSettings`, so only the write
    // path needs threading.
    onProgressBarSettingsChanged:
        (com.aicreatorstudio.app.model.ProgressBarSettings, Boolean) -> Unit = { _, _ -> },
    // 2026-10-13 — voice changer. `(clipId, effect)`: the effect belongs to
    // one clip's audio, so the id travels with it rather than being read
    // from the selection inside the panel — that keeps the write correct
    // even if the selection changes between the tap and the recomposition.
    onVoiceEffectChanged:
        (String, com.aicreatorstudio.app.model.VoiceEffect) -> Unit = { _, _ -> },
    // 2026-10-13 — PiP shape mask. `(overlayId, settings, pushHistory)`:
    // the mask belongs to one overlay, so the id travels with it rather
    // than being read from the selection inside the panel — that keeps the
    // write correct even if the selection changes between the tap and the
    // recomposition. `pushHistory` is `false` for the per-frame updates a
    // slider drag produces (state only) and `true` for the one commit on
    // release (undo snapshot + Room write).
    onOverlayMaskChanged: (
        String,
        com.aicreatorstudio.app.model.OverlayMaskSettings,
        Boolean
    ) -> Unit = { _, _, _ -> },
    // 2026-10-13 — chroma key («پرده سبز»). The twin of
    // `onOverlayMaskChanged`, with the same triple shape and for the same
    // reason: the Boolean is the live/commit flag, `false` for a slider
    // drag frame and `true` for the single commit on release. The two are
    // separate callbacks so a caller can never write one when it meant the
    // other — they are different settings that merely share a target.
    onOverlayChromaChanged: (
        String,
        com.aicreatorstudio.app.model.ChromaKeySettings,
        Boolean
    ) -> Unit = { _, _, _ -> },
    // PHASE B5 — production subtitle styling.
    onApplySubtitleStyle: (String, com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = { _, _ -> },
    onApplySubtitleStyleToAll: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    // 2026-09-01 AI tool handlers. Defaults keep the existing
    // (no-AI-tools) callers compiling while the screens are wired
    // up below — see EditorScreenBody for the live wiring.
    onRunAiTool: (String, com.aicreatorstudio.app.ai.AiToolOptions) -> Unit = { _, _ -> },
    onCancelAiTool: (String) -> Unit = {},
    onResetAiTool: (String) -> Unit = {},
    onEnsureMattingModelReady: () -> Unit = {},
    onMattingBackgroundModeChanged: (com.aicreatorstudio.app.model.MattingBackgroundMode) -> Unit = {},
    onEnsureAutoframeModelReady: () -> Unit = {},
    autoframeModelStatusText: String = "",
    autoframeModelAvailable: Boolean = true,
    // Motion Tracking — the ROI the user drew, plus the live backend
    // probe text. The ROI is nullable/absent until the user enters
    // selection mode and drags a box.
    pendingTrackingRoi: android.graphics.RectF? = null,
    motionTrackBackendText: String = "",
    onTrackingRoiChanged: (android.graphics.RectF) -> Unit = {},
    onEnterTrackingSelection: () -> Unit = {},
    onExitTrackingSelection: () -> Unit = {},
    onPickBackgroundMedia: () -> Unit = {},
    // Live Background Remove availability, read from the
    // registered engine (not derived from AiToolState, which
    // starts Idle before the first probe).
    mattingModelAvailable: Boolean = false,
    // AI Upscale (ESRGAN) model + Generate wiring. The panel
    // renders Download / Generate / Cancel / result from these.
    upscaleModelState: AiToolState = AiToolState(toolId = "upscale"),
    upscaleModelReady: Boolean = false,
    upscaleModelStatusText: String = "",
    onEnsureUpscaleModelReady: () -> Unit = {},
    upscaleProgress: UpscaleProgress? = null,
    effectiveUpscaleProfileLabel: String? = null,
    onUpscaleGenerateClicked: () -> Unit = {},
    onUpscaleCancelClicked: () -> Unit = {},
    onDismissUpscaleResult: () -> Unit = {},
    // 2026-09-10 AI OCR wiring (wrapper level).
    onEnsureOcrModelReady: () -> Unit = {},
    ocrModelStatusText: String = "",
    ocrModelAvailable: Boolean = false,
    onApplyOcr: (String, Long, Int, Float) -> Unit = { _, _, _, _ -> },
    // 2026-09-11 «زیرنویس فارسی» studio wiring (wrapper level).
    onApplySubtitleTemplate: (com.aicreatorstudio.app.model.SubtitleTemplate) -> Unit = {},
    onPickStudioTemplate: (com.aicreatorstudio.app.model.SubtitleTemplate) -> Unit = {},
    onAddTemplateSubtitle: (String, com.aicreatorstudio.app.model.SubtitleTemplate, Long, Long) -> Unit = { _, _, _, _ -> },
    onOpenAutoSubtitles: () -> Unit = {},
    studioTemplateId: String? = null,
    // 2026-09-21 — AI Auto Silence Cut pass-throughs.
    onAutoCutSilence: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onCancelAutoCutSilence: () -> Unit = {},
    onClearAutoCutSilenceResult: () -> Unit = {},
    // PHASE 1 — analyze → preview → apply pass-throughs.
    onAnalyzeSilence: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onApplySilencePreview: () -> Unit = {},
    onDiscardSilencePreview: () -> Unit = {},
    onToggleSilenceSegment: (String) -> Unit = {},
    onSelectAllSilence: (Boolean) -> Unit = {},
    onAnalyzeJumpCut: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    // PHASE 4 — voice enhancement preview flow.
    onPreviewVoiceEnhancement: (com.aicreatorstudio.app.ai.voice.VoiceEnhancementPreset) -> Unit = {},
    onApplyVoiceEnhancement: () -> Unit = {},
    onDiscardVoiceEnhancement: () -> Unit = {},
    onClearVoiceEnhancement: () -> Unit = {},
    onCancelVoiceEnhance: () -> Unit = {},
    // PHASE 5 — smart reframe preview flow.
    onAnalyzeReframe: (Float, Float) -> Unit = { _, _ -> },
    onApplyReframe: () -> Unit = {},
    onDiscardReframe: () -> Unit = {},
    onClearReframe: () -> Unit = {},
    onCancelReframe: () -> Unit = {},
    // 2026-09-29 — Speed Ramping / curves.
    onSpeedCurveChanged: (String, com.aicreatorstudio.app.model.SpeedCurve?) -> Unit = { _, _ -> },
    onClearSpeedCurve: (String) -> Unit = {},
    // 2026-09-29 — Beat Detection & timeline markers.
    onDetectBeats: (com.aicreatorstudio.app.util.BeatDetector.BeatSensitivity) -> Unit = {},
    onCancelBeats: () -> Unit = {},
    onClearBeats: () -> Unit = {},
    onAutoSplitOnBeats: () -> Unit = {},
    // PHASE 6 — beat sync suggestion flow.
    onAnalyzeBeatSync: (com.aicreatorstudio.app.util.BeatSyncMode) -> Unit = {},
    onEnsureBeatSyncBeats: () -> Unit = {},
    onCancelBeatSync: () -> Unit = {},
    onApplyBeatSync: () -> Unit = {},
    onDiscardBeatSync: () -> Unit = {},
    onToggleBeatCandidate: (String) -> Unit = {},
    onSelectAllBeats: (Boolean) -> Unit = {},
    // PHASE 7 — smart highlight flow.
    onAnalyzeHighlights: () -> Unit = {},
    onCancelHighlightAnalysis: () -> Unit = {},
    onApplyHighlights: () -> Unit = {},
    onDiscardHighlights: () -> Unit = {},
    onClearHighlights: () -> Unit = {},
    onToggleHighlight: (String) -> Unit = {},
    onSelectAllHighlights: (Boolean) -> Unit = {},
    onSeekHighlight: (String) -> Unit = {},
    onAudioClipSelected: (String?) -> Unit = {}
) {
    // ── 2026-09-30 — the sheet must outlive its own exit animation ──────
    //
    // `AnimatedVisibility` at the call site keeps this composable in
    // composition until the collapse has finished, but by then
    // `uiState.toolPanel` is already null. Without a latch the title would
    // blank and `SubPanelContent`'s `when` would match no branch at all, so
    // the sheet would blink empty instead of visibly sliding away.
    val lastPanelState = remember { mutableStateOf(uiState.toolPanel) }
    LaunchedEffect(uiState.toolPanel) {
        uiState.toolPanel?.let { lastPanelState.value = it }
    }
    val panel = uiState.toolPanel ?: lastPanelState.value
    if (panel == null) return

    // `SubPanelContent` dispatches on `uiState.toolPanel`, and several inner
    // panels read it back (Adjust's `isAdjustPanelOpen`, the studio tabs…),
    // so hand it a copy that still names the panel being animated out. This
    // copy only exists during the exit transition.
    val contentUiState = if (uiState.toolPanel != panel) {
        uiState.copy(toolPanel = panel)
    } else {
        uiState
    }

    // Height budget. The enclosing Column measures the sheet AFTER the top
    // bar and the playback strip, so `maxHeight` here is exactly the
    // post-chrome space — the same space the weighted video preview competes
    // for. `SubPanelHeight.resolveHeightDp` hands the preview a guaranteed
    // floor instead of letting the sheet take a flat 50% of it, and keeps
    // slider panels compact while letting galleries breathe.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val sheetHeightDp = SubPanelHeight.resolveHeightDp(
            availableDp = maxHeight.value,
            panel = panel
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeightDp.dp)
                .background(
                    // 2026-10-16 (Phase 2) — was a pair of raw literals
                    // (`0xFF13131A` / `0xFF0F0F14`) that existed nowhere else
                    // in the app. The sheet is the deepest layer on screen,
                    // so it uses the recessed-surface tokens: [SurfaceInset]
                    // fading into [SurfaceSunken].
                    Brush.verticalGradient(
                        listOf(
                            SurfaceInset,
                            SurfaceSunken
                        )
                    )
                )
                .border(1.dp, HairlineSubtle, RoundedCornerShape(0.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            val panelScrollState = rememberScrollState()
            val panelScope = rememberCoroutineScope()

            // 2026-09-20 FIX: the drag handle + header row are FIXED
            // (never scroll away). Only the SubPanelContent body below
            // is scrollable. Previously everything was in one
            // verticalScroll Column, so the drag handle gesture
            // scrolled the entire body — pushing the studio tabs above
            // the fold on small screens.
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 2026-09-13 — drag handle. Dragging scrolls ONLY the
                // body below the header.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    panelScrollState.dispatchRawDelta(-dragAmount)
                                },
                                onDragEnd = {
                                    val half = panelScrollState.maxValue / 2
                                    val target = if (panelScrollState.value > half) {
                                        panelScrollState.maxValue
                                    } else 0
                                    panelScope.launch { panelScrollState.animateScrollTo(target) }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .width(44.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.25f))
                    )
                }
                // Header row with title + close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        // 2026-09-30 — reads the latched `panel`, not
                        // `uiState.toolPanel`: during the exit animation the
                        // latter is already null and the header would blank
                        // mid-collapse.
                        text = panel.title(),
                        style = MaterialTheme.typography.titleMedium,
                        color = LabelPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            // 2026-10-16 (Phase 2) — 28dp → 40dp. This is the
                            // only way out of every tool panel, and 28dp is
                            // well under the touch-target floor. The fill is
                            // the faint hairline token so the larger circle
                            // stays visually quiet.
                            .size(40.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(HairlineSubtle)
                            .border(1.dp, Hairline, RoundedCornerShape(percent = 50))
                            .clickable(role = Role.Button) { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "بستن",
                            tint = LabelPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // 2026-09-20 FIX: scrollable body — only the panel
                // content scrolls; the header stays pinned.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(panelScrollState)
                ) {
                    // Panel body
                    SubPanelContent(
                    // 2026-09-30 — `contentUiState`, not `uiState`: it still
                    // names the panel while the sheet collapses, so the
                    // dispatch below keeps rendering instead of falling
                    // through to an empty body mid-animation.
                    uiState = contentUiState,
                    currentPositionMs = currentPositionMs,
                    projectDuration = projectDuration,
                    onClose = onClose,
                    onVolumeChanged = onVolumeChanged,
                    onBrightnessChanged = onBrightnessChanged,
                    onContrastChanged = onContrastChanged,
                    onSaturationChanged = onSaturationChanged,
                    onPlaybackSpeedChanged = onPlaybackSpeedChanged,
                    onRotationChanged = onRotationChanged,
                    onRotate90 = onRotate90,
                    onTrimStartChanged = onTrimStartChanged,
                    onTrimEndChanged = onTrimEndChanged,
                    onClipTrimChanged = onClipTrimChanged,
                    onPanelSelected = onPanelSelected,
                    onAutoCaption = onAutoCaption,
                    onToggleTrimMode = onToggleTrimMode,
                    onResetEffects = onResetEffects,
                    onAddText = onAddText,
                    onApplyText = onApplyText,
                    onUpdateSelectedFont = onUpdateSelectedFont,
                    onUpdateSelectedFontSize = onUpdateSelectedFontSize,
                    onUpdateSelectedColor = onUpdateSelectedColor,
                    onApplySelectedStyle = onApplySelectedStyle,
                    onApplyStyleToAllTexts = onApplyStyleToAllTexts,
                    onUpdateSelectedRotation = onUpdateSelectedRotation,
                    onUpdateSelectedStroke = onUpdateSelectedStroke,
                    onUpdateSelectedShadow = onUpdateSelectedShadow,
                    onUpdateSelectedBackground = onUpdateSelectedBackground,
                    onUpdateSelectedAnimation = onUpdateSelectedAnimation,
                    onUpdateSelectedDuration = onUpdateSelectedDuration,
                    onMoveSelectedTextToPlayhead = onMoveSelectedTextToPlayhead,
                    onAddParagraph = onAddParagraph,
                    onSnapSelectedText = onSnapSelectedText,
                    onNudgeSelectedText = onNudgeSelectedText,
                    onEffectSelected = onEffectSelected,
                    onSetTransition = onSetTransition,
                    onPickMusic = onPickMusic,
                    onExtractAudioFromVideo = onExtractAudioFromVideo,
                    onClipAnimationChanged = onClipAnimationChanged,
                    onClipCropChanged = onClipCropChanged,
                    onSetClipSpeed = onSetClipSpeed,
                    onGenerateSubtitles = onGenerateSubtitles,
                    onSelectWhisperModel = onSelectWhisperModel,
                    onSubtitleLanguageSelected = onSubtitleLanguageSelected,
                    onSelectAccuracyMode = onSelectAccuracyMode,
                    onSaveCustomDictionary = onSaveCustomDictionary,
                    onSelectWordsPerCaption = onSelectWordsPerCaption,
                    onDownloadWhisperModel = onDownloadWhisperModel,
                    onToggleMuteSelected = onToggleMuteSelected,
                    onUpdateAudioClipVolume = onUpdateAudioClipVolume,
                    onUpdateAudioClipMute = onUpdateAudioClipMute,
                    onExportSrt = onExportSrt,
                    // PHASE B4 — pass-through to the SRT import path.
                    onImportSrt = onImportSrt,
                    onConsumeSrtImportState = onConsumeSrtImportState,
                    // PHASE B3 — pass-through.
                    onCancelSubtitleGeneration = onCancelSubtitleGeneration,
                    onUpdateTextOverlay = onUpdateTextOverlay,
                    onDeleteTextOverlay = onDeleteTextOverlay,
                    onDeleteAllAiSubtitles = onDeleteAllAiSubtitles,
                    onClearTextSelection = onClearTextSelection,
                    // 2026-10-13 — subtitle editing workflow pass-through.
                    onSplitSubtitle = onSplitSubtitle,
                    onMergeSubtitleWithNext = onMergeSubtitleWithNext,
                    onRetimeSubtitle = onRetimeSubtitle,
                    onRetimeSubtitleWord = onRetimeSubtitleWord,
                    onResyncSubtitleText = onResyncSubtitleText,
                    onBeginGlobalSubtitleResize = onBeginGlobalSubtitleResize,
                    onGlobalSubtitleSizeLive = onGlobalSubtitleSizeLive,
                    onEndGlobalSubtitleResize = onEndGlobalSubtitleResize,
                    onSetGlobalSubtitleSize = onSetGlobalSubtitleSize,
                    onScaleAllSubtitles = onScaleAllSubtitles,
                    // 2026-10-13 — custom-font pass-through.
                    customFonts = customFonts,
                    onImportCustomFont = onImportCustomFont,
                    onDeleteCustomFont = onDeleteCustomFont,
                    // 2026-10-13 — video progress bar pass-through.
                    onProgressBarSettingsChanged = onProgressBarSettingsChanged,
                    // 2026-10-13 — voice changer pass-through.
                    onVoiceEffectChanged = onVoiceEffectChanged,
                    // 2026-10-13 — PiP shape mask pass-through.
                    onOverlayMaskChanged = onOverlayMaskChanged,
                    // 2026-10-13 — chroma key pass-through.
                    onOverlayChromaChanged = onOverlayChromaChanged,
                    // PHASE B5 — pass-through to the production
                    // subtitle styling handlers.
                    onApplySubtitleStyle = onApplySubtitleStyle,
                    onApplySubtitleStyleToAll = onApplySubtitleStyleToAll,
                    onPickOverlay = onPickOverlay,
                    onCanvasPresetChanged = onCanvasPresetChanged,
                    onCanvasBackgroundChanged = onCanvasBackgroundChanged,
                    onClipFillModeChanged = onClipFillModeChanged,
                    onClipTransformChanged = onClipTransformChanged,
                    onResetClipTransform = onResetClipTransform,
                    onClipFadeInChanged = onClipFadeInChanged,
                    onClipFadeOutChanged = onClipFadeOutChanged,
                    onClipOpacityChanged = onClipOpacityChanged,
                    onColorGradingChanged = onColorGradingChanged,
                    onResetColorGrading = onResetColorGrading,
                    onBeforeAfterChange = onBeforeAfterChange,
                    onUpscalerPresetChanged = onUpscalerPresetChanged,
                    onUpscalerStrengthChanged = onUpscalerStrengthChanged,
                    onResetUpscaler = onResetUpscaler,
                    onUpscaleExportClicked = onUpscaleExportClicked,
                    // 2026-09-01 AI tools
                    onRunAiTool = onRunAiTool,
                    onCancelAiTool = onCancelAiTool,
                    onResetAiTool = onResetAiTool,
                    onEnsureMattingModelReady = onEnsureMattingModelReady,
                    onMattingBackgroundModeChanged = onMattingBackgroundModeChanged,
                    onEnsureAutoframeModelReady = onEnsureAutoframeModelReady,
                    autoframeModelStatusText = autoframeModelStatusText,
                    autoframeModelAvailable = autoframeModelAvailable,
                    pendingTrackingRoi = pendingTrackingRoi,
                    motionTrackBackendText = motionTrackBackendText,
                    onTrackingRoiChanged = onTrackingRoiChanged,
                    onEnterTrackingSelection = onEnterTrackingSelection,
                    onExitTrackingSelection = onExitTrackingSelection,
                    onPickBackgroundMedia = onPickBackgroundMedia,
                    mattingModelAvailable = mattingModelAvailable,
                    upscaleModelState = upscaleModelState,
                    upscaleModelReady = upscaleModelReady,
                    upscaleModelStatusText = upscaleModelStatusText,
                    onEnsureUpscaleModelReady = onEnsureUpscaleModelReady,
                    upscaleProgress = upscaleProgress,
                    effectiveUpscaleProfileLabel = effectiveUpscaleProfileLabel,
                    onUpscaleGenerateClicked = onUpscaleGenerateClicked,
                    onUpscaleCancelClicked = onUpscaleCancelClicked,
                    onDismissUpscaleResult = onDismissUpscaleResult,
                    onEnsureOcrModelReady = onEnsureOcrModelReady,
                    ocrModelStatusText = ocrModelStatusText,
                    ocrModelAvailable = ocrModelAvailable,
                                    onApplyOcr = onApplyOcr,
                    onApplySubtitleTemplate = onApplySubtitleTemplate,
                    onPickStudioTemplate = onPickStudioTemplate,
                    onAddTemplateSubtitle = onAddTemplateSubtitle,
                    onOpenAutoSubtitles = onOpenAutoSubtitles,
                    studioTemplateId = studioTemplateId,
                    onAutoCutSilence = onAutoCutSilence,
                    onCancelAutoCutSilence = onCancelAutoCutSilence,
                    onClearAutoCutSilenceResult = onClearAutoCutSilenceResult,
                    onAnalyzeSilence = onAnalyzeSilence,
                    onApplySilencePreview = onApplySilencePreview,
                    onDiscardSilencePreview = onDiscardSilencePreview,
                    onToggleSilenceSegment = onToggleSilenceSegment,
                    onSelectAllSilence = onSelectAllSilence,
                    onAnalyzeJumpCut = onAnalyzeJumpCut,
                    onPreviewVoiceEnhancement = onPreviewVoiceEnhancement,
                    onApplyVoiceEnhancement = onApplyVoiceEnhancement,
                    onDiscardVoiceEnhancement = onDiscardVoiceEnhancement,
                    onClearVoiceEnhancement = onClearVoiceEnhancement,
                    onCancelVoiceEnhance = onCancelVoiceEnhance,
                    onAnalyzeReframe = onAnalyzeReframe,
                    onApplyReframe = onApplyReframe,
                    onDiscardReframe = onDiscardReframe,
                    onClearReframe = onClearReframe,
                    onCancelReframe = onCancelReframe,
                    onSpeedCurveChanged = onSpeedCurveChanged,
                    onClearSpeedCurve = onClearSpeedCurve,
                    onDetectBeats = onDetectBeats,
                    onCancelBeats = onCancelBeats,
                    onClearBeats = onClearBeats,
                    onAutoSplitOnBeats = onAutoSplitOnBeats,
                    onAnalyzeBeatSync = onAnalyzeBeatSync,
                    onEnsureBeatSyncBeats = onEnsureBeatSyncBeats,
                    onCancelBeatSync = onCancelBeatSync,
                    onApplyBeatSync = onApplyBeatSync,
                    onDiscardBeatSync = onDiscardBeatSync,
                    onToggleBeatCandidate = onToggleBeatCandidate,
                    onSelectAllBeats = onSelectAllBeats,
                    onAnalyzeHighlights = onAnalyzeHighlights,
                    onCancelHighlightAnalysis = onCancelHighlightAnalysis,
                    onApplyHighlights = onApplyHighlights,
                    onDiscardHighlights = onDiscardHighlights,
                    onClearHighlights = onClearHighlights,
                    onToggleHighlight = onToggleHighlight,
                    onSelectAllHighlights = onSelectAllHighlights,
                    onSeekHighlight = onSeekHighlight,
                    onAudioClipSelected = onAudioClipSelected
                )
                } // inner scrollable Column
            } // outer fixed Column
        }
    }
}

@Composable
private fun SubPanelContent(
    uiState: EditorUiState,
    currentPositionMs: Long,
    projectDuration: Long,
    onClose: () -> Unit,
    onVolumeChanged: (String, Float) -> Unit,
    onBrightnessChanged: (Float) -> Unit,    onContrastChanged: (Float) -> Unit,
    onSaturationChanged: (Float) -> Unit,
    onPlaybackSpeedChanged: (Float) -> Unit,
    onRotationChanged: (Int) -> Unit,
    onRotate90: () -> Unit,
    onTrimStartChanged: (Long) -> Unit,
    onTrimEndChanged: (Long) -> Unit,
    onClipTrimChanged: (String, Long, Long) -> Unit,
    // PHASE A (Persistence Integrity) — lets a panel hand the user on
    // to a sibling panel (the Effects panel's "Trim clip" pill) instead
    // of pretending to do the work itself.
    onPanelSelected: (ToolPanel?) -> Unit = {},
    // 2026-10-05 — AUTO-CAPTION one-tap (SUBTITLES panel) / passthrough.
    onAutoCaption: (String?) -> Unit = {},
    onToggleTrimMode: () -> Unit,
    onResetEffects: () -> Unit,
    onAddText: (String, Long, Long, PersianFont) -> Unit,
    // 2026-09-06 — Text tool «اعمال» pass-through: add at playhead +
    // select the new overlay.
    onApplyText: (String, Long, Long, String) -> Unit = { _, _, _, _ -> },
    // 2026-09-06 — Paragraph-panel editing of the SELECTED overlay.
    onUpdateSelectedFont: (String) -> Unit = {},
    onUpdateSelectedFontSize: (Float) -> Unit = {},
    onUpdateSelectedColor: (Int) -> Unit = {},
    onApplySelectedStyle: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    // 2026-09-08 - Text panel style tab, apply-to-every-text button.
    onApplyStyleToAllTexts: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    onMoveSelectedTextToPlayhead: () -> Unit = {},
    // 2026-09-06 — full TEXT SYSTEM setters.
    onUpdateSelectedRotation: (Float) -> Unit = {},
    onUpdateSelectedStroke: (Boolean) -> Unit = {},
    onUpdateSelectedShadow: (Boolean) -> Unit = {},
    onUpdateSelectedBackground: (Boolean) -> Unit = {},
    onUpdateSelectedAnimation: (com.aicreatorstudio.app.model.TextOverlayAnimation) -> Unit = {},
    onUpdateSelectedDuration: (Long) -> Unit = {},
    // 2026-09-06 — Paragraph tool pass-throughs.
    onAddParagraph: (String, Long, Long, PersianFont, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onSnapSelectedText: (Float, Float) -> Unit = { _, _ -> },
    onNudgeSelectedText: (Float, Float) -> Unit = { _, _ -> },
    onEffectSelected: (VideoEffect) -> Unit,
    onSetTransition: (com.aicreatorstudio.app.model.TransitionScope,
                      com.aicreatorstudio.app.model.TransitionConfig?) -> Unit,

    onPickMusic: () -> Unit,
    // 2026-10-13 — Extract Audio from Video: opens the system video picker
    // via the host's launcher. Default keeps every existing call site.
    onExtractAudioFromVideo: () -> Unit = {},
    onClipAnimationChanged: (String, ClipAnimation) -> Unit,
    onClipCropChanged: (String, CropPreset) -> Unit,
    onSetClipSpeed: (String, Float) -> Unit,
    onGenerateSubtitles: (PersianFont) -> Unit,
    onSelectWhisperModel: (WhisperModelSpec) -> Unit,
    // 2026-09-06 — subtitle language picker pass-through.
    onSubtitleLanguageSelected: (com.aicreatorstudio.app.ai.SubtitleLanguage) -> Unit = {},
    onSelectAccuracyMode: (com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode) -> Unit = {},
    onSaveCustomDictionary: (String) -> Unit = {},
    // 2026-09-15 — words-per-caption (Premiere-style) pass-through.
    onSelectWordsPerCaption: (Int) -> Unit = {},
    // 2026-09-06 — model download pass-through for the Subs card.
    onDownloadWhisperModel: (WhisperModelSpec) -> Unit = {},
    // 2026-09-06 — per-clip mute toggle for the Volume panel.
    onToggleMuteSelected: () -> Unit = {},
    // 2026-09-06 — per-audio-object setters for the Volume panel.
    onUpdateAudioClipVolume: (String, Float) -> Unit = { _, _ -> },
    onUpdateAudioClipMute: (String, Boolean) -> Unit = { _, _ -> },
    onExportSrt: () -> Unit,
    // PHASE B4 — SRT import. Same shape as onExportSrt: the
    // host function (EditorScreenBody, which holds a
    // viewModel reference) wires this through to
    // viewModel.onImportSrtClicked().
    onImportSrt: () -> Unit,
    onConsumeSrtImportState: () -> Unit,
    // PHASE B3 — see the matching pass-through in SubPanelRow.
    onCancelSubtitleGeneration: () -> Unit,
    onUpdateTextOverlay: (String, String) -> Unit,
    onDeleteTextOverlay: (String) -> Unit,
    onDeleteAllAiSubtitles: () -> Unit,
    onClearTextSelection: () -> Unit,
    // 2026-10-13 — CapCut-style subtitle editing workflow.
    onSplitSubtitle: (String, Int) -> Unit = { _, _ -> },
    onMergeSubtitleWithNext: (String) -> Unit = {},
    onRetimeSubtitle: (String, Long, Long) -> Unit = { _, _, _ -> },
    onRetimeSubtitleWord: (String, Int, Long, Long) -> Unit = { _, _, _, _ -> },
    onResyncSubtitleText: (String) -> Unit = {},
    // 2026-10-15 — global subtitle font size (batch resize of every
    // subtitle in one undo step). Inert defaults keep every existing
    // caller compiling; see EditorViewModel.beginGlobalSubtitleResize.
    onBeginGlobalSubtitleResize: () -> Unit = {},
    onGlobalSubtitleSizeLive: (Float) -> Unit = {},
    onEndGlobalSubtitleResize: () -> Unit = {},
    onSetGlobalSubtitleSize: (Float) -> Unit = {},
    onScaleAllSubtitles: (Float) -> Unit = {},
    // 2026-10-13 — Custom Font Importer pass-through (same shape as the
    // Subtitle Studio wiring above).
    customFonts: List<com.aicreatorstudio.app.model.CustomFont> = emptyList(),
    onImportCustomFont: (String) -> Unit = {},
    onDeleteCustomFont: (String) -> Unit = {},
    // 2026-10-13 — Video progress bar pass-through (same shape as the
    // SubPanelRow parameter above; see that KDoc for the flag's meaning).
    onProgressBarSettingsChanged:
        (com.aicreatorstudio.app.model.ProgressBarSettings, Boolean) -> Unit = { _, _ -> },
    // 2026-10-13 — voice changer pass-through (same shape as the
    // SubPanelRow parameter above).
    onVoiceEffectChanged:
        (String, com.aicreatorstudio.app.model.VoiceEffect) -> Unit = { _, _ -> },
    // 2026-10-13 — PiP shape mask pass-through. The Boolean is the same
    // live/commit flag the progress-bar and voice panels use: `false` for
    // a slider drag frame, `true` for a discrete edit or a release.
    onOverlayMaskChanged: (
        String,
        com.aicreatorstudio.app.model.OverlayMaskSettings,
        Boolean
    ) -> Unit = { _, _, _ -> },
    // 2026-10-13 — chroma key pass-through. Same live/commit contract as
    // `onOverlayMaskChanged` above; see that KDoc for what the Boolean means.
    onOverlayChromaChanged: (
        String,
        com.aicreatorstudio.app.model.ChromaKeySettings,
        Boolean
    ) -> Unit = { _, _, _ -> },
    // PHASE B5 — production subtitle styling handlers.
    onApplySubtitleStyle: (String, com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = { _, _ -> },
    onApplySubtitleStyleToAll: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    onPickOverlay: () -> Unit,
    // Canvas / transform / background handlers (added in 2026-08-28 refactor)
    onCanvasPresetChanged: (CanvasPreset) -> Unit,
    onCanvasBackgroundChanged: (CanvasBackground) -> Unit,
    onClipFillModeChanged: (String, FillMode) -> Unit,
    onClipTransformChanged: ClipIdTransformUpdater,
    onResetClipTransform: (String) -> Unit,
    // Per-clip fade / opacity handlers (added in toolbar audit).
    onClipFadeInChanged: (String, Float) -> Unit,
    onClipFadeOutChanged: (String, Float) -> Unit,
    onClipOpacityChanged: (String, Float) -> Unit,
    // 2026-08-29 Lumetri color grading handlers.
    onColorGradingChanged: (com.aicreatorstudio.app.model.ColorGrading) -> Unit,
    onResetColorGrading: () -> Unit,
    onBeforeAfterChange: (Boolean) -> Unit,
    // AI Upscaler handlers.
    onUpscalerPresetChanged: (com.aicreatorstudio.app.model.UpscalerPreset) -> Unit,
    onUpscalerStrengthChanged: (Float) -> Unit,
    onResetUpscaler: () -> Unit,
    onUpscaleExportClicked: () -> Unit,
    // 2026-09-01 AI tool handlers (Background Remove / Auto Frame / Voice Clean).
    onRunAiTool: (String, com.aicreatorstudio.app.ai.AiToolOptions) -> Unit,
    onCancelAiTool: (String) -> Unit,
    onResetAiTool: (String) -> Unit,
    onEnsureMattingModelReady: () -> Unit,
    onMattingBackgroundModeChanged: (com.aicreatorstudio.app.model.MattingBackgroundMode) -> Unit = {},
    onEnsureAutoframeModelReady: () -> Unit = {},
    autoframeModelStatusText: String = "",
    autoframeModelAvailable: Boolean = true,
    pendingTrackingRoi: android.graphics.RectF? = null,
    motionTrackBackendText: String = "",
    onTrackingRoiChanged: (android.graphics.RectF) -> Unit = {},
    onEnterTrackingSelection: () -> Unit = {},
    onExitTrackingSelection: () -> Unit = {},
    onPickBackgroundMedia: () -> Unit,
    mattingModelAvailable: Boolean = false,
    upscaleModelState: AiToolState = AiToolState(toolId = "upscale"),
    upscaleModelReady: Boolean = false,
    upscaleModelStatusText: String = "",
    onEnsureUpscaleModelReady: () -> Unit = {},
    upscaleProgress: UpscaleProgress? = null,
    effectiveUpscaleProfileLabel: String? = null,
    onUpscaleGenerateClicked: () -> Unit = {},
    onUpscaleCancelClicked: () -> Unit = {},
    onDismissUpscaleResult: () -> Unit = {},
    // 2026-09-10 AI OCR wiring (content level).
    onEnsureOcrModelReady: () -> Unit = {},
    ocrModelStatusText: String = "",
    ocrModelAvailable: Boolean = false,
    onApplyOcr: (String, Long, Int, Float) -> Unit = { _, _, _, _ -> },
    // 2026-09-11 «زیرنویس فارسی» studio wiring (content level).
    onApplySubtitleTemplate: (com.aicreatorstudio.app.model.SubtitleTemplate) -> Unit = {},
    onPickStudioTemplate: (com.aicreatorstudio.app.model.SubtitleTemplate) -> Unit = {},
    onAddTemplateSubtitle: (String, com.aicreatorstudio.app.model.SubtitleTemplate, Long, Long) -> Unit = { _, _, _, _ -> },
    onOpenAutoSubtitles: () -> Unit = {},
    studioTemplateId: String? = null,
    // 2026-09-21 — AI Auto Silence Cut pass-throughs.
    onAutoCutSilence: (Float, Float, Float) -> Unit = { _, _, _ -> },
    onCancelAutoCutSilence: () -> Unit = {},
    onClearAutoCutSilenceResult: () -> Unit = {},
    // PHASE 1 — analyze → preview → apply pass-throughs.
    onAnalyzeSilence: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    onApplySilencePreview: () -> Unit = {},
    onDiscardSilencePreview: () -> Unit = {},
    onToggleSilenceSegment: (String) -> Unit = {},
    onSelectAllSilence: (Boolean) -> Unit = {},
    onAnalyzeJumpCut: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    // PHASE 4 — voice enhancement preview flow.
    onPreviewVoiceEnhancement: (com.aicreatorstudio.app.ai.voice.VoiceEnhancementPreset) -> Unit = {},
    onApplyVoiceEnhancement: () -> Unit = {},
    onDiscardVoiceEnhancement: () -> Unit = {},
    onClearVoiceEnhancement: () -> Unit = {},
    onCancelVoiceEnhance: () -> Unit = {},
    // PHASE 5 — smart reframe preview flow.
    onAnalyzeReframe: (Float, Float) -> Unit = { _, _ -> },
    onApplyReframe: () -> Unit = {},
    onDiscardReframe: () -> Unit = {},
    onClearReframe: () -> Unit = {},
    onCancelReframe: () -> Unit = {},
    // 2026-09-29 — Speed Ramping / curves.
    onSpeedCurveChanged: (String, com.aicreatorstudio.app.model.SpeedCurve?) -> Unit = { _, _ -> },
    onClearSpeedCurve: (String) -> Unit = {},
    // 2026-09-29 — Beat Detection & timeline markers.
    onDetectBeats: (com.aicreatorstudio.app.util.BeatDetector.BeatSensitivity) -> Unit = {},
    onCancelBeats: () -> Unit = {},
    onClearBeats: () -> Unit = {},
    onAutoSplitOnBeats: () -> Unit = {},
    // PHASE 6 — beat sync suggestion flow.
    onAnalyzeBeatSync: (com.aicreatorstudio.app.util.BeatSyncMode) -> Unit = {},
    onEnsureBeatSyncBeats: () -> Unit = {},
    onCancelBeatSync: () -> Unit = {},
    onApplyBeatSync: () -> Unit = {},
    onDiscardBeatSync: () -> Unit = {},
    onToggleBeatCandidate: (String) -> Unit = {},
    onSelectAllBeats: (Boolean) -> Unit = {},
    // PHASE 7 — smart highlight flow.
    onAnalyzeHighlights: () -> Unit = {},
    onCancelHighlightAnalysis: () -> Unit = {},
    onApplyHighlights: () -> Unit = {},
    onDiscardHighlights: () -> Unit = {},
    onClearHighlights: () -> Unit = {},
    onToggleHighlight: (String) -> Unit = {},
    onSelectAllHighlights: (Boolean) -> Unit = {},
    onSeekHighlight: (String) -> Unit = {},
    onAudioClipSelected: (String?) -> Unit = {}
) {
    val selectedClip = uiState.selectedClip
    when (uiState.toolPanel) {
        ToolPanel.TRIM -> {
            val clip = selectedClip
            if (clip != null) {
                // PHASE A (Persistence Integrity) — `setClipTrim` takes
                // TIMELINE positions, but this panel used to hand it the
                // clip's SOURCE-space `trimStartMs` / `trimEndMs`. The
                // two agree for the common single clip at position 0,
                // which is exactly why the bug hid: on any clip with a
                // timeline offset the deltas came out wrong and the clip
                // was trimmed somewhere other than the slider showed.
                val trimStartOnTimeline = clip.timelineStartMs.coerceAtLeast(0L)
                val trimEndOnTimeline = trimStartOnTimeline + clip.effectiveDurationMs
                TrimPanel(
                    trimStartMs = trimStartOnTimeline,
                    trimEndMs = trimEndOnTimeline,
                    durationMs = trimEndOnTimeline,
                    isTrimMode = uiState.isTrimMode,
                    onTrimStartChanged = { onClipTrimChanged(clip.id, it, trimEndOnTimeline) },
                    onTrimEndChanged = { onClipTrimChanged(clip.id, trimStartOnTimeline, it) },
                    onToggleTrimMode = onToggleTrimMode
                )
            } else {
                Text(
                    text = "Select a clip to trim.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabelSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        ToolPanel.SPEED -> {
            val clip = selectedClip
            if (clip != null) {
                Column {
                    // Constant-speed presets first — they are the
                    // long-standing control and stay exactly where
                    // users expect them. The ramp editor sits *below*
                    // and multiplies whatever they set, rather than
                    // replacing them. See [EditorViewModel.setClipSpeedCurve].
                    SpeedPanel(
                        currentSpeed = clip.speed,
                        onSpeedSelected = { onSetClipSpeed(clip.id, it) }
                    )
                    Spacer(Modifier.height(18.dp))
                    SpeedCurvePanel(
                        curve = clip.speedCurve,
                        sourceDurationMs = clip.effectiveDurationMs,
                        onCurveChanged = { onSpeedCurveChanged(clip.id, it) },
                        onResetCurve = { onClearSpeedCurve(clip.id) }
                    )
                }
            } else {
                Text(
                    text = "Select a clip to change its speed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabelSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        ToolPanel.VOLUME -> {
            // 2026-09-06 — when an AUDIO object is selected (Video
            // Audio / Music / SFX lane), the sheet binds to that audio
            // object instead of the video clip.
            val selectedAudio = uiState.project?.audioClips
                ?.firstOrNull { it.id == uiState.selectedAudioClipId }
            val clip = selectedClip
            when {
                selectedAudio != null -> VolumeSheet(
                    clipLabel = selectedAudio.fileName,
                    clipVolume = if (selectedAudio.muted) 0f else selectedAudio.volume,
                    isMuted = selectedAudio.muted,
                    onToggleMute = {
                        onUpdateAudioClipMute(selectedAudio.id, !selectedAudio.muted)
                    },
                    onVolumeChanged = { v ->
                        onUpdateAudioClipVolume(selectedAudio.id, v)
                    },
                    onDismiss = onClose
                )
                clip != null -> VolumeSheet(
                    clipLabel = clip.fileName,
                    clipVolume = clip.volume,
                    isMuted = clip.volume == 0f,
                    onToggleMute = { onToggleMuteSelected() },
                    onVolumeChanged = { onVolumeChanged(clip.id, it) },
                    onDismiss = onClose
                )
                else -> Text(
                    text = "Select a clip or audio track to adjust its volume.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        ToolPanel.TEXT -> TextOverlayPanel(
            currentPositionMs = currentPositionMs,
            durationMs = projectDuration,
            onAddText = { text, start, end, fontName ->
                onAddText(text, start, end, PersianFont.from(fontName))
            },
            // 2026-09-06 — «اعمال در محل پلی‌هد»: add at the playhead
            // AND select the new overlay for immediate drag / styling.
            onApplyAtPlayhead = { text, start, end, fontName ->
                onApplyText(text, start, end, fontName)
            },
            // 2026-09-08 — style tab. The selected overlay drives the
            // draft; every mutation applies live via the shared setters.
            selectedOverlay = uiState.textOverlays
                .firstOrNull { it.id == uiState.selectedTextId },
            onUpdateFont = { fontName -> onUpdateSelectedFont(fontName) },
            onUpdateFontSize = { size -> onUpdateSelectedFontSize(size) },
            onUpdateColor = { argb -> onUpdateSelectedColor(argb) },
            onApplyStyle = { style -> onApplySelectedStyle(style) },
            onApplyStyleToAll = { style -> onApplyStyleToAllTexts(style) },
            // 2026-10-13 — Custom Font Importer. Same three callbacks the
            // Subtitle Studio strip uses; the font picker here reuses the
            // shared CustomFontStrip so both surfaces cannot drift. These
            // are the SubPanelContent pass-through parameters — this
            // composable does not hold the ViewModel.
            customFonts = customFonts,
            onImportCustomFont = onImportCustomFont,
            onDeleteCustomFont = onDeleteCustomFont
        )
        // 2026-09-06 — Paragraph tool: multi-line text with a 3x3
        // position grid + nudge pad. The grid snaps the selected
        // overlay when one is selected, otherwise it seeds the
        // anchor for the new paragraph.
        //
        // When a text tile is tapped on the timeline this panel opens
        // BOUND to the selected overlay: edit text / font / size /
        // color, apply a style preset, and move the overlay to the
        // playhead.
        ToolPanel.PARAGRAPH -> ParagraphTextPanel(
            currentPositionMs = currentPositionMs,
            durationMs = projectDuration,
            selectedOverlay = uiState.textOverlays
                .firstOrNull { it.id == uiState.selectedTextId },
            onAddParagraph = { text, start, end, font, x, y ->
                onAddParagraph(text, start, end, font, x, y)
            },
            onSnapSelected = { x, y -> onSnapSelectedText(x, y) },
            onNudgeSelected = { dx, dy -> onNudgeSelectedText(dx, dy) },
            onDeselect = onClearTextSelection,
            onUpdateSelectedText = { newText ->
                onUpdateTextOverlay(
                    uiState.selectedTextId ?: return@ParagraphTextPanel,
                    newText
                )
            },
            onUpdateSelectedFont = { font ->
                onUpdateSelectedFont(font.name)
            },
            onUpdateSelectedFontSize = { size ->
                onUpdateSelectedFontSize(size)
            },
            onUpdateSelectedColor = { argb ->
                onUpdateSelectedColor(argb)
            },
            onApplySelectedStyle = { style ->
                onApplySelectedStyle(style)
            },
            onUpdateSelectedRotation = { deg ->
                onUpdateSelectedRotation(deg)
            },
            onUpdateSelectedStroke = { enabled ->
                onUpdateSelectedStroke(enabled)
            },
            onUpdateSelectedShadow = { enabled ->
                onUpdateSelectedShadow(enabled)
            },
            onUpdateSelectedBackground = { enabled ->
                onUpdateSelectedBackground(enabled)
            },
            onUpdateSelectedAnimation = { anim ->
                onUpdateSelectedAnimation(anim)
            },
            onUpdateSelectedDuration = { ms ->
                onUpdateSelectedDuration(ms)
            },
            onMoveSelectedToPlayhead = {
                onMoveSelectedTextToPlayhead()
            }
        )
        ToolPanel.SUBTITLES -> AISubtitleCard(
            selectedClip = selectedClip,
            // 2026-09-11 — show WHAT the next Generate will transcribe:
            // the tapped audio file (paired video audio) or the clip.
            generationSourceLabel = uiState.selectedAudioClipId
                ?.let { id -> uiState.project?.audioClips?.firstOrNull { it.id == id } }
                ?.let { "🎵 ${it.fileName}" }
                ?: selectedClip?.let { "🎬 ${it.fileName}" },
            // PHASE B3 — bind the entire state machine to the
            // card. The card decides which label + which
            // buttons to show from `state.isRunning` /
            // `state.isTerminal` / `state.userMessage` /
            // `state.progressFraction`.
            state = uiState.subtitleGenerationState,
            // 2026-09-07 GATE FIX — a subtitle exists whenever ANY
            // subtitle-source overlay is present: AI captions
            // (words-bearing) OR SRT imports (subtitleSource == "srt").
            // Previously the gate required words.isNotEmpty(), which
            // permanently disabled "Export SRT" for SRT-only imports,
            // and it included the transient srtExportTrigger, which
            // flipped the Generate button to "Generate again" for one
            // frame whenever the export picker launched.
            hasGeneratedSubtitles = uiState.textOverlays.isNotEmpty(),
            activeTranscriptionEngine = uiState.activeTranscriptionEngine,
            selectedModel = uiState.selectedWhisperModel,
            // 2026-10-10 — key-aware cloud row + missing-key warning
            // banner so the card never shows false "Cloud" ready.
            hasGroqApiKey = uiState.hasGroqApiKey,
            aiModelStatuses = uiState.aiModelStatuses,
            // 2026-09-06 — download button + progress bar wiring.
            aiModelDownloadProgress = uiState.aiModelDownloadProgress,
            onDownloadModel = onDownloadWhisperModel,
            // The selected text overlay may be either an AI
            // subtitle (words.isNotEmpty()) or a user-added
            // Text overlay (words.isEmpty()). The card renders
            // the inline editor only when it is an AI
            // subtitle.
            selectedTextOverlay = uiState.textOverlays
                .firstOrNull { it.id == uiState.selectedTextId },
            onSelectModel = onSelectWhisperModel,
            // 2026-09-06 — language picker wiring.
            initialLanguage = uiState.selectedSubtitleLanguage,
            onSelectLanguage = onSubtitleLanguageSelected,
            // 2026-09-09 — accuracy mode + custom dictionary wiring.
            initialAccuracyMode = uiState.subtitleAccuracyMode,
            onSelectAccuracyMode = { mode -> onSelectAccuracyMode(mode) },
            // 2026-09-15 — words-per-caption (Premiere-style) wiring.
            initialWordsPerCaption = uiState.subtitleWordsPerCaption,
            onSelectWordsPerCaption = { count -> onSelectWordsPerCaption(count) },
            savedDictionaryText = uiState.customDictionaryText,
            onSaveDictionary = { text -> onSaveCustomDictionary(text) },
            onGenerateSubtitles = onGenerateSubtitles,
            onAutoCaption = onAutoCaption,
            // PHASE B3 — new handlers the card needs.
            onCancel = onCancelSubtitleGeneration,
            onUpdateTextOverlay = { id, newText ->
                onUpdateTextOverlay(id, newText)
            },
            onDeleteTextOverlay = { id -> onDeleteTextOverlay(id) },
            onDeleteAllAiSubtitles = onDeleteAllAiSubtitles,
            onClearSelection = onClearTextSelection,
            onExportSrt = onExportSrt,
            // PHASE B4 — SRT import. Same trigger pattern as
            // export: the host wires this through to
            // viewModel.onImportSrtClicked().
            onImportSrt = onImportSrt,
            srtImportState = uiState.srtImportState,
            onConsumeSrtImportState = onConsumeSrtImportState,
            // PHASE B5 — production subtitle styling handlers.
            onApplySubtitleStyle = { id, style ->
                onApplySubtitleStyle(id, style)
            },
            onApplySubtitleStyleToAll = { style ->
                onApplySubtitleStyleToAll(style)
            },
            hasSubtitles = uiState.textOverlays.any {
                it.subtitleSource != null
            }
        )
        ToolPanel.AUDIO -> AudioPanel(
            selectedClip = selectedClip,
            onVolumeChanged = { v -> selectedClip?.id?.let { onVolumeChanged(it, v) } },
            onFadeInChanged = { ms -> selectedClip?.id?.let { onClipFadeInChanged(it, ms) } },
            onFadeOutChanged = { ms -> selectedClip?.id?.let { onClipFadeOutChanged(it, ms) } },
            onPickMusic = onPickMusic,
            // 2026-10-13 — the audio panel's «استخراج صدا از ویدیو» row.
            // The loading state rides on uiState (lightweight + non-blocking:
            // only the row itself shows a spinner).
            onExtractAudio = onExtractAudioFromVideo,
            isExtractingAudio = uiState.isExtractingAudio,

        )
        ToolPanel.FILTERS -> FilterPanel(
            selectedEffect = selectedClip?.videoEffect ?: uiState.selectedEffect,
            onEffectSelected = onEffectSelected
        )
        // 2026-09-28 IA — ADJUST and EFFECTS were two byte-identical branches
        // (the planned ColorGradingPanel was never written, so ADJUST was an
        // interim copy of EFFECTS: 23 duplicated arguments that had to be kept
        // in sync by hand). They are one branch now.
        //
        // The "Select a clip to…" fallback is still reachable in one edge
        // case — the user deletes the selected clip while this panel is open —
        // so it stays, but as a single shared empty state.
        ToolPanel.ADJUST, ToolPanel.EFFECTS -> {
            val clip = selectedClip
            if (clip != null) {
                EffectsPanel(
                    brightness = clip.brightness,
                    contrast = clip.contrast,
                    saturation = clip.saturation,
                    playbackSpeed = clip.speed,
                    rotation = clip.rotation,
                    selectedEffect = clip.videoEffect,
                    onEffectSelected = onEffectSelected,
                    onBrightnessChanged = onBrightnessChanged,
                    onContrastChanged = onContrastChanged,
                    onSaturationChanged = onSaturationChanged,
                    onPlaybackSpeedChanged = { onSetClipSpeed(clip.id, it) },
                    onRotationChanged = onRotationChanged,
                    onRotate90Degrees = onRotate90,
                    // PHASE A (Persistence Integrity) — the trim trim
                    // trim sliders live in `TrimPanel`, not here, so
                    // the old wiring passed the *global* trim
                    // callbacks into a panel that never invokes them:
                    // a dead control that looked like it worked. The
                    // pill now opens the real Trim panel instead of
                    // silently doing nothing.
                    onOpenTrimPanel = { onPanelSelected(ToolPanel.TRIM) },
                    onResetEffects = onResetEffects
                )
            } else {
                Text(
                    text = "Select a clip to adjust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LabelSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        ToolPanel.TRANSITIONS -> TransitionPanel(
            selectedClip = selectedClip,
            onApply = onSetTransition
        )
        ToolPanel.CROP -> CropPicker(
            selectedClip = selectedClip,
            onCropSelected = { preset ->
                selectedClip?.id?.let { onClipCropChanged(it, preset) }
            }
        )
        ToolPanel.BACKGROUND -> BackgroundPanel(
            current = uiState.canvasBackground,
            onBackgroundChanged = onCanvasBackgroundChanged
        )
        // 2026-10-13 — «نوار پیشرفت» (video progress bar / story timer).
        // The value is read from `uiState` (the same `uiState` the preview
        // renders from), so the panel and the canvas can never show
        // different settings. Only the write path needs the pass-through.
        ToolPanel.PROGRESS_BAR -> ProgressBarPanel(
            settings = uiState.progressBarSettings,
            onSettingsChanged = { settings ->
                onProgressBarSettingsChanged(settings, false)
            },
            onSettingsCommitted = { settings ->
                onProgressBarSettingsChanged(settings, true)
            }
        )
        // 2026-10-13 — «تغییر صدا» (voice changer).
        //
        // The target is resolved in the SAME order the toolbar's context is
        // (text > audio > clip): a standalone audio object wins over a
        // video clip, because that is the object the user tapped. Both are
        // `VideoClip` rows in `project.clips` (an audio-only clip is one
        // with `isAudioOnly = true`), so one lookup covers both.
        //
        // The current value is read from that clip — never from a local
        // copy — so the highlighted card is always the effect the preview
        // and the export are actually using.
        ToolPanel.VOICE_CHANGER -> {
            val voiceTarget = selectedClip
                ?: uiState.project?.clips
                    ?.firstOrNull { it.id == uiState.selectedAudioClipId }
            VoiceChangerPanel(
                selected = voiceTarget?.voiceEffect
                    ?: com.aicreatorstudio.app.model.VoiceEffect.NONE,
                hasTarget = voiceTarget != null,
                onSelect = { effect ->
                    voiceTarget?.id?.let { id -> onVoiceEffectChanged(id, effect) }
                }
            )
        }
        ToolPanel.VOICEOVER -> {
            // 2026-10-21 — «نریشن» (voiceover). The panel owns the
            // `RECORD_AUDIO` request, so the ViewModel only ever sees
            // "the user pressed record with the grant in hand".
            //
            // The call is a one-liner on purpose: the panel's body is
            // built in its own composable (`VoiceoverPanelSection`)
            // because this `when` lives inside a lambda that is already
            // at the JVM's 64 KB method-size limit.
            VoiceoverPanelSection(
                state = uiState.voiceoverState,
                actions = LocalVoiceoverActions.current
            )
        }
        ToolPanel.ASPECT_RATIO -> AspectRatioPanel(
            currentPreset = uiState.canvasPreset,
            onPresetSelected = onCanvasPresetChanged
        )
        ToolPanel.TRANSFORM -> TransformPanel(
            selectedClip = selectedClip,
            onFillModeChanged = { mode ->
                selectedClip?.id?.let { onClipFillModeChanged(it, mode) }
            },
            onTransformChanged = { scale, offsetX, offsetY, rotation, flipH, flipV ->
                selectedClip?.id?.let {
                    onClipTransformChanged.update(
                        it, scale, offsetX, offsetY, rotation, flipH, flipV
                    )
                }
            },
            onRotate90Left = {
                selectedClip?.id?.let {
                    val t = selectedClip.transform
                    onClipTransformChanged.update(
                        it,
                        t.scale, t.offsetX, t.offsetY,
                        (t.rotation + 360 - 90) % 360,
                        t.flipH, t.flipV
                    )
                }
            },
            onRotate90Right = {
                selectedClip?.id?.let {
                    val t = selectedClip.transform
                    onClipTransformChanged.update(
                        it,
                        t.scale, t.offsetX, t.offsetY,
                        (t.rotation + 90) % 360,
                        t.flipH, t.flipV
                    )
                }
            },
            onReset = {
                selectedClip?.id?.let { onResetClipTransform(it) }
            }
        )
        ToolPanel.OPACITY -> OpacityPanel(
            selectedClip = selectedClip,
            onOpacityChanged = { o -> selectedClip?.id?.let { onClipOpacityChanged(it, o) } }
        )
        // 2026-10-13 — «ماسک» (PiP shape masks).
        //
        // The target is resolved through `OverlayPipGeometry.resolveTarget`,
        // the SAME helper the preview's selection chrome uses — so the PiP
        // wearing the chrome and the PiP being reshaped are never two
        // different objects. The panel holds no copy of the settings: it
        // renders `maskTarget.maskSettings` and writes back through the
        // live/commit pair, so the panel, the preview clip and the exported
        // frame all read one value.
        ToolPanel.OVERLAY_MASK -> {
            val maskTarget = com.aicreatorstudio.app.model.OverlayPipGeometry.resolveTarget(
                overlays = uiState.overlays,
                selectedOverlayId = uiState.selectedOverlayId,
                editorMs = currentPositionMs,
                clipId = selectedClip?.id
            )
            OverlayMaskPanel(
                settings = maskTarget?.maskSettings
                    ?: com.aicreatorstudio.app.model.OverlayMaskSettings.Default,
                targetLabel = maskTarget?.label?.takeIf { it.isNotBlank() } ?: "لایه",
                hasTarget = maskTarget != null,
                // Live drag: state only, no undo entry, no Room write.
                onSettingsChanged = { settings ->
                    maskTarget?.id?.let { onOverlayMaskChanged(it, settings, false) }
                },
                // Discrete edit / slider release: one undo entry + persist.
                onSettingsCommitted = { settings ->
                    maskTarget?.id?.let { onOverlayMaskChanged(it, settings, true) }
                }
            )
        }
        // 2026-10-13 — «پرده سبز» (chroma key).
        //
        // The sibling of the mask branch above and it resolves its target
        // through the SAME `OverlayPipGeometry.resolveTarget` call — the
        // helper the preview's selection chrome uses. That is not an
        // accident: mask and key compose at render time (the key runs
        // before the mask clip), so if the two panels resolved different
        // targets, a user who set both would get a preview that keyed one
        // overlay and masked another. One call, one answer.
        ToolPanel.CHROMA_KEY -> {
            val keyTarget = com.aicreatorstudio.app.model.OverlayPipGeometry.resolveTarget(
                overlays = uiState.overlays,
                selectedOverlayId = uiState.selectedOverlayId,
                editorMs = currentPositionMs,
                clipId = selectedClip?.id
            )
            ChromaKeyPanel(
                settings = keyTarget?.chromaKeySettings
                    ?: com.aicreatorstudio.app.model.ChromaKeySettings.Default,
                targetLabel = keyTarget?.label?.takeIf { it.isNotBlank() } ?: "لایه",
                hasTarget = keyTarget != null,
                // Live drag: state only, no undo entry, no Room write.
                onSettingsChanged = { settings ->
                    keyTarget?.id?.let { onOverlayChromaChanged(it, settings, false) }
                },
                // Discrete edit / slider release: one undo entry + persist.
                onSettingsCommitted = { settings ->
                    keyTarget?.id?.let { onOverlayChromaChanged(it, settings, true) }
                }
            )
        }
        ToolPanel.OVERLAY -> OverlayPicker(onPick = { _ -> onPickOverlay() })
        ToolPanel.ANIMATIONS -> AnimationPicker(
            selectedClip = selectedClip,
            onAnimationSelected = { anim ->
                selectedClip?.id?.let { onClipAnimationChanged(it, anim) }
            }
        )
        ToolPanel.UPSCALER -> {
            // Effective settings: per-clip if a clip is selected, else global.
            val effective = if (selectedClip != null &&
                selectedClip.upscale != com.aicreatorstudio.app.model.UpscalerSettings.Identity
            ) {
                selectedClip.upscale
            } else {
                uiState.upscale
            }
            UpscalerPanel(
                settings = effective,
                onPresetSelected = onUpscalerPresetChanged,
                onStrengthChanged = onUpscalerStrengthChanged,
                onReset = onResetUpscaler,
                onExportClicked = onUpscaleExportClicked,
                isProcessing = uiState.upscaleExportState !is com.aicreatorstudio.app.export.UpscalerExportState.Idle,
                modelStatus = null,
                upscaleModelState = upscaleModelState,
                upscaleModelReady = upscaleModelReady,
                upscaleModelStatusText = upscaleModelStatusText,
                onDownloadModelClicked = onEnsureUpscaleModelReady,
                upscaleProgress = upscaleProgress,
                onGenerateClicked = onUpscaleGenerateClicked,
                onCancelUpscaleRun = onUpscaleCancelClicked,
                onDismissUpscaleResult = onDismissUpscaleResult,
                effectiveProfileLabel = effectiveUpscaleProfileLabel
            )
        }
        // ---- 2026-09-01: Three new AI tools ----
        //
        // 2026-10-26 — the two composables below (and Motion Tracking's
        // sibling branches) each own a fairly large argument surface;
        // `EditorMattingPanelSection` specifically became necessary
        // when its arguments pushed the enclosing `when`-lambda past
        // the JVM's 64 KB method limit (release compiler: exactly the
        // VoiceoverPanelSection failure of 2026-10-21). The branches
        // live in their own top-level composables for the same reason.
        ToolPanel.AI_MATTING -> {
            val mattingState = uiState.aiTools["matting"]
                ?: com.aicreatorstudio.app.ai.AiToolState(toolId = "matting")
            EditorMattingPanelSection(
                selectedClip = selectedClip,
                mattingState = mattingState,
                mattingModelAvailable = mattingModelAvailable,
                onRunAiTool = onRunAiTool,
                onCancelAiTool = onCancelAiTool,
                onResetAiTool = onResetAiTool,
                onEnsureMattingModelReady = onEnsureMattingModelReady,
                onPickBackgroundMedia = onPickBackgroundMedia,
                onMattingBackgroundModeChanged = onMattingBackgroundModeChanged
            )
        }
        ToolPanel.AI_AUTOFRAME -> {
            val clip = selectedClip
            val autoState = uiState.aiTools["autoframe"]
                ?: com.aicreatorstudio.app.ai.AiToolState(toolId = "autoframe")
            AutoFramePanel(
                selectedClip = clip,
                settings = clip?.autoFrame ?: com.aicreatorstudio.app.model.AutoFrameSettings.Identity,
                keyframes = clip?.cropKeyframes ?: emptyList(),
                aiState = autoState,
                onRun = { opts -> onRunAiTool("autoframe", opts) },
                onCancel = { onCancelAiTool("autoframe") },
                onReset = { onResetAiTool("autoframe") },
                modelStatusText = autoframeModelStatusText,
                modelAvailable = autoframeModelAvailable,
                onEnsureModelReady = onEnsureAutoframeModelReady
            )
        }
        ToolPanel.AI_MOTION_TRACK -> {
            val clip = selectedClip
            val trackState = uiState.aiTools["motiontrack"]
                ?: com.aicreatorstudio.app.ai.AiToolState(toolId = "motiontrack")
            MotionTrackPanel(
                selectedClip = clip,
                settings = clip?.motionTrack
                    ?: com.aicreatorstudio.app.model.MotionTrackSettings.Identity,
                keyframes = clip?.cropKeyframes ?: emptyList(),
                overlays = uiState.textOverlays,
                aiState = trackState,
                pendingRoi = pendingTrackingRoi,
                backendStatusText = motionTrackBackendText,
                onRoiChanged = onTrackingRoiChanged,
                onEnterSelection = onEnterTrackingSelection,
                onExitSelection = onExitTrackingSelection,
                onRun = { opts -> onRunAiTool("motiontrack", opts) },
                onCancel = { onCancelAiTool("motiontrack") },
                onReset = { onResetAiTool("motiontrack") }
            )
        }
        ToolPanel.AI_VOICE_CLEAN -> {
            val clip = selectedClip
            val voiceState = uiState.aiTools["voiceclean"]
                ?: com.aicreatorstudio.app.ai.AiToolState(toolId = "voiceclean")
            VoiceCleanPanel(
                selectedClip = clip,
                settings = clip?.voiceClean ?: com.aicreatorstudio.app.model.VoiceCleanSettings.Identity,
                aiState = voiceState,
                onRun = { opts -> onRunAiTool("voiceclean", opts) },
                onCancel = { onCancelAiTool("voiceclean") },
                onReset = { onResetAiTool("voiceclean") }
            )
        }
        ToolPanel.AI_VOICE_ENHANCE -> {
            VoiceEnhancePanel(
                state = uiState.voiceEnhanceState,
                selectedClip = selectedClip,
                committedEnabled = selectedClip?.voiceClean?.enabled == true,
                onEnhance = onPreviewVoiceEnhancement,
                onCancel = onCancelVoiceEnhance,
                onApply = onApplyVoiceEnhancement,
                onDiscard = onDiscardVoiceEnhancement,
                onClearCommitted = onClearVoiceEnhancement
            )
        }
        ToolPanel.AI_OCR -> {
            val clip = selectedClip
            val ocrState = uiState.aiTools["ocr"]
                ?: com.aicreatorstudio.app.ai.AiToolState(toolId = "ocr")
            // 2026-10-20 — `remember` the decoded result. The decode
            // allocated a NEW instance on every recomposition, so the
            // panel's `LaunchedEffect(committedResult)` restarted on
            // every frame of playback (~30 Hz while the playhead
            // moves). The key is now stable.
            val committed = remember(clip?.ocrResult) {
                clip?.let { c ->
                    com.aicreatorstudio.app.ai.ocr.OcrClipResultJson.decode(c.ocrResult)
                }
            }
            OcrPanel(
                selectedClip = clip,
                aiState = ocrState,
                ocrResult = uiState.ocrResult,
                committedResult = committed,
                onRun = { opts -> onRunAiTool("ocr", opts) },
                onCancel = { onCancelAiTool("ocr") },
                onReset = { onResetAiTool("ocr") },
                onApply = { text, ts, lines, conf -> onApplyOcr(text, ts, lines, conf) },
                modelStatusText = ocrModelStatusText,
                modelAvailable = ocrModelAvailable,
                onEnsureModelReady = onEnsureOcrModelReady
            )
        }
        ToolPanel.AI_REFRAME -> {
            ReframePanel(
                state = uiState.reframeState,
                selectedClip = selectedClip,
                committedEnabled = selectedClip?.autoFrame?.enabled == true,
                committedKeyframes = selectedClip?.cropKeyframes?.size ?: 0,
                onAnalyze = onAnalyzeReframe,
                onCancel = onCancelReframe,
                onApply = onApplyReframe,
                onDiscard = onDiscardReframe,
                onClearCommitted = onClearReframe
            )
        }
        ToolPanel.AI_SILENCE_CUT -> {
            AutoSilenceCutPanel(
                state = uiState.autoSilenceCutState,
                onRun = { threshold, pad, minSilence ->
                    onAutoCutSilence(threshold, pad, minSilence)
                },
                onCancel = onCancelAutoCutSilence,
                onDismiss = onClearAutoCutSilenceResult,
                // PHASE 1 — analyze → preview → apply (non-destructive).
                onAnalyze = { threshold, pre, post, minSilence ->
                    onAnalyzeSilence(threshold, pre, post, minSilence)
                },
                onApplyPreview = onApplySilencePreview,
                onDiscardPreview = onDiscardSilencePreview,
                onToggleSegment = onToggleSilenceSegment,
                onSelectAllSegments = onSelectAllSilence,
                onAnalyzeJumpCut = onAnalyzeJumpCut
            )
        }
        ToolPanel.AI_BEAT_SYNC -> {
            BeatSyncPanel(
                state = uiState.beatSyncState,
                videoClip = uiState.selectedClip,
                audioClip = uiState.project?.audioClips
                    ?.firstOrNull { it.id == uiState.selectedAudioClipId },
                audioAnalyzed = (uiState.project?.audioClips
                    ?.firstOrNull { it.id == uiState.selectedAudioClipId }
                    ?.beatData?.hasBeats) == true,
                onAnalyze = onAnalyzeBeatSync,
                onEnsureBeats = onEnsureBeatSyncBeats,
                onCancel = onCancelBeatSync,
                onApply = onApplyBeatSync,
                onDiscard = onDiscardBeatSync,
                onToggleCandidate = onToggleBeatCandidate,
                onSelectAll = onSelectAllBeats
            )
        }
        ToolPanel.AI_HIGHLIGHTS -> {
            HighlightPanel(
                state = uiState.highlightState,
                selectedClip = selectedClip,
                committedCount = selectedClip?.highlightRanges?.size ?: 0,
                onAnalyze = onAnalyzeHighlights,
                onCancel = onCancelHighlightAnalysis,
                onApply = onApplyHighlights,
                onDiscard = onDiscardHighlights,
                onClearCommitted = onClearHighlights,
                onToggle = onToggleHighlight,
                onSelectAll = onSelectAllHighlights,
                onSeek = onSeekHighlight
            )
        }
        ToolPanel.BEATS -> {
            BeatPanel(
                audioClip = uiState.project?.audioClips
                    ?.firstOrNull { it.id == uiState.selectedAudioClipId },
                toolState = uiState.aiTools[BEAT_TOOL_ID],
                selectedVideoClip = uiState.project?.clips
                    ?.firstOrNull { it.id == uiState.selectedClipId },
                onDetect = { sensitivity -> onDetectBeats(sensitivity) },
                onCancel = onCancelBeats,
                onClear = onClearBeats,
                onAutoSplit = onAutoSplitOnBeats,
                onSelectAudioClip = onAudioClipSelected
            )
        }
        ToolPanel.SUBTITLE_STUDIO -> {
            com.aicreatorstudio.app.ui.screens.editor.subtitle.SubtitleStudioPanel(
                durationMs = projectDuration,
                playheadMs = currentPositionMs,
                hasGeneratedSubtitles = uiState.textOverlays.isNotEmpty(),
                initialTemplate = studioTemplateId?.let { id ->
                    // 2026-09-19 PHASE 4 — JSON catalog first (with
                    // legacy migration), then the hand-built catalog.
                    com.aicreatorstudio.app.model.template.JsonTemplateMapper.pickTemplate(
                        uiState.jsonCatalogTemplates, id
                    )
                        ?: com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.byId(id)
                        ?: com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.migrateLegacyId(id)
                },
                onApplyToAll = onApplySubtitleTemplate,
                onPickTemplate = onPickStudioTemplate,
                onAddSubtitle = onAddTemplateSubtitle,
                onOpenAutoSubtitles = onOpenAutoSubtitles,
                catalogTemplates = uiState.jsonCatalogTemplates,
                catalogStatus = uiState.jsonCatalogStatus,
                // 2026-09-20 — inline subtitle text editor
                selectedTextOverlay = uiState.textOverlays
                    .firstOrNull { it.id == uiState.selectedTextId },
                onUpdateTextOverlay = onUpdateTextOverlay,
                onDeleteTextOverlay = onDeleteTextOverlay,
                onClearTextSelection = onClearTextSelection,
                // 2026-10-13 — CapCut-style subtitle editing workflow.
                onSplitSubtitle = onSplitSubtitle,
                onMergeSubtitleWithNext = onMergeSubtitleWithNext,
                onRetimeSubtitle = onRetimeSubtitle,
                onRetimeSubtitleWord = onRetimeSubtitleWord,
                onResyncSubtitleText = onResyncSubtitleText,
                onBeginGlobalSubtitleResize = onBeginGlobalSubtitleResize,
                onGlobalSubtitleSizeLive = onGlobalSubtitleSizeLive,
                onEndGlobalSubtitleResize = onEndGlobalSubtitleResize,
                onSetGlobalSubtitleSize = onSetGlobalSubtitleSize,
                onScaleAllSubtitles = onScaleAllSubtitles
            )
        }
        null -> Unit
    }
}

// ====================================================================
// Media Missing banner (project restoration safety net)
// ====================================================================

/**
 * Shown when a reopened project has clips whose media could not be
 * resolved (revoked photo-picker grants, deleted files). Offers a one
 * tap relink: the user picks a replacement video, the ViewModel copies
 * it into app-owned storage and rewrites the affected clip URIs.
 */
@Composable
private fun MediaMissingBanner(
    missingNames: List<String>,
    onRelinkClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 2026-10-16 (Phase 2) — four raw amber literals replaced by the
            // warning status tokens, so a missing-media warning is the same
            // amber as every other warning in the app.
            .background(StatusWarningContainer)
            .border(1.dp, StatusWarning.copy(alpha = 0.35f), RoundedCornerShape(0.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = StatusWarning,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "رسانه پروژه یافت نشد" +
                (if (missingNames.isNotEmpty()) ": ${missingNames.first()}" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = LabelPrimary,
            modifier = Modifier.weight(1f)
        )
        Box(
            // 2026-10-16 — "Relink" was the last English action label in the
            // workspace chrome, and the control was ~30dp tall. Now Persian
            // at a 40dp minimum.
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(StatusWarning.copy(alpha = 0.18f))
                .border(1.dp, StatusWarning.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button) { onRelinkClick() }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "انتخاب مجدد فایل",
                style = MaterialTheme.typography.labelMedium,
                color = StatusWarning,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ====================================================================
// Error banner (transient, dismissable)
// ====================================================================

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 2026-10-16 (Phase 2) — was a flat neutral slab
            // (`0xFF1A1A20`), so an error looked like ordinary chrome and
            // carried no status signal at all. Now it uses the same
            // icon + tinted-container pattern as the missing-media and
            // network banners, in the error hue.
            .background(StatusErrorContainer)
            .border(1.dp, StatusError.copy(alpha = 0.35f), RoundedCornerShape(0.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = StatusError,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = LabelPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            // 2026-10-16 — 28dp → 40dp, and the accessible name is now
            // Persian ("بستن") instead of "Dismiss".
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(HairlineSubtle)
                .border(1.dp, Hairline, RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "بستن",
                tint = LabelPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ====================================================================
// AI model network notice (a precondition — deliberately NOT dismissable)
// ====================================================================

/**
 * 2026-09-24 — shown directly ABOVE the bottom tool dock while the
 * selected Whisper model needs a network route the user must enable
 * ([com.aicreatorstudio.app.ai.WhisperModelSpec.networkNoticeFa]).
 *
 * Cloud transcription runs on Groq's API, which an Iranian IP cannot
 * reach, so Generate fails until the user turns on their
 * sanctions-bypass / VPN.
 *
 * It deliberately has NO dismiss button, unlike [ErrorBanner]: this is
 * a standing precondition of the selected model, not a one-off error,
 * and hiding it would hide the reason Generate fails. It is driven by
 * the SELECTED model, so it appears the moment the model row is tapped
 * and disappears when an on-device model is chosen.
 */
@Composable
private fun AiModelNetworkBanner(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(StatusInfoContainer)
            .border(1.dp, StatusInfo.copy(alpha = 0.35f), RoundedCornerShape(0.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = StatusInfo,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = LabelPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

// ====================================================================
// Helpers
// ====================================================================

private fun ToolPanel.title(): String = when (this) {
    // 2026-10-16 (Phase 2) — the sheet header used to alternate between
    // English ("Trim", "Filters", "AI Upscale") and Persian («ردیابی حرکت»,
    // «بهبود صدا») for the same class of label, in the SAME panel: the
    // header. That mixed-language header was the most visible inconsistency
    // in the workspace chrome. Only this mapping is translated — the ~200
    // strings *inside* the panels are deliberately untouched in this phase.
    // Also normalises the stray 8-space indent some branches had.
    ToolPanel.TRIM -> "برش"
    ToolPanel.SPEED -> "سرعت پخش"
    ToolPanel.VOLUME -> "میزان صدا"
    ToolPanel.TEXT -> "افزودن متن"
    ToolPanel.PARAGRAPH -> "متن پاراگراف"
    ToolPanel.SUBTITLES -> "زیرنویس با هوش مصنوعی"
    ToolPanel.AUDIO -> "صدا و موسیقی"
    ToolPanel.FILTERS -> "فیلترها"
    ToolPanel.ADJUST -> "اصلاح رنگ"
    ToolPanel.EFFECTS -> "جلوه‌ها"
    ToolPanel.CROP -> "برش تصویر"
    ToolPanel.BACKGROUND -> "پس‌زمینه"
    ToolPanel.OPACITY -> "شفافیت"
    ToolPanel.OVERLAY -> "افزودن لایه"
    ToolPanel.ANIMATIONS -> "انیمیشن"
    ToolPanel.TRANSITIONS -> "انتقال"
    ToolPanel.ASPECT_RATIO -> "نسبت تصویر"
    ToolPanel.TRANSFORM -> "جابه‌جایی و برش"
    ToolPanel.UPSCALER -> "بزرگ‌نمایی هوشمند"
    ToolPanel.AI_MATTING -> "حذف پس‌زمینه"
    ToolPanel.AI_AUTOFRAME -> "قاب‌بندی خودکار"
    ToolPanel.AI_MOTION_TRACK -> "ردیابی حرکت"
    ToolPanel.AI_VOICE_CLEAN -> "پاک‌سازی صدا"
    ToolPanel.AI_VOICE_ENHANCE -> "بهبود صدا"
    ToolPanel.AI_OCR -> "تشخیص متن تصویر"
    ToolPanel.AI_SILENCE_CUT -> "برش خودکار سکوت"
    ToolPanel.AI_REFRAME -> "ری‌فریم هوشمند"
    ToolPanel.AI_BEAT_SYNC -> "سینک ضرب"
    ToolPanel.AI_HIGHLIGHTS -> "هایلایت هوشمند"
    ToolPanel.BEATS -> "تشخیص ضرباهنگ"
    ToolPanel.SUBTITLE_STUDIO -> "زیرنویس فارسی"
    ToolPanel.PROGRESS_BAR -> "نوار پیشرفت"
    // 2026-10-13 — the full name; the toolbar chip is the shortened
    // «وویس» (see `ContextualToolbarSpec.VoiceChangerChip`).
    ToolPanel.VOICE_CHANGER -> "تغییر صدا"
    // 2026-10-13 — the toolbar chip is «ماسک», which is already the
    // whole word, so the title only needs the possessive to say what
    // it masks.
    ToolPanel.OVERLAY_MASK -> "ماسک لایه"
    // 2026-10-13 — «پرده سبز». The toolbar chip is the same words, and
    // unlike «ماسک» there is nothing to add: "پرده" alone would name the
    // panel but not the action, and the user asked for this name.
    ToolPanel.CHROMA_KEY -> "پرده سبز"
    // 2026-10-21 — «نریشن». The toolbar chip uses the same word, so the
    // title only says what the panel does.
    ToolPanel.VOICEOVER -> "ضبط نریشن"
}

/**
 * Builds a short label for the on-canvas filter / adjust chip.
 * Shows the famous-effect name when one is set, otherwise shows the
 * B/C/S adjustments that differ from identity (1.0).
 */
private fun buildFilterChipLabel(
    effect: VideoEffect,
    brightness: Float,
    contrast: Float,
    saturation: Float
): String {
    // 2026-10-16 (Phase 2) — Persian, like every other on-canvas label.
    // The B/C/S abbreviations were Latin letters inside an otherwise
    // Persian chip, which read as a rendering glitch over the video.
    if (effect != VideoEffect.NONE) return "فیلتر: ${effect.displayName}"
    val parts = mutableListOf<String>()
    if (brightness != 1f) parts += "نور ${"%.2f".format(brightness)}"
    if (contrast != 1f) parts += "کنتراست ${"%.2f".format(contrast)}"
    if (saturation != 1f) parts += "اشباع ${"%.2f".format(saturation)}"
    return if (parts.isEmpty()) "اصلاح‌شده" else parts.joinToString(" · ")
}


// ====================================================================
// 2026-09-19 — caption rendering lives in ONE place now:
// [com.aicreatorstudio.app.export.CaptionRenderer] draws preview
// AND export bitmaps (see rememberCaptionImageBitmap in
// CaptionBitmap.kt). The old per-word Compose renderers
// (ViralMotionCaption / renderLegacyCaption /
// buildKaraokeAnnotatedString) were removed so no second
// implementation can drift out of sync again.
// ====================================================================
