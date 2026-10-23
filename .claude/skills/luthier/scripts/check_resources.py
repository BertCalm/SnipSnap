#!/usr/bin/env python3
"""Check the complete Luthier bundle without importing its DSP dependencies.

The script locates the bundle from its own resolved path, so it works through
the Codex discovery symlink, from any working directory, and in an unpacked ZIP.
"""
from __future__ import annotations

import ast
import json
from pathlib import Path
import re
import sys


REFERENCES = (
    "combination-grammar.md",
    "dsp-catalog.md",
    "engine-inventory.md",
    "gear-modeling.md",
    "omnibus-mine.md",
    "reuse-and-licenses.md",
    "review-rubric.md",
    "snipsnap-conventions.md",
    "spec-template.md",
)
HARNESS_SCRIPTS = ("probes.py", "proto_template.py", "snipkit.py")
RESOURCE_PATH = re.compile(r"(?:references|scripts)/[A-Za-z0-9_./-]+\.(?:md|py)\b")


def check(root: Path) -> list[str]:
    errors: list[str] = []
    texts: dict[str, str] = {}

    def read(relative: str) -> str | None:
        if relative in texts:
            return texts[relative]
        path = root / relative
        if not path.resolve().is_relative_to(root):
            errors.append(f"Resource leaves the skill folder: {relative}")
            return None
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as error:
            errors.append(f"Cannot read {relative}: {error}")
            return None
        if not text.strip():
            errors.append(f"Empty resource: {relative}")
            return None
        texts[relative] = text
        return text

    required = {"SKILL.md", "README.md", "evals/evals.json"}
    required.update(f"references/{name}" for name in REFERENCES)
    required.update(f"scripts/{name}" for name in HARNESS_SCRIPTS)
    for relative in sorted(required):
        read(relative)

    # Follow newly added references as well as checking the original bundle.
    # External example input paths in eval prompts are not bundle resources.
    skill = texts.get("SKILL.md", "")
    for relative in sorted(set(RESOURCE_PATH.findall(skill)) - required):
        read(relative)
    for directory, pattern in (("references", "*.md"), ("scripts", "*.py")):
        for path in sorted((root / directory).rglob(pattern)):
            read(path.relative_to(root).as_posix())

    for relative, text in sorted(texts.items()):
        if relative.endswith(".py"):
            try:
                ast.parse(text, filename=relative)
            except SyntaxError as error:
                errors.append(f"Invalid Python in {relative}:{error.lineno}: {error.msg}")

    if "evals/evals.json" in texts:
        try:
            evaluations = json.loads(texts["evals/evals.json"])
            if not isinstance(evaluations, dict) or evaluations.get("skill_name") != "luthier":
                errors.append("evals/evals.json must describe the luthier skill")
            elif not isinstance(evaluations.get("evals"), list) or not evaluations["evals"]:
                errors.append("evals/evals.json must contain a nonempty evals list")
        except json.JSONDecodeError as error:
            errors.append(f"Invalid JSON in evals/evals.json:{error.lineno}: {error.msg}")

    # A standalone luthier/ ZIP has no repository wrappers. In SnipSnap, both
    # discovery locations must lead to this same folder rather than diverging copies.
    if root.parent.name == "skills" and root.parent.parent.name == ".claude":
        repo = root.parents[2]
        codex = repo / ".agents" / "skills" / "luthier"
        if not (codex / "SKILL.md").is_file() or codex.resolve() != root:
            errors.append(
                ".agents/skills/luthier must resolve to .claude/skills/luthier "
                "with a readable SKILL.md"
            )
    return errors


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    errors = check(root)
    if errors:
        print("Luthier resource check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(
        f"Luthier resources OK: {len(REFERENCES)} reference documents, "
        f"{len(HARNESS_SCRIPTS)} harness scripts, eval JSON, and referenced files."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
