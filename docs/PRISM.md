# PRISM

Prism ports the [11 October engineering specification](superpowers/specs/2026-10-11-prism-engine-engineering-spec.md)
into SnipSnap's deterministic offline melodic pipeline. Thirty-two sinusoidal
partials share a stable root while five spectral groups develop at different
rates. KEY, PLUCK, ORGAN, BASS, GLASS and VEIL provide harmonic keys,
articulated plucks, organ sustain, low body, sparse resonance and slow textures.
The sounds are synthesized from original templates; no factory sound data or
external samples are used. Prism remains a working name.

## Listen

The [published audition](https://prism-snip-snap-audition.bertcalm.chatgpt.site)
plays dry audio rendered by the Kotlin engine. Start with the voices, compare
raw and matched versions, then explore macro sweeps and the interaction grids.
Repeat HOLD clips for several cycles. Listening notes stay on the current
device and can be exported as JSON.

```text
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generatePrismAudition
```

Generated WAVs, the manifest and the listening page live under
`testkit/prism-audition/` and are ignored by Git. The source page and generator
are tracked. The full pack contains 148 cases with raw/matched partners.
Matched files use SnipSnap's shared audition target of .03 RMS over the loudest
200 ms, distinct from the engine's melodic output target. The initial eight
presets are an audition roster. The proposed
36-preset expansion waits for the owner's approval of the dry sound.

## Controls

| Control | Behavior |
| --- | --- |
| TUNE | Two octaves above each voice's minimum; .5 selects the middle octave |
| GRAIN | Smooth harmonic energy → separated bands and sparse clusters |
| DRIFT | Unified contours → independently timed spectral groups |
| SHAPE | Rounded, hollow, nasal and open spectral envelopes |
| STRIKE | Soft rounded attack → bright articulated strike |
| BODY | Thin glassy spectrum → stronger low and middle body |
| HOLD | Finite decay below .975; settled periodic loop at .975 and above |

GRAIN uses continuous masks with a root/body floor and bounded aggregate
energy. SHAPE applies broad masks to each partial before summation, blending
note-relative structure with absolute-frequency features. BODY changes
harmonic density and loss. DRIFT changes group amplitude timing, without
transposing the note. STRIKE and velocity shape the attack and bright transient;
velocity zero is silent. KEY, PLUCK and VEIL span MIDI 48–72, ORGAN 36–60,
BASS 24–48 and GLASS 60–84. Templates and contour settings interpolate inside the
harmonic model rather than crossfading rendered samples.

## Rendering, saving and routing

Each render starts a fresh mono note, with deterministic phase derived through
`Dsp.seedFor`. Synthesis runs at four times 44.1 kHz, excludes partials outside
the usable output bandwidth, conditions the output before decimation and uses
the shared `MELODIC_LOUDNESS_TARGET`. Diagnostic probes retain the conditioned
signal before loudness processing so normalization cannot conceal unstable
internal levels.

Patches retain their voice, macros, velocity, model version and template
configuration. Template IDs and bounded ratio, amplitude, group, envelope and
loop overrides serialize with the recipe. Patch editing and velocity variants
preserve those properties. The patch registry, factory presets, dry sixteen-pad
kit, FRESH TAPE, CLI and phone synth picker expose the same renderer. Finite
notes carry TONAL metadata; held notes carry LOOP metadata.

```text
./gradlew :cli:run --args="synth PRISM KEY --all --midi 60 --velocity 0.4 --out /tmp/prism-keys"
./gradlew :cli:run --args="synth PRISM GLASS --all --midi 72 --out /tmp/prism-glass"
```

HOLD exports a settled loop only. The current pad format has no onset-plus-loop
region, so the finite STRIKE attack is excluded from loop-only material. Carrier
cycles and envelope motion recur over the selected period. Loop checks cover
pitch, independently synthesized adjacent cycles and `Keys.seamError < 1e-3`
after conditioning and decimation. The pitched `Keys` adapter supports
instrument export with a nonzero loop marker.

Numerical checks cover deterministic replay, finite raw levels, pitch, DC,
bandwidth, decay, velocity, spectral macro differences, template serialization,
routing and difficult held loops. The audition adds five-point sweeps and the
required GRAIN × SHAPE, DRIFT × STRIKE, BODY × GRAIN and DRIFT × SHAPE grids.
These checks support listening; they do not establish sonic approval. Phone
and MPC playback still require device validation.
