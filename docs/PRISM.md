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
are tracked. The current matrix contains 176 cases with raw/matched partners;
nine comparison-only clips bring the full pack to 185. The original 157 clips
retain their IDs, audio and listening notes; the 28 additions are factory presets.
Matched files use SnipSnap's shared audition target of .03 RMS over the loudest
200 ms, distinct from the engine's melodic output target. The manifest records
each recipe, PCM fingerprint, pitch, levels, render cost and relevant loop metrics.

## Factory bank

The owner's positive review of the dry sound led to a complete 36-preset bank.
The original eight recipes remain unchanged; 28 additions use the existing
renderer and bounded template controls to provide different attacks, harmonic
balances and group development. Every preset has a musical role on the audition
page and is available through the shared factory preset registry, phone synth
picker and CLI.

| Family | Presets | Voices |
| --- | ---: | --- |
| Keys and plucks | 10 | KEY 5, PLUCK 5 |
| Organs | 6 | ORGAN |
| Basses | 6 | BASS |
| Sustained textures | 8 | VEIL |
| Glass and bells | 6 | GLASS |

Presets retain their finite or held behavior when rendered. Held material exports
only its seamless sustain region; use the finite voices and velocity probes to
judge the strike and early harmonic development. The factory bank adds no FX,
external samples or changes to the calibrated GLASS renderer.

## GLASS listening revision

The first listening verdict described GLASS as harsh and dissonant. The R2
factory template puts its strongest eight partials on integer harmonics and
tapers the higher partials, retaining a quiet quarter-harmonic shimmer. The
five timbral controls and Small Prism's settings remain unchanged.

The audition's GLASS comparison panel pairs R1 and R2 at the same settings:
default notes and Small Prism at C4, C5 and C6, plus a held C5. Start with
matched playback to judge the tonal change independently of output level.
Reviews follow the audio revision, so earlier GLASS verdicts are retained
separately from the revised sound.

New factory GLASS patches store the complete calibrated ratios and amplitudes.
The original empty BELL/RINGING template retains its meaning, and JSON decoding
uses historical GLASS defaults for omitted template data. Existing saved
recipes and explicit custom templates retain their original sound.

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

`--all` renders every preset for the selected voice: five KEY or PLUCK presets,
six ORGAN, BASS or GLASS presets, or eight VEIL presets. `--preset` selects a
one-based index within that voice; the original eight keep their existing
positions. These commands export pad WAVs. The CLI's `--instrument` and `--drone`
options currently apply to other engines.

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
The dry sound has received the owner's positive listening review. Numerical
checks continue to support the expanded bank; phone and MPC playback still
require device validation.
