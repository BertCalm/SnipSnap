"""Import the approved trimmed bank from a local audition checkout.

Usage: python scripts/import_velour_round5.py /path/to/velour-audition
The archive hashes pin the accepted delivery; no network fetch or DSP render occurs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

EXPECTED_ARCHIVES = {
    "close": "c8034f26239c6a3d4e7655376acd868c37405c9113322dba1ac39523bef79021",
    "velour": "335d4f841a1747d42251d87c03042bc82d3290a76ef02de4fb05b75bcfea4930",
    "bloom": "7131004056409ba62b9629dfa43b9746ea7c35df5d5f028dafe55736abec1285",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audition_checkout", type=Path)
    site = parser.parse_args().audition_checkout
    repo = Path(__file__).resolve().parents[1]
    dest = repo / "synth/src/main/resources/velour/approved-v1"
    # Validate the entire delivery before replacing any bank file.
    for kind, expected in EXPECTED_ARCHIVES.items():
        archive = site / "dist/round5" / f"velour-{kind}-r5.zip"
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise ValueError(f"{kind}: not the approved trimmed archive")
    manifest = {
        "version": 1,
        "approval": "Owner approved reconstruction 2026-11-10 and trimmed delivery 2026-11-12",
        "audition": "https://velour-drum-audition.bertcalm.chatgpt.site/round5/",
        "siteCommit": "147679e4a88d61ad1fdb735a0b75e1881b1baad0",
        "reference": "https://github.com/sgossner/VSCO-2-CE",
        "referenceLicense": "CC0-1.0",
        "kits": {},
    }
    dest.mkdir(parents=True, exist_ok=True)
    for kind in EXPECTED_ARCHIVES:
        with zipfile.ZipFile(site / "dist/round5" / f"velour-{kind}-r5.zip") as archive:
            meta = next(name for name in archive.namelist() if name.endswith("kit.json"))
            kit = json.loads(archive.read(meta))
            parent = Path(meta).parent
            pads = []
            for pad in kit["pads"]:
                layers = []
                for zone, layer in zip(("soft", "mid", "hard"), pad["layers"], strict=True):
                    name = f'{kind}/{pad["slot"]:02d}-{zone}.wav'
                    data = archive.read(str(parent / layer["sample"]))
                    path = dest / name
                    path.parent.mkdir(exist_ok=True)
                    path.write_bytes(data)
                    layers.append({"file": name, "sha256": hashlib.sha256(data).hexdigest()})
                pads.append({
                    "slot": pad["slot"], "name": pad["name"], "class": pad["class"],
                    "level": pad["level"], "muteGroup": pad["muteGroup"], "layers": layers,
                })
            manifest["kits"][kind] = pads
    (dest / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    shutil.copyfile(site / "source/tambourine/LICENSE", dest / "REFERENCE-LICENSE.txt")
    print("Imported 144 exact trimmed PCM24 files; bytes:",
          sum(path.stat().st_size for path in dest.rglob("*.wav")))


if __name__ == "__main__":
    main()
