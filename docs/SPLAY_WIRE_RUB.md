# SPLAY WIRE and RUB (model 3: both kept)

SPLAY had one voice, TONGUE. These are the first two of the five further bodies proposed for it. The
characteristic gesture is unchanged: **common release → coordinated catches → separating flams →
bounded decay**. The pendulums and the catch times are exactly model 2's. Only the body the catches
strike is new.

Status: implemented in `SplayBodies.kt` and tested in JVM tests. **Not registered** in the SYNTH
picker, the patch format, the starters or MAKE INSTRUMENT yet.

## Owner listening

- **Round `splay-voices-1` (14 Nov 2026):**
  - **WIRE: KEEP** on all three of its clips: S2 (16 mechanisms at C4), S4 (soft, medium and hard
    touch) and S6 (a six-note phrase, C3 to C5).
    `SplayBodiesTest` pins every take behind those clips (ten float renders) and rebuilds the three
    clip WAVs byte for byte against the SHA-256 of the files the owner heard:

    | Clip | PCM16 WAV SHA-256 |
    | --- | --- |
    | S2 | `32ef3c5ccd867028696fd6ba7cd78b9bd9066405b6018a6071a99a05b4a3798a` |
    | S4 | `0a789d83f67a8caad1e4c5011f51c04219e71f2eecaf9849ee866435457891d7` |
    | S6 | `949e2cff576a30b6ab24964109c92f177d9722d3ea4a7b3870655ddf848b94c5` |

  - **RUB: TWEAK** on all four of its clips (S3, S5, S7, S8). The note on S3: "We need a bit of a catch
    like gesture in the sound."
  - S1 was the TONGUE reference (also marked KEEP; it is the accepted sound and unchanged).
- **Round `splay-voices-2` (14 Nov 2026):** RUB re-tuned with a stick-slip catch (below), in the same
  four shapes (S9–S12) with S1 again as the reference. **RUB: KEEP** on all four clips, no notes.
  `SplayBodiesTest` pins every take behind them (eleven float renders, including the held render and
  its loop start) and rebuilds the four clip WAVs byte for byte:

  | Clip | PCM16 WAV SHA-256 |
  | --- | --- |
  | S9 (16 mechanisms at C4) | `0175fc33551adeeedf5ce919f3732f2eb4de38320e69757be58af8a75af9dbf9` |
  | S10 (soft, medium, hard) | `36bd0047c5c2630eec5636be38096ab18b7f43d18da54f916e87568fb511a681` |
  | S11 (phrase, C3 to C5) | `307697b0e02587a666236acbe5b24d957e2c9f54143f789f96682fa37b820eed` |
  | S12 (held, loop played twice more) | `de152edadf72f496b4eafe5e0f793bc04da55c32dd533a4fed1909224a989856` |

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
lifts. Sound never pushes back on the arms.

**RUB's catches (round 2): stick, then slip.** In round 1 every catch struck the same partial balance
as a soft accent, so a long-ringing body smoothed it away (about 2.5 dB of swing around a catch). Now
each catch is a bowed-string stick-slip gesture:

1. **Grip (10 ms before the catch):** the rub sags by up to 75%, and the grip holds the resonator back
   (an extra 300 per second of damping at a full touch, 60% of that at the softest), so the ringing
   ducks.
2. **Slip (at the catch):** the catch strikes the partials, ten times harder than in round 1, and the
   rub surges and dies away over 30 ms.
3. **Scrape:** a short (18 ms) burst of its own seeded friction noise, band-passed between 1.2 kHz and
   2.2–7 kHz (brighter for a harder touch), rides on the slip.
4. **Where it lands:** the upper arm's angle picks the catch point between three anchors (.06, .22,
   .38 along the resonator), so successive catches strike different partial balances, as WIRE's
   pluck point does.

A stronger catch grips and slips harder. Everything is linear in time and repeats with the loop
window, so HOLD still closes.

Measured (JVM, C4, 16 mechanisms, median over 59 catch groups, 5 ms frames):

| | Dip-to-peak swing around a catch |
| --- | --- |
| RUB, round 1 | 2.5 dB |
| WIRE (kept) | 3.2 dB |
| RUB, round 2 | 9.5 dB |

Under 1% of RUB's energy sits above 4 kHz, so the scrape adds bite, not static.

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
| RUB (round 2) | 0.54 |

## HOLD (RUB)

- **How the loop is made:** RUB plays the real motion for 3 s. It then plays a 2-second window of
  that motion (3–5 s) in a circle: its catches, the upper-arm pressure and the rosin noise. A catch
  pulse that runs past the window's end wraps to its start. The window's last 150 ms of pressure and
  noise are blended toward the motion that led into it, so the circle has no jump.
- **Why it is seamless:** RUB is linear and loses energy, so a repeating push settles into a
  repeating sound. The held render runs three cycles (9 s), and its last cycle (7–9 s) is the loop.
  The seam is measured, not hidden with a crossfade.
- **Measured:** `Keys.seamError` is below 1e-6 at MIDI 36, 60 and 84 (0 at MIDI 60 in round 2); the bar is 1e-3.
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
- WIRE and RUB render exactly the takes the owner kept: their float renders and the seven heard clip WAVs.
- RUB's catches swing more than 6 dB around each catch group (measured about 9.5), with under 3% of
  its energy above 4 kHz.
- RUB's held loop is exactly one 2-second cycle, its seam is under 1e-6, and it matches the finite
  note before the window.
- Renders are deterministic, peak at most .94, and finish a warm render under 3 s (measured about
  70–120 ms). MIDI outside 36–84 and HOLD on WIRE are refused.
- RUB's noise follows the seed; WIRE has no noise.

## Still open

- **The other three bodies:** AIR, BAR and SKIN, kept in round `splay-voices-3`; see
  [SPLAY_AIR_BAR_SKIN.md](SPLAY_AIR_BAR_SKIN.md).
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
- **RUB's catches grip before they slip,** because a long-ringing body hides an accent but not a
  duck followed by a strike. That dip-then-attack is what a bow catching and letting go sounds like.
- **The risky decision is RUB's balance** between the singing rub and the catch gestures. It is set
  by numbers only (a 9.5 dB swing at the catches, while the quietest tenth of 50 ms windows still
  sits at about .6–.7 of the median), and listening decides it.
