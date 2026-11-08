# Fired Bronze: playing-response round

The characteristic gesture is **lighter touch → less processor and amp drive →
full-strike impact at the accepted pushed sound**.

Baseline: `de13d78d063f639990f861d47f9b4da950ce538f`. Job: CONCEIVE, a playing
recipe over the accepted kit. Josh called the pushed direction “a big improvement”
and asked to continue. Both pushed is the working baseline; this new velocity
response was subsequently accepted by Josh: “Sounds great”, then “Good on MPC,
we can proceed” (2026-11-08). The sixteen-pad, four-layer performance is now the
hardware-approved baseline.

## Scope and mechanism

The sources and full-strength treatment do not change. A new versioned
`FiredBronzePerformance` recipe maps native velocity to the existing Push settings:

```
PRESS = .65 × (.80 + .20 × velocity)
HEAT  = .72 × (.65 + .35 × velocity)
AMP   = .70 × (.72 + .28 × velocity)
SPACE = .45, BPM = 96
```

At velocity 1 the original settings object is returned directly, preserving
bit-identical full-strength rendering. At lower velocities the source already
changes its excitation color and applies velocity gain. The new law additionally
reduces compressor depth and nonlinear drive. It adds no second velocity gain.
Space and timing remain unchanged. This is an authored touch law, not a physical
model of input impedance or amplifier cleanup. The rack primitives remain
peak-relative; the control mapping explicitly supplies the missing per-pad
relationship to strike strength.

This is the lightest landing: kit recipes, not another engine or rack processor.
SKIN/TERRA already cover the source roles, and Push covers this coloration. The
selected MUTATE direction changes the excitation conditions at each strike,
with a clear gesture, no solver and one build round. STACKing another compressor
would change the accepted full-strength sound. COUPLEing hits through shared amp
state would require live processing that exported samples cannot preserve.

## Export and audition

The new program has 16 pads, each with four timbre layers rendered at .20, .45,
.70 and 1.0. Export zones are MIDI velocity 1–31, 32–63, 64–95 and 96–127.
Layer names are LIGHT, MEDIUM, FIRM and FULL. Soft layer WAVs are peak-matched
to the hard layer; MPC then applies velocity gain. Native listening sequences
retain source velocity, so the light hits are actually quieter. No interpolation
between timbre zones is promised; Josh has now accepted the kit on MPC; no separate automated hardware measurement is claimed.

A03, A04 and A15 retain choke group 1. Long kettle/gong sources retain their tails.
The shared-bus sound from the previous round remains available, but this round
works on individual pads because that is what a four-layer MPC program stores.
The comparison grooves use the same notes, velocities and gains. They render
continuous velocity recipes per hit; they are not an MPC sample-player emulator.
Four-hit sequences expose the four representative layer settings in order.

The page includes 16 full-strength pads, fixed-drive and responsive sequences
for kick/snare/Tin/open hat, and the two grooves. Default playback is native
levels. Matched mode scales an entire four-hit sequence together, preserving
relative hit levels inside the clip. All 64 source recipes ship in the download.
The accepted three-layer Push kit remains available for comparison.

The initial performance study introduced no new source macros, HOLD, factory registry or Android picker. The integration below adds an editable adapter.
No new nonlinear processing path is added: the existing 4× stages, DC cleanup,
finite tail cap and peak guard remain those of Push. Rendering adds only a few
parameter operations to its cost; no solver/cache is needed. Prior long-render
and phone-performance limitations remain. Extra layers increase offline export
work and storage. No new phone timing or aliasing measurement is claimed.

## Reproduction and validation

```sh
./gradlew --no-daemon :synth:test --tests '*FiredBronzePerformanceTest' --tests '*FiredBronzePushTest' --tests '*DeterminismTest.FIRED*'
./gradlew --no-daemon :synth:generateFiredBronzePerformanceAudition
python3 scripts/fired_bronze_performance_page.py
```

Verification targets: full-strength identity on every pad, ordered native energy
across all four representative velocities, finite/DC/end bounds, zero velocity,
recipe replay, exact old-groove identity, 64 sample references and contiguous
velocity zones. Hardware loading and browser interaction remain unverified.
The focused Kotlin run and generator completed with exit 0: 12 tests passed
(3 performance probes, 3 Push probes, 6 determinism checks). Export validation
found 16 populated pads and 64 valid WAV references with the four expected
velocity zones and A03/A04/A15 choke group. All 16 full-strength audition WAVs
and the previous-response groove are byte-identical to the accepted pack.
The download is approximately 19.6 MB. All WAVs are mono 44.1 kHz PCM24.
Repository-wide `test --fail-fast` reached its 180-second limit (exit 124);
no complete-suite pass is claimed. Exported slice ends also match the actual
frame counts of all 64 layer WAVs. The 52 Site audio files were converted to
lossless FLAC and verified against the original PCM; MPC ZIPs retain WAVs.

## Why it is built this way

- Keeping the hard hit exact protects the sound that earned the positive feedback.
- Lower drive on quiet hits adds tonal contrast beyond simply lowering volume.
- Four timbre layers make that contrast available in a sample-based MPC program.
- Constant effects timing keeps quiet notes in the same acoustic space as accents.
- The risk is audible stepping between velocity zones; the comparison sequences expose the representative transitions before hardware acceptance.
