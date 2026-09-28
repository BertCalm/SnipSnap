# SILK Phase 1b Implementation Plan — SCALE, INFLECT, OUD, GUZHENG

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give SILK its own scale system (SCALE + TUNE walking scale degrees,
INFLECT nudging between them), extend `Strings` with the two linear in-loop
stages OUD and GUZHENG need (dispersion, multi-loop courses) plus the pitch
envelope OUD's SLIDE needs, and ship the first two SILK voices — OUD and
GUZHENG — registered end to end (patch, presets stub, velocity, SynthScreen,
determinism/fuzz suites, KIT tune readout).

**Architecture:** Phase 1a proved `Strings` is PLUCK's loop, exactly, and
nothing in this phase is allowed to change that proof. New capability is
added as **optional stages, off by default** — `Strings.Dispersion` (a
stiffness allpass cascade), `Strings.Course` (N detuned loops summed) — plus
a per-sample pitch envelope on `Strings.Loop` that re-solves the tuning
budget as the target frequency moves. `Silk.kt` composes these into OUD and
GUZHENG the way `Pluck.kt` composes `Strings.pluck`; `SilkScales.kt` holds
the scale table and the TUNE/SCALE/INFLECT-to-frequency math shared by every
future SILK voice, not just these two.

**Tech Stack:** Kotlin/JVM (`:synth` module, Gradle, JDK 17, JUnit via
`kotlin("test")`), the existing `Dsp`, `Modes`, `Strings` and `Pluck`-adjacent
registration points (`Patches.kt`, `Presets.kt`, `Velocity.kt`).

**Spec:** `docs/superpowers/specs/2026-09-27-silk-string-engine-design.md` —
"TUNE and SCALE", "INFLECT", "The tuning budget, extended", "The voices"
(OUD, GUZHENG), "Macros", "Data flow and compatibility", "Testing", "Phasing
and gates" (row 1b).

**Research:** `docs/superpowers/plans/2026-09-27-silk-research.md` §2 (oud),
§3 (guzheng), §5 (tuning) — every sourced number below cites back to one of
these.

## Why this phase's shape

Phase 1a's rule continues: **every stage `Strings.Loop` gains must default to
exactly PLUCK's loop with the stage off**, so `StringsTest`'s frozen-copy grid
stays the proof after this phase too. This phase does *not* touch `Pluck.kt`,
`PluckTest`, or any PLUCK preset — the only files PLUCK depends on
(`Strings.kt`) get additive, default-off changes, verified by re-running
Phase 1a's two proofs unmodified before anything else.

OUD and GUZHENG are chosen first (per the spec's phasing) because both are
PLUCK's loop plus **linear** additions only — no nonlinearity, no stability
risk beyond what an allpass coefficient bound already handles. SANTUR (a new
exciter and a resonator bank) and SHAMISEN (the one nonlinear stage, sawari)
come after, on a toolkit this phase has already exercised.

## Global Constraints

- **PLUCK's two proofs stay green, unmodified, throughout.** `PluckTest`
  (36/36 including the Task 1 pin) and `StringsTest`'s frozen-copy grid
  (384 + 108 cases) are run before Task 1's first edit and after every
  later task. Any change to their expected values is this plan going wrong,
  not the tests.
- **Every new `Strings` stage defaults to off, and "off" means bit-identical
  to Phase 1a's `Strings.pluck`.** `Dispersion` with `M = 0` (or coefficient
  `0`), `Course` with `N = 1`, the pitch envelope with no target change, must
  each reduce to today's loop, checked the same way Phase 1a checked PLUCK:
  a frozen-reference grid comparison, not a tolerance.
- **The tuning budget's rule is absolute:** every stage in the loop pays for
  its own phase delay at the fundamental before the loop splits into
  integer + fraction. `Strings.tune` gains a `dispersionDelay` term; nothing
  else about its shape changes.
- **Sourced values are cited inline** (`// §2, Erkut 2002` and the like) so a
  reviewer can find the number without re-opening the research doc; "shape,
  not measurement" values say so in the same comment, per the spec's rule.
- **`SCALE` table rows ship only if dataset-confirmed or equal-division** —
  the spec's table, verbatim. No new row is invented in this phase; if a row
  is missing it stays missing.
- **Commands run from the repo root.**
  One class: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.<Name>"`.
  The whole suite: `./gradlew --no-daemon test`.
  Native harness (unaffected, run anyway per Phase 1a's practice):
  `ctest --test-dir build/native-tests`.
- **Never push on an inconclusive test run.** A background or timed-out
  `gradlew` run is not evidence; confirm a genuine `BUILD SUCCESSFUL` line
  and fresh test-report timestamps (the discipline this session already
  holds itself to) before every commit.
- **Every commit ends with:**
  ```
  Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim
  ```
- **Branch:** `claude/sound-design-tools-mdmrbw` (continues from Phase 1a's
  merged history — restart from the base branch first if that branch was
  deleted after merge). This plan lands first in its own docs PR, for
  review; implementation (Tasks 1–9) is one further PR, opened after Task 9.

---

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/SilkScales.kt` | **new** — the SCALE table (14 rows from the spec), `TUNE`+`SCALE`+`INFLECT` → frequency |
| `synth/src/main/kotlin/com/snipsnap/synth/Strings.kt` | **modified** — `Dispersion`, `Course`, the pitch envelope on `Loop`; `tune` gains `dispersionDelay` |
| `synth/src/main/kotlin/com/snipsnap/synth/Silk.kt` | **new** — `enum class SilkVoice { OUD, GUZHENG }`, `macrosFor`, `defaults`, `scramble`, `synthesize`, `render` — the PLUCK-shaped entry points |
| `synth/src/main/kotlin/com/snipsnap/synth/SilkPatch.kt` | **new** — the recipe type, mirroring `PluckPatch`/`ResinPatch` |
| `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt`, `Presets.kt`, `Velocity.kt` | one branch each for `Engine.SILK` |
| `app/.../SynthScreen.kt` | `Engine.SILK`, both voices, `DrumClass.TONAL` |
| `synth/src/test/kotlin/com/snipsnap/synth/SilkScalesTest.kt` | **new** — table integrity, degree math, INFLECT bounds |
| `synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt` | **modified** — off-by-default grids for `Dispersion`, `Course`, the pitch envelope; the erratum-pinning coefficient test |
| `synth/src/test/kotlin/com/snipsnap/synth/SilkTest.kt` | **new** — in-tune, STIFF-sharpens, COURSE-beats, SLIDE, PRESS, determinism, fuzz, classifier |
| `docs/SYNTH_ROADMAP.md` | an S16 row (S15 is Phase 1a) |

---

### Task 1: `SilkScales` — the table, and TUNE + SCALE without INFLECT yet

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/SilkScales.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/SilkScalesTest.kt`

- [ ] **Step 1: The table.** Fourteen rows, verbatim from the spec's "The
  table" (cents-above-root lists, each row's period — 1200 for all but
  BOHLEN_PIERCE's 1902 — and a `.scl`-style provenance comment per row, e.g.
  `// dataset (Ho & Han 1982 via DaMuSc T0337)`). `CHROMATIC` is
  `0..1100 step 100` — PLUCK's own behaviour, so a SILK voice on CHROMATIC at
  INFLECT-centre reproduces 12-EDO exactly.
- [ ] **Step 2: `snap`.** `fun snap(macro: Float): Scale = TABLE[(macro * (TABLE.size - 1)).toInt()]` —
  the TINES `RATIO` precedent named in the spec, so SCALE's snapping is
  provably the same pattern already tested elsewhere.
- [ ] **Step 3: `frequencyFor` (TUNE + SCALE only).**
  `degree = round(tune * 2 * scale.cents.size)`,
  `hz = root * 2^((octave * scale.period + scale.cents[degree mod scale.cents.size]) / 1200)`,
  matching the spec's formula exactly, `octave = degree / scale.cents.size`.
  BOHLEN_PIERCE's period is 1902, not 1200 — the formula must read the
  scale's own period, not assume it.
- [ ] **Step 4: Tests.**
  - Every row's `cents` starts at 0 and stays within its period; every row
    closes (`period - cents.last()` is a musically sane final step, not
    required to equal a step size — BOHLEN_PIERCE and the equal divisions
    close exactly, the dataset rows are checked against the spec's own
    closing note, e.g. PENTATONIC's Pythagorean 1201 rounds to 1200 here).
  - `snap` at macro 0 and 1 returns the first and last row; at the values
    the spec names as each voice's default (RAST, SHUR, PENTATONIC,
    MIYAKO_BUSHI) returns that row.
  - `frequencyFor` at TUNE's centre lands on the scale's own root; at
    TUNE = 1 lands exactly two periods above root (one octave for the
    1200-cent rows, one tritave for BOHLEN_PIERCE).
  - CHROMATIC reproduces `Pluck`'s existing semitone formula bit-for-bit at
    ten sampled TUNE values (the guard that adding SCALE never silently
    changes what "no scale" means).
- [ ] **Step 5: Run, commit.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.SilkScalesTest"`
Expected: PASS.

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/SilkScales.kt \
        synth/src/test/kotlin/com/snipsnap/synth/SilkScalesTest.kt
git commit -m "SilkScales: the SCALE table, and TUNE walking its degrees

Fourteen rows from research §5 - dataset-confirmed or equal-division
only, each with its provenance as a .scl-style comment. CHROMATIC
reproduces PLUCK's own 12-EDO formula bit for bit. INFLECT is next.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 2: INFLECT

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/SilkScales.kt`, `SilkScalesTest.kt`

- [ ] **Step 1: `frequencyFor` gains `inflect: Float = 0.5f`.** Centre detent:
  `val cents = if (abs(inflect - 0.5f) <= 0.02f) 0f else (inflect - 0.5f) * 100f`
  (0.5 → 0¢, 0 → −50¢, 1 → +50¢, linear); added to the degree's cents before
  the `2^(.../1200)` conversion. The detent is exact — a value inside the
  band contributes literally `0f`, not a value that merely rounds to it.
- [ ] **Step 2: Tests.** INFLECT 0 and 1 read −50¢/+50¢ (±5¢ tolerance is
  the *measurement* tolerance in later pitch tests; this unit test checks
  the formula, so it can assert the cents value exactly). Every value in
  `0.48..0.52` produces exactly the un-inflected frequency. A value outside
  the detent band by one ULP is *not* zeroed (guards the boundary, not just
  the middle).
- [ ] **Step 3: Run, commit.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.SilkScalesTest"`
Expected: PASS.

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/SilkScales.kt \
        synth/src/test/kotlin/com/snipsnap/synth/SilkScalesTest.kt
git commit -m "SilkScales: INFLECT - a bounded, centred nudge on the degree

+-50 cents, linear, with an exact detent inside +-0.02 of centre so a
knob nudged back to the middle lands on the degree, not a cent off it.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 3: `Strings.Dispersion` — the stiffness allpass cascade

This is the phase's highest-risk task: the guzheng prototype's dispersion
filter was the design bug the whole project started from (an allpass with
gain > 1, the wrong sign for "stiffer strings go sharp"). The corrected
design (Rauhala & Välimäki 2006, corrected per Rauhala's 2007 dissertation
Eq. 3.8) goes in with its own erratum-guarding test before it is wired into
the loop.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Strings.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt`

- [ ] **Step 1: `Dispersion` class.** A cascade of `M` first-order allpasses
  in transposed form (`out = a*x + z1; z1 = x - a*out`, the corrected form
  from the spec's prototype review — *not* the prototype's untransposed
  one), one shared coefficient `a`, `a ∈ (-0.95, 0]` clamped. `M` and `a` are
  derived from an inharmonicity coefficient `B` via the corrected
  Rauhala/Smith design equation (spec's "The tuning budget, extended"); if
  the design yields `D ≤ 1` (flat partials, the wrong direction), the
  cascade is bypassed — `Dispersion.forB(b: Double, f0: Float): Dispersion?`
  returns `null` in that case, and callers treat `null` as "no stage".
- [ ] **Step 2: `dispersionDelay`.** Closed-form phase delay of the cascade
  at `f0`: `M × (phase delay of one allpass stage at ω0)`. Pin one published
  coefficient (the spec names Van Duyne's CLM piano table, −0.92…−0.04, or
  Smith's Faust port) in a test so a future edit can't reintroduce the
  DAFx-06 `ln M`/`ln B` erratum.
- [ ] **Step 3: Wire into `Loop`.** `Loop` gains an optional
  `dispersion: Dispersion? = null` constructor param; when non-null, its
  cascade runs on `tuned` before the loop low-pass (between the tuning
  allpass and `loopLp.lp(...)`), in the position the spec's architecture
  diagram gives (`loop LP ─▶ [dispersion]` reads loop-then-dispersion in
  the diagram — confirm against the diagram's arrow order before wiring;
  if the diagram and this step disagree, the diagram wins and this step is
  corrected before merging). `Strings.tune` gains a `dispersionDelay: Double = 0.0`
  parameter, subtracted in `exact` alongside the existing terms.
- [ ] **Step 4: The off-by-default grid.** Extend `StringsTest`'s pattern:
  render with `dispersion = null` across the existing 384-case grid's
  frequencies/rates/damps and assert identity with `LegacyPluckLoop.ks`
  (already true, but this step's real job is proving the *plumbing* — the
  new nullable parameter threaded through `tune`/`Loop`/`pluck` — doesn't
  perturb the no-dispersion path; a copy-paste of Phase 1a's grid with the
  new parameter explicitly passed as `null`/`0.0` at every case is the
  cheapest proof).
- [ ] **Step 5: The sharpening test.** At a fixed `f0` and a representative
  `B` (guzheng's sourced range), assert partial `n`'s measured frequency
  divided by `n·f0` rises with `n` over the first eight partials (this is
  Testing item 2 from the spec, pulled forward because Task 3 is exactly
  where a sign error would hide). At `B = 0` (or wherever the design
  bypasses), partials stay within 5 cents of harmonic.
- [ ] **Step 6: Run, commit.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.StringsTest"`
Expected: PASS — the original 384+108+5 cases, plus the new off-by-default
grid and the two dispersion tests.

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Strings.kt \
        synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt
git commit -m "Strings: Dispersion - the corrected stiffness allpass cascade

Transposed first-order allpasses, a < 0 so partials go sharp (the sign
the guzheng prototype got backwards); B->a via the corrected Rauhala
design equation, bypassed where it would flip sign. Off by default -
the frozen-copy grid still holds with dispersion = null everywhere.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 4: `Strings.Course` — N detuned loops, summed

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Strings.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt`

- [ ] **Step 1: `Course` class/function.** `N` loops (1 to 4) at seeded
  detunes in cents around `f0` (`Dsp.seedFor`, per the spec — a voice+note
  seed so one pad's shimmer is stable across renders and two pads differ),
  each its own `Loop` with slightly unequal feedback so the set decays
  unevenly, summed sample by sample. `N = 1` is a single, undetuned loop —
  identical to calling `Strings.pluck` directly.
- [ ] **Step 2: The off-by-default proof.** `Course(N = 1, spread = 0f, ...)`
  compared sample for sample against plain `Strings.pluck` at the same
  parameters, across a grid — the same discipline as Task 3's Step 4.
- [ ] **Step 3: The beating test.** At `N = 2`, a nonzero spread, envelope
  (RMS in short windows, or peak-to-peak of the Hilbert envelope — pick
  whichever `Dsp` already has a helper for, otherwise the simplest windowed
  RMS) shows amplitude modulation that `spread = 0` does not.
- [ ] **Step 4: Run, commit.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.StringsTest"`
Expected: PASS.

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Strings.kt \
        synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt
git commit -m "Strings: Course - N detuned loops summed, N=1 is plain pluck

Seeded per voice+note so one pad's shimmer is stable and two pads
differ, unequal feedback so a course decays unevenly. N=1 reproduces
Strings.pluck exactly - the off-by-default proof.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 5: The pitch envelope — re-solving tuning as the delay moves

Needed by OUD's SLIDE (glide into the note from below). This is new code
*beside* the fixed-tuning path, not a change to it — PLUCK and every
SILK voice that never slides must never enter it.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Strings.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt`

- [ ] **Step 1: `Loop.retune(freq: Float)`** (or a constructor-time glide
  schedule — pick whichever keeps `next()`'s hot path simplest to reason
  about) that re-solves `Strings.tune` at the new frequency and updates `n`
  (by resizing the ring, carefully — a shrinking `n` must not read
  uninitialized history; only grow-then-settle, per the spec's "SLIDE
  starts *below* the target, so it only lengthens the loop", removes the
  shrink case for OUD specifically, but the method itself should not
  silently assume that direction if PRESS or the SHAMISEN glide reuse it
  later) and `a` while carrying `apX1`/`apY1` state through unchanged.
- [ ] **Step 2: The off-by-default proof.** A `Loop` that is never retuned
  (no `retune` call across its lifetime) renders identically to today's
  `Loop` — same grid discipline as Tasks 3 and 4.
- [ ] **Step 3: The glide test.** A loop retuned partway through a render
  from a lower frequency to a target shows a pitch track (measured by
  zero-crossing or autocorrelation over short windows — whichever `Dsp` or
  the test tree already has) starting below the target and reaching it,
  matching the direction, not yet wired to any voice.
- [ ] **Step 4: Run, commit.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.StringsTest"`
Expected: PASS.

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Strings.kt \
        synth/src/test/kotlin/com/snipsnap/synth/StringsTest.kt
git commit -m "Strings: Loop.retune - a pitch envelope beside the fixed path

Re-solves the tuning budget as the target frequency moves, carrying
the allpass state through. A Loop that is never retuned is unchanged -
OUD's SLIDE is the first caller, next.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 6: `Silk.kt` + `SilkPatch.kt` — the OUD voice

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Silk.kt`
- Create: `synth/src/main/kotlin/com/snipsnap/synth/SilkPatch.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/SilkTest.kt`

- [ ] **Step 1: `SilkVoice` and the nine macros.** `enum class SilkVoice { OUD, GUZHENG }`
  (SANTUR/SHAMISEN join in Phases 2–3). `macrosFor` returns the shared seven
  (TUNE, SCALE, INFLECT, DAMP, PICK, STRIKE, BODY) plus OUD's two (COURSE,
  SLIDE) — the spec's "Macros" table. Defaults: OUD's SCALE default is RAST.
- [ ] **Step 2: OUD's `synthesize`.** `Course(N = 2)` at COURSE's spread,
  detune seeded per the spec; excitation is `Strings.pluckExciter` (the
  pick burst, STRIKE low by default per the spec) plus a short high-passed
  tick for the risha (shape, not measurement — say so in the comment);
  SLIDE drives `Loop.retune` from below, both loops of the course sharing
  one pitch envelope so the detune survives the glide, with a slightly
  lower loop gain for the slide's duration (Erkut, §2) to model the
  fretting finger's extra damping; BODY drives the two-mode table
  (113 Hz Q 9.91, 182 Hz Q 10.06 — Erkut 2002, §2) via `Modes`, the same
  first-difference drive `Strings.bodyRing` already does for PLUCK.
- [ ] **Step 3: `SilkPatch`.** Mirrors `PluckPatch`/`ResinPatch`'s shape —
  macro map, `scramble`, whatever `Patches`/`Presets`/`Velocity` need to
  treat it like every other engine's patch type.
- [ ] **Step 4: `render`/`renderWith`.** Same shape as `Pluck.render` —
  oversampled render, decimate (U6), `Dsp.levelTo`, fade tail.
- [ ] **Step 5: Tests (OUD only for now).**
  - In tune at every degree of RAST at TUNE's default INFLECT (5-cent
    tolerance against RAST's own cents, not 12-EDO's).
  - COURSE: envelope beating at COURSE = 1, none at COURSE = 0 (Task 4's
    test, now through the actual voice).
  - SLIDE: pitch track starts below target, reaches it within 5 cents.
  - Determinism: same patch, same bytes, seed via `Dsp.seedFor("SILK", SilkVoice.OUD, ...)`.
  - Fuzz: every macro at 0/0.5/1 — finite, bounded, within the ring ceiling.
  - Classifier: OUD's defaults read TONAL.
- [ ] **Step 6: Run, commit.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.SilkTest"`
Expected: PASS.

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Silk.kt \
        synth/src/main/kotlin/com/snipsnap/synth/SilkPatch.kt \
        synth/src/test/kotlin/com/snipsnap/synth/SilkTest.kt
git commit -m "Silk: the OUD voice - course, slide, and a two-mode body

Two loops per note over Strings.Course, SLIDE gliding both through
Loop.retune, body from Erkut 2002's two measured modes. In tune on
RAST, courses beat, slides land, deterministic, fuzzed, classified
TONAL.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 7: The GUZHENG voice

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Silk.kt`, `SilkTest.kt`

- [ ] **Step 1: GUZHENG's macros and defaults.** STIFF, PRESS as the
  character pair; SCALE default PENTATONIC.
- [ ] **Step 2: STIFF → `Dispersion`.** Maps 0..1 to `B` from 0 to 1.5e-4
  (default ≈ 3.5e-5, per the spec), `M = 4`, through `Dispersion.forB`,
  bypassed where the design flips sign (Task 3's `null` case) — audibly, a
  harmonic string at that point, which the spec calls out explicitly for
  the top octave from about A5.
- [ ] **Step 3: PRESS → a snapped semitone bend.** Four positions: 0, +100,
  +200, +300 cents, via `Loop.retune` *up* from the plucked degree (shape
  of the rise time — no source measures it, comment says so). The pressed
  note deliberately lands off the SCALE table, on a 12-EDO semitone above
  the degree — not through `SilkScales.frequencyFor`'s snapped path.
- [ ] **Step 4: Body and excitation.** Five-mode table (83.69, 138.13,
  172.50, 197.19, 275.00 Hz — Deng 2016, §3, Qs shape); fingerpick exciter,
  PICK high / STRIKE low by default.
- [ ] **Step 5: Tests.**
  - In tune at every PENTATONIC degree.
  - STIFF sharpens partials (Task 3's test, through the voice this time,
    at GUZHENG's actual `B` range).
  - PRESS: at each of the four stops, pitch track starts on the plucked
    degree and ends 0/100/200/300 cents above it (±5 cents).
  - Determinism, fuzz (including PRESS's four stops explicitly, not just
    0/0.5/1), classifier TONAL.
- [ ] **Step 6: Run, commit.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.SilkTest"`
Expected: PASS.

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Silk.kt \
        synth/src/test/kotlin/com/snipsnap/synth/SilkTest.kt
git commit -m "Silk: the GUZHENG voice - stiffness and the pressed bend

STIFF drives Dispersion's B (0..1.5e-4), bypassed where the design
would flip sign. PRESS snaps to 0/+100/+200/+300 cents from the
plucked degree - off the pentatonic table on purpose, reaching fa and
ti the way a real hand does. In tune, sharpens, presses land, fuzzed.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 8: Registration — Patches, Presets, Velocity, SynthScreen, the KIT readout

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt`, `Presets.kt`, `Velocity.kt`
- Modify: `app/.../SynthScreen.kt`
- Modify (add `SILK` cases): `DeterminismTest`, `PresetsTest`, `PadRecipeTest`, `VelocityGrooveShuffleTest`, `shell`'s `UserPresetsTest`
- Modify: the KIT tune-readout code (F5.3, per the spec's "Where microtones meet the MPC")

- [ ] **Step 1: Follow GLINT's table.** Per the spec's "Data flow and
  compatibility": grep for every exhaustive `when` over `Patch` and every
  hardcoded engine roster (do not trust the spec's list as complete — it
  says so itself), add `Engine.SILK` and its two voices everywhere one is
  found.
- [ ] **Step 2: `Velocity.macroSpecsFor` + PICK.** SILK's PICK registers as
  the velocity macro, the same wiring every other engine has.
- [ ] **Step 3: `PluckPresets`-shaped stub for SILK.** Phase 4 owns real
  presets ("authored blind are disposable" — the spec's rule), but the
  registration tests above need at least one preset per voice to exercise;
  a single placeholder preset per voice, clearly marked temporary in a
  comment, unblocks this task without pre-empting Phase 4's by-ear pass.
- [ ] **Step 4: `SynthScreen.kt`.** `Engine.SILK` in the picker, both voices
  in each parallel dispatcher, `DrumClass.TONAL` for both.
- [ ] **Step 5: The KIT tune readout.** A SILK pad's readout shows a note
  name and a cents offset against 12-EDO (e.g. `E♭ −50¢`) rather than
  rounding to the nearest semitone; the IN KEY action leaves a non-CHROMATIC
  SILK pad alone, per the spec.
- [ ] **Step 6: Run the registration suites.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.DeterminismTest" --tests "com.snipsnap.synth.PresetsTest" --tests "com.snipsnap.synth.PadRecipeTest" --tests "com.snipsnap.synth.VelocityGrooveShuffleTest"`
and (if `:shell` runs separately) `./gradlew --no-daemon :shell:test --tests "*.UserPresetsTest"`.
Expected: PASS, with SILK's cases included in each.

- [ ] **Step 7: Commit.**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Patches.kt \
        synth/src/main/kotlin/com/snipsnap/synth/Presets.kt \
        synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt \
        app/src/main/java/com/snipsnap/app/SynthScreen.kt \
        synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PresetsTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt
git commit -m "Silk: registration - Patches, Presets, Velocity, SynthScreen, KIT

Engine.SILK reaches every exhaustive Patch dispatch GLINT's table
warned about, PICK through Velocity like every other engine, and the
KIT tune readout reads a SILK pad's cents against 12-EDO instead of
rounding it away.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
```

---

### Task 9: Whole-suite verification, the roadmap row, the PR

- [ ] **Step 1: Run everything.**

Run: `./gradlew --no-daemon test`
Expected: PASS across all modules — confirm the genuine `BUILD SUCCESSFUL`
line and fresh test-report timestamps before proceeding, per this session's
standing rule.

Also: `ctest --test-dir build/native-tests`. Expected: unchanged (this
phase touches no C++).

- [ ] **Step 2: PLUCK's two proofs, one more time, isolated.**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.StringsTest"`
Expected: `PluckTest` 36/36 unchanged from Phase 1a; `StringsTest`'s
frozen-copy grids (Phase 1a's 384+108, plus Task 3/4/5's off-by-default
grids) all pass.

- [ ] **Step 3: The listening gate.** Per the spec's "Phasing and gates":
  render OUD and GUZHENG at defaults and at each character macro's extremes
  (COURSE, SLIDE, STIFF, PRESS) and listen — CLOSER / SAME / WORSE against
  the real instrument. This gate is a human judgment call, not a test; flag
  it in the PR description as done or as open, honestly.

- [ ] **Step 4: Add the roadmap row.** In `docs/SYNTH_ROADMAP.md`'s phasing
  table, after the S15 row:

```markdown
| S16 | **Phase 1b shipped** — SILK's SCALE and INFLECT (fourteen scale rows, TUNE walking degrees, a bounded centred cents nudge) and its first two voices, OUD (course, slide, Erkut's two-mode body) and GUZHENG (stiffness dispersion via the corrected Rauhala design, the pressed semitone bend) — registered through Patches/Presets/Velocity/SynthScreen/KIT. PLUCK's two Phase 1a proofs (the pinned renders, the frozen-copy grid) still hold. SANTUR and SHAMISEN are Phases 2–3 | S15 (SILK Phase 1a) |
```

- [ ] **Step 5: Commit, push, open the PR.**

```bash
git add docs/SYNTH_ROADMAP.md
git commit -m "Roadmap: S16, SILK Phase 1b

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QgFDGoGvyxHKwrW6rRNMim"
git push -u origin claude/sound-design-tools-mdmrbw
```

The PR body says: PLUCK is unaffected (cite the two proofs); SCALE/INFLECT's
design and where the table's rows are sourced from; OUD and GUZHENG's
listening-gate result; that SANTUR and SHAMISEN are next.

---

## What Phase 2 (SANTUR) inherits

- `Strings.Course` generalises straight to `N = 4`.
- `Strings.Dispersion` is reused as SANTUR's *fixed* stiffness
  (`B = 3.1e-4`, not a knob) — no new dispersion code, just a different
  caller.
- A new exciter (`Exciter.mallet`, a raised-cosine pulse) and a new output
  stage (the sympathetic resonator bank, driven post-loop by the string's
  first difference, following SCALE's degrees) are Phase 2's actual new
  DSP — nothing here anticipates them beyond leaving `Silk.kt`'s shape open
  to a third voice.

## What Phase 3 (SHAMISEN) inherits

- `Strings.Dispersion` again, always-on and small, per the spec's van
  Walstijn citation.
- `Loop.retune` for the built-in pitch glide (open strings starting sharp).
- The one genuinely new, nonlinear piece — `Collision` (sawari/jawari) — is
  not built in this phase; SITAR (PLUCK's own Phase 3) and SHAMISEN's sawari
  share it, and it lands with whichever of the two is implemented first.
