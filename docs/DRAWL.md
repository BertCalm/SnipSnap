# DRAWL

A bar-stopped string whose pitch path is the performance: the bar slides the note in, shimmers on it and
the string rings out through its body. Six voices for the western and cosmic country end of the shelf:

| Voice | Instrument | Root | Keys |
|---|---|---|---|
| CONE | resonator guitar: steel bar, metal picks, spun aluminium cone | A3 | A2 to A4 |
| BOTTLE | electric slide: bottleneck, bridge pickup, a small amp | D4 | D3 to D5 |
| LAP | lap steel: bar across the strings, dark pickup | D4 | D3 to D5 |
| STEEL | pedal steel: volume swell, pedals raise the inner tones | E4 | E3 to E5 |
| FLATTOP | country flat-top: fretted steel strings, a slide steps over the frets | D3 | D2 to D4 |
| GUT | western nylon: flesh fingers, finger vibrato | E3 | E2 to E4 |

Design, every listen and every measured number: [the DRAWL spec](superpowers/specs/2026-11-20-drawl-engine-design.md).
Gate status: [the DRAWL entry](ENGINE_ACCEPTANCE_GATES.md). Code: `synth/.../Drawl.kt` and its `Drawl*` neighbours,
`shell/.../DrawlKeygroupMaker.kt` and `DrawlPadMaker.kt`.

## Controls

- **Knobs** (every voice): TUNE (an octave either side of the root), SLIDE (how far and slow the bar travels in;
  STEEL's pedal travel, FLATTOP's fret steps), WAVER (hand vibrato), PICK, BODY, RING and HOLD.
- **HOLD is finite**, the owner's decision of 21 Nov 2026: it lengthens the note to at most 8 s and never loops.
- **On the recipe, not a knob** (SYNTH picker, under the knobs):
  - TOUCH is how hard the string is picked: brighter and louder at one gain per sound.
  - TAKE 1 to 4 are the player's four passes. ROBIN deals them on a repeated pad.
  - STRUM is OFF for one string, or the voice's own chord picked DOWN or UP, with SWEEP from 10 to 240 ms.
    A bar chord slides as one, STEEL's pedals raise its tones, and a fretted chord is not slid.

## Presets and kits

- **Factory bank:** 18 presets, three per voice: its default note, its long slide with full vibrato and its strum.
  Every one is a render the owner heard and kept, pinned by hash.
- **DRAWL** (starter): sixteen finite pads in D major pentatonic.
  - A01 to A08 walk RESO TWANG from D3 to F#4.
  - A09 to A12 are the lead voices.
  - A13 and A14 are the lap and pedal slides.
  - A15 and A16 are the I and V strums.
- **DRAWL TAKES** (starter): the same pads with soft, medium and hard picking and four takes on each, about 72 MB.
  MPC 3 carries every take. MPC 2 plays each touch's first take, the recorded format limit.

## Keys and chords (MAKE INSTRUMENT, round 4)

- **Range and roots:** the voice's root an octave either side, the reach of TUNE (25 keys). A sampled root every
  minor third, plus the saved note. A key between roots plays its neighbour a semitone up or down.
- **Touch:** soft .3, medium .75 and hard 1, in velocity windows 0 to 42, 43 to 84 and 85 to 127.
- **Takes:** two per touch, the saved take and take 1 (a keygroup holds eight samples). They are dealt in turn in
  HALOBAR's matching-window Cycle layout, so the MPC 3 export keeps them.
- **Length:** HOLD is capped at .5 on keys, so a key rings at most about 6 s. Key-up fades it over 0.6 s.
- **Package size:** 54 samples and 18 to 29 MB per voice at the factory recipes.
- **Every sample is the engine's own render**, bound by SHA-256 to its recipe in `drawl-source-recipes.json`.
  A package whose audio, recipe or mapping drifts is refused.
- **Strums:** a strummed recipe makes no keys. On keys, the chord tool plays chords: `DrawlChords.notes` moves a
  voicing by whole octaves into the voice's keys, folding a voicing wider than the range. All 960 chord-tool
  voicings play on real saved DRAWL keys in `DrawlKeygroupMakerTest`.

## Listening

`./gradlew :synth:generateDrawlAudition` renders the engine, kit and pin files.
`./gradlew :shell:generateDrawlKeysAudition` makes every voice's keys and plays them back through the app's
sample player. `prototype/drawl/` holds the Python prototype and the page builders.
