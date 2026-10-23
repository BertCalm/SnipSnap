# DSP technique catalog: the tried and tested foundation

Organized by the role a block plays in an instrument, because that is how you assemble one:
something excites, something resonates, something couples, something loses energy, something
shapes the output, something changes over time. For each technique: what it is good at, where
it breaks (so you can recognize a broken or suboptimal model when reviewing), its cost, and
what SnipSnap already has. Sources at the end. Keep claims honest: a technique "sounds like X"
is a listening claim; an aliasing bound is a measurement claim.

## 1. Exciters (where energy enters)

| Technique | Good at | Breaks when | Cost | In SnipSnap |
|---|---|---|---|---|
| Impulse / short noise burst (KS pluck) | plucks, taps, snaps | burst too long smears the attack; too white sounds synthetic. Shape it (lowpass by pick position, comb by position) | trivial | `Strings.pluck`, `Modes.ring` inputs |
| Raised-cosine / Hann force pulse | mallets, hammers, soft knocks; width sets brightness | width fixed regardless of velocity or size (a big block and a small block should not share a kernel) | trivial | Terra, Tessera, Murk |
| Hunt-Crossley or nonlinear spring hammer (`F = k·x^α(1 + λẋ)`) | piano, mallet realism: harder hits get shorter, brighter contacts by physics | explicit integration with a stiff spring: use small substeps or implicit solve | moderate | Omnibus Ochre; none in SnipSnap |
| Bow (stick-slip friction, McIntyre-Schumacher-Woodhouse) | sustained strings, rubbing, scrapes | hysteresis loop needs the implicit solve (`Modes.Friction`); naive friction tables "stutter" | moderate | `Strings.Bow`, Arco, `Modes.Friction` |
| Reed table (Smith passive reflection) and air jet (Verge/Cook flute) | woodwinds, voiced pipes | the valve's DC pressure leaks into the output (BORE lesson: listen to the bore wave, not the injected sample) | low | Bore, Embouchure |
| Lip buzz (mass-spring valve) | brass | needs the bore's own feedback to find pitch; blown open at high pressure | moderate | BrassGrow prototype |
| Rolling / traveling contact (periodic texture map read at contact phase) | rolling, scraping, gear teeth, shudder | reading the texture with the wrong phase (body phase vs contact azimuth) is the classic error (Skirl §12) | low once the phase is right | Pitchwheel, Skirl proposal |
| Droplet / event fields (Poisson or deterministic schedule of micro-impacts) | rain, droplets, gravel, cascades | a random click scheduler with no physical envelope reads as "FX layer bolted on". Tie event density to a modeled quantity | low | Cistern, Flotilla, Murk's owls |
| Turbulence noise shaped by pressure and position | breath, wind, water, chiff | noise that does not follow the excitation envelope reads as hiss | low | Bore chiff, Osprey |
| Granular source (grains of noise, of a wavetable, of the instrument's own history) | microsound, textures, "sustain breaking into grains" | grain envelope clicks (always window), phase incoherence when you want pitch | moderate | Orphica, Grains, Omnibus Opal |

Velocity rule (Omnibus D001, SnipSnap `brightnessOverride`): velocity must change contact
duration, spectral weight or event energy, never level alone.

## 2. Resonators and bodies (what rings)

| Technique | Good at | Breaks when | Cost | In SnipSnap |
|---|---|---|---|---|
| Modal synthesis (bank of 2-pole resonators, each `ratio, gain, t60`) | bars, plates, bells, membranes, bodies; exact decay control; cheap | ratios copied from a table with no source read as "generic bell"; peak scales with `1/sin(θ)` so low notes and 4× rates get loud (peak-normalize); modes above Nyquist must be skipped | N modes × 1 biquad | `Modes.ring`, `Modes.Bank`, Skin, Terra, Mercury, Nimbus |
| Coupled modal bank (springs between modes, `Modes.Bank.connect`) | sympathetic ringing, water loading, beating, "third sound" | kappa sum over the stability bound explodes; retuning must preserve energy | N² worst case, sparse in practice | Mercury, Corolla |
| Digital waveguide / Karplus-Strong (delay + loop filter) | strings, bores, tubes; pitch exact in whole + fractional samples | integer delay only (pitch steps), loop filter phase detunes (budget it: `Strings.tune(roundTrip)`), dispersion needs allpass | one delay line | `Strings.Loop`, Pluck, Silk, Bore, Gyre |
| Stiff string (dispersion allpass chain, inharmonicity B) | piano, bars-as-strings, sitar precursors | too much dispersion smears the attack into a "boing"; B must be sourced | +allpasses | `Modes.stiffString`, Oven, Silk |
| Banded waveguide (one waveguide per mode) | bowed bars, glass, bowls | more expensive than modal for the same spectrum unless you need the sustained-excitation interaction | N delay lines | none shipped |
| Mass-spring networks (Verlet, scanned synthesis) | evolving shapes, haptic-feeling motion, "wavetable that moves" | explicit integration unstable at high stiffness×dt; use Verlet with capped dt | N masses per sample | Scanned Chain |
| Graph Laplacian network of oscillators (Terrane's `H = diag(ω) + L`) | energy transfer across a topology; keeps the uniform mode at the root (`L·1 = 0`) | row-normalizing destroys symmetry (and passivity); hash-order iteration breaks determinism | sparse N | Terrane proposal; Flock's neighbour rules are related |
| Feedback delay network (orthogonal/Householder/Hadamard matrix) | rooms, chambers, knot resonators, "FX as instrument" | gain over unity in any eigen-direction grows; a limiter hiding it is a bug, not a design | 4 to 16 delays | Overlap, SPRING section, Omnibus LushReverb/Oxbow |
| Formant filters (parallel bandpass / Klatt) | vowels, "mouth", nasal character | formants that do not move with pitch read as a vocoder; keep formants fixed in Hz while pitch moves | few biquads | Vox, Murmur |
| Tube/cone with tonehole lattice | woodwind realism | tonehole scattering is fiddly; usually a single-cone approximation is enough for a one-shot | moderate | Bore (single cone) |
| Air cavity / Helmholtz (one resonance with a port) | hollow bodies, vessels, "cavity" macros | one resonance alone is a wah; pair it with 2 to 4 shell modes | trivial | Terra cavity, Suture, Murk |

## 3. Sources (pitched oscillators and spectra)

| Technique | Good at | Breaks when | Cost | In SnipSnap |
|---|---|---|---|---|
| PolyBLEP / BLIT (band-limited classic waves) | analog-style saw, square, pulse | discontinuity in the derivative still aliases at extreme pulse widths; fine at 4× | trivial | Siren, Stoop, `Dsp.square` is naive (do not use raw) |
| Wavetable with mip-mapped tables | any periodic spectrum, morphing | no mip map = aliasing on high notes; cubic interpolation, not linear, above 2 kHz | low | Contour, Snap |
| Phase distortion / phase shaping (Casio-style, Glint's windowed burst) | formant-like sweeps without filters, "PD" character | sharp phase maps alias; soften the knee or oversample | trivial | Glint, Crease |
| FM / PM (2 to 4 operators) | bells, tines, EPs, metallic, vocal | ratio drift from float phase accumulation (snap ratios); deep index aliases (oversample) | trivial | Tines, Alloy, Murmur, Tangle |
| Additive (partial banks with group envelopes) | bells, organs, spectral morphs, inharmonic sets | hundreds of partials per sample is costly; partials above Nyquist must be dropped per note | N sines | Prism, Registers, Tonewheel |
| Wavefolding (West Coast) | brassy, hollow, odd-harmonic growth with drive | folds alias hard; needs 4× to 8× or ADAA (antiderivative antialiasing) | trivial | Tide |
| Chaos (Rössler, Lorenz, Chua, Aizawa; RK4) | living drones, breath, unrepeatable-sounding but seeded | timestep too large diverges; a "leash" to pitch is what makes it playable | 3 to 4 ODEs/sample | Vent, Omnibus Ouroboros |
| GENDY (dynamic stochastic waveform, Xenakis) | rough, breathing noise-tones | breakpoint random walks go DC; bound them | trivial | none (Omnibus Oracle) |
| VOSIM (pulse-train of decaying sine bursts) | vocal-ish, formant-rich pulses | aliasing at high pitch | trivial | none (Omnibus Ortolan) |
| Wave terrain (orbit over a 2D function) | evolving timbres from geometry | terrain discontinuities alias; keep the surface smooth | low | none (Omnibus Outcrop) |
| Cellular automata / reaction-diffusion as waveform or wavetable | alien, evolving textures | not pitched unless the cell count maps to period; DC and aliasing from hard cell states | low to moderate | none (Omnibus Obiont, Oobleck) |
| Boids / swarm oscillators | chorus-that-breathes, clouds, crowd | detuning pulled by cohesion collapses to unison (needs separation); 24 to 128 oscillators | N | Flock |
| Kuramoto-synchronized oscillators | partials that lock and unlock, choirs | strong coupling locks instantly (boring); keep coupling near the critical value | N² naive | none (Omnibus Opera) |

## 4. Nonlinearity and shaping

| Technique | Good at | Breaks when | Cost | In SnipSnap |
|---|---|---|---|---|
| Soft clip / tanh / asymmetric curves | warmth, fuzz, "bark", pickup saturation | aliasing above a few dB of drive at 1×; 4× plus band-limit before decimation is the house minimum; ADAA for hard knees | trivial | Fork BARK, VALVE, CRUNCH |
| Fold-back / jawari (one-sided limit that shortens the loop) | sitar buzz, sawari, bridge rattle | the dynamically shortening wrap and the static fold-back are different models (SILK S18 lesson); say which | trivial | `Strings.Loop.jawari` |
| Collision (unilateral contact with restitution) | rattles, buzzing membranes, bead trays, domino chains | penetration and tunneling at large dt; energy injection from constraint stabilization | moderate to high | Tremor beads, Rung proposal |
| Pickup curve (nonlinear position readout) | EP "bark", magnetic pickups | symmetric curve kills the even harmonics the pickup should add | trivial | Fork, Magnet |
| Hysteresis (tape, transformer, Preisach/Jiles-Atherton) | tape saturation with memory | expensive if solved exactly; the cheap version is a one-state lag | moderate | TAPE section (ChowDSP-inspired) |
| Bit/rate reduction, slicing (gated on/off patterns) | chip, stutter, slicer pedals | hard gates click (ramp 1 to 5 ms); a slicer whose rate is not a musical division sounds broken | trivial | CRUNCH, none for slicing |

## 5. Energy flow, coupling and loss (what makes a sound a gesture)

- **One-way readout** (mechanics computed first, audio reads it): deterministic, cacheable,
  cheap to re-render for pitch. All three pre-skill specs and most shipped ecosystem engines
  use it. Breaks when the "readout" quietly injects energy (random clicks, re-normalized late
  pulses).
- **Two-way coupling** (resonator feeds back into the exciter: bow, reed, lip, string-bridge):
  the realism of self-sustained instruments; needs an implicit or iterative solve at the
  junction and a stability argument.
- **Passivity by construction**: a network whose unforced norm cannot grow (Hermitian H with
  positive damping, orthogonal FDN matrices, kappa under the bound). The house wants feedback
  "bounded by construction rather than by a limiter" (Gyre G3).
- **Energy budgets**: more events must not mean more power. Hold mean excitation power fixed
  and scale per-event energy down as rate rises (Skirl §13). Scale by a physical proxy (normal
  impulse × relative speed, resistance power), then map through one bounded monotonic
  soft-knee curve with a fixed reference. Never normalize each event independently.
- **Loss laws**: frequency-dependent loss (loop filter), viscous (velocity-proportional),
  Coulomb/regularized friction torque (`τ = -μ·N·R·ω/√(ω²+ε²)`), radiation loss (grows with
  frequency). Name which one you used; "damping" alone is not a model.
- **Sympathetic resonance**: a bank tuned to related pitches driven by a bridge signal at low
  gain. Cheap "third sound"; keep it under the root so pitch stays clear.

## 6. Time structure and motion (what changes over seconds)

Because SnipSnap bakes motion into the render, every design needs a **gesture** (the specs'
"characteristic gesture" line) with a beginning, an evolution and an ending:
- Mechanical trajectories (a disk losing energy, a chain of blocks falling, a flower's hinge).
- Slow states (agitation, fog compression, heat, water level) that gate or shape excitation.
- Phrase clocks (Relay, Revel, Circuit): a schedule of events with a shared breath.
- Growth and decay of spectra (Prism groups, Orchard-style harmonic growth, Olvido erosion).
- Memory (a buffer the instrument reads from and degrades: Ombre, Spool, Orphica).

HOLD strategies that have shipped: whole-period loops planned to the note (Mercury's
`planLoop`, retune passes until the seam closes), a loop cut from the held middle of a long
one-shot with a power-constant blend (Bore round 7, the owner's ear chose it), a reserved top
step (Gyre), a refusal (Nacre). A loop that holds a mechanical gesture at a fixed state is a
"driven adaptation", not the physics continuing; say so.

## 7. Output hygiene (the part reviewers check first)

4× oversampling → band-limit (`Tide.bandLimit`) → decimate → remove DC (mean then 20 Hz
high-pass) → level to the loudness target → 4 ms tail fade. Oversampling does not make
arbitrarily sharp pulses safe: band-limit excitation kernels before they enter resonators.
Denormals: left alone on purpose (documented). True peak exists but is not wired. A final
limiter must never hide internal growth.

## 8. Recognizing broken or suboptimal models (review heuristics)

1. A "physical" parameter that is really a mix knob (BODY as a cutoff, FOG as delay time
   alone). Ask what quantity it changes in the model.
2. A scheduled crescendo pretending to be mechanics (fixed inter-onset ramps, per-event
   normalization). Ask where the energy came from.
3. Two clocks that should be one (body rotation vs contact azimuth; musical time vs solver
   time). Ask which phase drives the audible event.
4. Feedback bounded by a limiter. Ask for the passivity argument.
5. Constants with no provenance. Ask: measured, sourced, or shape?
6. A model whose cost scales with a count the macros can raise without bound.
7. Pitch that is a side effect (modal ratio tables that move the root when a material knob
   turns). Ask for the root-compensation rule.
8. "Oversampled" designs that inject an unbandlimited pulse at the oversampled rate.
9. A HOLD loop that is periodic by construction and then "measured" against itself (a loop
   played twice is tautologically periodic; measure the kept stretch against its continuation).
10. Presets authored before the voice was heard.

## Sources (reference-grade, mostly free)

- J. O. Smith, *Physical Audio Signal Processing* (online, CCRMA): waveguides, scattering,
  reed/bow/jet models, commuted synthesis.
- Bilbao, *Numerical Sound Synthesis*: finite-difference and modal schemes, stability
  conditions, collisions (the energy-method stability arguments the house asks for).
- Zölzer (ed.), *DAFX: Digital Audio Effects*: filters, modulation, nonlinear processing,
  spatial.
- Pirkle, *Designing Audio Effect Plugins in C++* and *Designing Software Synthesizer
  Plugins*: practical architectures.
- Cook, *Real Sound Synthesis for Interactive Applications*: modal/physical shortcuts.
- Välimäki et al., "Discrete-Time Modelling of Musical Instruments" (survey).
- Andy Simper, "Solving the continuous SVF equations" (Cytomic TPT SVF).
- Parker, Zavalishin, "Reducing the aliasing of nonlinear waveshaping using continuous-time
  convolution" (ADAA).
- Vesa Välimäki, "Antialiasing oscillators in subtractive synthesis" (PolyBLEP family).
- Moffat & Reiss, procedural audio literature for rolling/impact/droplet schedules.
- Stanford STK, Faust `physmodels.lib`, DaisySP, Mutable Instruments firmware, ChowDSP,
  Omnibus: implementations to read (see reuse-and-licenses.md before copying anything).
- Allen Downey, *Think DSP* (Python notebooks): the gentlest route into spectra, filters and
  convolution for someone newer to code; good for prototyping intuition, not for synthesis
  architecture.
