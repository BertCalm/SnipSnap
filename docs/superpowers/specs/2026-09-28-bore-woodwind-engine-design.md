# BORE — the woodwind engine: a blown bore in a fleet of strikes

**Status:** design; brainstorm from the 2026-09-28 specification. **R0,
the shared-toolkit change, is built** (2026-09-28; "R0, as built" below);
**R1, the engine, is built** (2026-09-29; "R1, as built" below), its presets
provisional and its audition gate not yet run. The spec's engine, transcribed and rendered, does not work
(three of five voices NaN, one a DC limit cycle, one an octave up — "The
specification, as reviewed"); the corrections below are measured, and
the corrected model is a Phase-0 spike — a throwaway prototype built only
to find out whether the corrected loop works before any of it is designed
in — kept as a record outside the build
([`../plans/2026-09-28-bore-phase-0-spike.md`](../plans/2026-09-28-bore-phase-0-spike.md)),
no engine code committed; its numbers are Appendix B, and R1 rebuilds it
from this design rather than copying it
(see "Phasing and gates"). This document lands as a docs-only PR (zero
check runs by design, `.github/workflows/tests.yml:24-34`; FORK's spec
landed alone as #359, house §5); its commit message names no maker or
machine.
**Date:** 2026-09-28
**Plan:** to be written per round (`docs/superpowers/plans/2026-09-28-bore-round-N.md`)
**Related:** [`2026-09-27-fork-electric-piano-engine-design.md`](2026-09-27-fork-electric-piano-engine-design.md)
is the model for this document (an external spec reviewed against the
code, one physics correction, the fleet table, claims tests, rounds with
gates); [`2026-09-27-silk-string-engine-design.md`](2026-09-27-silk-string-engine-design.md)
built the `Strings` toolkit this engine's bore is made from and set the
Phase-0/bit-identical-extraction shape this document copies (spike first;
then move the shared code into `Strings.kt` in a PR that changes no
audio, proven by comparing PLUCK's renders before and after, sample for
sample);
[`2026-09-27-siren-dub-engine-design.md`](2026-09-27-siren-dub-engine-design.md)
settled HOLD, the LOOP top step and landing an effect by recipe, all three
reused here (HOLD: the length knob for a sound that is held rather than
struck; the LOOP top step: the knob's top position renders a seamless
loop instead of a note; landing by recipe: a pad arrives with rack
effects already written into its saved settings).
**Roadmap:** the `SYNTH_ROADMAP.md` row is added when implementation
starts, not before — the rule FATHOM, RESIN, GLINT, SILK and FORK followed.
The design assumed S17; SILK's Phases 2 and 3 took S17 and S18 while it
waited, so BORE's row is **S19** (added with R1).
**Evidence:** six investigator reports read the specification against the
tree at `20115ab` (an API audit, a DSP desk review with a Python replica, an
empirical probe that transcribed the spec's Kotlin and rendered it, a
house-conventions audit, a fleet comparison, a product review); a Kotlin
spike then built the corrected loop on the house's primitives and
measured it. Every number below is copied from one of those, and each
says which. Tags of the form `dsp F12`, `empirical §3`, `api §1`,
`house §2g`, `fleet §3` and `product §4` name the report and its finding
number or section; a bare `F12` is always the DSP review. The reports are
workflow scratch files, not in the tree, so every fact they back is
restated here in full and the tag records only which report measured it.
The Phase-0 spike's source, its test, its report and the Python replica
scripts are kept as a record in
[`../plans/2026-09-28-bore-phase-0-spike.md`](../plans/2026-09-28-bore-phase-0-spike.md),
outside the build, so its numbers can be re-run; that file and this one
are the only changes to the tree, and no engine code is committed.

## Why BORE

The ask (2026-09-28) arrived as a seventeen-page specification with its
Kotlin attached: an acoustic woodwind physical-modelling engine for
`:synth` — five voices (a baritone sax, a bassoon, a heckelphone, a flute,
a guanzi), six macros (TUNE, BREATH, EMBOUCHURE, VIBRATO, DECAY, MELLO), a
`BorePatch`, a twelve-preset roster, four wiring points, a test file, a
checklist, and an integrated tape-replay stage ("MELLO") that bakes a
pressure-pad clonk, wow, flutter, saturation, head loss and hiss into the
render.

The home decision — which part of the app the engine lives in, and so
whether it renders files offline or plays live under a finger — is
already made, by the spec itself: **this is a `:synth` engine** —
Kotlin, offline, one `Snip` (the app's in-memory audio clip: samples,
channel count, sample rate — `audio/.../Cleanup.kt:12`) per render —
like every engine before it (sixteen `Patch` subtypes today — a `Patch`
is one engine's saved preset, its engine, voice and macro values, the
thing `kit.json` stores and a pad regenerates from; `Patches.kt:36-57` —
and thirteen in the picker, the engine selector on the phone's SYNTH
screen, `SynthScreen.kt:1827`). Unlike FORK's brief there is no
real-time layer to strip: the spec renders at four times the sample rate
(`Dsp.RATE * Dsp.OVERSAMPLE`, 176.4 kHz) so that the harmonics a
nonlinearity makes above hearing can be filtered off before the render
is brought back down to 44.1 kHz, instead of folding back into the
audible band as false tones — aliasing (`Dsp.kt:23-32`); it returns a
`Snip`, and its `BorePatch` is the house shape to the letter. FORK's
four reasons hold unchanged
(`2026-09-27-fork-electric-piano-engine-design.md:22-34`): the product
is WAVs that go into MPC kits and keygroups; CI measures the rendered
sound; a kit regenerates bit-for-bit from `kit.json` — each pad's
*recipe*: the engine, voice, macro values and rack settings it was made
from, so the WAV is never the only copy of a sound; the rack (the
per-pad effects chain — EQ, tape, echo and the rest — saved beside the
pad in `kit.json`), keygroup export (an MPC instrument file that spreads
one sound across the keyboard, one zone per note range), the instant
loop (turn a knob, the pad re-renders in tens of milliseconds and
retriggers) and the 4× oversample all exist for offline renders. Where
BORE sits in the fleet is also already decided, though not by the spec:
the synth-depth design set aside a *sustained group* — VOX, FATHOM,
TONEWHEEL — "driven rather than struck … the same machinery used for
bowed and blown models", with "their own audition gate"
(`docs/superpowers/specs/2026-09-18-synth-depth-design.md:377-395`; a
gate is a listening session — a page of rendered clips with verdict
buttons — whose verdict decides whether the next phase starts at all).
BORE is the fourth member of that group and the first whose sustain is
physics rather than an envelope hold.

For a reader new to this, the mechanism in one paragraph. A clarinet's
reed is a valve: mouth pressure pushes it toward the mouthpiece, the air
that gets past it makes a pressure wave in the pipe, and the wave travels
to the open end, reflects, and comes back to the reed a few milliseconds
later — where it either helps or hinders the valve's next opening. Above a
certain blowing pressure the help wins, the loop's gain exceeds one, and
the note sustains itself for as long as the player blows; the pipe's
length sets the pitch and the reed's law sets the tone. In a real reed,
blowing harder also brightens the sound, because the reed beats against
the mouthpiece; the memoryless table this design starts from buys
threshold and level with BREATH but not brightness (the spike's finding
3, below) — brightness is LIP's, and whether BREATH earns it back is a
gate question. A flute has no reed: a thin jet of air crosses the mouth
hole and flips above or below its sharp edge, and the wave returning up
the pipe is what flips it — the same loop with a different valve. That
loop is the one thing the fleet does not have. Everything in `:synth` is
a strike — a burst into a body that rings and dies (PLUCK, SILK, FORK,
TINES, SKIN, TERRA) — or an oscillator under an envelope (VELVET, FATHOM,
TIDE, GLINT, VOX, TONEWHEEL, SIREN). `RESIN`'s ladder does
self-oscillate, but at its own frequency with no exciter, and the held
path (the code that renders a note for a keyboard instrument, where the
note must loop cleanly for as long as a key is down) caps it out
(`Resin.kt:125-132`). A pressure-controlled valve inside a tuned feedback
loop, sustained by a DC input, would be the first note in the tree
*sustained by a valve* (fleet §4). The tape part is not new: three of
MELLO's five parts are the rack's TAPE section re-derived with the same
1.3 Hz wow constant (`Tape.kt:35`), a fourth is a fourth copy of a hiss
the tree already has three of; only the clonk has no relative.

## The specification, as reviewed

Transcribed faithfully into a worktree with one `.toFloat()` and rendered
(Appendix A), the spec's engine does not work: BASSOON, HECKEL and GUANZI
render NaN at defaults (GUANZI non-finite from 27.5 ms; BASSOON and
HECKEL a constant +0.76 DC through the sustain, overflowing at 893.7 ms of
a 935 ms note), BARI is a limit cycle whose output is 60 % DC (mean +0.56
on a 0.95 peak) with no note at its 55 Hz root, and FLUTE plays an octave
up (867 Hz for f0 = 440, 1.94–1.99× at every TUNE). The causes are the
half-length delays, an unbounded reed, a rectified double reed, no DC
blocker and an unbudgeted loop filter, each measured below. Corrected on
the house's primitives, the same idea runs bounded and in tune to 2 cents
on the cylinder (Appendix B).

The contract half of the document — the `BorePatch`, the macro list, the
wiring into `Patches`/`Presets`/`Velocity`: the shape the app expects of
any engine — is nearly right; the physics half names the right mechanisms
and codes each of them wrong in a way that a single in-tune test would
have caught. Read against the code and, where the reports rendered it,
against the render:

| Spec section | In the repository today | Verdict |
|---|---|---|
| §1 signal-flow diagram (p.1): exciter → bore → "Modal Resonator Formants" → MELLO "in the synthesis loop" → `Punch.applyOversampled` → Snip | the formant block has no code (only HECKEL's one biquad, inside the loop); `applyMelloTape` runs over the finished buffer after the loop (dsp F25); `Punch.applyOversampled` is the drum engines' output stage — a transient shaper that boosts a hit's first two milliseconds and then peak-normalises — and its callers are Thump, Skin, Terra (`Punch.kt:270`; api §5) | the boxes are the right boxes; two are prose-only, the last is the wrong chain |
| §2.1 bore table (p.2): λ = 2L for cone and open pipe, λ = 4L for the closed cylinder, odd series from the closed cylinder | the table is right; the code halves each delay (`rate/(2f0)` and `rate/(4f0)`) so every voice resonates at 2·f0 — FLUTE measured at 1.94–1.99× `frequencyFor` (empirical §3); the p.9 comment that the mouthpiece "reflects opposite" is backwards, it is the open bell that inverts (dsp F4); BASSOON's "500 Hz & 1.5 kHz" vowel is prose only (F6) | **an octave up on every voice** — the most audible thing in this document |
| §2.2A single reed (p.3): `U = u0·(1 − Δp/pc)·√abs(Δp)·sgn(Δp)`, beating shut at `pc` | the textbook quasi-static valve (F7); but the aperture is unbounded for Δp < 0 (F8) and the junction is written the wrong way round (F9; see "The reed"), so BARI parks in a limit cycle whose mean is +0.56 on a 0.95-peak render (empirical §4) | right law, no bounds; replaced by the reflection table — a lookup that says, for each pressure difference across the reed, what fraction of the returning wave bounces back into the pipe, never more than all of it, so the valve can shape the note but cannot add energy without limit |
| §2.2B double reed (p.3): `abs(1 − (Δp/pc)²)` | not implemented — the code squares the single-reed *flow* (`flow*flow*1.5f`), a rectifier (F10); BASSOON and HECKEL sit at pure DC (a constant offset with no wiggle — the speaker cone pushed out and held there, silence) through the sustain and overflow to `inf` in the release — 567 ms into the replica's 600 ms render (dsp F10), 893.7 ms into the probe's 935 ms one (empirical §6) | the same table with a steeper slope, not a new law |
| §2.2C air jet (p.3): delay-then-sigmoid labium | the Verge/Cook shape (F14) on a half-period loop (so 2·f0), a fixed jet delay (nothing overblows), pressure *inside* the sigmoid as an offset, no DC blocker (the very-low high-pass that lets the wave through and drains a loop's constant offset); renders at 867 Hz for f0 = 440 with peak 16 (replica) | STK Flute's structure (STK, the Synthesis ToolKit: Cook and Scavone's open-source C++ library of textbook physical models, the reference this design measures itself against); the Kotlin spike locks it at τ_jet = T/2 (τ_jet is the time the air jet takes to cross the mouth hole, T the note's period — the jet must arrive half a period late for the fundamental to build) within 2.5 cents at 440/880 Hz (Appendix B) |
| §2.3 MELLO 1, the clonk (p.3, p.11): 90 Hz damped sine at t = 0 | no relative anywhere in the tree (fleet §3, the MOTION row); prose τ 18 ms, code 15 ms; hard-cut at 35 ms where the envelope is still 0.097 — a −30 dB step, a click (F24) | the one genuinely new MELLO part; an onset term, not a tape stage |
| MELLO 2, wow and flutter: 0.35 % and 0.10 % of a 5 ms delay at 1.3 / 5.8 Hz | `Tape.kt:35-37, 64-65` is the same construction (WOW_HZ 1.3, FLUTTER_HZ 7.4, a 5 ms base); the spec's depths are 0.25 / 0.32 cents peak — thirty to a hundred times too shallow to hear (F20); measured: pitch unchanged at every MELLO (empirical §8) | the rack's TAPE |
| MELLO 3, saturation: `Dsp.drive(s + 0.05·m·s², 0.35·m)` | `Tape.kt:76` is `Dsp.drive(s + 0.06·d·s², 0.8·d)`; the spec runs it on the un-normalised loop output (FLUTE peaks at 16), so at MELLO 1 the flute is a square wave and rolloff (the frequency below which 85 % of the energy sits, `Features.kt:20`) *rises* 2616 → 7623 Hz (empirical §8) | the rack's TAPE, and never a nonlinearity before the level is decided — a saturator's effect depends entirely on how hard it is hit, so a signal whose level differs 40 dB between voices gets a different effect on every voice |
| MELLO 4, head loss: prose 8.5 kHz, code `expMap(1−m, 4500, 14000)` | `Tape.kt:53` AGE is `expMap(1−age, 3200, 16000)` | the rack's AGE |
| MELLO 5, hiss at −52 dBFS | the fourth hiss in the tree (`Eras.kt`, `TapeWear.kt`, `Vinyl.kt`), each with a cap and a seed rule; the spec seeds it from the *unresolved* `seed` argument (0), so every render's hiss is `Noise(999)` (api §1) | the rack |
| MELLO's dry/wet: `output = input·(1−m) + s·m` | sums the dry signal against a copy delayed 5 ms — a comb with total notches at MELLO 0.5 (F26); every MELLO parameter already scales with `amount` | never |
| §3 macros (p.4): TUNE · BREATH · EMBOUCHURE · VIBRATO · DECAY · MELLO | six is inside the 3–6 budget (`docs/SYNTH_ROADMAP.md:173-174`); TUNE's "A2 to A4" contradicts the roots A1/Bb1/C2/A3/A2; EMBOUCHURE is not a plain word (rule 2); DECAY is note length with a fixed 80 ms release — HOLD in SIREN's words (F31); VIBRATO modulates bore length, a string player's mechanism (F17); BREATH is exact silence at 0 (empirical §5) and, by the spec's own arithmetic, past the closing pressure at 1 with EMBOUCHURE at its default (1.2 > pClosing 1.0; product §3, an inference) — though the probe measured BARI still limit-cycling there (AC 0.15 under DC 0.67) and FLUTE oscillating (empirical §5) | five: TUNE · BREATH · LIP · CHIFF · HOLD, vibrato baked (built in at a fixed musical depth, no knob), MELLO out |
| §3 `BorePatch` (p.4–5) | matches `TerraPatch.kt` (34 lines) to the letter: `init { Patches.validateMacros }`, `ENGINE`, `fromJsonValue` via `Patches.decode` (`Patches.kt:94`, `:74-92`); every API it calls resolves (api §1) | built as written |
| §4 `Bore.kt` loop (p.7–9) | a ring (a circular buffer standing in for the pipe: what goes in comes back out D samples later) read with a two-tap lerp (a straight-line guess between two neighbouring samples, for a delay that is not a whole number of samples) whose `toInt()` makes `frac` negative half of every ring cycle (F3); the bell one-pole (the simplest low-pass filter) inside the loop with its phase unbudgeted — a filter holds the wave back a little, that lengthens the loop, and nothing subtracts it from the delay, so the note reads flat by 8–266 cents, moving with EMBOUCHURE (F2); HECKEL's bulb *inside* the loop is loop gain 1.36 at 140 Hz (F16; peak 35.7 in 0.4 s); a `seed` argument no recipe can store (F19); `defaults + macros` passes unknown keys through where `Fork.settled` drops them (`Fork.kt:260`) | rebuilt on `Strings.Loop`, whose tuning budget — the sum every delay in the loop (the filter's, the DC blocker's, the interpolator's) is charged against, so the ring is shortened by exactly that much and the note lands on pitch (`Strings.kt:161`) — already charges the one-pole's phase (`:110-114`) |
| §4 output (p.10): `normalizeByFold → Punch.applyOversampled(lin(BREATH, 0.2, 0.7)) → limitPeak → fadeTail` | the drum path; every melodic engine ends in the melodic chain — `Tide.bandLimit` (a steep low-pass at 19.5 kHz, `Tide.kt:518`), `Dsp.decimate` (bring the 4× render back down to 44.1 kHz, `Dsp.kt:677`), `Dsp.levelTo(MELODIC_LOUDNESS_TARGET)` (measure the loudness and scale the whole render to the one target every melodic pad shares, so pads from different engines sit at the same level in a kit, `Dsp.kt:636`), `fadeTail` (fade the last few milliseconds to zero so the file ends without a click, `Dsp.kt:710`) — as `Fork.kt:465-468` and `Tide.kt:549` do; measured RMS 0.62–0.74 against the 0.1834 target (empirical §4); no DC removal anywhere, and `inf` becomes a NaN WAV through `normalizeByFold` (F11, F30) | the melodic chain; no PUNCH (`fork spec:305`) |
| §5 `BorePresets` (p.12): twelve, 3/2/2/3/2 per voice | five names exceed 14 characters, two name a maker or a near-miss, counts are uneven against the one-number-per-voice law (`ForkPresetsTest.kt:66-73`; house §2g); five of twelve pass every law | authored by ear after the gate, eight per voice |
| §6.1 `Patches.kt` wiring | the one BORE line is right; the `ChimeraPatch` context line does not exist (`grep -rni chimera` = 0) | one arm at `Patches.kt:52-54` |
| §6.2 `Presets.kt` wiring | `TerraPresets`/`ChimeraPresets` do not exist; `PresetsTest.kt:29, 46-50` fails once `Presets.all()` grows unless updated (api §1) | one arm, one term, the KDoc count (`Presets.kt:8`), the test |
| §6.3 `Velocity.kt` wiring | `macroSpecsFor` is an exhaustive `when` with no `else` (`Velocity.kt:191-218`) — a Kotlin `when` over a sealed type must name every subtype or the build fails, so the compiler, not convention, is what makes this arm mandatory; `brightnessOverride → "BREATH"` is the right door but the house registers it only after a sweep (`ForkTest.kt:328`); the `TerraPatch -> "STRIKE"` context line does not exist | the arm in R1; the override only if a sweep passes — and the spike says it will not |
| §6.4 `Keys.bore` (p.14) | returns a one-shot `Snip` (a clip that plays once and stops) where every held instrument returns a `KeyNote` (the clip plus the frame its sustain loop starts at, so a key held down keeps sounding; `Keys.kt:17`); `coerceIn`s the range where the house `require`s and names it (`Keys.kt:225-227, :297`); re-snaps through TUNE where `Keys.fork` drives the core at `midiHz(midi)` (`Keys.kt:346-357`) | R2, on `resinPad`'s body |
| §7 `BoreTest` (p.14–16), five tests | `org.junit.jupiter` against 77 `kotlin.test` files; determinism with a seed proves purity, not a recipe; `peak() ≤ 0.991` is not guaranteed by `limitPeak(1f)` (`Dsp.kt:654`); the MELLO rolloff test *fails* as written (7623 vs 2616 Hz, the saturation on an un-normalised buffer outweighing the clonk in the 93 ms window); once the level is fixed it would pass for the wrong reason on any render whose first 93 ms holds a clonk (`Features.kt:47`, FFT_SIZE 4096); the odd-harmonic test asserts `isFinite` on two centroids (the spectral centroid: the spectrum's centre of mass in Hz, the usual single number for "how bright"); the velocity test fails in the wrong direction (641 → 43 Hz, DC-driven; empirical §9); no in-tune test | replaced by the claims tests below |
| prose ≠ code, minor (p.2–4, p.9, p.17) | "wavefolding" is given as the reason 4× is needed — no folder exists in the code; the valve is called "cubic" — it is `(1 − x)·√x`; saturation prose 0.04·x², code 0.05 (empirical §12); GUANZI is a double reed in §2.2B and single-reed `flow*1.15f` in the code, so the odd-series cylinder was already single-reed physics in the spec's own Kotlin; the exciter gains 1.35 / 1.5 / 1.15 and the flute's turbulence 0.12 against the reeds' 0.035 are listening values with no source (fleet §3); `scramble` lacks `Fork.kt:249`'s `temperature >= 1f -> base` branch (api §1); the "standard 6-macro contract required by the :synth architecture" is a 3–6 range, not a count (`docs/SYNTH_ROADMAP.md:173`; house §2a); the checklist's "4× decimation anti-aliasing confirmed" has no test behind it | none carried; test 9 is the aliasing test |
| §8 checklist (p.16–17) | names 5 of the ~26 surfaces FORK's introducing commit touched (`git show --stat 82dc292`: 60 files, +8464/−29; house §1) | the registration table below |

### What the spec got right

It is worth being exact about this, because the corrections are many and
the idea is good.

- **`BorePatch` is the house shape.** `init { Patches.validateMacros(this,
  Bore.macrosFor(voice)) }`, `ENGINE`, `fromJsonValue` through
  `Patches.decode` — indistinguishable from `TerraPatch.kt`. Every API the
  engine body calls exists with the signature it assumes:
  `MacroSpec(name, default, neutral)` (`Thump.kt:33`), `Dsp.OnePole.lp`
  (`Dsp.kt:414`), `Dsp.Biquad.bandpass` (`:374`), `Dsp.Noise` (`:171`),
  `Dsp.seedFor` (`:147`), `Dsp.scrambleNear` (`:133`), `Tide.bandLimit`
  (`Tide.kt:533`, `internal`, same module) called at the right rate.
  One `.toFloat()` stood between the transcribed engine and a compile
  (empirical §1, C1).
- **Seeding by the house route.** `Dsp.seedFor("BORE", voice, f0)` is the
  form `Fork.kt:335` uses; the recipe promise is kept on the
  `BorePatch.render()` path.
- **TUNE snaps 24 semitones from a per-voice root**, the shape
  `Resin.frequencyFor` and `Fork.midiFor` take (`Resin.kt:82`,
  `Fork.kt:256-258`); `scramble` is `Dsp.scrambleNear` around a preset,
  FORK's rule (`Fork.kt:246`).
- **4× with `Tide.bandLimit` before decimation** is needed and sufficient
  (F33): the valve's cusp and the beating kink mint aliasing inside the
  loop, and at 176.4 kHz a component must exceed 157 kHz to fold under
  19.5 kHz (a component folds — reappears as a false tone — at the sample
  rate minus its own frequency, so at 176.4 kHz only one above 157 kHz
  lands below 19.5 kHz).
- **The valve formula itself** is the textbook pressure-controlled valve
  (F7), and the sign convention — −1 for the closed cylinder, +1 for the
  cone — gives the odd and the full series once the delays are right (F4).
  The **closed-versus-conical distinction** is the right axis to build on.
- **Turbulence inside the loop, scaled by pressure**, is the physically
  right place, and the one place no engine in the fleet puts noise (fleet
  §3, the TINES/TERRA/VOX row). `Dsp.Noise` is bipolar, so no DC comes
  from it (F18).
- **The vibrato envelope** (5.2 Hz, 60 ms delay, 200 ms rise) is
  deterministic and its default depth, ±7.8 cents, is plausible (F17); only
  the mechanism is a string player's.
- **Cost is fine**: 37–59 ms per rendered second at 4×, about half of
  FORK's 88 (empirical §10); the spike's corrected loop runs at 26–49
  (Appendix B).
- **Two ideas worth keeping.** The pressure-pad clonk is the one MELLO part
  with no relative in the tree; and "the tape runs out at 8 s" is the best
  idea in the spec and needs no DSP — an *unlooped* keygroup zone is
  exactly what `loopStartFrame = 0` already means (`Keys.kt:17`; product
  §2b).
- **The preset ideas** — LOW ROAR, HONK STAB, CHAMBER REED, BREATHY,
  TAPE FLUTE, TEMPLE, OVERBLOWN — are audition-worthy names for sounds a
  beatmaker would reach for; the roster's law problems are length and
  count, not taste.

## The physics, measured

Rendered faithfully — the spec's Kotlin transcribed into a worktree with
one `.toFloat()` and no other change, then probed (empirical report) —
the engine does not work, as the headline above says. Each defect below
is stated, measured, and followed by a plain sentence on why the ear
cares.

### The octave

In code the pipe is a delay line: a queue of D samples that hands back
what went in D samples ago, with a fraction g of it fed back in — the
wave's round trip and its loss, which is the whole "waveguide" idea.
Such a loop rings at every frequency whose period fits the loop a whole
number of times. A single delay line of D samples with feedback +g
resonates at multiples of `rate/D`; with −g at odd multiples of
`rate/(2D)` — the inverted case needs two trips to come back in phase,
which is why a closed pipe's harmonics are the odd ones. The spec sets
`D = rate/(2f0)` for the cone and the open pipe and `rate/(4f0)` for the
closed cylinder (p.7): in both branches the fundamental heard is 2·f0.
The line must hold the **round trip** — `T` for a cone or open pipe (STK
Flute, Saxofony), `T/2` with inversion for a closed cylinder (STK
Clarinet, `rate/f·0.5 − 1.5`). The spec's own table is right; the code
halves each (F1).

| Voice (TUNE 0.5) | `frequencyFor` | spec's D (samples at 176.4 kHz) | loop resonates at | measured (probe a, FFT peak) |
|---|---|---|---|---|
| BARI | 110.00 | 801.8, +0.94 | 220.0 | 331 Hz — a limit cycle, not the loop (see the reed) |
| BASSOON | 116.54 | 756.8, +0.94 | 233.1 | NaN |
| HECKEL | 130.82 | 674.2, +0.94 | 261.6 | NaN |
| FLUTE | 440.00 | 200.5, +0.94 | 880.0 | **867.3 Hz** (1.971×) |
| GUANZI | 220.00 | 200.5, −0.94 | 440.0 (odd series of 440) | NaN |

FLUTE at TUNE 0 / 0.5 / 1 measured 437.2 / 867.3 / 1708.2 Hz for
220 / 440 / 880 — 1.987× / 1.971× / 1.941×, flatter as f0 rises, which is
the next defect. *To the ear:* every note plays an octave above the one
TUNE names, and `Keys.bore`'s roots are wrong by the same octave.

### The reed

The single-reed aperture `(1 − Δp/pc).coerceAtLeast(0)` is `1 + |Δp|/pc`
with no ceiling for Δp < 0, so `|U| ∝ |Δp|^1.5` — a junction that supplies
energy without limit whenever the bore pressure exceeds the mouth (F8).
The double reed is coded as `flow*flow*1.5f`, the square of the
single-reed flow with the sign discarded — a full-wave rectifier (every
negative value flipped positive — the sign that tells the pipe which way
the air is moving is thrown away), so it only ever pushes one way: a DC
pump (F10). And the junction (where the reed meets the pipe) is explicit
(`deltaP = 1.2·P − boreReflection`) where the physics is implicit — the
spec computes the pressure across the reed from the returning wave alone,
but the real pressure also depends on the flow the reed is about to let
through, so the equation contains its own answer — and the +1 loop finds
a bad operating point on the √ cusp (the sharp corner of the square-root
curve at zero pressure, where a tiny change in pressure is a huge change
in flow), where the valve's small-signal gain (how much a tiny wiggle in
pressure is amplified into flow on each pass) is about 17 — against a
pipe that can only lose 6 % per trip, so the valve, not the pipe, decides
what happens (F9). The result is a limit cycle: a self-repeating
oscillation of the valve itself, not the pipe's note.

| Voice, corner | replica (0.4–0.6 s) | probe (raw oversampled buffer) |
|---|---|---|
| GUANZI defaults | diverges to `inf` at **39.5 ms** (37.5 at BREATH 1/EMB 0; 40.6 at BREATH 0.3/EMB 1) | > 1 at 3.7 ms, > 10³ at 13.2 ms, non-finite from 27.5 ms; 160,153 of 165,007 samples non-finite |
| BASSOON defaults | diverges at **567 ms** of 600 | sustain a *constant* 0.757–0.760 (pure DC, no oscillation); > 1 at 872 ms, non-finite from 893.7 ms (release starts 855 ms) |
| HECKEL defaults | diverges at **566 ms** | same shape; non-finite from 893.7 ms |
| BARI defaults | bounded, peak 1.04, **DC 0.66 against 0.23 rms AC**; peaks at 325 / 332 / 541 / 769 / 984 Hz for f0 = 110 | raw mean +0.618, AC rms 0.236 (DC 2.6× the AC); output mean +0.56 on a 0.95 peak; the classifier (`Classifier.classify`, `Classifier.kt:87` — the app's listener that files any clip as KICK, SNARE, PERC, TONAL, LOOP and so on, which decides the pad's group and whether other pads choke it) files PERC |
| HECKEL BREATH 1 / EMB 0 | bounded, peak **35.7** (the bulb, F16) | peak 34.95 at 934 ms |

Downstream nothing guards: `Dsp.normalizeByFold` scans `if (a > peak)`,
so an `inf` sets the gain to 0 and `inf·0 = NaN` (F11's reading of
`Dsp.kt`; the probe's stage-by-stage attribution matched — the count of
non-finite samples passed unchanged through `bandLimit` and
`normalizeByFold` and only *shrank* at `decimate`). Three of five voices
reach the WAV as NaN. *To the ear:* BASSOON, HECKEL and GUANZI are a
silent or corrupt file; BARI is a buzz with a thump of DC under it whose
pitch hops by octaves and fifths across BREATH and EMBOUCHURE (probe c:
217 / 74 / 55 / 110 Hz at four corners).

### DC

Mouth pressure is a DC input into a loop whose DC gain, with net +0.94,
is `1/(1 − 0.94) = 16.7` (each round trip adds 0.94 of the last, and 1 +
0.94 + 0.94² + … sums to 1/(1 − 0.94)); the spec removes DC nowhere
(F30). BARI's output mean is +0.56 — the DC gate every preset test
applies (`|dc| < 0.05`, `ForkPresetsTest.kt:32`) fails by an order of
magnitude, and headroom is lost to an offset no speaker reproduces.
FORK's rule is "the DC goes twice" (`fork spec:251-255`): an in-loop DC
blocker on the +1 loops (the cone and the flute, whose reflection does
not invert) — because the loop multiplies any offset by 16.7, so it must
be removed where it is made — *and* a mean subtraction plus a 20 Hz
one-pole high-pass before `levelTo`, because the exciter's own offset
still leaks past the blocker's slow corner and `levelTo` would otherwise
spend headroom on it. *To the ear:* a pad that pops on every trigger and
sits several dB under its apparent level.

### The loop filter's unbudgeted phase

The bell one-pole sits inside the feedback and its phase delay at f0
(how many samples the filter holds the fundamental back —
`τ = atan2(r·sin w, 1 − r·cos w)/w`) is never charged to the delay length
(F2). Against the spec's own D (cents = `1200·log2((D + τ)/D)`):

| Voice, semitone | D | EMB 0 | EMB 0.5 | EMB 1 |
|---|---|---|---|---|
| BARI 12 (110 Hz) | 801.8 | 15.1 smp, 32 c | 7.7, 17 c | 3.8, 8 c |
| BASSOON 24 (233 Hz) | 378.4 | 19.4, 86 c | 10.1, 46 c | 5.1, 23 c |
| HECKEL 24 (262 Hz) | 337.1 | 24.6, **122 c** | 12.5, 63 c | 6.2, 32 c |
| FLUTE 24 (880 Hz) | 100.2 | 6.4, 108 c | 3.6, 60 c | 1.9, 32 c |
| GUANZI 24 (440 Hz) | 100.2 | 16.6, **266 c** | 8.9, 147 c | 4.6, 78 c |

Because `bellCutoff = expMap(EMBOUCHURE, …)`, EMBOUCHURE is a pitch knob:
measured on FLUTE, 864.7 → 882 Hz across its travel (probe c, ~35 cents on
the *spec's* short loop; the numbers halve in a corrected full-period loop
and are still 4–130 cents). This is the defect `Strings.tune` exists for
(`Strings.kt:66-183`: "tens of cents flat and growing worse at higher
TUNE"), and its remedy — charge the one-pole's closed-form phase, the DC
blocker's lead (a high-pass *advances* the wave slightly — a negative
delay, a lead — so the loop owes it the other way) and the interpolator's
half sample against one exact budget, then split into an integer delay
and a first-order allpass (a filter that passes every frequency at the
same level and only delays it, tuned to supply the fraction of a sample
the whole-number ring cannot: a loop of 391.6 samples is a ring of 391
plus an allpass worth 0.6) (`:110-114`, `:142-146`, `:161`, `:178`) — is
the model. *To the ear:* the brightness knob detunes the note by up to a
whole tone.

### The correct formulation

Julius Smith's (Stanford CCRMA, the standard reference for waveguide
synthesis; the STK models are his and Cook's) reed reflection table in
the house's own idiom — a textbook, known-stable loop the way
`Strings.Loop` is:

```
ret  = LP_bell( s · 0.95 · line[n − D_exact] )     s = −1 closed cylinder, +1 cone / open pipe (+ DC blocker on the +1 loops)
Δ    = ret − p_m·(1 + turb·noise)                  p_m from BREATH; turbulence multiplicative (STK: noiseGain · breath)
r    = clamp(offset + slope·Δ, −1, 1)              passive by construction: |r| ≤ 1 — the valve never sends back
                                                   more than it received, so nothing in the loop can grow without bound
out  = p_m + Δ·r                                   → line
```

with `D_exact = (rate/f0)·{1, ½} − τ_LP(f0) − τ_DC(f0) − 0.5`, the
`Strings.tune` budget with a round-trip factor. The double reed is the
same table with a steeper slope and earlier beating (Almeida, Vergez &
Caussé, *J. Acoust. Soc. Am.* 2007, who measured a real double reed's
quasi-static curve as the single reed's with a steeper closing side), not
the spec's squared law. The flute is STK Flute's structure: a full-period
+1 loop with a DC blocker; a second, short delay for the air jet's travel
across the mouth hole (`τ_jet = T/2`), deflected by the wave returning up
the pipe; a labium (the sharp edge the jet strikes) modelled as a
soft-clipping `tanh`, so the jet's push saturates instead of growing
without limit; LIP sets where the jet rests against that edge, and
blowing harder scales the jet's *input* rather than shifting it (the spec
put pressure inside the sigmoid as an offset, so blowing harder flattened
the curve — less tone, more DC); and half the wave reflects at each end.

**The Python replica, qualified.** The DSP review's `bore_sim2.py` ran
this corrected loop and reported "±3 cents at semitones 0/12/24" for the
three reed voices and a 2nd harmonic 39.8 dB down for GUANZI. Re-run
twice independently for this document (the script is kept in the Phase-0
record, `../plans/2026-09-28-bore-phase-0-spike.md`; the numbers are
Appendix A10), that
headline is literally what the peak search prints and hollow for the
cone: BARI at semitones 0 and 12 has AC RMS (the average size of the
wiggling part — the tone) 0.0044 / 0.0057 against DC (the constant part)
0.0585 — an offset thirteen times the tone (BASSOON 0.0040 / 0.0081,
HECKEL 0.0047 / 0.0109) — the reed is not speaking, and the ±3-cent peak
is turbulence ringing the passive bore; only semitone 24 speaks (AC
0.069), and GUANZI speaks at all three (AC 0.31–0.42). The replica's
flute lands at 35.35 Hz with DC −0.375 — "did not lock". The threshold
probe (`bore_thresh.py`, also in the Phase-0 record; its table is Appendix
A11; turbulence off, a 1e-3 kick, so self-oscillation is told apart from
noise ringing the bore) then showed the cone reeds mode-hopping —
jumping to a different resonance of the pipe than the one TUNE asked for
— where they do speak: BARI 110 Hz at EMB 0.5 speaks only at BREATH 0.8,
at 133.9 Hz (+340 cents); at EMB 1 at BREATH 0.65, at 251.9 Hz; BARI 55
Hz never lands on f0; only 220 Hz holds within ±5 cents (−4.2 to +1.5
across its speaking corners). The closed cylinder holds within ±2 cents
across its whole window at 110 / 220 / 440 Hz. **A pitch test alone
passes on a reed that does not speak** — BARI semitone 12 at BREATH
0.2–0.5 reads 109.1–109.8 Hz (−3 to −14 cents) with kicked AC 0.0000.
That finding shapes Phase 0's gate below.

**The Kotlin spike (Phase 0, done).** Built from the house's primitives —
a ring with the Jaffe–Smith allpass (the fractional-delay allpass from
Jaffe and Smith's 1983 extension of the plucked-string algorithm, the one
`Strings.Loop` already uses), `Strings.tune`'s `filterDelay` and
`dcDelay` verbatim, the reflection table, the melodic output chain — and
measured at BREATH 0.65 / LIP 0.5 (Appendix B has every table):

| Shape | f0 | fundamental (cents) | strongest peak | odd/even | h2, h3 (dB re h1) | raw DC / AC | non-finite |
|---|---|---|---|---|---|---|---|
| CYLINDER | 110 / 220 / 440 | +0.7 / +1.3 / +1.9 | f0 | +42.6 / +40.6 / +39.1 dB | −48.6, −9.9 / −44.8, −11.7 / −41.9, −15.6 | +0.017 / 0.59 · +0.018 / 0.57 · +0.020 / 0.51 | 0 |
| CONE, blocker f0/25 | 55 / 110 / 220 | −4.3 / −5.8 / −4.4 | f0 (Pitch.detect 54.78 / 109.70 / 219.40) | +6.8 / +5.1 / +2.3 dB (2 Hz rows) | −10.1, −18.7 / −7.9, −22.5 / −9.2, −21.6 | +0.059 / 0.15 · +0.060 / 0.33 · +0.050 / 0.55 | 0 |
| FLUTE, jet T/2 | 220 / 440 / 880 | −0.1 / +1.2 / +2.5 | 3rd mode at 220 (h3 +3.9 dB); f0 at 440 / 880 | +40.1 / +36.7 / +32.9 dB | −44.2, +3.9 / −42.8, −10.0 / −37.3, −11.2 | +0.027 / 1.24 · +0.026 / 1.28 · +0.031 / 1.22 | 0 |

*odd/even:* the odd harmonics' energy over the even harmonics', in dB —
a hollow, clarinet-like tone is a large positive number; *h2, h3:* the
second and third harmonics relative to the fundamental h1; *raw DC / AC:*
the constant offset and the tone's size before the output chain.

Zero non-finite samples in about 220 renders; Loudness 0.1832–0.1834 at
every oscillating corner; |final mean| ≤ 0.0094. The cylinder is in tune
within 2 cents at defaults and at all 13 of its 18 oscillating corners
(+0.2 to +2.2 cents) with a 39–43 dB odd/even ratio; the cone speaks at
f0 at 55 / 110 / 220 with a full series and sits a consistent ~5 cents
flat; the flute locks at τ_jet = T/2 (0.32 and 0.40 did not: +74 to +102
cents, upper modes dominant) within 2.5 cents at 440 and 880, with its
low register 3rd-mode-dominant at 220 Hz. Cost 26–49 ms per rendered
second warm (FLUTE 36–60).

Three findings from the spike that no report had, each a design fact:

1. **The cone's DC-blocker corner (its cutoff frequency — below it the
   offset drains, above it the wave passes; everywhere else in this
   document "corner" means an extreme of the macro grid) is a real
   conflict.** The blocker's lead is different at every frequency and
   the budget subtracts only its value at f0, so the harmonics see a
   loop that is the wrong length for them: at 20 Hz (the replica's
   value) the mismatch is 178 samples at 55 Hz, and the 2nd mode lands
   68 cents flat (37 at 110 Hz): the octave wins and the reed locks on
   mode 2 (iteration 1: global peak 105.7 Hz for f0 55, 215.5 for 110).
   Either this 20 Hz blocker or the slope-as-LIP parametrisation
   (finding 2) — both of which the replica shares — can account for the
   replica's non-speaking cone; the spike changed both before the cone
   spoke at f0, so which one it was is not separated. An earlier draft
   of this document guessed that the blocker's corner should track the
   key; the spike, which held the bell at 2500 Hz throughout, shows the
   blocker's corner is the cause, not the cure, and that guess is
   withdrawn. At 2 Hz — `Strings.kt:131`'s own reasoning, "2 Hz (not 20)
   keeps that lead under a degree" — the modes are harmonic (2nd mode
   −1.0 / −2.9 / −1.3 cents) but the reed's operating point relaxes at
   ~21 Hz (sub-40 Hz energy at the top of the spectrum, peak 0.95 after
   `levelTo`, `Pitch.detect` null). No blocker at all parks the loop at
   DC +0.74. `f0/25` (2.2 / 4.4 / 8.8 Hz) is the measured compromise:
   2nd mode within −4.9 cents, sub-audio ≤ −25 dB, `Pitch.detect`
   agreeing. It is a compromise, not a solution; the honest alternative
   is a different cone reduction — the apex allpass the DSP review names
   (F5), or a two-segment loop with inverting ends — and it is Phase 0's
   open question. *To the ear:* at 20 Hz the sax's second harmonic sits
   a third of a semitone flat, sour rather than saxophone; at 2 Hz a
   slow chug under the note.
2. **LIP as the table's slope is a pressure gain, not a stiffness
   axis.** `r = offset + slope·(ret − p_m)` enters slope and p_m as one
   product, so doubling the slope and halving the pressure gives the
   same table — LIP and BREATH were one knob wearing two names, which is
   why the corners choked or starved instead of changing tone; with the
   spec's (and STK's) parametrisation the spike's first iteration
   measured the corners either choking (r clamped at +1 once
   `|slope|·p_m > 0.3`: BREATH ≥ 0.85 at LIP ≥ 0.5 rendered AC 0.0000)
   or starving (LIP 0 at BREATH ≤ 0.65: AC ≤ 0.0008) — 6 of 18 cylinder
   corners and 5 of 18 cone corners spoke. The mapping that works: LIP
   is the table's **offset** (the reed's rest reflection, 0.5 → 0.85, a
   looser lip more open), slope fixed at −0.3, and BREATH is a **share
   of the closing pressure** `(1 − offset)/|slope|` (0.4 → 0.97, so
   BREATH 1 sits just under extinction at every LIP). Under it 13 of 18
   cylinder corners and 11 of 15 cone corners speak
   (`BoreSpike.kt:52-62`).
3. **BREATH does not brighten the cylinder.** Centroid once playing is
   261.7 / 265.2 / 259.5 / 252.5 Hz for BREATH 0.5 → 1.0 — flat, not
   monotonic; BREATH buys threshold and level (raw AC 0.51 → 0.68), which
   `levelTo` then takes back. LIP darkens monotonically (282.8 → 250.4 Hz,
   rolloff 1540 → 668 Hz). The `brightnessOverride → "BREATH"` line the
   spec asks for should be planned as failing its sweep; see "Macros".

**Threshold and extinction, as a formula and then measured.** At rest `Δ
= −p_m`, the junction's small-signal gain is `offset + 2|slope|·p_m`
(the table's rest reflection plus its slope times the two-way pressure
swing), and the loop speaks when `g·(offset + 2|slope|·p_m) > 1`: `p_thr
= (1/g − offset)/(2|slope|)`; the reflection clamps at +1 (the reed
beaten shut, nothing starts) at `p_ext = (1 − offset)/|slope|`. With `g
= 0.95`, `offset 0.7`: |slope| 0.325 gives 0.54 and 0.92, |slope| 0.6
gives 0.29 and 0.50 — a window about 0.3 of the raw pressure axis that
moves with the slope, which is what the threshold probe measured
(cylinder 220 Hz at EMB 0.5: silent at BREATH 0.2–0.5, speaking at
0.65–0.8, silent again at 1.0; at EMB 1 the window is 0.4–0.65; at EMB 0
only 1.0 speaks). In the spike's share form the predicted threshold at
LIP 0.5 (offset 0.675) is 0.58 of closure and the measured first
oscillation is 0.60 (BREATH 0.35). So BREATH cannot map linearly onto
pressure: it maps onto `[p_thr(LIP) + m, p_ext(LIP) − m]` with both
edges measured per voice, or playability rule 3 ("sweet spots are wide
by construction", `docs/SYNTH_ROADMAP.md:177-178`) fails at both ends.
The spec's own BREATH is exact silence at 0 on every voice (empirical
§5); at 1, the product report's reading of the code predicted a
beaten-shut reed at the default EMBOUCHURE (1.2·BREATH against `pClosing
= 1.0`; product §3, an inference), but the probe measured BARI and FLUTE
still oscillating there (probe c: BARI AC 0.15 under DC 0.67 at 109 Hz,
FLUTE AC 0.76 at 865 Hz) because the returning wave keeps Δp below
closure — the top-of-knob failure in the spec's engine is the DC-driven
pitch hop and the NaN voices, not silence; in the corrected table model
it is extinction, the `r = +1` clamp above. *To the ear:* without the
window, the top of the breath knob goes silent and so do soft velocity
layers.

**The residual pull.** The table is memoryless, but the one-pole's phase
delay falls toward its corner and the blocker's lead is largest at low
notes, so the harmonics see a slightly different loop length than f0
does, and a valve-driven loop settles where all of its harmonics
together agree on a round trip, not where the fundamental alone would
ring — and since the filter delays each harmonic differently, that is a
few cents off the pipe's own pitch, and it moves as BREATH and LIP
change which harmonics are strong. The replica shows the residual moving
with BREATH and LIP (cylinder 440 Hz: 0.0 to +1.5 cents across its
speaking corners; BARI 220 at EMB 1: −1.7 to −4.2); the spike shows the
cylinder +0.4 to +1.3 cents across its whole BREATH range (a 0.9-cent
spread) and the cone −8.0 to +2.3 across its f0/25 corners around a ~5
cent flat centre. `Dsp.Ladder`'s precedent applies — measure the shift
and pin it per voice rather than publish a formula (`Dsp.kt:278-283`: "a
published polynomial fix would be a tuned constant shipped unmeasured")
— and `TuningAccuracyTest`'s own split between its 5-cent and
quarter-tone sweeps (`TuningAccuracyTest.kt:83`, `:142`): hold 5 cents
at defaults, print and bound the corners.

**Simplifications the model makes, stated.** No truncated cone: the cone
is a full-period +1 loop, exactly harmonic — a Benade-compensated cone
(Benade's rule: a real saxophone stays in tune because the mouthpiece's
volume stands in for the missing tip of the cone, so the model may treat
the cone as complete) — and the apex allpass (corner ≈ c/2πx0, ~500 Hz
for a 0.1 m truncation) is optional and, if added, budgeted like SITAR's
stiffness allpass (`Strings.kt:116-124`). No toneholes: TUNE is bore
length, so there is no tonehole-lattice cutoff (a real row of open holes
acts as a high-pass on the bore; here every note is the same pipe cut to
length) and every note has the same fingering. No register hole, so no
overblowing in round one — mode selection is not yet under control in
the first register (the cone's blocker conflict above; the flute's
3rd-mode low register). A memoryless reed: no reed resonance, no squeak,
a single-mode reed. One one-pole standing in for wall loss and bell
radiation together. Quasi-static Bernoulli flow (the flow through the
reed follows the pressure instantly, with no inertia). No vocal tract.

## Against the fleet

| Engine or effect | Why BORE is not it |
|---|---|
| **GLINT** (REED / BOTTLE / KAZOO) | Phase distortion: "the formant is generated rather than carved", a voice is a window function, and the engine's promises — click-free sweeps, no feedback path — are all properties of having no loop (`Glint.kt:10-29`; fleet §3). A blown voice is nothing but the loop. GLINT's REED is a boing, by its own audition. |
| **VOX** (CHOIR / GHOST / THROAT / WRAITH) | The fleet's continuous-excitation melodic engine: a glottal pulse with breath puffs into five formants, a BREATH macro meaning aspiration mix (`Vox.kt:113-120, :145`), delayed vibrato, a hold on DECAY. The sample-CD *breathy flute* — noise through a narrow resonance on a held tone — is 80 % built between THROAT's whistle and WRAITH's breath bands; that is the stopgap if the product wants only "breathy, flutey". VOX cannot do what defines a bore: noise *inside* the loop and filtered by it, pitch owned by the loop, a reed beating shut, a register. |
| **PLUCK / SILK + `Strings`** | "A burst of noise in a tuned feedback loop *is* a plucked string." `Strings.tune` is the tuning budget the spec lacks (`Strings.kt:161`), `Strings.Loop` the ring, allpass, loop one-pole and DC blocker the spec re-implemented without it (`:389-434`), `retune` the click-free pitch move (`:450`). BORE's bore *is* `Strings.Loop` with a different exciter — its third client and the first not plucked; what it lacks is a junction hook, a round-trip factor and an independent blocker corner (three small additions, "Architecture"). SILK's precedent: share the toolkit, take a new name. |
| **Modes** | Per-mode resonators specified by t60 (the seconds a ringing mode takes to fall 60 dB — the house specifies decay in time, never by Q, because a fixed Q ties ring time to pitch), `Modes.fixed(hz, gain, t60)` for an absolute body resonance (`Modes.kt:202`), driven through `Strings.bodyRing` (`Strings.kt:642`). The spec's "Modal Resonator Formants", BASSOON's vowel and HECKEL's 140 Hz bulb are rows through that call, outside the loop where a formant belongs — and `Modes.kt:121` forbids recalled numbers, which is what the spec's are. |
| **TAPE, TapeWear, Eras, VINYL** (the rack) | TAPE is wow 1.3 Hz + flutter 7.4 Hz as a modulated fractional delay, a tanh drive with even-harmonic asymmetry, an AGE head loss (`Tape.kt:35-53, :64-77`); TapeWear, the `tape` era and VINYL each carry a capped, seeded hiss. MELLO's parts 2–5 are these with different constants. |
| **FxChain and the CRUNCH rule** | The rack's order is fixed — VINYL → TAPE → … → ECHO (`FxChain.kt:11`) — and the chain serialises beside the WAV in `kit.json`. An in-engine MELLO puts tape before EQ and outside the recipe's rack section, so a MELLO'd pad could not be un-taped or re-ordered like every other pad. "The engine renders dry and the rack does what it already does" — applied to WOBBLE for FORK (`fork spec:88`) and to ECHO for SIREN (`siren spec`, ECHO row). |
| **SIREN** (HOLD, LOOP, `landingChain`) | The precedent for everything the spec's DECAY is trying to be: a gated, held note ("how long the button is down", `Siren.kt:75-90`), a LOOP top step for the SURFACE (the phone screen where a LOOP pad plays under a finger, pitched by where the finger sits — `SynthScreen.kt:228`; `Siren.kt:36-38`, `:339-451`), an honest LOOP/TONAL predictor (`:176-179`), and the one engine that bakes a rack effect into a recipe (`landingChain` — the effects a pad arrives with when SEND TO PAD lands it, written into its recipe so it regenerates with them; `Siren.kt:128`). |
| **RESIN held** (`Keys.resinPad`, `Dsp.Ladder`) | The held-note instrument: `planLoop` rounding K periods to whole frames, `requireSeam` — the seam is the join where a loop's end meets its start; `seamError` measures how much the waveform disagrees across it, and the bar 1e-3 refuses any loop that would click every pass (`Keys.kt:188-206`) — one retry with a longer settle, never a crossfade (blending the two ends to hide a bad join, which smears the tone) (`Keys.kt:169-180, :253-257`). The ladder past r = 4 is the fleet's only self-oscillator, with no exciter and a pitch of its own that the held path deliberately avoids (`Resin.kt:125-132`). BORE would be the first `Strings`-family loop whose round-trip gain exceeds one, held there by a DC input. |
| **Keys** (organ, `ep`, `fork`) | Sustain is "an exact whole number of waveform periods … the seam is mathematics, not scissors" (`Keys.kt:27-31`), and the organ renders with vibrato and rotor off because "a rotor a third of the way through a turn at the seam is a click" (`:79-86`). BORE's turbulence and vibrato are the same problem, answered the same way below. |
| **TIDE** (`bandLimit`, CROSS) | The 19.5 kHz eighth-order filter before decimation and the 45 dB between-harmonics bar (`Tide.kt:518-537`; `ForkTest.kt:87`). CROSS is the fleet's only nonlinear feedback and it bends a phase accumulator — pitch still owned by the accumulator (a phase accumulator is a counter stepping through one cycle of a waveform at the note's rate — an ordinary oscillator; CROSS distorts its output but the counter still owns the pitch). TIDE's method for a loop-gain ceiling (map it, measure between harmonics, pick the rough side on purpose) is the method LIP's ceiling wants. |
| **TERRA** (the other external physical spec) | Its topology table became per-voice numbers on two functions, its Helmholtz coupling a `Dsp.Biquad.bandpass` into tanh, and a voice with no mechanism of its own was dropped (`Terra.kt:12-44`). The method for BORE's five voices: two loop shapes, per-voice numbers, and HECKEL dropped the way MICRO_FLOCK was (MICRO_FLOCK: a third exciter in TERRA's spec that no preset used, deferred indefinitely — `Terra.kt:31`). TERRA's *output* path is the drum path BORE must not copy. |
| **FORK** | The shape of this document, and three rules it settled that apply verbatim: no PUNCH on a melodic engine (`fork spec:305`), `levelTo` on pads and velocity-true layers on keys (`:260-266`), and a velocity macro registered only after a monotonic sweep (`:527-532`). FORK's one new DSP was a nonlinearity *after* the resonator; BORE's is one *inside* the loop. |

So BORE is worth building for exactly one thing none of that does: **a
pressure-controlled valve inside a tuned feedback loop, sustaining a note
from DC pressure** — where the pitch, the speaking threshold, the
brightness and the register all fall out of one nonlinear limit cycle.
BREATH as a physical dynamic, LIP as a change of the valve law rather
than a filter move, the reed's own threshold as the soft layer: "the same
effect, from the physics", FORK's argument (`fork spec:84`).

## The tape question

MELLO's five parts against `Tape.kt`, one by one, with what was measured:

| MELLO part | Spec's numbers | `Tape.kt` / the rack | Measured | Verdict |
|---|---|---|---|---|
| 1 · clonk | `sin(2π·90t)·e^{−t/τ}·0.35·m`, τ 15 ms (prose 18), cut at 35 ms | nothing — no rack section *injects* an onset; the engine-side relatives are fixed "what the mechanism does" onsets (TONEWHEEL's key click, `Tonewheel.kt:37`; TIDE's CLICK; TERRA's CLACK) | at the cut the envelope is `e^{−35/15} = 0.097`: a −30 dB step (F24); it lands at t = 0 while the note is at 0 (40 ms attack) — a key thump before the tape speaks, which is what a tape-replay keyboard does | the only new part; an onset term inside CHIFF, run until its envelope is under 1e-3 (−60 dB, the house's "inaudible" bar — the same point a mode's t60 is measured to), about 105 ms, or windowed with `Fork.striker`'s raised cosine (`Fork.kt:445-451`, the `STRIKER_FADE_MS` fade at `:197`), never cut at −30 dB |
| 2 · wow and flutter | 1.3 Hz × 0.35 % and 5.8 Hz × 0.10 % **of a 5 ms delay** | `WOW_HZ 1.3`, `FLUTTER_HZ 7.4`, depths 0.55 / 0.12 of the 5 ms base at full (`Tape.kt:35-37, :51-52`) — tens of cents | pitch deviation is the time derivative of the delay — pitch shifts by how *fast* the delay is changing, not how far — so a ±0.0175 ms swing at 1.3 Hz moves the pitch by only 0.25 cents (flutter 0.32; F20); the probe measured 864.7 Hz at every MELLO | the rack's WOBBLE; the house's own 0.05 is already −8.1/+3.3 cents (`SynthKits.kt:22-30`) |
| 3 · saturation | `Dsp.drive(s + 0.05·m·s², 0.35·m)` on the raw loop buffer | `Dsp.drive(s + 0.06·d·s², 0.8·d)` peak-matched (`Tape.kt:76`) | FLUTE's raw peak is 16, so at MELLO 1 (`g = 3.1`) `tanh(±50)` is a square wave: **rolloff 2616 → 7623 Hz, centroid +19 %** — the spec's own darkening test fails (empirical §8); a reed at raw peak ~1 is nearly clean, a 40 dB spread across voices (F21) | the rack's DRIVE, on a levelled signal |
| 4 · head loss | prose 8.5 kHz; code `expMap(1−m, 4500, 14000)` | AGE: `expMap(1−age, 3200, 16000)` (`Tape.kt:53`) | — | the rack's AGE |
| 5 · hiss | `0.0025·m`, "−52 dBFS" — then mixed by `amount` again, so it scales with `amount²` | `Eras.kt` −54 dB, `TapeWear.kt` ≤ −48, `Vinyl.kt` ≤ −48, each seeded once ("a tape has one noise floor") | −52 dBFS peak / −57 RMS relative to an *un-normalised* buffer; seeded from the raw `seed` (0) so every note shares `Noise(999)` | the rack |
| the mix | `output = input·(1−m) + s·m` | TAPE never mixes dry — WOBBLE 0 is a constant delay | a comb filter — adding a signal to a copy of itself delayed 5 ms cancels every frequency whose half-period is 5 ms (100 Hz, 300 Hz, 500 Hz …), a row of notches like a comb's teeth — dry against a 5 ms-delayed copy, total notches at odd multiples of 100 Hz at MELLO 0.5, 3–5 dB ripples every 200 Hz at the presets' 0.75–0.95 (F26) | removed; every parameter already scales with `amount` |

"In-line, not post-processing" is false as coded: `applyMelloTape(rawBuffer,
…)` runs over the finished buffer after the synthesis loop (F25). Its one
technical difference from `FxChain`'s `tape` section is that it runs at
176.4 kHz before decimation, so its tanh aliases less than the rack's
44.1 kHz one — genuine, and small; TAPE's drive is mild and the house
accepted TAPE at the snip rate (fleet §3, the TAPE row). The CRUNCH rule
has been applied twice to exactly this shape (`fork spec:88`; `siren
spec`, ECHO row), and it settles it: **BORE renders dry.**

How the tape-flute story is told instead — SIREN's door, exactly.
`Siren.landingChain(macros)` bakes the rack's ECHO into a one-shot's recipe
and lands a LOOP dry, "a baked tail smears across the seam" (`Siren.kt:120-129`);
`SynthKits.siren()` and SEND TO PAD both consume it (`SynthKits.kt:213-215`;
`SynthScreen.kt:498`). BORE's is
`fun landingChain(macros): FxChain? = if (isLoop(hold)) null else
FxChain().withSection("tape", LANDING_TAPE)`. A LOOP lands dry for the
same reason SIREN's does: wow at 1.3 Hz cannot ride a 2 s wrap unless the
loop holds whole wow periods, which is engine work (product §2b). The
testkit kit carries the same chains, so the MPC hears the story too; and
since `SynthKits.melodicMotion` (the WOBBLE 0.05 every older melodic pad
carries, `SynthKits.kt:35-37`) is applied only at `:48, :55, :63` — the
FORK and SIREN kits, whose shape `bore()` copies, land with their own
chain or none — there is no second tape on a BORE pad.

`LANDING_TAPE`'s numbers are a gate question, not a default to guess:
Tape's depths are linear in WOBBLE (`Tape.kt:51-52`), so the measured
−8.1/+3.3 cents at 0.05 becomes about −16/+7 at 0.1 and −32/+13 at 0.2.
The audition A/Bs the flute dry, through 0.1 / DRIVE 0.25 / AGE 0.5, and
through 0.2 / 0.3 / 0.6, level-matched by `AuditionLevel`, against the
spec's MELLO 0.85 rendered on the corrected engine as the fourth clip
(the product report's question 6). A tie or a preference for the rack
ends the question; a clear preference for the in-engine stage moves
parts 2–5 back into the engine as a `landingChain`-free macro in R2, with
the clonk already engine-native either way. One caveat carried over: a
recipe that carries FX cannot re-render velocity layers through
`atVelocity` — `canUseAtVelocity` requires `fx == null` (`Velocity.kt:164-165`)
— and falls back to `soften` (a peak-matched low-pass that only darkens
the finished clip, `Velocity.kt:26`), because a velocity layer is a fresh
render from the recipe's macros, and the rack's effects are applied at
landing rather than inside `render`, so a re-render would come back
without them; a keys instrument therefore renders dry and takes its tape
on hardware or in the sidecar.

**The 8 seconds.** "The tape runs out" is the best idea in the spec: in a
keygroup it is an *unlooped* zone, which `loopStartFrame = 0` already
means (`Keys.kt:17`) — the Mellotron's mechanism, literally, for no DSP.
Its cost is size: at the suite's PCM_24 default an 8 s mono zone is
`8 × 44100 × 3 = 1.06 MB`, 9.5 MB for nine zones, 19 MB with two layers,
against the RESIN pad's 3.5 MB (product §2b). On a pad, HOLD tops at 4 s
like SIREN's (`Siren.kt:76`); the 8 s unlooped zone is an instrument-sheet
option in R2 ("RUNS OUT"), not the default.

**Verdict.** Parts 2–5 are the rack's; the clonk is CHIFF's top; the
tape-flute preset is a recipe with a `tape` section; MELLO the macro and
the word go.

## The name

**BORE.** A bore is the pipe's interior — the thing whose shape decides
cone-versus-cylinder, the axis this engine is built on — and a
one-syllable mechanism word like FORK and TONEWHEEL. It has no collision
in the tree. Its risk is the pun: "bore" means dull, and on the LCD
beside preset names and in a toast ("BORE renders…") it can read as a
verdict (product §5). **WIND** is the alternative: the family word,
generic, plain, covering flute and reeds alike, with no pun and no
collision; CANE (the reed's material, evocative like VELVET and SILK)
excludes the flute; REED collides with `GlintVoice.REED`
(`Glint.kt:10`); AIR is the rack's EQ band; BREATH is better spent on
the macro (the FORK/BARK argument — FORK's rule that the best macro word
must not be spent on the engine's name, `fork spec:104-107`). This
document keeps BORE as the working name because it names the mechanism
and because nothing else in the design depends on the string; the
owner's call is the last section, and a rename costs one constant and
one enum entry.

**The rule, applied.** "Sound yes, names never" (`docs/SYNTH_ROADMAP.md:27-47`)
covers engine and voice names, preset names, descriptions and commit
messages; a document stating the rule may name the machines, and this one
does. The spec's title says "Mellotron Stage Module", its section is
"Integrated Mellotron Emulation (MELLO)", its macro is MELLO and a preset
is MELLO BARI 68: a registered trademark and its five-letter near-miss on
a panel and in a listbox — the shape `docs/SYNTH_UPGRADE.md:76-78` calls
"the violation with a wink". HECKEL is Wilhelm Heckel GmbH, the bassoon
house; the heckelphone is named for the firm the way "the Rhodes" is, and
HECKEL the voice and GHOST HECKEL the preset put a maker on the panel.
LIEBESFUSS is the generic term for the bulb bell and passes. The year
suffixes — TAPE FLUTE 1967, MELLO BARI 68 — read as era tags; nothing in
the regex catches a year, so it is a reviewer's call, and the rule's own
worked examples name the sound, never the vintage (CONCRETE, TAPE THUD,
PAPER CUP).

`PresetTestSupport.trademarkBlocklist` (`PresetTestSupport.kt:27-39`)
knows drum-machine, West Coast, dub and electric-piano makers, each added
with a comment citing the spec that brought them. Implementation adds
this brief's own: `mello(?!w)` (below; it covers `mellotron` too, since
`mello` followed by `t` matches), `heckel`, `chamberlin` (the tape
keyboard's predecessor and a maker's name), and the sax makers `selmer`
and `yanagisawa`; common-word makers (`conn`, `buffet`) are not added as
bare substrings. The near-miss `mello` is settled, not open: the house's
unbounded-substring policy (`PresetTestSupport.kt:15-25` explains why `\b`
is a hole) means a bare `mello` also matches MELLOW — and MELLOW is
already on five shipped presets (MELLOW BRASS, `VelvetPresets.kt:56`;
MELLOW FM, `FathomPresets.kt:80`; MELLOW GUT, `PluckPresets.kt:34`;
MELLOW RING, `ForkPresets.kt:60`; MELLOW WAH, `ResinPresets.kt:66`), so
the bare substring would turn five engines' blocklist tests red the day
it lands. **Decision: `mello(?!w)`** — the `(?!w)` is a negative
lookahead: match `mello` only when the next letter is not `w`, so
MELLOTRON and MELLO BARI are caught and MELLOW is not — the only form
that keeps those five green, with the reason written into the regex's
KDoc the way the `\b` decision is. The near-miss list gains
`MELLO BARI 68`, `HECKEL PIPE`, `MELLOTRON`, `mello-ish`; the clean list
gains `TAPE FLUTE`, `CHAMBER REED`, `LOW ROAR`, `MELLOW REED` and the
shipped `MELLOW BRASS`, so the lookahead is proven by what it lets
through (`ThumpPresetsTest.kt:107-111`; `ForkPresetsTest.kt:85-93`).

**Voice names.** FLUTE, BASSOON, BARI and GUANZI are generic instrument
names, the class `PluckVoice { NYLON, HARP, KOTO, BANJO, SITAR }` and
SILK's OUD/GUZHENG belong to. Round one's cone voice is **SAX**: the
plainer word for this user than BARI, and a generic dictionary word for
the instrument; a reviewer may note that it is also Adolphe Sax's
surname. The rule guards live makers' names and model numbers: SAX is an
eponym that became the dictionary word (Sax's own firm was absorbed by
Selmer), where HECKEL is a trading firm today — and the doc records BARI
as the fallback if the house reads it as the HECKEL case anyway. The
closed-cylinder voice, when it ships, is **HOLLOW**, named for the
sound: a real guanzi is a double-reed nasal pipe, so GUANZI is "sound
yes" only for a different sound than the spec's own "hollow,
clarinet-like" description (product §4). REED is not available (GLINT).

**Preset name law.** Uppercase, ≤ 14 characters for the sunken LCD listbox
(`docs/SYNTH_UPGRADE.md:137`), unique per voice, one count per voice per
engine (8 for FORK, 10 for SIREN and TIDE, 12 for PLUCK/RESIN/VELVET),
blocklist-clean. Of the spec's twelve, five pass every law as written:
LOW ROAR, HONK STAB, CHAMBER REED, BREATHY C4, TEMPLE GUANZI. The other
seven, as worked examples of the law:

| Spec's name | Chars | Problem | Named for the sound, ≤ 14 |
|---|---|---|---|
| VINTAGE BASSOON | 15 | length | OLD BASSOON |
| LIEBESFUSS DARK | 15 | length | LIEBESFUSS |
| TAPE FLUTE 1967 | 15 | length; a year tag | TAPE FLUTE (with `tape` in its landing chain) |
| OVERBLOWN HARMONIC | 18 | length; and the jet as coded cannot overblow (F14) | OVERBLOWN — only if the engine does; else a name for what it does |
| WARPED CYLINDER | 15 | length | WARPED PIPE |
| MELLO BARI 68 | 13 | trademark near-miss; a model-era number | a tape-landed name — TAPE HONK |
| GHOST HECKEL | 12 | a maker's name | GHOST BULB, if the bulb voice ever ships |

BREATHY C4 carries TUNE 0.50 (+12 semitones), so it sits at A4 on the
spec's A3 root; with the root moved to C4 (below) it would sit at C5, so
the note goes from the name: BREATHY alone. The roster itself is
authored by ear after Phase 0 has said which voices speak ("Phasing"),
eight per voice, so none of these names is a commitment.

## Architecture

```
BREATH ─▶ p_m(t) = share(BREATH, LIP) · closing(LIP) · env(CHIFF attack · HOLD · 80 ms release) · vib(t)
                                                    │
   SAX    : Δ = ret − p_m·(1 + turb·noise);  r = clamp(offset(LIP) + slope·Δ, −1, 1);  y = p_m + Δ·r
   FLUTE  : y = tanh( g(p_m)·(jet[n − T/2] + offset(LIP)) + turb·noise )·min(p_m, 1) + 0.5·ret      (STK Flute)
   [CHIFF top: + key thump, 90 Hz damped sine, windowed]
                                                    │ inject(y)
                                                    ▼
   Strings.Loop:  ring[D_exact] ─▶ two-tap average ─▶ tuning allpass ─▶ loopLp (the bell, corner from LIP)
                  ─▶ [DC blocker, +1 loops only, corner dcHz] ─▶ × fb   (+0.95 SAX / FLUTE, −0.95 HOLLOW)  ─▶ ret = reflected()
                                                    │ loop output
                                                    ▼
   [Strings.bodyRing(Modes.fixed rows) — identity at amount 0; no table in R1]
                                                    ▼
   Tide.bandLimit (19.5 kHz at 176.4 kHz) ─▶ Dsp.decimate ─▶ mean removed ─▶ 20 Hz high-pass ─▶ Dsp.levelTo(MELODIC_LOUDNESS_TARGET) ─▶ Dsp.fadeTail
```

Rendered at `Dsp.RATE * Dsp.OVERSAMPLE` (`Dsp.kt:33`) and decimated,
like every engine, and not optionally: the valve's cusp and the beating
kink mint harmonics above Nyquist (half the sample rate, the highest
frequency a render can hold; anything above it folds back as aliasing)
by construction. Mono in round one. `D_exact = (rate/f0)·roundTrip −
filterDelay − dcDelay − 0.5`, `require`d above `MIN_LOOP_SAMPLES`
(`Strings.kt:37, :178`) — the budget subtracts the filters' delays from
the ring, and a note so high that the ring would be shorter than what it
owes cannot be built, so the check fails loudly instead of rendering
nonsense; at 176.4 kHz the loops are SAX C2 2,697 samples down to C4
674, FLUTE C4 674 down to C6 169, HOLLOW A2 802 (a half period) down to
A4 200 — all far above 2.

### The exciter

**The reed** is the reflection table above, one line per sample, with
the spike's mapping: `slope = −0.3` (STK Clarinet's), `offset = lin(LIP,
0.5, 0.85)`, `closing = (1 − offset)/0.3`, and BREATH a share of
`closing` between two edges measured per voice — the predicted threshold
share `(1/0.95 − offset)/(2(1 − offset))` (the `p_thr` formula above
divided by `closing`, so it is a fraction of the closing pressure rather
than a raw pressure) plus a margin, and 0.97 (just under extinction).
The double reed, if BASSOON ever ships, is the same table with a steeper
slope. Turbulence is `Dsp.Noise(Dsp.seedFor("BORE", voice, hz))`,
multiplicative on `p_m` at 0.05 — a listening value with no source, like
the spec's 0.035, and a gate number. The core is `internal fun
blow(voice, hz, macros, rate, turbulence: Float? = null): FloatArray` at
exact Hz, the shape of `Fork.strike` (`Fork.kt:423`), with the override
argument `Pluck.synthesize` takes for its jawari (SITAR's buzzing
bridge, which a test can switch off) — because the claims tests read the
core at native rate (the 4× render, before decimation and before
`levelTo`), never the levelled `Snip`: `levelTo` would lift a
non-speaking, noise-only render to the loudness target and hide the
defect (the `Fork.bank` rule, `ForkTest.kt:30-31`; the spike measured
exactly that — sub-threshold renders come out at peak 0.990 as
full-scale hiss).

**The flute jet** is best-effort with its own gate: it locks in Kotlin at
`τ_jet = T/2` (0.32 and 0.40 do not), within 2.5 cents at 440 and 880 Hz,
with a speaking threshold between BREATH 0.5 and 0.65 in the spike's
`p_m = 1.2·BREATH` form and a register that moves with pressure (at
BREATH 0.85 the 3rd mode takes over at 440 Hz, at 1.0 it returns). So the
flute's BREATH also maps onto a measured window — floor above the jet's
threshold, ceiling below the 3rd-mode takeover — and its low register
(3rd mode +3.9 dB at 220 Hz) is the reason its root moves to C4. Not tried
yet: `τ_jet ∝ 1/√p_m` (the overblow mechanism), a jet band-pass, a lower
bore-filter corner. Those are the flute's prototype pass inside Phase 0.

**CHIFF** is the onset: at 0 a breath swell (~120 ms attack), at 0.5 the
spec's 40 ms tongued onset, at 1 a hard tongue (8 ms) plus the key thump —
the spec's 90 Hz clonk relocated to the one place it is engine-native,
run to < 1e-3 or windowed, gain 0 below CHIFF 0.7 and rising to 1 — a key
thump belongs to a tape keyboard, not a flute, so it appears only where
the knob already asks for a hard, tongued onset, and a default CHIFF
never has one. Flute identity lives in the first 50 ms, and a beatmaker's
stab is all attack.

### The bore

Two loop shapes, and both are `Strings.Loop`, not a private ring:

| Shape | `roundTrip` | `fb` | DC blocker | Voices |
|---|---|---|---|---|
| open / conical | 1.0 (a full period) | +0.95 | yes; corner `f0/25` on the cone (the spike's measurement), 2 Hz on the flute (the spike's default; not compared against other corners on the flute) | SAX, FLUTE; BASSOON later |
| closed cylinder | 0.5 (a half period) | −0.95 | none | HOLLOW |

`fb` 0.95 is STK's reflection coefficient; the spec's 0.94 is within
rounding. It is the loss per trip the valve must overcome, not a tone
control — the bell filter and LIP own the tone.

**Three additions to `Strings.kt`**, each the size `retune` and `gain` were
for SILK, landed first as their own PR (R0) — `Strings` is shared with
PLUCK and SILK, so a change to it is proven harmless to them on its own
before BORE's audio arrives to muddy the diff — and proven by
`StringsTest`'s frozen-copy grid (PLUCK's loop rendered at every rate,
pitch and damping and compared sample for sample against a frozen copy of
the old loop; one changed sample fails the test, `Strings.kt:22-24`;
`StringsTest.kt:22`) — SILK's Phase 1a shape:

1. `tune(…, roundTrip: Double = 1.0)`: `Strings.kt:161` becomes
   `exact = (rate / freq) * roundTrip − filterDelay − stiffDelay − dcDelay −
   dispersionDelay − 0.5`. The filter phase is still evaluated at `w =
   2π·freq/rate` (`:112`), which is the resonance that matters. The default
   leaves every existing caller unchanged (`Strings.kt:451, :478`;
   `Silk.kt:206, :318`; `StringsTest.kt:77, :84, :258, :282`), and `retune`
   (`:450`) carries the factor through, or a HOLLOW retune would re-solve
   at the full period.
2. `Loop(…, dcBlock: Boolean = jawari > 0f, dcHz: Float = 2f)`: the blocker
   at `:424-428` runs when `dcBlock`, not only under `jawari`;
   `dcBlockerA` (`:194`) takes the corner instead of its fixed 2 Hz; `tune`
   charges `dcDelay` (`:142-146`) under the same flag. SITAR keeps 2 Hz.
   Why the corner is a parameter is the spike's finding 1: `Strings.kt:131`
   chose 2 Hz because a higher corner's lead is budgeted at the fundamental
   only and leaves dispersion on the upper partials — measured on the cone
   as a 2nd mode 37–68 cents flat at 20 Hz — but at 2 Hz the reed's
   operating point relaxes at 21 Hz, and `f0/25` is where the cone measured
   best.
3. A junction hook. `next(x)` is additive — `x + fb * yy` at `:429` with
   the returning wave private to the method — and a blown bore's new line
   sample is a nonlinear function of the returning wave. `next` splits
   into `reflected(): Float` (everything from the tap through `fb * yy`,
   cached) and `inject(y: Float)` (the `history[i % size] = y; i++` write),
   with `next(x) = inject(x + reflected())` — a pure reordering, so the
   frozen grid is the proof it changed nothing.

Everything else in the loop already exists: the two-tap average and its
budgeted half sample (the spike's single tap made no measurable difference
to tuning), the Jaffe–Smith allpass that replaces the spec's negative-`frac`
lerp (F3), and `loopLp` (`:407`) — which *is* the spec's bell filter, its
corner set by LIP per voice (the spec's `expMap` table: SAX 1800 → 6500 Hz,
FLUTE 4000 → 12000, HOLLOW 1600 → 5500; the spike held 2500 fixed so LIP
had one axis under test, and whether LIP moves the corner as well as the
offset is a gate question). Negative feedback is already expressible —
`fb` is a Float, `damping()` merely never produces one (`:46-49`).

**Formants** — the spec's BASSOON vowel and HECKEL bulb — are `Modes.fixed`
rows through `Strings.bodyRing` after the loop (identity at amount 0,
`Strings.kt:643`), never inside it (F16). R1 ships the hook with no table:
the spec's numbers have no source, and `Modes.kt:121` says ratios are
sourced, not recalled. A row lands with a citation, or is auditioned
labelled *shape* — SILK's convention for a number chosen by ear rather
than taken from a source, so nobody mistakes it for physics (as its OUD
gains are).

**Vibrato** is baked, as pressure modulation — baked rather than a knob
for two reasons: a wind player's vibrato at a fixed musical depth is
identity, not a choice (TONEWHEEL's key click is "not a macro because it
is not a choice", `Tonewheel.kt:35-37`), and the sixth macro slot is kept
free for whichever of VIBRATO or OVERBLOW the gate asks for:
`p_m·(1 + d·sin(2π·5.2·t))` after the spec's 60 ms delay and 200 ms rise,
depth ±8 cents' worth at the default, scaled by HOLD so a 0.3 s stab has
none and a 3 s pad has some, and **bounded so it never leaves the
speaking window** (a swing of 0.25 of `p_m`, an earlier draft's first
guess, would cross the speaking window, which the closed form above and
the threshold probe put at only ~0.3 of the pressure axis — 0.65–0.8 at
EMB 0.5, Appendix A11 — so the vibrato would blow the note out on every
swing). A wind vibrato is pressure, not bore length (F17); the pitch
component is whatever the reed makes, tested ≤ 30 cents. Off in the LOOP
render, the organ's rule. Unlike MELLO, vibrato has no rack fallback —
WOBBLE is a tempo-synced filter sweep (`Wobble.kt:8-14`) and TAPE's
motion is capstan wow at 1.3 / 7.4 Hz (`Tape.kt:35-36`); VOX bakes its
vibrato with no knob and TONEWHEEL exposes WARBLE for the rotor
(`Tonewheel.kt:123`) — so it is baked for the five-macro budget and the
speaking window, not because the rack has it; the product review's case
for a knob with an extreme top (~80 cents, rate rising with depth,
product §3) is what the sixth-slot question at the gate decides. If the
gate misses the knob, it is the sixth macro.

**The threshold, and what a soft blow renders.** With BREATH mapped onto
the window, no pad render is sub-threshold: BREATH 0 is the softest
*speaking* pressure (the owner's decision 3 offers the alternative). The
only sub-threshold stretches are the onset (CHIFF 0's swell passes through
threshold on its way up — that is the breath before the note) and the
release. A render that nonetheless finds itself below threshold (a
corner the window's margin got wrong) must render as *breath* — the
turbulence filtered by the passive bore — at a level fixed relative to
the note, never lifted to `MELODIC_LOUDNESS_TARGET`; the fuzz test names
that corner.

### The output

`Tide.bandLimit` at the oversampled rate, `Dsp.decimate`, the mean
subtracted outright and a 20 Hz `Dsp.OnePole` high-pass by subtraction
(a high-pass made by subtracting a low-passed copy from the signal,
`Dsp.kt:413`; 20 Hz because it is below hearing and above the slowest
offset the reed leaves; FORK's "the DC goes twice"), `Dsp.levelTo(RATE,
MELODIC_LOUDNESS_TARGET)` (`Dsp.kt:507, :636`), `Dsp.fadeTail`. No
PUNCH; no `normalizeByFold`; no `limitPeak(1f)`. Loudness: BREATH and
LIP change tone and threshold, not level, the promise `levelTo` keeps on
every melodic pad; on keys the layers are velocity-true, `Keys.fork`'s
`Dsp.normalize` (`Keys.kt:354`), never levelled per render.

## Voices

**Round one: FLUTE and SAX** — the roster this document takes (the
product review's recommendation, product §1, §4): the sound everyone
names, and one honk. Each voice is a different exciter × bore pair with
its own physics claims to prove, so five voices is five auditions; FORK
shipped two and added a third after its gate. Both enter R1 only by
passing Phase 0's entry rule (below): a contiguous BREATH window at
least half the knob wide at f0 across TUNE 0–24 at every LIP corner, and
in tune within 5 cents at defaults.

| Voice | Physics | Root | Where the spike left it |
|---|---|---|---|
| **FLUTE** | air jet × open pipe; `roundTrip 1.0`, +0.95, blocker 2 Hz, `τ_jet = T/2` | **C4** (MIDI 60), moved from the spec's A3 — the register people call flute is C4–C7 (product §4) | locks within 2.5 cents at 440/880; threshold BREATH 0.5–0.65; 3rd-mode-dominant at 220 Hz; register moves with pressure. Needs its window edges and its ceiling measured, and the low register heard |
| **SAX** | single reed × cone; `roundTrip 1.0`, +0.95, blocker `f0/25` | **C2** (MIDI 36), so the knob's centre is C3 where a baritone honks under a snare; the spec's A1 is defensible and re-heard at the gate | speaks at 55/110/220 with a full series, ~5 cents flat (a per-voice pinned correction of about +5 cents, a 0.3 % shorter loop); 11 of 15 corners speak at f0/25, dead at LIP 1 for BREATH ≤ 0.65 and at LIP 0.5 / BREATH 0.3; a sub-40 Hz chug −16 to −22 dB at LIP 1. The risky voice, and the horn the product wants |
| **HOLLOW** | single reed × closed cylinder; `roundTrip 0.5`, −0.95, no blocker | A2 (MIDI 45), the chalumeau — a clarinet's low register | the anchor: ±2 cents at all 13 speaking corners at 220 Hz and at 110 / 220 / 440 at LIP 0.5, 39–43 dB odd/even, a threshold at BREATH 0.35 at LIP 0.5, LIP darkening monotonically. **The best-measured shape, and not in R1's roster** — it is SAX's replacement if SAX fails the entry rule after the window mapping, and the first R2 voice otherwise. Three in R1 is the owner's decision 2 |

The physics axes are exciter (single reed / double reed / jet) × bore
(cone, full series / closed cylinder, odd / open cylinder). What the
audition may add: **BASSOON** as the double reed — the same table with a
steeper slope and earlier beating, on the cone, with a sourced formant row
if one is found — a preset family on SAX's reed first, a voice if the
ear wants the reed law itself; and **HOLLOW**, above. What is cut, and
why: **HECKEL** is a maker's name on a voice, and BASSOON plus one biquad
at 140 Hz mixed at 0.45 — a preset, not a voice, and its bulb was a gain
stage inside the loop (F16); if the bulb is ever wanted it is a
`Modes.fixed` row on BASSOON. **GUANZI** as a name goes with the physics
kept under HOLLOW.

## Macros

Five, inside the 3–6 budget (`docs/SYNTH_ROADMAP.md:173-178`; FORK has
five, `Fork.kt:221-227`). Adding a macro later is compatible — `settled`
fills a missing key from defaults — and removing one is not, so the sixth
slot stays open for the gate.

| Macro | Moves | Mapping | Default |
|---|---|---|---|
| **TUNE** | the note | 24 semitones from the voice's root, snapped through `Keys.midiHz` (`Fork.kt:256-258`); `neutral = 0.5` | 0.5 |
| **BREATH** | mouth pressure: threshold, level (taken back by `levelTo`), turbulence, and on FLUTE the register | a **share of the closing pressure between two measured edges**, `lin(BREATH, s_thr(LIP) + m, 0.97)` on the reeds, the jet's own window on FLUTE; turbulence `lin(BREATH, 0.02, 0.06)` × p_m. Never silent at either end — the spec's silence at 0 and beaten-shut silence at 1 are rule-3 failures | 0.6 |
| **LIP** | the reed's rest opening — the table's offset — and the bell corner; tighter is darker and closer to the beat | `offset = lin(LIP, 0.5, 0.85)`; bell `expMap(LIP, lo, hi)` per voice if the gate keeps that axis; FLUTE: jet offset 0 → 0.3. Phase budgeted, so it is *not* a pitch knob (a claims test). EMBOUCHURE is not a plain word (rule 2); BITE is TINES'; LIP fits the LCD. `neutral = 0.5` | 0.5 |
| **CHIFF** | the attack: a swell, a tongue, a tongue plus the key thump | attack `expMap(CHIFF, 120 ms, 8 ms)`; thump gain 0 below 0.7, rising to 1; the thump run to < 1e-3 (~105 ms) or windowed | 0.4 |
| **HOLD** | how long the player blows; the top step is a LOOP | SIREN's mapping (`Siren.holdSeconds`, `Siren.kt:190`): `expMap(hold/0.99, 0.3, 4)` s with a 40 ms-shaped attack from CHIFF and an 80 ms release; `LOOP_THRESHOLD 0.99`, SCRAMBLE capped at 0.95 (`Siren.kt:83-84, :171`); `drumClassFor` derived from the classifier's 1.5 s line the way `Fork.drumClassFor` derives it (`Fork.kt:239-242`), from the *rendered* duration — the gate plus the 80 ms release and any tail pad — never a hard-coded knob value: under this mapping the gate alone crosses 1.5 s at HOLD ≈ 0.62 and, with the release counted, at ≈ 0.59, so the constant is computed from the mapping at implementation, not typed. (The spec's own hard-coded 0.65 was wrong even for its own mapping: `expMap(0.65, 0.25, 3.5)` is 1.39 s, under the line, whose crossing there is 0.679.) (`drumClassFor` is each engine's cheap prediction of the classifier's verdict from the macros alone, so a pad can land in the right group without being rendered twice; what the buckets can be is test 11) | 0.45 (0.97 s, under the 1.5 s line) |

Defaults: BREATH 0.6 sits in the middle of the measured speaking window at
every LIP, so SCRAMBLE's neighbourhood speaks; LIP 0.5 is the offset's
midpoint and its `neutral`; CHIFF 0.4 is the spec's own 40 ms tongued
onset (`expMap(0.4, 120 ms, 8 ms)` ≈ 41 ms) with no thump; HOLD 0.45 is
0.97 s, under the classifier's 1.5 s line so a default pad is a note, not
a LOOP (whether it files TONAL or PERC is test 11's measurement).

DECAY is not the word: the spec's own code is `expMap(decay, 0.25, 3.5)`
of note length with a fixed 80 ms release, and nothing decays — it stops.
SIREN's precedent: "the honest name costs nothing" (`siren spec:134-136`).
VIBRATO is baked (above), MELLO is out, and no PUNCH — a drum-engine macro
on a melodic engine (`fork spec:305`), with the spec's `punchAmount =
lin(BREATH, 0.2, 0.7)` a hidden second meaning besides. The sixth slot's
candidates at the gate: VIBRATO as a knob, or **OVERBLOW** — the one
extreme the rack cannot fake, a hard blow breaking to the next register;
on FLUTE the mechanism is the jet ratio, which STK's flute has and the
spike did not try; on SAX it is a register vent, which has no precedent in
the tree and would be research. Not in round one because it is uncertain
physics on one voice and a discontinuity SCRAMBLE could land on.

`scramble` (SCRAMBLE, the phone's randomise button) is `Dsp.scrambleNear`
— each macro nudged from a starting point by a random amount,
`Dsp.kt:133` — around the voice's presets once they exist (SKIN's lesson:
random values around the defaults land on garbage, around presets they
land on sounds, `Skin.kt:96-104`) with HOLD capped; until presets land it
takes GLINT's around-defaults form (`Glint.kt:180-191`).

**Velocity.** `Velocity.macroSpecsFor` gets its arm in R1 — the compiler
demands it (`Velocity.kt:191-218`). The `brightnessOverride → "BREATH"` line
the spec asks for is *not* registered in R1, and the design says why
rather than discovering it: the house registers an override only after a
sweep proves the macro moves the centroid at every step of its travel
(`ForkTest.kt:328`, an 11 % ripple allowed; PLUCK's PICK, FORK's STRIKE),
and the spike measured BREATH's centroid on the cylinder *flat* once
playing (261.7 → 252.5 Hz from 0.5 to 1.0). Two proofs would be needed
and one is expected to fail: the monotonic sweep, and "speaks at every
velocity" — `atVelocity` scales the macro toward 0.28× the preset's value
(`Velocity.kt:177, :284`), which the window mapping keeps inside the
speaking range (BREATH 0 is the floor), so the second proof passes by
construction and the first does not. BORE therefore takes `soften`
(`Velocity.kt:26`) in R1 like every engine without a proven axis; the
sweep runs anyway, per voice — FLUTE's jet puts pressure on the input
gain and may brighten where the reed does not — and the line is added
for a voice that passes. LIP darkens monotonically but in the *wrong*
direction for `atVelocity`'s floor (scaling LIP down loosens the lip and
brightens), so it is not the axis either. The override stays scoped to
`BorePatch`: VOX's BREATH means noise mix, the SKIN/SNAP lesson
(`Velocity.kt:223-236`).

## Held, and the LOOP

Two things look alike and cost differently.

**The LOOP (round one, behind the seam test).** HOLD's top step renders one
seamless loop for the SURFACE, and `→ SURFACE ▸` appears beside SEND TO PAD
exactly as it does for SIREN (`SynthScreen.kt:1063-1069`). This is the
"fun on a phone" prize — a flute under a finger, pitched by position,
breath and all — and it costs the same loop plan the held instrument
needs, so the two share it. The plan, in full, because it is not
plumbing:

- `Bore.planLoop(hz)`: `K = ceil(LOOP_MIN_SECONDS · hz)` periods (≥ 2 s so
  the WAV is a usable one-shot too, `Siren.kt:87, :330`), `loopFrames =
  round(K · RATE / hz)`, `baseHz = K · RATE / loopFrames` — `Keys.planLoop`'s
  fraction-of-a-cent move (`Keys.kt:169-180`). At C4 that is 524 periods,
  88,326 frames (baseHz 261.626, +0.004 cents).
- **Turbulence is periodic by construction**: drawn from a seeded table
  exactly `loopFrames × OVERSAMPLE` long and read modulo its length, so
  the exciter's input is loop-periodic and every downstream state settles
  to the same period. The organ's alternative — noise off in the held
  render, `motion = false` (`Keys.kt:79-86`) — is the fallback; a noise
  floor about −35 dB under the tone is the other (uncorrelated noise across
  the wrap contributes about twice its energy to the seam; house §3).
- **Vibrato is off** in the LOOP render, the organ's rule; whole vibrato
  periods per loop (SIREN's fit, `Siren.kt:339-371`) is later.
- **The played pitch is a limit cycle, not an accumulator's.** A
  valve-driven loop plays a few cents off the bore's linear resonance,
  moving with BREATH and LIP (F2; the residual pull above), so K whole
  periods of the *planned* `baseHz` do not close the way RESIN's phase
  accumulator does. The step no fleet loop has needed: render a settle
  stretch, measure the played frequency with `TuningAccuracyTest.measuredHz`'s
  windowed FFT (`TuningAccuracyTest.kt:43`), `Loop.retune` the bore by the
  ratio (`Strings.kt:450-458`; the Loop built 1 % low so the correction has
  room), render again, cut at a zero crossing (a point where the waveform
  passes through zero, so the cut itself makes no click; `Siren.bestCut`,
  `Siren.kt:425`), and let **`Keys.requireSeam` decide** at
  `MAX_SEAM_ERROR = 1e-3` (`Keys.kt:147, :201-204`), one retry at 2× settle
  (`:255-257`), never a crossfade (`:253`). Level on the loop region.
- If any R1 voice × TUNE fails the bar after the retry, the top step is
  **withheld from R1's readout** (no LOOP label, `drumClassFor` files by
  duration) and becomes R2. The classifier files anything over 1.5 s as
  LOOP regardless (`Classifier.kt:72, :137`), so a LOOP is filed by name,
  as SIREN's is (`Siren.kt:176-177`).

**The held keygroup (round two).** MAKE INSTRUMENT is gated to RESIN and
SIREN (`SynthScreen.kt:583, :619-621, :1083-1087`) because each closes
its own loop. `Keys.borePad(voice, macros, midi, attackSeconds,
cancelled): KeyNote` is `resinPad`'s body (`Keys.kt:216-259`):
`borePadMidis(voice)`, nine zones every minor third in the voice's
register (`:134-137`), `require` on range naming it,
`Bore.Held(attackSeconds, seconds, baseHz, turbulencePeriod)` rendered
unlevelled and decimated (`Resin.kt:266-267`), the cancel check every
2¹⁵ samples (`Resin.kt:231`), loop start after attack plus settle,
`requireSeam`, level on the loop region, `limitPeak(0.99)`;
`InstrumentSuite.renderBore` with two BREATH layers as `Layered(stem,
snip, velStart, velEnd, loopStartFrame)` and the
`InstrumentSidecar.RECIPES` entry that throws by name
(`InstrumentSidecar.kt:30, :59`); `HeldSpec.Bore` on the phone
(`SynthScreen.kt:1516`). A soft layer that does not speak is a hole in
the instrument — RESIN's held-pad decision 2, "the whistle is given up":
the pad stops short of self-oscillation rather than ship an instrument
with holes in it (`2026-09-25-resin-held-pad-design.md:393`), refused
exactly that — and the window mapping is what prevents it. Beside the
held path, the cheap one-shot instrument: `Keys.bore(midi, voice,
macros, breath): Snip` in `Keys.fork`'s shape (`Keys.kt:346-357`:
`require` on range, the core driven at `midiHz(midi)` so TUNE's snap is
never inverted, `Dsp.normalize`) feeding `InstrumentSuite.renderBore`'s
two layers as `renderFork` does (`InstrumentSuite.kt:84-87`) — a day's
work where the held one is a measurement, and shipped together with its
consumer, never alone. It is R2 with the held path, not R1, because a
woodwind keygroup of 0.3–4 s one-shots is the failure rule 6 names
("sustained key patches render a loopable sustain segment",
`docs/SYNTH_ROADMAP.md:184-187`) — a flute that stops under a held key.
Until R2 the door that already exists is PAD FROM ANYTHING → MAKE PAD →
INSTRUMENT, a one-note keygroup from any pad (`docs/BENCH.md:103-108`;
`kit/.../OneNote.kt`), wrong past a fourth because a woodwind's formants
are its identity — which is the argument for multisampling, after the
ear has said yes.

**RUNS OUT.** The 8 s unlooped zone is an instrument-sheet option in R2
(1.06 MB per zone at PCM_24), not HOLD's ceiling on a pad.

## Data flow and compatibility

The spec's checklist names five surfaces; FORK's introducing commit
touched sixty files (`git show --stat 82dc292`, +8464/−29). Against the
house checklist (house §1), with FORK's R1 line counts as the size guide
and the round each surface lands in. The compiler enforces `Velocity`'s
arm; `PresetsTest` enforces the `Presets` arm; every other line is a
convention.

| # | Surface | FORK R1 | BORE | Round |
|---|---|---|---|---|
| 0 | `synth/.../Strings.kt` | — | `tune(roundTrip)`, `Loop(dcBlock, dcHz)`, `reflected()`/`inject()`; `retune` carries `roundTrip` | **R0** |
| 1 | `Bore.kt` (new) | 385 | `BoreVoice { FLUTE, SAX }` in-file (`Fork.kt:51`), constants with KDoc that says *why* each number, `macrosFor`/`defaults`/`drumClassFor`/`scramble`/`midiFor`/`frequencyFor`/`settled`, `internal blow(voice, hz, macros, rate, turbulence)`, `render`, `planLoop`/`renderLoop`, `landingChain`, the thump | R1 |
| 2 | `BorePatch.kt` (new) + `Patches.kt` arm | 82 (in `Patches.kt`) | `TerraPatch.kt`'s shape; one arm at `Patches.kt:52-54` | R1 |
| 3 | `BorePresets.kt` (new) | 45 | 8 per voice, authored by ear against the built engine, conditional on Phase 0 | R1 |
| 4 | `Presets.kt` | +10/−2 | `forVoice` arm, `all()` term, KDoc "twelve" → "thirteen" (`Presets.kt:8, :62-66, :72-75`). Without this arm `Presets.forVoice("BORE", …)` hits `else -> emptyList()` and `snipsnap synth BORE` renders nothing | R1 |
| 5 | `Velocity.kt` | +6 | the exhaustive-`when` arm (`:191-218`, compile-mandatory); no `brightnessOverride` line until a sweep passes | R1 |
| 6 | `Keys.kt` | +39 | `borePad`, `borePadMidis`, `bore` one-shot, `BORE_LOW/HIGH_MIDI` | R2 |
| 7 | `SynthKits.kt` | +22 | `bore()`: A01–A08 a SAX walk on the pentatonic (the `fork()`/`siren()` shape, `SynthKits.kt:208-249`), A09–A14 six FLUTE presets, A15–A16 the two LOOPs — eight SAX pads because the honk is the kit's melodic walk and the house shape puts the tune range first, six FLUTE presets because a flute is a pad sound that needs fewer pitches, and the two LOOPs last where the SURFACE looks for them; `landingChain` per pad, LOOPs dry; the FLUTE pads land as PERC pads by the classifier's own gate (test 11) | R1 |
| 8 | `synth/build.gradle.kts` | +9 | `generateBoreKit`, `generateBoreAudition` (`:248-265`'s shape) | R1 |
| 9 | `BoreTest.kt` (new) | 577 | the claims tests | R1 |
| 10 | `BorePresetsTest.kt` (new) | 112 | the six-contract preset test (`ForkPresetsTest.kt:19-107`) | R1 |
| 11 | `BoreKitGenerator.kt` (new) | 32 | `KitAssembler.assembleArranged` + `KitExporter.exportProgramFolder` | R1 |
| 12 | `StringsTest.kt` | — | the inverting budget (a bare −0.95 loop at `roundTrip 0.5` resonates at f within 5 cents with no 2nd harmonic); the split's identity | R0 |
| 13 | `DeterminismTest.kt` | +8 | a canary: `patch.render()` twice, `assertContentEquals` (`DeterminismTest.kt:104-106`'s shape) | R1 |
| 14 | `PresetsTest.kt` | +3/−1 | `:29`, `:46-50` | R1 |
| 15 | `PresetTestSupport.kt` | +5/−1 | `mello(?!w)`, `heckel`, `chamberlin`, `selmer`, `yanagisawa` as new alternatives (`mello(?!w)` covers `mellotron`), with the KDoc line | R1 |
| 16 | `ThumpPresetsTest.kt` | +4/−2 | the near-miss and clean lists (`:107-111`) | R1 |
| 17 | `PadRecipeTest.kt` | — (SILK's) | `onePatchPerEngine` (`:25`) | R1 |
| 18 | `VelocityGrooveShuffleTest.kt` | — (SILK's) | the "darker at low velocity" canary (`:126-140`) — only with an override, and windowed past the thump | later |
| 19 | `InstrumentSuite.kt` / `InstrumentSidecar.kt` / `InstrumentSuiteGenerator.kt` | +20 / +4 / +1 | `renderBore`, the `RECIPES` entry, the name→renderer pair | R2 |
| 20 | `BoreAuditionGenerator.kt` (new) + `audition/bore-audition.html` | ~200 + ~1000 (SIREN's PR) | the listening page, copied from `siren-audition.html` for its SURFACE stand-in (`:380`) | R1 |
| 21 | `app/.../SynthScreen.kt` | +12/−2 | the `Engine` entry (`:1827`) and **seven** arms (`:1846-1953`: `voices`, `macrosFor`, `defaults`, `scramble`, `render`, `drumClass`, `buildPatch`), the landing line beside SIREN's (`:498`), the `→ SURFACE ▸` condition (`:1063`), the LOOP readout (`:1009`), the toast | **R1.1** — not compilable in a cloud session (no Android SDK; `:app` is outside the Gradle graph), and "a picker button guessed blind is worse than one left for a session that can verify it" (`fork spec:544-554`) |
| 22 | `README.md` | +24/−4 | the engine paragraph and the count (`README.md:336-337`, "thirteen") | R1.1 |
| 23 | `docs/SYNTH_ROADMAP.md` | +1 | row S19 at implementation time (S17 and S18 were taken) | R1 |
| 24 | this spec | +137 | "As built" in the same commit as the code | R1 |
| 25 | `testkit/SnipSnap Bore Kit/` | 16 WAVs + `.xpm` | `./gradlew :synth:generateBoreKit` | R1 |
| 26 | `testkit/Instruments/SnipSnap Bore.xty` + `_[TrackData]/`, `instruments.json`, the zips | 18 WAVs + `.xpm`, +209 | `./gradlew :synth:generateInstrumentSuite`, then `scripts/pack_testkit_zips.py`; the suite run regenerates unrelated instrument WAVs — revert those by hand, as FORK's commit did (house §1 E) | R2 |

The deferrals have precedent: TERRA and SILK are registered in `Patches`
and `Velocity` and absent from the picker (`SynthScreen.kt:1827` lists
thirteen); GLINT and TERRA are absent from `Presets.kt`
(`docs/SYNTH_ROADMAP.md:265, :270`). BORE registers in R1, ships presets
in R1 *if* Phase 0 has said the voice speaks (the TIDE/SIREN/FORK way;
SILK's "presets authored blind are disposable" is why the condition), and
takes the picker in R1.1 from a session that can see `:app`. `shell`'s
`UserPresetsTest` roster stops at TIDE and no engine since has joined it
(house §1). The CLI needs nothing for one-shots: `SynthCommand` resolves
through `Presets` (`SynthCommand.kt:43-59`), so `snipsnap synth BORE FLUTE
--all --out <dir>` works the day the `Presets` arm lands; `--instrument`
and `--drone` are RESIN-only by name (`:59`) and stay so — FORK's keys
went through `InstrumentSuite`, and BORE's will too.

The recipe: `BorePatch(name, voice, macros)`, `VERSION = 1`, round-trips
through `Patches.fromJsonValue`; a pad landed with `LANDING_TAPE` carries
`PadRecipe(patch, fx)` and regenerates bit-for-bit; there is no `seed`
argument anywhere (`Dsp.seedFor` only, F19).

## Failure handling

- A macro outside 0..1 is coerced and an unknown key dropped, `Fork.settled`'s
  shape (`Fork.kt:260`); `BorePatch`'s `init` rejects both in a recipe.
- `Strings.tune`'s `require(exact >= MIN_LOOP_SAMPLES)` is the loud floor
  (`Strings.kt:178`); at BORE's roots and ranges it is never near (169
  samples at the top of FLUTE).
- The loop cannot run away, and the argument is the small-gain one, not
  a pointwise one. The reflection table is non-expansive — `|r| ≤ 1`
  means `|out − p_m| ≤ |ret − p_m|`, sample by sample: the valve never
  returns more than it received — and the linear path back to it (the
  allpass at magnitude 1, the bell one-pole and the DC blocker at
  magnitude ≤ 1, the 0.95 reflection) returns at most 95 % of what went
  round at every frequency; with the loop's gain below one, a bounded
  mouth pressure gives a bounded state (the small-gain theorem: energy
  cannot grow without limit, so the note is the valve keeping a bounded
  oscillation alive, never a runaway), no `inf` can be minted, and
  `normalizeByFold`'s NaN path (F11) is unreachable — and not in the chain
  anyway. The flute has more room: `tanh` bounds the labium term by `p_m`
  whatever the jet does, so the path from `ret` to `out` has gain 0.5 and
  the loop 0.475. What this argument does **not** give is a pointwise
  ceiling: `|r| ≤ 1` bounds one update, not the sum of a filtered history,
  and an earlier draft's `2·p_max` claim is withdrawn. The fuzz test
  therefore pins an *empirical* raw-loop ceiling — the grid's measured
  maximum with a margin, recorded as a constant with its measurement in
  the KDoc (the `Dsp.Ladder` precedent) — and asserts against it, so a
  corner that grows past what Phase 0 saw is named. Phase 0 measured zero
  non-finite samples in about 220 renders and raw AC RMS up to 1.26 on
  the cylinder (Appendix B4); it did not tabulate raw peaks, and the fuzz
  test does.
- A BREATH window whose margin is wrong at some corner renders breath, at
  a fixed level, never hiss lifted to full scale; the fuzz test's
  non-silent clause is "a pitched tone 20 dB over the between-harmonic
  floor", so that corner is named, not passed.
- A LOOP whose seam does not close after the retry is refused by name
  (`Keys.requireSeam`'s message), and in R1 the top step is withheld from
  the readout rather than shipped with a click.
- `drumClassFor` is a cheap macro-only predictor verified against the
  real classifier across ten HOLD steps; it never wishes for TONAL
  (`Fork.kt:239-242`; `fork spec:513-525`).
- The held render (R2) takes `cancelled` and checks it every 2¹⁵ samples;
  one-shots do not, as none do.
- Peak memory is about 8–10 MB per 3.5 s render (a 2.47 MB raw buffer
  plus its decimated copies), in line with every 4× engine (empirical
  §11); not a concern.

## Testing

All `kotlin.test`, never Jupiter (77 of 77 synth test files); all read
off rendered audio — the core at native rate for the physics, the `Snip`
for the product claims. CI sweeps run at HOLD 0 (0.3 s) — CI runs on
metered minutes, and the house measures pitch on the first 0.25 s of a
note anyway (`TuningAccuracyTest.kt:45-46`). Sized, as estimates from the
measured 26–59 ms per rendered second: test 2's in-tune sweep is 25 TUNE
steps × 2 voices × 4 corners ≈ 200 renders, about 5 s; test 1's speaking
window sweeps BREATH in 0.05 steps at three TUNEs × three LIPs per voice
in CI (≈ 360 renders, 5–7 s), and the full 25-TUNE grid (≈ 1,500 renders,
20–30 s) is a printed table behind an opt-in system property, not a CI
default.

### The ones that carry the claims

1. **Speaks.** For every voice, every snapped TUNE step (three in CI,
   the full grid opt-in — above) and LIP at 0 / 0.5 / 1: the core at
   `turbulence = 0`, kicked by 1e-3, has AC RMS in its last 100 ms above
   a floor at BREATH's default, and the set of BREATH values that speak
   is one contiguous window at least half the knob wide. Printed as a
   table, per voice. **This runs before the in-tune test and is what
   makes it meaningful**: a pitch test alone passes on a reed that does
   not speak (the threshold probe's BARI 12 at BREATH 0.2–0.5, −3 to −14
   cents at AC 0.0000).
2. **In tune.** Every snapped TUNE step, both voices, BREATH and LIP at
   default and at the window's corners: the fundamental within 5 cents
   of `frequencyFor` at defaults by `measuredHz`'s windowed FFT peak
   search (`TuningAccuracyTest.kt:43-80`) — never autocorrelation
   (`Pitch.detect`'s method: it reads the period of the whole waveform,
   so a DC offset or a dominant harmonic fools it — 1025.6 Hz for BARI's
   55 Hz root, Appendix A2), and never a zero-crossing count of the kind
   `ForkTest.preciseFundamental` (`ForkTest.kt:124`) uses: the threshold
   probe's own zero-crossing column read 300.7 Hz on BARI at 110 Hz at a
   corner that was not speaking (Appendix A11: EMB 0.5, BREATH 0.65,
   kicked AC 0.005) — a detector counting crossings a tone does not own.
   The corners are printed and held to a named per-voice bound (the
   residual pull), with a pinned per-voice correction where the spike
   measured one (SAX ≈ +5 cents). The one test that would have caught
   the octave, the filter lag and the bulb on the first run.
3. **LIP is not a pitch knob.** Pitch at LIP 0 and LIP 1 within 5 cents
   of each other at three TUNEs per voice — F2's direct test.
4. **Bounded, and pinned.** Zero non-finite samples at every corner of
   the fuzz grid, and the raw loop peak under an *empirical* ceiling: the
   grid's measured maximum with a margin, pinned as a constant with its
   measurement in the KDoc (the `Dsp.Ladder` precedent) — a number the
   test asserts, not `isFinite`, and not a derived bound: the small-gain
   argument in "Failure handling" forbids a runaway but gives no
   pointwise constant.
5. **The series is the bore's, and the jet's.** SAX's 2nd harmonic within
   12 dB of the 1st (the cone's full series; spike: −7.9 to −10.1 dB);
   FLUTE's 2nd at least 15 dB under (spike: −37 to −44 dB) — and the doc
   names the mechanism, which is *not* the open pipe (its series is full)
   but the jet's odd-symmetric labium with `τ_jet = T/2`; HOLLOW, when it
   ships, 2nd ≥ 20 dB under the 1st and 3rd (spike: −42 to −49 dB). The
   spec's odd-harmonic test asserted `isFinite`.
6. **BREATH and LIP are monotonic where they claim to be.** LIP's
   centroid non-increasing over nine steps with FORK's 11 % ripple
   allowance (`ForkTest.kt:328`), measured on the sustain (from 0.2 s,
   past any thump: `FeatureExtractor` reads the first 4096 samples, 93
   ms, `Features.kt:47`, and a thump inside that window decides the
   number — the mechanism by which the spec's own MELLO test would pass
   for the wrong reason once its level bug is fixed). BREATH's sweep
   runs and prints; it is the *price* of the velocity override, and the
   override is added only for a voice that passes it. Loudness does not
   move with either (`levelTo`).
7. **DC.** `|mean| < 0.05` on every render (the spike: ≤ 0.0094), and the
   in-loop blocker's harmonicity on SAX: the 2nd mode within 10 cents of
   2·f0 (at 20 Hz it was 68 cents flat).
8. **Loudness in the melodic band**: `loud ≥ 0.9 · MELODIC_LOUDNESS_TARGET
   || peak ≥ 0.95` (`ForkPresetsTest.kt:28`), *and* the sub-threshold
   clause: any render below threshold passes by the loudness clause, never
   the peak clause.
9. **Aliasing floor.** `harmonicClarity ≥ 45 dB` (`ForkTest.kt:87`;
   `harmonicClarity`: the energy at the harmonics over the energy
   between them, in dB — the between-harmonics floor is where aliasing
   shows) at the top TUNE, BREATH at the window's top, LIP 1 — run at
   `turbulence = 0`, because that measure counts everything off-harmonic
   as noise and breath *is* the sound; a separate test proves turbulence
   energy rises with BREATH.
10. **Determinism through the recipe.** `BorePatch.render()` twice,
    byte-identical; every seed from `Dsp.seedFor`; the `DeterminismTest`
    canary.
11. **Classifier agreement.** `drumClassFor` agrees with `Classifier.classify`
    across ten HOLD steps; no preset in a drum choke group (pads that cut
    each other off, like closed and open hats; a note filed there would be
    silenced by the next hit — `ForkPresetsTest.kt:36-37`); the LOOP filed
    by name. Expected buckets, from the classifier's own gates: TONAL
    needs `lowRatio > 0.55` (the share of energy under 200 Hz,
    `Features.kt:26`) *and* `decayMs > 500` (`Classifier.kt:44-45,
    :141-151`); LOOP is length over 1.5 s (`:72, :137`); everything else
    goes to the bright branch, PERC or SNARE. FLUTE at C4–C6 has no energy
    under 200 Hz and so reads PERC below 1.5 s by construction — the six
    FLUTE pads at A09–A14 land as PERC pads whatever the engine does, the
    same truth roadmap S16 records for PLUCK and SILK; SAX at C2–C3 is the
    only voice that can read TONAL, and only if its −20 dB decay clears
    500 ms. `drumClassFor` therefore predicts PERC/LOOP for FLUTE outright
    and is measured for SAX. The finite voices of the spec's engine
    measured PERC (empirical §4); the corrected engine's buckets are
    unmeasured until this test runs, and the picker's table says what it
    measures — never TONAL by wish (FORK's spec wished it and measured
    otherwise, `fork spec:513-525`).
12. **The LOOP closes.** Whole periods ≥ 2 s, `Keys.seamError` at the
    wrap < 1e-3 at three TUNEs per voice, pitch within 1 cent of the
    plan — tighter than the 5-cent tuning bar because the loop is K
    whole periods of the *planned* pitch, and across 524 periods each
    cent of miss is 0.3 of a period of slip at the wrap; the seam bar is
    what closes the loop, this bar says the retune step landed —
    bit-identical across two calls; `landingChain` null on a LOOP and a
    `tape` section otherwise (`SirenTest.kt:379-382`'s shape).
13. **CHIFF is heard only at the start.** The first 30 ms's centroid
    differs between CHIFF 0 and 1; past 200 ms the spectra agree within
    1 dB per band; the thump's tail is under 1e-3 before it stops.
14. **Recipe round trip and names.** Presets through `toJsonText`/
    `fromJsonText` unchanged; ≤ 14 characters, uppercase, unique, one count
    per voice; the blocklist with the new terms; the near-miss and clean
    lists; spread `rmsDistance > 0.05` (`ForkPresetsTest.kt:57-107`).

### The rest

- **Fuzz:** every macro at 0 / 0.5 / 1 across both voices — finite,
  bounded, non-silent (a pitched tone, not hiss), finishes. This alone
  would have caught F8, F10, F11 and F30 on the spec's engine.
- **Peak ≤ 0.99** (`levelTo`'s ceiling), never `limitPeak(1f)`.
- **Strings unchanged:** the frozen grid passes after the R0 additions; a
  bare −0.95 loop at `roundTrip 0.5` resonates at f within 5 cents with no
  2nd harmonic; `next(x) == inject(x + reflected())` sample for sample.
- **Identity:** 200 SCRAMBLEs audible and unclipped; SCRAMBLE never rolls
  LOOP.
- **Keys (R2):** nine zones with no gap, every zone in tune, every seam
  closed, the hard layer brighter and louder than the soft, a seam that
  does not close refused by name, cancellation reaching the render —
  `ResinHeldTest`'s list.
- **Render time:** a printed ms-per-second beside the sweep, against the
  measured 26–59.

### Why each of the spec's five tests is replaced

| Spec's test | What it proves | Replaced by |
|---|---|---|
| bit-for-bit with `seed = 123` | that `Bore.render(…, seed)` is a pure function — not that a *recipe* regenerates, which is the promise | test 10 |
| every voice peak ≤ 0.991, rate, channels | not guaranteed by `limitPeak(1f)`; three voices fail it by NaN, "the right red for the wrong reason" | test 4 and the fuzz |
| MELLO lowers rolloff on FLUTE | fails as written (7623 > 2616 Hz, the un-normalised saturation outweighing the clonk); with the level fixed it would pass for the wrong reason on any render with a clonk in its first 93 ms | the landing A/B at the gate; test 13's window rule |
| "guanzi exhibits strong odd-harmonic dominance" | asserts `isFinite` on two centroids — nothing its name says | test 5 |
| velocity darkens via BREATH | fails in the wrong direction (641 → 43 Hz, DC-driven); and depends on BREATH being monotonic, which the spike says it is not | test 6, and the override withheld |

## Phasing and gates

Every phase ends the house way: stop and listen. A listening page renders
the phase's voices at defaults and at each macro's extremes, and the chips
— the verdict buttons on the listening page, saved beside the clips — are
the gate. Phase 0 is the throwaway spike; R0 to R3 are the pull requests
that follow, R0 being the shared-toolkit change that must land before any
BORE code.

| Phase | Ships | Gate |
|---|---|---|
| **0 — spike** (spike done, recorded in `../plans/2026-09-28-bore-phase-0-spike.md`; the gate not yet run on its full grid) | the corrected loop on the house's primitives, three shapes, six print-only tests, every number in Appendix B | **speaks *and* in tune**, in that order, per voice: a contiguous BREATH window at least half the knob wide at f0 across TUNE 0–24 at every LIP corner, then 5 cents at defaults. "The knob" is the spike's share axis (BREATH → 0.4–0.97 of the closing pressure) before the per-LIP window mapping R1 ships — measured there, not on raw pressure. Where it stands: HOLLOW is ±2 cents at all 13 speaking corners at 220 Hz and at 110 / 220 / 440 at LIP 0.5, but the TUNE 0–24 × LIP grid the rule asks for has not been run, and at LIP 1 its window is BREATH 0.65–1.0 (0.35 wide), so the rule as worded is met only after the window mapping; SAX speaks at 55/110/220 with the f0/25 blocker, ~5 cents flat, and has dead corners at LIP 1 / BREATH ≤ 0.65 and at LIP 0.5 / BREATH 0.3 — the window mapping at LIP 1 and the blocker question (f0/25 versus the apex allpass or a two-segment cone) remain; FLUTE locks at 440/880, its window edges, ceiling and low register remain. Nothing registered until each R1 voice passes |
| **R0** (built 2026-09-28, "R0, as built") | the three `Strings.kt` additions, `StringsTest`'s new checks; no audio change | the frozen grids match sample for sample (SILK 1a): **met** — 384 and 108 cases unchanged, and the whole JVM suite green |
| **R1** | `Bore.kt` (FLUTE and SAX, five macros, the LOOP render behind the seam test, `landingChain`, the thump), `BorePatch`, `BorePresets` (8 + 8, by ear against the built engine, frozen by the six-contract test), registration in `Patches`/`Presets`/`Velocity`, `SynthKits.bore()` and its testkit kit, the tests above, the blocklist terms, the audition page. Mono — **built 2026-09-29** ("R1, as built"; presets provisional) | **the audition** (below) |
| **R1.1** | the phone: the picker entry and seven arms, SEND TO PAD landing with tape, `→ SURFACE ▸` at LOOP, the toast; README's count | built in a session that can see `:app`; the gate's verdict is its precondition |
| **R2** | `Keys.borePad` + `InstrumentSuite.renderBore` + `HeldSpec.Bore` (MAKE INSTRUMENT), the one-shot `Keys.bore` beside it, RUNS OUT, HOLLOW as a voice, the sixth macro (VIBRATO or OVERBLOW), the withheld LOOP if R1 withheld it | the instrument under two hands for ten seconds: the seam, and whether breath repeats audibly at the loop period |
| **R3** | presets re-heard and extended, BASSOON as a reed family with a sourced formant row, stereo (U4, the upgrade document's stereo item — per-patch opt-in after a rack audit, `docs/SYNTH_UPGRADE.md:271-287`), DRONE TO LOOP (SIREN's fourth door) | each its own listen |

Order of work inside R1: the reed table and the tune budget first
(spike-backed), then the flute's prototype pass, then the LOOP, then the
landing and the kit — SILK's nonlinear-risk-last rule inverted, because
here the nonlinearity is the note.

**The audition page** (`BoreAuditionGenerator`, the SIREN page's shape with
its Web Audio SURFACE stand-in, `siren-audition.html:380`; every clip
through `AuditionLevel`), in this order:

1. **Hold the surface first** — FLUTE LOOP and SAX LOOP under a finger, a
   pitch slider, thirty seconds. Chip: does the wrap click; is it fun.
2. **The kit as it lands** — A01–A08 SAX walking the pentatonic, A09–A14
   six FLUTE presets, A15–A16 the two LOOPs; every one-shot through the
   landing TAPE, LOOPs dry.
3. **The honk A/B** — SAX HONK STAB, RESIN BRASS and VELVET BRASS stabs in a
   two-bar pattern at 90 bpm beside a THUMP snare (the cloud has no
   captured audio; FORK used synthesised stand-ins too,
   `ForkAuditionGenerator.kt:8-28`), alternating on the bar line and
   level-matched — the audition spec's own rules
   (`docs/AUDITION_SPEC_2026_09.md:100-115`).
4. **Blind identity** — FLUTE default at three TUNEs, unlabelled: flute /
   recorder / pipe / synth.
5. **Each voice**: default, then BREATH · LIP · CHIFF at both ends with a
   plain-words line for each end (TUNE and HOLD are not knobs to audition,
   `ForkAuditionGenerator.kt:33`), then all eight presets.
6. **The tape A/B** — each voice dry, through `LANDING_TAPE` at 0.1 and at
   0.2, and the spec's MELLO 0.85 rendered on the corrected engine;
   level-matched.
7. **Hollow or nasal** — HOLLOW at defaults against SAX, if the owner
   chooses three voices; else the spike's cylinder render, labelled as such.
8. **One extra** — FLUTE at BREATH 1: should it break to the octave? The
   OVERBLOW question, asked before it is built.
9. **The phone** — a 4 s HOLD on the test device, timed against the 150 ms
   shimmer (`SynthScreen.kt:185`); no phone has been measured (product §7).

**Pass rule.** Questions 3 and 4 must pass: if the honk cannot be told
from RESIN BRASS the engine is redundant for this user, and if the flute
reads "synth" the presets are not earned (FORK's own audition verdict —
closer to a piano on TINE, but neither voice read as one outright —
which is why FORK got a third voice in round two, `fork spec:556-561`).
Presets are frozen per voice that passes; `LANDING_TAPE`'s numbers are
set by 6; R2 is gated on 1 and 4; the sixth macro on 8. A voice that
fails gets no roster and no picker entry — GLINT's state (built,
registered, no presets and no picker entry, waiting on its audition,
`docs/SYNTH_ROADMAP.md:265`), not worse.

**Effort**, from FORK's footprint. FORK R1 hand-written, per file from
`git show --stat 82dc292`: `:synth` main 385 + 82 + 45 + 10 + 6 + 39 + 22 +
9 = **598**; tests 577 + 112 + 32 + 8 + 3 + 5 + 4 + 20 + 4 + 1 = **766**;
`:app` **12**; docs 24 + 1 + 137 = **162**; **1,538** in all, about 7,000
generated, 60 files. FORK R2 (one voice from the gate): 7 files, +275/−37.

| Phase | Files | Hand-written | Notes |
|---|---|---|---|
| 0 | 2 | 543 (throwaway) | `BoreSpike.kt` 238, `BoreSpikeTest.kt` 305 — done |
| R0 (built) | 2 | ~390 as built | `Strings.kt` +116/−12, `StringsTest.kt` +264; the estimate was ~100 |
| R1 | ~20 hand-written, +17 generated | **~1,750** | main ~616: `Bore.kt` ~480 (two exciters, the budget calls, `renderLoop` on `Siren.kt:339-451`'s ~130-line shape, `landingChain`, CHIFF), `BorePatch` 35, `BorePresets` 50, `Patches` 1, `Presets` 6, `Velocity` 1, `SynthKits` 25, gradle 18; tests ~971: `BoreTest` ~600, `BorePresetsTest` ~115, kit generator 32, audition generator ~200, canaries 24; docs ~165 (README 24, roadmap 1, "As built" ~140); plus a ~1,000-line page copied and edited, and a kit's 16 WAVs and `.xpm` generated |
| R1.1 | 3 | ~20 | `SynthScreen.kt` ~18, README's count |
| R2 | ~12 | ~530 | `Bore.kt` +120 (`Held`, `renderHeld`, the retune step), `Keys.kt` +100, suite/sidecar/generator +18, `BorePadMaker` in `:shell` ~60, `HeldSpec` +30, tests ~200; a ~3.5 MB instrument generated |

R0 plus R1 was estimated at about 1,850 hand-written lines against FORK R1's
1,538 — a fifth *more*, not two-thirds (an earlier two-thirds estimate was
wrong); with R0 built at about 390 rather than 100 it is about 2,140, two
fifths more ("R0, as built");
the generated weight is a third of FORK's, because there is no
instrument in R1. The size is five voices' worth of physics claims
folded into two, and a LOOP render FORK never had.

## Out of scope

- **Toneholes and a tonehole-lattice cutoff.** TUNE is bore length; every
  note has the same fingering.
- **A register hole and overblowing** — unless R1's flute pass proves the
  jet ratio cheap, in which case it is the sixth macro's candidate; on the
  cone it is research.
- **A truncated cone.** The Benade-compensated full-period loop; the apex
  allpass only if Phase 0's blocker question lands on it.
- **Reed resonance, squeak, a vocal tract.**
- **MELLO as DSP** — all five parts; the clonk lives in CHIFF.
- **HECKEL; BASSOON and GUANZI as named voices in R1.**
- **PUNCH; the `seed` argument; a `Keys.bore` that returns a bare `Snip`
  with no consumer.**
- **Stereo** before U4's rack audit; **DRONE TO LOOP** before R2's seam is
  proven; **a real-time or native voice.**
- **Twelve uneven presets and the five names over 14 characters.**

## Decisions already taken

What this document treats as closed, and on whose authority — so a
reviewer can tell precedent from proposal. FORK's table has the same
three columns (`fork spec:427-435`).

| Question | Decision | By |
|---|---|---|
| Home | a `:synth` engine, Kotlin, offline, one `Snip` per render | the spec itself; FORK's four reasons (`fork spec:22-34`) |
| Tape stage | the engine renders dry; the tape-flute story is a `landingChain` with a `tape` section | the CRUNCH rule (`fork spec:88`; siren spec, ECHO row), applied twice already |
| Length macro | HOLD, with a LOOP top step; not DECAY | SIREN's precedent (`siren spec:134-136`; `Siren.kt:74-90`) |
| Output | `Tide.bandLimit → Dsp.decimate → Dsp.levelTo → fadeTail`; no PUNCH | the melodic fleet (`Fork.kt:465-468`, `Tide.kt:549`); `fork spec:305` |
| Reed law | Smith's reflection table, its reflection never past ±1; LIP as the table's offset, BREATH a share of the closing pressure | Phase 0's measurement (Appendix B; findings 1–3) over the spec's Bernoulli valve |
| Bore | `Strings.Loop` with three additions, landed as R0 | fleet §4; spike B10; SILK's Phase 1a shape |
| DC | in-loop blocker on the +1 loops, mean and 20 Hz high-pass at the output | FORK's "the DC goes twice" (`fork spec:251-255`) |
| Names | MELLO and HECKEL off every product surface; `mello(?!w)` in the blocklist | `docs/SYNTH_ROADMAP.md:27-47`; the five shipped MELLOW presets |
| Velocity | `soften` until a sweep passes; no `brightnessOverride` in R1 | the house's registration rule (`ForkTest.kt:328`; `Velocity.kt:238-253`); the spike's flat BREATH centroid |
| Roadmap row, README count | at implementation, not now | the rule FATHOM, RESIN, GLINT, SILK and FORK followed (house §2h) |

## Decisions for the owner

Each with the default this document takes; a default stands until the
owner or the gate overturns it. This is FORK's "Open for review".

1. **The name.** BORE (this document: the mechanism word, no collision) or
   WIND (the family word, no pun). Default **BORE**; a rename is one
   constant.
2. **R1 voices.** FLUTE + SAX (this document), FLUTE + SAX + HOLLOW (the
   best-measured shape is nearly free once the inverting budget exists,
   and it answers "hollow or nasal" at the same gate), or the spec's
   five. Default **two**, HOLLOW as SAX's replacement if SAX fails Phase
   0's entry rule and the first R2 voice otherwise. Voice names: SAX or
   BARI; roots C4 and C2 versus the spec's A3 and A1, re-heard either
   way.
3. **BREATH 0.** The softest *speaking* pressure (rule 3 holds, velocity
   never silences a layer, "air" is not on the knob) or air (physically
   honest, velocity needs another axis). Default **the softest speaking
   pressure**.
4. **The velocity macro.** `soften` in R1 with BREATH's sweep printed per
   voice, the override registered only for a voice that passes (this
   document); or register BREATH now and let the test fail. Default
   **soften**; the spike says the cylinder's sweep is flat.
5. **MELLO's fate.** Parts 2–5 to the rack by `landingChain`, decided
   finally by the level-matched A/B at the gate (default); or in-engine
   from the start. `LANDING_TAPE` at 0.1 or 0.2 WOBBLE, and whether every
   BORE one-shot lands through it or only the tape-flute presets. Default
   **land every one-shot, LOOPs dry, 0.1 until the gate says 0.2**.
6. **HOLD's ceiling.** 4 s on pads like SIREN (default), with RUNS OUT as an
   8 s unlooped instrument-sheet option in R2; or 8 s on pads (rejected
   here on file size and phone time — an estimate of 0.3 to 0.95 s per
   drag step at 4 s: the 26–59 ms per rendered second measured on the JVM
   (Appendix B) times the DSP review's 2–4× phone factor (dsp §12), no
   phone measured — past the 150 ms shimmer, `SynthScreen.kt:185`).
7. **Whether the clonk survives.** Inside CHIFF above 0.7, run to −60 dB
   (default); or dropped, with CHIFF as swell-to-tongue only.
8. **The cone's blocker.** `f0/25` as measured (default), or Phase 0 spends
   a day on the apex allpass / two-segment cone before R1; the spec's LIP
   → bell-corner axis kept or dropped in favour of LIP as the reed's rest
   opening alone.
9. **The `mello` regex.** Settled rather than open: `mello(?!w)` is the
   only form that keeps the five shipped MELLOW presets (MELLOW BRASS, FM,
   GUT, RING, WAH) green; the bare substring is not an option. Recorded in
   the KDoc. Listed here so the owner sees it, not to reopen it.
10. **Presets in R1** conditional on Phase 0 (default), or after the gate
    outright (GLINT/SILK).
11. **The LOOP in R1** behind the seam test with the withheld fallback
    (default), or R2 whole.
12. **Whether to build at all.** The alternative is a VOX breathy-flute
    stopgap — a `VoxFlute` sub-voice on the machinery THROAT and WRAITH
    already have: a held source, breath as band-passed noise, a narrow
    resonance picking a harmonic, delayed vibrato — cheap, no new
    physics, and enough if the product only needs "breathy, flutey". It
    cannot give the honk, the threshold, a register, or noise filtered
    by the loop. Default **build**, because Phase 0 has already shown
    the idea works in Kotlin: bounded everywhere, in tune to 2 cents on
    the cylinder, a speaking cone, a flute that locks (at the corners
    measured; the full grid is the gate) — and because the honk is the
    one sound this user asks for that no engine in the fleet makes.

---

## R0, as built — 2026-09-28

R0 is the change to `Strings` that BORE's bore needs and that no PLUCK or
SILK sample may notice. It is built, and it moves nothing: the two frozen
grids (384 cases against PLUCK's own loop, 108 against Phase 3a's stiffness
and jawari, both sample for sample), PLUCK's, SILK's, the determinism and
the pad-recipe suites, and the whole JVM build (nine modules, 3,277 tests,
none failing) are green on it.

**What landed** (`Strings.kt`; the design's "Three additions", as written
except where a departure is listed):

1. `tune(…, roundTrip: Double = 1.0)` (`:97-111, :210`): `exact =
   (rate / freq) * roundTrip − filterDelay − stiffDelay − dcDelay −
   dispersionDelay − 0.5`. The period stays a Float division and the round
   trip multiplies it as a Double, so at the default the value is the same
   Float widened and multiplied by 1.0 — the bit-for-bit reason the grids
   stay green. `Loop` carries the factor and `retune` passes it back
   (`:465`, `:597`).
2. `tune(…, dcBlock, dcHz)` and `Loop(…, dcBlock, dcHz)` (`:186`, `:453`):
   the DC blocker stands on its own, at a corner the caller chooses.
   `dcBlock` defaults to `jawari > 0`, `dcHz` to the new `DC_BLOCK_HZ = 2f`
   (`:51`), so SITAR keeps exactly what it had. In the loop the blocker was
   the second half of the jawari branch; it is now its own step in the same
   place in the chain, so a loop with the limiter on runs the same
   operations in the same order.
3. `reflected()` / `inject(y)` (`:507`, `:565`) with `next(x)` rebuilt on
   them (`:578`): a blown bore replaces `x + …` with a nonlinear function
   of the returning wave. `reflected()` is cached until `inject`, so a
   caller that asks twice does not step the filters twice.

**Departures from the design, each small and each for a reason:**

- **`next` keeps its warm-up branch.** The design wrote `next(x) =
  inject(x + reflected())`. Before the loop has history `reflected()` is
  0, and `x + 0f` turns a `-0.0` input into `+0.0` — a different bit, which
  the grids compare. `next` passes `x` straight through until there is
  history, exactly as the loop always started, and the identity test
  exercises both halves.
- **Two guards the design did not list, both tightened by review.** `tune`
  refuses a `roundTrip` outside (0, 1], by name — at or below 0 it makes
  `exact` negative and trips the Karplus-Strong minimum's message, which
  blames a note that is too high; above 1 it is a loop longer than its own
  note; and a finite extreme like `Double.MAX_VALUE` overflows `exact` to
  infinity, which passes the minimum and returns a tuning no ring can be
  built from. The blocker refuses a corner that is not a frequency under
  Nyquist: an infinite corner, or any finite one far enough past Nyquist
  that `exp(−2π·hz/rate)` underflows, makes the coefficient exactly 1, a
  blocker that subtracts every sample and silently kills the loop. Both
  first shipped weaker (`roundTrip > 0`, corner `> 0`) and an automated
  review found the holes; each is now tested at the extremes, in the budget
  and in the loop, and removing either alone fails its test — including the
  review's own suggested weaker fix, finite-only, which lets
  `3.4028235E38` through.
- **The corner is a named constant.** `DC_BLOCK_HZ` replaces the literal 2
  inside `dcBlockerA`, with its KDoc carrying the spike's reason for
  leaving it a parameter: whether the cone wants `f0/25` (Appendix B) is
  now R1's choice to make, not a change to `Strings` to ask for.

**The checks** (`StringsTest.kt`, ten new tests, 32 in the class).
Each was written to fail for its own reason, and each guard was proven by
removing it alone and watching a test fail with a message that names it:

| Guard removed | Caught by | The message |
|---|---|---|
| `retune` drops the round trip | *retune carries the round trip* | the loop's own "needs a loop of 1193 samples, past the 671 this Loop was built for" |
| the loop runs the default corner, not the one it was given | *the blocker's budgeted corner and its running corner are the same number* | measured 107.3 Hz for a 110 Hz note |
| `tune` ignores the round trip | *roundTrip multiplies the period*, *a half-length loop that inverts…*, *retune carries…* | the budgets should differ by half a period; 104.1 Hz for 110 |
| the blocker runs only under jawari again | *a loop with the blocker on drains a steady offset*, and the corner test | the loop stays at the offset instead of settling at the input |
| `reflected()` is not cached | *reflected then inject is next, taken apart* | "a second reflected() before inject must return the same wave", differing at sample 892 |

The corner test carries its own control: it budgets 20 Hz and runs 2 Hz on
purpose and requires the result to read audibly off, so a measurement that
could not see the mismatch would fail there rather than pass the guard for
nothing.

**A measurement lesson, kept because R1's tests will hit the same wall.**
The closed-cylinder test first failed — on the measurement, not on the
loop. It read the 2nd harmonic 16.6 dB under the fundamental where the
physics says far more, for two reasons that both belong to the ruler:
`PluckSpectra`'s Goertzel has no window, so a ringing fundamental leaks
into the harmonic an octave up; and a single noise burst has a random
spectrum at each harmonic, so a claim about one harmonic against another
cannot rest on a draw. The test now excites with a unit impulse (a flat
spectrum, so what comes out is the loop's own comb) and reads a
Hann-windowed FFT. Measured that way the 2nd harmonic is 97.6, 103.8 and
101.4 dB under the fundamental at 110, 220 and 440 Hz, and the 3rd at least
72 dB over the 2nd; the bounds (60 and 40 dB) sit well inside those. The
reason it is that clean is the one the design gave for a closed cylinder in
other words: a ring-down whose every half period is the last with its sign
flipped is half-wave antisymmetric, so an even harmonic can be nothing but
what the decay envelope and the window leave behind — where the design's
first justification, the driven comb's 32 dB resonance-to-anti-resonance
ratio, is a steady-state figure that a ring-down skips. The control beside
it, the same half-length loop with its sign kept, plays an octave up within
5 cents: the spec's defect reproduced in five lines.

**Size.** The design estimated about 100 hand-written lines (`Strings.kt`
+60, `StringsTest.kt` +40); it came to 128 changed lines in `Strings.kt`
(about 90 of them KDoc and comments, in the file's own habit of saying why
beside the code) and 264 added in `StringsTest.kt`. R0 plus R1's estimate
in "Phasing and gates" moves from about 1,850 to about 2,140 hand-written
lines (R0 at its built ~390 in place of ~100), most of the difference the
tests the design asked for.

**Not done, on purpose.** No BORE code; no audio change of any kind; no
roadmap row (S19 is added with R1, the house rule). R1's first open
question is unchanged: the cone's blocker corner, `f0/25` as measured or
the apex allpass, now a parameter and no longer a `Strings` change.

## R1, as built — 2026-09-29

R1 is the engine: `Bore.kt`, `BorePatch`, `BorePresets`, the registration, the kit and
the audition. It is built and every test is green (the whole JVM build — nine modules, 3,323 tests, none failing, none skipped (3,330 on the
merge with the default branch's VOX changes, the same result)). **Nothing in it has been
heard.** The constants were measured, the presets were authored from the measurements, and
the tests prove the loop speaks, in tune, bounded, and closes when it is asked to loop; none
of that says it sounds like a woodwind. The audition (`generateBoreAudition`, then the page)
is the gate, and its first job is to say which of these guesses is wrong.

**What landed.** `Bore.kt` (`BoreVoice { FLUTE, SAX }`, the five macros, `blow`/`render`, the
LOOP step, `landingChain`, `drumClassFor`, `scramble`), `BorePatch.kt`, `BorePresets.kt` (8 + 8),
registration in `Patches.kt` and `Presets.kt` and `Velocity.macroSpecsFor`, `SynthKits.bore()`
and its testkit kit (`SnipSnap Bore Kit`, 16 pads, 2.5 MB), `BoreKitGenerator` and
`BoreAuditionGenerator` with their two Gradle tasks and the listening page, the blocklist terms,
`BoreTest` and `BorePresetsTest` plus the canaries in `DeterminismTest`, `PadRecipeTest`,
`PresetsTest` and `SynthKitTest`, and roadmap row S19. `Velocity` needed no override: none of
BORE's five macros is a brightness macro Velocity knows, so a soft note goes through `soften`,
as VOX's does. No change to `Strings.kt`, `Keys.kt`, `Siren.kt` or the app.

### What R1 measured, and what it changed in the design

The design's Phase 0 proved the loop; R1 swept the knobs. A print-only probe (kept out of the
tree, its method now in `BoreMeasure`) read BREATH, LIP and TUNE per voice on the raw loop:
Hann-windowed harmonics, autocorrelation pitch, onset envelopes, aperiodicity. Eleven things
came out different from the design, and each is in the KDoc of the constant it changed.

1. **SAX's root is C3, not C2.** A reed's onset is a number of periods, so it is slow low down:
   0.3-0.4 s to 80% of steady at 131 Hz at the default knobs (0.63 s at the tightest lip and
   hardest breath), 0.4 s at 92 Hz, over 0.8 s at 65 Hz. A baritone's octave would be a stab
   that never speaks. R2 needs a faster starter to bring it back.
2. **LIP's tight end is offset 0.78, not 0.85.** At 0.85 the speaking window shrinks to a
   sliver and the note to a tenth of the amplitude. The *measured* speaking threshold (a share
   of the closing pressure) is 0.60 at the loose end to 0.82 at 0.85, not the small-signal
   prediction's 0.55 to 0.675: BREATH's floor sits a margin above the fit, not the formula.
3. **FLUTE's bore cutoff is a multiple of the note** (2x to 6x, LIP and BREATH moving it), not
   a fixed 6 kHz. The jet's tanh is a hard limiter: with a fixed bell a C4's 3rd mode took over
   above p = 0.8 and jumped an octave and a fifth; with the ratio the fundamental leads in all
   175 combinations, and the timbre follows the note. The jet's rest offset is never 0: a jet
   dead-centre on the edge is perfectly symmetric and stays silent.
4. **The jet's delay is fractional.** Rounded to a whole sample it moved the played pitch in
   quarter-percent steps, and the LOOP's measure-and-correct retune oscillated instead of
   converging.
5. **What is heard is the bore wave, not the sample the valve injects.** The injected sample
   carries the mouth's DC pressure, which after the output high-pass is a sub-200 Hz swell at
   every onset. The classifier read 8 of the first 16 presets as a snare or a clap (the head
   window's magnitudes, summed, are what it counts). Tapping the wave returning from the bore,
   which the loop's DC blocker has already cleaned, put all 16 on PERC or LOOP with no other
   change, and is also plainly what an instrument radiates.
6. **Vibrato is pitch, not pressure.** The spec's pressure wobble moved the pitch by under a
   cent, and by the same with HOLD at 0 — dead code that claimed a feature. It is now the loop's
   length, retuned every 64 samples through `Strings.Loop.retune`, ±20 cents at 5.2 Hz, scaled by
   HOLD (measured 25-35 cents peak to peak at HOLD 0.98, 13-19 at 0.5, none at 0).
7. **The breath noise had to be raised to be there.** At the spec's 0.02-0.06 the aperiodicity
   was -46 to -55 dB, inaudible. FLUTE now runs 0.05 to 0.6 (about -50 to -28 dB at C5), the reed
   0.03 to 0.3 (about -62 to -43 dB); above about 0.7 a tight reed stalls. Listening values.
8. **Two starters were added, both measured.** A 4 ms pressure pulse when the swell reaches
   speaking pressure (a reed's onset otherwise grows from a seed of about 1e-3 of its amplitude;
   the pulse seeds it a hundred times higher: 0.43 s to 0.28 s at 185 Hz on the first noise
   settings, and the test now takes every other seed away and asks for a saving of at least
   50 ms), and a burst of breath noise on the front of the note scaled by CHIFF. With the noise
   as high as it now is the noise seeds the reed too, so the pulse is for the soft end.
9. **BREATH brightens the bore explicitly** (the bell's cutoff moves 0.8x to 1.3x on the reed,
   0.9x to 1.2x on the flute). Loudness is levelled away, so a harder breath has to be timbre;
   the reed's own nonlinearity moves the 2nd harmonic 8 dB and the jet's nothing.
10. **The pitch is pinned**, not budgeted: the nonlinearity pulls SAX flat and FLUTE sharp, and
    a fixed pin (+4 and -3 cents) leaves the worst case in the 175-combination sweep at
    FLUTE -1.7..+3.5 cents and SAX -3.5..+4.1. The LOOP retunes exactly instead.
11. **The seam check that meant something.** The obvious check, `Keys.seamError` on the loop
    played twice, is tautological (a loop followed by itself is periodic whatever is in it) and
    reported 0.00 on loops that were two whole periods off. The check that means something
    compares the kept steady stretch with itself one loop later; see below.

### The LOOP step

HOLD's top step is a seamless two-second loop of the held note, in whole periods and whole
frames, rendered dry: steady pressure, **no turbulence and no vibrato** (a loop cannot carry a
signal that does not repeat), the warm-up discarded, the *played* pitch corrected until K
periods fill the frames exactly, then cut where the two neighbours are smallest (SIREN's
`bestCut`) and levelled. The pitch correction measures the loop's own length by climbing a
ladder of lags (1, 2, 4, ... K periods): one jump from one period to K cannot be trusted, since
a jet's near-square wave has a triangular correlation peak and a parabola through a triangle is a
quarter sample off, which times 740 periods is a whole period. The warm-up scales with periods
(at least 400), not seconds: a loose reed at full breath was still creeping at 131 Hz after
3 s. A render that cannot close throws, rather than shipping a click.

Measured on 70 corners (both voices, seven notes, five LIP/BREATH corners): the worst seam is
1.8e-4 against the Organ's bar of 1e-3, and over `BoreLoopFuzzTest`'s 100 loops — every one of the
25 TUNE steps of both voices, at the defaults and at a seeded random LIP, BREATH and CHIFF — the
worst is 3.3e-4. A render takes 0.15-0.43 s, and the loop is exactly TUNE by construction (the
retune's fixed point). The first build reported 23 of 60 corners over
the bar, then 19, then 2, then 0: the wrong-period lock (a narrower search, then the ladder),
the quantised jet, and the warm-up, in that order.

### The presets, and what the classifier says

Sixteen, eight per voice, from the measurements: FLUTE speaks everywhere (onset 0.04-0.31 s), so
its presets differ by register, breath noise, brightness, attack and length; SAX is slow low down,
so its short, hard-tongued reeds sit high and its low ones are swells and held notes. One
preset of each voice is HOLD 1 and therefore a LOOP (STEADY LOOP, SOLO LOOP). They are **provisional**: the six-contract
test freezes them as sound, not as good.

The classifier reads every preset of the final roster PERC or LOOP; none reads a drum. (Two low
SAX presets read TONAL at an intermediate setting, and the first roster read 8 of 16 as a snare or
a clap; both are why the rule below is what it is.) The filed class is exact where the rule is exact — over 1.5 s is a LOOP, decided by the same
comparison the classifier makes (`frames.toFloat() / RATE > 1.5f`) on the exact frame count a render
makes, not on a padded duration (review found the padded version filing a 1.49998 s note a LOOP; a
boundary sweep and the real classifier now hold it) — and files PERC below it. TONAL is the classifier's bass gate (over 55% of the head window's magnitude under
200 Hz, ringing past 500 ms), which a fundamental's own skirts supply at C3-A3; it depends on
the spectrum, which macros alone do not promise, so `drumClassFor` does not predict it and the
test accepts PERC or TONAL for a one-shot. Noisier settings than the roster's read as CLAP or
SNARE (the reed's LOW HONK at BREATH 0.85 did): the roster stays at the settings that do not,
which is a fact about the classifier's magnitude sums and not about the sound.

### Verification

`BoreTest` (22 claims), `BorePresetsTest` (10), `BoreLoopFuzzTest` (1), the kit test, and the
canaries above. Each guard was broken in turn and the test that should catch it did (ten
mutations, ten kills): no pitch correction in the LOOP (killed by the seam, steadiness and length
tests), the flute's bell fixed at 6 kHz (the fundamental-leads and LIP tests), no pitch pin (the
7-cent test), no tongue pop (the onset tests), no vibrato (the vibrato test), a thump below
CHIFF's threshold (the thump test), the output tapped at the valve again (the preset
classification test — the finding that changed the design), a BREATH floor under the speaking
threshold (the onset, pop and LOOP tests), a whole-sample jet delay (the LOOP tests), and the
warm-up counted in seconds only (the seam test).
The measurement helpers (`BoreMeasure`) read the raw loop so `Dsp.levelTo` cannot lift a silent
render to the loudness target and hide it.

### Not done, on purpose, and known limits

- **The phone:** no picker entry, no README count, no `→ SURFACE ▸`, no Web Audio stand-in on
  the listening page (R1.1, as designed). The page's clips are all rendered on the desktop.
- **Held instrument and keys** (`Keys.borePad`, MAKE INSTRUMENT, the one-shot `Keys.bore`): R2.
- **A drum program plays every pad once through, LOOP-class included** (`Loop=False`, as SIREN's
  LOOP pads are): the kit's A12 and A16 end cleanly, they do not repeat while held. The wrap is
  heard in the audition page's REPEAT now, and held on a pad once R2's held instrument exists.
- **SAX is slow below about C4** (item 1) and its BREATH changes the 2nd harmonic (-11 to -19 dB)
  more than it brightens; a tight reed is quieter and slower than a loose one (raw level a fifth,
  onset 0.63 s at C3 at full breath).
- **FLUTE's LIP and BREATH move the timbre by 4-8 dB** in the 2nd-5th harmonics: audible,
  moderate. The breath noise is what BREATH does most.
- **The LOOP is dry, noiseless and vibrato-free** — a whole-period wrap cannot carry noise.
  The audition asks whether a breathless loop is a pad or a synth.
- **Guesses waiting for the audition:** the turbulence ranges, the chiff burst, the thump's level,
  the reed's bell, `LANDING_TAPE` (WOBBLE .1 against .2 is on the page), the pop.
- **CPU:** Phase 0 measured a one-shot at 26-60 ms per second of audio and R1 has not re-measured it; a LOOP takes 0.15-0.43 s to render (it renders 3-5 s of steady stretch, four times, to converge the pitch).

## R1, the reed's bite — 2026-09-30

The first note back from the audition owner: *the SAX needs a bit more of the bite of a reed.* Nobody
has listened to the answer either; every number below is a measurement of the rendered audio.

**What bite was measured as.** The energy from 1 to 4 kHz against the fundamental's, in dB, on the
finished render (`BoreMeasure.biteDb`; a Goertzel sweep in 16 Hz steps). The R1 reed at the default
knobs read -24 -22 -19 -16 -15 dB at C3 G3 C4 G4 C5.

**What was tried, on the raw loop.** The reed's bell (2500 Hz) doubled: +6 to +11 dB of bite, uneven
across the register, and the biggest lever. The loop gain 0.95 to 0.975: it raises the raw level and
helps the high notes, small elsewhere. The reflection table's slope: **nothing tonal** (the output is
levelled, so only the raw level moves); it was dropped. A looser LIP: a modest +1 to +2 dB.

**Why the bell did not ship in the loop.** A brighter bell keeps more of the upper modes alive in the
pipe, and the LOOP step then has more corners where the held note does not repeat. A scan of 117 SAX
corners (TUNE every second semitone, LIP and BREATH each at 0, 0.5 and 1, HOLD 1) counted the corners
that do not close as a loop: **2** at the bell R1 shipped (2500 Hz), 7 at 3500, 10 at 4000, 16 at 5000.
The bite is better made where it cannot reach the loop.

**What shipped: a presence bell on the output.** `Bore.BITE_DB` (12 dB) at `BITE_HZ` (2 kHz, Q 0.7),
for the reed voice only, eased to `BITE_TIGHT_SHARE` (0.4) of that in dB as LIP goes from 0 to 1, so a
pinched reed stays mellow and a loose one buzzes (LIP already dropped the 2nd harmonic 8 dB the same
way). It sits after the pipe, in `condition`, a linear filter: a periodic wave through it is still
periodic. At the default knobs the bite reads -18.7 -16.4 -13.4 -10.5 -9.7 dB, **+5.3 to +5.6 dB at
every one of the five notes**; at LIP 0.1 it reads -13 -11 -9 -8 -6, at LIP 0.9 -26 -25 -20 -14 -14.
The audition renders four strengths of the same phrase (none, half, this, one and a half times) so the
gain is a choice made by ear. It is an output radiation stage, not a model of the reed's buzz, and it
may sound like an equaliser; the audition's first question is whether it does.

**What it cost, said plainly.**

- The same 117-corner scan with the bell: 3 corners do not close (the 2 R1 already had, plus one
  marginal at 1.3e-3 against the bar of 1e-3, at D3 with LIP 0 and BREATH 0), and 11 of the rest are
  over 2.5e-4 (7 before). The fuzz test's worst seam is 5.1e-4 (3.3e-4 before). The claims test's
  bound for its 18 corners went **from a quarter of the bar to half**: its worst corner (LIP 0,
  BREATH 1, C4) reads 2.8e-4, and the bell weights the top partials, where a loose, hard reed's
  residual drift shows first. The loop still closes at every corner the tests and the fuzz visit; the
  margin is 2x, not 4x.
- **A limit R1 already had and did not know.** At 2 of the 117 corners (C3 with LIP 0.5 and BREATH 1;
  G#3 with LIP 1 and BREATH 1) the LOOP does not close, seam 0.54 and 0.09. The pitch correction
  converged to 0.000 cents, so the length is right; the stretch simply does not repeat 262 periods
  later. The reed's amplitude there wanders slowly (about 0.5% over roughly 3 s), and the guess is the
  slow relaxation of the reed's operating point (`SAX_BLOCKER_DIVISOR`'s trade-off); the guess was not
  tested. The render throws rather than ship a click, and the phone has no way to reach it in R1 (no
  picker entry, no scramble into the LOOP step); a caller of `Bore.render` at HOLD 1 and those knobs
  will see the exception. R1's "70 corners, worst 1.8e-4" was true of the corners it visited.
- The classifier reads brightness: GROWL crossed its SNARE line (`highRatio` 0.50 against 0.5) and
  LOW HONK reached 0.49. Both lost tongue (LOW HONK CHIFF 0.5 to 0.35; GROWL CHIFF 0.5 to 0.35 and
  BREATH 0.85 to 0.75, still a hard reed): now 0.45 and 0.46, BITE 0.46. The margin is 0.04, thin, and
  it is a fact about the classifier's magnitude sums and not about the sound.
- The LOOP's pitch correction is now adaptive: up to five passes, stopping when the measured length is
  within 3e-7 of the wanted one (three passes left 4e-7 at 262 periods). This did not move the worst
  seam; it is kept because it costs nothing when the first passes converge.
- **The committed testkit was stale.** Regenerating `testkit/SnipSnap Bore Kit` from the unmodified
  code changed 15 of its files, flute pads included, so it had not been rebuilt after R1's last engine
  edits. It is regenerated here, from this change's code.

**Verification.** `BoreTest`'s "the reed has bite, and a looser lip has more of it" (floors 2-3 dB under
the measured -18.7 to -9.7, and a 9 dB LIP span) and "the bite bell is the reed's, eases as LIP
tightens, and never touches the flute" (arithmetic on `biteBoostDb`). Each guard was broken alone and a
test named it: the bell at 0 dB, the bell the same at every lip, the bell on the flute too. The full
JVM suite passes.

### Round 1.2: the rasp — 2026-09-30

The bell shipped, and the owner heard all four strengths of it, STRONG (+18 dB) included, and the note
came back: *still needs more bite ... buzzier and raspier.* A bell can only lift what the pipe already
made; a reed's buzz is *new* harmonics. So the second step makes some.

**What shipped: a soft clip after the pipe, on one-shots.** `Bore.raspBend`: `tanh(drive * (x + bias))`,
in place, at the oversampled rate and ahead of the band limit (what it makes above the audio band is
filtered, not folded back), before the presence bell. The wave is first scaled by its own
loudest-200-ms level, so the drive is a fact about the knobs and not about how hard the loop ran (a
sine at 0.01 and at 0.5 come out the same to within 1 dB). Small-signal gain is 1. The amount is the
reed's looseness times its breath (`raspAmount`: 1 at LIP 0 and BREATH 1, `RASP_TIGHT_SHARE` 0.25 of it
at the tightest lip, `RASP_SOFT_SHARE` 0.4 at the softest breath), the drive runs from 1 to
`1 + RASP_DRIVE` (3), and the flute has none.

**The bias was measured, not chosen.** A symmetric `tanh` makes odd harmonics only, a clarinet's hollow
sound. On a sine at the default knobs' amount (0.475), a bias of 0.15 left the 2nd at -27 dB and the 3rd
at -11.9; 0.4 gives -18.5 and -12.4 with the 4th at -22 and the 5th at -22; 0.8 turns it over (2nd -12.5,
3rd -14.7). `RASP_BIAS` is 0.4. The bite band barely tells them apart (the numbers below moved by
0.1 dB), so this is a fact about the harmonics and not about the bite metric.

**What it did, rendered SAX.** Bite at C3 G3 C4 G4 C5 (dB, 1-4 kHz against the fundamental):

| | C3 | G3 | C4 | G4 | C5 |
|---|---|---|---|---|---|
| R1, no bell | -24.0 | -22.5 | -18.7 | -15.5 | -14.7 |
| the bell (last round) | -18.7 | -16.4 | -13.4 | -10.5 | -9.7 |
| bell and rasp (now) | -9.5 | -7.4 | -5.4 | -3.4 | -2.6 |
| now, LIP 0.1 | -5.5 | -3.2 | -1.3 | -0.5 | +0.4 |
| now, LIP 0.9 | -16.5 | -15.4 | -11.7 | -7.3 | -6.5 |

That is 7 to 9 dB over the bell, a little more at the bottom of the register than the top, and 12 to 15 over R1. The gain saturates: at
a tenth of the amount it is already 5.6 dB (a tenth still bends a wave normalised to its own loudness),
at the full amount 9, and at twice it 10.7, which is why the audition's ladder is a tenth, 0.4 and 1, not
1, 1.5 and 2. With the bell off the rasp alone reads -15.8 -13.9 -11.4 -8.8 -8.4 (measured at the earlier bias of
0.15), about 6 dB under the two together; the audition includes it to ask whether the bell is still wanted.

**What it cost, said plainly.**

- **No rasp in a LOOP.** The bend keeps a periodic wave periodic, but it flattens the tops and steepens
  the edges, and an edge is where timing error shows: the same slow drift that a sine hides moves a
  clipped wave's edge by a step. On the 142-corner SAX scan (the 117 grid and the fuzz set) the corners
  that do not close as a LOOP went from 3 with no rasp to 9 at 0.15 of the amount, 10 at 0.3 and 11 at
  0.6, and at the full amount the fuzz test's SAX corner (step 1, LIP .33, BREATH .23) read 2.2e-3
  against the bar of 1e-3. A LOOP therefore carries the bell and no rasp, and closes exactly as merged
  (the fuzz test's worst seam is unchanged at 5.06e-4). SOLO LOOP is less raspy than the one-shots it
  is named after. A bent loop wants a crossfaded wrap, which is R2's.
- **The classifier's margin, again.** All seven SAX one-shot presets still read PERC or LOOP; the closest
  to the SNARE line are GROWL 0.47, LOW HONK 0.46 and BITE 0.45 (line 0.5), where the bell alone left
  0.46, 0.45 and 0.46. HIGH STAB's attack-burst count rose from 5 to 7, with flatness 0.19 against a
  CLAP's 0.35.
- **It may sound like a fuzz pedal.** LIP 0.1 reads a bite of about -1 dB, the band as loud as the
  fundamental. Nobody has heard it. The rasp constants (`RASP_DRIVE`, `RASP_BIAS`) are guesses waiting
  for the audition's ladder.

**Verification.** `BoreTest`: the rasp's harmonics on a sine (none at amount 0; the 2nd, 3rd and 4th over
-22, -16 and -26 dB at 0.475; the same at two levels), its amount (loose and hard, never the flute's),
and the bite floors moved to -12 -10 -8 -6 -5.5 dB. Each guard was broken alone and a test named it: no
rasp on the reed (the floors and the amount test), no bias (the sine test), no level scaling (the floors
and the sine test), the rasp on the flute (the amount test), the rasp in the LOOP (the LOOP claims test
and the fuzz test).

### Round 1.3: the voicing — 2026-10-01

The rasp merged, and the owner asked for research on what was still missing ("a missing ingredient that we
haven't nailed"). The research was done against real instruments, not against the literature alone, and it
changed what the SAX is trying to be.

**What was measured.** UNSW's saxophone acoustics site (Wolfe's group) publishes recordings of a real tenor
for every note (22.05 kHz, 16-bit mono) and the same note at four dynamics. Thirteen notes, written A#3 to F5,
and the four A#3 dynamics were analysed the same way as our renders (the loudest 0.4 s, a Hann FFT, bands in dB
against the whole sound). Against our SAX at the same pitch (their written D4 sounds C3 = our TUNE 0):

| | centroid | 50-250 | 250-500 | 500-1k | 1-2k | 2-4k | 4-8k | strongest partial | onset to 80% |
|---|---|---|---|---|---|---|---|---|---|
| real tenor | 866 Hz | -16.0 | -7.9 | -2.1 | -9.2 | -12.0 | -18.2 | 5th | 0.07 s |
| ours, R1 | 164 Hz | -0.5 | -10.4 | -16.3 | -25.6 | -39.9 | -54.5 | 1st | 0.45 s |
| ours, with the bell and rasp | 300 Hz | -1.2 | -10.3 | -12.0 | -12.0 | -18.5 | -33.6 | 1st | 0.34 s |
| ours, with the voicing | 1113 Hz | -9.2 | -7.0 | -6.4 | -5.4 | -8.4 | -18.8 | 3rd | 0.38 s |

Across the thirteen real notes the fundamental was the strongest partial in **one** (the 2nd to 7th in the rest),
the 50-250 Hz band held 12-16 dB less than the whole sound where the note's fundamental lay under 175 Hz, and the
onset was 0.05-0.12 s (0.49 s for a very soft note). Across the four dynamics the brightness centroid went
327, 566, 653, 711 Hz from very soft to very loud and the 4-8 kHz band went -35, -31, -22, -20 dB: the same note
is much brighter the louder it is, which the UNSW page explains by the reed beating (it closes for part of the
cycle and the wave is clipped on one side), and which our BREATH did not do (the centroid moved 285 to 310 Hz).
The same page gives the tone-hole lattice's cutoff, about 800 Hz on a tenor and 1300 Hz on a soprano, above which
"the harmonics fall off" in the low notes. Our brightness work until here (the bell at 2 kHz, the rasp) had been
adding bite in one band of a spectrum whose whole shape was wrong: a strong fundamental with the rest far under it.

**What shipped: a voicing on the output.** `Bore.voice`: a low shelf cutting the fundamental region
(`VOICE_LOW_DB` -26 under `VOICE_LOW_HZ` 200) and a high shelf lifting everything over `VOICE_HIGH_HZ` 3 kHz by
`VOICE_HIGH_DB` 18, rolled off above `VOICE_TOP_HZ` 9 kHz (a real recording has little there, and the 22 kHz of a
44.1 kHz render is not a place to lift). The numbers are the owner's: a prototype filter on the rendered reed,
at five settings, got KEEP for the deeper cut (-20 dB) and for the brighter lift (+18 dB), MEH for the plain cut
and the plain lift, and CUT for the version without the rasp; the shipped values are the two keeps together, with
the cut taken a little further to reach the real band balance (measured against the recording, not by ear).
`voicingFor` gives it to the reed alone; the flute was not measured.

**It follows the note's loudness.** The first build applied the shelves at full strength from the first sample,
and every SAX one-shot preset then read as a SNARE or a CLAP to the drum classifier (a high-frequency share of
0.67-0.89 against its line of 0.5). That matters here: when a pad lands, the app files it under the classifier's
class, which sets its choke group, so a SAX read as a snare would mute real snares. The classifier looks at the first
4096 samples, which for this reed (a 0.3-0.4 s onset) are the tongue's pop and the breath burst while the tone
grows; lifting the highs at full strength lifts that noise. Nor was it the sample rate, the roll-off, or the
chiff (a grid of two cuts by four lifts, with and without the burst, said the same). So the shelves open with
the envelope (`VOICE_FOLLOW_HZ` 1 kHz tone, 10 ms smoothing - 40 ms and a bloom since Round 1.4 - nothing at the start of the note and all of it at the
loudest): which is also what the instrument does, brighter as it gets louder. Only the two shelves open; the 9 kHz
roll-off is a fixed band limit at every level. Blending it in by the envelope too (Copilot's review suggested it, and it
would make the note's start transparent at every frequency) was tried: the pop and the breath burst keep their top end
and BITE reads a SNARE (0.53), LOW HONK 0.49, so it stays fixed, as a recording's own band limit is. On the same grid, the real tenor
reads PERC or LOOP at its low notes (a high-frequency share of 0.36-0.47; two of thirteen notes read SNARE).

**What it cost, said plainly.**

- **No voicing in a LOOP.** On the 142-corner SAX scan (the 117 grid and the fuzz set) the corners that do not close
  as a LOOP went from 3 (the merged reed, no rasp) to **19-21** with the voicing in the loop, with or without the
  9 kHz roll-off, with fuzz corners at 3.2e-3 and 5.8e-3 against a bar of 1e-3. A LOOP carries the bell and neither the rasp nor the voicing,
  so SOLO LOOP and the loops of the roster are darker than the one-shots they are named after. A bent, lifted loop
  wants a crossfaded wrap; it is the same R2 item as the rasp's.
- **The classifier's margin is thin and it is not monotonic.** With the voicing the SAX one-shot presets read
  0.27-0.46 (BITE 0.46, HIGH STAB 0.39, SMOOTH 0.40, LOW HONK 0.43, GROWL 0.40, line 0.5), but a preset's reading
  moves up and down by 0.03-0.05 with CHIFF (LOW HONK: 0.43 at 0.15, 0.50 at 0.45) and the next sound design step can
  push one over. Two presets were retuned to sit lower, as provisional presets are: LOW HONK (BREATH 0.7 to 0.6,
  CHIFF 0.35 to 0.15) and GROWL (BREATH 0.75 to 0.65, CHIFF 0.35 to 0.15).
- **The attack is not fixed.** Ours is 0.34-0.45 s to 80% at C3 against the real 0.05-0.12 s, and it is the likely
  root of the classifier problem above (a head window of pop and burst). A faster starter is R2. *(Done in Round 1.4: 0.04-0.10 s.)*
- **Not done, from the same measurements:** BREATH-linked brightness (the real centroid moved 327 to 711 Hz from
  soft to loud; the voicing's loudness-following is within one note, not across the BREATH knob), and the noise
  floor between the harmonics (valley to peak at 1-4 kHz: real -28 to -36 dB, ours -48 to -56 dB).
- **One recording, one player.** Thirteen notes of one instrument, close-miked; the numbers above are the shape of
  that instrument and not a law. The recordings are UNSW's; they are not in the repository.

**Verification.** `BoreTest`: the bite floors moved to +3.0 +2.0 +1.0 -0.5 -1.0 dB (measured +6.0 +4.9 +3.9 +2.3
+1.8), the fundamental region at least 5 dB under the whole sound (measured -9.5 at C3 and -7.9 at G3, against -1 for
the merged reed), the voicing's envelope (a 130 Hz and a 4 kHz tone ramped up over 0.4 s: within 2 dB of unity in the
first 30 ms, the 130 Hz cut and the 4 kHz lifted at full level), and the flute's none. Each guard was broken alone and
a test named it: no voicing (the floors, the share and the envelope tests), a static voicing with no envelope (the
envelope test and the classifier test, SNARE), the voicing on the flute (the classifier and the share tests), the
voicing in the LOOP (the LOOP claims test and the fuzz test), no rasp and no bell (the floors).

### Round 1.4: the attack — 2026-10-01

The voicing merged, and the owner chose the attack as the next fix: "Let's fix the attack speed next". Round 1.3
had measured it as the largest remaining gap between this reed and a real one, and as the likely root of the
classifier's thin margin (the head window is the tongue's pop and the breath burst while the tone grows).

**What was wrong.** A real tenor reaches 80% of a note's level in 0.05-0.12 s (a very soft note, 0.49 s). This
reed took 0.44 0.30 0.22 0.16 0.11 s at C3 G3 C4 G4 C5 at the default knobs, and the same number of *periods*
(about 58) at every pressure, overshoot and loop gain that was tried: a reed that starts from a tiny seed grows by
a fixed factor per period, so a low note is slow in seconds. CHIFF's attack ramp, the pop and the breath burst did
not help, because the growth, not the drive, was the clock. Every one-shot swelled where a saxophone attacks.

**What shipped: the tongue's seed.** A short tone at the note's own frequency is put straight into the bore at the
tongue's release (`TONGUE_SEED`, 8 periods under a raised-cosine window, starting at the pop). It starts the growth far
above the turbulence's seed (about 1e-3 of the steady level), which takes the onset to 0.10 0.07 0.06 0.05 0.04 s at
the same notes, with the played pitch and the steady level unchanged to a cent and a percent. Its amplitude is the tongue's: `TONGUE_SEED x CHIFF^0.6 x lin(BREATH, 0.1, 1) x (pressure / 1.04)^2`, so
CHIFF 0 is still the old slow swell (0.40 s at C3; the tongue's release is what speeds a note, and it does not
tongue), 0.19 0.10 0.06 0.04 s at CHIFF 0.2 0.4 0.7 1, and a BREATH 0 note stays soft and slow (0.31 s; 0.07 s at
BREATH 1). The squared pressure term is the reed's own level (the loop's steady level goes about as that square): a
fixed seed overshot a tight reed's steady level 1.7 to 2 times; and the CHIFF curve is under 1 because a seed linear in
CHIFF overshot 1.65 times at CHIFF 1. It is capped at `TONGUE_SEED_MAX` 0.5: past it,
at the loosest lip with the hardest breath and tongue, the raw peak went from the steady 2.3 to 3.9 and 4.1 against
the test's bound of 3.

One thing that did not work, kept so it is not tried again: the seed through the mouth pressure. The pressure
enters the bore with the weight (1 - r), about 0.07 where the reed table sits, so half the mouth pressure for six
periods only took C3 to 0.28 s; put into the bore the same tone took it to 0.19 s at 0.2 for four periods and to
0.10 s for eight. The FLUTE takes no seed (the jet has its own onset, 0.13 s at
C3, seeded or not), nor does a LOOP's steady stretch: a loop cannot carry a signal that does not repeat, and its
warm-up is discarded anyway.

**The classifier, and a first attempt that was wrong.** With the tone arriving in 0.1 s, the first 93 ms (all the
classifier looks at) is a developed tone, and HIGH STAB, the brightest preset, read 0.63, a
SNARE: the voicing followed the loudness linearly, so it was already half open at 40 ms. The tone should not be
fully bright until it is fully loud, and the UNSW recordings say the same (the harmonics grow faster than the
fundamental as a note gets louder), so brightness now follows the level to a power: `VOICE_BLOOM` 2, with the
smoothing lengthened from 10 to 40 ms. The first version took the square of the plain envelope-to-peak, and two
existing tests named what it broke: C3's bite fell from +6.0 dB to +1.8 and the voicing's full-level lift from
+8.2 to +5.8. The envelope follower reads a *raw* block RMS (64 samples) of a low tone, which ripples within the
cycle (a period is 339 samples at C3), so its smoothed mean sits at 0.72 of the raw peak at C3, 0.88 at C4, 0.95 at
G4: a low note never reached "full" voicing, and squaring that took C3's plateau to 0.51. So the bloom is relative
to the note's own ceiling, `a x (a / ceiling)^(BLOOM - 1)`: the plateau is the plain amount, exactly what the voicing
was fitted at and the bite floors measured at, and only the way up and down is bent. On the voicing test's ramp the
4 kHz lift at the halfway point is +0.3 dB (it was +3.4) and the plateau +8.2 dB is unchanged.

**Measured.**

| | C3 | G3 | C4 | G4 | C5 |
|---|---|---|---|---|---|
| onset to 80%, before | 0.44 s | 0.30 s | 0.22 s | 0.16 s | 0.11 s |
| onset to 80%, the seed | 0.10 s | 0.07 s | 0.06 s | 0.05 s | 0.04 s |
| bite (1-4 kHz vs the fundamental), from 0.5 s | +6.2 dB | +5.0 | +3.9 | +2.2 | +1.8 |

The bite row is the voicing round's own (+6.0 +4.9 +3.9 +2.3 +1.8), unchanged to the measurement. The six SAX
one-shot presets read PERC at 0.16-0.44 against the line at 0.5 (HIGH STAB 0.44, LOW HONK 0.37, GROWL 0.36, BITE 0.31,
AIRY REED 0.21, SMOOTH 0.16); PAD REED, the loop, reads LOOP.

**What it cost, said plainly.**

- **The classifier's margin is still thin where it was thin.** HIGH STAB, the brightest knobs, reads 0.44 against 0.5;
  the readings are better than the voicing round's overall (0.16-0.44 against 0.27-0.46), but a preset that is both
  loud and bright is one design step from a SNARE.
- **Not heard.** The numbers say the attack is a tenor's; whether it sounds like one is the audition's question (the
  new section, ATTACK, is the same phrase at no seed, a light one, the shipped one and a stronger one, and the
  kit's SAX pads).
- **A hard tongue is an accent now.** At CHIFF 1 the note overshoots its steady level (about 1.3 times) before
  settling, since the seed arrives while the reed is still being pushed; the level stage hides it, but it is in the
  raw wave, and the bounded test's 3 is a ceiling with room.
- **The high notes are slightly fast.** 0.04 s at C5 against the real 0.05-0.12 s; the same seed is a share of a
  shorter note.
- **Still open, from the same measurements:** BREATH-linked brightness (the real centroid moved 327 to 711 Hz from
  soft to loud across a player's dynamics; ours moves within a note, not across the knob), the noise floor between
  the harmonics, and the crossfaded wrap that would let a LOOP carry the rasp and the voicing (so loops are still
  darker than the one-shots).

**Verification.** `BoreTest`: a note at the default knobs speaks within 0.16 s at C3 and 0.12 s elsewhere, and the
seed saves at least 0.05 s at all five; CHIFF's onset shortens at every step (0.40 to 0.04 s) and a BREATH 0 note
is still slow; a flute blown with the seed forced on is the flute without it, to the sample; the voicing's bloom
(the 4 kHz lift halfway up is under a quarter of the plateau's, the plateau at least +7.5 dB and -13.5 dB). Each
guard was broken alone and a test named it; the list is in the pull request.

## Appendix A — the probe's tables (the spec's engine, as transcribed)

`Bore.kt` verbatim from the spec (its pages 5–11) with one `.toFloat()`,
rendered in a throwaway worktree by nine print-only probes; the probe is
not kept, since the spec is its source. Defaults: TUNE 0.5,
BREATH 0.65, EMBOUCHURE 0.5, VIBRATO 0.3, DECAY 0.5, MELLO 0.

**A1 · Analytic loop frequencies** (renderRate 176,400):

| Voice | f0 | delaySamples | comb fundamental `rate/D` (+0.94) | inverting `rate/2D` (−0.94) |
|---|---|---|---|---|
| BARI | 110.00 | 801.82 | 220.0 | — |
| BASSOON | 116.54 | 756.82 | 233.1 | — |
| HECKEL | 130.82 | 674.21 | 261.6 | — |
| FLUTE | 440.00 | 200.45 | 880.0 | — |
| GUANZI | 220.00 | 200.45 | — | 440.0 |

**A2 · Tuning** (FFT peak, Blackman–Harris, middle 50 %, DC removed):

| Voice | TUNE | expected | FFT peak Hz | ratio |
|---|---|---|---|---|
| FLUTE | 0 / 0.5 / 1 | 220 / 440 / 880 | 437.15 / 867.32 / 1708.24 | 1.987 / 1.971 / 1.941 |
| BARI | 0 / 0.5 / 1 | 55 / 110 / 220 | 73.07 / 331.02 / 1523.18 | a limit cycle; DC-removed `Pitch.detect` 1025.6 / 109.4 / 217.2 |
| BASSOON, HECKEL, GUANZI | any | — | NaN (the lowest scanned bin) | — |

**A3 · Defaults:**

| Voice | peak | RMS | mean (DC) | non-finite | Classifier | centroid / rolloff Hz |
|---|---|---|---|---|---|---|
| BARI | 0.950 | 0.622 | +0.564 | 0 | PERC 0.40 | 73 / 4673 |
| BASSOON | NaN | NaN | NaN | 1852 (sustain exactly 0) | UNKNOWN | — |
| HECKEL | NaN | NaN | NaN | 1852 | UNKNOWN | — |
| FLUTE | 0.950 | 0.745 | +0.014 | 0 | PERC 0.40 | 946 / 2616 |
| GUANZI | NaN | NaN | NaN | 40049 of 41251 | UNKNOWN | — |

**A4 · Raw oversampled buffer, defaults** (attack 7,056 frames; release from 150,895 of 165,007):

| Voice | finite peak | mean | first > 1 | first non-finite | non-finite count |
|---|---|---|---|---|---|
| BARI | 1.043 | +0.618 | 42.6 ms | never | 0 |
| BASSOON | 3.3e37 | +3.5e32 | 872.1 ms | 893.7 ms | 7364 |
| HECKEL | 1.9e37 | +1.4e32 | 874.5 ms | 893.7 ms | 7366 |
| FLUTE | 16.22 | +0.235 | 16.8 ms | never | 0 |
| GUANZI | 9.0e37 | −5.5e34 | 3.7 ms | 27.5 ms | 160153 |

**A5 · Corners** (BREATH × EMBOUCHURE at TUNE 0.5; DC-removed Pitch):
BARI finite at all six with pitch hopping 217 / 74 / 55 / 217 / 109 / 110
Hz; FLUTE finite and oscillating at all six, EMBOUCHURE raising the pitch
864.7 → 882.0 Hz; BASSOON and HECKEL NaN at five of six (the sixth, BREATH
1/EMB 0, AC RMS 0.001–0.01); GUANZI NaN at all six; BREATH 0 exactly
silent on every voice.

**A6 · Odd/even** (magnitudes at k·f0, DC-removed f0): BARI 6.76 : 1 (h3 >
h1 — a 220 Hz comb in a period-2 limit cycle); FLUTE 56.9 : 1 (a
fundamental at 2·f0 with weak odd partials); the rest NaN.

**A7 · MELLO on FLUTE:** MELLO 0 / 0.5 / 1 → centroid 948 / 977 / 1126 Hz,
rolloff 2616 / 3467 / 7623 Hz, pitch 864.7 at every setting.

**A8 · BREATH as velocity on BARI:** BREATH 0.25 / 0.50 / 0.85 → centroid
641 / 137 / 43 Hz, DC +0.28 / +0.49 / +0.63, pitch 109.4 / 74.1 / 73.6.

**A9 · Cost** (second call, JIT warm): BARI 37, BASSOON 37, HECKEL 48,
FLUTE 59, GUANZI 49 ms per rendered second; BARI at DECAY 1 (3.5 s)
55 ms/s; FORK DINNER JAZZ 88 ms/s.

**A10 · The replica, corrected loop** (`bore_sim2.py`, reproduced in `../plans/2026-09-28-bore-phase-0-spike.md`, re-run twice independently for this document): peak / DC / AC RMS / lowest peak vs f0 —
BARI 0/12/24: 0.090/0.0585/**0.0044**/−2.9 c · 0.098/0.0586/**0.0057**/+1.1 c · 0.276/0.0570/0.0693/+0.4 c;
BASSOON: 0.0040/+2.6 c · 0.0081/−2.2 c · 0.0719/−0.1 c;
HECKEL: 0.0047/−3.1 c · 0.0109/+1.4 c · 0.0623/−1.2 c;
FLUTE: AC 0.0050–0.0065, DC −0.375, lowest peak 35.35 / 35.39 / 13.92 Hz (no lock);
GUANZI: 0.493/0.0174/0.3140/−162.7 c (the peak listing; the zero-crossing probe reads 110.0 Hz within ±0.5 c wherever it speaks) · 0.4179/+1.8 c · 0.3950/+1.9 c.

**A11 · The threshold probe** (`bore_thresh.py`, reproduced in the same record; turbulence off, 1e-3 kick; AC RMS of the last 100 ms, then zero-crossing cents):

| Voice, semi, EMB | 0.20 | 0.30 | 0.40 | 0.50 | 0.65 | 0.80 | 1.00 |
|---|---|---|---|---|---|---|---|
| GUANZI 12 (220 Hz), 0.5 | 0 | 0 | 0 | 0 | **0.442** +0.9 | **0.523** +1.0 | 0 |
| GUANZI 12, 1.0 | 0 | 0 | 0.029 −0.1 | 0.351 +0.6 | 0.429 +0.4 | 0 | 0 |
| GUANZI 12, 0.0 | 0 | 0 | 0 | 0 | 0 | 0 | 0.625 +1.2 |
| GUANZI 24 (440), 0.5 | 0 | 0 | 0 | 0 | 0.400 +1.5 | 0.468 +1.5 | 0 |
| BARI 12 (110), 0.5 | 0 (109.2 Hz, −13 c) | 0 | 0 | 0.0001 | 0.005 | **0.208 at 133.9 Hz, +340 c** | 0.0003 |
| BARI 12, 1.0 | 0 | 0 | 0.0003 | 0.013 | **0.177 at 251.9 Hz** | 0 | 0 |
| BARI 24 (220), 0.5 | 0 | 0 | 0 | 0 | 0.084 +1.5 | 0.462 −2.9 | 0 |
| BARI 24, 1.0 | 0 | 0 | 0.0002 | 0.272 −1.7 | 0.347 −4.2 | 0 | 0 |
| BARI 0 (55), any | never at f0 | | | | | | |

## Appendix B — the spike's tables (Kotlin, the corrected loop)

`BoreSpike.kt` (238 lines) and `BoreSpikeTest.kt` (305), reproduced with
the spike's own report in
[`../plans/2026-09-28-bore-phase-0-spike.md`](../plans/2026-09-28-bore-phase-0-spike.md)
(copy the two files into `synth/src/main` and `synth/src/test` to re-run;
`./gradlew --no-daemon :synth:test --tests
"com.snipsnap.synth.BoreSpikeTest"` was green in 12–18 s); every number
this document relies on is in the tables below.
Iteration 3 reproduced iteration 2 byte for byte
(timing aside). Constants: BELL 2500 Hz (reeds), FLUTE_BELL 6000, DC_HZ 2
(f0/25 on the cone in test 6), REED_SLOPE −0.3, OFFSET 0.5–0.85 (LIP),
P_SHARE 0.4–0.97 (BREATH), TURB 0.05, attack 40 ms, release 80 ms,
REFLECTION 0.95, OUT_DC 20 Hz, JET_RATIO 0.5, JET/END_REFLECTION 0.5,
JET_GAIN_PER_P 3.0, JET_OFFSET_MAX 0.3, FLUTE_P_MAX 1.2, 1.0 s notes.

**B1 · Iteration log:**

| Iter | Reed / pressure | Cone blocker | CYL 110/220/440 (c) | CONE 55/110/220 (c; global peak) | FLUTE 220/440/880 (c) |
|---|---|---|---|---|---|
| 1 | offset 0.7; LIP → slope −0.2..−0.5; p_m = 1.2·BREATH | 20 Hz | +0.5 / +0.9 / +1.3 | −6.0 / −5.6 / −2.2; 105.7 / 215.5 / 219.7 — mode 2 dominant, 36–70 c flat | −12.9 / −5.0 / +0.4 |
| 2 | slope −0.3; LIP → offset 0.5..0.85; p_m = pClose·lin(BREATH, 0.4, 0.97) | 2 Hz | +0.7 / +1.3 / +1.9 | −4.0 / +0.3 / −6.8; 54.9 / 110.0 / **23.3** (a 21 Hz relaxation) | −0.1 / +1.2 / +2.5 |
| 3 | as 2 | 20 vs 2 vs f0/25 vs none | unchanged | f0/25: −4.3 / −5.8 / −4.4; mode 2 −1.6 / −4.9 / −4.2; sub-40 Hz −25 / −38 / −44 dB | unchanged |

**B2 · Pitch, DC, level at BREATH 0.65 / LIP 0.5:**

| Shape | f0 | exact / n / a | τLP / τDC | nearPk (c) | globalPk Hz | Pitch.detect | rawDC | rawAC | final mean | peak | Loudness | ms/s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CYLINDER | 110 | 791.088 / 791 / 0.838 | 10.73 / 0 | +0.7 | 110.04 | 109.98 | +0.017 | 0.587 | −0.00002 | 0.308 | 0.1834 | 106–113 (JIT) |
| CYLINDER | 220 | 390.201 / 390 / 0.666 | 10.71 / 0 | +1.3 | 220.17 | 220.50 | +0.018 | 0.571 | −0.00002 | 0.238 | 0.1834 | 32–49 |
| CYLINDER | 440 | 189.831 / 189 / 0.092 | 10.62 / 0 | +1.9 | 440.49 | 441.00 | +0.020 | 0.508 | −0.00002 | 0.232 | 0.1834 | 26–43 |
| CONE 2 Hz | 55 | 3215.091 / 3215 / 0.833 | 10.74 / −18.55 | −4.0 | 54.87 | 54.78 | +0.057 | 0.155 | −0.00001 | 0.612 | 0.1832 | 33–45 |
| CONE 2 Hz | 110 | 1597.546 / 1597 / 0.294 | 10.73 / −4.64 | +0.3 | 110.02 | null | +0.034 | 0.321 | −0.00937 | 0.952 | 0.1834 | 30–37 |
| CONE 2 Hz | 220 | 792.270 / 792 / 0.575 | 10.71 / −1.16 | −6.8 | 23.31 | null | +0.042 | 0.423 | −0.00078 | 0.962 | 0.1834 | 26–47 |
| CONE f0/25 | 55 / 110 / 220 | — | τDC −20.41 / −10.20 / −5.10 | −4.3 / −5.8 / −4.4 | 54.86 / 109.63 / 219.45 | 54.78 / 109.70 / 219.40 | +0.059 / +0.060 / +0.050 | 0.148 / 0.326 / 0.547 | — | 0.643 / 0.435 / 0.335 | — | — |
| FLUTE | 220 | 798.783 / 798 / 0.121 | 4.20 / −1.16 | −0.1 | 659.82 (h3) | 219.40 | +0.027 | 1.245 | −0.00005 | 0.226 | 0.1834 | 39–60 |
| FLUTE | 440 | 397.011 / 397 / 0.979 | 4.19 / −0.29 | +1.2 | 440.30 | 441.00 | +0.026 | 1.279 | −0.00004 | 0.215 | 0.1834 | 36–40 |
| FLUTE | 880 | 196.363 / 196 / 0.467 | 4.16 / −0.07 | +2.5 | 881.28 | 882.00 | +0.031 | 1.221 | −0.00005 | 0.213 | 0.1834 | 37–42 |

*exact:* the budgeted loop length in samples; *n / a:* its whole-sample
ring and the allpass fraction; *τLP / τDC:* the bell filter's delay and
the DC blocker's lead, in samples; *nearPk:* the FFT peak nearest f0, in
cents; *globalPk:* the strongest peak anywhere in the spectrum.
Non-finite samples: 0 in every render. `Pitch.detect`'s 220.50 / 441.00 /
882.00 are its lag quantisation, not the loop.

**B3 · Harmonics** (dB re h1; odd/even = 20·log10(Σ|odd| / Σ|even|)):

| Shape f0 | h2 | h3 | h4 | h5 | h6 | h7 | h8 | odd/even |
|---|---|---|---|---|---|---|---|---|
| CYL 110 | −48.6 | −9.9 | −49.8 | −15.3 | −51.4 | −19.6 | −53.4 | +42.6 |
| CYL 220 | −44.8 | −11.7 | −48.3 | −19.2 | −52.8 | −25.8 | −57.5 | +40.6 |
| CYL 440 | −41.9 | −15.6 | −51.2 | −27.0 | −56.7 | −36.4 | −58.0 | +39.1 |
| CONE 55 (2 Hz) | −9.6 | −19.4 | −15.0 | −16.9 | −26.5 | −23.7 | −27.5 | +6.8 |
| CONE 110 (2 Hz) | −7.3 | −33.0 | −21.6 | −31.5 | −24.8 | −33.4 | −34.4 | +5.1 |
| CONE 220 (2 Hz) | −0.7 | −5.6 | −9.6 | −6.0 | −11.9 | −12.5 | −12.6 | +2.3 |
| CONE 55 (f0/25) | −10.1 | −18.7 | −16.0 | −17.3 | −25.5 | | | |
| CONE 110 (f0/25) | −7.9 | −22.5 | −15.7 | −22.3 | −24.9 | | | |
| CONE 220 (f0/25) | −9.2 | −21.6 | −17.5 | −33.5 | −25.8 | | | |
| FLUTE 220 | −44.2 | **+3.9** | −37.8 | −3.1 | −42.1 | −4.7 | −39.1 | +40.1 |
| FLUTE 440 | −42.8 | −10.0 | −44.2 | −15.4 | −45.3 | −19.5 | −47.0 | +36.7 |
| FLUTE 880 | −37.3 | −11.2 | −40.4 | −18.4 | −44.2 | −24.6 | −48.5 | +32.9 |

Iteration 1's cone (20 Hz) for the record: 55 Hz h2 **+8.0 dB above h1**,
110 Hz h2 +6.5 — the inharmonic 2nd mode dominating.

**B4 · Corner sweep, CYLINDER 220 Hz** (BREATH × LIP; raw mid-50 % AC RMS,
"osc" > 0.01; cents at f0):

| BREATH | LIP 0 | LIP 0.5 | LIP 1 |
|---|---|---|---|
| 0.15 | — (0.0013) | — (0.0003) | — (0.0000) |
| 0.30 | osc +0.9, 0.739 | — (0.0071) | — (0.0002) |
| 0.50 | osc +2.0, 0.921 | osc +1.1, 0.513 | — (0.0007) |
| 0.65 | osc +2.2, 1.029 | osc +1.3, 0.571 | osc +0.3, 0.102 |
| 0.85 | osc +1.8, 1.165 | osc +1.1, 0.637 | osc +0.4, 0.147 |
| 1.00 | osc +1.5, 1.261 | osc +0.8, 0.676 | osc +0.2, 0.111 |

Every oscillating corner: global peak = f0 (220.03–220.27), rawDC +0.003
to +0.037, output peak 0.236–0.271, Loudness 0.1834. Non-oscillating
corners: output peak 0.990, Loudness 0.059–0.084 — `levelTo` lifting the
turbulence floor.

**B5 · Corner sweep, CONE 110 Hz, f0/25** (f0 cents / mode-2 cents / sub-40 Hz dB):

| BREATH | LIP 0 | LIP 0.5 | LIP 1 |
|---|---|---|---|
| 0.30 | +2.3 / −10.6 / −0.3 (relaxation) | — | — |
| 0.50 | −8.0 / −7.6 / −34.8 | −7.3 / −5.6 / −29.8 | — |
| 0.65 | −2.0 / +1.2 / −18.6 | −5.8 / −4.9 / −37.7 | — |
| 0.85 | −1.0 / +5.4 / −20.2 | −4.7 / −8.0 / −34.4 | −0.5 / −8.4 / −22.2 |
| 1.00 | −5.2 / −12.2 / −34.6 | −4.5 / −7.7 / −37.4 | −1.7 / −8.7 / −16.2 |

11 of 15 oscillating; output peaks 0.40–0.73; `Pitch.detect` 109.43–109.98
on every oscillating corner. At 2 Hz the LIP 0 column and the low-BREATH
rows were the 21 Hz relaxation (global peak 20.5–23.1 Hz, Pitch null).

**B6 · Threshold at LIP 0.5** (0.05 steps): CYLINDER 220 first oscillates
at BREATH **0.35** (p_m 0.649 = 0.60 of the closing pressure 1.083; the
table's linear threshold predicts 0.58); rawAC/p_m 0.011 at 0.30, 0.444 at
0.35, 0.615 at 0.40, 0.69 at 0.50–0.55, easing to 0.64 at 1.0; pitch +0.4
to +1.3 c across the whole playable range. CONE 110 (2 Hz): a clean note
from 0.35 (+4.0 c), then within ±2.8 c up to 1.0; below 0.35 the
relaxation. FLUTE 440: rawAC 0.0008 / 0.0016 / 0.0028 at BREATH 0.15 / 0.30
/ 0.50 (no lock), 1.279 at 0.65 (+1.2 c), 1.723 at 0.85 (+2.2 c but global
peak 1321.45 Hz = the 3rd mode), 1.770 at 1.0 (+2.1 c, global 440.54).

**B7 · Centroid** (`FeatureExtractor.extract` on the middle of the output):
CYLINDER 220 vs BREATH at LIP 0.5: 6593.5 (0.15, noise), 1238.6 (0.30,
under threshold), 261.7 / 265.2 / 259.5 / 252.5 Hz (0.50–1.0) — flat.
CYLINDER 220 vs LIP at BREATH 0.65: 282.8 / 274.5 / 265.2 / 251.8 / 250.4 Hz
— monotonic non-increasing; rolloff 1540 / 1109 / 1098 / 668 Hz. CONE 110 at
2 Hz: 4–77 Hz, dominated by the relaxation; re-measure at f0/25 before any
claim.

**B8 · Jet ratio, FLUTE at BREATH 0.65 / LIP 0.5** (nearPk cents; global
peak Hz): 0.32 → +102.4 (1088.6) / +80.5 (849.8) / +73.7 (1700.1) at
220 / 440 / 880, upper modes +40 to +54 dB, unusable; 0.40 → +79.7 / +79.6
/ +80.1 (869 / 461 / 922), unusable; **0.50 → −0.1 / +1.2 / +2.5** (659.8 /
440.3 / 881.3).

**B9 · Residuals against a 5-cent gate:** CYLINDER +0.7 / +1.3 / +1.9 at
defaults, +0.2 to +2.2 across all 13 oscillating corners — passes without a
correction (the residual is sharp and grows with f0, ≈ 0.2–0.3 samples of
loop, consistent with a memoryless junction sitting one write behind the
read). CONE −4.0 / +0.3 / −6.8 at 2 Hz, −4.3 / −5.8 / −4.4 at f0/25 — a
consistent ~5 c flat; with a measured per-voice correction of about +5 c
(a 0.3 % shorter loop) the defaults pass and the corners straddle ±5 c
around it. FLUTE −0.1 / +1.2 / +2.5 at defaults, +1.2 to +2.2 over BREATH
0.65–1.0 — passes.

**B10 · What the spike needs from `Strings.Loop`:** a junction hook
(`next(x)` is additive at `Strings.kt:429` with the returning wave private
to the method); a round-trip factor in `tune` (`:161` hard-codes
`rate/freq`); the DC blocker term independent of `jawari` (`:142-146` budget
it and `:424-428` run it only under `jawari > 0`) with a corner that is a
parameter rather than `dcBlockerA`'s fixed 2 Hz (`:194`). Negative feedback
is already expressible. Keeping the two-tap average and its budgeted half
sample is fine; the spike's single tap made no measurable difference.
