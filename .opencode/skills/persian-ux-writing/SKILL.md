---
name: persian-ux-writing
description: Write and review natural, consistent Persian UI copy - correct letters (ی/ک), نیم‌فاصله rules, punctuation, tone, terminology, buttons, forms, error/success/empty/confirmation messages and plural handling. Use when creating, translating or reviewing Persian interface text.
license: MIT
compatibility: opencode
---

# نگارش تجربه کاربری فارسی (Persian UX Writing)

## When to use
Writing or reviewing any user-facing Persian string: labels, buttons, placeholders, errors, toasts, empty states, onboarding, confirmations, emails/notifications.

## Instructions

### 1. Encoding hygiene (verifiable)
- Use Persian **ی (U+06CC)** and **ک (U+06A9)**, not Arabic ي (U+064A) and ك (U+0643).
- Digits: Persian ۰-۹ are U+06F0–06F9; Arabic-Indic ٠-٩ (U+0660–0669) look similar but differ. Do not mix them.
- Search for hits: `rg -n "[\x{064A}\x{0643}\x{0660}-\x{0669}]"` (adjust quoting for your shell; any regex engine that supports Unicode escapes works). Legitimate Arabic quotations are the only exception.
- For search/filter features, **normalize both the query and the data** (ي→ی, ك→ک, Arabic-Indic and Persian digits → one form, strip tatweel ـ where appropriate).

### 2. نیم‌فاصله (ZWNJ, U+200C)
Use ZWNJ where Persian orthography calls for it. Reliable cases:
- Verb prefixes: **می‌شود، نمی‌توانید، می‌خواهید**
- Plural / comparative suffixes: **کتاب‌ها، برنامه‌های، بزرگ‌تر، بهترین** (ترین is attached; ‌تر uses ZWNJ)
- Compound words by Academy convention: **به‌روزرسانی، هم‌زمان، پیش‌فرض، نام‌کاربری** (product vocabulary must pick one spelling and keep it)
- Words ending in ه + suffix: **خانه‌ای، خانه‌مان**
Do **not** replace ZWNJ with a normal space (breaks the word) or remove it (breaks joining). Prefer the Persian Academy (فرهنگستان زبان و ادب فارسی) orthography when sources disagree, and record the product's decisions in a glossary. If unsure about a specific word, flag it instead of guessing.

### 3. Punctuation
- Persian comma **،**, semicolon **؛**, question mark **؟**; guillemets **« »** for quotation. No space before ، ؛ ؟ : and one space after.
- Prefer the single ellipsis character **…** or consistent usage across the product.
- Keep Latin punctuation inside Latin segments (URLs, code).

### 4. Tone and grammar
- Use the polite **شما** form and plural verb agreement consistently: «وارد شوید»، «ذخیره کنید».
- Choose one button convention and keep it: short verbs/nouns («ذخیره»، «ثبت‌نام»، «ورود»); avoid mixing «ذخیره کن» (informal) and «ذخیره کنید».
- Persian has no grammatical gender: write gender-neutral copy, avoid copying English gendered patterns.
- **Plurals**: after numerals Persian nouns stay singular: «۳ پیام»، not «۳ پیام‌ها». Use ICU MessageFormat/Intl.PluralRules, not string concatenation. CLDR `fa` has `one` (covers 0 and 1) and `other`; do not assume English rules.
- Avoid literal translation of English idioms and UI clichés. Rewrite for meaning: "Sign in to continue" → «برای ادامه وارد شوید».
- Avoid unnecessary exclamation marks and emoji in errors.

### 5. Message patterns
**Errors** = what happened + (why, if known) + what to do next. Never blame the user; never show raw codes/stack traces as the only message.
- ✗ «خطا در انجام عملیات»
- ✓ «ذخیره‌سازی انجام نشد. اتصال اینترنت را بررسی کنید و دوباره تلاش کنید.»
- ✗ «ایمیل نامعتبر است»
- ✓ «نشانی ایمیل را کامل وارد کنید؛ مثلاً name@example.com»

**Success**: confirm the result concisely. «تغییرات ذخیره شد.»

**Loading**: say what is happening if >1s. «در حال بارگذاری گزارش‌ها…»

**Empty states** = explain + next action. «هنوز سفارشی ثبت نکرده‌اید. برای شروع، اولین سفارش خود را ثبت کنید.»

**Destructive confirmation** = name the object and consequence; button repeats the verb.
«حذف پروژه؟ این کار قابل بازگشت نیست.» → [حذف پروژه] [انصراف]

**Forms**: persistent visible labels (not placeholder-only), helper text for format, «(اختیاری)» for optional fields, errors next to the field.

### 6. Terminology
Create or reuse a glossary and apply it everywhere. Example starting set (adapt to the product, then stay consistent):

| English | Persian choice |
|---|---|
| Settings | تنظیمات |
| Log in / Log out | ورود / خروج |
| Sign up | ثبت‌نام |
| Save / Cancel / Delete | ذخیره / انصراف / حذف |
| Search | جست‌وجو |
| Notification | اعلان |
| Profile | نمایه (or پروفایل — pick one) |
| Dashboard | پیشخوان (or داشبورد — pick one) |

Keep brand and product names in their original script unless the brand officially localizes them.

## Procedure
1. Locate copy: i18n resource files, hard-coded JSX/templates, server messages, emails.
2. Run the encoding checks (ی/ک and digits); list hits.
3. Review ZWNJ in the 20–30 most frequent strings, then in shared messages.
4. Review every error/empty/confirmation pattern against §5.
5. Build or update the glossary; flag inconsistencies by file.
6. Check plural handling and concatenated strings (`"..." + count + "..."`).

## Acceptance checklist
- [ ] No Arabic ي/ك or mixed digit sets in Persian copy.
- [ ] ZWNJ correct and preserved (not replaced with spaces).
- [ ] One tone/register (شما), one button convention.
- [ ] Errors tell what happened and what to do; empty states offer a next action.
- [ ] Plurals via ICU/Intl, no concatenated sentences.
- [ ] Glossary exists and is applied.
