# Lacquer — engine engineering specification

Version 0.1 • 11 October 2026 • Working name; naming checks pending

## 1. Intent and reference

Lacquer is a polished layered-tone engine for glassy keys, synthetic brass, compact chord-like timbres, plucks, basses, and lush sustains. Its defining control, RELIEF, separates the arrival and development of a bright edge, warm body, and softer trailing layer.

The Roland JD-990 supplies documented inspiration for waveform/filter/amplitude layers and digital oscillator sync: [user guide](https://cdn.roland.com/assets/media/pdf/JD-990_UG.pdf), [owner's manual](https://cdn.roland.com/assets/media/pdf/JD-990_OM.pdf). The generated layers and timing controls here are original proposals, not hardware emulation or factory PCM reproduction.

Its signature is **finished layered tone with depth from articulation**. No repository was inspected. Values require calibration; the supplied edited Gyre contract establishes SnipSnap integration conventions.

## 2. Contract and budget

Deterministic offline mono rendering, 44.1 kHz output/4× internal path, five timbral macros plus HOLD, velocity-sensitive excitation, shared melodic loudness, patches, presets, kits, auditions, MPC export, and pitched classifier guards. Fresh render state and no external sample assets.

V1 uses up to four original layers: body, edge, trailing support, and optional quiet reinforcement. Each has a source, pitch ratio, onset offset, filter projection, and amplitude contour. Sources may be band-limited oscillator blends, small FM/modal tones, or original generated waveform cycles. Share reusable DSP where practical.

Normal layers use the same root with subordinate harmonic/octave support. Do not turn layered presets into undisclosed fixed chords. An explicit chord recipe is deferred unless existing preset metadata can clearly expose it.

## 3. Layer roles

Body provides pitch and low/mid weight. Edge provides transient definition and brighter harmonics. Trail supplies a softer slower component with less upper detail. Optional reinforcement is quiet and must not make three layers sound like four unrelated patches.

Patch data specifies layer source templates, ratios, filters, envelopes, reference gains, and timing profiles. A family selects related layer combinations. Keep the body strong enough for mono playback; uncontrolled detuning or opposite phases must not create thin normal presets.

Layer sources are independently articulated, not delayed copies of a finished main tone. Every layer should have a clear role in diagnostic solo auditions.

## 4. RELIEF: arrival and development

At low RELIEF, layers begin nearly together. Increasing it introduces bounded onset offsets and different attack/decay timing. Suggested initial offsets are 0–80 ms for tight patches and up to 150 ms for deliberate blooms. These are calibration targets.

Keep the earliest layer and requested root identifiable. Different patches may begin with body or edge, but no random order changes during sweeps. RELIEF follows a serialized timing profile rather than controlling every offset independently.

Smooth each onset; when a layer begins later, its source/envelope state follows that actual onset. Do not change a read pointer discontinuously or merely add a global slapback echo. Delay-like repetition is not the intended default.

The difference should remain useful at low values: small attack separation changes the composite contact without requiring a second obvious note.

## 5. SHEEN and BODY

SHEEN controls upper-layer brightness, edge-to-body projection, and a restrained spectral tilt. It spans soft polished detail through brighter glass/metal. Retain pitched body and avoid replacing the high end with broadband hiss.

BODY controls low/mid layer reinforcement, body-source character, and filter loss. It spans lean articulated tone through rounded substantial support. It is not final gain, nor merely the inverse of SHEEN.

Coordinate layer gain limits so increasing BODY and SHEEN together does not create a large raw-level spike. Shared leveling cannot be the only mix safety mechanism.

## 6. CONTOUR

CONTOUR moves envelope shape from short/clipped through longer blooming sustain. Coordinate amplitude, filter-envelope timing, and trailing-layer development. It is not selection of a new waveform family.

At low settings, retain a useful pitched tail for key/bass presets. At high settings, do not turn every patch into a slow pad; the selected voice constrains its range. Velocity changes source energy, edge strength, and filter articulation within bounds.

Compute duration from the latest onset and longest active tail. Never stop before a scheduled trail arrives. Start around 1–4 seconds for bass/key/pluck and 3–7 for sustained voices within host limits, with documented compression of timing/envelopes where necessary.

## 7. GLUE

GLUE supplies restrained shared compression/saturation and common spectral coloration after layer summation. Its purpose is cohesion while preserving articulation. Use an explicit bounded dynamics/drive design, not undefined feedback between independent layers.

At zero the mix remains finished and clean. Higher values gently soften peaks and integrate layers. Compensate level without audible pumping or swallowing RELIEF's timing. Nonlinear processing runs on the oversampled path and is band-limited before decimation.

No mandatory chorus, reverb, or stereo width is required for core identity. Existing rack FX may be added after dry acceptance.

## 8. Macro set

| Macro | Low → high | Default | Neutral proposal |
|---|---|---:|---:|
| RELIEF | Together → separated layer arrivals/development | .35 | .30 |
| SHEEN | Soft upper detail → bright glassy edge | .45 | .45 |
| BODY | Lean → rounded substantial support | .60 | .50 |
| CONTOUR | Clipped → blooming/sustained | .45 | .45 |
| GLUE | Clean layers → cohesive rounded mix | .25 | .00 |
| HOLD | Finite gesture → seamless sustained region | 0 | 0 |

Defaults are hypotheses; verify MacroSpec semantics. Required interactions: RELIEF × CONTOUR, RELIEF × SHEEN, BODY × GLUE, and SHEEN × GLUE. Every normal voice needs useful timbral macro activity.

## 9. Voices and presets

Proposed enum: `LacquerVoice { KEY, GLASS, BRASS, BASS, PLUCK, SILK }`.

| Voice | RELIEF | SHEEN | BODY | CONTOUR | GLUE | Identity |
|---|---:|---:|---:|---:|---:|---|
| KEY | .35 | .45 | .60 | .45 | .25 | Finished layered key |
| GLASS | .45 | .70 | .40 | .50 | .20 | Bright edge with soft support |
| BRASS | .30 | .55 | .65 | .55 | .30 | Synthetic brass-like body |
| BASS | .20 | .35 | .80 | .35 | .30 | Defined low layered gesture |
| PLUCK | .30 | .60 | .45 | .25 | .20 | Tight differentiated attack |
| SILK | .60 | .40 | .60 | .80 | .25 | Broad polished sustain |

Target 36 original presets: 10 keys, 6 glass, 6 brass-like, 6 bass/pluck, 8 sustained. Names: Glass Coat, Warm Underlay, Three Arrivals, Amber Brass, Soft Surface, Delayed Tine, Silk Block, Close Finish. Distinct layer roles matter more than preset count.

## 10. HOLD

Static sustained layers lose onset separation after settling. To preserve meaningful RELIEF in loop-only output, held patches use low-depth periodic articulation or filter/spectral contours with corresponding layer phase offsets. Full attacks need not retrigger. At minimum RELIEF those contours align; increasing it separates their development.

This is a documented held-mode mapping, not proof that onset delay remains audible forever. If an attack-plus-loop format is supported, the initial separated arrivals belong to the attack region.

Make layer contour cycles, source phases/modulation, and GLUE state compatible with the loop. Use bounded preroll, periodic seeds, and the existing pitched-loop method without coarse root detuning. Apply project-standard wrap handling after selecting stable sustain. Require `Keys.seamError < 1e-3` and listening for phase cancellation, gain pumping, and duplicate articulations.

## 11. Integration and numerical requirements

Bound layer mix, timing, oscillator/FM bandwidth, filter resonance, compression, and drive. Prevent clicks, DC, denormals, non-finite state, and aliasing. Level-match diagnostic comparisons and inspect raw peaks before shared leveling. Profile four-layer worst cases.

Use `Dsp.seedFor` or current seed infrastructure. Identical complete inputs reproduce samples and onset times. Implement `Lacquer.kt`, voice/patch/presets, `Patches`, `SynthKits`, `drumClassFor`, auditions, tests, and roadmap using actual interfaces. Serialize layer templates/ratios/gains/timing, envelopes, processing, and loop profiles using sibling conventions.

Use shared `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain. Standard pitched cases avoid KICK/SNARE/CLAP/HAT/TOM classification. Bright glass/pluck and low bass attacks need explicit probes.

## 12. Build and acceptance

1. Build original body/edge/trail sources, tuning, solo/mix auditions, and level probes.
2. Add RELIEF timing and coordinated envelopes; approve dry identity.
3. Add all macros, held contour mapping, voices, and curated presets.
4. Complete HOLD/integration; run current checks with supplied baseline `./gradlew --no-daemon test`.

Probe peak/RMS, DC, pitch, onset offsets, decay, spectrum, aliasing, cost, macro distances, determinism, classifier, serialization, and seam before fixing bounds. Audition note/velocity coverage, five-point sweeps, interaction grids, all-high combinations, latest-layer duration boundaries, and difficult HOLD cases.

Acceptance: useful dry layered presets; RELIEF changes actual independent articulation; coherent pitch/body in mono; independent SHEEN/BODY/CONTOUR roles; GLUE without uncontrolled pumping; meaningful held RELIEF mapping; bounded deterministic extremes; patch/routing checks; and seam metric/listening success. Owner sonic approval precedes preset expansion.

## 13. Deferred scope

Defer JD-990 data/circuit compatibility, factory PCM imports, optional sync/ring-mod expansion, stereo, live polyphony, automatic chords, and standalone UI. Confirm range, duration, velocity, loop format, APIs, and name. Lacquer succeeds when subtle layer timing adds depth to a finished playable tone.
