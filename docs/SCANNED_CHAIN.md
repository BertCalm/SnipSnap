# Scanned Chain

Scanned Chain turns a saved 128-point shape into a pitched, evolving one-shot.
The scanner sets the note; a separate mass-and-spring simulation moves the
shape. It is a finite render-to-pad voice in SYNTH, with PLUCK, METAL and BLOOM
starting points.

## Make a pad

1. Open SYNTH and select SCANNED CHAIN. Choose a voice and its factory preset.
2. Keep the factory shape, draw a new shape, or take a shape from an existing
   pad. Drawing and source extraction change the initial shape, which is then
   saved in the patch.
3. Adjust MOTION, DAMP, BEND and BLOOM together. Set the note, velocity and
   finite length, then audition the result.
4. Use SEND TO PAD. The pad carries the rendered WAV and its complete recipe.
   SAVE AS PRESET retains the patch for another kit; the recall control loads a
   scanned-chain pad's settings back into the editor.

The three presets are Spring Pluck, Metallic Trace and Evolving Bloom.
BLOOM uses the quieter bright strike established in the listening
experiment, giving its delayed low-mode force room to emerge.

## Controls

| Control | What changes |
| --- | --- |
| MOTION | Physical evolution speed. Zero freezes the shape while its note still plays. |
| DAMP | Mechanical velocity loss, from 0 to 6 per second. Higher settings alter how movement settles. |
| BEND | Cubic spring stiffness, from 0 to 8. Its effect depends on strain in the shape. |
| BLOOM | Added force, from 0 to 1, during a bounded window of at most two seconds. Listen for body arriving after the strike. |
| Note | Scanner MIDI note, 36 through 96, independent of the mechanical clock. |
| Velocity | Initial displacement and force strength, with a velocity-sensitive output level. |
| Length | A finite rendered duration, from 0.25 to 4 seconds. |

There is no held-note or seamless-loop mode. Every pad plays an ordinary
rendered sample; MPC export uses the existing one-shot workflow.

## Shapes and recall

FROM PAD offers an explicit 20 ms timbral window or a confidently detected
tonal cycle. A window transfers a timbral imprint, rather than the original
recording's envelope, chronology or pitch. A tonal-cycle request is refused
when the source cannot supply a reliable cycle. The selected source position
and extraction mode are recorded as provenance.

The patch stores all 128 finite points, the source description, four macro
values, note, velocity, seed, duration, envelope and physical configuration.
It does not need the source WAV when recalled or replayed. Drawn shapes and
source shapes are centered and bounded once when created; decoded patches
retain their saved points exactly.
Creation also bounds the cyclic step between neighboring points to 0.25,
using one uniform gain that preserves the contour. This limits excessive
initial strain in broadband shapes before the simulation starts. The factory
shapes already meet that bound; damping and the running state are unchanged.

The JSON engine tag is `SCANNED_CHAIN`, with model 1 inside the existing
patch and pad-recipe formats. Model 1 fixes mechanical and driver frequencies
per voice; numerical solver resolution is also part of that model. Its
remaining serialized settings are validated before rendering. No global
recipe-version change is required.

CLI factory auditions use the same presets:

```text
snipsnap synth SCANNED_CHAIN PLUCK --all --out /tmp/scanned-pluck
snipsnap synth SCANNED_CHAIN METAL --all --midi 60 --velocity 0.8 --out /tmp/scanned-metal
snipsnap synth SCANNED_CHAIN BLOOM --all --out /tmp/scanned-bloom
```

## Implementation and verification

The production model preserves the experiment's simultaneous velocity-Verlet
solver, exact split damping, independent scanner, pitch-limited spatial
projection, velocity-aware snapshot interpolation and shared 4x decimator.
Each render owns its state. Background rendering is interruptible; playback
uses the finished sample. Factory and user patches use one constant output
gain with a velocity-dependent peak ceiling.

The original [listening experiment](SCANNED_CHAIN_EXPERIMENT.md), audited
prototype and its published recordings remain separate from the production
model. The original comparison is still available at the
[private audition](https://snipsnap-scanned-chain-audition.bertcalm.chatgpt.site).

Production checks cover saved-shape ownership, version and bounds rejection,
exact patch/recipe recall, deterministic synthesis, note and velocity edits,
kit persistence, recipe replay and MPC export. Android editor checks cover
shape editing and recalled state. Actual phone render latency and hardware
listening remain device checks.

The first integration passed 78 focused JVM tests, including the original
experiment and the shared preset, velocity and replay contracts. An additional
234 production stress renders passed: 192 macro/voice/shape corner cases and
42 mixed-contour/configuration cases at velocity 1 and the four-second maximum.
Maximum measured displacement was 0.758, below the 0.9 model guard. This is
empirical coverage, rather than a proof for every possible contour.

The Android debug APK and the four new Compose editor tests compile with
Java 17 and Android SDK 35. Device execution of those tests was not run in the
local environment, which has no Android device or readable KVM device.
