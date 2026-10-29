# UI/UX Studio — Project Instructions

When working on a user interface, follow this sequence unless the user explicitly requests a narrower task:

1. **Discover:** inspect repository structure, framework, current UI, routing, styling system, existing components, and available tests.
2. **Frame the problem:** identify user, primary task, business/product goal, platform, constraints, and measurable success criteria. Ask a question only when a missing answer materially blocks safe progress; otherwise state a reasonable assumption.
3. **Choose relevant skills:** consult `docs/skill-catalog.md` and load only the skills that match the platform and task.
4. **Plan:** state the main UX decisions, components/routes affected, risks, and acceptance criteria.
5. **Design:** define hierarchy, layout, states, typography, color, spacing, and responsive behavior before coding.
6. **Implement:** follow existing conventions, reuse components, avoid unnecessary dependencies, and preserve working behavior.
7. **Verify:** run the checks actually available (lint, typecheck, tests, build, browser, screenshots, keyboard/accessibility checks). Distinguish passed, failed, skipped, and unavailable checks.
8. **Review:** inspect edge states, responsive layouts, accessibility, consistency, and regressions.
9. **Report:** summarize changed files, important decisions, verification evidence, and known limitations.

## Quality bar

- Every screen needs a clear primary action and understandable information hierarchy.
- Use design tokens and reusable components rather than scattered arbitrary values.
- Include loading, empty, error, disabled, success, and destructive-confirmation states where relevant.
- Keyboard navigation, visible focus, readable contrast, meaningful labels, and reduced-motion preferences must be considered.
- Avoid generic AI-generated aesthetics: excessive gradients, gratuitous glassmorphism, random card grids, oversized headings, and decorative effects without a product reason.
- Do not invent research findings, user feedback, analytics, test results, or tool capabilities.
- Do not rewrite the architecture or introduce a framework without a concrete need.
- For existing projects, inspect before editing and keep the diff focused.
- If visual verification tools are unavailable, perform the strongest feasible static checks and disclose the limitation.
