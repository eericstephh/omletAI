---
name: persian-localization
description: Implement and review Persian locale behavior - Jalali (Solar Hijri) vs Gregorian dates, digit systems, number/currency/percent formatting with Intl, rial vs toman, week start, time zones, collation and parsing of Persian-digit input while keeping canonical machine values. Use for dates, numbers, money and sorting in Persian products.
---


# بومی‌سازی و قالب‌بندی (Persian Localization)

## When to use
Displaying or accepting dates, times, numbers, prices, percentages, phone numbers, sorting or search in a Persian product.

## Policy first (ask when unknown)
Persian language alone does **not** decide these. Settle them per product and write them in one place (README or docs):

| Decision | Options |
|---|---|
| Calendar shown | Jalali only / Gregorian only / user-selectable / both |
| Digits shown | Persian ۰-۹ / Latin 0-9 / user-selectable |
| Currency unit | rial (IRR) / toman (display convention) / multiple currencies |
| Market | Iran / Afghanistan (Dari) / diaspora (different tz, calendar habits) |
| Week start / weekend | Per market; confirm |

If the product has not decided, **ask the user** and propose a default with its trade-offs. Do not silently choose.

## Instructions

### 1. Keep canonical data canonical
- Store dates as ISO 8601 (UTC instants or `YYYY-MM-DD` calendar dates). **Never store Jalali strings** as the source of truth.
- Store numbers as numbers and money as integer minor units (or a documented canonical unit), phone numbers as E.164 (`+98…`).
- Convert for display at the edge (UI/formatting layer). Convert back on input at the same boundary.

### 2. Format with Intl, be explicit
```js
// Calendar and numbering system are explicit, not inferred from the locale.
const jalali = new Intl.DateTimeFormat('fa-IR-u-ca-persian-nu-arabext', { dateStyle: 'long', timeZone: 'Asia/Tehran' });
const greg   = new Intl.DateTimeFormat('fa-IR-u-ca-gregory-nu-latn',   { dateStyle: 'medium' });
const num    = new Intl.NumberFormat('fa-IR');                       // Persian digits and separators
const numLat = new Intl.NumberFormat('fa-IR-u-nu-latn');             // Persian locale, Latin digits
const pct    = new Intl.NumberFormat('fa-IR', { style: 'percent' });
```
- Numbering systems: `arabext` = Persian ۰-۹, `arab` = Arabic-Indic ٠-٩, `latn` = 0-9.
Observed output (Node 22.22 / ICU 78.2; **re-verify in your runtime**, ICU data differs by browser and version):

| Call | Result |
|---|---|
| `Intl.DateTimeFormat('fa-IR')` (2026-03-21) | `۱۴۰۵/۱/۱` (Jalali + Persian digits are the default for `fa-IR`) |
| `jalali` formatter above, `dateStyle: 'long'` | `۱ فروردین ۱۴۰۵` |
| `NumberFormat('fa-IR').format(1234567.89)` | `۱٬۲۳۴٬۵۶۷٫۸۹` (U+066C group, U+066B decimal) |
| `NumberFormat('fa-IR').format(-5)` | U+200E + `−۵` (LRM + U+2212 minus) |
| `percent` formatter, `0.5` | `۵۰٪` (U+066A) |
| currency `IRR`, `1500000` | U+200E + `ریال` + NBSP + `۱٬۵۰۰٬۰۰۰` (unit label comes first) |
| `PluralRules('fa')` for 0, 1, 2, 5 | `one, one, other, other` |
| `Intl.Locale('fa-IR').getWeekInfo()` | `{ firstDay: 6, weekend: [5], minimalDays: 1 }` (Saturday start, Friday weekend) |
| `Collator('fa')` on `گ چ پ ب ا ژ ز` | `ا ب پ چ ز ژ گ` |

- Output uses locale-specific separators (e.g. Arabic thousands `٬` and decimal `٫`) and may include invisible bidi marks (e.g. LRM) around negative numbers. **Do not strip or hand-roll separators**; test in the target runtime, because ICU data differs by browser/Node version.
- Intl can *format* Jalali dates but does not provide Jalali date **arithmetic or pickers**. For month navigation, ranges and pickers, use a maintained library already in the project or evaluate one (e.g. `@internationalized/date`'s Persian calendar, `date-fns-jalali`, `dayjs` + a Jalali plugin, `jalaali-js`). Check maintenance status and bundle size before adding a dependency; avoid adding one if the app only formats.

### 3. Currency
- IRR is the ISO 4217 code. **Toman is not an ISO code**: 1 toman = 10 rial, a display convention. If the UI shows toman, implement it as an explicit app-level conversion (`display = rial / 10`) and always show the unit label («تومان» / «ریال»). Never mix units in the same view or store.
- Do not format currency by concatenating symbols; use `Intl.NumberFormat` with `style: 'currency'` where it yields acceptable output, or format the number and attach a localized unit label.
- Check fraction digits: rial/toman amounts usually have none.

### 4. Time, week and zones
- Use IANA zones (`Asia/Tehran`, `Asia/Kabul`); never hard-code offsets. Iran's rules have changed recently (daylight saving time was abolished), so keep tz data current and avoid assumptions in tests.
- Week typically starts on Saturday in Iran and Friday is the weekly holiday; confirm for the product's market. Prefer `Intl.Locale#getWeekInfo()` / `weekInfo` where available, with a documented fallback.
- Month names (Jalali): فروردین، اردیبهشت، خرداد، تیر، مرداد، شهریور، مهر، آبان، آذر، دی، بهمن، اسفند. Let Intl produce them; do not hard-code unless needed.

### 5. Input parsing
Users type Persian digits, Arabic-Indic digits, Latin digits, and different separators (`,` `،` `٬` `.` `٫` `/`).
- Normalize before parsing: map ۰-۹ and ٠-٩ to 0-9; unify separators; strip bidi marks and spaces.
- For numeric fields use `<input type="text" inputmode="numeric" autocomplete="…">` with normalization, because `type="number"` often rejects Persian digits.
- Validate **after** normalization; show errors in Persian (`persian-ux-writing`).
- Jalali date input: validate month length (first six months 31 days, next five 30, Esfand 29 or 30 in leap years) using a library, not custom arithmetic.

### 6. Sorting and search
- `new Intl.Collator('fa')` for alphabetical sorting (Persian letters پ چ ژ گ order correctly only with a locale-aware collator).
- Normalize before comparing (see `persian-ux-writing` §1).

## Procedure
1. Find all places that format or parse dates/numbers/money: `rg -n "toLocale(Date|Time)?String|Intl\.|moment|dayjs|date-fns|toFixed|parseInt|parseFloat|Number\("`.
2. Identify the current implicit policy; compare to the declared policy; list mismatches.
3. Verify stored values are canonical.
4. Test inputs: Persian digits, Arabic digits, mixed separators, empty, very large numbers, leap-year Esfand, month boundaries around Nowruz.
5. Run any existing unit tests; add table-driven tests for conversions if the project has a test runner.

## Acceptance checklist
- [ ] Localization policy documented (calendar, digits, currency, market).
- [ ] Canonical storage (ISO dates, E.164, numeric money); no Jalali strings in the database/API.
- [ ] Intl used with explicit calendar and numbering system; no hand-rolled separators.
- [ ] Rial/toman unit always labeled and never mixed.
- [ ] Persian/Arabic/Latin digit input normalized before validation.
- [ ] Locale-aware sorting and normalized search.
- [ ] Date arithmetic/pickers not custom-written.
