# Persian Subtitle Template — Research and Design Conclusions

**Date:** 2026-10-11
**Applies to:** `com.aicreatorstudio.app` — the 20 built-in Persian subtitle templates
(`model/BuiltInSubtitleTemplates.kt`) and the shared renderer
(`export/CaptionRenderer.kt`).

Every conclusion below records its source and states how it changes or confirms
an actual implementation decision. Conclusions that did **not** change anything
are marked *confirms existing*.

---

## 1. RTL layout and Persian-specific typography

### 1.1 Mirror the layout — do not just right-align text

> "RTL languages require complete layout mirroring — not simply right-aligning
> left-to-right layouts."

**Source:** https://www.dtplabs.com/blog/rtl-typography-complete-guide-arabic-hebrew-farsi

**Application to Omelet.** A caption is not a page, so there is no column or
binding edge to mirror. The one direction-sensitive element that *does* exist is
the **progress bar** (`ProgressBarSpec.directionAware`). The research confirms
the correct model: under an RTL caption the bar must grow from the **right**
(reading) edge. `CaptionRenderer` already implements this
(`directionAware`, line ~192, ~786). *Confirms existing* — and is now pinned by
test (§7).

### 1.2 Never reverse Persian strings manually

> "Software tools with proper Arabic support handle most cases correctly using
> the Unicode Bidirectional Algorithm... a font that supports basic character
> shapes but lacks proper contextual alternates produces **disconnected letter
> forms** that read as amateur typography to native speakers."

**Sources:**
- https://www.dtplabs.com/blog/rtl-typography-complete-guide-arabic-hebrew-farsi
- https://developer.android.com/reference/android/text/TextDirectionHeuristics

**Application to Omelet.** Persian letters are **cursively joined** — «س» + «ل»
must shape into «سل», not render as two isolated glyphs. Reversing a Persian
string breaks both joining and contextual forms. The implementation uses
`TextDirectionHeuristics.FIRSTSTRONG_RTL` on the `StaticLayout` and lets the
platform bidi algorithm order the run
(`CaptionRenderer.kt:549`, `:663`, `:742`). There is **no `String.reverse()`**
anywhere in the caption path. This is a hard requirement and is now tested
(§7.2).

### 1.3 Mixing Persian with Latin and digits

> "Product names like iPhone or Microsoft 365 appear as Latin text within Arabic
> paragraphs but must flow naturally in the overall right-to-left reading
> direction — the Latin letters read left-to-right locally while the surrounding
> paragraph continues right-to-left globally."
>
> "The numeric characters display left-to-right even within RTL paragraphs,
> because mathematical notation is inherently left-to-right in written
> convention."

**Source:** https://www.dtplabs.com/blog/rtl-typography-complete-guide-arabic-hebrew-farsi

**Application to Omelet.** A caption like «با ChatGPT کار میکنم» must place
«ChatGPT» as an intact LTR island inside an RTL sentence — **not** reverse it, and
**not** split it across lines if avoidable. `FIRSTSTRONG_RTL` handles this
correctly because the first strong character is Persian. This is why the
renderer must never apply a per-word transform that assumes uniform direction,
and why each word's own direction is probed independently when rendering the
active-word highlight (`:742` `rtlWord`). Tested in §7.3.

### 1.4 Numbers

Persian locales use either Persian-Indic digits (۰-۹) or Western digits
depending on convention. **Source:** same DTP Labs guide.

**Application to Omelet.** Do not transcode digits. Whisper returns whichever
form the speaker's audio implies; the template system must render both
faithfully. No digit normalisation was added — this is a deliberate
*non-change*.

### 1.5 نیم‌فاصله (ZWNJ, U+200C)

ZWNJ is described in the Persian typography literature as a load-bearing
character that separates word parts without inserting a visible space
(«میروم» is correct; «میروم» and «میروم» are not).
**Source (Persian typography guide):** https://vibefarsi.ir/skills/persian-typography

**Application to Omelet.** ZWNJ must survive the whole pipeline:
`PersianNormalizer.normalize` preserves it explicitly while folding Arabic
ي/ك, and the corrector's `ZWNJ_FIXES` table *adds* ZWNJ to forms Whisper wrote
without it. Templates must not re-space or trim word-internal ZWNJ. Tested in
§7.3.

---

## 2. Line length and wrapping

> "Aim for an average line length of **45–90 characters**, including spaces...
> You should be able to fit between **two and three alphabets** on a line."

**Source:** https://practicaltypography.com/line-length.html

**Caveat that matters here.** This figure is for continuous body text read in
long paragraphs. **Video captions are not paragraphs** — a viewer reads one
caption in 1–3 seconds, in peripheral vision, while also watching the picture.
The applicable rule is much stricter than 45 characters.

**Decision.** Omelet already caps captions at `maxCharsPerLine = 42` in
`SubtitlePostProcessSettings` (default) for a single caption line, which is
below the typographic range *and* appropriate for glance-reading. The templates
keep `maxTextWidthFraction = 0.9` so the text never touches the frame edge.
The practical caption budget used across the catalog is **≤ 7 words / ~42
characters per line**, which is what the segmenter already enforces.
*Confirms existing* — the 45–90 guidance was reviewed and deliberately **not**
adopted, because it is a page-typography measure.

---

## 3. Vertical-video safe areas

> "Edit vertical video on a 1080×1920 canvas, but keep essential text, faces and
> calls to action away from the outer edges and **lower interface area**."
>
> "**Where should subtitles go?** Usually in the **lower-middle** area, high
> enough to clear controls and large enough to scan without blocking the
> subject."
>
> "**Common mistakes:** Placing subtitles at the very bottom."

**Source:** https://mediaspeclab.com/en/social-media-safe-zones-2026-keep-text-visible-in-vertical-video/

That article is deliberately explicit that **no single pixel-perfect safe
rectangle exists** across Reels / Shorts / TikTok, because interface overlays
vary by platform, device and caption length. It recommends a conservative
master layout instead.

**Decision — this is the most important numerical change the research produced.**

The existing catalog places most captions at `positionY` between **0.84 and
0.88**. On a 1920 px-high canvas, `0.88` puts the caption baseline at
**y ≈ 1690 px**, i.e. ~230 px from the bottom edge — squarely inside the region
the platform's caption/username overlay and the action-button column occupy.
The research says explicitly that this is the classic mistake.

The catalog was therefore **lifted out of the 0.85+ band**. The actual
post-change distribution across the 20 templates is:

| Band | `positionY` | Templates |
|---|---|---|
| Upper-lower / caption-stack | 0.62 | `fa_18_social` — a deliberately tall two-line stack |
| Mid-lower (podcast / talking-head) | 0.72–0.75 | `fa_10_podcast`, `fa_07_creator` |
| Lower-middle (recommended) | 0.78–0.80 | `fa_02_impact`, `fa_11_gaming`, `fa_13_outline`, `fa_15_dynamic`, `fa_20_signature`, `fa_12_neon`, `fa_14_shadow_pop` |
| Lower-third (default) | 0.82 | `fa_04_karaoke`, `fa_06_glass`, `fa_09_news`, `fa_17_modern_box` |
| Lower-third (default) | 0.84 | `fa_01_clean`, `fa_03_yellow_word`, `fa_05_capsule`, `fa_16_bar`, `fa_19_premium` |
| Cinematic standard | 0.88 | `fa_08_cinematic` (only exception) |

That is **19 of 20 templates moved into 0.62–0.84**; the single remaining
template is the cinematic one, deliberately left low.

`0.88` is retained **only** for the cinematic/dialogue template, where the
bottom-positioned subtitle is the established convention of the medium and the
viewer expects it. Every other template moved up.

For reference, the renderer's own anchor table
(`templatePositionAsAnchorDelta`, `SubtitleTemplate.kt:353`) treats
`BOTTOM = 0.88`, `CENTER = 0.5`, `TOP = 0.12`, so a `positionY` of 0.78–0.84
sits clearly above the platform chrome while remaining visually "bottom".

---

## 4. Readability over bright, dark and busy footage

> "Using tiny auto-captions with **low contrast**" is listed as a common
> mistake.

**Source:** https://mediaspeclab.com/en/social-media-safe-zones-2026-keep-text-visible-in-vertical-video/

The WCAG 2.1 minimum contrast requirement is **4.5:1 for normal text** and
**3:1 for large text** (≥ 18 pt regular, or ≥ 14 pt bold).
**Source:** https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html

**Application to Omelet.** White text over arbitrary video cannot guarantee a
contrast ratio, because the background is unknown and changes per frame. The
mitigation is therefore **always** one of:

1. a **stroke/outline** (black, 3–4.5 sp) — used by IMPACT, YELLOW_WORD,
   CREATOR, GAMING, DYNAMIC, SOCIAL, OUTLINE, PODCAST;
2. a **background panel** (solid or blurred, 0.45–0.72 opacity) — used by
   CAPSULE, GLASS, NEWS, PODCAST, MODERN_BOX, SIGNATURE;
3. a **drop shadow** with sufficient offset.

Every one of the 20 templates carries at least one of these three. A template
with none would be unreadable on white footage, so this is enforced by test
(§7.1). The `OUTLINE` template deliberately sets `textColorArgb = TRANSPARENT`
with a stroke only — a hollow treatment intended specifically for bright scenes,
where a white fill would disappear.

---

## 5. Active-word treatment

Observed pattern across CapCut / Reels / TikTok caption styles: the **spoken
word** is distinguished from its neighbours by one dominant mechanism, and only
one — a colour change, a background pill, a karaoke fill, a scale bump, or an
underline. Stacking several at once is what makes amateur captions unreadable.

**Sources (public product documentation and observable public examples):**
- CapCut text/caption feature documentation:
  https://www.capcut.com/tools/auto-caption
- YouTube Shorts creation guidance (caption tooling):
  https://support.google.com/youtube/answer/10343433

The brief's own constraint is aligned: "Avoid excessive neon, bouncing,
oversized text and decorative effects that reduce readability."

**Decision.** Each template declares exactly **one primary** active-word
mechanism via `motionKind` + `activeWordColorArgb`, with `activeWordUnderline`
and `activeWordBackgroundArgb` used as restrained secondary accents only:

| Mechanism | `motionKind` | Templates |
|---|---|---|
| Colour change | `HIGHLIGHT_COLOR` | YELLOW_WORD, NEWS, PODCAST, BAR |
| Karaoke fill | `KARAOKE_FILL` | KARAOKE, SIGNATURE |
| Scale/motion bump | `FADE_SCALE`, `BOUNCE_POP`, `SLIDE_UP_WORD` | GAMING, CREATOR, DYNAMIC, SOCIAL |
| Glow | `NEON_GLOW` | NEON |
| None (static) | `NONE` | CLEAN, IMPACT, CAPSULE, GLASS, CINEMATIC, OUTLINE, SHADOW_POP, MODERN_BOX, PREMIUM |

`activeWordScale` is capped at **1.12** across the catalog. The field itself
permits up to 2.5, but a scale above ~1.15 on a 9:16 frame causes the caption
block to reflow mid-word and is exactly the "oversized/bouncing" failure the
brief prohibits.

---

## 6. Fonts and licensing

All Persian families bundled in the app are **SIL Open Font License 1.1**,
released by their authors (rastikerdar / aminabedi68). This is recorded in the
repo at `app/src/main/res/font/vazirmatn_family.xml`:

> "All fonts are SIL OFL (rastikerdar / aminabedi68 releases)."

SIL OFL 1.1 explicitly permits bundling and redistribution inside an
application, including commercial use, provided the font is not sold on its own
and reserved names are respected.
**Source:** https://openfontlicense.org/

**Fonts currently used by the 20 templates (all OFL, all bundled):**
Vazirmatn, Vazirmatn Code (mono), Sahel, Shabnam, Samim, Estedad, Parastoo,
Gandom, Nahid, Tanha, Lalezar.

**Consequence for the brief's requirement to "verify actual font availability
before referencing a font".** The Persian fonts named in the catalog are present
in `res/font/` and are all OFL. **No new font was added**, because adding a
font would grow the APK and every required design direction is reachable with
the existing eleven Persian families (11 distinct faces across 20 templates is
ample). A regression test asserts that every built-in template pins a real
bundled Persian family and never falls back to `DEFAULT`
(`BuiltInSubtitleTemplatesFaFontTest`).

---

## 7. Animation timing and preview/export consistency

> The platform caption tools animate captions on entry and rely on the same
> timing source for the exported file.
**Source:** https://www.capcut.com/tools/auto-caption

**Application to Omelet — the architectural guarantee.**

`CaptionRenderer.render(...)` is a **single** implementation consumed by:

- the live editor preview — `ui/screens/editor/CaptionBitmap.kt:59`
- the burned-in video export — `export/SubtitleBurnIn.kt:245, :293`
- the tracked-overlay export path — `export/TrackedOverlayRenderer.kt:86`
- the template picker thumbnails — `rememberTemplateImageBitmap`

Because there is exactly one drawing routine, a template **cannot** look
different in the picker and in the export. This satisfies the brief's "No fake
preview that differs from the exported result" at the architectural level
rather than by convention.

Entry/exit durations are `animationDurationMs` (60–1500, validated in
`SubtitleTemplate.init`) with an optional separate `animationDurationMsOut`.
Values used across the catalog are **160–320 ms** for entry. Anything slower
reads as sluggish on a 1–3 second caption.

---

## 8. What the research changed vs. confirmed

| # | Conclusion | Effect on the code |
|---|---|---|
| 1 | Safe area: never place captions in the bottom UI band | **Changed** — 19 of 20 templates now sit in 0.62–0.84; `fa_08_cinematic` kept at 0.88 as the one deliberate convention exception |
| 2 | 45–90 char line length is page typography, not captions | **Confirmed** existing 42-char cap; explicitly not adopted |
| 3 | One dominant active-word mechanism per design | **Confirmed**; `activeWordScale` capped at 1.12 |
| 4 | Contrast needs stroke, panel or shadow — always | **Confirmed**; enforced by test |
| 5 | RTL via bidi algorithm, never manual reversal | **Confirmed**; enforced by test |
| 6 | Latin/numeric runs stay intact inside RTL text | **Confirmed**; enforced by test |
| 7 | ZWNJ is load-bearing | **Confirmed**; enforced by test |
| 8 | Fonts must be OFL and bundled | **Confirmed**; no new font added |
| 9 | One renderer ⇒ preview == export | **Confirmed** architecture |
| 10 | No universal pixel-perfect safe rectangle exists | **Noted as a limitation** — templates are conservative, not micro-tuned per platform |

---

## 9. Sources (all public, consulted 2026-10-11)

1. DTP Labs — *RTL Typography: A Complete Guide for Arabic, Hebrew & Farsi*
   https://www.dtplabs.com/blog/rtl-typography-complete-guide-arabic-hebrew-farsi
2. Butterick's Practical Typography — *Line length*
   https://practicaltypography.com/line-length.html
3. Media Spec Lab — *Social Media Safe Zones 2026: Keep Text Visible in Vertical Video*
   https://mediaspeclab.com/en/social-media-safe-zones-2026-keep-text-visible-in-vertical-video/
4. W3C — *Understanding WCAG 2.1: Contrast (Minimum)*
   https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html
5. Android Developers — *TextDirectionHeuristics*
   https://developer.android.com/reference/android/text/TextDirectionHeuristics
6. SIL — *Open Font License 1.1*
   https://openfontlicense.org/
7. CapCut — *Auto caption tool documentation*
   https://www.capcut.com/tools/auto-caption
8. Google — *Create YouTube Shorts* (support documentation)
   https://support.google.com/youtube/answer/10343433
9. Persian typography skill guide (font licensing, نیم‌فاصله, mixed text)
   https://vibefarsi.ir/skills/persian-typography

**Note on sourcing honesty.** Sources 7 and 8 are official product
documentation; they document *that* caption styling and safe-area tooling
exist and how the platform expects captions to be handled. They do **not**
publish the internals of proprietary template packs, and none of the Omelet
templates are copies of a vendor template. The designs here are original,
derived from the general principles above. No claim is made that any template
is "proven viral" — no performance data exists to support such a claim.
