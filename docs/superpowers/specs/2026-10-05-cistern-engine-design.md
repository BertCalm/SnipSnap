# Cistern — engine engineering specification

Version 0.1 • 3 October 2026, America/Chicago • Provisional name; release naming checks pending

Sections 1–20 preserve the supplied engineering proposal. Section 21 records
the Kotlin port's actual public contract and implementation choices. The
proposal's numeric targets and sonic descriptions remain subject to listening.

## 1. Summary

Cistern is a physically inspired pitched synthesis engine for SnipSnap. It imagines a large translucent membrane below an array of suspended liquid droplets. The droplets remain held until vibration releases them. A note strikes the membrane from below. Its motion releases nearby droplets; they fall onto the membrane and produce smaller impacts that can release droplets farther away.

Liquid collecting on the membrane adds load and damping. The initially clear note becomes heavier and softer as its own response lands. Narrow drains gradually remove the liquid, allowing the membrane to recover a brighter resonance.

The signature gesture is **elastic strike → delayed drops → expanding cascade → accumulating load → draining recovery**. One resonant surface produces the initial note and the subsequent drop tones, and its changing liquid state affects both.

This is a generated imaginary-instrument design. Suspended droplets and their release field are invented mechanisms. Equations, defaults, counts, and timings are proposals requiring DSP probes and listening, not validated physical measurements. No repository implementation was inspected.

## 2. Scope and SnipSnap baseline

Follow the supplied SnipSnap architecture and the constraints in the edited Gyre specification: deterministic offline mono generation, macro-driven variation, patch serialization, presets, pitched kit routing, shared loudness processing, auditions, and optional seamless held loops.

Expected output is 44.1 kHz using the established 4× internal render and band-limited decimation infrastructure. Confirm current repository APIs and utilities before implementation. No external sample assets are required.

V1 renders one requested note and a complete small reservoir/membrane state. Each independent render starts from a deterministic suspended-droplet arrangement and initial membrane load. Independently exported pads do not share liquid history. A stateful phrase harness is diagnostic work, not a promised public API change.

Core identity must be audible with minimal rack effects. V1 is mono and does not require visual animation, three-dimensional fluid simulation, or stereo drop positioning.

## 3. Sonic goals and non-goals

Useful regions include clear elastic attacks, rounded droplet notes, sparse delayed answers, expanding pitched cascades, dense rippling textures, heavy wet resonance, and gently brightening tails. Extreme settings may merge individual drops into granular excitation while retaining the membrane’s tonal center.

Cistern is not a membrane hit with an arpeggiator, a water recording layered over a pad, a conventional reverb, or an oscillator pitch envelope called liquid weight. Drop events follow release state and travel; wetness changes the resonating object before output conditioning.

## 4. Initial component budget

| Component | Initial count | Function |
|---|---:|---|
| Membrane modes | 8–12 | Requested pitch and elastic upper resonances |
| Surface regions | 5–7 rings or patches | Local excitation, liquid load, and release influence |
| Suspended droplet slots | 24–48 | Finite available cascade material |
| Airborne drops | At most the slot count | Release time, mass, height, and impact scheduling |
| Drain states | 1 per region | Bounded removal and redistribution of liquid |
| Frame/body modes | 3–4 | Supporting resonant enclosure |

Use a reduced modal surface with coarse liquid regions. A full membrane mesh or Navier–Stokes solver is unnecessary for the first complete engine. Droplet slots are explicit finite objects; do not create an unlimited event stream in normal mode.

## 5. Initial strike and tuning

Note-on supplies a short upward force pulse to the membrane. STRIKE controls contact hardness, contact area, excitation position spread, and initial displacement. If the host provides velocity, it scales event energy within this contact character.

Use a finite band-limited impulse projected into membrane modes. The root mode carries the requested pitch. Start with upper ratios near 1, 1.6, 2.2, 2.9, and 3.8, then calibrate the elastic identity. These are proposed timbral ratios rather than measured membrane eigenvalues.

Liquid impacts excite the same modes at their landing positions. Do not assign each droplet an independent oscillator note. Position-dependent modal weights create different timbral answers while preserving the common pitched surface.

SKIN changes tension/stiffness distribution, modal spacing, compliance, dry losses, and sensitivity to loading. Compensate static root shifts so changing SKIN does not transpose the requested note. The initial strike must remain useful when the cascade is restrained.

## 6. Suspended field and release

Each slot has a position, available liquid mass, suspension threshold, release accumulator, and state: suspended, airborne, landed, or unavailable. Slot placement and small differences derive from deterministic configuration and the project seed.

SUSPENSION controls how tightly droplets are held and how far membrane disturbance reaches through the imagined holding field. Product mapping: low values produce a restrained, easily damped release field; high values permit more responsive cascading. Internally name the derived threshold and reach separately to avoid confusing macro direction with literal suspension strength.

Use a smoothed local membrane-motion envelope or projected modal energy to drive release accumulation. Do not treat every audio waveform cycle as a release event. A sufficiently strong disturbance crosses a threshold and releases an available slot once.

A front may propagate through adjacent regions with bounded delay and attenuation. This models the expanding release influence, distinct from the droplet’s subsequent fall. Avoid globally releasing every slot simultaneously unless an explicit extreme patch crosses all thresholds.

Initial impact influence can favor near-center slots; later drop impacts can reach outer regions. The cascade emerges through these local stimulus paths, not a predetermined note sequence.

## 7. Falling drops and impact timing

After release, a droplet takes a finite time to reach the membrane. Use a simple height/acceleration model or an equivalent deterministic travel-time mapping. Start with musically exaggerated delays around 40–500 ms. Define normalized units and do not claim literal physical scale from those timings.

DROP changes droplet mass distribution, contact footprint, and fall-height profile. Keep their effects coherent: larger drops deliver heavier, rounder contacts and more local liquid loading. Height can influence impact velocity separately from mass.

Impact time follows release plus travel. If the membrane’s slow displacement affects timing, use a bounded first-order approximation; a full moving-surface collision solver is deferred. Schedule fractional-sample impacts or interpolate their force pulses so control-rate quantization does not add unwanted clicks.

Impact force depends on droplet mass, arrival velocity, contact compliance, and existing wet load. Use a short finite pulse with rounded contact; add only a small band-limited splash texture tied to impact energy. The membrane resonance, not splash noise, is the primary pitched answer.

A wet surface can soften later impacts. Strong drops should therefore change subsequent articulation as well as loudness.

## 8. Cascade causality and energy

A drop impact can stimulate neighboring suspension slots, which may release after their own threshold and travel delay. Keep explicit event lineage in diagnostics: initial strike, release cause, airborne travel, landing, and secondary releases.

One-shot cascade growth is bounded by the finite suspended inventory. A slot cannot release twice without explicit replenishment. Also enforce a maximum event density, a short local refractory interval, and an overall finite render horizon. These guards complement the inventory; they should not replace causal triggering with arbitrary dropped events.

Falling droplets carry stored gravitational energy from their initial suspension. The strike triggers that release rather than supplying all subsequent impact energy. The imaginary holding field does not replenish the inventory in one-shot mode. This permits an amplifying cascade without pretending the passive membrane creates energy.

Keep acoustic feedback and event branching separate. With no remaining drops and no performer input, the membrane/frame network must decay. A threshold detector must not restart a spent slot because its tail is still ringing.

## 9. Accumulating liquid and membrane loading

Landing deposits liquid into the corresponding surface region. Track nonnegative liquid quantity with bounded capacity. Transfer between adjacent regions through a smooth redistribution rule; drain or spill any excess through explicit sinks.

Liquid changes effective modal mass, damping, contact compliance, and coupling between regions. Coarse modal-weighted loading is sufficient. Apply coefficient changes smoothly and account for the energy implications of changing mass; avoid a coefficient interpolation that creates unexplained growing resonance.

Greater liquid load should generally soften impacts, shorten some upper-mode decay, and reinforce a heavier lower spectral balance. It need not simply darken all modes equally. Dense cascades may suppress their own high-frequency articulation as liquid builds.

Requested-pitch policy: compensate most slow root shift at normal settings, retaining a small bounded expressive sag. Start with a ±10-cent target for moderate steady tones and allow a documented extreme sag up to roughly 30 cents if listening supports it. Do not let wet loading silently move the engine to a different note. These tolerances are initial proposals.

Maintain audible loading through modal balance and loss even when pitch compensation is strong. Wetness must not reduce to a low-pass filter or a fixed pitch envelope.

## 10. Drainage and recovery

DRAIN controls removal rate, drain distribution, and a small amount of local redistribution toward outlets. Low values retain heavier wet coloration; high values restore the dry response sooner. Retain bounded baseline loss or a termination rule so DRAIN=0 cannot force endless rendering.

Drain flow follows available liquid. Do not generate an unrelated continuous trickle when the surface is dry. Optional quiet drain texture is derived from flow and remains subordinate to the tonal surface.

As liquid leaves, damping and loading recover smoothly. The remaining resonance may brighten, but drainage does not restore acoustic energy already dissipated. A brighter tail can arise from changing spectral loss or later impacts on a lighter surface; it cannot regrow indefinitely with no energy source.

This distinction is essential: draining removes mass and changes the object, while resonant decay continues. A repeated swell requires a new drop, an external gesture, or the explicit HOLD mechanism.

## 11. Frame and body

Use a small stable frame/body resonator receiving membrane mounting forces and returning bounded loading. It gives weight beneath the translucent skin without becoming an independent bass oscillator.

Body properties are voice configuration in V1. SKIN can correlate mounting load with compliance, but does not function as a generic body-filter knob. No reverb substitutes for the frame coupling.

## 12. Macros

Expose five timbral controls plus HOLD. Values and neutral points are proposed; verify MacroSpec semantics in the current host.

| Macro | Default | Proposed neutral | Low → high |
|---|---:|---:|---|
| STRIKE | .50 | .40 | Soft broad contact → firm bright upward excitation |
| SUSPENSION | .50 | .40 | Restrained release response → wider, more reactive cascade |
| DROP | .45 | .40 | Small light droplets → larger contacts, heavier load, varied travel |
| SKIN | .55 | .50 | Compliant soft structure → tighter, more articulate membrane |
| DRAIN | .45 | .40 | Persistent wet loading → faster unloading and recovery |
| HOLD | .00 | .00 | Finite reservoir gesture → seamless replenished material cycle |

Required interactions: STRIKE × SUSPENSION changes initial release reach; DROP × SUSPENSION changes secondary branching; DROP × SKIN changes contact projection/loading; DROP × DRAIN changes accumulated wetness; SKIN × DRAIN changes the character of recovering resonance.

Every normal voice must expose useful audible changes across all five timbral macros. DRAIN should alter wet contact during ordinary gestures, not only after a very long tail. SUSPENSION should affect some available slots even near its low range. Diagnostic no-drop operation is separate from a normal macro minimum unless the product intentionally exposes a true off setting.

## 13. Voices and starting defaults

Proposed enum: `CisternVoice { FIRST, DRIP, CASCADE, POOL, RIPPLE, RECOVERY }`.

| Voice | Character | STRIKE | SUSPENSION | DROP | SKIN | DRAIN |
|---|---|---:|---:|---:|---:|---:|
| FIRST | Clear melodic surface with sparse answers | .55 | .25 | .30 | .65 | .65 |
| DRIP | Distinct rounded delayed notes | .35 | .40 | .45 | .55 | .50 |
| CASCADE | Expanding chain of pitched impacts | .60 | .75 | .50 | .60 | .45 |
| POOL | Heavy wet membrane and softened contacts | .50 | .60 | .80 | .35 | .20 |
| RIPPLE | Light dense articulated surface | .45 | .70 | .25 | .80 | .65 |
| RECOVERY | Audible wet-to-light settling trajectory | .55 | .60 | .65 | .55 | .80 |

Voices bias modal ratios, slot placement, travel profile, contact shape, and frame losses. They use the same engine. No voice disables a timbral macro.

Initial presets: First Drop, Hanging Rain, Soft Skin, Wide Cascade, Heavy Landing, Thin Ripple, Wet Basin, Slow Drain, Clear Return, Quiet Reservoir, Dense Surface, and Replenished Circle. Names require release checks.

## 14. One-shot duration and HOLD

One-shots have one finite initial strike and a fixed droplet inventory. Start near 2–6 seconds, extending long cascade/recovery presets only within current host limits. Stop when no meaningful airborne events remain and acoustic energy falls below the established threshold, or at a bounded maximum duration. Do not wait indefinitely for exact liquid equilibrium after audio is silent.

HOLD introduces an explicit powered circulation system: drained liquid is lifted back into the suspended field. A deterministic bounded maintenance excitation can keep the surface active when a sparse cascade would otherwise stop. This pump and maintenance gesture supply energy; do not imply that a finite drop inventory sustains itself forever.

Track airborne, surface, drained, and resuspended inventory consistently. Pump return has finite delay and capacity. Define steady replenishment or a periodic cycle with balanced input and drainage. No droplet is recreated instantly at the wrap.

For seamless loops, converge reservoir occupancy, pending drops, liquid regions, release accumulators, pump queues, membrane modes, and frame modes with bounded preroll. Periodic pump phase alone does not guarantee periodic cascade events. Use repeatable seeded variation and constrained periodic release opportunities in HOLD mode where necessary.

Select a stable cycle region and use the project wrap utility or a short crossfade if needed. Check for duplicated drops, missing landings, and sudden wetness changes at the seam. Target `Keys.seamError < 1e-3` if still the current contract, together with listening for rhythmic or timbral discontinuities.

Confirm whether the host supports an attack plus loop region. If it returns only a loop buffer, provide the settled replenished state and document omission of the initiating strike. Do not silently expand the sample API.

## 15. Numerical and rendering requirements

Use stable modal integration, bounded loading, nonnegative liquid state, and explicit source/sink bookkeeping. Acoustic coupling must decay when inputs stop. Changing load must not inject uncontrolled energy. Recover non-finite state deterministically at its source; final limiting is not a stability fix.

Render short impacts and nonlinear contact on the oversampled path. Coarse fluid/release updates may use a slower clock, initially around 1 kHz, with convergence checks and interpolated event timing. Smooth modal coefficients and antialias dense event excitation. Prevent DC, denormals, and high-register splash masking.

All placement variation, thresholds, roughness, and contact texture derive from the existing seed mechanism, such as `Dsp.seedFor`. Identical voice, pitch, macros, velocity, patch, and seed context must reproduce identical samples and event order under the current render contract.

Apply shared melodic loudness processing after band-limited decimation. Compare raw and normalized auditions so weak impacts or an inaudible recovery are not concealed by leveling. Record render time and memory before optimizing.

Diagnostics: initial surface audio, drop-only excitation, frame audio, slot/release lineage, travel times, liquid inventory, regional load, drain flow, pump queues, and modeled input/dissipation. Keep these outside the user macro surface.

## 16. Integration

Expected additions: `Cistern.kt`, `CisternVoice`, `CisternPatch`, `CisternPresets`, renderer/macro registration, `Patches` entry, `SynthKits`, `drumClassFor`, audition task, tests, and roadmap update. Adapt names to current repository organization.

Round-trip voice and all macros through existing JSON conventions. Reuse `MELODIC_LOUDNESS_TARGET`, PadRecipe, and FxChain handling. Dry audio must demonstrate the cascade and wet loading before rack effects.

Cistern is pitched melodic material. Standard auditions must satisfy existing guards against KICK, SNARE, CLAP, HAT, and TOM. Preserve tonal tails in FIRST, constrain splash noise in RIPPLE, and retain root energy in POOL. Confirm actual classifier/routing behavior rather than assuming interfaces.

## 17. Build rounds

**Round 1 — struck surface.** Build tuned membrane, positional impacts, and frame. Establish useful dry pitched samples. Measure pitch, peak, RMS, DC, decay, and render cost before fixing test bounds.

**Round 2 — finite cascade.** Add slots, local release field, travel timing, impacts, and inventory accounting. Demonstrate that a stronger event changes branching and that exhausted slots cannot release again.

**Round 3 — changing surface.** Add liquid regions, load, damping, and drainage. Compare full loading with a frozen-load diagnostic using the same scheduled drops. Establish an audible changing object rather than a generic pitch/filter envelope.

**Round 4 — circulation and delivery.** Add powered HOLD, balanced replenishment, convergence, seam handling, patches, presets, kits, auditions, and documentation. Run current required checks; the supplied baseline names `./gradlew --no-daemon test`. Confirm applicable repository instructions before treating that as the complete gate.

The owner’s listening verdict remains required. Numeric gates establish causal behavior and stability, not whether the liquid surface has a compelling sound.

## 18. Audition and acceptance

Add `generateCisternAudition` or the current equivalent. Render every voice, low/middle/high supported notes, and quiet/medium/strong event energy. Sweep macros at 0, .25, .5, .75, and 1 with neutral companions. Include raw and matched-loudness comparisons.

Required grids: STRIKE × SUSPENSION, DROP × SUSPENSION, DROP × SKIN, DROP × DRAIN, and SKIN × DRAIN. Include isolated strike, restrained cascade, all-high extremes, heavy retained liquid, fast drainage, dense high-register impacts, spent inventory, and difficult HOLD cycles.

| Requirement | Evidence |
|---|---|
| Identity | One surface audibly carries the initial strike, delayed cascade, and changing wet tail |
| Release causality | Each release has a stimulus and available slot; secondary impacts can cause further releases |
| Travel | Impact time follows release plus bounded fall time |
| Inventory | Slots cannot release twice in a one-shot; liquid bookkeeping stays bounded and consistent |
| Loading | Accumulation changes contact, damping, and modal response before final output |
| Recovery | Drainage changes the remaining resonance without creating endless new energy |
| Pitch | Moderate full mixes retain root; calibrate proposed tuning/sag limits with analysis and listening |
| Stability | Passive acoustic tail decays; extreme one-shots terminate; long HOLD stays bounded |
| Macros | Every normal voice demonstrates useful audible and measurable changes |
| Determinism | Identical inputs produce sample-identical audio and event order |
| Patch/routing | JSON round-trip and current pitched guards pass |
| Loops | Representative HOLD cases pass seam metric and event/material continuity review |
| Output | Calibrated peak, DC, aliasing, duration, and render-cost requirements pass |

## 19. Deferred work and open decisions

Defer full fluid mechanics, droplet breakup and spray meshes, stereo positioning, multiple playable membrane notes, persistent cross-pad state, unrestricted rainfall sources, visual rendering, and physical hardware.

Before implementation, confirm note range, velocity semantics, duration limits, HOLD format, current patch/render APIs, and release naming constraints. During probes, establish modal ratios, threshold mapping, travel-time distribution, inventory size, loading compensation, drainage behavior, and stable replenishment cycles. Numeric proposals remain subject to calibration.

## 20. Sonic north star

A clear elastic note should summon smaller impacts across its own surface. Their accumulating liquid should make later notes land differently and change the resonance already sounding. As the surface drains, the remaining gesture should regain some clarity without forgetting the cascade that changed it.

Cistern succeeds when a note brings down the material that reshapes its own sound.

## 21. Kotlin port — 5 October 2026

The port adds `Cistern.kt`, `CisternPatch`, `CisternPresets`, patch and
velocity dispatch, the preset roster, `SynthKits.cistern()`, the shell's
starter-kit entry, the phone picker and the CLI. The engine and twelve
starting presets are provisional and have not passed the owner's listening
gate. No release-name availability check is claimed.

### Public contract

```kotlin
val snip = Cistern.render(
    CisternVoice.CASCADE,
    mapOf("SUSPENSION" to 0.75f, "DROP" to 0.5f),
    midi = 60,
    velocity = 0.6f,
)
```

The six voices are FIRST, DRIP, CASCADE, POOL, RIPPLE and RECOVERY. Every
voice exposes STRIKE, SUSPENSION, DROP, SKIN, DRAIN and HOLD. The note is
an explicit MIDI integer from 36 to 84, default 60; it is not a TUNE
macro. Velocity is a finite 0–1 event-energy parameter, default 1. Zero
velocity produces silence. Invalid notes or velocities are rejected.
Missing macro values use the voice defaults, finite render macro values
are bounded to 0–1, and non-finite render values revert to their defaults.
`CisternPatch` validates its macros through the existing patch contract.

`Cistern.defaults(voice)` and `Cistern.macrosFor(voice)` declare the controls.
Each macro's neutral is the voice's default, following the current host
convention. This is a port decision that differs from the proposal's common
neutral points. Defaults otherwise follow section 13, with HOLD at zero.
`Cistern.isLoop(hold)` is true at HOLD ≥ 0.85. `Cistern.drumClassFor` routes
these long pitched gestures as LOOP, including finite one-shots; the routing
class and the presence of a seamless held loop are separate properties.

`CisternPatch(name, voice, macros, midi = 60, velocity = 1f, model = 1)`
persists voice, controls, note, event energy and model version through the
existing JSON and `PadRecipe` path. The current patch renderer rejects
unsupported model versions. The FX rack operates after the dry engine as
usual. `Velocity.atVelocity` sends event energy to Cistern rather than
substituting another macro or only scaling an already-rendered sample.
Independent patch renders start independent reservoir states.

### Surface, reservoir and rendering

The heard signal is mono at 44.1 kHz. Ten membrane modes and three frame
modes run at the established 4× internal rate of 176.4 kHz; shared sinc
decimation and melodic loudness conditioning follow. A fixed .04 output
gain supplies PCM headroom before loudness conditioning; it is also present
in the raw audition signal and frame tap. The six liquid regions
have nonnegative inventory and a capacity of 1.25 normalized units each.
FIRST starts with 28 suspended slots, RIPPLE with 44, and the other voices
with 36. POOL and RECOVERY also start with a small wet load. Placement and
contact roughness derive from the project seed mechanism, and the field's
random placement remains fixed across a macro sweep.

The provisional voicing uses six distinct surface profiles rather than tiny
offsets of one modal balance. Each profile declares upper-mode spacing,
force projection, radiation, loss, frame coupling, contact compliance and
suspended-field geometry. FIRST leads with a clear elastic strike; DRIP
emphasizes rounded delayed answers; CASCADE exposes spreading upper modes;
POOL retains close low resonances under wet damping; RIPPLE has narrow,
light contacts; RECOVERY starts loaded and clears as liquid drains. The
root remains the requested note. Contact duration scales with the root
period across C2–C6 so higher notes retain audible surface character.

STRIKE changes position, footprint and force duration as well as input
energy. SKIN changes upper spacing, radiation, contact projection and
loss. DROP changes mass, height and contact compliance; SUSPENSION changes
release thresholds and reach; DRAIN changes wet retention during the
gesture. The dry sixteen-pad kit uses all six profiles and all twelve
starting presets at explicit C-minor-pentatonic notes. Its first eight
pads alternate surface characters instead of transposing a single FIRST
patch.

Audition revision `voice-contrast-2` addresses the owner's reported
sameness. The old clips had roughly 99% of their C4 spectral energy in
the fundamental band. New regression checks require audible non-root
body, distinct normalized spectra at the same note and velocity, and
FIRST/DRIP STRIKE and SKIN endpoint contrast. These numerical checks do
not establish musical acceptance. The hosted audition preserves original
clips for direct before/after listening and identifies the revision in
exported notes; patch model 1 remains the provisional causal architecture.

The material/release clock advances every 176 internal samples, approximately
1002.27 Hz. A smoothed local modal-energy envelope acts on a decaying release
field. Threshold crossings spend a slot once, bounded propagation reaches
other regions, and a landing can become the cause of secondary releases.
Release-to-landing travel is constrained to 40–500 ms. Rounded finite force
pulses evaluate fractional start times on the oversampled path. The short
splash texture remains subordinate to the membrane.

Modal coordinates carry acoustic energy explicitly. Free evolution is a
rotation followed by contraction, and the membrane/frame exchange is an
orthogonal rotation. Accreting mass removes acoustic energy; unloading keeps
the remaining energy rather than restoring past dissipation. Wet load
changes modal mass, upper-mode spacing, loss and impact compliance before
output leveling. Adjacent liquid redistribution is conservative, while
overflow and drainage go into explicit sinks. A baseline drain remains at
DRAIN 0. The root's expressive loading sag is bounded in the implementation;
the proposal's pitch tolerances still require measurement and listening.

One-shot length is selected from the macros rather than waiting for exact
material equilibrium:

```text
3.3 + 1.4·SUSPENSION + .8·DROP + .5·(1−DRAIN) + .4·min(HOLD, .85) seconds
```

The computed horizon is bounded at 6.4 seconds, below the public eight-second
one-shot limit. One-shots receive a 40 ms ending fade after decimation. The
internal diagnostic `seconds` option permits bounded .1–30 second continuous
runs for inspecting passive tails and circulation. No public stateful phrase
or cross-pad liquid history is added.

### HOLD format and evidence

HOLD powers a capacity-limited pump that returns drained material after a
finite delay, plus explicit maintenance contacts. It also starts with a
finite priming tank equal to 35% of the suspended liquid mass; that tank is
included in initial inventory and must pass through the delayed lift before
replenishing slots. Slots must actually refill before they release again.
Periodic opportunities constrain the release field to a nominal 2.4 second
material cycle. The period is snapped to a whole number of material-clock
ticks and output samples; its actual duration is recorded in each report.
Opportunities favor each region after its maintenance contact while retaining
the slot's local threshold requirement. Held release guards fit their
opportunity spacing so eligible slots do not suppress the following cycle;
one-shot guards retain their profile's characteristic spacing. HOLD uses a faster drain
baseline than a one-shot so retained corners can circulate; DRAIN still
changes that removal rate. This circulation supplies material and excitation
explicitly rather than assigning perpetual energy to the passive surface.

The engine carries acoustic and material state through at most ten preroll
cycles, requires two successive confirmations, and rechecks the next actual
exported cycle. Acoustic, material, audio and event-sequence differences must
all fall below 2e-5. Relative audio-cycle difference must also fall below
1e-4, so a quiet render cannot pass on absolute amplitude alone. The
independent-cycle seam must be below 1e-3, and boundary sample/slope errors
against the actual carried continuation, normalized to local signal RMS,
must each be below 1e-3.
Invalid circulation throws instead of returning an unvalidated loop or
applying a fallback crossfade. Internal reports preserve all four differences,
convergence, pump delay/capacity and the independent seam measurement.
An additional carried guard region gives the decimator actual future samples
at the end of the selected cycle.

The returned buffer contains two copies of the selected period. The public
sample buffer has no attack-plus-loop marker, so the initiating strike is
omitted. Repeating a buffer does not establish source convergence; the
independent cycle and full state reports are required separately.

### Kit and audition

The sixteen-pad kit uses FIRST, DRIP, CASCADE, POOL, RIPPLE, RECOVERY,
FIRST and DRIP on A01–A08, at MIDI 60, 63, 65, 67, 70, 72, 75 and 77:
C4, E♭4, F4, G4, B♭4, C5, E♭5 and F5. A09–A16 hold Heavy Landing, Slow
Drain, Dense Surface, Hanging Rain, Wet Basin, Thin Ripple, Clear Return
and the held Replenished Circle, at MIDI 48, 55, 63, 67, 48, 75, 70 and 60,
respectively. Every pad has a dry regeneration recipe.

Run:

```sh
./gradlew :synth:generateCisternAudition
```

Open `testkit/cistern-audition/index.html`. The page embeds its manifest for
local-file playback, loads no external services, offers raw/matched playback
and optional repeat, and stores listening notes in the browser with JSON
export. The generated folder is gitignored. Both versions use 24-bit PCM:
raw audio precedes melodic leveling, while matched audio uses the shared
`AuditionLevel` loudest-window rule without changing timing or spectral shape.

The roster contains the kit and presets, each voice at C2/C4/C6 with velocity
.25/.6/1, every macro at 0/.25/.5/.75/1 with declared neutral companions,
and the five requested 3×3 interaction grids. Diagnostic pairs cover isolated
strike, restrained cascade, identical contacts with full/frozen loading,
frame-only and drop-only output, all-high timbral settings, retained heavy
liquid, fast drainage, dense C6 impacts, an extended spent reservoir and
difficult HOLD corners, including quiet sparse circulation at C2, C4 and C6.
Drop-only output replays a real causal schedule
without its audible initiating strike; it does not manufacture a free-running
drop sequence.

`manifest.json` records peak, loudness, RMS, DC, duration, render timing and
estimated render-buffer storage, plus release/landing/secondary counts,
inventory error, load, energy and any non-finite recoveries. Selected
diagnostic clips also write event lineage and liquid/energy traces. HOLD
clips include structured convergence, event and independent-source seam
reports, including relative audio difference and the sample/slope match to
actual carried continuation. Before writing a held clip, the generator
independently verifies these acceptance fields and tests the actual last-to-first wrap after PCM24
quantization on both the raw and matched versions. The wrap's sample step
is compared with local first-derivative RMS, and neighboring slope changes
with local second-derivative RMS, using 256 samples on each side and explicit
quantization floors. Ratios above eight stop generation. Those numerical
click checks are reported in the manifest and page; they remain separate
from the owner's listening verdict. A failed rerender removes the old page
and manifest so they cannot falsely represent an incomplete matrix.
These measurements
support causal and stability review. The listening gate still determines
whether the evolving liquid surface is musically compelling, whether each
control is useful, and which proposed names and presets should ship.
