# AI media decode — frame sampling, lifecycle, subtitles

Narrow reference for the on-device AI tools («هوشمند» sheet) and the Whisper subtitle path.
Load this instead of the whole memory when working on `ai/media/`, `ai/engines/`, `ai/ocr/`, `ai/upscale/`,
`ai/audio/`, or `ai/subtitle/`.

---

## 1. Frame sampling

**Never sample AI frames with `MediaMetadataRetriever.getFrameAtTime(t, OPTION_CLOSEST_SYNC)`.** That option
returns the closest **sync frame**, i.e. the nearest keyframe, so every timestamp inside one GOP decodes to
the same picture — a 10 fps matting pass over a 2 s GOP wrote 20 identical masks, each labelled with the
*requested* timestamp rather than the picture's own PTS, so masks lagged the frame and the cutout slid off the
person. It also carries no display rotation.

`OPTION_CLOSEST` fixes the picture but re-seeks and re-decodes from the previous keyframe on **every** sample —
minutes of extra work for a 600-sample pass.

Use `ai/media/SequentialFrameSampler.kt`: it decodes the stream once, in order, and emits the frame whose PTS
crosses each requested interval. O(frames), not O(samples × GOP). Contract:

- `Frame.timeMs` is the decoded picture's own PTS, **rebased so the first frame is 0** (clip-local — what the
  matting sidecar and `CropKeyframe` both expect).
- Display rotation is applied, so a portrait phone video arrives upright.
- `maxSide` downscales before the caller sees the frame.
- Returns `0` on failure so callers keep their legacy fallback; it must never throw.

Consumers: `MediaPipeMattingEngine`, `SaliencyTrackerEngine` (Auto Frame / Reframe), `TesseractOcrEngine`.
A new analysis engine **must** use it. Matting sidecar keys are the picture's PTS; export resolves masks via
`export/MaskIndexMath.nearestIndex`.

---

## 2. Decoder lifecycle — the crash rules (2026-10-20)

Two tombstones from tapping the «هوشمند» tools produced four rules. They are structural, not defensive
programming: violating any of them is a process abort, not an exception.

### 2.1 Release EOS without rendering it

`MediaCodec.releaseOutputBuffer(index, true)` on a buffer flagged `BUFFER_FLAG_END_OF_STREAM` makes the
`ImageReader` hand out an `Image` with no backing gralloc buffer. `Image.getPlanes()` builds its planes
**lazily**, so the failure surfaces on the first `planes[0]` access inside
`VideoFramePipeline.imageToBitmap`:

```
Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity for nullptr pointer: 1
  in call to NewDirectByteBuffer from
  android.media.ImageReader$SurfaceImage.nativeCreatePlanes(int, int, long)'
  #18 VideoFramePipeline.yuv420ToBitmap
  #22 VideoFramePipeline.imageToBitmap
  #26 SequentialFrameSampler.sample
  #31 TesseractOcrEngine.analyzeVideo
```

CheckJNI aborts the process, so **no `try/catch` in Kotlin can catch it**. Always
`releaseOutputBuffer(st, !eos)`. `AiExportHooks` and `OverlayCompositeRenderer` always did this; the two hot
loops (`SequentialFrameSampler.sample`, `VideoFramePipeline.runLoop`) did not, which is why OCR, matting, auto
frame, reframe, motion tracking and upscale all died.

### 2.2 Never hold an `Image` across AI work

`onFrame` is a `suspend` callback that runs inference for up to seconds; `Request.frameOp` blocks for hundreds
of ms. The reader is created with 3 slots, so keeping the `Image` checked out across that work starves the
decoder and the driver eventually reclaims the buffer underneath you.

Order is: **convert → `image.close()` → then run the AI op.** Every conversion path copies pixels into an
`IntArray`/`ByteArray`, so the `Bitmap` is independent of the `Image`.

### 2.3 `acquireNextImage()` blocks

It does **not** return null when the queue is empty — it blocks forever. Gate every acquire on a
`pendingImages` counter incremented once per rendered (non-EOS) output. On `IllegalStateException`, re-sync
**both** `pendingImages` and `ptsQueue` to zero, otherwise the EOS condition (`ptsQueue.isEmpty()`) can never
be reached and the loop stalls.

### 2.4 Never call GL from a worker thread without a context

```
signal 11 (SIGSEGV) in libGLESv1_CM.so (glGetString+52)
  #07 ai.upscale.DeviceCapabilityDetector.queryGlesMajor
  #15 ai.upscale.EsrganUpscalePipeline.run
```

`GLES20.glGetString` with no current EGL context dereferences a null dispatch table. A native fault is not an
exception, so the `runCatching` around it was useless. Read
`packageManager.systemAvailableFeatures.maxOfOrNull { it.reqGlEsVersion }` instead — plain metadata, no
context, `(packed shr 16) and 0xFFFF` for the major version, fall back to 1 (which keeps the FAST engine).

All four rules are pinned by `AiFrameDecodeLifecycleTest`.

---

## 3. Whisper subtitles (Persian accuracy round, 2026-10-20)

Seven independent defects were found in the shared decode path; all are fixed.

1. **`logprob_thold` must stay `-1.0`** (upstream) in `whisper-jni.cpp`. whisper.cpp accepts the **last rung of
   the temperature ladder unconditionally**, so a stricter threshold pushed normal Persian windows
   (avg_logprobs ≈ −0.6…−0.9) onto the 0.8/1.0 rungs and produced fluent *invented* Persian.
2. **`temperature_inc = 0.2`** for every `AccuracyMode` (FAST 1, BALANCED 5, ACCURATE 8 beams).
3. **The prompt must be natural text.** Whisper *imitates* `initial_prompt`; it does not obey it. An
   instruction block ("قواعد: …") becomes output. `ai/audio/WhisperPrompt.PRIMER` is natural Persian prose and
   is also sent as the cloud `prompt` field.
4. **Anti-aliased resampling.** `AudioResampler` runs a 4th-order Butterworth
   (`Biquad.cascade(..., lowPass = true)`, cutoff `toRate * 0.45`) before decimation. The old nearest-neighbour
   decimation folded everything above 8 kHz back into the speech band.
5. **Level conditioning.** `ai/audio/WhisperAudioConditioner` applies an 80 Hz high-pass and normalises
   active-frame RMS to −20 dBFS (boost ≤ +18 dB, cut ≥ −9 dB), with a 99.5-percentile peak guard so one click
   cannot cancel the boost for a whole take.
6. **Window planning.** `ai/audio/WindowPlanner` cuts near each 30 s mark in the quietest 10 ms frame
   (search ±600 ms, min fill 55%) instead of on a fixed grid that sliced words in half.
7. **Cross-window context.** The previous window's tail (≤ 140 chars) is carried as context, gated on mean
   confidence ≥ 0.55.

**Honest ceiling.** The offline model is Whisper **Small f16 only** (Medium was removed 2026-09-21; its ggml
file sits unused at `app/ai-models/whisper-ggml-medium/`). Small is the Persian accuracy ceiling.
`TranscriptCorrector` can only repair confusables from a closed candidate list — it cannot turn
"ایتجایزش" into "آپدیت". Decode quality is the only lever.

---

## 4. Visibility rules

`HighlightRange` renders via `HighlightRangeStrip`. `ComingSoonChipIds` is empty — a locked chip would be an
unreachable panel. Matting compositing and Voice Clean are **export-only**: do not claim they preview.
