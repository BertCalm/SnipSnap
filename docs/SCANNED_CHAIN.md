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
experiment, giving its delayed low-mode force room to emerge. New patches use
the calibrated model-two response. Recalled model-one patches retain their
original controls; **USE UPDATED CONTROLS** keeps their source and settings
while selecting model two for the current edit. SEND TO PAD saves that edit.

## Controls

| Control | What changes |
| --- | --- |
| MOTION | Physical evolution speed. Zero freezes the shape while its note still plays. |
| DAMP | Mechanical velocity loss, from 0 to 6 per second. Higher settings alter how movement settles. |
| BEND | Spring hardening relative to the authored shape's strain. Model two bounds the extra stiffness while giving smooth sources a useful control range. |
| BLOOM | Source-relative added force, from 0 to 1, during a window of at most two seconds. Listen for body arriving after the strike. |
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
and extraction mode are recorded as provenance. Tonal cycles use their
fractional source period without an amplitude taper or duplicated endpoint.
Long cycles are filtered to their first 63 spatial harmonics before reducing
them to 128 points, so high source harmonics cannot fold into lower modes.
The 20 ms timbral window retains its original taper and extraction arithmetic.

The patch stores all 128 finite points, the source description, four macro
values, note, velocity, seed, duration, envelope and physical configuration.
It does not need the source WAV when recalled or replayed. Drawn shapes and
source shapes are centered and bounded once when created; decoded patches
retain their saved points exactly.
Creation also bounds the cyclic step between neighboring points to 0.25,
using one uniform gain that preserves the contour. This limits excessive
initial strain in broadband shapes before the simulation starts. Model two
also budgets the complete initial energy and bounded external drive before
starting the simulation. One constant gain applies to the initial coordinates,
rest shape, nonlinear strain reference and driving force. The trajectory is
never clipped or normalized while it moves. This covers passive spatial
focusing that an initial coordinate/neighbor limit alone cannot predict.

The JSON engine tag is `SCANNED_CHAIN`, with model 1 or 2 inside the existing
patch and pad-recipe formats. Both fix mechanical and driver frequencies per
voice; numerical resolution and the control law belong to the saved model.
Successfully rendered model-one patches keep their exact original PCM. A
legacy source that previously failed a recognized headroom guard can retry
with conservative initial excitation, without clipping the running state.
Other errors and cancellation still propagate. Model-one JSON is never
silently rewritten as model two. No global recipe-version change is required.

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

Model two uses a positive, bounded hardening spring law. Its nonlinear
reference comes from the authored cyclic strain, so BEND does not depend on
tiny unscaled differences between 128 smooth coordinates. The force and
potential use the same law, and the tangent stiffness has a global bound.
BLOOM's force scales with the authored source RMS. Scaling a contour from a
quiet strike to a stronger source therefore scales its driver as well.

One completed dry clip is cached, with at most four seconds of mono PCM
(705,600 bytes of sample payload). The sonic key includes the saved model,
coordinate bits, resolved macros, physical configuration, note and velocity;
display names and source provenance do not affect it. Every caller receives
owned samples. Exact repeats and preview-to-pad placement can reuse the dry
clip, with effects applied separately. Physical edits still require a fresh
render; this cache does not claim fast knob-to-audio response on a phone.

## Why it is built this way

- Saving the coordinates makes a pad independent of its original recording.
- A saved render model keeps existing sounds stable when control laws change.
- Bounded hardening gives BEND more reach without unbounded spring stiffness.
- Scaling the strike and driver together protects their musical relationship
  when a rough shape needs more initial headroom.
- Caching finished audio removes repeated work while keeping mutable physics
  state private to each render. New control calibration still needs listening
  acceptance; numerical activity alone does not establish musical usefulness.

The original [listening experiment](SCANNED_CHAIN_EXPERIMENT.md), audited
prototype and its published recordings remain separate from the production
model. The original comparison is still available at the
[private audition](https://snipsnap-scanned-chain-audition.bertcalm.chatgpt.site).

Production checks cover saved-shape ownership, version and bounds rejection,
exact patch/recipe recall, deterministic synthesis, note and velocity edits,
kit persistence, recipe replay and MPC export. Regression fixtures include
four pre-change model-one PCM hashes and the optimized legal contour that
previously failed near 99 ms. Extraction checks cover pure-sine harmonic
purity, fractional periods, phase/wrap behavior and unchanged timbral-window
point hashes, plus rejection of downsampling aliases while retaining low
partials. Cache checks cover ownership, invalidation, cancellation and
separate effects processing. Android editor checks cover shape editing,
recalled state and opting into updated controls. Actual phone render latency
and hardware listening remain device checks.
