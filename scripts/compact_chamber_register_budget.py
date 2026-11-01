#!/usr/bin/env python3
"""Recover deployment TAR headroom by losslessly compacting four unaliased raw diagnostics."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import wave

from chamber_flac_seektable import index_flac
from package_chamber_extremes_site import contained, write_json
from package_chamber_site import directory_bytes, encode_verified, sha256

IDS = tuple(f"hybrid-solo-{i}" for i in range(4))
LIMIT = 256 * 1024 * 1024
MARGIN = 20 * 1024


def tar_accounting(root: Path, hosting_config: Path) -> dict:
    """Count canonical Git USTAR records, including nonempty directories and framing."""
    files = {"dist/" + p.relative_to(root).as_posix(): p.stat().st_size
             for p in root.rglob("*") if p.is_file()}
    files[".openai/hosting.json"] = hosting_config.stat().st_size
    directories = {parent.as_posix() for name in files for parent in Path(name).parents if str(parent) != "."}
    # The retained tree is representable without per-file extended headers.
    # Fail rather than silently undercount if a future filename requires them.
    for name in files.keys() | directories:
        tarfile.TarInfo(name).tobuf(format=tarfile.USTAR_FORMAT, encoding="utf-8", errors="strict")
    data = sum(files.values())
    file_padding = sum((-size) % 512 for size in files.values())
    headers = (len(files) + len(directories)) * 512
    raw = data + file_padding + headers + 1024 + 1024
    total = ((raw + 10239) // 10240) * 10240
    return {"method": "Canonical Git USTAR: file and nonempty-directory headers, 512-byte file padding, 1024-byte commit global PAX, 1024-byte end markers, 10240-byte record rounding",
            "fileBytes": data, "files": len(files), "nonemptyDirectories": len(directories),
            "headersBytes": headers, "filePaddingBytes": file_padding,
            "globalPaxBytes": 1024, "endMarkersBytes": 1024, "recordPaddingBytes": total - raw,
            "canonicalGitTarBytes": total, "safetyMarginBytes": MARGIN,
            "guardedTarBytes": total + MARGIN, "limitBytes": LIMIT}


def compact(baseline: Path, stage: Path, destination: Path, hosting_config: Path,
            ffmpeg: str, ffprobe: str) -> dict:
    baseline, stage, destination, hosting_config = (p.resolve() for p in (baseline, stage, destination, hosting_config))
    for a, b in ((baseline, stage), (baseline, destination), (stage, destination)):
        if a == b or a.is_relative_to(b) or b.is_relative_to(a):
            raise ValueError("Use separate, non-nested baseline, register stage and new output directories")
    if destination.exists() and (not destination.is_dir() or any(destination.iterdir())):
        raise ValueError("Use a new or empty destination")
    if not baseline.is_dir() or not stage.is_dir() or not hosting_config.is_file():
        raise ValueError("Baseline, complete register stage and exact hosting configuration are required")
    if any(p.is_symlink() for root in (baseline, stage) for p in root.rglob("*")):
        raise ValueError("Expected regular site artifacts, not symbolic links")
    prior = {p.relative_to(baseline).as_posix(): sha256(p) for p in baseline.rglob("*") if p.is_file()}
    manifest = json.loads(contained(stage, "manifest.json").read_text())
    root_report = json.loads(contained(stage, "hosting-audio.json").read_text())
    proof = json.loads(contained(stage, "register/hosting-audio.json").read_text())
    if proof["priorFiles"] != len(prior) or "budgetCompaction" in proof:
        raise ValueError("Use the original published baseline and an uncompacted checked register stage")
    protected = set()
    for folder in ("extremes", "register"):
        for clip in json.loads(contained(stage, folder + "/manifest.json").read_text())["clips"]:
            for field in ("raw", "matched"):
                protected.add(contained(stage, folder + "/" + clip[field]))
    selected = []
    for clip_id in IDS:
        clip = next(c for c in manifest["clips"] if c["id"] == clip_id)
        relative = f"raw/{clip_id}.wav"
        path = contained(stage, relative)
        aliases = sum(c.get(field) == relative for c in manifest["clips"] for field in ("raw", "matched"))
        if (clip["section"] != "stems" or clip["raw"] != relative or aliases != 1 or path in protected
                or sha256(path) != clip["metrics"]["audioSha256"]["raw"] or sha256(path) != prior[relative]):
            raise ValueError("A selected diagnostic is aliased, changed, or outside the intended raw-only scope")
        selected.append(clip)
    if destination.exists():
        destination.rmdir()
    shutil.copytree(stage, destination)
    ledger = {"policy": "Only four unaliased root raw WAV containers become indexed lossless FLAC; original WAV bytes are exactly reconstructable. Every matched recording, archive, source and register/extremes recording is unchanged.",
              "reason": "Deployment limit includes TAR headers and block padding, not only expanded file bytes",
              "conversions": [], "historicalMetadata": [], "changedMetadata": [], "deletedContainers": [],
              "priorPreservedFileCountBeforeCompaction": proof["preservedPriorFiles"]}
    for relative in ("manifest.json", "hosting-audio.json"):
        original = contained(stage, relative).read_bytes()
        name = "root-manifest.json.gz" if relative == "manifest.json" else "root-hosting-audio.json.gz"
        snapshot = destination / "register/metadata-before-budget" / name
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        snapshot.write_bytes(gzip.compress(original, compresslevel=9, mtime=0))
        if gzip.decompress(snapshot.read_bytes()) != original:
            raise ValueError("Historical metadata snapshot failed exact recovery")
        ledger["historicalMetadata"].append({"originalPath": relative, "originalSha256": hashlib.sha256(original).hexdigest(),
                                             "originalBytes": len(original), "gzipPath": snapshot.relative_to(destination).as_posix(),
                                             "gzipSha256": sha256(snapshot), "exactRecovery": True})
    for clip in selected:
        relative = clip["raw"]
        wav = contained(destination, relative)
        flac = wav.with_suffix(".flac")
        if flac.exists():
            raise ValueError("Refusing to overwrite a pre-existing FLAC")
        original = wav.read_bytes()
        verified = encode_verified(ffmpeg, wav, flac)
        indexed = index_flac(ffmpeg, ffprobe, flac)
        pcm = subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-i", str(flac), "-f", "s16le", "-c:a", "pcm_s16le", "-"], check=True, capture_output=True).stdout
        recovered = io.BytesIO()
        with wave.open(recovered, "wb") as writer:
            writer.setparams((1, 2, 44100, 0, "NONE", "not compressed"))
            writer.writeframes(pcm)
        if recovered.getvalue() != original or indexed["pcmSha256"] != verified["pcmSha256"]:
            raise ValueError("A diagnostic's original WAV cannot be recovered byte for byte")
        entry = {"clipId": clip["id"], "originalWav": relative, "originalWavSha256": hashlib.sha256(original).hexdigest(),
                 "originalWavBytes": len(original), "flac": flac.relative_to(destination).as_posix(), "flacSha256": sha256(flac),
                 "flacBytes": flac.stat().st_size, "seekTable": indexed,
                 "canonicalWavRecovery": {"exact": True, "sha256": hashlib.sha256(recovered.getvalue()).hexdigest(),
                                          "method": "Decode PCM16 little-endian, then write a mono 44100Hz PCM WAV with the canonical 44-byte RIFF header"}} | verified
        ledger["conversions"].append(entry)
        ledger["deletedContainers"].append({"path": relative, "sha256": entry["originalWavSha256"], "recoverFrom": entry["flac"], "wavRecoveryExact": True})
        root_report["compressed"].append(entry)
        clip["raw"] = entry["flac"]
        clip["metrics"]["audioSha256"]["raw"] = entry["flacSha256"]
        wav.unlink()
    manifest["hostingAudio"]["compressedRawFiles"] = len(root_report["compressed"])
    manifest["hostingAudio"]["budgetCompactionReport"] = "register/hosting-audio.json"
    manifest["hostingAudio"]["previousHostedMetadata"] = ledger["historicalMetadata"]
    root_report["policy"] = "Lossless FLAC for selected diagnostic raw PCM16; four additional unaliased root stems are indexed for TAR headroom"
    root_report["compressedRawFiles"] = len(root_report["compressed"])
    root_report["targetBytes"] = LIMIT
    root_report["budgetCompaction"] = {"report": "register/hosting-audio.json", "historicalMetadata": ledger["historicalMetadata"], "additionalRawFiles": 4}
    root_report["verification"]["retainedAssetHashesMatched"] -= 4
    root_report["verification"]["additionalCanonicalWavRecoveriesExact"] = 4
    write_json(destination / "manifest.json", manifest)
    helper = destination / "register/hosting-source/compact_chamber_register_budget.py"
    shutil.copyfile(Path(__file__).resolve(), helper)
    proof["hostingSourceFiles"].append({"path": "hosting-source/" + helper.name, "sha256": sha256(helper)})
    allowed = {"index.html", "extremes/index.html", "manifest.json", "hosting-audio.json"} | {e["path"] for e in ledger["deletedContainers"]}
    for relative, digest in prior.items():
        if relative not in allowed and sha256(contained(destination, relative)) != digest:
            raise ValueError(f"An undeclared earlier asset changed: {relative}")
    for relative in ("manifest.json", "hosting-audio.json"):
        ledger["changedMetadata"].append({"path": relative, "beforeSha256": prior[relative], "afterSha256": None,
                                           "recoverFrom": next(e["gzipPath"] for e in ledger["historicalMetadata"] if e["originalPath"] == relative)})
    proof["preservedPriorFiles"] = len(prior) - len(allowed)
    ledger["preservedPriorFiles"] = proof["preservedPriorFiles"]
    ledger["allowedPriorChanges"] = sorted(allowed)
    ledger["allOtherPriorFilesExact"] = True
    proof["budgetCompaction"] = ledger
    for _ in range(12):
        write_json(destination / "hosting-audio.json", root_report)
        for entry in ledger["changedMetadata"]:
            entry["afterSha256"] = sha256(destination / entry["path"])
        write_json(destination / "register/hosting-audio.json", proof)
        expanded = directory_bytes(destination)
        accounting = tar_accounting(destination, hosting_config)
        if proof.get("expandedBytes") == expanded and root_report.get("hostedBytes") == expanded and ledger.get("tarAccounting") == accounting:
            break
        proof["expandedBytes"] = expanded
        root_report["hostedBytes"] = expanded
        ledger["tarAccounting"] = accounting
    else:
        raise ValueError("Size and TAR proof did not reach a fixed point")
    if accounting["guardedTarBytes"] >= LIMIT:
        raise ValueError(f"Canonical TAR plus safety margin exceeds limit: {accounting}")
    print(json.dumps({"newRawConversions": 4, "rawConversionsTotal": len(root_report["compressed"]),
                      "expandedBytes": expanded, "canonicalGitTarBytes": accounting["canonicalGitTarBytes"],
                      "tarHeadroomBytes": LIMIT - accounting["canonicalGitTarBytes"],
                      "allOriginalWavsRecoverExactly": True, "preservedPriorFiles": proof["preservedPriorFiles"]}))
    return proof


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("baseline", type=Path)
    p.add_argument("stage", type=Path)
    p.add_argument("destination", type=Path)
    p.add_argument("--hosting-config", type=Path, default=Path("/workspace/sites/chamber-snipsnap-audition/.openai/hosting.json"))
    p.add_argument("--ffmpeg", default="ffmpeg")
    p.add_argument("--ffprobe", default="ffprobe")
    a = p.parse_args()
    compact(a.baseline, a.stage, a.destination, a.hosting_config, a.ffmpeg, a.ffprobe)


if __name__ == "__main__":
    main()
