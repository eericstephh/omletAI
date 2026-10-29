1. Copy .opencode, install-skills.ps1 and start-dashboard.bat into your Android project root.
2. In every .opencode/agents/*.md check the "model:" line against `opencode models` and fix the IDs.
3. Edit "project" in .opencode/state/progress.json.
4. Install extra skills (needs git, optional): powershell -ExecutionPolicy Bypass -File install-skills.ps1
5. Start opencode, choose the "orchestrator" agent and send your first message.
   The dashboard starts by itself and opens http://localhost:4310 in your browser.
Fallback: double-click start-dashboard.bat (or run: node .opencode\dashboard\serve.mjs).
If the plugin does not load, rename .opencode/plugin to .opencode/plugins (or the reverse).
