#!/usr/bin/env python3
"""Assemble the static listening page from generateSpoolAudition's real WAVs."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--renders", type=Path, default=root / "testkit/spool-audition")
    parser.add_argument("--output", type=Path, default=root / "testkit/spool-audition-site")
    parser.add_argument("--source-base", help="Git revision preceding the port (defaults to the engine's introduction parent)")
    args = parser.parse_args()
    renders, output = args.renders.resolve(), args.output.resolve()
    if output == renders or renders in output.parents:
        parser.error("output must be outside the render directory")
    manifest = json.loads((renders / "manifest.json").read_text())
    clips = manifest["clips"]
    assets = set()
    for clip in clips:
        for field in ("rawPath", "matchedPath"):
            relative = Path(clip[field])
            source = (renders / relative).resolve()
            if renders not in source.parents or not source.is_file():
                raise ValueError(f"Missing or invalid audio asset: {relative}")
            assets.add(relative)
    output.mkdir(parents=True, exist_ok=True)
    for name in ("index.html", "styles.css", "app.js"):
        shutil.copy2(root / "scripts/spool-audition" / name, output / name)
    for relative in sorted(assets):
        (output / relative).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(renders / relative, output / relative)
    # Preserve generator metadata and export files without copying stale web bundles.
    for name in ("patches", "kit"):
        if (renders / name).is_dir():
            shutil.copytree(renders / name, output / name, dirs_exist_ok=True)
    for name in ("patches.json",):
        if (renders / name).is_file():
            shutil.copy2(renders / name, output / name)
    if (renders / "diagnostics").is_dir():
        shutil.copytree(renders / "diagnostics", output / "diagnostics", dirs_exist_ok=True)
    engine = root / "synth/src/main/kotlin/com/snipsnap/synth/Spool.kt"
    manifest["dspSourceSha256"] = hashlib.sha256(engine.read_bytes()).hexdigest()
    rendered_hash = manifest.get("provenance", {}).get("sourceSha256")
    if rendered_hash and rendered_hash != manifest["dspSourceSha256"]:
        raise ValueError("Spool source changed since rendering; regenerate the audition first")
    manifest["sourceCommit"] = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    introduction = subprocess.check_output(
        ["git", "log", "--diff-filter=A", "--format=%H", "--", str(engine.relative_to(root))],
        cwd=root, text=True).splitlines()
    base = args.source_base or (introduction[0] + "^" if introduction else "HEAD")
    manifest["sourceBaseCommit"] = subprocess.check_output(
        ["git", "rev-parse", base], cwd=root, text=True).strip()
    manifest["sourceDirty"] = bool(subprocess.check_output(
        ["git", "status", "--porcelain"], cwd=root, text=True).strip())
    manifest["downloads"] = dict(manifest.get("downloads", {}), source="spool-engine.patch")
    kit_files = sorted(path for path in (output / "kit").rglob("*") if path.is_file())
    if kit_files:
        with zipfile.ZipFile(output / "spool-mpc-kit.zip", "w", zipfile.ZIP_DEFLATED) as bundle:
            for path in kit_files:
                bundle.write(path, path.relative_to(output / "kit").as_posix())
        manifest["downloads"]["kit"] = "spool-mpc-kit.zip"
    # Keep each hosted download comfortably below the static provider's 25 MiB
    # per-asset limit. Every part carries the manifest and patch roster.
    entries = [(output / relative, relative.as_posix()) for relative in sorted(assets)]
    for name in ("patches", "kit", "diagnostics"):
        entries.extend((path, path.relative_to(output).as_posix())
                       for path in sorted((output / name).rglob("*")) if path.is_file())
    parts, current, size = [], [], 0
    for path, relative in entries:
        entry_size = path.stat().st_size
        if entry_size > 18 * 1024 * 1024:
            raise ValueError(f"Audio bundle entry exceeds hosting budget: {relative}")
        if current and size + entry_size > 18 * 1024 * 1024:
            parts.append(current)
            current, size = [], 0
        current.append((path, relative))
        size += entry_size
    if current:
        parts.append(current)
    manifest["downloads"]["audioParts"] = [
        {"path": f"spool-audition-audio-{i:02d}.zip", "label": f"Audio · part {i} of {len(parts)}"}
        for i in range(1, len(parts) + 1)]
    manifest["downloads"]["audio"] = manifest["downloads"]["audioParts"][0]["path"]
    text = json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    (output / "manifest.json").write_text(text)
    for old in output.glob("spool-audition-audio*.zip"):
        old.unlink()
    for part, download in zip(parts, manifest["downloads"]["audioParts"]):
        with zipfile.ZipFile(output / download["path"], "w", zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr("manifest.json", text)
            if (output / "patches.json").is_file():
                bundle.write(output / "patches.json", "patches.json")
            for path, relative in part:
                bundle.write(path, relative)
    # Include the complete port, including follow-up fixes, after a clean commit.
    patch = subprocess.check_output(
        ["git", "diff", "--binary", manifest["sourceBaseCommit"]], cwd=root)
    (output / "spool-engine.patch").write_bytes(patch)
    print(f"Prepared {len(clips)} listening cases and {len(assets)} WAVs at {output}")


if __name__ == "__main__":
    main()
