# Publishing to GitHub

Nothing in this repository publishes itself. Follow these steps deliberately.

1. Run the checks: `python scripts/validate_repo.py`, `python -m unittest discover -s tests -v`, and (if OpenCode is installed) `python scripts/check_opencode_discovery.py`.
2. Review for secrets and personal data: `git status`, `git diff`, and look at every new file.
3. Initialize and commit locally (only when you decide to):
   ```bash
   git init
   git add .
   git commit -m "Initial release 0.1.0"
   ```
4. Create the GitHub repository yourself (name suggestion: `persian-ui-ux-agent`), then add the remote and push the first time:
   ```bash
   git remote add origin https://github.com/<you>/persian-ui-ux-agent.git
   git branch -M main
   git push -u origin main
   ```
5. In repository settings: enable **private vulnerability reporting** and secret scanning; add a contact to `SECURITY.md` if you have one.
6. Confirm the first workflow run. Check that the action versions in `.github/workflows/validate.yml` are current; the `opencode-discovery` job is informational (`continue-on-error`).
7. Tag and release: `git tag v0.1.0 && git push origin v0.1.0`, then create a GitHub Release from the changelog entry.

Suggested repository description:

> Persian-first UI/UX agent kit for OpenCode: RTL layout, Persian typography, UX writing, localization, bidirectional text, accessibility, and an evidence-based audit command.

Suggested topics: `opencode`, `ui-ux`, `persian`, `farsi`, `rtl`, `localization`, `accessibility`, `ai-agent`.
