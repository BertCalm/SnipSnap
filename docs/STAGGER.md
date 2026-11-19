# STAGGER

Authored drum kits after the owner's three-part producer profile: a kick low-passed until it has no click
against a sharp dry snare and hats at a whisper; a clock whose kick drags and whose snare lands a hair early,
the lag breathing rather than scattering; and foley (timpani, gong, glass, zap, punch) pitched down through a
12-bit converter in place of fills. STAGGER is a working name: the producer the profile was written after, his
aliases, titles, labels and collaborators cannot name a kit, pad or phrase (`StaggerKitsTest` checks every name).

The characteristic gesture is **a muffled thud with no click → a dry crack right on top of it → a hat you can
barely hear, or none → the clock drags and catches up → a cartoon lands where a fill would**.

[Design brief, the measured references and the round-0 prototype](superpowers/specs/2026-11-18-stagger-drum-kits-brief.md).

## Status

**Round 2 built (2026-11-19): three kits on the shelf, proven in software; the kits not yet heard as kits.** The owner heard
round 1b and said "New sounds are better" (2026-11-19), which retires round 1's THUMP and SKIN drums and keeps EMBER's.
The owner did not pick a cutoff, a clock width or slices over hits, so round 2 keeps the kit's defaults (300 Hz, the kit's
clock, isolated EMBER hits) and the page's alternatives stand ready. Round 2 adds the family's other two kits and the
shelf rows, and proves every pad's velocity and round-robin grid through save, reload, the app's pad resolver and the
MPC 3 export (`StaggerKitTest`, `:shell:generateStaggerKits`).

| Kit | Tempo | Print | Clock (kick, snare, hats; wobble, rho) | Pads (A01 to A14) | A15 | A16 |
|---|---|---|---|---|---|---|
| STAGGER CARDBOARD | 89 | box: 12 bits at 26 kHz, a worn tape | +12, -3, +8; 10 ms, .4 | Box Kick, Crack Snare, Whisper Hat, Rim, Low Box Kick, Cross Stick, Paper Snare, Shaker, Dead Tom, Timpani, Dark Gong, Zap, Punch, Clap | Timpani Roll | Cardboard Groove |
| STAGGER MATINEE | 84 | reel: the same converter, a cleaner tape | +10, -3, +6; 8 ms, .4 | Stage Kick, Stage Snare, Whisper Hat, Rim, Timpani, Low Timpani, Dark Gong, Crash Gong, Glass, Low Glass, Zap, Low Zap, Punch, Stab | Timpani Riser | Matinee Groove |
| STAGGER STUMBLE | 93 | worn: the tape driven and worn, more record | +18, -3, +10; 16 ms, .55 | Hum Kick, Rimshot, Crack Snare, Ghost Snare, Shaker, Open Box, Low Tom, Knock, Timpani, Dark Gong, Punch, Zap, Clap, Bell | Dragging Roll | Stumble Groove |

The kits differ in their drums (`StaggerKitsTest` holds every pair to at least six different pad names and a different
kick and backbeat drum, dry), their rooms (CARDBOARD small and dry, MATINEE a stage, STUMBLE close with the bus pressing
harder), their clocks, their prints and their phrases, not in effect strength alone. MATINEE's groove has a glass break
on the and of four and a zap pickup into bar two; STUMBLE's has no hat part at all (the shaker whispers) and the rimshot
takes the backbeat; its roll is sixteenths every stroke later than the last by its own clock.

Measured on the grooves (`StaggerKitsTest`): hats 28.4, 28.5 and 29.2 dB under the mix at their loudest (CARDBOARD,
MATINEE, STUMBLE; the references 16 to 35, median 23); 95 percent of the energy under 579, 636 and 495 Hz (the references
309 to 635; MATINEE's foley is allowed up to 1.5 kHz and lands inside the references anyway). A foley pad's cap shrinks
with its pitch so every pad lands under 1.4 s (the Low Timpani at -9 measured 1.62 s with a flat 1 s cap).

The round 2 listening page (private to the owner's account): [Stagger, round two](https://claude.ai/artifact/5YU1WGvQy2ZyhDj2WCx8dC).
Every pad of the three kits read back from its MPC 3 export through the app's own pad resolver: printed, dry, and
soft, medium and hard with each zone's four takes. The page plays MP3 copies (160 kbps); the kits' audio is the WAV.

`:shell:generateStaggerKits` verified all three kits on 2026-11-19: 16 pads and 192 slices each, saved PCM, reload,
the pad resolver over two cycles of every zone, the MPC 3 track read back, SFZ and DecentSampler written; built in
138, 139 and 115 s (CARDBOARD, MATINEE, STUMBLE) on this machine, EMBER's models being the cost. The phone builds a
starter when it is picked, and this has not been measured on a phone.

```sh
./gradlew --no-daemon :synth:test --tests '*StaggerKitsTest' :shell:test --tests '*StaggerKitTest'
./gradlew --no-daemon :shell:generateStaggerKits          # → testkit/stagger-kits/ (gitignored)
python3 scripts/stagger_page/build_page.py testkit/stagger-kits OUT_DIR [mp3]
```

**Round 1b heard (2026-11-19): "New sounds are better."** EMBER's drums are the kit's.

**Round 1b built, not heard (2026-11-19): every drum re-sourced from EMBER.** The owner heard round 1 as "too much
like drum machine sounds". Round 1's drums were THUMP's and SKIN's: a swept sine is a drum machine by construction,
and an isolated synth hit has no shell, wires, bleed or room in it, where the profile's drums are chopped off records.
Round 1b (`StaggerKits` version 2) keeps the kit, the muffle, the foley print and the clock, and sources every drum
from EMBER (calfskin heads with sag, threshold wires, hats and ride fitted to recordings, the club's response and a
warm chain; the owner approved its voices by ear on 2026-11-16). It also shows a second way, SHATTER's: the groove
played in its room and cut at a hit (`StaggerKits.sliced`), so the pad carries its neighbours' bleed.

The round 1b listening page (private to the owner's account):
[Stagger, round one b](https://claude.ai/artifact/AungjMxiy48xkpqZQscryx). Each drum from round 1 beside its EMBER
replacement, EMBER's kick open and at three cutoffs beside round 1's, kick, snare and hat slices with their takes,
every pad printed and dry with its takes and zones, and the groove under the kit's clock, straight and humanised.

```sh
./gradlew --no-daemon :synth:generateStaggerRound1b         # → testkit/stagger-round1b/ (gitignored)
python3 scripts/stagger_page/build_page.py testkit/stagger-round1b OUT_DIR
```

Measured on round 1b (`StaggerKitsTest`): the crack snare's centroid 3.59 kHz over its first 120 ms (round 1's
4.19; the references' median 3.2); the groove's hats 28.4 dB under the mix (round 1's 30.2; the references' median
23); 95 percent of the groove's energy under 579 Hz (round 1's 442; the references 309 to 635). EMBER's kick carries
4.0 percent of its energy in 150 to 400 Hz with or without the clipper (its shell already puts it there), so on an
acoustic kick the muffle's drive is a character choice, a squarer thud, and the click still falls 38-fold. The
suite takes about three minutes against round 1's one: EMBER renders at the internal rate with Bessel modes.

**Round 1 built, not heard (2026-11-19).** One kit, CARDBOARD (89 BPM), in the CYPHER shape: fourteen hits, a
timpani roll into the gong on A15, a two-bar groove on A16, three velocity zones of four takes on every pad
(`StaggerKits`, version 1). It is not on the shelf and has no generator proof beyond `StaggerKitsTest`; the
family's other two kits (the cinematic one and the drunkest one) wait for the owner's verdict on this one. The
owner chose the type-beat references as the measuring stick ("Let's try based on the type beats, if needed I'll
get the real things", 2026-11-19).

The listening page (private to the owner's account): [Stagger, round one](https://claude.ai/artifact/B7m8RXy7XvfBec9tgDC15y).
It plays every pad printed and dry, its four takes and three zones, the box kick at three cutoffs beside the
open kick and the filter with no clipper, GLASS at three pitches and four takes, and the groove under nine
clock laws.

```sh
./gradlew --no-daemon :synth:test --tests '*StaggerKitsTest'
./gradlew --no-daemon :synth:generateStaggerRound1          # → testkit/stagger-round1/ (gitignored)
python3 scripts/stagger_page/build_page.py testkit/stagger-round1 OUT_DIR   # the page, from the manifest
```

## What is new on the kit side

- **MUFFLE** (`StaggerKits.muffled`): the strike through a soft clipper and then two one-poles at the cutoff.
  The clipper first, because a filter can only remove: it makes the kick's harmonics at two, three and four
  times the root, the filter takes the click and the top, and the harmonics under the cutoff survive, which is
  what a phone plays. The Box Kick is THUMP's kick (click and all) at 300 Hz and drive 3; the Low Box Kick is
  SKIN's at 250 Hz; the Dead Tom is SKIN's tom at 400 Hz and drive 2.
- **The drunk clock** (`Cypher.staggered`): one drift shared by every part plus a bias per part. The drift is a
  mean-reverting walk ticked once per eighth (rho 0 is `humanised` with a bias, rho near 1 is `behind`), centred
  over the loop so a part's mean lateness is its bias. CARDBOARD: kick +12 ms, snare -3, hats +8, wobble 10 ms,
  rho 0.4, every number a shape value between the profile's words and the references' tighter backbeats.
- **FOLEY** (`StaggerKits.foley`): the strike sampled at 12 bits and 26.04 kHz with no anti-alias filter
  (`Eras` sp1200) and *then* played slow (`Speed`), a sampler's order, which the rack cannot express (PITCH runs
  before CRUNCH there). The converter's images sit near 18 kHz and come down with the pitch. A foley pad skips
  the kit's CRUNCH so its grain is not doubled at a second rate. Timpani (SKIN's tom at the bottom of its range,
  -5), Dark Gong (FIRED BRONZE's kempul, -5), Zap (TINES, -7), Punch (-5).
- **GLASS** (`StaggerKits.glass`): a pane breaking, which no engine made: 28 modal shards, three modes each
  between 2.2 and 8.8 kHz ringing 20 to 220 ms, the first three inside 2 ms and the rest scattered over about
  120 ms, every shard drawn from the take's seed. Not on CARDBOARD; on the page for the cinematic kit's sake.
- **PUNCH** (`StaggerKits.punch`): a comic-book punch, a rising noise whoosh whose band falls from 3 kHz to
  550 Hz, landing on a short high thud 110 ms later. The pad's onset is the swing.
- **The groove is played in a room** (`LooseCrateKits.Room`, reused rather than copied; the family's `played`
  adds the clock, a drummer's kick dynamics and the snare wires under every kick), because the owner heard a
  dry sum of isolated hits as "a bit drum machine like" on LOOSE CRATE.

The kit print (`StaggerKits.BOX`): CRUNCH at 12 bits and a 26.0 kHz hold, TAPE light and worn, VINYL light under
the phrases only.

## Gates

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: drum kits |
| Drums: multiple distinct genre kits on the shelf | Pass in software: three kits under SKINS, held apart by `StaggerKitsTest`; the owner has heard CARDBOARD's drums (round 1b, "New sounds are better"), not the three kits as kits |
| Percussion: roll/riser | Built: a timpani roll, a timpani riser into the crash gong, a dragging snare roll, one per kit, in the room; not heard |
| Velocity and round robin: rendering | Pass in software: every soft zone a different strike from the hard one and no brighter; every zone holds four different strikes (`StaggerKitsTest`) |
| Velocity and round robin: saving, playback, export | Pass in software: `StaggerKitTest` and `:shell:generateStaggerKits` (all three kits, 192 slices each: saved PCM, reload, the pad resolver walking the takes, MPC 3 track, SFZ, DecentSampler). MPC 2 is not written (the owner's format limit). Not played on a phone or an MPC |

## What was measured while building (Kotlin, `StaggerKitsTest`, this cloud JVM)

| Claim | Measured |
|---|---|
| The muffled kick keeps the band a phone plays | 1.56 percent of its energy in 150 to 400 Hz; the same filter with no clipper 0.98 percent |
| The muffle removes the click | above 1 kHz in the first 40 ms (tapered window): the open kick 1.6e-4 of its energy, the muffled 3.0e-6, a 55-fold drop |
| Both kicks and the dead tom keep the band through the print | over 1 percent each |
| The crack snare is a crack | centroid 4.19 kHz over its first 120 ms (the references 1.3 to 5.3, median 3.2; the bare SKIN snare read 10.8 kHz, so it got a 4.5 kHz contact and its sizzle came down) |
| The groove's hats | the 6 to 14 kHz band at its loudest 30.2 dB under the full band (the references 16 to 35, median 23): at the whisper end. At a quarter of the written weights it measured 35; the converter's TONE at .45 (a 4.4 kHz low-pass) alone had taken the band out |
| The groove's mix is dark | 95 percent of its energy under 442 Hz (eight references under 309 to 635) |
| The clock does what its numbers say | on a sixteen-bar pattern: every part's mean within 1 ms of its bias, the kick 15 ms behind the snare, hats' spread 10.2 ms for a 10 ms wobble, lag-one autocorrelation 0.46 for rho 0.4 (CYPHER's `humanised` -0.32); the same seed gives the same offsets |
| GLASS deals panes | four takes at least 0.5 apart in shape (a copy is 0); the same take twice is byte-identical; 0.49 s at -5 |
| Every pad | the same bytes twice; finite; levelled to 0.92 at every zone's first take; a soft hit a different, no brighter strike; four different takes in every zone; every hit under 1.5 s, both phrases over it; the groove bar-exact and closing on itself |

Nothing here is a listening claim.

## Disclosed, not fixed

- **The hats sit at the whisper end of the references' range** (30 dB under against a median of 23). Raising
  them from a quarter to their written weights moved the band from 35 to 30 dB; the converter, the tape and the
  bus set the rest. The owner's ear decides whether they come up further.
- **The gong's soft takes** needed a nudge of tuning and touch to differ (0.0026 apart without it, under the
  family's 0.005), as the LOOSE CRATE doum did.
- **The clipper in MUFFLE runs at the output rate**, not 4x: its harmonics on a kick under 100 Hz sit far below
  Nyquist, and the kick is normalised before it.
- **A foley pad's length** is capped at 1 s before the pitch move (1.33 s at -5), under the loop floor by
  construction; a longer gong or timpani would file as LOOP.
- **Not on the shelf, no generator proof.** Save, playback and MPC 3 export are round 2's work, after the listen.

## Why it is built this way

- The kick is clipped before it is filtered, because a filter can only remove and a bare sine at 52 Hz is
  silent on a phone. Remove the clipper and the phone band falls from 1.56 to 0.98 percent (measured).
- The clock is one shared drift plus a bias per limb: independent draws put the kick and the hat on the same
  beat on different sides of it, which the ear reads as a mistake. The references showed no drift, so rho is
  low by default and the page plays 0, 0.4 and 0.75.
- The foley is converted first and pitched second on the kit side, because the rack's order is right for
  everything else and pinned by goldens.
- The groove goes through LOOSE CRATE's room law rather than a copy of it, because a private helper copied
  between kit files is the roster's recurring defect.
- The one risky decision is the cutoff: 250 Hz is the profile, 400 Hz is safer on a phone. The page plays both.
