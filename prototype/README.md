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

The `window.__deck` handle exists only so automated checks can drive it.
