# Full-weight tap impact study

Owner feedback: a person would have more oomph and weight. Earlier revisions
restored and filtered existing contact body, but did not add a stronger mechanical
impact. This study adds a finite pressure-driven support response beneath toe/heel.
The exact prior rhythm, accents, per-foot take choices and brush PCM are retained.

At 176.4 kHz, a short unit-area pressure pulse (0.9 ms toe / 1.4 ms heel at full
force) drives six damped inharmonic modes. A smaller delayed pressure pulse represents
load spreading across the sole. A short broad-band knock supplies contact texture.
The output uses the Python house band limit, decimator, DC block and tail fade,
then sums with the prior synthesized contact. Modes use compensated gain to avoid
frequency-dependent gain from the resonator implementation. All pole radii are
strictly less than one. Velocity changes pulse width and energy; seeded repeat
variation changes modal frequencies and strengths. No per-hit peak normalization,
limiter, new recording, room convolution or sub-bass sweep is used.

This is an authored contact/support analogy, not recovered shoe/floor geometry or
measured human mass. It remains a Python audition, not a new native source model.
Native downloads stay at model 3; source realism, native integration, export and
physical-device gates remain open, including the MPC 3 missing slice map.

Why it is built this way:

- A driven impact adds attack energy and body that filtering cannot recover.
- The small secondary pressure event proposes sole loading without another full stomp.
- Multiple short, inharmonic modes spread weight across the low-mid range.
- Retaining exact timing and source take choices isolates impact from performance changes.
- The main risk is too much drum-like floor tone; only listening can establish the balance.

Run `python3 prototype/footwork/tap_impact/build.py /path/to/footwork-native-v3 prototype/footwork/tap_weight_feel/report.json /new/output`.
Thirty-six phrase/roll/build cells plus 24 toe/heel cells pass finite/headroom,
dynamics, determinism, repeat and PCM16 round-trip assertions. The same-gain
comparison intentionally preserves added impact energy. A separate energy-matched
phrase comparison is provided to judge timbral change independently of level.
Hard-velocity take-0 first-40-ms energy is about 2.39x for toe and 2.98x for heel;
these are measurements, not perceptual or human-realism scores.
Street and Industrial retained WAV hashes are checked unchanged before publishing.
