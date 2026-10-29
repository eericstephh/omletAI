# Persian RTL release checklist

Use before shipping a Persian interface. Each item should be **verified** (you saw it) or marked **not verified**. Details live in the matching skill.

## Direction and layout (`persian-rtl-layout`)
- [ ] `<html lang="fa" dir="rtl">` (or per-locale values) set from the active locale.
- [ ] No `row-reverse` faking RTL; DOM order = visual order = focus order.
- [ ] Logical CSS properties in new code; physical `left/right` justified where kept.
- [ ] Directional icons mirrored; logos, checkmarks, clocks untouched.
- [ ] Menus, drawers, toasts, steppers, tables, charts, carousels checked in RTL.
- [ ] 320 / 375 / 768 / 1280 px with no accidental horizontal scroll.

## Typography (`persian-typography`)
- [ ] Specimen renders: joining correct, no missing glyphs, ZWNJ preserved.
- [ ] Font license verified and recorded; WOFF2, subset, `font-display` set.
- [ ] No `letter-spacing`, uppercase transforms or faux italics on Persian text.
- [ ] Line-height about 1.7-1.9 for body; no clipping in buttons, chips, tabs.
- [ ] System fallback tested with the web font blocked.

## Writing (`persian-ux-writing`)
- [ ] Persian ی/ک only; consistent digits.
- [ ] نیم‌فاصله correct; one register (شما); one button convention.
- [ ] Errors say what happened and what to do; empty states have a next action.
- [ ] Plurals via ICU/Intl; no concatenated sentences; glossary applied.

## Localization (`persian-localization`)
- [ ] Policy documented: calendar, digits, currency (rial vs toman), market.
- [ ] Canonical stored values (ISO dates, numeric money, E.164 phones).
- [ ] Intl with explicit calendar/numbering system; units labeled.
- [ ] Persian/Arabic/Latin digit input normalized; Jalali dates validated by a library.
- [ ] Locale-aware sorting and normalized search.

## Mixed direction (`bidirectional-content`)
- [ ] URLs, emails, phones, IDs, versions isolated (`dir="ltr"` / `<bdi>`).
- [ ] User-generated text uses `dir="auto"` / `<bdi>`.
- [ ] No manual string reversal or stored bidi control characters.
- [ ] Copy/paste of IDs and emails is clean.

## Quality (`persian-ui-quality`)
- [ ] Tokens used; state matrix complete (hover, focus, disabled, loading, error, empty).
- [ ] Glass effects restrained, with opaque fallback; no blur behind long text.
- [ ] Motion limited to transform/opacity; reduced motion honored.
- [ ] Font/dependency weight justified; existing tests run.

## Accessibility (`persian-accessibility`)
- [ ] Persian accessible names; labels visible; errors associated with fields.
- [ ] Keyboard-only flows complete; arrow keys follow RTL in custom widgets.
- [ ] Focus visible; targets at least 24 px (44 px preferred on touch).
- [ ] Contrast measured (text, icons, focus rings, over blur).
- [ ] 200% zoom and 320 px reflow OK.
- [ ] Screen reader with a Persian voice tested, or explicitly marked **not verified**.
