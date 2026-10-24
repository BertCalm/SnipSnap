# CONTOUR

Contour is a preset-first original wavetable instrument. A stable body oscillator
carries the requested note while a related edge oscillator takes a different
spectral path. Its generated tables and generic filter are musical DSP choices,
not imported Waldorf assets or a measured hardware emulation.

The source specification is
[the 11 October engineering spec](superpowers/specs/2026-10-11-contour-engine-engineering-spec.md).
SnipSnap already has a CONTOUR rack filter. That existing effect keeps its API and
saved recipes; the new synth is `ContourEngine`, identified by `CONTOUR` in engine
patch JSON. The effect and instrument have separate dispatchers.

## Playing and saving

BASS, KEY, PLUCK, LEAD, HOLLOW and SWELL select different table pairs, layer
relationships and amplitude/filter contours. Each render starts with fresh
oscillator, envelope and filter state and produces mono 44.1 kHz audio.

| Control | Behavior |
| --- | --- |
| TUNE | Host pitch control; neutral at .5, two octaves down or up from the voice root |
| SCAN | Base position along the patch's fixed table pair |
| OFFSET | Spectral displacement and response difference of the edge layer |
| MOTION | Depth and speed of pre-filter spectral travel; zero freezes both scans |
| FILTER | Generic filter cutoff, restrained resonance and envelope amount |
| DRIVE | Compensated pre-filter excitation and bounded saturation on the 4× path |
| HOLD | Finite sustain length; the top step supplies settled loop-only material |
| BODY | Root-to-overtone balance: rounded root emphasis through a fuller partial spectrum |
| EDGE | Edge-layer level and sustained projection; the contrast response also trades body overtones for edge detail with root support |
| ATTACK | Amplitude, edge and filter attack times, approximately ⅛–8× the saved recipe |
| DECAY | Amplitude decay/release and edge/filter decay times, approximately ¼–4× the saved recipe |
| RATE | Independent scan clock, approximately ⅛–8×; MOTION still sets travel and must be nonzero |
| RESO | Independent filter emphasis, without moving the cutoff control |
| ENV | Filter contour depth, from no transient through the authored amount to a larger opening |

Velocity changes excitation energy, spectral emphasis and filter response.
Its energy gain enters before saturation and filtering; velocity zero returns
silence. BASS is centered on C2 (MIDI 36), KEY/PLUCK/LEAD on C4 (60), and
HOLLOW/SWELL on C3 (48). The supported range is the center ±24 semitones.
Default finite notes last about 1.2–4.9 seconds; configured finite renders are
bounded at eight seconds. HOLD reaches loop-only export at .95.
Table selection is a patch property: moving macros never replaces a table ID
mid-note. Patch JSON preserves the table pair, layer ratio, scan biases, travel,
relationship, motion shape, filter type, envelopes, loop timing, velocity and
render-model version. Recipes replay the same dry source through any stored rack.
Structural body-harmonic weight, edge-root level and the edge's own attack-to-rest
projection are saved with the patch. Fresh voices use those settings to give the
body more overtone detail and the edge a distinct articulation. Older recipes
missing these fields decode to neutral settings, including recipes without a
configuration object; they retain their original rendered samples.

In legacy recipes the seven added controls are neutral at .5. Missing values also resolve to .5,
with explicit neutral paths retaining the original model-1 arithmetic. Older
seven-control patches need no migration. BODY changes the root/partial balance
before the filter rather than adding distortion: its root contribution spans
1.5× to .25×, while the existing partial spectrum spans .5× to 1×. EDGE reaches
.8 input level at its upper endpoint. Envelope field limits and the eight-second
finite budget bound ATTACK/DECAY. RATE retains the existing MOTION depth/rate
curve and supplies an independent clock multiplier; HOLD fits 1–16 whole motion
cycles per loop. RESO varies SVF damping from 2.5 to .4 around the authored value.
ENV adds up to two filter octaves beyond the authored amount, bounded by the
18 kHz cutoff ceiling. Settled HOLD has no attack or filter-contour gesture.

The [Luthier EDGE review](CONTOUR_EDGE_REVIEW.md) first addressed low edge sustain
with the saved `FOREGROUND` response, which blends the envelope projection toward
one across EDGE. The owner heard a useful range in Soft Digital and Bright Margin,
but reported the other seven recipes still too subtle from zero to maximum.

The corresponding fresh voices and those seven recipes now select `CONTRAST`.
The fresh PLUCK voice keeps `FOREGROUND`. Alongside the
foreground projection, EDGE reduces the body's non-root contribution toward
35%, raises edge partial amplitude toward 4×, and adds bounded support to the
body root. The edge's own fundamental is not boosted. The mix budgets the added
contributions before DRIVE. Tables, tuning, motion and the shared filter retain
their mechanisms. Soft Digital and Bright Margin keep their previous complete
recipes. These shape laws broaden the control range; listening still decides
whether the result is rounded enough.

Missing/null response fields decode to `LEGACY`; explicit `FOREGROUND` recipes
retain their previous arithmetic. Saved recipes keep that choice when macros are
edited. Model 1 and its phase seed remain unchanged. Load a fresh preset in the
updated app to use the revised response.

`./gradlew :synth:generateContourEdgeAudition` writes a focused 180-clip comparison
under `testkit/contour-edge-audition/`, with finite and held forms of all nine
recipes at five EDGE settings, matched WAVs and complete replay JSON. Its previous
side loads nine pinned complete recipes from `47809ba2d`, the preceding audition.
Each page card can play 0%, 50% and 100% in sequence; finite notes retain their
whole attack and held steps repeat for two seconds each.

Nine provisional dry recipes are Rounded Edge, Moving Corner, Dark Table,
Copper Scan, Soft Digital, Bright Margin, Twin Outline, Hollow Step and Long
Contour. Their table pairs and contours differ; the proposed 36-preset expansion
waits for the owner's sonic verdict.

R03 reauthored those nine recipes around rounded bass entrances, mellow key blooms,
a softer pluck, slower lead and hollow gestures, and an evolving held tone.
Different table pairings and bounded scan paths replace the preceding recipes'
broad overtone boost. Finite attacks span 18–110 ms, with new decay, sustain and
edge contours; the held recipe has a loop of about 3.6 seconds. Filter openings and
drive are restrained in these authored recipes. Their complete configurations
are saved as usual, so earlier saved recipes keep their previous sound.

R04 targets about 25% of R02's earlier edge and 75% of R03's rounded character.
Each compatible numeric macro and structural setting moves one quarter of the
way from the archived R03 recipe toward its matching R02 recipe. R03's table
pairs, layer relationship, motion shape and filter type stay with the patch.
The percentages describe parameter weights; listening judges the resulting character.
Finite attacks span 14.25–88 ms and the held recipe's nominal loop is 3.3 seconds.
Earlier saved recipes continue to render from their complete saved settings.

R05 responds to the limited audible change of R04 by widening the control surface
and rebuilding the nine recipes. Five body-table families replace the preceding
bank's heavy use of SOFT_SAW. Independent layer and articulation settings, octave
edge intervals in selected recipes and a band-pass hollow recipe provide more
distinct spectra and gestures while keeping modest drive. The audition isolates
each added control at 0, .5 and 1 so its effect can be judged directly; these are
rendered choices from the native engine. The nine recipes remain provisional.

`SynthKits.contour()` supplies sixteen pads: seven C-minor-pentatonic KEY notes
and the nine recipes. CONTOUR appears in the Android SYNTH picker and FRESH TAPE
starter catalog. Finite pads carry TONAL metadata; held pads carry LOOP metadata.
The capture classifier still makes its own duration/spectrum-based decision.

The CLI accepts the usual preset path with explicit note and velocity overrides:

```text
./gradlew :cli:run --args="synth CONTOUR KEY --preset 1 --midi 60 --velocity 0.4 --out /tmp/contour"
./gradlew :cli:run --args="synth CONTOUR KEY --patch /tmp/contour-patch.json --macros BODY=.8,EDGE=.6 --out /tmp/contour"
```

`--patch` replays a complete downloaded Contour patch, including its table pair,
config and velocity. `--macros` accepts comma-separated control/value pairs in
0..1; unknown, duplicate and non-finite values are rejected. `--midi` and a TUNE
macro override cannot be combined because both set pitch.

## HOLD and export

The top of HOLD exports settled sustain without the initial pluck/key attack.
Motion cycles and the edge relationship are fitted to a compatible period after
three preroll periods. Oscillator/filter states, complete consecutive periods and
the unblended seam must agree before the wrap crossfade. Fine sample-frame fitting follows the existing pitched-loop
convention rather than shifting the requested note by a coarse cycle fit.

Ordinary drum pads play their WAV once; their format does not expose a separate
attack-plus-loop region. `Keys.contourPad()` supplies two copies of the settled
region and a nonzero `loopStartFrame`, matching the existing keygroup export
convention. Repeated playback on the audition page exposes the actual wrap.
SYNTH **MAKE INSTRUMENT** and CLI `--instrument` now use `ContourPadMaker` to
export seventeen minor-third roots across the voice's supported four octaves.
Adjacent roots cover the intervening semitones; keys outside that range are
unmapped. The complete edited patch supplies tables, layer relationships,
movement, filter and excitation velocity. Each zone replaces TUNE with its
absolute MIDI pitch and forces HOLD. A five-millisecond entry fade affects only
the first copy; the repeating cycle and loop marker remain unchanged. RELEASE
sets the player's key-up fade, from 0.1 to 1.5 seconds (default 0.6).

These are captured settled tones: the finite attack, decay and transient filter
contour do not retrigger. Saved velocity authors the sampled excitation; playing
velocity controls sample amplitude through the standard single-layer instrument
path. There are no separately captured velocity layers. MPC velocity defaults
and physical playback still require a device check.

```text
./gradlew :cli:run --args="synth CONTOUR KEY --preset 1 --instrument --release .6 --out /tmp/contour-keys"
./gradlew :cli:run --args="synth CONTOUR KEY --patch /tmp/contour-patch.json --instrument --velocity .6 --macros BODY=.8 --out /tmp/contour-keys"
./gradlew :shell:generateContourStarter
```

Instrument mode rejects explicit `--midi`, `--attack`, and TUNE/HOLD macro edits
because its keyboard and settled sustain own those settings. Both MPC generations
share the exported PCM24 WAVs: a native `.xty` track sits beside its
`_[TrackData]/` folder containing the MPC 2 `.xpm` twin. The phone's
`.instrument.json` sidecar sits beside that folder. Keep each track and sidecar
with their matching folder.

The starter task packages the current nine recipes and 153 zones under
`testkit/contour-starter/`, with editable patch JSON, source fingerprints, file
hashes and a ZIP. It reads back the native JSON, XPM XML, phone sidecars and WAV
sampler sheets before packaging. Commit the source before running it so the bank
identifies its exact implementation. The Android panel groups all fourteen
controls into Tone, Envelope, Movement, Filter and Play.

The bank task also writes `web/` with nine exact root-loop previews and recipe
downloads. Copy it into an existing audition's `instruments/` directory and
refresh that audition's page to show its instrument link. The published page
includes this companion listening view; its R05 matrix audio stays unchanged.

## Audition and verification

The [published audition](https://snipsnap-contour-audition.bertcalm.chatgpt.site)
is private to the repository owner's connected ChatGPT account.

```text
./gradlew --no-daemon test
./gradlew :synth:generateContourAudition
./gradlew :synth:generateContourAudition -PcontourComparisonBaseline=/path/to/verified-original-pack
```

The generator writes a listening page, manifest and dry WAVs under
`testkit/contour-audition/`. The original matrix contains 477 cases:
9 presets, 36 note/velocity voice checks, 150 macro-sweep points, 216 interaction
points, 6 all-high corners, 36 diagnostic comparisons and 24 held loops.
R05 adds 63 control-range cases: seven controls at three values, each in three
saved recipe contexts. The bass filter-contour comparison uses Dark Table so
its lower cutoff leaves room to hear ENV. Identical neutral recordings share WAVs. Supplying an archived
previous round adds nine Previous/New pairs, bringing the comparison pack to
558 cases. The R05 comparison uses the previously published R04 voicing.
Previous WAVs remain byte-identical;
current references reuse the new preset WAVs. The comparison records each
version's complete patch, source fingerprints and audio hashes. The baseline
directory must contain `baseline.json`, its original `source/` files, and the
verified `raw/` and `matched/` WAVs. Generation refuses a mismatched source,
audio hash or level policy. An existing generated `comparison/` directory is a
valid baseline for a later regeneration.

The page opens with Control range when those recordings are available, with
control and recipe selectors and three exact rendered steps. Each recording
offers its complete patch JSON for CLI replay. Revision A/B pairs can
load Previous into A and New into B. Both use the same note, velocity and
level policy, with finite attacks played as rendered. The previous page added a
12 ms entry ramp to every clip, which softened short pluck attacks; only settled
HOLD entries now use that ramp. Stop fades remain in place.

Start with the paired recipes, then compare voices
across note and velocity. The full matrix includes five-point macro sweeps,
SCAN × OFFSET, OFFSET × MOTION, SCAN × FILTER and FILTER × DRIVE,
all-high settings, isolated body/edge and frozen/open/clean comparisons,
raw versus normalized levels, and held repeats. Feedback stays in the browser
and can be exported as JSON.

Numerical checks cover table continuity/bandwidth, root pitch, raw bounds/DC and
decay, deterministic fresh and concurrent renders, macro activity, velocity,
structural patch/recipe round-trip, kits/MPC routing, and measured loop seams.
Passing these checks establishes reproducibility and bounded behavior. Sonic
acceptance is the owner's listening decision; Android/device and MPC hardware
checks remain separate.

The first round's 24 held cases had a maximum unblended seam error of
`1.14e-13`, consecutive-period relative RMS error of `3.04e-7`, and oscillator/filter
state error of `1.79e-7`, all below the `1e-3` gates. Each round records current
loop evidence in its manifest. Every matched export stays at or below .90 peak;
the manifest reports any attenuation needed to fit a raw export into PCM.
The manifest records complete patch JSON, source/recipe fingerprints and WAV
hashes. Browser checks cover 320–1440 px layouts, A/B playback, repeated HOLD,
native audio fallback, keyboard filters and persistent feedback export.
The hosted audition uses 16-bit mono 44.1 kHz WAVs to fit the complete matrix
within the hosting archive limit. SnipSnap's kit and MPC exports remain 24-bit.

The moving-table bandwidth probe compares coherent scans against an 8× reference,
retaining intended modulation sidebands. Its worst folded-energy share is
`3.24e-9` (about −84.9 dB), below the −60 dB gate; a deliberately unbandlimited
moving control folds 59.3% and proves the measurement detects aliases. The
separate nonlinear-engine probe measures frozen high-register output and includes
an intentionally aliased native-rate control.

The added maximum-RATE check uses actual engine scan trajectories near 226 Hz,
maximum layer travel and an independent 8× reference for the 4× source path.
Its worst folded-energy share is `5.43e-9` (about −82.7 dB), below the −60 dB
gate while preserving the intended motion sidebands.

## Table and filter policy

The eight original families are ROUND_PULSE, SAW_HOLLOW, ODD_EVEN, DIGITAL_BELL,
CLUSTER, FORMANT, SOFT_SAW and COPPER. Each has 24 phase-aligned frames, 2048
samples per frame and 16 harmonic-limit levels from 1 to 256 partials. Pitch
crossfades use two levels that both stay below 18 kHz, leaving room for slow scan
sidebands before the shared output low-pass and decimator.

One gain per family sets the maximum absolute sample across every frame and
pitch level to .90. No per-frame normalization erases the intended dynamics;
the fixed fundamental and continuous interpolation retain the tonal body.
The bank is immutable and shared; all sounding state stays local to a render.
The layer voicing acts after lookup: body harmonic weighting leaves its root
unchanged, while edge-root attenuation makes room for the edge's overtone path.
Both use the same interpolated normalized root component as the table bank.
Neutral settings bypass this projection and reproduce the original lookup
exactly. The bounded edge envelope acts before the shared filter and converges
to its sustain value for HOLD.

The filter is the existing generic TPT state-variable filter with restrained
damping, followed by the shared output bandwidth conditioning. HOLLOW recipes
may blend its low-pass and band-pass outputs. DRIVE uses bounded tanh excitation
before the filter at 176.4 kHz. Raw internal probes run before output conditioning
and loudness makeup, so the final gain stage cannot conceal unstable filter state.
