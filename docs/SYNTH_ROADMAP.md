# Synth roadmap — generate kits, not just capture them

Capture is the headline; synthesis is the second leg. The goal: dial in drum
sounds (and eventually keys) inside SnipSnap, render them to WAVs, and ship
them through the exact same kit → preflight → export pipeline as captured
snips. A kit where pad A01 came off a YouTube rip and pad A02 came out of the
synth is one kit, not two features.

## Why this is cheaper than it sounds

Pads are one-shots. That means **synthesis here is offline rendering** — a
pure function from parameters to a `Snip` — not a real-time engine:

- No latency budget, no Oboe coupling, no voice management. Render on commit,
  audition through the same player as any sample.
- Every downstream stage already exists and is tested: `Cleanup`, `WavWriter`,
  `KitAssembler`, `Preflight`, `KitExporter`.
- It's already demonstrated: `DrumSynth` (kicks with pitch sweeps, filtered
  snares, high-passed hats, FM-free toms) powers the play-mode prototype's
  demo kit, and the `:kit` end-to-end test synthesizes a break and exports it
  as a loadable MPC program. The roadmap below is "grow that seed", not
  "build a synthesizer from nothing."
- Fully testable in CI: rendered audio can be measured (centroid, decay,
  pitch) with the analysis code we already have. The classifier becomes a
  test harness: a THUMP kick preset must classify as KICK.

## The one legal guardrail: sound yes, names never

"Clones of classics" is a fine ambition **as sound**. Emulating the character
of a vintage analog drum voice or an FM electric piano is legitimate and done
industry-wide; the circuits' patents are long gone. What is not ours:

- **Trademarked names and model numbers.** Nothing in the app, presets, or
  store listing gets named after real machines — not the famous Roland/Akai/
  Linn/E-mu/Yamaha model numbers, not obvious near-misses of them.
- **Trade dress.** No recreating another machine's faceplate; everything
  wears TapeOS.
- **Sampled content from the originals.** All synthesis from scratch — which
  is also what makes the sounds ours to ship.

So the engines get our own cassette-era names, and preset descriptions say
"boomy analog kick", never "the famous one".

This prohibition covers product surfaces — engine and voice names, preset
names, descriptions, and commit messages — not documents that discuss the
rule itself: this roadmap, design specs, and implementation plans may name
machines when the point is naming the rule that forbids naming them.

## The engines

### THUMP — analog-style drum voices (the drum synth engine)

One engine, per-class voice models, each a small parameter set with big
range. First-generation voices:

| Voice | Model | Macro knobs |
|---|---|---|
| Kick | sine w/ exponential pitch sweep + click transient | TUNE · SWEEP · DECAY · CLICK · DRIVE |
| Snare | two detuned tones + filtered noise | TUNE · SNAP (noise mix) · DECAY · TONE |
| Hats | six-oscillator square cluster → bandpass (the classic metallic recipe) | TUNE · DECAY (cl/op) · METAL |
| Clap | burst train + noise tail | SPREAD · DECAY · TONE |
| Tom / Conga | swept tone, tunable family | TUNE · SWEEP · DECAY |
| Cowbell / Rim / Clave | two-tone square pair / damped tick | TUNE · DECAY |

Presets ship, knobs refine — MVP is preset + 3-5 macros per voice, never a
modular patchbay. A "SYNTH KIT" action renders a whole 16-pad kit from one
style preset (the demo kit becomes THUMP's factory default).

### CRUNCH — the character processor (not a synth, the secret weapon)

Vintage sampler character as a per-pad effect: bit-depth reduction to ~12-bit,
resample through a low, era-correct rate, filter. Two reasons it's in the
synth roadmap:

1. It's what makes THUMP sounds gel with captured material.
2. Applied to *captured* snips, it's the "make my YouTube rip sound like
   1987" knob — arguably more valuable than any oscillator.

Cheap DSP (quantize + resample + one-pole filters), lives in the `Cleanup`
stage as an optional pass, per-pad setting stored in `kit.json`.

### The key engines — chosen for fun-per-knob

Brainstormed against one axis: playability. Every paradigm below is the
*fun-first* version of itself, and all of them obey the playability rules
(next section).

**VELVET — subtractive.** The undisputed playability king: filter +
resonance is the most gratifying knob in synthesis and it's nearly
impossible to make an ugly sound. Two oscs, resonant low-pass, two
envelopes, LFO. Macros: SHAPE (saw↔pulse w/ PWM) · FAT (detune/unison) ·
CUTOFF · SQUEEZE (resonance + env amount) · GLIDE (mono). Basses, pads,
brass, strings.

**TINES — FM, deliberately small.** Full FM is famously unfun to program;
the fun version is **two operators with RATIO snapped to musical values**
and one BRIGHT knob driving the index. Macros: RATIO (snapped) · BRIGHT ·
BITE (index envelope) · WOBBLE. E-pianos, bells, metallic percussion, growl
basses. Three fixed algorithms, never more.

**TONEWHEEL — additive, made playable.** Pure additive (draw 64 partials)
is synthesis as data entry — the least playable paradigm there is. But
additive with a handle humans have loved for 90 years is **drawbars**:
eight harmonic sliders, a PERC click register, WARBLE (vibrato/chorus),
DIRT (drive). TapeOS loves sliders, and organ stabs on a 4×4 grid are
ridiculous fun. This is the additive engine.

**PLUCK — Karplus-Strong physical modeling (1983, era-correct).** The best
fun-per-parameter ratio in synthesis: essentially one knob (DAMP) and it
always sounds good. Macros: DAMP · BODY (resonator colour) · PICK (exciter
brightness) · DOUBLE (12-string detune). Kalimbas, nylon guitar, harps,
koto. Cheap to render, impossible to ruin.

**Free bonus, no engine required:** a chip-tune preset pack is VELVET
squares rendered through CRUNCH. Maximum kitsch, zero new DSP.

### The playability rules (these outrank the engine list)

1. **Preset-first, knobs-second.** Every engine is playable at first touch;
   3–6 macros, never a patchbay.
2. **Macros speak plain words** — BRIGHT, FAT, DIRT, WOOD, AIR — never
   "mod index". A macro moves several parameters underneath so it always
   does something musical.
3. **Bounded ranges.** You can't detune into garbage unless you ask; sweet
   spots are wide by construction.
4. **SCRAMBLE.** A dice roll within musical bounds on every panel, always
   undoable. Half of synth fun is slot-machine discovery.
5. **The instant loop.** Knob turn → re-render (one-shots render in tens of
   ms) → auto-retrigger, so every move is heard immediately.
6. **Sampling-era honesty.** Motion (LFO, sweeps) bakes into the render —
   you are sampling a synth, which is exactly the workflow the MPC was born
   into. Sustained key patches render a loopable sustain segment; loop-point
   authoring joins the keygroup export work.

## Keys need the keygroup door

Drum engines render one-shots onto pads — zero new export work. **Key engines
need KEYGROUP programs**: render the patch at several pitches (e.g. every
minor third across 4 octaves), export as a multisampled keygroup instrument
the MPC plays chromatically.

That makes keygroup export the gating dependency for TINES/VELVET keys — and
it's another golden-file job: save a keygroup program from real hardware,
template it, round-trip it (same method that built the drum writer). Worth
grabbing a keygroup export whenever the reference kits get made.

## The play surface stays 4×4 — settled

Keys are played on the same 4×4 pad grid as drums. No piano keyboard UI,
ever. This is consistency with the instrument, not a compromise: the MPC
itself plays keygroups on pads (Pad Perform), so what your hands learn in
SnipSnap is exactly what the hardware gives you back.

What follows from it:

- **Note layouts, not keyboards.** Bottom-left pad (A01) is the root.
  Two layouts at launch:
  - **CHROMATIC** — 16 semitones ascending left→right, bottom→top; just over
    an octave per bank.
  - **SCALE** — only in-scale notes on the pads (pick key + scale), 16 notes
    ≈ two-plus octaves, wrong notes physically impossible. The mode most
    people should live in.
  A fourths-stacked grid (rows offset by a fourth, chord shapes become
  grips) is a later third option.
- **Banks are octaves.** A/B/C/D shift the same layout up/down — the same
  muscle-memory move as drum banks.
- **Keys need note-off.** Drums are one-shots; keys sustain while held and
  release on lift. The play engine grows hold/release, per-patch mono mode
  with glide (SQUELCH-style basslines are exactly mono + glide), and slide
  becomes glissando in scale mode.
- **Export matches.** Rendered keygroup programs play the same way on the
  MPC's own pads; the root-note and layout conventions travel with the kit.

## Where it lives

- New `:synth` module, pure Kotlin, zero deps, same testing discipline.
  `DrumSynth` in `:audio` is the seed and eventually thins to a wrapper over
  THUMP presets.
- Patches are JSON (via `:json`), stored per-pad in `kit.json` (a synth pad
  keeps its recipe next to its rendered WAV, so it stays editable) and as
  shareable preset files. **Shipped**: `PadRecipe` (a [`Patch`] from any
  engine + an `FxChain`) serializes into `KitPad.recipe` — opaque to `:kit`,
  typed in `:synth` — and the factory kits carry it on every pad. The test
  is the promise: a kit folder regenerates its own WAVs bit-for-bit from
  nothing but `kit.json`.
- UI is a TapeOS control panel: sunken LCD scope showing the rendered
  waveform, chunky sliders, preset list in a sunken listbox. Peak-1996
  plausible — parameter synths in software are exactly the ReBirth-era move.
  Engine panels are where the loudest scheme's users get what they deserve.

## Phasing

| Phase | Ships | Depends on |
|---|---|---|
| S1 | **shipped** — `:synth` module: THUMP voices, SCRAMBLE, patch JSON, SYNTH KIT render + testkit export | — |
| S2 | **shipped** — CRUNCH character pass in `:synth`, works on captured snips too | — |
| S2.5 | **shipped** — the FX rack: EQ (RBJ three-band: BASS/MID/AIR, 0.5 = flat), SQUASH (punch), TAPE (wow/flutter + hysteresis drive + head wear, ChowDSP-inspired), ECHO (tape delay), SPRING (Schroeder reverb), REVERSE; `FxChain` fixes the order (reverse→eq→squash→crunch→tape→echo→spring), owns a total tail budget, serializes per-pad JSON | S2 |
| S2.6 | **shipped** — SMEAR joins the rack (reverse→**smear**→eq→…): the STN transient mask taken out by AMOUNT, peak-matched; the `smeared` character; on the phone, the PAD SHEET's second TREATMENT row | S2.5 |
| S3 | **shipped** — TINES percussion voices (BELL/CHIME/BLOCK/ZAP/TOY, 2-op FM, snapped RATIO) fill A13-A16 of the factory kit | S1 |
| S3.5 | **shipped** — PLUCK (Karplus-Strong, 4 body voices) + TONEWHEEL (8 drawbars + PERC/WARBLE/DIRT, 3 registrations) render one-shots; TUNE snaps to semitones; `SynthKits.melodic()` is a playable pentatonic kit | S1 |
| S3.7 | **shipped** — VOX (three-formant vocal synthesis: VOWEL morphs A→E→I→O→U, CHOIR/ROBOT/GHOST) + GRAINS (granular resynthesis — the engine that eats captures: SIZE/SMEAR/DRIFT/PITCH/SHINE over any source snip); `SynthKits.cloud()` is the atmosphere kit | S1 |
| S3.6 | **shipped** — VELVET one-shot stabs (BASS/BRASS/SQUELCH/CHIP: naive saw/pulse stack + sub, resonant SVF with envelope sweep, SHAPE/FAT/CUTOFF/SQUEEZE/DECAY) — and the chip-tune bonus is real: `SynthKits.chip()` runs VELVET squares and THUMP/TINES drums through one CRUNCH converter | S1 + S2 |
| S3.8 | **shipped** — FATHOM bass voices (DEEP/GRIND/GLASS: sine with a SWEEP attack blip, a SPREAD-detuned saw pair, low-tuned 2-op FM with a snapped RATIO), engine-owned DRIVE before the filter, and GLIDE on every voice | S1 |
| S4 | **shipped** — keygroup export in both generations: `KeygroupWriter` (`.xpm`, corrected line-by-line against commercial programs) and `Mpc3TrackWriter.writeKeygroup` (`.xty`, corpus-guarded); hardware load check pending | reference corpus (done) |
| S5 | **shipped** — the instrument suite: `Keys` renders engines at exact MIDI pitch (EP from TINES with velocity-true soft/hard renders, Organ from TONEWHEEL with mathematically-cut sustain loops, Harp from PLUCK, Music Box from TINES), multisampled every minor third, packaged dual-generation (`.xty` + `.xpm` twin in one `_[TrackData]/`) | S3 + S4 |
| S6 | **shipped** — SKIN, a second drum engine alongside THUMP: modal synthesis rather than THUMP's oscillators (KICK/SNARE/TOM sum decaying sine partials at inharmonic ratios — a struck membrane's own recipe; STICK is that same recipe's single-partial limit; HAT_CLOSED/HAT_OPEN/RIDE run continuous noise through a bank of resonant filters; SHAKER runs continuous noise through one deliberately wide, non-resonant filter), eight voices classifier-verified against THUMP's own `DrumClass` gates where a dedicated class exists, PUNCH on every voice | S1 + U3 + U5 + U6 |

S1 and S2 are pre-app-buildable in this repo with CI coverage, same as
everything else. S4 is the one that needs hardware again.

## Placement

This slots after the app MVP (capture → kit → export must ship first — the
synth makes kits better, capture makes the app exist). CRUNCH is the likely
queue-jumper: it improves captured kits, which is MVP territory.

## What comes after S5

S1–S5 shipped this roadmap's original scope — but it built thirty voices
and no presets, and rule 1 above ("preset-first, knobs-second") is therefore
only half true. [`docs/SYNTH_UPGRADE.md`](SYNTH_UPGRADE.md) picks up there:
the preset library, a SCRAMBLE that lands, punch, stereo, and MATCH — the
capture-aware preset pick that no standalone synth can copy. That upgrade's
own non-goals parked "a second drum engine" as scoped-out, not permanent;
S6 is that decision revisited and shipped. SKIN itself still has no preset
roster of its own — `Presets.kt` has no SKIN branch — so it inherits, not
solves, the "no presets" gap above; a `SkinPresets.kt` is follow-up work,
same as it was for THUMP before U1.

## S7 — SNAP: the photo engine

The app's name has two halves and until S7 only one of them was cashed.
SNAP (`synth/Snap.kt`, `Photo.kt`, `SnapPatch` in `Patches.kt`) turns a
picture from the phone's camera into a pad, two ways at once, both chosen
because they land on a *one-shot* the pipeline already knows how to trim,
place and export — not on a minute of spectrogram texture:

1. **Wavetable.** One line read through the photo is one cycle of a
   waveform: 256 brightness values, looped at a pitch. The voice picks the
   line — HORIZON reads left to right (each column averaged top to bottom,
   so it is the picture's silhouette, not one noisy row of pixels), PLUMB
   reads top to bottom the same way, ORBIT walks a ring around the centre.
   A ring closes on itself, so that cycle wraps with no seam; the other two
   get a 16-sample blend at the seam so an unrelated pair of end pixels does
   not click once per period. A skyline is buzzy, a gradient is soft,
   stripes are harmonic.

2. **Feature mapping.** The photo's summary numbers (`Snap.look`: mean
   brightness, contrast, mean saturation, saturation-weighted circular mean
   hue and how strongly the photo agrees on it, and the mean brightness step
   between points one cell apart on a 128-cell grid, across and down) set
   the four macros (`Snap.macrosFrom`): hue → TUNE across two octaves from
   A2, red low and violet high, the circle cut at rose (330°) so a 355° red
   and a 5° red are neighbours at the bottom of the knob rather than two
   octaves apart; a grey photo, or one whose colours cancel (half red, half
   cyan has no dominant hue), lands on the centre detent; brightness →
   BRIGHT (the filter, floored so a night shot is dark, not inaudible);
   colourfulness → DECAY; detail → GRIT, measured on a grid so a 160 px
   thumbnail and a 1024 px one of the same scene agree and a horizontal
   stripe counts as much as a vertical one. Every mapped value is bounded
   the way SCRAMBLE's are, so no photo lands on garbage. They are starting
   points to wreck: the sliders stay live and AS SHOT puts them back.

The voice is a wavetable oscillator (linear interpolation, rendered through
U6's 4x oversample and decimate because a 256-point table read at 440 Hz has
plenty above the band of its own and GRIT's drive makes more), into the
`TptSvf` low-pass that follows the envelope a little, into `Dsp.drive`,
under a 3 ms attack and a DECAY-set exponential tail. Renders are capped at
1.45 s so a pad stays under the classifier's 1.5 s LOOP line; at the top of
DECAY the tail outlasts its 500 ms TONAL line, and on a smooth line the
classifier calls the note a note (a square-wave stripe photo it hears as a
bright PERC — which it is).

What a pad stores is the 256 numbers and the macros, never the photo:
`SnapPatch` writes a `table` field beside `Patches`' common four, and the
WAV rebuilds from it bit for bit like every other synth recipe. The photo's
line is in the sidecar as plain digits — and a sidecar edited by hand meets
the same doors a photo does: a wrong-length, out-of-range or flat table is
a `JsonException` like any other malformed recipe (BREED and the recipe
replay already catch that), never a silent pad. The patch keeps its own
copy of the table.

Placement: like GRAINS, SNAP is outside SYNTH's `Engine` picker (it needs a
`Photo`, not a voice enum) and has its own tab. TAKE PHOTO uses the system
camera's `TakePicturePreview` contract — the small bitmap the camera app
hands back, no file, no storage permission, no CAMERA permission of our own.
"Small" is a promise some camera apps break, so the screen shrinks whatever
arrives to 512 px on the long side before reading it and catches the
`OutOfMemoryError` a 12 MP one would raise (`Copy.SNAP_TOO_BIG`), and it
keeps the line it read together with the chip and photo that read it, so
SEND TO PAD lands what was heard under the name of the chip that made it.
The one refusal comes in words (`Copy.SNAP_FLAT`): a line with no swing in
it — a plain wall read top to bottom, a clear sky read left to right — has
no waveform to play, and the screen says so instead of landing a silent
pad.

Not done, deliberately: a spectrogram scan (the Aphex trick) would need an
inverse FFT and produce textures, not hits; a preset roster (SNAP's presets
are photos); stereo.

### S7.1 — DRAW: the oscillator you draw

The photo was the first pen; a finger is the second. A SNAP pad is 256
numbers looped at a pitch and the engine never cared where they came from,
so DRAW (`synth/Draw.kt`, the DRAW chip and overlay in `SnapScreen`) is
only the pen:

- **`Draw.stroke`** lays a segment onto a table: every point the segment
  crosses takes its height there, the rest are untouched, either direction,
  clamped at the panel's edge. The screen sends one stroke per touch move
  from the previous position, so a fast finger that skips twenty points
  draws a line through all of them rather than dots. Every function returns
  a new array — a draft and a committed line never share storage.
- **Starting shapes**: `Draw.wave` (SINE, TRIANGLE, SAW, SQUARE, PULSE) for
  the cycle, `Draw.shape` (HOLD, FALL, PLUCK, SWELL, BOUNCE) for the volume.
  The surface opens over the line that is playing — the photo's, or the
  last drawing — so a photo line can be redrawn in part.
- **`Draw.smooth`**: a three-point average, circular for a cycle (whose
  last point neighbours its first), endpoints held for a shape (the note's
  start and finish are where they were drawn).
- **The volume shape** is `Draw.ENVELOPE_SIZE` (64) points across the
  note, stored as an optional `envelope` field beside the patch's `table`,
  read by `Snap.render` in place of the DECAY exponential under the same
  3 ms ramp; DECAY then sets only the length. A recipe without the field
  reads as it always did. A shape that never opens is refused at both doors
  (`Copy.SNAP_SHAPE_SILENT` on the surface, a `JsonException` from a
  sidecar), as a flat drawn wave is (`Copy.SNAP_DRAW_FLAT`).
- **`SnapVoice.DRAWN`** is the fourth line, with no photo behind it:
  `Snap.table` and `Snap.read` refuse it, `readsPhoto` says which lines a
  photo can be read along, and the screen keeps a drawn line current for as
  long as the DRAW chip is the selected one.

The surface auditions on every change with the sliders' own debounce, and
the main screen's render loop stands still while it is open so the two
never fight. DONE commits the wave only if it was drawn on or a starting
shape was picked (a visit to set the SHAPE alone leaves the photo's line
the photo's), and the shape only if one was drawn; CANCEL and back leave
everything as it was.

A hardening pass over DRAW (an independent read plus this document's own)
settled the following: the last drawing is kept across chip taps and new
photos and comes back through the surface's LAST button (a photo chip's
read used to replace it and DRAW reopened on blank); opening the surface
plays nothing until the first stroke, and closing it does not replay what
it just played; a new photo selects its own HORIZON line rather than
landing the photo's knobs on a drawn one; a shape "opens" only if it
reaches `Draw.OPENS` (8 of 255, -30 dB) — `any { it > 0 }` let a one-level
bump through that `normalize` then lifted to a full-scale click — and every
door uses that one check; `"envelope": null` in a sidecar reads as no
shape; FALL is drawn to end where the undrawn note is cut
(`Snap.LENGTH_OVER_T60`); the panel draws point i at the centre of the
column the pen maps a touch onto, so the line and the finger agree; a
touch with a NaN in it, or a table with no points, draws nothing; and a
shape drawn over nothing is kept with a toast saying why it is not yet
heard.

### S7.2 — PHOTO FIELD: the whole picture under a finger

SNAP reads one line through a photo and DRAW draws one; a photo has two
dimensions and a million pixels. The GRAIN FIELD screen (`docs`: the pad
sheet's scatter, `GrainField.analyze` in `:audio`) already plays a scatter
of short grains from wherever a finger is, and all it needs is a sample and
a list of positions — a `GrainField.GrainMap`. So the picture is the map.

`PhotoField.build` (`synth/PhotoField.kt`) cuts the photo into a grid
(16×12 by default, a cell at least a pixel each way so a photo smaller
than the grid reads in overlapping cells rather than refusing), and for
each cell: `Snap.look` for its reading and `Snap.macrosFrom` for its
knobs, `Snap.table` for its HORIZON line — or a SINE from `Draw.wave` when
the line is flat, because the field must sound everywhere and a patch of
clear sky is a pure tone at its hue — then `Snap.grain` for a steady
`GRAIN_FRAMES` (4096, ~93 ms) tone of it, HOLD-shaped under the usual ramp
since the field's voice windows every grain itself, scaled by the cell's
brightness between `DARKEST_LEVEL` (0.25) and 1 so a night shot still
speaks. The grains sit end to end in one source and the map places grain
i at the centre of cell i, y down as the canvas draws it. No
`Projector` comes with a photo, so DUET's chip stays hidden.

`Snap.grain` is `render` with a length of the caller's choosing
(`synthesize`'s `lengthSeconds`), through the same oversampled path, cut to
the exact frame count the field addresses by stride.

On the screen, FIELD builds the field once per photo (off the main thread,
`Copy.SNAP_FIELD_BUSY` on the LCD meanwhile) and opens `GrainFieldScreen`
with a `PrebuiltField` — the field's sample and map, the photo as a
backdrop drawn dimmed under the dots, PHOTO FIELD for a title and ◄ SNAP
for the way back — in place of loading and analyzing a pad. SNAP's own
audition stops when the field opens and its render loop stands still while
it is up, so the two voices never overlap.

CLOUD is `PhotoField.cloud`: the field's whole source through
`Grains.render` (2.5 s, seeded), landed on a pad through the same slot
chooser as SEND TO PAD, as audio with no recipe — every GRAINS pad is its
own truth — classed LOOP, named Snap Cloud.

Deliberately later: capturing a drag across the field as audio (GRAIN
FIELD's own declared next phase, not built for pads either); the
spectrogram reading of a photo (the image as a picture of sound, through
the spectral door and `Pghi` the retune already uses) as a second field
mode; a KEY lock that pins every cell to one note so the field varies only
in timbre.

A hardening pass over PHOTO FIELD (an independent read plus this
document's own) settled the following. The voice re-triggers on a fixed
512-frame clock and a photo cell's grain is a steady tone from phase zero,
so copies of one grain overlap-added at a fixed period comb-filtered each
other — near silence at the notes whose period divides the hop badly (a
JVM overlap-add of a 311 Hz grain measured under a sixth of its jittered
level); `GrainMap.jitterTriggers` asks the voice for hops drawn between
half and one-and-a-half of the clock, which scatters the phases, and a
photo field sets it while an analyzed pad's map keeps the even cadence.
`Snap.grain` renders at native rate, the one place SNAP does, because two
hundred oversampled-and-decimated grains took four seconds on a desktop
and the voice windows and mixes them eight deep anyway. `Snap.table`
interpolates between the pixels of a line narrower than the table instead
of repeating each one, so a 32-pixel cell (or a thumbnail) is a curve and
not a staircase buzzing at the pixel rate. A cell whose swing is under
`SOFT_SWING` (24) is blended toward a sine in proportion, since the cycle
is normalized to full scale before it plays and a four-level sky would
otherwise be a full-scale four-step square. On the screen: a build that
lands after a newer photo drops itself, a new photo closes a field that
is up (the main loop stood still behind an overlay no longer shown), TAKE
PHOTO waits for a build, the field overlay catches touches so a tap in a
gap cannot reach AUDITION and play SNAP's voice over the field's, the
prebuilt field is remembered so a recomposition does not tear the voice
down mid-drag, the way back consumes its quiet even with nothing to
render, and CLOUD onto a synth pad clears the pad's recipe (replaceAudio
keeps one when handed null, and the old note would have regenerated over
the cloud on the next rebuild from the sidecar).

### S7.3 — A KIT FROM ONE PHOTO

`docs/PHOTO_SPECS.md` §1, built as specced. `PhotoKit.build` (`synth/PhotoKit.kt`)
is `PhotoField.build`'s own reading of a picture, cut 4×4 instead of 16×12,
each cell landing as a full SNAP **pad** (`Snap.render`, a note) rather than
a grain: `Snap.look` for the reading, `Snap.macrosFrom` for the knobs,
`Snap.table(HORIZON)` plus `PhotoField.cellTable`'s own blend toward a sine
for the line, so a flat cell never refuses here either — the kit must fill
every pad, same as the field must sound everywhere. Cell (column, row),
row 0 the photo's top row, lands at slot `13 - 4×row + column`: (0, 0) is
A13, (0, 3) is A01 — `PadBanks`'s own top-row-first numbering, the same
geometry `KitArt`'s GRID style and `KitScreen`'s own grid already draw.
`AutoPlace` never runs: the layout is the picture's, not the drum
convention's.

Colour needed one seam: `Snap.Reading` gained `meanRgb` (a third pair of
accumulators alongside `Snap.look`'s luminance and hue sums, packed through
`Photo.rgb`), and `ArrangedPad` gained an optional `colorHex`, checked
first in `KitAssembler.assembleArranged` ahead of `AutoPlace.colorFor` —
every other caller passes none and gets the class colour exactly as
before. The recipe is the pad's `SnapPatch` (`PadRecipe(patch =
patch).toJsonValue()`), so a photo kit regenerates from its `kit.json`
sidecar like every other synth kit; it is not a folder of anonymous WAVs.

Landing is `KitShelf.landPhotoKit` (`:app`) — `render`'s own shape
(`freshName`, `KitAssembler.assembleArranged`, a fresh `Entry`) off a
photo's own cells instead of a starter's seed — called from `App.kt`'s
`buildPhotoKit`, which mirrors `fresh` line for line: the same whole-app
`busy` overlay (`Copy.SNAP_KIT_BUSY`), the same shelf-list refresh, the
same `open`/`screen = AppScreen.KIT` landing. KIT ▸ sits beside FIELD ▸
and CLOUD ▸ on the SNAP screen; unlike them it leaves the screen, which is
why it rides the app's own busy lock rather than a local one the way
`buildingField`/`cloudBusy` do — the same shape `fresh`/`finishBreed`/
`texture` already use for "render offline, land on the shelf, open it."

### S7.4 — PRINT: capturing a drag across the field

`docs/PHOTO_SPECS.md` §2, built as specced. `GrainFieldScreen`'s own KDoc
called this out as "v1 is play-only… capturing the performance is the
declared next phase, not built yet"; PRINT is that phase, for the pad
sheet's own field as much as PHOTO FIELD's, since both share the one
`GrainFieldScreen` composable and the one `GrainVoice`.

`GrainVoice` gained `startPrint(): Boolean` / `stopPrint(): FloatArray?`
and three `@Volatile` fields in the same one-writer/one-reader shape
`targetX`/`targetY`/`gated` already use: `printing` (the UI writes it,
the render loop reads it once per block), `printed` (the render loop
writes it, `stopPrint` reads it back), and `printBuffer` — `PRINT_SECONDS`
(60, `SurfaceEngine.MAX_PRINT_SECONDS`'s own ceiling) worth of frames,
preallocated once in `start()` so the render loop itself never allocates,
the same discipline the mix block follows. The tap sits in `runLoop`
right after the block is clamped to `[-1, 1]` and before it is written to
the `AudioTrack` — a print holds exactly what the track was about to
play. The arithmetic (how much of one block still fits, stopping dead at
the ceiling) is `PrintTap` (`:shell`, new) — a pure function with its own
JVM test, since `GrainVoice` is Kotlin over `AudioTrack` and has none.
`stopPrint` waits one block's worth of wall-clock time before reading the
buffer back: the loop reads `printing` once per block, so the block
already in flight when STOP lands still has to land in the buffer first;
a print that reached the ceiling on its own has already stopped and needs
no such wait.

`GrainFieldScreen` gained a PRINT / STOP PRINT header chip beside DUET's,
an `● PRINTING` line over the field while one runs, and the landing:
`stopPrint()` off `Dispatchers.Default` (never the UI thread — the sleep
above would jank it), then `SnipStore.import` on IO, the exact shape
`SurfaceScreen`'s own PRINT already uses (`Copy.surfacePrinted`,
`Copy.PRINT_LOST` reused verbatim; a new `Copy.GRAIN_FIELD_NOTHING_PRINTED`
in place of `SURFACE_NOTHING_PRINTED`, whose wording says "hold the
surface" — true on SURFACE, not on a screen with no surface on it). A new
`onFieldPrinted: () -> Unit` callback threads through both call sites —
`App.kt`'s own GRAIN FIELD and, one level down, `SnapScreen`'s PHOTO
FIELD overlay — to the same `{ importCount++ }` shelf-reload `SURFACE`'s
own `onPrinted` already triggers. No PAD landing here: PRINT only ever
lands on TAPE, the spec's own recommended reading ("it is what was
heard"); a captured drag reaches pads afterward through the ordinary
TAPE → CHOP door, same as any other capture.

### S7.5 — TILT: tip the phone to play the picture

`docs/PHOTO_SPECS.md` §3, built as specced. `TiltSource` (`:app`) read only
roll (gravity along the device's X axis); it now reads pitch too (Y axis),
guarded and set independently of roll so a NaN or a missing reading on one
axis never holds the other back. Both are `@Volatile` floats, 0..1, flat at
0.5, written from the sensor thread and read from the UI/control-rate loop
— the same single-value contract `tilt` already had.

The mapping from a raw (roll, pitch) reading to the field's cursor —
dead-banded (`DEAD_ZONE`, 0.02: below this much change from the cursor's
last position, a reading is a still hand's jitter, not a move, or the
field would shimmer at rest) and smoothed (`SMOOTHING`, 0.5, DUET's own
factor) — is `TiltCursor.step` (new, `:shell`), a pure function with its
own JVM test, since `GrainFieldScreen`'s loops have none.

`GrainFieldScreen` gained a TILT header chip and a control-rate
`LaunchedEffect` in the exact shape DUET's own loop already has: every
tick, if no finger is down, `TiltCursor.step` walks `autoPos` toward the
phone's current tilt and calls `voice.setTarget`/`gate`, a finger always
winning over either automatic cursor the same way. DUET and TILT are
exclusive — the spec's own recommendation, "one automatic cursor at a
time" — enforced where each chip is tapped on: turning one on turns the
other off, since both drive the one `autoPos`/`voice` and nothing
reconciles two live writers of it. Available on both `GrainFieldScreen`
callers, the pad sheet's own field as much as PHOTO FIELD's, since
nothing about TILT is photo-specific — the chip only shows when
`TiltSource.available` (a gravity or accelerometer sensor exists).

The one thing this environment cannot settle: the Y axis's sign in
portrait. `SurfaceScreen`'s existing use of `TiltSource.tilt` already
fixed the X axis's convention on a real device; pitch's sign (tip away =
up or down) is an on-device check, named as such in `app/README.md`'s
verify line.

### S7.6 — PATH: walk a picture in time

`docs/PHOTO_SPECS.md` §4, built as specced, both landings — §1 shipped
first, so the ring landing had a kit to name pads on. `PhotoPath`
(`synth/PhotoPath.kt`, new) is three pure functions: `sample` resamples a
drawn polyline by arc length to a fixed step count (a fast stroke and a
slow one over the same line give the same walk; a path that lingers over
one stretch gives it no more points than a path that crossed it in a
blink), `cellsFor` maps each resampled point to a `PhotoKit` grid cell
(0..1 both ways, clamped, `PhotoKit.COLUMNS`/`ROWS` by default so a
walked path always names the same cells a photo kit from the same
picture would), and `render` walks the cells as one gapless loop.

`render` needed a third `Snap` shape alongside `render` (full
oversampled, decay-driven length) and `grain` (native-rate, exact
length, for a grain field's hundreds-at-once cost): `Snap.cut`, new,
oversampled like `render` but exact-length and HOLD-shaped like `grain`
— a path is at most 64 steps, not a field's hundreds, so the
oversampling cost stays affordable, and a walked path is one gapless
line, not a run of separate hits, so each step must hold to its own end
rather than decay away before the next starts. `PhotoKit.kt` itself
picked up a small refactor first: `cellAt`/`slotFor` pulled out of
`build`'s own inline loop, so `PhotoPath.render` reads a cell exactly
the way a photo kit's own pad does — one function, not two readings that
could drift.

`PhotoPath` stays `:synth`-only; it never builds an `Orbit` or
`PatternOrbit`, since `:synth` has no dependency on `:loop`. That glue —
`App.kt`'s new `buildPathRing` — mirrors `OrbitScreen`'s own
`addPatternRing`/`addSnipRing` shape from outside the screen: load
`orbits.json` if one exists (else `OrbitPresets.fromKit`, the same
tempo/rate fallback `OrbitScreen`'s own loader uses), refuse past
`OrbitSet.MAX_ORBITS` with the existing `Copy.ORBIT_RINGS_FULL`, append
a `PatternOrbit(kit, hits)` whose hits are `PhotoKit.slotFor(column,
row)` per step, save, and hand the result back into `App.kt`'s own
hoisted `orbitSet`/`orbitSelected` before navigating to `AppScreen.ORBIT`
— the same whole-app busy overlay `buildPhotoKit` uses, since RING
leaves SNAP the way KIT ▸ does. The loop-pad landing stays local to
SNAP, `sendPathToSlot` mirroring `sendCloudToSlot` line for line
(audio with no recipe, `DrumClass.LOOP`, the same `SlotChooserOverlay`).

DRAW's own overlay gained a third tab, PATH — shown only over a photo —
with a `PathLcd` panel (the photo dimmed underneath, `GrainFieldScreen`'s
own backdrop convention, plain top-down coordinates rather than
`DrawLcd`'s wave/shape y-up), a STEPS chip row (8/16/32/64), and its own
bottom row (`CLEAR | CANCEL | LOOP PAD ▸ | RING ▸`) in place of
WAVE/SHAPE's `SMOOTH | CLEAR | LAST | CANCEL | DONE` — PATH commits
nothing through DONE, since LOOP PAD ▸ and RING ▸ each land the walked
cells directly, the same way KIT ▸ leaves through its own callback
rather than a draft.

### S7.7 — DUET on a photo field

`docs/PHOTO_SPECS.md` §5, built as recommended (fixed at
brightness/loudness), with one correction to the spec's own design
sketch — see below. `GrainField.Projector` (`:audio`) is now an
interface, one method, `project(vector: FloatArray): Pair<Float,
Float>`; the hand-rolled PCA basis `analyze` fits from a sample's own
grains kept its shape and its `internal constructor` under a new name,
`PcaProjector`. Every call site outside `:audio` only ever called
`.project(...)` on whatever `GrainMap.projector` held, so the rename
touched nothing beyond `:audio` itself and two stale KDoc `[...]` links
in `GrainFieldScreen.kt`; the one test that reached past the interface
(`GrainFieldTest`'s degenerate-axis case, which reads `.degenerate1`/
`.degenerate2` — fields the interface has no business declaring, since
`AxesProjector` has no notion of a degenerate axis) now casts to
`PcaProjector` for those two lines instead.

`AxesProjector(x: Axis = Axis.CENTROID)` (`:audio`, new) is the other
`Projector`: no grains to fit a PCA basis from, so it reads `x` straight
off `Similar.vector`'s own named dimension (`Axis` enum, one entry per
vector index) and leaves `y` at the map's own 0.5 centre. That is the
one place this build deviates from the spec's own sketch of
`AxesProjector(x: Axis, y: Axis)`: loudness is deliberately not one of
`Similar.vector`'s nine dimensions (the class's own KDoc — "a quiet
snare is still a snare"), so a second `Axis` for y would have to
misname some other spectral feature as loudness instead of just not
having one. `PhotoField.build` (`:synth`) sets `projector =
AxesProjector()` on its map — the one-line change the spec priced — so
a photo field carries a projector unconditionally, unlike an analyzed
sample's, which is only ever present when `analyze` didn't hit the
degenerate/too-few-grains cases `PcaProjector`'s own KDoc already
documents.

The spec's "the tick maps y itself" line (its own stated smaller
option, next to threading level through the interface as a tenth
dimension) is what actually wires loudness to y, and it needed a real
few lines in `GrainFieldScreen.kt`'s DUET loop, not the "DUET's code
changes not at all" the spec predicted for the interface-extraction
bullet alone: after `p.project(vector)` returns, `y` is replaced with
`MicSessionService.level.value` (already read once per tick for the
silence gate, so no new mic read) whenever `p is AxesProjector`,
leaving a `PcaProjector`-backed field's own second principal component
untouched. DUET's chip visibility (`projector != null`) and every other
line of that loop needed no change — the polymorphic `.project(...)`
call was already the only thing DUET asked of a projector.

### S7.8 — BREED: two photos, one child's line

`docs/PHOTO_SPECS.md` §6, built as specced. `crossTable(a: IntArray, b:
IntArray, coin: () -> Int)`, a new top-level function beside `object
Draw` in `Draw.kt` (not a method of `Draw` itself — crossing two lines
is breeding arithmetic, not "the pen" `Draw`'s own KDoc says that object
is), crosses two same-length point arrays point by point: `coin()` spun
fresh for every point, 0 → A's own value, 1 → B's, anything else → the
mean — the identical three-way shape `Breed.pick` already flips for a
macro. Size-agnostic on purpose: the same function crosses a SNAP
table's 256 points and a drawn envelope's 64. The coin is a callback
rather than a concrete `Random` type so `Breed`'s own `java.util.Random`
drives it directly — every other seeded function in `:synth` uses
`kotlin.random.Random`, and `crossTable` taking a `java.util.Random`
parameter just to suit one caller in another module would have been the
wrong module owning the convention.

`Breed.cross` (`:shell`) gained one branch: when both parents' patches
are `SnapPatch` (already guaranteed same engine and voice by the
surrounding check), a new private `crossSnap` crosses their tables
through `crossTable`, and their envelopes too when both drew one — a
new `crossEnvelope` giving a shape only one parent has the identical
"rides half the time" treatment `crossMacros` already gives a rack
section only one parent has. `SnapPatch`'s own constructor is the one
place that can refuse the result (a flat table, or a shape that never
opens), so `crossSnap` treats that refusal the way `breed()`'s own
outer classifier audit already treats a mismatched class: one retry
(a full fresh spin of both table and envelope), then a fallback — here,
A's own line and shape verbatim, since a photo kit's own line is always
a valid `SnapPatch` to begin with. The crossed macros are decided
before any of this and ride through unconditionally, on the fallback
path too: a table refusing to cross is not a reason to also throw away
a macro cross that succeeded.

Nothing on the SNAP or BREED screens changed — the spec's own costing
was right about that: BREED's existing button already crosses whatever
`Breed.cross` hands it back, and this changes only what that call
produces for a SNAP pad.

### S7.9 — SPECTRUM: the photo as a spectrogram

`docs/PHOTO_SPECS.md` §7, built as recommended (log frequency, its own
button). `Spectrogram.read(photo, seconds, sampleRate, seed): Snip`
(`synth/Spectrogram.kt`, new) builds one `FloatArray(Spectral.BINS)`
per output frame and hands the whole grid to `Pghi.invert` — a synthetic
spectrogram grid, not a real STFT of anything, since the "signal" here
is a picture read as one. Two axes, read differently on purpose:

- **Time (columns).** Each frame's fractional photo-x position is
  interpolated the exact way `Snap.table`'s own `resample` handles a
  line shorter than its 256-point table — the linear-interpolation
  else-branch, generalized from `TABLE_SIZE` to `frameCount` — so a
  narrow photo's columns blend into each other rather than repeating in
  a staircase.
- **Frequency (rows).** Each magnitude bin *b* has one true frequency,
  `Spectral.binHz(b, sampleRate)` — the plain linear FFT spacing every
  other `:audio` caller already assumes — and *that* frequency is what
  the log scale maps onto a photo row (~40 Hz at the bottom, Nyquist at
  the top; linear would put nine tenths of the image above 2 kHz). The
  bin index itself is never log-spaced.

That second point was a real bug on the first pass, not a hypothetical
one: an earlier version computed a bin's "frequency" by *inverting* the
log-row formula as a function of the bin index — `hz(b) = 40 ×
(nyquist/40)^(b/(BINS−1))` — then immediately fed that same `hz(b)`
back into `ln(hz/40)/logSpan` to pick a row. The two operations are
exact inverses of each other, so the whole thing canceled algebraically
into a **linear** bin-to-row map, silently defeating the log scale
entirely. It surfaced as a correctness bug, not a crash: `SpectrogramTest`
painted a photo's row for a chosen 1000 Hz and probed the inverted
audio with a single-bin Goertzel detector expecting energy to
concentrate there: instead it found near-flat noise from 100 Hz up to
5 kHz and a sharp spike around 10 kHz — the tone had landed roughly an
octave and a half sharp of where it was painted, following the linear
map's own bin arithmetic rather than the log scale the picture was
drawn against. The fix reads each bin's real linear frequency via
`Spectral.binHz` first, *then* runs that through the log-row formula —
one direction only.

The other two knobs the spec named for keeping a photograph from
reading as hiss: `magnitude = luminance²` (a power curve, so a dark
photo is near-silent rather than carrying a hiss floor), and each
frame's own bins under 5% of that frame's peak floored to true zero
before `Pghi.invert` ever sees them. `SpectrogramTest` covers all four
of the spec's own suggested cases — a bright row's tone lands near that
row's frequency (Goertzel probe), a black photo inverts to exact
silence, a bright column's energy concentrates in the output's own
middle third (a "click" at that column's time), and a non-positive
`seconds` refuses outright — plus the regression case above.

Landing is SPECTRUM ▸, a new button beside FIELD ▸/CLOUD ▸/KIT ▸ on the
SNAP screen, needing only the open photo (not FIELD's own grid the way
CLOUD does) — `sendSpectrumToSlot` mirrors `sendCloudToSlot` line for
line: recipe-less audio, `DrumClass.LOOP`, the same `SlotChooserOverlay`.
No `Grains.render` pass over the result — the texture itself is the pad,
the same "both are recipe-less audio, as CLOUD is" reading the spec's
own landing section offered as the simpler of its two options.
