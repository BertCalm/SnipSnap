"""Build the MIRRORBALL listening page from the generator's output: 16-bit WAV copies of the clips plus index.html."""
import json, pathlib, shutil, subprocess, sys

# Usage: python3 scripts/mirrorball_page/build_page.py testkit/mirrorball-kits testkit/mirrorball-kits/site
src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
site = pathlib.Path(sys.argv[2])
shutil.rmtree(site, ignore_errors=True)
site.mkdir()
manifest = json.loads((src / "manifest.json").read_text())
files = {}
for kit in manifest["kits"]:
    (site / kit["id"]).mkdir()
    for wav in sorted((src / kit["id"]).glob("*.wav")):
        out = site / kit["id"] / (wav.stem + ".wav")
        # 16-bit WAV: the exported samples at two thirds of the 24-bit size (the host serves .wav, not .flac).
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(wav), "-sample_fmt", "s16", str(out)], check=True)
        files[f"{kit['id']}/{out.name}"] = f"{kit['id']}/{out.name}"
html = (here / "template.html").read_text()
html = html.replace("/*MANIFEST*/", json.dumps(manifest, separators=(",", ":"))).replace("/*EXT*/", "wav")
# The gate table is written by hand from the evidence (gates.html), never generated from a hope.
html = html.replace("/*GATES*/", (here / "gates.html").read_text())
(site / "index.html").write_text(html)
total = sum(f.stat().st_size for f in site.rglob("*") if f.is_file())
print(len(files), "audio files,", round(total / 1e6, 1), "MB, index", round((site / "index.html").stat().st_size / 1e3), "kB")
