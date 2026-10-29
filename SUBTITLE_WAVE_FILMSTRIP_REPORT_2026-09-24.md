# گزارش قالب‌های زیرنویس — Wave و Filmstrip
# Subtitle Templates — Wave & Filmstrip Implementation Report

**Date:** 2026-09-24 · **Scope:** subtitle template engine only (no timeline / export / Whisper changes)
**Verdict:** `STATUS: PARTIAL` — implemented and unit-verified; **device verification is the user's
gate and has not happened yet.**

---

## 0. Scope decision (why only 2 of the 20)

Inspecting the real catalogs first showed **18 of the 20 requested looks already exist**. Building
them as new templates would have been duplicate architecture (Rule 2). The user chose
*"فقط قالب‌های غایب"* — only the genuinely missing axes.

| # | Requested | Reality — existing implementation |
|---|---|---|
| 01 | Kinetic | `fa_15_dynamic` (SLIDE_UP_WORD, scale 1.3) · `fa_07_creator` (BOUNCE_POP 1.25) · `fa_18_social` (1.2) |
| 02 | Highlight | **`fa_16_bar`** — `activeWordBackgroundArgb=0xFFFF375F` + HIGHLIGHT + HIGHLIGHT_COLOR (exact match) |
| 03 | Pop | `fa_07_creator` (BOUNCE_POP) · `bounce_text_pop` (JSON) |
| 04 | Punch | `fa_15_dynamic` · `keyword_punch_zoom` (JSON, `keywordScaleBoost` 1.4) |
| 05 | Minimal | `fa_01_clean` · `minimal_clean` (JSON) · `fa_19_premium` |
| 06 | Neon | **`fa_12_neon`** (glow 12dp + NEON_GLOW) · `neon_glow` (JSON) |
| 07 | Streamer | `fa_11_gaming` · `fa_02_impact` |
| 08 | Cinema | `fa_08_cinematic` · `cinematic_bar` (JSON, letterboxBars) |
| 09 | Viral | `fa_18_social` · `fa_02_impact` |
| 10 | Typewriter | **`typewriter_reveal`** (JSON, `characterByCharacter` → `TYPEWRITER`) |
| 11 | Fade | `minimal_clean` (JSON) · `TextOverlayAnimation.FADE_IN` |
| 12 | Outline | **`fa_13_outline`** |
| 13 | Gradient | `slide_reveal` (JSON, GRADIENT background + `gradientAngleDeg` consumed at `CaptionRenderer.kt:831`) |
| 14 | Boxed | `fa_17_modern_box` · `fa_05_capsule` · the `cinema_*_box` JSON family |
| 15 | Underline | `progress_bar_underline` (JSON) · `activeWordUnderline` → `CaptionRenderer.kt:595` |
| 16 | Glitch | **`glitch_cut`** (JSON, `glitchIn` → `GLITCH`) |
| 17 | Slide | `slide_reveal` (JSON, `slideInDirectionAware` → `SLIDE_UP_WORD`) |
| 18 | Shadow | `fa_14_shadow_pop` |
| **19** | **Wave** | **GENUINELY MISSING** → implemented as `wave_accent` |
| **20** | **Filmstrip** | **GENUINELY MISSING** → implemented as `filmstrip_frame` |

Catalogs after this change: **20 built-in** (`BuiltInSubtitleTemplates.kt`, unchanged) +
**19 JSON** (`assets/subtitle_templates.json`, was 17) = **39**.

---

## 1. فایل‌های تغییر‌یافته — Files changed

| File | Change | Why |
|---|---|---|
| `app/src/main/assets/subtitle_templates.json` | +2 rows: `wave_accent`, `filmstrip_frame` (17 → 19) | The data-driven extension point |
| `model/SubtitleTemplate.kt` | `enum ProgressBarShape { STRAIGHT, WAVE }`; `ProgressBarSpec.shape` (default `STRAIGHT`); `toJson`/`fromJson` round-trip | The new accent-shape axis. Default keeps every legacy blob/JSON identical |
| `model/WordMotionKind.kt` | `CaptionBackgroundKind.FILMSTRIP("نوار فیلم")` + `engineTypeString → "filmstrip"` | The new background axis |
| `db/EditorStateJson.kt` | `shape` written + read for the overlay `progressBar` | Saved projects keep the wave |
| `export/CaptionRenderer.kt` | Wave math (`WAVE_CYCLE_MS`, `WAVE_WAVELENGTH_FRACTION`, `WAVE_AMPLITUDE_FRACTION`, `waveOffsetY`, `wavePhaseAt`), `drawWaveAccent`, `wavePath`; `FILMSTRIP` branch in `drawBackground` | **The real render code** |
| `model/template/JsonTemplateMapper.kt` | `"filmstrip"` in `mapBackground` + `withBackgroundOverride`; `"waveaccent"` in the `progressBar` spec mapping + `withHighlightOverride`; **and the two `mapMotion` branches (see §5)** | JSON → engine wiring |
| `test/.../CaptionProgressBarTest.kt` | +6 wave-math tests | Proves the math, not just that it compiles |
| `test/.../JsonTemplateMapperTest.kt` | +5 wiring tests; 2 catalog counts 17 → 19 | Proves the rows reach the engine |
| `test/.../JsonTemplateParserTest.kt` | 2 counts 17 → 19; +2 ids in the presence list | Catalog contract |
| `test/.../AcceptanceTestSuite.kt` | 1 count 17 → 19 (+ comments) | Catalog contract |
| `test/.../AiModelStatusTest.kt`, `TranscriptionEngineFallbackTest.kt`, `WhisperDownloadUrlTest.kt` | 5 stale `MEDIUM` refs → `SMALL`/removed | **Pre-existing** break, see §6 |

---

## 2. معماری — Architecture used

**Reuse → Extend. No new renderer, no duplication, no parallel path.**

```
assets/subtitle_templates.json   ← the ONLY place a template is declared
        │  JsonTemplateParser
        ▼
   JsonTemplateCatalog  →  ResolvedTemplate  →  JsonTemplateMapper.toSubtitleTemplate
                                                        │
                                    ┌───────────────────┴───────────────────┐
                                    ▼                                       ▼
                        ProgressBarSpec.shape                  CaptionBackgroundKind
                        (STRAIGHT | WAVE)                      (… | FILMSTRIP)
                                    └───────────────────┬───────────────────┘
                                                        ▼
                              export/CaptionRenderer.kt#render   ← THE single renderer
                                   ▲                    ▲
                    ui/screens/editor/CaptionBitmap.kt   export/SubtitleBurnIn.kt
                          (Compose preview)                (Media3 export)
```

Because both the preview and the export call the **same** `CaptionRenderer`, parity is structural —
there is no second place to keep in sync. Wave and Filmstrip therefore appear identically in the
preview and in the burned-in export by construction.

**No static-image template was added** — both are pure geometry, drawn per frame.

---

## 3. قالب موج رنگی — `wave_accent`

**Behaviour.** The *same* progress-bar contract as `progress_bar_underline` — driven by real
per-word timestamps, growing from the reading edge, same height/placement — but the stroke is a
travelling sine instead of a ruler. Track and fill are both sampled from **one shared phase**, so the
fill grows *along* the same wave rather than a second wave sliding over the first.

| Property | Value |
|---|---|
| `wordHighlight.type` | `waveAccent` |
| `ProgressBarSpec.shape` | `WAVE` |
| Fill / track | `#FFD400` / `#FFFFFF33` |
| `heightRatio` | 0.18 (× font size) |
| `directionAware` | `true` → RTL grows right→left |
| Motion kind | `HIGHLIGHT_COLOR`, reveal `HIGHLIGHT` |
| Cycle | 3600 ms per full travel |
| Wavelength | 0.32 × caption width |
| Amplitude | 0.85 × (barH / 2) — stays inside the bar band |

| # | Item | Result |
|---|---|---|
| 1 | Files changed | see §1 |
| 2 | Architecture | §2 — data-driven row + one new enum value + shared renderer |
| 3 | Template behaviour | as above |
| 4 | **Persian RTL** | ⚠️ **NOT device-verified.** By code: `directionAware=true` → `progressFillRtl(true,true)==true` (unit-tested) so the fill starts at the right edge. The sine is a function of absolute x, so the wave shape is direction-agnostic. **Needs your eye on a real clip.** |
| 5 | **Word timestamps** | Unit-verified: `progressFraction` is driven by `overlay.words` min-start/max-end (existing tests). The wave **adds no new timing source** and needs **no Whisper re-run**. Visual timing still needs device confirmation. |
| 6 | **Timeline** | ⚠️ Not verified. The change touches no timeline/clip code — only `CaptionRenderer` draw + `ProgressBarSpec`. Undo/redo and the timeline are untouched by design, but this is an assertion, not a measurement. |
| 7 | **Performance** | `wavePath` builds **≤240** line segments (`WAVE_STEPS_PER_WAVELENGTH`=10/λ, clamped 2..240); `drawWaveAccent` allocates 2 `Paint` + ≤2 `Path` per frame. No allocation in the math (`waveOffsetY`/`wavePhaseAt` are pure). Cost is flat regardless of canvas size. |
| 8 | **Build** | ✅ `compileDebugKotlin` clean · `assembleDebug` OK · packaged asset verified |
| 9 | Remaining | device test; manual-picker limitation (§7) |

---

## 4. قالب نوار فیلم — `filmstrip_frame`

**Behaviour.** A dark plate behind the caption carrying **two rows of perforations** (top and bottom),
like a frame of 35 mm film. Drawn with plain `drawRoundRect` calls only — no blur, no shader.

| Property | Value |
|---|---|
| `background.type` | `filmstrip` |
| `CaptionBackgroundKind` | `FILMSTRIP` |
| Plate | `#0A0A0A`, cornerRadius 10 dp, opacity 0.9 |
| Perforations | colour = `accentColorArgb`, alpha 0.9, hole H = 0.11 × box height, W = 1.6 × H, **count capped at 20** |
| Motion kind | `HIGHLIGHT_COLOR`, reveal `HIGHLIGHT` (active word `#FFD400`) |

| # | Item | Result |
|---|---|---|
| 1 | Files changed | see §1 |
| 2 | Architecture | §2 |
| 3 | Template behaviour | as above |
| 4 | **Persian RTL** | Perforation layout is **symmetric and centred** (`startX` centres the row), so it is RTL/LTR-neutral by construction. Text RTL comes from the existing path. ⚠️ Not device-verified. |
| 5 | **Word timestamps** | Uses `colorChange` + `wordTimestamp` — the existing highlight path. No new timing source, **no Whisper re-run**. |
| 6 | **Timeline** | ⚠️ Not verified; no timeline code touched. |
| 7 | **Performance** | ≤ **41** `drawRoundRect` calls per frame (1 plate + 2 × ≤20 holes), hard-capped, independent of canvas size. No allocation beyond 1 `Paint`. |
| 8 | **Build** | ✅ same as above |
| 9 | Remaining | device test; manual-picker limitation (§7) |

---

## 5. 🐞 باگ واقعی که تست پیدا کرد — The bug the tests caught

**This is the most important line in this report.**

`JsonTemplateMapper.mapMotion` keys **two independent `when` branches** off the highlight type
string, and `withHighlightOverride` has a **third**. Adding `"waveaccent"` to only the
`progressBar`-spec mapping left `motionKind = NONE`:

```kotlin
// BEFORE — "waveaccent" fell through to NONE
var kind = when (hl) {
    "colorchange" -> WordMotionKind.HIGHLIGHT_COLOR
    "box"         -> WordMotionKind.HIGHLIGHT_COLOR
    "progressbar" -> WordMotionKind.HIGHLIGHT_COLOR
    else          -> WordMotionKind.NONE        // ← waveaccent landed here
}
...
val reveal = when {
    ...
    hl == "colorchange" || hl == "box" || hl == "progressbar" -> HIGHLIGHT   // ← also missing
```

**Consequence:** the template compiled, parsed, resolved, and mapped a WAVE bar — and **never
highlighted a single word.** The build was green. The parser tests were green. Only a test asserting
the *resolved engine field* caught it (`expected:<HIGHLIGHT_COLOR> but was:<NONE>`).

**Fixed** in both branches. This is bug class #1 in its purest form, and the new variant
(*"a new value on a string-keyed axis must be added to every `when`"*) is now recorded in
`.workbuddy-ai/memory/MEMORY.md` and the agent's `bug-classes.md`.

> This is exactly why the rule *"never declare a template complete because the code compiles"* exists.
> Compilation would have shipped a broken template.

---

## 6. Pre-existing breakage repaired to unblock verification

The JVM test source set had been **uncompilable since 2026-09-21** — `WhisperModelSpec.MEDIUM` and
`AiModelCatalog.WHISPER_MEDIUM` were deleted (users migrated to Small) but **5 test references were
left behind**, so **no unit test could run at all**. Repaired minimally (`MEDIUM` → `SMALL`, or
dropped where `SMALL` already asserted the same thing):

- `AiModelStatusTest.kt` — removed the redundant `MEDIUM.isCloud` line
- `TranscriptionEngineFallbackTest.kt` ×2 → `SMALL`
- `WhisperDownloadUrlTest.kt` ×2 → `WHISPER_SMALL` (the test was already *named* `whisper-small…`)

**Still broken, not touched:** the `androidTest` source set (~6 files reference
`WhisperModelSpec.MEDIUM`). `testDebugUnitTest` does not compile it, but `assembleDebugAndroidTest`
will fail.

---

## 7. نتیجه بیلد — Verification evidence

```
:app:compileDebugKotlin            ✅ clean (0 errors)
:app:assembleDebug                 ✅ app-debug.apk — 91,881,370 bytes
   └─ assets/subtitle_templates.json packaged: 19 templates, incl. wave_accent + filmstrip_frame
:app:testDebugUnitTest             1444 tests completed, 2 failed, 2 skipped
```

| Test class (touched) | Tests | Failed |
|---|---|---|
| `CaptionProgressBarTest` | 10 | **0** |
| `JsonTemplateMapperTest` | 30 | **0** |
| `JsonTemplateParserTest` | 5 | **0** |
| `AcceptanceTestSuite` | 17 | **0** |
| `CaptionBackgroundTest` | 20 | **0** |
| `SubtitleTemplateTest` / `BuiltInSubtitleTemplatesFaFontTest` | 16 / 4 | **0** |
| `AiModelStatusTest` / `TranscriptionEngineFallbackTest` / `WhisperDownloadUrlTest` | 6 / 7 / 4 | **0** |

**The 2 failures are pre-existing, asset-dependent, and unrelated to templates:**

1. `RealModelInstallTest` — wants `app/src/main/assets/ai-models/whisper-ggml-medium/ggml-medium.bin`,
   deleted on 2026-09-21.
2. `WhisperNativeSymbolsTest > libwhisper so is built for every supported ABI` — wants a
   `libwhisper.so` per ABI while `abiFilters` is `arm64-v8a` only. (It *skips* until `assembleDebug`
   has produced stripped native libs — which is why the skip count moves between runs.)

New baseline recorded in `MEMORY.md`: **1444 / 2 / 2** (was 1189 / 5 / 2 — that count predates the
MEDIUM removal).

---

## 8. محدودیت‌ها — What is NOT verified / known limitations

**Honest status: the code is wired and unit-proven. The *look* is not verified.**

1. **❌ No device test has been run.** `adb devices` is empty. The user tests on their real device —
   this was the explicit choice. **Nothing here is "complete" until you select both templates on a
   real Persian clip and look at the result.**
2. **❌ Persian RTL rendering is reasoned, not seen.** The math is direction-aware and unit-tested;
   the visual (does the wave read well under Vazirmatn, does the filmstrip sit correctly behind
   joined Persian text) is unverified.
3. **❌ Preview/export parity is structural, not measured.** Both call the same renderer, but no
   export was produced and compared.
4. **❌ Timeline interaction is untouched but unverified** (no clips added/trimmed during testing).
5. **⚠️ Manual override pickers don't offer the new axes.** `SubtitleStudioPanel` keeps local
   string↔kind lists for the Background/Highlight tabs with only the 6 original kinds. The new axes
   are reachable from the **Templates** section (as required) but not as a *manual* override option.
   They degrade gracefully — `else -> null` falls back to the template's own kind and
   `effectiveKind.engineTypeString` round-trips `"filmstrip"` back correctly, so **nothing destroys
   the look** — but it is a UX gap. Fixable in ~4 lines if you want it.
6. **`overshoot`** remains parsed and plumbed with no renderer consumer (a bug-class-#1 candidate,
   flagged previously — not part of this change).

---

## 9. قدم بعدی — Your device test

1. Install the fresh `app-debug.apk`.
2. Import a short **Persian** clip + SRT (no Whisper run needed — the templates add no timing source).
3. Open **زیرنویس → قالب‌ها** and pick **«موج رنگی»** — confirm: the accent is a moving wave, it
   grows from the **right** for Persian, and the active word highlights.
4. Pick **«نوار فیلم»** — confirm: the dark plate with two perforation rows sits behind the text and
   does not clip the glyphs.
5. Scrub the timeline and confirm both follow the word timestamps and survive undo/redo.
6. Export a few seconds and confirm the preview and the export match.

Report back what you see and I will fix anything that differs.
