#!/usr/bin/env python3
"""
Scan every registered synth engine against the Luthier registration checklist
(.claude/skills/luthier/references/snipsnap-conventions.md, section 3) and the
house probe set (section 5), and print the result as a Markdown table.

Read-only, standard library only. Every column is a grep over the tree, so each
one is a *presence* check, not a quality judgement: "tests=3" says three files
match `XxxTest.kt`, not that they prove anything, and a probe counted "hit" means
the keyword appears in the engine's own test files. A shared test (PresetsTest,
PatchesTest, DeterminismTest) can cover an engine this scan files as missing.
Read the doc that cites this script for how the columns were weighed:
docs/ENGINE_STANDARD_SCAN_2026_11.md.

    python3 scripts/engine_standard_scan.py            # the table
    python3 scripts/engine_standard_scan.py --gaps     # the table plus per-probe gap lists
"""
import os, re, glob, sys
R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SY = f"{R}/synth/src/main/kotlin/com/snipsnap/synth"
ST = f"{R}/synth/src/test/kotlin/com/snipsnap/synth"
SH = f"{R}/shell/src/main/kotlin/com/snipsnap/shell"
SHT = f"{R}/shell/src/test/kotlin/com/snipsnap/shell"
AUD = f"{R}/synth/src/test/resources/audition"
patches = open(f"{SY}/Patches.kt").read()
engines = re.findall(r"^\s*([A-Za-z]+)Patch\.ENGINE ->", patches, re.M)
pt = open(f"{ST}/PatchTypes.kt").read()
rosterless = re.search(r"ROSTERLESS[^\n]*", pt).group(0)
protos = re.search(r"PROTOTYPES[^\n]*", pt).group(0)
cat = open(f"{SH}/EngineCatalog.kt").read()
det = open(f"{ST}/DeterminismTest.kt").read()
roadmap = open(f"{R}/docs/SYNTH_ROADMAP.md").read()
synthkits = open(f"{SY}/SynthKits.kt").read()
presets_kt = open(f"{SY}/Presets.kt").read()
velocity = open(f"{SY}/Velocity.kt").read()
def rd(p):
    try: return open(p).read()
    except: return ""
PROBES = {
 "det": r"assertContentEquals|determin",
 "finite": r"isFinite|finite",
 "dc": r"\bmean\b|DC|dc offset",
 "loud": r"levelTo|loudness|Loudness|MELODIC_LOUDNESS",
 "pitch": r"Pitch\.detect|rootHz|cents",
 "macro": r"every macro|each macro|changes the sound|macro changes|unknown key|unknown macro",
 "json": r"fromJsonText|toJsonText|round trip|roundTrip",
 "class": r"Classifier|classify|drumClassFor",
 "seam": r"seamError|requireSeam",
 "scramble": r"scramble",
 "nonfinite": r"NaN|POSITIVE_INFINITY|non-finite|nonFinite",
}
rows = []
for e in engines:
    E = e.upper()
    key = "SCANNED_CHAIN" if e == "ScannedChain" else E
    roster = "RLESS" if f"{e}Patch" in rosterless else ("PROTO" if f"{e}Patch" in protos else "yes")
    presets = os.path.exists(f"{SY}/{e}Presets.kt") or os.path.exists(f"{SY}/{e}FactoryPresets.kt") or os.path.exists(f"{SY}/{e}KitPresets.kt")
    parm = (f"{e}Presets." in presets_kt) or (f"{e}FactoryPresets." in presets_kt) or (f"{e}KitPresets." in presets_kt)
    kit = ("yes" if re.search(rf"fun {e[0].lower()+e[1:]}\w*\(", synthkits) else ("own" if os.path.exists(f"{SY}/{e}Kits.kt") else "no"))
    gen = bool(glob.glob(f"{ST}/{e}Kit*Generator.kt") or glob.glob(f"{ST}/{e}KitGenerator.kt"))
    aud = bool(glob.glob(f"{ST}/{e}*AuditionGenerator.kt") or glob.glob(f"{SHT}/{e}*AuditionGenerator.kt"))
    html = bool(glob.glob(f"{AUD}/{e.lower()}*") or glob.glob(f"{AUD}/{e.lower().replace('scannedchain','scanned-chain')}*") or glob.glob(f"{ST}/{e}*AuditionGenerator.kt") and "html" in rd(glob.glob(f"{ST}/{e}*AuditionGenerator.kt")[0]).lower())
    tests = sorted(glob.glob(f"{ST}/{e}*Test.kt"))
    ptest = any("Presets" in t for t in tests)
    deter = bool(re.search(rf"\b{e}(Patch|Presets|FactoryPresets|KitPresets)?\b", det))
    # the engine's own test files may hold their own determinism canary
    body = "".join(rd(t) for t in tests)
    probes = {k: bool(re.search(v, body)) for k, v in PROBES.items()}
    if probes["det"]: deter = True
    docs = sorted(os.path.basename(p) for p in glob.glob(f"{R}/docs/{E}*.md") + glob.glob(f"{R}/docs/engines/{E}*.md") + glob.glob(f"{R}/docs/{E.replace('SCANNEDCHAIN','SCANNED_CHAIN')}*.md"))
    app = f'"{key}"' in cat
    keys = bool(re.search(rf"fun {e[0].lower()+e[1:]}Pad\b", rd(f"{SY}/Keys.kt")))
    pm = os.path.exists(f"{SH}/{e}PadMaker.kt") or os.path.exists(f"{SH}/{e}Pad.kt")
    src = rd(f"{SY}/{e}.kt") + rd(f"{SY}/{e}Engine.kt") + rd(f"{SY}/{e}Instrument.kt")
    hold = '"HOLD"' in src or '"HOLD"' in rd(f"{SY}/{e}Patch.kt")
    road = len(re.findall(rf"\|\s*{E}\b", roadmap)) + len(re.findall(rf"\b{E}\b", roadmap))
    rows.append(dict(engine=E, roster=roster, presets=presets, parm=parm, kit=kit, gen=gen, aud=aud, html=html,
        tests=len(tests), ptest=ptest, det=deter, probes=probes, docs=docs, app=app, keys=keys, pm=pm, hold=hold, road=road))
hdr = "engine|roster|presets|kit|kitgen|audition|page|tests|preset test|determinism|probes hit/11|seam test|docs|picker|keygroup|padmaker|HOLD|roadmap mentions"
print("|" + hdr + "|"); print("|" + "|".join("---" for _ in hdr.split("|")) + "|")
for r in rows:
    p = r["probes"]; hit = sum(p.values())
    print("|%s|%s|%s|%s|%s|%s|%s|%d|%s|%s|%d|%s|%s|%s|%s|%s|%s|%d|" % (
        r["engine"], r["roster"], ("yes" if r["presets"] else "no") + ("" if r["parm"] else " (no arm)"), r["kit"],
        "yes" if r["gen"] else "no", "yes" if r["aud"] else "no", "yes" if r["html"] else "no", r["tests"],
        "yes" if r["ptest"] else "no", "yes" if r["det"] else "no", hit, "yes" if p["seam"] else ("n/a" if not r["hold"] else "NO"),
        ",".join(r["docs"]) or "none", "yes" if r["app"] else "no", "yes" if r["keys"] else "no", "yes" if r["pm"] else "no",
        "yes" if r["hold"] else "no", r["road"]))
if "--gaps" not in sys.argv:
    sys.exit(0)
print()
print("probe keys:", list(PROBES))
missing = {k: [r["engine"] for r in rows if not r["probes"][k]] for k in PROBES}
for k, v in missing.items(): print(f"{k} missing ({len(v)}): {' '.join(v)}")
