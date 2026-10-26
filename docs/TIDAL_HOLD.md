# Tidal HOLD: as built

Repository read: `fd791f58`, 2026-10-26. This extends the merged finite instrument.
The accepted finite source models and six finite factory presets stay intact.
The characteristic gesture is gather → encounter → moving aftermath → release.

## Mechanism

```
complete TidalPatch
    → cached restricted encounter → the shared 240 Hz source/cohort readout
    → post-passage descriptor window → smooth periodic traversal
    → whole-turn carrier fitting → continuously rendered neighboring cycles
    → 4× band limit / decimate / 8 Hz DC control / common mix leveling
    → aftermath pad loop OR preserved finite opening + join + keygroup sustain
```

The solver remains literal softened restricted dynamics. HOLD is a musical
reconstruction, not an indefinitely evolved or cyclic gravitational system.
The field reads a 1.4-second window selected from the post-passage tail allocation,
before the original source supply fades. A cosine traversal closes position and
velocity at both turns. Population identity, core/tail allocation, source amplitudes,
frequency motion and contact phase all come from the shared finite readout.
Model-1 and model-2 source characters remain selectable.

The selected field lasts 3.8–4.2 seconds. The planner searches sample lengths on a
64-frame grid and fits the fundamental within 0.1 cent. Each other carrier keeps
its time-varying frequency plus a constant correction of at most `0.5 / seconds`
Hz. Thus inharmonic metal is not collapsed to root harmonics. Half-sample frequency
integration closes the discrete phase sum. Zero-speed descriptor turns avoid a
control reset. All oscillators keep running across the inspected cycles. Complete turns are
counted as integers alongside the fractional oscillator phase; accumulating a
large floating phase total can otherwise falsely reject valid high-note fields.

No feedback is added. Amplitudes are bounded interpolations of the finite field's
source allocation and damped modal amplitudes. The acoustic renderer does not
feed energy into the trajectory. Its raw coherent sum is checked below 0.96
before leveling. The source's frequency guard and 4× conditioning remain in use.
The loop marker and exported end receive no fade or seam repair.

The finite opening is retained sample for sample up to the join. A smooth 350 ms
join blends it into phase-aligned reconstruction. The sustain marker follows an
entire reconstructed field, so the preceding seam context contains no join.
Real preceding, kept and following cycles pass independently through continuous
oscillator and filter state. Tests check phase closure, full-cycle relative RMS
error and `Keys.seamError` against the real preceding cycle.

## Design choice

The strong direction is reconstruction from the existing cohort controls: it
keeps motion and source identity with bounded work. A static partial freeze would
be cheaper but discard evolving strand structure. A crossfaded slice of the
finite WAV would retain the recording but make pitch and stretched-metal closure
depend on an arbitrary cut. HOLD adds a mode to Tidal, not another engine. FLOCK's
captured swarm and ORIGAMI's periodic spectral field are the nearest implementation
precedents; neither carries Tidal's original-population encounter readout.

## Saved and played

`HOLD` is an explicit 0/1 switch. Fractional or non-finite values fail. Held patch
JSON requires `heldModel = 1`; finite JSON remains compatible. Source model, exact
signed Long seed, MIDI, velocity and all five encounter controls survive editing,
saving, drafts and pad reopening. HOLD pads are gated loops of the aftermath;
NOTE pads play the full finite performance once. A pad loop is cyclically rotated
to its smallest-magnitude sample before delivery, avoiding a large trigger step.
No sample is faded or reshaped, and the instrument's opening and loop marker are unchanged.

`TidalKeys.note` retains the opening before its nonzero loop marker. The shell
maker exports seventeen three-semitone zones across MIDI 36–84, with 0.1–1.5 second
note-off release. Saved velocity controls the sampled source excitation; keyboard
velocity scales playback rather than synthesizing a new physical encounter.
Source recipes and the held version are saved beside the package.

Preparation remains bounded to eight settings. The existing 8 MiB completed-audio
cache includes HOLD, both versions of the source, note, velocity, seed and all
controls. Cache hits return owned copies. New held edits reconstruct a fresh
field; they are not instant. Instrument zones render serially so a phone holds
one oversampled working field at a time. Cancellation is polled during preparation,
readout, every 256-sample synthesis block and conditioning boundaries.

## Evidence and limits

Verified: all twelve frozen raw and normalized finite references remain exact
following extraction of the shared readout. Kotlin HOLD tests cover both voices,
both source models, MIDI 36/48/60/72/84, exact Long seed replay, silence, cache
ownership, mid-render cancellation, preserved opening, root fit and independent
neighboring cycles. The audition generator writes six model-2 examples and all
associated recipes/measurements.

Measured in the initial Java 17 listening run (other build work was active;
these are observations, not a controlled benchmark): six complete held notes
rendered in 2.53–3.72 seconds. Maximum absolute fitted root error was 0.00651 cents.
Full neighboring-cycle relative RMS errors were 2.62e-8–9.52e-8. The raw JSON report
is generated with the audio and retained in
[the measured evidence](benchmarks/tidal-hold/audition-measurements.json); no
threshold is based on elapsed time. Both seventeen-zone exported instruments
also pass explicit MPC 2, MPC 3, phone-zone and WAV sampler-marker checks.
Their maximum PCM24 seam errors are 4.54e-14 (PAIR) and 3.07e-14 (STREAM);
[export evidence](benchmarks/tidal-hold/export-verification.json) records the archives.

Proposed shape constants: descriptor window selection, 1.4-second travel,
roughly four-second field and 350 ms join. No new presets are claimed as heard.
The moving control window is repeatable, so sustained listening can reveal its
period. Physical phone latency, memory and playback, MPC hardware loading, the
join's musical usefulness and the held source's character require listening.
Eighteen actual exported-audio pitch windows across the six default examples
remain within 5.17 cents, with minimum confidence 0.873. Eight additional
hard-played edge probes cover both voices at MIDI 36/84, full velocity, exact
Long.MAX_VALUE seed, minimum BIND and maximum TAIL. All pass closure, finite
output, peak and DC checks. Their maximum pad-entry magnitude is 2.42e-5; the
six default examples stay below 6.90e-6 after rotation. The six instrument
demonstrations remain byte-identical after pad-entry rotation, and all twelve
WAVs remain exact after changing phase-state measurement to integer turn counts.
[Final field evidence](benchmarks/tidal-hold/held-field-validation.json),
[pitch windows](benchmarks/tidal-hold/pitch-probes.json) and
[edge probes](benchmarks/tidal-hold/edge-probes.json) retain the measurements.
Seam certification covers the dry source and exported instruments; subsequent
rack processing has its own boundaries. Numerical closure does not establish
musical acceptance.

## Reproduce

```
./gradlew :synth:test --tests '*Tidal*Test' :shell:test --tests '*Tidal*Test'
./gradlew :synth:generateTidalHoldAudition -PtidalHoldOutput=/tmp/tidal-hold-audition
./gradlew :cli:run --args='synth TIDAL STREAM --macros HOLD=1 --out /tmp/tidal-held-pad'
./gradlew :cli:run --args='synth TIDAL STREAM --instrument --release 0.7 --out /tmp/tidal-held-keys'
```

The audition contains encounter/hold/release demonstrations and eight consecutive
unfaded held cycles at MIDI 36, 60 and 84 for both voices. Its release demonstration
uses an explicit 0.7-second linear fade; the phone and MPC instrument players own
the actual note-off envelope. The original source audition stays available.

## Why it is built this way

- Reusing the source readout makes the sustained material belong to the encounter.
  A second independent voicing would drift away from the sound already accepted.
- Fitting each carrier's accumulated phase preserves stretched metal while closing
  the field. Forcing every partial onto harmonic ratios would change its material.
- Keeping the finite opening preserves the event that created the aftermath.
  Pads omit that opening deliberately; instruments retain it before their marker.
- Continuous neighboring cycles expose oscillator or filter-state mismatches.
  Copying one buffer twice could not prove those states actually close.
- The main musical risk is the repeating control traversal. The eight-cycle
  audition makes that repetition available for judgment without hiding its wraps.
