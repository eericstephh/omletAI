---
name: visual-regression
description: Visual regression and UI QA. Use this focused skill when the task matches its scope.
---

# Visual regression and UI QA

## When to use
Use when validating a UI change against expected appearance or behavior.

## Workflow
1. Identify critical routes, states, viewport sizes, and likely regression surfaces.
2. Use screenshot capture or browser automation only if configured and available.
3. Compare hierarchy, overflow, spacing, typography, states, and responsive reflow—not only pixel differences.
4. Record baseline conditions and known dynamic content that can create false positives.
5. Report PASS/FAIL/BLOCKED/NOT RUN and link each claim to evidence.

## Expected outputs
- Risk-based test matrix
- Observed discrepancies
- Repro steps
- Verification status

## Avoid
- Claiming screenshot comparison without screenshots
- Treating every pixel difference as a user-facing defect

## Done when
- The recommendations connect to the actual product goal and user task.
- Important assumptions and unverified claims are explicitly labeled.
- The result is specific enough to implement or evaluate.
