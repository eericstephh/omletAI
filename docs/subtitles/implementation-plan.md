# Implementation Plan — Subtitle Template System

**Date:** 2026-10-16
**Status:** implemented (vertical slice + taxonomy); catalog expansion pending approval
**Companion documents:** [`reference-analysis.md`](reference-analysis.md),
[`../persian-subtitle-template-research.md`](../persian-subtitle-template-research.md)

---

## 1. Goal and acceptance criteria

**Goal.** A professional, Persian-first subtitle-template system for Omelet:
a data-driven catalog of ≥20 original presets spanning the brief's eight
categories, browsable and selectable from the existing subtitle editor, with
word-level highlighting and animation identical in preview and export.

**Acceptance criteria**

| # | Criterion | Status |
| --- | --- | --- |
| A1 | ≥20 distinct presets across the eight requested categories, browsable | **Met** — 25 presets (see §4.2) |
| A2 | The eight categories are each an on-screen section | **Met** (§4.3) |
| A3 | Templates are described by a typed, validated data model, not per-template UI code | **Already met** — `SubtitleTemplate` + 6/7-axis JSON model with `require` invariants |
| A4 | Word-level highlighting animates from real recognizer timings | **Already met** — `TranscribedWord` + `WordMotionEngine` |
| A5 | No fabricated word timings | **Already met** — segment-level fallback only (§6) |
| A6 | Preview and export produce identical captions | **Already met** — single `CaptionRenderer` (§5) |
| A7 | Persian shaping, RTL, bundled fonts only | **Already met** (§7) |
| A8 | Automated tests pass; build succeeds | See the verification section of the final report |
| A9 | No paid API, no login, no new network, no cloud rendering, no added model assets | **Met** — the change adds 2 Kotlin files, a test file, and small edits to the mapper and the picker; the guard test in §9 keeps it that way |
| A10 | Every served preset respects the catalog's readability cap | **Met** after fixing D1 (§10.2), with a catalog-wide regression test |

---

## 2. Architecture map (Phase 1 audit result)

```
 capture / import
        │
        ▼
 audio extraction ──────────────► ai/WhisperEngine            (on-device, libwhisper.so)
        │                              │  TranscribedWord {text,startTimeMs,endTimeMs,confidence}
        ▼                              ▼
 ai/subtitle/SubtitleSegmenter ─► Subtitle { words: List<TranscribedWord> }
        │                              │
        ▼                              ▼
 model/TextOverlay (subtitleSource = "ai" | "srt")
        │  subtitleStyle: SubtitleStyle        ← font, size, colors, stroke, shadow, bg, align, position
        │  motionKind / backgroundKind / words  ← per-word motion + timing
        ▼
 ┌──────────────┴───────────────┐
 ▼                              ▼
 PREVIEW                        EXPORT
 ui/…/CaptionBitmap.kt          export/SubtitleBurnIn.kt
        └────────► export/CaptionRenderer.render ◄────────┘
                    (single rasterizer → parity)
        ▲
        │
 ui/…/subtitle/SubtitleStudioPanel.kt   ← the picker
        │
        ▼
 model/template/  JsonTemplate → TemplateOverrides(7 axes) → ResolvedTemplate
                  → JsonTemplateMapper → SubtitleTemplate
        assets/subtitle_templates.json  (schema 3.0, 25 templates)
```

**Key facts established by the audit**

- The picker serves the **JSON catalog** (`uiState.jsonCatalogTemplates`,
  `EditorViewModel.kt:3949–3956`), not the hand-built list.
- `SubtitleTemplate` is the typed, validated model: it enforces
  `activeWordScale ∈ 0.8f..2.5f` and `animationDurationMs ∈ 60..1500` in its
  `init` block, so invalid presets fail fast rather than at render time.
- Editing is per-axis and non-destructive: `TemplateOverrides` holds only what
  the user changed; the immutable base is never mutated.
- Preview and export share `CaptionRenderer`, so parity is structural.

---

## 3. Requirement → status matrix (brief phases vs. what already exists)

| Brief phase | Requirement | Finding |
| --- | --- | --- |
| 4 | Original catalog, ≥20 presets | **25 already ship** (19 + a 6-preset set added 2026-10-15) |
| 4 | The eight specific categories | **Missing** — 5 different keys exist |
| 4 | Template-selection interface: category browsing, thumbnails, selected state, live preview | **Already exists** in `SubtitleStudioPanel.kt` (2-column grid, real static Persian preview per card, selected ring, tap-to-apply) |
| 5 | Word-level timestamps + animation | **Already exists** (real recognizer timings; 11 motion kinds) |
| 5 | Reuse word timings; never fake | **Already exists** and documented |
| 6 | Persian correctness, RTL | **Already exists** (research doc + tests) |
| 7 | Export integration | **Already exists** (`SubtitleBurnIn` → same renderer) |
| 8 | Tests | **Already extensive** (parser, mapper, parity, offline guard, RTL) |

**Conclusion.** The only genuine gap against the brief was the **eight-category
taxonomy**. That is exactly what was implemented. Everything else was verified
as already present, and nothing was rewritten to manufacture work.

---

## 4. The eight-category browse taxonomy (the change)

### 4.1 Design

Two new pure-Kotlin files, no Android, no I/O:

- `model/template/SubtitleTemplateCategory.kt`
  - `enum class SubtitleTemplateCategory(key, displayNameFa, displayNameEn)` —
    the eight brief categories, in brief order.
  - `object SubtitleTemplateTaxonomy`
    - `classify(template): SubtitleTemplateCategory` — total, deterministic,
      never throws.
    - `group(templates)` — declaration-ordered, empty buckets skipped.
    - `coverage(templates)` — category → count.
- The picker (`SubtitleStudioPanel.kt`) now groups by
  `SubtitleTemplateTaxonomy.group(...)` and renders
  `category.displayNameFa`. **Two lines changed; no template, id, style or
  renderer touched.**

### 4.2 Classification rule

1. **Curated table** for every id shipped today (25 JSON + 26 built-in).
   Curated because visual intent is not always recoverable from resolved
   properties — two presets can share `colorChange` yet target different
   viewers.
2. **Property fallback** for anything else (a future entry, or a user-saved
   template), ordered by specificity:

   | Signal | Category |
   | --- | --- |
   | rounded/pill surface (`SOLID_ROUNDED`, `PER_LINE_HIGHLIGHT`) or outlined text (stroke on, no background) | ROUNDED_OUTLINED |
   | `WordRevealMode.KARAOKE`/`WORD_BY_WORD`, `KARAOKE_FILL`, `TYPEWRITER` | KARAOKE_WORD_BY_WORD |
   | `BOUNCE_POP`, `GLITCH`, `SLIDE_UP_WORD`, `FADE_SCALE`, `RISE_BOUNCE`, `SHAKE_WIGGLE` | VIRAL_BOLD |
   | `NEON_GLOW`, `HIGHLIGHT_COLOR` | KEYWORD_EMPHASIS |
   | otherwise | CLEAN_PERSIAN (safest readable default) |

   A user template is therefore never dropped into an "unknown" hole.

### 4.3 Coverage of the served catalog (25 presets)

| # | Category (fa) | Presets |
| --- | --- | --- |
| 1 | وایرال و درشت | 7 |
| 2 | کارائوکه کلمه‌به‌کلمه | 4 |
| 3 | پادکست و مصاحبه | 3 |
| 4 | مینیمال و سینمایی | 4 |
| 5 | آموزشی و توضیحی | 1 |
| 6 | تأکید بر کلیدواژه | 3 |
| 7 | فارسی تمیز و خوانا | 2 |
| 8 | پس‌زمینه گرد و حاشیه‌دار | 1 |

All eight are non-empty (**A1, A2 met**), and the shape of the gap is now
visible: categories 5 and 8 are thin, and the 26 hand-built designs have **no**
educational preset at all (asserted in CI so it cannot be forgotten).

### 4.4 Why derive instead of storing a new JSON key

- Storing a new `category` value would invalidate the grouping of already-saved
  projects and break `JsonTemplateMapperTest`, which pins the five existing
  keys and their labels.
- Deriving is additive, reversible (delete two files), and testable on the JVM
  without Android.
- Trade-off, stated honestly: a derived taxonomy is a *presentation* layer. The
  underlying presets are still authored against the original five keys, so this
  does not by itself add a *dedicated* podcast or educational design.

---

## 5. Preview ↔ export parity strategy

Parity is **already structural**: `CaptionBitmap.kt` (preview) and
`SubtitleBurnIn.kt` (export) both call `CaptionRenderer.render`. The plan is to
preserve that:

1. The taxonomy is display-only and never reaches the renderer.
2. Any future preset must be expressed through `SubtitleTemplate` fields the
   renderer already honours — never via a second drawing path.
3. `SubtitleTemplateExportParityTest` and `CaptionRendererParityTest` remain the
   enforcement point.

---

## 6. Word-timing strategy and its limits

- **Source of truth:** `TranscribedWord.startTimeMs/endTimeMs` from the on-device
  recognizer (C5 in the reference analysis). Reused unchanged; no parallel
  timing type.
- **Animation:** `WordMotionEngine` consumes those timings for the active word;
  RTL karaoke fills right-to-left (`WordMotionKind.kt:14–15`).
- **When word timings are absent:** the caption falls back to **segment level**
  (`ALL_AT_ONCE` / whole-line reveal). Equal division of a segment across its
  words would be a fabrication and is **not** done.
- **Limit (explicitly accepted):** word-by-word quality depends on the
  recognizer's word timings. Under poor audio, the recognizer may return
  segment-level output only, in which case even a karaoke template degrades to
  a whole-line reveal. This is honest degradation, not a bug.

---

## 7. Persian / RTL strategy

Unchanged from the repository's researched position:

- Bundled OFL Persian families only (45 `res/font/` members); `FontFallback`
  resolves any unbundled name to a bundled family so Persian never falls back to
  a system face with broken joining.
- Direction-aware progress fill (right-to-left for Persian, left-to-right for
  Latin).
- No manual string reversal anywhere in the caption path.
- Positions are normalized against the **video canvas**, not the screen, so a
  template looks the same in preview and export at every aspect ratio.

---

## 8. Performance and memory

- The change adds one enum lookup per template per recomposition of the picker —
  negligible, and it happens once per catalog load in practice.
- No new bitmaps, allocations or native code.
- No model weights, no assets, no APK-size increase beyond ~10 KB of Kotlin.
- The existing preview already recycles per-frame caption bitmaps; that design is
  untouched.

---

## 9. Test plan and acceptance criteria

**New tests** (`SubtitleTemplateTaxonomyTest`, 8 cases):

1. all eight categories exist with unique keys and non-blank fa/en labels;
2. the served JSON catalog fills all eight categories with ≥20 presets;
3. the 26 hand-built designs are classified totally (with the documented
   educational gap asserted explicitly);
4. curated JSON ids map to the intended categories;
5. curated hand-built ids map to the intended categories;
6. unknown ids fall back on properties and never throw (5 cases);
7. grouping follows declaration order and skips empty buckets;
8. classification is deterministic and partitions the catalog (nothing lost or
   duplicated).

**Regression guards (must stay green, unchanged)**

- `JsonTemplateParserTest`, `JsonTemplateMapperTest`, `TemplateCatalogCacheTest`,
  `TemplateOverridesTest` — the catalog and override contracts.
- `SubtitleTemplateExportParityTest`, `CaptionRendererParityTest`,
  `CaptionRendererRtlTest` — parity, RTL, the 1.12 readability cap.
- `OfflineSubtitleTemplateGuardTest` — the template layer stays offline.
- `ProSubtitleTemplatesTest`, `SubtitleTemplateTest`,
  `BuiltInSubtitleTemplatesFaFontTest` — the hand-built catalog.

**Commands**

```
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

**Acceptance:** no new failures beyond the known baseline
(`RealModelInstallTest`, asset-dependent and unrelated), and a successful
`assembleDebug`.

---

## 10. Risks, rollback, and known defects

### 10.1 Risks of this change

| Risk | Mitigation |
| --- | --- |
| Picker grouping changes (5 sections → 8) | Display-only; ids, styles and output unchanged; revert is deleting two files and restoring two lines |
| A future catalog entry lands in an odd bucket | Property fallback is total and deterministic; the "otherwise" branch is the safest readable category |
| A rounded/outlined user template mis-buckets | Documented rule order; `ROUNDED_OUTLINED` is checked before motion |

### 10.2 Defects

**D1 — active-word scale exceeded the 1.12 readability cap — FIXED (2026-10-16).**
18 of 25 served templates exceeded the cap the repo's own research sets
(`docs/persian-subtitle-template-research.md:229,297`): sixteen at `1.15` and
`keyword_punch_zoom` at `1.4`, because `JsonTemplateMapper` defaulted
highlight-active templates to `1.15` and passed `keywordScaleBoost` straight
through. **Fix:** clamp at the mapper boundary to `coerceIn(0.8f, 1.12f)` — the
same cap `SubtitleTemplateExportParityTest` already enforces for the hand-built
catalog — so a future catalog entry cannot drift either. The two assertions in
`JsonTemplateMapperTest` that pinned the old values (`1.4`, `2.5`) were updated,
and a new test asserts every served template now respects the cap.

*Known trade-off (explicit product decision):* the active-word bump on those 18
templates is now smaller — `1.15 → 1.12`, and `keyword_punch_zoom` `1.4 → 1.12`.

**D2 — `EditorViewModel.kt` contains mojibake** (replacement characters) in
string literals. Pre-existing, unrelated to templates, still **not** fixed and
out of scope for a focused diff.

### 10.3 Rollback

Delete `model/template/SubtitleTemplateCategory.kt` and
`SubtitleTemplateTaxonomyTest.kt`; restore the two lines in
`SubtitleStudioPanel.kt` to `JsonTemplateMapper.groupByCategory` /
`categoryFaLabel(category)`. No data migration is involved.

---

## 11. Remaining work (requires approval)

1. ~~Dedicated presets for the thin/missing categories~~ — **declined for now**
   (decision 2026-10-16): keep the derived taxonomy only. All eight categories
   are browsable today; the thin coverage of *educational* and *rounded/outlined*
   remains documented in §4.3. Revisit only as a deliberate contract change:
   the catalog size is pinned by five assertions across three test files
   (`AcceptanceTestSuite:34`, `JsonTemplateMapperTest:294,427`,
   `JsonTemplateParserTest:22,25`).
2. ~~D1 resolution~~ — **done** (§10.2).
3. **On-device visual verification** of the eight sections and of karaoke
   highlighting with real Persian audio. Not possible in this environment; no
   device/emulator run was performed.
4. **Reference-binary investigation** — still blocked (see reference analysis
   §2.2) and would need a legally obtained binary plus a JADX provider.
