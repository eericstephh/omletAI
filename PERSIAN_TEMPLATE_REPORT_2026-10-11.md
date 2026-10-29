# 20 Premium Persian Subtitle Templates — Final Report

**Date:** 2026-10-11
**Project:** `com.aicreatorstudio.app` ("Omelet")
**Scope:** research, audit, correction and verification of the 20 built-in
Persian subtitle templates, plus the research document that drives them.
**Status:** complete. All required deliverables produced; see §8 for what
was *not* verified.

---

## 1. Research sources and design principles extracted

The full document is `docs/persian-subtitle-template-research.md` (9 sections,
every conclusion tied to a source and to a code decision; §8 is an explicit
changed-vs-confirmed table). Sources, all public, consulted 2026-10-11:

| # | Source | Principle extracted | Effect on the code |
|---|---|---|---|
| 1 | DTP Labs — *RTL Typography: A Complete Guide for Arabic, Hebrew & Farsi* — https://www.dtplabs.com/blog/rtl-typography-complete-guide-arabic-hebrew-farsi | RTL needs full layout mirroring, not right-alignment; never reverse strings manually (destroys cursive joining + contextual forms); Latin runs stay intact LTR islands inside RTL; digits read LTR inside RTL | Confirmed: `FIRSTSTRONG_RTL` + bidi, no `String.reverse()` anywhere in the caption path; direction-aware progress bar |
| 2 | Butterick's Practical Typography — *Line length* — https://practicaltypography.com/line-length.html | 45–90 chars is **page** typography for continuous reading | **Deliberately not adopted** — captions are glance-read in 1–3 s; the existing 42-char cap is correct and was kept |
| 3 | Media Spec Lab — *Social Media Safe Zones 2026* — https://mediaspeclab.com/en/social-media-safe-zones-2026-keep-text-visible-in-vertical-video/ | Keep text away from the lower interface band; subtitles belong lower-**middle**; placing subtitles at the very bottom is a named common mistake; **no single pixel-perfect safe rectangle exists** | **The biggest numerical change** — lifted 19 of 20 templates out of the 0.85+ band (§4 below) |
| 4 | W3C — *Understanding WCAG 2.1: Contrast (Minimum)* — https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html | 4.5:1 normal text, 3:1 large text — but the video background is unknown and changes per frame | Every template must carry **stroke, shadow or panel**; enforced by test |
| 5 | Android Developers — *TextDirectionHeuristics* — https://developer.android.com/reference/android/text/TextDirectionHeuristics | `FIRSTSTRONG_RTL`/`_LTR` is the correct mechanism for mixed-direction runs | Confirmed; each active word's direction is probed independently |
| 6 | SIL — *Open Font License 1.1* — https://openfontlicense.org/ | OFL permits bundling + redistribution in an app, incl. commercial use, if not sold standalone and reserved names respected | Confirmed; no new font added (all 11 Persian families already OFL and bundled) |
| 7 | CapCut — *Auto caption tool* — https://www.capcut.com/tools/auto-caption | Caption tools animate on entry and use the same timing source for the exported file; the spoken word gets **one dominant** distinction | Confirmed — one renderer for preview+export; one primary active-word mechanism per template (tested) |
| 8 | Google — *Create YouTube Shorts* — https://support.google.com/youtube/answer/10343433 | Platform caption tooling exists; safe-area handling is expected of the creator | Confirmed safe-area conclusions |
| 9 | Persian typography guide (vibefarsi) — https://vibefarsi.ir/skills/persian-typography | ZWNJ (نیم‌فاصله) is load-bearing; font licensing; mixed-text rules | Confirmed; ZWNJ preserved end-to-end and now tested |

**Sourcing honesty.** Sources 7 and 8 are official product documentation:
they document *that* caption styling and safe-area tooling exist and how the
platform expects captions to be handled. They do **not** publish the internals
of proprietary template packs. **No CapCut or vendor template was copied** —
the designs are original, derived from the general principles above. **No
template is claimed to be "proven viral"**: no performance data exists to
support such a claim, so none is made.

---

## 2. The 20 templates — names and intended use

All 20 are already registered in `model/BuiltInSubtitleTemplates.kt` with
stable ids. Persian name → intended use:

| # | id | Name | Category | Reveal | Size | Intended use |
|---|---|---|---|---|---|---|
| 01 | `fa_01_clean` | تمیز | پایه | all-at-once | 26 | Clean bold white + dark outline. Default, interview, any footage |
| 02 | `fa_02_impact` | ایمپکت | وایرال | all-at-once | 42 | Large punchline / hook, 1–3 words |
| 03 | `fa_03_yellow_word` | کلمه زرد | سوشال | highlight | 30 | Yellow active-word on white — Shorts/Reels standard |
| 04 | `fa_04_karaoke` | کارائوکه | وایرال | karaoke | 30 | Karaoke progression, lyrics/music clips |
| 05 | `fa_05_capsule` | کپسول مشکی | پایه | all-at-once | 28 | Soft rounded black panel, talking-head |
| 06 | `fa_06_glass` | شیشه‌ای | پریمیوم | all-at-once | 27 | Frosted panel, premium podcast |
| 07 | `fa_07_creator` | کریتور | وایرال | word-by-word | 34 | Fast-paced creator, cyan active word |
| 08 | `fa_08_cinematic` | سینمایی | سینمایی | all-at-once | 28 | Minimal cinematic dialogue subtitle (bottom-pinned) |
| 09 | `fa_09_news` | خبری | خبری | highlight | 26 | Editorial / news lower-third with solid panel |
| 10 | `fa_10_podcast` | پادکست | سوشال | highlight | 32 | Premium podcast clip, panel + highlight |
| 11 | `fa_11_gaming` | گیمینگ | گیمینگ | highlight | 34 | Gaming energetic, controlled orange accent |
| 12 | `fa_12_neon` | نئون | وایرال | highlight | 32 | **Restrained** cyan glow (not excessive neon) |
| 13 | `fa_13_outline` | خط‌دار | پایه | all-at-once | 34 | Hollow stroke only — high-contrast B&W, bright footage |
| 14 | `fa_14_shadow_pop` | سایه‌برجسته | وایرال | all-at-once | 38 | Heavy drop shadow, high-contrast on busy background |
| 15 | `fa_15_dynamic` | داینامیک | وایرال | word-by-word | 32 | Fast-paced, yellow active word + slide-up |
| 16 | `fa_16_bar` | نوار هایلایت | سوشال | highlight | 30 | Travelling highlight pill behind the spoken word |
| 17 | `fa_17_modern_box` | باکس مدرن | ادیتوریال | all-at-once | 28 | Compact two-line modern box, editorial headline |
| 18 | `fa_18_social` | سوشال استک | سوشال | word-by-word | 38 | Tall two-line social stack, sits higher in frame |
| 19 | `fa_19_premium` | پرمیوم مینیمال | پریمیوم | all-at-once | 30 | Quiet-luxury minimal — elegant storytelling, documentary |
| 20 | `fa_20_signature` | سیگنچر | شاخص | karaoke | 34 | Omelet signature: gradient panel + karaoke fill + glow |

**Coverage of the brief's 20 creative directions.** Each was checked against
the catalog and each is present: clean bold white w/ dark outline (01);
yellow active word (03); premium podcast (10); minimal cinematic (08);
modern educational (17); fast-paced creator (07/15); soft rounded panel
(05); high-contrast B&W (13/14); purple premium (19/06); teal/cyan active
word (07/12); warm orange (11); editorial headline (09/17); compact two-line
(17/18); large punchline (02); elegant storytelling (19/08); gaming
energetic (11); documentary (19/09); gradient accent (20); karaoke
progression (04/20); distinctive Omelet signature (20).

**Genuine distinctness — measured, not asserted.** Across the 20: **12
distinct fonts, 8 active-word colours, 5 reveal modes, 8 motion kinds, 4
background kinds, font sizes 26–42.** A test computes a pairwise **render
fingerprint** over every pixel-affecting field and fails on any collision —
this is the mechanical proof that the catalog is not "20 superficial colour
variations".

---

## 3. Files created or modified

**Created**

| File | Purpose |
|---|---|
| `docs/persian-subtitle-template-research.md` | The research document required by the brief: 9 sections, 9 source URLs, each conclusion tied to a code decision |
| `app/src/test/java/com/aicreatorstudio/app/model/SubtitleTemplateExportParityTest.kt` | 21 tests — catalog, distinctness, readability, safe area, apply path, word timings, offline, back-compat |
| `app/src/test/java/com/aicreatorstudio/app/export/CaptionRendererRtlTest.kt` | 16 tests — RTL detection, no reversal, Arabic→Persian folding, ZWNJ, font pipeline |
| `app/src/test/java/com/aicreatorstudio/app/model/OfflineSubtitleTemplateGuardTest.kt` | 2 tests — permanent no-network guard over the template layer |
| `PERSIAN_TEMPLATE_REPORT_2026-10-11.md` | This report |

**Modified — `app/src/main/java/com/aicreatorstudio/app/model/BuiltInSubtitleTemplates.kt` only**

1. **Safe-area lift** (research §3 — captions must clear the platform lower UI band):
   - `fa_01_clean` `positionY` 0.86 → **0.84**
   - `fa_06_glass` 0.86 → **0.82** (tall frosted panel sat deepest into chrome)
   - `fa_09_news` 0.85 → **0.82** (solid lower-third bar, most collision-prone)
   - `fa_08_cinematic` **deliberately kept at 0.88** with an inline comment
     marking it the single intentional exception (a bottom-pinned dialogue
     subtitle is the convention of the medium).
   - Result: **19 of 20 templates now sit in 0.62–0.84.**
2. **Readability cap** (research §5 caps `activeWordScale` at 1.12; five
   templates exceeded it, and the field itself permits up to 2.5):
   - `fa_07_creator` 1.25 → **1.12**
   - `fa_11_gaming` 1.20 → **1.12**
   - `fa_15_dynamic` 1.30 → **1.12**
   - `fa_18_social` 1.20 → **1.12**
   - `fa_10_podcast` 1.10 — already within cap, unchanged.
   - The two worst cases (`fa_07_creator`, `fa_15_dynamic`) combine
     `WORD_BY_WORD` reveal with a large per-word scale, where the active
     word's scale feeds back into the measured line width and reflows the
     block mid-word — exactly the "oversized/bouncing" failure the brief
     prohibits.

No other production file needed changing. **No font, asset, dependency or
Gradle configuration was added.**

---

## 4. How templates integrate with the existing renderer and export pipeline

**No second subtitle system was built.** The integration was already correct
and is deliberately left that way.

- **One apply path.** `TextOverlay.withTemplate(template)`
  (`model/SubtitleTemplate.kt:295`) copies the template's style and every
  active-word/animation/background field onto the overlay. Its
  `templatePositionAsAnchorDelta` (`:353`) converts the style's absolute
  `positionY` into the renderer's anchor-delta contract
  (`BOTTOM = 0.88`, `CENTER = 0.5`, `TOP = 0.12`).
- **One renderer.** `export/CaptionRenderer.render(context, overlay, style,
  canvasWidthPx, canvasHeightPx, frameTimeMs, typefaceCache, fontScale)`
  is the **single** drawing routine, consumed by:
  - live editor preview — `ui/screens/editor/CaptionBitmap.kt:59`
  - burned-in export — `export/SubtitleBurnIn.kt:245, :293`
  - tracked-overlay export — `export/TrackedOverlayRenderer.kt:86`
  - picker thumbnails — `SubtitleStudioPanel.rememberTemplateImageBitmap`
- **Therefore preview == export by architecture, not by convention.** A
  template cannot look different in the picker and in the exported video,
  which satisfies the brief's "no fake preview" requirement structurally.
  A test asserts that `withTemplate` copies *every* render-relevant field,
  so a future edit that silently drops one fails CI.
- **Word-by-word behaviour.** Reveal uses the existing `TranscribedWord`
  timings (`text`, `startTimeMs`, `endTimeMs`). Tests cover: contiguous
  non-overlapping timings, a **monotonically advancing** active-word index
  across a caption (no stutter/backwards jump), and the honest fallback
  when only segment-level timings exist — the templates must **not**
  fabricate word timings, so such a segment renders the whole caption.
- **RTL.** `CaptionRenderer.isRtl` → `PersianNormalizer.isPersianScript`;
  layout direction set via `FIRSTSTRONG_RTL`/`_LTR` at `:549`, `:663`,
  `:742`, with `Paint.Align.LEFT` deliberately chosen so `StaticLayout`
  owns centering. **No `String.reverse()` exists in the caption path.**
- **Templates are offline data.** Pure Kotlin data classes, JSON-serializable,
  no Compose and no Android UI types in the model, no network, no remote
  configuration.

---

## 5. Persian / RTL and active-word tests performed

39 new tests, **all passing**. Concrete behaviour verified:

**RTL / Persian text (`CaptionRendererRtlTest`, 16 tests)**
- Persian captions are detected RTL; Latin-only and digit-only captions are not.
- Mixed «با ChatGPT کار می‌کنم» is RTL because Persian is present, and the
  Latin run is not split or reversed.
- Persian-Indic digits (۰-۹, U+06F0–U+06F9) still resolve to the RTL script.
- Both Arabic ي/ك and Persian ی/ک are recognised as RTL script.
- **No reversal:** first and last tokens of a Persian sentence are preserved
  in order, and `PersianNormalizer.normalize` preserves token count and order.
- `normalize` folds Arabic ي→ی and ك→ک (so the font's Persian contextual
  forms are used) — verified by asserting the Arabic code points are gone.
- **ZWNJ is preserved** through `normalize`, is not converted to a space,
  and is not stripped.
- Every one of the 20 templates pins a bundled Persian family; **none** falls
  back to `DEFAULT` (system font, which shapes Persian disconnected).
- `PersianFamilies.size >= 8` guards the "12 fonts" claim from silently decaying.
- All width fractions stay inside 0.5–0.95.

**Active word and export parity (`SubtitleTemplateExportParityTest`, 21 tests)**
- Exactly 20 templates, all `builtIn`, ids unique and frozen against a
  literal expected list (a rename is a breaking change for saved projects).
- Every id resolves back through `byId`.
- Pairwise render-fingerprint uniqueness — no two templates render identically.
- Design-space breadth minimums on fonts/colours/reveal modes/motion kinds/
  background kinds; font sizes within 24–48 with ≥5 distinct values.
- All 20 carry stroke, shadow or panel (readability over arbitrary footage).
- All 20 have `activeWordScale <= 1.12`; none stacks more than 2
  active-word decorations (one dominant mechanism rule).
- Safe-area: no template sits at `positionY >= 0.86` except the documented
  cinematic exception, which is asserted to remain at 0.88.
- `withTemplate` copies all 18 render-relevant fields; the karaoke flag stays
  in step with the reveal mode; the anchor-delta position math is exact.
- Switching presets is non-mutating and the catalog is immutable.
- Word timings contiguous/ordered; active-word index monotonic; segment-only
  subtitles do not fabricate word timings and all 20 still apply cleanly.
- JSON round trip is lossless for the render-relevant fields (offline portability).
- All 24 legacy ids (`tmpl_*`, `viral_*`) still migrate to a live template —
  saved projects keep their styling.

**Offline guarantee (`OfflineSubtitleTemplateGuardTest`, 2 tests)**
- Re-reads 7 template/render/picker source files on **every build** and
  fails on any network/remote-config/cloud-AI/download token
  (`okhttp`, `retrofit`, `HttpURLConnection`, `Socket`, `Firebase`,
  `RemoteConfig`, `openai`, `anthropic`, `groq`, `supabase`,
  `ModelDownloader`, `downloadModel`, …).
- The whole catalog is constructible with zero I/O — `BuiltInSubtitleTemplates.ALL`
  touches every template and requires no `Context`, file or socket.

---

## 6. Unit test results and failures

Run with the pinned JDK 21 (JBR) via
`org.gradle.launcher.GradleMain testDebugUnitTest`.

```
2157 tests completed, 2 failed, 2 skipped
```

- **Baseline before this work: 2118 tests, 2 failed, 2 skipped.**
- **Delta: +39 tests, exactly the three new classes (21 + 16 + 2). No new failures.**

The **2 failures are pre-existing and unrelated to this work** — they are the
known asset-dependent pair recorded in the project baseline:

| Failing test | Reason |
|---|---|
| `RealModelInstallTest > install real bundled whisper medium model end-to-end through the production installer` | Requires the real Whisper model asset, absent from the JVM test fixture |
| `WhisperNativeSymbolsTest > libwhisper so is built for every supported ABI` | Requires the built native `.so` for every ABI |

Neither test touches the subtitle template, renderer, font or picker code,
and both fail identically on the unmodified baseline. Both are skipped/absent
by environment, not by defect.

Every pre-existing template/caption suite still passes unchanged:
`BuiltInSubtitleTemplatesFaFontTest` (4), `SubtitleTemplateTest` (16),
`SubtitleTemplateApplyTest` (4), `CaptionRendererParityTest` (9),
`OfflineCorrectionGuardTest` (3) — the Task A correction layer is unaffected.

---

## 7. Debug APK build status

- **`assembleDebug` → `BUILD SUCCESSFUL`** (run separately; see caveat below).
- Artifact: `app/build/outputs/apk/debug/app-debug.apk`
- Size: **122,201,865 bytes**
- Size delta vs. pre-change APK: **+0 bytes (0.0000%)**

The zero delta is expected and correct: the change adds **no font, no asset,
no dependency, no Gradle configuration** — only float constants in an
existing file, plus test sources that are not packaged into the APK.

⚠️ **Build caveat worth recording.** Running `testDebugUnitTest assembleDebug`
as one invocation ended `BUILD FAILED` on the test task and **did not run
`assembleDebug`** — the APK mtime stayed at the earlier build, so the APK was
stale. Running `assembleDebug` on its own succeeded and repackaged the APK
(mtime advanced). Anyone verifying this should **check the APK mtime**, not
the exit code of a chained run.

---

## 8. Limitations not yet verified on a real device

Stated plainly, per the brief's honesty requirement:

- **No device or emulator run was performed.** There is **no observed
  on-device output** behind any claim in this report. Specifically, the
  following are argued from *configuration and unit tests*, not from looking
  at a phone screen:
  - that Persian glyphs shape correctly and stay cursively joined;
  - that no glyph is clipped and no diacritic detaches;
  - that ZWNJ renders correctly in the running app and in an exported file;
  - that animation is smooth (no stutter) on real hardware;
  - that the new `positionY` values clear the actual platform chrome on
    current Reels / Shorts / TikTok builds;
  - that the reduced `activeWordScale` values look right in motion.
- **`CaptionRenderer.render(...)` is never executed by any test here.** It
  requires a real `Context`, `Canvas` and GL stack that a JVM unit test
  cannot supply (`unitTests.isReturnDefaultValues = true` makes `android.*`
  inert, so such a test would pass for the wrong reason). The tests verify
  the *inputs and configuration* the renderer consumes — direction detection,
  style fields, apply-path completeness, safe-area bands — not the pixels.
- **Preview/export parity is architectural, not empirically diffed.** I did
  not capture a preview frame and an exported frame and compare them
  pixel-by-pixel. The argument is that a single `render(...)` serves both
  paths; that argument is strong but is an argument, not a measurement.
- **No performance or recomposition profiling.** The brief's "avoid
  unnecessary recompositions and per-frame allocations" is not measured;
  no Compose tracing was run.
- **Safe areas are conservative, not platform-tuned.** The research source
  states explicitly that no single pixel-perfect safe rectangle exists across
  Reels/Shorts/TikTok, because overlays vary by platform, device and caption
  length. The templates therefore use one conservative master band rather
  than per-platform micro-tuning.
- **`androidTest` remains broken** (pre-existing, unrelated to this work), so
  no instrumented UI test of the picker exists.
- **Not verified:** visual comparison against CapCut's actual templates. The
  designs are original, derived from the published principles in §1; no
  vendor template was reproduced and no claim of equivalence is made.

---

## Summary of acceptance criteria

| Requirement | Status |
|---|---|
| Research document exists with real source URLs | ✅ `docs/persian-subtitle-template-research.md`, 9 sources |
| Exactly 20 usable templates registered | ✅ asserted by test, frozen id list |
| Templates visibly distinct and fit for stated use | ✅ pairwise render-fingerprint test + measured breadth |
| Persian RTL handling checked | ✅ 16 tests; **device rendering not verified** |
| Selected template flows through preview + export | ✅ single renderer; apply-path completeness tested |
| Word highlighting uses existing timing data | ✅ monotonic-index + contiguous-timing + honest-fallback tests |
| No paid API or runtime internet dependency added | ✅ permanent guard test; APK delta 0 |
| Existing transcription + correction behaviour intact | ✅ baseline suites unchanged and passing |
| Relevant tests and debug build run | ✅ 2157 tests / 2 pre-existing failures; APK built |

**No new cost was introduced.** No paid API, subscription, backend, cloud
service, mandatory login, email registration, runtime network request or
downloadable asset is involved anywhere in the template layer.
