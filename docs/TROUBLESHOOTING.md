# Troubleshooting

First, ask OpenCode what it sees (it is the source of truth):

```bash
opencode --version
opencode debug paths
opencode agent list
opencode debug skill
opencode debug config
```

## The agent does not appear

1. Is the file at `.opencode/agents/Persian-UI-Specialist.md` (project) or `<config>/agents/` (global; get `<config>` from `opencode debug paths`)? The file name becomes the agent name.
2. Start OpenCode from inside the project (or a subdirectory). Restart OpenCode after adding files.
3. Check the frontmatter (see [Malformed frontmatter](#malformed-frontmatter)).
4. If the agent is `mode: subagent` it will not show in the <kbd>Tab</kbd> cycle; this agent uses `mode: all`.
5. `opencode debug agent Persian-UI-Specialist` prints the parsed agent or an error.

## `/persian-ui-audit` is not found

1. File must be `.opencode/commands/persian-ui-audit.md`; the file name is the command name.
2. The command's `agent:` must exist (`Persian-UI-Specialist`). If you renamed the agent, update the command.
3. `template` must **not** appear in frontmatter; the Markdown body is the template.
4. Confirm with `opencode debug config` (look under `command`).

## Skills are not discovered

1. Directory layout: `.opencode/skills/<name>/SKILL.md`, with `SKILL.md` exactly in capitals.
2. Frontmatter must contain `name` and `description`; `name` must equal the directory name and use lowercase letters, digits and single hyphens.
3. Skill names must be unique across all locations (project, global, `.claude/skills`, `.agents/skills`). A duplicate can shadow or conflict.
4. Permissions: a `deny` rule for a skill hides it from agents.
5. `opencode debug skill` lists what is loaded and where from.

## Malformed frontmatter

- The file must start with `---` on the very first line, and the block must end with another `---` line.
- Use spaces, not tabs; quote values containing `: ` or starting with special characters.
- Keep descriptions on one line (or use proper YAML block syntax).
- Run `python scripts/validate_repo.py`; it reports delimiter and required-field problems. It is not a full YAML parser, so also confirm with `opencode debug ...`.

## Installation conflicts

- You already have an agent, command or skill with the same name: rename yours or skip ours; do not keep two with the same name.
- The same skill exists globally and in the project: remove one copy.
- You merged files and lost your own: restore from version control; the install snippets in [INSTALLATION.md](INSTALLATION.md) skip existing files instead of overwriting.

## Version differences

- **Directory names**: current docs use plural `agents/`, `commands/`, `skills/`; older documentation and community examples use singular `agent/`, `command/`, `skill/`. OpenCode 1.18.35 loaded both (see [COMPATIBILITY.md](COMPATIBILITY.md)). If an older release does not see the plural directories, rename them to the singular form.
- **Skill metadata**: stable docs require `name` and `description` and a name matching the directory; V2 docs treat `name` as a display label and take the ID from the path. This repository satisfies both.
- **Permissions**: the agent uses `permission:` with `edit`, `webfetch` and `bash` patterns. Older releases used a `tools:` map; if your version rejects `permission`, check its docs and adapt the frontmatter.
- **Subagent commands**: when a command targets a subagent it may run in a child session. The audit command targets a `mode: all` agent so it runs in the current session.

## Persian text looks wrong in my terminal

Terminal emulators often do not shape right-to-left text; letters may appear disconnected or reversed even though the file is correct. Inspect the file in an editor with RTL support or in a browser. This is a display limitation, not a content problem.

## The audit seems to edit files

It should not: the command says read-only and the agent's `edit` permission is `ask`. If edits are proposed, deny them and report it. For a hard guarantee, set `edit: deny` in the agent's frontmatter (then the agent cannot implement fixes until you change it back).
