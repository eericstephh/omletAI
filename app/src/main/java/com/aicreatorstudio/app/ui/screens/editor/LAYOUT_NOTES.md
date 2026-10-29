# EditorScreen.kt — CapCut-style layout (2026-08-28)

## Layout order (top → bottom)
1. `FloatingTopBar` (back, title, export) — fixed height, fillMaxWidth
2. `BoxWithConstraints` (video preview) — `weight(1f, fill = true)`, `heightIn(max = 360.dp)`
3. `PlaybackControls` (time / iOS slider / time + play/pause + volume) — natural height
4. `Timeline` (time ruler + tracks) — natural height
5. `BottomToolbar` — natural height, docked at the bottom of the Column

The sub-panel sheet (Audio, Text, Effects, etc.) is a sibling of the Column inside
the outer Box, positioned `align(Alignment.BottomCenter)` with `padding(bottom = 80.dp)`
so it sits above the BottomToolbar.

## Why weight(1f) on the preview
Without `weight(1f)`, the preview would be its `heightIn(max = 360.dp)` always, and
the empty space would be wasted between preview and playback controls. With weight,
the preview expands to fill whatever vertical space remains after TopBar, Playback,
Timeline, and Toolbar claim their natural heights.

## Key subroutines (all in EditorScreen.kt)
- `EditorScreen` (L117) — Hilt entry, wires ViewModel → EditorScreenContent
- `EditorScreenContent` (L208) — the actual layout
- `FloatingTopBar` (L940) — iOS-style top nav
- `iOSZoomButton` (L1031) — small circular icon button used in timeline
- `PlaybackControls` (L1058) — scrubber + transport row
- `Timeline` (L1221) — multi-track CapCut-style timeline
- `TrackRailLabel` / `TimeRuler` / `TrackRow` / `OverlayTrackRow` / `SubtitleTrackRow` — timeline sub-components
- `formatTime` / `formatRulerTime` / `distanceBetween` / `minZoomForDuration` / `MAX_TIMELINE_SCALE` — helpers
- `DraggableTextOverlay` (L1637) — draggable text on the preview

## How to extend
- New timeline tracks: add a Row + TrackRow in `Timeline()` body and a new TrackRailLabel
- New sub-panel: add a new `when (selectedPanel)` branch in the sub-panel sheet (~L865)
- New bottom toolbar item: edit `BottomToolbarItems` in `BottomToolbar.kt` and add a `when (actionId)` in the `onAction` callback at L735
