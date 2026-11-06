# HYDROFORM

HYDROFORM renders a pressure burst through a dispersed return, a derivative-controlled
fold, and an explicit short ending. DOME is a low swept burst; BULKHEAD is a higher,
inharmonic strike. Both are mono, dry and deterministic. No fluid or collision solver runs.

The current implementation ports accepted Python R5. The native output stage uses
SnipSnap's own filtering, DC removal and loudness algorithm; its audio is a fresh listening
gate. No final presets or kit integration are included in this round.

| Control | Default | Behavior |
|---|---:|---|
| TUNE | .50 | Continuous two-octave range: 24.5–98 Hz DOME; 73.5–294 Hz BULKHEAD |
| CHARGE | .70 | Source amplitude and nonlinear drive |
| STANDOFF | .35 | Three stable delayed all-passes, arrival offset and direct/return interference |
| YIELD | .50 | Slope activation threshold; lower values engage folding more readily |
| ARREST | .65 | Exponential 650 to 88 ms stop; max is the former R1 .75, not its 45 ms choke |
| HOLD | 0 | DOME only: exactly 1 retains a 2 s, 120 BPM-assumed eighth-note stutter |

Velocity changes the source onset and excitation, plus upper-partial weight on BULKHEAD.
Zero velocity returns silence. Velocity is serialized so a saved touched recipe replays.
No random source or variation is added during the port.

DOME files as KICK, BULKHEAD as PERC, and held DOME as LOOP. These are intended kit slots,
not a claim that every distorted corner is recognized identically by the classifier.
On actual native WAVs, the classifier calls default DOME PERC (confidence .40), default
BULKHEAD SNARE (.662), and HOLD LOOP (.90). Across the initial 89 non-hot studies: 56 PERC, 30 SNARE,
2 KICK, 1 LOOP. These heuristic labels do not silently re-voice the accepted instrument;
factory kit placement is deferred. See reference/hydroform/native-classification.csv.
No pitch correction is added: the attack sweeps and inharmonic/distorted corners can confuse
pitch detection. Root metadata retains continuous tuning with approximate MIDI labels.

Run:

```
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generateHydroformAudition
```

The generator uses reference/hydroform/python-manifest.json by default and writes to
`testkit/hydroform-audition`. Override `hydroformManifest` or `hydroformOutput` with `-P`.
It writes native WAVs, replayable production recipes, raw floats for Python parity checks,
and timing, loudness and pitch diagnostics. The frozen comparison WAV has no production
recipe because the fixed shaper is an audition control only.

The hardest BULKHEAD corner remains an aliasing risk. Python 4x-vs-16x aligned residual was
about -18.4 dB there; that includes filter and derivative differences and is not an alias bound.
No higher-rate claim or spectral repair is silently introduced in Kotlin.

## Why it is built this way

- Analytic sources preserve the auditioned onset without a solver or random variation.
- A stable delayed return makes STANDOFF change attack structure before distortion.
- Smooth, root-scaled slope activation gives the folding its accepted dynamic character.
- Explicit stop/ramp timing implements ARREST independently from clipping.
- The shared house output stage makes the port compatible; raw parity distinguishes a port
  error from an expected filtering or loudness difference.
