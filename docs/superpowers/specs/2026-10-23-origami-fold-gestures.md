# ORIGAMI fold gestures

The characteristic gesture is a clear onset → a change between source and folded color → a settled tone → release.

This is an extension of the existing ORIGAMI synth. It adds an explicitly selected, once-only fold-depth contour to the accepted model-4 sources. The finite model, old recipes and six factory presets stay as they were. Velocity expression, additional transformation structures and a new preset bank are later rounds.

## Ground truth and scope

The implementation baseline is `89ddb5be26471e2fa9429a66de3e9835a6050ed2`, the FAN/STILL held-key work in PR #612. That PR is still open, so this change is stacked on its branch. The source files and API names were inspected before this brief was written.

Luthier's main `SKILL.md` was read. Its linked conventions, combination grammar, inventory, catalog, prototype template and spec template are unavailable through the skill service and were not found locally. This brief follows the main workflow using the repository's actual render, recipe, UI and test contracts. No absent reference is treated as evidence.

Literal mechanism: analytic upper partials occupy the accepted source frequencies and geometrically relocated frequencies. A complementary amplitude envelope changes their relative share. Paper folding is the organizing analogy; no physical paper is simulated. No external recording, STFT or new noise source is introduced.

## Alternatives and landing

| Direction | Gesture clarity and novelty | Risk and cost |
| --- | --- | --- |
| INVERT: once-only source/fold-depth contour | Clear opening or unfolding within ORIGAMI's existing spectral relationship. Selected. | Fixed destinations keep pitch risk low. A small envelope runs over cached oscillator bases. |
| COUPLE: envelope moves CREASE | Real partial glides through the reflection axis. | Could reintroduce pitch ambiguity and the crowded color rejected in earlier listening. |
| SPLICE: fold, hold, then return | More elaborate articulated phrases. | More editor states, timing parameters and held-loop transitions than this round needs. |

The nearest verified siblings are `PrismMotion`, which morphs finite additive amplitudes, and CREASE's pressure/deformation gestures, which alter waveform phase. This extension keeps ORIGAMI's source-to-relocated-spectrum relationship. It belongs inside ORIGAMI rather than in a new engine or rack section.

## Signal flow

```text
model-4 source, seed, note and static geometry
     ├── root oscillator ─────────────────────────┐
     ├── source upper bank × (1 − fold depth) ────┤
     └── relocated upper bank × fold depth ───────┤
                                               ▼
                      4× mono mix → sinc decimation → 8 Hz DC rejection
                                               ▼
                             finite end guard → common melodic level

held keys: completed gesture/onset → phase-aligned periodic settled body
                                                → player note-off release
```

The gesture is applied before audio conditioning. Endpoints are not normalized independently. The root receives the original voice envelope and does not follow the fold contour. Complementary shares preserve the upper-bank amplitude budget.

## Controls

| Control | Contract | Required evidence |
| --- | --- | --- |
| TUNE | Existing C3–C5 semitone selection; explicit authored MIDI retains precedence until edited. | Recipe and CLI/UI pitch edits preserve the complete gesture state. |
| CREASE | Existing reflection position. | Destination spectrum remains the static model-4 destination for that setting. |
| FOLD | Maximum relocated share, 0–1. | Zero exactly replays the accepted source; partial settings produce intermediate spectral travel. |
| TURN | Existing upper-bank translation. | Gesture timing does not move destination frequencies. |
| WARP | Existing spacing around the root. | Geometry remains stable throughout a gesture and its held body. |
| MOTION | STEADY retains its existing axis-motion amount. BLOOM/UNFOLD use it for exponential fast-to-slow gesture duration. | Early/middle/late spectral projection and FOLD × MOTION interactions demonstrate movement, not only master gain. |

The new selector is STEADY / BLOOM / UNFOLD. Switching preserves all numeric controls. The UI explains the selected direction and, in new modes, that MOTION sets gesture time and plays once. No seventh macro or attack slider is added.

BLOOM starts with zero reflected share and rises to the authored FOLD. UNFOLD starts at authored FOLD and falls to zero. Both use a quintic contour with zero first and second derivatives at the endpoints. The axis LFO is disabled in these modes; destination frequencies remain fixed.

Initial authored timing choices, subject to listening:

| Voice | Gesture start | Duration at MOTION 0–1 |
| --- | --- | --- |
| CRANE | 0.003 s | 0.035–0.50 s |
| FAN | 0.040 s | 0.12–1.20 s |
| STILL | 0.350 s | 0.30–1.80 s |

STILL's delayed start lets its 480 ms amplitude attack expose the change at short gesture times. These numbers are design choices, not physical measurements.

## Held behavior and compatibility

Only explicit model-4 gesture selection invokes the new renderer. Models 1–3 refuse non-STEADY gestures rather than upgrading. STEADY follows the original finite/held arithmetic, and its canonical JSON omits gesture data. New recipes add `gesture: { model: 1, name: "BLOOM" | "UNFOLD" }`; unknown versions and names are rejected. Finite geometric model 4 and held model 1 remain independent from gesture model 1.

FAN/STILL keep MAKE INSTRUMENT. Their unlooped prefix contains the complete gesture. The periodic body uses the settled source/fold share and zero axis motion. The loop marker follows the completed contour and transition; wraps do not replay the gesture. CRANE keeps its finite note shape. Existing release metadata controls key-up fade.

Current public paths inspected for propagation are `OrigamiPatch.withMacros`, `Patches.edited`, `PadRecipe`, `Velocity.atVelocity`, `UserPresets`, `OrigamiPadMaker.Spec`, `OrigamiKeys.note` and `SynthScreen.buildCurrentPatch`. Gesture edits must immediately write the full ORIGAMI navigation snapshot, preserving absolute MIDI, velocity, model and all 64 seed bits. CLI adds ORIGAMI-only `--gesture` and `--macros`; drone `--motion` keeps its existing meaning.

## Cost and cancellation

Cold renders precompute fixed-frequency 4× root/source/relocated bases keyed by voice, MIDI, seed and CREASE/TURN/WARP. FOLD, MOTION, gesture and velocity do not invalidate them. Two bounded cache entries consume at most about 17 MB for two STILL bases. Cached buffers remain private. New mixes and output conditioning still run per render.

The playability target is warm editing in tens of milliseconds on the JVM host; it must be measured, not claimed from the design. Cold/warm host timings and cache size go in the audition manifest. Phone performance must not be inferred from the host. Synthesis, cache acquisition, mixing and chunked conditioning check cancellation. Held zones remain serial to bound phone memory.

## Proof and listening gate

- Preserve exact old STEADY JSON, R1–R4 audio, factory presets and held-model-1 recipes.
- Compare raw BLOOM + UNFOLD against source + full static fold, with the same geometry, seed, velocity and envelope.
- Check root-only identity and stationary root/destination frequencies; measure spectral movement independently from the contour formula.
- Exercise low/middle FOLD and MOTION, including STILL's audible attack, geometry corners, C3/C4/C5, zero velocity, signed 64-bit seeds and cancellation.
- Verify held attack identity, complete gesture before the marker, independent periodic continuation, wrap step/slope and release playback.
- Check preset promotion, native navigation, SEND/replace, CLI replay and held export metadata.
- Run scoped tests, the full JVM test command, Android assembly and focused device tests. Judge success by exit codes.
- Render native and matched listening clips, including direct STEADY/BLOOM/UNFOLD comparisons and long held notes. The owner judges whether the color change is useful and stays warm.

Verified: baseline APIs and existing accepted-path architecture were read at the commit above. The Kotlin implementation now supplies the gesture law, timing calibration, bounded cache, recipe propagation and held transition described here. The timing choices remain subject to listening.

Measured in this round:

- 217 scoped JVM tests pass, including 13 gesture tests, four workflow tests, legacy render hashes, CLI replay and the determinism canary. The full JVM command is also run before handoff; its final result is recorded with the PR and handoff artifacts.
- Both Android APKs assemble. Focused device checks cover the six controls, gesture selection, maker release, legacy recipes and complete navigation snapshots; final device results are recorded with the handoff.
- The Kotlin audition generator renders 33 native/matched clips, including four twelve-second held references. Its source fingerprint is `0b3e5ce7f9dc8ac797570655269b5409e64991e944a6c9de2b98e5ddf4f782a1`.
- After the listening matrix warmed the JVM, cache-cleared renders take 78–111 ms; the six voice/gesture medians for repeated FOLD/MOTION edits span 18–35 ms. These are JVM 21 amd64 host measurements, not phone latency. Two STILL cache entries occupy 16,934,400 bytes.
- Chromium checks pass embedded playback, native/matched switching, transport isolation, held release and a 390-pixel mobile layout. All 66 WAVs decode with the expected PCM16 mono 44.1 kHz headers. The managed browser refused direct file-origin navigation, so the self-contained page was tested over loopback with secondary network requests blocked.

Heard: no new sound has been accepted by the owner. Render measurements and browser playback checks do not supply a listening verdict.

## Why it is built this way

- The root stays separate so the changing upper color does not pull the note away from its key.
- The gesture changes the balance of two existing spectra, keeping the source and folded destinations the owner already preferred.
- A voice-specific start lets STILL's soft onset reveal the gesture rather than hiding it beneath the attack.
- A completed gesture sits before the held loop so a long note does not repeatedly reopen itself.
- STEADY bypasses the new arithmetic, keeping saved sounds stable while the new mode is auditioned.
