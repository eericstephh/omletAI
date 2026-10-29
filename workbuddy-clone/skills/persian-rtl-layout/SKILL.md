---
name: persian-rtl-layout
description: Build and review correct right-to-left (RTL) layouts for Persian interfaces - root lang/dir, CSS logical properties, flex/grid, navigation, forms, tables, icon mirroring, focus order, scrolling and mobile. Use when creating or auditing any Persian/RTL screen.
---


# چیدمان راست‌به‌چپ (Persian RTL Layout)

## When to use
Any time a screen, component or stylesheet must render Persian (or any RTL language) correctly, or when an existing UI shows mirrored/misaligned elements in RTL.

## Instructions

### 1. Set direction at the root, once
- Document language and direction: `<html lang="fa" dir="rtl">`. Use `lang="fa-IR"` only if region matters (e.g. Intl formatting); `fa-AF` for Afghan Dari.
- In multilingual apps set both attributes **dynamically from the active locale** (not hard-coded) and keep them in one place (root layout / app shell).
- Prefer the `dir` attribute over CSS `direction`; CSS `direction` is for rare presentational cases.
- Isolated Latin-only islands (code, URLs) get their own `dir="ltr"` (see `bidirectional-content`).

### 2. Use logical CSS, not physical
Replace physical properties with logical ones so one stylesheet serves both directions.

| Physical (avoid) | Logical (prefer) |
|---|---|
| `margin-left/right` | `margin-inline-start/end` |
| `padding-left/right` | `padding-inline-start/end` |
| `left` / `right` | `inset-inline-start` / `inset-inline-end` |
| `text-align: left/right` | `text-align: start/end` |
| `border-left` | `border-inline-start` |
| `float: left` | `float: inline-start` |
| `border-top-left-radius` | `border-start-start-radius` |
| `width/height` (when direction-dependent) | `inline-size` / `block-size` |

Tailwind: use `ms-*`, `me-*`, `ps-*`, `pe-*`, `start-*`, `end-*`, `text-start`, `text-end`; use `rtl:` / `ltr:` variants only for what logical utilities cannot express. Do not mass-rewrite a working codebase: fix per component, starting with shared ones.

### 3. Let flex and grid follow direction
- Flex/grid already flip with `dir`. **Do not** use `flex-direction: row-reverse` to "fake" RTL: it double-reverses in RTL and desynchronizes visual order from DOM/tab order.
- Keep **DOM order = visual order = focus order**. Avoid CSS `order`, absolute positioning tricks and `row-reverse` that change reading order.

### 4. Directional icons and images
- **Mirror** icons that express direction: back/forward arrows, chevrons in lists and breadcrumbs, "next/previous", indent/outdent, reply/undo/redo, progress that moves along reading direction.
- **Do not mirror**: logos, brand marks, checkmarks, clocks, charts' data shapes, images with text, phone/handsets, most media-playback glyphs (decide once per product and be consistent).
- Implement with a class: `[dir="rtl"] .icon-directional { transform: scaleX(-1); }` (or `:dir(rtl)` where browser support is acceptable). Prefer an icon-set that ships RTL variants.

### 5. Components
- **Navigation / menus**: primary nav starts on the inline-start (right). Dropdown/popover alignment uses `inset-inline-start`; check collision logic in the popover library for RTL.
- **Forms**: labels aligned to start; required/optional markers and helper text at the inline end of the label; icons inside inputs use logical padding; email/URL/phone/code inputs are `dir="ltr"` (see `bidirectional-content`).
- **Tables**: first column on the right, numeric columns right-aligned *or* consistently aligned by design; sticky columns use `inset-inline-start`.
- **Tabs, steppers, breadcrumbs, pagination**: order flows right to left; arrows follow direction.
- **Drawers, sheets, toasts**: slide in from the inline-start/end edge, not "left/right". Animations using `translateX` need a direction factor: `--dir: 1` / `--dir: -1` via `[dir="rtl"]`.
- **Charts**: decide and document axis direction. Time axes in Persian products usually flow right to left; numerals and axis labels may need LTR isolation. Many chart libraries need an explicit RTL option.
- **Carousels/scrollers**: in RTL, `scrollLeft` starts at 0 and becomes **negative** when scrolling toward the end in modern browsers. Never hand-roll `scrollLeft` math without testing RTL; prefer `scroll-snap` and `scrollIntoView`.

### 6. Mobile and overflow
- Test 320, 375, 768, 1280 px widths. Persian strings are often longer than English labels: allow wrapping (`min-width: 0` on flex children, `overflow-wrap: anywhere` for long tokens).
- Honor safe-area insets; back-swipe and edge gestures originate at the inline-start edge in RTL.
- Horizontal scroll must not be accidental: check `overflow-x` on body at every breakpoint.

## Procedure
1. Find where `lang`/`dir` are set; list every `dir`/`direction` override.
2. Search for physical properties: `rg -n "margin-(left|right)|padding-(left|right)|\bleft:|\bright:|text-align:\s*(left|right)|float:\s*(left|right)|row-reverse|translateX|scrollLeft"`.
3. Classify each hit: legitimate (intentionally physical, e.g. a video seek bar) vs. directional bug.
4. Fix at the shared level first (tokens, base components, utilities), then call sites.
5. Verify by toggling `dir` at runtime and comparing LTR/RTL screenshots.

## Acceptance checklist
- [ ] `lang` and `dir` set on `<html>` from the active locale.
- [ ] No unexplained physical `left/right` properties in new or touched code.
- [ ] No `row-reverse` used to emulate RTL; DOM order equals visual and focus order.
- [ ] Directional icons mirror; non-directional icons do not.
- [ ] Menus, tables, forms, steppers, drawers and toasts verified at 320 px and desktop.
- [ ] No accidental horizontal scrolling.
- [ ] Carousels/scrollers tested in RTL.
