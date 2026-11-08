# Fired Bronze: processor and amp round

The characteristic gesture is **accepted dry contact → shaped body and harmonics
→ dark repeats and short space → shared amp/speaker coloration**.

Baseline: `d2acec84584082ba00e09f78b51aa454d1cf1637`. Job: BENCHMARK into CONCEIVE.
Josh accepted the dry kit with “Sounds good, carry on.” This round implements the
previously proposed next step. Its new processing has not received a listening verdict.

## Reference and landing

The [Walrus DFX-1 product page](https://www.walrusaudio.com/products/dfx-1-percussion-processing-unit)
was re-read, and the previously downloaded
[manual v1.03](https://cdn.shopify.com/s/files/1/0906/8480/files/DFX-1_Digital_Manual_v1.03.pdf?v=1784316983)
was checked again. The manual describes Forward as EQ, gate, compression,
saturation, delay, reverb; the product page still places saturation first and
tone last. This study explicitly follows the manual ordering. Its defining
behaviors are transient/body control, asymmetric saturation, repeat darkening,
and a short diffuse tail. Published controls are evidence, not measured sonic
calibration. No hardware audio comparison or circuit-match claim is made.

The amp is the repository's generic VALVE model: asymmetric curve, mild supply
sag and a speaker response. No particular amplifier was specified or modeled.
The final amp colors both the strike and the effects tail. This is a versioned
recipe over existing processing primitives, not another engine or rack section.
SKIN and TERRA remain the nearest source siblings; COMPRESSOR, ECHO, SPRING and
VALVE already own the necessary mechanisms. No external DSP source was copied.

The selected STACK direction has a clear forward gesture, low implementation
risk and one build round. JUXTAPOSE (dry plus parallel amplified branch) would
retain more direct attack but introduces phase-alignment risk. COUPLE (amp output
feeding the instrument body) would create a new gesture but require calibration
and feedback stability work. The selected route fulfills the approved plan with
existing bounded primitives.

## Signal flow and controls

```
accepted dry kit or full dry groove
    → near-flat EQ → gentle input gate on short individual pads only
    → 65% direct / 35% compressed body
    → 4× asymmetric saturation without cabinet
    → dark sixteenth-note repeats → short Schroeder space
    → PROCESSOR audition
    → separate 4× amp with mild sag and cabinet response
    → AMP audition
```

All arrows are one-way. COMPRESSOR is the existing detector/gain model, not a
VCA or FET circuit. SPRING is a damped-comb/allpass space, not a calibrated
physical spring or plate. Existing primitives keep their peak-relative gain
laws. In particular, individual pad amp drive is not a model of electrical input
voltage or hardware cleanup at low velocity. Native source velocity still sets
level and timbre; the shared bus retains hit-to-hit amplitude variations inside
one processing pass. There is no fresh loudness normalization after treatment.

| Recipe control | Default | Mechanism | Interaction |
|---|---:|---|---|
| PRESS | .28 | compressor depth/ratio; 35% parallel compressed branch | PRESS×HEAT changes the signal reaching saturation |
| HEAT | .35 | processor and final amp drive | HEAT×SPACE changes distortion of the tail |
| SPACE | .30 | role-scaled delay/reverb sends | SPACE×BPM changes overlap of repeats |
| BPM | 96 | sixteenth-note delay clock, 60–180 range | affects repeats, not source pitch |

No new source macros, HOLD or phone controls are registered. The hot comparison
uses PRESS .55 / HEAT .80 / SPACE .50; it is an audition alternative, not the
export default. Kick and hats have smaller effects sends; bronze keeps more
high end and uses less amp drive than skin. Long voices and the shared bus bypass
the input gate. A zero SPACE setting bypasses both time effects.

Unlike the rejected first treatment, this one has no 1.49-second total cap.
It retains every source frame and permits up to one added second of space. A
short fade ends truncated effects, after the original source has ended. The amp
keeps that complete span. Existing feedback gains remain below one; the output
peak guard is not a stability mechanism. Nonlinear stages use VALVE's existing
4× interpolation, band limit and decimation. Final DC cleanup and fade do not
re-level quiet strikes. This round does not add a higher-rate alias-error test.

## Whole bus versus exported pads

The main audition processes the exact accepted four-bar groove through one
shared processor and amp. This lets the compressor and sag react to overlapping
hits. A separate comparison sums individually treated pads with the same notes,
gains, velocities and hat choke timing. These are intentionally different:
rendered pad samples cannot retain a compressor's history between live hits.
The per-pad groove is an audition of that route, not bit-exact emulation of MPC
velocity-zone interpolation. Its native velocity recipes are rendered per hit.

The downloadable MPC program contains 16 individually processed pads and three
peak-matched timbre layers per pad. MPC supplies velocity gain. A03/A04/A15 share
choke group 1; baked wet tails on those pads are choked too. Bus processing lets
space ring independently after the dry hat is choked. Both behaviors are exposed
for listening. Gong and kettle remain finite long one-shots, not seamless loops.

Schemas for dry R2, dry kit and earlier studies remain unchanged. `FiredBronzeColor`
embeds the exact dry recipe and adds a distinct model version and stage. No
factory registration or application picker change is included. Dry audio and
previous pages remain available.

## Build and validation

```sh
./gradlew --no-daemon :synth:test --tests '*FiredBronzeColorTest' --tests '*FiredBronzeDryKitTest' --tests '*DeterminismTest.FIRED*'
./gradlew --no-daemon :synth:generateFiredBronzeColorAudition
python3 scripts/fired_bronze_color_page.py
```

Render cost: bounded linear passes over each clip, no solver. The generator
reuses each dry source while benchmarking treatment. Timings are three-run
warmed host medians for treatment only; source rendering is additional. No new
persistent cache is added. Long gong and shared-bus renders are offline audition
costs, not proven interactive phone performance.

Verified: APIs and route read on the baseline above; manual/product discrepancy
recorded. Proposed: processing amounts, speaker choice and all perceived blend
claims. Heard: dry kit accepted by Josh; processed round pending. Measured: focused Kotlin run and generator completed with exit 0; 10 tests
passed (3 processing, 3 dry-kit, 4 determinism checks). Independent WAV checks
confirmed all 18 dry files match the accepted pack byte for byte. Export XML
has 16 populated pads, 48 valid sample layers, contiguous velocity zones, and
A03/A04/A15 in choke group 1. Every WAV is mono 44.1 kHz PCM24.

Measured: treatment-only warmed AMP medians were kick 91.0 ms, snare 81.5 ms,
Tin 55.6 ms and gong 512.9 ms. All pad medians ranged 34.2–512.9 ms. The
interactive phone budget is not established. Full-bus dry/processor/amp peaks
were .3533/.3398/.3399; processor and amp are 19.1 s, including added space.
Gong AMP is 9.0 s. These measurements establish bounds, not perceived fidelity.

The compact download contains the 16-pad program, replay recipes, attribution,
notes and three native full-bus comparisons. Individual auditions and matched
versions stream on the page. Repository-wide `test --fail-fast` reached its 180-second limit (exit 124);
no full-suite pass is claimed. MPC hardware
loading and browser interaction remain unverified.

## Why it is built this way

- A mostly direct compressor blend retains the hand contact while adding body.
- The processor and amp are separate so you can judge where the source character changes.
- Long original tails remain intact; only added effects have a duration cap.
- The whole-kit and per-pad examples expose the dynamics that sample export cannot preserve.
- The main risk is losing the distinct strokes in distortion or space. Matched dry comparisons and a separate hot alternative make that easy to judge.
