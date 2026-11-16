# East African fitted synthesis kits

Five curated starter banks for Bongo Flava (100 BPM), Singeli (200), Bongopiano
(112), Genge (96) and Kapuka (108). Each has 22 pads, three velocity zones and
three deterministic alternate strikes: 990 variants including hand rolls and
shaker risers. These are listening arrangements, not definitions of the genres.

The owner accepted the fitted Python revision on 2026-11-15: “It's much better.
Proceed.” That approval belongs to the Python bank. Kotlin listening and
phone/MPC hardware approval are separate gates. The earlier TIMBER hardware
approval does not apply here.

## Sound and landing

The characteristic gesture is a finite strike, a fitted transient, decaying
resonances and a noise tail. Modal and noise-envelope parameters were fitted
offline to CC0 recordings, then frozen. Runtime reads scalar JSON parameters
and generates oscillators and fresh seeded noise. There are no sampled attacks,
recorded residuals, impulse responses, spectral frames or source WAV resources.
The two log basses use FM. Exported WAVs are synthesized output.

SKIN and TERRA are the nearest general drum mechanisms. Their stock voices do
not reproduce these measured per-velocity envelopes and resonances. The landing
is a curated starter family, like TIMBER and the fitted SPORE bank, rather than
another Patch engine or SYNTH picker entry. There are no new macro promises.

Core roles: two kicks, snare, rimshot, rim, closed/open hats, shaker, low/high
bongos, conga, bell and two logs. A hand roll and shaker riser occupy pads 15/16;
muted tap, open conga, clave, wood knock and low/high metal occupy 17–22.
The first 16 pads stay in bank A, the six colors continue into bank B.

Each genre has its approved tuning/damping and tempo. Shelf balances additionally
emphasize the arrangement's roles: hand drums in Bongo Flava; tight kick, rim,
wood and metal in Singeli; shaker, conga and logs in Bongopiano; kick/backbeat in
Genge; and kick, shaker, bongos and clave in Kapuka. These shelf balances are new
native choices. The comparison audition preserves the approved event scores
and gains so that it isolates the renderer change. Hats share a choke group.

## Native transfer

`EastAfricaModel` preserves the fitted modes, per-zone noise envelopes, fixed
calibration gains, take offsets, 4× rate, 18 kHz eighth-order low-pass, 81-tap
Kaiser decimator, 22 Hz high-pass and fixed bank gain. It does not normalize each
hit. Fitted filters are stable Butterworth SOS in double precision.

The Python prototype used NumPy PCG64 normal noise. Native code uses `Dsp.seedFor`
and `Dsp.Noise`, transformed to Gaussian noise with Box–Muller. Thus the noise
statistics and envelopes transfer, but noise samples are intentionally different.
Do not claim byte identity to the Python audition. Native renders must replay
byte for byte against themselves. Model version 1 pins this native revision.
The six-entry noise cache is bounded and shared across velocity layers; it holds
procedural carriers only, never recordings. Filter coefficients and tiny scalar
take offsets are prepared offline, so no solver runs when building a kit.

Source provenance: Virtuosity Drums, Versilian Studios/Austin McMahon; VSCO CE,
Sam Gossner/Simon Dalzell, edits by Elan Hickler. CC0 notices and source hashes
are in `scripts/east_africa`. The original recorded bank's derived dynamic zones
remain derived calibration targets, not claims of independently recorded layers.
User-supplied music clips guide the arrangements only and are not kit sources.

## Replay and verification

Baseline repository inspected: `f4a3ad59d00d53f71d0b481b35c01b2a1fa66b2a`.
Integration merged current base `508f62437a63c9df37b2df3827fbda3310ddb613`
(HICCUP and the DRUMLINE audit) without changing this synthesis model.
Earlier Python study inspected `494af88e6545f8ee03ced33cf2dad4598fc67248`.

```sh
# Offline coefficient preparation, needs NumPy and SciPy, no source audio.
python3 scripts/east_africa/prepare_native.py

# Every variant, replay, dynamics, saving, playback, MPC3 and MPC2 metadata/PCM.
./gradlew --no-daemon :synth:test --tests '*EastAfricaKitsTest' \
  :shell:test --tests '*EastAfricaRoundTripTest' --tests '*StarterShelfTest'

# Native banks, MPC3 and MPC2 folders, and exported-chain audition windows.
./gradlew --no-daemon :shell:generateEastAfricaKits
python3 scripts/east_africa/audition_native.py testkit/east-africa-kits

# Optional comparison to the retained approved Python bank.
python3 scripts/east_africa/audition_native.py testkit/east-africa-kits \
  /workspace/east-africa-fitted/audio

./gradlew --no-daemon test
```

The Python model and frozen Python reports are retained separately. Their
measurements are historical Python evidence, not Kotlin test results. The
native generator writes `verification.csv`; the audition script writes
`port-comparison.json`. Spectral-band and time-window distances are engineering
comparisons, not perceptual realism scores.

`EastAfricaRoundTripTest` checks the shelf, saved kit, actual chain PCM, two
round-robin cycles, MPC3 zone/cycle fields and exported PCM, MPC2's first-take
velocity windows, and SFZ import with playback at all 127 nonzero velocities.
`EastAfricaKitsTest` checks all 990 variants, source replay, level ordering,
gain-matched timbral differences, distinct takes, bounds, DC and tails. Source
identity is versioned and validated instead of silently accepting incomplete
metadata. There is no automatic migration from the earlier sampled studies.

MPC2 keeps each zone's first take. The owner's combined velocity/round-robin
format exception applies. MPC3 software read-back does not settle the shared
on-device chain-WAV slice-map question. Hardware acceptance remains open.

## Measured native results

- 990 variants: sample peak 0.918877, estimated 4× true peak 0.919462,
  maximum absolute DC 0.000019381, final 10 ms RMS at most 0.00056654.
- Minimum gain-matched shape residual: soft/hard −26.11 dB, alternate takes
  −43.20 dB. Lower residual means closer shapes; neither test accepts copies.
- Maximum same-zone take loudness spread: 2.65 dB; the Python bank reached 2.46 dB.
- Against the accepted Python bank's 900 single hits: mean spectral-band
  distance 1.011 dB (95th percentile 2.023), mean time-energy distance 0.529 dB
  (95th percentile 1.315). These normalized features are not perceptual scores.
  Mean absolute RMS-level difference is 0.390 dB. The largest is the medium
  Singeli closed-hat's third take, 3.222 dB quieter than its Python counterpart.
- Stationary noise-filter energy above output Nyquist: at most 0.00002164
  of total energy (−46.65 dB). This is not full transient alias certification.
- Full bank render times on this host: Bongo Flava 14.72 s, Singeli 8.75 s,
  Bongopiano 18.89 s, Genge 10.41 s, Kapuka 12.32 s. These include 198 variants
  and phrase assembly, not phone timing. Playback uses the rendered PCM.
- Native C++ source audit and all eight CTest suites passed. Focused JVM runs
  passed two synthesis tests (all 990 variants), the five-kit round-trip test
  and three shelf checks. Full regression is tracked separately below.

MPC3 download ZIP parts reconstruct the unchanged exported files when extracted
into one directory. `package_mpc3.py` verifies every packaged byte against the
export; splitting accommodates the Page's per-file upload limit.

## Acceptance ledger

- Python fitted bank listening: owner-approved, 2026-11-15.
- Native Kotlin listening: open; fresh noise needs a native audition.
- Native rendering, persistence, playback and export: focused JVM checks passed.
  All 990 strikes replay identically; all five kits pass native/MPC3 PCM and
  cycle read-back, MPC2 first-take windows, and SFZ imported playback. SFZ gain
  preserves its existing 0.001 dB text precision rather than bit identity.
- Repository regression: tracked in the delivery PR checks. The first local full
  run exposed the two README starter counts (158 → 163); both were corrected. After merging HICCUP, the combined count is 166.
  That first run lost its execution session before a completion status was
  available, so it is not claimed as a full-regression pass.
  There is no Android SDK in this workspace; Android build/emulator evidence
  comes from CI, not the local JVM command.
- Phone and MPC hardware: open.
- Melodic/keygroup/chord and string STRUM gates: not applicable to finite drum banks.
- HOLD: not applicable; rolls and risers are finite authored phrases with tails.

## Why it is built this way

- Fitted resonances retain the accepted drum bodies; generic drum presets lost that character.
- Separate envelopes per velocity zone change the strike's spectrum and decay, not just volume.
- Fresh filtered noise gives hats, wires and shakers their texture without replaying recordings.
- Fixed gain preserves the kit's dynamics; normalizing each strike would flatten them.
- Versioned source identity and complete grids let save/export checks follow every strike.
  Changing the random generator is the principal transfer risk, so native listening stays open.
