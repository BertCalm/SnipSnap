# Eight things a photo could still do, specced

Written 2026-09-21, the day after PHOTO FIELD's hardening round merged
(#292). SNAP, DRAW and PHOTO FIELD are on the default branch, each with a
hardening pass behind it; this document is the next eight, asked for as
one list and priced as eight sections. §1–§7 are now built
(`synth/PhotoKit.kt`, `GrainVoice.startPrint`/`stopPrint`, `TiltCursor.step`,
`synth/PhotoPath.kt`, `audio/AxesProjector.kt`, `synth/Draw.kt`'s own
`crossTable`, `synth/Spectrogram.kt`; `docs/SYNTH_ROADMAP.md` S7.3–S7.9);
§8 is not. Each ends with the decision that is not mine to make.

**Method, and the caution `SPECS_2026_09.md` taught.** Every "what
exists" line below was checked against the source on the day of writing,
and the check is printed so the next reader re-runs it rather than
trusts it. Where a seam is *almost* there — a type that would need one
more field, a constructor that is `internal` — that is said in words,
because the last spec document's largest errors were seams priced as
present that were one caller short.

**The order is a recommendation.** §1 first, because it turns the whole
photo idea into something a producer keeps: one photo, one kit, on the
hardware, in its own colours. §2 and §3 next, the two cheapest ways to
make the field feel alive. §8 last, the only one that needs plumbing the
app does not have.

---

## 1. A kit from one photo, the pads lit in its colours

### What exists

| | |
|---|---|
| `synth/PhotoField.build` | cuts a photo into a grid of cells, each read by `Snap.look` / `Snap.table`, rendered by `Snap.grain` |
| `synth/Snap.render` | a full pad (not a grain) from a table and macros — what a kit pad wants |
| `synth/Shuffle.kit` | a 16-pad kit from a seed, **classifier-audited**: a slot re-rolls until `Classifier.classify` lands in the slot's accepted classes |
| `kit/KitAssembler.assembleArranged` | `List<ArrangedPad?>` → a kit folder; `ArrangedPad(snip, cls, recipe)` carries a recipe |
| `kit/Kit.KitPad.colorHex` | a `#rrggbb` per pad, validated |
| `mpc3/Mpc3TrackWriter` | writes per-pad colours as packed ints when any pad carries one (`program.pads.any { it?.color != null }`) |
| `shell/KitArt` | the expansion browser tile, supplied as bytes to `ExpansionWriter` |
| `app/SnapScreen` | the photo, `thumb`, at ≤512 px; SEND TO PAD's kit write with `KitBuilderModel` |

Check it: `grep -n "colorHex" kit/src/main/kotlin/com/snipsnap/kit/Kit.kt`;
`grep -n "coloured" mpc3/src/main/kotlin/com/snipsnap/mpc3/Mpc3TrackWriter.kt`;
`grep -rn "KitArt" --include=*.kt shell/src/main kit/src/main | head`.

### What it is

The MPC grid is 4×4 and the field already cuts a picture into cells.
Cut it 4×4 instead, render each cell as a SNAP **pad** (a note, not a
grain), and land all sixteen at once with the grid's geometry matching
the picture: the photo's top-left cell is A13, its bottom-left A01, the
same MPC geometry `KitScreen` draws. Every pad's `colorHex` is its
cell's mean colour, so the MPC's pads light up as the photo. The
expansion tile is the photo itself, so the kit's cover on the hardware
is the picture it was made from.

### Design

- **`PhotoKit.build(photo, name): List<ArrangedPad?>`** in `:synth`,
  beside `PhotoField`. Sixteen cells, each: `Snap.look` → macros,
  `Snap.table(HORIZON)` → line (a flat cell → `Draw.wave(SINE)`, as the
  field does; `PhotoField.cellTable`'s soft blend applies), `SnapPatch`
  (voice HORIZON, the cell's table and macros) → `render()`. The recipe
  is the patch's, so the kit **regenerates from its sidecar** like every
  synth kit: a photo kit is not a folder of anonymous WAVs.
- **Class.** Every pad classified by `Classifier.classify(render)`, the
  way `Shuffle` audits its rolls — a photo cell is whatever it sounds
  like (most will be PERC or TONAL; a dark, smooth cell at TUNE 0 may
  earn KICK). `AutoPlace` is *not* run: the layout is the picture's, not
  the drum convention's. Say so on the toast.
- **Colour.** `colorHex` = the cell's mean RGB, from `Snap.Reading` —
  which today carries luminance, saturation and hue but not the mean
  RGB; add `meanRgb: Int` to `Reading` (one more accumulator in
  `Snap.look`). `Mpc3Exporter` must pass `colorHex` through to the
  program's per-pad `color`; check whether it does today or derives
  colour from class (`grep -n "color" kit/src/main/kotlin/com/snipsnap/kit/Mpc3Exporter.kt`).
- **Tile.** `KitArt` gets a `fromPhoto(thumb)` path: the ≤512 px bitmap,
  centre-cropped square, PNG bytes. The tile is 1000×1000 in the
  commercial packs; a 512 px source upscaled is fine for a browser tile
  and honest about where it came from.
- **Screen.** A KIT ▸ button on SNAP beside FIELD/CLOUD: builds off the
  main thread (`Copy.SNAP_KIT_BUSY`), lands as a **new kit** on the shelf
  named after the date (like FRESH TAPE), never over the open kit — a
  sixteen-pad overwrite is not a SEND. Opens KIT on it.

### Cost

`:synth` ~120 lines plus tests; `Reading.meanRgb` ~10; `KitArt` ~30;
screen ~80. Render cost is sixteen full SNAP pads (~1.45 s each at 4×
oversample) — under two seconds on the JVM, a few on a phone, with the
busy line up. No real-time code.

### How it would be tested

- A 4×4 photo of sixteen flat colours builds a kit whose pad *n* has
  the colour of cell *n* and whose recipe regenerates the same WAV.
- The layout test: top-left cell → slot 13, bottom-left → slot 1
  (`PadBanks` geometry).
- A kit from the same photo twice is byte-identical (recipes, WAVs).
- The exporter test: `Mpc3TrackWriter` output carries sixteen packed
  colours matching the pads (this is where a colour-from-class shortcut
  would show).

### The decision

Whether a photo kit lands beside the shelf's kits as a first-class kit
(recommended) or as a bank B behind the open kit. And whether the layout
is the picture's (recommended, it is the point) or `AutoPlace`'s.

**Built as recommended:** a photo kit lands as its own first-class entry
on the shelf (`KitShelf.landPhotoKit`, named PHOTO KIT, `freshName`d like
every other kit), never as a bank B on the kit that was open, and the
layout is the picture's own — `AutoPlace` never runs.

---

## 2. Print a drag across the field onto TAPE

### What exists

| | |
|---|---|
| `app/GrainVoice.runLoop` | mixes `block` (BLOCK_FRAMES) and blocking-writes it to the track; the block is the whole output |
| `app/SurfaceScreen` PRINT | the pattern: `SurfaceEngine`'s native `PrintBuffer` tap, `PrintLength.seconds(bars, bpm)` capped at `MAX_PRINT_SECONDS` (60 s), lands via `SnipStore.import(snip, filesDir, now)` with `onPrinted` bumping the shelf |
| `app/GrainFieldScreen` | "**v1 is play-only** … capturing the performance is the declared next phase, not built yet" (its own KDoc) |

Check it: `grep -n "SnipStore.import\|MAX_PRINT_SECONDS" app/src/main/kotlin/com/snipsnap/app/ui/SurfaceScreen.kt app/src/main/kotlin/com/snipsnap/app/SurfaceEngine.kt`.

### What it is

PRINT on the field: from the tap to STOP PRINT, everything the voice
mixed lands on TAPE as a snip. Then it is the app's own loop — trim,
chop at the hits, classify, kit — so a drag across a picture ends up on
pads through the same door as a capture. This is the field's declared
next phase, for the pad sheet's field as much as the photo's.

### Design

- **A print tap in `GrainVoice`.** A preallocated `FloatArray` of
  `MAX_PRINT_SECONDS × RATE` frames and a `@Volatile printing` flag; the
  render loop, after the clamp, copies `block` into it while printing
  and stops at the ceiling. No allocation in the loop: the buffer is
  made at `start()`. `takePrint(): FloatArray?` hands the filled part
  back on STOP, trimmed to what was written.
- **Same threading contract as the voice's target/gate** — the UI writes
  the flag, the loop reads it once per block; the buffer is only read
  after the flag is off and one block has passed (STOP waits one block,
  as PRINT's own STOP does).
- **Screen.** A PRINT / STOP PRINT chip on `GrainFieldScreen`'s header
  (both callers get it), `● PRINTING` on the LCD, the same
  `SnipStore.import` landing and `onPrinted` bump `SurfaceScreen` uses.
  `Copy.PRINT_LOST` / `SURFACE_NOTHING_PRINTED` are reusable as they are.

### Cost

`GrainVoice` ~40 lines; screen ~50; no `:synth` change. Tested on the
host only by the native harness's pattern — `GrainVoice` is Kotlin over
`AudioTrack`, so its loop has no JVM test; the print buffer's fill and
trim logic should be a pure function (`PrintTap` in `:shell`) with a
test, and the loop calls it.

### How it would be tested

`PrintTap`: blocks appended until the ceiling, the ceiling never
exceeded, `take()` returns exactly what was written, a take before any
block is null. On the phone: a five-second drag prints a five-second
snip that CHOP cuts at the grains' onsets.

### The decision

Whether the print is the mixed output (recommended: it is what was
heard) or a re-render of the cursor's path at higher quality (a
different feature — see §4).

**Built as recommended:** the print is `GrainVoice`'s own mixed block,
tapped after the clamp and before the write — what was heard, exactly.

---

## 3. Tilt the phone to play the picture

### What exists

| | |
|---|---|
| `app/TiltSource` | gravity along the device's X axis → `tilt` 0..1, volatile, read from the UI frame loop; `available` when a gravity or accelerometer sensor exists |
| `app/SurfaceScreen` | the one caller: tilt as a filter macro |
| `app/GrainFieldScreen` DUET | the pattern for **an automatic cursor**: a control-rate loop writes `autoPos` and calls `voice.setTarget` / `gate`, a finger always wins (`touching`) |

Check it: `grep -rn "TiltSource" --include=*.kt app/src/main | grep -v TiltSource.kt`.

### What it is

Tip the phone and the cursor wanders across the picture: roll is
left–right, pitch is up–down. Hands-free, and the most demonstrable
thing the field can do — hold the phone up to the photo it was taken
of and tilt.

### Design

- **`TiltSource` gains the Y axis.** Today it reads `values[0]` only;
  add `pitch` from `values[1]` with the same gravity normalization and
  NaN guard. `SurfaceScreen`'s reading of `tilt` is untouched.
- **A TILT chip** on the field's header beside DUET, using DUET's own
  loop shape: every `DUET_TICK_MS`, if not `touching`, `autoPos =
  (roll, pitch)` smoothed by `DUET_SMOOTHING`, `setTarget`, `gate(true)`;
  a finger wins; chip off → `gate(false)` in the loop's `finally`, as
  DUET's does. The ring `autoPos` draws is already there.
- **Dead zone.** Flat is 0.5 both ways; a hand is never still, so a
  ±0.02 dead band round the last position before the target moves,
  or the field would shimmer at rest.
- Available on the pad sheet's field too, since nothing in it is
  photo-specific.

### Cost

`TiltSource` ~10 lines; screen ~40. No `:synth` change, no real-time
change. The one risk is the sensor's axis convention in portrait, which
only a phone settles — `SurfaceScreen`'s existing use fixes the X axis's
sign; the Y axis's sign is the on-device check.

### How it would be tested

The mapping (`roll`, `pitch` → cursor with dead band and smoothing) as
a pure function in `:shell` with a test; the sign on the phone.

### The decision

Whether TILT and DUET are exclusive chips (recommended: one automatic
cursor at a time) or stack.

**Built as recommended:** TILT and DUET are exclusive — turning one on
turns the other off, since both drive the one cursor.

---

## 4. Walk a path through the picture in time

### What exists

| | |
|---|---|
| `synth/Draw.stroke` | a segment onto a 256-point table — a **path** sampled at 256 points is the same shape |
| `synth/PhotoField.Field.cells` | per-cell macros and tables; `grainOf(column, row)` |
| `loop/Orbit` | a pattern ring: `steps` 1..64, `PatternOrbit(kit, hits)`, a `voice` of pads for a melody ring |
| `loop/OrbitEngine.render` | a ring set offline into a `Snip` |
| `kit/Kit.tempoBpm` | the kit's tempo, what the grid divides |

Check it: `grep -n "data class Orbit\|MAX_STEPS" loop/src/main/kotlin/com/snipsnap/loop/Orbit.kt`.

### What it is

Draw a path over the photo. Sample it at the kit's tempo grid — one
point per sixteenth — and each point is a cell, each cell a note. The
picture is walked in time: a melody from where the line goes, a rhythm
from where it lingers. Two landings: a **loop pad** (the path rendered
offline, like CLOUD) and an **ORBIT ring** (the path as hits on the
kit's pads, editable afterwards).

### Design

- **`PhotoPath`** in `:synth`: `sample(path: List<(x, y)>, steps): List<Cell>`
  — resample the drawn polyline by arc length to `steps` points (so a
  fast stroke and a slow one over the same line give the same walk),
  then the cell under each. `render(field, cells, bpm, steps): Snip` —
  each step a SNAP note (`Snap.render` of the cell's table and macros,
  cut to the step length with the same seam blend as a grain), gapless.
- **As a ring.** Needs the kit of §1: a path over a photo *kit* names
  pads directly (`PatternOrbit(kit, hits)` with `voice` = the sixteen
  slots), and ORBIT plays it live at the kit's tempo. Without §1 the
  ring has no pads to name, so the loop pad is the standalone landing.
- **Surface.** DRAW's own overlay with a third tab, PATH, drawing over
  the photo instead of the wave panel; STEPS chips (8/16/32/64); DONE
  lands.

### Cost

`:synth` ~120 lines plus tests (arc-length resampling is the only
arithmetic); overlay tab ~120 (reuses `DrawLcd`'s pointer loop with the
photo as backdrop). The ring landing depends on §1.

### How it would be tested

Arc-length resampling: a path drawn as 3 points and as 300 points along
the same line gives the same cells; a path that lingers gives repeated
cells; the render is `steps × stepFrames` long and each step's pitch is
its cell's TUNE.

### The decision

Whether the loop pad is enough on its own, or §1 is a prerequisite so
the ring landing ships with it (recommended: build after §1, ship both
landings).

**Built as recommended:** both landings ship, after §1.
`synth/PhotoPath.kt` resamples the drawn polyline by arc length
(`sample`) and maps points to cells (`cellsFor`); a new `Snap.cut`
(oversampled like `render`, exact-length and HOLD-shaped like `grain`)
renders each step, concatenated gapless by `render`. DRAW's overlay
gained the third tab, PATH, with STEPS chips (8/16/32/64) and its own
`LOOP PAD ▸` / `RING ▸` buttons; the ring names the open kit's pads
directly through `PhotoKit.slotFor`, the same geometry §1's kit was
laid out with.

---

## 5. Sing into the photo

### What exists

| | |
|---|---|
| `app/GrainFieldScreen` DUET | mic → `Similar.vector(FeatureExtractor.extract(snip))` → `projector.project` → cursor; **hidden when `projector == null`**, which a photo field always is |
| `audio/GrainField.Projector` | `internal constructor`, the PCA basis of an analyzed sample; `project(vector): Pair<Float, Float>` is the only thing DUET calls |
| `audio/Similar.vector` | nine features in ~0..1: centroid, rolloff, flatness, ZCR, low/mid/high ratios, … |

Check it: `grep -n "projector" app/src/main/kotlin/com/snipsnap/app/ui/GrainFieldScreen.kt | head`.

### What it is

DUET on a photo field: hum, and the cursor scrubs the picture — a
brighter voice moves right, a louder one up (or any two features a
person can steer). The field then plays what is under the voice.

### Design

- **`Projector` becomes an interface** with one method,
  `project(vector: FloatArray): Pair<Float, Float>`; the PCA class
  implements it (rename `PcaProjector`, still `internal` to construct).
  DUET's code changes not at all.
- **`AxesProjector(x: Axis, y: Axis)`** in `:audio`: picks two of the
  nine vector dimensions, with a per-axis range learned from nothing
  (the vector is already ~0..1) — brightness (centroid) → x, loudness →
  y is the default. Loudness is not in `Similar.vector`; DUET's tick
  already reads `MicSessionService.level`, so the projector takes the
  level as a tenth dimension, or the tick maps y itself. The latter is
  smaller.
- **`PhotoField.build`** sets `projector = AxesProjector(...)` on its
  map. The DUET chip appears on a photo field with no screen change.

### Cost

`:audio` ~40 lines plus tests; `PhotoField` one line. The feedback
hazard DUET's KDoc documents applies unchanged: headphones.

### How it would be tested

`AxesProjector`: a vector with centroid 0.2 lands at x 0.2; the PCA
projector's existing tests unchanged after the interface extraction.

### The decision

Which two features, and whether they are fixed or a chip cycles them
(recommended: fixed at brightness/loudness first).

**Built as recommended, fixed at brightness/loudness, with one correction
to this section's own design sketch.** `AxesProjector` ended up taking
one `Axis` (x, defaulting to `CENTROID`) rather than two: `y` is left at
the map's own 0.5 centre, because loudness — the one feature DUET wants
there — is deliberately not part of `Similar.vector` ("a quiet snare is
still a snare"), so a *second* `Axis` for y would have to name some
other spectral feature and call it "loudness," which it isn't. The tick
maps y itself, as this section already flagged as the smaller of its two
options — but "smaller" still needed a few real lines in
`GrainFieldScreen.kt`'s DUET loop (blend `MicSessionService.level` onto
`y` when the field's projector is an `AxesProjector`, leaving a
`PcaProjector`'s own second principal component alone), not the "DUET's
code changes not at all" this section predicted for the interface
extraction alone.

---

## 6. Two photos, one child

### What exists

| | |
|---|---|
| `shell/Breed.cross` | A's patch with crossed macros **when the engines and voices agree**, else A's patch; `withMacros` round-trips through JSON, so a `SnapPatch`'s `table` and `envelope` ride along unchanged |
| `synth/SnapPatch.table` | 256 ints; `envelope` 64 ints or null |

Check it: `grep -n "fun cross" -A 12 shell/src/main/kotlin/com/snipsnap/shell/Breed.kt`.

### What it is

BREED two SNAP pads and the child's *line* is a cross of both pictures'
lines, not A's alone: two photos, one waveform.

### Design

- In `Breed.cross`, when both patches are `SnapPatch`, cross the tables
  point by point with the same coin `pick` uses for macros (A, B, or the
  mean), and the envelopes likewise when both have one (one alone rides
  half the time, as a section does). The result goes through
  `SnapPatch`'s own doors: a flat cross is refused, so re-roll the coin
  once and fall back to A's table.
- A `crossTables` pure function in `:synth` (`Draw`'s neighbour), so the
  arithmetic is tested there and BREED calls it.

### Cost

~40 lines plus tests. Nothing on the screen: BREED's existing button
already crosses recipes.

### How it would be tested

Two ramps (up, down) crossed with a seeded coin give a table between
them; identical parents give the same child; a cross that comes out flat
falls back to A.

### The decision

None blocking; a small, self-contained addition.

**Built as specced.** `crossTable` (`:synth`, `Draw.kt`'s own neighbour,
not a method of `Draw` itself) crosses two same-length point arrays —
either a 256-point SNAP table or a 64-point drawn envelope, the shape is
size-agnostic — through a `coin: () -> Int` callback rather than a
concrete `Random` type, so `Breed`'s own `java.util.Random` drives it
without `:synth` taking on that type (every other seeded function in
`:synth` uses `kotlin.random.Random`). `Breed.cross` crosses a SNAP
pair's tables and, when both parents drew one, their envelopes — one
alone rides half the time, `crossMacros`'s own rule for a section only
one side has. `SnapPatch`'s own constructor is what refuses a flat
table or a shape that never opens; on that refusal the coin gets one
full re-spin (table and envelope together), and a second refusal falls
back to A's own line and shape verbatim — the crossed macros ride
through regardless, since they are decided independently and never fail
to construct.

---

## 7. The photo as a spectrogram

### What exists

| | |
|---|---|
| `audio/Pghi.invert(mags, outFrames, sampleRate, seed)` | **audio from magnitudes alone**: phases integrated by the phase-gradient heuristic, the quiet remainder random — the exact inverse the idea needs |
| `audio/Spectral` | `FRAME` 1024, `HOP` 256, `BINS` 513: the magnitude grid `invert` expects (one `FloatArray(BINS)` per frame) |
| `synth/Grains.render` | eats any source: the inverted texture is a GRAINS source like a capture |

Check it: `grep -n "fun invert" -B 8 audio/src/main/kotlin/com/snipsnap/audio/Pghi.kt`.

### What it is

The literal reading: the image *is* a picture of sound. Columns are
time, rows are frequency (top high), brightness is level. The photo is
resampled onto the magnitude grid and inverted. It is the version people
have heard of, and it makes a texture — seconds long, a LOOP — not a
hit; hence a second field/CLOUD mode, not a pad engine.

### Design

- **`Spectrogram.read(photo, seconds): Snip`** in `:synth`: `frames =
  seconds × RATE / HOP`; for each frame, column *x* of the photo
  (interpolated as `Snap.table`'s narrow-line path does), for each bin
  *k* of 513, row *y* = the bin's frequency on a **log scale** from
  ~40 Hz at the bottom to Nyquist at the top (linear puts nine tenths of
  the picture above 2 kHz), luminance → magnitude (a power curve,
  luminance², so black is silence rather than a hiss floor); then
  `Pghi.invert`, normalize. Optionally hue → stereo pan later; mono
  first.
- **Landing.** A SPECTRUM chip on CLOUD's chooser, or a second CLOUD
  mode: `Grains.render` over the inverted texture (the cloud of the
  spectrogram), or the texture itself as the pad. Both are recipe-less
  audio, as CLOUD is.

### Cost

`:synth` ~80 lines plus tests; `Pghi.invert` on a 2.5 s texture is a few
hundred frames — cheap. The one design risk is that photographs make
noise-like spectrograms (every bin lit a little); the power curve and a
floor cut (bins under 5 % of the frame's peak to zero) are the two knobs
that make a picture *read* rather than hiss.

### How it would be tested

A photo of one bright horizontal line at row *y* inverts to a tone whose
pitch estimate matches the row's bin frequency; a black photo is
silence; a bright vertical line is a click at that column's time.

### The decision

Log or linear frequency (recommended: log), and whether it is a CLOUD
mode or its own button.

**Built as recommended (log), as its own button.** `Spectrogram.read`
(`:synth`) builds one `FloatArray(513)` magnitude row per output frame:
the column position is the same fractional-index linear interpolation
`Snap.table`'s own `resample` uses for a line shorter than its table
(never a column simply repeated), and each bin's row comes from that
bin's own **linear** FFT frequency (`Spectral.binHz`) mapped onto the
photo through the log scale — bin index itself is never log-spaced, only
the row it reads from is; an earlier draft conflated the two into a
formula that canceled itself back to a linear map, and a unit test
(painting a target Hz's own row and probing the inverted audio with a
Goertzel detector) caught it landing roughly an octave-and-a-half sharp
of the tone it was supposed to be. Each frame's own quietest bins are
floored to true zero below 5% of that frame's peak, the second knob
(with the power curve) the cost section named for keeping a photograph
from reading as hiss. Landed as its own SPECTRUM ▸ button beside
FIELD ▸/CLOUD ▸/KIT ▸ — needing only the photo, not FIELD's own grid —
through the same recipe-less texture-as-a-pad door CLOUD already uses,
not a further `Grains.render` pass.

---

## 8. The live camera

### What exists

| | |
|---|---|
| `app/SnapScreen` | `TakePicturePreview` only — one still, no preview stream |
| `app/GrainVoice` | the only Kotlin real-time voice; steady-state grains over a fixed source |
| `app/SurfaceEngine` (native) | a control ring with per-sample `ParameterSmoother`s — the pattern for a **live-modulated** oscillator |
| `synth/Snap.table` / `look` | per-frame reads are cheap at 512 px (a few ms) |

Check it: `grep -rn "TakePicturePreview\|CameraX\|camera2" --include=*.kt app/src/main`.

### What it is

Point the phone at the world and hear it: the camera preview is read
every frame into a table and a reading, and a running oscillator
morphs toward them. Pan across a room and the sound follows. The most
striking idea in this list, and the only one that needs plumbing the app
does not have.

### Design

- **Camera.** CameraX `Preview` + `ImageAnalysis` at ~320×240, one
  `Photo` per analyzed frame on a background executor. A new
  dependency (`androidx.camera:camera-*`), and now a CAMERA permission
  of our own with the runtime prompt — `TakePicturePreview` needed
  neither.
- **Voice.** Not a grain field: a wavetable oscillator whose table
  cross-fades to the new frame's line over ~50 ms and whose macros
  smooth toward the new reading — exactly `SurfaceEngine`'s control-ring
  shape. Either a second native engine (`LiveSnapEngine.cpp`, with the
  host test harness the native tree has) or a Kotlin `AudioTrack` loop
  like `GrainVoice` with a double-buffered table. Native is the app's
  convention for anything continuous.
- **Screen.** LIVE on SNAP: the preview fills the panel, the line read
  drawn over it, the four sliders live, FREEZE takes the current frame
  as the photo (so everything above works on it).

### Cost

The largest by far: a camera dependency and permission, a new real-time
engine (native, with both CMake lists — the steward skill's warning
about the two source lists applies), and a screen. Days, not hours; and
nothing about the engine can be proved here beyond its host tests.

### How it would be tested

The engine's table cross-fade and macro smoothing on the host harness
(a table switch never clicks); the frame-to-table read is `Snap.table`,
already tested. The feel is the phone's.

### The decision

Whether to build it at all before the rest — it is the one item here
that is a project rather than a feature. Recommended: last, and only
once §1–§3 have been played on a phone.

---

## What gates all of the above

`app/` is still proved by a compiler in CI and by a phone in a hand;
every section above says which half of it lives in `:synth`/`:audio`
with a JVM test and which half is Compose. The on-device checklist in
`app/README.md` grows a line per section as each lands, the way it did
for SNAP, DRAW and PHOTO FIELD.
