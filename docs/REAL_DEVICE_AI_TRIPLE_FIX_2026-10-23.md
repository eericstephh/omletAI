# Background Remove on a real phone - the three stacked failures

**Project:** AI Creator Studio (`com.aicreatorstudio.app`)
**Device:** Xiaomi 2107113SG (`vili_global`), Android 14, arm64-v8a, serial
`c3f38b01`
**Build:** release (`minifyReleaseWithR8`), `versionName 1.4.0`
**Date:** 2026-10-23

---

## 0. Summary

Background Remove (MediaPipe selfie-multiclass matting) had never once
completed on this phone. Three independent defects sat on top of each other,
and each one only became visible after the previous one was fixed:

| # | Layer | Symptom | Status |
|---|-------|---------|--------|
| 1 | R8 / Flogger | `ExceptionInInitializerError` inside `Graph.<clinit>` - the segmenter was never created | **fixed, proven** |
| 2 | Native / ImageReader | `SIGABRT` in `nativeCreatePlanes` - the process died on the first decoded frame | **fixed, proven** |
| 3 | Kotlin init order | `NullPointerException` in `probeAiToolModelState` - the editor died on launch, at random | **fixed, proven** |
| 4 | Sampling plan | 600 frames at ~17 fps - 7 minutes for a 36 s clip | **fixed, proven**: 358 frames, 10 fps, ~4 min |

The matting pass now runs to completion on the device and the panel reports
`تکمیل شد` / `حذف پس‌زمینه آماده است`.

---

## 1. R8 renamed the logging backend (release-only)

**Evidence** (release crash buffer):

```
java.lang.IllegalStateException: no caller found on the stack for: j8.c
    at com.google.mediapipe.framework.Graph.<clinit>
```

`j8.c` is `com.google.common.flogger.FluentLogger` in the release mapping.
Flogger resolves its caller by **walking the stack and matching class names**;
R8 renamed it, so the walk found nothing and threw during class
initialisation. `Graph.<clinit>` is reached from `TaskRunner.create`, so
**every** MediaPipe feature failed, and the panel implied the model was
missing.

**Fix** - `app/proguard-rules.pro`:

```proguard
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
```

**Proof** - `app/build/outputs/mapping/release/mapping.txt`:

```
com.google.common.flogger.FluentLogger -> com.google.common.flogger.FluentLogger:
com.google.common.flogger.AbstractLogger -> com.google.common.flogger.AbstractLogger:
renamed count = 0
```

**Guard** - `R8KeepRulesTest.google flogger is kept so its stack walk can find the caller`.

See also `docs/R8_PROTOBUF_MEDIA_PIPE_BREAK_2026-10-22.md` for the protobuf
half of the same release-only class of bug.

---

## 2. `Image.getPlanes()` aborts the process (native, uncatchable)

With the segmenter finally created, the run moved to frame sampling and died
three seconds later:

```
Fatal signal 6 (SIGABRT) in tid 16879 (DefaultDispatch)
Abort message: 'JNI DETECTED ERROR IN APPLICATION:
  non-zero capacity for nullptr pointer: 1 in call to NewDirectByteBuffer'
  ... ImageReader$SurfaceImage.getPlanes
  ... VideoFramePipeline.imageToBitmap      (u5.u.c)
  ... VideoFramePipeline.imageToBitmap      (u5.u.d)
  ... SequentialFrameSampler.sample         (u5.i.a)
```

Facts established from the log, not guessed:

- the clip is **HEVC**, decoder `c2.qti.hevc.decoder`;
- the image was **1080x1920** and matched the reader exactly, so the existing
  size guard (the previous fix) passed;
- the codec renders `crop(0, 0, 1079, 1919)` into a 1080x1920 surface
  (`android._video-scaling = 1`);
- the reader was created with `ImageFormat.YUV_420_888, maxImages = 3`, whose
  buffers do **not** carry `USAGE_GPU_SAMPLED_IMAGE` (measured, §2.2).

A native abort cannot be caught, so no `try/catch` can help - the only defence
is to not make the call.

### 2.1 The policy

`VideoFramePipeline.imageToBitmap(image)` on **API 29+**:

1. `image.hardwareBuffer` -> `Bitmap.wrapHardwareBuffer(buffer, null)` ->
   `copy(ARGB_8888, false)` (GPU readback). No planes are touched.
2. If any step fails, throw a **catchable** `PipelineException` instead of
   falling back to `getPlanes()`.

On API < 29 the plane path stays (there is no `HardwareBuffer` API).

### 2.2 What the device actually did

```
W/VideoFramePipeline: wrapHardwareBuffer failed: usage flags must contain USAGE_GPU_SAMPLED_IMAGE.
W/FrameSampler: sequential sampling failed: decoder frame 1080x1920 is not readable
                through a hardware buffer; refusing the plane path that aborts the process
```

So on this device the fast sequential path is **unavailable**, and the
engine's existing `MediaMetadataRetriever` fallback takes over:

```
if (sampledSequentially == 0) { /* legacy retriever path */ }
```

The important difference is that this is now a **clean fallback instead of a
process kill** - and the run completed:

```
I/MediaPipeMattingEngine: matting pass: frames=600 decode=0ms infer=194204ms
                          write=14575ms src=1080x1920 interval=59
```

The panel then showed `تکمیل شد` and the preview badge
`حذف پس‌زمینه آماده است`.

---

## 3. A declaration-order race that killed the editor at launch

After the two fixes above the app started dying at random **before** the panel
opened:

```
FATAL EXCEPTION: DefaultDispatcher-worker-3
java.lang.NullPointerException: Attempt to invoke virtual method
  'java.lang.Object java.util.concurrent.ConcurrentHashMap.put(...)'
  on a null object reference
    at EditorViewModel.probeAiToolModelState(EditorViewModel.kt:447)
    at EditorViewModel.restoreAiState(EditorViewModel.kt:402)
```

`init` started `viewModelScope.launch(Dispatchers.IO) { restoreAiState() }`
while `aiToolAvailabilityCache` was declared **after** the `init` block. Kotlin
initialises properties in declaration order, so the IO coroutine could reach
`aiToolAvailabilityCache[toolId] = available` before the field existed. It is
timing-dependent, which is exactly why it survived this long: the UI thread
only has to lose the race once.

**Fix** - the three fields touched by `restoreAiState` are declared **before**
`init`, with the crash in the comment.

**Guard** - `EditorViewModelInitOrderTest` (source-order test):
`init` still launches on `Dispatchers.IO`, the three fields precede `init`,
and `restoreAiState` still probes `"matting"` (so the fix cannot be "achieved"
by deleting the probe).

---

## 4. Sampling plan and UI language

Measured cost of the passing run: **7 minutes for a 36 s clip**.

- 600 frames x ~0.32 s inference = 194 s;
- the retriever fallback adds ~0.33 s per frame of decoding;
- `intervalMs = durationMs / MAX_FRAMES` produced **59 ms (~17 fps)**, although
  the code comment has always said "~10 fps".

Two focused changes:

1. `MIN_SAMPLE_INTERVAL_MS = 100` - the sampling step now has the floor the
   comment promised (359 frames instead of 600 for that clip, -40 % runtime).
2. the result summary is Persian, like every other engine's summary:
   `پس‌زمینه در N فریم حذف شد (1080×1920، حدود 10 فریم در ثانیه)`.

Verified in the shipped dex: the Persian literal is present (`classes.dex`
offset 8682716) and `Person matting applied` is gone (0 hits).

### 4.1 The final run, on the final build

Same phone, same 36 s clip, same flow (force-stop -> home -> tap the
capability -> scroll -> tap the run button), then `logcat` until the pass
finished:

```
I/MediaPipeMattingEngine: matting pass: frames=358 decode=0ms infer=133598ms
                          write=7836ms src=1080x1920 interval=100
```

- `interval=100` - the ~10 fps floor is in effect (was 59 ms);
- 358 frames instead of 600;
- **253 s end to end** instead of 420 s (-40 %);
- 133.6 s of that is inference (0.37 s/frame), the rest is the retriever
  fallback decoding + PNG writes.

Panel text, read off the device with `uiautomator`:

```
حذف پس‌زمینه آماده است
تکمیل شد
پس‌زمینه در 358 فریم حذف شد (1080×1920، حدود 10 فریم در ثانیه)
```

The Persian sentence renders correctly in the RTL panel, mixed digits and the
`1080×1920` fragment included.

---

## 5. Files

| File | Change |
|------|--------|
| `app/proguard-rules.pro` | Flogger keep rule + `-dontwarn`. |
| `app/src/main/java/.../ai/media/VideoFramePipeline.kt` | `hardwareBitmap()`; API 29+ never touches `getPlanes()`; catchable failure. |
| `app/src/main/java/.../viewmodel/EditorViewModel.kt` | `aiToolAvailabilityCache`, `cachedAutoUpscaleProfile`, `esrganSpecCache` moved above `init`. |
| `app/src/main/java/.../ai/engines/MediaPipeMattingEngine.kt` | `MIN_SAMPLE_INTERVAL_MS`, Persian summary. |
| `app/src/test/java/.../ai/R8KeepRulesTest.kt` | Flogger keep-rule guard. |
| `app/src/test/java/.../viewmodel/EditorViewModelInitOrderTest.kt` | Declaration-order guard (3 tests). |

---

## 6. Verification status

**Proven on the device (`c3f38b01`):**

- no `no caller found on the stack`, no `ExceptionInInitializerError`;
- no `SIGABRT` / `non-zero capacity for nullptr pointer`;
- no launch `NullPointerException`;
- `matting pass: frames=358 ... interval=100 ... src=1080x1920` - the pass
  completed on the final build;
- UI: `حذف پس‌زمینه آماده است`, `تکمیل شد`, and the Persian summary
  `پس‌زمینه در 358 فریم حذف شد (1080×1920، حدود 10 فریم در ثانیه)`.

**Not proven:** export with a matting sidecar (the composition path) on this
device, and the fast sequential sampler on a device whose reader buffers do
carry `USAGE_GPU_SAMPLED_IMAGE`.

**Tooling note:** the automated flow was interrupted twice by the phone itself
- the screen turned off and later a call arrived - and both times the app was
still alive with the result intact afterwards, which is itself a small
confirmation that the pass does not depend on the foreground.

---

## 7. Known limitations / next

1. **The fast path is missing on this phone.** `ImageReader` (YUV_420_888,
   public API) allocates buffers without `USAGE_GPU_SAMPLED_IMAGE`, so the
   hardware-buffer route is closed and every frame costs the retriever's
   ~0.33 s. Two follow-ups: an `ImageFormat.PRIVATE` reader (GPU-only buffers,
   sampled by the GPU) or a `SurfaceTexture` + `glReadPixels` reader. Both need
   a device test before shipping.
2. **Inference is ~0.32 s/frame.** `ANALYSIS_MAX_SIDE = 720` is far above the
   model's 256x256 input; lowering the analysis bound to ~320 costs nothing in
   mask fidelity and shrinks the per-frame upload/post-process work.
3. **Matting is still export-only in the preview** (the panel says so) - the
   composition happens in the export pre-renderer.
