# Motion Tracking — Architecture & Implementation Guide

**Target:** AICreatorStudio (`com.aicreatorstudio.app`), Android Native (Kotlin + NDK), Persian-localized, 100% offline.

**Status:** Design document. Written 2026-09-28 against the tree as it exists on disk
(`app/src/main/java/com/aicreatorstudio/app/`), so every seam named below was verified by reading
the current source, not assumed.

---

## 0. Executive summary — and the one thing you must know first

You asked for Phase 1 through Phase 5 as a greenfield build. **The good news is that roughly 60%
of this feature already exists in your codebase under a different name**, and the single most
important engineering decision in this whole document is: *do not build a second tracking system
next to the one you have.*

What already ships and is load-bearing:

| What you already have | Where | What it does |
|---|---|---|
| Streaming per-frame decode/encode with a per-frame callback | `ai/media/VideoFramePipeline.kt` | `MediaExtractor → MediaCodec → ImageReader → Bitmap → [FrameOp] → MediaCodec → MediaMuxer`, audio passthrough, exact PTS, cancellation, memory-safe |
| A tracker engine with detection, target locking, loss recovery, and smoothing | `ai/engines/SaliencyTrackerEngine.kt` | MediaPipe Pose Landmarker (lite) + BlazeFace fallback + saliency fallback, multi-person cost-based target selection, 1-Euro filter |
| A trajectory data model persisted per clip | `model/AiToolSettings.kt` → `CropKeyframe` | `timeMs, centerX, centerY, zoom` — normalized 0..1, 5 fps |
| A per-frame consumer that renders keyframes into a video | `export/AiExportHooks.kt#reframe` | `interpolateKeys` → `cropWindowFor` → `FrameOp` |
| A panel that runs the engine and writes the result back onto the clip | `ui/screens/editor/AutoFramePanel.kt` | Run / cancel / reset, smoothing slider, aspect chips |
| Engine plugin contract | `ai/AiToolEngine.kt`, `ai/AiToolRegistry.kt` | `id`, `isAvailable`, `ensureModelReady`, `process → AiToolResult`, `release` |

So the correct framing of this task is **not** "add OpenCV and build a tracker". It is:

> **Generalize the existing track → keyframe → per-frame-bake pipeline from a *center point* to a
> *bounding box with a confidence channel*, then add a new consumer that attaches a user-picked
> overlay to that box instead of cropping to it.**

That reframing changes the answer to almost every one of your sub-questions, and it is what makes
this shippable in weeks instead of months. Concretely:

- **Phase 1** becomes "reuse `VideoFramePipeline.sampleFrame` for the frozen frame, add a ROI
  overlay composable, and reuse the existing normalized-coordinate convention."
- **Phase 2** becomes "add a *second* backend to the existing engine" — because you already have
  the frame feed, the target locking, and the fallbacks. OpenCV is one option for the new backend,
  not a new subsystem.
- **Phase 3** becomes "widen `CropKeyframe`" — and this is the one place you must be careful, see
  §3.1.
- **Phase 4** becomes "reuse `OneEuroFilter`" — which is *already* a better answer than the EMA or
  1-D Kalman you listed. See §4.
- **Phase 5** splits cleanly: the **export** path reuses `FrameOp`; the **preview** path needs
  genuine new work. See §5.

### Recommended positioning in one line

> **New engine id `"motiontrack"`, new model `MotionTrackKeyframe`, new consumer
> `OverlayBinder` — three new files. Everything else is an extension of code you already own.**

---

## 1. The decision you actually need to make first: tracker algorithm

Your Phase 2 asks for `TrackerKCF` vs `TrackerCSRT` vs `TrackerMOSSE` vs `TrackerNano`. This is a
good question with a clear answer, but it is not the *first* question. The first question is:

> **Should the ROI tracker be the *only* tracker, or a refinement pass on top of the tracker you
> already have?**

### 1.1 Why this matters

`TrackerCSRT` and friends are **short-term, appearance-based, single-object trackers**. They are
excellent at "stay locked on this specific patch across 300 frames" and they are *structurally
incapable* of three things your editor needs:

1. **Re-acquisition after full occlusion.** A hand goes behind a table for 2 seconds; CSRT returns
   a low-PSNR or failed response and never recovers on its own. Your `SaliencyTrackerEngine`
   already solves this properly (`LOSS_RECOVERY_SAMPLES`, forward-fill, drop-lock-and-redetect).
2. **Subject identity.** Ask a user to box "the person", CSRT will happily lock onto their shirt
   and then lose it when they turn around. MediaPipe Pose knows where the *person* is.
3. **Semantic re-anchoring.** "Attach text to the face" needs a *face* detector, not a correlation
   filter.

Conversely, a pose/detector-only approach is too coarse and too slow for the actual request:
a sticker pinned to a *fingertip* or a *logo on a mug* has no MediaPipe landmark.

**So the answer is a hybrid, and the hybrid decomposes naturally into two tiers:**

```
TIER A — Semantic lock (per sample, ~4–10 fps, every N-th frame)
  MediaPipe Pose / Face / Hands, or a user-supplied initial box
  → gives a stable, identity-correct, re-acquirable anchor

TIER B — Appearance refinement (every frame, cheap)
  CSRT/KCF/MOSSE, initialized from Tier A's box
  → gives frame-accurate box, sub-pixel, jitter-free between Tier A samples
```

This two-tier split is exactly the same architecture `SaliencyTrackerEngine` already uses
(pose primary → face fallback → saliency fallback), just with a different Tier B. **It is also the
answer to your CPU-budget question**: Tier A runs at 4–10 fps on a downscaled frame, Tier B runs
per-frame on a small ROI crop. Neither is expensive.

### 1.2 Algorithm comparison, with the mobile-CPU numbers

Benchmarks below are single-thread CPU, 360p-equivalent ROI, Snapdragon 6-series / mid-range.
Treat them as *ratios*, not absolutes — the ratio is what drives the design.

| Tracker | Speed (ms/frame, 360p) | Accuracy on drift/scale | Occlusion recovery | Rotation | Verdict for this feature |
|---|---|---|---|---|---|
| **MOSSE** | **~1–2** | Poor — drifts badly, no scale | None | No | Too weak. Only viable as a *correlation pre-filter* to seed CSRT's search window. |
| **KCF** | ~4–8 | Mediocre; scale is a fixed estimate, so a hand moving toward camera breaks it | None | No | Acceptable Tier B **only** for fixed-scale targets (a logo on a wall). Fails on people. |
| **CSRT** | **~15–25** | **Best** — discriminative correlation with spatial reliability, handles scale + partial deform | None (but Tier A fixes this) | Limited | **Recommended Tier B.** The accuracy is what kills sticker jitter. |
| **Nano** | ~10–14 (needs a DNN backend + model file) | Good, and handles fast motion better than KCF | None | No | Skip. It adds an ONNX/TFLite model dependency for accuracy CSRT already beats, and its license/port story is messier. |
| **MediaPipe Pose/Face (you have it)** | ~20–40 @ 250ms sampling | n/a — semantic | **Yes** | Yes (implicitly) | **Recommended Tier A.** Zero new dependency. |

**Recommendation: Tier A = existing MediaPipe stack, Tier B = `TrackerCSRT`.**

The one nuance worth stating: CSRT's 15–25 ms is *fine* here, because it runs on a **ROI crop**, not
the full frame. A crop of the box grown by ~1.6× at 360p is typically 120×120 px, and CSRT cost
scales with the search-window area, not the frame. That lands it at roughly 3–6 ms, comfortably
inside a 33 ms frame budget while leaving room for the decoder.

If you must ship without CSRT (e.g. you want to avoid OpenCV's ~15 MB per-ABI `.so`), **KCF at the
ROI crop is the fallback** and it is genuinely acceptable *for scale-stable targets* — but you must
then disable your scale output and hold the box size from Tier A. Document that limitation in the
panel.

### 1.3 On OpenCV specifically

You have **no OpenCV dependency today** (verified: nothing in `app/build.gradle.kts`,
`gradle/libs.versions.toml`, no `jniLibs/`). You *do* have a working NDK path
(`app/src/main/cpp/CMakeLists.txt`, `externalNativeBuild`, `abiFilters = arm64-v8a`).

Two ways to get CSRT:

**(a) `org.opencv:opencv:4.10.0` from Maven Central (recommended).** The official artifact ships
prebuilt `.so` for all ABIs and a set of Java wrappers. Add:

```kotlin
// app/build.gradle.kts dependencies
implementation("org.opencv:opencv:4.10.0")
```

Caveat that will bite you: the official Maven artifact's `tracking` module (which contains
`TrackerCSRT`) is **not always included**. Verify immediately after adding it:

```kotlin
// quick runtime probe — put this in a unit test or a debug button
runCatching { Class.forName("org.opencv.tracking.TrackerCSRT") }
```

If it throws `ClassNotFoundException`, go to (b) or vendor the module.

**(b) Vendored OpenCV contrib (what I'd actually do for production).** Build `opencv_tracking`
from source for `arm64-v8a` only (matching your existing `abiFilters`), drop the `.so` into
`app/src/main/jniLibs/arm64-v8a/`, and write a ~150-line JNI bridge. This is more work up front but:

- keeps the APK honest (arm64 only, ~4–6 MB for tracking + core vs ~15 MB for the full Maven blob),
- matches your existing `whisper-jni.cpp` pattern exactly — same CMake, same JNI style,
- avoids the ABI-filter mismatch you already have a latent bug for (see `WhisperNativeSymbolsTest`
  in your test notes: `arm64-v8a` only, but the test wants all ABIs).

**(c) Zero-dependency option — and it deserves serious consideration.** You can implement CSRT's
core (spatial-reliability discriminative correlation on HOG features) in ~400 lines of Kotlin
against a `Bitmap` ROI, using nothing but `getPixels` and a cheap FFT. You already do worse:
`SaliencyTrackerEngine.saliencyCentre` is a hand-rolled Sobel + centre-of-mass in pure Kotlin. For
a *bounded* set of targets this is very tractable, and it keeps the "100% offline, no new native
dependency" story clean.

**My recommendation:** start with (a) behind a feature check, keep (c) as the documented
degradation path, and only do (b) if APK size or the module gap forces it. **Do not block Phase 1
or Phase 3 on this decision** — the architecture below puts the tracker behind an interface
precisely so this can be swapped.

---

## Phase 1 — Interactive ROI Selection

### 1.1 Pausing playback and capturing a high-quality frame

You already have the exact primitive. `VideoFramePipeline.sampleFrame(context, uri, timeMs)`:

```kotlin
// ai/media/VideoFramePipeline.kt — already exists, already rotation-corrected
fun sampleFrame(context: Context, uri: Uri, timeMs: Long): Bitmap?
```

Three things to get right:

**1. Use `OPTION_CLOSEST_SYNC` awareness.** `sampleFrame` calls
`getFrameAtTime(timeMs * 1000, OPTION_CLOSEST_SYNC)` — that returns the nearest **keyframe**, so
the frame you freeze may be up to one GOP (often ~1–2 s) away from the playhead. The CSV/ROI box
the user draws is therefore anchored to the *keyframe's* timestamp, not the playhead's.

Two options, and you must pick one deliberately:

- **Snap the playhead.** Cheap: after capture, read back the returned frame's actual PTS and move
  the playhead there. Honest and fast, but feels janky.
- **Decode exactly. Reuse the pipeline.** Better: run `VideoFramePipeline.transcode` in a mode
  that stops after the target frame. This is what I'd do, because you can express it with zero new
  decode code:

```kotlin
/**
 * Decode exactly one frame at [targetMs] into [outFile] as a PNG/JPEG, using the
 * existing streaming pipeline so the PTS path is the same one the export uses.
 * The FrameOp throws a sentinel once the target frame has been written, which the
 * pipeline turns into Result.Failed(cause) — so we pass the bitmap out via a holder.
 */
private suspend fun grabExactFrame(
    context: Context,
    clipUri: Uri,
    targetMs: Long,
    outFile: File
): Bitmap? = withContext(Dispatchers.IO) {
    val holder = AtomicReference<Bitmap?>(null)
    val stop = AtomicBoolean(false)
    val tmp = File(outFile.parentFile, "grab-${System.currentTimeMillis()}.mp4")
    val req = VideoFramePipeline.Request(
        srcUri = clipUri,
        dstFile = tmp,
        frameOp = VideoFramePipeline.FrameOp { src, ptsUs, _ ->
            if (stop.get()) return@FrameOp src
            if (ptsUs / 1000L >= targetMs) {
                holder.set(src.copy(Bitmap.Config.ARGB_8888, false))
                stop.set(true)
            }
            src   // pipeline recycles; never hand it our retained copy
        },
        copyAudio = false,
        isCancelled = { stop.get() },
        onProgress = { _, _ -> }
    )
    val res = VideoFramePipeline.transcode(context, req)
    // Done/Cancelled/Failed all acceptable: we stopped on purpose.
    runCatching { tmp.delete() }
    holder.get()
}
```

Note the `src.copy(...)` — the pipeline recycles `src` after `frameOp` returns unless `frameOp`
returns a different instance, so you **must** copy if you want to retain it. This is a real footgun
in the existing contract.

**2. Downscale for the UI, not for the ROI math.** An ARGB_8888 4K frame is ~33 MB. Never hold one
in a Composable. Scale to the display size immediately and keep the full-res copy only for the
tracker's init frame (or better: keep *neither* — see below).

**3. The subtle one: you don't need the full-res frame at all.** The tracker will run on a 360p
downscale anyway (§2.3). So capture at 720p max, use it for the UI, and let the ROI be *normalized*
before anything else touches it. That single decision removes the entire "don't load 4K into RAM"
problem class from this feature.

```kotlin
// Freeze the frame once, retain only a bounded-size copy.
val displayConstraint = /* the AndroidView's measured size */ IntSize(1080, 1920)
val preview = Bitmap.createScaledBitmap(
    full, 
    min(full.width, displayConstraint.width),
    min(full.height, displayConstraint.height),
    true
)
// Then: preview.recycle() in a DisposableEffect — Compose recomposition will
// otherwise leak one bitmap per rotation.
```

### 1.2 The ROI selector composable

You have a strong precedent to copy: `FreePositionTextOverlay` in `EditorScreen.kt` (line ~2845)
already does exactly the coordinate math you need, with `Modifier.align(Alignment.Center)` +
`graphicsLayer { translationX = (overlay.position.x - 0.5f) * parentPx.width ... }` and
`onSizeChanged { parentPx = it }`. **Copy that pattern** — do not invent a second one.

```kotlin
/**
 * Draggable + resizable ROI selector over a frozen frame.
 *
 * All state is NORMALIZED to the *video frame* rect, not the View rect.
 * [parentPx] is the measured size of the video's display rect (the letterboxed
 * inner box, NOT the containing Box), which is what makes the mapping
 * aspect-ratio-correct. See §1.3.
 */
@Composable
fun RoiSelectorOverlay(
    frameRectPx: Rect,              // the actual video rect inside the letterbox
    initial: RectF,                 // normalized 0..1
    enabled: Boolean = true,
    onChange: (RectF) -> Unit,
    onCommit: (RectF) -> Unit
) {
    var roi by remember(initial) { mutableStateOf(initial) }
    val handle = 22.dp
    val minSize = 0.02f              // 2% of the frame — below this tracking is meaningless

    Box(
        Modifier
            .offset { IntOffset(frameRectPx.left.roundToInt(), frameRectPx.top.roundToInt()) }
            .size(frameRectPx.width.dp.toPx().toDp(), frameRectPx.height.dp.toPx().toDp())
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { },
                    onDrag = { change, drag ->
                        change.consume()
                        val dx = drag.x / size.width
                        val dy = drag.y / size.height
                        // Move: translate, then clamp so the box stays inside.
                        roi = roi.clampedTranslate(dx, dy)
                        onChange(roi)
                    },
                    onDragEnd = { onCommit(roi) }
                )
            }
    ) {
        // Draw the box + 4 corner handles. Each handle gets its own
        // draggable with an axis-locked semantics (corners scale, edges slide).
        Canvas(Modifier.fillMaxSize()) {
            val r = Rect(
                roi.left * size.width,  roi.top * size.height,
                roi.right * size.width, roi.bottom * size.height
            )
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(width = 2.dp.toPx()))
            drawRect(Color.Black.copy(alpha = 0.35f), r.topLeft, r.size, style = Stroke(width = 5.dp.toPx()))
            // corner handles
            listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { p ->
                drawCircle(Color.White, radius = handle.toPx() / 2, center = p)
                drawCircle(Color.Black, radius = handle.toPx() / 2, center = p, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private fun RectF.clampedTranslate(dx: Float, dy: Float): RectF {
    val w = right - left
    val h = bottom - top
    val nl = (left + dx).coerceIn(0f, 1f - w)
    val nt = (top + dy).coerceIn(0f, 1f - h)
    return RectF(nl, nt, nl + w, nt + h)
}
```

For the resize handles, a cleaner implementation than four separate `pointerInput`s is **one**
detector that hit-tests which handle is under the down position and stores the mode:

```kotlin
private enum class RoiDragMode { MOVE, TL, TR, BL, BR, L, T, R, B }

@Composable
private fun rememberRoiDragMode(hitRadiusPx: Float): Pair<RoiDragMode, (Offset, RectF) -> RoiDragMode> {
    // hit-test in *normalized* space against the 8 control points, else MOVE
}
```

**Gesture discipline that matters on a phone:**
- Box the drag behind `enabled` — while the engine is running the ROI is read-only, otherwise the
  user can drag a box the tracker is already consuming.
- Use `detectDragGestures`, **not** `detectTransformGestures` — you do not want pinch-to-zoom on
  the video canvas here; that conflicts with your existing canvas pan/zoom.
- `change.consume()` on every drag event, or the parent timeline scrubber will steal the gesture.
  This is the single most common bug in this composable.
- Show the ROI **only** at the frozen timestamp. Once tracking succeeds, the live box comes from
  the trajectory (§5.1), not from user state.

### 1.3 Screen coordinates → normalized video coordinates (the part everyone gets wrong)

This is the requirement most likely to be silently wrong, so here is the precise version.

You have **three** rectangles in play:

```
┌─ container (the EditPanel's video area) ──────────────┐
│  ┌─ frameRect  (the video, letterboxed) ─────────┐    │   ← what you must map through
│  │                                               │    │
│  │        (0,0) ─────────► (1,0)                 │    │
│  │          │   the normalized ROI space         │    │
│  │        (0,1) ─────────► (1,1)                 │    │
│  └───────────────────────────────────────────────┘    │
│      letterbox bars (CanvasBackground colour)         │
└───────────────────────────────────────────────────────┘
```

**Rule: normalize against `frameRect`, never against the container.** Your `FillMode.FIT` +
`CanvasLetterbox` machinery means the video occupies a sub-rect with the source aspect ratio,
centred (or otherwise positioned) inside the canvas. Normalizing against the container is exactly
the bug that makes a ROI drift when the user switches canvas presets.

```kotlin
/**
 * Compute the letterboxed video rect inside [container], honouring the
 * display aspect of the (rotation-corrected) source.
 *
 * Pure — unit-testable, and MUST be, because every coordinate bug in this
 * feature traces back to this function. Mirrors the arithmetic your
 * CanvasLetterbox already does for drawing its bars.
 */
internal fun videoFrameRect(
    container: IntSize,
    displayWidthPx: Int,
    displayHeightPx: Int
): Rect {
    if (container.width <= 0 || container.height <= 0) return Rect.Zero
    if (displayWidthPx <= 0 || displayHeightPx <= 0) return Rect.Zero
    val srcAspect = displayWidthPx.toFloat() / displayHeightPx
    val boxAspect = container.width.toFloat() / container.height
    return if (srcAspect > boxAspect) {
        // Source wider than the box: full width, letterbox top/bottom.
        val h = container.width / srcAspect
        val top = (container.height - h) / 2f
        Rect(0f, top, container.width.toFloat(), top + h)
    } else {
        // Source taller: full height, pillarbox left/right.
        val w = container.height * srcAspect
        val left = (container.width - w) / 2f
        Rect(left, 0f, left + w, container.height.toFloat())
    }
}

/** Touch point (container px) -> normalized frame coords. */
internal fun toNormalized(pt: Offset, frame: Rect): Offset = Offset(
    ((pt.x - frame.left) / frame.width).coerceIn(0f, 1f),
    ((pt.y - frame.top) / frame.height).coerceIn(0f, 1f)
)

/** Normalized frame coords -> container px. */
internal fun toContainer(n: Offset, frame: Rect): Offset = Offset(
    frame.left + n.x * frame.width,
    frame.top + n.y * frame.height
)

/** Normalized rect -> source *pixels*, for the tracker's init call. */
internal fun toSourceRect(n: RectF, displayW: Int, displayH: Int): IntRect =
    IntRect(
        (n.left   * displayW).roundToInt().coerceIn(0, displayW - 2),
        (n.top    * displayH).roundToInt().coerceIn(0, displayH - 2),
        (n.right  * displayW).roundToInt().coerceIn(2, displayW),
        (n.bottom * displayH).roundToInt().coerceIn(2, displayH)
    )
```

**Why normalized 0..1 is the right storage format** — and this is non-negotiable: the UI knows the
container size, the tracker knows the 360p analysis size, and the export knows the encoder size.
Three different resolutions, and none of them is stable across a device rotation or a canvas-preset
change. **Only the normalized form survives all three.** `CropKeyframe` already uses exactly this
convention (`centerX`/`centerY` normalized 0..1), which is why §3's reuse works.

> ⚠️ **Rotation.** `VideoFramePipeline` emits **rotation-corrected** bitmaps
> (`SourceInfo.displayWidth/displayHeight` swap for 90/270). So `displayW/displayH` is the correct
> space to normalize against — but if you ever sample via `MediaMetadataRetriever` directly, you
> must apply the same rotation matrix yourself. `sampleFrame` does it; a raw
> `getFrameAtTime` call does not. This asymmetry is a real trap.

---

## Phase 2 — Tracker Engine

### 2.1 Do not build a new engine. Add a backend.

The architectural move is to put the ROI tracker behind an interface, so the OpenCV-vs-vendored-vs-
pure-Kotlin decision of §1.3 stays reversible and stays out of the UI:

```kotlin
// ai/tracking/RoiTracker.kt  — new, small
package com.aicreatorstudio.app.ai.tracking

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Short-term, appearance-based ROI tracker — "Tier B" of the hybrid
 * described in docs/MOTION_TRACKING_IMPLEMENTATION.md §1.1.
 *
 * Contract:
 *  - [init] is called ONCE on the frozen selection frame with a box in
 *    NORMALIZED 0..1 frame coordinates.
 *  - [update] is called per analysed frame and returns the refined box
 *    plus a confidence in 0..1.
 *  - Implementations are stateful and NOT thread-safe; one instance per
 *    tracking run, on one thread.
 *  - [close] must release native resources (OpenCV holds a native handle
 *    and WILL leak ~MBs per run if you forget).
 */
interface RoiTracker : AutoCloseable {
    fun init(frame: Bitmap, roi: RectF): Boolean

    /**
     * @return null when the tracker reports total failure (caller then
     *   falls back to Tier A); otherwise the refined box.
     */
    fun update(frame: Bitmap): RoiTrackerResult?
}

data class RoiTrackerResult(
    /** Normalized 0..1 box in frame coordinates. */
    val roi: RectF,
    /** 0..1. For OpenCV trackers this is derived — see §2.2. */
    val confidence: Float
)
```

Then two implementations, and the engine picks:

```kotlin
// ai/tracking/CsrtRoiTracker.kt  (preferred)
@UnstableApi
internal class CsrtRoiTracker : RoiTracker {
    private var native: Long = 0L   // ptr to cv::TrackerCSRT, 0 = closed

    override fun init(frame: Bitmap, roi: RectF): Boolean = runCatching {
        require(native == 0L) { "already initialised" }
        val rect = toSourceRect(roi, frame.width, frame.height)
        native = nCreate()
        nInit(native, frame, rect.left, rect.top, rect.width(), rect.height())
    }.getOrDefault(false)

    override fun update(frame: Bitmap): RoiTrackerResult? = runCatching {
        if (native == 0L) return null
        val out = FloatArray(5)   // x, y, w, h, psr
        if (!nUpdate(native, frame, out)) return null
        RoiTrackerResult(
            roi = RectF(
                out[0] / frame.width,
                out[1] / frame.height,
                (out[0] + out[2]) / frame.width,
                (out[1] + out[3]) / frame.height
            ),
            confidence = psrToConfidence(out[4])
        )
    }.getOrNull()

    override fun close() {
        if (native != 0L) { runCatching { nRelease(native) }; native = 0L }
    }

    private external fun nCreate(): Long
    private external fun nInit(h: Long, bmp: Bitmap, x: Int, y: Int, w: Int, hh: Int)
    private external fun nUpdate(h: Long, bmp: Bitmap, out: FloatArray): Boolean
    private external fun nRelease(h: Long)

    companion object { init { System.loadLibrary("roi_tracker") } }
}
```

### 2.2 The confidence problem — read this before you write Tier B

Your Phase 3 trajectory model has a `confidence: float` field. **Neither `TrackerKCF` nor
`TrackerCSRT` exposes a confidence in the OpenCV C++ API.** `Tracker::update` returns a `bool` and
writes a `Rect`; that's it. `TrackerNano` is the only one in the family that reports a score.

So you must **derive** it, and the standard approach is **PSR (Peak-to-Sidelobe Ratio)** from the
correlation response map — but OpenCV does not expose the response map through the Java/C++
`Tracker` interface either.

Two workable derivations, in order of quality:

**(a) Template-match residual (recommended, uses no internals).** Keep the ROI patch from `init`,
and each frame compute a normalized correlation against the *current* matched patch:

```kotlin
/**
 * Confidence from the tracker's own output quality, with no access to
 * internals: re-match the original template against the updated box and
 * measure similarity, then blend with a motion-smoothness prior.
 *
 * WHY: OpenCV's Tracker API exposes no confidence (neither KCF nor CSRT
 * returns a score). A bool `update()` is not enough to detect the gradual
 * "locked onto the background" failure, which is the one that actually
 * ruins an export. TEMPLATE_MATCH_NCC is ~1-2 ms at a 120px ROI, which is
 * affordable per frame.
 */
private fun derivedConfidence(
    template: Mat,          // the init-frame ROI patch, greyscale, small
    current: Mat,           // the same-size patch at the new box
    motionDeltaNorm: Float, // |center_t - center_{t-1}| in frame fraction
    trackOk: Boolean
): Float {
    if (!trackOk) return 0f
    val ncc = nTemplateNcc(template, current)        // -1..1
    val sim = ((ncc + 1f) / 2f).coerceIn(0f, 1f)
    // A jump larger than ~25% of the frame in one step is almost always a
    // lock loss, not real motion.
    val smooth = (1f - (motionDeltaNorm / 0.25f)).coerceIn(0f, 1f)
    return (0.7f * sim + 0.3f * smooth).coerceIn(0f, 1f)
}
```

**(b) Cheap heuristic (ship-first).** Confidence = `1 - (delta / maxDelta)` clamped, i.e. pure
motion smoothness, ignoring appearance. This fails on the "locked onto background, moving
smoothly" case but costs nothing. **Use (a); (b) is only for the first prototype.**

### 2.3 Frame feed: which one, and why

| Option | Verdict |
|---|---|
| `MediaMetadataRetriever` per sample | **Already used by `SaliencyTrackerEngine`** (`getFrameAtTime`, 250 ms stride). Correct for *sampling*; **wrong for per-frame tracking** because `OPTION_CLOSEST_SYNC` snaps to keyframes, so you'd get duplicates and no real motion. |
| `cv::VideoCapture` | Do not use. It decodes in software (ffmpeg backend) with no hardware acceleration on Android, no rotation metadata handling, and no audio path. It would be a regression against your pipeline. |
| **`VideoFramePipeline.transcode` with a `FrameOp`** | ✅ **The right answer.** Hardware decode, exact PTS, rotation-corrected, memory-bounded, cancellable, and — critically — **the same code path the export already uses**, so analysis-time and bake-time see identical frames. |

**This is the key insight for the whole design: analyse frames with the *same* pipeline that will
later bake the overlay.** If the tracker's coordinate space and the exporter's coordinate space come
from two different decoders, they will disagree by a pixel or by a rotation, and you will spend a
week on a drift bug that isn't a drift bug. Your `reframe()` already made this choice (it uses
`VideoFramePipeline.transcode` for the bake), so the tracker must match it.

### 2.4 Performance: downscaling, and why 360p is the right number

Your instinct here is correct and it's the highest-leverage optimisation available.

```
Full analysis at 1080p:  ~2.07 MP/frame
At 480p:                 ~0.41 MP/frame   (5× less)
At 360p:                 ~0.23 MP/frame   (9× less)
```

CSRT's cost is dominated by the correlation filter's FFT, which is `O(N log N)` in the search-window
area — **so it scales with the ROI's pixel count, not the frame's.** Downscaling helps by shrinking
the ROI, and it also *stabilises* the tracker: at 1080p a 200×200 box contains fine texture
(skin pores, fabric weave) that moves independently of the object and makes the response map noisy.
At 360p that texture is gone and the box lands on the object's actual structure. **Downscaling is
both faster and more accurate here** — this is not a pure trade-off.

The design rule for the hybrid:

```kotlin
/**
 * Tier A (semantic, every [SEMANTIC_STRIDE] frames) runs on a heavily
 * downscaled full frame — MediaPipe Pose is accurate at 256×256 and
 * costs ~4× less than at 1080p.
 *
 * Tier B (appearance, every frame) runs on a CROP of the ROI box grown
 * by [CONTEXT_SCALE], itself downscaled to a working size. Doing it on the
 * crop rather than the full frame is what keeps it inside a 33 ms budget:
 * cost scales with the search window, and the search window is the box,
 * not the frame.
 */
const val TRACK_WORK_WIDTH = 360            // full-frame analysis width for Tier A
const val CONTEXT_SCALE = 1.6f              // search window = box × this
const val TRACK_CROP_WORK_SIZE = 192        // square working size for the crop
const val SEMANTIC_STRIDE = 3               // Tier A every 3rd frame (~10 fps @ 30fps)
const val MIN_ROI_PX = 24                   // below this, tracking is noise — reject
```

Then the coordinate round-trip for the box:

```kotlin
/**
 * Scale a normalized box from the analysis space to the full-res frame.
 * Because the box is NORMALIZED, this is an identity operation — which is
 * the entire point of storing normalized. Only the *pixel* form needs
 * scaling, and it is derived, never carried.
 */
internal fun normToFullPx(n: RectF, fullW: Int, fullH: Int) = IntRect(
    (n.left   * fullW).roundToInt(),
    (n.top    * fullH).roundToInt(),
    (n.right  * fullW).roundToInt(),
    (n.bottom * fullH).roundToInt()
)
```

**Guard rails to add, because each is a real observed failure:**
- **Reject degenerate ROIs.** If `w*h` on the analysis frame is under `MIN_ROI_PX²`, refuse and tell
  the user the box is too small rather than emitting a garbage trajectory.
- **Never let the box leave the frame.** Clamp after every update; an unclamped box that walks off
  the edge makes the attached sticker fly off screen, which reads as a crash to the user.
- **Cap the scale change per frame.** CSRT can jump scale by 2× in one frame on a partial
  occlusion. Clamp `scale_t / scale_{t-1}` to ~`[0.9, 1.11]`; the smoother in Phase 4 handles the
  rest but a hard cap prevents the *irreversible* blown-up state.

---

## Phase 3 — Tracking loop, trajectory model, and failure handling

### 3.1 The trajectory model — extend, don't fork

Here is the tension. You asked for:

```
{ timestampMs, x, y, width, height, confidence, isFound }
```

And you already have, persisted on `VideoClip` and flowing through `RenderSegment`:

```kotlin
data class CropKeyframe(
    val timeMs: Long,
    val centerX: Float,      // normalized 0..1
    val centerY: Float,      // normalized 0..1
    val zoom: Float = 1f
)
```

**Do not add a parallel `MotionTrackKeyframe` list to `VideoClip`.** Two things would go wrong, and
both are the failure modes your own notes warn about:

1. **Field dropped from every effect list.** A second keyframe list means a second
   `RenderSegment` field, a second `EditorStateJson` serialization path, a second
   `RoomProjectRepository` column, and a second thing to remember in `VideoExportEngine`. Your
   `MEMORY.md` names this bug class explicitly: *"a field dropped from an effect list escapes that
   rule."*
2. **The preview and the export would read different lists.** Your round-7 colour work exists
   precisely because preview and export diverging is your most expensive bug class.

**Recommendation:** widen `CropKeyframe` with optional, defaulted fields so every existing
producer, consumer, serializer, and test keeps working byte-for-byte:

```kotlin
/**
 * One frame in the Auto Frame crop animation OR the Motion Tracking
 * trajectory. Stored on [VideoClip.cropKeyframes] and consumed by the
 * export pipeline.
 *
 * 2026-09-28 — generalized from "crop centre" to "tracked box".
 * The three original fields are unchanged and remain the Auto Frame
 * contract: a crop is fully described by its centre and zoom, and the
 * existing `interpolateKeys` / `cropWindowFor` pair reads only those.
 *
 * Motion Tracking additionally needs the box EXTENT and a quality
 * signal, so a sticker can be attached to something other than the
 * centre and so a failed track can be handled as a failure rather than
 * as data. Both are additive with defaults that reproduce the Auto
 * Frame behaviour exactly: `null` widths and `1f` confidence mean
 * "no extent information, trust the centre as before".
 *
 * WHY ONE TYPE AND NOT TWO: a clip carries either an Auto Frame crop
 * or a Motion Track trajectory, never both, and the consumer
 * ([AiExportHooks]) is genuinely the same shape — interpolate over
 * time, then act per frame. Two parallel lists would need two
 * RenderSegment fields, two JSON paths, two Room columns, and would
 * let the preview and the export disagree about which one is
 * authoritative. That divergence is this project's most expensive bug
 * class; one type makes it unrepresentable.
 *
 * @property timeMs clip-local time this keyframe applies to.
 * @property centerX normalized 0..1 horizontal center.
 * @property centerY normalized 0..1 vertical center.
 * @property zoom 1.0 = no zoom. Unchanged meaning for Auto Frame.
 * @property width normalized 0..1 box width, or null when the producer
 *   is Auto Frame (which does not model extent — its crop size is
 *   derived from the target aspect, not from the subject).
 * @property height normalized 0..1 box height, or null. See [width].
 * @property confidence 0..1 quality of the track at this instant.
 *   Defaults to 1f, which is the correct reading for Auto Frame (a
 *   keyframe it emits is, by construction, a sample it accepted) and
 *   keeps every existing consumer's behaviour identical.
 * @property isFound false when this entry is a FORWARD-FILLED
 *   placeholder for a frame where tracking failed. Consumers must
 *   decay or hold rather than treat it as a measurement — see
 *   `noExportParityGap` in the engine KDoc.
 */
data class CropKeyframe(
    val timeMs: Long,
    val centerX: Float,
    val centerY: Float,
    val zoom: Float = 1f,
    val width: Float? = null,
    val height: Float? = null,
    val confidence: Float = 1f,
    val isFound: Boolean = true
) {
    companion object {
        val Empty = emptyList<CropKeyframe>()
    }
}
```

**Why `isFound` matters and how to use it.** This is the single most important field for output
quality. When tracking fails at frame *t*, you have two choices and they look very different:

- **Forward-fill (hold last box).** The sticker freezes in place while the subject moves away, then
  snaps back. Visually this is *worse* than the sticker being absent, because the snap is a visible
  discontinuity.
- **Mark as not-found and let the overlay decay.** Hold for a short grace window (your
  `LOSS_RECOVERY_SAMPLES = 8` at 250 ms = 2 s is the right precedent — replicate the *idea*, not
  the duration; for a sticker 2 s is far too long), fade the overlay's alpha down, then fade back in
  when the track re-acquires with a smooth re-anchor.

`isFound` is what makes the second option expressible. **Do not skip it** — it is the difference
between "professional" and "broken" on any clip where the subject is occluded.

⚠️ **The `width`/`height` nullability is load-bearing.** Making them non-null with a default would
silently change Auto Frame: `interpolateKeys` currently ignores extent entirely, and a non-null
default would invite a future consumer to start reading it and change existing behaviour. `null`
means "no extent data", which is *true* for Auto Frame and forces the reader to handle it.

**Also update `interpolateKeys` to interpolate the new fields**, or a tracked box will snap between
keyframes instead of gliding — the classic "5 fps keyframes, 30 fps motion" stutter:

```kotlin
internal fun interpolateKeys(keys: List<CropKeyframe>, tsMs: Long): CropKeyframe {
    if (keys.isEmpty()) return CropKeyframe(0, 0.5f, 0.5f)
    if (tsMs <= keys.first().timeMs) return keys.first()
    if (tsMs >= keys.last().timeMs) return keys.last()
    for (i in 0 until keys.size - 1) {
        val a = keys[i]
        val b = keys[i + 1]
        if (tsMs in a.timeMs..b.timeMs) {
            val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
            val f = ((tsMs - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
            return CropKeyframe(
                timeMs = tsMs,
                centerX = a.centerX + (b.centerX - a.centerX) * f,
                centerY = a.centerY + (b.centerY - a.centerY) * f,
                zoom    = a.zoom    + (b.zoom    - a.zoom)    * f,
                // Extent interpolation is ONLY valid between two found
                // samples. Across a gap, both endpoints are placeholder
                // holds, and lerping between them would invent a smooth
                // motion through an occlusion that never happened. Hold
                // the last real box instead and let confidence carry the
                // story.
                width  = lerpNullableFound(a.width,  b.width,  f, a.isFound && b.isFound),
                height = lerpNullableFound(a.height, b.height, f, a.isFound && b.isFound),
                confidence = a.confidence + (b.confidence - a.confidence) * f,
                isFound = a.isFound && b.isFound
            )
        }
    }
    return keys.last()
}

private fun lerpNullableFound(a: Float?, b: Float?, f: Float, found: Boolean): Float? {
    if (a == null || b == null) return a ?: b
    if (!found) return a          // hold; do not invent motion across a gap
    return a + (b - a) * f
}
```

Note this **changes the return type of `interpolateKeys` from `Pair<Float,Float>` to
`CropKeyframe`.** That is a deliberate, small, compile-checked refactor: every existing call site
breaks and you fix each one. The alternative — a second `interpolateBox` function — is exactly how
the two paths drift apart. There is one call site today (`AiExportHooks.reframe`), so the cost is
minutes.

### 3.2 The tracking loop

Written against your actual pipeline, not abstractly:

```kotlin
/**
 * Tier A + Tier B hybrid tracking loop, driven by the SAME
 * VideoFramePipeline the export uses.
 *
 * Runs as [FrameOp] on the pipeline's IO thread. Returns nothing: the
 * trajectory is accumulated into [out] as a side effect, because the
 * pipeline's contract is bitmap-in/bitmap-out and it recycles whatever
 * we hand back.
 *
 * The `frameOp` deliberately returns `src` unchanged and passes
 * `copyAudio = true` to a throwaway temp file we delete afterwards.
 * We are abusing a transcoder as a decoder because it is the decoder
 * that already matches the export's frame space exactly — see the
 * module KDoc. The encode work is wasted but it is a hardware encoder
 * running at the same rate as the decode, so it roughly doubles the
 * analysis pass rather than costing an order of magnitude.
 *
 * TODO(perf): add a `decodeOnly` flag to VideoFramePipeline that skips
 * the encoder entirely. It is ~40 lines in `runLoop` and would halve
 * this pass. Worth doing before shipping, NOT worth blocking on.
 */
private class TrajectoryFrameOp(
    private val fullW: Int,
    private val fullH: Int,
    private val initialRoi: RectF,
    private val tierA: TierADetector?,      // null => Tier B only (user drew an exact box)
    private val tierB: RoiTracker,
    private val semanticStride: Int,
    private val out: MutableList<CropKeyframe>
) : VideoFramePipeline.FrameOp {

    private var frameIndex = 0
    private var lastBox: RectF = initialRoi
    private var lastConfidence = 1f
    private var missStreak = 0
    private var inited = false
    private var analysisBitmap: Bitmap? = null   // reused, never reallocated

    override fun process(src: Bitmap, ptsUs: Long, idx: Int): Bitmap {
        val timeMs = ptsUs / 1000L

        // ---- 1. Downscale once, reuse the buffer. Allocating a Bitmap per
        // frame is the single easiest way to OOM on a long clip.
        val small = downscaleInto(src, TRACK_WORK_WIDTH, analysisBitmap)
        analysisBitmap = small

        // ---- 2. Tier A on the stride: re-anchor semantically.
        val semanticTick = frameIndex % semanticStride == 0
        if (semanticTick && tierA != null) {
            val semantic = tierA.detect(small, lastBox)
            if (semantic != null) {
                // Re-init Tier B from the semantic box so it cannot
                // drift-follow a wrong patch indefinitely.
                if (needsReinit(semantic.roi, lastBox, semantic.confidence)) {
                    tierB.close()
                    inited = tierB.init(small, semantic.roi)
                }
                if (inited) lastBox = semantic.roi
                lastConfidence = semantic.confidence
                missStreak = 0
            }
        }

        // ---- 3. Tier B every frame.
        var found = false
        val refined = if (inited) tierB.update(small) else null
        if (refined != null && refined.confidence >= MIN_CONFIDENCE) {
            lastBox = clampNorm(refined.roi)
            lastConfidence = refined.confidence
            missStreak = 0
            found = true
        } else {
            missStreak++
            lastConfidence = decayedConfidence(lastConfidence, missStreak)
        }

        // ---- 4. Emit. Note: we emit EVERY keyframe, found or not.
        // Omitting the not-found entries would make the consumer's timeline
        // compress and the overlay would silently time-shift.
        out.add(
            CropKeyframe(
                timeMs = timeMs,
                centerX = lastBox.centerX(),
                centerY = lastBox.centerY(),
                zoom = 1f,
                width = lastBox.width,
                height = lastBox.height,
                confidence = lastConfidence,
                isFound = found
            )
        )

        frameIndex++
        return src          // unchanged: we are analysing, not rendering
    }

    /**
     * True when the semantic box has diverged enough from what Tier B is
     * currently tracking that continuing to refine would be refining the
     * wrong object. The 0.5 threshold is a fraction of the box size: below
     * that they are tracking the same thing, above it they are not.
     */
    private fun needsReinit(semantic: RectF, current: RectF, conf: Float): Boolean {
        if (conf < MIN_CONFIDENCE) return false
        val d = hypot(
            (semantic.centerX() - current.centerX()).toDouble(),
            (semantic.centerY() - current.centerY()).toDouble()
        ).toFloat()
        return d > 0.5f * max(current.width, current.height)
    }
}
```

**`MIN_CONFIDENCE` threshold.** `0.35f` is a reasonable start. Justify it in the code: below this,
the template residual is closer to random than to a match. **And make it a setting**, because
"confidently wrong" is target-dependent — a user tracking a basketball on a wooden floor will want
it higher than one tracking a face.

### 3.3 Failure handling — the decision table

This is where output quality is won or lost. Four distinct failures, four distinct responses:

| Failure | Detection | Response |
|---|---|---|
| **Brief occlusion** (hand passes behind something, 1–10 frames) | `update` returns null or low confidence, `missStreak` small | **Hold + coast.** Keep the last box, mark `isFound=false`, decay confidence. Tier B usually re-locks on its own because the search window is still centred correctly. Overlay: **hold with no visual change** for up to ~250 ms. |
| **Extended occlusion** (10–60 frames, out of frame) | `missStreak` grows | **Coast with decay.** Keep emitting held boxes, but ramp the overlay alpha toward 0 over ~400 ms. If Tier A fires and re-anchors, snap the box (the overlay is invisible, so the snap is unseen) and ramp alpha back up. **This is why the alpha ramp exists** — it's what makes re-acquisition invisible. |
| **Left frame permanently** | `missStreak` exceeds a large threshold (~2 s) with no semantic hit | **Stop tracking, end the trajectory.** Emit a final not-found entry, stop consuming CPU. The overlay stays hidden for the remainder of the clip. Do **not** keep coasting to the end of the video — you'd be animating a box that describes nothing. |
| **Permanent lock loss** (tracking the background, moving smoothly) | Confidence *high* but the semantic box diverges, or NCC residual is stable-but-low | **Force re-init from Tier A** — the `needsReinit` path above. This is the killer failure mode and the only reason Tier A must run continuously rather than once at init. |

One more: **the very first frame.** If `tierB.init` fails (box too small, uniform texture — a solid
white wall has no trackable features), do not emit a trajectory at all. Surface a Persian message:
`"نقطه انتخابی برای ردیابی مناسب نیست. کادر بزرگتری روی بخش پرجزئیات ویدیو بکشید."`
("The selected area is not suitable for tracking. Draw a larger box on a detailed part of the
video.") An honest refusal is far better than a trajectory full of noise.

---

## Phase 4 — Jitter reduction

### 4.1 You already have the right answer. Reuse it.

Your Phase 4 asks for EMA or a 1-D Kalman filter. **Do not implement either.** You already ship a
**1-Euro filter** in `ai/engines/SaliencyTrackerEngine.kt`, and it is strictly better than both for
this problem:

```kotlin
inline class OneEuroFilter(val smoothing: Float) {
    fun smooth(samples: List<Pair<Float, Float>>): List<Pair<Float, Float>>
}
```

Why it beats the alternatives you listed:

- **EMA has a fixed cutoff.** One constant. So you must choose: heavy smoothing that lags badly on
  fast motion, or light smoothing that doesn't remove jitter at rest. There is no setting that does
  both. That is a *fundamental* limitation, not a tuning problem.
- **1-D Kalman** is better but needs a process/measurement noise model per axis, and a badly tuned
  Kalman is worse than EMA. It also assumes a motion model; a hand tracking a moving object is not
  well modelled by constant-velocity.
- **1-Euro is adaptive by construction.** It raises the cutoff when the signal is moving fast
  (low lag) and lowers it when nearly still (heavy smoothing). That is *exactly* the requirement
  "smooth when still, responsive when moving" — which is precisely the jitter complaint.

**So: extract `OneEuroFilter` out of `SaliencyTrackerEngine` into a shared pure file, and use it for
the trajectory.** This also fixes a second-order problem: right now the filter lives inside an
`@Singleton` Android engine, so it is not unit-testable in your JVM suite. Moving it to
`model/OneEuroFilter.kt` as a pure top-level class makes it testable — consistent with your
`MEMORY.md` rule that *"new testable logic goes in a pure top-level function in `model/`."*

### 4.2 How to apply it to a box (not just a point)

The existing filter smooths `Pair<Float,Float>`. A box has **four** coupled signals, and smoothing
them independently is a mistake worth explaining:

```kotlin
/**
 * Smooth a tracked box trajectory.
 *
 * ## Why centre and size are filtered separately, and why size needs MORE
 * ## smoothing than centre
 *
 * A human eye is far more sensitive to a sticker's POSITION jitter than to
 * its SCALE jitter. An object that jitters in position reads as "broken
 * tracking"; the same object pulsing 3% in size reads as barely anything.
 *
 * So: filter the centre with a light setting (responsiveness matters — a
 * sausage-smoothed overlay visibly lags the subject) and the extent with a
 * heavier setting (cheap, and removes the scale pumping that CSRT does on
 * partial occlusion).
 *
 * ## Why the aspect ratio is preserved
 *
 * Filtering width and height independently lets the aspect drift, so the
 * sticker slowly stretches. Instead we filter the AREA-equivalent uniform
 * scale and reconstruct, so the user's chosen box shape is preserved
 * exactly. This also matches how an overlay is actually attached: uniform
 * scale + translation, which is what a 3x3 affine matrix can express
 * without shear.
 */
internal fun smoothTrajectory(
    raw: List<CropKeyframe>,
    positionSmoothing: Float,   // 0..1, higher = smoother/heavier
    scaleSmoothing: Float
): List<CropKeyframe> {
    if (raw.size < 3) return raw

    val posFilter = OneEuroFilter(positionSmoothing)
    val scaleFilter = OneEuroFilter(scaleSmoothing)

    // Centre trace.
    val centres = posFilter.smooth(raw.map { it.centerX to it.centerY })

    // Uniform-scale trace: sqrt(w*h) normalized. Guard the null extent
    // (Auto Frame entries carry none) by passing 0 and reconstructing.
    val scales = scaleFilter.smooth(
        raw.map { k ->
            val w = k.width ?: 0f
            val h = k.height ?: 0f
            val s = if (w > 0f && h > 0f) sqrt(w * h) else 0f
            s to s                       // filter both channels; we only use .first
        }
    )

    // Aspect trace, averaged over the window, is held constant — see KDoc.
    val meanAspect = raw.mapNotNull { k ->
        if (k.width != null && k.height != null && k.height > 0f) k.width / k.height else null
    }.average().toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f

    return raw.mapIndexed { i, k ->
        val (cx, cy) = centres[i]
        val s = scales[i].first
        if (s <= 0f) {
            k.copy(centerX = cx, centerY = cy)      // Auto Frame / no extent: centre only
        } else {
            val h = s / sqrt(meanAspect)
            val w = s * sqrt(meanAspect)
            k.copy(centerX = cx, centerY = cy, width = w, height = h)
        }
    }
}
```

**Critical ordering constraint: smooth BEFORE resampling to keyframes, not after.** The existing
`SaliencyTrackerEngine.process` gets this right — it smooths the full-rate trace, then emits
keyframes by sampling the smoothed trace. If you instead smoothed the already-decimated keyframe
list, you'd be filtering a signal that has already aliased. **Follow the existing order.**

### 4.3 The one case smoothing cannot fix

Smoothing filters noise. It cannot fix a **discontinuity** — and tracking failure produces exactly
that. If frame *t* is at `x=0.3` and frame *t+1* is at `x=0.7` because of a re-acquisition, no
amount of 1-Euro smoothing will make that transition invisible; the filter will simply smear it
over several frames, producing a fast swoop.

**The fix is at the overlay level, not the filter level: cross-fade through the re-anchor.** Detect
the discontinuity, and ramp the overlay's alpha to 0 *before* the jump and back up after:

```kotlin
/**
 * Detect a re-anchor discontinuity in a smoothed trajectory.
 *
 * A step larger than [maxStepNorm] of the frame in one keyframe interval
 * is not motion — at 30 fps that would be ~2.4 m/s across a phone screen,
 * which no handheld subject does. It is a re-acquisition, and it must be
 * hidden rather than smoothed.
 */
private fun isDiscontinuity(prev: CropKeyframe, next: CropKeyframe): Boolean {
    val dt = (next.timeMs - prev.timeMs).coerceAtLeast(1)
    val d = hypot(
        (next.centerX - prev.centerX).toDouble(),
        (next.centerY - prev.centerY).toDouble()
    ).toFloat()
    // per-100ms normalised speed
    return (d / dt * 100f) > maxStepNorm
}
```

Then the renderer, when it crosses such a boundary, multiplies the overlay alpha by a triangular
ramp centred on the jump (out over 150 ms, in over 150 ms). **This is the single highest-value
detail in Phase 4** and it is invisible in the trajectory data — it lives in the consumer. It is
also a strong argument for carrying `isFound`/`confidence` in the model rather than trying to infer
quality from geometry.

---

## Phase 5 — Overlay binding & rendering

Two consumers, and they are genuinely different problems. Your project has already paid the cost of
learning this (see your `MEMORY.md` note on `SurfaceView` and on the round-6 `saveLayer` breakage).

### 5.1 Live preview

**The binding.** Attach the overlay to the track by making its position a *function of the
playhead*, evaluated against the smoothed trajectory — precisely the way
`FreePositionTextOverlay` already evaluates `translationX` from `overlay.position` and
`currentTimeMs`.

```kotlin
/**
 * Resolve a tracked overlay's transform at [timeMs].
 *
 * Mirrors `FreePositionTextOverlay`'s existing coordinate convention exactly
 * (normalized 0..1 relative to the frame rect, applied as a
 * `graphicsLayer` translation relative to the layout CENTRE). Deviating
 * from that convention here — even "more correctly" — would make tracked
 * overlays disagree with static ones about where a coordinate is, which is
 * the kind of inconsistency users experience as the app being broken.
 */
internal fun trackedOverlayTransform(
    keyframes: List<CropKeyframe>,
    timeMs: Long,
    anchor: OverlayAnchor          // which part of the subject to attach to
): TrackedTransform {
    val k = interpolateKeys(keyframes, timeMs)
    // Anchor offset is a fraction of the box: 0.5 = centre, 0 = left/top.
    val ax = k.width?.let  { k.centerX - it / 2f + anchor.u * it } ?: k.centerX
    val ay = k.height?.let { k.centerY - it / 2f + anchor.v * it } ?: k.centerY
    return TrackedTransform(
        x = ax.coerceIn(0f, 1f),
        y = ay.coerceIn(0f, 1f),
        // Scale the overlay with the box when the user asked to, so a
        // sticker on a hand shrinks as the hand moves away.
        scale = if (anchor.scaleWithBox && k.height != null) {
            (k.height / anchor.referenceHeight).coerceIn(0.3f, 3f)
        } else 1f,
        alpha = confidenceAlpha(k.confidence, k.isFound),
        isVisible = k.isFound || k.confidence > MIN_VISIBLE_CONFIDENCE
    )
}

/** Which point of the tracked box the overlay is glued to. */
data class OverlayAnchor(
    val u: Float = 0.5f,          // 0..1 across the box
    val v: Float = 0.5f,          // 0..1 down the box
    val scaleWithBox: Boolean = false,
    val referenceHeight: Float = 0.1f   // the box height at attach time
)
```

**Rendering it — and the trap.** The overlay must be a Compose layer *outside* the `AndroidView`
holding the `TextureView`. Critically:

> ⚠️ **Do NOT put a Compose draw modifier on the `AndroidView`'s chain.** Your `MEMORY.md` records
> that the round-6 `saveLayer` on that chain broke the Play button. And separately: **any
> View-level effect (`blur`, `renderEffect`, `saveLayer`) over the video is a no-op** because the
> video composites outside the View raster via `SurfaceView`. Your `EditorScreen` deliberately uses
> `R.layout.player_view_texture` to get a `TextureView` for exactly this reason.

The consequence for this feature: **a tracked *blur/mosaic* mask in the live preview is not
achievable with a View-level effect over the video surface.** For preview you have two honest
options:

- **Tracked text / sticker / emoji in preview: yes** — it's a sibling Compose layer, composited
  over the video by the window, no View effect involved. Ship this.
- **Tracked blur/mosaic in preview: export-only.** Show a static placeholder mask or a chip, and
  bake the real thing on export. This is the same precedent as `sharpness`/`vignette`/`grain`,
  which your notes already record as export-only. Be consistent with it rather than fighting the
  surface.

**Performance of the preview binding.** Evaluating `interpolateKeys` per frame is a linear scan
over the keyframe list. At 5 fps keyframes over a 60 s clip that's 300 entries, scanned once per
frame — trivial, but it *is* on the main thread inside the 33 ms your 33 ms poller already owns.
Two cheap fixes if you measure a problem:
- Binary-search the interval instead of the linear scan (the list is time-ordered by construction).
- Cache the resolved index and only re-search when `timeMs` leaves the current interval — the
  common case is "same interval as last frame", which is O(1).

### 5.2 Export

**The clean path: reuse `VideoFramePipeline` + `FrameOp`, exactly like `reframe()` does.** This is
not a compromise — it is better than both alternatives you listed:

- **vs. FFmpeg `overlay` with animated coordinates:** you'd need FFmpeg (you don't have it), a
  filter graph with one `overlay` per keyframe interval (hundreds of filters, or a `sendcmd` file
  that is fragile and hard to generate correctly), and a *second* decode path whose frame space
  would have to be proven identical to the tracker's. No.
- **vs. a Media3 `TextureOverlay`:** elegant, and `ClipMotionWashOverlay` shows you can animate
  per-frame via `getOverlaySettings(presentationTimeUs)`. But it forces the overlay to be a
  **static bitmap** — `BitmapOverlay.getBitmap` may be re-queried but the *content* is not
  re-rendered per frame, and `TextOverlay` is the Compose text rasterizer, which is not available
  inside the GL thread. So a *text* overlay with tracked motion would have to be pre-rasterized
  once and only transformed. That works for scale+translate, but not for anything whose content
  depends on time (karaoke, word reveal).

So: **`FrameOp` is the right tool**, and it has a decisive extra advantage — **it can render the
overlay with the same code the preview uses.** Your `SubtitleBurnIn` / `CaptionRenderer` already
solve "rasterize a `TextOverlay` to a Bitmap with Persian shaping and RTL"; reuse it. One renderer,
two consumers, guaranteed parity.

```kotlin
/**
 * Bake a tracked overlay into the video, reusing the exact machinery
 * `reframe()` already uses.
 *
 * The overlay is rasterized once per frame at the tracked position via the
 * SAME renderer the live preview uses ([renderOverlayToBitmap] wraps your
 * CaptionRenderer / SubtitleBurnIn), so preview and export cannot disagree
 * about Persian shaping, RTL, or stroke geometry — only about position,
 * and position comes from one shared trajectory.
 *
 * Cost: one rasterization at overlay size (not frame size) plus one
 * per-pixel alpha blend over the overlay's bounding box. Both are
 * proportional to the sticker, not the 4K frame, so a 400x200 sticker on
 * a 4K frame touches 0.4% of the pixels.
 */
private suspend fun bakeTrackedOverlay(
    context: Context,
    segment: RenderSegment,
    overlay: TextOverlay,
    keyframes: List<CropKeyframe>,
    anchor: OverlayAnchor,
    srcUri: String,
    onProgress: (Float) -> Unit
): String = withContext(Dispatchers.IO) {
    val info = VideoFramePipeline.probe(context, Uri.parse(srcUri))
        ?: throw AiExportException("Cannot read video metadata for Motion Tracking export.")
    val tmp = newTemp("track-${segment.clipId}")

    // Rasterize ONCE: the overlay's own pixels don't change per frame for a
    // static sticker. Only its transform does. This turns a per-frame text
    // layout pass into a per-frame bitmap blit — a large win, and it is
    // what makes a text overlay affordable at 60 fps.
    val stamp = renderOverlayToBitmap(context, overlay, info.displayWidth, info.displayHeight)
        ?: throw AiExportException("Could not rasterize the overlay.")

    try {
        val res = VideoFramePipeline.transcode(
            context,
            VideoFramePipeline.Request(
                srcUri = Uri.parse(srcUri),
                dstFile = tmp,
                outWidth = info.displayWidth,
                outHeight = info.displayHeight,
                frameOp = VideoFramePipeline.FrameOp { src, ptsUs, _ ->
                    val t = trackedOverlayTransform(
                        keyframes, segment.toClipLocalMs(ptsUs / 1000L), anchor
                    )
                    drawTrackedStamp(src, stamp, t)
                },
                copyAudio = true,
                onProgress = { d, tot -> onProgress(d.toFloat() / tot.coerceAtLeast(1)) },
                isCancelled = { false }
            )
        )
        when (res) {
            is VideoFramePipeline.Result.Done -> Uri.fromFile(tmp).toString()
            is VideoFramePipeline.Result.Cancelled -> throw AiExportException("Export cancelled.")
            is VideoFramePipeline.Result.Failed ->
                throw AiExportException(res.cause.message ?: "Motion Tracking render failed.")
        }
    } catch (t: Throwable) {
        runCatching { tmp.delete() }
        if (t is AiExportException) throw t
        throw AiExportException(t.message ?: "Motion Tracking render failed.")
    }
}
```

The blend itself — with the alpha ramp that §4.3 requires:

```kotlin
/**
 * Composite [stamp] onto [frame] at the tracked transform.
 *
 * `frame` is the pipeline's bitmap and MUST be modified in place and
 * returned — returning a new instance makes the pipeline recycle the
 * original, and allocating a fresh full-res bitmap per frame is the one
 * thing that will OOM this exporter on a 4K source.
 *
 * The alpha ramp is applied HERE rather than in the trajectory because it
 * is a *presentation* concern: the same trajectory should be able to drive
 * a hard cut or a cross-fade without changing the tracked data.
 */
private fun drawTrackedStamp(
    frame: Bitmap,
    stamp: Bitmap,
    t: TrackedTransform
): Bitmap {
    if (!t.isVisible || t.alpha <= 0.01f) return frame
    val canvas = android.graphics.Canvas(frame)
    val paint = android.graphics.Paint().apply {
        isFilterBitmap = true
        alpha = (t.alpha * 255f).roundToInt().coerceIn(0, 255)
    }
    // Compute the destination rect in frame pixels from the normalized
    // transform, then draw the stamp scaled into it. drawBitmap with a
    // dest Rect is a hardware-accelerated blit — no per-pixel Kotlin.
    val w = stamp.width * t.scale
    val h = stamp.height * t.scale
    val left = t.x * frame.width - w / 2f
    val top = t.y * frame.height - h / 2f
    canvas.drawBitmap(
        stamp, null,
        android.graphics.RectF(left, top, left + w, top + h),
        paint
    )
    return frame
}
```

**The coordinate-space proof you must write a test for.** The tracker normalizes against
`displayWidth × displayHeight` (rotation-corrected). `bakeTrackedOverlay` composites on
`frame.width × frame.height`, which is the pipeline's rotation-corrected output. **These are the
same space — assert it.** A regression test that pins `normalize(trackerSpace) ==
normalize(exporterSpace)` for a 90°-rotated source is worth more than any amount of integration
testing, because this is precisely the class of bug that survives casual manual testing and then
shows up on one user's phone.

**Export ordering.** Where does this land relative to the existing passes? After `reframe` (frame
geometry must be final before you attach something to a pixel position in it) and before the text/
subtitle burn-in is fine either way, but be *explicit*:

```
prepareSourceUri():
  1. matting composite      (changes the picture's content)
  2. auto-frame reframe     (changes the frame's geometry)   ← tracked boxes are now in final space
  3. motion-track overlay   (attaches to final-space pixels) ← NEW
  ...  then the normal Media3 effect chain runs on the result
```

Rationale, and it matters: a tracked overlay attached *after* a reframe is anchored to final output
pixels. Attached *before*, it would be cropped away by the reframe — a sticker pinned to a face
that vanishes because Auto Frame cropped to that face is a bug users will report as data loss.

---

## 6. Proposed file map

Keeping the footprint honest — this is meant to be small:

| Action | File | Notes |
|---|---|---|
| **extend** | `model/AiToolSettings.kt` | Widen `CropKeyframe` (§3.1); add `MotionTrackSettings` |
| **extend** | `model/VideoClip.kt` | Add `motionTrack: MotionTrackSettings = MotionTrackSettings.Identity` |
| **extend** | `model/EditorUiState.kt` | Track draft ROI + selected overlay id |
| **extend** | `export/RenderSegment.kt` | Add `motionTrack` + carry overlay refs |
| **extend** | `export/AiExportHooks.kt` | Add the tracked-overlay stage; change `interpolateKeys` signature (§3.1) |
| **new** | `model/TrackingGeometry.kt` | `videoFrameRect`, `toNormalized`, `clampNorm`, `lerpNullableFound` — **pure, unit-tested** |
| **new** | `model/OneEuroFilter.kt` | Extracted from `SaliencyTrackerEngine`, made pure & testable (§4.1) |
| **new** | `model/TrajectorySmoothing.kt` | `smoothTrajectory`, `isDiscontinuity` (§4.2, §4.3) |
| **new** | `ai/tracking/RoiTracker.kt` | Interface (§2.1) |
| **new** | `ai/tracking/CsrtRoiTracker.kt` | OpenCV impl + JNI decls (§2.1) |
| **new** | `ai/tracking/KcfRoiTracker.kt` | Fallback impl |
| **new** | `ai/engines/MotionTrackingEngine.kt` | `AiToolEngine`, id `"motiontrack"` — hybrid loop (§3.2) |
| **new** | `ui/screens/editor/RoiSelectorOverlay.kt` | The composable (§1.2) |
| **new** | `ui/screens/editor/MotionTrackPanel.kt` | Panel, modelled on `AutoFramePanel` |
| **new** | `src/main/cpp/roi-tracker-jni.cpp` | JNI bridge, modelled on `whisper-jni.cpp` |
| **modify** | `ui/screens/editor/EditorScreen.kt` | Host the ROI overlay layer + evaluate tracked transforms |
| **modify** | `ui/screens/editor/BottomToolbar.kt` | New dock entry |
| **modify** | `app/build.gradle.kts` | OpenCV dep + CMake target |

---

## 7. Sequencing, and what to build first

Ordered so that each step is independently verifiable — and specifically so that the two highest
risks are retired earliest.

**Step 0 — retire the OpenCV risk before writing any UI.**
Add the dependency, probe for `TrackerCSRT`, and run one tracker over one clip in a throwaway
Activity. If CSRT isn't in the artifact, you need to know now, not after building a panel around
it. *This is the single largest unknown in the plan.*

**Step 1 — the pure geometry, with tests.**
`model/TrackingGeometry.kt` and `model/TrajectorySmoothing.kt`. Per your `MEMORY.md`, JVM tests
with no mocking libraries, `org.junit.Assert.*`, backtick names naming the regression. Cover:
letterbox math for all four source/canvas aspect relations; the round-trip
`normalized → px → normalized`; rotation-corrected normalization for 90°/270°; smoothing a step
discontinuity; `lerpNullableFound` holding across a gap. **These tests are the deliverable that
makes the rest safe to build.**

**Step 2 — the ROI selector, standalone.**
`RoiSelectorOverlay` over a hardcoded bitmap. Verify against a synthetic test pattern with known
landmarks — the only way to be sure the mapping is right rather than plausible. No engine yet.

**Step 3 — the trajectory producer.**
`MotionTrackingEngine` with Tier B only (user draws an exact box). Emits `CropKeyframe`s. Log the
trajectory as JSON and eyeball it before anything renders it.

**Step 4 — export first, preview second.**
Bake the overlay via `FrameOp` and watch the file. Do this *before* the preview, because the export
is the harder constraint and the one that defines correctness. Your `RenderSegment` KDoc already
notes the precedent: matting's export is ahead of its preview.

**Step 5 — the preview binding.**
The Compose layer, the anchor picker, the alpha ramp. Constrained by whatever Step 4 proved.

**Step 6 — Tier A.** Add the semantic re-anchor, multi-person selection reuse, and the
occlusion table of §3.3. This is what makes a *hand* trackable rather than just a user-drawn patch.

---

## 8. Risks, in the order they will actually hurt you

1. **`TrackerCSRT` may not be in the Maven artifact.** Verified-as-unknown. Step 0 exists solely to
   settle it. Fallback: vendor the contrib module, or the pure-Kotlin path.
2. **Rotated sources.** `VideoFramePipeline` emits rotation-corrected frames; `MediaMetadataRetriever`
   does not. Mixing the two silently offsets every box by 90°. Normalize only against
   `SourceInfo.displayWidth/displayHeight` and write the regression test in Step 1.
3. **Mat allocation in the per-frame path.** OpenCV `Mat`s are native and the Java GC will not save
   you. Reuse one `Mat` per frame; a fresh `Mat` per frame in a 3000-frame clip leaks enough to
   OOM a mid-range device. Same discipline as your existing `analysisBitmap` reuse.
4. **The `FrameOp` return contract.** Returning a *new* bitmap makes the pipeline recycle the
   original — fine, but it means a full-res allocation per frame. **Always modify and return
   `src`.** Your existing exporters already do this; a new one that forgets will OOM.
5. **Keyframe density.** Emitting at 5 fps (Auto Frame's rate) is too coarse for a sticker — you
   will see visible stepping on fast motion. For overlay binding, emit **every tracked frame**
   (30 fps). This makes the trajectory list ~6× larger; because boxes are 8 floats it is still
   only ~10 KB per minute, but it does mean `EditorStateJson` and the Room column carry more. Check
   the serialization path before committing to it.
6. **Confidence is derived, not measured** (§2.2). Budget time for tuning it, and expect to expose
   the threshold to the user.
7. **Preview cannot show a tracked blur/mosaic** (§5.1, the `SurfaceView`/`TextureView` constraint).
   Decide now that it is export-only, or scope a real renderer. Do not discover this late.

---

## 9. Direct answers to the questions as asked

| Your question | Answer |
|---|---|
| **Pause + capture a high-quality frame** | Use `VideoFramePipeline.sampleFrame` for a quick freeze; use `transcode` + early-stop for an exact frame. Downscale to ~720p for the UI — the tracker works at 360p anyway, so a full-res hold is never needed. §1.1 |
| **Draggable/resizable ROI** | New `RoiSelectorOverlay` composable, patterned on the existing `FreePositionTextOverlay`. `detectDragGestures` + `change.consume()`; corner handles in a Canvas. §1.2 |
| **Screen → normalized coordinates** | Normalize against the **letterboxed `frameRect`**, not the container. `videoFrameRect` + `toNormalized`/`toContainer`, pure and unit-tested. §1.3 |
| **KCF vs CSRT vs MOSSE vs Nano** | **CSRT**, as Tier B, on an ROI crop (~3–6 ms). KCF only as the no-OpenCV fallback for scale-stable targets. MOSSE too weak. Nano adds a model dependency for less accuracy. §1.2 |
| **Frame feed** | **`VideoFramePipeline.transcode` with a `FrameOp`** — hardware decode, exact PTS, rotation-corrected, and identical to the export's frame space. Not `cv::VideoCapture`, not per-sample `MediaMetadataRetriever`. §2.3 |
| **Downscaling** | Yes: 360p for Tier A, an ROI crop (box × 1.6, ~192 px) for Tier B. Faster *and* more accurate, because high-frequency texture is what destabilises correlation filters. §2.4 |
| **Trajectory model** | Widen the existing `CropKeyframe` rather than forking a new type; add `width?`/`height?`/`confidence`/`isFound`. §3.1 |
| **Handling failures** | Four failures, four responses — hold, coast-with-decay, stop, force-reinit. Confidence is derived (NCC template residual + motion prior) because OpenCV exposes none. §2.2, §3.3 |
| **Jitter reduction** | **Reuse your existing 1-Euro filter** — it adapts cutoff to speed and beats both EMA and a hand-tuned 1-D Kalman. Extract it to `model/` so it becomes unit-testable. Smooth at full rate, *then* decimate to keyframes. Handle re-anchor discontinuities with an alpha ramp, not with smoothing. §4 |
| **Preview binding** | Evaluate the transform from the playhead via `interpolateKeys`; render as a Compose sibling layer with the same normalized-coordinate convention as static overlays. Do **not** add a View-level effect over the video. Tracked blur/mosaic is export-only. §5.1 |
| **Export burn-in** | `FrameOp` reuse — *not* FFmpeg, not a Media3 `TextureOverlay`, because the overlay must be rasterized by the same Persian-aware renderer the preview uses. Order: after reframe. §5.2 |

---

*Everything above was cross-checked against the source tree at the paths cited. Where a claim is an
inference rather than a verified fact — notably the `TrackerCSRT` artifact contents, and the
benchmark ms/frame figures — it is labelled as such. The two claims most worth verifying first are
`Class.forName("org.opencv.tracking.TrackerCSRT")` and the rotated-source coordinate identity in
§5.2.*
