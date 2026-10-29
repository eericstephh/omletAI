# Aspect Ratio / Orientation Bug — Root Cause & Fix

**Date:** 2026-09-30
**Report:** Portrait (9:16) imports display correctly; landscape (16:9) imports are
"forced / stretched or cropped into vertical dimensions".
**Result:** 1782 tests completed, 2 failed (pre-existing), 2 skipped. +49 new tests,
0 regressions. `assembleDebug` OK.

---

## 1. Headline: the reported asymmetry cannot be produced by this app's code

Before fixing anything, I swept the entire `app/src/main` tree — manually and with a
dedicated search pass. **There is no code path in this repository that forces a landscape
source into a portrait frame.** That is a real finding, and it matters, because it tells you
where the bug actually lives.

The preview's aspect ratio is a pure pass-through of the clip's own stored dimensions:

```kotlin
// EditorScreen.kt:1078 — the ONLY aspect source in production
sourceAspect = if (activeClip != null && activeClip.width > 0 && activeClip.height > 0)
    activeClip.width.toFloat() / activeClip.height.toFloat() else 16f / 9f,

// EditorScreen.kt:1843 + 2044 — the ONLY .aspectRatio(...) in production
val targetRatio = canvasPreset.effectiveAspectRatio(sourceAspect)
...
.heightIn(max = maxHeight)
.aspectRatio(targetRatio)
```

`CanvasPreset.effectiveAspectRatio` returns `sourceAspect` unchanged for `FREE`. The
foreground player is `RESIZE_MODE_FIT` (default `FillMode.FIT`) — it letterboxes and cannot
stretch or crop.

**And every fallback in the tree is landscape-biased.** `EditorUiState.canvasPreset`,
`VideoProject.canvasPreset`, `ProjectEntity.canvasPreset`, `MIGRATION_4_5`'s
`DEFAULT 'WIDE_16_9'`, `decodeCanvasPreset`'s `getOrDefault(WIDE_16_9)`, and every
`ExportQuality` (`HIGH_1080P` = 1920×1080). A fallback can therefore only ever break
**portrait** — the opposite of what was reported.

`VERTICAL_9_16` is never assigned programmatically anywhere. `EditorVideoCanvas.kt` (which
does contain a `CanvasAspect.Vertical(9f/16f)` and a `rotComp = 16f / 9f`) has **no call
sites** — it is dead code. `AutoFrameSettings`' 9:16 default is reached only when the user
explicitly runs the Auto Frame tool. `EditorTimeline` has no thumbnail/filmstrip bitmap
pipeline at all (waveform + gradient placeholders only — no Coil/Glide, no `getFrameAtTime`).

**Conclusion:** the remaining variable is the media file's own metadata. The fix below
hardens exactly that, and removes every hard-coded landscape assumption that could produce
the *mirror* of the reported symptom.

> **Lesson worth keeping:** the *direction* of a reported asymmetry is evidence. When a user
> says "A is fine, B is broken", enumerate which mechanisms *can* produce that direction
> before hunting for any mechanism at all. Four separate candidates were ruled out here
> purely because they would have broken portrait instead of landscape.

---

## 2. What was actually broken — four real defects, one per area you listed

### Area 1 — Metadata extraction & rotation handling

**Defect.** `VideoMetadataExtractor` did the rotation swap inline, untestably (it needs a real
`MediaMetadataRetriever`), with an exact `rotation == 90 || rotation == 270` test and no guard:

```kotlin
val (displayWidth, displayHeight) = if (rotation == 90 || rotation == 270) {
    rawHeight to rawWidth
} else {
    rawWidth to rawHeight
}
```

Three problems:

1. **Rotation was not normalised.** Containers and devices hand back `-90`, `450`, `270`.
   `-90` fails `== 90` and `== 270`, so a legitimate quarter turn was silently dropped and the
   clip rendered sideways.
2. **The metadata was trusted unconditionally.** On a meaningful set of Android versions,
   devices and container layouts, `METADATA_KEY_VIDEO_WIDTH/HEIGHT` already carry the
   rotation. Swapping again then flips an axis that was never rotated — a landscape clip
   comes out portrait. Metadata alone cannot tell you which convention you are in.
3. **Degenerate reads were not distinguished from valid ones.**

**Fix.** New pure, JVM-testable `model/VideoDimensions.kt`:

- `normalizeRotation(Int)` folds any value into `[0, 360)`.
- `isQuarterTurn(Int)` — only `90`/`270` exchange the axes. `180` deliberately does not.
- `resolve(rawW, rawH, rotation, probeW, probeH)` swaps only for a real quarter turn, and
  returns degenerate input **unchanged** rather than inventing a plausible pair (inventing
  one makes a failed read indistinguishable from a correct reading).
- The **probe** is the decisive part. `MediaMetadataRetriever.getFrameAtTime` returns a
  **rotation-applied** `Bitmap`, so its orientation is ground truth. When it contradicts the
  metadata-derived orientation, **the probe wins**. It can decide orientation only — never
  pixel counts — so it can never invent a resolution.

`VideoMetadataExtractor` now takes one best-effort probe per import, wrapped in
`runCatching`, falling back to the metadata reading on failure.

### Area 2 — Project canvas & default aspect ratio

**Defect (this is the one you named exactly).** `CanvasPreset.forSourceDimensions()` exists,
is documented as *"the import-time canvas detection that replaced the previous hard-coded
`WIDE_16_9` project default"*, has **20 passing unit tests** — and had **zero production call
sites**. `createProjectFromClip` hard-coded `CanvasPreset.FREE` instead. The first-import
canvas was therefore never actually derived from the source.

This is the project's documented **bug class 1: "written but never rendered"**.

**Fix.** Both `InMemoryProjectRepository` and `RoomProjectRepository` now call
`CanvasPreset.forSourceDimensions(clip.width, clip.height)`. A canonical ratio snaps to its
preset — which additionally **pins the export frame** to that preset's `exportWidth ×
exportHeight`; anything non-canonical or unknown falls through to `FREE`, which tracks the
source 1:1.

**Also fixed: hard-coded landscape canvas defaults.** `EditorUiState.canvasPreset` is what
the preview reads *before* `loadProject()` overwrites it, so a 16:9 there meant the editor's
very first frame asserted landscape regardless of the import. `EditorUiState`, `VideoProject`,
`ProjectEntity` and `decodeCanvasPreset`'s fallback are all now `FREE` — the only value that
cannot be wrong about a source it has not seen. `MIGRATION_4_5`'s historical SQL is
deliberately **left alone**: it already ran on installed databases, and rewriting it would
only diverge from those devices.

### Area 3 — Preview container (Compose)

**Verified correct; no change needed — and that is the finding.**

- Exactly one `.aspectRatio(...)` in production, fed by the source's own ratio.
- `.heightIn(max = maxHeight).aspectRatio(targetRatio)` is the correct form: it fits the
  largest ratio-correct box (width-driven for landscape, height-driven for portrait). The
  `2026-09-03` comment at `EditorScreen.kt:2029-2042` documents why the previous
  `fillMaxWidth().aspectRatio(...)` was wrong.
- `RESIZE_MODE_FIT` for `FIT`/`MANUAL`, `RESIZE_MODE_ZOOM` for `FILL` — letterboxing is
  correct, and `FillMode.FIT` is the default.
- No hard-coded `9f/16f` reaches the live preview.
- `cropAspectRatio` defaults to `0f`, so the `cropScale` re-fit is a no-op unless the user
  actively picks a crop preset.

### Area 4 — Export & render graph

**Defect.** Both export entry points resolved the output frame like this:

```kotlin
val outW = if (preset.exportWidth > 0) preset.exportWidth else quality.widthPx
val outH = if (preset.exportHeight > 0) preset.exportHeight else quality.heightPx
```

**Every `ExportQuality` is defined landscape** (`HIGH_1080P` = 1920×1080). So for a `FREE`
project the frame came from the quality tier, not the source: a portrait project had its
caption burn-in and upscale grid laid out for a 1920×1080 frame, while a landscape project
happened to come out right. This is the same orientation asymmetry as a hard-coded 9:16,
mirrored — and it is the concrete meaning of your requirement *"export must respect correct
canvas dimensions"*.

**Fix.** New `ExportFrame.resolveOutputSize(preset, srcW, srcH, qW, qH)`:

| Source | Quality | Output |
|---|---|---|
| any sized preset | — | the preset's `exportWidth × exportHeight` |
| 1920×1080 | 1080p | 1920×1080 *(unchanged)* |
| 1080×1920 | 1080p | 1080×1920 *(was 1920×1080)* |
| 3840×2160 | 1080p | 1920×1080 |
| 2160×3840 | 1080p | 1080×1920 |
| 1920×804 (2.39:1) | 1080p | 1920×804 |
| unknown | 1080p | 1920×1080 |

Two properties, by construction: **orientation-symmetric** (the long side is the budget,
whichever axis it is on) and **budget-preserving** (the tier still caps resolution; a smaller
source is never upscaled). Landscape output is byte-identical to before.

---

## 3. A non-bug I nearly "fixed"

`EditorViewModel.setCanvasPreset` appeared to persist the canvas with `saveClips(...)` — the
**clips** table — while `canvasPreset` lives on the **projects** row. That would have been a
"written but never persisted" bug, and I had it queued as a fix.

It already calls `persistProjectMeta()` two lines further down (`EditorViewModel.kt:3840`).
My first read was truncated at line 3839.

The `saveClips` call above it is redundant (a preset change does not touch the clips table)
but harmless, so it was left alone to keep the diff surgical. **Read a persistence function
to its closing brace before calling it broken.**

---

## 4. Files changed

**New**

| File | Why |
|---|---|
| `model/VideoDimensions.kt` | Pure owner of the coded→display dimension swap + rotation normalisation + the decoded-frame probe |
| `test/.../model/VideoDimensionsTest.kt` | 30 tests |
| `test/.../export/ExportOutputSizeTest.kt` | 19 tests |

**Modified**

| File | Change |
|---|---|
| `video/VideoMetadataExtractor.kt` | Delegates to `VideoDimensions.resolve`; adds one best-effort `getFrameAtTime` probe |
| `video/ProjectRepository.kt` | `createProjectFromClip` → `forSourceDimensions` |
| `db/RoomProjectRepository.kt` | `createProjectFromClip` → `forSourceDimensions`; `decodeCanvasPreset` fallback → `FREE` |
| `db/ProjectEntity.kt` | Default `"FREE"` |
| `model/VideoProject.kt` | Default `FREE` |
| `model/EditorUiState.kt` | Default `FREE` |
| `export/ExportFrame.kt` | New `resolveOutputSize` + `evenSize` |
| `viewmodel/ExportDelegate.kt` | Uses `resolveOutputSize` |
| `viewmodel/EditorViewModel.kt` | `onExportClicked` uses `resolveOutputSize` |

---

## 5. Verification

```
1782 tests completed, 2 failed, 2 skipped
```

Baseline was `1733 completed, 2 failed, 2 skipped`. **+49 tests, 0 regressions.** The 2
failures are the pre-existing, asset-dependent pair that this project never chases:
`RealModelInstallTest` and `WhisperNativeSymbolsTest`.

| New test class | Tests | Result |
|---|---|---|
| `VideoDimensionsTest` | 30 | 0 failures, 0 errors |
| `ExportOutputSizeTest` | 19 | 0 failures, 0 errors |

The load-bearing cases: the probe disagreeing with metadata **in both directions**; degenerate
metadata staying degenerate instead of becoming a plausible pair; rotation normalisation of
`-90`/`450`; and the symmetry property *"swapping the source axes swaps the resolved output
axes"*, which makes a tier preference for either orientation unrepresentable.

`compileDebugKotlin` and `compileDebugUnitTestKotlin`: **BUILD SUCCESSFUL, zero warnings.**
`assembleDebug`: **OK** — `app/build/outputs/apk/debug/app-debug.apk`, ~93 MB, freshly built.

> Note: the overall Gradle run reports `BUILD FAILED` because of the 2 pre-existing test
> failures, but `assembleDebug` completes before them. Check the APK's mtime, not the exit code.

---

## 6. What to check on-device

The one thing static analysis cannot settle is which convention the offending file's
container uses. The probe now resolves that automatically at import, but if a landscape clip
*still* comes out portrait, the container is reporting dimensions that disagree with its own
decoded frames in a way no heuristic can arbitrate. In that case the file itself is
malformed, and the fastest confirmation is to compare, for the same file,
`METADATA_KEY_VIDEO_WIDTH`, `METADATA_KEY_VIDEO_HEIGHT`, `METADATA_KEY_VIDEO_ROTATION`, and
the width/height of one `getFrameAtTime` bitmap.
