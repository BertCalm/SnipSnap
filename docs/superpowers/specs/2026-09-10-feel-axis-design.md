# Feel Axis — Design (Humanization, Subsystem A)

**Goal:** Replace GROOVE's hardcoded per-note jitter with a single bipolar
TIGHT ↔ AS PLAYED ↔ LOOSE control, built on the existing `GrooveFeel`
template model, so that quantize becomes reachable from the phone, PROG A
stops misrepresenting the capture, and humanize produces a coherent groove
instead of scatter.

**Non-goal:** the `.pocket` / steal-the-feel UI (subsystem B), per-loop live
drift (C), and extracting a feel from a live take (D). This spec only
extends the data model far enough that B does not have to break its own
file format later.

---

## 1. What is wrong today

- `computeProgram(0)` is `GrooveVariations.humanize(base, 0.5f, seed)` —
  every note draws an independent random offset of up to ±60 pulses. The
  program is labelled **"PROG A · AS CAPTURED / THE BREAK, AS PLAYED"**.
  It is neither.
- Independent per-note error is not what human timing is. Human deviation
  is *biased* (a consistent pocket) and *correlated* (drift persists).
  Uncorrelated noise yields a sloppy take, not a human one.
- Quantize is unreachable from the app. The only path from a loose take to
  a tight one is `FORK TO E ▸`, which also consumes the single editable
  program slot and may demand a `REPLACE E?` confirm.
- `GrooveVariations.quantize` **clamps** a note that rounds past the loop
  end to `limit - 1`, which in a 240-pulse grid is never itself a grid
  multiple — an off-grid ghost inside the clip named "Tight".
  `GrooveEdit.quantized` already solves this by wrapping and deduping; the
  older sibling never learned. This ships to the MPC and into ARRANGE.
- `HUMANIZE ⚄` works only by bumping a seed that PROG A alone reads, so
  any fix to PROG A kills that button.

## 2. Decisions taken

| Decision | Choice | Why |
|---|---|---|
| Model | Generated `GrooveFeel.Template`, not per-note jitter | Consistency per metric position *is* groove; also exactly assertable in tests |
| Control shape | One bipolar axis, detented at centre | TIGHTEN toggle, LOOSENESS and DEPTH are the same parameter at different signs |
| Controls in A | Two: the axis and `⚄ RESEED` | Per-lane pocket and accent structure relocate to B, arriving learned from real donors rather than dialled by hand |
| When applied | Baked into the clip, deterministic, reseedable | Exports faithfully; per-loop variation is subsystem C |
| Lane dimension | Added to the data model now, not exposed in A's UI | B populates it; adding it later would break `.pocket` files already in the world |
| Blend mode | The axis itself: left half blends toward the grid, right half adds offsets | A single `REPLACE` (snap-to-grid *then* offset) would destroy a live take's timing at every setting. `REPLACE` belongs to B, where a donor template is applied wholesale |
| Persistence | Resets per kit open, like `swingPercent` | An invisible persisted setting that silently alters exports is the trap this codebase keeps closing |

## 3. Data model

```kotlin
// kit/src/main/kotlin/com/snipsnap/kit/GrooveFeel.kt
data class Template(
    val offsets: List<Long?>,                                  // 16 positions, pulses late(+)/early(-)
    val accents: List<Float?>,                                 // 16 positions, velocity ratio
    val laneOffsets: Map<GrooveEdit.Lane, Long> = emptyMap(),   // NEW - constant push/drag per lane
    val laneAccents: Map<GrooveEdit.Lane, Float> = emptyMap(),  // NEW - constant velocity scale per lane
)
```

Both new maps default empty, which is exactly how a v1 `.pocket` reads.

**Extraction must decompose, not double-count.** If `extract()` learns
position offsets and lane offsets independently from the same donor, both
capture the same underlying deviation and applying both overshoots the lean.
Order is fixed:

1. `laneOffsets[lane]` = median over all that lane's notes of
   `timePulses - gridOf(timePulses)`.
2. `offsets[pos]` = median over notes at `pos` of
   `(timePulses - gridOf(timePulses)) - laneOffsets[lane]` — the **residual**.

Then `grid + laneOffset + positionOffset` reconstructs the original rather
than exceeding it. Accents decompose the same way by division, not
subtraction.

Notes on pads outside the five named lanes have no lane, so they take
position offsets only. Stated rather than left to fall through.

**Scope note.** A adds the fields and generates templates; it does **not**
change `GrooveFeel.extract`, which keeps returning empty lane maps. The
decomposition rule above is specified here because it is the trap B must not
fall into, and because the file format has to carry the fields before B
writes any `.pocket` into the world. A v2 pocket written by today's CLI
therefore has empty lane maps, which is lossless for what A can express.

## 4. The transform

```kotlin
/** t in -1f..+1f. -1 = fully snapped, 0 = exactly as played, +1 = fully leaned. */
fun applyFeel(clip: Mpc3Clip, t: Float, template: Template): Mpc3Clip {
    if (t == 0f) return clip                       // structural identity, not arithmetic
    val grid16 = Mpc3Clip.PULSES_PER_16TH
    return clip.copy(
        notes = GrooveEdit.dedupeLouder(
            clip.notes.map { n ->
                // Unwrapped on purpose - the lerp target must not wrap. See below.
                val grid = (n.timePulses + grid16 / 2) / grid16 * grid16
                val pos = ((grid / grid16) % GrooveFeel.POSITIONS).toInt()
                val lane = GrooveEdit.Lane.entries.firstOrNull { GrooveEdit.noteFor(it) == n.note }
                val lead: Long = if (t < 0f) {
                    Math.round(n.timePulses + (grid - n.timePulses) * -t.toDouble())
                } else {
                    n.timePulses + Math.round(t.toDouble() * (template.offsets[pos] ?: 0L))
                }
                val withPocket = lead + (lane?.let { template.laneOffsets[it] } ?: 0L)
                n.copy(timePulses = LiveRecord.wrapped(withPocket, clip.bars))
            },
        ),
    )
}
```

`dedupeLouder` is required, not decorative: pulling two nearby hits toward the
same grid step at `t = -1` collides them, exactly as `GrooveEdit.quantized`
already handles. One note per `(note, pulse)` address survives, the louder one.

Four properties this must hold, each of which is a test in §8:

**Identity at zero is structural.** `if (t == 0f) return clip` — "as played"
must not be a claim resting on float round-tripping of Long pulses.

**Continuity at zero.** `t → 0⁻` gives `lerp(x, grid, 0) = x`; `t → 0⁺`
gives `x + 0 = x`. The halves meet, so the detent is not a discontinuity.

**The lerp target must be the UNWRAPPED grid position.** A note at pulse
7600 in a 2-bar clip snaps to 7680, which wraps to 0. Lerping toward the
*wrapped* target gives `lerp(7600, 0, 0.5) = 3800` — the middle of the bar,
not halfway to the downbeat. Snap without wrapping, lerp, and wrap only at
the very end: `lerp(7600, 7680, 0.5) = 7640`, and at `t = -1`,
`wrapped(7680) = 0`, the correct wrapping quantize.

**Wrap, never clamp.** The ADD path can push a hit past the loop end.
`LiveRecord.wrapped` is the codebase's single rule and must be reused —
introducing a fresh clamp on the loose end of the same slider whose tight
end we are fixing for clamping would be indefensible.

**The pocket is orthogonal to the axis.** `laneOffset` is added *after* the
blend and is unaffected by `t`, so "maximally tight with a dragging snare"
stays reachable. That combination is most of the MPC catalogue; a naive
single control would make it impossible.

## 5. Feel generation

A uniform random 16-vector can place a large late offset on position 0 —
beat 1 — and moving beat 1 makes the loop sound *wrong*, not human. Real
grooves are weighted by metric position: downbeats barely move, eighth
offbeats move more, the 'e' and 'a' sixteenths move most.

```kotlin
/** Ceiling on a generated offset at full weight: 3/8 of a 16th. Wide enough to
 *  lean audibly, narrow enough that a note stays clearly attached to its own step. */
const val FEEL_MAX_OFFSET_PULSES = 90L

/** Offsets scale by metric position - a groove leans on its weak beats, not its pulse. */
val FEEL_WEIGHT: List<Float> = listOf(
    0.25f, 1f, 0.6f, 1f,   // 1  e  &  a
    0.25f, 1f, 0.6f, 1f,   // 2  e  &  a
    0.25f, 1f, 0.6f, 1f,   // 3  e  &  a
    0.25f, 1f, 0.6f, 1f,   // 4  e  &  a
)

fun generated(seed: Int): Template   // 16 draws at FULL magnitude; `t` scales at apply time
```

Generation produces a full-magnitude template so that reseeding is
independent of the axis position. `t` scales it in `applyFeel`.
Accents are not generated in A — see §9.

## 6. UI

No new interaction primitive. Every continuous value in this app is a
`−`/`+` stepper with a two-line readout; introducing drag beside a
scrolling needle roll would be a new interaction and a poor one on a phone.
FEEL reuses the SWING row shape exactly.

```
[► PLAY]  [ −  SWING 62%  + ]       unchanged
          [    RIDES PROG B   ]

[ −    FEEL · LOOSE 40% · #47   + ] new, full width
  [       RIDES A · C · D         ]

[⚄ RESEED] [EDIT STEPS] [MIDI ▸]    HUMANIZE's slot, relabelled
```

- Range `-100..+100` step `20` — eleven positions, five taps centre to
  either end, and `0` falls on a step so the detent is real.
- Readout reads `TIGHT n%` / `AS PLAYED` / `LOOSE n%`. The seed (`#47`)
  shows only when `t > 0`, so a roll worth keeping can be returned to by
  hand before B ships `.pocket` files.
- **Tapping the readout snaps back to `AS PLAYED`.** Without it, traversing
  the axis end to end is ten taps and A/B-ing the extremes — the main thing
  anyone will do — is tedious.
- `⚄ RESEED` takes the freed `HUMANIZE` slot, disabled when `t <= 0`, since
  rerolling a feel you are not applying does nothing.
- `FORK TO E ▸` in the post-take row becomes **`EDIT THIS TAKE`**. With the
  axis carrying quantize, that button's job is no longer "make it tight",
  it is "make it editable".
- `PROG A` renames from **"AS CAPTURED / THE BREAK, AS PLAYED"** to
  **"PROG A · THE BREAK"**. At `t = +80` the old label claims the opposite
  of what it is; the honesty now belongs to the FEEL readout.
- State is `remember(kitDir)` alongside `swingPercent`.

**Layout risk, called out as work rather than a follow-up:** this screen
shipped a layout collapse two builds ago (a stray `fillMaxHeight` starving
`NeedleRoll`'s weight). Adding a row is exactly the change that
reintroduces it, and no test will catch it. Device verification is part of
this task.

## 7. Consumers — all four, or it is a preview and not a lens

`base` is read in four places. A feel that reaches fewer than all four is a
control that lies.

| Consumer | Today | Change |
|---|---|---|
| Needle roll | `computeProgram(...)` | takes `t` + template; applies feel to `base` before the per-index transform |
| Playback clock | calls `computeProgram` | none — shares the function, so audio and display cannot disagree |
| `exportMidi` | `GrooveVariations.standard(exportBase, swingPercent)` | `standard(applyFeel(exportBase, feel, template), swingPercent)` |
| `Arranger.arrange` | loads base from disk, `standard(base)` | gains `swingPercent` and feel params, defaulted neutral so the CLI is unaffected |

**PROG B is deliberately exempt.** `GrooveVariations.swing` quantizes
internally, so any upstream feel is wiped. That is by design — B's own
subtitle is "ON THE GRID, PUSHED LATE". The fix is disclosure in the idiom
this screen already uses (SWING's readout says "RIDES PROG B"), so FEEL's
says **"RIDES A · C · D"**. A test pins it so it cannot drift silently.

**Pre-existing bug fixed here:** `Arranger.arrange` calls `standard(base)`
with no `swingPercent`, so **ARRANGE has silently ignored the SWING control
since it shipped**. We are editing that exact line to thread feel through;
fixing swing at the same time is one argument. Note this changes what
existing kits' arrangements sound like.

## 8. Migration and behaviour changes

- `PocketStore.VERSION` 1 → 2. Writes `laneOffsets`/`laneAccents`; `read`
  accepts v1 by leaving both empty. The strict "unknown versions refused"
  rule is preserved by accepting 1 and 2 explicitly, not by relaxing it.
- `GrooveVariations.quantize` is replaced by the wrapping, deduping
  implementation (shared with `GrooveEdit.quantized` — one rule, one copy).
  **This changes the "Tight" clip in every export and every arrangement.**
  It is a fix, but it is a behaviour change and must be stated in the
  commit, not discovered.
- `GrooveVariations.humanize` becomes dead once `computeProgram` stops
  calling it. Verify no other callers (`grep`, including `cli/` and tests)
  and then remove it — do not assume.

## 9. Out of scope, and why

- **Accent/dynamics generation.** SnipSnap's material is chopped breaks;
  `ReadGroove` reads real transient amplitudes, so captured velocities are
  already a human performance and random variation on top is noise on
  signal. Where flat velocities *do* occur (step-editor patterns write a
  constant `0.9f`), the useful fix is accent *structure* learned from a
  donor — subsystem B, done better.
- **Per-lane push/drag UI.** The data model carries it; B supplies it
  learned from real drummers. Nobody dials "snare +18 pulses" by hand when
  they can ask for a break's pocket.
- **Per-loop variation.** Subsystem C.

## 10. Known limits, stated rather than discovered

- **The grid is 16ths, permanently** (`STEPS_PER_BAR = 16`,
  `POSITIONS = 16`). Tightening a break with 32nd ghost notes or a triplet
  fill will destroy them. Consistent with the step editor and the feel
  template, and it only bites when tight is explicitly asked for — but it
  is a real limit.
- **On an already-quantized source the left half does nothing**, because
  "as played" already *is* the grid. True for MIDI-imported and Euclid
  patterns; not true for chopped breaks, which is the main path. The
  readout should say so at the detent rather than let the user drag into a
  no-op.
- **A roll cannot be kept.** The seed display makes it reproducible by
  hand; B makes it saveable.

## 11. Test plan

Pure arithmetic, no IO, so everything asserts exactly.

| Test | Asserts |
|---|---|
| `t = -1` equals `GrooveEdit.quantized` | the tight endpoint is the correct wrapping quantize |
| `t = 0` is note-for-note identity | the claim "AS PLAYED" |
| `t = +1` equals `original + offsets` | the loose endpoint |
| `t = ±0.001` ≈ original | continuity across the detent from both sides |
| note at 7600, `t = -0.5`, 2 bars → 7640 | the unwrapped-lerp-target rule, not 3800 |
| note at 7600 with a late offset, `t = +1` | wraps to the next pass, never clamps |
| lane offset survives `t = -1` | pocket orthogonality |
| off-lane note takes position offset only | no lane offset, no drop |
| generated offset at position 0 ≤ `0.25 × FEEL_MAX` | metric weighting |
| PROG B output is identical for every `t` | the documented exemption |
| v1 `.pocket` reads with empty lane maps | migration |
| v2 `.pocket` round-trips lane maps | migration |
