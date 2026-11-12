# SOOT

SOOT is a drum engine for grimy street boom bap, built only from physical models, after the owner's
sonic profile. SOOT is a working name: the label the profile was written after cannot name a kit,
voice or preset.

The characteristic gesture is **dry skin knock that sags → papery crack → tight coil clatter → the
kit breathing through a dictaphone**.

[Design, references, measurements and listening rounds](superpowers/specs/2026-11-12-soot-drum-kit-design.md).

## Status

Round 1 (renderer model 1): eight voices in Kotlin, a prototype outside the factory presets
(`PatchTypes.PROTOTYPES`) and the phone picker. Not heard in Kotlin yet: the confirming-listen page
is https://claude.ai/artifact/6DPRBQwsJLHre4kFp2UtVc (private to the owner).

Round 0 (Python harness) was heard on 2026-11-12. The owner picked:
- **Kick:** sag .35 with the longer sub (t60 0.6 s).
- **Snare:** the hot one, the 12-bit cassette.
- **Hats:** 25 cents of detune.
- **Metal:** the rod, plate, cup and the coil kicked alone, all kept.
- **Touch:** every soft hit heard as a softer hand.
- **Glue:** the coupled groove with the heavy dictaphone (GRIME .9). No pad print ruined.
- **Layout:** approved.

Still to come:
- the confirming listen on the Kotlin renders, and the pads' GRIME (.9 or .45);
- the 16 pads assembled into a kit, with velocity and round robin through saving, playback and
  export (GATECRASH's path: `PadHit.resolve`, MPC 3 read-back);
- A15, the coil roll (the percussion gate's riser), and A16, the four-bar phrase through one shared
  coil (the only place the kick can drive the whole kit's spring);
- two sibling genre kits, chosen by the owner;
- the picker entry, in a session with an Android SDK, as the house rule says.

## The kit (approved 2026-11-12, re-pointed by the picks)

As the MPC lays them out (A01 bottom left). The picks became the main pads, so three alternates now
hold the sounds that were not picked.

| | | | |
|---|---|---|---|
| A13 Narrow closed hat (9 cents) | A14 Narrow open hat (chokes A13) | A15 Coil roll (not built) | A16 Four-bar phrase (not built) |
| A09 Steel plate | A10 The coil kicked alone | A11 Bench snare | A12 Coil snare, cassette .5 |
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
   90 Hz, so the kick drives the spring), then the dictaphone at GRIME: the lows under 100 Hz go
   round the grit; above them an AGC that pumps, a small speaker's 2.7 kHz bump and low-pass, and a
   ROM reader's zero-order hold and bit loss. GRIME 0 bypasses the dictaphone.
3. **Output:** centred, 20 Hz high-pass, levelled to the house target, 4 ms fade, under 1.45 s.

The coil is `BreakerChain.spring` with `Soot.COIL_TANK`: 70 stretched allpasses (fc 2.2 kHz),
springs of 19 and 24 ms, a loop decay of 0.35 + 0.55 x COIL s. BREAKER's `Tank` gained fields for
this, with defaults that are BREAKER's own numbers.

Velocity is a strike, not a level: every drum takes it through a contact law (a softer hit is a
longer contact, a lower corner), the kick also through its sag, and the coil through its dwell.
A take re-strikes: fresh noise, a moved strike position, fresh metal phases.

## Listen

```sh
./gradlew --no-daemon :synth:generateSootAudition          # testkit/soot-audition/
python3 scripts/soot_r0/build_page.py --round 1             # needs numpy and ffmpeg
```

Round 0's harness: `python3 scripts/soot_r0/soot_r0.py` then `python3 scripts/soot_r0/build_page.py`.

## Limits, stated plainly

- **Not heard in Kotlin.** The Kotlin port uses the app's noise and filter cascades, not numpy's.
- **The hats' velocity mostly disappears in the print.** Dry, a hard hat has 5.5 dB more energy
  above 9 kHz than a soft one; through GRIME .9 it has 0.1 dB more. The renders still differ (18 dB
  apart). The confirming listen asks.
- **The dictaphone adds grit to a dark drum.** On the plate, the energy above 6 kHz rises from
  about -51 dB to -25 dB through GRIME .9. That is the ROM hold's aliasing, which the profile asked
  for; on the snare and hats the dictaphone removes 5 to 6 dB up there.
- **The kick's takes are the closest:** 18.5 to 22.7 dB apart. Most of a kick is its sub.
- **No shared bus on pads.** A sampler plays each pad alone, so a pad carries only its own breath.
- **Mono, no hardware.** Nothing has been played on a phone or an MPC.
