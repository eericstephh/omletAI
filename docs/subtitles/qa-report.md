# زیرنویس فارسی — QA Report (Phase 4)

**Date:** 2026-10-16
**Scope:** validate the shipped Persian-first subtitle-template system — the eight authoring
categories, Persian text layout, preview↔export parity, and the `1.12` active-word animation
cap — before any further design work or new templates.
**Related docs:** [`implementation-plan.md`](implementation-plan.md) ·
[`reference-analysis.md`](reference-analysis.md) ·
[`../persian-subtitle-template-research.md`](../persian-subtitle-template-research.md)

## Verdict legend

| Label | Meaning |
|---|---|
| **VERIFIED** | The check was executed here and the result is recorded below. |
| **FIXED** | A confirmed defect; the fix and its verification are in §8. |
| **DEFECT** | A confirmed defect that is reported but deliberately **not** fixed in this pass. |
| **BLOCKED** | The check cannot be executed in this environment. Exact manual steps in §9. |
| **UNVERIFIED** | Not covered by any check that ran here. |
| **OBSERVATION** | Measured fact that is not a defect (e.g. an intentional trade-off). |

## 0. Summary

| # | Area | Verdict | Note |
|---|---|---|---|
| 1 | Whole unit suite | **VERIFIED** | 2905 tests, **0 failures**, 0 errors, 0 skipped |
| 2 | Debug build | **VERIFIED** | `BUILD SUCCESSFUL`, `app-debug.apk` 115.43 MB |
| 3 | Pre-existing red test (`RealModelInstallTest`) | **FIXED** | two independent defects; §3 |
| 4 | All eight categories populated | **VERIFIED** | 45 templates, 8/8 categories non-empty; §4 |
| 5 | Category-wide style/typography/safe-area sweep | **VERIFIED** | 20 new assertions; §4 |
| 6 | Persian fixture (ZWNJ, digits, mixed Latin, 1/2/3-line) | **VERIFIED** | new fixture `K`; §5 |
| 7 | Text contrast on an opaque plate | **VERIFIED** | every such template ≥ 4.5:1 |
| 8 | Presets with **no** legibility aid | **DEFECT** | 2 presets; §5.4 |
| 9 | Active-word colour legibility | **DEFECT** | 1 preset at 1.65:1; §5.5 |
| 10 | Preview ↔ export parity | **FIXED** | style-resolution defect found and fixed; §6 |
| 11 | Animation cap consistency | **OBSERVATION** | 1.12 enforced at one boundary only; §7 |
| 12 | Glyph shaping / real line breaking | **BLOCKED** | needs Android graphics; §9.1 |
| 13 | Device / export / screenshot checks | **BLOCKED** | no device or emulator; §9.2 |
| 14 | `androidTest` execution | **BLOCKED** | compiles only; §9.3 |

**Nothing in this report is a screenshot, a device result or a recording.** Every figure below
was produced by a command shown in §2 or by a test listed in §4–§7.

---

## 1. Environment and device availability

| Item | Value |
|---|---|
| Host OS | Windows 11 (`win32`), host `DESKTOP-LK3IBL3` |
| JDK | JetBrains Runtime **21.0.11** (`C:/Users/eric steph/.jdks/jbr-21.0.11`) |
| Build | Gradle wrapper `./gradlew.bat` |
| Android SDK | `C:\android` |
| Unit tests | JUnit 4, `app/src/test` — **2905** tests |
| Instrumentation | `app/src/androidTest` — compiled, **not executed** |
| Mocking | `unitTests.isReturnDefaultValues = true`; test deps are `junit`,
  `kotlinx-coroutines-test`, `org.json` |
| **Robolectric** | **absent** — see below |
| Version control | **not a git repository** — no VCS, no CI |
| Device / emulator | **none available** |

### 1.1 The two hard constraints

**(a) No Android graphics on the JVM.** There is no Robolectric dependency, so a JVM unit test
gets the Android SDK *stub* classes and `isReturnDefaultValues = true` makes every stub method
return a default (0 / null). `Bitmap.createBitmap`, `Canvas.drawText` and
`StaticLayout.Builder.build()` therefore run but produce nothing real. Actual **glyph shaping,
cursive joining and line wrapping cannot be verified here.** The codebase already said so at
`app/src/test/java/com/aicreatorstudio/app/export/CaptionRendererRtlTest.kt:22-24`.

**(b) No device or emulator.** `adb`, the Media3 export pipeline, and any on-screen capture are
unavailable. Phase 3's real-device checks and Phase 4's export/capture/compare steps are
consequently **BLOCKED** (§9), not skipped.

### 1.2 What *is* verifiable here

The shared renderer exposes its layout maths as pure functions — `fontSizePx`,
`maxTextWidthPx`, `anchorFractions`, `placeBox`, `isRtl` — and the timing/motion logic
(`WordMotionEngine`, `TextOverlay.activeWordAt`, `KaraokeRanges`) is pure Kotlin. Style
resolution, catalog mapping, JSON round-trip and the taxonomy are all framework-free. That is
the surface this report validates.

---

## 2. Commands executed and actual results

All commands run from the repository root. Results were read from
`app/build/test-results/testDebugUnitTest/*.xml` (and the captured logs), **not** from the
console exit code alone — PowerShell treats Gradle's stderr warnings as errors, so a pipeline
can report a misleading non-zero status.

| # | Command | Exit | Actual result |
|---|---|---|---|
| 2.1 | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | 0 | `tests=2905 failures=0 errors=0 skipped=0`; no failing class |
| 2.2 | `.\gradlew.bat :app:assembleDebug --console=plain` | 0 | `BUILD SUCCESSFUL`; `app-debug.apk` **115.43 MB**, timestamp `10/16/2026 1:45:15 PM` (rebuilt *after* the §8.2 main-source fix) |
| 2.3 | `.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain` | 0 | `BUILD SUCCESSFUL`; 1 pre-existing deprecation warning (`COLOR_FormatYUV420Planar`, `AiToolsRealInferenceTest.kt:342`) |
| 2.4 | `.\gradlew.bat :app:testDebugUnitTest --tests "…ai.RealModelInstallTest"` | 0 | `tests=1 failures=0` |
| 2.5 | `.\gradlew.bat :app:testDebugUnitTest --tests "…template.SubtitleTemplateCategoryQaTest" --tests "…ai.SrtFixtureFilesTest"` | 0 | all green |
| 2.6 | `java ProxyProbe.java` (standalone JDK 21, outside Gradle) | 0 | `RESPONSE code=200 contentLength=229746`; `READ body bytes = 229746`; `PROBE RESULT: OK` |
| 2.7 | byte-level fixture audit (PowerShell, `ReadAllBytes`) | 0 | `ZWNJ=7`, Persian-indic digits `=8`, BOM absent, `bareLF=23`, `CRLF=0` |

Because the tests and the build were run as **separate** Gradle invocations (2.1 then 2.2),
a failing test task could not have masked a stale APK — the failure mode recorded in
`docs/WHISPER_COMPATIBILITY_FINAL_REPORT_2026-10-11.md:235-237`.

---

## 3. Test baseline and failure analysis

### 3.1 Baseline (before any Phase 4 edit)

| Metric | Value |
|---|---|
| Tests | **2881** |
| Failed | **1** |
| Errors / Skipped | 0 / 0 |
| Only failing class | `com.aicreatorstudio.app.ai.RealModelInstallTest` |
| Failure | `java.lang.AssertionError at RealModelInstallTest.kt:83` |

**This is the complete baseline.** Exactly one red test, in one class, and it is *not* a
subtitle test.

### 3.2 Root cause — two independent defects in one test

**(a) A stale fixture.** The test hard-coded
`app/src/main/assets/ai-models/whisper-ggml-medium/ggml-medium.bin`. That asset does not exist:
`whisper-medium` was deliberately removed from the runtime catalog and from
`models.lock.json`, and:

- `WhisperModelSelectionAuditTest.kt:70-74` asserts `byId("whisper-medium") == null`;
- `WhisperNativeSymbolsTest.kt:115-149` records the 2026-10-11 correction that removed
  `whisper-ggml-medium`;
- `manifest.json` lists `whisper-small` (declared `"bundled": false`) as the only Whisper
  entry.

So the **catalog is internally consistent and the test was the stale artefact** — the
correction that fixed `WhisperNativeSymbolsTest` simply missed this file.

**(b) A broken HTTP stub.** After the fixture was corrected, the test still failed — now at the
download leg:

```
expected InstallOutcome.Ready, got Failed(phase=DOWNLOADING,
  kind=MODEL_DOWNLOAD_FAILED, detail=HTTP -1 downloading http://127.0.0.1:51939/…)
```

`HTTP -1` is the fetcher's sentinel for *any* exception (`DefaultHttpFetcher` wraps
`conn.responseCode` in `runCatching`, so a reset reads as `-1`). The stub drained the request
with

```kotlin
while (input.available() > 0) { input.read(drain) }   // ← the bug
```

`available()` is `0` whenever the server thread wins the accept race. The stub then wrote
229 746 bytes and closed the socket **while the client was still writing its request**; the
client saw a reset, and the fetcher reported `-1`. The `serverError` flag stayed `null` because
the server thread never threw — it had already finished.

**Reproduced outside Gradle** with a standalone JDK 21 program (`ProxyProbe.java`, §2.6) that
mimics the test exactly but reads the request head *blocking* before responding. It returned
`code=200`, the full 229 746-byte body, and `PROBE RESULT: OK`. The same probe printed
`http.proxyHost = null`, `HTTP_PROXY = null`, `NO_PROXY = 127.0.0.1,localhost,::1` and the
default `ProxySelector` — so **the environment was never at fault; the stub was.**

### 3.3 Corroboration

This failure was already known and explicitly deferred.
`docs/WHISPER_COMPATIBILITY_FINAL_REPORT_2026-10-11.md:224-228` records
"**2231 completed, 1 failed.** The single failure is `RealModelInstallTest`, which hard-codes
… an artefact removed from the project long ago … deliberately not chased." Phase 1.5 therefore
**closed a documented backlog item**; it did not discover a new subsystem bug.

### 3.4 Fix

`app/src/test/java/com/aicreatorstudio/app/ai/RealModelInstallTest.kt` was rewritten so it
cannot rot again:

- the fixture is **derived** from `app/ai-models/models.lock.json` — entries with
  `bundled != false` whose asset exists on disk at the declared size, smallest first;
- the stub HTTP server now **block-reads the request head** (to the CRLF CRLF terminator) and
  accepts in a loop, exactly like a real server;
- an explicit assertion reports a stuck stub instead of a bare `Ready` mismatch.

**Every original assertion is preserved** — `InstallOutcome.Ready`, `InstallSource.Downloaded`,
a SHA-256 match against the lockfile, a sidecar carrying the verified SHA, and no leftover
`.partial`. The download path is still genuinely exercised: `TestContext` is a `ContextWrapper`
over `null`, so `context.assets` throws, `copyBundledAsset` returns `BundledResult.Miss`
(`UnifiedModelInstaller.kt:340`), and the installer falls through to DOWNLOAD. No assertion was
deleted, weakened or turned into a skip.

### 3.5 Final test state

| Metric | Baseline | Final | Δ |
|---|---|---|---|
| Tests | 2881 | **2905** | +24 |
| Failed | 1 | **0** | −1 |
| Errors / Skipped | 0 / 0 | 0 / 0 | — |

The +24 = 20 new category QA tests (§4) + 4 new export-style parity tests (§6.4) + the
`SrtFixtureFilesTest` case added for fixture `K` (§5.1, the class went from 10 to 11 tests).

---

## 4. Results for all eight categories

### 4.1 Catalog composition (measured)

| Source | Templates |
|---|---|
| `app/src/main/assets/subtitle_templates.json` | 25 |
| `model/BuiltInSubtitleTemplates.kt` (hand-built `fa_*`) | 26 |
| Shared ids (`fa_pro_*` defined in **both**) | 6 |
| **Combined catalog offered by the picker** | **45** |

`SubtitleTemplateCategoryQaTest` asserts the combined catalog has **unique** ids — a duplicate
would render two identical cards. **VERIFIED.**

### 4.2 The eight categories (measured, all non-empty)

| Category | Key | Count | Templates |
|---|---|---|---|
| ویروسی / تأکیدی | `VIRAL_BOLD` | 13 | `fast_zoom_reveal`, `glitch_cut`, `bounce_text_pop`, `slide_reveal`, `fa_pro_hormozi_viral`, `fa_pro_comic_pop`, `fa_pro_red_punch`, `fa_02_impact`, `fa_07_creator`, `fa_11_gaming`, `fa_14_shadow_pop`, `fa_15_dynamic`, `fa_18_social` |
| کارائوکه / کلمه‌به‌کلمه | `KARAOKE_WORD_BY_WORD` | 7 | `karaoke_classic`, `progress_bar_underline`, `wave_accent`, `typewriter_reveal`, `fa_04_karaoke`, `fa_16_bar`, `fa_20_signature` |
| پادکست / گفت‌وگو | `PODCAST_INTERVIEW` | 5 | `cinema_black_box`, `cinema_dark_box`, `cinema_red_box`, `fa_09_news`, `fa_10_podcast` |
| مینیمال / سینمایی | `MINIMAL_CINEMATIC` | 6 | `minimal_clean`, `cinematic_bar`, `filmstrip_frame`, `fa_pro_golden_lux`, `fa_08_cinematic`, `fa_19_premium` |
| آموزشی | `EDUCATIONAL` | **1** | `cinema_yellow_script` |
| تأکید بر کلمهٔ کلیدی | `KEYWORD_EMPHASIS` | 5 | `keyword_punch_zoom`, `neon_glow`, `fa_pro_neon_cyberpunk`, `fa_03_yellow_word`, `fa_12_neon` |
| فارسی تمیز | `CLEAN_PERSIAN` | 4 | `cinema_plain_white`, `fa_pro_glass_minimal`, `fa_01_clean`, `fa_06_glass` |
| گرد / دورخط‌دار | `ROUNDED_OUTLINED` | 4 | `cinema_black_pill`, `fa_05_capsule`, `fa_13_outline`, `fa_17_modern_box` |
| | **Σ** | **45** | |

**Verdict: VERIFIED.** Every requested category ships at least one template, the classification
is total (every template lands in exactly one bucket), counts sum to 45, and the taxonomy is a
*derived* presentation layer — it adds no JSON key, so no saved project changed shape.

> The counts are **pinned** in `SubtitleTemplateCategoryQaTest` deliberately: growing the
> catalog must now update that file on purpose.

### 4.3 Checks run against every template of every category

New suite: `app/src/test/java/com/aicreatorstudio/app/model/template/SubtitleTemplateCategoryQaTest.kt`
— **20 tests, all green.**

| Check | Method | Verdict |
|---|---|---|
| Templates per category + pinned counts | taxonomy `group()`; 8/8 present | **VERIFIED** |
| Classification is total, one bucket each | `coverage()` sums to catalog size | **VERIFIED** |
| Offline, Persian-capable font | `PersianFont.from(style.fontFamily) in PersianFamilies` for all 45 | **VERIFIED** |
| Horizontal safe margin | `maxTextWidthFraction ∈ 0.50‥0.95` | **VERIFIED** |
| Vertical safe band | `anchorFractions(style).y ∈ 0.10‥0.92` | **VERIFIED** |
| Box stays in frame | renderer's own `placeBox()` on 1080×1920, worst case 3 lines | **VERIFIED** |
| Animation duration bounds | `animationDurationMs ∈ 60‥1500` (in + out) | **VERIFIED** |
| Legibility aid present | stroke ∨ shadow ∨ plate ≥ 0.35 ∨ non-`NONE` background | **DEFECT** for 2 presets, §5.4 |
| Text contrast on an opaque plate | WCAG ratio ≥ 4.5:1 | **VERIFIED** (all pass) |
| Active-word contrast on its backing | WCAG ratio ≥ 3:1 | **DEFECT** for 1 preset, §5.5 |
| Active word is visually differentiated | colour ∨ pill ∨ bold ∨ underline ∨ glow ∨ reveal ∨ motion | **VERIFIED** |
| Apply copies every field | `withTemplate` sets style, colours, scale, motion, reveal, animation, bar, glow, size, family, karaoke mirror | **VERIFIED** |
| Apply preserves real word timings | word list and uneven durations unchanged for all 45 | **VERIFIED** |
| Save / reload | `SubtitleTemplate.fromJson(t.toJson()) == t` for all 45 | **VERIFIED** |
| Seek | `activeWordAt` start-inclusive / end-exclusive / gaps / past-end; `KaraokeRanges` lands on the whole ZWNJ word | **VERIFIED** |

### 4.4 Per-category notes

| Category | Note |
|---|---|
| آموزشی | Thinnest bucket at **1** template. Not a defect, but it is the clearest content gap. |
| گرد/دورخط‌دار | 4 templates; the strongest signal for `ROUNDED_OUTLINED` is a rounded surface or outlined text, which the property fallback also honours. |
| فارسی تمیز | **1 of its 4 presets ships with no legibility aid** (§5.4) — notable because this category is the one whose stated intent is maximum legibility. |
| ویروسی/تأکیدی | Contains the one measured active-word contrast defect (§5.5). |

---

## 5. Persian text-layout findings

### 5.1 The fixture

There was no single fixture covering the brief's required character set, so one was added:

**`app/src/test/resources/srt-fixtures/K-persian-rtl-stress.srt`** — 502 bytes, LF-only, no BOM,
5 cues.

| Requirement | Coverage | Verified by |
|---|---|---|
| Joined (cursive) letters | می‌خواهم, به‌روزرسانی, نیم‌فاصله | byte audit + parse assertions |
| Half-spaces (نیم‌فاصله, U+200C) | **7**, none adjacent to a space | `ZWNJ=7`; adjacency loop |
| Punctuation | `«»`, `!`, `—`, `،`, `ٔ`, `.` | parse assertions |
| Persian digits | **8** (`۱ ۲ ۳ ۰`) → `۲.۰`, `۱.۲.۳` | byte audit + parse assertions |
| Mixed Latin | `ChatGPT`, `synchronization` | parse assertions |
| Short and long words | را / پیش‌فرض‌های, ویدئوسازی, حرفه‌ای | parse assertions |
| 1 / 2 / 3-line captions | line counts `[1, 2, 3, 2, 1]` | parse assertion |
| Distinct timestamps | 5 distinct, strictly increasing starts | parse assertions |
| Correct Persian letters | ی (U+06CC) and ک (U+06A9) present; Arabic ي (U+064A) and ك (U+0643) **absent** | parse assertions |

The fixture drives the **real import path** (`SrtParser` → `SrtImportMapper.toTextOverlays`),
and the overlay text/timings are asserted to survive unchanged.

> The fixture also feeds a word-timing test with **hand-authored, deliberately unbeven** windows
> (proportional to token length, with gaps). These are deterministic inputs for the timing
> model. **They are not speech-recognition output and nothing here measures transcription
> accuracy.** The test explicitly asserts the windows are *not* equal division.

Because the SRT fixtures under `app/src/test/resources` serve JVM checks and the mirrored
`app/src/androidTest/assets` copies are only read by instrumentation tests that name their files
explicitly, `K` was added to the **test resources only** — a focused change; no instrumentation
test depends on it.

### 5.2 What is verified about Persian text

| Property | Verdict |
|---|---|
| نیم‌فاصله survives parse + import byte-exactly, never adjacent to a space | **VERIFIED** |
| Persian-indic digits survive untouched | **VERIFIED** |
| Persian letter forms are used, not Arabic look-alikes | **VERIFIED** |
| Mixed Persian/Latin cues are detected as RTL by the renderer's `isRtl` | **VERIFIED** |
| Persian words with ZWNJ are locatable as a whole karaoke span (`KaraokeRanges`) | **VERIFIED** |
| Multi-line cue text reaches the overlay with its line breaks intact | **VERIFIED** |
| A Persian token that carries no Arabic-block letter (e.g. `۱)`) is *not* forced RTL | **VERIFIED** |

### 5.3 Method for the contrast numbers

WCAG 2.1 relative luminance on the **declared** ARGB colours, alpha composited over an opaque
black backdrop; ratio = `(L₁+0.05)/(L₂+0.05)`. These are *design-intent* measurements: the real
plate is `backgroundOpacity` over unknown video, so the on-device ratio varies with the clip.
Two limits are therefore stated rather than hidden:

- ratios are only asserted where the plate is **≥ 0.85 opaque** (19 templates); below that the
  backdrop is genuinely video-dependent and is reported as *unmeasurable here*;
- gradient plates are compared against `backgroundColorArgb` (the dominant stop) only.

### 5.4 Text contrast and legibility aids

| Finding | Measurement | Verdict |
|---|---|---|
| Base text on an opaque plate | **all 19** such templates ≥ **4.5:1** (WCAG AA) | **VERIFIED** |
| Presets with **no** outline, **no** shadow and **no** plate | **2**: `cinema_plain_white` (CLEAN_PERSIAN) and `cinema_yellow_script` (EDUCATIONAL) | **DEFECT** |

Both defective presets draw bare text directly over arbitrary video. On a bright clip there is
nothing keeping the caption readable:

- `cinema_plain_white` — white text, `stroke=false`, `shadow=false`, `backgroundKind=NONE`;
- `cinema_yellow_script` — yellow `#FFD400` text, same absence.

`cinema_plain_white` sits in the **فارسی تمیز** category — the one whose purpose is maximum
legibility — so the absence is not merely risky, it contradicts the category's own intent.

These are pinned as a **ratchet** in the QA suite: the test asserts the offender set is *exactly*
`{cinema_plain_white, cinema_yellow_script}`, so the list cannot grow silently and a fix must
edit the test deliberately. **Not fixed here** — see §10.

### 5.5 Active-word (highlight) legibility

The highlighted word must be *more* legible than the text it replaces. The backing is the
template's active-word pill when it draws one, otherwise the plate.

| Finding | Measurement | Verdict |
|---|---|---|
| Active word on its backing | every template ≥ **3:1** **except one** | **DEFECT** |
| `bounce_text_pop` | active word `#00E5A0` (mint) on its own **white** plate → **1.65:1** | **DEFECT** |

`bounce_text_pop` uses near-black text on a white plate at **18.9:1** — excellent. The moment a
word becomes active it is recoloured to mint on that same white plate, dropping to **1.65:1**:
the highlight makes the spoken word *less* readable than the rest of the caption. Because the
plate is white over unknown video the ratio never improves (worst case ≈ 1.5:1 if the clip
behind it is dark). The white outline (`shadow: "#FFFFFF"`) does not rescue it — a white halo on
a white plate.

A related but weaker case: `cinema_yellow_script` differentiates its active word by recolouring
it white against a yellow base — a luminance difference of only **1.43:1 (observed)**. The word
does also become bold, so this is reported as a *weak* highlight rather than a failure of the
"is it differentiated" rule.

**Not fixed here** — see §10. The fix is a one-value catalog edit per template, and choosing the
replacement colour is a design decision.

### 5.6 Typography

**VERIFIED:** all 45 templates resolve to a **bundled, offline** Persian family
(`PersianFont.PersianFamilies`) — never a system fallback, which would shape Persian broken on
some OEMs. The family set is asserted to contain ≥ 8 families elsewhere
(`CaptionRendererRtlTest`).

**BLOCKED:** whether the chosen family *shapes* the fixture's joining/ZWNJ sequences correctly
at the pixel level cannot be checked here (needs `StaticLayout` + a real `Canvas`, §9.1).

### 5.7 Pre-existing, out of scope

| Finding | Measurement | Note |
|---|---|---|
| Mojibake in `EditorViewModel.kt` | **107** U+FFFD across **101 lines** | All in KDoc/comment lines — corrupted em-dashes, not user-facing strings. Pre-existing; untouched. |
| Mojibake in `AcceptanceTestSuite.kt` (test) | 6 U+FFFD | Test-only. Pre-existing; untouched. |
| `docs/MANUAL_TEST_CHECKLIST.md` stale | says "17 templates" and a corrupted Persian tab label (`??????`) | The catalog now holds **45**. Not fixed. |

---

## 6. Preview ↔ export parity evidence

### 6.1 One renderer, four call sites

Every caption pixel in the app comes from a single function, `CaptionRenderer.render`
(`app/src/main/java/com/aicreatorstudio/app/export/CaptionRenderer.kt:329`). There is no second
text implementation.

| Consumer | File:line | Argument contract |
|---|---|---|
| **Preview** — canvas | `ui/screens/editor/CaptionBitmap.kt:59` (`rememberCaptionImageBitmap`) | `canvasWidthPx/HeightPx` = the Compose surface size; `frameTimeMs` = timeline playhead |
| **Preview** — studio cards | `ui/screens/editor/CaptionBitmap.kt:104` (`rememberTemplateImageBitmap`) → `:131` | builds a synthetic overlay via `withTemplate`, then the same call |
| **Export** — burn-in, static | `export/SubtitleBurnIn.kt:245` (cached bitmap) | `canvasWidthPx/HeightPx` = **output** frame; both overlay anchors `(0,0)` so placement happens *inside* the bitmap |
| **Export** — burn-in, per-frame | `export/SubtitleBurnIn.kt:293` (viral motion / entrance) | same, `frameTimeMs` = `presentationTimeUs / 1000` |
| **Export** — tracked overlay | `export/TrackedOverlayRenderer.kt:86` | same; rasterised once, then blitted |

`TrackedOverlayRenderer`'s KDoc states the reason explicitly (`:30-45`): the export rasterises
"the same renderer the preview uses … One renderer, two consumers, guaranteed pixel parity for
everything except position."

**VERIFIED (structural):** preview and export call the identical function with the same
normalised metrics. Sizes derive from the caller's canvas, never from screen pixels
(`CaptionRendererParityTest` locks `fontSizePx`).

**Time base — VERIFIED:** `SubtitleBurnInFactory.createOverlays` rebases each overlay window
*and* each word into output time (`SubtitleBurnIn.kt:112-121`), so the shared renderer and
`WordMotionEngine` see one consistent time base. Single-clip projects at timeline 0 are
bit-identical to the previous mapping.

**fontScale — OBSERVATION:** `CaptionRenderer.render` takes `fontScale` (default `1f`). Only the
small picker cards pass a different value (`SubtitleStudioPanel.kt:2095` uses `0.75f`). The
canvas preview and every export path use the default, so parity is unaffected — the reduced
scale is a deliberate thumbnail shrink, not a parity path.

### 6.2 The defect this pass found — style resolution diverged

`SubtitleBurnIn.SubtitleWindowOverlay` resolves its style through
`TextOverlay.exportStyle()` (`SubtitleBurnIn.kt:235`). The canvas preview resolves it inline.
For an overlay with `subtitleStyle == null` the two disagreed:

| Path | Resolution when `subtitleStyle == null` |
|---|---|
| Preview (canvas) — `EditorScreen.kt:3405-3406` | `SubtitleStyle.Default` |
| **Export** — `SubtitleBurnIn.kt:163` (before the fix) | style **derived from the overlay's own fields** |

This matters because **both** ways a user obtains subtitles leave `subtitleStyle` null:

- `ai/WhisperTranscriptionToTextOverlays.kt:117-135` → `subtitleSource = "ai"`, no style;
- `ai/SrtImportMapper.kt:118-140` → `subtitleSource = "srt"`, no style.

Predicted effect of the mismatch (from the two style objects):

| Field | Preview (`Default`) | Export (derived) |
|---|---|---|
| `bold` | **true** | false (`fontWeight = Normal`) |
| `strokeEnabled` | **true**, black 2sp | false |
| `backgroundEnabled` | **true**, black 0.55 | false (`backgroundKind = NONE`) |
| `verticalAlignment` | **BOTTOM** | CENTER |
| `positionY` | **0.85** | overlay position (0.5) |
| `maxTextWidthFraction` | 0.9 | 1.0 |

So a freshly transcribed or imported caption previewed as **bold, outlined text in a black pill
near the bottom** and burned into the MP4 as **plain, non-bold text with no outline, no pill,
centred in the frame**.

The KDoc claimed the preview "already uses exactly this precedence" (`SubtitleBurnIn.kt:159-161`).
It does not: `FreePositionTextOverlay` (`EditorScreen.kt:3989`) is the **decorative** path
(`subtitleSource == null`) and is a separate Compose renderer; the canvas caption path uses
`Default`. A source-comment check would not have caught this — the runtime resolution does.

**Verdict: DEFECT (FIXED, §8.2).**

### 6.3 Remaining parity risk

`FreePositionTextOverlay` (decorative text) applies a default shadow (black 55%, blur 4,
offset 1.5) when `subtitleStyle == null` (`EditorScreen.kt:4017-4021`), while `exportStyle()`
sets `shadowEnabled = false` for the same overlay. This is a *subtle* remaining difference on
the decorative path. It is reported as an **UNVERIFIED** parity risk rather than a defect: the
decorative path is a separate Compose renderer whose geometry cannot be compared pixel-wise
without a device (§9.2), and the two are close in intent.

---

## 7. Animation-cap evaluation (`activeWordScale` = 1.12)

### 7.1 Where the cap is enforced (measured by reading each boundary)

| Boundary | File | Range enforced |
|---|---|---|
| 1. JSON → model (the D1 fix) | `model/template/JsonTemplateMapper.kt:235` | **`0.8f‥1.12f`** |
| 2. Model invariant | `model/SubtitleTemplate.kt` (`require`) | `0.8f‥2.5f` |
| 3. Hand-built catalog | `model/BuiltInSubtitleTemplates.kt` | authored 1.08–1.12 |
| 4. Template → overlay | `viewmodel/EditorViewModel.kt:4036` | none (passes through) |
| 5. Project restore | `db/EditorStateJson.kt:229` | none |
| 6. Motion engine | `ai/WordMotionEngine.kt:106` | none; **adds** the bounce |
| 7. Renderer | `export/CaptionRenderer.kt` (`buildViralLayout`) | `0.5f‥2.5f` |

**The 1.12 cap is enforced at exactly one boundary — the JSON mapper.** Boundary 5 means a
project saved from a hand-built template *can* restore a value above 1.12, and boundary 7 would
then render it. **OBSERVATION.**

**A concrete instance of the silent clamp** — `JsonTemplateMapper.kt:235`:

```kotlin
?: if (highlightActive) 1.15f else 1f).coerceIn(0.8f, 1.12f)
```

A JSON template that declares an active highlight but no explicit scale asks for **1.15** and
silently receives **1.12**. That is the intended outcome of the D1 decision, but it happens with
no diagnostic — worth knowing before anyone tunes a preset and wonders why the value will not
move.

### 7.2 Measured catalog state (all 45 templates)

| Metric | Value |
|---|---|
| Templates above 1.12 | **0** |
| Templates at exactly 1.12 | **23** |
| Templates using a motion kind that reads the scale | **10** |
| Max / min template value | **1.120** / 1.000 |

Of those 10: **6 × BOUNCE_POP**, 2 × SLIDE_UP_WORD, 2 × FADE_SCALE.

### 7.3 Effective scale per motion kind (characterised by test)

| Motion kind | Formula | Effective max at 1.12 |
|---|---|---|
| `SLIDE_UP_WORD` | `active ? activeWordScale : 1` | **1.120** |
| `FADE_SCALE` | `0.7 + 0.3t + (target−1)t`, `target = 1.12` | **1.120** |
| `SHAKE_WIGGLE` | `1 + activeWordScale * 0.1` | **1.112** |
| `BOUNCE_POP` | `activeWordScale + \|sin(2.5πt)\|·(1−t)·0.35` | **1.4035** |

The BOUNCE_POP peak is analytic (max of `|sin(2.5πt)|(1−t)` = **0.809908** at `t = 0.1804`;
`1.12 + 0.35·0.809908 = 1.4035`) and is pinned by a test that samples the engine every
millisecond. For `t ≥ 1` the bounce term is **exactly 0**, so a long word settles back to
`activeWordScale` and never dips below it — no shrink defect.

### 7.4 Answer to the brief's question

> "Whether animation values are clamped consistently at all input boundaries."

**No.** The design cap of 1.12 is a *JSON-authoring* cap, not a rendering invariant: the model
permits 2.5, the renderer permits 2.5, project restore re-applies whatever was saved, and the
one motion kind that reads the scale can push the drawn size to ≈1.40. None of this is a bug —
a bounce that did not overshoot would not bounce — but the boundary table above should be read
as the actual contract.

**No change was made to the cap**: raising or lowering it would require evidence and tests, and
the current value is consistent with the research doc
(`docs/persian-subtitle-template-research.md:229,297`).

---

## 8. Confirmed fixes and changed files

### 8.1 Files changed

| File | Change | Why |
|---|---|---|
| `app/src/test/java/com/aicreatorstudio/app/ai/RealModelInstallTest.kt` | rewritten: lockfile-derived fixture; stub HTTP server block-reads the request head and accepts in a loop; added a stuck-stub assertion | fixes the only red test (two defects, §3.2) |
| `app/src/main/java/com/aicreatorstudio/app/export/SubtitleBurnIn.kt` | `exportStyle()` now dispatches: `subtitleStyle ?: if (subtitleSource != null) SubtitleStyle.Default else decorativeExportStyle()`; KDoc corrected | fixes the preview↔export parity break (§6.2) |
| `app/src/test/java/com/aicreatorstudio/app/export/TextExportStyleTest.kt` | +4 tests (subtitle → `Default`; decorative still derived; real style still wins; survives a project round-trip) | pins both branches against regression |
| `app/src/test/resources/srt-fixtures/K-persian-rtl-stress.srt` | **new** fixture (5 cues, 502 B) | no fixture covered ZWNJ + Persian digits + mixed Latin + 1/2/3-line together (§5.1) |
| `app/src/test/java/com/aicreatorstudio/app/ai/SrtFixtureFilesTest.kt` | added `K` to the expectation map; KDoc updated; test renamed `…eleven fixtures…` | keeps the existing fixture smoke test complete |
| `app/src/test/java/com/aicreatorstudio/app/model/template/SubtitleTemplateCategoryQaTest.kt` | **new** — 20 tests | the category-wide validation sweep (§4.3) |
| `docs/subtitles/qa-report.md` | **new** | this report |

**No product data was changed:** the 25 JSON presets and the 26 hand-built designs are
untouched, no template was added, no colour was edited, the catalog still holds 45 entries, and
the pinned catalog-count assertions (`AcceptanceTestSuite.kt:34`,
`JsonTemplateMapperTest.kt:294,427`, `JsonTemplateParserTest.kt:22,25`) were not touched.

### 8.2 Fix 1 — `exportStyle()` parity (main source)

Before:

```kotlin
internal fun TextOverlay.exportStyle(): SubtitleStyle = subtitleStyle ?: SubtitleStyle(…)
```

After:

```kotlin
internal fun TextOverlay.exportStyle(): SubtitleStyle = subtitleStyle ?: when {
    subtitleSource != null -> SubtitleStyle.Default   // matches the canvas preview
    else -> decorativeExportStyle()                   // Add-Text / Paragraph, unchanged
}
```

The former body became `private fun TextOverlay.decorativeExportStyle()`.

**Blast radius:** only overlays with `subtitleStyle == null`. Decorative overlays
(`subtitleSource == null`) keep the exact previous behaviour, so all six pre-existing
`TextExportStyleTest` cases pass unchanged. Subtitle overlays now burn identically to what the
preview shows.

**Verified by:** `TextExportStyleTest` 10/10 green; full suite 2905/2905 green; `assembleDebug`
rebuilt after the change (§2.2).

### 8.3 Fix 2 — `RealModelInstallTest`

Described in §3.4. **Verified by:** 1/1 green, and the full suite returned to zero failures.

### 8.4 Defects reported but deliberately **not** fixed

| # | Defect | Why not fixed here |
|---|---|---|
| D3 | `cinema_plain_white`, `cinema_yellow_script` ship with no stroke, shadow or plate | Fixing means choosing an outline/pill for two deliberately "plain" presets — a product/design decision, and it changes shipped visuals. Ratcheted by test instead. |
| D4 | `bounce_text_pop` active-word colour is 1.65:1 on its own white plate | The fix is one colour value, but *which* colour is a design decision. Ratcheted by test (the offender set must remain exactly this one id). |
| D5 | `cinema_yellow_script` active colour differs from its base by only 1.43:1 | Weak, not broken (the word also becomes bold). Recommend strengthening alongside D3. |
| D6 | `activeWordScale` clamped at one boundary only | Changing any clamp alters rendered output for existing projects. Documented in §7; needs sign-off. |
| D7 | Mojibake in `EditorViewModel.kt` (107 U+FFFD, comments only) and stale `MANUAL_TEST_CHECKLIST.md` | Pre-existing, unrelated to subtitles, out of scope. |

---

## 9. Blocked and unverified checks

### 9.1 BLOCKED — glyph shaping and real line breaking

**Why:** no Robolectric; JVM tests get stubbed `android.graphics`, so `StaticLayout` performs no
real text measurement and `Canvas` rasterises nothing.

**Cannot be asserted here:** that the fixture's joined letters shape correctly; that ZWNJ
prevents a break; where the two-pass layout actually wraps the Persian fixture; whether the
two-pass rebuild (`CaptionRenderer.kt:395-411`, re-laying out at the measured width) ever
re-wraps differently from the first pass. The last is a genuine, untested risk.

**Manual step:** install `app-debug.apk` on a device, apply `fa_01_clean`, type
`پیش‌فرض‌های کاربردی ویدئوسازی حرفه‌ای` plus `۱.۲.۳ (نهایی)` and confirm joining, ZWNJ integrity
and that the wrap point does not jump when the box tightens.

### 9.2 BLOCKED — device, export and pixel comparison

**Why:** no device or emulator in this environment.

**Manual steps** (from `docs/MANUAL_TEST_CHECKLIST.md`, extended for this pass):

1. Import `K-persian-rtl-stress.srt` and confirm the canvas preview shows a caption that is
   bold, outlined, in a black pill, near the bottom — **then export a 5 s clip and confirm the
   MP4 shows the same** (this is the §8.2 fix; before it, the burn centred plain text).
2. Repeat with an AI transcription (no template applied) — same expectation.
3. Apply each of the eight categories' first template, export 5 s each, and confirm
   preview == export (styling, position, active-word colour).
4. Confirm the `1.12` cap visually: a BOUNCE_POP word should overshoot ≈1.40× then settle at
   exactly 1.12× (**not** 2.5×).
5. Confirm no caption leaves the frame at 9:16 and 16:9.
6. Seek backwards and forwards mid-caption and confirm the active word tracks the audio and the
   progress bar re-syncs after a seek.
7. Save the project, force-stop, relaunch, reload — styling and word timings must be unchanged.
8. Screenshot the preview and a frame of the export at the same timestamp and diff them.

### 9.3 BLOCKED — `androidTest` execution

The instrumentation suites (including `SrtImportExportInstrumentationTest` and
`SubtitleStyleInstrumentationTest`, which consume the SRT fixtures, and the Whisper
real-inference tests) **compile** (`:app:compileDebugAndroidTestKotlin`, exit 0) but were never
executed.

**Stale references in `androidTest` (documented, not fixed — cosmetic, confirmed non-breaking
because the module compiles):**

| File:line | Stale content |
|---|---|
| `WhisperRealInferenceTest.kt:33,38,142,143` | "real `ggml-medium.bin`", "1.5 GB", `assets/ai-models/whisper-ggml-medium/…` |
| `WhisperSubtitlesToTimelineInstrumentationTest.kt:49` | "the real `ggml-medium.bin`" |
| `WhisperModelBenchmarkTest.kt:45-46` | lists `whisper-medium` / `whisper-large-v3` as expected ids |
| `WhisperModelAccuracyAuditTest.kt:67,309-310` | maps `whisper-medium → whisper-ggml-medium/ggml-medium.bin` |
| `SubtitleGenerationLifecycleInstrumentationTest.kt:36` | "`ggml-medium.bin`, running whisper.cpp" |
| `GroqApiInstrumentationTest.kt:42` | `model = "whisper-large-v3"` (cloud model id — verify separately) |

### 9.4 BLOCKED — reference-binary (REA) analysis

No legally obtained reference APK (e.g. CapCut) exists in the workspace, and the REA binary
tools are unavailable on this host: `inspect-android-package` needs a caller-supplied
`REA_JADX_MCP_JAR` (not installed), and `inspect-binary-layout` reports "pwntools artifact
profile supports Linux x64 only". Ghidra is unavailable on Windows for this purpose and Hopper
is macOS-only. Reference comparisons remain as documented in
[`reference-analysis.md`](reference-analysis.md) §2.

### 9.5 UNVERIFIED

| Item | Note |
|---|---|
| ASR quality on the Persian fixture | The fixture validates *layout/timing plumbing*, not transcription accuracy. No accuracy claim is made. |
| Decorative-text shadow parity | `FreePositionTextOverlay` draws a default shadow that `exportStyle()` does not (§6.3). Needs the device comparison in §9.2. |
| Two-pass re-wrap stability | See §9.1. |
| Gradient-plate contrast | Only the dominant colour stop was measured. |
| `fontScale > 1` preview vs export | Only picker cards use it; no pixel comparison was possible. |

---

## 10. Prioritised next steps

| P | Item | Why | Effort |
|---|---|---|---|
| **P0** | Run §9.2 on a real device — especially step 1 (the §8.2 parity fix) and step 4 (bounce vs cap) | The only way to close the parity and shaping gaps; nothing in this environment can | 1 device session |
| **P1** | Give `cinema_plain_white` and `cinema_yellow_script` a legibility aid (D3), and strengthen `cinema_yellow_script`'s active-colour delta (D5) | These are the only presets with **zero** protection against a bright clip; one of them is in the "clean Persian" category | tiny (2 data edits) |
| **P1** | Recolour `bounce_text_pop`'s active word to ≥ 3:1 on its white plate (D4) | The highlight currently makes the spoken word *less* readable than the rest of the caption | tiny (1 data edit) |
| **P2** | Add a second educational template | `EDUCATIONAL` is the thinnest category at 1 of 45 | small (1 data edit + count pins) |
| **P2** | Decide D6: either document 1.12 as an authoring cap in the model KDoc, or enforce `0.8‥1.12` at boundaries 4–5 | Today restore can exceed the cap while the JSON path cannot | small, needs sign-off (changes rendering) |
| **P2** | Reconcile `decorativeExportStyle()` with `FreePositionTextOverlay`'s default shadow | Closes the last known decorative-path parity risk | small |
| **P3** | Refresh `docs/MANUAL_TEST_CHECKLIST.md` (17 → 45 templates, fix the corrupted tab label) and sweep the stale `ggml-medium` references in `androidTest` | Keeps the manual/QA surface truthful | small |
| **P3** | Add a Persian rendering check that runs on a device in CI | Would catch shaping/wrap regressions the JVM cannot | medium |

### Explicitly **not** done

- No template was added, removed or restyled (the two contrast defects are **reported**, not
  silently changed — see §8.4).
- The `1.12` cap was neither raised nor lowered.
- No architectural change, no new dependency, no new paid API, no login/subscription requirement.
- No test was deleted or weakened; the one pinned count that *had* to change
  (`SrtFixtureFilesTest` 10 → 11 fixtures) was updated on purpose and by name.
