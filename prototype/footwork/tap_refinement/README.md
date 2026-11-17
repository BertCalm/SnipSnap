# Tap-only refinement

Owner feedback on the new phrase studies: Tap is weakest; Street and Industrial
are pretty good. Preserve those two exact phrase studies as the working direction.
This is not approval of all individual sources or end-to-end kit acceptance.

Baseline inspected: `dff74270da249e1c175c57183b88a2734c3ee22c`.
The previous performance study applied the same 260-Hz high-pass and 75-ms
exponential damping to every light contact. This removes much of the heel body
and suppresses the brushes before they finish travelling. Its Tap arrangement
also overlays a repeating toe/heel pulse with fills, sometimes at the same instant.

The new study keeps native model-3 source grids and makes role-specific changes:

- Toe: retain the delayed ta-ee plate response; reduce low floor support.
- Heel: preserve low-mid knock, shorten the tail, remove upper plate-like ringing.
- Brush/spank: preserve the directional pressure envelope with separate ending
  times; suppress the later planted-contact tail and raise the brush balance.

The four-bar, 124-BPM phrase has call/answer, a quieter question with a longer
breath, then a turnaround. A single foot-event ledger replaces the layered pulse.
Step uses the existing toe/ball-tap proxy. The related roll/build are revised with
the same contacts. No new physical model, sampled recording, native version or
kit download is introduced. Contact shaping is linear filtering/enveloping of
saved native synthesized velocity/repeat windows, not a realism claim.

Why it is built this way:

- Separate contact treatment preserves the differences a common filter removed.
- Complete brush/spank pairs make the movement explicit rather than decorative hiss.
- Rests and accent hierarchy give the phrase a question and answer.
- The same four-contact vocabulary carries through phrase, roll and build.
- Existing synthetic sources remain the limiting factor; a timing check does not
  prove dancer biomechanics or recognizable tap sound.

Run `python3 prototype/footwork/tap_refinement/build.py /path/to/footwork-native-v3 /new/output`.
The source grids come from the native model-3 generator. There are 36 phrase/gesture
cells and 48 single-contact cells. Assertions check finite/headroom/DC/tail,
determinism, silence at zero, phrase repeat differences, monotonic dynamics,
more than 70 ms between events assigned to one foot, and PCM16 round-trip error.
Single-contact repeat waveforms are inherited from the saved grid; no independent
new contact-round-robin gate is claimed. Full dynamics uses the saved three anchor
layers and continuous gain, with fixed role gains rather than per-hit normalization.

Audition A/B files use explicitly labeled average-energy matching, separately from
the full-dynamics strips. A same-rhythm/previous-contact comparison isolates the
contact treatment from arrangement. Retained Street/Industrial WAV hashes are
recorded in `retained-phrases.json` and checked before publication.

This remains a Python audition, not native engine parity or export acceptance.
Source realism, native integration and delivery gates, including the MPC 3 slice
map blocker, remain open. Browser audio decoding cannot be tested in this session.
