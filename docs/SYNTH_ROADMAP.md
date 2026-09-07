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
| S4 | **shipped** — keygroup export in both generations: `KeygroupWriter` (`.xpm`, corrected line-by-line against commercial programs) and `Mpc3TrackWriter.writeKeygroup` (`.xty`, corpus-guarded); hardware load check pending | reference corpus (done) |
| S5 | **shipped** — the instrument suite: `Keys` renders engines at exact MIDI pitch (EP from TINES with velocity-true soft/hard renders, Organ from TONEWHEEL with mathematically-cut sustain loops, Harp from PLUCK, Music Box from TINES), multisampled every minor third, packaged dual-generation (`.xty` + `.xpm` twin in one `_[TrackData]/`) | S3 + S4 |

S1 and S2 are pre-app-buildable in this repo with CI coverage, same as
everything else. S4 is the one that needs hardware again.

## Placement

This slots after the app MVP (capture → kit → export must ship first — the
synth makes kits better, capture makes the app exist). CRUNCH is the likely
queue-jumper: it improves captured kits, which is MVP territory.
