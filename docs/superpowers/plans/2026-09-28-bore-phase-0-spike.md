# BORE — the Phase 0 spike, recorded

**Status:** a record, not a plan. Nothing here is in the build.
**Date:** 2026-09-28
**Design:** [`../specs/2026-09-28-bore-woodwind-engine-design.md`](../specs/2026-09-28-bore-woodwind-engine-design.md)
— "The physics, measured", "Phasing and gates" (Phase 0) and Appendix B are
the numbers this file produced; Appendix A10 and A11 are the two Python
scripts' numbers.

## What this is

The design document for BORE rests on measurements, and this file keeps
what made them so they can be re-run:

- **`BoreSpike.kt` and `BoreSpikeTest.kt`** — the throwaway Kotlin
  prototype of the spec's blown-bore idea with its proven defects fixed
  (the round-trip delay, a passive reed reflection table, an in-loop DC
  blocker on the +1 loops, the bell filter's phase charged to the loop
  budget, the melodic output chain), built from the house's own
  primitives so the test could print numbers. It is not an engine: no
  `Patch`, no preset, no picker entry, no kit. It lives here as text, not
  as source, on purpose — R1 rebuilds the loop on `Strings.Loop` from the
  design rather than copying this. To re-run it, copy the two files into
  `synth/src/main/kotlin/com/snipsnap/synth/` and
  `synth/src/test/kotlin/com/snipsnap/synth/` and run
  `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.BoreSpikeTest" -i`;
  the suite is print-only (its only assertions guard against non-finite
  output) and took 12–18 s once compiled. Every number in the design's
  Appendix B is copied from that output.
- **The spike's own report**, as written after the third run, with the
  iteration log (why the reed's parametrisation and the cone's blocker
  corner changed between runs).
- **`bore_sim.py`, `bore_sim2.py`, `bore_thresh.py`** — the DSP review's
  pure-Python replicas of the spec's loop and of the corrected loop
  (double precision, no Gradle), and the threshold-of-oscillation probe
  behind the design's Appendix A10 and A11. `bore_sim.py` is the shared
  base the other two import. They are claims about the arithmetic, not
  about a Kotlin build, and the design says so where it uses them. Run
  with `python3 bore_sim2.py` and `python3 bore_thresh.py` from one
  directory holding all three.

The `F`-numbers in the Kotlin KDoc (`F1`, `F2`, `F8` …) are the DSP desk
review's finding numbers; each is restated, with its measurement, in the
design's "The physics, measured" and its verdict table. The worktree and
scratch paths the report names were the session's temporary directories
and no longer exist; the report is kept verbatim as the record of what
was run.

## The spike's report

Worktree: `/home/user/SnipSnap/.claude/worktrees/wf_414745fe-34f-5` (two untracked files, nothing committed, main checkout untouched):
- `synth/src/main/kotlin/com/snipsnap/synth/BoreSpike.kt` — 238 lines (about 60 of them KDoc), `internal object BoreSpike`, marked SPIKE in its header.
- `synth/src/test/kotlin/com/snipsnap/synth/BoreSpikeTest.kt` — 305 lines, six print-only tests; the only asserts guard non-finite output (none fired).

Build/run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.BoreSpikeTest" -i` → BUILD SUCCESSFUL (1m24s fresh, 38–46 s after; the suite itself runs in 12–18 s). Raw outputs saved at `/tmp/claude-0/-home-user-SnipSnap/f2864421-f685-54a8-81bd-4b182444799c/scratchpad/borespike_iter{1,2,3}.xml`. Every number below is copied from those runs. The iteration-3 rerun reproduced all 144 non-new lines of iteration 2 byte-for-byte (timing column aside): the render is deterministic.

## What the spike is

Render at `Dsp.RATE * Dsp.OVERSAMPLE` = 176 400 Hz, 1.0 s note, 40 ms linear attack / hold / 80 ms release on the mouth pressure, then `Tide.bandLimit` → `Dsp.decimate` → subtract mean + 20 Hz `Dsp.OnePole` high-pass → `Dsp.levelTo(MELODIC_LOUDNESS_TARGET)` → `Dsp.fadeTail`. Seed `Dsp.seedFor("BORESPIKE", shape, f0)`, turbulence `Dsp.Noise` multiplicative on the pressure (5 %).

Loop (house primitives): ring of `n + 2` floats, single integer tap `n` samples back, first-order allpass `a = (1-frac)/(1+frac)` for the fraction (Jaffe-Smith, the same form as `Strings.Loop.next`, Strings.kt:398–400), `Dsp.OnePole` bell filter INSIDE the loop, reflection ±0.95. Budget copied from `Strings.tune`: `filterDelay` is Strings.kt:110–114 verbatim (`BoreSpike.onePoleDelay`), `dcDelay` is Strings.kt:142–146 verbatim (`BoreSpike.dcBlockerDelay`), `exact = roundTrip − filterDelay − dcDelay`, `n = floor(exact)`, `require(exact >= Strings.MIN_LOOP_SAMPLES)`. No 0.5-sample two-tap term because the read is single-tap.
- CYLINDER: roundTrip = T/2, reflection −0.95, no in-loop DC blocker.
- CONE: roundTrip = T, reflection +0.95, in-loop one-pole DC blocker (`ret − lp(ret, dcHz)`), lead budgeted.
- FLUTE: roundTrip = T, +0.95, DC blocker, bore filter 6 kHz; jet line of `jetRatio · T` samples fed by `−0.5 · ret`; labium `tanh(3·p_m · jet + offset(LIP))·min(p_m,1) + 0.5 · ret` — pressure as input gain, offset from LIP (0..0.3).
- Reed (CYLINDER/CONE): STK reflection table, `Δ = ret − p_m`, `r = clamp(offset + slope·Δ, −1, 1)`, `out = p_m + Δ·r`, written to the line.

## Iteration log (the loop's own tuning; pitch was never off by a constant on the cylinder, so the iterations were driven by the cone and the corners)

| Iter | Reed / pressure | Cone DC blocker | CYL 110/220/440 (cents) | CONE 55/110/220 (cents, global peak Hz) | FLUTE 220/440/880 (cents) |
|---|---|---|---|---|---|
| 1 | offset 0.7 fixed; LIP → slope −0.2..−0.5; p_m = 1.2·BREATH | 20 Hz | +0.5 / +0.9 / +1.3 | −6.0 / −5.6 / −2.2, global peak 105.71 / 215.47 / 219.72 (2nd mode dominant and 36–70 c flat at 55/110) | −12.9 / −5.0 / +0.4 (220: global peak 653.5 = 3rd mode) |
| 2 | slope −0.3 fixed; LIP → offset 0.5..0.85; p_m = pClose·lin(BREATH, 0.4, 0.97), pClose = (1−offset)/0.3 | 2 Hz (Strings' corner) | +0.7 / +1.3 / +1.9 | −4.0 / +0.3 / −6.8, global peak 54.87 / 110.02 / 23.31 (sub-audio relaxation ~21 Hz at 110/220, Pitch.detect null) | −0.1 / +1.2 / +2.5 (220: global peak 659.8, h3 +3.9 dB) |
| 3 | as 2 | 20 Hz vs 2 Hz vs f0/25 vs none, side by side | (unchanged) | f0/25: −4.3 / −5.8 / −4.4, 2nd mode −1.6 / −4.9 / −4.2, sub-40 Hz energy −25 / −38 / −44 dB, Pitch 54.78 / 109.70 / 219.40 | (unchanged) |

Why iteration 2 changed the reed: with LIP as slope and an absolute pressure, slope×pressure is a single product and the table is scale-invariant in it, so iteration 1's corners either choked (r clamped at +1 once |slope|·p_m > 0.3: BREATH ≥ 0.85 at LIP ≥ 0.5 and BREATH 0.65/LIP 1 rendered rawAC 0.0000, exactly silent) or sat under threshold (LIP 0 at BREATH ≤ 0.65: rawAC ≤ 0.0008); only 6 of 18 CYLINDER corners and 5 of 18 CONE corners oscillated. LIP as the table offset (rest reflection) with pressure expressed as a share of the closing pressure gives every LIP a playable BREATH range.

Why the cone's blocker moved: a one-pole high-pass's lead is budgeted at f0 only; at 20 Hz it is 178 samples at 55 Hz (of a 3207-sample period), so the 2nd mode lands 68 c flat (measured 105.76 Hz for 2·55) and 37 c flat at 110 Hz; at 2 Hz the modes are harmonic (2nd mode −1.0 / −2.9 / −1.3 c) but the reed's operating point relaxes at ~21 Hz (sub-40 Hz energy −0.0 dB of the total at 110 and 220 Hz, peak 0.95–0.96 after levelTo, Pitch.detect null). No blocker at all: the +1 loop parks at DC +0.74 and does not oscillate at LIP 0.5 (rawAC 0.006) and relaxes at 21 Hz with f0 +79 c at LIP 0. f0/25 (2.2 / 4.4 / 8.8 Hz) is the measured compromise: 2nd mode within −4.9 c, sub-audio ≤ −25 dB, Pitch.detect agrees.

## Final constants (BoreSpike.kt)

BELL_HZ 2500 (reed voices), FLUTE_BELL_HZ 6000, DC_HZ 2 default (test 6 shows f0/25 is the better cone value), REED_SLOPE −0.3, OFFSET_LO/HI 0.5/0.85 (LIP), P_SHARE_LO/HI 0.4/0.97 of closing pressure (BREATH), TURB 0.05, ATTACK 40 ms, RELEASE 80 ms, REFLECTION 0.95, OUT_DC_HZ 20, JET_RATIO 0.5, JET_REFLECTION 0.5, END_REFLECTION 0.5, JET_GAIN_PER_P 3.0, JET_OFFSET_MAX 0.3, FLUTE_P_MAX 1.2, note 1.0 s.

## (1) Pitch, series, DC, level at BREATH 0.65 / LIP 0.5 (iteration-2 constants; CONE also at f0/25 from test 6)

nearPk = loudest FFT peak within one semitone of f0 (TuningAccuracyTest.measuredHz idiom: Blackman-Harris, ≥65536 points over the middle 50 %, parabolic interpolation); globalPk = loudest peak above 20 Hz; Pitch = `Pitch.detect(fromSec 0.25, windowSec 0.5)`.

| Shape | f0 | exact / n / a | τLP / τDC (smp) | nearPk (cents) | globalPk Hz | Pitch.detect | rawDC (mid 50 %) | rawAC | preDC mean | final mean | peak | Loudness | ms per rendered s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CYLINDER | 110 | 791.088 / 791 / 0.8382 | 10.730 / 0 | 110.045 (+0.7) | 110.04 | 109.98 (c0.99) | +0.0170 | 0.5868 | +0.0170 | −0.00002 | 0.308 | 0.1834 | 106–113 (first row, JIT) |
| CYLINDER | 220 | 390.201 / 390 / 0.6659 | 10.709 / 0 | 220.170 (+1.3) | 220.17 | 220.50 (c0.99) | +0.0177 | 0.5713 | +0.0177 | −0.00002 | 0.238 | 0.1834 | 32–49 |
| CYLINDER | 440 | 189.831 / 189 / 0.0923 | 10.624 / 0 | 440.494 (+1.9) | 440.49 | 441.00 (c1.00) | +0.0200 | 0.5076 | +0.0201 | −0.00002 | 0.232 | 0.1834 | 26–43 |
| CONE 2 Hz | 55 | 3215.091 / 3215 / 0.8333 | 10.736 / −18.554 | 54.872 (−4.0) | 54.87 | 54.78 (c0.90) | +0.0571 | 0.1552 | +0.0571 | −0.00001 | 0.612 | 0.1832 | 33–45 |
| CONE 2 Hz | 110 | 1597.546 / 1597 / 0.2935 | 10.730 / −4.640 | 110.017 (+0.3) | 110.02 | null | +0.0342 | 0.3207 | +0.0342 | −0.00937 | 0.952 | 0.1834 | 30–37 |
| CONE 2 Hz | 220 | 792.270 / 792 / 0.5751 | 10.709 / −1.160 | 219.139 (−6.8) | 23.31 | null | +0.0424 | 0.4225 | +0.0424 | −0.00078 | 0.962 | 0.1834 | 26–47 |
| CONE f0/25 | 55 | — | 10.736 / −20.407 | −4.3 | 54.86 | 54.78 (c0.90) | +0.0592 | 0.1476 | — | — | 0.643 | — | — |
| CONE f0/25 | 110 | — | 10.730 / −10.204 | −5.8 | 109.63 | 109.70 (c0.97) | +0.0598 | 0.3260 | — | — | 0.435 | — | — |
| CONE f0/25 | 220 | — | 10.709 / −5.102 | −4.4 | 219.45 | 219.40 (c0.99) | +0.0504 | 0.5469 | — | — | 0.335 | — | — |
| FLUTE | 220 | 798.783 / 798 / 0.1214 | 4.195 / −1.160 | 219.984 (−0.1) | 659.82 | 219.40 (c0.99) | +0.0272 | 1.2449 | +0.0272 | −0.00005 | 0.226 | 0.1834 | 39–60 |
| FLUTE | 440 | 397.011 / 397 / 0.9792 | 4.189 / −0.290 | 440.304 (+1.2) | 440.30 | 441.00 (c1.00) | +0.0259 | 1.2788 | +0.0257 | −0.00004 | 0.215 | 0.1834 | 36–40 |
| FLUTE | 880 | 196.363 / 196 / 0.4671 | 4.164 / −0.073 | 881.277 (+2.5) | 881.28 | 882.00 (c1.00) | +0.0311 | 1.2209 | +0.0308 | −0.00005 | 0.213 | 0.1834 | 37–42 |

Non-finite samples: 0 in every render of every test (raw and output). Pitch.detect's 220.50/441.00/882.00 are its lag quantisation (44100/lag), not the loop.

Harmonics h1..h8 in dB re h1 (peak power within ±3 % of k·f0), odd/even = 20·log10(Σ|odd| / Σ|even|):

| Shape f0 | h1 | h2 | h3 | h4 | h5 | h6 | h7 | h8 | odd/even |
|---|---|---|---|---|---|---|---|---|---|
| CYL 110 | 0 | −48.6 | −9.9 | −49.8 | −15.3 | −51.4 | −19.6 | −53.4 | +42.6 dB |
| CYL 220 | 0 | −44.8 | −11.7 | −48.3 | −19.2 | −52.8 | −25.8 | −57.5 | +40.6 dB |
| CYL 440 | 0 | −41.9 | −15.6 | −51.2 | −27.0 | −56.7 | −36.4 | −58.0 | +39.1 dB |
| CONE 55 (2 Hz) | 0 | −9.6 | −19.4 | −15.0 | −16.9 | −26.5 | −23.7 | −27.5 | +6.8 dB |
| CONE 110 (2 Hz) | 0 | −7.3 | −33.0 | −21.6 | −31.5 | −24.8 | −33.4 | −34.4 | +5.1 dB |
| CONE 220 (2 Hz) | 0 | −0.7 | −5.6 | −9.6 | −6.0 | −11.9 | −12.5 | −12.6 | +2.3 dB |
| CONE 55 (f0/25), h1..h6 | 0 | −10.1 | −18.7 | −16.0 | −17.3 | −25.5 | | | |
| CONE 110 (f0/25) | 0 | −7.9 | −22.5 | −15.7 | −22.3 | −24.9 | | | |
| CONE 220 (f0/25) | 0 | −9.2 | −21.6 | −17.5 | −33.5 | −25.8 | | | |
| FLUTE 220 | 0 | −44.2 | +3.9 | −37.8 | −3.1 | −42.1 | −4.7 | −39.1 | +40.1 dB |
| FLUTE 440 | 0 | −42.8 | −10.0 | −44.2 | −15.4 | −45.3 | −19.5 | −47.0 | +36.7 dB |
| FLUTE 880 | 0 | −37.3 | −11.2 | −40.4 | −18.4 | −44.2 | −24.6 | −48.5 | +32.9 dB |

Iteration 1 (20 Hz blocker) cone series for the record: 55 Hz h2 +8.0 dB above h1, 110 Hz h2 +6.5 dB — the inharmonic 2nd mode dominating, not a full series.

## (2) Corner sweep BREATH {0.15, 0.3, 0.5, 0.65, 0.85, 1.0} × LIP {0, 0.5, 1} (iteration-2 constants)

CYLINDER 220 Hz — finite at all 18; oscillating (raw mid-50 % AC RMS > 0.01) at 13/18:

| BREATH | LIP 0 | LIP 0.5 | LIP 1 |
|---|---|---|---|
| 0.15 | — (rawAC 0.0013) | — (0.0003) | — (0.0000) |
| 0.30 | osc +0.9 c, AC 0.739 | — (0.0071, just under) | — (0.0002) |
| 0.50 | osc +2.0 c, 0.921 | osc +1.1 c, 0.513 | — (0.0007) |
| 0.65 | osc +2.2 c, 1.029 | osc +1.3 c, 0.571 | osc +0.3 c, 0.102 |
| 0.85 | osc +1.8 c, 1.165 | osc +1.1 c, 0.637 | osc +0.4 c, 0.147 |
| 1.00 | osc +1.5 c, 1.261 | osc +0.8 c, 0.676 | osc +0.2 c, 0.111 |

Every oscillating cylinder corner: global peak = f0 (220.03–220.27 Hz), Pitch.detect 220.50 c0.99, rawDC +0.003..+0.037, output peak 0.236–0.271, Loudness 0.1834. Non-oscillating corners: output peak 0.990, Loudness 0.059–0.084 (levelTo scales the turbulence floor to the ceiling — see "sub-threshold" below).

CONE 110 Hz, 2 Hz blocker — finite at all 18; "osc" at 14/18 but the LIP 0 column and the low-BREATH rows are the 21 Hz relaxation, not a note (global peak 20.5–23.1 Hz, Pitch null): nearPk cents by row (LIP 0 / 0.5 / 1): 0.15: −124.5 / −127.5 / —; 0.30: +4.2 / +18.0 / —; 0.50: +32.1 / −2.8 / —; 0.65: −24.1 / +0.3 / −0.9; 0.85: −11.6 / +0.1 / +0.3; 1.00: −3.7 / +0.2 / −0.5.

CONE 110 Hz, f0/25 blocker (test 6b, BREATH {0.3, 0.5, 0.65, 0.85, 1.0}) — finite at all 15, oscillating at 11/15 (not at BREATH 0.3/LIP 0.5, 0.3/1, 0.5/1, 0.65/1):

| BREATH | LIP 0: f0 c / mode-2 c / sub-40 Hz dB | LIP 0.5 | LIP 1 |
|---|---|---|---|
| 0.30 | +2.3 / −10.6 / −0.3 (relaxation still present) | — | — |
| 0.50 | −8.0 / −7.6 / −34.8 | −7.3 / −5.6 / −29.8 | — |
| 0.65 | −2.0 / +1.2 / −18.6 | −5.8 / −4.9 / −37.7 | — |
| 0.85 | −1.0 / +5.4 / −20.2 | −4.7 / −8.0 / −34.4 | −0.5 / −8.4 / −22.2 |
| 1.00 | −5.2 / −12.2 / −34.6 | −4.5 / −7.7 / −37.4 | −1.7 / −8.7 / −16.2 |

Output peaks 0.40–0.73, Pitch.detect 109.43–109.98 (c0.90–0.97) on every oscillating corner.

## (3) BREATH threshold at LIP 0.5 (0.05 steps, iteration-2 constants)

- CYLINDER 220: first oscillating BREATH = 0.35 (p_m 0.649 = 0.60 of the closing pressure 1.083; the table's linear threshold is (1/0.95 − offset)/(2·(1 − offset)) = 0.59 of closure for offset 0.675 — measured matches). rawAC/p_m: 0.011 at 0.30, 0.444 at 0.35, 0.615 at 0.40, 0.69 at 0.50–0.55, easing to 0.64 at 1.0. Pitch across the whole playable range +0.4..+1.3 c (a 0.9 c spread from BREATH 0.35 to 1.0).
- CONE 110 (2 Hz): rawAC > 0.01 from BREATH 0.15 but that is the sub-audio relaxation (nearPk −127.5 / −131.3 c at 0.15/0.20, +17.9 / +18.0 at 0.25/0.30); a clean note from 0.35 (+4.0 c), then +0.6, −0.5, −2.8, −0.1, +0.4, +0.3, −0.1, +1.8, −2.6, +0.1, −0.8, −0.1, +0.2 c up to 1.0.
- FLUTE 440 (test 5b): rawAC 0.0008 / 0.0016 / 0.0028 at BREATH 0.15 / 0.30 / 0.50 (no lock), 1.279 at 0.65 (+1.2 c), 1.723 at 0.85 (nearPk +2.2 c but global peak 1321.45 Hz = 3rd mode), 1.770 at 1.0 (+2.1 c, global 440.54). Threshold between 0.50 and 0.65 (jet gain ∝ p_m² in this formulation).

## (4) Spectral centroid (`FeatureExtractor.extract` on the middle of the output)

CYLINDER 220 vs BREATH at LIP 0.5: 6593.5 (0.15, noise only), 1238.6 (0.30, under threshold), 261.7, 265.2, 259.5, 252.5 Hz (0.50–1.0) — flat once playing; not monotonic. In this table model BREATH sets level and threshold, barely brightness (rawAC 0.51 → 0.68, rolloff 1098 → 678 Hz).
CYLINDER 220 vs LIP at BREATH 0.65: 282.8, 274.5, 265.2, 251.8, 250.4 Hz — monotonic non-increasing (tighter lip = darker; rolloff 1540 → 1109 → 1098 → 668 Hz; the LIP 1 rolloff reads 14 040 Hz because rawAC drops to 0.10 and the turbulence floor shows).
CONE 110 (2 Hz): centroids 4–77 Hz — dominated by the sub-audio relaxation; not a brightness measurement (vs LIP reads non-decreasing 5.7 → 77.0 for the same reason). Needs re-measuring at f0/25 before any claim.

## (5) Level / DC bar (ForkPresetsTest.kt:26–32: loud ≥ 0.9·0.1834 or peak ≥ 0.95; |dc| < 0.05)

Every oscillating render: Loudness 0.1832–0.1834 (= target), |final mean| ≤ 0.00937 (that worst case is CONE 110 at 2 Hz; all others ≤ 0.0008), so both clauses pass. Output peak is 0.21–0.31 for CYLINDER/FLUTE, 0.34–0.73 for CONE at f0/25, 0.95–0.96 for CONE at 2 Hz (crest factor from the relaxation). Sub-threshold renders (BREATH below the reed's threshold): levelTo lifts the 5 % turbulence floor to peak 0.990 with Loudness 0.059–0.12 — they pass the bar only by the peak clause and would sound like full-scale hiss; the design has to say what a sub-threshold BREATH renders.

## (6) FLUTE, best effort — it locks

Jet ratio comparison at BREATH 0.65 / LIP 0.5 (nearPk cents; global peak Hz): 0.32 → +102.4 (1088.6) / +80.5 (849.8) / +73.7 (1700.1) at 220/440/880, upper modes dominant (h2 +40..+54 dB), unusable; 0.40 → +79.7 / +79.6 / +80.1 c (global 869 / 461 / 922), unusable; 0.50 → −0.1 / +1.2 / +2.5 c, global 659.8 / 440.3 / 881.3. So τ_jet = T/2 is the register condition that works; at 220 Hz the 3rd mode is +3.9 dB above the fundamental (every odd mode is coherent with τ_jet = T/2 and the 6 kHz bore filter does not discriminate at 660 Hz), at 440/880 h3 is −10/−11 dB. Register behaviour with BREATH: at 0.85 the 3rd mode takes over (global 1321 Hz), at 1.0 it returns to f0. Not tried: τ_jet ∝ 1/√p (the overblow mechanism), a jet band-pass, a lower bore-filter corner. The flute needs its own audition pass, but it is no longer "did not lock".

## Cost

JIT-warm: 26–49 ms per rendered second (CYLINDER 220/440, CONE), 36–60 ms/s (FLUTE); the first measured row of each run reads 106–113 ms/s (JIT warm-up despite one discarded warm call). Comparable to the probe's 37–59 ms/s for the spec's loop and under FORK's 88 ms/s (u1_empirical §10).

## Residuals against a 5-cent gate

- CYLINDER: +0.7 / +1.3 / +1.9 c at 110/220/440 defaults; +0.2..+2.2 c across all 13 oscillating corners; +0.4..+1.3 c across the whole BREATH range. Passes 5 c without a correction. The residual is sharp and grows slightly with f0 (≈0.2–0.3 samples of loop, consistent with the memoryless junction sitting one write behind the read); a `Dsp.Ladder`-style measured correction is not needed here.
- CONE: −4.0 / +0.3 / −6.8 c at 2 Hz; −4.3 / −5.8 / −4.4 c at f0/25 — a consistent ~5 c flat at defaults, with corners spanning −8.0..+2.3 c at f0/25. Without a correction it fails a strict 5 c gate at 110 and 220; with a measured per-voice correction of about +5 c (shorten the loop ≈0.3 %, the `Dsp.Ladder` KDoc precedent) the defaults pass and the corners straddle ±5 c around it — the reed's own pull with BREATH/LIP is the irreducible part. The 2nd mode is a separate residual (−1.6..−4.9 c at f0/25; −68 c at 20 Hz) set by the blocker corner, not by the loop length.
- FLUTE: −0.1 / +1.2 / +2.5 c at defaults; +1.2..+2.2 c over BREATH 0.65–1.0. Passes without correction (with the caveat that 220 Hz's loudest partial is the 3rd).

## What worked / what did not

Worked: the idea. A pressure-controlled reflection table closing a tuned waveguide sustains a note, bounded everywhere (0 non-finite samples in ~220 renders), in tune to ~2 c on the closed cylinder with a 39–43 dB odd/even ratio, with a real playing threshold (BREATH 0.35 at LIP 0.5) and a LIP that darkens monotonically. The corrected cone gives a full series (h2 −7..−10 dB, h3 −19..−33 dB) within ~6 c. The flute jet locks at τ_jet = T/2 within 2.5 c at 440/880.

Did not (as data for the design): (a) the +1 single-loop cone has a real conflict between its in-loop DC blocker's harmonicity (mode 2 −37..−68 c at 20 Hz) and its operating-point stability (21 Hz relaxation at 2 Hz); f0/25 is a measured compromise, not a solution — the honest alternative is a different cone reduction (two-segment "blown string" with inverting ends, or the apex allpass the DSP review names). (b) LIP-as-slope with an absolute pressure (the spec's and STK's parametrisation) is a gain axis that chokes or starves the corners; LIP has to be the table offset (or the bell), and BREATH a share of the closing pressure. (c) BREATH does not brighten the cylinder (centroid flat 252–265 Hz once playing) — a velocity → BREATH mapping buys threshold and level, not the "harder = brighter" the spec's test 5 expects. (d) Sub-threshold renders come out as full-scale hiss through levelTo. (e) The flute's low register is 3rd-mode-dominant at 220 Hz and there is no overblow mechanism yet.

## What a real implementation needs from Strings.Loop (two additions)

1. A junction hook. `Loop.next(x)` is additive: `x + fb * yy` (Strings.kt:429) with the returning wave `yy` private to the method. A blown bore's new line sample is a nonlinear function of the returning wave (`out = p_m + Δ·r(Δ)`, or the jet), so Loop needs a variant that hands `fb·LP(allpass(tap))` (and, for the cone, the DC-blocked version) to a caller-supplied junction and writes the junction's return into the history — the spike's loop body is exactly Loop's minus the `x +` and plus that call. Everything else (ring, allpass state, `loopLp`, budget) is reused unchanged.
2. Shape in the budget and the loop: `tune` hard-codes `exact = rate/freq − … − 0.5` (Strings.kt:161) — it needs a round-trip factor (0.5 for the closed cylinder) and the DC blocker term independent of `jawari` (Strings.kt:142–146 budget it and Strings.kt:424–428 run it only when `jawari > 0`), with a corner that is a parameter rather than the fixed 2 Hz of `dcBlockerA` (Strings.kt:194) — the cone measured best at f0/25. Negative feedback is already expressible (`fb` is a Float; `damping()` only produces positive values, Strings.kt:46–49). Keeping Loop's two-tap average and its budgeted 0.5 sample is fine; the spike's single tap made no measurable difference to tuning.

Not touched by the spike, still open for the design: HOLD/loop seams, vibrato as pressure modulation, the tape stage (the rack's), presets, Velocity's BREATH scaling against the measured threshold, and the classifier bucket.

## `BoreSpike.kt`

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * SPIKE - not an engine. Phase 0 of the BORE design ("measured, not
 * described"): the spec's blown-bore idea with its proven defects fixed,
 * built from the house's own primitives so BoreSpikeTest can print numbers.
 * Nothing here is wired into a Patch, a preset, a picker or a kit; delete
 * once the design document has its tables.
 *
 * What is fixed relative to the spec (u1_dsp-review F1/F2/F8/F9/F10/F29/F30):
 *  - the delay line holds the ROUND TRIP: T/2 with reflection -0.95 for the
 *    closed cylinder (odd series), T with +0.95 and an in-loop DC blocker for
 *    the cone / open pipe (full series);
 *  - the fraction of the loop length rides a first-order allpass
 *    (Jaffe-Smith, as [Strings.Loop]), and the bell one-pole's phase lag and
 *    the DC blocker's lead are charged to the delay budget exactly as
 *    [Strings.tune] charges `filterDelay` and `dcDelay`;
 *  - the exciter is Smith's reed reflection table (STK Clarinet), passive by
 *    construction (|r| <= 1), so nothing can diverge;
 *  - the output chain is the melodic fleet's (bandLimit -> decimate -> DC
 *    twice -> levelTo(MELODIC_LOUDNESS_TARGET) -> fadeTail), not the drum
 *    path.
 */
internal object BoreSpike {

    enum class Shape { CYLINDER, CONE, FLUTE }

    /** Reed voices' bell one-pole corner, fixed so LIP is the only brightness axis under test. */
    const val BELL_HZ = 2500f
    /** FLUTE's bore filter corner (STK Flute's pole 0.7 at 22.05 kHz is ~9 kHz). */
    const val FLUTE_BELL_HZ = 6000f
    /**
     * In-loop DC blocker corner for the +1 loops (cone, flute). Iteration 1
     * used 20 Hz and measured the cone's 2nd mode 36-70 cents flat of 2*f0
     * at 110/55 Hz (the blocker's lead is budgeted at f0 only; at 55 Hz it
     * was 178 samples). 2 Hz is [Strings.tune]'s own choice for the same
     * reason (Strings.kt:131-134).
     */
    const val DC_HZ = 2f
    /**
     * Reed table slope, fixed (STK Clarinet's -0.3). Iteration 1 made LIP
     * the slope with an absolute pressure range and measured the corners
     * choking (r clamped at +1 once slope*pressure > 0.3) or under
     * threshold; slope*pressure is one product and the table is
     * scale-invariant in it, so slope-as-LIP is only a gain. LIP is the
     * table's offset instead (the reed's rest reflection: loose lip = more
     * open = lower), and pressure is a share of the reed's closing pressure.
     */
    const val REED_SLOPE = -0.3f
    const val OFFSET_LO = 0.5f
    const val OFFSET_HI = 0.85f
    /** BREATH -> share of the closing pressure (1 - offset)/|slope|; 0.97 keeps the reed just open at rest at BREATH 1. */
    const val P_SHARE_LO = 0.4f
    const val P_SHARE_HI = 0.97f
    /** FLUTE's mouth pressure at BREATH 1. */
    const val FLUTE_P_MAX = 1.2f
    /** Multiplicative turbulence on the mouth pressure. */
    const val TURB = 0.05f
    const val ATTACK_S = 0.040f
    const val RELEASE_S = 0.080f
    const val REFLECTION = 0.95f
    /** Post-chain DC high-pass (FORK's rule: the DC goes twice). */
    const val OUT_DC_HZ = 20f

    /** FLUTE: jet delay as a fraction of the bore period, jet/end reflection shares, pressure -> jet gain. */
    const val JET_RATIO = 0.5f
    const val JET_REFLECTION = 0.5f
    const val END_REFLECTION = 0.5f
    const val JET_GAIN_PER_P = 3.0f
    const val JET_OFFSET_MAX = 0.3f

    /** What a render leaves behind for the test to measure, besides the Snip. */
    class Result(
        val snip: Snip,
        /** The oversampled loop output as written to the line, before any output stage. */
        val raw: FloatArray,
        /** After bandLimit + decimate, before the mean subtraction and 20 Hz high-pass. */
        val beforeDc: FloatArray,
        /** After DC removal, before levelTo. */
        val beforeLevel: FloatArray,
        val tuning: Strings.Tuning,
        val filterDelay: Double,
        val dcDelay: Double,
        val renderNanos: Long,
        /** The mouth pressure at full envelope, for normalising AC measurements. */
        val pMax: Float,
    )

    /** One-pole low-pass phase delay at [freq], samples - [Strings.tune]'s `filterDelay` verbatim. */
    fun onePoleDelay(loopHz: Float, freq: Float, rate: Int): Double {
        val filterA = 1.0 - exp(-2.0 * PI * min(loopHz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * freq / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        return -filterPhase / w
    }

    /** One-pole high-pass (input minus low-pass) phase delay at [freq], negative (a lead) - [Strings.tune]'s `dcDelay` form. */
    fun dcBlockerDelay(dcHz: Float, freq: Float, rate: Int): Double {
        val dcA = (1.0 - exp(-2.0 * PI * dcHz / rate)).toFloat()
        val r = 1.0 - dcA
        val w = 2.0 * PI * freq / rate
        val phase = atan2(sin(w), 1.0 - cos(w)) - atan2(r * sin(w), 1.0 - r * cos(w))
        return -phase / w
    }

    /**
     * The loop budget: round trip minus every in-loop stage's own delay at
     * f0, split integer + allpass. [dcHz] is the +1 loops' in-loop blocker
     * corner; 0 means no in-loop blocker (iteration 3 measures both).
     */
    fun tune(shape: Shape, f0: Float, rate: Int, dcHz: Float = DC_HZ): Triple<Strings.Tuning, Double, Double> {
        val period = rate / f0.toDouble()
        val bell = if (shape == Shape.FLUTE) FLUTE_BELL_HZ else BELL_HZ
        val filterDelay = onePoleDelay(bell, f0, rate)
        val dcDelay = if (shape == Shape.CYLINDER || dcHz <= 0f) 0.0 else dcBlockerDelay(dcHz, f0, rate)
        val roundTrip = if (shape == Shape.CYLINDER) period * 0.5 else period
        val exact = roundTrip - filterDelay - dcDelay
        require(exact >= Strings.MIN_LOOP_SAMPLES) { "loop too short: $exact samples at $f0 Hz" }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        return Triple(Strings.Tuning(exact, n, a), filterDelay, dcDelay)
    }

    fun render(
        shape: Shape,
        f0: Float,
        breath: Float = 0.65f,
        lip: Float = 0.5f,
        seconds: Float = 1.0f,
        seed: Int = Dsp.seedFor("BORESPIKE", shape, f0),
        jetRatio: Float = JET_RATIO,
        dcHz: Float = DC_HZ,
    ): Result {
        val t0 = System.nanoTime()
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val (tuning, filterDelay, dcDelay) = tune(shape, f0, rate, dcHz)
        val n = tuning.n
        val a = tuning.a
        val size = n + 2
        val line = FloatArray(size)
        var w = 0
        var apX1 = 0f
        var apY1 = 0f
        val bell = Dsp.OnePole(rate)
        val bellHz = if (shape == Shape.FLUTE) FLUTE_BELL_HZ else BELL_HZ
        val dcLp = Dsp.OnePole(rate)
        val g = if (shape == Shape.CYLINDER) -REFLECTION else REFLECTION
        val noise = Dsp.Noise(seed)
        val offset = Dsp.lin(lip, OFFSET_LO, OFFSET_HI)
        val pClose = (1f - offset) / -REED_SLOPE
        val pMax = if (shape == Shape.FLUTE) FLUTE_P_MAX * breath.coerceIn(0f, 1f) else pClose * Dsp.lin(breath, P_SHARE_LO, P_SHARE_HI)

        // FLUTE's jet line: jetRatio of the bore period, integer.
        val jetN = (jetRatio * rate / f0).toInt().coerceAtLeast(1)
        val jet = FloatArray(jetN + 1)
        var jw = 0
        val jetOffset = Dsp.lin(lip, 0f, JET_OFFSET_MAX)

        val total = (seconds * rate).toInt()
        val attackN = (ATTACK_S * rate).toInt()
        val releaseN = (RELEASE_S * rate).toInt()
        val raw = FloatArray(total)
        for (i in 0 until total) {
            val env = when {
                i < attackN -> i.toFloat() / attackN
                i > total - releaseN -> (total - i).toFloat() / releaseN
                else -> 1f
            }
            val pm = pMax * env * (1f + TURB * noise.next())
            // Returning wave: the integer tap, the tuning allpass, the bell one-pole, the reflection.
            val d = line[(w - n + size) % size]
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            var ret = bell.lp(g * tuned, bellHz)
            if (shape != Shape.CYLINDER && dcHz > 0f) ret -= dcLp.lp(ret, dcHz)
            val out = if (shape == Shape.FLUTE) {
                // Jet: the returning wave deflects the jet, the disturbance convects
                // across the mouth (jet delay), the labium saturates it; pressure is
                // the jet's INPUT GAIN, the offset is geometry (LIP).
                val jetIn = -JET_REFLECTION * ret
                val jetOut = jet[(jw - jetN + jet.size) % jet.size]
                jet[jw] = jetIn
                jw = (jw + 1) % jet.size
                val labium = tanh((JET_GAIN_PER_P * pm * jetOut + jetOffset).toDouble()).toFloat()
                labium * pm.coerceAtMost(1f) + END_REFLECTION * ret
            } else {
                // Smith's reed reflection table (STK Clarinet): passive by construction.
                val delta = ret - pm
                val r = (offset + REED_SLOPE * delta).coerceIn(-1f, 1f)
                pm + delta * r
            }
            line[w] = out
            w = (w + 1) % size
            raw[i] = out
        }

        val work = raw.copyOf()
        Tide.bandLimit(work, rate)
        val dec = Dsp.decimate(work, Dsp.RATE)
        val beforeDc = dec.copyOf()
        // The DC goes twice: subtract the mean, then a 20 Hz one-pole high-pass.
        var mean = 0.0
        for (v in dec) mean += v
        val m = (mean / dec.size).toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in dec.indices) {
            val x = dec[i] - m
            dec[i] = x - hp.lp(x, OUT_DC_HZ)
        }
        val beforeLevel = dec.copyOf()
        Dsp.levelTo(dec, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(dec)
        val t1 = System.nanoTime()
        return Result(Snip(dec, channels = 1, sampleRate = Dsp.RATE), raw, beforeDc, beforeLevel, tuning, filterDelay, dcDelay, t1 - t0, pMax)
    }

    /** Small-signal sanity used by the test's printout: RMS of [s] over [from, to). */
    fun rms(s: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var acc = 0.0
        for (i in from until to) acc += s[i].toDouble() * s[i]
        return sqrt(acc / (to - from)).toFloat()
    }
}
```

## `BoreSpikeTest.kt`

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SPIKE MEASUREMENTS for the corrected BORE loop (BoreSpike). Prints; the
 * only assertions guard against NaN so a failure is data, not a red test.
 * Measurement idioms copied from TuningAccuracyTest / ForkTest / BoreProbeTest.
 */
class BoreSpikeTest {

    private val rate = Dsp.RATE

    // ---- helpers ----------------------------------------------------------------

    private fun magnitudes(samples: FloatArray, start: Int, n: Int): FloatArray {
        val re = FloatArray(n) { i ->
            val w = 0.35875 - 0.48829 * cos(2 * PI * i / (n - 1)) + 0.14128 * cos(4 * PI * i / (n - 1)) - 0.01168 * cos(6 * PI * i / (n - 1))
            (samples.getOrElse(start + i) { 0f } * w).toFloat()
        }
        val im = FloatArray(n)
        Fft.forward(re, im)
        return FloatArray(n / 2) { b -> (re[b] * re[b] + im[b] * im[b]) }
    }

    private fun midStart(s: FloatArray) = (s.size * 0.25f).toInt()
    private fun midLen(s: FloatArray) = (s.size * 0.5f).toInt()

    private fun fftSize(len: Int): Int { var n = 1024; while (n < len) n *= 2; return max(n, 65536) }

    private fun midSpectrum(s: FloatArray): Pair<FloatArray, Int> {
        val n = fftSize(midLen(s))
        return magnitudes(s, midStart(s), n) to n
    }

    private fun interp(mag: FloatArray, best: Int, n: Int): Float {
        val a = ln(mag[best - 1].toDouble() + 1e-30)
        val c = ln(mag[best].toDouble() + 1e-30)
        val d = ln(mag[best + 1].toDouble() + 1e-30)
        val denom = a - 2 * c + d
        val delta = if (abs(denom) > 1e-12) 0.5 * (a - d) / denom else 0.0
        return ((best + delta) * rate / n).toFloat()
    }

    /** Global peak above [minHz] with parabolic interpolation (BoreProbeTest idiom). */
    private fun globalPeakHz(s: FloatArray, minHz: Float = 20f): Float {
        val (mag, n) = midSpectrum(s)
        val lo = max(1, (minHz * n / rate).toInt())
        var best = lo
        for (b in lo until mag.size - 1) if (mag[b] > mag[best]) best = b
        return interp(mag, best, n)
    }

    /** Loudest peak within one semitone of [wantHz], parabolic (TuningAccuracyTest.measuredHz idiom). */
    private fun nearPeakHz(s: FloatArray, wantHz: Float): Float {
        val (mag, n) = midSpectrum(s)
        val binHz = rate.toFloat() / n
        val radius = max(1, (wantHz * 0.059f / binHz).toInt())
        val center = (wantHz / binHz).toInt()
        var best = center
        for (b in max(1, center - radius)..min(mag.size - 2, center + radius)) if (mag[b] > mag[best]) best = b
        return interp(mag, best, n)
    }

    /** Peak power at k*f0 within +/-3 %, k = 1..count, as dB relative to h1. */
    private fun harmonicsDb(s: FloatArray, f0: Float, count: Int): FloatArray {
        val (mag, n) = midSpectrum(s)
        val p = FloatArray(count) { k ->
            val target = f0 * (k + 1)
            val lo = max(1, (target * 0.97f * n / rate).toInt())
            val hi = min(mag.size - 1, (target * 1.03f * n / rate).toInt())
            var best = 0f
            for (b in lo..hi) best = max(best, mag[b])
            best
        }
        return FloatArray(count) { k -> (10.0 * log10((p[k] + 1e-30) / (p[0] + 1e-30))).toFloat() }
    }

    private fun oddEvenDb(h: FloatArray): Float {
        // magnitudes (sqrt power) from the dB-re-h1 values
        fun lin(db: Float) = Math.pow(10.0, db / 20.0)
        val odd = lin(h[0]) + lin(h[2]) + lin(h[4]) + lin(h[6])
        val even = lin(h[1]) + lin(h[3]) + lin(h[5]) + lin(h[7])
        return (20.0 * log10(odd / even)).toFloat()
    }

    private fun cents(measured: Float, expected: Float): Float =
        if (measured <= 0f || expected <= 0f) Float.NaN else (1200.0 * log2(measured / expected)).toFloat()

    private fun peak(s: FloatArray): Float { var p = 0f; for (v in s) p = max(p, abs(v)); return p }
    private fun nonFinite(s: FloatArray): Int = s.count { !it.isFinite() }
    private fun meanOf(s: FloatArray, from: Int, to: Int): Float { var a = 0.0; for (i in from until to) a += s[i]; return (a / (to - from)).toFloat() }

    /** Raw-loop AC RMS over the middle 50 % with its own DC removed. */
    private fun rawAcRms(raw: FloatArray): Pair<Float, Float> {
        val ms = midStart(raw); val ml = midLen(raw)
        val dc = meanOf(raw, ms, ms + ml)
        var acc = 0.0
        for (i in ms until ms + ml) { val d = raw[i] - dc; acc += d * d }
        return dc to sqrt(acc / ml).toFloat()
    }

    private fun pitchStr(snip: Snip): String {
        val p = Pitch.detect(snip, fromSec = 0.25f, windowSec = 0.5f)
        return if (p == null) "null" else "%.2f(c%.2f)".format(p.hz, p.confidence)
    }

    private fun shapeName(s: BoreSpike.Shape) = s.name.padEnd(8)

    // ---- (1) pitch, series, DC, level ----------------------------------------------

    private fun fullRow(shape: BoreSpike.Shape, f0: Float, breath: Float = 0.65f, lip: Float = 0.5f) {
        BoreSpike.render(shape, f0, breath, lip) // warm
        val r = BoreSpike.render(shape, f0, breath, lip)
        val s = r.snip.samples
        val nf = nonFinite(s) + nonFinite(r.raw)
        val (rawDc, rawAc) = rawAcRms(r.raw)
        val bd = r.beforeDc
        val beforeDcMean = meanOf(bd, midStart(bd), midStart(bd) + midLen(bd))
        val finalMean = s.average().toFloat()
        val near = if (nf == 0) nearPeakHz(s, f0) else Float.NaN
        val global = if (nf == 0) globalPeakHz(s) else Float.NaN
        val h = if (nf == 0) harmonicsDb(s, f0, 8) else FloatArray(8) { Float.NaN }
        val loud = Loudness.of(r.snip)
        val msPerSec = r.renderNanos / 1e6 / r.snip.durationSeconds
        println(
            "P1 | %s | f0 %7.2f | exact %9.3f n %5d a %.4f | tauLP %6.3f tauDC %+7.3f | nearPk %8.3f (%+6.1f c) | globalPk %8.2f (%+7.1f c) | Pitch %s | nonFinite %d | rawDC %+.4f rawAC %.4f | preDC mean %+.4f | final mean %+.5f | peak %.3f | loud %.4f | %.1f ms/s".format(
                shapeName(shape), f0, r.tuning.exact, r.tuning.n, r.tuning.a, r.filterDelay, r.dcDelay,
                near, cents(near, f0), global, cents(global, f0), pitchStr(r.snip), nf, rawDc, rawAc, beforeDcMean, finalMean, peak(s), loud, msPerSec,
            ),
        )
        println("H1 | %s | f0 %7.2f | h1..h8 dB re h1: %s | odd/even %+.1f dB".format(shapeName(shape), f0, h.joinToString(" ") { "%+6.1f".format(it) }, if (nf == 0) oddEvenDb(h) else Float.NaN))
        assertTrue(nf == 0, "$shape at $f0 Hz produced $nf non-finite samples")
    }

    @Test
    fun `1 - pitch series DC level at BREATH 0_65 LIP 0_5`() {
        println("=== (1) CYLINDER 110/220/440, CONE 55/110/220 at BREATH 0.65, LIP 0.5 ===")
        for (f0 in listOf(110f, 220f, 440f)) fullRow(BoreSpike.Shape.CYLINDER, f0)
        for (f0 in listOf(55f, 110f, 220f)) fullRow(BoreSpike.Shape.CONE, f0)
    }

    // ---- (2) corner sweep -------------------------------------------------------

    @Test
    fun `2 - BREATH x LIP corners`() {
        println("=== (2) CORNERS: shape | f0 | BREATH | LIP | finite | rawDC | rawAC(mid50) | osc(>0.01) | nearPk cents | globalPk Hz | Pitch | final peak | loud ===")
        for ((shape, f0) in listOf(BoreSpike.Shape.CYLINDER to 220f, BoreSpike.Shape.CONE to 110f)) {
            for (breath in listOf(0.15f, 0.3f, 0.5f, 0.65f, 0.85f, 1.0f)) {
                for (lip in listOf(0f, 0.5f, 1f)) {
                    val r = BoreSpike.render(shape, f0, breath, lip)
                    val s = r.snip.samples
                    val nf = nonFinite(s) + nonFinite(r.raw)
                    val (rawDc, rawAc) = rawAcRms(r.raw)
                    val osc = rawAc > 0.01f
                    val near = if (nf == 0 && osc) nearPeakHz(s, f0) else Float.NaN
                    val global = if (nf == 0 && osc) globalPeakHz(s) else Float.NaN
                    println(
                        "C2 | %s | %5.0f | %.2f | %.1f | %s | %+.4f | %.4f | %s | %+7.1f | %8.2f | %s | %.3f | %.4f".format(
                            shapeName(shape), f0, breath, lip, if (nf == 0) "yes" else "NO($nf)", rawDc, rawAc, if (osc) "osc" else "---",
                            cents(near, f0), global, pitchStr(r.snip), peak(s), Loudness.of(r.snip),
                        ),
                    )
                    assertTrue(nf == 0, "$shape BREATH $breath LIP $lip produced $nf non-finite samples")
                }
            }
        }
    }

    // ---- (3) threshold ----------------------------------------------------------

    @Test
    fun `3 - BREATH threshold at LIP 0_5`() {
        println("=== (3) THRESHOLD: shape | f0 | BREATH | rawAC | osc | nearPk cents | Pitch ===")
        for ((shape, f0) in listOf(BoreSpike.Shape.CYLINDER to 220f, BoreSpike.Shape.CONE to 110f)) {
            var first = -1f
            var b = 0.05f
            while (b <= 1.0001f) {
                val r = BoreSpike.render(shape, f0, b, 0.5f)
                val (_, rawAc) = rawAcRms(r.raw)
                val osc = rawAc > 0.01f
                if (osc && first < 0f) first = b
                val near = if (osc) nearPeakHz(r.snip.samples, f0) else Float.NaN
                println("T3 | %s | %5.0f | %.2f | pm %.3f | %.4f (%.3f of pm) | %s | %+7.1f | %s".format(shapeName(shape), f0, b, r.pMax, rawAc, rawAc / r.pMax, if (osc) "osc" else "---", cents(near, f0), pitchStr(r.snip)))
                b += 0.05f
            }
            println("T3 | %s | %5.0f | first oscillating BREATH = %.2f".format(shapeName(shape), f0, first))
        }
    }

    // ---- (4) centroid monotonicity ---------------------------------------------

    @Test
    fun `4 - centroid vs BREATH and vs LIP`() {
        println("=== (4) CENTROID: shape | f0 | BREATH | LIP | centroidHz | rolloffHz | rawAC | nearPk cents ===")
        for ((shape, f0) in listOf(BoreSpike.Shape.CYLINDER to 220f, BoreSpike.Shape.CONE to 110f)) {
            val vsBreath = ArrayList<Float>()
            for (breath in listOf(0.15f, 0.3f, 0.5f, 0.65f, 0.85f, 1.0f)) {
                val r = BoreSpike.render(shape, f0, breath, 0.5f)
                val f = FeatureExtractor.extract(Snip(r.snip.samples.copyOfRange(midStart(r.snip.samples), r.snip.samples.size), 1, rate))
                val (_, rawAc) = rawAcRms(r.raw)
                vsBreath += f.centroidHz
                println("M4 | %s | %5.0f | %.2f | %.2f | %8.1f | %8.1f | %.4f | %+7.1f".format(shapeName(shape), f0, breath, 0.5f, f.centroidHz, f.rolloffHz, rawAc, cents(if (rawAc > 0.01f) nearPeakHz(r.snip.samples, f0) else Float.NaN, f0)))
            }
            println("M4 | %s | centroid vs BREATH monotonic non-decreasing: %s".format(shapeName(shape), vsBreath.zipWithNext().all { it.second >= it.first }))
            val vsLip = ArrayList<Float>()
            for (lip in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val r = BoreSpike.render(shape, f0, 0.65f, lip)
                val f = FeatureExtractor.extract(Snip(r.snip.samples.copyOfRange(midStart(r.snip.samples), r.snip.samples.size), 1, rate))
                val (_, rawAc) = rawAcRms(r.raw)
                vsLip += f.centroidHz
                println("M4 | %s | %5.0f | %.2f | %.2f | %8.1f | %8.1f | %.4f | %+7.1f".format(shapeName(shape), f0, 0.65f, lip, f.centroidHz, f.rolloffHz, rawAc, cents(if (rawAc > 0.01f) nearPeakHz(r.snip.samples, f0) else Float.NaN, f0)))
            }
            println("M4 | %s | centroid vs LIP monotonic: non-decreasing %s / non-increasing %s".format(shapeName(shape), vsLip.zipWithNext().all { it.second >= it.first }, vsLip.zipWithNext().all { it.second <= it.first }))
        }
    }

    // ---- (6) CONE DC-blocker trade-off ------------------------------------------

    private fun coneRow(tag: String, f0: Float, breath: Float, lip: Float, dcHz: Float) {
        val r = BoreSpike.render(BoreSpike.Shape.CONE, f0, breath, lip, dcHz = dcHz)
        val s = r.snip.samples
        val nf = nonFinite(s) + nonFinite(r.raw)
        val (rawDc, rawAc) = rawAcRms(r.raw)
        val osc = nf == 0 && rawAc > 0.01f
        val near = if (osc) nearPeakHz(s, f0) else Float.NaN
        val global = if (osc) globalPeakHz(s) else Float.NaN
        val h = if (osc) harmonicsDb(s, f0, 6) else FloatArray(6) { Float.NaN }
        // 2nd mode's own tuning: loudest peak within a semitone of 2*f0, in cents from 2*f0
        val mode2 = if (osc) cents(nearPeakHz(s, 2 * f0), 2 * f0) else Float.NaN
        // sub-audio content: energy below 40 Hz vs the total, mid-50 %, in dB
        val (mag, n) = midSpectrum(s)
        var lo = 0.0; var all = 0.0
        for (b in 1 until mag.size) { val hz = b.toFloat() * rate / n; if (hz < 40f) lo += mag[b]; all += mag[b] }
        println(
            "D6 | %-8s | f0 %5.0f | B %.2f L %.2f | dcHz %5.2f tauDC %+8.3f | finite %s | rawDC %+.4f rawAC %.4f | %s | f0 %+7.1f c | mode2 %+7.1f c | globalPk %8.2f | sub40Hz %+6.1f dB | peak %.3f | Pitch %s | h1..h6 %s".format(
                tag, f0, breath, lip, dcHz, r.dcDelay, nf == 0, rawDc, rawAc, if (osc) "osc" else "---", cents(near, f0), mode2, global,
                10 * log10(lo / all + 1e-30), peak(s), pitchStr(r.snip), h.joinToString(" ") { "%+6.1f".format(it) },
            ),
        )
        assertTrue(nf == 0, "CONE $tag at $f0 produced $nf non-finite samples")
    }

    @Test
    fun `6 - CONE in-loop DC blocker fixed vs f0-relative vs none`() {
        println("=== (6) CONE DC blocker: 20 Hz (iter 1) | 2 Hz (iter 2) | f0/25 | none, at 55/110/220, BREATH 0.65 LIP 0.5 ===")
        for (f0 in listOf(55f, 110f, 220f)) {
            coneRow("20Hz", f0, 0.65f, 0.5f, 20f)
            coneRow("2Hz", f0, 0.65f, 0.5f, 2f)
            coneRow("f0/25", f0, 0.65f, 0.5f, f0 / 25f)
            coneRow("none", f0, 0.65f, 0.5f, 0f)
        }
        println("=== (6b) CONE 110 corners BREATH {0.3,0.5,0.65,0.85,1.0} x LIP {0,0.5,1} for f0/25 and none ===")
        for (mode in listOf("f0/25" to 110f / 25f, "none" to 0f)) {
            for (breath in listOf(0.3f, 0.5f, 0.65f, 0.85f, 1.0f)) for (lip in listOf(0f, 0.5f, 1f)) coneRow(mode.first, 110f, breath, lip, mode.second)
        }
    }

    // ---- (5) FLUTE best effort ---------------------------------------------------

    @Test
    fun `5 - FLUTE best effort`() {
        println("=== (5) FLUTE 220/440/880 at BREATH 0.65, LIP 0.5 ===")
        for (f0 in listOf(220f, 440f, 880f)) {
            try { fullRow(BoreSpike.Shape.FLUTE, f0) } catch (e: AssertionError) { println("P1 | FLUTE %5.0f | %s".format(f0, e.message)) }
        }
        println("=== (5a) FLUTE jet ratio 0.32 / 0.40 / 0.50 at 220/440/880, BREATH 0.65 LIP 0.5: nearPk cents | globalPk Hz | h1..h5 dB ===")
        for (ratio in listOf(0.32f, 0.40f, 0.50f)) {
            for (f0 in listOf(220f, 440f, 880f)) {
                val r = BoreSpike.render(BoreSpike.Shape.FLUTE, f0, 0.65f, 0.5f, jetRatio = ratio)
                val s = r.snip.samples
                val (_, rawAc) = rawAcRms(r.raw)
                val osc = rawAc > 0.01f && nonFinite(r.raw) == 0
                val h = if (osc) harmonicsDb(s, f0, 5) else FloatArray(5) { Float.NaN }
                println("J5 | ratio %.2f | f0 %5.0f | rawAC %.4f | %s | nearPk %+7.1f c | globalPk %8.2f | h: %s | Pitch %s".format(
                    ratio, f0, rawAc, if (osc) "osc" else "---", cents(if (osc) nearPeakHz(s, f0) else Float.NaN, f0), if (osc) globalPeakHz(s) else Float.NaN,
                    h.joinToString(" ") { "%+6.1f".format(it) }, pitchStr(r.snip)))
            }
        }
        println("=== (5b) FLUTE 440 BREATH sweep at LIP 0.5 ===")
        for (breath in listOf(0.15f, 0.3f, 0.5f, 0.65f, 0.85f, 1.0f)) {
            val r = BoreSpike.render(BoreSpike.Shape.FLUTE, 440f, breath, 0.5f)
            val (rawDc, rawAc) = rawAcRms(r.raw)
            val osc = rawAc > 0.01f
            println("F5 | FLUTE 440 | BREATH %.2f | finite %s | rawDC %+.4f rawAC %.4f | %s | nearPk %+7.1f c | globalPk %8.2f | Pitch %s".format(
                breath, nonFinite(r.raw) == 0, rawDc, rawAc, if (osc) "osc" else "---",
                cents(if (osc) nearPeakHz(r.snip.samples, 440f) else Float.NaN, 440f), if (osc) globalPeakHz(r.snip.samples) else Float.NaN, pitchStr(r.snip)))
        }
    }
}
```

## `bore_sim.py`

```python
#!/usr/bin/env python3
"""Pure-Python replica of the BORE spec's render loop (section 4, Bore.render),
minus MELLO / Tide.bandLimit / Punch / decimate, so the loop's own pitch,
DC and boundedness can be measured. Double precision instead of Float32;
qualitative behaviour (divergence, fixed points, resonance) is the same.
"""
import math, cmath, sys

RATE = 44100
OVERSAMPLE = 4

def expmap(m, lo, hi):
    m = min(max(m, 0.0), 1.0)
    return lo * math.exp(math.log(hi / lo) * m)

def lin(m, lo, hi):
    m = min(max(m, 0.0), 1.0)
    return lo + (hi - lo) * m

class Noise:
    def __init__(self, seed):
        self.state = 1 if seed == 0 else seed
    def next(self):
        self.state = (self.state * 1103515245 + 12345) & 0x7fffffff
        return (self.state / 0x3fffffff) - 1.0

class OnePole:
    def __init__(self, rate):
        self.rate = rate; self.state = 0.0
    def lp(self, x, cutoff):
        a = 1.0 - math.exp(-2.0 * math.pi * min(cutoff, self.rate * 0.45) / self.rate)
        self.state += a * (x - self.state)
        return self.state

class Biquad:
    def __init__(self):
        self.b0=1;self.b1=0;self.b2=0;self.a1=0;self.a2=0
        self.x1=self.x2=self.y1=self.y2=0.0
    def bandpass(self, f0, q, rate):
        w0 = 2*math.pi*f0/rate; cw=math.cos(w0); sw=math.sin(w0)
        alpha = sw/(2*q)
        a0 = 1+alpha
        self.b0 = alpha/a0; self.b1 = 0.0; self.b2 = -alpha/a0
        self.a1 = -2*cw/a0; self.a2 = (1-alpha)/a0
    def process(self, x):
        y = self.b0*x + self.b1*self.x1 + self.b2*self.x2 - self.a1*self.y1 - self.a2*self.y2
        self.x2=self.x1; self.x1=x; self.y2=self.y1; self.y1=y
        return y

ROOT = {"BARI":55.0,"BASSOON":58.27,"HECKEL":65.41,"FLUTE":220.0,"GUANZI":110.0}

def render(voice, tune=0.5, breath=0.65, embouchure=0.5, vibrato=0.0, seconds=0.6, variant="spec"):
    rr = RATE*OVERSAMPLE
    semis = round(tune*24)
    f0 = ROOT[voice]*2**(semis/12)
    total = int(seconds*rr)
    out = [0.0]*total
    prng = Noise(12345)
    cyl = voice == "GUANZI"; flute = voice == "FLUTE"
    if variant == "spec":
        delay = rr/(4.0*f0) if cyl else rr/(2.0*f0)
    else:  # corrected lengths: half period for closed cylinder, full period for the cone/open pipe
        delay = rr/(2.0*f0) if cyl else rr/f0
    maxDelay = int(delay*2)+64
    line = [0.0]*maxDelay; w = 0
    jetD = max(int(delay*0.48), 2); jet = [0.0]*(jetD+16); jw = 0
    bell = OnePole(rr)
    bellCut = {"BARI":expmap(embouchure,1800,6500),"BASSOON":expmap(embouchure,1400,5000),
               "HECKEL":expmap(embouchure,1100,4200),"FLUTE":expmap(embouchure,4000,12000),
               "GUANZI":expmap(embouchure,1600,5500)}[voice]
    bulb = Biquad(); bulb.bandpass(140.0,2.5,rr)
    attackF = int(0.040*rr); releaseF = int(0.080*rr)
    vibF = 5.2/rr; vibDelayF = int(0.060*rr)
    pClosing = lin(embouchure,0.4,1.6)
    diverged_at = None
    for n in range(total):
        if n < attackF: env = n/attackF
        elif n > total-releaseF: env = (total-n)/releaseF
        else: env = 1.0
        env *= breath
        vibAmp = min((n-vibDelayF)/(rr*0.2),1.0)*vibrato*0.015 if n > vibDelayF else 0.0
        vibOff = math.sin(2*math.pi*vibF*n)*vibAmp
        cur = delay*(1.0+vibOff)
        rd = w - cur
        r0 = int(rd)            # Kotlin toInt(): truncation toward zero, as in the spec
        while r0 < 0: r0 += maxDelay
        r0 %= maxDelay
        r1 = (r0+1) % maxDelay
        frac = rd - int(rd)
        refl = line[r0] + (line[r1]-line[r0])*frac
        deltaP = env*1.2 - refl
        if flute:
            jr = (jw - jetD + len(jet)) % len(jet)
            js = jet[jr]
            turb = prng.next()*0.12*env
            exc = math.tanh(js + env*0.8 + turb)
            jet[jw] = deltaP; jw = (jw+1) % len(jet)
        else:
            turb = prng.next()*0.035*env
            p = deltaP + turb
            if p < pClosing:
                ap = max(1.0 - p/pClosing, 0.0)
                sg = 1.0 if p >= 0 else -1.0
                flow = sg*ap*math.sqrt(abs(p))
            else:
                flow = 0.0
            if voice == "BARI": exc = flow*1.35
            elif voice in ("BASSOON","HECKEL"): exc = flow*flow*1.5
            elif voice == "GUANZI": exc = flow*1.15
            else: exc = flow
        sign = -1.0 if cyl else 1.0
        try:
            bs = bell.lp(exc + refl*sign*0.94, bellCut)
        except OverflowError:
            bs = float('inf')
        o = bs
        if voice == "HECKEL":
            o += bulb.process(bs)*0.45
        if not math.isfinite(o):
            diverged_at = n
            break
        line[w] = o; w = (w+1) % maxDelay
        out[n] = o
    return f0, out, diverged_at

def fft(x):
    n = len(x)
    if n == 1: return x
    even = fft(x[0::2]); odd = fft(x[1::2])
    t = [cmath.exp(-2j*math.pi*k/n)*odd[k] for k in range(n//2)]
    return [even[k]+t[k] for k in range(n//2)] + [even[k]-t[k] for k in range(n//2)]

def spectrum(sig, rate, start_s, len_s, nfft=32768):
    seg = sig[int(start_s*rate):int((start_s+len_s)*rate)]
    m = len(seg)
    mean = sum(seg)/m
    win = [(0.5-0.5*math.cos(2*math.pi*i/(m-1)))*(seg[i]-mean) for i in range(m)]
    while nfft < m: nfft *= 2
    x = [complex(v,0) for v in win] + [0j]*(nfft-m)
    X = fft(x)
    mag = [abs(X[k]) for k in range(nfft//2)]
    return mag, rate/nfft

def peak_near(mag, binhz, hz, radius_semis=0.5):
    r = max(1, int(hz*(2**(radius_semis/12)-1)/binhz))
    c = int(hz/binhz)
    best = max(range(max(1,c-r), min(len(mag)-2, c+r)+1), key=lambda b: mag[b])
    a,b2,c2 = mag[best-1],mag[best],mag[best+1]
    den = a-2*b2+c2
    d = 0.5*(a-c2)/den if den != 0 else 0.0
    return (best+d)*binhz, b2

def cents(a,b): return 1200*math.log2(a/b)

def analyse(voice, **kw):
    rr = RATE*OVERSAMPLE
    f0, y, div = render(voice, **kw)
    if div is not None:
        print(f"{voice:8s} f0={f0:7.2f}  DIVERGED to inf/NaN at sample {div} ({div/rr*1000:.1f} ms)")
        return
    body = y[int(0.2*rr):int(0.55*rr)]
    peak = max(abs(v) for v in y)
    dc = sum(body)/len(body)
    ac = math.sqrt(sum((v-dc)**2 for v in body)/len(body))
    mag, binhz = spectrum(y, rr, 0.2, 0.35)
    # strongest bin under 6 kHz
    top = max(range(1, int(6000/binhz)), key=lambda b: mag[b])
    fpk = top*binhz
    fpk_i, _ = peak_near(mag, binhz, fpk, 0.3)
    # lowest peak that is within 20 dB of the strongest, above 30 Hz
    thr = mag[top]/10.0
    lowest = None
    for b in range(int(30/binhz), top+1):
        if mag[b] > thr and mag[b] >= mag[b-1] and mag[b] >= mag[b+1]:
            lowest = b; break
    flow_hz = peak_near(mag, binhz, lowest*binhz, 0.3)[0] if lowest else fpk_i
    print(f"{voice:8s} f0={f0:7.2f} peak|y|={peak:8.3f} DC={dc:8.4f} ACrms={ac:8.4f} "
          f"strongest={fpk_i:8.2f} Hz ({cents(fpk_i,f0):+7.0f} c vs f0, {cents(fpk_i,2*f0):+7.0f} c vs 2f0) "
          f"lowest-in-20dB={flow_hz:8.2f} Hz ({cents(flow_hz,f0):+6.0f} c vs f0)")
    base = flow_hz
    harm = []
    for k in range(1,7):
        _, m = peak_near(mag, binhz, base*k, 0.3)
        harm.append(20*math.log10(m/mag[top]+1e-12))
    print("          harmonics of lowest (dB re strongest): " + " ".join(f"h{k+1}={harm[k]:6.1f}" for k in range(6)))

if __name__ == "__main__":
    variant = sys.argv[1] if len(sys.argv) > 1 else "spec"
    vib = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    print(f"variant={variant} vibrato={vib}")
    for v in ["BARI","BASSOON","HECKEL","FLUTE","GUANZI"]:
        analyse(v, vibrato=vib, variant=variant)
```

## `bore_sim2.py`

```python
#!/usr/bin/env python3
"""Second pass: (a) the spec loop with a proper peak list; (b) a corrected
loop (STK-style reed reflection table, half-period/full-period delays,
bell LP phase budgeted, DC blocker on the +1 loops, floor-based fractional
read) to show where f0 lands once the delay and sign are right; (c) the
bell filter's phase-delay budget per voice, in samples and cents."""
import math, cmath, sys
from bore_sim import RATE, OVERSAMPLE, expmap, lin, Noise, OnePole, Biquad, ROOT, fft

RR = RATE*OVERSAMPLE

def onepole_delay(cutoff, f, rate):
    a = 1.0 - math.exp(-2*math.pi*min(cutoff, rate*0.45)/rate)
    r = 1.0 - a
    w = 2*math.pi*f/rate
    ph = -math.atan2(r*math.sin(w), 1 - r*math.cos(w))
    return -ph/w

def dc_delay(f, rate, hz=20.0):
    # one-pole high-pass (input minus one-pole low-pass at hz): phase lead at f
    a = 1.0 - math.exp(-2*math.pi*hz/rate); r = 1 - a
    w = 2*math.pi*f/rate
    ph = math.atan2(math.sin(w), 1 - math.cos(w)) - math.atan2(r*math.sin(w), 1 - r*math.cos(w))
    return -ph/w

BELL = {"BARI":(1800,6500),"BASSOON":(1400,5000),"HECKEL":(1100,4200),"FLUTE":(4000,12000),"GUANZI":(1600,5500)}

def render_fixed(voice, tune=0.5, breath=0.65, embouchure=0.5, seconds=0.6):
    semis = round(tune*24); f0 = ROOT[voice]*2**(semis/12)
    total = int(seconds*RR); out = [0.0]*total
    prng = Noise(12345)
    cyl = voice == "GUANZI"; flute = voice == "FLUTE"
    bellCut = expmap(embouchure, *BELL[voice])
    tauLp = onepole_delay(bellCut, f0, RR)
    if cyl:
        period_frac = 0.5; g = -0.95; dcb = False
    else:
        period_frac = 1.0; g = +0.95; dcb = True
    exact = RR/f0*period_frac - tauLp - (dc_delay(f0, RR) if dcb else 0.0)
    D = math.floor(exact); frac = exact - D   # integer + linear-interp fraction (an allpass would be exact)
    maxDelay = D + 8
    line = [0.0]*maxDelay; w = 0
    bell = OnePole(RR)
    dcLp = OnePole(RR)
    jetD = max(int(RR/f0*0.32), 2); jet = [0.0]*(jetD+4); jw = 0
    attackF = int(0.040*RR); releaseF = int(0.080*RR)
    slope = -lin(embouchure, 0.2, 0.45)   # EMBOUCHURE as reed stiffness (STK Clarinet: offset 0.7, slope -0.3)
    for n in range(total):
        if n < attackF: env = n/attackF
        elif n > total-releaseF: env = (total-n)/releaseF
        else: env = 1.0
        P = env*breath
        # read D + frac samples back, floor-based
        i0 = (w - D - 1) % maxDelay; i1 = (w - D) % maxDelay
        # value at delay D+frac = x[n-D-frac] = lerp(x[n-D], x[n-D-1], frac)
        ret = line[i1] + (line[i0] - line[i1])*frac
        ret = bell.lp(g*ret, bellCut)
        if dcb:
            ret = ret - dcLp.lp(ret, 20.0)
        if flute:
            turb = prng.next()*0.04*P
            pd = P*(1+turb) - 0.5*ret
            jr = (jw - jetD) % len(jet); js = jet[jr]; jet[jw] = pd; jw = (jw+1) % len(jet)
            jt = js*(js*js - 1.0); jt = max(-1.0, min(1.0, jt))
            y = jt + 0.5*ret
        else:
            turb = prng.next()*0.02*P
            Pn = P*(1+turb)
            pd = ret - Pn
            r = max(-1.0, min(1.0, 0.7 + slope*pd))
            y = Pn + pd*r
        line[w] = y; w = (w+1) % maxDelay
        out[n] = y
    return f0, out

def peaks(sig, start_s, len_s, fmax=3000, nfft=65536, count=8):
    seg = sig[int(start_s*RR):int((start_s+len_s)*RR)]
    m = len(seg); mean = sum(seg)/m
    win = [(0.5-0.5*math.cos(2*math.pi*i/(m-1)))*(seg[i]-mean) for i in range(m)]
    while nfft < m: nfft *= 2
    X = fft([complex(v,0) for v in win] + [0j]*(nfft-m))
    binhz = RR/nfft
    mag = [abs(X[k]) for k in range(int(fmax/binhz)+2)]
    top = max(mag[1:])
    found = []
    for b in range(2, len(mag)-1):
        if mag[b] > mag[b-1] and mag[b] >= mag[b+1] and mag[b] > top*0.01:
            a,b2,c = mag[b-1],mag[b],mag[b+1]; den = a-2*b2+c
            d = 0.5*(a-c)/den if den else 0.0; d = max(-0.5, min(0.5, d))
            found.append(((b+d)*binhz, 20*math.log10(mag[b]/top)))
    found.sort(key=lambda t: -t[1])
    return sorted(found[:count]), mean

def cents(a,b): return 1200*math.log2(a/b)

def report(name, f0, y):
    pk = max(abs(v) for v in y)
    body = y[int(0.2*RR):int(0.55*RR)]
    dc = sum(body)/len(body); ac = math.sqrt(sum((v-dc)**2 for v in body)/len(body))
    ps, _ = peaks(y, 0.2, 0.35)
    lowest = ps[0][0] if ps else float('nan')
    print(f"{name:8s} f0={f0:7.2f}  peak={pk:7.3f} DC={dc:7.4f} ACrms={ac:7.4f}  lowest peak {lowest:8.2f} Hz = {cents(lowest,f0):+7.1f} c vs f0")
    print("          peaks (Hz, dB re strongest): " + "  ".join(f"{f:7.1f}/{d:5.1f}" for f,d in ps))

if __name__ == "__main__":
    from bore_sim import render
    print("=== bell one-pole phase delay at f0 (spec's delay D = rate/(4f0) cyl, rate/(2f0) else), EMB 0 / 0.5 / 1 ===")
    for v in ROOT:
        for semis in (0, 12, 24):
            f0 = ROOT[v]*2**(semis/12)
            D = RR/(4*f0) if v == "GUANZI" else RR/(2*f0)
            row = []
            for emb in (0.0, 0.5, 1.0):
                tau = onepole_delay(expmap(emb, *BELL[v]), f0, RR)
                row.append(f"emb{emb:.1f}: tau={tau:5.1f} smp ({-cents(D/(D+tau),1):+6.1f} c)")
            print(f"  {v:8s} semi {semis:2d} f0={f0:7.2f} D={D:7.1f}   " + "   ".join(row))
    print()
    print("=== SPEC loop as written, defaults (VIBRATO 0), 0.6 s ===")
    for v in ROOT:
        f0, y, div = render(v, vibrato=0.0, variant="spec")
        if div is not None:
            print(f"{v:8s} f0={f0:7.2f}  DIVERGED (inf/NaN) at {div/RR*1000:.1f} ms")
        else:
            report(v, f0, y)
    print()
    print("=== SPEC loop, BREATH 1 / EMBOUCHURE 0 and BREATH 0.3 / EMBOUCHURE 1 corners ===")
    for v in ROOT:
        for br, emb in ((1.0, 0.0), (0.3, 1.0)):
            f0, y, div = render(v, breath=br, embouchure=emb, vibrato=0.0, variant="spec", seconds=0.4)
            tag = f"{v}@B{br}/E{emb}"
            if div is not None:
                print(f"{tag:22s} DIVERGED at {div/RR*1000:.1f} ms")
            else:
                pk = max(abs(s) for s in y); body = y[int(0.2*RR):int(0.35*RR)]; dc = sum(body)/len(body)
                print(f"{tag:22s} bounded: peak={pk:8.3f} DC={dc:7.3f}")
    print()
    print("=== CORRECTED loop (STK reed table, D = T/2 & -0.95 for the closed cylinder, D = T & +0.95 & DC blocker for cone/open pipe, LP phase budgeted) ===")
    for v in ROOT:
        for semis in (0, 12, 24):
            f0, y = render_fixed(v, tune=semis/24)
            report(f"{v}/{semis}", f0, y)
```

## `bore_thresh.py`

```python
#!/usr/bin/env python3
"""Threshold-of-oscillation probe on the CORRECTED reed loop of bore_sim2
(STK reflection table, |r|<=1). Turbulence is switchable so that self-
oscillation can be told apart from noise passively ringing the bore: with
turbulence 0 the loop is kicked once by a 1e-3 impulse and either grows to
a limit cycle (speaks) or dies (below threshold). Reports AC rms of the
last 100 ms, DC, and a zero-crossing pitch. Pure Python, double precision;
a claim about the arithmetic, not about a Kotlin build."""
import math, sys
from bore_sim import expmap, lin, Noise, OnePole, ROOT
from bore_sim2 import onepole_delay, dc_delay, BELL, RR

def render(voice, tune, breath, emb, turb=0.02, kick=1e-3, seconds=0.5):
    semis = round(tune*24); f0 = ROOT[voice]*2**(semis/12)
    total = int(seconds*RR); out = [0.0]*total
    prng = Noise(12345)
    cyl = voice == "GUANZI"
    bellCut = expmap(emb, *BELL[voice])
    tauLp = onepole_delay(bellCut, f0, RR)
    if cyl: pf, g, dcb = 0.5, -0.95, False
    else: pf, g, dcb = 1.0, +0.95, True
    exact = RR/f0*pf - tauLp - (dc_delay(f0, RR) if dcb else 0.0)
    D = math.floor(exact); frac = exact - D
    maxDelay = D + 8; line = [0.0]*maxDelay; w = 0
    bell = OnePole(RR); dcLp = OnePole(RR)
    attackF = int(0.040*RR)
    slope = -lin(emb, 0.2, 0.45)
    for n in range(total):
        env = min(1.0, n/attackF)
        P = env*breath
        i0 = (w - D - 1) % maxDelay; i1 = (w - D) % maxDelay
        ret = line[i1] + (line[i0] - line[i1])*frac
        ret = bell.lp(g*ret, bellCut)
        if dcb: ret = ret - dcLp.lp(ret, 20.0)
        Pn = P*(1 + prng.next()*turb)
        if n == attackF + 2000: Pn += kick
        pd = ret - Pn
        r = max(-1.0, min(1.0, 0.7 + slope*pd))
        y = Pn + pd*r
        line[w] = y; w = (w+1) % maxDelay; out[n] = y
    tail = out[int(0.4*RR):]
    dc = sum(tail)/len(tail)
    ac = math.sqrt(sum((v-dc)**2 for v in tail)/len(tail))
    # zero-crossing pitch on the DC-removed tail
    cr = []
    for i in range(1, len(tail)):
        p, q = tail[i-1]-dc, tail[i]-dc
        if p < 0 <= q: cr.append(i-1 + (-p/(q-p)))
    hz = float('nan')
    if len(cr) >= 3:
        per = sorted(cr[i]-cr[i-1] for i in range(1, len(cr)))
        hz = RR/per[len(per)//2]
    return f0, dc, ac, hz

if __name__ == "__main__":
    print("voice semi emb | BREATH: acRMS (turb on) / acRMS (turb off, kicked) / zc-Hz (off) / cents")
    for voice, semi in (("BARI",0),("BARI",12),("BARI",24),("BASSOON",12),("GUANZI",0),("GUANZI",12),("GUANZI",24)):
        for emb in (0.0, 0.5, 1.0):
            row = []
            for br in (0.2, 0.3, 0.4, 0.5, 0.65, 0.8, 1.0):
                f0, dc, ac_on, _ = render(voice, semi/24, br, emb, turb=0.02)
                _, dc2, ac_off, hz = render(voice, semi/24, br, emb, turb=0.0)
                c = 1200*math.log2(hz/f0) if hz == hz and hz > 0 else float('nan')
                row.append(f"{br:.2f}:{ac_on:.4f}/{ac_off:.4f}/{hz:6.1f}/{c:+6.1f}")
            print(f"{voice:7s} {semi:2d} {emb:.1f} | " + "  ".join(row))
```
