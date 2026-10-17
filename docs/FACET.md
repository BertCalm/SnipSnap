# FACET — finite mirrorball prototype

Facet explores one pitched instrument illuminated by twelve synthetic beams.
A rotating facet layout changes which spectral routes receive their energy;
shared resonance lets one reflection linger into the next. Few facets should
produce broad blooms, and many should produce finer shimmer. These are design
intentions awaiting the owner's listening verdict.

The initial experiment implements **BLOOM** and **SHIMMER**. It is a dry,
sample-free, deterministic offline mono renderer at 44.1 kHz, with 4× internal
oversampling. It is a finite listening prototype, not the complete six-voice
engine in the supplied engineering specification.

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
alignment in turns, beam mode, beam overrides and synthesis model version 1.
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
```

The shared patch dispatcher, pad recipe serializer and native excitation
velocity path recognize Facet. Macro edits preserve its note, geometry seed,
alignment and authored beam settings.

## Listening and delivery scope

Run `./gradlew :synth:generateFacetAudition` to write the listening page,
WAVs, patch JSON and diagnostics under `testkit/facet-audition`. Add
`-PfacetQuick` for a smaller development run, or
`-PfacetOutput=/path/to/output` to choose another destination. Section ZIPs
contain matched WAVs with their recipes, settings and traces; the first-light
ZIP supplies sixteen matched clips for an initial listen. Raw and native WAVs
remain available as individual downloads.

The audition pack is the first decision point. Compare dry BLOOM and SHIMMER,
stopped alignments, coarse and fine density, slow and fast rotation, and raw
versus shared-level output. Mechanism comparisons bypass resonance or collapse
the spectral routes to one common gain. The intended model should retain
moving color and lingering reflections that the common-gain comparison loses.

Diagnostics and numerical checks can establish bounded output, determinism
and control activity. They cannot establish that the result sounds like a
coherent illuminated instrument. The owner's listening verdict remains open.

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
