"""SIGIL round 0: build the listening page from gen.py's output. Python harness.

    python3 build_page.py OUT        # reads OUT/<kit>/..., OUT/report.json; writes OUT/site/

Each pad's twelve hits (three zones x four takes) go into one MP3, a hit per fixed-length slot starting 50 ms in,
so the page loads one file per pad and plays a slot with Web Audio. The references are never on the page.
"""
from __future__ import annotations

import html
import json
import os
import subprocess
import sys
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from kits import KITS, ZONES, TAKES  # noqa: E402

RATE = 44100
PRE = 0.05


def read(path):
    with wave.open(path, "rb") as w:
        n = w.getnframes()
        raw = w.readframes(n)
        sw = w.getsampwidth()
    if sw == 2:
        return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768
    if sw == 3:  # the Kotlin audition's 24-bit WAVs
        bb = np.frombuffer(raw, np.uint8).reshape(-1, 3)
        v = bb[:, 0].astype(np.int32) | (bb[:, 1].astype(np.int32) << 8) | (bb[:, 2].astype(np.int32) << 16)
        return (np.where(v >= 1 << 23, v - (1 << 24), v) / float(1 << 23)).astype(np.float32)
    if sw == 4:
        return np.frombuffer(raw, dtype="<f4").astype(np.float32)
    raise ValueError(sw)


def mp3(x, path):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "-",
                    "-codec:a", "libmp3lame", "-b:a", os.environ.get("SIGIL_KBPS", "160") + "k", path], input=pcm, check=True)


def sprite(folder, out_path):
    hits = [read(os.path.join(folder, f"z{z}_t{t}.wav")) for z in range(len(ZONES)) for t in range(TAKES)]
    slot = PRE + max(len(h) for h in hits) / RATE + 0.05
    n = int(slot * RATE)
    buf = np.zeros(n * len(hits) + RATE // 2, dtype=np.float32)
    for i, h in enumerate(hits):
        at = i * n + int(PRE * RATE)
        buf[at: at + len(h)] = h
    mp3(buf, out_path)
    return round(n / RATE, 5)


def fmt(v, unit="", nd=0):
    if v is None:
        return "–"
    return f"{v:,.{nd}f}{unit}"


KIDS = [k["id"] for k in KITS if not k.get("parent")]  # the measured table: the kits with drums

# What each voice is, in a line, shown when its pad is tapped (the owner asked what the rods were).
VOICE_NOTES = {
    "kick": "a sine whose pitch drops an octave, a beater click and a short knock",
    "snare": "two head tones, wires, a crack and a short tail",
    "clap": "three noise bursts and a fourth crushed to a lower sample rate",
    "hat": "six detuned square waves (drum-machine metal) mixed with noise",
    "log_drum": "struck wood plus a sine that bends down into the note",
    "glass_bell": "struck glass plus FM, the classic synthetic bell",
    "tine": "a thumb-piano tine with its buzz, and an 8-bit copy an octave up",
    "clock_tick": "a clock's tick-tock: two wooden clicks, the second one crushed",
    "shaker": "bead grains, some of them replaced by single digital pulses",
    "wood_chirp": "a wooden knock with a synthetic upward chirp riding it",
    "breath": "a breath or half-sung vowel through three formants, lightly ring-modulated (no words)",
    "water_drop": "a droplet: a sine that rises fast over a tiny splash",
    "rod": "a texture, not a hit: wind through a tuned metal pipe, swelling in, with a faint shimmer",
    "hand_drum": "a skin struck by hand, with a synthetic sub under the low ones",
    "tambourine": "ten jingles landing a few ms apart over the frame",
    "rim": "a stick on a wooden rim",
    "metal_tick": "a short FM ping, the most synthetic hit in the set",
    "zap": "a falling sine, driven",
    "snap": "a finger snap, part of it crushed to a lower sample rate",
    "scrape": "a scraper: a run of tiny wooden clicks slowing down, every third one crushed",
    "roll": "the kit's roll: fresh strikes speeding up from eighths to thirty-seconds",
}


def ref_rows(refs, report):
    """Reference ranges beside each kit's own numbers (Python harness, the same probes on both)."""
    heavy = [r for r in refs if r["pack"] == "NH_UAS"]
    slammed = [r for r in refs if r["name"].startswith("BBL_90_drum")]

    def rng(vals, unit="", nd=0):
        vals = [v for v in vals if v is not None]
        return f"{fmt(min(vals), '', nd)} to {fmt(max(vals), unit, nd)}" if vals else "–"

    k = [r["hits"].get("K", {}) for r in heavy + slammed]
    s = [r["hits"].get("S", {}) for r in heavy + slammed]
    rows = []
    kits = report["kits"]

    def kick(kid):
        p = kits[kid]["pads"][0]["stats"]
        return p

    def snare(kid):
        for p in kits[kid]["pads"]:
            if p["voice"] == "snare":
                return p["stats"]

    rows.append(("Kick pitch at the hit", rng([d.get("f0_attack_hz") for d in k], " Hz"),
                 *[fmt(kick(kid)["f0_attack_hz"], " Hz") for kid in KIDS]))
    rows.append(("Kick pitch after 80 ms", rng([d.get("f0_body_hz") for d in k if d.get("f0_body_hz", 0) < 100], " Hz"),
                 *[fmt(kick(kid)["f0_body_hz"], " Hz") for kid in KIDS]))
    rows.append(("Kick gone 20 dB after", rng([d.get("to_m20db_ms") for d in k], " ms"),
                 *[fmt(kick(kid)["to_m20db_ms"], " ms") for kid in KIDS]))
    rows.append(("Kick distortion under its note", rng([d.get("harmonics_over_fund_db") for d in k], " dB", 1),
                 *[fmt(kick(kid)["harmonics_over_fund_db"], " dB", 1) for kid in KIDS]))
    rows.append(("Snare tone centre, first 120 ms", rng([d.get("centroid_hz") for d in s], " Hz"),
                 *[fmt(snare(kid)["centroid_120ms_hz"], " Hz") for kid in KIDS]))
    rows.append(("Snare gone 20 dB after", rng([d.get("to_m20db_ms") for d in s], " ms"),
                 *[fmt(snare(kid)["to_m20db_ms"], " ms") for kid in KIDS]))

    def hat_under(kid):
        b = kits[kid]["groove_balance_db"]
        names = [n for n in b if "Hat" in n or "Shaker" in n or "Tambourine" in n]
        return max(b[n] for n in names)

    rows.append(("Loudest hat or shaker under the kick, in the groove", "10–20 dB (drum loops)",
                 *[fmt(-hat_under(kid), " dB", 1) for kid in KIDS]))

    def snare_vs(kid):
        b = kits[kid]["groove_balance_db"]
        for p in kits[kid]["pads"]:
            if p["voice"] == "snare":
                return b[p["name"]]

    rows.append(("Snare against the kick, in the groove", "within about 3 dB",
                 *[fmt(snare_vs(kid), " dB", 1) for kid in KIDS]))
    g = lambda kid, key: kits[kid]["groove"][key]
    rows.append(("Groove: 95 % of the energy under", rng([r["roll95_hz"] for r in heavy + slammed], " Hz"),
                 *[fmt(g(kid, "roll95_hz"), " Hz") for kid in KIDS]))
    rows.append(("Groove: share in 60–150 Hz", rng([r["bands"]["60-150"] for r in heavy + slammed], " %", 0),
                 *[fmt(g(kid, "bands")["60-150"], " %") for kid in KIDS]))
    rows.append(("Groove: swing (50 straight, 67 triplet)", "50–57 (one tape loop 67)",
                 *[fmt(g(kid, "groove_all").get("swing_pct_16th"), "", 1) for kid in KIDS]))
    return rows


def main(out):
    site = os.path.join(out, "site")
    os.makedirs(os.path.join(site, "audio"), exist_ok=True)
    report = json.load(open(os.path.join(out, "report.json")))
    refs = json.load(open(os.path.join(HERE, "references.json")))
    manifest = []
    by_id = {k["id"]: k for k in KITS}
    built = {}  # kit id -> {pad name: (file, slot)}, so a drumless kit can share its parent's identical pads
    for kit in KITS:
        kid = kit["id"]
        pads = []
        names = [p["name"] for p in kit["pads"]] + [kit["roll"]["name"]]
        parent = by_id.get(kit.get("parent"))
        mine = built.setdefault(kid, {})
        for i, name in enumerate(names):
            folder = os.path.join(out, kid, f"{i + 1:02d}_{name.replace(' ', '_')}")
            fn = f"audio/{kid}_{i + 1:02d}.mp3"
            shared = None
            if parent is not None:
                if i < len(kit["pads"]):
                    twin = next((p for p in parent["pads"] if p["name"] == name and p["voice"] == kit["pads"][i]["voice"]
                                 and p["params"] == kit["pads"][i]["params"]), None)
                else:
                    r, pr = kit["roll"], parent["roll"]
                    same = r["name"] == pr["name"] and kit["pads"][r["pad"]]["name"] == parent["pads"][pr["pad"]]["name"]
                    twin = {"name": name} if same else None
                if twin is not None:
                    shared = built[parent["id"]][twin["name"]]
            if shared:
                fn, slot = shared
            else:
                slot = sprite(folder, os.path.join(site, fn))
            mine[name] = (fn, slot)
            rep = report["kits"][kid]["pads"][i]
            voice = rep["voice"]
            pads.append({"n": i + 1, "name": name, "file": fn, "slot": slot, "voice": voice,
                         "note": VOICE_NOTES.get(voice, "") + (f" (the same pad as {parent['title']}'s)" if shared else ""),
                         "bright": round(rep["soft_over_hard_hf_db"], 1),
                         "takes": round(rep["worst_closest_takes_db"], 1)})
        gfn = f"audio/{kid}_groove.mp3"
        mp3(read(os.path.join(out, kid, "groove.wav")), os.path.join(site, gfn))
        pads.append({"n": 16, "name": "Groove", "file": gfn, "slot": None, "voice": "groove"})
        manifest.append({"id": kid, "parent": kit.get("parent"), "title": kit["title"], "bpm": kit["bpm"], "swing": kit["swing"],
                         "print": kit["print"], "pads": pads, "groove": gfn,
                         "groove_s": round(report["kits"][kid]["groove_seconds"], 2)})
        print(kid, "audio done", flush=True)
    if os.environ.get("SIGIL_EMBED"):
        # one self-contained page: every MP3 inlined as base64 (the page decodes it itself; no fetch needed)
        import base64
        done = set()
        for kit in manifest:
            for p in kit["pads"]:
                if p["file"] in done:
                    continue  # a shared pad's audio is inlined once, under its parent
                p["data"] = base64.b64encode(open(os.path.join(site, p["file"]), "rb").read()).decode()
                done.add(p["file"])
    rows = ref_rows(refs, report)
    head = "".join(f'<th class="{k["id"]}">{k["title"]}</th>' for k in KITS if not k.get("parent"))
    page = TEMPLATE.replace("/*MANIFEST*/", json.dumps(manifest)).replace("<!--KITHEADS-->", head).replace("<!--ROWS-->", "\n".join(
        "<tr>" + "".join(f"<td>{html.escape(str(c))}</td>" for c in r) + "</tr>" for r in rows))
    open(os.path.join(site, "index.html"), "w").write(page)
    print("page:", os.path.join(site, "index.html"))


TEMPLATE = open(os.path.join(HERE, "page_template.html")).read() if os.path.exists(
    os.path.join(HERE, "page_template.html")) else ""

if __name__ == "__main__":
    main(sys.argv[1])
