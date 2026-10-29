# Omelet — UI/UX Redesign: "Dark Studio"

**Date:** 2026-10-16
**Scope:** Visual identity + design system + the highest-visibility surfaces of the Android app.
**Status:** Implemented and verified by build/test. Visual verification on a device was **not** possible (no emulator/device attached) — see §8.

---

## 1. Audit and identified problems

### 1.1 Repository shape (measured)

| Fact | Value | How measured |
|---|---|---|
| Kotlin files in `main` | 326 | `Get-ChildItem -Recurse -Filter *.kt app/src/main/java` |
| Lines of Kotlin in `main` | 100,331 | same, `Measure-Object -Line` |
| Unit test suites | 216 | `app/build/test-results/testDebugUnitTest/*.xml` |
| Unit tests | 2925 (0 failures / 0 errors / 0 skipped) | same |
| Version control | **none** — `git` reports *"not a repository"* | `git status` |
| Compose BOM | 2024.12.01 (Material3 1.3.x) | `gradle/libs.versions.toml:9` |
| Locales shipped | `values`, `values-fa`, `values-ar` | `app/src/main/res` |
| Debug APK | 115.43 MB | `app/build/outputs/apk/debug/app-debug.apk` |

Layout: `ui/theme/` (color/type/shape/theme), `ui/components/` (shared widgets), `ui/screens/{splash,home,importvideo,settings,editor}`, `ui/navigation/`. The editor is the bulk of the code (`EditorScreen.kt` alone is 6,254 lines; `SubtitleStudioPanel.kt` is 2,388).

### 1.2 What the audit found

The app was **not** a blank slate. It already carried an "Apple Glass, 2026-01" dark theme with semantic token names and a documented, contrast-verified palette. So the work was a **refinement, not a replacement** — the brief's own instruction ("inspect before editing, keep the diff focused").

**Current design system (before this change)**
- Palette: indigo-tinted near-black (`#0A0E1A`), surfaces `#161C30` / `#1F2640`, bordered by a translucent white hairline, violet `#8B5CF6` brand + `#A78BFA` bright accent, and **cyan `#22D3EE` as a second accent**.
- Type: a custom scale one step above Material's defaults (17/15/13sp body) with Persian-safe zero letter-spacing.
- Shapes: squircle-approximating radii 10/13/16/22/28dp; `iOSShapes` for component-level corners.
- Glass: `GlassSurface`/`GlassChrome` with blur + tint + hairline + top highlight.

**Defects found (all with file:line evidence)**

| # | Problem | Evidence |
|---|---|---|
| P1 | **Wrong brand on the splash.** The first screen a user sees read **"AIerix"** with the tagline *"Next-Gen AI Video Editor"*, while the product is **Omelet / املت** (`strings.xml:2`). | `SplashScreen.kt` (pre-change) `GradientWordmark` + `contentDescription = "AIerix logo"` |
| P2 | **Home was authored in English inside a Persian-default, RTL app.** Copy bypassed the project's own `t()` localisation table entirely. | `HomeScreen.kt` (pre-change): `"WELCOME"`, `"Your Projects"`, `"Create New Project"`, `"No projects yet"` |
| P3 | **Secondary accent failed AA.** Cyan `#22D3EE` on the elevated surface measured **3.99:1**. | computed; same figure as the file's own comment on `AccentCyan` |
| P4 | **Error red failed AA for body text.** `#EF4444` on surface = **4.49:1** (AA needs 4.5). | computed |
| P5 | **Decorative excess.** Home ran a 4-blob animated "purple motion" field plus a twinkle-particle layer; feature cards used animated gradient posters with a shimmer sweep. | `HomeScreen.kt` `AmbientPurpleMotion`; `FeatureCard.kt` shimmer/`Animatable` |
| P6 | **Raw colours scattered through screens** — 145 hex literals outside `ui/theme/`. | counted 223 total in `main`, 78 of them inside `ui/theme/` |
| P7 | **Dead visual code that invites edits in the wrong place.** `EditorTopBar.kt` (206 lines) has **zero callers**; the live bar is `EditorTopBarSection` inside `EditorScreen.kt`. `MembershipGlassCard.kt` is likewise unreferenced. | `grep 'EditorTopBar('` → only its own definition; ditto `MembershipGlassCard(` |
| P8 | **English screen-reader labels** on the editor's primary chrome (`"Back"`, `"Undo"`, `"Redo"`, `"Export"`, `"Close"`) in a Persian-default app. | `EditorScreen.kt:2381,2410,2417,2470,4847` |
| P9 | **`ImportVideoScreen` used default Material chrome** — a stock `Scaffold`/`TopAppBar`/`Button` — unlike every other screen, and in English. It is the destination of Home's primary CTA. | `ImportVideoScreen.kt` (pre-change) |

Widely-flagged but **not** acted on: the editor's 27 tool panels contain ~209 hardcoded English strings; that is a content/localisation project of its own (see §10).

---

## 2. REA: what was actually available

**REA could not be used, and nothing was fabricated.**

Verified, not assumed:
- `search` over the tool catalogue returns **no REA / jadx / frida / ghidra / apk-inspect / adb / emulator tools**. Only `browser.*` and `opencode.*` are registered.
- `tools.opencode.list_mcp_resources({})` → `{ "resources": [], "templates": [] }` — **no MCP servers at all**, so no REA MCP server.
- `Get-Command rea` → nothing; no `rea` CLI on `PATH`.
- The `reverse-engineer-anything` skill is installed as **instructions only**; its own text states it "does not register the REA MCP server, install analysis engines, or add tools to an already-running agent."

The skill also states: *"For ordinary analysis of a complete source repository, use normal repository tools and do not run REA readiness or provider commands."* Since the full source is present, REA is not the right instrument here regardless.

**Conclusion:** no binary/device inspection was performed. No reference app was decompiled, no screenshots of third-party apps were captured, and no proprietary assets or branding were copied.

**What stood in for reference research:** the existing repository's own design documentation, which is unusually complete for this project — `docs/design-principles.md`, `docs/DESIGN-CHECKLIST.md` (Persian RTL release checklist), `docs/UX_IA_AUDIT_AND_PROPOSAL.md` (658 lines on editor toolbar architecture). These are *project* sources of truth, not third-party research, and are treated as such.

**Separating fact from interpretation:** §1 is measured fact. The design direction in §3 is interpretation — a defensible reading of "cinematic, precise, calm, professional", not a finding.

---

## 3. Design principles and final decisions

### 3.1 Five principles for Omelet

1. **Near-neutral ink, one voice of colour.** Surfaces are charcoal with only a faint cool tint. Violet carries brand and selection; cyan is demoted to genuinely informational signals. This directly answers the brief's "no excessive neon or strong purple glow" and de-AI-ifies the look.
2. **Contrast is a constraint, not a preference.** Every text/surface pair was measured. White text is permitted on exactly one background: the CTA gradient.
3. **Video first.** Chrome recedes so footage dominates. Real backdrop blur stays on *floating* chrome only (top bar, nav, sheets); flat panels get a solid surface + a 1dp border instead.
4. **Persian is the authoring language.** Copy is written RTL-first, not English text dropped into an RTL frame. Digits follow one convention, and Persian letters/half-spaces are preserved byte-exactly.
5. **Motion is optional.** One staggered entrance, no idle loops. Decorative motion is suppressed when the system asks for no animation.

### 3.2 Colour tokens — final values and measured contrast

Palette moved from indigo-tinted to **charcoal-ink**, and the second accent from cyan to the brief's violet.

| Token | Before | After | Role |
|---|---|---|---|
| `CinematicBlack` | `#0A0E1A` | `#0B0D12` | app background |
| `CinematicSurface` | `#161C30` | `#141821` | panels, cards |
| `CinematicElevated` | `#1F2640` | `#1B202B` | elevated |
| `CinematicHighest` | — | `#242B38` | highest |
| `SurfaceSunken` *(new)* | — | `#08090D` | timeline troughs, letterbox |
| `SurfaceInset` *(new)* | — | `#10141C` | one step below a card |
| `BorderSubtle` *(new)* | (`Hairline` only) | `#2B3240` | solid panel/card edges |
| `BorderStrong` *(new)* | — | `#3A4354` | emphasised edges |
| `LabelPrimary` | `#F8FAFC` | `#F5F7FB` | text |
| `LabelSecondary` | `#9AA3B2` | `#B0B8C8` | supporting copy |
| `LabelTertiary` | `#8B93A1` | `#8993A5` | muted |
| `AccentPrimary` | `#8B5CF6` | `#8B5CF6` | fills |
| `AccentBright` | `#A78BFA` | `#A78BFA` | text/icons on dark |
| `AccentSecondary` *(new)* | — | `#8B7CFF` | second accent |
| `AccentCyan` | `#22D3EE` | `#22D3EE` | **info only** |
| `StatusError` | `#EF4444` | `#F87171` | error |
| `StatusSuccess` | `#22C55E` | `#34D399` | success |
| `StatusWarning` | `#F59E0B` | `#FBBF24` | warning |

Measured WCAG ratios (worst case = the lightest surface, `CinematicElevated`):

| Pair | Before | After |
|---|---|---|
| primary text on elevated | 14.24 | **15.20** |
| secondary text on elevated | 5.86 | **8.18** |
| tertiary text on elevated | 4.81 | **5.26** |
| secondary accent on surface | 3.99 ✗ | **5.43 ✓** |
| error on surface | 4.49 ✗ (AA) | **6.42 ✓** |
| white on CTA gradient | 5.70 / 7.10 ✓ | 5.70 / 7.10 ✓ (unchanged) |

Every text role passes 4.5:1 on all three surfaces. The one deliberately-forbidden combination is preserved and re-documented: **never white text on `AccentBright`** (2.72:1) — white is for the `ctaBrush()` gradient only.

**RTL and colour in the same breath:** every rule above is direction-agnostic. Nothing in the palette or the new components uses physical left/right; layout uses Compose logical start/end and `LocalLayoutDirection`.

### 3.3 Typography

- **Kept** the project's Persian-tested sizes (17/15/13sp body — deliberately above Material's defaults, because Arabic-script x-height is small and shrinking it costs legibility). No size regression.
- **Improved** line heights to ~1.45–1.5× (body 25/23/20sp) so Persian ascenders and descenders survive `fontScale` 1.3× without clipping.
- **Added** `OmeletType` (`overline`, `timecode`, `caption`) so an eyebrow label or timecode reads identically everywhere instead of each screen hand-rolling a `TextStyle`.
- **Letter-spacing stays 0** on every Persian-bearing style. Only the Latin/numeric `timecode` gets tracking (0.2sp).

### 3.4 Shape and motion

- Added `AppRadius` (8/12/16/20/24/28 + pill) as one source of truth, so a card on Home and a card in a sheet cannot drift apart.
- Added `Motion` (120/200/320/480ms with standard/emphasised/exit easings) — restrained by default.
- **Reduced motion is real, not aspirational:** `Theme.kt` reads `Settings.Global.ANIMATOR_DURATION_SCALE` and provides `LocalMotionEnabled`. `FeatureShowcaseRow` and `SplashScreen` consume it and skip decorative animation when the user has asked the system to remove animations.

### 3.5 Glass policy

Real blur is expensive and was judged over-used. The rule now documented in `Color.kt`: **one backdrop-blurred layer per screen maximum** (via `GlassChrome` for top bars/sheets). Everything else is a static translucent tint + hairline. `GlassSurface`/`GlassChrome` internals were left intact (their tints are token-driven and therefore re-tuned automatically).

---

## 4. Files changed

| # | File | Change |
|---|---|---|
| 1 | `ui/theme/Color.kt` | **rewritten** — Dark Studio ramp, new semantic tokens (`SurfaceSunken`, `SurfaceInset`, `BorderSubtle`, `BorderStrong`, `AccentSecondary`, `Status*`), all legacy symbols preserved as aliases; measured-contrast table in the header |
| 2 | `ui/theme/Type.kt` | **rewritten** — line heights, `OmeletType` extra roles |
| 3 | `ui/theme/Shape.kt` | added `AppRadius`, `Motion` |
| 4 | `ui/theme/Theme.kt` | **rewritten** — new dark/light schemes, `outline = BorderSubtle`, error family, `LocalMotionEnabled` |
| 5 | `ui/components/ProjectCard.kt` | **rewritten** — bordered card, bounded destructive affordance, Persian meta line |
| 6 | `ui/components/EmptyState.kt` | **rewritten** — icon tile + optional primary action (`actionLabel`/`onAction`) |
| 7 | `ui/components/FeatureCard.kt` | **rewritten** — gradient posters + shimmer replaced by calm tinted-icon cards; honours reduced motion |
| 8 | `ui/components/iOSButton.kt` | tokenized the Gray/Tinted/Destructive/Success styles (raw iOS greys removed) |
| 9 | `ui/components/ComingSoonScreen.kt` | **rewritten** — dark chrome + tokens + Persian copy |
| 10 | `ui/components/OmeletCookingAnimation.kt` | illustration literals hoisted into one documented `OmeletIllustration` block (artwork deliberately *not* recoloured) |
| 11 | `ui/screens/home/HomeScreen.kt` | **rewritten** — crystalised header, CTA hierarchy, Persian copy via `t()`, calm static backdrop |
| 12 | `ui/screens/splash/SplashScreen.kt` | **rewritten** — brand corrected to املت + AI, neon double-glow removed, reduced-motion aware |
| 13 | `ui/screens/importvideo/ImportVideoScreen.kt` | **rewritten presentation** — design-system chrome + Persian copy; all picker/permission logic untouched |
| 14 | `ui/navigation/NavGraph.kt` | placeholder copy localised via `t()` |
| 15 | `ui/screens/editor/EditorScreen.kt` | 4 live-chrome screen-reader labels localised |
| 16 | `ui/screens/editor/EditorTimeline.kt` | 6 raw literals → tokens (ruler ticks, tile gradient, playhead, clip fill) |
| 17 | `ui/screens/editor/subtitle/SubtitleStudioPanel.kt` | 7 raw literals → tokens (panel wells, danger state) |
| 18 | `settings/LocalizedStrings.kt` | en + ar entries for all new Home / Import / placeholder keys |

Raw hex literals **outside** `ui/theme/`: **145 → 117** (−28). Literals *inside* `ui/theme/` rose from 78 → 88 because the token files now define more tokens — which is the point.

---

## 5. Screens/components redesigned

- **Home** — the flagship. Replaced an animated 4-blob + particle field with a single static gradient and one faint brand glow. Header became a glass chrome bar with an RTL-correct stacked wordmark (Persian name and the Latin `AI` rendered as two runs so bidi can never reorder them). Actions now have a clear hierarchy: primary CTA pill on the `ctaBrush()` gradient, subordinate outlined pill beneath. Added a project count chip and an empty state with a real next step.
- **Project card** — solid surface + 1dp `BorderSubtle` edge, 56dp thumbnail with a graceful no-thumbnail fallback, and a delete affordance that is a *bounded, tinted* button with its own label, so a destructive tap can't be confused with "open".
- **Feature showcase** — the six real capabilities, each a calm card with a tinted icon tile. Verified every feature listed is actually reachable from the editor; nothing advertises non-existent functionality.
- **Splash** — brand fixed (§1 P1), double glow + blurred duplicate wordmark removed.
- **Import video** — now matches the app instead of stock Material; Persian copy.
- **Coming-soon placeholder** — dark chrome instead of a default `TopAppBar`.
- **Editor** — token alignment on the timeline (ruler ticks, clip fill, playhead) and subtitle panel wells, plus Persian screen-reader labels on the live top bar.
- **Buttons** — the four legacy styles now use semantic tokens, so `Gray`, `Tinted`, `Destructive` and `Success` all follow one palette.

---

## 6. Accessibility and RTL improvements

- **Contrast:** four failures fixed (P3, P4 and two tertiary-text roles); all text now ≥4.5:1 on the lightest surface. The forbidden white-on-`AccentBright` rule is preserved and re-documented with measured numbers.
- **Reduced motion:** `LocalMotionEnabled` derived from the platform animator scale, consumed by the splash and the feature grid.
- **Screen-reader labels:** Persian labels on the editor's Back/Undo/Redo/Export/Close, and on every new control (`onClickLabel`, delete-by-name). The app now announces Persian to a Persian user instead of the old English strings.
- **RTL correctness:** Home, Import and ComingSoon were authored in Persian and are laid out with logical start/end. The bidi hazard in the brand lockup is solved structurally (two text runs in an ordered `Row`) rather than with manual string tricks.
- **Touch targets:** the new header controls are 44dp; primary/secondary actions are 54dp tall.
- **Text integrity (byte-verified):** every Persian string written this session was checked for `U+200C` (ZWNJ) and `U+FFFD`. Result: **ZWNJ present, zero U+FFFD** in all seven files (e.g. `LocalizedStrings.kt` 32 ZWNJ, `HomeScreen.kt` 5, `FeatureCard.kt` 12). Persian `ی`/`ک` (U+06CC / U+06A9) are used throughout; Arabic `ي` appears only inside Arabic translations.
- **Digits:** one convention (Latin, matching `Formatters`), documented in `ProjectCard.kt`. Persian does not pluralise a noun after a numeral, so `"3 ویدیو"` is correct rather than a special-cased `"۱"`.

---

## 7. Verification evidence

All commands run from the repository root. `PowerShell` reports exit code 1 when Gradle writes to stderr, so results were read from artifacts rather than the exit code.

| Check | Command | Result |
|---|---|---|
| Kotlin compile | `gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| Unit tests | `gradlew :app:testDebugUnitTest` | **216 suites, 2925 tests, 0 failures, 0 errors, 0 skipped** |
| Debug APK | `gradlew :app:assembleDebug` | **BUILD SUCCESSFUL**, `app-debug.apk` **115.43 MB** |
| Instrumented-test sources | `gradlew :app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** |

**Baseline vs after:** the unit-test totals are **identical** to the pre-change baseline (2925 / 0 / 0 / 0), and the APK size is unchanged at 115.43 MB. **No existing test expectation was modified** — no test needed to change, and none was weakened.

**Static analysis:** `:app:lintDebug` is **not** reported as passing. It is disabled repo-wide at `app/build.gradle.kts:207-212`, and a prior attempt to enable it failed with a tooling crash (`LLResolutionFacade.findCompiledFirSymbol` via `UastGradleVisitor`, "only works on compiled declarations"), producing no `lint-results-debug.*`. That is a pre-existing tooling limitation, carried over and **not** worked around or claimed as a pass.

**Not claimed:** no device, no screenshots, no visual confirmation, no export run (§8).

---

## 8. Limitations and unverified checks (explicit)

Nothing in this section was tested.

- **No device or emulator is attached**, so there are **no screenshots** and **no visual verification**. Everything visual in this report is derived from code and computed contrast, not from seeing it.
- **Unverified visually:** the new Home layout in a real RTL render; the stacked املت/AI wordmark's bidi result; splash timing at 120Hz and 60Hz; the new ProjectCard delete affordance at real density; ImportVideoScreen's states (loading / selected / permission-denied / error); dialog and sheet behaviour; timeline tick legibility against real video; playhead visibility over bright footage.
- **Persian glyph shaping/wrapping is unverified.** On the JVM, `android.graphics` is stubbed (`unitTests.isReturnDefaultValues`), and there is no Robolectric, so joining, ZWNJ behaviour and line-breaking cannot be exercised here. ZWNJ *bytes* were verified; *rendering* was not.
- **`fontScale` 1.3× clipping** was designed for (line heights raised) but not measured on a device.
- **Contrast figures** are WCAG 2.x relative-luminance maths. They do not model OLED gamma, ambient light, or Android's own dynamic-dim behaviour.
- **The editor was not restyled.** The 27 tool panels' visual language, the contextual toolbar chips, the preview overlay and the export dialog were left alone apart from token swaps and a11y labels. See §10.
- **The subtitle studio / template picker was not redesigned.** Only its raw colours were tokenised. Active-word highlighting, word timing, Persian shaping and transcription logic were **not touched** — correctly, per the brief.
- **No functionality change was intended or observed**, but this rests on tests and compilation, not on a device walkthrough. Login/API settings, project persistence, export and navigation were not exercised at runtime.

---

## 9. Design decisions worth recording

- **`AccentSecondary` (`#8B7CFF`) did not replace cyan.** Using two near-identical violets would have flattened the palette and removed the ability to signal "informational" distinctly. Cyan survives at its measured 9.4:1 but is restricted to information, not decoration.
- **The illustration was not recoloured.** `OmeletCookingAnimation` draws a grey pan, gold film-strip omelette and orange flames. Those are art direction, not UI semantics; forcing them through UI tokens would have made the artwork worse to satisfy a rule meant for screens.
- **Dead files were flagged, not deleted.** `EditorTopBar.kt` and `MembershipGlassCard.kt` are unreferenced (§1 P7). Deleting production files is a product decision, not a styling one, so it is left to the owner with the evidence attached.
- **The editor's ~209 English strings were not mass-translated.** Doing so would be a large, unreviewable content change that the brief explicitly warns against, and it could not be visually checked here.

---

## 10. Recommended next steps

**P0 — verify on hardware.** Install `app-debug.apk`, then check: Home in Persian RTL; the املت/AI lockup; splash timing; delete confirmation; Import video's four states; timeline against bright and dark footage; `fontScale` 1.3×. Capture screenshots. Until this happens, §8 stands.

**P1 — finish the design-system adoption.**
1. `EditorScreen.kt` still holds 33 raw literals; `ReframePanel` (10), `BeatSyncPanel` (8), `HighlightPanel` (7), `VoiceEnhancePanel` (7), `AutoSilenceCutPanel` (7) follow. Replace with tokens.
2. Redesign the editor chrome properly: contextual toolbar chips, preview overlay, and the export dialog, on the new tokens.
3. Tokenise `AppUpdateSheet`, `OmeletCookingAnimation`'s call site, and the remaining home widgets.

**P1 — localisation debt.** The editor's ~209 hardcoded English strings should be converted to the `t()` table (or explicitly declared out of scope). Home, Import, Splash and the placeholder now set the pattern.

**P2 — the subtitle/template picker.** It is the app's signature feature and was only tokenised. It deserves the same first-class treatment as Home: browsable categories, clearer progress and error states, and a polished active-template selection — **without** touching transcription or word timing.

**P2 — dead code.** Decide on `EditorTopBar.kt` (206 lines) and `MembershipGlassCard.kt`; both are verified unreferenced and are exactly the kind of duplicate that causes future edits in the wrong file.

**P2 — lint.** Investigate a narrow, non-crashing lint subset, or repair the `UastGradleVisitor` crash, so a static-analysis signal exists at all.

**Carried over from the subtitle work (still open, untouched here):** `docs/MANUAL_TEST_CHECKLIST.md` is stale (says "17 templates"; corrupted Persian tab label); the `1.12f` `activeWordScale` authoring cap; and the `EditorViewModel.kt` mojibake (comments only).

---

## Continued in Phase 2

The points above marked **P1 - finish the design-system adoption** and
**P1 - localisation debt** (the editor chrome and the export dialog), plus
**P2 - the subtitle/template picker**, were addressed in
[`ui-redesign-2026-10-phase-2-editor.md`](ui-redesign-2026-10-phase-2-editor.md).
That report is the current record for the editor; this one remains the record for the
token layer, Home, Splash and Import.
