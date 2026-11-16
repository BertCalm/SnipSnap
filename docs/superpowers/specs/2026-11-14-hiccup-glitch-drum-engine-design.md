# HICCUP Engine: Engineering Specification

Version 1.0 · 2026-11-14

Status: complete (2026-11-15); built through round 4 (engine, three kits, presets in the factory bank). The Kotlin kits were heard on 2026-11-15 (see the ledger); a second listen the same day covered the 21 presets and the kits' soft and medium zones, and on 2026-11-15 the owner approved hardware playback on a phone and an MPC (the owner's report: "Hardware is good"), and a third listen the same day heard every round-robin take of every hit pad ("Takes are good"; see round 4 as built). This section's original text, written before the Kotlin existed, follows.

Repository truth: read on `origin/claude/mobile-mpc-drum-sampler-t58x74` at `23cde0d1c`. APIs grepped there: `Dsp.seedFor`, `Dsp.Noise`, `Tide.bandLimit`, `Dsp.decimate`, `Dsp.normalize`, `Dsp.fadeTail`, `Scuff.takeFor`, `ScuffPatch`, `ArrangedPad.velocityTakes`, `StarterKits.Starter`, `StarterShelf.GROUPS`, `Velocity.macroSpecsFor`, `Velocity.touchedRender`, `PatchTypes.ROSTERLESS`, `SynthRoot`. SCUFF (`Scuff.kt`, `ScuffPatch.kt`, `ScuffKits.kt`) is the template for every surface this spec does not describe.

## 1. Summary

The characteristic gesture is **hit → snag → ratchet → buzz → cut**.

HICCUP is a drum engine for glitch hop, IDM and hyperpop. A clean synthesized drum hit is written into a short memory. A seeded fault script then re-reads that memory: it snags at a point, repeats a slice, the repeats may shorten until they are a pitch, and every re-read is coarser than the last. A TOY control makes the source itself cheaper (a toy-keyboard or game-console drum), which is the kitsch. Everything is computed; nothing is sampled.

## 2. Goals and scope

Musical goals: drum kits whose glitch is inside each strike, so it follows velocity and changes with each round-robin take, instead of being one printed effect.

V1 includes: eight voices, six macros, velocity and take on the patch, three genre kits with authored ROLL and RISER pads, presets after the listen. V1 excludes: tempo inside `render` (kits lock phrases at their own BPM), HOLD (a drum hit is finite; the percussion gate allows that), stereo, any sampled source.

## 3. Why it earns a pad (sibling check)

| Nearest | What it does | What it cannot do |
|---|---|---|
| SCUFF (`Scuff.kt:37,563`) | GRIT: sample-and-hold plus bit reduction as a fixed colour on clap, rim, hat | change the hit's time structure; no repeats |
| `Crunch.kt`, rack section "crunch" | zero-order hold and bit quantization over a whole pad | the same loss on every sample; no generations |
| `Roll.kt`, `Gate.kt` (keyed treatments) | tempo-keyed re-edit of a finished pad | respond to velocity or take; they repeat the whole pad, not a slice of the strike |
| Cosmic Breaks A09 to A12 | printed glitch samples | be re-rendered, tuned or re-struck |
| THUMP | clean analog-style synth drums | any of the above |

No engine on the default branch does buffer repeat on drums. HICCUP's new thing is a fault script inside the strike.

## 4. Signal flow

```
voice, macros, velocity, take
        │
        ├──────────────▶ [HiccupTake: Latin square over 4 dimensions] ──┐
        ▼                                                               │
[HiccupSource: one clean hit @176.4 kHz]                                ▼
   TUNE DECAY TOY, velocity = contact hardness        [HiccupScript: up to 5 events, drawn once per voice]
        │  (peak-normalized: the memory)                 FAULT admits events by threshold, CHOP sets length
        ▼                                                               │
[HiccupReader: one read-position map over the memory] ◀──(events)───────┘
   per segment: start, length, direction, speed, gain, generation
   CRUSH: hold period and level count per generation
        │ (audio @176.4 kHz)
        ▼
[Output: Tide.bandLimit → Dsp.decimate → DC block → Dsp.normalize(.95) → Dsp.fadeTail]
```

One-way throughout. No feedback path exists, so there is nothing to bound: every output sample is one memory sample times a gain of at most 1, plus at most half a quantizer step.

## 5. The source (HiccupSource)

Eight voices: KICK, SNARE, CLAP, HAT_CLOSED, HAT_OPEN, TOM, BLIP, SQUEAK. The prototype's `source()` is the reference for six of them (its HAT with DECAY low or high is the two hat voices; TOM is the KICK model rooted at 110 Hz with a 2.5× sweep instead of 7×). All root frequencies, sweep depths and decay ranges are "shape".

TUNE moves the root one octave either side. DECAY is the amplitude decay time. Velocity is contact hardness: sweep depth, click level and noise band edges, never level alone.

TOY is one physical idea, the cheapness of the chip, moving three quantities together:

| Quantity | TOY 0 | TOY 1 |
|---|---|---|
| envelope frame clock | continuous | 60 steps per second (pitch and level move in stairs) |
| wave shape `tanh(k·sin)/tanh(k)` | k = 0.05 (a sine) | k = 9 (nearly square, steep harmonic rolloff) |
| noise clock (sample-and-hold noise) | 60 kHz (white) | 3.5 kHz (console hiss) |

## 6. The fault script (HiccupScript)

Five slots per voice. Each slot has six draws in 0..1 (exist, position, length, shrink, reverse, dive) from `Dsp.Noise(Dsp.seedFor("HICCUP", voice.name, "script"))`. **The draws never depend on a macro.** Macros only scale them, so turning a knob never reshuffles the pattern.

```
fe        = FAULT · lin(velocity, 0.55, 1.0)              soft hits stumble less
threshold = (rank of slot by its exist draw + exist draw) / 5
slot plays iff fe > threshold                             FAULT only ever adds events
position  = 0 if u_pos < 0.34 else (u_pos − 0.34)/0.66 · 0.55 · dry seconds
length    = expMap(CHOP, 110 ms, 1.6 ms) · 2^((u_len − 0.5)·1.4)
            below 12 ms: snapped so 1/length is a minor-pentatonic note over the voice root
budget    = lin(fe, 50 ms, 260 ms) · (0.6 + 0.8·u_len)
repeats   = clamp(round(budget / length), 2, 48)
shrink    = 1 − 0.44·(u_shrink − 0.5)·fe if u_shrink > 0.5, else 1   (length · shrink^k, floor 0.6 ms)
reverse   = u_rev < 0.35·fe  (odd repeats read backward)
dive      = u_dive < 0.4·fe  (read speed falls from 1 toward 0.45 across the event)
gain_k    = exp(−elapsed / (1.5 · budget))
```

Position 0 is the ratchet (the attack itself repeats). Events are sorted by position; one that would start inside the previous event's slice is dropped. The reader pauses during repeats and then continues from the end of the slice, so a fault lengthens the hit. Total length is capped at 1.4 s, under the classifier's 1.5 s LOOP line.

Energy: repeats are budgeted in time, not count, so a shorter CHOP gives more repeats of less material, and the per-repeat gain decays over the budget. More events cannot raise the peak.

All constants in this section are "shape".

## 7. The reader and CRUSH (HiccupReader)

The reader builds read positions per segment and interpolates the memory linearly. CRUSH is **loss per re-read**, a memory that degrades each time it is read:

```
depth(g) = 1 − exp(−g / 2.5)            g = generation, 0 for the hit as played
c        = CRUSH · (0.25 + 0.75·depth(g))
hold     = internal rate / expMap(c, 44100, 1100) frames   read position held for this long
levels   = expMap(c, 4096, 5)            y = round(y·levels)/levels, mid-tread
c = 0 bypasses both exactly.
```

Mid-tread quantization has no DC and gates quiet tails shut, which is the "cut". Every segment edge gets a 0.35 ms raised-cosine ramp unless the read is contiguous with the previous segment.

The hold runs at the internal rate, so the staircase's images are made cleanly and then band-limited once. That is the intended character, and it means the house probe "no partial near Nyquist" does not apply as written: a crushed signal is broadband by design. The replacement probe is in §11.

## 8. Macro contract

| Control | Default | Changes which model quantity | Required interaction | Test |
|---|---:|---|---|---|
| TUNE | .50 | source root, and the pitch the short repeats snap to | TUNE × CHOP: buzz stays in key | root within 50 cents on KICK, TOM, BLIP at FAULT 0 |
| DECAY | .50 | source decay time | DECAY × FAULT: a longer memory has more to snag on | length monotonic in DECAY at FAULT 0 |
| TOY | .35 | frame clock, wave squareness, noise clock | TOY × CRUSH: two different cheapnesses, source and memory | spectral distance between TOY 0 and 1 |
| FAULT | .45 | which script events exist | FAULT × velocity | event set at a is a subset of the set at b > a, every voice, 21 steps |
| CHOP | .50 | slice length (stutter down to pitch) | CHOP × TUNE | repeat count rises as CHOP rises at fixed FAULT |
| CRUSH | .35 | hold period and level count per generation | CRUSH × FAULT: more re-reads, more to lose | CRUSH effect at FAULT 1 exceeds its effect at FAULT 0 |

Identity test: at FAULT 0 and CRUSH 0 the output of the reader equals the source bit for bit. No macro shares the engine's name. FAULT also exists on TREMOR and CRUSH on BREAKER with other meanings; macro names are per engine in this repo.

## 9. Velocity and round robin (the "all instruments" gate)

The patch carries `velocity`, `take` and `model`, the `ScuffPatch` shape. Kits deliver a 3 zone × 4 take grid through `ArrangedPad.velocityTakes` (pattern A, as `ScuffKits.build`), which is the path already proven through save, playback, MPC 3, SFZ and DecentSampler by `ScuffKitTest`.

Takes are dealt, not wobbled (the ETCH lesson recorded at `Scuff.kt:29`). Take 0 is the plain render. Takes 1 to 4 deal four strata on four dimensions with a 4×4 Latin square, as `Scuff.takeFor` does, so no two takes share a stratum on any dimension:

| Dimension | What the stratum moves |
|---|---|
| snag position | offsets every non-zero event position by −12%, −4%, +4%, +12% of the dry length |
| slice length | scales every event length by 0.8, 0.93, 1.07, 1.25 (re-snapped when under 12 ms) |
| shrink | adds −0.06, −0.02, +0.02, +0.06 to the shrink draw |
| strike | source contact jitter stratum (sweep time, click level), plus a new noise seed |

The exist draws never move, so every take has the same events as take 0 at the same FAULT. Four takes are one drum stumbling four ways. The prototype nudges all draws by a bounded random amount instead; the Latin square replaces that and has not been prototyped.

Format limit, stated directly: MPC 2 exports velocity layers with each layer's first take only (`Mpc2Limits`, owner's decision of 2026-11-14).

## 10. Kits, roll and riser (the drum and percussion gates)

Three genre kits, 16 pads each, authored after the listen. Layout per `docs/KIT_BEST_PRACTICES.md`: A01 kick, A02 snare, A03 closed hat, A04 open hat (hats in mute group 1), A15 ROLL, A16 RISER (phrases in mute group 2).

| Kit id | BPM | Character (hypotheses) |
|---|---:|---|
| `hiccup-hop` | 98 | glitch hop: deep kick, long CHOP, FAULT moderate, TOY low. The stumble is a swing |
| `hiccup-lab` | 150 | IDM: CHOP high so repeats are pitches, FAULT high on snares and percussion, CRUSH deep |
| `hiccup-sugar` | 160 | hyperpop: TOY high, TUNE up, short DECAY, BLIP and SQUEAK forward |

Distinctness is tested the way `TempestGenreKitsTest` does: at least 12 different pads between any two kits and no shared kick or snare. Kits differ in voices, tuning and roles, not in effect strength alone.

ROLL and RISER are kit phrases of fresh strikes on a sixteenth grid at the kit's BPM, the `ScuffKits` pattern (`ScuffKits.kt:126-139`). ROLL: one bar of snare strikes with FAULT stepping up. RISER: one bar of strikes with CHOP rising from stutter to pitch, so the riser is the engine's own gesture stretched over a bar.

## 11. Numerical and output requirements

- Deterministic: byte-identical renders; `DeterminismTest` canary including a JSON replay of velocity and take.
- Finite and bounded: raw reader peak at most 1 + 1/(2·5) by construction; test asserts under 1.11 at random corners.
- DC: |mean| under 0.005 (mid-tread quantizer; measured 1.5e-5 worst in the prototype).
- Level: `Dsp.normalize` to 0.95 peak, the drum peak convention (`Rivet.kt:187`, SCUFF).
- Aliasing: the source alone (FAULT 0, CRUSH 0) passes the house near-Nyquist probe. For crushed renders the probe is decimation fold: render the reader at 8× and at 4×, compare after the output stage, difference under −40 dB.
- Length: at most 1.4 s plus the pad tail; `drumClassFor` returns the voice's class and a classifier test checks KICK, SNARE, HAT_CLOSED, HAT_OPEN, CLAP and TOM at default macros. Risk: a ratcheted kick may be filed differently; if so the test narrows to FAULT at or below its default and the limit is disclosed.
- Scramble in bounds, unknown macro keys dropped, non-finite macro falls back to its default.

## 12. Render budget

Python prototype: 30 ms per voice (NumPy, a 2021 laptop class machine). The work is one source pass and one interpolated read over at most 1.4 s at 176.4 kHz, about 250k frames, with no solver. Target in Kotlin: under 30 ms per render on the JVM. No cache is needed.

## 13. Integration

Rows from the SCUFF commits (e387545b4, 7d5ade512, 34c1b3166, e5d83adc6):

R1: `Hiccup.kt`, `HiccupPatch.kt`, `Patches.fromJsonValue` arm, `Velocity.macroSpecsFor` and `touchedRender` arms, `SynthRoot` arm (or `SynthRootTest` ROOTLESS), `UserPresets` arm if the compiler asks, `PatchTypes.ROSTERLESS`, `PatchesTest.onePatchPerEngine`, `PadRecipeTest`, `DeterminismTest`, `HiccupTest.kt`.

Later rounds: `HiccupKits.kt`, `StarterKits.ALL` entries, `StarterShelf` SKINS group, `StarterKitsTest` lists, `HiccupPresets.kt`, generator tasks, `docs/HICCUP.md`, roadmap row, gates section, README counts, `EngineCatalog` plus the app picker (SOOT's commit 5be08feac is the template; needs an Android SDK session).

## 14. Build rounds and gates

- R0 (done): Python prototype, nine clips, three questions.
- R1: engine, patch, registration arms, `HiccupTest`. Rosterless. Gate: the owner hears the Kotlin voices.
- R2: three kits with velocity and take grids, ROLL and RISER, shelf entries, `HiccupKitsTest`, shell `HiccupKitTest` (save, reload, playback order, MPC 3 export read-back). Gate: the owner hears the kits.
- R3: presets, docs, catalog and picker, gates section with a status per gate.

## 15. Sonic risks

1. The ratchet reads as a machine-gun at high FAULT on a kick. Probe: event count and per-event gain; fix is the gain law, not a limiter.
2. TOY 1 on hats is only hiss. Probe: centroid and flatness against TOY 0.
3. The 0.35 ms edges tick louder than the material on quiet tails. Probe: peak of the edge window against the segment RMS.
4. Pitch-snapped buzz fights the kit's key. The snap follows TUNE, so a kit sets TUNE per pad; disclosed.
5. Takes sound like four patterns, not one drum. That is audition question 3.

## 16. Verified / proposed / heard ledger

| Claim | Status | Evidence |
|---|---|---|
| FAULT 0 with CRUSH 0 is the dry hit exactly | measured (Python harness, not Kotlin) | max abs error 1.1e-16, six voices, `docs/prototypes/hiccup/report.json` |
| FAULT only adds events | measured (Python) | subset check true over 21 steps × six voices |
| Deterministic | measured (Python) | two renders equal |
| Bounded and DC-free at corners | measured (Python) | raw peak 1.002, 72 random corners finite, DC under 0.01 |
| Every macro moves the sound | measured (Python) | 1.1 to 28 dB log-spectral distance; lowest is KICK DECAY, which changes length more than spectrum |
| CRUSH matters more with re-reads | measured (Python) | 6.7 dB at FAULT 0, 8.0 dB at FAULT 1 on SNARE |
| Velocity changes the strike | measured (Python) | leveled KICK centroid 133, 178, 228, 250 Hz at velocity .2, .5, .8, 1 |
| Latin-square takes | proposed | not prototyped; the prototype uses bounded nudges |
| TOM voice, split hat voices | proposed | not prototyped as separate voices |
| Kit characters, all constants | proposed, "shape" | none |
| The drum classifier files ratcheted hits correctly | not verified | needs the Kotlin `Classifier` |
| Any of it sounds good | heard (2026-11-15) | The owner listened to an audition page of the Kotlin engine (model 2, kit 2): a four-bar groove from each of the three kits, every hit pad A01 to A14 of each kit at the hardest velocity zone, each kit's roll and riser, and the four round-robin takes of SUGAR's Pop Snare. Verdict, verbatim: "sounds check passes". |
| The 21 presets sound good | heard (2026-11-15) | Filed in the factory bank after the kit listen (round 4), then heard in a second listen the same day: all 21, each played twice at velocity 1, take 0. Verdict, verbatim: "Everything sounds good". |
| The softer velocity zones sound good | heard (2026-11-15) | The second listen played every hit pad A01 to A14 of each kit soft, medium and hard (velocities .30, .65, 1), first take of each zone, at the same peak level as saved. Verdict, verbatim: "Everything sounds good". The second to fourth takes of those zones were heard in the third listen (next row). |
| Every round-robin take sounds like the same drum, and the four differ | heard (2026-11-15) | The third listen played, for each kit and each zone (soft .30, medium .65, hard 1), every hit pad A01 to A14 with its four takes back to back in dealt order (nine clips; model 2, kit 2). Verdict, verbatim: "Takes are good". |
| Hardware (phone, MPC) playback | approved by the owner (2026-11-15) | The owner said, verbatim: "Hardware is good". This is the owner's report, not a capture. MPC 2 keeps one take per zone (stated format limit). |

## 17. Why it is built this way

1. **The script is drawn once and the knobs only scale it.** If the random draws depended on the knob values, a tiny turn of FAULT would give a completely different stutter. Fixing the draws means FAULT at 40 is FAULT at 30 plus one more event, so the knob can be learned.
2. **CRUSH is tied to re-reads, not laid over the whole pad.** The attack stays punchy because the first pass is nearly clean; the damage accumulates on the repeats. Remove that and it is the rack's crunch section, which already exists.
3. **Short repeats snap to a note.** Below about 12 ms the ear stops hearing repeats and hears a pitch. Leaving that pitch to chance makes kits sound out of tune; snapping it to the voice's root makes the buzz playable.
4. **TOY changes the source, not the memory.** Kitsch here means a cheap instrument (stair-stepped sweeps, square waves, console noise) that then breaks. Two separate cheapnesses give more range than one.
5. **The risky decision is the take dealing.** Round robin must be distinct strikes, and a glitch that changes too much per take stops being one drum. The exist draws are frozen across takes for that reason; the listen decides whether the four strata are too wide.

## As built

Round 1, 2026-11-14. Nothing was heard.

**Built.** `Hiccup.kt` (source, script and reader as internal functions of `object Hiccup`, plus takes and the output stage), `HiccupPatch.kt` (the `ScuffPatch` shape, `ENGINE = "HICCUP"`, model 1), registration arms in `Patches.fromJsonValue`, `Velocity.macroSpecsFor`, `Velocity.atVelocity` (touched render), `SynthRoot.kt` (rootless arm), `PatchTypes.ROSTERLESS`, hand-built entries in `PatchesTest`, `PadRecipeTest` and `SynthRootTest` (and its ROOTLESS set), a `DeterminismTest` canary with JSON replay of velocity and take for takes 0 to 4, and `HiccupTest.kt`. No kits, presets, shelf entries, catalog or picker.

**Deviations from the spec or prototype.**
1. Takes are the Latin square of section 9 (snag offsets -12/-4/+4/+12 percent of dry length, slice scales .8/.93/1.07/1.25, shrink adds -.06/-.02/+.02/+.06, strike stratum with a 0.15 jitter and a new noise seed). The prototype's random nudges are gone. The strike stratum also drives the small per-voice wobbles the prototype drew from its jitter generator.
2. Overlap settling in the script is by slot rank on take-neutral geometry, not by position as section 6 says, so the slot set is monotone in FAULT and identical across takes by construction. A take's offsets then push a later event past an earlier slice and clamp it inside the memory; no event is dropped by a take.
3. Filters: the prototype's Butterworth band-pass and high-pass are a 12 dB per octave high-pass (two `Dsp.FixedOnePole` in series) followed by a `Dsp.TptSvf` Butterworth low-pass. These run in Float inside `Dsp`; the rest of the synthesis is Double.
4. Reader output is in the memory's own scale (the quantizer sees it scaled by the memory peak), so the FAULT 0 plus CRUSH 0 identity holds bit for bit without a renormalize. The source's last 10 ms are faded on a raised cosine, and 10 ms of silence are appended before band-limiting. Dive speed is floored at 0.45 (the prototype floored at 0.3). The kick click is filtered over its first 30 ms only. `tanh` is computed from one `exp`.
5. HAT_CLOSED DECAY maps to a 14 to 80 ms time constant, HAT_OPEN to the prototype's 12 to 300 ms. These ranges are "shape".
6. The "CRUSH matters more at FAULT 1" test asserts on the relative RMS change, not on a spectral distance (see the numbers).

**Measured.** All from `HiccupTest`, printed with `--info`.
- Worst raw reader peak over memory peak across 64 random corners (8 voices, 8 each): 1.0007 (bound asserted 1.11).
- Mean ms per default render, 10 runs after 3 warm-ups, run alongside other test classes in one JVM: KICK 34.2, SNARE 34.4, CLAP 31.5, HAT_CLOSED 8.9, HAT_OPEN 62.4, TOM 21.9, BLIP 18.2, SQUEAK 19.2. A solo run of the Hiccup class alone gave KICK 10.4, SNARE 11.6, CLAP 10.2, HAT_CLOSED 3.9, HAT_OPEN 20.5, TOM 11.1, BLIP 11.4, SQUEAK 6.5. The section 12 target of 30 ms holds in the solo run.
- Classifier: round 1 measured SNARE correct and the rest not at the defaults; Phase A below supersedes it.
- Level-matched third-octave shape distance, velocity .3 against 1 (dB), after the Phase A retune: KICK 4.92, SNARE 2.78, CLAP 1.55, HAT_CLOSED 2.81, HAT_OPEN 0.82, TOM 1.78, BLIP 8.02, SQUEAK 4.92. The test floor is 0.3 dB (round 1 values before the retune were KICK 5.78, CLAP 1.89, HAT_CLOSED 3.87, HAT_OPEN 0.64, TOM 3.29).
- CRUSH 0 against .8 on SNARE: relative RMS change 0.767 at FAULT 0 and 1.026 at FAULT 1 (asserted). The third-octave shape distance did not show the ordering: 9.85 dB at FAULT 0 and 8.44 dB at FAULT 1 (40 Hz to 10 kHz). The prototype reported 6.7 and 8.0 dB under a measure that is not in this repository, so the two are not comparable.
- Total repeats at FAULT .8 for CHOP 0, .25, .5, .75, 1: KICK 4, 17, 45, 138, 144; SNARE 6, 18, 52, 137, 144; CLAP 4, 17, 65, 180, 192; HAT_CLOSED 2, 6, 16, 92, 144; HAT_OPEN 6, 23, 64, 187, 192; TOM 6, 18, 64, 183, 192; BLIP 4, 22, 62, 176, 192; SQUEAK 2, 11, 32, 92, 96. The counts are summed over events and saturate at 48 per event.
- Passing in `HiccupTest`: determinism, finite and bounded, DC under 0.005, length under 1.5 s, identity at the unit level (three velocity and take pairs per voice), FAULT subset over 21 steps at CHOP 0, .5 and 1, equal slot sets across takes at 21 FAULT steps, every macro changes the render, unknown key and non-finite macro, scramble, pairwise different takes, JSON round trip.

**Not verified.** Any sound. The decimation-fold probe of section 11 (8x against 4x, under -40 dB) and the near-Nyquist probe on the source alone were not written. Spectral centroid and flatness probes for TOY on hats, the edge-tick probe and the per-event gain probe of section 15 were not run. Whether the take strata are too wide for one drum is untested. Render time under a quiet machine beyond the solo run above. The `UserPresets` promotion path has no HICCUP arm (no presets exist). The full test suite was not run.

**Not heard (as of round 1; the Kotlin kits were heard on 2026-11-15, see the ledger).**

### Phase A: classifier diagnosis, 2026-11-14

The classifier (`Classifier.kt`) decides from the first 4096 samples of the pad: the low-band share above 0.55 means the bass family (KICK if centroid under 100 Hz, or under 130 Hz with low share above 0.85; otherwise TOM); otherwise a hat needs centroid above 12 kHz and high-band share above 0.97 (closed under 120 ms decay); a CLAP needs flatness above 0.35 and three attack bursts in the first 80 ms; a SNARE needs high share above 0.5 and flatness at least 0.25; the rest is PERC.

Verdicts per voice (test `HiccupTest` "classifier diagnosis table", printed with `--info`), after the retune below:

| Voice | (a) dry: FAULT 0, CRUSH 0, TOY 0 | (b) FAULT 0, default TOY and CRUSH | (c) defaults | (d) FAULT 1 |
|---|---|---|---|---|
| KICK | KICK | KICK | PERC | PERC |
| SNARE | SNARE | SNARE | SNARE | SNARE |
| HAT_CLOSED | HAT_CLOSED | SNARE | SNARE | SNARE |
| HAT_OPEN | HAT_OPEN | SNARE | SNARE | SNARE |
| CLAP | CLAP | CLAP | CLAP | CLAP |
| TOM | TOM | TOM | PERC | PERC |

Before the retune the dry column was already correct for all six; (b) was wrong for KICK (TOM; centroid 109 Hz, low share 0.82, under the 0.85 stretch rule), both hats (SNARE) and CLAP (SNARE; flatness 0.329). The source is shaped at the default TOY, so the retune targeted (b), not only (a).

Reference, the same features on the house voices at defaults: THUMP KICK, SNARE, HAT_CLOSED, HAT_OPEN, CLAP and TOM all file as their own class (hats centroid 12677 and 12409 Hz, high share 0.982, flatness 0.26 and 0.17). SCUFF KICK files PERC (centroid 129 Hz, low share 0.52), SCUFF HAT files SNARE (centroid 6600 Hz) and SCUFF CLAP files CLAP. So the classifier can file a house-style hat and kick; SCUFF's own do not.

Source constants changed (all "shape"), with before and after of the features at (b):
- KICK and TOM click: gain 0.5 to 0.15, band 1500 to 9000 Hz to 1200 to 5000 Hz. KICK sweep depth 7 to 5 (TOM stays 2.5). Chip wave squareness `toy * 0.8` to `toy * 0.5`. KICK (b): TOM, centroid 109 Hz, low 0.819 before; KICK, centroid 92 Hz, low 0.907 after. TOM (b): TOM, centroid 157 Hz before and after; TOM (c) still files PERC.
- CLAP band 800 to 3600 Hz (times TUNE) to 600 to 5500 Hz. CLAP (b): SNARE, flatness 0.329 before; CLAP, flatness 0.404 after (default TOY with CRUSH 0 alone reads flatness 0.301, filed SNARE).
- HAT (both): metal high-pass 6000 to 8500 Hz, hiss high-pass 7000 to 11000 Hz, mix 0.7 metal and 0.5 hiss to 1.0 metal and 0.35 hiss, the hiss noise clock moves 0.3 as far with TOY, and the memory is low-passed at 18 kHz before CRUSH. (a) HAT_CLOSED centroid 12612 Hz before, 12187 Hz after; HAT_OPEN 13136 Hz before, 12705 Hz after, so the dry margin over the 12 kHz line is small. With CRUSH 0 and default TOY both hats file as hats (centroid 12404 and 12226 Hz). At default CRUSH, (b) is still SNARE: centroid 11280 and 11318 Hz, high share 0.957 and 0.958, under the 0.97 rule.

Found and not fixed: the hats misfile at the default CRUSH. The generation-0 hold at about 32 kHz folds the memory's remaining content and the quantizer's noise into the low band, which takes the high-band share under 0.97. An integer hold length was tried and made it worse (centroid 10526 Hz); it was reverted. The retune did not change `Classifier.kt`, the FAULT, CHOP or CRUSH defaults, or the identity. The classifier assertion is therefore: every drum voice dry; KICK, SNARE, CLAP and TOM at (b); both hats at FAULT 0 and CRUSH 0. Columns (c) and (d) are a disclosed limit: a hit with the default fault or any crush can file differently, and kits assign a pad's class by voice.

Reader refactor for the fold probe: the rate-dependent code (source, reader) moved into `Hiccup.Core(fs)`, with the engine using `Core(176400)`; audio at the default rate is unchanged by the move. `renderPad` takes an `oversample` argument, used only by the probe.

Aliasing probes (`HiccupTest`):
- Near-Nyquist, dry source (FAULT 0, CRUSH 0), power at or above 20 kHz as dB of total at TOY 0, .35, 1. Non-hat voices: worst -31.3 dB (SNARE, TOY 0); KICK, TOM -142 to -77; BLIP -82 to -73; SQUEAK -101 to -50; CLAP -58 to -38. Hats: HAT_CLOSED -18.3, -19.7, -26.4; HAT_OPEN -19.1, -20.0, -26.9. Asserted under -25 dB for non-hats and under -15 dB for hats; the hats are bright by design.
- Decimation fold, crushed (FAULT .8, CRUSH 1), the 8x internal render against the 4x one after the output stage, as dB of the 4x RMS: KICK -20.5, SNARE -3.2, CLAP -2.2, HAT_CLOSED -5.9, HAT_OPEN -9.4, TOM -17.9, BLIP -13.8, SQUEAK -3.6. None is under the spec's -40 dB, so nothing is asserted beyond finiteness, and the figure is disclosed. The two renders also differ by up to half an internal sample in event positions and hold phase, which this measure does not remove, so it overstates the fold; how much it overstates was not separated.

### Round 2: kits, 2026-11-14

Built: `HiccupKits.kt` (three kits, `VERSION` 1), `HiccupKitsTest.kt`, `shell/.../HiccupKitTest.kt`, `HiccupKitGenerator.kt` with the `generateHiccupKits` task and the `.gitignore` line, three `StarterKits.Starter` entries and the SKINS group ids, `StarterKitsTest` additions (ignore lists, pinned ids, per-engine zone assertions), README starter counts 125 to 128 (`StarterKits.ALL.size`, enforced by `ConventionTest`), and the HICCUP section of `docs/ENGINE_ACCEPTANCE_GATES.md`. No presets, `docs/HICCUP.md`, roadmap row, catalog or picker.

Pads are as authored in the round-2 brief (levels, TUNE, DECAY, TOY, FAULT, CHOP, CRUSH). Hits are `Hiccup.render` strikes at velocities .30, .65, 1 and grid take t = engine take t + 1, scaled to the sampler gain law of `ScuffKits` (every zone's first take peaks at 0.92). The roll strikes the kit's A02 settings on a 16-note bar (eighths, sixteenths, thirty-seconds) with FAULT from 0 to the pad's value and velocity from .45 to 1. The riser strikes A05 (HOP) or A10 (LAB, SUGAR) on 16 sixteenths with CHOP from .15 to 1, TUNE up .20 and velocity .5 to 1. Phrases take engine take (take + note index) mod 4, plus one. Phrases are classed LOOP, as SCUFF classes its own.

Measured (`HiccupKitsTest`, run with `--info`):
- Pads of 16 whose hard first take differs between kit pairs: HOP/LAB 16, HOP/SUGAR 16, LAB/SUGAR 16; no shared kick or snare (the floor asserted is 12).
- Smallest peak-normalized soft-against-hard distance over all pads: 0.307 (floor asserted 0.05). Smallest take-against-take distance in a zone: 0.602 (floor asserted 0.005).
- Worst first-take DC offset over 144 zone renders: 1.4e-5. Hit pads are under 1.5 s; phrase pad lengths in seconds: HOP 2.949 and 2.949, LAB 2.100 and 2.100, SUGAR 2.000 and 1.978 (the bar plus half a second at most, as `ScuffKits` sizes a one-bar phrase).
- `:shell:generateHiccupKits` verified all three kits: 16 pads and 192 slices each, saved PCM, playback and the MPC 3 track read back. Output under `testkit/hiccup-kits/` (gitignored): 229 MB in total (HOP 85 MB, LAB 76 MB, SUGAR 67 MB), per kit `pad-NN.wav`, `touch-NN.wav` strips for the 14 hits, `kit-source.json`, `native/` and `mpc/<kit>_[TrackData]`; plus `manifest.json` and `verification.csv`. No SFZ or DecentSampler files are written (SCUFF's generator writes none either).
- `HiccupKitTest` runs the save, reload, `TriadKitProof.verify`, MPC 3 export and `verifyTrack` proof for all three kits on four pads each (kick, closed hat, roll, riser), the playback order and repeat check, and the shelf check for all three.

Deviations from the round-2 instructions: mute group 1 is set on A03 and A04 only, as written, so the closed hat A14 "Dust Hat" (HOP) and the open hat A14 "Static" (LAB) are in no mute group. Starter blurbs are longer than the three quoted phrases, which each lead their blurb, and in SCUFF's style. The generator does not write SFZ or DecentSampler.

Not verified: any sound; hardware playback; MPC 2 (stated format limit); that the hit pads' classifier verdicts match their assigned classes (assigned by voice); whether the kits are distinct to the ear, only that their renders differ.

### As built: R3 groove fixes, 2026-11-15

A 160 BPM hyperpop groove made from the round 2 kit was called incoherent. Four causes were measured in the Python prototype (`docs/prototypes/hiccup/hiccup_groove_v2.py` and `hiccup_groove_v2_run.py`) before the engine was touched:
1. The buzz ate the attack. An at-attack event with a 1 to 5 ms slice cut the transient; the correlation of the first 15 ms of the wet render against the dry one was 0.19 to 0.72.
2. Each voice snapped its buzz pitch to a pentatonic scale over its own root, so five drums buzzed in five keys.
3. Slice lengths had random ratios (x2^((u-.5)*1.4)) and the stumble time was in milliseconds unrelated to anything, so nothing landed on a grid.
4. Total inserted time per hit was unbounded; the kick reached 13 sixteenths at 160 BPM.

The fixes, in `Hiccup.kt` (`MODEL_VERSION` 1 to 2, so saved model 1 patches are refused as with any bump):
1. Attack guard, `GUARD_SECONDS` = 18 ms. An at-attack event (position draw under .34) is placed at max(0, 18 ms minus its slice length), so it ends at the guard or later and the first 18 ms play as the dry hit.
2. One key, `KEY_HZ` = 55. Slices under 12 ms snap to the minor pentatonic over 55 Hz, in any octave. The snap function is unchanged; only its root is. Neither the voice root nor TUNE is used by the script any more.
3. Grid. A slice is the CHOP slice times 2^round((u_len - .5) * 2), that is x1/2, x1 or x2, then pitch-snapped. The grid unit g is the CHOP slice doubled or halved into [40 ms, 80 ms). An event's budget is a whole number of units, at least ceil(length / g). Non-attack positions round to multiples of g/2, at least g/2. The re-read fills the budget exactly: repeats shrink as before until the budget is used and the last is cut to fit (`repeatLengths`, shared by the script and the reader, so `Event.repeats` is what is played). `Hiccup.chopFor(seconds)` is the inverse of the CHOP slice map (110 ms to 1.6 ms, exponential), clamped to 0..1.
4. Cap. Total stumble per hit is max(1, round(FAULT * velocity weight * 6)) units.

Differences from the prototype:
- Admission is by rank on the take-neutral geometry, as in round 1, not by position as in `hiccup_groove_v2.script`. The prototype spends the cap while it walks the events, and an early event that wants more units as FAULT rises can starve a later one that a lower FAULT admitted, which breaks the rule that FAULT only adds events. So a slot is admitted while the sum of its smallest costs (its slice in whole units, independent of FAULT) fits the cap, and the first admitted slot is always admitted. The units are then dealt in rank order, each slot getting what it wants (at least one) from what the cap leaves. The slot set is monotone in FAULT and the same across takes.
- Because the take changes the slice, an event's budget is max(its grant, its slice in whole units) at the take stage. The sum of budgets can therefore exceed the cap by (slice units - 1) per event; the grid test states that allowance. At FAULT .2 on a CHOP 0 kick it reached 4 units against a cap of 1.
- Round-robin takes: the Latin square's slice stratum now adds -.40, -.15, .15 or .40 to u_len before the rounding, instead of scaling the length by .8 to 1.25. 144 of 328 sampled (slot, take) lengths differ from take 0. Take 0 adds 0. A take that would push an event past the end of the memory falls back to the take-neutral length, then halves it (re-snapped) until it fits.
- Repeats are rounded on the running total of samples, so a long run of repeats does not drift off the budget. The dive speed floor stays at .45 as in the Kotlin round 1 reader.

Kits (`HiccupKits.VERSION` 1 to 2): every pad's CHOP is `div(kit, n)`, the slice of one 1/n note at the kit's BPM, n the power of two from 4 to 512 nearest in log terms to the slice the old literal gave. At LAB and SUGAR several literals were faster than 1/512 reaches; those pads sit at 1/512 (3.1 ms at 150 BPM, 2.9 ms at 160). The backbone (kick, snare, closed hat, clap) has lower FAULT: HOP .20, .30, .10, .25; LAB .30, .40, .15, .35; SUGAR .15, .30, .10, .20. SUGAR Candy Kick DECAY .65 to .45. The roll phrase sweeps FAULT up to the snare's value, so the three rolls are also calmer. No backbone pad needed a further DECAY change.

Measured (`HiccupTest`, `HiccupKitsTest`):
- Attack kept, first 15 ms wet against the FAULT 0 render of the same take, FAULT 1, CHOP 1, velocity 1, takes 0 to 4: minimum .994 (SQUEAK); every other voice .999 or better.
- Buzz pitch: 1365 events under 12 ms over all voices, CHOP, TUNE and takes, all within 1e-12 cents of a degree over 55 Hz.
- Longest backbone pad (pads 1, 2, 3, 5, top velocity, four takes) to -30 dB below peak, in sixteenths: HOP 4.53, LAB 4.89, SUGAR 5.64 (limit 6).
- Smallest take-against-take distance in a zone .652 (was .602); smallest soft-against-hard distance .205 (was .307); floors .005 and .05 unchanged.
- Total repeats at FAULT .8, CHOP 0 to 1 in quarters: KICK 2, 14, 89, 379, 378. The count is no longer strictly monotone in CHOP (the budget jumps by an octave with the unit); `repeat count at fixed FAULT rises as CHOP rises` now allows a fall of up to 15% between steps (worst measured: TOM 381 to 352) and still asserts the last count exceeds the first.

Not verified at round 3: any sound. Nobody had yet heard the Kotlin output. The 2026-11-15 listen (see the ledger) included a four-bar groove from each kit and the verdict "sounds check passes"; it was not a test of the earlier "incoherent" complaint against a recorded baseline, and the presets and hardware were unheard at that point (the presets were heard later the same day, see round 4 as built).

## Round 4 as built, 2026-11-15

The owner listened to an audition page of the Kotlin engine (model 2, kit 2): a four-bar groove from each of the three kits, every hit pad A01 to A14 of each kit at the hardest velocity zone, each kit's roll and riser, and the four round-robin takes of SUGAR's Pop Snare. Verdict, verbatim: "sounds check passes". The 21 presets were not on that page. A second audition page the same day (model 2, kit 2) held all 21 presets, each played twice at velocity 1, take 0 (the picker's render), and, for each kit, every hit pad A01 to A14 struck soft, medium and hard (velocities .30, .65, 1), first take of each zone, at the same peak level as saved. Verdict, verbatim: "Everything sounds good". Neither of those two listens covered every take (the second listen played the first take of each zone) or hardware. The picker's on-device test passed in CI's emulator job on PR #1143 on 2026-11-15. Later on 2026-11-15, told that hardware playback on a phone and an MPC was the remaining gate, the owner said, verbatim: "Hardware is good". This is the owner's report, not a capture. MPC 2 still keeps one take per zone (stated format limit). On 2026-11-15 the owner listened to a third audition page of the Kotlin engine (model 2, kit 2): for each of the three kits and each velocity zone (soft .30, medium .65, hard 1), every hit pad A01 to A14 with its four round-robin takes played back to back in the order the pad deals them (nine clips). The question asked was whether any take sounded like a different drum, or four takes sounded the same. Verdict, verbatim: "Takes are good". With that, every take of every hit pad has been heard. Nothing is open: HICCUP is complete as of 2026-11-15, accepted sound `Hiccup.MODEL_VERSION` 2 with `HiccupKits.VERSION` 2.

Done: `Presets.all()` and `Presets.forVoice` serve `HiccupPresets`; HICCUP left `PatchTypes.PROTOTYPES` (SCUFF's pattern); a saved strike promotes through `HiccupPatch.fromJsonText` so velocity, take and model survive. The "shape" constants were not changed.
