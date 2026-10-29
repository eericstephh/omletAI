---
name: rtl-localization
description: Localization, RTL, and multilingual UI. Use this focused skill when the task matches its scope.
---


# Localization, RTL, and multilingual UI

## When to use
Use for Persian, Arabic, Hebrew, mixed-direction content, and localized products.

## Workflow
1. Use logical start/end layout properties rather than hardcoded left/right assumptions.
2. Check bidirectional text, punctuation, numbers, currency, dates, and embedded Latin strings.
3. Allow for text expansion, different line breaks, and font metrics across languages.
4. Mirror directional icons only when their meaning is directional; do not blindly mirror brand or media controls.
5. Test mixed RTL/LTR fields, truncation, navigation order, and accessibility reading order.

## Expected outputs
- Localization checklist
- RTL layout rules
- Mixed-direction test cases
- String expansion risks

## Avoid
- Treating RTL as only text alignment
- Reversing every icon or the semantic reading order

## Done when
- The recommendations connect to the actual product goal and user task.
- Important assumptions and unverified claims are explicitly labeled.
- The result is specific enough to implement or evaluate.
