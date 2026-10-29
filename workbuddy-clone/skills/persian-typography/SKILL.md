---
name: persian-typography
description: Choose, load and tune typography for Persian text - glyph coverage, joining/shaping, fonts and licenses, fallback stacks, line-height, weights, wrapping, truncation and clipping, letter-spacing pitfalls and font performance. Use for any Persian text styling or font decision.
---


# تایپوگرافی فارسی (Persian Typography)

## When to use
Setting or reviewing fonts, text sizes, line-height, truncation, buttons with Persian labels, or font loading for a Persian UI.

## Instructions

### 1. Never pick a font without checking three things
1. **Coverage**: Persian letters (including پ چ ژ گ), ZWNJ (U+200C), Persian digits (U+06F0–06F9), punctuation (، ؛ ؟ « »), and common marks (ـ, ً ٌ ٍ َ ُ ِ ّ ْ). Test with a specimen string (below).
2. **License**: web-embedding and app-embedding rights, commercial use, modification, redistribution. Many popular Persian fonts are open (e.g. SIL OFL for Vazirmatn and Noto fonts) while others are commercial or restricted. **Read the license in the font's official repository or vendor page; do not assume.** Record font name, version, source URL and license in the project's docs.
3. **Product fit**: tone (neutral UI vs. editorial), weights actually needed, variable-font support, digit style (Persian vs. Latin default), Latin glyph quality (if Latin appears in UI).

Do not hard-code a font recommendation as the answer; present 2–3 vetted options with trade-offs and let the project decide.

### 2. Specimen string for testing
```
فارسی: می‌شود، نمی‌توانید، به‌روزرسانی، هم‌زمان، پیچیده، گزارش ژرف، چای و گل
ارقام: ۰۱۲۳۴۵۶۷۸۹  |  لاتین: 0123456789
علائم: «نقل‌قول» ، ؛ ؟ ٪ … ( ) [ ]
مختلط: نسخه‌ی v2.4.1 از Chrome در Windows 11 منتشر شد.
```
Check: letters join correctly, no tofu/boxes, ZWNJ renders as a gap with no joining, digits look consistent, mixed Latin baseline aligns.

### 3. Shaping and joining rules
- **Never set `letter-spacing` on Persian text** (other than `0`/`normal`): it breaks cursive joining. Reset it inside Persian contexts and in components inherited from Latin design systems (uppercase labels, buttons, tabs).
- `text-transform: uppercase/capitalize` is meaningless for Persian; remove from shared styles that also render Persian.
- **No faux italics**: Persian has no true italic; synthesized slant looks poor. Use weight, color or size for emphasis. Set `font-synthesis: none` where practical.
- Avoid `text-align: justify` for UI and body text (uneven gaps). If justified text is required, test it visually.
- Do not strip or normalize ZWNJ in rendering code. Do not render Arabic variants ي/ك; see `persian-ux-writing`.

### 4. Sizes, line-height, weights
- Persian text needs more vertical room than Latin. Start points to validate visually: body 16–18 px with `line-height` 1.7–1.9; headings 1.3–1.5; dense UI labels at least 1.5.
- Minimum comfortable size: 14 px for secondary text; avoid 12 px for sentences.
- Use few weights (typically 400, 500/600, 700). Verify that each weight exists in the loaded files, otherwise the browser synthesizes bold.
- Persian and Latin of the same `px` size look different (Latin x-height is larger). When mixing, adjust with `font-size-adjust` or `size-adjust` on the fallback `@font-face`, or tune the two families separately.

### 5. Font stacks and loading
```css
:root {
  --font-ui: "<ChosenPersianFont>", "Noto Sans Arabic", system-ui, "Segoe UI", Tahoma, sans-serif;
}
html { font-family: var(--font-ui); letter-spacing: 0; }
```
- Always include a system fallback that supports Persian (Tahoma and Segoe UI on Windows; system-ui elsewhere) and test with the web font **blocked**.
- Self-host as **WOFF2**; use `font-display: swap` (or `optional` for non-critical weights); preload only the one or two critical files.
- Subset sensibly: Arabic/Persian blocks (U+0600–06FF, U+0750–077F, U+FB50–FDFF, U+FE70–FEFF) plus U+200C–200D and needed Latin/digits. Use `unicode-range` to split Persian and Latin files if using a separate Latin face.
- Prefer a variable font when you need 3+ weights; compare total bytes.
- Some font families ship separate builds for Persian digits (often marked "FD"). Confirm which digit shapes the chosen build renders for ASCII digits and match that with your localization policy (`persian-localization`).

### 6. Wrapping, truncation, clipping
- Allow wrapping by default. For single-line truncation use `overflow: hidden; text-overflow: ellipsis; white-space: nowrap;` and ensure `min-width: 0` on flex/grid children. For multi-line use `-webkit-line-clamp`/`line-clamp`.
- Tight `line-height` plus `overflow: hidden` **clips Persian diacritics and tall letter forms** (ک گ ل ط, dots below). Give buttons/chips at least `line-height: 1.5` and block padding; test with ً ّ and long strings.
- Buttons: use `white-space: normal` plus `min-height`, not fixed `height`, so longer Persian labels can wrap without clipping.
- Long unbroken tokens (URLs, IDs): `overflow-wrap: anywhere`.
- Do not hard-break lines with `<br>` in translatable strings.

## Procedure
1. Inventory fonts in use (CSS `@font-face`, imports, `<link>`, Tailwind/theme config, system fallbacks).
2. Render the specimen at 3 sizes and each used weight; capture screenshots; note tofu/missing glyphs.
3. Check licenses and record them.
4. Check `letter-spacing`, `text-transform`, `font-style: italic`, `justify`, `line-height` and `overflow` rules that apply to Persian text.
5. Measure font bytes and request count; test with the web font blocked.

## Acceptance checklist
- [ ] Specimen renders with correct joining, no tofu, ZWNJ preserved.
- [ ] License verified and recorded (name, version, URL, license).
- [ ] No `letter-spacing`/`uppercase`/faux italic on Persian text.
- [ ] Body `line-height` reviewed (about 1.7–1.9) and no clipping in buttons, chips, tabs, tables.
- [ ] Fallback stack tested with the web font blocked.
- [ ] WOFF2, `font-display` set, critical files preloaded, total font weight justified.
