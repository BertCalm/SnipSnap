# The mic as the app's ears — vision log (2026-09-04)

**Seed:** what is the phone mic when it stops being a recorder? Koala has a
mic too; ours must do what theirs can't imagine.

**Peak insight (survived the DA):** Koala's mic *records*; ours *understands*.
The codebase isn't a recorder's back-end — it's a listener's nervous system:
live transient classification, Pitch/Tuner/KeyGuess, groove-as-data
(CapturedGroove/GrooveEdit), six synth engines with JSON patch DNA, a
TeachLog that learns from correction, and Similar.kt (a distance metric over
audio features). Three faces of the same idea:

1. **CONTROLLER — beatbox→groove.** Beatbox a pattern: Transients finds the
   hits, Classifier names them (kick/hat/snare), CapturedGroove turns timed
   hits into an Mpc3Clip = PROG A on the open kit. Hum a line: Pitch+Tuner
   snap it to the kit's key. Differentiator vs every beatbox toy: the
   classifier is TEACHABLE (TeachLog learns YOUR mouth), and the output rides
   the whole GROOVE system to MIDI/MPC export — it ends in hardware, not in a
   party trick.
2. **SURVEYOR — tap→patch.** Tap a mug/radiator/door: Features measures
   fundamental/decay/spectral tilt, Similar matches it to the nearest point
   in a sparsely pre-rendered synth macro-space → "that mug is a TINES BLOCK
   at F#, want the patch?" The world becomes a patch catalogue. Nearest-
   neighbor, not inverse synthesis — the user finishes with the sliders.
3. **DUET — call and response.** The machine and you in the room together:
   you clap, it answers on your kit with swing, Tempo follows you drifting.
   The phone becomes the other musician. DA's physics objection (speaker
   into mic) → answered by TURN-TAKING (which is what a duet IS); full-
   overlap self-subtraction logged UNVERIFIED (echo-path stability on phone
   speaker→mic without AEC).

**Temporal:** years of TeachLog corrections = an ear tuned to one human's
sonic world, entirely on-device. "NOTHING LEAVES THE PHONE" becomes the moat,
not just the privacy line.

**Bummer's sequencing:**
- Phase 1 (now): ship the Retroactive Snip plan — the load-bearing wall.
- Phase 2: BEATBOX→GROOVE — smallest gap, because it is a *snip
  interpretation* (offline Transients+Classifier+CapturedGroove over a
  committed snip), NOT a live-streaming mode. Weeks.
- Phase 3: TAP→PATCH (needs offline macro-space render + Similar), then the
  turn-taking DUET once device latency numbers exist.

**The Hidden Win:** beatbox→groove is just a *different reading of a snip*, so
IMPORT gets it free — any WAV, from anywhere, can be read as a groove instead
of a sound. A 2019 voice memo becomes a drum pattern in 2026.

**Paradigm fragments (not yet escalated):** voice-as-VOX-patch-DNA; two-phone
acoustic sync; room-hum key detection.
