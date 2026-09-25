# RESIN Drone — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Design:** [`docs/superpowers/specs/2026-09-25-resin-drone-design.md`](../specs/2026-09-25-resin-drone-design.md). Read it first, especially **Decided** and **What the probe measured**. This plan does not repeat the reasoning, only the decisions and their numbers.

**Goal:** A RESIN patch becomes a drone on the loop grid. It is a long, slowly breathing note that owns one track. It loops on the chain's wrap with no seam, stays within 3 cents of its note at any tempo, and re-renders itself when the tempo changes. It never stalls the other five tracks while it renders.

**Architecture:**
- **`:synth`** gets `ResinDrone`, the render. The phases come from the sample index modulo the loop. The square's cycles and RATE's breaths are whole numbers. A 1.0 s pre-roll is discarded. Everything renders at the session's rate.
- **`:loop`** gets:
  - `DroneBlock` (a recipe plus `slice` of `of`);
  - `DroneFit`, the pure n-and-nudge math, with no engine in it;
  - a defaulted `SampleSource.drone(...)`, which is the injection seam;
  - a rule in `Residency` that a drone never bakes on the engine thread.
- **`:shell`** gets `DroneSource`, a `SampleSource` decorator. It answers `drone(...)` through `ResinDrone`, and renders each drone once for all its slices. This needs a new `:shell → :loop` dependency. There's no cycle, since `:loop` depends on neither `:shell` nor `:synth`.
- **`:cli`** gets an audition door.
- **`:app`** gets the SYNTH-screen door and the grid readout.

**Tech Stack:** Kotlin/JVM 2.0.21, Gradle wrapper, `kotlin.test`, Compose (the app task only). No new libraries.

## How to hand this to a session

```
Implement docs/superpowers/plans/2026-09-25-resin-drone.md task by task,
on branch claude/sound-design-tools-mdmrbw (restart it from the default
branch, claude/mobile-mpc-drum-sampler-t58x74, if its PR has merged). Read
the design spec it links first, and .claude/skills/steward/SKILL.md before
your first push. Commit after every task. Run ./gradlew --no-daemon test
before every push. Open one PR when Task 9's verification is green.
```

## Running tests here

- Fast loop: `./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.ResinDroneTest'`. Likewise `:loop:test`, `:shell:test` and `:cli:test` with their own classes.
- Before every push, run `./gradlew --no-daemon test` with the Bash tool's `run_in_background` and a timeout of at least 45 minutes. Gate on Gradle's exit code, not on a grep. Don't add an inline `&`. After killing Gradle, clear `loop/build/classes` before the next run.
- `:app` is not in this environment's Gradle graph, since there's no Android SDK here. Only CI's `android-build` and `emulator-tests` prove Task 7. Re-read that diff twice before pushing it.

## Global constraints

- **Measure, never guess; never loosen a threshold blind.** The numbers below come from the probe. If a test misses, print the measured value, find out why, and re-derive the threshold with margin.
- **The one-shot render and the held render must not move by a single bit.** `ResinTest`'s pin (the `contentHashCode` hashes) and `ResinHeldTest` must pass unchanged. `ResinDrone` is a *separate* loop that shares only `Resin`'s helpers. It is not a third branch inside `synthesize`.
- **No crossfade, ever.** Periodicity is exact by construction. A drone that doesn't close is a failing test, not a baked fade.
- **The engine thread never renders a drone** (spec, probe finding 3).
- **Copy strings go through `Copy`** (`Personality.kt`), with `PersonalityTest` laws.
- **Naming guardrail** (`docs/SYNTH_ROADMAP.md`, "sound yes, names never").
- Commit messages follow the house style: a sentence about the *why*, ending with the session's attribution lines.

## Numbers from the probe (do not re-derive; assert against them)

| Constant | Value | Where it came from |
|---|---|---|
| `DRONE_PREROLL_SECONDS` | `1.0f` | worst corner 1.0e-14 at 1.0 s; 2.2e-10 at 0.5 s |
| `DroneFit.MAX_NUDGE_CENTS` | `3.0` | the promise in **Decided 1** |
| n candidates | `1, 2, 4, 8` | `Session.MAX_CHAIN = 8` |
| Period-to-period diff after pre-roll | `< 1e-12` (probe: 0.0 to 1e-14) | test bar, 9 orders of magnitude under the seam bar |
| CPU | ~0.24 s per rendered second (44.1 kHz), 0.26 s (48 kHz) | on this cloud machine, one core |

## File structure

| File | Change |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Resin.kt` | `cutoffRange` → `internal`; nothing else |
| `synth/src/main/kotlin/com/snipsnap/synth/ResinDrone.kt` | new: `Spec`, `plan`, `render` |
| `synth/src/test/kotlin/com/snipsnap/synth/ResinDroneTest.kt` | new |
| `loop/src/main/kotlin/com/snipsnap/loop/Session.kt` | + `DroneBlock` |
| `loop/src/main/kotlin/com/snipsnap/loop/DroneFit.kt` | new: n, nudge, `refit` |
| `loop/src/main/kotlin/com/snipsnap/loop/SampleSource.kt` | + defaulted `drone(...)` |
| `loop/src/main/kotlin/com/snipsnap/loop/BlockBaker.kt` | + the `DroneBlock` branch |
| `loop/src/main/kotlin/com/snipsnap/loop/SessionStore.kt` | + `"drone"` block JSON |
| `loop/src/main/kotlin/com/snipsnap/loop/SessionBuilder.kt` | + `sendDrone` |
| `loop/src/main/kotlin/com/snipsnap/loop/Residency.kt` | drones never bake on the calling thread |
| `loop/src/test/kotlin/com/snipsnap/loop/DroneFitTest.kt`, `DroneBlockTest.kt` | new; + cases in `ResidencyTest` |
| `shell/build.gradle.kts` | + `implementation(project(":loop"))` |
| `shell/src/main/kotlin/com/snipsnap/shell/DroneSource.kt` | new: the decorator and its single-flight cache |
| `shell/src/test/kotlin/com/snipsnap/shell/DroneSourceTest.kt` | new |
| `cli/src/main/kotlin/com/snipsnap/cli/SynthCommand.kt` | `--drone`, `--root`, `--motion`, `--rate`, `--bpm`, `--bars` |
| `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` | `DRONE TO LOOP ▸` and its sheet (RESIN only) |
| `app/src/main/kotlin/com/snipsnap/app/LoopActivity.kt`, `LoopGrid.kt`, `App.kt` | wrap the source; refit on tempo; readout |
| `README.md`, `docs/SYNTH_ROADMAP.md`, `testkit/README.md` | Task 8 |

---

### Task 1: `DroneFit`: n and the nudge, in `:loop`

**Why here:** the grid has to pick n and re-slice a track when the tempo changes. That has to happen without knowing any engine exists. The maths is pitch arithmetic only.

- [ ] Create `DroneFit` (object) in `:loop`:

```kotlin
/** Hz of a MIDI note, equal temperament, A4 = 440. */
fun hz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

/**
 * The note a drone of [loopFrames] at [sampleRate] actually plays: the
 * nearest pitch whose sub-octave completes a whole number of cycles in the
 * loop (spec, "The one trade-off").
 */
fun snappedHz(rootHz: Double, loopFrames: Long, sampleRate: Int): Double {
    val seconds = loopFrames.toDouble() / sampleRate
    val m = Math.round(rootHz / 2 * seconds).coerceAtLeast(1)
    return 2.0 * m / seconds
}

fun nudgeCents(rootHz: Double, loopFrames: Long, sampleRate: Int): Double =
    1200.0 * ln(snappedHz(rootHz, loopFrames, sampleRate) / rootHz) / ln(2.0)

/** Smallest n in SPANS whose actual nudge is within MAX_NUDGE_CENTS; else the last. */
fun spanFor(rootMidi: Int, session: Session): Int
```

- [ ] Constants: `SPANS = listOf(1, 2, 4, 8)` (assert `SPANS.last() == Session.MAX_CHAIN` in a test) and `MAX_NUDGE_CENTS = 3.0`.
- [ ] `fun refit(session: Session): Session`. Every track whose chain is all `DroneBlock`s of one recipe gets re-sliced to `spanFor(rootMidi, session)` slices (`slice` 0 until n, `of` = n). Every other track is returned as the same instance.
- [ ] Tests, `DroneFitTest`:
  - Fresh session (1 bar, 90 BPM, 44.1 kHz): A1 (33) → 4; A2 (45) → 2; A3 (57) → 1.
  - `nudgeCents` for A1 at n = 4 is within 0.01 of **-1.97**, the probe's number.
  - For every MIDI note 33..84 (RESIN's register), every whole BPM 40..220, and both 44.1 and 48 kHz: `spanFor`'s nudge is ≤ 3 cents, except exactly one case. That case is A1 (33) at 216 BPM: n = 8, -3.15 cents (-3.14 at 48 kHz). Both numbers were computed when this plan was written. Assert the exception list equals that one case. If it doesn't, print the list and correct the spec. Don't widen the assertion.
  - `refit` at a new BPM changes only drone tracks, and keeps `name`, `level`, `pan` and `engaged`.

Commit: "A drone knows how many bars it needs to stay in tune, and the grid can ask without knowing what a synth is"

### Task 2: `DroneBlock` on the grid: data, JSON and baking seam

- [ ] In `Session.kt`:

```kotlin
/**
 * One slice of a drone: a recipe the renderer understands, rendered once
 * over [of] intervals and played back a slice per interval. The chain's wrap
 * is the drone's wrap, so a drone owns its whole track (spec, Decided).
 */
data class DroneBlock(val recipe: JsonValue, val rootMidi: Int, val slice: Int, val of: Int) : Block() {
    init {
        require(of in DroneFit.SPANS) { "a drone spans one of ${DroneFit.SPANS} intervals, got $of" }
        require(slice in 0 until of) { "slice $slice of $of" }
        require(rootMidi in 0..127) { "root $rootMidi" }
    }
}
```

  Check that `JsonValue` equality is structural. `Residency.CacheKey` depends on it. Add a test that two separately parsed equal recipes give equal blocks.
- [ ] `SampleSource`, defaulted like `muteGroups`:

```kotlin
/**
 * A drone's whole render: [frames] frames at [sampleRate], stereo, or null
 * when this source has no renderer for [recipe]. The baker slices it; the
 * source never sees slices, so a drone's n slices can share one render.
 */
fun drone(recipe: JsonValue, rootMidi: Int, frames: Long, sampleRate: Int): Snip? = null
```

- [ ] `BlockBaker.bake`: `is DroneBlock` → `source.drone(recipe, rootMidi, of * intervalFrames, sampleRate)`. Slice `[slice * intervalFrames, (slice + 1) * intervalFrames)`. If the result is null, or not exactly `of * intervalFrames` frames, bake `silence(session)`. That's the same "one missing file must not stop the grid" rule. Apply no tail fade: the slices are contiguous, and a fade would *make* the seam.
- [ ] `SessionStore`: `{"type":"drone","recipe":{…},"rootMidi":33,"slice":0,"of":4}`. **Don't bump `VERSION`.** An old build already throws on an unknown block type, and bumping would make every existing `loop.json` unreadable to this build. Round-trip test.
- [ ] `SessionBuilder.sendDrone(session, trackIndex, name, recipe, rootMidi): Session`. It builds the chain of `spanFor` slices, engaged. No files are written.
- [ ] Tests, `DroneBlockTest`:
  - A fake source returning a ramp: each slice is the right window of it.
  - Null or wrong-length results become silence.
  - JSON round-trips.
  - `sendDrone` lands `spanFor` slices.

Commit: "The loop grid can hold a drone: a recipe on the track, audio only at bake time"

### Task 3: A drone never bakes on the engine thread

**Why:** probe finding 3. A four-interval drone costs about 2.8 s of CPU here, and a phone is plausibly 2 to 3 times slower. `WARM_TIMEOUT_MS` is 1.5 s. After a timeout, `buffersFor` bakes on the engine thread and every track drops out.

- [ ] `Residency.buffersFor`: for a `DroneBlock` key that is not baked, return the session's silence buffer for that track *without caching it*. Then submit the bake to the executor, exactly as `prefetch` does (including its stale-length early-out). The other block kinds keep their current behavior; don't widen this change.
- [ ] `warm` keeps including drone keys, so a tempo change starts their render as early as it can. Its bounded wait is unchanged.
- [ ] `Bouncer` still bakes drones synchronously, since it has no deadline. Confirm it calls `BlockBaker` directly (line ~102) and add a test that proves a bounce contains the drone.
- [ ] Tests, in `ResidencyTest`, with a source whose `drone(...)` blocks on a latch:
  - `buffersFor` returns within 50 ms, with silence on the drone track and real audio on the others.
  - After the latch opens and the executor drains, `buffersFor` returns the drone.
  - A second `buffersFor` during the render doesn't submit a second bake. Count calls.

Commit: "A drone that isn't ready is a bar of silence on its own track, not a dropout on all six"

### Task 4: `ResinDrone`: the render, in `:synth`

- [ ] `Resin.cutoffRange` changes from `private` to `internal`. It's the only change to `Resin.kt`. The pin tests prove it.
- [ ] `ResinDrone` (object):

```kotlin
const val DRONE_PREROLL_SECONDS = 1.0f
const val MOTION_MAX_OCTAVES = 2f
val RATES = listOf(1, 2, 4)

data class Spec(val voice: ResinVoice, val macros: Map<String, Float>, val motion: Float, val rate: Int) {
    init { require(motion in 0f..1f); require(rate in RATES) }
    fun toJson(): JsonValue   // {"engine":"RESIN","voice":…,"macros":{…},"motion":…,"rate":…}
    companion object { fun fromJson(v: JsonValue): Spec? }  // null for another engine or a bad shape
}

/** Mono, exactly [frames] long at [sampleRate], periodic with period [frames]. */
fun render(spec: Spec, rootMidi: Int, frames: Long, sampleRate: Int): FloatArray
```

- [ ] The render follows the probe's code exactly:
  - Render at `sampleRate * Dsp.OVERSAMPLE`. `loopOs = frames * OVERSAMPLE`. `pre = DRONE_PREROLL_SECONDS * sampleRate` whole frames. Render `pre + frames + 256` frames, times `OVERSAMPLE`.
  - For each oversampled index `i`, `k = i % loopOs`:
    - the sub saw's phase is `k * m / loopOs`;
    - the note saw's is `k * 2m / loopOs`;
    - the square's is `k * q / loopOs`;
    - the LFO is `sin(2π · rate · k / loopOs)`.
  - Compute `m` with the same rounding as `DroneFit.snappedHz`. `q = round(f · detune · seconds)`, coerced to at least `2m + 1`, so there is at least one whole beat.
  - The mix and gains are `Resin.stackGains`. Resonance is `Resin.resonanceFor(cream, held = true)`, capped at r = 4.0.
  - The cutoff floor is `Dsp.keyTrack(Dsp.expMap(CUTOFF, lo, hi), f, Resin.frequencyFor(voice, 0.5f), CUTOFF_KEY_TRACK_AMOUNT)`. The cutoff is `floor · 2^(motion · MOTION_MAX_OCTAVES · lfo)`, capped at `Resin.MAX_CUTOFF_HZ`.
  - CONTOUR, DECAY and TUNE are ignored.
  - Decimate with `Dsp.decimate(raw, sampleRate)` and cut `[pre, pre + frames)`.
  - Level the loop to `Dsp.MELODIC_LOUDNESS_TARGET` with `Loudness.of`, as `Keys.resinPad` does, then `limitPeak(0.99)`. Levelling is a single gain, so it can't break the periodicity.
- [ ] Tests, `ResinDroneTest`:
  - **Exact loop:** render `frames`, then render `2 * frames` of the same spec with the same root and rate. The second half of the long one matches the short one within `1e-12` energy ratio. Run this on the five probe corners (the pre-roll table in the spec) at 44.1 and 48 kHz.
  - **Seam:** `Keys.seamError` on `render(...) + render(...)` concatenated is < 1e-12.
  - **Pitch:** at MOTION 0, STACK 0 and CREAM 0.2, the detected pitch is within 0.5 cents of `DroneFit.snappedHz`. The note is where the grid thinks it is. Use `ResinHeldTest`'s clean-read macros and the looped audio for BASS, whose sub-octave sits under `Pitch.detect`'s 40 Hz floor.
  - **MOTION moves the filter:** the spectral centroid swings RATE times per loop at MOTION 1 and stays flat at MOTION 0. Measure the swing over 16 windows and assert the count of centroid maxima equals `rate`.
  - **Ignored macros:** CONTOUR and DECAY at 0 and 1 give identical renders.
  - **Determinism**, and **loudness** within 1 dB of the target on a default drone.
  - **`Spec` JSON** round-trips; `fromJson` of a non-RESIN recipe is null.
  - **Cost guard:** print CPU seconds per rendered second. Assert only that it is < 1.0 (four times the probe's 0.24), so this is not a timing test.

Commit: "RESIN drones: a breathing note that loops to the bit, at the session's rate"

### Task 5: `DroneSource`: the renderer the grid is handed, in `:shell`

- [ ] `shell/build.gradle.kts`: + `implementation(project(":loop"))`, with a one-line comment saying why ("the drone renderer is a `SampleSource`").
- [ ] `class DroneSource(private val inner: SampleSource) : SampleSource by inner`:
  - It overrides `drone(...)`: `ResinDrone.Spec.fromJson(recipe) ?: return inner.drone(...)`. It renders mono and duplicates to stereo, because the grid bakes stereo (**Decided 3**).
  - **Single flight:** a `ConcurrentHashMap<Key, CompletableFuture<Snip>>`, with `Key(recipe, rootMidi, frames, sampleRate)` and `computeIfAbsent`. The n slices baked in parallel share one render. Keep the last `MAX_RESIDENT = 4` completed renders and evict the oldest. A drone at a *new* length is a new key; evict the old length when a new one for the same recipe and root completes.
  - A render that throws completes exceptionally. `drone` logs nothing (`:shell` has no logger) and returns null, which `BlockBaker` turns into silence. The failure is removed from the map, so the next bake retries.
- [ ] Tests, `DroneSourceTest`:
  - Four slices baked through `BlockBaker` on a 4-thread executor call `ResinDrone.render` once. Count calls with a test seam, an injectable render function defaulting to `ResinDrone::render`.
  - The four slices laid end to end equal one render.
  - A non-RESIN recipe goes to `inner`.
  - A throwing render gives silence, then retries.
  - Eviction keeps memory bounded.

Commit: "The grid gets its drone renderer from outside, so it still doesn't know what RESIN is"

### Task 6: The CLI door: `synth --drone`

- [ ] `snipsnap synth RESIN BASS --preset 3 --drone --root A1 --motion 0.6 --rate 2 [--bpm 90] [--bars 1] --out drone.wav`:
  - Pick n with `DroneFit.spanFor` for a session at that BPM and bar count and 44.1 kHz. Render with `ResinDrone`, and write the whole drone once.
  - Print `A1 · 4 bars · -1.97¢ · MOTION 0.60 · 2 breaths` and the path.
  - RESIN only. Refuse `--all`, and refuse `--instrument` alongside `--drone`. Refuse `--root`, `--motion` and `--rate` without `--drone`.
  - `--root` takes a note name (reuse the parser `--instrument` or `Keys` already has; don't write a new one).
- [ ] `--loop 3` writes the drone three times end to end, so a listener hears the wrap. That's the audition the testkit README asks for.
- [ ] Two tests in `SynthCommandTest`: a drone WAV of exactly `n * intervalFrames` frames, and the refusals.

Commit: "A drone you can hear without a phone: synth --drone"

### Task 7: The phone doors: SYNTH → grid, and the grid readout

- [ ] **LoopActivity:** wrap whatever source it builds as `DroneSource(source ?: KitSampleSource(dir))`, the one line at `Residency(session, …)`. Do the same wherever `LoopBounce` gets its source, or a bounce loses its drones.
- [ ] **Tempo:** in `onBpm`'s settle, `toWarm` and `toApply` become `DroneFit.refit(… .copy(bpm = targetBpm))`. The written copy becomes `DroneFit.refit((onDisk ?: toApply).copy(bpm = targetBpm))`. Read the handler's comments on live re-reads first and keep every one of them true.
- [ ] **SYNTH screen**, RESIN only: a full-width `DRONE TO LOOP ▸` `LabButton` after `MAKE INSTRUMENT ▸`. It opens a sheet like `HeldInstrumentSheet`:
  - **ROOT:** a note stepper across the voice's own TUNE range, 25 semitones from its root. It defaults to the open kit's key root in that range if the screen can reach the kit's `KeySpec`, else the voice root.
  - **MOTION:** a knob, 0..1, labelled in octaves (`±1.2 oct`).
  - **RATE:** a 1 / 2 / 4 cycler, labelled `BREATHS`.
  - **PREVIEW:** renders the drone at the loop session's current tempo, bars and device rate, or a fresh session's if none exists. It plays it twice through the existing preview path, so the wrap is heard. It shows `RENDERING…` while it works.
  - **SEND:** the same `LoopWrites.writing` block as `sendSnipToLoop` (App.kt ~1720). It keeps the unreadable-session and full-grid answers, and uses `SessionBuilder.sendDrone` in place of `send`.
  - Toast: `Copy.droneLanded(note, track, bars)`, e.g. "A1 DRONE ON TRACK 3, 4 BARS AROUND". Add `PersonalityTest` laws.
- [ ] **LoopGrid `describe`:** `is DroneBlock` → `"DRONE ${noteName(rootMidi)} · ${slice + 1}/$of"`. If `DroneFit.nudgeCents` is over 3 at the current tempo (the n = 8 case), append `· ${"%.1f".format(c)}¢`.

Commit: "RESIN drones reach the grid from the SYNTH screen, and follow the tempo"

### Task 8: Document it

- [ ] `README.md`: one paragraph beside the RESIN-held one.
- [ ] `docs/SYNTH_ROADMAP.md`: an S8.2 row after S8.1.
- [ ] `testkit/README.md`: "RESIN drones, made with the CLI". Give the `--drone --loop 3` command and three things to listen for: no seam at the wrap, the breath lands where the wrap is, and the note sits in tune against a keys instrument at the same root.
- [ ] The spec's status becomes "built", with a "What building it measured" section, as the held pad's spec has.

Commit: "Document RESIN drones"

### Task 9: Verification and the PR

- [ ] `./gradlew --no-daemon test`, full, on the exit code.
- [ ] Re-read the `:app` diff twice.
- [ ] Run the naming-guardrail grep over the new files.
- [ ] Push, open the PR against `claude/mobile-mpc-drum-sampler-t58x74`, subscribe, and drive it to green.

## Notes for the implementer

- **Why phases from the index, not accumulated:** accumulated Double phases drift by about 1e-10 cycles over a drone, which is harmless, but the probe's exactness came from recomputing identical values each period. Keep the probe's form. It is also cheaper to reason about.
- **Memory:** the longest drone is one 48 s interval (8 bars at 40 BPM). That's 34 MB of oversampled floats plus the decimator's two intermediates. Print the peak in `ResinDroneTest`'s cost test. Don't optimize unless CI or a phone objects; streaming decimation is the later fix.
- **A drone restarts on a tempo change.** `refit` can change n, which changes the chain, and `Arrangement.indexAt` then lands wherever interval % n says. That's accepted: the render changed anyway.
- **Out of scope** (spec): stereo, other engines, width motion, per-bar variation.
