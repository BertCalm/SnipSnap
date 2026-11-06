# More held instruments, and instruments in expansions (2026-11-04)

The engine scan's "keygroup reach" finding: only 22 engines reached MAKE INSTRUMENT.
The owner asked for more, chose to qualify engines first and build the qualifiers through
one shared maker, and asked for XPN keygroups of the new instruments, both as a factory
pack and through the phone's own EXPANSION export.

## What changed

- **MERCURY and KEEL** reach MAKE INSTRUMENT. Both already had finished makers
  (`MercuryPadMaker`, `KeelPadMaker`) that nothing in the app called. MERCURY's loops were
  approved by ear on 2026-10-04.
- **`LoopPadMaker`**, one maker for any engine whose top HOLD step is a loop that closes on
  itself. `SynthRoot` moves the patch to each zone's note (every minor third, an octave
  either side of its root, inside its range), HOLD is forced to its top step, and the loop
  it renders is the zone, twice over, looping at the second copy. Every zone's join is
  checked as it renders and a click is refused, naming the zone.
- **`LoopInstrumentScan`** (`./gradlew :shell:scanLoopInstruments`) decides which voices it
  offers. Each candidate voice's first factory preset is played at its lowest, root and
  highest note, and three readings are taken: the wrap (the join's high-frequency energy
  against the sound's own 95th percentile), the pitch against the key, and the level's
  spread over 50 ms windows. Results land in `testkit/loop-instruments/`.
- **Expansions carry instruments.** `PackBuilder` packs an instrument package as
  `Programs/<Name>.xpm` (the package's own program) with its looped WAVs under
  `Samples/<Name>/`, beside the kits. On the phone, MAKE > INSTRUMENTS rows have
  EXPANSION >, and EXPORT's EXPANSION subject lists and writes them.
- **A factory pack**: `./gradlew :shell:generateInstrumentPack` renders MERCURY's three picker
  voices, KEEL's three, ARCO's two (through `ArcoPadMaker`, at their defaults) and every
  qualifier, and packs them as `SnipSnap New Keys` (folder and
  `.xpn`) under `testkit/loop-instruments-pack/`. `-Pengines=THAW,ARCO` limits it.

## Who qualified

61 voices across 14 engines, each from its first factory preset:

| Engine | Voices |
|---|---|
| AEROSTAT | FLOAT |
| ALLOY | BASS, KEY, PLUCK, BRASS, STRING, SOFT |
| BORE | FLUTE, SAX |
| COROLLA | TONGUE, CHOIR, CHATTER, ORBIT |
| EMBOUCHURE | REED, BRASS, BASS, SOFT, BOW, WHISTLE (octave to check by ear) |
| FLOTILLA | KNOCK, DRIFT |
| LACQUER | GLASS, KEY, BRASS, BASS, PLUCK, SILK |
| MURMUR | BASS, KEY, PLUCK, METAL |
| PITCHWHEEL | PLUCK, DRAW, THAWED, TURN |
| SKITTER | BASS, LEAD, HOLLOW, FLUTTER, CHIRP, FRAY |
| STOOP | TINE, CLAV, ORGAN, LEAD |
| TANGLE | LEAD, HOLLOW, PULSE, METAL |
| THAW | all six |
| UNDERTOW | all six |

EMBOUCHURE reads exactly one octave above every key, the same at each note. A weak
fundamental fools the detector that way, so this may be the detector rather than the sound;
the owner's ear decides.

## Who did not, and why

| Engine | Reason |
|---|---|
| APSIS, CIRCUIT, CISTERN, MURK, REVEL, SCOUR, SPOOL, SUTURE | the loop pulses (7 to 95 dB spread): a rhythm, not a held note |
| NIMBUS | cymbals: no keyboard pitch (readings hundreds of cents off) |
| RELAY, TESSERA | in every voice the join clicks, the loop pulses, or both (TESSERA CHAMBER would not settle) |
| COROLLA BLOSSOM and HUSK, MURMUR LEAD and CHOIR, PITCHWHEEL CLUNK and RECOIL, STOOP BASS and CHORD, TANGLE BASS and KNOT, FLOTILLA RIPPLE, HOLLOW, CROSSWAVE and GATHER | that voice's pitch, octave or steadiness failed; the engine's other voices passed |
| OVEN | no HOLD control |
| SHOAL | its factory voices are WOOD, which refuses HOLD loops by design |
| CAIRN, WICK | prototypes with no factory presets |

ARCO qualified here on 2026-11-04 and left the shared maker on 2026-11-06 for its own,
`ArcoPadMaker`: each key starts with the bow's own stroke, then holds the LOOP's loop, in a
soft and a hard touch layer (the ARCO design spec's R2 held-keygroup section). Not heard yet.
BORE has its own round-two spec for a dedicated held instrument (breath attention, an attack
before the loop); until it lands, the shared maker gives it one.

## How to try it

- **On the phone:** SYNTH, pick a qualified voice, MAKE INSTRUMENT. Then MAKE > INSTRUMENTS,
  EXPANSION > on the row, and EXPORT > EXPANSION to write it with your kits.
- **On the MPC:** run the pack generator and copy `SnipSnap New Keys.xpn` (or the folder under
  `Expansions/`) to the MPC's drive.

## Why it is built this way

- **One maker, many engines.** Every qualifying engine already makes the loop a held key
  needs; what was missing was the keygroup around it. `SynthRoot` (the ROOT stepper's own
  code) already moves any patch to any note, so the maker needs no engine-specific code.
- **Qualify by measurement, then listen.** A loop that pulses, clicks or plays the wrong
  note makes a bad keyboard however well it is packaged. The scan's three readings rule
  those out before anything reaches the owner's ears; what passes still waits on them.
- **The loop twice over.** A keygroup layer whose loop starts at frame 0 reads as no loop at
  all, so each zone is two copies with the loop at the second, MERCURY's shape.
- **The package's own program in the pack.** The expansion carries exactly the `.xpm` the
  phone wrote, so a pack never plays differently from the instrument it came from.

## Ledger

- **Verified:** `LoopPadMakerTest` (6), `PackBuilderTest` (5), `ExpansionExportTest` (12),
  `ExpansionStoreTest` (5), `ExpansionMembersTest` (4), `ConventionTest` and `PersonalityTest`
  pass. The phone code compiles only in CI (no Android SDK in this session).
- **Measured:** the scan's readings, in `testkit/loop-instruments/loop-instruments.md`, for
  three notes per voice. The maker checks every zone's join again when it renders.
- **Proposed:** that the qualifiers make good instruments.
- **Heard:** nothing. Every new instrument waits on the owner's listen.
