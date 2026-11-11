# BREAKER drum engine: design

Status: round 1 built (KICK and SNARE in Kotlin, prototype status, outside the factory presets).
Round 0 heard by the owner on 2026-11-09 (below). The rest of the kit, the genre kits, the roll
and the audition page are later rounds.
Repository truth: `c00edb997` (main after #995) for the design; round 1 built on `f4213fe14`. Read: `FxChain.SECTIONS` (28 sections;
compressor, squash and valve come before echo, spring and room), `Spring.kt` (a Schroeder
network of four combs and two allpasses, no dispersion), `Compressor.kt`, `Squash.kt`,
`Valve.kt`, `Crunch.kt`, `Vinyl.kt`, `Echo.kt`, `Hush.kt`, `Backwash.kt` (a private chorus →
compressor → VALVE path), `SkinVoice`, `ThumpVoice`, `BackwashVoice`. "Breaker" appears only
as Osprey's internal `ContactKind.BREAKER`, not as an engine.

The characteristic gesture is **dusty crack → spring drip → crushed swell → speaker bark**.

## What it is

A drum engine for a kit: boom bap drums as if sampled off a surf record, run through a
DFX-1-style chain and a surf amp. Every hit gets the record's grit, then tube saturation,
then a real spring tank's chirping drip and a slap echo. Next comes an FET-style compressor.
Past halfway, it sits after the spring and pulls the drip back up behind the hit. Last is a
bright combo amp.

## The references, distilled (research names only, never product names in the app)

- **Walrus Audio DFX-1** (manual v1.03):
  - **Compressor:** FET (attack 0 to 10 ms, release 0.05 to 1.5 s) or VCA.
  - **The rest:** asymmetric tube saturation (0 to +24 dB), vintage or modern delay (10 ms to
    2 s), spring or plate reverb (0.2 to 8.2 s), with a noise gate and EQ in front.
  - **Two orders.** Forward is EQ → gate → comp → saturation → delay → reverb. Reverse is
    EQ → gate → saturation → reverb → delay → comp.
  - Essence: the order switch, because Reverse compresses the reverb.
- **The amp** (assumed: a blackface-style clean combo fed by a 6G15-style tube spring unit):
  - Dwell drives the tank (the drip), Mixer blends it, Tone darkens its return. The amp is
    bright, open-backed and breaks up gently.
  - Essence: an overdriven spring in front of a clean, bright amp.
- **Boom bap:** drums sampled from records into a 12-bit, 26.04 kHz sampler: aliasing grit,
  then compressed hard. Essence: grit at the source.
- **Surf drums:** a bright snare and toms, a washy ride and crash, the tank on the backbeat.
  Essence: the drip on the snare.

## Why it earns a pad

The nearest siblings are BACKWASH (drum → moving double → parallel compression → VALVE) and
the FX rack. They cannot do three things:
- **Make a spring chirp.** SPRING is a small Schroeder room.
- **Compress after the reverb.** In the rack the compressor sits before the reverbs, and
  BACKWASH has no reverb.
- **Run the spring into the amp.** VALVE sits before SPRING in the rack.

BACKWASH set the precedent: an order the fixed rack cannot make, owned by an engine.

## Signal flow

```
voice, TUNE, velocity
   │
   ▼
[Source: an acoustic kit hit, reused (SKIN, or BACKWASH's dry source), picked by ear]
   │ audio 44.1 kHz
   ▼
[DUST: 12-bit, 26.04 kHz hold (CRUNCH) + record bed (VINYL), reused]
   │
   ▼
[SAT: asymmetric tube curve, 4x, band-limited]
   │
   ▼
[CRUSH, first half: FET compressor before the spring (glue)]
   │
   ├──────────────────────────────▶ dry
   ▼
[SPRING TANK: dwell drive → send high-pass → dispersive allpass chain (the chirp)
   inside a delay loop, loop gain < 1 by construction → TONE]   return level = DRIP
   │
   ▼  (dry + return)
[SLAP: one delay line, 60 to 140 ms, repeats darken, feedback ≤ 0.5]
   │
   ▼
[CRUSH, second half: FET compressor after the spring (the crushed swell)]
   │
   ▼
[AMP: VALVE, bright open speaker (its own 4x round trip)]
   │
   ▼
[Output: DC block → levelTo → fadeTail, capped at 1.49 s so it stays a hit]
```

## Macro contract (defaults are shape, not measurement)

| Control | Default | What it changes | Required interaction | Test |
|---|---:|---|---|---|
| TUNE | per voice | the drum's pitch, by the source's own law | | the source centroid moves monotonically |
| DUST | .35 | bits 16 → 10, hold rate 44.1 → 26.04 → 18 kHz, the crackle bed | DUST × CRUSH: the crush lifts the bed, capped | aliasing energy rises; bed stays 40 dB or more under the hit at CRUSH 1 |
| DRIP | .45 | dwell (drive into the tank) and the tank's return | DRIP × CRUSH: past .5 the crush swells the drip | 1 to 4 kHz chirp energy, 50 to 400 ms after the hit, rises |
| SLAP | .20 | slap level and repeats | SLAP × CRUSH: crushed repeats come up | a repeat peak at the slap time |
| CRUSH | .50 | 0 to .5: glue before the spring; .5 to 1: adds the crush after it | the order law itself | the tail-to-hit ratio (100 to 400 ms against the first 20 ms) rises past .5 |
| AMP | .35 | VALVE drive and sag into a bright open speaker | AMP × DUST: the grits stack | harmonic share and centroid rise |

No HOLD: these are hits, as in BACKWASH.

## Voices

- **R1:** KICK (the boom) and SNARE (the bap) through the whole chain.
- **After the listen:**
  - closed and open hats, ride, crash;
  - three toms (surf fills), tambourine;
  - BUMP, the tank rattled on its own.
- **Kit and presets:** after the listen.

## The three directions

- **Strong: BREAKER, an engine.** STACK in the DFX-1's Reverse order, plus DISTIL.
  - The gesture is clear.
  - It does what neither BACKWASH nor the rack can: the drip, and the crush after the space.
  - Risk is low to medium: one new spring model, bounded by construction.
  - Three rounds:
    - R1: KICK, SNARE and the chain.
    - R2: the rest of the kit.
    - R3: kit and presets after listening.
- **Alternate A: the lightest landing.**
  - A TANK control on the rack's SPRING, so it chirps (TANK 0 is today's sound, bit for bit).
  - A surf board over sections that already exist.
  - A 16-pad starter kit built from SKIN and BACKWASH voices.
  - Every pad in the app gains the drip, but this loses the crushed swell and the
    spring-into-the-amp order, which the rack cannot make.
  - Risk is low (it must prove TANK 0 unchanged). One or two rounds.
- **Alternate B: kick the amp.**
  - INVERT: the spring tank is the drum. The hit only rattles the springs, the crash a surf
    amp makes when bumped, pitched by TUNE, with the toms played into it.
  - The strangest and most surf of the three.
  - Risk is high: a pitchless boing reads as an effect, not a drum. Better as one voice (BUMP)
    inside Strong than as its own engine.

## Risks

1. **A kick into a spring is mud.** High-pass the tank's send, as a real driver transformer
   does (250 Hz, shape).
2. **The crush lifts the crackle and hiss into a bed.** Cap the bed, and test it at CRUSH 1,
   DUST 1.
3. **The spring's cost and stability.** The allpass chain is lossless; only the tank's echo
   loop feeds back, with gain under 1 times a low-pass. About 100 allpasses at 44.1 kHz is
   roughly 10 ms per pad (proposed; measure it).
4. **Length.** Drip plus slap tails pass 1.5 s, where the classifier calls it a LOOP. Cap the
   output at 1.49 s with a fade, as BACKWASH does.
5. **Classes.** A dark, crushed chain moved BACKWASH's snare to PERC and its hats to SNARE in
   the classifier. Measure, then declare honestly.
6. **Trademarks.** Add Walrus, DFX, Fender, 6G15, 1176 and Rossum to the blocklist. Voice and
   preset names stay generic: Drip, Crushed, Bump.

## Render budget

Under 100 ms median per pad on a desktop (BACKWASH: 52 ms median):
- source 10 to 30 ms;
- dust about 2 ms;
- spring about 10 ms;
- compressors about 2 ms;
- VALVE about 50 ms (its 4x round trip on 1.5 s).

No solver, so nothing to cache.

## Cheapest proof (R0, Python harness, before any Kotlin)

1. **The spring alone, on an impulse and a snare:**
   - the chirp: 2 kHz arrives 20 ms or more after 200 Hz, on every echo;
   - energy falls at every DRIP setting;
   - render time.
2. **The chain on KICK and SNARE:** CRUSH at .2, .5 and .9 against DRIP at .2 and .6. A
   listening page at one loudness: the gesture, and whether the crushed swell reads as a
   spring swelling back up or as a broken gate.

## Why it is built this way

1. **The order is the instrument.** Past halfway, CRUSH moves the compressor after the
   spring. Each hit ducks the drip, and the drip swells back up behind it. That is the DFX-1's
   Reverse mode, and it is why this is an engine: the rack's fixed order puts the compressor
   before the reverbs.
2. **A real spring chirps.** The rack's SPRING is a small room. A spring carries low notes
   faster than high ones, so every echo arrives low notes first and rises, like a water drop's "plink": the surf drip (an earlier draft said falling; the source and the R0 measurement say rising). A long
   chain of allpass filters makes that. Allpasses neither add nor remove energy, so it cannot
   run away.
3. **Grit goes first.** Boom bap drums came off a record into a 12-bit sampler before anything
   else touched them. Reusing CRUNCH and VINYL keeps that grit identical to the rack's.
4. **The amp is last and bright:** a surf combo with a clean, open speaker. VALVE already
   models one.
5. **The one risky decision:** crushing after the spring also lifts the record's crackle. It
   is capped and tested.

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| The rack's compressor and VALVE come before SPRING | verified | `FxChain.SECTIONS`, `c00edb997` |
| SPRING has no dispersion (Schroeder combs and allpasses) | verified | `Spring.kt` KDoc |
| BACKWASH runs a private chorus → compressor → VALVE path | verified | `Backwash.kt` |
| DFX-1 orders, ranges and controls | verified (manual) | DFX-1 manual v1.03 |
| A dispersive allpass chain makes a spring's chirp | sourced | Välimäki, Parker, Abel, JAES 58(7/8), 2010; Parker 2011 |
| The defaults above | proposed (shape) | none |
| Anything sounds surf or boom bap | not heard | the R0 page |

## Sources

- DFX-1 product page: https://www.walrusaudio.com/products/dfx-1-percussion-processing-unit
- DFX-1 manual v1.03: https://cdn.shopify.com/s/files/1/0906/8480/files/DFX-1_Digital_Manual_v1.03.pdf
- Fender Reverb Unit (6G15): https://en.wikipedia.org/wiki/Fender_Reverb_Unit, https://wiki.surfguitar101.com/ReverbFaq
- Parametric spring reverberation: https://aes2.org/publications/elibrary-page/?id=15511 ; efficient dispersion structures (Parker 2011): https://asp-eurasipjournals.springeropen.com/articles/10.1155/2011/646134
- SP-1200 (12-bit, 26.04 kHz): https://en.wikipedia.org/wiki/E-mu_SP-1200, https://www.rossum-electro.com/products/sp-1200/

## R0 (2026-11-09, Python harness, not Kotlin)

Sources: Backroom's accepted SKIN kick and snare, rendered by the app, dry and through DUST
(CRUNCH + VINYL). Chain in `breaker_r0.py`. Page: https://claude.ai/artifact/SB6oe7FxgsnTV3zgoupmYB

| Measured | Result |
|---|---|
| Chirp direction (tank A: 60 stretched allpasses, K 5, coefficient 0.6, fc 4.3 kHz) | group delay per pass 1.7 ms at 200 Hz, 2.8 ms at 2 kHz, 10.8 ms at 3.5 kHz: highs later, rising chirps, dispersion growing every echo |
| Tank B (100 stages, K 7, coefficient 0.7, fc 3 kHz) | 2.8 ms at 200 Hz, 8.9 ms at 2 kHz, 46 ms at 3.5 kHz: the bend lower and deeper, a twang near 3 kHz |
| Tank stability (DRIP 0.6) | energy falls about 3 dB per 100 ms, monotonically; no limiter |
| The crushed swell (tail 100-400 ms against the first 20 ms) | snare at DRIP 0.2: -18.2, -14.8, -8.5 dB at CRUSH 0.2, 0.5, 0.9; kick: -19.3, -18.9, -12.6 dB |
| Crackle bed at DUST 1, CRUSH 1 (no spring) | VINYL spans only the source (0.23 to 0.32 s); its last 100 ms rise 10 dB on the kick (-47 to -37 dB re peak), 1.4 dB on the snare |
| Brief's "2 kHz 20 ms after 200 Hz per echo" | not met by either tank per pass (1.1 and 6 ms); it accumulates over echoes. Revised: judge by ear |
| Render time | Python only (5 to 7 s, per-sample loops); no Kotlin number yet |

## Completion gates (docs/SYNTH_COMPLETION_PROGRAM.md, owner standard 2026-11-07)

| Gate | Applies | BREAKER's plan |
|---|---|---|
| Drum synth: multiple distinct genre kits on the shelf | yes | Three kits from one engine, each in `StarterKits.ALL` and one `StarterShelf.GROUPS` group: Breaker (surf x boom bap, the 16 pads agreed), Dub Tank (one-drop dub: long slap, wet spring, rimshot) and Trip hop (a crushed spring, heavy and dark). The owner picked the two genres on 2026-11-09 |
| Percussion: authored roll/riser, not identical repeated hits | yes | Two authored finite articulations: a snare roll riser into the spring (each hit a fresh re-strike at rising velocity, the tank accumulating, the crush pumping) and a surf tom fill (high to floor). Proposed placement: A15 becomes the riser (the shaker leaves the Breaker kit) |
| All instruments: velocity levels and round robin through render, recipe, player and export | yes | Velocity and take pass into the SKIN sources (native strike velocity, `Restrike` re-strikes); the chain keeps them. Kit pads use the shared paths (`Velocity.variantsAt`, Robin); no private RR system (the programme forbids per-engine RR). Known shared limit: `ArrangedPad` refuses soft variants with takes (SC-02); record verified/partial per path |
| Melodic engine (six voices, keygroups, chords) | no | a drum engine |
| Articulation: appropriate HOLD, STRUM | no HOLD | hits stay finite under 1.5 s; HOLD not offered, as BACKWASH |

The final audition page carries the evidence for each row.

## Round 0, heard (2026-11-09)

Page: https://claude.ai/artifact/SB6oe7FxgsnTV3zgoupmYB (Python harness, Backroom's kick and snare).

| Question | Heard |
|---|---|
| Tank A or tank B: which drips like a surf spring? | **B** (the bend lower and deeper, 100 stages, coefficient 0.7, transition 3 kHz) |
| Snare at low, middle and high crush: does the high one swell back up? | **"Swells, I like it"**: the crush after the spring stays |
| Against Backroom's accepted kick and snare: still boom bap underneath? | **"Still boom bap"** |
| Kick: which one? | **High crush** (CRUSH 0.9, DRIP 0.2) |

Round 1's defaults follow: tank B, CRUSH 0.9 on both, DRIP 0.2 on the kick and 0.6 on the snare.

## The kit (16 pads, agreed 2026-11-09)

As the MPC lays them out (A01 bottom left). The bottom row is Backroom's accepted core through the
chain; the second row pushes it; the third is the surf fills; the top row is the cymbals and the tank.

| | | | |
|---|---|---|---|
| A13 Ride (washy) | A14 Crash (through the amp) | A15 Shaker, or the snare riser (proposed swap) | A16 Bump (the tank kicked) |
| A09 Floor Tom (drippy) | A10 Rack Tom | A11 High Tom | A12 Crushed Snare |
| A05 Crushed Boom | A06 Drip Snare | A07 Cross Stick (slap) | A08 Tambourine |
| A01 Boom | A02 Bap | A03 Closed Hat | A04 Open Hat (chokes A03) |

Sources: Backroom's SKIN kick, snare, hats, cross stick and shaker; SKIN toms and ride; BACKWASH's
tambourine. New: a crash (no drum engine has one) and Bump (the tank alone). Every pad gets two softer
velocity layers and re-struck takes.

## Genre kits (the owner picked Dub Tank and Trip hop, 2026-11-09)

Page: https://claude.ai/artifact/WjH9MpC2i8dnbsLZ2jmzjg. One engine and Backroom's drums; only the
controls and the groove change. Two of the four become kits 2 and 3.

| Candidate | Groove | Settings (shape) |
|---|---|---|
| Dub Tank | one-drop, 74 BPM | DRIP .7, SLAP .75 (a 300 ms echo), CRUSH .6, AMP .3, DUST .3 |
| Garage | straight eighths, 150 BPM | AMP .8, CRUSH .35, DRIP .3, SLAP .15, DUST .1 |
| Lo-fi | swung sixteenths, 82 BPM, the snare late | DUST .8, pitched 2 semitones down, CRUSH .55, DRIP .2 |
| Trip hop | heavy break, 92 BPM | CRUSH .85, DRIP .55, SLAP .3, DUST .5, pitched 1 semitone down |

**Heard (2026-11-09).** Dub Tank: "Pick it". Trip hop: "Pick it". Garage and Lo-fi: "Maybe". Kits
2 and 3 are Dub Tank and Trip hop. Garage and Lo-fi stay here as candidates for a later kit.

**The gate (docs/ENGINE_ACCEPTANCE_GATES.md).** Different effect strengths on the same kit are not
distinct genre kits. The candidates above changed only the controls and the groove. That made them
quick to compare, but they are not kits. Each picked genre becomes an authored 16-pad kit with its
own tuning, damping, backbeat roles and pad arrangement. For example, Dub Tank puts the rimshot on
the drop and adds a delay-thrown snare. Trip hop gets a lower, fatter kick and snare and a heavier
break layout. Each layout goes to the owner before it is built.

SLAP gained a time law for the dub reading: a 90 ms slapback up to 0.5, growing to 375 ms at 1.

## As built: round 1 (2026-11-09)

`Breaker.kt` (voices, controls, the source and DUST, the render), `BreakerChain.kt` (saturation,
CRUSH, the spring tank, SLAP), `BreakerPatch.kt` (with a renderer `model`, 1), `BreakerPresets.kt`
(three provisional references per voice, outside the factory bank). Registered:
- **Saving and loading:** `Patches`.
- **Velocity:** `Velocity` (both arms).
- **Round robin:** `Restrike` re-strikes the SKIN source per take.
- **The shell:** `SynthRoot` (rootless, like the other drum engines).
- **Tests:** `PatchTypes.PROTOTYPES`, plus `PatchesTest` and `PadRecipeTest` hand-built entries.

The phone picker waits for a session with an Android SDK.

Defaults follow round 0: tank B; CRUSH 0.9 on both; DRIP 0.2 on the kick and 0.6 on the snare;
DUST 0.35; SLAP 0.2; AMP 0.35.

**Measured** (`BreakerTest`, desktop JVM):

| Claim | Kick | Snare |
|---|---|---|
| Tail against hit at CRUSH .2 / .5 / .9 (DRIP .6) | -9.7 / -10.2 / -8.1 dB | -9.6 / -7.1 / -5.8 dB |
| 2 kHz after the hit against its first 50 ms, DRIP 0 → .6 | -25.8 → -10.8 dB | -17.3 → -0.9 dB |
| What the classifier hears (declared) | KICK (KICK) | PERC (SNARE) |
| Render, warm | 88 ms | 74 ms |

**The tank.** Energy per 100 ms falls at every window (0 to -35.7 dB over 1.4 s at DRIP 0.6). On
the first echo, 300 Hz peaks at 43.9 ms and 2.5 kHz at 57.7 ms: the chirp rises.

**Against the round-0 prototypes** (third-octave rms, attack 0 to 30 ms and body 30 to 300 ms):
- **The snare** (tank B, CRUSH .5, DRIP .6): 1.2 dB and 0.8 dB.
- **The kick** (CRUSH .9, DRIP .2): reported here at round 1 as 15.2 dB in the attack, with the cause
  put down to the harness's stand-in decimator. **That was wrong; see "As built: round 1b".**
  - **What happened.** The stand-in delays the prototype by about 1 ms, and the measuring windows
    were fixed in time, so the 0 to 5 ms burst landed differently in them.
  - **Lined up on their peaks,** the two attacks agree within 1 dB.
  - **The real difference** was the kick's tank and the slap time.

**Classes.** The snare files as PERC, as BACKWASH's dark snare did. Its SNARE role is nominal, for
the pad sheet and choke groups, and is declared honestly here.

Page: https://claude.ai/artifact/6yjYWB6dy8awTe12LEyCDK (the app against the picks, takes, a soft hit).

## Heard: round 1 (2026-11-09)

| Question | Owner's answer |
|---|---|
| Kick: the app against your pick. The same kick? | **Close, but not it** |
| Snare: the app joins your tank pick and your crush pick. Is it what you picked? | **Yes, both** |
| Three hits in a row from the app: still the same drum each time? | **Yes, each time** |
| A half-strength hit: a softer hand on the same drum? | **Yes** |

## As built: round 1b (2026-11-09)

**Why the kick was not it.** Each measurement here compares the kick lined up on its peak, in
third-octave bands, re the pick, at matched rms. Re-running the round-0 prototype on the app's
own source (they agree to 1.3e-7) with one thing changed at a time gives:

| Change from the pick | Tail 300 to 1400 ms | Body 30 to 300 ms |
|---|---|---|
| The harness's stand-in decimator swapped for a delay-compensated one | within 0.2 dB | within 0.1 dB |
| Tank B in place of tank A | 125 Hz -3.2, 250 Hz -3.0, 2.5 kHz -2.7 dB | 125 Hz -1.2, 2.5 kHz -1.6 dB |
| The slap at 90 ms in place of 95 ms | 63 Hz +4.9 dB | 63 Hz +0.6 dB |

So the onset was never the difference. Two settings changed between rounds were:
- **The tank.** The kick pick rang through tank A. Round 1 put every voice through tank B, the tank
  picked on the snare.
- **The slap time.** Round 1 moved it to 90 ms. On the kick, the first repeat lands on a body still
  ringing near 60 Hz. At 90 ms its lows add to that body; at 95 ms they partly cancel.

Stage by stage, each app stage given the app's previous stage agrees with the prototype within
0.4 dB everywhere, except the amp: 0.8 to 1.0 dB less at 450 Hz (Kotlin's VALVE against its Python
port).

**The change: renderer model 2.**
- The kick rings through tank A; the snare stays on tank B.
- The slap is 95 ms on both voices.
- A model-1 save keeps tank B and 90 ms on both.

**Measured, model 2 against the pick** (lined up as above):
- within 1.1 dB in every band and window;
- except 450 Hz, 0.6 to 2.2 dB lower, from the amp;
- and 10 kHz in the first 30 ms, 3 dB lower, 70 dB down.

Before, model 1 was up to 5.6 dB off in the tail. The snare moves at most 1.2 dB from model 1
(63 Hz, 30 to 300 ms).

**Tests.**
- The tank test now runs both tanks, and times each echo with zero-phase band-passes. A narrow
  band-pass's own delay is longer at 300 Hz than at 2.5 kHz. Tank B's bend hides that bias; tank A's
  lighter bend does not.
- Zero-phase first echoes:
  - tank A: 300 Hz at 39.5 ms, 2.5 kHz at 41.2 ms;
  - tank B: 300 Hz at 40.7 ms, 2.5 kHz at 60.6 ms.
- Kick tail against hit at CRUSH .2 / .5 / .9 (DRIP .6): -10.8 / -10.1 / -8.5 dB.

Page: https://claude.ai/artifact/FD1uDKgAhotz1BsV4wn1cJ (the re-fit kick, the pick and round 1's
kick; the snare at 95 and 90 ms; the re-fit kick's takes and a half-strength hit).

## Heard: round 1b (2026-11-10)

| Question | Owner's answer |
|---|---|
| Kick: the re-fit against your pick. The same kick now? | **Same kick** |
| Snare: the slap back at 95 ms. Still your snare? | **Yes** |
| The re-fit kick, hit again and at half strength: the same drum, a softer hand? | **Yes** |

The kick and the snare are accepted by ear on model 2, with their takes and their half-strength hits.

## As built: round 2 (2026-11-10)

Ten drums join the kick and snare. They go through the same rig and stay on model 2. The kick
alone uses tank A; every new drum uses tank B. Their settings are proposals for the owner's listen,
and the per-drum defaults are in `docs/BREAKER.md`.

**Sources** (written out in `Breaker.kt`):
- **Backroom's closed and open hats, cross stick and shaker,** as accepted (`BoomBapKits.parts()`).
- **Toms:** one SKIN shell (DECAY .65/.55/.50, TONE .50, PUNCH .55, DROP .15, CLICK .25) at TUNE .35,
  .60 and .82. That is about 124, 154 and 188 Hz, near a major third apart.
- **Ride:** SKIN's LONG RIDE with SIZZLE .35.
- **Tambourine:** BACKWASH's CC0 recordings (`BackwashAcoustic`): two dynamic layers, two takes each.

**Round robin.** SKIN re-seeded noise per take only on its struck voices. `Skin.render` gains
`reseedUnstruck`: asked, the hats, ride, shaker and stick re-seed their metal, beads or click for any
take above 0. It is off by default, and only BREAKER asks, so nothing else changes.

That matters for FIRED BRONZE: its hats already pass takes into SKIN. Unasked, those takes still
render identically, so its saved kits keep their sound. **Finding, not fixed here:** FIRED BRONZE's
hat round robin is therefore copies of one strike. `docs/ENGINE_ACCEPTANCE_GATES.md` says that does
not pass, and turning `reseedUnstruck` on there would fix it under a new model.

**Velocity on the unstruck drums.** SKIN does not strike the hats, ride, shaker or stick, so BREAKER
softens them itself. Measured on a 40% closed hat (the first 20 ms):

| What softened it | Energy above 6 kHz, re the full hit | Attack centroid (0 to 100 ms) |
|---|---|---|
| The house law, TONE scaled toward 0.28 of itself | +1.4 dB | +11% |
| The source low-passed, corner near 2.3 kHz | +1.8 dB | |
| The finished hit low-passed, corner near 4.8 kHz (`UNSTRUCK_SOFTEN` 0.6) | -3.4 dB | -18% (0 to 30 ms) |

Why the first two get brighter:
- **TONE does not darken a SKIN hat.** It spreads the hat's partials. Spreading them less pulls the
  top ones under DUST's filter, so the hit gets brighter.
- **The rig levels every hit into the tube.** DUST, the tube and the crush therefore set the top end,
  whatever the input.

So these four drums soften after the rig, as `Velocity.soften` does for captured audio. On the
others, velocity comes from the source:
- **The snare and toms** get darker through SKIN's contact law.
- **The kick's** 50% hit has a higher attack centroid (1332 to 1772 Hz) because it loses body. The
  owner heard it as a softer hand at round 1.
- **The tambourine's** soft recorded layer is slightly brighter (+1.5 dB above 4 kHz): lighter
  shakes are mostly jingle.

**Measured** (`BreakerTest`):
- **Tail against hit at CRUSH .2 / .5 / .9 (DRIP .6), in dB:**
  - closed hat -10.0 / -5.6 / -5.3;
  - open hat -8.4 / -3.9 / -5.3;
  - stick -9.1 / -5.5 / -7.6 with the short slap (-10.0 / -6.2 / -7.9 with the long one);
  - shaker -9.9 / -7.1 / -4.8;
  - tambourine -4.1 / -2.9 / -3.9;
  - floor tom -5.1 / -2.1 / -1.9;
  - rack tom -9.5 / -7.8 / -6.3;
  - high tom -7.4 / -5.1 / -5.1;
  - ride -4.6 / -1.9 / -4.7.

  A short drum is held down hard by the first half of the crush already. So the swell past
  halfway, which the owner heard, is asserted on the kick and snare only.
- **2 kHz after the hit, DRIP 0 to .6:** from +2.6 dB (the ride) to +54 dB (the closed hat).
- **Classes:** every new drum files as PERC through the rig, the hats included. Their declared
  classes (HAT_CLOSED, HAT_OPEN, TOM, PERC) go on the pads.
- **Render, warm:** 70 to 151 ms (the ride is the slowest).

Page: https://claude.ai/artifact/RxgQg4dtArMiGkuKMateCS. It asks:
- hats nearly dry or in the spring;
- the long or short slap on the stick;
- whether the tambourine and shaker belong;
- drippy or drier toms;
- a washy or drier ride;
- the repeat and 40% hits;
- A15: the shaker or the riser.

## Heard: round 2 (2026-11-10)

| Question | Owner's answer |
|---|---|
| Hats: crunchy and nearly dry, or into the spring? | **Nearly dry** |
| Cross stick: the long slap or the short one? | **Short slap** (SLAP .45 to .20) |
| Tambourine and shaker: do they belong in this kit? | **Both** |
| Floor, rack and high tom: a surf tom set? Drippy or drier? | **Drippy** |
| Ride: washy or drier? | **Neither: "sounds like a crash"** |
| Hit again and at 40% strength: the same drum each time, a softer hand? | **Yes** |
| Pad A15: keep the shaker, or swap in a snare riser? | **Swap in the riser** |

What follows from the answers:
- **The ride.** The long ride with its rivets sizzling, through the spring and the crush, is all
  wash and no stick. Round 3 re-voices it with a defined ping, and offers this sound as the A14
  crash.
- **The shaker** still belongs, but no longer has A15.

**Decided after round 2 (2026-11-10):**
- **The shaker** takes A16. Bump, the tank kicked on its own, moves to the genre kits, where the dub
  kit can use it.
- **The ride:** round 3 offers two candidates for the owner to pick from:
  - SKIN's ride re-voiced with a defined ping and little wash, mostly out of the spring;
  - BACKWASH's CC0 ride-bell recordings (3 layers × 3 takes) through the rig.
- **The crash (A14):** the round-2 ride sound, offered beside one brighter, longer variant through
  the amp.
- **FIRED BRONZE's hat round robin** gets fixed after BREAKER, in its own PR with a listen.

The kit, as of round 2:

| | | | |
|---|---|---|---|
| A13 Ride (round 3 picks) | A14 Crash (round 3) | A15 Snare riser (round 3) | A16 Shaker |
| A09 Floor Tom (drippy) | A10 Rack Tom | A11 High Tom | A12 Crushed Snare |
| A05 Crushed Boom | A06 Drip Snare | A07 Cross Stick (short slap) | A08 Tambourine |
| A01 Boom | A02 Bap | A03 Closed Hat | A04 Open Hat (chokes A03) |


## Heard: round 3 (2026-11-10 to 11-11)

Round 3 went through six pages in the Python harness:

| Page | What it asked | Owner's answer |
|---|---|---|
| [3](https://claude.ai/artifact/Ls4i2FqLtvTVZV4EZS7rWB) | Crash: round 2's ride through the rig, or a brighter, longer SKIN cymbal into the combo. Ride: SKIN's re-voiced with a ping, or BACKWASH's CC0 ride bell. The riser. | "Everything but the bell ride sounds very harsh": too loud next to the kit, fizzy, piercing. "Can't you replicate the recorded sounds with synth??" |
| [3b](https://claude.ai/artifact/3h2g1p8EdMDVp5cejSCEZs) | Cymbals fitted to the CC0 recordings (third-octave noise plus the ringing partials) in a beat at a kit balance; the riser tamed (half crush, a light amp, less dust, no tube) | Asked to study the cymbals dry: "I want to hear them dry." |
| [3c](https://claude.ai/artifact/JGg9DojzQtYPJRAfkMxPzS) | The fitted cymbals dry, alone and over BREAKER's kick and snare | "That is dry?? It sounds heavily processed" |
| [3d](https://claude.ai/artifact/U9mpdFNUJABbG2Q32pebNm) | A: noise in third-octave bands. B: several hundred of the recording's own resonances as decaying tones. | **Ride and crash: B, the modal synth** |
| [3e](https://claude.ai/artifact/SwxfaJWSTnsTqex5p72rmf) | The modal cymbals with two-stage fades and the measured soft-hit law, alone and in beats | "These don't sound dry when you are listed as dry." Only the cymbals were dry; the next page labels exactly what is dry. |
| [3f](https://claude.ai/artifact/3Mu5i1Vw8gA4FUTC8VoprP) | The fitted decays against "tight" ones; beats with every combination labelled | **Tight sounds dry: "These are fine."** **In a beat: BREAKER drums with dry cymbals,** "Wouldn't they be adjustable?" |

Decided on 2026-11-11:
- **A13 Ride:** the tight modal ride.
- **A14 Crash:** the tight modal crash, left to ring about 3 s. It is the one exception to the
  one-shot cap.
- **A15 Riser:** the tamed roll.
- **The cymbals are adjustable:** dry by default, with every control at 0 a true bypass, so each
  control dials the rig in.

Why the noise bands sounded processed: a cymbal is hundreds of resonances beating against each
other. Noise filtered into bands can match each band's level and decay, but it has no partials to
beat, so it sounds like a cymbal through an effect.

Why "tight": the overhead mic hears the room, which shows up as a second, slower decay on most
modes. Keeping each mode's steeper slope removes it. The owner heard the result as dry.

## As built: round 3 (2026-11-11, renderer model 3)

**The cymbals** (`BreakerCymbal`, docs/BREAKER.md "The cymbals"). Fitted by
`scripts/fit_breaker_cymbals.py` to Virtuosity Drums (CC0 1.0) at 9f04cf9a, overhead mic, the
ride's and crash's hardest and softest layers. Only the fitted numbers ship
(`resources/breaker/cymbals.json`, 31.7 KB, with `NOTICE.txt`):
- **Modes:** 400 on the ride and 418 on the crash. Each has a frequency, an amplitude (with its
  band's level correction folded in), its tight slope and its band's soft-hit tilt.
- **The script reproduces the auditioned round-3f model exactly** (every number equal).
- **The Kotlin port against the auditioned Python renders,** compared as band envelopes at 20 ms,
  80 ms, 320 ms, 1 s and 2.2 s:
  - in the dense bands (1.6 kHz up), within 1 to 2 dB;
  - in the bands with a handful of modes (300 Hz to 1.3 kHz), up to about 8 dB apart. That is the
    beating of different random phases, the same variation a fresh take makes.

**Velocity, measured.** Per band, the soft recording minus the hard one, each relative to its own
loudness over 80 to 320 ms. The tilts are real, not noise:
- **The noise check:** every band of both soft recordings sits at least 7 dB over its own floor (the
  last 2 s). At 7 dB, noise moves a band's level by under 1 dB, and the bands that look odd sit
  higher still: the crash's 12.7 and 16 kHz bands are 53 and 46 dB over their floor.
- **The law:**
  - a softer crash carries more middle (+5 to +14 dB, 315 Hz to 1.3 kHz), less presence (about
    -3 dB, 4 to 6 kHz) and slightly more air (+4 dB, 12.7 to 16 kHz);
  - a softer ride carries more low ring (+20 dB at 250 Hz, +11 at 315) and less at 1.3 to 2 kHz
    (-4 to -6 dB).
- **Not darker by centroid.** The crash's 40% attack centroid is within 0.1% of its full hit's. So
  `BreakerTest` asserts the house claim instead: at matched loudness, a 40% hit moves the spectrum's
  shape by more than 3 dB in some octave. Measured, 40% minus full, by octave from 250 Hz to 8 kHz:
  - ride: +8.9, +2.0, -2.9, -1.6, -0.6, +0.9 dB;
  - crash: +4.8, +6.4, +4.3, -0.6, -1.5, -1.0 dB.

**In the rig.** The cymbals skip the tube. On them, AMP crossfades the combo in (0 is no amp), and
every other control at 0 is already a bypass. So at the defaults (all 0), the hit is the fitted
synth with the engine's finish only: DC removal, fade and level (`BreakerTest`, within 1e-5).

**The riser:**
- **The roll:** twenty fresh snare strikes over 2.4 s, accelerating along `1-(1-p)^1.6` from about
  4 to 25 a second. Velocity rises from 30% to 85% of the pad's, so SKIN's contact law brightens
  the roll as it builds, and the strikes' gain rises from .35 to 1.
- **The rig:** it skips the tube. Its defaults are the tamed pick: DUST .2, DRIP .6, SLAP .2,
  CRUSH .5, AMP .2.
- **The rise:** after the rig, a fader push from -12 dB to 0 (`p^1.5`), because the crush evens out
  the crescendo.
- **Measured:** the first 0.5 s at -28.6 dB, the last 0.5 s of the build at -15.8 dB.

**Lengths.** The crash is 3.2 s and the riser 3.4 s. The classifier files both as LOOP (over
1.5 s), so both are declared PERC. Render, warm desktop JVM: ride 76 ms, crash 164 ms, riser
213 ms.

**Saves.** Model 3 changes the ride; model 2 keeps round 2's SKIN ride, and refuses the crash and
riser. Presets:
- cymbals: Dry, Through the rig (DUST .3, CRUSH .4, AMP .35), In the spring (DRIP .45, CRUSH .3);
- riser: Tamed, Pumping (CRUSH .9, AMP .35), Short spring (DRIP .2).

**Still open:**
- The Kotlin renders wait for the owner's confirming listen. The page
  (https://claude.ai/artifact/5CKZu3HiYQj7xqzkEDXexA) plays each pick beside the app's render, then
  takes, 40% hits, the presets and two beats. The app's riser is the pick, sample for sample. The
  cymbals match it within 1 to 2 dB in the dense bands.
- The riser is the percussion gate's authored roll in rendering only. It has not been played, saved
  in a kit or exported yet.
