"""FACSIMILE round 0 audition: renders every pad and phrase, plus the reference beside it.

Writes MP3s and data.json into OUT_DIR for the listening page. The replica audio comes from
models/*.json only. The reference clips come from the work folder's separated components and
the reference loops, so this script (like fit.py and measure.py) reads licensed PCM and its
output stays out of the repository.

Per pad:
  ref       the separated reference audio of the pad's layers, cut at a moment where they all
            fire, stopped before any of them fires again (so it carries some bleed)
  rep       the replica at full strike, take 1, at the same gain as ref: a level difference
            between them is real and is left audible
  vel       soft (.3), medium (.6) and hard (1) strikes, each matched in loudness so the
            ear judges the change in character, not volume
  rr        four takes at full strike, back to back, at their own level
Per phrase: the reference loop and the replica groove, matched in loudness.

    python3 -I build_audition.py REF_DIR WORK_DIR MODELS_DIR OUT_DIR
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # run with -I: import our own modules
import groove  # noqa: E402
import synth  # noqa: E402

RATE = synth.RATE
GAP = 0.25


def mp3(path: Path, y: np.ndarray) -> str:
    wav = path.with_suffix(".wav")
    sf.write(wav, np.clip(y, -1, 1).astype(np.float32), RATE, subtype="FLOAT")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav),
                    "-codec:a", "libmp3lame", "-b:a", "192k", str(path)], check=True)
    wav.unlink()
    return path.name


def rms(y):
    return float(np.sqrt(np.mean(y ** 2))) + 1e-12


def trim(y, floor_db=-70):
    """Cut a render's silent tail (keeps 30 ms past the last sample above the floor)."""
    a = np.abs(y)
    live = np.nonzero(a > a.max() * 10 ** (floor_db / 20))[0]
    end = min(len(y), (live[-1] if len(live) else len(y)) + int(0.03 * RATE))
    return y[:end]


def render_pad(models: dict, layers: list[str], v: float, take: int) -> np.ndarray:
    parts = [trim(synth.render(models[d], v, take)) for d in layers]
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def reference_strike(work: Path, inv: dict, layers: list[str], max_s: float = 1.2):
    """The separated reference audio of all layers at one moment they all fire. Layers from
    different loops never fired together: each loop's layers are cut on their own and the cuts
    are summed at the onset, which is what the pad itself does."""
    loops = sorted({d.split("-")[0] for d in layers})
    if len(loops) > 1:
        cuts = [reference_strike(work, inv, [d for d in layers if d.split("-")[0] == k], max_s)
                for k in loops]
        out = np.zeros(max(len(c) for c in cuts))
        for c in cuts:
            out[:len(c)] += c
        return out
    key = layers[0].split("-")[0]
    drums = {d["id"]: d for d in inv[key]["drums"]}
    ev = {d: drums[d]["events"] for d in layers}
    n = int(round(inv[key]["seconds"] * RATE))
    # Only real hits end a cut: detections under a quarter of the loudest are mostly bleed,
    # and stopping at them left some references a tenth of a second long.
    stops = sorted(int(x["t"] * RATE) for d in layers for x in ev[d] if x["gain"] > 0.25)

    def gap_after(t0):
        g = [(t - t0) % n for t in stops]
        # a component re-firing within 120 ms is the same strike detected twice (a snare's
        # tail part fires again 78 ms in), so it does not end the cut
        g = [x for x in g if x > int(0.12 * RATE)]
        return min(g) if g else n
    # Among strong hits where every layer fires, take the one with the longest clean gap after
    # it (enough to hear the tail), then the loudest.
    cands = [e for e in ev[layers[0]] if e["gain"] > 0.5 and
             all(any(abs(x["t"] - e["t"]) < 0.012 for x in ev[d]) for d in layers[1:])]
    cands = cands or sorted(ev[layers[0]], key=lambda e: -e["gain"])[:3]
    best = max(cands, key=lambda e: (min(gap_after(int(e["t"] * RATE)), int(0.5 * RATE)),
                                     e["gain"]))
    t0 = int(best["t"] * RATE)
    y = np.zeros(n)
    for d in layers:
        for c in drums[d]["components"]:
            y += sf.read(work / f"{key}_c{c}.wav")[0]
    stop = min(gap_after(t0), int(max_s * RATE))
    pre = int(0.002 * RATE)
    seg = y[(np.arange(-pre, stop) + t0) % n].copy()
    fade = int(0.006 * RATE)
    seg[-fade:] *= np.linspace(1, 0, fade)
    return seg


def strip(clips: list[np.ndarray], match: bool) -> np.ndarray:
    ref = max(rms(c[:int(0.2 * RATE)]) for c in clips)
    gap = np.zeros(int(GAP * RATE))
    out = []
    for c in clips:
        out += [c * (ref / rms(c[:int(0.2 * RATE)]) if match else c), gap]
    return np.concatenate(out)


def main(ref_dir: Path, work: Path, models_dir: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    kits = json.loads((HERE / "kits.json").read_text())
    inv = json.loads((work / "inventory.json").read_text())
    loops = {f.stem: json.loads(f.read_text()) for f in models_dir.glob("[0-9]*.json")}
    models = {d: m for lp in loops.values() for d, m in lp["drums"].items()}
    measure = json.loads((models_dir / "measure-report.json").read_text())
    fitrep = json.loads((models_dir / "fit-report.json").read_text())
    refs = {str(int(re.search(r"TRNDY_(\d+)_", p.stem).group(1))): p
            for p in ref_dir.glob("*.wav")}
    data = {"kits": []}
    peak_target = 10 ** (-1 / 20)
    for kit in kits["kits"]:
        kd = dict(name=kit["name"], tempo=kit["tempo"], character=kit["character"],
                  references=kit["references"], pads=[])
        for pad in kit["pads"]:
            slug = f"{kit['name'].split()[-1].lower()}_{pad['pad'].lower()}"
            pd = dict(pad=pad["pad"], name=pad["name"], cls=pad["class"])
            if "phrase" in pad:
                key = pad["phrase"]
                ref = sf.read(refs[key], always_2d=True)[0].mean(1)
                raw = groove.render_loop(loops[key], kits["choke"].get(key, []))
                gs = kits.get("glue", {}).get(key)
                rep = groove.glue(raw, **gs) if gs else raw
                # one gain for all three so nothing clips; replica and raw matched in loudness
                # to the reference, so the ear judges the glue, not the volume
                g = peak_target / max(np.abs(ref).max(),
                                      np.abs(rep).max() * rms(ref) / rms(rep),
                                      np.abs(raw).max() * rms(ref) / rms(raw))
                pd.update(phrase=key, bpm=loops[key]["name_bpm"], glue=gs,
                          ref=mp3(out / f"{slug}_ref.mp3", ref * g),
                          rep=mp3(out / f"{slug}_rep.mp3", rep * g * rms(ref) / rms(rep)),
                          raw=mp3(out / f"{slug}_raw.mp3", raw * g * rms(ref) / rms(raw)),
                          metrics=measure["loops"][key])
            else:
                v = pad.get("velocity", 1.0)
                ref = reference_strike(work, inv, pad["layers"])
                rep = render_pad(models, pad["layers"], v, 0)
                g = peak_target / max(np.abs(ref).max(), np.abs(rep).max())
                vel = [render_pad(models, pad["layers"], x, 0) for x in (0.3, 0.6, 1.0)]
                rr = [render_pad(models, pad["layers"], v, t) for t in range(4)]
                vs = strip(vel, match=True)
                rs = strip(rr, match=False)
                pd.update(layers=pad["layers"], velocity=v,
                          ref=mp3(out / f"{slug}_ref.mp3", ref * g),
                          rep=mp3(out / f"{slug}_rep.mp3", rep * g),
                          vel=mp3(out / f"{slug}_vel.mp3", vs * peak_target / np.abs(vs).max()),
                          rr=mp3(out / f"{slug}_rr.mp3", rs * peak_target / np.abs(rs).max()),
                          level_db=round(20 * np.log10(rms(rep[:int(.2 * RATE)]) /
                                                       rms(ref[:int(.2 * RATE)])), 1),
                          metrics=[dict(id=d, **measure["drums"][d],
                                        glide=fitrep[d].get("glide"))
                                   for d in pad["layers"]])
            kd["pads"].append(pd)
            print(kit["name"], pad["pad"], pad["name"])
        data["kits"].append(kd)
    (out / "data.json").write_text(json.dumps(data, indent=1))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
