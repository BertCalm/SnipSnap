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

- **All eight THUMP voices** (kick, snare, both hats, clap, tom, cowbell, rim)
  with their per-voice macro sets, every knob 0..1 over the same bounded
  musical ranges as the Kotlin engine.
- **CRUNCH inline** — grit → zero-order hold → bit quantization → three-pole
  tone filter, peak-matched, exactly as in `Crunch.kt`. Sliding a CRUNCH macro
  switches it on; the OFF/ON button A/Bs it.
- **The playability loop**: every slider move re-renders and retriggers within
  ~70 ms, so tweaking sounds like playing. SCRAMBLE is the uniform macro roll;
  FACTORY resets the voice. The LCD scope draws the rendered buffer and the
  RENDER readout shows real synthesis time.

What's fake: nothing writes a patch file or a WAV — that's `ThumpPatch` and
the export pipeline's job in the real app.

The `window.__deck` / `window.__play` / `window.__lab` handles exist only so
automated checks can drive the pages.
