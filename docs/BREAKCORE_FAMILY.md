# The breakcore family: SPLINTER, SHATTER, SOUNDCLASH, REDLINE, LULL, STROBE, CARTRIDGE and BARRICADE

Status: seven new kits are built in Kotlin and on the shelf, right after SPLINTER; every software gate
passes. The owner auditioned the first three kits' (SHATTER, SOUNDCLASH, REDLINE) grooves and pads on 2026-11-18
and kept them as built ("These are great"). The second round (LULL, STROBE, CARTRIDGE, BARRICADE) was auditioned
and approved by the owner as built. Per-pad verdicts, phone playback and MPC playback are open.

## What the family is

Breakcore is a break cut up too fast, a sound system left on, and a kick turned into a bassline. One kit
cannot hold all of that, so each kit owns one job, and none reuses another's pad with an effect turned up.
All eight kits share one shape: sixteen voices on A01 to A16, an authored roll on B01 and a riser on B02
(both class LOOP), three velocities (0.30, 0.65, 1.00) by three takes on every pad (162 cells a kit), no
per-hit levelling, saved as source recipes. Entirely synthesized; no recording is loaded. Like SPLINTER they
are source grids with saved recipes, not engines: no Patch and no picker entry. `BreakcoreKit` is the shared
interface and `Breakcore` holds the shared helpers (mix, finish, saw, filter, drive, echo in beats, spin-back),
the generic saved `Breakcore.Recipe`, and the shelf arrangement.

| Kit | Id | BPM | Job |
|---|---|---|---|
| SPLINTER | `splinter-breakcore` | 200 | the edit: sped-up hits, chops, retriggers, clip ([SPLINTER](SPLINTER.md)) |
| SHATTER | `shatter-breakcore` | 180 | the break: one played break cut at its own hits |
| SOUNDCLASH | `soundclash-breakcore` | 175 | the sound system: subs, reese, siren, rewind, echo |
| REDLINE | `redline-breakcore` | 250 | speedcore: tuned kicks, noise, scream |
| LULL | `lull-breakcore` | 170 | the quiet side: piano, strings, a music box, then a sudden stop |
| STROBE | `strobe-breakcore` | 145 | the 1992 rave: a diving whine, stabs, a sped-up break |
| CARTRIDGE | `cartridge-breakcore` | 190 | chip breakcore: pulse, 4-bit triangle, shift-register noise |
| BARRICADE | `barricade-breakcore` | 165 | digital hardcore: power chords, punk drums, noise |

## SHATTER: the break

`shatter-breakcore`, 180 BPM. The characteristic gesture is **one take, cut at the hits, each slice dragging its neighbours' room**.

One original two-bar break is played once on SKIN drums in one room, through a shared room and a light tape, at a force (the velocity) and a take (a different performance: timing, strike noise, force). Every pad is a window of that one performance, so a kick slice carries the ride wash and snare tail that were sounding when it was cut: what a sampled break is and an isolated hit is not. SPLINTER is the opposite, isolated hits. Softer is a softer performance: darker strikes, a quieter room, less tape drive. The sixteen pads tile the two bars.

| Slot | Pad | Class | Level |
|---|---|---|---|
| A01 | Kick Ride | KICK | 1 |
| A02 | Ghost | SNARE | .6 |
| A03 | Snare Ride | SNARE | .9 |
| A04 | Kick Push | KICK | .9 |
| A05 | Kick Drag | KICK | .85 |
| A06 | Ghost Two | SNARE | .6 |
| A07 | Kick Late | KICK | .9 |
| A08 | Snare Tail | SNARE | .85 |
| A09 | Downbeat | KICK | 1 |
| A10 | Crack | SNARE | .9 |
| A11 | Ghost Ride | PERC | .6 |
| A12 | Flam | SNARE | .75 |
| A13 | Kick Answer | KICK | .9 |
| A14 | Snare Drop | SNARE | .85 |
| A15 | Open Snare | SNARE | .85 |
| A16 | Turnaround | PERC | .85 |
| B01 | Slice Repeat | LOOP | .7 |
| B02 | Break Rush | LOOP | .7 |

There is no mute group: the ride is already in the slices. B01 repeats a slice and B02 is the break rush.

## SOUNDCLASH: the sound system

`soundclash-breakcore`, 175 BPM. The characteristic gesture is **sub drop, siren climb, rewind, slam**.

Sources are the approved Spore kick and snare, SKIN, the rack's Siren, Echo and Spring, and three things built for the kit: sine subs with a pitch fall, a two-saw reese through the house filter, and a spin-back read at 4x so the fast backwards pass folds nothing into the band. Echo repeats are set in beats at 175 BPM, so a throw lands on the grid. The tuned pads sit in F (Sub Low on F1, Sub High on C2, the reese on F1). Velocity drives tone: harder opens the filters, pushes the subs into their harmonics and the siren's grit.

| Slot | Pad | Class | Level |
|---|---|---|---|
| A01 | Slam Kick | KICK | 1 |
| A02 | Sub Drop | TONAL | .85 |
| A03 | Dub Rim | SNARE | .6 |
| A04 | Dub Snare | SNARE | .85 |
| A05 | Reese Stab | TONAL | .7 |
| A06 | Wobble Reese | TONAL | .65 |
| A07 | Siren | PERC | .5 |
| A08 | Laser | PERC | .45 |
| A09 | Horn Stab | TONAL | .55 |
| A10 | Rewind | PERC | .75 |
| A11 | Echo Clap | CLAP | .7 |
| A12 | Tight Hat | HAT_CLOSED | .4, choke 1 |
| A13 | Dub Hat | HAT_OPEN | .4, choke 1 |
| A14 | Sub Low | TONAL | .85 |
| A15 | Sub High | TONAL | .8 |
| A16 | Noise Sweep | PERC | .5 |
| B01 | Dub Roll | LOOP | .7 |
| B02 | Siren Rise | LOOP | .55 |

The two hats share choke group 1.

## REDLINE: speedcore

`redline-breakcore`, 250 BPM. The characteristic gesture is **pitch dive, distorted body held on a note, noise snare, feedback scream**.

The kick is the bassline. It is synthesized: a sine diving from twelve times its note onto the note in about 10 ms, a 3 ms click, then SPLINTER's oversampled clipper. Its body holds a pitch, so four pads are the same kick on F# (root, minor third, fifth, octave) and a pattern across them is a bassline (`BreakcoreFamilyTest` checks each lands on its note). Velocity is drive: a soft hit clips less and is cleaner, not only quieter. Snares, hats and crash are filtered noise through the same clipper; the scream is noise through a nearly self-oscillating band-pass, swept and clipped.

| Slot | Pad | Class | Level |
|---|---|---|---|
| A01 | Terror Kick | KICK | 1 |
| A02 | Kick Third | KICK | .95 |
| A03 | Kick Fifth | KICK | .95 |
| A04 | Kick Octave | KICK | .9 |
| A05 | Short Kick | KICK | .95 |
| A06 | Kick Stutter | KICK | .9 |
| A07 | Noise Snare | SNARE | .8 |
| A08 | Burst Clap | CLAP | .75 |
| A09 | Saw Hat | HAT_CLOSED | .4, choke 1 |
| A10 | Noise Hat | HAT_OPEN | .4, choke 1 |
| A11 | Crash Noise | PERC | .45 |
| A12 | Scream | PERC | .55 |
| A13 | Feedback Stab | PERC | .5 |
| A14 | Bit Kick | KICK | .9 |
| A15 | Gate Noise | PERC | .5 |
| A16 | Tom Drop | TOM | .7 |
| B01 | Snare Roll | LOOP | .7 |
| B02 | Scream Rise | LOOP | .55 |

The two hats share choke group 1.

## LULL: the quiet side

`lull-breakcore`, 170 BPM, in A minor. The characteristic gesture is **something tender, then a sudden stop**.

Piano chords (Am7, Fmaj7, Cadd9, G6: i, VI, III, VII) from Oven FELT, strings and choir held under them from [Spool], a music box, a bell, a cello and a worn tape. The pads that make the track end are Reverse Piano (an inhale), Piano Stop (a tape stop: the piano falls in pitch to nothing), Fading Repeat (a ritardando echo whose repeats slow, get quieter and each choke the one before) and Swell Rise (a swell that is cut on the downbeat). Cents never come from TUNE, which snaps to semitones: a take's tuning is a constant-speed spin of the finished chord. Takes differ by strum order, strum spread, per-note level and those cents. Velocity softens the hammer and lengthens the strum. Every constant awaits a per-pad verdict.

| Slot | Pad | Class | Level |
|---|---|---|---|
| A01 | Piano Am | TONAL | .8 |
| A02 | Piano F | TONAL | .8 |
| A03 | Piano C | TONAL | .8 |
| A04 | Piano G | TONAL | .8 |
| A05 | Strings Am | TONAL | .65 |
| A06 | Choir Am | TONAL | .65 |
| A07 | Box A | TONAL | .5 |
| A08 | Box C | TONAL | .5 |
| A09 | Box E | TONAL | .5 |
| A10 | Bell | TONAL | .55 |
| A11 | Reverse Piano | PERC | .7 |
| A12 | Worn Piano | TONAL | .75 |
| A13 | Cello A | TONAL | .7 |
| A14 | Crackle | PERC | .3 |
| A15 | Piano Stop | PERC | .85 |
| A16 | Glass | TONAL | .45 |
| B01 | Fading Repeat | LOOP | .7 |
| B02 | Swell Rise | LOOP | .6 |

There is no mute group: a chord is meant to ring over the next.

## STROBE: the 1992 rave

`strobe-breakcore`, 145 BPM. The characteristic gesture is **a whine dives, the piano stab answers, a sped-up break runs under both**.

The Whine is a detuned pulse and saw stack that scoops up into its note and dives an octave away; its name is a gesture, not a brand. Around it are a hard bright piano (Oven GRAND), a punchy organ bass (Tonewheel STAB), a saw chord stab, an orchestra hit, a clean bleep over a sub, a voice stab (Glint VOWEL), and a break that was played at 115 BPM and is read at 145, so its pitch rose by about four semitones with its speed (`BreakcoreFamilyTest` checks the bar length and the ratio). Kick and snare are Spore, hats are SKIN. Cents never come from TUNE: a take's tuning is a constant-speed spin of the finished note or a constant ratio on the oscillator's own frequency. Velocity opens the filters, drives harder and deepens the Whine's scoop.

| Slot | Pad | Class | Level |
|---|---|---|---|
| A01 | Whine | TONAL | .75, group 2 |
| A02 | Whine Dive | TONAL | .75, group 2 |
| A03 | Whine Stab | TONAL | .7, group 2 |
| A04 | Rave Piano | TONAL | .8, group 3 |
| A05 | Piano Low | TONAL | .8, group 3 |
| A06 | Organ Bass | TONAL | .75 |
| A07 | Saw Stab | TONAL | .7 |
| A08 | Orchestra Hit | PERC | .85 |
| A09 | Bleep | TONAL | .55 |
| A10 | Bleep Bass | TONAL | .8 |
| A11 | Vox Stab | TONAL | .55 |
| A12 | Rave Kick | KICK | 1 |
| A13 | Rave Snare | SNARE | .9 |
| A14 | Closed Hat | HAT_CLOSED | .4, group 1 |
| A15 | Open Hat | HAT_OPEN | .4, group 1 |
| A16 | Sped Break | LOOP | .75 |
| B01 | Stab Roll | LOOP | .7 |
| B02 | Whine Rise | LOOP | .6 |

Mute groups: the three Whine pads share group 2 and the two piano pads share group 3, so each family plays one note at a time; the hats share group 1. Groups are metadata on the saved kit; they change no sound.

## CARTRIDGE: chip breakcore

`cartridge-breakcore`, 190 BPM. The characteristic gesture is **a console arpeggio, a crushed break smashed through it, noise-channel drums**.

The kit is the sound chip of an 8-bit console, which is four things: pulse duty (12.5, 25 and 50 percent), a stepped 4-bit triangle (16 levels a cycle), a linear-feedback shift register for noise (a long 15-bit mode that hisses and a short 93-step mode that rings pitched, its clock rate setting the pitch), and pitch and volume that move only on the 60 Hz frame, in sixteen volume steps. None of the character is aliasing: the pulse is two polyBLEP saws, and the triangle staircase and the register render at 4x and come down through the house decimator, so the steps stay and nothing folds back (`BreakcoreFamilyTest` counts the levels and the register loops). Velocity is not a chip feature, so it is mapped onto chip settings: the duty, the sweep depth, the noise clock and mode, and the starting volume step. A take varies the register's start state, a few cents and the vibrato phase. Crushed Break is SHATTER's Crack slice cut to 4 bits, held at about 11 kHz and low-passed back.

| Slot | Pad | Class | Level |
|---|---|---|---|
| A01 | Chip Kick | KICK | 1 |
| A02 | Chip Snare | SNARE | .9 |
| A03 | Chip Hat | HAT_CLOSED | .45, group 1 |
| A04 | Chip Open Hat | HAT_OPEN | .45, group 1 |
| A05 | Lead C | TONAL | .7, group 2 |
| A06 | Lead G | TONAL | .7, group 2 |
| A07 | Arp Cm | TONAL | .65 |
| A08 | Arp Ab | TONAL | .65 |
| A09 | Tri Bass C | TONAL | .85 |
| A10 | Tri Bass G | TONAL | .85 |
| A11 | Sweep Up | PERC | .6 |
| A12 | Blip | TONAL | .55 |
| A13 | Explosion | PERC | .8 |
| A14 | Crushed Break | PERC | .8 |
| A15 | Duty Sweep | TONAL | .65 |
| A16 | Echo Lead | TONAL | .65, group 2 |
| B01 | Noise Roll | LOOP | .7 |
| B02 | Arp Rise | LOOP | .6 |

Mute groups: Lead C, Lead G and Echo Lead share group 2, so a lead plays one note at a time; the hats share group 1.

## BARRICADE: digital hardcore

`barricade-breakcore`, 165 BPM, in E. The characteristic gesture is **a power chord through a cranked amp, a punk break, noise where a voice would be**.

The chords are Magnet CHUG notes (E2 B2 E3 and the neighbours, summed with a strum of a few milliseconds) through Valve at MAGNET's landing amp with DRIVE and TONE raised. A risk gate measured that chord: the 1 to 4 kHz band 6 dB under the full band, above 10 kHz 37 dB under, and 3.1 s of sustain above -30 dB (`BreakcoreFamilyTest` checks the spectral centroid is in the guitar band, and that Dive Bomb falls about an octave from the root). Palm Mute is cut by its own envelope. Kick, snare and hat are Spore and SKIN drums driven and lifted at 2.5 kHz; Bullhorn, Pick Slide, Cable Pull, Crash and Noise Wall are synthesized from noise. Cents never come from TUNE: a take's tuning is a constant-speed spin of the summed chord, and a take also moves the strum order and spread, PICK, BLEND and the noise seeds. Velocity is amp drive, a harder PICK and a brighter TONE.

| Slot | Pad | Class | Level |
|---|---|---|---|
| A01 | Power E | TONAL | .8, group 1 |
| A02 | Power G | TONAL | .8, group 1 |
| A03 | Power A | TONAL | .8, group 1 |
| A04 | Palm Mute E | TONAL | .8, group 1 |
| A05 | Dive Bomb | PERC | .7, group 1 |
| A06 | Feedback | PERC | .6 |
| A07 | Pick Slide | PERC | .6 |
| A08 | Punk Kick | KICK | 1 |
| A09 | Punk Snare | SNARE | .9 |
| A10 | Crash | PERC | .5 |
| A11 | Hat | HAT_CLOSED | .45 |
| A12 | Bullhorn | PERC | .7 |
| A13 | Cable Pull | PERC | .6 |
| A14 | Crushed Chord | TONAL | .7, group 1 |
| A15 | Noise Wall | PERC | .6 |
| A16 | Open E | TONAL | .8, group 1 |
| B01 | Chug Roll | LOOP | .8 |
| B02 | Feedback Rise | LOOP | .6 |

Mute group: Power E, Power G, Power A, Palm Mute E, Dive Bomb, Crushed Chord and Open E share group 1, so a guitar plays one sound at a time. The hat has no group.

## The gestures

- SHATTER: play the slices in order for the break, out of order for a re-sequence; B01 repeats one slice; B02 rushes the break.
- SOUNDCLASH: Sub Drop, then Siren Rise, then Rewind, then Slam Kick with an Echo Clap throw behind it.
- REDLINE: kicks A01 to A04 are one bassline; Snare Roll then Scream Rise into the drop.
- LULL: chords with the music box over them, Worn Piano, Fading Repeat and Swell Rise, then Piano Stop on the downbeat and the stop.
- STROBE: Whine Dive, then Rave Piano answering; Sped Break under both; Stab Roll and Whine Rise into the drop.
- CARTRIDGE: an arp (Arp Cm, Arp Ab) over noise-channel drums, Crushed Break, Noise Roll and Arp Rise into the drop.
- BARRICADE: Power E, Power G, Power A with Palm Mute E between, Punk Kick and Snare under them, Chug Roll and Feedback Rise into the drop.

## Gates (each kit)

| Gate | Status | Evidence |
|---|---|---|
| Melodic voices, keygroups, chords; HOLD and STRUM | Not applicable | Drum kits; finite hits. |
| Percussion: authored roll and riser | Pass in software | B01 and B02 on every kit. |
| Drums: genre kit with authored roles | Pass in software | Own pad map, own tempo, own sources; the family has eight distinct ids and tempos (`BreakcoreFamilyTest`). |
| Drums: multiple genre kits | Met by the family | Eight kits on the shelf, each its own job. |
| Velocity: rendering | Pass in software | Every pad: peaks rise with force, the normalised shape changes, zero force is silent (`BreakcoreFamilyTest`). |
| Round robin: rendering | Pass in software | Every pad's takes 2 and 3 differ from take 1 (`BreakcoreFamilyTest`). |
| Saving and playback | Pass in software | All 162 cells per kit: saved recipe, rerender, PCM on disk within 3 steps of 24-bit, `PadHit` selection with the wrap to take 1. Frozen takes change playback; where a kit has a mute group, playing a pair of its pads with the groups removed changes playback (`BreakcoreDeliveryTest`). |
| Export: SFZ | Pass in software | 162 regions per kit, re-imported; PCM and selection match. |
| Export: MPC 3 | Pass in software | Three layers per pad, cycle length 3, packed PCM read back, with the groove clip. |
| Export: MPC 2 | Not applicable | The 2026-11-14 ruling: velocity layers with round robin are judged on MPC 3. No MPC 2 archive. |
| Shelf | Pass in software | Each id in `StarterKits.ALL` and once in the skins group, in the order SPLINTER, SHATTER, SOUNDCLASH, REDLINE, LULL, STROBE, CARTRIDGE, BARRICADE. |
| Determinism | Pass | `DeterminismTest` canary per kit; recipes replay to the sample. |
| Sound | **Grooves and pads heard, kept** | Owner: SHATTER, SOUNDCLASH, REDLINE on 2026-11-18; LULL, STROBE, CARTRIDGE, BARRICADE auditioned and approved in the second round. Per-pad verdicts not given. |
| Phone and MPC playback | **Not verified** | No device has loaded them. |

**Overall: incomplete.** Software gates are in place; per-pad verdicts and the hardware gates are the owner's.

## Verified, measured, heard

- **Verified** (read in this tree): `Breakcore`, `BreakcoreKit`, `Shatter`, `Soundclash`, `Redline`, `Lull`, `Strobe`, `Cartridge`, `Barricade`, `Splinter`
  (now implements `BreakcoreKit`; its output is unchanged), `StarterKits`, `StarterShelf`, `PadHit`, `KitPreview`,
  `SfzWriter`, `Mpc3Exporter`. SPLINTER's delivery was generalised into `BreakcoreDelivery`; `SplinterDelivery`
  is now that delivery with SPLINTER's spec, and `SplinterDeliveryTest` and its manifest keys are as before.
- **Measured** (tests, on a Mac): every pad's full-force peak is between 0.25 and 1.0, mean under 0.005, last
  sample zero, soft below medium below hard (`BreakcoreFamilyTest`); the seven delivery runs each prove 162 cells.
- **Proposed** (shapes, no listen behind them): every drive, gain, grain size and pad level; the grooves are
  the ones the owner auditioned, rebuilt as eight-bar-or-longer MPC clips that use every pad.
- **Heard:** the owner auditioned SHATTER's, SOUNDCLASH's and REDLINE's grooves and pads on 2026-11-18 and kept them
  as built ("These are great"); LULL, STROBE, CARTRIDGE and BARRICADE were auditioned and approved as built in the
  second round. Per-pad verdicts, phone playback and MPC playback remain open.

## Reproduce

```
./gradlew --no-daemon :shell:generateShatterKit      # testkit/shatter-breakcore/
./gradlew --no-daemon :shell:generateSoundclashKit   # testkit/soundclash-breakcore/
./gradlew --no-daemon :shell:generateRedlineKit      # testkit/redline-breakcore/
./gradlew --no-daemon :shell:generateLullKit         # testkit/lull-breakcore/
./gradlew --no-daemon :shell:generateStrobeKit       # testkit/strobe-breakcore/
./gradlew --no-daemon :shell:generateCartridgeKit    # testkit/cartridge-breakcore/
./gradlew --no-daemon :shell:generateBarricadeKit    # testkit/barricade-breakcore/
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.BreakcoreFamilyTest'
./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.BreakcoreDeliveryTest'
```

Each generator writes `audio/<kit>-groove.wav`, `audio/<kit>-touch.wav`, `audio/<kit>-pad-reel.wav`, one WAV per
pad, and a Native, SFZ and MPC 3 archive.
