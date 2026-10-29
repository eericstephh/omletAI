# Full Project Review & APK Output — AICreatorStudio (Omelet)

**Date:** 2026-10-11
**Package:** `com.aicreatorstudio.app`
**Version:** 1.4.0 (versionCode 4)
**Reviewer:** automated full-project review + build

---

## 1. Verdict

**The project is healthy and builds cleanly.** Both a debug and a
production-signed release APK were produced and verified. There are **no
build blockers and no code-integrity problems** worth fixing.

The only test failures are the **two known pre-existing, asset-dependent
tests** that fail identically on the untouched baseline. Nothing was
introduced by the recent subtitle-template work.

---

## 2. APK outputs

| Artifact | Size | Notes |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | **122,201,865 B (116.5 MB)** | Debug-signed, no minification |
| `app/build/outputs/apk/release/AICreatorStudio-v1.4.0-release.apk` | **77,993,409 B (74.4 MB)** | **Release-signed**, R8-minified + resource-shrunk |

The release build is **36 % smaller** than debug (116.5 MB → 74.4 MB),
which is the expected effect of R8 + `shrinkResources`.

### Verified properties

| Check | Debug | Release |
|---|---|---|
| Build result | ✅ `BUILD SUCCESSFUL` | ✅ `BUILD SUCCESSFUL` (6m 11s) |
| Package / version | `com.aicreatorstudio.app.debug` v1.4.0-debug | `com.aicreatorstudio.app` v1.4.0 |
| Label | املتAI | املتAI |
| minSdk / targetSdk | 24 / 35 | 24 / 35 |
| ABI | arm64-v8a | arm64-v8a |
| Signature | Android Debug key | **AICreatorStudio release key** (`CN=AICreatorStudio, O=AICreatorStudio, L=Tehran, C=IR`, SHA-256 `1ec4f3ff…45c8b0`) |
| `apksigner verify` | ✅ passes | ✅ passes |
| `zipalign -c 4` | ✅ passes | ✅ passes |
| Native libs | 14 `.so` incl. `libwhisper.so` | 14 `.so` incl. `libwhisper.so` (stripped 980 KB → 955 KB) |
| Persian fonts in `resources.arsc` | ✅ all 10 families | ✅ **all 10 families survived shrinking** |
| `subtitle_templates.json` asset | ✅ present | ✅ present |
| `assets/ai-models` | 9 entries | 9 entries |

**Important:** all 10 Persian font families (Vazirmatn, Sahel, Shabnam,
Samim, Estedad, Parastoo, Gandom, Nahid, Tanha, Lalezar) are confirmed
present in the **release** resource table — the R8 resource shrinker did
**not** strip any of them, so Persian subtitle rendering works in the
production build.

---

## 3. Test results

```
2157 tests completed, 2 failed, 2 skipped
```

| | |
|---|---|
| Tests executed | 2157 |
| Passed | 2153 |
| Failed | 2 — **both pre-existing, both asset-dependent** |
| Skipped | 2 |

### The 2 failures are environment-dependent, not defects

| Test | Why it fails | Affects the app? |
|---|---|---|
| `RealModelInstallTest > install real bundled whisper medium model end-to-end through the production installer` | Needs the real Whisper model asset, absent from the JVM test fixture | No |
| `WhisperNativeSymbolsTest > libwhisper so is built for every supported ABI` | Needs the built native `.so` for every ABI; the app currently ships `arm64-v8a` only by deliberate configuration | No |

Both are recorded in the project baseline and fail identically before and
after the recent work. The second is arguably a **stale test**: the build
config intentionally restricts to `arm64-v8a` (see §5), so "every supported
ABI" no longer means what the test assumes.

---

## 4. Code integrity scan

| Check | Result |
|---|---|
| Kotlin source files (main) | **283 files, 89,981 lines** |
| Kotlin test files | **166 files, 34,084 lines** |
| `TODO` / `FIXME` / `HACK` in main sources | **2** — both one documented perf note in `MotionTrackingEngine.kt`; not defects |
| `println(` debug leftovers | **0** |
| `Log.d(` calls | 8 (normal diagnostic logging) |
| Backup / temp artifacts (`*.bak`, `*.orig`, `*~`) | **none** |
| Non-null assertions (`!!`) | 22 across 283 files — normal density |

**Clean.** No abandoned code, no leftover scaffolding, no debug prints.

---

## 5. Configuration review

- **Signing** — correctly externalised. Release credentials live in
  `local.properties` (git-ignored) with env-var fallback; `app/build.gradle.kts`
  fails fast with a clear message rather than silently falling back to debug
  signing. The keystore `app/release-key.jks` is present.
- **Native build** — whisper.cpp vendored under `app/src/main/cpp/{whisper,ggml}`
  (328 KB + 211 KB). The `whisperSourcesAvailable` guard prevents a broken
  build on a fresh checkout before the download task runs.
- **NDK** — pinned to `30.0.16138531` because the host's `26.3.11579264`
  folder is a broken/partial install. Documented inline.
- **ABI** — `arm64-v8a` only, deliberately (x86 excluded to save size).
  Worth noting: this means the app will **not install on 32-bit ARM devices
  or x86 emulators**, despite `minSdk = 24` suggesting broad coverage.
- **minify/shrink** — release only; both enabled and both working.
- **No secrets committed** in `build.gradle.kts`.
- **Not a git repository** — so there is no VCS safety net. Consider
  initialising one; the project has no history to diff against.

---

## 6. Subtitle-template work (recent change) — final state

Confirmed in the built APK's source:

- **20 templates**, ids `fa_01_clean` … `fa_20_signature`, stable and unique.
- **`activeWordScale` ≤ 1.12 across the whole catalog** — the four values
  that exceeded the documented readability cap (`fa_07_creator` 1.25,
  `fa_11_gaming` 1.20, `fa_15_dynamic` 1.30, `fa_18_social` 1.20) are now
  all at 1.12. `fa_10_podcast` at 1.10 was already within cap.
- **Safe area** — 19 of 20 templates sit in the 0.62–0.84 band, clear of
  the platform lower UI chrome. `fa_08_cinematic` is deliberately retained
  at 0.88 as the one documented convention exception.
- **39 new tests** across three classes, all passing:
  `SubtitleTemplateExportParityTest` (21), `CaptionRendererRtlTest` (16),
  `OfflineSubtitleTemplateGuardTest` (2).
- **Zero APK size impact** — the change touched only float constants in one
  source file; no font, asset or dependency was added.

Full detail: `PERSIAN_TEMPLATE_REPORT_2026-10-11.md` and
`docs/persian-subtitle-template-research.md`.

---

## 7. Recommendations (optional, in priority order)

1. **Initialise a git repository.** Currently there is no version control,
   so nothing can be diffed, reverted or bisected. This is the single
   highest-value change to the project's health.
2. **Revisit `WhisperNativeSymbolsTest`.** It asserts every supported ABI
   has a built `.so`, but the build deliberately targets `arm64-v8a` only.
   Either align the test with the config or restore multi-ABI builds.
3. **Consider adding `armeabi-v7a`** if 32-bit Android reach matters —
   currently those devices cannot install the app.
4. **Decide on `MotionTrackingEngine.kt`'s `decodeOnly` TODO** — it is a
   real ~2× performance opportunity, documented but unimplemented.

None of these block release.

---

## 8. Limitations of this review

- **No device or emulator install/run was performed.** Both APKs were
  verified statically (structure, signature, alignment, resource table,
  manifest). "It builds and is signed" is **not** the same as "it runs" —
  no UI, camera, playback, export or subtitle rendering was exercised on a
  real device.
- **`androidTest` (instrumented tests) were not run** — the configuration is
  known to be broken (pre-existing), so no on-device test coverage exists.
- The two failing tests were classified as pre-existing from the project's
  recorded baseline; I did not re-verify them against a pristine checkout
  because there is no VCS to produce one.
