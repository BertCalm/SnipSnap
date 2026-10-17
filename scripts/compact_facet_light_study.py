#!/usr/bin/env python3
"""Remove duplicate section ZIPs for Sites' expanded bundle size limit.

All current and earlier clip WAVs stay byte-identical. The original R2 manifest
is retained as historical metadata; the served archive lists available packages.
Run after generateFacetLightStudy, before source packaging and publication.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("directory", type=Path)
root = parser.parse_args().directory.resolve()
manifest_path = root / "manifest.json"
prior_path = root / "r2-manifest.json"
report_path = root / "light-study/report.json"
manifest = json.loads(manifest_path.read_text())
prior = json.loads(prior_path.read_text())
report = json.loads(report_path.read_text())
assert manifest["revision"] == "light-study-v1"
assert prior["revision"] == "r2-living-source"
prior_clips = [clip for section in prior["sections"] for clip in section["clips"]]
assert len(prior_clips) == 57 and len(report["clips"]) == 12
for clip in prior_clips + report["clips"]:
    for variant in ("raw", "native", "matched"):
        path = (root / clip[variant]).resolve()
        assert path.is_relative_to(root) and sha(path) == clip["sha256"][variant]

original_path = root / "archive/r2/published-manifest.json"
if not original_path.exists():
    original_path.parent.mkdir(parents=True, exist_ok=True)
    original_path.write_bytes(prior_path.read_bytes())
original_sha = sha(original_path)
removed = prior.get("distribution", {}).get("removedDuplicateZipPackages", [])
section_zips = {
    "facet-revision.zip", "facet-identities.zip", "facet-stopped.zip",
    "facet-facets.zip", "facet-mechanism.zip", "facet-controls.zip",
    "facet-performance.zip", "facet-slow-motion.zip",
}
for name in sorted(section_zips):
    path = root / name
    if path.exists():
        removed.append({"path": name, "bytes": path.stat().st_size, "sha256": sha(path)})
        path.unlink()
distribution = {
    "kind": "compact-hosting",
    "packagingScript": "scripts/compact_facet_light_study.py",
    "packagingScriptSha256": sha(Path(__file__).resolve()),
    "originalManifest": "archive/r2/published-manifest.json",
    "originalManifestSha256": original_sha,
    "allPriorClipAudioPreserved": True,
    "removedDuplicateZipPackages": removed,
    "notes": "Individual raw/native/matched WAVs, settings, reports, source, the first-listen pack and R1 originals remain available. Duplicate per-section ZIP packages are omitted from this distribution.",
}
prior["downloads"]["archives"] = [
    item for item in prior["downloads"].get("archives", [])
    if (item.get("path") or item.get("url")) not in section_zips
]
prior["distribution"] = distribution
write_json(prior_path, prior)
summary = report["summary"]
if "previousFilesPreserved" in summary:
    summary["previousFilesVerifiedUnchangedDuringGeneration"] = summary.pop("previousFilesPreserved")
summary["previousClipAudioFilesPreserved"] = len(prior_clips) * 3
report["distribution"] = distribution
manifest["distribution"] = distribution
manifest["summary"] = summary
manifest["previousStudy"]["manifestSha256"] = sha(prior_path)
manifest["previousStudy"]["originalManifest"] = distribution["originalManifest"]
old_note = "Prior R2 audio, metadata and downloads remain unchanged."
new_note = "Prior R2 clip audio remains unchanged; distribution metadata lists retained downloads."
for document in (report, manifest):
    if "metricNotes" in document:
        document["metricNotes"] = document["metricNotes"].replace(old_note, new_note)
readme_path = root / "light-study/README.txt"
readme_path.write_text(readme_path.read_text().replace(old_note, new_note).replace(
    "Previous R2 assets remain intact.", "Previous R2 clip audio remains intact."))
write_json(report_path, report)
write_json(manifest_path, manifest)

# Keep the report inside the first-listen ZIP identical to its standalone copy.
archive = root / "light-study.zip"
temporary = archive.with_suffix(".zip.tmp")
with zipfile.ZipFile(archive) as source, zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for item in source.infolist():
        if item.filename == "light-study/report.json":
            data = report_path.read_bytes()
        elif item.filename == "light-study/README.txt":
            data = readme_path.read_bytes()
        else:
            data = source.read(item)
        item.compress_type = zipfile.ZIP_DEFLATED
        output.writestr(item, data)
temporary.replace(archive)
expanded = sum(path.stat().st_size for path in root.rglob("*") if path.is_file())
assert expanded < 250 * 1024 * 1024, f"Bundle is still too large: {expanded} bytes"
print(json.dumps({"expandedBytes": expanded, "removedDuplicatePackages": len(removed), "previousClipAudioPreserved": 171, "newClipAudioPreserved": 36}))
