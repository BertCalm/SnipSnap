# STAGGER: design brief for a muffled-kick, drunk-clock boom-bap kit family

Status: concept with a round-0 Python prototype. Nothing rendered in Kotlin, nothing heard.
Repository truth: read at `8daaf2ef` (2026-11-18). APIs named below were grepped: `CypherKits` (`Part`,
`Phrase`, `Print`, `build`, `render`, `touch`, `velocities`, `TAKES`), `KitStrikes` in `Cypher.kt`
(`thumpKick`, `skinStruck`, `skinTouched`, `terra`, `tines`, `cymbal`, `multi`, `layered`, `struck`,
`finish`), `Cypher.Note` (`offsetMs` clamped to +-60), `Cypher.behind`, `Cypher.humanised`,
`Cypher.ratchet`, `Cypher.phraseRaw`, `LooseCrateKits.Print` (`HEADROOM`), `LooseCrateKits.Room`,
`LooseCrateKits.played`, `LooseCrateKits.squeezeBass`, `Crunch.MACROS` (BITS, RATE with a 10.5 kHz hold
floor, TONE, GRIT), `Eras.process("sp1200")` (12 bits at 26.04 kHz, no anti-alias filter, zero-order hold back
up), `Speed.process` (SEMITONES, 0.5 native, +-12), `Tape`, `Vinyl`, `Valve`, `Compressor`, `Room`,
`FiredBronze.Recipe(Part.KEMPUL, ...)` ("Dark Gong"), `TinesVoice.ZAP`, `ThumpVoice.SPARK`,
`BreakerCymbal.render(Kind.CRASH, ...)`, `SkinVoice.TOM`, `TerraVoice.COMPOUND_MEMBRANE`,
`Classifier.LOOP_MIN_SECONDS` (1.5 s), `PresetTestSupport.trademarkBlocklist`, `StarterKits.kt:774`
(the LOOSE CRATE rows), `StarterShelf.kt:54` (the SKINS group), `shell/build.gradle.kts:601`
(`generateLooseCrateKits`). `siblingPatches` does not exist (expected).

Working name STAGGER, after the clock. The producer this brief is written after cannot name a kit,
pad, phrase or preset: his name, his aliases, his album and series titles, his labels and his
collaborators' names go on the blocklist the family's test checks (the LOOSE CRATE rule,
`LooseCrateKitsTest`). This research section names nothing either; the owner's research does, and
that is where the names stay.

The characteristic gesture is **a muffled thud with no click → a dry crack right on top of it → a hat
you can barely hear, or none → the clock drags and catches up → a cartoon lands where a fill would**.

## What it is (one paragraph)

Three authored sixteen-pad drum kits in the CYPHER shape (fourteen hits, a roll or riser on A15, a
bar-exact groove on A16, three velocity zones of four takes on every pad, saved with the version that
printed them). The sound is the owner's three-part profile: a low-passed, boxy kick against a sharp,
dry snare with the hats turned down to a whisper; sequencing that ignores the grid in a particular way
(the kick lazily late, the snare a hair early, and the lag breathing rather than scattering); and
foley in place of fills: timpani rolls, a dark gong, glass breaking, a laser zap, a comic-book punch,
all pitched down through a 12-bit converter so the converter's own grain comes down into the band a
phone plays. Everything but two small things is authoring over shipped engines and prints. The two
new things are one feel law (`staggered`, a shared mean-reverting drift plus a per-part bias) and one
sound source (a glass shatter, which no engine makes).

## Why it earns a shelf row (the sibling check)

| Need | Nearest in the roster | What it cannot do |
|---|---|---|
| A kick with the click filtered off, driven so a phone still hears it | THUMP KICK (CLICK 0 still leaves the sine's own attack; no low-pass), SKIN KICK (TONE darkens, no two-pole roll-off), LOOSE CRATE's `squeezeBass` (a compressor under 180 Hz, not a filter) | roll the kick off at 250 to 350 Hz with harmonics put back *before* the filter; nothing in the roster prints a kick that way |
| The kick late, the snare early, the lag breathing | `Cypher.behind` (one fixed offset per part), `Cypher.humanised` (every note independent), `Cypher.swung` | a lag that is correlated from note to note: measured, `humanised` has a lag-one autocorrelation of -0.10, a drift has 0.74 (§ the prototype) |
| Foley with the converter's grain pitched down into the audible band | `FxChain` runs PITCH (Speed) first, CRUNCH later, by design (`Speed.kt`: "it runs first, so ... the whole rack all see the pitched sound") | a sampler's order, converter *then* pitch: measured on a zap, the sampler order puts its loudest image at 10.5 kHz, the rack's at 19.7 kHz, and 2.3 times the energy from 6 to 16 kHz |
| Glass breaking | nothing (grep "shatter": two preset names, ORPHICA and SCOUR) | a scattered cascade of tiny ringing shards |
| Timpani, gong, zap, crash | SKIN TOM and TERRA COMPOUND_MEMBRANE at low TUNE; FIRED BRONZE KEMPUL "Dark Gong"; TINES ZAP and THUMP SPARK; BREAKER's CRASH | nothing; reused, printed through the foley converter |
| Dusty, loose boom bap | CYPHER DUST (unquantized break, tape driven hard), SOOT (dictaphone grime), LOOSE CRATE (the humming kick, flams, dragging hats, record rumble, approved 2026-11-18) | none of them muffles the kick while leaving the snare sharp, none has the asymmetric drunk clock, none carries foley as pads |

The owner has chosen the kit-family landing three times for this kind of brief (GATECRASH, CYPHER,
LOOSE CRATE), so this brief does not re-argue it: a genre is a set of authored choices, and a kit is
the lightest landing that keeps the gesture. A STAGGER engine would cost 26 integration surfaces for
what three tables and two helpers do.

## Signal flow

```
kit, role, velocity v, take t
        │
        ▼
[Part: engine strike]──(dry hit @44.1 kHz, peak = touch(v))──▶[pad print]──▶[kit print]──▶[zone gain]──▶[finish]
   SKIN / THUMP / TERRA /           one of:                        CRUNCH 12-bit, 26 kHz hold,
   TINES / FIRED BRONZE /           none (the snare, the rim: dry)  TONE dark, GRIT low
   BREAKER crash / SHATTER          MUFFLE: tanh(3x) → 2-pole LP   → TAPE light (WOBBLE .06, DRIVE .15, AGE .55)
   or PUNCH (whoosh + thud)         FOLEY: Eras sp1200 → Speed     (foley pads skip the kit's CRUNCH:
                                    (SEMITONES -5, -7 or -9)        their print already converted them)

[Phrase]: notes (beat, part, weight) ──▶ staggered(bias by part, wobble, rho, seed) ──▶ each note a fresh
          strike ──▶ summed dry ──▶ played as one kit in one small room with a bus compressor and a dark
          shelf (LOOSE CRATE's Room law) ──▶ the kit print, printed twice end to end for the loop seam
```

MUFFLE in words: a soft clipper first, then a two-pole low-pass. The clipper makes harmonics at two,
three and four times the kick's root (100 to 250 Hz for a 52 Hz root); the filter then takes the click
and everything above a few hundred hertz away, but the harmonics it made sit under the cutoff and
survive. Without the clipper the filter leaves a sine, and a sine at 52 Hz is a number on a phone, not
a sound. Measured below.

FOLEY in words: the effect is first "sampled" at 12 bits and 26.04 kHz (the hold images of the
converter sit around 18 kHz at the house rate), then played slow. Playing it slow moves everything,
images included, down by the same ratio: at -7 semitones the 18 kHz image lands at 12 kHz, where it is
heard as grainy metal. The rack cannot do this because PITCH runs before CRUNCH, so the foley print is
a kit-side `Print` that composes the two sections in the sampler's order, the way LOOSE CRATE's
`Print` composes CRUNCH → VALVE → COMPRESSOR → TAPE → VINYL in the order the records were made.

## The three kits

| Kit (working name) | Tempo | Identity | Pads (A01 to A14) | A15 | A16 |
|---|---|---|---|---|---|
| CARDBOARD | 89 | the profile itself: boxy kick, dry crack, whisper hat | Box Kick (THUMP KICK, CLICK 0, MUFFLE 300 Hz), Crack Snare (SKIN SNARE, SNAP .8, SIZZLE .7, DECAY .3, dry), Whisper Hat (SKIN HAT_CLOSED, TONE .3, DECAY .15), Rim (THUMP RIM, dry), Low Box Kick (MUFFLE 250 Hz, TUNE low), Cross Stick (SKIN STICK), Paper Snare (SKIN SNARE, soft zone's wires, as a pad), Shaker (SKIN SHAKER, dark), Dead Tom (SKIN TOM, MUFFLE 400 Hz), Timpani (SKIN TOM, TUNE .15, DECAY .8, FOLEY -5), Dark Gong (FIRED BRONZE KEMPUL, FOLEY -5, faded under 1.5 s), Zap (TINES ZAP, FOLEY -7), Punch (whoosh + thud, FOLEY -5), Clap (THUMP CLAP, dry, short) | Timpani roll: `ratchet` at 1/32 over two beats, weight .3 to 1, into the gong | two bars, `staggered`; hats at .25; the kick and snare carry the bar |
| MATINEE | 84 | the cinematic kit: half the pads are effects | Box Kick, Crack Snare, Rim, Whisper Hat, then ten foley pads: two timpani (FOLEY -5 and -9), Gong, Crash-Gong (BREAKER CRASH, 1.4 s, FOLEY -9), Shatter (take-dealt, FOLEY -5), Low Shatter (FOLEY -9), Zap, Low Zap (THUMP SPARK, FOLEY -7), Punch, Stab (TERRA TUNED_BAR, BUZZ .6, FOLEY -7) | Riser: the timpani roll crescendo over a whole bar (`ratchet`, 1/16 then 1/32) landing on the crash-gong | two bars, `staggered`; a shatter on the and of four, a zap pickup into bar two |
| STUMBLE | 93 | the drunkest clock; a rim in the snare's place; no hats in the groove | Hum Kick (SKIN KICK over THUMP SUB, MUFFLE 350 Hz, drive 4), Rimshot (THUMP RIM, TUNE .85, dry), Crack Snare, Ghost Snare (`softer` .4), Shaker (whisper), Open Box (SKIN HAT_OPEN, dark, short), Low Tom (MUFFLE 400 Hz), Knock (TERRA SLAP, BOWL 0), Timpani, Gong, Punch, Zap, Clap, Bell (TINES BELL, FOLEY -7) | A dragging snare roll: `ratchet` with `staggered` on the roll itself (every stroke a little late) | two bars, `staggered` at the widest law; no hat part at all, the shaker at .2 |

A fourth kit, the dusty-loop side (record rumble under a humming kick), was considered and left out: it
is LOOSE CRATE HUMMIN and CYPHER DUST, and the owner kept both of those by choice.

Every setting in the table is a shape value. Pad names are placeholders for the trademark check.

## The macro contract (for a kit family, the authored laws)

| Law | Default (shape) | What it changes | Required interaction | Test |
|---|---:|---|---|---|
| MUFFLE cutoff | 300 Hz (250 to 400 by pad) | the two-pole low-pass after the clipper on a kick or tom pad | with drive: at 250 Hz and no drive the phone band falls under 1 percent | `share_150_400 >= 0.01` on every muffled pad (the CYPHER sub test's shape) |
| MUFFLE drive | 3 (tanh gain) | harmonics made before the filter | doubles the 150 to 400 Hz share at every cutoff (measured) | the drive-off render has less phone-band share than the drive-on one |
| kick bias | +22 ms (STUMBLE +28) | how late the kick lands | with the snare's -6: the kick sits 28 to 34 ms behind the snare | kick mean minus snare mean within 25 to 40 ms |
| snare bias | -6 ms | the snare a hair early | | |
| hat bias | +8 ms | the hats sit back | | |
| wobble | 12 ms (STUMBLE 18) | the drift's stationary spread, shared by every part | clamped with the bias to +-60 (`Cypher.Note`) | worst offset under 60; every note still an onset of its part (`CypherTest`'s probe) |
| rho | 0.75 per eighth | how slowly the lag breathes | 0 is `humanised`, 1 is a constant | lag-one autocorrelation of the hat offsets over 0.5 |
| whisper | hats at .25 of the groove's level (STUMBLE: no hat part) | the hats' place in the phrase, not the pad's level | a pad is levelled to .92 like every pad: "whisper" lives in the phrases and in the hat's timbre | the groove's 6 to 14 kHz band at its loudest at least 20 dB under the full band's |
| FOLEY pitch | -5, -7 or -9 semitones by pad | `Speed` after the converter | foley pads skip the kit's CRUNCH; every foley pad ends under 1.5 s so it files as PERC, not LOOP | `drumClass` of every pad from the render; the sampler-order render carries more 6 to 16 kHz energy than the rack-order one on a zap |

## The three directions

**Strong: DISTIL and JUXTAPOSE, a kit family (as above).** The profile's three parts become three
kit-side mechanisms: a per-pad MUFFLE print, a `staggered` feel law beside `behind` and `humanised`
in `Cypher.kt`, and a FOLEY print that composes the converter and the transport in the sampler's
order. One new source (the shatter) and one new strike (the punch: two different sources at an
offset, which `KitStrikes.layered` almost does and `multi` does for one drum). Gesture clarity: high.
Novelty: the contrast print, the drunk clock and foley-as-pads are each absent from the shelf. Risk:
low; everything but the shatter is shipped code. Cost: one synth file, one feel function, two shell
test files, one Gradle task, three starter rows. Wins because the brief is three sounds, not three
engines, and this is the shape the owner has approved three times.

**Alternate A: INVERT, the drunk clock as a kit-level feel on every shelf kit.** `Groove.hits` (the
demo groove every kit plays itself with) gains a FEEL dial that runs `staggered` over any kit's
pattern. Broader reach (every kit can stumble), less identity (no muffle, no foley), and it touches
the demo groove's MPC clip writer. Worth doing later if the owner likes the clock on STAGGER.

**Alternate B: MUTATE, MUFFLE as a THUMP voice.** A BOX voice whose low-pass is inside the engine and
follows velocity (a softer hit is more muffled, as a softer beater is), tunable in the SYNTH screen.
The SUB precedent: the kit-side function is written so it can become a voice's `render` if the owner
asks to turn the knob. Costs the `Velocity.kt` arms, presets, the classifier contract and a picker
entry; a round of its own.

## Risks

- **The muffled kick vanishes on a phone.** Measured: under 250 Hz the 150 to 400 Hz share drops below
  1 percent even with drive. The law stops at 250 Hz and the drive is on. The listening page plays the
  Box Kick at 400, 300 and 250 Hz so the owner can choose, as they did for the SUB pedal's drive.
- **"Drunk" reads as "sloppy".** The owner heard LOOSE CRATE round 1 as "a bit drum machine like"; the
  opposite failure is the same page. The page plays CARDBOARD's groove three ways: `staggered`,
  `humanised` at the same spread, and `behind` alone.
- **Foley reads as a sound-effects library, not a kit.** Every effect is levelled, printed and dealt
  like a drum, kept under 1.5 s (the LOOP floor), and placed where a fill goes on the grooves.
- **Two converters in series.** A foley pad's print already sampled it at 26 kHz; the kit's CRUNCH would
  sample it again at a different rate and the grain would double. Foley pads skip the kit's CRUNCH and
  take only its TAPE.
- **The loop seam under tape wobble.** Reuse LOOSE CRATE's print-twice-and-keep-the-second-pass
  (`LooseCrateKits.printed`); measured there at 0.0001 to 0.0045 against a 0.001 house limit, so a
  disclosed seam is likely.
- **Whisper hats as pads.** A pad is levelled like every pad, so the hat *pad* is not a whisper; its
  timbre is (dark, short), and the groove is. Disclosed.
- **Trademarks.** The blocklist terms for this family: the producer's name and aliases, his album and
  series titles, his labels, his collaborators. `PresetTestSupport.trademarkBlocklist` plus a family
  list in the test, as LOOSE CRATE does.

## Render budget

LOOSE CRATE's kits build in 31 to 107 s on the build machine (every groove through a room), and the
phone builds a starter when it is picked. STAGGER's pads cost the same shape; the FOLEY print adds two
resamples (cheap); the shatter is 28 shards of three two-pole modes over 0.7 s at the internal rate,
which the Python harness rendered in well under a second per take. No solver, no cache story needed.
The Kotlin round 0 measures the per-pad time as LOOSE CRATE did.

## Cheapest proof: the round-0 prototype (done, Python, not Kotlin, nothing heard)

`prototype/stagger/stagger_proto.py` writes `prototype/stagger/report.json` and 23 WAVs to
`testkit/stagger-proto/` (gitignored). numpy only; this sandbox has no scipy, so the filters, the
resampler and the probes are written out in the script.

**1. The muffled kick** (a THUMP-shaped kick, 118 falling onto 52 Hz, with a click; a two-pole low-pass
after a tanh; the sharp dry snare beside it for the contrast):

| Cutoff | Drive | 150 to 400 Hz share | Above 1 kHz | Whole centroid | Crest |
|---|---|---|---|---|---|
| open | 1 | 1.27 % | 0.16 % (the click) | 3,116 Hz | 19.0 dB |
| 600 Hz | 1 / 3 | 1.24 / 2.47 % | 0.001 % | 175 / 152 Hz | 18.0 / 14.6 |
| 350 Hz | 1 / 3 | 1.06 / 2.10 % | 0 | 132 / 125 Hz | 17.7 / 14.6 |
| 250 Hz | 1 / 3 | 0.82 / 1.55 % | 0 | 113 / 111 Hz | 17.7 / 14.5 |
| 160 Hz | 1 / 3 | 0.41 / 0.69 % | 0 | 95 / 94 Hz | 17.5 / 14.1 |

The snare's centroid is 7,838 Hz with 59 percent of its energy above 1 kHz. So the contrast the profile
describes is a 60-to-1 centroid ratio with the click gone entirely (the above-1 kHz share reads 0 from
350 Hz down). The drive doubles the phone-band share at every cutoff and costs 3.5 dB of crest (the
thud is squarer). Attack time to 90 percent of peak stays 6 ms down to 250 Hz and is 8 ms at 160 Hz:
the filter removes the click, not the onset. The law: 250 to 400 Hz, drive on.

**2. The drunk clock** (a four-bar boom-bap pattern at 88 BPM; bias kick +22, snare -6, hat +8 ms;
wobble 12 ms; rho 0.75 per eighth):

| Law | Kick mean / spread | Snare mean / spread | Hat mean / spread | Kick behind snare | Hat lag-one autocorrelation | Worst |
|---|---|---|---|---|---|---|
| `staggered` | +31.5 / 11.5 ms | +0.5 / 14.2 ms | +15.9 / 12.0 ms | 31.0 ms | 0.74 | 52.0 ms |
| `humanised` 22 ms | -2.0 / 10.1 | -2.4 / 10.7 | -1.5 / 13.6 | 0.5 ms | -0.10 | 21.6 ms |
| `behind` only | +22 / 0 | -6 / 0 | +8 / 0 | 28 ms | 0 | 22 ms |

The same seed gave the same offsets twice. The spread of `staggered` matches the LOOSE CRATE references'
"15 to 50 ms off the grid" reading; what `humanised` cannot give is the 0.74: neighbouring notes lean the
same way, which is a player, not a scatter. One disclosed thing: over four bars the drift's mean came out
near +9 ms, so every part landed later than its bias (the kick 31.5 against 22). The Kotlin law should
centre the drift over the loop so the authored bias is exactly what the kit says.

**3. The converter order** (a zap and a timpani, -7 semitones, 12 bits at 26.04 kHz):

| Source | Order | 6 to 16 kHz share | Loudest bin above 10 kHz |
|---|---|---|---|
| zap | rack (pitch, then convert) | 0.37 % | 19,660 Hz |
| zap | sampler (convert, then pitch) | 0.84 % | 10,456 Hz |
| timpani | rack | 0.001 % | 18,119 Hz |
| timpani | sampler | 0.001 % | 11,995 Hz |

The predicted image for low content is 44,100 - 26,040 = 18,060 Hz, and 12,054 Hz after -7 semitones;
the timpani measured 18,119 and 11,995. On a bright effect the sampler order carries 2.3 times the
upper-band energy and puts the grain at 10 kHz, which is the "grainy, metallic, lower sample rate" of
the profile. On a dark effect the order is inaudible. So FOLEY is a kit-side print in the sampler's
order, and it matters most on the zaps, the shatter and the punch.

**4. The glass shatter and the punch.** Three takes of the shatter are three different panes (peak
normalised cross-correlation 0.07 and 0.05 between takes; a copy is 1, the family's "another hit" line
is under 0.5), the same take twice is byte-identical, 0.7 s, centroid 4,755 Hz, rising to 6,038 Hz
through the converter at -5 (the images added the metal). The punch's whoosh leads its thud by 114 ms,
so the pad's onset is the swing, not the hit, which is the cartoon.

## Integration (round 1, the rows that grep)

1. `synth/.../StaggerKits.kt` in `LooseCrateKits`' shape: `Kit`, `Print`, `Part`, `Phrase`, `build`,
   `render`, `dry`, the room law for the grooves. Version 1.
2. `Cypher.staggered(notes, biasMs: Map<Int, Float>, wobbleMs, rho, seed)` beside `humanised`, drift
   centred over the loop, clamped to `Note`'s +-60. Adding a function does not move CYPHER's or LOOSE
   CRATE's audio, so `Cypher.VERSION` stays.
3. The shatter and the punch as private sources in `StaggerKits` (as SLIDE began inside CYPHER);
   `KitStrikes.layeredAt(a, b, offsetMs)` only if `layered` plus `multi` cannot make the punch.
4. **Lift, do not copy:** `LooseCrateKits.Room`, `played` and `printed` (the print-twice seam) are
   what STAGGER's grooves need. A private helper copied between kit files is the roster's recurring
   defect (`KitStrikes` was lifted out of GATECRASH for CYPHER for exactly this reason). Move them to
   a shared object beside `KitStrikes` in `Cypher.kt`, keep LOOSE CRATE's renders byte-identical, and
   prove it with its pinned tests.
5. Three starter rows after `StarterKits.kt:774`, three ids in the SKINS group at `StarterShelf.kt:54`,
   `generateStaggerKits` after `shell/build.gradle.kts:601`.
6. `StaggerKitsTest` (synth): every pair of kits at least six different pad names and a different kick
   and snare dry; every muffled pad's phone-band share; every soft zone a different, duller strike;
   four different takes per zone; the clock's numbers on each groove; every pad's class from its
   render; the blocklist. `StaggerKitTest` (shell): save, reload equal, the pad resolver walking the
   takes, MPC 3 read-back, SFZ and DecentSampler, as `LooseCrateKitTest`.
7. `docs/STAGGER.md` as built, a `docs/SYNTH_ROADMAP.md` row, the gates entry.

## Build rounds and gates

- **Round 0 (this brief):** the Python numbers above; the owner picks the kits and the clock's width.
- **Round 1:** the Kotlin candidates (CARDBOARD's fourteen hits, its groove three ways, the Box Kick at
  three cutoffs, the shatter at three pitches), a listening page. Gate: the owner's ear.
- **Round 2:** three kits, the generator, the gates table. Gate: "multiple distinct genre kits", velocity
  and round robin through rendering, saving, playback and MPC 3 export in software.
- **Round 3:** the owner's phone and MPC.

## Verified / measured / proposed / heard

| Claim | Status | Evidence |
|---|---|---|
| The CYPHER kit shape, `KitStrikes`, `Cypher.Note` +-60 ms, `behind`, `humanised`, `ratchet` | verified | `Cypher.kt`, `CypherKits.kt` at `8daaf2ef` |
| `FxChain` runs Speed before Crunch | verified | `FxChain.SECTIONS`, `Speed.kt` KDoc |
| `Eras.sp1200` is 12 bits at 26.04 kHz with no anti-alias filter | verified | `Eras.kt:60-80` |
| LOOSE CRATE's room law and print-twice seam | verified | `LooseCrateKits.kt` (`Room`, `played`, `printed`) |
| A low-pass at 250 to 400 Hz with a tanh ahead of it keeps over 1 percent in 150 to 400 Hz | measured | `report.json`, Python, not Kotlin |
| `staggered` has lag-one autocorrelation 0.74 against `humanised`'s -0.10 | measured | `report.json` |
| The sampler order carries 2.3 times the 6 to 16 kHz energy of the rack order on a zap | measured | `report.json` |
| Three shatter takes correlate under 0.1 | measured | `report.json` |
| Every table setting, the three kits, the pad lists | proposed | nothing rendered in Kotlin |
| The kick reads as boxy and the clock as drunk | heard? | not heard; round 1's page |

## Why it is built this way (for the owner)

- **The kick is clipped before it is filtered, not after.** A filter can only remove. If the click and
  the top go first, what is left is a sine at 52 Hz, and a phone speaker plays nothing there. The clipper
  makes copies of the kick an octave and a twelfth up first; the filter then takes the click but leaves
  those copies, and the phone hears a thud. Remove the clipper and the 150 to 400 Hz share halves
  (measured).
- **The clock is one drift shared by every part, plus a bias per part.** A drummer has one sense of time
  that wanders, and a habit per limb. If each note drew its own lateness (`humanised`), the kick and
  the hat on the same beat would disagree, which the ear hears as a mistake rather than a feel. The
  shared drift is why neighbouring notes lean the same way (the 0.74), and the biases are why the kick
  still drags behind the snare on average (31 ms).
- **The foley is converted first and pitched second, on the kit side, not in the rack.** The rack's
  order is right for everything else and is pinned by goldens, so it is not touched. The kit print
  composes the same two sections in the other order, which is what a sampler did.
- **The grooves are played in a room with a bus compressor**, because the owner heard a dry sum of
  isolated hits as "drum machine like" on LOOSE CRATE, and the room law that fixed it is already
  written. It is lifted, not copied.
- **The one risky decision is the cutoff.** 250 Hz is the profile; 400 Hz is safer on a phone. The page
  plays both and the owner picks, as they did for the SUB pedal's drive.

## What would have changed this brief (prompt notes)

- A reference loop per kit, with timestamps, the way LOOSE CRATE had twelve files: the clock's biases
  and the cutoff would then be measured against something rather than set by shape.
- Which three kits. The profile names one sound; the three here are my split (the profile, the
  cinematic one, the drunkest one). A fourth from the early instrumental series or a collaborator's
  side is a different kit.
- Whether MUFFLE should become a THUMP voice later (Alternate B), which decides how the kit-side
  function is written in round 1.
