# KEEL Engine: Engineering Specification

Version 1.0 · 2026-10-23
Status: proposed engineering handoff. Prototyped in the Luthier Python harness (three voices, 53 renders, report attached). First listen 2026-10-24 on a small speaker, ten harness clips: the settle reads as one gesture, and the bowed hold belongs to the pluck. The octave question and SLIDE remain open, and BELLY is unheard. Kotlin not started. Sibling check corrected 2026-10-24: the first version missed BALLAST, a merged bass engine (section 2). WEIGHT rebuilt 2026-10-26: the sub-octave was inaudible on two listens and is removed; WEIGHT now thickens the note's own fundamental and overtones 2 to 4 (section 9), heard as "Sounds good". Sections 11, 14 and 15 and the R1 "As built" record keep their sub-era numbers where marked. R2 built 2026-10-26 (the held loop and the keygroup, "As built: R2"); its gate is the owner playing the keygroup. The keys page (2026-10-27) heard UPRIGHT's sustain level as about right and WEIGHT 1 as still KEEL, and heard UPRIGHT's held keys pump; the bow is now locked to the string ("R2 follow-up" at the end). The bow page (2026-10-28) heard that bow, with the most hair offered (.15), as no pump at C2 and A2, and the sustain level as about right again. R3 built 2026-10-29: twelve presets, the kit and a KEEL starter ("As built: R3"); BELLY was put to one question before any Kotlin, and its gate and the kit's are the owner's. The belly page was answered ITS OWN SOUND (2026-10-31) and BELLY was built the same day as KEEL's third voice ("As built: BELLY").
Repository truth: SnipSnap read at `55ae5fd00` (2026-10-23). Every API named below was found with `grep -rn` in `synth/src/main/kotlin/com/snipsnap/synth/` on that commit; the file and line are given at first use. Omnibus read at `e35a7927d` (MIT).

The characteristic gesture is **attack with character → the upper spectrum distils away → the body fills in under it → release**. (Until 2026-10-26 the third beat was "the sub settles in under the note"; section 9 says why it changed.)

## 1. Summary

KEEL is a bass engine for the A01 register and the keygroup path. Three voices share one mechanism, a settle clock `s(t) = 2^(-t / half_life)` that every voice's spectrum follows downward toward its fundamental while WEIGHT raises the note's own fundamental and first overtones into the space the upper harmonics leave. UPRIGHT is a plucked string through a fixed wooden body and a closing low-pass. REDUCE is two-operator FM whose index decays on the settle clock. BELLY is a warm sine belly plus a detuned saw bite that fades on the settle clock. The settle is computed (it is an exponential with a half-life the SETTLE macro sets); the "bass that settles" is an analogy drawn from three Omnibus engines (section 13), not a physical model of anything.

What is computed: the string, the body modes, the FM, the saw, the filters, the drive, the body (WEIGHT), the envelope. What is imagined: that a bass note "settles". Nothing is simulated; no solver runs.

## 2. Goals and scope

Musical goals: a one-shot bass for the A01 pad that an MPC owner reaches for first; a bass keygroup (the roster has none: `Keys.kt` carries ten `xxxPad` functions at `5e953da54`, none for a bass, BALLAST included); a HOLD loop that is the settled state of the note, so the keygroup is attack plus loop.

V1 includes: three voices in the prototype, two in Kotlin R1 (REDUCE and UPRIGHT, the two that differ most); six macros plus HOLD; TONAL one-shots under 1.5 s; LOOP at HOLD's top; a `KeelPadMaker` in R2. V1 excludes: samples, stereo, FX in the signal path (GRIT is a source stage, not a pedal), velocity layers in kits (the pad maker is where they land), BELLY in Kotlin until it has been heard (heard as its own sound and built, 2026-10-31).

Why a new engine and not FATHOM R2: FATHOM (`Fathom.kt`, 273 lines) is the drum-side bass. Its roots are Hz (`frequencyFor`, line 114: DEEP 41.2 Hz, GRIND and GLASS 55 Hz), TUNE runs 0..24 semitones upward only, it has no MIDI root, no `drumClassFor` (its KICK/PERC classes are a static table in `app/.../SynthScreen.kt:4181`), no `Tide.bandLimit`, no DC stage, no HOLD, no keygroup, and 36 presets whose identity is KICK and PERC one-shots. Giving it a MIDI root, a sub and a loop would change what every FATHOM preset is. KEEL takes the pitched contract (TONAL and LOOP, MIDI root, HOLD, keygroup) and leaves FATHOM alone.

Why a new engine and not BALLAST R2 (added 2026-10-24; the first version of this spec missed BALLAST because the Luthier engine inventory has no row for it). BALLAST (`Ballast.kt`, spec `2026-10-04-ballast-bass-engine-design.md`) is the roster's pitched bass: six voices (ROOT, WIRE, GLINT shown as TILES, DEEP, BLOOM, SWARM), TUNE C1 to C4 on MIDI 24 to 60, a held loop that closed on all 72 measured corners, 48 presets, on the phone picker, and the owner liked the bass. Its source is a saw and a pulse into `Dsp.Ladder`, and the gesture is the bass shaking a structure: frame modes, sympathetic wires and rattling tiles. What it does not do, by its own spec: it adds no sub ("There is no sub-sonic component added"; energy under a held ROOT at C2 is 67.9 dB below the note), nothing in it is FM, WIRE is sympathetic wires driven by the bass rather than a plucked bass string, it has no `Keys.ballastPad` and no PadMaker, so no keygroup, and its renders measured 165 to 283 ms on a server against KEEL's tens. Its filter droops over a fixed 0.3 s, the nearest thing in the roster to a settle. KEEL's settle differs in two ways: its half-life is the SETTLE macro (0.05 to 1.2 s), and the same clock pulls the sub in. Adding a sub, a string and an FM source to BALLAST would change all 48 presets and the structure's input. So KEEL is a second bass with a different gesture, not the first bass. The cheaper test of the keygroup slot is `Keys.ballastPad` plus a `BallastPadMaker`, with no new DSP.

## 3. SnipSnap contract

Offline mono 44.1 kHz (`Dsp.RATE`, `Dsp.kt:21`), synthesized at `RATE * Dsp.OVERSAMPLE` (`OVERSAMPLE = 4`, `Dsp.kt:33`). Output stage copied from Mercury's `finish` and `condition` (`Mercury.kt:692-722`): `Tide.bandLimit(raw, RATE * OVERSAMPLE)` (`Tide.kt:537`) → `Dsp.decimate(raw, RATE)` (`Dsp.kt:759`) → subtract the mean, then `Dsp.OnePole` high-pass at 20 Hz (`x - hp.lp(x, 20f)`, `Dsp.kt:451`) → `Dsp.levelTo(out, RATE, MELODIC_LOUDNESS_TARGET * velocity.pow(.35f))` as Oven does (`Oven.kt:219-226`; `MELODIC_LOUDNESS_TARGET = 0.1834f`, `Dsp.kt:562`) → `Dsp.fadeTail(out)` (`Dsp.kt:792`). Seeds via `Dsp.seedFor("KEEL", voice.name, midi)` (`Dsp.kt:147`); noise via `Dsp.Noise(seed)` (`Dsp.kt:171`). Six macros plus HOLD. `drumClassFor` returns TONAL or LOOP, never a drum class. HOLD seam under `Keys.MAX_SEAM_ERROR = 1e-3` (`Keys.kt:147`), measured with `Keys.seamError(s, loopStart)` (`Keys.kt:188`) against a real preceding cycle. Registration per `snipsnap-conventions.md` section 3 and section 12 below.

## 4. Signal flow

```
midi, velocity, macros (TUNE SETTLE WEIGHT GRIT SLIDE LENGTH HOLD)
        │
        ▼
[KeelClock]──(per sample @176.4 kHz: f(t) pitch track, s(t) settle, env(t))──▶
   pure functions of macros; no state; nothing to cache
        │
        ├──▶[KeelSource: one of]
        │      UPRIGHT  pluck burst ─▶ Strings.Loop (retune per sample to f(t)) ─▶ curing LP (fc = f·(1.5 + 45·s)) ─▶ + 0.18·body (3 fixed-Hz modes)
        │      REDUCE   mod sine @f ─▶ carrier sin(2π·phase + (7·s + .08)·mod)
        │      BELLY    .75·(sine + .3·sine³) + bite: polyBLEP saw @f·2^(9/1200) ─▶ closing LP (fc = f·(2 + 28·s)) × .65·(.25 + .75·s)
        │          │
        │          ▼
        │      [normalise to .8 peak] ─▶ [GRIT: tanh(k·x)/tanh(k), k = lin(GRIT,1,24)·(.5 + .5·velocity)]
        │          │
        │          ▼
        └──▶[KeelWeight: overtones 1..4 read on the phase track (one-period means), raised by 9 dB·w,
                   │      missing ones floored at w·(−3, −6, −9 dB) under the fundamental; w = WEIGHT·(.5 + .5·(1 − s))]
                   ▼
            [source + weight] × env(t)   (one-shot)      or   [no env, no slide]  (HOLD: settled-state loop)
                   │
                   ▼
            [Output: bandLimit → decimate → mean + 20 Hz HP → levelTo·v^.35 → fadeTail]
```

Single arrows throughout: every path is one-way. The only feedback is inside `Strings.Loop`, whose loop gain is below one by construction (section 6). WEIGHT reads the source's own overtones and adds to them in their own phase; it never feeds back into the source.

## 5. Architecture and separation

- `KeelClock`: `pitchTrack(hz, slide, n)`, `settle(n, halfLife)`, `ampEnv(n, length, hold)`. Pure functions of the macros and the frame count. Nothing is cached because nothing costs more than an exponential per sample.
- `KeelSource`: one function per voice with the signature `(macros, hz, n, s, f, phase, seed, velocity) -> FloatArray`. Each returns an un-normalised source; `render` normalises to a 0.8 peak before GRIT so the drive law means the same thing for every voice.
- `KeelWeight`: one function. It takes the source after GRIT, the phase track (so it follows SLIDE), the settle curve and WEIGHT (section 9).
- `Keel.render`: assembles, applies the envelope, finishes. `Keel.renderLoop` assembles with `held = true` (no slide, no envelope, a planned pitch), cuts the whole-period loop, measures the seam.
- Nothing crosses between voices. BELLY's bite detune is the only per-voice constant the HOLD planner touches (section 10).

## 6. The sources

### 6.1 UPRIGHT (a plucked bass string through a wooden body)

Literal: a Karplus-Strong string with a one-pole loop low-pass and a loop gain, excited by two periods of linearly decaying noise low-passed at 3 kHz (Omnibus Oaken's pluck, `OakenEngine.h:82`). Kotlin: `Strings.pluck(freq, seconds, damping, pickHz, seed, rate, ...)` (`Strings.kt:1119`) or, because the pitch must follow SLIDE per sample, a `Strings.Loop` (`Strings.kt:527-540`) driven by `inject` and retuned with `retune(freq)` (`Strings.kt:671`). `Strings.tune` budgets the loop filter's phase delay (`Strings.kt:101`); the Python harness does not, which is why its UPRIGHT reads 8 to 13 cents flat (section 14). Expect the Kotlin to land within the house 5 to 50 cents.

Loop gain: `g = clamp(10^(-3 / (3 · LENGTH_s · f0)), 0.85, 0.9995)`, so the string alone reaches -60 dB at three times LENGTH and the amplitude envelope, not the string's loss, ends the note. Loop low-pass fixed at 2.2 kHz ("tension"; a shape constant).

Curing low-pass (Oaken's `:315-350`, 12 dB/oct, cutoff falling over the note): `fc(t) = clamp(f0 · (1.5 + 45 · s(t)), 60, 8000)` Hz, two cascaded one-poles updated per sample (`Dsp.TptSvf.process(input, freqHz, k)` with k = 2 for no resonance, `Dsp.kt:208`, is the house filter; a `Dsp.OnePole` pair is the cheaper equivalent). At `s = 1` the string is bright; at `s → 0` only the fundamental and a little second harmonic remain.

Body: three fixed-Hz modes, `Modes.fixed(hz, gain, t60)` (`Modes.kt:205`) rung with `Modes.ring(string, 1f, modes, rate)` (`Modes.kt:88`): (190 Hz, .7, .11 s), (540 Hz, .4, .08 s), (1050 Hz, .25, .06 s). Oaken's body is three bandpasses at 180 to 220, 520 to 600, 1000 to 1200 Hz with Q only (`:260-300`); the T60s here are shapes. Mix 0.18 body, 0.82 string. **Rule learnt in the prototype**: the body's longest T60 must be shorter than any note's fundamental decay, or the body outlives the fundamental and steals the pitch (v1 had 0.22 s at gain .9 and a 0.3 mix, and the late-window detector read 190 Hz as the note's third harmonic and the pitch an octave low). Peak-normalise the body before mixing (the `Modes.ring` gain rule in `snipsnap-conventions.md` section 4).

Not simulated: the string's stiffness, the pick position comb, the bridge, the body's coupling back into the string, the air.

### 6.2 REDUCE (FM that distils to its carrier)

Literal: two-operator sine FM, modulator at ratio 1.0 (unison, so the distilled end state is the carrier alone), no feedback. Index law `I(t) = 7.0 · s(t) · (0.6 + 0.4 · velocity) + 0.08`. Omnibus Omega's distillation (`OmegaEngine.h:476, 505, 128`) decays the index with a half-life of 2 to 20 s on a held note; here the half-life is SETTLE's 0.05 to 1.2 s because a one-shot has to finish settling inside LENGTH. Omega's index is applied twice in its own code (`distill.trigger(modIndex)` then `modIdxNow · distilledIndex`, so its default 3 behaves as 9); 7.0 is the shape chosen for the prototype, not a port of that number.

What FATHOM GLASS does and this does not: GLASS's index decays with the amplitude envelope (`idx = fmIndex · envAt(t, t60 · 0.6)`, `Fathom.kt`). REDUCE's index decays on SETTLE, independent of LENGTH, so a short note can stay bright to its end and a long note can go pure in its first 100 ms. That independence is the engine.

Not simulated: Omega's gravity (ratio pulled to an integer over 10 s) and purity noise. Both are held-note behaviours.

### 6.3 BELLY (a belly and a bite)

Literal: belly `A = sine + 0.3 · sine³` (Omnibus Overbite's sine shape at full, `OverbiteEngine.h:90-250`); bite `B` = a PolyBLEP saw 9 cents above the note (the repo has PolyBLEP in SIREN and STOOP; grep the helper's name before citing it) through a closing low-pass `fc(t) = clamp(f0 · (2 + 28 · s(t)), 80, 7000)`; `mix = 0.75 · A + 0.65 · (0.25 + 0.75 · s(t)) · B`. The bite fades as the note settles; the belly stays.

Sibling risk: FATHOM GRIND is a detuned saw pair (4 to 90 cents, symmetric), and BALLAST ROOT is a saw and a pulse into a ladder. BELLY differs in that its bite is one saw against a sine belly and it fades on the settle clock, but the difference is small enough that BELLY waits for the listen (R3) and may become a REDUCE or UPRIGHT preset family instead.

## 7. GRIT

`y = tanh(k · x) / tanh(k)`, `k = Dsp.lin(GRIT, 1, 24) · (0.5 + 0.5 · velocity)`. FATHOM's own private drive law (`Fathom.kt:129`) with k up to 48; bass wants less. Applied to the normalised source only. The sub is added after GRIT so the drive never turns the sub into a square whose odd harmonics compete with the note.

## 8. The settle clock and the envelope

`s(t) = 2^(-t / h)`, `h = Dsp.expMap(SETTLE, 0.05, 1.2)` s (`Dsp.kt:42`). At SETTLE .5, h = 0.245 s: the note is spectrally settled (s < 0.06) one second in.

Amplitude: 3 ms linear attack, then `exp(-6.9078 · t / L)`, `L = Dsp.expMap(LENGTH, 0.20, 1.25)` s. `Dsp.Env(attackSeconds = 0.003f, decay2T60 = L)` (`Dsp.kt:500`). Render length `1.15 · L`, at most 1.44 s, under `Classifier.LOOP_MIN_SECONDS = 1.5f` (`Classifier.kt:81`, private, so Keel carries its own 1.5 s constant) so every one-shot files TONAL.

HOLD below the loop threshold: a sustain stage. The envelope holds at full for `HOLD · 1.0` s before the decay begins, with the total render still capped at 1.44 s, so HOLD is audible at every setting and not a dead control below its top. HOLD ≥ 0.85: `isLoop` is true and `render` returns the settled-state loop (section 10), which files LOOP.

SLIDE: bipolar around .5 with `Dsp.around(macro, lo, center, hi)` (`Dsp.kt:63`): offset in semitones `around(SLIDE, -12, 0, +12)`. The pitch runs in the log domain, `f(t) = f0 · 2^((offset/12) · e^(-t/τ))`, `τ = (0.03 + 0.25 · |offset|/12) / 4.6` s, so the slide reaches 1% of its offset at 30 to 280 ms. SIREN's sweep "lands on the note" the same way (`Siren.kt:31, 60-66`); FATHOM's GLIDE is upward only and linear in semitones (`Fathom.kt:184-191`). Everything that has a pitch (string, FM, saw, sub) reads the same `f(t)` and phase track, which is what keeps the sub locked during a slide.

## 9. WEIGHT: the body fills in under the note

Rebuilt 2026-10-26. Prototype: `2026-10-23-keel-prototype/keel_thicken_ab.py`, measurements `weight-body-report.json`.

For the note's fundamental and overtones k = 2, 3, 4, read the complex amplitude `C_k(t)` by synchronous demodulation on the note's own phase track: `C_k = mean_P(mean_P(x · e^(−i·2π·k·phase)))`, where `mean_P` is a moving average exactly one period of the note long, `P(t) = round(RATE_internal / f(t))`, taken twice. A one-period mean nulls every other harmonic while the pitch is steady; a one-pole would leak the neighbours (risk 8 below was that fault). `A_k = 2·|C_k|`.

- `w(t) = WEIGHT · (0.5 + 0.5 · (1 − s(t)))`: the body grows as the settle removes the top, from half strength at the attack.
- `boost = 10^(9 dB · w / 20)`. The fundamental becomes `A_1 · boost`; each overtone's target is `T_k = max(A_k · boost, floor_k · w · A_1 · boost)` with `floor = (−3, −6, −9 dB)` for k = 2, 3, 4.
- Only the difference is added, in the overtone's own phase: `added_k = (T_k − A_k) · cos(2π·k·phase + arg u_k)`, `u_k = C_k + 0.02·|C_1|·(C_1/|C_1|)^k`, so where the note has no k-th overtone the floor is placed in phase with the fundamental's k-th multiple. Nothing cancels.
- Overtones 1 to 4 rise together, so the note's own balance and pitch are kept. Boosting 2 to 4 alone read REDUCE at F#1 an octave high (its 2nd overtone already sits 7 dB over its fundamental late in the note); capping that boost erased the effect on REDUCE. Both were measured before the owner heard anything.
- Applied after GRIT and before the amplitude envelope. At the register's top (G3, 196 Hz) the 4th overtone is 784 Hz: nothing near Nyquist.

Measured (harness, house loudness levelling, late window 0.45 to 0.95 s, against WEIGHT 0):

| Note | WEIGHT | 60 to 120 Hz | 120 to 400 Hz | 400 to 4000 Hz | Pitch |
|---|---|---|---|---|---|
| UPRIGHT C2 | .5 / 1 | +3.2 / +6.1 | +3.0 / +6.3 | −0.5 / −1.2 | root |
| REDUCE F#2 | .5 / 1 | +2.7 / +4.8 | +2.5 / +4.6 | −0.8 / −1.1 | root |
| REDUCE F#1 | .5 / 1 | +2.7 / +4.8 | +2.0 / +4.2 | −1.2 / −2.9 | root |

The top band falls because every clip is levelled to one loudness: the tilt toward the body at WEIGHT 1 is 5.7 to 7.5 dB. Early in the note (0.10 to 0.45 s) the body band rises 2.4 to 3.8 dB at WEIGHT 1.

**What it replaced.** Until 2026-10-26 WEIGHT was a phase-locked sine one octave under the note, `0.7 · WEIGHT^1.5 × (0.35 + 0.65 · (1 − s)) × lowfade × sourceEnv`. On the Kotlin R1 clips the owner heard WEIGHT 0, .7 and 1 as the same; the sub sat 2.4 to 16 dB under the note's fundamental at 23 to 46 Hz. Reshaped (soft-clipped sine, divided square) and raised level with the fundamental it was still "no real difference": above 60 Hz the clips matched the no-sub clip within 0.3 dB. A sub quiet enough to keep the note the note is felt on a full-range system and heard almost nowhere else. The ledger has both verdicts.

## 10. HOLD: the settled-state loop

Strategy: Mercury's whole-period plan (`Mercury.planLoop`, `Mercury.kt:731`; `renderLoop`, `:756`). With the sub removed (2026-10-26) the whole signal repeats at `1 / f0`, so plan in whole periods of `f0`: `loopFrames` = the frame count nearest 3.0 s that holds a whole number of periods; `hzFit = periods · RATE / loopFrames`. The seams below were measured with the earlier `f0/2` plan and the sub; R2 re-measures them. WEIGHT's one-period means are exact on a planned loop, because the pitch is fitted to whole periods. At C2 the retune is 0.01 cents (measured). Render warm-up + loop + 0.25 s with `held = true` (no slide, no envelope), band-limit, decimate, DC, then `Keys.seamError(s[0 until loopStart + loopFrames], loopStart)` against the warm-up as the real preceding cycle.

- REDUCE: warm-up 2.5 s (ten half-lives at SETTLE .5; the planner should use `max(2.5, 10 · h)`). Seam 1.1e-6 (measured).
- BELLY: the bite's 9-cent detune beats at 0.34 Hz at C2; a loop closes only if the beat count per loop is whole. Retune the detune so `beats = round(beatHz · loopSeconds)` fits: at 3 s the fitted detune is 8.81 cents (measured). The house "retune until the seam closes". Seam 2.2e-6 (measured).
- UPRIGHT: a plucked string decays, so the held state needs a sustain. A **bow**: seeded noise of exactly `loopFrames · OVERSAMPLE` internal frames, tiled, low-passed at 900 Hz, injected into the loop at 0.04 with the loop gain raised to 0.98 (Oaken's bow is filtered noise times pressure, `OakenEngine.h:90`). Because the bow repeats with the loop period and the string is time-invariant at the planned pitch, the output is loop-periodic once the transient has died: the house's "periodic driver" strategy. Time constant of the string at g = 0.98 is `period / (1 − g)` = 0.75 s at C2; warm-up 4.5 s is six time constants. Seam 3.2e-5 with 38% of the loop's energy above 100 Hz and the loop's pitch at the root (measured). With g = 0.995 and a 2.5 s warm-up the seam was 0.24: the string had not reached steady state. The planner must derive the warm-up from the loop gain, not fix it.
- The loop is "driven": the bow holds the string at a fixed state; the physics of a pluck does not continue. Say so in the KDoc.

Keygroup (R2): `KeelPadMaker` after `ResinPadMaker` (`shell/.../ResinPadMaker.kt`, 119 lines, no base class; `SynthScreen.kt` wires makers by hand). Zones every 3 semitones over MIDI 24..48, nine zones (`resinPadMidis` pattern, `Keys.kt:134`). Each zone's `KeyNote` is attack plus loop: the one-shot's settle (SLIDE at .5, env held) followed by the planned loop, `loopStartFrame` = the attack's frame count (`KeyNote`, `Keys.kt:17`; `resinPad`'s `cut`, `Keys.kt:216-266`). The instrument sidecar already carries `loopStartFrame` and a velocity window (`kit/.../InstrumentStore.kt:36-50`), so this is the first engine to ship the attack-plus-loop region the roadmap lists as open. Sample cost: UPRIGHT zones are about 7.5 s (4.5 + 3), 660 KB each; shorten by lowering the hold loop gain if the hardware load check objects.

## 11. Macro contract

| Control | Default | What it changes in the model | Required interaction | Test |
|---|---:|---|---|---|
| TUNE | .50 | ±12 semitones around the patch's MIDI root, clamped to MIDI 24..55 | | root within 50 cents at WEIGHT 0, every voice, three notes (UPRIGHT 5 to 15 cents flat in the harness, section 14) |
| SETTLE | .50 | half-life h of s(t), 0.05..1.2 s: the speed at which harmonics leave and the sub arrives | SETTLE × LENGTH: a short note with slow SETTLE never settles (bright pluck); a long note with fast SETTLE is a sub tone with an attack | log-spectral distance between extremes ≥ 5 dB (measured 5.3 / 10.0 / 10.1 dB); centroid at 0.3 s monotonic falling in SETTLE |
| WEIGHT | .40 | the body: fundamental and overtones 2 to 4 raised by up to 9 dB, missing overtones floored, grown by the settle (section 9) | WEIGHT × SETTLE sets when the body fills in | 120 to 400 Hz band, late window, rises monotonic in WEIGHT and by at least 3 dB at WEIGHT 1; 400 to 4000 Hz does not rise; root pitch at WEIGHT 1, every voice, three notes (harness: +4.2 to +6.3 dB, root on all) |
| GRIT | .20 | tanh drive k 1..24 on the source only | GRIT × velocity | distance between extremes ≥ 20 dB (measured 30 / 49 / 23 dB); alias proxy at GRIT 1 under 1e-3 before bandLimit |
| SLIDE | .50 | start offset −12..+12 semitones, log-domain glide 30..280 ms | SLIDE reaches the sub too (shared phase) | early-window pitch at SLIDE 0 below the root by ≥ 300 cents, late within 5 (measured −658 / −1.2 cents) |
| LENGTH | .50 | -60 dB time of the envelope, 0.2..1.25 s; render ≤ 1.44 s | LENGTH sets UPRIGHT's loop gain (3 × L) | decay-to-−60 dB tracks L; render frames < 1.5 s · RATE |
| HOLD | 0 | sustain seconds before the decay; ≥ .85 the settled-state loop | HOLD top ignores SLIDE and LENGTH | seam < 1e-3 against a real preceding cycle; `drumClassFor` agrees with the length to the frame |

Macros are plain words. No knob shares the engine's name. Six macros is the house ceiling; LENGTH is the first to fold if the owner wants five.

Velocity (on the patch, as Oven's `velocity` field): scales GRIT's k by `0.5 + 0.5 · v`, REDUCE's index by `0.6 + 0.4 · v`, UPRIGHT's pick low-pass by `2 + 2 · v` kHz, BELLY's bite level by `0.6 + 0.4 · v`, and the output target by `v^0.35`. Velocity changes spectral weight before it changes level (Omnibus D001, the house `brightnessOverride` rule). `brightnessOverride` is not claimed until a sweep proves GRIT monotonic.

## 12. Voices

| Voice | Round | Source | Defaults (TUNE SETTLE WEIGHT GRIT SLIDE LENGTH HOLD) | Hypothesis |
|---|---|---|---|---|
| REDUCE | R1 | 2-op FM, index on the settle clock | .5 .5 .4 .2 .5 .5 0 | the cleanest statement of the gesture |
| UPRIGHT | R1 | KS string, fixed body, curing LP | .5 .45 .35 .15 .5 .55 0 | the only plucked string bass in the roster (BALLAST WIRE is sympathetic wires) |
| BELLY | R3 | sine belly, fading saw bite | .5 .5 .45 .3 .5 .5 0 | may be a preset family of REDUCE instead; the listen decides |

Presets after the listen only. Kit (`SynthKits.keel()`, after `oven()` at `SynthKits.kt:1067`): A01..A08 a pentatonic walk of REDUCE over TUNE from MIDI 36 (`PENTATONIC`, `SynthKits.kt:109`), A09..A16 named presets with the loops last, every class from `drumClassFor`, dry.

## 13. Render budget and caching

All stages are linear-time per sample with no solver and no geometry: one delay line, two one-poles, three biquads, a tanh, two sines, an exponential. A 1.44 s one-shot is 254k internal frames; the Python harness renders a voice in 30 to 70 ms including its own per-sample string loop, so the Kotlin will be well inside "tens of ms per knob turn". Nothing is cached. The HOLD loop costs the warm-up: 7.5 s of internal frames for UPRIGHT (1.3 M), 5.5 s for the others; a keygroup of nine zones is nine such renders and is a `KeelPadMaker` job with a progress callback, as `resinPad` has (`cancelled`). Pitch alone never rebuilds anything because nothing is built.

## 14. Numerical and output requirements, with the harness numbers

Python harness v3 (`keel_proto.py`, report.json beside this spec), not Kotlin. Three voices at MIDI 30, 36, 42 and the corners named.

| Probe | Requirement | Measured (harness) |
|---|---|---|
| Determinism | byte-identical renders (`DeterminismTest`) | by construction (seeded noise, no clock); not run as a Kotlin test yet |
| Finite, bounded | all finite; raw internal peak under 2.0 before `levelTo` at the harness macro set; under 16 (`RAW_PEAK_BOUND`) at any setting once WEIGHT is the body, whose worst measured corner is 6.9 | finite; raw peak 0.94..1.00 (sub-era harness) |
| Output peak | 0.01..1 | 0.88..0.99 |
| DC | abs(mean) < 0.005 | ≤ 1.2e-4 |
| Root pitch (WEIGHT 0, floor 40 Hz) | within 50 cents | REDUCE −1.1, BELLY −1.8, UPRIGHT −9.2 cents (harness KS un-budgeted; `Strings.tune` budgets it) |
| Root pitch with the sub, floor 25 Hz | documented, not required | reads f0/2 once WEIGHT ≥ .7 (REDUCE) or at MIDI 42 for all voices: the double period is exactly periodic. The Kotlin root test runs at WEIGHT 0; the sub has its own probe |
| Never grows | later block RMS below the attack peak | PASS, all 9 renders (ratio 0.27..0.28) |
| Aliasing | no partial above 0.95 Nyquist in the output; alias proxy before bandLimit under 1e-3 at the worst corner (GRIT 1, SETTLE 1, top note) | 0 partials; proxy UPRIGHT 9e-6, REDUCE 5.2e-4, BELLY 1.5e-4 |
| Every macro changes the sound | per-macro distance, WEIGHT by sub-band share | table in section 11 |
| Low end | C1 with WEIGHT 1: energy below 20 Hz under 1%, root still C1 | 0.19% / 0.013% / 0.012%; root 32.5 / 32.7 / 32.8 Hz |
| Length and class | one-shot < 1.5 s; loop files LOOP | 0.575 s at LENGTH .5; loop 2.997 s |
| HOLD seam | < 1e-3 against a real preceding cycle | REDUCE 1.1e-6, BELLY 2.2e-6, UPRIGHT 3.2e-5 |

Not measured: loudness against `Loudness.of` (the harness levels to RMS), velocity, scramble, JSON round trip, classifier on real renders. Those are Kotlin probes.

## 15. Sonic risks (where this would sound bad) and the probe for each

1. **The octave question.** Resolved 2026-10-26 by removing the sub; WEIGHT's test now requires the root at WEIGHT 1 (section 11). History: at WEIGHT ≥ .7 a pitch detector heard the sub as the note; the ear might have too. Probe: root at WEIGHT 0; sub-band share monotonic. Decision: the owner's, from the WEIGHT sweep at three notes. If the ear flips before .7, lower the 0.7 ceiling or make WEIGHT's top a stated "drop" region.
2. **The body steals the pitch** (happened in v1). Probe: late-window pitch at WEIGHT 0 on UPRIGHT; body T60 ≤ 0.11 s is the guard. Revert the T60 alone and the probe fails.
3. **The sub outlives the note** (happened in v2). Resolved 2026-10-26: no sub. Probe: sub-band share in the last 20% of the render under the share at 40%. The `sourceEnv` factor is the guard.
4. **GRIT on a settled sine is a square.** Odd harmonics return after the settle removed them. Probe: centroid monotonic in GRIT at s → 0; the owner decides whether that is the point or a defect. A GRIT that fades with s(t) is the alternative.
5. **SLIDE from above is a cartoon.** +12 semitones falling reads as a chirp (early window +2690 cents against the root, the detector catching the glide). Consider narrowing the upward side to +5. Audition section SLIDE at 0, .25, .5, .75, 1.
6. **Double decay on UPRIGHT.** String loss plus envelope makes LENGTH feel short (decay-to-−60 dB 0.39 s against LENGTH's 0.5). The 3 × L loop gain is the guard; the owner may want 4 ×.
7. **BELLY is a FATHOM GRIND or BALLAST ROOT preset.** Probe: none; a listen. R3 gate. BALLAST ROOT weakens BELLY's case further. Closed 2026-10-31: the owner heard BELLY against both, on C2, as its own sound.
8. **The sub's borrowed envelope ripples at the note's pitch** (found 2026-10-26; resolved with the sub's removal, and the reason WEIGHT demodulates over whole periods). `sourceEnv` is a 30 Hz one-pole on the source's square, so on UPRIGHT it still carries the note's fundamental and amplitude-modulates the sub, putting energy at 1.5 times the note: 17.7 dB under the note's fundamental on UPRIGHT at C2 with a sine sub, 53 dB under on REDUCE. Probe: energy at 1.5 f0 with a sine sub. Fix with the WEIGHT rework: a lower or two-pole follower.
9. **WEIGHT was inaudible** (heard 2026-10-26, ledger; resolved by section 9's rebuild). The pure sine sub sits at 23 to 46 Hz. The rework under audition (`2026-10-23-keel-prototype/keel_sub_ab.py`, `sub-shapes-report.json`) keeps the grow law and the borrowed level and compares sine, soft-clipped sine and a divided square, each with its fundamental level with the note's. At 1.5 f0 the square measures 10.5 dB (UPRIGHT C2) and 11.2 dB (REDUCE F#2) under the note's fundamental, the soft clip 15.6 and 13.3 dB.
10. **WEIGHT at its top may read as boom or distortion.** The owner's verdict was "Sounds good"; the page's second question (still KEEL, like distortion, boomy) was not answered. Probe: 400 to 4000 Hz never rises with WEIGHT. Listen again at the R2 keygroup.
11. **WEIGHT during a slide.** The one-period mean follows `P(t)`, but during SLIDE's glide neighbouring harmonics are not exactly nulled. Probe: SLIDE 0 and 1 at WEIGHT 1 stay finite, bounded and on the root in the late window.

## 16. Integration (checklist rows that grep)

Enforced by the compiler or registry tests:
1. `Keel.kt`: `enum class KeelVoice { REDUCE, UPRIGHT, BELLY }` (BELLY added 2026-10-31, after the gate), `object Keel` with `MODEL_VERSION`, `MIDI_MIN = 24`, `MIDI_MAX = 55`, `DEFAULT_MIDI = 36`, `TUNE_SEMITONES = 24`, `macrosFor(voice): List<MacroSpec>` (`MacroSpec(name, default, neutral, law)`, `Thump.kt:38`), `defaults`, `settled`, `midiFor(voice, tune, midi)`, `frequencyFor` via `Keys.midiHz` (`Keys.kt:35`), `drumClassFor(voice, macros)`, `scramble`, `isLoop(hold)`, `render(voice, macros, midi, velocity)`, `renderLoop`. Oven is the shape (`Oven.kt:30-69, 219`).
2. `KeelPatch.kt`: Oven's shape with `midi`, `velocity`, `model` on the patch and in `toJsonValue` (`OvenPatch.kt`).
3. `Patches.kt`: a `KeelPatch.ENGINE -> KeelPatch.fromJsonValue(value)` arm (the dispatch at `Patches.kt:46-112`, 67 arms today).
4. `Velocity.kt`: `macroSpecsFor` arm (`is KeelPatch -> Keel.macrosFor(patch.voice)`, the `when` at line 191) and `touchedRender` arm (`is KeelPatch -> patch.copy(velocity = v).render()`, the `when` at line 369, Oven's form at 416). No `else` exists, so the compiler finds a missing arm.
5. `KeelPresets.kt` plus the `Presets.forVoice` arm (`Presets.kt:238` is Oven's) and the `all()` term (`Presets.kt:282`), or `KeelPatch.ENGINE` in `PatchTypes.PROTOTYPES` (`synth/src/test/.../PatchTypes.kt:69`) until the listen. R1 ships as a PROTOTYPE; R3 (2026-10-29) files the roster in `Presets` and takes KEEL off `PROTOTYPES`.
6. `PatchesTest.onePatchPerEngine` and `PadRecipeTest.handBuiltPatches` entries while rosterless.

By convention:
7. `DeterminismTest` canary.
8. `SynthKits.keel()` (section 12) and `KeelKitGenerator.kt` + `generateKeelKit` task → `testkit/SnipSnap Keel Kit/`.
9. `KeelTest.kt` with the probe set of section 14 and `KeelPresetsTest.kt` with the six preset contracts; blocklist terms: none (no maker was named in this brief; the Omnibus names are research text).
10. `KeelAuditionGenerator.kt` + `generateKeelAudition` → `testkit/keel-audition/`: voices at three notes, each macro swept, WEIGHT × SETTLE and GRIT × velocity at their corners, SLIDE at five settings, the kit, the loop eight times end to end.
11. Docs: "As built" appended here in the same commit as the code, a `docs/SYNTH_ROADMAP.md` row, `docs/KEEL.md`, README engine count.
12. R2: `Keys.keelPad` and `Keys.keelPadMidis`, `shell/.../KeelPadMaker.kt` after `ResinPadMaker`, a `KeelPadMakerTest`. Stale since `5e953da54`: there are 13 makers, not 8. `shell/.../PadMaker.kt` is the PAD FROM ANYTHING card, not a base class; the shape to copy is `GlintPadMaker` or `ResinPadMaker`, and the app's `HeldSpec` in `app/.../ui/SynthScreen.kt` is the one dispatch. Re-read before R2.
13. Deferred: the app picker (`SynthScreen.kt` `Engine` enum plus its arms) until after the listen. This machine has an Android SDK and `:app` builds, so the deferral is a choice, not a tooling limit.

Test gate: `./gradlew --no-daemon test`, judged by the exit code.

## 17. Build rounds and gates

- **R1** (one session): `Keel.kt`, `KeelPatch.kt`, REDUCE and UPRIGHT one-shots, HOLD as the sustain stage only (loop refused above .85 with a test that says so), the probes of section 14 as `KeelTest`, `DeterminismTest`, PROTOTYPES entry, the audition generator. Gate: the owner listens to the audition's WEIGHT sweep and SETTLE sweep and answers the octave question.
- **R2** (one session): `renderLoop` for both voices (REDUCE's planner, UPRIGHT's bow with the warm-up derived from the loop gain), `isLoop`, LOOP class to the frame, the seam test against the warm-up, `Keys.keelPad` attack-plus-loop, `KeelPadMaker`, hardware load check of one instrument. Gate: the owner plays the keygroup. Built 2026-10-26 ("As built: R2"); the hardware load check and the gate are the owner's. The keys page heard a pump; the bow was locked to the string on 2026-10-27 ("R2 follow-up"), and the bow page (2026-10-28) heard it, with more hair, as no pump at C2 and A2.
- **R3**: BELLY if it earned a voice, presets after the listen (6 per voice, the Mercury number), `SynthKits.keel()`, kit generator, docs. Gate: the kit on the phone. Built 2026-10-29 ("As built: R3") without BELLY, which went to the belly page first; both gates are the owner's. BELLY built 2026-10-31 on the page's answer, ITS OWN SOUND ("As built: BELLY").
- **R4**: picker entry (the SDK is present; `:app` builds); velocity layers in `KeelPadMaker` (two or three windows), the first engine to use the sidecar's `velStart..velEnd` beyond 0..127.

## 18. The three directions considered

**Strong (DISTIL + MUTATE, built above).** Distil three Omnibus bass engines to one shared mechanism, the settle, and mutate its timescale from a held note (2 to 20 s) to a one-shot (0.05 to 1.2 s). Gesture clarity: one line, every voice obeys it. Novelty: nothing in the roster has a spectral clock set by a macro and independent of the amplitude envelope (BALLAST's filter droop is fixed at 0.3 s), a locked sub that grows, a plucked string bass or an FM bass, or a bass keygroup. BALLAST already owns the pitched-bass slot, so KEEL is a second bass, not the first. Risk: low (no solver, no feedback beyond a KS loop under unity); the octave question is the one open risk. Cost: two rounds to a keygroup.

**Alternate A (INVERT).** The sub is the carrier and the played note is its modulator: ratio 0.5 FM where distillation leaves the octave-below sine and the note is heard as a partial of the sub. Gesture: note → the note sinks into its own sub. Novelty high, risk high: the root is ambiguous by design, so the keygroup map is an octave off for half the WEIGHT range, and the classifier's bass branch cannot be told what the note is. Cost: one round, but the audition would likely send it back. Kept as a possible REDUCE voice ("SINK") for R3 if the owner likes the WEIGHT top.

**Alternate B (COUPLE).** A two-way coupling between the string and a tuned resonator at the sub-octave (`Modes.Bank`, `Modes.kt:494`) so the sub is energy the string loses, not a sine added: the settle becomes a physical transfer. Gesture: pluck → the string's energy drains into the sub resonator → the sub rings on. The most "third sound". Risk: the transfer rate depends on pitch and on the coupling bound (`MAX_NODE_KAPPA = 1.0`), the sub's level is then not a macro but an outcome, and a pitch detector reads the resonator. Cost: two rounds and a stability argument. Not chosen for V1; the right home for it is an UPRIGHT R3 voice once the plain version has been heard.

## 19. Research references

- Omnibus (`github.com/.../XO_OX-XOmnibus`, MIT, `LICENSE` "Copyright (c) 2026 XO_OX Designs", read at `e35a7927d`): `Source/Engines/Oaken/OakenEngine.h` (pluck :82, bow :90, body :260-300, curing LP :315-350), `Omega/OmegaEngine.h` (ratio table :369, index law :476 and :505, half-life :128), `Ogre/OgreEngine.h` (sub divider :69-104, mix :418, gravity :694), `Overbite/OverbiteEngine.h` (OscA :90-250, interaction :1940, Belly macro). Credit line in the KDoc, the `Bellows.kt` pattern. Note: SnipSnap has no LICENSE file; flagged once here.
- Karplus and Strong, "Digital Synthesis of Plucked-String and Drum Timbres" (1983); J. O. Smith, *Physical Audio Signal Processing* (waveguide tuning and loop filters); Chowning, "The Synthesis of Complex Audio Spectra by Means of Frequency Modulation" (1973).
- No products were studied for this engine; no maker is named in any engine, voice or preset name.

## 20. Verified / measured / proposed / heard ledger

| Claim | Status | Evidence |
|---|---|---|
| `Dsp.around(macro, lo, center, hi, pivot)`, `Dsp.expMap`, `Dsp.lin`, `Dsp.seedFor`, `Dsp.Env`, `Dsp.TptSvf.process`, `Dsp.OnePole.lp`, `Dsp.levelTo`, `Dsp.decimate`, `Dsp.fadeTail` exist as cited | verified | `Dsp.kt` lines 63, 42, 36, 147, 500, 208, 451, 716, 759, 792 at `55ae5fd00` |
| `Tide.bandLimit(buf, rate)` is `internal` in `Tide.kt:537`; Mercury's `finish`/`condition` sequence | verified | `Mercury.kt:692-722` |
| `Strings.Loop` constructor, `retune`, `inject`; `Strings.pluck`; `Strings.tune` budgets the loop filter's phase | verified | `Strings.kt:527-540, 671, 639, 1119, 101` |
| `Modes.fixed`, `Modes.ring`, `Modes.Bank` | verified | `Modes.kt:205, 88, 494` |
| `Keys.seamError`, `MAX_SEAM_ERROR = 1e-3`, `KeyNote(snip, loopStartFrame)`, `resinPad`'s attack-plus-loop cut, `mercuryPad`'s loop doubling | verified | `Keys.kt:188, 147, 17, 216-266, 339-348` |
| The instrument sidecar carries `loopStartFrame` and `velStart..velEnd`; every synth PadMaker writes one 0..127 layer | verified | `kit/.../InstrumentStore.kt:36-50`; `shell/.../*PadMaker.kt` |
| FATHOM has Hz roots, upward-only GLIDE, no HOLD, no keygroup, static KICK/PERC in the app | verified | `Fathom.kt:114, 184-191`; `SynthScreen.kt:4181` |
| BALLAST is registered (`Patches.kt:69`), not rosterless or a prototype, on the picker, with voices ROOT WIRE GLINT DEEP BLOOM SWARM; no `Keys.ballastPad`, no `BallastPadMaker`; no sub by its own spec | verified | default branch `5e953da54`: `Ballast.kt:65`, `PatchTypes.kt:56, 69`, `app/.../ui/SynthScreen.kt`, `Keys.kt` pad list, `shell/` makers, BALLAST spec lines 103, 109, 142 |
| The first sibling check covered FATHOM only and missed BALLAST | correction | the Luthier `engine-inventory.md` has no row for BALLAST, CAUSTIC, FILAMENT, LATTICE, SCANNEDCHAIN or TIDAL; of those only BALLAST is a bass. Found 2026-10-24 |
| `Classifier.LOOP_MIN_SECONDS = 1.5f` is private | verified | `Classifier.kt:81` |
| `OvenPatch` carries `midi`, `velocity`, `model`; Velocity's Oven arm is `patch.copy(velocity = v).render()` | verified | `OvenPatch.kt`; `Velocity.kt:269, 416` |
| PolyBLEP exists in the repo | read (inventory) | SIREN, STOOP; helper name not grepped |
| Omnibus engines, constants and the Omega double-index | verified | files and lines in section 19, MIT |
| Seam, pitch, DC, peak, alias, never-grows, sub-band and SLIDE numbers in sections 11 and 14 | measured | Python harness v3, `report.json`; not Kotlin |
| Body T60 0.11 s, mix 0.18, index 7.0, k 1..24, sub ceiling 0.7, settle range 0.05..1.2 s, loop LP 2.2 kHz, bow 0.04 at g 0.98 | proposed (shape) | chosen in the prototype; none heard |
| The settle reads as one gesture, not a filter sweep (REDUCE, SETTLE 0 and 1) | heard | owner, 2026-10-24, harness v3 clips 07-08, small speaker. The settle lives in the upper spectrum, so a small speaker can judge it |
| UPRIGHT's bowed hold belongs to the plucked attack | heard (small speaker) | owner, 2026-10-24, clip 10. The seam is unconfirmed by ear: the answers were exclusive, so choosing BELONGS did not rule out SEAM AUDIBLE. Seam measured 3.2e-5 |
| Where the note stops being the note and becomes the octave below as WEIGHT rises (risk 1) | heard: WEIGHT inaudible | owner, 2026-10-26, Kotlin R1 start-here clips (REDUCE F#1 and F#2, UPRIGHT C2 at WEIGHT 0, .7, 1): "Weights all sound the same." Measured on those clips: the sub at WEIGHT 1 sits 16, 5 and 2.4 dB under the note's fundamental, at 23, 46 and 33 Hz, and the clips peak near -17 dBFS. A pure sine that low and that quiet is under what ears and most headphones reproduce. The prototype's full-band WEIGHT activity of about 1 dB said the same thing; the sub-band probe hid it |
| A reshaped sub (soft-clipped sine or divided square, fundamental level with the note's) makes WEIGHT audible (risk 9) | heard: no | owner, 2026-10-26, Python sub-shape clips at UPRIGHT C2 and REDUCE F#2: "No real difference." Measured against the no-sub clip: 60 to 120, 120 to 400 and 400 to 4000 Hz all within 0.3 dB for every shape; only 20 to 60 Hz moves (+5 dB UPRIGHT, +15 dB REDUCE); the square's overtones change the band above 80 Hz by 18 to 19 dB under the clip itself, masked by the note's own harmonics. A sub level with the fundamental is felt on a full-range system and not heard elsewhere |
| WEIGHT as the body (section 9) reads as heavier | heard: yes | owner, 2026-10-26, Python clips UPRIGHT C2, REDUCE F#2 and F#1 at WEIGHT 0, .5, 1: "Sounds good." The page's character and playback questions were not answered |
| SLIDE from an octave below is musical, not a cartoon (risk 5) | open | not answered, clip 09 |
| BELLY earns a voice (risk 7) | heard: its own sound | owner, belly page answers (page 2026-10-29, answered 2026-10-31), Q1 ITS OWN SOUND: Python harness BELLY at C2 and G1 against BALLAST ROOT, FATHOM GRIND, REDUCE and UPRIGHT, all on C2 ("As built: R3") |
| Kotlin BELLY is the BELLY that was heard | measured | against the page's two harness clips: house loudness and peak equal, every band from 30 Hz to 4 kHz within 0.11 dB in three windows ("As built: BELLY"); not heard |
| The eighteen presets and the kit (R3, BELLY) | not heard | classifier and loudness measured ("As built: R3", "As built: BELLY"); the gate is the kit on the phone |
| The held loop closes on every zone, both voices (section 10) | measured (Kotlin R2) | `KeelTest`: REDUCE seams at most 1.6e-13, UPRIGHT at most 4.1e-5, every zone at the defaults and three zones at each of WEIGHT 1, SETTLE 0 and SETTLE 1; not heard |
| UPRIGHT's bowed sustain sits right under its pluck in a key zone | heard: about right (twice) | owner, keys page answers 2026-10-27, clips 01-02 (C1, C2). Those loops swung and were lifted on their loudest window to 6 dB; their mean sat 7.3 and 7.7 dB under the attack. `KEY_SUSTAIN_GAP_DB` is now 7.5, on the locked bow's loudest window (R2 follow-up); bow page answers 2026-10-28, Q3 ABOUT RIGHT (C2 and A2 at hair .15, loop means 8.2 and 8.9 dB under the attack) |
| UPRIGHT's held keys hold their level (no pump) | heard: pump (noise bow); heard: no pump (locked bow, C2 and A2) | owner, keys page 2026-10-27, Q2 "A PUMP". Measured on those files: the loop swung 2.6 dB at C1 to 12.5 dB at A2, every 3 s, and the press dipped up to 7.8 dB under the loop before it. The locked bow (R2 follow-up) at hair .15 measures 1.0 to 3.5 dB of swing and at most 2.2 dB of dip; bow page answers 2026-10-28, Q2 NO PUMP (C2 and A2) |
| WEIGHT 1 on a held key still reads as KEEL, not boom or distortion (risk 10) | heard: still KEEL | owner, keys page 2026-10-27, clips 06-09 (both voices' HEAVY instruments, C2 held and the walks, the noise bow) |
| How much hair the locked bow carries | heard: more (.15) | owner, bow page answers 2026-10-28, Q1 MORE HAIR over still (0) and a little (.06), at C2 and A2. `Keel.BOW_HAIR = 0.15` |

## Why it is built this way

1. **One clock, three voices.** SETTLE is the engine. If every voice had its own idea of "settle" the macro would mean three things and the kit would feel like three engines. Remove the shared clock and KEEL is three presets of FATHOM.
2. **Weight lives where hearing works.** A sub-octave quiet enough to keep the note the note sits at 23 to 46 Hz and was heard as nothing twice. WEIGHT now raises the note's own fundamental and first overtones, 60 to 400 Hz, together so the pitch cannot move, and only adds in each overtone's own phase so nothing cancels. Measured before it was played: the body band rises 4 to 6 dB at the top of the knob.
3. **The body rings shorter than the note.** A fixed 190 Hz body mode that outlives the fundamental becomes the pitch (v1). Keeping the body's longest ring under 0.11 s is what makes it a body and not a partial.
4. **HOLD is a driven state, not frozen physics.** UPRIGHT's loop is a bowed string, and the bow repeats with the loop so the seam can close. It is honest to say the pluck does not "continue"; a bowed bass is a different instrument from a plucked one, and the keygroup gets both: the pluck as the attack, the bow as the sustain.
5. **Every listen is measured first.** The sub failed twice because its change was all below 60 Hz, which the first prototype's 1 dB full-band WEIGHT reading had already said. WEIGHT's rebuild was gated on its band changes before the owner heard it, and its first two versions failed that gate (too subtle; then an octave flip at F#1).

## As built

Round R1, 2026-10-24, on `claude/keel-r1` off `5e953da54`. Kotlin, not heard: nobody has listened to a Kotlin render. Every number below is from `KeelTest` output (printed with a `KEEL` prefix) unless it says otherwise.

### What was built

- `Keel.kt`: `KeelVoice { REDUCE, UPRIGHT }` and `object Keel` with every member of section 16 row 1 except `renderLoop`: `MODEL_VERSION = 1`, `MIDI_MIN = 24`, `MIDI_MAX = 55`, `DEFAULT_MIDI = 36`, `TUNE_SEMITONES = 24`, `macrosFor`, `defaults`, `settled`, `midiFor`, `frequencyFor`, `drumClassFor`, `scramble`, `isLoop`, `render(voice, macros, midi, velocity)`. Internal: `synthesize`, `probe`, `Options` (two ablation switches the guard tests use), and the laws (`slideOffset`, `driveFor`, `subLevel`, `pickHz`, `loopGain`, `oneShotFrames`).
- `KeelPatch.kt`: Oven's shape (`ENGINE = "KEEL"`, voice, macros, `midi`, `velocity`, `model`, JSON both ways, `render()`).
- `KeelPresets.kt`: exists only because `PresetsTest` requires a roster class for every prototype. It files each voice's section 12 defaults and nothing authored. It is not in `Presets`.
- Registration: a `Patches.fromJsonValue` arm, the two `Velocity.kt` arms (`macroSpecsFor`, `touchedRender`), `KeelPatch.ENGINE` in `PatchTypes.PROTOTYPES`, a hand-built KEEL patch in `PatchesTest.onePatchPerEngine` and `PadRecipeTest.handBuiltPatches`, a `DeterminismTest` canary.
- `KeelTest.kt` (20 tests, about 6 s) and `KeelAuditionGenerator.kt` with the `generateKeelAudition` task. The task wrote 71 clips, `manifest.json` and `index.html` under `testkit/keel-audition/`, beside the ten Python-harness clips and `README.txt` already in that folder, which it did not touch.
- Render cost, measured warm on the build machine: 11 to 19 ms for REDUCE and 13 to 23 ms for UPRIGHT per one-shot, against 20 to 60 ms in the harness.

Output stage as specified: `Tide.bandLimit`, `Dsp.decimate`, mean subtracted and a 20 Hz one-pole high-pass, `Dsp.levelTo` to `MELODIC_LOUDNESS_TARGET * v^0.35`, `Dsp.fadeTail`, with synthesis at `RATE * OVERSAMPLE`. Noise is seeded with `Dsp.seedFor("KEEL", MODEL_VERSION, voice, note)`. UPRIGHT uses `Strings.tune`, `Strings.Loop` and `retune`, `Modes.fixed` and `Modes.ring`; no repo helper was unable to express the prototype, so no loop was written inline.

### Where the spec and the prototype disagree, and what was built

1. **SLIDE is linear, not `Dsp.around`.** Sections 8, 11 and 16 name `Dsp.around(SLIDE, -12, 0, 12)`. That map is exponential on each side, and a centre of 0 puts `ln(0 / -12)` in it: NaN at the default SLIDE .5. The prototype's own `around` is linear (`offset = (SLIDE - .5) * 24`). Built as the prototype.
2. **The body gains are compensated.** The prototype called its modal ring with `compensate=True`, so each mode's peak does not depend on its frequency. `Modes.ring` does not, and at 176.4 kHz the 190 Hz mode would outweigh the 1050 Hz mode about five times. Each gain is multiplied by `sin(2 pi hz / rate)` before `Modes.fixed`, then the body is peak-normalised, as the prototype did.
3. **Velocity has a one-point disagreement.** The prototype has no velocity. GRIT's k and REDUCE's index at velocity 1 equal the prototype's. UPRIGHT's pick low-pass follows section 11 (`2 + 2v` kHz), which is 4 kHz at velocity 1; the prototype's fixed 3 kHz is velocity .5 on that law.
4. **UPRIGHT's loop is retuned every 8 internal frames, not every sample,** and only when the pitch has moved more than 2e-5 in ratio (0.035 cent). With SLIDE at .5 it is retuned once. Per-sample retuning gave the same figures at the one corner it was tried on. The loop is built for 0.99 of the glide's lowest pitch, because `retune` can only shorten the ring it was built with.
5. **`isLoop(hold)` is `hold >= .85`; `settled` clamps HOLD to .85.** R1 builds no loop, so HOLD 1 renders exactly what HOLD .85 renders (tested byte for byte, for .85, .9 and 1). `drumClassFor` does not consult `isLoop`: it files TONAL, checked against the rendered frame count on every LENGTH and HOLD corner. The sustain law is `min(1.44 s, 1.15 L + HOLD)` with the envelope held at full for HOLD seconds.
6. **The render cap can cut a loud decay.** At HOLD 1 and LENGTH 1 the 1.44 s cap ends the render 30.0 dB under the peak block for REDUCE and 52.8 dB for UPRIGHT; only the 4 ms fade closes it. Disclosed, not changed: the cap is the spec's, and the alternative (shortening the decay to fit) changes what LENGTH means at high HOLD.
7. **Section 15 risk 3 is redefined.** The wording "sub-band share in the last 20% under the share at 40%" cannot hold, because the grow law raises the sub's share as the harmonics leave. It rose 1.56 times for REDUCE and 1.54 for UPRIGHT with the guard in place, and 1.85 and 2.07 times without it. What the guard prevents is the sub becoming the tail (prototype v2). The test asserts that UPRIGHT's last 20% at WEIGHT 1 keeps under half of its energy below 45 Hz (0.284 built) and that the same probe finds a lone sub without the borrowed level (0.956). The 0.5 line is a majority, not a fitted number.
8. **Section 15 risk 2: "revert the T60 alone" does not fail the probe.** The late-window pitch at WEIGHT 0 stays within 1 cent for T60 .22 s alone, for a 0.3 mix alone and for a 190 Hz gain of .9 alone. The first prototype's body (all three together) fails it on every note: it reads +2355, +1909 and +1195 cents at MIDI 30, 36 and 42, each close to 190 Hz, the body's own mode. The test asserts the combination.
9. **The aliasing bar holds at the harness's top note and not at the register's.** The harness's top note was MIDI 48. At GRIT 1 and SETTLE 1 REDUCE's alias proxy is 5.2e-4 there (harness 5.2e-4), and 2.3e-3 at MIDI 55, over the 1e-3 bar. The test asserts 48 and prints 55. At 55 the output also shows 48 bins over -80 dB re the loudest within 0.95 to 1.0 Nyquist for REDUCE; a drive of 24 on FM is a near-square wave and its own harmonics reach the band edge. UPRIGHT is clean at both notes (1.3e-4 and 7.1e-6).
10. **The section 14 pitch rows were taken at WEIGHT .4, not 0.** The harness's nine renders used the shared macro set (WEIGHT .4); the "WEIGHT 0" in the row label is loose. The Kotlin root test runs at WEIGHT 0 as specified, and both are printed.
11. **The SETTLE centroid test reads rising, not falling.** Section 11 says the centroid at 0.3 s is "monotonic falling in SETTLE". SETTLE sets the half-life, so a higher SETTLE settles later and the note is brighter at 0.3 s. Built and tested rising with the macro, which is falling with settle speed.
12. **The harness used one macro set for every voice** (SETTLE .5, WEIGHT .4, GRIT .2, SLIDE .5, LENGTH .5), not UPRIGHT's section 12 defaults. `KeelTest` uses that set for every number compared below.
13. **Docs follow TIDAL's commit, not section 16 row 11:** the "As built" section, a paragraph at the top of `docs/SYNTH_ROADMAP.md`, and `docs/KEEL.md`. No README engine count change (TIDAL's commit did not make one). No CLI arm in `SynthCommand.kt` (TIDAL's commit added some; R1's brief did not list them).

### Section 14, harness against Kotlin

Python harness v3 against `KeelTest`. Kotlin levels with `Dsp.levelTo` (loudest 200 ms window) and the harness with whole-file RMS, so peaks and the dB distances of macros that change the length differ for that reason.

| Probe | Harness | Kotlin |
|---|---|---|
| Determinism | by construction, not run | byte-identical, both voices, velocity 1 and .4, macro order irrelevant; `DeterminismTest` canary passes |
| Finite, raw internal peak under 2 | 0.94 to 1.00 | 0.921 to 0.988 (harness macros, 3 notes); worst corner, every macro at 1: REDUCE 1.421, UPRIGHT 1.283 |
| Output peak 0.01 to 1 | 0.88 to 0.99 | 0.585 to 0.937 |
| DC under .005 | at most 1.2e-4 | at most 2.8e-4 |
| Root pitch, cents (REDUCE, UPRIGHT) | MIDI 36: -1.1, -9.2 (UPRIGHT harness un-budgeted) | WEIGHT 0, MIDI 30 / 36 / 42: REDUCE +3.2 / +0.8 / +0.8, UPRIGHT -0.2 / -1.1 / -0.7; at WEIGHT .4, MIDI 36: +1.1, -0.5. `Strings.tune` removed UPRIGHT's flatness |
| Root with the sub (documented) | reads f0/2 from WEIGHT .7 (REDUCE, floor 25 Hz) and at MIDI 42 | `Pitch.detect` (40 Hz floor) at MIDI 42, WEIGHT 0 / .4 / .7 / 1: REDUCE +1 / +1 / 0 / -1200, UPRIGHT -1 / -1 / -2 / -1200 cents |
| Never grows, later over attack | 0.176 to 0.282 | REDUCE 0.272 to 0.282, UPRIGHT 0.204 to 0.209 at HOLD 0. At the sustain top: REDUCE 1.049 (grows: the sub rises under a held source), UPRIGHT 0.748 |
| Partials above 0.95 Nyquist | 0 | 0 (both voices, MIDI 30 / 36 / 42, harness macros) |
| Alias proxy, GRIT 1 SETTLE 1 | UPRIGHT 9e-6, REDUCE 5.2e-4 (MIDI 48) | MIDI 48: UPRIGHT 1.3e-4, REDUCE 5.2e-4. MIDI 55: UPRIGHT 7.1e-6, REDUCE 2.3e-3 (over the bar) |
| SETTLE extremes, dB (UPRIGHT, REDUCE) | 5.31, 10.01 | 5.38, 9.71 |
| SETTLE centroid at 0.3 s, SETTLE 0 / .25 / .5 / .75 / 1 | not measured | REDUCE 82 / 120 / 172 / 306 / 327 Hz, UPRIGHT 101 / 141 / 153 / 157 / 159 Hz |
| WEIGHT, share under 45 Hz at WEIGHT 0 / .4 / .7 / 1 | REDUCE 0 / .37% / 1.98% / 5.56%, UPRIGHT .03% to 9.08% | REDUCE 0 / .38% / 1.98% / 5.58%, UPRIGHT .11% / .96% / 4.51% / 11.95% |
| WEIGHT extremes, dB (UPRIGHT, REDUCE) | 1.29, 1.07 | 0.98, 1.10 |
| GRIT extremes, dB (UPRIGHT, REDUCE) | 30.44, 48.66 | 27.57, 46.08 |
| SLIDE extremes, dB (UPRIGHT, REDUCE) | 7.84, 19.98 | 7.96, 17.95 |
| LENGTH extremes, dB (UPRIGHT, REDUCE) | 5.84, 7.94 | 4.18, 3.14 (leveling differs between different-length renders) |
| HOLD extremes, dB (UPRIGHT, REDUCE) | not in the harness | 3.16, 2.19 |
| SLIDE 0 / SLIDE 1, REDUCE, MIDI 36, early / late cents | -657.9 / -1.2 and +2690.6 / +0.9 | -661.8 / -0.8 and +2674.0 / +1.5; SLIDE .5: +0.7 / -0.7 |
| SLIDE 0, UPRIGHT, MIDI 36, early / late cents | not measured | -578.0 / -1.4 |
| Low end, C1, WEIGHT 1: energy under 20 Hz | UPRIGHT 0.19%, REDUCE 0.013% | UPRIGHT 0.001%, REDUCE 0.014% |
| Low end, strongest peak near C1 | 32.5 (UPRIGHT), 32.7 (REDUCE) Hz | 32.63 (UPRIGHT, -4.0 cents), 32.76 (REDUCE, +3.2 cents) Hz |
| Length and class | 0.575 s at LENGTH .5; loop 2.997 s | REDUCE 0.575 s (25358 frames), UPRIGHT 0.630 s at its default LENGTH .55; longest render 1.440 s; every one files TONAL to the frame; no loop in R1 |
| Decay to -60 dB at LENGTH .25 / .5 / .75 | REDUCE .51 and UPRIGHT .39 to .41 at .5 | REDUCE .33 / .51 / .80 s (L .32 / .50 / .79), UPRIGHT .25 / .40 / .61 s |
| Risk 2, late-window pitch at WEIGHT 0, UPRIGHT, MIDI 30 / 36 / 42 | not measured | -0.2 / -1.1 / -0.7 cents; the first prototype's body: +2355 / +1909 / +1195 |
| Risk 3, share under 45 Hz, 40% to last 20%, WEIGHT 1 | not measured | see disagreement 7 |
| HOLD seam | REDUCE 1.1e-6, BELLY 2.2e-6, UPRIGHT 3.2e-5 | not built (R2) |
| Velocity, output target | not in the harness | loudness ratio soft (.3) over hard 0.656 against 0.3^0.35 = 0.656 |

### Findings for the listen and for R2

- **UPRIGHT's velocity law runs the wrong way for brightness.** At velocity .3 against 1 (WEIGHT 0, C2) REDUCE is darker when soft as intended (share over 1 kHz .0062 against .0306). UPRIGHT is brighter when soft (.1179 against .0833; centroid 408 against 349 Hz). The 2.2 kHz string loop absorbs most of the pick low-pass, and GRIT's touch-scaled drive adds low odd harmonics faster than highs. Section 11 already withholds `brightnessOverride` until a sweep proves GRIT monotonic; this is that sweep's first answer.
- **UPRIGHT at MIDI 42 with SLIDE 1 reads an octave high in its late window** (+1188.6 cents, NSDF confidence .88). The second harmonic (184 Hz) is 17 dB over the fundamental because the body's 190 Hz mode sits 45 cents from it. It is the body-steals-the-pitch risk in a corner the harness never rendered; no other tried corner does it (UPRIGHT SLIDE 1 at MIDI 36 and 48, SLIDE .8 at 36, 42 and 48 read the root).
- **The house pitch reader flips to the sub at WEIGHT 1 on MIDI 42** for both voices (`Pitch.detect`, floor 40 Hz, sub at 46 Hz). It does not flip at .7. This is a detector reading, not a hearing: the octave question stays the owner's.
- **`Pitch.detect` returns null on a glide**; the SLIDE early window is read with the harness's NSDF, which the test file carries.
- **REDUCE grows after its attack at the top of the sustain stage** (later over attack 1.049), because the sub rises under a source that no longer decays. It is not a bug in the law; it is what the grow law does when HOLD keeps the envelope up.
- **Open for R2:** the loop region (`LOOP_HOLD_AT`, `renderLoop`, the seam test against the warm-up, `Keys.keelPad`), and whether the alias bar should be met at MIDI 55 (cap GRIT's k on REDUCE at high notes, or accept a near-square's harmonics).
- **Ledger rows now measured in Kotlin:** root pitch for both voices at three notes, determinism, finite and bounded, DC, never-grows (HOLD 0), SETTLE, WEIGHT, GRIT and SLIDE activity, the SLIDE landing, the low end at C1, the length and class line, risk 2's guard. **Still not heard:** all of it, including the settle as one gesture in Kotlin, the bowed hold (not built), and the octave question.

### As built: WEIGHT as body (2026-10-26)

Kotlin on `claude/keel-r1`, not heard. The Python version of this WEIGHT was heard ("Sounds good"); nobody has played the Kotlin one. Every number below comes from `KeelTest` output (the `KEEL body`, `KEEL risk11`, `KEEL finite` and `KEEL render time` lines) unless it says otherwise.

**What changed in the code.**

- `Keel.kt`: the sub is gone. Removed `subLevel`, `SUB_CEILING`, `sourceEnvelope` with `SOURCE_ENV_HZ` and `SOURCE_ENV_HEAD_SECONDS`, the low fade, and `Options.borrowSourceLevel`. The body options (`bodyT60Scale`, `bodyMix`, `bodyLowGain`) stay.
- New `Keel.weightBody(source, tr, s, weight)`, called after GRIT and before the amplitude envelope. At WEIGHT 0 it returns the source array itself. Otherwise it implements section 9 as written: each of the fundamental and overtones 2 to 4 is demodulated on the phase track (`x e^(-2 pi i k phase)`) and averaged by a moving mean exactly one period long, taken twice, through prefix sums of the real and imaginary parts. The period is per sample, `P(i) = round(INTERNAL_RATE / tr.f[i])`, `mean[i] = (c[i+1] - c[i+1-P(i)]) / P(i)`, and the mean of the samples so far while `i + 1 < P(i)`. The gain is `boost = 10^(9 dB w / 20)` with `w = WEIGHT (0.5 + 0.5 (1 - s))`, the floors are `-3, -6, -9 dB` times `w A_1 boost`, only the positive difference is added, and the added overtone takes the phase of `u_k = C_k + 0.02 |C_1| (C_1 / |C_1|)^k`. Everything is in Double. It takes one sin and one cos per sample; overtones 2 to 4 come from the fundamental's by complex products. Every loop calls `stopIfUnwanted()` every 8192 samples.
- `Keel.Track`, `track` and `settleCurve` became `internal` so the tests can call `weightBody` on a synthetic signal.
- The runaway guard in `render` is `RAW_PEAK_BOUND = 16` on the mix before the output stage, not 2 (deviation 2).
- `MODEL_VERSION` stays 1. KEEL is a `PROTOTYPES` entry with no saved presets and no picker entry, so no saved sound changes meaning. A render at WEIGHT 0 is also unchanged: 60 renders (both voices, MIDI 24, 30, 36, 42 and 55, touch 1 and .4, three macro sets) hashed to the same bytes before and after the change (a scratch test, not kept in the suite). A render above WEIGHT 0 sounds different, and none of those is saved anywhere.
- `KeelTest`: `energy under 45 Hz grows monotonically with WEIGHT` became `WEIGHT fills in the body, ...`; `risk 3 ...` and its helper `lateOverMid` are gone; `risk 11 ...` is new, with `the band meter ...` and `WEIGHT 0 hands the source back untouched, and the body law holds on steady tones`. The last one checks, against a DFT over whole periods that is independent of the code under test, the +9 dB on a bare fundamental, the three floors with their phases, the half strength at the attack (`s = 1`) and an existing overtone raised by the same 9 dB in its own phase. The SLIDE test lost its 45 Hz share check; risk 11 takes its place. `every other macro changes the sound` passes unchanged (WEIGHT extremes 2.45 dB for UPRIGHT and 2.07 dB for REDUCE, against 0.98 and 1.10 for the sub). The 22 tests in the file take about 7.5 s.
- `KeelAuditionGenerator.kt`: the start section is the WEIGHT check, nine clips (REDUCE MIDI 30, REDUCE MIDI 42, UPRIGHT MIDI 36, each at WEIGHT 0, .5 and 1, LENGTH .8) under the one question "Does WEIGHT make the note heavier?". The octave wording and the headphones note are gone, and so are the sub labels in the deeper sections. `generateKeelAudition` wrote 71 clips, `manifest.json` and `index.html`; every file in the manifest exists. Three `START/*_w7.wav` files from the first round were left over (the generator does not clear old output), so they were deleted. `harness-v3/` was not touched.
- `docs/KEEL.md`, the KEEL paragraph in `docs/SYNTH_ROADMAP.md`, the `KeelPatch` KDoc, the `generateKeelAudition` task description and the KEEL line in `PatchTypes.kt` no longer describe a sub or the octave question.

**Kotlin against section 9's harness table.** Late window 0.45 to 0.95 s, change in dB against WEIGHT 0, LENGTH .8, SETTLE .5, GRIT .2, SLIDE .5, touch 1, levelled with `Dsp.levelTo`. The bands are read with a 4th-order Butterworth band-pass (8 poles, scipy's `butter(4, [lo, hi], "band")` in design), causal over the whole clip. Each cell is harness / Kotlin.

| Note | WEIGHT | 60 to 120 Hz | 120 to 400 Hz | 400 to 4000 Hz |
|---|---|---|---|---|
| UPRIGHT C2 | .5 | +3.2 / +2.8 | +3.0 / +3.3 | -0.5 / +0.1 |
| UPRIGHT C2 | 1 | +6.1 / +5.1 | +6.3 / +6.7 | -1.2 / **+1.8** |
| REDUCE F#2 | .5 | +2.7 / +2.6 | +2.5 / +2.5 | -0.8 / -0.8 |
| REDUCE F#2 | 1 | +4.8 / +4.8 | +4.6 / +4.6 | -1.1 / -1.2 |
| REDUCE F#1 | .5 | +2.7 / +2.7 | +2.0 / +2.0 | -1.2 / -1.2 |
| REDUCE F#1 | 1 | +4.8 / +4.8 | +4.2 / +4.2 | -2.9 / -2.9 |

Early window (0.10 to 0.45 s), WEIGHT 1, 120 to 400 Hz: Kotlin +3.9 (UPRIGHT C2), +2.9 (REDUCE F#2), +2.4 (REDUCE F#1); the harness had +3.8, +2.9 and +2.4. The two REDUCE rows agree with the harness to within 0.1 dB in every cell, early and late, and their output peaks agree (0.569 and 0.583 at WEIGHT 1, against 0.569 and 0.584). REDUCE is the same source in both. UPRIGHT is not (deviation 4).

Root pitch at WEIGHT 1, `Pitch.detect` over 0.45 to 0.70 s: UPRIGHT C2 -0.8 cents (confidence .85), REDUCE F#2 +1.6 (.91), REDUCE F#1 +0.6 (.82). At WEIGHT 0 the same reader gives -1.5, +1.0 and +0.6. The root at MIDI 42 stays within 1 cent across WEIGHT 0, .4, .7 and 1 for both voices. Risk 11, SLIDE 0 and SLIDE 1 at WEIGHT 1, MIDI 36: finite, raw peak under 2 on the scaled mix (unscaled: REDUCE 2.41 and 2.17, over the section 14 bar for the reason in deviation 2; UPRIGHT 1.63 and 1.62), output peaks 0.685, 0.586, 0.769 and 0.918, and the late pitch is +1.3 and +1.6 cents (REDUCE), -0.8 and -1.1 cents (UPRIGHT).

Render time, warm, LENGTH .8, this machine, not asserted: REDUCE 18 ms at WEIGHT 0 and 34 ms at WEIGHT 1; UPRIGHT 21 ms and 36 ms. The body costs about 15 ms.

**Deviations from section 9 and the Python reference, and what is open.**

1. **The top-band bar is read with a brick-wall meter** (decided 2026-10-26, after this record was first written). The bar asks whether WEIGHT adds anything above 400 Hz. With the Butterworth band-pass, UPRIGHT C2 at WEIGHT 1 read +1.8 dB and failed, but before levelling its raw 400 to 4000 Hz energy is identical at WEIGHT 0 and 1. UPRIGHT's late top band sits 25 dB under its body band, so the band-pass's skirt (-17 dB at 262 Hz) passes the boosted 4th overtone, and levelling, which lowers the whole clip by 3.0 dB, is what the brick-wall FFT meter reads there: -3.0 dB. The bar stays at +0.5 dB; only the meter changed, and the Butterworth reading is still printed beside it. The table's **+1.8** is the Butterworth reading.
2. **The runaway guard is a peak of 16 on the mix, not 2.** Without a change `render` throws ("excessive raw state") for REDUCE at WEIGHT 1 on the very clips the body test and the audition need. The Python reference itself peaks over the section 14 bar: REDUCE, WEIGHT 1, LENGTH .8, raw peak 2.44, 1.97, 2.07, 2.14 and 2.23 at MIDI 24, 30, 36, 42 and 55, and the Kotlin port matches those to three decimals at SLIDE .5. Over every 0 or 1 setting of SETTLE, GRIT, SLIDE, LENGTH and HOLD at WEIGHT 1 (32 settings, MIDI 24, 36 and 55, touch 1 and .5, both voices: 384 renders) the worst unscaled peak was 6.7 (REDUCE, MIDI 24, touch .5, SETTLE 0, GRIT 1, SLIDE 1, LENGTH 0, HOLD 1), and 600 random settings reached 6.9 (scratch tests, not kept; the 6.7 corner is kept in `KeelTest`). A settled, held, driven note at WEIGHT 1 is a sine plus three floored overtones that peak together, so this is the law and not a fault. The first version of this round scaled the mix by 0.125 to keep the old bar of 2 (exact, being a power of two, and inaudible, since everything after it is linear up to `Dsp.levelTo`). It was replaced by the explicit `RAW_PEAK_BOUND = 16`, about twice the worst measured, so the code states the bound it checks; the output is identical either way. The tests hold the harness macro set (WEIGHT .4) to the section 14 bar of 2 (0.964 to 1.213) and the corners to 16 (everything at 1: 3.137 REDUCE, 2.366 UPRIGHT; the sweep-worst corner 6.7).
3. **The period follows SLIDE (as asked); the Python's is fixed.** At SLIDE .5 the two are the same number for every sample, and REDUCE agrees with the Python as above. During a glide they differ by design: REDUCE, MIDI 42, SLIDE 0, WEIGHT 1 has a raw peak of 1.980 in Kotlin and 1.717 in the Python. Risk 11 is therefore the Kotlin-only claim, and it holds on the root pitch, not on the glide itself, which was not heard.
4. **UPRIGHT's numbers are not expected to match the harness.** The Kotlin string is `Strings.Loop` with a closing low-pass and compensated body modes; the harness string is its own waveguide. Its body-band cells are within 1.0 dB of the harness (-1.0 to +0.4) and its top band at WEIGHT 1 is 3.0 dB above it (item 1).
5. **The meter is written in the test, not scipy's.** scipy is not installed here, so the Butterworth band-pass is written in the test and checked by `the band meter ...` (-3.01 dB at both band edges, unity at the warped centre, a centre tone passes at unity). The REDUCE agreement above is the cross-check against the harness's own filter. For the same reason the Python render was run with scipy stubbed out (REDUCE needs none of it) to compare raw peaks, not bands.
6. **The audition's UPRIGHT clips use the harness macros** (SETTLE .5, GRIT .2) and not UPRIGHT's defaults (SETTLE .45, GRIT .15), so the nine start clips are the nine rows measured above. The deeper sections use the voice defaults as before.
7. **The growing-window start is kept.** In the first period the one-period mean has fewer samples than a period, so the estimates are rough and the added body can overshoot while the envelope is still rising (REDUCE at MIDI 24, WEIGHT 1: raw peak at 1.7 ms). The Python does the same and was the version that was heard.
8. **The 0.99 peak ceiling engages at the bottom of the register.** Over 60 renders (both voices, MIDI 24, 27, 30, 36 and 42, WEIGHT 0, .5 and 1, LENGTH .5 and .8, harness macros; a scratch test, not kept) `levelTo`'s peak limiter held 5 renders under the loudness target, all at WEIGHT .5 or 1: REDUCE MIDI 24 at WEIGHT 1 (-0.7 dB, LENGTH .5); UPRIGHT MIDI 27 at WEIGHT .5 (-1.2 dB) and at WEIGHT 1 (-2.7 dB at LENGTH .5, -1.1 dB at LENGTH .8); UPRIGHT MIDI 30 at WEIGHT 1 (-0.7 dB). No WEIGHT 0 render is held. The body raises the crest factor of a low note. No test catches it (the house loudness probe accepts a peak of 0.95 or more; the C1 test prints the REDUCE peak at 0.990), so kit levels at the low end will not match with WEIGHT up. Disclosed, not changed.

**R1 rows this supersedes.** The sub rows of section 14 (root with the sub, WEIGHT share under 45 Hz, risk 3) and the R1 findings about the house pitch reader flipping to the sub no longer apply. Rows that moved only because the harness macro set carries WEIGHT .4: the never-grows ratio at the top of the sustain stage is 1.240 for REDUCE and 0.842 for UPRIGHT (was 1.049 and 0.748, not asserted); GRIT extremes 41.22 and 27.50 dB (was 46.08 and 27.57); SETTLE extremes 9.73 and 5.61 dB (was 9.71 and 5.38); SLIDE extremes 17.51 and 8.00 dB (was 17.95 and 7.96); the alias proxy for REDUCE at GRIT 1 and SETTLE 1 is 4.1e-04 at MIDI 48 and 1.8e-03 at MIDI 55 (was 5.2e-04 and 2.3e-03, still over the bar at 55); the level of the cut at HOLD 1 and LENGTH 1 is -27.6 dB for REDUCE and -49.6 dB for UPRIGHT (was -30.0 and -52.8). Not heard: all of it, including WEIGHT in Kotlin.

### As built: R2, the held loop and the keygroup (2026-10-26)

Kotlin on `claude/keel-r1`, not heard. Every number comes from `KeelTest` (the `KEEL held`, `KEEL zone` and `KEEL hold` lines), `KeelPadMakerTest`, the `generateKeelKeys` output, or scratch tests run for this round and not kept; each says which.

**What was built.**

- `Keel.kt`:
  - `LOOP_SECONDS = 3.0` and `planLoop(noteHz)`: `periods = round(3 f)`, `frames = round(periods RATE / f)`, and the note is fitted to the loop, `hz = periods RATE / frames`. That is at most half a frame in 3 s off the note, 0.007 cents. At C2 the loop is 132152 frames holding 196 periods.
  - `heldLoopStart`: the warm-up is ten half-lives of the settle clock, and for UPRIGHT at least six of the string's time constants (`period / (1 - 0.98)`, 1.53 s at C1). Then come `HELD_LEAD_SECONDS = 0.25`, over which the settle clock has stopped (deviation 1).
  - `synthesize(..., held = true)`: the fitted pitch with no slide, the envelope up after its 3 ms attack, the settle clock stopped, and UPRIGHT bowed. The bow is a seeded noise one loop long at the internal rate, tiled, low-passed at 900 Hz (a 2-pole `TptSvf`), fed in at 0.04 from the first sample, with the loop gain at 0.98, as section 10 and the heard harness had it. The render runs 0.25 s past the loop's end.
  - `renderHeld` returns the conditioned audio cut at the loop's end, its `loopStart` and its seam.
  - `renderLoop` returns the loop alone, levelled as a one-shot (`MELODIC_LOUDNESS_TARGET * v^0.35`). It throws if the seam is at or over `Keys.MAX_SEAM_ERROR`.
  - `render` routes HOLD at or above `LOOP_HOLD_AT` (.85) to `renderLoop`, and `drumClassFor` files it LOOP.
  - `settled` no longer clamps HOLD. A one-shot's hold is `min(HOLD, SUSTAIN_TOP)`, so a probe at HOLD 1 is the R1 sustain top. `scramble` caps HOLD at `ROLL_HOLD_TOP = 0.8`, short of the loop.
  - `renderKey` makes one keygroup zone (deviation 4).
  - A `cancelled` lambda is asked wherever an interrupt was.
- `weightBody` is streamed: sixteen running means one period long replace fourteen full-length arrays. A 15 s held zone would otherwise hold about 360 MB of them. Sixty one-shot renders (both voices, MIDI 24, 36 and 55, five macro sets including the glides, touch 1 and .4) hashed byte-identical before and after (scratch test). It is also faster: REDUCE at WEIGHT 1 renders in 27 ms, against 34.
- `Keys.kt`: `keelPadMidis()` (MIDI 24 to 48 every 3 semitones, nine zones) and `keelPad(voice, macros, midi, cancelled)`, which centres TUNE, calls `Keel.renderKey` and `requireSeam`. They sit at the end of `object Keys`, away from the BALLAST branch's insertion before ORPHICA, so the two branches do not collide there.
- `shell/.../KeelPadMaker.kt`: `GlintPadMaker`'s and the BALLAST branch's shape (RELEASE 0.1 to 1.5 s, `Spec`, `spec`, `zoneMidis`, `renderZone`, `assemble` with edge zones at ±9 and inner zones at ±1, one 0..127 layer, `export`, `preview` of the middle zone, C2). Nothing in the app calls it: KEEL has no picker entry (R4).
- `KeelPadMakerTest` (8 tests, REDUCE's nine zones as one per-class fixture) passes in 2.0 s at the default 512 MB test heap. It covers the tiling, the loop marker in the MPC 2 `.xpm`, the MPC 3 `.xty` and the phone's sidecar, the phone's own player holding through the wrap and going silent within RELEASE, no clobber, the preview, the shared layout and the RELEASE range.
- `KeelKeysGenerator` and `:shell:generateKeelKeys` write `testkit/keel-keys/` (gitignored). That is four instruments (each voice at its defaults and at WEIGHT 1) through the production exporter, plus nine previews through `InstrumentEngine`. The held previews last until each zone's loop has come round twice (UPRIGHT C1 to 16.9 s), not a fixed 9 s.
- `KeelTest` has 28 tests and takes 43 s (it was 22 tests and 7.5 s).
  - Rewritten from R1: the HOLD test (the loop region now renders one loop, the planned frames, filed LOOP by `drumClassFor` and by `Classifier`), and the one-shot class, cap, never-grows and register tests, which use HOLD .84, the top of the sustain stage.
  - New: the seam on every zone at the defaults and on three zones at WEIGHT 1, SETTLE 0 and SETTLE 1; the loop's pitch; a zone's loop equals the HOLD loop up to one gain; the zone levels and the lift; no swell from the lift; and the held guards (reproducible, cancelled throws, register refused, raw bound at the held corners).
- `DeterminismTest`: a held-loop canary.
- `KeelAuditionGenerator`: the HOLD sweep stops at .8 (its .85 would now be a loop under a sustain label).
- `MODEL_VERSION` stays 1. HOLD at .85 and above changed meaning, from the sustain top to the loop, but KEEL has no saved presets and no picker entry.

**Measured.**

| Probe | Result |
|---|---|
| Seam, every zone at the defaults plus three zones at WEIGHT 1, SETTLE 0 and SETTLE 1 (`KeelTest`) | REDUCE at most 1.6e-13, UPRIGHT at most 4.1e-5, bar 1e-3. The harness had 1.1e-6 and 3.2e-5 with the settle running |
| Loop pitch, whole-loop DFT within 50 cents (no window: the loop holds whole periods), C1 / C2 / C3 | REDUCE 0.00 / 0.00 / 0.00 cents. UPRIGHT -2.43 / -0.00 / -1.28 cents. Over all nine zones (scratch): -3.0 to +3.7 cents, set by `Strings.tune` at g 0.98. `Pitch.detect` returns nothing at C1 |
| Zone loop against `renderLoop` after one gain | worst difference 5.9e-8 to 8.7e-8 of the peak |
| Zone length, defaults | REDUCE 5.7 s every zone, so 51.3 s per instrument (4.5 MB at 16-bit). UPRIGHT 5.5 s (C3) to 12.4 s (C1), 74.8 s (6.6 MB). The exporter writes 24-bit: the four instruments take 33 MB |
| Zone length, SETTLE 1 | 15.25 s for both voices (12.25 s warm-up), 137 s per instrument, about 12 MB at 16-bit |
| Peak heap, one held render (scratch, MemoryMXBean) | 32 to 156 MB, worst UPRIGHT at SETTLE 1 and WEIGHT 1 |
| Render time, one instrument of nine zones (generator, this machine, serial) | REDUCE 1.3 to 1.4 s, UPRIGHT 2.2 to 2.3 s |
| Raw internal peak, held corners | REDUCE 6.54, UPRIGHT 3.99, bound 16 |
| Zone attack, house loudness over the first 0.6 s, against the target | 0.0 dB in every zone measured, except REDUCE WEIGHT 1 MIDI 24 at -0.4 dB (the 0.99 limiter) |
| Sustain under the attack, defaults, before and after the lift | UPRIGHT C1 16.2 → 6.0, C2 12.6 → 6.0, MIDI 42 4.9 → 4.9 (not lifted), C3 7.4 → 6.0 dB. REDUCE 2.1 (C1) to 0.0 (C3), never lifted. At WEIGHT 1 REDUCE's loop sits 3.0 to 3.5 dB above its attack, and is not lowered |
| Largest rise above the running minimum after 0.6 s, UPRIGHT C1 / D#1 / F#1, lifted against as rendered | defaults 3.8 / 6.8 / 3.3 against 3.8 / 6.8 / 3.3 dB. WEIGHT 1 4.7 / 8.9 / 3.9 against the same. The lift adds nothing; what remains is the bow's own breathing |

**Deviations from section 10, and what is open.**

1. **The settle clock stops 0.25 s before the loop.** Section 10 lets it run, with the warm-up long enough that what is left does not matter. Stopped, the held source repeats exactly with the loop, so REDUCE's seam is rounding error (1e-13). The warm-up becomes a choice about how settled the held note is, not a condition of the seam. The 0.25 s covers the seam's 256 frames, WEIGHT's two one-period means (61 ms at C1) and the 20 Hz high-pass.
2. **The warm-up is `max(10 h, 6 tau) + 0.25 s`, with no cap.** Section 10 had REDUCE at `max(2.5, 10 h)`, 2.5 s at the defaults, and UPRIGHT at 4.5 s at C2, to be derived from the loop gain. Built, REDUCE at the defaults starts its loop at 2.70 s. UPRIGHT starts at 2.54 s (C3) to 9.42 s (C1), because six time constants at g 0.98 are 300 periods. At SETTLE 1 both voices start at 12.25 s. A cap (6 s, say) would leave SETTLE 1's held note 3% unsettled (REDUCE's index 0.29 against 0.08) and would cut the instrument from 12 to about 8 MB. Not capped: nobody has objected to the size.
3. **SLIDE, LENGTH and HOLD play no part in a held note**, as section 11 says of HOLD's top. TUNE is centred in a zone, so the zone's MIDI is the note.
4. **A key zone is levelled on its first `KEY_LEVEL_SECONDS = 0.6`, and UPRIGHT's sustain is lifted.** A zone is levelled on its attack, as BALLAST's branch does, so short presses match across zones.
   - **Why the lift.** UPRIGHT's bow at 0.04, the level the loop was heard at, left the sustain 16.2 dB under the pluck at C1 and 7.4 dB at C3 (house loudness, defaults). A held low key would sound like the note dying.
   - **A louder bow was tried and rejected.** The sweep at C1, C2 and C3 (scratch) at bow 0.04, 0.08, 0.16 and 0.32: the gap fell from 16.2 to 4.5 dB at C1, and from 7.4 to 2.0 dB at C3. It saturated because GRIT's tanh starts driving the loop hard, which changes the loop's sound.
   - **A deterministic bow was tried and reverted.** The bow's lines at the note's harmonics were set to the noise's average strength. It moved the gaps by at most 2 dB, so the gap is not line-to-line randomness: it is the curing low-pass at `1.5 f` passing a band of the bow that widens with pitch, plus the house loudness's low discount.
   - **What `renderKey` does instead.** After the render, it raises the note by a gain that only rises. Wherever the note's own house-weighted level (200 ms, centred) has fallen under the lifted sustain, the gain holds it there, up to the loop's lift, and it joins the loop's constant gain on a straight line over the last 0.2 s before the clock stops. The loop gets one gain, so its seam is untouched (the zone loop equals HOLD's loop to 1e-7).
   - **The first version modelled the gain on the string's time constant.** It dipped 10 dB at C1 near 2 s and swelled back, because the curing low-pass takes the attack's brightness away at the settle half-life, much faster. The table above is the second version.
   - **What is shape, not measurement.** `KEY_SUSTAIN_GAP_DB = 6` is unheard, and so is the choice to lift UPRIGHT and never lower a loop. (Heard 2026-10-27 as about right; now 7.5 dB on the steady loop, see the R2 follow-up.) REDUCE's loop sits at most 5.8 dB under its attack anywhere measured (WEIGHT 0, SETTLE 1, C1) and would never be lifted.
5. **UPRIGHT's bow breathes, by more than the heard clip showed.** A 3 s tiled noise has a line every 0.33 Hz, and at g 0.98 the string's resonance is 0.2 Hz wide at C1 and about 0.8 Hz at C3, so a few lines beat inside each harmonic. Over one loop (house level, 0.5 s steps, defaults):
   - C2 dips about 5 dB once. That is the loop heard in harness v3 and judged to belong.
   - MIDI 45 dips 8 to 9 dB twice.
   - MIDI 42 and 48 swing about 4 dB.

   This is the loop as designed, not the lift. If the owner hears a pump, the levers are a longer loop (more lines per resonance), a lower loop gain at the top of the register (a wider resonance, and a shorter warm-up), or a bow that is periodic at the note's period plus a quieter noise. (Heard as a pump 2026-10-27; the third lever was built, see the R2 follow-up.)
6. **The body's fixed modes make some notes louder.** UPRIGHT's loop at MIDI 42 and 45 sits 4.9 and 3.4 dB under the attack, against 6 to 16 dB elsewhere, because their second and fifth harmonics land on the 190 Hz and 540 Hz modes. They are under the lift's line, so they keep their own level. After the lift, neighbouring zones' sustains differ by at most 2.6 dB at the defaults (scratch).
7. **One velocity layer, at full touch**, as the BALLAST branch and the other held makers. Layers are R4.
8. **Not done here: the hardware load check.** The four instruments are in `testkit/keel-keys/mpc/` for the MPC. The phone path is exercised only by `KeelPadMakerTest` (`InstrumentStore` and `InstrumentEngine`), because KEEL has no picker entry to make one on the device.
9. **Still open from R1:** UPRIGHT's soft touch reads brighter, REDUCE is over the alias bar at MIDI 55, UPRIGHT reads an octave high at MIDI 42 with SLIDE 1, the low notes are held under the loudness target by the limiter at WEIGHT 1, risk 10 (boom at WEIGHT's top) is unanswered, and SLIDE is unanswered. The keys page asks risk 10 again, on the HEAVY instruments.

### R2 follow-up: the bow locked to the string (2026-10-27)

**What was heard.** The KEEL Keys page answers: Q1 ABOUT RIGHT (UPRIGHT's sustain level, clips 01 and 02), Q3 STILL KEEL (WEIGHT 1, clips 06 to 09, risk 10's boom question), and Q2 A PUMP. The Q2 text named UPRIGHT's breathing bow and asked whether it read as a pump.

**What the pump was.** Measured on R2's first zone files (house weighting, 200 ms windows every 50 ms, the loop tiled three times; the dip is the loop's mean level less the lowest window between 0.6 s and the loop):

| UPRIGHT zone | C1 | D#1 | F#1 | A1 | C2 | D#2 | F#2 | A2 | C3 |
|---|---|---|---|---|---|---|---|---|---|
| Loop swing, dB | 2.6 | 4.3 | 3.4 | 3.2 | 6.7 | 8.2 | 11.2 | 12.5 | 11.3 |
| Press dip under the loop, dB | 2.4 | 4.7 | 1.4 | 1.9 | 4.8 | 5.0 | 4.7 | 7.8 | 1.6 |

REDUCE's loops are flat (0.3 dB at most) and REDUCE is unchanged. With the settle stopped, the held UPRIGHT is close to the string's fundamental alone. A noise tiled one loop long puts its energy on lines 0.33 Hz apart, and the resonance at the fundamental, `f (1 - g) / pi` wide, takes in more of them the higher the note. Their random sum beats, the loop repeats the beat every 3 s, and the same beat, running from the first sample, made each press dip under the loop and swell into it. The approved harness clip 10 (C2, the loop only, no attack) swung 4.9 dB. The Kotlin C2 zone swung 6.8 dB, and nothing above C2 had been heard.

**What was built: `Keel.heldBow`.** This is R2 deviation 5's third lever: a drive locked to the string's period, as a bow's stick-slip is, with a trace of noise over it.
- **The locked part.** Every harmonic of the plan's pitch up to 3.6 kHz, at the bow filter's 12 dB/octave level. It is tabulated over one cycle (4096 points, read linearly) and read at `(periods i mod frames) / frames`, so it closes on the loop exactly.
  - Its fundamental carries the average power the noise bow's lines put into the resonance, `2 sigma 0.04 sqrt((1 + M) / frames)`, so the held level is the one heard.
  - Each harmonic starts in the pluck's own phase (the burst's projection on it), so the bow builds the note the pluck leaves instead of cancelling it on the way in.
  - Ablated (scratch): with seeded random phases instead, the press dips 1.6 dB at C1, 2.8 dB at D#1 and 1.3 dB at C1 with WEIGHT 1, against 0.3, 0.1 and 0.5 dB aligned. That set the pump test's first dip bound, 1.5 dB at hair .06; at the hair chosen the check moved to the hairless bow ("What changed after it").
- **The hair.** It is the old bow's seeded noise, low-passed round the loop in two passes with the second kept. It is scaled by `hair 0.04 sqrt((1 + M) / M)`, so its beat against the steady note is `hair` RMS at the fundamental at every pitch. Here `M = x coth x - 1` is the off-line share of the resonance, with `x = (1 - g) f L / 2`.
- **The amount.** `BOW_HAIR` was proposed at .06. The bow page put 0, .06 and .15 to the owner at C2 and A2, against the files as heard, and the owner chose .15 (below).

**Measured (Kotlin):** the hair sweep (scratch), `KeelTest`'s new pump test (every zone at the defaults and at WEIGHT 1; its bounds are under "Heard" below), and the regenerated MPC files.

| Hair | Swing C1 / C2 / A2 / C3, dB | Dip C1 / C2 / A2 / C3, dB |
|---|---|---|
| 0 | 0.3 / 0.1 / 0.0 / 0.1 | 0.2 / 0.0 / 0.0 / 0.0 |
| .06 | 0.6 / 0.6 / 1.5 / 1.2 | 0.3 / 0.2 / 0.8 / 0.3 |
| .15 (default, heard) | 1.0 / 1.2 / 3.5 / 3.0 | 0.4 / 0.4 / 2.2 / 1.4 |

- **The default across the register.** At .15 the nine zones swing 1.0 to 3.5 dB (C1 1.0, D#1 1.9, F#1 1.2, A1 2.2, C2 1.2, D#2 3.0, F#2 3.3, A2 3.5, C3 3.0) and dip at most 2.2 dB (A2). At WEIGHT 1 they swing 1.3 to 3.7 dB, the most at D#2. At .06 they swung 0.6 to 1.5 dB.
- **Why the low zones swing less.** A low zone's level is spread over more overtones, and they beat independently.
- **Seams** are at most 4.8e-5 (4.3e-5 at the defaults).
- **Pitch.** The loop's pitch is now the plan's: without the hair, UPRIGHT reads 0.00 cents at C1, C2 and C3, where the noise bow read -3.0 to +3.7. With the hair at .15 the whole-loop reading is -0.53 cents at C1, 0.00 at C2 and -0.03 at C3: the hair's lines within 50 cents of the note pull the power-weighted reading, most at C1, where 50 cents is 0.9 Hz. The test holds the locked bow alone to 0.05 cents and the shipped loop to 1.
- **Render time.** An UPRIGHT instrument renders in 2.2 to 2.4 s.

**The sustain gap is 7.5 dB.** The ABOUT RIGHT was given to loops that swung and were lifted on their loudest window to 6 dB; their mean sat 7.3 dB (C1) and 7.7 dB (C2) under the attack. At .06 the locked loop was nearly steady, its loudest window close to its mean, so `KEY_SUSTAIN_GAP_DB = 7.5` kept the level that was heard. At .15 the loop moves up to 3.5 dB, and lifted on its loudest window its mean sits 8.0 to 8.9 dB under the attack; that is what the bow page played, and the owner heard it as about right again. As rendered, the sustain sits 18.8 dB under the pluck at C1, 14.2 dB at C2 and 9.3 to 10.5 dB at F#2 and C3, so every zone at the defaults is lifted.

**Disclosed, not fixed.**
- **A2 at WEIGHT 1.** Its sustain's mean sits 2.2 dB under its attack, and C3's 6.1 dB (at .06: 2.6 and 5.9). WEIGHT's body fills in where A2's harmonics meet the 190 Hz mode, above the lift's line, so the zone keeps its own level. The noise bow did the same: A2 averaged 4.1 dB under its attack, swinging 13.8 dB.
- **The hair is heard at C2 and A2 only.** The other seven zones and WEIGHT 1 with the locked bow are measured, not heard.

**Heard (bow page answers, 2026-10-28).** Q1 MORE HAIR, over still and a little; Q2 NO PUMP, in the one picked, at C2 and A2; Q3 ABOUT RIGHT. Eight clips, C2 and A2 each as first heard, still, a little and more, assembled from the zone files (the press, the loop twice, a 0.6 s release), not played through the phone's sample player. The owner first reported that only clips 01 and 02 played; the served files matched the local ones byte for byte and all eight played in desktop Chromium, so the cause was not found, and the answers, which pick clips 04 and 08's bow, came back after.

**What changed after it.**
- `BOW_HAIR = 0.15`.
- The pump test checks two things. As shipped, it holds swing to 4.0 dB and dip to 3.0. That is a guard set just above what was heard as no pump (A2 swings 3.5 and dips 2.2), not a pump threshold: the noise bow, heard as a pump on the keys page as one answer for the whole page, swung from 2.6 dB at C1 to 12.5 at A2, and the guard still fails it at six of nine zones. Without the hair, it holds swing to 0.5 dB and dip to 1.0, where the locked part alone measures 0.3 and 0.2.
- Ablated again at hair 0 (scratch): with random phases the press dips 1.5 dB at C1, 3.1 at D#1 and 1.2 at F#1, and C1 at WEIGHT 1 no longer closes (seam 1.4e-3), so the hairless bound catches it. As shipped, the same ablation dips 1.9 and 2.7 dB, inside the 3.0 bound. That is why the hairless check exists.
- `testkit/keel-keys/` regenerated at .15.

**Not heard:** the locked bow at C1, D#1, F#1, A1, D#2, F#2 and C3; WEIGHT 1 with the locked bow; the regenerated files on the MPC.

### As built: R3, the presets, the kit and the BELLY question (2026-10-29)

Round R3, 2026-10-29, on the same branch. Nothing in it has been heard. Every number is from a Kotlin render (`KeelPresetsTest`, or a scratch reading test deleted at the end of the round) unless it says otherwise.

**What was built.**
- `KeelPresets`: six per voice, every root C2 and every note in C minor pentatonic (the kit's key). KEEL left `PatchTypes.PROTOTYPES`; `Presets.forVoice` and `Presets.all()` serve the roster.
- `KeelPresetsTest`: the house roster contract (`MercuryPresetsTest`'s shape) plus the roster's own claims, listed under "What the tests hold".
- `SynthKits.keel()`: A01 to A08 walk C minor pentatonic up from C2 on REDUCE at its defaults; A09 to A16 are presets, the two loops last. Every pad is dry and filed by `Keel.drumClassFor`.
- `KeelKitGenerator` and `:synth:generateKeelKit`, writing `testkit/keel-kit/` (OVEN's generator shape: MPC 3 export with the MPC 2 twin, the `kit.json` sidecar, read-back, `pads.tsv`, README, the 25 MiB budget).
- A KEEL starter on the phone's new-kit menu (`StarterKits`, after BALLAST, key C minor pentatonic). The phone picker is still R4.

**The roster.**

| Voice | Preset | Note | SETTLE | WEIGHT | GRIT | SLIDE | LENGTH | HOLD | Filed | The classifier hears |
|---|---|---|---|---|---|---|---|---|---|---|
| REDUCE | ROUND FLOOR | C2 | .5 | .45 | .15 | .5 | .5 | .1 | TONAL | PERC |
| REDUCE | QUICK SINK | G1 | .15 | .55 | .2 | .5 | .4 | 0 | TONAL | PERC |
| REDUCE | LOW HULL | C1 | .3 | .75 | .1 | .5 | 1 | .8 | TONAL | TONAL |
| REDUCE | GROWL LINE | F1 | .7 | .5 | .65 | .5 | .55 | 0 | TONAL | PERC |
| REDUCE | SCOOP UP | D#2 | .4 | .4 | .2 | .38 | .5 | 0 | TONAL | PERC |
| REDUCE | SETTLED HUM | C2 | .5 | .5 | .15 | .5 | .5 | 1 | LOOP | LOOP |
| UPRIGHT | WARM GUT | C2 | .4 | .4 | .1 | .5 | .55 | 0 | TONAL | PERC |
| UPRIGHT | MUTED THUMB | A#1 | .25 | .45 | .05 | .5 | .2 | 0 | TONAL | PERC |
| UPRIGHT | LONG NECK | D#1 | .6 | .4 | .1 | .5 | .85 | .2 | TONAL | PERC |
| UPRIGHT | BRIGHT PICK | G2 | .8 | .25 | .2 | .5 | .45 | 0 | TONAL | PERC |
| UPRIGHT | HOT WOOD | C2 | .5 | .45 | .55 | .5 | .5 | 0 | TONAL | PERC |
| UPRIGHT | BOWED NOTE | C2 | .45 | .35 | .15 | .5 | .55 | 1 | LOOP | LOOP |

How the roster was chosen. It spreads SETTLE (a half-life of 80 ms, QUICK SINK, against 0.64 s, BRIGHT PICK), WEIGHT, GRIT and LENGTH across both voices, from C1 to G2. Four rules that the listens so far set:
- SLIDE stays at or under .5, so no preset slides down from above, the side risk 5 calls a cartoon. No slide has been heard yet (clip 09 had no answer), and SCOOP UP's .38 starts 2.9 semitones under the note and rises onto it.
- The loops sit at C2, where the locked bow was heard.
- Every note is in the kit's key.
- The house loudness is one level (−14.7 dB, except WARM GUT and MUTED THUMB at −15.2 and −15.3: WARM GUT peaks at the 0.99 ceiling, which stops the levelling short).

**LOW HULL and the classifier.** At C1 with LENGTH .8 and HOLD .3 or .5, the classifier heard a TOM: the bass branch with a decay under 500 ms, read as 435 ms and then 302 ms. The house roster contract forbids a choking class, and TOM is one.
- The decay reading jumps because the feature extractor's envelope window is 23 ms and a C1 period is 31 ms, so the envelope ripples at the note.
- Moving the note up did not help: D#1 and F1 were still a TOM, and at velocity .3 C1 and D#1 read KICK.
- LENGTH 1 with HOLD .8 reads TONAL at velocity 1, .6 and .3 (decay 575 to 633 ms), so LOW HULL is a long held C1.
- That puts LOW HULL on the 1.44 s cap. HOLD .8 plus a 1.25 s fade would need about 2 s, so the render stops mid-decay: the kit's A14 file has its last 50 ms 36 dB under its peak, closed by the 4 ms fade.
- Disclosed: a short KEEL note at C1 can read as a drum to the classifier. Kits file by `drumClassFor`, which never returns a drum class, so this matters only where the app classifies a render itself.

**Names.** The first draft's WARM PLUCK named PLUCK, an engine's tag, which the house name rule refuses; it is WARM GUT. Older rosters carry such names (DRY PLUCK in PLUCK, TIGHT PLUCK in FORK, SOFT PLUCK in TINES) from before the rule.

**Spread.** `Spread.detect` now takes a KEEL pad's note from its recipe (`Keel.midiFor`), as it does for BALLAST and PITCHWHEEL: `Pitch.detect` reads 40 to 1200 Hz, and KEEL's C1 (32.7 Hz) and D#1 (38.9 Hz) are under it.

**Deviations from the plan.**
- **Six per voice.** The plan said "6 per voice, the Mercury number". MERCURY and BALLAST now carry eight per voice, but six was kept: twelve recipes is already more than one kit shows.
- **The walk sets the note, not TUNE.** A01 to A08 set the patch's root to 36 + the pentatonic step, with TUNE at .5. KEEL's TUNE spans ±12 semitones and the walk climbs 17, so a TUNE walk would clamp A06 to A08 (the ScannedChain kit's form).
- **The folder.** The kit is written to `testkit/keel-kit/`, OVEN's form, not `testkit/SnipSnap Keel Kit/`.

**BELLY: put to the ear, not built.** Adding BELLY to `KeelVoice` would reach every loop over the voices (the seam and pump tests, the keygroup zones, the determinism canary) before anyone had heard it. So the round asks first.
- **The page.** The belly page (2026-10-29) asks one question: is BELLY a bass the roster does not already have?
- **The clips, all on C2, within 0.5 dB of one another in house loudness, each note played twice.**
  - BELLY at C2 and at G1, from the Python harness at the section 12 defaults, with WEIGHT as the body (`keel_thicken_ab.render`, no sub).
  - Against BALLAST ROOT's STONE FLOOR, FATHOM GRIND's GRINDER (TUNE .125, which is C2), REDUCE's ROUND FLOOR and UPRIGHT's WARM GUT, rendered by the Kotlin engines.
- **What the answer sets.** ITS OWN SOUND makes BELLY a Kotlin voice with its loop (section 10's retuned detune), six presets and a place in the kit. ALREADY HAVE IT closes risk 7 and drops BELLY. CAN'T TELL keeps BELLY out of Kotlin for V1: it is asked once more on headphones with the kit, and an answer that still can't tell drops it.

**The gate.** The kit on the phone. On the phone the kit is the KEEL starter. The phone gets this branch only as a CI debug APK, which needs the branch pushed; until then, `testkit/keel-kit/` loads on the MPC.

**The phone app.** KEEL is the first FRESH TAPE starter whose engine is not on SYNTH's `Engine` picker. Every place `SynthScreen` maps a recipe's engine to the picker uses `firstOrNull` and skips what it does not find, so the KEEL pads play, and SYNTH's reopen list leaves them out until R4.

**Not heard:** every preset, the kit, BELLY in Kotlin (it does not exist).

### As built: BELLY (2026-10-31)

The belly page was answered ITS OWN SOUND, so BELLY is KEEL's third voice, built to section 6.3 with the loop of section 10.

**What was built.**
- `KeelVoice.BELLY` and its defaults: SETTLE .5, WEIGHT .45, GRIT .3, LENGTH .5 (section 12's row, the page's clips).
- `Keel.bellySource`: `0.75 (sine + 0.3 sine^3) + 0.65 (0.6 + 0.4 v)(0.25 + 0.75 s) LP(saw)`. The saw is PolyBLEP (SIREN's private helper, copied), 9 cents sharp, through two one-poles at `f (2 + 28 s)`, 80 to 7000 Hz. The harness stepped that cutoff every 128 samples; the Kotlin moves it per sample.
- `Keel.biteRatio`: a one-shot's bite is 9 cents sharp. A held loop needs whole beats, so over the loop's `periods` the bite runs `beats = max(1, round(periods (2^(9/1200) - 1)))` more cycles. The fitted detune is 8.81 cents at C2 and G3, as the prototype measured, and moves between 6.2 and 17.6 cents over the register: it jumps at MIDI 43 and 52, where the rounding steps, and doubles at C1, where half a beat in 3 s rounds up to one. Not heard: low held BELLY keys beat about twice as fast as the one-shot.
- Six presets, the kit's new layout, and the FRESH TAPE description ("Plucked, FM and sine-and-saw bass ...").

**The port matches the clip** (Kotlin against the two harness clips on the page, both levelled by the house stage):

| Note | House loudness | Peak | 30-120, 120-400, 400-4000 Hz, three windows to 0.55 s |
|---|---|---|---|
| C2 | -14.73 / -14.73 dB | .865 / .865 | within 0.05 dB |
| G1 | -14.73 / -14.73 dB | .945 / .945 | within 0.11 dB |

The one larger difference is 4 to 16 kHz after 0.3 s, 2 to 4.5 dB, about 100 dB under full scale and 55 dB under the note: the harness clip's 16-bit floor. It also moves the late centroid, which is why the centroid is not in the table.

**The loop** (BELLY at its defaults, HOLD 1, every MIDI note 24 to 55): seam at most 1.7e-13. BELLY needs no warm-up beyond the settle clock's ten half-lives. Its loop sits 0.5 to 1.1 dB *above* its attack (the body filling in), so `renderKey`'s lift, which is UPRIGHT's, never applies. Raw peak before the output stage at most 7.3 over sixteen macro corners and sixty rolls at C1 and G3, under `RAW_PEAK_BOUND`.

**The presets** (all at C minor pentatonic notes, SLIDE at or under .5, the loop at C2):

| Preset | Note | SETTLE / WEIGHT / GRIT / SLIDE / LENGTH / HOLD | Heard by the classifier | Filed |
|---|---|---|---|---|
| SOFT PILLOW | C2 | .5 / .45 / .3 / .5 / .5 / .1 | PERC | TONAL |
| DEEP CUSHION | C1 | .35 / .6 / .2 / .5 / 1 / .8 | TONAL | TONAL |
| TOOTH MARK | G1 | .75 / .35 / .55 / .5 / .5 / 0 | PERC | TONAL |
| SLOW MELT | D#2 | 1 / .4 / .3 / .5 / .7 / .2 | PERC | TONAL |
| CLIMB IN | A#1 | .4 / .5 / .25 / .38 / .5 / 0 | PERC | TONAL |
| WARM HUSH | C2 | .5 / .5 / .3 / .5 / .5 / 1 | LOOP | LOOP |

All six sit at the house loudness (-14.7 dB). Names avoid BITE, LIFT and DRONE, which are other engines' macro or voice words, though the name test checks only engines, voices and rack sections.

**The classifier at C1.** A short BELLY one-shot under G1 reads as KICK (C1 and D#1 at LENGTH 0 to .5; G1 at velocity .3), as REDUCE and UPRIGHT do at C1: the decay reading is unreliable below one period of the note (`KEEL.md`, Limits). DEEP CUSHION is long for that reason, LOW HULL's fix.

**The kit.** A01 to A08 unchanged (the REDUCE walk); A09 WARM GUT, A10 BRIGHT PICK (UPRIGHT); A11 SOFT PILLOW, A12 TOOTH MARK (BELLY); A13 QUICK SINK, A14 LOW HULL (REDUCE); A15 BOWED NOTE (UPRIGHT loop), A16 WARM HUSH (BELLY loop). MUTED THUMB, HOT WOOD and the REDUCE loop SETTLED HUM left the kit and stay presets: the walk already gives REDUCE eight pads, and a kit is sixteen.

**Not heard:** Kotlin BELLY, its presets, its loop, and the fitted detune's faster beat on low held keys.

