# Whisper Audit — Offline Transcription Compatibility (2026-10-11)

Scope: `com.aicreatorstudio.app` v1.4.0, minSdk 24 / targetSdk 35, ABI `arm64-v8a` only.
Method: source inspection + ELF binary analysis of the **shipped** `libwhisper.so`, not assumption.

---

## 1. What the implementation actually is

The repository does **not** use a prebuilt whisper.cpp or a wrapper library. It vendors the
source and compiles it:

| Piece | Reality |
|---|---|
| **whisper.cpp version** | **v1.7.6**, pinned in `app/ai-models/models.lock.json` (`sha256 166140e9…`, 7,307,730 B tarball) |
| **Vendored tree** | `app/src/main/cpp/whisper/` (whisper.cpp + whisper.h) and `app/src/main/cpp/ggml/` |
| **Build system** | `app/src/main/cpp/CMakeLists.txt`, driven by AGP `externalNativeBuild` + CMake 3.22.1, NDK `30.0.16138531` |
| **JNI bridge** | `app/src/main/cpp/whisper-jni.cpp` — compiled **into `libwhisper.so`** (there is no separate `libwhisper-jni.so`) |
| **Kotlin surface** | `ai/WhisperNative.kt` (4 `external fun`s), `ai/WhisperEngine.kt` (the `TranscriptionEngine` impl) |
| **GPU** | Disabled: `cparams.use_gpu = false` |

### Initialization path (exact order)

```
EditorViewModel.runGeneration (EditorViewModel.kt:3428)
  └─ SubtitleGenerator.initializeModelFor(spec)
       ├─ WhisperEngine.initializeModelFor(spec)          [Dispatchers.IO, initMutex]
       │    ├─ 1. runNativeSelfTest()  →  System.loadLibrary("whisper") + WhisperNative.selfTest()
       │    ├─ 2. modelInstaller.installWithManifest()     → bundled-copy OR 465 MB download, SHA-256
       │    ├─ 3. RAM pre-checks (total-RAM hard block, lowMemory/avail<req÷2 block)
       │    └─ 4. ensureNativeContext(spec)                → WhisperNative.initFromFile(path)
       └─ SubtitleGenerator.generateTranscription(...)
            └─ WhisperEngine.generateTranscriptionInternal  [Dispatchers.IO]
                 ├─ audioExtractor.prepareAudioSourceForDecode()   (remux to .m4a)
                 ├─ audioExtractor.decodeTo16kMonoFloat()          (MediaExtractor + MediaCodec)
                 └─ for each 30 s window:
                      WhisperNative.fullWithWordTimestamps(...)    → whisper_full(ctx, ...)
```

### Inference parameters actually used

From `whisper-jni.cpp` + `WhisperEngine.kt`:

- `token_timestamps = true` (required for per-word timing), `max_len = 0`
- `suppress_nst = true`, `entropy_thold = 3.0f`, `logprob_thold = -0.8f`
- `temperature_inc` from `AccuracyMode`: FAST 0.15 / BALANCED **0.1** (default) / ACCURATE 0.2
- `beam_size` from `AccuracyMode`: FAST 1 (greedy) / BALANCED **5** / ACCURATE 8; `length_penalty = 0.3f` when beam > 1
- `no_context = true`, `single_segment = false`, `translate = false`
- VAD: silero `silero-v6.2.0-ggml.bin` extracted from assets to `cacheDir`, `threshold = 0.55f`,
  `min_speech_duration_ms = 250`, `min_silence_duration_ms = 150`
- `n_threads` clamped to `1..8`, resolved by `effectiveThreads()` = model's 4, clamped to
  `min(Runtime.availableProcessors(), selfTest.systemMaxThreads)`
- **`flash_attn = true`** — set on `whisper_context_params` in `nativeInit`
- Language: user picker → else model's `"fa"` → else `"fa"`

### Model

- **`whisper-small` only** — `ggml-small.bin`, f16, **487,601,967 bytes (465 MB)**,
  `sha256 1be3a9b2…987b`, `dirName whisper-ggml-small`
- **`bundled: false`** → never in the APK; **always downloaded** on first use
- Medium and Large-v3 have been removed from the catalog; Large now maps to cloud (`CLOUD_API`)
- `CatalogEntry.minSizeBytes = 480_000_000` (a **floor**, 7.2 MB below the real size)

---

## 2. Decisive binary evidence

I extracted `lib/arm64-v8a/libwhisper.so` from the shipped debug APK and parsed its ELF
headers/symbol tables directly (no binutils available; a purpose-written reader was used).

### 2.1 The library is well-formed

| Property | Value | Verdict |
|---|---|---|
| ELF class / type | 64-bit, `ET_DYN` (PIE) | correct |
| `e_machine` | `0xB7` = AArch64 | correct |
| `DT_NEEDED` | `liblog`, `libandroid`, `libm`, `libc++_shared`, `libdl`, `libc` | all satisfiable on device |
| `libc++_shared.so` present in APK | yes, 1,423,696 B | ✅ resolved |
| max `LOAD` `p_align` | **16384** | ✅ 16 KB-page ready (Android 15+) |
| stripped size | 980,712 B | consistent with a real whisper+ggml build |

### 2.2 All four JNI symbols resolve

```
DEF  size=  216  Java_com_aicreatorstudio_app_ai_WhisperNative_nativeInit
DEF  size=   16  Java_com_aicreatorstudio_app_ai_WhisperNative_nativeFree
DEF  size= 2016  Java_com_aicreatorstudio_app_ai_WhisperNative_nativeFullWithWordTimestamps
DEF  size=  308  Java_com_aicreatorstudio_app_ai_WhisperNative_nativeSelfTest
```

`--exclude-libs,ALL` works as intended: only **82** symbols are exported (all 4 JNI + C++ RTTI),
the other 169 are `UNDEF` and resolve against the system libs above.

**Conclusion: `UnsatisfiedLinkError` for the declared functions is ruled out on `arm64-v8a`.**

### 2.3 The CPU path cannot reject a device (this is the important one)

`ggml/ggml-cpu/arch/arm/cpu-feats.cpp` computes an affinity **score** and returns `0` (reject)
when a feature the library was *compiled* for is absent:

```cpp
#ifdef GGML_USE_DOTPROD
    if (!af.has_dotprod) { return 0; }
#endif
#ifdef GGML_USE_MATMUL_INT8
    if (!af.has_i8mm) { return 0; }
#endif
```

These are `#ifdef`-gated on compile-time flags. I scanned the shipped `.text` (728,200 bytes) for
the corresponding opcodes:

| Opcode family | Instructions found |
|---|---|
| `SDOT`/`UDOT` (requires `dotprod`) | **0** |
| `MMLA` (requires `i8mm`) | **0** |

`CMakeLists.txt` never sets `GGML_NATIVE` or any per-variant flag, so the NDK toolchain compiled
for the **baseline `armv8-a`**. **The CPU-feature score function can never reject a device** — every
`#ifdef` is unset, so it always returns the base score of 1.

> **This rules out the "unsupported CPU instruction / backend configuration" hypothesis.**
> The library runs on any ARM64 CPU that Android 7+ supports.

---

## 3. ABI findings — a real compatibility limit, but not the described bug

**The APK ships exactly one ABI: `arm64-v8a`.**

```
app/build.gradle.kts:120    abiFilters += listOf("arm64-v8a")
```

Confirmed in both shipped APKs: every one of the 14 `.so` files is under `lib/arm64-v8a/` only.

The comment directly above that line claims *"Build for arm64-v8a (modern phones), armeabi-v7a
(older mid-range), and x86_64"* — **the comment is stale and contradicts the code.**

### Why this is not the reported bug

A `armeabi-v7a`-only device **cannot install the APK at all** (Play/`adb` reject it, and the OS
cannot unpack native libs for the primary ABI). The user reports the app runs and fails to
transcribe. So the failing devices are **`arm64-v8a`** — and on those, §2.2 and §2.3 show the
native side is sound.

### What it *does* cost

- 32-bit ARM phones: excluded entirely (cannot install).
- x86_64 emulators / some Chromebooks: excluded.

Per the brief I am **not** changing the ABI set. Widening it means re-vendoring and cross-compiling
whisper+ggml for `armeabi-v7a` and `x86_64`, which changes APK size and needs its own device
validation. The trade-off is documented in §7 rather than silently taken.

### Stale artefacts — all three now corrected

1. The `abiFilters` comment above → rewritten to state the verified truth.
2. `DeviceCapabilityDetector.SUPPORTED_ABIS = {arm64-v8a, armeabi-v7a, x86_64}` — it advertised
   ABIs that cannot be installed, telling users their device was supported when it was not →
   reduced to `{arm64-v8a}`, exactly what ships.
3. `WhisperNativeSymbolsTest` asserted a `.so` for all three ABIs — **this was the source of the
   long-standing suite failure.** It was a stale test, not a regression (see §5) → corrected.

---

## 4. Hypotheses, ranked by evidence

The brief said not to assume OOM. Having eliminated the native defects above, here is the honest
ranking. **I could not reproduce the failure on a device, so these remain hypotheses.**

### H1 — Model load failure from an incomplete/corrupt 465 MB download (most likely)

Strongest candidate, and it fits "same RAM, different phones" precisely.

- `whisper-small` is **`bundled: false`** — every device downloads 465 MB before first use.
- `installWithManifest` streams into `cacheDir/ai-model-downloads/<id>.partial`, then SHA-verifies,
  then `renameTo` the final path.
- **The atomicity gap:** `renameTo()` on the *same filesystem* is atomic, but `cacheDir` and
  `filesDir` are not guaranteed to share a filesystem. If `renameTo` returns false the code falls
  back to `stagedFile.copyTo(finalFile)` — **a non-atomic, restartable copy** — then deletes the
  source. A process death or storage error mid-copy leaves a **truncated file at the final path.**
- The SHA check happens **before** that copy, so a half-written final file is **never re-verified**.
- `isVerifiedInstalled` does check, but takes a **fast path on the `.sha256` sidecar**, which by
  then has already been written by the installer. So the truncated file is trusted.

On a phone where the download itself fails (mobile data, flaky Wi-Fi, no space headroom), the user
gets a file large enough to pass `minSizeBytes` (480 MB floor vs 487.6 MB real) but whose bytes are
wrong → `whisper_init_from_file_with_params` returns `NULL` → `MODEL_LOAD_FAILED`. A phone that
downloaded cleanly works. **Same RAM, different phones.**

### H2 — Memory pressure during *native* allocation (strong, and distinct from Java OOM)

Not a Java `OutOfMemoryError` — a **native `malloc` failure inside ggml**. Relevant facts:

- `largeHeap="true"` is set, giving the Java heap headroom. It does **not** help native allocations.
- Whisper Small f16 working set ≈ **488 MB weights + KV cache + ggml compute buffers**; the code
  itself documents ~1.8 GB.
- The RAM gate is deliberately soft (§`initializeModelFor`): it only hard-blocks if total RAM is
  below requirement or `lowMemory`/`availMem < required÷2`. Below that it logs and **attempts the
  load anyway**.
- When ggml's `malloc` fails, the failure surfaces as a generic init failure — the code has **no
  way to distinguish "not enough native memory" from "corrupt file."** That ambiguity is exactly why
  this is hard to diagnose today.
- The engine also holds the **whole video's 16 kHz mono float PCM resident** for the entire
  inference (109.9 MB for a 30-min clip, 219.7 MB for 60 min) plus a 1.83 MB per-window copy.
  Small next to 1.8 GB, but it is avoidable pressure.

An 8 GB phone with many background apps and a fragmented low-memory region can fail here while an
identical-spec phone succeeds.

### H3 — Thread over-subscription on big.LITTLE / weak-scheduler devices (plausible)

`effectiveThreads()` = `min(4, availableProcessors(), nativeSelfTest.systemMaxThreads)`.

`availableProcessors()` reports **logical** cores. On an 8-core big.LITTLE phone this is 8, so the
clamp is 4. Four threads pinned across a heterogeneous cluster can be slower than two, and on some
kernel configurations can contribute to allocation churn. This is a **performance/robustness**
issue, not a proven cause of outright failure — I am not claiming it causes the reported symptom.

### H4 — `flash_attn = true` on the CPU backend (unverified)

`cparams.flash_attn = true` is set unconditionally with `use_gpu = false`. The CPU backend does
implement `GGML_OP_FLASH_ATTN_EXT`, so this is legitimate in principle. However
`whisper.cpp:1170` shows `if (!wctx.params.flash_attn || !wctx.params.use_gpu)` — there are call
sites where the flag is consulted together with `use_gpu`, so the CPU behaviour is **not uniform
across the code path**. I could not test this without a device. Flagging it as a suspect, not a
conclusion.

### Ruled out

| Hypothesis | Why ruled out |
|---|---|
| `UnsatisfiedLinkError` / missing symbols | All 4 JNI symbols `DEF` in the shipped `.so` (§2.2) |
| Unsupported ABI **on the failing devices** | Those devices install and run; only arm64 is packaged (§3) |
| Unsupported CPU instruction / backend | 0 `SDOT`/`UDOT`, 0 `MMLA` in `.text`; baseline `armv8-a` (§2.3) |
| Model format incompatible with runtime | Same v1.7.6 headers used to compile and to load; f16 is the audited format |
| `libc++_shared` missing | Present in APK at 1.42 MB; declared and resolvable (§2.1) |
| 16 KB page-size mismatch | `p_align = 16384` (§2.1) |
| Java-heap `OutOfMemoryError` | `largeHeap="true"`; and this is native memory (see H2) |

---

## 5. Why the suite had 2 red tests (and why that mattered here)

`WhisperNativeSymbolsTest` had **two** failing tests, both stale:

1. `libwhisper so is built for every supported ABI` — asserted the `.so` exists for
   `arm64-v8a`, `armeabi-v7a` **and** `x86_64`. Only arm64 is built → failed. **Stale test.**
2. `real bundled whisper medium model …` — looked for
   `ai-models/whisper-ggml-medium/ggml-medium.bin`. Medium was removed from the catalog in
   `2026-09-21`, and `whisper-small` is download-only (`bundled: false`), so **no** Whisper model
   is in the APK at all → failed. **Stale test.**

Both were **test rot**, not product regressions. They mattered here because they were the only
existing filesystem-level checks on the Whisper artefacts, and they encoded assumptions that are no
longer true — which is exactly how a permanently-red test hides a real regression behind expected
noise. Both are corrected in §8.6.

A third related failure, `RealModelInstallTest`, is **asset-dependent and pre-existing**; it is left
untouched and is not chased.

---

## 6. Diagnostic gaps (what we currently cannot tell apart)

These are the concrete reasons a failing device is currently undiagnosable. Each is addressed by the
work in §8.

| # | Gap | Consequence |
|---|---|---|
| G1 | `initFromFile` collapses **every** failure to `null` — `UnsatisfiedLinkError`, native `abort`, `NULL` return | "Corrupt file" and "out of native memory" and "library missing" all look identical |
| G2 | No distinction between **file missing / truncated / wrong-size / wrong-SHA / native-reject** | User is told "may be corrupt" regardless of the real cause |
| G3 | No record of **available memory, total RAM, ABI, API level, thread count, load duration** at failure | Cannot correlate across devices at all |
| G4 | The installer's **`CROSS-FILESYSTEM` copy fallback is non-atomic** and is never re-verified | Silent truncation can be mistaken for an installed model (H1) |
| G5 | `installWithManifest` writes the `.sha256` sidecar after a rename/copy that may have failed silently | Corrupt model is then trusted by the fast path |
| G6 | Failure messages are Persian-only prose with no stable machine-readable code | No way to classify reports from users |

---

## 7. What was NOT changed, and why (trade-offs made explicitly)

1. **ABI set unchanged.** Adding `armeabi-v7a`/`x86_64` requires vendoring and cross-compiling
   whisper+ggml per ABI, materially growing the APK, and cannot be validated here (no device).
   Per the brief, an ABI is not removed or added without evidence and a stated trade-off.
2. **minSdk unchanged (24).** Native APIs used are all available at 24; no evidence requires a bump.
3. **Model quality untouched.** No change to `beam_size`, `temperature_inc`, `entropy_thold`,
   `logprob_thold`, VAD thresholds, sample rate (16 kHz) or window length (30 s). These are the
   Persian-quality levers and are deliberately left alone.
4. **No paid/cloud fallback added.** `CLOUD_API` remains the user's own Groq key, opt-in, and
   strictly separate from the offline path — the separation is preserved.
5. **Persian correction, templates, word timestamps, navigation, export:** untouched.

---

## 8. Diagnosis and hardening — what was actually implemented

Privacy-safe and metadata-only. **Never** logged: audio content, file paths beyond a basename,
API keys, device identifiers, or anything unrelated to the failure.

### 8.1 The failure-stage enum (the core deliverable)

Recorded from a fixed vocabulary — this is what turns "it doesn't work" into an answerable question:

```
NATIVE_SELFTEST → MODEL_MISSING → MODEL_SIZE_MISMATCH → MODEL_SHA_MISMATCH
  → NATIVE_INIT_NULL → NATIVE_INIT_OK → AUDIO_DECODE → INFERENCE_EMPTY → INFERENCE_ERROR
```

Implemented in `ai/WhisperDiagnostics.kt` (`Stage`), with a Persian message per stage in
`persianFor(stage)`. Also recorded per attempt: API level and `Build.VERSION.RELEASE`; primary ABI +
`SUPPORTED_ABIS`; CPU count; `totalMem`/`availMem`/`lowMemory`/`largeHeap`; model file name, expected
byte count, actual byte count, and the size verdict; native library load result; per-window inference
duration, thread count, sample count and word count.

`ModelArtifact.summary()` deliberately prints only the **file name and directory name**, and a
`sanitize()` pass collapses any path-looking token to its basename, so a caller cannot leak a path
into logcat by accident.

### 8.2 The stage is now carried, not flattened

`ai/AiFailure.kt` gains `WhisperFailure(stage, message, cause, detail)` — a plain `Exception`
subclass (so every existing `catch (e: Exception)` keeps working) carrying the machine-readable
stage. In `WhisperEngine.initializeModelFor` the `catch (e: WhisperFailure)` arm is placed **before**
the generic arm, so the precise stage survives to the UI instead of being rewritten as the old
"may be corrupt, re-download 465 MB" sentence.

### 8.3 Native init distinguishes four outcomes

`WhisperNative.InitResult` is a sealed interface: `Ok(ctx)` / `LibraryUnavailable(cause)` /
`NativeReturnedNull` / `Threw(cause)`, produced by the new `initFromFileDetailed()`. `initFromFile()`
is retained unchanged for the benchmark path.

Crucially, `ensureNativeContext` now **inspects the file before calling native at all**, and resolves
the ambiguous `NativeReturnedNull` case honestly:

- file missing → `MODEL_MISSING`
- below the declared floor → `MODEL_SIZE_MISMATCH`
- exact size differs from the manifest → `MODEL_SHA_MISMATCH`
- exact size matches but native still returned `NULL` → `NATIVE_INIT_NULL`
  (i.e. this is **not** a bad file — it is the load/memory path)

That last distinction is the one that stops a memory-pressure device from being told to re-download.

### 8.4 Model integrity (addresses G4, G5 and H1)

- **Exact-size gate.** `AiModels.expectedSizeFor()` reads `sizeBytes` from
  `assets/ai-models/manifest.json` (verified present: `whisper-small = 487,601,967`).
  `isVerifiedInstalled` checks it **before** trusting the `.sha256` sidecar, and deletes a now-untrusted
  sidecar on mismatch. This closes the 7.2 MB band between the `minSizeBytes` floor (480,000,000) and
  the real artefact where a truncated download previously passed.
- **Atomicity re-verification.** After the move into the final path, `AiModelInstaller` re-checks the
  **destination** length against the expected size and refuses to install a file it cannot vouch for;
  the `.sha256` sidecar is written only *after* the destination is confirmed intact.
- **Preserve the valid model.** The old `finalFile.delete()` + move could destroy a working model on a
  failed repair. It is now a backup-based move (`<name>.bak`) that restores the previous good file if
  the replacement does not land intact.

### 8.5 Memory and CPU (addresses H2, H3)

- **Thread ceiling.** `effectiveThreads()` now clamps to `Threads.REQUESTED_CAP = 4` *in addition to*
  the CPU/native caps. Previously an 8-core phone got 8 logical cores as its cap; since the requested
  value happened to be 4 the practical effect was small, but the ceiling is now explicit and greppable,
  and it is what stops a future catalog change from handing 8 threads to a memory-constrained device.
- **Pre-load memory record.** The memory state the load is about to run under is logged immediately
  before the attempt, so a later native allocation failure can be correlated with it.
- **Inference failure is never swallowed.** The per-window native call is wrapped so a `Throwable`
  produces `INFERENCE_ERROR` with its cause — never an empty word list.

### 8.6 Stale artefacts corrected

- `app/build.gradle.kts` — the `abiFilters` comment claimed three ABIs while the code builds one.
  Replaced with the verified truth and an explicit "do not add an ABI without building it" note.
- `DeviceCapabilityDetector.SUPPORTED_ABIS` — reduced from `{arm64-v8a, armeabi-v7a, x86_64}` to
  `{arm64-v8a}`, i.e. exactly what ships. It previously told 32-bit and x86 users their device was
  supported when the app could not load its own native library.
- `WhisperNativeSymbolsTest` — its ABI list and its bundled-`ggml-medium` assertion both encoded
  assumptions that are no longer true (see §5). Corrected to match reality; the GGML magic-byte check
  is retained and now discovers whatever model is actually present.

### 8.7 Persian UI strings recovered

The subtitle pipeline was not the only place where user-facing Persian had been **corrupted to ASCII
`?` runs at write time**. `model/SubtitleMessages.kt` collects the recovered strings as plain
constants (plus an `AiTool` nested object for the install-phase messages), which makes them
JVM-testable and gives them a single place to audit. `assertNoMojibake()` is the shared guard, and a
repo-wide static scan test now fails if a *live* Kotlin string literal in the main sources is a
question-mark run (comments that quote the defect are skipped deliberately).

Strings recovered and rewired in this pass:
`EditorViewModel` (subtitle failure + AI-tool panel status), `CloudWhisperService` (13 network/API
error messages), plus the earlier subtitle-pipeline set.

---

## 9. Status

- **Confirmed by evidence:** native compatibility on `arm64-v8a` is sound (§2); the ABI packaging is
  arm64-only (§3); the CPU-feature rejection path is inert (§2.3); the `minSizeBytes` floor sat 7.2 MB
  below the real artefact (§2/§8.4).
- **Not confirmed:** the actual failure mode on a specific phone. **H1 (truncated/corrupt download)
  and H2 (native allocation under memory pressure) remain the leading hypotheses.** No device was
  available, so nothing in §4 is a proven root cause.
- **Deliberately unchanged:** ABI set, `minSdk`, model quality parameters (`beam_size`,
  `temperature_inc`, thresholds, VAD, 16 kHz, 30 s windows), the Persian correction pipeline,
  subtitle templates, word timestamps, navigation and the export pipeline. The local-Whisper /
  optional-user-Groq-key separation is preserved exactly.
- **Next diagnostic action:** capture a `WhisperDiag`-tagged logcat from a failing device (see the
  collector instructions in the final report) — the failing `stage=` line is the diagnosis.
- **Honest limitation:** a green build is not evidence that a device can run the model. The tests
  added here prove the *diagnostic and integrity* behaviour, not native inference.

