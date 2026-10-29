---
name: aierix-cut-project
description: "Verified, code-derived knowledge base for the AIerix Cut Android video editor (com.aicreatorstudio.app). Use when working on this project to locate code, run the build and unit tests, trace the Whisper/Persian subtitle pipeline, find the real subtitle template catalogs, understand why a preview/export feature diverges, or check the known bug classes and open gaps. Load this before editing any subtitle, template, effect, timeline or export code so you do not guess a path or re-derive an expensive fact."
---

# AIerix Cut — project knowledge base

Everything here was read out of the code, not inferred. Paths are relative to the repo root
`C:\Users\eric steph\Desktop\projects\edit\New folder (2)\AICreatorStudio`; Kotlin paths are
relative to `app/src/main/java/com/aicreatorstudio/app/`.

**Read the narrowest reference that covers your task:**

| File | Read it when |
|---|---|
| `references/project-map.md` | You need to find where something lives, or the stack/module layout. |
| `references/build-and-test.md` | You are about to build, run tests, or write a test. |
| `references/subtitle-pipeline.md` | Any subtitle, transcription, Persian correction, template or caption-rendering work. |
| `references/bug-classes.md` | A feature "does nothing", an effect is missing, or preview and export disagree. |
| `references/open-gaps.md` | You need to know what is already known-broken before you start. |

## Two rules that override everything else

1. **The code is the source of truth.** If this skill disagrees with the code, the code wins — and
   fix this skill.
2. **Never trust a KDoc over a consumer.** This project's most expensive recurring bug is a field
   that is written and persisted but read by no renderer. Verify a feature by grepping for its
   consumers.

```bash
# The core diagnostic: who actually reads this field?
grep -rn "<fieldName>" --include=*.kt app/src
```

## The three most useful orientation facts

- **There is exactly one subtitle renderer**: `export/CaptionRenderer.kt#render`, called by the
  Compose preview *and* by the Media3 export. A preview/export divergence therefore almost never
  means "two renderers" — it means a missing effect in a list, a parameter not forwarded, or a
  Media3 timing gate. See `references/bug-classes.md`.
- **Two separate subtitle template catalogs exist** — 20 built-ins in Kotlin plus 17 JSON templates.
  Do not assume a template name is real; look it up in `references/subtitle-pipeline.md`.
- **`./gradlew` does not work under Git Bash.** Use the exact command in
  `references/build-and-test.md`. Do not re-download the wrapper jar — it is fine.

## Reusable procedure for the parity bug class

For any visual control that "does nothing", or that works in the preview but not in the file, the
dedicated skill **`media3-export-parity`** carries the full verified procedure and the Media3 1.5.0
API facts (value-shape, list-membership and sink-lifetime gates). Use it rather than re-deriving
them. A short summary lives in `references/bug-classes.md`.
