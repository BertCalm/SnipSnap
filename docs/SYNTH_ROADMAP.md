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
| S8 | **shipped** — RESIN, the ladder engine (BASS/LEAD/BRASS: a three-oscillator STACK through `Dsp.Ladder`, the four-pole transistor-ladder low-pass with tanh in the loop, measured before use; CUTOFF key-tracked, CREAM the feedback up to self-oscillation, CONTOUR the filter envelope's amount and speed on one knob) and CONTOUR, the same filter as a rack section after EQ — design in `docs/superpowers/specs/2026-09-24-resin-ladder-engine-design.md` | S1 + U5 + U6 |
| S8.1 | **shipped** — RESIN, held: a RESIN patch as a nine-zone keys instrument that sounds while a key is held (ATTACK/RELEASE; loops of whole sub-octave periods with the detuned square snapped to one beat per loop and the pitch fitted to whole frames; CREAM capped at the self-oscillation threshold), from SYNTH's `MAKE INSTRUMENT ▸` or `snipsnap synth RESIN <VOICE> --instrument` — design in `docs/superpowers/specs/2026-09-25-resin-held-pad-design.md` | S5 + S8 |
| S8.2 | **shipped** — RESIN, droning: a RESIN patch as a loop-grid track (`DroneBlock`, a recipe rendered at bake time) that breathes through the ladder (MOTION, BREATHS 1/2/4) and spans the fewest intervals that keep its note within 3 cents, every oscillator and breath whole cycles per loop so the wrap is exact; re-sliced on every tempo change, and never rendered on the engine thread; from SYNTH's `DRONE TO LOOP ▸` or `snipsnap synth RESIN <VOICE> --drone` — design in `docs/superpowers/specs/2026-09-25-resin-drone-design.md` | S8.1 + loop grid |
| S9 | **built, awaiting the audition gate** — TIDE, the West Coast engine (BONGO/DRIP/GONG/FLARE: a phase-modulated sine through a triangle-core wavefolder into a note-keyed low-pass gate whose release slows as it falls; FOLD/WARP/GLOW/DECAY/WANDER, RATIO on GONG and FLARE, DECAY holding GONG and FLARE open; WANDER seeded from the recipe and a take index; eighth-order band limit before decimation; forty presets) — spec and as-built notes under S9 below | S1 + U5 + U6 |
| S10 | **Phase 1 shipped** — GLINT, the phase-distortion engine (REED/BOTTLE/KAZOO: a sine burst at `k`× the fundamental windowed to zero by each cycle's end, so the formant sweeps while the pitch does not move; TUNE/PEAK/FOLLOW/BODY/BLOOM/DECAY, FOLLOW morphing the peak between absolute Hz and note-tracking over `Dsp.keyTrack`, PEAK snapping to integer harmonics up to k=12, the body decaying faster than the burst so a note fades to glass) — design in `docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`. TRACE (the window taken from your own material) and the preset roster are Phases 2 and 3, gated on the audition. | S1 + U5 + U6 |

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

### S7.10 — LIVE: the camera preview as a continuous voice

`docs/PHOTO_SPECS.md` §8, built with its own recommended gate (§1–§3
played on a phone first) explicitly waived — there is no phone in this
session to play them on, and the answer, asked directly, was to build
it anyway.

**The native voice.** `LiveSnapEngine.cpp`/`.h` (`app/src/main/cpp/`,
new) is a wavetable oscillator, not a grain field: one `LiveTable`
(256 points, `Snap.liveCycle`'s own shape — already −1..1, zero-mean,
seam-blended) read every sample, cross-fading to a new table over a
fixed ~50 ms window rather than swapping in a click, at the *same*
phase on both sides of the fade so the blend is of two comparable
cycles rather than two waves drifting apart in time. The table handshake
is `SurfaceEngine::adoptPendingSample`'s own single-slot pointer
convention, copied rather than reinvented: a `pending_`/`retired_` pair
of atomics so the audio thread only ever adopts and never frees, and the
UI thread only ever frees what the audio thread has already retired.
Three macros — TUNE, BRIGHT, GRIT — glide through `ParameterSmoother`
exactly as `SurfaceEngine`'s own corners do; BRIGHT's smoother doubles
as the actual audio-rate lowpass filter itself (`filter_.setTarget(sample);
filter_.next()`), the same object playing both roles because a one-pole
follower and a one-pole lowpass are the identical piece of math. DECAY
is read off every frame (`Snap.macrosFrom`) and shown on its own slider,
but never sent to the engine at all — the class's own header KDoc says
why: a continuous voice never stops sounding to decay away, unlike every
one-shot SNAP render.

Rate-dependent state (both smoothers and the crossfade's own frame
count) is sized in a private `configureForRate(fs)`, called once from
the constructor at the default 48000 Hz and again from `start()` at
whatever rate the device actually opens — `SurfaceEngine`'s own
constructor keeps the same split, because the host test harness
(`app/src/main/cpp/test/`) constructs an engine directly and calls
`onAudioReady` without ever calling `start()`.

**A real bug the host tests caught before a phone would have.** The
first draft's `setMacros()` correctly stored its three targets into
atomics, but nothing in `onAudioReady()` ever read them back into the
smoothers — `tuneSmoother_`/`brightSmoother_`/`gritSmoother_` just kept
gliding toward whatever they were `.snap()`ped to at construction and
never moved again. Every macro was a dead knob: the engine compiled,
opened a stream, and made sound, and would have passed a listen test
that never actually moved BRIGHT. A new test,
`live_snap_engine_bright_opens_the_filter`, caught it directly — two
renders at BRIGHT 0 and BRIGHT 1 came back with the identical filtered
level (confirmed with a stray `printf` before the fix: `dark=11.395226
lit=11.395226`, bit for bit) until `onAudioReady` was given three lines
reading `targetTune_`/`targetBright_`/`targetGrit_` into the smoothers'
own `setTarget` right after adopting a pending table. All five new
tests (silence with no table pushed, a table's pitch once one arrives,
a mismatched `pushFrame` length clamped rather than overrun, the
crossfade never clicking across a table swap, and this one) pass
alongside the suite's existing 97.

**The JNI/Kotlin owner shape.** `jni.cpp` gained a third `extern "C"`
block, `NativeLiveSnap` — the same create/destroy/start/stop/sampleRate/
needsRestart/isShared/latencyMillis shape `NativeSurface` and
`NativePads` already keep, plus `pushFrame`/`setMacros`.
`LiveSnapVoice.kt` is the Kotlin owner class, `SurfaceEngine.kt`'s own
shape copied exactly: a `Long` handle, every method `@Synchronized` and
a no-op once closed, `close()` idempotent. Both `CMakeLists.txt`s (the
production `add_library` and the host test's `add_executable`) list
`LiveSnapEngine.cpp` — the two-source-lists footgun the steward skill
warns about, watched for on purpose this time.

**`Snap.liveCycle`.** `:synth`'s own `cycle(table)` — the raw table to
the seam-blended, zero-mean cycle a wavetable oscillator actually
reads — was `internal`, unreachable from `:app`'s native/Kotlin tree. A
one-line public `Snap.liveCycle(table) = cycle(table)` is the only
`:synth` change this section needed.

**The screen and the camera — unverified beyond static review.**
`LiveSnapScreen.kt` (`app/src/main/kotlin/com/snipsnap/app/ui/`, new) is
CameraX `Preview` + `ImageAnalysis`, bound to the screen's own
`LocalLifecycleOwner`, with `ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888`
so each analyzed frame becomes a `Bitmap` by a direct
`copyPixelsFromBuffer` (Android's ARGB_8888 byte order and CameraX's
RGBA_8888 byte order agree, which is the whole reason that output format
exists) rather than a hand-rolled YUV_420_888 conversion, downscaled
before `Bitmap.toPhoto()` (widened from `private` to `internal` in
`SnapScreen.kt` for exactly this reuse) feeds `Snap.table`/`Snap.look`/
`Snap.liveCycle` into the voice on the analyzer's own background thread.
The line just read is drawn over the preview; the four macro sliders
(TUNE/BRIGHT/DECAY/GRIT, `MacroSlider` reused read-only) track the same
reading; FREEZE lands the last frame on `SnapScreen`'s own `photo`/
`reading`/`macros` through the identical assignment TAKE PHOTO's own
camera-result callback already makes. `AndroidManifest.xml` gained the
CAMERA permission (optional `uses-feature`, so a camera-less device
still opens SNAP; LIVE ▸ asks and, refused, says so and closes) and
`app/build.gradle.kts` gained `androidx.camera:camera-core`/`camera2`/
`lifecycle`/`view` 1.4.1.

No Android SDK reaches this session — the native engine is proved by its
own host tests, `Snap.liveCycle` by a JVM test, but the CameraX bind,
the permission flow, the preview's own look and the live sound on a
real stream have only been read, never run. `app/README.md`'s on-device
checklist carries a LIVE line naming exactly that.

### S7.11 — CHORD: the photo picks a chord

`PhotoChord` (`synth/PhotoChord.kt`, new) reads one photo as a chord and
lands the chord on the pads low to high, so `Arp` (`:loop`) can walk it.
Three readings, each on its own axis so they cannot move together:

- **Root** from hue, through SNAP's own TUNE mapping, so the chord is
  rooted on the note the photo's SNAP pad already plays; a grey photo
  lands on the centre detent, A.
- **Third** from brightness: at or above mid-grey (`MAJOR_LUMINANCE`,
  0.5) is major, below is minor.
- **Seventh** from colourfulness, at the halfway point `Snap.macrosFrom`
  stretches saturation around (`SEVENTH_SATURATION`, 0.25).

`PhotoChord.tones` is every chord tone inside SNAP's two-octave TUNE
range, from the root's first appearance, so the root sits on A01 (the
`Scales` keys-on-pads rule). That is four pads for a high root (root,
third, fifth, root again) up to nine for a low seventh chord, so an UP
arpeggio across them always spans at least an octave inside one bank.
`build` renders every pad from the whole photo's HORIZON line (through
`PhotoField.cellTable`'s sine blend, so a plain wall still fills its
chord) and returns the slots an arpeggio should walk beside the pads.

Each pad wears `PhotoChord.colorFor` its pitch: `Snap.hueForSemitones`
(the TUNE mapping run backwards, new and shared) at full saturation, so
photographing a pad's light and reading it with SNAP plays that pad's
note. `PhotoChordTest` holds that round trip for every semitone, by
pitch class, since the hue circle closes and the bottom and top A share
a colour.

Not done: the landing. `:synth` cannot see `:loop`, so the glue that
lands the pads on the shelf and writes an `Arp.run` ring over
`Built.slots` belongs in `:app`, beside `buildPathRing`, and has not
been written.

### S7.12 — TELEPHONE: a sound whispered through pictures

Every door before this one runs picture to sound. `Spectrogram.portrait`
(new) is the first the other way: a sound painted on exactly the scale
`Spectrogram.read` plays a picture back on, columns of time by rows of
log frequency. The two share `rowForBin` (pulled out of `read`, whose
behaviour is unchanged; `SpectrogramTest` still passes as it was), so
each is the other's way back. A row takes the loudest bin `read` would
light from it, so a partial between rows is never averaged into the
dark; brightness is the square root of level against the portrait's
peak, `read`'s power curve undone. Frames are kept when their centre
lies inside the audio. `Spectral` pads a frame of silence at each end,
and drawing those would add a black margin that the next `read` plays
as extra time. Reverting that filter alone turns the edge column of a
steady tone from 0.73 to 0.0, which is what the edge test asserts
against.

The colour carries pitch as SNAP hears it, `Photo.tint` (new) shading a
row's hue to exactly the brightness the level asks for. Rec. 601 luma is
a straight sum of the channels, so the tint changes nothing `read` sees,
to within a channel's rounding. It changes what SNAP sees: a 220 Hz
tone's portrait reads through `Snap.look` as TUNE at A3.

`Telephone` (`synth/Telephone.kt`, new) is the loop: `pass` paints a
sound and reads the portrait back at the sound's own length, and `chain`
repeats it up to `MAX_GENERATIONS` (16) times, each pass hearing only
the one before. A pass keeps where the energy sits in time and pitch
(`TelephoneTest` holds 440 Hz within 3% through one) and loses phase,
anything under a frame's 5% floor, detail finer than a row or a column,
and whatever 8-bit pixels round away. Deterministic per seed.

Not done: the screen. The portraits want showing in a row as the
generations drift, with any generation's sound one tap from a pad.

## S9 — TIDE: the West Coast engine

**Status:** S9 built (engine, presets, tests, SYNTH picker, testkit
kit); **waiting on its audition gate** before S9.1. Where the build
departs from this spec, and why, is under "As built" below.

RESIN (S8) is the East Coast half of synthesis: a rich wave, cut down by
a resonant filter. TIDE is the other half. It **builds** harmonics instead
of cutting them: a plain sine is bent, folded and modulated until it is
bright, and then a low-pass gate closes brightness and level together, the
way a struck object goes quiet. The signature result is the "bongo": a
woody, wet, pitched knock with nothing like it anywhere else in the picker.

Guardrail: the style is named after the coast, never after its makers.
Nothing on a product surface (engine, voices, presets, descriptions,
commits) names a manufacturer, a module or a model number. The words
below (wavefolder, low-pass gate, complex oscillator) are generic
terms of the craft. The guard that enforces this,
`PresetTestSupport.trademarkBlocklist`, knows only the drum-machine and
keyboard makers today, so S9 extends it with this style's own: `buchla`,
`serge` and `make\s*noise`, plus their model numbers as they come up in
review.

### Why a new engine and not a preset

| Already in `:synth` | Where |
|---|---|
| Two-operator FM with a snapped RATIO | `Tines` (`RATIOS`, `strike`) |
| A resonant two-pole low-pass | `Dsp.TptSvf` |
| 4× oversampled render, decimated to `RATE` | `Dsp.OVERSAMPLE`, `Dsp.decimate` |
| Loudness levelling for melodic voices | `Dsp.levelTo` at `Dsp.MELODIC_LOUDNESS_TARGET` |
| Deterministic per-render seeds | `Dsp.seedFor`, `Dsp.Noise` |

So TIDE is only worth building for the three things none of that does:

1. **The wavefolder.** Past full scale, a folder reflects the wave back on
   itself instead of clipping it, so each extra unit of drive adds a new
   fold and a new set of odd harmonics. A sine stays a sine at zero, and
   turns glassy, then vocal, then snarling as FOLD rises. Nothing in the
   tree adds harmonics this way. `tanh` drive (RESIN, FATHOM) squashes;
   folding multiplies.
2. **The low-pass gate.** One control drives a VCA and a low-pass together,
   through a slow-to-let-go response (the light-dependent resistor of the
   original circuits). Brightness and level fall at once, and the tail
   slows as it fades. That coupling is the "bongo". A VCA after a filter
   envelope can approximate it, but only by accident.
3. **Uncertainty.** A smooth, seeded random source that nudges timbre and
   decay a little per note, so sixteen hits of one pad are sixteen
   slightly different knocks. It is baked, deterministic and bounded.

### Signal path

Rendered at `Dsp.RATE * Dsp.OVERSAMPLE` and decimated at the end, like
every engine. Folding is the most alias-prone operation in synthesis, so
oversampling is not optional here.

```
MOD sine (carrier × RATIO) ──phase-mod (WARP)──▶ CARRIER sine at the note
    ──▶ FOLDER (FOLD, opened by the strike, closing with the gate)
    ──▶ LOW-PASS GATE (VCA + two-pole low-pass on one control, DECAY)
    ──▶ decimate ──▶ levelTo(MELODIC_LOUDNESS_TARGET + voice offset) ──▶ fadeTail
         ▲
    WANDER: one seeded smooth-random line nudging fold depth, gate decay and
            WARP per note; never pitch
```

- **Phase modulation, not frequency modulation.** The carrier's phase is
  pushed around; its frequency never moves. Pitch therefore stays exactly
  on the note at any WARP, which keeps TUNE, `Keys`, SPREAD and the
  keygroup export honest. Through-zero FM would sound close but drift the
  pitch the detector reads.
- **Sine folder:** `y = sin(π/2 · drive · x)`, drive `1 → 6` from FOLD. It
  is smooth, so it aliases less than a triangle folder at the same
  brightness, and at drive 1 it passes a sine untouched. A small fixed DC
  offset before the fold, set per voice, adds the even harmonics that
  keep it from sounding like a square.
- **Gate control `c`:** rises in about 2 ms, then falls as
  `dc/dt = −c / τ(c)` with `τ(c) = τ₀ · (1 + 3·(1 − c))`, so the tail
  slows as it fades. Gain is `c^1.3`. Cutoff maps `c` from 60 Hz up to
  18 kHz, key-tracked through `Dsp.keyTrack` so a high note is not
  darker than a low one at the same setting. Resonance stays low (a gate is
  not a squelch filter); BONGO alone gets a little, for the pop.
- **The fold closes with the gate.** Fold depth is `FOLD × c`, so a hit is
  brightest at the strike and mellows into its tail, the second half of
  what makes it sound struck rather than switched.

### Macros

Plain words and bounded ranges (playability rules 2 and 3). Every voice has
the first five; RATIO appears only where it is the point.

| Macro | Moves | Range |
|---|---|---|
| **TUNE** | the note, snapped to semitones from the voice's root | 24 semitones, like VELVET/PLUCK/VOX |
| **FOLD** | folder drive at the strike | 1× (clean) → 6× |
| **WARP** | phase-modulation depth | index 0 → 3, `Dsp.expMap` |
| **DECAY** | the gate's `τ₀` | per voice, e.g. BONGO 20 ms → 400 ms |
| **WANDER** | how far the random line strays, per note | 0 (identical hits) → ±25% on fold, decay and WARP |
| **RATIO** | modulator : carrier, snapped | `Tines.RATIOS` (GONG, FLARE only) |

### Voices

| Voice | What it is | Root | Expected class |
|---|---|---|---|
| **BONGO** | the signature: sine, ratio 1, light fold, a short gate with a touch of resonance | C3 | TONAL |
| **DRIP** | high and very short; the pitch chirps into the note over its first 15 ms and lands on it before the detector's window opens | C5 | PERC or TONAL |
| **GONG** | an inharmonic RATIO, long gate, low fold: metallic and ringing | C3 | TONAL (pitch may read unclear) |
| **FLARE** | the lead/stab: the fold opens wide and closes slower than the gate, a brassy "wah" of harmonics | C2 | TONAL |

"Expected" means the mapping in `SynthScreen`'s `drumClass` table mirrors
what the classifier test actually measures, the rule FATHOM set, not what
this table guesses.

Presets (`TidePresets.kt`) ship in the first phase, 8–12 per voice, named
for the sound: WOOD BONGO, RAIN DRIP, TEMPLE GONG, SNARL FLARE. SCRAMBLE
uses `Dsp.scrambleNear`, so a dice roll stays inside each voice's sweet
spot.

### It already works with what shipped

- **SPREAD** (PR #327): TIDE keeps exact pitch, so one BONGO spread across
  a bank in MIN PENT is the classic West Coast plucked pattern in one tap.
  WANDER never moves pitch, so the pitch SPREAD detects is the pitch every
  pad plays. GONG may read as no clear pitch, and SPREAD already handles
  that.
- **The rack:** SPRING and ECHO after a BONGO is most of the genre's
  ambience; nothing new is needed.
- **The CLI:** `snipsnap synth TIDE BONGO --all --out <dir>` works once the
  engine is registered, so every preset can be heard without a phone.

### Tests (CI measures the sound, not just the code)

- **Pitch:** at every snapped TUNE step, BONGO and FLARE detect within
  5 cents with FOLD and WARP at 0, and within 10 cents with both at
  maximum. Folding and phase modulation keep the fundamental.
- **The gate's signature:** the spectral centroid of the tail is at least
  1.5× lower than at the strike (brightness closes with level), and the
  fall from −6 dB to −20 dB takes longer than the first 6 dB did (the
  tail slows).
- **FOLD adds harmonics:** the centroid rises across FOLD 0 → 0.5 → 1
  for every voice at its defaults.
- **Aliasing floor:** at FOLD 1, WARP 1 and the top TUNE, energy between
  harmonics stays at least 45 dB under the harmonic energy.
- **Determinism:** the same recipe renders bit-identical audio (the
  regenerate-from-`kit.json` promise); WANDER 0 makes take indices
  identical, and WANDER above 0 makes them differ with the same pitch.
- **Loudness:** within the band the other melodic engines are held to.
- **Identity:** each voice's defaults and presets classify as its mapped
  class, and 200 SCRAMBLEs are all audible and unclipped.
- **Recipe:** `TidePatch` round-trips through JSON and `Patches.fromJsonValue`.

### Phasing

| Step | Ships |
|---|---|
| **S9** | `synth/Tide.kt` (`TideVoice`, macros, render), `TidePatch` in `Patches.kt`, `TidePresets.kt` and its `Presets` branch, the tests above, TIDE in the SYNTH picker (…→ RESIN → TIDE → THUMP; README's engine count goes from nine to ten), and a `SnipSnap Tide Kit` generator under `testkit/` (`./gradlew :synth:generateTideKit`). **Ends at an audition gate**: nothing proceeds until the voices have been heard, the rule the synth-depth work set. |
| **S9.1** | FLARE and BONGO as keys instruments through S8.1's `MAKE INSTRUMENT ▸` path, via a `Keys.tide(midi)` renderer at exact pitch. A gate is a strike device, so "held" means the gate parks at a sustain level with the fold settled before the loop starts. Whether that still sounds like TIDE is a listening question. |
| **S9.2** | *Optional:* TIDE POOL, uncertainty driving the *notes*: a seeded, in-key random melody landed on the loop grid as an `Arp`-style ring over a spread bank. This is the genre's generative patch, and the one place WANDER is allowed to choose pitches (from the scale, never between them). |

### As built — 2026-09-25

Measured while building, and each one changed the design rather than
the test:

- **The gate is keyed to the note, not to a fixed floor.** Closed is half
  the note, open is 64 times it (held between 6 and 18 kHz), exponential
  between. With the spec's absolute 60 Hz floor the gate closed through a
  high note's fundamental almost at once, and a closing filter pulls a
  partial's phase as it passes: DRIP at C5 read −22 cents in its first
  60 ms. Keyed, the gate passes the fundamental late and quiet on every
  note. A small sag remains, the way a struck drum's pitch falls: `TideTest`
  holds a clean note within 10 cents while the gate is over half open and
  within 30 cents down to a tenth.
- **An eighth-order band limit at 19.5 kHz runs before decimation.** The
  shared decimator rejects only about 18 dB just above the new Nyquist.
  Measured on a steady fold at C6 (drive 6, index 3): 36.5 dB of clarity at
  4×, 37.1 dB at 8×, 45.3 dB at 4× with the band limit. The leak, not the
  render rate, set the floor, so TIDE keeps the family's 4× render.
- **Above C6, FOLD and WARP ease off with pitch** (`reachAt`). DRIP's top
  octave at full fold and full WARP puts harmonics past what 4× holds (32 dB
  clarity at C7 even band-limited, 41 dB at 8× at twice the render time).
  Only DRIP plays up there. `TideTest` holds every harmonic voice's top note
  at ≥ 45 dB and proves the measure sees aliasing (the native-rate render
  scores at least 10 dB worse).
- **DECAY is the time to −60 dB, per voice**, not τ₀ (ranges as revised
  below). The release slows by `1 + 1.5·(1 − c)`, not `1 + 3·(1 − c)`: at 3
  a long GONG ran to ten seconds.
- **RATIO snaps to whole numbers on FLARE (1–4) and to bell ratios on GONG**
  (1.4, 2.7, 3.5, 4.2, 5.8), not to `Tines.RATIOS` for both. A non-integer
  ratio on the lead made it a bell with no clear pitch.
- **BONGO and DRIP are PERC; GONG and FLARE are TONAL** (as revised
  below). The struck pair follows the classifier; the held pair follows
  RESIN's rule.
- **Velocity reaches TIDE through FOLD.** It joins `Velocity`'s brightness
  macros, so a soft strike folds less, not just quieter.
- **WANDER's seed is the recipe plus a take index.** The same take always
  renders the same bytes (the kit regenerates to the bit); take 1 differs
  from take 0 only when WANDER is above 0. Nothing lands takes on pads yet:
  a round-robin chain is where they belong.
- **Ten presets per voice**, forty in all, `TidePresets.kt`.

Open questions 1–4 are answered as recommended: TIDE, FOLD, the seed rule
above, and DRIP as PERC.

### Revision — GLOW and held notes, 2026-09-26

The audition said "more character and oomph" and "very staccato". A
measurement found why: the strike was rich and the tail was bare. Counting
harmonics within 40 dB of the loudest, with each of the three things that
close with the gate held open in turn (DECAY 1):

| | 0–40 ms | 40–120 ms | 120–280 ms | 280–600 ms |
|---|---|---|---|---|
| WOOD BONGO, as built | 13 | 6 | 3 | 1 |
| … WARP not following the gate | 14 | 6 | 3 | 2 |
| … fold not following the gate | 11 | 7 | 5 | 3 |
| … filter held open | 14 | 9 | 6 | 3 |
| SNARL FLARE, as built | 21 | 11 | 6 | 5 |
| … filter held open | 21 | 12 | 11 | 16 |

The filter took the most, the fold closing with it took the tail twice,
and WARP barely mattered. So:

- **GLOW**, a new macro on every voice: how much brightness outlives the
  level. At 0 the gate is as built (the classic knock). Up, the fold keeps
  up to 60% of its depth once the gate has closed, and the filter follows
  `c^(1 − 0.75·GLOW)` rather than `c`, closing behind the level. Defaults:
  BONGO and DRIP 0.3, GONG 0.6, FLARE 0.7. Measured, SNARL FLARE at its
  default keeps 13 harmonics at 40–120 ms where GLOW 0 keeps 6; WOOD BONGO
  at GLOW 1 keeps 5 at 120–280 ms where GLOW 0 keeps 1.
- **Held notes live in DECAY on GONG and FLARE.** DECAY's top half holds
  the gate fully open for up to 60% of the note before it releases, so a
  long setting is a held note that then rings out. A separate HOLD macro
  would have made GONG and FLARE eight macros; this keeps them at seven,
  THUMP SNARE's count, with RESIN's CONTOUR as the one-knob precedent.
  BONGO and DRIP never hold.
- **Longer ranges.** DECAY now runs BONGO 0.12–1.5 s, DRIP 0.06–0.8 s,
  GONG 0.4–4 s, FLARE 0.25–4 s; the struck pair stops at 2 s, the held
  pair at 4 s.
- **GONG and FLARE are TONAL.** Long enough to hold, the classifier reads
  GONG as LOOP for four of ten presets, SNARE for three, PERC for three;
  FLARE as PERC or LOOP by length. They are pitched notes, so they take
  RESIN's rule and are held to measuring harmonic instead. BONGO and DRIP
  still classify PERC (8 of 10 presets each) and stay PERC.
- **Level, by loudness.** A held note meets the loudness target under a
  low peak (SLOW SUNRISE: 3.3 s at peak 0.29), a short hit meets the peak
  ceiling first; the tests hold "at the target or at the ceiling", which
  is the rule the engine follows, instead of "peak over 0.5".

The character stages auditioned alongside (a pitch thump, body, drive,
click, punch) are parked, not built: GLOW answered the missing harmonics
first, and the next audition says whether the oomph is still missing.

### Revision — the edge, 2026-09-26

With GLOW in, the audition said the voices "walk the line between good and
weird" and asked to push further. Five levers were prototyped and
auditioned one at a time and together (four presets: WOOD BONGO, TEMPLE
GONG, SNARL FLARE, RAIN DRIP); the pick was all five at a moderate
setting. They are built in as how TIDE sounds, not as knobs: an EDGE macro
would have made GONG and FLARE eight macros, past THUMP SNARE's seven.

- **SWEEP.** The modulator starts 2.2 times its ratio and dives onto it
  with a 20 ms time constant: WARP's sidebands fall into place in the
  strike, a zap. Auditioned at 60 ms, it left SNARL FLARE's pitch
  unreadable into its second hundred milliseconds (read 196 Hz for 131,
  confidence 0.47); at 20 ms it reads true, confidence 0.85, by 50 ms.
- **CROSS.** The folded output feeds back into the modulator's phase, 0.75
  radians per unit, so fold and WARP argue instead of chaining. Feedback
  like this is clean while `CROSS · index · drive` stays under about 1,
  rough to about 1.6 and noise past it (mapped on a steady fold at C3:
  index 1, drive 6 kept 79 dB of clarity at CROSS 0.1 and 30 at 0.4; full
  FOLD and WARP at 0.75 had none, 0.6 dB). So the loop is held at 1.5, the
  rough side on purpose: SNARL FLARE's strike sits right there, and a full
  corner keeps its harmonics 25-40 dB clear, a snarl rather than a hiss.
- **TILT.** The fold's bias leans +0.4 rad at the strike to −0.4 as the
  gate closes: the even harmonics turn over across the note.
- **WOBBLE.** Three seeded random lines, stepped at 11 Hz and smoothed over
  6 ms, jitter the fold's depth and WARP's index by ±22.5% and the fold's
  bias by ±0.27 rad, so a held note is never quite still. Seeded from the
  recipe (and from the take only when WANDER is up), so a pad plays the
  same way every time and WANDER 0 still makes every take identical.
- **REACH.** FOLD and WARP reach 40% further: drive 8.4 at FOLD 1 (was 6),
  index 4.2 at WARP 1 (was 3). The "above C6" easing is replaced by one
  rule that knows the modulator: the brightest corner reaches about
  `drive · (1 + index · ratio) · note`, and where that passes 22 kHz both
  ease together (`reachAt`). Low notes get all of the extra; DRIP's top
  octave and FLARE's high RATIOs ease back. Measured on a steady
  full-corner fold, the worst, DRIP at C7, keeps 45.7 dB of clarity; at the
  old notion's 25.1 kHz it and FLARE's C4 at RATIO 4 sat at 44.3-44.5.

None of them touches a clean note: SWEEP and CROSS act through WARP's
index, WOBBLE's depth moves scale with FOLD and WARP, and TILT and WOBBLE's
bias fade in over FOLD's first tenth. `TideTest` holds FOLD 0, WARP 0 to
harmonics 50 dB under the fundamental.

What moved in the tests, and why:

- **Pitch is read from 100 ms, not 50.** At 50 ms SWEEP still has the
  modulator 10% sharp and BONGO at full everything read a step high. From
  100 ms every harmonic voice at full FOLD and WARP, both RATIO ends,
  three takes, reads the clean note's pitch, now with a confidence floor
  (0.6) so a note that turned to noise fails too. Every FLARE preset
  reads its note within 10 cents at confidence 0.8 from 100 ms.
- **GONG and FLARE are held to ringing harmonic from 100 ms.** The zap is
  noisy by design (TEMPLE GONG reads flatness 0.23 over the whole note,
  0.05 from 100 ms); the presets' rings all measure 0.08 or under.
- **GONG's corners clang.** At full FOLD and WARP, a bell ratio gives CROSS
  no period to lock to, and GONG measures noise-like there (flatness
  0.26-0.42, was 0.06-0.14). GONG is a bell with no one pitch, so this is
  scrap metal struck hard rather than a broken note, and it is kept.

The classifier now reads BONGO as PERC for all ten presets (was 8) and
DRIP for nine (was 8): the zap reads more struck. GONG and FLARE are
TONAL as before.

### Revision — the oomph, 2026-09-26

The character stages parked after GLOW were ported onto the engine as it
stands (GLOW and the edge in) and auditioned one at a time and in pairs,
on WOOD BONGO, LOW CONGA, RAIN DRIP, TEMPLE GONG, SNARL FLARE and FOLD
BASS. The pick was "g OOMPH": THUMP and BODY. DRIVE (saturation), CLICK
(a noise transient) and PUNCH (a transient shaper) were heard and left
out. Both are built in, like the edge: GONG and FLARE have no room for
an eighth macro.

- **THUMP.** The strike starts seven semitones sharp and falls onto the
  note with a 5 ms time constant, carrier and modulator together, the way
  a drum head's pitch drops as it is hit. At 20 ms 13 cents remain, and
  every pitch reading starts later than that. BONGO's top note reads
  623 Hz over its first 1-8 ms for a 523 Hz note.
- **BODY.** A clean sine on the carrier's phase, half the gate's level,
  under the same VCA: the note itself under the fold. The fold spreads a
  bright voice's energy up the spectrum and leaves the note thin. The
  fundamental's share of the first 150 ms, across FLARE's ten presets:
  −2.7 to −28.3 dB without BODY (SNARL FLARE the worst), −1.3 to −6.6
  with it. The struck voices, whose note already carried them, move
  under 1 dB.
- **BODY takes the note's polarity, once per note.** A fold and a RATIO
  above 1 can turn the note's fundamental upside down, and a sine added
  blind then cancels it: REED STAB (RATIO 3) lost 2 dB of note and
  HOLLOW HORN 5. A running read of the polarity was tried and dropped:
  under a fundamental 28 dB down (SNARL FLARE) it wavered, and BODY with
  it (SNARL FLARE fell to −12 dB, OCTAVE GROWL to −19). The sign of the
  whole note's correlation with BODY's sine, taken after the loop, is
  steady and always adds.

What moved in the tests: "brightness closes with the level" asked the
strike to be twice as bright as the tail. BODY's sine under the strike
pulls its centroid toward the note, most on DRIP (C6, the least fold
room): 1.9 times its tail with BODY, 2.6 without. The test now holds what
the gate actually does, more strictly: the tail closes to the note itself
(within 10%), and the strike is at least 1.5 times brighter. New tests:
the strike starts sharp and lands; every FLARE preset's note sits within
8 dB of the whole. The classifier reads BONGO and DRIP as PERC on all ten
presets each.

### Not doing

A patchbay or patch cables (rule 1: presets and macros, never modular);
real-time synthesis (TIDE renders offline like every engine); through-zero
FM (it moves the pitch, see above); stereo in S9 (mono first, as RESIN's
drone was; width is a later per-voice decision).

### Open questions

1. **The name.** TIDE is unused anywhere in the tree. COAST was the obvious
   pick, but `TapeDeck.Mode.COAST` already means a flicked reel coasting to
   a stop.
2. **FOLD, the word.** It is the genre's own word, but CHOP already has a
   FOLD layout. They live on different screens and mean different things;
   is that acceptable, or should the macro be BEND?
3. **WANDER and MOTION.** The synth-depth design says per-note randomness
   should make a *re-render* differ. The recipe promise says a kit
   regenerates bit-for-bit. This spec resolves it by seeding from the patch
   plus an explicit take index, so the same take is always identical and
   different takes differ. MOTION, when built, should use the same rule.
4. **DRIP's class.** Short and high reads PERC to the classifier. If it
   lands PERC, it stays out of SPREAD's pitch path unless a pitch is found.
