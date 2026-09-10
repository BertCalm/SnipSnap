# ORBIT: span and bar

A spec for the two length knobs ORBIT does not have yet. Nothing here is
built. `docs/ORBITS.md` describes what is.

## The idea in one table

A ring alone is just a circle that takes some time to go round; "one bar
of 8/4" and "two bars of 4/4" are the same circle. The difference only
appears when rings are compared, and what they are compared against is
the **lap**: the set's shared bar, 16 sixteenths at the set's tempo today.

So there are two knobs, and they must stay apart:

| Knob | Lives on | What it changes | Example |
|---|---|---|---|
| **Span** | one ring | how many laps one turn of *this* ring takes | a 3-step ring spanning 2 laps: three hits across two bars |
| **Bar** | the set | how long the lap is, for *every* ring at once | a 12-step bar: the set is in 3/4 |

Change a span and one ring means something new. Change the bar and every
spanned ring means something new together, which is the "4/4 × 2 = 8/4"
observation: it is a change to the reference, not to a ring.

| Ring | Steps | Span | Heard against a 16-step bar |
|---|---|---|---|
| KICK | 16 | free | four on the floor, once a bar |
| THREE | 3 | 1 lap | a triplet across the bar: one hit every 1⅓ beats |
| SLOW THREE | 3 | 2 laps | three hits across two bars: one every 2⅔ beats |
| SEVEN | 7 | 4 laps | seven hits spread over four bars |
| SIXTEENTHS | 4 | ½ lap | four hits per half bar: straight eighths |

Make the bar 32 and SLOW THREE is a plain triplet again. That is the two
knobs interacting, and it is correct.

## Span (per ring)

### Model

`Orbit.lockToBar: Boolean` becomes `Orbit.span: OrbitSpan`:

```kotlin
enum class OrbitSpan(val laps: Double?) {
    FREE(null),   // the ring's period is its own steps, in sixteenths — polymeter
    HALF(0.5),    // one turn is half a lap
    ONE(1.0),     // one turn is a lap — today's LOCK TO BAR
    TWO(2.0),
    FOUR(4.0),
}
```

`FREE` is what a ring is today when not locked; `ONE` is what it is when
locked. Nothing existing changes meaning.

`OrbitClock.periodSteps(set, orbit)`, in the Kotlin it will be:

```kotlin
fun periodSteps(set: OrbitSet, orbit: Orbit): Int {
    val laps = orbit.span.laps ?: return orbit.steps           // FREE
    return (set.lapSteps * laps).roundToInt().coerceAtLeast(1)  // HALF, ONE, TWO, FOUR
}
```

Every bar length offered (below) is even, so `HALF` is always whole.
`lapFrames`, `periodFrames`, `phase`, `stepAt`, `firings`, `cycleSteps`
(the LCM over periods, seeded with the lap), `cycleBars`, `ratioLabel`
and `tailSweep` are unchanged: they already work from `periodSteps`.

A spanned ring's steps divide its span, however many there are. 32 steps
across half a lap is a run of 32nd-note-ish cells; 3 steps across four
laps is three long notes. Both are legal. The strip is already sized to
the step count, not the period, so nothing there changes.

### Length label

`OrbitClock.lengthLabel` says, after the step count:

| Span | Label |
|---|---|
| FREE | as today: `N 16THS`, `N BEATS`, `1 BAR`, `N BARS`, from the steps |
| HALF | `½ BAR` |
| ONE | `1 BAR` |
| TWO | `2 BARS` |
| FOUR | `4 BARS` |

So the panel reads `3 STEPS · 2 BARS` or `20 STEPS · 5 BEATS`. A free
ring whose steps happen to equal one bar also reads `1 BAR`; the
difference between the two is the SPAN chip beside it, and the ring
ticks below.

### Store

`orbits.json` goes to version 3. Rings write `"span": "FREE" | "HALF" |
"ONE" | "TWO" | "FOUR"`. Reading:

| File version | Field | Becomes |
|---|---|---|
| 1 | `"mode": "SAME_LAP"` | `ONE` |
| 1 | `"mode": "SAME_SPEED"` | `FREE` |
| 2 | `"lockToBar": true` | `ONE` |
| 2 | `"lockToBar": false` or absent | `FREE` |
| 3 | `"span"` | as written; unknown or absent → `FREE` |

### Screen

- **The SPAN chip** replaces LOCK TO BAR in the panel's first row. It
  shows the current span (`FREE`, `½ BAR`, `1 BAR`, `2 BARS`, `4 BARS`)
  and taps to the next, wrapping; accent-coloured when not free, as
  LOCK TO BAR is when locked. Long-press opens a five-chip picker for
  anyone who does not want to cycle. Screen-reader name: `SPAN: 2 BARS —
  TAP FOR THE NEXT`.
- **Lap ticks on long rings.** A ring spanning two or more laps draws a
  heavier tick where each lap boundary falls, so the eye can see "the
  bar is here" on a ring longer than a bar. Free rings never get one:
  their bar boundary drifts, which is the point of them.
- **The rings' order** is by period, as now, so a 4-lap ring sits
  outside a 2-lap one, which sits outside the bar.
- **Snip rings** take a span like any ring: a snip spanning two laps is
  fitted to two laps. The fit report says what that did. The tempo
  offer is unchanged.
- **Presets**: THREE is `ONE` (it was locked). Nothing else spans.

### Tests

- `periodSteps` for every span at laps 12, 16, 20, 24, 32.
- A 3-step `TWO` ring against a free 16: cycle 32 steps, ratio `1 : 2`,
  firings at 0, ⅓ and ⅔ of 32 steps.
- `HALF` at every offered lap is whole.
- Store: v3 round trip; v2 `lockToBar` reads as `ONE`; v1 `SAME_LAP`
  reads as `ONE`; an unknown span string reads as `FREE`.
- Clip: a `TWO` triplet writes notes at thirds of two bars.
- Boards: the snip ring board and the modes board show a spanned ring.

## Bar (per set)

### Model

`OrbitSet.lapSteps` already exists (16, and the model allows 1..64). It
becomes a choice:

| Steps | Reads as |
|---|---|
| 12 | 3/4 |
| 16 | 4/4 |
| 20 | 5/4 |
| 24 | 6/4 |
| 32 | 8/4 |

All even, so `HALF` stays whole. Nothing else in the model changes.

What changing the bar does, ring by ring:

- A **free** ring is untouched: its period is its own steps.
- A **spanned** ring re-periods: a `ONE` triplet across 16 becomes a
  triplet across 12. The picture and the label follow.
- A **spanned snip** refits to its new period; the fit report says
  what happened. A free snip is untouched.
- The **cycle** changes, because the lap seeds the LCM. The header
  readout follows.

### Screen

- **Tapping the header's readout** (the ratio and cycle line, or the
  bar and tempo line) swaps the panel for **THE SET**: a `BAR` row of
  five chips (`12 · 3/4`, `16 · 4/4`, `20 · 5/4`, `24 · 6/4`, `32 · 8/4`,
  the current one accented) and one explaining line: `THE BAR EVERY
  SPANNED RING IS MEASURED AGAINST. FREE RINGS DO NOT CARE.` The set's
  tempo line sits there too, so this is where BPM would get typed entry
  later. CLOSE returns to the ring panel. Screen-reader name on the
  readout: `THE SET — TAP TO CHANGE THE BAR`.
- **Header**: `BAR 2/15` counts the set's bars, whatever their length.
  The ratio line is unchanged.
- **Every change to the bar is one UNDO**, as any commit is.

### Out the door

`Mpc3Clip` has bars but no time signature; its bar is 16 sixteenths
(`PULSES_PER_BAR`). So:

- **CLIP ▸ KIT** writes notes at their true pulse positions, which is
  right at any bar length, but counts its bars in 4/4:
  `bars = ceil(cycleSteps / 16)`. With a 12-step bar, a 4-bar cycle is
  48 steps, which the clip calls 3 bars. The OUT panel says so when the
  lap is not 16: `THE MPC COUNTS 4/4 BARS: 3.`
- **The 64-bar refusal** is 64 bars of 16 (1024 steps), since that is the
  clip's ceiling. `OrbitClip.bars` and `refusal` move to that arithmetic;
  the header's own cycle readout stays in the set's bars.
- **BOUNCE ▸ TAPE** is unaffected: it renders `cycleFrames`.

### Tests

- Lap 12 with a free 16-step ring: cycle 48 steps, 4 bars of 12.
- Lap 12 with a `ONE` triplet: firings at thirds of 12 steps.
- `OrbitClip.bars` at lap 12 and a 48-step cycle is 3; at lap 20 and a
  20-step cycle is 2 (20 steps needs a second 4/4 bar).
- Store round-trips every offered lap.

## Order

Two rounds, span first, because it is one ring at a time and its store
migration is the delicate part:

1. **Round 7, span**: `OrbitSpan`, `periodSteps`, `lengthLabel`, store v3
   with both readers, the SPAN chip and picker, lap ticks, presets, the
   clip test, docs and boards.
2. **Round 8, bar**: the lap choice, THE SET panel from the header, the
   clip's 4/4 counting and OUT line, the refusal arithmetic, docs and
   boards.

## Open questions

- Spans stop at four laps. Eight is easy to add if a sixteen-bar ring is
  ever wanted; nothing else would change.
- The bar list is the five common meters. 28 (7/4) and 36 (9/4) are
  even and would slot in; they are left out until asked for.
- With the bar at 12, a free 16-step KICK is no longer "once a bar"; it
  is a 4-against-3 against the bar. That is correct and probably what a
  3/4 set wants noticed, so the ratio line is the only warning.
