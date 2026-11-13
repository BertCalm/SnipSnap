# SOOT

SOOT is a drum engine for grimy street boom bap, built only from physical models, after the owner's
sonic profile. SOOT is a working name: the label the profile was written after cannot name a kit,
voice or preset.

The characteristic gesture is **dry skin knock that sags → papery crack → tight coil clatter → the
kit breathing through a dictaphone**.

[Design, references, measurements and listening rounds](superpowers/specs/2026-11-12-soot-drum-kit-design.md).

## Status

**Sound approved at round 2** (2026-11-13: "All sounds fine"). The 16-pad kit (`SootKits.VERSION` 3)
is on the FRESH TAPE shelf (SKINS: "SOOT"). Its grids are proven in software through save, playback
and MPC 3 export. The engine stays a prototype outside the factory presets and the phone picker.

How the owner got there:
- **Round 0** picked the hits.
- **Round 1:**
  - accepted the Kotlin kick and snare;
  - moved the pads to GRIME .45;
  - found the hats' coil send.
- **Round 1b** removed that send, kept the alternates, and approved the kit.
- **Round 2:**
  - heard the re-voiced A11 and A12 as different drums;
  - accepted the coil roll and the loop point;
  - moved the four bars to the heavier dictaphone (.9).

Still to come:
- the owner's listen to the four sibling kits, which are built and on the shelf:
  - SOOT DRUMLESS, SOOT BASEMENT DUB, SOOT BRUSHED NOIR and SOOT WAREHOUSE INDUSTRIAL
    (`SootGenreKits.kt`, `:shell:generateSootGenreKits`);
  - listening page: https://claude.ai/artifact/J6S61MGPeNuErNGJq9v4xa;
- phone and MPC hardware;
- the picker entry, in a session with an Android SDK, as the house rule says.

## The kit (approved 2026-11-12, re-pointed by the picks)

As the MPC lays them out (A01 bottom left). The picks became the main pads, so three alternates now
hold the sounds that were not picked.

| | | | |
|---|---|---|---|
| A13 Narrow closed hat (9 cents) | A14 Narrow open hat (chokes A13) | A15 Coil roll | A16 Four bars (loops at 84 BPM) |
| A09 Steel plate | A10 The coil kicked alone | A11 Bench slap (high, clean metal) | A12 Deep coil snare (low, drowned in the spring) |
| A05 Heavy kick (sub t60 0.9 s) | A06 Dry crack | A07 Tension rod | A08 Small metal cup |
| A01 Kick (sag .35, sub 0.6 s) | A02 Hot snare (12-bit) | A03 Wide closed hat (25 cents) | A04 Wide open hat (chokes A03) |

## How a hit is made

1. **The drum** (`Soot.kt`):
   - **KICK:** ten circular-membrane modes. Every mode goes sharp on impact by SAG x velocity^1.5
     (SAG 1 is a semitone), then settles half that flat over about 90 ms. Plus a short wood frame
     knock, a beater click, and a sub an octave under the knock that falls from +7 semitones in
     18 ms, its length set by SUB, driven lightly for a third harmonic.
   - **SNARE:** a sub-millisecond stick snap, two paper bands (2.6 and 5.2 kHz), a taut head, wires,
     a short steel bench (BENCH), then the coil (COIL), then the cassette edge (EDGE: asymmetric
     saturation at 4x, a high loss, 12-bit past half).
   - **HAT_CLOSED, HAT_OPEN:** 112 struck-metal partials and no noise: six rods with detuned
     twins and 88 cluster partials, frozen in `SootTables.kt` from the round-0 generator so the
     metal is the one the owner picked. DETUNE spreads the twins (1 is 40 cents).
   - **ROD, PLATE, CUP:** a threaded rod beating against its 11-cent twin, a 30-mode steel plate,
     a five-mode cup.
   - **COIL:** a soft thump into the coil with its send opened to 120 Hz.
2. **The print**, what a pad keeps: the pad's own send into the coil (the kick's opened down to
   90 Hz, so the kick drives the spring; the hats send nothing from model 2), then the dictaphone at
   GRIME (.45 by default from model 2, .9 on model 1): the lows under 100 Hz go
   round the grit; above them an AGC that pumps, a small speaker's 2.7 kHz bump and low-pass, and a
   ROM reader's zero-order hold and bit loss. GRIME 0 bypasses the dictaphone.
3. **Output:** centred, 20 Hz high-pass, levelled to the house target, 4 ms fade, under 1.45 s.

The coil is `BreakerChain.spring` with `Soot.COIL_TANK`: 70 stretched allpasses (fc 2.2 kHz),
springs of 19 and 24 ms, a loop decay of 0.35 + 0.55 x COIL s. BREAKER's `Tank` gained fields for
this, with defaults that are BREAKER's own numbers.

Velocity is a strike, not a level: every drum takes it through a contact law (a softer hit is a
longer contact, a lower corner), the kick also through its sag, and the coil through its dwell.
A take re-strikes: fresh noise, a moved strike position, fresh metal phases.

## The kit (`SootKits.kt`)

- **Grids:** every pad has three velocity zones (.30, .65, 1) of four takes, struck afresh by the
  engine.
- **A15, the coil roll:** sixteen snare strikes speeding up into one coil, with a fader push over
  the build.
- **A16, four bars:** the groove through one shared coil the kick drives, then the dictaphone the
  kick pumps (GRIME .9, the owner's round-2 pick; the single pads stay at .45). It is exactly four bars at 84 BPM, its tail wrapped onto its start.
- **Recipes:** none. The pads carry their grids, as GATECRASH's do.

`./gradlew --no-daemon :shell:generateSootKit` builds the kit and proves it. The saved kit reloads
equal. The saved and exported PCM match the renders through `PadHit.resolve`, two cycles of every
zone. The MPC 3 track reads back with its zones, cycles and windows. The listening pack goes to
`testkit/soot-kit/listen/`. Then:

```sh
python3 scripts/soot_r0/build_page.py --round 2 testkit/soot-kit testkit/soot-r0
```

## Listen

```sh
./gradlew --no-daemon :synth:generateSootAudition          # testkit/soot-audition/
python3 scripts/soot_r0/build_page.py --round 1             # needs numpy and ffmpeg
```

Round 0's harness: `python3 scripts/soot_r0/soot_r0.py` then `python3 scripts/soot_r0/build_page.py`.

## Limits, stated plainly

- **The Kotlin port uses the app's noise and filter cascades,** not numpy's. The owner heard the
  kick and snare as their picks; the hats are re-heard at model 2.
- **The hats' velocity brightness mostly disappears in the print.** Dry, a hard hat has 5.5 dB more
  energy above 9 kHz than a soft one; through GRIME .9 it has 0.1 dB more. By ear (round 1) a soft
  hat is still a softer hand.
- **The dictaphone adds grit to a dark drum.** On the plate, the energy above 6 kHz rises from
  about -51 dB to -25 dB through GRIME .9. That is the ROM hold's aliasing, which the profile asked
  for; on the snare and hats the dictaphone removes 5 to 6 dB up there.
- **The kick's takes are the closest:** 18.5 to 22.7 dB apart. Most of a kick is its sub.
- **The shared bus lives on A16 only.** A sampler plays each pad alone, so a single-hit pad
  carries only its own breath.
- **The kit takes 27.6 s to build on this host** (4 cores). The four bars are 63% of it. A phone
  will be slower.
- **MPC 2 does not pass the round-robin export gate.** It keeps one take per zone. SFZ and
  DecentSampler were not generated.
- **Mono, no hardware.** Nothing has been played on a phone or an MPC.
