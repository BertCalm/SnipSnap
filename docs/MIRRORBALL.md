# MIRRORBALL

Three 1970s disco kits, every pad printed through an FET compressor, a multi-head tape echo, then an amp.

The characteristic gesture is **dead, clicky strike → FET squeeze → tape repeats in rhythm → the amp pulls
the repeats up behind the hit**.

MIRRORBALL is a kit family, not a SYNTH engine, in the way GATECRASH is. The owner asked on 2026-11-12 for "a
disco drum kit pushed through" the UAFX 1176 Studio Compressor, then the Strymon El Capistan, then an amp, and
attached a research note on the sound of disco drums. The hits are SKIN, THUMP, ONSET and TERRA drums and
BACKWASH's recorded tambourine as those engines already make them. What is new is one treatment stage,
`Mirrorball`, which prints each pad through the owner's chain in the owner's order. The FX rack cannot do this:
`FxChain` runs VALVE before TAPE and ECHO, so there the echoes never reach the amp.

**Status: round 1, built, not yet heard.** Round 0 was a Python sound study
(`scripts/mirrorball_r0.py`, [its page](https://claude.ai/artifact/EJ9vFTy9SBbj2cbQJ125oH)). Its five
questions were not answered before the owner asked for the build, so round 1 takes the defaults listed below,
and the audition page has a button for each alternative. The [design
specification](superpowers/specs/2026-11-12-mirrorball-disco-kit-design.md) has the research, the model and
the R0 measurements.

## The three kits

SKINS shelf:

| Kit | Tempo | What it is | How the chain is set |
|---|---|---|---|
| MIRRORBALL PHILLY STRINGS | 116 | A dead kick, a towelled snare, two congas, a recorded tambourine, rack and floor tom, ride, cowbell, shaker, a ghost snare | Clean amp, tape at middle age. The kick is dry; the snare has one quiet slap; the hats, tambourine and shaker gallop on two heads |
| MIRRORBALL LOFT PARTY | 122 | A booming open kick, a clap layered on the snare, a hand clap, quinto, conga, tumba, bongo, two timbales, cowbell, claves | Warmer amp, older and wobblier tape, the percussion on the two longest heads |
| MIRRORBALL MUNICH MACHINE | 128 | An analog kick and a long kick, an ONSET snare, a clap, four falling synth toms, a sequencer hat, zap, rim | Amp pushed, new tape (less wobble, brighter repeats), the sequencer hat on the dotted-eighth head |

Each kit has sixteen pads: fourteen hits, then a fill on A15 and a build on A16 (Philly: tom fill and snare
build; Loft: timbale fill and clap build; Munich: synth-tom fill and snare build). The phrases go through one
chain together, as a drum bus would, so there the kick and the hats share one compressor.

Every pad has three velocity zones (soft, medium, hard) with four takes in each. A softer zone is a softer
strike, and it reaches the compressor's fixed threshold lower, so it is squeezed less, saturates the tape less
and drives the amp less. The zones are levelled to the same peak, because the app and the MPC both make a
softer touch quieter on their own. Hats choke each other (group 1); the fill and the build choke each other
(group 2).

## Round 1 defaults (the owner's round-0 questions, unanswered)

- **Order:** the owner's (amp last). "Rack order" on the page plays amp before echo.
- **Speaker:** bright (rolls off at 8.5 kHz) on snares, hats, claps and percussion; darker (5.2 kHz) on kicks,
  toms and hand drums. Round 0's study measured the dark speaker taking a disco snare's centroid from 11.4 to
  4.6 kHz. "Darker speaker" on the page plays round 0's default.
- **Echo:** none on any kick, so four on the floor stays four; a whisper on the snares; the gallop (a
  sixteenth plus a dotted eighth) on the hats and shakers.
- **Amp:** clean on Philly, warmer on Loft, pushed on Munich.
- **Printed pads,** with the fill and build as the bus-like phrases.

## Generate the proof and the listening pack

```sh
./gradlew --no-daemon :shell:generateMirrorballKits
python3 scripts/mirrorball_page/build_page.py testkit/mirrorball-kits testkit/mirrorball-kits/site
```

Output goes to `testkit/mirrorball-kits/` (ignored by git). The page builder needs `ffmpeg`.

<!--MEASURED-->
