# FORGE: a hydroforming transient section. Design brief

**Status:** concept, prototyped in Python (`2026-10-25-forge-section-prototype.py`, beside this file). Nothing rendered in Kotlin, nothing heard.
**Source idea:** the owner's XO_HYDROFORM design specification (explosive hydroforming as a transient synthesis paradigm: a transmission medium, a charge at a standoff, strain-rate dependent plasticity, die arrest). This brief adapts it to SnipSnap; it does not reproduce it.
**Repository truth:** commit `ad2439e` (2026-10-25). APIs grepped and read: `Valve.process`/`Valve.upsample` (`Valve.kt:153-197`, the rack's only oversampled section), `Tide.bandLimit`, `Dsp.decimate`, `Dsp.Biquad.allpass` (`Dsp.kt:425`), `Dsp.seedFor`, `Dsp.Noise`, `Dsp.limitPeak`, `Dsp.levelTo`, `FxChain.SECTIONS` (`FxChain.kt:196`), `Squash`, `Spike`, `Compressor`, `Punch`, `Onset.render`, `Thump.render`, `Tide.fold` (`Tide.kt:357`). No slew-dependent transfer curve exists anywhere in `synth/`; no section converts clipped energy into noise.

**The characteristic gesture is: hit → spread through the medium (a sub-millisecond chirp) → the fast part flows and folds, the slow part fractures → coined flat against the die, with a flash of heat.**

## What it is

A per-pad processing stage for drum one-shots and metallic attacks. It takes a finished pad (a synthesized drum or a captured hit), adds a low shock at a tiny standoff, spreads the attack through a chain of all-pass filters so a click becomes a short chirp, drives the fast-moving part of the signal into a wavefolder while the slow-moving part meets a rigid clip, and ends in a hard ceiling that turns whatever it removes into a short burst of high-frequency noise. There is no gain envelope anywhere: no attack time, no release, so nothing pumps and nothing recovers. The output is peak-matched to the input, like every rack section, so the stage changes the shape of the hit and never its level.

## The brief, translated

| Brief says | Status here | What the design does |
|---|---|---|
| Water as an incompressible medium: an all-pass matrix "aligns and concentrates the transient's phase energy" | **Corrected (measured).** An all-pass chain does the opposite: it spreads a transient in time. A 0.2 ms click came out 1.6 ms long after 2 sections, 4.4 ms after 8, 12.5 ms after 24, with its peak down to 0.20. Offline, the exact inverse exists (reverse, filter, reverse: identity to 5e-14) but using it after the folder throws the folder's new harmonics away (see finding 2). | Analogy. The medium is a **chirp maker**: the click becomes a short rising sweep, and a sweep through a folder makes far denser harmonics than a click does. CHARGE sets the chain length. |
| Charge mass: "an RK4-integrated exponential decay" | Simplified. An exponential decay has a closed form; a solver buys nothing. | A pressure front (0.1 ms rise) into a 48 Hz decaying sine, amplitude from CHARGE. |
| Standoff: a sub-millisecond delay that comb-filters the attack | **Partly holds (measured).** The delay changes the attack's spectrum by 6.4 to 6.9 dB at every step, but no comb notch at 1/(2·delay) registers at the output, because the folder scrambles the interference pattern. A delayed copy of the pad's own attack did not comb either, for the same reason. | Analogy. STANDOFF is an attack-colour control, not a tunable comb. If a pitched "punch" comb is wanted, it has to happen after the nonlinearity; that is a different section. |
| Hyperplastic wavefolding: fast signals flow, slow ones fracture; folding "avoids aliasing" | **Physics is an analogy, the aliasing claim is inverted.** Most metals get stronger and more brittle at high strain rate, not softer. And folding aliases harder than clipping, not less (catalog §3); measured below. | Analogy, kept because it makes a good instrument: the slew-rate state decides the transfer curve **and the drive**. A curve switch alone did nothing on a kick (finding 4). |
| Die arrest: "zero-release, infinite-ratio" limiter; the excess becomes "thermal noise" | A zero-release infinite-ratio limiter is a clipper. The thermal burst is new and cheap. | Literal in the DSP sense: a clamp whose ceiling closes and hardens with ARREST, plus a seeded, band-limited noise burst driven by the clipped-off excess. |
| "Massively loud, mathematically flat peak" | Level is the house's job (`Dsp.levelTo`, kit balance), never a section's. | What the stage controls is the **crest factor** (peak over RMS). Measured: 4.87 at ARREST 0 down to 4.07 at ARREST 1. Lower crest means louder at the same peak once the kit levels it. |
| "Velocity-dependent waveshaping" | Means signal slew (dx/dt), not pad velocity. | The rack has no velocity. An engine landing could map pad velocity to YIELD; see Alternate A. |
| "Bypassing time-based modulations suited for loop-based content" | Matches the house: offline one-shots, no clocks. | Nothing to adapt. |

Not simulated: fluid pressure, plastic deformation, heat. The names stay because they tell the player what the knobs do.

## Why it earns a slot (sibling check)

The nearest things in the rack and the drum engines, and the one thing each cannot do:

- **SQUASH** (`Squash.kt`): a punch compressor with a 2 ms lookahead and a 90 ms release. It shapes loudness over time with a gain envelope; it cannot change the transfer curve by how fast the signal moves, and its release is exactly the "springback" the brief wants rid of.
- **SPIKE** (`Spike.kt`): fast and slow envelope followers, the gap gained up. A transient *booster*: linear in level, no harmonics, no ceiling.
- **COMPRESSOR** (`Compressor.kt`): the sustain compressor; the opposite job.
- **VALVE** and **CRUNCH**: saturation and converter grit over the whole buffer, memoryless; neither reads slew, neither has a shock or a medium.
- **PUNCH** (`Punch.kt`, internal to THUMP, SKIN, ONSET): soft saturation and a boost confined to a fixed 0.5 ms onset window. Closest in spirit (a fixed "the attack is at t=0" assumption, like this design). It is `internal`, one amount, no fold, no ceiling.
- **ONSET**'s SNAP and EDGE, **THUMP**'s PUNCH: per-engine attack controls; captured samples never reach them.

None of them has a transfer curve that depends on the signal's speed, a chirp stage before a folder, or a clipper that turns its excess into noise. And none of them can touch a captured hit the way a rack section can.

## Landing: a rack section, not an engine

The brief's own words decide it: the input is "either a synthesized impulse or a raw percussion sample." Only a rack section reaches both. An engine (26 integration surfaces, an audition gate, a 16-pad kit) would compete with THUMP, ONSET and SKIN for a drum pad and could never process a chop. The gesture is a *processing* gesture (what happens to a hit), which is what a section is for.

Working name **FORGE** (a plain word; not in `PresetTestSupport.trademarkBlocklist`, not used by any engine, section or macro at `ad2439e`). Macros **CHARGE, STANDOFF, YIELD, ARREST**, the owner's four, unchanged.

Placement: after SQUASH, before VALVE. Dynamics first, the forming stage, then the amp colours the formed blank. The alternative (after VALVE, as a final ceiling) is a listening question; the rack's fixed order means it is decided once.

The section runs at 4× internally the way VALVE does (`Valve.upsample` → stage → `Tide.bandLimit` → `Dsp.decimate`, `Valve.kt:162-170`), because two of its four stages make new harmonics.

## Signal flow

```
pad in (44.1 kHz, peak-normalised)
   │
   ▼
[upsample 4x: Valve.upsample pattern]                                            @176.4 kHz from here
   │
   ├──────────────────────────────────────────────┐
   ▼                                              │
[CHARGE: shock = 0.1 ms front → 48 Hz decaying sine, amp 0..1.2 × peak]
[STANDOFF: the shock lands 0..1 ms after t = 0]  ─┘  (sum, re-normalise)
   │
   ▼
[Medium: N second-order all-passes, N = 0..24 from CHARGE, centres 300 Hz..6 kHz log-spaced, Q 0.7]
   │   (Dsp.Biquad.allpass; re-normalise: the medium spreads the pulse but delivers all of its energy)
   ▼
[Slew detector: |Δx|·rate, one-pole 2 kHz, threshold = YIELD-fraction of THIS pad's peak slew]──▶ p(t) in 0..1
   │                                                                                             │
   ▼                                                                                             ▼
[Hyperplastic shaper: (1 − p)·clip(1.3·x)  +  p·fold(1.3·(1 + 3p)·x)]      (triangle folder, Tide.fold family)
   │
   ▼
[Die: ceiling c = 1.0 → 0.4 from ARREST; y = (1 − a)·tanh-soft(x/c) + a·clamp(x/c), × c]
[Heat: excess = max(|x/c| − 1, 0), 0.5 ms follower, × band-limited (3..14 kHz) Dsp.Noise(seedFor("FORGE", macros)), × a × 0.35]
   │
   ▼
[Tide.bandLimit → Dsp.decimate → peak-match to the input's peak]                 @44.1 kHz
```

Every box is a pure function of the pad and the four macros; nothing feeds back; nothing needs a cache. The whole chain in the Python harness takes about 30 ms for a 0.6 s pad at 4×, dominated by scipy's filter calls; Kotlin loops over 265k samples with about thirty operations each will be a few milliseconds.

## Macro contract

| Control | Default | What it changes in the model | Required interaction | Test (from the prototype) |
|---|---:|---|---|---|
| CHARGE | .50 | shock amplitude (0 to 1.2 × pad peak) **and** all-pass count (0 to 24) | with STANDOFF: at CHARGE 0 there is no shock, so STANDOFF must be inert, by a test, not by accident | low-band share (20 to 120 Hz) rises monotonically on a source that has none: 0.007 → 0.011 → 0.018 → 0.031 → 0.058 |
| STANDOFF | .50 | where the shock front lands, 0 to 1 ms after the hit | with CHARGE (above) | attack log-spectral distance ≥ 6 dB between the ends (measured 6.4 to 6.9); no comb claim |
| YIELD | .50 | the slew threshold, as a fraction of this pad's own peak slew (2 % to 100 %), so the knob means the same on a kick and a hat | with CHARGE: a longer chirp is more "fast material" | the body's distortion (share above 200 Hz after 50 ms) falls monotonically to a floor: 0.064 → 0.020 → 0.0058, then flat; the attack's share above 3 kHz peaks in the upper half (0.13 → 0.31) |
| ARREST | .50 | the die's ceiling (1.0 → 0.4 of peak) and hardness (tanh → clamp), and the heat burst's gain | none | crest factor falls monotonically: 4.87 → 4.86 → 4.62 → 4.30 → 4.07; burst share above 6 kHz rises 0.0004 → 0.0019 |

All four moved the sound at every step (6 to 24 dB log-spectral distance from the default). An unknown key changes nothing (asserted, after a bug in the prototype's own seed: see finding 6).

## What the prototype found (Python harness, not Kotlin)

Two stand-in pads: a THUMP-like kick (160 → 48 Hz sweep, t60 0.45 s, a 0.6 ms click) and a five-mode inharmonic "metal" ring. The house output stage from `snipkit.finish`. Nothing heard.

1. **The medium spreads, it does not focus.** Energy width of a 0.2 ms click: 1.55 ms (2 sections), 4.37 ms (8), 12.5 ms (24); peak 1.0 → 0.68 → 0.35 → 0.20. The brief's "aligns and concentrates" is inverted. The useful reading is a chirp maker, and it is the best thing in the design: on the metal source the dispersed fold put 0.70 of the first 5 ms above 3 kHz against 0.12 for a direct fold (5.8×). On the kick it was 0.19 against 0.25, because a kick's click sits under its body and the chirp spreads it further under.
2. **Recompression is dead.** Forward all-pass → fold → exact inverse all-pass (reverse, filter, reverse) is possible offline and inverts the linear path to 5e-14. But the harmonics the folder adds were never dispersed, so the inverse advances them to before t = 0 and the buffer cuts them off: attack share above 3 kHz fell to 0.06 (kick) and 0.12 (metal), below the direct fold. Dropped.
3. **ADAA does not help here; oversampling does, a little.** Against a 16× reference on the same output path (measurement floor 0.02 dB): the die's hard clip alone is clean at 4× (0.55 dB; 0.19 at 8×). The slew-gated folder is the aliasing source: 4.9 dB at 4×, 3.3 dB at 8×, and first-order antiderivative anti-aliasing on each branch changed nothing (±0.03 dB), because the blend and the drive vary with the signal. Part of the 4× vs 16× distance may be the detector's own rate dependence rather than fold-back; the Kotlin probe should separate the two (render the detector's p at both rates and compare) before choosing 4× or 8×.
4. **A curve switch alone does nothing on a kick.** With the fold and the clip at the same drive, the click (high slew, low amplitude under the body) never crossed the knee, and YIELD only changed the body. The hyperplastic state has to *add drive*: fast material is driven `1 + 3p` times harder into the folder. After that change YIELD does what the brief wants: the body's distortion falls to a floor as YIELD rises (0.064 → 0.0058) while the attack folds (0.13 → 0.31 in the upper half). The knob's law still needs a re-map: above about 0.95 nothing folds (threshold at the pad's peak slew), and below about 0.3 the body folds too, which is the "fracture everything" end. Both ends are live; the middle is where the instrument is.
5. **The heat burst must be band-limited before it is shaped.** A white burst high-passed at 4 kHz left 0.7 % of the raw energy above the output Nyquist and 26 partials above 0.95 Nyquist after decimation; a 3 to 14 kHz band-pass left 0 and 0. The remaining 4× vs 16× difference with the burst on (about 2 dB) is a different noise realisation per rate, not fold-back.
6. **Seed from known macros only.** The prototype's first seed hashed every key it was handed, so an unknown macro changed the burst and failed the house's "unknown key changes nothing" probe. `Tide.seedFor` filters its keys for the same reason; FORGE must too.
7. **Nothing grew, nothing went non-finite, DC stayed under 2e-5** at every macro corner tried.

## Three directions

- **Strong: a rack section (DISTIL + STACK).** The four stages as one `Forge` section, oversampled like VALVE, peak-matched, four macros. Gesture clear; new against the rack (nothing reads slew, nothing coins); risk low (no feedback, no solver; the folder's 4× vs 8× is the open number); cost about VALVE's size plus tests. Wins because it reaches every kit and every captured hit, and because the brief's own input definition asks for a processor.
- **Alternate A: a drum engine (PORT, literal in the DSP sense).** HYDROFORM voices KICK, SNARE, METAL with THUMP-style raw sources (as ONSET borrows them), the shock tuned by TUNE, and pad velocity mapped to YIELD through `Velocity.touchedRender`, so a harder hit folds more (the house's "velocity changes spectral weight, not level" rule). Gesture the same; novelty against the roster only in velocity-as-timbre; risk the ONSET/THUMP pad competition; cost 26 surfaces. Build it only if the section is heard and wanted on generated drums more than on chops.
- **Alternate B: the die rings (INVERT).** The clipped-off excess excites three to five metal modes (`Modes.ring`) instead of noise, so coining leaves a metallic ring: a different ending to the gesture, aimed at the keygroup-attack use. Risk: the ring lengthens a one-shot and can move a KICK to PERC in the classifier (the PUNCH/DUB knife edge, `Punch.kt` KDoc); cost one `Modes.ring` call. A candidate for the top half of ARREST after the first listen.

## Risks

- **Aliasing in the folder** (finding 3). Probe: 4× against an 8× render of the same stage in a Kotlin test, bar set after the listen.
- **CHARGE moves a KICK's classifier identity.** The shock adds sub weight by design; `Classifier` reads low-ratio on a knife edge (0.574 vs 0.55 for THUMP's KICK through DUB). Probe: classify the THUMP and ONSET kits through FORGE at CHARGE 1 and assert no class changes, or disclose which do.
- **STANDOFF reads as a dead knob at CHARGE 0.** Decide: a non-zero floor on the shock, or document it (SPIKE's SUSTAIN has a neutral the same way).
- **All-zeros is not a copy.** At CHARGE 0, YIELD 1, ARREST 0 the stage is still a gentle tanh at the pad's peak and a 1.3× clip knee. Decide whether the rack's "all-zeros ≈ transparent" habit (CRUNCH's KDoc) is a bar here (distance under 1 dB) or whether FORGE declares itself always-on.
- **The heat burst on a hat.** A noise burst on a source that is already noise may read as nothing or as a click. Audition before a bar.
- **Shape constants** with no provenance yet: all-pass centres 300 Hz to 6 kHz and Q 0.7, pre-gain 1.3, fold drive 3, die floor 0.4, burst gain 0.35 and band 3 to 14 kHz, follower 0.5 ms, detector smoother 2 kHz, shock 48 Hz with t60 0.25 s. Each is labelled "shape" until the owner's gate moves it.

## Cheapest proof (the next step)

Run the prototype on real pads: `testkit/` THUMP and ONSET kicks and snares, a SKIN hat, a captured chop, a MERCURY or TINES attack for the keygroup case. Write the sixteen clips (four macros at their ends, plus the default and the dispersed-vs-direct fold pair) to a gate page the way the COMPRESSOR spec did, and ask the owner four questions: does the fold on the chirp read as thicker or as "laser"; does the die at ARREST 1 read as loud or as broken; does the burst read as heat or as a click; and where in the rack it belongs. Those answers set the bars; no Kotlin before them.

## Integration (each row greps at `ad2439e`)

- `Forge.kt`: `object Forge` with `MACROS`, `defaults()`, `scramble()`, `process(snip, macros)`, in the shape of `Spike.kt` and the oversampled body of `Valve.process` (`Valve.kt:153-197`).
- `FxChain`: one `Section("forge", Forge.MACROS, { it.forge }, { c, m -> c.copy(forge = m) }, Forge::process)` in `SECTIONS` between `squash` and `valve` (`FxChain.kt:196-224`); one appended `forge: Map<String, Float>?` constructor field (`FxChain.kt:53-78`); the KDoc order line (`FxChain.kt:11`); `FxChain.VERSION` unchanged (JSON is generic over `SECTIONS`, `FxChain.kt:142-155`).
- Pinned order lists and `SECTION_NAMES` users: `FxTest`, the presets tests' forbidden-word lists (the COMPRESSOR spec's row).
- `Treatments.EXTRA`: a recipe chip once the listen names one (`Treatments.kt:47` is SPIKE's `spiked`); `shell/.../PadSheet.kt:74,207` maps chips to recipes.
- `ForgeTest`: the macro contract's five bars, determinism, finite, DC, peak-match, unknown key, the 4× vs 8× folder probe, the kit classifier sweep.
- `README.md` rack lines and `docs/CLI.md` move with the order. No app change (the rack's chrome derives from the recipes).

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| No slew-dependent curve, no coining clipper exists in `synth/` | verified | `grep -rn -i "slew\|derivative"` and the section KDocs, `ad2439e` |
| VALVE is the pattern for an oversampled rack section | verified | `Valve.kt:153-197` |
| `Dsp.Biquad.allpass` is the RBJ form | verified | `Dsp.kt:420-432` |
| An all-pass chain spreads a transient; the inverse exists offline | measured | finding 1, 2: 0.2 → 12.5 ms; identity error 5e-14 |
| Dispersed fold is denser than direct fold on a metallic source | measured | finding 1: 0.70 vs 0.12 above 3 kHz in 5 ms |
| Recompression removes the fold's harmonics | measured | finding 2 |
| The die clips cleanly at 4×; the folder aliases; ADAA1 does not help | measured | finding 3: 0.55 dB, 4.9 dB, ±0.03 dB |
| The slew state must add drive, not only switch the curve | measured | finding 4 |
| A band-limited burst leaves nothing above the output Nyquist | measured | finding 5: 0.7 % → 0, 26 → 0 partials |
| Each macro moves its named quantity monotonically (YIELD on the body; attack share non-monotonic) | measured | macro table |
| 4× is enough for the folder | proposed | open: the 4× vs 8× Kotlin probe |
| FORGE between SQUASH and VALVE is the right slot | proposed | listening question |
| The fold on the chirp reads as thick, not as a laser zap | heard? | not heard; the gate page's first question |
| The heat burst reads as heat | heard? | not heard |

## Why it is built this way

1. **The all-pass chain is kept, but for the opposite reason the brief gives.** It cannot focus a transient; filters that pass every frequency at a different delay can only spread one. But a spread click is a tiny rising sweep, and a sweep through a folder makes a much thicker set of harmonics than a click does, because the folder sees a different frequency at every instant. That is the thickening the brief was after, by a different mechanism. Take the chain out and FORGE is a clipper with a sub.
2. **The fast part gets more drive, not just a different curve.** In a kick, the click is quiet next to the body even though it moves fast. A transfer curve only does something where the signal crosses its knee, so switching curves by speed changed nothing on the attack until the "flowing" state also pushed the signal harder into the folder. Without that, YIELD is a body-distortion knob with a physics name, which is the first thing the review rubric flags.
3. **The threshold is relative to each pad's own peak slew.** The rack works on finished pads at any level and brightness. A threshold in absolute units would make YIELD mean one thing on a kick and another on a hat. Because the render is offline, the whole pad can be read first and the knob scaled to it; a real-time plugin could not do this.
4. **The ceiling closes as it hardens.** A clipper at the signal's own peak clips nothing. "Coining" only exists when the die is lower than the blank, so ARREST lowers the ceiling and hardens the curve together, and the peak-match afterwards brings the level back. That is where the crest factor drop comes from, and the crest factor is the only honest form of "massively loud" in a system where the kit sets the level.
5. **The noise burst is filtered before it is shaped.** Multiplying white noise by a fast envelope and then band-limiting leaves energy above the output Nyquist that folds back as grit nobody asked for. Band-limiting the noise first, then shaping it, measured clean. The one risky decision in the design is the folder's aliasing at 4×: the house minimum is enough for the clip, measurably not quite for the fold, and whether 3 dB of difference against an 8× render is audible is a listening question, not a measurement.

## Prompt notes for the next round

The brief was rich enough to design from without a question. Three things would have sharpened it: which pads it is for first (a THUMP kick, an ONSET snare, a chop, a keygroup attack), because the chirp's value is source-dependent (finding 1); whether CHARGE is meant to be heard as a sub layer or as an attack booster, because those pull the shock's frequency in different directions; and a reference clip with a timestamp for the "coined" sound, so the gate page can be built against it.
