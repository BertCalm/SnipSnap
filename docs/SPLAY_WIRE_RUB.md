# SPLAY WIRE and RUB (model 3, candidates, not yet heard)

SPLAY had one voice, TONGUE. These are the first two of the five further bodies proposed for it. The
characteristic gesture is unchanged: **common release → coordinated catches → separating flams →
bounded decay**. The pendulums and the catch times are exactly model 2's. Only the body the catches
strike is new.

Status: implemented in `SplayBodies.kt` and tested in JVM tests. **Not registered** in the SYNTH
picker, the patch format, the starters or MAKE INSTRUMENT, and **not yet heard**: they go to the
owner's Listening Desk first (round `splay-voices-1`). Registration, keygroups and presets follow a
KEEP.

## What stays exactly as it was

- **TONGUE** keeps models 1 and 2, their defaults, seeds and render code. `SplayAcceptedBankTest`
  still matches all five accepted populations (1, 2, 4, 8, 16) raw and matched PCM.
- **The mechanics:** `Splay.prepareModel2Excitation` runs TONGUE's own model-2 preparation chain
  (settle → trajectory → contacts → coincidence gain) and hands out the catch list, the admission and
  the upper-arm pressure. TONGUE's render never calls it. `SplayBodiesTest` checks the catch list
  equals TONGUE's for all five accepted populations plus a fractional one, whatever the note,
  velocity, seed or body, and that TONGUE renders the same bytes before and after the bodies run.
- **Model 3 belongs to the bodies.** `Splay.supportsModel(3)` stays false: model 3 never renders
  TONGUE, because bumping TONGUE's model would change its seed and so its sound.

## The bodies

**WIRE: one string.** A tuned delay loop (`Strings.tune` and `Strings.Loop`, the house string). Each
catch plucks it at a point set by the upper arm's angle (a pulse and its inverted image a share of a
period later). The loop's low-pass basis is 12 × the note (12 kHz ceiling), with LOSS .15. These shape
choices come from the backup's WIRE prototype.

**RUB: a rubbed resonator.** Up to 16 harmonic partials with long memory (T60 2.8 s on the
fundamental, shorter above). They are rubbed continuously by one shared, seeded rosin-noise stream.
The upper arm's pressure swells the rub from a floor of .30, through a 40 ms lag, so it never fully
lifts. The catches strike the same partials as accents. Sound never pushes back on the arms.

## Velocity

A softer touch is a wider catch pulse, so a duller, rounder hit:

```text
q = .10 + .28 × (1 − .75 × CONTACT) × (1 − .65 × velocity)    (cycles)
w = q / f
contact seconds = w / (1 + w / .004)
```

Contact time shortens strictly as touch or CONTACT rises, at every pitch. It stays at most .38 of a
period and under 4 ms. RUB's rosin is also darker for a softer touch (its low-pass runs from 900 Hz
up to 6.9 kHz). Loudness then follows velocity with TONGUE's ×v^0.65.

Measured (JVM, C4, levels matched, spectral centroid of 2–2.7 s):

| | Soft (.3) / hard (1.0) |
| --- | --- |
| WIRE | 0.78 |
| RUB | 0.68 |

## HOLD (RUB)

- **How the loop is made:** RUB plays the real motion for 3 s. It then plays a 2-second window of
  that motion (3–5 s) in a circle: its catches, the upper-arm pressure and the rosin noise. A catch
  pulse that runs past the window's end wraps to its start. The window's last 150 ms of pressure and
  noise are blended toward the motion that led into it, so the circle has no jump.
- **Why it is seamless:** RUB is linear and loses energy, so a repeating push settles into a
  repeating sound. The held render runs three cycles (9 s), and its last cycle (7–9 s) is the loop.
  The seam is measured, not hidden with a crossfade.
- **Measured:** `Keys.seamError` is about 1e-9 at MIDI 36, 60 and 84; the bar is 1e-3.
- **What it claims:** this is a real excerpt of the motion played in a circle. It does not claim the
  chaotic motion repeats.
- **Before the window:** the held note is the finite note, sample for sample, up to the loop window
  (checked to 2.5 s).

WIRE stays finite: positive HOLD is refused.

## The backup prototype's recorded MIDI 84 failure

The backup's WIRE carried a research gate that compared its 4x render with an 8x reference render.
At MIDI 84, three early-spectrum cases were recorded just over that gate's .05 limit. That gate
compared two discretisations of one loop; it was not a pitch or audio check. It is **not ported**.
WIRE here runs at the house 4x rate like every other string, and is held to the checks that matter for
playing:

- **Pitch:** within 12 cents at MIDI 36, 48, 60, 72 and 84, with confidence over .4. Measured: 0.1
  cents at MIDI 84.
- **Contact pulses:** at MIDI 84 they still span about 21–64 internal samples, with the backup's
  repaired width law, so the attack is resolved.

## Tests (`SplayBodiesTest`)

- The bodies strike exactly TONGUE's model-2 catches.
- TONGUE renders the same bytes before and after the bodies run, at models 1 and 2.
- Pitch is within 12 cents across MIDI 36–84 for both bodies.
- A softer touch is duller once levels are matched (soft/hard brightness < .85), and loudness still
  follows velocity.
- WIRE and RUB are new bodies, not TONGUE settings, at 1 and 16 mechanisms:
  - each is over 1.6× brighter than TONGUE;
  - RUB keeps more sound between catches than WIRE.
- RUB's held loop is exactly one 2-second cycle, its seam is under 1e-6, and it matches the finite
  note before the window.
- Renders are deterministic, peak at most .94, and finish a warm render under 3 s (measured about
  70–120 ms). MIDI outside 36–84 and HOLD on WIRE are refused.
- RUB's noise follows the seed; WIRE has no noise.

## Still open

- **Owner listening:** WIRE and RUB are unheard.
- **The other three bodies:** AIR, BAR and SKIN.
- **Registration:** the SYNTH picker, the patch format at model 3, keygroups with three real touch
  layers (and held keys for RUB), presets and a starter. After a KEEP, each accepted render gets a
  pinned PCM24 hash.
- **Round robin and chord tools.**

## Why it is built this way

- **The catches keep their original timing,** so SPLAY's coordinated-to-separated gesture survives
  in a new body. Only what they strike changes.
- **WIRE is a string,** which stores and loses energy differently from TONGUE's four tongues, so it
  is a voice and not another setting.
- **RUB rubs as well as strikes,** so it sings between catches. Its floor and lag keep the rub from
  switching on and off with each swing.
- **The loop repeats a real stretch of the motion** through a linear body that loses energy, so the
  seam can be measured and is not hidden.
- **The risky decision is RUB's balance** between the rub and the catches. It is set by numbers only
  (the quietest tenth of 50 ms windows sits at about .7 of the median), and listening decides it.
