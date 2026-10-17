# FACET — finite mirrorball prototype

Facet explores one pitched instrument illuminated by twelve synthetic beams.
A rotating facet layout changes which spectral routes receive their energy;
shared resonance lets one reflection linger into the next. Few facets should
produce broad blooms, and many should produce finer shimmer. These are design
intentions awaiting the owner's listening verdict.

The experiment implements **BLOOM** and **SHIMMER**. It is a dry,
sample-free, deterministic offline mono renderer at 44.1 kHz, with 4× internal
oversampling. It is a finite listening prototype, not the complete six-voice
engine in the supplied engineering specification.

## Light study after R2

The owner heard R2 as an Operation buzzer rather than light shimmer. Its
richer harmonic spectrum did not establish the intended identity. The next
step is a finite directional study, `FacetLightStudy`, with **GLASS_GLINTS**
and **AIRY_WASH**. It is a separate experiment; model 2 remains the default,
and the study does not enter `FacetPatch` or the patch registry as model 3.

The implemented study uses soft excitation packets at beam/facet receiver
alignments, a few selected upper modes per packet, shared decays, and a quiet
pitch anchor. Glass emphasizes distinct lingering glints; air uses weaker,
longer overlaps and restrained shaped noise. These are listening hypotheses,
not an accepted solution or a brightness adjustment to the continuous R2
harmonic stack.

The comparison defines twelve six-second clips: BLOOM and SHIMMER at
C3 and C4, each heard as the R2 reference, glass and air under the same
controls, velocity .8 and seed 17. The previous 57-clip R2 study remains
available in collapsed sections on the listening page. **31 focused checks
passed**, with no failures or skips: 20 engine, patch, model and light-study
checks plus 11 registry, preset and promotion checks. The five study tests
cover replay, stopped geometry, options, parameter corners, distinct
directions, packet activity, all five coarse macro controls and 4×/8×
convergence. That rate comparison includes causal filter/state and event
discretization differences; it is not a pure alias measurement. The full
repository gate has not been rerun for this study. Listening acceptance
remains pending. Noise-driven excitation and independently
decaying glints need not produce high periodicity confidence; a pitch-detector
score does not decide whether these textures meet the listening goal.

## R1 feedback and R2 source revision

The owner heard R1 and described the core sound as lo-fi and dead, while
confirming that the macro controls worked. Analysis of its published C3
defaults supports a limited source palette: in the 1–4.5-second body, BLOOM
puts 88.6% of power into the fundamental and 99% below 522 Hz. SHIMMER puts
35.8% into the fundamental and 47.3% into the third harmonic, with 99% below
1,308 Hz. Power above 1 kHz is 0.013% and 1.33%, respectively. These are
power-spectrum measurements from the exported PCM24, using 16,384-sample
Hann windows with a 4,096-sample hop; they support the source revision but
do not replace the listening verdict.

R1 uses eight harmonic carriers. Its twelve linear resonance modes reinforce
those same frequencies. Reflection routing moves their balance, but cannot
create a broad source palette on its own. The owner's response concerns that
core sound, rather than a failure of the controls or export precision.

R2 revises the source to 32 partials shared across three beam groups, with
subtle beating in the upper harmonics while the fundamental stays locked to
the requested note. Each upper partial keeps a fixed carrier beneath a
quieter moving component, so the harmonic body remains present as it beats.
The same geometry, macro mappings, exact-stop behavior
and beam override rules remain the basis of the experiment. No rack effect
is needed to supply the added source body. The `sourceMotion` diagnostic in
`FacetOptions` can disable the source movement without changing the saved
musical recipe; it defaults to enabled.

A separate raw-source probe at C3, velocity .8 and seed 17 measured power
above 1 kHz rising from about 0.013% in R1 to 4.9% in full R2 for BLOOM,
and from 1.3% to 18.0% for SHIMMER. The six-second probes use the 1–4.5-second
body, 65,536-sample Hann windows and an 8,192-sample hop. Enabling R2's
restrained source movement also increased body RMS variation compared with
the dense stationary source. These measurements describe the source change;
they do not establish that it sounds better.

Model 1 is preserved in `FacetModel1.kt`. New patches default to model 2;
patches explicitly naming model 1 continue to render it, and JSON with no
`modelVersion` is read as model 1. Copying or editing a saved patch retains
its model. Upgrading an old recipe is an explicit choice, for example
`oldPatch.copy(modelVersion = Facet.MODEL_VERSION)`. R1 audio and recipes
remain identified separately in the R1/R2 listening comparisons.

R1 passed the full repository gate: **4,944 tests in approximately 86 minutes**.
That is historical R1 validation. R2 passed **26 focused tests**, with zero
failures or skips: 15 engine, patch and model tests plus 11 registry, preset
and promotion checks. Coverage includes both R1 PCM goldens, replay, stopped
geometry, control activity, density continuity, source-motion body retention,
pitch, register bounds and 4×/8× aliasing comparison. The full repository gate
has not been rerun for R2. The 57-clip pack rendered successfully as 171 PCM24
WAVs; all exports were decoded and verified, both C3 R1 defaults matched all
six published raw/native/matched hashes, and pitch estimates stayed within
.54 cents with confidence above .93. The owner's later Operation-buzzer
feedback prompted the separate light study above; these checks did not
establish the intended sound.

## Controls and pitch

| Control | Intended effect |
| --- | --- |
| ILLUMINATE | Beam participation, excitation energy and upper-partial richness |
| ELEVATION | Move illuminated regions from lower foundation toward upper air |
| CHROMA | Change beam spectra and their contrasting colors |
| REVOLVE | Exactly stopped at zero, then broad sweeps through fast glints |
| FACETS | Interpolate coarse reflections into finer surfaces |
| HOLD | Length of the finite gesture below .99; loops are deferred |

MIDI root and excitation velocity are explicit recipe fields, separate from
the six controls. The patch range is MIDI 24–84. REVOLVE = 0 keeps the saved
initial alignment; it does not create a live freeze operation. A new offline
render always starts from the recipe's saved state.

**HOLD ≥ .99 is rejected with a finite-prototype message.** It must not silently
produce an ordinary one-shot under a seamless-loop label. The later HOLD stage
must close source phases, rotor movement, beam travel and resonator memories
while preserving pitch and measurable movement at slow positive rotation.

## Reproducible recipes and beam overrides

`FacetPatch` stores its voice, macro map, MIDI root, velocity, seed, initial
alignment in turns, beam mode, beam overrides and synthesis model version.
The patch snapshots its collections. Geometry tables and running resonance
states are derived render data and do not enter JSON.

Beam IDs are stable integers 0–11. Each override stores normalized intensity,
elevation, color and width. The mode makes precedence explicit:

- `MACRO` generates all twelve records from the voice, macros and seed. Its
  override list must be empty; an accidental nonempty list is rejected.
- `OVERRIDE` first generates the same configuration, then replaces the listed
  IDs with absolute override values. Unlisted IDs keep their generated values.
  Overridden fields do not receive hidden additional macro offsets.

An intensity of zero mutes that beam. Supply twelve zero-intensity overrides
to mute the whole source, including its quiet root support after tails decay.
ILLUMINATE = 0 remains an audible low-illumination macro setting.

For example, a reproducible stopped recipe with one authored beam is:

```kotlin
val patch = FacetPatch(
    "Frozen Bloom", FacetVoice.BLOOM,
    Facet.defaults(FacetVoice.BLOOM) + ("REVOLVE" to 0f),
    midi = 48,
    seed = 17,
    initialAlignment = .25f,
    beamMode = "OVERRIDE",
    beams = listOf(FacetBeam(0, intensity = .7f, elevation = .3f, color = .2f, width = .6f)),
)
val restored = FacetPatch.fromJsonText(patch.toJsonText())
val audio = restored.render()

// Request the preserved R1 renderer explicitly.
val r1Audio = restored.copy(modelVersion = Facet.LEGACY_MODEL_VERSION).render()
```

The shared patch dispatcher, pad recipe serializer and native excitation
velocity path recognize Facet. Macro edits preserve its note, geometry seed,
alignment and authored beam settings.

## Listening and delivery scope

The light study has its own task, `./gradlew :synth:generateFacetLightStudy`,
with `-PfacetLightOutput=/path/to/output` to choose a destination instead of
`testkit/facet-audition`. The task writes twelve focused comparisons rendered
by `FacetLightStudy` and the preserved R2 reference renderer, adding them
to the front of the page while retaining the previous R2 assets, reports
and archives. `light-study.zip` contains matched audio and full replay
settings; raw and native files are individually downloadable. Study
settings identify `FacetLightStudy`, its study version, direction and render
arguments, rather than masquerading as saved `FacetPatch` recipes.

The preserved R2 page can be generated separately with
`./gradlew :synth:generateFacetAudition` to write its listening page,
WAVs, patch JSON and diagnostics under `testkit/facet-audition`. Add
`-PfacetQuick` for a smaller development run, or
`-PfacetOutput=/path/to/output` to choose another destination. Section ZIPs
contain matched WAVs with their recipes, settings and traces; its first-light
ZIP supplies sixteen matched clips: twelve R1/R2 comparisons, the two current
defaults and two slow-motion examples. Raw and native WAVs remain available
as individual downloads.

The previous R2 pack has 57 clips. Its first twelve compare each voice at C3 and
C4 using the preserved R1 source, the denser R2 source with source motion
disabled, and full R2 with source motion enabled. This separates added harmonic
body from the subtle upper-partial beating. The remaining 45 clips exercise
the current R2 defaults and controls. Every clip records its model version and
source-motion setting. Use the full diagnostic settings alongside the musical
patch to replay a comparison.

`-PfacetR1Baseline=/path/to/published-r1` selects an existing R1 snapshot for
the original-audio archive. That bounded archive retains the two published
C3 defaults with their recipes and metadata; its historical manifest and
report describe the original 45-clip pack. The new C3 R1 comparison renders
must match the published raw/native/matched PCM hashes.

The audition pack is the first decision point. Compare dry BLOOM and SHIMMER,
stopped alignments, coarse and fine density, slow and fast rotation, and raw
versus shared-level output. Mechanism comparisons bypass resonance or collapse
the spectral routes to one common gain. The intended model should retain
moving color and lingering reflections that the common-gain comparison loses.

Diagnostics and numerical checks can establish bounded output, determinism
and control activity. They cannot establish that the result sounds like a
coherent illuminated instrument. Both R1 and R2 received core-sound revision
feedback; the light study remains a directional listening experiment.

The first experiment does not register Facet in the Android picker, add a
factory kit, publish a 36-preset roster, or provide the expanded beam editor.
The remaining GLASS, AURA, SWEEP and PRISMATIC profiles follow evidence from
the initial two voices.

Sustained playback is a separate integration stage: ordinary kit-pad hits
play their complete WAV once, and a LOOP filing label does not change that.
SYNTH audition also starts once unless explicitly given loop bounds. Held
performance will need verified loop metadata and the `Keys`/`KeyNote` plus
instrument-maker path used by existing keygroup instruments. Exported sample
transposition changes motion speed along with pitch; independently rendered
zones are needed when the motion rate must remain comparable across registers.
