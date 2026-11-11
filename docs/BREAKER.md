# BREAKER

BREAKER is a drum engine for surf rock by way of boom bap: drums as if sampled off a surf record,
pushed through a drum processor and a surf amp.

The characteristic gesture is **dusty crack → spring drip → crushed swell → speaker bark**.

[Design, references and listening rounds](superpowers/specs/2026-11-09-breaker-drum-engine-design.md).

## Status

Round 3 (renderer model 3), a prototype outside the factory presets (`PatchTypes.PROTOTYPES`).
Fourteen voices; the kick and snare are built from the owner's round-0 picks.

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

Pad A15 becomes the snare riser, and the shaker moves to A16 (Bump moves to the genre kits).

Round 3 (model 3) re-voices the ride and adds the crash and the snare riser. Through the rig, the
first cymbals and riser were harsh: too loud next to the kit, fizzy and piercing. The owner asked
for the recorded cymbals to be replicated with synthesis instead. On 2026-11-11 they picked:
- **The cymbals:** the "tight" modal synth, heard dry ("these are fine"), in the kit dry by
  default, with every control able to dial the rig back in.
- **A13:** this ride. **A14:** this crash, ringing about 3 s.
- **A15:** the tamed riser.

Model-2 saves keep round 2's ride.

Not in the phone picker yet; the picker entry waits for a session with an Android SDK, as the house
rule says. Still to come:
- the 16 pads assembled into a kit, with velocity and round robin through saving, playback and
  export;
- two more genre kits, Dub Tank and Trip hop (the owner's picks);
- the surf tom fill;
- the audition page.

The kit, as agreed:

| | | | |
|---|---|---|---|
| A13 Ride | A14 Crash (rings about 3 s) | A15 Snare riser | A16 Shaker |
| A09 Floor Tom | A10 Rack Tom | A11 High Tom | A12 Crushed Snare |
| A05 Crushed Boom | A06 Drip Snare | A07 Cross Stick | A08 Tambourine |
| A01 Boom | A02 Bap | A03 Closed Hat | A04 Open Hat (chokes A03) |

## How a hit is made

1. **Source:** each drum's settings are frozen in `Breaker.kt`.
   - **Backroom's SKIN kick, snare, hats, cross stick and shaker,** as accepted (`docs/BOOM_BAP.md`).
   - **Three SKIN toms:** one shell at about 124, 154 and 188 Hz.
   - **The ride and crash:** a modal synth fitted to CC0 recordings (below, "The cymbals"). Model 2's
     ride was SKIN's long ride, with its rivets sizzling.
   - **The riser:** twenty strikes of the snare, speeding up from about 4 to 25 a second over 2.4 s
     and rising in strength.
   - **BACKWASH's CC0 tambourine recordings.**

   Each round-robin take is a fresh strike (`Restrike`):
   - the SKIN drums re-seed their noise;
   - the cymbals strike every mode afresh (below);
   - the riser re-strikes all twenty snares;
   - the tambourine alternates its two recorded takes.

   The pad's velocity works per drum:
   - the kick, snare, toms and the riser's strikes take SKIN's own contact law;
   - the cymbals take the difference measured between a soft and a hard recording;
   - the tambourine picks a recorded dynamic layer;
   - SKIN does not strike the hats, shaker and stick, so a softer hit darkens their finished
     sound after the rig (step 9).
2. **DUST:** the rack's own CRUNCH and VINYL: the record and the sampler.
3. **Saturation:** an asymmetric tube curve at 4x. The cymbals and the riser skip it: on noisy
   metal and a dense roll, it was the fizz the owner heard as harsh.
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
8. **AMP:** the rack's VALVE as a bright open-back combo. On the cymbals, AMP is how much of the
   combo you hear, so at 0 there is no amp at all.

9. **Soft hits on the unstruck drums** (hats, shaker, stick, and model 2's ride): the finished
   hit's top is rolled off, near 4.8 kHz at 40%. Darkening their source does not survive the rig;
   see the design spec, "As built: round 2".

10. **The riser's push:** the crush evens out a crescendo, so the riser rises after the rig, from
    -12 dB to full over the build.

A hit is capped at 1.49 s with a fade, so it stays a one-shot. Two voices are longer and are
declared PERC, never classified: the crash rings 3.2 s (the owner's call), and the riser lasts
3.4 s, the build and the spring after it.

## The cymbals

A cymbal is several hundred resonances beating against each other. Noise in bands gets each band's
level and decay right but none of that shimmer, and the owner heard it as processed. So
`BreakerCymbal` resynthesizes the recording's own resonances (sinusoids plus a residual, Serra and
Smith 1990):
- **Modes:** 400 on the ride and 418 on the crash, picked from an 8192-point STFT of a hard hit.
  Each has a frequency, an amplitude and one decay.
- **Tight:** the fit measures two decay slopes per mode, the first 300 ms and after. Each mode keeps
  the steeper one, which leaves out the slow tail the overhead mic heard from the room. The owner
  picked it, heard dry.
- **Level:** one correction per third-octave band, matched to the recording over 80 to 320 ms.
- **The stick:** 60 ms of noise in third-octave bands at the recording's levels over its first
  40 ms, at a 0.6 mix (shape, as auditioned).
- **Velocity:** each mode moves by the level difference, per band, between the soft and hard
  recordings, and the stick moves toward the soft recording's spectrum. A softer crash carries more
  middle and less presence. A softer ride carries more low ring (250 to 315 Hz) and less at 1.3 to
  2 kHz. Neither is simply darker.
- **A take:** a fresh strike. Each mode is up to 0.3 Hz off in pitch, with its own phase and a level
  scatter of 1.6 dB rms, and the stick is fresh.
- **TUNE:** three semitones either way.

Source: Virtuosity Drums (CC0 1.0), `sfzinstruments/virtuosity_drums` at `9f04cf9a`, overhead mic,
the ride and crash at their softest and hardest layers. `scripts/fit_breaker_cymbals.py` writes
`synth/src/main/resources/breaker/cymbals.json`, and `NOTICE.txt` beside it lists the four files and
their hashes. Only the fitted numbers ship; no recording does.

## Controls

| Control | Default | What it does |
|---|---:|---|
| TUNE | .50 | the drum's own tuning, a quarter of its range either way |
| DUST | .35 | the record and sampler grit (.35 is Backroom's accepted crunch) |
| DRIP | per drum, below | how hard the hit drives the spring tank, and how much comes back |
| SLAP | .20 unless below | the echo: level and repeats, and past halfway its time |
| CRUSH | .90 unless below | the compressor: glue up to halfway, then also crushing after the spring |
| AMP | .35 unless below | the combo's drive and sag (on the cymbals, how much of the combo) |

On the shaker, which has no tuning of its own, TUNE moves its band.

Per-drum defaults:

| Drum | DUST | DRIP | SLAP | CRUSH | AMP | Tank |
|---|---:|---:|---:|---:|---:|---|
| KICK | .35 | .20 | .20 | .90 | .35 | A |
| SNARE | .35 | .60 | .20 | .90 | .35 | B |
| HAT_CLOSED | .35 | .10 | 0 | .60 | .35 | B |
| HAT_OPEN | .35 | .15 | 0 | .60 | .35 | B |
| STICK | .35 | .30 | .20 | .70 | .35 | B |
| SHAKER | .35 | .15 | .10 | .40 | .35 | B |
| TAMBOURINE | .30 | .35 | .20 | .50 | .35 | B |
| TOM_FLOOR | .35 | .60 | .20 | .80 | .35 | B |
| TOM_RACK | .35 | .45 | .20 | .80 | .35 | B |
| TOM_HIGH | .35 | .40 | .20 | .80 | .35 | B |
| RIDE, CRASH | 0 | 0 | 0 | 0 | 0 | B |
| RISER | .20 | .60 | .20 | .50 | .20 | B |

The cymbals are dry by default, and every control at 0 is a true bypass. Their presets are Dry,
Through the rig, and In the spring; the riser's are Tamed, Pumping, and Short spring.

Model 2's ride: DUST .30, DRIP .45, SLAP 0, CRUSH .60, AMP .35.

## Verification

`BreakerTest` checks:
- **The house basics:** every hit is finite, bounded and centred, and every control moves the
  sound. Every hit is exactly 1.49 s long, except the crash (3.2 s) and the riser (3.4 s).
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
  - a 40% hit on the hats, shaker and stick has an attack centroid more than 5% lower.
- **Round 3:**
  - at their defaults the cymbals are the fitted synth with only the engine's finish (DC removal,
    fade, level), and AMP brings the combo in;
  - each cymbal has at least 300 modes; the crash still rings 1.6 to 2.2 s in (above -45 dB re
    its first 100 ms) and is declared PERC;
  - a 40% cymbal changes its spectrum's shape by more than 3 dB in some octave at matched loudness
    (ride +9 dB at 250 Hz; crash +6 dB at 500 Hz);
  - the riser builds by at least 8 dB;
  - model 2 refuses the crash and riser, and model 2's ride still renders model 2.

`SkinTest` checks that, asked, a later take re-seeds SKIN's hats, ride, shaker and stick, and that
unasked (every other engine), a take changes nothing.

`DeterminismTest` replays every voice and a take, byte for byte, and through a JSON round trip.
