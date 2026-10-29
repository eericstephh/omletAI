---
name: persian-ui-quality
description: Raise overall visual and interaction quality of Persian interfaces - design tokens, hierarchy, spacing and type scales, consistent components, full interaction-state coverage, responsive behavior, performance, restrained glassmorphism, and how to test with real Persian content. Use when designing, implementing or reviewing UI quality beyond pure RTL mechanics.
---


# کیفیت رابط کاربری فارسی (Persian UI Quality)

## When to use
Designing or reviewing component visuals, tokens, states, responsive behavior, motion, glass effects, performance, or testing a Persian UI with realistic content.

## Instructions

### 1. Start from what exists
Read the codebase's current design language (tokens, theme, components, screenshots) before proposing changes. Extend it; do not rewrite the product's look without a reason tied to user needs. Separate **design proposals** from **code changes** in your report.

### 2. Tokens over magic numbers
Centralize in CSS variables / theme config:
- **Color**: semantic roles (`--color-bg`, `--color-surface`, `--color-text`, `--color-text-muted`, `--color-border`, `--color-accent`, `--color-danger`, `--color-success`, `--color-focus`) for light and dark; do not use raw hex in components.
- **Type scale**: 5–7 sizes with matched Persian line-heights (`persian-typography`).
- **Spacing**: a 4 or 8 px scale; logical properties for inline spacing.
- **Radius, elevation, borders, motion** (duration/easing) as tokens; one radius family per product.
- Tokens must be direction-agnostic: name by role (`inline-start`), not side (`left`).

### 3. Hierarchy and composition
- One primary action per view; secondary actions visually quieter.
- Group by proximity, separate by whitespace before lines/boxes. Persian text is visually dense: increase inter-section spacing and avoid long full-width lines (aim for roughly 45–75 characters per line in reading content).
- Align to the start edge; avoid center alignment for multi-line Persian paragraphs.
- Avoid template-looking UIs: unify a deliberate type pairing, restrained palette, and real content instead of lorem ipsum.

### 4. Interaction-state matrix (required per interactive component)
default · hover · focus-visible · active/pressed · disabled · loading · selected/checked · error · success · empty (collections) · skeleton/placeholder.
- Disabled elements still need legible contrast and an explanation when the reason is not obvious.
- Loading preserves layout (skeletons or fixed height) to avoid layout shift.
- Every async action has loading, success and failure copy (`persian-ux-writing`).

### 5. Responsive behavior
- Mobile first; verify 320, 375, 768, 1024, 1280+ widths and landscape.
- Prefer container/fluid layouts and wrapping over hiding content; turn wide tables into stacked cards or scroll containers with a visible affordance.
- Test with the **longest realistic Persian strings** and mixed Latin content, not only short labels.

### 6. Glassmorphism, restrained
Use `backdrop-filter` for a few floating layers (nav bars, sheets, popovers), never as page-wide decoration.
```css
.glass {
  background: color-mix(in srgb, var(--color-surface) 82%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-border) 70%, transparent);
  -webkit-backdrop-filter: blur(16px) saturate(1.4);
  backdrop-filter: blur(16px) saturate(1.4);
}
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .glass { background: var(--color-surface); }
}
@media (prefers-reduced-transparency: reduce) { .glass { background: var(--color-surface); backdrop-filter: none; } }
@media (forced-colors: active) { .glass { background: Canvas; border-color: CanvasText; backdrop-filter: none; } }
```
Rules: never place long Persian reading text on blur; verify text contrast against the worst-case backdrop (light and dark images); limit blur radius and the number of simultaneous blurred layers (cost grows on low-end phones); always provide the opaque fallback.

### 7. Motion
- Animate `transform` and `opacity`; avoid animating layout properties.
- Keep durations short (about 120–250 ms for UI feedback); respect `prefers-reduced-motion`.
- Directional motion (slides, carousels) must follow reading direction in RTL.

### 8. Performance
- Fonts: WOFF2, subset, limited weights, `font-display` (see `persian-typography`).
- Avoid heavy shadows/filters on large or scrolling surfaces; prefer simple borders and subtle shadows.
- Lazy-load below-the-fold images, set explicit width/height; avoid layout shift.
- Add a dependency (e.g. Jalali date library, icon pack) only after checking bundle impact.
- Measure rather than guess when tools exist (Lighthouse, bundle analyzer); otherwise label performance items as hypotheses.

### 9. Testing with real content
Build a fixture of realistic Persian data: long names, Persian+Latin mixes, large numbers, Jalali dates, empty and error states. Run existing unit/e2e/visual tests; if Playwright or similar is present, add RTL snapshots for key screens at 2–3 widths.

## Procedure
1. Inventory tokens and components; note inconsistencies (duplicated colors, ad-hoc spacing, multiple radii).
2. Walk the state matrix for the 5 most-used components.
3. Review 3 key screens at 3 widths in RTL with long content.
4. Review blur/shadow/animation usage against §6–§8.
5. Propose changes grouped as: token-level, component-level, screen-level.

## Acceptance checklist
- [ ] New/changed styles use tokens; no unexplained raw values.
- [ ] State matrix complete for touched components.
- [ ] Key screens verified at 320/768/1280 with long Persian strings.
- [ ] Glass effects limited, contrast-checked, with opaque fallback and reduced-transparency/forced-colors handling.
- [ ] Motion uses transform/opacity and respects reduced motion.
- [ ] Font and dependency cost justified; existing tests run and results recorded.
