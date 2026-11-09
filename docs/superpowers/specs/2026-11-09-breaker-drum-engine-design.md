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
- **The kick** (CRUSH .9, DRIP .2): 15.2 dB and 3.3 dB. The kick's first 30 ms carry 10 to 25 dB
  less from 450 Hz to 3 kHz in the app.
  - **The cause, traced stage by stage.** The source matches the prototype's to 1.6e-7, and the
    crush is the same code (2e-7 on the same input). The harness's stand-in decimator, a 63-tap
    FIR, made the prototype's saturated onset start 0.6 ms later and steeper. The crush passed
    that sharper spike, and the amp turned it into a click.
  - **What the app does instead.** It uses the house resampler and keeps the smoother onset.
  - **What next.** Disclosed, not matched. If the owner hears the click missing, the fix is a
    deliberate onset control, not a copy of the stand-in's ringing.

**Classes.** The snare files as PERC, as BACKWASH's dark snare did. Its SNARE role is nominal, for
the pad sheet and choke groups, and is declared honestly here.

Page: https://claude.ai/artifact/6yjYWB6dy8awTe12LEyCDK (the app against the picks, takes, a soft hit).
