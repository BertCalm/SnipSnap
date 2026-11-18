# LOOSE CRATE

A planned family of drum kits after one producer's loose, dusty, sub-heavy beat-tape drums. LOOSE CRATE is a
working name. No kit, pad or preset name may use the producer's name, his album titles or his label
(`PresetTestSupport.trademarkBlocklist`; round 1 adds those terms to it).

The characteristic gesture is **kick hums under the bar → snare lands loose, flammed or bouncing → hats drag
behind → a dark, rumbling bed with the top cut off**.

## Status

**Round 0 rendered, not yet heard.** Seven candidate hits and one four-bar groove, rendered in Kotlin from the
engines as they stand and measured against the owner's reference files. Nothing is on the shelf, nothing is
registered, and no setting has been heard. The [listening page](https://claude.ai/artifact/DaKKJX6p18cMbitmFKDVQn)
is private to the owner's account.

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

## Gates

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: drum kits |
| Drums: multiple distinct genre kits on the shelf | Not yet: four planned, none built |
| Percussion: roll/riser | Not yet: a flam roll is planned per kit |
| Velocity and round robin: rendering | Partly: three zones and four fresh takes per candidate render; velocity is timbral except the thud kick (above) |
| Velocity and round robin: saving, playback, export | Not yet: no kit exists |

## Ledger

- **Verified**: the APIs above, by grep at `afb0fa98`; `LooseCrateRound0Test` (four tests) passes.
- **Measured**: every number in the round-0 tables, Python probes on the Kotlin renders and on the reference
  files.
- **Proposed**: every macro setting, the print, the four kits, the multi-strike offsets.
- **Heard**: nothing yet.
