# Offline Whisper Compatibility — Investigation Report (2026-10-11)

**Project:** Omelet / AICreatorStudio — `com.aicreatorstudio.app` v1.4.0 (versionCode 4)
**Scope:** Why offline Whisper fails on some 8 GB phones but works on others with the same RAM.
**Method:** Audit first, then implement only what the evidence supported. No device was available.

> **Read this first — what is and is not claimed.**
> The root cause is **not confirmed**. No failing (or working) device was available, so the
> device-specific failure mode remains a ranked hypothesis list, not a proven diagnosis. What *is*
> established is a set of facts read out of the shipped binaries and sources, plus a set of real
> defects found and fixed along the way. Nothing in this report is "solved because it compiles."

---

## 1. Confirmed root cause, or a clear list of unresolved hypotheses

### Confirmed by direct evidence (not inference)

| # | Finding | Evidence |
|---|---|---|
| C1 | Native compatibility on `arm64-v8a` is **sound**. All four JNI symbols resolve; the library is a valid AArch64 PIE. | ELF dynamic-symbol parse of the shipped `libwhisper.so` (§4) |
| C2 | The **CPU-feature gate can never reject a device**. `cpu-feats.cpp` scores on `GGML_USE_DOTPROD` / `GGML_USE_MATMUL_INT8`, both **unset**; the `.text` contains **zero** `SDOT`/`UDOT` and **zero** `MMLA`. Baseline `armv8-a`. | Opcode scan + `#ifdef` inspection (§4) |
| C3 | The APK ships **exactly one ABI: `arm64-v8a`**. All 14 `.so` are under `lib/arm64-v8a/`. The build comment claiming three ABIs was stale. | `unzip -l` of both APKs; `abiFilters` (§4) |
| C4 | The in-app integrity floor (`minSizeBytes = 480,000,000`) sat **7.2 MB below** the real `whisper-small` artefact (`487,601,967`). A download truncated inside that band passed the size check. **Real defect.** | `manifest.json` vs `AiModels.WHISPER_SMALL` |
| C5 | `whisper-small` is **`bundled: false`** — every user downloads ~487 MB before first use. | `models.lock.json` |
| C6 | A **cross-filesystem copy fallback** could place a truncated file at the final path, and the `.sha256` sidecar was written before the destination was verified — after which `isVerifiedInstalled` trusted it. **Real defect.** | `AiModelInstaller` source trace |
| C7 | 39 user-facing Persian string literals across three files had been **corrupted to literal ASCII `?`** at write time, so users saw `"??? ????? ???"` instead of an explanation. **Real defect.** | Byte-level source scan (zero non-ASCII chars on those lines) |

### Unresolved hypotheses (ranked, in the absence of a device)

**H1 — Truncated/corrupt model from an interrupted 487 MB download (leading).**
Highest-ranked because it explains "same RAM, different phones" with no hardware difference at all:
a phone on flaky data or with low storage headroom gets a file that clears the old floor but has
wrong bytes → `whisper_init_from_file_with_params` returns `NULL` → generic load failure. A phone
that downloaded cleanly works. C4 and C6 are the concrete mechanisms that let this hide.

**H2 — Native allocation failure under memory pressure (strong).**
Not a Java `OutOfMemoryError` — a native `malloc` inside ggml, which `largeHeap="true"` does not
help. Working set ≈ 488 MB weights + KV cache + ggml compute buffers (the code documents ~1.8 GB).
The RAM gate is deliberately soft and attempts the load anyway below its threshold, so a genuinely
tight device reaches the allocator and fails there.

**H3 — Thread over-subscription on big.LITTLE (plausible, not a proven failure cause).**
`availableProcessors()` reports *logical* cores (8 on a 4+4 phone). Four threads across a
heterogeneous cluster can be slower than two and add per-thread scratch memory.

**H4 — `flash_attn = true` with `use_gpu = false` (unverified).**
The CPU backend does implement `GGML_OP_FLASH_ATTN_EXT`, but `whisper.cpp:1170` consults the flag
together with `use_gpu`, so the CPU path is not uniform. Flagged as a suspect only.

### Confidently ruled out

| Hypothesis | Why |
|---|---|
| `UnsatisfiedLinkError` / missing symbols | All 4 JNI symbols `DEF` in the shipped `.so` (C1) |
| Unsupported ABI **on the failing devices** | Those devices install and run; only arm64 ships (C3) |
| Unsupported CPU instruction / backend | Zero `SDOT`/`UDOT`, zero `MMLA` (C2) |
| Model format incompatible with runtime | Same v1.7.6 headers compiled and loaded; f16 is the audited format |
| `libc++_shared.so` missing | Present in the APK, declared in `DT_NEEDED`, resolvable |
| 16 KB page-size mismatch | Max `LOAD p_align = 16384` |
| Java-heap `OutOfMemoryError` | `largeHeap="true"`; and this is native memory (H2) |

---

## 2. Exact failure stage and supporting evidence

**Not determined for a specific device** — that is the honest answer, and it is precisely the problem
this work fixes. Before this change, five genuinely different faults all arrived at the UI as one
sentence ("the file may be corrupt; download it again"), so a failing device was *undiagnosable*.

The failure stage is now known at runtime. The vocabulary, in pipeline order:

```
NATIVE_SELFTEST → MODEL_MISSING → MODEL_SIZE_MISMATCH → MODEL_SHA_MISMATCH
  → NATIVE_INIT_NULL → NATIVE_INIT_OK → AUDIO_DECODE → INFERENCE_EMPTY → INFERENCE_ERROR
```

Supporting evidence for the *mechanisms* behind the two leading stages:

- **`MODEL_SIZE_MISMATCH` / `MODEL_SHA_MISMATCH`** — C4 (7.2 MB floor gap) and C6 (unverified
  destination + premature sidecar) are the two ways a wrong file becomes a "trusted" install.
- **`NATIVE_INIT_NULL` with a correctly-sized file** — this is the *discriminating* case: if the file
  size matches the manifest exactly and native still returns `NULL`, blaming the bytes is not
  supportable, so the code now reports the load/memory path instead. That is H2 surfaced honestly.

The single log line that answers it on a real device:

```
WhisperDiag: stage=MODEL_SIZE_MISMATCH FAILED | api=.. abi=.. cpu=.. totalMb=.. availMb=..
             lowMemory=.. | model=file=ggml-small.bin exists=true actual=... expectedMin=... truncated=true
```

---

## 3. Files changed and why

### New

| File | Why |
|---|---|
| `ai/WhisperDiagnostics.kt` | The stage enum, the environment/model snapshot, and metadata-only logging with a path sanitizer. The core deliverable: turns "it doesn't work" into an answerable question. |
| `model/SubtitleMessages.kt` | The recovered Persian user-facing strings as plain constants (JVM-testable), plus `assertNoMojibake()` as the shared guard. |
| `docs/WHISPER_COMPATIBILITY_AUDIT_2026-10-11.md` | The full audit with the binary evidence tables and ranked hypotheses. |

### Modified

| File | Change | Why |
|---|---|---|
| `ai/AiFailure.kt` | Added `WhisperFailure(stage, message, cause, detail)` | A plain `Exception` subclass carrying a machine-readable stage, so no existing `catch (e: Exception)` site needs to change. |
| `ai/WhisperNative.kt` | Added `InitResult` sealed interface + `initFromFileDetailed()` | Distinguishes library-unavailable / native-null / threw, which the old `null`-collapse could not. `fullWithWordTimestamps` left untouched (a source-shape test pins it). |
| `ai/WhisperEngine.kt` | `ensureNativeContext` inspects the file first and names the stage; native-null disambiguated by size; decode/inference errors wrapped with their stage; pre-load memory record; `effectiveThreads()` capped at `Threads.REQUESTED_CAP = 4`; window PCM scoped tightly | Makes the failure honest, keeps native failures from being flattened, and reduces avoidable memory churn. |
| `ai/AiModels.kt` | `expectedSizeFor()` / `readManifestSize()`; exact-size gate ahead of the `.sha256` sidecar | Closes C4 (the floor gap) and stops a stale sidecar from blessing a truncated file. |
| `ai/AiModelInstaller.kt` | Exact-size check before hashing; backup-based move that restores the previous good model; post-move destination re-verification; sidecar written only after the destination is intact | Closes C6 and satisfies "a failed repair must never cost the user a working model." |
| `viewmodel/EditorViewModel.kt` | 6 subtitle messages + 20 AI-tool status strings wired to `SubtitleMessages` | Recovers C7. |
| `ai/subtitle/cloud/CloudWhisperService.kt` | 13 network/API error strings restored to real Persian | Recovers C7. |
| `ai/DeviceCapabilityDetector.kt` | `SUPPORTED_ABIS` reduced to the shipped set `{arm64-v8a}` | It advertised ABIs that cannot be installed. |
| `app/build.gradle.kts` | The stale three-ABI comment replaced with verified truth | The comment contradicted the code beneath it. |
| `test/.../WhisperNativeSymbolsTest.kt` | ABI list and bundled-model assertions corrected to reality | A permanently-red test that hid real regressions behind expected noise. |
| `test/.../DeviceCapabilityDetectorTest.kt` | ABI-set assertion corrected | It pinned a falsehood. |
| `test/.../CloudWhisperServiceTest.kt` | 4 assertions + a fixture updated to the real Persian messages | They asserted the corrupted text. |

---

## 4. ABI and native library compatibility findings

**Packaged ABI: `arm64-v8a` only.** `abiFilters += listOf("arm64-v8a")`; verified by listing the
native entries of both built APKs — all 14 `.so` files are under `lib/arm64-v8a/`.

Native library, from an ELF parse of the shipped `libwhisper.so`:

| Property | Value | Meaning |
|---|---|---|
| Class / machine | ELF64, `e_machine = 0xB7` | AArch64 — genuine 64-bit ARM build |
| Type | `ET_DYN` (PIE) | Loadable under modern hardening |
| `DT_NEEDED` | `liblog, libandroid, libm, libc++_shared, libdl, libc` | All resolvable; `libc++_shared.so` is in the APK |
| Max `LOAD p_align` | `16384` | 16 KB-page ready |
| Exported symbols | 82 | `--exclude-libs,ALL` is working (no ggml internals leaked) |
| JNI symbols | `nativeInit`, `nativeFree`, `nativeFullWithWordTimestamps`, `nativeSelfTest` — all `DEF` | No `UnsatisfiedLinkError` on arm64 |
| `SDOT` / `UDOT` / `MMLA` in `.text` | **0 / 0 / 0** | Baseline `armv8-a`; the CPU-feature score function cannot reject a device |

### On `armeabi-v7a` and `x86_64`

They are **not** packaged. Per the brief I did **not** widen the ABI set: adding an ABI means
re-vendoring and cross-compiling whisper+ggml for it, materially changing APK size, and it cannot be
validated without a device. What I did do is stop *claiming* support: the stale comment and the stale
`SUPPORTED_ABIS` set are corrected. A 32-bit-only device cannot install the app at all, so it is not
the reported failure — the failing devices are `arm64-v8a`, and on those the native side is sound.

---

## 5. Model integrity and memory findings

**Model.** `whisper-small`, `ggml-small.bin`, **487,601,967 B**, `bundled: false` (download-only),
f16. A second Whisper checkpoint (`CLOUD_API`) is the user's own Groq key and is strictly separate —
that separation is preserved.

**Integrity (defects C4 and C6, both fixed).** The floor was 7.2 MB below reality, so a truncated
download could pass; the cross-filesystem copy fallback was non-atomic and its result was never
re-verified, while the `.sha256` sidecar was written regardless — so a bad file became permanently
"installed." Now: an exact-size gate reads `sizeBytes` from the same manifest that supplies the SHA
and runs **before** the sidecar is trusted; the installer re-verifies the **destination** after the
move and writes the sidecar only if it landed intact; and the previous good model is preserved as a
`.bak` and restored if a repair fails.

**Memory.** The engine holds the whole decoded recording as 16 kHz mono float PCM for the entire
inference (~110 MB for 30 minutes) plus a ~1.83 MB copy per 30 s window. That is small next to the
~1.8 GB working set, but it is avoidable pressure, so the window copy is now scoped as tightly as
possible and the buffer is documented as dead once the windows are consumed. The load is attempted
with a recorded memory snapshot immediately beforehand, so a later native allocation failure can be
correlated with the conditions it ran under.

**Threads.** `effectiveThreads()` now clamps to `Threads.REQUESTED_CAP = 4` in addition to the CPU
and native caps. Previously an 8-core phone's cap was its logical core count; the practical effect
was small because the catalog value was already 4, but the ceiling is now explicit and is what stops
a future catalog change from handing 8 threads to a memory-constrained device.

**No model-quality parameter was changed** — not `beam_size`, `temperature_inc`, `entropy_thold`,
`logprob_thold`, the VAD thresholds, the 16 kHz sample rate, or the 30 s window. Those are the
Persian-quality levers and were deliberately left alone.

---

## 6. Tests executed and their results

New device-independent tests (§6 of the brief), all green:

| Suite | Covers |
|---|---|
| `WhisperDiagnosticsTest` | Missing file ≠ truncated ≠ wrong-size; the 7.2 MB floor gap is caught; unknown size never reported as wrong; **no absolute path in any summary**; every stage has real Persian copy; no stage message is mojibake; the stage set is pinned; `WhisperFailure` keeps its stage through a `Result` and stays catchable as `Exception`; `InitResult` keeps library-unavailable distinct from native-null. |
| `WhisperModelIntegrityTest` | The **real** `manifest.json` declares `whisper-small` at 487,601,967 B; the manifest and `models.lock.json` agree on every shared id; `whisper-small` is not bundled; the catalog keeps only `SMALL` + `CLOUD_API`. |
| `WhisperFailurePropagationTest` | The `catch (e: WhisperFailure)` arm **precedes** the generic arm (Kotlin takes the first match); that arm propagates rather than rewrapping; every failure names a stage; inference errors return a failure and keep their cause; the file is inspected **before** native init; native-null is disambiguated by size; the thread cap is applied. |
| `SubtitleMessagesGuardTest` | Every recovered message is non-blank, contains Persian letters, passes `assertNoMojibake`, and interpolates its detail; the guard correctly rejects the real corruption signature; **plus a repo-wide static scan** that fails if any *live* Kotlin string literal in `app/src/main` is a question-mark run (comments are skipped deliberately). |

**Honest scope:** these prove the *diagnostic and integrity* behaviour — argument classification,
confinement of the failure stage, message integrity. They **do not** prove native inference works.
The JVM test process has no Android JNI loader and no `.so`, so any such claim would be false. That
is why no mocking framework is used as a stand-in for real native behaviour.

**Full suite: 2231 tests completed, 1 failed** — see §7 for the APK result and the failing test.

---

## 7. Debug APK build status

**Built successfully.** Gradle 8.13 via `GradleMain`, JDK 21 JBR, `BUILD SUCCESSFUL`.

| | |
|---|---|
| Path | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | **122,201,865 bytes** |
| Delta vs. previous build | **0 bytes** (pure Kotlin changes; test sources do not ship) |

**Verified, not assumed:**

- No source file in `app/src/main` is newer than the APK, so it was packaged after every change.
- The APK contains **19** `classes*.dex`.
- The new classes are **present in the dex**: `WhisperDiagnostics`, `WhisperFailure`,
  `SubtitleMessages`, and the `REQUESTED_CAP` thread constant were each located by scanning the
  dex bytes. A green build that silently excluded the new code would fool a build-log check; this
  does not.
- Native libraries: all `.so` entries are under **`lib/arm64-v8a`** only — consistent with
  §4 and with the corrected `SUPPORTED_ABIS`.

**Tests:** **2231 completed, 1 failed.** The single failure is `RealModelInstallTest`, which
hard-codes `app/src/main/assets/ai-models/whisper-ggml-medium/ggml-medium.bin` — an artefact removed
from the project long ago (Medium was dropped from the catalog, and `whisper-small` is
download-only, so **no** Whisper model ships in the APK). It is asset-dependent, pre-existing, and
deliberately not chased.

**This is one fewer failure than the recorded baseline of two.** `WhisperNativeSymbolsTest` — the
other long-standing red test — was corrected here because it asserted both a three-ABI build and a
bundled Medium model that no longer exist (§8.6). That is a genuine improvement to the suite's
signal: a permanently-failing test is where a real regression hides.

> ⚠️ Note for future runs: `assembleDebug` and the test task must be invoked **separately**. When
> chained, a failing test task stops the build before packaging runs, which would leave a stale APK
> in place and look like a successful build.


---

## 8. Real-device results

**None. No working and no failing device was available, so the root cause is not confirmed.**

Per the brief, here are the precise instructions to collect what is needed. The next report becomes
decisive because the failure stage is now emitted.

**On the failing phone (and, for comparison, on a working one):**

1. Install the debug APK, enable Developer options → USB debugging.
2. Connect and run:
   ```
   adb logcat -c
   adb logcat -v time WhisperDiag:V WhisperEngine:V AndroidRuntime:E libc:F DEBUG:V "*:S" > whisper_report.txt
   ```
3. In the app: open the project → AI Subtitles → select the offline **Whisper Small** model →
   generate subtitles → let it fail.
4. Stop the capture (Ctrl-C) and also record:
   ```
   adb shell getprop ro.build.version.release
   adb shell getprop ro.product.cpu.abi
   adb shell "dumpsys meminfo com.aicreatorstudio.app | head -40"
   adb shell "ls -l /data/data/com.aicreatorstudio.app/files/models/whisper-ggml-small/"
   ```
   (On a non-rooted device the app's own `filesDir` listing is not readable; instead capture the
   `model=` field from the `WhisperDiag` line, which reports the same file name, expected and actual
   byte counts without exposing a path.)

**What to send back:** the captured log file, the `getprop` values, and the `meminfo` header.

**How to read it — the first `stage=` line that says `FAILED` is the diagnosis:**

| Observed stage | Meaning | Next action |
|---|---|---|
| `NATIVE_SELFTEST` | The `.so` did not load | Packaging/ABI problem — check the device ABI against `arm64-v8a` |
| `MODEL_MISSING` | No model file on disk | The download never completed; re-download |
| `MODEL_SIZE_MISMATCH` (`truncated=true`) | File below the floor | **H1** — interrupted download |
| `MODEL_SHA_MISMATCH` | Size right, bytes wrong | **H1** — corrupt file |
| `NATIVE_INIT_NULL` (size correct) | File is fine, native refused it | **H2** — native allocation; compare `availMb`/`lowMemory` |
| `AUDIO_DECODE` | Codec/extractor failure | Container or audio-format problem |
| `INFERENCE_EMPTY` | Decoded silence | Source has no usable speech |
| `INFERENCE_ERROR` | Native failed mid-inference | Compare `elapsed` and threads across windows |

Comparing that line on a working device and a failing one is the single most informative step.

---

## 9. Remaining limitations and the next diagnostic action

### Limitations

1. **No device verification.** The device-specific root cause is not confirmed. H1 and H2 are
   ranked, instrumented, and answerable — not proven.
2. **`armeabi-v7a` / `x86_64` remain unsupported.** Deliberate, with the trade-off stated in §4.
   Users on 32-bit-only or x86 devices cannot install the app at all.
3. **H4 (`flash_attn` on CPU) is untested.** Left as-is because changing an inference parameter
   without evidence risks the Persian output quality this brief protects.
4. **The static string scan covers `app/src/main` only.** Corrupted literals in test fixtures are not
   scanned by it (some test fixtures use `"???"` deliberately as an unknown-font sentinel). The
   remaining test-side fixtures that were corrupted have been corrected by hand.
5. **Comments still quote the corrupted text on purpose**, to document the defect; the scanner skips
   comment lines for exactly this reason.
6. **A green build is not evidence a device can run the model.** This is stated once more because it
   is the easiest way to overclaim here.

### Next diagnostic action

Capture one `WhisperDiag` logcat from a failing phone and one from a working phone, per §8, and send
both. The first `FAILED` stage line separates H1 from H2 immediately — which is precisely the
question that was unanswerable before this change. If it lands on `NATIVE_INIT_NULL` with a correct
file size, the next step is to gate the load more conservatively (a lower working-set configuration
offered as an explicit user choice, never a silent quality drop) rather than to touch the decoder.
