# MEMORY.md — AICreatorStudio

`com.aicreatorstudio.app` · src root `app/src/main/java/com/aicreatorstudio/app/`

**Load the narrowest source first.** Export/effect → `reference/architecture.md`; shipped features →
`reference/features.md`; AI frames/decoder/Whisper → `reference/ai-media-decode.md`; Media3 →
`media3-export-parity` skill; Atlas maps → `aierix-cut-project` skill; history → `.memory/2026-*.md`.

## Build & test

**Git since 2026-10-29** (`96f0f73`) — `git diff` before claiming what changed. `local.properties`
(release signing passwords) and `app/ai-models/**/*.bin` are ignored on purpose.

`./gradlew <task> --offline` from Bash, keep `--offline`. Procedure → **`reference/build-and-apk.md`**.

- ~3000 tests, 3–6 min → background; grep `^e:` / `BUILD`. Release builds are 5–9 min → pass
  `timeout: 600000`, never the 120 s default.
- Never-chase: `RealModelInstallTest`, `WhisperNativeSymbolsTest`, `WhisperLargeV3LockfileTest`.
- ⚠️ ~~`compileDebugUnitTestKotlin` RED since 2026-09-21~~ — **NOT TRUE as of 2026-10-30.**
  Verified by running the task with no files parked: `> Task :app:compileDebugUnitTestKotlin`
  succeeded. The park-in-`build/tmp/stale-tests/` dance is **no longer needed**; stop doing it on
  reflex. (It was real in September and was fixed by the 2026-10-27 whisper.cpp re-copy.)
- ⚠️ Build as a **separate invocation** from tests, and **`rm` the APK first** (incremental `package`
  keeps dead bytes). Verify by mtime + DEX grep.

## Verifying an APK

`zipalign -c 4`, `apksigner verify`, `aapt2 dump badging`, `unzip -l` from Bash, build-tools **35.0.0**.
No `strings` → unzip `classes*.dex`, `grep -a`; our code is in `classes3/11.dex` (`classes17.dex` is a
third-party GL lib with zero app classes).

**Deobfuscating a crash stack:** `app/build/outputs/mapping/release/mapping.txt` ships with every
release build — `grep "^[^ ].* -> b5\.b0:$"` maps a frame in one step. Lambda frames are always
obfuscated even when the class is kept.

## Highest-value traps

- **A missing import is the #1 cascade cause** — add it, recompile, *then* diagnose. `private const val`
  cannot sit between imports; a literal `*/` in a KDoc silently ends the comment.
- **A wrong KDoc teaches the wrong invariant** — verify by grepping the **consumers**.
- **A forwarded-only parameter eventually stops being forwarded** (`fade` on `StemClip`, `motionTrack`
  in `RoomProjectRepository`, `voiceEffect` in `AudioMixerPlayer`'s key list, `shortSideOverride` in
  `MaskBitmaps`). New per-clip field → grep every construction site *and* every cache-key list.
- ⚠️ **Decoded bitmaps are immutable** — `BitmapFactory.decode*` → `setPixels` throws
  `IllegalStateException`. `copy(ARGB_8888, true)` first, recycle the original. Cost us 2026-10-29:
  every export with a non-zero matting slider died at PREPARING behind "Video processing failed".
- ⚠️ **A fast path hides the broken slow path** — `isNoOp` early returns put the defect where no test
  looks. When a function has two paths, test the expensive one.
- **Tests: JUnit4 + coroutines-test + org.json only.** Hilt VMs can't build in JVM → pure top-level fn
  in `model/`, or a **source-shape test**. ⚠️ A ONE-LINE `/** … */` makes a line-oriented stripper
  swallow to the next `*/`; the copies in `CustomFontWiringTest`, `SubtitleEditingWorkflowTest`,
  `ProgressBarWiringTest`, `NativeEngineFailureWiringTest`, `OverlayMaskWiringTest`,
  `AudioLaneLifecycleWiringTest` must agree. On failure **grep the RAW file** first.
- ⚠️ **Stale closures in long-lived `DisposableEffect`s** are the most-repeated bug class (a 33 ms
  poller read a captured `isPlaying` and hard-paused playback). Read via
  `rememberUpdatedState(...).value`; a state the effect must *write* needs `by remember { mutableStateOf }`.
- ⚠️ **A processor must DECLARE the format it actually emits.** `AudioProcessingPipeline.configure`
  chains each processor's *returned* format into the next and only advances when `isActive()`.
  `VolumeScalingAudioProcessor`/`FadeAudioProcessor` read `asFloatBuffer` and hard-code
  `ENCODING_PCM_FLOAT`, so they are float-only: a 16-bit converter placed *ahead* of them is
  undone, and the Sonic stage behind dies with `UnhandledAudioFormatException` (`encoding=4`).
  Correct shape: float stages → `ToInt16` → Sonic stages → (`ToFloat` → float stages) →
  **`ToInt16` last, always** (Media3 takes the mixer format from input 0 and appends 16-bit-only
  Sonic/ChannelMixing to every later input). `ToFloatPcmAudioProcessor` is hand-written —
  media3-common 1.5.0 has no float counterpart. `AudioChainFormatTest` pins the order.
  ⚠️ Still open: at speed ≠ 1 the exported **audio track is exactly half the video duration**
  (constant ×2, not speed²) — see `.memory/2026-10-31.md` for the measurements.
- ⚠️ **MediaPipe JNI**: `tasks-vision` ships no `.so`; the only lib is `tasks-core`'s
  `libmediapipe_tasks_jni.so`, shared by both engines via `ai/MediaPipeNativeLib.kt`.
- ⚠️ **`AiToolRegistry` resolves engines from the Hilt `List<AiToolEngine>`** in
  `AppModule.provideAiToolEngines`; adding an engine = adding it there. `SpectralGateVoiceClean` is
  intentionally unregistered.
- ⚠️ **`models.lock.json` exists twice** (`app/ai-models/` is truth; `assets/` regenerated by
  `preBuild`). `"bundled": false` = never packaged. `BundledAssetIntegrityTest` asserts they agree.
- ⚠️ **`unitTests.isReturnDefaultValues = true`** makes `android.*` inert (`Rect`/`Bundle` → `0`) —
  tests pass for the wrong reason. Plain floats for geometry.
- ⚠️ **A new ctor dep on a `@HiltViewModel` breaks its JVM test** — grep `app/src/test` for `ViewModel(`
  first; interface + `Default…` + `InMemory…` fake in `di/AppBindings.kt`.
- ⚠️ Confirm the composable holds `viewModel` before wiring `viewModel.*`; `…Content` functions are
  callback-driven. Never put a fixed-size child in a weighted Compose slot (`Modifier.weight` is a scope
  extension → private composables need a pass-through `modifier`).
- ⚠️ **Do NOT call `setVideoEffects` at construction** (killed playback). Colour goes to the
  `TextureView` layer paint, never a Compose draw modifier on the `AndroidView`. `RgbMatrix` = 4×4
  column-major.
- Preview `ExoPlayer` holds one item → `pos` is **already clip-local**. `VideoClip.brightness` is a
  non-null `Float` → always wins over `uiState`.

## Preview/export parity (🎯 hard-won)

- **The DIRECTION of a reported asymmetry is evidence** — enumerate mechanisms that *can* produce it
  first; read a persistence fn **to its closing brace** before calling it broken.
- `TextOverlay.fontFamily` ↔ `overlay.subtitleStyle.fontFamily` must both be written
  (`updateSelectedTextFont`); mismatch shows **only in the export**.
- **Parity is safe only when STRUCTURAL** — one shared pure function feeding both renderers.
- Compose `PathFillType` has no inverse; `android.graphics.Path` has `INVERSE_EVEN_ODD`. Preview
  knockout needs `CompositingStrategy.Offscreen` + `BlendMode.DstOut`.
- ⚠️ **Matting composites at export only** — the preview shows the untouched clip, so a user who ran
  the pass sees "it doesn't work". Known, unfixed.

## Dimensions, IA, persistence

→ **`reference/editor-ia.md`** — canvas sizing chain (one owner per step), the contextual-toolbar chip
counts pinned by `ContextualToolbarSpecTest`, `SubPanelHeight` clamps, the **paired-audio invariant**
(every mutator of `project.clips` must also touch the `AudioClip` with that `originVideoClipId`, or the
"Video Audio" lane drifts — `setClipTrim`/`setClipSpeed` are still uncovered), and the `aiToolsJson`
per-clip persistence checklist (`toVideoProject` omission is bug class 1).

## AI media

→ **`reference/ai-media-decode.md`** before touching `ai/media/`, `ai/engines/`, `ai/ocr/`,
`ai/upscale/`, `ai/audio/`, `ai/subtitle/`. Four rules that abort the process rather than throw
(`AiFrameDecodeLifecycleTest`):

- **Never render an EOS output** — `releaseOutputBuffer(st, !eos)`. A rendered EOS buffer yields an
  `Image` with no gralloc buffer; the lazy `planes[0]` trips CheckJNI (`non-zero capacity for nullptr
  pointer`), which no `try/catch` catches.
- **Convert → close the `Image` → then run the AI op.** `onFrame` suspends for seconds against a 3-slot
  reader; holding the `Image` lets the driver reclaim it.
- **`acquireNextImage()` blocks** (never returns null) — gate it on a `pendingImages` counter.
- **No GL call without a current EGL context** — `glGetString` on a worker thread is a SIGSEGV.

## Matting mask (2026-10-29)

- **The category mask is binary** — `cls == 0 → 0f else 1f`. No soft knee, ever; partial alpha came
  only from the old EMA. Soft edges are the composition-time feather (`MaskPostProcess`).
- **`ai/MattingTemporalFilter.kt`** = median of three + scene-cut reset at 0.35. **Do not add an EMA on
  top** — it turns the median's one-frame lag into a multi-frame fade (`MattingTemporalFilterTest`
  catches it). Once a filter has discarded a value, do not average the result.
- **`ANALYSIS_MAX_SIDE = 512`, not 320** — 320 gives a 16:9 short side of 180, *below* the model's 256
  input, so the segmenter upscales. Pinned by `analysis bound keeps the short side at the model input`.
- **`MaskBitmaps.applyPostProcess` is pure in / new buffer out**; the caller recycles the decoded PNG.
  Guarded by `MaskBitmapsMutabilityTest`.
