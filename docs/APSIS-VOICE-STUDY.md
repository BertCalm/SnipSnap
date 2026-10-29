# Apsis voice identities

Status: audition prototypes, 2026-10-28. Five new candidates and a common-harness
PASSAGE reference. Nothing in this round has been heard by the implementing
session. Names and acoustic constants are provisional musical shape choices.

Repository truth: SnipSnap `df675d00426486ce301c7a742b4a7691baf30af8`.
`Apsis.kt`, `ApsisOrbit.kt`, `Dsp.seedFor`, `Dsp.Noise`, `Dsp.OVERSAMPLE`,
`Dsp.MELODIC_LOUDNESS_TARGET`, `Gyre.kt`, `Corolla.kt`, `Nimbus.kt`,
`Pitchwheel.kt`, `Undertow.kt`, and the Luthier conventions were read or grepped.

The characteristic gesture is **approach → encounter → a voice-specific response
→ darker release or recurring return**.

## What this round builds

One diagnostic identity per candidate, not a 36-preset bank. All six use the same
orbital control geometry and a damped modal network. A different excitation law,
modal table, loss law and radiation balance establish each identity. The work is
Python/Numba adapted from Luthier's `proto_template.py`; it does not add voices to
Android or change any saved Apsis model. The study's PASSAGE is an approximation
for comparison in the same harness. The existing Kotlin PASSAGE bank remains
available separately on the listening page.

| Candidate | Gesture | Excitation and body | Intended pad role |
|---|---|---|---|
| PASSAGE reference | approach, bright passage, darker reply | powered root, narrow encounter, short upper responses | moving keys |
| TETHER | catch, pluck, release | short contact envelope, harmonic bank, upper modes die first | plucked keys |
| VESSEL | tap, ring, beat, settle | pitch-scaled mallet pulse, nearly harmonic vessel modes, long upper memory | chiming notes |
| CORONA | open, breathe, close | broad contact, harmonic formant clusters and low-level turbulence | airy sustained keys |
| GRAIN | gather, scrape, scatter | eleven raised asperities per revolution, bounded noise contacts, short material losses | pitched texture |
| ANCHOR | charge, release, recover | bounded pressure reservoir, odd-harmonic release, low root support | bass and hollow stabs |

CORONA's bands select nearby harmonics with a register floor. It is not an
articulatory vocal model. TETHER is a modal plucked approximation, not a waveguide
string. VESSEL uses imaginary near-harmonic ratios, not measured vessel data.
GRAIN synthesizes noise contacts without captured samples. ANCHOR's pressure is
an analogy governing excitation; it is not a fluid simulation.

## Three directions considered

1. **Strong, SPLICE:** distinct exciters and loss profiles on the shared orbital
   network. Gesture clarity high; novelty is within APSIS; bounded risk; one
   audition round. This exposes differences in attack, persistence and register
   while keeping the orbital gesture recognizable.
2. **Alternate A, STACK:** different processing chains after PASSAGE. Gesture
   clarity medium; low roster novelty; low DSP risk; cheapest round. It changes
   the finish more than how the instrument responds to an encounter.
3. **Alternate B, COUPLE:** six interacting physical submodels. Gesture clarity
   uncertain; potentially high novelty; high stability and rendering risk;
   several build rounds. This would obscure which voice contributes what before
   the individual identities have been judged.

Nearest siblings: GYRE already has shared strings and a rotor; NIMBUS already has
moving pressure over modal plates. Their existing gestures justify this landing
as voices within APSIS. The shared Kepler encounter clock and common-reference
audition are the useful distinction. This round proposes no additional engine.

## Signal flow and bounds

```text
STRETCH / DISTANCE / ALIGN / finite length or held period
     ↓
Kepler geometry and contact fields [cache: these controls, duration, rate, held]
     ↓ control interpolation
voice exciter + native velocity + fractional BODIES allocation
     ↓ bounded forcing at 176.4 kHz
damped modal rotations ↔ orthogonal upper-port exchange [COUPLE]
     ↓
radiation weights → 20 kHz band limit → decimate → mean / 20 Hz DC removal
     ↓
common raw monitor gain OR declared comparison gain → mono 44.1 kHz PCM16 WAV
```

The ellipse timing runs Kepler's equation, with a bounded fourteen-iteration
solve and checked residual. The common period ratios are 1, 1.2, 1.5, 2, 3 and 6.
The Python geometry reads normalized distance and angular contact fields; it
does not reproduce every production absorption and contact-scheduling law.

Each free modal update is a rotation times `rho < 1`. Each coupling junction is
a proper two-coordinate rotation. Thus unforced modal energy cannot increase,
independently of the trajectory. Root coordinates are outside the upper-port
exchange. ANCHOR's reservoir update is a convex combination of its previous state
and a target in [0,1], so it also remains bounded. Forcing, listener weights and
matching gains are separate. No limiter participates in the stability argument.
The tests inject a growing free pole and confirm that the energy-rise probe
detects it.

Noise uses the integer recurrence in `Dsp.Noise` and string/integer seed hashing
equivalent to `Dsp.seedFor`. Arithmetic and phase initialization differ from
Kotlin; no claim of production audio identity is made.

## Macro contract

| Control | Default | Changes | Required interaction / check |
|---|---:|---|---|
| STRETCH | .65 | orbital eccentricity and encounter dwell | with DISTANCE, changes contact density; low/middle waveform probe |
| DISTANCE | .40 | common inner period, .6–2 seconds | changes timing without changing the requested root |
| BODIES | .40 | continuous participation over six stable identities | total allocation uses square-root population normalization |
| ALIGN | .55 | approach phases and orientations | changes coincidence without replacing body identities |
| COUPLE | .45 | orthogonal exchange rate through upper ports | responds to encounter proximity; low/middle activity probe |
| HOLD | 0 | finite source duration and tail; .99+ requests a settled loop | finite duration increases; loop independently converges |

ROOT is separate MIDI 36–84, and native velocity is separate 0–1. All candidate
comparisons use the same defaults. Velocity changes the actual contact, spectral
weights or pressure release. Controlled touch and macro pairs use frozen gains.

## Audition and evidence

The 66 clips comprise six common C4 identities, six identical three-note scores,
twelve range checks, twelve native-touch checks, twenty-four STRETCH/COUPLE takes,
and six held cycles. Scores use C3, G3 and E-flat3 at 0, 1.8 and 3.6 seconds, with
native velocities .65, .45 and .85. Full six-second tails are retained.

Identity, phrase, range and held clips independently target loudest-200ms RMS .12,
subject to peak .85. Touch and control families use one gain per voice and family,
referenced to the baseline C4 take and limited by the family's largest peak.
Raw clips all use one common monitor gain. The source metadata records both
gains. These are prototype listening policies, not Kotlin perceived-loudness
normalization or measured MPC playback gain.

Held output fits the common period to the sample and root clocks, repeats the
external noise realization, and prerolls real state until its complete modal,
contact and reservoir boundary state converges. The kept output and a real
preceding cycle pass through the output filters together. There is no seam
crossfade. This is a driven repeating adaptation; it does not promise an attack
prefix or native MPC program for these candidates.

`testkit/apsis-voices/reports/validation.json` records the generator checks,
spectral and envelope distances, complete-cycle recurrence, timings and exact
source hash. `readback-and-controls.json` records PCM readback and separate
low/middle control probes. The bundled pitch helper had an incorrect second
prefix-energy term; this study uses a corrected NSDF checked against independent
tones and noise. That skill-helper correction has not been applied globally.

## Measured in this round

The final generated baseline C2/C4/C6 set stays within 24.44 cents of the requested
notes. All six identities reproduce sample-for-sample after other renders. The
largest held seam error is below .000170; the largest relative adjacent-cycle
error is below .000079. These are numerical checks, not a listening verdict.
Seven focused Python tests pass, including an intentionally unstable free pole.
All 132 PCM files pass independent readback, hash, format, peak and DC checks;
the largest absolute decoded DC is below .0000014. Every voice's five movement
and exchange controls changes its level-independent waveform between .15 and
.50, and finite HOLD increases duration. Separate results accompany the download.

The common C4 spectral total-variation distance is at least .0658 across the
fifteen pairs. Distances establish measurable differences, not whether a new
voice earns a place. Listen especially to PASSAGE versus TETHER: their attacks
and releases should justify two identities even where their spectra overlap.

## Cost, risks and next gate

Geometry is cached independently of voice, note, native velocity, population and
coupling. The cache retains six immutable entries, each under roughly 3.2 MB for
the allowed duration range. Audio and modal state are never cached. Desktop
Python/Numba timings are attached; they do not establish phone latency or the
house target of tens of milliseconds after a knob move. A Kotlin implementation
should reuse the existing prepared-motion infrastructure and profile before
claiming that target.

The higher-rate waveform comparison includes event sampling and output
resampling, not alias power alone. Relative errors at the C6 bright corner are
1.38–1.92% across the four deterministic tonal profiles. This includes a corrected
per-second excitation scale when doubling the internal rate. Noise-profile reference comparisons are
excluded because the sample sequences differ with internal rate. Production
aliasing qualification, Kotlin patch/model compatibility, Android registration,
full application regression tests, native exports and physical listening are
future implementation gates. This round changes no production Kotlin files.

| Claim | Status | Evidence |
|---|---|---|
| Current Android Apsis has PASSAGE only | verified | `ApsisVoice` at the repository base above |
| Five new response laws can be compared | implemented prototype | source, 66 clips and recipes |
| Pitch, deterministic replay, passive decay and held recurrence | measured | generator, focused tests and JSON reports |
| Six useful, distinctive musical identities | proposed | owner audition pending |
| These candidates sound good | not heard | no claim |
| Six production Android voices or 36 approved presets | not implemented in this round | existing app remains the PASSAGE implementation |

## Reproduce

```sh
python3 -m pip install numpy scipy numba
python3 scripts/apsis_voice_study.py testkit/apsis-voices
python3 -m unittest discover -s scripts -p test_apsis_voice_study.py -v
python3 scripts/verify_apsis_voice_study.py testkit/apsis-voices
```

## Why it is built this way

- A common orbit and the same notes make differences attributable to the voice.
- Excitation and loss determine how the instrument begins and remembers a contact.
  Giving each candidate its own laws makes attack and release part of its identity.
- An independent root anchor keeps richer materials playable across the keyboard.
- A passive shared network makes numerical safety reusable across the family.
- One diagnostic identity per voice leaves the preset decisions until after listening.
