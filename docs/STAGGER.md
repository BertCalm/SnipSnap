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

**Round 3c built (2026-11-20): "Dark Gong sounds like the timpani."** It did, by measure. The Dark Gong was FIRED
BRONZE's kempul played five semitones down: its fundamental sat at 72 Hz against the timpani's 78, its overtones lined
up almost like a drum's (73, 146, 200, 293 Hz), nothing sounded above 300 Hz, and its upper band fell 7.6 dB after the
strike, as a drum's does. Nothing about it said gong.

The Dark Gong is now a modal gong (`StaggerKits.modalGong`, `StaggerKits` version 8), built on the three things that
tell a gong from a drum: a hum that beats, partials that do not line up, and upper partials that bloom after the
strike. The first draft was still a hum (2 percent above 300 Hz): a two-pole resonator's output grows as its pitch
falls, so the upper modes' gains are now scaled by their ratio, and the mallet narrowed from 3.5 to 2 ms on a full hit.

| Hard strike | New Dark Gong | Old Dark Gong | Timpani |
|---|---|---|---|
| Strongest partial | 120 Hz | 72 Hz | 78 Hz |
| Share of the first half-second above 300 Hz | 7.5 percent | 0 | 0.2 |
| Its 250 Hz to 2 kHz band, 50 to 250 ms after the strike against the first 50 ms | +5.1 dB | -7.6 | -2.2 |

A soft strike blooms 0.3 dB and keeps nothing above 300 Hz: darker, not only quieter. `StaggerKitsTest` holds the gong
at least 1.4 times the timpani's pitch, with at least five times its share above 300 Hz and blooming at least 3 dB
above 300 Hz (measured in the test: 120 Hz against 79, 7.7 times, +4.4 dB against the timpani's -2.7). Every other pad
is round 3b's.

The round 3c listening page (private to the owner's account): [Stagger, round three c](https://claude.ai/artifact/FzLjsYKXkXXxK9JEKeVy65). Each kit's
Timpani and Dark Gong as round 3b shipped them and as this round does, then every pad. `:shell:generateStaggerKits`
verified the three kits again (169, 169 and 146 s on this machine).

**Round 3b built (2026-11-20): "It just sounds flat, muffled, and muddy."** Measured on our own drums, one change at a
time, because a reference figure taken from full mixes had misled round 3 (below).

- **The snare was the mud.** On CARDBOARD's and MATINEE's backbeats, 65 to 81 percent of the snare's first 120 ms sat
  in 250 to 600 Hz: the head (TUNE .50, DAMP .45) rang for a quarter second, and its crack was filtered three times
  over (a 5.5 kHz contact filter, the converter's TONE, and a -3 dB shelf at 1.2 kHz on every groove). The references'
  snares, found by an onset detector in the mix, hold a median 16 percent there; their basslines can only push that
  figure up, so the comparison is safe in this direction. STUMBLE's backbeat is a rimshot and read 19. Round 3 made it
  more exposed by bringing the kick down and the hats to a whisper around it.
- **Round 3's kick knock boomed.** It put 41 to 43 percent of a printed kick's energy in 100 to 250 Hz. Round 3 had
  aimed its 150 to 400 Hz band at the references' median 17 percent, but that figure came from full mixes, basslines
  and samples included; on its own the kick needed far less.

Swept through the print before choosing: on the snare the wires are the biggest lever (BUZZ .60 to 1 alone takes the
250 to 600 Hz share from .58 to .38), then the damping (the head stops in 150 ms and the attack over the body rises
from 5.5 to 8 dB), then less WARMTH and an open contact filter; it levels off near .32, where EMBER's head keeps its own
body. On the grooves, the dark shelf was the other big lever.

The change (`StaggerKits` version 7): the crack snare at TUNE .65, DAMP .90, WARMTH .35, a 12 kHz contact filter and
wires that follow the stroke (`emberWired`: BUZZ 1 on a full hit, .40 at rest, so a soft hit stays darker than a hard
one); the stage snare the same law a little lower and longer; every kick's knock lower, damped to the stop, cut at
350 Hz and level with the sub; no dark shelf on any groove; the hats .60 on CARDBOARD and .55 on MATINEE.

Measured on the exported pads (round 3 in brackets):

| Claim | CARDBOARD | MATINEE | STUMBLE |
|---|---|---|---|
| Backbeat, share of its first 120 ms in 250 to 600 Hz (the references a median 16 percent) | 44 percent (81) | 24 (70) | 15 (19) |
| Backbeat, spectral centroid | 1,388 Hz (550) | 1,935 (684) | 1,117 (933) |
| Backbeat, share above 2 kHz | 29 percent (7) | 45 (11) | 14 (8) |
| Kick pad, share in 100 to 250 Hz (the boom) | 18 percent (43) | 11 (41) | 9 (9) |
| Groove, backbeat over kick at the written beats | -0.8 dB | -2.0 | -1.2 |
| Groove, hat under the backbeat | 13.8 dB | 10.5 | no hat |
| Groove, 95 percent of the energy under | 2.90 kHz (994 Hz) | 3.48 (2.24) | 3.96 (1.79) |

The snare's own attack over its body rises from about 5.5 to 8 dB. `StaggerKitsTest` now holds every kit kick at 5 to
20 percent in 150 to 400 Hz (the sub alone .02, round 3's .30 to .48) and the grooves' ceilings under the brightest
references' 4.4 kHz; the backbeat balance, the hat level, the dryness, the press and the velocity law hold as before.
CARDBOARD's backbeat still carries more body than the references' median: EMBER's head keeps about a third of a struck
snare's energy there however it is damped, and going further would mean a different snare.

The round 3b listening page (private to the owner's account): [Stagger, round three b](https://claude.ai/artifact/PkQ7vzV7hJZ7LehQeHK5kv). Each kit's
kick, backbeat drum and groove from round 2, round 2d, round 3 and this round side by side, then every pad as it ships.
`:shell:generateStaggerKits` verified the three kits again (196, 192 and 164 s on this machine).

**Round 3 built (2026-11-20): "The reverb is gone. Now I think the drums are not being hit hard enough. It sound like
light taps."** Measured before changing anything. Not the cause: the gate (a snare without it has the same crack-over-body
shape: 10 dB down 50 ms in, body 1.6 dB under its attack), the velocity (EMBER's stroke at .95 and 1 are the same hit),
the takes (within 2 dB), the press (the sustain compressor at any AMOUNT moved a dry snare's crest 0.4 dB and its body
0.5), the density (the grooves' loud frames and whole-loop RMS sit where the references' do), or the snare itself (its
decay, crest and spectrum sit inside the references' ranges). Two things measured off, both against the references:

- **The kicks were subs.** Dry through the print, 98 percent of a kick's energy sat under 150 Hz and 2 percent in
  150 to 400 Hz, at any TUNE, BUZZ or DAMP, and the muffle's cutoff was not what kept it there (opened to 1 kHz it
  passed 0.2 percent above 400). The references' kicks put a median 17 percent of their energy in 150 to 400 Hz
  (quartiles 9 and 32) over the 300 ms after the hit: the beater's knock and the shell, the "box" in "boxy". On a
  phone, which plays nothing under about 150 Hz, round 2d's kick was a tap.
- **The snare sat under the kick.** In every groove the backbeat's loudness (50 ms RMS at beats 1 and 5, where no
  kick rings under it) sat 8 to 14 dB under the kick's (beats 0 and 4): a muffled sub is dense (crest 2.6 dB over
  50 ms) and a snare's crack is not (11). The written weights could not fix it: the touch law floors every note's
  peak at .35, so a kick weight of .18 still plays at -6.6 dB. The references point the same way but only roughly:
  an onset detector (the references carry no note list) puts their snare a median 7 dB over their kick, and the
  same detector on round 2d's own loops read 0 to 14 dB over where the direct measure at the written beats read
  8 to 14 under, so the reference figure is a direction, not a target.

Two more things came out while fixing those. **The hats were as loud as the snare**: at their written weights a
closed hat peaked at 74 percent of full scale and, being noise (crest about 6 dB over 50 ms), its loudness sat
within a decibel of the backbeat's on every eighth; the 6 to 14 kHz measure had called it a whisper only because the
print's TONE had darkened it. **The groove bus presses the kick back up**: the sustain compressor at about 5:1 took a
16 dB kick cut and gave 6.5 dB of it back through MATINEE's bus at .35 (2 dB at .20), which is why the first kick
levels tried moved the balance a third as far as written.

The change (`StaggerKits` version 6): **BOX**, every kit kick is its muffled sub 3 dB under a damped rack tom with
nothing above 450 to 600 Hz (a sampled kick with a sub under it, the way a chopped record's kick is layered); **the
kick level with the snare**: `Cypher.phraseRaw` takes a per-part gain after the touch law and each kit's room carries a
kick level (`Room.kickDb`: CARDBOARD -12 under its bus at .40, MATINEE -16 under a bus lightened to .20, STUMBLE -16
under its bus at .50; LOOSE CRATE's rooms 0, untouched), chosen from a sweep of bus amount against kick level so the
backbeat lands within 3 dB of the kick; **the hats at a whisper** (`hatLevel` .40 on the two kits with hats, so the
hat sits 10 dB or more under the backbeat); and **STAGGER's own touch law** (`StaggerKits.touch`, floor .12 against
CYPHER's .35) so the written ghosts, shakers and wires are quiet. The low and stage kicks' DAMP rises a little (.70
to .85, .45 to .60). The press, the gate, the clock, the snares and every other pad are as round 2d left them.

Measured (`StaggerKitsTest`, round 2d in brackets):

| Claim | CARDBOARD | MATINEE | STUMBLE |
|---|---|---|---|
| Kick, share of energy in 150 to 400 Hz (box kick, stage kick, hum kick; the low box kick 0.31) | 0.48 (0.02) | 0.39 | 0.30 |
| Kick, share above 400 Hz / above 1 kHz in the first 40 ms | 0.004 / 0.0001 | 0.005 / 0.0002 | 0.002 / 0.0000 |
| Groove, backbeat over kick at the written beats | +0.9 dB (about -10) | -0.8 dB (about -10) | -2.8 dB (about -14) |
| Groove, hat under the backbeat | 18.6 dB (about 1) | 11.8 dB | no hat |
| Groove, 95 percent of the energy under | 994 Hz (415) | 2.24 kHz (521) | 1.79 kHz (290) |
| Groove, quiet tenth under the loudest frame | 56.1 dB (49.3) | 51.5 dB (46.7) | 51.2 dB (43.3) |

The test holds the backbeat between 4 dB under the kick and 10 over it, the hat at least 10 dB under the backbeat on
the two kits with hats, every kit kick at least 9 percent in 150 to 400 Hz with no more than 3 above 400 and no click,
and the grooves' ceilings under 2 kHz (MATINEE, whose glass and zap carry more of its groove now, under 3). The
snares, the press, the dryness and the clock measure as round 2d did. The round 3 listening page (private to the owner's account):
[Stagger, round three](https://claude.ai/artifact/NapB2yLz7rheK6dUwU8U8J). Each kit's kick, backbeat drum and groove from round 2, round 2c, round 2d and
this round side by side, then every pad as it ships. `:shell:generateStaggerKits` verified the three kits again
(151, 140 and 127 s on this machine). On the exported pads the hits' median crest reads 11.7, 10.9 and 9.2 dB and the grooves
17.6, 13.9 and 14.1 (round 2d: 11.7, 10.9, 9.2 and 15.0, 14.3, 9.9).

**Round 2d built (2026-11-20): "It sounds like room reverb or something like that."** The owner heard round 2c and
named the room. It was EMBER's: every EMBER drum carries the club's response at any DISTANCE (its direct-to-reverberant
ratio runs from 16 dB at DISTANCE 0 to -4 dB at 1), so each STAGGER hit was printed with a tail before the kit's ROOM
added its own, and the grooves played those tailed hits through a second room on top. Round 2d (`StaggerKits` version
5) dries every EMBER source: DISTANCE 0, then a HUSH gate (FLOOR .5, CLOSE .5) closes on what the club still leaves,
and the grooves take no ROOM, only the bus press and the shelf. The timpani keep their club (a timpani roll in a dry
box is not a timpani roll); everything else is a drum in a dead room.

Measured (`StaggerKitsTest`, the dryness test): every backbeat drum sits 35.6, 37.5 and 44.5 dB under its own peak at
150 to 250 ms (CARDBOARD, MATINEE, STUMBLE) and is silence at 300 to 500 ms, where the test asks at least 50 dB; every
groove's quiet tenth sits 49.3, 46.7 and 43.3 dB under its loudest frame (round 2c: 24 to 25; the test asks at least
30, so a room cannot come back unnoticed). With the room gone the grooves' ceilings fall to 415, 521 and 290 Hz (round
2c: 579, 636, 495), and the hats read 28, 28 and 30 dB under the mix. The light press stays on the hits (the snares'
crest holds at 10.9, 12.0 → 11.8 and 14.0 → 12.5 dB; a dry snare gives the peak match nothing to re-level against, so
its crest can only hold or fall, which is what the test now asks).

The round 2d listening page (private to the owner's account): [Stagger, round two d](https://claude.ai/artifact/QNpWdiFNsMQG8j1Wb4Bjz6). Each kit's kick,
backbeat drum and groove from round 2 (the room, no press), round 2c (the room, pressed) and round 2d (dry) side by
side, then every pad as it ships. `:shell:generateStaggerKits` verified the three kits again (196, 202 and 162 s on this
machine). On the exported pads (peak against 200 ms RMS) the hits' median crest reads 11.7, 10.9 and 9.2 dB and the grooves
15.0, 14.3 and 9.9 (round 2c: 11.2, 9.7, 8.1 and 12.3, 12.1, 9.1).

What to listen for: these kits are now very dry, which is the muffled-kick, cracking-snare profile, and also the way
round 1's drums read as "drum machine sounds". If the dry kits read that way again, the next move is a short, dark
room on the grooves only (not on the hits), or the owner's own chosen DISTANCE, measured the same way.

**Round 2c built (2026-11-20): "It made it echoy."** The owner heard round 2b's press as echoy. The sustain compressor
presses the body and the peak match lifts everything under it, and under an EMBER drum that is the club's response
baked into every hit: measured, round 2b's press lifted the snares' tails 12 to 14 dB and CARDBOARD's groove's quiet
moments 11 dB (from 25 dB under its loudest to 15), the room coming forward, which the first round's measurements had
recorded as "the tail comes up" and read as glue. Round 2c (`StaggerKits` version 4) presses the hits with a fast,
light press instead (`PRESS`: ATTACK .15, RECOVER .20, AMOUNT .25), measured on the kick, the snare and the groove
before choosing, and the grooves take no second press: their room's bus is their press.

Measured (`StaggerKitsTest`): the snares' crest falls 0.7, 0.9 and 2.3 dB (CARDBOARD, MATINEE, STUMBLE) and their tails
rise 4.2, 2.9 and 6.2 dB (round 2b: 11.8, 7.9 and 17.4); the kicks' crests rise 0.9 to 1.4 dB and their tails move
within 1.1 dB; every groove's quiet tenth sits 24 to 25 dB under its loudest, where round 2's did (round 2b: 15), and the
grooves' ceilings are round 2's again (579, 636 and 495 Hz). The test now bounds a snare's tail lift to 2 to 8 dB and
holds every groove's quiet tenth at least 20 dB under its loudest, so the echo cannot come back unnoticed.

The round 2c listening page (private to the owner's account): [Stagger, round two c](https://claude.ai/artifact/6YhwBR1yvdaSazJGY1hJf4).
Each kit's kick, backbeat drum and groove from round 2 (no press), round 2b (echoy) and round 2c side by side, then
every pad as it ships. `:shell:generateStaggerKits` verified the three kits again (198, 205 and 164 s on this machine).
On the exported pads (peak against 200 ms RMS) the hits' median crest reads 11.2, 9.7 and 8.1 dB (round 2: 11.0, 9.8,
9.1; round 2b: 10.7, 10.1, 8.0) and the grooves 12.3, 12.1 and 9.1, round 2's exactly, since the print no longer
presses them.

**Round 2b built (2026-11-19): "maybe we need a bit of compression."** The owner heard the round-2 page and asked for a
bit of compression. The single pads had none (only the grooves went through a bus), so every kit's print now presses
its pads between the converter and the tape (`StaggerKits.PRESS`: COMPRESSOR at AMOUNT .40, ATTACK .25, RECOVER .45,
the same on all three kits), the way a chopped record's drums were pressed at the mix; the peak match after it brings
the tail and the room back up. A foley pad is pressed like every pad; the grooves keep their bus on top. Kit version 3.

Measured before choosing (`StaggerKitsTest` prints it): the sustain compressor at a slow attack lets the hit through
and presses the body under it, so the first settings *raised* CARDBOARD's snare crest from 10.0 to 11.8 dB and its
kick's from 3.6 to 8.8. At a fast attack (about 1 ms) the three snares fall 0.3 to 2.9 dB in crest and their tails
(300 to 500 ms against the first 50 ms) come up 8 to 17 dB; the kicks' tails come up 0.2 to 4 dB and their crests rise
1.6 to 2.2 dB, a muffled kick being already dense (crest 1.3 to 3.6 dB), and a humming kick giving the press nothing to
lift. The test holds those claims. On the exported pads (Python, peak against 200 ms RMS): the hits' median crest moved
11.0 → 10.7 (CARDBOARD), 9.8 → 10.1 (MATINEE) and 9.1 → 8.0 dB (STUMBLE), their loudest-peaked pads 16.5 → 13.8, 18.7 →
16.9 and 14.6 → 16.5, and the grooves 12.3 → 10.8, 12.1 → 10.3 and 9.1 → 8.0 dB. The press lifts every groove's 95 percent
ceiling (579 → 658, 636 → 801, 495 → 950 Hz) as the tails and the room come up; the test's limits moved with the reason
beside each (700, 1500, 1000). The hats sit 27, 26 and 25 dB under the mix (round 2: 28, 29, 29; the references' median 23).

The round 2b listening page (private to the owner's account): [Stagger, round two b](https://claude.ai/artifact/CSeGgyNZd8nZ5qWfFXHXBz). Each kit's kick,
backbeat drum and groove before and after the press, then every pad as it ships. `:shell:generateStaggerKits` verified
all three kits again (built in 179, 184 and 151 s on this machine).

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
  -5), Dark Gong (the modal GONG below, -5; FIRED BRONZE's kempul until round 3c), Zap (TINES, -7), Punch (-5).
- **GONG** (`StaggerKits.modalGong`, round 3c): a hanging gong as modes, because FIRED BRONZE's kempul pitched down
  measured as a second timpani. A hum that beats (two modes 1.2 percent apart, about 1.4 Hz after the print),
  partials that do not line up (1.47, 2.03, 2.71 up to 9.1 times the hum), and upper partials that bloom over
  120 ms after the strike. A soft strike is a wider mallet with less bloom: darker, not only quieter.
- **GLASS** (`StaggerKits.glass`): a pane breaking, which no engine made: 28 modal shards, three modes each
  between 2.2 and 8.8 kHz ringing 20 to 220 ms, the first three inside 2 ms and the rest scattered over about
  120 ms, every shard drawn from the take's seed. Not on CARDBOARD; on the page for the cinematic kit's sake.
- **PUNCH** (`StaggerKits.punch`): a comic-book punch, a rising noise whoosh whose band falls from 3 kHz to
  550 Hz, landing on a short high thud 110 ms later. The pad's onset is the swing.
- **The groove is played in a room** (`LooseCrateKits.Room`, reused rather than copied; the family's `played`
  adds the clock, a drummer's kick dynamics and the snare wires under every kick), because the owner heard a
  dry sum of isolated hits as "a bit drum machine like" on LOOSE CRATE. Round 2d took the ROOM itself off
  (heard as "room reverb"); the bus, the shelf, the dynamics and the wires stay.
- **BOX** (`StaggerKits.boxed`, round 3): a kit kick is its muffled sub 3 dB under a damped rack tom with
  nothing above 450 to 600 Hz (`knockOf`): a sampled kick with a sub under it. EMBER's kick alone is a sub (98
  percent under 150 Hz), and a phone plays nothing under about 150. Round 3b shortens and lowers the knock (DAMP
  .95, cut at 350 Hz, level with the sub), because round 3's boomed.
- **The kick level with the snare** (`Room.kickDb`, `Cypher.phraseRaw`'s per-part gain, round 3): the grooves' kicks
  sit 12 to 16 dB under the rest of the kit (the bus gives part of that back), because round 2d's grooves put the
  snare 8 to 14 dB under the kick and the references put it roughly over. STAGGER's own touch law (`StaggerKits.touch`, floor .12 against
  CYPHER's .35) lets the written ghosts, shakers and wires be quiet.

The kit print (`StaggerKits.BOX`): CRUNCH at 12 bits and a 26.0 kHz hold, TAPE light and worn, VINYL light under
the phrases only.

## Gates

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: drum kits |
| Drums: multiple distinct genre kits on the shelf | Pass in software: three kits under SKINS, held apart by `StaggerKitsTest`; the owner has heard CARDBOARD's drums (round 1b, "New sounds are better"), not the three kits as kits |
| Percussion: roll/riser | Built: a timpani roll, a timpani riser into the crash gong (the timpani keep their club), a dragging snare roll (dry, round 2d), one per kit; not heard |
| Velocity and round robin: rendering | Pass in software: every soft zone a different strike from the hard one and no brighter; every zone holds four different strikes (`StaggerKitsTest`) |
| Velocity and round robin: saving, playback, export | Pass in software: `StaggerKitTest` and `:shell:generateStaggerKits` (all three kits, 192 slices each: saved PCM, reload, the pad resolver walking the takes, MPC 3 track, SFZ, DecentSampler). MPC 2 is not written (the owner's format limit). Not played on a phone or an MPC |

## What was measured while building (Kotlin, `StaggerKitsTest`, this cloud JVM)

| Claim | Measured |
|---|---|
| The muffled kick keeps the band a phone plays | 1.56 percent of its energy in 150 to 400 Hz; the same filter with no clipper 0.98 percent |
| The muffle removes the click | above 1 kHz in the first 40 ms (tapered window): the open kick 1.6e-4 of its energy, the muffled 3.0e-6, a 55-fold drop |
| Both kicks and the dead tom keep the band through the print | over 1 percent each |
| The crack snare is a crack | centroid 3.61 kHz over its first 120 ms (round 2d, dry; 4.19 with the club) (the references 1.3 to 5.3, median 3.2; the bare SKIN snare read 10.8 kHz, so it got a 4.5 kHz contact and its sizzle came down) |
| The groove's hats | the 6 to 14 kHz band at its loudest 28.4, 27.9 and 30.3 dB under the full band (round 2d, dry; the references 16 to 35, median 23): at the whisper end. At a quarter of the written weights round 1 measured 35; the converter's TONE at .45 (a 4.4 kHz low-pass) alone had taken the band out |
| The groove's mix is dark | 95 percent of its energy under 415, 521 and 290 Hz (round 2d, dry; eight references under 309 to 635) |
| The drums are dry (round 2d) | every backbeat drum 35.6, 37.5 and 44.5 dB under its peak at 150 to 250 ms and silent at 300 to 500; every groove's quiet tenth 49.3, 46.7 and 43.3 dB under its loudest frame |
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
