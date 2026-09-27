# SILK — four Silk Road strings, and a TUNE that leaves the piano

**Status:** design, from conversation 2026-09-27. Not implemented.
**Date:** 2026-09-27
**Research:** [`../plans/2026-09-27-silk-research.md`](../plans/2026-09-27-silk-research.md) —
read its preamble first: this session's network blocked every scholarly
host, so **no body, tuning or string number in it is confirmed yet**.
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

Cents values are **not in this spec on purpose.** Each row below is filled
from the research note once its source is read directly; until then the
row ships only if marked "equal-division, no source needed".

| SCALE entry | Degrees | Source status |
|---|---|---|
| CHROMATIC | 12-EDO — PLUCK's behaviour | equal-division, no source needed |
| PENTATONIC (gong) | guzheng's open strings | research pending |
| RAST | Arabic, neutral 3rd and 7th | research pending (24-EDO convention vs practice) |
| BAYATI | Arabic, neutral 2nd | research pending |
| HIJAZ | Arabic, augmented 2nd | research pending |
| SHUR | Persian, koron 2nd | research pending |
| MAHUR | Persian | research pending |
| MIYAKO-BUSHI | Japanese in-scale | research pending |
| 24-EDO | every quarter tone | equal-division, no source needed |
| 19-EDO, 31-EDO | xenharmonic | equal-division, no source needed |
| FREE | unsnapped — TUNE is continuous over 24 semitones | none |

Each voice defaults to its own tradition's scale (OUD → RAST, SANTUR → SHUR,
GUZHENG → PENTATONIC, SHAMISEN → MIYAKO-BUSHI) — but **every scale is
reachable from every voice**. A santur in 19-EDO is a feature.

### FREE, and the "atonal" question

Asked in conversation: does this enable more atonal abilities? Recorded
answer: *atonal* (no key centre) was always possible with twelve semitones;
what SCALE adds is *microtonal* and *xenharmonic* pitch — notes between the
piano keys, and whole tunings outside the Western set. FREE is the one
place "snapped, never a mistuning" is given up on purpose: any pitch in the
range, for sound design rather than melody. It is last in the table so a
SCRAMBLE roll reaches it only as often as any other scale.

### Where microtones meet the MPC

- **Pads: already fine.** Each SILK pad is rendered at its exact pitch, and
  a pad's `tuneCoarse` / `tuneFine` (−36..36 semitones, −100..100 cents,
  `Kit.kt:97-98`) exist for the kit-level key picker. A quarter-tone pad
  needs nothing new in the export.
- **The pad tune readout** (F5.3) shows a name and a cents offset against
  12-EDO for a SILK pad, e.g. `E♭ −50¢`, instead of rounding to the nearest
  semitone. The IN KEY action leaves SILK pads with a non-CHROMATIC SCALE
  alone — they are in their own key by construction.
- **Keygroups: out of scope.** `Keys.kt` samples every minor third and lets
  the MPC transpose in semitones; a maqam keyboard needs one zone per key,
  each rendered at its own degree. Named as the follow-on below.

## The voices

Each voice is a recipe over `Strings`. Every Hz, t60 and cents value below
that comes from an instrument (a body mode, a tuning, a detune) is a
**placeholder marked "shape, not measurement"** until the research note
confirms it — the rule PLUCK's Phase 2 kept. DSP constants (a feedback
range, an allpass count) are engineering and are set by the audition.

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
  share the pitch envelope so the detune survives the glide.
- **Body:** a bowl-back table under BODY. Research returned eight candidate
  peaks, all unsupported; until one is read, BODY uses a **neutral wooden
  shape** (the guitar's confirmed low modes, shifted by ear) and says so.
- **Loop:** darker and shorter than NYLON (nylon trebles, fretless neck).

### GUZHENG — stiffness and the press

- **Loop:** one string per note, bright, long ring (DAMP default low).
- **Dispersion:** **STIFF** sets the allpass coefficient (`a` from 0 to a
  negative bound found by the budget test) with `M = 4` stages. 0 is a
  harmonic string; 1 is bell-like, past any real guzheng — the ugly end is
  kept reachable (audition-gate rule).
- **Press:** **PRESS** is the left hand pushing down behind the bridge: the
  note starts at the open string one scale degree *below* and bends up to
  the target (the *an* technique, which is how a pentatonic instrument plays
  its missing 4th and 7th). 0 = plucked open; 1 = a slow, full bend.
- **Pick:** fingerpicks — PICK high, STRIKE low.
- **Body:** paulownia box; research pending.

### SANTUR — four strings and the wash

- **Course:** `Course(N = 4)`, COURSE is the spread of the four — the
  santur's shimmer is four unison strings slightly apart, before any
  sympathy.
- **Excitation:** `Exciter.mallet`. PICK is **mallet hardness** (pulse
  width: wide and dark to narrow and bright), so velocity-through-PICK
  means the same thing it does on every other voice. STRIKE is where on the
  string it lands. One pulse, no bounce: light mezrabs are reported not to
  bounce (unsupported; kept as the simpler default either way).
- **Wash:** **WASH** is the undamped instrument. A bank of resonators
  (`Modes`, specified by t60 — never Q) tuned to the **current SCALE's
  degrees across three octaves**, driven by the string's first difference
  (PLUCK's body drive, same reasoning). WASH sets the bank's level and its
  t60 together. The bank follows SCALE, so a SHUR santur rings in SHUR.
- **Body:** research pending.

### SHAMISEN — sawari and the slap

- **Loop:** one string, bright, shortish ring.
- **Sawari:** **SAWARI** is the obstacle's depth: 0 = no contact, 1 = the
  string grazing the neck on every swing. Strength needs no second knob —
  it follows the string's amplitude, as the jawari outline in PLUCK's spec
  already reasons. Loss on contact is fixed; the buzz must never add energy
  (tested).
- **Slap:** **SLAP** is the bachi hitting the skin with the string: a short
  noise burst through a small skin `Modes` table, mixed in parallel. 0 =
  string only.
- **Body:** the skin *is* the body; the string drives it as BANJO's string
  drives its head. Skin modes research pending.
- **Pick:** the bachi — PICK high, STRIKE very low.

## Macros

Eight per voice: six shared, two character.

| Macro | Meaning | All voices |
|---|---|---|
| TUNE | scale degree over two octaves above the voice's root | ✓ |
| SCALE | which scale TUNE walks (snapped table above) | ✓ |
| DAMP | the loop's decay and brightness together (PLUCK's meaning) | ✓ |
| PICK | exciter brightness; mallet hardness on SANTUR. The velocity macro | ✓ |
| STRIKE | where on the string — bridge to centre (PLUCK's map) | ✓ |
| BODY | the body table's level against the string | ✓ |

| Voice | Character 1 | Character 2 |
|---|---|---|
| OUD | COURSE — spread of the pair | SLIDE — bend in from below |
| GUZHENG | STIFF — dispersion | PRESS — pressed bend up to the note |
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
| Dispersion coefficient out of range | `a` clamped to `(−0.95, 0]`; `|a| < 1` is what keeps an allpass stable |
| Pitch envelope drives the loop under its floor | SLIDE and PRESS only ever start *below* the target, so the loop only lengthens during a bend |
| Collision adds energy | the fold-back loss is `< 1` by construction; a test renders SAWARI 1 at PICK 1 and asserts the envelope never rises after the attack |
| Sympathetic bank blows up the level | linear bank, levelled by `Dsp.levelTo` after render; no clipper |
| A SCALE table row without a source | not shipped. A pending row is absent from the table, not present with guessed cents |

## Testing

### The ones that carry the claims

1. **In tune at every degree of every scale.** For each voice × each shipped
   SCALE × each TUNE degree, the rendered fundamental lands within 5 cents
   of *the scale's own* target (not 12-EDO's). PLUCK's 5-cent rule,
   generalised.
2. **STIFF makes partials sharp.** At STIFF 1, partial `n`'s measured
   frequency divided by `n·f0` rises with `n` (monotonic over the first
   eight partials); at STIFF 0 it stays within 5 cents of harmonic. This is
   the test that catches a sign error in `a`.
3. **PLUCK unchanged.** The bit-identical hashes across the `Strings`
   extraction.

### The rest

- COURSE: the envelope of a COURSE-1 render shows beating (amplitude
  modulation) that a COURSE-0 render does not.
- SLIDE / PRESS: the pitch track starts below the target and ends on it
  within 5 cents.
- WASH: the tail after the string's own t60 is louder with WASH 1 than 0,
  and its spectral peaks sit on the SCALE's degrees.
- SAWARI: late-tail high-band energy ratio rises with SAWARI; the envelope
  never grows (the energy test above).
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
| 0 | Research re-run with network access; throwaway spike renders of all four for a first listen | none (spike only) | research note rows confirmed or struck; spike heard |
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
| Microtones | yes, through TUNE — SCALE macro, TUNE walks degrees; FREE for unsnapped |
| Scope | all four voices, phased by DSP risk (OUD+GUZHENG, SANTUR, SHAMISEN) |
| Order of work | research, then this spec, then per-phase plans |

### Open for review

1. **Network.** The research re-run needs the environment's network access
   widened (at least pub.dega-akustik.de, en.wikipedia.org,
   ccrma.stanford.edu, arxiv.org, researchgate.net, pubs.aip.org). Until
   then every instrument number here is a placeholder.
2. **SCALE on every voice, or per-voice lists?** This spec says every scale
   everywhere, each voice defaulting to its tradition.
3. **Eight macros.** Two more than PLUCK's six. If the screen
   is crowded, SCALE could move to a chip above the knobs rather than a
   knob.
