# PLUCK Phase 3a — SITAR: the jawari, the sympathetic strings, the gourd, and stiffness in the loop

**Status:** implementing on claude/pluck-depth-phase-3 (plan 2026-09-26-pluck-sitar.md).
**Date:** 2026-09-26
**Parent:** [`2026-09-25-pluck-depth-design.md`](2026-09-25-pluck-depth-design.md) — this is
the "Phase 3 (outline)" row of that spec, made concrete after Phase 2's
four listening gates. Phase 2's other leftovers (a fixed per-voice body
calibration, a pitch feature in the audio module's classifier, the KALIMBA
preset pass, koto's remaining body modes) are out of scope here and get
their own specs.
**Plan:** docs/superpowers/plans/2026-09-26-pluck-sitar.md

## Why a sitar, and why now

Josh asked for it in Phase 2 ("Could we replace with another string for
pluck? Sitar?") and chose "banjo first, sitar later". Banjo is in. A sitar
is the one plucked string whose identity lives in things the engine does
not have yet: a bridge that buzzes, a bed of strings that ring in sympathy,
and long steel strings whose partials sit sharp of harmonic. Each of those
is a small addition to the Karplus-Strong path, and the two that are
generic (stiffness, sympathetic loops) are reusable by KOTO and HARP once
they exist.

Three decisions from the brainstorm bind this design:

1. **SITAR is a voice with presets, like BANJO.** It has no kit pads in this
   phase. Any single note stands on its own, so the sympathetic strings tune
   to the played note's own series, not to a scale.
2. **The jawari is a property of the voice, driven by velocity.** Every
   sitar note buzzes; harder plucks buzz more; the buzz fades with the note.
   The six PLUCK macros keep their meanings on every voice. No seventh macro.
3. **All four approaches as recommended:** the jawari inside the loop (with
   an output-side buzz as the fallback if it pulls the pitch too far), the
   sympathetic strings as short Karplus-Strong loops under DOUBLE, a sourced
   gourd body, and dispersion as a per-voice allpass that KOTO and HARP carry
   at zero until a gate hears it on.

## Architecture

SITAR is the fifth member of `PluckVoice`. Every `when` over the voices
gains a branch — `Pluck.kt` (constants, defaults, root, body table),
`PluckPresets.kt` (twelve presets), `Patches.kt`, `Presets.kt`, `Keys.kt`,
`SynthKits.kt` helpers where they enumerate, and the app's `SynthScreen`
class mapping (TONAL, like the others).

The string path is Phase 2's path with two stages added inside the loop
and one layer added beside it:

```
burst ─(PICK low-pass)─(STRIKE comb)─▶ ┌─ delay ─ two-tap average ─ tuning allpass ─ stiffness allpass ─ loop low-pass ─ jawari ─ DC blocker ─┐
                                        └────────────────────────────────── feedback ◀───────────────────────────────────────────────────────┘
                                                              │ string
                                   sympathetic loops (DOUBLE) ◀┤
                                     first difference ─▶ gourd body (BODY) ─▶ +
```

Nothing before the loop changes. STRIKE's comb, PICK's low-pass and the
seeded burst are as Phase 1 and 2 left them. The render/decimate/level/fade
tail, the decay-following length and the 4 s ceiling are untouched.

### Voice constants (starting values; the gate moves them)

| Constant | SITAR | Why |
|---|---|---|
| root | 139 Hz (C#3) | the common tonic of the playing string; TUNE's default 0.5 lands on C#4 |
| loop low-pass | 7000 Hz | steel strings under a wire plectrum: brighter than KOTO (4200), darker than BANJO (9000) |
| pick band | 2500–12000 Hz | the mizrab is a wire pick |
| ring | 1.4 | a sitar sustains longer than a koto; HARP is 1.3 |
| STRIKE default | 0.30 | plucked nearer the bridge than the guitar's quarter |
| DOUBLE default | 0.40 | the sympathetic strings are present by default |
| DAMP default | 0.50 | a 1.3 s budget: the sympathetic strings ring to the end of it, and a longer default reads as a loop to the classifier (Task 4 ruling) |
| PICK default | 0.65 | bright |
| BODY default | placeholder until the gate, written as such in code |
| stiffness | chosen at the gate between two computed candidates (see Dispersion) |
| jawari drive | chosen at the gate among three (see The jawari) |

### The jawari

The bridge of a sitar is a flat, slightly curved ivory or bone surface.
As the string swings toward it, the string wraps on the curve, which
shortens the vibrating length for that half of the cycle. The effect is
asymmetric (one side only), grows with the swing (amplitude-dependent), and
fades as the note decays — which is why a sitar note "opens" into its buzz
and then closes. Issanchou et al. (2018, research note §2, source 6), who
simulate the string against the bridge as a nonsmooth contact problem and
compare it with measurement, describe the same thing as a "descending
formant": energy pushed into the high partials at the onset and receding as
the amplitude falls, and record the flat-topped "crenel" shape directly
(their Fig. 2) that a hard one-sided clamp reproduces. The engine
reproduces the direction, not the model — their tanpura case needs a 2 MHz
sampling rate and a complementarity solve per contact step.

In the loop, after the low-pass and before feedback:

```
z = y − k · min(y, p0) · y / p0    (y > 0)
```

where `y` is the low-passed loop signal, `p0` the exciter's peak (so the
term is a fraction of the string's own level, not an absolute), and `k`
the drive. The `min(y, p0)` clamp means `|z| ≤ |y|` holds above `p0` as
well, not only below it. The sign matters: the bridge is a barrier, so it
can only *limit* the string's swing toward it, never add to it — a
one-sided limiter loses a little energy on each positive half-cycle and
generates the even harmonics of the buzz, and because `|z| ≤ |y|` it can
never raise the loop's gain above one. (A term that added to `y` would be
a positive feedback on half of every cycle and would run away at feedback
near one.) Below the clamp the term is quadratic in `y`, so it is loudest
at the onset and vanishes on its own as the note decays; no envelope is
needed. `k` is the voice's drive constant times a velocity map
`lin(velocity, 0.3, 1.0)`: a soft note buzzes a little, a hard one buzzes
fully. The drive constant starts at 0.3; the audition hears 0.15, 0.3 and
0.6 and the chips choose.

A one-sided term adds offset. A DC blocker follows it inside the loop —
the signal minus its own 2 Hz one-pole low-pass — so the loop cannot
accumulate the offset over hundreds of cycles. Its phase delay at the
fundamental joins the tuning budget the same way the low-pass's and the
stiffness allpass's already do, and a corner far below the lowest note
keeps its own dispersion negligible: 0.11% at C#4's tenth partial.

The nonlinearity sits in feedback, so two guards are part of the design,
not afterthoughts: the stability test (below) and the fallback. **Fallback:**
if the tuning sweep at full drive pulls any note past a quarter tone, or
the stability test fails at any DAMP, the jawari moves outside the loop as
an amplitude-gated one-sided waveshaper on the string's output (the same
shape, applied once, no feedback). That version cannot "open" the tone the
same way, and the spec records the choice if it is taken.

**History (spike round, 2026-09-27): the fallback above, and two other
in-loop placements, were tried and measured, and none replaced the version
shipped above.** A hard clamp inside the loop referenced to the exciter's
own pre-loop peak barely engaged: `y`, the clamped quantity, is measured
after the two-tap average and the loop low-pass have already stripped
most of the burst's energy, so the loop's working amplitude sits well
below that reference and the clamp rarely crossed it — the onset's
high-band share sat flat at 0.41–0.45 across the whole depth range tried,
never reaching a 0.55 gate. Referenced instead to the loop's own running
swing, an in-loop clamp damps and pulls: it removes loop energy every
cycle it engages (high-band share 0.09 against a dry string's 0.19 in the
same window — less buzz than no jawari at all — and render duration
collapsed from ~0.5 s to 0.38 s) and detuned the note by 65 cents at depth
0.7, non-monotonically as the depth was stepped down — ruling out a simple
wrong-constant fix, since the tuning budget above has no term for what an
in-loop nonlinearity does to the loop's own phase. Taking the fallback —
moved outside the loop, referenced to the raw signal — fixed both of
those (tuning and decay both clean at every depth tried) but never
buzzed, because the burst is forty times the tone: a peak follower's
max-hold latched onto the exciter burst (peak ≈ 1.0) rather than the
tone's own steady swing (peak ≈ 0.03 in the 0.15–0.35 s measurement
window) and decayed from that inflated value too slowly to ever give the
tone's own peaks room to clamp against. Low-passing the follower at
800 Hz so it would track the fundamental instead of the burst stopped the
latching, but the clip removes the string's own harmonics instead of
adding them — it behaves as a declipper, not a buzz generator: the
crenel's own band (`PluckSpectra.harmonicsOverFundamental`, the 2nd–8th
harmonic over the fundamental, past onset) fell *below* the dry string's
at every depth tried, and fell further as depth rose. **The shipped
jawari stays the version above — in the loop, a one-sided quadratic bend
referenced to the exciter's peak — until a design is found that closes
one of these four failure modes (barely engages; damps and pulls;
misses the tone for the burst; declips instead of buzzing) without
opening another.**

**Velocity reaches the render as a number.** Today `Velocity.atVelocity`
moves the brightness macro (PICK, for PLUCK) and re-renders. Patch
validation rejects any macro key `macrosFor` does not list, so velocity
cannot travel in the macro map; instead `Pluck.render` gains a `velocity`
parameter (default 1.0) and `atVelocity` calls it directly for a
`PluckPatch`, passing the scaled PICK map and the velocity. Velocity is not
a `MacroSpec`: it has no knob, no preset carries it, no pad recipe saves it,
and `macrosFor` does not list it. The other four voices ignore it.

**The mechanism changed again after this section was written.** A later
round on this branch replaced the quadratic-bend-plus-DC-blocker version
above with a different in-loop design: the loop length is stretched by a
static budget computed from the drive alone (`exact` in `Pluck.ks`), and a
slow (2 ms attack / 15 ms release) envelope follower shifts the delay
line's read position by a fraction of the period, capped at
`SITAR_WRAP_MAX`. `Pluck.kt`'s own KDoc on `ks`'s `jawari` parameter and on
`SITAR_JAWARI` is the current, authoritative description of what actually
ships (or is staged to) - this section's diagrams and formula are history,
not the present mechanism.

**Resolution (2026-09-27).** Three engineering rounds separate the design
above from what ships. The first tried the fast, rail-driven drive
(0.3, per-sample, essentially the formula above) and broke the spectral
tests meant to prove the buzz was real - the "History" paragraph above
records the four placements tried and how each failed. The second drained
the rail through a DC blocker inside the loop and broke tuning instead -
a one-sided term inside feedback needs its own phase budgeted into the
loop length, and this one detuned the note non-monotonically as depth
fell, ruling out a simple wrong-constant fix. The third replaced the
mechanism outright: `Pluck.ks`'s KDoc on `jawari` has the version that
ships - a slow (2 ms attack / 15 ms release) envelope follower shifting
the delay line's own read position, the loop's average length stretched
by a static budget computed from the drive alone, no runtime giveback.
This fixed tuning (every semitone inside a quarter tone, at every depth
tried) but left the depth-vs-buzz relationship unexamined.

Josh's first gate on that third mechanism (chips on the audition page,
before anyone had measured that relationship) picked the fuller of two
depths on offer, 0.015 over 0.003 - `spike_wrap_020`,
`p3b_wrap_full_default` and `p3b_wrap_full_root` all chipped closer, at
both the played note (C#4) and the root (C#3). Measuring before
committing (this repo's practice for every round on this branch) found
0.015 actually measures with LESS harmonic content than 0.003, by the
measure `PluckSpectra`'s own KDoc names for exactly this
(`harmonicsOverFundamental`, the 2nd-8th harmonic over the fundamental,
past onset) - the chip had been read as "buzz over strict tuning," but
the harmonics said the opposite. A second, cleaner gate settled it: a
plain two-clip A/B, 0.010 against 0.015, no other context - Josh picked
0.010 outright. That preference and the measurement agree, independently:
`harmonicsOverFundamental` read 4.4709 with no wrap at all, peaked at
4.6422 at 0.010, and had already fallen to 3.0152 by 0.015 at the time -
0.015 was already past its own peak. (These numbers were measured before
stiffness moved to HIGH and before BODY existed; re-measured 2026-09-28 at
the final shipped configuration - stiffness HIGH, BODY isolated out the
same way the wrap's own tuning-cost measurement isolates DOUBLE - they
move to 1.3653/1.7966/2.5485 and the 0.015-already-past-its-peak shape no
longer holds once stiffness is in the mix; see `Pluck.SITAR_JAWARI`'s
KDoc. The one claim that held then and holds now: the wrap adds harmonic
content over having none.) **`SITAR_JAWARI = 0.010` ships.**
`the wrap adds harmonics over the fundamental at the shipped depth`
(PluckTest) asserts it for real, the buzz-content test this design
deferred since its very first draft.

The DC-offset and dry-decay-duration questions the 0.015 attempt raised
were resolved along the way, independent of the final depth. `Pluck.ks`
and `Pluck.synthesize`'s SITAR branch each gained an output-side one-pole
high-pass at 2 Hz (guarded by `jawari > 0f`, outside any feedback path, so
no budget term needed, unlike the old in-loop blocker) - the new
mechanism's loop has no DC blocker analogous to the old one's, and the
tarab's own near-unity feedback (`SYMPATHETIC_FEEDBACK` 0.995, a DC gain
of `1/(1-0.995) = 200`) turned even a tiny residual from the string-level
fix back into a measurable offset, so it needed the fix twice (string
ratio 2.76e-4 -> 1.47e-6; tarab ratio 8.05e-4 -> 1.82e-5, both now under
the 1.0e-4 bound). `the jawari leaves no offset` passes, and `the
oversampled render's decay time matches a direct native-rate render`
(every voice) still passes too - the specific regression a
rate-mismatched high-pass caused in an earlier attempt at exactly this
fix did not recur, since both `Dsp.OnePole`s are constructed with their
call site's own `rate` parameter explicitly. Separately, the apparent
dry-decay-duration finding turned out to be a measurement artifact, not
an engine defect: `trimToDecay`'s -60dB-from-peak cut moves with the
peak, not only the decay rate (confirmed: RMS compared in two fixed
windows at jawari 0 and 0.015 decayed within 0.6 dB of each other), so
`DOUBLE on the sitar is the sympathetic strings, not the detune` and `the
scale tuning rings where the note has nothing` now size their own "late"
analysis window relative to whatever duration the renders being compared
actually return, rather than assuming a fixed number of seconds will
always be there.

At the shipped 0.010, the tuning cost is real but smaller than 0.015's:
swept across all 25 semitones at the default (DOUBLE 0,
`TuningAccuracyTest`'s "the wrap's tuning cost stays inside a quarter
tone..."), worst sharp is +11.4 cents (semitone 2, not the root), worst
flat -15.7 cents (semitone 24), crossing between semitones 8 and 9 - the
same crossing point as 0.015, at roughly a third of that depth's own
worst-case cents. Most notes still fall outside the ordinary five-cent
bound, so the shared sweep (`every Pluck semitone lands within five cents
at the default body`) still excludes SITAR by name, pointing at the
dedicated test. Three more tests reach the same string cost and are
handled the same way, measured rather than loosened blind: `STRIKE at
either end keeps every Pluck voice within five cents` now skips SITAR too
- a new companion test, `the wrap's tuning cost stays inside a quarter
tone at both STRIKE ends too`, covers what it drops (-7.8 cents at the
default STRIKE, -12.2 at STRIKE 0, both well inside the quarter tone);
and `the sympathetic strings at DOUBLE 1 keep every sitar note within
five cents` / `the scale tuning at DOUBLE 1 keeps every sitar note within
five cents` are renamed to `... inside a quarter tone, the wrap's own
cost aside`, since the five-cent bound they carried was never actually
testing what DOUBLE 1 itself adds - their own worst measured values
(~7-8 cents, at the root) track the dry string's own root-note cost
(~8.4 cents), not a DOUBLE-1-specific defect.

### Sympathetic strings under DOUBLE

A sitar carries eleven to thirteen tarab strings under the frets, tuned to
the raga, that ring in sympathy with whatever is played. With no scale to
tune to (decision 1), they tune to the played note's own series. When
DOUBLE is above zero, four short loops ring beside the main string:

| Loop | Ratio to the note | Why |
|---|---|---|
| 1 | 0.5 | the octave below: the drone |
| 2 | 1.5 | the fifth |
| 3 | 2.0 | the octave |
| 4 | 3.0 | the octave and a fifth |

Each is a Karplus-Strong loop with feedback 0.995 and a darker low-pass at
4 kHz, **fed by the played string alone** at a coupling of 0.05, the way
the bridge transmits vibration to the tarab, rather than by its own burst
or by each other. A tarab rings long, not forever: 0.995 is a decay of
about five seconds at C#4 and ten an octave below; at 0.999 the loops held
the render to the end of the budget. Their sum enters the output at
`0.5 · DOUBLE`, so DOUBLE 1 is a drone and is the ugly end on purpose. At
DOUBLE 0 they are not rendered and cost nothing. With the tarab on, the
note runs to the end of its DAMP budget and ends on the trim's short fade,
rather than where the string itself stops ringing. On SITAR, DOUBLE
therefore stops meaning the twelve-string detune it means on the other
four voices; the macro's KDoc says so.

Cost: five loops instead of one. The Phase 2 profile put PLUCK at a quarter
of the fleet average, so this is affordable without a budget change.

### Dispersion

A stiff string's partials sit sharp of harmonic by a factor that grows
with the partial number. In the loop, one first-order allpass per voice
carries that stiffness: its coefficient comes from a per-voice constant,
and its phase delay at the fundamental is added to the loop's tuning budget
the same way the low-pass's delay already is, so the fundamental stays in
tune while the upper partials stretch.

| Voice | Stiffness | Why |
|---|---|---|
| SITAR | HIGH ships (2026-09-28 gate, `p3a_stiff_high`): the allpass coefficient that puts the tenth partial 3.0% sharp (the stiff-string law `n·√(1 + B·n²)` with B ≈ 6e-4). Combined with the current wrap (0.010), the root note reads 16.1 c sharp (`measuredHz`/`cents`, `TuningAccuracyTest`'s own approach); the whole sweep stays inside a quarter tone (`the high stiffness candidate with the bridge on...`). The LOW candidate (1.0% sharp, B ≈ 2e-4) remains in the audition as the road not taken. Both found by a measuring probe in the plan, not tuned by hand | long steel strings: the inharmonicity is audible |
| KOTO, HARP | 0, with a dispersion-on candidate in the audition | both passed a gate; they do not change unheard |
| NYLON, BANJO | 0 | not offered |

The allpass phase at the fundamental is computed from the coefficient the
same way `filterDelay` is computed from the low-pass pole, and the
`MIN_LOOP_SAMPLES` guard covers the combined delay.

A single first-order allpass pins one partial (the tenth) and only
approximates the stiff-string law; its delay is fixed in samples, so
shorter loops are more inharmonic, which is the right direction for a
fretted string.

A real baaj string's numbers — steel, 0.31 mm diameter, 0.88 m speaking
length, tuned to D3 at about 40 N, E = 2e11 Pa — give an inharmonicity
coefficient near 3e-5 by B = pi^2 E I / (T L^2), so its tenth partial sits
about 0.1% sharp. The LOW candidate's 1.0% is roughly ten times that, and
the shipped HIGH candidate's 3.0% is roughly thirty times it, so stiffness
OFF is the physically faithful setting and LOW/HIGH are stylizations — the
gate chose HIGH (2026-09-28) — recorded so the section does not read as if
3% were measured.

### The gourd body

The sitar's tumba (gourd) and tabli (soundboard) are its body. The Phase 2
rule stands: **every Hz in the body table comes from a source the verifier
opened.** The research ran before this section was final
(`2026-09-26-pluck-sitar-body-research.md`, 2026-09-26) and came back with
**zero body modes**: six sources opened, all about the string, the bridge
or the sympathetic strings; the four candidates that plausibly hold a
measured body mode — above all Limkar & Chandekar's 2022 modal analysis in
the *Journal of Vibration and Control* — sit behind paywalls no
policy-compliant route opened. No opened source gives the gourd's
dimensions either, so a Helmholtz frequency cannot be derived from a cited
geometry.

So SITAR **started without a body table.** `bodyFor(SITAR)` was empty and
`withBody` returned the string unchanged; the BODY macro was inert on this
voice and its KDoc said why. The jawari, the sympathetic strings and the
stiffness carried the identity at the first gate, and the audition
rendered the voice at BODY 0 only. A fully sourced body still arrives in a
follow-up inside this phase the moment a source is read — the paywalled
paper, if Josh can open it through an institution or a purchase, is the
single most likely source of a full modal table — and then the rows enter
exactly as Phase 2's did: driven by the first difference, RMS-matched over
the string, zero-padded tail, BODY's reach 0–3× the string. The
classification and BODY tests enumerate voices with a table, so an empty
table is skipped, not failed.

**2026-09-27:** the paper could not be obtained, so under one explicit
exception to the sourcing rule — a shape may ship if the gate chose it and
it is labelled — `bodyFor(SITAR)` carries three unsourced modes, 110 Hz
(air, Q ~ 10), 270 Hz (soundboard, Q ~ 8), 520 Hz (bridge region, Q ~ 6),
as candidates on the page. BODY's default stays 0 until the gate chooses;
the note's section 5 (`2026-09-26-pluck-sitar-body-research.md`) lists
them as shapes.

**2026-09-28:** BODY 1 ships as SITAR's default, settled across two gates.
First, `p3a_body_1` chipped closer — the "dominant" amount (`BODY_MAX`,
three times the string) — at the note that's actually representative
(TUNE's default, 277 Hz). A separate chip, `p3a_body_35_root`, preferred
the quieter .35 — but only at the root note (139 Hz), not the default: the
three fixed resonances (110/270/520 Hz) sit closer to 139 Hz than to
277 Hz, so a smaller amount already reads convincing there regardless of
its actual size — an artifact of where the note sits, not a competing
verdict about the amount itself. `TuningAccuracyTest`'s BODY-at-its-ugly-end
sweep, run at these final defaults (stiffness HIGH, the 0.010 wrap, the
raised tarab, all at once), measures SITAR's actual shipped tuning cost
rather than a setting nobody hears — worst case +16.46 cents at semitone
24, inside the quarter tone the sweep already held every voice to.

Second: once BODY 1 was combined with the wrap and the louder tarab, a
muted string (DAMP 1) turned out to keep resonating through the body for
about half a second — narrow enough a concern that it went back to Josh as
its own direct A/B, isolated from every other axis: TIGHT (BODY 0) against
WITH BODY (BODY 1), both at DAMP 1 (`answer/mute_body`). His call: **"Hum
is fine with body."** The fuller amount, confirmed rather than walked back
to the .35 fallback. `SITAR at DAMP one hums through the body instead of
thudding...` (PluckTest) documents the exception this creates against the
shared `DAMP at one is a short thud` bound (every other voice stays under
0.5 s; SITAR measures ~0.55 s and is held to a looser, honestly-disclosed
1.0 s ceiling instead, not left unbounded).

Two presets needed their own correction once BODY's default actually
reached 1 - stacked with a fixed body mode, the crest factor falls low
enough that `Dsp.levelTo`'s loudness match squashes a render's peak under
`PluckPresetsTest`'s 0.5 floor. This was first scoped to SITAR's RINGING
alone (its DAMP 0 is the most sustained corner any SITAR preset uses) on
the theory that DAMP was the whole story; measuring all twelve presets
against the real 1.0 default - not the interim .35 an earlier round
shipped while this was still under investigation - found a second driver,
proximity to `bodyFor(SITAR)`'s 110 Hz mode, and a second preset, LOW
TONIC (`TUNE 0`, the root note at 139 Hz, close enough to that mode to be
exposed even at a moderate DAMP 0.3), that driver alone accounts for.
Each now carries its own BODY override, the last amount that keeps its own
peak at the untouched-string ceiling (0.99), measured across the whole
range rather than guessed: RINGING at `"BODY" to 0.35f` (0.99 through
0.35, falling smoothly above it to 0.4754537 at 1.0 - the same amount
`p3a_body_35_root` already established as carrying real character, not a
compromise toward zero), LOW TONIC at `"BODY" to 0.2f` (0.99 through 0.2,
falling to 0.44782394 at 1.0 - smaller than RINGING's because the root
sits nearer the resonance). Every other preset clears 0.5 on the voice
default with real margin; DRONE (0.594) and CENTRE PICK (0.624) are the
closest of the rest, neither close enough to need touching.

### Presets

Twelve SITAR presets, disposable like every other preset file, authored
for the audition rather than by ear: a plain ALAAP, a bright JHALA, a
drone-heavy one, a dry one with DOUBLE 0, a muted one, a high one, two with
STRIKE at its ends, and four spread over DAMP. `PluckPresetsTest` keeps
them rendering clean; the parent spec's by-ear pass re-authors them later.

## Data flow

- `Pluck.render(voice, macros, velocity = 1f)` gains the velocity
  parameter; `synthesize` takes it too and passes the jawari drive into
  `ks`. `PluckPatch.render()` keeps calling it with the default.
- `ks` gains two optional parameters: a stiffness coefficient (default 0,
  no allpass) and a jawari drive (default 0, no nonlinearity, no DC
  blocker). The sympathetic loops are a separate private
  `sympathetic(input, hz, rate)` beside `ks`, sharing its geometry
  derivation by copy; folding the two into one helper is a Phase 3b item
  before KOTO and HARP reuse the pattern. With every default, `ks`
  produces the Phase 2 output byte for byte; the test asserts it.
- `Velocity.atVelocity` adds `VELOCITY` to the map for `PluckPatch` beside
  the PICK move it already makes. `Velocity.brightnessOverride` is
  unchanged (PICK for every PLUCK voice).
- The audition generator renders the SITAR set; the listening page adds a
  SITAR card; the artifact's database keys carry a phase prefix (`p3a_*`).

## Error handling

- The `MIN_LOOP_SAMPLES` guard in `ks` includes the stiffness allpass's
  delay in `exact`, so a note too high for the combined delay fails loudly
  with the existing message.
- The jawari's DC blocker and the stability test guard the loop; the
  fallback is written into the spec (above) so the plan can take it without
  a new design round.
- A sympathetic loop at ratio 3 on the highest TUNE (two octaves above
  C#3 → C#5 → 1662 Hz at ratio 3) is still above `MIN_LOOP_SAMPLES` at the
  4× render rate; the test sweeps every note with DOUBLE 1 to prove it.

## Testing

Every test is a measurement on the render, in the pattern of `PluckTest`
and `TuningAccuracyTest`:

- **Tuning.** All 25 notes at the defaults (jawari and sympathetic strings
  on) within five cents. All 25 at full jawari drive within a quarter tone,
  every note over five cents printed for the gate. All 25 at DOUBLE 1
  within five cents (the sympathetic loops must not pull the note).
- **Stability.** At every DAMP tenth and full drive, at DOUBLE 0 the
  −60 dB cut lands before the budget for DAMP ≥ 0.3, and no render exceeds
  its budget; with the tarab on the note runs to the budget's end by
  design. No render clips.
- **The buzz follows velocity.** The high band's share of energy over the
  first 200 ms rises monotonically with VELOCITY at 0.3, 0.65 and 1.0.
- **No offset.** The output's mean over the note is under 1e-4 of its
  peak, at DOUBLE 0 and again at the default macros (DOUBLE 0.4, the tarab
  on).
- **DOUBLE 0 is the string, byte for byte.** The DOUBLE test measures the
  drone at f0/2 and the fifth at 1.5·f0 over a 0.3–0.55 s window (not the
  octave at 2·f0, which the old detune branch also raised); DOUBLE 1
  raises each by at least 6 dB over DOUBLE 0.
- **Dispersion is real.** With the stiffness candidate on, the tenth
  partial sits above ten times the fundamental by the amount the
  coefficient predicts, within a tolerance; with stiffness 0 it sits on it.
- **The high candidate holds under the bridge.** With
  `SITAR_STIFFNESS_HIGH` and the shipped jawari, five notes across the
  range stay within a quarter tone, printing any that read over five cents
  so the gate sees the pull as a number, not a comment.
- **The defaults are the Phase 2 path.** `ks` with every new parameter at
  its default matches the Phase 2 render byte for byte on every voice.
- **Classification.** The classification test states what `Classifier`
  may call SITAR once measured, the way it does for BANJO, and asserts it.
- **Everything else as today:** export regression renders a SITAR preset,
  presets render clean, the melodic kit is unchanged, `SynthKitTest` and
  `VelocityGrooveShuffleTest` pass.

## Audition

The gate is Josh's chips on the listening page, before merge. Candidates
per axis, each at the default note and the root:

- the jawari drive at three levels (soft, the constant, hard);
- the sympathetic level at two (the default and DOUBLE 1);
- dispersion on and off, and on KOTO and HARP the same pair;
- BODY at 0 only, until a source gives the voice a table.

The constants the chips choose ship. A second round, if needed, follows
the Phase 2 pattern: last time's clip beside the new one.

## Sequence

1. Research note: done 2026-09-26, zero body modes reachable; the body
   waits on a source (see The gourd body).
2. Plan: written from this spec and the note, with no body table.
3. Implementation in tasks, each gated by review; the audition set last.
4. Josh's gate; a second round if the chips ask for one.
5. Merge.

## Out of scope

Kit pads for SITAR; the classifier's pitch feature; the KALIMBA preset
pass; koto's remaining body modes; a fixed per-voice body calibration;
meend (the pitch bend that pulls the string sideways along a fret), which
needs a control the pad has no gesture for.
