# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-10-10

### Added
- `Persian-UI-Specialist` agent (`.opencode/agents/`), `mode: all`, read-oriented permissions (`edit: ask`, `webfetch: ask`, bash allowlist for read-only commands).
- `/persian-ui-audit` command (`.opencode/commands/`): read-only, evidence-based audit with 0-5 scoring, severity-grouped findings, staged remediation plan and Passed/Failed/Not-run check log.
- Seven skills (`.opencode/skills/`): `persian-rtl-layout`, `persian-typography`, `persian-ux-writing`, `persian-localization`, `bidirectional-content`, `persian-ui-quality`, `persian-accessibility`.
- Dependency-free structural validator (`scripts/validate_repo.py`) and unit tests (`tests/`).
- Runtime discovery check against a real OpenCode install (`scripts/check_opencode_discovery.py`).
- GitHub Actions workflow (`.github/workflows/validate.yml`).
- Documentation: installation, compatibility evidence, troubleshooting, design checklist, contributing, release steps, security policy.

### Notes
- Directory names use the plural layout from the current official OpenCode documentation (`agents/`, `commands/`, `skills/`). OpenCode 1.18.35 was observed to load both plural and singular names; see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).
