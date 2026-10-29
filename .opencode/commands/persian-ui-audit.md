---
description: Read-only, evidence-based audit of Persian/RTL UI quality (layout, typography, writing, localization, bidirectional text, accessibility, performance, tests).
agent: Persian-UI-Specialist
---

Run a **read-only** Persian/RTL UI audit of the current project. Scope or focus requested by the user (may be empty): `$ARGUMENTS`

## Rules

- **Do not modify, create or delete any project file.** Implement fixes only if the user later asks for it explicitly.
- Separate **Verified** findings (you read the code or ran a command) from **Hypotheses** (you could not render or run the UI). Never present a hypothesis as fact.
- Do not install dependencies or change configuration. Run only existing, non-destructive project checks (tests, lint, type-check). If you skip a check, say why.

## Procedure

1. **Discover the project.** Identify framework, styling system, component library, routing, i18n approach, where `lang`/`dir` are set, and available test/lint/build commands. Read `AGENTS.md`/README if present. If the project has no UI code, say so and stop.
2. **Load skills** with the `skill` tool as needed: `persian-rtl-layout`, `persian-typography`, `persian-ux-writing`, `persian-localization`, `bidirectional-content`, `persian-ui-quality`, `persian-accessibility`.
3. **Inspect** each area below using file search and reading. Cite file paths and line numbers.
   1. RTL layout (root `lang`/`dir`, physical vs logical CSS, mirrored icons, flex/grid reversal hacks, focus order)
   2. Mixed-direction text (URLs, emails, phones, IDs, brand names, `dir="auto"`, isolation)
   3. Persian typography (font coverage, fallbacks, licensing, `letter-spacing`, line-height, clipping, truncation)
   4. Persian UX writing (ي/ك vs ی/ک, نیم‌فاصله, tone, terminology, error/empty/confirmation copy)
   5. Localization and numerals (Intl usage, calendar, digits, currency/toman-rial, parsing of Persian digits, canonical stored values)
   6. Accessibility (labels, error association, keyboard, focus visibility, contrast, target size, reduced motion, `lang` on foreign segments)
   7. Responsive behavior (narrow widths, long strings, overflow)
   8. Visual consistency (tokens, spacing, radii, hierarchy)
   9. Interaction states (loading, empty, error, success, disabled, selected, focus)
   10. Performance (font loading/subsetting, blur/shadow cost, layout shift)
   11. Tests and documentation (existing tests, RTL/Persian coverage, docs)
4. **Run** relevant existing checks if they can be run safely, and record the exact command and real result.
5. **Report** using the format below.

## Report format

1. **Executive summary** (3–6 sentences, overall state, biggest risk)
2. **Scores (0–5)** for: RTL layout, Persian typography, writing quality, localization formatting, mixed-direction text, accessibility, test coverage. Give one line of evidence per score. If an area could not be inspected, write `n/a` rather than guessing.
3. **Findings** grouped under **Critical**, **High**, **Medium**, **Low**. Each finding: ID, title, file path/component (with line if known), status (**Verified** / **Hypothesis**), user impact, reproduction steps if known, recommended fix.
4. **Top five improvements**, ranked by impact vs effort.
5. **Staged remediation plan** (small, ordered steps; note which agents/skills help).
6. **Checks executed** — table with columns: check, exact command, result. Group into **Passed**, **Failed**, **Not run** (with reason).
7. **Manual checks still required** (e.g. real device rendering, screen reader with a Persian voice, font license review).

End by asking whether the user wants any of the fixes implemented.
