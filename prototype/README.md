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

## scuff/ — SCUFF round-0b audition (Python harness)

A dry Michigan drum kit (ten voices) and the chain that makes it (GRIT, LOCKER, BARK, SLAM),
as numbers and WAVs before any Kotlin. `python3 gen_audition.py OUT && python3 build_page.py OUT`
renders 155 peak-matched clips with their measurements and writes the audition page, including a
round-0 against round-0b section built from the round-0 model read out of git. `compare_kotlin.py KOTLIN OUT`
measures the Kotlin engine's clips (`./gradlew :synth:generateScuffAudition`) against the Python ones, aligned
by cross-correlation, and `build_page.py` then puts the pairs at the top of the page. With the shell generator's pack
(`./gradlew :shell:generateScuffKits`) copied in under `OUT/shelf/`, the page also carries the kit on the shelf. Needs
numpy and scipy. See [`../docs/SCUFF.md`](../docs/SCUFF.md).

## clutter/ — CLUTTER round-0 audition (Python harness)

A found-sound drum kit of sixteen modelled household objects, one per pad (bucket, rice box, jar,
keys, suitcase, book, matchbox, cutlery drawer, tub, frying pan, table, saucepan, bottle, mug, wine
glass, zipper), each with loose parts that only move above a strike threshold, as numbers and
WAVs before any Kotlin. `python3 gen_audition.py OUT && python3 build_page.py OUT` renders the
sixteen-pad kit, every pad at three velocity zones and four takes (the page's playable pad grid),
the velocity ladders, the takes and every control on every object, with their measurements.
Needs numpy and scipy. See [`../docs/CLUTTER.md`](../docs/CLUTTER.md).
