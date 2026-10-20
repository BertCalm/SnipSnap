#!/usr/bin/env python3
"""Package the generated scanned-chain experiment for offline listening."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--standalone", type=Path)
    args = parser.parse_args()
    directory = args.directory.resolve()
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    if "sweeps" not in manifest:
        shutil.copyfile(manifest_path, directory / "pairs-manifest.json")
    sweep_manifest = directory / "sweeps/manifest.json"
    if sweep_manifest.is_file():
        manifest["sweeps"] = json.loads(sweep_manifest.read_text())
    repository = Path(__file__).resolve().parent.parent
    template_path = repository / "scripts/scanned-chain-audition.html"
    template = template_path.read_text()
    placeholder = "__SCANNED_CHAIN_MANIFEST__"
    if template.count(placeholder) != 1:
        raise ValueError("listening template must contain exactly one manifest placeholder")

    # The archive lives beside the generated pack. Do not claim a download
    # inside the page: the unpacked folder need not retain that sibling.
    manifest.pop("packFile", None)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")

    # Keep the audition tied to the source state that generated it. A changed
    # renderer or generator requires regeneration, not just a new archive.
    provenance = manifest.get("provenance", {})
    for file_key, hash_key in (("coreFile", "coreSha256"), ("generatorFile", "generatorSha256")):
        path = (repository / provenance[file_key]).resolve()
        if not path.is_relative_to(repository):
            raise ValueError("implementation path is outside the repository")
        if hashlib.sha256(path.read_bytes()).hexdigest() != provenance[hash_key]:
            raise ValueError(f"{file_key} changed after rendering; regenerate the audition")
    if hashlib.sha256(template_path.read_bytes()).hexdigest() != provenance["pageTemplateSha256"]:
        raise ValueError("page template changed after rendering; regenerate the audition")
    if "sweeps" in manifest:
        # The sweep manifest is also included verbatim. Verify its declared
        # source hashes before attaching it when present.
        sweep_provenance = manifest["sweeps"].get("provenance", manifest["sweeps"])
        for key, relative in (("coreSha256", provenance["coreFile"]),
                              ("generatorSha256", "synth/src/test/kotlin/com/snipsnap/synth/ScannedChainSweepGenerator.kt")):
            if key in sweep_provenance and hashlib.sha256((repository / relative).read_bytes()).hexdigest() != sweep_provenance[key]:
                raise ValueError("sweep implementation changed after rendering; regenerate the sweeps")

    # Source snapshots are reviewable inside the download. They use the real
    # SnipSnap dependencies; the included task excerpt explains reproduction.
    implementation = directory / "implementation"
    inputs = [
        provenance["coreFile"], provenance["generatorFile"],
        "synth/src/test/kotlin/com/snipsnap/synth/ScannedChainPrototypeTest.kt",
        "scripts/scanned-chain-audition.html", "scripts/package-scanned-chain-audition.py",
        "scripts/prepare-scanned-chain-site.py",
        "docs/SCANNED_CHAIN_EXPERIMENT.md",
    ]
    for relative in ["synth/src/test/kotlin/com/snipsnap/synth/ScannedChainSweepGenerator.kt",
                     "synth/src/test/kotlin/com/snipsnap/synth/ScannedChainSweepGeneratorTest.kt"]:
        if (repository / relative).is_file():
            inputs.append(relative)
    for relative in inputs:
        destination = implementation / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(repository / relative, destination)
    build_text = (repository / "synth/build.gradle.kts").read_text()
    marker = 'tasks.register<JavaExec>("generateScannedChainAudition")'
    start = build_text.index(marker)
    task_definition = build_text[start:build_text.index("\n}", start) + 2]
    (implementation / "gradle-task.txt").write_text(task_definition + "\n")
    sweep_marker = 'tasks.register<JavaExec>("generateScannedChainSweeps")'
    if sweep_marker in build_text:
        start = build_text.index(sweep_marker)
        (implementation / "sweep-gradle-task.txt").write_text(
            build_text[start:build_text.index("\n}", start) + 2] + "\n")
    tracked_diff = subprocess.run(["git", "diff", "--", ".gitignore", "synth/build.gradle.kts"],
                                  cwd=repository, capture_output=True, check=True).stdout
    changes = bytearray(tracked_diff)
    for relative in inputs:
        # All inputs are new experiment files. git diff --no-index exits 1
        # when it successfully emits a diff; an error is a distinct status.
        result = subprocess.run(["git", "diff", "--no-index", "--", "/dev/null", relative],
                                cwd=repository, capture_output=True)
        if result.returncode not in (0, 1):
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
        changes.extend(result.stdout)
    (directory / "implementation.patch").write_bytes(changes)

    def html_for(data):
        encoded = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
        return template.replace(placeholder, encoded)

    (directory / "index.html").write_text(html_for(manifest))
    archive = directory.with_suffix(".zip")
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as packed:
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                packed.write(path, path.relative_to(directory))

    standalone = args.standalone or directory.parent / "scanned-chain-listen.html"
    standalone.parent.mkdir(parents=True, exist_ok=True)

    def embed(value):
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                if key in {"rawFile", "matchedFile", "sourcePreview"} and isinstance(item, str):
                    path = (directory / item).resolve()
                    if not path.is_relative_to(directory) or not path.is_file():
                        raise ValueError(f"missing or external listening asset: {item}")
                    result[key] = "data:audio/wav;base64," + base64.b64encode(path.read_bytes()).decode("ascii")
                else:
                    result[key] = embed(item)
            return result
        if isinstance(value, list):
            return [embed(item) for item in value]
        return value

    standalone.write_text(html_for(embed(manifest)))
    print(json.dumps({"archive": str(archive), "archiveBytes": archive.stat().st_size,
                      "standalone": str(standalone), "standaloneBytes": standalone.stat().st_size}))


if __name__ == "__main__":
    main()
