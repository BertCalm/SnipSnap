"""Write the eight SIGIL kits as the Kotlin port's data: synth/src/main/resources/sigil/kits.json.

    python3 prototype/sigil/export_kits.py

kits.py stays the one place a pad is authored; SigilKits.kt reads this file, so the prototype the owner hears and the
kits the app ships cannot drift apart by hand-copying. Run it after any change to kits.py, and bump SigilKits.VERSION
when a pad's audio changes.
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from kits import KITS, ZONES, TAKES  # noqa: E402

OUT = os.path.join(HERE, "..", "..", "synth", "src", "main", "resources", "sigil", "kits.json")


def plain(v):
    if isinstance(v, (tuple, list)):
        return [plain(x) for x in v]
    if isinstance(v, float):
        return round(v, 6)
    return v


def main():
    kits = []
    for k in KITS:
        kits.append({
            "id": k["id"], "title": k["title"], "parent": k.get("parent"), "bpm": k["bpm"], "swing": k["swing"],
            "print": k["print"], "printAmount": k["print_amount"],
            "pads": [{"name": p["name"], "voice": p["voice"], "gain": p["gain"],
                      "params": {n: plain(v) for n, v in p["params"].items()}} for p in k["pads"]],
            "roll": {n: plain(v) for n, v in k["roll"].items()},
            "groove": {
                "bars": k["groove"]["bars"],
                "mix": {str(i): v for i, v in sorted(k["groove"]["mix"].items())},
                "steps": {str(i): s.replace(" ", "") for i, s in sorted(k["groove"]["steps"].items())},
            },
        })
    doc = {"zones": list(ZONES), "takes": TAKES, "kits": kits}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(doc, f, indent=1)
        f.write("\n")
    print("wrote", os.path.normpath(OUT), len(kits), "kits")


if __name__ == "__main__":
    main()
