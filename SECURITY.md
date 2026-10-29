# Security Policy

## Scope

This repository contains Markdown instructions for OpenCode (an agent, a command and seven skills) plus small, dependency-free Python scripts for validation. It has no server, no network code and stores no secrets. The realistic security concerns are:

- **Agent permissions**: the agent ships with `edit: ask`, `webfetch: ask` and a bash allowlist for read-only Git/listing commands. A change that widens these permissions without need is a security-relevant change.
- **Prompt injection**: audited projects, web pages and tool output can contain text that tries to instruct the agent. The agent prompt treats such content as data; weaknesses in that wording are in scope.
- **Unsafe guidance**: skill content that would lead to insecure code (for example, advice that weakens input handling or recommends unsafe bidirectional-control handling).
- **Scripts**: `scripts/validate_repo.py` and `scripts/check_opencode_discovery.py` read files and run `opencode debug …` in a temporary directory; reports of unsafe behavior are in scope.

## Reporting a vulnerability

Please report responsibly and do not publish exploit details before a fix is available.

- Preferred: use GitHub's **private vulnerability reporting** (Security tab → "Report a vulnerability") once the maintainers have enabled it on the published repository.
- If that option is not available, open a public issue that contains **no sensitive details** and asks the maintainers for a private channel.

No security email address is published here on purpose. Maintainers: add a contact to this file when one exists.

## Secrets

- Never commit API keys, tokens, credentials, private keys or personal data. `.gitignore` excludes common secret files, and `scripts/validate_repo.py` runs a heuristic scan for obvious credential formats (it is not a complete secret scanner; consider GitHub secret scanning for the published repository).
- If you accidentally commit a secret, revoke it immediately, then remove it from history using your platform's recommended procedure.

## Supported versions

Only the latest release is supported.
