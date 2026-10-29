# Reference Analysis — Subtitle Templates

**Date:** 2026-10-16
**Project:** Omelet / `com.aicreatorstudio.app` (versionName `1.4.0`, versionCode `4`)
**Scope:** the subtitle-template system — what it must do, what was verifiable, and
what could **not** be verified on this host.
**Related:** [`docs/persian-subtitle-template-research.md`](../persian-subtitle-template-research.md)
(existing public-source research), [`implementation-plan.md`](implementation-plan.md).

---

## 1. Purpose and method

The brief's Phase 2 asks for a reference investigation of a comparable
subtitle-template system, using REA on **legally obtained reference binaries
only**, with strict separation of *confirmed evidence*, *hypotheses* and
*unknowns*.

This document records:

1. what the reference investigation could actually achieve on this machine
   (§2 — **blocked**, with exact reasons),
2. the evidence that *was* obtained, from artifacts we are entitled to inspect
   (§3),
3. confirmed findings / hypotheses / unknowns, kept strictly apart (§4),
4. the independent design conclusions the implementation rests on, each tied to
   its source (§5),
5. legal position and limitations (§6, §7).

Nothing in this document is presented as a finding unless it was directly
observed. Where the only honest option was an independent design decision, that
is labelled as such.

---

## 2. Phase 2 status: BLOCKED (reference-binary analysis not performed)

The brief's reference target is a third-party short-video editor. Three
independent blockers apply, and each was verified rather than assumed.

### 2.1 No legally obtained reference binary is present

A recursive search for Android packages across the workspace returns **only
Omelet's own builds**:

| Location | File |
| --- | --- |
| `app/build/outputs/apk/debug/` | `app-debug.apk` |
| `app/build/outputs/apk/release/` | `AICreatorStudio-v….apk` |
| `app/build/outputs/apk/androidTest/debug/` | test APK |
| `dist/` | five `Omelet-1.4.0-debug-*.apk` variants |

No third-party editor APK/APKS/XAPK is present. Downloading one would not make
it "legally obtained" for this purpose, so none was fetched. **The reference
binary was therefore not analysed.**

### 2.2 REA is installed and runnable, but the Android provider is unavailable

REA was pinned and invoked as `npx -y rea-agents@6.4.0` through
`C:\Program Files\nodejs\npx.cmd` (the `.ps1` shim is blocked by the host's
PowerShell execution policy). The CLI **does** run and `inspect-artifact` /
`extract-artifact` work (they were used in the preceding audit).

| REA operation | Result on this host |
| --- | --- |
| `inspect-artifact` | **Works** — used for §3 |
| `extract-artifact` | **Works** — used for §3 |
| `inspect-android-package` | **Blocked** — requires a caller-supplied `REA_JADX_MCP_JAR`; JADX is not installed and REA will not install it |
| `inspect-binary-layout` | **Blocked** — "pwntools artifact profile supports Linux x64 only"; this host is Windows |
| native-symbol analysis (Ghidra / Hopper) | **Unavailable** — Ghidra's Windows profile is P0 / no DB mutation; Hopper is macOS-only |

Consequently no Android-class or native-code analysis was attempted on a
reference app.

### 2.3 Consequence for the deliverable

Per the brief's own fallback rule, the implementation is designed
**independently**, from (a) our own inspectable artifact and (b) publicly
observable product behaviour, and the missing reference analysis is recorded
here as an open blocker rather than papered over with invented detail.

---

## 3. Evidence actually obtained

All items below come from artifacts we are entitled to inspect: Omelet's own
debug build and the repository itself.

| # | Evidence | How obtained |
| --- | --- | --- |
| **E1** | No third-party editor package in the workspace | recursive file search for `*.apk`/`*.apks`/`*.xapk` |
| **E2** | Omelet debug APK identity: `sha256 41394C3B…17A5A`, 121,032,561 bytes, 968 archive entries | hash + archive inspection (state at audit time; the APK is rebuilt by any subsequent build, so this hash identifies the *audited* artifact, not a released one) |
| **E3** | Subtitle-relevant members shipped in our APK: `assets/subtitle_templates.json`; `assets/fonts/vazir_regular.ttf`, `vazir_bold.ttf`; `lib/arm64-v8a/libwhisper.so` | archive entry listing |
| **E4** | **45** members under `res/font/` — the complete free (OFL) Persian families: Vazirmatn (+ light/medium/bold/black + `vazirmatn_family.xml`), Sahel, Shabnam, Samim, Estedad, Parastoo, Gandom, Nahid, Tanha, Lalezar, Vazir Code | archive entry listing |
| **E5** | `abiFilters = listOf("arm64-v8a")` only; `libwhisper.so` is the sole STT native library present | `app/build.gradle.kts:143`; archive listing |
| **E6** | Building config: `compileSdk 35`, `targetSdk 35`, `minSdk 24`, NDK `30.0.16138531`, Kotlin `2.0.21`, AGP `8.13.2`, JVM target 17 | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| **E7** | The repository already contains public-source design research for Persian subtitle layout, RTL progress-bar direction, and the active-word scale cap | `docs/persian-subtitle-template-research.md` |

**E3/E4/E5 relevance.** Persian text rendering is *self-sufficient offline*:
every family the catalog can name is either bundled (E4) or resolved through
`FontFallback` to a bundled family. Speech-to-text is on-device via
`libwhisper.so` (E5); word-level timing therefore originates locally and needs
no network. This is a confirmed capability, not a hypothesis.

---

## 4. Confirmed findings, hypotheses, unknowns

### 4.1 Confirmed (directly observed)

| # | Finding | Evidence |
| --- | --- | --- |
| C1 | No third-party reference binary is available; reference analysis is blocked | E1, §2.2 |
| C2 | The app ships **25** JSON templates (`assets/subtitle_templates.json`, schema `3.0`) **and** **26** hand-built `fa_*` designs (`BuiltInSubtitleTemplates.ALL`). The Subtitle Studio serves the **JSON catalog** (`EditorViewModel` → `uiState.jsonCatalogTemplates`) | asset contents; `JsonTemplateParserTest`; `EditorViewModel.kt:3949–3956` |
| C3 | The JSON catalog's authoring `category` key has **5** values — `popular`, `dynamic`, `fantasy`, `standard`, `cinema` — which do **not** match the brief's eight categories | asset contents; `JsonTemplateMapper.groupByCategory` |
| C4 | Preview and export share one rasterizer: the Compose preview (`CaptionBitmap.kt`) and the burn-in exporter (`SubtitleBurnIn.kt:245,293`) both call `CaptionRenderer.render` | source |
| C5 | Word-level timing is real and reuses the recognizer's own timestamps: `TranscribedWord(id, text, startTimeMs, endTimeMs, confidence)` reported by `whisper_full_get_word_t`; "fake timing is never synthesized by the AI path" | `ai/Transcription.kt:5–26`; `BuiltInSubtitleTemplates.kt:18–21` |
| C6 | **18 of 25** shipped JSON templates resolve to an `activeWordScale` **above 1.12**: sixteen at `1.15`, `keyword_punch_zoom` at `1.4`. The repo's own research sets the cap at **1.12**, and `SubtitleTemplateExportParityTest` enforces ≤1.12 for the hand-built catalog | computed from the asset using the mapper's own rule (`keywordScaleBoost ?: if(highlightActive) 1.15 else 1.0`); `docs/persian-subtitle-template-research.md:229,297`; `JsonTemplateMapper.kt` |
| C7 | `EditorViewModel.kt` contains mojibake (replacement characters) in string literals — pre-existing, unrelated to templates | prior audit of the same file |
| C8 | The template layer is guarded as fully offline by an automated test | `OfflineSubtitleTemplateGuardTest` |

### 4.2 Hypotheses (clearly unverified — each with the test that would settle it)

| # | Hypothesis | How to confirm |
| --- | --- | --- |
| H1 | C6 is a mapper default (`1.15`) applied before the 1.12 cap was introduced for the hand-built set, i.e. an oversight rather than an intentional per-catalog difference | clamp the mapper to 1.12 and check that no product requirement (e.g. `JsonTemplateMapperTest`) or design note depends on 1.15 |
| H2 | The brief's "≥20 presets across eight categories" is satisfied by *re-labelling* the existing catalog, i.e. no new artwork is strictly required | the taxonomy in §5.3 puts all 25 into the eight buckets; only *dedicated* podcast/educational designs are genuinely absent |
| H3 | CapCut-style editors drive word highlighting from recognizer word timestamps rather than from per-word user edits | would need reference-binary analysis (blocked) or vendor documentation; **not** relied upon |

### 4.3 Unknowns (explicitly not determined)

- The internal data model, animation curves, layout algorithm and asset format
  of any third-party editor. **Not investigated** (blocked, §2.2). No claim is
  made about them anywhere in this work.
- Whether third-party templates use recognizer timings or hand-authored ones.
- Real-world perceptual quality of the eight categories with actual Persian
  speech: **untested** — no device/emulator run was performed, and no user
  testing exists.

---

## 5. Design conclusions used by the implementation

Each conclusion states its source and whether it came from evidence or is an
independent design decision.

### 5.1 Reuse the existing word-timing data model (from C5)

The brief's rule is: *if word-level timestamps already exist, reuse them;
never fake equal division.* Omelet's recognizer already emits per-word
`startTimeMs`/`endTimeMs` (C5). The implementation therefore reuses
`TranscribedWord` unchanged rather than introducing a second timing type.
**No new timing model, no synthesised timings.**

### 5.2 Keep one rasterizer for preview and export (from C4)

Parity is an architectural property here, not a hope: preview and export call
the same `CaptionRenderer`. The implementation must not introduce a second
rendering path for the new categories. This constraint is recorded so a future
change cannot silently fork the two.

### 5.3 The eight categories are a *derived browse taxonomy* (independent decision)

The brief names eight categories; the shipped catalog uses five different ones
(C3). Changing the catalog's stored `category` values would invalidate saved
projects' grouping and break catalog tests that pin the existing keys.

**Decision:** add the eight categories as a *view* over the catalog
(`SubtitleTemplateCategory` + `SubtitleTemplateTaxonomy`), derived
deterministically from each template's already-resolved properties, leaving the
JSON and every template id/style untouched. Rationale:

- it satisfies "≥20 presets across these categories" using the 25 that already
  ship (H2),
- it is additive: no saved project, renderer input or existing test changes,
- it is reversible — deleting two files restores the previous grouping.

### 5.4 Persian/RTL behaviour follows the existing researched rules (from E7)

Direction-aware progress fill, mirrored layout where a direction-sensitive
element exists, bundled Persian families only, and no manual string reversal
are already established by the repository's public-source research and are
enforced by tests. The new taxonomy is display-only and must not alter them.

---

## 6. Legal and ethical position

- Exactly one binary was inspected, and it is **our own** build (E2–E5).
- No third-party application, font, artwork or template file was copied,
  decompiled or redistributed.
- No access-control or DRM mechanism was bypassed; none was encountered.
- Fonts referenced by the catalog are either bundled OFL families (E4) or
  resolved to a bundled family by `FontFallback`. Commercial Persian fonts
  (IRANSans, Yekan, Dana, Kalameh, Morabba, Peyda) are deliberately **not**
  bundled because they lack a redistribution licence
  (`model/TextOverlay.kt:226–229`).
- The template designs are original compositions by this project. Their
  *inspiration* is observable, publicly visible product conventions (bold
  centre captions, word-by-word karaoke, boxed lower-thirds), which are
  uncopyrightable ideas; no expression was reproduced from a third party.

---

## 7. Limitations of this analysis

1. **No reference-binary analysis.** Phases described as "reverse-engineer the
   reference app" were not performed; §2 records why. Any conclusion that would
   have required it is marked unknown (§4.3).
2. **No device or emulator verification.** All verification in this work is
   JVM unit tests plus a compile/package build. Visual output on a real device,
   gesture behaviour, and performance under load are **unverified**.
3. **No user testing or analytics exist** for these templates; no such data is
   claimed.
4. **Reported scale-overflow (C6)** is measured against the mapper's own
   resolution rule, not against rendered pixels; it is a design-rule violation,
   not a measured visual defect.
5. **The APK hash in E2** identifies the artifact as it existed at audit time;
   it changes on every rebuild and does not identify a release.
