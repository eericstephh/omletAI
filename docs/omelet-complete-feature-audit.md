# Omelet Android (AI Creator Studio) — Complete Feature Audit

> **Version:** Regenerated 2026-10-19 (repo root moved from `C:\...` to `G:\...`; the original
> 2026-10 audit report was lost in the move). This regeneration reflects the **current** source,
> including all 2026-10-17/18 AI fixes and the Background-Removal optimization.
> Method: static source reading (3 parallel explorers) + emulator run on Pixel 8 (API 36) for UI-path
> verification + unit/build runs. Runtime performance numbers are **not** captured yet — see §6.

---

## 1. Architecture overview

Single-module app (`:app`), package `com.aicreatorstudio.app`, ~260 Kotlin files.

| Layer | Facts |
|---|---|
| Build | Gradle 8.13-bin, AGP 8.13.2, Kotlin 2.0.21, KSP, Hilt 2.52, JDK pinned JBR 21 (`gradle.properties:13`), compileSdk/targetSdk 35, minSdk 24, `arm64-v8a` only, NDK 30.0.16138531, CMake 3.22.1, Lint disabled, release minify+shrink on, versionCode 4 / 1.4.0, debug suffix `.debug` |
| UI | Jetpack Compose (BOM 2024.12.01), Material 3 + a parallel iOS-styled theme set (`ui/theme/IOS*`), Navigation-Compose 2.8.4. Screens: `SplashScreen`, `HomeScreen`, `ImportVideoScreen`, `SettingsScreen`, `EditorScreen` (6302 lines) + 60+ editor panels |
| ViewModel | 7 ViewModels; `EditorViewModel` (`viewmodel/EditorViewModel.kt:84`) is 7569 lines with non-ViewModel delegates (`AdjustmentDelegate`, `ExportDelegate`, `HistoryDelegate`, `SubtitleDelegate`, `TimelineDelegate`, `TransitionDelegate`) |
| AI | whisper.cpp via JNI (CPU, 1.7.6 vendored), MediaPipe tasks-vision 0.10.35 (matting, pose, face), TFLite 2.17.0 (selfie multiclass, ESRGAN, blazeface), Tesseract4Android 4.9.0 (OCR), ONNX Runtime 1.28.0 (Silero VAD), RNNoise vendored C, OpenCV 4.10.0 feature-gated (`-PwithOpenCv=true`, default OFF), Groq cloud Whisper client |
| Media | Media3 1.5.0 `Transformer` for export (`di/ExportModule.kt:27-34`); direct MediaCodec+MediaMuxer pipeline `ai/media/VideoFramePipeline.kt` (789 lines) for AI pre-render/analysis; ExoPlayer preview (TextureView `PlayerView`, one main + one player per PiP overlay, one per audio stem `AudioMixerPlayer.kt`) |
| Persistence | Room `projects_db` v8: `projects` + `clips` tables with JSON-blob columns (`textOverlaysJson`, `mediaOverlaysJson`, `aiToolsJson`, `audioClipsJson`, `progressBarSettingsJson` — `db/ProjectEntity.kt:27-65`); additive migrations only, no destructive fallback (`di/AppModule.kt:37-43`); settings in file-based `SettingsRepository` (SharedPreferences) — **no DataStore** |
| Background work | **No WorkManager**; `Dispatchers.IO` + owned scopes (`RoomProjectRepository.kt:41`, `VideoExportEngine` SupervisorJob) |
| Logging | `logging/OmeletLog.kt:41` facade, tag `"Omelet/<component>"`, ring store `filesDir/logs/omelet.log` (`OmeletLogStore.kt`), secret scrubbing; parallel raw tags: `VideoExportEngine`, `MediaPipeMattingEngine`, `AiExportHooks` |
| Ads | Tapsell 1.4.0-alpha03; app-open ad on Home once per session; `ExportAdGate` for export/cloud |
| Native build | Conditional on `app/src/main/cpp/whisper/whisper.cpp` + `ggml/ggml.c` existing (`app/build.gradle.kts:17-18`) — Gradle task downloads whisper.cpp v1.7.6 tarball, SHA-verified via `app/ai-models/models.lock.json`; builds `libwhisper.so`, conditional `librnnoise.so`; CPU backend only, 16 KB page-size flags |

Export flow (verified): `ExportDialog` → `ExportDelegate.onExportClicked` (`ExportFrame.resolveOutputSize`) → `VideoExportEngine.startExport(): Flow<ExportState>` → `RenderGraphBuilder` builds `RenderSegment`s → `Media3TransformerExportEngine` builds `EditedMediaItemSequence` + `Composition` → **before Transformer, each segment passes `AiExportHooks.prepareSourceUri`** (`VideoExportEngine.kt:505`) which applies, in load-bearing order: matting composite → reframe crop keyframes → tracked-overlay burn-in → media-overlay burn-in (`AiExportHooks.kt:72-133`) → voice-clean audio remux (`maybeSwapCleanAudio`, single-segment exports only, `VideoExportEngine.kt:646`). Publish via MediaStore (`VideoExportEngine.kt:2064`).

---

## 2. Feature registry (as exposed to users)

Status legend: ✅ implemented (real processing) · 🟡 partially · ⬛ dead/unreachable code · UNV = behavior not runtime-verified.

### A. Project creation & media import
| Feature | Label | Entry | Implementation | Status |
|---|---|---|---|---|
| New project | «پروژه جدید» | `HomeScreen` (`HomeScreen.kt:417,431`) | Routes to import flow (`NavGraph.kt:55`); the `NEW_PROJECT` route itself is a `ComingSoonScreen` («ابزار ساخت پروژه به‌زودی اضافه می‌شود», `NavGraph.kt:75-82`) | 🟡 label implies a creator flow; real creation rides import |
| Import video | «افزودن ویدیو» | Photo Picker `PickMultipleVisualMedia(10)` + `GetContent` fallback (`ImportVideoScreen.kt:102-111`) | `ImportVideoViewModel.onVideosPicked`; `MediaStorageManager.importToAppStorage` copies to `filesDir/media/` (fixes revoked picker URIs); `VideoMetadataExtractor` rotation-checked; 5-min cap; batch skip messages | ✅ |
| Audio pairing on import | — | `ImportVideoViewModel` | `AudioPairingService.pairForVideo` real pairing, persisted in `audioClipsJson` | ✅ |
| Project list / delete | «پروژه‌های من», «حذف پروژه» | `HomeScreen` | Room-backed (`HomeViewModel` → `RoomProjectRepository`), confirm dialog, `OrphanedMediaJanitor` cleanup (unit-tested) | ✅ |
| Media relink | «رسانه پروژه یافت نشد…» / «انتخاب مجدد فایل» | `EditorScreen.MediaMissingBanner` | `MediaStorageManager.isMediaAccessible` | ✅ |

### B. Timeline & clip editing
| Feature | Label | Handler (`EditorViewModel`) | Model op | Status |
|---|---|---|---|---|
| Split | «برش» | `splitClip` 2779, `splitClipMultiple` 2866 | trim math + SpeedCurve sliced at cut | ✅ |
| Trim | «تریم» | `setClipTrim` 2916 | `trimStartMs/trimEndMs` | ✅ |
| Duplicate | «تکثیر» | `duplicateClip` 2985 | new UUID at end | ✅ |
| Delete | «حذف» | `deleteClip` 2894 (refuses last clip) | remove | ✅ |
| Speed | «سرعت» | `setClipSpeed` 3341 (0.25–4×), curve 3378 | `speed`, `speedCurve` | ✅ |
| Volume | «ولوم» | `updateClipVolume` 3013 (0–2), mute 3048 | `volume`, `AudioClip.muted` | ✅ |
| Crop | «کراپ» | `updateClipCrop` 3437 | `cropPreset` | ✅ |
| Transform | «جابجایی» | `setClipTransform` 5285 | `ClipTransform` FIT/FILL/MANUAL | ✅ |
| Opacity | «شفافیت» | `setClipOpacity` 3492 | `opacity` | ✅ |
| Animation | «انیمیشن» | `updateClipAnimation` 3425 | `ClipAnimation` | ✅ |
| Fades | (audio panel) | `setClipFadeInMs/OutMs` 3454/3472 | `fadeInMs/fadeOutMs` | 🟡 **write-only** — persisted, never rendered by any engine (`ContextualToolbarSpec.kt:103-117` documents this) |
| Zoom/playhead/undo | — | `onTimelineZoomChange` 694, `HistoryDelegate` (20-step in-memory, not persisted) | | ✅ |

### C. Text, subtitles, templates
| Feature | Label | Implementation | Status |
|---|---|---|---|
| Text/paragraph overlays | «متن» | `TextOverlayPanel`, `ParagraphTextPanel`, canvas drag chrome `FreePositionTextOverlay`; full style surface in VM 1420–1746 | ✅ |
| AI subtitles (Whisper) | «زیرنویس» | Real whisper.cpp word timestamps (`WhisperEngine.kt:59-71`, `whisper_full_get_word_t`, centisecond→ms), 30 s windows, beam 5 BALANCED default, `fa` default, Persian prompt priming; post-pipeline `ai/subtitle/*` + offline Persian corrector `ai/subtitle/corrector/*` | ✅ |
| Cloud Whisper (Groq) | «اتصال هوش مصنوعی» | `ai/groq/*`, API-key UI, VPN notice «برای استفاده از این مدل باید تحریم شکن روشن کنید» (`WhisperModelSpec.kt:67-68`); non-dismissable network banner | ✅ (network-gated) |
| Templates | Subtitle Studio | **20 built-in** (`fa_01_clean`…`fa_20_signature`) **+ 6 pro** (`model/BuiltInSubtitleTemplates.kt`) + **25 JSON catalog** (`assets/subtitle_templates.json`) — no "17" anywhere | ✅ |
| SRT import/export | — | SAF `CreateDocument`/`OpenDocument`, `SrtParser/SrtFormatter/SrtImportMapper` | ✅ |
| Karaoke word motion | — | `ai/WordMotionEngine.kt`, `words[]` per overlay | ✅ |
| Custom fonts | — | SAF import (`CustomFontStrip.kt:65`), `data/font/*` repository | ✅ |

### D. Audio & sound
| Feature | Label | Implementation | Status |
|---|---|---|---|
| Voice changer | «وویس» | 6 presets (`model/VoiceEffect.kt`), live preview via custom audio sink (EditorScreen 479–492), export `VoiceEffectAudioProcessor` | ✅ |
| Voice clean | «پاک‌سازی صدا» | `RnnoiseVoiceCleanEngine` (RNNOISE 48 kHz, VAD-gated blend, writes `cacheDir/voiceclean/<clipId>.wav`) + fallback `SpectralGateVoiceClean` (STFT, `.pcm` — note: fallback output is **not** read by the export swap) | ✅ (fallback-export gap: §4) |
| Voice enhance | «بهبود صدا» | preview→apply WAV commit flow (VM 6775–6897), stale-timeline guards | ✅ |
| Beat detection/sync | «سینک ضرب» | `BeatDetector.kt` (real PCM analysis), `BeatSyncPlanner`, VM 3092/3209/7603/7788 | ✅ |
| Silence cut | «برش سکوت» | Silero VAD v6 ONNX (`ai/vad/SileroVadDetector.kt`), `SilenceCutter.buildCutClips`, jump-cut ranker | ✅ (cancellation gap: §4) |
| Background music | «موزیک» | audio picker → `filesDir`, music lane, per-stem ExoPlayer (`AudioMixerPlayer`) | ✅ |
| SFX | «صدا» (lane AUDIO_SFX) | `model/SoundEffect.kt` has 10 built-ins; **no call site ever creates an `audioCategory="sfx"` clip** — `SoundEffects` has zero consumers | ⬛ dead code |
| Waveforms | — | real PCM peaks (`ensureWaveformsFor` 2572, `WaveformGenerator` unit-tested) | ✅ |

### E. Filters / color / effects / transitions
| Feature | Implementation | Preview == Export? |
|---|---|---|
| One-tap effects (NOIR/SEPIA/VIVID/FADE/COLD/WARM/INVERT/DREAM/CYBER/VHS) | `EffectPresets.toColorMatrix` | ✅ same ColorMatrix both paths (`GradeRgbMatrix`); parity test `ExportColorParityInstrumentationTest` |
| Color grading | `ColorGradingPanel` + `ColorGradingMath` + scopes (`ColorScope.kt`) | ✅ shared matrix; preview renders via `ColorMatrixColorFilter` on TextureView (ExoPlayer.setVideoEffects was removed because it broke playback — documented EditorScreen 2702–2738) |
| Sharpness / grain / vignette | `export/SharpnessEffect`, `GrainEffect` (GLSL shader asset), `VignetteOverlay` | ❌ export-only (documented) |
| Transitions | `model/Transitions.kt` + `TransitionEngine` + `ClipMotion.compose` shared | ✅ geometry+wash; blur phase intentionally not previewed |
| Chroma key «پرده سبز» | `util/ChromaKeyEngine` shared | ✅ |
| PiP masks «ماسک» | `OverlayMask` + `OverlayPipLayer` preview / `OverlayCompositeRenderer` export | ✅ |
| Letterbox background | `CanvasPreset` + `CanvasBackground` BLACK/BLUR/COLOR, `CanvasBackgroundOverlay` export | ✅ |
| LUT support | — | ⬛ none (comments only) |

### F. AI & computer-vision features — all real, details in §5
`matting` (MediaPipe selfie multiclass) · `autoframe` (SaliencyTrackerEngine: pose+face) · `motiontrack` (NCC/OpenCV ROI tracker) · `ocr` (Tesseract fas/ara/eng) · `voiceclean` (RNNoise) · silence cut / highlights (Silero VAD) · reframe (`ReframePlanner`) · beat sync · transcription (whisper.cpp / Groq) · upscaler (ESRGAN TFLite, full pipeline + export engine — chip badge comment «به‌زودی» is stale, feature is implemented).

### G. Playback / preview / export / settings
- Preview: single ExoPlayer playlist, TextureView, ms-accurate scrub with throttle (`PlayheadScrubThrottle`), frame stepping, restore-seek. ✅
- Export: Media3 Transformer with AI pre-render hooks, stall watchdog (`ExportStallPolicy`, timeouts `BASE_TIMEOUT_MS/FINALIZE_TIMEOUT_MS`), progress stage strings, cancel, MediaStore publish. ✅
- **No export notification** (no POST_NOTIFICATIONS/foreground service) — progress is in-app only.
- Settings: models/downloads, Groq connection, theme/language, hardware accel, autosave, haptics, default quality/font, diagnostics log share. ✅
- No onboarding flow; changelog via bell sheet (`AppUpdateSheet`).

---

## 3. End-to-end pipeline findings

1. **AI pre-render ordering is documented and enforced** in `AiExportHooks.prepareSourceUri`: matting → reframe → tracked overlay ("ORDER IS LOAD-BEARING", `AiExportHooks.kt:91-109`) → media overlays. Cancel checks (`pipelineCancelCheck`, `AiExportHooks.kt:196-199`) wired into all four `VideoFramePipeline.Request`s. FIXED 2026-10-17.
2. **Single-source crop math**: export `AiExportHooks.cropWindowFor` (793–814) delegates to `ReframePlanner.cropWindowFor` (119–144), the same function the Compose preview helper `AutoFramePreview` uses. FIXED 2026-10-17 (was a real preview/export parity risk).
3. **Matting sidecar is now honest-by-construction**: `MediaPipeMattingEngine.process` verifies segmenter + metadata **before** `MattingSidecar.beginWritePass` (wipes previous PNGs), streams one PNG per frame (`writeFramePng`, PNG compress 100, sha256 recorded), finalizes `manifest.json` **before** `Result.success` (engine lines 225–306), and deletes partials on cancel/error (`:329-340`). Export verifies manifest + per-frame sha + model sha (`MattingSidecar.verify`, `AiExportHooks.kt:218-230`) and fails loudly with Persian guidance.
4. **Bounded analysis resolution**: matting PNGs written at max side 720 (`ANALYSIS_MAX_SIDE`, engine:574); export `maskFor` bilinearly rescales each PNG to the composite frame (`AiExportHooks.kt:283-295`) with a single-entry cache and explicit recycle.
5. **Coordinate/timestamp correctness**: sidecar PNGs keyed by **source-local ms** (`MattingSidecar.kt:27-28`); trajectories interpolated through shared `MotionTrackingGeometry`; rotation normalized at probe (`VideoFramePipeline.probe` + `SourceInfo` defaults — FIXED; pinned by `SourceInfoDisplaySizeTest`).
6. **Preview parity**: tracked text overlays driven by `TrackedOverlayPreview.trackedPreviewPlacement` (EditorScreen 3477–3496, 4182) mirroring `TrackedOverlayRenderer.drawStamp`; Auto Frame crop preview via `autoFramePreviewPlacement` (EditorScreen 3039–3057). Pinned by `AIPreviewPlacementTest`.
7. **Matting preview is a readiness chip only** (EditorScreen 2844–2858, 3629–3632): the chip says masks exist; no live composite in preview. `maskFor` has exactly one caller (export). Documented v1 limitation, not a broken link.
8. **Voice-clean export swap is single-segment only** (`VideoExportEngine.kt:270-272`, `AiExportHooks.kt:146-177`) and reads only the RNNoise `.wav`; the `SpectralGateVoiceClean` fallback writes `.pcm` that the swap never reads — the swap then silently keeps original audio. Hypothesis-grade gap (fallback is only used when RNNoise native fails).
9. **No WorkManager anywhere** — long AI runs are plain coroutine scopes; process death mid-Apply loses the run (state stays un-committed; sidecar deleted) but cannot corrupt persistence, since `onAiToolSuccess` is the only commit point.
10. **Duplicate Apply protection**: `runAiTool` cancels the previous job for the same toolId (`EditorViewModel.kt:6051`) and gates the run button via `AiToolUiPolicy.generateEnabled`.

---

## 4. Prioritized findings

### Fixed in this codebase (verified, keep regression checks)
| ID | Was | Fix evidence |
|---|---|---|
| F1 | Matting ran with no project id → fake sidecar routing | `AiToolOptions.mattingProjectId` contract + refusal exception (engine:177-180); ViewModel routing 6077–6095 |
| F2 | Blank/empty mask silently exported | engine writes loud failure; export verify gate |
| F3 | Export pre-renders not cancellable | `pipelineCancelCheck` at 4 sites |
| F4 | Rotation wrong for rotated sources | `VideoFramePipeline.probe` normalization; `SourceInfoDisplaySizeTest` |
| F5 | Crop math duplicated preview vs export | `ReframePlanner.cropWindowFor` single source |
| F6 | Sidecar written post-run from ViewModel (unbounded memory, dishonest success) | engine-loop streaming writes; `finalizeManifest` before success |
| F7 | Tracked overlay / AutoFrame not visible in preview | `TrackedOverlayPreview`, `AutoFramePreview` + EditorScreen wiring |

### Open findings

**P1**
- **P1-1 · No unit coverage for `MattingSidecar` streaming API.** The 2026-10-17 rewrite (`beginWritePass/writeFramePng/finalizeManifest/verify`) has no `MattingSidecarTest`; only contract tests exist (`AiMattingEngineTest` is JVM-contract-only; real inference covered on-device by `AiToolsRealInferenceTest`). A regression in manifest/sha ordering would be caught only at export time on a device. Repro: any matting run; Evidence: glob of `app/src/test/**` shows no sidecar test. Fix: add JVM tests for `finalizeManifest`→`verify` (mini JSON, sha equality, stale model sha), `beginWritePass` wipe semantics, `delete` idempotence. Risk: low. This is the highest-value cheap test in the repo.
- **P1-2 · Tapsell mediation app key committed in build file.** `TapsellMediationAppKey` placeholder contains a real console key (`app/build.gradle.kts:93-94`). Not a user credential, but it is an account-identifying secret in source. Fix: move to `local.properties`/env like the release keystore creds. Risk: none.

**P2**
- **P2-1 · `supportsRtl="false"` while the whole app is Persian-first** (`AndroidManifest.xml:28` vs its own comment claiming support) and `NavGraph` wraps `EditorScreen` in `LayoutDirection.Ltr` (94–96). Persian strings render fine, but mirrored padding/arrangement bugs are structurally possible (the emulator UI audit showed correct-looking RTL rows, so visual damage today appears limited — treat as latent risk). Fix requires a deliberate RTL re-audit of the editor row/canvas; do not flip the flag blindly.
- **P2-2 · Bundled 1.53 GB `whisper-ggml-medium/ggml-medium.bin` in assets is orphaned.** The picker (`WhisperModelSpec.kt`) offers SMALL local + CLOUD_API only; `models.lock.json`'s `models` array no longer contains medium; `manifest.json` omits it. UNVERIFIED whether it reaches the APK (would explode APK size); either way it is dead repo weight and a contradiction between assets and lockfile. Fix: remove from assets + lockfile hygiene, or re-register it intentionally.
- **P2-3 · Voice-clean fallback output never used at export.** `SpectralGateVoiceClean` writes `cacheDir/voiceclean/<clipId>.pcm` (`SpectralGateVoiceClean.kt:150-161`) but `AiExportHooks.maybeSwapCleanAudio` reads only `.wav` (156). If RNNoise native fails and the fallback ran, export silently keeps original audio. Fix: unify output format (write `.wav` in the fallback too), or surface a "cleaned audio unavailable" state.
- **P2-4 · Silero VAD loop is not cooperative-cancel.** `SileroVadDetector.detect` has no `ensureActive()` inside the frame loop (progress callback only). A cancelled silence-cut/highlight run keeps decoding until detection returns. Fix: pass the job through or check a cancel flag per frame (pattern already exists in `MotionTrackingEngine`/`TesseractOcrEngine`).
- **P2-5 · SFX feature is dead code.** Lane + 10 built-in effects + UI tab exist; no producer creates `audioCategory="sfx"` clips (D above). Either implement a producer or remove lane+model+chip (verified no callers).
- **P2-6 · AUDIO_SFX/`SoundEffects` aside — audio fades write-only** (B table). Implement rendering in preview mixer (`AudioMixerPlayer`/Transformer `AudioProcessor` chain) or remove the write path.

**P3**
- P3-1 Duplicate class names: two `SettingsViewModel` (`viewmodel/…:23`, `settings/…:9`) and two `DeviceCapabilityDetector` (`ai/…:244`, `ai/upscale/…:146`). Confusing, zero runtime impact.
- P3-2 Doc drift: `ContextualToolbarSpec.kt:419` "7 engines" vs 11 panels; ONNX version comment says 1.20 but literal is 1.28.0 (`app/build.gradle.kts:301-304`); stale KDoc in `ReframePlanner.cropWindow` calling export "its own implementation".
- P3-3 Dead catalog entry `mlkit-speech-recognition` in `libs.versions.toml:57-58`.
- P3-4 English headers inside Persian panels: `AudioPanel.kt:74` "Audio"/"Sound" (also the letterbox Background panel swatches "Color"/"Fill"/"Blurred video" strings are English).
- P3-5 `EditorViewModel` (7569 lines) / `EditorScreen.kt` (6302) / `EditorStateJson` (1226) — maintainability debt; delegate pattern already exists and should be extended rather than growing the ViewModel further.
- P3-6 `previewFps`, `snapToGrid`, `showFrameCount`, `maxUndoSteps` settings prefs exist but editor consumption is UNVERIFIED/minimal — either wire or remove from the settings model.
- P3-7 Known UX limitations (accepted, documented in `docs/manual-ai-fixes-device-test.md`): COLOR mode has no color picker (fixed `0xFF111111`); no live matting mask in preview; `decodeOnly` analysis flag TODO in `MotionTrackingEngine.kt:103-106` (~2× waste on tracking analysis pass).

### Confirmed vs hypothesis separation
- Confirmed by reading: all items above with file:line evidence.
- Hypothesis: actual device-side Apply time improvement magnitude (needs the `matting pass:` log on a real phone), RTL visual damage scope, whether medium `.bin` is packaged into the APK.

---

## 5. AI model & dependency inventory

| Engine | Model / file | Source | Delegate | Notes |
|---|---|---|---|---|
| Matting | `selfie_multiclass_256x256.tflite` (~16 MB, SHA-verified, minSize 15 MB) | bundled asset + storage.googleapis repair | CPU (no delegate set) | class 0=background; EMA α=0.65; feather 0–10 px; threshold applied at compose time (soft knee) |
| Auto Frame | `pose_landmarker_lite.task` (5.6 MB) + `blaze_face_short_range.tflite` (230 KB) | bundled + repair URL | CPU | 4 fps sampling, 200 ms keyframes, 1-Euro smoothing, 2 poses, per-frame exceptions swallowed→fallback scoring |
| Motion tracking | none (NCC tracker fallback, OpenCV optional) | — | CPU | 360 px work width, 33 ms keyframes, minConfidence 0.35, MAX_MISS_FRAMES 45 |
| OCR | `fas/ara/eng.traineddata` (tessdata_fast) | bundled + github repair | CPU (serialized) | PSM_AUTO, min word conf 40, RTL reversal |
| Voice clean | RNNoise 8-bit weights in `librnnoise.so`; fallback STFT | vendored native | CPU | 48 kHz; VAD-gated blend; `MAX_BOOST_GAIN = 10^(12/20)` |
| Silence cut / highlights | `silero_vad.onnx` 2.3 MB (v6); silero ggml variant also bundled (unused by this detector) | bundled | CPU | frame 512 @16 kHz, threshold 0.5, pad 300 ms, min silence 500 ms |
| Transcription | `ggml-small.bin` 487 MB (download, f16 — q5_1 rejected for Persian BPE); medium 1.53 GB bundled (orphaned, P2-2); cloud = Groq `whisper-large-v3-turbo` | hf.co + Groq | CPU, 4 threads, beam 5 | real word timestamps; Persian initial prompt |
| Upscaler | `ESRGAN.tflite` 4.99 MB | bundled + repair | CPU/GPU profile-gated (`UpscaleProfile`) | tiled (`EsrganTiler`) |
| Offline | Every on-device feature runs fully offline after model install; only Groq cloud transcription needs network/VPN. | | | |

Runtime model install: `UnifiedModelInstaller` — `.partial` + `.sha256`, atomic rename, repair from bundled asset when present.

---

## 6. Test & build status

- `testDebugUnitTest`: **BUILD SUCCESSFUL** (2026-10-18, PowerShell; read the `BUILD` line, exit code 1 is a pipeline artifact).
- `assembleDebug`: **BUILD SUCCESSFUL**; APK at repo root `AICreatorStudio-v1.4.0-debug-aiFixes-2026-10-17.apk`.
- Coverage strengths: subtitle/SRT/RTL suites, voice effect DSP+persistence, export config/geometry/parity, model install/lockfile, beat/VAD, editor JSON round-trips (incl. `AiToolsTrajectoryRoundtripTest`), preview-placement parity tests.
- Gaps: `MattingSidecar` (P1-1), no instrumentation test dedicated to the matting **export path**, no test for `maybeSwapCleanAudio` fallback formats.
- Not runnable yet: `connectedDebugAndroidTest` suite (needs a device; `adb devices` empty during audit — emulator runs were interrupted by infra restarts). Manual checklist: `docs/manual-ai-fixes-device-test.md`.

---

## 7. Suggested repair order

1. `MattingSidecar` JVM tests (cheap, protects the newest risky change) → 2. Move Tapsell key out of VCS → 3. Voice-clean fallback `.wav` unification → 4. Silero VAD cancellation → 5. SFX decision (implement or remove) → 6. Audio fade rendering or removal → 7. Dead-code/doc hygiene batch (P3 items) → 8. `decodeOnly` flag in `VideoFramePipeline` (perf, shared-pipeline change — needs regression tests for matting/reframe) → 9. COLOR color picker + live matting preview chip upgrade → 10. RTL structural audit (flag + editor LTR wrapper) as its own project.

## 8. Sequential implementation plan (one feature at a time)

1. **Sidecar test pack** (no app change): write `MattingSidecarTest` covering write→finalize→verify→sha mismatch→delete; run `testDebugUnitTest`.
2. **Secret hygiene**: relocate `TapsellMediationAppKey` to `local.properties` (pattern already exists for keystore creds); verify ad init fallback message.
3. **Voice clean**: change `SpectralGateVoiceClean` output to `.wav` (or teach the swap to read `.pcm`); add unit test for swap file resolution; verify on-device fallback path if reachable.
4. **VAD cancellation**: thread a cancel check through `SileroVadDetector.detect` + `SilenceSegmentMapper`; cancel test via coroutine scope cancel mid-detect.
5. **SFX**: product decision needed (implement producer vs remove lane/model/labels). Prefer removal to kill dead surface.
6. **Fades**: add fade rendering to Transformer audio chain + preview mixer, or remove write path + settings UI.
7. **Hygiene**: rename duplicate classes, fix KDoc/comment drift, delete dead catalog entries, translate remaining English panel strings.
8. **`decodeOnly`** in `VideoFramePipeline.Request` (skip encoder when no output needed); update MotionTrackingEngine (removes the TODO), keep matting behavior unchanged; re-run all AI unit tests + device checklist.
9. **COLOR picker + preview mask chip** in `AiMattingPanel` (uses existing threshold/strength plumbing).
10. **RTL project**: flip `supportsRtl`, remove Ltr wrapper, audit editor rows/canvas mirrored behavior, screenshot matrix on device.

## 9. Regression checklist for the rest of the app

After any AI-engine change:
- [ ] `testDebugUnitTest` green (includes subtitle/SRT/RTL, voice, export config, editor round-trip suites).
- [ ] Import → edit (split/speed/trim) → color grading → export of a **plain** (AI-free) project: output unregressed (`RenderGraphBuilder`, `GradeRgbMatrix` parity test).
- [ ] Export with PiP overlay + mask + chroma key (order-sensitive compose path).
- [ ] Subtitles: generate → karaoke overlay → SRT export round-trip.
- [ ] Voice changer preview audible + export processed.
- [ ] Matting: sidecar verify gate still rejects tampered manifest (delete a PNG → export must fail with Persian guidance, not export wrong content).
- [ ] Cancel export mid-run: no partial file published to MediaStore.
- [ ] Crash log path (`CrashReporter`) still writes on forced exception.
