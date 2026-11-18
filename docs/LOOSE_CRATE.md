# LOOSE CRATE

Four drum kits after one producer's loose, dusty, sub-heavy beat-tape drums. LOOSE CRATE is a working name.
No kit, pad or preset name may use the producer's name, his aliases, album titles or label: `LooseCrateKitsTest`
checks every name against `PresetTestSupport.trademarkBlocklist` and against those terms.

The characteristic gesture is **kick hums under the bar → snare lands loose, flammed or bouncing → hats drag
behind → a dark, rumbling bed with the top cut off**.

## Status

**Round 1 heard; round 2a waiting on a choice.** The four kits are on FRESH TAPE's SKINS shelf
(`LooseCrateKits`, `StarterKits`), with their velocity and round-robin grids proven in software through save, reload,
playback and MPC 3, SFZ and DecentSampler export. The owner heard round 1 (2026-11-18): "Sounds pretty good", then
"It's a bit drum machine like". Round 2a (below) tries two fixes on HUMMIN's groove before any kit changes. Nothing
has been played on a phone or an MPC.

Listening pages (private to the owner's account): [round 1](https://claude.ai/artifact/WgYApFNJqJTjEiv6XGgnn6),
the four kits read back from their MPC 3 export; [round 0](https://claude.ai/artifact/DaKKJX6p18cMbitmFKDVQn),
the candidates and the first groove.

Repository truth: read at `afb0fa98` (2026-11-18). APIs used were grepped: `KitStrikes` (in `Cypher.kt`),
`Cypher.Note`, `Cypher.humanised`, `Cypher.phraseRaw`, `CypherKits.touch`, `CypherKits.render`, `Skin.render`,
`Thump.SUB_LOW_HZ`, `Terra` SLAP macros, `Crunch`, `Tape`, `Vinyl`, `Echo` TIME (60 to 350 ms).

## The landing: a kit family, not an engine

The owner chose this landing on 2026-11-18. CYPHER DUST and SOOT already cover dusty boom bap. What the
references show and those kits do not:

1. **A flam or bouncing snare on one pad**: several fresh strikes inside 40 to 60 ms. ECHO's shortest time
   is 60 ms, too long for a 10 to 13 ms bounce.
2. **The humming kick at the centre of a kit**, with the mix tilted so the kick band carries most of the energy.
3. **A loose hand that varies on every hit**, rather than one voice uniformly late (CYPHER's "Laid Back").

All three are kit choices plus one strike helper, so this lands in the CYPHER shape: sixteen-pad kits from
THUMP, SKIN and TERRA, printed through the existing rack. The owner approved four kits:

| Kit (working name) | Tempo | Built toward | Identity |
|---|---|---|---|
| HUMMIN | 88 | that kick hummin, Ill yameanery, flam cheeta | humming 50 Hz kick, flam snare, dark dragging hats, heavy rumble |
| CLOTH | 82 | the cloth, oh tee tea | dry and close, clean gaps, kick dropping 118 → 57 Hz, deep bouncing snare |
| HAZE | 84 (half time of 168) | obscura, mirage, konducta | wide, 16 kHz ceiling, washing cymbal swell, metallic ringing percussion |
| RITUAL | 86 / 115 | ritual, rasta pasta, mutations, slap it up | short sub thud, high slap snare, sparse, dub-leaning |

## The references, measured

Twelve files from the owner (nine loops, three snare one-shots). They are used for measurement only: never
committed, sliced, resampled or shipped. Numbers come from the Python harness, not Kotlin.

Every loop is bar-exact at the tempo in its file name (88 BPM is 10.91 s, four bars). The three at 158 to 169
BPM read as half time.

| Reference | BPM | Kick f0 attack → body | Kick to −20 dB | 95% of energy below | Ceiling |
|---|---|---|---|---|---|
| that kick hummin | 88 | 61 → 54 Hz | 555 ms | 221 Hz | full |
| Ill yameanery | 88 | 51 → 48 Hz | 485 ms | 334 Hz | full |
| the cloth | 82 | 118 → 57 Hz | 182 ms | 3.0 kHz | full |
| ritual | 86 | 55 Hz | 75 ms | 161 Hz | ~16 kHz |
| rasta pasta | 115 | 92 → 46 Hz | 82 ms | 199 Hz | full |
| mutations | 158 (79) | 62 Hz | 94 ms | 872 Hz | ~16 kHz |
| obscura | 164 (82) | 111 Hz | 260 ms | 5.9 kHz | ~16 kHz |
| konducta | 169 (84.5) | 53 → 45 Hz | 61 ms | 210 Hz | full |
| mirage | 128 | 56 → 71 Hz | 89 ms | 6.0 kHz | ~16 kHz |

| Snare | Body | Strikes inside the hit | Centroid | Reading |
|---|---|---|---|---|
| flam cheeta | 262 Hz | ~0, 9, 25, 42 ms | 2,040 Hz | a flam |
| oh tee tea | 196 Hz | taps every 10 to 13 ms, falling about 2.5 dB each | 1,391 Hz | a bounce, long wires |
| slap it up | 396 Hz | one | 1,544 Hz | a high slap, loudest from 0.5 to 2 kHz |

The 88 BPM loops measure a crest factor of 10.8 to 12.6 dB. Their timing spreads 15 to 50 ms off the grid;
that number comes from a crude band-split onset detector, so read it as "not quantized".

## Round 0 (as built)

`LooseCrateRound0Generator` (test source set) renders every candidate as a hard first take, a dry copy, four
takes and three velocity zones, plus the HUMMIN groove and three CYPHER DUST pads for comparison:

```sh
./gradlew --no-daemon :synth:generateLooseCrateRound0          # → testkit/loose-crate-round0/ (gitignored)
./gradlew --no-daemon :synth:test --tests '*LooseCrateRound0Test'
```

**The print** (`crate`): CRUNCH (12-bit, 32 kHz hold, TONE .95, GRIT .05) → TAPE (WOBBLE .08, DRIVE .15,
AGE .60) → VINYL (CRACKLE .20, HISS .25, RUMBLE .30 on a hit, .70 on the groove). It runs in the order the
records were made: sampled into a converter, bounced to tape, with the record's rumble underneath.

**The one new strike law** (`multi`): one pad that is several fresh strikes of the same drum at authored
millisecond offsets and weights. It makes both the flam (0, 7, 23, 40 ms) and the bounce (six taps, 9.5 to
56 ms). If round 0 keeps it, it moves into `KitStrikes` for round 1.

Measured on the final renders (Python probes on the Kotlin output):

| Candidate | Target | Measured |
|---|---|---|
| Hum kick, layered (SKIN knock over THUMP SUB) | 485 to 555 ms to −20 dB; 54 Hz | 493 ms; 56.5 → 53.8 Hz |
| Hum kick, SUB alone | same | 521 ms; 57.2 → 53.8 Hz |
| Thud kick (SKIN) | 61 to 94 ms; 45 to 55 Hz body | 76 ms; 63.3 → 50.5 Hz |
| Flam snare (SKIN × 4) | 262 Hz; centroid 2,040 Hz | 250 Hz (SKIN's top is 260); 2,239 Hz |
| Bounce snare (SKIN × 6) | 196 Hz; centroid 1,391 Hz | 198 Hz; 1,949 Hz |
| Slap snare (TERRA SLAP over SKIN wires) | 396 Hz; body −20 dB by 25 ms | body peak 335 Hz; 19 ms |
| HUMMIN groove, 88 BPM | crest 10.8 to 12.6 dB; 95% below 221 to 334 Hz | 10.4 dB; 161 Hz |

Velocity changes the sound, not only the level, on every candidate except one. The magnitude-weighted onset
centroid rises from soft to hard (the hat measures 6,225 → 6,886 → 7,021 Hz). The exception is the thud kick,
whose soft hit reads slightly brighter (335 → 312 → 309 Hz). The likely cause is the 12-bit converter's
fixed-size grain, which is relatively louder on a quiet hit; this is disclosed, not fixed. Every take is a
fresh strike.

What changed while building, measured:
- **The print clipped.** CRUNCH and TAPE match their input's peak and then clamp at full scale. A summed flam
  or groove peaks above 1, and the first printed groove measured a crest factor of 2.6 dB. The print now
  scales anything over 0.9 down first. `thePrintedGrooveKeepsItsPeaksAndIsBarExact` fails without that
  change (measured: crest 5.7 dB).
- **Drive squeezed the snares.** At TAPE DRIVE .40 the printed snares measured 7 dB more RMS than the
  references at the same peak. DRIVE is now .15 and GRIT .05.
- **The print lengthens a low tail by about half** (550 → 773 ms to −20 dB), so the SUB is aimed short
  (DECAY .85).
- **No SKIN snare reaches the slap's 0.5 to 2 kHz band** (13 dB short). TERRA's hand slap does. Its BOWL
  must be off: at the default .5 it rang for 0.9 s and put 95 percent of the energy under 215 Hz.
- **The hat's velocity was volume only** (4,788 / 4,752 / 4,778 Hz). It now has its own two-pole contact
  falling to 3 kHz on a soft hit, and `aSoftHatIsDarkerNotOnlyQuieter` holds that.

Disclosed, not fixed:
- The groove's loop point steps by 0.005 of full scale, against the house's 0.001 seam limit.
- A printed one-shot carries VINYL's bed to the end of its render buffer. The slap pad is 0.9 s long for
  that reason.
- TAPE's 5 ms base delay puts 5 ms of silence before every printed pad. CYPHER DUST's printed pads have it too.

## Round 1 (as built)

`LooseCrateKits` holds the four kits in CYPHER's shape. Each has fourteen hits, then A15 a roll that speeds up into
a landing (strikes at beats × (i / count)^0.6 over two beats) and A16 a four-bar groove. Every pad has three
velocity zones (0.30, 0.65, 1) of four takes, each zone levelled to one peak (0.92); the sampler turns
velocity into gain. Chokes: hats (1), the roll and the groove (2), the hum kick and the subs (3).

```sh
./gradlew --no-daemon :synth:test --tests '*LooseCrateKitsTest' :shell:test --tests '*LooseCrateKitTest'
./gradlew --no-daemon :shell:generateLooseCrateKits      # → testkit/loose-crate-kits/ (gitignored)
```

| Kit | Tempo | Print | Pads |
|---|---|---|---|
| HUMMIN | 88 | crate (round 0's) | Hum Kick, Flam Snare, Dark Hat, Dark Open Hat, Hum Sub, Bounce Snare, Thud Kick, Ghost Snare, Rim, Shaker, Dark Ride, Low Tom, Knock, Slap Snare; Flam Roll; Hummin Groove |
| CLOTH | 82 | close: 14-bit, full rate, light tape, no record under the hits | Drop Kick, Bounce Snare, Tight Hat, Open Hat, Short Kick, Tight Flam, Cross Stick, Ghost Snare, Low Tom, High Tom, Shaker, Ride, Swish, Slap; Bounce Roll; Cloth Groove |
| HAZE | 84 | haze: coarser, the tape wobbling most | Haze Kick (ONSET), Lazy Flam, Haze Hat, Haze Open Hat, Wash, Haze Ride, Bell, Metal, Low Sub, High Tom, Low Tom, Shaker, Rim, Ghost Flam; Ghost Roll; Haze Groove |
| RITUAL | 86 | dub: the darkest top, the deepest rumble | Thud Kick, Slap Snare, Ritual Hat, Ritual Open Hat, Sub Drop, Rimshot, Low Tom, Doum, Hand Slap, Shaker, Low Bell, Knock, Ghost Slap, High Thud; Slap Roll; Ritual Groove |

HUMMIN's pads 1, 2, 3, 5, 6, 7 and 14 are round 0's strikes through round 0's print: the round-0 generator now
renders them from `LooseCrateKits`, and its 33 files are byte-identical to the ones the owner heard.

Measured on the exported files (Python probes; the groove's references in brackets):

| Groove | Crest factor | 95% of energy below | Loop-point step (largest step before it) |
|---|---|---|---|
| HUMMIN | 10.9 dB (10.8 to 12.6) | 161 Hz (221 to 334) | 0.0029 (0.034) |
| CLOTH | 18.8 dB (15.6) | 5.7 kHz (3.0 k) | 0.0003 (0.0014) |
| HAZE | 15.6 dB (11.4 to 17.8) | 4.3 kHz (0.2 to 6.0 k) | 0.0035 (0.0058) |
| RITUAL | 16.0 dB (12.8 to 13.1) | 1.2 kHz (161 to 872) | 0.0009 (0.0028) |

What changed while building, measured:
- **The loop seam.** Round 0 printed the groove once and stepped 0.005 at the wrap. The groove is now
  printed twice end to end and the second pass kept, its last 20 ms crossfaded into the first pass, which runs
  straight on into the kept pass's first frame. `every groove is bar-exact and closes on itself` holds it.
- **The print brightened soft hits.** Fed in at its zone's level, a soft hit's 12-bit grain was relatively
  louder, and every low or woody drum's soft zone measured brighter than its hard one (the thud kick 264 →
  244 → 244 Hz onset centroid, soft to hard; CLOTH's near-clean converter showed none). Every pad now enters
  its print at 0.9. `the print never makes a softer hit brighter` holds it; reverting the level alone fails
  it on HUMMIN's thud kick (soft 351 Hz, hard 311 Hz). Hard hits already entered at 0.9, so round 0 did not move.
- **A soft doum's takes were nearly alike** (0.0015 apart, under the family's 0.005): TERRA's re-seeded
  strike barely changes a quiet hit, so a take also nudges FORCE and DECAY.
- **HAZE's first kick was RITUAL's drum** (a SKIN kick 0.18 from the thud): it is ONSET's now.

Disclosed, not fixed:
- **Build time.** A kit builds in 31 to 85 seconds on this machine (`:shell:generateLooseCrateKits`), and the
  phone builds a starter when it is picked. Not measured on a phone.
- **Small velocity changes.** The rims, the cross stick and the toms change by 1 to 3 percent in onset
  centroid from soft to hard. They pass the shape test (a different strike) but may sound only quieter.
- **RITUAL is brighter than its references** (95 percent of its energy under 1.2 kHz, against 161 to 872 Hz):
  its short thud carries less sub than the reference loops' kicks.
- A printed one-shot carries its print's VINYL bed to the end of its render buffer, and pads' beds add when
  they overlap; CLOTH's hits carry none.

## Round 2a: less drum machine (HUMMIN's groove only, not yet heard)

"It's a bit drum machine like" (the owner, 2026-11-18). Measured on the two 88 BPM references against round 1's
HUMMIN groove (Python probes; the onset detector behind the kick spread is rough):

| Measure | References | Round 1 | A: the room | B: the acoustic kit |
|---|---|---|---|---|
| Quietest tenth of 10 ms frames, under the loudest | 18 to 23 dB | 45 dB | 27 dB | 19 dB |
| Cymbal band (6 to 14 kHz) at its quietest | 26 to 29 dB down | 49 | 31 | 24 |
| 95 percent of the energy below | 221 to 334 Hz | 161 Hz | 307 Hz | 624 Hz |
| Loud against soft kicks | 9 to 11 dB | 1.6 dB | 4.5 dB | 5.5 dB |

Round 1 summed isolated dry hits into silence, every kick at about one strength. A sampled break is one kit in one
room on shared microphones: the room rings, the wires buzz when the kick lands, the cymbals wash, a compressor
breathes with the whole kit, and the drummer's kicks differ.

`LooseCrateRound2Generator` (`:synth:generateLooseCrateRound2a`) renders the same notes and feel two ways:
- **A, the room:** round 1's own sounds; the kick weights curved (w^3) for a drummer's dynamics; SKIN snare wires
  under every kick; a soft dark ride on the quarters; the open hat ringing unchoked; hats at 0.7 of round 1; then
  ROOM (SIZE .22, WALLS .55, DISTANCE .60), COMPRESSOR (AMOUNT .45, ATTACK .80, RECOVER .45) and a -5 dB high shelf
  at 1.2 kHz (the references are mixed dark), then round 1's crate print.
- **B, the acoustic kit:** A with EMBER's snare (the same flam, at 0.75), hats and ride; the hum kick stays.

What moved while tuning, measured: a first pass put the kit through the room with round 1's levels and measured 5
to 8 dB too much above 1 kHz against the references and the kicks squeezed to 3 dB of spread; the ride, the wires
and the hats came down, the compressor eased and slowed, and the shelf went on. B keeps 6 dB more snare body
between 200 Hz and 1 kHz than the references.

[Round 2a listening page](https://claude.ai/artifact/7AJ7r6HtckC5H31c8qmR1f) (private to the owner). The choice
of A, B or neither decides round 2: the treatment carried to all four kits, and whether single pads carry a
little room too.

## Gates

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: drum kits |
| Drums: multiple distinct genre kits on the shelf | Built, not heard: four kits under SKINS. `LooseCrateKitsTest` holds every pair to at least six different pad names and a different kick and main snare, dry (shape distance over 0.5), and the four prints differ |
| Percussion: roll/riser | Built, not heard: one accelerating roll per kit (A15) |
| Velocity and round robin: rendering | Pass in software: every soft zone is a different strike from the hard one and never brighter; every zone holds four different strikes (`LooseCrateKitsTest`) |
| Velocity and round robin: saving, playback, export | Pass in software: `LooseCrateKitTest` and `:shell:generateLooseCrateKits` (all four kits, 192 slices each: saved PCM, reload, the pad resolver walking the takes, MPC 3 track, SFZ, DecentSampler). MPC 2 is not written (the owner's format limit). Not played on a phone or an MPC |

## Ledger

- **Verified**: the APIs above, by grep at `afb0fa98`; `LooseCrateRound0Test` (4), `LooseCrateKitsTest` (11) and
  `LooseCrateKitTest` (3) pass; `:shell:generateLooseCrateKits` verifies all four kits.
- **Measured**: every number in the round-0 tables, Python probes on the Kotlin renders and on the reference
  files.
- **Proposed**: every setting outside round 0's candidates, the four prints, the rolls and grooves.
- **Heard**: round 0's two hum kicks ("Both sound great", 2026-11-18). Nothing else.
