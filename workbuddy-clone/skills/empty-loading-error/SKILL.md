---
name: empty-loading-error
description: Empty, loading, error, and success states. Use this focused skill when the task matches its scope.
---


# Empty, loading, error, and success states

## When to use
Use for any data-driven interface or asynchronous action.

## Workflow
1. Inventory initial, loading, empty, partial, error, offline, success, and retry states.
2. Explain what happened and what the user can do next.
3. Use skeletons only when they improve perceived continuity; avoid layout shifts.
4. Provide retry and preserve safe user input when possible.
5. Distinguish no data, no results, no permission, and a failed request.

## Expected outputs
- State matrix
- Copy and action for each state
- Recovery paths
- Layout-stability checks

## Avoid
- Blank screens with no explanation
- Treating every empty state as an error

## Done when
- The recommendations connect to the actual product goal and user task.
- Important assumptions and unverified claims are explicitly labeled.
- The result is specific enough to implement or evaluate.
