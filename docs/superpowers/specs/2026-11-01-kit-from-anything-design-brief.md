# KIT FROM ANYTHING: design brief

Status: concept, prototyped in Python with the repository's own classifier as the judge. Nothing
in Kotlin, nothing heard.
Erratum 2026-11-01, after review of PR 796: the harness's pitch probe normalized its NSDF
wrongly and clipped every confidence to 1.0 (fixed in `.claude/skills/luthier/scripts/probes.py`,
see the QUARRY brief's erratum); the ride, crash and siren gates were not evaluated; the
assembler was placed in the wrong module; and the mute-group path needs an override. All four
are corrected below and every number is regenerated. The drum verdicts did not change.
Repository truth: commit `dfc8fd0a`, read 2026-11-01. APIs grepped before being named:
`Classifier` (the rules quoted below, `Classifier.kt:139-206`), `FeatureExtractor`
(`Features.kt`: bands under 200 Hz, under 2 kHz, 2 kHz and up; decay is the time to −20 dB),
`AutoPlace` (the conventional layout and `HAT_MUTE_GROUP = 1`), `Balance.TARGETS`, `KitPad`
(`slot`, `drumClass`, `colorHex`, `level`, `tuneCoarse`, `muteGroup`, `oneShot`, `source`,
`recipe`, `velocityLayers`), `KitAssembler.assemble` / `assembleArranged` / `ArrangedPad`,
`Scale` (`CHROMATIC MAJOR MINOR MAJOR_PENTATONIC MINOR_PENTATONIC`), `Tuner.inKey`,
`Chopper.byTransients`, `Stretch`, `Cleanup`, `Desample`, `Body`, `PadFromAnything`,
`ThumpPresets` (the BOOM BAP and HOUSE rows), `Treatments`, `Eras.names`
(`sp1200 mpc60 tape phone`), `GrooveFeel`, `Kit.tempoBpm`, `ChopCommand.SURE_CONFIDENCE`,
and the CLI's `classify`.

Prototype: [`2026-11-01-kit-from-anything-prototype.py`](2026-11-01-kit-from-anything-prototype.py),
results in [`2026-11-01-kit-from-anything-prototype-results.json`](2026-11-01-kit-from-anything-prototype-results.json).
The renders are Python; the verdicts on the drum slots are the real Kotlin classifier, run
through `cli/build/libs/snipsnap.jar classify` on the written WAVs. "Measured" below means that.

This is not an engine. It is a kit assembler, and its gesture is the kit's: **one sound → sixteen
pads that play together in one key and one style**.

## Where it came from, and what is already decided

Josh's idea: any captured sound becomes four bass notes, four tones, a kick, a snare, a closed
and an open hat, a ride, a crash, a clap and a siren; the user picks a key and a genre; the app
chops, stretches, compresses and treats the source into a playable kit. Decisions taken in
conversation: **purist by default** (every pad is the source, transformed), the synth is a
**blend fallback, never a replacement**, the tonal half of that fallback is the QUARRY engine
([its brief](2026-10-31-quarry-engine-design-brief.md), merged), and the first three genres are
**house, techno and boom bap**.

## What it is

Input: one captured `Snip` (or a longer capture the chopper can cut), a key (`rootSemitone` and a
`Scale`), a genre. Output: a kit folder through `KitAssembler.assembleArranged`, sixteen
`ArrangedPad`s in a fixed layout, each tagged, levelled by `Balance`, chokes set, tempo written to
`Kit.tempoBpm`, provenance in each pad's `source` map, and a recipe that can rebuild the pad.

The layout is fixed by the slot list, so `AutoPlace` is not used; its conventions are kept
(kick A01, snare A02, closed hat A03, open hat A04, hats choke each other, nothing else does).
One integration fact decides how: `KitAssembler.assembleArranged` sets every pad's choke
group from its class through `AutoPlace.muteGroupFor` (`KitAssembler.kt:164`), and
`ArrangedPad` has no override, so a ride or crash tagged HAT_OPEN would be choked by the
closed hat. `ArrangedPad` gains an optional `muteGroup` override, honoured by the assembler,
and A06 and A07 carry group 0 (found in review of PR 796).

| Pad | Slot | Made from the source by | Tag (`DrumClass`) | Probe |
|---|---|---|---|---|
| A01 | KICK | the source's forced cycle sung at the genre's kick pitch with a pitch drop, driven then low-passed steeply; the source's own first 8 ms as the click | KICK | classifier says KICK |
| A02 | SNARE | the source's own noise (below) decaying, brightened per genre, over the forced cycle at the genre's snare tune | SNARE | classifier says SNARE |
| A03 | HAT CLOSED | the source read faster (the sampler trick: faster is higher) for the first 30 ms, over its noise read faster, high-passed at 9 kHz, short | HAT_CLOSED | classifier says HAT_CLOSED |
| A04 | HAT OPEN | the same, long | HAT_OPEN | classifier says HAT_OPEN |
| A05 | CLAP | four bursts of the source's noise 12 ms apart, rising, then its tail | CLAP | classifier says CLAP |
| A06 | RIDE | the source's noise read 4× faster as the wash, band-passed 4 to 11 kHz, long; the real transient read 4× faster as the stick; a short cycle ping as the bell | HAT_OPEN (no ride class exists), choke group 0 | centroid over 4 kHz, decay over 300 ms |
| A07 | CRASH | the source's noise and the source itself, both read 3× faster, paulstretched into one wash, high-passed at 3 kHz, capped under the LOOP line | HAT_OPEN, choke group 0 | under 1.5 s, decay over 500 ms |
| A08 | SIREN | the source's cycle along a pitch trajectory: a drop (boom bap), a rise and fall (house), a climb (techno) | PERC | the trajectory's range in semitones |
| A09 to A12 | BASS | QUARRY at the key's root, third, fifth, octave from C1 plus root, low-passed per genre | TONAL | root within 20 cents |
| A13 to A16 | TONE | QUARRY an octave and a half up, grain field open, longer | TONAL | root within 20 cents |

**The source's own noise** is the one new primitive: the source divided by its own exact long-term
magnitude spectrum (two passes, floored at −50 dB), its arrival paulstretched to the length
needed with a short window, and read faster until at least half its power sits above 2 kHz. It is
still only the source. The floor is the purist limit: what cannot be flattened within it is not
forced, and that slot falls back to the blend.

**Found before made.** When `Chopper.byTransients` finds two or more hits in the capture, every
slice is classified first, and a slice the classifier files as the slot's class at or above
`ChopCommand.SURE_CONFIDENCE` is used as the pad, treated by the genre's landing chain only. A
break is the purist's best case and should never be synthesized over.

## The genre table

Every number is "shape" until a listen. The drum numbers are translated from the house's own
THUMP preset rows: BOOM BAP from `DUSTY BOOM` to `TAPE THUMP` (kick TUNE .16 to .34, DECAY .37 to
.53, CLICK low), `DEEP ROOM` to `BACKBEAT`, `RAGGA TICK` to `FAST TAP`; HOUSE from `FOUR FLOOR`
to `PEAK TIME` (TUNE .33 to .51, SWEEP .48 to .68, DECAY .14 to .30, CLICK .38 to .62),
`CRACKED CLAY` to `PICCOLO`, `CLUB TICK` to `PEAK TAP`; TECHNO from HOUSE's `WAREHOUSE` and
`PEAK TIME` corner pushed (the shortest decay, the most click, the most drive). The repo has no
techno rows, so that one is the author's extrapolation and is marked so.

| | BOOM BAP | HOUSE | TECHNO |
|---|---|---|---|
| Tempo written | 92 | 124 | 132 |
| Kick: pitch, drop, decay, click, low-pass | 52 Hz, ×2.2 over 40 ms, 280 ms, .25, 110 Hz | 48 Hz, ×3 over 25 ms, 240 ms, .45, 120 Hz | 50 Hz, ×4 over 18 ms, 170 ms, .6, 115 Hz |
| Snare: tune, decay, brightness | 190 Hz, 230 ms, .45 | 220 Hz, 170 ms, .6 | 240 Hz, 120 ms, .7 |
| Hats: closed, open, read speed | 70 ms, 380 ms, 5× | 90 ms, 480 ms, 6× | 50 ms, 300 ms, 7× |
| Clap decay | 180 ms | 220 ms | 150 ms |
| Ride decay, crash length | 700 ms, 1.3 s | 800 ms, 1.4 s | 600 ms, 1.2 s |
| Siren | drop, 7 semitones over .45 s | rise and fall, 12 over .8 s | climb, 12 over 1 s |
| Bass: decay, low-pass; tone decay | 450 ms, 900 Hz; 800 ms | 280 ms, 1.2 kHz; 900 ms | 160 ms, 700 Hz; 500 ms |
| Landing chain (proposed, not prototyped) | `Eras` sp1200 at .6, SQUASH | `Eras` tape at .3, small ROOM | VALVE drive, SQUASH, dry |
| Groove (proposed) | `GrooveFeel.generated`, swung 16ths | straight, open hat on the offbeat | straight |

The landing chains are `FxChain` recipes over existing sections and the existing eras, applied
per pad after the slot is made, exactly as `Treatments` already applies a named character.

## The blend rule

Per slot, the probe in the table is the gate. When the purist render fails it, the pad becomes a
blend, and the rule has three parts. The synth half is chosen **to sound like the purist render**:
for a drum slot `Desample` finds the nearest THUMP patch to the purist render (not to the raw
source), so the synth kick is the kick this bark almost was; for a bass or tone slot QUARRY is
already the source's own material, and until it ships `Body` ringing the key's chord tones on
the hit is the bridge. The share is `1 − confidence` of the purist render against its probe,
**capped at 0.5**, so the source is never less than half the pad. The pad's `source` map says
so in words (`made: purist` or `made: blend, synth THUMP KICK 0.4`) and the pad sheet shows it;
nothing is silent about it.

## What the prototype measured

Four synthesized stand-in sources (a pluck, a sine-sweep kick, a two-formant bark, a snare) ×
three genres = twelve kits, 192 pads, every one judged or probed. Key: A minor.

**Drum slots judged by the real classifier (5 slots × 12 kits = 60):**

| Pass | Agreed | What the numbers changed |
|---|---|---|
| 1 | 19 / 60 | kick bodies read centroid 250 to 850 Hz (TOM, PERC, TONAL): the forced cycle at 50 Hz is only 2.3× slower than the excerpt, so the source's formants stay in the mids. Claps and snares failed the flatness gates. Open hats tiled a sped-up 60 ms source and read as 12 ms closed hats. |
| 2 | 26 / 60 | drive before a 4-pole low-pass at 110 to 130 Hz, shorter kick decays: kicks 11 / 12. Noise from whitening alone still carried a pluck's harmonic lines. |
| 3 | 52 / 60 | exact (unsmoothed) whitening, "read faster until bright", the hat noise read at the hat's speed: hats 24 / 24, kicks 12 / 12. |
| 4 | **56 / 60** | two-pass whitening at a −50 dB floor: snares 11 / 12, claps 9 / 12. |

The four that remain all come from the sine-sweep kick source: its snare (boom bap) and its three
claps read PERC, because a single swept sine has almost no noise to quarry. That is the fallback
set, by design, and it is the right one: a source with nothing in it should blend, not be forced.

**Every other slot, gated as the table says (regenerated with the fixed probe):**

| Slot | Gate | Result |
|---|---|---|
| RIDE (12) | centroid over 4 kHz, decay over 300 ms | 12 / 12 after the noise-based recipe; the first recipe tiled a sped-up kick into a 23 ms ride, and a review caught that the gate had never been run |
| CRASH (12) | under 1.5 s, decay over 500 ms | 12 / 12; decays 580 to 911 ms |
| SIREN (12) | the ordered pitch readings follow the genre's trajectory: eight 50 ms windows, each confident reading within 150 cents of the expected pitch at that instant; fewer than six confident readings is unmeasured, not a fail | 8 pass (worst 34 cents off the trajectory), 3 fail (the snare source in every genre, readings thousands of cents off: its cycle has no fundamental to sweep, the same limit as its bass pads), 1 unmeasured (the bark's house rise-and-fall, one confident reading). A first version of this gate took the min-to-max span and passed a 36-semitone jumble as a 7-semitone drop; a review caught it. |
| BASS and TONE (96) | within 20 cents of the target, confidence 0.3 or better | 78 / 96, worst 11.2 cents, confidences 0.82 to 0.999. The 18 misses are all from the snare source: its bass pads read near 900 to 1800 Hz (a harmonic region, 36 semitones high) and its tones 220 cents high at confidence about 0.8. A noise-dominated source's forced cycle has a weak fundamental, which is a real finding and exactly the case the tonal fallback exists for. The first version of this brief called these sub-harmonic probe errors; that was the probe's bug, and it is retracted. |

**The fallback set, complete:** 25 of 192 pads fail their gate: 1 snare and 3 claps (the sine
sweep has no noise), 3 sirens and 18 bass and tone pads (the snare source has no fundamental to
sing or sweep). Every one names a source with nothing to give for that slot, which is what the
blend is for. Kept apart from that set: 1 pad the probe could not read (the bark's house siren),
which says nothing about the pad and waits for a listen or a better tracker. All 192 pads land
under the 1.5 s LOOP line. The classifier, where no
class exists for the slot, files the siren PERC, the ride SNARE or PERC, the crash HAT_OPEN or
SNARE, and 94 of 96 bass and tone pads PERC; the assembler tags these itself.

## Risks

1. **Identity.** Whitening and paulstretching push "the source's noise" toward any noise; the
   −50 dB floor and the two-pass limit bound it, and the "read faster" rule keeps the source's
   own transient in every hat. Whether a bark's kit still sounds like the bark is a listen, and
   the audition page should play each pad beside the raw source.
2. **The classifier is the judge for five slots only.** Ride, crash and siren have no class;
   their tags are the assembler's, and `Balance.TARGETS` and the pad colour follow the tag.
3. **Kicks from a source with no low end** are manufactured by the forced cycle; the prototype's
   twelve kicks all passed, but a real phone capture with the mic's roll-off has not been tried.
4. **The pitch probe.** The harness's NSDF was mis-normalized until 2026-11-01 (erratum above);
   the Kotlin tests use `Pitch.detect`, which was never affected, but any number this brief
   quotes with a confidence came from the fixed script. A sweep (the siren) is read in 50 ms
   windows against the expected trajectory; one pad in twelve gave too few confident readings
   and is reported as unmeasured, never as a fail.
5. **The LOOP line.** A crash wants to be long; it is capped at 1.45 s so the kit stays a one-shot
   kit. HOLD is not part of this feature.
6. **Render time**, proposed: sixteen offline chains per kit; the whitening STFT and paulstretch
   on under a second of material are cheap, the eight QUARRY notes cost tens of milliseconds each
   with its cache. The Python harness renders a kit in under two seconds with nothing cached. A
   genre change re-renders all sixteen; a key change re-renders eight.
7. **Module placement.** `:synth` depends on `:kit` (`synth/build.gradle.kts:893`), and the
   assembler needs `FxChain`, `Eras`, QUARRY and later `Desample` from `:synth`, so it cannot
   live in `:kit` without a dependency cycle. It lives in `:synth` beside `SynthKits`, which
   already builds `ArrangedPad` lists for `:kit` to assemble (found in review of PR 796).
8. **Recipes.** These pads are derived audio, neither a raw capture (whose recipe is an
   `FxChain`) nor a synth patch. The deterministic promise ("a kit folder rebuilds its WAVs from
   the sidecar") needs each pad to carry `{slot, genre, key, source hash, version}` and the
   source WAV kept in the folder. This is the same decision QUARRY's brief files as R0, and it
   should be made once for both.

## Build rounds

- **R0** the recipe decision above, shared with QUARRY; two real capture fixtures (one tonal, one
  drum) under a second each, checked into test resources.
- **R1** the drum eight, purist only, in `:synth` beside `SynthKits` (a `KitFromAnything`
  object returning `List<ArrangedPad>` into `KitAssembler.assembleArranged`, with the
  `muteGroup` override added to `ArrangedPad` first), the genre table as data, the landing
  chains; the Kotlin test runs the real classifier over the five judged slots on both fixtures
  and asserts the agreements the prototype reached, runs the ride, crash and siren gates, and
  reports the fallback set rather than hiding it. CLI: `kitfrom <wav> --key Am --genre house
  --out <dir>`.
- **R2** bass and tones through `Body` as the bridge, then QUARRY when its R1 lands; `InKey` is
  not needed since the notes are rendered at pitch.
- **R3** the blend rule with `Desample`, the `source` map wording, found-before-made via the
  chopper.
- **R4** the pad sheet door, the audition page (each pad beside its source, three genres, two
  keys), docs.

## Verified / measured / proposed / heard

| Claim | Status | Evidence |
|---|---|---|
| The classifier's rules and bands are as quoted | verified | `Classifier.kt:44-206`, `Features.kt:57-125` |
| `KitAssembler.assembleArranged` takes arranged pads | verified | `KitAssembler.kt:85` |
| 56 of 60 judged drum slots agree with the real classifier | measured | results JSON `score` |
| The four misses are all from the sine-sweep source | measured | results JSON `kits` rows with `agrees: false` |
| 78 of 96 bass and tone pads within 20 cents at confidence 0.3 or better, worst 11.2 | measured | results JSON, `gate_pass` rows |
| The 18 misses are all the snare source's, reading a harmonic region | measured | results JSON, `cents` rows |
| Ride and crash gates pass 24 of 24 | measured | results JSON, `gate_pass` |
| Siren trajectory gate: 8 pass, 3 fail, 1 unmeasured | measured | results JSON, `siren_status` |
| The fallback set is 25 of 192 pads, plus 1 unmeasured kept apart | measured | results JSON `score.fallback_by_slot`, `score.unmeasured_by_slot` |
| All 192 pads under the LOOP line | measured | `under_loop_line` |
| `:synth` depends on `:kit`, so the assembler lives in `:synth` | verified | `synth/build.gradle.kts:893` |
| `KitAssembler` derives the choke group from the class with no override | verified | `KitAssembler.kt:164` |
| The landing chains and grooves suit their genres | proposed | not prototyped |
| A bark's kit sounds like the bark | heard? | not heard; audition pad-beside-source |
| The techno numbers | proposed | extrapolated from HOUSE's corner; no techno rows exist in the repo |

## Why it is built this way

- **The classifier is the referee, not a filter.** Each drum pad is made to satisfy the rules the
  repo already uses to tell a kick from a tom, so what lands on A01 is a kick by the app's own
  definition. That is also why the design went through four passes: the numbers told it where
  the mids were hiding in the kick and why a pluck would not become a clap.
- **Three tricks make every drum from any sound.** Read it faster for hats (higher and shorter),
  sing its cycle at a drum pitch for kicks and bodies, and whiten its arrival into noise for
  snares and claps. All three are the source, so a kit made from a cough is still the cough.
- **The genre is a table, not a brain.** Three rows of plain numbers, each traced to a preset
  the repo already ships, so a fourth genre is a row and a listen, not a new mechanism.
- **The fallback is bounded and spoken.** When the source has nothing to give for a slot (a pure
  sine has no noise, a snare has no fundamental), the synth blends in at no more than half and
  the pad says so. Forcing a clap out of a sine would have been a lie in the kit folder.
- **Found beats made.** A capture that already contains a snare should put that snare on A02.
  Synthesizing over a real hit is the one thing this feature must never do.

## What would sharpen the next prompt

Two real captures of yours (a tonal one and a drum-like one, a second or less, 44.1 kHz), the
one genre to build first, and whether the four bass notes should be root-third-fifth-octave as
here or a walking shape (root, flat seventh, octave, fifth) for boom bap.

## As built (R0 and R1, 2026-11-01)

`KitFromAnything` (`synth/src/main/kotlin/com/snipsnap/synth/KitFromAnything.kt`), its test,
`ArrangedPad.muteGroup`, and the CLI verb `kitfrom <wav> [--key] [--genre] [--out] [--name]`.
Python prototype numbers above; what follows was measured in Kotlin against the real
classifier, on the same four stand-in sources (the Python prototype's WAVs) × three genres.

**What changed against the design, and why (each one a number the Kotlin run gave):**

1. *Which cycle a source yields is no longer the classifier's TONAL verdict.* `classifyBright`
   has no TONAL arm, so a nylon pluck at A3 is PERC to the classifier and was forced into a
   46 ms excerpt holding ten periods; sung at A1 it read 550 Hz (ten times the note). Now the
   classifier vetoes only the noise classes (hats, clap) and the pitch detector decides: a
   period at confidence 0.5 or better is a note (the siren's base, the provenance says
   "pitched"); one at 0.3 or better still shapes the cycle, because any excerpt with a period
   in it (a snare's 180 Hz body) reads as a harmonic of whatever it is sung at.
2. *The source's noise is frozen, not stretched.* A short-window stretch walking along the
   arrival followed its decay; every tile of that noise started loud and died, the open hat
   read as a 70 ms closed hat and the ride decayed in 110 ms. The noise is now one 1024-point
   window at the arrival with new seeded phases every frame (`Stretch.freeze`'s idea),
   stationary by construction, then read faster until half its power is above 2 kHz.
3. *The colour pressed back onto a note is the envelope, smoothed over a kilohertz.* Five-bin
   smoothing kept a pitched source's harmonic lines, which carved the new note's own
   harmonics out: a tone at A3 read 440 Hz. Formants survive the wider smoothing; lines do not.
4. *The snare has a gentle 7 kHz shelf and more body* (0.8 noise, 0.5 body): the frozen noise
   is brighter than the Python one, and the techno snare read as an open hat without it.

**Measured, Kotlin, twelve kits, 192 pads, every pad gated:** 183 pass, 8 fail, 1 unmeasured.

| Source | Gates failed |
|---|---|
| pluck (pitched 219.8 Hz) | none, in all three genres |
| bark (forced) | none, in all three genres |
| snare (forced) | TONE_ROOT and TONE_THIRD in every genre (read 116 to 118 cents sharp at confidence 0.83: the noise-dominated cycle's fundamental is weak, the case the tonal blend exists for); BASS_ROOT unmeasured in techno (no pitch read on a 160 ms note) |
| sine-sweep kick (forced) | HAT_CLOSED and HAT_OPEN in boom bap only (PERC: at that genre's 5× read speed the sweep's noise does not reach 12 kHz; house and techno read faster and pass) |

The sine sweep's snare and claps, the Python prototype's fallback set, pass in Kotlin: the
frozen noise is brighter and flatter than the walked stretch was. The test suite asserts the
agreements above on a pluck preset, a THUMP snare and a formant burst in every genre, ride and
crash on four sources, the notes and the siren on the pitched source, and that a pure sine
sweep keeps every pad and names what it failed.

**After the review of PR 826** (each verified before being taken): the fast read is band-limited
through the repository's windowed-sinc `Resampler`, so nothing folds into a drum's band; the
whitening runs once per source and every slot freezes its noise from that one window; thirds
are chosen by semitone interval, not scale index (a pentatonic's third entry is its fourth;
CHROMATIC takes the minor third); every pad ends in the house 4 ms fade before it is measured
or written; non-finite samples are refused in words; the pitch gates read a pad faster or
slower by a power of two so C1 and a 1760 Hz siren both sit inside `Pitch.detect`'s 40 to
1200 Hz, with a low-key and a high-siren test; and the CLI bounds the read to the 30 s limit
before decoding, through `WavReader.readCapped`.

**R3, the blend (built 2026-11-01).** A pad whose purist render fails its gate is blended:
the purist stays, never under half, and a partner joins at `BLEND_SHARE` (0.5: `1 − confidence`
of a failed gate, capped). A drum's partner is `Desample.nearest` on the purist render itself,
among the voices kindred to the slot's tag, its distance reported and `far` said when it is
past `Desample.FAR`; a note's partner is a resonator at the note struck by the pad's own head
(the RING idea from the QUARRY brief, three modes at 1, 2 and 3); the siren's is a band-limited
pulse read along the same trajectory. Both halves are peak-matched before mixing so the share is
a share of level, the pad takes the longer length under the LOOP line, and the blend is gated
again. The provenance carries `made = blend`, `synth`, `synthShare`, `synthDistance`, the purist
`gate` and `gateDetail` kept on record, and `gateAfterBlend`. An unmeasured pad is not blended:
nothing is known to be wrong with it. QUARRY is not yet an engine, so the tonal partner is the
RING bridge the brief's R2 named. Tests: every failed pad and only those are blended, at exactly
half, drum partners name a THUMP or SKIN voice with a distance; blending never fails more gates
than the purist did; a RING partner lands the root on a noise-over-180 Hz source; blends are
deterministic. Measured and kept as a limit: the sine sweep's blended hats still read SNARE to
the classifier with the sweep's own noise held at half, and the pad says so in
`gateAfterBlend`; the cap is the rule, and a louder partner would have been a replacement.

**R0, the recipe (built 2026-11-01).** A derived pad is neither a synth patch nor a captured
pad's chain; its recipe is the third shape: `{"kitFromAnything": {version, slot, genre, root,
scale, sourceFile, sourceHash}}`. The kit folder keeps the source as `kitfrom-source.wav`
beside `kit.json`, and the kit is made from that copy read back, so the recipe's hash is the
hash of the file in the folder. `KitFromAnything.rerender(recipe, source)` rebuilds the pad,
blend included, bit for bit (the assembler is deterministic from these alone), and refuses in
words a source whose hash is not the recipe's. `KitFromAnything.check(dir)` rebuilds every such
pad in a kit and compares the bytes the house writer would produce with the WAV on disk;
`kitfrom --check <kit-dir>` prints it pad by pad and exits 1 on a difference. The shell's
replay door refuses the recipe by name (it belongs to its own source) and `KitDiff` names it.
QUARRY, when it ships as an engine, will need the same kept-source arrangement; the decision
is made here once. Tests: JSON round-trip and a wrong shape named; every pad of a sweep kit
and a pluck kit rebuilt bit for bit, blends included, and no recipe without a kept source; a
swapped source refused; the CLI keeps the source, gives every pad a recipe, `--check` reports
sixteen of sixteen identical, and a halved source is caught.

**Still owed:** real capture fixtures, the pad sheet door (R4), and the listen. Nothing has been
heard.
