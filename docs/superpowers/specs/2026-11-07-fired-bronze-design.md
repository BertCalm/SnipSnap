# Fired Bronze: kit and processing study specification

Version 1.0 · 2026-11-07

Status: engineering specification and executable first-build study. No owner
listening approval, traditional-instrument fidelity, phone timing, or MPC hardware
acceptance is claimed. Authored constants below are **shape**, not measurements.

Repository truth: `cc47036c43e36d3eca78bd6e70cb15994b08d469`.
Read and searched: `Skin`, `Terra`, `Modes`, `Dsp`, `Tide`, `Compressor`, `Valve`,
`Eq`, `Echo`, `Spring`, `FxChain`, `PedalBoard`, `BoomBapKits`, `ArrangedPad`,
`KitAssembler`, `KitExporter`, `Velocity`, and `DeterminismTest`.

## 1. Gesture and landing

The characteristic gesture is **palm or stick attack → bending skin and ringing
bronze → compressed, splashing repeats → speaker growl**.

This is a sixteen-pad hybrid percussion kit with a reusable treatment, not a new
factory engine. SKIN already owns the kick/snare/hat roles; TERRA is the nearest
membrane/cavity/bell sibling. Neither alone represents stroke-selective loaded
membranes, a pressure-release bayan gesture, and this processor-before-amp route.
Reuse the actual SKIN core and the shared modal toolkit; do not copy TERRA's
generic mode ratios and label them as measured tabla or bonang.

Chosen direction: DISTIL, a kit plus explicit treatment. Clear gesture, moderate
novelty, moderate calibration risk, three staged build rounds. SPLICE (hand
exciters in bronze bodies) is an alternate with higher timbral novelty but weaker
instrument recognition. COUPLE (drums excite a shared bronze bank) is a later,
costlier bus instrument with additional state and stability work. The chosen
direction preserves contrasting pad identities and has the smallest integration
surface. The user asked to start the build, so R1 includes all sixteen audition
roles rather than promoting unreviewed voices into the factory catalog.

## 2. Goals and explicit boundaries

- Keep a real kick, snare, closed hat and open hat.
- Distinguish Indian hand-drum, ceramic, Javanese hand-drum, bamboo and gong roles.
- Every rendered sound is generated, mono, finite and deterministic.
- Preserve dry, processor-only, and amp-last comparisons from the same recipe.
- Export a sixteen-pad MPC sample kit with three timbre zones and hat choking.
- Save complete versioned study recipes and provide a replay command.
- Use an authored hybrid tuning; do not call equal temperament slendro or pelog.

R1 is not an authentic traditional ensemble, full bol vocabulary, calibrated
membrane/shell solver, pedal circuit clone, or shared live bus compressor. The
study's recipes are not registered `Patch`/`PadRecipe` objects. The exported kit
contains WAVs; the phone cannot edit these source models in its factory picker.
No app changes are made without an Android SDK. This is a first build to listen
to, not factory presets authored before hearing the sources.

## 3. Pad contract

| Pad | Candidate label | Research reference | Implemented R1 mechanism |
|---|---|---|---|
| A01 | Clay Kick | drum-set bass drum + invented clay accent | SKIN KICK plus quiet ceramic modal attack |
| A02 | Palm Snare | snare wires + invented hand-slap accent | SKIN SNARE plus quiet short membrane accent |
| A03 | Closed Bronze | closed hi-hat | SKIN HAT_CLOSED; shared family settings with A04 |
| A04 | Open Bronze | open hi-hat | SKIN HAT_OPEN; same choke group as A03 |
| A05 | Open Skin | dayan open center articulation | near-harmonic, loaded-membrane-inspired mode table |
| A06 | Stopped Skin | dayan damped articulation | suppressed root, independently shortened modal decays |
| A07 | Pressure Bass | bayan pressure articulation | three decaying partials with smooth stored press/release |
| A08 | Barrel Slap | dholak treble slap | short inharmonic membrane bank |
| A09 | Low Hand | Central Javanese ciblon, low open stroke | lower, longer membrane bank |
| A10 | High Hand | ciblon, high damped stroke | brighter, differently weighted and damped membrane bank |
| A11 | Clay Pop | ghatam shell and air response | inharmonic shell bank plus low modal cavity proxy |
| A12 | Slit Knock | bamboo kenthongan | sparse, damped structural-mode approximation |
| A13 | Low Kettle | bonang kettle gong | authored pitched gong modes at 330 Hz center |
| A14 | High Kettle | second bonang note | same family at 440 Hz center |
| A15 | Dark Gong | short kempul adaptation | longer low gong modes at 110 Hz center |
| A16 | Cup Choke | Cambodian chhing/chhepp | compact metallic bank with contact-like damping |

The 330/440 relationship is an authored 4:3 interval, not a claim about a
particular gamelan. Other source center frequencies live once in `Part.hz`.
Generic pad labels avoid promising an exact bol or traditional recording.
The enum names identify research targets internally. Only the first four slots
use KICK/SNARE/HAT classes; the others are PERC. No pitched-engine classifier
exemption is being silently applied to a newly registered engine.

## 4. Signal flow

```text
part, TUNE, TOUCH, TIGHT, velocity, take, model version
    |  control data; potential dry-cache key
    v
SKIN source OR authored modal source @176.4 kHz
    -> bandLimit -> decimate -> mean/DC -> level -> tail fade @44.1 kHz
    | DRY comparison
    v
EQ -> input gate -> parallel compression -> asymmetric saturation
    -> dark sixteenth-note delay -> Schroeder space
    | PROCESSOR comparison
    v
separate VALVE amp -> mild sag -> speaker filtering
    -> DC cleanup -> finite tail cap/fade -> peak ceiling
    -> velocity gain -> AMP comparison / exported sample
```

All arrows are one-way audio. There is no source/amp feedback. Reverb and delay
feedback are internal to the existing tested primitives. Both nonlinear stages
use VALVE's 4× implementation. The processor is explicitly composed because one
`FxChain` puts VALVE before ECHO/SPRING, which is not the requested final amp.

## 5. Source model and energy

Static sources use `Modes.ring` with a short Hann contact pulse. Contact duration
changes with TOUCH and velocity; deterministic small texture uses `Dsp.Noise`
seeded with `Dsp.seedFor("FIRED_BRONZE", version, part, take)`. Holding noise stable
while turning controls avoids confusing a new random strike with macro motion.
The pulse is normalized to unit area and band-limited before excitation.

For each retained mode:

```text
theta_i = 2*pi*f0*ratio_i / internal_rate
r_i = exp(-ln(1000)/(t60_i*internal_rate))      # strictly below one
g_i = authored_gain_i / sum(abs(authored_gain)) * sin(theta_i)
y_i[n] = g_i*x[n] + 2*r_i*cos(theta_i)*y_i[n-1] - r_i^2*y_i[n-2]
```

The `sin(theta)` factor cancels the toolkit resonator's impulse-response
`1/sin(theta)` gain. This is a pitch/rate energy correction, not per-event
normalization of the decay. Each radius is passive and fixed within a static
hit. Higher modes receive more excitation with harder touch. Modes whose maximum
planned frequency exceeds 18 kHz are excluded before rendering. The R1 static
tables contain three to five modes, not a finite-element simulation.

Dayan open and damped strokes differ in modal weights and individual losses.
The damped stroke suppresses the root; it is not a gated copy of the open stroke.
Near-harmonic ratios are authored shapes informed by loaded-head research,
not fitted eigenvalues. Authenticity needs reference-informed calibration.

Bayan uses bounded analytic decaying sinusoids with a smooth pressure trajectory:

```text
pressure(t): 0 before 60 ms; raised-cosine rise to 180 ms;
             hold through 250 ms; raised-cosine release through 550 ms; then 0
pitch_ratio(t) = 2^((1 + 5*effective_touch)*pressure(t)/12)
phase_i[n+1] = phase_i[n] + 2*pi*f0*ratio_i*pitch_ratio(t)/rate
amplitude_i[n+1] = amplitude_i[n] * exp(-ln(1000)/(t60_i*rate))
```

This is a gesture analogy, not a solved eccentric membrane. Pressure-driven
loss and cavity coupling remain R2 work. The phase remains continuous, and
amplitude cannot grow after the finite contact ramp. No limiter stabilizes it.

Ghatam's low mode is only a cavity proxy: moving mouth area and shell-air
coupling are not yet modeled. Kendhang's two articulations do not yet share a
coupled two-head state. Bonang/kempul do not yet simulate nonlinear beating.
Bamboo is not claimed to use measured slit-mode ratios. These limits must stay
visible beside audition claims.

## 6. Processor and amplifier

Reference behavior: transient/body compression, asymmetric saturation, dark
repeats, short space, then a separately driven speaker.

The DFX-1 product page and manual v1.03 disagree on ordering. This study chooses
the manual's Forward order: EQ → input gate → compression → saturation → delay
→ reverb, followed by the user-requested amp. The page's alternative is not
quietly treated as identical. No maker names appear in preset/kit names.

R1 approximations:

- Existing `Compressor` with a 45% dry / 55% compressed parallel blend. Its
  detector is not labeled as an API VCA or 1176 FET circuit.
- `Valve` with CAB/SAG off supplies processor saturation; a separate `Valve`
  invocation supplies the final amp. These are distinct functional stages.
- Input gate has peak-relative threshold, hold, and smoothed attack/release.
  It does not gate later reverb. `Gate.chop` is a slicer and is not reused here.
- `Echo.delayFrames` is set through its inverse exponential law to one sixteenth
  at recipe BPM (60–180). R1 uses fixed feedback 0.258, within the primitive's
  stable range, and darker repeats. No self-oscillation mode is included.
- `Spring` is the existing damped-comb/allpass Schroeder space. Its name does
  not establish a dispersive spring-tank model. Plate and true spring options
  are deferred rather than advertised as implemented.
- No microphone, phantom power, hardware pads, MIDI menu, or stereo simulation.
- Per-pad rendering cannot reproduce a shared compressor reacting to the groove.

Hat/bronze amp settings retain more treble and use less drive; kick has low sag.
Wet sends are role-scaled, lowest on kick and highest on kettle gongs. Final
output is capped at min(source length + 1 s, 1.49 s), with an 80 ms fade when
truncated. This is a deliberate finite-kit adaptation, especially for the gong.

## 7. Macro and velocity contract

| Macro | Default | Quantity | Interaction and proof |
|---|---:|---|---|
| TUNE | .50 | modal roots ±1 octave; bounded SKIN pitch mapping | compare pitch at fixed touch, no modal alias fold |
| TOUCH | .45 | contact width, high-mode weights, bayan bend depth | TOUCH × PRESS preserves attack distinctions |
| TIGHT | .60 | modal t60 scale; SKIN decay; input-gate hold/release | decay changes without a discontinuous cutoff |
| PRESS | .35 | compressor threshold/ratio via AMOUNT | compare early/body energy with TOUCH fixed |
| TRAIL | .25 | delay/reverb sends and space size | TRAIL × HEAT changes the colored trail |
| HEAT | .35 | pre-space saturation and final amp drive | harmonics change on loudness-matched comparisons |

All controls are 0..1. `settled` clamps finite control inputs and restores
defaults for non-finite values; serialized recipes strictly reject malformed
values and unknown controls. JSON includes all six settled controls, part,
stage, velocity, BPM, take and model version. Unknown model versions fail.

Velocity changes contact and modal weights before processing and applies a
final scalar gain. Some existing effects normalize relative to input peak;
R1 does not claim a fully level-sensitive hardware drive response. Exported
soft zones are peak-matched timbre layers because MPC applies velocity gain.
The study renders .30/.65/1.00 layers. No random seed is taken from wall time.

HOLD is reserved, not exposed in the finite study. No sustained loop or loop
seam claim is made. Future looping requires an actual independently measured
continuation, not repetition of a cut sample.

## 8. Render budget and caching

Target: a warm dry pad in tens of milliseconds, and a treatment update under
100 ms on the target phone. These are targets, not measured promises. Record
five-run warmed JVM medians for each source/stage with the audition generator.
Maximum duration and mode count are fixed; no geometry/solver runs per sample.

R1 keeps render state local and does not install an unbounded global cache.
The generator caches part/velocity renders within its fixed phrase. For future
interactive treatment, retain dry PCM under a bounded key of model version,
part, TUNE, TOUCH, TIGHT, velocity and take; invalidate wet output for PRESS,
TRAIL, HEAT, BPM or stage. A pitch edit rebuilds coefficients, never geometry.
Phone profiling decides whether a two-entry per-pad dry cache is needed.

## 9. Integration and reproducibility

| Surface | R1 action |
|---|---|
| `synth/.../FiredBronze.kt` | sources, controls, strict versioned study recipe |
| `FiredBronzeTreatment.kt` | explicit processor then amp |
| `FiredBronzeKits.kt` | sixteen arranged pads, names, zones, hat group |
| `FiredBronzeTest.kt` | source, order, tempo, recipe, velocity and numerical probes |
| `DeterminismTest.kt` | all-part processed study canary |
| `FiredBronzeAuditionGenerator.kt` | WAVs, recipe replay, metrics, listening page, MPC folder, ZIP |
| `synth/build.gradle.kts` | `generateFiredBronzeAudition` task |
| docs and roadmap | study status and run commands |
| factory Patch/Presets/Velocity registries | deliberately not modified for this study |
| app picker and editable phone recipes | deferred; no Android SDK in this environment |

Export metadata points to standalone study recipes. Do not attach study JSON
as a `PadRecipe` and imply that the app can deserialize it. The kit stores
rendered samples and source provenance. Factory promotion requires deciding
whether calibrated sources become TERRA extensions or another reusable source
family, and integrating the normal registry/velocity/serialization surfaces.

## 10. Tests and audition gate

Automated first-build checks: finite mono 44.1 kHz output; peak <= 1; DC < .005;
finite endings; all hits shorter than 1.5 s; deterministic JSON replay; rejected
model versions; damped/open dayan late-energy and early-waveform distinction;
audio-observed bayan pitch movement; sixteenth-note delay within one sample;
processor-before-amp composition and order sensitivity; all macros move;
velocity timbre and gain; zero-velocity silence; 16 named pads and hat choking.

Audition includes all parts dry/processor/amp, softer strikes, every macro,
TOUCH×PRESS and TRAIL×HEAT pairs, and the same original hybrid phrase dry/wet.
Listening copies use `AuditionLevel`; native WAVs retain renderer dynamics.
No one calls the hybrid phrase a traditional tala or gendhing.

Before factory promotion: quantify pitch/root confidence against reference
recordings, measure 4× against a higher-rate source reference, inspect raw
headroom before output conditioning, validate low/mid macro interactions in
metrics, and read back both MPC export formats. Full regression command is
`./gradlew --no-daemon test`; report actual exit status and any failures.

## 11. Build rounds and sonic risks

R1: executable sixteen-pad study, matched comparisons, recipe replay, sample
kit and numerical checks. R2: calibrate identities from owner listening and
instrument references, starting with dayan/bayan, ghatam and bonang. R3:
accepted voices/treatment become editable factory resources, with SDK-verified
picker, phone timing, export read-backs, and actual MPC loading.

Risks: near-harmonic dayan reads as an electronic bell; bayan motion arrives
after its energy has decayed; ghatam reads as a rimshot; two hand drums become
retuned copies; bonang lacks characteristic partial evolution; cabinet erases
hat definition; gate removes ghost strokes; compression conceals source flaws;
the finite cap removes the gong's identity. Each has an isolated source/stage
comparison in the audition; hearing and calibration remain necessary.

## 12. Research references and boundaries

- [Narang/Rao, tabla articulation](https://www.ee.iitb.ac.in/course/~daplab/publications/2017/ismir-final-paper.pdf): strike location, pressure and manner matter; Na is not a generic mute.
- [Air loading and tabla harmonicity](https://pubmed.ncbi.nlm.nih.gov/28464644/): membrane alone is not the complete instrument.
- [Ashok/Tiwari, bayan](https://www.sciencedirect.com/science/article/pii/S0003682X25000088): wrist pressure and pitch modulation; abstract/preview evidence, not a copied solver.
- [Batish Institute, dholak](https://raganet.com/Issues/7/dholak.html): bass loading, treble head and articulation; distinguish naal variants.
- [Sumarsam/Wesleyan, kendhang](https://wesomeka.wesleyan.edu/vim2/items/show/9): Central Javanese roles and hand playing.
- [Giridhar Udupa, ghatam](https://www.ghatamudupa.com/ghatam): surface, opening, pitch and touch; no measured mode table supplied.
- [Kenthongan organology](https://www.atlantis-press.com/proceedings/icade-21/125975220): bamboo instrument context, not acoustic calibration.
- [Wesleyan, bonang](https://wesomeka.wesleyan.edu/vim2/items/show/3) and [Grinnell, kempul](https://omeka-s.grinnell.edu/s/MusicalInstruments/item/1214): construction and roles; Grinnell's cited kempul specimen is iron, not a bronze material measurement.
- [Perrin et al., small gong](https://pubmed.ncbi.nlm.nih.gov/25324093/): a particular steel gong, not universal bonang ratios.
- [BYU gong research](https://physics.byu.edu/faculty/gee/gamelan): nonlinear beating in a particular Balinese gong; not implemented in R1.
- [UMBC, Cambodian chhing](https://www2.umbc.edu/eol/cambodia/chhing.htm): open/closed contact states.
- [Yamaha, drum kit](https://www.yamaha.com/en/musical_instrument_guide/drums/mechanism/) and [Bilbao, snare](https://www.research.ed.ac.uk/en/publications/time-domain-simulation-and-sound-synthesis-for-the-snare-drum/): core roles and coupled snare behavior.
- [Bill Alves, gamelan tuning](https://pages.hmc.edu/alves/pleng.html): no standardized slendro/pelog interval table.
- [DFX-1 product](https://www.walrusaudio.com/products/dfx-1-percussion-processing-unit) and [manual v1.03](https://cdn.shopify.com/s/files/1/0906/8480/files/DFX-1_Digital_Manual_v1.03.pdf?v=1784316983): behavior and disclosed order discrepancy.

No external DSP source or instrument samples are copied. Product names occur
only in research discussion. Published acoustical measurements are not claimed
as measurements of this implementation.

## 13. Evidence ledger

| Claim | Status | Evidence |
|---|---|---|
| Existing toolkit signatures and fixed FX order | verified | files read at the repository commit above |
| Instrument articulation principles | researched | cited practitioner, museum and primary research sources |
| Mode ratios, decays, pitch centers, macro defaults | proposed shape | authored for first audition, uncalibrated |
| Exact physical-instrument or hardware match | not claimed | no reference-matched measurement or listening |
| Native tests, artifacts, timing | measured | 10 focused tests passed; 102-clip pack and XPM read-back; loaded-host timing below |
| Owner hearing, phone/MPC acceptance | open | no approval inferred from numerical tests |

## Why it is built this way

1. SKIN keeps the mandatory drum roles recognizable while the new sources focus
   on articulation the existing kit does not supply.
2. Separate modal losses make a damped stroke change its spectrum as well as its
   length; a short volume envelope alone would miss that distinction.
3. A stored pressure gesture makes the bayan speak after the attack without
   paying for a membrane solver on every knob turn.
4. The final amp receives the repeats and space, so the whole tail shares its
   coloration rather than having clean reverb pasted onto distorted drums.
5. The risky decision is the authored modal palette. It must survive dry
   listening before the effects or factory catalog can make it seem finished.

## As built

R1 exists in `FiredBronze.kt`, `FiredBronzeTreatment.kt`, `FiredBronzeKits.kt`
and the `generateFiredBronzeAudition` Gradle task. The 10 focused Kotlin tests
passed (exit 0), including a deterministic recipe canary. The generator produced
102 native/matched comparison pairs, 100 recipes, and a 16-pad/48-layer XPM kit.
XML/WAV read-back passed; the bayan recipe replay produced an identical WAV hash.

Median per-pad warm timings were 171.0 ms dry, 214.9 ms processor, and 294.8 ms
amp-last on the shared Xeon/JDK21 host while other tests ran. The interactive
budget remains unmet/unverified, and no phone performance is claimed.
Detailed commands, timing ranges, regression outcome, and limitations are in
[the build notes](../../FIRED_BRONZE.md). No owner listening approval is implied.
