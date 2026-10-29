# Omelet UI Redesign — Phase 2: Professional Minimal Editor

Focused continuation of the Dark Studio redesign. Phase 1 rebuilt the token layer,
the shared components, Home, Splash and Import and is documented in
[`ui-redesign-2026-10-dark-studio.md`](ui-redesign-2026-10-dark-studio.md).

**Phase 2 target:** bring the actual video-editing workspace — `EditorScreen` and the
subtitle studio panel — to the same level of polish, without touching transcription,
subtitle timing, persistence, navigation or the export pipeline.

- Date: 2026-10-16
- Scope: editor presentation + accessibility labels only
- Not a git repository: every change is an uncommitted file on disk. There is no diff
  to revert to, so this report is the record.

---

## 1. Exact files changed

Twelve files. Eleven under `app/src/main/java/com/aicreatorstudio/app/`, one doc.

| # | File | Nature of change |
|---|------|------------------|
| 1 | `ui/theme/Color.kt` | +2 tokens (`OnVideoScrim`, `OnVideoScrimStrong`) with measured-contrast rationale |
| 2 | `ui/screens/editor/EditorScreen.kt` | Top bar layout defect, touch targets, tool-sheet surface, 3 banners, `ToolPanel.title()`, on-video scrims, 3 remaining English labels |
| 3 | `ui/screens/editor/ExportDialog.kt` | WCAG AA contrast fix on the primary button, off-palette literals → tokens, full Persian copy |
| 4 | `ui/screens/editor/subtitle/SubtitleStudioPanel.kt` | Category filter navigation (new), `ModeChip` / `TemplateGridCard` / skeleton tokens + targets |
| 5 | `ui/screens/editor/BeforeAfterSplitSlider.kt` | On-video scrim → token (×2) |
| 6 | `ui/screens/editor/AiMattingPanel.kt` | `contentDescription` "Reset" → «بازنشانی» |
| 7 | `ui/screens/editor/MotionTrackPanel.kt` | same |
| 8 | `ui/screens/editor/OcrPanel.kt` | same |
| 9 | `ui/screens/editor/AutoFramePanel.kt` | same |
| 10 | `ui/screens/editor/VoiceCleanPanel.kt` | same |
| 11 | `ui/screens/editor/UpscalerPanel.kt` | same, plus the result banner's "Done" / "Failed" / "Dismiss" → Persian and a larger dismiss target |
| 12 | `docs/ui-redesign-2026-10-phase-2-editor.md` | this report |

No file was deleted. No dependency, Gradle, manifest, ViewModel, repository or model
change. `Color.kt` gained two constants; no existing token was redefined, so every
Phase 1 screen keeps its exact appearance.

---

## 2. Audit findings that drove the work

Read before editing: `EditorScreen.kt` (6,254 lines) structure, the whole
`ContextualToolbar.kt` + `ContextualToolbarSpec.kt` pair, `EditorPlaybackStripSection`,
`EditorTimelineSection`, `EditorBottomDockSection`, `SubPanelRow` / `SubPanelContent`,
the three banners, `ExportDialog.kt` end to end, and the picker section of
`SubtitleStudioPanel.kt`.

### Confirmed defects, with evidence

| ID | Defect | Evidence |
|----|--------|----------|
| E1 | **Top bar slots can overlap.** Three children of a `Box` aligned `CenterStart` / `Center` / `CenterEnd`; the left slot was `widthIn(max = 160.dp)` and the centred undo/redo pair is ~86 dp wide. At 360 dp the left slot reaches x=160 while the pair starts at x=125 — the project title ran *under* the undo button. | `EditorScreen.kt` top bar, pre-change |
| E2 | **Touch targets below the floor.** Top bar back/undo/redo 40 dp; Export pill ≈34 dp tall; transport play 40 dp; tool-sheet close 28 dp; `ModeChip` ≈28 dp; `ErrorBanner` dismiss 28 dp; missing-media "Relink" ≈30 dp. | measured from the modifiers |
| E3 | **Primary export CTA failed WCAG AA.** `iOSDialogButtonStyle.Filled` was a horizontal `#8B5CF6 → #A78BFA` gradient under `Color.White`. White on `#8B5CF6` is 4.0:1; on `#A78BFA` it is **2.7:1**. This was the button that starts every export. | `ExportDialog.kt` pre-change |
| E4 | **On-video chrome was unreadable over bright footage.** Play-glyph scrim 35 % black (≈2.3:1 worst case), text pills 55 % (≈4.4:1), and the matting pill used an **18 % accent tint with no scrim at all** (≈1.1:1). | `EditorScreen.kt`, `BeforeAfterSplitSlider.kt` |
| E5 | **Mixed-language sheet headers.** `ToolPanel.title()` returned `"Trim"`, `"Filters"`, `"AI Upscale"` for some panels and «ردیابی حرکت»، «بهبود صدا» for others — the same label class, in the same header position, in English *and* Persian. | `EditorScreen.kt` pre-change |
| E6 | **English screen-reader labels and on-screen labels on live chrome.** `"Dismiss"`, `"Play"`/`"Pause"`, `"Relink"`, `"EDITING"`, `"Export"`, `"No video loaded"`, `"BG Remove ready"`, `"Filter:"`/`"B/C/S"`/`"Adjusted"`, and `"Reset"` repeated on six AI panels. | grep for ASCII-only `text = "` / `contentDescription = "` |
| E7 | **The three status banners were not distinguishable.** Missing-media used four raw amber literals, the network banner four raw blue literals, and the error banner a flat neutral `#1A1A20` with no icon and no status hue at all. | `EditorScreen.kt` banners pre-change |
| E8 | **No category navigation in the template picker.** 25 catalog templates rendered as ~13 rows (~2,600 dp) under 8 category headings inside an already-scrolling sheet, with no way to jump and no per-category counts. | `SubtitleStudioPanel.kt` pre-change |
| E9 | **Off-palette literals in the export dialog.** Gray button `0xFF3A3A3C → 0xFF2C2C2E` (iOS system grays, from outside the palette) and a tool-sheet gradient `0xFF13131A → 0xFF0F0F14` that existed nowhere else in the app. | both files |

### Deliberately left alone (with reasons)

- **The editor is LTR chrome by architecture.** `NavGraph.kt:94-96` wraps
  `EditorScreen` in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)`.
  Timeline maths, playhead position, trim handles and drag gestures all depend on it, and
  Persian *text* still renders RTL because Compose resolves paragraph direction per
  paragraph from the first strong character. Mirroring the workspace would be a
  behaviour change I cannot verify without a device, so I preserved it and translated
  the labels instead.
- **Timeline internals.** `EditorTimeline.kt` already uses `AccentBright` for the
  selected-clip state and a white→accent capsule for the playhead; track rails are
  intentionally 3 % white. `EditorLayoutSpec.TimelinePlayheadHitWidth` — the
  timeline-vs-playhead drag separation — was not touched.
- **`buildFilterChipLabel`'s `%.2f` formatting** is locale-aware; on a Persian locale
  Java's `Formatter` emits ASCII digits with the locale decimal separator. Unchanged.
- **The transition flash (`TransitionGeometry.flashBlackAlpha`) and the free-position
  text shadow (`Color.Black.copy(0.55f)`) are rendering, not chrome.** The text shadow
  in particular participates in preview↔export parity, so it was left as-is.

---

## 3. Before / after design decisions

### 3.1 Top bar (`EditorTopBarSection`)

- **Before:** `Box` + three independently aligned children (`CenterStart` / `Center` /
  `CenterEnd`), left slot `widthIn(max = 160.dp)`.
- **After:** `Box` keeps the `GlassChrome` background; the content is one `Row` with
  **equal `weight(1f)` side slots** around an intrinsic-width centre slot. Undo/redo stay
  *exactly* centred (the weights match), but an overlap is now geometrically impossible —
  the title ellipsises inside its own slot.
- 40 dp → **44 dp** on back/undo/redo; disabled alpha now uses the shared
  `StateDisabledAlpha` instead of a local `0.35f`.
- `Color.White.copy(0.06f)` → `HairlineSubtle` fill, `Hairline` border.
- Eyebrow `"EDITING"` in cyan → **«در حال ویرایش»** in `LabelSecondary`. It is the
  screen's identity label, not an informational signal, so it no longer competes with the
  project title for emphasis.
- Export pill: `≈34 dp` → **`heightIn(min = 44.dp)`**, label `"Export"` → **«خروجی»**, and
  the icon's duplicate `contentDescription` removed (a `clickable` node merges its
  descendants, so TalkBack previously announced "خروجی Export"). White is kept here
  because this *is* the `ctaBrush()` gradient, the only surface where the token contract
  permits it.

### 3.2 Playback strip and timeline band

- Play button 40 dp → **44 dp**, `contentDescription` "Play"/"Pause" → **«پخش»/«توقف»**,
  `Color.White` → `LabelPrimary`.
- Scrubber colours: raw `Color.White.copy(0.10f)` inactive track → `BorderSubtle`
  (the trough was nearly invisible), `iOSBlueBright`/`iOSBlue` → `AccentBright`/`AccentPrimary`.
- **New:** the transport band and the timeline band both used `CinematicSurface`, so they
  merged into one slab and the timeline's top boundary was unreadable. The timeline band
  now carries a 1 dp `HairlineSubtle` frame. `Modifier.border` draws inside the bounds, so
  layout is unchanged and no timeline coordinate moved.

### 3.3 Tool sheet

- Background gradient `0xFF13131A → 0xFF0F0F14` → **`SurfaceInset` → `SurfaceSunken`**
  (the recessed-surface tokens). The sheet is the deepest layer on screen, so this is
  where those tokens belong.
- Close control 28 dp → **40 dp**, `Color.White.copy(0.10f)` → `HairlineSubtle` +
  `Hairline` edge and `LabelPrimary` icon.
- The panel-title typography was already correct and is unchanged.

### 3.4 Status banners

All three now share one pattern — **status icon + tinted container + `LabelPrimary`
body** — and use status tokens:

| Banner | Before | After |
|--------|--------|-------|
| `MediaMissingBanner` | `#3A2E10` / `#6B541A` / `#FFC94D` / `#FFE0A0`; "Relink" ≈30 dp | `StatusWarningContainer`, `StatusWarning` icon + 35 % border, `LabelPrimary` body, **«انتخاب مجدد فایل»** at 40 dp |
| `AiModelNetworkBanner` | `#10233A` / `#2A4A73` / `#6FB3FF` / `#D6E8FF` | `StatusInfoContainer`, `StatusInfo` icon + 35 % border, `LabelPrimary` body |
| `ErrorBanner` | flat `#1A1A20`, white text, **no icon, no hue**, "Dismiss" at 28 dp | `StatusErrorContainer`, `StatusError` icon + 35 % border, `LabelPrimary` body, close at **40 dp** with a Persian accessible name |

The error banner had no status signal at all before; an error and ordinary chrome were
the same colour.

### 3.5 On-video chrome (new tokens)

`ui/theme/Color.kt` gains exactly two tokens, because chrome floating *directly* over the
video frame cannot rely on a surface token — its backdrop is arbitrary footage:

```kotlin
val OnVideoScrim       = Color(0x9E000000)  // 62% — behind icons/glyphs
val OnVideoScrimStrong = Color(0xBF000000)  // 75% — behind text pills
```

Applied in `EditorScreen.kt` (preview play glyph; filter/adjust pill; matting pill, which
now layers the scrim *under* its accent tint instead of relying on an 18 % tint alone) and
in `BeforeAfterSplitSlider.kt` (the bar's dark glow and its label pill — the file's own
comment already said the glow exists "so it reads against bright video frames").

Residual `Color.Black` uses in the editor that were **intentionally not** converted: the
transition flash overlay (`EditorScreen.kt` `flashBlackAlpha`), the canvas background, the
free-position text shadow, and the transition gradient stops in `EditorTimeline.kt`.

### 3.6 Export dialog

- **CTA gradient → `ctaBrush()`** (`AccentDeep → AccentCtaBottom`). This is the single
  highest-value change in this pass: the button that starts every export was unreadable at
  its light end. `ctaBrush()` is the project's sanctioned white-text gradient and is
  already the toolbar Export pill's fill, so the dialog CTA now matches the control that
  opened it.
- Gray button `0xFF3A3A3C / 0xFF2C2C2E` → `CinematicHighest / CinematicElevated`.
- Progress track `Color.White.copy(0.08f)` → `BorderSubtle`.
- Fact cards / stat pills: `Color.White.copy(0.05f)` → `HairlineSubtle` fill with a
  `Hairline` edge (fill and border were the same 7 % before, so the edge vanished).
- Unselected quality radio ring `HairlineSubtle` → `BorderSubtle` (visible).
- Selected quality row's label is now `iOSBlueBright`, matching how the template picker
  marks its selection — "selected" now reads the same in both places.
- Primary button gained `heightIn(min = 44.dp)` and `role = Role.Button`.
- All dialog copy → Persian: «خروجی ویدئو»، «کیفیت خروجی را انتخاب کنید»، «شروع خروجی»،
  «انصراف»، «مدت»، «حجم تقریبی»، «در حال خروجی گرفتن»، «خروجی آماده شد»، «حجم»،
  «پخش ویدئو»، «تمام»، «خروجی ناموفق بود»، «خطای ناشناخته»، «خروجی لغو شد»،
  «هیچ فایلی ذخیره نشد.»، «باشه». The progress-stage strings were already Persian, so the
  dialog was previously mixed within a single flow.

### 3.7 Sheet headers (`ToolPanel.title()`)

One mapping function, 32 panels, fully Persian now — including previously English
`"Trim"` → «برش»، `"Playback Speed"` → «سرعت پخش»، `"Filters"` → «فیلترها»،
`"Color Grading"` → «اصلاح رنگ»، `"AI Upscale"` → «بزرگنمایی هوشمند»، `"Background
Remove"` → «حذف پس‌زمینه»، `"Auto Frame"` → «قاب‌بندی خودکار»، `"Voice Clean"` →
«پاک‌سازی صدا»، `"AI OCR"` → «تشخیص متن تصویر», and `"ردیابی حرکت (Motion Tracking)"` →
«ردیابی حرکت» (an English gloss inside a Persian header). The stray 8-space indentation on
eleven branches was normalised at the same time.

**Scope note:** only this *mapping* is translated. The roughly 200 strings **inside** the
panels are deliberately untouched in this phase, per the instruction not to mass-translate
unrelated editor strings.

### 3.8 Subtitle template picker (category navigation)

`SubtitleStudioPanel.kt` gained a category chip row above the grid:

- New remembered state `pickerCategory: SubtitleTemplateCategory?` (`null` = «همه»),
  declared **unconditionally at the top of the composable** so the `remember` slot is
  stable regardless of which branch composes.
- `visibleGroups` is a **view filter** over the *same* `SubtitleTemplateTaxonomy.group()`
  result the grid already consumed. Counts come from that same list, so a chip can never
  advertise more templates than the grid shows. If a category ever yielded no rows, the
  code falls back to showing everything rather than an empty section.
- Chips are `«همه» (n)` plus one per category with its count, reusing `ModeChip`.
- **Nothing about templates changed**: ids, styles, `WordRevealMode`, `WordMotionKind`,
  word timing, active-word highlighting, the `CaptionRenderer` preview path and the
  export path are untouched.
- `ModeChip` 28 dp → **36 dp** with `role = Role.Button`, raw 5 % white fill →
  `HairlineSubtle`, faint border → `Hairline`. This also upgrades the Font / Color /
  Shadow / Highlight / Background / Motion / Position tab chips, which all use `ModeChip`.
- `TemplateGridCard`: unselected fill raw 4 % white → `HairlineSubtle`, border →
  `Hairline`, `role = Role.Button` added.
- `TemplateGridSkeleton`: three raw white literals → `HairlineSubtle` / `Hairline`.

### 3.9 Remaining on-canvas labels

- `"No video loaded"` → **«ویدئویی بارگذاری نشده»** (the empty preview placeholder — the
  first thing a user sees in the focal area).
- `"BG Remove ready"` → **«حذف پس‌زمینه آماده است»**.
- `buildFilterChipLabel`: `"Filter: …"` → «فیلتر: …»; `B`/`C`/`S` → «نور» / «کنتراست» /
  «اشباع» (Latin letters inside a Persian pill read as a rendering glitch over video);
  `"Adjusted"` → «اصلاح‌شده».

### 3.10 Accessibility labels on AI panels

`contentDescription = "Reset"` appeared on six live panel controls
(`AiMattingPanel`, `MotionTrackPanel`, `OcrPanel`, `AutoFramePanel`, `VoiceCleanPanel`,
`UpscalerPanel`) — the same English accessible name repeated across the AI surfaces.
All six are now «بازنشانی». `UpscalerPanel`'s result banner also moved
`"Done"`/`"Failed"`/`"Dismiss"` → «انجام شد»/«ناموفق»/«بستن», and its dismiss control grew
from ≈30 dp to a comfortable body.

---

## 4. Usability issues fixed

1. **Title/undo overlap at phone widths** — removed by construction (E1).
2. **The controls this pass touched now meet the 36–44 dp floor** (E2): the top-bar
   back/undo/redo triple, the Export pill, the transport play button, the tool-sheet close,
   the error-banner close, the missing-media action, `ModeChip` (used in eight places), and
   the upscale result banner's dismiss control. Every one of them was below it before.
3. **The export CTA meets WCAG AA** (E3) — it did not before.
4. **On-video status pills and the play glyph are legible over bright frames** (E4).
5. **The timeline's top boundary is visible**; transport, timeline and dock read as three
   bands instead of one slab.
6. **An error now looks like an error** (E7) rather than like ordinary chrome.
7. **Six AI panels no longer announce themselves in English** to screen readers (E6).
8. **The picker has category navigation with counts** (E8) — the worst navigation cost in
   the app's signature feature.
9. **"Selected" is signalled consistently** across the export quality list, the template
   cards and `ModeChip`: accent-tinted label + accent border + accent wash.
10. **Shell labels are Persian throughout** the workspace (top bar, transport, banners,
    sheet headers, export dialog) — the editor was the last mixed-language surface (E5, E6).

---

## 5. Tests and build results

All commands run from the repo root on 2026-10-16. Test and assemble were invoked as
**separate** statements so a failing test task cannot leave a stale APK that looks
successful. PowerShell treats Gradle stderr warnings as errors, so a post-run exit code
of `1` appears after `BUILD SUCCESSFUL`; results below are read from the artifacts, not
the exit code.

| Check | Command | Result |
|-------|---------|--------|
| Compile (main) | `.\gradlew.bat :app:compileDebugKotlin --console=plain` | **BUILD SUCCESSFUL** |
| Unit tests | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | **BUILD SUCCESSFUL** |
| Debug APK | `.\gradlew.bat :app:assembleDebug --console=plain` | **BUILD SUCCESSFUL** |
| Instrumentation-test compile | `.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain` | **BUILD SUCCESSFUL** |

### Test totals (read from `app/build/test-results/testDebugUnitTest/*.xml`)

```
suites=216  tests=2925  failures=0  errors=0  skipped=0
```

Identical to the pre-Phase-2 baseline (216 / 2925 / 0 / 0 / 0). **No test expectation was
modified** to make anything pass; no test file was touched in this phase.

### Artifact

```
app/build/outputs/apk/debug/app-debug.apk   115.43 MB
```

Byte-size identical to the Phase 1 build.

### Warnings

One pre-existing compiler warning remains, in code this phase did not touch:

```
w: .../EditorScreen.kt:3293:32 Condition is always 'true'.
```

(it is `if (hasWash && motionPose != null)` in the preview's transition-wash block). No
new warning was introduced in any changed file.

### Static analysis

`:app:lintDebug` remains **unavailable, not passing** — lint is disabled repo-wide at
`app/build.gradle.kts:207-212`, and the previously attempted enable crashed inside
`UastGradleVisitor.visitBuildScript`. That is a tooling blocker carried over from Phase 1,
not something this phase resolved. No lint result is claimed.

### Token hygiene (measured)

| Metric | Phase 1 end | Phase 2 end |
|--------|-------------|-------------|
| Raw `Color(0x…)` literals outside `ui/theme/` | 145 → 117 | **102** |
| Raw literals inside `ui/theme/` | 88 | 90 (two new tokens) |

`Color.White` alpha-tints are still widespread in the editor (**228** occurrences in
`ui/screens/editor/`). Many are legitimate — white text and glyphs drawn over video — but
this number is **not** a claim that the editor is fully tokenised. Only the surfaces this
phase actually redesigned were converted. See §8.

### Persian text integrity (measured)

Read via `[System.IO.File]::ReadAllBytes` + UTF-8 decode, never from console echo (the
console codepage mangles non-ASCII to `?`).

```
EditorScreen.kt        ZWNJ= 13  U+FFFD=0
ExportDialog.kt        ZWNJ=  8  U+FFFD=0
SubtitleStudioPanel.kt ZWNJ= 61  U+FFFD=0
AiMattingPanel.kt      ZWNJ=  4  U+FFFD=0
MotionTrackPanel.kt    ZWNJ= 10  U+FFFD=0
OcrPanel.kt            ZWNJ=  4  U+FFFD=0
AutoFramePanel.kt      ZWNJ=  5  U+FFFD=0
VoiceCleanPanel.kt     ZWNJ=  0  U+FFFD=0   (its new strings carry no ZWNJ)
UpscalerPanel.kt       ZWNJ=  0  U+FFFD=0   (same)
Color.kt               ZWNJ=  0  U+FFFD=0
BeforeAfterSplitSlider ZWNJ=  0  U+FFFD=0
TOTAL                  ZWNJ=105  U+FFFD=0
```

Every new Persian string that requires a ZWNJ carries one — verified by grepping the
literal text (e.g. «پاک‌سازی صدا»، «قاب‌بندی خودکار»، «بزرگ‌نمایی هوشمند»،
«جابه‌جایی و برش»، «اصلاح‌شده»، «پس‌زمینه»).

---

## 6. Contrast changes (computed, not device-measured)

These are **calculated** from the token definitions under the worst-case backdrop
(pure-white footage), using WCAG relative-luminance maths. They are not screenshots and
not device measurements.

| Foreground / backdrop | Before | After |
|---|---|---|
| White on the export CTA gradient | 4.0:1 → **2.7:1** (fails AA) | 5.7:1 → **7.1:1** (`ctaBrush()`) |
| White glyph on the preview play scrim over white footage | ≈2.3:1 | **≈6.2:1** (`OnVideoScrim`) |
| White text on a status pill over white footage | ≈4.4:1 | **≈10.4:1** (`OnVideoScrimStrong`) |
| White text on the matting pill over white footage | ≈1.1:1 | **≈6.0:1** (scrim + 32 % tint) |
| `LabelPrimary` on `StatusErrorContainer` / `StatusWarningContainer` / `StatusInfoContainer` | n/a (no container before) | ≥ 14:1 (near-white on very dark) |

---

## 7. Device verification status — **BLOCKED**

```
$ C:\android\platform-tools\adb.exe devices -l
List of devices attached
```

No device and no emulator. The following are therefore **unverified** and no screenshot,
RTL rendering, or on-device behaviour is claimed anywhere in this report:

- Whether the new three-slot top bar actually ellipsises the title cleanly at 360 dp
  (the overlap is removed *by construction*, but the resulting truncation is unseen).
- Real Persian glyph shaping, joining and wrapping of the new strings at 1.0× and 1.3×
  `fontScale` — including whether any sheet header now wraps to two lines.
- The new on-video scrim values against real bright and dark footage.
- Touch-target comfort in the hand (the *sizes* are 36–44 dp; the *reachability* is not
  assessed).
- The picker's category chip row at narrow widths — whether the horizontal scroll feels
  right and whether 9 chips plus counts read as intended in RTL chrome.
- Dialog and bottom-sheet spacing with the keyboard open.
- That the export dialog's CTA gradient and the new `ctaBrush()` look coherent side by side
  with the toolbar Export pill.

**Nothing in §5 changes these.** Compilation and unit tests cannot substitute for visual
verification, and the existing 2,925 tests contain no UI assertions for the editor chrome
(no Robolectric, and `android.graphics` is stubbed via `unitTests.isReturnDefaultValues`).

---

## 8. Remaining limitations and recommended next steps

### Honest limitations of this pass

1. **`Color.White` alpha-tints remain extensive in the editor (228 occurrences).**
   Only the surfaces redesigned here were converted. Tokenising the rest is mechanical but
   large, and several are legitimately "white over video".
2. **The editor is still LTR chrome.** Persian labels inside an LTR `Row` are *positioned*
   left-to-right. This is the existing architecture and I preserved it deliberately;
   mirroring the workspace is a separate, device-verified piece of work.
3. **`ContentDescription` English strings remain in panels I did not open** — for example
   `FloatingPanel.kt` ("Drag", "Close"), `EditPanel.kt` ("Subtitle style"),
   `UpscaleBottomSheet.kt` ("Close", "Selected"). Two of those files are unreferenced
   anyway (§8.3).
4. **The ~200 in-panel content strings are still mixed English/Persian.** Only the sheet
   *headers* were converted. This is the largest remaining localisation gap and it is
   explicitly out of scope for this phase.
5. **`UpscaleProgressDialog.kt` still shows "Export Complete" / "Export Failed" in
   English.** It is the same defect class as the export dialog and was found during the
   audit, but it belongs to the AI-upscale flow rather than the export dialog named in
   scope, so it was left for a focused follow-up.
6. **The identical 35 % black scrims in `BeatSyncPanel`, `ReframePanel`,
   `VoiceEnhancePanel` and `EditPanel`** were not converted — their context was not
   confirmed to be over-video. The new `OnVideoScrim` token makes each a one-line fix once
   that is checked.
7. **`:app:lintDebug` is still unavailable** (§5). There is no static-analysis signal.

### 8.3 Dead-code findings (evidence only — **not deleted**)

Per the instruction to report rather than delete, these are verified unreferenced and are
recorded for a separate cleanup decision:

| File | Lines | Evidence |
|---|---|---|
| `ui/screens/editor/EditorTopBar.kt` | 206 | `EditorTopBar(` matches only its own definition |
| `ui/screens/home/MembershipGlassCard.kt` | — | `MembershipGlassCard(` matches only its own definition |
| `ui/screens/editor/FloatingPanel.kt` | 275 | `FloatingPanelContainer` / `FloatingVolumeControl` / `FloatingPanelState` have no references outside the file; the only `FloatingPanel(` call is from `FloatingPanelContainer` in the same file |
| `ui/screens/editor/UpscaleBottomSheet.kt` | 593 | the `UpscaleBottomSheet(` composable has no callers; only comments elsewhere mention it |

That is roughly **1,074 lines** of unreferenced editor/home code. Note that
`EditPanel.kt` is **not** dead — it defines no symbol named `EditPanel`, and its real
entry points are referenced.

### Recommended next steps, in priority order

1. **P0 — verify on hardware.** Install `app-debug.apk` and walk §7, then add a dated
   addendum with screenshots. Until then §7 stands.
2. **P1 — finish the design-system adoption in the editor.** Remaining raw literals:
   `ReframePanel` (10), `BeatSyncPanel` (8), `VoiceEnhancePanel` (7),
   `HighlightPanel` (7), `AutoSilenceCutPanel` (7), `EditorTimeline.kt`'s transition stops.
   Then apply `OnVideoScrim` / `OnVideoScrimStrong` where §8.6 applies.
3. **P1 — convert the editor's ~200 in-panel strings to Persian,** or declare them
   permanently out of scope. The sheet headers now set the pattern.
4. **P2 — give the tool panels themselves the same first-class treatment** as the picker
   and Home: clearer progress/error states for the AI panels, polished active states,
   consistent section headers — without touching transcription or timing.
5. **P2 — `UpscaleProgressDialog.kt` and the remaining English `contentDescription`s**
   (one pass, mechanical, ~10 strings).
6. **P2 — decide on the four unreferenced files** in §8.3.
7. **P2 — restore a static-analysis signal**, either by repairing the
   `UastGradleVisitor` crash or by enabling a narrow non-crashing lint subset.

### Carried over from earlier phases (still open, untouched here)

- `docs/MANUAL_TEST_CHECKLIST.md` is stale ("17 templates"; corrupted Persian tab label).
- The `1.12f` `activeWordScale` authoring cap boundary.
- `EditorViewModel.kt` mojibake (107 U+FFFD over 101 comment lines, comments only).
- Stale `ggml-medium` references in `androidTest`.
