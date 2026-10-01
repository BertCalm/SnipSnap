# MERCURY — a rubbed, bent, water-loaded modal object: the handoff, reviewed against the tree

**Status:** review and decision. **Phase 0 has run and passed**
(2026-10-01; "Phase 0, as measured" below): the rotation bank speaks, is in
tune, decays passively and stays bounded. **R0, the shared toolkit, is built**
(2026-10-01; "R0, as built" below): `Modes.Bank`, `Modes.Friction` and
`Modes.symmetricEigen`, with no change to any existing render. **R1, the
engine, is built** (2026-10-01; "R1, as built" below): PING, SING and
BLADE, 24 presets, a kit and an audition page, roadmap row **S21**.
**Nothing has been heard;** the audition gate is next. This document reads the external *Mercury Engine — Engineering
Specification* (v1.0, 2026-10-01; musical saw + glass harmonica +
waterphone) against the checkout at `e11c178`. It does the spec's own
"Round 0 — repository alignment" and decides how the idea enters SnipSnap.
No DSP claim below is measured. Every repository claim cites a file and
line that were read. The doc lands as a docs-only PR (zero check runs by
design, `.github/workflows/tests.yml` `paths-ignore`). The commit message
names no maker or machine.
**Date:** 2026-10-01
**Plan:** the Phase 0 record is
[`../plans/2026-10-01-mercury-phase-0-spike.md`](../plans/2026-10-01-mercury-phase-0-spike.md);
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
- [`2026-10-01-gyre-coupled-string-engine-design.md`](2026-10-01-gyre-coupled-string-engine-design.md):
  GYRE, the coupled-string engine, which is in progress separately (decision 11).
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
this repository. No *modal* resonator here is both high-Q and stable under
per-sample frequency change (`Strings.Loop` is retunable and drivable, but it
is one harmonic feedback loop per string, not a bank of independent
inharmonic modes), no code couples modes in both directions, and
the only friction model (ARCO's) exists as a spike on a waveguide, not on
modes.

So the order is:
1. A **Phase-0 spike** that must speak, stay in tune and decay passively.
2. An **R0** that adds one new, stable, drivable modal bank to `Modes`. This
   is shared code that STRIKE, TERRA's BEND and ARCO's bowed bar would
   also want.
3. An **R1** with three voices, not six, on the house's contract.
4. **LOOP and the other three voices at R2**, behind the audition gate.

Twelve places where the spec departs from the house are corrected below.
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
| 12 | §4: five sound macros plus HOLD; no pitch control. `render` takes only voice and macros. | Pitched engines choose their note with a centred `TUNE` macro, snapped over `TUNE_SEMITONES` (`Bore.kt:72, :485-509`: `MacroSpec("TUNE", 0.5f, neutral = 0.5f)`, `midiFor`, `frequencyFor`). The kit's note pads are TUNE steps (`SynthKits.kt:263-266`). | Add `TUNE` first, BORE's way: snapped, per-voice `rootMidi`, `frequencyFor` feeding `hz`. That makes seven macros, the ceiling (roadmap :80). R2's fuzz runs over TUNE's steps. |

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
| `Strings.Loop` (feedback waveguide) | `Strings.kt:523-675`, `retune` :667, `reflected`/`inject` :577/:635 | yes, state-preserving | yes | yes (BORE drives it) | one loop is one harmonic series; several loops summed are not coupled (`Strings.course`) |
| TERRA / SKIN phase-accumulator sines | `Terra.kt:367-408`, `Skin.kt:174-207` | yes, perfectly | yes | **no**: not resonators | no |

None does all four *as a bank of independent inharmonic modes*. `Strings.Loop`
comes closest, but its partials are tied to one delay line, so BEND cannot
move them separately and WATER cannot load them one by one. So the one genuinely new piece of DSP is a **per-mode
resonator that keeps its energy under frequency change, can be read for
velocity and driven by force every sample, and can exchange energy with
its neighbours.**

Two candidates were compared in Phase 0, and **the rotation resonator won**
("Phase 0, as measured"):
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
  hz = frequencyFor(voice, TUNE)                // Bore.kt:507-509
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
UI): TUNE .50/.50 (BORE's), BEND .50/.50, RUB .35/.35, WATER .20 default / .00 neutral,
GLASS .60/.55, COUPLE .30/.25, HOLD `DEFAULT_HOLD`. That is seven macros: past "3–6, never a
patchbay" (roadmap :174) by one, and at the seven that roadmap :80 treats as
the ceiling. TUNE is the one the spec forgot (correction 12), so any further
macro has to replace one, not add to them.

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

Compile-enforced (the only one):
1. `Velocity.macroSpecsFor` arm (`Velocity.kt:219`), an exhaustive `when`
   over the sealed `Patch`. `:synth` will not compile without it.

Test-enforced, not compile-enforced:
2. `Patches.fromJsonValue` arm (`Patches.kt:55`). It dispatches on the
   engine *string* and ends in `else -> throw JsonException`
   (`Patches.kt:57`), so a missing arm compiles. It is caught only by a
   JSON round-trip test (`MercuryPresetsTest`, `PadRecipeTest`
   `onePatchPerEngine`).

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
| **0 — spike** (throwaway, recorded in `plans/`). **Passed 2026-10-01.** | Candidate R0 banks, 12+4 modes, reciprocal friction, coupling, BEND/WATER motion; probes at MIDI 36/60/84 | **Speaks, in tune, decays.** Rub onset reliable across pitch and pressure. Excitation off with geometry fixed: energy falls monotonically at every COUPLE. A BEND sweep does not pump amplitude. WATER 0.05 is measurable inside a 2 s note. Raw states bounded at every corner. If friction will not sustain on modes, stop here and say so. |
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
   until the spike speaks.* **Taken by the owner 2026-10-01:** "start the
   Mercury Phase 0 spike with the defaults". Every other default below
   stands unless the owner says otherwise.
2. **"No tenth engine"** (`synth-depth spec:818`) has been overtaken in
   practice: FORK, SILK, TERRA and BORE came after it. *Default: record
   here that admission is by the fleet table plus the audition gate, as
   BORE's and ARCO's specs already do.*
3. **MERCURY versus STRIKE.** PING overlaps STRIKE's planned GLASS voice.
   *Default: if both are ever built, STRIKE drops GLASS and both share the
   R0 bank.* **Taken by the owner 2026-10-01.**
4. **MERCURY versus ARCO's bowed bar** (`arco spec:2033-2036`). *Default:
   bowed modal bodies belong to MERCURY. ARCO stays strings.* **Taken by
   the owner 2026-10-01.**
5. **Voices at R1.** *Default: three (PING, SING, BLADE). EDDY, VESSEL and
   SHARD at R2 only if R1's gate passes.*
6. **Renames.** *Default: SAW → BLADE, TIDE → EDDY, SLOW TIDE → SLOW CURRENT.*
7. **HOLD's meaning.** *Default: the house one (contact length; top step
   LOOP), not the spec's 0/0 neutral and default.*
8. **Velocity.** *Default: `soften` at R1. A brightness override (RUB or
   GLASS) only after a monotonic centroid sweep, per the house rule.*
9. **Mode tables: designed or sourced?** *Default: designed, labelled as
   such and kept in `Mercury.kt`, seeded from a source where one opens.
   `Modes.Material` stays sourced-only.* Phase 0 seeded both tables from
   Rayleigh's closed forms: the thin ring's inextensional bending modes
   (PING, SING) and the free-free beam (BLADE; its first four ratios are
   `Modes.METAL_BAR`'s). The vessel, BEND and GLASS numbers stay designed.
10. **LOOP on a tap.** At RUB 0 the sound decays, so what does LOOP loop?
    *Default: LOOP always sustains contact at a floor pressure, and the
    tap goes into the warm-up.*
11. **Gyre.** The spec names "Gyre" as a separate coupled-string
    rotational engine. **Answered 2026-10-01:** GYRE is in progress
    separately. Its design review is
    [`2026-10-01-gyre-coupled-string-engine-design.md`](2026-10-01-gyre-coupled-string-engine-design.md)
    (#419), and nothing of it is built here.
    - **No shared R0.** GYRE builds its membrane and sympathetic bank from
      `Dsp.Biquad.bandpass`, and couples its strings through a
      `‖M‖ ≤ 1` junction on `Strings.Loop`. Neither needs MERCURY's bank.
    - **No overlapping files.** MERCURY's R0 lands in `Modes`; GYRE's lands
      in `Strings`/`Dsp`.
    - **Both are bowed.** GYRE's bow is ARCO's `Strings.Bow` (STK's
      reflection table on a waveguide). MERCURY's friction is a force law
      on modal velocity.
    - **The same correction twice.** Both reviews replace their brief's
      crossfaded wrap with the house's exact loop (GYRE's G5, MERCURY's
      correction 1).

## Phase 0, as measured — 2026-10-01

The record, with every table and both throwaway sources, is
[`../plans/2026-10-01-mercury-phase-0-spike.md`](../plans/2026-10-01-mercury-phase-0-spike.md).
It took four iterations (the fourth answers the review on #423), and everything was measured; nothing was heard.
It settles R0 and changes R1's mapping.

**The gate passes on the rotation bank:**

| Gate item | Measured |
|---|---|
| Speaks | Mode 0 wins in all 15 voice renders. A rub sustains on mode 0 in all 20 onset renders and in 64 of 64 rubbed corners. |
| In tune | Every R1 voice is within 2.2 cents from MIDI 36 to 84 (fundamental partial). A rub's pitch pull is under 0.6 cents. |
| Decays | Strike only, geometry fixed: no energy rise in any 1 ms block, at any COUPLE, on either table. There was also none with BEND, WATER and COUPLE all at 1 and moving. |
| No pumping | A ±12-semitone, 2 Hz sweep holds energy within 0.9990–0.9997 and the pickup within ±0.35 dB. |
| WATER 0.05 | 4–6 cents of slow drift, where WATER 0 gives 0.4–2.6 (PING and SING). Its spectral distance is about a quarter of a +0.05 BEND step. |
| Bounded | All 128 corners are finite; the worst state is 0.049, against a finger speed of 0.03. |

The trapezoidal candidate, with its coupling one sample late, diverges at
COUPLE ≥ 0.3 and pumps ±6 dB under the sweep. A passive trapezoid needs
the full coupled implicit solve, and it was not pursued.

**R0, now concrete.** One new `Modes` class carries everything below, with
claims tests for passive decay, sweep invariance, the diagonal-dominance
bound and the anchor fix:
- **Modes.** Each mode is a complex state `z = ωq − i·v`. Every step, it is
  rotated by `r·e^{iωT}`, with `r` from t60 and `ω` retunable per sample
  (Phase 0 retuned every 8 samples).
- **Coupling.** Reciprocal spring kicks, `v_i += T·Σ k_ij·q_j`, with
  `k_ij = κ·COUPLE·min(ω_i, ω_j)²`. That is `K = diag(ω²) − A`: springs
  `½·k_ij·(q_i − q_j)²` on modes whose own stiffness is pre-reduced by
  `Σ_j k_ij`, so an uncoupled mode keeps ω_i. The node degree is at most 4
  and the effective κ at most 0.2, so K stays strictly diagonally dominant,
  and therefore positive definite, at every setting. The plain form,
  `diag(ω²) + L`, was measured too (iteration 4). It is equally passive,
  but it moves the note further (+48 to +121 cents against −13 to −85), so
  the compensated form is kept.
- **Contact.** A contact-vector API: read `Σ b_i·v_i`, then apply a force
  back through `b`. The friction is solved by a scalar Newton step against
  the velocity it changes.
- **The anchor fix.** Solve K's eigenvalues once per note (Jacobi, 16×16).
  Scale every mode so the eigenvector that is mostly mode 0 sits on the
  note. Without it, COUPLE flattens the note by 13–85 cents; with it, the
  error is 0.02.

**R1's mapping changes** (iterations 2–4):
- **Pressure** comes from a target linear e-fold,
  `P = (γ₀ + 1/τ)/(|φ′(v_B)|·b₀²)`, not from a multiple of the threshold.
  A multiple of the threshold made onset depend on GLASS.
- **The e-fold is set in periods,** `τ = max(20 ms, 12/f)`. A 20–40 ms
  e-fold pulled MIDI 36 flat by 19–41 cents, and let mode 1 capture SING's
  low notes.
- **A contact-patch taper,** `b_i = ratio_i^(−0.5)`. A finger averages out
  short wavelengths, and with the taper mode 0 wins everywhere.
- **The tap seeds the rub.** With it, onset is 60–205 ms; without it,
  334–1,127 ms.
  - The spec's `tapWeight = cos(π·RUB/2)` is 0 at RUB 1, so R1 gives the
    finger's landing a floor: `max(cos(π·RUB/2)·tap, 0.2)`.
  - That floor measured 95–534 ms at RUB 1 (iteration 4).
  - A floor of 0.05 is slower than none at MIDI 36.
- **WATER's depth is ∝ √WATER, and its rate is 0.6 + 2.4·WATER Hz.** Linear
  depth left WATER 0.05 at 1/30 of a small BEND step.
- **Vessel modes sit 3.5–7 % from their primary partners.** That is what
  makes COUPLE audible as energy exchange: the vessel's share reaches
  0.12–0.62, sloshing by up to 0.82, and WATER moves the share at COUPLE
  0.6 from 0.31 to 0.53 at 300 ms.
- **Cost.** An 8 s note renders in under 0.5 s, so 24 modes stay
  affordable if the audition asks for them.

**Still open, as designed:**
- LOOP (R2), with the friction-lock-under-WATER risk above;
- velocity;
- an aliasing measurement;
- GLASS's friction selectivity;
- every sonic claim, which waits for R1's audition.

## R0, as built — 2026-10-01

**What landed.** R0 is purely additive to `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt`:
no existing line changed, so no existing render can move. The full JVM
suite, `./gradlew --no-daemon test`, including `DeterminismTest`, is the
proof. The claims tests are in `ModesBankTest.kt`.

- **`Modes.Bank(size, rate)`**, the rotation bank:
  - `tune(i, hz, t60)`: retunable on any sample, with the mode's state, and
    so its energy, kept.
  - `connect(i, j, kappa)` / `setKappa(edge, kappa)`: reciprocal springs.
  - `step()`: rotate, then spring kicks.
  - `drive(b, force)`: a force through a participation vector, landing as
    velocity.
  - `velocity(i)`, `displacement(i)`, `velocityAlong(w)` (the pickup, or
    the contact speed), `compliance(b)` and `energy()`.
  - `anchorScale(anchor)` and `coupledHz(anchor)`.
- **`Modes.Friction(a)`**, the contact: `curve`, `slope`, `steepestFall`,
  and `force(driver, surface, pressure, compliance)`.
- **`Modes.symmetricEigen`**, a cyclic Jacobi solve for the once-per-note
  anchor fix.
- **`Modes.MAX_NODE_KAPPA = 1`.**

**Where it differs from the spike, and why:**
- **The stability bounds are enforced, not chosen.** `tune`, `connect` and
  `setKappa` refuse any change that breaks either bound, and a refused call
  leaves the bank untouched. This is the same structural style as GYRE's
  `‖M‖ ≤ 1` junction.
  - **Where the bounds come from.** Eliminating v turns the undamped
    rotate-then-kick step into a leapfrog recurrence,
    `M·(q_{n+1} − 2q_n + q_{n−1}) = −K̂·q_n`, with θ = ωT,
    `M = diag(ω/(T·sin θ))` and `K̂ = diag(2ω·tan(θ/2)/T) − A`. It is stable
    exactly when `K̂` and `4M − K̂` are both positive definite. Diagonal
    dominance, with S a mode's kappa sum and `x = π·f/rate`, gives the two
    bounds:
    - **`S < 1`** (because `tan x ≥ x`). This also keeps K itself positive
      definite.
    - **`S·x·tan x < 1`.** The first version of R0 had only `S < 1`, which
      makes K positive definite but does not make the split stable near
      Nyquist. The review on #428 found a pair of modes at 490 Hz with a
      1 kHz rate and kappa 0.5 that diverged.
  - **The second bound is tight.** A symmetric mode pair 3 % inside it
    stays bounded for 200,000 undamped steps; 3 % outside, it reaches 10¹²
    within 60–240 steps (at 300, 400 and 450 Hz at a 1 kHz rate).
  - **MERCURY never meets it.** At its 19 kHz mode ceiling at 176.4 kHz,
    `x·tan x` is 0.12.
- **The friction solve is bracketed.** The spike's plain 8-step Newton
  failed the new claims test near the unique-root bound: the slope tends
  to 0 there, and Newton oscillates. `|φ| ≤ 1` puts the root within ±p·c of
  the free slip, so `force` runs Newton inside that bracket and bisects
  when a step would leave it. At the pressures MERCURY uses, the bracket
  is about 10⁻⁵ wide and the answer is the spike's to rounding.
- **`anchorScale` is a ratio, so it is invariant.** After the retune it
  returns the same factor. `coupledHz` reads where the anchor really rings,
  and that is the number to check: it lands on the note.
- **`tune` costs an exp, a cos and a sin.** The spike retuned every 8
  samples. R1 decides its own control rate; the class allows every
  sample.

**The claims, measured on the shipped class** (`ModesBankTest`, 14 tests):

| Claim | Test | Measured |
|---|---|---|
| A free mode is an exact damped rotation | 20,000 steps against `r^n·cos(nθ)` | velocity error 6.3×10⁻¹³, displacement error 1.9×10⁻¹⁶ |
| Retuning does not pump energy | ±12 semitones at 2 Hz, every sample, against the unswept bank | energy ratio within 1.4×10⁻¹¹ of 1 |
| A coupled bank never gains energy | Phase 0's object at kappa 0.036 / 0.072 / 0.12 / 0.24 (the last puts the busiest modes at a kappa sum of 0.96) | no 1 ms block above its predecessor; worst block ratio 0.9973 |
| Passive at random tunings at the bound | 6 random 16-mode tunings (40 Hz–20 kHz, t60 0.05–8 s), kappa 0.24 | no rise |
| K stays positive definite | 200 random tunings over 10 octaves, kappa sum 0.96 | every eigenvalue > 0 |
| The bound is enforced | `connect` / `setKappa` past 1, a self-loop, negative kappa | each refused, and a refused spring leaves no edge behind |
| The split's bound is enforced near Nyquist | the review's 490 Hz / 1 kHz / kappa 0.5 case, by `connect` and by retuning a connected mode | both refused; the bank is unchanged |
| Just inside the bound, it is stable | 20 random undamped 6-mode chains up to 0.48·rate, each kappa sum at 0.98 of its bound, 50,000 steps | energy stays within 100× of its start; it never diverges |
| The anchor fix | COUPLE 1, MIDI 60 | −85.3 cents uncorrected (Phase 0: −85.3); `coupledHz` on the note to 10⁻¹²; rendered 0.005 cents |
| Friction solves its own equation | 500 random contacts up to 0.9 of the unique-root bound | force consistent with its slip to 10⁻¹² |
| A rubbed bank sings on its anchor | Phase 0's iteration-3 rub at MIDI 60, COUPLE 0.25, 0.2 landing floor | −0.65 cents (Phase 0: −0.6); level change over the last 0.3 s 1.0004×; anchor 1.5×10⁵ × mode 1; peak 0.027 |
| Deterministic | the rub twice | identical |
| Guarded | stepping an untuned bank; tuning at Nyquist or t60 0 | refused |

**Next: R1, the engine.** Design and plan are as above:
- PING, SING and BLADE;
- seven macros (TUNE, BEND, RUB, WATER, GLASS, COUPLE, HOLD);
- 24 presets;
- registration, a kit, and an audition page for the owner's listening gate;
- the mapping changes from "Phase 0, as measured".

## R1, as built — 2026-10-01

**What landed:**
- **The engine and its patch.** `Mercury.kt`, `MercuryPatch.kt` and
  `MercuryPresets.kt` (8 per voice).
- **Registration.** `Patches.fromJsonValue`, `Velocity.macroSpecsFor` and
  `Presets` (`forVoice`, `all()`; the KDoc count is now fifteen).
- **The kit.** `SynthKits.mercury()`, `MercuryKitGenerator` and
  `testkit/SnipSnap Mercury Kit/`.
- **The audition.** `MercuryAuditionGenerator` and
  `audition/mercury-audition.html`, with the gradle tasks
  `generateMercuryKit` and `generateMercuryAudition`;
  `testkit/mercury-audition/` is gitignored.
- **Tests.** The claims (`MercuryTest`), the preset contract
  (`MercuryPresetsTest`), and an arm each in `DeterminismTest`,
  `PadRecipeTest`, `PresetsTest` and `SynthKitTest`.
- **The roadmap row, S21.** GYRE's review expected "S21 or later", so S22 is
  free for it.
- **Not touched.** The phone's picker, which is R1.1 and needs a machine
  with the SDK.

**The object, as built.** It is Phase 0's iteration-3 object on
`Modes.Bank`:
- PING runs C4–C6; SING and BLADE G3–G5.
- Up to 12 primary modes and 4 vessel modes. A mode that could reach
  40 kHz at the widest bend is dropped, so a high note has fewer modes.
  That keeps every mode far inside the bank's split bound: `x·tan x` is
  0.62 at 40 kHz, and the kappa sum is under 0.77.
- Every mode fades out between 14 and 19 kHz.
- The control rate is 8 samples.
- The friction is `Modes.Friction(5000)`, with the finger at 0.03.

**Where R1 differs from the plan, and why:**
- **GLASS also roughens the contact.** On a rubbed voice, friction locks
  onto the fundamental, so GLASS's damping and tilt barely changed the
  sound. By band energy, SING's GLASS 0 against 1 was 0.001.
  - The spec asks GLASS to change the friction too ("low GLASS should
    favour broader, more damped motion"). So the pressure now jitters by
    up to `ROUGHNESS` 0.6 at GLASS 0 and not at all at GLASS 1.
  - The jitter is seeded noise smoothed below 2 kHz. Listening values.
- **Pressure goes as RUB².** Linear in RUB, the rub crossed its sustain
  threshold at RUB 0.05, so PING's default of 0.08 would have been a weak,
  slow rub. As RUB² it crosses at about 0.19 at C4. Below that, RUB is a
  tap with a slightly longer ring.
- **HOLD is the contact, then the object rings out.**
  - The ring is half the fundamental's t60, clamped to 0.4–2.5 s.
  - It is released on a raised cosine over the ring's last 40 %: a
    documented release, not a cut.
  - At the default GLASS, every render is past the classifier's 1.5 s
    line and so is filed LOOP by length. The shortest MERCURY (GLASS 0,
    HOLD 0) is 0.93 s, is filed PERC, and the classifier hears PERC.
  - The committed kit is 7.8 MB, the largest, because glass rings.
- **PING's HOLD is mostly length.** A tap has nothing to hold, so HOLD on
  PING moves the sound by 0.13 on ARCO's measure, against 0.5–1.4 for every
  other knob. It clears the bar, and its meaning on a tap is a question for
  the gate.
- **Velocity falls back to `soften`,** as BORE's does (decision 8). No
  override is registered until a monotonic centroid sweep earns one.

**The claims, measured** (`MercuryTest`):

| Claim | Measured |
|---|---|
| In tune (BEND centred, no water), every voice at TUNE 0, .5 and 1 | within 0.53 cents |
| The bend gesture | BEND 1 starts +161 cents and BEND 0 −166 cents in the first 0.1 s; both settle within 0.54 cents after 2.5 s |
| No dead knob, on ARCO's measure (bar 0.1) | every knob 0.13 (PING's HOLD) to 1.45; WATER 0.05 against 0, 1.18–1.29 |
| RUB turns a tap into a rub | level at the end of a 2.4 s contact over its start: tap 0.07 / 0.01, rub 1.15 / 1.25 (SING / BLADE) |
| The rub locks on the note at the bottom of the range, at both ends of GLASS, COUPLE 1 | within 0.42 cents |
| The anchor fix at COUPLE 1 | within 0.41 cents |
| Every corner of the knobs, both ends of TUNE, raw | 192 renders, all finite, worst raw peak 0.043 |
| The filed class follows the length line | the shortest is 0.93 s, filed and heard PERC; the default is filed and heard LOOP |
| Determinism, length, clamping, refusal, velocity fallback | all hold |

**The roster** (`MercuryPresetsTest`). All 24 presets render clean at the
melodic loudness, round-trip through JSON, carry all seven knobs, and
spread apart. None is named after an engine, a voice (R2's EDDY, VESSEL
and SHARD included) or a rack section. Each lands on the note its comment
names, and the classifier hears none of them as a drum: all are LOOP by
length.

**Next.** The owner's audition. The page is the kit, then each voice's
range, a phrase, three velocities, every knob's ends, the dead-zone
probes and its presets, then the five interaction grids: 166 clips in all.
Then R1.1 (the phone) and R2 (LOOP, `Keys.mercuryPad`, EDDY, VESSEL and
SHARD).

