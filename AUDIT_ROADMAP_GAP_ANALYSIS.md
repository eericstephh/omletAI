PROMPT 2: CORE EDITING TOOLS & UNDO/REDO

CRITICAL RULE: Our project ALREADY has a timeline and some editing tools. Do NOT recreate or rewrite the timeline from scratch. If an existing tool is already implemented and functional, DO NOT touch or break it. Only implement or integrate the missing tools.

Based on the audit, please fulfill the missing core editing tools:

1. Basic Operations:
   - Verify if Split, Trim, and Duplicate exist for clips on the timeline. If missing or disconnected from the UI buttons, implement them cleanly in EditorViewModel and connect them to the timeline state.
2. Undo/Redo System:
   - Inspect our state management. If an Undo/Redo stack does not exist or is incomplete, implement an immutable/command-pattern Undo/Redo history for clip actions (split, delete, trim, move).
3. Playback Speed:
   - Check if video speed control is hooked to the player. Ensure basic speed adjustment (0.25x to 2.0x) works without desyncing audio/video.

Run 'gradlew testDebugUnitTest' and ensure the project builds with zero errors.


