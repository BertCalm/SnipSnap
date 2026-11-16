# FOOTWORK: connected gestures from the reference-guided contacts

The owner described the isolated contact study as “Better” and asked to continue
in that direction. This is directional feedback, not full-kit acceptance.
Base read: `b1f21b21f1b313171fe61a1b736bebe5e47fb5de`.

The characteristic gesture is travel → return or landing → weight transfer →
release. This study preserves all 24 toe/heel source cells from `tap_contact_fit`
exactly and adds authored brush/spank sources, shuffle, flap and ball-change
candidates, a two-bar call/answer, a pickup-based roll and an accelerating build.

## Why it is built this way

- The preferred toe/heel source is reused without refitting or additional bass.
  Exact PCM comparison against the prior audition prevents the improved body
  from disappearing while the surrounding movement changes.
- Sliding excitation through the same spectral response supplies brush travel.
  The return stroke is shorter and firmer. These are mechanical analogies, not
  measured shoe friction or confirmed real gestures. The main risk is that the
  travel still reads as noise rather than a shoe moving across the floor.
- Each gesture has an explicit role, foot, time and force ledger. A shuffle has
  forward/return movement; a flap ends with a planted toe. Ball change uses a
  lighter toe then the opposite foot's stronger contact with a heel settlement.
  That last heel is an authored variant, not a universal definition of the step.
- The phrase leaves space between calls and answers. The roll/build use brush,
  return and landing pickups with occasional heels, addressing the rejected
  alternating-stomp construction. Human timing is not claimed measured here.
- Per-foot/per-role repeat counters vary the strikes while one fixed 0.82 mix
  gain preserves dynamics and decay overlap. No compression, reverb or per-hit
  output normalization is added. Finite gestures do not need loop seams.

## Verification and acceptance

Python only: 72 cells (six studies × three velocities × four takes), deterministic
rerenders, zero-velocity silence, finite/headroom/DC/tail checks, increasing
velocity energy, distinct takes at every level, and PCM16 save/read error below
5e-5. Maximum peak is 0.5831. All 24 core toe/heel cells are PCM-identical to the
previous audition. The report contains every event and result.

The page presents full-strength studies; each short gesture also has all 12
strength/repeat cells, and phrase/roll/build have soft and medium comparisons at
the same gain. All 72 individual files are saved locally by the builder. Browser
decoding and native instrument selection/export are separate from these checks.

Heard: the owner favors the previous isolated-contact direction. The new brush,
gesture and performance studies have not received listening approval. Native
model/kit versions remain unchanged. Distinct-kit identity, source realism,
authored performance acceptance, hardware checks and the MPC 3 slice-map blocker
remain open. Street and Industrial phrase audio and prior contact comparisons
are preserved; this round does not refit the private recordings.

```sh
python3 prototype/footwork/tap_connected/build.py /tmp/footwork-tap-connected
```

The renderer needs only the adjacent numeric contact model. The invariance check
also reads the previous audition's two dynamics WAVs from the local Site checkout.
No uploaded reference recording is read or incorporated into synthesis.
