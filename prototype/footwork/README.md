# FOOTWORK R0 sound proof

This is a sample-free Python audition, not the delivered three-kit Kotlin instrument. See [the design and complete gate contract](../../docs/FOOTWORK.md).

Run from the repository root, with NumPy and SciPy installed:

```sh
python3 prototype/footwork/footwork_proto.py /absolute/output/directory
```

Open the output `index.html` beside its `audio/` folder, or extract `footwork-first-audition.zip` first. It contains eight contact/gesture candidates, three velocity layers and four takes per candidate, touch sequences and an original dance phrase. `manifest.json` contains hashes and source-level checks. No performance recording is included.

The prototype follows the repository Luthier prototype-template workflow, reusing its `snipkit` module. Fixed per-voice gains were calibrated once against the four hard takes for headroom, then kept identical for all velocities and takes. Python uses NumPy's seeded generator; Kotlin RNG equivalence remains a porting decision to verify. All modal frequencies, balances, contact widths and damping laws are unapproved shape hypotheses.

Remaining gates: the full 16+2 pad vocabulary, three distinct shelf kits, authored roll/riser, native saving/playback, SFZ/MPC export, Kotlin parity, owner listening and device acceptance. Do not label this pack a finished kit.
