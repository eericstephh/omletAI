# Contributing

Thanks for helping improve Persian-first UI tooling. Please keep changes small and evidence-based.

## Principles

- **Verify, do not assume.** Claims about OpenCode behavior need evidence from the official docs or a command run against a real installation. Record it in [COMPATIBILITY.md](COMPATIBILITY.md).
- **Actionable content only.** Skills should contain steps, examples and acceptance checklists, not general advice.
- **No unnecessary dependencies.** The repository is dependency-free on purpose.
- **No secrets or personal data**, ever.
- Keep identifiers (directory names, skill names, file names) in English. Persian is welcome in examples, glossaries and user-facing documentation. Persian examples must use Persian ی/ک and correct نیم‌فاصله.

## Setup and validation

Tested with Python 3.12 (CI uses 3.12); other Python 3 versions are untested. No packages to install.

```bash
python scripts/validate_repo.py                  # structure, frontmatter, links, secrets heuristic
python -m unittest discover -s tests -v          # unit tests for the validator
python scripts/check_opencode_discovery.py       # optional: needs OpenCode on PATH; exit 2 = blocked
```

Run all three before opening a pull request. Report honestly which ones you ran and the results; a check you did not run is "not run".

## Adding or changing a skill

1. Create `.opencode/skills/<name>/SKILL.md`. Name: lowercase letters, digits, single hyphens; must equal the directory name; unique.
2. Frontmatter must include `name` and `description` (1-1024 characters; say what it does *and when to use it*).
3. Include: when to use, concrete instructions, a procedure, and an acceptance checklist.
4. Update `README.md`, `CHANGELOG.md`, and `REQUIRED_SKILLS` in `scripts/validate_repo.py` if it becomes a required skill.

## Changing the agent or command

- Keep the agent read-oriented (`edit: ask`) unless there is a strong reason. Document permission changes in the pull request.
- `template` must not be in command frontmatter.
- Re-run discovery with a real OpenCode install and paste the result.

## Pull request expectations

- Describe the problem, the change, and how you verified it.
- Update documentation and the changelog (`[Unreleased]`).
- Keep unrelated formatting changes out.
- By contributing you agree your contribution is licensed under the MIT license of this repository.
