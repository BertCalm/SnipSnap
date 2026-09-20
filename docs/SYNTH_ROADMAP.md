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
