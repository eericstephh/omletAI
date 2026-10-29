---
name: task-flows
description: Task flows and user flows. Use this focused skill when the task matches its scope.
---

# Task flows and user flows

## When to use
Use for checkout, account setup, creation tools, settings, and other multi-step tasks.

## Workflow
1. Define entry point, desired completion, preconditions, and exit states.
2. Map happy path plus likely alternate paths, cancellations, validation failures, and recovery.
3. Minimize unnecessary steps while preserving informed choices and safety.
4. Show system feedback and state transitions at every consequential step.
5. Turn the flow into testable scenarios and identify the highest-risk branches.

## Expected outputs
- Flow diagram or ordered flow
- Alternate/error paths
- State transitions
- Acceptance scenarios

## Avoid
- Optimizing step count at the expense of clarity
- Ignoring cancellation and recovery

## Done when
- The recommendations connect to the actual product goal and user task.
- Important assumptions and unverified claims are explicitly labeled.
- The result is specific enough to implement or evaluate.
