---
name: mobile-editor-workflows
description: Creative editor and timeline UX. Use this focused skill when the task matches its scope.
---


# Creative editor and timeline UX

## When to use
Use for video, audio, image, animation, and other timeline-based creative tools.

## Workflow
1. Separate navigation, selection, scrubbing, dragging, and editing gestures to reduce conflicts.
2. Make selection state, playhead position, clip boundaries, snapping, and undo behavior obvious.
3. Provide contextual toolbars and preserve workspace when switching modes.
4. Show progress, cancellation, failure recovery, and output settings for long-running tasks.
5. Test dense timelines, zoom levels, short clips, touch targets, and accidental destructive edits.

## Expected outputs
- Interaction/gesture map
- Timeline state rules
- Undo/recovery behavior
- Dense-layout test cases

## Avoid
- Gesture conflicts between scrolling and dragging
- Controls that change meaning without clear state

## Done when
- The recommendations connect to the actual product goal and user task.
- Important assumptions and unverified claims are explicitly labeled.
- The result is specific enough to implement or evaluate.
