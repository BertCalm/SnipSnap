#!/usr/bin/env python3
"""Append the checked register comparison while retaining every earlier recording."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from chamber_flac_seektable import index_flac
from package_chamber_extremes_site import contained, write_json
from package_chamber_site import directory_bytes, encode_verified, pcm16, sha256


def decoded_pcm(ffmpeg: str, path: Path) -> bytes:
    if path.suffix.lower() == ".wav":
        return pcm16(path)[0]
    if path.suffix.lower() != ".flac":
        raise ValueError(f"Expected a WAV or FLAC recording: {path}")
    return subprocess.run(
        [ffmpeg, "-nostdin", "-v", "error", "-i", str(path), "-f", "s16le",
         "-c:a", "pcm_s16le", "-"], check=True, capture_output=True,
    ).stdout


def checked_manifest(manifest: dict, checks: dict) -> None:
    if manifest.get("models") != {"before": 4, "after": 5}:
        raise ValueError("Expected the previous wider and register-candidate models")
    clips, cards = manifest["clips"], manifest["cards"]
    by_id = {clip["id"]: clip for clip in clips}
    if len(clips) != 10 or len(by_id) != 10 or len(cards) != 12:
        raise ValueError("Expected ten unique recordings and twelve comparison cards")
    wanted = {(voice, midi, variant) for voice in ("HYBRID", "AQUARIUM")
              for midi in (60, 72, 84) for variant in ("before", "after")}
    actual = {(c["voice"], c["midi"], c["variant"]) for c in cards}
    if actual != wanted:
        raise ValueError("The complete two-room, three-note comparison is required")
    for card in cards:
        clip = by_id[card["clipId"]]
        if (clip["voice"], clip["midi"]) != (card["voice"], card["midi"]):
            raise ValueError("A comparison card points to a different room or note")
        expected_variant = "anchor" if card["midi"] == 60 else card["variant"]
        expected_model = 5 if expected_variant == "after" else 4
        if clip["variant"] != expected_variant or clip["model"] != expected_model:
            raise ValueError("A comparison card points to a different sound revision")
    if not checks.get("passed") or checks.get("dirtyExperiment") is not False:
        raise ValueError("Package a complete final render, not a dirty experiment")
    if checks.get("sourceRevision") != manifest["sourceRevision"]:
        raise ValueError("Manifest and checks identify different rendering sources")
    identities = checks["acceptedLowerAndCenterIdentities"]
    if len(identities) != 4 or {(c["voice"], c["midi"]) for c in identities} != {
            (voice, midi) for voice in ("HYBRID", "AQUARIUM") for midi in (48, 60)}:
        raise ValueError("Both lower and center identities must be checked in both rooms")
    for entry in identities + checks["baselineReplays"]:
        if not all(entry.get(key) is True for key in ("rawFloat32Exact", "rawWavExact", "matchedWavExact")):
            raise ValueError("A preserved recording failed exact replay")
    if len(checks["baselineReplays"]) != 4 or len(checks["portableReplays"]) != 4:
        raise ValueError("The four old recordings and four C6 portable replays are required")
    if any(entry.get("wavExact") is not True for entry in checks["portableReplays"]):
        raise ValueError("A portable C6 recipe changed its recording")
    if len(checks["allCandidateNumericalChecks"]) != 8 or any(
            not entry["checks"] or not all(value is True for value in entry["checks"].values())
            for entry in checks["allCandidateNumericalChecks"]):
        raise ValueError("Every candidate recording must pass the complete numerical guards")


def deduplicate_sources(current: Path, study: Path, dry_run: bool = False) -> dict:
    """Reuse only byte-identical source snapshots, retaining their logical identity.

    Earlier site files remain immutable. Each provenance entry retains its
    original repository path, revision and hash, and gains the actual hosted
    path. The original provenance document remains available verbatim as well.
    """
    original = contained(study, "source-provenance.json").read_bytes()
    provenance = json.loads(original)
    prior_by_sha = {}
    for path in sorted(current.rglob("*"), key=lambda p: p.relative_to(current).as_posix()):
        relative = path.relative_to(current)
        if path.is_file() and any(part in ("source", "source-baseline") for part in relative.parts[:-1]):
            prior_by_sha.setdefault(sha256(path), relative.as_posix())
    groups = (("source", provenance["files"]), ("source-baseline", provenance["baseline"]["files"]))
    expected = set()
    for prefix, entries in groups:
        for entry in entries:
            relative = f"{prefix}/{entry['path']}"
            if relative in expected:
                raise ValueError("A source provenance entry occurs more than once")
            expected.add(relative)
    actual = {path.relative_to(study).as_posix() for prefix, _ in groups
              for path in (study / prefix).rglob("*") if path.is_file()}
    if actual != expected:
        raise ValueError("The source snapshot inventory differs from its provenance document")
    retained_by_sha = {}
    mappings = []
    removed_bytes = 0
    for prefix, entries in groups:
        for entry in entries:
            relative = f"{prefix}/{entry['path']}"
            source = contained(study, relative)
            digest = sha256(source)
            if digest != entry["sha256"]:
                raise ValueError(f"A source snapshot's recorded hash changed: {relative}")
            size = source.stat().st_size
            if digest in prior_by_sha:
                prior_relative = prior_by_sha[digest]
                hosted = "../" + prior_relative
                target = contained(current, prior_relative) if dry_run else contained(study.parent, study.name + "/" + hosted)
                reuse = "prior-source"
            elif digest in retained_by_sha:
                hosted = retained_by_sha[digest]
                target = contained(study, hosted)
                reuse = "study-source"
            else:
                hosted = relative
                target = source
                retained_by_sha[digest] = hosted
                reuse = "retained"
            if sha256(target) != digest:
                raise ValueError(f"A hosted source mapping does not preserve its exact bytes: {relative}")
            duplicate = hosted != relative
            if duplicate:
                removed_bytes += size
                if not dry_run:
                    source.unlink()
            entry["hostedPath"] = hosted
            mappings.append({"originalFile": relative, "sha256": digest, "originalBytes": size,
                             "hostedPath": hosted, "reuse": reuse, "removedDuplicate": duplicate})
    policy = "Source snapshots share only files with identical SHA-256; logical paths, revisions and hashes are retained"
    original_hash = hashlib.sha256(original).hexdigest()
    provenance["hosting"] = {"policy": policy, "renderedProvenance": "source-provenance-rendered.json",
                             "renderedProvenanceSha256": original_hash, "report": "hosting-audio.json"}
    hosted_provenance_bytes = (json.dumps(provenance, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
    if not dry_run:
        (study / "source-provenance-rendered.json").write_bytes(original)
        (study / "source-provenance.json").write_bytes(hosted_provenance_bytes)
        # Verify the complete mapping after every duplicate has been removed.
        for mapping in mappings:
            target = contained(study.parent, study.name + "/" + mapping["hostedPath"])
            if sha256(target) != mapping["sha256"]:
                raise ValueError("A final source mapping is missing or changed")
    return {"policy": policy, "renderedProvenance": "source-provenance-rendered.json",
            "renderedProvenanceSha256": original_hash,
            "hostedProvenanceSha256": hashlib.sha256(hosted_provenance_bytes).hexdigest(),
            "sourceFiles": len(mappings), "priorSourceReferences": sum(m["reuse"] == "prior-source" for m in mappings),
            "studySourceReferences": sum(m["reuse"] == "study-source" for m in mappings),
            "retainedSourceFiles": sum(m["reuse"] == "retained" for m in mappings),
            "removedDuplicateBytes": removed_bytes, "hostedProvenanceBytes": len(hosted_provenance_bytes),
            "netBytesSavedBeforeHostingReport": removed_bytes - len(hosted_provenance_bytes), "mappings": mappings}


def navigation(destination: Path, before: dict[str, str]) -> list[dict]:
    changes = []
    specifications = (
        ("index.html", r'(<a\b[^>]*\bhref="extremes/index\.html"[^>]*>.*?</a>)',
         '\n        <a class="text-link hero-link" style="display:block;margin-top:14px" href="register/index.html">Higher-note resonance comparison <span aria-hidden="true">↗</span></a>'),
        ("extremes/index.html", r'(<div class="facts">.*?</div>)',
         '\n      <p class="small"><a href="../register/index.html">Higher-note resonance comparison <span aria-hidden="true">↗</span></a></p>'),
    )
    for relative, pattern, link in specifications:
        path = contained(destination, relative)
        original = path.read_text()
        updated, count = re.subn(pattern, lambda match: match[0] + link, original, flags=re.DOTALL)
        if count != 1:
            raise ValueError(f"Expected one existing navigation anchor in {relative}, found {count}")
        path.write_text(updated)
        changes.append({"path": relative, "beforeSha256": before[relative], "afterSha256": sha256(path)})
    return changes


def package(current: Path, rendered: Path, destination: Path, ffmpeg: str, ffprobe: str, limit: int) -> dict:
    current, rendered, destination = (path.resolve() for path in (current, rendered, destination))
    for a, b in ((current, rendered), (current, destination), (rendered, destination)):
        if a == b or a.is_relative_to(b) or b.is_relative_to(a):
            raise ValueError("Use three separate, non-nested site, render and output directories")
    if not current.is_dir() or not rendered.is_dir() or limit <= 0:
        raise ValueError("Existing site and final render directories and a positive budget are required")
    if destination.exists() and (not destination.is_dir() or any(destination.iterdir())):
        raise ValueError("Use a new or empty output directory")
    if (current / "register").exists():
        raise ValueError("This command cannot replace an already published register study")
    for root in (current, rendered):
        if any(path.is_symlink() for path in root.rglob("*")):
            raise ValueError("Site and render artifacts must contain regular files, not symbolic links")
    before = {path.relative_to(current).as_posix(): sha256(path)
              for path in current.rglob("*") if path.is_file()}
    original_manifest = contained(rendered, "manifest.json").read_bytes()
    manifest = json.loads(original_manifest)
    checks = json.loads(contained(rendered, "checks.json").read_text())
    checked_manifest(manifest, checks)
    prior_manifest_path = contained(current, "extremes/manifest.json")
    prior_manifest = json.loads(prior_manifest_path.read_text())
    prior_clips = {clip["id"]: clip for clip in prior_manifest["clips"]}
    if destination.exists():
        destination.rmdir()
    shutil.copytree(current, destination)
    study = destination / "register"
    shutil.copytree(rendered, study)
    (study / "manifest-rendered.json").write_bytes(original_manifest)
    proof = {
        "schemaVersion": 1,
        "policy": "Verified lossless FLAC with frame seek tables; C4 anchors reference the unchanged previous study",
        "sourceRevision": manifest["sourceRevision"], "renderedManifestSha256": hashlib.sha256(original_manifest).hexdigest(),
        "previousHostedManifestSha256": sha256(prior_manifest_path),
        "priorFiles": len(before), "priorBytes": directory_bytes(current), "limitBytes": limit,
        "compressed": [], "reused": [],
    }
    new_ids = []
    for clip in manifest["clips"]:
        metrics = clip["metrics"]
        if clip["variant"] == "anchor":
            old = prior_clips[clip["baselineClipId"]]
            if (old["voice"], old["midi"], old["model"]) != (clip["voice"], 60, 4):
                raise ValueError("A C4 anchor identifies a different prior recording")
            hosted = {}
            for field in ("raw", "matched", "recipe"):
                copied = contained(study, clip[field])
                original = contained(rendered, clip[field])
                source = contained(current, "extremes/" + old[field])
                if field == "recipe":
                    if sha256(original) != metrics["recipeSha256"] or copied.read_bytes() != source.read_bytes():
                        raise ValueError("An anchor's recipe differs from its original published recipe")
                    extra = {}
                else:
                    if sha256(original) != metrics["audioSha256"][field]:
                        raise ValueError("An anchor's original WAV hash changed")
                    original_pcm = decoded_pcm(ffmpeg, original)
                    hosted_pcm = decoded_pcm(ffmpeg, source)
                    if original_pcm != hosted_pcm:
                        raise ValueError("An anchor's hosted recording changes its original PCM16 samples")
                    extra = {"pcmSha256": hashlib.sha256(original_pcm).hexdigest(), "samplesIdentical": True}
                relative = "../extremes/" + old[field]
                entry = {"clipId": clip["id"], "baselineClipId": clip["baselineClipId"], "field": field,
                         "originalRenderedPath": clip[field], "originalRenderedSha256": sha256(original),
                         "path": relative, "sha256": sha256(source)} | extra
                proof["reused"].append(entry)
                clip[field] = relative
                if field != "recipe":
                    metrics["audioSha256"][field] = entry["sha256"]
                hosted[field] = entry
                copied.unlink()
            clip["hostingAudio"] = hosted
            continue
        new_ids.append(clip["id"])
        hosted = {}
        recipe = contained(rendered, clip["recipe"])
        if sha256(recipe) != metrics["recipeSha256"]:
            raise ValueError("A register recipe's hash changed")
        for field in ("raw", "matched"):
            relative = clip[field]
            original = contained(rendered, relative)
            wav = contained(study, relative)
            if sha256(original) != metrics["audioSha256"][field] or sha256(wav) != sha256(original):
                raise ValueError("A rendered register WAV's hash changed")
            flac = wav.with_suffix(".flac")
            if wav.suffix.lower() != ".wav" or flac.exists():
                raise ValueError("Expected a distinct newly rendered WAV container")
            verified = encode_verified(ffmpeg, wav, flac)
            indexed = index_flac(ffmpeg, ffprobe, flac)
            if indexed["pcmSha256"] != verified["pcmSha256"]:
                raise ValueError("A FLAC seek table changed the verified PCM16 recording")
            entry = {"clipId": clip["id"], "mode": field, "originalWav": relative,
                     "originalWavSha256": sha256(original), "originalBytes": wav.stat().st_size,
                     "flac": flac.relative_to(study).as_posix(), "flacSha256": sha256(flac),
                     "flacBytes": flac.stat().st_size, "seekTable": indexed} | verified
            proof["compressed"].append(entry)
            hosted[field] = entry
            clip[field] = entry["flac"]
            metrics["audioSha256"][field] = entry["flacSha256"]
            wav.unlink()
        clip["hostingAudio"] = hosted
    if len(new_ids) != 8 or len(proof["compressed"]) != 16 or len(proof["reused"]) != 6:
        raise ValueError("Expected eight new raw/matched pairs and two complete reused C4 anchors")
    manifest["hostingAudio"] = {"report": "hosting-audio.json", "renderedManifest": "manifest-rendered.json",
                                "samplesIdentical": True, "policy": proof["policy"]}
    proof["sourceSnapshots"] = deduplicate_sources(current, study)
    manifest["hostingSourceSnapshots"] = {"report": "hosting-audio.json", "provenance": "source-provenance.json",
                                           "renderedProvenance": "source-provenance-rendered.json",
                                           "policy": proof["sourceSnapshots"]["policy"]}
    manifest["baseline"]["previousHostedManifestSha256"] = proof["previousHostedManifestSha256"]
    write_json(study / "manifest.json", manifest)
    proof["navigation"] = navigation(destination, before)
    allowed_changes = {entry["path"] for entry in proof["navigation"]}
    proof["preservedPriorFiles"] = sum(sha256(contained(destination, path)) == digest
                                       for path, digest in before.items() if path not in allowed_changes)
    if proof["preservedPriorFiles"] != len(before) - len(allowed_changes):
        raise ValueError("An earlier recording, recipe, archive or source asset changed")
    scripts = study / "hosting-source"
    scripts.mkdir(exist_ok=True)
    proof["hostingSourceFiles"] = []
    for name in ("package_chamber_register_site.py", "package_chamber_extremes_site.py",
                 "package_chamber_site.py", "chamber_flac_seektable.py"):
        source = Path(__file__).resolve().with_name(name)
        shutil.copyfile(source, scripts / name)
        proof["hostingSourceFiles"].append({"path": "hosting-source/" + name, "sha256": sha256(scripts / name)})
    proof["expandedBytes"] = 0
    for _ in range(10):
        write_json(study / "hosting-audio.json", proof)
        measured = directory_bytes(destination)
        if measured == proof["expandedBytes"]:
            break
        proof["expandedBytes"] = measured
    else:
        raise ValueError("Expanded-byte report did not reach a fixed point")
    expanded = directory_bytes(destination)
    if expanded >= limit:
        raise ValueError(f"Expanded site exceeds its hosting budget: {expanded} >= {limit}")
    print(json.dumps({"newRecordings": 8, "losslessFiles": 16, "reusedAnchors": 2,
                      "retainedPriorFiles": proof["preservedPriorFiles"], "expandedBytes": expanded,
                      "headroomBytes": limit - expanded, "allConvertedPcmSamplesIdentical": True}))
    return proof


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("current", type=Path)
    parser.add_argument("rendered", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--limit-bytes", type=int, default=256 * 1024 * 1024)
    args = parser.parse_args()
    package(args.current, args.rendered, args.destination, args.ffmpeg, args.ffprobe, args.limit_bytes)


if __name__ == "__main__":
    main()
