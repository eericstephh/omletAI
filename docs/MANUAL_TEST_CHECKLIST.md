# PHASE 12 - Manual Device Testing Checklist

## Overview
17 manual verification scenarios for the Subtitle Template Engine.

## Prerequisites
- Install `app-debug.apk` on device (API 24+)
- Open editor, add subtitle overlay, open Studio panel

---

## 1. Template Catalog Loads
- [ ] "??????" tab shows 17 templates grouped by category
- [ ] Each shows Persian name + English subtitle

## 2. Template Selection
- [ ] Tap "Karaake Classic" - preview updates (white text, yellow active word)
- [ ] Tap "Glitch Cut" - preview updates (black box, red active word)

## 3. Font Tab Isolation
- [ ] Change font to Lalezar - preview updates
- [ ] Switch tabs and back - font persists
- [ ] Reset button restores template default

## 4. Color Tab Isolation
- [ ] Tap red swatch - text turns red
- [ ] Enter hex #00FF00 and apply - text turns green
- [ ] Reset button restores default color

## 5. Shadow Tab Isolation
- [ ] Tap "outline" - stroke appears
- [ ] Adjust width slider
- [ ] Tap "glow" - glow effect appears
- [ ] Reset button works

## 6. Highlight Tab Isolation
- [ ] Tap "colorChange" - active word highlights
- [ ] Tap "progressBar" - bar appears under text
- [ ] Reset works

## 7. Background Tab Isolation
- [ ] Tap "solidBox" - box appears
- [ ] Tap "gradientBox" - gradient appears
- [ ] Tap "letterboxBars" - cinematic bars
- [ ] Reset works

## 8. Motion Tab Isolation
- [ ] Change entry to "pop" - text pops in
- [ ] Adjust duration slider
- [ ] Select easing preset
- [ ] Reset works

## 9. Position Tab Isolation
- [ ] Tap bottom-left grid - text moves
- [ ] Adjust X/Y sliders
- [ ] Scale slider at 1.5x
- [ ] Danger zone warning at Y>82%
- [ ] Reset works

## 10. Cross-Tab Independence
- [ ] Modify font, color, shadow, position on one template
- [ ] Switch between all tabs - all changes persist
- [ ] Each reset only affects its own axis

## 11. Template Re-selection
- [ ] Modify current template
- [ ] Select different template - overrides reset to new defaults

## 12. RTL Text
- [ ] Persian text renders RTL correctly
- [ ] ZWNJ words do not split

## 13. Export Parity
- [ ] Set gradient + letterbox + position + easing
- [ ] Export 5s video
- [ ] Verify output matches preview

## 14. Gradient Angle
- [ ] Set gradientBox with 45-degree angle
- [ ] Preview shows diagonal gradient
- [ ] Export matches

## 15. Progress Bar
- [ ] Select progressBar, set orange color
- [ ] Bar fills in sync with audio
- [ ] Export matches preview

## 16. GLITCH Effects
- [ ] Select Glitch Cut template
- [ ] Glitch jitter is deterministic across replays

## 17. Stability
- [ ] Open/close Studio 10x rapidly - no crash
- [ ] Switch tabs rapidly - smooth
- [ ] Apply 5 templates in sequence - no ANR

---

## Sign-off
| # | Pass | Notes |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |
| 6 | | |
| 7 | | |
| 8 | | |
| 9 | | |
| 10 | | |
| 11 | | |
| 12 | | |
| 13 | | |
| 14 | | |
| 15 | | |
| 16 | | |
| 17 | | |
