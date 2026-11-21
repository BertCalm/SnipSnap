# Prototypes

## tapedeck.html — the trim interaction, for real

A self-contained touchable prototype of the Tape Deck screen. Open it in any
browser (it's also published as an artifact); on a phone it answers the one
design question a mockup can't: does dragging the tape under a fixed needle
*feel* right?

What's real in it:

- **A tape engine.** Audio position chases your finger with a per-sample
  smoother, so scrubbing pitches like actual tape; play spins up and stop
  winds down instead of gating. Flicks coast with friction.
- **A synthesized 12.8 s "capture"** (kicks/snares/hats/toms) with its onset
  positions known exactly — they render as tick marks and drive snapping.
- Snap-to-onset on release, zero-crossing snap for IN/OUT, loop preview of
  the selection, hold-to-wind transport, zoom, commit-to-shelf with haptic.

What's fake: the audio is synthesized, commit doesn't write a file, and the
overview is mono. It's an interaction prototype, not the app.

Personality, per [`../docs/PERSONALITY.md`](../docs/PERSONALITY.md): tap the
cassette for a pencil rewind, leave it idle for the screensaver, triple-tap
the position readout for the mechanical tape counter, and commit a few snips
for the deck's opinions.

## playmode.html — the 16-pad grid, playable

Full-screen dark-LCD pad grid with a synthesized demo kit, for answering "does
finger-drumming feel right" before any app exists.

What's real: pre-rendered buffers triggered on pointerdown (multi-touch), the
hat choke (A03 kills A04), mono bass (choke group 3), a self-choking loop pad,
slide-across-pads rolls, sustained pads staying lit while they ring, and the
CRT power-off on holding EXIT (personality catalog, shipped). The hint bar
shows the context's reported base latency.

What's fake: the sounds are synthesized, there's no kit loading, and true
trigger latency on Android needs Oboe — a browser can only approximate it.

## thumplab.html — the synth panel, auditioning the real algorithms

The THUMP voice panel from [`../docs/SYNTH_ROADMAP.md`](../docs/SYNTH_ROADMAP.md),
playable. Every DSP line is a straight JS port of the shipped Kotlin — same
constants, same envelope shapes, same macro ranges — so what you audition here
is what `:synth` renders on export, not an approximation of it.

What's real:

- **Five engines behind one panel**: all eight THUMP voices (kick, snare,
  both hats, clap, tom, cowbell, rim), five TINES FM voices (bell, chime,
  block, zap, toy — snapped RATIO included), four PLUCK Karplus-Strong
  bodies (kalimba, nylon, harp, koto — TUNE snapped to semitones), three
  TONEWHEEL registrations with all eight drawbars as sliders plus
  PERC/WARBLE/DIRT, and four VELVET subtractive stabs (bass, brass, squelch,
  chip — resonant SVF, envelope-swept). Every knob 0..1 over the same
  bounded musical ranges as the Kotlin.
- **CRUNCH inline** — grit → zero-order hold → bit quantization → three-pole
  tone filter, peak-matched, exactly as in `Crunch.kt`. Sliding a CRUNCH macro
  switches it on; the OFF/ON button A/Bs it.
- **The FX rack** — SQUASH (lookahead compressor), ECHO (darkening tape
  delay), SPRING (Schroeder reverb) and REVERSE, applied in `FxChain.kt`'s
  fixed order with the same chain-level tail budget. Sliding any macro
  enables its section; the named buttons A/B each one.
- **The playability loop**: every slider move re-renders and retriggers within
  ~70 ms, so tweaking sounds like playing. SCRAMBLE is the uniform macro roll;
  FACTORY resets the voice. The LCD scope draws the rendered buffer and the
  RENDER readout shows real synthesis time.

What's fake: nothing writes a patch file or a WAV — that's `ThumpPatch` and
the export pipeline's job in the real app.

The `window.__deck` / `window.__play` / `window.__lab` handles exist only so
automated checks can drive the pages.

## etch/ — ETCH round-1 audition (Python harness)

A step-line bass, a drive that glues the steps, and a tempo delay into a reverb, as numbers
and WAVs before any Kotlin. `python3 gen_audition.py OUT && python3 build_page.py OUT` renders
54 level-matched clips with their measurements and writes the audition page. Needs numpy,
scipy and numba. See [`../docs/ETCH.md`](../docs/ETCH.md).

## kindred/ — KINDRED round-2 audition (Python harness)

A one-voice, eight-step analog-style percussion synth (two oscillators with a shared pitch-decay
envelope, FM and hard sync, noise, a four-pole ladder, three decays), built from the owner's DFAM
reference clips, and four sixteen-pad genre kits made from that one voice (TECHNO, INDUSTRIAL,
ELECTRO, AMBIENT). `kindred_proto.py` is the voice; `KINDRED_MODEL=2 python3 kindred_kits.py OUT`
renders every pad at three velocities and four takes, each kit's A15 phrase, A16 riser, groove and
walk, with a JSON report. `KINDRED_MODEL=2 python3 groove_ltas.py OUT` measures the grooves' band
balance against the reference clips' median. Model 2 is the sound the owner accepted on 2026-11-15
(round 1 was heard as "a small radio speaker"); see docs/KINDRED.md. `gen_recipes_kt.py` writes the Kotlin
recipes and kit specs from the kit table; `compare_kotlin.py` A/Bs the Kotlin pads; `build_k2_page.py` cuts the K2
listening clips from `./gradlew :shell:generateKindredKits`. Needs numpy, scipy and numba.

## scuff/ — SCUFF round-0b audition (Python harness)

A dry Michigan drum kit (ten voices) and the chain that makes it (GRIT, LOCKER, BARK, SLAM),
as numbers and WAVs before any Kotlin. `python3 gen_audition.py OUT && python3 build_page.py OUT`
renders 155 peak-matched clips with their measurements and writes the audition page, including a
round-0 against round-0b section built from the round-0 model read out of git. `compare_kotlin.py KOTLIN OUT`
measures the Kotlin engine's clips (`./gradlew :synth:generateScuffAudition`) against the Python ones, aligned
by cross-correlation, and `build_page.py` then puts the pairs at the top of the page. With the shell generator's pack
(`./gradlew :shell:generateScuffKits`) copied in under `OUT/shelf/`, the page also carries the kit on the shelf. Needs
numpy and scipy. See [`../docs/SCUFF.md`](../docs/SCUFF.md).

## clutter/ — CLUTTER round-0c audition (Python harness)

A found-sound drum engine of 22 modelled household objects, each with loose parts that only move above a
strike threshold, and five sixteen-pad kits from them (`kits.py`: the CLUTTER kit and four genre kits),
as numbers and WAVs before any Kotlin. `python3 gen_audition.py OUT && python3 build_page.py OUT` renders
every pad of every kit at three velocity zones and four takes (one twelve-slot WAV per pad, which the
page's pad grid slices), each kit's groove, the CLUTTER kit's velocity ladders and takes, bank B, and every
control on every object, with their measurements. `compare_kotlin.py KOTLIN_DIR` measures the Kotlin port
(`./gradlew :synth:generateClutterAudition`) against these renders object by object. With the shell generator's pack (`./gradlew :shell:generateClutterKits`) encoded as MP3 under `OUT/shelf/`, the
page also carries the four kits on the shelf. Needs numpy and scipy. See [`../docs/CLUTTER.md`](../docs/CLUTTER.md).

## ember/ — EMBER round-0e audition (Python harness)

A live jazz and soul drum kit in a small club in 1962 (27 voices) and four sixteen-pad kits from it (`kits.py`),
as numbers and WAVs before Kotlin. The cymbals, hats and brushes are fitted to CC0 recordings; only the fitted numbers
are here. `python3 gen_audition.py OUT && python3 build_page.py OUT` renders every pad at three velocity zones and
four takes, each kit's groove and every control on every voice, with their measurements. `compare_kotlin.py
KOTLIN_DIR` measures the Kotlin port (`./gradlew :synth:generateEmberAudition`) against these renders voice by voice,
and `export_club.py` writes the club tail the Kotlin port loads. Needs numpy and scipy. See
[`../docs/EMBER.md`](../docs/EMBER.md).

## calabash/ — CALABASH round-0 audition (Python harness)

Drum kits for Afrobeat, Afrobeats, Mbalax and Afro-Cuban jazz, approved by ear on 2026-11-15: 36 voices, the hand
drums, bells, claves, shaker and claps fitted to CC0 recordings (`fit_calabash.py`, `hands.json`, `NOTICE.txt`),
the shekere, talking drums, sabar family and timbales physical, and four sixteen-pad kits (`kits.py`).
`python3 gen_audition.py OUT && python3 build_page.py OUT` renders every pad at three zones and four takes, the
grooves, the strokes, every voice and every control, and writes the listening page. `export_fixed.py` writes the
three draws the Kotlin port takes from the prototype (the room's noise, the cymbals' pairs, the crowd);
`compare_kotlin.py KOTLIN_DIR` measures the port (`./gradlew :synth:generateCalabashAudition`) against these renders
pad by pad. `shelf_grooves.py` puts the kits on the shelf (`:shell:generateCalabashKits`) on the page beside the
approved grooves. Needs numpy, scipy and ffmpeg. See [`../docs/CALABASH.md`](../docs/CALABASH.md).

## taiko/ — the Japanese festival set, fitted

The Python rounds behind TERRA's nine stick voices (`TerraSticks.kt`,
[`../docs/TERRA_STICKS.md`](../docs/TERRA_STICKS.md)). `fit.py` is round 1's
fitter (measured notes plus a fitted third-octave residual); `round2.py` to
`round6.py` are the reworks the owner's listening verdicts asked for;
`models.json` and `report.json` are the fitted models and their measurements.
`gen_kotlin.py` writes `TerraSticksTables.kt`, and `cmp_kotlin.py` compares the
Kotlin render with the Python one. The scripts read the owner's reference
recordings, which are not in the repository.
`round7_fan.py` fits the fan's slap and swish as trains of flaps (round 7).
