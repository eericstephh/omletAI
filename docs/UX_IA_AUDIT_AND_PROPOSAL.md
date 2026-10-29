# UX & Information Architecture Audit — Editor Toolbars & Panels

**Status:** PROPOSAL — awaiting review/approval. No code has been changed.
**Date:** 2026-09-28
**Scope:** `ui/screens/editor/**`, `model/EditorUiState.kt`, `viewmodel/{EditorViewModel,TimelineDelegate,SubtitleDelegate}.kt`
**Goal:** Replace the flat, always-identical tool dock with a **2-level contextual toolbar** (CapCut / InShot architecture) driven by what the user has selected.

---

## 0. Executive Summary

The editor is **feature-complete but navigation-poor**. There are **27 distinct tool panels** and **31 dock chip definitions**, presented through a **fixed 5-category + extras dock that never reacts to selection**. The user must remember where a tool lives, scroll a 12-chip horizontal row, and in two cases *cannot reach a panel at all*.

Four structural problems, in severity order:

| # | Problem | Impact |
|---|---|---|
| **P1** | The dock is **context-blind**. It renders the same 5 categories + 6 extras whether nothing, a video clip, an audio clip, or a text overlay is selected. | Every tool is 2 taps + a scroll away, always. No discoverability, no affordance for "what can I do *to this thing*". |
| **P2** | **2 panels are unreachable** (`ToolPanel.ADJUST`, `ToolPanel.CROP`) — they have `SubPanelContent` branches, `title()` entries and preview wiring, but **no dock entry**. | Dead features. `ADJUST` is also a verbatim duplicate of the `EFFECTS` branch. |
| **P3** | **416 lines of dead dock code** in two files (`EditorBottomDock.kt`, `BottomToolbar.kt`) that a maintainer would reasonably edit — and change nothing. | Classic *"written but never rendered"*. Compounded by a stale `LAYOUT_NOTES.md` that tells you to edit the dead file. |
| **P4** | **4 AI tools are dead-ends** (`locked = true` → `"بزودی..."` toast): `UPSCALER`, `AI_MATTING`, `AI_AUTOFRAME`, `AI_OCR`. Their panels exist and are fully wired. | 4 of 7 chips in the AI category do nothing. |

Plus **duplicated entry points** (Split exists in 2 places, Duplicate/Delete in 2 each), a **broken selection invariant** for text, **two competing implementations** of the same track-reveal rule, and — found during implementation — a **fourth dead file** (`EditorPlaybackStrip.kt`, 347 lines).

The good news: **the hard part already exists.** `selectedClipId`, `selectedAudioClipId` and `selectedTextId` are already on `EditorUiState` and are *mostly* mutually exclusive. The new IA is a **derivation + a presentation swap**, not a data-model rewrite.

---

## 1. Method & Files Scanned

| File | Lines | Role |
|---|---|---|
| `ui/screens/editor/EditorScreen.kt` | 5,053 | Hosts the **live** dock (`EditorBottomDockSection`), the panel sheet (`SubPanelRow`), and the dispatcher (`SubPanelContent`) |
| `ui/screens/editor/EditorTimeline.kt` | 2,640 | Clip / audio / text / subtitle selection gestures |
| `ui/screens/editor/EditPanel.kt` | 1,304 | `AnimationPicker`, `CropPicker`, `AISubtitleCard` |
| `ui/screens/editor/subtitle/SubtitleStudioPanel.kt` | — | «زیرنویس فارسی» template studio |
| `ui/screens/editor/{SpeedCurvePanel, MotionTrackPanel, AiMattingPanel, AutoFramePanel, UpscalerPanel, OcrPanel, AutoSilenceCutPanel, BeatPanel, VoiceCleanPanel, AudioPanel, CanvasPanels, EffectsPanel, TextOverlayPanel, ParagraphTextPanel, OpacityPanel, SubtitleStylePanel, TransitionPanel, ToolbarSubPanels}.kt` | ~7,000 | The 27 panel bodies |
| `ui/screens/editor/{EditorBottomDock,BottomToolbar}.kt` | 416 | **DEAD** legacy docks |
| `ui/screens/editor/EditorPlaybackStrip.kt` | 355 | Transport row (2nd entry point for Split + Aspect) |
| `ui/screens/editor/EditorLayoutSpec.kt` | 79 | Dock height, sub-panel fraction, spacing scale |
| `model/EditorUiState.kt` | ~550 | `ToolPanel` enum (27 values) + selection fields |
| `viewmodel/{EditorViewModel,TimelineDelegate,SubtitleDelegate}.kt` | — | Selection mutations + `revealTracksForTool` |

---

## 2. Current State — Full Inventory

### 2.1 The three chrome layers

```
EditorScreen
├── EditorTopBarSection            (EditorScreen.kt:1624)   ← Back · Undo · Redo · Export
├── EditorVideoPreviewSection      (EditorScreen.kt:1763)   ← canvas + free-position text + selection chrome
├── EditorPlaybackStripSection     (EditorScreen.kt:3245)
│   └── TransportRow               (EditorPlaybackStrip.kt:160)
│       └── ⏪  ▶  ⏩   ·   ✂Split  ⛶Aspect  ⛶Fullscreen   ← 3 of these duplicate the dock
├── EditorTimelineSection          (EditorScreen.kt:3356)
└── EditorBottomDockSection        (EditorScreen.kt:3436)   ← THE TOOL DOCK
    ├── DockCategoryRow            (EditorScreen.kt:3602)   ← ROW 1: always visible
    └── DockCategoryToolRow        (EditorScreen.kt:3657)   ← ROW 2: only when a category is expanded
        └── SubPanelRow            (EditorScreen.kt:3730)   ← 50%-height sheet, opens above the dock
            └── SubPanelContent    (EditorScreen.kt:4130)   ← 27-branch `when (uiState.toolPanel)`
```

### 2.2 The live dock — what the user actually sees

**Row 1 (always visible, horizontally scrollable — 12 chips):**

| # | Chip | Persian | Type | Payload |
|---|---|---|---|---|
| 1 | ویرایش | Edit | category | `TRIM`, `SPEED` |
| 2 | صدا | Audio | category | `VOLUME`, `AUDIO`, `AI_VOICE_CLEAN` |
| 3 | تنظیم رنگ | Color | category | `FILTERS`, `EFFECTS`, `OPACITY`, `TRANSFORM` |
| 4 | متن | Text | category | `TEXT`, `PARAGRAPH`, `SUBTITLES` |
| 5 | هوش مصنوعی | AI | category | `UPSCALER`🔒, `AI_MATTING`🔒, `AI_AUTOFRAME`🔒, `AI_OCR`🔒 |
| — | *(divider)* | | | |
| 6 | نسبت | Ratio | panel | `ASPECT_RATIO` |
| 7 | ترنزیشن | Transition | panel | `TRANSITIONS` |
| 8 | انیمیشن | Animation | panel | `ANIMATIONS` |
| 9 | برش | Split | action | `"split"` |
| 10 | تکثیر | Duplicate | action | `"duplicate"` |
| 11 | حذف | Delete | action | `"delete"` (destructive) |

**Row 2 (appears only when a category is expanded):**

| Category | Tools revealed |
|---|---|
| ویرایش (edit) | تریم `TRIM` · برش `"split"` · سرعت `SPEED` · تکثیر `"duplicate"` · حذف `"delete"` |
| صدا (media) | صدا `VOLUME` · فایل صوتی `AUDIO` · پاکسازی صدا `AI_VOICE_CLEAN` |
| تنظیم رنگ (visual) | فیلترها `FILTERS` · افکت `EFFECTS` · شفافیت `OPACITY` · کراپ `TRANSFORM` · پس‌زمینه `BACKGROUND` · اورلی `OVERLAY` |
| متن (text) | متن `TEXT` · پاراگراف `PARAGRAPH` · زیرنویس AI `SUBTITLES` · زیرنویس فارسی `SUBTITLE_STUDIO` |
| هوش مصنوعی (ai) | افزایش کیفیت🔒 · حذف پس‌زمینه🔒 · اتوفریم🔒 · ردیابی حرکت · متن‌یابی🔒 · برش خودکار سکوت · ضرباهنگ |

🔒 = `locked = true` → tapping shows a `"بزودی..."` toast and never opens the panel.

### 2.3 Complete `ToolPanel` → reachability matrix (all 27 values)

`enum class ToolPanel` — `model/EditorUiState.kt:450`. `SubPanelContent` handles **all 27** (`EditorScreen.kt:4280-4865`).

| # | `ToolPanel` | Panel composable | `title()` | Dock entry | Reachable? |
|---|---|---|---|---|---|
| 1 | `TRIM` | `TrimPanel` (ToolbarSubPanels.kt:191) | Trim | ویرایش → تریم | ✅ |
| 2 | `SPEED` | `SpeedPanel` + `SpeedCurvePanel` | Playback Speed | ویرایش → سرعت | ✅ |
| 3 | `VOLUME` | `VolumeSheet` | Volume | صدا → صدا · **auto-opens on audio tap** | ✅ (2 paths) |
| 4 | `TEXT` | `TextOverlayPanel` | Add Text | متن → متن · **auto-opens on text tap** | ✅ (2 paths) |
| 5 | `PARAGRAPH` | `ParagraphTextPanel` | متن پاراگراف | متن → پاراگراف | ✅ |
| 6 | `SUBTITLES` | `AISubtitleCard` | AI Subtitles | متن → زیرنویس AI | ✅ |
| 7 | `AUDIO` | `AudioPanel` | Audio | صدا → فایل صوتی | ✅ |
| 8 | `FILTERS` | `FilterPanel` | Filters | تنظیم رنگ → فیلترها | ✅ |
| 9 | **`ADJUST`** | **`EffectsPanel` (verbatim duplicate)** | Color Grading | **— none —** | ❌ **ORPHAN** |
| 10 | `EFFECTS` | `EffectsPanel` | Effects | تنظیم رنگ → افکت | ✅ |
| 11 | **`CROP`** | **`CropPicker`** | Crop | **— none —** | ❌ **ORPHAN** |
| 12 | `BACKGROUND` | `BackgroundPanel` | Background | تنظیم رنگ → پس‌زمینه | ✅ |
| 13 | `OPACITY` | `OpacityPanel` | Opacity | تنظیم رنگ → شفافیت | ✅ |
| 14 | `OVERLAY` | `OverlayPicker` | Add Overlay | تنظیم رنگ → اورلی | ✅ |
| 15 | `ANIMATIONS` | `AnimationPicker` | Animations | extras → انیمیشن | ✅ |
| 16 | `TRANSITIONS` | `TransitionPanel` | Transitions | extras → ترنزیشن | ✅ |
| 17 | `ASPECT_RATIO` | `AspectRatioPanel` | Canvas | extras → نسبت · playback strip | ✅ (2 paths) |
| 18 | `TRANSFORM` | `TransformPanel` | Transform & Crop | تنظیم رنگ → کراپ | ✅ |
| 19 | `UPSCALER` | `UpscalerPanel` | AI Upscale | هوش مصنوعی → **🔒 locked** | ⚠️ **dead-end** |
| 20 | `AI_MATTING` | `AiMattingPanel` | Background Remove | هوش مصنوعی → **🔒 locked** | ⚠️ **dead-end** |
| 21 | `AI_AUTOFRAME` | `AutoFramePanel` | Auto Frame | هوش مصنوعی → **🔒 locked** | ⚠️ **dead-end** |
| 22 | `AI_MOTION_TRACK` | `MotionTrackPanel` | ردیابی حرکت | هوش مصنوعی → ردیابی حرکت | ✅ |
| 23 | `AI_VOICE_CLEAN` | `VoiceCleanPanel` | Voice Clean | صدا → پاکسازی صدا | ✅ |
| 24 | `AI_OCR` | `OcrPanel` | AI OCR | هوش مصنوعی → **🔒 locked** | ⚠️ **dead-end** |
| 25 | `AI_SILENCE_CUT` | `AutoSilenceCutPanel` | برش خودکار سکوت | هوش مصنوعی → برش خودکار سکوت | ✅ |
| 26 | `BEATS` | `BeatPanel` | تشخیص ضرباهنگ | هوش مصنوعی → ضرباهنگ | ✅ |
| 27 | `SUBTITLE_STUDIO` | `SubtitleStudioPanel` | زیرنویس فارسی | متن → زیرنویس فارسی | ✅ |

**Tally:** 27 defined · 25 reachable · **2 orphans** · 4 of the 25 are locked dead-ends → **21 genuinely usable**.

### 2.4 Selection model (already present)

| Field | Declared | Set by | Clears the others? |
|---|---|---|---|
| `selectedClipId: String?` | `EditorUiState.kt:43` | `TimelineDelegate.selectClip` (:28) | ✅ clears audio + text |
| `selectedAudioClipId: String?` | `EditorUiState.kt:51` | `TimelineDelegate.selectAudioClip` (:38) / `EditorViewModel.selectAudioClip` (:1588) | ✅ clears clip + text |
| `selectedTextId: String?` | `EditorUiState.kt` | `SubtitleDelegate.selectTextOverlay` (:162) / `selectTextOverlayWithPanel` (:166) | ❌ **clears nothing** |
| `toolPanel: ToolPanel?` | `EditorUiState.kt:205` | `EditorViewModel.setToolPanel` (:638) | — |

Derived: `val selectedClip: VideoClip?` = `project?.clips?.find { it.id == selectedClipId }` (`EditorUiState.kt:416`).

**Current auto-open behaviour** (the seed of contextual UI, applied inconsistently):
- Tapping an **audio** clip → auto-sets `toolPanel = VOLUME` (`EditorViewModel.kt:1612`).
- Tapping a **text/subtitle** overlay → `selectTextOverlayWithPanel` sets `toolPanel = TEXT` (`SubtitleDelegate.kt:170`).
- Tapping a **video** clip → sets `selectedClipId` only. **No toolbar change at all.**

---

## 3. Findings

### P1 — The dock is context-blind *(highest impact)*
`EditorBottomDockSection` (`EditorScreen.kt:3436`) takes only `selectedPanel`, `onPanelSelected`, `onAction`. It **never reads** `selectedClipId` / `selectedAudioClipId` / `selectedTextId`. Consequence: the dock is identical in all four selection states, so the user gets zero affordance for "what applies to the thing I just tapped". `TRIM`, `SPEED`, `FILTERS`, `OPACITY` are equally visible when a *text overlay* is selected — where they do nothing but print "Select a clip to…".

### P2 — Two orphaned panels
`ToolPanel.ADJUST` and `ToolPanel.CROP` are referenced only by:
- their own `SubPanelContent` branch (`:4539`, `:4613`),
- `ToolPanel.title()` (`:5004`, `:5006`),
- `isAdjustPanelOpen = uiState.toolPanel == ToolPanel.ADJUST` (`:1104`).

**No `DockTool(...)` anywhere points at them.** They are unreachable dead features. `ADJUST` is worse than dead: its body is a **verbatim copy** of the `EFFECTS` branch (compare `:4542-4564` with `:4577-4599`) — 23 duplicated arguments, so any `EffectsPanel` signature change must be made twice.

### P3 — 416 lines of dead dock code + a stale doc
`EditorBottomDock.kt` (`EditorDockItems`, `EditorBottomDock`) and `BottomToolbar.kt` (`ToolbarItem`, `BottomToolbarItems`, `BottomToolbar`) are **referenced by nothing**. Grep for `EditorBottomDock(`, `BottomToolbar(`, `EditorDockItems`, `BottomToolbarItems` returns only their own definitions — plus `LAYOUT_NOTES.md:34`, which actively instructs:

> *"New bottom toolbar item: edit `BottomToolbarItems` in `BottomToolbar.kt`"*

That is a live trap: following the project's own note produces a no-op change.

> ⚠️ `VolumeSheet` also lives in the dead `BottomToolbar.kt` — it **is** used (`EditorScreen.kt:4340, 4352`). It must be **moved, not deleted**.

### P4 — 4 AI tools are dead-ends
`DockTool(..., locked = true)` on `UPSCALER`, `AI_MATTING`, `AI_AUTOFRAME`, `AI_OCR` (`EditorScreen.kt:3581-3585`). `DockChip` (`:3704`) intercepts the tap and shows `"بزودی..."`. The panels (`UpscalerPanel`, `AiMattingPanel`, `AutoFramePanel`, `OcrPanel`) are all implemented and wired through `SubPanelContent`. Affordance is also inconsistent: 3 use `Icons.Filled.Lock` as the chip icon, `UPSCALER` uses `Icons.Filled.AutoAwesome`.

**This is a product decision, not a bug** — but it means the AI category is 4/7 noise.

### P5 — Duplicated entry points
| Tool | Places it lives | Problem |
|---|---|---|
| **Split** | dock extras (`:3544`) · ویرایش category (`:3554`) | 2 entry points, both visible at once |
| **Aspect ratio** | dock extras (`:3541`) · Root → Canvas (proposed) | 1 live entry point |
| **Volume** | صدا category → `VOLUME` panel · auto-open on audio tap → same panel · **and** `VolumeSheet` branch when a clip is selected | Same enum, two different render paths |
| **Duplicate / Delete** | dock extras (`:3545-3546`) · ویرایش category (`:3556-3557`) | Byte-identical duplicate chips |

> **Correction (2026-09-28, during implementation).** The first draft of this
> audit claimed Split had **three** entry points, the third being the transport
> row in `EditorPlaybackStrip.kt`. That was wrong: `EditorPlaybackStrip.kt` is
> **itself dead code** — `EditorPlaybackStrip` (public), `IconAction`
> (internal), and five private composables are referenced by **nothing**
> (grep-verified). The live strip is `EditorPlaybackStripSection` in
> `EditorScreen.kt:3255`, which renders inline and has no Split/Aspect button.
> So the real count was 2, and the file was deleted in Phase 4 as a fourth
> instance of the "written but never rendered" class this audit is about —
> 347 lines that a maintainer could reasonably have edited to no effect.

### P6 — Selection invariant is broken for text
`selectClip` and `selectAudioClip` both null out the other two selections. `selectTextOverlay` (`SubtitleDelegate.kt:162-164`) and `selectTextOverlayWithPanel` (`:166-173`) clear **neither**. So tapping a subtitle leaves `selectedClipId` pointing at a video clip. Today this is invisible (no consumer reads both); under the new contextual IA it becomes **ambiguous** — the context resolver would not know which toolbar to show.

### P7 — Two competing implementations of the same rule
Track reveal is implemented twice:

| | `EditorViewModel.revealTracksForTool` (`:678-699`) | `EditorScreen` inline `when (enum)` (`:1584-1596`) |
|---|---|---|
| `SUBTITLES` | `SUBTITLE` | `SUBTITLE` **+ `TEXT`** |
| `AUDIO` | `AUDIO_MUSIC` | `AUDIO_MUSIC` **+ `AUDIO_SFX`** |
| `OVERLAY` | `OVERLAY` | `OVERLAY` |
| `TEXT` | `TEXT` | `TEXT` |
| `EFFECTS` / `TRANSITIONS` | `EFFECTS` | *(not handled)* |

They **disagree**, and both run (`setToolPanel` calls the VM version; the dock callback then runs the screen version), so `focusedTrackKind` is decided by write order. Adding a panel to one and not the other silently drifts.

### P8 — The root row already overflows
Row 1 = 5 categories + divider + 6 extras = **12 chips in a `LazyRow`**. On a ~360dp-wide phone at `iOSToolbarItem` width, only ~6 are visible. `برش`/`تکثیر`/`حذف` — the three most-used edit actions — are the **last** three, i.e. permanently scrolled off-screen.

### P9 — Category semantics are muddy
- **«تنظیم رنگ»** (*color adjustment*) contains filters, effects, opacity, transform, background **and** overlay — it is a grab-bag, not a color category.
- **«کراپ»** (crop) is the label for `ToolPanel.TRANSFORM`, while a real `ToolPanel.CROP` (`CropPicker`) exists unused. Two different things claim the word "crop".
- **«صدا»** (*audio*) holds `VOLUME` + `AUDIO` + `AI_VOICE_CLEAN` — three genuinely different concerns.

### P10 — Panels print empty-state text instead of not existing
`TRIM`, `SPEED`, `ADJUST`, `EFFECTS` all render `"Select a clip to trim/adjust."` when nothing is selected (`:4294`, `:4566`, …). Under a contextual IA this state becomes **unreachable by construction**, and those 4 branches can drop their `else` arms.

---

## 4. Proposed IA — 2-Level Contextual Toolbar

### 4.1 The core idea

> **The toolbar is a function of the selection, not a fixed palette.**

One derived value drives everything:

```kotlin
// model/EditorContext.kt  — pure, unit-testable (no Compose, no Android deps)
enum class EditorContext { ROOT, CLIP_SELECTED, AUDIO_SELECTED, TEXT_SELECTED }

fun resolveEditorContext(
    selectedClipId: String?,
    selectedAudioClipId: String?,
    selectedTextId: String?
): EditorContext = when {
    selectedTextId != null     -> EditorContext.TEXT_SELECTED   // topmost z-order
    selectedAudioClipId != null -> EditorContext.AUDIO_SELECTED
    selectedClipId != null     -> EditorContext.CLIP_SELECTED
    else                       -> EditorContext.ROOT
}
```

Priority mirrors the visual stacking (text renders above video, audio is a sibling lane), and is **defensive only** — §5.2 fixes the invariant so at most one is ever set.

### 4.2 The new structure

```
EditorBottomDockSection
└── ContextualToolbar                         ← NEW: one row, never scrolls
    ├── (ROOT)            → 5 chips
    ├── (CLIP_SELECTED)   → 6 chips + trailing icon-only Duplicate/Delete
    ├── (AUDIO_SELECTED)  → 3 chips + trailing icon-only Delete
    └── (TEXT_SELECTED)   → 2 chips + trailing icon-only Delete
        └── onTap → opens ToolSheet (existing SubPanelRow, 50% height)
```

Rules that make it CapCut-like:
1. **One row. Always.** No second reveal row, no horizontal scroll on a 5–6 chip toolbar. Chips shrink to fit; the group sheet is where abundance lives.
2. **Destructive actions are icon-only, right-aligned, and separated by a divider.** Delete is never a big chip adjacent to Split.
3. **Sub-tools live in a sheet, not a row.** A chip that groups (e.g. *Smart AI*) opens a sheet listing its members — this replaces Row 2 entirely.
4. **The toolbar animates on selection change** (cross-fade + 120 ms width spring) so the user *sees* that the tool set changed because they tapped something.
5. **Root is never empty and never scrolls** — a new user always sees exactly 5 things.

### 4.3 (a) ROOT — nothing selected

| # | Chip | Persian | Opens | Absorbs (existing `ToolPanel`s) |
|---|---|---|---|---|
| 1 | **Templates** | قالب‌های آماده فارسی | `SubtitleStudioPanel` template gallery, opened on the **Templates** tab | `SUBTITLE_STUDIO` (promoted to root) |
| 2 | **Subtitles & Text** | زیرنویس و متن | Sheet: AI Subtitles · Subtitle Studio · Add Text · Paragraph | `SUBTITLES`, `TEXT`, `PARAGRAPH` |
| 3 | **Audio / Music** | صدا و موزیک | Sheet: Add Music · Voice Clean · Auto Silence Cut | `AUDIO`, `AI_VOICE_CLEAN`, `AI_SILENCE_CUT` |
| 4 | **Canvas** | کادر و نسبت تصویر | Sheet: Aspect Ratio · Crop · Fill & Background · Transform | `ASPECT_RATIO`, `CROP`, `BACKGROUND`, `TRANSFORM` |
| 5 | **Overlay / PiP** | تصویر در تصویر | Sheet: Add Overlay · Animations · Opacity · Transitions | `OVERLAY`, `ANIMATIONS`, `OPACITY`, `TRANSITIONS` |

```
ROOT  ──  [ قالب‌ها ]  [ زیرنویس و متن ]  [ صدا و موزیک ]  [ کادر ]  [ تصویر در تصویر ]
```

**Every one of the 27 panels is now accounted for.** `ADJUST` and `CROP` — previously unreachable — become reachable inside *Canvas* and *Filters & Adjust*.

### 4.4 (b) CLIP_SELECTED — a video clip is tapped

| # | Chip | Persian | Behaviour |
|---|---|---|---|
| 1 | **Split** | برش | Action — `viewModel.splitClip(selectedClipId, playhead)` |
| 2 | **Speed** | سرعت | Sheet, 2 tabs: **Constant** (`SpeedPanel`) · **Curve** (`SpeedCurvePanel`) |
| 3 | **Smart AI** | ابزارهای هوشمند | Sheet, 5 items: **Motion Track** · **Auto Frame** · **Matting** · **Upscale** · **OCR** |
| 4 | **Volume & Audio** | صدا و ولوم | `VolumeSheet` (volume + mute) + fade in/out + **Voice Clean** + **Auto Silence Cut** |
| 5 | **Filters & Adjust** | فیلتر و تنظیم | Sheet, 4 tabs: **Filters** · **Adjust** · **Effects** · **Opacity** |
| 6 | **Trim & Motion** | برش و حرکت | `TrimPanel` + `AnimationPicker` |
| — | ⌦ **Duplicate** · 🗑 **Delete** | تکثیر · حذف | **Icon-only, right-aligned, behind a divider** |

```
CLIP  ──  [ برش ] [ سرعت ] [ هوشمند ] [ صدا ] [ فیلتر و تنظیم ] [ تریم و حرکت ]  │  ⧉  🗑
```

The user's requested *"Speed (simple + curve)"* is chip 2. *"Smart AI Tools (Motion Track, Auto Frame, Matting)"* is chip 3, extended with Upscale + OCR because they are clip-scoped too and otherwise have no home.

### 4.5 (c) AUDIO_SELECTED — an audio track is tapped

| # | Chip | Persian | Behaviour |
|---|---|---|---|
| 1 | **Beat Detection** | تشخیص بیت | `BeatPanel` — sensitivity, detect, auto-split on beats |
| 2 | **Volume & Fade** | صدا و محو | Volume + mute + fade in/out |
| 3 | **Split / Delete** | برش و حذف | Split at playhead; Delete as icon-only trailing |

```
AUDIO  ──  [ تشخیص بیت ] [ صدا و محو ] [ برش ]  │  🗑
```

*Note:* `duplicateClip` operates on **video clips only** (`EditorViewModel.kt:1996`), so **Duplicate is deliberately omitted** from the audio toolbar rather than shipped as a dead chip.

*Open question Q3:* Auto Silence Cut is an audio-domain operation but currently lives in the AI category. Proposal keeps it in **Root → Audio/Music**; alternative is to add it here as a 4th chip.

### 4.6 (d) TEXT_SELECTED — a text / subtitle overlay is tapped

| # | Chip | Persian | Behaviour |
|---|---|---|---|
| 1 | **Fonts & Typography** | فونت و تایپوگرافی | `PersianFont` picker · size · **کشیدگی (stretch)** · colour · stroke · shadow · background |
| 2 | **Animation & Styles** | انیمیشن و استایل | `TextOverlayAnimation` + `SubtitleStyle` presets + *Apply to all* |
| — | 🗑 **Delete** | حذف | Icon-only, right-aligned |

```
TEXT  ──  [ فونت و تایپوگرافی ] [ انیمیشن و استایل ]  │  🗑
```

Positioning (snap presets, nudge, rotation, *move to playhead* — currently in `ParagraphTextPanel`) and duration fold into these two sheets as extra tabs, so the toolbar stays at 2 chips. Both `TextOverlayPanel` and `ParagraphTextPanel` already expose every setter required.

### 4.7 Context transition matrix

| From → To | Trigger | Toolbar behaviour | Panel behaviour |
|---|---|---|---|
| ROOT → CLIP | tap a video clip | swap to CLIP chips (120 ms) | none — panel stays closed |
| ROOT → AUDIO | tap an audio clip | swap to AUDIO chips | **keep** current auto-open of Volume? → **Q1** |
| ROOT → TEXT | tap a text overlay | swap to TEXT chips | currently auto-opens `TEXT` → **Q2** |
| CLIP → ROOT | tap empty canvas / timeline background | swap to ROOT | close panel |
| any → any | tap a different object | swap directly | close panel, keep scroll position |
| CLIP → ROOT | Delete the selected clip | swap to ROOT | close panel |

### 4.8 Where the AI tools go (and the `locked` question)

| Tool | New home | `locked` today | Recommendation |
|---|---|---|---|
| Motion Track | CLIP → Smart AI | open | keep |
| Auto Frame | CLIP → Smart AI | 🔒 toast | **unlock** — `AutoFramePanel` + model-ready plumbing exist |
| Matting | CLIP → Smart AI | 🔒 toast | **unlock** (export-only per `MOTION_TRACKING_IMPLEMENTATION.md`; say so in the panel, not the dock) |
| Upscale | CLIP → Smart AI | 🔒 toast | **unlock** or remove from the chip list until shippable |
| OCR | CLIP → Smart AI | 🔒 toast | **unlock** — `OcrPanel` + `onApplyOcr` fully wired |
| Voice Clean | CLIP → Volume & Audio · ROOT → Audio | open | keep |
| Auto Silence Cut | ROOT → Audio/Music | open | keep |
| Beats | AUDIO toolbar | open | keep |

If any must stay locked, put the lock **inside** the Smart AI sheet as a "به‌زودی" badge on the row — **not** as a chip that eats a tap and shows a toast. A 5-item sheet can carry 1–2 unavailable items gracefully; a dock row cannot.

---

## 5. Required Supporting Changes

### 5.1 Context resolution (new, pure, testable)
`model/EditorContext.kt` — `enum class EditorContext` + `resolveEditorContext(...)`. Pure top-level function in `model/`, per the project's "no mocking libraries" convention (test stack is JUnit4 + coroutines-test + `org.json` only; Hilt `EditorViewModel` with 13 ctor deps **cannot** be built in a JVM test). This is directly unit-testable.

### 5.2 Fix the selection invariant *(prerequisite — do this first)*
`SubtitleDelegate.selectTextOverlay` and `selectTextOverlayWithPanel` must null out `selectedClipId` + `selectedAudioClipId`, matching `selectClip` / `selectAudioClip`. Without this, `resolveEditorContext` is ambiguous and P6 becomes a visible bug.

```kotlin
fun selectTextOverlay(id: String?) {
    state.update {
        it.copy(
            selectedTextId = id,
            selectedClipId = if (id != null) null else it.selectedClipId,
            selectedAudioClipId = if (id != null) null else it.selectedAudioClipId
        )
    }
}
```

### 5.3 Unify the track-reveal rule (P7)
Delete the inline `when (enum)` in `EditorScreen.kt:1584-1596` and keep **one** implementation in `EditorViewModel.revealTracksForTool`, extended to cover `EFFECTS` / `TRANSITIONS` and to reveal both audio lanes for `AUDIO`.

### 5.4 Kill the duplicated entry points (P5)
- Remove Split + Aspect from `TransportRow` (`EditorPlaybackStrip.kt:196-206`) — keep ⏪ ▶ ⏩ and Fullscreen. Split belongs to the clip context; Aspect to Canvas.
- Remove the duplicate Split/Duplicate/Delete from the ویرایش category (they exist as the clip toolbar's trailing icons).
- `VolumeSheet` **moves** out of `BottomToolbar.kt` into its own file (or into `ToolbarSubPanels.kt`) before that file is deleted.

### 5.5 Delete dead code (P3)
- Delete `EditorBottomDock.kt` (153 lines) and `BottomToolbar.kt` (265 lines, after extracting `VolumeSheet`).
- Delete/rewrite the stale guidance in `LAYOUT_NOTES.md:34`.

---

## 6. Current vs. Proposed — Side-by-Side

| Dimension | Current | Proposed |
|---|---|---|
| Toolbar states | **1** (fixed palette) | **4** (ROOT / CLIP / AUDIO / TEXT) |
| Root row chips | 12 (scrolls; Split/Delete off-screen) | **5** (never scrolls) |
| Rows | 1 + conditional 2nd row | **1** always |
| Selection-aware? | ❌ never | ✅ derives from `selectedClipId`/`selectedAudioClipId`/`selectedTextId` |
| Distinct `ToolPanel`s reachable | 25 / 27 | **27 / 27** |
| Orphaned panels | 2 (`ADJUST`, `CROP`) | **0** |
| Dead-end chips | 4 (`locked`) | **0** (locked items become sheet rows with a badge) |
| Duplicated entry points | 3 tools (Split, Duplicate, Delete) | **0** |
| Delete affordance | Big chip, mid-row, scrolls off | **Icon-only, right, divider-separated** |
| Discovery for a new user | Must learn 5 categories + 12 extras | **5 root chips, then context takes over** |
| `EditorScreen.kt` dock code | ~470 lines (3436-3900) | ~180 lines (spec-driven) |

### 6.1 Migration map — every current chip, its destination

| Current location | Current chip | New home |
|---|---|---|
| ویرایش | تریم | CLIP → *Trim & Motion* |
| ویرایش | برش | **CLIP → Split** (chip 1) |
| ویرایش | سرعت | **CLIP → Speed** (chip 2) |
| ویرایش | تکثیر | CLIP → trailing ⧉ |
| ویرایش | حذف | CLIP → trailing 🗑 |
| صدا | صدا | CLIP → *Volume & Audio* · AUDIO → *Volume & Fade* · ROOT → *Audio/Music* |
| صدا | فایل صوتی | ROOT → *Audio/Music* → Add Music |
| صدا | پاکسازی صدا | CLIP → *Volume & Audio* · ROOT → *Audio/Music* |
| تنظیم رنگ | فیلترها | CLIP → *Filters & Adjust* |
| تنظیم رنگ | افکت | CLIP → *Filters & Adjust* |
| تنظیم رنگ | شفافیت | CLIP → *Filters & Adjust* · ROOT → *Overlay/PiP* |
| تنظیم رنگ | کراپ (`TRANSFORM`) | ROOT → *Canvas* → Transform |
| تنظیم رنگ | پس‌زمینه | ROOT → *Canvas* → Fill & Background |
| تنظیم رنگ | اورلی | **ROOT → *Overlay/PiP*** (chip 5) |
| متن | متن | ROOT → *Subtitles & Text* → Add Text |
| متن | پاراگراف | ROOT → *Subtitles & Text* → Paragraph |
| متن | زیرنویس AI | ROOT → *Subtitles & Text* → AI Subtitles |
| متن | زیرنویس فارسی | **ROOT → *Templates*** (chip 1) |
| هوش مصنوعی | افزایش کیفیت 🔒 | CLIP → *Smart AI* |
| هوش مصنوعی | حذف پس‌زمینه 🔒 | CLIP → *Smart AI* |
| هوش مصنوعی | اتوفریم 🔒 | CLIP → *Smart AI* |
| هوش مصنوعی | ردیابی حرکت | CLIP → *Smart AI* |
| هوش مصنوعی | متن‌یابی 🔒 | CLIP → *Smart AI* |
| هوش مصنوعی | برش خودکار سکوت | ROOT → *Audio/Music* |
| هوش مصنوعی | ضرباهنگ | **AUDIO → Beat Detection** (chip 1) |
| extras | نسبت | ROOT → *Canvas* → Aspect Ratio |
| extras | ترنزیشن | ROOT → *Overlay/PiP* → Transitions (+ timeline handle stays) |
| extras | انیمیشن | ROOT → *Overlay/PiP* → Animations · CLIP → *Trim & Motion* |
| extras | برش / تکثیر / حذف | CLIP trailing icons |
| *(unreachable)* | `ADJUST` | CLIP → *Filters & Adjust* → **Adjust** tab |
| *(unreachable)* | `CROP` | ROOT → *Canvas* → **Crop** tab |

---

## 7. Files & Composables To Modify

### 7.1 New files

| File | Contents | Risk |
|---|---|---|
| `model/EditorContext.kt` | `enum class EditorContext` + `fun resolveEditorContext(...)` | **None** — pure Kotlin |
| `ui/screens/editor/ContextualToolbarSpec.kt` | Declarative spec: `EditorContext → List<ContextChip>`; `ContextChip` = `Action(id)` \| `Panel(ToolPanel)` \| `Group(label, List<ContextChip>)` | **None** — pure data |
| `ui/screens/editor/ContextualToolbar.kt` | `ContextualToolbar(context, selectedPanel, onChip, onAction)` + `ToolGroupSheet(...)` | Low — new composable |
| `ui/screens/editor/VolumeSheet.kt` | `VolumeSheet` **moved verbatim** out of `BottomToolbar.kt` | **None** — move only |
| `test/.../EditorContextTest.kt` | 4 context cases + priority ordering + null permutations | **None** |

### 7.2 Modified files

| File | Target | Change |
|---|---|---|
| `ui/screens/editor/EditorScreen.kt` | `EditorBottomDockSection` (**:3436**) | Read selection; resolve context; render `ContextualToolbar` instead of two `LazyRow`s |
| | `DockCategoryRow` (**:3602**) | **Delete** — replaced |
| | `DockCategoryToolRow` (**:3657**) | **Delete** — replaced by `ToolGroupSheet` |
| | `DockChip` (**:3704**) | Retain as the chip primitive; drop the `locked` toast branch |
| | `DockTool` (**:3693**) / `DockCategory` (**:3502**) / `DockCategories` (**:3509**) | **Delete** — replaced by `ContextualToolbarSpec` |
| | `DockCategory.tools()` (**:3551**) / `categoryTools()` (**:3593**) | **Delete** |
| | `onPanelSelected` callback (**:1576-1615**) | Remove inline `when (enum)` (→ §5.3); extend `onAction` with audio-split |
| | `SubPanelContent` (**:4130**) | Drop `else` arms of `TRIM`/`SPEED`/`ADJUST`/`EFFECTS` (unreachable); **collapse `ADJUST` into `EFFECTS`** (P2 duplicate) |
| | `ToolPanel.title()` (**:4995**) | Add sheet-group titles |
| | `EditorBottomDockSection` call site (**:1576**) | Pass the 3 selection ids + `currentPositionMs` |
| `ui/screens/editor/EditorPlaybackStrip.kt` | — | **Delete the whole file** — it is entirely dead (see the P5 correction). No Split/Aspect removal is needed because the live strip never had them. |
| `ui/screens/editor/EditorLayoutSpec.kt` | `BottomDockHeight = 80.dp` | Re-tune for a single row (~64 dp); add `ToolSheetHeightFraction` |
| `model/EditorUiState.kt` | `ToolPanel` (**:450**) | Keep all 27 (no enum churn — avoids the string/enum-keyed `when` trap) |
| `viewmodel/SubtitleDelegate.kt` | `selectTextOverlay` (**:162**), `selectTextOverlayWithPanel` (**:166**) | **Fix exclusivity** (§5.2) |
| `viewmodel/EditorViewModel.kt` | `setToolPanel` (**:638**) | Unchanged signature |
| | `revealTracksForTool` (**:678**) | Become the **single** source of truth (§5.3) |
| | `selectAudioClip` (**:1588**) | Reconsider auto-`VOLUME` (**Q1**) |
| `viewmodel/TimelineDelegate.kt` | `selectClip` (**:28**), `selectAudioClip` (**:38**) | No change — already correct |

### 7.3 Deleted files
| File | Lines | Condition |
|---|---|---|
| `ui/screens/editor/EditorBottomDock.kt` | 153 | No external references (verified) |
| `ui/screens/editor/BottomToolbar.kt` | 265 | After `VolumeSheet` is extracted → `VolumeSheet.kt` |
| `ui/screens/editor/EditorPlaybackStrip.kt` | 347 | No external references (verified — see the P5 correction) |
| `ui/screens/editor/LAYOUT_NOTES.md` | 40 | Stale; instructs editing the dead dock |

### 7.4 Untouched
All 27 panel bodies, `EditorTimeline.kt`, `EditorTopBar.kt`, the whole `export/` + `ai/` layers, `AndroidManifest`, Gradle. **No persisted state, DB, or JSON schema changes** — this is presentation + two selection-mutation fixes.

---

## 8. Rollout Plan

| Phase | Work | Verifiable by |
|---|---|---|
| **0 — Safety net** | Add `EditorContextTest`; fix selection invariant (§5.2); unify `revealTracksForTool` (§5.3) | New JVM test; `testDebugUnitTest` stays at baseline `1671 completed, 2 failed, 2 skipped` (the 2 are pre-existing asset-dependent: `RealModelInstallTest`, `WhisperNativeSymbolsTest`) |
| **1 — Spec, no UI change** | Add `EditorContext.kt` + `ContextualToolbarSpec.kt`; render the **existing** dock from the spec with `context = ROOT` hard-coded | Diff must be a visual no-op → proves the spec is lossless |
| **2 — Contextual switch** | Wire `resolveEditorContext`; swap chip sets on selection; add the transition animation | Manual: tap clip/audio/text, watch the toolbar swap |
| **3 — Group sheets** | Replace Row 2 with `ToolGroupSheet`; surface the 2 orphans (`ADJUST`, `CROP`) | All 27 panels reachable — checklist per §2.3 |
| **4 — Cleanup** | Remove playback-strip duplicates; delete dead files + `LAYOUT_NOTES.md`; drop unreachable `else` arms | `grep` for deleted symbols returns nothing; build + test green |
| **5 — Polish** | Chip spacing, RTL audit (Persian labels), lock badges inside sheets, empty-state copy | Screenshot pass on 360 dp + 412 dp |

**Build note:** `./gradlew` is broken under Git Bash (classpath separator). Use the pinned JDK 21 + Gradle 8.13 launcher directly; the suite is ~1671 tests, ~65 s cold → background it. `mergeDebugAssets` only runs on `assemble`, so a unit-test-only loop cannot catch a missing asset.

---

## 9. Open Questions — need your decision before Phase 2

| # | Question | Options | My recommendation |
|---|---|---|---|
| **Q1** | Should tapping an **audio clip** still auto-open the Volume panel, or only swap the toolbar? | (a) keep auto-open (b) toolbar-only | **(b)** — toolbar-only. Auto-open hides the preview and pre-empts the user; the toolbar already puts Volume one tap away. |
| **Q2** | Same for tapping a **text overlay** (currently auto-opens `TEXT`)? | (a) keep (b) toolbar-only | **(b)** toolbar-only, for the same reason. |
| **Q3** | Auto Silence Cut: Root → Audio/Music, or AUDIO-selected as a 4th chip? | (a) Root (b) Audio-selected | **(a)** Root — it operates on a whole file, not a selected region. |
| **Q4** | The 4 `locked` AI tools — unlock, or keep gated? | (a) unlock all (b) unlock ready ones, badge the rest (c) keep as-is | **(b)** — `AutoFramePanel`, `AiMattingPanel`, `OcrPanel` are fully wired; a `به‌زودی` **badge inside the Smart AI sheet** is honest without burning a dock tap. |
| **Q5** | Does **Templates** mean Persian *subtitle* templates only (the 20 built-ins), or should it grow into full project templates? | (a) subtitle templates (b) project templates later | **(a)** now, with the chip label/route designed to absorb (b) without moving. |
| **Q6** | `TRIM` — keep a precision panel, or rely on timeline drag handles only? | (a) keep as *Trim & Motion* (b) drop from toolbar, long-press only | **(a)** keep — drag handles are already primary, the panel is the precision fallback. |
| **Q7** | Should the **Transitions** chip appear in CLIP context only when the clip has an adjacent neighbour? | (a) always in Root → Overlay (b) also conditionally in CLIP | **(b)** — a transition needs a neighbour; showing it otherwise is a dead tap. |

---

## 10. Appendix — Evidence Index

| Claim | Evidence |
|---|---|
| Live dock is `EditorBottomDockSection` | `EditorScreen.kt:1576` (only call site) |
| `EditorBottomDock` / `BottomToolbar` are dead | grep `EditorBottomDock(`, `BottomToolbar(`, `EditorDockItems`, `BottomToolbarItems` → self-references only + `LAYOUT_NOTES.md:34` |
| `VolumeSheet` is live but parked in a dead file | `BottomToolbar.kt:134` definition; `EditorScreen.kt:4340, 4352` usage |
| `ADJUST` unreachable | No `DockTool` references `ToolPanel.ADJUST`; only `:1104`, `:4539`, `:5004` |
| `ADJUST` duplicates `EFFECTS` | `EditorScreen.kt:4542-4564` ≡ `:4577-4599` |
| `CROP` unreachable | No `DockTool` references `ToolPanel.CROP`; only `:4613`, `:5006` |
| 4 locked tools | `EditorScreen.kt:3581, 3582, 3583, 3585` (`locked = true`) |
| Toast on locked tap | `EditorScreen.kt:3714-3715` (`"بزودی..."`) |
| Split ×2 | `:3544`, `:3554` |
| Aspect ×1 | `:3541` |
| `EditorPlaybackStrip.kt` is dead | grep: `EditorPlaybackStrip`, `ScrubberRow`, `TransportRow`, `BigPausePlayButton`, `TransportIconButton`, `TimecodeText`, `IconAction` → 0 external references (see the P5 correction) |
| Text selection breaks exclusivity | `SubtitleDelegate.kt:162-173` vs `TimelineDelegate.kt:28-48` |
| Track-reveal implemented twice & disagrees | `EditorViewModel.kt:678-699` vs `EditorScreen.kt:1584-1596` |
| Audio tap auto-opens Volume | `EditorViewModel.kt:1612` |
| Text tap auto-opens TEXT panel | `SubtitleDelegate.kt:170` |
| Root row = 12 chips | `:3510-3547` (5 categories + 6 extras + divider) |
| 27 `ToolPanel` values | `EditorUiState.kt:450-501` |
| 27 `SubPanelContent` branches | `EditorScreen.kt:4280-4865` |
| Empty-state text in 4 panels | `:4294` (TRIM), `:4566` (ADJUST), + SPEED/EFFECTS |
| Test baseline | `1671 completed, 2 failed, 2 skipped` (2026-09-28) |

---

## 11. Implementation Record — 2026-09-28

All seven Open Questions were answered (Q1b, Q2b, Q3a, Q4b, Q5a, Q6a, Q7b) and
the plan was approved for autonomous execution Phase 0 → 5. What actually
shipped, and every place it deviates from the proposal above.

### 11.1 What shipped, per phase

| Phase | Shipped | Notes |
|---|---|---|
| **0 — Safety net** | `model/ToolTrackReveal.kt` (`tracksForTool`); `SubtitleDelegate.selectTextOverlay` clears the other selections; `EditorViewModel.revealTracksForTool` now delegates to the one source of truth | New tests: `EditorContextTest` (18), `ToolTrackRevealTest` (10), `SelectionExclusivityTest` (7) |
| **1 — Spec, no UI change** | `model/EditorContext.kt`, `ui/screens/editor/ContextualToolbarSpec.kt` | Pure data + pure function → fully JVM-testable, per the "no Hilt in unit tests" constraint |
| **2 — Contextual switch** | `ContextualToolbar` composable; `EditorBottomDockSection` re-signatured; `Crossfade(tween(180))` on context | `LaunchedEffect(context)` closes the group sheet so it can't outlive its chips |
| **3 — Group sheets** | `ToolGroupSheet` + `SheetMemberRow`; `ADJUST` and `CROP` restored; the duplicate `ADJUST` branch collapsed into `ADJUST, EFFECTS ->` | `reachableToolPanels()` now covers all 27 |
| **4 — Cleanup** | Deleted `EditorBottomDock.kt` (153), `BottomToolbar.kt` (265), `EditorPlaybackStrip.kt` (347), `LAYOUT_NOTES.md`; extracted `VolumeSheet.kt`; **removed the duplicate Split button from the live transport row** (§5.4); removed the 3 now-dead tokens `EditorLayoutSpec.PlaybackStripHeight` / `.BottomDockHeight` / `.SCRUB_SCRUBBER_HEIGHT`; dropped the dead `iOSToolbarItem` / `Splitscreen` imports | `EditorScreen.kt` 5033 → 4802 lines |
| **5 — Polish** | Adaptive chip icon tile; label `overflow` Visible → Ellipsis; chip gap 6dp → 4dp; sheet empty-state; "به‌زودی" badge for the one not-ready tool | See §11.3 |

### 11.2 Deviations from the approved proposal

1. **The audio toolbar ships 2 chips, not 4.** The proposal asked for *Beat
   Detection / Volume & Fade / Split & Delete*. Auditing the code showed:
   `splitClip`/`deleteClip` operate on `project.clips` (`VideoClip`) only, so
   there is no audio-object split or delete to call; and `AudioClip.fadeInMs`/
   `fadeOutMs` exist on the model and are honoured by the exporter
   (`RenderGraphBuilder` → `RenderSegment` → `AudioFade`), but **nothing writes
   them** and `project.audioClips` never reaches `RenderGraphBuilder` — only
   `VideoClip.toRenderSegment` exists. A fade control would therefore be
   write-only. Shipped as `بیت` + `صدا` instead of two more chips that eat a
   tap. Closing this needs model + export work and is a follow-up, not faked.

2. **`EditorPlaybackStrip.kt` was a fourth dead file** (347 lines), found
   during Phase 4. This also corrected the P5 finding: the transport row's
   Split/Aspect buttons lived in that dead file, so deleting it looked like it
   had settled §5.4 on its own.

   **It had not.** A *live* `EditorPlaybackStripSection` still exists inside
   `EditorScreen.kt`, and it carried its own Split button whose handler was
   byte-identical to the new `clip_split` chip
   (`uiState.selectedClipId ?: activeClip?.id` → `splitClip(id, currentPositionMs)`).
   Deleting the dead file therefore left the real duplicate in place — the same
   action rendered twice whenever a clip was selected. The button, its
   `onSplit` parameter and the call-site lambda were removed, leaving the row
   with ⏪ ▶ ⏩, the scrubber and the timecodes. Split now has exactly one home,
   the CLIP context, where it has a target by definition (§5.4).

   *Lesson:* "the file is dead" and "the feature is gone" are different claims.
   Grep for the **behaviour**, not the symbol you just deleted.

3. **`VolumeSheet.kt:134`** carried a pre-existing deprecation
   (`Icons.Filled.VolumeOff`) through the file move; fixed to the AutoMirrored
   variant so the move introduced no new warning.

### 11.3 Phase 5 — the layout finding that mattered

The audit's Phase 5 verification was "screenshot pass on 360 dp + 412 dp".
There is no emulator in this environment, so the check was done by computing
the row's width budget instead:

- `RowScope.weight(1f)` (with the default `fill = true`) gives each chip
  **exactly** its share of the remaining width — it cannot grow past it.
- Worst case is CLIP with a neighbour: 7 chips + divider + 2×38dp icons.
  On a 360dp phone that is `360 − 20 (gutter) − 76 (icons) − 5 (divider) −
  48 (8 gaps × 6dp) = 211dp ÷ 7 ≈ 30dp` per chip.
- The icon tile inside `iOSToolbarItem` was a **fixed 36dp**, so it overflowed
  every chip by ~6dp and collided with its neighbour — on the most-used
  context, on a common screen size.

Fixes, all in `iOSButton.kt` / `ContextualToolbar.kt`:

- `iOSToolbarItem` now measures its own width with `BoxWithConstraints` and
  derives `tileSize = (maxWidth − 6dp).coerceIn(22dp, 36dp)` and a
  proportional glyph size, so a chip can never render content wider than
  itself. Unbounded width still resolves to the original 36dp tile.
- The chip label was `overflow = TextOverflow.Visible`, which is precisely the
  "spill into the neighbour" behaviour; now `Ellipsis`, centred.
- Chip gap 6dp → 4dp returns ~2dp per chip in the worst case; the chips carry
  their own rounded surfaces so the separation still reads.
- The group sheet got an empty-state line, so a future spec edit that empties
  a group cannot silently open a blank sheet.

**RTL result — no change needed, and that is the finding.** The app forces
`LayoutDirection.Ltr` for its chrome in `NavGraph.kt:93-94`; the only RTL
boundary is `EditorScreen.kt:3179-3181`, which wraps a *single text overlay's*
content when that overlay is flagged RTL. The toolbar therefore inherits LTR
chrome by design, which is correct: Persian labels render RTL inside their own
box while the chrome (icon-left, destructive-cluster-at-the-end) stays
predictable. The Persian strings themselves are centred, and the sheet uses
`maxLines = 2` so diacritics and descenders are not clipped.

### 11.4 Verification

- `compileDebugKotlin` — BUILD SUCCESSFUL, **no warnings**.
- `assembleDebug` — **BUILD SUCCESSFUL**, `app/build/outputs/apk/debug/app-debug.apk` produced.
- `testDebugUnitTest` — **1733 completed, 2 failed, 2 skipped**. The 2 failures
  are the documented pre-existing, asset-dependent pair (`RealModelInstallTest`,
  `WhisperNativeSymbolsTest`) and are unrelated to this work. Baseline before
  the change was `1671 completed, 2 failed, 2 skipped` → **+62 tests, 0
  regressions.**
- New test classes, all green: `EditorContextTest` 18, `ToolTrackRevealTest` 10,
  `SelectionExclusivityTest` 7, `ContextualToolbarSpecTest` 27.
- `grep` for every deleted symbol (`EditorBottomDock`, `BottomToolbar`,
  `EditorPlaybackStrip`, `DockCategory`, `selectTextOverlayWithPanel`,
  `onSplit`, `Splitscreen`, `BottomDockHeight`, `SCRUB_SCRUBBER_HEIGHT`) in
  `app/src` returns only the intentional "removed here" comments.
