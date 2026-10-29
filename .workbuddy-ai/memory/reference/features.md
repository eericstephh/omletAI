# reference/features.md — shipped feature invariants

Loaded on demand. `MEMORY.md` carries the traps and the one-line summary of each feature;
this file carries the detail you need **while editing that feature**.

Read the section you need, not the file.

---

## Subtitles

**Templates (2026-09-29, do not re-implement):** `model/SubtitleTemplate.kt` + built-ins
`model/BuiltInSubtitleTemplates.kt` (20, ids `fa_01_clean`…`fa_20_signature`; `fa_01_clean` is DEFAULT,
`PersianFont.VAZIR`). `assets/subtitle_templates.json` holds **19 presets in a different id space**;
loaders `data/subtitle/{JsonTemplateCatalog,SubtitleTemplateRepository}.kt`; UI
`ui/screens/editor/subtitle/SubtitleStudioPanel.kt`. **One renderer for preview AND export:**
`export/CaptionRenderer.kt`. ⚠️ `CaptionBitmap.kt`/`SubtitleBurnIn.kt` have **zero** karaoke matches
because they *delegate* — a per-file grep is not evidence of absence for a bridge. ⚠️ **Dana/Kalameh
cannot be bundled** (commercial; the picker is the free OFL set only — `TextOverlay.kt:228`).
`WordMotionKind` = 11, `CaptionBackgroundKind` = 7, `WordRevealMode` = 4.
⚠️ **`CaptionRenderer` uses a 720-unit canvas** (`CANVAS_UNIT_PX = 720f`) — any overlay matching its
scale converts dp the same way: `dp * canvasHeightPx / 720f`.

**Editing workflow (2026-10-13):** `model/SubtitleEditOps.kt` is the pure, JVM-testable owner of
`splitAt`/`merge`/`retime`/`retimeWord`/`rebuildTextFromWords`; `EditorViewModel` has 5 matching actions;
controls live in `SubtitleStudioPanel.SubtitleEditingSection`.
- ⚠️ **The PERSISTED type is `TextOverlay`, not `Subtitle`.** `ai/Transcription.kt`'s
  `Subtitle`/`TranscribedWord` is the *generation* shape. `TextOverlay` has **no `confidence` field**.
- **`text` and `words` are independent representations.** Every op upholds INV-COHERENCE: split
  partitions (never duplicates a word id), merge concatenates in order, retime **never touches words**,
  ZWNJ survives byte-for-byte, word starts stay monotonic. The whitespace convention when rebuilding
  `text` is load-bearing — `KaraokeRanges` matches by character offset.
- **Split/merge re-derive the frame FROM THE WORDS**, so they do NOT round-trip a *hand-widened* frame.
  Deliberate: **`retime` is the non-destructive op** (frame only, reversible).
- ⚠️ **Invalid input returns `null`, never throws**, and the ViewModel **returns before
  `historyDelegate.push()`** — a refused edit must leave no undo entry.

---

## Picture-in-Picture + shape masks (2026-10-13)

Two shared sources own everything; **neither renderer may define a shape or a placement of its own.**

- **`model/OverlayMask.kt`** — `OverlayMaskType{NONE,CIRCLE,ROUNDED_RECT,HEART,STAR}`,
  `OverlayMaskSettings(type, cornerRadiusDp, featherDp, invert)` (`isIdentity` = the write-nothing
  predicate; ⚠️ `NONE` + `invert` is **not** the identity — it is a full-frame knockout),
  `OverlayMaskGeometry.outline(type, cornerRadiusFraction)` → a **closed normalised polygon**
  (`List<NormPoint>`, 0..1, first point not repeated). Polygons not beziers: two independent curve
  evaluators kept in sync "by review" is the failure mode this design removes. `CIRCLE_SAMPLES = 96`,
  `HEART_SAMPLES = 120` (parametric cardioid, min/max normalised **from the samples**, y flipped),
  star = 10 vertices at `PENTAGRAM_INNER_RATIO = 0.381966f`. `sanitize` clamps radius `0..160` dp,
  feather `0..48` dp; `cornerRadiusFraction` uses the **short side**, capped at `0.5f`.
- **`model/OverlayPipGeometry.kt`** — `resolveBox(...)` → `PipBox`, plus `isVisibleAt`, `localMs`,
  `resolveTarget`, `editorMsForSourcePts`, `clampScale`/`clampNorm`, `hitTest` (rotates into the box
  frame, then `OverlayMaskGeometry.contains`), `aspectRatioFor`, `defaultPlacement() = (0.12, 0.12, 0.34)`.
  ⚠️ **`positionX`/`positionY` are the box's TOP-LEFT normalised to canvas; `scale` is box width as a
  fraction of canvas width.** A centre-anchor reading pushes the model default off-screen.
- ⚠️ **`CANVAS_UNIT_PX = 720f`** — the constant `CaptionRenderer` and `ProgressBarGeometry` already
  share. dp→px is `dp * canvasHeightPx / 720f`, **NOT** Compose `Dp.toPx()`.
- **Renderers:** `ui/screens/editor/OverlayMaskShape.kt` (`Outline.Generic(Path)`) +
  `export/OverlayMaskRenderer.kt` (`android.graphics.Path`, `INVERSE_EVEN_ODD`). Feather shares one
  artefact — `OverlayMaskRenderer.rasterizeAlphaMask` → preview `BlendMode.DstIn`, export
  `PorterDuff.DST_IN` + `BlurMaskFilter` (**software canvas only**; `Canvas(bitmap)` is software, the
  hardware frame canvas never sees a mask filter). Inversion: the export flips the fill type; the
  preview uses `DstOut` on an `Offscreen` layer, because Compose has no inverse fill type.
- **Preview:** `OverlayPipLayer.kt` (sits above video/subtitles, **below** the text gesture surface;
  hit testing is **per box and mask-aware**, so a circular facecam does not swallow taps in its
  transparent corners) + `OverlayPipPlayer.kt` (one small `ExoPlayer` per video overlay, slaved to the
  editor clock, **muted on purpose** — the export composites frames only and copies segment audio
  sample-for-sample). ⚠️ `pipVideoOverlays` (filtered to video) feeds the **player**;
  `pipOverlays = uiState.overlays` (all) feeds the **layer**, so stills render too.
- **Export:** `OverlayCompositeRenderer.kt` (CPU `FrameOp`) + `AiExportHooks.burnMediaOverlays`.
  ⚠️ **The PiP pass runs LAST in `prepareSourceUri`**, after matting → reframe → tracking burn-in,
  because `positionX`/`positionY`/`scale` describe the frame the viewer finally sees. Gated on
  `hasRenderableOverlays` (a no-overlay project must export through the old path byte-for-byte).
  ⚠️ **A `FrameOp` must mutate and RETURN `src`** — the pipeline recycles a returned bitmap that differs
  from the input, so returning a fresh instance OOMs a 4K export. Overlay media is decoded forward-only
  (`OverlayVideoSampler`, `MAX_ADVANCE_STEPS = 600`); a request before the current position returns the
  current frame and one past the end returns the last, so a short overlay holds rather than blinking
  out. Media3 1.5.0 has **no public video-over-video compositing API** (`OverlayEffect`/`TextureOverlay`
  are bitmap-only and `getBitmap` runs on the GL thread where a per-frame decode is illegal) — hence the
  CPU pre-pass.
- **Chain:** `VideoProject.mediaOverlays` → `RenderGraphBuilder` → `RenderSegment.mediaOverlays` →
  `AiExportHooks` → compositor. `RenderSegment` carries the **whole list**; the compositor filters by time.
- **UI:** `ui/screens/editor/OverlayMaskPanel.kt` under «ماسک» (`ToolPanel.OVERLAY_MASK` = **GALLERY**,
  COMPACT stays 11). Chips in `OverlayMaskGeometry.panelOrder`; the radius slider is **hidden** (not
  disabled) unless `ROUNDED_RECT`; «معکوس کردن ماسک» switch; «حذف ماسک» commits
  `OverlayMaskSettings.Default`. `EditorScreen` resolves the target through the **same**
  `OverlayPipGeometry.resolveTarget` the preview chrome uses, so the highlighted PiP and the edited PiP
  can never be two different objects.
- **Undo:** `setOverlayMask(id, settings, pushHistory)` is the live/commit pair (equality guard → push →
  persist, all gated). A drag uses `beginOverlayTransform` (one push on the first moved frame) +
  `updateOverlayTransform` (state only, clamped) + `endOverlayTransform` (one persist).
  ⚠️ `selectOverlay` deliberately does **not** clear `selectedClipId` — the mask chip lives in the CLIP
  toolbar, which only shows while a clip is selected.
- **Persistence:** the mask rides `mediaOverlaysJson` (no Room migration). `aspect`, `rot` and the four
  `mask*` keys are written **only when non-default**, so a pre-feature project serialises
  byte-identically. ⚠️ `maskRadius` is written **only for `ROUNDED_RECT`** — a radius is meaningless for
  the other shapes, so a circle/heart/star does not remember one across a save.
- **Tests:** `model/OverlayMaskTest.kt` (geometry + normalised bounds), `db/OverlayMaskPersistenceTest.kt`
  (round-trip + defaults + clamping), `viewmodel/OverlayMaskWiringTest.kt` (source-shape parity guards).

---

## Undo/redo (any new document field)

`model/EditorStateHistory.kt`'s `restoreDocumentFrom` uses `snapshot.copy(...)` over an explicit volatile
list, so a NEW document field is undo/redo-aware with no edit. Sliders must split `onSettingsChanged`
(state-only, per-frame) from `onSettingsCommitted` (undo push + Room write) on `onValueChangeFinished` —
the 20-deep, non-deduping undo stack otherwise floods on one drag. `ProgressBarPanel.LabeledSlider` is
the reference shape.

## Room additive migration

`ProjectDatabase version` bumps + an `ALTER TABLE … NOT NULL DEFAULT '…'` migration registered in
`di/AppModule.kt`; `fallbackToDestructiveMigration()` is deliberately absent. ⚠️ **`saveProjectMeta` has
4 production call sites** and new params have **no default** — a forgotten call site is a compile error,
not a silent reset.

---

## Video Progress Bar (نوار پیشرفت)

Canvas-wide story timer, preview/export parity by construction (one shared `ProgressBarGeometry.layout`).
⚠️ **Name collision:** `ProgressBarSpec`/`ProgressBarShape` in `model/SubtitleTemplate.kt` are a
*per-word underline inside a caption box* — a different feature sharing the Persian name. New types:
`ProgressBarSettings`/`ProgressBarPosition`/`ProgressBarGeometry`/`ProgressBarLayout`/`ProgressBarOverlay`.
- `ProgressBarGeometry` owns `CANVAS_UNIT_PX = 720f` (**the same constant `CaptionRenderer` uses**).
  ⚠️ The preview must **not** convert dp with Compose's `Dp.toPx()` — that scales with screen density
  while the exported bar scales with encoder size.
- ⚠️ **`presentationTimeUs` is ITEM-LOCAL** in Media3's effect chain. A whole-composition bar needs
  `compositionOriginMs + presentationTimeUs / 1000`, origins from pure `CompositionTimeline.originsMs()`.
- **Effect order:** `… CanvasBackgroundOverlay` → **`ProgressBarOverlay`** → `SubtitleBurnIn` → audio.
- `ToolPanel.PROGRESS_BAR` is **COMPACT**; root chip label «پیشرفت» (full «نوار پیشرفت» is 10 chars).

## Custom Font Importer (.ttf / .otf)

`model/CustomFont.kt` (`id` = a UUID we mint; the on-disk name is `<uuid>.<ttf|otf>`, so traversal is
impossible **by construction**); `data/font/CustomFontRepository.kt` interface + `Default…`
(`filesDir/fonts/index.json`) + `InMemory…` fake bound via `@Binds`. `data/font/CustomFontLocator.kt` is
a process-wide paths-only `@Volatile` map with ONE writer, existing because `CaptionRenderer` is
deliberately **DI-free** — preview and export resolve the **same** font through it, and `render`'s
`resolveCustomFontPath` param *defaults to* `CustomFontLocator::pathFor`.
**The persisted `fontFamily` is one opaque string with two namespaces:** a `PersianFont` enum name
(`"VAZIR"`) or `"custom:<uuid>"`. ⚠️ **Delete re-writes BOTH `overlay.fontFamily` and
`overlay.subtitleStyle?.fontFamily`** — sweeping only one leaves the decorative renderer pointing at a
deleted file, visible **only in the export**. UI is the shared
`ui/screens/editor/CustomFontStrip.kt`, used by BOTH `SubtitleStudioPanel` and `TextOverlayPanel`.

## Voice Changer (تغییر صدا) — shipped 2026-10-13

Per-clip presets (pitch + time-domain DSP) in preview **and** export.
- ⚠️ **`playbackRate` is 1.0 for EVERY effect, deliberately** — a naive chipmunk desyncs A/V and
  multiplies against the `SpeedCurve` ramp. All effects are duration-neutral; asserted by test.
- **Only CHIPMUNK/DEEP are pure pitch** (`SonicAudioProcessor`). ROBOT = ring mod + band-pass,
  TELEPHONE = band-pass (300–3400 Hz), ECHO = delay line — hand-rolled Biquads (two Butterworth
  Q = 1/√2, transposed DF-II; edges clamp `[20 Hz, 0.45 × Nyquist]`).
  ⚠️ **LFO + delay pointer advance once per FRAME, not per buffer** — per-buffer makes the echo depend
  on Media3's buffer size.
- ⚠️ **`RING_MOD_BLEND 0.85f` does NOT stop the modulator nulling** — `(1-B) + B·sin` spans
  `[1-2B, 1]` = `[-0.7, 1]` and still crosses zero twice per cycle; the blend buys only a **non-zero
  mean** (`1-B` = 15% surviving dry). `B < 0.5` stays positive but is a tremolo, not a robot.
- **Preview seam = `protected DefaultRenderersFactory.buildAudioSink(...)`**; the subclass mirrors the
  decompiled default body + `setAudioProcessors(...)`.
- ⚠️ **Media3 builds the chain `[custom…, silenceSkip, sonic]`** → a custom processor runs **before**
  the rate stage. ⚠️ **Processors must NEVER report themselves inactive** — Media3 asks `isActive()`
  once at `configure()` and *drops* a processor that says no. It re-reads the profile **every buffer**.
- **Chain order is the parity contract:** preview `[voice, sonic(pitch)] + [silenceSkip, sonic(rate)]`,
  export `volume → voice → pitch → speed → fade`; both = timbre → pitch → rate. Pitch is deliberately
  **NOT** routed through `PlaybackParameters.pitch` (that would reorder the stages).
- ⚠️ **`AudioMixerPlayer` gives each stem its own bridge** (two songs, two effects).
  `ToolPanel.VOICE_CHANGER` is **GALLERY**; chip «وویس» lives in **both** the clip and audio rows.

---

## Native AI engines — ABI + failure reporting (2026-10-13)

- **The APK ships `arm64-v8a` ONLY** (14 `.so`; `abiFilters += listOf("arm64-v8a")` is deliberate).
  `libwhisper.so` is healthy (`ELF64`/`AArch64`, all four JNI symbols exported, LOAD segments 16 KB
  aligned). Inspect with
  `C:/android/ndk/30.0.16138531/toolchains/llvm/prebuilt/windows-x86_64/bin/{llvm-nm,llvm-readelf}.exe`.
- **The old error was a LIE, not a bug.** One sentence («…پردازنده…») came from **six unrelated call
  sites**. Fix = `ai/NativeEngineFailure.kt` (pure enum + `classify()`/`classifyModel()`); only
  `CPU_INSTRUCTION_UNSUPPORTED` may blame the CPU. `UnsatisfiedLinkError`'s **message** is the only
  discriminator: `"No implementation found"` → `JNI_SYMBOL_UNRESOLVED`; `"e_machine"` /
  `"is 32-bit instead of 64-bit"` → `CPU_INSTRUCTION_UNSUPPORTED`; else → `LIBRARY_LOAD_FAILED`.
  Check `libraryAvailable` first.

## Logging (2026-10-12) — shipped, stable

`logging/` package. `OmeletLog` (@Singleton) → logcat (tags unchanged) + JSONL to
`filesDir/logs/omelet.log` via `OmeletLogStore` (one IO coroutine, `Channel(1024, DROP_OLDEST)`,
`writesDisabled` latch on failure — never crashes). Pure: `LogRecord`/`LogLevel`, `LogSanitizer`
(⚠️ **BEARER must run before KV_SECRET** or `Authorization: Bearer sk-…` leaks), `LogFormat`
(⚠️ **LOWER generation = NEWER**), `LogReportBuilder`, `DiagnosticsSummary`. Caps 512 KB/file, 4 files,
2 MB. Settings «گزارش‌های فنی» (`DiagnosticsSection.kt`). Share = `ACTION_SEND` text —
**no FileProvider in this project**.

## Tapsell Mediation — shipped, stable

`ir.tapsell:tapsell:1.4.0-alpha03` + `repo.tapsell.ir` (Tapsell **Plus** is the deprecated predecessor).
The app key lives in **manifest meta-data** via `manifestPlaceholders["TapsellMediationAppKey"]`;
`TapsellAds.APP_KEY` is a second copy feeding only `isConfigured` — keep both in sync. Per-placement
gates `hasAppOpenZone`/`hasExportZone` — do **not** fold back into one flag. The export ad fires from
`LaunchedEffect(uiState.exportState)`, `Succeeded` only.

## User-visible messages

⚠️ **This app has no Toast machinery.** The only user-visible channel is the dismissible banner in
`EditorScreen.kt`: `editorNotice` = refusals, `errorMessage` = failures, `editorConfirmation` (green) =
successes.
