#!/usr/bin/env python3
"""Build a lossless Embouchure hosting derivative below the Sites archive cap.

Usage:
  python3 scripts/package_embouchure_audition.py \
    testkit/embouchure-preset-audition testkit/embouchure-hosted-audition

The complete generated audition and its offline listening packs are read-only.
Only identical audition WAVs share URLs. Native MPC files retain their original
bytes and paths; their hosted ZIPs contain complete kits without duplicated
listening audio or pages. No synthesis, normalization or PCM conversion occurs.
"""

import argparse
import copy
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET
import zipfile


MIB = 1024 * 1024
AUDIO_KEYS = ("path", "rawPath", "matchedPath")
INLINE_MANIFEST = re.compile(
    r'<script id="embouchure-manifest" type="application/json">[\s\S]*?</script>\s*'
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def relative_path(value):
    path = PurePosixPath(value)
    require(value and not path.is_absolute() and ".." not in path.parts and "\\" not in value,
            "unsafe relative asset path: " + value)
    return path.as_posix()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(MIB):
            digest.update(chunk)
    return digest.hexdigest()


def inventory(root):
    return {p.relative_to(root).as_posix(): sha256(p)
            for p in sorted(root.rglob("*")) if p.is_file()}


def tree_hash(files):
    digest = hashlib.sha256()
    for path, checksum in sorted(files.items()):
        digest.update(path.encode("utf-8") + b"\0" + checksum.encode("ascii") + b"\n")
    return digest.hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n",
                    encoding="utf-8")


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from strings(child)


def native_references(root, kit):
    """Resolve the actual MPC 2/3 sample references, not only sidecar paths."""
    expected = {PurePosixPath(p).name for p in kit["samplePaths"]}
    sample_directory = (root / kit["mpc2Path"]).parent
    program = (root / kit["mpc3Path"]).read_bytes()
    if program[:2] == b"\x1f\x8b":
        program = gzip.decompress(program)
    text = program.decode("utf-8")
    track = json.loads(text[text.index("{"):])
    mpc3 = {relative_path(s) for s in strings(track) if s.lower().endswith(".wav")}
    document = ET.parse(root / kit["mpc2Path"])
    mpc2 = set()
    for element in document.iter():
        if element.tag in {"SampleName", "SampleFile"} and element.text and element.text.strip():
            name = element.text.strip()
            mpc2.add(relative_path(name if name.lower().endswith(".wav") else name + ".wav"))
    require(mpc3 == expected and mpc2 == expected,
            "MPC program/sample mapping differs: " + kit["id"])
    require(all((sample_directory / name).is_file() for name in expected),
            "MPC sample reference is missing: " + kit["id"])
    return {"mpc3SampleReferences": len(mpc3), "mpc2SampleReferences": len(mpc2)}


def package_kit(source, destination, kit, source_files):
    directory = relative_path(kit["directory"])
    paths = sorted(path for path in source_files if path.startswith(directory + "/"))
    require(paths and len(kit["samplePaths"]) == kit["pads"], "incomplete native kit")
    for key in ("mpc3Path", "mpc2Path", "recipePath"):
        require(relative_path(kit[key]) in paths, "native program or recipe missing")
    require(all(relative_path(p) in paths for p in kit["samplePaths"]),
            "native kit sample missing")
    archive = relative_path(kit["bundlePath"])
    target = destination / archive
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for path in paths:
            entry = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, (source / path).read_bytes(), compresslevel=9)
    with zipfile.ZipFile(target) as bundle:
        require(bundle.namelist() == paths and bundle.testzip() is None,
                "native kit ZIP closure or CRC failed")
        for path in paths:
            require(hashlib.sha256(bundle.read(path)).hexdigest() == source_files[path],
                    "native kit archive changed bytes: " + path)
    require(target.stat().st_size < 25 * MIB, "native kit ZIP exceeds the per-asset cap")
    references = native_references(destination, kit)
    description = (
        f"Complete {kit['pads']}-pad MPC 3 and MPC 2 kit with its original PCM24 samples, "
        "native patch recipes and README. Keep the programs with their sample folders. "
        "This kit download contains no offline audition page or listening-probe collection; "
        "the full audition remains on the website."
    )
    descriptor = {
        "id": kit["id"], "title": kit["name"] + " MPC kit", "kind": "mpc-kit",
        "path": archive, "description": description, "kitIds": [kit["id"]],
        "pads": kit["pads"], "sampleCount": len(kit["samplePaths"]),
        "containsAuditionPage": False, "containsAuditionAudio": False,
        "bytes": target.stat().st_size,
    }
    return descriptor, {"id": kit["id"], "files": len(paths), "samples": len(kit["samplePaths"]),
                        "archiveSha256": sha256(target), **references}


def package(source, destination, player, maximum_bytes):
    manifest_path = source / "manifest.json"
    require(manifest_path.is_file(), "source needs a generated manifest.json")
    require(source != destination and source not in destination.parents and destination not in source.parents,
            "destination must be separate from the complete source collection")
    require(not destination.exists() or destination.is_dir() and not any(destination.iterdir()),
            "destination must be absent or empty")
    for name in ("index.html", "app.js", "styles.css"):
        require((player / name).is_file(), "player resource missing: " + name)
    source_files = inventory(source)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(manifest["engine"] == "Embouchure" and manifest["completeMatrix"] and not manifest["quick"],
            "source must be the complete Embouchure audition")
    clips = manifest["clips"]
    require(len({row["id"] for row in clips}) == len(clips), "duplicate logical clip IDs")
    original_paths = [relative_path(row[key]) for row in clips for key in AUDIO_KEYS if key in row]
    require(all(path.startswith("audio/") and path.endswith(".wav") for path in original_paths),
            "only audition WAV paths may be aliased")
    require(len(set(original_paths)) == manifest["counts"]["auditionWavs"],
            "source logical WAV count differs from the manifest")
    require(set(original_paths) == {p for p in source_files if p.startswith("audio/")},
            "source audio namespace contains unlisted files")
    canonical = {}
    mapping = {}
    for path in original_paths:
        checksum = source_files[path]
        mapping[path] = canonical.setdefault(checksum, path)

    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=destination.name + ".tmp-", dir=destination.parent))
    try:
        source_archives = {relative_path(b["path"]) for b in manifest["bundles"]}
        replaced = {"manifest.json", "index.html", "app.js", "styles.css"}
        for path in source_files:
            if path.startswith("audio/") or path in source_archives or path in replaced:
                continue
            target = staging / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source / path, target)
        for path in canonical.values():
            target = staging / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source / path, target)

        hosted = copy.deepcopy(manifest)
        audit_audio = []
        for old, row in zip(clips, hosted["clips"]):
            for key in AUDIO_KEYS:
                if key in old:
                    path = old[key]
                    row[key] = mapping[path]
                    checksum = source_files[path]
                    require(sha256(staging / row[key]) == checksum,
                            "logical audition bytes changed: " + old["id"] + "/" + key)
                    audit_audio.append({"id": old["id"], "mode": key, "sourcePath": path,
                                        "hostedPath": row[key], "sha256": checksum})
            unchanged = copy.deepcopy(row)
            for key in AUDIO_KEYS:
                if key in old:
                    unchanged[key] = old[key]
            require(unchanged == old, "logical recipe or measurements changed: " + old["id"])

        bundles, native_audit = [], []
        for kit in hosted["kits"]:
            descriptor, audit = package_kit(source, staging, kit, source_files)
            bundles.append(descriptor)
            native_audit.append(audit)
            for path, checksum in source_files.items():
                if path.startswith(kit["directory"] + "/"):
                    require(sha256(staging / path) == checksum, "native kit asset changed: " + path)
        hosted["bundles"] = bundles
        hosted["bundlePath"] = None
        hosted["bundleDescription"] = (
            "Four complete native MPC kit downloads contain programs, PCM24 samples, recipes and README files. "
            "The online bench retains the complete listening collection."
        )
        hosted["counts"]["logicalAuditionWavs"] = len(original_paths)
        hosted["counts"]["uniqueAuditionWavs"] = len(canonical)
        hosted["hosting"] = {
            "schemaVersion": 1, "packaging": "deduplicated-audio-native-kits", "lossless": True,
            "sourceCollection": source.name, "sourceManifestSha256": source_files["manifest.json"],
            "sourceTreeSha256": tree_hash(source_files), "logicalClips": len(clips),
            "logicalAuditionWavs": len(original_paths), "uniqueAuditionWavs": len(canonical),
            "duplicateAuditionFilesRemoved": len(mapping) - len(canonical),
            "nativeKitSamples": sum(len(k["samplePaths"]) for k in hosted["kits"]),
            "audioDeduplication": "Only SHA-256-identical complete WAV files share a URL; every logical render keeps its exact original bytes and recipe.",
            "downloads": "Hosted ZIPs contain complete native MPC kits, without offline listening pages or duplicated audition audio. The full offline audition banks remain preserved in the generated source collection.",
            "manifestDelivery": "manifest.json fetch; the HTML does not duplicate the manifest",
            "packager": "scripts/package_embouchure_audition.py",
        }
        write_json(staging / "manifest.json", hosted)
        html = INLINE_MANIFEST.sub("", (player / "index.html").read_text(encoding="utf-8"))
        require('id="embouchure-manifest"' not in html, "hosted page still embeds the manifest")
        (staging / "index.html").write_text(html, encoding="utf-8")
        for name in ("app.js", "styles.css"):
            shutil.copy2(player / name, staging / name)
        require("mpc-kit" in (staging / "app.js").read_text(encoding="utf-8"),
                "player must distinguish native-only kit downloads from offline audition packs")
        unique_paths = set(mapping.values())
        require({p.relative_to(staging).as_posix() for p in (staging / "audio").glob("*.wav")} == unique_paths,
                "physical audition namespace differs from alias targets")
        require(inventory(source) == source_files, "complete source collection changed during packaging")

        report = {
            "result": "PASS", "packaging": hosted["hosting"]["packaging"],
            "sourceManifestSha256": source_files["manifest.json"], "sourceTreeSha256": tree_hash(source_files),
            "sourceFiles": len(source_files), "sourceUnchanged": True,
            "logicalClips": len(clips), "logicalAuditionWavs": len(original_paths),
            "uniqueAuditionWavs": len(canonical), "unchangedLogicalAudioHashes": len(audit_audio),
            "nativeKitSamples": hosted["hosting"]["nativeKitSamples"],
            "rendererFingerprint": hosted["rendererFingerprint"], "kits": native_audit,
            "bundles": [{"id": b["id"], "bytes": b["bytes"]} for b in bundles],
            "expandedBytes": 0, "expandedMiB": 0, "maximumExpandedBytes": maximum_bytes,
            "audio": audit_audio,
        }
        report_path = staging / "hosting-audit.json"
        write_json(report_path, report)
        for _ in range(8):
            files = [p for p in staging.rglob("*") if p.is_file()]
            size = sum(p.stat().st_size for p in files)
            if report["expandedBytes"] == size:
                break
            report["expandedBytes"] = size
            report["expandedMiB"] = round(size / MIB, 6)
            report["files"] = len(files)
            report["maxAssetBytes"] = max(p.stat().st_size for p in files)
            write_json(report_path, report)
        require(report["expandedBytes"] == sum(p.stat().st_size for p in staging.rglob("*") if p.is_file()),
                "hosting size accounting did not stabilize")
        require(report["expandedBytes"] < maximum_bytes, "hosting derivative exceeds the expanded archive budget")
        require(report["maxAssetBytes"] < 25 * MIB, "hosting derivative exceeds the per-asset budget")
        if destination.exists():
            destination.rmdir()
        staging.rename(destination)
        return {key: value for key, value in report.items() if key not in {"audio", "rendererFingerprint"}}
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Immutable complete generated audition")
    parser.add_argument("destination", type=Path, help="Absent or empty hosting derivative directory")
    parser.add_argument("--player-source", type=Path,
                        default=Path(__file__).resolve().parents[1] / "synth/src/test/resources/embouchure-audition",
                        help="Current repository player resources (index.html, app.js, styles.css)")
    parser.add_argument("--max-expanded-mib", type=float, default=250,
                        help="Expanded output budget; default 250 MiB gives margin below Sites' 256 MiB cap")
    args = parser.parse_args()
    require(0 < args.max_expanded_mib <= 256, "expanded budget must be between 0 and 256 MiB")
    report = package(args.source.resolve(), args.destination.resolve(), args.player_source.resolve(),
                     int(args.max_expanded_mib * MIB))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
