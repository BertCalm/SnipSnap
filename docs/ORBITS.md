# ORBIT — the circular sequencer

Take a bar of tape and tape its end to its beginning: a ring that plays that
bar on repeat. Take a somewhat longer snip and tape it into a bigger ring
around the first. Drive both with one needle. The inner ring comes round
first, the outer later, and where their downbeats fall against each other
changes every lap until, some bars later, they meet again. Stack more rings
and the meeting point moves further out. That is the whole feature, and
this document is the arithmetic behind it and where it lives in the code.

## Two meanings of "the same speed"

The picture above hides a choice, and ORBIT makes it a per-ring toggle.

| Mode | The rule | What it makes | Rings meet again |
|---|---|---|---|
| **SPEED** (`SAME_SPEED`) | The needle covers the same number of 16ths per second on every ring, so a ring's lap is its own step count. | **Polymeter**: a 16-step ring against a 20-step ring is 4/4 against 5/4. | After the least common multiple of the step counts: 16 and 20 meet every 80 steps, five bars. |
| **LAP** (`SAME_LAP`) | Every ring completes one lap per reference bar, whatever its step count, so a 3-step ring's steps are each a third of the bar. | **Polyrhythm**: three even hits against four inside one bar. | Every bar, by definition. |

Both are one formula. A ring's *period* in frames is

```
SPEED:  period = steps × stepFrames
LAP:    period = lapSteps × stepFrames          (the reference bar, 16 by default)
stepFrames = 60 / bpm / 4 × sampleRate         (one 16th)
```

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

## The cycle

`OrbitClock.cycleSteps` is the LCM of every SPEED ring's step count with the
reference bar (a LAP ring counts as one bar long whatever its steps). It
grows fast with coprime rings:

| Rings | Cycle |
|---|---|
| 16, 12, 20 (+ a LAP ring) | 240 steps — 15 bars |
| 16, 20 | 80 steps — 5 bars |
| 16, 17, 19 | 5,168 steps — 323 bars |

The screen shows this number in its header and its footer, the way the loop
grid's bounce shows its interval count before rendering: a long cycle is a
choice, not a surprise.

## What a ring carries

- **A pad pattern** (`PatternOrbit`): hits on steps against a kit, by pad
  slot. A hit starts a voice — the pad's WAV, decoded and resampled once —
  which rings out over the block boundary like any one-shot. The mixer is
  the loop grid's linear pan law; 64 voices, oldest stolen.
- **A snip** (`SnipOrbit`): one capture from the SNIPS shelf taped round the
  ring. Its default ring size is its own length in 16ths at the set's tempo
  (`OrbitClock.naturalSteps`), so a five-beat capture lands on a 20-step
  ring rather than being squeezed into a bar. The audio is fitted to the
  ring's period with the loop grid's one fit rule (`BlockBaker.fitLoop`:
  trim within 2 %, slice at the hits and re-place them beyond it), so a
  snip on a ring and a loop on the grid can never disagree about what
  wrapping sounds like. A tempo change or a resize refits it.

## Where it lives

| Piece | What it is |
|---|---|
| `loop/Orbit.kt` | `Orbit`, `OrbitSet`, `OrbitMode`, the content types, and `OrbitClock` — every number above |
| `loop/OrbitBank.kt` | The prepared audio for a set: pads at the device rate, snips fitted to their periods. Immutable; an edit prepares a new one reusing the last |
| `loop/OrbitEngine.kt` | The transport: fixed 2048-frame blocks, hits scheduled per block, voices mixed, snips wrapped, written to the same `AudioSink` the loop grid uses. `render` is the offline bounce and the test harness |
| `loop/OrbitStore.kt` | `orbits.json`, a sidecar beside the kit like `groove.json` |
| `loop/OrbitPresets.kt` | The starter set from a kit (FLOOR 16 · HATS 12 · PERC 20 · THREE, a LAP triplet) and the empty-ring and snip-ring constructors |
| `app/OrbitSampleSource.kt` | Pads from the kit shelf via `KitSampleSource`, snips from `snips/` |
| `app/ui/OrbitScreen.kt` | The rings, the taps, the panel, the transport. Reached from GROOVE's **ORBIT ▸**, left by **◄ GROOVE** |

## Not yet

- **Export.** Rings are phone-side only. Flattening a set to an MPC clip is
  `OrbitClock.firings` over one `cycleFrames`, converted to pulses, which is
  the shape the `Mpc3Clip` writer already takes; a WAV bounce is
  `OrbitEngine.render` over the same length. Both are short follow-ups once
  the rings have been heard.
- **Velocity and swing on a ring.** Hits land at 0.9; `OrbitHit.velocity`
  is stored and honoured, the screen just has no control for it yet.
- **Hardware verification** of the Android screen: the cloud session
  cannot compile `:app` (see `app/README.md`), so the screen is reviewed
  Kotlin until CI's `android-build` job or a desktop build has been through
  it.
