# SIGIL round 0 (Python prototype)

Four drum kits (IRON 74, NEON 90, ASH 115, KILN 100 BPM) written after the owner's 23 reference loops. Design notes and every
measured number: [docs/SIGIL.md](../../docs/SIGIL.md). Python harness, not Kotlin; nothing here has been heard.

```sh
python3 prototype/sigil/gen.py OUT            # 4 kits x 16 pads x 3 zones x 4 takes, rolls, grooves, checks -> OUT/report.json
python3 prototype/sigil/build_page.py OUT     # OUT/site/index.html and its MP3 sprites
SIGIL_EMBED=1 SIGIL_KBPS=96 python3 prototype/sigil/build_page.py OUT   # one self-contained page, audio inlined
python3 prototype/sigil/measure.py REFS OUT   # the references' numbers (references.json); REFS is never committed
```

- `sigil_proto.py`: the twenty voices, the soft-strike tone floor, and the four prints (blown speaker, slam, tape, sampler).
- `kits.py`: the four kits as data: pads and their constants, rolls, grooves written as step strings.
- `gen.py`: renders and checks everything (finite, peak, DC, take distance, soft-darker, kick and snare stats, groove
  balance from stems) and measures each groove with the reference probes.
- `measure.py`: the probes, shared by the references and our grooves.
- `build_page.py`, `page_template.html`: the listening page.
- `references.json`: the 23 references' numbers only. The audio itself stays with the owner.
- `report.json`: round 0b's numbers (all four kits).

Needs numpy, scipy, ffmpeg (for the page) and the Luthier `snipkit.py` (found through the skill folder).
