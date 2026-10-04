# The pedal board: guitar-pedal effects for the FX rack

**Status:** design, reviewed section by section with the owner on 2026-10-04; not yet planned or built.
**Builds on:** VALVE (the rack's amp section, `2026-09-29-magnet-valve-design.md`) and the MAGNET guitar engine (PR #438). Nothing in this design starts until #438 has merged.
**Roadmap row:** to be assigned when the first stage is planned (the table is numbered by arrival).

## Why

The owner asked whether the app is "doing effects pedals". The MAGNET spec had put pedals out of scope ("a fuzz law, a noise gate, a tuner, pedals: VALVE presets or later sections"). This is that later step.

**Intent (owner's answer, option C):** pedals for both audiences: the guitar voices (JANGLE and CHUG, so a guitar pad sounds like a rig) and any pad (a fuzz on a drum, a wah on a choir). Start small: pedal-style recipes over the sections the rack already has, and a new section only where the rack has a real gap and a listen proves it.

**Success:** a user taps a pedal on a pad and hears the pedal they asked for; every new section earns its place at an owner listening gate, the way VALVE and MAGNET did; nothing existing changes sound or moves on the pad sheet.

## What the rack already has

The per-pad rack has 19 sections in a fixed order (`FxChain.SECTIONS`, the order is "a playability rule wearing an architecture hat: no routing screen, no wrong answers"): SPEED, SWELL, SMEAR, GHOST, SPIKE, EQ, CONTOUR, SQUASH, VALVE, CRUNCH, RING, DUB, VINYL, TAPE, ENSEMBLE, PHASE, ECHO, SPRING, MOTION.

| Pedal | In the rack today |
| --- | --- |
| Compressor, EQ, chorus, phaser, delay, spring reverb, tape, bit-crush, ring mod, amp | Yes: SQUASH, EQ, ENSEMBLE, PHASE, ECHO, SPRING, TAPE, CRUNCH, RING, VALVE |
| Fuzz or overdrive before the amp | No. VALVE's DRIVE is the amp. |
| Wah, envelope filter | Partly. CONTOUR sweeps a ladder filter once from the hit (a one-shot sweep behaves like an auto-wah), but is a low-pass, not a resonant band-pass. |
| Tremolo, vibrato | No. RING is sum-and-difference and its slowest rate is 30 Hz. |
| Flanger | No. PHASE is allpass notches, not a comb. |
| Octave | No. SPEED is the transport (the whole pad's pitch), not the played note. |
| Noise gate | No. GATE is a keyed rhythmic chop outside the rack. |

The fixed rack order is already the guitar-pedal convention: wah, compressor, fuzz or drive, amp, then modulation, delay and reverb. A pedal takes its natural slot; no routing is added.

## Decisions taken

1. **Staged hybrid** (of three approaches offered; presets-only and one-section-with-a-type-switch were declined). Recipes first, then three new sections, each at its own gate.
2. **A pedal is a recipe or a section.** A recipe is a named chip over existing sections (the way PUNCH is VALVE at 0.6): no new DSP. A section is new DSP with three macros, a fixed slot, and a gate. The pad sheet's AMT stepper fades either toward its neutral; nothing new to learn.
3. **Placement on the pad sheet: a new seventh row, PEDALS**, six chips: FUZZ, DRIVE, COMP, WAH, TREM, FLANGE. Nothing moves: PHASE, ENSEMBLE and SECTION keep their places. This bends the rule that rows group by what an effect does rather than what it is, on purpose: a guitarist looks for pedals by pedal name. (Declined: regrouping by function with a MOD row, which would have moved three existing chips.)
4. **The process for every stage** is the MAGNET and VALVE process: ledger, per-task review, a final review, an owner listening gate; a section that fails its gate ships without a chip.

## Stage 0: the PEDALS row and three recipes (no new DSP)

The row (`Treatments.PEDAL_SEGMENTS`, appended to `Treatments.ROWS`; the pad sheet draws from `ROWS`, so a row is one list entry plus the two shell tests that pin the layout, `PadSheetTest` and `UatSimTest`). It starts with three chips, and gains FUZZ, TREM and FLANGE as their sections pass.

New characters are appended to `Treatments.EXTRA`, never to `Shuffle.TREATMENTS` (a seeded bank indexes it; the names list is pinned in order). A chain must not set a macro exactly at its neutral (AMT could never move it; the AMT-scaling test false-fails on it): `amped` leaves TONE out for this reason.

| Chip | Recipe (starting shape values, to be heard at the gate) |
| --- | --- |
| DRIVE | VALVE with a low-to-middle DRIVE and the cabinet out of the way: a drive pedal into a clean amp (CAB's neutral is the transparent end, so the recipe uses the nearest non-neutral value or omits it; settled in the plan) |
| COMP | SQUASH alone, tuned to sustain (the remix bank's `punched` is SQUASH plus CRUNCH; COMP must not be a copy of it) |
| WAH | CONTOUR with CREAM up and a fast, deep SWEEP (CUTOFF's neutral is 1, fully open) |

**Gate (short):** for each chip, on a guitar pad (JANGLE and CHUG), a drum and a choir: right, not enough, too much, or not usable. The WAH answer also decides whether WAH stays a recipe or becomes its own section (Stage 3, not scheduled).

## Stage 1: FUZZ

A new section, **before the amp**: `squash → fuzz → valve → crunch` in the fixed order.

- **Signal path (proposal; every number is "shape" until a spike measures it):** normalise to peak 1, a gain law on FUZZ, an asymmetric starved clip, a DC blocker (about 5 Hz, as VALVE's), TONE, peak-match. Always oversampled by 4 (VALVE's zero-stuff and `Tide.bandLimit`), because VALVE measured and blocked the "only when driven" idea: the 1x path changes the tone.
- **Macros:** FUZZ (how hard it clips), STARVE (how gated and sputtery it gets as the note decays: the velcro), TONE (centred; 0.5 is flat so AMT fades to flat). Neutrals chosen so AMT 0 does nothing.
- **Why the DC blocker is not optional:** an asymmetric clip leaves DC, and DC biases VALVE's tube. VALVE normalises its input, so a hot FUZZ does not change VALVE's level; it does flatten the crest factor, so VALVE's SAG becomes a constant offset. The FUZZ chip therefore pairs the fuzz with a mild VALVE (a fuzz pedal into a clean amp).
- **Measured first (a Phase 0 spike, as VALVE's was):** aliasing on a steady harmonic probe (never a decaying hit: that mis-ranks), DC under the 1e-4 bound, pitch preserved (interpolated autocorrelation, cents), level-matched, the end of the note (a high-gain stage lifts a quiet tail: the last 20 ms at least 50 dB under peak, MAGNET's bar), the cost per rendered second.
- **Gate:** blind, on a guitar pad, a drum and a choir: is it a fuzz, an amp, or a bit-crush? Then the ends of FUZZ, STARVE and TONE ("extreme enough": the standing rule is that a macro's extremes must be extreme, and the ugly end is allowed).
- A deliberately **deferred** variant: an octave-up rectifier fuzz. It only works on a single note and pads are often chords.

## Stage 2: TREMOLO and FLANGER (one spike, one gate)

Two small sections **after the amp, between PHASE and ECHO**: `phase → flanger → tremolo → echo`. Both are free-running (the rack has no tempo context), both run per channel from one modulator shared across the pair so stereo moves together, both take `(Snip, Map<String, Float>) -> Snip` like every section.

- **TREMOLO.** Gain moves between 1 and 1 minus DEPTH: it never inverts (that is what separates it from RING). It **starts open, at full level**, as GATE and WOBBLE do, so a hit's attack is never eaten (PHASE, RING and TAPE start at sin 0). Macros: RATE (Hz), DEPTH, SHAPE (a sine to a click-free square: the square's corners smoothed with a one-pole whose coefficient is matched-Z). Checks: depth exact (peak-to-trough equals DEPTH), no clicks, DEPTH 0 bit-exact bypass.
- **FLANGER.** One short delay (0.1 to 10 ms) swept by a sine LFO, summed with the dry, read before it writes (which is what allows feedback), through the existing interpolated read `Dsp.tap` (linear; the spike decides whether linear interpolation's high-frequency droop in a feedback comb needs more). Macros: RATE, DEPTH, FEEDBACK (two-way: negative hollow, positive jet; capped below 1 by measurement). It does not extend a pad's tail (feedback near 0.7 rings out in tens to a few hundred ms; ECHO and SPRING are the only sections that extend it). Not through-zero.
- **Gate:** each chip's ends ("extreme enough") and a plain-words line per end, on a guitar pad, a drum and a choir; TREM and FLANGE join the row only if they pass.

## Registration and the hazards the scouts found

(Facts: `.superpowers/sdd/2026-10-pedal-board/scouting.md`, git-ignored; line numbers as of 2026-10-04.)

- Registration is table-driven: one `Section(...)` row in `FxChain.SECTIONS` (the order is the signal path and the JSON order) and one appended `Map<String, Float>?` field after `valve` on `FxChain` (constructor order differs from rack order by design); the field name must equal the section name (a reflection test compares them). `FxChain.VERSION` stays 1 (bumping it throws on every saved `kit.json`). **The section names `fuzz`, `tremolo` and `flanger` are permanent saved keys**; an older build silently drops an unknown key.
- **One pinned test breaks on the first FUZZ build:** `FxTest` asserts VALVE sits right after SQUASH (`FxTest.kt:697-698`); it is rewritten as squash, fuzz, valve, crunch, with a FUZZ placement test and an absent-section-is-byte-stable check. Docs that state the order are edited with it: the `FxChain` KDoc order line and the VALVE paragraph, and `README.md` lines about the rack.
- **Contract tests cover every new section with no edit** (FxTest iterates `SECTIONS`): deterministic and finite and in range under random macros, peak within 0.05 of the source's, stereo bit-identical per channel, JSON round-trip, AMT 0 lands on each macro's neutral, no section widens a mono pad except ENSEMBLE. Exemptions exist (`contractExcluded`, `stereoExcluded`), each with a self-check; none should be needed.
- **"FUZZ" becomes a forbidden word in preset names** (the name-collision tests build their list from `SECTION_NAMES`). No MERCURY or ARCO preset uses it, but two THUMP presets are named FUZZ SUB and FUZZ TOM (no test scans THUMP names): rename them with Stage 1, to keep the house rule that a preset name never collides with a section.
- A hot FUZZ before VALVE does not disturb VALVE's pinned tests (goldens at 1e-5, a valve-only chain equal to `Valve.process` exactly; `Magnet.LANDING_VALVE` and `SynthKits.LEAD_VALVE` write all four macros out so a change to VALVE's defaults cannot move a landing). Editing `Valve.kt` (for example to extract the shared oversampler or DC blocker, which are inline in `Valve.process` and would otherwise be copied) is the only way to disturb them; the plan decides copy or extract.
- **A merge collision to coordinate:** the SAY design (`2026-09-30-become-strung-say-design.md`) also appends a section field after `valve` and a row in `SECTIONS`; two branches adding there will conflict on the same lines. Order the work, or have the second branch rebase and renumber.
- **The DSP catalog has nothing** on waveshaping, distortion, oversampling for nonlinearities, tremolo shapes or flangers (its Quick Lookup has no row for them). It does supply the matched-Z one-pole and the DC-blocker form (Smith, catalog #1 eq. 19-2, 19-3, 19-5), the oversampler's window-length law (#1 eq. 16-3, 16-4) and the AM form (#14). Every other number is measured in a spike and is not cited as catalog.

## Out of scope

Octave; a noise gate; a tuner; through-zero flanging; an optical-tremolo model; a routing or pedal-ordering UI (the order is fixed on purpose); tempo-synced rates; stereo widening; WAH as its own section (decided at the Stage 0 gate); presets (authored by ear after each gate, per section that passes).

## Effort, from footprints

Stage 0: a list entry, three recipes and two shell tests: small. FUZZ: about VALVE's size (VALVE was about 300 hand-written lines with its upsampler and tests) plus the order-test and docs edits. TREMOLO and FLANGER: each about PHASE's size.

## Verification (every stage)

`./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test` (the four `:shell` source-scanning laws run only when forced), and the stage's listening gate. A section that fails its gate gets no chip.
