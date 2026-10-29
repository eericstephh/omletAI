# Installation

Assets live in `.opencode/` (`agents/`, `commands/`, `skills/`). Nothing is installed automatically and no dependencies are required.

> **Static vs runtime checks.** `scripts/validate_repo.py` checks the *repository's structure*. Only OpenCode itself can tell you whether your *installed version* discovers the assets (see [Verify](#verify-discovery-in-your-opencode)).

## Project-level installation (recommended)

Copies the assets into one application project. If your project has no `.opencode/` directory yet, the steps below create it.

**Before you start:** back up or commit your project. **Do not overwrite** existing agents, commands, skills or configuration. The snippets skip files that already exist and tell you which ones; resolve conflicts by hand (for example if you already have an agent with the same name).

### Linux / macOS (bash)

Tested on Linux (bash). Set the two paths first.

```bash
SRC="/path/to/persian-ui-ux-agent/.opencode"
DST="/path/to/your-app/.opencode"
mkdir -p "$DST"
( cd "$SRC" && find . -type f | while IFS= read -r f; do
    if [ -e "$DST/$f" ]; then echo "skip (exists): $f"
    else mkdir -p "$DST/$(dirname "$f")" && cp "$f" "$DST/$f"; fi
  done )
```

### Windows (PowerShell)

Not executed in the verification sandbox (no PowerShell available); written to be conservative. Set the two paths first.

```powershell
$src = "C:\path\to\persian-ui-ux-agent\.opencode"
$dst = "C:\path\to\your-app\.opencode"
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Get-ChildItem -Path $src -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($src.Length).TrimStart('\', '/')
    $target   = Join-Path $dst $relative
    if (Test-Path -LiteralPath $target) { Write-Warning "Skipped (exists): $relative"; return }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $target
}
```

Commit the copied files if you want teammates to get them.

## Global (user-level) installation

Global assets apply to every project for your user. Because this changes your personal OpenCode setup, do it deliberately and only after project-level testing.

Documented user-level locations (same names as the project layout):

| Asset | Location |
|---|---|
| Agents | `~/.config/opencode/agents/` |
| Commands | `~/.config/opencode/commands/` |
| Skills | `~/.config/opencode/skills/<name>/SKILL.md` |

First ask OpenCode where *your* installation looks, because the base directory depends on OS and environment variables (for example `XDG_CONFIG_HOME`):

```bash
opencode debug paths
```

Use the directory printed next to `config`. Verified on Linux: with `HOME=/tmp/fakehome`, `config` was `/tmp/fakehome/.config/opencode` and assets copied there were discovered from an unrelated directory. **Windows and macOS paths are not verified here**; use the `config` path that `opencode debug paths` prints rather than assuming.

Linux / macOS:

```bash
CONF="$HOME/.config/opencode"      # replace with the 'config' path from `opencode debug paths`
mkdir -p "$CONF"
cp -Rn /path/to/persian-ui-ux-agent/.opencode/agents   "$CONF/"
cp -Rn /path/to/persian-ui-ux-agent/.opencode/commands "$CONF/"
cp -Rn /path/to/persian-ui-ux-agent/.opencode/skills   "$CONF/"
```

(`-n` avoids overwriting; if your `cp` lacks it, use the loop from the project-level section with `DST="$CONF"`.)

Windows (PowerShell): reuse the project-level snippet with `$dst` set to the `config` path printed by `opencode debug paths`.

Skill names must be unique across locations. If the same skill exists both globally and in a project, remove one.

OpenCode also reads skills from `.claude/skills/` and `.agents/skills/`; do not install the same skills in several of those places.

## Verify discovery in your OpenCode

From the project (or any directory for a global install):

```bash
opencode --version
opencode agent list                       # should include Persian-UI-Specialist
opencode debug agent Persian-UI-Specialist
opencode debug skill                      # should list the 7 persian-* / bidirectional-content skills
opencode debug config                     # command section should include persian-ui-audit
```

Or run the bundled check, which uses an isolated temporary project and config (it never touches your real settings):

```bash
python scripts/check_opencode_discovery.py            # uses `opencode` from PATH
python scripts/check_opencode_discovery.py --opencode /path/to/opencode
```

Exit code 0 = discovered; 1 = something missing; 2 = blocked (OpenCode not found or could not run).

## Use

- Start OpenCode in your project, then either press <kbd>Tab</kbd> to switch to **Persian-UI-Specialist** (it is a `mode: all` agent) or mention it with `@Persian-UI-Specialist`.
- Run the audit: `/persian-ui-audit` or `/persian-ui-audit checkout flow and settings pages`.
- The agent loads the skills it needs through OpenCode's `skill` tool.

## Uninstall

Delete the files you copied (agent file, command file and the seven skill directories). Nothing else is modified.
