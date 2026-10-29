# The four recurring bug classes

These are the expensive ones. Each has been hit repeatedly in this codebase. Read this before
debugging "the feature does nothing".

---

## #1 — Written but never rendered

A model field and a ViewModel setter persist the value, but **no renderer reads it**. The slider
moves, the DB stores it, nothing changes on screen or in the file.

**Rule: verify a feature by grepping for its consumers, never by reading its KDoc.**

```bash
grep -rn "<fieldName>" --include=*.kt app/src
```

Count the write sites and the read sites. Four outcomes:

| Writes | Preview reads | Export reads | Diagnosis |
|---|---|---|---|
| yes | no | no | Write-only everywhere. Not a parity bug — a missing feature. |
| yes | yes | no | The classic parity bug. The preview is the spec. |
| yes | no | yes | Export-only; check the preview is not silently inert. |
| yes | yes | yes | The field is wired. Look elsewhere. |

**Nastiest variant:** a parameter declared *only to be forwarded* and then omitted at the call site.
`buildEditedMediaItem` declared `outputWidthPx`/`outputHeightPx` and `canvasBackground`, but its only
caller omitted them, so every fade, flash and opacity wash was silently skipped while the arithmetic
tests stayed green. It is now wired (`VideoExportEngine.kt` ~479-489, `CanvasBackgroundOverlay`
added).

**Second variant that the grep rule does NOT catch:** a field dropped from an **effect list**. When
several features claim "preview == export" in their KDocs, **diff the two renderers' effect lists**
against each other.

**Third variant (2026-09-24) — a new value on a *string-keyed* axis must be added to EVERY `when`
keyed on that string.** The subtitle template engine keys whole axes off JSON type strings, and the
same string is re-`when`-ed in several independent places. Adding `wave_accent` with
`wordHighlight.type = "waveAccent"` to the `progressBar` spec mapping was not enough:
`JsonTemplateMapper.mapMotion` has **two** separate `when (hl)` branches — one deriving `kind`, one
deriving `reveal` — and `withHighlightOverride` has a **third**. The template compiled, parsed,
resolved and mapped a WAVE bar, while `motionKind` silently came out `NONE`, i.e. **no word was ever
highlighted**. Only a test that asserts the *resolved engine field* caught it; the build and the
parser tests were all green.

> **Rule: when you add a value to a string-keyed axis, grep the literal string and update every
> `when`. Then assert the resolved engine field in a test — never the intermediate JSON.**

The same shape applies to `background.type` (`mapBackground`, `withBackgroundOverride`) and to the
`SubtitleStudioPanel` manual pickers' local string↔kind lists.

---

## #2 — SurfaceView defeats every View-level effect

`PlayerView(context)` defaults to `surface_type="surface_view"`, whose video is composited **outside
the View raster**. So `Modifier.blur`, `graphicsLayer { renderEffect }` and
`drawContent`/`saveLayer` operate on a transparent hole. Hit three times: colour tint, preview grade,
blur background.

- A preview layer needing a View-level effect must inflate `R.layout.player_view_texture`
  (**TextureView**). This project already does — **do not switch it back.**
- `Modifier.blur` is also a no-op below API 31 (this app's `minSdk` is 24).
- **Surface type is irrelevant to `ExoPlayer.setVideoEffects`** — Media3 applies those in the
  renderer, before the frame reaches the Surface.

The 2026-08-30 "preview is black while audio plays" bug was exactly this reasoning applied against a
SurfaceView.

---

## #3 — A third-party API's *value shape* assumed, not verified

`GradeRgbMatrix.kt` (the only Media3 `RgbMatrix`, shared by preview and export) returned the app's
raw **4×5 row-major** `ColorMatrix` — 20 floats, offsets 0..255. Media3 wants **4×4 column-major,
exactly 16 floats, offsets 0..1**, and does not convert:
`DefaultShaderProgram.updateMatrixCache` asserts `length == 16` from `drawFrame` **outside** its
`try`, so the `IllegalStateException` reaches `VideoFrameProcessingTaskExecutor`
(`shouldCancelTasks = true`) and **cancels the entire video pipeline**.

Fixed by `toMedia3RgbMatrix4x4` (pure, unit-tested).

> **Rule: verify the *value shape* of a third-party API, not just the method signature.** Treat a
> confident comment about third-party behaviour as a guess until it has a citation or a test. The
> false claim here sat in two KDoc blocks and survived because every test asserted matrix
> *arithmetic*, not the effect's *shape*.

**Offset convention — two of them, do not mix:**

| API | Shape | Offsets |
|---|---|---|
| Compose / `android.graphics` `ColorMatrix` | 4×5 **row-major**, 20 floats | 0..255 |
| Media3 `RgbMatrix` | 4×4 **column-major**, 16 floats | 0..1 |

Convert at exactly one boundary (`GradeRgbMatrix#toMedia3RgbMatrix4x4`) and nowhere else.

---

## #4 — A third-party one-shot *lifetime* gate, silent and total

`MediaCodecVideoRenderer` builds its GL pipeline (`PlaybackVideoGraphWrapper` = `videoSink`) inside
`onEnabled`, and **only when `videoEffects != null` at that exact instant**. `videoSink` is assigned
in exactly one place — the constructor — and nothing creates it later; `setVideoEffects` forwards to
the sink only `if (videoSink != null)`.

> **If the first `onEnabled` runs before anyone has called `setVideoEffects`, the sink is never
> created and every effect is silently discarded for the whole life of the player.**

Symptom: a *working* video with *dead* effects. The decoder renders straight to `displaySurface`
because `videoSink == null`. The sliders and the matrix are correct, not one pixel changes — and it
is indistinguishable from bug class #1 unless you read the renderer.

> ⚠️ **Do NOT "fix" this by calling `setVideoEffects` at construction time.** That was tried on
> 2026-09-19 and it **KILLED PLAYBACK** — the video never started and Play/Pause was dead. The gate
> has a **second precondition**: `onEnabled` only tells the new sink where to draw when
> `displaySurface != null` at that same instant (`MediaCodecVideoRenderer:752`). This screen inflates
> its `PlayerView` inside a Compose `AndroidView` factory, so the surface attaches *after*
> `prepare()` — the sink is created **blind**, produces no frames, and the player sits in BUFFERING
> forever (`isPlaying` never turns true, so the Play button, guarded by `isPlaying`, looks dead).

> **Rule: a one-shot initialisation gate can have a *second* precondition, and satisfying the gate
> without it turns a silent no-op into a total failure.** Enumerate everything else that must be true
> at that instant; if that instant can never occur, stop and pick a different mechanism.

### Corollaries worth keeping

- **Membership ≠ value.** A change of list *membership* triggers re-registration and a graph rebuild
  (which flushes); a change of a long-lived instance's *value* needs no rebuild because
  `RgbMatrix.getMatrix()` is re-queried per frame. So the grade pass is **always** in the preview
  effect list (identity matrix included).
- **Find the API's "do I re-read your state?" decision.** It is often an `equals` cache — then a
  long-lived mutable instance is permanently equal to itself and the slider is dead from frame 2 with
  no error. Verified correct for all four preview effects (**do not re-audit**): `RgbMatrix` every
  frame; `SeparableConvolutionShaderProgram` compares `getConvolution(...)` (why
  `SharpeningFunction` **must** override `equals` by `weight`); `TextureOverlay` re-queries
  `getTextureId` per frame; `GrainEffect` sets uniforms in `drawFrame`. Same shape in
  `DefaultVideoFrameProcessor.configureEffects`. `AudioMixerPlayer` has no gate.
- **Check whether the app can even see the failure.** The preview had **no `onPlayerError`
  listener**, so a dead GL pipeline only froze the picture and made two device tests inconclusive.
  There is now a `DisposableEffect(exoPlayer)` logging `onPlayerError` (`errorCodeName` + message)
  under tag `EditorScreen`.

---

## How the preview renders colour now (2026-09-19, round 7) — do not regress this

`EditorScreen.kt` contains **no live `setVideoEffects` call** — comments only, deliberately (see
class #4). Colour is applied **natively to the `TextureView`'s own layer paint**:

```kotlin
SideEffect {
    val textureView =
        foregroundPlayerView[0]?.getVideoSurfaceView() as? android.view.TextureView
    if (textureView != null) {
        textureView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, previewColourPaint)
    }
}
```

`previewColourPaint` = `remember(colorMatrix) { Paint().apply { colorFilter =
ColorMatrixColorFilter(colorMatrix.values) } }` (or `null` at identity). `foregroundPlayerView` is a
plain `remember { arrayOfNulls<PlayerView>(1) }` holder — **not** Compose state, so publishing a View
triggers no recomposition.

- ⚠️ **Never put a Compose draw modifier on the `AndroidView`'s chain.** The round-6 `saveLayer`
  (`drawWithCache` + `Paint.colorFilter` + `drawContent()` inside `drawIntoCanvas`) **broke the Play
  button**: a re-entrant draw of the node's own content from inside that node's draw callback, while
  the hosted TextureView composites video — frames stop being presented, so `isPlaying` never turns
  true. The modifier chain is byte-for-byte `Modifier.fillMaxSize().graphicsLayer { … }`. Keep it.
- **A "skip it at the default value" guard is not a safety valve** — it is a switch the user flips on
  their first action, turning a maybe-broken feature into a definitely-broken one.
- `TextureView.setLayerType` ignores the layer type (a TextureView is always a hardware layer) and
  forwards to `setLayerPaint`, applied in `draw()`. So **no offscreen layer** is allocated and there
  is no path from the colour back to the player, the Surface, or the event loop. `setLayerPaint`
  no-ops on an identical instance — hence `remember(colorMatrix)`.
- `PlayerView.getVideoSurfaceView()` is public and the surface view is built in the `PlayerView`
  constructor, so the reference is valid right after inflation. The `as? TextureView` is a **guard**:
  on a SurfaceView, `setLayerType(LAYER_TYPE_HARDWARE, paint)` would allocate a *real* offscreen
  layer.
- No `RenderEffect` anywhere: it is API 31+ and `minSdk` is 24. A `Paint.colorFilter` works from
  API 24.

---

## Ownership invariants that cause "impossible" bugs

- **Clip-clock invariant.** The preview's ExoPlayer holds **exactly one** media item
  (`setMediaItem(MediaItem.fromUri(activeClip.uri))` + `seekTo(0L)`), so `pos` is **already
  clip-local** (`0..clipDuration`). `activeClip` is `uiState.selectedClip ?: clips.firstOrNull { !it.isAudioOnly }`
  — *not* the clip under the playhead. **Do NOT subtract `clip.timelineStartMs`**: that is an
  editor-timeline offset this player never sees; it drives clip-local time negative and silently
  stalls the effect. That wrong convention had spread to three call sites. The **export** *is*
  item-relative.
- **One writer per player property.** In `EditorScreen` the 33 ms poller is the **sole** writer of
  `exoPlayer.volume`; the volume/mute `LaunchedEffect` writes `baseVolumeRef`. Two writers for one
  player property is a recurring bug class here — it silently broke playback speed once.
- **`brightness`/`contrast`/`saturation`/`colorGrading`/`opacity` reach the preview as
  `activeClip?.field ?: uiState.field`.** `VideoClip.brightness` is a **non-null** `Float`, so the
  clip always wins. Panels are gated on `selectedClip != null` and the ViewModel mirrors only
  `if (selectedClipId != null)` — so a control that writes `uiState` alone is invisible to the
  preview.
- Per-clip audio fades: `model/AudioFade.kt#gainAt`, used by both renderers. Linear in amplitude;
  windows clamped to the clip; overlaps resolve via `min()`. The export processor counts **input
  frames** for clip-local time and is appended **last**.
