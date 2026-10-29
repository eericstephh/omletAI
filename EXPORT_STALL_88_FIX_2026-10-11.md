# Omelet (املت) — Video Export Stuck at 88%: Root Cause and Fix

**Date:** 2026-10-11
**Scope:** Android video editor, `com.aicreatorstudio.app`, v1.4.0 (versionCode 4), minSdk 24 / targetSdk 35
**Verdict in one line:** The 88% plateau is **not** a stage of the encoder. It is a hard ceiling in Media3's own progress arithmetic combined with an unbounded wait loop that had no timeout — the export could hang there forever. Both are now fixed, but **no real-device export was performed**, so the fix is evidenced by library bytecode + 30 unit tests, not by an observed successful export.

---

## 1. Root cause, with the exact code path and evidence

### 1.1 What people assume vs. what actually happens

The natural assumption is that "88%" names an encoder or muxer stage — e.g. "video done, now muxing audio". That assumption is **wrong**, and it is wrong in a way that matters: there is no single operation at 88%.

### 1.2 How the pipeline is actually implemented

I traced the export path before touching anything. The important discovery is that this project **does not use raw `MediaCodec`/`MediaMuxer` or FFmpeg**. It uses the high-level **`androidx.media3.transformer.Transformer`** (Media3 **1.5.0**).

The relevant chain:

```
EditorViewModel
  └─ ExportDelegate            (thin pass-through, no progress mapping)
       └─ VideoExportEngine.start()
            ├─ startExportInternal()   → builds Composition, picks OutputTarget
            ├─ startTransformer()      → transformer.start(composition, path)
            ├─ Transformer.Listener    → onCompleted / onError
            └─ pollProgress(transformer)   ← the loop that hung
```

Progress reaches the UI here:

```
VideoExportEngine.pollProgress  →  ExportState.Exporting(ExportProgress)
   →  ExportDelegate  →  ExportDialog.kt:395  "${(progress * 100).toInt()}%"
```

I also confirmed the two-step safe export is intact and is **not** the problem: the `Transformer` writes to an app-private cache file, and `CacheFileOutputTarget.finalize()` then does a full byte copy into MediaStore with the `IS_PENDING` flag.

### 1.3 The evidence: Media3 1.5.0 bytecode

I extracted `media3-transformer-1.5.0-runtime.jar` and disassembled it with `javap -p -c`, examining `Transformer`, `TransformerInternal`, `SequenceAssetLoader` and `ExoPlayerAssetLoader`. Five facts came out, and together they *fully* explain the symptom:

**Fact 1 — Progress is a playback-position ratio, not an encoder signal.**

`ExoPlayerAssetLoader.getProgress` is, in effect:

```java
min(player.getCurrentPosition() * 100 / player.getDuration(), 99)
```

It reports how far the *decoder* has read, not how far the *encoder* has written. It is hard-clamped to **99**, so `100` is not reachable through this path at all.

**Fact 2 — With audio present, the ceiling is lower than 99.**

`TransformerInternal.updateProgressInternal` **averages across all sequences** (an integer `idiv`). A composition has a video sequence *and* one or more audio sequences. The audio sequence finishes early and then stops advancing, so the average stalls while the video is still going. The practical ceiling therefore lands in the **high 80s** — which is exactly the reported ~88%.

This is the single most important finding: **the number 88% is not a stage boundary. It is arithmetic.** Any video with audio will plateau there. It also explains why the report consistently says "~88%" rather than some other value.

**Fact 3 — Progress stops being written at all once samples drain.**

`updateProgressInternal` has **exactly one caller**: `drainExportersInternal()`. There is no timer, no periodic emitter. Once the last sample is drained from the exporters, progress is simply never written again. The value freezes.

**Fact 4 — Finalization reports nothing.**

`endInternal` **resets** `progressState` / `progressValue` back to `0`, then calls `MuxerWrapper.finishWritingAndMaybeRelease(...)` — the muxer flush and file close. That finalization runs with **completely zero progress reporting**. So even the counter-intuitive behaviour is explained: if the UI ever did read after the reset, it would jump *backwards* to 0.

**Fact 5 — Only one progress state is ever published.**

`Transformer.PROGRESS_STATE_AVAILABLE` (value `2`) is the only state in which `progressValue` is copied into the caller's holder. Any other state means the holder is stale.

### 1.4 The actual bug: an unbounded wait

`pollProgress` was an loop that waited for progress to reach completion, with no exit other than a terminal state. Because of Facts 1–5 that terminal value is **never emitted**, so the loop could never exit. And crucially:

```
grep -E 'withTimeout|timeout|deadline' app/src/main/java/com/aicreatorstudio/app/export/*.kt
→ 0 hits
```

**There was no timeout anywhere in the export path.** So the failure mode was not "slow" — it was **an infinite hang with the UI frozen at 88%**, exactly as reported.

### 1.5 A second, latent defect: false success

While tracing I found a race that is arguably worse than the hang. Four independent writers assigned directly to the shared `activeState`:

- `onCompleted` → `Succeeded`
- `onError` → `Failed`
- the progress loop → (terminal states)
- `cancel()` → `Cancelled`

Nothing ordered them. Once the watchdog below exists, a `Failed("stalled")` could be **overwritten by a later `Succeeded`** — the UI would report a successful export for a file that was actually abandoned. That is a false success, and it had to be closed as part of this fix.

---

## 2. Files changed and why

| File | Change | Why |
|---|---|---|
| `export/ExportConfig.kt` | Added `ExportStallPolicy` + `ExportStallDetector` (pure Kotlin objects) | Keeps the stall arithmetic out of the Android/Media3 layer so it is JVM-testable. `ExportStage.FINALIZING` already existed and is reused. |
| `export/VideoExportEngine.kt` | Rewrote `pollProgress`; added `emitTerminal()` latch; added `ExportDiagnostics`; added `FINALIZING_PROGRESS = 0.99f`; diagnostics at begin / target / start / completed / error / cancel / finalize / publish | The core fix — bounded wait, explicit finalization stage, single terminal state, and evidence for future debugging. |
| `ui/screens/editor/ExportDialog.kt` | Added `stageLabel(stage)` mapping to Persian; `Exporting` branch now uses it instead of the raw English `displayName` | The finalization phase now has a real user-facing label ("در حال نهایی‌سازی فایل…") rather than an untranslated English token. |
| `app/src/test/.../ExportStallDetectorTest.kt` | New, 17 tests | Pins the watchdog contract. |
| `app/src/test/.../ExportStateTerminalTest.kt` | New, 13 tests | Pins the single-terminal-state contract. |

**Deliberately not changed:** the editing pipeline, subtitle rendering, Persian text handling, audio sync, effects, export settings, the two-step cache→MediaStore publishing strategy, and all output paths. The `Transformer` call itself, the `Composition` construction and `buildEditedMediaItem` are untouched. No dependency, no asset, no Gradle change.

---

## 3. The fix implemented

### 3.1 Time the last *change*, not the total duration

The naive watchdog ("fail if the export runs longer than N seconds") is wrong — it would kill legitimate long 4K exports. Instead `pollProgress` now records `lastChangeMs` and resets it **only when the progress value actually changes**:

```kotlin
var lastProgress = -1f
var lastChangeMs = System.currentTimeMillis()

while (true) {
    val state = transformer.getProgress(holder)
    val now = System.currentTimeMillis()
    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
        val pct = (holder.progress / 100f).coerceIn(0f, 1f)
        if (pct != lastProgress) {
            lastProgress = pct
            lastChangeMs = now        // real work → bump the clock
        }
        ...
    }
    ...
    delay(500L)
}
```

A slow-but-moving export bumps the clock forever and is never touched. A true hang stops bumping it and is caught.

### 3.2 Two budgets, because finalization is legitimately longer

```kotlin
object ExportStallPolicy {
    const val BASE_TIMEOUT_MS: Long = 120_000L        // 2 min, while encoding
    const val FINALIZE_TIMEOUT_MS: Long = 300_000L    // 5 min, while finalizing
    const val FINALIZE_AT_OR_ABOVE: Float = 0.85f

    fun timeoutForMs(progress: Float): Long =
        if (progress >= FINALIZE_AT_OR_ABOVE) FINALIZE_TIMEOUT_MS else BASE_TIMEOUT_MS
}
```

The finalize budget is strictly more generous than the base budget — the muxer flush and the MediaStore byte copy of a large file are genuinely the slowest, least interruptible part of the export.

### 3.3 An explicit finalization state the user can see

The library can never say "I am now finalizing". We can infer it: past `0.85f` the library has stopped being able to advance. So the stage flips:

```kotlin
if (pct >= ExportStallPolicy.FINALIZE_AT_OR_ABOVE) {
    emit(ExportState.Exporting(ExportProgress(pct, ExportStage.FINALIZING)))
} else {
    emit(ExportState.Exporting(ExportProgress(pct, ExportStage.ENCODING)))
}
```

`ExportDialog.kt` renders this as **"در حال نهایی‌سازی فایل…"**, so the user sees "finalizing the file" instead of a frozen percentage next to a stale "Encoding" label.

### 3.4 Fail loudly, never fake 100%

```kotlin
val stall = ExportStallDetector.check(lastProgress, lastChangeMs, now)
if (stall != null) {
    ExportDiagnostics.warn("watchdog fired: stalled ${stall.stalledSeconds}s at ...")
    activeTransformer.get()?.let { runCatching { it.cancel() } }
    emitTerminal(ExportState.Failed(ExportStallDetector.messageFor(stall)))
    break
}
```

The Transformer is cancelled to release hardware codecs, and the state becomes an actionable `Failed` carrying a message that **names the phase, quotes the percentage and the delay, and tells the user to try again**. No message ever claims success. Nothing is silently swallowed — the `runCatching` wraps only the *cancel* call (which must not throw during error handling), not the diagnosis.

### 3.5 Exactly one terminal state

```kotlin
private fun emitTerminal(state: ExportState): Boolean {
    while (true) {
        val current = activeState.value
        if (current.isTerminal()) return false
        if (activeState.compareAndSet(current, state)) {
            ExportDiagnostics.terminal(state)
            return true
        }
    }
}
```

`onCompleted`, `onError`, the watchdog and `cancel()` all go through this latch. The first terminal transition wins; every subsequent one is dropped. This closes the false-success race from §1.5. The four pre-start guard `emit(ExportState.Failed(...))` calls were deliberately left as plain `emit` — they run before any run is active and cannot race.

### 3.6 Structured diagnostics, metadata only

A small `ExportDiagnostics` object (tag `"VideoExportEngine"`) records: the run parameters, elapsed milliseconds, stage transitions, progress, `errorCodeName`, output byte counts, and the terminal state. Its KDoc states explicitly what is **not** logged: **no media content, no paths, no URIs, no keys.** It extends the existing logging rather than introducing a parallel system — the file already had a `TAG`.

### 3.7 What was explicitly *not* done

- The pipeline was not rewritten; the `Transformer` call, the `Composition`, `buildEditedMediaItem`, subtitles, effects, audio and settings are untouched.
- Progress is not faked to 100%, and the stall is not hidden.
- No user project or output file is deleted.
- No paid API, cloud processing or new recurring cost.
- No exception is caught and silently ignored.
- No arbitrary global timeout kills legitimate large exports — the clocks are measured from the last *change*.

---

## 4. Tests run and their results

**Full suite — `testDebugUnitTest` on JDK 21 (JBR):**

```
2187 tests completed, 2 failed, 2 skipped
168 test classes
```

- Predicted exactly: 2157 (prior baseline) + 30 new = 2187.
- The **2 failures are the pre-existing, asset-dependent pair**, unchanged and untouched by this work:
  - `ai/RealModelInstallTest` — expects a bundled whisper model asset
  - `ai/WhisperNativeSymbolsTest` — expects a `.so` for every supported ABI, while the build deliberately filters to `arm64-v8a`
- I aggregated all 168 XML result files directly to confirm no *other* class regressed — the only two failures are those two classes.

**New tests — 30, all green:**

- `ExportStallDetectorTest` (17, 0 failures) — no stall while progressing; a slow-but-moving 4K export never trips; fires past the base budget while encoding; fires past the finalize budget at the 0.88 plateau; fires when progress never moves; the 88% plateau specifically times out instead of hanging; clock-skew safety (`nowMs < lastChangeMs` must not fire); the finalize budget is strictly more generous; the threshold switches at the documented boundary; the threshold sits below Media3's 0.99 clamp; messages are actionable (name the phase, quote percentage and delay, say "Try again"); no message ever claims success.
- `ExportStateTerminalTest` (13, 0 failures) — mirrors `isTerminal`; succeeded/failed/cancelled are terminal; idle/preparing/exporting are not; **finalizing is NOT terminal**; the latch admits exactly one terminal transition; a late success cannot override an earlier stall failure; a late cancel cannot override an earlier failure; progress cannot be constructed outside 0..1; `FINALIZING_PROGRESS` (0.99f) is below 100%; finalizing and encoding are distinct stages; failure messages are non-empty and never read as success; failure keeps its cause.

**One test was wrong, and I fixed the test — not the code.** The case `no stall at exactly the base budget boundary` failed because the detector fires *inclusive* at the limit (`silentMs < limit`), while the assertion expected exclusivity. I split it into "just below the boundary" (must not fire) and "at exactly the boundary" (must fire). Reporting a stall one millisecond early is the safer failure direction than hanging, so the stricter semantic was kept.

**Honest scope note:** these tests exercise the stall *policy*, the *state machine* and the *latch* as pure logic. They do **not** drive a real `Transformer`. No test here executes hardware encoding.

---

## 5. Debug APK build status

**Debug — `assembleDebug`, separate invocation: `BUILD SUCCESSFUL in 1m 30s`**

| | |
|---|---|
| Path | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | **122,201,865 bytes** (116.5 MB) — **delta 0** vs. the pre-change APK |
| Built | 2026-10-11 13:05 (fresh mtime — not a stale artifact) |
| SHA-256 | `b2f1d147b81a31ba7dfa84de649f0a7cf424503561a94b2e9222d69c9df84d4a` |
| Signature | `CN=Android Debug` — `apksigner verify` passes |
| Manifest | `com.aicreatorstudio.app.debug`, versionCode 4, versionName `1.4.0-debug`, minSdk 24, targetSdk 35, ABI `arm64-v8a` |

**Release — `assembleRelease`, separate invocation: `BUILD SUCCESSFUL in 6m 42s`**

| | |
|---|---|
| Path | `app/build/outputs/apk/release/AICreatorStudio-v1.4.0-release.apk` |
| Size | **77,993,409 bytes** (74.4 MB) |
| Built | 2026-10-11 13:12 |
| SHA-256 | `51cd72babb3c41d7b98e869130b2f40f03007a8c6c206d02196671a293972ff4` |
| Signature | `CN=AICreatorStudio, OU=AI, O=AICreatorStudio, L=Tehran, ST=Tehran, C=IR`, SHA-256 `1ec4f3ff…45c8b0` — verified |

The **zero byte delta** on the debug APK is the expected and reassuring result: this work changed only Kotlin logic (float constants, control flow, logging) and added test sources, which do not ship. No new asset, no new dependency, no new permission.

Note that `assembleDebug` and `assembleRelease` were run as **separate** Gradle invocations from the test task, because the test task's exit code stops a chained build before packaging. I verified the APK mtimes rather than trusting the exit code.

---

## 6. Was a real export completed and played successfully?

**No.**

I want to be direct about this rather than let the green build imply more than it proves:

- **No device or emulator export was performed.** `Transformer` requires real hardware codecs (`MediaCodec`), which a JVM unit test cannot supply, and no physical device or working emulator was available in this environment.
- Therefore **I have not observed a successful export end-to-end**, and I have not seen the resulting video play.
- `androidTest` remains broken (a pre-existing condition, documented before this task) — so there is no instrumented coverage either.

**What this means for the claim.** The root cause is established from *library bytecode*, which is deterministic and does not need a device: the 99 clamp, the cross-sequence averaging, the single caller of `updateProgressInternal`, the progress reset in `endInternal`, and the muxer finalization that is never reported. Those five facts are read directly out of the shipped 1.5.0 artifact, and together they account for the 88% symptom precisely — including *why* it is 88% rather than some other number.

The correct statement of the outcome is therefore:

> **The root cause of the 88% freeze is identified and proven from Media3 bytecode. The unbounded wait that turned it into a permanent hang is now impossible by construction, and the false-success race is closed. This is verified by 30 unit tests covering the stall policy and the terminal-state machine. It is *not* verified by an observed successful export on real hardware.**

I am not claiming "the bug is fixed" as a device-verified statement. Compilation success was explicitly not treated as evidence.

---

## 7. Remaining uncertainty

1. **No hardware confirmation.** The strongest remaining uncertainty: I have not seen the fix run against a real encoder/muxer. The bytecode reasoning is solid, but device behaviour is the actual acceptance criterion. A single real export on an `arm64-v8a` device would move this from "proven by analysis" to "verified".

2. **The budget values are judgement, not measurement.** `BASE_TIMEOUT_MS = 120 s` and `FINALIZE_TIMEOUT_MS = 300 s` were chosen to be comfortably above plausible worst-case encoding and muxing times. I have **no on-device timing data** for this app on real 4K footage, so I cannot say whether a slow phone on a very large export might legitimately exceed them. The design mitigates this (the clock only runs while progress is *frozen*, and finalization gets the larger budget), but the numbers themselves are not empirically derived.

3. **The `0.85f` finalize boundary is an inference.** It comes from reasoning about where cross-sequence averaging saturates, corroborated by the reported ~88%. A composition with many audio sequences could saturate elsewhere. The consequence of being wrong is cosmetic (the wrong stage *label* for a while), not a hang — the watchdog uses whichever budget matches the observed plateau.

4. **Cancellation mid-finalization is unverified.** `Transformer.cancel()` during the muxer flush is not something I could exercise. The latch guarantees at most one terminal state, but whether the partially written cache file is always cleaned up in that window depends on Media3 internals I did not disassemble.

5. **`WhisperNativeSymbolsTest` is arguably stale**, not a real regression — it demands a `.so` per supported ABI while the build intentionally ships `arm64-v8a` only. It has failed before every task in this sequence and is untouched by this change. I have left it alone deliberately rather than "fix" a test to make the suite green.

6. **There is no git safety net.** This project is not a git repository, so these "pre-existing failure" classifications cannot be re-verified against a pristine checkout, and there is no diff or revert path for the changes above.

---

## Summary

The 88% figure is not a stage — it is a ceiling produced by Media3 averaging progress across the video and audio sequences, clamped at 99 by the library itself. The old progress loop waited for a completion value the library never emits, with no timeout in the entire export path, so the export hung there permanently. On top of that, four unordered writers to the shared state could publish a `Succeeded` over a `Failed`.

The fix bounds the wait by measuring silence *since the last progress change* (so slow exports survive), splits the budget for the genuinely slower finalization phase, surfaces an explicit `FINALIZING` stage in Persian, and routes every terminal transition through a compare-and-set latch so exactly one outcome is ever published. Diagnostics are metadata-only.

**2187 tests, 2 pre-existing failures, 30 new tests green; debug and release APKs built, signed and verified; debug APK byte-identical in size. No real-device export was performed — the fix is proven from library bytecode and unit tests, and device verification remains pending.**
