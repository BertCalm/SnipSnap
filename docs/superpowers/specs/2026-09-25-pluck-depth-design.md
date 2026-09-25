# PLUCK depth — STRIKE, BODY, and a tine that is a bar

**Status:** design, approved in conversation 2026-09-25. Not implemented.
**Date:** 2026-09-25
**Plan:** to be written (`docs/superpowers/plans/2026-09-25-pluck-depth-phase-1.md`)
**Parent:** [`2026-09-18-synth-depth-design.md`](2026-09-18-synth-depth-design.md) — this
is PLUCK's share of Phase 2 ("spine across remaining struck engines"), shaped
by its own audition rather than by the tier list that document implies.
**Roadmap:** the `SYNTH_ROADMAP.md` phasing row is added when implementation
starts, not now — the rule FATHOM and RESIN followed.

## Why PLUCK, and why not presets

Josh's complaint (2026-09-24): PLUCK "is too limited." Offered three readings
of that — instrument identity, knob reach, aliveness — he chose all three,
body first. The profile that preceded the choice found nothing to fix in
CPU terms (PLUCK costs roughly a quarter of the fleet average and is the one
engine that could afford a two-hundred-mode body unnoticed) and five things
to fix in design terms:

| Finding | Where |
|---|---|
| A voice is five constants: root, loop cutoff, exciter range, ring length. There is no body, so all four voices are "a string into nothing." `SYNTH_ROADMAP.md:108` originally listed PLUCK's macros as DAMP · BODY · PICK · DOUBLE; BODY became TUNE when pitch needed a knob and never came back | `Pluck.kt:132-137` |
| DAMP drives loop cutoff, feedback gain and render length together, so a bright short pluck and a dark long ring are both unreachable | `Pluck.kt:139,197-198` |
| The render is capped at 1.35 s. The feedback gain at DAMP 0 (0.998) would ring a HARP root for twenty seconds; the *file* is what stops at 1.35 | `Pluck.kt:139` |
| The exciter is one period of filtered noise with no position and no shape | `Pluck.kt:283-294` |
| Velocity falls back to `Velocity.soften` because PICK is not a registered brightness macro, even though `PluckTest` already proves PICK brightens the attack | `Velocity.kt:232,418` |

The 48 presets are disposable by standing decision (all six preset files
say in their KDoc they were "authored from that DSP, not by ear"), so
nothing here preserves their sound. `PluckPresetsTest` keeps them rendering
clean; by-ear re-authoring is the parent spec's Phase 3.

## What the audition said

A throwaway spike (see the appendix) rendered eleven variants per voice at
factory defaults, and Josh auditioned them from a phone on 2026-09-25 with
one chip per clip — CLOSER, SAME or WORSE against the real instrument — and
notes. The verdicts are the design's inputs; the tier list is not.

| Clip | NYLON | KOTO | KALIMBA | HARP |
|---|---|---|---|---|
| Body, half the string's level | same | worse | worse | same |
| Body, equal | same | closer | worse | closer |
| Body, three times | closer | worse | worse | closer |
| Pick at the bridge (0.08) | closer | closer | same | closer |
| Pick at a quarter (0.25) | same | same | same | same |
| Pick at the centre (0.5) | closer | closer | same | closer |
| Body equal + pick quarter | same | worse | worse | same |
| Ring 3.5 s at DAMP 0.05 | closer | closer | — | closer |
| Ring 3.5 s + body | closer | worse | worse | closer |

His words: KOTO "there is a prominent knock sound in the body sound";
KALIMBA "it body has a similar knock sound. Overall kind of a flat sounding
instrument"; HARP "not much difference in the body." Overall: the body makes
them "generally a bit more like an instrument"; pick position does "more"
than the body; the dominant setting "varies across instrument." Asked what
the knock was, he chose "a thump at the very start" over a hum under the
note.

Four conclusions, each of which shapes a section below:

1. **Pick position outranks the body, and only its extremes register.** The
   "usual playing spot" read SAME on every voice. This is the snare gate's
   rule again (`snipsnap-audition-gate-findings`): a macro's extremes must be
   extreme, including the ugly end, because the wanted territory lies past
   where an author would stop.
2. **The ring ceiling is a real limit.** Every voice he marked read CLOSER
   with the cap lifted.
3. **The body helps and the spike's tables knock.** The thump is the low
   body modes (placed at 98–140 Hz with half-second decays, guessed) ringing
   off the attack burst, which the spike fed to the body as raw string
   displacement. It is a defect of the spike's excitation, not a verdict on
   bodies. The combined clip also tested the wrong pair — body plus the
   inert quarter position.
4. **KALIMBA is flat because it is not a string.** No body amount helped it
   and the note says the instrument itself is flat. A kalimba tine is a
   clamped-free bar whose overtones sit near 1 : 6.27 : 17.5; a
   Karplus-Strong loop produces a harmonic series at every setting, so no
   macro range can reach that voice's identity.

## Architecture

PLUCK stays one engine with four voices, one `render(voice, macros)`, and
the existing 4× oversampled render/decimate/level/fade tail. Two paths sit
under it after Phase 3; until then, one.

### The string path — NYLON, HARP, KOTO (and KALIMBA until Phase 3)

```
noise burst ──(PICK low-pass)──(STRIKE comb)──▶ Karplus-Strong loop ──┬──▶ string
                                                                     │
                                          first difference ──▶ Modes.ring ──▶ × BODY ──▶ +
```

- **EXCITE.** The burst is unchanged: one loop period of seeded noise
  through a one-pole at the PICK frequency, zero-meaned. STRIKE adds the
  Jaffe–Smith pick-position comb *to the burst*, `e[i] = x[i] − x[i − d]`
  with `d = round(p · N)`, where `N` is the integer loop length and `p` the
  fractional position along the string. The comb lengthens the exciter to
  `N + d` samples; those extra samples enter the loop as input, not initial
  state, which the loop's `out[i] += fb · lp(tuned)` form already supports
  (the spike proved this against the shipped engine: with `d = 0` it
  reproduced `Pluck.render` bit-for-bit). The comb's notches fall at every
  harmonic `k` where `k · p` is an integer: at `p = 0.5` the even harmonics
  vanish (hollow), and as `p → 0` the comb approaches a differentiator
  (thin, bright, low harmonics cut).
- **The loop** is untouched. Its tuning budget — integer delay, fractional
  allpass, the loop filter's phase lag and the two-tap average — is the
  work of Phase 0b and the comb does not enter it: the comb sits before the
  loop, and `d ≤ N / 2` can never shorten the loop below `MIN_LOOP_SAMPLES`.
- **BODY drive.** The body is driven by the string's **first difference**,
  `drive[i] = s[i] − s[i − 1]`, not by the string itself. Physically the
  bridge force follows the string's slope at the bridge, which is the
  velocity-like quantity, not displacement. Numerically it is a 6 dB per
  octave tilt, and at the 4× render rate it is why the knock goes away: the
  first difference passes 5 kHz at `2·sin(π·5000/176400) ≈ 0.178` and 98 Hz
  at `≈ 0.0035`, so the burst's low content reaches the low body modes 34 dB
  below its mid content, where the spike's displacement drive sent them
  equal. The drive is then RMS-matched to the string so BODY means what it
  says.
- **BODY** rings the drive through `Modes.ring` against the voice's body
  table (a fixed body does not track pitch, so the table is in absolute Hz —
  see "The bodies"), RMS-matches the result to the string, and adds it
  scaled by the macro. **BODY 0 skips the ring entirely and is byte-identical
  to the string path**, the same guarantee WIDTH 0 gives the snare.
- **DOUBLE** is unchanged: a second string, sharp by `1.002–1.012`, at
  `0.7 × DOUBLE`. Both strings feed the one body.

### The tine path — KALIMBA, Phase 3

```
short burst ──(PICK low-pass)──▶ Modes.ring(TINE table, atPosition(STRIKE)) ──▶ tine
                                          first difference ──▶ Modes.ring(box) ──▶ × BODY ──▶ +
```

- `Modes.Material.TINE` is a **clamped-free bar**: Euler–Bernoulli
  eigenvalues `βL = 1.8751, 4.6941, 7.8548, 10.9955`, squared and normalised
  to the first, give `1 : 6.267 : 17.548 : 34.386`. The free-free bar the
  table already carries (`1 : 2.756 : 5.404 : 8.933`) comes from the same
  family, and `Modes.body()` dresses the ratios with the same strike shape.
  Sourcing follows the rule written on `Modes.tableFor`: the ratios are
  recorded in the plan workspace with citations (Fletcher & Rossing,
  *The Physics of Musical Instruments*, the bars chapter; Rossing, *Science
  of Percussion Instruments*, the mbira chapter) before they land in code,
  the way `mode-ratios-research.md` did for the others.
- The exciter is the same PICK-coloured burst, cut to a few milliseconds:
  a tine is struck-plucked by a thumbnail, not rung by a period of noise.
- STRIKE keeps its name and its "position" meaning, and on the tine it uses
  the cantilever's own mode shapes rather than `Modes.atPosition`'s string
  sine: a clamped-free bar's `n`-th shape is
  `φₙ(x) = cosh(βₙx) − cos(βₙx) − σₙ(sinh(βₙx) − sin(βₙx))` with
  `σₙ = (cosh βₙL − cos βₙL) / (sinh βₙL + sin βₙL)`, and mode `n`'s gain is
  weighted by `|φₙ(p·L)| / |φₙ(L)|`. The free tip is an antinode of every
  mode, so `p = 1` is the full strike; the second mode's node near `0.774 L`
  and the third's near `0.5 L` and `0.868 L` are where a pluck goes glassy
  or woody, which is the range STRIKE exists to reach. `p` runs from `0.4 L`
  (the ugly end) to the tip. PICK stays what it is on every voice — exciter
  brightness, which on a tine is thumbnail versus flesh. Decided 2026-09-25
  on Josh's "you tell me"; the gate still judges it.
- TUNE places the fundamental mode at the snapped frequency exactly, so
  `TuningAccuracyTest`'s five-cent bound holds by construction.
- DAMP scales every mode's t60, the way the snare's DECAY does. DOUBLE is a
  second tine, sharp, at the same gain as the string path's second string:
  the doubled-tine mbira is real.
- The tine feeds the same BODY stage as the strings, with a small box body.

## Macros

Six, inside `SYNTH_ROADMAP.md` rule 1's ceiling, and organised by the
parent spec's spine: EXCITE has PICK and STRIKE, the loop has TUNE and DAMP,
BODY has BODY, and DOUBLE is PLUCK's own.

| Macro | Stage | 0 | 1 | Default | Notes |
|---|---|---|---|---|---|
| TUNE | — | root | +24 semitones, snapped | per voice, unchanged | `Keys.harp` and `SynthKits.melodic` depend on the snapping and the roots; both stay |
| DAMP | loop | rings out, ≥ 3.5 s | dead thud, ≈ 0.3 s | per voice, unchanged | the loop's `fb` and `loopHz` mapping is unchanged; only the render length changes (below) |
| PICK | EXCITE | dark exciter | bright exciter | per voice, unchanged | becomes the velocity macro |
| STRIKE | EXCITE | at the bridge, `p = 0.03` | the centre, `p = 0.5` | ≈ 0.75 (`p ≈ 0.25`) | `p = expMap(STRIKE, 0.03, 0.5)`: positions near the bridge change fast, so the map is exponential. The default lands on the quarter position that read SAME as the shipped engine, so a preset that never touches STRIKE sounds as it did |
| BODY | BODY | none, byte-identical to the string | three times the string's RMS | per voice, placeholder | the spike's "dominant" is the top of the range and it read CLOSER on two voices, so it stays reachable |
| DOUBLE | — | one string | honky twelve-string | per voice, unchanged | |

**Render length follows the decay, not a constant.** Today `seconds =
ring × lin(1 − DAMP, 0.35, 1)` capped at 1.35 s cuts a HARP at −26 dB and
fades it in 4 ms. The new rule: render until the loop's output envelope has
fallen 60 dB below its peak, floor 0.25 s, ceiling 4.0 s; `Dsp.fadeTail`
then only ever touches a tail that is already inaudible. A muted pluck stays
a short file and a DAMP 0 harp gets its ring. The 4 s ceiling is a file-size
and kit-export judgement (≈ 350–530 KB per pad at 24-bit mono), not a
sonic one; the gate can move it.

**Per-voice defaults are placeholders until the gate**, and are written as
such in code (a table with a comment naming this document), the way
`LOUDNESS_OFFSET` already is. Starting values from the chips: BODY HARP 0.4,
KOTO 0.35, NYLON 0.5, and KALIMBA 0 until Phase 3 — every body amount made
the string-model kalimba read WORSE, and the string is what Phase 3
replaces; STRIKE 0.75 on every voice except KOTO at 0.6 (a koto is played
with a pick near the bridge).

## The bodies

Each string voice has one fixed body table: a short list of
`(Hz, gain, t60)` rows. A body does not track pitch, so the rows are absolute
frequencies; the implementation passes them to `Modes.ring` as ratios
against a 1 Hz fundamental through a one-line `Modes.fixed(hz, gain, t60)`
helper, so the call site reads as what it is.

**The tables are sourced, not recalled.** This is the same rule that
governs `Modes.tableFor`, and the spike's tables were guesses precisely so
the audition could happen before the sourcing work. Phase 2's first task
writes `body-research.md` in the plan workspace with citations for each
voice before any Hz reaches `Pluck.kt`:

| Voice | Body | What the literature gives | Working values the plan must confirm or replace |
|---|---|---|---|
| NYLON | classical guitar | the air resonance A0 near 100 Hz, the top-plate T1 near 200 Hz, the back-coupled T2 near 250 Hz, then plate modes; Q of order 20–50 (Fletcher & Rossing, the guitar chapter) | 98, 195, 250, 410, 560, 780, 1200, 2400 Hz |
| KOTO | paulownia box, ~1.8 m | body resonances measured on the instrument; the literature is thinner (Ando's koto studies are the starting point) | 140, 205, 310, 470, 690, 1050, 1600 Hz |
| HARP | spruce soundboard | a dense soundboard series from roughly 100 Hz up (Waltham & Kotlicki on the concert harp) | 110, 165, 240, 330, 450, 600, 820, 1100, 1500 Hz |
| KALIMBA | hand-sized box with sound holes | a Helmholtz resonance in the low hundreds of Hz plus box-plate modes; sparse literature, so a measured resonance from a recording is acceptable evidence | 330, 620, 950, 1400, 2100 Hz — Phase 3, with the tine |

**Decays.** The knock fix has two halves. The first difference drive is
one. The other is that a body's lowest modes have moderate Q: the spike gave
the guitar's A0 a 0.45 s t60 (Q ≈ 20, which is physically reasonable) and
still knocked, because the drive was wrong; with the drive fixed, the
lowest mode's t60 is bounded at 0.3 s and the rest follow `Modes.body()`'s
shape, and the gate decides whether the residual thump — which a real
guitar does have on a hard pluck — is character or defect.

**Level.** The body layer is RMS-matched to the string over the whole
render before the macro scales it. `Modes.ring`'s KDoc explains why its raw
output is hundreds of times louder than its input at low frequencies; the
snare peak-normalises for the same reason.

**The PLUCK peak-limiting finding.** Every shipped PLUCK render sits at
`Dsp.levelTo`'s 0.99 ceiling: the burst's crest factor keeps
`MELODIC_LOUDNESS_TARGET` out of reach, so PLUCK is peak-limited where the
other melodic engines are loudness-matched, and reads quieter than they do
on a kit. A body rounds the attack and the target then lands (the spike
measured up to 2× RMS at equal peak). This document records it and does not
fix it: the gate hears BODY first, and `LOUDNESS_OFFSET` exists for what is
left.

## Velocity, seeds, and the cheap wins

- **Velocity through PICK.** `Velocity.brightnessOverride` gains a
  `PluckPatch → "PICK"` line beside the snare's `SNAP` line, after a sweep in
  `PluckTest` proves PICK is monotonic in spectral centroid for every voice —
  the KDoc on that function says exactly why a name match is not enough. A
  soft harp is then re-synthesised darker, not low-passed.
- **Seeds.** `Dsp.seedFor("PLUCK", voice.name)` and
  `Dsp.seedFor("PLUCK", voice.name, "DOUBLE")` replace the literal 11 and 23.
  Renders stay deterministic; PLUCK stops being the one engine off the
  convention.
- **DAMP is not split.** A separate tone-versus-decay macro was on the tier
  list and the audition did not test it; it is not built until it is heard.

## Data flow and compatibility

- `PluckPatch(name, voice, macros)` and its JSON are unchanged in shape.
  `synthesize` already merges defaults under whatever macros a patch
  carries, so a saved four-macro patch loads with STRIKE and BODY at their
  defaults. `Pluck.macrosFor` grows to six `MacroSpec`s; the SYNTH screen
  lays out whatever `macrosFor` returns (RESIN already ships six).
- `Keys.harp(midi)` and `SynthKits.melodic()` call `render` with TUNE only
  and inherit the new defaults. Their sound changes; the presets policy
  accepts that, and `InstrumentSuite`'s harp export changes with it.
- `SCRAMBLE` (`Pluck.scramble`) works over the six macros with no change.
- Export is untouched: mono WAVs through `Cleanup`, `WavWriter`, `Preflight`.
- `PadRecipe.VERSION` is not bumped: old recipes decode and replay with
  defaults for the new macros, and a replayed recipe is "the same patch
  through today's engine," not a sound-identical re-render. Josh's call,
  2026-09-25: pad recipes can change, the app is pre-launch.

## Failure handling

- The `require(exact >= MIN_LOOP_SAMPLES)` in `Pluck.ks` stays and stays
  reachable-by-construction only; STRIKE cannot lower `exact`.
- `Modes.ring` skips modes at or above Nyquist; a body table row above
  88.2 kHz at the render rate is impossible, and the test that builds every
  voice's table asserts every row is below 20 kHz anyway.
- BODY 0 short-circuits before `Modes.ring`, so a voice with an empty body
  table (KALIMBA in Phase 2, if its box is deferred) renders exactly as the
  string path.
- The decay-following render length has a hard 4 s ceiling and a 0.25 s
  floor, so a loop that never decays (it cannot, `fb ≤ 0.998`) or a
  pathological DAMP cannot produce an unbounded or empty buffer.
- Every macro is coerced to `0..1` at entry, as today.

## Phasing and gates

Each phase ends the way the parent spec's phases do: **stop and have him
hear it.** The listening page built for the spike
(`https://claude.ai/artifact/Hyiby2DKd4A2FWX6ZtC7VR`) is the audition
surface: each phase re-renders it from the real engine at the same clip
set — shipped, the macro's extremes, and the defaults — and his chips and
notes are the gate's record.

| Phase | Ships | Audio changes | Gate |
|---|---|---|---|
| 1 | STRIKE macro; decay-following render length with the 4 s ceiling; velocity through PICK; seed convention; `macrosFor` at five | Yes | STRIKE extremes and DAMP 0 across all four voices |
| 2 | `body-research.md`; per-voice sourced tables for the three string voices; first-difference drive; BODY macro with placeholder defaults (KALIMBA at 0); `macrosFor` at six | Yes | BODY at 0 / default / 1 for NYLON, KOTO and HARP; the knock question asked again |
| 3 (outline) | `Modes.Material.TINE` and KALIMBA on the tine path; dispersion allpasses inside the loop for KOTO and HARP (stiff-string inharmonicity, with the allpass phase folded into the loop's tuning budget); sympathetic strings under DOUBLE | Yes | its own spec and plan, written after Phase 2's gate, because the tine spike comes first |

Phase 3's string physics were not tested by the spike and are not designed
here beyond their names. The tine is designed above because the audition
made the case for it; its plan still opens with a throwaway render, the way
this one did.

## Testing

Tests guard properties; they do not author sounds. All new assertions live
in `PluckTest` unless named otherwise, and every threshold below is a
starting number the plan may tighten after the gate, never loosen without
saying why.

- **Tuning** — `TuningAccuracyTest`'s `every Pluck semitone lands within
  five cents` keeps passing at defaults **and** with STRIKE and BODY at both
  extremes: neither the comb nor a fixed body moves the fundamental. In
  Phase 3 it also covers the tine.
- **STRIKE reach** — at the bridge, the fundamental's share of energy
  against harmonics 2–4 is lower than at the centre for every voice; at the
  centre, the second harmonic sits at least 20 dB below where the bridge
  puts it.
- **STRIKE default is inert** — a render at the default STRIKE differs from
  the pre-STRIKE engine by less than a stated spectral distance; the
  audition's "quarter reads SAME" is what this pins.
- **Ring ceiling** — at DAMP 0 the render is at least 3.5 s and its envelope
  at the fade is at least 55 dB below peak; at DAMP 1 it is under 0.5 s.
- **PICK is monotonic** — spectral centroid is non-decreasing across eleven
  PICK steps for every voice; this test is the precondition for the
  velocity override and is written before it.
- **BODY 0 is the string** — byte-identical to a render with the body
  stage bypassed.
- **BODY carries its share** — at BODY 1 the RMS of (render − string) over
  the RMS of the string is within ±20 % of the macro's mapped amount.
- **No knock** — in the first 30 ms of a BODY 1 render, energy below 200 Hz
  is no more than 1.5× that of the BODY 0 render; the first-difference drive
  is what makes this pass.
- **Body tables are sane** — every row below 20 kHz, ascending, with
  positive gain and t60; the table's source is named in a KDoc that the
  test does not check but the reviewer does.
- **Determinism** — `is deterministic` stays; seeds change value, not
  behaviour.
- **Presets** — `PluckPresetsTest` unchanged: clean, non-silent, round-trip,
  names.
- **Tine (Phase 3)** — a `ModesTest` case pins TINE's second and third
  ratios at 6.267 and 17.548 within 0.5 %; a KALIMBA render's measured
  second partial lands within 2 % of 6.27× the fundamental.
- **Tine position (Phase 3)** — the cantilever weighting leaves mode 1
  unchanged across `p` and puts mode 2 at least 20 dB below its tip level
  at `p = 0.774`, its node.

## Out of scope

- **SPACE.** Per-mode stereo through `Modes.ringStereo` is a body feature
  and PLUCK's body is new; WIDTH waits until the body has been heard in
  mono. The FX-rack per-channel audit the parent spec owes applies when it
  arrives.
- **DRIVE.** No saturation on the exciter or in the loop. The sitar buzz is
  a real reach and it is not what the audition asked for.
- **Splitting DAMP.** Not heard, not built.
- **Per-render randomisation.** Pads are one WAV; determinism is the
  product.
- **Re-authoring the 48 presets.** The parent spec's Phase 3.

## Decisions taken at review — 2026-09-25

These were the document's open questions; Josh answered them on review and
the sections above already reflect the answers.

1. **Recipe replay: no version bump.** Pad recipes can change; the app is
   pre-launch. A replayed recipe is the same patch through today's engine.
2. **KALIMBA gets no body until Phase 3.** Every body amount made the
   string-model kalimba read WORSE in the spike because the string is the
   defect; its BODY default is 0 in Phase 2 and its box table ships with
   the tine.
3. **The 4 s ring ceiling stands.**
4. **STRIKE on the tine means position**, weighted by the cantilever's own
   mode shapes so the nodes fall where a real tine has them; PICK remains
   hardness. Josh delegated the call; the Phase 3 gate still judges it.

## Appendix — the spike

`synth/src/test/kotlin/com/snipsnap/synth/PluckBodySpike.kt` (untracked,
throwaway; Phase 1's first task deletes it) is a gated test that renders
eleven WAVs per voice into `$PLUCK_SPIKE_OUT`: the shipped engine, a
bit-identical copy of it, the copy with a body at three levels, with the
pick comb at three positions, both together, and two 3.5 s rings. Its copy
of `Pluck.ks` differed from the shipped render by a maximum sample
difference of 0.0 in every voice, which is what makes the verdicts about
the variants and not about the copy.

Two things it measured that this document relies on:

- **Every shipped PLUCK render sits at the 0.99 peak ceiling** (see "The
  bodies"); the audition set had to be re-levelled to one RMS so the body
  could not win the A/B by being louder.
- **Displacement drive knocks.** Feeding the body the string itself put
  the burst's low content into 98–140 Hz modes at full level. The
  first-difference drive above is the correction, derived from the bridge
  force and confirmed by the 34 dB figure, not by ear yet — Phase 2's gate
  asks the knock question again.

The audition page (`https://claude.ai/artifact/Hyiby2DKd4A2FWX6ZtC7VR`)
holds the verdicts in its `verdicts/{NYLON,KOTO,KALIMBA,HARP,overall}`
documents and stays the audition surface for every phase.
