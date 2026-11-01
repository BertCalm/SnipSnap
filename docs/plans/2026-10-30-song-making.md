# Song making: the plan

Status: proposed. Written 2026-10-30 against `e561bec68`, from a Luthier review
of the song paths (Arranger, the LOOP grid, ORBIT sections, the mixdowns and
the album). Nothing here is built yet.

## Why this plan exists

SnipSnap has three ways to make a song, and they grew apart:

| Path | Where | What a "song" is |
|---|---|---|
| Arranger (SONG ▸, `arrange`, `beat`, `album`) | `shell/.../Arranger.kt` | sections laid end to end, mixed to one WAV, or flipped as `.xpj` sequences |
| LOOP grid | `loop/.../Session.kt`, `Arrangement.kt`, `BlockBaker.kt`, `Mixer.kt`, `Bouncer.kt` | six chains that drift and realign after their least common multiple; no beginning or end |
| ORBIT sections | `loop/.../Orbit.kt` (`OrbitSection`), `OrbitEngine.kt` | rings restarted per section |

Each one sums its own tracks and sets its own level. Most of what the review
found lives in those seams.

## The measured baseline

From a scratch Kotlin test (deleted after the run, nothing committed), fixture
kit of `DrumSynth` kick, snare and closed hat at the default 92 BPM:

| Probe | Result |
|---|---|
| Arranger section starts against the bar grid | every boundary lands 600 ms (0.92 beat) late; into the turn 1,200 ms (1.84 beats); cumulative 5.52 beats by the outro |
| Grid: open hat (500 ms) on the last 16th of a 1-bar interval at 120 BPM | 375 ms cut, last sample 0.274, next interval starts at 0.000: a step of 0.27 every loop |
| Grid: six engaged tracks of the same kick (kick peak 0.858) | bounce peak 5.146, then `WavWriter` clamps to ±1 |
| Album: loudness of a sparse and a dense fixture song | 0.8 dB apart (not a finding at fixture scale) |

Read, not measured:
- With an Answer bed, `renderBed` makes each body section longer by the whole bass sample (`Arranger.kt:303-330`), so those boundaries slip further.
- `renderBed` plays any bed note outside 0.51..1.99× speed at the sample's own pitch, which is the wrong note (`Arranger.kt:312`).
- The grid's pattern baker never asks `SampleSource.muteGroups`, and ORBIT does (`OrbitBank.kt:87`).
- The 0.95 ceiling is typed four times: `KitPreview.kt:93`, `KitPreview.kt:223`, `Arranger.kt:269` and `SessionMixdown.PEAK`. The grid has none.

## The phases

Each phase is one PR, in this order. Phases 1 to 3 fix things that are
wrong today. Phases 4 to 8 add things producers would reach for. Phase 9 is a
design document, not code. The pure-JVM half of every phase can be built in a
cloud session. Anything on an Android screen waits for a session with an SDK,
by the house rule that a screen wired blind is worse than one left for a
session that can verify it.

### Phase 1. One song bus (S)

**Goal:** one place decides how loud a song may get, and the LOOP grid stops
clipping.

- Add `SongBus` to `:audio`, the one module `:loop`, `:kit` and `:shell` all depend on.
  - It holds `PEAK = 0.95f` and `fun ceiling(snip): Snip`, the peak scale every mixdown already does.
  - Replace the four copies with calls to it. The output stays byte-identical, so existing tests and goldens must not move.
- Give the grid a ceiling that playback and bounce share, because `Bouncer`'s promise is "the same path as playback":
  - After `Mixer.mix`, scale the interval by `min(1, PEAK / peak)`.
  - Ramp from the previous interval's gain over 10 ms, so the gain never steps.
  - Every buffer for an interval is baked before the mix, so the peak is known in advance and the ceiling needs no lookahead.
  - Put it in one function that `LoopEngine.playOne` and `Bouncer.toSink` both call.

**Tests:**
- the six-kick session bounces with peak ≤ 0.95 (5.146 today)
- a session that never exceeds the ceiling bounces byte-identical to today
- live (a capture `AudioSink` through `LoopEngine.runFor`) equals `Bouncer.render` sample for sample
- revert the ceiling and watch the first test fail

**Why first:** phases 2, 3, 7 and 8 all write audio through this bus, so it has to exist before them.

#### Phase 1, as built (2026-10-31)

- `SongBus` (`audio/.../SongBus.kt`) holds `PEAK`, `peakOf` and an in-place `ceiling(samples)`.
  - It works on the bare buffer every caller already held, not on a `Snip`.
  - `KitPreview.render`, `KitPreview.renderLoop`, `SessionMixdown.render` and `Arranger.mixdown` now call it.
  - `SessionMixdown.PEAK` is gone; its one test reads `SongBus.PEAK`.
- **Byte-identical, measured:** a scratch test hashed all four paths on a fixture hot enough to engage the ceiling (each at exactly 0.950), before and after the change. The hashes matched on all four. The test was deleted after the run. `SongBusTest` pins the arithmetic against the old inline form.
- The grid's ceiling is `IntervalCeiling` (`loop/.../IntervalCeiling.kt`). `LoopEngine.playOne` and `Bouncer.toSink` each call it after `Mixer.mix` and before the sink.
- **Changed against the design: a cut is immediate and only a rise is ramped.**
  - The design said to ramp from the previous gain over 10 ms. A downward ramp cannot hold the ceiling: the first frames of a loud interval would play at the old, higher gain, and a downbeat kick lives in those frames. There is no lookahead across the boundary, because the next interval is not mixed yet while this one plays.
  - So a cut lands at the boundary in one step, and a rise eases over `RAMP_MS = 10`. Any gain on the way up is under the target, so the ceiling holds everywhere.
- **Measured:** `IntervalCeilingTest`.
  - The six-kick fixture sums past 4 raw and bounces at the ceiling.
  - Under the ceiling, the bounce equals the raw mix byte for byte.
  - Four intervals of `LoopEngine` playback equal `Bouncer.render`, sample for sample, through both the cut and the ease.
  - With the bounce's `ceiling.apply` removed alone, two named tests fail. One reports "bounce peak 5.145858 over 0.95", the review's number.
- **Disclosed, not fixed:**
  - On a sound that runs straight through a boundary (a drone, a repeating loop), a cut is a level step of `(old − new) × |sample|` at that frame.
  - The gain is set per interval, so an interval with one loud hit plays quieter than its neighbours. The level steps at interval scale, which is a mild pumping.
  - Both are the price of a ceiling that never bends a waveform. A user who hears either should turn the track levels down, and a later screen can say how far the ceiling turned an interval down (`IntervalCeiling.gain`).
- **Found while building, not in scope:**
  - ORBIT (`OrbitEngine`) has no ceiling either.
  - It mixes continuous voices in about 43 ms blocks, so a per-block gain there would act as a limiter, pumping every block. It needs its own design and its own decision. It is not folded into this phase.
- **Heard:** nothing.

### Phase 2. Arranger sections on the grid, and the right bass notes (S to M)

**Goal:** every section starts on its bar, and the bed plays the written note.

- In `Arranger.mixdown`, compute each section's start from the cumulative pulse count:
  `round(cumulativePulses × framesPerPulse)`.
  - Do not sum rounded frame counts, or rounding drift creeps back in.
  - Add each segment into the output at its start (overlap-add) instead of `copyInto` after the previous one ends (`Arranger.kt:262`). The 0.6 s ring-out (`KitPreview.kt:46`) then sounds under the next section's downbeat, which is what a real tail does.
  - The outro's ring-out stays at the end of the song.
- **Pull-up into the turn:** build the spinback from the outgoing audio and play it over the outgoing section's last beat, so the turn drops on its downbeat. This is decision D1 below; the default shown is the recommendation.
- **Bed:** let `renderBed` overlap the same way, and stop it extending the segment.
  - Repitch with `Resampler` directly instead of `TempoFit.repitch`. `TempoFit.MAX_RATIO = 2f` is a rule about tempo fits, not about notes.
  - A note the bed truly cannot reach is skipped and named in the section's `reason`, never played at the wrong pitch (decision D4).
- Then put `SongBus.ceiling` on the result.

**Tests:**
- `sectionStarts[i]` equals the grid frame exactly, for every section, at 92 and at 140 BPM
- song length = total bars + one final ring-out
- a bed note 19 semitones over the root renders within 50 cents of its written pitch (`Pitch.detect`)
- `ArrangerTest.kt:281` ("ring-outs and transitions on top") is rewritten to the new rule in the same commit, saying why

#### Phase 2, as built (2026-11-01)

- **Sections on the grid.**
  - `Arranger.mixdown` counts each section's start in pulses and turns it into a frame once, with KitPreview's own arithmetic (`60 / bpm × RATE / PULSES_PER_BEAT`).
  - Segments are added into the song at those frames, so each ring-out sounds under the next section's opening.
  - `Mix.sectionStarts` now reports the grid, which is what ARRANGE's needle reads.
- **Pull-up (D1):** `pullUpOver` keeps the outgoing section up to its last beat, plays `BeatTape.pullUp` built from that audio across the last beat, and stops at the turn's downbeat. A pulled-up record has stopped, so nothing of the outgoing section, bed included, sounds under the turn. The outro's tape stop is unchanged.
- **Bed (D4):**
  - `repitched` resamples to a virtual rate at any ratio, which is `TempoFit.repitch`'s arithmetic without its factor-of-two bound.
  - The old `0.51..1.99` window refused a speed of exactly 2, so **every octave note in an Answer line played at the root, an octave low**. `Answer.pickOffset` picks the octave 12% of the time (34% in a scale without a fifth), so this was routine, not an edge case.
  - A note past `BED_REACH_SEMITONES = 24` is left out. No line the app writes reaches it (the Answer's widest leap is the octave), so it is not plumbed into a section's `reason` as the plan suggested; only a hand-edited `answer.json` could get there.
- **Found while building, fixed because they move section timing:**
  - `tiled` offset each repeat by a four-four bar (`PULSES_PER_BAR`) whatever the clip's meter. `Mpc3Clip` refuses a note past its own length, so for a three-four clip repeated more than once this did not play late: it threw. Every repeated section is tiled, so a waltz-time groove could not be mixed down at all. It now steps by `clip.lengthPulses`.
  - The bed was tiled by the drum section's repeat count, not by its own length. A two-bar line under a one-bar clip played 4 times ran 8 bars, past the section into the next; a one-bar line under a four-bar clip played once covered one bar of four. `bedNotes` lays the line end to end across the section and drops anything at or past its end.
- **Measured:** `ArrangerTest`, 22 tests.
  - Section starts equal the grid frame exactly at the default 92 BPM and at 140 BPM.
  - The song runs less than a second (the outro's ring-out) past its last bar.
  - `pullUpOver` leaves everything before the last beat untouched, fills the beat, and ends at the turn.
  - Bed notes 12, 19 and −12 semitones from a 110 Hz sine land within 50 cents.
  - A three-four clip tiled 3 times is nine beats with no gaps, and the bed covers a section exactly in both directions.
- **Each guard is proven by reverting it alone:**
  - End-to-end placement fails the grid test and the length test ("158770 frames" past the last bar, 3.6 s, the review's stumble summed).
  - The four-four tiling fails the three-four test ("note at 8640 falls outside 3 bars").
  - The old window fails the pitch test ("12 semitones: wanted 220.0 Hz, heard 110.0").
- **Disclosed, not fixed:**
  - A bed note that is still ringing when the next section's bed starts overlaps it. The bed is a sampler one-shot, not a monophonic voice, as it was before.
  - The spinback starts at 0.8 of the last sample before the cut, so the turn's pull-up opens with a small level step. This is unchanged from the appended version.
  - `BeatTape.arrange` (SIDE A) also steps by `PULSES_PER_BAR` and is left alone here; its gaps between kits are a tape leader by design.
- **Heard:** nothing. The boundary after the theme is the moment to listen to, before and after.

### Phase 3. Grid patterns: no click, real tails, chokes (M)

**Goal:** a pattern on the grid sounds like the same pattern on ORBIT.

- **3a, the click (S):**
  - When `bakePattern` cuts a hit at the interval end, fade it over `BlockBaker.TAIL_FADE_MS`, as `retrigger` and `conform` already do.
  - **Test:** the open-hat probe's last sample is under 1e-3 (0.274 today).
- **3b, tails carry over (M):**
  - A baked pattern becomes a body of exactly `intervalFrames` plus an overhang capped at one interval.
  - The mix of interval i adds the overhang of each track's block at i-1.
  - A hit nudged early on step 1 becomes a pre-roll the same way, so it is no longer dropped.
  - `Residency` already caches by block, so the overhang lives with its block.
  - Loop and drone blocks keep today's rules: a drone's slices are contiguous, and a fitted loop is cut on purpose.
  - Update the "every buffer is exactly one interval" KDoc in `Session.kt` and `BlockBaker.kt` to say body, not buffer.
  - **Test:** an open hat on the last 16th of block A is heard at the start of block B, and a single-block chain wraps its own tail.
- **3c, chokes (S):**
  - `bakePattern` reads `source.muteGroups(kit)`. A hit in group g ends the earlier voices in g over `AutoPlace.CHOKE_FADE`, the fade ORBIT uses (`OrbitEngine.kt:374`).
  - **Test:** in group 1, an open hat followed by a closed hat leaves under −60 dB of open hat after the fade. The same pattern rendered by the grid and by ORBIT agrees within a stated tolerance.

### Phase 4. Export the song from the app (S pure, then S app)

**Goal:** what SONG ▸ plays can leave the phone. Today the mix exists only in memory.

- Move the body of `ArrangeCommand.run` (stage the program, write the `.xpj` with section-named sequences, optional mixdown WAV) into a `:shell` function, `SongExport.write(kit, kitDir, plan, dest)`, and make the CLI call it.
  - **Test:** the CLI output stays byte-identical, proven by the existing CLI tests.
- **App, SDK session:** an EXPORT on `ArrangeScreen` calls `SongExport`. It writes where OUT already writes, and offers the WAV to the share sheet the way GROOVE's SHARE ▸ does.

### Phase 5. An arrangement you can edit (M pure, then M app)

**Goal:** the Arranger suggests a song, and the user changes it.

- **Pure:** a stored `SongPlan` (`song.json` next to `groove.json`, written through `AtomicFile`).
  - A plan is an ordered list of sections. Each section has a clip reference (captured, tight, half, sparse, fill, ghosted, or one of the user's programs), repeats, and a transition.
  - `Arranger.arrange` writes the first plan. After that the stored plan wins until the user rerolls.
  - Edits: move, delete, duplicate, set repeats, swap the clip, set the transition.
  - Clips are referenced by name and derived when the song renders, so a plan never holds stale audio.
- `mixdown` and `SongExport` read the stored plan.
- **Tests:** a JSON round trip; a stale reference to a deleted program is refused by name; a reroll replaces the plan and says so.
- **App, SDK session:** `ArrangeScreen` gains drag to reorder, a repeats stepper per section, and a clip picker.
- **Where the plan lives** is decision D3.

### Phase 6. More transitions (S each)

**Goal:** boundaries that do something besides stop and spin.

Each transition is one function, (outgoing last bar, bpm) → audio over that bar. It never makes the section longer, so the grid from phase 2 holds.
- **DROP:** the last bar keeps only the kick, or only the bed when there is one.
- **CLOSE:** a low-pass sweep over the last bar. The filter is `Dsp.TptSvf`, which `:shell` already reaches through `:synth`.
- **REVERSE CRASH:** a crash or open-hat pad reversed into the downbeat. It is skipped and named when the kit has neither.

The Arranger picks defaults by the same measured-rule style it uses now, each with a `reason`, and phase 5's editor can change them. Tests: length unchanged, the next downbeat exact, deterministic per seed.

### Phase 7. Song and album loudness (S)

**Goal:** songs and album tracks sit at the same loudness, not just under the same peak.

- `SongBus.finish`: gain to `SONG_LOUDNESS_TARGET` by `Loudness.of`, then `TruePeak.limit` at `TruePeak.DEFAULT_CEILING` (−1 dBFS).
- The target is a "shape" constant. Choose it by measuring the current mixdowns of every kit in `testkit/` first, and write the measurement into the commit.
- `AlbumCommand` and the Arranger mixdown both use it.
- This deliberately changes every mixdown's level, so it ships alone and says so.
- **Test:** two kits of different density land within 0.5 dB of each other, with true peak at or under the ceiling.

### Phase 8. Stems (S to M)

**Goal:** a producer can take the song into a DAW.

- **Grid:** `Bouncer` writes one WAV per engaged track beside the mix.
- **Arranger:** stems by role (drums, bed). Drum sub-stems (kick, snare, hats, other) come from rendering the clip with its notes filtered by pad class.
- Each stem gets the same per-interval gain the bus applied to the mix, so the stems sum to the mix.
- **Test:** the sum of the stems equals the mix within 1e-5, before the final limiter (phase 7). The limiter is disclosed as mix-only.

### Phase 9. One song model (design doc only)

Write `docs/superpowers/specs/<date>-one-song-model-design.md`:
- A song is phase 5's section list, with a section source that can be a groove program, an ORBIT section, or a LOOP grid scene.
- Everything renders through phase 1's bus and phase 2's grid placement.
- The Arranger becomes the "suggest a song" generator for that model.

This picks up the roadmap's unscheduled "unifying the three note models" (`FEATURE_PLAN.md`, wave YYY). Build nothing until Josh has read the spec.

## Waiting on Josh: the song-mode capture (unblocks GG3.2)

`Mpc3Song.items` can carry `(sequence, repeats)` steps today, but the writer
refuses them until a real file shows the schema (`Mpc3ProjectWriter.kt:47-50`,
`FEATURE_PLAN.md:847`). When the capture lands, phase 5's plan writes real
song steps, so the hardware plays the song instead of the user flipping
sequences by hand.

What to save on the Live III, so the diff isolates the song bytes:
1. A new project with three short sequences of different lengths (for example 1, 2 and 4 bars) and a sound or two in each. Save it as `song-capture-empty`.
2. In Song mode, add three steps: sequence 1 repeated 2 times, sequence 3 repeated 1 time, sequence 2 repeated 3 times. The out-of-order steps and different repeats make the step order and the repeat count unambiguous. Save it as `song-capture-steps`.
3. Copy both `.xpj` files, with their project data folders, into `reference/`.

## Decisions for Josh (each has a default the plan uses)

- **D1. Pull-up into the turn.** Default: the spinback plays over the outgoing section's last beat, so the turn lands on time. The alternative keeps a deliberate gap, the way a DJ rewind really stops the record. The default is recommended because a song played back on the MPC cannot have that gap.
- **D2. The grid's ceiling.** Default: a per-interval gain with a 10 ms ramp. It is deterministic, identical live and in the bounce, and costs one peak scan per interval. The alternative is a real limiter with attack and release, which reacts within an interval but has to run the same way in both paths.
- **D3. Where `song.json` lives.** Default: the kit folder, next to `groove.json`, where the Arranger reads today. `DIRECTION_2026_10.md` moves the beat to the project, and the plan should follow it when that wiring lands rather than wait for it.
- **D4. Bed notes out of range.** Default: repitch to the written note at any ratio. The alternative folds by octaves to keep the timbre near the sample's own.

## Not in this plan

- MIDI clock in and out, and one transport for ORBIT and BEAT (`MIDI_GAPS_2026_10.md:53-55`). That is Midiwright work.
- A grid bounce imports as a mono snip. `SnipStore.import` says that is deliberate ("the ring is mono; the deck reads mono"). Phase 8's WAV export is where stereo survives.
- `BeatTape`'s gaps between kits. They are a tape leader by design.
- Per-step velocity, length and probability in the step editor (the UI review's gap). That belongs in a BEAT screen plan.

## Ledger

- **Verified:** read on `f6a47dba3`. The files this plan touches are unchanged at `e561bec68`.
- **Measured:** the four baseline numbers above, by a Kotlin scratch test against the real `Arranger`, `Bouncer` and `DrumSynth`.
- **Proposed:** every phase, the 10 ms ramp, the one-interval overhang cap, the transitions, the loudness target.
- **Heard:** nothing. The section-boundary stumble (phase 2) is the one to listen for before and after.
