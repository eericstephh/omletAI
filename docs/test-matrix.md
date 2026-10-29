# UI Test Matrix Template

Adapt this matrix to the product. Do not mark checks as passed until they have actually been run.

| Area | Scenario | Expected result | Status | Evidence / notes |
|---|---|---|---|---|
| Primary task | Complete the main user task | Clear completion and correct saved state | NOT RUN | |
| Validation | Submit invalid or incomplete input | Specific actionable error; input preserved where possible | NOT RUN | |
| Loading | Slow or pending operation | Feedback appears; layout remains stable | NOT RUN | |
| Empty | No data or no search results | Clear explanation and useful next step | NOT RUN | |
| Failure | Network/API/action failure | Error is understandable; recovery is possible | NOT RUN | |
| Keyboard | Navigate and operate controls | Logical order and visible focus | NOT RUN | |
| Responsive | Narrow, medium, wide viewports | No unintended overflow; priority preserved | NOT RUN | |
| Accessibility | Names, semantics, contrast, non-color cues | No known critical issue in checked scope | NOT RUN | |
| Theme | Light/dark if supported | Semantic colors and states remain clear | NOT RUN | |
| Regression | Existing critical flow | Prior behavior still works | NOT RUN | |

Use only these status values: `PASS`, `FAIL`, `BLOCKED`, `NOT RUN`. Note the exact tool, viewport, or scenario for any evidence.
