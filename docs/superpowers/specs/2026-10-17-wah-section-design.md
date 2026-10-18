# The WAH section: a wah pedal for the FX rack

**Status:** design, reviewed section by section with the owner on 2026-10-16 and 2026-10-17; built; listening gate pending. This is Stage 1 of `2026-10-04-pedal-board-design.md`.
**Builds on:** the pedal board's Stage 0 (the PEDALS row and the DRIVE and COMP chips, merged as PR #514), VALVE, and the two round-two listens whose picks and prototype numbers it carries.
**Roadmap row:** the pedal board's row, S30 in `docs/SYNTH_ROADMAP.md`; this stage extends it.

## Why

The owner's first pedal listen found the WAH chip was "just a filter": the CONTOUR recipe behind it ties the sweep's depth to its speed and is about 90 percent over in 30 ms, then leaves a fixed resonant low-pass on the note. The owner: "The wah doesn't sweep like a wah wah. It just sounds like a filter was applied." Round two put three real sweeps in front of them (touch, auto, one rock); they picked **auto** on all four sources (a snare, a choir, both MAGNET guitars) and said "several: a switch": the other moves should stay selectable.

**Success:** a pad tapped with WAH gets a resonant filter that travels for hundreds of milliseconds and keeps moving for as long as the note rings; the three moves exist as recipes; the section passes its owner listen, or the chip does not ship.

## What the owner heard and picked (round two, 2026-10-12)

The prototype was a test-source spike, not production code (`.superpowers/sdd/2026-10-06-pedal-board-stage-0/spike-round2/summary.md`, git-ignored): `Dsp.TptSvf`'s `band` output times its damping `k` plus 0.05 of the dry signal, peak-matched; the centre moves exponentially between a heel of 280 Hz and a toe of 2800 Hz (a decade); `k` 0.2 (Q 5). Three moves, measured by short-time spectral centroid after the landing amp:
- **Touch** (an envelope follower, attack 20 ms, release 100 ms, an 8 dB span under the pad's own envelope peak): 2.55 octaves on the choir, 2.98 on JANGLE, 2.65 on CHUG; opens in 23 to 114 ms and closes in 156 to 503 ms; then parks at the heel (about 3 s of a 4 s guitar note).
- **Auto** (a free-running sine at 2 Hz, starting at the heel): 2.56, 2.82 and 1.75 octaves; the only one that never parks. **The owner's pick.**
- **One rock** (one raised-cosine gesture, up in 250 ms, down in 500 ms, then held at the heel): 2.57, 2.03 and 1.58 octaves; parks for about 3 s of a 4 s note.
The shipped CONTOUR recipe, for comparison, settles in 22 to 37 ms on the guitars.
The touch move with the first brief's numbers (5 ms attack, 150 ms release, a 30 dB span) failed on guitars: it opens the filter before the first analysis window, and the pluck's own decay darkens the note at about the same slope, so the two cancel. The follower is therefore slow (20 ms attack) and its span is narrow (8 dB).

## Decisions taken

1. **A section of its own, `wah`**, replacing the CONTOUR recipe (the `quacked` character was removed at Stage 0). Its name is a permanent saved key. No test blocks it: the preset-name tests that forbid section names cover AEROSTAT, ARCO, FLOTILLA, GLINT, MERCURY and TREMOR, none of which use "wah"; RESIN has two presets named WAH STAB and MELLOW WAH that no test scans (the same overlap the house rule exists to avoid, noted and left).
2. **The "switch" is three weights, not a stepped MODE macro.** The app exposes no per-section dials (a user taps chips and moves AMT), and AMT fades every macro of a recipe toward its neutral, which would flip a stepped MODE part-way (no existing section documents a rule for that; MAGNET's TUNE snaps to semitones and ENSEMBLE's SECTION is a crossfade, so neither is a precedent). So TOUCH, AUTO and ROCK are each a weight from 0 to 1 with neutral 0; "which move" is which weight a recipe sets to 1, and AMT shrinks the sweep smoothly.
3. **One chip.** The PEDALS row gets WAH (the AUTO recipe). Touch and one rock exist as recipe characters, reachable from the CLI's `treat` and from saved kits, with no chip of their own: the row holds six and DRIVE, COMP, WAH, TREM and FLANGE are planned. A TOUCH chip can be added later if the owner asks.
4. **No oversampling.** The filter is linear (a time-varying `TptSvf`, stable under modulation), so none of VALVE's aliasing machinery applies.

## The section

**Slot:** after CONTOUR, before SQUASH (`… eq → contour → wah → squash → valve → crunch …`), the guitar convention (wah, compressor, drive, amp). A new `Section("wah", Wah.MACROS, { it.wah }, { c, m -> c.copy(wah = m) }, Wah::process)` row in `FxChain.SECTIONS` and one appended `Map<String, Float>?` field named `wah` on `FxChain` (the field name must equal the section name; the constructor order differs from the rack order by design). `FxChain.VERSION` stays 1.

**Macros (all in 0..1):**

| Macro | Default | Neutral | Meaning |
| --- | --- | --- | --- |
| TOUCH | 0 | 0 | the weight of the envelope-follower move |
| AUTO | 0 | 0 | the weight of the LFO move |
| ROCK | 0 | 0 | the weight of the one-rock gesture |
| SPEED | 0.5 | 0.5 | the LFO rate (about 0.5 Hz at 0 to 8 Hz at 1, `Dsp.expMap`, 2 Hz at 0.5); the follower's release and the rock's length both scale with the LFO period (release 100 ms, rock 250 ms up and 500 ms down at 0.5) |
| Q | 0.6 | 0.6 | the resonance: damping `k = Dsp.expMap(1 - Q, 0.1, 0.8)`, so 0.6 is about 0.23 (a Q of about 4.35; the owner's prototype used k 0.2, a Q of 5), 1 squeals at k 0.1, 0 is gentle |

A recipe sets a weight and leaves SPEED and Q out (setting a macro exactly at its neutral is one AMT could not move, and the AMT-scaling test false-fails on it).

**Per channel (a shared modulator across a stereo pair; the filter state is per channel):**
- `p_touch(t)`: the envelope follower on the larger channel's `|x|`, converted to dB under the pad's own envelope peak (the rack renders whole pads offline, so the peak is measured in a first pass), mapped so the peak is 1 (the toe) and 8 dB below it is 0 (the heel).
- `p_auto(t) = 0.5 - 0.5 * cos(2 pi f t)`: starts at the heel, free-running (the rack has no tempo).
- `p_rock(t)`: the raised-cosine gesture, then 0.
- `reach = clamp(1.5 * (TOUCH + AUTO + ROCK), 0, 1)` and `pos = clamp(1.5 * (TOUCH * p_touch + AUTO * p_auto + ROCK * p_rock), 0, 1)` (shape: the factor 1.5 keeps the chip's first tap at the pad sheet's default AMT 0.7 at full strength; Stage 0's DRIVE and COMP both read as too weak there until recipes were pushed, and the section must not repeat it; AMT below about 0.67 shrinks the sweep and the wet amount).
- The centre `fc = 280 * 10^pos` Hz; `y = k * band + 0.05 * x` from one `Dsp.TptSvf(rate)` per channel; `out = (1 - reach) * x + reach * y`; then peak-matched to the input's peak (the rack contract). The sample rate is the pad's (never a literal), the centre is kept under 0.45 of it.
- If `TOUCH + AUTO + ROCK < 0.001` the section returns a copy of its input (as PHASE does at DEPTH 0).
- It does not extend a pad's tail (the filter rings a few milliseconds at Q 5).

**Characters (recipes), appended to `Treatments.EXTRA` after `compressed`:** `wahed` = `wah = {AUTO 1.0}` (the WAH chip, via `PadSheet.CHARACTER_FOR`), `touchwahed` = `{TOUCH 1.0}`, `rockwahed` = `{ROCK 1.0}` (CLI and kits only). Names are finalised in the plan against the pinned names list and `Treatments.names`.

**On guitar pads:** until the in-front-of-the-amp design (Stage 3 of the pedal board spec) exists, a chip acts on the pad's baked audio, so on a MAGNET pad the wah lands after the amp. The owner preferred the rig way (the pedal in front of the amp) for jangle at the Stage 0 listen; the gate plays both ways again, with the shipped section.

## Tests and the gate

Section tests (in `WahTest`, in the style of `ValveTest`, bars pinned at the measured value minus a margin the report states, each mutation-checked):
- bit-exact copy at zero weights; deterministic; finite, in range, no DC; peak within 0.05 of the input's; a mono pad stays stereo-identical per channel;
- AUTO at its defaults covers at least 1.5 octaves of short-time spectral centroid (a 23 ms window, 5 ms hop, over the first 600 ms), on the choir and a JANGLE note, against the unprocessed pad as the negative control (it swings under 1.5 octaves); a CONTOUR-recipe baseline was not built, the dry pad is the control;
- ROCK reaches its toe at 152 ms and leaves it at 446 ms at the default SPEED (the 1.5 stretch of the position clamps it before the gesture's 250 ms peak, so the audible rise is 152 ms, not 250, and AUTO likewise rests at the toe for 39 percent of each cycle at AMT 100 and 14 percent at AMT 70); AUTO's period matches `1 / f`; TOUCH opens within 120 ms of a hit and closes as the note decays (the envelope follows the pad), and TOUCH and ROCK parked at the heel are measured and reported, not hidden;
- the last 20 ms of a landed note is at least 50 dB under its peak through a guitar's landing amp (VALVE lifts a quiet tail);
- SPEED and Q sweep a real range at their ends (a plain-words line per end);
- the rack's shared contract tests (FxTest iterates `SECTIONS`) pass with no exemption: deterministic, finite, peak-matched, stereo-identical, JSON round trip, AMT 0 on every neutral, no mono widening; plus a placement test: `wah` sits right after `contour`.

**The owner's gate (short):** the shipped section, not the prototype, on a snare, a choir and both guitars; AUTO at the pad sheet's default AMT and at full; TOUCH and ROCK recipes beside it (ROCK on the choir and both guitars only: on a pad shorter than about 350 ms at the default SPEED the snare's ROCK is the same audio as AUTO, so that clip is not rendered); the ends of SPEED and Q ("extreme enough": the standing rule is that a macro's extremes must be extreme, the ugly end allowed); the guitars both ways. The WAH chip joins the PEDALS row only if the gate passes.

## Registration and hazards

- Registration is table-driven (see the pedal board spec's list). The base moves quickly: other sections have been appended after `valve` since the pedal board spec was written, so the plan's first step re-reads `FxChain`'s constructor, `SECTIONS` and the pinned lists (`FxTest`'s order tests, `TreatmentsTest`'s names list, `PadSheetTest`'s inventory) at the branch's actual base.
- Docs that state the rack's order are edited with the section: the `FxChain` KDoc order line and its rationale paragraphs, `README.md`'s rack lines, `docs/CLI.md`'s `treat` reference (the new characters).
- The spec's other merge hazards stand (the SAY design appends to `Treatments.EXTRA` and `FxChain` too; whichever lands second re-pins the lists).
- The catalog has nothing on wah filters (the pedal board spec's note); the numbers here are the spike's, measured.

## Out of scope

A noise gate in the wah; an expression-pedal or MIDI control (the app has no per-section dials); a tempo-synced rate (the rack has no tempo); a stepped MODE macro; a TOUCH chip (later, if asked); the in-front-of-the-amp rendering (Stage 3); presets for any engine.

## Effort, from footprints

About PHASE's size (an LFO, a shared modulator, per-channel state) plus an envelope follower and a first pass for the peak: a section of about 150 hand-written lines, its test class, the registration edits and the three characters.

## Verification

`./gradlew :shell:cleanTest :cli:cleanTest`, then `./gradlew :synth:test :shell:test :cli:test` (the `:shell` source laws run only when forced), and the owner's gate. A section that fails its gate ships without a chip.
