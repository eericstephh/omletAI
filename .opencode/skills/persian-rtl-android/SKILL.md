---
name: persian-rtl-android
description: Rules for Persian (Farsi) and RTL Android apps built with Jetpack Compose - layout direction, Persian digits, fonts, Jalali dates, mixed-direction text
---
Use when the app UI or data is Persian/Arabic/RTL.

## Layout
- Manifest: `android:supportsRtl="true"`. Compose mirrors layouts automatically; never hardcode left/right. Use `start`/`end` (`Modifier.padding(start = ...)`, `Alignment.Start`, `TextAlign.Start`).
- Directional icons (back arrow, chevrons) need `Modifier.autoMirrored` / `Icons.AutoMirrored.*`.
- Force RTL only if the whole app is Persian: wrap the root in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`; otherwise follow the device locale.
- Add `res/values-fa/strings.xml`; keep all user-facing text in resources, never in code.

## Text
- Font: bundle Vazirmatn (or another Persian font) in `res/font` and set it in the Material3 `Typography`. Do not rely on the default font for Persian.
- Numbers: store and compute in Latin digits; format for display with `NumberFormat.getInstance(Locale("fa"))`. Parse user input after converting Persian/Arabic digits (۰-۹, ٠-٩) to 0-9, including the Persian decimal separator.
- Mixed Persian + English/numbers in one line: wrap the Latin part with `BidiFormatter` or add U+200F (RLM) so punctuation does not jump sides.
- Currency/amounts: group with thousands separators in the same formatter; keep the unit (ریال/تومان) as a separate Text so it does not flip.

## Dates
- `java.time` has no Jalali calendar. Use `android.icu.util.PersianCalendar` (API 24+) for conversion/formatting, or a maintained Jalali library. Store dates as UTC epoch or ISO Gregorian and convert only for display.

## Review checklist
- No `Modifier.padding(left/right)`, `Gravity.LEFT`, or `TextAlign.Left/Right` in the diff.
- Text fields accept Persian input and show a correct cursor/alignment.
- Screenshots/previews checked with `@Preview(locale = "fa")`.
