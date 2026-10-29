---
name: persian-accessibility
description: Audit and implement accessibility for Persian RTL interfaces - semantics, accessible names in Persian, keyboard and arrow-key behavior in RTL, focus visibility, validation error association, contrast, text scaling, touch targets, reduced motion, language tagging of mixed content, and honest recording of what cannot be tested. Use for any Persian UI accessibility work.
---


# دسترس‌پذیری رابط فارسی (Persian Accessibility)

## When to use
Reviewing or building forms, navigation, dialogs, date pickers, tables, notifications, or any interactive Persian UI for keyboard, screen-reader, low-vision and motor accessibility.

## Instructions (map to WCAG 2.2 where useful)

### 1. Language and semantics
- `<html lang="fa">`; tag embedded foreign segments: `<span lang="en">Google Chrome</span>` so screen readers can switch pronunciation. Keep `dir` consistent with `lang`.
- Use native elements first (`button`, `a`, `input`, `select`, `dialog`, `table`, `nav`, `main`). ARIA only to fill gaps; a wrong role is worse than none.
- One `h1`; heading levels follow structure, not appearance. Landmarks: `header`, `nav`, `main`, `footer`; label repeated landmarks («ناوبری اصلی»).
- Page `<title>` unique and Persian (or the product's language).

### 2. Accessible names
- Every control has a Persian accessible name: visible label, `aria-label` or `aria-labelledby`. Icon-only buttons need a name («بستن»، «جست‌وجو»، «منو»).
- The accessible name should contain the visible label text (WCAG 2.5.3 Label in Name).
- Placeholders are not labels. Do not rely on `title`.
- Mixed content: keep names short and avoid long isolated Latin strings.

### 3. Forms and validation
- `<label for>` or wrapping label; required state via `required` and a visible text marker, not color alone («(الزامی)»).
- Errors: set `aria-invalid="true"`, link message with `aria-describedby`, place it next to the field in plain Persian (`persian-ux-writing`), and do not rely on color/icon alone.
- On submit failure: move focus to an error summary or the first invalid field; summary links to each field. Announce async results through `role="status"` (polite) or `role="alert"` (urgent only).
- Use `autocomplete`, `inputmode`, correct `type`; keep numeric inputs tolerant of Persian digits (`persian-localization`).

### 4. Keyboard in RTL
- All functionality works with keyboard only; visible logical focus order equals DOM order and visual order (`persian-rtl-layout`).
- **Arrow keys in horizontal widgets (tabs, radios, menus, toolbars, sliders, carousels) must follow reading direction**: in RTL, moving to the *next* item generally corresponds to the **Left** arrow. Native elements usually handle this; custom widgets often do not. Test each one by hand.
- Dialogs: focus moves in, is trapped (prefer native `<dialog>`/inert background), `Esc` closes, focus returns to the trigger.
- Skip link («پرش به محتوای اصلی») as the first focusable element; make it visible on focus.
- Jalali date pickers: grid semantics, arrow-key day navigation, PageUp/PageDown for months, Home/End, Persian month/day names announced, and a plain text-entry alternative.

### 5. Visual requirements
- **Contrast**: text 4.5:1 (large text 3:1); UI component boundaries, icons and focus rings 3:1 against adjacent colors (WCAG 1.4.3, 1.4.11). Measure with tokens in light and dark, including text over glass/blur (`persian-ui-quality`).
- **Focus**: never `outline: none` without a replacement; use `:focus-visible` with at least a 2 px ring and offset.
- **Text scaling**: layout survives 200% zoom and browser text-size increase without clipped Persian text; reflow at 320 CSS px width without two-dimensional scrolling (1.4.4, 1.4.10). Use relative units (`rem`).
- **Text spacing**: overriding line-height/letter/word spacing must not break layouts (1.4.12). (Remember: Persian text should not get letter-spacing; the override is a test, not a style.)
- **Target size**: at least 24×24 CSS px (WCAG 2.2 AA, 2.5.8); aim for 44×44 on touch.
- **Motion**: respect `prefers-reduced-motion`; no auto-playing motion longer than 5 s without a pause control; no flashing.
- Do not use color alone to convey state; add text or icon with accessible name.

### 6. Tables and data
- Use `<table>` with `<th scope>` and `<caption>`; check reading order in RTL (first column on the right).
- Charts need a text alternative or data table; do not rely on color alone.

### 7. Honest testing record
Automated tools find only a subset. Record for each check: method (automated/manual), tool, result.
- Automated: axe, Lighthouse, ESLint a11y plugins if available in the project.
- Manual: keyboard-only walkthrough, 200% zoom, 320 px reflow, contrast measurement, reduced motion.
- **Screen readers**: Persian speech voice availability varies by OS and screen reader (some setups read Persian with a different or no Persian voice). If you cannot test with a real screen reader and a Persian voice, mark it **"not verified"** — do not claim support.

## Procedure
1. Check `lang`, landmarks, headings, `<title>`.
2. Tab through each main flow in RTL; record order issues and traps.
3. Inspect forms for labels, error association, focus on errors.
4. Check focus styles, target sizes, contrast tokens, zoom/reflow, reduced motion.
5. Run available automated checks; list results and gaps.
6. Produce findings with WCAG reference, severity, evidence, fix, and verification status.

## Acceptance checklist
- [ ] `lang` set on page; foreign segments tagged.
- [ ] Every control has a Persian accessible name containing its visible label.
- [ ] Errors associated (`aria-describedby`/`aria-invalid`) and focus managed on submit failure.
- [ ] Keyboard-only flows complete in RTL; arrow keys follow direction in custom widgets.
- [ ] Focus visible everywhere; targets ≥24 px (44 px preferred on touch).
- [ ] Contrast measured for text, icons, focus rings, including blurred surfaces.
- [ ] 200% zoom and 320 px reflow without clipping or two-way scroll.
- [ ] Reduced motion honored.
- [ ] Untested items (e.g. screen reader with Persian voice) explicitly marked "not verified".
