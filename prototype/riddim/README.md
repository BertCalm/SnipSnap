# RIDDIM round 0 (Python prototype)

Dancehall drum and percussion voices synthesized to targets measured from the owner's reference loops. Design
notes and every measured number: [docs/RIDDIM.md](../../docs/RIDDIM.md). Python harness, not Kotlin; nothing here
has been heard.

```sh
python3 prototype/riddim/riddim_proto.py OUT        # every voice soft/hard, two takes, and three sketch grooves
python3 prototype/riddim/check_voices.py OUT        # pitch tracks, decays, take distance, velocity brightness
python3 prototype/riddim/kits.py OUT2               # round 1: three kits, every pad 3 zones x 4 takes, grooves
python3 prototype/riddim/build_page.py OUT2         # round 1 listening page (needs ffmpeg)
python3 prototype/riddim/measure_groove.py REFS/*.wav                                # sixteenth-step grids per band
python3 prototype/riddim/measure_targets.py REFS reference_jobs.json OUT.json        # hit-level targets
```

- `riddim_proto.py`: the voices and grooves. Imports CALABASH's prototype for the reused percussion.
- `measure_groove.py`, `measure_targets.py`, `reference_jobs.json`: how the references were measured. The loops
  themselves are commercial and are not committed; `reference_targets.json` holds only the numbers.
- `check_voices.py`: the new voices against those numbers.
- `kits.py`: round 1's three 16-pad kits (3 velocity zones x 4 takes per pad), the A16 rolls, the grooves and
  `checks.json` (take distance and velocity brightness per pad). `build_page.py OUT` writes the listening page.

Needs numpy and scipy.
