# OVERLAP — second listening prototype

OVERLAP adapts the [XOmnibus guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/overlap_synthesis_guide.md)
as a deterministic finite six-delay resonator in `:synth`. The
[private audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site) presents
revision 2, with the [original audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site/r1/index.html)
retained for comparison. Musical acceptance remains with the owner.

## Listening verdict and revision

The first listening verdict found the four routing identities too similar,
KNOT halfway a short pluck, and the breathing pair indistinguishable. Three
mechanisms explained the failures:

- Every full-depth circuit had been tuned to the same root-period comb, so the
  routing mostly changed how that same harmonic structure was excited and read.
- `(1-d)I + dP` discarded differential energy. Its squared-energy loss is
  `d(1-d) ||x-Px||²`, greatest around the middle of the control.
- Shallow output-only modulation averaged six phases together, while a shared
  initiating hammer and a short rubbing passage dominated the comparison.

Revision 2 changes the model. It remains a listening candidate; passing numerical
checks does not establish that the new identities or cadence work musically.

## Routing and depth

The same six segment proportions `[1.2, 1.6, 2.0, 3.0, 2.4, 1.8]` belong to every
routing. One overall geometry scale tunes the longest effective circuit to the
reference note; shorter circuits keep their own families.

| Routing | Full-depth structure |
| --- | --- |
| SELF | Six separate lengths, with different upper intervals above the reference root |
| TREFOIL | Two positive three-line cycles, giving root and fifth families |
| MIRROR | Three reflected pairs, giving three odd-harmonic families |
| TORUS | One reflected six-line circuit, giving one odd-harmonic family |

KNOT uses proper rotations: Rodrigues rotations on the two triples, quarter-turn
pair rotations for MIRROR, and composed adjacent rotations for TORUS. Scattering
preserves its input's squared norm at every depth. MIRROR includes one polarity
inversion per pair; TORUS includes one inverted reflection. An unsigned three-pair
or six-cycle permutation has determinant -1 and cannot connect continuously to
identity inside six-dimensional orthogonal matrices. These reflections are an
explicit part of the new instrument, rather than a hidden gain correction.

Fractional-delay interpolation and two-tap damping are passive. Explicit per-line
feedback remains below one. Together with orthogonal scattering, this supplies
an L2 small-gain stability argument before normalization. The tests check the
matrix on every input direction and exercise actual unlevelled impulse responses.

At KNOT zero, every topology selection yields the identical SELF network. Overall
geometry moves toward the selected full-depth size as KNOT rises. Intermediate
pitch remains exploratory. Full-depth families retain the reference root, but a
multi-family sound's strongest perceived pitch or autocorrelation estimate can
follow another family. No independent root oscillator hides that behavior.

## Breathing and glow

Six slow Kuramoto phases gate their own noise excitation and complementary
absorption. Independent phases give staggered entrances; strong LOCK gathers
those energy processes into shared surges. Pickup weights remain fixed. There
is no separate master volume oscillator. PULSE zero bypasses both gates and
breath absorption. The breathing pair uses PULSE .65 (about 1.4 Hz), HOLD one,
and only changes LOCK. Its opening ramps in, without a common hammer masking
the ongoing gesture. Listen beyond the opening second.

GLOW derives bounded even/odd harmonics from the actual output, outside feedback.
It adds harmonic character; it does not promise that every spectrum becomes
brighter. The six deterministic excitation streams are shared between routing
and control comparisons. Synthesis and nonlinearity run at 4× and use the shared
antialias decimator. Output is mono 44.1 kHz.

| Control | Revision 2 behavior |
| --- | --- |
| KNOT | Identity → selected passive scattering, with overall geometry changing |
| PULSE | Zero is off; otherwise sets the six slow phase rates |
| LOCK | Stronger synchronization of actual excitation and absorption |
| GLOW | More output-derived harmonics |
| RING | Nominal low-frequency T60 `.55 × 24^RING` seconds, before additional spectral losses |
| HOLD | Finite excitation duration `.040 + 5.96 × HOLD²` seconds; at one, six seconds plus decay |

All outputs remain finite, capped at eight seconds. HOLD does not export a
seamless or indefinite loop. Phone-picker registration, captured-snip input and
factory kit integration remain outside this audition prototype.

## Recipes and regeneration

`OverlapPatch` stores an explicit model, MIDI 24–84, velocity and the six macros.
New patches use model 2. An old recipe with no model reads as model 1. The original
renderer is preserved in `OverlapV1`; a golden WAV hash checks that its published
sound remains byte-identical. Edits and velocity variants retain the saved model.
Six provisional starting points live in `OverlapPresets`, outside the factory
preset roster.

```text
./gradlew :synth:test --tests com.snipsnap.synth.OverlapTest
./gradlew :synth:generateOverlapAudition
```

The task writes the generated, ignored `testkit/overlap-audition/`. Page source is
`prototype/overlap-audition/`. Twelve clips compare routing, depth, breathing, glow
and two musical candidates. Revision-prefixed WAV and recipe paths avoid serving
stale first-listen audio. The manifest records hashes, reference/pitch observations,
phase order and audio envelope measurements. Raw listening files retain one fixed
engine gain and the regular end fade; matched files use `AuditionLevel`'s loudest
200 ms target `.03`. Raw internal diagnostics precede the fade and leveling.

The page starts silent and plays one clip at a time. Keep/revise/pass decisions
and notes stay in browser storage and export as JSON. Revision 2 uses a separate
note key; first-listen notes remain accessible on the retained original page.
