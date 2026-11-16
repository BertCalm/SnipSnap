# Bristle bass study, round 0

Status: Python audition prototype. No Kotlin, app picker, Oboe engine or factory preset changes.
The characteristic gesture is blunt onset → buzzing bass body → darkening sustain → tight stop.
Reference: the owner's target is the bass character of Dead Prez's *Hip Hop*. No recording was
analyzed or sampled; these clips are hypotheses for owner listening, not an authenticated recreation.

## Reproduce

From the repository root, with Python 3, NumPy, SciPy and Matplotlib installed:

```sh
python3 prototype/bristle/bristle_proto.py /workspace/bristle-audition
```

Open `listen.html` in that output directory. It embeds every audio clip, so it can be moved alone.
Start with A_phrase and B_phrase, then compare the C1/C2/C3 notes. The phrase is newly authored
for this study, not a transcription. All clips use matched RMS; this is not equal perceived loudness.
`report.json` includes recipes, WAV hashes, measurements and the source commit.

This script began from `.claude/skills/luthier/scripts/proto_template.py`, replacing its modal
renderer and replacing its illustrative modal HOLD loop with finite bass notes and a phrase.
It imports that bundle's `snipkit` output stage and `probes`. The resource checker passed.

## Repository truth

Read at `23cde0d1c107039c23fd74ff5c3d2b359a7f6e75`:

- `Fathom.kt`: DEEP, GRIND, GLASS; drive before TPT low-pass; GRIND has two detuned saws.
  FATHOM already has upward baked glide. Its current drum classification is not automatically
  appropriate for a new melodic voice.
- `Dsp.kt`: `TptSvf.process`, `seedFor`, `OVERSAMPLE`, `decimate`, `levelTo`, `fadeTail` verified.
  The prototype follows the unsaturated TPT recurrence in double precision; it is not a
  byte-identical Kotlin port. No randomness is used.
- `Keel.kt`: BELLY already mixes a sine-derived body and PolyBLEP saw, with 9-cent detune and
  a coordinated settling envelope. This is a closer architectural sibling than the older
  inventory used in the first conversation suggested. A KEEL voice/recipe extension deserves
  comparison before deciding the final landing. FATHOM remains a candidate for its drive path.
- `Velvet.kt`: general subtractive BASS sibling. The roster already serves bass; no new engine
  is justified by this experiment alone.

## Signal flow and voicing

```text
Shared pitch + finite slide → PolyBLEP saw → [A: tanh → TPT low-pass] ─┐
                                          [B: TPT low-pass → tanh]  ├→ amp → output
Shared phase → sine at the SAME fundamental ─────────────────────────┘
Output: 4× raw → 20 kHz band limit → decimate → DC removal → RMS match → tail fade
```

The alternative source clips use a pulse plus sine, a saturated sine alone, or a saw alone.
Sine polarity is aligned to the rising saw's fundamental before filtering. Filtering changes
relative phase, so this is not a promise of constructive addition at every cutoff.
Filter damping is fixed at 1.2; no self-oscillation or internal limiting. The cutoff and
coefficients follow a smooth envelope. A is the preferred hypothesis because its final filter
controls the newly created harmonics; B tests the owner's submitted ordering directly.
The supplied 650 Hz cutoff / 4.5 drive is inspiration, not a numerical calibration target.

All voicing values are provisional shapes. These controls are Python study parameters:

| Control | Default | Model quantity | Evidence |
| --- | ---: | --- | --- |
| TUNE | .5 | C1–C3, C2 centre; audition passes explicit MIDI | Three notes, pitch/confidence |
| WEIGHT | .55 | Clean fundamental gain, 0–.9 | 0/1 and .25/.5 comparisons; WEIGHT × GRIT corners |
| GRIT | .55 | tanh drive, 1–12 | Spectral distances; both filter orders |
| DARK | .55 | Cutoff, 2400 down to 180 Hz at C2, 60% key tracking | Spectral distances; DARK × BITE corners |
| BITE | .25 | Initial filter opening, 0–2 octaves, 65 ms time constant | Spectral distances and corners |
| SLIDE | 0 | Starts 0–12 semitones below target, arrives in 120 ms | Post-slide pitch plus phrase |
| HOLD | .5 | Gate length .12–.72 s, then 45 ms release | Finite-note duration comparisons |

MIDI is an explicit render argument, as in the original template; TUNE is its proposed UI mapping,
not a second independent pitch input. Each one-shot buffer is one second including silence.
The note uses a 4 ms cosine onset and a gentle .95 s exponential droop during its gate.
HOLD is finite duration here, not a seamless loop. The macro shape is six controls plus HOLD.

## Verification and limits

Round-0 Python result: 25 WAVs, 32 bounded control corners, maximum measured note error
0.94 cents and minimum pitch confidence 0.999. Audition-note sample peaks were at most
0.417; the largest pre-output corner peak was 1.577. The 16× reference difference was
-48.4 dB for A and -62.1 dB for B at the tested bright, driven C3 corner. These numbers
do not establish which route resembles the recording. Median Python render plus output
conditioning was 458 ms for a one-second buffer, above the phone playability target;
Kotlin/phone cost has not been measured. Exact values and control distances are in `report.json`.

The generator asserts finite/bounded output, DC, post-slide root pitch, pitch confidence,
repeatability, NaN fallback, ignored unknown keys, and activity at extremes and low/middle
settings. It sweeps 32 route/weight/grit/dark/bite corners before output normalization.
An independent filter-decay test removes excitation and checks its late energy, including
high-cutoff/low-damping settings. It does not use an output limiter to hide growth.

The 16× versus 4× comparison measures total rate-dependent error, not pure aliasing: oscillator
band limiting and filter discretization contribute too. The raw high-frequency energy probe
alone cannot prove freedom from aliasing. No strict alias audibility gate is asserted.

The template pitch detector initially tried to measure silence after the shortest note.
The study now places its window inside the actual gate; it retains a post-slide window for
longer notes. This is a measurement-window correction, not a pitch fix.

The toolkit approximates the Kotlin decimator and uses RMS instead of `Loudness.of`. Reported
render timings are Python on this workspace, not JVM or phone benchmarks. Target for a later
Kotlin one-shot is tens of milliseconds, to be measured on-device. This model is O(samples)
with fixed filter state, no solver or geometry, and no need for a precompute cache.

Acceptance gates remain open: installed voice/presets, playable keygroups, starter kit, chord
compatibility, appropriate sustained HOLD, timbral velocity and round robin through rendering,
saving, playback and export, and phone/MPC listening. This round is one timbre with two routing
choices; those choices do not count as two completed musical voices. STRUM and drum genre kits
are not applicable to this bass study. No factory samples or heard presets were changed.

## Why it is built this way

- A clean fundamental can preserve bass weight while distortion shapes the upper harmonics.
- The two filter orders isolate the largest uncertainty in the submitted design.
- A stable TPT filter avoids the original recurrence's high-cutoff instability.
- Oversampling and output conditioning reduce nonlinear foldback and keep files usable.
- Phase cancellation and the reference's exact articulation remain listening risks; source and
  slide comparisons expose them before committing to an engine integration.

## Owner acceptance and Kotlin round 1

After this report was generated, the owner accepted both routes: “I like both versions.”
They then authorized the Kotlin prototype and velocity/notes round. See `docs/BRISTLE.md`
for the implementation, measured source preservation and remaining integration gates.
`kotlin_audition.py` packages actual JVM output; it does not substitute Python audio for it.
