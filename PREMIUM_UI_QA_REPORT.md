# Premium UI Redesign — Validation & QA Report (validation-only pass)

> Scope: verify the premium redesign. No business logic, navigation, ViewModel,
> media/export/AI, model-download, Tapsell, or dependency changes were made
> in this pass. No code was refactored to fix findings — corrections are
> proposed only, awaiting approval where they would alter the visual direction.
> Date: 2026-10-10. No git repository exists (`not a git repository`),
> so scope was verified via file timestamps + content inspection instead of a diff.
> Every number below was re-computed or re-executed in this pass; nothing is
> carried over on trust from any earlier draft.

## 1. Environment & commands executed

| Item | Value |
|---|---|
| OS / shell | Windows, PowerShell 5.1 (tool wrapper) |
| Java | OpenJDK 25.0.4.1 LTS (Microsoft build) |
| Gradle | Wrapper 8.13 (project `gradlew.bat`) |
| Android SDK | `C:\android` (`local.properties`) |
| `adb` | Present at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, **works** (`adb devices` runs) — but **zero devices attached** |
| Emulator | **Not installed** (`$LOCALAPPDATA\Android\Sdk\emulator\emulator.exe` absent, no `emulator` on PATH) |
| Network | `--offline` builds (dependencies cached) |

Commands executed (all in repo root, this pass):

1. `.\gradlew.bat :app:assembleDebug --offline -x lint` → **BUILD SUCCESSFUL** (2 executed, 47 up-to-date, ~5s; fully cached).
2. `.\gradlew.bat :app:testDebugUnitTest --offline --tests "…ContextualToolbarSpecTest" --tests "…TimelineDelegateTest" --tests "com.aicreatorstudio.app.export.*"` → **BUILD SUCCESSFUL**.
3. `.\gradlew.bat :app:testDebugUnitTest --offline --tests "com.aicreatorstudio.app.ads.ExportAdGateTest"` → **BUILD SUCCESSFUL**.
4. `.\gradlew.bat :app:lintDebug --offline` → **BUILD FAILED — environment/toolchain fault, not a code finding** (see §2; failure reproduced this pass in 1m 7s).
5. Contrast math via `python3` (sRGB → linear, alpha compositing, WCAG ratio). Formula: `L = 0.2126R+0.7152G+0.0722B`, `ratio = (hi+0.05)/(lo+0.05)`.
6. `adb devices` via full SDK path → `List of devices attached` (empty). No app launch, no screenshots.

## 2. Build / test / lint results

### Build — PASS (verified, re-executed)

- `:app:assembleDebug --offline -x lint`: **BUILD SUCCESSFUL**. Everything up-to-date; no new warnings in output. APK packaging completed (`:app:packageDebug`, `:app:assembleDebug`).

### Unit tests — PASS, targeted subset (verified, re-executed; XML results read)

| Suite | tests | failures | errors | skipped |
|---|---|---|---|---|
| `ContextualToolbarSpecTest` (toolbar IA contract) | 27 | 0 | 0 | 0 |
| `TimelineDelegateTest` (timeline state — untouched by redesign) | 11 | 0 | 0 | 0 |
| `ExportAdGateTest` (`ads` package — untouched) | 3 | 0 | 0 | 0 |
| All `export.*` suites (18 suites: RenderGraph, CompositionTimeline, Caption*, Export*, Upscale*, Grade, AiExportGeometry…) | 218 | 0 | 0 | 0 |
| **Total observed** | **259** | **0** | **0** | **0** |

**Not run:** the full `:app:testDebugUnitTest` suite (100+ files) and any `androidTest` instrumentation (requires a device). No claim is made about them.

### Lint — NOT VERIFIED (toolchain fault, reproduced this pass)

- `:app:lintDebug --offline` failed after 1m 7s inside lint itself:
  `AndroidLintWorkAction … Unexpected failure during lint analysis …
  findFirCompiledSymbol only works on compiled declarations`
  (Kotlin K2/FIR crash while visiting **build scripts**, `UastGradleVisitor.visitBuildScript` — stack confirms it crashes on a Gradle script UClass, before any source reporting).
- This is a **toolchain bug in the lint version vs. the Kotlin plugin**, unrelated to the UI files. No lint findings for `Color/Type/Shape/Glass/iOSButton/EditorTopBar/ContextualToolbar` could be observed.
- Recommendation: re-run lint after upgrading AGP/Kotlin lint artifacts, or run `lintDebug` on CI with network; do not treat this pass as "lint clean".

### Scope check — PASS (verified, timestamp-based, no git)

Most-recently modified UI files (by `LastWriteTime`):

| File | Modified | In redesign scope? |
|---|---|---|
| `ui/screens/editor/ContextualToolbar.kt` | 2026-10-10 01:51 | ✅ yes |
| `ui/screens/editor/EditorTopBar.kt` | 2026-10-10 01:49 | ✅ yes |
| `ui/components/iOSButton.kt` | 2026-10-10 01:48 | ✅ yes |
| `ui/components/Glass.kt` | 2026-10-10 01:45 | ✅ yes |
| `ui/theme/Shape.kt` | 2026-10-10 01:45 | ✅ yes |
| `ui/theme/Type.kt` | 2026-10-10 01:45 | ✅ yes |
| `ui/theme/Color.kt` | 2026-10-10 01:44 | ✅ yes |
| `DESIGN_SYSTEM.md` | 2026-10-10 01:52 (same session) | ✅ yes (docs) |
| `EditorScreen.kt` (00:55), `EffectPresets.kt` / `ColorGradingMath.kt` (10-09), `EditorTimeline.kt`, `EffectsPanel.kt` … | earlier sessions | ❌ untouched by redesign |

Out-of-scope areas confirmed untouched (timestamps predate the 01:44–01:51 redesign window): navigation (`AppDestinations.kt` 08-28, `NavGraph.kt` 09-17), ViewModels (latest `EditorViewModel.kt` 10-09 10:45), `app/build.gradle.kts` (10-05), `settings.gradle.kts` (09-16), `gradle.properties` (09-02), `AndroidManifest.xml` (09-17).

`PREMIUM_UI_QA_REPORT.md` (this file) is the only new file in this pass.

## 3. Contrast verification (WCAG AA, alpha-composited, re-computed this pass)

Thresholds: **4.5:1** normal text (< 18pt / < 14pt bold), **3:1** large text & non-text UI (icons, hairlines, focus).

### 3.1 Dark theme — redesigned pairs (verified by calculation)

| # | Foreground → Background (files/tokens) | Ratio | Verdict |
|---|---|---|---|
| C1 | `LabelPrimary #F8FAFC` on `CinematicBlack #0B0C10` (TopBar title, `EditorTopBar.kt:85`) | **18.68:1** | ✅ PASS |
| C2 | `LabelPrimary` on `CinematicSurface #16181F` (sheet titles, `ContextualToolbar.kt:391`) | **16.94:1** | ✅ PASS |
| C3 | `LabelSecondary #9AA3B2` (NEW) on `CinematicSurface` (helper/empty text) | **6.97:1** | ✅ PASS — was 3.73 (C4) |
| C4 | `LabelSecondary #64748B` (OLD, pre-redesign) on `CinematicSurface` — baseline only | 3.73:1 | ❌ (historical; confirms the fix was needed) |
| C5 | `LabelTertiary #7D8590` on `CinematicSurface` — `TransitionPanel.kt:256` unselected `labelSmall` names | **4.75:1** | ✅ PASS normal text (margin 0.25; keep under review — any darkening regresses it) |
| C6 | `LabelQuaternary #5B6472` on `CinematicSurface` | 2.96:1 | ⚠️ FAIL if used for text — **verified unused for text**: only defined in `Color.kt:33`; no text usage found. Safe as decorative-only **provided it stays that way** |
| C7 | `LabelToolbar #E2E8F0` on `CinematicSurface` (legacy dock labels, `iOSButton.kt`) | 14.38:1 | ✅ PASS |
| C8 | White `#FFFFFF` on Export gradient bottom `iOSBlue #8B5CF6` (`EditorTopBar.kt` Export pill) | 4.23:1 | ❌ **FAIL normal text** (proposal P-C1) |
| C9 | White on Export gradient top `iOSBlueBright #A78BFA` (same pill, top edge) | 2.72:1 | ❌ **FAIL normal text** (same proposal) |
| C10 | `iOSBlueBright #A78BFA` text on `CinematicSurface` (Tinted button style, `iOSButton.kt:103`) | 6.51:1 | ✅ PASS |
| C11 | `iOSRed #EF4444` on dark (destructive trailing action, `ContextualToolbar.kt:308`; 4.71 on surface, 5.19 on black) | **4.71:1** | ✅ PASS normal text |
| C12 | Disabled white @45% on `CinematicBlack` (`SquircleIconButton`/`ExportPillButton` disabled alpha) | 4.52:1 | ✅ PASS (margin 0.02 — borderline) |
| C13 | Disabled white @45% on `CinematicSurface` | 4.49:1 | ⚠️ **borderline FAIL by 0.01** — acceptable for *disabled* (WCAG-exempt), but 45% alpha must not be reused for any enabled text |
| C14 | Idle tile icon White @92% / tile label `LabelPrimary` @85% / dock label `LabelToolbar` @95% on surface | 15.08 / 12.42 / 13.05:1 | ✅ PASS |
| C15 | Sheet dismiss «بستن» `LabelPrimary` @75% / empty-state @65% on surface (`ContextualToolbar.kt:409,422`) | 9.88 / 7.70:1 | ✅ PASS |
| **C16 (NEW)** | Coming-soon badge «به‌زودی» `LabelPrimary` **@45%** on surface (`ContextualToolbar.kt:272,284`) | **4.34:1** | ❌ **FAIL normal text** (`labelSmall` 11sp) — this is *enabled visible* text, not disabled (proposal P-C2) |

Notes:

- C12/C13 assume white text composited over the bare surface. Real chips composite over slightly lighter glass, which raises the ratio marginally — the 4.49 figure is the conservative floor.
- Splash tagline `SplashTagline #9AA3B2` on `SplashBackground #0B0C10` ≈ 7.5:1 ✅ (same token family as C3 on a darker base).

### 3.2 Light theme (pre-existing, out of redesign scope — informational)

- `iOSLightLabelSecondary` (60% `#0F172A`) on white: **4.70:1** ✅ PASS.
- `iOSLightLabelTertiary` (30% `#0F172A`) on white: **1.96:1** ❌ FAIL if used for small text. Predates the redesign, unchanged; flagged only (P-C3).

### 3.3 Failing pairs requiring a decision (no auto-fix applied)

- **F1 — Export pill white-on-violet (C8/C9).** Gradient `iOSBlueBright(#A78BFA) → iOSBlue(#8B5CF6)` with white `labelLarge` text fails AA at both ends (2.72 / 4.23 < 4.5). Primary CTA → highest-value contrast defect.
  - *Smallest targeted correction (proposal P-C1, needs visual approval):* darken the gradient to `iOSBlueDeep #7C3AED → iOSBlue #8B5CF6` — white on `#7C3AED` = **5.70:1** ✅, violet identity kept (`#6D28D9` = 7.10:1 is safer but visibly darker).
  - *Not applied* — materially alters the approved gradient; awaiting direction.
- **F2 — Disabled 4.49 floor (C13).** No action (disabled is WCAG-exempt); noted so 45% is never reused for enabled text.
- **F3 — Coming-soon «به‌زودی» badge 4.34 (C16, new in this pass).** Informative enabled text below AA.
  - *Smallest targeted correction (proposal P-C2):* raise badge text/icon alpha 45% → 65% (7.70:1 ✅, matches the empty-state treatment at `ContextualToolbar.kt:422`) — needs visual approval as it brightens the "locked" look.

## 4. Compose accessibility audit (static inspection — verified present / absent)

Inspected: `iOSButton.kt`, `Glass.kt`, `EditorTopBar.kt`, `ContextualToolbar.kt`, `iOSSlider.kt`, `ZeroSnapSlider.kt`.

### 4.1 Clickable controls — role / labels / states (verified in code)

| Check | Result |
|---|---|
| `iOSPillButton` has `role=Role.Button`, `onClickLabel=label`, `semantics{disabled()}` when disabled (`iOSButton.kt:128-139`) | ✅ PASS |
| `iOSIconControl` has `role=Role.Button`, `onClickLabel=contentDescription` (`iOSButton.kt:212-216`) | ✅ PASS (caveat A2) |
| `iOSToolbarItem` has `role=Role.Button`, `onClickLabel=label`, `semantics{selected}` (`iOSButton.kt:307,331-335`) | ✅ PASS |
| `ToolbarTile` has `role=Role.Button`, `onClickLabel=chip.label`, `semantics{selected}` (`ContextualToolbar.kt:233-244`) | ✅ PASS |
| `TrailingIconAction` (Delete/Duplicate) has `role + onClickLabel` (`ContextualToolbar.kt:322-329`) | ✅ PASS |
| `SquircleIconButton` / Export pill: `role + onClickLabel + disabled()` (`EditorTopBar.kt:140-149,178-187`) | ✅ PASS |
| A1: `iOSPillButton` / `SquircleIconButton` / `TrailingIconAction` decorative icons use `contentDescription=null` **inside** a labelled `Role.Button` | ✅ correct pattern |
| A2: `iOSIconControl(contentDescription=null)` default — a null call site yields an unlabeled button (`iOSButton.kt:170`). No such call site in redesigned files, but the API permits it | ⚠️ minor hardening opportunity (require non-null label) — proposal only |
| A3 (NEW): coming-soon `ToolbarTile` sets `enabled=false` on clickable but exposes **no** `disabled()` semantics (`ContextualToolbar.kt:233-244`) — TalkBack announces a tile that swallows taps with no disabled state | ⚠️ minor; proposal P4 |
| Destructive Delete still icon-only with no in-dialog confirm at this layer | ⚠️ known, **out of scope** (no behavior change allowed); reconfirmed present, not regressed |

### 4.2 Custom sliders & timeline gestures — TalkBack (verified absent; no blind fix per constraints)

- `iOSSlider.kt` (Canvas + `detectTapGestures`/`detectHorizontalDragGestures`, touch target **28dp**, `:56`): **no** `semantics{}`, `progressBarRangeInfo`, `stateDescription`, or `contentDescription`. Confirmed by full-file read.
- `ZeroSnapSlider.kt` (same technique, 28dp, snap-to-zero haptic tick): **no** semantics. The snap tick has no spoken/text equivalent.
- `EditorTimeline` scrub/trim/pinch gestures, `RoiSelectorOverlay` handles (~20dp hit), `SpeedCurvePanel` double-tap-to-delete: unchanged, still semantics-free.
- Per the mission constraint ("do not add `progressBarRangeInfo` blindly"), **nothing was added**. Correct semantics depend on per-slider value semantics (e.g. Volume 0–200% vs. symmetric ±100 with a zero snap needing `stateDescription="snapped to zero"`), which requires targeted follow-up with TalkBack validation, not a blanket modifier.

### 4.3 Touch targets (verified in code)

- New minimums: `SquircleIconButton` **44dp** (`EditorTopBar.kt:136`), Export pill padding 10dp vertical (≈44dp), `ToolbarTile` icon box **44dp** (`ContextualToolbar.kt:249`), `TrailingIconAction` **44dp** (`:311`), sheet dismiss padding 10dp vertical (≈44dp). ✅
- Remaining sub-48dp: sliders **28dp** (`iOSSlider.kt:56`, `ZeroSnapSlider.kt:81`), ROI handles ~20dp. Deliberately left (gesture behavior change needs validation). 44dp meets Apple HIG and WCAG 2.5.8 (24px); M3 48dp is the ideal — documented gap.

### 4.4 Truncation (new static observation)

- `ToolbarTile` label (`ContextualToolbar.kt:280-292`): `maxLines=1` but **no** `overflow=Ellipsis` (Compose default = Clip). A long Persian label in a 60dp tile clips mid-glyph with no ellipsis. The legacy `iOSToolbarItem` (`iOSButton.kt:376-378`) and the design comment at `:366-369` both specify Ellipsis — the new tile does not implement it. Proposal P4 (one-line, visual-neutral).
- `EditorTopBar` title (`EditorTopBar.kt:82-89`): `maxLines=1`, no overflow — same Clip default. Proposal P4.

## 5. Persian & RTL verification (static only — verified; runtime NOT verified)

| Check | Result |
|---|---|
| All 15 `letterSpacing` values in `ui/theme/Type.kt` are `0.sp` (grep-verified, lines 27–133) | ✅ PASS — no Persian-shaping risk from tracking |
| Non-zero `letterSpacing` in `SplashScreen.kt:219,241,252` (`0.08em`/`0.15em`) applies **only** to Latin brand strings ("AIerix", "Next-Gen AI Video Editor") | ✅ PASS — no Arabic-script text affected (verified by read) |
| Line heights +2sp generous vs SF for `fontScale` headroom (`Type.kt:15,26,33,…`) | ✅ PASS (static; runtime 1.3x not verified — no device) |
| Directional icons in touched files: `EditorTopBar` Back/Undo/Redo use `Icons.AutoMirrored.*` (`EditorTopBar.kt:17-19,77,98,105`) | ✅ PASS — RTL-mirroring correct |
| Persian strings use «ی/ک» with correct half-spaces in touched lines («به‌زودی», «بستن», «ابزاری برای این بخش ثبت نشده», `ContextualToolbar.kt:281,407,420`) | ✅ PASS |
| Export CTA label hardcoded English ("Export", `EditorTopBar.kt:200`) — stays Latin under fa-IR locale | ℹ️ observation only; product decision whether to localize |
| Mixed fa/EN labels, truncation, fontScale 1.0/1.3 layout | ❌ **NOT VERIFIED at runtime** — no emulator/device (see §6). Static risk is low, but large-text clipping in 60dp tiles and the TopBar title can only be confirmed on-device |
| `fontScale` hardcodes elsewhere (`SubtitleStudioPanel 0.75f`) | ⚠️ pre-existing, untouched, out of scope — noted |
| **D1 (NEW): `DESIGN_SYSTEM.md` is stale vs. the code it documents** — Typography table lists SF tracking (`0.37sp`/`-0.41sp`/…) but `Type.kt` is all `0.sp` (§5 row 1); Surfaces list `#0D0D0E/#1C1C1E/#2C2C2E/#3A3A3C` but `Color.kt` is `#0B0C10/#16181F/#1E2028/#282A34`; Glass lists "8/12/16/24% white" but code is ~72–90% dark tints; Accents list `#0A84FF/#409CFF/…` but code is the violet `#8B5CF6` family; File-structure lists `IOSTheme.kt`/`IOSType.kt`/`IOSShape.kt`, which **do not exist** (theme dir contains only `Color/Type/Shape/Theme.kt`) | ⚠️ docs-only defect, proposal P3 — a reader implementing from the doc would reintroduce the exact defects the redesign fixed (non-zero tracking, wrong tokens) |

## 6. Runtime visual verification — NOT AVAILABLE (explicit, verified this pass)

- **No emulator, no connected device, no screenshots.** `adb devices` (via the real SDK binary) returns an empty list; no `emulator` binary exists. The app was **not launched**; Home/Import/Editor/panels/Export states were **not visually inspected**.
- **No screenshots are attached, and none are fabricated.** Any prior preview (`preview/editor-preview.html`) is a static mock, not evidence.
- Manual verification steps (exact, for a connected device or emulator):
  1. `adb devices` → device listed; `.\gradlew.bat :app:installDebug`; launch app.
  2. Home: header, Create/Import pills, project list/EmptyState; toggle fontScale 1.0 → 1.3 (Settings → Accessibility) and re-check.
  3. Import: select video → metadata card; deny permission → permission card; attempt >5min file → error copy.
  4. Editor: TopBar (Back/Undo/Redo/Export), playback strip, timeline lanes, tap clip → `ContextualToolbar` chips; open AI group sheet → dismiss via «بستن».
  5. Export dialog: quality picker → Preparing → Exporting % → Success/Failure/Cancel; eyeball the Export pill gradient (F1) and the «به‌زودی» badge (F3).
  6. TalkBack ON: traverse TopBar → toolbar → sheet; confirm each announces a Persian/English label + selected/disabled state; confirm sliders announce (expected: they do NOT yet — §4.2).
  7. RTL: system locale fa-IR; confirm Back/Undo/Redo mirror, Persian labels don't clip at 1.3x, Export pill still legible.

## 7. Regression risks

1. **Behavior preservation is compilation + contract-tested, not runtime-proven.** `assembleDebug` + 259 passing unit tests prove the toolbar IA contract, timeline state, export math, and ad-gate logic — but **zero Compose UI tests exist** (`createComposeRule` grep: no files found) and no instrumentation ran. Navigation/export/timeline behavior "preserved" = no code touched there + build green, **not** an observed run.
2. **Haptic change** (`LongPress` → `TextHandleMove` in touched buttons): same API, lighter pattern; negligible risk, but feel differs — confirm on-device.
3. **Motion change** (bounce → `NoBouncy`, 0.90→0.95–0.97): animation-spec only; cannot break logic, but visual feel needs the manual pass (§6).
4. **Color change blast radius**: `LabelSecondary`/`LabelTertiary`/`Hairline` are referenced across many untouched screens (e.g. `TransitionPanel`, `SettingsScreen`, all editor panels) — intended improvement (all get better contrast), but any screen relying on the old muted look will look brighter. Spot-check Settings + all panels on-device.
5. **Lint blindness**: the lint-tool crash means **zero static-analysis coverage** this pass (accessibility/compose lint rules included). Re-run on a fixed toolchain before release.
6. **Docs drift (D1)**: stale `DESIGN_SYSTEM.md` values could mislead future work into reintroducing fixed defects — correct the doc, not the code.
7. **Untouched known defects** (still present, not regressed): slider/timeline TalkBack gap (§4.2), Export white-on-violet (F1), coming-soon badge contrast (F3), delete-without-confirm, auto-login dialog, light-tertiary 1.96. None were introduced by the redesign.

## 8. Prioritized recommendations (proposals — no code changed)

| Priority | Item | Rationale | Effort |
|---|---|---|---|
| **P1** | Decide F1 Export-pill gradient (darken to `#7C3AED`-anchored, 5.70:1) | Primary CTA fails AA; smallest identity-safe fix computed | S — 1 token + screenshot |
| **P1** | Manual on-device pass per §6 checklist (contrast eyeball, 1.3x, TalkBack traverse) | Everything runtime is unverified | M — needs device |
| **P2** | Fix F3 badge contrast (badge alpha 45% → 65%, 7.70:1) + decide with design (dims "locked" cue) | Enabled informative text fails AA | XS + screenshot |
| **P2** | Targeted slider semantics follow-up (per-slider `progressBarRangeInfo` + `stateDescription`, 28→48dp hit) with TalkBack validation | Largest remaining a11y gap; must not be blind | M |
| **P2** | Fix lint toolchain (`lintDebug` FIR crash) and re-run; enable Compose a11y lint rules | No static coverage this pass | S–M |
| **P3** | Correct stale `DESIGN_SYSTEM.md` values (D1: tracking table, hexes, glass %, accents, file list) to match `Color.kt`/`Type.kt`/disk | Doc actively contradicts the approved code | S |
| **P3** | Run FULL `testDebugUnitTest` suite + relevant `androidTest` on device | Only 259 targeted tests observed; zero UI tests exist | M (device for androidTest) |
| **P3** | Light-theme tertiary token (1.96:1) + pre-existing gaps (delete-confirm, auto-login) | Pre-existing, out of redesign scope | S–M, separate decision |
| P4 | Add `overflow=Ellipsis` to `ToolbarTile` label + TopBar title; expose `disabled()` on coming-soon tiles; harden `iOSIconControl` label non-null | Visual-neutral one-liners; prevent future unlabeled buttons | XS |

---

## الخلاصه فارسی و بررسی‌های دستی باقی‌مانده

**وضعیت:** بازطراحی پریمیوم از نظر استاتیک سالم است — بیلد موفق، ۲۵۹ تست واحد مرتبط پاس (۲۷ + ۱۱ + ۳ + ۲۱۸، صفر خطا)، دامنه تغییرات محدود به ۷ فایل دیزاین‌سیستم + مستندات، و کنتراست متن ثانویه از ۳٫۷ به ۷٫۰ ارتقا یافته است. هیچ منطق تجاری تغییر نکرد و هیچ کدی در این پاس اصلاح نشد.

**یافته‌های مهم (بدون اصلاح خودکار):**

1. دکمه Export (مهم‌ترین CTA) با متن سفید روی گرادیان بنفش روشن، کنتراست ۲٫۷–۴٫۲ دارد و AA را رد می‌کند — پیشنهاد: تیره‌کردن گرادیان به `#7C3AED` (کنتراست ۵٫۷، هویت حفظ می‌شود). منتظر تأیید بصری است.
2. نشان «به‌زودی» با شفافیت ۴۵٪ کنتراست ۴٫۳ دارد و AA را رد می‌کند — پیشنهاد: افزایش به ۶۵٪ (کنتراست ۷٫۷). منتظر تأیید بصری است.
3. اسلایدرهای سفارشی و تایم‌لاین همچنان برای TalkBack بدون معنا هستند — افزودن کورکورانه ممنوع؛ نیازمند فاز جدا با تست واقعی.
4. سند `DESIGN_SYSTEM.md` در چند جدول (tracking، کدهای رنگ، شیشه، فایل‌ها) با کد واقعی ناهماهنگ است — باید سند اصلاح شود، نه کد.

**محدودیت‌های صریح:** lint به‌خاطر باگ تولچین اجرا نشد (خطا بازتولید شد)؛ هیچ اسکرین‌شات/تست دستی/ران‌تایم گرفته نشد (`adb` سالم است ولی دستگاهی متصل نیست و امولاتور نصب نیست)؛ سوئیت کامل تست‌ها و تست‌های UI اجرا نشد (تست Compose در ریپو وجود ندارد).

**چک‌لیست دستی باقی‌مانده (روی دستگاه واقعی):** نصب دیباگ → صفحه Home (فونت ۱٫۰ و ۱٫۳) → ایمپورت (مجوز/خطا) → ادیتور (تاپ‌بار، تایم‌لاین، شیت AI، دکمه بستن) → دیالوگ اکسپورت (هر ۶ حالت) → بررسی چشمی گرادیان Export و نشان «به‌زودی» → گشت TalkBack با اعلان فارسی/انگلیسی → لوکیل fa-IR برای آینه‌ای شدن آیکون‌ها و عدم بریدگی متن در کاشی‌های ۶۰dp.
