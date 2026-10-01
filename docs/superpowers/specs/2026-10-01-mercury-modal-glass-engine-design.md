# MERCURY — a rubbed, bent, water-loaded modal object: the handoff, reviewed against the tree

**Status:** review and decision; **nothing is built and nothing has been
rendered.** This document reads the external *Mercury Engine — Engineering
Specification* (v1.0, 2026-10-01; musical saw + glass harmonica +
waterphone) against the checkout at `e11c178`. It does the spec's own
"Round 0 — repository alignment" and decides how the idea enters SnipSnap.
No DSP claim below is measured. Every repository claim cites a file and
line that were read. The doc lands as a docs-only PR (zero check runs by
design, `.github/workflows/tests.yml` `paths-ignore`). The commit message
names no maker or machine.
**Date:** 2026-10-01
**Plan:** Phase 0 record first (`docs/superpowers/plans/2026-10-0x-mercury-phase-0-spike.md`),
then one plan per round.
**Related:**
- [`2026-09-28-bore-woodwind-engine-design.md`](2026-09-28-bore-woodwind-engine-design.md):
  the model for an external spec reviewed against the code, and the
  registration and LOOP pattern R1 copies.
- [`2026-09-29-arco-bowed-string-engine-design.md`](2026-09-29-arco-bowed-string-engine-design.md):
  the house's only friction model (design and spike only).
- [`2026-09-18-synth-depth-design.md`](2026-09-18-synth-depth-design.md):
  STRIKE and its GLASS voice (design only) and the "No tenth engine" line.
- [`2026-09-30-terra-hit-bend-talk-design.md`](2026-09-30-terra-hit-bend-talk-design.md):
  per-mode level and pitch curves (design only).
**Roadmap:** no `SYNTH_ROADMAP.md` row until implementation starts, the
rule FATHOM, RESIN, GLINT, SILK, FORK and BORE followed.
**Evidence:** three read-only investigator passes over the tree at
`e11c178`:
- **[api]** the engine contract, using BORE as the reference.
- **[fleet]** the roadmap, the admission policy and naming.
- **[dsp]** the reusable modal, friction, modulation and loop code.

## The decision, in one paragraph

**Build MERCURY as its own engine, but not yet as the spec draws it.** It
reaches something no engine in the tree can: a *sustained, friction-driven
modal body whose modes move together*. That passes the house's "against the
fleet" test on paper. Its central mechanism, though, is unproven anywhere in
this repository. No resonator here is both high-Q and stable under
per-sample frequency change, no code couples modes in both directions, and
the only friction model (ARCO's) exists as a spike on a waveguide, not on
modes.

So the order is:
1. A **Phase-0 spike** that must speak, stay in tune and decay passively.
2. An **R0** that adds one new, stable, drivable modal bank to `Modes`. This
   is shared code that STRIKE, TERRA's BEND and ARCO's bowed bar would
   also want.
3. An **R1** with three voices, not six, on the house's contract.
4. **LOOP and the other three voices at R2**, behind the audition gate.

Eleven places where the spec departs from the house are corrected below.
Eleven decisions are left for the owner, each with a default.

## Why MERCURY

The spec's sound is a pitched object you can tap *or rub*, whose shape
(BEND) and moving contents (WATER) move all of its resonances together.
The fleet has struck modal bodies and one bowed design. It has no rubbed
modal body and no coherent, physically correlated modal drift:

| What MERCURY would make | Nearest thing in the tree | What that lacks |
|---|---|---|
| Glass ping / soft mallet | TINES CHIME ("two near-identical bells beating", `Tines.kt:260-262`), FORK BAR (struck modal bar, `Fork.kt:90-91`), presets GLASS TAP / GLASS TINE | Struck only. Fixed geometry, so nothing deforms or loads. |
| Pure sustained bowed glass | None built. ARCO is a bowed *string*, design only (`arco spec:3`). | Nothing sustains a modal body by friction. |
| Musical-saw glide lead | SILK SHAMISEN's built-in glide (a string); GLINT SWEEP (a formant path) | The glide is a pitch line, not a body changing shape. |
| Waterphone drift and beating | TIDE WANDER, `EnsemblePlayers.Wander` (`EnsemblePlayers.kt:130-167`) | Random pitch lines, one per note or voice, not one mass moving every mode. |
| Metallic pads and drones | RESIN drone, SIREN drone, TIDE GONG | Subtractive, PD or FM; not a resonant object. |

**Worth building for exactly one thing none of that does:** energy fed
into a modal object by friction, whose modes shift together as it bends
and as its load moves. Whether that is *audibly* new is the audition's
question. CHIMERA's numeric novelty bar (normalised cross-correlation
against the nearest existing render, below 0.9;
`become-strung-say spec:16-20`) should be run against TINES CHIME and
FORK BAR as part of the gate.

## The specification, as reviewed

The spec was written from a one-page summary, without the source (its §1
says so). Most of its contract names are real:
- `MacroSpec(name, default, neutral)` (`Thump.kt:33`)
- `Dsp.levelTo` (`Dsp.kt:654`)
- `MELODIC_LOUDNESS_TARGET` (`Dsp.kt:525`)
- `Dsp.seedFor` (`Dsp.kt:147`)
- `Keys.seamError` and the 1e-3 gate (`Keys.kt:147, :188`)
- `Patches`, `SynthKits`, `drumClassFor`, `PadRecipe`, `FxChain`
- the 4× render (`Dsp.OVERSAMPLE`, `Dsp.kt:33`)

The departures:

| # | The spec says | The tree says | Correction |
|---|---|---|---|
| 1 | §13.6: if the loop will not converge, "use the existing repository wrap crossfade". | No crossfade helper exists. The house rule is the opposite: "A second failure propagates, naming the zone - never a crossfade." (`Keys.kt:253`). BORE throws on a failed seam (`Bore.kt:916-922`). | No crossfade. A corner that cannot close throws, and that corner is not offered as LOOP (see "HOLD, and the LOOP"). |
| 2 | §4: HOLD neutral 0, default 0, "repository-defined held loop behavior". | HOLD on SIREN and BORE is the **gated length**, 0.3–4 s, and its top step (≥ 0.99) is LOOP; `DEFAULT_HOLD = 0.45` (`Bore.kt:361-367`). | Adopt the house HOLD. For MERCURY, HOLD is the **contact length**. This is the "virtual contact envelope" §12 asks for, already on a knob. |
| 3 | §14: include an "engine model/config version". | Patches carry `{engine, version, name, voice, macros}` with one shared `Patches.VERSION = 1` (`Patches.kt:32, :60-72`). Unknown top-level fields are ignored. | No per-engine version field. `MercuryPatch` copies `BorePatch.kt` (37 lines). |
| 4 | §14: handle out-of-range and non-finite macros "consistently with the repository". | `Patches.validateMacros` `require`s every value in 0..1 and every key known (`Patches.kt:95-102`), so a NaN fails `v in 0f..1f`. `settled()` clamps on the render path (`Bore.kt:495-499`). | Same two layers: reject at the patch boundary, clamp in `settled`. |
| 5 | §8: velocity "MUST affect excitation energy and brightness/pressure". | Patches carry no velocity. `Velocity` re-renders only through a registered brightness macro, otherwise it `soften`s (`Velocity.kt:26, :239-254`). House rule: register an override only after a monotonic centroid sweep test. Only PLUCK takes a numeric velocity. | R1 ships `soften`, as BORE did (`BoreTest.kt:518-531`). An override (likely RUB or GLASS) or a numeric velocity on `render` waits on the sweep. Owner decision 8. |
| 6 | §14: seeds must not rely on enum ordinals or hashes. | `Dsp.seedFor(vararg parts)` hashes each part's `toString()` (`Dsp.kt:147-151`), so an enum contributes its *name*, which is stable. | `Dsp.seedFor("MERCURY", voice, hz, "strike")`, and likewise `"contact"` and `"mass"`: three labelled streams, as §14 wants. |
| 7 | §15: at least 12 presets; names include **SLOW TIDE**. | 8 presets per voice, uppercase, ≤ 14 characters (`BorePresetsTest`). "No engine's name inside a preset name" (`arco spec:624-627`), and TIDE is an engine. | 8 per voice (24 at R1). SLOW TIDE becomes SLOW CURRENT. The other eleven names pass the blocklist and are free. |
| 8 | §4: voices **SAW** and **TIDE**. | TIDE is an engine (`Patches.kt:221`, the picker). SAW is the sawtooth everywhere (`Draw.Wave.SAW`, `Draw.kt:47`; VELVET's SHAPE; presets RAW SAW, THIN SAW, BUZZ SAW). | SAW becomes **BLADE** and TIDE becomes **EDDY**. Both names are absent from every `.kt` file in the tree. PING, SING, VESSEL and SHARD are free as voice names. |
| 9 | §5–§10: base mode ratios "require tuning". | `Modes` forbids unsourced ratios: "do not adjust a value here without a source" (`Modes.kt:121-125`). There is no GLASS, BOWL, PLATE or SAW table (`Modes.kt:133`). | MERCURY's tables live in `Mercury.kt`, labelled as **designed** abstractions (which §1 says they are), with sourced starting points where a source opens. They are not added to `Modes.Material`. Owner decision 9. |
| 10 | §11: "never clamp many high modes to the same frequency". | `Modes.ring` already *skips* modes above Nyquist (`Modes.kt:99`). | Keep that, but fade a mode out as it approaches the internal ceiling rather than cutting it, because BEND and WATER move modes across the line. |
| 11 | §3: classifier must avoid KICK, SNARE, CLAP, HAT, TOM. | Correct, and it is a real risk. SKIN's ride BELL was built and dropped because it read as SNARE (roadmap :100-103). Bright, short sounds hit `highRatio > 0.5 → SNARE` (`Classifier.kt`). | PING and SHARD presets get BORE's preset contract (`BorePresetsTest.kt:39-63`) from R1, and the voicing is fixed musically, never by "appending an unrelated tone" (the spec's own §16). |

### What the spec got right

- **One shared architecture, not three instruments crossfaded** (§1). This
  is the house's "a voice is its excitation" pattern (STRIKE, PLUCK).
- **Reciprocal friction:** force applied back through the same
  participation vector it was measured on (§8). ARCO's spike found the
  same thing the hard way. A bow on one ring parks at DC, and the
  junction must be two-sided (`arco spec:361-372, :648-660`).
- **"A stable individual resonator does not by itself prove stability of
  the coupled system,"** and time-varying K does parameter work (§6). This
  is correct, and it is why Phase 0 measures passive decay before
  anything else.
- **Raw diagnostics before levelling** (§11). `Bore.blow` returns raw
  samples for exactly this reason (`Bore.kt:669-680`).
- **The listening gate outranks the numbers** (§17). This is the house rule
  (roadmap :1130).
- **The rounds** (§18) map almost one-to-one onto the house's
  Phase 0 / R0 / R1 / R2.

## What has to be new: one drivable, retunable, coupled modal bank

[dsp] went through every resonator in the tree:

| Candidate | Where | Per-sample retune | High Q | Drivable (friction feeds back) | Couplable |
|---|---|---|---|---|---|
| `Modes.ring` (direct-form two-pole) | `Modes.kt:85-118` | no: coefficients fixed per call | yes | input only, no state access | no |
| FORK's glide loop (same two-pole, retuned) | `Fork.kt:603-621` | yes, but amplitude moves when coefficients change | yes | input only | no |
| `Dsp.TptSvf` (trapezoidal SVF) | `Dsp.kt:208-260` | yes, "clean under fast modulation" | **no**: `k.coerceAtLeast(0.1f)` caps Q near 10 (`:244`); SKIN rejected it for long rings | yes | possible |
| TERRA / SKIN phase-accumulator sines | `Terra.kt:367-408`, `Skin.kt:174-207` | yes, perfectly | yes | **no**: not resonators | no |

None does all four. So the one genuinely new piece of DSP is a **per-mode
resonator that keeps its energy under frequency change, can be read for
velocity and driven by force every sample, and can exchange energy with
its neighbours.**

Two candidates should be compared in Phase 0. Neither is chosen here:
- A **complex one-pole / rotation resonator**: state `z ← r·e^{iθ}·z + b·x`,
  with `r` from t60 and `θ` from the instantaneous Hz. A rotation keeps
  |z|, so BEND and WATER do not pump amplitude the way the two-pole does.
  Displacement and velocity come from the quadrature pair.
- **The spec's own §6 model**: a trapezoidal (implicit) state-space step
  with a positive spring Laplacian for coupling.

Either one lands in R0 as a new class in `Modes` with its own tests. It
changes no existing render: `DeterminismTest` and every frozen grid must
stay byte-identical. That makes it shared toolkit, as `Strings.kt` became
for SILK and BORE:
- STRIKE's "excitation into a modal body" (`synth-depth spec:774`) would
  be built on it.
- TERRA's BEND hook (`terra spec:665-799`) wants per-sample pitch on modes.
- ARCO's "bowed bar is a BODY table swap" (`arco spec:2033-2036`) needs a
  drivable body.

## What is reused, and from where

| Need | Source in the tree | Verdict |
|---|---|---|
| Mode table type, position weights, pan spread | `Modes.Mode` (`Modes.kt:34`), `atPosition` (:362), `spread` (:382), `fixed` (:202), `morph` (:331) | Reuse as is |
| Strike pulse | `Terra.hardStickExciter` / `fleshPalmExciter` (`Terra.kt:299-330`), `Fork.excite` / `striker` (`Fork.kt:491, :859`) | Reuse |
| Friction law | ARCO spike's table on relative velocity, `ρ(Δv) = clamp((|slope·(Δv+0.001)|+0.75)^−4, 0.01, 0.98)` (`arco spec:793-808`; spike Appendix B) | Port onto *contact velocity* (`Σ participation·modeVelocity`). Spike text only, no code in the tree. |
| Onset aids for a self-oscillation | BORE: pressure overshoot (`Bore.kt:292-294`), POP pulse (:382-394), threshold-margin floor (:281-290); ARCO: window-mapped pressure (`arco spec:834-892`) | Reuse the discipline. A tap is a natural POP for a rub. |
| 4× → band-limit → decimate → DC → level → fade | `Tide.bandLimit` (`Tide.kt:518-541`), `Dsp.decimate` (`Dsp.kt:695`), `Bore.condition` / `finish` (`Bore.kt:808-834`) | Reuse as is |
| Loop-safe slow motion (WATER in a LOOP) | `ResinDrone` index-mod-loop phases (`ResinDrone.kt:132-192`), `GlintHeld` (`GlintHeld.kt:24-36`: decimation keeps whole-frame periodicity) | Reuse the pattern |
| Modulated frequency closing on whole cycles | `Siren.planLoop`: integrate the pitch multiplier over the loop, set `baseHz = cycles/∫` (`Siren.kt:339-371`) | Reuse, per mode |
| Limit-cycle period measure and retune | `Bore.measureLoopSamples` and ≤ 5 retune passes (`Bore.kt:859-870, :936-941`) | Reuse for the rubbed LOOP |
| Seam gate, cut | `Keys.seamError` / `requireSeam` (`Keys.kt:188-206`), `Siren.bestCut` (`Siren.kt:425`) | Reuse as is |
| One-shot WATER drift | `EnsemblePlayers.Wander`, which the KDoc says is not loop-safe (`EnsemblePlayers.kt:53-54`) | Not the driver. WATER must be one deterministic mass oscillator (§9), which ResinDrone-style phases make loop-safe. |

## The name

- **MERCURY:** no blocklist match and unused anywhere in the tree. It is a
  common word; the hand review under roadmap :27-47 finds nothing to stop
  it.
- **Voices** (R1 bold): **PING**, **SING**, **BLADE** (was SAW), then EDDY
  (was TIDE), VESSEL and SHARD.
- **Macros:** BEND, RUB, WATER, GLASS, COUPLE, HOLD.
  - BEND already means "pitch-drop speed" on THUMP Kick/Tom and TINES Zap
    (`Tines.kt:290-292`). HOLD already means two things across engines, so
    macro words are per-engine by precedent. MERCURY's BEND still differs
    enough to name in its KDoc.
  - GLASS is FATHOM's *voice* name (`Fathom.kt:29`). As a macro word that
    is allowed.
- **Blocklist:** no new terms needed. If the spec's inspirations are ever
  named in a description, those are instrument types, not marks.

## Architecture, on the house contract

```
Mercury.render(voice, macros): Snip            // Bore.render's shape, Bore.kt:958
  └─ internal sound(voice, hz, macros, rate = RATE*OVERSAMPLE, …): FloatArray   // raw, unlevelled, for tests
       per sample, fixed order, preallocated:
         geometry   c(t) → BEND gesture toward cTarget = 2·BEND−1 (§7)
         mass       one damped 2-D oscillator, seeded ("mass") (§9)
         modes      12 primary + 4 vessel on the R0 bank; ratio_i(c, mass), damping_i(GLASS, mass)
         contact    vSurface = Σ b_i·v_i ; F = pressure·ρ(vDriver − vSurface) + roughness("contact")
                    F applied back through b_i (reciprocal)            // §8
         strike     finite pulse into b_i ("strike") at onset           // tapWeight
         coupling   reciprocal springs, neighbours + primary↔vessel     // COUPLE, WATER-scaled
         pickup     Σ p_i·v_i
  └─ condition(): Tide.bandLimit → Dsp.decimate → mean + 20 Hz one-pole → levelTo(MELODIC) → fadeTail
```

**Macros** (`macrosFor`, house `MacroSpec`; order fixed for patches and
UI): BEND .50/.50, RUB .35/.35, WATER .20 default / .00 neutral,
GLASS .60/.55, COUPLE .30/.25, HOLD `DEFAULT_HOLD`. That is six macros:
within "3–6, never a patchbay" (roadmap :174) and under the seven-macro
ceiling.

**One-shot lifecycle:** strike at 0, contact for HOLD's seconds
(`HOLD_MIN..MAX`), contact release, then free ring until a windowed energy
floor or the cap, then `fadeTail`. `drumClassFor` is derived from the exact
frame count, as `Bore.drumClassFor` is (`Bore.kt:623-627`).

## HOLD, and the LOOP

Two different periodicity problems live in one object:

1. **The free modes.** They are inharmonic, so they share no period. In a
   loop each mode's frequency is snapped to a whole number of cycles over
   the loop, using SIREN's integral when BEND or WATER move it
   (`cycles_k = round(f_k·∫mult_k)`). Mode identities stay stable (§7). A
   snap is at most half a cycle per loop, about the cents
   `Keys.planLoop` already accepts (`Keys.kt:169-180`).
2. **The rubbed limit cycle.** Friction locks to a slip period. That is
   BORE's and ARCO's problem, solved by measure and retune
   (`Bore.kt:936-941`).

In the LOOP:
- WATER's mass runs at whole cycles of the loop. The spec's "do not
  silently make WATER ineffective by rounding its motion to zero" (§13.4)
  is honoured by lengthening the loop, not by zeroing the motion.
- The BEND gesture stays in the warm-up.
- Roughness becomes a repeating sequence.
- The stretch is decimated whole (GlintHeld's proof), then
  `Siren.bestCut`, then `requireSeam`.

**Whether (1) and (2) close together under the 1e-3 gate is unknown.** A
friction-locked mode with WATER moving the others may never become exactly
periodic. That is why LOOP moves to R2 behind its own probe. The answer to
a corner that will not close is a throw and a scramble ceiling, never a
crossfade (correction 1).

## Registration: every touch point (from [api], BORE as reference)

Compile-enforced:
1. `Patches.fromJsonValue` arm (`Patches.kt:55`).
2. `Velocity.macroSpecsFor` arm (`Velocity.kt:219`), an exhaustive `when`.
   `:synth` will not compile without it.

Test-enforced or by convention, in `:synth`:

3. `Presets.forVoice` / `all()` and the KDoc count (`Presets.kt:8-9, :66-69, :98`).
   This is also how the CLI sees it (`SynthCommand.kt:59`).
4. `SynthKits.mercury()`: 16 pads in BORE's shape (`SynthKits.kt:263-279`).
5. `synth/build.gradle.kts`: `generateMercuryKit`, `generateMercuryAudition`
   (BORE's at :277-294).
6. Tests: `DeterminismTest` canary (one-shot and LOOP), `PadRecipeTest`
   `onePatchPerEngine`, `PresetsTest` counts, `SynthKitTest`, and the new
   `MercuryTest` / `MercuryPresetsTest` (blocklist near-miss and clean
   lists).
7. `StringMachineTest:40-46`: MERCURY's presets must land dry. No
   `Presets.landingFor` chain.
8. New files: `Mercury.kt`, `MercuryPatch.kt`, `MercuryPresets.kt`,
   `MercuryKitGenerator.kt`, `MercuryAuditionGenerator.kt`,
   `audition/mercury-audition.html`, `testkit/SnipSnap Mercury Kit/`, and a
   `.gitignore` line for `testkit/mercury-audition/`.

Deferred to R1.1, because `:app` cannot compile without an Android SDK and
a cloud session has none (steward skill):
- the `SynthScreen.kt` `Engine` enum entry and its seven `when` arms
  (`:1855-1990`)
- the README engine count (`README.md:348`)

R2 (held keys): `Keys.mercuryPad`, `InstrumentSuite` / `InstrumentSidecar`
`RECIPES`.

## Phasing and gates

| Phase | Ships | Gate |
|---|---|---|
| **0 — spike** (throwaway, recorded in `plans/`) | Candidate R0 banks, 12+4 modes, reciprocal friction, coupling, BEND/WATER motion; probes at MIDI 36/60/84 | **Speaks, in tune, decays.** Rub onset reliable across pitch and pressure. Excitation off with geometry fixed: energy falls monotonically at every COUPLE. A BEND sweep does not pump amplitude. WATER 0.05 is measurable inside a 2 s note. Raw states bounded at every corner. If friction will not sustain on modes, stop here and say so. |
| **R0** | The chosen bank as a `Modes` class, with its own claims tests | No existing render changes (DeterminismTest and frozen grids byte-identical) |
| **R1** | MERCURY with PING, SING, BLADE: patch, 24 presets, registration, kit, audition page (macro sweeps, the five interaction grids of §10, BEND .40/.50/.60 and WATER 0/.05/.10/.20 dead-zone probes) | **The audition.** Blind identity, plus A/B against TINES CHIME and FORK BAR; NCC < 0.9 against the nearest render |
| **R1.1** | Picker entry and README count, on a machine with the SDK | R1's verdict |
| **R2** | LOOP (the HOLD top step) with a fuzz over every voice × TUNE × corner; `Keys.mercuryPad`; EDDY, VESSEL, SHARD | Seam < 1e-3 on the exported buffer, plus an eight-wrap listen |

The spec's §16 matrix is kept, with two changes. "HOLD" moves to R2. The
§16 performance budget (8 s in 10 s, < 128 MB) is recorded as a
measurement, not a gate: the repo has none, and BORE has none.

## Out of scope

The same as the spec's §2: stereo, real-time, fluid simulation, finite
elements, and FX identity. Also out: audio-rate mass motion, and a numeric
velocity API until a sweep earns it.

## Decisions for the owner

Each has a default. Silence means the default.

1. **Build at all, now?** The roadmap carries five engines "awaiting the
   audition gate" (TIDE, GLINT, SIREN, BORE; SILK "not heard") and three
   designed-but-unbuilt ones (STRIKE, ARCO, MAGNET). MERCURY adds a sixth
   gate to clear. *Default: yes, but only Phase 0 now. Nothing past it
   until the spike speaks.*
2. **"No tenth engine"** (`synth-depth spec:818`) has been overtaken in
   practice: FORK, SILK, TERRA and BORE came after it. *Default: record
   here that admission is by the fleet table plus the audition gate, as
   BORE's and ARCO's specs already do.*
3. **MERCURY versus STRIKE.** PING overlaps STRIKE's planned GLASS voice.
   *Default: if both are ever built, STRIKE drops GLASS and both share the
   R0 bank.*
4. **MERCURY versus ARCO's bowed bar** (`arco spec:2033-2036`). *Default:
   bowed modal bodies belong to MERCURY. ARCO stays strings.*
5. **Voices at R1.** *Default: three (PING, SING, BLADE). EDDY, VESSEL and
   SHARD at R2 only if R1's gate passes.*
6. **Renames.** *Default: SAW → BLADE, TIDE → EDDY, SLOW TIDE → SLOW CURRENT.*
7. **HOLD's meaning.** *Default: the house one (contact length; top step
   LOOP), not the spec's 0/0 neutral and default.*
8. **Velocity.** *Default: `soften` at R1. A brightness override (RUB or
   GLASS) only after a monotonic centroid sweep, per the house rule.*
9. **Mode tables: designed or sourced?** *Default: designed, labelled as
   such and kept in `Mercury.kt`, seeded from a source where one opens.
   `Modes.Material` stays sourced-only.*
10. **LOOP on a tap.** At RUB 0 the sound decays, so what does LOOP loop?
    *Default: LOOP always sustains contact at a floor pressure, and the
    tap goes into the warm-up.*
11. **Gyre.** The spec names "Gyre" as a separate coupled-string
    rotational engine. Nothing by that name is in the tree.
    *Default: no action until a Gyre spec arrives. If it does, it shares
    R0's bank rather than growing its own.*
