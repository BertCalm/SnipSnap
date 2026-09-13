# FX rack expansion — five rack sections, two keyed treatments

**Date:** 2026-09-13
**Status:** design approved, implementation plan pending

## Summary

Add five sections to the per-pad FX rack (SPIKE, RING, DUST, PHASE, PITCH) and
two treatments to the keyed family (ROLL, GATE), preceded by two refactors that
make the additions safe and followed by a regroup of the PAD SHEET's treatment
card.

The rack goes from 12 sections to 17. The card goes from 20 chips to 27.

## Why the refactors come first

`FxChain` enumerates its sections by hand in six places, and two more sites
outside it repeat the list:

- `Treatments.chain`'s `scale()` (`synth/.../Treatments.kt:57-71`) — a section
  missing here is **not scaled by the AMT knob**, silently.
- `Breed.cross`'s `FxChain(...)` call (`shell/.../Breed.kt:184-197`) — a section
  missing here is **silently dropped from every bred child**.

No test catches either. Adding five sections means ten new chances to hit them.

A third problem is latent: `scale()` computes `v * amount`, which scales every
macro toward **zero**. That is correct only when zero is the macro's neutral.
`Eq`'s macros are `0.5 = flat` (`Eq.kt:20-24`), so AMT 0.5 on a flat EQ is a 6 dB
cut. It is unreachable today only because no shipped treatment sets `eq`. PITCH
would be the first section to actually trip it, since `0.5 = native`.

## Landing sequence

| | Landing | Behavior change |
|---|---|---|
| L1 | `FxChain` section registry | none — pure refactor |
| L2 | `MacroSpec` gains `neutral` | latent EQ fix only |
| L3 | SPIKE, RING, DUST, PHASE | four new sections, CLI-reachable |
| L4 | PITCH — transport stage | new section + `process()` restructure |
| L5 | ROLL, GATE in `Keyed` | two new keyed treatments |
| L6 | PAD SHEET regroup | card reshuffles, all 27 chips placed |

The regroup lands last so the card is reshuffled **once**, with every new chip
placed, rather than three times. New sections are reachable from the CLI as soon
as they exist — `TreatCommand` is generic over `Treatments.names`.

## L1 — the section registry

`FxChain` stays a `data class`. `Treatments.EXTRA` constructs it with named
arguments, `FxTest` relies on `==`, and `Treatments.chain` uses `copy()`;
replacing it with a map would break all three for no gain.

What changes is that every enumeration site reads one table:

```kotlin
internal class Section(
    val name: String,
    val macros: List<MacroSpec>,
    val get: (FxChain) -> Map<String, Float>?,
    val with: (FxChain, Map<String, Float>?) -> FxChain,
    val run: (Snip, Map<String, Float>) -> Snip,
    /** TRANSPORT (speed) and ARRIVAL (swell) run before the tail budget is measured. */
    val stage: Stage = Stage.RACK,
)

enum class Stage { TRANSPORT, ARRIVAL, RACK }

/** Rack order. This list IS the order — in the signal path and in JSON alike. */
internal val SECTIONS: List<Section> = listOf(/* speed, swell, smear, ghost, ... */)
```

**`Section` stays `internal`; the accessors must be public.** Kotlin `internal`
is Gradle-module-scoped, and `Breed` lives in `:shell` while `FxChain` lives in
`:synth` — so an internal `SECTIONS` would be invisible to the site with the
worst silent-failure trap. `FxChain` exposes:

```kotlin
companion object {
    /** Every section's name, in rack order. */
    val SECTION_NAMES: List<String>
    /** [name]'s macro specs. */
    fun macrosOf(name: String): List<MacroSpec>
}
/** [name]'s macros on this chain, or null when bypassed. */
fun section(name: String): Map<String, Float>?
/** This chain with [name] set to [macros] (null bypasses it). */
fun withSection(name: String, macros: Map<String, Float>?): FxChain
```

`Treatments` and `Breed` iterate `SECTION_NAMES` and use those two accessors,
never the `Section` descriptor.

**Stage, not a boolean.** `process()` already runs SWELL before the tail budget
is measured, so a single `transport` flag cannot describe the rack. `Section`
carries `stage: Stage` where `Stage` is `TRANSPORT` (speed), `ARRIVAL` (swell)
and `RACK` (everything else, run by `processRest`).

Sites that become iterations over `SECTIONS`:

- `FxChain.init` macro-name and range validation
- `FxChain.isBypass`
- `FxChain.processRest`
- `FxChain.toJsonValue`
- `FxChain.fromJsonValue`
- `Treatments.chain`'s `scale()`
- `Breed.cross`'s `FxChain(...)` call

**Free correctness win.** Rack order is currently hand-maintained in *two*
places — `processRest` and `toJsonValue`'s pair list — and `FxTest.kt:251/303/413`
assert JSON order with `indexOf` comparisons. The two lists agree today, but
nothing makes them agree. One `SECTIONS` list feeding both turns a coincidence
into a structural guarantee.

`Section` holding function references gives `FxChain` a compile-time dependency
on all 17 section objects. Acceptable within one module; noted as real coupling.

**Acceptance:** the entire existing test suite passes unmodified. A refactor that
needs test edits is not behavior-preserving.

## L2 — `MacroSpec` learns its neutral

```kotlin
data class MacroSpec(val name: String, val default: Float, val neutral: Float = 0f)
```

`scale()` becomes `spec.neutral + (v - spec.neutral) * amount`.

For `neutral = 0` this reduces exactly to today's `v * amount`, so every existing
section is bit-identical. Only `Eq` changes, gaining `neutral = 0.5f` on its
three macros — a path no shipped treatment currently reaches.

`neutral` is defaulted, so every existing `MacroSpec("X", 0.5f)` call across all
synth engines compiles untouched.

**A `snapped` flag was considered and rejected.** Sections snap their own macros
internally (`Speed.process` maps 0..1 onto 25 semitone steps, as `Wobble` maps
onto divisions), so a scaled macro snaps correctly without `scale()` knowing
about grids. `neutral` alone grades PITCH musically — macro 0.75 (+6 st) at AMT
0.5 gives 0.625 (+3 st) — where an exclusion rule would have made AMT a dead knob
on it.

## L3 — four rack sections

Names checked against `ALL_SEGMENTS`, `CHARACTER_FOR`, `KEYED_FOR`,
`Treatments.names` and `FxChain`'s field names. All clear.

| Section | Field | Treatment | Chip |
|---|---|---|---|
| SPIKE | `spike` | `"spiked"` | SPIKE |
| RING | `ring` | `"ringed"` | RING |
| DUST | `dust` | `"dusted"` | DUST |
| PHASE | `phase` | `"phased"` | PHASE |

`HIT` was rejected as a name: this codebase says "the hit" in nearly every KDoc
to mean the sample itself. `PUNCH` was unavailable — it already maps to
`"punched"` (squash + crunch).

**SPIKE** · `ATTACK` (0.4), `SUSTAIN` (0.5, **neutral 0.5**) · *after GHOST, before EQ*
Two envelope followers at different speeds; their difference is the transient.
ATTACK gains that difference, SUSTAIN gains the residual — bipolar, so 0.5 is
untouched, below tightens, above swells. Peak-matched.
The macro is `SUSTAIN`, not `BODY`: `BODY` is already a chip on the card
(`KEYED_FOR["BODY"] = "bodied"`, the resonator bank), and two unrelated meanings
of one word on the same screen is the problem that ruled out `HIT`.
*Position:* SMEAR removes the attack, GHOST removes attack and tone, SPIKE
exaggerates it. Same axis, same zone — anatomy before tone.

**RING** · `FREQ` (0.35), `MIX` (0.4) · *between CRUNCH and DUB*
Multiply by a sine, `FREQ` exp-mapped ~30 Hz–3 kHz. Deterministic, no tail,
peak-matched.
*Position:* CRUNCH and DUB are the converter's damage; RING is damage of a
different species — inharmonic rather than quantized.

**DUST** · `CRACKLE` (0.4), `RUMBLE` (0.25), `HISS` (0.3) · *after DUB, before TAPE*
Vinyl surface noise: sparse bandpassed impulses, low-passed rumble under ~60 Hz,
capped hiss. Seeded like `Swell.SEED`, so a pad crackles identically forever.
Follow `TapeWear`'s precedent for honest ceilings (its hiss caps at −48 dBFS).
*Position:* before TAPE deliberately — TAPE then processes the crackle along with
everything else, because the chain being modeled is a record dubbed to tape.
*Constraint:* the only section that **adds** signal. Noise beds are scaled
relative to the input's peak before summing, then the sum is peak-matched.

**PHASE** · `RATE` (0.35), `DEPTH` (0.6), `FEEDBACK` (0.3) · *after TAPE, before ECHO*
Four-stage allpass cascade, LFO-swept, LFO phase starting at zero so it is
deterministic without a seed. No tail.
*Position:* the sweep happens to the finished tone, and ECHO's repeats then carry
the sweep at earlier LFO positions.

None of the four change length, so `capTail` and `MAX_CHAIN_TAIL_SECONDS` are
untouched until L4.

## L4 — PITCH as a transport stage

**Kotlin object `Speed`**, field `speed`, treatment `"pitched"`, chip **PITCH**.

`com.snipsnap.audio.Pitch` already exists — the pitch *detector*, used by
`Keyed.isPitched`. A `synth.Pitch` would compile but would sit confusingly beside
it. `DISPLAY_LABELS` keeps the user-facing word as PITCH.

One macro, `SEMITONES` (default **0.5 = native**, neutral 0.5, ±12 semitones at
the ends).

**This section deliberately breaks the stem convention.** Every other section
keeps field and treatment on one stem — `smear`/`"smeared"`, `dub`/`"dubbed"`,
`swell`/`"swelled"`. Here the chain is `speed` (field, JSON key) → `"pitched"`
(treatment) → `PITCH` (chip), with two renames in it, and
`PadSheet.segmentForCharacter` does a reverse lookup across that chain. The
alternative, `"speeded"`, is consistent and ugly. The break is intentional, but
it is the one place an implementer cannot derive one name from another, so all
three must be read from this spec rather than inferred.

Varispeed only — the sampler's actual mechanism, where pitch is speed and length
follows. No length-preserving mode: SWELL owns paulstretch and GRAINS owns
granular, so a KEEP switch would be a third stretch engine in a rack whose
discipline is one idea per knob.

*Position:* the transport, not an effect. MOTION's KDoc established the principle
— *"you are sampling a machine, and this is the machine's transport."* MOTION is
the capstan letting go at the end; PITCH is the pitch knob before the sound ever
leaves.

```kotlin
fun process(snip: Snip): Snip {
    val pitched = speed?.let { Speed.process(snip, it) } ?: snip
    val swelled = swell?.let { Swell.process(pitched, it) } ?: pitched
    return capTail(swelled, processRest(swelled))
}
```

**Tail budget.** `capTail(input, output)` measures from whatever it is handed.
Handed the *pitched* signal, the arithmetic is already correct: a hit pitched
down an octave is twice as long, and its one-second allowance is measured from
the new length. The extra duration is the user's instruction, not tail, so it is
not charged against a budget that exists to stop echo and reverb running away.

What changes:

- `MAX_CHAIN_TAIL_SECONDS`'s doc comment — measured from the pitched, swelled sound
- PITCH declares **no length cap of its own**; capping a varispeed truncates the note
- `Section.stage = Stage.TRANSPORT` so `processRest` skips it and `process` runs it first
- `Section.name` is `"speed"`, matching the field, so the JSON key is `"speed"` like
  every other section's. PITCH is the chip's display word only. It emits first in
  JSON; an old `kit.json` without the key round-trips as bypass

**Reuse:** `Motion.stop`/`start` already do a variable-speed read with a
linear-interpolated head. PITCH is that read at a constant rate. Extract the
resampling read into `Dsp` so both varispeed stages share one interpolator.

**Do not bump `FxChain.VERSION`.** `fromJsonValue` throws on any version
mismatch, so a bump breaks every existing `kit.json`. Every past
section (EQ, SMEAR, GHOST, MOTION, DUB, SWELL) landed at VERSION 1, because an
absent key round-trips as bypass.

## L5 — ROLL and GATE in the keyed family

`Keyed.kt`'s KDoc states the boundary: *"treatments that read the kit itself —
its key, its tempo — where the rack's characters read nothing but the sound."*
ROLL and GATE read the kit's tempo, so they belong here, not in the rack. No BPM
reaches `FxChain`, and no section signature changes.

WOBBLE was dropped from scope entirely: `"wobbled"` is already a shipped keyed
treatment.

Both are `Snip → Snip` with AMOUNT, peak-matched, outside `capTail`.

**ROLL** → `"rolled"` · reuses `Dials.division`
The hit's first `division` worth of audio, repeated across its length, each
repeat decaying. AMOUNT is how much of the hit the roll replaces.
Label `"1/16 AT 92 BPM"`, matching `wobbled`'s format.
Refusal: a hit shorter than one division — *"the hit is shorter than one 1/16 at
92 BPM."*

**GATE** → `"gated"` · reuses `Dials.division`
A square envelope at the division, with short fades so edges do not click.
AMOUNT is depth: 0 open, 1 full chop. Same label format, same refusal.

Both reuse `Dials.division` rather than adding fields — only one treatment
applies at a time, and `Dials` is explicitly *"the dials the phone does not draw."*

Touchpoints: `Keyed.NAMES`, `Keyed.apply`, `Keyed.refusal`, `PadSheet.KEYED_FOR`,
and **one new CLI command each** — `RollCommand.kt` and `GateCommand.kt`,
following `WobbleCommand.kt`'s shape (`model.keyedPad(slot, name, amount, dials)`).
`TreatCommand`'s genericity covers characters only, not the keyed family.

## L6 — the card regroup

The card dispatches through three families (`PadSheet.treatmentFor:193-203`):
`ERA_FOR` → `CHARACTER_FOR` → `KEYED_FOR`. Row one is the Time Machine eras and
is structurally pinned, because `eraFor` requires `segment in SEGMENTS`.

Rows below it are free: **TUNE is already a keyed chip on a character row**, which
is the precedent that permits grouping by what an effect does rather than by how
it is implemented.

```
ERAS       NONE   CRUSH  TAPE   DIRT    SMEAR
ANATOMY    SWELL  TAIL   SKIM   GHOST   SPIKE
CHARACTER  PUNCH  RING   DUB    DUST    PHASE
TIME       SLAP   WASH   ROLL   GATE
TRANSPORT  FLIP   STOP   START  PITCH
THE KIT    TUNE   BODY   WOBBLE ETERNAL
```

27 chips; widest row is 5, unchanged, so no chip narrows. ROLL and GATE sit in
TIME rather than with the keyed family, because the card should group by effect.

Every chip except row one moves. Muscle memory breaks once, deliberately, for a
card that teaches the rack's logic instead of its implementation history.

## Testing

### The registry's largest payoff

`FxTest.kt:150-171`'s shared-contract test registers **3 of 11** sections. Driving
it from `SECTIONS` gives all 17 the contract test automatically, including the
eight uncovered since they shipped.

### The test that closes both traps

One parameterized test over `SECTIONS`:

```
for each section S:
  build FxChain with only S set to non-default macros
  assert JSON round-trip preserves it          (fromJsonValue/toJsonValue wired)
  assert isBypass == false                     (isBypass wired)
  assert process() differs from input          (processRest wired)
  assert Treatments.chain scales it at AMT 0.5 (scale() wired — trap #1)
  assert Breed.cross preserves it              (cross() wired — trap #2)
```

Neither silent failure is reachable after this, for any section added from here on.

### Per-section invariants

Every new section clears the existing bar: determinism across two runs, output
peak ≤ input peak, samples finite and in −1..1, stereo channels stay identical,
zero-macros is a true bypass, JSON key order matches rack order.

| Section | Extra invariant |
|---|---|
| DUST | All-zeros is **bit-identical to the input**, not input-plus-inaudible-noise |
| RING | **Exempt from classifier identity**, documented as GHOST is |
| SPEED | Pitched down 12 st ≈ doubles length; `capTail` must not truncate it |
| SPIKE | `SUSTAIN` at 0.5 is a true no-op — first test of `neutral` on a non-EQ macro |

### Two structural tests

**AMT neutrality (L2).** For every section and macro: `scale(amount = 0)` lands on
the macro's `neutral`; `amount = 1` is unchanged. Catches the latent EQ bug.

**Chip inventory (L6).** The regroup rewrites the very assertions that would
police it (`PadSheetTest.kt:109-116` hard-asserts row contents), so the test
checks properties instead: `ALL_SEGMENTS` has no duplicates, size is 27, and
every segment except NONE resolves through `treatmentFor` to a non-null
treatment.

## Error handling

ROLL and GATE register in `Keyed`'s existing pattern: `refusal()` answers *before*
anything is touched, returning words rather than throwing, so the phone says "the
hit is shorter than one 1/16 at 92 BPM" instead of failing silently.

## Documentation to update

- `README.md:219-239` — the rack section list and processing-order string
- `docs/CLI.md:335-351` — `treat`'s character list and the card's row layout
- `docs/FEATURE_PLAN.md` — a new Wave entry in the VV format (owner, size, exit test)
- `FxChain`'s own rack-order KDoc (`FxChain.kt:8-22`)

## Open risks

1. **RING and classifier identity.** Ring mod destroys pitch identity by design,
   so `FxTest`'s "a treated kick is still a kick" will fail at any useful depth.
   Resolved as an explicit documented exception rather than by capping MIX, which
   would make the section timid for a test's sake.
2. **`"ringed"`** is the weakest of the four treatment names. Replaceable without
   affecting anything else in this design.
3. **The regroup cannot be policed by the test it rewrites.** L6 needs a
   reviewer's eye on the chip inventory: 27 in, 27 out, no duplicates.
4. **`Section` couples `FxChain` to all 17 section objects** at compile time.
   Acceptable within one module, but it is real coupling and worth revisiting if
   the rack is ever split across modules.
