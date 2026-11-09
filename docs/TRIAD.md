# TRIAD: shared drum bus audition

Status: native Kotlin listening candidate, not an approved factory bank or phone instrument.
Repository baseline: c00edb997 (2026-11-09). Job: CONCEIVE, distilling the supplied gear chains.

The characteristic gesture is **kick and sub lock → swung percussion interlocks → the shared bus clamps, trails or fractures → finite decay**.

## Landing and sibling check

A groove composer and bus processor is the lightest landing. THUMP already supplies electronic kick, clap and metallic hats; TERRA supplies synthetic hand-drum membranes; SKIN supplies the shaker. REVEL has an interlocking modal phrase but not this electronic drum mix; BACKWASH has per-hit processing but not this shared bus. No new sealed Patch subtype, presets, picker entry or engine-count change is needed for the first listening gate.

The Latin voices are authored membrane approximations, not samples or measured recreations of the original machine. No claim of faithful hardware emulation is made. All synthesis is existing native Kotlin. Only the new arrangement and processors are new.

## Three directions

- Strong, STACK / Warehouse: clear four-on-the-floor gesture, shared dynamics distinguish it from per-hit processing, low numerical risk, one build round. It wins as the anchor because the kick can demonstrably control the whole bus.
- Alternate A, DISTIL / Submerged: same gesture with delayed percussion answers; moderate novelty, low risk with bounded feedback, one build round.
- Alternate B, MUTATE / Fracture: the phrase is re-read backward or at double speed after crushing; strongest temporal novelty, highest risk of hiding the groove, one build round plus listening refinement.

All three requested directions are rendered, not just the strong direction.

## Signal flow

```
124 BPM event clock + swing + nine generated lanes @44.1 kHz
    -> shared dry sum -> sinc upsample @176.4 kHz
    -> WAREHOUSE: envelope compressor -> mid-emphasized blended clipping
    -> SUBMERGED: parallel gentle compression/saturation -> two-head filtered tape delay
    -> FRACTURE: sine fold + quantizer + rate hold -> windowed history grains
    -> bandLimit -> decimate -> mean removal -> 20 Hz DC block
    -> whole-performance loudness match -> short end fade @44.1 kHz
```

Choose exactly one rig. The tape send removes bass; the original whole signal still passes through the first stage. This is an authored adaptation to preserve the sub anchor. The grain processor reads the processed combined signal, including its low end. It is clocked to sixteenths, not a live clock listener or proprietary Microcosm algorithm.

## Controls and timing

All constants below are authored shape choices, pending listening. The audition settings file records exact values.

| Control | Default | Changes | Interaction / proof |
|---|---:|---|---|
| TUNE | .50 | kick and membrane macro offsets | WEIGHT exposes sub cancellation risk |
| WEIGHT | .65 | sub lane gain | DRIVE changes its compression and harmonics |
| SWING | .38 | odd sixteenths delayed up to 30% of a step | DENSITY increases swung events; kick stays on grid |
| DENSITY | .70 | adds bongo, shaker, rim and hat events in thresholds | SWING affects only off-grid percussion |
| DRIVE | .55 | compression ratio / clipping gain / fold and quantizer | SPACE changes recovery or the history receiving it |
| SPACE | .55 | recovery / delay feedback and mix / grain duration and mix | DRIVE supplies the material it processes |

Tempo is explicit (90–150 BPM), bars 1–8; first audition is four bars at 124 BPM followed by three seconds of decay. No HOLD and no seamless-loop claim. These are finite performances; arbitrary sustained effect tails are not cut into a purported seamless loop.

## Reproduce

```sh
./gradlew --no-daemon :synth:generateTriadAudition
python3 scripts/verify_triad_audio.py
python3 scripts/build_triad_page.py /absolute/path/to/site/dist
```

The source archive retains the three Kotlin files and this document. Within the repository, the Gradle task and packaging scripts reproduce the same arrangement. The verification script reads actual PCM24 delivery files independently of Kotlin.

## Stability, cost and cache contract

The compressor never applies gain above unity. Warehouse waveshapers are bounded. Tape taps are a convex .6/.4 blend, the loop lowpass is passive, tanh has slope at most one, feedback is at most .68. Grains have no feedback, at most two overlapping Hann windows, bounded source read positions and gain <=.9. Rate-reduction aliasing is intentional; 4x processing and house band limiting suppress out-of-band products at delivery, not all audible bitcrusher foldover.

`Triad.Source` is explicit reusable precomputation: key it by MODEL_VERSION and all Settings fields (BPM, bars, TUNE, WEIGHT, SWING, DENSITY). Reuse it when DRIVE/SPACE/rig changes. No hidden mutable global cache. Cost is linear in finite frame count; no solver. A multi-second phrase is an offline render, not a demonstrated tens-of-ms phone knob response. Measured desktop costs are in metrics.csv. UI integration must use background renders and a bounded cache; phone performance is unverified.

## Listening gate and integration

`Triad.kt` is callable from Kotlin; `generateTriadAudition` writes PCM24 WAVs, native stage levels at a common fixed -6.02 dB transport gain, matched comparisons, source stems, exact events and settings. `TriadTest` exercises determinism, silence, finite bounds, DC, clock placement, control movement and delay decay. No modification of shipped drum voices. The source is already composed from THUMP, TERRA and SKIN, so their existing engine registry, patch and velocity tests remain applicable. This composer has no sealed Patch registration or velocity API. Pattern velocity presently affects playback gain, not hand-contact timbre; richer velocity articulations are a later musical choice.

No Android changes, MPC program export, new factory presets or HOLD integration in this listening gate. Comparison mixes and stage taps are matched down to the lowest achievable house loudness in their group, avoiding unequal levels when one clip hits peak protection. Stems retain separate solo audition levels. The downloadable WAVs are ordinary mono 44.1 kHz PCM24 files. They are not packaged as a playable MPC program.

## Sonic risks

1. Two low kicks can cancel at some settings. Compare thud/sub stems and full dry mix before processing.
2. Fast compression can flatten the thud. Compare stage one and final Warehouse at all three amounts.
3. Heavy crush can erase Latin articulation. Compare Fracture light/main/heavy against dry.
4. Delay return can obscure claps. Compare Submerged first stage and final tail.
5. Source hand drums may read too synthetic. This is the central first-audition question; no recorded original-machine samples are embedded.

## Research and adaptation

- [Cali76 Bass manual](https://origineffects.com/wp-content/uploads/2024/11/Origin-Effects-Cali76-Bass-Compressor-Owners-Manual-V2-2.pdf): envelope control and sidechain filtering. This adaptation uses a full-band detector for deliberate sub-driven pumping; it does not reproduce the adjustable sidechain HPF or FET circuit.
- [OD-200 product](https://www.boss.info/global/products/od-200/): multiple drive types and gain paths. Adaptation is one blended clip law, not its twelve modes.
- [Clean product](https://www.chasebliss.com/clean): creative compression, parallel dry path, dynamic response. Clean is not simply a boost pedal; this study uses gentle compression and added authored saturation, not its full circuit or dynamic EQ.
- [El Capistan V2 manual](https://www.strymon.net/manuals/ElCapistan_v2_UserManual_RevC.pdf): multiple heads, tape loss and motion. Adaptation is two weighted taps and bounded filtered feedback, no spring reverb or full tape transport.
- [Bitmap 2 manual](https://www.redpandalab.com/content/docs/Bitmap%202%20Owners%20Manual.pdf): quantization, sample-rate reduction, waveshaping. Adaptation has those mechanisms but no product preset or exact transfer-function claim.
- [Microcosm product](https://www.hologramelectronics.com/products/microcosm): synchronized granular, pitch and looping treatments. Adaptation is a seeded, finite history-grain scheduler, not its proprietary algorithms or live phrase looper.

Research scope: primary product/manual reading; no listening evaluation of hardware demos was performed. Product names occur only as research references.

## Verified / measured / heard

- Verified: source APIs and processor order inspected at baseline above; generated audio comes from Kotlin.
- Measured: see audition metrics.csv and test results, updated in the as-built notes below.
- Proposed: all mix, threshold, decay and tonal constants are listening hypotheses.
- Heard: not heard by the implementing assistant; owner's audition pending.

## Why it is built this way

- One shared bus lets a bass hit pull the percussion down and release it together. Per-hit effects cannot create that interaction.
- The same source performance feeds every rig so rhythmic differences cannot masquerade as better processing.
- Removing bass from the tape send leaves room for the low kick while hats and hand drums make the echoes.
- Windowed grains keep captured slices from acquiring hard edit clicks. The risky decision is processing the full low end in Fracture; the audition lets you decide how far that should go.

## As built: first audition

44 delivered PCM24 WAVs passed independent Python finiteness, peak, DC and format checks. Maximum delivered peak 0.990000; maximum absolute mean 0.004143 (including unconditioned native transports). The thirteen full-mix/stage comparisons reach the same house loudness, approximately 0.053509, after downward-only matching; nine stems use separate solo levels. Native transport is a common gain of .5, with no clip-by-clip normalization.

Observed four-bar processing times in this shared cloud run: 3305–7439 ms per rig. These include upsampling and both stage outputs; they are not isolated phone benchmarks. This is not instant knob playback. Reusing the source avoids hit synthesis, not the bus render itself.

Focused Kotlin tests passed before the full JVM run. Browser playback is unverified because browser-control tools are unavailable in this session; asset links, copied PCM hashes and archive contents are checked locally. Owner listening remains pending.
