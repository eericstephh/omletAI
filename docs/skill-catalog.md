# Skill Catalog

Use this catalog to select focused skills for the current task. Do not load every skill for every request. Combine a few relevant skills rather than producing a bloated process.

## Product discovery and UX
- `product-framing` — design brief, outcomes, constraints, acceptance criteria
- `user-goals-jtbd` — motivation, jobs-to-be-done, workarounds
- `ux-research` — research planning and assumption validation
- `journey-mapping` — multi-stage user journeys and service touchpoints
- `information-architecture` — content hierarchy and navigation
- `task-flows` — happy path, alternate paths, error recovery
- `usability-heuristics` — expert heuristic review
- `wireframing` — structural layout before polish
- `usability-testing` — test scripts and evidence synthesis
- `design-evaluation` — release gates and acceptance criteria

## Visual design foundations
- `visual-hierarchy` — emphasis, grouping, composition
- `typography` — type scales, readability, language support
- `color-systems` — semantic palette and states
- `layout-grid-spacing` — grid, containers, rhythm, density
- `icons-imagery` — iconography, illustration, image behavior
- `design-tokens` — token taxonomy and theming
- `design-systems` — system architecture and governance
- `premium-product-design` — cohesive high-craft visual direction

## Interaction and content
- `interaction-design` — affordances, states, feedback, recovery
- `microinteractions-motion` — purposeful motion and reduced motion
- `forms-validation` — forms and validation UX
- `navigation-patterns` — navigation models and responsive adaptation
- `onboarding` — first-run and activation
- `empty-loading-error` — async and data states
- `content-design` — labels, instructions, errors, UX writing
- `notifications-feedback` — alerts, status, progress, toasts
- `search-filter-sort` — search and data discovery
- `trust-privacy-security-ux` — permissions, privacy, destructive actions

## Platform and product patterns
- `responsive-design` — content-driven responsive behavior
- `mobile-ux` — mobile-first and native app patterns
- `web-saas` — multi-user web apps and subscription products
- `dashboard-data-density` — operational dashboards and data-dense UI
- `data-visualization` — charts and quantitative communication
- `ecommerce-conversion` — product discovery and checkout
- `landing-pages` — product marketing pages
- `rtl-localization` — RTL, bidirectional text, localization
- `dark-mode` — multi-theme behavior
- `game-ui` — HUD, game menus, controls, aspect ratios
- `mobile-editor-workflows` — creative tools, timeline, scrubbing, undo
- `pricing-subscription-ux` — plan comparison, billing, cancellation
- `forms-data-tables` — enterprise tables and dense forms

## Engineering and quality
- `component-design` — component contracts, variants, states
- `frontend-implementation` — repository-aware implementation
- `design-handoff` — implementation-ready specifications
- `design-critique` — prioritized expert review
- `accessibility` — semantic, keyboard, contrast, assistive technology
- `performance-aware-ui` — assets, loading, rendering, perceived performance
- `visual-regression` — visual and interaction QA
- `design-evaluation` — acceptance criteria and release gates

## Task-to-skill routing examples

| Task | Start with | Add when relevant |
|---|---|---|
| New product interface | product-framing, user-goals-jtbd, information-architecture, wireframing, visual-hierarchy | design-tokens, component-design, accessibility, responsive-design |
| Existing UI redesign | usability-heuristics, design-critique, visual-hierarchy | design-systems, frontend-implementation, visual-regression |
| Mobile app screen | mobile-ux, interaction-design, visual-hierarchy | onboarding, forms-validation, accessibility |
| SaaS dashboard | web-saas, dashboard-data-density, information-architecture | data-visualization, search-filter-sort, forms-data-tables |
| Persian/Arabic interface | rtl-localization, typography, responsive-design | content-design, accessibility |
| Design system | design-systems, design-tokens, component-design | dark-mode, accessibility, design-handoff |
| Timeline-based editor | mobile-editor-workflows, interaction-design, component-design | responsive-design, empty-loading-error, visual-regression |
| Landing page | product-framing, landing-pages, visual-hierarchy | content-design, ecommerce-conversion, performance-aware-ui |
| Accessibility review | accessibility, usability-heuristics | responsive-design, forms-validation, visual-regression |
| Subscription flow | pricing-subscription-ux, trust-privacy-security-ux, forms-validation | content-design, web-saas, usability-testing |

## Selection rule
Load only the skills that materially affect the requested task. For a narrow change, use 1–3 skills. For a large feature or redesign, use a compact set across discovery, design, implementation, and verification. If the request is implementation-only, do not force a full research phase; still inspect the product context and preserve behavior.
