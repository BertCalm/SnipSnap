# HYDROFORM candidate bank / R7

Twelve proposed recipes and a sixteen-pad dry kit make the native port auditionable in
musical context. They are candidates, not factory presets. `Presets`, `SynthKits`, the
roster exemption and Android picker are unchanged. The DSP and model version remain the
merged native implementation, byte-identical to the port on commit `26207f5`.

This round was prepared against `22a6b40055192504f91a5a58f78e0ab01b53871e`.

## Reproduce

```
./gradlew --no-daemon :synth:generateHydroformCandidateKit
```

The generator writes `testkit/hydroform-candidate-bank/`; override it with
`-PhydroformCandidateOutput=/absolute/path`. The folder contains twelve PCM24 preset
studies, complete patch JSON, forty-eight PCM16 browser previews, measurements, a pattern
preview, and `hydroform-candidate-kit.zip`. The ZIP contains the MPC 3 XTD beside its
TrackData directory, an MPC 2 XPM twin inside TrackData, all forty-eight PCM24 sample
zones, editable `kit.json`, all zone recipes, all twelve candidate presets and a pad map.
The archive's map uses paths relative to the extracted archive root.

Set the MPC project to 120 BPM. The XTD has a four-bar pattern. XPM does not carry that
pattern. The software preview mixes the delivered hard-zone PCM using saved pad mixer
levels and a .25 global gain; it does not emulate an MPC velocity curve or effects.

| Pads | Source | Purpose |
|---|---|---|
| A01–A08 | LOW PRESSURE / DOME | G minor pentatonic, G0, Bb0, C1, D1, F1, G1, Bb1, C2 |
| A09–A14 | Six BULKHEAD candidates | Light tap, low panel, medium strike, cut rim, driven face, long panel |
| A15 | YIELD BREAK / DOME | Driven accent |
| A16 | DIE CYCLE / DOME HOLD | Two-second one-bar phrase at an assumed 120 BPM, played once |

All pads use one-shot playback, with no effects or choke groups. HOLD's measured seam
belongs to the engine; this kit does not add gated or automatic looping. The UI auditions
one sample at a time and displays the MPC pad orientation with A01 at bottom left.

Every pad has source velocities .3, .6 and 1. Soft and middle are native re-renders,
peak-matched to the hard strike using `Velocity.peakMatch`; MPC playback velocity owns
level. Their per-zone JSON retains source velocity and the manifest records normalization.
To reconstruct a delivered soft zone, render that JSON and peak-match it to the same pad's
hard-zone render. No additional timbre processing occurs.

## Verification ledger

**Verified by the generator:** twelve distinct candidate names, six recipes per voice,
sixteen pads, three zones each, finite mono 44.1 kHz PCM24 audio, JSON render replay,
preflight before and after export, and MPC 3/XPM software read-back without blockers.
Every delivered zone is compared against its native render after documented normalization;
allowed PCM24 quantization error is at most `3 / 8388608` per sample. Browser previews are
PCM16 transcodes with no extra leveling. The ZIP is self-contained and its paths resolve.

**Measured classification:** the sixteen hard zones classify as twelve PERC, three SNARE
and one LOOP. Proposed placement is nine KICK, six PERC and one LOOP. Those roles come
from the engine's existing `drumClassFor`, not a classifier override. Neither proposed
placement nor the detector establishes what a listener will use the sound for. Both are
recorded in the pad map. Read-back explicitly does not inspect pad color, one-shot or
humanize; these remain separate format/runtime limits, not evidence of hardware behavior.

**Proposed:** names, macro settings, pad placement and the pattern are an audition draft.
None is recorded as heard or final. Native R6 listening acceptance, Android performance
and picker work, phone behavior, and loading/playback on MPC hardware remain open.

## Why it is built this way

- The lower eight pads keep the source recipe fixed and change only pitch, making register
  comparisons straightforward.
- The upper half contrasts touch, material, drive and ending without changing the accepted
  model or inventing another voice.
- Three source strikes expose timbre changes while retaining the exporter's established
  peak-matched velocity convention.
- A one-shot phrase checks HOLD in a pattern without assuming loop playback metadata that
  has not been tested on hardware.
- Candidate status lets the listening verdict determine promotion into the factory bank.
