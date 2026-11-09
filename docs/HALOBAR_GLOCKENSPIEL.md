# HALOBAR glockenspiel

Baseline: `f918220a049f1407a4f32a3bbf2449ab6461fce3`.

The characteristic gesture is a bright metal strike → inharmonic ringing →
dark rhythmic answers → a moving halo.

GLOCKENSPIEL is HALOBAR's fourth voice. It is a native steel-bar approximation
for listening, with provisional studies rather than a promoted factory bank.
The existing twelve vibraphone, marimba and xylophone presets retain their
approval. CROTALES and TUBULAR BELLS are the remaining planned additions.

## Source and controls

The new material uses the existing `Modes.Material.METAL_BAR` ratios
1:2.756:5.404:8.933. That table cites the squared free-free Euler–Bernoulli
eigenvalues, normalized to the first mode, from Fletcher & Rossing and Blevins.
It is a useful inharmonic steel-bar family, not a measured glockenspiel.
Relative gains 1/.50/.26/.12, base T60 values 3.8/1.5/.60/.28 seconds and contact
width .0010→.00009 seconds are musical shapes. The existing register/velocity
laws and 65 μs contact floor still apply.

The source reuses the normalized modal recurrences and band-limited contact.
It has no vibraphone motor. MOTION moves the return; no fan or mallet collision
geometry is simulated. The body send still reduces the root and opens over
10 ms. The feedback, phase, halo and output stages remain shared.

| Control | Default | Glockenspiel behavior |
|---|---:|---|
| TUNE | .50 | Semitone pitch, C4–C7, C5 midpoint |
| TOUCH | .40 | Contact duration and the short dry strike noise |
| RATE | .45 | Six divisions of the same 72 BPM reference |
| RETURN | .45 | Bounded feedback, loss and saturation |
| SPACE | .35 | Echo/halo amount; zero bypasses the return |
| MOTION | .25 | Phase movement in the return |
| HOLD | .30 | Finite source decay; .98–1 remains one maximum |

The characteristic pair checks are TOUCH×SPACE and HOLD×RETURN; the audition
also retains RETURN×SPACE, RATE×RETURN and MOTION×RETURN corners. Files remain
mono 44.1 kHz with a 4x source/nonlinear core and at most twelve seconds per
note. There is no seamless HOLD loop or host-tempo input.

`rootMidi`, `minimumMidi` and `maximumMidi` define the voice's register in the
engine, CLI and shell stepper. The original three mappings retain their exact
arithmetic and ranges. Audio model 1 remains valid for their saved recipes;
adding a new enum value does not revise those sounds.

## Landing and design choice

This is a voice in the existing engine. FORK supplies pickup-colored bars;
TINES supplies FM bells. Neither replaces this modal source feeding HALOBAR's
separate root-reduced send and three-head return.

| Direction | Gesture and roster value | Risk and cost |
|---|---|---|
| DISTIL: metal-bar source into the existing return | Strong; a fourth material shares the established gesture | Small four-mode cost; chosen |
| SPLICE: FM strike into the bar body | Clear attack, but overlaps TINES and FORK | Extra shaping to justify and audition |
| COUPLE: echoes re-excite the bars | A different evolving instrument | Changes the feedback proof and approved routing; deferred |

The factory bank remains twelve recipes. Four glockenspiel listening studies
are served by `HalobarPresets.auditionFor`, independently of `Presets.all()`.
The CLI provides a default Glockenspiel Study without presenting it as an
approved factory preset. The existing phone picker discovers the new voice
through `HalobarVoice.entries`; saved drafts and SEND TO PAD use the normal
versioned recipe path.

The separate sixteen-pad Glockenspiel Study kit has eight C minor pentatonic
notes, C5 through F6, repeated dry and with dub. Every pad is a finite one-shot
with its complete editable source recipe. It does not replace the accepted
three-voice FRESH TAPE starter.

## Reproduce and listen

```sh
./gradlew :synth:generateHalobarGlockenspielAudition
./gradlew :cli:run --args='synth HALOBAR GLOCKENSPIEL --midi 77 --velocity .7 --macros SPACE=.6,RETURN=.7 --out /tmp/glockenspiel'
```

The audition is `testkit/halobar-audition/glockenspiel/index.html`, linked from
the existing audition. Start with the dry/dub phrase. Compare F4, F5 and F6,
then the original voices at F5. Check the shortest/highest attacks, soft strikes
and long endings. Studies, all controls, five interacting pairs and the full
range edges are provided with saved recipes and measured diagnostics.

The house pitch detector stops at 1.2 kHz. Above 1.1 kHz, the probe analyzes
the same PCM with a half-rate clock and rescales the estimate. Analysis windows
are scaled to retain the same physical sample span. No PCM is resampled, and
the detector receives no expected pitch. The manifest records the analysis
rate, estimated physical frequency and confidence.

Prepared SPACE edits use the existing private 8 MiB stem cache, with the
separate 6 MiB completed-output cache. Fresh calculations bypass both. The
generator measures thirty distinct positive SPACE values and thirty fresh
renders after eight warm-ups. Full fresh rendering is still a playability
cost to measure; no phone timing follows from a desktop result.

## Evidence and acceptance

- Verified: the named baseline APIs, existing metal-bar ratio source, shared
  cache/routing and enum-based picker integration.
- Proposed shapes: gains, losses, register and contact law for this voice.
- Measured: see the generated manifest and final build record for actual
  PCM probes, compatibility replay, test exits and timing samples.
- Heard: the original three-voice native audition has owner approval.
  Glockenspiel listening, phone performance and MPC hardware loading are open.

## Why it is built this way

- Inharmonic steel modes give glockenspiel its own ring before effects.
- A hard contact supplies the compact attack; TOUCH and velocity soften it.
- One shared return preserves the instrument's dub behavior across materials.
- An isolated audition and kit preserve the accepted foundation while the
  owner judges the new voice.
