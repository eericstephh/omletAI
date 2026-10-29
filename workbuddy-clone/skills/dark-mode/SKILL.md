---
name: dark-mode
description: Dark mode and theme adaptation. Use this focused skill when the task matches its scope.
---


# Dark mode and theme adaptation

## When to use
Use when a product supports light/dark appearance or multiple themes.

## Workflow
1. Map semantic tokens to themes instead of inverting colors mechanically.
2. Preserve hierarchy and contrast without making surfaces all the same black/gray.
3. Revisit elevation, borders, focus, status colors, images, and charts in each theme.
4. Respect system preference where appropriate and provide predictable user control.
5. Test theme switching, persistence expectations, and every critical state.

## Expected outputs
- Theme token mapping
- Theme-specific exceptions
- Contrast risks
- Switching behavior

## Avoid
- Simple color inversion
- Low-contrast borders and disabled text disappearing

## Done when
- The recommendations connect to the actual product goal and user task.
- Important assumptions and unverified claims are explicitly labeled.
- The result is specific enough to implement or evaluate.
