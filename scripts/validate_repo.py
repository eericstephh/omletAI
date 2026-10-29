#!/usr/bin/env python3
"""Structural validator for the persian-ui-ux-agent repository.

Dependency-free (standard library only). It checks repository STRUCTURE and
basic frontmatter shape. It is NOT a YAML parser and NOT a substitute for
testing discovery with a real OpenCode installation; see
scripts/check_opencode_discovery.py and docs/COMPATIBILITY.md for that.

Usage:
    python scripts/validate_repo.py [--root PATH] [--strict]

Exit codes: 0 = no errors, 1 = errors found (or warnings with --strict).
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

AGENT_NAME = "Persian-UI-Specialist"
COMMAND_NAME = "persian-ui-audit"
REQUIRED_SKILLS = (
    "persian-rtl-layout",
    "persian-typography",
    "persian-ux-writing",
    "persian-localization",
    "bidirectional-content",
    "persian-ui-quality",
    "persian-accessibility",
)
REQUIRED_FILES = (
    "README.md",
    "CHANGELOG.md",
    "LICENSE",
    "SECURITY.md",
    ".gitignore",
    "docs/INSTALLATION.md",
    "docs/COMPATIBILITY.md",
    "docs/DESIGN-CHECKLIST.md",
    "docs/CONTRIBUTING.md",
    "docs/TROUBLESHOOTING.md",
    "scripts/validate_repo.py",
    "tests/test_repo_structure.py",
    ".github/workflows/validate.yml",
)
VALID_MODES = {"primary", "subagent", "all"}
BUILTIN_AGENTS = {"build", "plan", "general", "explore"}
SKILL_NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
KEY_RE = re.compile(r"^([A-Za-z0-9_][A-Za-z0-9_.\-/]*)\s*:\s*(.*)$")
LINK_RE = re.compile(r"(?<!\!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE_RE = re.compile(r"^(```|~~~)")
SECRET_PATTERNS = {
    "OpenAI/Anthropic-style key": re.compile(r"\bsk-[A-Za-z0-9_\-]{20,}"),
    "GitHub token": re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}"),
    "GitHub fine-grained token": re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"),
    "AWS access key id": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "Slack token": re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}"),
    "Private key block": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
}
SKIP_DIRS = {".git", "node_modules", "__pycache__", ".pytest_cache", ".venv", "dist"}
TEXT_SUFFIXES = {".md", ".py", ".yml", ".yaml", ".json", ".txt", ".toml", ".cfg", ".ini", ".sh", ".ps1", ""}


class Result:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, path: str, msg: str) -> None:
        self.errors.append(f"{path}: {msg}")

    def warn(self, path: str, msg: str) -> None:
        self.warnings.append(f"{path}: {msg}")


def parse_frontmatter(text: str) -> tuple[dict[str, str] | None, str | None]:
    """Parse a minimal frontmatter block.

    Returns (fields, error). Only top-level `key: value` pairs are read;
    indented lines are treated as belonging to the previous key (nested
    values such as `permission:` maps are recorded as an empty string).
    This is intentionally NOT a full YAML parser.
    """
    text = text.lstrip("\ufeff")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return None, "file must start with a '---' frontmatter delimiter"
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
    if end is None:
        return None, "frontmatter is not closed with a second '---' line"
    fields: dict[str, str] = {}
    for n, line in enumerate(lines[1:end], start=2):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line[0] in " \t":
            continue  # nested content of the previous key
        m = KEY_RE.match(line)
        if not m:
            return None, f"line {n}: cannot parse frontmatter line {line!r}"
        value = m.group(2).strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        fields[m.group(1)] = value
    return fields, None


def body_after_frontmatter(text: str) -> str:
    lines = text.lstrip("\ufeff").splitlines()
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            return "\n".join(lines[i + 1 :])
    return ""


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def rel(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()


def check_required_files(root: Path, res: Result) -> None:
    for name in REQUIRED_FILES:
        p = root / name
        if not p.is_file():
            res.error(name, "required file is missing")
        elif p.stat().st_size == 0:
            res.error(name, "required file is empty")


def check_agent(root: Path, res: Result) -> None:
    agents_dir = root / ".opencode" / "agents"
    path = agents_dir / f"{AGENT_NAME}.md"
    shown = rel(root, path) if agents_dir.exists() else f".opencode/agents/{AGENT_NAME}.md"
    if not path.is_file():
        res.error(shown, "agent file is missing")
        return
    text = read(path)
    if not text.strip():
        res.error(shown, "agent file is empty")
        return
    fields, err = parse_frontmatter(text)
    if err:
        res.error(shown, err)
        return
    assert fields is not None
    if not fields.get("description"):
        res.error(shown, "frontmatter 'description' is required")
    mode = fields.get("mode")
    if mode is not None and mode not in VALID_MODES:
        res.error(shown, f"mode {mode!r} is not one of {sorted(VALID_MODES)}")
    if not body_after_frontmatter(text).strip():
        res.error(shown, "agent has no prompt body")


def check_command(root: Path, res: Result) -> None:
    path = root / ".opencode" / "commands" / f"{COMMAND_NAME}.md"
    shown = f".opencode/commands/{COMMAND_NAME}.md"
    if not path.is_file():
        res.error(shown, "command file is missing")
        return
    text = read(path)
    if not text.strip():
        res.error(shown, "command file is empty")
        return
    fields, err = parse_frontmatter(text)
    if err:
        res.error(shown, err)
        return
    assert fields is not None
    if not fields.get("description"):
        res.error(shown, "frontmatter 'description' is required")
    if "template" in fields:
        res.error(shown, "'template' must not be in frontmatter; the Markdown body is the template")
    agent = fields.get("agent")
    if agent:
        known = BUILTIN_AGENTS | {p.stem for p in (root / ".opencode" / "agents").glob("*.md")}
        if agent not in known:
            res.error(shown, f"agent {agent!r} does not exist in .opencode/agents or built-ins")
    if not body_after_frontmatter(text).strip():
        res.error(shown, "command has no body (template)")


def check_skills(root: Path, res: Result) -> None:
    skills_dir = root / ".opencode" / "skills"
    if not skills_dir.is_dir():
        res.error(".opencode/skills", "skills directory is missing")
        return
    seen: dict[str, str] = {}
    present_dirs = {p.name for p in skills_dir.iterdir() if p.is_dir()}
    for required in REQUIRED_SKILLS:
        if required not in present_dirs:
            res.error(f".opencode/skills/{required}", "required skill directory is missing")
    for d in sorted(present_dirs):
        skill_dir = skills_dir / d
        shown = f".opencode/skills/{d}/SKILL.md"
        # Exact-case check (case-insensitive file systems would hide 'skill.md').
        if "SKILL.md" not in {p.name for p in skill_dir.iterdir()}:
            res.error(shown, "SKILL.md is missing (must be spelled exactly SKILL.md)")
            continue
        text = read(skill_dir / "SKILL.md")
        if not text.strip():
            res.error(shown, "skill file is empty")
            continue
        fields, err = parse_frontmatter(text)
        if err:
            res.error(shown, err)
            continue
        assert fields is not None
        name = fields.get("name", "")
        desc = fields.get("description", "")
        if not name:
            res.error(shown, "frontmatter 'name' is required")
        else:
            if name != d:
                res.error(shown, f"name {name!r} must match directory name {d!r}")
            if len(name) > 64 or not SKILL_NAME_RE.match(name):
                res.error(shown, f"name {name!r} must be 1-64 chars of lowercase letters, digits, single hyphens")
            if name in seen:
                res.error(shown, f"duplicate skill name {name!r} (also in {seen[name]})")
            seen[name] = shown
        if not desc:
            res.error(shown, "frontmatter 'description' is required")
        elif not (1 <= len(desc) <= 1024):
            res.error(shown, f"description length {len(desc)} must be 1-1024 characters")
        if not body_after_frontmatter(text).strip():
            res.error(shown, "skill has no body")


def iter_text_files(root: Path):
    for p in root.rglob("*"):
        if not p.is_file() or any(part in SKIP_DIRS for part in p.relative_to(root).parts):
            continue
        if p.suffix.lower() in TEXT_SUFFIXES:
            yield p


def check_links(root: Path, res: Result) -> None:
    for p in iter_text_files(root):
        if p.suffix.lower() != ".md":
            continue
        in_fence = False
        for n, line in enumerate(read(p).splitlines(), start=1):
            if FENCE_RE.match(line.strip()):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for target in LINK_RE.findall(line):
                if re.match(r"^[a-zA-Z][a-zA-Z0-9+.\-]*:", target) or target.startswith("#"):
                    continue
                path_part = target.split("#", 1)[0]
                if not path_part:
                    continue
                resolved = (p.parent / path_part).resolve()
                if not resolved.exists():
                    res.error(f"{rel(root, p)}:{n}", f"broken relative link -> {target}")


def check_cross_references(root: Path, res: Result) -> None:
    readme = root / "README.md"
    if not readme.is_file():
        return
    text = read(readme)
    for token in (AGENT_NAME, f"/{COMMAND_NAME}", *REQUIRED_SKILLS):
        if token not in text:
            res.error("README.md", f"does not mention {token!r}")
    changelog = root / "CHANGELOG.md"
    if changelog.is_file() and not re.search(r"^## \[", read(changelog), re.M):
        res.error("CHANGELOG.md", "no '## [version]' heading found")


def check_secrets(root: Path, res: Result) -> None:
    """Heuristic scan for obvious credential formats. Not a full secret scanner."""
    for p in iter_text_files(root):
        try:
            text = read(p)
        except UnicodeDecodeError:
            continue
        for label, pattern in SECRET_PATTERNS.items():
            if pattern.search(text):
                res.error(rel(root, p), f"possible secret detected ({label})")


def validate(root: Path) -> Result:
    res = Result()
    check_required_files(root, res)
    check_agent(root, res)
    check_command(root, res)
    check_skills(root, res)
    check_links(root, res)
    check_cross_references(root, res)
    check_secrets(root, res)
    return res


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent), help="repository root")
    parser.add_argument("--strict", action="store_true", help="treat warnings as errors")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    res = validate(root)
    for w in res.warnings:
        print(f"WARN  {w}")
    for e in res.errors:
        print(f"ERROR {e}")
    failed = bool(res.errors) or (args.strict and bool(res.warnings))
    print(f"\nStructural validation: {'FAILED' if failed else 'PASSED'} "
          f"({len(res.errors)} error(s), {len(res.warnings)} warning(s)) in {root}")
    print("Note: this checks structure only. It does not prove OpenCode discovers the assets;"
          " run scripts/check_opencode_discovery.py with a real OpenCode install.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
