# Known open gaps & dead code

Known-broken or known-incomplete things in this project. **Do not build on top of a known gap** —
either fix it first or report it as a constraint. Do not silently "fix" one as a side effect of an
unrelated task either; that hides a regression.

## Open functional gaps

**Status as of 2026-10-21.** This table used to list eight gaps; four of them had
already been closed in the code and the list had not been updated. Every row below
was re-verified against the source, not against an old note.

| Gap | Status | Detail |
|---|---|---|
| Transitions never render in the preview | **CLOSED** | The preview composes `transitionIn` / `transitionOut` through the export's own `ClipMotion.compose` (`EditorScreen`, "PHASE A"). Blur-based transitions still preview geometry + wash only, because `Modifier.blur()` on the video layer is the shape that broke the Play button twice. |
| `VideoClip.rotation` has no writer | **CLOSED** | The Adjust/Effects panel showed `clip.rotation` while its handlers wrote the *global* `uiState.rotation`, so the exported video was unrotated. The handlers now write the selected clip, the preview reads the same field the export reads, and both sides snap to the quarter-turn grid through one shared rule (`snapRotationToQuarterTurn`). |
| Normal export ignores the upscaler | **CLOSED** | The Upscaler panel's CAS pass was preview-only and the per-clip settings were persisted nowhere. `RenderSegment` now carries them, the complete colour chain (B/C/S → effect → grade → CAS) is built by one shared function (`ColorGradingMath.fullPreviewExportMatrix`) that both renderers call, the settings ride the AI-tools blob, and the export resolves the global default exactly as the preview does. |
| Trim is not applied to the preview | **CLOSED** | The preview playlist gives every clip a `MediaItem.ClippingConfiguration` whose window *is* the clip's trim (`EditorScreen`, "the preview playlist itself"). |
| Preview/export BCS maths disagree | **CLOSED** | Both sides call `ColorGradingMath.fullExportMatrix`; the preview's `combinedColorMatrix` delegates to it rather than computing its own. |
| Blurred canvas background not graded in preview | **CLOSED** | The export grades the whole composited frame; the preview now applies the same colour paint to the blurred background's `TextureView` as well. |
| `sharpness` / `vignette` / `grain` are export-only | **PARTIALLY CLOSED** | **Vignette**: the preview builds its plate with the export's own `VignettePlate.pixels` and places it on the video's rect (`CanvasLetterbox.videoRect`) — the falloff and alpha are the export's, not a lookalike. **Grain**: the preview tiles a noise field, which is an *approximation* — the export's grain is a fragment shader with a mid-tone response a preview cannot reproduce, and the preview's video is a `TextureView`, so no blend mode can be used against it. **Sharpness**: still export-only. It is a separable convolution (`SharpnessKernel`); rendering it in the preview needs either a shader or a per-pixel CPU pass, and the only Compose route is a filter on the video layer — the exact shape that broke the Play button twice. |
| `adjustment` delegate is inert | **OPEN** | `AdjustmentDelegate` is constructed (`EditorViewModel.adjustment`) but never called; the ViewModel carries its own copies of every method. Its semantics are now the correct ones (it writes the selected clip, not just the global mirror), so it is a candidate to become the single implementation rather than dead code — but that refactor has not been done. |
| **Voiceover (ضبط نریشن)** | **CLOSED** | 2026-10-21. CapCut's signature narration feature was missing entirely: the project had the audio lane, the mixer, the voice changer and the volume/fade controls, but **no recorder** and no `RECORD_AUDIO` grant. Now: a `MediaRecorder` capture into the app's own storage (AAC in an MPEG-4 container, so the mixer needs no special case), a panel with a record button / live level meter / elapsed timer, a confirm-before-place step, and the take lands on the timeline at the playhead as an audio object of its own (`audioCategory = "voice"`). The permission is requested at the moment of the tap. |

## CapCut capabilities still missing (2026-10-21, ranked)

Verified against the **shipped** artefact with REA (`inspect-artifact` on the release
APK) and the manifest, not against a feature wishlist.

| # | Capability | Evidence that it is missing | Effort |
|---|---|---|---|
| 1 | **Background export** (the export survives the app being closed) | The manifest declared exactly one activity (`MainActivity`) and no worker of ours; WorkManager's services ship as a transitive dependency but the project never used it. The export ran in `viewModelScope`, so it died with the process. | **DONE 2026-10-22** — see below |
| 2 | **Export progress notification** | `POST_NOTIFICATIONS` was absent from the manifest, so no notification could be shown on API 33+. | **DONE 2026-10-22** — the service posts a progress notification with a cancel action |
| 3 | **Sticker / GIF layer** | No sticker panel, no GIF assets; `ToolPanel` has no such entry. (The `libpl_droidsonroids_gif.so` in the APK comes from the Tapsell ad SDK, not from us.) | Medium — and it needs *content*, which cannot be copied from CapCut |
| 4 | **Freeze frame / still** | No freeze-frame concept in the model. CapCut's "Add still" per second. | Medium |
| 5 | **AI object removal (eraser)** | No inpainting / erase feature anywhere. | High (needs a model) |
| 6 | **Camera / selfie capture** | No `CAMERA` permission in the manifest. | Medium |
| 7 | **LUT (`.cube`) import** | Media3's LUT shader ships in the APK, but the project has only fixed effect presets — no LUT path. | Medium |

### Background export — CLOSED 2026-10-22

The export now runs in a **foreground service** (`export/ExportService.kt`), so it
survives the user leaving the app and the app being swiped away. A progress
notification with a cancel action is shown on a low-importance channel.

* `ExportRequest` carries the project's id plus the one value that is not on
  the project (the upscaler's global default) across the process boundary.
* `ExportRequestResolver` is the **single** rule that turns a project into a
  project + config — the editor and the service both call it, so the file the
  service renders is the file the editor would have rendered.
* The engine exposes its shared `StateFlow`, so the editor *observes* the
  export it no longer starts. `prepare()` clears the previous run's terminal
  state first, because a `StateFlow` replays its current value to a new
  collector.
* `android:foregroundServiceType="dataSync|mediaProcessing"` — chosen at runtime by
  SDK level (`dataSync` on API 29-34, where `mediaProcessing` does not exist;
  `mediaProcessing` on API 35+, the correct type for a video render) — plus
  `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and
  `FOREGROUND_SERVICE_MEDIA_PROCESSING`. Without the runtime split, Android 14
  refuses the start with `InvalidForegroundServiceTypeException`.

**Export frame source — fixed 2026-10-24.** The export used to decode into an
`ImageReader`; on the measured phone (Xiaomi 2107113SG, Android 14, HEVC) *no*
reader usage produces a readable frame: `getPlanes()` aborts the process and
`wrapHardwareBuffer` refuses the buffer. The transcode and both export samplers
now use MediaCodec **byte-buffer** output and `VideoFramePipeline.codecImageToBitmap`.
Proven end to end on the phone: `export +243198ms: SUCCEEDED`, 35.805 s /
1920×1080 / AAC, parsed from the file itself. See
`docs/REAL_DEVICE_EXPORT_FRAMESOURCE_FIX_2026-10-24.md`.

**Honest limits.** The render still lives in memory: if the process is killed
outright (the user force-stops the app from Settings, or the system kills a
foreground service under extreme memory pressure), the export is lost and is
**not** resumed. A resumable export would need the render to be checkpointed,
which is a much larger change. `POST_NOTIFICATIONS` is a runtime grant — without
it the export still runs, the notification is just not shown.


## CapCut background-removal parity (2026-10-25, ranked)

Derived from a **static** reverse-engineering pass over CapCut 19.9.0
(`com.lemon.lvoverseas`, versionCode 19900002, SHA-256 `3C74A994…D30F2C38`).
Full evidence, contracts and the "what is still unverified" list live in
`docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-10-25.md`. Nothing from CapCut was copied;
only field names, parameter values and pass ordering were read.

**The headline finding:** CapCut runs its matting model at **360×640** and then
rebuilds the edge on the GPU. We run a **256²** model and keep the mask at output
resolution, but our only post-processing is a **pixel feather**. So most of the
visible quality gap is *post-processing*, not the model — which means the P0
items below need **no new model**.

| # | Gap | CapCut evidence | Our state | Priority |
|---|---|---|---|---|
| 1 | **Mask expansion / contraction** (grow or shrink the subject) | Draft field `matting.expansion` (`long`); `DilateErode` shader: `kernelSize = erode_dilate_kernel_size / 8`, `k >= 0` → 31-tap max (dilate), `k < 0` → 31-tap min + soft erosion `pow(clamp(min - (max-center)*(\|k\|/5), 0, 1), 1+\|k\|/4)` | **DONE 2026-10-26** — `MattingSettings.expansion` (−1..+1, normalized) + `MaskPostProcess.dilate`/`softErode` (exact O(N) deque max/min, the shader's soft-erode formula) at composition time; `گسترش سوژه` slider | **P0 ✅** |
| 2 | **Normalised edge feather with gamma** | `blur_kernel_size / 100` → `inputEdgeSmooth` 0..1; separable 43-tap gaussian, `sigma 4`, `gamma 1.4` before and `1/1.4` after; collapses to 5 taps at 8× when `edgeSmooth < 0.2` | **DONE 2026-10-26** — `MattingSettings.feather` (0..1 normalized; done **without** the gamma-1.4 weighting — its direction was not verified, doc §11) + `MaskPostProcess.gaussianFeather` (3-box O(N)); `نرمی لبه` slider | **P0 ✅** (gamma) |
| 3 | **Reverse selection** (keep background instead of subject) | `matting.reverse` (`boolean`) + `enable_reverse` → `mask = 1 - mask` at the end of the chain | **DONE 2026-10-26** — `MattingSettings.reverse` + `MaskPostProcess.invert` as the chain's last pass; `معکوس` toggle | **P0 ✅** |
| 4 | **One shared post-process used by every consumer** | the mask is published as a single texture (`addUserTexture("#MattingMask")`) and every later stage reads it | **DONE 2026-10-26** — moved from generation-time to **composition-time**: `MaskPostProcess` (pure JVM) → `MaskBitmaps` (Bitmap bridge) → `AiExportHooks.maskFor` applies the chain on the raw sidecar mask at its native resolution, before the single rescale. Sliders persist immediately and take effect on the next export **without a re-run** — the parity property behind the "mask as algorithm layer" architecture. (The *preview* does not composite the mask at all — that is a pre-existing, separately tracked approximation, not a regression of this change.) | **P0 ✅** |
| 5 | **Temporal model for video** (`frashevery 15` + GRU) | `ai_matting_gru` graph (`forwardtype -1`, `model_name tt_matting_video_gru`) next to `ai_matting` (`forwardtype 0`); `matting_mp_frashevery = 15` | **PARTIAL 2026-10-29** — the *statistical* half is done without any new weights: `ai/MattingTemporalFilter.kt` replaces the fixed `TEMPORAL_ALPHA = 0.65` EMA with a median of three (a one-frame measurement is discarded, not dimmed) plus a scene-cut reset at 0.35 mean mask change. What remains is the actual recurrence: a GRU that knows what "the same subject" looks like across occlusions and re-entry. No model, no schedule. | **P1** (the learned half) |
| 6 | **Atomic undo for "apply mask + new background"** | `DraftComboParams("image_editor_matting_apply")` / `("image_editor_matting_cancel")` | history exists, but mask + background are separate steps | **P1** |
| 7 | **Interactive brush / eraser (VOS)** | `custom_matting` graph (`mobile_vos`, `mobile_vos_input_mask_type 1`), `AddCustomMattingReqStruct(brush_type, brush_size, brush_points)`, `LVVEMattingPenType` = fast 1 / general 2 / eraser 4 / fasteraser 8 (bitmask) | **absent** | **P2** (needs a VOS model) |
| 8 | **Non-portrait modes** (object / saliency / head / pet / tag) | 5 more graphs: `ai_matting_video_object` + `_preview`, `saliency_matting`, `tag_matting`, `camera_matting`; `MATTING_TYPE_{SALIENCY,FACE,HEAD,FOLLOW,COMBINE,EXTRA}` | person only (Selfie Multiclass) | **P2** (needs models) |
| 9 | **AI-generated background** | `com.vega.edit.matting.aibackground` (864 methods) | colour / image / video / blur only | **P2** (needs a generative service) |
| 10 | **Face-detect-driven subject choice** | `AddMattingReqStruct.face_detect_interval / _limit / _threshold` from `FaceDetectConfig(intervalMs, detectLimit, threshold)` | **absent** | **P2** |

**Implementation notes for the 2026-10-26 P0 closure** (see
`docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-10-25.md` for the evidence):

* **UI slider ranges are derived, not observed.** CapCut's exact slider
  endpoints were never on screen in the RE samples; `expansion ∈ −1..+1`
  (`±1` → `shortSide/20` px reach) and `feather` 0..1 (`1` →
  `shortSide/60` px sigma) follow the shader's own transforms with
  `normSize = 240` + `kernelSize = ui/8`. Section 11 lists what is still
  unverified.
* **The knobs live in the ViewModel, not the panel signature.** The
  three callbacks (`setMattingExpansion/Feather/Reverse`) travel through
  `LocalMattingPanelActions` (`EditorMattingPanelSection.kt`) rather
  than adding arguments to the `AnimatedVisibility` sub-panel lambda —
  that lambda sits at the JVM's 64 KB method limit and one extra
  argument already failed the whole screen build twice
  (`MethodTooLargeException`, same shape as the voiceover precedent of
  2026-10-21).
* **Legacy projects migrate to `feather = 0`.** Blobs written before
  this change had their feather *baked into the sidecar PNGs*
  (generation-time `featherPx`); loading them with the default 0.15
  would double-feather. `EditorStateJson` reads blobs lacking
  `mattingFeather` exactly as 0. A fresh engine run replaces the
  sidecar with raw masks and re-warms the default.
* **Traversal order**: expansion (dilate or soft-erode) → feather →
  reverse, matching the shader's pass order; nothing else was observed
  between them.
* **All passes are O(N)** — monotonic-deque max/min and a sliding-sum
  box-blur approximation of the Gaussian — because the chain runs
  *inside the export loop*. Its border semantics are clamped
  replication (`proc_border 1`); the border bookkeeping was verified
  against brute-force oracles in `MaskPostProcessTest` after a
  one-sample window shift slipped through the first cut.

**2026-10-27 — "subject stays, background fully removed" is the committed
default of the button.** The user request arrived after a manual device
test where pressing the button visibly changed nothing: the default
background mode was `ORIGINAL` (a mask-only pass-through) and the
`TRANSPARENT` mode — the CapCut behaviour — threw at export. Now:
`TRANSPARENT` is real (the removed area is filled with
`CanvasLetterbox.fadeColorArgb` — the project's canvas fill, the same
colour the letterbox bars get — instead of an alpha container MP4 cannot
carry), a fresh run commits it when the user has not picked a mode
(`MattingSettings.runDefaultBackgroundMode`), and the panel leads the
selector with a «حذف» chip + an honest hint that the live playback
preview does not composite and the result appears in the export (doc
§16). Legacy `ORIGINAL` blobs stay pass-through *until* the user
re-runs the button — then they flip to TRANSPARENT.

**2026-10-29 — the temporal filter and the analysis bound.** Two changes, both
without new weights:

* **`TEMPORAL_ALPHA = 0.65` is gone.** It was one line and could not be
  retuned: slow enough to damp noise meant a one-frame false positive became a
  0.65-alpha ghost that decayed over ~0.5 s, and fast enough to follow motion
  meant a trailing edge. `ai/MattingTemporalFilter.kt` now takes the median of
  `(current, prev, prev2)` — a one-frame measurement is a minority of three and
  is **discarded**, a persistent one is reproduced **exactly** on the second
  frame — and resets the history when the frame changes by ≥ 0.35 (a cut).
  There is deliberately **no EMA on top**: the first cut of the class had one,
  and `MattingTemporalFilterTest` caught it turning the median's one-frame lag
  into a four-frame fade (1.0 → 0.3 → 0.09). Consequence: the emitted mask is
  binary again, so soft edges come from the composition-time feather only.
* **`ANALYSIS_MAX_SIDE` 720 → 512.** The model ingests 256×256, so 720 was
  waste. 512 is the smallest round bound that keeps a 16:9 frame's **short**
  side (288) at or above the model's 256 input, so the segmenter downsamples
  instead of stretching a starved frame up. The `~320` previously suggested in
  `docs/REAL_DEVICE_AI_TRIPLE_FIX_2026-10-23.md` §7 would give a 180-pixel short
  side and was rejected for that reason. Halves the per-frame pixel count
  (147k vs 291k). Pinned by `AiMattingEngineTest`.

**Both are unverified on a device** — none was attached when they were written. The compile is
green and 28 JVM tests pass; the visual result (ghost removal, cut handling, edge fidelity at 512)
still needs a real clip.

### The device session found a real export crash (fixed, re-verification pending)

The phone was attached later on 2026-10-29 and Background Remove ran end to end: 358 frames, no
crash, the summary line logged. **The export then died at PREPARING** with the useless user-facing
message "Video processing failed". The log stack was fully obfuscated; resolving it through
`app/build/outputs/mapping/release/mapping.txt` (written by every release build) gave
`AiExportHooks$compositeMatting$2` → `Bitmap.setPixels` →

> `MaskBitmaps.applyPostProcess` wrote the processed alpha **back into the bitmap it was handed**,
> and its only caller passes `BitmapFactory.decodeFile(sidecarPng)`. Decoded bitmaps are
> **immutable**, so `setPixels` threw `IllegalStateException`. `isNoOp` returns before touching the
> bitmap, which is why this shipped on 10-25 and survived: it only fires once expansion, feather or
> reverse is off zero. **Every user who touched a slider got a dead export.**

Fixed in `2858d7c`: copy-then-write (`mask.copy(ARGB_8888, true)`), caller recycles the decoded
original, and the dead `shortSideOverride` is now actually forwarded to `MaskPostProcess.apply`.
All other `setPixels` call sites were audited and are safe (they write into `createBitmap` /
`copy(..., true)` targets). Guarded by `MaskBitmapsMutabilityTest` (3 source-shape tests).

⚠️ **The fixed APK was never installed** — the phone dropped off USB first. So the export that used
to crash has not been re-run against the fix. `dist/Omelet-1.4.0-release-maskfix.apk`. That single
tap is the first thing to do next session.

**Lesson worth keeping:** a function with a fast path (`isNoOp` → return early) and a slow path is
a hiding place. The slow path is the one that is broken, and it is the one no test exercises.

**Do not** treat items 1–3 as "implement when the new model lands". They are the
cheapest visible win we have, and they are *independent* of the model.

**Also recorded, not scheduled:** CapCut stores the photo mask as an
*algorithm layer on the segment* (`VideoAlgorithmUpdateParam(name = "image_editor_matting",
path = <mask.png>, path_on = true, algorithmType = LVVELocalAlgorithmType.Y)`), not as
baked pixels. That is why it can re-tune expansion/feather/reverse without re-running
the model. As of 2026-10-26 our sidecar PNGs are raw and these three knobs are
applied at composition time (see item 4), which gives them the same
"no re-run" property with the export; the knobs live in the same JSON
blob, so they survive save/reopen like every other tool state.

## CapCut capabilities already present (do not rebuild)

Auto captions (offline Whisper + cloud), subtitle templates (24) and the Persian
subtitle studio, background removal (MediaPipe matting - mask computed in a
post-pass, applied at export), auto-frame (pose
landmarker), OCR (Tesseract, Persian/Arabic/English), beat sync, silence cut,
smart highlights, motion tracking, voice clean / enhance / change, ESRGAN
upscale, transitions (preview **and** export), speed ramps, keyframes, PiP
overlays with shape masks and chroma key, crop / rotate / flip, the full colour
grade, custom font import, and 12 Persian fonts.

## Known approximations (deliberate, documented)

- The preview's **grain** is a tiled noise field, not the export's shader. It does
  not carry the mid-tone weighting, so it is slightly stronger in the blacks and
  whites than the file's.
- The preview's **vignette** follows the preview's own video rect, which for a
  rotated or zoomed clip is the *preview's* rect — the export fits the rotated
  frame into the canvas instead. The falloff and the alpha are identical; only
  the rectangle can differ.
- The **blur-based transitions** preview their geometry and wash but not their
  blur.
- **Background remove (matting)** computes the mask in a **post-pass at 10 fps**
  and applies it at export; the preview never shows the live mask (the panel
  says so). `SequentialFrameSampler` now decodes byte buffers like the export
  does, so the `MediaMetadataRetriever` fallback that cost ~0.33 s per sample is
  gone: measured on the Xiaomi 2107113SG a 36 s clip / 358 samples takes **120 s**
  (`infer=112675ms write=7268ms`), down from 253 s. Inference is now the
  bottleneck; the sampler is no longer a surface consumer at all.
- **Export overlay / VIDEO-background samplers** were moved to byte-buffer decode
  with the export fix (2026-10-24) but have **not** been exercised on the device:
  the test project contains neither an overlay clip nor a video background. They
  use the mechanism proven in `transcode`, which is not the same as a measurement.
- **Export speed** is ~226 ms per output frame on the measured phone (243 s for a
  35.8 s clip). The YUV↔ARGB conversion, the matte composite, the rotation copy
  and the encoder feed have not been profiled separately.

## Dead code — do not extend, and do not "clean up" opportunistically

| Symbol | Note |
|---|---|
| `ui/screens/editor/EditorVideoCanvas.kt` | Superseded. |
| `ui/screens/editor/BottomToolbar.kt` (`BottomToolbarItems` :89) | Legacy second toolbar. The canonical one is `EditorScreen.EditorBottomDockSection`. |
| `ui/screens/editor/EditorBottomDock.kt` (`EditorDockItems` :66) | Legacy shim. |
| `ViralStylePanel.kt` | Unused panel. |
| `model/WaveformGenerator.kt` | Superseded. |
| `ai/PersonMatteEffect.kt` | Superseded stub. |
| `ToolPanel.CROP` (`model/EditorUiState.kt`) | Orphaned panel — no reachable UI entry. |
| `ads/TapsellAds.kt` | Uses a placeholder `"YOUR_TAPSELL_KEY"`. **Do not wire this up or add real keys.** |
| `video/ProjectRepository.kt` | In-memory placeholder implementation. |

## Declared-but-unused fields (bug class #1 candidates)

- `EditorVideoPreviewSection.activeWord` — declared at `ui/screens/editor/EditorScreen.kt:1704` with
  a KDoc and passed at `:1059`, but **never read in the function body** (only a stale comment at
  `:2352`). The shared renderer derives the active word internally from `frameTimeMs`.
- `EditorUiState.activeWordId` — `model/EditorUiState.kt:260`; set in `EditorViewModel.kt:438/455/512`
  and serialized in `model/EditorStateHistory.kt:80`, but its only consumer builds the unused
  `activeWord` above, so it is **effectively dead in the preview**.
- Legacy per-overlay fields on `TextOverlay` (`color`, `fontWeight`, `backgroundColor`,
  `backgroundAlpha`, `fontSizeSp`) are superseded by `subtitleStyle` for the subtitle path —
  `CaptionRenderer` reads the style bundle, not these. **Verify before relying on them.**

Not a bug: `WordMotionKind.HIGHLIGHT_COLOR` returns `WordState.Rest` in both branches
(`ai/WordMotionEngine.kt:90-91`) **by design** — the colour is applied via spans.

## Stale documentation — the code wins

- `README.md` at the repo root claims the AI and timeline features are "not built yet". It is
  outdated; it is still useful only for the import flow.
- Any KDoc asserting third-party behaviour without a citation is a guess until verified — see bug
  class #3 in `bug-classes.md`.
