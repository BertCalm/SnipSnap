# The breakcore family: SPLINTER, SHATTER, SOUNDCLASH and REDLINE

Status: three new kits are built in Kotlin and on the shelf, right after SPLINTER; every software gate
passes. The owner auditioned all three kits' grooves and pads on 2026-11-18 and kept them as built
("These are great"). Per-pad verdicts, phone playback and MPC playback are open.

## What the family is

Breakcore is a break cut up too fast, a sound system left on, and a kick turned into a bassline. One kit
cannot hold all of that, so each kit owns one job, and none reuses another's pad with an effect turned up.
All four kits share one shape: sixteen voices on A01 to A16, an authored roll on B01 and a riser on B02
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

## The gestures

- SHATTER: play the slices in order for the break, out of order for a re-sequence; B01 repeats one slice; B02 rushes the break.
- SOUNDCLASH: Sub Drop, then Siren Rise, then Rewind, then Slam Kick with an Echo Clap throw behind it.
- REDLINE: kicks A01 to A04 are one bassline; Snare Roll then Scream Rise into the drop.

## Gates (each kit)

| Gate | Status | Evidence |
|---|---|---|
| Melodic voices, keygroups, chords; HOLD and STRUM | Not applicable | Drum kits; finite hits. |
| Percussion: authored roll and riser | Pass in software | B01 and B02 on every kit. |
| Drums: genre kit with authored roles | Pass in software | Own pad map, own tempo, own sources; the family has four distinct ids and tempos (`BreakcoreFamilyTest`). |
| Drums: multiple genre kits | Met by the family | Four kits on the shelf, each its own job. |
| Velocity: rendering | Pass in software | Every pad: peaks rise with force, the normalised shape changes, zero force is silent (`BreakcoreFamilyTest`). |
| Round robin: rendering | Pass in software | Every pad's takes 2 and 3 differ from take 1 (`BreakcoreFamilyTest`). |
| Saving and playback | Pass in software | All 162 cells per kit: saved recipe, rerender, PCM on disk within 3 steps of 24-bit, `PadHit` selection with the wrap to take 1. Frozen takes change playback; where a kit has a choke, unchoked hats change playback (`BreakcoreDeliveryTest`). |
| Export: SFZ | Pass in software | 162 regions per kit, re-imported; PCM and selection match. |
| Export: MPC 3 | Pass in software | Three layers per pad, cycle length 3, packed PCM read back, with the groove clip. |
| Export: MPC 2 | Not applicable | The 2026-11-14 ruling: velocity layers with round robin are judged on MPC 3. No MPC 2 archive. |
| Shelf | Pass in software | Each id in `StarterKits.ALL` and once in the skins group, in the order SPLINTER, SHATTER, SOUNDCLASH, REDLINE. |
| Determinism | Pass | `DeterminismTest` canary per kit; recipes replay to the sample. |
| Sound | **Grooves and pads heard, kept** | Owner, 2026-11-18. Per-pad verdicts not given. |
| Phone and MPC playback | **Not verified** | No device has loaded them. |

**Overall: incomplete.** Software gates are in place; per-pad verdicts and the hardware gates are the owner's.

## Verified, measured, heard

- **Verified** (read in this tree): `Breakcore`, `BreakcoreKit`, `Shatter`, `Soundclash`, `Redline`, `Splinter`
  (now implements `BreakcoreKit`; its output is unchanged), `StarterKits`, `StarterShelf`, `PadHit`, `KitPreview`,
  `SfzWriter`, `Mpc3Exporter`. SPLINTER's delivery was generalised into `BreakcoreDelivery`; `SplinterDelivery`
  is now that delivery with SPLINTER's spec, and `SplinterDeliveryTest` and its manifest keys are as before.
- **Measured** (tests, on a Mac): every pad's full-force peak is between 0.25 and 1.0, mean under 0.005, last
  sample zero, soft below medium below hard (`BreakcoreFamilyTest`); the three delivery runs each prove 162 cells.
- **Proposed** (shapes, no listen behind them): every drive, gain, grain size and pad level; the grooves are
  the ones the owner auditioned, rebuilt as eight-bar-or-longer MPC clips that use every pad.
- **Heard:** the owner auditioned all three kits' grooves and pads on 2026-11-18 and kept them as built ("These
  are great"). Per-pad verdicts, phone playback and MPC playback remain open.

## Reproduce

```
./gradlew --no-daemon :shell:generateShatterKit      # testkit/shatter-breakcore/
./gradlew --no-daemon :shell:generateSoundclashKit   # testkit/soundclash-breakcore/
./gradlew --no-daemon :shell:generateRedlineKit      # testkit/redline-breakcore/
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.BreakcoreFamilyTest'
./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.BreakcoreDeliveryTest'
```

Each generator writes `audio/<kit>-groove.wav`, `audio/<kit>-touch.wav`, `audio/<kit>-pad-reel.wav`, one WAV per
pad, and a Native, SFZ and MPC 3 archive.
