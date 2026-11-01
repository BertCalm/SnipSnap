# SnipSnap engine inventory (2026-11-01)

84 engines registered in `Patches.kt` (the `*Patch.ENGINE ->` cases in `Patches.fromJsonValue`),
verified at commit `a292d5f6`, plus a handful of unregistered synthesis objects. The phone picker
(`EngineCatalog.kt`) filters by nine categories (`EngineCategory`: drums, bass, keys, strings,
winds, voices, synth, bells, pads), but the repo has no mechanism taxonomy. The families below
are the skill's own grouping by mechanism, used for sibling checks and gap scans. Re-grep the
macros before quoting them (`macrosFor` in each engine file).

The first gap scan after ROOM (2026-11-01) found the earlier list, 66 engines at commit
`565551e`, 18 engines short. Recount before any gap scan with
`grep -cE '^\s+[A-Za-z]+Patch\.ENGINE ->' synth/src/main/kotlin/com/snipsnap/synth/Patches.kt`
and compare the registered names with this file's first column.

Legend: T = TONAL, L = LOOP, P = PERC, T/L = TONAL then LOOP at HOLD's top, P/L = PERC then LOOP
when HOLD is top or the render passes 1.5 s. † = registered but absent from the phone picker
(checked against `EngineCatalog.kt` on 2026-11-01). "from Omnibus" = adapted from an XOceanus
engine (see omnibus-mine.md). "classified from the render" = no fixed class: `Classifier` files it
from the audio.

## Strings (waveguide, Karplus-Strong, coupled)
| Engine | Idea | Macros | Class |
|---|---|---|---|
| PLUCK | Karplus-Strong string: nylon, harp, koto, banjo, sitar (jawari fold-back) | TUNE DAMP PICK BODY DOUBLE (+STRIKE) | T |
| SILK† | KS strings on scale rows: oud, guzheng, santur, shamisen (courses, dispersion, sawari) | TUNE SCALE INFLECT DAMP PICK STRIKE BODY + per voice | T |
| MAGNET† | electric guitar: KS plus pickup comb and coil resonance, dry for VALVE | TUNE MUTE PICK BLEND | T |
| ARCO† | bowed string: stick-slip on a waveguide (cello, erhu) | TUNE BOW GRIP BODY HOLD | P/L |
| GYRE† | 4 strings 1:2:3:4 on one bridge and membrane, 8 sympathetics, internal rotor | TUNE TOUCH SYMPATHY SPIN BODY HOLD | P/L (rosterless) |
| NACRE | two octave-paired 4-string harps, separate bodies, 8 sympathetics (prototype, HOLD refused) | TOUCH DUET CURRENT VENUE BLOOM HOLD | T |
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
| AEROSTAT | a strike hits two tube banks; the air pulse spins a rotor whose valve lets a reservoir blow a whistle | TUNE STRIKE PRESSURE INERTIA RELEASE LIFT HOLD | P/L |
| UNDERTOW | piston suction reservoir, four weighted flaps, ceramic rims, airflow resonators | TUNE DRAW FLAP WEIGHT SPIRAL LEAK HOLD | always L |
| OTIS (idea from Omnibus, original implementation) | the accepted organ, steam pipe, blues harmonica and squeeze-box recipes as finite scores sharing one pressure or cabinet (ORGAN, STEAM, HARP, SQUEEZE) | TUNE, four per voice (ORGAN: BARS CLICK DRIVE ROTARY; STEAM: PRESSURE BREATH WANDER SHARED; HARP: PRESSURE BREATH BEND VIBRATO; SQUEEZE: PRESSURE MUSETTE PULSE SHARED), then LENGTH; no HOLD | P/L by length |

## Struck and modal (metal, glass, wood, skin)
| Engine | Idea | Macros | Class |
|---|---|---|---|
| MERCURY | modal glass/steel object loaded by moving water; tapped, rubbed, bent | TUNE BEND RUB WATER GLASS COUPLE HOLD | P/L |
| NIMBUS | six suspended same-root modal plates with travelling pressure ports | TUNE EXCITE SPACING HEIGHT FIELD FUNNEL HOLD | T/L |
| COROLLA | coupled metal petal modes, neighbour springs, chamber, energy-driven hinge | TUNE PULL BLOOM FIELD CONTACT CHAMBER HOLD | T/L |
| TESSERA | one struck object of mixed materials (bars, string courses, tubes, frame) in a moving chamber | TUNE MATERIAL HAMMER SCALE FOLD MOTION HOLD | T/L |
| SUTURE | bronze vessel: four plates, elastic cords, wooden eyelets, stitch closure, cavity | TUNE GAP STITCH CORD SEAM CAVITY HOLD | always L |
| THAW | modal ice plates rubbed by powered copper runners, melt and water channels | TUNE CONTACT HEAT FREEZE CHANNELS THICKNESS HOLD | T, L late |
| MURK | four tuned wooden trunks exchanging waves through lossy delays, synthesized owl replies | TUNE STRIKE TRUNK FOG AGITATION GROVE HOLD | always L |
| PITCHWHEEL | wheel teeth catch fingers through resin filaments into a 10-mode wooden body | TUNE PUSH TOOTH ADHESION HEAT BODY HOLD | T/L |
| CHAMBER | modal quartet (membrane, bar, cowbell, triangle) in a room with material boundaries (CLI only) | STRIKE BALANCE MATERIAL DIVIDE PLACEMENT HOLD | T |
| CISTERN | struck modal membrane releasing a droplet field whose landings re-excite it | STRIKE SUSPENSION DROP SKIN DRAIN HOLD | always L |
| FLOTILLA | pitched emitters on a pool of damped modes; floating vessels make contacts under a dome | PULSE CROSSING FLOTILLA VESSEL SURFACE SKIN HOLD | always L |
| SHOAL | harmonic energy transport (GLASS/RIPPLE) driving WOOD, BOWL or WIRE materials | DENSITY CURRENT COHESION STRAY GLOW HOLD | T/L |
| FORK | modal electric piano: struck bar's 4 modes through a nonlinear pickup curve | TUNE STRIKE BARK STIFF DECAY WIDTH | P/L by decay |
| TINES | 2-op FM with snapped ratio: bell, chime, kalimba, block, zap, toy | TUNE RATIO BRIGHT BITE DECAY + per voice | T or P |
| REVEL | six modal drum heads playing an interlocking phrase, heard by 1 to 3 moving microphones | TUNE PLAY SKIN ORBIT WEAVE REACH HOLD | T/L |
| TREMOR | tuned hide drum (12 modes), up to 4 strikers, a bead tray, a powered six-string cage that can fault | TUNE STRIKE ENSEMBLE BEADS CAGE CURRENT FAULT HOLD | KICK/TOM/PERC by pitch |
| ACCORD | fifteen self-sustaining mechanisms on one moving support; their contacts and pressure drive a tuned acoustic bank (BRUSH, WOOD, TINE); the pitch is the MIDI note, so there is no TUNE | PACE COUPLING MISMATCH CONTACT RAFT HOLD (HOLD refused) | T |
| APSIS | a powered root and a central body feed six orbiting modal bodies: approach, rush past, linger (PASSAGE, GRAIN, ANCHOR) | STRETCH DISTANCE BODIES ALIGN COUPLE HOLD | T/L |
| CAIRN† | one launched bead trades motion between four stone pockets through a shared mount; steep groove edges take energy out, then it rings (KNOCK, ANSWER, TUMBLE, a stone bouncing back to a bed in shorter flights); prototype | TUNE WEIGHT SLOPE GROOVE FRICTION BODY HOLD | T/L |
| CAUSTIC | one dry harmonic source lights one circular body; its geometry decides which shared modes ring (ALLOY, HOLLOW_MALLET, RUBBED_RING, TRAVELLING_CATCHES) | SOURCE FOCUS BODY LENGTH HOLD (HOLD refused) | T; files as L past 1.5 s, and the default LENGTH 1 renders 8 s |
| FERRY† | a struck wooden string hands energy to a wire with sympathetic receivers, which bloom and return (PASSAGE); prototype | HANDOFF MATCH HOLD (HOLD refused) | T |
| FILAMENT | one source split across guided paths with bend sites; the escaped sound feeds a damped halo (THREAD, PULSE, CURL) | INJECT BEND LENGTH BUNDLE COLLECT HOLD | T/L |
| LIBRATION | trajectories of a rotating-frame three-body system voice wooden, glassy and departing notes (GATHER, TWINS, DEPARTURE); HOLD is 0 or 1, and 1 needs an explicit held model | REGION WANDER NUDGE SWARM RESONANCE HOLD | T/L |
| MERIDIAN | a maintained precessing swing touches twelve surfaces (wood, wire, glass, metal) that keep ringing (SWEEP) | TUNE LATITUDE LENGTH REACH SURFACE MEMORY HOLD | T/L |
| TIDAL | a two-center encounter drives 32 persistent harmonic cohorts that end as ringing strands (PAIR, STREAM); HOLD is 0 or 1 | APPROACH PASSAGE TILT BIND TAIL HOLD | always filed L |
| TREFOIL | three pitched banks move under equal-mass Newtonian gravity and trade energy on contact (WEAVE, HOLLOW, WIRE, TANGLE, TRACE) | WEAVE NUDGE EXCHANGE THREAD MEMORY HOLD (HOLD refused) | T |
| SCOUR† | a roller spins up on a free plate while shot rains faster, dents harden it, one slam lands and it rings (PLATE, PANE, SHEET, BEAM); never loops | TUNE BUILD RPM SHOT HARDEN HOLD | P; L past 1.5 s |
| EROSION† | charge, irregular discharge, delayed collapse, thinning dust: an energy-reservoir analogy (ARC, CRATER); prototype, no HOLD | TUNE VOLTAGE FEED FLUID SLAG | classified from the render |

## Drums
| Engine | Idea | Macros | Class |
|---|---|---|---|
| THUMP | analog-style drums: swept sines, square clusters, filtered noise | TUNE DECAY + per voice | drums |
| SKIN | modal drums: inharmonic decaying partials; resonant noise banks for hats and ride | TUNE DECAY TONE PUNCH + per voice | drums |
| TERRA† | world percussion: membrane, cavity, conical bell, tuned bar; palm or stick exciters | TUNE DECAY FORCE POS DROOP + per topology | KICK/TOM/PERC |
| ONSET | continuous blend between an electronic (THUMP) and a physical (SKIN) source | TUNE DECAY BLEND SNAP BODY EDGE | drums |

## Bass (the A01 register)
| Engine | Idea | Macros | Class |
|---|---|---|---|
| FATHOM | bass: source → DRIVE → resonant low-pass (DEEP, GRIND, GLASS) | TUNE GLIDE DRIVE CUTOFF DECAY + per voice | KICK/PERC |
| BALLAST | a bass drives a frame that shakes six sympathetic strings and six glass tiles that knock (ROOT, WIRE, GLINT shown as TILES, DEEP, BLOOM, SWARM); has a keygroup builder | TUNE DRIVE SYMPATHY SPAN GLASS FRAME HOLD | P/L |
| KEEL† | a bass that settles: attack with character, the upper spectrum distils away, the body fills in under the note (REDUCE, UPRIGHT, BELLY); drawn from three Omnibus engines, nothing ported; has a keygroup builder | TUNE SETTLE WEIGHT GRIT SLIDE LENGTH HOLD | T/L (loops from HOLD 0.85) |

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
| SCANNED CHAIN† (from Omnibus Obscura) | scanned synthesis: a 128-point shape moved by a mass-spring simulation | MOTION DAMP BEND BLOOM | T |
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
| LATTICE | a harmonic source passes ordered two-component projection gates; the rejected parts excite a resonant outline (WARM, PLUCK, HOLLOW, TRACE) | TUNE TURN STAGES TWIST COLOR REJECT HOLD (TRACE: TUNE SPEED HOLD) | T/L (TRACE finite only) |
| LORENTZ† | a falling sweep, one collision, irreversible capture and a steady sub tail: a pulse-welding analogy (FUSE, LORENTZ); HOLD lengthens the decay toward 1.2 s and never loops | TUNE CAPACITOR GAP IMPACT WELD HOLD | classified from the render |
| RIME | a Koch contour read through two pickups into an eight-mode body (LOOP, GLINT, VESSEL, REED, FILIGREE, CRUST); HOLD past 0.85 adds periodic scale motion | DETAIL EDGE ANGLE UNFOLD BODY HOLD | T/L |

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
engine can synthesize. Verified at commit `a292d5f6` (2026-11-01); the 2026-10-21 list at
`565551e` is superseded:
- **Keygroup (multisampled instrument) path**: `Keys.kt` has twelve builders (`rimePad`,
  `resinPad`, `sirenPad`, `mercuryPad`, `prismPad`, `creasePad`, `registersPad`, `contourPad`,
  `glintPad`, `ballastPad`, `orphicaPad`, `keelPad`), and `shell` has 22 engine PadMakers
  (BALLAST, BELLOWS, CONTOUR, CREASE, FILAMENT, FLOCK, GLINT, KEEL, LIBRATION, MERCURY, MERIDIAN,
  OHM, OPENSKY, ORIGAMI, ORPHICA, OVERLAP, PRISM, REGISTERS, RESIN, RIME, SIREN, TIDAL) beside
  the shared `PadMaker.kt`. The two lists overlap and were not de-duplicated. How many HOLD
  engines still lack a path has not been counted.
- **Velocity layers in kits**: `softVariants` is used by the kit assembler (`KitAssembler.kt`),
  by the THUMP classic starter kit (`StarterKits.kt`, two soft variants from `Velocity.variantsAt`)
  and by test-side kit generators for APSIS, MERIDIAN, ACCORD, FLOCK and MURMUR. Whether the SKIN,
  TERRA and ONSET kits carry layers has not been counted.
- **Bass register**: FATHOM, BALLAST and KEEL. BALLAST and KEEL have keygroup builders. KEEL is
  off the phone picker. ARCO, CIRCUIT and TREMOR root at C2 (`ROOT_MIDI = 36`) but are not bass
  instruments.
- **Loop region**: a keygroup layer carries a loop start (`InstrumentStore.kt`,
  `Layer.loopStartFrame`), so an instrument can hold an attack and then loop. The roadmap
  still says a pad in a kit has no attack-plus-loop region field, so HOLD engines export
  loop-only material to a pad.
- **Phone picker**: 15 of the 84 are absent: ARCO, BORE, CAIRN, EROSION, FERRY, GYRE, KEEL,
  LORENTZ, MAGNET, SCANNED CHAIN, SCOUR, SILK, SNAP, TERRA, WICK. That is
  `docs/ENGINE_STANDARD_SCAN_2026_11.md`'s list plus LORENTZ and SCANNED CHAIN; its count of 82
  predates ACCORD and one more. Recount rather than quote either.
- **Roadmap open items** recur in `docs/SYNTH_ROADMAP.md` rows: the S4 keygroup export still
  needs its hardware load check, most engines await the owner's listen, phone and MPC checks are
  open, release-name checks are open. Grep for "remains open", "pending", "awaiting",
  "attack-plus-loop" rather than reading the file (2,300 lines of long table rows).

## Rack and pedals (the FX side of a pad)

Verified at `a292d5f6`. The rack has 23 sections in a fixed order (`FxChain.SECTIONS`): speed,
swell, smear, ghost, spike, eq, contour, wah, compressor, squash, forge, valve, crunch, ring, dub,
vinyl, tape, ensemble, phase, echo, spring, room, motion. The pedal library (`Pedals.kt`) has
eight pedals (DRIVE, SUSTAIN, WAH, TOUCH, ROCK, FORGE, DELAY, ROOM) and one board (RIG). Rack
moves that exist as characters but not as pedals include `swelled`, `phased` and `ensembled`.
The owner asked for a FADE IN pedal and a VIBRATO pedal at the 2026-10-23 gate (roadmap S30 row);
the pedal brainstorm (`docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md`) specs both
as new sections, not chips over an existing one. A pedal needs its own listening gate, and its
character must land on the setting the owner heard at the pad sheet's tap (AMT 70): the ROOM
chip's first version fell short of the heard room for exactly that reason.

## Thin by slot (the lens that decides a gap scan's order)

| Kit slot | What serves it today | Thin because |
|---|---|---|
| Bass (A01 register, sub to low mids) | FATHOM, BALLAST, KEEL, RESIN low TUNE, MAGNET low | served; KEEL is off the phone picker |
| Kick / snare / hats / toms | THUMP, SKIN, TERRA, ONSET, TREMOR, FATHOM kicks | velocity layers now exist in the kit assembler and the THUMP classic kit; count the other drum kits before calling it thin; an open/closed hat pair on one physical engine is unverified |
| Keys instrument (keygroup) | 12 `Keys` builders and 22 engine PadMakers | HOLD engines without a path are uncounted |
| Loop / texture pads | the always-LOOP engines, HOLD tops | plentiful; the gap is export (seam, keygroup), not synthesis |
| Chord / stab | PHOTO CHORD (a photo picks a chord laid on pads), STRING MACHINE's landing, OTIS (chords sharing one budget), RIME's voicing, GYRE and NACRE intervals | partly served; none was read for a key-aware stab, so "absent" is no longer a safe claim |
| Melodic lead | most of the roster | crowded |

## What the roster already covers heavily, and what it barely touches

Heavy: struck modal objects with an imagined ecology (Murk, Cistern, Flotilla, Suture, Thaw,
Corolla, Nimbus, Tessera, Pitchwheel and now Accord, Ferry, Cairn, Apsis, Libration, Trefoil,
Tidal, Meridian, Scour), waveguide strings, physical winds, "always LOOP" ecosystem engines. Most
engines added since 2026-10-21 are the same mould: a physically inspired pitched engine with an
imagined mechanism, five macros plus HOLD. Another in that mould competes with its siblings for a pad.

Thin or absent (candidates for a gap scan, not conclusions; each was last checked by word search,
not by reading):
- **Granular/microsound as a first-class pitched engine** (ORPHICA's sustain and the capture-side
  GRAINS; "granular" appears in three synth files).
- **Formant and vocal-tract models**: VOX now has throat, wraith, swarm and speak files
  (`VoxThroat`, `VoxWraith`, `VoxSwarm`, `VoxSpeak`) and "glottal" appears in six synth files. Read
  them before calling this a gap.
- **Spectral engines** beyond ORIGAMI and PRISM ("vocoder" and "spectral freeze" each appear in one
  synth file; ETERNAL is capture-side; EROSION is an energy analogy, not a spectral engine).
- **Feedback instruments** (every shipped engine bounds feedback by construction; a deliberate
  feedback-as-instrument design with a stability proof would be new).
- **Rhythm-first drum engines**: `Groove.kt`, `Shuffle.kt` and `Roll.kt` exist (file names only,
  unread); nothing was verified as a bar-long drum engine.
- **Tuning systems** beyond SILK's scale rows (microtonal and temperament words appear in six synth
  files, unread).
- **FX-as-instrument** on the pitched side (OVERLAP is one; the Aquarium suite is FX, not an engine).
- **Velocity as timbre**: `brightnessOverride` exists but few engines prove a monotonic sweep.
