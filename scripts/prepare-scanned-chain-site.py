#!/usr/bin/env python3
"""Prepare the audition for hosting with lossless, verified 24-bit FLAC audio."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audition", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source = args.audition.resolve()
    output = args.output.resolve()
    repository = Path(__file__).resolve().parent.parent
    manifest = json.loads((source / "manifest.json").read_text())
    template = (repository / "scripts/scanned-chain-audition.html").read_text()
    provenance = manifest["provenance"]
    for key, hash_key in (("coreFile", "coreSha256"), ("generatorFile", "generatorSha256")):
        if hashlib.sha256((repository / provenance[key]).read_bytes()).hexdigest() != provenance[hash_key]:
            raise ValueError("implementation changed after rendering")
    sweep_provenance = manifest["sweeps"]["provenance"]
    for key, hash_key in (("coreFile", "coreSha256"), ("generatorFile", "generatorSha256")):
        if hashlib.sha256((repository / sweep_provenance[key]).read_bytes()).hexdigest() != sweep_provenance[hash_key]:
            raise ValueError("sweep implementation changed after rendering")

    # Presentation-only edits do not require rendering accepted audio again.
    provenance.setdefault("audioGenerationPageTemplateSha256", provenance["pageTemplateSha256"])
    provenance["pageTemplateSha256"] = hashlib.sha256(template.encode()).hexdigest()
    provenance["pageRefreshedWithoutAudioChanges"] = True

    names = set()
    def find_audio(value):
        if isinstance(value, dict):
            for item in value.values():
                find_audio(item)
        elif isinstance(value, list):
            for item in value:
                find_audio(item)
        elif isinstance(value, str) and value.endswith(".wav"):
            path = (source / value).resolve()
            if path.is_relative_to(source) and path.is_file():
                names.add(value)
    find_audio(manifest)
    if len(names) != 139:
        raise ValueError(f"expected 139 original and sweep audio assets, found {len(names)}")
    output.mkdir(parents=True, exist_ok=True)

    def pcm(path):
        return subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-threads", "1", "-i", str(path),
                               "-f", "s24le", "-"], check=True, capture_output=True).stdout

    def convert(name):
        original = source / name
        destination = output / Path(name).with_suffix(".flac")
        destination.parent.mkdir(parents=True, exist_ok=True)
        before = pcm(original)
        after = pcm(destination) if destination.is_file() else None
        if before != after:
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", str(original),
                            "-c:a", "flac", "-compression_level", "8", "-threads", "1", str(destination)], check=True)
            after = pcm(destination)
        if before != after:
            raise ValueError(f"decoded PCM changed in lossless encoding: {name}")
        return name, {
            "file": destination.relative_to(output).as_posix(),
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            "sourceWavSha256": hashlib.sha256(original.read_bytes()).hexdigest(),
            "pcm24Sha256": hashlib.sha256(after).hexdigest(),
            "bytes": destination.stat().st_size,
        }
    with ThreadPoolExecutor(max_workers=4) as workers:
        encoded = dict(workers.map(convert, sorted(names)))

    def rewrite(value):
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                if key in {"rawSha256", "matchedSha256"}:
                    mode = key.removesuffix("Sha256")
                    source_name = value.get(mode + "File")
                    if source_name in encoded:
                        result[mode + "SourceWavSha256"] = item
                        result[key] = encoded[source_name]["sha256"]
                        continue
                if key == "sha256":
                    source_name = value.get("matchedFile", value.get("file"))
                    if source_name in encoded:
                        result["sourceWavSha256"] = item
                        result[key] = encoded[source_name]["sha256"]
                        continue
                result[key] = rewrite(item)
            return result
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        if isinstance(value, str) and value in encoded:
            return encoded[value]["file"]
        return value
    manifest = rewrite(manifest)
    manifest["hostingAudio"] = {
        "codec": "FLAC", "lossless": True, "sampleRate": 44100, "bitDepth": 24,
        "verification": "Every FLAC decoded to the same signed little-endian 24-bit PCM frames as its rendered WAV.",
        "assets": encoded,
    }
    manifest.pop("packFile", None)
    def page(data):
        return template.replace("__SCANNED_CHAIN_MANIFEST__", json.dumps(data, separators=(",", ":")).replace("</", "<\\/"))

    # Offline pack uses the same lossless audio and hides its self-download link.
    for name in ["implementation", "implementation.patch", "README.txt", "pairs-manifest.json"]:
        origin = source / name
        destination = output / name
        if origin.is_dir():
            shutil.copytree(origin, destination, dirs_exist_ok=True)
        elif origin.is_file():
            shutil.copyfile(origin, destination)
    (output / "README.txt").write_text(
        "Scanned-chain parameter explorer\nOpen index.html with its audio folders beside it.\n"
        "All hosted audio is lossless 24-bit mono 44.1 kHz FLAC, verified against rendered WAV PCM.\n"
        "The implementation snapshots reproduce standard WAVs using SnipSnap's generator tasks.\n"
        "Five prerecorded settings per control; no live synthesis or sustained loops.\n")
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "index.html").write_text(page(manifest))
    archive = output / "audition.zip"
    temporary_archive = output / ".audition.zip.tmp"
    paths = sorted(path for path in output.rglob("*") if path.is_file() and path not in {archive, temporary_archive})
    with zipfile.ZipFile(temporary_archive, "w", zipfile.ZIP_DEFLATED) as packed:
        for path in paths:
            packed.write(path, path.relative_to(output))
    if temporary_archive.stat().st_size >= 25 * 1024 * 1024:
        raise ValueError("download archive exceeds the static asset size budget; split it before publication")
    temporary_archive.replace(archive)

    # Hosting needs only the page, downloadable pack, and audio; source is inside
    # the pack, avoiding duplicate static uploads of implementation snapshots.
    for name in ["implementation", "implementation.patch", "README.txt", "pairs-manifest.json", "manifest.json"]:
        path = output / name
        if path.is_dir():
            shutil.rmtree(path)
        elif path.is_file():
            path.unlink()
    manifest["packFile"] = "audition.zip"
    (output / "index.html").write_text(page(manifest))
    total = sum(path.stat().st_size for path in output.rglob("*") if path.is_file())
    print(json.dumps({"output": str(output), "assets": len(encoded), "downloadBytes": archive.stat().st_size,
                      "staticBytes": total, "pcmVerified": True, "pageTemplateSha256": provenance["pageTemplateSha256"]}))


if __name__ == "__main__":
    main()
