# SIGIL: design brief and round-0 audition

Status: **round 0b (2026-11-20): four kits rendered and measured in Python; round 0's three heard by the owner as
"pretty good", KILN not yet heard.** A family of sixteen-pad drum kits written after the owner's 23 reference loops: "a drum collection that matches the vibe ... the
same spirit ... mix together elements to create cohesive kit". The owner chose the shape on 2026-11-20: "three kits,
one per tempo" (the recommendation), wear "mix it up", "the kick/snare sound is impactful", "the percs are a bit
esoteric, organic/inorganic hybrid". On hearing round 0 the owner wrote "Pretty good. Not sure I understand the rods"
(IRON's and ASH's A14 texture pads); told what they were, "I think they are fine as is", and asked for a fourth kit.
KILN (round 0b) is that kit; the page now says what each pad is when it is tapped. Nothing is in Kotlin yet. The round-0 prototype is the thing to hear first,
following the CALABASH path (prototype approved by ear, then the Kotlin port measured against it).

Listening page (private to the owner): <https://claude.ai/artifact/BnxtCiWjvFZsYXeohmaEzZ>. Four playable 4x4 pad
grids in MPC order with a velocity switch (soft .40, medium .70, hard 1.0) and four takes per pad dealt in turn, each
kit's two-bar groove looped, and the measured table below.

Prototype: [`prototype/sigil/`](../prototype/sigil/). Every number in this file is from that Python harness, not from
Kotlin. Repository truth read at `7495ca56` (2026-11-20). APIs named for the Kotlin round were grepped:
`CalabashKits.build` and `CalabashKits.VERSION` (the kit-family shape to copy), `PadHit.resolve`
(`shell/.../PadHit.kt:142`), `Restrike.take` (`synth/.../Restrike.kt:60`), `StarterKits.ALL` and
`StarterShelf.GROUPS` (the SKINS heading), the `generateCalabashKits` task in `shell/build.gradle.kts`. SIGIL is a
working name: nothing in the repository used it. The reference loops' pack labels are research text only and never
name a kit, pad or preset.

The characteristic gesture is **a sub that drops an octave under you → a snare that lands as hard as the kick → a
whisper of hats → percussion that is half wood, glass and breath and half sine, FM and crushed grain, walking A minor
over the top**.

## The references, measured (2026-11-20)

Twenty-three loops from the owner, all 24-bit 44.1 kHz stereo stems with no vocals or bass (cleaner evidence than
STAGGER's phone recordings of full mixes). Used for measurement only; never committed, sliced or shipped.
`prototype/sigil/measure.py` (numpy) writes `references.json` (numbers only) and reads our grooves with the same
probes.

| Group | Loops | What the numbers say |
|---|---|---|
| Heavy drum loops | six at 70, 80, 90 BPM, two bars each | Kicks are sub drops: 105 to 135 Hz at the hit, 36 to 60 Hz 80 ms later, 20 dB down in 70 to 150 ms; harmonics 8 to 13 dB under the fundamental, except one blown speaker (0.9 dB under, 351 ms long). Snares dark: 1.1 to 1.8 kHz centroid over the first 120 ms, two with 160 to 190 ms tails. Hats 10 to 20 dB under the kick. 50 to 70 percent of the energy in 60 to 150 Hz, under 5 percent above 3 kHz. Side 15 to 31 dB under mid: centred. Onsets 2 to 4 ms from the grid. |
| Slammed kit loop | one at 90 BPM | An 808-style sub, 126 to 38 Hz, 98 percent under 150 Hz; a 2.4 kHz crack snare; ratcheting hats; the loudest reference (-12.7 dBFS RMS, 12.2 dB crest). |
| Percussion loops | thirteen at 75, 90, 115 BPM, eight bars | Shakers, bells, clocks, wood; four are tuned (spectral flatness 0.09 to 0.23 against 0.53 to 0.74 for the shakers), low wooden hits at 90 to 200 Hz like log drums, labelled Am, F, C and E (all inside A natural minor). Wide: L/R correlation 0.11 to 0.88. Always moving: the quietest 50 ms frames sit only 15 to 20 dB under the average. Swing 50 to 57 percent. |
| Tape and vinyl | two, 85 and 100 BPM | A tambourine and a jungle percussion loop at 67 percent swing (a full triplet shuffle), the only strongly swung reference. |

Calibration note: the per-hit filing (kick, snare, hat by band over the first 120 ms) is crude and reads a dense
groove badly, as STAGGER found. The groove balance below is therefore measured directly from each pad's stem at its
written notes, not from the onset filing.

## Why it earns a shelf row (the sibling check)

| Need | Nearest on the shelf | What it cannot do |
|---|---|---|
| A sub-drop kick and a snare level with it, dark and centred | CYPHER TRAP and DRILL (808s, hat rolls); STAGGER (muffled kick, dry snare) | neither puts a log-drum and glass layer over the drums; STAGGER's kick is a muffled thud, not a drop |
| Tuned log drums, shakers, bells | CALABASH AFROBEATS (shaker, talking drum, bell); EAST AFRICA BONGOPIANO (logs, shaker, 112 BPM) | organic sources only; no synthetic half, no heavy hip-hop drums under them |
| Organic and synthetic in one hit | none found | every SIGIL percussion voice blends the two (below) |

The kit-family landing (authored tables over a small set of voices) is the one the owner chose for CALABASH,
CYPHER, STAGGER and LOOSE CRATE; an engine would cost 26 integration surfaces for what four tables and twenty
voice functions do.

## The four kits

| Kit | BPM | Swing | Print (the kit's wear) | Pads A01 to A14 | A15 | A16 |
|---|---|---|---|---|---|---|
| IRON | 74 | 52 | Blown speaker: asymmetric clipping, a cone rolling off at 4.5 kHz, a rattle when pushed | Drop Kick, Blown Kick, Dark Snare, Rim Knock, Closed Hat, Grain Shaker, Log Low (A2), Log High (E3), Glass Bell (A4), Clock Tick, Breath, Hand Drum, Water Drop, Iron Rod | Snare Drag (eighths to 32nds over 2.2 s) | groove, 2 bars |
| NEON | 90 | 51 | Slam: clean and loud, a soft knee into a ceiling | Sub Kick, Punch Kick, Crack Snare, Glitch Clap, Closed Hat, Open Hat, Grain Shaker, Clock Tick, Wood Chirp, Bell Ping (C5), Log Mid (C3), Voice Chop, Metal Tick, Zap | Hat Roll (16ths to triplet 32nds, rising) | groove, 2 bars, half-time snare, hat ratchets |
| KILN | 100 | 64 | Old sampler: 12 bits held near 26 kHz with no filter before the hold, a little drive; record warp and crackle on the groove | Thump Kick, Ghost Kick, Crunch Snare, Snap, Closed Hat, Tambourine, Bongo High, Bongo Low, Conga Low, Low Tine C (C3), Low Tine E (E3), Bell A (A4), Breath, Scrape | Bongo Roll (high and low in turn, eighths to 32nds over 2.4 s) | groove, 2 bars, heavy triplet shuffle |
| ASH | 115 | 56 | Tape: head bump at 90 Hz, warm saturation, top rolled off at 10 kHz; wow, flutter, hiss and crackle on the groove | Round Kick, Tape Snare, Rim, Tambourine, Shaker Push, Shaker Pull, Log Low (A2), Log Mid (C3), Log High (E3), Thumb Tine (A4), Glass Bell (E5), Hand Drum, Breath, Wind Rod | Shaker Rush (push and pull, eighths to 32nds) | groove, 2 bars, four on the floor, logs walking A minor |

Every pad: three velocity zones (.40, .70, 1.0) of four takes. Every tuned pad is in A minor.

## The hybrid percussion (what "organic/inorganic" means here)

| Voice | Organic half | Synthetic half |
|---|---|---|
| Log drum | a free bar's modes (1, 2.756, 5.404) struck by a mallet | a sine bending down 5 to 12 semitones into the note |
| Glass bell, bell ping | glass modes (1, 2.32, 4.25, 6.63) | FM, modulator at 3.51x, index falling |
| Thumb tine | clamped-bar modes (1, 6.27, 17.55) and a buzz | the octave crushed to 6 bits at 11 kHz |
| Clock tick | two wooden clicks a few ms apart (the escapement) | the second click sample-crushed |
| Shakers | bead grains under a push or pull envelope | a share of the grains as single held pulses (digital beads) |
| Wood chirp | a wooden knock | an upward pitch sweep riding it |
| Breath, voice chop | noise and a glottal buzz through three vowel formants (no words) | ring modulation at 290 Hz |
| Water drop | a splash | a sine whose pitch rises fast |
| Rod, wind | noise in a tuned comb that rings | an FM shimmer above it |
| Hand drum | membrane modes (Bessel ratios), a slap | a sub-drop an octave under it |
| Snap | a short burst and the fingers' resonance | part of it sample-crushed |
| Scrape | a run of tiny wooden clicks slowing down | every third click sample-crushed |
| Hats | noise | six band-limited squares (additive odd harmonics to 20 kHz, so nothing aliases) |
| Clap | three noise bursts | the fourth sample-crushed |

## Signal flow

```
kit, pad, velocity v, take t
        │  seed = seedFor("SIGIL", kit, pad, v, t)
        ▼
voice(params, v, rng)  at 4x (176.4 kHz)       ← fresh noise and a hair of tune/decay per take
        ▼
touch_tone(v)        a soft hit darker: low-pass 600·40^v Hz under v² of the dry hit
        ▼
kit print            input level .9·touch(v): a soft hit saturates the speaker or tape less
        ▼
house output stage   band limit → decimate to 44.1 kHz → DC block → 4 ms tail fade
        ▼
level                pad gain × touch(v), touch(v) = .35 + .65·v (CypherKits.touch)

A15 roll: accelerating strokes of one pad (fresh strike each), summed at 4x, printed, finished.
A16 groove: the written steps on the kit's own pads (zone by velocity, takes dealt in turn), swing on the off
sixteenths, 2 ms jitter, then the kit's bus (lighter speaker, slam, or tape with its motion).
```

## Measured (Python harness, round 0, 2026-11-20)

`prototype/sigil/gen.py` writes `report.json`; the same probes read the references.

| Measure | Owner's drum loops | IRON | NEON | ASH | KILN |
|---|---|---|---|---|---|
| Kick pitch at the hit | 105 to 186 Hz | 132 Hz | 129 | 122 | 117 |
| Kick pitch after 80 ms | 36 to 97 Hz | 55 Hz | 50 | 61 | 57 |
| Kick 20 dB down after | 69 to 351 ms | 149 ms (Blown Kick 357) | 204 | 148 | 158 |
| Kick harmonics under the note | -13.3 to -0.9 dB | -9.9 dB (Blown Kick -3.6) | -8.4 | -11.4 | -12.8 |
| Snare centroid, first 120 ms | 1,099 to 2,857 Hz | 1,710 Hz | 2,423 | 2,140 | 1,683 |
| Snare 20 dB down after | 51 to 186 ms | 199 ms | 109 | 176 | 178 |
| Loudest hat or shaker under the kick, groove | 10 to 20 dB | 19.7 dB | 17.7 | 14.0 | 17.9 |
| Snare against the kick, groove | within about 3 dB | -0.3 dB | -0.4 | -2.9 | -0.7 |
| Groove, 95 % of the energy under | 301 to 3,898 Hz | 3,144 Hz | 2,175 | 581 | 3,661 |
| Groove, share in 60 to 150 Hz | 50 to 70 % | 47 % | 55 | 69 | 51 |
| Groove, swing | 50 to 57 (one loop 67) | 51.1 | 51.1 | 55.8 | 63.3 (the swung tape loop: 67) |

- **Every pad is finite, under full scale and DC-free** (60 pads x 3 zones x 4 takes, plus the rolls).
- **Takes are fresh strikes, not copies.** The closest two takes in any zone of any pad sit 21.9 to 30.4 dB apart
  at worst per kit (a copy reads over 40).
- **A soft hit is darker, measured pad-relatively** (share of the first 120 ms above the pad's own hard-hit
  centroid; a roll is read whole). 58 of 60 pads are as dark or darker at .40 than at 1.0, most by 1 to 12 dB;
  the other two are listed under "Disclosed, not fixed". KILN's tambourine first read 0.8 dB brighter soft; a soft
  shake now rings fewer of the highest jingles (-2.7 dB, and ASH's -6.5).
- KILN's sampler holds at a whole number of internal samples (29.4 kHz at the 4x rate), close to the classic 26 kHz,
  not exact. The Kotlin port can hold at the exact rate with a fractional counter.
- **Render time:** 56 ms a hit on average in Python (all 42 hits at .70), a roll 2.1 s, a groove 1.9 s.

### Disclosed, not fixed

- ASH's Rim reads 0.9 dB brighter at .40 than at 1.0; IRON's Hand Drum (+0.1 dB) reads level. Both are quieter
  when soft, by the touch law. The Kotlin round's velocity test must hold every pad at
  no brighter.
- The pads and grooves are mono, as the house renders. The percussion references are wide (L/R correlation down to
  0.11); width cannot be carried, so density and the synthetic half carry the busyness instead.
- ASH's groove is bass-heavy for a percussion-forward kit (95 % under 560 Hz, 69 % in 60 to 150 Hz, inside the drum
  loops' range but at its edge): the four-on-floor kick and the A2 log carry it. The first listen decides.
- IRON's groove has more 1 to 3 kHz (13.8 %) than the heavy drum loops (under 5 %): the dark snare sits level with
  the kick, as asked, and its wires are where that energy is.
- The groove loop on the page stops each pass at the bar line, so the tail of the last hit is cut on repeat.

## Render budget

Python round 0: 56 ms a hit, 2.1 s a roll, 1.9 s a groove (above). The Kotlin port's budget is the house's "tens of
ms" a hit; every voice here is closed-form decaying sines, filtered noise and one comb, with no solver, so it should
port inside it. A kit's whole grid is 3 x 4 x 15 renders plus the groove, cached by the kit's version like CALABASH.

## Build rounds and gates

| Round | What | Gate |
|---|---|---|
| 0 and 0b (this) | Python prototype, four kits (KILN added in 0b), listening page | the owner's ear on the page |
| 1 | Kotlin port of the approved round (`Sigil.kt`, `SigilKits.kt` in the CALABASH shape), measured pad by pad against the prototype | the A/B distance within the takes' own spread |
| 2 | the kits on the shelf (`StarterKits.ALL`, `StarterShelf.GROUPS` under SKINS), velocity x round robin through saving, `PadHit.resolve` playback and MPC 3 export (`generateSigilKits`) | the owner's gates: velocity and round robin end to end; MPC 2 keeps each zone's first take (format limit, 2026-11-14) |
| 3 | phone and MPC | the owner's report |

## Verified / measured / proposed / heard

- **Verified:** the APIs named above (grepped at `7495ca56`); the reference files' format (24-bit 44.1 kHz stereo).
- **Measured:** every number in this file, Python harness, `prototype/sigil/report.json` and `references.json`.
- **Proposed:** the kit layouts, the grooves, every voice constant ("shape" until heard), the A minor key.
- **Heard:** nothing. Only the owner hears.

## Why it is built this way (for the owner)

1. **Measured first, then designed.** The kick and snare numbers came from your drum loops before a single voice was
   written, and the first render was wrong in measurable ways (kicks 230 to 600 ms long against your 70 to 150, a
   snare centred at 391 Hz against your 1.1 to 1.8 kHz). Measuring caught that before you had to hear it.
2. **Each kit has its own wear, and the wear is part of velocity.** A soft hit goes into IRON's speaker or ASH's tape
   more quietly, so it distorts less, as a real soft hit would. Without that, every hit was saturated the same and
   some soft hits measured brighter than hard ones.
3. **Every percussion voice is two halves.** "Organic/inorganic hybrid" is built literally: a physical model (wood,
   glass, beads, skin, breath) and a synthetic part (a pitch-swept sine, FM, crushed grains) in the same hit, so a
   pad is neither a field recording nor a drum machine.
4. **One key.** Your tuned loops are in A minor, F, C and E, which all fit A minor, so every tuned pad sits in A minor
   and the four kits agree with each other and with your loops.
5. **The risky decision** is the snare level: your drum loops put the snare as loud as the kick, so every groove does.
   If that reads as too much, it is one number per kit.

## What would have changed this brief (prompt notes)

A timestamp on the one sound you love most in a reference ("the snare at 0:04 in driving snare"), which of the
three tempos you make most, and whether the percussion should ever be the loudest thing in a kit.
