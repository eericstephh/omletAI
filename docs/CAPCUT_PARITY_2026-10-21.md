# CapCut Parity — Gap Closure & Rebuilds

**Project:** Omelet / AI Creator Studio (`com.aicreatorstudio.app`)
**Date:** 2026-10-21
**Scope:** close the CapCut-capability gaps recorded in
`AIerix-Cut-Lead-Engineer-Agent/skills/aierix-cut-project/references/open-gaps.md`,
and rebuild the capabilities that were already present but broken or half-wired.

---

## 0. What this is and is not

This is an **independent implementation** work log. No CapCut code, assets,
fonts, model weights or DRM was extracted or reproduced — the project's own rule
set forbids it, and the work below was done from the capability list, the
existing code, and the project's own research
(`docs/research/capcut-smart-subtitles-analysis.md`).

**REA (Reverse Engineering Agent) was used where it legitimately applies**: on
*our own* shipped artefact (`inspect-artifact` on the release APK, which
produced real Evidence with a root SHA-256) and on *our own* device (the ADB
provider read the device's crash buffer, which is where the decisive evidence
for the native abort came from). REA cannot decompile a commercial app for us —
no CapCut artefact was available, and copying one was out of scope.

The REA MCP server is now registered for opencode at
`C:\Users\eric steph\.config\opencode\opencode.jsonc` with `REA_ADB_PATH` in the
server's `env` block, so the 24 ADB tools work in-session.

---

## 1. The gaps that were real, and what closed them

### 1.1 `VideoClip.rotation` had no writer — CLOSED

**The bug.** The Adjust/Effects panel *displayed* the selected clip's rotation:

```kotlin
// EditorScreen.kt
EffectsPanel(
    rotation = clip.rotation,          // the clip's own rotation
    onRotationChanged = onRotationChanged,   // …but this wrote the CANVAS rotation
    onRotate90Degrees = onRotate90,
)
```

and the handlers wrote `EditorUiState.rotation` — a **global** mirror that only
the preview read:

```kotlin
fun onRotationChanged(rotation: Int) {
    _uiState.update { it.copy(rotation = rotation % 360) }   // canvas, not clip
}
```

The export, meanwhile, reads the clip's own field:

```kotlin
// RenderGraphBuilder.toRenderSegment
val combinedRotation = normaliseRotation(rotation + transform.rotation)
```

`VideoClip.rotation` was therefore permanently `0`: the panel showed `0°`, the
rotate button moved a number the export never saw, and **the exported video came
out unrotated however much the user rotated it**.

**The fix.**

1. `EditorViewModel.onRotationChanged` / `rotate90Degrees` now write the
   **selected clip's** rotation — exactly like brightness / contrast /
   saturation already did — and keep the global mirror in sync.
2. The `+90°` step accumulates on the **clip's own** value, so repeated taps go
   90 → 180 → 270 → 0 instead of restarting from a stale mirror.
3. The preview now reads the field the export reads:
   `rotation = activeClip?.rotation ?: 0` instead of the global mirror, so the
   screen and the file cannot disagree.
4. `resetEffects` now resets the clip's brightness / contrast / saturation /
   rotation too. It previously reset only the global mirror, so the panel's
   "reset" left the visible values untouched.
5. One shared snapping rule, `snapRotationToQuarterTurn` (in
   `model/ClipTransform.kt`), is used by the editor *and* by the export
   (`RenderGraphBuilder.normaliseRotation` now delegates to it). The export
   resolves the rotation to a quarter turn because the rotated bounding box is
   only exact for multiples of 90; a stored `45°` would have rendered as `45°` on
   screen and `0°` in the file.

`AdjustmentDelegate` — the inert delegate the gap list flags — was given the same
semantics so it is not a trap if it is ever wired up.

**Verification.** `ClipRotationWriterTest` (new): the two pure rules are tested
behaviourally (snapping down, folding negatives, accumulating on the clip), and
the wiring is pinned by source shape, which is the pattern this project's other
ViewModel tests use (`EditorViewModel` is a Hilt singleton and cannot be built
in a JVM test).

---

### 1.2 The normal export ignored the upscaler — CLOSED

**The bug, in three parts.**

1. The Upscaler panel's contrast-adaptive sharpening (CAS) pass existed **only
   in the preview**. The export had no field to receive the settings at all, so
   the exported file carried none of the enhancement the user could see.
2. `VideoClip.upscale` was written by the panel and read by the preview, but it
   was persisted **nowhere** — neither a Room column nor the AI-tools blob — so
   reopening the project silently dropped the user's choice.
3. The upscaler's **global default** lives on `EditorUiState.upscale`, which the
   export path cannot see. A project whose user had picked "Smart HD" globally
   (with no clip selected) exported unenhanced while the preview showed the
   sharpened image.

**The fix.**

- `RenderSegment` gained an `upscale` field; `RenderGraphBuilder` fills it from
  the clip.
- The complete colour chain is now built by **one** function,
  `ColorGradingMath.fullPreviewExportMatrix` — B/C/S → famous effect → Lumetri
  grade → AI-upscaler CAS — and **both** the preview and the export call it.
  The CAS matrix is the export's own `SharpeningMath.casColorMatrix`, composed
  in the same order the preview used, so the two cannot drift apart. As a side
  effect the preview's grading now uses the same pure 4×5 concat the export
  uses, instead of `android.graphics`' own `postConcat`.
- The settings ride the AI-tools blob (`ClipLookSettings.upscale`), so they
  survive a reopen — the same route `speedCurve` and the rest of the look
  settings take, and no Room migration.
- The export resolves the global default the same way the preview does
  ("per-clip if it is set, otherwise the global default").

**The 2× *pixel* upscale is deliberately not part of this.** It needs a real
resampling pass and lives in the dedicated upscale engines
(`com.aicreatorstudio.app.ai.upscale`); this pass is the per-pixel enhancement
the preview already showed.

**Verification.** `UpscaleExportParityTest` (new): the CAS ordering is tested
against `concatColorMatrix`, the identity and non-finite cases are covered, the
segment carries the settings, and the persistence and wiring are pinned.

---

### 1.3 The blurred canvas background was not graded in the preview — CLOSED

The export grades the whole composited frame. The preview graded only the
foreground `TextureView`, so a graded project showed a warm, graded foreground
over a raw, cold background.

The blurred background's `TextureView` now receives the same colour paint — the
`previewColourPaint` was hoisted above the canvas box so both layers can use it.
`TextureView.setLayerPaint` no-ops on an identical `Paint` instance, so this
costs one field comparison per recomposition, not a repaint.

---

### 1.4 Vignette and grain were export-only — PARTIALLY CLOSED

The Color Grading panel wrote `vignetteAmount` / `vignetteFeather` /
`vignetteRoundness` and `grain`, the DB persisted them, and only the export's
`VignetteOverlay` / `GrainEffect` ever read them back. The sliders moved and
the screen did not change.

**Vignette — real parity.** The preview builds its plate with the export's own
`VignettePlate.pixels`, at the same 1/4 resolution, and places it on the video's
rect — computed by the export's own `CanvasLetterbox.videoRect` — with the same
deliberate over-cover. The falloff, the alpha and the edge behaviour are
therefore the export's, not a lookalike.

**Grain — an approximation, and it is documented as one.** The export's grain is
a fragment shader: per-pixel white noise scaled by a mid-tone parabola, so a
crushed black stays crushed. A preview cannot run a shader, so the preview tiles
a small noise field and cycles through a few tiles so the grain moves instead of
sitting still like dirt on the lens.

Two details make it read as grain rather than as a grey veil:

- each speckle is **white or black with an alpha** that follows the noise's
  magnitude, so the overlay both lightens and darkens;
- the draw uses **plain alpha and no blend mode** — the preview's video is a
  `TextureView` inside an `AndroidView`, so its pixels are not in the Compose
  canvas and a Skia blend mode would have nothing to blend against (it would
  composite as a flat, opaque sheet).

What the approximation does **not** carry is the shader's mid-tone response, so
the preview's grain is slightly stronger in the blacks and whites than the
file's.

**Sharpness — still export-only, on purpose.** It is a separable convolution
(`SharpnessKernel` / `SharpnessEffect`). Rendering it in the preview needs
either a shader or a per-pixel CPU pass, and the only Compose route is a filter
on the video layer — the exact shape that broke the Play button twice (see the
history in `EditorScreen`, "Layer 2.5"). It is documented rather than faked.

**Verification.** `DetailPreviewParityTest` (new): the plate is the export's
plate, the plate resolution is a quarter of the frame, the centre stays clear
while the corners darken, the grain tile is black-and-white speckles with
varying alpha, is deterministic per seed, and the video-rect helper is the
export's.

---

## 2. What REA says we actually ship (and what that implies)

The gap list above is a written document; the *shipped* artefact is the
ground truth. REA's `inspect-artifact` was run on the release APK
(`sha256 8734e260…`) and `project_android_application_graph` projected the
inventory into an Android application graph. The findings that matter for
CapCut parity:

**What ships (894 archive entries, 2 DEX files, 14 native libraries)**

| Area | What is in the APK |
|---|---|
| AI models (bundled) | `ESRGAN.tflite` (4.76 MB), `blaze_face_short_range.tflite`, `pose_landmarker_lite.task` (5.51 MB), `selfie_multiclass_256x256.tflite` (15.61 MB), `silero_vad.onnx` (2.22 MB) + `silero-v6.2.0-ggml.bin` |
| AI models (downloaded on demand) | `whisper-small` (487 MB), the Tesseract `fas` / `ara` / `eng` traineddata — `models.lock.json` documents the "bundled-first, downloadable as a repair" policy |
| Native | `libonnxruntime` (27.3 MB), `libmediapipe_tasks_jni` (10 MB), `libtesseract`, `libwhisper`, `libtensorflowlite{,_gpu}_jni`, `librnnoise`, `libleptonica`, `libjpeg`, `libpngx` |
| Shaders | Media3's grain, HSL, LUT, separable-convolution and transformation shaders |
| Fonts | Vazir regular/bold + the `res/` Persian font catalogue (Sahel, Shabnam, Samim, Estedad, Parastoo, Gandom, Nahid, Tanha, Lalezar) |
| Other | `subtitle_templates.json` (24 templates), the sample clip, `player_view_texture` |

`libpl_droidsonroids_gif.so` also ships — **from the Tapsell ad SDK**, not
from us (`gradle dependencyInsight` shows the whole chain). It is not a
half-built feature of ours and must not be reported as one.

**The manifest** (decoded with `aapt2 dump permissions`)

Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID`,
`READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE` (≤32), `WAKE_LOCK`,
`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`. Components: exactly one
activity of ours — `MainActivity`.

Two things that fall straight out of that list:

1. **No `RECORD_AUDIO`** and no `MediaRecorder` / `AudioRecord` anywhere in
   the source → **no voiceover**. CapCut's narration feature was missing
   outright, even though every other piece of it (the audio lane, the mixer,
   the voice changer, volume and fades) was already there. → **implemented,
   see §3.**
2. **No `POST_NOTIFICATIONS`, no worker, no foreground service of ours** →
   the export runs in `viewModelScope` and dies with the process, and no
   progress can be shown on API 33+. CapCut exports in the background with a
   notification. → **still open; it is the next one to build.**

Also checked and *not* gaps: audio import uses the SAF picker
(`GetContent("audio/*")`), so it needs no `READ_MEDIA_AUDIO`; the
`bundledOnly` flag in `ai-models/manifest.json` is dead by design
(`models.lock.json` says so), so the eight entries reading `false` are not a
bug.

**The ranked list of what to copy next** is in
`AIerix-Cut-Lead-Engineer-Agent/skills/aierix-cut-project/references/open-gaps.md`
— background export first, then the progress notification, then stickers/GIF,
freeze frame, AI object removal, camera capture and LUT import.

---

## 3. Voiceover (ضبط نریشن) — CapCut's narration feature, built

**What was missing.** The project had the audio lane, audio clips with volume
and fades, the audio mixer, the offline voice changer, and the waveform — but
no way to capture anything, and no `RECORD_AUDIO` grant in the manifest. The
`ToolPanel` enum had 33 entries and none of them was a recorder.

**What was built.**

| Piece | Where | Note |
|---|---|---|
| The permission | `AndroidManifest.xml` | `android.permission.RECORD_AUDIO`, requested at runtime **only** when the user presses record. |
| The recorder | `audio/VoiceoverRecorder.kt` | `MediaRecorder` → AAC in an MPEG-4 container, written into `filesDir/voiceovers/`. |
| The state | `model/VoiceoverState.kt` | `Idle` / `Recording(level, elapsed)` / `Captured(uri, duration)` / `Failed`. |
| The actions | `EditorViewModel` | `startVoiceover` / `stopVoiceover` / `placeVoiceover` / `cancelVoiceover` / `consumeVoiceoverError`. |
| The panel | `ui/screens/editor/VoiceoverPanel.kt` | A record button, a live level meter, an elapsed timer, and the confirm/cancel row for the captured take. |
| The entry point | `ContextualToolbarSpec.VoiceoverChip` | «نریشن» in the **audio** toolbar — one context only, because a voiceover is always a *new* object rather than an edit of a selected one. |

**Decisions worth recording.**

- **`MediaRecorder`, not `AudioRecord`.** `AudioRecord` gives raw PCM, which
  would mean writing a WAV container by hand and then encoding AAC before
  the export could mix it. `MediaRecorder` produces a normal media file, so
  the take goes through the **same** path as an imported music file — the
  mixer, the waveform, the volume slider and the export need no special
  case.
- **App-owned storage.** The take is written into `filesDir`, so it survives
  the app being killed and needs no storage permission — the same rule the
  project already follows for imported media.
- **Confirm before placing.** A mis-fired recording must not leave a stray
  clip on the timeline, so `stopVoiceover` only *holds* the take; the user
  confirms it with «افزودن به تایم‌لاین», and only then does it become an
  audio-only clip at the playhead with `audioCategory = "voice"`.
- **The meter draws a peak, not a waveform.** The recorder reports a peak
  amplitude; drawing a fake waveform from it would be a lie, so the panel
  draws a level bar.
- **The recorder is released with the ViewModel.** A live `MediaRecorder`
  holds the microphone, so `onCleared` cancels it.

**One hard constraint the feature ran into.** The editor screen's
`AnimatedVisibility` content lambda is a single JVM method that is already at
the **64 KB method-size limit**: adding one more argument to the sub-panel
call made the Kotlin compiler fail with

```
MethodTooLargeException: Method too large:
com/aicreatorstudio/app/ui/screens/editor/EditorScreenKt$EditorScreenBody$22$29.invoke
```

— i.e. the whole screen stopped compiling. The actions therefore travel
through a **composition local** (`LocalVoiceoverActions`) provided around the
screen's body, whose content is a single call, so the oversized lambda is not
touched at all. `VoiceoverWiringTest` pins that decision, because the next
person to add a parameter there would otherwise hit the same wall.

---

## 4. Background export — the export survives leaving the app (2026-10-22)

**The gap.** The export ran in the editor's `viewModelScope`. That scope is
cancelled when the Activity goes away, so **leaving the app mid-render killed
the export**, and swiping the app away killed it too. The evidence was in the
shipped artefact: the manifest declared exactly one activity
(`MainActivity`) and no worker of ours — WorkManager's services ship as a
transitive dependency, but the project never used it. CapCut keeps exporting
in the background, with a notification.

**The fix.** The export now runs in a foreground service.

| Piece | Where | Note |
|---|---|---|
| The service | `export/ExportService.kt` | `@AndroidEntryPoint`, `foregroundServiceType="mediaProcessing"`, a progress notification with a cancel action on a low-importance channel. |
| The request | `export/ExportRequest.kt` | Carries the project's id plus the one value that is not on the project (the upscaler's global default) across the process boundary. |
| The single rule | `ExportRequestResolver` | Pure. The editor and the service both call it, so the file the service renders is the file the editor would have rendered — the old inline assembly in `startExportLocked` became the only copy. |
| The shared stream | `VideoExportEngine.state` | The editor no longer starts the export, so it *observes* the engine's shared `StateFlow` instead of the per-call flow. |
| `prepare()` | `VideoExportEngine` | A `StateFlow` replays its current value to a new collector, so the previous run's terminal state ("done") has to be cleared before a new run is announced — the same class of bug the 2026-09-10 fix addressed inside the engine. |
| Permissions | `AndroidManifest.xml` | `POST_NOTIFICATIONS` (the notification is only *shown* with the grant; the export runs either way) and `FOREGROUND_SERVICE_MEDIA_PROCESSING`. |

**Why a foreground service and not WorkManager.** It is the smallest thing
that gives exactly CapCut's behaviour with **no new dependency**: the process
is not killed while the service is in the foreground, so the render survives.
WorkManager would additionally survive a *process* death, but the export's
engine is an in-memory Media3 pipeline, so a restarted process would have to
re-render from zero anyway — and `androidx.hilt:hilt-work` is not available to
this project offline.

**Honest limits.** The render still lives in memory: if the process is killed
outright (the user force-stops the app from Settings, or the system kills a
foreground service under extreme memory pressure), the export is lost and is
**not** resumed. A resumable export would need the render checkpointed, which
is a much larger change. This is written down in `open-gaps.md` rather than
papered over.

---

## 5. The gaps that were already closed (the list was stale)

The gap list was written at a point in time and had not been updated. Each of
these was re-verified against the source:

| Gap | Where it is actually solved |
|---|---|
| Transitions never render in the preview | `EditorScreen` "PHASE A" — the preview composes `transitionIn` / `transitionOut` through the export's own `ClipMotion.compose`. |
| Trim is not applied to the preview | `EditorScreen` "the preview playlist itself" — every clip's `MediaItem.ClippingConfiguration` window *is* the trim. |
| Preview/export BCS maths disagree | Both call `ColorGradingMath.fullExportMatrix`; the preview's `combinedColorMatrix` delegates to it. |
| `adjustment` delegate is inert | Still open. Its semantics are now the correct ones, so it is a candidate to become the single implementation rather than dead code — but that refactor has not been done. |

---

## 6. The native crash that made the AI features unusable (2026-10-21)

Found with REA's ADB provider reading the device's crash buffer — the tombstone
of the real device (Xiaomi 2107113SG, Android 14):

```
Fatal signal 6 (SIGABRT) in tid 14694 (DefaultDispatch), pid 13927
Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity for nullptr pointer: 1
    in call to NewDirectByteBuffer
    from android.media.ImageReader$SurfaceImage$SurfacePlane[]
           ImageReader$SurfaceImage.nativeCreatePlanes(int, int, long)'
```

with the application frames (from the debug build, whose names are not
obfuscated):

```
com.aicreatorstudio.app.ai.engines.MediaPipeMattingEngine$process$2.invokeSuspend
  com.aicreatorstudio.app.ai.media.SequentialFrameSampler.sample
    com.aicreatorstudio.app.ai.media.VideoFramePipeline.imageToBitmap
      com.aicreatorstudio.app.ai.media.VideoFramePipeline.yuv420ToBitmap
```

**Cause.** AOSP's `MediaMetadataRetriever` reports
`METADATA_KEY_VIDEO_WIDTH/HEIGHT` as the **display** size — it swaps the axes
for a 90/270 rotation before returning. A MediaCodec decoder writes the
**coded** (cropped) size from the track format. The `ImageReader` was sized
from the retriever, so on a portrait clip it expected a frame the decoder never
produced, and `ImageReader.nativeCreatePlanes` produced a plane whose address
was null and whose capacity was 1. ART's JNI check turns that into an
immediate `abort()` — and a native abort **cannot be caught**, so the whole
process died. Background Remove simply made the app vanish.

**Fix, three layers.**

1. Every `ImageReader` is sized from the **track format** (`decoderOutputSize`),
   the only authoritative statement of "what will the decoder write".
2. `probe()` now reports the **coded** size, from the track format. That also
   fixed a latent double-swap: `displayWidth` used to be the *coded* size for a
   portrait clip, so the export's output dimensions — and the matting mask's
   size — were wrong for exactly the clips that crashed.
3. `image.getPlanes()` is now only ever called on an image whose dimensions have
   already been checked against the reader's size. `getWidth()` / `getHeight()`
   are plain accessors and never touch the native plane machinery, so a
   mismatch is rejected with a clear error instead of aborting the process.

**Still unverified on hardware.** `DecoderSizeDiagnosticTest` (androidTest) is
written and ready; the device disconnected before it could run.

---

## 7. Files changed

| File | Change |
|---|---|
| `ai/media/VideoFramePipeline.kt` | Coded size from the track format; `decoderOutputSize`; guarded `imageToBitmap`; reader sized from the track format. |
| `ai/media/SequentialFrameSampler.kt` | Same reader sizing + guarded conversion — the exact crashing path. |
| `export/AiExportHooks.kt`, `export/OverlayCompositeRenderer.kt` | Same reader sizing (the export's two decode sites). |
| `viewmodel/EditorViewModel.kt` | Clip rotation writers; `resetEffects` resets the clip; the upscaler's global default reaches the export; the clip's upscale settings reach the persisted blob. |
| `viewmodel/AdjustmentDelegate.kt` | Rotation writes the clip, with the same snapping rule. |
| `ui/screens/editor/EditorScreen.kt` | The preview reads the clip's rotation; the shared colour chain; the blurred background is graded; the vignette and grain overlays; `previewColourPaint` hoisted. |
| `ui/screens/editor/ColorGradingMath.kt` | `fullPreviewExportMatrix` — the one complete colour chain, plus `casMatrixArray`. |
| `export/VideoExportEngine.kt` | The colour chain comes from the shared function; the upscale is baked. |
| `export/RenderSegment.kt`, `export/RenderGraphBuilder.kt` | The segment carries the upscale settings. |
| `model/ClipTransform.kt` | `snapRotationToQuarterTurn`, `nextQuarterTurnRotation`. |
| `model/AiToolSettings.kt` | `ClipLookSettings.upscale`. |
| `model/GrainSignal.kt` | `noiseTile` — the preview's grain field. |
| `db/EditorStateJson.kt`, `db/RoomProjectRepository.kt` | The upscale settings are persisted and restored. |
| `AIerix-Cut-Lead-Engineer-Agent/.../open-gaps.md` | Rewritten against the code, with the closed rows, the remaining approximations, and the ranked list of what to copy next. |
| `AndroidManifest.xml` | `RECORD_AUDIO` for the voiceover. |
| `model/VoiceoverState.kt` (new) | The recorder's state. |
| `audio/VoiceoverRecorder.kt` (new) | The `MediaRecorder` capture. |
| `ui/screens/editor/VoiceoverPanel.kt` (new) | The panel, the composition local, and the actions holder. |
| `viewmodel/EditorViewModel.kt` | The voiceover actions; the recorder is released in `onCleared`. |
| `model/EditorUiState.kt` | `ToolPanel.VOICEOVER` + the `voiceoverState` field. |
| `model/SubPanelHeight.kt` | The panel's sheet height class. |
| `ui/screens/editor/ContextualToolbarSpec.kt` | The «نریشن» chip in the audio toolbar. |
| `ui/screens/editor/EditorScreen.kt` | The composition-local provider and the panel branch. |

**Tests added:** `ClipRotationWriterTest`, `UpscaleExportParityTest`,
`DetailPreviewParityTest`, `DecoderSizeGuardTest`,
`DecoderSizeDiagnosticTest` (androidTest — needs the device).

---

## 8. Verification

- **3174 unit tests, 0 failures** (was 3115 before this session).
- Release APK built; `apksigner` → **v2 verified**; `zipalign -c -P 16` →
  **successful**; the 16 KB page check → **0 not aligned** (14/14 `.so` at
  `p_align = 16384`).
- **Not verified on hardware.** The device (`c3f38b01`) disconnected early in
  the session and never came back, so:
  - `DecoderSizeDiagnosticTest` has not been run - the crash fix is reasoned
    from the tombstone and guarded in code, but not proven on the phone;
  - the new vignette / grain overlays have not been seen on a screen;
  - the voiceover has not been recorded on a real microphone.

**The honest next step is to plug the phone in** and run:

```
.\gradlew.bat :app:connectedDebugAndroidTest --offline "-Pandroid.testInstrumentationRunnerArguments.class=com.aicreatorstudio.app.ai.media.DecoderSizeDiagnosticTest"
```

then install `AICreatorStudio-v1.4.0-release-2026-10-22-bgexport.apk` and confirm that
Background Remove no longer kills the app.

---

### 8.1 Update - 2026-10-23: the phone came back, and three more bugs were in the way

The device (`c3f38b01`, Xiaomi 2107113SG / Android 14) was reconnected and
Background Remove was driven end to end with `adb input`, a `uiautomator` dump
per step and a full `logcat` capture. Closing the crash took **three** fixes,
each hidden behind the previous one:

1. **R8 renamed Google Flogger** - `Graph.<clinit>` threw
   `IllegalStateException: no caller found on the stack for: j8.c`
   (`j8.c` = `FluentLogger`), so the segmenter was never created.
   Fixed with a keep rule and proven against the release mapping.
2. **`Image.getPlanes()` aborted the process** on an HEVC frame
   (`non-zero capacity for nullptr pointer: 1` in `nativeCreatePlanes`).
   API 29+ now reads frames through `Image.getHardwareBuffer()`; when that is
   unavailable the frame is rejected with a catchable exception and
   `MediaPipeMattingEngine`'s retriever fallback runs. Proven:
   `matting pass: frames=600 ... src=1080x1920`.
3. **A launch race in `EditorViewModel`** - `init` launched `restoreAiState()`
   on `Dispatchers.IO` while the cache it writes was declared after `init`,
   so the editor died with a `ConcurrentHashMap.put` NPE on unlucky starts.
   The fields now precede `init`, guarded by `EditorViewModelInitOrderTest`.

Result on the phone: `حذف پس‌زمینه آماده است` + `تکمیل شد`. The panel's summary
is Persian now, and the sampling step has the ~10 fps floor its comment always
claimed (the first passing run did 600 frames at ~17 fps: 7 minutes for a 36 s
clip).

Full evidence, measurements and the remaining limitations (the fast sequential
sampler is unavailable on this device; ~0.32 s of inference per frame) are in
`docs/REAL_DEVICE_AI_TRIPLE_FIX_2026-10-23.md`.

---

### 8.2 Update - 2026-10-24: the export's frame source was moved off the GPU surface

With matting fixed, the **export** became the next failing feature on the same
phone, and it failed twice for unrelated reasons.

1. **The foreground service could not start on Android 14.** `ExportService`
   called `startForeground` with `mediaProcessing`, which only exists on API 35,
   and without `FOREGROUND_SERVICE`; the platform threw
   `InvalidForegroundServiceTypeException` and no frame was ever touched. The
   type is now chosen at runtime (`dataSync` on API 29-34, `mediaProcessing` on
   35+) and the manifest declares both plus the three permissions.
2. **No `ImageReader` frame on this phone is readable.** With the default
   usage, `Bitmap.wrapHardwareBuffer` refuses the buffer ("usage flags must
   contain USAGE_GPU_SAMPLED_IMAGE") and `Image.getPlanes()` **aborts the
   process** in `nativeCreatePlanes`. With an explicit
   `USAGE_GPU_SAMPLED_IMAGE or USAGE_CPU_READ_OFTEN` (allocated usage `0x103`),
   the hardware buffer is null and the plane path still aborts. The export
   transcode and both export samplers now configure their decoders in
   **byte-buffer mode** (`configure(format, null, null, 0)`,
   `COLOR_FormatYUV420Flexible`, `getOutputImage()`) and convert through the new
   `VideoFramePipeline.codecImageToBitmap`; one more bug surfaced there - a
   byte-buffer image's last chroma row is shorter than `rowStride`, so
   `readPlaneRow` now clamps to the plane's `limit()`.

Measured on the phone after both fixes:

```
decoder video/hevc in byte-buffer mode (coded 1080x1920)
decoder output: video/raw 1080x1920 color=0x7f420888
export +243198ms: SUCCEEDED bytes=3458608 durationMs=35852
```

The file was pulled and parsed: `mvhd` → **35.805 s**, `avc1` → **1920×1080**,
`mp4a` present, byte count identical to the log. The panel showed
«خروجی آماده شد», 3.5 MB, 35.9s.

Honest cost and limits: 243 s for a 35.8 s clip (~226 ms/frame, CPU-bound, not
profiled per stage). Only the overlay and VIDEO-background samplers still lack
a device run for their byte-buffer change, and only one HEVC clip has been
exported. Full evidence:
`docs/REAL_DEVICE_EXPORT_FRAMESOURCE_FIX_2026-10-24.md`.

The same mechanism then went into `SequentialFrameSampler` (matting, Auto Frame,
OCR), and that one **was** verified on the phone: `sequential sampler:
video/hevc byte-buffer decode`, `matting pass: frames=358 decode=0ms
infer=112675ms write=7268ms` — **120 s** for the 36 s clip, down from 253 s,
with «تکمیل شد» in the panel. The final APK then exported that project
end to end: `export +265243ms: SUCCEEDED bytes=3397824`, parsed as 35.805 s /
1920×1080 / AAC (the size differs from the earlier file because the mask was
recomputed, not because the muxer changed). Suite: **3187 tests, 0 failures**.
