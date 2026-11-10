# BREAKER

BREAKER is a drum engine for surf rock by way of boom bap: drums as if sampled off a surf record,
pushed through a drum processor and a surf amp.

The characteristic gesture is **dusty crack → spring drip → crushed swell → speaker bark**.

[Design, references and listening rounds](superpowers/specs/2026-11-09-breaker-drum-engine-design.md).

## Status

Round 1b (renderer model 2), a prototype outside the factory presets (`PatchTypes.PROTOTYPES`).
Two voices, KICK and SNARE, built from the owner's round-0 picks.

At round 1 the owner heard:
- the snare, its takes and a half-strength hit as theirs;
- the kick as "close, but not it".

Round 1b gives the kick back the tank and the slap time its pick was heard with. A model-1 save
keeps round 1's sound. On 2026-11-10 the owner heard the re-fit kick as the same kick, and the snare
(with the slap back at 95 ms), the takes and the half-strength hits as theirs.

Round 2 adds ten drums through the same rig: the closed and open hats, cross stick, shaker,
tambourine, three toms and ride. On 2026-11-10 the owner heard them:
- **The hats:** nearly dry.
- **The cross stick:** the short slap.
- **The tambourine and shaker:** both belong.
- **The toms:** drippy.
- **The repeat and 40% hits:** the same drum, played by a softer hand.
- **The ride:** sounds like a crash. Round 3 re-voices it, and its sound is a candidate for the crash.

Pad A15 becomes the snare riser.

Not in the phone picker yet; the picker entry waits for a session with an Android SDK, as the house
rule says. Still to come:
- the crash and Bump, and the 16 pads assembled into a kit;
- two more genre kits, Dub Tank and Trip hop (the owner's picks);
- the snare riser and the surf tom fill;
- the audition page.

## How a hit is made

1. **Source:** each drum's settings are frozen in `Breaker.kt`.
   - **Backroom's SKIN kick, snare, hats, cross stick and shaker,** as accepted (`docs/BOOM_BAP.md`).
   - **Three SKIN toms:** one shell at about 124, 154 and 188 Hz.
   - **SKIN's long ride,** with its rivets sizzling.
   - **BACKWASH's CC0 tambourine recordings.**

   Each round-robin take is a fresh strike (`Restrike`):
   - the SKIN drums re-seed their noise;
   - the tambourine alternates its two recorded takes.

   The pad's velocity works per drum:
   - the kick, snare and toms take SKIN's own contact law;
   - the tambourine picks a recorded dynamic layer;
   - SKIN does not strike the hats, ride, shaker and stick, so a softer hit darkens their finished
     sound after the rig (step 9).
2. **DUST:** the rack's own CRUNCH and VINYL: the record and the sampler.
3. **Saturation:** an asymmetric tube curve at 4x.
4. **CRUSH, first half:** an FET-style compressor gluing the hit.
5. **DRIP:** a spring tank. Its echoes rise in pitch because a spring carries low notes faster than
   high ones, and the bend grows with every bounce. Each drum uses the tank the owner heard it
   through at round 0:
   - **the snare: tank B**, the surf spring they picked, with a deeper bend and a twang near 3 kHz;
   - **the kick: tank A**, a lighter, brighter bend. Their kick pick rang through it.
6. **SLAP:** an echo, a 95 ms slapback that grows toward 375 ms past halfway. 95 ms is the time the
   owner heard at round 0. On a kick the time matters: at 90 ms, the repeat adds to the body's
   lows instead of partly cancelling them, and the tail gets 5 dB more boom at 63 Hz.
7. **CRUSH, second half:** the same compressor after the spring, so each hit ducks the drip and it
   swells back up.
8. **AMP:** the rack's VALVE as a bright open-back combo.

9. **Soft hits on the unstruck drums** (hats, ride, shaker, stick): the finished hit's top is rolled
   off, near 4.8 kHz at 40%. Darkening their source does not survive the rig; see the design spec,
   "As built: round 2".

The hit is capped at 1.49 s with a fade, so it stays a one-shot.

## Controls

| Control | Default | What it does |
|---|---:|---|
| TUNE | .50 | the drum's own tuning, a quarter of its range either way |
| DUST | .35 | the record and sampler grit (.35 is Backroom's accepted crunch) |
| DRIP | per drum, below | how hard the hit drives the spring tank, and how much comes back |
| SLAP | .20 unless below | the echo: level and repeats, and past halfway its time |
| CRUSH | .90 unless below | the compressor: glue up to halfway, then also crushing after the spring |
| AMP | .35 | the combo's drive and sag |

On the shaker, which has no tuning of its own, TUNE moves its band.

Per-drum defaults (the ride's waits for round 3):

| Drum | DUST | DRIP | SLAP | CRUSH | Tank |
|---|---:|---:|---:|---:|---|
| KICK | .35 | .20 | .20 | .90 | A |
| SNARE | .35 | .60 | .20 | .90 | B |
| HAT_CLOSED | .35 | .10 | 0 | .60 | B |
| HAT_OPEN | .35 | .15 | 0 | .60 | B |
| STICK | .35 | .30 | .20 | .70 | B |
| SHAKER | .35 | .15 | .10 | .40 | B |
| TAMBOURINE | .30 | .35 | .20 | .50 | B |
| TOM_FLOOR | .35 | .60 | .20 | .80 | B |
| TOM_RACK | .35 | .45 | .20 | .80 | B |
| TOM_HIGH | .35 | .40 | .20 | .80 | B |
| RIDE | .30 | .45 | 0 | .60 | B |

## Verification

`BreakerTest` checks:
- **The house basics:** every hit is finite, bounded, centred and exactly 1.49 s long, and every
  control moves the sound.
- **Robustness:** an unknown key changes nothing, a non-finite control falls back to its default,
  and JSON round trips keep the model.
- **Velocity and takes:** soft and hard hits differ, takes are fresh strikes, and every take is
  deterministic.
- **The three round-0 claims:**
  - both tanks' echoes rise in pitch and never gain energy;
  - DRIP puts spring energy after the hit;
  - CRUSH past halfway lifts the tail.
- **Round 1b:**
  - the slap's first repeat lands at 95 ms (90 ms on model 1);
  - a model-1 save still renders model 1.
- **Round 2:**
  - the CRUSH swell past halfway on the kick and snare. Every drum whose tail starts more than
    6 dB under its hit gets lifted by the crush. The tambourine's jingles already sit within 6 dB.
  - DRIP rings after the hit on every drum. The ride's is the smallest, 2.6 dB, because its own wash
    already fills the band.
  - a 40% hit on the hats, ride, shaker and stick has an attack centroid more than 5% lower.

`SkinTest` checks that, asked, a later take re-seeds SKIN's hats, ride, shaker and stick, and that
unasked (every other engine), a take changes nothing.

`DeterminismTest` replays both voices and a take, byte for byte.
