# Compatibility and verification record

This file records **what was verified, how, and what was not**. Re-run the commands for your own OpenCode version before relying on it.

## Audit environment (2026-10-10)

| Item | Value |
|---|---|
| OS / shell | Ubuntu 24.04.4 LTS sandbox, `sh`/`bash` |
| Python / Node | 3.12.3 / 22.22.2 |
| OpenCode | **1.18.35** (`npm install opencode-ai` into a temporary local directory; not installed globally; isolated `XDG_*` / `HOME` so no user config was touched) |
| Starting state | Working directory was `/` with no project and no Git repository, so nothing existing needed preserving. |

## Official documentation consulted

Retrieved through web search (the sandbox could not reach `opencode.ai` directly):

- Skills: `https://opencode.ai/docs/skills.md`, `https://opencode.ai/v2/docs/skills/`
- Agents: `https://opencode.ai/v2/docs/agents/`; older layout: `https://thdxr.dev.opencode.ai/docs/agents`
- Commands: `https://opencode.ai/v2/docs/commands/`

What those pages say (summarized, not quoted):

| Topic | Documented behavior |
|---|---|
| Agents | Markdown files in `.opencode/agents/<name>.md` (project) or `~/.config/opencode/agents/<name>.md` (global); file name becomes the agent name; Markdown body is the system prompt; older docs use singular `agent/`. |
| Commands | Markdown files in `.opencode/commands/` or `~/.config/opencode/commands/`; optional frontmatter such as `description`, `agent`, `model`, `subtask`; **the body is the template** and `template` must not be in frontmatter. |
| Skills | `.opencode/skills/<name>/SKILL.md` (also `.claude/skills/` and `.agents/skills/` variants); `SKILL.md` in capitals; `name` and `description` required in the stable docs, `name` must match the directory, description 1-1024 characters; unknown fields ignored; names must be unique across locations. The V2 docs relax this (path-derived ID, `name` only a display label) but a matching name is valid in both. |

## Empirical results on OpenCode 1.18.35

| # | Check | Command | Result |
|---|---|---|---|
| 1 | Plural **and** singular directory names | probe project with `.opencode/{agents,agent,commands,command,skills,skill}`, then `opencode agent list`, `opencode debug config`, `opencode debug skill` | **Both** layouts were loaded (agents, commands, skills) |
| 2 | Agent loads with chosen frontmatter | `opencode debug agent Persian-UI-Specialist` | Loaded: mode `all`, temperature 0.2, `edit: ask`, `webfetch: ask`, bash patterns as written |
| 3 | Command loads | `opencode debug config` | `persian-ui-audit` present with `agent: Persian-UI-Specialist` and body as template |
| 4 | Seven skills discovered (project, with Git) | `opencode debug skill` | 7 of 7 found |
| 5 | Seven skills discovered (project, no Git) | same, in a directory without `.git` | 7 of 7 found |
| 6 | Global install, Linux, isolated `HOME` | copy `agents/ commands/ skills/` into `~/.config/opencode/`, run from an unrelated empty directory | agent, command and 7 skills discovered; `opencode debug paths` reported config at `~/.config/opencode` |
| 7 | `scripts/check_opencode_discovery.py` | `python scripts/check_opencode_discovery.py --opencode <path>` | **PASSED** on 1.18.35: agent, command and all 7 skills discovered in an isolated temporary project; prints `BLOCKED` and exits 2 when OpenCode is not found |

## Layout decision

The task template proposed singular directory names (`agent/`, `command/`, `skill/`). The current official docs use plural names, and 1.18.35 accepts both. This repository therefore uses the **plural** layout. If you must support an older OpenCode release that only knows the singular names, rename the three directories and follow [TROUBLESHOOTING.md](TROUBLESHOOTING.md#version-differences).

## Not verified

- **Executing** the agent or `/persian-ui-audit` against a model. An attempt to run `opencode run --command persian-ui-audit` hung until a 90 s timeout with no output (no model provider configured, restricted network). Discovery and parsing are verified; runtime behavior and output quality are **not**.
- Any OpenCode version other than 1.18.35.
- Windows and macOS (paths and PowerShell snippets in [INSTALLATION.md](INSTALLATION.md) are written from the documentation and Linux evidence; the sandbox had no PowerShell).
- The GitHub Actions workflow (not executed). Action major versions (`actions/checkout@v7`, `actions/setup-python@v6`, `actions/setup-node@v6`) were taken from search results that were mostly third-party pull requests, not official release pages; confirm them when you publish.
- Rendering behavior described in skills (browser support for `:dir()`, `prefers-reduced-transparency`, `Intl.Locale#getWeekInfo`, etc.) is general guidance; verify against your browser targets. The `Intl` outputs listed in `persian-localization` were observed on Node 22.22 / ICU 78.2 only.
- Font licenses: skills tell the agent to verify licenses; no font was selected or vetted here.
