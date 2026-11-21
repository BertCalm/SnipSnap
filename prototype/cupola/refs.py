"""Where the CUPOLA prototype finds things.

The owner's reference recordings (handpan, sundrum, tongue-drum and tube loops from commercial
sample packs) are NOT in the repository: they are not ours to redistribute. Put them in one or
more folders and point CUPOLA_REFS at them (os.pathsep-separated). Files are found by name
pattern, so a leading upload prefix like "8145e9e6-" does not matter.

Rendered audio goes to testkit/cupola-listen/ (gitignored), or CUPOLA_OUT.
"""
from __future__ import annotations

import glob
import os
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LUTHIER_SCRIPTS = REPO / ".claude" / "skills" / "luthier" / "scripts"
OUT = Path(os.environ.get("CUPOLA_OUT", REPO / "testkit" / "cupola-listen"))


def ref_dirs() -> list[str]:
    raw = os.environ.get("CUPOLA_REFS", "")
    dirs = [d for d in raw.split(os.pathsep) if d]
    if not dirs:
        raise SystemExit("Set CUPOLA_REFS to the folder(s) holding the reference recordings (see refs.py).")
    return dirs


def find(pattern: str) -> list[str]:
    """Every reference file whose name contains `pattern` (a glob fragment), sorted."""
    hits = []
    for d in ref_dirs():
        hits += glob.glob(os.path.join(d, f"*{pattern}*"))
    return sorted(hits)


def one(pattern: str) -> str:
    hits = find(pattern)
    if len(hits) != 1:
        raise SystemExit(f"expected one reference matching {pattern!r}, found {len(hits)}")
    return hits[0]
