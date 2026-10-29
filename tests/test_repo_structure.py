"""Unit tests for scripts/validate_repo.py (standard library only).

Run:  python -m unittest discover -s tests -v
These tests verify the STRUCTURAL validator. They do not test OpenCode itself.
"""
from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("validate_repo", ROOT / "scripts" / "validate_repo.py")
validate_repo = importlib.util.module_from_spec(_spec)
sys.modules["validate_repo"] = validate_repo
_spec.loader.exec_module(validate_repo)  # type: ignore[union-attr]

IGNORE = shutil.ignore_patterns(".git", "__pycache__", "node_modules", ".pytest_cache")


class FrontmatterParsing(unittest.TestCase):
    def test_valid(self):
        fields, err = validate_repo.parse_frontmatter("---\nname: a\ndescription: \"b: c\"\n---\nbody\n")
        self.assertIsNone(err)
        self.assertEqual(fields, {"name": "a", "description": "b: c"})

    def test_nested_keys_do_not_break_parsing(self):
        fields, err = validate_repo.parse_frontmatter(
            "---\ndescription: x\npermission:\n  edit: ask\n  bash:\n    \"*\": ask\n---\nbody\n")
        self.assertIsNone(err)
        self.assertEqual(fields["description"], "x")
        self.assertIn("permission", fields)

    def test_missing_opening_delimiter(self):
        _, err = validate_repo.parse_frontmatter("name: a\n---\n")
        self.assertIn("must start", err)

    def test_missing_closing_delimiter(self):
        _, err = validate_repo.parse_frontmatter("---\nname: a\n")
        self.assertIn("not closed", err)

    def test_unparseable_line(self):
        _, err = validate_repo.parse_frontmatter("---\nthis is not a key value\n---\n")
        self.assertIn("cannot parse", err)

    def test_bom_is_tolerated(self):
        fields, err = validate_repo.parse_frontmatter("\ufeff---\nname: a\n---\nbody")
        self.assertIsNone(err)
        self.assertEqual(fields["name"], "a")


class RepoValidation(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name) / "repo"
        shutil.copytree(ROOT, self.root, ignore=IGNORE)

    def errors(self):
        return validate_repo.validate(self.root).errors

    def assertHasError(self, fragment):
        errs = self.errors()
        self.assertTrue(any(fragment in e for e in errs), f"expected {fragment!r} in {errs}")

    def test_real_repository_passes(self):
        res = validate_repo.validate(ROOT)
        self.assertEqual(res.errors, [])

    def test_missing_required_skill(self):
        shutil.rmtree(self.root / ".opencode" / "skills" / "persian-accessibility")
        self.assertHasError("persian-accessibility")

    def test_all_seven_skills_required(self):
        self.assertEqual(len(validate_repo.REQUIRED_SKILLS), 7)
        self.assertEqual(len(set(validate_repo.REQUIRED_SKILLS)), 7)

    def test_skill_name_must_match_directory(self):
        p = self.root / ".opencode" / "skills" / "persian-typography" / "SKILL.md"
        p.write_text(p.read_text(encoding="utf-8").replace("name: persian-typography", "name: wrong-name", 1), encoding="utf-8")
        self.assertHasError("must match directory name")

    def test_invalid_skill_name_characters(self):
        d = self.root / ".opencode" / "skills" / "Bad_Name"
        d.mkdir()
        (d / "SKILL.md").write_text("---\nname: Bad_Name\ndescription: x\n---\nbody\n", encoding="utf-8")
        self.assertHasError("lowercase letters")

    def test_skill_name_copied_from_another_skill_is_rejected(self):
        d = self.root / ".opencode" / "skills" / "dup-copy"
        d.mkdir()
        (d / "SKILL.md").write_text("---\nname: persian-typography\ndescription: x\n---\nbody\n", encoding="utf-8")
        self.assertHasError("must match directory name")

    def test_empty_skill_file(self):
        (self.root / ".opencode" / "skills" / "persian-ui-quality" / "SKILL.md").write_text("", encoding="utf-8")
        self.assertHasError("skill file is empty")

    def test_skill_missing_description(self):
        p = self.root / ".opencode" / "skills" / "bidirectional-content" / "SKILL.md"
        p.write_text("---\nname: bidirectional-content\n---\nbody\n", encoding="utf-8")
        self.assertHasError("'description' is required")

    def test_skill_description_too_long(self):
        p = self.root / ".opencode" / "skills" / "bidirectional-content" / "SKILL.md"
        p.write_text("---\nname: bidirectional-content\ndescription: " + "x" * 1025 + "\n---\nbody\n", encoding="utf-8")
        self.assertHasError("1-1024")

    def test_wrong_case_skill_filename(self):
        d = self.root / ".opencode" / "skills" / "persian-localization"
        (d / "SKILL.md").rename(d / "skill.md")
        self.assertHasError("must be spelled exactly SKILL.md")

    def test_agent_missing(self):
        (self.root / ".opencode" / "agents" / "Persian-UI-Specialist.md").unlink()
        self.assertHasError("agent file is missing")

    def test_agent_invalid_mode(self):
        p = self.root / ".opencode" / "agents" / "Persian-UI-Specialist.md"
        p.write_text(p.read_text(encoding="utf-8").replace("mode: all", "mode: bogus", 1), encoding="utf-8")
        self.assertHasError("mode 'bogus'")

    def test_agent_missing_description(self):
        p = self.root / ".opencode" / "agents" / "Persian-UI-Specialist.md"
        p.write_text("---\nmode: all\n---\nprompt\n", encoding="utf-8")
        self.assertHasError("'description' is required")

    def test_command_template_in_frontmatter_rejected(self):
        p = self.root / ".opencode" / "commands" / "persian-ui-audit.md"
        p.write_text("---\ndescription: x\ntemplate: nope\n---\nbody\n", encoding="utf-8")
        self.assertHasError("'template' must not be in frontmatter")

    def test_command_references_unknown_agent(self):
        p = self.root / ".opencode" / "commands" / "persian-ui-audit.md"
        p.write_text("---\ndescription: x\nagent: ghost\n---\nbody\n", encoding="utf-8")
        self.assertHasError("does not exist")

    def test_command_missing(self):
        (self.root / ".opencode" / "commands" / "persian-ui-audit.md").unlink()
        self.assertHasError("command file is missing")

    def test_unclosed_frontmatter_detected(self):
        p = self.root / ".opencode" / "skills" / "persian-rtl-layout" / "SKILL.md"
        p.write_text("---\nname: persian-rtl-layout\ndescription: x\nbody without closing\n", encoding="utf-8")
        self.assertHasError("not closed")

    def test_missing_required_doc(self):
        (self.root / "docs" / "TROUBLESHOOTING.md").unlink()
        self.assertHasError("docs/TROUBLESHOOTING.md")

    def test_missing_license(self):
        (self.root / "LICENSE").unlink()
        self.assertHasError("LICENSE")

    def test_broken_relative_link(self):
        (self.root / "docs" / "INSTALLATION.md").write_text("[x](./does-not-exist.md)\n", encoding="utf-8")
        self.assertHasError("broken relative link")

    def test_links_inside_code_fences_ignored(self):
        p = self.root / "docs" / "INSTALLATION.md"
        p.write_text(p.read_text(encoding="utf-8") + "\n```\n[x](./nope.md)\n```\n", encoding="utf-8")
        self.assertEqual([e for e in self.errors() if "nope.md" in e], [])

    def test_secret_detected(self):
        (self.root / "docs" / "leak.md").write_text("token ghp_" + "a" * 36 + "\n", encoding="utf-8")
        self.assertHasError("possible secret")

    def test_readme_must_reference_skills(self):
        p = self.root / "README.md"
        p.write_text(p.read_text(encoding="utf-8").replace("persian-accessibility", "removed"), encoding="utf-8")
        self.assertHasError("does not mention 'persian-accessibility'")


if __name__ == "__main__":
    unittest.main()
