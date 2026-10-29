# Project map — AIerix Cut

Repo root: `C:\Users\eric steph\Desktop\projects\edit\New folder (2)\AICreatorStudio`
Kotlin root: `app/src/main/java/com/aicreatorstudio/app/` (~240 Kotlin files)
Not a git repo. Single Gradle module `:app`; no build flavors.

## Stack (verified from `gradle/libs.versions.toml` and `app/build.gradle.kts`)

| Component | Version / value |
|---|---|
| Gradle | 8.13 (wrapper dist `gradle-8.13-bin`) |
| AGP | 8.13.2 |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Hilt | 2.52 |
| Compose BOM | 2024.12.01 |
| Media3 | **1.5.0** |
| Room | 2.6.1 (DB version **7**) |
| Navigation | 2.8.4 |
| coroutines | 1.9.0 |
| junit | 4.13.2 |
| compileSdk / targetSdk / minSdk | 35 / 35 / **24** |
| Java / Kotlin target | 17 |
| NDK | 30.0.16138531 |
| `abiFilters` | **`arm64-v8a` only** |

`gradle.properties` pins `org.gradle.java.home=C:/Users/eric steph/.jdks/jbr-21.0.11` (the host
JDK 25 is rejected). `local.properties` holds only `sdk.dir=C:\android`.

## Architecture

100% Jetpack Compose + Material 3 (the only XML layout is `R.layout.player_view_texture`).
MVVM + Hilt. UI state is a single `StateFlow<EditorUiState>`.

## Package layout

| Package | Files | What it holds |
|---|---|---|
| `ui/screens/editor/` | 39 | The editor screen, timeline, panels, preview bridge |
| `ai/` | 35 | Whisper, transcription, model install, upscaling, OCR, matting |
| `model/` | 31 | Project/clip/overlay models, templates, pure helper logic |
| `export/` | 19 | Media3 Transformer pipeline, effects, caption rendering |
| `viewmodel/` | 11 | `EditorViewModel` + delegates (history, timeline, subtitle, export, transition) |
| `ui/components/` | 10 | Shared UI |
| `ai/upscale/` | 8 | Upscaler engines |
| `ui/theme/` | 7 | Theme, colors, type, shapes (iOS-derived design system) |
| `ai/subtitle/` | 6 | Post-processing, normalization, cloud path |
| `db/` | 6 | Room database, entities, DAO, JSON converters |
| `model/template/` | 5 | JSON template model, mapper, cache |
| `ai/engines/` | 4 | Voice-clean / RNNoise engines |
| `ai/groq/` | 4 | Groq cloud transcription client + keystore-backed key store |
| `ai/ocr/` | 4 | OCR + text normalization |
| `settings/`, `di/`, `video/` | 4 each | Settings repo, Hilt modules, `ProjectRepository` |
| `ui/navigation/` | 2 | `NavGraph`, `AppDestinations` |
| root | 3 | `MainActivity`, `AICreatorStudioApp`, `CrashReporter` |

## Navigation

`MainActivity` → `ui/navigation/NavGraph.kt#AppNavGraph`. Routes in
`ui/navigation/AppDestinations.kt`: `splash`, `home`, `new_project`, `import_video`, `settings`,
`project_detail/{projectId}`. `PROJECT_DETAIL` renders `EditorScreen` inside
`CompositionLocalProvider(LocalLayoutDirection = Ltr)`. There is **no separate model-manager
screen** — transcription and model selection live inside the editor.

## The editor screen

`ui/screens/editor/EditorScreen.kt` — **4731 lines**. `EditorScreen` at :195, body at :280. Major
sections:

| Symbol | Line |
|---|---|
| `EditorTopBarSection` | :1549 |
| `EditorVideoPreviewSection` | :1688 |
| `EditorPlaybackStripSection` | :3069 |
| `EditorTimelineSection` | :3180 |
| `EditorBottomDockSection` | :3255 (the canonical bottom toolbar) |
| `SubPanelContent` | :3918 |

The preview surface is `androidx.media3.ui.PlayerView` inflated from `R.layout.player_view_texture`
(a **TextureView**) inside an `AndroidView`; the holder `foregroundPlayerView` is a plain
`remember { arrayOfNulls<PlayerView>(1) }`, not Compose state.

`ui/screens/editor/EditorTimeline.kt` — **2403 lines**: `EditorTimeline` :127, `ClipBlock` :1158,
`SubtitleTrackRow` :1523, `AudioLaneRow` :2270, `CenteredPlayheadOverlay`.

**Two bottom toolbars exist.** The canonical one is `EditorScreen.EditorBottomDockSection` :3255 with
`DockCategory` rows (`EditorBottomDock.kt:66 EditorDockItems`). `BottomToolbar.kt:89
BottomToolbarItems` and `EditorBottomToolbar` are **legacy/dead** — do not build on them.

Tool panels are enumerated in `model/EditorUiState.kt:430` (`ToolPanel`), including
`SUBTITLE_STUDIO` :465. Dead/orphaned panel: `ToolPanel.CROP`.

### Layout order (verified 2026-09-24)

`EditorScreenBody` is ONE top-to-bottom `Column`:

`EditorTopBarSection → EditorVideoPreviewSection (weight(1f), capped) →
EditorPlaybackStripSection → EditorTimelineSection → SubPanelRow (only when toolPanel != null) →
SubtitleEditBottomSheet → Spacer(14.dp) → EditorBottomDockSection`

The sub-panel is a **Column sibling**, not a `Box` overlay. The repo's own
`ui/screens/editor/LAYOUT_NOTES.md` still describes the older "sibling of the Column inside the outer
Box, `align(BottomCenter)`, `padding(bottom = 80.dp)`" arrangement and is **stale** — trust the code.

### Banner convention — how to put a notice above the toolbar

Two precedents, both `private` in `EditorScreen.kt`: `MediaMissingBanner` (amber, carries a Relink
action pill) and `ErrorBanner(message, onDismiss)` (dark, dismissable). Both are a `Row` with
`fillMaxWidth()` + `background(...)` + `border(1.dp, …, RoundedCornerShape(0.dp))` +
`padding(horizontal = 14.dp, vertical = 8.dp)`, an optional 16.dp `Icon`, and a `Text` with
`Modifier.weight(1f)`.

To add one, mount it in `EditorScreenBody` **immediately before `EditorBottomDockSection`** (after the
14.dp `Spacer`) so it reads as "above the toolbar". Precedent: `AiModelNetworkBanner` (2026-09-24),
driven by `uiState.selectedWhisperModel.networkNoticeFa` — i.e. off the model-selection state, so it
appears on tap with **no new ViewModel state and no timer**.

There is **no `SnackbarHost` anywhere in this app** — do not introduce one for a single notice.

## State management

- `viewmodel/EditorViewModel.kt` — **4659 lines**, `@HiltViewModel`, `uiState: StateFlow<EditorUiState>`.
  **13 constructor dependencies**: `ProjectRepository`, `SubtitleGenerator`, `Lazy<WhisperEngine>`,
  `VideoExportEngine`, `UpscalerExportEngine`, `MediaStorageManager`, `AiToolRegistry`,
  `UpscaleEngineRegistry`, `UnifiedModelInstaller`, `AiModelInstaller`, `SettingsRepository`,
  `SubtitleTemplateRepository`, `JsonTemplateCatalog`, `SavedStateHandle`, `@ApplicationContext Context`.
  This cannot be constructed in a JVM test — see `build-and-test.md`.
- Delegates: `HistoryDelegate`, `TimelineDelegate`, `SubtitleDelegate`, `ExportDelegate`,
  `TransitionDelegate`, `AdjustmentDelegate` (the last is inert).
- **Undo/redo**: `viewmodel/HistoryDelegate.kt` — two `ArrayDeque<EditorUiState>`, `historyLimit = 20`,
  `push()/undo()/redo()`, with **67 `push()` call sites** in `EditorViewModel`.
- Restore is **not** wholesale: `model/EditorStateHistory.kt:37#restoreDocumentFrom(snapshot, current)`
  merges only "document" fields and preserves live/volatile fields (transport, export/AI progress,
  one-shot triggers). Persisted via `persistAfterHistoryChange()`.
- Tests: `HistoryDelegateTest`, `model/EditorStateHistoryTest`.

## Data model & persistence

- `model/VideoProject.kt` — `VideoProject(id, name, createdAtEpochMillis, clips, canvasPreset,
  canvasBackground, textOverlays, mediaOverlays, audioClips, lastPositionMs)`.
- `model/VideoClip.kt` — `VideoClip(id, uri, fileName, durationMs, width, height, fileSizeBytes,
  importedAtEpochMillis, volume, speed, trimStartMs, trimEndMs, timelineStartMs, brightness,
  contrast, saturation, rotation, fillMode, transform, fadeInMs, fadeOutMs, opacity, videoEffect,
  colorGrading, upscale, matting, autoFrame, cropKeyframes, voiceClean, ocrResult)`.
- Also `model/AudioClip.kt`, `TextOverlay.kt`, `SubtitleStyle.kt`, `SubtitleTemplate.kt`,
  `CanvasPreset.kt`, `ColorGrading.kt`, `Transitions.kt`, `EditorUiState.kt`.
- **Persistence is Room**: `db/ProjectDatabase.kt` (version 7, entities `VideoProjectEntity` +
  `VideoClipEntity`, migrations 4→5→6→7), `ProjectEntity.kt`, `ProjectDao.kt`,
  `RoomProjectRepository.kt`, `Converters.kt`, `EditorStateJson.kt`. Complex fields (text overlays,
  media overlays, AI tools, audio clips) are JSON blobs written by the hand-rolled
  `EditorStateJson`. Interface: `video/ProjectRepository.kt`; `InMemoryProjectRepository` exists for
  tests.

## Docs in the repo

- `README.md` — **stale** (claims AI/timeline are unbuilt). Trust the code.
- `DESIGN_SYSTEM.md` — iOS dark palette (`#0D0D0E/#1C1C1E/#2C2C2E/#3A3A3C`; accents `iOSBlue
  #0A84FF`, `iOSRed #FF453A`, subtitle purple `#BF5AF2`), SF-Pro typography, squircle shapes
  10–28dp, components `GlassSurface`, `iOSSlider`, `iOSPillButton`, `iOSIconControl`, `iOSToolbarItem`.
- `docs/MANUAL_TEST_CHECKLIST.md` — manual runtime checklist.
- `AUDIT_ROADMAP_GAP_ANALYSIS_2026-09-16.md` — feature matrix; blockers #1 (single-clip export) and
  #2 (speed) fixed, #3 (transitions cosmetic) still open.
- `preview/` — a standalone HTML preview, unrelated to the app.
