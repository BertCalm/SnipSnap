"""Build the CYPHER listening page from the generator's output: compressed copies of the clips plus index.html.

Usage: python3 scripts/cypher_page/build_page.py testkit/cypher-kits testkit/cypher-kits/site [wav|mp3]

The page is mono, as the kits are. MP3 at 160 kbit/s (the default) keeps the pack under the artifact host's
size limits and plays on every phone; it adds the encoder's few milliseconds of lead-in, so the onset timing on
the page is not the sample-accurate timing the kit has. 16-bit WAV is the fallback and what GATECRASH's page played.
"""
import json, pathlib, shutil, subprocess, sys

src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
site = pathlib.Path(sys.argv[2])
ext = sys.argv[3] if len(sys.argv) > 3 else "mp3"
shutil.rmtree(site, ignore_errors=True)
site.mkdir(parents=True)
manifest = json.loads((src / "manifest.json").read_text())
files = 0
for kit in manifest["kits"]:
    (site / kit["id"]).mkdir()
    for wav in sorted((src / kit["id"]).glob("*.wav")):
        out = site / kit["id"] / (wav.stem + "." + ext)
        if ext == "mp3":
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-c:a", "libmp3lame", "-b:a", "160k", str(out)], check=True)
        else:
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-sample_fmt", "s16", str(out)], check=True)
        files += 1
html = (here / "template.html").read_text()
html = html.replace("/*MANIFEST*/", json.dumps(manifest, separators=(",", ":"))).replace("/*EXT*/", ext)
(site / "index.html").write_text(html)
total = sum(f.stat().st_size for f in site.rglob("*") if f.is_file())
print(files, "audio files,", round(total / 1e6, 1), "MB, index", round((site / "index.html").stat().st_size / 1e3), "kB")
