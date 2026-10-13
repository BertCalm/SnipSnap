# The pedal board: guitar-pedal effects for the FX rack

**Status:** design, reviewed section by section with the owner on 2026-10-04 and revised on 2026-10-13 after two owner listens (the Stage 0 listen and a round-two listen); the revised Stage 0 is next, then the WAH section.
**Builds on:** VALVE (the rack's amp section, `2026-09-29-magnet-valve-design.md`) and the MAGNET guitar engine (merged, PR #438).
**Roadmap row:** S30 in `docs/SYNTH_ROADMAP.md` (the table is numbered by arrival; SUTURE already claims S29).

## Why

The owner asked whether the app is "doing effects pedals". The MAGNET spec had put pedals out of scope ("a fuzz law, a noise gate, a tuner, pedals: VALVE presets or later sections"). This is that later step.

**Intent (owner's answer, option C):** pedals for both audiences: the guitar voices (JANGLE and CHUG, so a guitar pad sounds like a rig) and any pad (a fuzz on a drum, a wah on a choir). Start small: pedal-style recipes over the sections the rack already has, and a new section only where the rack has a real gap and a listen proves it.

**Success:** a user taps a pedal on a pad and hears the pedal they asked for; every new section earns its place at an owner listening gate, the way VALVE and MAGNET did; nothing existing changes sound or moves on the pad sheet.

## What the rack already has

The per-pad rack has 19 sections in a fixed order (`FxChain.SECTIONS`, the order is "a playability rule wearing an architecture hat: no routing screen, no wrong answers"): SPEED, SWELL, SMEAR, GHOST, SPIKE, EQ, CONTOUR, SQUASH, VALVE, CRUNCH, RING, DUB, VINYL, TAPE, ENSEMBLE, PHASE, ECHO, SPRING, MOTION.

| Pedal | In the rack today |
| --- | --- |
| Compressor, EQ, chorus, phaser, delay, spring reverb, tape, bit-crush, ring mod, amp | SQUASH, EQ, ENSEMBLE, PHASE, ECHO, SPRING, TAPE, CRUNCH, RING, VALVE. But SQUASH is the sampler's punch compressor, not a sustain pedal (see "What the listens found"). |
| Fuzz or overdrive before the amp | VALVE's DRIVE is the amp; at DRIVE 1 it is gain 1000, which the owner heard as a drive pedal. |
| Wah, envelope filter | No. CONTOUR sweeps a ladder low-pass once from the hit, about 90 percent over in 30 ms; the owner: "it just sounds like a filter was applied". |
| Tremolo, vibrato | No. RING is sum-and-difference and its slowest rate is 30 Hz. |
| Flanger | No. PHASE is allpass notches, not a comb. |
| Octave | No. SPEED is the transport (the whole pad's pitch), not the played note. |
| Noise gate | No. GATE is a keyed rhythmic chop outside the rack. |

The fixed rack order is already the guitar-pedal convention: wah, compressor, fuzz or drive, amp, then modulation, delay and reverb. A pedal takes its natural slot; no routing is added.

## Decisions taken

1. **Staged hybrid** (of three approaches offered; presets-only and one-section-with-a-type-switch were declined). Recipes first, then new sections where the rack has a real gap, each at its own gate.
2. **A pedal is a recipe or a section.** A recipe is a named chip over existing sections (the way `amped`, VALVE at DRIVE 0.6, is a recipe that has no chip of its own today): no new DSP. A section is new DSP with a few macros, a fixed slot, and a gate. The pad sheet's AMT stepper fades either toward its neutral; nothing new to learn.
3. **Placement on the pad sheet: a new seventh row, PEDALS.** Nothing moves: PHASE, ENSEMBLE and SECTION keep their places. This bends the rule that rows group by what an effect does rather than what it is, on purpose: a guitarist looks for pedals by pedal name. (Declined: regrouping by function with a MOD row, which would have moved three existing chips.) Chips join the row as their pedals pass: DRIVE and COMP first, then WAH, then TREM and FLANGE.
4. **The process for every stage** is the MAGNET and VALVE process: ledger, per-task review, a final review, an owner listening gate; a section that fails its gate ships without a chip.
5. **The order after the two listens (the owner delegated it, 2026-10-13):** Stage 0 (revised) ships the row with DRIVE and COMP; Stage 1 the WAH section (the owner's clearest ask, with prototypes already heard); Stage 2 a dedicated COMPRESSOR section (the owner said yes); Stage 3 the board in front of the amp for guitar pads (a design that WAH and COMPRESSOR want and that decides how guitars hear every pedal); Stage 4 TREMOLO and FLANGER; Stage 5 FUZZ, deferred (DRIVE at its top is already a fuzz-grade gain; a separate fuzz law waits for a reason).

## What the listens found (owner, 2026-10-12)

**Stage 0 listen** (recipes DRIVE, COMP and WAH on a snare, a choir and both guitars; 17 answers): DRIVE and COMP were "not enough" on every source even at AMT 100; WAH was "right" on the snare and the choir and "not usable" on the guitars the pad-sheet way; on jangle the owner preferred the pedal in front of the amp (the rig way) for WAH; "the same" for DRIVE and COMP, which were too weak to tell. Note: "The wah doesn't sweep like a wah wah. It just sounds like a filter was applied."

**Round-two listen** (stronger candidates, 15 answers; the spike's numbers are in the git-ignored workspace `.superpowers/sdd/2026-10-06-pedal-board-stage-0/spike-round2/summary.md`):
- **COMP:** the strongest SQUASH candidate (AMOUNT 1.0, ATTACK 0.05) on all four sources, and "yes" to compression as its own section built for sustain. Measured: at the pad sheet's default AMT 0.7 SQUASH alone lifts the tail over the body by only 2.0 dB (JANGLE), 3.0 (CHUG) and 5.7 (choir), because the linear AMT fade caps AMOUNT at 0.7 and SQUASH's threshold is only 18 dB under the peak with a fixed 90 ms release. In front of the landing amp (itself a compressor) COMP adds only 1.3 to 2.2 dB over the baked pad.
- **DRIVE:** the hardest candidate (VALVE DRIVE 1.0, SAG 0.15, CAB 0.08) on the snare and both guitars, the lead-tone candidate (DRIVE 0.9, SAG 0.5, TONE 0.7, CAB 0.25) on the choir; "a drive is a drive pedal now". Measured: THD on a steady 220 Hz sine is 36 percent at AMT 0.7 (gain 11.3, VALVE's own default) and 38 at AMT 1 (gain 1000). Flag: the same recipe is 20 to 25 times hotter into the tube at AMT 1 than at 0.7 (a linear fade on an exponential gain law), and VALVE lifts a quiet tail by that gain, so on a short pad such as a snare the amped end is cut (the last 20 ms is -19.8 dB under the peak at AMT 1; the bar is -50): an audible click.
- **WAH:** the auto prototype (a free-running 2 Hz sweep) on all four sources, and "several: a switch": the owner wants the touch, auto and one-rock moves selectable.

## Stage 0 (revised): the PEDALS row with DRIVE and COMP

The row is `PadSheet.PEDAL_SEGMENTS` in the `shell` module, appended to `PadSheet.ROWS`; the recipes are named characters in `Treatments.EXTRA` (`synth`), reached from a chip through `PadSheet.CHARACTER_FOR`. The app's `TreatmentCard` draws every row of `PadSheet.ROWS` generically, so the app needs no change; a row is one list entry, one `CHARACTER_FOR` entry per chip, and the inventory that `PadSheetTest` pins (31 chips before the pedals; a row at most 6 wide; a word on one row only). New characters are appended to `Treatments.EXTRA`, never to `Shuffle.TREATMENTS` (a seeded bank indexes it; the names list is pinned in order). A chain must not set a macro exactly at its neutral (AMT could never move it; the AMT-scaling test false-fails on it).

| Chip | Recipe (the owner's picks) |
| --- | --- |
| DRIVE | VALVE DRIVE 1.0, SAG 0.15, CAB 0.08. CAB cannot be omitted (a missing macro is VALVE's default, 0.6, and AMT could not fade it), so it takes a small non-neutral value; the speaker network is fully in at CAB 0.25. **With a pre-amp end fade** (below). |
| COMP | SQUASH AMOUNT 1.0, ATTACK 0.05 (ATTACK 0 is SQUASH's neutral). An interim chip: it is the best SQUASH can do and the owner picked it, and it is replaced by the COMPRESSOR section's recipe at Stage 2. |

The WAH chip and its `quacked` recipe are **removed** (it never was a wah; the owner's verdict) and return with the WAH section.

**The pre-amp end fade for DRIVE.** A `Treatments.apply` door change, not a VALVE change (VALVE is merged and its goldens are pinned): before the chain runs, `driven` fades the last N milliseconds of the pad with a power-shaped fade (the lesson of MAGNET: a fade ahead of the amp survives the amp's lift). N and the shape are chosen by measurement: the shortest, mildest fade for which a snare and a choir through `driven` at AMT 0.7 and 1 end at least 50 dB under their peak (with 3 dB of margin) and keep a DC mean under 1e-4. The recipe stays an fx-only recipe: `Treatments.apply` is the one door every consumer (`KitBuilder.characterPad`) uses, so a re-treated pad gets the same fade.

**A finding that stays true: a chip acts on the pad's baked audio.** `KitBuilder.characterPad` rewrites every file the pad references (`rewriteEveryFile`), so a chip tapped on a MAGNET pad processes audio that has already gone through its landing amp: DRIVE stacks a second amp on the first, and COMP lands after the amp. Existing behaviour that the row invites taps on: a chip replaces a MAGNET pad's patch recipe (which holds its landing `valve` section) with the fx-only recipe, and NONE (`unEraPad`) restores the audio but sets the recipe to null (`KitBuilder.kt` around line 775). Stage 3 is the design that fixes this.

**Gate:** the two listens above were the gate for these two chips (the owner picked the recipes from heard candidates); the pre-amp fade is checked by measurement, and the owner hears the shipped chips on the phone when the PR is up.

## Stage 1: the WAH section

A real wah: a resonant band-pass swept over hundreds of milliseconds, **before the amp** (`contour → wah → squash → …`, after CONTOUR and before SQUASH, the guitar convention: wah, compressor, drive, amp).

- **From the prototypes the owner heard** (spike B, a test-source prototype, no production code yet): `Dsp.TptSvf`'s band output plus 0.05 of the dry, peak-matched; the centre moves exponentially between a heel of 280 Hz and a toe of 2800 Hz (a decade), Q about 5 (damping 0.2). The three moves differ only in where the centre sits over time: **touch** (an envelope follower, attack 20 ms, release 100 ms, an 8 dB span under the pad's own peak, drives it), **auto** (a free-running sine at 2 Hz, starting at the heel), **one rock** (one raised-cosine heel-to-toe in 250 ms and back in 500 ms, then held at the heel). Measured on the choir and both guitars after the landing amp, each sweeps 1.6 to 3.0 octaves of spectral centroid for 350 to 500 ms, against the shipped recipe's 0.01 to 0.4 octave after 60 ms. Touch and one rock park at the heel for about 3 s of a 4 s guitar note; auto never parks; the owner chose auto.
- **Macros (proposal, to be fixed at the section's own design):** MODE (touch, auto, rock; default auto: a stepped macro like ENSEMBLE's SECTION), SPEED (the LFO rate, the follower's release, or the rock's length), RANGE (how far the centre travels), Q (resonance), with the 0.05 dry as a constant. Neutrals so AMT 0 does nothing (the contract tests require AMT 0 to land on every neutral and a near-copy at neutral).
- **Hazards the prototype showed:** the touch move with the brief's 5 ms attack and 150 ms release failed on guitars (the filter opens before the first window and the pluck's own decay darkens at the same slope: they cancel), so the follower is slow (20 ms attack, 8 dB span); a pure band-pass sweeps hardest but leaves nothing off-centre, so the small dry stays; TptSvf with damping 0.1 to 0.25 is stable and bounded in the prototype; the end of a note through a high-gain amp is checked (the last 20 ms at least 50 dB under the peak).
- **Needs Stage 3** to be a pedal in front of the amp on guitar pads: the owner heard the rig way on jangle and preferred it; the pad-sheet way (a wah after the baked amp) was "not usable" on guitars.
- Gate: the owner's ends ("extreme enough") per macro, and the MODE switch; the WAH chip joins the row only if it passes.

## Stage 2: the COMPRESSOR section

A compressor built for sustain, **before the amp, after the wah** (the guitar convention). The owner said yes to its own section after hearing SQUASH's ceiling. The spike's simulation of a plain feed-forward compressor with SQUASH's 2 ms lookahead (its configuration A reproduced SQUASH's ceiling within 0.3 dB, so the model is calibrated) says what it needs:
- a **threshold about 30 dB under the peak** (the missing ingredient: tail-over-body lift choir +19.2 dB, JANGLE +10.2, CHUG +17.4, against SQUASH's best +9.4, +6.5, +7.2);
- a **release of 300 ms or more** (adds 2.6 to 3.5 dB alone); ratio 4:1 is enough (8:1 adds only 1.5 to 1.8 dB); attack about 5 ms keeps a pluck's crest near dry;
- **make-up gain of 12 to 23 dB**, best made on the body with a limiter at the peak; the cost is that it lifts the noise floor, and the amp after it, by the same amount.
Macros, the section's name (a permanent saved key; `squash` is taken) and its neutrals are settled at its own brainstorm. The COMP chip's recipe moves from SQUASH to this section when it passes its gate.

## Stage 3: the board in front of the amp, for guitar pads

The design problem: a chip acts on the pad's baked audio, so on a guitar pad every pedal lands after the amp. The owner preferred the rig way (pedal, then amp) for the wah, and DRIVE, COMPRESSOR and any FUZZ want it too. The direction to brainstorm (not decided): a MAGNET pad's recipe holds its engine patch and its landing chain; a pedal chip on a guitar pad would compose the pedal's sections into that chain and re-render the pad from its patch, instead of rewriting the baked audio; NONE would remove the pedal and restore the landing chain. Open questions for that brainstorm: whether the re-render path exists for every synth pad or only MAGNET's; undo; what AMT means for a pedal inside a chain; which other engines want it. It is its own spec and plan.

## Stage 4: TREMOLO and FLANGER (one spike, one gate)

Two small sections **after the amp, between PHASE and ECHO**: `phase → flanger → tremolo → echo`. Both are free-running (the rack has no tempo context), both run per channel from one modulator shared across the pair so stereo moves together, both take `(Snip, Map<String, Float>) -> Snip` like every section.

- **TREMOLO.** Gain moves between 1 and 1 minus DEPTH: it never inverts (that is what separates it from RING). It **starts open, at full level**, as GATE and WOBBLE do, so a hit's attack is never eaten (PHASE, RING and TAPE start at sin 0). Macros: RATE (Hz), DEPTH, SHAPE (a sine to a click-free square: the square's corners smoothed with a one-pole whose coefficient is matched-Z). Checks: depth exact (peak-to-trough equals DEPTH), no clicks, DEPTH 0 bit-exact bypass.
- **FLANGER.** One short delay (0.1 to 10 ms) swept by a sine LFO, summed with the dry, read before it writes (which is what allows feedback), through the existing interpolated read `Dsp.tap` (linear; the spike decides whether linear interpolation's high-frequency droop in a feedback comb needs more). Macros: RATE, DEPTH, FEEDBACK (two-way: negative hollow, positive jet; capped below 1 by measurement). It does not extend a pad's tail (feedback near 0.7 rings out in tens to a few hundred ms; ECHO and SPRING are the only sections that extend it). Not through-zero.
- **Gate:** each chip's ends ("extreme enough") and a plain-words line per end, on a guitar pad, a drum and a choir; TREM and FLANGE join the row only if they pass.

## Stage 5: FUZZ (deferred)

A section **before the amp**: `squash → fuzz → valve → crunch`. Deferred because the owner's picked DRIVE (VALVE at DRIVE 1) is already gain 1000: a separate law earns its place only if a listen says the drive pedal is not a fuzz. If it comes: normalise, a gain law, an asymmetric starved clip, a DC blocker (about 5 Hz, as VALVE's), TONE, peak-match; always oversampled by 4 (VALVE measured and blocked "only when driven": the 1x path changes the tone); macros FUZZ, STARVE (gated, sputtery decay), TONE; measured first (aliasing on a steady harmonic probe, never a decaying hit; DC under 1e-4; pitch preserved; the end of the note). An octave-up rectifier fuzz stays deferred (it only works on a single note).

## Registration and the hazards the scouts found

(Facts: `.superpowers/sdd/2026-10-pedal-board/scouting.md`, git-ignored; line numbers as of 2026-10-04.)

- Registration is table-driven: one `Section(...)` row in `FxChain.SECTIONS` (the order is the signal path and the JSON order) and one appended `Map<String, Float>?` field after `valve` on `FxChain` (constructor order differs from rack order by design); the field name must equal the section name (a reflection test compares them). `FxChain.VERSION` stays 1 (bumping it throws on every saved `kit.json`). **A section's name is a permanent saved key** (`wah`, the compressor's name, `tremolo`, `flanger`, `fuzz`); an older build silently drops an unknown key.
- **Pinned tests break when a section lands before VALVE:** `FxTest` asserts VALVE sits right after SQUASH (`FxTest.kt:697-698`); the first section placed between them (the WAH goes before SQUASH, so it does not touch this; a FUZZ would) rewrites it. Docs that state the order are edited with each section: the `FxChain` KDoc order line and paragraphs, and `README.md` lines about the rack.
- **Contract tests cover every new section with no edit** (FxTest iterates `SECTIONS`): deterministic and finite and in range under random macros, peak within 0.05 of the source's, stereo bit-identical per channel, JSON round-trip, AMT 0 lands on each macro's neutral, no section widens a mono pad except ENSEMBLE. Exemptions exist (`contractExcluded`, `stereoExcluded`), each with a self-check; none should be needed.
- **A new section's name becomes a forbidden word in preset names** (the name-collision tests build their list from `SECTION_NAMES`). "FUZZ" is already used by two THUMP presets (FUZZ SUB, FUZZ TOM): rename them if a fuzz section lands. Check WAH and the compressor's name against existing preset names first.
- A section before VALVE does not disturb VALVE's pinned tests (goldens at 1e-5, a valve-only chain equal to `Valve.process` exactly; `Magnet.LANDING_VALVE` and `SynthKits.LEAD_VALVE` write all four macros out so a change to VALVE's defaults cannot move a landing). Editing `Valve.kt` is the only way to disturb them.
- **Stage 0 already collides with the SAY (become-strung) design:** both append to `Treatments.EXTRA` after `sectioned` and both change `PadSheetTest`'s chip counts and its inventory. Whichever lands second re-pins the names list and the counts (only `Shuffle.TREATMENTS` is seed-indexed, and it is untouched, so the cost is a merge conflict, not data). The SAY design also appends a section field after `valve` and a row in `SECTIONS`, as each new section here does: the second branch rebases and renumbers.
- **The DSP catalog has nothing** on waveshaping, distortion, oversampling for nonlinearities, tremolo shapes, flangers, wah filters or compressors (its Quick Lookup has no row for them). It supplies the matched-Z one-pole and the DC-blocker form (Smith, catalog #1 eq. 19-2, 19-3, 19-5), the oversampler's window-length law (#1 eq. 16-3, 16-4) and the AM form (#14). Every other number is measured in a spike and is not cited as catalog.

## Out of scope

Octave; a noise gate; a tuner; through-zero flanging; an optical-tremolo model; a routing or pedal-ordering UI (the order is fixed on purpose); tempo-synced rates; stereo widening; presets (authored by ear after each gate, per section that passes).

## Effort, from footprints

Stage 0 (revised): two recipes, one removed recipe, a pre-amp fade and the tests: small. WAH: about PHASE's size plus an envelope follower and a stepped MODE. COMPRESSOR: a compressor with lookahead, about SQUASH's size. Stage 3: a design first. TREMOLO and FLANGER: each about PHASE's size. FUZZ: about VALVE's size.

## Verification (every stage)

`./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test` (the four `:shell` source-scanning laws run only when forced), and the stage's listening gate. A section that fails its gate gets no chip.
