# The export frame source that no surface could deliver — 2026-10-24

Device: Xiaomi 2107113SG (`vili_global`, `c3f38b01`), Android 14 (API 34),
1080×2400. Test clip: HEVC 1080×1920, 35.852 s, decoded by
`c2.qti.hevc.decoder`. App: `com.aicreatorstudio.app`, release build.

This document records what was measured, what was changed because of it, and
what is still unproven. It is the continuation of
`docs/REAL_DEVICE_AI_TRIPLE_FIX_2026-10-23.md` (matting) and covers the **export**
path.

---

## 1. Symptom

After the matting crash was closed, the export still failed on this phone.
The first failure was not even a failure — the app **died** while preparing
the export, because a native abort cannot be caught:

```
JNI DETECTED ERROR IN APPLICATION: non-zero capacity for nullptr pointer: 1
  in call to NewDirectByteBuffer
  from android.media.ImageReader$SurfaceImage.nativeCreatePlanes(int,int,long)
```

Once that was turned into a catchable error, the export reported:

```
export +674ms: FAILED: Export failed at PREPARING: Video processing failed
```

No frame had been encoded; both failures happen before the first encode.

---

## 2. Three attempts at the surface path, all measured

The export's frame source was a decoder writing into an `ImageReader`
(`YUV_420_888`, 3 slots), the same shape the matting sampler used.

### 2.1 Default reader usage

`ImageReader.newInstance(w, h, YUV_420_888, 3)` requests **no** usage flags, so
the buffers are allocated from the producer's (codec's) GPU-only usage.

| Step | Result |
|---|---|
| `Image.getHardwareBuffer()` | non-null, but `Bitmap.wrapHardwareBuffer` **refused** it: `usage flags must contain USAGE_GPU_SAMPLED_IMAGE` |
| `Image.getPlanes()` | **native abort** in `nativeCreatePlanes` (`non-zero capacity for nullptr pointer: 1`) |

So both readback routes were closed, and one of them killed the process.

### 2.2 Explicit `USAGE_GPU_SAMPLED_IMAGE or USAGE_CPU_READ_OFTEN`

The allocation was changed to request the two usages a readback needs. The
device accepted the reader and logged its usage:

```
I/VideoFramePipeline: frame reader 1080x1920 with usage 0x103 (GPU_SAMPLED|CPU_READ)
```

With that usage the hardware buffer came back **null**, and allowing the plane
path aborted the process again with the identical JNI error. Measured crash
(`logcat`, 16:38:01):

```
F/eatorstudio.app: JNI DETECTED ERROR IN APPLICATION: non-zero capacity for nullptr pointer: 1
```

**Conclusion:** on this driver no `ImageReader` usage produces a frame that can
be read on the CPU. The problem is not a missing flag; it is the surface path
itself.

### 2.3 The fix: MediaCodec byte-buffer output

`MediaCodec.configure(format, null, null, 0)` — a **null Surface** — makes the
decoder hand out its own `ByteBuffer`s instead of rendering into a buffer
queue. `getOutputImage()` still returns an `Image`, but its planes are ordinary
direct buffers over the codec output, not `SurfaceImage` planes; nothing calls
`nativeCreatePlanes`.

The decoder is asked for the only colour format `getOutputImage()` supports:

```kotlin
srcFormat.setInteger(
    MediaFormat.KEY_COLOR_FORMAT,
    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
)
decoder.configure(srcFormat, null, null, 0)
```

Measured, on the same clip:

```
I/VideoFramePipeline: decoder video/hevc in byte-buffer mode (coded 1080x1920)
I/VideoFramePipeline: decoder output: video/raw 1080x1920 color=0x7f420888
```

`0x7f420888` = `COLOR_FormatYUV420Flexible`. This is the first configuration in
which a decoded frame of this clip was actually read on this phone.

---

## 3. The second bug behind it: a short last chroma row

The first byte-buffer export failed within one frame:

```
export +674ms: FAILED: Export failed at PREPARING: Video processing failed
W/VideoFramePipeline: java.nio.BufferUnderflowException
    at java.nio.DirectByteBuffer.get(DirectByteBuffer.java:281)
```

Cause: a byte-buffer image ends at the last byte the codec wrote. The final
chroma row is therefore **shorter than `rowStride`**, while the conversion read
a full `rowStride` row. Surface images never showed this (their planes are
full-size), so the old code was correct for the old source and wrong for the
new one.

`readPlaneRow` now clamps every read to the plane's own `limit()` and zero-fills
the remainder:

```kotlin
private fun readPlaneRow(src: ByteBuffer, offset: Int, dst: ByteArray) {
    if (offset < 0 || offset >= src.limit()) { dst.fill(0); return }
    src.position(offset)
    val n = minOf(dst.size, src.limit() - offset)
    src.get(dst, 0, n)
    if (n < dst.size) dst.fill(0, n, dst.size)
}
```

Y rows are zero-filled (black), U/V rows are neutralised by the caller (128 →
no colour shift) because it checks `uvx < row.size`.

---

## 4. The export now succeeds on the phone

Measured run (`logcat`):

```
I/VideoExportEngine: export begin: videoSegments=1 audioSegments=0 durationMs=35852
I/VideoExportEngine: export +243198ms: SUCCEEDED bytes=3458608 durationMs=35852 \
  fileName=AI_Project___________________20261024_164850.mp4
```

UI state after the run (dumped with `uiautomator`, not inferred from the log):

| Element | Text |
|---|---|
| Title | «خروجی آماده شد» |
| File | `AI_Project___________________20261024_164850.mp4` |
| Size | 3.5 MB |
| Duration | 35.9s |
| Action | «پخش ویدئو» |

The file itself was pulled and parsed (no `ffprobe` on this machine, so the MP4
boxes were read directly):

| Check | Value |
|---|---|
| Path | `/sdcard/Movies/AICreatorStudio/AI_Project___________________20261024_164850.mp4` |
| Bytes | 3,458,608 (matches the log exactly) |
| `ftyp` | `mp42` |
| `mvhd` | timescale 10000, duration 358052 → **35.805 s** |
| Video sample entry | `avc1`, `0x0780 × 0x0438` = **1920×1080** |
| Audio | `mp4a` present (`esds`, AAC) |

So the export is not merely "reported success": it produced a playable MP4 of
the right length, resolution and codec.

A **repeat export** on the same build produced the same file byte for byte
(`bytes=3458608`, `export +240540ms`) — the pipeline is deterministic for the
same project and mask.

The **final APK** (matting sampler moved to byte-buffer decode, §8.1) was then
exported again, after Background Remove had recomputed its mask with the new
sampler:

```
export +265243ms: SUCCEEDED bytes=3397824 durationMs=35852 \
  fileName=AI_Project___________________20261024_172611.mp4
```

Pulled and parsed: `ftyp mp42`, `mvhd` → **35.805 s**, `avc1` →
**1920×1080**, `mp4a`/`esds` present. Panel: «خروجی آماده شد», 3.4 MB, 35.9s.
The byte count differs from the earlier run because the **mask content**
changed (it was regenerated by the new sampler), not because the muxer
changed: same duration, same resolution, same codecs.

### Cost, honestly

243 s for a 35.8 s clip (358 frames) ≈ **226 ms/frame**. This is CPU-bound, not
I/O-bound: per frame the pipeline does YUV→ARGB (Kotlin), matte composite, a
rotation copy, and ARGB→YUV. The split per stage has **not** been profiled, so
no claim is made about which stage dominates. The final build measured 265 s
for the same clip with a freshly recomputed matte, so the matte composite is
part of the cost but its share is not isolated.

---

## 5. What changed, file by file

| File | Change |
|---|---|
| `ai/media/VideoFramePipeline.kt` | `transcode` decoder in byte-buffer mode; new `runLoop` that dequeues outputs, converts with the new public `codecImageToBitmap`, then releases; the reader, `readerHandoff`, `ptsQueue` and `pendingImages` dequeue machinery deleted; `yuv420ToBitmap` reads rows through `readPlaneRow`; `imageToBitmap`'s surface policy (hardware buffer first, planes only when allowed) kept for the one remaining surface consumer. |
| `export/AiExportHooks.kt` | `BgVideoSampler` decodes byte buffers (no reader), converts via `codecImageToBitmap`; reader field and close call removed. |
| `export/OverlayCompositeRenderer.kt` | `OverlayVideoSampler` does the same; `ImageReader` import removed. |
| `ai/media/SequentialFrameSampler.kt` | **Also byte-buffer now**, as a second step after the export was proven: the reader, `pendingImages`, `ptsQueue` and the guarded surface conversion are gone. Pictures that do not land on a sample instant are released **without conversion** (that is the saving), and each sampled picture is converted and its buffer released before the suspending `onFrame` runs. |

The foreground-service fix from the same day (`dataSync` on API 29–34,
`mediaProcessing` on API 35+, plus the three permissions) is what lets the
service start at all on Android 14; it is documented in
`ExportServiceForegroundTypeTest` and is not repeated here.

---

## 6. Tests

- **New:** `ByteBufferDecodeTest` (5 tests) pins the new contract:
  - the transcode decoder is configured without a `Surface`;
  - both export samplers configure `(trackFormat, null, null, 0)` and never
    create an `ImageReader`;
  - `COLOR_FormatYUV420Flexible` is requested and `codecImageToBitmap` is
    reachable from both;
  - `readPlaneRow` clamps to `limit()` and `yuv420ToBitmap` uses it;
  - the export path no longer calls the surface-specific `imageToBitmap`.
- **Updated (they pinned the removed design):**
  - `AiFrameDecodeLifecycleTest` — both loops are now guarded for "never render
    an output", `releaseOutputBuffer(st, false)` and conversion through
    `codecImageToBitmap`; the pipeline's close-before-`frameOp` check follows
    the `img` alias, and the sampler is pinned to no `ImageReader` /
    `acquireNextImage` at all.
  - `DecoderSizeGuardTest` — the export sources are now guarded for the
    opposite property (no reader, byte-buffer conversion); the reader-size and
    dimension guards remain pinned for the matting sampler.
  - `ExportRequestResolverTest` — expects
    `android:foregroundServiceType="dataSync|mediaProcessing"` and both
    per-type permissions.

Full suite: **3187 tests, 0 failures**.

## 7. Build

```
.\gradlew.bat :app:assembleRelease --offline        → BUILD SUCCESSFUL
apksigner verify --verbose                          → Verifies (v2 scheme)
zipalign -c -P 16 -v 4                              → Verification successful
```

Artefact: `AICreatorStudio-v1.4.0-release-2026-10-24-exportfix.apk`
(99,130,155 bytes), installed with `adb install -r -d` → `Success`, app starts
with no crash entry in the crash buffer.

---

## 8. The matting sampler followed (verified 2026-10-24)

### 8.1 Background Remove is now ~2x faster

`SequentialFrameSampler` fed matting, Auto Frame and OCR, and it used the same
reader that cannot be read on this phone. It therefore fell back to
`MediaMetadataRetriever` for **every** sample (~0.33 s each), which is why a
36 s clip took 253 s. It now uses the byte-buffer mechanism proven by the
export, with two extra properties:

- a picture that does **not** land on a sample instant is released without ever
  becoming a `Bitmap` — a 100 ms pass converts roughly one of every three
  pictures;
- the sampled picture is converted and its output buffer released **before**
  the suspending `onFrame` (inference) runs.

Measured on the phone, same project, same 358 samples:

| Run | Log line | Wall clock |
|---|---|---|
| Before (reader + retriever fallback) | `matting pass: frames=358 decode=0ms infer=133598ms write=7836ms` | 253 s |
| After (byte-buffer decode) | `matting pass: frames=358 decode=0ms infer=112675ms write=7268ms` | **120 s** |

The engine's own **decode** timer reads `0` in both runs — the retriever
fallback was never inside that bucket — so the wall clock, not a timer, is the
evidence. The sampler announced the new path: `sequential sampler: video/hevc
byte-buffer decode`, with no fallback line.

UI after the pass: «تکمیل شد» and «پس‌زمینه در 358 فریم حذف شد (1080×1920،
حدود ۱۰ فریم در ثانیه)». Crash buffer: empty.

### 8.2 What is still unverified

- **Overlay and background-video samplers.** Their byte-buffer change is
  compile- and test-verified but was **not exercised on the device**: the test
  project has no overlay clip and no VIDEO-mode background, so no export ran
  through those two samplers. They use the same mechanism that is proven in
  `transcode` and now in the matting sampler, but "same mechanism" is not a
  measurement.
- **Only one clip and one mask** have been exported end to end (HEVC 1080×1920,
  16:9 canvas), in three runs. H.264 sources, 4K sources, clips with rotation
  metadata, multi-segment projects and overlay/bg-video projects are untested in
  this configuration.
- **Instrumented tests still cannot run**: MIUI refuses to install the debug
  build (`INSTALL_FAILED_USER_RESTRICTED` for `com.aicreatorstudio.app.debug`),
  and BlueStacks is x86_64/Android 9 while the APK is arm64-v8a. All of the
  above is `adb`-driven release-build evidence, not JUnit-on-device evidence.
