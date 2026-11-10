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

Not in the phone picker yet; the picker entry waits for a session with an Android SDK, as the house
rule says. Still to come:
- the rest of the 16-pad kit;
- two more genre kits, Dub Tank and Trip hop (the owner's picks);
- the snare riser and the surf tom fill;
- the audition page.

## How a hit is made

1. **Source:** Backroom's accepted SKIN kick and snare (`docs/BOOM_BAP.md`), their settings frozen
   in `Breaker.kt`. Struck at the pad's velocity, and struck again for each round-robin take
   (`Restrike`).
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

The hit is capped at 1.49 s with a fade, so it stays a one-shot.

## Controls

| Control | Default | What it does |
|---|---:|---|
| TUNE | .50 | the drum's own tuning, a quarter of its range either way |
| DUST | .35 | the record and sampler grit (.35 is Backroom's accepted crunch) |
| DRIP | kick .20, snare .60 | how hard the hit drives the spring tank, and how much comes back |
| SLAP | .20 | the echo: level and repeats, and past halfway its time |
| CRUSH | .90 | the compressor: glue up to halfway, then also crushing after the spring |
| AMP | .35 | the combo's drive and sag |

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

`DeterminismTest` replays both voices and a take, byte for byte.
