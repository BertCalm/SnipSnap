# ORBIT — the circular sequencer

Take a bar of tape and tape its end to its beginning: a ring that plays that
bar on repeat. Take a somewhat longer snip and tape it into a bigger ring
around the first. Drive both with one needle. The inner ring comes round
first, the outer later, and where their downbeats fall against each other
changes every lap until, some bars later, they meet again. Stack more rings
and the meeting point moves further out. That is the whole feature, and
this document is the arithmetic behind it and where it lives in the code.

## A ring's length

The picture above hides a choice, and ORBIT makes it a chip on each ring.

| Ring | The rule | What it makes | Rings meet again |
|---|---|---|---|
| **Free** (SPAN FREE) | The needle covers the same number of 16ths per second on every ring, so a ring's lap is its own step count: 20 steps is five beats. | **Polymeter**: a 16-step ring against a 20-step ring is 4/4 against 5/4. | After the least common multiple of the step counts: 16 and 20 meet every 80 steps, five bars. |
| **Spanned** (SPAN ½ BAR · 1 BAR · 2 BARS · 4 BARS) | One turn of the ring is that many laps of the set's bar whatever its step count, so a 3-step ring spanning one bar is a triplet, and spanning two bars it is three hits across eight beats. | **Polyrhythm**: three even hits against four inside one bar, or across two. | Every span, by definition: a 2-bar ring meets the bar every two bars. |

A ring's row says which it is in words — "20 STEPS · 5 BEATS", "3 STEPS ·
2 BARS" — so there is no mode to explain. A ring alone is just a circle;
"one bar of 8/4" and "two bars of 4/4" are the same circle, and the
difference appears only against the shared lap, which is why a span is
measured in laps. Both are one formula. A ring's *period* in frames is

```
free:     period = steps × stepFrames
spanned:  period = round(lapSteps × laps) × stepFrames   (the bar, 16 by default; laps ½, 1, 2, 4)
stepFrames = 60 / bpm / 4 × sampleRate                   (one 16th)
```

The SPAN chip taps to the next span round the five and long-presses to a
picker. A ring spanning two or more bars wears a heavier tick where each
bar boundary falls; a free ring's bar boundary drifts, so it gets none.

and everything else is a function of the frame count since play started:

```
phase(ring, frame)  = (frame mod period) / period          0 ≤ phase < 1
stepAt(ring, frame) = floor(phase × steps)
hit k fires at      = lap × period + round(k × period / steps)
```

The transport is that one frame count, the same way the loop grid's
transport is one interval count. There are no per-ring cursors, nothing to
keep in sync, nothing that can drift, and the tests check phases and firing
frames as exact integers at 120 BPM and 48 kHz, where a 16th is exactly
6,000 frames.

## The bar

The lap every spanned ring is measured in is the set's, not a ring's:
`OrbitSet.lapSteps`, 16 by default and a choice of 12, 16, 20, 24 or 32
(3/4, 4/4, 5/4, 6/4, 8/4 — `OrbitSet.BAR_CHOICES`, all even so a half-bar
span stays whole). Tapping the header's readout opens THE SET, where the
bar is a row of chips. Change it and:

- a **free** ring is untouched: its period is its own steps;
- a **spanned** ring re-periods: a one-bar triplet across 16 becomes a
  triplet across 12, and the picture and label follow;
- a **spanned snip** refits to its new period, and the fit report says so;
- the **cycle** changes, since the lap seeds the LCM, and the header's
  `BAR 2/15` counts the set's bars whatever their length.

That is the "4/4 × 2 = 8/4" observation kept apart from span: changing
the bar changes every spanned ring's meaning at once; changing a span
changes one ring's. Every change to the bar is one UNDO.

## Swing

The set carries a swing as the MPC counts it, `OrbitSet.swing`, 50 to 75
(`SWING_CHOICES`: 50 · 54 · 58 · 62 · 66 · 71 · 75, a row of chips in
THE SET). It is the share of each pair of 16ths the first takes: 50 is
straight, 66 a triplet feel, 75 the dotted-8th. `OrbitClock.swingFrames`
pushes every **odd step** late by (swing − 50) / 50 of a 16th, on every
ring whose step *is* a 16th (`stepIsSixteenth`: every free ring, and a
spanned ring whose steps fill its laps — asked as `periodSteps == steps`,
a question about the music with no sample rate in it). A 3-step ring
across a bar has no offbeat 16ths to push and is left straight.

Swing and a hit's own `offset` are two layers, and they meet in two
places rather than one. The engine, the strike flare and the bounce count
frames: `stepOffset` carries the set's swing, `firingOffset` adds the
hit's lean, and the ring draws each hit where it fires — a swung offbeat
sits late on the ring as it does in time. The MPC clip counts pulses and
never converts out of them: `stepPulses` carries the swing as
`Mpc3Clip.swingPush` itself, `firingPulses` adds the lean (already in
pulses, so nothing is converted at all), and `pulseFirings` walks the laps
— the same walk `firings` uses, so the two cannot disagree about which
laps a hit lands on. A clip is musical time, so the same set exports the
same pulses whatever rate it happens to be playing at.

## Level and pan

Every ring has a level (0 to 150 %, in tenths) and a pan (quarter by
quarter, L 100 to R 100) on its panel; the mixer applied both since the
first build and the panel now reaches them. Tap the readout to put a
level back to 100 or a pan back to centre.

## A ring's voice

A pattern ring has a *voice*: the pads it may play, in the order they are
shown when the ring is unrolled. A drum ring's voice is one pad. A bass
ring's voice is the kit's tonal pads, low to high, and every hit names
which of them it plays, so a melody is one ring rather than one ring per
note. The screen unrolls the picked ring into a strip with one row per pad
in its voice — a kick ring is a single row, a bass ring a small piano roll
— and that strip is the editor; the ring itself is the picture of the
result. On the ring, a melodic ring's dots step in and out from the line by
pitch.

The header shows the rings' lengths against each other, reduced: 16, 20
and a ring spanning one bar read "4 : 5"; a 3-step ring spanning two
bars against a free 16 reads "1 : 2".

## Filling a ring

`OrbitPatterns` is the shortcut past one tap per step. `euclid(k, n)` is
Bjorklund as Toussaint tells it, onset first — 3 round 8 is the tresillo
`x..x..x.`, 5 round 8 the cinquillo `x.xx.xx.` — and SPREAD on the screen
puts k of them on the ring's first pad. CLEAR empties a ring. The dice
(`scramble`) roll a seeded handful of hits for every pad in the voice,
soft, normal or accented, and a new seed rolls again. A long-press on a
strip cell cycles a hit's weight soft → normal → accent, or drops an accent
on an empty cell. The step count is a picker of the sizes worth a chip
(`STEP_CHOICES`), not a walk on − and +.

Three more ways in, all on the panel's second row:

- **REC** arms the strip's pad rail. While the transport runs, a rail tap
  writes a hit for that pad on the ring's *nearest* step
  (`OrbitClock.nearestStep`: a late tap rounds back, an early one
  forward) and is heard through PLAY's engine as any rail tap is. The
  tap's moment is taken from the engine's frame count less the output
  buffer and a touch allowance, so what you meant lands where you heard
  it. A hit already there is left as it is, weight and all
  (`OrbitPatterns.place`): playing over a hit is not lifting it. Disarmed,
  the rail only auditions, as before.
- **◀ ▶ TURN** rotate every hit one step earlier or later, wrapping
  (`OrbitPatterns.turn`). A tresillo turned one step is a different
  groove; the `euclid` rotation the CLI has always had is now a chip.
- **DUP** copies the picked ring beside itself, named `KICK 2`
  (`OrbitPatterns.copyName`). Two identical rings, one of them a step
  shorter or a step turned, is how phasing starts.

## The cycle

`OrbitClock.cycleSteps` is the LCM of every ring's period with the reference
bar (a spanned ring's period is its laps of the bar whatever its steps). It
grows fast with coprime rings:

| Rings | Cycle |
|---|---|
| 16, 12, 20 (+ a ring spanning one bar) | 240 steps — 15 bars |
| 16 and a 3-step ring spanning two bars | 32 steps — 2 bars |
| 16, 20 | 80 steps — 5 bars |
| 16, 17, 19 | 5,168 steps — 323 bars |

The screen shows this number in its header and its footer, the way the loop
grid's bounce shows its interval count before rendering: a long cycle is a
choice, not a surprise.

## What a ring carries

- **A pad pattern** (`PatternOrbit`): hits on steps against a kit, by pad
  slot, drawn from the ring's voice. A hit starts a sounding voice — the
  pad's WAV, decoded and resampled once — which rings out over the block
  boundary like any one-shot. The mixer is the loop grid's linear pan law;
  64 sounding voices, oldest stolen.
- **A snip** (`SnipOrbit`): one capture from the SNIPS shelf taped round the
  ring. Its default ring size is its own length in 16ths at the set's tempo
  (`OrbitClock.naturalSteps`), so a five-beat capture lands on a 20-step
  ring rather than being squeezed into a bar. The audio is fitted to the
  ring's period with the loop grid's one fit rule (`BlockBaker.fitLoop`:
  trim within 2 %, slice at the hits and re-place them beyond it), so a
  snip on a ring and a loop on the grid can never disagree about what
  wrapping sounds like. A tempo change or a resize refits it — and the fit
  reports itself (`FitReport`: as is, trimmed, padded, or sliced, with the
  percentage and the slice count), so the ring's panel says `SLICED AT 12
  HITS · SQUEEZED 8%` rather than leaving the ear to guess. The ring wears
  the fitted audio's waveform (`OrbitBank.peaks`: the loudest sample per
  equal arc, scaled to the ring's own loudest). A snip that knows its
  tempo (`Tempo.estimate`, trusted from 0.3 confidence) offers it once when
  the ring lands: SET moves the whole set and re-sizes the ring to its
  natural length there; KEEP leaves both. BPM taps settle for 400 ms before
  the set saves and its snips refit, with `REFITTING…` in the rings' corner
  meanwhile; a set with no snip rings takes the new tempo on the next block.

## Where it lives

| Piece | What it is |
|---|---|
| `loop/Orbit.kt` | `Orbit` (steps, span, voice), `OrbitSpan`, `OrbitSet` (with the bar and its meter labels), the content types, and `OrbitClock` — every number above, plus the length and ratio labels |
| `loop/OrbitBank.kt` | The prepared audio for a set: pads at the device rate, snips fitted to their periods, each with its `FitReport` and its peaks for the ring's waveform. Immutable; an edit prepares a new one reusing the last |
| `loop/LoopFit.kt` | `LoopFit` (as is · trimmed · padded · sliced), `FitReport` and its label, `FittedLoop` — what `BlockBaker.fitLoopReported` says it did |
| `loop/OrbitEngine.kt` | The transport: fixed 2048-frame blocks, hits scheduled per block, voices mixed, snips wrapped, written to the same `AudioSink` the loop grid uses. `render` is the offline bounce and the test harness |
| `loop/OrbitStore.kt` | `orbits.json` (version 4; versions 1–3 still load — a v1/v2 lock becomes a one-bar span, and a hit with no `offset` sits on its step), a sidecar beside the kit like `groove.json` |
| `loop/OrbitClip.kt` | One cycle as an MPC clip in `groove.json`, counted in the clip's own 4/4 bars whatever the set's bar, and the 64-bar refusal both outputs share |
| `loop/OrbitFeel.kt` | Pocket for rings: a `GrooveFeel` donor's sixteen per-position offsets laid onto hits, a seeded humanised take, or straight again. Writes `OrbitHit.offset` — pulses late or early of the step, which is the only place a feel or a jitter can live on a ring |
| `loop/OrbitPatterns.kt` | Euclid, SPREAD, CLEAR, the dice, TURN, `place` (what REC writes), a copy's name, the weight cycle, the step-size choices |
| `loop/OrbitPresets.kt` | The starter set from a kit — one ring per instrument the kit has (KICK 16 · SNARE 16 · HATS 12 · PERC 20 · THREE, a locked triplet · BASS 20 over the tonal pads) — and the empty-ring and snip-ring constructors |
| `app/OrbitSampleSource.kt` | Pads from the kit shelf via `KitSampleSource`, snips from `snips/` |
| `app/ui/OrbitScreen.kt` | Reached from **ORBIT** on the menu row (with a kit open), or from GROOVE's `ORBIT ▸` — on its empty state beside RECORD and STEPS, and on its action row once a groove exists. The rings (shortest inside, a 16th-long comet tail, a strike flare, a pulse when they meet, a snip ring's waveform), the unrolled strip, the panel (with the fit report), the tempo offer, the debounced BPM (running while held), UNDO (40 edits deep, a BPM run counting as one), the transport, audition through PLAY's pad engine, solo by long-press, and a description of the rings and every cell's state for a screen reader. Reached from GROOVE's **ORBIT ▸**, left by **◄ GROOVE** |

## Out the door

Two ways a set leaves the screen, both one cycle long and both refused in
words when the cycle passes 64 bars (`OrbitClip.refusal`):

- **BOUNCE ▸ TAPE** renders one cycle of what is heard (`OrbitEngine.render`
  over `cycleFrames`; a solo bounces alone) and drops it on the TAPE shelf
  through `SnipStore.import`, so rings feed the app's own loop: tape, chop,
  kit, MPC.
- **CLIP ▸ KIT** flattens one cycle of every engaged pattern ring's firings
  onto the 960-PPQ grid (`OrbitClip.clip`: pad A0N plays note 35+N, the
  writer's chromatic map; `Mpc3Clip` has no time signature, so the clip
  counts bars of sixteen 16ths whatever the set's bar — a 3/4 set's
  four-bar cycle is 48 steps, three of the clip's, and the OUT panel says
  so — and the 64-bar ceiling is measured in those bars, with the header's
  cycle line turning warn-coloured past it) and writes it into the kit's `groove.json` as
  "ORBIT 4:5", replacing the last ORBIT clip and leaving the captured base,
  the variations and PROG E untouched — so the native export embeds it and
  it rides to the MPC with the kit. A kit with no groove yet gets the ORBIT
  clip as its first, which is what GROOVE then shows.

## Not yet

- **Hardware verification** of the Android screen: the cloud session
  cannot compile `:app` (see `app/README.md`), so the screen is reviewed
  Kotlin until CI's `android-build` job or a desktop build has been through
  it.
