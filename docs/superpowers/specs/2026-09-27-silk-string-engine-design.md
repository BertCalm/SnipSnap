# SILK — four Silk Road strings, and a TUNE that leaves the piano

**Status:** design; its five open decisions settled in conversation 2026-09-27. Not implemented.
**Date:** 2026-09-27
**Research:** [`../plans/2026-09-27-silk-research.md`](../plans/2026-09-27-silk-research.md) —
second pass, with scholarly hosts reachable. Its preamble says what is
confirmed, corrected and still missing; the spec below cites it by section
(§1 shamisen … §5 tuning).
**Plan:** to be written per phase (`docs/superpowers/plans/2026-09-27-silk-phase-N.md`)
**Related:** [`2026-09-25-pluck-depth-design.md`](2026-09-25-pluck-depth-design.md) —
PLUCK's Phase 3 outline (SITAR: jawari, sympathetic strings, dispersion)
names three of the four primitives this spec builds. See "SILK and PLUCK's
Phase 3" below.
**Roadmap:** the `SYNTH_ROADMAP.md` row is added when implementation
starts, not now — the rule FATHOM, RESIN and GLINT followed.

## Why SILK

The ask (2026-09-27) arrived as four C++ prototypes — a shamisen dual
exciter, an oud double course, a guzheng allpass dispersion chain and a
santur sympathetic matrix — and three answers:

| Question | Answer |
|---|---|
| Where does it live? | a new engine, SILK, in `:synth` (Kotlin, offline one-shots), not native C++ |
| Microtones? | yes — "add to TUNE" |
| Scope? | all four instruments; phasing left to this spec |
| Next step? | research, then spec (this document) |

PLUCK cannot hold these four as more voices. Its macro set — TUNE, DAMP,
PICK, STRIKE, BODY, DOUBLE — has nowhere for a bend, a buzz, stiffness,
or a sympathetic wash, and all four instruments want at least one of
those as their *identity*, not as an effect. A new engine gets its own
macro list; the string loop underneath is PLUCK's, shared rather than
copied (see "Architecture").

### The prototypes, as reviewed

The C++ was read as pseudocode for intent. What it got right and what it
got wrong both shaped this design:

| Prototype | Kept | Changed, and why |
|---|---|---|
| Shamisen `ShamisenExciter` | strike splits into string + skin | `trigger()` returns one sample: a KS string wants a *burst* the length of the loop, and the slap needs its own short envelope. The skin is `Modes` (a resonator bank with per-mode decay), not one 400 Hz biquad. The prototype also misses the shamisen's identity, **sawari** — the buzz. |
| Oud `OudCourse` | two loops per course, smoothed pitch for slides | the detune was a constant `1.0015` though the comment said randomised; SnipSnap draws it from `Dsp.seedFor`. `sampleRate / freq` ignored the loop filter's delay — the exact bug PLUCK's task-11 measured as "tens of cents flat". |
| Guzheng `GuzhengDispersion` | first-order allpasses in the loop | **the difference equation is not an allpass.** `out = g·x + z1 − g·z1; z1 = x + g·out` has `|H| = 1.33` at DC per stage at `g = 0.5` (3.16× across four stages) — inside a loop at 0.998 feedback it diverges. The correct transposed form is `out = a·x + z1; z1 = x − a·out`, and for partials to go *sharp* (what stiffness does) `a` must be **negative** in that form; the prototype's `0 … 0.9` range bends them flat. |
| Santur `SanturSympatheticMatrix` | a parallel bank driven by the string | `Q = 50` rings for `t60 ≈ 2.2·Q/f` — 0.55 s at 200 Hz, 0.11 s at 1 kHz, so the top of the bank dies first. SILK specifies each resonator by **t60**, which `Modes.Mode` already does. The soft clipper goes: an offline render sees the whole buffer, so level is `Dsp.levelTo` after the fact, never a nonlinearity on the wash. |

### The name

SILK: the Silk Road that carried all four instruments' ancestors, and the
silk strings the guzheng and shamisen were strung with before nylon. A
generic word, allowed by the naming rule.

## Architecture

```
exciter ──▶ course (1–4 loops) ──▶ body (Modes) ──▶ [sympathetic bank] ──▶ decimate ──▶ levelTo ──▶ fadeTail
  pluck burst      each loop:                                 SANTUR only
  mallet pulse     delay ─▶ tuning allpass ─▶ loop LP ─▶ [dispersion] ─▶ [collision] ─┐
  + slap burst       ▲                                                               │
                     └──────────────────────── × feedback ◀──────────────────────────┘
```

Rendered at `Dsp.RATE * Dsp.OVERSAMPLE` and decimated (U6), like every
other engine. Mono, like PLUCK.

### A shared string toolkit, extracted from PLUCK

`Pluck.ks` is already most of a waveguide: an integer delay, a first-order
tuning allpass for the fractional remainder (Jaffe & Smith), a one-pole loop
low-pass, a two-tap average, and — the hard-won part — a **tuning budget**
that subtracts every in-loop stage's phase delay at the fundamental before
splitting the loop into integer + fraction. SILK needs that loop with four
optional stages added, so the first job is to lift it into a shared file
rather than fork it.

**New file `synth/.../Strings.kt`** — `internal object Strings` holding:

| Piece | What it is | New or moved |
|---|---|---|
| `Loop` | PLUCK's `ks` loop as a class: delay, tuning allpass, loop LP, two-tap average, feedback; optional `Dispersion` and `Collision` slots; a per-sample **pitch envelope** (for slides and bends) that re-solves the tuning budget as the delay moves | moved, then extended |
| `Dispersion` | a cascade of `M` first-order allpasses, one coefficient `a < 0`; its phase delay at f0 is computed in closed form and charged to the tuning budget | new |
| `Collision` | a one-sided obstacle in the loop (sawari / jawari): displacement past `−h` is folded back with a loss, so the buzz follows the string's own amplitude — fierce at the attack, thinning as the note decays | new |
| `Course` | `N` loops (1 to 4) at seeded detunes in cents around f0, each with a slightly different feedback so the pair decays unevenly, summed | new; DOUBLE is its N=2 special case |
| `Exciter.pluck` | PLUCK's one-period filtered noise burst with STRIKE's position comb | moved |
| `Exciter.mallet` | a raised-cosine force pulse; width is hardness | new |

**The guard on the move:** PLUCK renders **bit-identical** before and after
the extraction. A test renders every PLUCK voice at its defaults and at a
macro corner set, before the move, stores the hashes, and asserts them
after. The extraction ships as its own PR with no audio change; SILK is
built on it after.

### SILK and PLUCK's Phase 3

PLUCK's spec outlines a SITAR voice needing a jawari, sympathetic strings
and dispersion allpasses for KOTO and HARP. SILK builds all three first,
in `Strings`. PLUCK's Phase 3 then becomes wiring: SITAR is a `Collision`
plus a sympathetic bank on PLUCK's own macros. Nothing in this spec changes
PLUCK's sound; PLUCK adopting dispersion or collision is PLUCK's decision,
in its own spec.

### The tuning budget, extended

The rule PLUCK learned stays the rule: **every stage in the loop pays for
its own delay at the fundamental.** With dispersion added the budget is:

```
exact = rate / f0 − loopLpDelay(f0) − 0.5 − dispersionDelay(f0)
n     = floor(exact);  frac = exact − n   → tuning allpass
```

`dispersionDelay(f0)` is `M × (phase delay of one allpass at ω0)`, closed
form. With `a < 0` each stage delays low frequencies by *more* than one
sample (`(1 − a)/(1 + a)` at DC), so a strong STIFF on a high note eats
the loop — `require(exact ≥ MIN_LOOP_SAMPLES)` stays, and STIFF's top is
set so the voice's highest note at STIFF 1 clears it (tested).

Research §3 confirms both the sign and the budget from the papers
themselves (Rauhala & Välimäki 2006, the IEEE letter and the DAFx-06
paper; Rauhala's 2007 dissertation; J. O. Smith's *Physical Audio Signal
Processing*): the design gives `a = (1 − D)/(1 + D)`, and its figures put
`a` between −1 and 0. Van Duyne's CLM piano table (−0.92 … −0.04) and
Smith's Faust port, whose `-Df0*M` term is this budget's
`dispersionDelay`, agree. It adds findings that change the design:

- **A fixed `a` barely moves a low note.** At f0 = 147 Hz, `a = −0.5` over
  four sections stretches the 12th partial by about 2 cents. So STIFF is
  mapped onto an **inharmonicity coefficient `B`** (`fk = k·f0·√(1+B·k²)`)
  and `a` is derived per note, rather than STIFF setting `a` directly.
- **The closed-form B→a design flips sign at the top.** For high, weakly
  stiff notes it yields `D < 1`, hence `a > 0` — flat partials, the wrong
  way (D6 at `B = 1e-4`: −11 cents at the 10th partial). Rule: **if
  `D ≤ 1`, bypass the cascade** — which is what DAFx-06 itself does (it
  sets `D` to 1, removing the sections). At guzheng-sized `B` this leaves
  the top octave, from about A5, harmonic. Where the sign is right the fit
  still overshoots the target by 13–20 % at `B = 1e-4`, so the STIFF test
  measures the stretch rather than trusting the map.
- **The printed design equation has an erratum.** DAFx-06's Eq. 7 reads
  `ln M` where `ln B` belongs; Rauhala's dissertation (Eq. 3.8) corrects
  it. `Strings.Dispersion` uses the corrected form, with a unit test
  pinning one published coefficient so the erratum cannot creep back.

**How much `B`.** No guzheng string has a *measured* `B` (§3: Sinin 2026,
the one paper that seemed to, reports partials snapped to equal-tempered
note names). The sourced neighbours:

| Instrument | `B` | Status |
|---|---|---|
| Guzheng string 21 (D2) | 3.5–3.8 × 10⁻⁵ | `computed` from Zhang et al. 2019's parameter table; the authors' own filter inverts to 3.46 × 10⁻⁵ (a 4× moment-of-inertia slip in that table would give 1.5 × 10⁻⁴) |
| Guqin (sister zither), one note at 392 Hz | 9 × 10⁻⁵ | confirmed (Penttinen et al., preprint) |
| Santur, average | 3.1 × 10⁻⁴ | confirmed (Heydarian) |

So **STIFF maps `B` from 0 to 1.5 × 10⁻⁴** for GUZHENG, default about
3.5 × 10⁻⁵, and the audition sweeps `{0, 1e-5, 3.5e-5, 9e-5, 1.5e-4}` on
D2, D3 and A3 against a recording. SANTUR carries its sourced
`B = 3.1 × 10⁻⁴` fixed, not on a knob (see the voice).

Collision is not in the budget: it is inactive at rest and, when it bites,
it *shortens* the effective loop slightly — which is audible on a real
sitar and shamisen as the attack reading a hair sharp. That is kept.

## TUNE and SCALE — leaving the piano

### What changes

Every melodic engine snaps TUNE to 24 semitones above a root. SILK adds a
second snapped macro, **SCALE**, and TUNE steps through **SCALE's degrees**
instead of semitones:

- `SCALE` snaps to an entry of a fixed table (the RATIO precedent in TINES:
  `snap(macro) = TABLE[(macro × (size − 1)).toInt()]`).
- Each entry is a list of **cents above the root for one octave** (the
  degrees), and the octave's size (1200 for everything except
  Bohlen–Pierce).
- `TUNE` spans two octaves of that scale: `degree = round(TUNE × 2·size)`,
  `hz = root × 2^((octave·1200 + cents[degree mod size]) / 1200)`.
- **"Snapped notes, never a mistuning"** holds: every TUNE position lands on
  a scale degree exactly. A quarter-tone is a degree, not a detune.

Both are ordinary 0..1 macros, so the recipe format, `PadRecipe.VERSION`,
SCRAMBLE and presets are unaffected: a pad's `{"SCALE": 0.43, "TUNE": 0.6}`
regenerates to the bit like any other.

### The table

Rows come from research §5. "Dataset" means confirmed against an open
research dataset read directly (DaMuSc, McBride, Passmore & Tlusty 2023),
whose row cites a book this pass could not open — the row's provenance goes
in the table file as a comment, `.scl`-style, so it travels with the code.
A row that is neither dataset-confirmed nor equal-division does not ship.

| SCALE entry | Cents above the root (one period) | Period | Source |
|---|---|---|---|
| CHROMATIC | 0 100 … 1100 — PLUCK's behaviour | 1200 | equal division |
| PENTATONIC (gong) | 0 204 408 702 906 | 1200 | dataset (Ho & Han 1982 via DaMuSc `T0337`; DaMuSc assumes *shi-er-lü* tuning, its own note says so; its Pythagorean 1201 closes to 1200 here) |
| RAST | 0 204 362 498 702 906 1064 | 1200 | dataset (Rechberger 2018, 53-comma row, `OT0441`) |
| BAYATI | 0 136 294 498 702 838 996 | 1200 | dataset (`OT0468`) |
| HIJAZ | 0 113 408 498 702 860 996 | 1200 | dataset, ascending 53-comma row (`5;13;4;9;7;6;9`), consistent with the other Arabic rows; only DaMuSc's *descending* row fails to close (52 commas) |
| SIKAH | 0 136 340 543 702 838 1042 | 1200 | dataset, ascending row — **corrected** from a first draft that copied the descending row's 634; Maqam World notates a perfect 5th and its page audio matches this row within 4 cents |
| SHUR | 0 133 294 498 702 792 996 | 1200 | dataset (Rechberger's 17-note gamut, `OT0308`) |
| MAHUR | 0 204 408 498 702 906 1110 | 1200 | dataset (`OT0311`) |
| CHAHARGAH | 0 133 408 498 702 835 1110 | 1200 | dataset (`OT0318`) |
| MIYAKO-BUSHI | 0 90 498 702 792 | 1200 | dataset (Hewitt 2013, `OT0103`, where it is labelled *kumoi joshi*); the set 1-♭2-4-5-♭6 is the *in* / miyako-bushi scale in en and ja Wikipedia and the koto's hira-jōshi on D. Renamed: "kumoi" names a different shape in Malm |
| HIRAJOSHI | 0 204 294 702 792 | 1200 | dataset (`OT0102`) — the same pitch set as MIYAKO-BUSHI, as a mode on its 4th |
| 24-EDO | every 50 cents | 1200 | equal division |
| 19-EDO | every 63.158 cents | 1200 | equal division |
| 31-EDO | every 38.710 cents | 1200 | equal division |
| BOHLEN–PIERCE | 13 equal steps of 146.304 cents | **1902** (3/1, a tritave) | equal division of 3/1 |

What §5 settled and what it left open:

- **The neutral third has no single right size.** Rast's third is 350 cents
  in 24-EDO notation, 362 in the 53-comma row and 384 in Ellis (1885); the
  1932 Cairo recordings put thirteen Egyptian tracks at 343–363 and Iraqi
  and Maghrebi ones anywhere from 309 to 376. SILK ships the 53-comma row
  because it is one consistent theory source for every Arabic row; a second
  "measured" variant per maqam is a follow-on, not a guess. Of the shipped
  rows, RAST's 362 sits at the top of the Egyptian band (median 352) and
  BAYATI's 136 at the bottom of its (133–184, median 150) — both inside
  INFLECT's reach.
- **The Persian rows agree with measurement.** SHUR, MAHUR and CHAHARGAH
  sit within 3.7 cents of Farhat's fret measurements on two tārs and a
  setār (as reported by Shafiei 2021; Farhat's book was not opened).
  SHUR's 133-cent second sits by Farhat's 135 and a measured singer's 137.
- **"Same note, two pitches" is two rows, not INFLECT.** Rast's 7th up and
  down, Bayati's 6th, the Persian variable degree in Shur are about 70
  cents apart — separate rows for the follow-on, never an inflection.
- **Miyako-bushi is five notes.** DaMuSc's seven-step "In" and
  "Miyako-bushi" rows match no source read and stay out; the shipped
  MIYAKO-BUSHI row is the five-note set.
- **Shamisen tunings are open-string sets, not scales:** honchōshi (root,
  4th, octave), niagari (root, 5th, octave), sansagari (root, 4th, minor
  7th in the same octave), confirmed as note names only. They matter to a
  multi-string gesture, which a one-shot is not; recorded for the preset
  pass.
- **The guzheng's pressed 4th and 7th are off the pentatonic table.**
  *Fa* and *ti* are made by pressing *mi* and *la* (confirmed as note names;
  no source measures a pressed note in cents). PRESS therefore bends by
  whole semitones from the plucked degree, not to the next degree — see the
  voice.

Each voice defaults to its own tradition's scale (OUD → RAST, SANTUR → SHUR,
GUZHENG → PENTATONIC, SHAMISEN → MIYAKO-BUSHI) — but **every scale is reachable
from every voice**. A santur in 19-EDO is a feature.

TUNE's two-period span means two tritaves for BOHLEN–PIERCE, about three
octaves: the voice's root is lowered for that row so the top stays under
the loop-length floor (`require` covers it either way).

### INFLECT — between the degrees, without leaving the table

Asked in conversation: does this enable more atonal abilities? Recorded
answer: *atonal* (no key centre) was always possible with twelve semitones;
what SCALE adds is *microtonal* and *xenharmonic* pitch — notes between the
piano keys, and whole tunings outside the Western set.

An unsnapped FREE row was drafted and **rejected in favour of INFLECT**
(decision 2026-09-27): TUNE always lands on a table degree, and **INFLECT**
bends that degree by a bounded amount on top. It is what real players do
and a table cannot hold — the thirteen Egyptian rast thirds of 1932 span
20 cents; Maqam World says Hijaz's 2nd is played a little high and its 3rd
a little low; a measured Persian singer's intervals scatter 5–14 cents —
without giving up "snapped notes, never a mistuning" as the default.

- **Bipolar, centred:** `MacroSpec("INFLECT", 0.5f, neutral = 0.5f)`.
  0.5 is the degree exactly; 0 is −50 cents, 1 is +50 cents, linear in
  cents between. ±50 is a quarter tone. Measured from the shipped rows it
  covers every 1932 Egyptian recording (rast 3rd −19…+1, bayati 2nd
  −3…+48, hijaz 2nd and 3rd), the Persian spreads, and the 120–180-cent
  span of the "neutral second". Two recordings fall just outside (an Iraqi
  and an Algerian rast 3rd, −51 and −53), and both scales also carry a
  1005-cent 7th, so they read as a different mode rather than a wide
  third. The bound stays under the ~70 cents that separates the "same
  note, two pitches" pairs above, which are rows, not inflections.
- **A detent at the centre:** within ±0.02 of 0.5 the offset is exactly
  0, so a knob nudged back near the middle lands on the degree rather
  than a cent off it.
- **SCRAMBLE leaves it at 0.5.** A dice roll never detunes a pad; INFLECT
  is a deliberate move, like KEY on the kit screen.
- **It moves the played note only.** SLIDE and PRESS bend toward the
  inflected pitch. WASH's sympathetic bank stays on the *un-inflected*
  table — the santur's strings are tuned to the dastgah; the player's
  hand is what bends one note against them, and that beating is the
  point.
- **The pad readout includes it:** `E♭ −50¢` already carries the cents,
  so an inflected pad reads as the pitch it is.

### Where microtones meet the MPC

- **Pads: already fine.** Each SILK pad is rendered at its exact pitch, and
  a pad's `tuneCoarse` / `tuneFine` (−36..36 semitones, −100..100 cents,
  `Kit.kt:97-98`) exist for the kit-level key picker. A quarter-tone pad
  needs nothing new in the export.
- **`fineTune` is cents — probably.** Research §5 found three MPC 3 track
  files from three exporter builds in which a layer's
  `pitch == coarseTune + fineTune/100` (e.g. −2 and 34 → −1.66). That
  answers `MPC3_FORMAT.md`'s open question on paper; the hardware still
  has the last word. The corpus holds no MPE, MTS or tuning-table field,
  so anything microtonal that reaches the MPC is baked into per-zone
  coarse + fine, whole cents at best.
- **The pad tune readout** (F5.3) shows a name and a cents offset against
  12-EDO for a SILK pad, e.g. `E♭ −50¢`, instead of rounding to the nearest
  semitone. The IN KEY action leaves SILK pads with a non-CHROMATIC SCALE
  alone — they are in their own key by construction.
- **Keygroups: out of scope.** `Keys.kt` samples every minor third and lets
  the MPC transpose in semitones; a maqam keyboard needs one zone per key,
  each rendered at its own degree. Named as the follow-on below.

## The voices

Each voice is a recipe over `Strings`. A value tagged with a research
section (§1–§5) is sourced there; every other instrument value (a gain, a
Q the source does not give, a detune) is **"shape, not measurement"** and
is set by ear at the gate — the rule PLUCK's Phase 2 kept. DSP constants
(a feedback range, an allpass count) are engineering.

### OUD — the course and the slide

- **Course:** two loops per note (`Course(N = 2)`), detune drawn per note
  from a seed that depends on the voice and the note, so one pad's shimmer
  is stable across renders and two pads differ. **COURSE** sets the spread
  (0 = unison, 1 = wide, honky), and the pair gets slightly unequal
  feedback so it decays unevenly — the "prompt then aftersound" of coupled
  strings, cheaply.
- **Excitation:** the pick burst near the bridge (STRIKE low), plus a very
  short high-passed tick for the risha. PICK is the burst's brightness.
- **Slide:** **SLIDE** bends into the note from below — depth and time on
  one knob (0 = none, 1 = a slow slide from a whole degree under, measured
  in *scale* degrees so a maqam slide lands on a maqam note). Both loops
  share the pitch envelope so the detune survives the glide. A fretless
  slide is not re-plucked; the finger's extra damping is modelled as a
  slightly lower loop gain for the slide's duration (Erkut, §2).
- **Body:** a two-mode table under BODY from the one oud measured strung
  and radiating: **113 Hz, Q 9.91** and **182 Hz, Q 10.06** (Erkut 2002, a
  Turkish ud; §2). Gains are shape. Neither source says which is the air
  mode. An assembled Arabic oud's structural modes (142, 226, 242, 280 Hz;
  Meccanica 2026, no Q) are recorded for a second body if the audition
  wants one. The eight DAGA 2018 peaks the first pass found are a **bare
  soundboard** on foam — no bowl, no air, no strings — and stay out of the
  bank.
- **Loop:** darker and shorter than NYLON — nylon on the two treble
  courses, metal wound on silk or nylon below (§2). STRIKE's comb scales
  with the confirmed scale lengths (Arabic 610–620 mm, Turkish 585 mm).
- **Course detune:** no source measures it; every source says "unison".
  COURSE's range is shape.

### GUZHENG — stiffness and the press

- **Loop:** one string per note, bright, long ring (DAMP default low).
- **Dispersion:** **STIFF** sets the inharmonicity `B` from 0 to
  1.5 × 10⁻⁴, default about 3.5 × 10⁻⁵ (see "The tuning budget,
  extended"), from which each note's allpass coefficient is derived for
  `M = 4` stages, bypassed where the design would flip sign. 0 is a
  harmonic string; the top is the guqin's measured stiffness and past it
  — the ugly end is kept reachable (audition-gate rule).
- **Press:** **PRESS** is the left hand pushing down behind the bridge
  after the pluck: the note starts on the plucked degree and bends **up by
  a whole number of semitones**. PRESS snaps to four positions — 0 (open),
  +100, +200, +300 cents — because that is how the instrument reaches the
  notes its pentatonic table lacks: *mi* pressed a semitone is *fa*, *la*
  pressed a semitone is *ti* (§5, note names confirmed). The deepest stop
  is the teaching source's "up to three half steps"; ISMIR 2022 bounds the
  press "within major third" (§3). The bend's rise time is shape: no
  source measures it. A pressed note ends off the SCALE table *on
  purpose* — it is the one deliberate exception, and it lands on a 12-EDO
  semitone above a table degree, never between.
- **Pick:** fingerpicks — PICK high, STRIKE low.
- **Body:** Deng 2016's five modes of a complete guzheng **with all strings
  tensioned**: 83.69, 138.13, 172.50, 197.19, 275.00 Hz (§3). No source
  gives a Q for any guzheng mode, so every Q is shape. The BJFU soundboard
  modes (234 Hz – 1.34 kHz) are of clamped, unstrung blanks and stay out.
- **Decay:** the confirmed shape to aim at (Li & Li 2024, §3): content
  above 2 kHz gone within 0.75 s of an A4, the 370–520 Hz band persisting.

### SANTUR — four strings and the wash

- **Range and root:** the 9-bridge santur runs E3–F6, about 165–1397 Hz,
  in 18 courses of four on two rows of nine bridges (§4). Root E3.
- **Stiffness, fixed:** the santur's measured average `B = 3.1 × 10⁻⁴`
  (Heydarian, §4) goes into every SANTUR loop's `Dispersion` — sourced, so
  not a knob. Heydarian also measured the octaves compressed by 11 and 28
  cents and the third stretched by 20; SILK keeps TUNE on the table and
  leaves that stretch to the dispersion it comes from.
- **Course:** `Course(N = 4)`, COURSE is the spread of the four. Every
  santur source says the four are tuned to exact unison; no spread is
  measured. So COURSE **defaults near 0** and its range is shape, informed
  by proxies (§4): two coupled piano strings lock with no beats below about
  0.3 Hz of mistuning (Weinreich); in Woodhouse's simulation 0.1–1 cent
  reshapes the decay, 2 cents is audible as pitch, 5 cents beats clearly.
  COURSE 1 is well past that — the ugly end kept reachable.
- **Excitation:** `Exciter.mallet`. PICK is **mallet hardness** (pulse
  width: wide and dark to narrow and bright), so velocity-through-PICK
  means the same thing it does on every other voice. The contact time is
  shape: no mezrab is measured; the piano proxy runs from about 4 ms in
  the bass to under 1 ms in the treble (Askenfelt & Jansson, §4). STRIKE's
  default puts the hit 30–40 mm from the bridge on the course's length (a
  maker's figure, low authority; the course lengths are confirmed). One
  pulse, no bounce — the sources disagree on whether a mezrab rebounds, and
  one pulse is the simpler default.
- **Wash:** **WASH** is the undamped instrument. A bank of resonators
  (`Modes`, specified by t60 — never Q) tuned to the **current SCALE's
  degrees across three octaves**, driven by the string's first difference
  (PLUCK's body drive, same reasoning). WASH sets the bank's level and its
  t60 together. The bank follows SCALE, so a SHUR santur rings in SHUR.
  No santur t60 is measured ("a few seconds", §4), so WASH's t60 range is
  shape.
- **Body:** no santur body mode is measured; BODY is a neutral shape and
  says so.

### SHAMISEN — sawari and the slap

- **Root and loop:** nagauta open strings measure about 131 / 175 /
  262 Hz in honchōshi (§1), so the root is C3. One string per note, bright,
  short ring: a mid-range note falls 20 dB in about 270 ms, half or less of
  a guitar's (§1). Each harmonic decays in two slopes, the first several
  times steeper — the loop's two-stage decay is DAMP's shape to aim at.
- **Pitch glide:** open strings start about 3.5 % sharp and fall to about
  1.5 % within 100 ms, then drift toward 0.8 % (§1). Built into the voice,
  scaled by PICK (a harder stroke stretches the string further); not a
  knob. The tuning test measures the settled pitch, after the glide.
- **Sawari:** **SAWARI** is the obstacle's depth: 0 = no contact, 1 = the
  string grazing the neck on every swing. Strength needs no second knob —
  it follows the string's amplitude, as the jawari outline in PLUCK's spec
  already reasons. Loss on contact is fixed; the buzz must never add energy
  (tested). **More is not simply more:** a thesis (§1) reports a sweet
  spot — too shallow swells repeatedly, too deep beats and dies faster —
  so the knob's travel includes the too-deep end on purpose, and the
  audition finds the default. What a working sawari does, measured on the
  biwa (Taguti & Tohnai 2001, §1): partials 6 to 20 and up are boosted
  *and* last longer, and the spectral centroid rises (864 → 1204 Hz in
  their example).
- **What the one paper read says** (van Walstijn, Bridges & Mehes, DAFx-16
  tanpura model — method, not shamisen values): the buzz's "precursor"
  **disappears when the string has no stiffness**, so the SHAMISEN loop
  always carries a small `Dispersion`, whatever STIFF would be; the note
  audibly *grows* in brightness over its first few hundred milliseconds,
  which is the test that a sawari works (a static bright EQ cannot do it);
  and 2× oversampling suffices, which U6's 4× already exceeds. The
  obstacle should be near-rigid — a soft clamp reads as mush.
- **Sympathetic sawari (later):** sawari acts on the open first string only,
  but stopped notes on the other strings buzz by resonance with it. A
  second, always-open loop carrying the obstacle, fed a little of the
  played string, reproduces that. Not in Phase 3's first cut; named so the
  audition can ask for it.
- **Slap:** **SLAP** is the bachi hitting the skin with the string. The
  stroke is three timed layers (§1, 1995 paper): about 7 ms of the bachi
  rubbing the string (a touch noise at −20 to −26 dB), then about 20 ms of
  string–skin contact, then the note, whose attack rises in 1.5–5 ms. The
  skin strike is what carries content up to about 16 kHz; played without
  touching the skin, most content above about 3 kHz goes. SLAP scales the
  contact layer: a short noise burst through a small skin `Modes` table,
  in parallel. 0 = string only, and the note's top end drops with it.
- **Body:** a fixed bank from what is measured (§1): the bare body frame
  at **573.2 Hz (damping 0.605 %)** and **630.8 Hz (0.854 %)**, the neck at
  **67.55 Hz (0.923 %)** and **85.38 Hz (2.35 %)** — Q from damping as
  `1/(2ζ)`, our arithmetic. The radiated spectrum peaks near 700 Hz and
  falls about 25 dB an octave below it, the lowest note's fundamental 41
  dB under its 5th harmonic (§1): the body is a strong high-pass, and the
  bank is shaped to it. **The skin's own first mode is unresolved** — the
  same lab reports 151.7 Hz one year and 766 / 1253 Hz another — so the
  skin table is shape until one is settled.
- **Pick:** the bachi meets the string at about 1/6 of its length, giving a
  component near 6 × f0 (§1): STRIKE's default is 1/6. PICK high. The
  bachi's material barely changes the sound (§1), so there is no knob for
  it.
- **Collision constants:** Bilbao & Torin (DAFx-14) and Siddiq 2012 are now
  read (§1); their contact laws are method, run at 88.2 kHz, which U6's
  4× (176.4 kHz) exceeds.

## Macros

Nine per voice: seven shared, two character. Eight were approved on
2026-09-27 before INFLECT was chosen over FREE; INFLECT is the ninth, and
the screen question below (SCALE as a chip) now carries more weight.

| Macro | Meaning | All voices |
|---|---|---|
| TUNE | scale degree over two octaves above the voice's root | ✓ |
| SCALE | which scale TUNE walks (snapped table above) | ✓ |
| INFLECT | ±50 cents on the snapped degree; 0.5 is exact, with a centre detent | ✓ |
| DAMP | the loop's decay and brightness together (PLUCK's meaning) | ✓ |
| PICK | exciter brightness; mallet hardness on SANTUR. The velocity macro | ✓ |
| STRIKE | where on the string — bridge to centre (PLUCK's map) | ✓ |
| BODY | the body table's level against the string | ✓ |

| Voice | Character 1 | Character 2 |
|---|---|---|
| OUD | COURSE — spread of the pair | SLIDE — bend in from below |
| GUZHENG | STIFF — dispersion (`B`) | PRESS — pressed bend up 0 / 1 / 2 / 3 semitones |
| SANTUR | COURSE — spread of the four | WASH — sympathetic ring |
| SHAMISEN | SAWARI — buzz | SLAP — skin hit |

Every character macro at 0 is the plain string, and at 1 is past the real
instrument. Velocity goes through PICK, registered the way PLUCK's is.

## Data flow and compatibility

Registration follows GLINT's table (its spec, "Data flow and
compatibility"), with GLINT's warning kept: grep for every exhaustive
`when` over `Patch` and every hardcoded engine roster rather than trust a
list. Known points:

| File | Change |
|---|---|
| `synth/.../Strings.kt` | new — the shared loop (Phase 1a, no audio change) |
| `synth/.../Pluck.kt` | `ks` and the exciter move to `Strings` |
| `synth/.../Silk.kt`, `SilkPatch.kt`, `SilkPresets.kt`, `SilkScales.kt` | new |
| `Patches.kt`, `Presets.kt`, `Velocity.kt` (`macroSpecsFor` + PICK) | one branch each |
| `app/.../SynthScreen.kt` | `Engine.SILK` and a branch in each parallel dispatcher; all four voices `DrumClass.TONAL` |
| `DeterminismTest`, `PresetsTest`, `PadRecipeTest`, `VelocityGrooveShuffleTest`, `shell`'s `UserPresetsTest` | add SILK |
| the KIT tune readout | cents offset for SILK pads |

**Untouched:** the CLI (`SynthCommand` resolves through `Presets`),
`Keys.kt`, `PadRecipe.kt`, the export writers.

## Failure handling

| Risk | Handling |
|---|---|
| Loop too short at high TUNE + high STIFF | `require(exact ≥ MIN_LOOP_SAMPLES)` with a message naming STIFF; STIFF's bound chosen so no voice's top degree reaches it (tested at every scale's top degree) |
| Dispersion coefficient out of range | `a` clamped to `(−0.95, 0]`; `|a| < 1` is what keeps an allpass stable. Where the design gives `D ≤ 1` the cascade is bypassed, as DAFx-06 does |
| Pitch envelope drives the loop under its floor | SLIDE starts *below* the target, so it only lengthens the loop. PRESS and the SHAMISEN glide shorten it (up to +300 cents; +3.5 %): the loop-floor `require` is checked at each voice's top degree with PRESS at +300 and INFLECT at +50, and the tested ranges keep clear of it |
| Collision adds energy | the fold-back loss is `< 1` by construction; a test renders SAWARI 1 at PICK 1 and asserts the envelope never rises after the attack |
| Sympathetic bank blows up the level | linear bank, levelled by `Dsp.levelTo` after render; no clipper |
| A SCALE table row without a source | not shipped. A pending row is absent from the table, not present with guessed cents |

## Testing

### The ones that carry the claims

1. **In tune at every degree of every scale.** For each voice × each shipped
   SCALE × each TUNE degree, the rendered fundamental lands within 5 cents
   of *the scale's own* target (not 12-EDO's). PLUCK's 5-cent rule,
   generalised. SHAMISEN is measured after its built-in glide settles
   (from 150 ms); every voice with dispersion is measured on the
   fundamental, which the budget holds exact while the partials stretch.
2. **STIFF makes partials sharp.** At STIFF 1, partial `n`'s measured
   frequency divided by `n·f0` rises with `n` (monotonic over the first
   eight partials); at STIFF 0 it stays within 5 cents of harmonic. This is
   the test that catches a sign error in `a`.
3. **PLUCK unchanged.** The bit-identical hashes across the `Strings`
   extraction.
4. **INFLECT is bounded and centred.** At 0 and 1 the fundamental sits
   −50 and +50 cents (±5) from the degree; anywhere in 0.48–0.52 it is on
   the degree exactly (test 1's tolerance); SCRAMBLE never moves it.

### The rest

- COURSE: the envelope of a COURSE-1 render shows beating (amplitude
  modulation) that a COURSE-0 render does not.
- SLIDE: the pitch track starts below the target and ends on it within
  5 cents.
- PRESS: at each of its four stops the pitch track starts on the plucked
  degree and ends 0 / 100 / 200 / 300 cents above it, within 5 cents.
- STIFF's dispersion design reproduces one coefficient published with the
  corrected Eq. 7, so the DAFx-06 erratum cannot return.
- SANTUR: its partial stretch at default matches `B = 3.1 × 10⁻⁴` within
  the fit tolerance the STIFF test uses.
- SHAMISEN glide: the first 100 ms reads sharp and falls; the settled pitch
  is on the degree.
- WASH: the tail after the string's own t60 is louder with WASH 1 than 0,
  and its spectral peaks sit on the SCALE's degrees.
- SAWARI: with SAWARI above 0 the energy in partials 6–20 is higher and
  longer-lived than at 0, and the spectral centroid is higher — the biwa
  measurement's signature. Not tested monotonic across the knob: the
  sourced sweet spot means too-deep can die faster. The envelope never
  grows (the energy test above).
- SLAP: the first 20 ms gains broadband energy with SLAP; the tail does not.
- Determinism: same patch, same bytes. Seeds from `Dsp.seedFor("SILK", voice, …)`.
- Fuzz: every macro at 0 / 0.5 / 1 across all voices — finite, bounded,
  within the ring ceiling.
- Classifier: all four defaults read TONAL.

## Phasing and gates

Grouped by how much new DSP each voice needs — least risk first. Every
phase ends the house way: **stop and listen.** A listening page renders the
phase's voices at defaults and at each character macro's extremes, and the
chips (CLOSER / SAME / WORSE against the real instrument) are the gate.

| Phase | Ships | Audio change | Gate |
|---|---|---|---|
| 0 | Throwaway spike renders of all four for a first listen (the research re-run is done) | none (spike only) | spike heard |
| 1a | `Strings` extraction from PLUCK | **none** — bit-identical | hashes match |
| 1b | SCALE + TUNE (`SilkScales`, equal-division rows + any confirmed rows); OUD and GUZHENG; `Dispersion`; pitch envelope; COURSE; registration | yes | OUD's COURSE and SLIDE, GUZHENG's STIFF and PRESS, at extremes |
| 2 | SANTUR: `Exciter.mallet`, `Course(N = 4)`, the sympathetic bank | yes | WASH and COURSE extremes; mallet hardness |
| 3 | SHAMISEN: `Collision`, the slap, the skin table | yes | SAWARI and SLAP extremes |
| 4 | Presets, by ear, after each voice's gate | — | the standing rule: presets authored blind are disposable |

Why this order: OUD and GUZHENG are PLUCK's loop plus two linear stages.
SANTUR adds a new exciter and the bank, both linear. SHAMISEN's sawari is
the only nonlinearity in the loop — the one piece with a stability risk —
so it lands last, on a toolkit that has already been listened to. PLUCK's
SITAR can follow Phase 3 directly.

## Out of scope

- **Real-time / native.** SILK is offline one-shots like every `:synth`
  engine. A live string would be `app/src/main/cpp` work with its own spec.
- **Microtonal keygroups** (`Keys.kt`), MTS and MPE. Pads first.
- **Gestures across strings:** the guzheng's *hua* glissando, santur
  tremolo rolls, oud tremolo. Those are phrases, not one-shots — a
  `Groove` or ROLL question, not an engine one.
- **Yao vibrato** on the guzheng. PRESS is the bend; vibrato is a third
  knob the audition can ask for.
- **Stereo courses.** Mono, like PLUCK.

## What comes after

**A maqam keyboard.** Once SCALE exists, `Keys.kt` can render one zone per
key at each scale degree, and the kit-level key picker can offer SCALE
beside KEY. That is the point where SILK stops being pads and becomes an
instrument the MPC plays in maqam.

## Decisions taken in conversation — 2026-09-27

| Question | Decision |
|---|---|
| Home | new engine SILK in `:synth`, Kotlin, offline |
| Microtones | yes, through TUNE — SCALE macro, TUNE walks degrees |
| SCALE reach | every scale on every voice, each voice defaulting to its tradition |
| Knob count | eight approved; INFLECT then chosen, making nine |
| Between the degrees | INFLECT (±50 cents on a snapped degree), not an unsnapped FREE row |
| Scale sources | dataset-confirmed rows (DaMuSc) accepted for scales; not for instrument measurements |
| Research re-run | yes — done the same day once network access was widened; second pass in the research note |
| PRESS | snapped semitone bends (0 / +100 / +200 / +300) from the plucked degree, so the guzheng reaches *fa* and *ti* — set by the second pass, which showed pressed notes are off the pentatonic table |
| Scope | all four voices, phased by DSP risk (OUD+GUZHENG, SANTUR, SHAMISEN) |
| Order of work | research, then this spec, then per-phase plans |

### Open for review

1. **SCALE as a knob or a chip.** Nine knobs is one more than was
   approved. If the SYNTH screen is crowded, SCALE moves to a chip row
   above the knobs (it is a list choice, not a sweep); decided when the
   screen is built, not here.
2. **What research still cannot give** (the research note's preamble has
   the list): course detuning for oud and santur, a santur t60 or body
   mode, any guzheng body Q, a mezrab contact time, and the shamisen skin's
   first mode (two incompatible reports from one lab). All are shape, set
   at the gates. The next leads, if wanted: Değirmenli's oud theses
   (academia.edu, login), Waltham ISMA 2014 and Deng's *J. Vibration and
   Shock* papers for per-string guzheng parameters.
