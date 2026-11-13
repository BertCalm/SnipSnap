# CYPHER: six hip-hop drum kits, engineering specification

Version 1.0 · 2026-11-12
Status: proposed engineering handoff. One mechanism prototyped in Python and measured; nothing rendered in Kotlin; nothing heard.
Repository truth: read at `4f47fdb4dab2d43a3648440528d7163afdf4508a`. APIs grepped: `GatecrashKits.build`, `Gatecrash.process`, `Gatecrash.calibrated`, `Room.process`, `FxChain.process`, `PadRecipe.render`, `BoomBapKits.parts`, `BoomBapKits.colorationFor`, `Eras.process`, `Thump.render(voice, macros, velocity)`, `Skin.render(voice, macros, velocity, take, reseedUnstruck)`, `Onset.render(voice, macros, modelVersion, velocity)`, `Terra.render(voice, macros, velocity)`, `Tines.render(voice, macros)`, `Rivet.render(voice, macros, modelVersion, velocity)`, `BreakerCymbal.render(kind, velocity, take, seconds, pitch, rate)`, `Fathom` GLIDE, `Roll.roll`, `Wobble.DIVISIONS`, `Classifier` (`TONAL_DECAY_MS = 500`, `KICK_MAX_CENTROID_HZ = 100`, `LOOP_MIN_SECONDS = 1.5`), `ArrangedPad` (`velocityTakes`, `muteGroup`, `source`, `displayName`), `StarterKits.kt:631-645`, `StarterShelf.kt:53`, `shell/build.gradle.kts:542`, `PresetTestSupport.trademarkBlocklist`.

The characteristic gesture, per kit, is in §8. The family's one line:

The characteristic gesture is **a genre's kick and snare → the space or converter that era printed them through → the hats' grid (swung, straight, or rolled) → the phrase that proves the kit is that genre**.

## 1. Summary

Six authored sixteen-pad drum kits for the six hip-hop signatures in the owner's brief: boom bap, trap, G-funk, underground cinematic boom bap, Michigan scat, and drill. A rendered kit family in the GATECRASH shape (three velocity zones of four takes per pad, fourteen hits, two phrases, source provenance on every pad, saved with the version that printed it), not a new Patch engine. The hits come from SKIN, THUMP, ONSET, TERRA, TINES, RIVET and BREAKER's cymbal as those engines stand. What is new is one sound source the roster lacks, **SLIDE**, a sub drum whose pitch holds its root and then glides to a target note, and three small things on the kit side: notes in a phrase may be late or early by an authored number of milliseconds (swing, behind-the-beat, unquantized), a note may carry a pitch offset (hat rolls that rise), and a pad may sum two engines' strikes (a hybrid kick). Everything else is authoring: which drum, which controls, which print.

What is computed: every hit, every space, every phrase. What is imagined: every number in §8 is a shape value until the owner listens.

## 2. Goals and scope

Musical goals: an MPC owner opens one of the six kits and the first kick, snare and hat already belong to that genre without a knob being turned. The phrase pads show the kit's rhythm language (the swung hats, the triplet hat roll, the behind-the-beat backbeat, the unquantized break drop, the scattered clicks, the sliding sub).

V1 includes: six kits, 16 pads each, 3 zones × 4 takes, the SLIDE source, phrase timing offsets and pitch offsets, hybrid strikes, one print per pad, the generator that proves save, playback and MPC 3 export, the listening pack, the probe tests.

V1 excludes: a SLIDE Patch engine with app knobs (Direction B, §4, a later round if the owner wants to tune the sub from the app), stereo (the house is mono: the brief's "panned wide" shakers land as two pads with different tunings), a sequencer swing setting (the kit's tempo and the phrase pads carry the feel; swing on the MPC is the MPC's job), any recorded sample, and MPC 2 round robin (its projection keeps only the first take of each zone, a known limit of every GATECRASH-shaped kit).

Trademarks: the research text below names the machines the brief names. No kit, pad or phrase name does, and `PresetTestSupport.trademarkBlocklist` catches `808`, `sp-1200`, `linn`, `dmx`, `mpc`-adjacent terms; the test in §10 runs every name through it.

## 3. Sibling check

| Need | Nearest in the roster | What it cannot do |
|---|---|---|
| A sliding sub drum (trap, drill, Michigan) | THUMP KICK: root 34 to 62 Hz, pitch falls onto the root in tens of ms (BEND), tail capped near 0.84 s so it keeps classifying as KICK (`Thump.kt:438-448`) | hold a root and then move to another note; ring past 0.85 s |
| | FATHOM DEEP: GLIDE starts up to an octave below and slides *into* the note over the first 35 % of the decay (`Fathom.kt:199-206`) | slide *away* from the root later in the note, which is the gesture; it is also a melodic engine with a 6-macro contract, not a drum |
| | The SUB pedal (an octave-down copy with DRIVE) | a pitch that moves |
| Hat rolls at 1/32, 1/32T, 1/64 with a rising pitch | `Roll.roll` (repeats a hit on `Wobble.DIVISIONS`, 1/1 to 1/16, decaying) and FILL's snare roll | 1/32 and finer, triplets, a pitch that rises through the roll |
| Boom bap converter grit and a dark room | the accepted Backroom study (`BoomBapKits`, six pads, CRUNCH → TAPE → ROOM, PCM hashes pinned) | it is six pads, not a kit, and is not on the shelf |
| Era converters | `Eras` (12-bit at 26.04 kHz with no anti-alias filter; 12-bit companded at 40 kHz; tape; phone), used only by `KitBuilder` | nothing it cannot do; it is simply not in any kit today |
| A gated or crushed room | `Gatecrash.process` (STONE and BURST), `Room.process` | nothing; both are reused as prints |
| Industrial metal hits | RIVET METAL | nothing; reused |
| A crash | `BreakerCymbal.render(CRASH, ...)` | nothing; reused |

So the sibling check says: five of the six kits are authoring over existing engines and prints, and the sixth thing, the slide, is a real gap. A kit family is the lightest landing that keeps every gesture. A SLIDE engine would be the lightest landing for the slide alone, and it is Direction B.

## 4. Three directions

**Strong: CYPHER, a GATECRASH-shaped kit family plus one private source (STACK and DISTIL, with one JUXTAPOSE).** Six kits, each a table of parts and two phrases, each pad printed through its own treatment. SLIDE lives inside the family as a source function, as GATECRASH's spaces do, and gets a Patch engine only if the owner asks to turn its knobs. Gesture clarity: high, six separate gesture lines. Novelty: the slide, the swung and unquantized phrases, and the first kits on the shelf for the genres the product's own name points at. Risk: low; every stage but SLIDE is shipped code, and SLIDE is a sine into a tanh. Cost: two synth files, two shell test files, one Gradle task, six starter rows. Wins because the brief is six sounds, not six engines, and the owner chose exactly this shape for the 1980s brief.

**Alternate A: one "era drum machine" engine with a GENRE macro (MUTATE).** A single Patch engine whose voices are KICK, SNARE, HAT and so on, and whose GENRE control walks the converter, the room and the tuning from boom bap to drill. Rejected: a genre is a set of authored choices (which drum, which room, what the hats do), not a parameter law, and every point between two genres is a kit nobody wants. It also costs the full 26-surface engine integration for what the kit side does with tables.

**Alternate B: SLIDE as a THUMP voice (`ThumpVoice.SUB`) and the six kits over it.** The slide becomes editable in the app: TUNE, DECAY, SLIDE, WHEN, DRIVE, CLICK. Gesture: the same. Novelty: the same plus an app knob. Risk: higher, because THUMP's kick path, presets, `Velocity.kt` arms, `Shuffle` slots and the SPARK family code all touch `ThumpVoice`, and the classifier files a 0.8 s sub as TONAL, which THUMP's `drumClassFor` contract and `ThumpTest` would have to accept. Cost: a round of its own. Recommended as round 4 if the owner wants to tune subs by hand; the kit-side source is written so that the same function becomes the voice's `render`.

## 5. Signal flow

```
kit, role, velocity v, take t
        │
        ▼
[Part: engine strike]──(dry hit @44.1 kHz, peak = touch(v))──▶[Print]──▶[zone gain]──▶[finish: DC, tail, fade]
   SKIN / THUMP / ONSET /              one of:                      one gain per zone,
   TERRA / TINES / RIVET /             none (dry)                   set by take 0
   BREAKER cymbal / SLIDE              FxChain rack (Backroom's CRUNCH→TAPE→ROOM)
   or a sum of two strikes             Eras.process (sp1200 / mpc60 / tape)
                                       Room.process at a measured send
                                       Gatecrash.process (STONE) at a measured send
                                       Tape / Vinyl sections alone

[Phrase]: notes (beat + offsetMs, part, weight, tuneSemis) ──▶ each note a fresh strike ──▶ summed dry ──▶ one Print
```

SLIDE's own chain, at the internal 4× rate:

```
pitch law p(t): settle (short fall onto root) → hold root → glide to root + SLIDE semitones from WHEN
        │
        ▼
sin(∫2π p dt) × amp env (1 ms attack, 12 ms hold, exp decay to −60 dB at t60)
        + click (1.5 ms raised cosine + 4 ms low-passed noise, × v^1.6)
        ▼
DRIVE: tanh(g·x)/tanh(g), g = lin(DRIVE, 1, 6) × (0.55 + 0.45 v) × take ratio
        ▼
Tide.bandLimit → Dsp.decimate → DC block → (the kit levels it)
```

Every box is a pure function of its inputs. Nothing feeds back. A kit's 192 renders are made once when the starter is created and cached as the saved kit, as GATECRASH does.

## 6. The model

### 6.1 SLIDE (literal: a sine with a pitch law, a click and a clipper)

Frames at the internal rate `r = Dsp.RATE × Dsp.OVERSAMPLE`. Root `f0 = 41.2 × 2^(lin(TUNE, 0, 12)/12)` Hz (E1 to E2). Decay `t60 = expMap(DECAY, 0.25, 1.3)` s, scaled by `(0.85 + 0.15 v)`.

```
settle(t)  = 0.35 × (0.6 + 0.4 v) × S_take × exp(−t / 0.012)              octaves; the machines' onset sweep
when       = lin(WHEN, 0.15, 0.75) × t60 + W_take                            seconds
tau        = (0.08 + 0.10 × |semis| / 12) × G_take                           seconds
semis(t)   = SLIDE_semis × (1 − exp(−max(0, t − when) / tau))                SLIDE_semis = lin(SLIDE, −12, 12)
p(t)       = f0 × 2^settle(t) × 2^(semis(t)/12)
phase[n]   = phase[n−1] + 2π p(t_n) / r
body[n]    = sin(phase[n]) × env[n]
env        = 1 ms linear attack; flat for 12 ms; then exp(−(t − 0.012) × ln(1000) / t60)
click[n]   = lin(CLICK, 0, 0.5) × v^1.6 × C_take × (raisedCosine(1.5 ms) + 0.6 × noise(4 ms, LP 4 kHz, seed(kit, role, take)))
x          = body + click
y          = tanh(g x) / tanh(g),  g = lin(DRIVE, 1, 6) × (0.55 + 0.45 v) × D_take
```

Then the house output stage. The pitch is integrated into phase, so a slide cannot click: there is no resonator to retune. The root `f0` is never moved by velocity or take; a detuned sub is a different note, and the trap and drill kits put subs on several pads at different roots on purpose.

What is not simulated: the voltage-controlled decay of the original machines, the bridged-T's own ring (the settle is a one-exponential stand-in), any speaker.

Classifier: a sub with t60 over 0.5 s reads TONAL (`Classifier.kt:159`), not KICK, and `Classifier` never sees a kit family's pads anyway: the pad's class is authored in its Part, as GATECRASH does. The test in §10 asserts the authored class and records the classifier's reading beside it rather than hiding the disagreement. No SLIDE pad exceeds 1.5 s, so none is ever a LOOP.

Takes: the ETCH lesson is that takes 0.5 to 0.8 dB apart were heard as one strike, and the fix was to deal several audible dimensions out so no two takes share a setting. SLIDE's four takes deal out five: settle depth `S`, click level `C`, drive `D`, slide start `W` (ms), glide time `G`:

| Take | S | C | D | W | G |
|---:|---:|---:|---:|---:|---:|
| 0 | 1.00 | 1.00 | 1.00 | 0 | 1.00 |
| 1 | 0.70 | 1.45 | 0.80 | +10 | 1.18 |
| 2 | 1.30 | 0.65 | 1.25 | −8 | 0.88 |
| 3 | 0.88 | 1.60 | 0.64 | +5 | 1.06 |

Measured (Python harness, §12): on a sine into a tanh, the log-spectral distance between takes is set almost entirely by the drive ratio, about 10 dB per unit of ln(ratio). The drives above sit 0.16 to 0.22 apart in ln, which puts the closest pair at 1.49 dB and the farthest at 5.5 dB, with the measured root spread across the four takes under 1 cent. Whether 1.49 dB is heard as a different strike is the owner's call; GATECRASH's weakest pair was heard and accepted.

### 6.2 Phrase timing (analogy: the feel is a table of offsets, not a model of a drummer)

A phrase note is `Note(beat, part, weight, offsetMs = 0, tuneSemis = 0)`. The note's strike starts at `beat × 60/bpm + offsetMs/1000` seconds. Three authored laws use it:

- **Swing** (BACKROOM): every off-beat sixteenth is late by `swing × (60/bpm/4)`; the kit's shape value is swing = 0.14 (about 58 % on a sampler's scale, where 50 % is straight). The owner's own accepted Backroom groove already put its off-beat hats "slightly late" and its backbeats "slightly behind the kick", so this is that groove's law written down.
- **Behind the beat** (LOWRIDER): snare and clap notes are late by 18 ms, hats by 8 ms, the kick on time. The gap is what the brief calls "unhurried".
- **Unquantized** (DUST): every note's offset is drawn once from a seeded table in −22..+22 ms (`Dsp.seedFor("CYPHER", kit, phrase)`), fixed forever by the kit version, so the "unquantized break" is the same break every time the kit is built. A phrase with offsets that change between builds would fail the determinism canary.
- **Scatter** (FLINT): a syncopated grid plus a 1/32 pickup, offsets ±6 ms on the clicks only, so the clicks sit off the kick and snare rather than on them.

`tuneSemis` is passed to the part's strike as a TUNE offset in the engine's own TUNE units (THUMP's hat TUNE spans 1.2 to 2.4 kHz over 0..1, so the implementing session converts semitones through the engine's own map; the spec says what, not the constant).

### 6.3 Hybrid strikes (JUXTAPOSE)

A part may sum two strikes, each peak-normalised, at authored weights, then the sum is normalised. Used once: LOWRIDER's kick is THUMP's round sine dive under SKIN's acoustic knock. The two share an onset, so the sum reads as one drum.

### 6.4 Prints

Each pad names one print. All are shipped code:

| Print | Call | Used by |
|---|---|---|
| Dry | none | TRAP, FLINT, most hats everywhere |
| Backroom rack | `FxChain.process` with `BoomBapKits.colorationFor(id)` (CRUNCH → TAPE → ROOM, the snare's SPIKE) | BACKROOM, every pad |
| Converter era | `Eras.process("sp1200" or "mpc60", snip, amount)` | not in V1 (see §8.1); listed because it is the literal arithmetic if the owner asks for "more crunch than Backroom" |
| Long room | `Room.process(snip, SIZE, WALLS, DISTANCE)` mixed with the dry hit at a send that gives an authored room energy (GATECRASH's `calibrated` idea, measured once per pad on take 0) | LOWRIDER snare, clap, toms |
| Dark big room | `Gatecrash.process` STONE with a Space (size .75, walls .7, crush .5, hold 140 ms, close 60 ms, roomDb −3 dB, tone 3 kHz) | DRILL snare, clap, metal |
| Tape | the TAPE section alone (DRIVE .7, AGE .75, WOBBLE .05) | DUST, every hit |
| Tape + vinyl | TAPE then VINYL (CRACKLE .5, RUMBLE .3, HISS .4) | DUST's phrases only, so the crackle is a bed under a break and not stuck to every hit |

Every print's send is energy-measured against the dry hit, never set against its peak: that is GATECRASH round 1's lesson and the reason its rooms were "loud static".

### 6.5 Velocity

Three zones at velocities .30, .65, 1, levelled to one peak per zone (the sampler owns loudness). A zone must be a different strike, not a quieter one:

- SKIN's kick, snare and toms, THUMP's kick, TERRA's drums: the engine's own strike velocity.
- SLIDE: drive, click and settle depth follow `v` (§6.1).
- THUMP's other voices, SKIN's hats and shaker, ONSET, TINES, RIVET, the cymbal: GATECRASH's `touched` law (named controls move with `v`, a one-pole contact filter falls from 18 kHz to 7 kHz). The implementing session should lift `touched`, `varied`, `jitter` and `TAKE_STEP` out of `GatecrashKits` into one internal `KitStrikes` object rather than copy them: a private helper copied between files is the roster's recurring defect.
- Prints: a softer hit is sent to its space at `touch(v)^1.5` (GATECRASH's `SEND_POWER`), so it is drier as well as duller.

## 7. Control contract

A kit family has no knobs. The contract is per pad, in the Part table, and the tests are the probes in §10. For SLIDE, kept so that Direction B can expose it unchanged:

| Control | Default | What it changes in the model | Required interaction | Test |
|---|---:|---|---|---|
| TUNE | .50 | root f0, E1 to E2 in semitones | none; takes and velocity never move it | root within 10 cents of f0 at every corner (measured: 0.4 to 2.5 cents) |
| DECAY | .60 | t60, 0.25 to 1.3 s | × (0.85 + 0.15 v) | decay to −60 dB within 15 % of t60 × 1.0 |
| SLIDE | .50 | the target interval, −12 to +12 semitones, centred (neutral .5) | tau grows with the interval | pitch before vs after the slide within 60 cents of the target (measured −481/−500, +1150/+1200, −672/−700) |
| WHEN | .50 | where the slide starts, 0.15 to 0.75 of t60 | none | the before-window pitch stays at f0 |
| DRIVE | .40 | tanh gain 1 to 6 | × (0.55 + 0.45 v) | 150 to 400 Hz share monotonic in DRIVE (measured 0.3 % at 0 to 5.5 % at 1) |
| CLICK | .50 | click level 0 to 0.5 | × v^1.6 | the first 30 ms' 2 to 8 kHz share monotonic in CLICK |

## 8. The kits

FRESH TAPE → SKINS, beside the GATECRASH kits. Every number is a shape value for round 1. Pad order follows `docs/KIT_BEST_PRACTICES.md`: A01 kick, A02 snare, A03 closed hat, A04 open hat, upward from there, A15 and A16 the phrases. Hats choke each other (group 1), the two phrases choke each other (group 2), and every SLIDE pad in a kit chokes every other SLIDE pad (group 3): a new slide stops the last one, which is how sliding subs are played.

### 8.1 CYPHER BACKROOM (boom bap), 90 BPM

The characteristic gesture is **heavy knock → papery crack → loose metallic shuffle → short dark room tail** (the accepted Backroom line).

The six accepted Backroom parts are the kit's core, byte for byte: the kit builds A01 to A06 from `BoomBapKits.parts()` through `colorationFor`, so the twelve pinned PCM24 hashes keep passing and the owner's accepted sound is what the kit opens with. BOOM_BAP.md says "factory promotion remains pending"; this kit is that promotion, completed to sixteen pads. The remaining pads use the same rack.

| Pad | Name | Class | Source | Notes |
|---|---|---|---|---|
| A01 | Pushed Knock | KICK | accepted Backroom kick | as pinned |
| A02 | Snap Paper | SNARE | accepted Backroom snare (model 2, More Snap) | as pinned |
| A03 | Pushed Tip | HAT_CLOSED | accepted | as pinned |
| A04 | Pushed Edge | HAT_OPEN | accepted | as pinned |
| A05 | Pushed Stick | PERC | accepted cross stick | as pinned |
| A06 | Pushed Seeds | PERC | accepted shaker | as pinned |
| A07 | Low Knock | KICK | SKIN KICK, TUNE .22, DECAY .40, CLICK .25, PUNCH .60 | the deeper second kick a chopped break gives you |
| A08 | Clap Layer | CLAP | THUMP CLAP SPREAD .35 DECAY .35 TONE .45 | the layer under the snare the brief names |
| A09 | Rim Layer | PERC | THUMP RIM TUNE .55 DECAY .2 | the other layer |
| A10 | Ghost Snare | SNARE | the accepted snare struck at v .35 as its own pad | the ghost the study carries as a zone, promoted to a pad for finger drummers |
| A11 | Rack Tom | TOM | SKIN TOM TUNE .55 DECAY .45 TONE .45 | |
| A12 | Floor Tom | TOM | SKIN TOM TUNE .20 DECAY .55 TONE .40 | |
| A13 | Dark Ride | PERC | SKIN RIDE TUNE .35 DECAY .5 TONE .35 | |
| A14 | Bell Hit | PERC | TINES BELL, short DECAY | the chopped-vibe stab a boom bap kit always has one of |
| A15 | Swung Hats | LOOP | phrase: one bar of A03/A04 sixteenths, swing .14, accents on the eighths, two open hats | the "head nod" written down |
| A16 | Break Drop | LOOP | phrase: two bars, kick/snare/hat break with swing .14 and the snare 6 ms behind the kick, a rim pickup | the kit playing itself |

Not used: `Eras.process("sp1200")`. The accepted rack's CRUNCH is already a converter; stacking the literal 12-bit path on it would change the sound the owner accepted. If round 1 is heard as "not crunchy enough", the era print is the next thing to try, on the new pads only.

### 8.2 CYPHER TRAP, 140 BPM

The characteristic gesture is **a short punch over a sub that holds then slides → a piercing snare → hats on a hard grid, rolling in triplets and sixty-fourths → a zap or a bell wide of the beat**.

Dry throughout. Precision is the print.

| Pad | Name | Class | Source | Notes |
|---|---|---|---|---|
| A01 | Punch | KICK | THUMP KICK TUNE .55 SWEEP .7 DECAY .25 CLICK .6 DRIVE .45 PUNCH .6 | the short kick that sits on top of the sub |
| A02 | Pierce Snare | SNARE | THUMP SNARE TUNE .72 SNAP .85 DECAY .3 TONE .7 | |
| A03 | Tick | HAT_CLOSED | THUMP HAT_CLOSED TUNE .6 METAL .55, DECAY set so measured t60 ≤ 40 ms | the ratchet rule, §12 |
| A04 | Open Tick | HAT_OPEN | THUMP HAT_OPEN TUNE .6 DECAY .5 METAL .55 | |
| A05 | Sub Root | KICK (choke 3) | SLIDE TUNE .50 DECAY .70 SLIDE .5 DRIVE .45 CLICK .6 | no slide: the note |
| A06 | Sub Low | KICK (choke 3) | SLIDE TUNE .08 (F1) DECAY .70 SLIDE .5 DRIVE .45 | a second root for a two-note line |
| A07 | Slide Down | KICK (choke 3) | SLIDE TUNE .50 DECAY .70 SLIDE .5 − 5/24 (a fourth down) WHEN .45 | |
| A08 | Slide Up | KICK (choke 3) | SLIDE TUNE .50 DECAY .70 SLIDE 1.0 (an octave up) WHEN .35 | |
| A09 | Clap | CLAP | THUMP CLAP SPREAD .3 DECAY .3 TONE .6 | |
| A10 | Rim | PERC | THUMP RIM TUNE .8 DECAY .2 | |
| A11 | Bell | PERC | THUMP COWBELL TUNE .4 DECAY .3 | "heavy cowbells" |
| A12 | Zap | PERC | THUMP SPARK TUNE .7 DECAY .25 COLOR .8 METAL .3 | "laser zaps" |
| A13 | Shaker Left | PERC | SKIN SHAKER TONE .8 DECAY .15 GRAIN .4 | the brief's wide pair, as two tunings in mono |
| A14 | Shaker Right | PERC | SKIN SHAKER TONE .6 DECAY .18 GRAIN .6 | |
| A15 | Hat Roll | LOOP | phrase: one bar of A03: beat 1 eighths, beat 2 sixteenths, beat 3 1/32 triplets, beat 4 1/64, tuneSemis rising 0 → +4 across beat 4, weight rising .5 → 1 | the signature |
| A16 | Slide Line | LOOP | phrase: one bar: A05 on 1, A07 on the and of 2, A08 before 4, A06 on 4 | |

### 8.3 CYPHER LOWRIDER (G-funk), 93 BPM

The characteristic gesture is **a round deep thump with an acoustic knock on top → a fat snare and clap that bloom into a long room → hats and a shaker behind the beat → a bell**.

| Pad | Name | Class | Source | Print |
|---|---|---|---|---|
| A01 | Hybrid Thump | KICK | THUMP KICK TUNE .30 SWEEP .35 DECAY .60 BEND .30 CLICK .2 (weight .7) + SKIN KICK TUNE .35 DECAY .30 CLICK .6 TONE .5 (weight .5) | dry |
| A02 | Fat Snare | SNARE | SKIN SNARE TUNE .45 SNAP .6 DECAY .55 RATTLE .6 PUNCH .6 | long room, +2 dB |
| A03 | Closed Hat | HAT_CLOSED | THUMP HAT_CLOSED TUNE .55 DECAY .3 METAL .4 | dry |
| A04 | Open Hat | HAT_OPEN | THUMP HAT_OPEN TUNE .55 DECAY .6 METAL .4 | dry |
| A05 | Big Clap | CLAP | THUMP CLAP SPREAD .6 DECAY .5 TONE .5 ROOM .7 | long room, +3 dB |
| A06 | Snare and Clap | SNARE | A02's strike + A05's strike (weights .8/.6) | long room, +2 dB |
| A07 | Rim | PERC | THUMP RIM TUNE .5 DECAY .25 | small room, −6 dB |
| A08 | Bell | PERC | THUMP COWBELL TUNE .5 DECAY .4 | dry |
| A09 | High Tom | TOM | SKIN TOM TUNE .70 DECAY .5 TONE .5 | long room, 0 dB |
| A10 | Low Tom | TOM | SKIN TOM TUNE .30 DECAY .6 TONE .45 | long room, 0 dB |
| A11 | Shaker | PERC | SKIN SHAKER TONE .5 DECAY .3 | dry |
| A12 | Tambourine-ish | PERC | SKIN HAT_OPEN TUNE .8 DECAY .3 TONE .7 RING .9 | dry; disclosed: a tuned hat standing in for a tambourine (BREAKER's tambourine is a recording and stays BREAKER's) |
| A13 | Sub Note | KICK (choke 3) | SLIDE TUNE .25 DECAY .60 SLIDE .5 DRIVE .25 CLICK .2 | dry; the P-funk synth bass note under the kick, no slide |
| A14 | Snap | PERC | SKIN STICK TUNE .6 DECAY .3 BODY .3 CLICK .4 | small room |
| A15 | Laid Back | LOOP | phrase: two bars, A01 on 1 and the and of 3, A06 on 2 and 4 **18 ms late**, A03 eighths **8 ms late**, A11 sixteenths, A08 on the and of 4 of bar 2 | long room on the snare notes only |
| A16 | Big Fill | LOOP | phrase: one bar, A02 sixteenths growing .4 → 1 into A05 on the next downbeat | long room |

The long room is `Room.process` SIZE .75 WALLS .8 DISTANCE .55, which is near the top of the house ROOM's 0.9 s decay cap. If the owner hears it as not drenched enough, the next step is a longer ROOM, not more send: more send at the same length is GATECRASH round 1 again.

### 8.4 CYPHER DUST (underground cinematic boom bap), 86 BPM

The characteristic gesture is **a raw tape thud → a high dry wooden crack → almost nothing else → a crash and an unquantized break that drops in**.

No compressor anywhere. The print is the TAPE section driven hard; VINYL only under the phrases.

| Pad | Name | Class | Source | Print |
|---|---|---|---|---|
| A01 | Tape Thud | KICK | SKIN KICK TUNE .30 DECAY .35 TONE .35 PUNCH .3 CLICK .4 | tape |
| A02 | Wood Crack | SNARE | SKIN SNARE TUNE .85 SNAP .8 DECAY .2 RATTLE .3 SIZZLE .2 PUNCH .3 | tape |
| A03 | Dry Tip | HAT_CLOSED | SKIN HAT_CLOSED TUNE .5 DECAY .15 TONE .35 | tape, light |
| A04 | Dry Edge | HAT_OPEN | SKIN HAT_OPEN TUNE .5 DECAY .35 TONE .35 | tape, light |
| A05 | Rim Click | PERC | SKIN STICK TUNE .7 DECAY .25 BODY .15 CLICK .6 | tape |
| A06 | Box Snare | SNARE | A02's strike + TERRA BOX (weights .8/.5) | tape; the "wooden" snare the brief describes, as two woods |
| A07 | Second Thud | KICK | SKIN KICK TUNE .40 DECAY .25 TONE .30 PUNCH .2 | tape; the slightly different kick a different break chop gives |
| A08 | Crash | PERC | `BreakerCymbal.render(CRASH, v, take, 2.2 s)` | tape |
| A09 | Dark Ride | PERC | SKIN RIDE TUNE .3 DECAY .5 TONE .3 | tape |
| A10 | Low Tom | TOM | SKIN TOM TUNE .25 DECAY .5 TONE .35 | tape |
| A11 | Shaker | PERC | SKIN SHAKER TONE .4 DECAY .3 | tape, light |
| A12 | Soft Snare | SNARE | A02's strike at v .4 as its own pad | tape |
| A13 | Bell Stab | PERC | TINES BELL, TUNE low, short | tape; the lone "sample" the drumless stretch plays over |
| A14 | Knock | PERC | TERRA BOX, lower TUNE | tape |
| A15 | Dusty Break | LOOP | phrase: one bar, A01/A02/A03 break, every note offset from the seeded −22..+22 ms table, weights scattered .55..1 | tape + vinyl |
| A16 | Crash Break | LOOP | phrase: A08 on the one, then A15's break | tape + vinyl |

### 8.5 CYPHER FLINT (Michigan scat), 120 BPM

The characteristic gesture is **a fast short kick and an early-sliding sub → a high piercing snare → wooden clicks and high rims thrown off the grid → an off-beat bell**.

Dry. The fastest kit on the shelf of the six.

| Pad | Name | Class | Source |
|---|---|---|---|
| A01 | Fast Kick | KICK | THUMP KICK TUNE .5 SWEEP .6 DECAY .22 CLICK .6 DRIVE .4 |
| A02 | High Snare | SNARE | THUMP SNARE TUNE .8 SNAP .9 DECAY .25 TONE .75 |
| A03 | Short Hat | HAT_CLOSED | THUMP HAT_CLOSED TUNE .65 METAL .5, t60 ≤ 54 ms |
| A04 | Open Hat | HAT_OPEN | THUMP HAT_OPEN TUNE .65 DECAY .4 METAL .5 |
| A05 | Early Slide | KICK (choke 3) | SLIDE TUNE .45 DECAY .50 SLIDE .5 − 5/24 WHEN .20 DRIVE .5 |
| A06 | Sub Root | KICK (choke 3) | SLIDE TUNE .45 DECAY .50 SLIDE .5 DRIVE .5 |
| A07 | High Rim | PERC | THUMP RIM TUNE .9 DECAY .18 |
| A08 | Higher Rim | PERC | THUMP RIM TUNE 1.0 DECAY .15 |
| A09 | Wood Click | PERC | SKIN STICK TUNE .9 DECAY .15 BODY 0 CLICK .8 |
| A10 | Box Click | PERC | TERRA BOX, high TUNE, short |
| A11 | Block | PERC | TINES BLOCK, short |
| A12 | Off Bell | PERC | THUMP COWBELL TUNE .7 DECAY .25 |
| A13 | Clap | CLAP | THUMP CLAP SPREAD .2 DECAY .25 TONE .7 |
| A14 | Zap | PERC | THUMP SPARK TUNE .8 DECAY .2 COLOR .9 |
| A15 | Click Scatter | LOOP | phrase: one bar of A07 to A11 on an off-balance sixteenth grid (steps 2, 3, 6, 7, 10, 13, 15 and a 1/32 pickup into 9), offsets ±6 ms, weights .5..1 |
| A16 | Slide Stack | LOOP | phrase: one bar: A06 on 1, A05 on the and of 1, A05 on 3, A06 on the and of 4 |

### 8.6 CYPHER DRILL, 142 BPM

The characteristic gesture is **a sub that lands, then slides down on the off-beat → a snare and clap in a dark big room, on the triplet grid → hats ticking under → a metal hit**.

| Pad | Name | Class | Source | Print |
|---|---|---|---|---|
| A01 | Drill Kick | KICK | THUMP KICK TUNE .45 SWEEP .6 DECAY .28 CLICK .5 DRIVE .4 | dry |
| A02 | Room Snare | SNARE | THUMP SNARE TUNE .6 SNAP .85 DECAY .3 TONE .6 | dark big room |
| A03 | Tick | HAT_CLOSED | THUMP HAT_CLOSED TUNE .6 METAL .6, t60 ≤ 54 ms | dry |
| A04 | Open Tick | HAT_OPEN | THUMP HAT_OPEN TUNE .6 DECAY .45 METAL .6 | dry |
| A05 | Sub | KICK (choke 3) | SLIDE TUNE .50 DECAY .80 SLIDE .5 DRIVE .4 | dry |
| A06 | Slide Down | KICK (choke 3) | SLIDE TUNE .50 DECAY .80 SLIDE .5 − 7/24 (a fifth) WHEN .60 | dry |
| A07 | Slide Up | KICK (choke 3) | SLIDE TUNE .50 DECAY .80 SLIDE 1.0 WHEN .35 | dry |
| A08 | Slide Late | KICK (choke 3) | SLIDE TUNE .50 DECAY .90 SLIDE .5 − 5/24 WHEN .75 | dry; the slide that lands on the next beat |
| A09 | Room Clap | CLAP | THUMP CLAP SPREAD .4 DECAY .35 TONE .55 | dark big room |
| A10 | Rim | PERC | THUMP RIM TUNE .9 DECAY .2 | dry |
| A11 | Metal Hit | PERC | RIVET METAL METAL .7 DRIVE .6 SIZE .5 WALLS .7 | dark big room, −6 dB |
| A12 | Metal Low | PERC | RIVET METAL TUNE .3 METAL .8 DRIVE .7 | dark big room, −6 dB |
| A13 | Grit Snare | SNARE | RIVET SNARE STRIKE .7 METAL .5 DRIVE .6 | dry; the industrial alternate |
| A14 | Shaker | PERC | SKIN SHAKER TONE .7 DECAY .15 | dry |
| A15 | Triplet Snare | LOOP | phrase: one bar: A02 on 2 and the and of 3, 1/8-triplet pickups on A09 before each, A03 eighths | dark big room on the snare and clap |
| A16 | Slide Call | LOOP | phrase: one bar: A05 on 1, A06 on the and of 2, A08 on 3, A07 before 4 | dry |

## 9. Render budget

A kit is 192 renders made once when the starter is created, then saved; that is seconds, not the single-hit budget, as GATECRASH discloses (7.7 to 10.7 s per kit on this cloud JVM, warm). The new costs on top of GATECRASH's per-hit 22 to 105 ms:

- SLIDE: a sine, an envelope and a tanh at 176.4 kHz for at most 1.35 s (238 k frames) plus the house output stage. Proposed under 10 ms warm in Kotlin; not measured (the Python harness says nothing about Kotlin speed).
- `Room.process` at SIZE .75: whatever ROOM costs today on a 1 s hit; GATECRASH's STONE spaces already pay it.
- Phrases: one strike per note (12 to 24 notes) plus one print. GATECRASH's phrases took 206 to 256 ms.

Cache: the per-pad measured send (`calibrated`) is kept in a map keyed by (kit, role), as GATECRASH does. Nothing else needs caching.

## 10. Probes and tests (`CypherTest`, `CypherKitTest`)

The GATECRASH set, applied to six kits, plus the new claims:

1. Every pad renders the same bytes twice (one-shots and phrases).
2. Every pad is finite, bounded, centred (|mean| < 0.005) and ends in silence.
3. Every zone is levelled to one peak; no take runs far from it.
4. **The slide reads.** On every SLIDE pad with SLIDE ≠ .5, pitch confidence ≥ 0.9 in the before-window and the after-window, and the measured move is within 60 cents of the target. On SLIDE = .5 pads the move is under 10 cents.
5. **The root never moves.** Across a SLIDE pad's twelve renders the before-window root spans under 5 cents.
6. **DRIVE puts the drum where a phone plays.** The 150 to 400 Hz share at v 1 is at least 2 % on every SLIDE pad (measured 2.7 to 4.2 % at the defaults).
7. **A softer SLIDE hit is a different strike.** Log-spectral distance soft vs hard ≥ 2 dB and the 150 to 400 Hz share lower at the soft zone (measured 3.33 dB and 1.5 % vs 2.8 %).
8. **Every zone holds four different strikes.** Closest pair per pad reported; SLIDE's is expected near 1.5 dB and is asserted ≥ 1 dB.
9. **The ratchet rule.** On TRAP's and DRILL's closed hat, measured t60 ≤ 1.5 × the shortest period in that kit's hat phrase (40 ms at 1/64, 54 ms at 1/32T at 140 BPM). This is the probe that stops a hat roll turning into a buzz (§12).
10. **A phrase's timing is what was authored.** The Gatecrash attack key counts one attack per note on each phrase, and the first attack of a late note lands within 1 ms of `beat × 60/bpm + offsetMs`.
11. **Every print carries its authored energy** against the dry hit within 0.3 dB and none exceeds +4 dB (GATECRASH's bound).
12. **The six kits are six kits.** Each pair differs in tempo, in at least six pad sources, and in the print of A02; the hat phrases differ in grid.
13. The authored class of every pad is carried; the classifier's reading of each SLIDE pad is recorded (expected TONAL) and the test asserts the authored class, not the classifier's.
14. Chokes: hats group 1, phrases group 2, SLIDE pads group 3, nothing else.
15. No pad, kit or phrase name matches `trademarkBlocklist`.
16. `CypherKitTest`: build, save, reload equal, every saved slice equals its render through `PadHit.resolve` over two full cycles of each zone, MPC 3 export read back (velocity ranges, base slices, cycle lengths, frame windows). MPC 2 is disclosed as first-take-only, as for GATECRASH.

The audition pack (`:shell:generateCypherKits`, output `testkit/cypher-kits/`, gitignored): per kit, every pad's hard take, its dry comparison, all twelve strikes soft to hard, and a two-bar groove dealt the way the app deals it; for the SLIDE pads, the same drum at DRIVE 0 / .5 / 1 so the owner can hear the phone-band trade; a page built like `scripts/gatecrash_page/build_page.py`.

## 11. Integration (file names that grep)

| Row | File | Status |
|---|---|---|
| SLIDE source, phrase model, hybrid strike, shared strike helpers | `synth/src/main/kotlin/com/snipsnap/synth/Cypher.kt` (new); `KitStrikes` lifted from `GatecrashKits.kt:259-300` | to write |
| The six kits | `synth/src/main/kotlin/com/snipsnap/synth/CypherKits.kt` (new), shape of `GatecrashKits.kt` | to write |
| Backroom core | `BoomBapKits.parts()` and `colorationFor` reused, unchanged | verified present |
| Starters | six `Starter(...)` rows in `shell/src/main/kotlin/com/snipsnap/shell/StarterKits.kt` beside lines 631 to 645 | to write |
| Shelf | ids in the SKINS group, `shell/src/main/kotlin/com/snipsnap/shell/StarterShelf.kt:53` | to write |
| Tests | `synth/src/test/kotlin/com/snipsnap/synth/CypherTest.kt`, `shell/src/test/kotlin/com/snipsnap/shell/CypherKitTest.kt` | to write |
| Generator | `shell/src/test/kotlin/com/snipsnap/shell/CypherKitGenerator.kt`; `generateCypherKits` in `shell/build.gradle.kts` beside line 542; `/testkit/cypher-kits/` in `.gitignore` | to write |
| Docs | `docs/CYPHER.md`; a row in `docs/SYNTH_ROADMAP.md`; a CYPHER section in `docs/ENGINE_ACCEPTANCE_GATES.md`; "As built" appended here | to write |
| Not touched | `Patches.kt`, `Velocity.kt`, `Presets.kt`, the app picker: a kit family registers no Patch | by design (Direction B would) |

Test gate: `./gradlew --no-daemon test`, judged by exit code.

## 12. What was measured (Python harness, `prototype/cypher/cypher_proto.py`, report in `prototype/cypher/report.json`)

Not Kotlin. Not heard.

**SLIDE reads as a pitch move.** Root 58.27 Hz (TUNE .5, A#1). Autocorrelation pitch and confidence before and after the slide:

| Shape | Target (cents) | Before Hz (conf) | After Hz (conf) | Measured (cents) | Decay to −60 dB |
|---|---:|---|---|---:|---:|
| straight | 0 | 58.33 (.99) | 58.35 (.98) | +0.4 | 0.76 s |
| down a fourth | −500 | 58.33 (.99) | 44.18 (.97) | −481 | 0.76 s |
| up an octave | +1200 | 58.33 (.99) | 113.33 (1.00) | +1150 | 0.77 s |
| drill, down a fifth, late | −700 | 58.33 (1.00) | 39.57 (.98) | −672 | 0.97 s |
| trap short punch | 0 | 58.36 (.99) | 58.31 (.96) | −1.5 | 0.49 s |

The shortfall of 20 to 50 cents is the after-window starting at three glide time constants, where an exponential approach is 95 % of the way; the note arrives. Centroid 61 to 62 Hz on all five; DC under 5e−5; no partial above 0.95 Nyquist; alias proxy 0.0 in the raw oversampled signal; energy never grows.

**DRIVE is the phone-band knob.** Share of energy between 150 and 400 Hz at DRIVE 0 / .25 / .5 / .75 / 1: 0.3 / 1.8 / 3.4 / 4.6 / 5.5 %, log-spectral distance from DRIVE 0: 0 / 3.6 / 6.3 / 8.6 / 10.4 dB. The SUB pedal's lesson holds: a clean sub is 99 % under 100 Hz.

**Velocity changes character.** At matched peak, v .3 / .65 / 1: 150 to 400 Hz share 1.5 / 2.2 / 2.8 %, distance from the hard hit 3.33 / 1.81 / 0 dB.

**Takes.** Before dealing: closest pair 0.19 dB (the click seed alone, which would have failed by ear as ETCH's did). After dealing five dimensions: pairs 2.23, 2.59, 3.33, 4.50, 1.49, 5.53 dB; roots 58.32 to 58.34 Hz (0.6 cents).

**The hat ratchet at 140 BPM.** A plain band-passed noise hat; the dip between consecutive hits on a 2 ms RMS envelope, and attacks counted by the GATECRASH-style key (3 ms lookback, 4.6 dB lead):

| Hat t60 | 1/32 (53.6 ms) | 1/32T (35.7 ms) | 1/64 (26.8 ms) |
|---:|---:|---:|---:|
| 40 ms | −76 dB | −50 dB | −36 dB |
| 80 ms | −39 dB | −26 dB | −18 dB |
| 150 ms | −22 dB | −15 dB | −10 dB |

Every cell counted 8 of 8 attacks. The rule the kits take from it: a hat whose t60 is at most 1.5 × the period keeps at least a 35 dB dip between hits; at 2.2 to 3 × the dip is 18 to 26 dB and the roll starts to read as a buzz; at 4 × and more it is a buzz. So TRAP's closed hat (a 1/64 roll) needs t60 ≤ 40 ms and DRILL's and FLINT's (1/32T) ≤ 54 ms. Whether 18 dB "starts to buzz" is a listening claim and is marked as one.

## 13. Sonic risks

1. **The slide is heard as a wobble, not a note change.** Probe 4 (the after-window confidence and cents). If heard that way, lengthen `tau` and move WHEN earlier.
2. **The sub is weight a phone does not play.** Probe 6. The DRIVE 0 / .5 / 1 clips in the pack make the trade audible.
3. **The hat roll buzzes.** Probe 9.
4. **The long room drowns the G-funk snare** (GATECRASH round 1 again). Probe 11 caps the energy; the pack's less-room and more-room trials on A02 let the owner move it.
5. **The unquantized break sounds like a mistake, not a feel.** No probe can settle it; the pack plays the quantized version of the same bar beside it.
6. **Six kits that sound like one kit with six tempos.** Probe 12 forces source differences, but only the owner's ear closes it; the gate says "different effect strengths on the same kit are not sufficient evidence".
7. **The Backroom promotion changes the accepted sound.** The twelve pinned hashes guard it; any new pad's rack is the accepted rack, not a retune of it.

## 14. Research references

The owner's brief (2026-11-12) is the research text and names the machines: the analog drum machine architecture behind trap's sub (its bridged-T sine with a decaying pitch and a click), the 12-bit samplers behind boom bap's grit (the repo's own `Eras` KDoc records 12-bit at 26.04 kHz with no anti-alias filter, and 12-bit companded at 40 kHz, as documented signal-path arithmetic, not circuit cloning), the early 1980s machines behind G-funk's round kicks, and the breaks the brief lists. No product control set is quoted; no sample of any record or machine is used; none of those names appears in any kit, pad or phrase name. GATECRASH's spec (`2026-11-10-gatecrash-gated-drum-kits.md`) is the shape this family copies, and its four listening rounds are the family's prior.

## 15. Verified / proposed / heard ledger

| Claim | Status | Evidence |
|---|---|---|
| GATECRASH is a rendered kit family with three zones of four takes, phrases, authored classes and chokes, registered in `StarterKits` and `StarterShelf`, proven by a generator | verified | `GatecrashKits.kt`, `StarterKits.kt:631-645`, `StarterShelf.kt:53`, `build.gradle.kts:542`, read at `4f47fdb` |
| THUMP's kick cannot hold a root then slide, and its tail is capped near 0.84 s | verified | `Thump.kt:425-448` |
| FATHOM's GLIDE slides into the note from below over the first 35 % of t60 | verified | `Fathom.kt:199-206` |
| `Roll` stops at 1/16 | verified | `Wobble.kt:20`, `Roll.kt` |
| Backroom's six parts and rack are accepted and hash-pinned | verified | `docs/BOOM_BAP.md`, `BoomBapKits.kt` |
| `Eras.process` exists and is used only by `KitBuilder` | verified | grep, `KitBuilder.kt:915` |
| `Gatecrash.process`, `Room.process`, `BreakerCymbal.render`, `Rivet.render`, `Terra.render`, `Tines.render` are public with the signatures in the header | verified | grep |
| The classifier reads a sub with t60 > 0.5 s as TONAL | verified | `Classifier.kt:159` |
| SLIDE's pitch move reads with confidence ≥ 0.96 and lands within 50 cents | measured | §12, Python |
| DRIVE moves the 150 to 400 Hz share from 0.3 % to 5.5 % | measured | §12, Python |
| Four SLIDE takes are at least 1.49 dB apart with the root fixed | measured | §12, Python |
| A hat with t60 ≤ 1.5 × the ratchet period keeps a ≥ 35 dB dip | measured | §12, Python, a plain noise hat |
| SLIDE renders under 10 ms warm in Kotlin | proposed | none; measure in the generator |
| Every control value in §8 | proposed (shape) | none; round 1 listen |
| Swing .14, 18 ms behind the beat, ±22 ms unquantized, ±6 ms scatter | proposed (shape) | the accepted Backroom groove's "slightly late" is the only prior |
| Each kit is heard as its genre | heard | round 1b (TRAP, DRILL) and round 2 (the other four), both approved 2026-11-12 |
| 1.49 dB between takes is heard as two strikes | heard, not objected to | no take was called a copy in rounds 1, 1b or 2 |

## Build rounds and gates

- **R0 (this document):** the design, the SLIDE and ratchet prototype, the numbers above. Gate: the owner's read.
- **R1:** `Cypher.kt` and `CypherKits.kt` with TRAP and DRILL only (the two kits that need SLIDE), `CypherTest` probes 1 to 9, 13 to 15, the generator and the pack. Gate: the owner hears the slide, the hat roll and the dark room.
- **R2:** BACKROOM (the promotion), LOWRIDER, DUST, FLINT; probes 10 to 12; `CypherKitTest`; starters and shelf; docs. Gate: the owner hears six kits.
- **R3:** the listen-and-fix round GATECRASH needed four of.
- **R4 (optional):** Direction B, SLIDE as a THUMP voice, if the owner wants the sub's knobs in the app.

## Why it is built this way

- **It is kits, not an engine,** because the brief is six sounds and the gate is "multiple distinct genre kits on the shelf". A GENRE knob would make in-between kits nobody asked for. The owner picked this shape for the 1980s brief and approved it.
- **SLIDE is the only new DSP,** and it is a sine with a pitch law into a clipper. The slide is written into the phase, so there is nothing to retune and nothing to click. If it were removed, trap, drill and Michigan would lose the thing that makes them those genres, and no engine in the roster can stand in.
- **The feel is a table of milliseconds, not a sequencer setting.** `render` receives no tempo, swing lives on the MPC, so the only place a kit can carry its groove is a phrase pad, with each note's lateness written down and seeded once. Remove the offsets and every kit's phrase is the same straight grid.
- **The Backroom kit reuses the accepted sound byte for byte** rather than re-authoring it, because the owner already accepted it and twelve hashes pin it. The ten new pads get the same rack.
- **The one risky decision is DRIVE on the sub.** A clean sub is correct and inaudible on a phone; a driven sub is audible and less pure. The prototype puts the number on the trade (0.3 % to 5.5 % of the drum between 150 and 400 Hz) and the pack plays all three so the owner can choose, as they did for the SUB pedal.

## As built (round 1, 2026-11-12)

The owner answered the two questions on 2026-11-12: SLIDE becomes an editable THUMP voice (Direction B, taken
now rather than at round 4), and TRAP and DRILL are built first. What changed against the design above:

- **SLIDE is `ThumpVoice.SUB`.** Six macros as in §7, no PUNCH. `Thump.render` gained a trailing `take`
  parameter that only SUB reads; `Velocity.touchedRender` and `Restrike` treat SUB as they treat KICK and
  SKIN's struck voices, so a SUB pad re-strikes through the app's robin. The app's two voice switches gained
  arms (class KICK, chip "SUB"); the picker lists voices from `ThumpVoice.entries`, so SUB appears. No factory
  presets (`PatchTypes.PROTOTYPE_VOICES`), and `Desample` keeps its legacy grid without SUB, like SPARK.
- **DECAY's top is 1.2 s, not 1.3,** and a render is `t60 × 1.15 + 0.042` s: at 1.3 s the longest render was
  1.75 s and the classifier filed it as LOOP. The longest is now 1.42 s.
- **The takes are the prototype's table**, in `Thump.SUB_TAKES`. Measured in Kotlin on the fourth-down sub:
  waveform shape distances 0.18 to 0.69 between takes, roots 58.33 to 58.45 Hz by autocorrelation (the
  detector's own resolution at 58 Hz is about 5 cents).
- **The kit helpers are shared.** `KitStrikes` (in `Cypher.kt`) holds `touched`, `varied`, `jitter`,
  `TAKE_STEP`, the engine wrappers, `layered`, `struck` and `finish`; `GatecrashKits` delegates to them through
  thin wrappers so its call sites and arithmetic are unchanged. `GatecrashTest` still passes.
- **A strike takes a pitch offset** (`Strike = (velocity, take, tuneSemis) -> FloatArray`) rather than a Note
  carrying a TUNE delta: the helper knows the voice's TUNE span in octaves (hats 0.954, rim 0.737).
- **Phrases choke inside themselves.** `CypherKits.Phrase.choke` names the parts that stop each other; a
  choked note ends where the next choked note starts, over a 5 ms fade. Without it the sub line was mud.
- **DRILL's snare is ONSET's, not THUMP's.** Every THUMP snare shares one wire-noise stream, so TRAP's and
  DRILL's measured as one drum in different clothes (shape distance under 0.5). ONSET's snare in the drill
  room is a different drum.
- **DRILL's subs sit at TUNE .62 (63 Hz), not .50:** 58 Hz down a fifth is 39 Hz, under the pitch probe's
  40 Hz floor and under most phones. TRAP's low sub (TUNE .08, 43 Hz) is driven harder (DRIVE .62) to reach
  the phone band.
- **Probe 10 is two probes.** GATECRASH's attack key cannot hear a sub landing on a ringing sub (it wants
  the level to lead its held peak by 1.7, and a sub is already at full level), so the key counts attacks on
  the DRILL snare bar only; on every phrase, every note is checked as an onset (louder after than before).
  The 1/64 roll is held by the ratchet probe (a 20 dB dip between hits, measured on the render).
- **Pitch on the kit's subs is read from zero crossings**, not autocorrelation: the detector loses roots
  near its 40 Hz floor, and a sub is a sine.

Measured (Kotlin, this cloud JVM): see [CYPHER](../../CYPHER.md). A warm SUB render is about 23 ms for a
0.74 s drum. TRAP built in 7.5 s and DRILL in 12.0 s in the generator. MP3 lead-in on the listening page moves
its onsets by a few milliseconds; the kit's timing is sample-accurate.

Not built: BACKROOM, LOWRIDER, DUST, FLINT (round 2), the riser, SFZ and DecentSampler exports. Not heard:
everything. The [listening page](https://claude.ai/artifact/ARC6e7nxteRTuAmiocntHG) is private to the owner.

## Round 2 heard (2026-11-12)

"All four pass." BACKROOM, LOWRIDER, DUST and FLINT are approved at kit version 3, from the listening page's
MP3 renders. With round 1b that closes the listening gate for all six kits; the R3 listen-and-fix round was
not needed. Preserve version 3 as the accepted baseline. Still open: playback on a phone or an MPC, which
only the owner's devices can close, and MPC 2's one-take-per-zone projection, which is a format limit. The
owner also kept SCUFF (a dry Michigan kit merged the same day) beside CYPHER FLINT: "I'll keep all kits."

## Round 1b heard, round 2 (2026-11-12)

"Ok, sounds good. Make sure we clear the approval gates." TRAP and DRILL are approved at kit version 2.
Round 2 builds the other four kits and closes what software can close:

- **BACKROOM, LOWRIDER, DUST, FLINT** as in §8, with these changes against the design: BACKROOM's accepted six
  are read from `BoomBapKits.parts()` and their hard first take is the accepted recipe at its own level into
  its rack (`KitStrikes.struck`'s `exact`), because the rack's converter and tape answer to level and a
  renormalised strike was not the accepted sound. The ten new pads use the same rack. LOWRIDER's long room
  is the GATECRASH stage with no crush, open 900 ms: a room that rings out, energy-measured like every space,
  rather than `Room.process` mixed by hand. Its snare is tuned well under BACKROOM's (both SKIN snares; at
  the design's tuning they measured as one drum). DUST's crash is BREAKER's fitted cymbal at 1.4 s with a
  half-second fade, under the loop floor. FLINT's kick is ONSET's, not THUMP's (a third THUMP kick measured
  as DRILL's). A print (`Part.print`, `Phrase.print`) applies a rack, tape or vinyl after the space.
- **The feel laws** (`Cypher.swung`, `behind`, `humanised`): swing .14 of a sixteenth on the off-beat
  sixteenths, 18 and 8 ms behind, ±22 ms seeded for the unquantized break, ±6 ms for the click scatter.
- **SKIN takes on the new kits** (`KitStrikes.skinStruck`): a drummer's force spread from `Restrike.landing`
  plus a small move of decay, contact and sag (kick, tom) or snap and sizzle (snare), at twice the usual
  step. A soft kick's re-strikes through BACKROOM's dark rack were 0.003 apart without it. GATECRASH keeps
  `skin` as approved.
- **SUB's length is its DECAY.** The design scaled t60 by velocity; a softer take then shortened the drum,
  which the robin's windows showed (`ThumpSubRobinTest`). Velocity moves drive, click and settle only.
- **SUB presets:** the eight sub recipes of the approved kits, named TRAP ROOT, TRAP LOW, TRAP DOWN, TRAP UP,
  DRILL ROOT, DRILL DOWN, DRILL UP, DRILL LATE. SUB leaves `PROTOTYPE_VOICES`.
- **Exports:** SFZ and DecentSampler beside MPC 3; MPC 2 is not written.
- `CypherKits.VERSION` is 3. Not heard: the four new kits.

## Round 1 heard, round 1b (2026-11-12)

The owner heard round 1 and said: "The triple snare thing is messy." That is DRILL's A15. Two causes, both
mine: the phrase sent every part through the dark room, so each hat eighth re-keyed the gate and the room
stood open for the whole bar (the GATECRASH key is keyed by the dry signal, and a hat is an attack); and the
two clap pickups sat 70 ms apart before each snare, inside the clap's own decay, so they read as one smeared
hit. Round 1b: `Phrase.printed` names the parts a phrase's space prints; the drill bar prints the snare and
clap only, keeps the kick and hats dry, and takes the drill shape (snare on three and the and of four, one
eighth-triplet clap pickup before each). The space's send is calibrated on the printed notes, since the
others never reach it. `CypherKits.VERSION` is 2. Not heard.
