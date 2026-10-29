# Architecture

The kit uses three layers:

1. **Commands** define repeatable task workflows such as designing, auditing, or verifying.
2. **Agents** assign specialist responsibilities for coordination, research, visual design, system architecture, implementation, review, and QA.
3. **Skills** provide focused domain knowledge that can be selected based on the task.

This separation avoids putting every design rule into every prompt. The coordinator reads the skill catalog, chooses a small relevant subset, and hands off only where specialist work adds value.

## Execution boundaries
The repository itself does not install browser drivers, screenshot tools, Android emulators, or visual regression services. Those capabilities depend on the user's OpenCode setup and project configuration. Agents must discover available tooling and label unavailable checks clearly.
