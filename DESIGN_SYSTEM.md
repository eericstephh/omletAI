# Apple Glass Design System (2026-11 Redesign)

> Evolved violet brand, cool blue-charcoal neutrals, Apple-subtle
> glassmorphism: one frosted chrome bar per screen, static glass
> elsewhere, hairlines + top highlight, no stacked blurs.
> 2026-11 changes: cooler surfaces (#090B11 family), CTA gradient
> `AccentDeep → AccentCtaBottom` (AA-safe white text — fixes the old
> Export-pill failure), cyan secondary accent, `GlassChrome` bar,
> floating glass toolbar dock, 44dp header actions, Persian-safe
> typography (tracking 0), restrained motion (no-bounce 0.95–0.97),
> unified selected wash, Role.Button semantics.

A premium Apple/iOS-inspired glass design system for the AI Creator Studio
Android Video Editor.

## Color Palette

### Surfaces (midnight-violet dark mode)

- `#0A0E1A` — `CinematicBlack`: app background
- `#161C30` — `CinematicSurface`: panels, cards, toolbars
- `#1F2640` — `CinematicElevated`: elevated surfaces
- `#2C3554` — `CinematicHighest`: highest elevation

### Glass / Translucent Layers (static tints over content)

- `GlassUltraThin` — `#12172A` @72% — faint scrim
- `GlassThin` — `#12172A` @77% — compact controls, toolbar dock
- `GlassRegular` — `#12172A` @85% — top bars, sheets (via `GlassChrome`)
- `GlassThick` — `#12172A` @90% — prominent overlays
- `Hairline` rgba(255,255,255,0.12) / `HairlineSubtle` rgba(255,255,255,0.07) — 1dp borders

> Engine note: Compose has no backdrop sampling and `Modifier.blur`
> blurs a bar's own content too, so all glass is translucent-tint based
> (sharp content, cheap). `GlassBackdrop` exists for true blurred
> backdrops behind sheets. Max one chrome bar per screen.

### Accent Colors (evolved violet duotone + cyan)

- `#8B5CF6` — `AccentPrimary` / `iOSBlue`: brand violet (icons/text on dark, glows)
- `#A78BFA` — `AccentBright` / `iOSBlueBright`: text-on-dark ONLY, never under white text (2.72:1)
- `#7C3AED` — `AccentDeep` / `iOSBlueDeep`: CTA gradient top (white = 5.70:1)
- `#6D28D9` — `AccentCtaBottom`: CTA gradient bottom (white = 7.10:1)
- `#22D3EE` — `AccentCyan` / `iOSCyan`: secondary accent — eyebrows, info (9.8:1 on surface)
- `#6366F1` — `iOSIndigo`: decorative gradient start
- `#30D158` / `#FF453A` / `#FF9F0A` — success / destructive / warning (status only)

### CTA Gradient (the ONLY gradient under white text)

`ctaBrush()` = vertical `AccentDeep → AccentCtaBottom`. Used by
`iOSPillButton.Filled`, Export pills (TopBar + legacy), play button,
Home Create button. White passes AA at both ends (5.70 / 7.10).

### Text Colors (AA-safe on Black/Surface)

- `#F8FAFC` — Primary (18.4 / 16.2:1)
- `#9AA3B2` — Secondary (~6.6:1 on surface; body/helper safe)
- `#8B93A1` — Tertiary (5.46:1 — **Black/Surface/Elevated only**, never on Highest)
- `#5B6472` — Quaternary (decorative only, never text)
- `#E2E8F0` — Toolbar labels
- Disabled/coming-soon text: `LabelPrimary` @65% (7.7:1) — never 45% for enabled text

### Timeline Track Colors
- `#5E5CE6` — Video (indigo)
- `#FF9F0A` — Audio/Music (orange)
- `#FF375F` — SFX (pink)
- `#30D158` — Overlay (green)
- `#BF5AF2` — Subtitles (purple)

## Typography (Persian-safe)

> All base styles keep `letterSpacing = 0.sp`: positive tracking breaks
> Arabic-script joining. (Non-zero tracking appears ONLY on Latin brand
> strings, e.g. the Splash "AIerix" wordmark.) Line heights are +2sp
> generous vs SF to survive fontScale 1.3x without clipping Persian
> ascenders (Vazirmatn/Sahel/Shabnam).

| Style | Size | Weight | Tracking |
|-------|------|--------|----------|
| displayLarge | 34sp | Bold | 0 |
| displayMedium | 28sp | Bold | 0 |
| displaySmall | 25sp | SemiBold | 0 |
| headlineLarge | 22sp | Bold | 0 |
| headlineMedium | 20sp | SemiBold | 0 |
| headlineSmall | 18sp | SemiBold | 0 |
| titleLarge | 17sp | SemiBold | 0 |
| titleMedium | 15sp | SemiBold | 0 |
| titleSmall | 13sp | Medium | 0 |
| bodyLarge | 17sp | Normal | 0 |
| bodyMedium | 15sp | Normal | 0 |
| bodySmall | 13sp | Normal | 0 |
| labelLarge | 15sp | SemiBold | 0 |
| labelMedium | 13sp | Medium | 0 |
| labelSmall | 11sp | Medium | 0 |

## Shape System (Squircle-style)

| Component | Radius |
|-----------|--------|
| extraSmall | 10dp |
| small | 13dp |
| medium | 16dp |
| large | 22dp |
| extraLarge | 28dp |
| capsule | 50% (pill) |
| iconBox | 14dp |
| chip | 12dp |
| card | 18dp |
| sheet | 22dp |
| modal | 24dp |
| thumb | 8dp |

## Core Components

### `GlassSurface` / `GlassChrome`
Frosted-glass primitives: translucent tint + 1dp hairline + top-edge
highlight. `GlassSurface` for sheets, cards, docks (static).
`GlassChrome` for the ONE floating bar per screen (Home header, Editor
top bar). `GlassBackdrop` for true blurred backdrops. Never stack blurs.

**Materials:** `UltraThin` (scrim) — `Thin` (docks, compact) —
`Regular` (bars, sheets) — `Thick` (overlays).

### `iOSSlider`
Custom iOS-style slider:
- Pill-shaped track with gradient-filled active portion
- White thumb with soft outer glow on drag
- Tap-to-seek and drag-to-scrub gestures
- Haptic-style press feedback

### `iOSPillButton`
Capsule CTA with the AA-safe `ctaBrush()` gradient and 5 styles:
- Filled (CTA gradient, white text)
- Tinted (translucent violet, bright-violet text on dark)
- Gray (neutral)
- Destructive (red)
- Success (green)

### `iOSIconControl`
Rounded-square or circle icon button with glass background, scale animation on press, and selected state with accent glow.

### `iOSToolbarItem`
Vertical icon+label pill with radial glow when selected. Used in the bottom toolbar.

## Editor Screen Architecture

```
┌──────────────────────────────────────┐
│ [Glass Navigation Bar]               │  ← FloatingTopBar
│  • Back    Editing: Project Name  Export│
├──────────────────────────────────────┤
│                                      │
│      [Video Preview - rounded]       │  ← BoxWithConstraints
│                                      │
├──────────────────────────────────────┤
│ [Glass Playback Controls]            │  ← PlaybackControls
│  00:00 ━━━━●━━━━━ 01:30              │  ← iOSSlider
│       ⏮  [▶]  🔊 ━━━●━               │
├──────────────────────────────────────┤
│ [Glass Timeline]                     │  ← Timeline
│  Video ▓▓▓▓▓▓▓▓                     │
│  Music ▓▓▓▓▓                        │  ← TrackRow w/ rounded thumbs
│  Subs  ▓▓▓▓▓▓▓                      │     + neon selection border
├──────────────────────────────────────┤
│ [Glass Sub-Panel Sheet]              │  ← Sub-panel sheets (when open)
│  Title                  [X]          │
│  ─────────────────────────           │
│  Panel content...                    │
├──────────────────────────────────────┤
│ [Glass Bottom Toolbar]               │  ← BottomToolbar
│  ⚏ ⊕ ⊗ ⎘ │ ⏏ ⏱ ▶ ⊜ ⧉ ⛶ ♪ T 𝕋 ⇄      │  ← iOSToolbarItem
└──────────────────────────────────────┘
```

## Micro-interactions (restrained premium)

- **Gentle press** 0.95–0.97 scale, `DampingRatioNoBouncy` — no bounce
- **Light haptic** `TextHandleMove` on taps (not `LongPress`)
- **Selected = accent wash 14% + hairline 35% + semibold** — no neon glow/shadow
- **Fade + slide** panel sheets; toolbar `Crossfade 180ms`
- **44dp minimum** touch targets; `Role.Button + onClickLabel + selected/disabled` semantics

## File Structure

```
ui/
├── theme/
│   ├── Color.kt          # Apple Glass palette + ctaBrush()
│   ├── Type.kt           # Persian-safe typography (tracking 0)
│   ├── Shape.kt          # Squircle radius system + spacing/elevation
│   └── Theme.kt          # M3 ColorScheme bindings
├── components/
│   ├── Glass.kt          # GlassSurface, GlassChrome, GlassBackdrop
│   ├── iOSButton.kt      # iOSPillButton, iOSIconControl, iOSToolbarItem
│   ├── iOSSlider.kt      # Custom iOS-style slider
│   └── ...
└── screens/
    ├── home/HomeScreen.kt    # Glass header, CTA pills, project cards
    └── editor/
        ├── EditorTopBar.kt      # Glass bar + CTA Export pill
        ├── ContextualToolbar.kt # Floating glass dock + group sheet
        └── ...
```
