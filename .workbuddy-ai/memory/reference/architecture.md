# AICreatorStudio — architecture reference

Long-form detail split out of `MEMORY.md` (2026-09-19) because that file was exceeding
the injection limit. `MEMORY.md` holds the rules and invariants; this file holds the
narrative behind them. Read a section when you are actually working in that area.

Per-round history: `../2026-09-16.md`, `../2026-09-17.md`, `../2026-09-19.md`.
Reusable Media3 procedure + verified 1.5.0 API facts: the `media3-export-parity` skill.

---

## Export architecture

- **Media3 Transformer 1.5.0** (H.264/AAC). **No FFmpeg anywhere.**
- Export renders **ALL segments** (since 2026-09-16): one `EditedMediaItemSequence`
  inside a `Composition`, laid end-to-end. Single-clip projects take the same path.
  `CompositionTimeline.outputMsForTimelineMs` maps editor → composition time.
- **The output frame is owned by `export/ExportFrame.kt`** — one pure object, because
  the `Presentation` and the wash overlays must agree on the size: `resolve`,
  `needsPresentation` (= `(w>0 && h>0) || multiClip`), `layoutFor`. **Why
  per-composition size but per-clip layout:** Media3 writes **one** video track, so
  every item must produce the same frame — `MuxerWrapper` throws
  `AppendTrackFormatException` on a width/height change. The engine resolves the size
  **once** from `effectiveSegments.first()`.
- `export/UpscalerExportEngine.kt` also renders all segments; `preset.targetScale` is
  applied via a `Presentation` effect (`Transformer` has **no** output-resolution
  API). Output = caller's target frame × targetScale, where the target frame is
  `ExportConfig.effectiveWidthPx/HeightPx` as resolved by `EditorViewModel`; source
  dims are only a fallback. Pure maths in `UpscaleOutputSize`; clamped at 8K,
  even-rounded. This engine does **not** bake speed / transitions / animations.
- **Multi-track audio (2026-09-16):** `RenderGraphResult.Success.audioSegments` carries
  audio-only clips. One `EditedMediaItemSequence` **per** audio clip, each
  `[gap, item]`, because items inside one sequence must not overlap while separate
  sequences are mixed. **UNITS TRAP:**
  `EditedMediaItemSequence.Builder.addGap(long)` is documented as milliseconds but
  forwards to `setDurationUs`, which is **microseconds** and asserts `> 0` — multiply
  by 1000, never pass 0. `addGap` is legal only in audio-only sequences. Audio is
  clamped to the video length.
- **Preview still plays only the first/selected clip**, so per-clip speed/selection on
  clip #2+ is preview-invisible unless selected.
- Whisper is real: whisper.cpp v1.7.6 vendored + built via NDK/CMake into
  `libwhisper.so`, real token-level word timings.
- Undo/redo = bounded 20-entry whole-`EditorUiState` snapshot stack in
  `EditorViewModel` (`historyLimit = 20`); restore goes through
  `restoreDocumentFrom` so live/volatile state is never clobbered.
- `ColorGradingMath.fullExportMatrix` composes B/C/S + **every**
  `VideoEffect.toColorMatrix()` preset + the full Lumetri chain into ONE 4×5 matrix.
  Before assuming a preset is "preview-only", grep `toColorMatrix()` — the factory may
  already serve it.

## Transitions + clip motion (since 2026-09-16)

Preview **and** export both render transitions through `model/TransitionGeometry.kt`
(pure Kotlin, no GL types) plus `TransitionEngine.poseForClipLocal(...)`, so the two
cannot drift. Authoring reference canvas is **430 × 280 dp**. A `null` config appends
nothing, so untouched projects export byte-identically.

`model/ClipMotion.kt` owns clip animations: `poseAt` (11 presets), `progressAt`
(**clamped, never wrapped**), `fadesClip`, and
`compose(transition, motion, clipOpacity, transformScale, panXFraction, panYFraction)`.

`compose` is load-bearing: it folds everything into the transition's `TransitionPose`
**component-wise** (scales multiply, translations/rotations add, alphas multiply,
`blurDp`/`flashWhite`/`flashBlack` pass through) so the export builds **one** matrix.
A second matrix effect would multiply instead, silently scaling a transition's travel
by the animation's zoom. `offsetX`/`offsetY` are **canvas fractions** (`-0.5f..0.5f`,
clamped by `EditorViewModel`), converted via `panX * REFERENCE_WIDTH_DP`. Only
`rotation` and `flipH`/`flipV` stay in `ScaleAndRotateTransformation` — a **uniform**
scale commutes with both, so export == preview. `ClipMotionEffects` is appended
**after** the `Presentation`, so the pose lives in canvas space and the flip sits
*inside* the pan rather than mirroring it.

The export side is `export/ClipMotionEffects.kt`. `PoseTimeline` composes everything;
`build()` returns `emptyList()` only when the segment has no transition, no preset
**and** no opacity. The fade wash fires for a fading transition, a fading preset, **or**
a reduced opacity.

**The wash is three passes inside one `OverlayEffect`, and the order is load-bearing**
(first = bottom-most): fade (the **canvas** colour, via
`CanvasLetterbox.fadeColorArgb`) → explicit black flash → explicit white flash. This
matches the preview's intended paint order (the clip at its `graphicsLayer` alpha over
the canvas, then a black `Box`, then a white `Box` — *which do not actually exist in
`EditorScreen`; see "Open issues"*). The fade washes toward the **canvas** colour, not
black, because the preview composites the clip over the letterbox `Box`;
`CanvasLetterbox.fadeColorArgb` is the **single** place that decides it and a unit test
asserts the wash and the plate agree. Both **flash** passes deliberately get
`clipOpacity = 1f`; a non-finite `clipOpacity` reads as `1f`.

Splitting the pass fixed an under-darkening: the old code folded `1 - alpha` and
`flashBlack` into one pass with `max`, but the preview *composes* them, so for
`GLITCH` (the one built-in setting both) the correct residual is `0.92 * 0.88 =
0.8096`, not `1 - 0.12 = 0.88`.

**The wash size is observed, never passed in (2026-09-17).** Each
`ClipMotionWashOverlay` reads the frame size from `configure(Size)`; this fixed a real
bug — with no `Presentation` and `rotationDegrees != 0`, step 1's
`ScaleAndRotateTransformation` grows the frame to the rotated bounding box, which
`resolve` does not report, so the old code passed `0` and suppressed every wash:
**a rotated clip's fade silently became a cut**. `frameIsKnown` survives only as
`hintWidthPx`/`hintHeightPx` for `CanvasBackgroundOverlay`. Regression-tested in
`RenderGraphBuilderTest`.

**The blur's height is still passed in, and that asymmetry is correct:**
`ClipMotionEffects.build` keeps `outputHeightPx` for exactly one job — turning a pose's
dp blur into a pixel sigma (`blurDp * (outputHeightPx / 430)`).
`SeparableConvolution` exposes no `configure` hook, so a convolution cannot observe its
frame. `blurReferenceHeightPx` decides it (pure, pinned by `RenderGraphBuilderTest`)
and the value is **exact**, not an approximation, because "no `Presentation`" means
FREE canvas + one clip and `CanvasLetterbox.rotatedBounds` gives the exact box
(`rotationDegrees` is an `Int` restricted to 0/90/180/270).

*Reusable lessons:* **before writing new geometry, grep for the geometry you already
have**; and **a documented approximation may not be an approximation — check whether
the value is knowable before accepting it as a limitation.**

## The canvas background is static in canvas space (2026-09-16)

The rule comes from the **preview**: `EditorScreen` puts the letterbox colour on the
**outer canvas `Box`** and the whole per-clip pose on the **inner foreground
`PlayerView`** (`graphicsLayer`). So the background is **static in canvas space and the
video moves over it.** Any overlay that must stay canvas-aligned while the video is
posed has to go **after** the pose and recompute its hole per frame — a pose is rigid,
so putting the plate *before* it moves the plate's outer bounds too
(`ClipMotion.ZOOM_IN_START_SCALE` is `0.45` and a push translates a full frame width,
so the plate would vacate most of the canvas). Two more placement constraints: **after**
the `Presentation` (the bars do not exist before it) and **after** the washes.

Cost rules for the full-frame mask: a per-pixel inverse transform is unaffordable in
ART (2.1 M pixels × ~15 float ops = 30–90 ms/frame at 1080p, no vectorisation) — the
silhouette is a **convex quadrilateral**, so use a **scanline rasteriser**
(O(height), ~1–2 ms/frame, cached on the pose). **Keep the mask binary** and use the
pixel-centre rule with the half-open ("top-left") fill rule on both axes; anti-aliasing
is *worse* here because the video's own edge is rasterised with one sample per pixel.

Two traps that cost real time: **a non-finite pose input fails invisibly** (a NaN
corner makes a containment test report "outside" everywhere and the row scan report no
crossing, so the plate comes out **fully opaque** and hides the video — sanitise every
pose input to its identity value), and **`TextureOverlay.setScale` must be derived from
the *bitmap's* dimensions, not the frame's.**

`CanvasLetterbox.videoRect` no longer means "are there bars"; it reports the un-posed
rect, and "are there bars" moved to `needsPlate`, which tests the **posed** silhouette.
`FillMode.FILL` is no longer rejected for the same reason.

**Still not baked: `CanvasBackground.Blur`** (needs a second video sequence +
`VideoCompositor`, i.e. a timeline-model change). The only canvas-background gap.

## Custom shader effects

`grain` is the project's **only** hand-written shader; `export/GrainEffect.kt` +
`model/GrainSignal.kt`. The full recipe and the silent-failure traps
(`setFloatsUniformIfPresent` is a no-op for an undeclared name; `release()` must delete
the program itself; guard `highp` with `#ifdef GL_FRAGMENT_PRECISION_HIGH`; seed from
the presentation timestamp, not a frame counter) are in the `media3-export-parity`
skill §7. The load-bearing project fact: **the shader cannot be tested, so it is a
hand-transcription of a pure, unit-tested helper — say so in *both* files, because
nothing enforces the correspondence.**

## Concurrency

`AiJobEngine.submit` had a silent-cancel race: `scope.launch { ... }` dispatches
**immediately**, so the block could already be running before the line that registered
it in `activeJobs` executed — and `cancel()` does
`activeJobs.remove(id) ?: return false`, so the cancel was **silently dropped**.

- **Register a cancellable job before it can run:** `CoroutineStart.LAZY` → store it →
  publish the observable state → `start()`.
- **A `CompletableDeferred` awaited across a LAZY job must be settled from the job's
  completion** (`job.invokeOnCompletion { if (!d.isCompleted) d.cancel(...) }`), or
  cancelling before `start()` means the body never runs and `await()` hangs forever.
- `kotlinx.coroutines.CancellationException` is a JVM typealias for
  `java.util.concurrent.CancellationException`, which has **no**
  `(String, Throwable)` constructor — use `initCause`.
- **When a test flakes, suspect the code before the test.** The
  `cancelTransitionsToCancelledNotError` "flake" was detecting this real race. It is
  still intermittently flaky under full-suite load while passing in isolation.

## Matting is EXPORT-ONLY via a CPU pre-render (2026-09-17)

`AiExportHooks.prepareSourceUri` runs for **every** segment before the Transformer,
blending `mix(bg, fg, a)` per frame from the sidecar alpha PNGs (`compositeMatting` →
`VideoFramePipeline.transcode`; audio copied sample-for-sample; `threshold` = soft knee,
not a hard cut). The live preview shows only a "BG Remove ready" chip — Compose cannot
sample `TextureView`/`SurfaceView` pixels. `PersonMatteEffect` is an unused stub kept as
a documented landing site; `EditorViewModel.previewMattingReady` has no collector.
Matting's BLUR background mode **is** exported — do not confuse it with the *canvas
letterbox* `CanvasBackground.Blur`.

`MattingSettings.featherPx` is generation-time only: wired through `AiToolOptions` →
`AiMattingPanel` → `MediaPipeMattingEngine` (`coerceIn(0f, 10f)`), but no UI slider
exists so it stays at the default `2f`. The feather is baked into the sidecar PNGs —
changing it requires re-running the tool.

## Dead code (no call sites in `main`)

`EditorVideoCanvas.kt`, `BottomToolbar.kt`, `EditorBottomDock.kt`,
`ViralStylePanel.kt`, `WaveformGenerator.kt`. `ToolPanel.CROP` is orphaned (no dock
entry). `ColorGradingMath.vignetteFactor` has zero callers.

## Open issues / known gaps (not yet fixed)

- **Preview/export BCS maths disagree (found 2026-09-19).**
  `EffectPresets.bcsMatrix` (preview) uses `scale = contrast`, `offset =
  (brightness-1)*128`; `ColorGradingMath.fullExportMatrix` (export) uses `k = c*b*s`,
  `off = 127.5*(1-c)*b`. So brightness/contrast/saturation are *different* on screen and
  in the MP4. Fix by having the preview call `fullExportMatrix`, or by making
  `bcsMatrix` delegate to the same pure helper.
- **REMAINING "I move it and nothing happens" bugs found by the 2026-09-19 consumer
  audit** (the "every panel control has a consumer in both renderers" claim was false a
  second time). None are fixed yet; all need a device test.
  - **Transitions never render in the preview** — `setClipTransition` writes them, the
    export renders them via `ClipMotionEffects`, and `EditorScreen.kt` contains **no**
    `TransitionEngine` call at all (the composable that would have done it,
    `EditorVideoCanvas.kt`, is orphaned). Picking a transition changes the panel and the
    MP4 but nothing on screen. Also: the black/white flash `Box`es that
    `ClipMotionEffects.kt` assumes exist **do not exist** — that comment describes a
    layer that was never there.
  - **Base rotation is preview-only.** `VideoClip.rotation` has **no writer**;
    `onRotationChanged` / `rotate90Degrees` write only `uiState.rotation`, which the
    preview reads but the export does not (it reads `clip.rotation` via
    `RenderGraphBuilder`). So the rotate-90 button turns the preview and the exported
    file comes out unrotated.
  - **A normal export ignores the AI upscaler entirely** — `VideoExportEngine.kt` has
    zero references to `upscale`/CAS; only `UpscalerExportEngine` applies it.
  - **Trim is not applied to the preview's video** — no `MediaItem.ClippingConfiguration`
    anywhere in `EditorScreen.kt`; the media item is the full `uri`.
  - `animT` in `EditorScreen.kt` uses a raw `%` instead of clamping to the clip window,
    so the animation pose restarts for any position outside `[0, clipDuration]`.
  - `uiState.selectedAnimation` has no reader (the preview reads `activeClip.animation`).
  - `cropPreset` and `ocrResult` are write-only in both renderers (only
    `cropAspectRatio` is consumed).
  - `EditorVideoCanvas.kt` is orphaned **and** uses the SurfaceView + `saveLayer` pattern
    `MEMORY.md` documents as rendering black — delete it rather than resurrect it.
- **FIXED 2026-09-19 (round 3):** audio fades were export-only (`StemClip`'s only
  construction site omitted `fadeInMs`/`fadeOutMs`, and the `LaunchedEffect` key omitted
  them too).
- **FIXED 2026-09-19 (round 3):** the Layer 2.5 colour overlay was an **empty** `Box`
  with a `graphicsLayer { renderEffect }` — a Compose `renderEffect` filters *its own
  node's* layer content, so with no children it filtered nothing. Removed.
- `viewmodel/AdjustmentDelegate.kt` — all four setters have no UI call sites (the UI
  calls the duplicate inline methods on `EditorViewModel`) and its `mutateClip` omits
  `persistClips()`. Inert half-finished refactor.
- `export/ClipMotionEffects.kt` — the `ClipMotionWashOverlay` comment says `(0,0)`
  anchors draw from the frame's "top-left"; `OverlaySettings` defines `(0,0)` as the
  **centre**. Result is correct (the bitmap is frame-sized); doc-only.
- `ai/PersonMatteEffect.kt` — its `PassThroughShaderProgram.drawFrame` is empty, but
  `BaseGlShaderProgram.queueInputFrame` clears the output FBO first, so attaching it
  would **blank** the frame rather than pass it through. Never instantiated, so latent.
- **Two `EditorScreen.kt` files exist.** The compiled one is
  `app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt`; the
  repo-root one (mtime 2026-09-15) is a stale near-duplicate in no source set. **Check
  the mtime before editing** — they are easy to confuse.
- Prior phase reports at the project root: `B3_FINAL_REPORT.md`, `B7_FINAL_REPORT.md`,
  `AUDIT_ROADMAP_GAP_ANALYSIS.md`.
- **Not a git repository** — there is no `git status` / `git diff` safety net here.

## Tooling hazards

- **Batching multiple `Edit` calls to the SAME file in one message silently drops
  some**, while reporting success (hit twice on 2026-09-16; the file was byte-identical
  on disk). **One `Edit` per file per message, then grep-verify before building.**
  Different files in one message appear safe.
- **After shipping, grep for the words that described the *gap*** (`preview-only`,
  `not implemented`, `has no consumer`, `known limitation`). Stale gap comments survive
  the fix every single time.
- **`javap` / classpath needs `;` on Windows** — a `:` gives a misleading "class not
  found". Nested classes need `$` and quoting (`'pkg.Outer$Inner'`). Inspect the real
  artifact under `~/.gradle/caches/**/transformed/media3-*-1.5.0/jars/classes.jar`;
  read the `-sources.jar` for behaviour, not just signatures.
- **Read Media3's own sources from the Gradle cache rather than reasoning about them.**
  They are one `jar xf` away:
  `~/.gradle/caches/modules-2/files-2.1/androidx.media3/<module>/1.5.0/*/<module>-1.5.0-sources.jar`.
  Every non-obvious Media3 fact in these notes came from there; the guesses were all
  wrong at least once.

---

## The preview effect list — full detail (2026-09-19)

`EditorScreen.kt`'s `setVideoEffects` list is the **single place** where preview/export
parity is decided, and it fails **silently and totally**: an effect missing from that one
list is invisible on screen while still working in the exported file. It is a few lines
inside a 4900-line Compose file with no test covering it — and bug class #4 proves the
list can be *complete and correct* and still not reach the screen.

- **Bug class #1's "grep for the consumers" rule does not catch this.** That rule finds a
  field with *no* consumer; it does not find a field whose consumer exists but was
  dropped from a *list*. **When several features claim "preview == export" in their
  KDocs, diff the two effect lists against each other** — that check would have caught
  both regressions below, which were found only by auditing the KDocs against the code.
- Fixed: the list held **only** the grade effect, so `SharpnessEffect` / `VignetteOverlay`
  / `GrainEffect` were never instantiated live. Their KDocs and `ColorGradingMath`'s class
  KDoc all asserted "preview == export", and `2026-09-17.md` records the preview **did**
  have `grainEffect` + `effectSignature` bit 8 → a regression, not a design choice.
- Fixed: the blurred canvas background (Layer 1) had regressed to `PlayerView(ctx)`
  (`SurfaceView`); restored the `R.layout.player_view_texture` inflate. `Modifier.blur` is
  still a no-op below API 31 whatever the surface type.
- Fixed (2026-09-19): the grade pass is now **unconditionally** present, so a slider drag
  never changes list membership — see bug class #4's corollary.

**The pattern to reuse for the preview pipeline:** one long-lived `remember`ed instance
per effect, with its mutable `@Volatile` field rewritten per recomposition (Media3
re-queries per frame, so a *value* change needs no rebuild); membership decided by the
**same pure predicates the export uses** (`SharpnessKernel.isActive` /
`VignettePlate.isActive` / `GrainSignal.isActive`) and folded into `effectSignature`
bits, so a membership change rebuilds while a slider drag does not. Order mirrors the
export: grade → sharpness → vignette → grain. A `TextureOverlay` is **not** an `Effect` —
wrap it once in a long-lived `OverlayEffect`. **And every `setVideoEffects` call site must
log on failure** — a bare `runCatching` here swallows the exact symptom the user reports.

**Audited and found correct** (do not re-audit): `SharpnessEffect`, `VignetteOverlay`,
`GrainEffect`, `CanvasBackgroundOverlay`, `ClipMotionMatrixEffect`,
`ClipMotionBlurEffect`, `ClipMotionWashOverlay`, `SubtitleWindowOverlay` — no
array-length mismatch against a Media3 `checkState`/`checkArgument`, correct `configure`
sizes, overlay scales derived from the *bitmap* not the frame, no undeclared uniform,
and every `release()` either calls `super` or deletes its GL program.
`ClipMotionMatrixEffect` returns `FloatArray(16)`, satisfying `DefaultShaderProgram`'s
shared 16-float assertion.

## Preview colour, the final design: the TextureView's own layer paint (2026-09-19, round 7)

**The Media3 `setVideoEffects` route was abandoned for the preview.** Everything above
about the effect list and the paused-state seek describes a design that is **no longer in
the code** — the effect instances, the `effectSignature` bits, the `setVideoEffects` call
and the seek round-trip were all deleted in round 6. It is kept here because the Media3
facts are still true and still matter for the export, and because the seek trace is the
right answer for any screen whose surface *is* ready before `prepare()`.

### Why the whole route had to go

Priming `videoEffects` at construction (round 4) made the renderer build its GL sink at the
first `onEnabled` — and killed playback. See `../2026-09-19.md` (round 6) and `MEMORY.md`
bug class #4 for the full trace. The one-line summary: **`onEnabled` runs during
`prepare()`, this screen has no display surface yet, and `setOutput()` never forwards a
late-arriving surface to an existing sink** — so the sink is created blind, no frame is ever
produced, the player sits in BUFFERING forever, and a Play button written as
`player.play()` guarded by `isPlaying` looks dead.

The two `setOutputSurfaceInfo` call sites in `MediaCodecVideoRenderer` are line 752
(inside `onEnabled`, guarded by `displaySurface != null`) and line 963
(`MSG_SET_VIDEO_OUTPUT_RESOLUTION`). There is no third path.

### The replacement: the platform View's own paint, not a Compose layer

Colour is applied **natively to the `TextureView`** that the `PlayerView` hosts, using the
matrix that was already being computed (it already folds BCS + presets + Lumetri + the
upscaler CAS):

```kotlin
// Non-state holder: publishing a View must not recompose, and the AndroidView
// factory must run exactly once.
val foregroundPlayerView = remember { arrayOfNulls<PlayerView>(1) }

val previewColourPaint = remember(colorMatrix) {
    if (ColorGradingMath.isIdentityMatrix(colorMatrix.values)) {
        null
    } else {
        android.graphics.Paint().apply {
            colorFilter =
                android.graphics.ColorMatrixColorFilter(colorMatrix.values)
        }
    }
}

// …inside the AndroidView factory: foregroundPlayerView[0] = inflated

SideEffect {
    val textureView =
        foregroundPlayerView[0]?.getVideoSurfaceView() as? android.view.TextureView
    if (textureView != null) {
        textureView.setLayerType(
            android.view.View.LAYER_TYPE_HARDWARE,
            previewColourPaint
        )
    }
}
```

**Verified from the platform source** (`C:/android/sources/android-37.0/android/view/TextureView.java`,
lines 355–380 and 420–445):

```java
/**
 * The layer type of a TextureView is ignored since a TextureView is always
 * considered to act as a hardware layer. The optional paint supplied to this
 * method will however be taken into account when rendering the content of
 * this TextureView.
 */
@Override public void setLayerType(int layerType, @Nullable Paint paint) {
    setLayerPaint(paint);
}
@Override public void setLayerPaint(@Nullable Paint paint) {
    if (paint != mLayerPaint) { mLayerPaint = paint; invalidate(); }
}
@Override public int getLayerType() { return LAYER_TYPE_HARDWARE; }
```

and in `draw()`: `mLayer.setLayerPaint(mLayerPaint); recordingCanvas.drawTextureLayer(layer);`

So **no offscreen layer is allocated** (the layer type is ignored — a TextureView is always a
hardware layer) and the paint is applied **directly to the content**, on the render path. This
is a first-class documented platform mechanism, not a workaround.

Two supporting facts, both verified rather than assumed:

- `androidx.media3.ui.PlayerView.getVideoSurfaceView()` is **public**
  (`PlayerView.java:1354`, `return surfaceView;`), and the surface view is created in the
  `PlayerView` **constructor** (`SURFACE_TYPE_TEXTURE_VIEW = 2` at line 288 →
  `surfaceView = new TextureView(context)` at 452–453). The reference is therefore valid
  immediately after inflation — no `onSizeChanged`/`frameIsKnown` dance is needed.
- `as? TextureView` is a **guard, not a formality**: on a SurfaceView,
  `setLayerType(LAYER_TYPE_HARDWARE, paint)` would allocate a *real* offscreen hardware layer
  instead of merely setting a paint — expensive and pointless.

No new imports were needed: `SideEffect` was added
(`androidx.compose.runtime.SideEffect`), and six became unused and were removed
(`drawWithCache`, `geometry.Rect`, `graphics.ColorFilter`, `graphics.Paint`,
`asComposeRenderEffect`, `drawscope.drawIntoCanvas`).

### The Compose `saveLayer` that this replaced — a documented trap

Round 6's design put the filter in a Compose layer on the foreground `AndroidView`:

```kotlin
// ⚠️ DO NOT RESTORE. This killed the Play button.
.drawWithCache {
    val layerPaint =
        if (ColorGradingMath.isIdentityMatrix(colorMatrix.values)) null
        else Paint().apply { colorFilter = ColorFilter.colorMatrix(colorMatrix) }
    onDrawWithContent {
        if (layerPaint == null) drawContent()
        else drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layerPaint)
            drawContent()
            canvas.restore()
        }
    }
}
```

The user's device report bisected it exactly: **playback and Play were fine until a slider
left its default, and died the instant it did** — i.e. the instant the `saveLayer` branch
activated. `drawContent()` from inside that node's own draw callback is a **re-entrant draw**
of the node's content while the hosted TextureView is compositing video; the resulting draw
loop stops frames being presented, so `onIsPlayingChanged` never fires and a Play button
written as `player.play()` guarded by `isPlaying` looks completely dead.

Two general rules came out of it:

- **Never put a Compose draw modifier on a node that hosts a platform View which composites
  its own frames.** Use the platform View's own paint instead.
- **A "skip it at the default value" guard is not a safety valve — it is a switch the user
  flips on their first action**, converting a maybe-broken feature into a definitely-broken
  one at exactly the moment they touch it.

Note that the old objection to this pattern ("against a SurfaceView it renders black", the
2026-08-30 bug) was *correct for its surface type* and is still the reason bug class #2
exists — it just was not the failure here. The current failure was the re-entrancy, not the
surface type.

### The matrix needs NO rescaling — verified, not assumed

This is the bug-class-#3 trap in a new costume, so it was checked against the Compose
sources (`ui-graphics-android:1.7.6`) rather than reasoned about:

```kotlin
// AndroidColorFilter.android.kt
internal actual fun actualColorMatrixColorFilter(colorMatrix: ColorMatrix): NativeColorFilter =
    AndroidColorMatrixColorFilter(colorMatrix.values)   // no scaling
```

and Compose's `ColorMatrix` KDoc states the result "has each channel clamped to the
**0 to 255** range", with an invert example using `255` in the translation column.

**So Compose's `ColorMatrix` convention is `android.graphics`'s: offsets 0..255,
row-major — not Media3's 0..1.** The project's `colorMatrix` is already in that convention
(proved three ways: it is fed to `android.graphics.ColorMatrix(...)` at `EditorScreen.kt`
~1755; `ColorGradingMath.fullExportMatrix` computes `off = 127.5f * (1f - c) * b`; and
`GradeRgbMatrix.toMedia3RgbMatrix4x4` **divides the offset column by 255** to reach
Media3). So the same `colorMatrix` object is handed to `ColorFilter.colorMatrix`
**unchanged**. Dividing by 255 here would have made every brightness/contrast offset 255×
too weak.

The Compose KDoc even names this exact idiom — "often used as input for
`ColorFilter.colorMatrix` and applied at draw time through `Paint.colorFilter`".

**Three conventions coexist in this one feature. Keep them straight:**

| Consumer | Layout | Offset range |
|---|---|---|
| `android.graphics.ColorMatrix`, Compose `ColorMatrix` / `ColorFilter.colorMatrix` | 4×5 row-major, 20 floats | **0..255** |
| Media3 `RgbMatrix` | 4×4 column-major, **16** floats | **0..1** |

Convert at exactly one boundary (`toMedia3RgbMatrix4x4`, which is unit-tested), and
nowhere else.

### Why the native path is safe

- **No offscreen layer.** `setLayerType` ignores the layer type on a TextureView, so this is
  not the "real hardware layer" cost that the same call would incur on a SurfaceView.
- **No Compose draw involvement.** The paint is applied by the platform View when it renders
  its content, so there is no re-entrant `drawContent()`, no draw loop, and **no path at all
  from the colour to the player, the Surface, or the touch/event loop**. That is precisely
  what the user asked for.
- **No API gate.** Only `RenderEffect` is API 31+; a `Paint.colorFilter` works from API 24
  (`minSdk` is 24). The old note claiming the previous approach was "dead on API < 31" was
  wrong, and is now moot.
- **No per-frame allocation.** `previewColourPaint` is built in `remember(colorMatrix)`, so it
  is rebuilt only when the grade actually changes, and `setLayerPaint` no-ops on an identical
  instance (`if (paint != mLayerPaint)`) — the ~30 Hz position-poller recompositions cost one
  field comparison, not a repaint.
- **No seek, no player state, no GL pipeline, no third-party lifetime gate.** Dragging a
  slider only re-assigns a `Paint` on a platform View.
- **It requires the TextureView surface type** (`R.layout.player_view_texture`, switched
  2026-09-01 for the matting path). On the default SurfaceView the `as? TextureView` guard
  makes it a silent no-op — safe, but colourless. **Do not switch the surface type back.**

### What this costs

`sharpness`, `vignette` and `grain` are now **export-only**. They never reached the screen
through the old code either — the sink never existed, so the list was inert — and a colour
matrix cannot express them regardless: a separable convolution and a spatial vignette are
not per-pixel colour transforms.

The blurred canvas background (Layer 1) is also **not** graded in the preview, while the
export grades the whole composited frame. A small, known divergence.

### Not verified

The change is compile-verified only. Whether the layer paint makes the preview respond on a
given device while keeping Play alive needs a device test — GL and View compositing cannot be
unit-tested here. `testDebugUnitTest` is byte-identical to baseline (1189 tests, the same 5
pre-existing Whisper/asset failures, 2 skipped), so the compile is clean and nothing regressed
at the unit-test level.
