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

## As built, 2026-11-09

The native audition and separate sixteen-pad kit were generated from the fixed
source snapshot and published at [the glockenspiel audition](https://halobar-native-audition.bertcalm.chatgpt.site/glockenspiel/).
The accepted foundation remains available at the parent page. Static links,
WAV headers and the MPC ZIP CRC passed checks. Browser QA was unavailable.

| Measurement | Native result |
|---|---:|
| Individual audition clips | 69 |
| Glockenspiel measured pitch cases | 10 |
| Largest absolute glockenspiel root error | 0.095 cents |
| Minimum glockenspiel pitch confidence | 0.917 |
| Fresh render median / p95 | 232.1 / 246.0 ms |
| Positive SPACE edit median / p95 | 11.0 / 13.2 ms |

Timing used Temurin 21.0.13 with four visible processors, thirty measurements
after eight warm-ups. Fresh calculation remains above 50 ms. SPACE edits reuse
preparation and meet that target; no completed-output hit satisfies the test.
The measurements do not establish phone performance.

The complete `./gradlew --no-daemon test` command passed 9,221 cases across
all nine JVM modules, with zero failures, errors or skips and no test exclusions.
The first attempt found an outdated starter fixture that expected the new
provisional voice in the accepted three-voice kit. The expected roster was
corrected and a separate study-kit recipe test added before the passing full
rerun. The glockenspiel DSP and audition audio did not change. While that attempt
ran, the foundation PR merged; the expansion was integrated with development
base `33788b3ba` before the final full run, with Android rebuilt afterward.
Unchanged modules may be UP-TO-DATE. The separate 27-case focused source/preset
run, 2-case CLI check and 120-case shelf/root integration check also passed.
The separate four-case glockenspiel suite passed after the study-kit test was
added. All sixteen exported study-kit WAVs regenerate byte-identically
from their saved recipes.
Android assembly and device-test compilation exited zero, and the APK's v2
signature verified. No on-device tests or physical MPC loading are claimed.

All 195 earlier Float32 reference cases still match. The 179 approved audition
WAVs and 153 recipes are byte-identical, and all twelve factory parameter maps
match their accepted references. Each of the 69 new audition clips also replays
exactly through the production renderer. The original sixteen-pad starter is
unchanged; the new glockenspiel kit is a separate study.

The [build ledger](../reference/halobar/glockenspiel-native-validation.json)
records the exact scopes, source hashes, APK, two timing distributions and
publication. The tested code tree is `82c5a0148d92c73bba1c09c5d3667137ce8008bb`, published as
[`c61d9f6fa`](https://github.com/BertCalm/SnipSnap/commit/c61d9f6fad7ab4d83d7ef2896903fb25d7b86e5e).
The original [foundation ledger](../reference/halobar/native-acceptance.json)
retains its earlier full-run failure and correction history; those counts are
not added to this new full run. The provisional
[Float32 fixture](../reference/halobar/glockenspiel-native-parity.tsv) can be
replayed with `NativeReplay.java`, passing `render` and the label
`"provisional glockenspiel audition"` after the TSV path.

Glockenspiel remains pending the owner's listening verdict. Crotales and
tubular bells remain planned, with no implementation claimed here.

## Why it is built this way

- Inharmonic steel modes give glockenspiel its own ring before effects.
- A hard contact supplies the compact attack; TOUCH and velocity soften it.
- One shared return preserves the instrument's dub behavior across materials.
- An isolated audition and kit preserve the accepted foundation while the
  owner judges the new voice.
