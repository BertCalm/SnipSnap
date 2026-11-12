# MIRRORBALL

Three 1970s disco kits, every pad printed through an FET compressor, a multi-head tape echo, then an amp.

The characteristic gesture is **dead, clicky strike → FET squeeze → tape repeats in rhythm → the amp pulls
the repeats up behind the hit**.

MIRRORBALL is a kit family, not a SYNTH engine, in the way GATECRASH is. On 2026-11-12 the owner asked for "a
disco drum kit pushed through" the UAFX 1176 Studio Compressor, then the Strymon El Capistan, then an amp, and
attached a research note on the sound of disco drums. The hits are SKIN, THUMP, ONSET and TERRA drums as those
engines already make them, plus a tambourine synthesized from BACKWASH's CC0 recordings. What is new is one
treatment stage, `Mirrorball`, which prints each pad through the owner's chain in the owner's order. The FX rack
cannot do this: `FxChain` runs VALVE before TAPE and ECHO, so there the echoes never reach the amp.

**Status: round 1b, built, not yet heard.** The [audition page](https://claude.ai/artifact/2BN7Peh1FUwrjvHjZZ5YbB) plays every pad
as the app would. The [design specification](superpowers/specs/2026-11-12-mirrorball-disco-kit-design.md) has the
research, the model and each round's measurements.

## The rounds

| Round | What it was | What the owner said |
|---|---|---|
| 0 | Python study: SKIN drums through the chain, gently set ([page](https://claude.ai/artifact/EJ9vFTy9SBbj2cbQJ125oH)) | "Sounds kind of basic and not very punchy or character rich" |
| 0b | Python study: retuned drums, a beater on the kick, a print stage (console EQ, hot tape), the compressor's parallel mode, the amp driven harder; Punch and Hot ([page](https://claude.ai/artifact/RC5kSEXbMUNHUmNvnAhLnN)) | "Punch is pretty good but maybe dialed back like 10-15%. Can we recreate the tambourine with synthesis?" |
| 1 | The app, at round 0's settings. Never heard; superseded before any listen | (none) |
| 1b | The app: Punch at 87.5 percent, the synthesized tambourine | not yet heard |

Measured, round 0 was basic for concrete reasons:
- the kick was nearly a pure sub sine, with no beater click;
- the tom was a pure sine;
- the speaker took 10 to 12 dB off the snare's and hats' top;
- the chain added almost no harmonics.

The specification's "Round 0, heard" section has the numbers.

## The three kits

SKINS shelf:

| Kit | Tempo | What it is | How it is printed and played |
|---|---|---|---|
| MIRRORBALL PHILLY STRINGS | 116 | A dead kick with a beater, a towelled snare, two congas, the synthesized tambourine, rack and floor tom, ride, cowbell, shaker, a ghost snare | Round 0b's Punch itself. The kick is dry; the snare has one quiet slap; the hats, tambourine and shaker gallop on two heads |
| MIRRORBALL LOFT PARTY | 122 | A booming open kick, a clap layered on the snare, a hand clap, quinto, conga, tumba, bongo, two timbales, cowbell, claves | A little hotter than Philly; older, wobblier tape; the percussion on the two longest heads |
| MIRRORBALL MUNICH MACHINE | 128 | An analog kick with a beater and a long kick, an ONSET snare, a clap, four falling synth toms, a sequencer hat, zap, rim | The hottest print and amp; new tape (less wobble, brighter repeats); the sequencer hat on the dotted-eighth head |

Each kit has sixteen pads: fourteen hits, then a fill on A15 and a build on A16. Philly has a tom fill and a
snare build, Loft a timbale fill and a clap build, Munich a synth-tom fill and a snare build. The phrases go
through one chain together, as a drum bus would, so there the kick and the hats share one compressor.

Every pad has three velocity zones (soft, medium, hard) with four takes in each. A softer zone is a softer
strike. It reaches the tape, the compressor's fixed threshold and the amp lower, so it saturates less, is
squeezed less and drives the amp less. The zones are levelled to the same peak, because the app and the MPC both
make a softer touch quieter on their own. Hats choke each other (group 1). The fill and the build choke each
other (group 2).

## The chain

1. **Print** (round 0b): the drum as it was recorded, before any pedal. Console EQ per drum comes from the
   owner's research note: the kick's thump at 68 Hz and beater at 3 kHz, the box cut at 380 Hz; the snare's
   body and a bright top; a stick attack on the toms; the hats' harsh top rolled off. Then tape driven hot, an
   asymmetric soft curve at 4x.
2. **Press**, the FET compressor (the 1176 pedal):
   - INPUT drives into a fixed threshold;
   - attack 20 µs to 800 µs, release 50 ms to 1.1 s with a slow second stage;
   - the pedal's parallel mode, a crushed copy (all buttons in, fastest knobs) under the hit;
   - every kick's attack at its slowest, so the beater passes.
3. **Reel**, the multi-head tape echo (the El Capistan's multi-head mode):
   - three heads at a sixteenth, a dotted eighth and a quarter, at the kit's tempo;
   - every pass saturates, darkens and thins, with wow, flutter and a seeded drift;
   - the loop gain is at most 0.92, so it cannot run away.
4. **Combo**, a tube amp and an open-backed speaker, last: the echoes go through it too. The speaker rolls off at
   8.5 kHz on snares, hats, claps and percussion, and at 6.5 kHz on kicks, toms and hand drums.

**Intensity.** The owner asked for Punch "dialed back like 10-15%". The kits use 87.5 percent, the middle of
that range. The EQ's decibels, the tape's heat, the crushed copy's share, the amp's gain above unity and its
sag all scale with it. Each kit then leans its own way from Punch: Philly is Punch itself, Loft a little
hotter, Munich hotter still. The beater is round 0b's level at 87.5 percent. The audition page has "Full
Punch" and "Lighter (75%)" on three pads per kit.

## The tambourine

The owner asked for the tambourine to be recreated with synthesis. BACKWASH's designed tambourines (model 4 and
its revisions) had been heard as lacking "a living rattle" and were replaced by CC0 recordings. So this one is
fitted to those recordings, as BREAKER's cymbals were fitted to theirs.

A tambourine does not ring as one body. Its jingles knock together dozens of times in the few hundred
milliseconds after the hand hits the frame. The recordings measure 57 to 69 collisions per hit, 3 to 5 ms apart,
and that rattle is the instrument. So `MirrorballTambourine` is built from four parts:
- **Jingles:** 78 resonances measured from the recordings, up to six per third-octave from 1 to 16 kHz.
- **Collisions:** about every 4 ms, each striking a random third of the jingles with a random sign. Their level
  follows the recordings' own 4 to 11 kHz envelope. A hard hit is loudest at once; a soft one builds for about
  50 ms. Velocity blends the two.
- **The hand:** a 3 ms click on the frame.
- **Tuning:** every jingle's level matched to the recordings per third-octave, per layer, then a fourth-order
  high-pass at 2.2 kHz, where the recordings hold almost nothing.

`scripts/fit_mirrorball_tambourine.py` writes the fit (`synth/src/main/resources/mirrorball/tambourine.json`).
Only those numbers ship; the synthesis never reads the recordings. By the fit's own measure it is within 1.5 dB
of the recordings in every third-octave from 1 to 16 kHz, and within 2 dB median on the envelope over 0 to
400 ms. Every take re-seeds the collisions, so a zone holds four genuinely different strikes rather than two
recordings replayed.

## Generate the proof and the listening pack

```sh
./gradlew --no-daemon :shell:generateMirrorballKits
python3 scripts/mirrorball_page/build_page.py testkit/mirrorball-kits testkit/mirrorball-kits/site
```

Output goes to `testkit/mirrorball-kits/` (ignored by git). The page builder needs `ffmpeg`. For each kit the
generator does six things:
1. builds all 192 slices and saves the kit;
2. reloads it and checks the reloaded kit equals the saved one;
3. checks every saved slice, and then every exported slice, against its render, through the app's own pad
   resolver (`PadHit.resolve`), two full cycles of each velocity zone;
4. exports an MPC 3 track and reads it back: velocity ranges, base slices, cycle lengths and frame windows;
5. writes each pad's hard first take (read from the exported file), its dry strike, all twelve slices soft to
   hard, the comparisons on three pads, and a two-bar groove dealt the way the app deals it, with the same
   groove dry;
6. writes `verification.csv`, `metrics.csv` and `manifest.json`.

## What was measured (round 1b, this cloud JVM, commit after `42400b4d`)

The generator, `:shell:generateMirrorballKits`:
- **All three kits pass.** 16 pads and 192 slices each. Every saved slice and every exported slice matches its
  render through `PadHit.resolve` over two full cycles of each zone. The MPC 3 track reads back with matching
  velocity ranges, base slices, cycle lengths and frame windows.
- **Build time, warm:** Philly 34.6 s, Loft 34.5 s, Munich 24.2 s. These are not phone timings.

`MirrorballTest` (18 tests) and `MirrorballKitTest`, `StarterKitsTest`, `StarterShelfTest` all pass:
- **The order.** With the amp last, the first echo comes back on average at least 2 dB louder against the hit
  than in the rack's order, on every kit's clean echo pads. Take the amp's sag and most of its gain away and that
  gap shrinks by more than 1 dB.
- **The echoes land on the heads.** The closed hat's repeats at a sixteenth and a dotted eighth stand at least
  10 dB over the space between them. With no echo, nothing is left there (60 dB or more under the hit).
- **The tape loop never grows,** at repeats 1, on three head settings at 60, 128 and 200 BPM.
- **Kicks.** No kick has an echo. Every kick (except Munich's long kick) has its beater: the 1.5 to 4 kHz band
  sits within 18 dB of the whole in the first 15 ms. The print adds more than 10 dB at 250 to 600 Hz to
  Philly's kick, and those harmonics rise from Lighter to the kit to Full Punch.
- **The compressor** lowers every main snare's crest by more than 1.5 dB and squeezes a soft hit less than a
  hard one. A hit held under the threshold comes through within 0.5 dB.
- **The tambourine** is within 3 dB of the recordings in every third-octave from 1.6 to 16 kHz, hard and soft,
  averaged over four takes. Its takes differ, and a soft hit peaks later than a hard one, as the recordings do.
- **Velocity and round robin.** Every zone is levelled to one peak. Every soft hit is a different strike, not a
  quieter copy, and every hit is duller there. Every zone holds four different takes.
- **The kits.** The three kits have different kicks and snares (dry), tempos, tapes, amps and pad roles. Every
  hit stays under 1.5 s and every phrase is filed as a loop. No name carries a maker's.

## Limits, stated plainly

- **Not heard.** Round 1b is the owner's round-0b pick moved into the app, at the intensity they asked for. Only
  round 0b's Python Punch was heard.
- **MPC 3 is the export target that keeps the round robin.** MPC 2's grid projection keeps the velocity zones
  but only the first take of each, so an MPC 2 export does not pass the round-robin gate. SFZ and DecentSampler
  exports were not generated.
- **No hardware.** Nothing here was played on a phone or loaded on an MPC.
- **Rendered bank, not recipes.** A pad cannot be re-edited as a SYNTH patch. Changing the kits means bumping
  `MirrorballKits.VERSION`; saved kits keep playing their own WAVs.
- **Mono.** The tape echo's stereo spread and the amp's room are not modelled.
- **Tempo-locked echoes.** The heads follow each kit's tempo, so at another tempo the repeats fall off the grid.
- **Per pad, not a bus.** A real 1176 on a drum bus makes the kick duck the hats. Single pads cannot do that;
  only the fill and the build, which go through one chain together.
- **Kit build time.** Each echoing hit prints 1.45 s through a 4x-oversampled amp, so a kit takes several times
  as long as a GATECRASH kit (figures above). That is a one-time cost when the starter is created.
- **Shared code copied, not shared.** `finish` and the zone levelling follow `GatecrashKits` line for line.
  Sharing them would mean editing the approved GATECRASH round.
- **Takes on noise voices.** The hats, shakers and THUMP and ONSET voices cannot be re-struck from outside, so
  their takes are small control moves on fresh noise. That is weaker than a re-strike.

## Why it is built this way

- **The order is the instrument.** The amp is last, so it squeezes the hit and its echoes together, and the
  repeats come up behind the hit. With the amp first, the repeats are clean copies of a distorted hit.
- **Character comes from the print.** Round 0 was basic because the drums were nearly pure sines and nothing
  added harmonics. Recording them the way a 1970s room did (console EQ, hot tape) gives the kick a beater and
  harmonics a small speaker can play, before any pedal.
- **Parallel compression is the punch.** The dry copy keeps the attack, and the crushed copy fills the body in
  behind it. That is the pedal's own parallel mode.
- **The tambourine is fitted, not designed.** Designed tambourines had been heard as lifeless. Fitting the
  jingles and the rattle to recordings the owner accepted keeps that sound and makes every take a new strike.
- **The one risky decision** is the 87.5 percent reading of "10-15%", applied to every stage at once. The page
  has 100 percent and 75 percent beside it.
