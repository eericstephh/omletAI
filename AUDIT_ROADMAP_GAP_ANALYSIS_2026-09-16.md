# AIerix Cut — Codebase Audit & Roadmap Gap Analysis

**Date:** 2026-09-16
**Scope:** Read-only inspection. No application code was written or modified *for this audit*.
**Codebase:** `AICreatorStudio`, package `com.aicreatorstudio.app` — 206 Kotlin source files.
**Source root (all paths below):** `app/src/main/java/com/aicreatorstudio/app/`

> **Note on this file:** the original copy was saved as `AUDIT_ROADMAP_GAP_ANALYSIS.md`,
> which was later overwritten with unrelated prompt text. This is the restored copy
> under a non-colliding name. See the status banner below for what has since been fixed.

---

> ## ⚠️ STATUS UPDATE — 2026-09-16 (later the same day)
>
> This report was written as a **read-only audit**. Since then, fixes have landed:
>
> | Finding | Status |
> |---|---|
> | 🔴 Blocker #1 — export renders only the first clip | ✅ **FIXED** — all segments now render into one `EditedMediaItemSequence` inside a `Composition` |
> | 🔴 Blocker #2 — per-clip speed ignored by export | ✅ **FIXED** — `SpeedChangeEffect` (video) + `SpeedChangingAudioProcessor` (audio) |
> | Duplicate clip overlapped its source exactly | ✅ **FIXED** — now placed after the original |
> | Undo/redo never persisted | ✅ **FIXED** — `persistAfterHistoryChange()` |
> | Undo/redo clobbered live state | ✅ **FIXED** — `restoreDocumentFrom` merge |
> | Two effects fought over the player's playback rate | ✅ **FIXED** |
> | 🟠 Blocker #3 — transitions cosmetic | ❌ Still open |
> | `UpscalerExportEngine.kt:118` still uses `segments.first()` | ❌ Still open |
> | Preview still only plays the first video clip | ❌ Still open |
> | Timeline waveform synthetic; fade in/out unwired; scopes use synthetic data | ❌ Still open |
>
> Test suite: 818 → **830 tests, 0 failures, 0 errors**. The body below is preserved
> as the original audit of record; the two ✅ blocker rows are superseded.

---

## 0. Executive Summary

The app is **substantially further along than a typical prototype**, and several subsystems are genuinely production-grade:

- **Local Whisper is real.** whisper.cpp v1.7.6 is vendored and compiled via NDK/CMake into `libwhisper.so`, with real JNI (`whisper_full`) inference and **real token-level word timings** (`token_timestamps = true`). Models are SHA-256 verified.
- **Color pipeline is real.** A Media3 `RgbMatrix` GL pass drives both live preview and export. 10 filter presets plus a full Lumetri grading matrix are baked into the exported file.
- **Aspect-ratio export is real.** 9:16 / 16:9 / 1:1 / 4:5 genuinely change the output file dimensions.
- **Undo/redo is real.** A bounded 20-entry snapshot stack, wired to the top bar, with 66 call sites.

However, there is **one systemic architectural gap that dominated everything else**, plus a consistent class of "UI shell" features:

### 🔴 Blocker #1 — Export rendered only the first clip  → ✅ NOW FIXED

```kotlin
// export/VideoExportEngine.kt:204-205 (BEFORE the fix)
// v1: only the first segment. Future phases will iterate `graph.segments`.
val segment = graph.segments.first()
```

Every multi-clip edit was silently discarded on export. This single line invalidated **transitions, multi-track audio mixing, and multi-clip sequencing** in one stroke.

### 🔴 Blocker #2 — Per-clip speed was ignored by export  → ✅ NOW FIXED

`RenderSegment.speed` was populated (`export/RenderGraphBuilder.kt:87`) but had **zero consumers anywhere in `export/`**. The Speed panel and live preview worked; the exported file played at 1×. A *silent* correctness failure.

### 🟠 Blocker #3 — Transitions are cosmetic  → ❌ STILL OPEN

43 transition types are defined (`model/Transitions.kt:26-82`) and the math engine is real (`TransitionEngine`), but its only consumers are the **dead** `EditorVideoCanvas.kt:144` and the panel's own demo thumbnails. `TransitionPanel` writes `clip.transitionIn/Out` strings that **nothing in the live preview or export reads**. `RenderSegment` has no transition field at all.

### 🟠 Pattern — "Field exists, nothing reads it"

A recurring failure mode: model fields and ViewModel setters are implemented and persisted, but no renderer consumes them. Confirmed instances: `fadeInMs`/`fadeOutMs`, `opacity`, `animation`, `fillMode`, `vignetteAmount`, `grain`, `sharpness`, `canvasBackground`. **Model-field presence is not evidence of a working feature.**

### 🟠 Pattern — Dead code

`EditorVideoCanvas.kt` (313 lines), `BottomToolbar.kt`, `EditorBottomDock.kt`, `ViralStylePanel.kt` and `WaveformGenerator.kt` all have **no call sites** in `main`. `ToolPanel.CROP` is dispatched in the panel `when` but has no dock entry, making the dedicated Crop panel unreachable.

---

## 1. Roadmap Checklist — Summary Table

**Legend:** ✅ Fully Working · 🟡 Partially Built / Broken · ❌ Missing
*Statuses reflect the original audit; see the banner above for what has since been fixed.*

| Feature Name | Current Status | Relevant File(s) in our codebase |
|---|---|---|
| **TIMELINE & MULTI-LAYER** | | |
| Video track / layer | ✅ Fully Working | `ui/screens/editor/EditorTimeline.kt` (`ClipBlock` 1186-1241), `model/EditorUiState.kt:527` |
| Audio tracks (Video Audio / Music / SFX) | 🟡 Partially Built / Broken | `EditorTimeline.kt:2191` (`AudioLaneRow`), `model/AudioClip.kt`, `audio/AudioPairingService.kt` — export drops audio-only clips (`export/RenderGraphBuilder.kt:63`) |
| Subtitle track / layer | ✅ Fully Working | `EditorTimeline.kt:1494` (`SubtitleTrackRow`), `ai/SubtitleGenerator.kt` |
| Stickers / Text overlays (multi-layer) | 🟡 Partially Built / Broken | `EditorTimeline.kt:1390` (5 text layers), `:1605` (3 overlay layers) — lanes are **tap-select only, no drag** |
| Clip drag along timeline | 🟡 Partially Built / Broken | `EditorTimeline.kt:1186` → `EditorViewModel.updateClipPosition:1620` — Video/Music/SFX lanes only |
| Timeline edge-resize trim | ✅ Fully Working | `EditorTimeline.kt:1193` (`TrimEdge`) → `setClipTrim:1926` |
| Timeline pinch-zoom + horizontal scroll | ✅ Fully Working | `EditorTimeline.kt:995-1026`, `EditorLayoutSpec.kt:81-82` |
| Timeline snap-to-grid | ❌ Missing | `EditorLayoutSpec.kt:83` (`TIMELINE_SNAP_MS`) referenced **only in comments** |
| Clip reorder (drag-to-reorder) | ❌ Missing | No `reorderClip`/`swapClips`; order implicit via `timelineStartMs` |
| **BASIC EDIT TOOLS** | | |
| Split (at playhead) | ✅ Fully Working | `EditorViewModel.splitClip:1865`, `EditorScreen.kt:1045` |
| Trim (in/out points) | ✅ Fully Working | `setClipTrim:1926`, `ToolbarSubPanels.kt:189-251` |
| Duplicate | 🟡 → ✅ Fixed | `duplicateClip:1947` — copy used to keep identical `timelineStartMs` |
| Delete | ✅ Fully Working | `deleteClip:1907` |
| Speed control (per-clip) | 🟡 → ✅ Fixed | `setClipSpeed:2024`; preview OK, export now bakes `SpeedChangeEffect` |
| **UNDO / REDO** | | |
| Undo/Redo architecture | ✅ → ✅ Fixed | `EditorViewModel.kt:3552-3600`, `historyLimit = 20`, 66 `pushHistory()` sites; restore now via `restoreDocumentFrom` |
| **VIDEO ADJUSTMENTS** | | |
| Brightness / Contrast / Saturation | ✅ Fully Working | `ColorGradingPanel.kt`, `ColorGradingMath.kt`, `export/GradeRgbMatrix.kt` |
| Lumetri grading (Exposure, Highlights, Shadows, Whites, Blacks, Temp, Tint, Vibrance, Hue×8) | ✅ Fully Working | `model/ColorGrading.kt:21-55`, `ColorGradingMath.kt:153-351` |
| Sharpness | ❌ Missing | Field exists; **not encoded in `gradingMatrix`** |
| Vignette | ❌ Missing | `ColorGradingMath.vignetteFactor:456` has **zero callers** |
| Grain | ❌ Missing | Field exists; not applied anywhere |
| Gamma | ❌ Missing | No field in `model/ColorGrading.kt` |
| Color scopes / histogram / vectorscope | 🟡 Partially Built / Broken | `ColorScope.kt:444-635` — real renderer over **synthetic** data (`rememberScopeSample:351-411`) |
| **SUBTITLE ENGINE** | | |
| Whisper integration (local, offline) | ✅ Fully Working | `ai/WhisperEngine.kt`, `ai/WhisperNative.kt`, `app/src/main/cpp/CMakeLists.txt:29-84`, `app/build.gradle.kts:58-82` |
| Whisper integration (cloud) | 🟡 Partially Built / Broken | `CloudWhisperService.kt:330` — private Cloudflare Worker, **not Groq** despite the label; `parseSuccess` hardcodes `words = emptyList()` → no karaoke |
| Manual text editing of subtitles | ✅ Fully Working | `updateTextOverlay:938`, `TextOverlayPanel.kt:179` |
| Cue list / subtitle timeline editor | ❌ Missing | No cue list UI |
| Subtitle retiming / shift / split | 🟡 Partially Built / Broken | `moveSelectedTextToPosition:1075`, `updateSelectedTextDuration:1212` — no split, no shift-all |
| Word-level highlight — preview | ✅ Fully Working | `activeWordIdAt:536`, `buildKaraokeAnnotatedString:2628`, `ai/WordMotionEngine.kt` |
| Word-level highlight — export | 🟡 Partially Built / Broken | `SubtitleBurnIn.renderViralWords:373` — only when `motionKind != NONE` (`:139-141`) |
| Subtitle styling / templates / motion | ✅ Fully Working | `model/SubtitleStyle.kt`, `model/SubtitleTemplate.kt`, `subtitle/SubtitleStudioPanel.kt` |
| SRT import / export | ✅ Fully Working | `ai/SrtParser.kt`, `ai/SrtFormatter.kt`, `onExportSrtClicked:3016`, `onImportSrtClicked:3092` |
| Persian normalization + correction | ✅ Fully Working | `ai/subtitle/PersianNormalizer.kt`, `subtitle/corrector/*`, `PersianFonts.kt` |
| **AUDIO TOOLS** | | |
| Extract audio (video → audio) | 🟡 Partially Built / Broken | `ai/AudioExtractor.kt:304` is real but **AI-internal only**; no user-facing flow |
| Volume control (per-clip) | ✅ Fully Working | `AudioPanel.kt:100`, `updateClipVolume:1968`; export `VideoExportEngine.kt:601` |
| Mute | ✅ Fully Working | `updateClipMute` family, `EditorScreen.kt:758` |
| Fade in / out | ❌ Missing | `setClipFadeInMs:2078` / `setClipFadeOutMs:2096` — fields **never read** (only JSON persistence) |
| Waveform on timeline | ❌ Missing | `EditorTimeline.kt:1356` synthesises from `seed.hashCode()`; real `model/WaveformGenerator.kt` unused |
| Multi-track simultaneous playback | 🟡 Partially Built / Broken | Preview real (`AudioMixerPlayer.kt`, `EditorScreen.kt:361`); export dropped (`RenderGraphBuilder.kt:63`) |
| Ducking / EQ / pitch-shift | ❌ Missing | No matches |
| **EFFECTS / FILTERS / TRANSITIONS / KEYFRAMES** | | |
| Filters / effects (10 presets) | ✅ Fully Working | `EffectsPanel.kt`, `EffectPresets.kt:43-129`, `VideoEffect` (`EditorUiState.kt:485`) |
| Viral Style | ❌ Missing (unwired) | `ViralStylePanel.kt:68` has **no call site** |
| Transitions (43 types) | 🟡 Partially Built / Broken | `model/Transitions.kt:26-82`; `TransitionEngine` consumed only by dead code + panel thumbnails |
| Clip animations (11 types) | 🟡 Partially Built / Broken | Preview real (`EditorScreen.kt:1712`); export **ignored** |
| Keyframes (general-purpose, user-editable) | ❌ Missing | Only `CropKeyframe` (`model/AiToolSettings.kt:171`) — an internal AI Auto-Frame output |
| AI Auto-Frame crop keyframes | ✅ Fully Working | `ai/engines/SaliencyTrackerEngine.kt:255`, consumed by `export/AiExportHooks.kt:430` |
| **EXPORT ENGINE & ASPECT RATIOS** | | |
| Export engine (core pipeline) | 🟡 → ✅ Fixed | `export/VideoExportEngine.kt` — Media3 **Transformer 1.5.0**, now multi-clip via `Composition` |
| Multi-clip composition | ❌ → ✅ Fixed | was `VideoExportEngine.kt:205` `segments.first()` |
| Transition rendering (export) | ❌ Missing | No transition code in `export/` |
| Subtitle burn-in (export) | ✅ Fully Working | `export/SubtitleBurnIn.kt` — karaoke caveat above |
| Audio mixing (export) | 🟡 Partially Built / Broken | Single-clip volume works; multi-track mix not baked |
| Aspect ratios — 16:9 / 9:16 / 1:1 / 4:5 | ✅ Fully Working | `model/CanvasPreset.kt:31-35`, `CanvasPanels.kt:72-90`; export honors dimensions (`VideoExportEngine.kt:578-590`) |
| Aspect ratio — Free / Original | ✅ Fully Working | `CanvasPreset.FREE` → falls back to source frame / `ExportQuality` |
| Canvas background (color / blur) | ❌ Missing | Preview-only — `@Suppress("UNUSED_PARAMETER") canvasBackground` |
| Export quality (720p / 1080p / 4K) | 🟡 Partially Built / Broken | `export/ExportConfig.kt:62-97` — dimensions apply, **bitrate & fps never passed to the Transformer** |
| Export fill mode (Fit / Fill / Manual) | ❌ Missing | `LAYOUT_SCALE_TO_FIT` hardcoded |
| AI export hooks (Matting / Auto-Frame / Voice Clean) | ✅ Fully Working | `export/AiExportHooks.kt:69-133, 600-750` |

---

## 2. Cross-Cutting Findings

### 2.1 Dead code inventory (no call sites in `main`)

| File | Note |
|---|---|
| `ui/screens/editor/EditorVideoCanvas.kt` | 313 lines; the only consumer of `TransitionEngine` |
| `ui/screens/editor/BottomToolbar.kt` | Superseded by `EditorScreen.EditorBottomDockSection` |
| `ui/screens/editor/EditorBottomDock.kt` | Same |
| `ui/screens/editor/ViralStylePanel.kt` | Presets consumable, panel unreachable |
| `model/WaveformGenerator.kt` | Real PCM peaks; only referenced by its own test |

### 2.2 Orphaned UI

- `ToolPanel.CROP` is dispatched in the panel `when` (`EditorScreen.kt:4281`) but **no dock entry references it** → the dedicated Crop panel is unreachable (only `TRANSFORM` is offered).

### 2.3 Silent-failure risks

1. **Speed** — preview correct, export wrong. *(fixed)*
2. **Transitions** — panel accepts a selection and persists it; output unchanged.
3. **Fade in/out** — sliders move, nothing audible changes.
4. **Multi-clip** — user builds a 10-clip sequence; export contains clip #1. *(fixed)*
5. **Export quality** — "4K" changes dimensions but not bitrate.
6. **Sharpness / Vignette / Grain** — sliders move, image unchanged.

---

## 3. Recommended Remediation Order

**Phase 1 — Unblock the export core** → ✅ **DONE 2026-09-16**
1. ✅ Iterate `graph.segments` instead of `.first()`.
2. ✅ Honor `segment.speed`.
3. ⬜ Wire `ExportConfig.bitrate` / `frameRate` into the Transformer builder.

**Phase 2 — Close the silent-failure gaps**
4. ⬜ Implement transition rendering (preview + export), or explicitly disable/annotate the panel.
5. ⬜ Implement audio fade in/out in preview + export.
6. ⬜ Bake clip animations into export, or label them preview-only.
7. ⬜ Apply `sharpness` / `vignette` / `grain` in the matrix, or hide the controls.

**Phase 3 — Feature completion**
8. ⬜ Multi-track audio mixing in export (`RenderGraphBuilder` drops `isAudioOnly`).
9. ⬜ User-facing "Extract audio" flow reusing `ai/AudioExtractor.kt`.
10. ⬜ Real waveform on the timeline (wire `WaveformGenerator`).
11. ⬜ Timeline snapping + drag-to-reorder; drag on Text/Overlay lanes.
12. ⬜ General-purpose keyframe system.
13. ⬜ Cloud Whisper: return `words[]` for karaoke, or rename the "Groq" label.
14. ⬜ Cue list / subtitle timing editor.
15. ⬜ Fix `UpscalerExportEngine.kt:118` (`segments.first()`) — same bug class as Blocker #1.

**Phase 4 — Cleanup**
16. ⬜ Delete dead code, or wire it.
17. ⬜ Wire `ToolPanel.CROP` to a dock entry.
18. ⬜ Replace synthetic scope data with real frame sampling.

---

## 4. Verification Notes

Every ❌ / 🟡 claim in Section 1 was confirmed by direct grep/read, not inferred:

- `graph.segments.first()` — `export/VideoExportEngine.kt:205` *(since fixed)*
- `segment.speed` — grep for `.speed` across `export/` returned **zero** matches *(since fixed)*
- `TransitionEngine` — 3 references total: its definition, the dead `EditorVideoCanvas.kt:144`, and `TransitionPanel` demo thumbnails
- `vignetteFactor` — 1 reference: its own definition
- `EditorVideoCanvas` — 1 reference: its own definition
- `WaveformGenerator` — 1 reference: its own definition (plus a KDoc mention)
- `fadeInMs`/`fadeOutMs` — only setters, `AudioPanel`, and `db/EditorStateJson.kt` persistence
