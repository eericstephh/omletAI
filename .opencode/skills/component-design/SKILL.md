---
name: component-design
description: Reusable component design. Use this focused skill when the task matches its scope.
---

# Reusable component design

## When to use
Use when implementing or standardizing a repeated UI pattern.

## Workflow
1. Define the component's responsibility, inputs, outputs, variants, and composition boundaries.
2. Model loading, empty, disabled, error, success, focus, hover, and selected states as applicable.
3. Keep content semantics and keyboard behavior built into the API.
4. Reuse design tokens and existing primitives; avoid unnecessary abstraction.
5. Document do/don't examples and test representative variants.

## Expected outputs
- Component contract
- Variants and states
- Usage examples
- Test cases

## Avoid
- A component with many unrelated boolean props
- Visual consistency without behavior consistency

## Done when
- The recommendations connect to the actual product goal and user task.
- Important assumptions and unverified claims are explicitly labeled.
- The result is specific enough to implement or evaluate.
