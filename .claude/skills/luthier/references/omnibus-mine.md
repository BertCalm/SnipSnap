# The Omnibus mine: XOceanus (XO_OX-XOmnibus) as an idea and code source

Repo: `github.com/BertCalm/XO_OX-XOmnibus` (branded XOceanus). C++/JUCE, 93 engines in source
(README says 86), MIT licensed ("Copyright (c) 2026 XO_OX Designs"; SPDX headers on sources).
SnipSnap's Aquarium port pins commit `a9bdc78`. Lineage runs one way: SnipSnap cites Omnibus,
Omnibus never mentions SnipSnap. This file is a map so you can find the juice fast.

## Where things live

| What | Path |
|---|---|
| Each engine | `Source/Engines/{Name}/{Name}Engine.h` (the .cpp files are one-line stubs). Exceptions: `Outwit/XOutwitAdapter.h` + `Outwit/DSP/`, `Overlap/XOverlapAdapter.h` + `Overlap/DSP/`, `Opera/OperaEngine.h` (+ `KuramotoField.h`), `Oxbow/OxbowAdapter.h`; Oxytocin, Oxidize, Ocelot, Owlfish, Offering, Overworld split across several headers |
| Engine interface and `CouplingType` enum | `Source/Core/SynthEngine.h` (JUCE-free copy in `SDK/include/xoceanus/`) |
| Shared DSP primitives | `Source/DSP/` (mostly JUCE-free) and `Source/DSP/Effects/` (27 `*Chain.h` effect chains) |
| Engine docs | `Docs/engines/` (53), `Docs/concepts/` (80 briefs), `Docs/specs/` (217) |
| Fleet references | `Docs/specs/xoceanus_sound_design_guides.md` (71 engines, one section each), `Docs/engines/engine_identity_cards.md`, `Docs/engines.json` (the roster), `Docs/genomes/*.xogenome`, `.claude/context/dsp-patterns.md` |
| Coupling | `Source/Core/MegaCouplingMatrix.h`, `Source/Core/CouplingCrossfader.h`, `Docs/cookbooks/coupling-cookbook-v1.md`, `Docs/coupling-recipes/`, `Docs/specs/knot_coupling_spec.md` |
| Doctrines | `Docs/doctrines/D001..D006`, `knowledge/doctrines/DOC-001..003` |
| Skills | `Skills/*` and `.claude/skills/*` |
| New-engine process | `Docs/specs/xoceanus_new_engine_process.md` (concept brief template §0.3, integration spec template §3.2) |

## The 15 most reusable shared files (JUCE-free unless noted)

1. `Source/DSP/FastMath.h`: denormal flush, fast exp/tanh/sin/pow2, `midiToFreq`, `softClip`, smoothing coefficients.
2. `Source/DSP/CytomicSVF.h`: Simper topology-preserving SVF.
3. `Source/DSP/PolyBLEP.h`: band-limited saw, square, triangle.
4. `Source/DSP/WavetableOscillator.h`: multi-frame morphing wavetable, cubic Hermite.
5. `Source/DSP/FamilyWaveguide.h`: delay line, damping, body resonance, sympathetic bank, drift, seven exciters (pluck, strum, pick, air jet, reed, lip buzz, bow).
6. `Source/DSP/StandardADSR.h`, 7. `Source/DSP/StandardLFO.h` (includes `BreathingLFO` for 0.01 Hz motion).
8. `Source/DSP/ShoreSystem/ShoreSystem.h`: five coastline regions with folk-instrument resonator profiles (already ported as `OspreyShore.kt`).
9. `Source/DSP/GardenAccumulators.h`: per-voice evolution state and a cross-voice event network.
10. `Source/DSP/Effects/AquaticFXSuite.h` (JUCE): six stages (already ported as Aquarium).
11. `Source/DSP/Effects/LushReverb.h`: 8-tap Hadamard FDN.
12. `Source/DSP/Effects/Combulator.h`: tuned comb bank with noise exciter.
13. `Source/DSP/Effects/AttractorDrive.h`: saturation driven by a Lorenz attractor.
14. `Source/Engines/Overlap/DSP/FDN.h` + `KnotMatrix.h` + `Entrainment.h`: knot-routed FDN with Kuramoto entrainment.
15. `Source/Engines/Opal/OpalDSP.h`: grain buffer and grain pool.

Primitive index by kind: filters (`CytomicSVF.h`; Huovilainen ladder in
`Oxytocin/OxytocinReactive.h`); waveguides (`FamilyWaveguide.h`, `Ocelot/KarplusStrong.h`,
bodies in Ostinato and OceanDeep, Ooze pipe); modal banks (`Ocelot/ModalResonator.h`, Onset,
Organon `ModalArray`, Oven `OvenModalResonator` + `OvenSympatheticNetwork`, Oware, Ostinato);
FDN/reverb (LushReverb, Overlap, Oxbow Householder, Oobleck, Ooze, Oxidize); chaos (Ouroboros
`AttractorState`: Lorenz, Rössler, Chua, Aizawa with RK4 at 4×; `Shapers/Oxide`; Oracle GENDY);
boids (Oceanic `Particle`, Oort `FlockAgent`); cellular automata (Organism 1D Wolfram, Outwit
arms + GA, Obiont 1D/2D Life as waveform, Oobleck Gray-Scott); granular (Opal, GranularSmear,
Owlfish MicroGranular, Orphica, Ombre memory buffer); synchronization (Opera `KuramotoField.h`,
Overlap `Entrainment.h`).

## Engine families (idea in under twelve words; doc under `Docs/`)

**Waveguide strings and winds:** Ohm (strings + spectral interference + FM), Orphica (plucked harp with per-voice grains), Obbligato (air-jet flute and reed, paired), Ottoni (three lip-buzz brass), Ole (plucked trio with sympathetic bank), Oaken (KS upright bass, 3-mode body), Overgrow (solo KS string that responds to silence), Obscura (scanned mass-spring chain), Ogive (scanned glass lattice, Verlet), Ooze (waveguide pipe, Reynolds jet, Minnaert bubble), OceanDeep (waveguide body bass with bio exciter).

**Modal and resonator:** Oven (16 modes, cast-iron grand), Ochre (16-mode copper upright, Hunt-Crossley hammer), Obelisk (inharmonic prepared piano), Opaline (glass and porcelain ratio tables), Oware (modal bars, mallet, buzz membrane, wood-to-metal axis), Ostinato (modal membranes + waveguide bodies, 12 cultures), Onset (8-voice drums with 8×8 cross-voice matrix), Osprey (turbulence-modulated resonators, ShoreSystem), Osteria (ensemble formant resonators with timbral memory), Organon (modal array driven by free-energy "metabolism").

**Electric pianos:** Oasis (tine into resonator bank), Okeanos (tine and pickup), Oddfellow (reed and pickup), Onkolo (pad-struck clavinet string), Opcode (2-op FM EP).

**Organs:** Oto (four mouth organs), Octave (four historic organs with wind noise; ported as REGISTERS), Oleg (Eastern European organ and bellows; ported as BELLOWS), Otis (tonewheel flagship).

**Subtractive and character:** Oblong, Obese (13 oscillators into ZDF ladder with drift), OddOscar (3 morphing oscillators into Moog ladder), OddfeliX (sine+noise, FM or KS with pitch snap), Odyssey (random-walk drift pad), Overbite (two-osc bass "Belly" and "Bite"), Overdub (dub voice with tape echo and spring), Olate (2-osc with aging model), Ogre (sub-bass with gravitational pull on other engines), Oxytocin (RE-201 / MS-20 / ladder circuit models driven by a love-triangle state).

**FM and phase:** Omega (2-op FM bass reducing toward the carrier), Obsidian (crystalline phase distortion), Observandum (phase synthesis over facets).

**Additive and spectral:** Orbital (64 partials with group envelopes), Overtone (partials from continued fractions of π, e, φ, √2), Oxalis (φ^n partials), Origami (FFT spectral folding; ported), Octant (3D tensor of 256 partials), Overtide (wavelet multi-resolution), Olvido (spectral erosion, highs fade first), Opera (additive vocal partials with Kuramoto sync).

**Wavetable, formant, terrain:** Orca (wavetable + echolocation comb + sidechain breach), Outlook (dual wavetable, parallax stereo), OpenSky (supersaw, shimmer, Shepard rise; ported), Ondine (formant vocal tract), Ortolan (VOSIM pulse trains), Outcrop (wave terrain), Orbweave (4 oscillators braided by a knot matrix), Oblique (wavefolder, bounce clicks, 6-tap prism delay), Ouie (two voices, 8 algorithms, interaction stage).

**Slow pads (Broth) and growth (Garden):** Overwash (Gaussian diffusion of partials), Overworn (spectral reduction over a session), Overflow (pressure accumulator with release valve), Overcast (spectrum frozen at note-on), Orchard (4-saw ensemble whose harmonics grow over 5 to 30 s), Osier (2-saw quartet with pitch attractor).

**Chaos and stochastic:** Ouroboros (Lorenz/Rössler/Chua/Aizawa with leash to pitch; ported as VENT, Rössler only), Oracle (GENDY with maqam tuning), Onda (nonlinear Schrödinger solitons).

**Swarm, CA, emergent:** Oceanic (128 boid oscillators; ported as FLOCK), Oort (boid agents on an amplitude axis + Poisson grains), Organism (16-cell Wolfram CA), Outwit (8 CA arms + genetic algorithm), Obiont (CA cells as the waveform), Oobleck (Gray-Scott reaction-diffusion as wavetable), Opsin (6 self-oscillating nodes exciting each other), Octopus (8 prime-ratio LFOs, envelope follower, freeze).

**Granular, buffer, memory:** Opal (granular with audio input buffer), Ombre (memory buffer that decays on read), Ostracon (shared tape buffer with degradation), Ollotron (tape-chamber keyboard with per-key wear), Owlfish (subharmonic Trautonium + micro-granular), Outflow (transient prediction drives granular and FDN), Oxidize (per-voice aging + sediment FDN).

**Effects as engines, utilities, hybrids:** Overlap (knot FDN with Kuramoto entrainment; ported), Oxbow (8-channel Householder FDN with phase erosion), Osmosis (analyzes external audio, outputs coupling signals), Optic (no audio, only modulation), Orrery (4-source vector pad with orbit paths), Ocelot (physical percussion, sample mangler, spectral pad, formant calls), Offering (boom-bap drums: transient, texture, collage, city processing), Obrix (runtime-configurable modular bricks), Overworld (six chip engines blended on a triangle).

## Already adapted into SnipSnap (do not re-propose as new)

BELLOWS ← Oleg · FLOCK ← Oceanic · OHM ← Ohm · ORIGAMI ← Origami · ORPHICA ← Orphica ·
OSPREY ← Osprey (+ ShoreSystem tables) · OVEN ← Oven · OVERLAP ← Overlap · REGISTERS ← Octave
· TRIO ← Ole · VENT ← Ouroboros (Rössler only) · SCANNED CHAIN ← Obscura · OPENSKY ← OpenSky ·
WIDE ← Epic Chains spec · BRASS GROW ← Ottoni guide · Aquarium ← AquaticFXSuite · XPN writer ←
`Tools/xpn_packager.py`. OBBLIGATO shares the name and idea but cites no source; ONSET shares
the name but the link is unconfirmed.

Unmined, and a good fit for SnipSnap's offline, one-shot, pitched contract: the bass family
(Oaken KS upright with a 3-mode body, Ogre sub-bass, Overbite two-osc "Belly" and "Bite",
Omega 2-op FM reducing toward the carrier; SnipSnap's bass register is FATHOM alone), Oware (mallet with
buzz membrane), Ochre (Hunt-Crossley hammer), Ooze (Minnaert bubbles), Oracle (GENDY), Onda
(solitons), Oobleck (reaction-diffusion), Obiont (CA as waveform), Opera (Kuramoto partials),
Ortolan (VOSIM), Outcrop (wave terrain), Olvido (spectral erosion), Overflow (pressure
accumulator), Ombre (decaying memory buffer), Ouroboros's other three attractors.

## Coupling vocabulary worth reusing

The `CouplingType` enum has 15 types. Control rate (about 1.5 kHz): AmpToFilter, AmpToPitch,
LFOToPitch, EnvToMorph, EnvToDecay, AmpToChoke, FilterToFilter, PitchToPitch, RhythmToBlend.
Audio rate: AudioToFM, AudioToRing, AudioToWavetable, AudioToBuffer. Special: KnotTopology
(mutual, linking number 1 to 5), TriangularCoupling (block-rate love-triangle state).

Depth bands (`coupling-cookbook-v1.md`): Whisper 0.05 to 0.15, Dialogue 0.20 to 0.45,
Possession 0.50 to 0.80, Full Possession 0.82 to 1.00. "Third sound" = the timbre that exists
only while two things are coupled. Tension vs resonance coupling = pairing opposite poles vs the
same pole of the bright/warm axis. Preset breeding strategies: Crossover, Blend, Dominant,
Mutant, interpolation (`.claude/skills/preset-breed.md`, `Tools/breed_presets.py`).

Names that appear in docs but not in the enum (Gravitational, Adversarial, Symbiotic,
Parasitic, Growth, Flux, ChaosInject, VelocityCoupling, EnvelopeFollow, RingMod): behaviours
inside specific engines or unbuilt designs. Cite them as ideas, not as APIs.

Example recipes: ONSET → OVERBITE (AmpToFilter 0.5 + AudioToFM 0.3 + EnvToDecay 0.2, "Drum
Teaches Bass"); OUROBOROS → OVERBITE (AmpToFilter + AmpToPitch, "Attractor Bassline"); ODYSSEY →
OPAL (EnvToMorph + AmpToFilter); ODDFELIX → ORBITAL (AudioToRing + LFOToPitch); OCEANIC ↔ OCTOPUS
(KnotTopology both ways); OXYTOCIN → OWARE (TriangularCoupling + AmpToFilter); OBLONG → OMEGA
(LFOToPitch + FilterToFilter, "Reese Feedback"); OSTINATO → OWARE (EnvToMorph 0.2 to 0.45).

Inside one SnipSnap engine these become **internal couplings**: the sidechain idea (Orca's
breach), the envelope-to-morph idea, the knot topology idea (OVERLAP already has it). A
"coupling" that crossed engines in Omnibus becomes a second voice, a second layer, or a
HOLD-adaptation inside one offline render here.

## The doctrines (useful as review criteria)

D001 velocity shapes timbre, not just level · D002 modulation is lifeblood (something always
moves) · D003 physics is synthesis: cite sources, use physically meaningful parameters · D004
no dead parameters: every declared control audibly changes the output · D005 engines must
breathe: at least one very slow motion wired to something audible · D006 expression required ·
DOC-001 filters need key tracking · DOC-002 the default patch must invite play · DOC-003 an
architecture without presets is just parts.

SnipSnap's own rules override where they conflict: motion bakes into the render (no live
LFOs), 3 to 6 macros (no mod matrix), delight stays out of the signal path.

## Omnibus skills to link rather than duplicate

`Skills/new-xo-engine/SKILL.md` (concept → architecture → scaffold → integration →
verification, for the C++ side), `Skills/engine-health-check` (quick D001 to D006 check),
`Skills/engine-qa-gate` (starts at 0/10, awards points only with evidence),
`.claude/skills/seance` (panel-style evaluation), `.claude/skills/preset-breed.md`,
`.claude/skills/coupling-design.md`, `Skills/research-team` (prior-art scouting),
`.claude/context/dsp-patterns.md` (parameter snapshots, denormals, smoothing). There is no
audition skill in Omnibus; SnipSnap's audition generators are the model.

## How to mine (the procedure)

1. Start from the idea, not the file: `grep -ril "<concept>" Docs/engines Docs/concepts
   Docs/specs/xoceanus_sound_design_guides.md` then open the engine header it points to.
2. Read the header's comment block and the `process` method before anything else. The
   algorithm is usually 40 to 200 lines; the rest is JUCE parameter plumbing.
3. Separate the **mechanism** (what to port), the **parameter law** (how a knob maps; often
   worth keeping), and the **realtime scaffolding** (voice pools, smoothing, denormals; drop
   it, SnipSnap renders offline).
4. Adapt, don't vendor: rewrite the mechanism in Kotlin against `Dsp`/`Modes`/`Strings`, and
   credit the source in the KDoc the way `Bellows.kt` does ("XO_OX Designs, OlegEngine.h,
   Copyright (c) 2026 XO_OX Designs, MIT License").
5. Record the Omnibus commit hash you read, so the lineage is reproducible.
