# Nacre — first finite listening model

Nacre is an original, sample-free paired-harp engine. Model 1 renders one complete six-second
mono gesture at 44.1 kHz using the existing 4× synthesis and antialias path. Listening acceptance
is pending. This is the first finite prototype, not the complete supplied engineering handoff.

## Implemented

- One PEARL profile; four independently plucked strings and four sympathetic resonators per harp.
- Bass reference is the requested MIDI root; the upper reference is an octave above it.
- Distinct excitation spectra, damping, body modes, timing tendencies, and strum directions.
- TOUCH, DUET, CURRENT, VENUE and BLOOM. MIDI 28–72 and physical excitation velocity are separate
  saved patch fields, with a model version and exact signed 64-bit seed.
- A deterministic shared current changes internal waveguide loss, finite excitation transfer,
  and cross-harp sympathetic transmission; the venue reads the same field at different positions.
- Coral uses short reflection paths and small fixed cavities; seaweed uses soft feed-forward
  transmission paths with current-driven apertures and no enclosed late tank.
- Six provisional recipes, patch/recipe replay, native velocity rendering, and CLI rendering.

## Deliberate prototype reductions

VENUE 0 is coral and VENUE 1 is seaweed, with a convex blend between them. The handoff's conch
and cave landmarks are not present. HOLD must be zero; any nonzero value is refused explicitly.
No held instrument, expanded chord/geometry editor, phone picker, or live SURFACE synthesis
integration is included. Ordinary sample playback can use the rendered audio.

The return topology is intentionally acyclic: active strings drive fixed local body filters;
a delayed body mixture drives sympathetic modes; the instrument feeds venue paths. Bodies,
sympathetic modes, and venues do not feed active strings. String delays and filter coefficients
remain fixed; current only reduces the built string feedback. The finite branches have fixed
poles inside the unit circle. This avoids assuming that Gyre's coupling argument validates a
larger changing return network. A future active venue return needs a new complete-network bound.

## Audition

Run `./gradlew --no-daemon :synth:generateNacreAudition` from the repository root. The output is
`testkit/nacre-audition/`, with 49 clips, 98 PCM24 WAVs, a maintained listening page, complete
production patch JSON, event CSVs, diagnostics, metrics, and downloadable ZIPs. The hosted page offers separate listening/raw packs to stay within the static host’s file-size limit. The source snapshot and
SHA-256 hashes identify exactly which implementation produced the audio.

Listening families share their reference's gain. The raw mode uses one common safe gain across
the pack. Diagnostic stems retain their contribution to the full mix; they are not independently
boosted into misleadingly prominent parts. Mute/bypass options are diagnostic settings, saved
separately from production patch JSON in the manifest.

The comparisons cover solo harps, tight/answering/crossing strums, still/moving/post-output-only
current, venue-bypassed current, both venues, unplucked sympathetic response with cross-harp
transfer enabled/disabled, five control steps, note-range probes, and soft excitation.

CLI example: `./gradlew :cli:run --args="synth NACRE PEARL --all --midi 48 --velocity 0.7 --out /tmp/nacre"`.
The provisional recipes remain outside the factory roster until their listening review.

## Acceptance

Automated checks cover deterministic events/audio, exact recipe replay, octave voicings,
internal current authority, sympathetic transfer, finite decay, moderate tuning, macro activity,
zero-velocity silence, and classifier safety. Numerical results do not establish whether the
performers sound distinct or whether the water and venues are musically convincing.

The owner's listening verdict should address the duet, the internal current, the unplucked
answer, and the coral/seaweed contrast before expanding the model.
