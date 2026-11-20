# LOOSE CRATE

Four drum kits after one producer's loose, dusty, sub-heavy beat-tape drums. LOOSE CRATE is a working name.
No kit, pad or preset name may use the producer's name, his aliases, album titles or label: `LooseCrateKitsTest`
checks every name against `PresetTestSupport.trademarkBlocklist` and against those terms.

The characteristic gesture is **kick hums under the bar → snare lands loose, flammed or bouncing → hats drag
behind → a dark, rumbling bed with the top cut off**.

## Status

**Complete (2026-11-18).** Sound approved ("It sounds good to me") and the owner's device gates passed ("All good
on my gates"): the four kits play on the owner's phone and load and cycle on the MPC. They are on FRESH TAPE's SKINS
shelf (`LooseCrateKits` version 3, `StarterKits`), their velocity and round-robin grids proven in software through
save, reload, playback and MPC 3, SFZ and DecentSampler export. How the owner got there: round 1 ("Sounds pretty
good", then "It's a bit drum machine like"); round 2a, choosing A; round 2 ("Better. Crate cloth could use a bit of
warmth or compression in the bass"); round 3, CLOTH's bass ("It sounds good to me"). The optional extra crack on
CLOTH's snare was not asked for and is left as built. The phone's build time was not reported separately (45 to 107 s
on the build machine).

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

[Round 2a listening page](https://claude.ai/artifact/7AJ7r6HtckC5H31c8qmR1f) (private to the owner). The owner
chose **A** (2026-11-18). The other two round-2a questions (how much room; room on single pads) went unanswered:
round 2 sized each kit's room to its own references and left the single hits dry, since pads sequenced on a
sampler would stack their rooms.

## Round 2 (as built)

`LooseCrateKits` version 2. Every groove (A16) and roll (A15) is played as one kit in one room
(`LooseCrateKits.Room`, `roomOf`, `played`); the fourteen single hits are unchanged. A groove's notes are played with
a drummer's kick dynamics (weight w becomes w^kickCurve), SKIN snare wires under every kick, hats at a room level
(the open hat ringing on each bar's last eighth where set), and a soft ride where set; then the whole kit goes through
ROOM, COMPRESSOR and a high shelf at 1.2 kHz before the kit's print. RITUAL's one drop also gained varying kicks
(1, 0.80, 0.95, 0.75 by bar) and a soft pickup into bars two and four.

| Kit | Room (SIZE, WALLS, DISTANCE) | Compressor (AMOUNT) | Shelf | Ride | Hats |
|---|---|---|---|---|---|
| HUMMIN | .22, .55, .60 (round 2a's A exactly) | .45 | -5 dB | Dark Ride .10 / .06 | x0.7, open hat rings |
| CLOTH | .10, .35, .40 | .30 | -2 dB | none | x0.85 |
| HAZE | .30, .55, .60 | .30 | -5 dB | none (its crash washes) | x0.6, open hat rings |
| RITUAL | .28, .50, .55 | .40 | -4 dB | none | x0.8 |

Measured on the exported grooves (Python probes; references in brackets):

| Kit | Quietest tenth under the loudest | Cymbal band at its quietest | Loop-point step (largest before it) |
|---|---|---|---|
| HUMMIN | 27.0 dB (18 to 23) | 31.1 dB (26 to 29) | 0.0044 (0.052) |
| CLOTH | 49.3 dB (45) | 55.2 dB (62) | 0.0001 (0.0018) |
| HAZE | 29.0 dB (22 to 31) | 22.8 dB (19 to 38) | 0.0009 (0.032) |
| RITUAL | 41.6 dB (35 to 44) | 41.5 dB (41 to 59) | 0.0045 (0.015) |

What moved while tuning, measured:
- HAZE with a ride on top of its crash measured its quietest frames 12 dB under the loudest (the references 22 to
  31): the ride went, the hats came down to 0.6 and the compressor eased (29 dB).
- CLOTH's mic distance moved its quietest frames under 1 dB from 57. The cloth reference's floor is the record's low
  rumble (its top sits 62 dB down), so CLOTH's groove print carries more rumble and less hiss (49 dB, top 55).
- RITUAL's one drop put every kick at 1: the new kick test measured 2.3 dB of spread. Its kicks now vary.

Tests: `every groove keeps sounding between hits as its references do` (a band around each kit's references; taking
HUMMIN's room away alone fails it at 41.8 dB) and `every groove's kicks land at least 8 dB apart from softest to
hardest`. Round 2a's generator now renders A from the kit itself: its five files match the heard ones to within one
24-bit step (A's before-print copy is levelled differently, 0.0009).

Disclosed, not fixed: a kit now builds in 45 to 107 seconds on this machine (round 1: 31 to 85), since every
groove and roll goes through a room. Not measured on a phone.

[Round 2 listening page](https://claude.ai/artifact/V66JmBdrMha3TvJLuLhdwh) (private to the owner).

## Round 3: CLOTH's bass (as built, not yet heard)

"Better. Crate cloth could use a bit of warmth or compression in the bass" (the owner, 2026-11-18). Measured against
the cloth reference (Python probes): round 2's CLOTH groove had a spikier bass, its crest (peak against RMS under
200 Hz) 21.4 dB against the reference's 17.9, and its kicks were nearly sine, the Drop Kick 40.6 dB down between 200
and 500 Hz and 62 dB down above 500 Hz.

CLOTH's print (`LooseCrateKits.CLOSE`, kit version 3) gains two stages after the converter, so every CLOTH pad,
groove and roll carries them:
- **Warmth:** VALVE with its speaker off (DRIVE .50, SAG .15, TONE .40, CAB 0): the harmonics a sine-like kick lacks.
  At DRIVE .25 the kick's 200 to 500 Hz moved under 1 dB; at .50 the Drop Kick's rose 6.6 dB (-34.0).
- **Compression in the bass:** `squeezeBass`, COMPRESSOR (AMOUNT .80, ATTACK .35, RECOVER .55) on the band under
  180 Hz alone, split by two one-poles so the rest passes untouched.

Measured in Kotlin on the groove through each print (`CLOTH's print presses its bass into body and leaves the rest
alone`): bass crest 21.1 dB through round 2's print, 19.2 with the tube, 17.9 with the tube and the squeeze, the
reference's 17.9. The test holds both: the full print at least 2 dB under round 2's, and the squeeze below the tube
alone.

Disclosed, not asked for: the groove's 0.5 to 2 kHz still sits near -22 dB against the reference's -12.4. That band
is the reference snare's crack and beater knock, not the bass; CLOTH's bounce snare is a deep body and wires with
little between. Left for the owner to call.

## Expansion: BLUE, SPEED, RAGA (round 0; SPEED and RAGA kept)

Asked which adjacent kits would extend the collection, the owner chose all three proposed (2026-11-18): BLUE (jazz
breaks off a record), SPEED (sped-up breaks) and RAGA (Indian-record percussion). Nearest siblings and what differs:
EMBER's Soul Jazz and After Hours (a clean club) for BLUE; the rack's SPEED section (whole semitones, per pad) for
SPEED; TERRA PUNJAB (the same hand drums, clean) for RAGA. No reference loops were given for these.

`LooseCrateExpansionRound0Generator` (`:synth:generateLooseCrateExpansionRound0`) renders one four-bar groove and
the key hits of each, through LOOSE CRATE's room (`LooseCrateKits.Room`) and crate print:
- **BLUE (90 BPM):** EMBER's felt kick, brush tap, brush sweep, ride and foot hat (DISTANCE 0, WARMTH 0: the room
  and the print supply both); the ride swung in triplets, brushes on two and four, a boom-bap kick push.
- **SPEED (108 BPM):** HUMMIN's approved strikes and played notes at 80 BPM in HUMMIN's room, then the loop read as
  if recorded at 1.35 times the rate (45 rpm over 33 1/3) and resampled (`Resampler`), three copies end to end with
  the middle kept, then the crate print. 529,200 frames at 80 become 392,000, four bars at 108 exactly (checked in the
  generator).
- **RAGA (94 BPM):** TERRA's dayan (open "na", closed "ti"), bayan ("ge", bending) and dholak over LOOSE CRATE's
  thud kick and slap snare.

Measured (Python probes): SPEED's hum kick rises from 53.8 to 73.3 Hz and shortens from 1.13 to 0.84 s; RAGA's bayan
bends from 103 to 90 Hz; BLUE's groove never goes quiet (quietest tenth 12.5 dB under the loudest), from the ride,
which rings over a second six times a bar; easing the brush sweep from .35 to .20 moved it under 1 dB.

**Round 0c, BLUE's brush sweep.** "The brush snare is too harsh and overbearing" (the owner, 2026-11-18). Round 0b
read it as the brush tap and softened that; the owner corrected it: "It's the brush sweep that was harsh and
overbearing". Round 0c returns the tap to round 0 exactly (byte-identical) and fixes the sweep. Round 0 had swept
faster with the mic close and no warmth (SPEED .45, DISTANCE 0, WARMTH 0); its loudest band was 2 to 6 kHz. The sweep
now takes EMBER's approved "Slow Sweep" exactly (SPEED .1, DISTANCE .4, WARMTH .35), at weight .12 and .10, and has its
own fader (.35), since a note's level follows its weight only gently (`touch`, never under 0.35 of full): the slow
sweep at weight .12 alone measured 3.7 dB under the rest of the kit, louder than round 0's 5.2, because it swishes
longer. Measured on dry stems on one scale, the sweep against the rest of the kit: 12.8 dB down (round 0: 5.2) and the
2 to 6 kHz band 7.8 dB down (round 0: level with it, 0.1). The groove's quietest moments sit 15 dB under its loudest:
the ride. Lesson for the kits: a part that should sit far back needs a fader, not only a soft weight.

**Round 0d, BLUE's sweep rattles.** "It sounds scraping rather than a gently rattling sweep" (the owner,
2026-11-19). EMBER's sweep is shaped bristle noise with no snare wires in it, so however soft it stays a scrape. Round
0d builds the sweep from the wires: a train of very soft taps of LOOSE CRATE's snare wires (`LooseCrateKits.wires` at
.45), one every 18 to 40 ms over 1.76 s, swelling and fading with the brush's circle (a sine arch to the 1.3, each tap
varied), over EMBER's sweep made darker (two one-poles at 2.5 kHz) and 8 dB under the wires. The tap and the fader are
unchanged. Measured on the sweep alone: the 15 to 60 Hz flicker of its loudness (the rattle) at -1.6 dB of its envelope
(round 0c -6.6, round 0 -8.4); its centre 2,784 Hz (round 0c 3,219, round 0 3,533); against the rest of the kit 13.0 dB
down (round 0c 12.8).

**Round 0e, a shaker in place of the sweep.** The owner heard round 0d as "just awful" and chose, of four
directions offered, a shaker in the sweep's place (2026-11-19). The lesson: a brush sweep is friction, thousands of
bristles on a coated head, and three rounds of stand-ins for it (shaped noise, then a train of wire taps) came out as
hiss or scrape; tuning was not going to fix the method. EMBER's shaker is fitted to a CC0 recording of a large
shaker; BLUE plays it on swung eighths (.35 on the beat, .55 on the swung "and", the bar's last lifted to .63), TUNE
.30, DAMP .45, at fader .35. The brush tap and everything else are unchanged. Measured on dry stems on one scale: the
shaker 14.8 dB under the rest of the kit, and 4.7 dB under it from 2 to 6 kHz (a shaker lives there: its centre is
5.2 kHz; TUNE from .40 to .30 moved that only 140 Hz). On the printed groove, 2 to 6 kHz is -13.8 dB of the whole,
level with round 0's harsh sweep (-14.0; round 0c -15.2). A shaker's brightness comes in short strokes, not a
continuous scrape, but given the history a softer groove (fader .25, about 3 dB down; 2 to 6 kHz at -14.7) is
rendered beside it so the owner can choose the level by ear.

**Round 0f, BLUE's ride shimmers.** "The ride needs to be brighter and more of a light shimmery wash after the
attack" (the owner, 2026-11-19). EMBER's flat ride is bright dry (its wash centred near 6.7 kHz), but BLUE's room
shelf (-4 dB over 1.2 kHz) and the crate print's tape head (AGE .60: a one-pole near 6 kHz) took the shimmer, and
through them its wash centred at 1.1 kHz. Both shape the whole kit and the tape is the family's, so the ride is
treated on its own channel: TUNE .65 and DAMP .10 (were .40 and .40), a +6 dB high shelf at 5 kHz, and its own band
over 5 kHz added twice over, faded in across the first 0.15 s so the strike is unchanged. A compressor on the ride
(the house COMPRESSOR, fast attack) was tried and rejected: it matches the input's peak, so it lifted the wash over
the strike and the ride lost its attack. Measured through the room and the print (no vinyl): the band over 5 kHz in
the wash, 0.2 to 0.6 s after the strike, from 12.6 to 7.4 dB under the attack; the wash's centre from 1.1 to 3.4 kHz.
On the printed groove, 6 to 12 kHz is up 2.7 dB and over 12 kHz up 6 dB, while 2 to 6 kHz (where the sweep was harsh)
moved 0.4 dB.

**Round 0g, a steady deck for BLUE.** "It's too noisy" (the owner, 2026-11-19), asked which noise: the ride, hissy.
Measured stage by stage, as the share of the ride's 5 to 14 kHz wash (0.2 to 0.6 s) in spectral peaks 8 dB over the
local floor: dry, 100% (all ringing modes; BUZZ's rivets made no difference); through the room, 100%; through the
converter alone, 95%; through the converter and tape, 73% for round 0e's ride and 57% for round 0f's. Turning off the
tape's drive changed nothing; turning off its wobble (WOBBLE .08, about 0.1% of pitch) gave 96% and 94%. Wow and
flutter smear a cymbal's hundreds of high modes into swish, and round 0f's brighter ride exposed it. BLUE now prints
through the crate print with WOBBLE 0 (`BLUE_PRINT` in the generator); the converter, the tape's drive and head, and
the vinyl are unchanged, and `LooseCrateKits.CRATE` and the four approved kits keep their wobble. Measured on the
printed ride hit (with vinyl): 94% (round 0f 55%, round 0e 71%), centred at 4.0 kHz (round 0f 4.4, round 0e 2.4).
Lesson for the kits: test a bright cymbal through the tape's wobble, not only its head.

**Round 0h, a ride like a record's.** "The ride should have less shimmer. It should be like these" (the owner,
2026-11-19), with two recordings of jazz rides: used only to measure, never committed or sliced. Measured from each
hit's peak, as the share of five bands (under 1 kHz, 1 to 3, 3 to 6, 6 to 12, over 12) at the strike (the first 40 ms),
the wash (0.2 to 0.6 s) and the tail (0.6 to 1.2 s): the references' wash holds 21 and 30 dB under its whole over 12
kHz, round 0g's 15. They live between 3 and 12 kHz, a stick ping centred near 8 to 9 kHz over a wash centred at 3.5 and
6.2 kHz, falling 20 dB in 0.52 and 1.32 s. A search through BLUE's print found the print does not add low end to a
cymbal; its converter filter and tape head take the top, so the lows' share rises (the converter's bit depth, grit and
the tape's drive barely moved it). So the ride is shaped before the print: EMBER's flat ride at TUNE .40, DAMP .30,
BUZZ 0; a 500 Hz low cut; +12 dB at 8 kHz (Q .8) against the tape head; the strike 3 dB softer, back over 50 ms; and
two Butterworth low-passes at 12 kHz (24 dB an octave). Round 0f's shelf and lift are gone; round 0g's steady tape
stays. Scored as the root mean square of those fifteen shares and the wash's and tail's levels against the strike,
the printed hit sits 5.3 dB from the references' mean (round 0g 7.0; each reference sits 3.5 from it). Its wash: over
12 kHz -23.2 dB, 6 to 12 kHz -3.2, centred at 5.5 kHz, 98% in ringing peaks; it falls 20 dB in 0.48 s. What is left is
the print's: vinyl hiss over 12 kHz in the tail and the strike's lows (under 1 kHz at -10.4 against -19.5 and -12.7).
On the printed groove, over 12 kHz falls from -29.2 to -35.9 dB of the whole.

**Round 0i, three grooves on the same sounds.** "Maybe the groove isn't good. It just isn't clicking" (the owner,
2026-11-19). Round 0's groove mixed two grids: the ride's spang-a-lang, the shaker and the brush ghosts on triplet
swing, the kick on straight sixteenths (beats 1.75 and 2.5, landing 56 ms after and 111 ms before the triplet notes
beside them at 90 BPM). And every note moved by its own random draw of up to 12 ms over its part's offset, so no two
parts locked. Three grooves, each on one grid with a fixed feel per part (brushes 16 ms behind, the ride 4 ms ahead,
the foot hat 6 and the shaker 4 behind, the kick on the grid) and a random spread of 4 ms: A, pocket (round 0's idea,
the kick on the triplet swing at 1, the swung "and" of 2 and of 3, the ghosts off the kick, the shaker on the back
strokes only); B, break (a hip-hop break: sixteenths swung at 58% as an MPC's swing, the ride on eighths, the kick
"boom, b-boom" at 1, the "a" of 1 and the "and" of 3, no shaker); C, A without the shaker. Shape values, for the
owner to choose by ear; round 0h's groove stays the kit's until then.

**Round 0j, the converter at its full rate.** "The ride is too noisy" (the owner, 2026-11-20, of round 0h's ride).
Round 0g measured only how tonal the 5 to 14 kHz band was; this time the floor between the ride's ringing peaks,
200 Hz to 16 kHz (a 150 Hz running median), at each stage of the print. The converter alone took the wash (0.2 to 0.6 s)
from 30.0 to 15.5 dB of peaks over the floor, its floor from 2 to 8 kHz from -43.6 to -29.3 dB of the strike; the tape
alone and the vinyl alone each cost a few dB. Not bit depth: 16 bits changed nothing. The converter holds its input at
about 32 kHz (RATE .22), not a divisor of 44.1 kHz, so a cymbal's top folds back as tones in tune with nothing, and
round 0h's +12 dB at 8 kHz fed it. A gentler hold (RATE .05) did not help. BLUE's print now runs the converter at RATE 0
(its 12 bits, the tape's drive and head and the vinyl stay; the approved kits keep CRATE whole): the printed ride's wash
is 27.6 dB over its floor (round 0h 14.7), its band shape against the references unchanged (5.4 dB from their mean, round
0h 5.3), over 12 kHz at -28.6 dB (the references -29.6 and -20.7). The references themselves measure 17.6 and 10.0: a
real cymbal's fizz is dense but in tune with the cymbal, and reads as cymbal; fold-back reads as hiss. The kick, brush
tap and foot hat move under 1.5 dB in any band; the shaker sheds 3.5 dB of fold-back under 1 kHz. Lesson for the kits:
a bright cymbal through a sample-rate reduction that does not divide the output rate needs its noise measured, not
only its spectrum.

**Round 0k, a stick hit and a quick ring.** "No. It's not right. Tone down the sizzle, it should be a bright stick hit
to the ride (not bell) with a light and shimmery ring that fades quickly" (the owner, 2026-11-20). Round 0h had
softened the strike to lift the wash toward the references; the owner wants the opposite balance. Each phrase as a
measure on the printed hit, the round-0h references (used only to measure) as a guide: the stick, the first 40 ms'
centre (references 8.6 and 7.7 kHz); not bell, 1 to 3 kHz's share of those 40 ms (references -25 and -15 dB); light,
the ring's level from 0.2 to 0.6 s against the stick; fades quickly, the time to 40 dB down; the sizzle, the ring's
peaks over the floor between them. A search of 72 settings through BLUE's print chose: DAMP .55; a 2 kHz low cut (was
500 Hz); the ride's own band over 5 kHz added 1.5 times over and gone in 10 ms (the ping); round 0h's +12 dB at 8 kHz
and 12 kHz cut kept; round 0h's softened strike gone; after 30 ms, an exponential fade of 0.35 s. Measured: the stick
at 7.8 kHz (round 0j 7.1), 1 to 3 kHz at -17.5 dB (round 0j -12.5), the ring 21 dB under the stick (round 0j 9; the
references 13 and 8), 40 dB down at 0.65 s (round 0j 1.4 s; the references 1.8 and 3.2), its peaks 21.6 dB over the
floor (the references 17.6 and 10). The ring's length is the owner's to judge, so a version fading over 0.5 s (40 dB
down at 0.77 s) is rendered beside it. A fade of 0.1 to 0.15 s was tried and rejected: 20 dB down in 0.1 s is a
choked cymbal, and the ring sank under the print's own vinyl floor.

**Round 0l, a fresher print for the rest of the kit.** "The ride is better the rest sounds like it's in a muffled
room" (the owner, 2026-11-20). Measured on groove A without the ride, by its spectral centre, its band shares, and
its clarity (the 50 ms after each backbeat's peak against the 250 ms after that, median of eight): dry, 1,691 Hz and
4.7 dB; through BLUE's room and print, 291 Hz and 1.5 dB. Stage by stage: the room alone kept clarity (4.1 dB); the
print alone took it to 2.1 (the tape alone 3.1, its drive costing 0.6; the converter alone 3.6), and the bus
compressor lifted the tails further. The darkening was the room's shelf (-4 dB over 1.2 kHz; removing it gave back
about 3.5 dB over 2 kHz), the tape's worn head (AGE .60, a one-pole near 6 kHz) and the converter's tone (.95);
moving the mic closer (DISTANCE .25, .15), harder walls, a smaller room and less vinyl rumble did next to nothing. So:
BLUE's print takes AGE .20 (near 12 kHz), DRIVE .08 (was .15) and TONE 1; the room's compressor eases to AMOUNT .20
(was .35); and the kick, brush tap, foot hat and shaker cancel the room's shelf with a mirrored +4 dB shelf at 1.2 kHz,
the last three with +3 dB over 4 kHz besides. The ride was fitted to the old chain: its presence comes down from +12
to +8 dB, which lands its printed hit where round 0k's was (the stick at 7.96 kHz against 7.79, 1 to 3 kHz -17.5
against -17.5, the ring -21.2 against -20.9, 40 dB down at 0.65 s). The ride keeps the room's shelf. Across the three
grooves: the centre from 316-360 to 488-561 Hz, 2 to 6 kHz up 2.6 to 3.8 dB, 6 to 12 kHz up 3.2 to 3.9, 200 Hz to
1 kHz down 1.4 to 1.7 (less box), clarity from 1.4-1.6 to 2.7 dB. Lesson for the kits: a print tuned for one voice
(the ride) can bury the others; measure the kit without it.

**SPEED and RAGA kept.** "Keep speed and raga" (the owner, 2026-11-20).

**Round 0m, BLUE's groove and ride chosen.** "The longer cymbal is good. The break for blue sounds better than the
groove. The groove kind of sounds like a Happy Trails type beat" (the owner, 2026-11-20). BLUE's groove is round 0i's
break (`breakNotes`: sixteenths swung at 58%, the ride on eighths, the kick at 1, the "a" of 1 and the "and" of 3, the
brushes on two and four, no shaker); the triplet pockets trotted like a cowboy clip-clop at 90 and stay rendered for
the record. Round 0's groove (`blueNotes`) is removed from the generator. The ride's fade is 0.5 s (40 dB down at
0.77 s), the longer of round 0k's two. Still open: whether round 0l cleared the muffled room, whether SPEED's 1.35
speed-up is right, subtler or more extreme, and whether RAGA's tabla leads or sits under the kick and snare.

[Expansion round 0 listening page](https://claude.ai/artifact/Q3rZ8po8ZaeJs9E4tNd86s) (private to the owner; what is
decided and the three open questions at the top, then BLUE's history, SPEED and RAGA).

## Gates

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: drum kits |
| Drums: multiple distinct genre kits on the shelf | Pass by ear (2026-11-18, round 3: "It sounds good to me"): four kits under SKINS. `LooseCrateKitsTest` holds every pair to at least six different pad names and a different kick and main snare, dry (shape distance over 0.5), and the four prints differ |
| Percussion: roll/riser | Pass by ear with the kits: one accelerating roll per kit (A15), in the room since round 2 |
| Velocity and round robin: rendering | Pass in software: every soft zone is a different strike from the hard one and never brighter; every zone holds four different strikes (`LooseCrateKitsTest`) |
| Velocity and round robin: saving, playback, export | Pass: in software, and on the owner's phone and MPC ("All good on my gates", 2026-11-18): `LooseCrateKitTest` and `:shell:generateLooseCrateKits` (all four kits, 192 slices each: saved PCM, reload, the pad resolver walking the takes, MPC 3 track, SFZ, DecentSampler). MPC 2 is not written (the owner's format limit). Not played on a phone or an MPC |

## Ledger

- **Verified**: the APIs above, by grep at `afb0fa98`; `LooseCrateRound0Test` (4), `LooseCrateKitsTest` (11) and
  `LooseCrateKitTest` (3) pass; `:shell:generateLooseCrateKits` verifies all four kits.
- **Measured**: every number in the round-0 tables, Python probes on the Kotlin renders and on the reference
  files.
- **Proposed**: every setting outside round 0's candidates, the four prints, the rolls and grooves.
- **Heard**: round 0's two hum kicks ("Both sound great"); round 1's kits ("Sounds pretty good", "It's a bit drum
  machine like"); round 2a, choosing A; round 2 ("Better. Crate cloth could use a bit of warmth or compression in the
  bass"); round 3 ("It sounds good to me"); the device gates ("All good on my gates") (all 2026-11-18).
