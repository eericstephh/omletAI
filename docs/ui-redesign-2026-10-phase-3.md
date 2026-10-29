# Omelet UI Redesign — Phase 3

**Date:** 2026-10-17
**Scope:** AI subtitle / subtitle-template experience, cross-screen consistency, dead-code verification, device verification, quality gates.
**Predecessors:** `docs/ui-redesign-2026-10-dark-studio.md`, `docs/ui-redesign-2026-10-phase-2-editor.md`
**Out of scope (per brief):** transcription, word timing, `CaptionRenderer`, persistence, preview/export behaviour. No tests were modified.

---

## 1. Method and honest limits

| Item | What was actually done |
| --- | --- |
| Source reading | Full read of `SubtitleStudioPanel.kt` (2 531 lines), `SettingsScreen.kt`, `UpscaleProgressDialog.kt`, `LocalizedStrings.kt`, `SubtitleTemplateCategory.kt`, `UpscalerExportState.kt`, `AppLanguage.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `NavGraph.kt`. |
| Search | `grep` for every symbol a claim depends on (dead-code call sites, token definitions, call sites of the strings I changed). |
| Static gates | The four Gradle gates, plus an attempt at lint. Results in §6. |
| Device | Real run on `emulator-5554` (API 37, `sdk_gphone16k_x86_64`, 1080×2400, density 420). Fresh debug APK installed (`adb install -r`), screenshots captured and inspected, `uiautomator` dumps parsed, `font_scale` raised to 1.3 and restored to 1.0. |
| **Not done** | No TalkBack/native-screen-reader session, no Persian native-speaker read of bidi edge cases, no screenshot-diff harness, no instrumented (androidTest) execution — the androidTest module compiles but was not run against the emulator. |

Two verification limits that materially constrain claims in this document:

1. **`uiautomator` dumps from this emulator do not surface Compose `selected` / `checkable` / `checked` state.** Proven, not assumed: a Material3 `FilterChip` rendering visually selected and a Material3 `Switch` rendering visually ON both report `selected="false"`, `checkable="false"`, `checked="false"` in the same dump (`state/…/20-chips.xml`, nodes `[38]–[52]`). Anything I added in this class is therefore verified by compilation + code review only, **not** on device.
2. **Contrast figures below are calculated from token hex values**, not measured from rendered pixels. They are supporting evidence, not a substitute for a visual check.

---

## 2. AI subtitle / template experience — audit result

### 2.1 What is correct (verified, keep as-is)

* **Picker counts cannot drift from the grid.** `SubtitleTemplateTaxonomy.group()` (`SubtitleTemplateCategory.kt:190`) is a `LinkedHashMap` keyed by `classify(t)`, then emitted in enum declaration order via `mapNotNull`. `classify` is total (`EXPLICIT[id]` first, else `classifyByProperties` which ends in an `else` branch), so every template lands in exactly one of the eight buckets — no bucket can silently drop an item, and nothing is double-counted. On device the header read **"(25)"**, matching the JSON catalog size, with the grid rendered beneath it.
* **Filtering is a view filter, not a mutation.** Selecting a category narrows what is rendered while `selectedPreset` (identity) survives; the grid card compares `t.id == selectedPreset.id`, so the applied template, its order, and the selection are unchanged by browsing. No persistence is touched by the picker.
* **LOADING and EMPTY are genuinely distinct states.** `JsonCatalogStatus` has a third value (`EMPTY`) that is *not* transient — the pre-Phase-3 code showed the same pulsing skeleton forever, telling the user "still loading" when the catalogue was unavailable. The three states are now separate branches.
* **Preview fidelity.** `TemplateGridCard` renders through `rememberTemplateImageBitmap(...)` at a frozen frame (4.5 × `wordDurationMs`), i.e. it is the *same* renderer path as the real caption — the card is a true preview, not a decorative swatch. `wordRevealMode`-specific templates (karaoke, typewriter) show their identity statically instead of looking alike.
* **Labels.** Category labels are Persian-first with the English mirror as the secondary line (`displayNameFa` / `displayNameEn`), and template cards show the Persian title plus the English product name (`«هرموزی وایرال»` / `Hormozi Viral`) — a deliberate bilingual pairing consistent across all 25 cards, not a missed translation.

### 2.2 Changes made in `SubtitleStudioPanel.kt`

Only one class of change: **selection was signalled by colour alone** (accent fill + accent border), which a screen reader cannot perceive. Three nodes now publish `selected` semantics:

| Location | Node | Change |
| --- | --- | --- |
| line 280 | The eight style-axis tabs (`قالب‌ها / فونت / رنگ / سایه / هایلایت / پس‌زمینه / موشن / موقعیت`) | `.semantics { selected = activeTab == i }` |
| line 2222 | `TemplateGridCard` (the template tile itself) | `.semantics { this.selected = isSelected }` |
| line 2309 | `ModeChip` (picker category / mode filter) | `.semantics { this.selected = isSelected }` |

The `this.` receiver qualifier is **required**, not stylistic: the composable parameter is named `selected`, and a local `val` shadows the imported `SemanticsPropertyReceiver.selected` extension property, which fails to compile with `'val' cannot be reassigned`. This was hit during the build and is the reason the qualifier is there — removing it will break the build.

Not changed, deliberately: the editor's ~200 in-panel English strings remain English (Phase 2 scope decision, see §3.2).

### 2.3 Defects found in the subtitle experience, not fixed

| # | Defect | Evidence | Why not fixed |
| --- | --- | --- | --- |
| S1 | **SRT chrome is English** inside a Persian-authored panel: `Export SRT`, `Import SRT`, `Custom Font`. | Screenshot `state/…/13-subtitle-grid.png`. | Falls under the Phase 2 decision not to mass-translate editor in-panel strings. Consistency requires a single pass, not three strings. |
| S2 | **Timeline chrome is English**: `Timeline`, `Zoom`, `Text / Subtitles / Music / SFX / Overlay` tabs. | Screenshots `09`, `25`. | Same as S1 — editor chrome language is a Phase 2 decision. |
| S3 | **Bottom dock overflows at large font**: at `font_scale 1.3` the sixth dock item (`پس‌زمینه`) is clipped at the right edge and wraps awkwardly. | Screenshot `state/…/25-editor-130.png`. | The dock is the editor's primary navigation; reflowing it is a design decision, not a one-line fix. Needs a scroll/`FlowRow`/fewer-items decision. |
| S4 | **Export/Import SRT buttons are visually disabled** (`Export SRT` greyed) while `Import SRT` sits above a hint that a clip must be selected first — the disabled reason is only implied by the hint below, not attached to the control. | Screenshot `state/…/13-subtitle-grid.png`. | Requires copy/content decision + `contentDescription` on the disabled control. |
| S5 | **Subtitle sheet surface is very light** (near-white card) against the dark editor, while every other editor surface is dark glass. Reads as a different product. | Screenshots `10`–`13`, `26`. | Not a bug I could attribute: it may be an intentional "paper" surface. Needs a product decision before touching; changing it would restyle the whole panel. |

---

## 3. Cross-screen consistency audit

### 3.1 The app renders **LTR chrome with Persian content** — by design, but documented backwards

`AndroidManifest.xml:28` sets `android:supportsRtl="false"`, and the comment immediately above it (line 19) says *«supportsRtl="true" restored per request»*. The two contradict each other: the comment describes a change that is not in the file.

Consequences verified on device (not inferred):

* Settings: back arrow on the **left**, section titles flush left, trailing controls on the **right** (`state/…/02-settings.png`).
* Home: header title on the left, bell/gear on the right; feature grid's first card at top-**left** (`01-home.png`).
* Text direction: paragraphs resolve **LTR**, so run groups appear in logical left-to-right order. Proof: the language chip logically `"سیستم (System)"` renders as `سیستم (System` — i.e. Persian run leftmost, parenthesis mid, Latin run rightmost (`02-chip-crop2.png`).

This matches the 2026-09-09 decision ("the UI stayed LTR and only strings localized"), and it explains why `AppUpdateSheet` and `EditorScreen` wrap themselves in explicit `LocalLayoutDirection` overrides. **Conclusion: the LTR rendering is intentional; the lying comment is the defect.** Flipping `supportsRtl` to `true` would mirror Rows/Arrangements on every screen at once — the flag's own comment warns it can break the editor — so it is *not* a low-risk change and was not made. It is the #1 item in §7.

### 3.2 Settings screen was still mixed-language (Phase 1 miss) — **fixed**

The Settings screen used English *keys* in `LocalizedStrings`. Since the table's fallback is "return the key", and there is no `fa` table, the keys themselves were what Persian users saw: section headers literally rendered **"Appearance", "Export", "General", "Hardware Acceleration", "Auto Save", "Haptic Feedback", "Default Export Quality", "Default Text Font"** in the default Persian UI. Arabic mode was worse: the `ar` table had no entries for those keys either, so Arabic users also got raw English. The screen also still used stock Material chrome with an English title `"Settings"` / `"Back"` — the exact defect Phase 1 fixed on the Import screen.

Fixed: all of the above now resolve through `t()` with the Persian source string as the key (the convention every other screen follows), plus new `en` **and** `ar` entries. See §5.

Other Settings observations:

| # | Finding | Evidence |
| --- | --- | --- |
| C1 | **Arabic mode has no Settings appearance/general keys at all** — fixed as part of the above. The `ar` table still stops at the download-model strings. | `LocalizedStrings.kt` before edit |
| C2 | **Model metadata is English data**, not chrome: `"AI model"`, `"Real neural 2x/4x video enhancement"`, `"Whisper Small"`. Localising means localising the model spec source, not `t()` keys. | `state/…/02-settings.png` |
| C3 | **`DiagnosticsSection` and `GroqConnectionSection` are Persian-only** — they call `t()` with Persian keys, but the keys are absent from *both* tables, so English/Arabic users see Persian. Pre-existing. | `DiagnosticsSection.kt:96–301`, `GroqConnectionSection.kt:103–237` (the latter does not even call `t()`) |
| C4 | **`AppUpdateSheet`'s private purple palette justification is now stale** (`SheetSurface = 0xFF14101F`, comment explains it was chosen to sit on Home's surface — Home has since been re-themed). | `AppUpdateSheet.kt` |
| C5 | Raw colour literals remain: **102** raw hex literals outside `ui/theme/`, **90** inside (the theme files themselves), and **158** `Color.White.copy(...)` calls in `ui/screens/editor`. | `grep` counts |
| C6 | `docs/MANUAL_TEST_CHECKLIST.md` is stale (predates the Dark Studio work). | prior reports |

### 3.3 One genuine, measured layout defect (fixed)

The Settings **font chip row had no scroll container**, so the fifth chip was measured against the leftover width: `"Lalezar"` collapsed and wrapped to one glyph per line. Measured on device, not eyeballed:

```
before:  chip 'Lalezar'  [930,1976][954,1976+234]   → 24 px wide × 234 px tall (7 stacked lines)
after:   chip 'Lalezar'  [930,2130][996,2164]       → 66 px wide × 34 px tall (one line)
```

Every chip row in the subtitle panel (12 of them) and the language picker directly above already use `horizontalScroll(rememberScrollState())`; this row was the outlier. Fixed to match. The sibling quality row (5 chips) happens to fit at `font_scale 1.0`, but had the identical latent fragility, so it received the same one-line treatment. Visual confirmation: `state/…/22-fontrow-fixed.png`.

### 3.4 `UpscaleProgressDialog` (Phase 2 leftover) — **fixed**

The AI-upscale progress modal was entirely English chrome in a Persian app (stage labels, cancel/done/close buttons, success/failure/cancel headings). Three details drove the approach:

* The stage labels live in the **export model** (`UpscalerExportState.stageLabels`) and are also consumed by the *unreferenced* `UpscaleBottomSheet`. Mutating the model to fix a dialog is the wrong layer, and the export engine has no business carrying UI copy — so the labels are mapped at the presentation layer via a local `UpscaleStageLabels` list, order-matched to the model.
* `state.message` on `Failed` is authored by `UpscalerExportEngine` and shared with the main export dialog. Translating it means changing the engine; **not done** (§7).
* 8 strings Persianised; the model's `stageLabels` field is now read only by dead code, so it can be deleted together with `UpscaleBottomSheet` (§4).

---

## 4. Dead code — evidence only, nothing deleted

Searched for every call site app-wide (`app/src`, all `.kt`):

| File | Lines | Call sites found | Verdict |
| --- | --- | --- | --- |
| `ui/screens/editor/EditorTopBar.kt` | 212 | `EditorTopBar(` appears **once**, at its own definition. The live top bar is `EditorTopBarSection` in `EditorScreen.kt:2359`, called at `EditorScreen.kt:1400`. | Unreferenced |
| `ui/screens/home/MembershipGlassCard.kt` | 259 | `MembershipGlassCard` appears **once**, at its own definition. | Unreferenced |
| `ui/screens/editor/FloatingPanel.kt` | 288 | `FloatingPanel(` twice: definition (line 57) and an internal call from `FloatingPanelContainer` (line 209). `FloatingPanelContainer` and `FloatingPanelState` appear **only inside this file**. | Self-contained, unreferenced |
| `ui/screens/editor/UpscaleBottomSheet.kt` | 605 | `UpscaleBottomSheet(` appears **once**, at its definition. Other hits are comments: `EditorViewModel.kt:5550`, `UpscaleProgressTest.kt:66`. | Unreferenced |
| **Total** | **1 364** | | |

Nothing was deleted (per brief). Two follow-ups this unlocks: (a) `UpscalerExportState.stageLabels` loses its last live consumer once the sheet goes; (b) the comment references above should be updated when it does.

---

## 5. Files changed

No git repository exists for this project — all changes are working-tree files on disk.

### `app/src/main/java/com/aicreatorstudio/app/ui/screens/settings/SettingsScreen.kt`
* Top bar: Persian title `t("تنظیمات")`, Persian back label `t("بازگشت")`, and token colours (`CinematicBlack` container, `LabelPrimary` content) so the bar matches every other screen's black header band instead of the default elevated surface.
* Section titles and rows localised through Persian keys: `اتصال هوش مصنوعی`, `ظاهر`, `خروجی` (+ `شتاب سخت‌افزاری` and its subtitle), `عمومی` (+ `ذخیره خودکار` and its subtitle, `بازخورد لمسی` and its subtitle), `کیفیت پیش‌فرض خروجی`, `فونت پیش‌فرض متن`.
* `ThemeModeSelector` labels `"Dark"/"Light"/"System"` → `حالت تیره / حالت روشن / سیستم`. These labels are *also* the icon `contentDescription`, so the accessible name is fixed at the same time.
* Download progress line was a bare Persian literal; now `t("در حال دانلود…") + " N%"` (`t()` has no interpolation).
* Font chip row and quality chip row wrapped in `horizontalScroll(rememberScrollState())` (§3.3).

### `app/src/main/java/com/aicreatorstudio/app/settings/LocalizedStrings.kt`
* Replaced the **11 English-keyed** Settings entries with Persian-keyed ones, following the table's stated convention ("keys ARE the default Persian source strings").
* Added 15 new keys to **both** `en` and `ar`: `اتصال هوش مصنوعی`, `ظاهر`, `خروجی`, `شتاب سخت‌افزاری`, the hardware-acceleration subtitle, `عمومی`, `ذخیره خودکار`, the auto-save subtitle, `بازخورد لمسی`, the haptics subtitle, `کیفیت پیش‌فرض خروجی`, `فونت پیش‌فرض متن`, `حالت تیره`, `حالت روشن`, `سیستم`.
* Note: a key `"روشن"` already maps to `"On"` in the text panel, hence the theme label is the full `"حالت روشن"` rather than `"روشن"` — a collision that would otherwise silently mistranslate a toggle.

### `app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/subtitle/SubtitleStudioPanel.kt`
* Added `import androidx.compose.ui.semantics.selected` and `...semantics.semantics`; added three `selected` semantics blocks (§2.2). No rendering, filtering, timing, or persistence logic touched.

### `app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/UpscaleProgressDialog.kt`
* Fully Persianised at the presentation layer via a local `UpscaleStageLabels`; export model untouched (§3.4).

### Already present in the working tree at the start of this pass (reviewed, not authored here)
`SubtitleStudioPanel.kt` contained pre-existing `2026-10-17 PHASE 3` edits: 40 dp tab chips, the `LOADING`/`EMPTY` split (line ~313), the `shownCount` header, and `ctaBrush()` for the save-preset button. They were read and verified behaviourally on device (the count header and the loading→grid transition were both observed), but they are **not** claimed as work from this pass.

---

## 6. Verification

### 6.1 Quality gates — all four pass

| Gate | Command | Result |
| --- | --- | --- |
| Kotlin compile | `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** (1 failure during development, fixed: §2.2 `this.selected`) |
| Unit tests | `:app:testDebugUnitTest` | **2925 tests / 216 suites / 0 failures / 0 errors / 0 skipped** (parsed from `app/build/test-results/testDebugUnitTest/*.xml`) |
| APK | `:app:assembleDebug` | **BUILD SUCCESSFUL** — `app-debug.apk`, **115.43 MiB** (identical to Phase 2) |
| Instrumented compile | `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** (compiled; not executed) |

No test was modified to make anything pass.

### 6.2 Static analysis — **tool failure, not a source defect**

* `:app:lintDebug` **fails** with an internal crash:
  `Unexpected failure during lint analysis (this is a bug in lint or one of the libraries it depends on)` → `KotlinIllegalArgumentExceptionWithAttachments: LLResolutionFacade.findCompiledFirSymbol`, reached from `LintDriver.checkBuildScripts` → `UastGradleVisitor.visitBuildScript`.
  The crash is in lint's analysis of **Gradle build scripts** (Kotlin 2.x FIR API vs. the lint version), i.e. it occurs before/independently of the app sources I edited. It is not attributable to any `.kt` file changed here.
* Independently, lint is configured to be useless in this project: `app/build.gradle.kts:207–212` sets `abortOnError = false`, `checkReleaseBuilds = false`, `checkDependencies = false`, `disable += "all"`. Even a successful run would report nothing.
* No alternative analyser is configured — no detekt, ktlint, spotless, or checkstyle plugin/`*.kts` reference exists in the project.

### 6.3 Device verification — emulator, screenshots inspected

Device: `emulator-5554`, API 37, x86_64 (`sdk_gphone16k_x86_64`), 1080×2400 @ 420 dpi, `font_scale` 1.0 (raised to 1.3 for one pass, then restored), package `com.aicreatorstudio.app.debug`. The freshly built APK was installed with `adb install -r -t` (Success) and launched via `MainActivity`.

Screenshots captured this session (kept outside the repo, under the session temp dir):

| File | What it shows |
| --- | --- |
| `01-home.png` | Home, Persian content, LTR chrome |
| `02-settings.png` | Settings, black top bar + Persian title/back (the fix) |
| `03/05/06-settings-*.png` | Export / General / Diagnostics / theme selector in Persian |
| `22-fontrow-fixed.png` | Font chip row after the scroll fix |
| `23-font130-settings.png` | Settings at `font_scale 1.3` — no clipping |
| `09-editor.png`, `25-editor-130.png` | Editor at 1.0 / 1.3 — dock overflow at 1.3 (S3) |
| `10–13-subtitle-*.png` | AI subtitle sheet: mode chips, speech language, accuracy, words/caption, custom font, SRT |
| `16-templates-fresh.png`, `17-templates-cats.png` | Template picker: 8 axis tabs with visible selected state, live preview card, template grid |
| `02-chip-crop2.png`, `07-hwaccel-crop.png` | Zoomed text for bidi inspection |

Confirmed on device: Persian RTL *text* shaping is correct everywhere including mixed-content chips and inline Latin (`GPU`, `Whisper`, `KeyStore`); the Settings top bar is black with a Persian title and a working back affordance; the subtitle sheet opens, its eight axis tabs scroll, and the template grid renders previews with Persian + English labels; the template header count renders; at `font_scale 1.3` Settings reflows without clipping while the editor dock clips (S3).

**Contrast (calculated from tokens, not measured):** `LabelPrimary #F5F7FB` on `CinematicBlack #0B0D12` ≈ **18.1 : 1**; `LabelSecondary #B0B8C8` on the same ≈ **9.8 : 1** — both above WCAG AAA (7 : 1) for normal text. This is the pair I introduced on the Settings top bar.

---

## 7. Remaining work, prioritised

1. **Decide the layout-direction question and fix the manifest comment.** `supportsRtl="false"` contradicts its own comment claiming `true`. Either the intent was to restore RTL (then this is an unfixed bug shipped since 2026-09-16), or LTR is intentional (then delete the misleading comment and the now-redundant `LocalLayoutDirection` overrides). Risk of the change itself is high — mirroring all Rows at once — so it needs a product decision, not a patch. *(§3.1)*
2. **Persian reading order in mixed sentences.** Because paragraphs resolve LTR, a sentence that interleaves Persian and a Latin token renders its run groups in logical LTR order, so a Persian reader encounters the clauses right-to-left in a different sequence than authored — e.g. the hardware-acceleration subtitle `برای خروجی سریعتر از GPU استفاده کن (ممکن است باتری بیشتری مصرف کند).` This affects every such string app-wide (Home cards too), so it needs a *systemic* decision: `TextDirection.Content` on Persian-authored body copy, or a rule about Latin tokens in sentences. Flagged for native-speaker confirmation; not changed here.
3. **Accessibility verification of the new `selected` semantics.** Could not be verified on this emulator (tool limitation, §1). Needs TalkBack or an instrumented Compose UI test asserting `SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)`.
4. **`DiagnosticsSection` + `GroqConnectionSection` are English/Arabic-blind.** Their Persian keys are missing from both tables (and `GroqConnectionSection` never calls `t()`), so non-Persian users get Persian. *(C3)*
5. **Delete or adopt the 1 364 lines of dead code.** Deleting `UpscaleBottomSheet` then frees `UpscalerExportState.stageLabels`. *(§4)*
6. **Translate the upscale/export failure `message` source** — authored in `UpscalerExportEngine` / `VideoExportEngine`, shared by two dialogs; needs an error-code → copy mapping rather than string edits in the engine. *(§3.4)*
7. **English/Arabic model metadata** (`"AI model"`, `"Real neural 2x/4x video enhancement"`) — localise the model spec source, not the render site. *(C2)*
8. **Editor in-panel and timeline English** (`Export SRT`, `Import SRT`, `Custom Font`, `Timeline`, `Zoom`, `Text/Subtitles/Music/SFX/Overlay`) — one deliberate pass, not three strings. *(S1, S2)*
9. **Reflow the bottom dock for large fonts** — at `font_scale 1.3` the sixth item clips. *(S3)*
10. **Attach disabled reasons to the SRT buttons** and give the disabled `Export SRT` control an accessible description. *(S4)*
11. **Decide the subtitle sheet's light surface** against the dark editor. *(S5)*
12. **Hygiene:** 102 raw hex literals outside `ui/theme/`; 158 `Color.White.copy(...)` in `ui/screens/editor`; stale `AppUpdateSheet` palette comment; stale `docs/MANUAL_TEST_CHECKLIST.md`; `EditorViewModel.kt` mojibake comments; `1.12f activeWordScale` cap. *(C4, C5, C6; prior reports)*
13. **Restore lint to a usable state** (`disable += "all"` removed, `abortOnError = true` when the tree is clean) and pin a lint version compatible with the Kotlin plugin — currently the task crashes before it can tell anyone anything. *(§6.2)*
