# WICK RATTLE: alternate source study

Status: historical source-selection audition, subsequently accepted by the owner.
Warm wire is now the default and all three alternate bodies have
[normal saved patches](WICK_RATTLE_PATCHES.md). The design and measurements
below record the earlier audition round. Repository baseline:
`a5caac7fd6c8a2e6d09ec740c60fb995c6bef368`. The owner rejected the underlying
tone of the connected-body RATTLE and asked to apply its character and movement
to a different sound. That feedback changes the source slot; it does not call
for another envelope revision.

The characteristic gesture remains **one pitched body opens → recovering gates
move inside it → answering openings soften → the body decays**.

## Design and landing

This is a REVIEW followed by a source-stage SPLICE. `Wick.inspect`,
`Wick.Options`, the gate network, output stage and existing phrase exporter were
read on the baseline above. The nearest source relatives are REGISTERS for
harmonic ranks and MERCURY for glass-like partial spacing. Neither source idea
earns a separate engine here: the useful experiment is that tone inside WICK's
existing release-triggered movement.

| Direction | Source analogy | Gesture, risk and cost |
|---|---|---|
| Warm wire, starting recommendation | Mellow full harmonic spectrum | Clear pitched body, low numerical risk; one table per render. May remain too close to an ordinary oscillator after filtering. |
| Hollow reed | Odd harmonics, no even upper partials | Same gesture with a hollow spectrum; low numerical risk and the same table cost. May overlap wire at darker settings. |
| Rubbed glass | Root with quiet half-order upper partials | Same gesture with a less harmonic edge; low numerical risk and the same table cost. Half-order color may suggest a lower pitch. |

These names describe tonal analogies. No physical string, reed, bow, friction or
glass model is simulated. All three use the same slot replacement so the
audition isolates the user's question. No effects are added.

```text
family, pitch, TIMBRE, velocity → precompute 4096-point source table
                                          ↓ continuous audio at 4×
unchanged charge / reserve / release edges → three existing low-pass gates
                                          ↓ same VCA and stage balance
                   band-limit → decimate → DC removal → level → finite tail
```

`Wick.RattleSource` has `CURRENT`, `WARM_WIRE`, `HOLLOW_REED` and
`RUBBED_GLASS`. The appended diagnostic `Options.rattleSource` defaults to
`CURRENT`. Ordinary patch JSON, presets, kits, macros and the six voice names
are unchanged. The five accepted voices ignore the option. The source study
uses explicit diagnostic recipes rather than ordinary patches which would
silently discard the candidate selection.

## Source and control contract

The root has weight one. Upper weights are multiplied by
`1.8 * TIMBRE^.85 * (.8 + .2 * velocity)`, then the table is normalized by the
sum of retained weights. All coefficients below are proposed sound-design
shapes, pending listening.

| Family | Ratios | Upper weights before exposure |
|---|---|---|
| Wire | 2 through 12 | .46, .29, .19, .13, .09, .065, .046, .033, .024, .017, .012 |
| Reed | 3, 5, 7, 9, 11, 13, 15 | .48, .23, .13, .075, .043, .025, .014 |
| Glass | 2.5, 3.5, 5 | .25, .18, .10 |

Partials at or above 20 kHz are omitted before table construction. Cubic table
reads replace the old folded-FM source and all of its added stage harmonics.
Glass spans two root cycles, matching the existing even-cycle HOLD plan. No
new evolving source state, solver or cross-render cache is introduced. Each
candidate builds a roughly 32 KiB table once per render; table inputs are the
family, pitch, TIMBRE and velocity. Render timings remain measured cloud JVM
costs, not a claim of phone performance or the tens-of-ms interactive target.

| Control | Fixed audition default | Role retained |
|---|---:|---|
| SPARK | .65 | Initial charge and opening speed |
| FEED | .50 | Supply pulse rate |
| CASCADE | .65 | Release-driven answering transmission |
| TIMBRE | .45 | Candidate upper-partial exposure; current body keeps its original mapping |
| RECOVERY | .65 | Reserve refill, charge lifetime and transmission delay |
| HOLD | 0 or 1 | Complete finite note or independently converged held period |

The comparison fixes pitch, velocity and all controls. Equality of actual event
records, rather than similarity of the output envelope, establishes that the
same gate movement drives every source. Different spectra can still change
perceived articulation through those same moving filters.

## Reproduction and evidence

Run `./gradlew :synth:generateWickSourceStudy` for a separate
`testkit/wick-source-study/` output. It contains four sources at C3/C4/C5, four
held C4 cases, and four versions of the same eight-note, 100 BPM phrase. The
current body remains a reference; no candidate is installed as the default.
Phrase notes rebuild the engine independently and retain complete finite tails.

`WickSourceStudyTest` covers source isolation, finite/pitched output, identical
causal events, TIMBRE activity, determinism and real held recurrence. Export
metrics and the independent artifact report distinguish verified behavior from
musical preference. A higher-rate comparison measures numerical agreement,
not isolated alias power. The existing accepted audio remains available.

The full repository JVM suite must be judged by its exit code. An earlier local
cohesion run was interrupted when the environment ended and has no completion
result; it must not be carried forward as a running or passed check.

## Evidence ledger

- Verified: source isolation and the unchanged control path are readable in the
  guarded implementation; generated reports record the rendered evidence.
- Measured: use the export manifest and accompanying artifact validation for
  actual pitch, bounds, event equality, repeatability, seam and timing numbers.
- Proposed: the three spectra give sufficiently different bodies for choosing
  the next RATTLE direction. Filtered similarity remains a listening risk.
- Heard: the owner dislikes the prior base tone. No new candidate has a verdict.

An independent Kotlin preservation probe compiled the baseline under a renamed
object and compared all six voices at their finite and held defaults. All 12
pairs match exactly in raw samples, levelled samples and diagnostics; the six
genuine held contexts also match. This probe covers 1,673,365 output frames for
each raw/levelled comparison, rather than relying on two calls to the new path.

The coordinated focused command completed with exit code zero: 19 WICK DSP,
five patch, four source-study and three CLI tests. All 20 study clips exported.
The Kotlin opening detector measured the three alternate families at C3/C4/C5
within .766 cents, with confidence at least .840. Median finite render times
across those three notes were 298 ms for wire, 271 ms for reed and 267 ms for
glass on the cloud JVM. These small, sequential samples are not a controlled
performance comparison or a device benchmark.

At C6 and TIMBRE one, normalized twelve-band 4×/8× distances measured
.000183 for wire, .000270 for reed and .000116 for glass. This is numerical
reference agreement; it does not isolate alias power or establish listening
quality. Full JVM validation is tracked separately from the focused result.

Independent PCM verification finds all 12 current register/phrase WAVs exact
against the previous export. All four genuine held PCM boundary errors are
zero, with wrap-step ratios .935–1.245. All four phrases reconstruct from their
saved PCM24 stems within two PCM24 units. A separate requested-root projection
measures all 12 registers within 1.801 cents; this uses a different measurement
from the opening detector above. Pre-fade RMS stays below .526% of body RMS.
At C4, one normalized FFT-window distance from the current body is .13569 for
wire, .07886 for reed and .03613 for glass. That establishes spectral change
in that window, not the owner's preference or perceived difference.

## Why it is built this way

- Keeping the gates fixed lets the owner judge the body independently of motion.
- Replacing the complete source slot removes the old FM color from candidates.
- Three small spectral families offer a useful choice before a larger model is built.
- The current body and accepted voices remain exact references during selection.
- The main risk is musical: a spectrum can measure differently and still feel
  like the wrong instrument. The audition, not a metric, decides the next step.
