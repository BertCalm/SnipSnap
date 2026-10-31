# The COMPRESSOR section: a sustain pedal for the FX rack

**Status:** design, reviewed section by section with the owner on 2026-10-21; not yet planned or built. This is Stage 2 of `2026-10-04-pedal-board-design.md`.
**Builds on:** the WAH section (PR #576, the section pattern this one copies), Stage 0's PEDALS row (the COMP chip, interim), SQUASH, and a Python prototype of the gain law below whose numbers are in "What the measurements say".
**Roadmap row:** the pedal board's row, S30 in `docs/SYNTH_ROADMAP.md`; this stage extends it.

## Why

The owner's Stage 0 listen found COMP (SQUASH at its strongest) "not enough" on every source, even at AMT 100, and the owner said yes to compression as its own section, built for sustain. SQUASH is the sampler's punch compressor: its threshold is 18 dB under the peak and its release is fixed at 90 ms. At the pad sheet's default AMT 0.7 it lifts a note's tail over its body by 2.0 dB (JANGLE), 3.0 (CHUG) and 5.7 (the choir).

**Success:** a pad tapped with COMP gets a compressor that holds a decaying note up (the choir's tail over body +17 dB against SQUASH's +5.7); the section passes its owner listen, or the chip does not repoint. On a guitar pad the chip is honest about its limit: see finding 2 below.

## What the measurements say

All numbers in this section come from a numpy prototype of the law below (Python harness, not Kotlin), run on 2026-10-21 over four dry clips: the choir, a snare, and the two MAGNET guitars both as the kit has them (through the landing amp, "baked") and as the engine renders them (before the amp, "pre"). They say nothing about how anything sounds. "Lift" is the earlier spike's measure: RMS of the tail window over RMS of the body window, minus the same ratio for the dry clip (snare: 100 to 200 ms over 5 to 50 ms; the others: 1.0 to 1.5 s over 0.1 to 0.5 s). Kotlin re-measures every one in the plan's first task.

1. **It reaches the earlier simulation's targets where the target was measured.** At the default 5 ms attack and 300 ms release, with the threshold ceiling at 36 dB: choir +17.7 dB, JANGLE pre +9.6, CHUG pre +15.5, against the simulation's +19.2, +10.2 and +17.4 (a 30 dB ceiling gives +16.8, +9.6, +14.5, so 36 is closer). SQUASH's best was +5.7, +2.0 and +3.0 at AMT 0.7.
2. **On a guitar pad as the kit has it, it is no better than today's COMP.** The baked pads give JANGLE +2.0 and CHUG +1.6. Their tails are only 4.4 and 4.0 dB under the body, because the landing amp is itself a compressor, and a 4:1 law removes three quarters of whatever difference there is. (The earlier simulation's guitar targets were measured on the dry engines, before the amp.) A pedal chip rewrites the baked audio, so until the pedal can sit in front of the amp (Stage 3) the COMP chip on a guitar pad is about today's strength, and on CHUG slightly weaker. The section pays on pads that no compressor has touched, and in front of an amp.
3. **A longer release gives less lift, not more.** Choir lift at release 80, 150, 300, 600, 1200 ms: +19.3, +20.9, +17.7, +9.0, +4.5. JANGLE pre peaks at 300 (+9.6), CHUG pre at 150 (+17.7), the snare at 80 (+6.2 against +1.3 at 300). The earlier spike's line that "a release of 300 ms or more adds 2.6 to 3.5 dB" does not hold for this law; the likely cause (not tested) is that a long release carries the loud body's gain reduction into the tail. So the release centre is 150 ms, not 300.
4. **Attack is the pluck knob, not the sustain knob.** Attack from 2 to 12 ms moves the lift by only 0.1 to 1.3 dB and the crest factor by 2.4 to 8.1 dB.
5. **The briefed 1.5 times reach would leave the top third of SUSTAIN dead.** With depth = clamp(1.5 x SUSTAIN), SUSTAIN 0.7 and 1.0 rendered bit-identical. The law below uses a curve instead.
6. **Make-up** (the peak-match gain, which is the sustain) is +13.9 to +19.7 dB at full strength with the 36 dB ceiling.
7. **The end of a pad needs a fade only when it is cut mid-tail.** The named clips end at digital zero or 73 to 81 dB under the peak, so they pass the repo's 50 dB bar with no fade. Cut hard mid-tail, JANGLE and CHUG end 18.6 and 17.5 dB under at the defaults. A 95 ms fade of power 5 passes at the knobs' extremes (40 ms misses by 0.04 dB at CHUG with attack 1 ms and release 80 ms; 200 ms passes but costs the snare's lift). The fade costs no lift at 95 ms.

## Decisions taken

1. **A section of its own, `compressor`.** Its name is a permanent saved key. No source or test uses the word, and the preset-name tests that forbid section names (AEROSTAT, ARCO, FLOTILLA, GLINT, MERCURY) have no preset called COMPRESSOR. SQUASH stays as the sampler's punch compressor and is not changed.
2. **Three knobs, no LEVEL.** SUSTAIN, ATTACK and RELEASE. Every rack section ends peak-matched to its input (the contract), so make-up is automatic and a LEVEL knob would do nothing. The owner chose this layout over a one-knob and a studio layout.
3. **SUSTAIN is a curve, not a clamp.** Depth d = 1 - (1 - SUSTAIN)^2: 0 at 0, 0.75 at 0.5, 0.91 at 0.7, 1 at 1. The chip's first tap at the pad sheet's default AMT 0.7 (SUSTAIN 0.7 after the fade toward neutral) is 91 percent of full depth, and no part of the knob is dead. This replaces the 1.5 times reach the wah uses, which is right for a gesture and wrong for a gain law.
4. **A new character, `sustained`; `compressed` stays.** Saved pads store a character's name and replay it by name, so changing `compressed` would quietly change every kit already saved. `compressed` keeps its SQUASH recipe and stays reachable from `treat`.
5. **The end fade lives in `Treatments.apply`, as `driven`'s does,** sized from the declared grid in finding 7. Stage 3's design still decides where fades live.
6. **No oversampling.** The section multiplies the audio by a slow gain (a 1 ms attack at the fastest); SQUASH does the same without it.
7. **The chip repoints only after the owner's gate.** The gate plays the guitars both ways (see below), so the owner hears finding 2 before deciding.

## The section

**Slot:** after WAH, before SQUASH (`… eq → contour → wah → compressor → squash → valve …`), the guitar convention (wah, compressor, drive, amp). A new `Section("compressor", Compressor.MACROS, { it.compressor }, { c, m -> c.copy(compressor = m) }, Compressor::process)` row in `FxChain.SECTIONS` and one appended `Map<String, Float>?` field named `compressor` after `wah` (the field name must equal the section name; the constructor order differs from the rack order by design). `FxChain.VERSION` stays 1.

**Macros (all in 0..1):**

| Macro | Default | Neutral | Meaning |
| --- | --- | --- | --- |
| SUSTAIN | 0 | 0 | how hard it holds the note up: threshold, ratio and make-up move together (the law below) |
| ATTACK | 0.5 | 0.5 | `Dsp.around(m, 1 ms, 5 ms, 25 ms)`: how much of the pluck escapes before the clamp |
| RELEASE | 0.5 | 0.5 | `Dsp.around(m, 40 ms, 150 ms, 1200 ms)`: how fast the gain comes back |

A recipe sets SUSTAIN and leaves ATTACK and RELEASE out (setting a macro exactly at its neutral is one AMT could not move, and the AMT-scaling test false-fails on it).

**The law (per frame; constants marked shape until measured in Kotlin and then listened to):**
- depth `d = 1 - (1 - SUSTAIN)^2`; threshold `thr = 36 dB x d` under the pad's own peak (the input's peak, as SQUASH measures it); ratio `R = 1 + 3d`, slope `1 - 1/R`.
- one detector for the whole frame: the loudest channel's `|x|` through a one-pole follower (`if (a > e) attack else release`, coefficient `1 - exp(-1/(t x rate))`), read 2 ms ahead (clamped to the last frame), as SQUASH does.
- `over = 20 log10(ahead / thr)` in dB; a 6 dB soft knee centred on the threshold: reduction 0 below -3 dB, `slope x (over + 3)^2 / 12` within +-3 dB (the standard quadratic knee: it meets `slope x over` at +3 dB), `slope x over` above.
- the one gain is applied to every channel of the frame; then the output is peak-matched to the input's peak (that gain is the make-up) and clamped to +-1.
- `SUSTAIN < 0.001`, or a silent input, returns a bit-exact copy. The sample rate is the pad's (never a literal). No tail.

## The character and its end fade

`sustained` = `compressor = {SUSTAIN 1.0}`. `Treatments.apply` fades the pad's last milliseconds before the section, as it does for `driven` (95 ms at power 5, never past the pad's second half). `driven` scales the length by the amp's lift (`ampLift`, which reads VALVE's DRIVE); `sustained` scales it by the section's make-up bound, `slope(d) x thr(d)`, at the amount over its value at full (27 dB), about 0.89 at AMT 0.7. The plan either generalises `ampLift` or adds a sibling, and updates the `PRE_AMP_FADE` comment, which names the amp.

Known edges, the same as `driven`'s and disclosed: a pad shorter than about 200 ms still ends hot; a BREED child, which renders an fx-only recipe through the bare chain, inherits a `sustained` parent's section unfaded; a pad whose loudest point lies inside the faded span is untested.

## Tests and the gate

Section tests (in `CompressorTest`, in the style of `WahTest`, bars pinned at the Kotlin-measured value minus a margin the report states, each mutation-checked, the wiring tests written in the first pass):
- bit-exact copy at `SUSTAIN < 0.001` whatever ATTACK and RELEASE say, and on a silent pad; deterministic; finite; peak within 0.05 of the input's; a doubled mono pad stays stereo-identical; a pad louder on one side keeps its image (one shared gain);
- **wiring:** SUSTAIN changes the output at every quarter of its range, including 0.7 against 1.0 (no dead zone); ATTACK moves the crest factor by at least a pinned amount; RELEASE moves the choir's lift by at least a pinned amount between 80 and 600 ms; each test fails when its knob is unwired, hard-wired or reversed (the gap the wah's final review found: its SPEED and Q had no test);
- **the sustain itself:** at SUSTAIN 0.7 and the defaults (5 ms attack, 150 ms release), the choir's lift is at least the Kotlin-measured value less a stated margin (the Python harness reads about +20 dB at s 1.0, against SQUASH's +5.7); the dry JANGLE and CHUG pass bars a few dB under their measured values (+8.7 and +17.7 at s 1.0); the baked guitars are pinned at their honest low value (about +2 dB), so a change that quietly helps or hurts them is seen;
- **sample rate:** the same burst at 44.1 and 48 kHz gives the same lift within 1 dB (the time constants are milliseconds), and a hard-wired rate fails it;
- **end of pad:** the last 20 ms of a landed note through a guitar's landing amp, and of a hard-cut snare and choir at AMT 0.7 and 1, at least 50 dB under the peak;
- the rack's contract tests iterate `SECTIONS` and cover the section with no exclusion; `FxTest` gains a placement test (WAH, then COMPRESSOR, then SQUASH); `TreatmentsTest` pins `sustained` after `rockwahed` in the names list and maps it to `compressor` in the pedal-recipes test; Breed's seeds are checked stable.

**The gate:** a listening page like the wah's, from `generateCompressorAudition` (a Gradle task, output git-ignored). Sources: a snare, a choir, JANGLE and CHUG. Clips per source: dry; today's COMP at AMT 70; the new chip at AMT 70 and 100; the ends of SUSTAIN, ATTACK and RELEASE. For each guitar also the pedal on the baked pad (the chip as the sheet does it) and in front of the amp (a rig), each against the other. Questions: does it sustain, is it better than today's COMP, do the ends sound like their words, which guitar order, should the COMP chip repoint to `sustained`. If yes, a small follow-up repoints `PadSheet.CHARACTER_FOR["COMP"]` to `sustained` (with `PadSheetTest`, `docs/CLI.md` and the roadmap row), as the wah's chip did.

## Registration hazards

- The table-driven registration of `FxChain` as for the wah: one `Section` row, one appended field, `VERSION` unchanged; the `FxChain` KDoc order line and the `README.md` rack order line gain `compressor`.
- `FxTest` pins VALVE right after SQUASH; the compressor lands before SQUASH, so that test is untouched.
- A new section's name becomes a forbidden word in preset names: checked above, none use it.
- `Treatments.EXTRA` is appended-only and its name list is pinned in `TreatmentsTest`; `Shuffle.TREATMENTS` is untouched.

## Out of scope

The board in front of the amp for guitar pads (Stage 3, its own design: it also decides where fades live); a limiter at the peak; side-chain; parallel compression; a tempo-synced release; the BREED end-fade edge; repointing the COMP chip before the gate.

## The ledger

- **Verified** (read in the repo at commit b429ec2c4, 2026-10-21): `FxChain.Section` and `SECTIONS` (the wah is the last pedal section); `Treatments.EXTRA`, the private `PRE_AMP_FADE`, `EndFade`, `ampLift`, `endFaded` and `apply`; `KitBuilder.characterPad` calling `Treatments.apply`; `Dsp.lin`, `Dsp.expMap` and `Dsp.around`; SQUASH's detector, lookahead and peak-match; the pinned names list and pedal-recipes test in `TreatmentsTest`; `contractExcluded` and `stereoExcluded` in `FxTest`; the VALVE-after-SQUASH pin; that no source or test uses the word COMPRESSOR.
- **Measured** (Python harness, not Kotlin): everything under "What the measurements say".
- **Proposed** (shape until Kotlin and a listen back them): the 36 dB ceiling, the 4:1 top ratio, the 6 dB knee, the attack and release ranges and centres, the depth curve, the 95 ms power-5 fade and its lift scaling, every test bar.
- **Heard:** nothing yet. The owner has heard SQUASH as COMP ("not enough") and said yes to a dedicated section.

## Why it is built this way

- **One big knob and two small ones.** A sustain pedal is one idea (hold the note up), so SUSTAIN moves threshold, ratio and make-up together; ATTACK and RELEASE are there to shape it, not to run it. Remove SUSTAIN's coupling and the knob becomes three knobs a phone user cannot reason about.
- **A curve, not the wah's stretch.** The wah's 1.5 times reach makes the first tap full strength, but on a gain law it freezes the top third of the knob. The measured proof was two renders that came out bit-identical. The curve gives the first tap 91 percent and keeps the top of the knob meaningful.
- **A shorter release than the textbook.** The spike said 300 ms or more; the measurement said the choir's lift falls from +20.9 to +9.0 as the release goes from 150 to 600 ms. The centre is 150 ms; the slow end of the knob stays for a different sound.
- **Guitars after the amp.** The landing amp already compressed a guitar pad, so its tail is only 4 dB under its body and a compressor has little left to hold up. The same compressor in front of the amp lifts the dry engines by +9.6 and +15.5 dB. That is not a flaw in the section; it is the argument for Stage 3, and the gate plays both orders so you hear it.
- **The one risky decision:** whether the COMP chip should repoint at all while guitars cannot be served. The gate answers it; the chip is a separate, small change precisely so the section can ship without it.
