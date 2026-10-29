---
name: bidirectional-content
description: Display and edit mixed Persian and Latin content correctly - URLs, emails, phone numbers, code, IDs, versions, brand names, numbers with signs, punctuation and parentheses - using dir/bdi/isolation instead of manual string tricks. Use whenever RTL text contains LTR fragments or user-generated text of unknown direction.
---


# محتوای دوجهته (Bidirectional Content)

## When to use
Persian sentences that contain English product names, URLs, emails, phone numbers, version numbers, IDs, code, negative numbers or ranges; inputs that accept both scripts; user-generated content whose direction is unknown.

## Core idea
The Unicode Bidirectional Algorithm decides display order. Neutral characters (spaces, punctuation, parentheses, `+ - / : .`) take direction from their neighbors, so they "jump" when an LTR fragment sits inside an RTL paragraph. **Fix by isolating fragments with markup, not by editing the text.**

## Instructions

### 1. Choose the tool by context
| Situation | Use |
|---|---|
| Known LTR fragment (URL, email, code, ID, version, phone) | `<span dir="ltr">…</span>` or `<code dir="ltr">…</code>` |
| Unknown direction (usernames, comments, titles from users) | `<bdi>…</bdi>` or `dir="auto"` on the element |
| Whole block of user text | `<p dir="auto">…</p>` |
| CSS-only styling hook | `unicode-bidi: isolate` (with `direction`) |
| Plain-text contexts without markup (push notifications, emails' subject lines, `title`/`alt` text, logs, CSV) | Unicode isolates: LRI U+2066 … PDI U+2069 (and RLI U+2067, FSI U+2068) around the fragment; or LRM U+200E / RLM U+200F for single-character fixes |
| Forcing a reorder (very rare) | `<bdo dir="ltr">`; avoid |

Prefer isolates (`LRI/RLI/FSI … PDI`) over legacy embeddings (`LRE/RLE`) and **never use overrides** (`LRO/RLO`, U+202D/U+202E) in user-influenced text.

### 2. Common cases
- **Brand/product names**: keep the Latin original: `برای ورود از <bdi>Google Chrome</bdi> استفاده کنید.` Wrap if the name is followed by digits or punctuation that jumps.
- **Phone numbers**: `<span dir="ltr">+98 21 1234 5678</span>`; store E.164; render with a formatter.
- **Emails/URLs/IDs/API keys/paths/commands**: always `dir="ltr"`, and use a monospace face for code-like values; allow wrapping with `overflow-wrap: anywhere`.
- **Version numbers**: `نسخه <bdi dir="ltr">2.4.1-beta</bdi>`.
- **Negative numbers and ranges**: a leading `-` can display at the wrong end in RTL. Use a real minus (U+2212) inside an isolated span, or let `Intl.NumberFormat('fa-IR')` produce it (it adds an LRM; do not strip it). For ranges prefer words: «از ۱۰ تا ۲۰» rather than `10-20`.
- **Parentheses and quotes around Latin**: if «(Pro)» renders as «)Pro(» or marks jump, isolate the whole parenthetical in `<bdi>` or restructure the sentence.
- **Lists of mixed items** (table cells, chips): set `dir="auto"` per cell/chip so each aligns by its own content, and use `text-align: start`.
- **Numbers with units**: `۱۲٫۵ MB` — isolate the Latin unit if spacing jumps; do not insert manual spaces.

### 3. Inputs
- Free text (names, search, comments): `dir="auto"`; test caret movement, selection and placeholder alignment in each target browser.
- Email, URL, phone, username/handle, password, code, OTP: `type` as appropriate plus `dir="ltr"`. If the form layout needs visual right alignment, add `text-align: end` rather than removing `dir="ltr"`.
- Numeric entry: see `persian-localization` (accept Persian/Arabic/Latin digits; normalize).

### 4. Data hygiene and clipboard
- **Never reverse strings manually**, split-and-reorder words, or add LRM/RLM into stored data to "make it look right". Keep data canonical; fix at render time.
- Values users copy (emails, IDs, tokens, commands) must not contain invisible bidi marks. Isolate with markup/CSS instead of control characters.
- When displaying untrusted text (filenames, repo/branch names, code diffs, user names), strip or visibly escape bidi control characters U+202A–202E and U+2066–2069. They can reorder text to disguise content (a known spoofing technique).

## Procedure
1. List content types that mix scripts: `rg -n "href=|mailto:|tel:|\.com|v[0-9]+\.|https?://"` plus user-generated fields.
2. For each, check the markup for `dir`/`<bdi>`/`unicode-bidi`.
3. Create a test string set (below) and render it in every component type (text, button, tab, chip, table cell, toast, input, placeholder, tooltip).
4. Compare visual order against the logical order; verify select/copy/paste and caret movement by hand.

Test strings:
```
برای ورود به (admin) از https://example.com/login?next=/a-b استفاده کنید.
ایمیل: name@example.com | تلفن: +98 21 1234 5678 | نسخه v2.4.1-beta
دما −۵ درجه است؛ بازه ۱۰ تا ۲۰؛ (Pro) شناسه: ID-4F2A-9
کاربر <bdi>John_Doe99</bdi> نوشت: «Hello! سلام»
```

## Acceptance checklist
- [ ] LTR fragments (URL, email, phone, code, ID, version) isolated with `dir="ltr"`/`<bdi>`.
- [ ] User-generated text uses `dir="auto"`/`<bdi>`; no assumption about direction.
- [ ] Email/URL/phone/code inputs are `dir="ltr"`.
- [ ] No manual string reversal; no bidi control characters stored in data.
- [ ] Copy/paste of IDs, emails and commands yields clean text.
- [ ] Untrusted text has bidi controls neutralized.
- [ ] Parentheses, signs, ranges verified in text, buttons, tables and toasts.
