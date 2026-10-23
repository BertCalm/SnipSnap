# SnipSnap engine inventory (2026-10-21)

66 engines registered in `Patches.kt` plus a handful of unregistered synthesis objects. The repo
has **no family taxonomy**: `Patches.kt`, `SynthKits.kt` and the phone picker are flat lists.
The families below are the skill's own grouping by mechanism, used for sibling checks and gap
scans. Re-grep the macros before quoting them (`macrosFor` in each engine file).

Legend: T = TONAL, L = LOOP, P = PERC, T/L = TONAL then LOOP at HOLD's top, P/L = PERC then LOOP
when HOLD is top or the render passes 1.5 s. † = registered but absent from the phone picker.
"from Omnibus" = adapted from an XOceanus engine (see omnibus-mine.md).

## Strings (waveguide, Karplus-Strong, coupled)
| Engine | Idea | Macros | Class |
|---|---|---|---|
| PLUCK | Karplus-Strong string: nylon, harp, koto, banjo, sitar (jawari fold-back) | TUNE DAMP PICK BODY DOUBLE (+STRIKE) | T |
| SILK† | KS strings on scale rows: oud, guzheng, santur, shamisen (courses, dispersion, sawari) | TUNE SCALE INFLECT DAMP PICK STRIKE BODY + per voice | T |
| MAGNET† | electric guitar: KS plus pickup comb and coil resonance, dry for VALVE | TUNE MUTE PICK BLEND | T |
| ARCO† | bowed string: stick-slip on a waveguide (cello, erhu) | TUNE BOW GRIP BODY HOLD | P/L |
| GYRE† | 4 strings 1:2:3:4 on one bridge and membrane, 8 sympathetics, internal rotor | TUNE TOUCH SYMPATHY SPIN BODY HOLD | P/L (rosterless) |
| NACRE† | two octave-paired 4-string harps, separate bodies, 8 sympathetics (prototype, HOLD refused) | TOUCH DUET CURRENT VENUE BLOOM HOLD | T |
| OHM (from Omnibus) | plucked string plus octave and third-harmonic companions coupled through a bridge membrane | TOUCH BODY LENGTH MEDDLING COMMUNE | T |
| TRIO (from Omnibus Ole) | tres, berimbau, charango exchanging waves through a shared junction | TOUCH DRAMA SIDES MOTION SPACE DAMP | T |
| ORPHICA (from Omnibus) | a pluck whose sustain breaks into grains of its own recent history | TUNE PLUCK FRACTURE SIZE DAMP HOLD | T/L |
| OVEN (from Omnibus) | hammered stiff strings through a dark fixed body (piano-like) | TUNE HAMMER BODY RING BLOOM HALO | T |

## Winds, reeds, air
| Engine | Idea | Macros | Class |
|---|---|---|---|
| BORE† | waveguide woodwind: reed table or air jet on `Strings.Loop` (FLUTE, SAX) | TUNE BREATH LIP CHIFF HOLD | P/L |
| EMBOUCHURE | a virtual player's gesture drives one resonator through reed, lip or bow driver; BRASS GROW prototype | TUNE EFFORT REED SHAPE GRAIN LENGTH HOLD | T/L |
| OBBLIGATO | air jet and reed on separate pipes with a duet controller | BREATH BOND MISCHIEF HOLD | T |
| BELLOWS (from Omnibus Oleg) | oscillator reed ranks, wheel-bowed melody with drones (accordion, hurdy-gurdy) | TUNE PRESSURE BODY MOTION HOLD + 2 per voice | T/L |
| REGISTERS (from Omnibus Octave) | additive pipe organ, beating 3-reed MUSETTE, TRANSISTOR | TUNE COLOR AIR EDGE SPREAD MOTION HOLD | T/L |
| TONEWHEEL | additive drawbar organ | TUNE PERC WARBLE DIRT | T |
| CIRCUIT | three pressure-driven breath resonators plus rattle/clapper/vessel/grunt gestures in a moving ensemble | TUNE BREATH DIAMETER ORBIT PACE CANYON HOLD | always L |
| AEROSTAT† | a strike hits two tube banks; the air pulse spins a rotor whose valve lets a reservoir blow a whistle | TUNE STRIKE PRESSURE INERTIA RELEASE LIFT HOLD | P/L |
| UNDERTOW | piston suction reservoir, four weighted flaps, ceramic rims, airflow resonators | TUNE DRAW FLAP WEIGHT SPIRAL LEAK HOLD | always L |

## Struck and modal (metal, glass, wood, skin)
| Engine | Idea | Macros | Class |
|---|---|---|---|
| MERCURY† | modal glass/steel object loaded by moving water; tapped, rubbed, bent | TUNE BEND RUB WATER GLASS COUPLE HOLD | P/L |
| NIMBUS | six suspended same-root modal plates with travelling pressure ports | TUNE EXCITE SPACING HEIGHT FIELD FUNNEL HOLD | T/L |
| COROLLA | coupled metal petal modes, neighbour springs, chamber, energy-driven hinge | TUNE PULL BLOOM FIELD CONTACT CHAMBER HOLD | T/L |
| TESSERA | one struck object of mixed materials (bars, string courses, tubes, frame) in a moving chamber | TUNE MATERIAL HAMMER SCALE FOLD MOTION HOLD | T/L |
| SUTURE | bronze vessel: four plates, elastic cords, wooden eyelets, stitch closure, cavity | TUNE GAP STITCH CORD SEAM CAVITY HOLD | always L |
| THAW | modal ice plates rubbed by powered copper runners, melt and water channels | TUNE CONTACT HEAT FREEZE CHANNELS THICKNESS HOLD | T, L late |
| MURK | four tuned wooden trunks exchanging waves through lossy delays, synthesized owl replies | TUNE STRIKE TRUNK FOG AGITATION GROVE HOLD | always L |
| PITCHWHEEL | wheel teeth catch fingers through resin filaments into a 10-mode wooden body | TUNE PUSH TOOTH ADHESION HEAT BODY HOLD | T/L |
| CHAMBER† | modal quartet (membrane, bar, cowbell, triangle) in a room with material boundaries (CLI only) | STRIKE BALANCE MATERIAL DIVIDE PLACEMENT HOLD | T |
| CISTERN | struck modal membrane releasing a droplet field whose landings re-excite it | STRIKE SUSPENSION DROP SKIN DRAIN HOLD | always L |
| FLOTILLA | pitched emitters on a pool of damped modes; floating vessels make contacts under a dome | PULSE CROSSING FLOTILLA VESSEL SURFACE SKIN HOLD | always L |
| SHOAL | harmonic energy transport (GLASS/RIPPLE) driving WOOD, BOWL or WIRE materials | DENSITY CURRENT COHESION STRAY GLOW HOLD | T/L |
| FORK | modal electric piano: struck bar's 4 modes through a nonlinear pickup curve | TUNE STRIKE BARK STIFF DECAY WIDTH | P/L by decay |
| TINES | 2-op FM with snapped ratio: bell, chime, kalimba, block, zap, toy | TUNE RATIO BRIGHT BITE DECAY + per voice | T or P |
| REVEL | six modal drum heads playing an interlocking phrase, heard by 1 to 3 moving microphones | TUNE PLAY SKIN ORBIT WEAVE REACH HOLD | T/L |
| TREMOR† | tuned hide drum (12 modes), up to 4 strikers, a bead tray, a powered six-string cage that can fault | TUNE STRIKE ENSEMBLE BEADS CAGE CURRENT FAULT HOLD | KICK/TOM/PERC by pitch |

## Drums
| Engine | Idea | Macros | Class |
|---|---|---|---|
| THUMP | analog-style drums: swept sines, square clusters, filtered noise | TUNE DECAY + per voice | drums |
| SKIN | modal drums: inharmonic decaying partials; resonant noise banks for hats and ride | TUNE DECAY TONE PUNCH + per voice | drums |
| TERRA† | world percussion: membrane, cavity, conical bell, tuned bar; palm or stick exciters | TUNE DECAY FORCE POS DROOP + per topology | KICK/TOM/PERC |
| ONSET | continuous blend between an electronic (THUMP) and a physical (SKIN) source | TUNE DECAY BLEND SNAP BODY EDGE | drums |
| FATHOM | bass: source → DRIVE → resonant low-pass (DEEP, GRIND, GLASS) | TUNE GLIDE DRIVE CUTOFF DECAY + per voice | KICK/PERC |

## Oscillator synths (subtractive, FM, PD, wavetable, additive, chaos)
| Engine | Idea | Macros | Class |
|---|---|---|---|
| VELVET | subtractive | TUNE SHAPE FAT CUTOFF SQUEEZE DECAY | T |
| RESIN | three oscillators through a transistor ladder; drone and held-pad designs | TUNE STACK CUTOFF CREAM CONTOUR DECAY | T |
| TIDE | West Coast: PM sine, wavefolder, low-pass gate | TUNE FOLD WARP GLOW CLICK DECAY WANDER RATIO | PERC or T by voice |
| SKITTER | two edged oscillators into a nonlinear multimode filter with signal-history agitation | BUZZ SKITTER AGITATE VENT STATIC HOLD | T/L |
| STOOP | groove voices (polyBLEP, harmonic banks, small FM) with a quieter answering shadow | TOUCH COLOR SHADOW WEAR LENGTH HOLD | T/L |
| ALLOY | 4-op phase modulation with a generated wood/tine/breath attack fed into the modulation port | TUNE MATERIAL FUSE EDGE BODY LENGTH HOLD | T/L |
| MURMUR | 4-op FM through 5 moving formant filters (a mouth) | MOUTH EDGE BREATH MOTION BODY HOLD | T/L |
| TANGLE | two oscillators phase-modulating each other with encounter-triggered capture | TANGLE CHASE CATCH BITE WEIGHT HOLD | T/L |
| CREASE | phase distortion: two analytic phase maps with fading deformation memory | FOLD PRESSURE DRAG SPLIT RELAX HOLD | T/L |
| GLINT | phase distortion: windowed sine burst at k×f0 gives a sweepable formant | TUNE PEAK FOLLOW BODY BLOOM DECAY DEPTH | T |
| CONTOUR | two-layer wavetable over 8 generated harmonic families, SVF, drive | TUNE SCAN OFFSET MOTION FILTER DRIVE HOLD + 7 detail | T/L |
| SNAP† | 256-point wavetable read from a photo or drawn | TUNE BRIGHT DECAY GRIT | T |
| SCANNED CHAIN (from Omnibus Obscura) | scanned synthesis: a 128-point shape moved by a mass-spring simulation | MOTION DAMP BEND BLOOM | T |
| PRISM | additive: 32 partials in 5 independently developing groups | TUNE GRAIN DRIFT SHAPE STRIKE BODY HOLD | T/L |
| ORIGAMI (from Omnibus) | spectral geometry: partials reflected around a crease, translated, respaced | TUNE CREASE FOLD TURN WARP MOTION | T |
| OPENSKY (from Omnibus) | seven band-limited saws into a stereo room with octave-shifted feedback; CRYSTAL is a bell | RISE WIDTH GLOW AIR | T |
| LACQUER | layered tone: body, struck edge, trail and support layers with articulated onsets | TUNE RELIEF SHEEN BODY CONTOUR GLUE HOLD | T/L |
| RELAY | one note handed between four generated source roles on a phrase clock | TUNE ORDER PULSE BLEND COLOR LENGTH HOLD | T/L |
| VENT (from Omnibus Ouroboros) | chaos: one Rössler trajectory is the audio | CHAOS LEASH VIEW DAMP PUSH LENGTH | T |
| FLOCK (from Omnibus Oceanic) | 24 audible oscillators following separation, alignment, cohesion | SPREAD PULL FLOW SCATTER EDGE HOLD | T |
| OVERLAP (from Omnibus) | six audible delay lines with topology-specific scattering (knot FDN) | KNOT PULSE LOCK GLOW RING HOLD | T |
| WICK† | one complex source into three low-pass gates whose releases trigger the next (CLI only) | SPARK FEED CASCADE TIMBRE RECOVERY HOLD | T/L |
| SIREN | dub siren: LFO-swept PolyBLEP square | TUNE RATE DEPTH SWEEP GRIT HOLD | T/L |

## Voice, tape, sample, environment
| Engine | Idea | Macros | Class |
|---|---|---|---|
| VOX | formant vocal synthesis: choir, speech, beatbox | VOWEL BREATH DECAY SIZE GLIDE + many | T; BEATBOX files drums |
| CONFLUENCE | recorded-source (VocalSet) 1 to 8 performer vocal gathering; not a registered Patch | CROWD REGISTER DIVERGENCE RESPONSE SPACE | always L |
| SPOOL | tape machine: three recorded source strips, heads, splices, spring return, cross-strip imprint | TUNE HEAD STRAIN RETURN IMPRINT SPLICE HOLD | T/L |
| GRAINS | granular resynthesis of captured snips; no Patch | SIZE SMEAR DRIFT PITCH SHINE | texture |
| OSPREY (from Omnibus) | a sea gesture excites three resonant bodies; scheduled creature callers answer | SHORE SEA MOTION DEPTH SPACE HOLD | T |

## Capture-side instruments (the `:audio` module, not engines)
BODY (tuned resonators struck by a captured hit), ETERNAL (hyperbolic tail freeze with PGHI
phases), RETUNE (spectral retune into key), DESAMPLE (nearest THUMP patch to a captured hit),
OUTSIDE (the room as an effect: play out of the speaker, listen, deconvolve), CHOPPER,
CLASSIFIER, TRANSIENTS.

## Export readiness (where the cheap gaps hide)

A pad is the unit of use, so what reaches a kit or a keygroup matters as much as what an
engine can synthesize. Verified at commit `565551e`:
- **Keygroup (multisampled instrument) path**: `Keys.xxxPad` exists for CONTOUR, GLINT,
  MERCURY, OPENSKY, ORPHICA, PRISM, REGISTERS, RESIN, SIREN; `shell` has a PadMaker for
  BELLOWS, GLINT, MERCURY, OPENSKY, ORPHICA, REGISTERS, RESIN, SIREN; `InstrumentSuite`
  (test-side) renders EP (Fork), harp, music box, organ, Mercury, Resin. Roughly 25 engines
  carry a HOLD loop and never reach this path.
- **Velocity layers in kits**: only `ObbligatoKits` renders soft/mid variants
  (`softVariants`). Every other kit is one velocity per pad, even for engines whose
  `Velocity.kt` arm renders with velocity.
- **Bass register**: FATHOM is the only engine whose identity is the bass slot; ARCO,
  CIRCUIT and TREMOR root at C2 (`ROOT_MIDI = 36`) but are not bass instruments. No bass
  engine is keygroup-capable.
- **Roadmap open items** recur in `docs/SYNTH_ROADMAP.md` rows: the sample format has no
  attack-plus-loop region (HOLD exports loop-only material), the S4 keygroup export still
  needs its hardware load check, most engines await the owner's listen, phone and MPC checks
  are open, release-name checks are open. Grep for "remains open", "pending", "awaiting",
  "attack-plus-loop" rather than reading the file (2,300 lines of long table rows).

## Thin by slot (the lens that decides a gap scan's order)

| Kit slot | What serves it today | Thin because |
|---|---|---|
| Bass (A01 register, sub to low mids) | FATHOM (3 voices), RESIN low TUNE, MAGNET low | one engine owns the slot; none keygroup-capable; no sustained bass loop with a designed gesture |
| Kick / snare / hats / toms | THUMP, SKIN, TERRA, ONSET, TREMOR, FATHOM kicks | no velocity layers outside ObbligatoKits; no physical open/closed hat pair; no bar-loop drum engine |
| Keys instrument (keygroup) | the 9 `Keys.xxxPad` engines | ~25 HOLD engines never exported as instruments; no attack-plus-loop |
| Loop / texture pads | the 7 always-LOOP engines, HOLD tops | plentiful; the gap is export (seam, keygroup), not synthesis |
| Chord / stab | none designed for it (Nacre's DUET, Gyre's 1:2:3:4 are intervals, not chords) | a chord-stab engine with a key-aware voicing is absent |
| Melodic lead | most of the roster | crowded; six of the last ten engines compete here |

## What the roster already covers heavily, and what it barely touches

Heavy: struck modal objects with an imagined ecology (Murk, Cistern, Flotilla, Suture, Thaw,
Corolla, Nimbus, Tessera, Pitchwheel), waveguide strings, physical winds, "always LOOP"
ecosystem engines. Six of the last ten engines are "physically inspired pitched engine with an
imagined mechanism, five macros plus HOLD, six voices." A seventh in that mould competes with
its own siblings for a pad.

Thin or absent (candidates for a gap scan, not conclusions):
- **Granular/microsound as a first-class pitched engine** (only ORPHICA's sustain and the
  capture-side GRAINS).
- **Formant and vocal-tract models beyond VOX** (no glottal-source or articulatory engine).
- **Spectral engines** beyond ORIGAMI and PRISM (no spectral freeze, no cross-synthesis, no
  vocoder-as-instrument).
- **Feedback instruments** (every shipped engine bounds feedback by construction; a deliberate
  feedback-as-instrument design with a stability proof would be new).
- **Sequencer-like gesture engines** exist (RELAY, REVEL, CIRCUIT) but nothing is
  rhythm-first for the drum side beyond ONSET/THUMP/SKIN/TERRA.
- **Tuning systems** beyond SILK's scale rows (no just intonation or maqam-aware engine).
- **FX-as-instrument** on the pitched side (OVERLAP is one; the Aquarium suite is FX, not an
  engine).
- **Velocity as timbre**: `brightnessOverride` exists but few engines prove a monotonic sweep.
- **The sample side**: HOLD exports loop-only material; there is no attack-plus-loop region in
  the sample format, which the roadmap lists as an open gap.
