# DRAWL round 0 (Python prototype)

A bar-stopped string whose pitch path is the performance: resonator guitar, electric slide, lap
steel, pedal steel, country flat-top and western nylon. Design, measurements and the gate plan:
[docs/superpowers/specs/2026-11-20-drawl-engine-design.md](../../docs/superpowers/specs/2026-11-20-drawl-engine-design.md).
Python harness, not Kotlin. The owner heard round 0 on 20 Nov 2026 and asked for more twang (CONE) and whine (BOTTLE); round 0b was that change ("Better"), round 0c gave LAP the same whine and slowed the wide vibrato ("Better"), and round 0d makes the vibrato shallower, not yet heard.

```sh
python3 prototype/drawl/drawl_proto.py OUT                                  # one WAV per voice at its defaults
python3 -I prototype/drawl/measure.py REFS_DIR OUT/reference_metrics.json  # the reference loops' numbers
python3 prototype/drawl/gen_audition.py OUT prototype/drawl/reference_metrics.json   # 168 renders and the probes
python3 prototype/drawl/compare_refs.py prototype/drawl/reference_metrics.json OUT/compare.json
python3 prototype/drawl/build_page.py OUT OUT/compare.json [AB_DIR]        # OUT/site/index.html and its MP3s
```

- `drawl_proto.py`: the model. Six voices, macros TUNE SLIDE WAVER PICK BODY RING HOLD, plus
  velocity, take (round robin) and STRUM (`intervals`, `sweep`, `direction`). The string kernel runs in
  two reads: mode 0 is the house `Strings.Loop` retuned every sample, mode 1 a Lagrange read; they
  measure the same (see the spec), so the Kotlin port keeps the house loop.
- `measure.py`: centroid, band shares, resonance peaks, onset rate, a YIN-style pitch track (glide and
  vibrato statistics), decay. Its glide and vibrato numbers are only trustworthy on single lines.
- `gen_audition.py`: voices at three notes, soft, medium and hard touch, four takes, every macro at 0
  and 1, SLIDE x WAVER corners, a strum each way; root, determinism, DC, velocity-timbre, take and
  zipper probes into `report.json`.
- `compare_refs.py`: each voice at its defaults beside the reference loops it answers to.
- `build_page.py`: the audition page (SnipSnap design-system tokens, VENT and OPENSKY).
- `character_probe.py` (rounds 0b and 0c): numbers for the owner's notes: "twang" (CONE), "whine"
  (BOTTLE, LAP), and the vibrato's rate and depth.
- `reference_metrics.json`, `round0_report.json`, `round0_compare.json`, and the round-0b
  `round0b_*.json`, `round0c_*.json` and `round0d_*.json` (report, compare, character): the numbers the spec quotes.
- `NOTICE.txt`: the fifteen reference loops by sha256. Their audio is commercial and is not here.

Needs numpy, scipy, numba (the string loop), ffmpeg (the page's audio) and the Luthier `snipkit.py`
(found through `.claude/skills/luthier/scripts`).
