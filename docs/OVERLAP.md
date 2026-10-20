# OVERLAP — factory bank and finite kit

OVERLAP adapts the [XOmnibus guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/overlap_synthesis_guide.md)
as a deterministic finite six-delay resonator in `:synth`. The owner approved
the twelve musical recipes for factory registration. They now enter the SYNTH
picker and a fixed sixteen-pad FRESH TAPE starter through the unchanged model-2
renderer. The [private audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site)
retains audition revision 3's twelve recipes and three scheduled studies, with
the MPC kit handoff alongside them. The
[second listen](https://overlap-snipsnap-audition.bertcalm.chatgpt.site/r2/index.html)
and [original audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site/r1/index.html)
remain available. The first, second and musical auditions keep their existing
audio; factory registration changes their availability in the app.

## Approved factory bank

`OverlapMusicalBank` contains the twelve complete approved model-2 recipes,
grouped by musical role. Each saves topology, all six macros, reference MIDI
note and velocity. `OverlapFactoryPresets` supplies these patches to
`Presets.forVoice`, `Presets.byName` and `Presets.all`. Its separate promotion
table accepts complete saved recipes, retaining their model and other state.
The earlier six `OverlapPresets` control references stay separate.

| Family | Recipes |
| --- | --- |
| Plucks | GLASS THREAD, HOLLOW PIN, CUP STRIKE, CHORD SEED |
| Finite textures | OPEN WEAVE, FIFTH CLOUD, SILVER VEIL, LOW HALO |
| Breaths | LOOSE TIDE, COMMON TIDE, PAIR PULSE, CIRCUIT RUN |

Plucks use a finite strike without breathing or continuing excitation. Textures
use extended finite excitation; LOW HALO also explores slow gathered swells.
Breaths explore independent or gathered excitation/absorption phases at different
rates. MIDI labels identify the geometry reference: intermediate
KNOT settings and multiple resonance families can produce a different strongest
perceived pitch. The phases are not synchronized to a host tempo.

## Phone controls and FRESH TAPE

SYNTH exposes SELF, TREFOIL, MIRROR and TORUS. Each topology serves three of the
approved recipes. KNOT, PULSE, LOCK, GLOW, RING and HOLD sit beside ROOT
(reference MIDI 24–84) and VELOCITY. Preview, macro edits, SAVE PRESET under
YOURS, reopening and SEND TO PAD retain the loaded model, note and velocity.
A legacy model-1 recipe keeps model 1 and fills missing controls using that
model's defaults.

FRESH TAPE → OVERLAP calls `SynthKits.overlap()` to build a fixed dry starter.
A01–A12 preserve the approved recipes in bank order. A13–A16 are LOW THREAD,
LOW PIN, LOW CUP and LOW SEED: copies of the first four plucks one octave down,
with their remaining patch fields preserved. Each pad carries its full
`PadRecipe`, reference-note metadata and native rendered sample.

All sixteen pads are TONAL one-shots. They play their complete finite sample
once, including its native end fade. HOLD extends excitation before decay;
individual renders end within eight seconds. Held notes and sustained loop
support are a separate future step. Captured-snip excitation also remains
future work.

## MPC kit handoff

```text
./gradlew :synth:generateOverlapKit
```

The generator writes `testkit/SnipSnap Overlap Kit/` and
`testkit/SnipSnap-Overlap-Kit.zip`. Use `-PoverlapKitOutput=/absolute/path` to
choose another output directory. The folder contains:

- `SnipSnap Overlap Kit.xtd` and `SnipSnap Overlap Kit_[TrackData]/`: the MPC 3
  track and its samples. The same data folder holds the MPC 2 `.xpm` twin.
- `Source Kit/kit.json` and all sixteen source WAVs, preserving editable
  recipes and the fixed pad order.
- `kit-manifest.json` with full recipes, frame lengths, reference notes and
  source/export WAV hashes, plus `README.txt` with load instructions and pad map.

Samples use native dry mono 44.1 kHz PCM24 renders and the standard kit mixer
level. Export keeps the complete sample lengths and one-shot settings without
audition matching or added effects. Copy the full folder together: MPC 3 loads
the `.xtd` beside its data folder; MPC 2 loads the `.xpm` inside that data folder.
The audition page provides access to the kit archive.

## Preserved audition and replay

```text
./gradlew :synth:generateOverlapMusicalAudition
```

The task writes `testkit/overlap-musical-audition/`, using page source from
`prototype/overlap-musical-audition/`. Its fifteen clips include the twelve
individual recipes and three dry musical studies made from scheduled notes.
Native preset WAVs use the regular patch renderer, shared house leveling and
saved velocity. Native studies sum those rendered notes with one declared
common mix gain of `.25`; their score JSON stores each complete `PadRecipe`
and its frame offset. There are no added effects.

To replay one downloaded study, run the same task with
`-PoverlapScore=/absolute/path/study.json -PoverlapWav=/absolute/path/study.wav`.
Each event retains its complete native tail; score replay refuses truncation.

Matched WAVs use `AuditionLevel`'s loudest-200-ms target `.03` for comparison.
The bank ZIP contains native WAVs, individual recipes and study scores; matched
WAVs remain separate audition downloads. Native playback preserves the regular
renderer levels, while matched playback changes their relative loudness for
listening. The approved recipes retain their pitch-reference limits in both modes.

Audition revision 3 changes the listening material and presentation, not the
engine model. The earlier `OverlapPresets` reference recipes and
`generateOverlapAudition` control-comparison task are preserved. HOLD remains
finite excitation; this bank provides no seamless or indefinite loop.

## Verification status

Host checks cover the preset registry, saved-model edits and shelf reopening,
native recipe replay, fixed starter pad order, complete WAV lengths and MPC 2/MPC 3
one-shot export. Relevant checks are:

```text
./gradlew :synth:test --tests com.snipsnap.synth.PresetsTest --tests com.snipsnap.synth.OverlapKitTest
./gradlew :shell:test --tests com.snipsnap.shell.UserPresetsTest --tests com.snipsnap.shell.OverlapIntegrationTest
./gradlew :synth:generateOverlapKit
```

Android CI builds the app and runs the focused `OverlapSynthScreenTest` on an
emulator. Local JVM checks do not establish physical phone or MPC behavior.
The checks below record the outstanding manual hardware pass; save a custom
preset before leaving SYNTH to retain its note and velocity across recreation.

## Manual real phone pass

1. Open SYNTH → OVERLAP. Select each of the four topologies, load its three
   factory presets and preview them. GLASS THREAD should load reference C4
   (MIDI 60), velocity `.85` and its six saved controls.
2. Change ROOT, VELOCITY and a macro, then preview. Save a named preset under
   YOURS, leave SYNTH and restart the app. Reopen it and verify the same control
   values and sound; inspect the saved recipe to confirm its model is retained.
   Repeat with a saved model-1 recipe when available.
3. SEND TO PAD into an empty slot. Reopen the kit and play it in KIT and PLAY;
   verify the name, recipe and full finite tail survive. Releasing a tap should
   leave the one-shot playing, and it should finish without repeating.
4. Create FRESH TAPE → OVERLAP. Check all sixteen pads: the approved twelve in
   order, followed by the four lower plucks. Listen through the longer textures
   and breaths; verify no accidental looping or premature tail cutoff.
5. Export and reimport through the normal kit flow. Reopen the imported kit and
   check pad order, one-shot settings and full tails. Keep `Source Kit/` as the
   editable recipe copy; inspect its saved reference notes, velocities and models.
6. Load the packaged `.xtd` and `.xpm` handoffs on the MPC in separate passes.
   Check all sixteen samples, one-shot endings and lower pluck answers. Record
   the phone/MPC models, build and any mismatches in the bench results.

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

Audition revision 2 introduced engine model 2. The approved musical bank retains
that renderer without DSP changes. Factory registration leaves its geometry,
breathing and glow behavior unchanged.

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

| Control | Engine model 2 behavior |
| --- | --- |
| KNOT | Identity → selected passive scattering, with overall geometry changing |
| PULSE | Zero is off; otherwise sets the six slow phase rates |
| LOCK | Stronger synchronization of actual excitation and absorption |
| GLOW | More output-derived harmonics |
| RING | Nominal low-frequency T60 `.55 × 24^RING` seconds, before additional spectral losses |
| HOLD | Finite excitation duration `.040 + 5.96 × HOLD²` seconds; at one, six seconds plus decay |

All outputs remain finite, capped at eight seconds. HOLD does not export a
seamless or indefinite loop. Picker registration and the starter kit preserve
that finite behavior. Captured-snip input and held-note support remain future work.

## Recipes and regeneration

`OverlapPatch` stores an explicit model, MIDI 24–84, velocity and the six macros.
New patches use model 2. An old recipe with no model reads as model 1. The original
renderer is preserved in `OverlapV1`; a golden WAV hash checks that its published
sound remains byte-identical. Edits and velocity variants retain the saved model.
Six reference starting points live in `OverlapPresets`, outside the factory
preset roster. They remain separate from the approved twelve-recipe
`OverlapMusicalBank`, which is registered through `OverlapFactoryPresets`.

```text
./gradlew :synth:test --tests com.snipsnap.synth.OverlapTest
./gradlew :synth:generateOverlapAudition
```

The reference task writes the generated, ignored `testkit/overlap-audition/`.
Page source is `prototype/overlap-audition/`. Twelve clips compare routing, depth, breathing, glow
and two musical candidates. Revision-prefixed WAV and recipe paths avoid serving
stale first-listen audio. The manifest records hashes, reference/pitch observations,
phase order and audio envelope measurements. Raw listening files retain one fixed
engine gain and the regular end fade; matched files use `AuditionLevel`'s loudest
200 ms target `.03`. Raw internal diagnostics precede the fade and leveling.

The listening pages start silent and play one clip at a time. Keep/revise/pass
decisions and notes stay in browser storage and export as JSON. The musical bank
uses its own note key, separate from both earlier auditions. Earlier notes remain
accessible on the retained first- and second-listen pages.
