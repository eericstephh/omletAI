#!/usr/bin/env python3
"""Runtime discovery check against a REAL OpenCode installation.

Copies this repository's .opencode/ into a temporary project, points OpenCode
at isolated config/data/cache dirs (your real user config is never touched),
then asks OpenCode itself what it discovered.

Usage:
    python scripts/check_opencode_discovery.py [--opencode PATH_OR_NAME]

Exit codes: 0 = discovered everything, 1 = something missing/invalid,
            2 = BLOCKED (OpenCode not found or could not run).
This script does NOT invoke a model, so it cannot prove the agent or command
behaves well; it only proves OpenCode loads them.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AGENT = "Persian-UI-Specialist"
COMMAND = "persian-ui-audit"
SKILLS = (
    "persian-rtl-layout", "persian-typography", "persian-ux-writing", "persian-localization",
    "bidirectional-content", "persian-ui-quality", "persian-accessibility",
)


def run_json(opencode: str, args: list[str], cwd: Path, env: dict[str, str]):
    proc = subprocess.run([opencode, *args], cwd=cwd, env=env, capture_output=True,
                          text=True, encoding="utf-8", timeout=120, shell=(os.name == "nt"))
    if proc.returncode != 0:
        raise RuntimeError(f"`opencode {' '.join(args)}` exited {proc.returncode}: {proc.stderr.strip()[:300]}")
    return json.loads(proc.stdout)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--opencode", default="opencode")
    opencode = ap.parse_args().opencode
    resolved = shutil.which(opencode) or (opencode if Path(opencode).exists() else None)
    if not resolved:
        print("BLOCKED: opencode executable not found (install it or pass --opencode).")
        return 2
    with tempfile.TemporaryDirectory(prefix="persian-ui-ux-agent-") as tmp:
        tmp_path = Path(tmp)
        project = tmp_path / "project"
        project.mkdir()
        shutil.copytree(ROOT / ".opencode", project / ".opencode")
        env = dict(os.environ)
        for var, sub in (("XDG_CONFIG_HOME", "config"), ("XDG_DATA_HOME", "data"),
                         ("XDG_CACHE_HOME", "cache"), ("XDG_STATE_HOME", "state")):
            env[var] = str(tmp_path / "xdg" / sub)
        try:
            version = subprocess.run([resolved, "--version"], capture_output=True, text=True,
                                     env=env, timeout=60, shell=(os.name == "nt")).stdout.strip()
            config = run_json(resolved, ["debug", "config"], project, env)
            skills = run_json(resolved, ["debug", "skill"], project, env)
        except (RuntimeError, OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
            print(f"BLOCKED: could not query OpenCode: {exc}")
            return 2
        print(f"OpenCode version: {version}")
        missing = []
        if AGENT not in config.get("agent", {}):
            missing.append(f"agent {AGENT}")
        if COMMAND not in config.get("command", {}):
            missing.append(f"command {COMMAND}")
        found_skills = {s.get("name") for s in skills}
        missing += [f"skill {s}" for s in SKILLS if s not in found_skills]
        for item in (f"agent {AGENT}", f"command {COMMAND}", *[f"skill {s}" for s in SKILLS]):
            print(f"  {'FAIL' if item in missing else 'ok  '}  {item}")
        print("\nDiscovery:", "FAILED" if missing else "PASSED")
        return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
