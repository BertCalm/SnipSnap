# Combination grammar: how new sounds get made from old parts

Josh's stated design stance: nothing is truly new; new instruments come from smashing ideas
together, changing the conditions an idea operates in, and porting a concept from one domain
to another. This file turns that stance into named operators you can apply deliberately, with
examples drawn from SnipSnap's own roster so the results stay in the house's register. The
foundation (dsp-catalog.md) is the parts bin. This is the assembly manual.

## 0. The anchor: the characteristic gesture

Every design starts and ends with one line in the form Josh's specs already use:

> The characteristic gesture is **A → B → C → D**.

(Skirl: knock → flutter → whirr → shudder → ringing stillness. Terrane: local touch →
neighboring response → branching resonance. Rung: tiny trigger → racing impacts → growing body
→ resonant landing.)

If an operator below does not change the gesture, it changed the timbre only, and that is a
preset, not an engine. If it changes the gesture, it may be an engine. Write the gesture line
first and let it veto.

## 1. The operators

### JUXTAPOSE (put two things side by side, unmixed)
Two mechanisms heard at once that do not interact, but whose contrast is the point.
- Use when the interest is in the difference (dry vs ringing, pitched vs noise, fast vs slow).
- Risk: it reads as a layer stack unless timing binds them (shared onset, shared breath).
- Roster example: LACQUER's four articulated layers; STOOP's answering shadow (same note, quieter, later).

### STACK (feed one into the next)
Series chain: exciter → body → shaper → space. The default of most sound design, and the
weakest operator for novelty on its own.
- Use when a known chain needs one unusual link (a slicer before a resonator, a chaos source into a bowed string).
- Risk: every link adds its own normalization and the chain forgets the energy budget.
- Roster example: FATHOM (source → drive → filter); MAGNET left dry for VALVE.

### SPLICE (take a stage from instrument X and graft it into instrument Y at the same functional slot)
Swap the exciter, the body, or the loss law, keep everything else.
- Use when X has a distinctive front end or back end: a reed driving a bell bank, a mallet striking a waveguide, a bow on a membrane.
- Rule: splice at the same slot (exciter for exciter). Splicing across slots (a body as an exciter) is COUPLE or INVERT.
- Roster example: ALLOY feeds a generated wood/tine/breath attack into an FM modulation port (an exciter spliced into a synth); TINES' FM bells with mallet decays.

### COUPLE (let two things exchange energy)
Bidirectional or sympathetic transfer: strings on a bridge, cells on a Laplacian, modes with
springs, owls answering strikes. This is where "third sound" lives (the Omnibus term: a timbre
that exists only while the coupling is on).
- Use when the interest is in response and memory, not the parts.
- Rule: name the coupling type (audio-rate vs control-rate; one-way readout vs two-way) and the stability bound.
- Depth bands borrowed from Omnibus: Whisper (0.05 to 0.15), Dialogue (0.2 to 0.45), Possession (0.5 to 0.8), Full Possession (0.82 to 1).
- Roster example: GYRE (strings on one bridge, sympathetics), MERCURY (water loading a modal bank), MURK (trunks through fog links), TERRANE proposal (Laplacian).

### MUTATE (change the conditions the idea operates in)
Keep the mechanism, change the medium, the scale, the gravity, the material, the temperature,
the air. Murk is a wood-strike engine mutated by "atmosphere thicker than Earth air". Thaw is a
rubbed plate mutated by "it melts".
- Use when a known model feels generic. Ask: what if the medium were viscous, hot, electric, granular, underwater, in a vacuum, on a moving platform, aging?
- Rule: the condition must enter the equations as a parameter with a law (viscosity → loss slope; heat → modal frequency drift and damping; size growth → mass and fall time). A condition that only changes a reverb is set dressing.
- Roster example: MURK (fog), THAW (heat and melt), RUNG proposal (growth), SPOOL (tape wear).

### INVERT (swap the roles)
The body becomes the exciter; the modulator becomes the carrier; the effect becomes the instrument; the loss becomes the source.
- Use when a chain is tired. A reverb that is played (OVERLAP), a pickup that drives a string, decay that triggers the next event (WICK's gate cascade), noise that is the resonator (SKIN's hats).
- Roster example: OVERLAP (FDN as the instrument), WICK (release triggers the next gate), CISTERN (the membrane's ring releases droplets that re-excite it).

### PORT (carry a concept from another domain as a literal model or as an analogy)
Physics, biology, geometry, mathematics, social systems. Two modes, and you must say which:
- **Literal**: the equations run (Euler's disk, dominoes, Voronoi cells, boids, reaction-diffusion, Kuramoto). The payoff is behaviour you could not have scripted; the cost is a solver, a stability story and a render-time budget.
- **Analogy**: the concept supplies a parameter law or a gesture shape, and the DSP is ordinary (a "half-life" decay law, a "critical mass" threshold that switches excitation regimes, "tidal" slow modulation). The payoff is a coherent macro story at near-zero cost.
- Rule: a literal port must state what is *not* simulated (Skirl: no air film, no elastic deformation) and what the acoustic readout is (one-way). An analogy must not claim physics.
- Roster example: FLOCK (boids, literal), VENT (Rössler, literal), SHOAL (energy transport, analogy), RELAY (a phrase clock, analogy of "handing off").

### DISTIL (take only the essence of several references)
For gear-stack prompts ("a sitar through an amp, a slicer, a fuzz, a Habit"): extract from each
reference the one behaviour that defines it, drop the rest, and build the smallest chain that
carries all the essences. Then ask whether the result wants to be a new gesture (an engine) or a
landing chain over an existing engine (a recipe). See gear-modeling.md.

### CROSS (breed two existing engines)
Take the exciter of one and the body of another, or the macro story of one and the mechanism of
another. Omnibus has preset-breeding strategies (Crossover, Blend, Dominant, Mutant); here the
operator works on architectures.
- Roster example: TESSERA (bars + string courses + tubes in one object) is a cross of several struck families.

## 2. Idea-to-DSP translation table

When a prompt is a concept rather than an instrument, map it before you design. Say whether the
mapping is literal or analogy.

| Concept given | Literal route (runs equations) | Analogy route (shapes a law) | Watch out |
|---|---|---|---|
| Gravity, falling, collapse | rigid-body or mass-spring with contact (Rung, Skirl) | accelerating event density with a finite energy budget | solver cost; energy injection by constraint stabilization |
| Heat, melting, freezing | temperature state drives modal frequency and damping (Thaw) | slow upward damping ramp; "melt" = loss slope increases over time | retuning a ringing resonator abruptly clicks |
| Viscosity, drag, thick air | loss ∝ velocity, travel delay ∝ density (Murk) | darker + slower envelopes | fog as "reverb amount" is set dressing |
| Pressure, suction, valves | reservoir state with flow through a nonlinear valve (Aerostat, Undertow) | an envelope that charges and releases | DC pressure leaking into audio |
| Growth, aging, wear | per-voice state that accumulates across the render (Spool wear, Olvido erosion) | time-indexed macro drift | state must be deterministic and seeded |
| Territory, cells, borders | Voronoi/Delaunay geometry with Laplacian coupling (Terrane) | a bank partitioned by nearest-neighbour weights | geometry out of the sample loop; stable IDs |
| Flocks, crowds, swarms | boids (Flock, Confluence's gathering) | chorus with cohesion/separation terms on detune | cohesion collapses to unison |
| Synchronization, entrainment | Kuramoto field on partials or oscillators | phase pull toward a reference with a strength knob | strong coupling locks instantly |
| Chaos, turbulence | attractor ODE as the audio or as modulation (Vent) | seeded random walks with a leash | timestep; DC; needs a pitch leash to be playable |
| Half-life, decay chains | parallel exponential decays feeding each other (Wick) | multi-stage release envelope | a chain that never ends is a loop, say so |
| Resonance, standing waves | waveguide or modal (literal by definition) | peak EQ | which is which |
| Interference, beating | two detuned sources, or coupled modes (Flock, Registers MUSETTE) | AM at the beat rate | beat rate must be a musical quantity |
| Diffusion, erosion | spectral blur over time (Overwash), high-first loss (Olvido) | lowpass that closes with time | spectral ops need PGHI-style phase care |
| Memory, forgetting | a buffer the instrument reads and degrades (Ombre, Orphica) | a feedback delay with loss | determinism of the buffer |
| Social systems (call and response, agitation, negotiation) | behavioural states per agent with latency, refractory time, budgets (Murk's owls, Circuit) | a phrase clock with accents | "a wildlife layer" bolted on |
| Fractals, self-similarity | partials or event schedules at ratio r^n (Rung's growth) | nested envelopes | exponential growth ratios (Rung's 246 million) must stay in diagnostics, not amplitude |
| Quantization, lattices, crystals | modal ratio tables from lattice geometry (Opaline, Obsidian) | snapped FM ratios | "crystal" is not a mode table without a source |

## 3. The option discipline: one strong, two to consider

For any design request, return exactly three directions unless the user asked for one:

1. **Strong**: the direction you would build. State in one sentence why it wins (usually:
   clearest gesture, lowest risk per unit of novelty, best fit to the roster gap).
2. **Alternate A**: a different operator applied to the same brief (if Strong is a COUPLE,
   make A a MUTATE or an INVERT).
3. **Alternate B**: the riskier or stranger reading, with its risk stated plainly.

Score each on: **gesture clarity** (can you say A → B → C), **novelty against the roster**
(name the two nearest siblings and the one thing they do not do), **risk** (solver, stability,
render time, trademark), and **cost** (build rounds). Keep the scoring to one line each; the
reasoning goes in the design, not in a table of adjectives.

## 4. Sibling check (do this before calling anything new)

Grep engine-inventory.md for the mechanism and for the macro words. If two shipped engines
already cover the gesture, say so and propose the design as a voice or a preset family of the
nearest one. The roster is 66 engines deep; the house's own roadmap notes six of the last ten
share the "physically inspired, five macros plus HOLD, six voices" mould. A new engine earns a
pad by doing something the siblings cannot: a different gesture, a different sample-side
behaviour (loop vs one-shot), or a different place in a kit (bass register, drum class).

## 5. Worked example (short)

This is a shape, not an answer. If the brief in front of you resembles it, re-derive every
step against the repo as it stands (the sibling check especially; the rack gains sections),
and expect a different Strong.

Brief: "a sound like a sitar through a guitar amp, a slicer pedal, a fuzz, and a reverse/looping
delay."

DISTIL: sitar = jawari buzz on a plucked string plus sympathetic strings; amp = asymmetric
saturation with a speaker's bandpass; slicer = tempo-locked gating with soft edges; fuzz =
hard asymmetric clipping with gated decay; reverse/looping delay = a buffer read backward with
feedback.

Sibling check: SILK/PLUCK already own the jawari string; VALVE and CRUNCH own the amp and
fuzz; there is no slicer and no reverse delay in `FxChain` (REVERSE is a whole-pad flag).

Strong: a **recipe plus one new rack section**, not an engine. PLUCK SITAR → new SLICE
section (soft-edged gate at a musical division, bakes into the render) → CRUNCH → VALVE →
ECHO with a reverse read. The gesture (pluck → chopped buzz → smeared backward tail) is new,
and it lands in a slot the pedal-board spec already reserves.

Alternate A (INVERT): the slicer gates the *excitation*, not the output: a sitar string
re-plucked at the slice rate while it still rings, so each slice has a new jawari transient.
That is an engine voice (SILK gains a STROBE voice), and HOLD can loop it.

Alternate B (COUPLE): the fuzz's rectified output drives the sympathetic strings of the sitar
(a pickup that feeds the body), so the fuzz "plays" the resonance. Novel, risky (feedback
bound), and the most "third sound".

Then: signal-flow diagram, macro contract, prototype the Strong, probe it, spec it.
