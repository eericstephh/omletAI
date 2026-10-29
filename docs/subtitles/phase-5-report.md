# Subtitle Visual QA Showcase & Template Polish — Phase 5 Report

**Date:** 2026-10-16
**Project:** Omelet / `com.aicreatorstudio.app` (versionName `1.4.0`, versionCode `4`)
**Scope:** a debug-only visual validation surface for the subtitle templates, the three
readability defects recorded in the Phase 4 QA report, and an independent design brief for
the next catalog phase — without rewriting the renderer.
**Related:** [`qa-report.md`](qa-report.md) · [`implementation-plan.md`](implementation-plan.md) ·
[`reference-analysis.md`](reference-analysis.md)

## Verdict legend

| Label | Meaning |
|---|---|
| **VERIFIED** | The check was executed on this host and the actual result is quoted below. |
| **FIXED** | A confirmed defect; the fix, its measured effect and its tests are in §4. |
| **BLOCKED** | The check cannot be executed in this environment. Exact manual steps are given. |
| **UNVERIFIED** | Not covered by any check that ran here. |
| **FAILED** | The check ran and failed. |
| **OBSERVATION** | A measured fact that is not a defect. |

---

## 1. Summary

| # | Task | Outcome |
|---|---|---|
| 1 | Debug-only subtitle showcase | **VERIFIED (compiles + unit-tested)** — renders the production registry through the production renderer; absent from release builds by source-set construction. Never launched on hardware (§8). |
| 2 | Manual device validation path | **DOCUMENTED** — install/launch commands and an inspection checklist in §3.3. Not executed: no device (§8). |
| 3 | Three readability defects | **FIXED** — active-word contrast 1.65:1 → **5.39:1** and 1.43:1 → **14.67:1**; the two plate-less presets gained a subtle outline. Each change is pinned by tests, and the QA allow-lists were deliberately emptied. |
| 4 | Catalog balance | **VERIFIED** — 45 templates, all eight categories populated, counts **13 / 7 / 5 / 6 / 1 / 5 / 4 / 4**. Design brief for the next phase in §5.3. No templates added. |
| 5 | Validation | **PARTIAL** — 2 925 unit tests pass, debug APK builds, androidTest compiles. `lintDebug` **FAILED** in the lint tool itself (§6.3). Device execution blocked. |

**Counts that changed this phase:** unit tests **2 905 → 2 925** (+13 showcase, +7 QA);
catalog entries, pinned template counts and the `1.12` animation cap are all **unchanged**.

**Deliverable map.**

| Requested deliverable | Where |
|---|---|
| Showcase implementation | §3 |
| Launch instructions | §3.3 |
| Changed files | §7 |
| Verified registry and category counts | §5.1 |
| Readability defects and proposed/final fixes | §4 |
| Automated test results | §6.1 – §6.3 |
| Physical-device test checklist | §3.3 (inspection steps 1-7) |
| Remaining unverified behaviour | §8 |
| Prioritized proposal for the next template-design phase | §9 |

---

## 2. Environment and what could actually be run

Same host as Phase 4: Windows, no Android device and no emulator attached, no `adb`
target. All figures below come from the Gradle invocations recorded verbatim in §6.

Seven files were **added** and three **modified** (§7). This is not a git repository, so
every change is an uncommitted file on disk and the "changed files" list is by inspection,
not by diff.

> **PowerShell note (applies to every command in §6).** PowerShell treats Gradle's stderr
> warnings as errors, so the process exit code is **not** a reliable pass/fail signal — the
> `1` reported after several `BUILD SUCCESSFUL` runs is that artefact. Every result below
> was read from the build output **and** cross-checked against
> `app/build/test-results/testDebugUnitTest/*.xml` and
> `app/build/intermediates/merged_manifests/`. Additionally, the console codepage mangles
> Persian into `????`, so Persian strings were verified by byte-level reads rather than by
> what the terminal shows.

---

## 3. Task 1 — the debug-only subtitle showcase

### 3.1 What it is, and what it deliberately is not

A single development screen that displays **every** template in the production registry
against one deterministic Persian fixture, with a scrubable playhead so the inactive and
active word states can both be inspected.

| Requirement | How it is met |
|---|---|
| Use the real registry and rendering components | `JsonTemplateCatalog` (the same Hilt singleton the editor uses) → `JsonTemplateMapper.mapCatalogState` → `BuiltInSubtitleTemplates.ALL`. Rendering goes `CaptionPreview` → `rememberCaptionImageBitmap` (`CaptionBitmap.kt:34`) → `CaptionRenderer.render` (`CaptionRenderer.kt:329`). |
| Enumerate, don't duplicate | `ShowcaseFixture.combinedRegistry(jsonCatalog)` derives the list from the two real sources and the real dedupe rule. No id is typed twice; a template added to the catalog appears automatically. |
| Name, category, active-word state | `NowPlaying` shows `name · nameEn`, `id`, the category from `SubtitleTemplateTaxonomy.classify`, and either `ACTIVE WORD: 4 / 14 — «…»` or `ACTIVE WORD: none — playhead is between words`. |
| Persian examples | Three authored fixtures with half-spaces (U+200C), Persian-indic digits, Latin inside RTL, and Persian punctuation. |
| Switch inactive ↔ active | Slider scrub, `restart`, `play/pause`, and `next word`. Park the playhead in a gap for the inactive state; play to sweep the animation. |
| Short / long / multiline | Three variants: 1 line / 2 lines / 3 lines. |
| All eight categories | Grouped by the production taxonomy, with a category filter chip row. |
| Stay out of release | `src/debug` source set only. §3.2 has the proof. |
| Follow Compose / Hilt / variants | `@AndroidEntryPoint` activity + `@HiltViewModel` + `hiltViewModel()` + `collectAsStateWithLifecycle`; chunked-row grid mirroring `SubtitleStudioPanel`. |
| No second caption renderer | There is no Compose `Text` drawing a caption anywhere in the showcase. The only caption pixels are the bitmap from `CaptionRenderer`. |

**Honest limitations of the surface itself.**

- The backdrops are **synthetic, drawn in Compose** — black, white, mid-gray, a split
  light/dark scene, and a "busy" scene with a wide luminance spread. They are *not* real
  video frames. They exist so one caption can be judged against very different
  luminances; final judgement over real footage still needs a device and a clip.
- The registry cards show a **data swatch** (base text / plate / active-word colours read
  off the template), not a caption render. Rendering 45 real caption bitmaps at once would
  cost tens of megabytes for no additional information; the single pinned preview above is
  the real render.
- The preview rasterises at **540 × 960** (half of 1080 × 1920, identical 9:16 ratio) and
  is scaled into a 280 dp box. Proportions, anchors, wrapping and word timing are
  therefore faithful; stroke crispness at true 1080 p is not.
- `fontScale` is left at the production default `1f` (the studio cards use `0.75f`), so
  this preview matches the canvas/export scale, not the picker-card scale.

### 3.2 It cannot reach a release build — measured, not asserted

The showcase lives in the `debug` source set, so it is not compiled into release at all.
No `BuildConfig.DEBUG` guard, no menu entry, nothing to remove before shipping. There is
also **no launcher intent-filter**, so it never appears as a second app icon.

Two independent checks back this up:

| Check | Result |
|---|---|
| `:app:processReleaseManifest` then read the merged release manifest | `SubtitleShowcaseActivity` **absent**, `debugtools` package **absent**, `MainActivity` present |
| `aapt2 dump xmltree --file AndroidManifest.xml app-debug.apk` | `<activity>` declaring `com.aicreatorstudio.app.debugtools.SubtitleShowcaseActivity` with label `Omelet Subtitle Showcase (debug only)` is **present** |

`ShowcaseFixtureTest` additionally fails if any of the five debug files disappear, if the
debug manifest stops declaring the activity, or if a file matching `*Showcase*` appears
anywhere under `src/main`.

### 3.3 Launching it on a physical device (Task 2)

```powershell
# 1. build
.\gradlew.bat :app:assembleDebug

# 2. install (package id gains the ".debug" suffix)
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 3. launch the showcase directly (it has no launcher icon by design)
adb shell am start -n com.aicreatorstudio.app.debug/com.aicreatorstudio.app.debugtools.SubtitleShowcaseActivity
```

`android:exported="true"` on the debug activity is required for `am start` from the shell
UID; it is safe because the component does not exist in release builds.

**What to inspect, in this order.**

1. **Glyph shaping and joining** — on the SHORT fixture, confirm `میروم` (with its
   half-space) joins and shapes correctly, and that no letter falls back to a system face
   on an OEM ROM. The Long fixture adds the `پیشفرضهای` / `همگامسازی` half-spaces.
2. **Half-space behaviour and wrapping** — switch to MULTILINE (3 lines). Confirm the
   half-spaces do not break as a line boundary and that no line overflows the frame.
3. **Mixed direction** — on the LONG fixture, confirm `ChatGPT` sits correctly inside the
   RTL line without reordering the Persian around it.
4. **Contrast over varied backgrounds** — cycle BLACK → WHITE → MID GRAY → SPLIT → BUSY at
   the same template and playhead. The three presets fixed in §4 should be the interesting
   ones: `bounce_text_pop` (white plate), `cinema_yellow_script` (yellow script + new dark
   chip), `cinema_plain_white` (white text + new outline).
5. **Safe margins in 9:16** — leave *guides* on. The three dashed lines are the renderer's
   own style anchors (`anchorFractions`: 0.12 / 0.5 / 0.88). Confirm bottom-anchored
   captions clear the frame edge on a real notched device.
6. **Active-word highlighting and animation** — press play and watch the highlight advance
   through the words; then scrub into a gap and confirm the state line reads
   `ACTIVE WORD: none`. `bounce_text_pop` is the one to watch for overshoot (§4.4).
7. **Template switching and state persistence** — pick templates across all eight category
   groups; rotate the device. The selection, caption variant, backdrop, category filter,
   guide toggle and playhead all survive rotation (`rememberSaveable`). The *product's*
   persistence is separate — `settingsRepository.saveStudioTemplateId` plus the project
   record — and is not exercised by this screen.

**MP4 export cannot be triggered from the showcase, and does not need to be.** Use the
existing editor so the export path stays the one under test:

1. Open the app (launcher icon is the normal app, not the showcase) → open or create a
   project with a video.
2. Add a caption overlay (type text manually, or generate subtitles).
3. Open the Studio panel → **Templates** tab → pick the template.
4. Export. The burn-in runs `SubtitleBurnInFactory` → `CaptionRenderer.render` on the
   same style object the preview drew, so the exported frame is the check that the
   preview told the truth.

Do not record this as passed until it has actually been run on hardware.

---

## 4. Task 3 — the three readability defects, fixed

All three are in `app/src/main/assets/subtitle_templates.json` (the 25 shipped presets).
Sixteen other presets are untouched — the fix is per-template, not a global restyle.

Contrast ratios are WCAG 2.x relative-luminance ratios computed exactly as the Phase 4 QA
suite computes them (alpha composited over black), and are re-asserted by tests in
`SubtitleTemplateCategoryQaTest` §3b.

### 4.1 `bounce_text_pop` — active word unreadable on its own plate (line 47)

| | |
|---|---|
| **Defect** | Active word mint `#00E5A0` drawn on the preset's own opaque white plate `#FFFFFF` = **1.65:1**. Reproduced exactly. |
| **Decision** | Keep the highlight type, the plate, the near-black base text and the BOUNCE_POP motion; change only the active colour to a deep green of the same family. This is the brief's first suggested remedy ("a contrasting active-word color") and the smallest possible diff. |
| **Change** | `wordHighlight.activeColor` `#00E5A0` → `#0B7A4B` |
| **Result** | **5.39:1** on the white plate — clears the 4.5:1 normal-text target with headroom, and stays clearly green. |
| **Identity preserved** | White plate, `#111111` base text, BOUNCE_POP motion unchanged; the "pop" is still a green pop, just one that survives its own background. |

### 4.2 `cinema_yellow_script` — the highlight was invisible (line 168)

| | |
|---|---|
| **Defect** | Active word white `#FFFFFF` against base yellow `#FFD400` = **1.432:1** (measured; the QA report's 1.43:1 is confirmed). A highlight that is darker-or-brighter by 1.4:1 is effectively no highlight. |
| **Decision** | Keep the yellow script and its Lalezar face, and put the *spoken word* on its own dark chip instead of changing its colour. A determinate backing makes the contrast measurable and guaranteed over any footage, which a white-on-yellow colour swap could never be. |
| **Change** | `wordHighlight` `colorChange(active #FFFFFF)` → `box(activeColor #000000CC, boxTextColor #FFD400)`, and `shadow` `none` → `outline(#000000, width 2)` |
| **Result** | Base text stays `#FFD400`; active word is yellow on a `#000000CC` chip = **14.67:1**, and still **8.9:1** even if the chip sits over a fully white frame. |
| **Behaviour unchanged** | `box` maps to the same `WordMotionKind.HIGHLIGHT_COLOR` as `colorChange` (`JsonTemplateMapper.mapMotion`), and `highlightActive` is true for both — so word-by-word tracking is identical. This is asserted in both `JsonTemplateMapperTest` and the QA suite rather than assumed. |
| **Identity preserved** | Still the yellow cinematic script in Lalezar; the chip is a dark accent consistent with the new outline. |

### 4.3 `cinema_plain_white` and `cinema_yellow_script` — no legibility aid (lines 216, 168)

| | |
|---|---|
| **Defect** | Both shipped with `shadow: none`, no plate and no stroke, so white/yellow text could vanish against a bright clip. |
| **Decision** | A **subtle outline** rather than a translucent plate. For a preset literally named "Plain White Text" in the clean-Persian category, a box would change what the preset *is*; an outline serves its stated purpose (legibility) without turning it into a boxed template. Width `2` matches the catalogue's existing "light text over video, no plate" precedent (`progress_bar_underline`, `slide_reveal`) and is the restrained end of the 2–4 range in use. |
| **Change** | `shadow` `none` → `outline`, colour `#000000`, width `2` (both presets) |
| **Note** | That is ~10.7 px of halo at 1080 × 1920 (`strokeWidthSp × canvasHeight / 720 × 2`), i.e. ~8 % of the rendered glyph height — visible separation without a heavy cartoon outline. |

`cinema_plain_white` otherwise keeps its identity: white base text `#FFFFFF`, red active
word `#FF3B3B`, `background: none`, `CaptionBackgroundKind.NONE`, no plate added.

### 4.4 The `1.12` animation cap — deliberately untouched

No template exceeds the cap (maximum is exactly `1.120`), and the cap is still enforced at
exactly one boundary, `JsonTemplateMapper.kt:235`
(`.coerceIn(0.8f, 1.12f)`). The Phase 4 finding stands unchanged: the cap bounds the
**template** value at the JSON border only, and `WordMotionEngine` adds its overshoot on
top, so `bounce_text_pop` actually draws up to **≈1.4035** analytically. This phase changed
no colours or scales on that path, and the QA suite still pins both facts. Raising or
lowering the cap remains an open decision for a later phase (§9.3).

### 4.5 Tests for every intentional change

Seven tests were added and two deliberately updated; the two QA **allow-lists were emptied
rather than deleted**, so the ratchet still exists and any *new* offender fails the suite.

| Test | Pins |
|---|---|
| `bounce_text_pop's active word now reads on its own white plate` | measured ≥ 4.5:1 on the actual plate, the new value, the preserved base colour, BOUNCE_POP motion |
| `cinema_yellow_script now separates its active word from the yellow base` | base still `#FFD400` + Lalezar, chip `#CC000000`, ≥ 4.5:1 on the chip, and that the *old* white-on-yellow pair really was < 1.5:1, plus `HIGHLIGHT_COLOR` motion |
| `both previously plain presets now carry a subtle legibility aid` | outline present, `#000000`, width ≤ 2 on both |
| `cinema_plain_white keeps its plain white identity` | white text, **no** plate, `CaptionBackgroundKind.NONE`, active `#FF3B3B` |
| `the three fixed presets are no longer on either allow list` | guards the ratchet |
| `every template has a legibility aid` (updated) | already-existing test; now expects an empty offender set |
| `the active word is legible on its own backing` (updated) | already-existing test; now expects an empty offender list and an empty allow-list |
| `inverted highlight keeps inactive as base text` (updated) | `JsonTemplateMapperTest`; base stays the yellow `inactiveColor`, active word now yellow-on-chip |

Catalog-wide contracts re-asserted unchanged: **19** templates with an opaque (≥ 85 %)
plate still meet 4.5:1; no template exceeds the 1.12 cap; every word-synchronised template
still differentiates its active word.

> **Threshold note.** The catalogue-wide floor stays at **3:1**, not 4.5:1, because these
> captions render at ~128 px and the active word is bold — WCAG AA for large text is 3:1.
> The three fixed presets are held to the stricter **4.5:1** by their own dedicated tests.
> A real contrast ratio does not by itself prove readability over *moving* video; that is
> why §3.3 step 4 exists.

---

## 5. Task 4 — catalog balance, verified

### 5.1 The counts, checked against the registry rather than restated

`SubtitleTemplateCategoryQaTest` now pins **both** registries separately, because they are
not the same set and the difference matters for anything user-facing.

| Registry | Size | Source |
|---|---|---|
| JSON presets — **what the Subtitle Studio picker actually serves** | 25 | `assets/subtitle_templates.json` → `JsonTemplateMapper.mapCatalogState` |
| Hand-built designs | 26 | `BuiltInSubtitleTemplates.ALL` |
| Shared ids (`fa_pro_*` defined in both, JSON wins) | 6 | dedupe by id |
| **Unduplicated catalog** | **45** | union |

Per-category counts, pinned as exact maps:

| Category | Served JSON (25) | Union (45) |
|---|---|---|
| Viral / bold | 7 | **13** |
| Word-by-word karaoke | 4 | **7** |
| Podcast / interview | 3 | **5** |
| Minimal / cinematic | 4 | **6** |
| Educational / explanatory | 1 | **1** |
| Keyword emphasis | 3 | **5** |
| Clean Persian-first | 2 | **4** |
| Rounded / outlined | 1 | **4** |
| **Total** | **25** | **45** |

The union counts match the figures quoted in the Phase 5 brief (**13 / 7 / 5 / 6 / 1 / 5 /
4 / 4**) exactly — **VERIFIED**. All eight categories are non-empty in both registries.

> **Correction to the Phase 4 report.** It described the 45-template union as "offered by
> the picker". That is inaccurate: `EditorScreen.kt:6014` passes
> `catalogTemplates = uiState.jsonCatalogTemplates`, i.e. the **25** mapped JSON presets.
> The 26 hand-built designs are reachable only through `BuiltInSubtitleTemplates.byId` /
> legacy-id migration for already-saved projects. Both count sets are now pinned by tests
> so the distinction cannot drift again.

### 5.2 Which categories need variety

Judged on the **served** 25 (what a user can actually pick today):

| Category | Served | Assessment |
|---|---|---|
| Educational / explanatory | **1** | Thinnest by a wide margin. A single preset (`cinema_yellow_script`) cannot cover a whole authoring category, and it is a *cinematic script* treatment rather than an instructional one. |
| Rounded / outlined | **1** | Only `cinema_black_pill`. For the "boxed caption" look that most Persian creators default to, one option is not a choice. |
| Clean Persian-first | **2** | `cinema_plain_white` and `fa_pro_glass_minimal`. Both lean decorative-or-minimal; there is no plain, high-legibility, no-plate option with a *plate-free dark* treatment for light footage. |
| Podcast / interview | **3** | All three are boxes in different colours (`cinema_black_box`, `cinema_dark_box`, `cinema_red_box`). No lower-third, no speaker-name style, no two-line treatment. |
| Keyword emphasis | **3** | Reasonable, but all three lean neon/orange. |
| Minimal / cinematic | **4** | Adequate. |
| Karaoke word-by-word | **4** | Adequate, though three of the four are underline/bar variants. |
| Viral / bold | **7** | Saturated. Do not add here. |

### 5.3 Design brief for the next template phase (proposal only — no templates added)

Prioritised by the gap in §5.2. Each brief is deliberately unlike the presets that already
exist, names the axes it exercises, and is scoped so it cannot duplicate a shipped preset.
All of them must keep the project's standing constraints: a bundled offline Persian family,
no system-font fallback, text inside the safe area, an active-word treatment that is
actually differentiated, and a legibility aid.

| # | Priority | Proposed preset | Category | Why it is not a duplicate | Axes it exercises |
|---|---|---|---|---|---|
| 1 | **P1** | *Educational step label* — a numbered "گام ۱" badge pinned to the top-left safe anchor with a separate one-line caption body | Educational | Nothing today places a persistent badge at a corner; every educational/clean preset is a single centred block | multi-element layout, TOP + LEFT anchors, badge plate, `ALL_AT_ONCE` reveal |
| 2 | **P1** | *Podcast lower-third* — name row above a title row, left-anchored, thin rule between them | Podcast | All three podcast presets are centred colour boxes; none is a lower-third with two distinct typographic levels | two-span text, left anchor, `PER_LINE_HIGHLIGHT`, thin-rule decoration |
| 3 | **P1** | *Rounded light caption* — soft light plate with dark Persian text | Rounded / outlined | `cinema_black_pill` is the only rounded preset and it is dark-on-light inverted; a light plate with dark text is the missing half of the pair | SOLID_ROUNDED at high radius, dark base text, contrast pair to pin |
| 4 | **P2** | *Clean dark drop* — plate-free white text with a tight drop shadow (not an outline) | Clean Persian-first | The category's plate-free option would be the only *shadow*-based legibility aid; `cinema_plain_white` uses an outline | dropShadow legibility aid, no plate, bottom anchor |
| 5 | **P2** | *Vocabulary emphasis* — the keyword grows and brightens while the rest dims | Keyword emphasis | Existing keyword presets recolour or pill the word; none dims the *package* around it | `keywordScaleBoost` within the 1.12 cap, per-word opacity, contrast pair to pin |
| 6 | **P3** | *Interview two-speaker* — shift the anchor left/right per speaker segment | Podcast | No preset moves position over time; this is a genuinely new axis | animated anchor, safe-area limits, two style instances |
| 7 | **P3** | *Bilingual stack* — Persian line above a smaller Latin translation line | Clean Persian-first | Mixed-direction handling differs from the existing mid-line `ChatGPT` case | LTR/RTL line isolation, per-line scale, `isRtl` path |

Two standing recommendations for that phase:

1. **Cap the size of `VIRAL_BOLD`.** It already holds 13 of 45. The Phase 5 brief's
   "avoid duplication" is best served by leaving it alone entirely.
2. **Raise the `1.12` clamped value question before adding scale-based presets.** Brief #5
   would exercise exactly the path where the cap silently reduces an authored value
   (`JsonTemplateMapper.kt:235` turns an implicit `1.15f` into `1.12f` with no diagnostic).

---

## 6. Task 5 — validation, with actual results

Every command below was run as its own Gradle invocation. Test and assemble are
deliberately separate statements: a chained failing test task can leave a stale APK that
still looks successful.

### 6.1 Executed — unit tests

| Command | Actual result |
|---|---|
| `:app:testDebugUnitTest --tests …SubtitleTemplateCategoryQaTest --tests …JsonTemplateMapperTest --tests …JsonTemplateParserTest` | `SubtitleTemplateCategoryQaTest` **27** tests / 0 failures · `JsonTemplateMapperTest` **31** / 0 · `JsonTemplateParserTest` **5** / 0. Read from the XML, not the exit code. |
| `:app:testDebugUnitTest --tests …ShowcaseFixtureTest` | **13** tests / 0 failures / 0 errors / 0 skipped |
| `:app:testDebugUnitTest` (full; results directory cleared first so nothing could be stale) | **`BUILD SUCCESSFUL`** — **2925 tests, 0 failures, 0 errors, 0 skipped, across 216 suites** |

Aggregated from `app/build/test-results/testDebugUnitTest/*.xml`. The Phase 4 baseline was
**2905 tests / 1 failure**; that failure (`RealModelInstallTest`) remains fixed, and the
delta of **+20** is +13 showcase and +7 QA tests.

Per-class totals for the classes touched this phase:

| Class | Tests | Failures |
|---|---|---|
| `model.template.SubtitleTemplateCategoryQaTest` | 27 (was 20) | 0 |
| `debugtools.showcase.ShowcaseFixtureTest` (new) | 13 | 0 |
| `model.template.JsonTemplateMapperTest` | 31 | 0 |
| `export.TextExportStyleTest` | 10 | 0 |
| `ai.SrtFixtureFilesTest` | 11 | 0 |
| `ai.RealModelInstallTest` | 1 | 0 |

`ShowcaseFixtureTest` is the reason the fixture's half-spaces are trustworthy: it asserts
the **code point** U+200C is really present at runtime, rather than trusting that the
Persian "looks right".

### 6.2 Executed — builds

| Command | Actual result |
|---|---|
| `:app:compileDebugKotlin` | `BUILD SUCCESSFUL` (debug manifest merged cleanly, showcase included) |
| `:app:assembleDebug` | `BUILD SUCCESSFUL` → `app/build/outputs/apk/debug/app-debug.apk`, **115.43 MB**, `10/16/2026 2:11:43 PM` |
| `:app:processReleaseManifest` | `BUILD SUCCESSFUL`; merged release manifest contains **no** `SubtitleShowcaseActivity` and no `debugtools` package (§3.2) |
| `aapt2 dump xmltree` (build-tools 36.0.0) on the debug APK | showcase `<activity>` present, MainActivity present |
| `:app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL` — **compiled only, not executed** (no device) |

### 6.3 FAILED — lint

`.\gradlew.bat :app:lintDebug` → **`BUILD FAILED in 3m 40s`**.

The failure is inside the lint tool, not in the source:

```
KotlinIllegalArgumentExceptionWithAttachments: LLResolutionFacade.findCompiledFirSymbol(LLResolutionFacade.kt:221)
  … ← UastGradleVisitor.visitBuildScript(UastGradleVisitor.kt:42)
  … ← LintDriver$Companion.handleDetectorError(LintDriver.kt:3880)
Message: `findFirCompiledSymbol` only works on compiled declarations, but the given declaration is not compiled.
```

Two things are worth stating plainly:

1. It is an AGP 8.13.2 / Kotlin 2.0.21 incompatibility in lint's **build-script** visitor.
   No Gradle build script was modified in this phase, and no source file from this phase
   appears anywhere in the stack. It is not a finding about the code.
2. `app/build.gradle.kts:207-212` disables lint repo-wide (`abortOnError = false`,
   `checkReleaseBuilds = false`, `disable += "all"`), so even a successful run carries no
   signal here. No lint report was produced (`app/build/reports/lint-results-debug.html`
   does not exist); only the intermediate partial-result XMLs were written.

**Recorded as FAILED (tooling), not as a code defect, and not worked around** — changing
the lint configuration to make a tooling crash go away would be an unrelated change with
no verification value.

### 6.4 Executed vs. merely compiled

| Check | State |
|---|---|
| Unit tests (2925) | **Executed** |
| `compileDebugKotlin`, `assembleDebug` | **Executed** (produces an APK) |
| Release manifest merge | **Executed** |
| `compileDebugAndroidTestKotlin` | **Compiled only — not executed** |
| `lintDebug` | **Executed, failed in the tool** |
| Any device/emulator run | **Not executed** — no device |

---

## 7. Changed files

### Added (7)

| File | Purpose |
|---|---|
| `app/src/debug/AndroidManifest.xml` | Declares the debug-only showcase activity; no launcher filter |
| `app/src/debug/java/com/aicreatorstudio/app/debugtools/SubtitleShowcaseActivity.kt` | `@AndroidEntryPoint` host, locale-wrapped like `MainActivity` |
| `app/src/debug/java/com/aicreatorstudio/app/debugtools/showcase/ShowcaseFixture.kt` | Three deterministic Persian fixtures (pure Kotlin) + registry union + overlay builder |
| `app/src/debug/java/com/aicreatorstudio/app/debugtools/showcase/SubtitleShowcaseViewModel.kt` | `@HiltViewModel`; loads the registry through `JsonTemplateCatalog` |
| `app/src/debug/java/com/aicreatorstudio/app/debugtools/showcase/SubtitleShowcaseScreen.kt` | The screen: preview, scrubber, backdrop/guide toggles, category browse |
| `app/src/testDebug/java/com/aicreatorstudio/app/debugtools/showcase/ShowcaseFixtureTest.kt` | 13 tests: fixture integrity, timing realism, registry dedupe, debug-only guard |
| `docs/subtitles/phase-5-report.md` | This document |

### Modified (3)

| File | Change |
|---|---|
| `app/src/main/assets/subtitle_templates.json` | `bounce_text_pop` active colour → `#0B7A4B` (line 47); `cinema_yellow_script` outline + `box` highlight on a dark chip (lines 174-175); `cinema_plain_white` outline (line 222). Each carries a Persian `note` recording the reason. |
| `app/src/test/java/.../model/template/SubtitleTemplateCategoryQaTest.kt` | Both allow-lists emptied with rationale; 2 tests re-worded + tightened; **7 new tests** (5 for the fixes in §3b, 2 pinning both registries separately) |
| `app/src/test/java/.../model/template/JsonTemplateMapperTest.kt` | `inverted highlight keeps inactive as base text` updated to the new chip and to assert the unchanged `HIGHLIGHT_COLOR` motion |

**Not changed, on purpose:** no product data beyond the three presets; no template added or
removed (45 entries before and after); every other pinned count untouched; the `1.12` cap
untouched; `EditorViewModel`, the renderer, the preview and the burn-in untouched; no new
dependency, no new Gradle build script, no network/paid service, no login.

---

## 8. Remaining unverified behaviour

| # | Item | Status | How to close it |
|---|---|---|---|
| 8.1 | The showcase has **never been launched**. No screenshot, no device, no layout verification — only compilation and unit tests. | **BLOCKED** | §3.3 steps 1-2 on a device or emulator. |
| 8.2 | **Real Persian glyph shaping, joining and line-breaking** cannot be verified on the JVM (no Robolectric; `android.graphics` is stubbed by `unitTests.isReturnDefaultValues`). The fixture's half-spaces are proven present as code points, not proven *shaped* on screen. | **BLOCKED** | §3.3 step 1-2 on a device with a real font stack. |
| 8.3 | **Readability over real moving footage** is unverified. The showcase backdrops are synthetic; the contrast ratios in §4 are static calculations. | **BLOCKED** | §3.3 step 4 with a real clip. |
| 8.4 | Judges the fixed presets' *visual* quality — whether `#0B7A4B` on the white plate still reads as a "pop", and whether the `cinema_yellow_script` chip looks intentional rather than bolted on. | **UNVERIFIED** | Device inspection; if it reads badly, the change is one JSON line per preset to revisit. |
| 8.5 | MP4 export parity for the fixed presets (preview vs burned-in frame). The renderer path is shared, but the three changed presets have not been exported. | **BLOCKED** | §3.3 export procedure. |
| 8.6 | `bounce_text_pop`'s real drawn scale: analytically ≈**1.4035** (engine overshoot on top of the template's 1.120). Whether that is visually acceptable is a design call nobody has made on a screen. | **UNVERIFIED** | §3.3 step 6; decision in §9.3. |
| 8.7 | `androidTest` sources compile but were never executed. | **BLOCKED** | Needs a device (`connectedDebugAndroidTest`). |
| 8.8 | `lintDebug` produces no usable output (§6.3). | **FAILED (tool)** | Toolchain fix, or enable real lint checks in `app/build.gradle.kts`. |
| 8.9 | Decorative-path default-shadow parity noted in Phase 4 — `FreePositionTextOverlay` draws a shadow that `decorativeExportStyle()` does not. Untouched by this phase. | **UNVERIFIED** | Same as Phase 4; a preview/export diff on a device. |
| 8.10 | `docs/MANUAL_TEST_CHECKLIST.md` is stale: it still says "**17** templates" (there are 45) and its Persian tab label is corrupted to `??????`. A validator following it will look for the wrong thing. | **DOCUMENTED** | Left alone deliberately (pre-existing, broader scope, and rewriting an unrelated user document was out of this phase's mandate). Flagged here and in §9.5 so the next device session does not trust it. |
| 8.11 | ASR accuracy from the fixture — no such claim is made anywhere. The fixture is authored text with authored timings; `ShowcaseFixtureTest` asserts the timings are *uneven* precisely so nobody mistakes them for a synthetic equal division. | **N/A** | — |

## 8b. What *is* verified, stated precisely

- 2 925 unit tests pass, read from XML, with a cleared results directory.
- The debug APK builds at 115.43 MB and declares the showcase activity; the release merged
  manifest does not.
- The showcase screen, ViewModel and activity compile against the real Hilt graph on the
  `debug` variant; the fixture's three captions are 1 / 2 / 3 lines with 4 / 14 / 8 words,
  uneven non-overlapping timings, real U+200C code points, Persian-indic digits, a Latin
  run, Persian-only letter forms, and punctuation.
- The registry union is derived and collapses the six shared `fa_pro_*` ids to 45 unique
  ids with JSON winning, matching the product's resolution order.
- Both registry count sets are pinned as exact maps, and the union matches the reported
  **13 / 7 / 5 / 6 / 1 / 5 / 4 / 4**.
- The three readability fixes are measured and re-asserted by tests: **1.65:1 → 5.39:1**,
  **1.432:1 → 14.67:1**, and a legibility aid now present on both previously bare presets.
- The QA allow-lists are empty and the ratchet is intact; the opaque-plate count is still
  exactly 19; no template exceeds the 1.12 cap.

---

## 9. Prioritized proposal for the next phase

### P0 — close the device gap before designing anything else

Nothing visual has been confirmed. The showcase and the three fixes are the cheapest things
to validate now, and their outcome could invalidate downstream design work.

1. Run §3.3 on a real device: launch the showcase, walk the six inspection steps.
2. Judge §8.4 specifically — do the three fixed presets still look like themselves?
3. Export one MP4 with each fixed preset and compare the frame to the preview (§8.5).
4. Record the result as a dated addendum to this report. **Do not mark it passed until a
   human has actually looked at a screen.**

### P1 — the three highest-value templates (§5.3 briefs 1-3)

Educational badge, podcast lower-third, rounded light caption. These fill the two
single-template categories and give podcast a treatment that is not a centred colour box.
Each needs its contrast pair pinned in `SubtitleTemplateCategoryQaTest`, and the pinned
counts and allow-lists updated deliberately.

### P2 — catalog hygiene

- Fix the stale `docs/MANUAL_TEST_CHECKLIST.md` (17 → 45 templates; repair the corrupted
  Persian tab label) **before** the next device session, so a validator is not misled
  (§8.10).
- Decide the `1.12` boundary question: document it as an authoring cap in the
  `SubtitleTemplate` KDoc, or enforce `0.8..1.12` at the last write boundary
  (`EditorViewModel` / `EditorStateJson`). Either way, add a diagnostic for the silent
  `1.15f → 1.12f` reduction (`JsonTemplateMapper.kt:235`).
- Consider enabling a narrow, non-tool-crashing lint subset so a lint check can actually
  serve as a gate again (§6.3, §8.8).

### P3 — deferred, lower value

- Reconcile `decorativeExportStyle()` with `FreePositionTextOverlay`'s default shadow (§8.9).
- Sweep the stale `ggml-medium` references in `androidTest` (carried over from Phase 4).
- The `EditorViewModel.kt` mojibake (107 U+FFFD across 101 comment lines) — pre-existing,
  comments only, not user-facing.

---

## Appendix A — exact commands run

```powershell
# readability fixes + registry counts (filtered, fast iteration)
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.aicreatorstudio.app.model.template.SubtitleTemplateCategoryQaTest" `
  --tests "com.aicreatorstudio.app.model.template.JsonTemplateMapperTest" `
  --tests "com.aicreatorstudio.app.model.template.JsonTemplateParserTest"

# showcase data layer
.\gradlew.bat :app:testDebugUnitTest --console=plain `
  --tests "com.aicreatorstudio.app.debugtools.showcase.ShowcaseFixtureTest"

# debug variant compiles with the showcase
.\gradlew.bat :app:compileDebugKotlin --console=plain

# FULL suite (results dir cleared first so nothing could be stale)
Remove-Item -Recurse -Force app\build\test-results\testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --console=plain

# APK (separate invocation, on purpose)
.\gradlew.bat :app:assembleDebug --console=plain

# proof the showcase is not in release
.\gradlew.bat :app:processReleaseManifest --console=plain

# androidTest: compilation only
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain

# lint: FAILED in the tool (§6.3)
.\gradlew.bat :app:lintDebug --console=plain
```

## Appendix B — how the counts in this report were read

- Unit-test totals and per-class results:
  `app/build/test-results/testDebugUnitTest/*.xml` (`tests`, `failures`, `errors`,
  `skipped` attributes), summed across all 216 suites — **not** the process exit code.
- Release/build-variant exclusion:
  `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
  versus the debug equivalent, plus `aapt2 dump xmltree` on `app-debug.apk`.
- Persian and half-space presence: byte-level UTF-8 reads of the fixture and catalog files
  (BOM, U+FFFD, U+200C, U+06F0–U+06F9, U+06CC/U+06A9 versus U+064A/U+0643), because the
  Windows console renders Persian as `????`.
- Contrast ratios: WCAG 2.x relative luminance with alpha composited over black, the same
  function the QA suite asserts with.
