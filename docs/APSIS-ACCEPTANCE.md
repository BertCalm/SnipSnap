# GRAIN and ANCHOR MPC acceptance bank

`SnipSnap Apsis Duo` is a download-only acceptance bank for the six existing
GRAIN and ANCHOR presets. It uses their current native Kotlin renderer without
changing the engine, factory presets or original PASSAGE bank.

| Pads | Contents |
| --- | --- |
| A01–A04 | Grain Passage, MIDI 60/63/67/70 |
| A05–A08 | Anchor Bass, MIDI 48/51/55/58 |
| A09/A10 | Scattered Grains C4 / Hollow Surge C3 |
| A11/A12 | Grain Passage C3 / Anchor Bass C4 |
| A13/A14 | Exact copies of A01/A05, on independent pads |
| A15/A16 | Held Grains C4 / Held Anchor C3 |

Every pad has three native excitation layers: MIDI velocities 1–41 select `.35`,
42–84 select `.65`, and 85–127 select `1.0`. The middle layer preserves the
factory touch reference. All 48 WAVs are dry mono 44.1 kHz PCM24, with native
note renders and SMPL root metadata. Pads have level 1 and separate mute groups.
The download includes MPC 3 `.xtd`, MPC 2 `.xpm`, editable kit data, full recipes,
sample checksums and an acceptance checklist. Copy the whole extracted folder;
the MPC 2 program lives inside the adjacent `_[TrackData]` folder.

## Listening and physical acceptance

The six scores play the actual exported WAVs, reopened and checked against their
SHA-256 hashes. They cover an 80 BPM note walk, both held voices, each voice's
three native touch layers, and same-pad restart versus alternating copy pads.
The primary performance uses MIDI 127 and unity pad gain. Touch inspections
select the three sample layers at unity gain; they do not predict the hardware's
additional velocity-to-amplitude curve. Raw scores use a fixed `.35` monitor
gain. Matched scores apply one gain to the entire file, targeting the loudest
200 ms RMS window at `.12` with a `.85` peak ceiling. Attacks are never matched
individually.

Finite same-pad retriggers choke the earlier sample. Alternating A01/A13 or
A05/A14 lets the earlier tail continue. Held pads repeat the full settled buffer,
with no onset prefix and release control 0. The score player implements these
documented gates; a physical MPC still needs to confirm their audible behavior.

On the MPC, check native note walks, soft/normal/hard touch, same-pad retrigger,
alternating-pad overlap, held joins across several cycles, and held release and
retrigger. Record unexpected clicks, layer jumps and tail cuts with pad names.
Hardware velocity scaling, actual release response and phone render latency
remain unmeasured. Automated waveform decoding is not a listening verdict.

## Reproduce

```sh
./gradlew :synth:test --tests com.snipsnap.synth.ApsisAcceptanceTest --tests com.snipsnap.synth.ApsisKitTest
./gradlew :synth:generateApsisAcceptance
python3 scripts/prepare_apsis_acceptance_site.py --site /path/to/opened/site
```

The generator defaults to ignored `testkit/apsis-acceptance`; override with
`-PapsisAcceptanceOutput=/path`. Scores and raw/matched audio are beside the ZIP.
Score sample paths resolve against this output root, including the extracted
kit folder. `sourceRevision` records the checked-out renderer revision used for
generation; sample hashes are the exact exported file identities.

Generation verifies diagnostic/native float equality, finite bounds, settled
held state and seams, PCM24 round trips, root/loop metadata, complete recipes,
preflight, both program readbacks and copy-pad equality. The integration test
reloads every saved score and compares its replay with the emitted raw WAV.
A checksum-mismatch assertion verifies that altered sample sources are rejected.

## Why it is built this way

- The normal `.65` layer retains the approved touch while the two extremes
  expose excitation changes without replacing the preset.
- Copy pads separate the effect of a new attack from same-pad choking. Native
  sample identity is checked before using them to compare carryover.
- Performances use exported samples so export rounding, sample boundaries and
  program choices remain part of acceptance.
- Whole-score matching preserves relative attacks and overlaps. Layer
  inspections explicitly exclude the unmeasured hardware amplitude curve.
- The audition page stores playback WAVs with lossless gzip compression to fit
  its hosting budget. Playback and WAV downloads unpack the original bytes;
  original command-line score sources retain their paths. Earlier takes and the
  original PASSAGE ZIP remain available. The staging script does not publish;
  publication uses the existing Site's source and version workflow.
