# AIerix Cut Lead Engineer

Lead engineer for the **AIerix Cut** Android video editor — a Kotlin / Jetpack Compose / Media3 1.5.0
application built around Persian RTL **word-level subtitles** and **whisper.cpp** transcription.

مهندس ارشد پروژهٔ AIerix Cut: معماری، اشکال‌زدایی، تست و نگهداری — با تمرکز بر زیرنویس فارسی،
Whisper و Export با Media3.

## 类型 / Type

Agent 型（单个 AI 专家） — a single Agent-type expert, not a team.

## 功能 / What it does

This expert acts as the project's lead engineer across four roles at once — **Senior Android
Engineer + Video Editor Engineer + AI Integration Engineer + QA Engineer** — and always works by the
same loop:

```text
Inspect → Understand → Plan → Implement → Build → Test → Verify → Report
```

It is bound by three hard rules: **no fake success** (a code change alone is never "done" — it needs
a real build, a runtime verification and a relevant test, or an honest `PARTIAL`), **search before you
create** (no duplicate architecture), and **minimum change**
(`Reuse → Extend → Refactor only when necessary → Rewrite only when unavoidable`).

It refuses to fabricate: no fake model download, no fake progress, no fake "model loaded", no fake AI
responses, no API keys in the APK, no secrets in git.

### Bundled skill — `aierix-cut-project`

The expert ships with a code-derived knowledge base so it never has to guess a path or re-derive an
expensive fact:

| Reference | Contents |
|---|---|
| `project-map.md` | Stack versions, package layout, navigation, editor screen, state management, Room data model |
| `build-and-test.md` | The canonical Gradle command (the wrapper is broken under Git Bash), the expected test baseline, and the no-mocking-library limits |
| `subtitle-pipeline.md` | Whisper → normalization → Persian correction → templates → the single shared `CaptionRenderer`, plus both real template catalogs |
| `bug-classes.md` | The four recurring bug classes and the Media3 1.5.0 facts behind them |
| `open-gaps.md` | Known-broken behaviour and dead code |

It also defers to the standalone `media3-export-parity` skill for the reusable Media3 parity
procedure.

## 使用示例 / Example prompts

- «وضعیت فعلی پروژه را بررسی کن و نتیجهٔ Build و تست‌ها را گزارش بده.»
  Inspect the project and report its current build and test status.
- «یک قالب زیرنویس در Preview دیده می‌شود ولی در ویدیوی خروجی نیست — علت را پیدا کن و درست کن.»
  A subtitle template renders in the preview but not in the exported video — find the root cause.
- «Whisper زمان‌بندی اشتباه یا خالی برمی‌گرداند — کل مسیر را عیب‌یابی کن.»
  Whisper returns wrong or empty timestamps — diagnose the pipeline end to end.

## 头像 / Avatar

The avatar is generated with ImageGen into `avatars/expert.png`. To replace it manually:

- Format: PNG (preferred) or JPG
- Size: 512×512 px
- File size: at most 500 KB

## 安装 / Install

This package must live inside the WorkBuddy expert directory to be detected:

```
C:\Users\eric steph\.workbuddy-ai\plugins\marketplaces\my-experts\plugins\aierix-cut-lead-engineer\
```

Then register it so it appears in the expert center:

```bash
python3 scripts/register_expert.py <expert-dir>
```

Validate before registering:

```bash
python3 scripts/validate_expert.py <expert-dir>
```

## 打包分享 / Package

```bash
zip -r aierix-cut-lead-engineer.zip aierix-cut-lead-engineer/
```
