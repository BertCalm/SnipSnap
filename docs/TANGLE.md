# TANGLE

Tangle ports the [supplied engineering specification](superpowers/specs/2026-10-11-tangle-engine-engineering-spec.md)
into SnipSnap's deterministic offline melodic path. Two oscillators influence
each other's future samples, catch temporarily, and escape into beating and
metallic movement. Encounters generate held timbral controls; a quiet root
anchor and octave-down support keep the requested note perceptible.

The port is an experimental instrument awaiting the owner's listening
verdict. Its normalized mappings, voice calibrations and recipe names are
prototype choices. The historical reference informs waveform mixing and
cross-modulation; Tangle's capture, asymmetric response and root support are
new proposals, not claims about the original instrument.

## Playing and saving

BASS, LEAD, HOLLOW, PULSE, METAL and KNOT share one architecture. Their
waveform blends, intervals, response times, envelopes and capture schedules
select different parts of its range.

| Control | Behavior |
| --- | --- |
| TANGLE | Restrained reciprocal influence → dense mutual modulation and encounter-driven movement |
| CHASE | A leads B → balanced influence → B leads A, with continuous response-time changes |
| CATCH | No capture at zero → stronger, more frequent temporary attraction and bounded dwell |
| BITE | Rounded filter response → brighter resonance and bounded nonlinear edge |
| WEIGHT | Quiet root floor → stronger requested root and octave-down support |
| HOLD | Finite note below .99; sustained loop at .99 and above |

Each `TanglePatch` saves `midi` and `velocity` beside the six macros. Pitch
does not use an extra macro. Saved MIDI roots span 24–84 (C1–C6); defaults are
36 for BASS and KNOT, 48 for LEAD, HOLLOW and PULSE, and 60 for METAL.
Velocity spans 0–1 and acts inside synthesis on early energy, filter opening
and bounded modulation effort; velocity zero is silent. Its filter-opening
factor is `0.45 + 0.55 × velocity`, so soft notes close upper harmonics before
shared leveling. The shared velocity-layer path re-renders the same note at
the requested velocity without moving its timbral macros.

The two octave-down subs remain derived from their oscillator phases and
keep a separate WEIGHT-controlled gain budget. Their combined gain tapers by
`min(1, rootHz / 110)²` below 110 Hz, where infrasonic support and the odd-ratio
sub's longer period can obscure the requested root. This taper leaves the
unmodulated root anchor unchanged.

Encounters accept wrapped ratio-phase crossings, oscillator wraps within a
phase-proximity window, and actual A/B waveform-value crossings. The last
case lets a stable nonzero phase relationship keep generating controls when
its phase error never crosses zero. These are meetings of the oscillator
system, gated to the bounded event rate; no random or independent clock
replaces them. `HeldEvent.relationship` records which relationship produced
each accepted sample alongside its A/B source values and phase error.

JSON follows shared patch version 1. Omitted or null note and velocity fields
read as the voice's root and velocity 1. Missing macro values use engine
defaults; partial macro maps remain partial when saved. Unknown voices,
unsupported patch versions, unknown macro names, nonfinite values and values
outside their allowed ranges are refused. Name/macro edits, recipe replay
and preset promotion preserve saved note and velocity. Initial phases and
variation derive from `Dsp.seedFor`; no private render state is serialized.

Output is mono at 44.1 kHz using the standard 4× internal path, DC removal,
antialias filtering and `MELODIC_LOUDNESS_TARGET` leveling. Raw renders and
capture/held-event traces remain available to probes so leveling cannot
stand in for stability or control activity.

## Presets and integration

The 36-recipe first listening roster is grouped by intended use:

| Family | Count | Examples |
| --- | ---: | --- |
| Bass | 10 | Root Knot, Soft Pursuit, Quiet Trap, Held Root |
| Lead | 8 | Catch Wire, Plain Thread, Hollow Pair, Long Escape |
| Pulse | 6 | Crawling Teeth, Slow Encounter, Held Crawl |
| Metallic | 6 | Metal Recoil, Soft Alloy, Held Recoil |
| Experimental | 6 | Tight Knot, Loose Ends, Held Struggle |

HOLLOW's two fixed-interval recipes belong to the lead family. Eight held
recipes cover all six voices. The clean starting points and moderate
examples are included for direct comparison with the denser cases. None of
the recipe names implies a passed listening gate.

`Patches`, `Presets`, `PadRecipe` and `Velocity` use the same regenerable
sound. Finite renders carry TONAL metadata so IN KEY and pad tuning can
retune them; held material carries LOOP metadata. Raw classifier probes are
still needed to establish that pitched cases do not resemble drums; routing
metadata does not prove that sonic acceptance condition.

The phone SYNTH picker exposes all six voices, macros, preview and saving.
Loaded patches retain note and velocity through edits and placing. Like the
existing explicit-note engines, newly created phone patches use the voice
root; separate note overrides are available in recipes and the CLI.

`SynthKits.tangle()` and FRESH TAPE's TANGLE starter create sixteen dry pads.
A01–A08 walk BASS up C minor pentatonic from C2. A09–A14 contain Catch Wire,
Hollow Pair, Crawling Teeth, Metal Recoil, Tight Knot and Quiet Trap. A15 and
A16 are Long Escape and Held Struggle. Every pad stores its complete recipe;
empty FX preserves the oscillator interaction.

```text
./gradlew :cli:run --args="synth TANGLE BASS --all --midi 36 --velocity 0.4 --out /tmp/tangle-bass"
./gradlew :cli:run --args="synth TANGLE LEAD --preset 6 --midi 60 --out /tmp/tangle-held"
```

## HOLD and acceptance

HOLD requires recurrence of the full oscillator/control/filter system,
including reciprocal-modulation history, capture states and timers,
encounter registers, smoothing, filter and DC memory. This prototype imposes
a stronger periodic constraint than the specification's schedule-only
allowance: it repeats both an encounter/capture schedule and an oscillator
phase trajectory derived from a causal sustained prerender. Mean phase drift
is removed to fit whole root/sub periods. A repeating corrective phase-rate
term and smooth attraction toward that trajectory then act inside synthesis.
Actual preceding oscillator outputs still drive reciprocal modulation;
the held renderer does not regenerate an unconstrained free trajectory.
This forcing can change the free interaction, and it repeats one fitted
catch sequence even when its timbre remains active. Listening must determine
whether that constrained motion preserves Tangle's identity.

The phase-rate residual is bounded to 1.20 times each oscillator's nominal
rate, trajectory attraction to 0.45 times that rate, and total correction
(including temporary capture) to 2.05 times it. The oscillator frequencies
retain their independent positive bounds and 12 kHz ceiling. Exceeding a
correction budget fails with a diagnostic. These are prototype budgets,
not evidence that the held sound matches a free sustain.

Loop selection preserves the requested pitch, with only the fine adjustment
needed to fit whole periods to output frames. The physical period fits whole
root/sub cycles near 1.65 seconds, above the house's 1.5-second sustain
classification threshold; it contains synthesized motion rather than padding.
The settling search compares
consecutive synthesis states and also the eight retained states of the
antialias filter plus enough filtered-sample history to cover every sinc
decimation stage. The largest synthesis or conditioning difference enters
the full-state error, which must fall below 2e-6 within eight passes. Periodic
guards keep filtering away from buffer-edge padding. Failure reports a
diagnostic; failed candidates receive no seam repair. Independently rendered
consecutive periods must also meet the seam check, and exported leveled
samples must meet `Keys.seamError < 1e-3`.

`renderLoopMeasured` retains a seam context containing the end of an
independently synthesized preceding period and the final period at the same
leveled gain. Export checks use that context, so a seam check cannot pass
merely by duplicating one buffer's identical samples.

The current pad API exports loop-only sustain material without an
attack-plus-loop-region field. Repeating a held audition clip is the way to
hear its wrap; a normal kit pad plays the exported buffer once.

```text
./gradlew --no-daemon test
./gradlew :synth:generateTangleAudition
```

The audition task writes a standalone listening pack, dry audio, a preset
manifest and probe data under `testkit/tangle-audition/`. The comparisons
include voice defaults, low/middle/high notes and velocities, five-step
timbral sweeps, paired interactions and held examples. Capture and held
event traces expose their oscillator lineage and bounded timing.

The raw PCM listening files apply one common gain of 0.5 before export, so
over-unity internal headroom cannot clip their waveforms. This preserves
relative levels across the raw comparisons. `rawExportGain` records that
attenuation; raw probe metrics describe the engine output before it. Matched
files use the separate shared melodic and audition leveling paths.

## Measured prototype limits

The first complete matrix on 2026-10-11 contained 372 probe cases and 172
listening clips, including all 36 presets. Every case was finite, all six
default voices repeated sample-identically, and none of the 90 standard
cases received a forbidden drum class. The house classifier's length rule
files long material as LOOP; this result alone does not prove perceived pitch.
Maximum internal raw peak was 1.4332 against the 1.5 budget, and maximum
leveled-output DC magnitude was 4.11e-7. The fourteen held cases had maximum
full-state error 1.34e-11; independent PCM16 seam checks read zero after
quantization. These numbers establish recurrence and bounds, not listening
acceptance.

All 90 standard cases retained a local requested-root spectral peak within
25 cents. Five nevertheless exceeded 50 cents in the global house pitch
detector: KNOT at MIDI 84 at each of the three velocities, Slow Encounter,
and Heavy Sideband. Across all 372 cases, 25 exceeded the detector threshold
and one experimental corner (`pair_knot_chase1_catch0`) exceeded the local
root-peak threshold. The house detector only covers notes at or above MIDI
28; low C1 cases use the separate requested-root measurements. Proposed
MIDI 24–84 input coverage is therefore not a universal tonal guarantee.

The all-high MIDI 84 references compare 4×, 8× and 16× generation. Maximum
normalized twelve-band spectral distances were .04090 (4×/8×), .02834
(4×/16×) and .03125 (8×/16×). Interaction trajectories can change with the
update rate, so these comparisons do not isolate alias energy or prove its
absence.

Render costs were measured on Linux amd64, Java 21.0.12.1, four available
processors, while the repository suite ran concurrently:

| Path | Cases | Median | 95th percentile | Maximum |
| --- | ---: | ---: | ---: | ---: |
| Finite probe plus shared leveling | 358 | 317 ms | 748 ms | 1180 ms |
| Held validation, two complete synthesis runs plus leveling | 14 | 2278 ms | 3088 ms | 3393 ms |

The complete pack took 216.1 seconds including analysis, file writing and
higher-rate references. The provisional workstation budget is a 95th
percentile below one second for finite rendering and below four seconds for
the duplicated held validation path on this class of worker. Single held
render cost and phone performance need separate measurements; this benchmark
is not a device latency claim or a timing assertion in CI.

The [private published audition](https://tangle-snipsnap-audition.bertcalm.chatgpt.site)
uses this generated audio and awaits the owner's listening verdict.

Production acceptance remains separate from the implemented prototype. The
owner must hear playable moderate patches, recognizable root, CHASE's
directional change, CATCH's settling/release and encounter-generated
movement. Extreme tones must stay distinctive and bounded, and repeated
held loops must preserve movement without clicks, pitch changes or rhythmic
stutters. Device/browser and MPC playback validation remain outstanding
until exercised. Automated browser QA was unavailable in this environment,
and Android compilation/CI validation remains pending. No listening verdict
is recorded by the implementation.
